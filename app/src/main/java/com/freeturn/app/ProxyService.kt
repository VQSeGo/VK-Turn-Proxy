package com.freeturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.DnsMode
import com.freeturn.app.domain.server.KCP_FEC_VALUE
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.config.InetNetwork
import com.wireguard.config.InetEndpoint
import java.net.InetAddress

sealed class StartupResult {
    data object Success : StartupResult()
    data class Failed(val message: String) : StartupResult()
}

class ProxyService : Service() {

    companion object {
        const val MAX_RESTARTS = 8
        const val EXTRA_CORE_ONLY = "extra_core_only"
        const val ACTION_START_TUNNEL = "com.freeturn.app.ACTION_START_TUNNEL"
        private const val CHANNEL_PROXY = "ProxyChannel"
        private const val CHANNEL_CAPTCHA = "CaptchaChannel"
        private const val NOTIF_ID_FG = 1
        private const val NOTIF_ID_CAPTCHA = 2
        // Жёстко привязываемся к строке-объявлению капчи в бинарнике, чтобы
        // случайные localhost-URL в других логах не открывали диалог.
        // Новое ядро (manual_captcha.go) пишет "manually open this URL: <url>".
        // Старое — "Open this URL in your browser: <url>". Поддерживаем оба, чтобы
        // приложение работало с кастомным ядром, оставшимся у пользователя.
        private val CAPTCHA_URL_REGEX =
            Pattern.compile("""(?:manually open this URL|Open this URL in your browser):\s*(https?://\S+)""")

        // События жизненного цикла соединений, публикуемые ядром (client/main.go).
        // Не-VLESS: несколько потоков со своим [STREAM N], у каждого свой Established/Closed.
        private val STREAM_ESTABLISHED_REGEX =
            Pattern.compile("""\[STREAM (\d+)\] Established DTLS connection""")
        private val STREAM_CLOSED_REGEX =
            Pattern.compile("""\[STREAM (\d+)\] Closed DTLS connection""")
        // VLESS: ядро само пишет агрегированное число активных сессий.
        private val VLESS_ACTIVE_REGEX =
            Pattern.compile("""\[session \d+\] (?:connected|disconnected) \(active: (\d+)\)""")
        // VLESS: целевое число сессий приходит в этой строке до первого connected.
        private val VLESS_TOTAL_REGEX =
            Pattern.compile("""VLESS mode: waiting for sessions to connect \(total: (\d+)\)""")
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var openAppIntent: PendingIntent? = null

    private val process = AtomicReference<Process?>(null)
    private var wgBackend: GoBackend? = null
    private val turnTunnel = object : Tunnel {
        override fun getName(): String = "TurnVpn"
        override fun onStateChange(state: Tunnel.State) {
            ProxyServiceState.addLog("=== WireGuard состояние: $state ===")
        }
    }
    private val userStopped = AtomicBoolean(false)
    private val sessionKillScheduled = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    @Volatile private var networkDebounceJob: kotlinx.coroutines.Job? = null
    private val restartCount = AtomicInteger(0)
    @Volatile private var captchaNotificationActive = false
    private var stoppedByWifi = false
    private var coreOnlyMode = false

    private lateinit var prefs: AppPreferences
    private lateinit var serviceScope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROXY,
                    getString(R.string.notif_channel_proxy),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CAPTCHA,
                    getString(R.string.notif_channel_captcha),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ВАЖНО: startForeground вызываем ПЕРВЫМ делом и БЕЗУСЛОВНО. Если ранее
        // была ветка с return до startForeground (например, при stale
        // ProxyServiceState.isRunning после kill'а сервиса без onDestroy),
        // система через ~5с бросала ForegroundServiceDidNotStartInTimeException.
        if (openAppIntent == null) {
            openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_PROXY)
            .setContentTitle(getString(R.string.notif_proxy_title))
            .setContentText(getString(R.string.notif_proxy_connecting))
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        startForeground(NOTIF_ID_FG, notification)

        if (intent?.action == ACTION_START_TUNNEL) {
            ProxyServiceState.addLog("Запрос на активацию WireGuard туннеля...")
            coreOnlyMode = false
            serviceScope.launch(Dispatchers.IO) { startWireGuardTunnel() }
            return START_STICKY
        }

        coreOnlyMode = intent?.getBooleanExtra(EXTRA_CORE_ONLY, false) ?: false
        if (coreOnlyMode) {
            ProxyServiceState.addLog("Режим запуска: Только прокси-ядро (без VPN-туннеля)")
        }

        ProxyServiceState.setRunning(true)
        userStopped.set(false)

        // Блокировка запуска на Wi-Fi
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val isWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }

        if (isWifi) {
            stoppedByWifi = true
            ProxyServiceState.addLog("=== ОШИБКА: Запуск прокси по Wi-Fi заблокирован ===")
            ProxyServiceState.setStartupResult(StartupResult.Failed("Запуск заблокирован: подключён Wi-Fi"))
            ProxyServiceState.setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Если процесс ядра ещё жив — это повторный onStartCommand (например,
        // sticky-рестарт). Не запускаем второй процесс, но требование о
        // startForeground выше уже выполнено.
        if (process.get() != null) {
            ProxyServiceState.setRunning(true)
            return START_STICKY
        }

        restartCount.set(0)
        ProxyServiceState.startNewSession(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()

        ProxyServiceState.addLog("=== ЗАПУСК ПРОКСИ ===")
        serviceScope.launch { startBinaryProcess() }

        return START_STICKY
    }

    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return

        fetchAndDecryptConfig()

        val cfg = prefs.clientConfigFlow.first()
        // Wrap-обфускация и VLESS bonding управляются на серверном экране, но
        // должны передаваться и клиенту с тем же ключом, иначе DTLS-handshake
        // не сойдётся. Источник истины — общий serverOpts.
        val srv = prefs.serverOptsFlow.first()

        val executable = "${applicationInfo.nativeLibraryDir}/libvkturn.so"

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
        } else {
            cmdArgs.add(executable)
            cmdArgs.add("-peer"); cmdArgs.add(cfg.serverAddress)

            val activeVkLink = (if (cfg.vkLink.isNotBlank()) cfg.vkLink else cfg.systemVkLink).trim()
            cmdArgs.add(if (activeVkLink.contains("yandex")) "-yandex-link" else "-vk-link")
            cmdArgs.add(activeVkLink)
            cmdArgs.add("-listen"); cmdArgs.add(cfg.localPort)
            if (cfg.threads > 0) { cmdArgs.add("-n"); cmdArgs.add(cfg.threads.toString()) }
            if (cfg.streamsPerCred > 0) {
                cmdArgs.add("-streams-per-cred"); cmdArgs.add(cfg.streamsPerCred.toString())
            }
            if (cfg.vlessMode) cmdArgs.add("-vless")
            else if (cfg.useUdp) cmdArgs.add("-udp")
            // VLESS bonding имеет смысл только в VLESS-режиме.
            if (cfg.vlessMode && srv.vlessBond) cmdArgs.add("-vless-bond")
            // WRAP: тот же ключ, что и у сервера (хранится в EncryptedSharedPreferences).
            // Без 64-hex ключа флаг не передаём — ядро упадёт.
            if (srv.wrapEnabled &&
                srv.wrapKey.length == 64 &&
                srv.wrapKey.matches(Regex("^[0-9a-fA-F]+$"))
            ) {
                cmdArgs.add("-wrap")
                cmdArgs.add("-wrap-key"); cmdArgs.add(srv.wrapKey)
            }
            if (cfg.manualCaptcha) cmdArgs.add("--manual-captcha")

            if (cfg.debugMode) cmdArgs.add("-debug")
            if (cfg.useCarrierDns) {
                val dns = activeNetworkDnsServers()
                if (dns.isNotBlank()) {
                    cmdArgs.add("-dns-servers"); cmdArgs.add(dns)
                }
            }
            if (cfg.dnsMode == DnsMode.UDP || cfg.dnsMode == DnsMode.DOH) {
                cmdArgs.add("-dns"); cmdArgs.add(cfg.dnsMode)
            }
            if (cfg.forcePort443) { cmdArgs.add("-port"); cmdArgs.add("443") }
            // Альтернативный TURN-узел: переключает клиент на указанный server-side relay
            // вместо автоподбора. Адрес задаётся пользователем, флаг работает только при
            // непустом значении (иначе ядро запустится без -turn и будет автоподбор).
            if (cfg.magicSwitch) {
                val turn = cfg.magicTurn.trim()
                if (turn.isNotEmpty()) {
                    cmdArgs.add("-turn"); cmdArgs.add(turn)
                }
            }
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var captchaSessionCounter = 0L

        // --- Трекинг активных соединений для индикации состояния в UI. ---
        // Не-VLESS: каждый поток логирует свой [STREAM N] Established/Closed парой
        // (defer Closed ставится ДО логирования Established, см. client/main.go).
        // Считаем именно инкрементами, а не множеством уникальных ID: в ядре есть
        // особенность — первый поток запускается с id=1 и цикл снова итерируется
        // с i=1, из-за чего один streamID дублируется при -n N. Для счётчика это
        // безопасно (на два Established придёт два Closed), для Set это давало
        // бы заниженное число активных (N-1 вместо N).
        var nonVlessActive = 0
        // Для не-VLESS целевое число потоков известно из конфига (-n). Если threads == 0,
        // ядро запускает один поток, считаем total = 1.
        val nonVlessTotal = if (cfg.isRawMode) 0 else if (cfg.threads > 0) cfg.threads else 1
        var vlessActive = 0
        var vlessTotal = 0
        var isVless = cfg.vlessMode

        fun publishStats() {
            val stats = if (isVless) {
                ConnectionStats(vlessActive, vlessTotal)
            } else {
                ConnectionStats(nonVlessActive, nonVlessTotal)
            }
            ProxyServiceState.setConnectionStats(stats)
        }
        // Сброс на старте сессии (в том числе на watchdog-рестарте).
        publishStats()
        try {
            ProxyServiceState.addLog("Команда: ${cmdArgs.joinToString(" ")}")

            val proc = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                // Ядро по умолчанию пишет vk_profile.json в CWD (= /data/app/.../lib/<abi>/),
                // а это read-only mount на Android. Перенаправляем в filesDir, где записать
                // можно. См. client/profiles.go: profileFilePath() читает $VK_PROFILE_PATH
                // в первую очередь.
                pb.environment()["VK_PROFILE_PATH"] =
                    File(filesDir, "vk_profile.json").absolutePath
                // KCP FEC — должно совпадать с сервером. Включается из ServerOpts.
                if (srv.kcpFec) {
                    pb.environment()["VK_TURN_KCP_FEC"] = KCP_FEC_VALUE
                }
                // CWD тоже подменяем на writeable dir — на случай если Go-код или его
                // зависимости пишут что-то относительными путями (логи кеша tls-client и т.п.).
                pb.directory(filesDir)
                pb.start()
            }
            process.set(proc)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (true) {
                    line = try {
                        reader.readLine()
                    } catch (e: java.io.IOException) {
                        // При destroyCompat()/Process.destroy() с другого треда
                        // нативный pipe закрывается, и блокирующий readLine() бросает
                        // IOException("Stream closed" / "read interrupted by close()").
                        // Это нормальный путь остановки — выходим из цикла молча.
                        val msg = e.message.orEmpty()
                        val benign = userStopped.get() ||
                            msg.contains("interrupted by close", ignoreCase = true) ||
                            msg.contains("Stream closed", ignoreCase = true) ||
                            msg.contains("Bad file descriptor", ignoreCase = true)
                        if (!benign) {
                            ProxyServiceState.addLog("Чтение лога ядра прервано: ${e.message}")
                        }
                        null
                    }
                    if (line == null) break
                    val l = line ?: continue
                    ProxyServiceState.addLog(l)

                    // Детекция URL ручной капчи. Каждый раз выдаём новый sessionId,
                    // чтобы диалог пересоздавал WebView, даже если URL не поменялся
                    // (бинарник всегда использует http://localhost:8765).
                    val captchaMatcher = CAPTCHA_URL_REGEX.matcher(l)
                    if (captchaMatcher.find()) {
                        val url = captchaMatcher.group(1)!!
                        captchaSessionCounter += 1
                        ProxyServiceState.setCaptchaSession(
                            CaptchaSession(url, captchaSessionCounter)
                        )
                        // Показываем нотификацию только если предыдущая капча уже закрыта.
                        // Бинарник может выдать несколько URL подряд за одну авторизацию —
                        // не плодим спам.
                        if (!captchaNotificationActive) {
                            showCaptchaNotification()
                            captchaNotificationActive = true
                        }
                    }

                    // Капча-сессия закончилась: бинарник либо завершил auth-чейн
                    // (Failed/Success), либо сама капча провалилась (timeout). Закрываем
                    // диалог — следующая капча-сессия откроет его заново через новый sessionId.
                    if (ProxyServiceState.captchaSession.value != null && (
                            l.contains("[VK Auth] Failed") ||
                            l.contains("[VK Auth] Success") ||
                            (l.contains("[Captcha]") && l.contains("failed"))
                        )) {
                        ProxyServiceState.setCaptchaSession(null)
                        cancelCaptchaNotification()
                    }

                    // Парсинг событий жизненного цикла соединений. Обновляем stats
                    // и используем первое "реально подключилось" как сигнал успешного старта.
                    var statsChanged = false
                    STREAM_ESTABLISHED_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            nonVlessActive += 1
                            statsChanged = true
                            isVless = false
                        }
                    }
                    STREAM_CLOSED_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            if (nonVlessActive > 0) nonVlessActive -= 1
                            statsChanged = true
                        }
                    }
                    VLESS_TOTAL_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            vlessTotal = m.group(1)!!.toInt()
                            isVless = true
                            statsChanged = true
                        }
                    }
                    VLESS_ACTIVE_REGEX.matcher(l).let { m ->
                        if (m.find()) {
                            vlessActive = m.group(1)!!.toInt()
                            isVless = true
                            statsChanged = true
                        }
                    }
                    if (statsChanged) publishStats()

                    // Startup: ядро упало с panic/fatal/окончательно не смогло
                    // получить creds ДО того, как удалось подключиться — считаем
                    // запуск неудачным. Первая строка без этих маркеров больше не
                    // трактуется как Success (ядро могло написать "Connecting..."
                    // и только потом упасть).
                    //
                    // ВАЖНО: не матчим подстроку "rate limit" — Go-сторона выводит
                    // её в кулдаун-логах ("identity cooldown", "VK throttle ...
                    // trying next") как рабочую часть retry-цикла, не как ошибку.
                    // Финальная неудача — "all VK credentials failed".
                    if (!startupEmitted) {
                        val lower = l.lowercase()
                        val hasFatal = lower.startsWith("panic:") ||
                            lower.startsWith("fatal error:") ||
                            lower.contains("all vk credentials failed") ||
                            lower.contains("fatal_captcha")
                        val hasConnection = (isVless && vlessActive > 0) ||
                            (!isVless && nonVlessActive > 0)
                        when {
                            hasFatal -> {
                                ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                                updateNotification("Ошибка подключения")
                                startupFailed = true
                                startupEmitted = true
                            }
                            hasConnection -> {
                                ProxyServiceState.setStartupResult(StartupResult.Success)
                                ProxyServiceState.markConnectedIfAbsent(SystemClock.elapsedRealtime())
                                if (coreOnlyMode) {
                                    updateNotification("Прокси активен (только ядро)")
                                } else {
                                    updateNotification("Прокси активен")
                                    serviceScope.launch(Dispatchers.IO) { startWireGuardTunnel() }
                                }
                                startupEmitted = true
                            }
                        }
                    }

                    // compareAndSet гарантирует единственный postDelayed даже при параллельных quota-ошибках
                    if (isQuotaError(l) && sessionKillScheduled.compareAndSet(false, true)) {
                        ProxyServiceState.addLog(">>> QUOTA ERROR — сброс сессии через 2с")
                        handler.postDelayed({
                            sessionKillScheduled.set(false)
                            if (!userStopped.get()) {
                                restartCount.set(0)
                                process.get()?.destroyCompat()
                            }
                        }, 2_000)
                    }
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitForCompat(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            ProxyServiceState.addLog("=== ПРОЦЕСС ОСТАНОВЛЕН (Код: $exitCode) ===")
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    "Процесс завершился без вывода (код: $exitCode)"))
            }

        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("error=13") || msg.contains("Permission denied")) {
                ProxyServiceState.addLog("КРИТИЧЕСКАЯ ОШИБКА: Отказано в запуске ядра — ваше устройство блокирует выполнение файлов из внутреннего хранилища (SELinux/noexec). Используйте встроенное ядро.")
                ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                startupFailed = true
            } else {
                ProxyServiceState.addLog("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            }
        } finally {
            stopWireGuardTunnel()
            ProxyServiceState.setCaptchaSession(null)
            cancelCaptchaNotification()
            // Процесс мёртв — активных соединений нет. При watchdog-рестарте
            // publishStats на новом старте снова выставит правильный target.
            ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
            process.set(null)
            when {
                userStopped.get() -> {
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                startupFailed -> {
                    ProxyServiceState.addLog("=== Ошибка при запуске, watchdog не активирован ===")
                    ProxyServiceState.setRunning(false)
                    // Убираем proxyFailed.tryEmit, так как startProxy и так обработает StartupResult.Failed
                    stopSelf()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        ProxyServiceState.addLog("=== Быстрый выход (${uptime}мс) — проверьте VK-ссылку и настройки ===")
                    } else {
                        ProxyServiceState.addLog("=== Сессия завершена нормально ===")
                    }
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    // Watchdog

    private fun scheduleWatchdogRestart() {
        val count = restartCount.incrementAndGet()
        if (count > MAX_RESTARTS) {
            ProxyServiceState.addLog("=== WATCHDOG: превышен лимит попыток ($MAX_RESTARTS), остановка ===")
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            stopSelf()
            return
        }
        val baseDelay = minOf(1_000L * count, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delay = baseDelay + jitter
        ProxyServiceState.addLog("=== WATCHDOG: перезапуск через ${delay}мс (попытка $count/$MAX_RESTARTS) ===")
        updateNotification("Переподключение ($count/$MAX_RESTARTS)...")
        handler.postDelayed({
            if (!userStopped.get()) serviceScope.launch { startBinaryProcess() }
        }, delay)
    }

    // Network handover

    private fun registerNetworkCallback() {
        networkInitialized = false
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return
                }

                // Авто-отключение при переходе на Wi-Fi
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    serviceScope.launch {
                        stoppedByWifi = true
                        ProxyServiceState.addLog("=== ОБНАРУЖЕН WI-FI — АВТО-ОТКЛЮЧЕНИЕ ===")
                        ProxyServiceState.setStartupResult(StartupResult.Failed("Авто-отключение: подключён Wi-Fi"))
                        stopSelf()
                    }
                    return
                }

                if (!networkInitialized) {
                    networkInitialized = true
                    return
                }
                
                // Дебаунс: отменяем предыдущий ждущий перезапуск, если он был
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (!userStopped.get() && process.get() != null) {
                        ProxyServiceState.addLog("=== СМЕНА СЕТИ — ПЕРЕЗАПУСК ===")
                        updateNotification("Смена сети, переподключение...")
                        restartCount.set(0)
                        val p = process.get()
                        p?.destroyCompat()
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    serviceScope.launch {
                        stoppedByWifi = true
                        ProxyServiceState.addLog("=== ОБНАРУЖЕН WI-FI — АВТО-ОТКЛЮЧЕНИЕ ===")
                        ProxyServiceState.setStartupResult(StartupResult.Failed("Авто-отключение: подключён Wi-Fi"))
                        stopSelf()
                    }
                }
            }
        }
        networkCallback = cb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(cb)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, cb)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // Notification

    private fun showCaptchaNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_CAPTCHA)
            .setContentTitle(getString(R.string.notif_captcha_title))
            .setContentText(getString(R.string.notif_captcha_text))
            .setSmallIcon(R.drawable.ic_notification_captcha)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_CAPTCHA, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS отозван юзером на API 33+ — молча игнорируем,
            // диалог в UI всё равно откроется через captchaSession StateFlow.
        }
    }

    private fun cancelCaptchaNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIF_ID_CAPTCHA)
        captchaNotificationActive = false
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_PROXY)
            .setContentTitle(getString(R.string.notif_proxy_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_FG, notification)
        } catch (_: SecurityException) {}
    }

    // Helpers

    private fun isQuotaError(line: String): Boolean =
        line.lowercase().contains("quota")

    /**
     * DNS активной сети (оператор/Wi-Fi). Возвращает comma-separated список IP,
     * пригодный для флага `-dns-servers` ядра. Пусто, если сеть недоступна или
     * у linkProperties нет DNS (что норма на эмуляторе/некоторых VPN).
     */
    private fun activeNetworkDnsServers(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return ""
            val lp = cm.getLinkProperties(net) ?: return ""
            lp.dnsServers
                .mapNotNull { it.hostAddress }
                .filter { it.isNotBlank() }
                .joinToString(",")
        } catch (_: Exception) {
            ""
        }
    }

    private fun startWireGuardTunnel() {
        try {
            val wgJsonStr = prefs.getWgConfig()
            if (wgJsonStr.isEmpty()) {
                ProxyServiceState.addLog("Ошибка: Конфигурация WireGuard пуста.")
                return
            }
            val wgJson = org.json.JSONObject(wgJsonStr)
            val clientConfig = kotlinx.coroutines.runBlocking { prefs.clientConfigFlow.first() }
            val relayLocalPort = clientConfig.localPort.substringAfterLast(":")

            ProxyServiceState.addLog("Инициализация WireGuard туннеля...")

            val configText = """
                [Interface]
                PrivateKey = ${wgJson.getString("private_key")}
                Address = ${wgJson.getString("address")}
                DNS = ${wgJson.optString("dns", "1.1.1.1")}
                MTU = ${wgJson.optInt("mtu", 1280)}
                ExcludedApplications = $packageName
                
                [Peer]
                PublicKey = ${wgJson.getString("public_key")}
                Endpoint = 127.0.0.1:$relayLocalPort
                AllowedIPs = 0.0.0.0/0
                PersistentKeepalive = ${wgJson.optInt("persistent_keepalive", 15)}
            """.trimIndent()

            val config = Config.parse(configText.byteInputStream())

            if (wgBackend == null) {
                wgBackend = GoBackend(this)
            }

            wgBackend?.setState(turnTunnel, Tunnel.State.UP, config)
            ProxyServiceState.addLog("WireGuard туннель запущен.")
        } catch (e: Exception) {
            ProxyServiceState.addLog("Ошибка запуска WireGuard: ${e.message}")
        }
    }

    private fun stopWireGuardTunnel() {
        try {
            if (wgBackend != null) {
                ProxyServiceState.addLog("Остановка WireGuard туннеля...")
                wgBackend?.setState(turnTunnel, Tunnel.State.DOWN, null)
                ProxyServiceState.addLog("WireGuard туннель остановлен.")
            }
        } catch (e: Exception) {
            ProxyServiceState.addLog("Ошибка остановки WireGuard: ${e.message}")
        }
    }

    private suspend fun fetchAndDecryptConfig(): Boolean {
        return withContext(Dispatchers.IO) {
            val token = prefs.getAuthToken()
            if (token.isEmpty()) {
                return@withContext true
            }

            var configUrl = ""
            var decryptionKey = ""
            var authException: Throwable? = null

            val authResult = performTokenAuth(token)
            if (authResult.isSuccess) {
                val response = authResult.getOrThrow()
                configUrl = response.configUrl
                decryptionKey = response.decryptionKey
                prefs.saveConfigUrl(configUrl)
                prefs.saveDecryptionKey(decryptionKey)
                prefs.saveSubscriptionExpiresAt(response.endsAt)
            } else {
                authException = authResult.exceptionOrNull()
                configUrl = prefs.getConfigUrl()
                decryptionKey = prefs.getDecryptionKey()
            }

            if (configUrl.isEmpty() || decryptionKey.isEmpty()) {
                ProxyServiceState.addLog("Ошибка авто-обновления: ${authException?.message ?: "Сервер недоступен"}")
                return@withContext false
            }

            try {
                val url = java.net.URL(configUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.connect()

                if (conn.responseCode == 200) {
                    val encryptedBytes = conn.inputStream.use { it.readBytes() }

                    val decryptedJson = decryptConfigBytes(encryptedBytes, decryptionKey)

                    val json = org.json.JSONObject(decryptedJson)

                    val relay = json.getJSONObject("relay")
                    val serverAddress = relay.getString("server_address")
                    val localPort = "127.0.0.1:" + relay.getString("local_port")
                    val threads = relay.getInt("threads")
                    val streamsPerCred = relay.getInt("streams_per_cred")

                    val vkLink = json.getString("vk_call_link")

                    val appSettings = json.optJSONObject("app_settings")
                    val materialU = appSettings?.optBoolean("material_u", true) ?: true
                    val alternativeTurnEnabled = appSettings?.optBoolean("alternative_turn_enabled", false) ?: false
                    val alternativeTurnAddress = appSettings?.optString("alternative_turn_address", "") ?: ""
                    val dnsResolverMode = appSettings?.optString("dns_resolver_mode", "auto") ?: "auto"
                    val useCarrierDns = appSettings?.optBoolean("use_carrier_dns", false) ?: false
                    val forcePort443 = appSettings?.optBoolean("force_port_443", false) ?: false
                    val syncServerSwitches = appSettings?.optBoolean("sync_server_switches", true) ?: true

                    val logging = json.optJSONObject("logging")
                    val debugMode = logging?.optBoolean("debug", true) ?: true

                    val wireguard = json.optJSONObject("wireguard")
                    if (wireguard != null) {
                        prefs.saveWgConfig(wireguard.toString())
                    }

                    val current = prefs.clientConfigFlow.first()
                    val updatedConfig = com.freeturn.app.data.ClientConfig(
                        serverAddress = serverAddress,
                        vkLink = current.vkLink,
                        systemVkLink = vkLink,
                        threads = current.threads,
                        streamsPerCred = current.streamsPerCred,
                        localPort = localPort,
                        debugMode = debugMode,
                        useCarrierDns = useCarrierDns,
                        dnsMode = dnsResolverMode,
                        forcePort443 = forcePort443,
                        syncServerSwitches = syncServerSwitches,
                        magicSwitch = alternativeTurnEnabled,
                        magicTurn = alternativeTurnAddress,
                        vlessMode = relay.optBoolean("vless", current.vlessMode),
                        useUdp = relay.optBoolean("udp", current.useUdp),
                        manualCaptcha = appSettings?.optBoolean("manual_captcha", current.manualCaptcha) ?: current.manualCaptcha,
                        isRawMode = current.isRawMode,
                        rawCommand = current.rawCommand
                    )

                    prefs.saveClientConfig(updatedConfig)
                    prefs.setDynamicTheme(materialU)
                    ProxyServiceState.addLog("=== Конфигурация успешно обновлена ===")
                    true
                } else {
                    ProxyServiceState.addLog("Ошибка скачивания конфига: ${conn.responseCode}")
                    false
                }
            } catch (e: Exception) {
                ProxyServiceState.addLog("Ошибка скачивания/дешифрования конфига: ${e.message}")
                false
            }
        }
    }

    private fun decryptConfigBytes(encryptedBytes: ByteArray, keyHex: String): String {
        if (encryptedBytes.size < 28) {
            throw Exception("Неверный размер файла конфигурации.")
        }
        val iv = encryptedBytes.sliceArray(0 until 12)
        val tag = encryptedBytes.sliceArray(12 until 28)
        val rawCiphertext = encryptedBytes.sliceArray(28 until encryptedBytes.size)
        val ciphertextWithTag = rawCiphertext + tag

        val keyBytes = hexToBytes(keyHex)
        val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(ciphertextWithTag)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private suspend fun performTokenAuth(token: String): Result<ServiceAuthResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://tvaldsforge.online/bot/turnproxy/auth?token=$token")
                
                // Проверяем, запущен ли прокси и есть ли активный системный VPN
                val isRunning = ProxyServiceState.isRunning.value
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val vpnNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.allNetworks.firstOrNull { network ->
                        val caps = cm.getNetworkCapabilities(network)
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
                    }
                } else {
                    null
                }

                val conn = when {
                    vpnNetwork != null -> {
                        ProxyServiceState.addLog("Запрос авторизации: роутинг через интерфейс VPN")
                        vpnNetwork.openConnection(url) as java.net.HttpURLConnection
                    }
                    isRunning -> {
                        try {
                            val cfg = prefs.clientConfigFlow.first()
                            val port = cfg.localPort.substringAfterLast(":").toIntOrNull() ?: 9000
                            ProxyServiceState.addLog("Запрос авторизации: роутинг через локальный SOCKS5 ($port)")
                            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
                            url.openConnection(proxy) as java.net.HttpURLConnection
                        } catch (e: Exception) {
                            ProxyServiceState.addLog("Сбой SOCKS5: ${e.message}, прямой запрос...")
                            url.openConnection() as java.net.HttpURLConnection
                        }
                    }
                    else -> {
                        url.openConnection() as java.net.HttpURLConnection
                    }
                }

                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.connect()

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(jsonStr)
                    val configUrl = json.getString("config_url")
                    val decryptionKey = json.getString("decryption_key")
                    val endsAt = json.optLong("ends_at", 0L)
                    Result.success(ServiceAuthResponse(configUrl, decryptionKey, endsAt))
                } else if (conn.responseCode == 403) {
                    Result.failure(Exception("Доступ отклонен: неверный токен"))
                } else {
                    Result.failure(Exception("Ошибка сервера: ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private data class ServiceAuthResponse(val configUrl: String, val decryptionKey: String, val endsAt: Long)

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        ProxyServiceState.setRunning(false)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        cancelCaptchaNotification()
        if (!stoppedByWifi) {
            ProxyServiceState.addLog("=== ОСТАНОВКА ИЗ ИНТЕРФЕЙСА ===")
        }
        stopWireGuardTunnel()
        process.get()?.destroyCompat()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}

private fun Process.destroyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}

private fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)
    val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
    while (System.currentTimeMillis() < deadline) {
        try { exitValue(); return true } catch (_: IllegalThreadStateException) { Thread.sleep(100) }
    }
    return try { exitValue(); true } catch (_: IllegalThreadStateException) { false }
}

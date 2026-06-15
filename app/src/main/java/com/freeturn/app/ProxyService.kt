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
import kotlin.coroutines.coroutineContext
import java.util.regex.Pattern
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

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
            Pattern.compile("""\[STREAM (\d+)\] (?:Established DTLS connection|relayed-address=[\d.:]+|\[VK Auth\] Success with client_id=\d+)""")
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
    private var launchJob: kotlinx.coroutines.Job? = null
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
        serviceScope.launch {
            ProxyServiceState.captchaSession.collect { session ->
                if (session == null) {
                    cancelCaptchaNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stoppedByWifi = false
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
            ProxyServiceState.addLog("=== ОШИБКА: Запуск прокси по WI-FI заблокирован ===")
            ProxyServiceState.setStartupResult(StartupResult.Failed("Запуск отклонён: обнаружен WI-FI"))
            ProxyServiceState.setRunning(false)
            android.widget.Toast.makeText(
                this,
                "Запуск отклонён: обнаружен WI-FI",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }

        // Если процесс ядра ещё жив — это повторный onStartCommand (например,
        // sticky-рестарт). Не запускаем второй процесс, но требование о
        // startForeground выше уже выполнено.
        synchronized(this) {
            if (process.get() != null || launchJob?.isActive == true) {
                ProxyServiceState.setRunning(true)
                return START_STICKY
            }

            restartCount.set(0)
            ProxyServiceState.setWatchdogAttempt(0)
            ProxyServiceState.startNewSession(this)

            if (wakeLock?.isHeld == true) {
                try { wakeLock?.release() } catch (_: Exception) {}
            }
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
            wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

            registerNetworkCallback()

            ProxyServiceState.addLog("=== ЗАПУСК ПРОКСИ ===")
            launchJob = serviceScope.launch { startBinaryProcess() }
        }

        return START_STICKY
    }

    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return

        val hasCachedConfig = prefs.clientConfigFlow.first().serverAddress.isNotEmpty()
        if (hasCachedConfig) {
            // Запуск ядра с кэшированным конфигом без блокировки
            serviceScope.launch {
                try {
                    fetchAndDecryptConfig()
                } catch (e: Exception) {
                    ProxyServiceState.addLog("Фоновое обновление конфигурации прервано: ${e.message}")
                }
            }
        } else {
            // Нет кэша — блокирующий запрос
            fetchAndDecryptConfig()
        }

        val cfg = prefs.clientConfigFlow.first()
        // Wrap-обфускация и VLESS bonding управляются на серверном экране, но
        // должны передаваться и клиенту с тем же ключом, иначе DTLS-handshake
        // не сойдётся. Источник истины — общий serverOpts.
        val srv = prefs.serverOptsFlow.first()

        val libDir = File(applicationInfo.nativeLibraryDir)
        val executable = libDir.listFiles { f ->
            (f.name.startsWith("libfreeturn") || f.name.startsWith("libvkturn")) && f.name.endsWith(".so")
        }?.maxByOrNull { it.name }?.absolutePath

        if (executable == null) {
            ProxyServiceState.addLog(
                "КРИТИЧЕСКАЯ ОШИБКА: ядро не найдено в ${libDir.path}. " +
                "Положите бинарник в jniLibs/arm64-v8a/ (имя обязано начинаться с lib и оканчиваться на .so)."
            )
            ProxyServiceState.setStartupResult(StartupResult.Failed("core binary not found"))
            return
        }

        // Clean up any leaked/zombie processes of the proxy binary from previous crashed runs
        try {
            val binaryName = File(executable).name
            val killProcess = Runtime.getRuntime().exec(arrayOf("killall", binaryName))
            killProcess.waitForCompat(2, TimeUnit.SECONDS)
            killProcess.destroyCompat()
        } catch (_: Exception) {}

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            cmdArgs.add(executable)
            cmdArgs.addAll(parts.drop(1))
        } else {
            cmdArgs.add(executable)
            cmdArgs.add("-peer"); cmdArgs.add(cfg.serverAddress)

            val activeVkLink = (if (cfg.useCustomVkLink && cfg.vkLink.isNotBlank()) cfg.vkLink else cfg.systemVkLink).trim()
            val provider = if (activeVkLink.contains("yandex")) "yandex" else "vk"
            cmdArgs.add("-provider"); cmdArgs.add(provider)
            cmdArgs.add("-link"); cmdArgs.add(activeVkLink)
            cmdArgs.add("-listen"); cmdArgs.add(cfg.localPort)
            if (cfg.threads > 0) { cmdArgs.add("-n"); cmdArgs.add(cfg.threads.toString()) }
            if (cfg.streamsPerCred > 0) {
                cmdArgs.add("-streams-per-cred"); cmdArgs.add(cfg.streamsPerCred.toString())
            }
            if (cfg.vlessMode) {
                cmdArgs.add("-mode"); cmdArgs.add("tcp")
                if (srv.vlessBond) cmdArgs.add("-bond")
            }
            if (cfg.useUdp) {
                cmdArgs.add("-transport"); cmdArgs.add("udp")
            }
            // WRAP: тот же ключ, что и у сервера (хранится в EncryptedSharedPreferences).
            // Без 64-hex ключа флаг не передаём — ядро упадёт.
            if (srv.wrapEnabled &&
                srv.wrapKey.length == 64 &&
                srv.wrapKey.matches(Regex("^[0-9a-fA-F]+$"))
            ) {
                cmdArgs.add("-obf-profile"); cmdArgs.add("rtpopus")
                cmdArgs.add("-obf-key"); cmdArgs.add(srv.wrapKey)
            }
            if (cfg.manualCaptcha) cmdArgs.add("-manual-captcha")

            if (cfg.debugMode) cmdArgs.add("-debug")
            if (cfg.useCarrierDns) {
                val dns = activeNetworkDnsServers()
                if (dns.isNotBlank()) {
                    cmdArgs.add("-dns-servers"); cmdArgs.add(dns)
                }
            }
            val mappedDnsMode = when (cfg.dnsMode) {
                "udp", "plain" -> "plain"
                "doh" -> "doh"
                else -> null
            }
            if (mappedDnsMode != null) {
                cmdArgs.add("-dns-mode"); cmdArgs.add(mappedDnsMode)
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
        val isCoroutineActive = kotlinx.coroutines.currentCoroutineContext().isActive
        if (userStopped.get() || !isCoroutineActive) return

        try {
            ProxyServiceState.addLog("Команда: ${cmdArgs.joinToString(" ")}")

            val proc = synchronized(this) {
                if (userStopped.get() || !isCoroutineActive) return@synchronized null
                try {
                    val pb = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                    pb.environment()["VK_PROFILE_PATH"] =
                        File(filesDir, "vk_profile.json").absolutePath
                    if (srv.kcpFec) {
                        pb.environment()["VK_TURN_KCP_FEC"] = KCP_FEC_VALUE
                    }
                    pb.directory(filesDir)
                    val p = pb.start()
                    process.set(p)
                    try { p.outputStream.close() } catch (_: Exception) {}
                    p
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("error=13") || msg.contains("Permission denied")) {
                        ProxyServiceState.addLog("КРИТИЧЕСКАЯ ОШИБКА: Отказано в запуске ядра — ваше устройство блокирует выполнение файлов из внутреннего хранилища (SELinux/noexec). Используйте встроенное ядро.")
                        ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                        startupFailed = true
                    } else {
                        ProxyServiceState.addLog("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
                    }
                    null
                }
            }
            if (proc == null) return

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
                    val l = line
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
                        ProxyServiceState.setCaptchaVerificationState(CaptchaVerificationState.REQUIRED)
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
                    val authFinished = l.contains("[VK Auth] Failed") ||
                            l.contains("[VK Auth] Success") ||
                            (l.contains("[Captcha]") && l.contains("failed"))
                    if (authFinished) {
                        ProxyServiceState.setCaptchaSession(null)
                        cancelCaptchaNotification()
                        val state = if (l.contains("[VK Auth] Success")) {
                            CaptchaVerificationState.NONE
                        } else {
                            CaptchaVerificationState.FAILED
                        }
                        ProxyServiceState.setCaptchaVerificationState(state)
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
                        val hasConnection = (isVless && vlessActive > 0) ||
                            (!isVless && nonVlessActive > 0)
                        when {
                            hasConnection -> {
                                try {
                                    val cfg = prefs.clientConfigFlow.first()
                                    val port = cfg.localPort.substringAfterLast(":").toIntOrNull() ?: 9000
                                    System.setProperty("socksProxyHost", "127.0.0.1")
                                    System.setProperty("socksProxyPort", port.toString())
                                } catch (_: Exception) {}
                                restartCount.set(0)
                                ProxyServiceState.setWatchdogAttempt(0)
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
                                ProxyServiceState.setWatchdogAttempt(0)
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

            val isCancelled = !kotlinx.coroutines.currentCoroutineContext().isActive
            val willRetry = !userStopped.get() && !isCancelled && !startupFailed && exitCode != 0 && (restartCount.get() < MAX_RESTARTS)

            if (!startupEmitted && !willRetry) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    "Процесс завершился (код: $exitCode)"
                ))
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
            try {
                System.clearProperty("socksProxyHost")
                System.clearProperty("socksProxyPort")
            } catch (_: Exception) {}
            stopWireGuardTunnel()
            ProxyServiceState.setCaptchaSession(null)
            ProxyServiceState.setCaptchaVerificationState(CaptchaVerificationState.NONE)
            cancelCaptchaNotification()
            // Процесс мёртв — активных соединений нет. При watchdog-рестарте
            // publishStats на новом старте снова выставит правильный target.
            ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
            val p = process.getAndSet(null)
            p?.destroyCompat()
            val isCancelled = !kotlinx.coroutines.currentCoroutineContext().isActive
            when {
                userStopped.get() || isCancelled -> {
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                startupFailed -> {
                    ProxyServiceState.addLog("=== Ошибка при запуске, watchdog не активирован ===")
                    ProxyServiceState.setRunning(false)
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
        ProxyServiceState.setWatchdogAttempt(count)
        if (count > MAX_RESTARTS) {
            ProxyServiceState.addLog("=== WATCHDOG: превышен лимит попыток ($MAX_RESTARTS), остановка ===")
            ProxyServiceState.setStartupResult(StartupResult.Failed("Не удалось подключиться к серверу после $MAX_RESTARTS попыток"))
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
            if (!userStopped.get()) {
                synchronized(this) {
                    if (launchJob?.isActive != true) {
                        launchJob = serviceScope.launch { startBinaryProcess() }
                    }
                }
            }
        }, delay)
    }

    // Network handover

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
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
                    if (!stoppedByWifi) {
                        stoppedByWifi = true
                        handler.post {
                            android.widget.Toast.makeText(
                                this@ProxyService,
                                "Прокси отключен: обнаружен WI-FI",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    serviceScope.launch {
                        ProxyServiceState.addLog("=== ОБНАРУЖЕН WI-FI — АВТО-ОТКЛЮЧЕНИЕ ===")
                        ProxyServiceState.setStartupResult(StartupResult.Failed("Авто-отключение: подключён WI-FI"))
                        stopSelf()
                    }
                    return
                }

                if (!networkInitialized) {
                    networkInitialized = true
                    return
                }
                
                // Дебаунс: отменяем предыдущий ждущий перезапуск, если он был
                val processToDestroy = process.get()
                if (processToDestroy != null) {
                    synchronized(this@ProxyService) {
                        networkDebounceJob?.cancel()
                        networkDebounceJob = serviceScope.launch {
                            kotlinx.coroutines.delay(2000)
                            synchronized(this@ProxyService) {
                                if (!userStopped.get() && process.get() == processToDestroy) {
                                    ProxyServiceState.addLog("=== СМЕНА СЕТИ — ПЕРЕЗАПУСК ===")
                                    updateNotification("Смена сети, переподключение...")
                                    restartCount.set(0)
                                    ProxyServiceState.setWatchdogAttempt(0)
                                    val p = process.getAndSet(null)
                                    p?.destroyCompat()
                                }
                            }
                        }
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (!stoppedByWifi) {
                        stoppedByWifi = true
                        handler.post {
                            android.widget.Toast.makeText(
                                this@ProxyService,
                                "Прокси отключен: обнаружен WI-FI",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    serviceScope.launch {
                        ProxyServiceState.addLog("=== ОБНАРУЖЕН WI-FI — АВТО-ОТКЛЮЧЕНИЕ ===")
                        ProxyServiceState.setStartupResult(StartupResult.Failed("Авто-отключение: подключён WI-FI"))
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

    private suspend fun startWireGuardTunnel() {
        try {
            val wgJsonStr = prefs.getWgConfig()
            if (wgJsonStr.isEmpty()) {
                val errMsg = "Ошибка: Конфигурация WireGuard пуста."
                ProxyServiceState.addLog(errMsg)
                if (!coreOnlyMode) {
                    ProxyServiceState.setStartupResult(StartupResult.Failed(errMsg))
                    userStopped.set(true)
                    stopSelf()
                }
                return
            }
            val wgJson = org.json.JSONObject(wgJsonStr)
            val clientConfig = prefs.clientConfigFlow.first()
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
            val errMsg = "Ошибка запуска WireGuard: ${e.message}"
            ProxyServiceState.addLog(errMsg)
            if (!coreOnlyMode) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(errMsg))
                userStopped.set(true)
                stopSelf()
            }
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
        return ProxyServiceState.configMutex.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - ProxyServiceState.lastFetchTime < 30_000L) {
                return@withLock true
            }

            val result = fetchAndDecryptConfigInternal()
            if (result) {
                ProxyServiceState.lastFetchTime = now
            }
            result
        }
    }

    private suspend fun fetchAndDecryptConfigInternal(): Boolean {
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

            var conn: java.net.HttpURLConnection? = null
            var useProxy = false
            val isRunning = ProxyServiceState.isRunning.value
            val isConnected = ProxyServiceState.connectedSince.value != null
            val hasProcess = process.get() != null

            if (isRunning && isConnected && hasProcess) {
                useProxy = true
            }

            try {
                if (useProxy) {
                    try {
                        val cfg = prefs.clientConfigFlow.first()
                        val port = cfg.localPort.substringAfterLast(":").toIntOrNull() ?: 9000
                        ProxyServiceState.addLog("Загрузка конфига: роутинг через локальный SOCKS5 ($port)")
                        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
                        conn = com.freeturn.app.domain.NetworkUtil.openConnection(configUrl, proxy)
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.connect()
                    } catch (e: Exception) {
                        ProxyServiceState.addLog("Сбой SOCKS5 при загрузке конфига: ${e.message}, прямой запрос...")
                        conn?.disconnect()
                        conn = null
                        useProxy = false
                    }
                }

                if (conn == null) {
                    conn = com.freeturn.app.domain.NetworkUtil.openConnection(configUrl)
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.connect()
                }

                if (conn.responseCode == 200) {
                    val encryptedBytes = conn.inputStream.use { it.readBytes() }

                    val decryptedJson = decryptConfigBytes(encryptedBytes, decryptionKey)

                    val json = org.json.JSONObject(decryptedJson)

                    val relay = json.getJSONObject("relay")
                    val serverAddress = relay.getString("server_address")
                    val localPort = "127.0.0.1:" + relay.getString("local_port")
                    val threads = relay.getInt("threads")
                    val streamsPerCred = relay.optInt("streams_per_cred", 10)

                    val vkLink = json.getString("vk_call_link")

                    val appSettings = json.optJSONObject("app_settings")
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
                    if (appSettings != null && appSettings.has("material_u")) {
                        val materialU = appSettings.getBoolean("material_u")
                        prefs.setDynamicTheme(materialU)
                    }
                    ProxyServiceState.addLog("=== Конфигурация успешно обновлена ===")
                    true
                } else {
                    ProxyServiceState.addLog("Ошибка скачивания конфига: ${conn.responseCode}")
                    false
                }
            } catch (e: Exception) {
                val userMsg = when (e) {
                    is java.net.UnknownHostException -> "Нет подключения к интернету или сервер конфигурации недоступен"
                    is java.net.SocketTimeoutException, is java.net.ConnectException -> "Превышено время ожидания конфигурации"
                    is javax.net.ssl.SSLException -> "Ошибка SSL/TLS при загрузке конфигурации (возможно, блокировка)"
                    is java.io.IOException -> "Ошибка сети при загрузке конфигурации"
                    else -> e.message ?: "Неизвестная ошибка"
                }
                ProxyServiceState.addLog("Ошибка скачивания/дешифрования конфига: $userMsg")
                false
            } finally {
                conn?.disconnect()
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

        var keyBytes: ByteArray? = null
        var decryptedBytes: ByteArray? = null
        try {
            keyBytes = hexToBytes(keyHex)
            val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)

            decryptedBytes = cipher.doFinal(ciphertextWithTag)
            return String(decryptedBytes, Charsets.UTF_8)
        } finally {
            keyBytes?.fill(0)
            decryptedBytes?.fill(0)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.trim().filter { it.isLetterOrDigit() }
        val len = cleaned.length
        if (len % 2 != 0) {
            throw IllegalArgumentException("Длина Hex-строки ключа должна быть чётной.")
        }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(cleaned[i], 16)
            val low = Character.digit(cleaned[i + 1], 16)
            if (high == -1 || low == -1) {
                throw IllegalArgumentException("Недопустимые символы в Hex-строке ключа.")
            }
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }

    private suspend fun performTokenAuth(token: String): Result<ServiceAuthResponse> {
        return withContext(Dispatchers.IO) {
            var conn: java.net.HttpURLConnection? = null
            var useProxy = false
            val isRunning = ProxyServiceState.isRunning.value
            val isConnected = ProxyServiceState.connectedSince.value != null
            val hasProcess = process.get() != null
            val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
            val urlString = "https://tvaldsforge.online/bot/turnproxy/auth?token=$encodedToken"

            if (isRunning && isConnected && hasProcess) {
                useProxy = true
            }

            try {
                if (useProxy) {
                    try {
                        val cfg = prefs.clientConfigFlow.first()
                        val port = cfg.localPort.substringAfterLast(":").toIntOrNull() ?: 9000
                        ProxyServiceState.addLog("Запрос авторизации: роутинг через локальный SOCKS5 ($port)")
                        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
                        conn = com.freeturn.app.domain.NetworkUtil.openConnection(urlString, proxy)
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.connect()
                    } catch (e: Exception) {
                        ProxyServiceState.addLog("Сбой SOCKS5: ${e.message}, прямой запрос...")
                        conn?.disconnect()
                        conn = null
                        useProxy = false
                    }
                }

                if (conn == null) {
                    conn = com.freeturn.app.domain.NetworkUtil.openConnection(urlString)
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.connect()
                }

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(jsonStr)
                    val configUrl = json.getString("config_url")
                    val decryptionKey = json.getString("decryption_key")
                    val endsAt = json.optLong("ends_at", 0L)
                    Result.success(ServiceAuthResponse(configUrl, decryptionKey, endsAt))
                } else if (conn.responseCode == 403) {
                    Result.failure(Exception("Неверный токен или подписка неактивна"))
                } else {
                    Result.failure(Exception("Ошибка сервера авторизации (код ${conn.responseCode})"))
                }
            } catch (e: Exception) {
                val userMsg = when (e) {
                    is java.net.UnknownHostException -> "Нет подключения к интернету или сервер недоступен"
                    is java.net.SocketTimeoutException, is java.net.ConnectException -> "Превышено время ожидания сервера"
                    is javax.net.ssl.SSLException -> "Ошибка SSL/TLS: возможно, соединение блокируется или перехватывается провайдером (MITM)"
                    is java.io.IOException -> "Ошибка сети при авторизации"
                    else -> e.message ?: "Неизвестная ошибка"
                }
                Result.failure(Exception(userMsg, e))
            } finally {
                conn?.disconnect()
            }
        }
    }

    private data class ServiceAuthResponse(val configUrl: String, val decryptionKey: String, val endsAt: Long)

    override fun onDestroy() {
        super.onDestroy()
        try {
            System.clearProperty("socksProxyHost")
            System.clearProperty("socksProxyPort")
        } catch (_: Exception) {}
        userStopped.set(true)
        ProxyServiceState.setRunning(false)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()
        ProxyServiceState.setWatchdogAttempt(0)
        ProxyServiceState.setCaptchaVerificationState(CaptchaVerificationState.NONE)
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        cancelCaptchaNotification()
        if (!stoppedByWifi) {
            ProxyServiceState.addLog("=== ОСТАНОВКА ИЗ ИНТЕРФЕЙСА ===")
        }
        stopWireGuardTunnel()
        synchronized(this) {
            val p = process.getAndSet(null)
            p?.destroyCompat()
            launchJob?.cancel()
            launchJob = null
        }
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}

private fun Process.destroyCompat() {
    try { outputStream?.close() } catch (_: Exception) {}
    try { inputStream?.close() } catch (_: Exception) {}
    try { errorStream?.close() } catch (_: Exception) {}
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

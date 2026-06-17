package com.freeturn.app.viewmodel

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.domain.LocalProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class ProxyViewModel(
    private val proxyManager: LocalProxyManager,
    private val prefs: AppPreferences,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val connectedSince: StateFlow<Long?> = ProxyServiceState.connectedSince
    val logs: StateFlow<List<String>> = ProxyServiceState.logs

    val clientConfig: StateFlow<ClientConfig> = prefs.clientConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientConfig())

    val proxyListen: StateFlow<String> = prefs.proxyListenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "0.0.0.0:56000")

    val proxyConnect: StateFlow<String> = prefs.proxyConnectFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "127.0.0.1:40537")

    private var lastActionTime = 0L

    fun startProxy() {
        val now = System.currentTimeMillis()
        if (now - lastActionTime < 1000) return
        lastActionTime = now

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }

        if (isWifi) {
            proxyManager.setErrorWithAutoReset("Запуск отклонён: обнаружен WI-FI")
            return
        }

        viewModelScope.launch {
            val expiresAtSeconds = prefs.getSubscriptionExpiresAt()
            val currentTimeSeconds = System.currentTimeMillis() / 1000L
            val isExpired = expiresAtSeconds != 0L && currentTimeSeconds >= expiresAtSeconds
            val elapsedSeconds = if (isExpired) currentTimeSeconds - expiresAtSeconds else 0L
            val within12Hours = elapsedSeconds < 12 * 3600

            val config = prefs.clientConfigFlow.first()

            if (!isExpired || within12Hours) {
                if (!checkAndRefreshSubscription()) {
                    proxyManager.setErrorWithAutoReset("Подписка закончилась")
                    return@launch
                }
                proxyManager.startProxy(config)
            } else {
                // Подписка просрочена более чем на 12 часов: запускаем только ядро без VPN
                proxyManager.startProxy(config, coreOnly = true)
                
                // Даем ядру немного времени для бинда SOCKS5 порта
                kotlinx.coroutines.delay(800)
                
                // Делаем проверочный запрос авторизации через запущенный SOCKS5 прокси ядра
                val renewed = checkAndRefreshSubscription()
                if (renewed) {
                    // Подписка обновлена (пользователь продлил её в боте)! Запускаем WireGuard туннель
                    val intent = Intent(appContext, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_START_TUNNEL
                    }
                    appContext.startService(intent)
                } else {
                    // Подписка всё ещё просрочена. Гасим прокси и показываем ошибку
                    proxyManager.stopProxy()
                    proxyManager.setErrorWithAutoReset("Подписка закончилась")
                }
            }
        }
    }

    fun stopProxy() {
        val now = System.currentTimeMillis()
        if (now - lastActionTime < 1000) return
        lastActionTime = now
        proxyManager.stopProxy()
    }

    fun dismissCaptcha() {
        proxyManager.dismissCaptcha()
    }

    fun clearLogs() {
        ProxyServiceState.clearLogs()
    }

    fun exportLogs() {
        ProxyServiceState.exportLogs(appContext)
    }

    // --- Subscription & Config Fetching Helpers ---

    suspend fun getAuthToken(): String = prefs.getAuthToken()
    suspend fun saveAuthToken(token: String) {
        prefs.saveAuthToken(token)
    }

    suspend fun getConfigUrl(): String = prefs.getConfigUrl()
    suspend fun saveConfigUrl(url: String) {
        prefs.saveConfigUrl(url)
    }

    suspend fun getDecryptionKey(): String = prefs.getDecryptionKey()
    suspend fun saveDecryptionKey(key: String) {
        prefs.saveDecryptionKey(key)
    }

    suspend fun getSubscriptionExpiresAt(): Long = prefs.getSubscriptionExpiresAt()
    suspend fun saveSubscriptionExpiresAt(timestamp: Long) {
        prefs.saveSubscriptionExpiresAt(timestamp)
    }

    suspend fun getWgConfig(): String = prefs.getWgConfig()
    suspend fun saveWgConfig(jsonStr: String) {
        prefs.saveWgConfig(jsonStr)
    }

    suspend fun checkAndRefreshSubscription(): Boolean {
        val token = getAuthToken()
        if (token.isEmpty()) {
            return true
        }

        val expiresAtSeconds = prefs.getSubscriptionExpiresAt()
        val currentTimeSeconds = System.currentTimeMillis() / 1000L

        if (expiresAtSeconds == 0L) {
            // Never authenticated or fresh start, let's allow starting but do a silent refresh
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    fetchAndDecryptConfig()
                } catch (e: Exception) {}
            }
            return true
        }

        val isExpired = currentTimeSeconds >= expiresAtSeconds

        if (!isExpired) {
            // 1. Subscription active: allow start immediately, refresh in background
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    fetchAndDecryptConfig()
                } catch (e: Exception) {}
            }
            return true
        } else {
            // Subscription expired
            val elapsedSeconds = currentTimeSeconds - expiresAtSeconds
            val within12Hours = elapsedSeconds < 12 * 3600

            if (within12Hours) {
                // 2. Expired < 12h: allow start immediately so they can reach auth server, refresh in background
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        fetchAndDecryptConfig()
                    } catch (e: Exception) {}
                }
                return true
            } else {
                // 3. Expired >= 12h: block start, attempt a blocking refresh first
                val result = fetchAndDecryptConfig()
                if (result.isSuccess) {
                    val newExpiresAtSeconds = prefs.getSubscriptionExpiresAt()
                    val newCurrentTimeSeconds = System.currentTimeMillis() / 1000L
                    return newCurrentTimeSeconds < newExpiresAtSeconds
                } else {
                    return false
                }
            }
        }
    }

    suspend fun fetchAndDecryptConfig(customToken: String? = null): Result<Boolean> {
        return ProxyServiceState.configMutex.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            if (customToken == null && now - ProxyServiceState.lastFetchTime < 30_000L) {
                return@withLock Result.success(true)
            }

            val result = fetchAndDecryptConfigInternal(customToken)
            if (result.isSuccess) {
                ProxyServiceState.lastFetchTime = now
            }
            result
        }
    }

    private suspend fun fetchAndDecryptConfigInternal(customToken: String?): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            val token = customToken ?: getAuthToken()
            if (token.isEmpty()) {
                return@withContext Result.success(true)
            }

            var configUrl = ""
            var decryptionKey = ""
            var authException: Throwable? = null

            // 1. Try to perform token authorization to get fresh URL and key
            var responseClientId = ""
            val authResult = performTokenAuth(token)
            if (authResult.isSuccess) {
                val response = authResult.getOrThrow()
                configUrl = response.configUrl
                decryptionKey = response.decryptionKey
                responseClientId = response.clientId
                ProxyServiceState.addLog("Авторизация успешна. Получен Client ID: $responseClientId")
                saveConfigUrl(configUrl)
                saveDecryptionKey(decryptionKey)
                saveSubscriptionExpiresAt(response.endsAt)
                prefs.saveClientId(response.clientId)
            } else {
                authException = authResult.exceptionOrNull()
                // Auth failed (network down / server down).
                // Try to use previously saved config_url and decryption_key as fallback!
                configUrl = getConfigUrl()
                decryptionKey = getDecryptionKey()
                responseClientId = prefs.getClientId()
                ProxyServiceState.addLog("Сбой авторизации (используем кэш): ${authException?.message}. Сохранённый Client ID: $responseClientId")
            }

            if (configUrl.isEmpty() || decryptionKey.isEmpty()) {
                val errMsg = authException?.message ?: "Сервер авторизации недоступен."
                return@withContext Result.failure(Exception(errMsg))
            }

            // 2. Fetch the encrypted config payload with proxy fallback
            var conn: java.net.HttpURLConnection? = null
            var useProxy = false
            val isRunning = ProxyServiceState.isRunning.value
            val isConnected = ProxyServiceState.connectedSince.value != null

            if (isRunning && isConnected) {
                useProxy = true
            }

            try {
                if (useProxy) {
                    try {
                        val currentCfg = prefs.clientConfigFlow.first()
                        val port = currentCfg.localPort.substringAfterLast(":").toIntOrNull() ?: 9000
                        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
                        conn = com.freeturn.app.domain.NetworkUtil.openConnection(configUrl, proxy)
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.connect()
                    } catch (e: Exception) {
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

                    // 3. Decrypt payload
                    val decryptedJson = decryptConfigBytes(encryptedBytes, decryptionKey)

                    // 4. Parse JSON config and save settings
                    val json = JSONObject(decryptedJson)

                    // Relay options
                    val relay = json.getJSONObject("relay")
                    val serverAddress = relay.getString("server_address")
                    val localPort = "127.0.0.1:" + relay.getString("local_port")
                    val threads = relay.getInt("threads")
                    val streamsPerCred = relay.optInt("streams_per_cred", 10)

                    // VK Link
                    val vkLink = json.getString("vk_call_link")

                    // App settings
                    val appSettings = json.optJSONObject("app_settings")
                    val alternativeTurnEnabled = appSettings?.optBoolean("alternative_turn_enabled", false) ?: false
                    val alternativeTurnAddress = appSettings?.optString("alternative_turn_address", "") ?: ""
                    val dnsResolverMode = appSettings?.optString("dns_resolver_mode", "auto") ?: "auto"
                    val useCarrierDns = appSettings?.optBoolean("use_carrier_dns", false) ?: false
                    val syncServerSwitches = appSettings?.optBoolean("sync_server_switches", true) ?: true

                    // Logging
                    val logging = json.optJSONObject("logging")
                    val debugMode = logging?.optBoolean("debug", true) ?: true

                    // WireGuard
                    val wireguard = json.optJSONObject("wireguard")
                    if (wireguard != null) {
                        saveWgConfig(wireguard.toString())
                    }

                    val forcePort443 = appSettings?.optBoolean("force_port_443", false) ?: false

                    // Save to ClientConfig - Thread safe read
                    val current = prefs.clientConfigFlow.first()
                    val updatedConfig = ClientConfig(
                        serverAddress = serverAddress,
                        vkLink = current.vkLink,
                        systemVkLink = vkLink,
                        threads = threads,
                        streamsPerCred = streamsPerCred,
                        useUdp = relay.optBoolean("udp", current.useUdp),
                        manualCaptcha = appSettings?.optBoolean("manual_captcha", current.manualCaptcha) ?: current.manualCaptcha,
                        localPort = localPort,
                        isRawMode = current.isRawMode,
                        rawCommand = current.rawCommand,
                        vlessMode = relay.optBoolean("vless", current.vlessMode),
                        debugMode = debugMode,
                        useCarrierDns = useCarrierDns,
                        dnsMode = dnsResolverMode,
                        forcePort443 = forcePort443,
                        syncServerSwitches = syncServerSwitches,
                        magicSwitch = alternativeTurnEnabled,
                        magicTurn = alternativeTurnAddress,
                        clientId = responseClientId
                    )

                    prefs.saveClientConfig(updatedConfig)

                    // Save Dynamic Theme preference
                    if (appSettings != null && appSettings.has("material_u")) {
                        val materialU = appSettings.getBoolean("material_u")
                        prefs.setDynamicTheme(materialU)
                    }

                    Result.success(true)
                } else {
                    if (authException != null) {
                        Result.failure(Exception(authException.message))
                    } else {
                        Result.failure(Exception("Не удалось загрузить конфигурацию (код ${conn.responseCode})"))
                    }
                }
            } catch (e: Exception) {
                if (authException != null) {
                    Result.failure(Exception(authException.message))
                } else {
                    val userMsg = when (e) {
                        is java.net.UnknownHostException -> "Нет подключения к интернету или сервер конфигурации недоступен"
                        is java.net.SocketTimeoutException, is java.net.ConnectException -> "Превышено время ожидания конфигурации"
                        is javax.net.ssl.SSLException -> "Ошибка SSL/TLS при загрузке конфигурации (возможно, блокировка)"
                        else -> "Не удалось загрузить конфигурацию: ${e.message}"
                    }
                    Result.failure(Exception(userMsg, e))
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun decryptConfigBytes(encryptedBytes: ByteArray, keyHex: String): String {
        if (encryptedBytes.size < 28) {
            throw Exception("Неверный размер бинарного файла конфигурации.")
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

    suspend fun performTokenAuth(token: String): Result<AuthResponse> {
        return withContext(Dispatchers.IO) {
            var conn: java.net.HttpURLConnection? = null
            var useProxy = false
            val isRunning = ProxyServiceState.isRunning.value
            val isConnected = ProxyServiceState.connectedSince.value != null
            val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
            val urlString = "https://tvaldsforge.online/bot/turnproxy/auth?token=$encodedToken"

            if (isRunning && isConnected) {
                useProxy = true
            }

            try {
                if (useProxy) {
                    try {
                        val currentCfg = prefs.clientConfigFlow.first()
                        val port = currentCfg.localPort.substringAfterLast(":").toIntOrNull() ?: 9000
                        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
                        conn = com.freeturn.app.domain.NetworkUtil.openConnection(urlString, proxy)
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.connect()
                    } catch (e: Exception) {
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
                    val json = JSONObject(jsonStr)
                    val configUrl = json.getString("config_url")
                    val decryptionKey = json.getString("decryption_key")
                    val endsAt = json.optLong("ends_at", 0L)
                    val clientId = json.optString("client_id", "")
                    Result.success(AuthResponse(configUrl, decryptionKey, endsAt, clientId))
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
}

data class AuthResponse(val configUrl: String, val decryptionKey: String, val endsAt: Long, val clientId: String)

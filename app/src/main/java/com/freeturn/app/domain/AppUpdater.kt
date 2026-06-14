package com.freeturn.app.domain

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.freeturn.app.data.AppPreferences
import kotlinx.coroutines.delay
import com.freeturn.app.viewmodel.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val context: Context, private val prefs: AppPreferences) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var latestApkUrl: String? = null

    private val apkFile: File
        get() {
            val dir = context.externalCacheDir ?: context.cacheDir
            return File(dir, "update.apk")
        }

    private fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: PackageManager.NameNotFoundException) {
        "0.0.0"
    }

    /**
     * @param silent true — при ошибке сети остаёмся в [UpdateState.Idle] (автопроверка при запуске).
     *               false — показываем [UpdateState.Error] (ручная проверка из UI).
     */
    suspend fun checkForUpdate(silent: Boolean = false) {
        _state.value = UpdateState.Checking
        val proxyReady = com.freeturn.app.ProxyServiceState.isRunning.value &&
                com.freeturn.app.ProxyServiceState.connectedSince.value != null
        Log.d("AppUpdater", "checkForUpdate: silent=$silent, proxyReady=$proxyReady")
        try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }

            val remoteVersion = release.getString("tag_name").removePrefix("v")
            Log.d("AppUpdater", "Remote version tags: $remoteVersion, current: ${getCurrentVersion()}")

            if (isNewer(remoteVersion, getCurrentVersion())) {
                latestApkUrl = findApkUrl(release)
                if (latestApkUrl != null) {
                    val changelog = release.optString("body", "").trim()
                    Log.d("AppUpdater", "New version available: $remoteVersion, URL: $latestApkUrl")
                    _state.value = UpdateState.Available(remoteVersion, changelog)
                } else {
                    Log.e("AppUpdater", "APK not found in release assets")
                    _state.value = if (silent) UpdateState.Idle
                    else UpdateState.Error("APK не найден в релизе")
                }
            } else {
                Log.d("AppUpdater", "No newer version found. Remote: $remoteVersion, current: ${getCurrentVersion()}")
                _state.value = UpdateState.NoUpdate
            }
        } catch (e: CancellationException) {
            // Отмена корутины — штатный путь (structured concurrency), не ошибка сети.
            throw e
        } catch (e: Exception) {
            Log.e("AppUpdater", "Error checking for update", e)
            _state.value = if (silent) UpdateState.Idle
            else UpdateState.Error("Нет соединения с сервером")
        }
    }

    suspend fun downloadUpdate() {
        val url = latestApkUrl ?: run {
            _state.value = UpdateState.Error("URL обновления не найден")
            return
        }

        _state.value = UpdateState.Downloading(0)
        val useProxy = com.freeturn.app.ProxyServiceState.isRunning.value &&
                com.freeturn.app.ProxyServiceState.connectedSince.value != null
        try {
            downloadUpdateInternal(url, useProxy)
        } catch (e: Exception) {
            if (useProxy) {
                try {
                    downloadUpdateInternal(url, false)
                } catch (e2: Exception) {
                    apkFile.delete()
                    _state.value = UpdateState.Error("Ошибка загрузки: ${e2.message}")
                }
            } else {
                apkFile.delete()
                _state.value = UpdateState.Error("Ошибка загрузки: ${e.message}")
            }
        }
    }

    private suspend fun downloadUpdateInternal(url: String, useProxy: Boolean) {
        if (useProxy) {
            downloadFileViaDownloadManager(url, apkFile) { progress ->
                _state.value = UpdateState.Downloading(progress)
            }
            _state.value = UpdateState.ReadyToInstall
            return
        }

        withContext(Dispatchers.IO) {
            var currentUrl = url
            var redirectCount = 0
            val maxRedirects = 5
            var connection: HttpURLConnection? = null

            try {
                while (redirectCount < maxRedirects) {
                    val conn = openCustomConnection(currentUrl, false)
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000

                    val responseCode = conn.responseCode
                    if (responseCode in listOf(301, 302, 303, 307, 308)) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location != null) {
                            currentUrl = URL(URL(currentUrl), location).toString()
                            redirectCount++
                            continue
                        }
                    }
                    connection = conn
                    break
                }

                val activeConn = connection ?: throw java.io.IOException("Превышено число перенаправлений")
                activeConn.connect()
                if (activeConn.responseCode !in 200..299) {
                    throw java.io.IOException("HTTP ${activeConn.responseCode}")
                }

                val totalSize = activeConn.contentLength.toLong()
                var downloaded = 0L

                activeConn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalSize > 0) {
                                _state.value = UpdateState.Downloading(
                                    (downloaded * 100 / totalSize).toInt()
                                )
                            }
                        }
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
        _state.value = UpdateState.ReadyToInstall
    }

    fun installUpdate() {
        if (!apkFile.exists()) {
            _state.value = UpdateState.Error("Файл обновления не найден")
            return
        }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    fun resetState() {
        _state.value = UpdateState.Idle
    }

    // Private

    /**
     * Находит активную VPN-сеть, через которую можно пробросить соединение.
     * Приложение исключено из VPN (ExcludedApplications), но
     * [android.net.Network.openConnection] позволяет явно привязать сокет
     * к любой сети, включая VPN.
     */
    private fun findVpnNetwork(): android.net.Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = cm.allNetworks
        Log.d("AppUpdater", "findVpnNetwork: scanning ${networks.size} networks")
        return networks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            val isVpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val hasInternet = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Log.d("AppUpdater", "Network $network: caps=$caps, isVpn=$isVpn, hasInternet=$hasInternet")
            isVpn && hasInternet
        }
    }

    /**
     * Открывает HTTP-соединение. Когда [viaTunnel] = true, соединение
     * привязывается к VPN-сети через [android.net.Network.openConnection],
     * минуя ExcludedApplications.
     */
    private fun openConnection(urlStr: String, viaTunnel: Boolean): HttpURLConnection {
        val url = URL(urlStr)
        if (viaTunnel) {
            val vpn = findVpnNetwork()
                ?: throw java.io.IOException("VPN-сеть не найдена")
            val conn = vpn.openConnection(url) as HttpURLConnection
            conn.setRequestProperty("User-Agent", NetworkUtil.BROWSER_UA)
            return conn
        }
        return NetworkUtil.openConnection(urlStr)
    }

    /**
     * Загружает файл по указанному URL через системный DownloadManager.
     * Это необходимо при включенном VPN, так как само приложение исключено из VPN
     * (ExcludedApplications) и прямые попытки привязать сокет к VPN-сети вызывают EPERM.
     * DownloadManager выполняется под системным UID и не имеет этого ограничения.
     */
    private suspend fun downloadFileViaDownloadManager(
        urlStr: String,
        destinationFile: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(urlStr))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationUri(Uri.fromFile(destinationFile))
                .addRequestHeader("User-Agent", NetworkUtil.BROWSER_UA)

            Log.d("AppUpdater", "downloadFileViaDownloadManager: enqueuing request for $urlStr")
            val downloadId = downloadManager.enqueue(request)
            Log.d("AppUpdater", "downloadFileViaDownloadManager: enqueued ID=$downloadId")

            var completed = false
            var success = false
            var errorReason = ""

            try {
                val startTime = System.currentTimeMillis()
                val timeoutMs = if (onProgress != null) 300_000L else 20_000L

                while (!completed && (System.currentTimeMillis() - startTime) < timeoutMs) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    downloadManager.query(query).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (statusIdx != -1) {
                                val status = cursor.getInt(statusIdx)
                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    success = true
                                    completed = true
                                } else if (status == DownloadManager.STATUS_FAILED) {
                                    val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    val reason = if (reasonIdx != -1) cursor.getInt(reasonIdx) else -1
                                    errorReason = "Загрузка через DownloadManager завершилась с ошибкой: статус=$status, код=$reason"
                                    completed = true
                                } else if (status == DownloadManager.STATUS_RUNNING && onProgress != null) {
                                    val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                    val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                    if (totalIdx != -1 && downloadedIdx != -1) {
                                        val total = cursor.getLong(totalIdx)
                                        val downloaded = cursor.getLong(downloadedIdx)
                                        if (total > 0) {
                                            val progress = (downloaded * 100 / total).toInt()
                                            onProgress(progress)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!completed) {
                        delay(250)
                    }
                }

                if (!completed) {
                    downloadManager.remove(downloadId)
                    throw java.io.IOException("Таймаут скачивания через DownloadManager")
                }

                if (!success) {
                    throw java.io.IOException(errorReason.ifEmpty { "Ошибка скачивания через DownloadManager" })
                }

                Log.d("AppUpdater", "downloadFileViaDownloadManager: success for ID=$downloadId, size=${destinationFile.length()} bytes")

                if (!destinationFile.exists() || destinationFile.length() == 0L) {
                    throw java.io.IOException("Скачанный файл пуст или отсутствует")
                }
            } finally {
                if (!success) {
                    downloadManager.remove(downloadId)
                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                }
            }
        }
    }

    /**
     * DNS-серверы, используемые при обращении через VPN-туннель.
     * Google и Cloudflare доступны через туннель.
     */
    private val proxyDohServers = listOf(
        DohServer("https://8.8.8.8/resolve?name=%s&type=A"),
        DohServer("https://1.1.1.1/dns-query?name=%s&type=A", accept = "application/dns-json"),
    )

    /**
     * DNS-серверы, используемые при прямом запросе (без прокси).
     * Яндекс DNS — в белых списках мобильных операторов.
     */
    private val directDohServers = listOf(
        DohServer("https://dns.yandex.com/api/v1/dns-query?name=%s&type=A"),
    )

    private data class DohServer(val urlTemplate: String, val accept: String? = null)

    private suspend fun resolveDnsOverHttps(hostname: String, useProxy: Boolean): String? {
        val servers = if (useProxy) proxyDohServers else directDohServers
        return withContext(Dispatchers.IO) {
            for (server in servers) {
                try {
                    val urlStr = server.urlTemplate.format(hostname)
                    val conn = openConnection(urlStr, useProxy)
                    server.accept?.let { conn.setRequestProperty("Accept", it) }
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.connect()
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().readText())
                        val answers = json.optJSONArray("Answer")
                        if (answers != null && answers.length() > 0) {
                            for (i in 0 until answers.length()) {
                                val ans = answers.getJSONObject(i)
                                if (ans.optInt("type") == 1) {
                                    return@withContext ans.getString("data")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AppUpdater", "DNS DoH resolution failed for ${server.urlTemplate}", e)
                }
            }
            null
        }
    }

    private suspend fun openCustomConnection(urlStr: String, useProxy: Boolean): HttpURLConnection {
        val url = URL(urlStr)
        val host = url.host

        if (url.protocol.lowercase() == "https" && !host.matches(Regex("^[0-9.]+$"))) {
            val ip = resolveDnsOverHttps(host, useProxy)
            if (ip != null) {
                val portStr = if (url.port != -1) ":${url.port}" else ""
                val path = url.file
                val rewrittenUrlStr = "https://$ip$portStr$path"

                val conn = openConnection(rewrittenUrlStr, useProxy) as javax.net.ssl.HttpsURLConnection
                val defaultFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
                conn.sslSocketFactory = SniSSLSocketFactory(defaultFactory, host)
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                    javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
                }
                return conn
            }
        }

        return openConnection(urlStr, useProxy)
    }

    private suspend fun fetchLatestRelease(): JSONObject {
        val proxyReady = com.freeturn.app.ProxyServiceState.isRunning.value &&
                com.freeturn.app.ProxyServiceState.connectedSince.value != null
        return try {
            fetchLatestReleaseInternal(proxyReady)
        } catch (e: Exception) {
            if (proxyReady) {
                fetchLatestReleaseInternal(false)
            } else {
                throw e
            }
        }
    }

    private suspend fun fetchLatestReleaseInternal(useProxy: Boolean): JSONObject {
        if (useProxy) {
            val dir = context.externalCacheDir ?: context.cacheDir
            val tempFile = File(dir, "release_${System.currentTimeMillis()}.json")
            try {
                downloadFileViaDownloadManager(RELEASES_URL, tempFile, null)
                val text = tempFile.readText()
                return JSONObject(text)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }

        val connection = openCustomConnection(RELEASES_URL, false)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        try {
            val code = connection.responseCode
            if (code == 200) {
                return JSONObject(connection.inputStream.bufferedReader().readText())
            }
            throw java.io.IOException("GitHub API HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    private fun findApkUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                return asset.getString("browser_download_url")
            }
        }
        return null
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/VQSeGo/VK-Turn-Proxy/releases/latest"

        fun isNewer(remote: String, current: String): Boolean {
            val rParts = remote.split("-", limit = 2)
            val cParts = current.split("-", limit = 2)

            val rNums = rParts[0].split(".").map { it.toIntOrNull() ?: 0 }
            val cNums = cParts[0].split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(rNums.size, cNums.size)) {
                val rv = rNums.getOrElse(i) { 0 }
                val cv = cNums.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }

            val rHasPre = rParts.size > 1
            val cHasPre = cParts.size > 1

            if (!rHasPre && cHasPre) return true
            if (rHasPre && !cHasPre) return false
            if (!rHasPre && !cHasPre) return false

            val rPreFields = rParts[1].split(".", "-")
            val cPreFields = cParts[1].split(".", "-")
            for (i in 0 until maxOf(rPreFields.size, cPreFields.size)) {
                val rf = rPreFields.getOrElse(i) { "" }
                val cf = cPreFields.getOrElse(i) { "" }
                if (rf == cf) continue

                val rfNum = rf.toIntOrNull()
                val cfNum = cf.toIntOrNull()

                return when {
                    rfNum != null && cfNum != null -> rfNum > cfNum
                    rfNum != null && cfNum == null -> false
                    rfNum == null && cfNum != null -> true
                    else -> rf.compareTo(cf) > 0
                }
            }
            return false
        }
    }
}

class SniSSLSocketFactory(
    private val delegate: javax.net.ssl.SSLSocketFactory,
    private val targetHost: String
) : javax.net.ssl.SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket {
        return delegate.createSocket(s, targetHost, port, autoClose)
    }

    override fun createSocket(host: String?, port: Int): java.net.Socket = delegate.createSocket(host, port)
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
        delegate.createSocket(host, port, localHost, localPort)
    override fun createSocket(address: java.net.InetAddress?, port: Int): java.net.Socket = delegate.createSocket(address, port)
    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
        delegate.createSocket(address, port, localAddress, localPort)
}

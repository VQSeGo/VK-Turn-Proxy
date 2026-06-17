package com.freeturn.app.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.viewmodel.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
        get() = File(context.cacheDir, "update.apk")

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
        try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            if (release == null) {
                _state.value = if (silent) UpdateState.Idle
                else UpdateState.Error("Не удалось получить информацию о релизе")
                return
            }

            val remoteVersion = release.getString("tag_name").removePrefix("v")

            if (isNewer(remoteVersion, getCurrentVersion())) {
                latestApkUrl = findApkUrl(release)
                if (latestApkUrl != null) {
                    val changelog = if (release.isNull("body")) "" else release.optString("body", "").trim()
                    _state.value = UpdateState.Available(remoteVersion, changelog)
                } else {
                    _state.value = if (silent) UpdateState.Idle
                    else UpdateState.Error("APK не найден в релизе")
                }
            } else {
                _state.value = UpdateState.NoUpdate
            }
        } catch (e: CancellationException) {
            // Отмена корутины — штатный путь (structured concurrency), не ошибка сети.
            throw e
        } catch (_: Exception) {
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
        val useProxy = com.freeturn.app.ProxyServiceState.isRunning.value
        try {
            downloadUpdateInternal(url, useProxy)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (useProxy) {
                try {
                    downloadUpdateInternal(url, false)
                } catch (e2: Exception) {
                    if (e2 is CancellationException) throw e2
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
        withContext(Dispatchers.IO) {
            var currentUrl = url
            var redirectCount = 0
            val maxRedirects = 5
            var connection: HttpURLConnection? = null

            try {
                while (redirectCount < maxRedirects) {
                    val conn = openConnection(currentUrl, useProxy)
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
                            if (!isActive) {
                                throw CancellationException("Download cancelled")
                            }
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

        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _state.value = UpdateState.Error("Не удалось запустить установщик пакетов: ${e.message}")
        }
    }

    fun resetState() {
        _state.value = UpdateState.Idle
    }

    // Private

    private suspend fun openConnection(urlStr: String, useProxy: Boolean): HttpURLConnection {
        return if (useProxy) {
            val cfg = prefs.clientConfigFlow.first()
            val port = cfg.localPort.substringAfterLast(":").toIntOrNull() ?: 9000
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
            NetworkUtil.openConnection(urlStr, proxy)
        } else {
            NetworkUtil.openConnection(urlStr)
        }
    }

    private suspend fun fetchLatestRelease(): JSONObject? {
        val useProxy = com.freeturn.app.ProxyServiceState.isRunning.value
        return try {
            fetchLatestReleaseInternal(useProxy)
        } catch (e: Exception) {
            if (useProxy) {
                fetchLatestReleaseInternal(false)
            } else {
                throw e
            }
        }
    }

    private suspend fun fetchLatestReleaseInternal(useProxy: Boolean): JSONObject? {
        val connection = openConnection(RELEASES_URL, useProxy)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return try {
            if (connection.responseCode == 200) {
                JSONObject(connection.inputStream.bufferedReader().readText())
            } else null
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
            val r = remote.removePrefix("v").trim()
            val c = current.removePrefix("v").trim()

            val rParts = r.split("-", limit = 2)
            val cParts = c.split("-", limit = 2)

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

            fun getPreReleaseComponents(pre: String): Pair<String, Int> {
                val clean = pre.lowercase().replace("-", "").replace(".", "")
                val match = Regex("^([a-z]+)(\\d+)$").matchEntire(clean)
                if (match != null) {
                    val name = match.groupValues[1]
                    val num = match.groupValues[2].toIntOrNull() ?: 0
                    return Pair(name, num)
                }
                return Pair(clean, 0)
            }

            val (rName, rNum) = getPreReleaseComponents(rParts[1])
            val (cName, cNum) = getPreReleaseComponents(cParts[1])

            return if (rName == cName) {
                rNum > cNum
            } else {
                rName.compareTo(cName) > 0
            }
        }
    }
}

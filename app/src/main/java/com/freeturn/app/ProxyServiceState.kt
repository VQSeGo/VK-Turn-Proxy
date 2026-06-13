package com.freeturn.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Сессия ручной капчи. sessionId позволяет диалогу различать соседние
 * капча-сессии с одинаковым URL и пересоздавать WebView через `key(sessionId)`.
 */
data class CaptchaSession(val url: String, val sessionId: Long)

/**
 * Агрегированная статистика подключений прокси-ядра.
 *
 * - [active] — число реально живых каналов (DTLS-потоков для не-VLESS, smux-сессий для VLESS).
 * - [total]  — целевое число каналов. 0 означает «ещё неизвестно» (VLESS до первой waiting-строки).
 */
data class ConnectionStats(val active: Int, val total: Int) {
    companion object {
        val IDLE = ConnectionStats(0, 0)
    }
}

enum class CaptchaVerificationState {
    NONE,
    REQUIRED,
    SUBMITTING,
    FAILED
}

/**
 * Централизованное состояние прокси-сервиса.
 * Публичный API — только read-only Flow, мутация через явные методы.
 */
object ProxyServiceState {

    val configMutex = Mutex()
    @Volatile var lastFetchTime = 0L

    private const val MAX_LOG_LINES = 200

    @Volatile private var currentLogFile: File? = null
    private val logScope = CoroutineScope(Dispatchers.IO)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val proxyFailed: SharedFlow<Unit> = _proxyFailed.asSharedFlow()

    private val _startupResult = MutableStateFlow<StartupResult?>(null)
    val startupResult: StateFlow<StartupResult?> = _startupResult.asStateFlow()

    private val _captchaSession = MutableStateFlow<CaptchaSession?>(null)
    val captchaSession: StateFlow<CaptchaSession?> = _captchaSession.asStateFlow()

    private val _connectionStats = MutableStateFlow(ConnectionStats.IDLE)
    val connectionStats: StateFlow<ConnectionStats> = _connectionStats.asStateFlow()

    private val _watchdogAttempt = MutableStateFlow(0)
    val watchdogAttempt: StateFlow<Int> = _watchdogAttempt.asStateFlow()

    private val _captchaVerificationState = MutableStateFlow(CaptchaVerificationState.NONE)
    val captchaVerificationState: StateFlow<CaptchaVerificationState> = _captchaVerificationState.asStateFlow()

    fun setWatchdogAttempt(attempt: Int) {
        _watchdogAttempt.value = attempt
    }

    fun setCaptchaVerificationState(state: CaptchaVerificationState) {
        _captchaVerificationState.value = state
    }

    /**
     * Момент первого успешного подключения в рамках текущей сессии пользователя
     * (`SystemClock.elapsedRealtime()` — устойчиво к переводу часов).
     *
     * null = ни одного успешного подключения в этой сессии ещё не было, либо
     * прокси остановлен. Watchdog-рестарт НЕ сбрасывает значение: с точки
     * зрения пользователя он нажал «вкл» один раз, и время активности —
     * это время от первого Established до остановки.
     */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
    }

    fun setStartupResult(result: StartupResult?) {
        _startupResult.value = result
    }

    fun emitFailed() {
        _proxyFailed.tryEmit(Unit)
    }

    fun startNewSession(context: Context) {
        try {
            val dir = File(context.filesDir, "sessions")
            if (!dir.exists()) dir.mkdirs()

            // Удаляем сессии старше 48 часов
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > 48 * 60 * 60 * 1000) {
                    file.delete()
                }
            }

            val timeStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            currentLogFile = File(dir, "session_$timeStr.log")
            
            addLog("=== СЕССИЯ СТАРТОВАЛА: $timeStr ===")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addLog(msg: String) {
        _logs.update { current ->
            val next = current + msg
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
        val file = currentLogFile ?: return
        logScope.launch {
            try {
                synchronized(this@ProxyServiceState) {
                    file.appendText("$msg\n")
                }
            } catch (e: Exception) {
                // Игнорируем ошибки записи
            }
        }
    }

    fun exportLogs(context: Context) {
        logScope.launch {
            try {
                val dir = File(context.filesDir, "sessions")
                if (!dir.exists()) {
                    return@launch
                }
                
                val now = System.currentTimeMillis()
                val limit24h = now - 24 * 60 * 60 * 1000
                
                val files = dir.listFiles()
                    ?.filter { it.name.startsWith("session_") && it.name.endsWith(".log") && it.lastModified() >= limit24h }
                    ?.sortedBy { it.lastModified() }
                    
                if (files.isNullOrEmpty()) {
                    return@launch
                }
                
                val tempFile = File(context.cacheDir, "proxy_logs_export.txt")
                if (tempFile.exists()) tempFile.delete()
                
                tempFile.bufferedWriter().use { writer ->
                    files.forEach { file ->
                        val sessionName = file.name.removePrefix("session_").removeSuffix(".log").replace("_", " ")
                        writer.write("=========================================\n")
                        writer.write("=== СЕССИЯ: $sessionName ===\n")
                        writer.write("=========================================\n")
                        synchronized(this@ProxyServiceState) {
                            file.forEachLine { line ->
                                writer.write(line)
                                writer.write("\n")
                            }
                        }
                        writer.write("\n\n")
                    }
                }
                
                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, tempFile)
                
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(intent, "Поделиться логами за последние 24 часа").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setCaptchaSession(session: CaptchaSession?) {
        _captchaSession.value = session
    }

    fun setConnectionStats(stats: ConnectionStats) {
        _connectionStats.value = stats
    }

    /**
     * Запомнить момент первого успешного подключения сессии. Повторные вызовы
     * игнорируются — таймер стартует один раз и не перезапускается при
     * watchdog-рестарте/временной потере всех потоков.
     */
    fun markConnectedIfAbsent(nowElapsed: Long) {
        _connectedSince.compareAndSet(null, nowElapsed)
    }

    fun clearConnectedSince() {
        _connectedSince.value = null
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}

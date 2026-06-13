package com.freeturn.app.domain

import android.content.Context
import android.content.Intent
import android.os.Build
import com.freeturn.app.ConnectionStats
import com.freeturn.app.ProxyService
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.StartupResult
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.viewmodel.ProxyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Управляет жизненным циклом прокси-сервиса: старт, стоп, отслеживание состояния.
 * Создаётся как Koin `single` и живёт в Application scope — `destroy()` не вызывается
 * намеренно, так как scope привязан к процессу приложения.
 */
class LocalProxyManager(private val context: Context) {

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resetJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch { observeProxyLifecycle() }
        scope.launch { observeCombinedState() }
    }

    private suspend fun observeProxyLifecycle() {
        ProxyServiceState.proxyFailed.collect {
            setErrorWithAutoReset("Прокси упал ${ProxyService.MAX_RESTARTS} раз — проверьте настройки")
        }
    }

    private suspend fun observeCombinedState() {
        combine(
            ProxyServiceState.isRunning,
            ProxyServiceState.connectionStats,
            ProxyServiceState.captchaSession
        ) { running, stats, captcha ->
            Triple(running, stats, captcha)
        }.collect { (running, stats, captcha) ->
            val current = _proxyState.value
            if (current is ProxyState.Error) {
                return@collect
            }

            if (!running) {
                if (current !is ProxyState.Starting) {
                    _proxyState.value = ProxyState.Idle
                }
            } else if (captcha != null) {
                _proxyState.value = ProxyState.CaptchaRequired(captcha.url, captcha.sessionId)
            } else {
                if (stats.active > 0) {
                    _proxyState.value = ProxyState.Running(stats.active, stats.total)
                } else {
                    if (current is ProxyState.Starting) {
                        // Keep Starting
                    } else if (current is ProxyState.CaptchaRequired) {
                        _proxyState.value = ProxyState.Connecting(stats.active, stats.total)
                    } else if (current is ProxyState.Idle) {
                        _proxyState.value = ProxyState.Starting
                    } else {
                        _proxyState.value = ProxyState.Connecting(stats.active, stats.total)
                    }
                }
            }
        }
    }

    suspend fun startProxy(cfg: ClientConfig, coreOnly: Boolean = false) {
        if (ProxyServiceState.isRunning.value) return
        if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle

        val activeLink = if (cfg.useCustomVkLink) cfg.vkLink else cfg.systemVkLink
        if (!cfg.isRawMode && (cfg.serverAddress.isBlank() || activeLink.isBlank())) {
            setErrorWithAutoReset("Не заполнены настройки клиента")
            return
        }
        if (cfg.isRawMode && cfg.rawCommand.isBlank()) {
            setErrorWithAutoReset("Не задана raw-команда")
            return
        }

        _proxyState.value = ProxyState.Starting

        ProxyServiceState.clearLogs()
        ProxyServiceState.setStartupResult(null)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.clearConnectedSince()
        val intent = Intent(context, ProxyService::class.java).apply {
            if (coreOnly) {
                putExtra(ProxyService.EXTRA_CORE_ONLY, true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Умное ожидание стартапа. Watchdog внутри сервиса делает до MAX_RESTARTS
        // попыток с backoff до 30с — фиксированный таймаут (20с) обрывал UI ещё
        // на первой-второй ретрай-итерации. Теперь ждём пока:
        //   • сервис не отдаст StartupResult (Success/Failed), ИЛИ
        //   • сервис не остановится сам (watchdog исчерпал лимит → isRunning=false).
        // Верхняя граница в 5 минут — страховка от подвисшего сервиса.
        val result = withTimeoutOrNull(5 * 60_000L) {
            // Wait until the service starts (isRunning becomes true) OR until a startup result is set
            combine(
                ProxyServiceState.startupResult,
                ProxyServiceState.isRunning
            ) { sr, running -> sr to running }
                .first { (sr, running) -> sr != null || running }

            // Once it has started or failed early, wait until it either has a startup result OR stops running
            combine(
                ProxyServiceState.startupResult,
                ProxyServiceState.isRunning
            ) { sr, running -> sr to running }
                .first { (sr, running) -> sr != null || !running }
                .first
        }

        if (_proxyState.value is ProxyState.Error) return
        if (_proxyState.value == ProxyState.Idle) {
            return
        }

        when (result) {
            null -> {
                stopProxy()
                setErrorWithAutoReset("Прокси не запустился")
            }
            is StartupResult.Failed -> {
                stopProxy()
                setErrorWithAutoReset(result.message)
            }
            is StartupResult.Success -> {
                val s = ProxyServiceState.connectionStats.value
                _proxyState.value = if (s.active > 0) {
                    ProxyState.Running(s.active, s.total)
                } else {
                    ProxyState.Connecting(s.active, s.total)
                }
            }
        }
    }

    fun stopProxy() {
        context.stopService(Intent(context, ProxyService::class.java))
        _proxyState.value = ProxyState.Idle
    }

    fun dismissCaptcha() {
        ProxyServiceState.setCaptchaSession(null)
    }

    fun setErrorWithAutoReset(message: String) {
        synchronized(this) {
            resetJob?.cancel()
            _proxyState.value = ProxyState.Error(message)
            resetJob = scope.launch(Dispatchers.Main) {
                delay(4_000)
                synchronized(this@LocalProxyManager) {
                    if (_proxyState.value is ProxyState.Error) {
                        _proxyState.value = ProxyState.Idle
                    }
                }
            }
        }
    }

    fun clearState() {
        _proxyState.value = ProxyState.Idle
    }

    fun destroy() {
        scope.cancel()
    }
}

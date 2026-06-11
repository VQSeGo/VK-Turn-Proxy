package com.freeturn.app.domain

import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class ProxyOrchestrator(
    private val prefs: AppPreferences,
    private val proxyManager: LocalProxyManager
) {
    suspend fun restartProxyIfRunning() {
        if (!ProxyServiceState.isRunning.value) return
        proxyManager.stopProxy()
        withTimeoutOrNull(2_000) {
            ProxyServiceState.isRunning.first { !it }
        }
        proxyManager.startProxy(prefs.clientConfigFlow.first())
    }
}

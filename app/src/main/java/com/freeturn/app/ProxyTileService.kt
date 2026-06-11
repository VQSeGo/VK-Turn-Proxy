package com.freeturn.app

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class ProxyTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var isProxyRunning = false

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        ProxyServiceState.isRunning.onEach { running ->
            isProxyRunning = running
            updateTileState()
        }.launchIn(scope!!)
    }

    override fun onStopListening() {
        super.onStopListening()
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        super.onClick()
        val currentScope = scope ?: return
        currentScope.launch {
            if (isProxyRunning) {
                val intent = Intent(this@ProxyTileService, ProxyService::class.java)
                stopService(intent)
            } else {
                ProxyServiceState.clearLogs()
                ProxyServiceState.setStartupResult(null)
                
                val prefs = com.freeturn.app.data.AppPreferences(applicationContext)
                val expiresAtSeconds = prefs.getSubscriptionExpiresAt()
                val currentTimeSeconds = System.currentTimeMillis() / 1000L
                val isExpired = expiresAtSeconds != 0L && currentTimeSeconds >= expiresAtSeconds
                val elapsedSeconds = if (isExpired) currentTimeSeconds - expiresAtSeconds else 0L
                val within12Hours = elapsedSeconds < 12 * 3600

                val intent = Intent(this@ProxyTileService, ProxyService::class.java).apply {
                    if (isExpired && !within12Hours) {
                        putExtra(ProxyService.EXTRA_CORE_ONLY, true)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (isProxyRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_service_label)
        tile.contentDescription = getString(R.string.tile_service_label)
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile_nearby)
        tile.updateTile()
    }
}

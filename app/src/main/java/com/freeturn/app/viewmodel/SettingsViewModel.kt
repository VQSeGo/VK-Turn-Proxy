package com.freeturn.app.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.Profile
import com.freeturn.app.data.ProfilesSnapshot
import com.freeturn.app.domain.AppUpdater
import com.freeturn.app.domain.LocalProxyManager
import com.freeturn.app.domain.ProxyOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class SettingsViewModel(
    private val prefs: AppPreferences,
    private val proxyManager: LocalProxyManager,
    private val appUpdater: AppUpdater,
    private val orchestrator: ProxyOrchestrator,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    val clientConfig: StateFlow<ClientConfig> = prefs.clientConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientConfig())

    val proxyListen: StateFlow<String> = prefs.proxyListenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "0.0.0.0:56000")

    val proxyConnect: StateFlow<String> = prefs.proxyConnectFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "127.0.0.1:40537")

    val dynamicTheme: StateFlow<Boolean> = prefs.dynamicThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val tgSubscribeShown: StateFlow<Boolean> = prefs.tgSubscribeShownFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val devMode: StateFlow<Boolean> = prefs.devModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val profilesSnapshot: StateFlow<ProfilesSnapshot> = prefs.profilesSnapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesSnapshot())

    val updateState: StateFlow<UpdateState> = appUpdater.state

    val fallbackPin: StateFlow<String?> = prefs.fallbackPinState.asStateFlow()

    fun isKeystoreFailed(): Boolean = prefs.isKeystoreFailed()

    fun verifyAndSetFallbackPin(pin: String): Boolean {
        return prefs.verifyAndSetFallbackPin(pin)
    }

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initialOnboardingDone = MutableStateFlow(false)
    val initialOnboardingDone: StateFlow<Boolean> = _initialOnboardingDone.asStateFlow()

    private val _initialTgSubscribeShown = MutableStateFlow(false)
    val initialTgSubscribeShown: StateFlow<Boolean> = _initialTgSubscribeShown.asStateFlow()

    val onboardingDone: StateFlow<Boolean> = prefs.onboardingDoneFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    private val profileMutex = Mutex()

    init {
        viewModelScope.launch {
            val done = prefs.onboardingDoneFlow.first()
            val tgShown = prefs.tgSubscribeShownFlow.first()
            _initialOnboardingDone.value = done
            _initialTgSubscribeShown.value = tgShown
            _isInitialized.value = true
        }
        viewModelScope.launch {
            appUpdater.checkForUpdate(silent = true)
        }
    }

    fun setPrivacyMode(enabled: Boolean) { _privacyMode.value = enabled }

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicTheme(enabled) }
    }

    fun setTgSubscribeShown() {
        viewModelScope.launch { prefs.setTgSubscribeShown() }
    }

    fun setDevMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setDevMode(enabled) }
    }

    fun setOnboardingDone() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }

    fun saveClientConfig(config: ClientConfig, expectedActiveId: String? = null) {
        viewModelScope.launch {
            profileMutex.withLock {
                val activeId = prefs.profilesSnapshot.first().activeId
                if (expectedActiveId != null && expectedActiveId != activeId) return@withLock
                persistClient(config)
            }
        }
    }

    fun setDnsMode(value: String) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.clientConfigFlow.first()
                if (current.dnsMode == value) return@withLock
                persistClient(current.copy(dnsMode = value))
            }
        }
    }

    fun setUseUdp(enabled: Boolean) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.clientConfigFlow.first()
                if (current.useUdp == enabled) return@withLock
                persistClient(current.copy(useUdp = enabled))
            }
        }
    }

    fun setManualCaptcha(enabled: Boolean) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.clientConfigFlow.first()
                if (current.manualCaptcha == enabled) return@withLock
                persistClient(current.copy(manualCaptcha = enabled))
            }
        }
    }

    fun setUseCarrierDns(enabled: Boolean) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.clientConfigFlow.first()
                if (current.useCarrierDns == enabled) return@withLock
                persistClient(current.copy(useCarrierDns = enabled))
            }
        }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.clientConfigFlow.first()
                if (current.debugMode == enabled) return@withLock
                persistClient(current.copy(debugMode = enabled))
            }
        }
    }

    fun setMagicSwitch(enabled: Boolean) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.clientConfigFlow.first()
                if (current.magicSwitch == enabled) return@withLock
                persistClient(current.copy(magicSwitch = enabled))
            }
        }
    }


    fun saveProxyServerConfig(listen: String, connect: String) {
        viewModelScope.launch {
            profileMutex.withLock { prefs.saveProxyConfig(listen, connect); mirrorActiveProfile() }
        }
    }

    // --- Profiles ---
    fun updateActiveProfileFromCurrent() {
        viewModelScope.launch {
            profileMutex.withLock {
                mirrorActiveProfile()
            }
        }
    }

    fun saveCurrentAsProfile(name: String) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.profilesSnapshot.first()
                val client = prefs.clientConfigFlow.first()
                val listen = prefs.proxyListenFlow.first()
                val connect = prefs.proxyConnectFlow.first()
                val ssh = prefs.sshConfigFlow.first()
                val server = prefs.serverOptsFlow.first()
                val base = name.trim().ifBlank {
                    serverAddrToProfileName(client.serverAddress)
                }
                val profile = Profile(
                    id = UUID.randomUUID().toString(),
                    name = uniqueProfileName(base, current.list, null),
                    client = client,
                    proxyListen = listen,
                    proxyConnect = connect,
                    ssh = ssh,
                    server = server
                )
                prefs.saveProfiles(current.list + profile)
                prefs.setActiveProfileId(profile.id)
            }
        }
    }

    private suspend fun mirrorActiveProfile() {
        val current = prefs.profilesSnapshot.first()
        val activeId = current.activeId ?: return
        val client = prefs.clientConfigFlow.first()
        val listen = prefs.proxyListenFlow.first()
        val connect = prefs.proxyConnectFlow.first()
        val ssh = prefs.sshConfigFlow.first()
        val server = prefs.serverOptsFlow.first()
        val updated = current.list.map {
            if (it.id == activeId)
                it.copy(
                    client = client,
                    proxyListen = listen,
                    proxyConnect = connect,
                    ssh = ssh,
                    server = server
                )
            else it
        }
        if (updated != current.list) prefs.saveProfiles(updated)
    }

    private suspend fun persistClient(config: ClientConfig) {
        prefs.saveClientConfig(config)
        mirrorActiveProfile()
    }

    fun renameProfile(id: String, name: String) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.profilesSnapshot.first()
                val target = current.list.firstOrNull { it.id == id } ?: return@withLock
                val base = name.trim().ifBlank { target.name }
                val unique = uniqueProfileName(base, current.list, id)
                val updated = current.list.map {
                    if (it.id == id) it.copy(name = unique) else it
                }
                prefs.saveProfiles(updated)
            }
        }
    }

    fun applyProfile(id: String) {
        viewModelScope.launch {
            val target = profileMutex.withLock {
                val current = prefs.profilesSnapshot.first()
                val t = current.list.firstOrNull { it.id == id } ?: return@withLock null
                prefs.saveClientConfig(t.client)
                prefs.saveProxyConfig(t.proxyListen, t.proxyConnect)
                prefs.saveSshConfig(t.ssh)
                prefs.saveServerOpts(t.server)
                prefs.setActiveProfileId(t.id)
                t
            } ?: return@launch

            if (ProxyServiceState.isRunning.value) {
                proxyManager.stopProxy()
                withTimeoutOrNull(2_000) {
                    ProxyServiceState.isRunning.first { !it }
                }
                proxyManager.startProxy(target.client)
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            profileMutex.withLock {
                val current = prefs.profilesSnapshot.first()
                val remaining = current.list.filterNot { it.id == id }
                prefs.saveProfiles(remaining)
                if (current.activeId == id) {
                    val next = remaining.firstOrNull()
                    prefs.setActiveProfileId(next?.id)
                    if (next != null) {
                        prefs.saveClientConfig(next.client)
                        prefs.saveProxyConfig(next.proxyListen, next.proxyConnect)
                        prefs.saveSshConfig(next.ssh)
                        prefs.saveServerOpts(next.server)
                    }
                }
            }
        }
    }

    private fun serverAddrToProfileName(serverAddr: String): String =
        serverAddr.substringBefore(':').takeIf { it.isNotBlank() }
            ?: appContext.getString(com.freeturn.app.R.string.profile_default_name)

    private fun uniqueProfileName(base: String, existing: List<Profile>, excludingId: String?): String {
        val taken = existing
            .filter { it.id != excludingId }
            .map { it.name.trim().lowercase() }
            .toSet()
        val trimmed = base.trim()
        if (trimmed.lowercase() !in taken) return trimmed
        var i = 2
        while ("$trimmed ($i)".lowercase() in taken) i++
        return "$trimmed ($i)"
    }

    // --- Updates ---
    fun checkForUpdate() {
        viewModelScope.launch { appUpdater.checkForUpdate(silent = false) }
    }

    fun downloadUpdate() {
        viewModelScope.launch { appUpdater.downloadUpdate() }
    }

    fun installUpdate() {
        appUpdater.installUpdate()
    }

    fun resetUpdateState() {
        appUpdater.resetState()
    }

    fun resetAllSettings() {
        viewModelScope.launch {
            if (ProxyServiceState.isRunning.value) {
                appContext.stopService(Intent(appContext, ProxyService::class.java))
            }
            prefs.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()

            val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appContext.startActivity(intent)
            }
        }
    }
}

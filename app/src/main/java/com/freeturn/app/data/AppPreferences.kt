@file:Suppress("DEPRECATION")

package com.freeturn.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import androidx.core.content.edit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

data class SshConfig(
    val ip: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "",
    val authType: String = "PASSWORD",
    val sshKey: String = "",
    val hostFingerprint: String = ""
)

object DnsMode {
    const val AUTO = "auto"
    const val UDP = "udp"
    const val DOH = "doh"
    val ALL = listOf(AUTO, UDP, DOH)
}

data class ClientConfig(
    val serverAddress: String = "",
    val vkLink: String = "",
    val systemVkLink: String = "",
    val useCustomVkLink: Boolean = false,
    val threads: Int = 4,
    /** Соответствует флагу `-streams-per-cred` ядра. Дефолт ядра = 10. */
    val streamsPerCred: Int = 10,
    val useUdp: Boolean = true,
    val manualCaptcha: Boolean = false,
    val localPort: String = "127.0.0.1:9000",
    val isRawMode: Boolean = false,
    val rawCommand: String = "",
    val vlessMode: Boolean = false,

    // Если true — добавляется флаг -debug для расширенного вывода в логах.
    val debugMode: Boolean = false,
    // Если true — в argv передаётся -dns-servers с DNS активной сети (оператор связи).
    val useCarrierDns: Boolean = false,
    // "auto" | "udp" | "doh" — соответствует флагу -dns ядра.
    val dnsMode: String = DnsMode.AUTO,
    // Если true — в argv ядра добавляется -port 443.
    val forcePort443: Boolean = false,
    /**
     * Если true — изменения vlessMode/vlessBond/wrapEnabled на клиенте дёргают
     * рестарт сервера (текущее поведение). Если false — флаги меняются только
     * у клиента, серверный процесс не трогается.
     */
    val syncServerSwitches: Boolean = true,
    val magicSwitch: Boolean = false,
    /** Адрес для флага -turn ядра, если magicSwitch включён. Пусто = не передавать. */
    val magicTurn: String = ""
)

class AppPreferences(context: Context) {
    private val context = context.applicationContext

    val keystoreFailed = java.util.concurrent.atomic.AtomicBoolean(false)
    val fallbackPinState = MutableStateFlow<String?>(null)

    fun isKeystoreFailed(): Boolean = keystoreFailed.get()
    fun setFallbackPin(pin: String) {
        fallbackPinState.value = pin
    }
    fun getFallbackPin(): String? = fallbackPinState.value

    init {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            keystoreFailed.set(true)
        }
        // Asynchronous migration of legacy credentials and profiles to EncryptedSharedPreferences
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.dataStore.edit { prefs ->
                    val legacyPass = prefs[SSH_PASS_LEGACY]
                    if (!legacyPass.isNullOrEmpty()) {
                        putEncryptedString("ssh_pass", legacyPass)
                        prefs.remove(SSH_PASS_LEGACY)
                    }
                    val legacyKey = prefs[SSH_KEY_LEGACY]
                    if (!legacyKey.isNullOrEmpty()) {
                        putEncryptedString("ssh_key", legacyKey)
                        prefs.remove(SSH_KEY_LEGACY)
                    }
                    val legacyProfiles = prefs[PROFILES_JSON]
                    if (!legacyProfiles.isNullOrEmpty()) {
                        putEncryptedString("profiles_json", legacyProfiles)
                        prefs.remove(PROFILES_JSON)
                    }
                    prefs.remove(booleanPreferencesKey("client_no_dtls"))
                    prefs.remove(CLIENT_TURN_PORT_443_LEGACY)
                    prefs.remove(CLIENT_ALLOCS_PER_STREAM_LEGACY)
                    prefs.remove(CLIENT_VK_LINKS_LEGACY)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        val SSH_IP = stringPreferencesKey("ssh_ip")
        val SSH_PORT = intPreferencesKey("ssh_port")
        val SSH_USER = stringPreferencesKey("ssh_user")
        val SSH_AUTH_TYPE = stringPreferencesKey("ssh_auth_type")
        val SSH_HOST_FP = stringPreferencesKey("ssh_host_fp")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val CLIENT_SERVER_ADDR = stringPreferencesKey("client_server_addr")
        val CLIENT_VK_LINK = stringPreferencesKey("client_vk_link")
        val CLIENT_SYSTEM_VK_LINK = stringPreferencesKey("client_system_vk_link")
        val CLIENT_USE_CUSTOM_VK_LINK = booleanPreferencesKey("client_use_custom_vk_link")
        // Устаревший ключ из мультиссылочной фичи — используется для очистки.
        private val CLIENT_VK_LINKS_LEGACY = stringPreferencesKey("client_vk_links")
        val CLIENT_THREADS = intPreferencesKey("client_threads")
        val CLIENT_STREAMS_PER_CRED = intPreferencesKey("client_streams_per_cred")
        val CLIENT_UDP = booleanPreferencesKey("client_udp")
        val CLIENT_MANUAL_CAPTCHA = booleanPreferencesKey("client_manual_captcha")
        val CLIENT_LOCAL_PORT = stringPreferencesKey("client_local_port")
        val CLIENT_IS_RAW = booleanPreferencesKey("client_is_raw")
        val CLIENT_RAW_CMD = stringPreferencesKey("client_raw_cmd")
        val CLIENT_VLESS = booleanPreferencesKey("client_vless")
        private val CLIENT_CAPTCHA_SOLVER_LEGACY = stringPreferencesKey("client_captcha_solver")
        val CLIENT_DEBUG = booleanPreferencesKey("client_debug")
        val CLIENT_USE_CARRIER_DNS = booleanPreferencesKey("client_use_carrier_dns")
        val CLIENT_DNS_MODE = stringPreferencesKey("client_dns_mode")
        val CLIENT_FORCE_PORT_443 = booleanPreferencesKey("client_force_port_443")
        val CLIENT_SYNC_SERVER = booleanPreferencesKey("client_sync_server")
        val CLIENT_MAGIC_SWITCH = booleanPreferencesKey("client_magic_switch")
        val CLIENT_MAGIC_TURN = stringPreferencesKey("client_magic_turn")
        // Устаревшие ключи — не пишутся, но молча удаляются при saveClientConfig.
        private val CLIENT_ALLOCS_PER_STREAM_LEGACY = intPreferencesKey("client_allocs_per_stream")
        private val CLIENT_TURN_PORT_443_LEGACY = booleanPreferencesKey("client_turn_port_443")
        val PROXY_LISTEN = stringPreferencesKey("proxy_listen")
        val PROXY_CONNECT = stringPreferencesKey("proxy_connect")
        // Серверные параметры (управляются на ServerManagementScreen).
        val SERVER_VLESS_BOND = booleanPreferencesKey("server_vless_bond")
        val SERVER_WRAP_ENABLED = booleanPreferencesKey("server_wrap_enabled")
        val SERVER_KCP_FEC = booleanPreferencesKey("server_kcp_fec")
        // Ревизия — инкрементится при saveServerOpts. Нужна, чтобы Flow эмитил
        // обновление, когда меняется только wrap-key в EncryptedSharedPreferences
        // (DataStore сам по себе не видит этого изменения).
        val SERVER_OPTS_REV = intPreferencesKey("server_opts_rev")
        // Wrap-ключ хранится в EncryptedSharedPreferences (key: "server_wrap_key"),
        // не в DataStore. См. encryptedPrefs ниже.
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val TG_SUBSCRIBE_SHOWN = booleanPreferencesKey("tg_subscribe_shown")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val DEV_MODE = booleanPreferencesKey("dev_mode")
        val PROFILES_REV = intPreferencesKey("profiles_rev")

        // Устаревшие ключи — используются только для миграции
        private val SSH_PASS_LEGACY = stringPreferencesKey("ssh_pass")
        private val SSH_KEY_LEGACY = stringPreferencesKey("ssh_key")
    }

    // Шифрованное хранилище для SSH-пароля и ключа (Android Keystore + AES-256)
    // Подавляем предупреждения: стабильной замены EncryptedSharedPreferences пока нет
    @Suppress("DEPRECATION")
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            e.printStackTrace()
            keystoreFailed.set(true)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            } catch (ke: Exception) {
                ke.printStackTrace()
            }
            context.deleteSharedPreferences("secure_ssh_prefs")
            try {
                createEncryptedPrefs()
            } catch (fallbackEx: Exception) {
                fallbackEx.printStackTrace()
                context.getSharedPreferences("secure_ssh_prefs_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "secure_ssh_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = Character.forDigit(v ushr 4, 16)
            hexChars[i * 2 + 1] = Character.forDigit(v and 0x0F, 16)
        }
        return String(hexChars)
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

    fun verifyAndSetFallbackPin(pin: String): Boolean {
        if (pin.length < 4) return false
        val prefs = encryptedPrefs
        val pinTest = prefs.getString("pin_test", null)
        if (pinTest == null) {
            try {
                fallbackPinState.value = pin
                val encrypted = encryptFallback("ok", pin)
                prefs.edit().putString("pin_test", encrypted).apply()
                return true
            } catch (e: Exception) {
                fallbackPinState.value = null
                return false
            }
        } else {
            try {
                val decrypted = decryptFallback(pinTest, pin)
                if (decrypted == "ok") {
                    fallbackPinState.value = pin
                    return true
                }
            } catch (e: Exception) {
                // Incorrect PIN
            }
            return false
        }
    }


    private fun deriveKey(pin: String, salt: ByteArray): SecretKey {
        val keySpec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(keySpec).encoded
        return SecretKeySpec(derived, "AES")
    }

    private fun encryptFallback(plainText: String, pin: String): String {
        val prefs = context.getSharedPreferences("secure_ssh_prefs_fallback", Context.MODE_PRIVATE)
        var saltHex = prefs.getString("salt", null)
        val salt: ByteArray
        if (saltHex == null) {
            salt = ByteArray(16)
            java.security.SecureRandom().nextBytes(salt)
            saltHex = bytesToHex(salt)
            prefs.edit().putString("salt", saltHex).apply()
        } else {
            salt = hexToBytes(saltHex)
        }
        val key = deriveKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return bytesToHex(iv) + ":" + bytesToHex(encrypted)
    }

    private fun decryptFallback(encryptedData: String, pin: String): String {
        val prefs = context.getSharedPreferences("secure_ssh_prefs_fallback", Context.MODE_PRIVATE)
        val saltHex = prefs.getString("salt", null) ?: throw Exception("Salt not found")
        val salt = hexToBytes(saltHex)
        val parts = encryptedData.split(":")
        if (parts.size != 2) throw IllegalArgumentException("Invalid encrypted format")
        val iv = hexToBytes(parts[0])
        val ciphertext = hexToBytes(parts[1])
        val key = deriveKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun getEncryptedString(key: String, defaultValue: String?): String? {
        val prefs = encryptedPrefs
        if (keystoreFailed.get()) {
            val pin = fallbackPinState.value ?: return defaultValue
            val encryptedValue = prefs.getString(key, null) ?: return defaultValue
            return try {
                decryptFallback(encryptedValue, pin)
            } catch (e: Exception) {
                e.printStackTrace()
                defaultValue
            }
        } else {
            return prefs.getString(key, defaultValue)
        }
    }

    private fun putEncryptedString(key: String, value: String?) {
        val prefs = encryptedPrefs
        if (keystoreFailed.get()) {
            val pin = fallbackPinState.value ?: return
            if (value == null) {
                prefs.edit().remove(key).apply()
            } else {
                val encryptedValue = encryptFallback(value, pin)
                prefs.edit().putString(key, encryptedValue).apply()
            }
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }

    private fun getEncryptedLong(key: String, defaultValue: Long): Long {
        val str = getEncryptedString(key, null) ?: return defaultValue
        return str.toLongOrNull() ?: defaultValue
    }

    private fun putEncryptedLong(key: String, value: Long) {
        putEncryptedString(key, value.toString())
    }

    val sshConfigFlow: Flow<SshConfig> = combine(
        context.dataStore.data,
        fallbackPinState
    ) { prefs, _ ->
        SshConfig(
            ip = prefs[SSH_IP] ?: "",
            port = prefs[SSH_PORT] ?: 22,
            username = prefs[SSH_USER] ?: "root",
            // Читаем из зашифрованного хранилища; если пусто — берём из DataStore (миграция)
            password = getEncryptedString("ssh_pass", null)
                ?: prefs[SSH_PASS_LEGACY] ?: "",
            authType = prefs[SSH_AUTH_TYPE] ?: "PASSWORD",
            sshKey = getEncryptedString("ssh_key", null)
                ?: prefs[SSH_KEY_LEGACY] ?: "",
            hostFingerprint = prefs[SSH_HOST_FP] ?: ""
        )
    }.catch { if (it is IOException) emit(SshConfig()) else throw it }

    val clientConfigFlow: Flow<ClientConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ClientConfig(
                serverAddress = prefs[CLIENT_SERVER_ADDR] ?: "",
                vkLink = prefs[CLIENT_VK_LINK] ?: "",
                systemVkLink = prefs[CLIENT_SYSTEM_VK_LINK] ?: "",
                useCustomVkLink = prefs[CLIENT_USE_CUSTOM_VK_LINK] ?: false,
                threads = prefs[CLIENT_THREADS] ?: 4,
                streamsPerCred = prefs[CLIENT_STREAMS_PER_CRED] ?: 10,
                useUdp = prefs[CLIENT_UDP] ?: true,
                manualCaptcha = prefs[CLIENT_MANUAL_CAPTCHA] ?: false,
                localPort = prefs[CLIENT_LOCAL_PORT] ?: "127.0.0.1:9000",
                isRawMode = prefs[CLIENT_IS_RAW] ?: false,
                rawCommand = prefs[CLIENT_RAW_CMD] ?: "",
                vlessMode = prefs[CLIENT_VLESS] ?: false,

                debugMode = prefs[CLIENT_DEBUG] ?: false,
                useCarrierDns = prefs[CLIENT_USE_CARRIER_DNS] ?: false,
                dnsMode = (prefs[CLIENT_DNS_MODE] ?: DnsMode.AUTO).let {
                    if (it in DnsMode.ALL) it else DnsMode.AUTO
                },
                forcePort443 = prefs[CLIENT_FORCE_PORT_443] ?: false,
                syncServerSwitches = prefs[CLIENT_SYNC_SERVER] ?: true,
                magicSwitch = prefs[CLIENT_MAGIC_SWITCH] ?: false,
                magicTurn = prefs[CLIENT_MAGIC_TURN] ?: ""
            )
        }

    val onboardingDoneFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[ONBOARDING_DONE] ?: false }

    val proxyListenFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[PROXY_LISTEN] ?: "0.0.0.0:56000" }

    val proxyConnectFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[PROXY_CONNECT] ?: "127.0.0.1:40537" }

    /** Снимок серверных опций. wrapKey читается из шифрованного хранилища. */
    data class ServerOpts(
        val vlessBond: Boolean = false,
        val wrapEnabled: Boolean = false,
        val wrapKey: String = "",
        val kcpFec: Boolean = false
    )

    val serverOptsFlow: Flow<ServerOpts> = combine(
        context.dataStore.data,
        fallbackPinState
    ) { prefs, _ ->
        ServerOpts(
            vlessBond = prefs[SERVER_VLESS_BOND] ?: false,
            wrapEnabled = prefs[SERVER_WRAP_ENABLED] ?: false,
            wrapKey = getEncryptedString("server_wrap_key", null) ?: "",
            kcpFec = prefs[SERVER_KCP_FEC] ?: false
        )
    }.catch { if (it is IOException) emit(ServerOpts()) else throw it }

    suspend fun saveServerOpts(opts: ServerOpts) {
        withContext(Dispatchers.IO) {
            putEncryptedString("server_wrap_key", opts.wrapKey)
        }
        context.dataStore.edit { prefs ->
            prefs[SERVER_VLESS_BOND] = opts.vlessBond
            prefs[SERVER_WRAP_ENABLED] = opts.wrapEnabled
            prefs[SERVER_KCP_FEC] = opts.kcpFec
            prefs[SERVER_OPTS_REV] = (prefs[SERVER_OPTS_REV] ?: 0) + 1
        }
    }

    val dynamicThemeFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[DYNAMIC_THEME] ?: true }

    val tgSubscribeShownFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[TG_SUBSCRIBE_SHOWN] ?: false }

    val profilesFlow: Flow<List<Profile>> = combine(
        context.dataStore.data,
        fallbackPinState
    ) { _, _ ->
        // Trigger reload when PROFILES_REV changes or fallbackPinState changes
        val json = getEncryptedString("profiles_json", null)
        ProfileJson.decodeList(json)
    }.catch { if (it is IOException) emit(emptyList()) else throw it }

    val activeProfileIdFlow: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[ACTIVE_PROFILE_ID]?.takeIf { it.isNotBlank() } }

    /** Заменяет весь список профилей. */
    suspend fun saveProfiles(list: List<Profile>) {
        withContext(Dispatchers.IO) {
            putEncryptedString("profiles_json", ProfileJson.encodeList(list))
        }
        context.dataStore.edit { prefs ->
            prefs[PROFILES_REV] = (prefs[PROFILES_REV] ?: 0) + 1
        }
    }

    suspend fun setActiveProfileId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_PROFILE_ID)
            else prefs[ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun saveSshConfig(config: SshConfig) {
        // Чувствительные данные — в зашифрованное хранилище
        withContext(Dispatchers.IO) {
            putEncryptedString("ssh_pass", config.password)
            putEncryptedString("ssh_key", config.sshKey)
        }
        // Остальное — в DataStore; удаляем устаревшие незашифрованные значения
        context.dataStore.edit { prefs ->
            prefs[SSH_IP] = config.ip
            prefs[SSH_PORT] = config.port
            prefs[SSH_USER] = config.username
            prefs[SSH_AUTH_TYPE] = config.authType
            prefs[SSH_HOST_FP] = config.hostFingerprint
            prefs.remove(SSH_PASS_LEGACY)
            prefs.remove(SSH_KEY_LEGACY)
        }
    }

    suspend fun saveSshFingerprint(fingerprint: String) {
        context.dataStore.edit { prefs -> prefs[SSH_HOST_FP] = fingerprint }
    }

    suspend fun saveClientConfig(config: ClientConfig) {
        context.dataStore.edit { prefs ->
            prefs[CLIENT_SERVER_ADDR] = config.serverAddress
            prefs[CLIENT_VK_LINK] = config.vkLink
            prefs[CLIENT_SYSTEM_VK_LINK] = config.systemVkLink
            prefs[CLIENT_USE_CUSTOM_VK_LINK] = config.useCustomVkLink
            prefs.remove(CLIENT_VK_LINKS_LEGACY)
            prefs[CLIENT_THREADS] = config.threads
            prefs[CLIENT_STREAMS_PER_CRED] = config.streamsPerCred
            prefs[CLIENT_UDP] = config.useUdp
            prefs[CLIENT_MANUAL_CAPTCHA] = config.manualCaptcha
            // Мигрируем старый ключ: noDtls удалён из приложения.
            prefs.remove(booleanPreferencesKey("client_no_dtls"))
            prefs[CLIENT_LOCAL_PORT] = config.localPort
            prefs[CLIENT_IS_RAW] = config.isRawMode
            prefs[CLIENT_RAW_CMD] = config.rawCommand
            prefs[CLIENT_VLESS] = config.vlessMode
            prefs.remove(CLIENT_CAPTCHA_SOLVER_LEGACY)
            prefs[CLIENT_DEBUG] = config.debugMode
            prefs[CLIENT_USE_CARRIER_DNS] = config.useCarrierDns
            prefs[CLIENT_DNS_MODE] = if (config.dnsMode in DnsMode.ALL) config.dnsMode else DnsMode.AUTO
            prefs[CLIENT_FORCE_PORT_443] = config.forcePort443
            prefs[CLIENT_SYNC_SERVER] = config.syncServerSwitches
            prefs[CLIENT_MAGIC_SWITCH] = config.magicSwitch
            prefs[CLIENT_MAGIC_TURN] = config.magicTurn.trim()
            // Удаляем устаревшие ключи.
            prefs.remove(CLIENT_TURN_PORT_443_LEGACY)
            prefs.remove(CLIENT_ALLOCS_PER_STREAM_LEGACY)
        }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { prefs -> prefs[ONBOARDING_DONE] = done }
    }

    suspend fun setDynamicTheme(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DYNAMIC_THEME] = enabled }
    }

    suspend fun setTgSubscribeShown() {
        context.dataStore.edit { prefs -> prefs[TG_SUBSCRIBE_SHOWN] = true }
    }

    val devModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[DEV_MODE] ?: false }

    suspend fun setDevMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DEV_MODE] = enabled }
    }

    suspend fun saveProxyConfig(listen: String, connect: String) {
        context.dataStore.edit { prefs ->
            prefs[PROXY_LISTEN] = listen
            prefs[PROXY_CONNECT] = connect
        }
    }

    /**
     * Снимок: список + id активного. Объединено в один Flow, чтобы UI получал
     * консистентную пару (нет окна, в котором активный id указывает на удалённый).
     */
    val profilesSnapshot: Flow<ProfilesSnapshot> = combine(
        context.dataStore.data,
        fallbackPinState
    ) { prefs, _ ->
        ProfilesSnapshot(
            list = ProfileJson.decodeList(getEncryptedString("profiles_json", null)),
            activeId = prefs[ACTIVE_PROFILE_ID]?.takeIf { it.isNotBlank() }
        )
    }.catch { if (it is IOException) emit(ProfilesSnapshot()) else throw it }

    /** Полный сброс: DataStore + EncryptedSharedPreferences. */
    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit { clear() }
            // Чистим следы старого кастомного ядра, если оставались.
            File(context.filesDir, "custom_vkturn").delete()
        }
        fallbackPinState.value = null
    }

    suspend fun getAuthToken(): String = withContext(Dispatchers.IO) {
        getEncryptedString("auth_token", "") ?: ""
    }
    suspend fun saveAuthToken(token: String) = withContext(Dispatchers.IO) {
        putEncryptedString("auth_token", token)
        Unit
    }

    suspend fun getConfigUrl(): String = withContext(Dispatchers.IO) {
        getEncryptedString("config_url", "") ?: ""
    }
    suspend fun saveConfigUrl(url: String) = withContext(Dispatchers.IO) {
        putEncryptedString("config_url", url)
        Unit
    }

    suspend fun getDecryptionKey(): String = withContext(Dispatchers.IO) {
        getEncryptedString("decryption_key", "") ?: ""
    }
    suspend fun saveDecryptionKey(key: String) = withContext(Dispatchers.IO) {
        putEncryptedString("decryption_key", key)
        Unit
    }

    suspend fun getSubscriptionExpiresAt(): Long = withContext(Dispatchers.IO) {
        getEncryptedLong("sub_expires_at", 0L)
    }
    suspend fun saveSubscriptionExpiresAt(timestamp: Long) = withContext(Dispatchers.IO) {
        putEncryptedLong("sub_expires_at", timestamp)
        Unit
    }

    suspend fun getWgConfig(): String = withContext(Dispatchers.IO) {
        getEncryptedString("wg_config", "") ?: ""
    }
    suspend fun saveWgConfig(jsonStr: String) = withContext(Dispatchers.IO) {
        putEncryptedString("wg_config", jsonStr)
        Unit
    }
}

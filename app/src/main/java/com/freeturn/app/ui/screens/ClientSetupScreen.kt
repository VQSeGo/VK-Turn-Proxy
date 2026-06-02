@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.DnsMode
import com.freeturn.app.data.ObfProfile
import com.freeturn.app.data.Provider
import com.freeturn.app.data.SplitTunnelMode
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.viewmodel.ServerViewModel
import com.freeturn.app.viewmodel.SettingsViewModel
import com.freeturn.app.viewmodel.SshConnectionState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * @param showFinishButton  true — онбординг-флоу, показываем кнопку «Завершить».
 *                          false — вкладка, авто-сохранение без кнопки.
 * @param onFinish  вызывается после нажатия кнопки «Завершить» (только если showFinishButton=true).
 */
@Composable
fun ClientSetupScreen(
    settingsViewModel: SettingsViewModel,
    serverViewModel: ServerViewModel,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val saved by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val sshConfig by serverViewModel.sshConfig.collectAsStateWithLifecycle()
    val sshState by serverViewModel.sshState.collectAsStateWithLifecycle()
    val serverState by serverViewModel.serverState.collectAsStateWithLifecycle()
    val serverOpts by serverViewModel.serverOpts.collectAsStateWithLifecycle()
    val proxyListen by settingsViewModel.proxyListen.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val isRegeneratingObfKey by serverViewModel.isRegeneratingObfKey.collectAsStateWithLifecycle()

    val isSshConnected = sshState is SshConnectionState.Connected
    val serverKnown = serverState as? com.freeturn.app.viewmodel.ServerState.Known
    // В sync-режиме эффективное значение — реальное состояние сервера из probe
    // (если запущен), иначе сохранённое. В !sync режиме — всегда сохранённое:
    // серверная и клиентская стороны могут различаться, и UI отражает клиента.
    val syncOn = saved.syncServerSwitches
    val effectiveTcpForward = if (syncOn && serverKnown?.running == true) serverKnown.tcpMode ?: saved.tcpForward else saved.tcpForward
    val effectiveObfProfile = if (syncOn && serverKnown?.running == true) serverKnown.obfProfile ?: serverOpts.obfProfile else serverOpts.obfProfile

    val context = LocalContext.current

    var serverAddress by rememberSaveable(saved.serverAddress) { mutableStateOf(saved.serverAddress) }
    var vkLink       by rememberSaveable(saved.vkLink)         { mutableStateOf(saved.vkLink) }
    var threads      by rememberSaveable(saved.threads)        { mutableFloatStateOf(saved.threads.toFloat()) }
    var streamsPerCred by rememberSaveable(saved.streamsPerCred) { mutableFloatStateOf(saved.streamsPerCred.toFloat()) }
    var localPort    by rememberSaveable(saved.localPort)      { mutableStateOf(saved.localPort) }
    var magicTurn by rememberSaveable(saved.magicTurn) { mutableStateOf(saved.magicTurn) }
    var wireGuardTunnelName by rememberSaveable(saved.wireGuardTunnelName) { mutableStateOf(saved.wireGuardTunnelName) }
    var wireGuardConfig by rememberSaveable(saved.wireGuardConfig) { mutableStateOf(saved.wireGuardConfig) }
    var splitTunnelApps by rememberSaveable(saved.splitTunnelApps) { mutableStateOf(saved.splitTunnelApps) }
    var showSplitAppPicker by rememberSaveable { mutableStateOf(false) }
    var lastSliderInt by rememberSaveable { mutableIntStateOf(saved.threads) }
    var lastStreamsInt by rememberSaveable { mutableIntStateOf(saved.streamsPerCred) }

    // Автозаполнение адреса сервера из SSH-конфига если поле пустое
    LaunchedEffect(sshConfig.ip, proxyListen) {
        if (serverAddress.isBlank() && sshConfig.ip.isNotBlank()) {
            val port = proxyListen.substringAfterLast(":", "56000")
            serverAddress = "${sshConfig.ip}:$port"
        }
    }

    // Авто-сохранение с дебаунсом 600 мс для текстовых полей и ползунков
    LaunchedEffect(
        serverAddress, vkLink, threads, streamsPerCred, localPort, magicTurn,
        wireGuardTunnelName, wireGuardConfig, splitTunnelApps
    ) {
        delay(600)
        val current = settingsViewModel.clientConfig.value
        settingsViewModel.saveClientConfig(
            current.copy(
                serverAddress = serverAddress.trim(),
                vkLink        = vkLink.trim(),
                threads       = threads.roundToInt(),
                streamsPerCred = streamsPerCred.roundToInt(),
                localPort     = localPort.trim(),
                magicTurn     = magicTurn.trim(),
                wireGuardTunnelName = wireGuardTunnelName.trim().ifBlank { "freeturn-wg" },
                wireGuardConfig = wireGuardConfig.trim(),
                splitTunnelApps = splitTunnelApps.trim()
            )
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.client_title)) },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // Подключение
                Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = serverAddress.redact(privacyMode),
                    onValueChange = { if (!privacyMode) serverAddress = it },
                    label = { Text(stringResource(R.string.server_address_label)) },
                    placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = privacyMode,
                    supportingText = { Text(stringResource(R.string.server_address_support)) }
                )

                // Провайдер TURN-creds (-provider). Пока только vk; список расширяемый.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.provider_title), style = MaterialTheme.typography.bodyMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        Provider.ALL.forEachIndexed { idx, value ->
                            SegmentedButton(
                                selected = saved.provider == value,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    settingsViewModel.setProvider(value)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = Provider.ALL.size)
                            ) { Text(providerLabel(value)) }
                        }
                    }
                }

                if (saved.provider == Provider.VK) {
                    OutlinedTextField(
                        value = vkLink.redact(privacyMode),
                        onValueChange = { if (!privacyMode) vkLink = it },
                        label = { Text(stringResource(R.string.call_link_label)) },
                        placeholder = { Text(stringResource(R.string.call_link_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = privacyMode,
                        supportingText = { Text(stringResource(R.string.call_link_support)) }
                    )
                }

                OutlinedTextField(
                    value = localPort.redact(privacyMode),
                    onValueChange = { if (!privacyMode) localPort = it },
                    label = { Text(stringResource(R.string.local_listen_address)) },
                    placeholder = { Text(stringResource(R.string.local_listen_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = privacyMode,
                    supportingText = { Text(stringResource(R.string.local_listen_support)) }
                )

                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

                // Параметры
                Text(stringResource(R.string.parameters_title), style = MaterialTheme.typography.titleMedium)

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.threads_format, threads.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.threads_recommendation),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Slider(
                        value = threads,
                        onValueChange = {
                            val newInt = it.roundToInt()
                            if (newInt != lastSliderInt) {
                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                lastSliderInt = newInt
                            }
                            threads = it
                        },
                        valueRange = 1f..128f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.streams_per_cred_format, streamsPerCred.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.streams_per_cred_recommendation),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = streamsPerCred,
                        onValueChange = {
                            val v = it.roundToInt()
                            if (v != lastStreamsInt) {
                                HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                lastStreamsInt = v
                            }
                            streamsPerCred = it
                        },
                        valueRange = 1f..50f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(stringResource(R.string.dns_mode_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.dns_mode_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val dnsOptions = listOf(
                        DnsMode.AUTO to stringResource(R.string.dns_mode_auto),
                        DnsMode.PLAIN to stringResource(R.string.dns_mode_udp),
                        DnsMode.DOH to stringResource(R.string.dns_mode_doh)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        dnsOptions.forEachIndexed { idx, (value, label) ->
                            SegmentedButton(
                                selected = saved.dnsMode == value,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    settingsViewModel.setDnsMode(value)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = dnsOptions.size)
                            ) { Text(label) }
                        }
                    }
                }

                // TURN-транспорт (-transport tcp|udp) ортогонален режиму туннеля.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(stringResource(R.string.transport_protocol), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.transport_protocol_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !saved.useUdp,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                settingsViewModel.setUseUdp(false)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text(stringResource(R.string.tcp)) }
                        SegmentedButton(
                            selected = saved.useUdp,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                settingsViewModel.setUseUdp(true)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text(stringResource(R.string.udp)) }
                    }
                }



                SwitchRow(
                    label = stringResource(R.string.manual_captcha),
                    description = stringResource(R.string.manual_captcha_desc),
                    checked = saved.manualCaptcha,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        settingsViewModel.setManualCaptcha(it)
                    }
                )

                SwitchRow(
                    label = stringResource(R.string.use_carrier_dns),
                    description = stringResource(R.string.use_carrier_dns_desc),
                    checked = saved.useCarrierDns,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        settingsViewModel.setUseCarrierDns(it)
                    }
                )

                SwitchRow(
                    label = stringResource(R.string.debug_mode),
                    description = stringResource(R.string.debug_mode_desc),
                    checked = saved.debugMode,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        settingsViewModel.setDebugMode(it)
                    }
                )

                SwitchRow(
                    label = stringResource(R.string.magic_switch),
                    description = stringResource(R.string.magic_switch_desc),
                    checked = saved.magicSwitch,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        settingsViewModel.setMagicSwitch(it)
                    }
                )

                if (saved.magicSwitch) {
                    OutlinedTextField(
                        value = magicTurn.redact(privacyMode),
                        onValueChange = { if (!privacyMode) magicTurn = it },
                        label = { Text(stringResource(R.string.magic_switch_address_label)) },
                        placeholder = { Text(stringResource(R.string.magic_switch_address_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = privacyMode,
                        supportingText = { Text(stringResource(R.string.magic_switch_address_support)) }
                    )
                }

                // Bond — client-only флаг (сервер детектит сам). SSH не нужен;
                // показываем только в TCP-форвард режиме.
                if (effectiveTcpForward) {
                    SwitchRow(
                        label = stringResource(R.string.client_bond),
                        description = stringResource(R.string.client_bond_desc),
                        checked = saved.bond,
                        onCheckedChange = {
                            HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                            settingsViewModel.setBond(it)
                        }
                    )
                }

                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

                // --- Туннельное приложение (WireGuard) ---
                Text(stringResource(R.string.tunnel_transport_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.tunnel_transport_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(stringResource(R.string.wireguard_section), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = wireGuardTunnelName.redact(privacyMode),
                    onValueChange = { if (!privacyMode) wireGuardTunnelName = it },
                    label = { Text(stringResource(R.string.wireguard_tunnel_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = privacyMode
                )
                OutlinedTextField(
                    value = wireGuardConfig.redact(privacyMode),
                    onValueChange = { if (!privacyMode) wireGuardConfig = it },
                    label = { Text(stringResource(R.string.wireguard_config_label)) },
                    placeholder = { Text(stringResource(R.string.wireguard_config_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    readOnly = privacyMode,
                    supportingText = { Text(stringResource(R.string.wireguard_config_support)) }
                )

                SplitTunnelSettings(
                    mode = saved.splitTunnelMode,
                    apps = splitTunnelApps,
                    privacyMode = privacyMode,
                    onModeChange = {
                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                        settingsViewModel.setSplitTunnelMode(it)
                    },
                    onAppsChange = { splitTunnelApps = it },
                    onPickApps = { showSplitAppPicker = true }
                )

                SwitchRow(
                    label = stringResource(R.string.logs_enabled),
                    description = stringResource(R.string.logs_enabled_desc),
                    checked = saved.logsEnabled,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        settingsViewModel.setLogsEnabled(it)
                    }
                )

                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

                Text(
                    stringResource(R.string.server_sync_section),
                    style = MaterialTheme.typography.titleMedium
                )

                SwitchRow(
                    label = stringResource(R.string.sync_server_switches),
                    description = stringResource(R.string.sync_server_switches_desc),
                    checked = syncOn,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        settingsViewModel.setSyncServerSwitches(it)
                    }
                )

                // В sync-режиме менять эти флаги без SSH опасно (рассинхрон).
                // В !sync — клиентские, SSH не нужен.
                val controlsEnabled = if (syncOn) isSshConnected else true
                val lockedHint = if (syncOn)
                    stringResource(R.string.locked_disconnect_hint) else null

                // TCP-форвард — табы (UDP по умолчанию / TCP для проброса). Синхр. с сервером.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(stringResource(R.string.tcp_forward_mode), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            lockedHint?.takeIf { !isSshConnected }
                                ?: stringResource(R.string.tcp_forward_mode_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !effectiveTcpForward,
                            enabled = controlsEnabled,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                settingsViewModel.setTcpForward(false)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text(stringResource(R.string.udp)) }
                        SegmentedButton(
                            selected = effectiveTcpForward,
                            enabled = controlsEnabled,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                settingsViewModel.setTcpForward(true)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text(stringResource(R.string.tcp)) }
                    }
                }

                // Профиль обфускации — табы (как транспорт). Должен совпадать с сервером.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(stringResource(R.string.obf_profile_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            lockedHint?.takeIf { !isSshConnected }
                                ?: stringResource(R.string.obf_profile_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ObfProfile.ALL.forEachIndexed { idx, value ->
                            SegmentedButton(
                                selected = effectiveObfProfile == value,
                                enabled = controlsEnabled,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    settingsViewModel.setObfProfile(value)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = ObfProfile.ALL.size)
                            ) { Text(obfProfileLabel(value)) }
                        }
                    }
                }

                if (effectiveObfProfile != ObfProfile.NONE) {
                    val obfKeyRegex = remember { Regex("^[0-9a-fA-F]{64}$") }
                    // Локальный черновик: правки в TextField не дёргают saveServerOpts/restart
                    // на каждом keystroke. Применение — отдельной кнопкой ниже, только когда
                    // ключ валидный (64 hex). Серверный ключ из serverOpts — источник истины:
                    // при внешнем обновлении (regen/probe) черновик пересинхронизируется.
                    var obfKeyDraft by rememberSaveable(serverOpts.obfKey) {
                        mutableStateOf(serverOpts.obfKey)
                    }
                    val draftValid = obfKeyDraft.matches(obfKeyRegex)
                    val draftDirty = obfKeyDraft != serverOpts.obfKey
                    OutlinedTextField(
                        value = if (privacyMode) serverOpts.obfKey.redact(true) else obfKeyDraft,
                        onValueChange = { if (!privacyMode) obfKeyDraft = it },
                        label = { Text(stringResource(R.string.server_obf_key_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = privacyMode,
                        singleLine = true,
                        isError = obfKeyDraft.isNotBlank() && !draftValid,
                        trailingIcon = {
                            if (serverOpts.obfKey.isNotBlank() && !privacyMode) {
                                IconButton(onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(
                                        android.content.ClipData.newPlainText("obf-key", serverOpts.obfKey)
                                    )
                                }) {
                                    Icon(
                                        painter = painterResource(R.drawable.content_copy_24px),
                                        contentDescription = stringResource(R.string.copy)
                                    )
                                }
                            }
                        },
                        supportingText = {
                            when {
                                obfKeyDraft.isBlank() -> Text(
                                    stringResource(R.string.obf_key_empty_hint),
                                    color = MaterialTheme.colorScheme.error
                                )
                                !draftValid -> Text(
                                    stringResource(R.string.obf_key_invalid_hint),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                    if (!privacyMode && draftDirty) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                settingsViewModel.setObfKey(obfKeyDraft)
                            },
                            enabled = draftValid,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.obf_key_apply))
                        }
                    }
                    if (isSshConnected) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                    serverViewModel.regenerateObfKey()
                                },
                                enabled = !isRegeneratingObfKey,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isRegeneratingObfKey) {
                                    androidx.compose.material3.CircularWavyProgressIndicator(
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.obf_key_regen_in_progress))
                                } else {
                                    Text(stringResource(R.string.obf_key_regen))
                                }
                            }
                        }
                    }
                }

                // Кнопка «Завершить» — только в онбординг-флоу
                if (showFinishButton && onFinish != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = serverAddress.isNotBlank() && vkLink.isNotBlank(),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(stringResource(R.string.finish_setup), style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showSplitAppPicker) {
        SplitTunnelAppPickerDialog(
            selected = splitTunnelApps.toPackageSet(),
            onDismiss = { showSplitAppPicker = false },
            onApply = { selected ->
                splitTunnelApps = selected.sorted().joinToString("\n")
                showSplitAppPicker = false
            }
        )
    }
}

@Composable
private fun SplitTunnelSettings(
    mode: String,
    apps: String,
    privacyMode: Boolean,
    onModeChange: (String) -> Unit,
    onAppsChange: (String) -> Unit,
    onPickApps: () -> Unit
) {
    val context = LocalContext.current
    Text(stringResource(R.string.split_tunnel_title), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.split_tunnel_desc),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val options = listOf(
        SplitTunnelMode.ALL to stringResource(R.string.split_tunnel_all),
        SplitTunnelMode.INCLUDE to stringResource(R.string.split_tunnel_include),
        SplitTunnelMode.EXCLUDE to stringResource(R.string.split_tunnel_exclude)
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) { Text(label) }
        }
    }
    if (mode != SplitTunnelMode.ALL) {
        Button(
            onClick = {
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                onPickApps()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !privacyMode
        ) {
            Text(stringResource(R.string.split_tunnel_pick_apps))
        }
        OutlinedTextField(
            value = apps.redact(privacyMode),
            onValueChange = { if (!privacyMode) onAppsChange(it) },
            label = { Text(stringResource(R.string.split_tunnel_apps_label)) },
            placeholder = { Text(stringResource(R.string.split_tunnel_apps_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            readOnly = privacyMode,
            supportingText = { Text(stringResource(R.string.split_tunnel_apps_support)) }
        )
    }
}

@Composable
private fun SplitTunnelAppPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var selectedApps by remember(selected) { mutableStateOf(selected) }
    val apps = remember { context.installedInternetApps() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_tunnel_pick_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(apps) { app ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = app.packageName in selectedApps,
                            onCheckedChange = { checked ->
                                selectedApps = if (checked) selectedApps + app.packageName
                                else selectedApps - app.packageName
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selectedApps) }) {
                Text(stringResource(R.string.split_tunnel_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private data class AppChoice(
    val label: String,
    val packageName: String
)

private fun android.content.Context.installedInternetApps(): List<AppChoice> {
    val pm = packageManager
    val flags = android.content.pm.PackageManager.GET_PERMISSIONS
    val packages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(flags)
    }
    return packages
        .asSequence()
        .filter { info ->
            info.packageName != packageName &&
                info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
        }
        .map { info ->
            val appInfo = info.applicationInfo
            AppChoice(
                label = appInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.packageName,
                packageName = info.packageName
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy<AppChoice> { it.label.lowercase() }.thenBy { it.packageName })
        .toList()
}

private fun String.toPackageSet(): Set<String> =
    split(',', '\n', ' ', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun providerLabel(value: String): String = when (value) {
    Provider.VK -> stringResource(R.string.provider_vk)
    else -> value
}

@Composable
private fun obfProfileLabel(value: String): String = when (value) {
    ObfProfile.NONE -> stringResource(R.string.obf_none)
    ObfProfile.RTPOPUS -> stringResource(R.string.obf_rtpopus)
    else -> value
}

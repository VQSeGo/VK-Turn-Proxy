@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.DnsMode
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.viewmodel.SettingsViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ClientSetupScreen(
    settingsViewModel: SettingsViewModel,
    showFinishButton: Boolean = false,
    onFinish: (() -> Unit)? = null
) {
    val saved by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val dynamicTheme by settingsViewModel.dynamicTheme.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var vkLink       by rememberSaveable(saved.vkLink)         { mutableStateOf(saved.vkLink) }
    var useCustomVkLink by rememberSaveable(saved.useCustomVkLink) { mutableStateOf(saved.useCustomVkLink) }
    var threads      by rememberSaveable(saved.threads)        { mutableFloatStateOf(saved.threads.toFloat()) }
    var streamsPerCred by rememberSaveable(saved.streamsPerCred) { mutableFloatStateOf(saved.streamsPerCred.toFloat()) }
    var useCarrierDns by rememberSaveable(saved.useCarrierDns) { mutableStateOf(saved.useCarrierDns) }
    var dnsMode by rememberSaveable(saved.dnsMode) { mutableStateOf(saved.dnsMode) }
    var magicSwitch by rememberSaveable(saved.magicSwitch) { mutableStateOf(saved.magicSwitch) }
    var magicTurn by rememberSaveable(saved.magicTurn) { mutableStateOf(saved.magicTurn) }
    var debugMode by rememberSaveable(saved.debugMode) { mutableStateOf(saved.debugMode) }
    var lastSliderInt by rememberSaveable { mutableIntStateOf(saved.threads) }
    var lastStreamsInt by rememberSaveable { mutableIntStateOf(saved.streamsPerCred) }
    var showResetDialog by remember { mutableStateOf(false) }

    // Sync local states if the underlying config gets updated externally
    LaunchedEffect(saved) {
        if (vkLink != saved.vkLink) vkLink = saved.vkLink
        if (useCustomVkLink != saved.useCustomVkLink) useCustomVkLink = saved.useCustomVkLink
        if (threads.roundToInt() != saved.threads) {
            threads = saved.threads.toFloat()
            lastSliderInt = saved.threads
        }
        if (streamsPerCred.roundToInt() != saved.streamsPerCred) {
            streamsPerCred = saved.streamsPerCred.toFloat()
            lastStreamsInt = saved.streamsPerCred
        }
        if (useCarrierDns != saved.useCarrierDns) useCarrierDns = saved.useCarrierDns
        if (dnsMode != saved.dnsMode) dnsMode = saved.dnsMode
        if (magicSwitch != saved.magicSwitch) magicSwitch = saved.magicSwitch
        if (magicTurn != saved.magicTurn) magicTurn = saved.magicTurn
        if (debugMode != saved.debugMode) debugMode = saved.debugMode
    }

    // Auto-save with debounce 600 ms
    LaunchedEffect(vkLink, useCustomVkLink, threads, streamsPerCred, useCarrierDns, dnsMode, magicSwitch, magicTurn, debugMode) {
        delay(600)
        val newConfig = saved.copy(
            vkLink = vkLink.trim(),
            useCustomVkLink = useCustomVkLink,
            threads = threads.roundToInt(),
            streamsPerCred = streamsPerCred.roundToInt(),
            useCarrierDns = useCarrierDns,
            dnsMode = dnsMode,
            magicSwitch = magicSwitch,
            magicTurn = magicTurn.trim(),
            debugMode = debugMode
        )
        if (newConfig != saved) {
            settingsViewModel.saveClientConfig(newConfig)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        val focusManager = LocalFocusManager.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { focusManager.clearFocus() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // Категория 1: Передача данных
                Text("Передача данных", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                Column {
                    Text("Количество потоков: ${threads.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
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
                        valueRange = 1f..32f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column {
                    Text("Потоков на аккаунт: ${streamsPerCred.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
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
                        Text("Источник ссылки на звонок VK", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Используйте пользовательскую ссылку, если системная ссылка на звонок не работает",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val options = listOf(
                        false to "Системная",
                        true to "Пользовательская"
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { idx, (value, label) ->
                            SegmentedButton(
                                selected = useCustomVkLink == value,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    useCustomVkLink = value
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size)
                            ) { Text(label) }
                        }
                    }

                    AnimatedVisibility(
                        visible = useCustomVkLink,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            val clipboardManager = LocalClipboardManager.current
                            OutlinedTextField(
                                value = vkLink,
                                onValueChange = { vkLink = it },
                                label = { Text("Своя ссылка на звонок VK") },
                                placeholder = { Text("https://vk.com/call/join/...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = {
                                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                            val clip = clipboardManager.getText()?.text.orEmpty()
                                            if (clip.isNotBlank()) {
                                                vkLink = clip
                                            }
                                        }) {
                                            Icon(painterResource(R.drawable.content_paste_24px), contentDescription = "Вставить из буфера")
                                        }
                                        if (vkLink.isNotEmpty()) {
                                            IconButton(onClick = {
                                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                                vkLink = ""
                                            }) {
                                                Icon(painterResource(R.drawable.delete_24px), contentDescription = "Сбросить")
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                SwitchRow(
                    label = stringResource(R.string.magic_switch),
                    description = stringResource(R.string.magic_switch_desc),
                    checked = magicSwitch,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        magicSwitch = it
                    }
                )

                if (magicSwitch) {
                    OutlinedTextField(
                        value = magicTurn,
                        onValueChange = { magicTurn = it },
                        label = { Text(stringResource(R.string.magic_switch_address_label)) },
                        placeholder = { Text(stringResource(R.string.magic_switch_address_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.magic_switch_address_support)) }
                    )
                }

                HorizontalDivider()

                // Категория 2: Настройки DNS
                Text("Настройки DNS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

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
                        DnsMode.UDP to stringResource(R.string.dns_mode_udp),
                        DnsMode.DOH to stringResource(R.string.dns_mode_doh)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        dnsOptions.forEachIndexed { idx, (value, label) ->
                            SegmentedButton(
                                selected = dnsMode == value,
                                onClick = {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    dnsMode = value
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = dnsOptions.size)
                            ) { Text(label) }
                        }
                    }
                }

                SwitchRow(
                    label = stringResource(R.string.use_carrier_dns),
                    description = stringResource(R.string.use_carrier_dns_desc),
                    checked = useCarrierDns,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        useCarrierDns = it
                    }
                )

                HorizontalDivider()

                // Категория: Логирование
                Text("Логирование", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                SwitchRow(
                    label = stringResource(R.string.debug_mode),
                    description = stringResource(R.string.debug_mode_desc),
                    checked = debugMode,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        debugMode = it
                    }
                )

                HorizontalDivider()

                // Категория 3: Внешний вид
                Text("Внешний вид", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                SwitchRow(
                    label = stringResource(R.string.dynamic_theme_title),
                    description = stringResource(R.string.dynamic_theme_desc),
                    checked = dynamicTheme,
                    onCheckedChange = {
                        HapticUtil.perform(context, if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF)
                        settingsViewModel.setDynamicTheme(it)
                    }
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showResetDialog = true
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(painterResource(R.drawable.delete_24px), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reset_settings))
                }

                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }

    if (showResetDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_all_settings_title)) },
            text = { Text(stringResource(R.string.reset_all_settings_desc)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showResetDialog = false
                        settingsViewModel.resetAllSettings()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

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

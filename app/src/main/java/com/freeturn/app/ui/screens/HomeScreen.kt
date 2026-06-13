@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.freeturn.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.freeturn.app.ui.HapticUtil
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.freeturn.app.ui.theme.extendedColorScheme
import com.freeturn.app.viewmodel.ProxyState
import com.freeturn.app.viewmodel.UpdateState
import com.freeturn.app.viewmodel.SettingsViewModel
import com.freeturn.app.viewmodel.ProxyViewModel
import androidx.core.net.toUri

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val proxyState by proxyViewModel.proxyState.collectAsStateWithLifecycle()
    val connectedSince by proxyViewModel.connectedSince.collectAsStateWithLifecycle()
    val uptimeText = rememberProxyUptime(connectedSince)
    val clientConfig by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val configUpdatedTrigger = remember { mutableStateOf(false) }
    val watchdogAttempt by com.freeturn.app.ProxyServiceState.watchdogAttempt.collectAsStateWithLifecycle()
    val captchaVerificationState by com.freeturn.app.ProxyServiceState.captchaVerificationState.collectAsStateWithLifecycle()

    // Запрос разрешений при первом открытии главного экрана
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* пользователь закрыл диалог батареи — результат нас не интересует */ }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            proxyViewModel.startProxy()
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // После диалога уведомлений — запрашиваем исключение из оптимизации батареи
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            batteryOptLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        delay(400) // даём экрану отрисоваться
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

        if (needsNotification) {
            // Запрашиваем нотификации; батарею запросим в callback выше
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Нотификации уже есть — сразу проверяем батарею
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                batteryOptLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }
        }
    }

    val profilesSnapshot by settingsViewModel.profilesSnapshot.collectAsStateWithLifecycle()
    val showBottomSheet = rememberSaveable { mutableStateOf(false) }
    val showProfilesSheet = rememberSaveable { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profilesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var devClickCount by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val devMode by settingsViewModel.devMode.collectAsStateWithLifecycle()
    var longPressed by remember { mutableStateOf(false) }

    var connectionTimer by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(proxyState) {
        if (proxyState is ProxyState.Starting || proxyState is ProxyState.Connecting) {
            connectionTimer = 0
            while (true) {
                delay(1000)
                connectionTimer++
            }
        } else {
            connectionTimer = 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    var pressStart by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
                    Text(
                        text = stringResource(R.string.turn_proxy_title),
                        modifier = Modifier.pointerInput(devMode) {
                            if (!devMode) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitFirstDown()
                                        pressStart = System.currentTimeMillis()
                                        val up = waitForUpOrCancellation()
                                        if (up != null) {
                                            val duration = System.currentTimeMillis() - pressStart
                                            if (duration >= 2000) {
                                                if (!devMode && !longPressed) {
                                                    longPressed = true
                                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                                }
                                            } else {
                                                if (!devMode && longPressed) {
                                                    devClickCount++
                                                    if (devClickCount >= 10) {
                                                        settingsViewModel.setDevMode(true)
                                                        HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                },
                actions = {
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        coroutineScope.launch {
                            val result = proxyViewModel.fetchAndDecryptConfig()
                            if (result.isSuccess) {
                                configUpdatedTrigger.value = true
                                kotlinx.coroutines.delay(3000)
                                configUpdatedTrigger.value = false
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Ошибка обновления"
                                snackbarHostState.showSnackbar("Ошибка: $err")
                            }
                        }
                    }) {
                        Icon(painterResource(R.drawable.refresh_24px), contentDescription = "Обновить конфигурацию")
                    }
                    IconButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showBottomSheet.value = true
                    }) {
                        Icon(painterResource(R.drawable.info_24px), contentDescription = stringResource(R.string.info_desc))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { focusManager.clearFocus() }
        ) {
            val pullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    coroutineScope.launch {
                        val result = proxyViewModel.fetchAndDecryptConfig()
                        isRefreshing = false
                        if (result.isSuccess) {
                            configUpdatedTrigger.value = true
                            kotlinx.coroutines.delay(3000)
                            configUpdatedTrigger.value = false
                        } else {
                            val err = result.exceptionOrNull()?.message ?: "Ошибка обновления"
                            snackbarHostState.showSnackbar("Ошибка: $err")
                        }
                    }
                },
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullToRefreshState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 840.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ProxyToggleButton(
                        state = proxyState,
                        isConfigUpdated = configUpdatedTrigger.value,
                        onClick = {
                            when (proxyState) {
                                is ProxyState.Idle, is ProxyState.Error -> {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                                    val isWifi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        val activeNetwork = cm.activeNetwork
                                        val capabilities = cm.getNetworkCapabilities(activeNetwork)
                                        capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ?: false
                                    } else {
                                        @Suppress("DEPRECATION")
                                        cm.activeNetworkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI
                                    }

                                    if (isWifi) {
                                        proxyViewModel.startProxy()
                                    } else {
                                        val intent = VpnService.prepare(context)
                                        if (intent != null) {
                                            vpnPrepareLauncher.launch(intent)
                                        } else {
                                            proxyViewModel.startProxy()
                                        }
                                    }
                                }
                                is ProxyState.Running, is ProxyState.Connecting, is ProxyState.Starting -> {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                                    proxyViewModel.stopProxy()
                                }
                                else -> {}
                            }
                        }
                    )

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = if (configUpdatedTrigger.value) {
                            "Конфигурация обновлена"
                        } else {
                            when (val s = proxyState) {
                                is ProxyState.Running -> {
                                    val base = stringResource(R.string.proxy_active)
                                    val counts = if (s.total > 0) "${s.active}/${s.total}" else "${s.active}"
                                    if (uptimeText != null) "$base — $counts · $uptimeText"
                                    else "$base — $counts"
                                }
                                is ProxyState.Connecting -> {
                                    if (watchdogAttempt > 0) {
                                        "Переподключение... (Попытка $watchdogAttempt/8)"
                                    } else {
                                        val counts = if (s.total > 0) " (${s.active}/${s.total})" else ""
                                        val stageText = when {
                                            connectionTimer < 2 -> "Запуск ядра прокси..."
                                            connectionTimer < 4 -> "Подключение к серверу..."
                                            connectionTimer < 6 -> "Проверка авторизации..."
                                            connectionTimer < 9 -> "Создание туннелей smux..."
                                            connectionTimer < 12 -> "Настройка сетевых маршрутов..."
                                            else -> "Обычно это не занимает много времени..."
                                        }
                                        "$stageText$counts"
                                    }
                                }
                                is ProxyState.Starting -> {
                                    if (watchdogAttempt > 0) {
                                        "Переподключение... (Попытка $watchdogAttempt/8)"
                                    } else {
                                        when {
                                            connectionTimer < 2 -> "Запуск ядра прокси..."
                                            connectionTimer < 4 -> "Подключение к серверу..."
                                            connectionTimer < 6 -> "Проверка авторизации..."
                                            connectionTimer < 9 -> "Создание туннелей smux..."
                                            connectionTimer < 12 -> "Настройка сетевых маршрутов..."
                                            else -> "Обычно это не занимает много времени..."
                                        }
                                    }
                                }
                                is ProxyState.Error -> s.message
                                is ProxyState.CaptchaRequired -> {
                                    when (captchaVerificationState) {
                                        com.freeturn.app.CaptchaVerificationState.SUBMITTING -> "Проверка капчи..."
                                        com.freeturn.app.CaptchaVerificationState.FAILED -> "Неверный ввод, повторите..."
                                        else -> "Требуется решение капчи..."
                                    }
                                }
                                else -> stringResource(R.string.proxy_press_to_start)
                            }
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (configUpdatedTrigger.value) {
                            MaterialTheme.extendedColorScheme.success
                        } else {
                            when (proxyState) {
                                is ProxyState.Running -> MaterialTheme.extendedColorScheme.success
                                is ProxyState.Error -> MaterialTheme.colorScheme.error
                                is ProxyState.CaptchaRequired -> {
                                    if (captchaVerificationState == com.freeturn.app.CaptchaVerificationState.REQUIRED) {
                                        MaterialTheme.extendedColorScheme.warning
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                }
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        },
                        textAlign = TextAlign.Center
                    )

                    val networkType = rememberNetworkType()
                    if (networkType != NetworkType.NONE) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (networkType == NetworkType.WIFI) R.drawable.wifi_24px
                                    else R.drawable.mobile_24px
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (networkType == NetworkType.WIFI) "Сеть: WI-FI" else "Сеть: LTE",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(100.dp))
                }
            }

            if (profilesSnapshot.active != null) {
                ActiveProfileBar(
                    snapshot = profilesSnapshot,
                    onSwitch = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showProfilesSheet.value = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                )
            }
        }
    }

    if (showBottomSheet.value) {
        val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet.value = false },
            sheetState = bottomSheetState,
            containerColor = sheetColor
        ) {
            InfoBottomSheet(
                settingsViewModel = settingsViewModel,
                containerColor = sheetColor
            )
        }
    }

    if (showProfilesSheet.value) {
        val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
        ModalBottomSheet(
            onDismissRequest = { showProfilesSheet.value = false },
            sheetState = profilesSheetState,
            containerColor = sheetColor
        ) {
            ProfilesSheetContent(
                settingsViewModel = settingsViewModel,
                snapshot = profilesSnapshot,
                containerColor = sheetColor,
                onClose = { showProfilesSheet.value = false }
            )
        }
    }

    UpdateDialogs(settingsViewModel)
}

// Диалоги обновления

@Suppress("AssignedValueIsNeverRead")
@Composable
private fun UpdateDialogs(settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val updateState by settingsViewModel.updateState.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(false) }

    when (val state = updateState) {
        is UpdateState.Available -> if (!dismissed) {
            AlertDialog(
                onDismissRequest = { dismissed = true },
                title = { Text(stringResource(R.string.update_available_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_available, state.version))
                        if (state.changelog.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    state.changelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        dismissed = true
                        settingsViewModel.downloadUpdate()
                    }) { Text(stringResource(R.string.update_download)) }
                },
                dismissButton = {
                    TextButton(onClick = { dismissed = true }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_downloading_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_downloading, state.progress))
                        Spacer(Modifier.height(12.dp))
                        LinearWavyProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { settingsViewModel.resetUpdateState() },
                title = { Text(stringResource(R.string.update_ready_title)) },
                text = { Text(stringResource(R.string.update_ready_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        settingsViewModel.installUpdate()
                    }) { Text(stringResource(R.string.update_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { settingsViewModel.resetUpdateState() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        else -> {}
    }
}

// Кнопка прокси

@Composable
private fun ProxyToggleButton(
    state: ProxyState,
    isConfigUpdated: Boolean = false,
    onClick: () -> Unit
) {
    val extended = MaterialTheme.extendedColorScheme
    val buttonLabel = when (state) {
        is ProxyState.Starting, is ProxyState.Connecting -> stringResource(R.string.proxy_connecting)
        is ProxyState.Running -> stringResource(R.string.proxy_active_stop)
        is ProxyState.Error -> stringResource(R.string.proxy_error_restart)
        else -> stringResource(R.string.start_proxy)
    }

    val isInactive = state is ProxyState.Idle || state is ProxyState.Error
    val showCheckmark = state is ProxyState.Running || (isConfigUpdated && isInactive)

    val containerColor by animateColorAsState(
        targetValue = when {
            showCheckmark -> extended.successContainer
            state is ProxyState.Error -> MaterialTheme.colorScheme.errorContainer
            state is ProxyState.Starting || state is ProxyState.Connecting ->
                MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tween(500),
        label = "btn_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            showCheckmark -> extended.onSuccessContainer
            state is ProxyState.Error -> MaterialTheme.colorScheme.onErrorContainer
            state is ProxyState.Starting || state is ProxyState.Connecting ->
                MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = tween(500),
        label = "btn_fg"
    )
    val scale by animateFloatAsState(
        targetValue = if (state is ProxyState.Starting || state is ProxyState.Connecting) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btn_scale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(176.dp)
            .scale(scale)
            .clip(CircleShape)
            .semantics { contentDescription = buttonLabel },
        shape = CircleShape,
        color = containerColor,
        tonalElevation = if (state is ProxyState.Running) 3.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                state is ProxyState.Starting || state is ProxyState.Connecting ->
                    LoadingIndicator(
                        color = contentColor,
                        modifier = Modifier.size(60.dp)
                    )
                showCheckmark -> Icon(
                    painterResource(R.drawable.check_circle_24px), stringResource(R.string.proxy_active_stop),
                    Modifier.size(60.dp), tint = contentColor
                )
                state is ProxyState.Error -> Icon(
                    painterResource(R.drawable.error_24px), stringResource(R.string.proxy_error_restart),
                    Modifier.size(60.dp), tint = contentColor
                )
                else -> Icon(
                    painterResource(R.drawable.play_arrow_24px), stringResource(R.string.start_proxy),
                    Modifier.size(60.dp), tint = contentColor
                )
            }
        }
    }
}

// Bottom sheet

@Suppress("AssignedValueIsNeverRead")
@Composable
private fun InfoBottomSheet(
    settingsViewModel: SettingsViewModel,
    containerColor: Color,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val dynamicTheme by settingsViewModel.dynamicTheme.collectAsStateWithLifecycle()
    val updateState by settingsViewModel.updateState.collectAsStateWithLifecycle()

    val appVersion = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—" }
        catch (_: Exception) { "—" }
    }

    val listColors = ListItemDefaults.colors(containerColor = containerColor)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        // Обновление
        item {
            UpdateListItem(
                state = updateState,
                colors = listColors,
                onCheck = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    settingsViewModel.checkForUpdate()
                },
                onDownload = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    settingsViewModel.downloadUpdate()
                },
                onInstall = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    settingsViewModel.installUpdate()
                }
            )
        }

        item { HorizontalDivider() }

        // Ссылки
        item {
            RepoLinkItem(
                title = stringResource(R.string.android_client),
                subtitle = "VQSeGo/VK-Turn-Proxy",
                url = "https://github.com/VQSeGo/VK-Turn-Proxy",
                containerColor = containerColor,
                onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                onOpen = { uriHandler.openUri(it) }
            )
        }

        item {
            RepoLinkItem(
                title = stringResource(R.string.proxy_core),
                subtitle = "samosvalishe/free-turn-proxy",
                url = "https://github.com/samosvalishe/free-turn-proxy",
                containerColor = containerColor,
                onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                onOpen = { uriHandler.openUri(it) }
            )
        }

        item {
            RepoLinkItem(
                title = stringResource(R.string.tg_bot),
                subtitle = "@torvaldsvpnbot",
                url = "https://t.me/torvaldsvpnbot",
                containerColor = containerColor,
                onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                onOpen = { uriHandler.openUri(it) }
            )
        }

        item {
            RepoLinkItem(
                title = stringResource(R.string.tg_support),
                subtitle = "@torvalds_support_bot",
                url = "https://t.me/torvalds_support_bot",
                containerColor = containerColor,
                onHaptic = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) },
                onOpen = { uriHandler.openUri(it) }
            )
        }

        item { HorizontalDivider() }

        // Настройки интерфейса
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.dynamic_theme_title)) },
                supportingContent = { Text(stringResource(R.string.dynamic_theme_desc)) },
                colors = listColors,
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = dynamicTheme,
                        onCheckedChange = {
                            HapticUtil.perform(
                                context,
                                if (it) HapticUtil.Pattern.TOGGLE_ON else HapticUtil.Pattern.TOGGLE_OFF
                            )
                            settingsViewModel.setDynamicTheme(it)
                        }
                    )
                }
            )
        }

        item { HorizontalDivider() }

        item {
            Text(
                text = stringResource(R.string.app_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Text(
                text = "v$appVersion",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Пункт обновления в bottom sheet

@Composable
private fun UpdateListItem(
    state: UpdateState,
    colors: ListItemColors,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val supportingText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_tap_to_check)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    ListItem(
        headlineContent = {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Column {
                Text(
                    supportingText,
                    color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state is UpdateState.Downloading) {
                    LinearWavyProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        },
        trailingContent = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download))
                }
                is UpdateState.ReadyToInstall -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.update_install))
                }
                is UpdateState.Idle, is UpdateState.NoUpdate, is UpdateState.Error ->
                    TextButton(onClick = onCheck) {
                        Text(stringResource(R.string.update_check))
                    }
                else -> {}
            }
        },
        colors = colors
    )
}

// Общие компоненты

@Composable
private fun RepoLinkItem(
    title: String,
    subtitle: String?,
    url: String,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onHaptic: () -> Unit,
    onOpen: (String) -> Unit
) {
    Surface(
        onClick = {
            onHaptic()
            onOpen(url)
        },
        color = containerColor
    ) {
        ListItem(
            headlineContent = { Text(title) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = if (subtitle != null) ({
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }) else null,
            trailingContent = {
                Icon(
                    painterResource(R.drawable.open_in_new_24px),
                    contentDescription = stringResource(R.string.btn_open),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )
    }
}

/**
 * Прилипшая к низу M3 «карточка» — единственная точка входа в управление профилями.
 */
@Composable
private fun ActiveProfileBar(
    snapshot: com.freeturn.app.data.ProfilesSnapshot,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = snapshot.active
    val title: String = active?.name ?: stringResource(R.string.profile_unsaved_label)
    val subtitle: String? = if (active != null) stringResource(R.string.profile_active_label) else null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        onClick = onSwitch
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painterResource(R.drawable.manage_accounts_24px),
                    contentDescription = null,
                    tint = if (active != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            Icon(
                painterResource(R.drawable.arrow_forward_24px),
                contentDescription = stringResource(R.string.profile_switch_action),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun String.redact(enabled: Boolean) = if (enabled) "••••••" else this

@Composable
private fun rememberProxyUptime(connectedSince: Long?): String? {
    if (connectedSince == null) return null
    val tick = androidx.compose.runtime.produceState(initialValue = 0L, connectedSince) {
        while (true) {
            value = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val now = tick.value.coerceAtLeast(connectedSince)
    val totalSec = ((now - connectedSince) / 1_000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private enum class NetworkType {
    WIFI,
    MOBILE,
    NONE
}

@Composable
private fun rememberNetworkType(): NetworkType {
    val context = LocalContext.current
    return androidx.compose.runtime.produceState(initialValue = NetworkType.NONE) {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        fun getCurrentNetworkType(): NetworkType {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return NetworkType.NONE
                val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
                    else -> NetworkType.NONE
                }
            } else {
                @Suppress("DEPRECATION")
                val activeInfo = cm.activeNetworkInfo
                if (activeInfo == null || !activeInfo.isConnected) return NetworkType.NONE
                when (activeInfo.type) {
                    ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                    ConnectivityManager.TYPE_MOBILE -> NetworkType.MOBILE
                    else -> NetworkType.NONE
                }
            }
        }
        
        value = getCurrentNetworkType()
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                value = getCurrentNetworkType()
            }
            override fun onLost(network: Network) {
                value = getCurrentNetworkType()
            }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                value = getCurrentNetworkType()
            }
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
        
        awaitDispose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
    }.value
}

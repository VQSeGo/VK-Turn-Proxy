package com.freeturn.app.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.freeturn.app.ui.HapticUtil
import com.freeturn.app.ui.screens.CaptchaWebViewDialog
import com.freeturn.app.ui.screens.ClientSetupScreen
import com.freeturn.app.ui.screens.HomeScreen
import com.freeturn.app.ui.screens.LogsScreen
import com.freeturn.app.ui.screens.OnboardingScreen
import com.freeturn.app.viewmodel.ProxyState
import com.freeturn.app.viewmodel.SettingsViewModel
import com.freeturn.app.viewmodel.ProxyViewModel
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val CLIENT_SETUP = "client_setup"
    const val HOME = "home"
    const val LOGS = "logs"
}

// Bottom navigation visible only in the main stream
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.LOGS, Routes.CLIENT_SETUP)

@Composable
fun AppNavigation(
    settingsViewModel: SettingsViewModel = koinViewModel(),
    proxyViewModel: ProxyViewModel = koinViewModel()
) {
    val isInitialized by settingsViewModel.isInitialized.collectAsStateWithLifecycle()

    // Do not build NavHost until DataStore is loaded
    if (!isInitialized) return

    val proxyState by proxyViewModel.proxyState.collectAsStateWithLifecycle()
    val initialOnboardingDone by settingsViewModel.initialOnboardingDone.collectAsStateWithLifecycle()
    val initialTgSubscribeShown by settingsViewModel.initialTgSubscribeShown.collectAsStateWithLifecycle()
    val startDestination = remember { if (initialOnboardingDone) Routes.HOME else Routes.ONBOARDING }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val devMode by settingsViewModel.devMode.collectAsStateWithLifecycle()
    val showNavSuite = (currentRoute in BOTTOM_NAV_ROUTES) && devMode

    val adaptiveType = NavigationSuiteScaffoldDefaults
        .calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val layoutType = if (showNavSuite) adaptiveType else NavigationSuiteType.None

    val context = LocalContext.current

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            navItems.forEach { item ->
                val selected = currentRoute == item.route
                item(
                    selected = selected,
                    onClick = {
                        if (!selected) HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        navController.navigate(item.route) {
                            popUpTo(Routes.HOME) { saveState = true; inclusive = false }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1.15f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "nav_icon_scale_${item.route}"
                        )
                        Crossfade(targetState = selected, label = "nav_icon_${item.route}") { isSelected ->
                            Icon(
                                painter = painterResource(
                                    if (isSelected) item.selectedIconRes else item.unselectedIconRes
                                ),
                                contentDescription = stringResource(item.labelResId),
                                modifier = Modifier.scale(scale)
                            )
                        }
                    },
                    label = { Text(stringResource(item.labelResId)) }
                )
            }
        }
    ) {
        AppNavHost(
            navController = navController,
            settingsViewModel = settingsViewModel,
            proxyViewModel = proxyViewModel,
            startDestination = startDestination
        )
    }

    val captchaState = proxyState as? ProxyState.CaptchaRequired
    if (captchaState != null) {
        androidx.compose.runtime.key(captchaState.sessionId) {
            CaptchaWebViewDialog(
                captchaUrl = captchaState.url,
                onDismiss = { proxyViewModel.dismissCaptcha() }
            )
        }
    }

    val isKeystoreFailed = settingsViewModel.isKeystoreFailed()
    val fallbackPin by settingsViewModel.fallbackPin.collectAsStateWithLifecycle()

    if (isKeystoreFailed && fallbackPin == null) {
        var pinInput by rememberSaveable { mutableStateOf("") }
        var isError by rememberSaveable { mutableStateOf(false) }
        var errorMessage by rememberSaveable { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "Защита данных (Keystore сбой)",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column {
                    Text(
                        text = "Android Keystore недоступен. Для защиты паролей SSH и ключей WireGuard введите или создайте мастер PIN-код (от 4 символов).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            pinInput = it
                            isError = false
                        },
                        label = { Text("PIN-код") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = isError,
                        supportingText = if (isError) {
                            { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
                        } else null,
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pinInput.length < 4) {
                            isError = true
                            errorMessage = "Минимум 4 символа"
                        } else {
                            val success = settingsViewModel.verifyAndSetFallbackPin(pinInput)
                            if (!success) {
                                isError = true
                                errorMessage = "Неверный PIN-код или ошибка сохранения"
                            }
                        }
                    }
                ) {
                    Text("Войти")
                }
            }
        )
    }

}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
    ) {
        composable(
            route = Routes.ONBOARDING
        ) {
            OnboardingScreen(
                viewModel = proxyViewModel,
                onSuccess = {
                    settingsViewModel.setOnboardingDone()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = Routes.CLIENT_SETUP,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            ClientSetupScreen(
                settingsViewModel = settingsViewModel,
                showFinishButton = false
            )
        }

        composable(
            route = Routes.HOME,
            enterTransition = {
                val fromRoute = initialState.destination.route
                if (fromRoute != Routes.CLIENT_SETUP && fromRoute != Routes.LOGS && fromRoute != Routes.HOME) {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 450,
                            easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
                        )
                    ) + scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(
                            durationMillis = 450,
                            easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
                        )
                    )
                } else {
                    fadeIn(animationSpec = tween(300))
                }
            },
            exitTransition = {
                fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                val fromRoute = initialState.destination.route
                if (fromRoute != Routes.CLIENT_SETUP && fromRoute != Routes.LOGS && fromRoute != Routes.HOME) {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 450,
                            easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
                        )
                    ) + scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(
                            durationMillis = 450,
                            easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
                        )
                    )
                } else {
                    fadeIn(animationSpec = tween(300))
                }
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(300))
            }
        ) {
            HomeScreen(
                settingsViewModel = settingsViewModel,
                proxyViewModel = proxyViewModel
            )
        }

        composable(
            route = Routes.LOGS,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            LogsScreen(proxyViewModel = proxyViewModel)
        }
    }
}

private data class NavItem(
    val route: String,
    val labelResId: Int,
    val selectedIconRes: Int,
    val unselectedIconRes: Int
)


private val navItems = listOf(
    NavItem(Routes.HOME, R.string.nav_home, R.drawable.home_24px, R.drawable.home_outlined_24px),
    NavItem(Routes.CLIENT_SETUP, R.string.settings_title, R.drawable.settings_24px, R.drawable.settings_outlined_24px),
    NavItem(Routes.LOGS, R.string.logs_title, R.drawable.terminal_24px, R.drawable.terminal_24px)
)

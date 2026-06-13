package com.freeturn.app.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
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

        composable(Routes.CLIENT_SETUP) {
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
                    null
                }
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
                    null
                }
            }
        ) {
            HomeScreen(
                settingsViewModel = settingsViewModel,
                proxyViewModel = proxyViewModel
            )
        }

        composable(Routes.LOGS) {
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

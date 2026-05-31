package com.freeturn.app.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import com.freeturn.app.ui.screens.ServerManagementScreen
import com.freeturn.app.ui.screens.SshSetupScreen
import com.freeturn.app.viewmodel.ProxyState
import com.freeturn.app.viewmodel.MainViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val SSH_SETUP = "ssh_setup"              // из настроек/инфо-модалки
    const val SSH_SETUP_OB = "ssh_setup_ob"        // только в мастере онбординга
    const val SERVER_MANAGEMENT = "server_management"
    const val SERVER_MANAGEMENT_OB = "server_management_ob" // только в мастере онбординга
    const val CLIENT_SETUP = "client_setup"
    const val CLIENT_SETUP_OB = "client_setup_onboarding"
    const val HOME = "home"
    const val LOGS = "logs"
}

// Нижнее меню / рельс видно только в основном потоке, не во время онбординга
private val BOTTOM_NAV_ROUTES = setOf(Routes.HOME, Routes.LOGS, Routes.CLIENT_SETUP)

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val isInitialized by viewModel.isInitialized.collectAsStateWithLifecycle()

    // Не строим NavHost пока DataStore не загружен — иначе startDestination
    // захватит дефолтный onboardingDone=false и всегда покажет онбординг
    if (!isInitialized) return

    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val initialOnboardingDone by viewModel.initialOnboardingDone.collectAsStateWithLifecycle()
    val startDestination = remember { if (initialOnboardingDone) Routes.HOME else Routes.ONBOARDING }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavSuite = currentRoute in BOTTOM_NAV_ROUTES

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
            viewModel = viewModel,
            startDestination = startDestination
        )
    }

    // Диалог капчи поверх любого экрана. Оборачиваем в key(sessionId), чтобы для
    // каждой новой капча-сессии Compose пересоздавал диалог и WebView грузил URL заново
    // (бинарник цикличит креды и для каждой выдаёт новую капчу с тем же localhost-URL).
    val captchaState = proxyState as? ProxyState.CaptchaRequired
    if (captchaState != null) {
        androidx.compose.runtime.key(captchaState.sessionId) {
            CaptchaWebViewDialog(
                captchaUrl = captchaState.url,
                onDismiss = { viewModel.dismissCaptcha() }
            )
        }
    }


}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    viewModel: MainViewModel,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                viewModel = viewModel,
                onSuccess = {
                    viewModel.setOnboardingDone()
                    navController.navigate(Routes.HOME) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.CLIENT_SETUP) {
            ClientSetupScreen(
                viewModel = viewModel,
                showFinishButton = false
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSshSetup = {}
            )
        }

        composable(Routes.LOGS) {
            LogsScreen(viewModel = viewModel)
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
    NavItem(Routes.HOME, R.string.nav_home, R.drawable.home_24px, R.drawable.home_24px),
    NavItem(Routes.CLIENT_SETUP, R.string.settings_title, R.drawable.settings_24px, R.drawable.settings_outlined_24px),
    NavItem(Routes.LOGS, R.string.logs_title, R.drawable.terminal_24px, R.drawable.terminal_24px)
)

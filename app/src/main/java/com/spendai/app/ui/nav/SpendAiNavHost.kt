package com.spendai.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.spendai.app.ui.download.DownloadScreen
import com.spendai.app.ui.home.HomeScreen
import com.spendai.app.ui.permissions.PermissionsScreen
import com.spendai.app.ui.setup.SetupViewModel
import com.spendai.app.ui.test.TestScreen

/**
 * Navigation routes for the Phase 1.5 onboarding flow.
 *
 * `permissions` is the cold-start destination unless the persisted
 * [com.spendai.app.ui.setup.SetupState] is already complete, in which
 * case the host jumps straight to `home`.
 */
object Routes {
    const val PERMISSIONS = "permissions"
    const val DOWNLOAD = "download"
    const val TEST = "test"
    const val HOME = "home"
}

@Composable
fun SpendAiNavHost(
    setupViewModel: SetupViewModel = viewModel(factory = SetupViewModel.Factory),
    navController: NavHostController = rememberNavController(),
) {
    val state by setupViewModel.state.collectAsStateWithLifecycle()
    val startRoute = remember(state.isComplete, state.permissionsGranted) {
        when {
            state.isComplete -> Routes.HOME
            !state.permissionsGranted -> Routes.PERMISSIONS
            !state.modelPresent -> Routes.DOWNLOAD
            !state.modelProbedOk -> Routes.TEST
            else -> Routes.HOME
        }
    }

    NavHost(navController = navController, startDestination = startRoute) {
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                setupViewModel = setupViewModel,
                onContinue = { navController.navigate(Routes.DOWNLOAD) },
            )
        }
        composable(Routes.DOWNLOAD) {
            DownloadScreen(
                setupViewModel = setupViewModel,
                onContinue = { navController.navigate(Routes.TEST) },
            )
        }
        composable(Routes.TEST) {
            TestScreen(
                setupViewModel = setupViewModel,
                onContinue = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                setupViewModel = setupViewModel,
                onRerunSetup = {
                    navController.navigate(Routes.PERMISSIONS) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}

package com.spendai.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.spendai.app.R
import com.spendai.app.ui.download.DownloadScreen
import com.spendai.app.ui.edit.EditTransactionScreen
import com.spendai.app.ui.home.HomeScreen
import com.spendai.app.ui.insights.InsightsScreen
import com.spendai.app.ui.permissions.PermissionsScreen
import com.spendai.app.ui.review.ReviewScreen
import com.spendai.app.ui.setup.SetupViewModel
import com.spendai.app.ui.sources.SourcesScreen
import com.spendai.app.ui.test.TestScreen
import com.spendai.app.ui.transactions.TransactionsScreen
import com.spendai.app.ui.debug.DebugLogScreen
import com.spendai.app.ui.debug.DebugLogDetailScreen

object Routes {
    const val PERMISSIONS = "permissions"
    const val DOWNLOAD = "download"
    const val TEST = "test"
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val INSIGHTS = "insights"
    const val REVIEW = "review"
    const val SOURCES = "sources"
    const val DEBUG_LOG = "debugLog"
    const val DEBUG_LOG_DETAIL = "debugLogDetail/{id}"
    fun debugLogDetail(id: Long) = "debugLogDetail/$id"
    const val TRANSACTION_DETAIL = "transactionDetail/{id}"
    fun transactionDetail(id: Long) = "transactionDetail/$id"
}

private data class BottomDest(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDest(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    BottomDest(Routes.TRANSACTIONS, R.string.nav_transactions, Icons.Outlined.Receipt),
    BottomDest(Routes.INSIGHTS, R.string.nav_insights, Icons.Outlined.Insights),
)

@Composable
fun SpendAiNavHost(
    setupViewModel: SetupViewModel = viewModel(factory = SetupViewModel.Factory),
    navController: NavHostController = rememberNavController(),
) {
    val state by setupViewModel.state.collectAsStateWithLifecycle()
    if (!state.isComplete) {
        OnboardingNavHost(
            setupViewModel = setupViewModel,
            navController = navController,
            state = state,
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val topLevel = bottomDestinations.any { it.route == currentRoute }
            if (topLevel) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    bottomDestinations.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    setupViewModel = setupViewModel,
                    onRerunSetup = {
                        navController.navigate(Routes.PERMISSIONS) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    },
                    onOpenReview = { navController.navigate(Routes.REVIEW) },
                    onOpenTransactions = { navController.navigate(Routes.TRANSACTIONS) },
                    onOpenDebugLog = { navController.navigate(Routes.DEBUG_LOG) },
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                )
            }
            composable(Routes.TRANSACTIONS) {
                TransactionsScreen(
                    onTransactionClick = { id ->
                        navController.navigate(Routes.transactionDetail(id))
                    },
                )
            }
            composable(
                route = Routes.TRANSACTION_DETAIL,
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("id") ?: 0L
                EditTransactionScreen(
                    transactionId = id,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.INSIGHTS) { InsightsScreen() }
            composable(Routes.REVIEW) { ReviewScreen() }
            composable(Routes.SOURCES) {
                SourcesScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DEBUG_LOG) {
                DebugLogScreen(
                    onBack = { navController.popBackStack() },
                    onRowClick = { id ->
                        navController.navigate(Routes.debugLogDetail(id))
                    },
                )
            }
            composable(
                route = Routes.DEBUG_LOG_DETAIL,
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("id") ?: 0L
                DebugLogDetailScreen(
                    logId = id,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun OnboardingNavHost(
    setupViewModel: SetupViewModel,
    navController: NavHostController,
    state: com.spendai.app.ui.setup.SetupState,
) {
    val startRoute = remember(state.permissionsGranted, state.modelPresent, state.modelProbedOk) {
        when {
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
            // Reached only if a user taps "Re-run setup" from the overflow
            // menu and the startRoute evaluated to HOME; this branch
            // is essentially unreachable in practice because
            // isComplete would be true by then.
        }
    }
}

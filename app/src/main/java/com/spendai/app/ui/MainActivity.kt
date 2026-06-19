package com.spendai.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.spendai.app.ui.nav.Routes
import com.spendai.app.ui.nav.SpendAiNavHost
import com.spendai.app.ui.theme.SpendAiTheme

/**
 * Single Activity for SpendAI. Hosts the Compose nav graph and applies
 * the M3 theme. There is no Fragment or secondary Activity in
 * Phase 1.5.
 *
 * ## Deep-links
 *
 * `IngestionService` posts a terminal reprompt notification with a
 * PendingIntent that targets this activity carrying
 * [EXTRA_TRANSACTION_ID] and the [ACTION_OPEN_TRANSACTION] action.
 * The activity reads the extra in both `onCreate` (cold start) and
 * `onNewIntent` (warm) and forwards it to the nav host via a small
 * [pendingDeepLink] state which the [SpendAiApp] composable
 * observes and navigates on.
 */
class MainActivity : ComponentActivity() {

    private val pendingDeepLink: MutableState<DeepLink?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDeepLink.value = intent?.toDeepLink()
        setContent {
            SpendAiApp(
                pendingDeepLink = pendingDeepLink.value,
                onDeepLinkConsumed = { pendingDeepLink.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = intent.toDeepLink()
    }

    private fun Intent.toDeepLink(): DeepLink? {
        if (action != ACTION_OPEN_TRANSACTION) return null
        val id = getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it > 0L } ?: return null
        return DeepLink.Transaction(id)
    }
}

/** A small in-process queue of deep-links the activity has not yet navigated to. */
sealed interface DeepLink {
    data class Transaction(val id: Long) : DeepLink
}

@Composable
private fun SpendAiApp(
    pendingDeepLink: DeepLink?,
    onDeepLinkConsumed: () -> Unit,
) {
    val navController = remember { mutableStateOf<NavHostController?>(null) }
    SpendAiTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SpendAiNavHost(
                deepLink = pendingDeepLink,
                onDeepLinkConsumed = onDeepLinkConsumed,
                registerNavController = { navController.value = it },
            )
        }
    }
}

const val ACTION_OPEN_TRANSACTION = "com.spendai.app.action.OPEN_TRANSACTION"
const val EXTRA_TRANSACTION_ID = "com.spendai.app.extra.TRANSACTION_ID"

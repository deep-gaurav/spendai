package com.spendai.app.ui.insights.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.domain.agent.insights.AgenticStatus
import com.spendai.app.ui.theme.Dimens

/**
 * The agentic insights screen. A multi-turn chat with the
 * on-device model. The model has one tool (query_database,
 * read-only SQL over the user's transactions) and can
 * respond with prose plus inline charts.
 *
 * The current auto Insights screen (com.spendai.app.ui.insights.InsightsScreen)
 * is left untouched. The user reaches this screen via an
 * "Ask AI" entry on the Insights screen header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgenticInsightsScreen(
    onBack: () -> Unit,
    viewModel: AgenticInsightsViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val debugEnabled by viewModel.debugEnabled.collectAsStateWithLifecycle()
    val verifierEnabled by viewModel.verifierEnabled.collectAsStateWithLifecycle()
    val debugLog by viewModel.debugLog.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.agentic_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.agentic_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleDebug() }) {
                        Icon(
                            imageVector = Icons.Filled.BugReport,
                            contentDescription = "Debug",
                            tint = if (debugEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.setVerifierEnabled(!verifierEnabled) }) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = if (verifierEnabled) {
                                "Verifier on (tap to disable)"
                            } else {
                                "Verifier off (tap to enable)"
                            },
                            tint = if (verifierEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status is AgenticStatus.Thinking || status is AgenticStatus.RunningTool) {
                        IconButton(onClick = { viewModel.cancel() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                StatusRow(status, verifierEnabled)
                AgenticChatInput(
                    onSend = viewModel::sendMessage,
                    enabled = status !is AgenticStatus.Thinking,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (messages.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = Dimens.SpaceMd,
                        vertical = Dimens.SpaceSm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        AgenticMessageBubble(message = msg)
                    }
                }
            }
            if (debugEnabled) {
                AgenticDebugPanel(
                    entries = debugLog,
                    onCopy = {
                        val transcript = viewModel.renderTranscript()
                        val clip = ClipData.newPlainText("SpendAI transcript", transcript)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(clip)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusRow(status: AgenticStatus, verifierEnabled: Boolean) {
    val (label, spinner) = when (status) {
        is AgenticStatus.Idle -> stringResource(R.string.agentic_status_idle) to false
        is AgenticStatus.Thinking -> stringResource(R.string.agentic_status_thinking) to true
        is AgenticStatus.RunningTool -> stringResource(R.string.agentic_status_running_tool) to true
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        if (spinner) {
            CircularProgressIndicator(
                modifier = Modifier.width(12.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Box(modifier = Modifier.width(12.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Pill that surfaces the verifier state. Default is
        // OFF, so this is the steady-state the user sees.
        Text(
            text = if (verifierEnabled) "Verifier on" else "Verifier off",
            style = MaterialTheme.typography.labelSmall,
            color = if (verifierEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(Dimens.SpaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.agentic_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.agentic_empty_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

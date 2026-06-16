package com.spendai.app.ui.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.ui.setup.SetupViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(
    setupViewModel: SetupViewModel,
    onContinue: () -> Unit,
    viewModel: TestViewModel = viewModel(factory = TestViewModelFactory(setupViewModel)),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.test_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.test_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(stringResource(R.string.test_prompt_label))
            Text(
                PROBE_PROMPT,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (ui.engineLabel.isNotEmpty()) {
                Text(
                    stringResource(R.string.test_engine_status_format, ui.engineLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            StatusBlock(phase = ui.phase, response = ui.response)

            Spacer(Modifier.height(8.dp))

            when (ui.phase) {
                TestUiState.Phase.Idle, TestUiState.Phase.Fail -> {
                    Button(
                        onClick = viewModel::run,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.test_run))
                    }
                    if (ui.phase == TestUiState.Phase.Fail) {
                        OutlinedButton(
                            onClick = { viewModel.continueAnyway(); onContinue() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.test_continue_anyway)) }
                    }
                }
                TestUiState.Phase.Pass -> {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.onboarding_continue)) }
                }
                TestUiState.Phase.Initializing, TestUiState.Phase.Asking -> {
                    OutlinedButton(
                        onClick = { /* busy; nothing to do */ },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.test_asking)) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StatusBlock(phase: TestUiState.Phase, response: String?) {
    val (icon, tint, message) = when (phase) {
        TestUiState.Phase.Pass -> Triple(
            Icons.Outlined.CheckCircle,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.test_pass),
        )
        TestUiState.Phase.Fail -> Triple(
            Icons.Outlined.Cancel,
            MaterialTheme.colorScheme.error,
            stringResource(R.string.test_fail),
        )
        TestUiState.Phase.Initializing -> Triple(
            null, MaterialTheme.colorScheme.primary,
            stringResource(R.string.test_initializing),
        )
        TestUiState.Phase.Asking -> Triple(
            null, MaterialTheme.colorScheme.primary,
            stringResource(R.string.test_asking),
        )
        TestUiState.Phase.Idle -> Triple(
            null, MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.test_idle),
        )
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                } else if (phase == TestUiState.Phase.Initializing ||
                    phase == TestUiState.Phase.Asking
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(message, style = MaterialTheme.typography.titleMedium, color = tint)
            }

            if (!response.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                SectionHeader(stringResource(R.string.test_response_label))
                Text(
                    response,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun TestViewModelFactory(
    setupViewModel: SetupViewModel,
): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
            TestViewModel(app, setupViewModel)
        }
    }

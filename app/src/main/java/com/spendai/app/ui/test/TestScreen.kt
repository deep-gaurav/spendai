package com.spendai.app.ui.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.OnboardingScaffold
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.setup.SetupViewModel
import com.spendai.app.ui.theme.Dimens
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun TestScreen(
    setupViewModel: SetupViewModel,
    onContinue: () -> Unit,
    viewModel: TestViewModel = viewModel(factory = TestViewModelFactory(setupViewModel)),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.app_name),
        step = 3,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
        ) {
            // Hero illustration
            CartoonIcon(
                id = R.drawable.art_test_mascot,
                size = 160.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceSm),
            )

            Text(
                text = stringResource(R.string.test_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.test_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Strict prompt
            StickerCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    SectionLabel(stringResource(R.string.test_prompt_label))
                    Text(
                        text = PROBE_PROMPT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (ui.engineLabel.isNotEmpty()) {
                        Spacer(Modifier.height(Dimens.SpaceXs / 2))
                        Text(
                            text = stringResource(R.string.test_engine_status_format, ui.engineLabel),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Status block
            StatusBlock(phase = ui.phase, response = ui.response)

            Spacer(Modifier.height(Dimens.SpaceXs))

            when (ui.phase) {
                TestUiState.Phase.Idle, TestUiState.Phase.Fail -> {
                    BigPrimaryButton(
                        onClick = viewModel::run,
                        text = stringResource(R.string.test_run),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            CartoonIcon(
                                id = R.drawable.ic_play_cartoon,
                                size = 28.dp,
                            )
                        },
                    )
                    if (ui.phase == TestUiState.Phase.Fail) {
                        BigOutlinedButton(
                            onClick = {
                                viewModel.continueAnyway()
                                onContinue()
                            },
                            text = stringResource(R.string.test_continue_anyway),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                TestUiState.Phase.Pass -> {
                    BigPrimaryButton(
                        onClick = onContinue,
                        text = stringResource(R.string.onboarding_continue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TestUiState.Phase.Initializing, TestUiState.Phase.Asking -> {
                    BigOutlinedButton(
                        onClick = { /* busy; nothing to do */ },
                        text = stringResource(R.string.test_asking),
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(phase: TestUiState.Phase, response: String?) {
    val (iconRes, tint, message) = when (phase) {
        TestUiState.Phase.Pass -> Triple(
            R.drawable.ic_check_cartoon,
            androidx.compose.ui.graphics.Color.Unspecified,
            stringResource(R.string.test_pass),
        )
        TestUiState.Phase.Fail -> Triple(
            R.drawable.ic_cross_cartoon,
            androidx.compose.ui.graphics.Color.Unspecified,
            stringResource(R.string.test_fail),
        )
        TestUiState.Phase.Initializing -> Triple(
            R.drawable.ic_cloud_download_cartoon,
            androidx.compose.ui.graphics.Color.Unspecified,
            stringResource(R.string.test_initializing),
        )
        TestUiState.Phase.Asking -> Triple(
            R.drawable.ic_cloud_download_cartoon,
            androidx.compose.ui.graphics.Color.Unspecified,
            stringResource(R.string.test_asking),
        )
        TestUiState.Phase.Idle -> Triple(
            R.drawable.ic_cloud_download_cartoon,
            androidx.compose.ui.graphics.Color.Unspecified,
            stringResource(R.string.test_idle),
        )
    }

    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (phase) {
                    TestUiState.Phase.Initializing, TestUiState.Phase.Asking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> CartoonIcon(id = iconRes, size = 36.dp)
                }
                Spacer(Modifier.size(Dimens.SpaceSm))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = when (phase) {
                        TestUiState.Phase.Pass -> MaterialTheme.colorScheme.tertiary
                        TestUiState.Phase.Fail -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            if (!response.isNullOrBlank()) {
                SectionLabel(stringResource(R.string.test_response_label))
                Text(
                    text = response,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun TestViewModelFactory(
    setupViewModel: SetupViewModel,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
            TestViewModel(app, setupViewModel)
        }
    }

package com.spendai.app.ui.download

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import java.util.Locale

@Composable
fun DownloadScreen(
    setupViewModel: SetupViewModel,
    onContinue: () -> Unit,
    viewModel: DownloadViewModel = viewModel(factory = DownloadViewModelFactory(setupViewModel)),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.app_name),
        step = 2,
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
                id = R.drawable.art_download_mascot,
                size = 160.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceSm),
            )

            Text(
                text = "Gemini API Setup",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "SpendAI uses the Google Gemini API to parse financial messages quickly and accurately on your device. Please enter your Gemini API key from Google AI Studio to proceed.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = ui.apiKey,
                onValueChange = { viewModel.onApiKeyChanged(it) },
                label = { Text("Gemini API Key") },
                placeholder = { Text("AIzaSy...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Dimens.SpaceXs))

            if (ui.present) {
                BigPrimaryButton(
                    onClick = onContinue,
                    text = stringResource(R.string.onboarding_continue),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "Please enter your API key to continue setup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DownloadViewModelFactory(
    setupViewModel: SetupViewModel,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
            DownloadViewModel(app, setupViewModel)
        }
    }

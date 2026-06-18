package com.spendai.app.ui.download

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.OnboardingScaffold
import com.spendai.app.ui.setup.SetupViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spendai.app.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    setupViewModel: SetupViewModel,
    onContinue: () -> Unit,
    viewModel: DownloadViewModel = viewModel(factory = DownloadViewModelFactory(setupViewModel)),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    var provider by remember { mutableStateOf(ui.provider) }
    var apiKey by remember { mutableStateOf(ui.apiKey) }
    var model by remember { mutableStateOf(ui.model) }
    var baseUrl by remember { mutableStateOf(ui.baseUrl) }

    LaunchedEffect(ui.provider, ui.apiKey, ui.model, ui.baseUrl) {
        provider = ui.provider
        apiKey = ui.apiKey
        model = ui.model
        baseUrl = ui.baseUrl
    }

    var expanded by remember { mutableStateOf(false) }
    val providers = listOf(
        "GEMINI" to "Google Gemini",
        "OPENAI" to "OpenAI",
        "CLAUDE" to "Anthropic Claude",
        "KIMI" to "Kimi (Moonshot)",
        "ZHIPU" to "Zai (Zhipu AI)",
        "OLLAMA" to "Ollama (Local)"
    )
    val providerLabel = providers.firstOrNull { it.first == provider }?.second ?: provider

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
            CartoonIcon(
                id = R.drawable.art_download_mascot,
                size = 160.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceSm),
            )

            Text(
                text = "Model Configuration",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Configure your preferred LLM provider. SpendAI requires a model with at least a 64K context window to resolve transaction ledger daily groupings. We recommend Google Gemini in AI Studio as a free option (using gemma-4-31b-it).",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Provider Dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = providerLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("LLM Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    providers.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                provider = id
                                expanded = false
                                // Set recommended defaults when provider changes
                                when (id) {
                                    "GEMINI" -> {
                                        model = "gemma-4-31b-it"
                                        baseUrl = ""
                                    }
                                    "OPENAI" -> {
                                        model = "gpt-4o-mini"
                                        baseUrl = ""
                                    }
                                    "CLAUDE" -> {
                                        model = "claude-3-5-haiku"
                                        baseUrl = ""
                                    }
                                    "KIMI" -> {
                                        model = "moonshot-v1-128k"
                                        baseUrl = ""
                                    }
                                    "ZHIPU" -> {
                                        model = "glm-4-flash"
                                        baseUrl = ""
                                    }
                                    "OLLAMA" -> {
                                        model = "llama3"
                                        baseUrl = "http://10.0.2.2:11434/v1/chat/completions"
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (provider == "OLLAMA") "API Key / Token (Optional for Ollama)" else "API Key / Token") },
                placeholder = { Text(if (provider == "GEMINI") "AIzaSy..." else "sk-...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Model Name
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model Name") },
                placeholder = { Text("e.g. gemma-4-31b-it") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Base URL (optional / custom)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL (Optional)") },
                placeholder = { Text(if (provider == "OLLAMA") "http://10.0.2.2:11434/v1/chat/completions" else "Default") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Dimens.SpaceXs))

            if (ui.verificationError != null) {
                Text(
                    text = ui.verificationError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.SpaceXs)
                )
            }

            if (ui.running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(Dimens.SpaceSm))
                    Text(
                        text = "Verifying connection...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                BigPrimaryButton(
                    onClick = {
                        viewModel.verifyAndSave(provider, apiKey, model, baseUrl) {
                            onContinue()
                        }
                    },
                    text = "Verify & Continue",
                    modifier = Modifier.fillMaxWidth(),
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

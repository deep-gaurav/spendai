package com.spendai.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.theme.Dimens
import com.spendai.app.ui.download.DownloadViewModel

/**
 * Lightweight post-onboarding screen that lets the user edit the
 * LLM provider, model name, API key, and base URL. Reuses
 * [DownloadViewModel] because that view-model is the single source
 * of truth for the `spendai_settings` SharedPreferences (it loads
 * the current values in `init { refreshPresent() }` and persists
 * new ones via `verifyAndSave`, which also re-initializes the
 * inference engine and runs the same connection probe the
 * onboarding test screen uses).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = viewModel(factory = DownloadViewModel.Factory),
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

    val providers = listOf(
        "GEMINI" to "Google Gemini",
        "OPENAI" to "OpenAI",
        "CLAUDE" to "Anthropic Claude",
        "KIMI" to "Kimi (Moonshot)",
        "ZHIPU" to "Zai (Zhipu AI)",
        "OLLAMA" to "Ollama (Local)",
    )
    val providerLabel = providers.firstOrNull { it.first == provider }?.second ?: provider
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.model_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
        ) {
            Text(
                text = stringResource(R.string.model_settings_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = providerLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.model_settings_provider_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    providers.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                provider = id
                                expanded = false
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
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = {
                    Text(
                        if (provider == "OLLAMA") {
                            stringResource(R.string.model_settings_api_key_optional)
                        } else {
                            stringResource(R.string.model_settings_api_key_label)
                        },
                    )
                },
                placeholder = {
                    Text(if (provider == "GEMINI") "AIzaSy..." else "sk-...")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.model_settings_model_label)) },
                placeholder = { Text("e.g. gemma-4-31b-it") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.model_settings_base_url_label)) },
                placeholder = {
                    Text(
                        if (provider == "OLLAMA") {
                            "http://10.0.2.2:11434/v1/chat/completions"
                        } else {
                            "Default"
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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
                        .padding(horizontal = Dimens.SpaceXs),
                )
            }

            if (ui.running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Dimens.SpaceSm))
                    Text(
                        text = stringResource(R.string.model_settings_verifying),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                BigPrimaryButton(
                    onClick = {
                        viewModel.verifyAndSave(provider, apiKey, model, baseUrl) {
                            onBack()
                        }
                    },
                    text = stringResource(R.string.model_settings_save),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

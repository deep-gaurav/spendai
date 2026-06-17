package com.spendai.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    viewModel: SourcesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Sources & categories", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
        ) {
            item { SectionLabel("Categories") }
            item {
                StickerCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                        if (state.categories.isEmpty()) {
                            Text(
                                text = "No categories yet. They appear here once Agent 2 assigns one to a transaction.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.categories.forEach { cat ->
                                CategoryRow(
                                    category = cat,
                                    onEmojiChange = { emoji -> viewModel.setCategoryEmoji(cat, emoji) },
                                )
                            }
                        }
                    }
                }
            }
            item { SectionLabel("Financial sources") }
            items(state.sources, key = { it.id }) { source ->
                SourceRow(
                    source = source,
                    accounts = state.accounts[source.id].orEmpty(),
                    onDisplayNameChange = { name -> viewModel.setSourceDisplayName(source, name) },
                    onAccountColorChange = { acc, hex -> viewModel.setAccountColor(acc, hex) },
                )
            }
            item { Spacer(Modifier.size(Dimens.SpaceMd)) }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, onEmojiChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(vertical = Dimens.SpaceXs),
    ) {
        Text(text = category.emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(Dimens.SpaceSm))
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    if (showPicker) {
        com.spendai.app.ui.edit.EmojiPickerDialog(
            currentEmoji = category.emoji,
            onDismiss = { showPicker = false },
            onSelect = { emoji ->
                onEmojiChange(emoji)
                showPicker = false
            },
        )
    }
}

@Composable
private fun SourceRow(
    source: FinancialSource,
    accounts: List<Account>,
    onDisplayNameChange: (String) -> Unit,
    onAccountColorChange: (Account, String?) -> Unit,
) {
    var displayName by remember(source.id, source.displayName) {
        mutableStateOf(source.displayName.orEmpty())
    }
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Source")
            Text(
                text = source.sourceKey,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    onDisplayNameChange(it)
                },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (accounts.isNotEmpty()) {
                HorizontalDivider()
                SectionLabel("Accounts")
            }
            accounts.forEach { acc ->
                AccountRow(
                    account = acc,
                    onColorChange = { hex -> onAccountColorChange(acc, hex) },
                )
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    onColorChange: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Text(
            text = "${account.issuer} ${account.maskedNumber}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ColorPaletteRow(
            currentHex = account.colorHex,
            onPick = onColorChange,
        )
    }
}

@Composable
private fun ColorPaletteRow(
    currentHex: String?,
    onPick: (String?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (currentHex == null) 2.dp else 1.dp,
                    color = if (currentHex == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .clickable { onPick(null) },
        )
        ACCOUNT_COLOR_PALETTE.forEach { hex ->
            val isSelected = hex.equals(currentHex, ignoreCase = true)
            val color = parseHexColor(hex) ?: Color.Gray
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

private fun parseHexColor(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return null
    return try {
        val r = cleaned.substring(0, 2).toInt(16)
        val g = cleaned.substring(2, 4).toInt(16)
        val b = cleaned.substring(4, 6).toInt(16)
        Color(red = r, green = g, blue = b)
    } catch (_: NumberFormatException) {
        null
    }
}

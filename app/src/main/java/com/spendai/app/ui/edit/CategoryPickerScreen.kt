package com.spendai.app.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spendai.app.data.local.entity.Category
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerScreen(
    categories: List<Category>,
    onPick: (Long) -> Unit,
    onAdd: (name: String, emoji: String) -> Unit,
    onBack: () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Choose category", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            Text(
                text = "Pick a category. Add a new one if none fit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StickerCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdd = true }
                            .padding(vertical = Dimens.SpaceSm),
                    ) {
                        Text(
                            text = "\u2795",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(Dimens.SpaceSm))
                        Text(
                            text = "Add new category",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (categories.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    LazyColumn {
                        items(categories, key = { it.id }) { cat ->
                            CategoryRow(
                                cat = cat,
                                onClick = { onPick(cat.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddCategoryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, emoji ->
                onAdd(name, emoji)
                showAdd = false
            },
        )
    }
}

@Composable
private fun CategoryRow(cat: Category, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Dimens.SpaceSm),
    ) {
        Text(text = cat.emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(Dimens.SpaceSm))
        Text(
            text = cat.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, emoji: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("\uD83D\uDCB8") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Pick an emoji")
                EmojiGrid(selected = emoji, onSelect = { emoji = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, emoji) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EmojiGrid(selected: String, onSelect: (String) -> Unit) {
    val emojis = EMOJI_PALETTE
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        emojis.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                row.forEach { e ->
                    val isSelected = e == selected
                    Text(
                        text = e,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .clickable { onSelect(e) }
                            .padding(4.dp)
                            .let { mod ->
                                if (isSelected) mod.then(Modifier.padding(2.dp))
                                else mod
                            },
                    )
                }
            }
        }
    }
}

internal val EMOJI_PALETTE: List<String> = listOf(
    "\uD83C\uDF54", // 🍔
    "\u26FD",       // ⛽
    "\uD83D\uDED2", // 🛒
    "\uD83D\uDE8C", // 🚌
    "\uD83D\uDED2", // 🛍️
    "\uD83E\uDDFE", // 🧾
    "\uD83C\uDFAC", // 🎬
    "\uD83D\uDC8A", // 💊
    "\uD83D\uDD01", // 🔁
    "\uD83D\uDCB0", // 💰
    "\uD83D\uDCB8", // 💸
    "\uD83D\uDCFA", // 📺
    "\uD83C\uDFE0", // 🏠
    "\u2708\uFE0F", // ✈️
    "\uD83D\uDE96", // 🚖
    "\uD83C\uDF55", // 🍕
    "\u2615",       // ☕
    "\uD83C\uDFAE", // 🎮
    "\uD83D\uDCDA", // 📚
    "\uD83D\uDCBC", // 💼
    "\uD83C\uDFE5", // 🏥
    "\uD83C\uDF93", // 🎓
    "\uD83D\uDC3E", // 🐾
    "\uD83C\uDF81", // 🎁
)

@Composable
fun EmojiPickerDialog(
    currentEmoji: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick an emoji") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                EMOJI_PALETTE.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                        row.forEach { e ->
                            val isSelected = e == currentEmoji
                            Text(
                                text = e,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .clickable { onSelect(e) }
                                    .padding(2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

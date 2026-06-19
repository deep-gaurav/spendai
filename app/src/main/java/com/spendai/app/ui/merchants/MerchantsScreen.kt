package com.spendai.app.ui.merchants

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.data.local.entity.MerchantMetadataKind
import com.spendai.app.ui.theme.Dimens

/**
 * Manual-edit screen for merchant knowledge. The sibling
 * surface to the Ask-AI `mutate_merchant` tool: every save
 * goes through the same mutator, so the two paths can
 * never disagree on what `isSelf = true` or `add a note`
 * means.
 *
 * Layout:
 *  - Top app bar with back arrow + title
 *  - Search field that filters on name, VPA, and metadata values
 *  - LazyColumn of merchant rows, each row showing:
 *    - The merchant name
 *    - An `isSelf` chip when set
 *    - The first metadata line (or a "+" hint)
 *  - Tap a row -> edit dialog with toggle + per-kind add/remove
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantsScreen(
    onBack: () -> Unit,
    viewModel: MerchantsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingMerchant by remember { mutableStateOf<MerchantRow?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Merchants",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::onSearchChange,
                placeholder = { Text("Search by name, VPA, or note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.merchants.isEmpty()) {
                EmptyState(
                    query = state.search,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.merchants, key = { it.id }) { row ->
                        MerchantRowCard(
                            row = row,
                            onClick = { editingMerchant = row },
                        )
                    }
                }
            }
        }
    }

    val editing = editingMerchant
    if (editing != null) {
        EditMerchantDialog(
            row = editing,
            onDismiss = { editingMerchant = null },
            onSetIsSelf = { isSelf ->
                viewModel.setIsSelf(editing.id, isSelf)
            },
            onAddMetadata = { kind, value ->
                viewModel.addMetadata(editing.id, kind, value)
            },
            onRemoveMetadata = { kind ->
                viewModel.removeMetadata(editing.id, kind)
            },
        )
    }
}

@Composable
private fun MerchantRowCard(
    row: MerchantRow,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(Dimens.BorderThin, outline, shape)
            .background(surface, shape)
            .clickable(onClick = onClick)
            .padding(Dimens.SpaceSm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (row.isSelf) {
                    IsSelfBadge()
                }
            }
            if (row.vpa != null) {
                Text(
                    text = "VPA: ${row.vpa}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val firstLine = row.metadata.firstOrNull()
            if (firstLine != null) {
                Text(
                    text = "${firstLine.second.name}: ${firstLine.first.value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (row.metadata.size > 1) {
                Text(
                    text = "+ ${row.metadata.size - 1} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IsSelfBadge() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(8.dp))
            .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "me",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
private fun EmptyState(query: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (query.isBlank()) {
                "No merchants yet. They appear here once Agent 2 sees a counterparty."
            } else {
                "No merchants match \"$query\"."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Edit dialog. Toggles isSelf on/off and lets the user add /
 * remove NOTE, CATEGORY_HINT, and LABEL entries. The "Add"
 * button opens a small inline form so the user can pick a kind
 * and type a value in one tap.
 */
@Composable
private fun EditMerchantDialog(
    row: MerchantRow,
    onDismiss: () -> Unit,
    onSetIsSelf: (Boolean) -> Unit,
    onAddMetadata: (MerchantMetadataKind, String) -> Unit,
    onRemoveMetadata: (MerchantMetadataKind) -> Unit,
) {
    var showAddForm by remember { mutableStateOf(false) }
    var pendingKind by remember { mutableStateOf(MerchantMetadataKind.NOTE) }
    var pendingValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                ) {
                    Text(
                        text = "Counterparty is me",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = row.isSelf,
                        onCheckedChange = onSetIsSelf,
                    )
                }
                if (row.vpa != null) {
                    Text(
                        text = "VPA: ${row.vpa}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = "Metadata",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.metadata.forEach { (md, kind) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                        ) {
                            Text(
                                text = kind.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 110.dp, max = 110.dp),
                            )
                            Text(
                                text = md.value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRemoveMetadata(kind) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
                if (showAddForm) {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                        ) {
                            MerchantMetadataKind.values().forEach { kind ->
                                KindChip(
                                    kind = kind,
                                    selected = pendingKind == kind,
                                    onClick = { pendingKind = kind },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = pendingValue,
                            onValueChange = { pendingValue = it },
                            placeholder = { Text("Value") },
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                        ) {
                            TextButton(
                                onClick = {
                                    if (pendingValue.isNotBlank()) {
                                        onAddMetadata(pendingKind, pendingValue.trim())
                                        pendingValue = ""
                                        showAddForm = false
                                    }
                                },
                            ) { Text("Save") }
                            TextButton(onClick = {
                                pendingValue = ""
                                showAddForm = false
                            }) { Text("Cancel") }
                        }
                    }
                } else {
                    TextButton(onClick = { showAddForm = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  Add metadata")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun KindChip(
    kind: MerchantMetadataKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(10.dp))
            .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.SpaceXs, vertical = 4.dp),
    ) {
        Text(
            text = kind.name,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}


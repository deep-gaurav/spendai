package com.spendai.app.ui.edit

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CHANNEL_OPTIONS: List<String?> = listOf(
    null, "UPI", "CARD", "NETBANKING", "NEFT", "IMPS", "WALLET", "ATM",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    transactionId: Long,
    onBack: () -> Unit,
) {
    val viewModel: EditTransactionViewModel = viewModel(
        factory = EditTransactionViewModel.factory(transactionId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCategoryPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Edit transaction", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
        ) {
            if (state.loading) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                return@Box
            }
            if (state.notFound) {
                Text(
                    text = "Transaction not found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                return@Box
            }

            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
                SourceSmsCard(rawSms = state.rawSms)
                StatusRow(state.status, state.confidence)
                TitleCard(
                    title = state.title,
                    onTitleChange = viewModel::setTitle,
                )
                AmountAndDirectionCard(
                    amountText = state.amountText,
                    onAmountChange = viewModel::setAmount,
                    direction = state.direction,
                    onDirectionChange = viewModel::setDirection,
                    currency = state.currency,
                    onCurrencyChange = viewModel::setCurrency,
                )
                CategoryCard(
                    currentCategoryId = state.categoryId,
                    allCategories = state.allCategories,
                    onClick = { showCategoryPicker = true },
                )
                MerchantCard(
                    allMerchants = state.allMerchants,
                    selectedMerchantId = state.merchantId,
                    onPick = viewModel::setMerchant,
                    creatingMerchant = state.creatingMerchant,
                    onStartCreating = viewModel::startCreatingMerchant,
                    newMerchantName = state.newMerchantName,
                    onNewNameChange = viewModel::setNewMerchantName,
                )
                AccountCard(
                    allAccounts = state.allAccounts,
                    selectedAccountId = state.accountId,
                    onPick = viewModel::setAccount,
                )
                DetailsCard(
                    channel = state.channel,
                    onChannelChange = viewModel::setChannel,
                    referenceNo = state.referenceNo,
                    onReferenceNoChange = viewModel::setReferenceNo,
                    notes = state.notes,
                    onNotesChange = viewModel::setNotes,
                )
                if (state.saveError != null) {
                    Text(
                        text = state.saveError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                BigPrimaryButton(
                    onClick = viewModel::save,
                    text = "Save",
                    modifier = Modifier.fillMaxWidth(),
                )
                BigOutlinedButton(
                    onClick = viewModel::delete,
                    text = "Delete",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(Dimens.SpaceMd))
            }
        }
    }

    if (showCategoryPicker) {
        CategoryPickerDialog(
            categories = state.allCategories,
            currentId = state.categoryId,
            onPick = { id ->
                viewModel.setCategory(id)
                showCategoryPicker = false
            },
            onAdd = { name, emoji ->
                viewModel.addCategory(name, emoji)
            },
            onDismiss = { showCategoryPicker = false },
        )
    }
}

@Composable
private fun CategoryPickerDialog(
    categories: List<com.spendai.app.data.local.entity.Category>,
    currentId: Long?,
    onPick: (Long) -> Unit,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                if (categories.isEmpty()) {
                    Text(
                        text = "No categories yet. Add one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(cat.id) }
                                .padding(vertical = Dimens.SpaceXs),
                        ) {
                            Text(text = cat.emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(Dimens.SpaceSm))
                            Text(
                                text = cat.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (cat.id == currentId) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ Add new category") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )

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
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        EMOJI_PALETTE.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                row.forEach { e ->
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
}

@Composable
private fun SourceSmsCard(rawSms: com.spendai.app.data.local.entity.RawSmsMessage?) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel("Source SMS")
            if (rawSms == null) {
                Text(
                    text = "Source message not available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = rawSms.senderAddress.ifBlank { "(unknown sender)" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = formatFullTimestamp(rawSms.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = rawSms.msgBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TitleCard(
    title: String,
    onTitleChange: (String) -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Title")
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatusRow(status: String, confidence: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
    ) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text("Auto-committed") },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        )
        Text(
            text = "Model confidence: ${"%.2f".format(confidence)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmountAndDirectionCard(
    amountText: String,
    onAmountChange: (String) -> Unit,
    direction: TransactionDirection,
    onDirectionChange: (TransactionDirection) -> Unit,
    currency: String,
    onCurrencyChange: (String) -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Amount")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountChange,
                    label = { Text("Amount") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = onCurrencyChange,
                    label = { Text("Currency") },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                )
            }
            SectionLabel("Direction")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(TransactionDirection.DEBIT, TransactionDirection.CREDIT)
                options.forEachIndexed { index, d ->
                    SegmentedButton(
                        selected = direction == d,
                        onClick = { onDirectionChange(d) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) {
                        Text(d.name)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryCard(
    currentCategoryId: Long?,
    allCategories: List<com.spendai.app.data.local.entity.Category>,
    onClick: () -> Unit,
) {
    val current = allCategories.firstOrNull { it.id == currentCategoryId }
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Category")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(vertical = Dimens.SpaceXs),
            ) {
                Text(
                    text = current?.emoji ?: "\u2014",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(Dimens.SpaceSm))
                Text(
                    text = current?.name ?: "Not set",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MerchantCard(
    allMerchants: List<com.spendai.app.data.local.entity.Merchant>,
    selectedMerchantId: Long?,
    onPick: (Long?) -> Unit,
    creatingMerchant: Boolean,
    onStartCreating: () -> Unit,
    newMerchantName: String,
    onNewNameChange: (String) -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Merchant")
            var expanded by remember { mutableStateOf(false) }
            val currentLabel = when {
                creatingMerchant -> "Create new…"
                selectedMerchantId == null -> "None"
                else -> allMerchants.firstOrNull { it.id == selectedMerchantId }?.name
                    ?: "Unknown (#$selectedMerchantId)"
            }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Merchant") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = { onPick(null); expanded = false },
                    )
                    allMerchants.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.name) },
                            onClick = { onPick(m.id); expanded = false },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Create new…") },
                        onClick = { onStartCreating(); expanded = false },
                    )
                }
            }
            if (creatingMerchant) {
                OutlinedTextField(
                    value = newMerchantName,
                    onValueChange = onNewNameChange,
                    label = { Text("New merchant name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountCard(
    allAccounts: List<com.spendai.app.data.local.entity.Account>,
    selectedAccountId: Long,
    onPick: (Long) -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Account")
            var expanded by remember { mutableStateOf(false) }
            val currentLabel = allAccounts.firstOrNull { it.id == selectedAccountId }?.let {
                "${it.issuer} ${it.maskedNumber}"
            } ?: "Unknown (#$selectedAccountId)"
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Account") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    allAccounts.forEach { a ->
                        DropdownMenuItem(
                            text = { Text("${a.issuer} ${a.maskedNumber}") },
                            onClick = { onPick(a.id); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsCard(
    channel: String?,
    onChannelChange: (String?) -> Unit,
    referenceNo: String,
    onReferenceNoChange: (String) -> Unit,
    notes: String,
    onNotesChange: (String) -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Details")
            var channelExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = channelExpanded,
                onExpandedChange = { channelExpanded = it },
            ) {
                OutlinedTextField(
                    value = channel ?: "Unknown",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Channel") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                DropdownMenu(
                    expanded = channelExpanded,
                    onDismissRequest = { channelExpanded = false },
                ) {
                    CHANNEL_OPTIONS.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c ?: "Unknown") },
                            onClick = { onChannelChange(c); channelExpanded = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = referenceNo,
                onValueChange = onReferenceNoChange,
                label = { Text("Reference / UPI txn id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = onNotesChange,
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatFullTimestamp(timestamp: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(timestamp))
}

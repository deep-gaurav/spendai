package com.spendai.app.ui.transactions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.domain.model.TransactionListItem
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = viewModel(),
    onTransactionClick: (Long) -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var sourceSheetFor by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
    ) {
        Text(
            text = stringResource(R.string.transactions_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (ui.hasAnyTransactions) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.transactions_search_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.transactions_search_clear))
                        }
                    }
                },
            )
        }
        if (ui.grouped.isEmpty()) {
            StickerCard {
                Text(
                    text = if (ui.query.isBlank()) {
                        stringResource(R.string.transactions_empty)
                    } else {
                        stringResource(R.string.transactions_search_empty)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            ) {
                items(ui.grouped, key = { it.day.toEpochDay() }) { group ->
                    DayGroupCard(
                        group = group,
                        onTransactionClick = onTransactionClick,
                        onSourceClick = { txnId -> sourceSheetFor = txnId },
                    )
                }
            }
        }
    }

    if (sourceSheetFor != null) {
        val txnId = sourceSheetFor!!
        val row = ui.grouped.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.details.id == txnId }
        if (row != null) {
            SourceSmsSheet(
                rawSmsId = row.details.rawSmsId,
                onDismiss = { sourceSheetFor = null },
            )
        } else {
            sourceSheetFor = null
        }
    }
}

@Composable
private fun DayGroupCard(
    group: DayGroup,
    onTransactionClick: (Long) -> Unit,
    onSourceClick: (Long) -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel(group.day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
            group.items.forEach { item ->
                TxnRow(
                    item = item,
                    onClick = { onTransactionClick(item.details.id) },
                    onSourceClick = { onSourceClick(item.details.id) },
                )
            }
        }
    }
}

@Composable
private fun TxnRow(
    item: TransactionListItem,
    onClick: () -> Unit,
    onSourceClick: () -> Unit,
) {
    val directionSymbol = when (item.details.direction) {
        TransactionDirection.DEBIT.name -> "-"
        TransactionDirection.CREDIT.name -> "+"
        else -> ""
    }
    val amountText = formatAmount(item.details.amountPaise)
    val tint = when (item.details.direction) {
        TransactionDirection.DEBIT.name -> MaterialTheme.colorScheme.error
        TransactionDirection.CREDIT.name -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val emoji = item.details.categoryEmoji ?: "\uD83D\uDCB8"
    val accountColor = parseHexColor(item.details.accountColorHex)
        ?: MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
    ) {
        // Account color bar
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accountColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(Dimens.SpaceXs))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Dimens.SpaceXs))
                Text(
                    text = item.accountShort,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "\uD83D\uDCEC source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSourceClick() },
            )
        }
        Text(
            text = stringResource(
                R.string.transactions_amount_format,
                directionSymbol,
                amountText,
                item.details.currency,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSmsSheet(
    rawSmsId: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var rawSms by remember { mutableStateOf<RawSmsMessage?>(null) }
    var notFound by remember { mutableStateOf(false) }
    LaunchedEffect(rawSmsId) {
        val app = context.applicationContext as SpendAiApp
        rawSms = withContext(Dispatchers.IO) { app.smsRepository.getById(rawSmsId) }
        if (rawSms == null) notFound = true
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            SectionLabel("Source SMS")
            if (notFound) {
                Text(
                    text = "Source message not found (it may have been deleted).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else rawSms?.let { msg ->
                Text(
                    text = msg.senderAddress.ifBlank { "(unknown sender)" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = formatFullTimestamp(msg.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Dimens.SpaceXs))
                Text(
                    text = msg.msgBody,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(Dimens.SpaceMd))
        }
    }
}

private fun formatAmount(paise: Long): String {
    val negative = paise < 0
    val abs = if (paise < 0) -paise else paise
    val rupees = abs / 100
    val p = abs % 100
    val whole = "%,d".format(rupees)
    val sign = if (negative) "-" else ""
    return "$sign$whole.${p.toString().padStart(2, '0')}"
}

private fun formatFullTimestamp(timestamp: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(timestamp))
}

private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
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

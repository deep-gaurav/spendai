package com.spendai.app.ui.tracking

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.domain.model.TransactionListItem
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Transactions for a single month picked on [TrackingScreen],
 * grouped by day (mirrors [com.spendai.app.ui.transactions.TransactionsScreen]'s
 * layout so the two screens read as one visual language).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingMonthDetailScreen(
    yearMonth: String,
    viewModel: TrackingViewModel = viewModel(),
    onBack: () -> Unit = {},
    onTransactionClick: (Long) -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val ym = remember(yearMonth) { runCatching { YearMonth.parse(yearMonth) }.getOrNull() }
    val group = ui.months.firstOrNull { it.yearMonth == ym }
    val zone = remember { ZoneId.systemDefault() }
    val dayGroups = remember(group) {
        group?.items
            ?.groupBy { Instant.ofEpochMilli(it.details.txnAtMillis).atZone(zone).toLocalDate() }
            ?.toSortedMap(compareByDescending { it })
            ?.map { (day, items) -> day to items }
            ?: emptyList()
    }
    val title = ym?.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())) ?: yearMonth

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        ) {
            if (dayGroups.isEmpty()) {
                StickerCard {
                    Text(
                        text = stringResource(R.string.transactions_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                    items(dayGroups, key = { it.first.toEpochDay() }) { (day, items) ->
                        StickerCard {
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                SectionLabel(day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
                                items.forEach { item ->
                                    MonthTxnRow(item = item, onClick = { onTransactionClick(item.details.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthTxnRow(
    item: TransactionListItem,
    onClick: () -> Unit,
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
    val emoji = item.details.categoryEmoji ?: "💸"
    val accountColor = parseHexColor(item.details.accountColorHex) ?: MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accountColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = emoji, style = MaterialTheme.typography.titleMedium)
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

private fun formatAmount(paise: Long): String {
    val negative = paise < 0
    val abs = if (paise < 0) -paise else paise
    val rupees = abs / 100
    val p = abs % 100
    val whole = "%,d".format(rupees)
    val sign = if (negative) "-" else ""
    return "$sign$whole.${p.toString().padStart(2, '0')}"
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

package com.spendai.app.ui.transactions

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import java.time.format.DateTimeFormatter

@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
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
        if (ui.grouped.isEmpty()) {
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            ) {
                items(ui.grouped, key = { it.day.toEpochDay() }) { group ->
                    DayGroupCard(group)
                }
            }
        }
    }
}

@Composable
private fun DayGroupCard(group: DayGroup) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel(group.day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
            group.transactions.forEach { txn ->
                TxnRow(txn)
            }
        }
    }
}

@Composable
private fun TxnRow(txn: Transaction) {
    val directionSymbol = when (txn.direction) {
        TransactionDirection.DEBIT.name -> "-"
        TransactionDirection.CREDIT.name -> "+"
        else -> ""
    }
    val amountText = formatAmount(txn.amountPaise)
    val tint = when (txn.direction) {
        TransactionDirection.DEBIT.name -> MaterialTheme.colorScheme.error
        TransactionDirection.CREDIT.name -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = txn.channel ?: "unknown",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "#${txn.id} · ${txn.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(Dimens.SpaceXs))
        Text(
            text = stringResource(
                R.string.transactions_amount_format,
                directionSymbol,
                amountText,
                txn.currency,
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

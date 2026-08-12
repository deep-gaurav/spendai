package com.spendai.app.domain.model

import com.spendai.app.data.local.dao.TransactionDetailsRow
import com.spendai.app.data.local.entity.TransactionDirection
import java.time.Instant
import java.time.ZoneId

/**
 * Display row for a transaction list. Combines the joined
 * [TransactionDetailsRow] with a render-time title, so the
 * Transactions and Tracking screens (day-grouped and
 * month-grouped respectively) share one derivation instead of
 * two copies drifting apart.
 */
data class TransactionListItem(
    val details: TransactionDetailsRow,
    val title: String,
    val timeText: String,
    val accountShort: String,
)

/**
 * Derives a [TransactionListItem] from the raw joined row: picks
 * a display title (explicit title, or one derived from
 * merchant/category/direction/channel), formats the local time,
 * and builds a short "issuer ••1234" account label.
 */
fun TransactionDetailsRow.toListItem(zone: ZoneId): TransactionListItem {
    val direction = runCatching {
        TransactionDirection.valueOf(direction)
    }.getOrDefault(TransactionDirection.DEBIT)
    val title = title?.takeIf { it.isNotBlank() }
        ?: TransactionTitle.derive(merchantName, categoryName, direction, channel)
    val time = Instant.ofEpochMilli(txnAtMillis).atZone(zone).toLocalTime()
    val timeText = "%02d:%02d".format(time.hour, time.minute)
    val accountShort = buildString {
        if (!accountIssuer.isNullOrBlank()) append(accountIssuer)
        if (!accountMaskedNumber.isNullOrBlank()) {
            if (isNotEmpty()) append(' ')
            append("••")
            append(accountMaskedNumber.takeLast(4))
        }
        if (isEmpty()) append("Account")
    }
    return TransactionListItem(
        details = this,
        title = title,
        timeText = timeText,
        accountShort = accountShort,
    )
}

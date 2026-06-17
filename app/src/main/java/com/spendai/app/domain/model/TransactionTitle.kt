package com.spendai.app.domain.model

import com.spendai.app.data.local.entity.TransactionDirection

/**
 * Pure-Kotlin fallback for the transaction title shown in the UI.
 *
 * The LLM is asked for `title` first (see `A2_SYSTEM_INSTRUCTION`);
 * when the model returns null/blank — or when the LLM is being
 * conservative about a borderline parse — this helper produces a
 * deterministic, non-empty title from the local context. The user
 * never sees "unknown" for a transaction title.
 *
 * Examples (assuming direction=DEBIT unless noted):
 *  - ("Zomato", "Food", DEBIT, "UPI")        -> "Zomato \u00B7 Food"
 *  - ("Zomato", "Food", DEBIT, null)         -> "Zomato \u00B7 Food"
 *  - (null, "Salary", CREDIT, "NEFT")        -> "Salary"
 *  - (null, "Transfer", DEBIT, "IMPS")       -> "Bank transfer"
 *  - (null, null, DEBIT, "CARD")             -> "Card transaction"
 *  - (null, null, DEBIT, null)               -> "Bank transaction"
 */
object TransactionTitle {

    fun derive(
        merchantName: String?,
        categoryName: String?,
        direction: TransactionDirection,
        channel: String?,
    ): String {
        val merchant = merchantName?.trim()?.takeIf { it.isNotEmpty() }
        val category = categoryName?.trim()?.takeIf { it.isNotEmpty() }
        val canonicalCategory = canonicalize(category, direction)

        return when {
            merchant != null && canonicalCategory != null && merchant.equals(canonicalCategory, ignoreCase = true) ->
                merchant
            merchant != null && canonicalCategory != null ->
                "$merchant \u00B7 $canonicalCategory"
            merchant != null ->
                merchant
            canonicalCategory != null ->
                canonicalCategory
            else -> channelFallback(channel, direction)
        }
    }

    private fun canonicalize(category: String?, direction: TransactionDirection): String? {
        if (category == null) return null
        val lower = category.lowercase()
        // Treat the common "income" categories as a generic "Salary"
        // for credit transactions so the title is short.
        return when {
            direction == TransactionDirection.CREDIT &&
                (lower == "salary" || lower == "income" || lower == "credit") -> "Salary"
            lower == "transfer" -> null // handled by channelFallback below
            else -> category
        }
    }

    private fun channelFallback(channel: String?, direction: TransactionDirection): String {
        val ch = channel?.uppercase()
        return when {
            ch == "ATM" -> "ATM withdrawal"
            ch == "CARD" -> "Card transaction"
            ch == "UPI" -> "UPI transaction"
            ch == "NETBANKING" -> "Netbanking"
            ch == "NEFT" || ch == "IMPS" -> "Bank transfer"
            ch == "WALLET" -> "Wallet transaction"
            direction == TransactionDirection.CREDIT -> "Money received"
            else -> "Bank transaction"
        }
    }
}

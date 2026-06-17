package com.spendai.app.domain.model

import com.spendai.app.data.local.entity.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the render-time fallback title when the LLM doesn't
 * emit one. The user never sees "unknown" for a transaction title.
 */
class TransactionTitleTest {

    @Test
    fun `merchant plus category yields Merchant middot Category`() {
        val title = TransactionTitle.derive("Zomato", "Food", TransactionDirection.DEBIT, "UPI")
        assertEquals("Zomato \u00B7 Food", title)
    }

    @Test
    fun `merchant alone yields the merchant name`() {
        val title = TransactionTitle.derive("Zomato", null, TransactionDirection.DEBIT, "UPI")
        assertEquals("Zomato", title)
    }

    @Test
    fun `no merchant and no category yields a channel fallback`() {
        val title = TransactionTitle.derive(null, null, TransactionDirection.DEBIT, "CARD")
        assertEquals("Card transaction", title)
    }

    @Test
    fun `transfer channel never has category middot in the title`() {
        // The LLM is allowed to emit "Transfer" as a category, but
        // the helper should suppress it (we use the channel fallback
        // for a clearer user-facing label).
        val title = TransactionTitle.derive(null, "Transfer", TransactionDirection.DEBIT, "IMPS")
        assertEquals("Bank transfer", title)
    }

    @Test
    fun `credit income canonicalises to Salary`() {
        val title = TransactionTitle.derive(null, "Income", TransactionDirection.CREDIT, "NEFT")
        assertEquals("Salary", title)
    }

    @Test
    fun `always returns a non-blank string`() {
        val combos = listOf(
            Triple("X", "Y", TransactionDirection.DEBIT),
            Triple(null, "Y", TransactionDirection.DEBIT),
            Triple("X", null, TransactionDirection.DEBIT),
            Triple(null, null, TransactionDirection.DEBIT),
            Triple(null, null, TransactionDirection.CREDIT),
        )
        for ((m, c, d) in combos) {
            val title = TransactionTitle.derive(m, c, d, null)
            assertTrue("expected non-blank for $m/$c/$d, got '$title'", title.isNotBlank())
        }
    }
}

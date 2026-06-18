package com.spendai.app.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the [AgentPrompt.a3UserMessage] overload that injects
 * manual corrections and an override prompt into the A3 user
 * message. We assert the order of sections and the formatting of
 * each correction row because the LLM is sensitive to the exact
 * shape of the input -- changing the header wording or the field
 * order is a contract change.
 */
class AgentPromptManualCorrectionTest {

    @Test
    fun `override appears at the top, then corrections, then context`() {
        val recents = listOf(
            AgentPrompt.A3ContextTransaction(
                id = 1, rawSmsText = "raw", amountPaise = 100, direction = "DEBIT",
                accountId = 1, accountLabel = "test", merchantName = null,
                referenceNo = null, title = null,
            ),
        )
        val candidate = AgentPrompt.A3CandidateInfo(
            rawSmsText = "cand", amountPaise = 100, direction = "DEBIT",
            accountId = 1, accountLabel = "test", merchantName = null,
            referenceNo = null, title = null,
        )
        val corrections = listOf(
            AgentPrompt.ManualCorrectionRow(
                rawSmsId = 9,
                linkedSmsIds = listOf(11L, 12L),
                userPrompt = "treat credit as transfer not duplicate",
                timestampLabel = "2026-06-18 10:00",
            ),
        )
        val out = AgentPrompt.a3UserMessage(
            recent = recents,
            candidate = candidate,
            manualCorrections = corrections,
            overridePrompt = "ignore credit card",
        )
        val overrideIdx = out.indexOf("## Override for this run")
        val correctionsIdx = out.indexOf("## Manual corrections")
        val contextIdx = out.indexOf("Recent Transactions")
        assertTrue("override should come first", overrideIdx >= 0)
        assertTrue("corrections should come after override", overrideIdx < correctionsIdx)
        assertTrue("context should come last", correctionsIdx < contextIdx)
        // Correction line shape
        assertTrue(
            "correction should include the linked ids",
            out.contains("(rawSmsId=9, linked=[11,12])"),
        )
        assertTrue(
            "correction should include the prompt text verbatim",
            out.contains("treat credit as transfer not duplicate"),
        )
    }

    @Test
    fun `no override, no corrections still produces a valid message`() {
        val out = AgentPrompt.a3UserMessage(
            recent = emptyList(),
            candidate = AgentPrompt.A3CandidateInfo(
                rawSmsText = "c", amountPaise = 0, direction = "DEBIT",
                accountId = 1, accountLabel = "x", merchantName = null,
                referenceNo = null, title = null,
            ),
            manualCorrections = emptyList(),
            overridePrompt = null,
        )
        assertTrue(out.startsWith("Recent Transactions"))
        assertTrue(!out.contains("## Override"))
        assertTrue(!out.contains("## Manual corrections"))
    }

    @Test
    fun `empty override is treated as no override`() {
        val out = AgentPrompt.a3UserMessage(
            recent = emptyList(),
            candidate = AgentPrompt.A3CandidateInfo(
                rawSmsText = "c", amountPaise = 0, direction = "DEBIT",
                accountId = 1, accountLabel = "x", merchantName = null,
                referenceNo = null, title = null,
            ),
            manualCorrections = emptyList(),
            overridePrompt = "   ",
        )
        assertTrue(!out.contains("## Override"))
    }
}

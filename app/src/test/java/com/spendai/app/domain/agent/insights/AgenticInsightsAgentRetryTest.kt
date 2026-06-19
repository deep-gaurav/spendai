package com.spendai.app.domain.agent.insights

import com.spendai.app.inference.ChatMessage
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM unit tests for the parse-failure retry loop in
 * [AgenticInsightsAgent]. The orchestrator is exercised with
 * a scripted [GemmaInferenceEngine] mock and no-op
 * [SqlExecutor] / [AnswerVerifier] fakes so we can assert
 * that:
 *
 *  - A parse-failure nudge is forwarded to the model as a
 *    `user` turn on the retry (regression test for the
 *    "dropped nudge" bug: previously the nudge was added as
 *    a [AgenticInsightsMessage.SystemMessage] which
 *    `buildChatHistory` explicitly dropped).
 *  - The orchestrator gives up gracefully after
 *    [AgenticInsightsAgent.MAX_PARSE_RETRIES] = 2 retries.
 *  - The happy path is unchanged: a single valid answer
 *    produces no nudge, no give-up notice, and one engine
 *    call.
 */
class AgenticInsightsAgentRetryTest {

    private val validAnswerJson =
        """{"action":"answer","thought":"hi","text":"hello"}"""

    @Test
    fun `parse failure nudge is forwarded to the model and recovers on the retry`() = runBlocking {
        val (agent, captured) = newAgent(scriptedReplies = listOf("", validAnswerJson))
        agent.sendMessage("what did I spend?")
        waitForIdle(agent)

        // Two engine calls: the failed first attempt and the
        // successful retry.
        assertEquals(
            "Engine should have been called twice (failed first attempt + recovery)",
            2,
            captured.size,
        )

        // The second call's history must include the nudge
        // as a `user` turn. This is the regression assertion
        // for the dropped-nudge bug.
        val secondCall = captured[1]
        val retryTurn = secondCall.lastOrNull { it.role == ChatMessage.ROLE_USER }
        assertNotNull("Second engine call must include a user turn", retryTurn)
        assertTrue(
            "Retry user turn should be the nudge: was '${retryTurn?.content}'",
            retryTurn!!.content.startsWith("Parser retry:"),
        )

        // The first call's history is the system prompt +
        // the user's question; no nudge, no prior assistant
        // turns.
        val firstCall = captured[0]
        assertEquals(2, firstCall.size) // system + user
        assertEquals(ChatMessage.ROLE_SYSTEM, firstCall[0].role)
        assertEquals(ChatMessage.ROLE_USER, firstCall[1].role)

        // The chat ended with the parsed answer.
        val lastAssistant = agent.messages.value
            .lastOrNull { it is AgenticInsightsMessage.AssistantMessage }
            as? AgenticInsightsMessage.AssistantMessage
        assertNotNull("Last assistant message should be present", lastAssistant)
        val parsed = lastAssistant?.parsed
        val answer = parsed as? AgenticAction.Answer
        assertNotNull("Last assistant should be an Answer, was ${parsed?.javaClass?.simpleName}", answer)
        assertEquals("hello", answer!!.text)

        // No give-up SystemMessage was appended: the retry
        // recovered.
        assertFalse(
            "Give-up SystemMessage should not have been appended",
            agent.messages.value.any { it is AgenticInsightsMessage.SystemMessage &&
                it.text.contains("could not parse") },
        )

        // The InternalNudge IS in the chat list (it is a
        // user-visible bubble).
        assertTrue(
            "InternalNudge should be in the chat list",
            agent.messages.value.any { it is AgenticInsightsMessage.InternalNudge },
        )
    }

    @Test
    fun `orchestrator gives up after MAX_PARSE_RETRIES`() = runBlocking {
        val (agent, captured) = newAgent(scriptedReplies = listOf("", "", ""))
        agent.sendMessage("what?")
        waitForIdle(agent)

        // Three engine calls: 1 original + 2 retries, then
        // give-up. The give-up itself does not call the
        // engine.
        assertEquals(3, captured.size)

        // Each retry must have surfaced a `Parser retry:`
        // user turn. The original first call had no nudge.
        // Calls 2 and 3 are the two retries.
        for (retryIdx in listOf(1, 2)) {
            val retryCall = captured[retryIdx]
            val hasRetryTurn = retryCall.any {
                it.role == ChatMessage.ROLE_USER && it.content.startsWith("Parser retry:")
            }
            assertTrue(
                "Engine call ${retryIdx + 1} should include a Parser retry user turn",
                hasRetryTurn,
            )
        }

        // The give-up SystemMessage is appended.
        val giveUp = agent.messages.value
            .filterIsInstance<AgenticInsightsMessage.SystemMessage>()
            .firstOrNull { it.text.contains("could not parse") }
        assertNotNull("Give-up SystemMessage should have been appended", giveUp)

        // The give-up is NOT a model turn, so it is NOT in
        // any engine call's history.
        for ((idx, call) in captured.withIndex()) {
            assertFalse(
                "Engine call ${idx + 1} must not contain the give-up SystemMessage",
                call.any { it.role == ChatMessage.ROLE_SYSTEM && it.content.contains("could not parse") },
            )
        }

        // Two InternalNudges were emitted (one per retry).
        val nudgeCount = agent.messages.value
            .count { it is AgenticInsightsMessage.InternalNudge }
        assertEquals(2, nudgeCount)

        assertEquals(AgenticStatus.Idle, agent.status.value)
    }

    @Test
    fun `happy path is unchanged - single answer produces no nudges or system notices`() = runBlocking {
        val (agent, captured) = newAgent(scriptedReplies = listOf(validAnswerJson))
        agent.sendMessage("hi")
        waitForIdle(agent)

        assertEquals(1, captured.size)
        assertFalse(
            "No nudge should be sent on the happy path",
            captured[0].any { it.role == ChatMessage.ROLE_USER && it.content.startsWith("Parser retry:") },
        )
        assertFalse(
            "No SystemMessage should be appended on the happy path",
            agent.messages.value.any { it is AgenticInsightsMessage.SystemMessage },
        )
        assertFalse(
            "No InternalNudge should be appended on the happy path",
            agent.messages.value.any { it is AgenticInsightsMessage.InternalNudge },
        )

        val lastAssistant = agent.messages.value
            .lastOrNull { it is AgenticInsightsMessage.AssistantMessage }
            as? AgenticInsightsMessage.AssistantMessage
        val answer = lastAssistant?.parsed as? AgenticAction.Answer
        assertNotNull(answer)
        assertEquals("hello", answer!!.text)
    }

    // ----- helpers -----

    private fun newAgent(
        scriptedReplies: List<String>,
    ): Pair<AgenticInsightsAgent, MutableList<List<ChatMessage>>> {
        val captured = mutableListOf<List<ChatMessage>>()
        val replyIdx = AtomicInteger(0)

        val engine = mockk<GemmaInferenceEngine>()
        val state = MutableStateFlow<InferenceState>(InferenceState.Ready("test"))
        every { engine.state } returns state
        // Match the call the orchestrator makes: named args (messages, stepLabel).
        every { engine.generateChatTracking(messages = any(), stepLabel = any()) } answers {
            val messages = firstArg<List<ChatMessage>>()
            captured.add(messages)
            val idx = replyIdx.getAndIncrement()
            val reply = scriptedReplies.getOrNull(idx) ?: ""
            flowOf(reply)
        }
        coEvery { engine.cancelCurrent() } returns Unit

        val sqlExecutor = mockk<SqlExecutor>(relaxed = true)
        val verifier = mockk<AnswerVerifier>(relaxed = true)

        val agent = AgenticInsightsAgent(
            engine = engine,
            sqlExecutor = sqlExecutor,
            verifier = verifier,
            nowMillis = { 1000L },
        )
        return agent to captured
    }

    private suspend fun waitForIdle(agent: AgenticInsightsAgent, timeoutMs: Long = 5_000L) {
        withTimeout(timeoutMs) {
            // The orchestrator runs on Dispatchers.IO. The test
            // thread is the runBlocking main thread, so by the
            // time we get here the IO coroutine may not have
            // launched yet (and status is still the default Idle).
            // Wait for the orchestrator to flip the status to
            // Thinking (i.e. it has entered the turn loop and is
            // about to call the engine), then wait for it to
            // flip back to Idle (the turn completed).
            val start = System.currentTimeMillis()
            while (agent.status.value !is AgenticStatus.Thinking &&
                agent.messages.value.none { it is AgenticInsightsMessage.AssistantMessage }
            ) {
                if (System.currentTimeMillis() - start > timeoutMs) {
                    throw AssertionError(
                        "Orchestrator never started; status=${agent.status.value} " +
                            "messages=${agent.messages.value.size}",
                    )
                }
                delay(5)
            }
            while (agent.status.value !is AgenticStatus.Idle) {
                if (System.currentTimeMillis() - start > timeoutMs) {
                    throw AssertionError(
                        "Orchestrator did not finish; status=${agent.status.value} " +
                            "messages=${agent.messages.value.size}",
                    )
                }
                delay(5)
            }
        }
    }
}

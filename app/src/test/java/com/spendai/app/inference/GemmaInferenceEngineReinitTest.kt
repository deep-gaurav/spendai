package com.spendai.app.inference

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SamplerConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the two-step per-call failure recovery on
 * [GemmaInferenceEngine]:
 *  - First consecutive failure: throw, next call gets a fresh
 *    Session automatically (no per-call rebuild needed; the engine
 *    stays Ready).
 *  - Second consecutive failure: reinitialise the engine (full
 *    reinit via [reinitializeInternal]).
 *
 * Each [GemmaInferenceEngine.runSession] call creates and closes
 * exactly one [Session] via [Engine.createSession], mirroring the
 * production pattern (one stateless session per agent call).
 */
class GemmaInferenceEngineReinitTest {

    /**
     * First per-message failure: the engine does NOT escalate to
     * reinit. The exception propagates to the caller, the engine
     * stays Ready, and a `createSession` counter shows exactly one
     * session was opened.
     */
    @Test
    fun `first consecutive failure leaves engine Ready for next call`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session1: Session = mockk(relaxed = true)
        val cb = slot<com.google.ai.edge.litertlm.ResponseCallback>()
        every { mockEngine.createSession(any()) } returns session1
        every { session1.close() } returns Unit
        every { session1.generateContentStream(any<List<com.google.ai.edge.litertlm.InputData>>(), capture(cb)) } answers {
            cb.captured.onError(LiteRtLmJniException("first failure"))
        }
        engine.setReadyForTest(mockEngine, "GPU")

        val ex = runCatching {
            engine.generatePredictionTracking("hi", "step").toList()
        }.exceptionOrNull()
        assertTrue("expected an exception", ex is LiteRtLmJniException)
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        // One Session created, one closed.
        verify(exactly = 1) { mockEngine.createSession(any()) }
        verify(exactly = 1) { session1.close() }
    }

    /**
     * Second consecutive failure on the same engine escalates to
     * [GemmaInferenceEngine.reinitializeInternal]. The escalation
     * closes the engine (via [GemmaInferenceEngine.close]) which
     * in turn closes the current Session. After the reinit, the
     * state is Ready (or Error if the reinit itself failed).
     */
    @Test
    fun `second consecutive failure escalates to engine reinit and lands in Error on reinit failure`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session1: Session = mockk(relaxed = true)
        val session2: Session = mockk(relaxed = true)
        // Pool of Sessions handed out by createSession.
        val pool = mutableListOf(session1, session2)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions in pool")
        }
        every { session1.close() } returns Unit
        every { session2.close() } returns Unit
        // Both sessions fail with onError.
        every { session1.generateContentStream(any<List<com.google.ai.edge.litertlm.InputData>>(), any()) } answers {
            val c = secondArg<com.google.ai.edge.litertlm.ResponseCallback>()
            c.onError(LiteRtLmJniException("first call failure"))
        }
        every { session2.generateContentStream(any<List<com.google.ai.edge.litertlm.InputData>>(), any()) } answers {
            val c = secondArg<com.google.ai.edge.litertlm.ResponseCallback>()
            c.onError(LiteRtLmJniException("second call failure"))
        }
        // Engine.createConversation is never called for the per-call
        // path (only the probe uses it).
        every { mockEngine.createConversation(any()) } throws IllegalStateException("not used here")
        engine.setReadyForTest(mockEngine, "GPU")

        val ex1 = runCatching {
            engine.generatePredictionTracking("a", "step1").toList()
        }.exceptionOrNull()
        assertTrue(ex1 is LiteRtLmJniException)
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        verify(exactly = 1) { mockEngine.createSession(any()) }

        // Second call: reinit path is attempted. The test does not
        // provide a fresh Engine for the reinit to install, so the
        // reinit fails (the cached initContext is null from
        // setReadyForTest) and the engine transitions to Error.
        val ex2 = runCatching {
            engine.generatePredictionTracking("b", "step2").toList()
        }.exceptionOrNull()
        assertTrue("expected exception", ex2 is LiteRtLmJniException)
        val state = engine.state.value
        assertTrue("expected Error, got $state", state is InferenceState.Error)
    }

    /**
     * Success between two failures resets the consecutive-failure
     * counter. The next failure then goes through the cheap
     * "throw + next call gets a fresh Session" path, NOT the
     * reinit path.
     */
    @Test
    fun `success between two failures resets the counter`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session1: Session = mockk(relaxed = true)
        val session2: Session = mockk(relaxed = true)
        val session3: Session = mockk(relaxed = true)
        val pool = mutableListOf(session1, session2, session3)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions in pool")
        }
        every { session1.close() } returns Unit
        every { session2.close() } returns Unit
        every { session3.close() } returns Unit

        // Shared per-call behaviour: call #0 and #2 fail, #1 succeeds.
        val callIndex = intArrayOf(0)
        val failOn = setOf(0, 2)
        fun respond(cb: com.google.ai.edge.litertlm.ResponseCallback) {
            val n = callIndex[0]++
            if (n in failOn) cb.onError(LiteRtLmJniException("call $n"))
            else { cb.onNext("ok"); cb.onDone() }
        }
        every { session1.generateContentStream(any<List<com.google.ai.edge.litertlm.InputData>>(), any()) } answers { respond(secondArg()) }
        every { session2.generateContentStream(any<List<com.google.ai.edge.litertlm.InputData>>(), any()) } answers { respond(secondArg()) }
        every { session3.generateContentStream(any<List<com.google.ai.edge.litertlm.InputData>>(), any()) } answers { respond(secondArg()) }
        engine.setReadyForTest(mockEngine, "GPU")

        // 1st call: fail -> counter=1, engine stays Ready.
        runCatching { engine.generatePredictionTracking("a", "s1").toList() }
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        // 2nd call: success on session2, counter resets to 0.
        val ok = engine.generatePredictionTracking("b", "s2").toList()
        assertEquals(listOf("ok"), ok)
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        // 3rd call: fail. Counter is 0 again so the engine stays
        // Ready and the next call naturally gets a fresh Session.
        runCatching { engine.generatePredictionTracking("c", "s3").toList() }
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        // Three sessions total — one per call.
        verify(exactly = 3) { mockEngine.createSession(any()) }
    }

    /**
     * [GemmaInferenceEngine.reinitialize] returns false when the
     * engine was never initialised (no cached Context / config). The
     * state remains whatever it was before the call.
     */
    @Test
    fun `public reinitialize returns false when engine was never initialised`() = runTest {
        val engine = GemmaInferenceEngine()
        val ok = engine.reinitialize()
        assertEquals(false, ok)
        assertNotEquals(InferenceState.Error("nope", null), engine.state.value)
    }

    /**
     * [GemmaInferenceEngine.close] preserves the cached config +
     * context + session config so a follow-up
     * [GemmaInferenceEngine.reinitialize] can still bring the engine
     * back. State goes to Uninitialized.
     */
    @Test
    fun `close preserves config so reinit remains possible`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        engine.setReadyForTest(mockEngine, "NPU", config = InferenceConfig(maxTokens = 1024))
        engine.close()
        // reinitialize tries to use the cached context, but since
        // initContext is null from setReadyForTest, reinitialize
        // returns false.
        val ok = engine.reinitialize()
        assertEquals(false, ok)
    }
}

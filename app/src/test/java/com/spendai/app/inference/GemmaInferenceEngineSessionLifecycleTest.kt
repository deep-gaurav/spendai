package com.spendai.app.inference

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the per-call [Session] lifecycle contract. The whole
 * point of switching from a long-lived `Conversation` to a per-call
 * `Session` is that each agent call gets a fresh, stateless
 * inference slot. These tests prove the engine creates one (and
 * only one) [Session] per call, closes it on every code path, and
 * never lets a Session error from one call affect the next.
 *
 * This is the regression net against the
 * "truncated 9-char response after 5 messages" bug: with one
 * shared `Conversation` the KV cache accumulated and the model
 * started producing garbage. With a per-call `Session`, the cache
 * is empty at the start of every call.
 */
class GemmaInferenceEngineSessionLifecycleTest {

    /**
     * One call creates exactly one [Session] and closes it. The
     * engine does not retain a reference between calls.
     */
    @Test
    fun `one call creates exactly one Session and closes it`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session: Session = mockk(relaxed = true)
        every { session.close() } returns Unit
        every { session.generateContentStream(any<List<InputData>>(), any()) } answers {
            val cb = secondArg<com.google.ai.edge.litertlm.ResponseCallback>()
            cb.onNext("ok")
            cb.onDone()
        }
        every { mockEngine.createSession(any()) } returns session
        engine.setReadyForTest(mockEngine, "GPU")

        val collected = engine.generatePredictionTracking("hi", "step").toList()
        assertEquals(listOf("ok"), collected)
        verify(exactly = 1) { mockEngine.createSession(any()) }
        verify(exactly = 1) { session.close() }
    }

    /**
     * A second call creates a second [Session] (no state carryover
     * between calls). The first Session is closed before the
     * second is created.
     */
    @Test
    fun `a second call creates a second Session (no state carryover)`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session1: Session = mockk(relaxed = true)
        val session2: Session = mockk(relaxed = true)
        every { session1.close() } returns Unit
        every { session2.close() } returns Unit
        every { session1.generateContentStream(any<List<InputData>>(), any()) } answers {
            val cb = secondArg<com.google.ai.edge.litertlm.ResponseCallback>()
            cb.onNext("first")
            cb.onDone()
        }
        every { session2.generateContentStream(any<List<InputData>>(), any()) } answers {
            val cb = secondArg<com.google.ai.edge.litertlm.ResponseCallback>()
            cb.onNext("second")
            cb.onDone()
        }
        val pool = mutableListOf(session1, session2)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions")
        }
        engine.setReadyForTest(mockEngine, "GPU")

        val c1 = engine.generatePredictionTracking("a", "s1").toList()
        val c2 = engine.generatePredictionTracking("b", "s2").toList()
        assertEquals(listOf("first"), c1)
        assertEquals(listOf("second"), c2)
        // Two sessions total — one per call.
        verify(exactly = 2) { mockEngine.createSession(any()) }
        // Both closed.
        verify(exactly = 1) { session1.close() }
        verify(exactly = 1) { session2.close() }
    }

    /**
     * When a [Session] throws [LiteRtLmJniException], the engine
     * surfaces the error to the caller, closes the broken Session,
     * and stays Ready. The next call gets a brand-new Session
     * (verified by `createSession` being called again) and is
     * unaffected by the previous failure.
     *
     * This is the regression net against the old behaviour where
     * a single bad inference poisoned the whole inbox.
     */
    @Test
    fun `Session throws LiteRtLmJniException and the next call still works with a fresh Session`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val brokenSession: Session = mockk(relaxed = true)
        val freshSession: Session = mockk(relaxed = true)
        every { brokenSession.close() } returns Unit
        every { freshSession.close() } returns Unit
        every { brokenSession.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("simulated GPU failure")
            )
        }
        every { freshSession.generateContentStream(any<List<InputData>>(), any()) } answers {
            val cb = secondArg<com.google.ai.edge.litertlm.ResponseCallback>()
            cb.onNext("recovered")
            cb.onDone()
        }
        val pool = mutableListOf(brokenSession, freshSession)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions")
        }
        engine.setReadyForTest(mockEngine, "GPU")

        // First call: broken Session. Error propagates; engine
        // stays Ready; the broken Session is closed.
        val ex = runCatching {
            engine.generatePredictionTracking("first", "step1").toList()
        }.exceptionOrNull()
        assertTrue("expected an exception", ex is LiteRtLmJniException)
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        verify(exactly = 1) { brokenSession.close() }
        verify(exactly = 1) { mockEngine.createSession(any()) }

        // Second call: fresh Session, succeeds. The new Session
        // is closed afterwards.
        val ok = engine.generatePredictionTracking("second", "step2").toList()
        assertEquals(listOf("recovered"), ok)
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        verify(exactly = 1) { freshSession.close() }
        verify(exactly = 2) { mockEngine.createSession(any()) }
    }
}

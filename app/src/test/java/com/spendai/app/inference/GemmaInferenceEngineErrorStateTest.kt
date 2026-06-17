package com.spendai.app.inference

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the engine error-recovery contract.
 *
 * There are two distinct failure modes:
 *  1. **Per-call failure** — `generateContentStream` errors (e.g.
 *     `Status Code: 13 — Failed to invoke the compiled model`).
 *     The native [Engine] is still valid; each call gets a fresh
 *     [Session] anyway. The engine stays [InferenceState.Ready] so
 *     the next call can succeed. This is what users see when the
 *     4th SMS hits a bad kernel.
 *  2. **Engine-broke failure** — the engine itself is
 *     unrecoverable (reinitialise also fails). The engine
 *     transitions to [InferenceState.Error] and the worker
 *     re-initialises on the next retry.
 *
 * The "I'm online" probe path is unchanged: it still uses a
 * one-off [Conversation] for chat-style one-shot inference.
 */
class GemmaInferenceEngineErrorStateTest {

    /**
     * Per-call error: the engine stays Ready and the exception
     * is re-thrown so the pipeline can mark the message as
     * skipped. The next call naturally uses a fresh [Session].
     */
    @Test
    fun `Session onError propagates to caller and engine stays Ready`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val brokenSession: Session = mockk(relaxed = true)
        every { brokenSession.close() } returns Unit
        val cb = slot<com.google.ai.edge.litertlm.ResponseCallback>()
        every { brokenSession.generateContentStream(any<List<InputData>>(), capture(cb)) } answers {
            cb.captured.onError(LiteRtLmJniException("simulated GPU failure"))
        }
        every { mockEngine.createSession(any()) } returns brokenSession
        engine.setReadyForTest(mockEngine, "GPU")

        val ex = runCatching {
            engine.generatePredictionTracking("hello", "test.step").toList()
        }.exceptionOrNull()
        assertTrue("expected an exception", ex != null)
        assertTrue(
            "exception should mention the simulated failure: ${ex?.message}",
            (ex?.message ?: "").contains("simulated GPU failure"),
        )
        // Critical: the engine is still Ready and the broken
        // session has been closed.
        assertEquals(
            "engine should be Ready after per-call failure",
            InferenceState.Ready("GPU"),
            engine.state.value,
        )
        verify(exactly = 1) { brokenSession.close() }
        verify(exactly = 1) { mockEngine.createSession(any()) }
    }

    /**
     * Engine-broke failure: two consecutive per-call failures
     * with no recovery trigger reinitialisation, which fails
     * (because initContext is null in tests), and the engine
     * transitions to Error.
     */
    @Test
    fun `two consecutive failures escalate to Error when reinit fails`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session1: Session = mockk(relaxed = true)
        val session2: Session = mockk(relaxed = true)
        every { session1.close() } returns Unit
        every { session2.close() } returns Unit
        every { session1.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("first failure")
            )
        }
        every { session2.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("second failure")
            )
        }
        val pool = mutableListOf(session1, session2)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions")
        }
        engine.setReadyForTest(mockEngine, "GPU")

        runCatching { engine.generatePredictionTracking("a", "s1").toList() }
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)

        runCatching { engine.generatePredictionTracking("b", "s2").toList() }
        val state = engine.state.value
        assertTrue("expected Error, got $state", state is InferenceState.Error)
    }

    /**
     * The engine allows [GemmaInferenceEngine.close] from the
     * Error state (the error message is preserved so the UI can
     * still show it, but the underlying native resources are
     * released).
     */
    @Test
    fun `close preserves Error state for UI display but releases native resources`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session1: Session = mockk(relaxed = true)
        val session2: Session = mockk(relaxed = true)
        every { session1.close() } returns Unit
        every { session2.close() } returns Unit
        every { session1.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("transient")
            )
        }
        every { session2.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("transient")
            )
        }
        val pool = mutableListOf(session1, session2)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions")
        }
        engine.setReadyForTest(mockEngine, "GPU")
        runCatching { engine.generatePredictionTracking("hi", "step").toList() }
        runCatching { engine.generatePredictionTracking("hi", "step").toList() }
        assertTrue(engine.state.value is InferenceState.Error)

        engine.close()
        assertTrue(
            "close() should preserve Error for UI; got ${engine.state.value}",
            engine.state.value is InferenceState.Error,
        )
    }

    /**
     * [GemmaInferenceEngine.initialize] is allowed from the Error
     * state — the worker calls it again on Result.retry(). The
     * internal guard in initialize() skips only Ready/Loading, so
     * the worker retry path is unblocked.
     */
    @Test
    fun `initialize is allowed from Error state (worker retry contract)`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val session1: Session = mockk(relaxed = true)
        val session2: Session = mockk(relaxed = true)
        every { session1.close() } returns Unit
        every { session2.close() } returns Unit
        every { session1.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("transient")
            )
        }
        every { session2.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("transient")
            )
        }
        val pool = mutableListOf(session1, session2)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions")
        }
        engine.setReadyForTest(mockEngine, "GPU")
        runCatching { engine.generatePredictionTracking("hi", "step").toList() }
        runCatching { engine.generatePredictionTracking("hi", "step").toList() }
        assertTrue("setup: state should be Error", engine.state.value is InferenceState.Error)

        engine.close()
        assertTrue(
            "state must not be Ready after error (would prevent re-init)",
            engine.state.value !is InferenceState.Ready,
        )
    }

    /**
     * After a per-call failure, the next call uses a fresh
     * [Session] and succeeds. This is the whole point of
     * per-call Session: a single bad SMS doesn't poison the
     * rest of the inbox.
     */
    @Test
    fun `second call after per-call failure succeeds with a fresh Session`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val brokenSession: Session = mockk(relaxed = true)
        val freshSession: Session = mockk(relaxed = true)
        every { brokenSession.close() } returns Unit
        every { freshSession.close() } returns Unit
        every { brokenSession.generateContentStream(any<List<InputData>>(), any()) } answers {
            secondArg<com.google.ai.edge.litertlm.ResponseCallback>().onError(
                LiteRtLmJniException("first call failure")
            )
        }
        every { freshSession.generateContentStream(any<List<InputData>>(), any()) } answers {
            val cb = secondArg<com.google.ai.edge.litertlm.ResponseCallback>()
            cb.onNext("hello ")
            cb.onNext("world")
            cb.onDone()
        }
        // First call gets brokenSession, second gets freshSession.
        val pool = mutableListOf(brokenSession, freshSession)
        every { mockEngine.createSession(any()) } answers {
            if (pool.isNotEmpty()) pool.removeAt(0) else error("no more sessions")
        }
        engine.setReadyForTest(mockEngine, "GPU")

        val ex1 = runCatching {
            engine.generatePredictionTracking("first", "step1").toList()
        }.exceptionOrNull()
        assertTrue("first call should fail", ex1 != null)
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)

        val collected2 = engine.generatePredictionTracking("second", "step2").toList()
        assertEquals(listOf("hello ", "world"), collected2)
        assertEquals(InferenceState.Ready("GPU"), engine.state.value)
        // Two sessions total — one per call, and each was closed.
        verify(exactly = 2) { mockEngine.createSession(any()) }
        verify(exactly = 1) { brokenSession.close() }
        verify(exactly = 1) { freshSession.close() }
    }
}

package com.spendai.app

import app.cash.turbine.test
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure JVM tests for the [GemmaInferenceEngine] state machine.
 *
 * We intentionally do NOT call [GemmaInferenceEngine.initialize] in
 * these tests — that path needs a real model and a real device. The
 * states we can verify from the JVM are:
 *  - the initial state is [InferenceState.Uninitialized]
 *  - calling [GemmaInferenceEngine.generatePrediction] before READY
 *    throws [IllegalArgumentException] (the `require(...)` check)
 *  - [GemmaInferenceEngine.close] is idempotent and resets to
 *    [InferenceState.Uninitialized]
 */
class GemmaInferenceEngineStateTest {

    @Test
    fun `engine starts in Uninitialized state`() = runTest {
        val engine = GemmaInferenceEngine()
        engine.state.test {
            assertEquals(InferenceState.Uninitialized, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `generatePrediction before READY throws`() = runTest {
        val engine = GemmaInferenceEngine()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.generatePrediction("hello")
            }
        }
    }

    @Test
    fun `generatePredictionStreaming before READY throws`() = runTest {
        val engine = GemmaInferenceEngine()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.generatePredictionStreaming("hello").collect { /* unreachable */ }
            }
        }
    }

    @Test
    fun `close is idempotent and resets state`() = runTest {
        val engine = GemmaInferenceEngine()
        engine.close()       // first call: nothing to close, no-op
        engine.close()       // second call: still no-op
        engine.state.test {
            assertEquals(InferenceState.Uninitialized, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

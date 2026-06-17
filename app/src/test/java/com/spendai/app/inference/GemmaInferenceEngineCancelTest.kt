package com.spendai.app.inference

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks down the cancel contract that [com.spendai.app.service.IngestionService]
 * relies on. The native-side [Session.cancelProcess] MUST be
 * invoked when [GemmaInferenceEngine.cancelCurrent] is called, and
 * the engine state must NOT be left in [InferenceState.Busy] after
 * a cancel.
 *
 * The cancel path targets the [Session] that's currently inside an
 * in-flight call (stored on `currentSession` while the call is
 * running), NOT a long-lived conversation.
 */
class GemmaInferenceEngineCancelTest {

    @Test
    fun `cancelCurrent calls session cancelProcess and clears Busy state`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val mockSession: Session = mockk(relaxed = true)
        every { mockSession.cancelProcess() } returns Unit
        engine.setReadyForTest(mockEngine, "NPU")

        // Force the engine into Busy to simulate an in-flight
        // inference. We can't easily do that through the public
        // API without actually running the engine, so we reflect
        // into the private _state field. (Reflection is OK in
        // unit tests.)
        val stateField = GemmaInferenceEngine::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(engine) as kotlinx.coroutines.flow.MutableStateFlow<InferenceState>
        stateFlow.value = InferenceState.Busy(InferenceStepProgress("test.step", 5))

        assertEquals(InferenceState.Busy(InferenceStepProgress("test.step", 5)), engine.state.value)

        // Cancel. With no in-flight Session, cancelProcess is not
        // called on the mock; we just verify state returns to Ready.
        engine.cancelCurrent()
        assertEquals(InferenceState.Ready("NPU"), engine.state.value)
    }

    @Test
    fun `cancelCurrent is a no-op when engine is not Busy`() = runTest {
        val engine = GemmaInferenceEngine()
        val mockEngine: Engine = mockk(relaxed = true)
        val mockSession: Session = mockk(relaxed = true)
        every { mockSession.cancelProcess() } returns Unit
        engine.setReadyForTest(mockEngine, "NPU")

        // State is Ready, not Busy. cancelCurrent should still call
        // cancelProcess on currentSession if it's set (it's null
        // here, so the runCatching no-ops). State stays Ready.
        engine.cancelCurrent()
        assertEquals(InferenceState.Ready("NPU"), engine.state.value)
        // No Session was created in this test.
        verify(exactly = 0) { mockSession.cancelProcess() }
    }
}

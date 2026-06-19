package com.spendai.app.ui.insights.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.agent.insights.AgenticInsightsAgent
import com.spendai.app.domain.agent.insights.AgenticInsightsMessage
import com.spendai.app.domain.agent.insights.AgenticStatus
import com.spendai.app.domain.agent.insights.AgenticAction
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin ViewModel pass-through to the
 * [com.spendai.app.domain.agent.insights.AgenticInsightsAgent].
 *
 * The orchestrator is a long-lived singleton owned by the
 * [com.spendai.app.SpendAiApp] service locator so the same
 * conversation survives configuration changes (rotation,
 * process restart is a separate concern — not handled in v1).
 *
 * The ViewModel deliberately does not own the agent: the
 * orchestrator manages its own scope, its own state flows,
 * and its own cancellation. The ViewModel only forwards user
 * intents and exposes the orchestrator's flows to the UI.
 */
class AgenticInsightsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication()

    val agent: AgenticInsightsAgent = app.agenticInsightsAgent

    val messages: StateFlow<List<AgenticInsightsMessage>> = agent.messages
    val status: StateFlow<AgenticStatus> = agent.status
    val debugLog: StateFlow<List<com.spendai.app.domain.agent.insights.AgenticDebugEntry>> = agent.debugLog
    val debugEnabled: StateFlow<Boolean> = agent.debugEnabled

    /**
     * Verifier toggle. Defaults to OFF so the chat feels
     * snappy. The chat screen exposes a button to flip it
     * back on when the user wants the ground-truth check
     * (re-prompts the model when the answer cites facts
     * not present in the SQL result rows).
     */
    val verifierEnabled: StateFlow<Boolean> = agent.verifierEnabled

    fun sendMessage(text: String) {
        agent.sendMessage(text)
    }

    fun cancel() {
        agent.cancel()
    }

    fun clear() {
        agent.clear()
    }

    fun toggleDebug() {
        agent.toggleDebug()
    }

    fun setVerifierEnabled(enabled: Boolean) {
        agent.setVerifierEnabled(enabled)
    }

    /**
     * Render the full conversation + debug log as a plain
     * text transcript. Used by the debug panel's "Copy" button.
     */
    fun renderTranscript(): String {
        val sb = StringBuilder()
        for (msg in messages.value) {
            sb.appendLine(transcriptLine(msg))
            sb.appendLine()
        }
        sb.appendLine("--- DEBUG LOG ---")
        for (entry in debugLog.value) {
            sb.appendLine("[${entry.kind}] ${entry.title}")
            sb.appendLine(entry.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun transcriptLine(msg: AgenticInsightsMessage): String = when (msg) {
        is AgenticInsightsMessage.UserMessage -> "User: ${msg.text}"
        is AgenticInsightsMessage.VerifierMessage ->
            "Verifier (attempt ${msg.attempt}): ${msg.text}"
        is AgenticInsightsMessage.InternalNudge ->
            "Parser retry: ${msg.text}"
        is AgenticInsightsMessage.AssistantMessage -> {
            val parsed = msg.parsed
            when (parsed) {
                is AgenticAction.Answer -> "Assistant: ${parsed.text}" +
                    if (parsed.charts.isNotEmpty()) "\nCharts: ${parsed.charts.size}" else ""
                is AgenticAction.QueryDatabase -> "Assistant (tool call):\n${parsed.sql}"
                is AgenticAction.MutateMerchant -> "Assistant (mutate_merchant):\n" +
                    "match=${parsed.matchByName ?: parsed.matchById} " +
                    "setIsSelf=${parsed.setIsSelf} clearIsSelf=${parsed.clearIsSelf} " +
                    "addMetadata=${parsed.addMetadata} removeMetadata=${parsed.removeMetadata}"
                null -> "Assistant: ${msg.streamedText}"
            }
        }
        is AgenticInsightsMessage.ToolCallMessage -> "Tool call:\n${msg.sql}"
        is AgenticInsightsMessage.ToolResultMessage -> {
            if (msg.error != null) "Tool error: ${msg.error}"
            else "Tool result: ${msg.rowCount} row(s)"
        }
        is AgenticInsightsMessage.MutationToolCallMessage -> {
            val a = msg.action
            "Tool call (mutate_merchant):\n" +
                "match=${a.matchByName ?: a.matchById} " +
                "setIsSelf=${a.setIsSelf} clearIsSelf=${a.clearIsSelf} " +
                "addMetadata=${a.addMetadata} removeMetadata=${a.removeMetadata}"
        }
        is AgenticInsightsMessage.MutationToolResultMessage -> {
            val r = msg.result
            if (r.error != null) "Tool error: ${r.error}"
            else "Mutation: matched=${r.matchedMerchantName} isSelf=${r.isSelfNewValue} " +
                "added=${r.metadataAdded.size} removed=${r.metadataRemoved.size} " +
                "affected=${r.affectedTransactionIds.size} " +
                "selfLinks=${r.selfTransferLinksWritten} reprompts=${r.repromptsEnqueued}"
        }
        is AgenticInsightsMessage.SystemMessage -> "System: ${msg.text}"
    }

    override fun onCleared() {
        // We deliberately do NOT shutdown the agent here — the
        // agent is a singleton on SpendAiApp and other
        // ViewModels (or the same ViewModel on rotation) will
        // reuse it. A future "clear on app exit" hook can
        // shutdown the agent from the Application class.
        super.onCleared()
    }
}

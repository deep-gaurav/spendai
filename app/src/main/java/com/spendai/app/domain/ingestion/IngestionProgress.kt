package com.spendai.app.domain.ingestion

/**
 * One event emitted by [IngestionPipeline.run] as the pipeline moves
 * through the A1→A2 stages for a date range. The static
 * `IngestionService.progress` StateFlow is the public observation
 * surface; the UI's [com.spendai.app.ui.home.HomeViewModel] collects it
 * and the home renders the current event.
 *
 * Events are kept small and emit-once-per-step so a `progress: Flow<IngestionProgress>`
 * never grows. The terminal events ([Done], [Failure], [Cancelled]) are
 * sticky until the next ingestion starts.
 */
sealed interface IngestionProgress {
    /** No ingestion is running. The home shows the "Ingest" CTA. */
    data object Idle : IngestionProgress

    /**
     * The LLM is being initialised. This is the single longest pause
     * in an ingestion (up to 10s on first run as the model loads);
     * the home surfaces it so the user knows "it's not stuck".
     */
    data class EngineInitialising(val currentState: String) : IngestionProgress

    /** The [SmsSource] is feeding rows into the DB. */
    data class LoadingFromSource(val seenSoFar: Int) : IngestionProgress

    /** Agent 1 finished a message. */
    data class MessageParsed(
        val messageIndex: Int,
        val totalMessages: Int,
        val kind: String,
    ) : IngestionProgress

    /**
     * Agent 2 (or some downstream step) failed on this message and
     * the pipeline chose to skip it rather than abort the run. The
     * raw_sms row stays UNPARSED so a future run can retry.
     */
    data class MessageSkipped(
        val messageIndex: Int,
        val totalMessages: Int,
        val reason: String,
    ) : IngestionProgress

    /**
     * Agent 2 finished a message. The transaction is now committed
     * in the local DB; the UI can show "Rs.100 at Acme" inline.
     */
    data class MessageCommitted(
        val messageIndex: Int,
        val totalMessages: Int,
        val transactionId: Long,
    ) : IngestionProgress

    /** The whole run finished successfully. Sticky until the next run. */
    data class Done(val summary: IngestionSummary) : IngestionProgress

    /** The whole run failed. [message] is human-readable. */
    data class Failure(val message: String) : IngestionProgress

    /** The user cancelled the run. */
    data object Cancelled : IngestionProgress
}

package com.spendai.app.domain.ingestion

/**
 * Aggregate counts for one [IngestionPipeline] run. Returned to the
 * caller (the foreground service maps it to a notification; the UI
 * surfaces it on the home after the run finishes).
 *
 * Phase 3 removed the day-batched commit step (A3). Every
 * transaction A1 + A2 commit lands directly in `spend_transaction`;
 * there is no review queue, so `needsReview` and `sourceBuckets`
 * are gone.
 */
data class IngestionSummary(
    val totalMessages: Int,
    val parsed: Int,
    val ignored: Int,
    /** Messages where A1 itself threw (engine compiled-model
     *  executor returned an error mid-inference). The engine
     *  rebuilds its conversation so the next call still works;
     *  this message is just dropped from the run. The raw_sms
     *  row stays UNPARSED so a future run can retry it. */
    val skippedByA1: Int = 0,
    /** Messages where A2 returned malformed JSON and the pipeline
     *  skipped them. The raw_sms row stays UNPARSED so a future run
     *  can retry. Surfaced in the Done card so the user knows
     *  nothing was silently dropped. */
    val skippedByA2: Int = 0,
    val committedTransactions: Int,
) {
    companion object {
        val EMPTY = IngestionSummary(0, 0, 0, 0, 0, 0)
    }
}

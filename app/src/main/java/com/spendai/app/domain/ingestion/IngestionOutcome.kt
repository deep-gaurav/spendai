package com.spendai.app.domain.ingestion

/**
 * What the [IngestionPipeline] returns. The pipeline doesn't throw for
 * "soft" failures (A2 didn't parse, A3 returned no commit) — it folds
 * them into the [summary]. Hard failures (engine crash, DB exception)
 * surface as [Failure].
 */
sealed interface IngestionOutcome {
    data class Success(val summary: IngestionSummary) : IngestionOutcome
    data class Failure(val message: String) : IngestionOutcome
}

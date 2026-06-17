package com.spendai.app.domain.model

/**
 * Tunable threshold the worker uses to decide whether a resolved
 * transaction is committed to the canonical [Transaction] table or
 * routed to [com.spendai.app.data.local.entity.PendingReview] for
 * the user to confirm.
 *
 * A single constant lives here so the home screen / settings can
 * surface it later. Phase 1 leaves it at 0.70; lower it for a more
 * cautious (more review cards) experience, raise it for a more
 * hands-off (more auto-commits) experience.
 */
object Confidence {
    const val AUTO_COMMIT_THRESHOLD: Float = 0.70f

    fun shouldAutoCommit(confidence: Float): Boolean =
        confidence >= AUTO_COMMIT_THRESHOLD
}

package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.RepromptJobDao
import com.spendai.app.data.local.entity.RepromptJob
import com.spendai.app.data.local.entity.RepromptJobStatus
import kotlinx.coroutines.flow.Flow

/**
 * Thin facade over [RepromptJobDao] consumed by the
 * [com.spendai.app.service.IngestionService] and the
 * [com.spendai.app.ui.edit.EditTransactionViewModel].
 *
 * The three writes the callers actually use:
 *  - [enqueue] / [start]: lifecycle transitions on a single job row.
 *  - [markCompleted] / [markFailed]: terminal transitions.
 *  - [getStale]: cold-start scan.
 *
 * The on-disk cap is enforced separately by [pruneToMostRecent]
 * (default [DEFAULT_KEEP]).
 */
class RepromptJobRepository(private val dao: RepromptJobDao) {

    suspend fun insert(row: RepromptJob): Long = dao.insert(row)

    suspend fun getById(id: Long): RepromptJob? = dao.getById(id)

    suspend fun getLatestByTransactionId(transactionId: Long): RepromptJob? =
        dao.getLatestByTransactionId(transactionId)

    fun observeByTransactionId(transactionId: Long): Flow<List<RepromptJob>> =
        dao.observeByTransactionId(transactionId)

    /**
     * Cold-start scan. Returns PENDING or RUNNING rows whose last
     * attempt is older than [staleBeforeMillis]. The service uses
     * this on `onCreate` to recover from a process death so the
     * user does not need to manually re-issue the reprompt.
     */
    suspend fun getStale(staleBeforeMillis: Long): List<RepromptJob> =
        dao.getStalePendingOrRunning(staleBeforeMillis)

    suspend fun getRunning(): List<RepromptJob> =
        dao.getByStatus(RepromptJobStatus.RUNNING.name)

    /**
     * Mark a job as actively being run. Increments [RepromptJob.attemptCount]
     * and stamps [RepromptJob.lastAttemptAt]. Used by the service
     * at the start of every attempt, including retries.
     */
    suspend fun markAttempt(
        id: Long,
        status: RepromptJobStatus,
        attemptCount: Int,
        lastAttemptAt: Long,
        errorMessage: String? = null,
    ) = dao.updateAttempt(id, status.name, attemptCount, lastAttemptAt, errorMessage)

    /**
     * Mark a job as terminally complete. Either [RepromptJobStatus.COMPLETED]
     * (success) or [RepromptJobStatus.FAILED] (giving up). The
     * service uses this on every terminal transition.
     */
    suspend fun markTerminal(
        id: Long,
        status: RepromptJobStatus,
        completedAt: Long,
        errorMessage: String? = null,
    ) = dao.complete(id, status.name, completedAt, errorMessage)

    suspend fun count(): Int = dao.count()

    suspend fun pruneToMostRecent(keep: Int = DEFAULT_KEEP) = dao.pruneToMostRecent(keep)

    companion object {
        /**
         * On-disk retention cap. Mirrors [ManualCorrectionRepository.DEFAULT_KEEP]
         * so a heavily-repromptex user does not blow up storage.
         */
        const val DEFAULT_KEEP = 200
    }
}

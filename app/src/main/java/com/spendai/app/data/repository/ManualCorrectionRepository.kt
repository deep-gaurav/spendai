package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.ManualCorrectionDao
import com.spendai.app.data.local.entity.ManualCorrection
import kotlinx.coroutines.flow.Flow

/**
 * Thin facade over [ManualCorrectionDao].
 *
 * The A3 prompt loader calls [getRecent] to pull the
 * [MAX_INJECTED] most recent rows in the order the prompt should
 * see them (newest first). The on-disk cap is enforced separately
 * by [pruneToMostRecent].
 */
class ManualCorrectionRepository(private val dao: ManualCorrectionDao) {

    suspend fun insert(row: ManualCorrection): Long = dao.insert(row)

    /**
     * Newest [limit] corrections, newest first. A3 calls this with
     * [MAX_INJECTED] to load the prompt section.
     */
    suspend fun getRecent(limit: Int = MAX_INJECTED): List<ManualCorrection> = dao.getRecent(limit)

    fun observeRecent(limit: Int = MAX_INJECTED): Flow<List<ManualCorrection>> =
        dao.observeRecent(limit)

    suspend fun getById(id: Long): ManualCorrection? = dao.getById(id)
    suspend fun count(): Int = dao.count()

    suspend fun pruneToMostRecent(keep: Int = DEFAULT_KEEP) = dao.pruneToMostRecent(keep)

    companion object {
        /**
         * Cap on how many corrections get injected into the A3
         * system prompt on each run. The user mentioned 10-20; 15
         * leaves comfortable headroom for the rest of the prompt
         * (system instruction + 20 context transactions) and trims
         * oldest beyond the cap at read time.
         */
        const val MAX_INJECTED = 15

        /** On-disk retention cap. Old rows beyond this are pruned. */
        const val DEFAULT_KEEP = 200
    }
}

package com.spendai.app.domain.ingestion.sources

import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.SmsSource

/**
 * No-op [SmsSource] used by the periodic [com.spendai.app.worker.DailyParsingWorker].
 *
 * The receiver already persists incoming SMS into `raw_sms` with
 * `status = UNPARSED`. The worker doesn't need to load anything new
 * — it just runs the pipeline over whatever's already pending. This
 * source's `load` is a no-op that returns 0; the pipeline then calls
 * `smsRepository.unparsedInRange(range)` (or `unparsedOnce()` for the
 * worker, which uses an unbounded range) to fetch the rows.
 */
class DatabaseSmsSource(
    @Suppress("unused") private val smsRepository: SmsRepository,
) : SmsSource {
    override suspend fun load(
        range: DateRange,
        sink: suspend (RawSmsMessage) -> Unit,
    ): Int = 0
}

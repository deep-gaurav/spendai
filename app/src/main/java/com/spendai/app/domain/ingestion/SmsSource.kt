package com.spendai.app.domain.ingestion

import com.spendai.app.data.local.entity.RawSmsMessage

/**
 * A loader for the [IngestionPipeline]. Implementations range from
 * the OS SMS provider (historical ingestion) to a fixed list (tests).
 *
 * The [load] contract:
 *  - Iterates the [range] in timestamp-ascending order.
 *  - For each message, calls [sink] exactly once. The pipeline's sink
 *    is `smsRepository.insert(msg)`, which means messages are
 *    persisted into `raw_sms` with the existing dedup index
 *    `(senderAddress, timestamp)` silently swallowing duplicates.
 *  - Returns the number of messages that were *newly* inserted.
 *    Sources that re-query a provider should count attempted inserts,
 *    not actual new rows — Room's `OnConflictStrategy.IGNORE` makes
 *    the insert return `-1` for a duplicate, but a `load` impl can
 *    just return its full scan count without bothering to inspect the
 *    return code (the pipeline doesn't differentiate).
 */
interface SmsSource {
    suspend fun load(
        range: DateRange,
        sink: suspend (RawSmsMessage) -> Unit,
    ): Int
}

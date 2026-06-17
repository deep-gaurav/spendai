package com.spendai.app.domain.ingestion.sources

import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.SmsSource

/**
 * Test-only [SmsSource] backed by a fixed list. Useful for
 * [com.spendai.app.domain.ingestion.IngestionPipelineTest] — pass a
 * canned list, drive the pipeline, assert on the resulting DB rows.
 */
class ListSmsSource(
    private val messages: List<RawSmsMessage>,
) : SmsSource {
    override suspend fun load(
        range: DateRange,
        sink: suspend (RawSmsMessage) -> Unit,
    ): Int {
        var count = 0
        for (msg in messages) {
            if (range.contains(msg.timestamp)) {
                sink(msg.copy(status = SmsStatus.UNPARSED))
                count++
            }
        }
        return count
    }
}

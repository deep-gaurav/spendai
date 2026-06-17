package com.spendai.app.domain.ingestion.sources

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.SmsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the OS SMS provider (`content://sms/inbox`) for the given
 * range and yields each row to the pipeline's sink.
 *
 * Requires `android.permission.READ_SMS` granted at runtime — the
 * permissions screen requests it before the home becomes reachable.
 *
 * ## Duplicates
 *
 * The provider may return messages the receiver already captured. The
 * existing `raw_sms` unique index on `(senderAddress, timestamp)`
 * silently swallows the duplicate insert (Room's IGNORE conflict
 * strategy), so the pipeline's A1 pass simply sees no UNPARSED row
 * for that sender+timestamp. The pipeline's own dedup is via
 * `parsedSmsRepository.getByRawSms(id)` so re-runs are also no-ops.
 *
 * ## Threading
 *
 * The cursor is queried on `Dispatchers.IO`. The pipeline's sink
 * writes to Room on its own dispatcher.
 */
class ContentResolverSmsSource(
    private val context: Context,
) : SmsSource {
    override suspend fun load(
        range: DateRange,
        sink: suspend (RawSmsMessage) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = context.contentResolver
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
        val args = arrayOf(range.startMillis.toString(), range.endMillis.toString())
        val sort = "${Telephony.Sms.DATE} ASC"

        var count = 0
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            args,
            sort,
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val sender = if (addrIdx >= 0) cursor.getString(addrIdx).orEmpty() else ""
                val body = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else ""
                val ts = if (dateIdx >= 0) cursor.getLong(dateIdx) else System.currentTimeMillis()
                sink(
                    RawSmsMessage(
                        senderAddress = sender,
                        msgBody = body,
                        timestamp = ts,
                        status = SmsStatus.UNPARSED,
                    )
                )
                count++
            }
        }
        count
    }
}

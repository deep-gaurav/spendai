package com.spendai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.service.IngestionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Captures incoming SMS messages and persists them in raw form.
 *
 * ## Lifecycle and threading
 *
 * A `BroadcastReceiver` is killed within ~10 seconds of `onReceive`
 * returning. To safely perform a DB write we call `goAsync()` which gives
 * us a [PendingResult] we can mark complete once our coroutine finishes.
 * Without this, the JVM could reclaim the process mid-insert on a
 * low-memory device and the SMS would be lost.
 *
 * ## Why no inference here
 *
 * Tempting as it is, we deliberately do NOT call [com.spendai.app.inference.GemmaInferenceEngine]
 * from this receiver:
 *  1. The 10-second budget is hostile to LLM work — first-load alone can
 *     take up to 10s, with no headroom for actual generation.
 *  2. `Engine.initialize()` and `sendMessage()` allocate large native
 *     buffers. Doing that in a process that may be reaped before the work
 *     completes risks bad-state crashes and ANRs in unrelated apps.
 *  3. Users do not see this receiver fire — the right place to surface
 *     errors is the foreground service.
 *
 * ## Hand-off to the service (v6)
 *
 * The previous version enqueued a one-shot [com.spendai.app.worker.DailyParsingWorker]
 * with `ExistingWorkPolicy.KEEP`. v6 fires the foreground
 * [IngestionService] directly via
 * [IngestionService.startPending]. The service's re-entrancy guard
 * makes the call a no-op when a run is already in flight, so the
 * receiver can fire on every message without piling up WorkManager
 * jobs.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        // goAsync() returns a PendingResult that holds the receiver "alive"
        // past onReceive — we have ~10s before the OS reclaims us.
        val pendingResult = goAsync()
        val app = context.applicationContext as SpendAiApp
        val smsRepository = app.smsRepository

        // runBlocking here is acceptable: PendingResult.finish() must be
        // called before its reference is GC'd, and we are explicitly
        // trading async cleanliness for the bounded lifespan guarantee.
        // The actual DB call inside is non-suspending from our perspective
        // (it suspends on Dispatchers.IO internally via Room's adapter).
        runBlocking(Dispatchers.Default) {
            try {
                val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: emptyArray()
                val groups = smsMessages.groupBy { it.displayOriginatingAddress }
                val now = System.currentTimeMillis()
                var insertedAny = false

                for ((sender, parts) in groups) {
                    val firstPart = parts.firstOrNull() ?: continue
                    val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
                    val timestamp = firstPart.timestampMillis
                    val message = RawSmsMessage(
                        senderAddress = sender.orEmpty(),
                        msgBody = body,
                        timestamp = if (timestamp > 0L) timestamp else now,
                        status = SmsStatus.UNPARSED,
                    )
                    val rowId = smsRepository.insert(message)
                    if (rowId > 0) {
                        insertedAny = true
                        Log.d(TAG, "Persisted SMS id=$rowId from ${message.senderAddress}")
                    } else {
                        Log.d(TAG, "Duplicate SMS ignored: ${message.senderAddress} @ ${message.timestamp}")
                    }
                }

                if (insertedAny) {
                    // Hand off to the foreground service. The
                    // service's busy guard means this is a no-op
                    // when an ingestion is already in flight — a
                    // future redelivery or the next message will
                    // pick the new UNPARSED row up.
                    IngestionService.startPending(context.applicationContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist SMS", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}

package com.spendai.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.worker.DailyParsingWorker
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
 *     errors is a foreground/WorkManager context.
 *
 * We DO enqueue a [DailyParsingWorker] one-shot so fresh messages don't
 * have to wait for the 24h periodic schedule.
 *
 * ## Permissions
 *
 * The `RECEIVE_SMS` permission is "dangerous" on Android 6+ and must be
 * granted at runtime by the user. Until then, the OS will simply not
 * deliver the broadcast to us and this class is unreachable. The consent
 * screen is a Phase 1.5 follow-up.
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
        val workManager = WorkManager.getInstance(context)

        // runBlocking here is acceptable: PendingResult.finish() must be
        // called before its reference is GC'd, and we are explicitly
        // trading async cleanliness for the bounded lifespan guarantee.
        // The actual DB call inside is non-suspending from our perspective
        // (it suspends on Dispatchers.IO internally via Room's adapter).
        runBlocking(Dispatchers.Default) {
            try {
                val pdus: Array<*> = intent.extras?.get("pdus") as? Array<*> ?: emptyArray<Any>()
                val format = intent.extras?.getString("format")
                val now = System.currentTimeMillis()

                for (pdu in pdus) {
                    val sms = android.telephony.SmsMessage.createFromPdu(pdu as ByteArray, format)
                    val message = RawSmsMessage(
                        senderAddress = sms.displayOriginatingAddress.orEmpty(),
                        msgBody = sms.displayMessageBody.orEmpty(),
                        timestamp = if (sms.timestampMillis > 0L) sms.timestampMillis else now,
                        status = SmsStatus.UNPARSED
                    )
                    val rowId = smsRepository.insert(message)
                    if (rowId > 0) {
                        Log.d(TAG, "Persisted SMS id=$rowId from ${message.senderAddress}")
                    } else {
                        Log.d(TAG, "Duplicate SMS ignored: ${message.senderAddress} @ ${message.timestamp}")
                    }
                }

                // Kick the worker so the new UNPARSED rows don't sit until
                // the next 24h periodic tick. KEEP is safe — if a worker
                // is already running it will pick these up when it loops.
                val request = OneTimeWorkRequestBuilder<DailyParsingWorker>().build()
                workManager.enqueueUniqueWork(
                    DailyParsingWorker.UNIQUE_ONE_SHOT,
                    ExistingWorkPolicy.KEEP,
                    request
                )
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

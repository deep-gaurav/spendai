package com.spendai.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.spendai.app.R
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionOutcome
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.domain.ingestion.sources.ContentResolverSmsSource
import com.spendai.app.domain.ingestion.sources.DatabaseSmsSource
import com.spendai.app.inference.InferenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that drives an [com.spendai.app.domain.ingestion.IngestionPipeline]
 * run. Started from:
 *  - the home screen (date range, user-picked via the picker),
 *  - the home screen "Re-process pending" CTA (re-process every
 *    row whose `processedAt` is still null),
 *  - the SMS receiver (kick the service on every new message),
 *  - the periodic WorkManager (24h safety net).
 *
 * v6 is the only ingestion executor. WorkManager and the receiver
 * are thin handoffs that fire an intent on this service.
 *
 * ## Pre-flight checks
 *
 * Before [startForeground] the service:
 *  1. Checks `READ_SMS`. If denied, emits
 *     `IngestionProgress.Failure("READ_SMS permission denied...")` and
 *     stops — the OS provider returns null rows when the caller
 *     can't read SMS, which would silently produce a 0-message run.
 *  2. Initialises the [com.spendai.app.inference.GemmaInferenceEngine]
 *     if it isn't already READY.
 *
 * ## Progress surface
 *
 * Each [IngestionProgress] event from the pipeline is republished to
 * a process-scoped [StateFlow][progress] (companion object) and to a
 * foreground notification. The home's [com.spendai.app.ui.home.HomeViewModel]
 * collects the StateFlow; the notification is the user's
 * out-of-app feedback channel.
 *
 * ## Reliability (v6)
 *
 *  - **Re-entrancy guard**: the companion holds an [AtomicBoolean busy]
 *    that is `true` from the moment a run starts until it finishes.
 *    A second `onStartCommand` arriving while `busy` is true is
 *    published as a `Failure("Another ingestion is already running")`
 *    event and the intent is dropped (`START_NOT_STICKY` for it).
 *    The in-flight run is not disturbed. This is the key protection
 *    against the WorkManager + receiver + UI three-way race.
 *  - `onStartCommand` returns `START_REDELIVER_INTENT` so the OS
 *    restarts the service after a kill. The pipeline is naturally
 *    idempotent because `raw_sms.processedAt` advances on every
 *    terminal state, so a restart resumes the remaining pending
 *    rows without re-doing finished work.
 *  - The wake lock is acquired with no timeout and released
 *    manually when the pipeline finishes. The 15-minute cap that
 *    the previous version used was too short for a 30-day
 *    historical run; the OS releases the lock automatically if
 *    the process dies.
 *  - Long-lived foreground service with `dataSync` type. On
 *    Android 14+, the type must match the actual work; the
 *    manifest declaration is already correct.
 */
class IngestionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var busyFor: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            runJob?.cancel()
            val app = applicationContext as SpendAiApp
            runCatching { kotlinx.coroutines.runBlocking { app.gemmaInferenceEngine.cancelCurrent() } }
                .onFailure { Log.w(TAG, "engine.cancelCurrent() failed", it) }
            _progress.value = IngestionProgress.Idle
            busy.set(false)
            busyFor = ""
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (busy.get()) {
            Log.w(TAG, "ingestion already running for '$busyFor'; dropping new intent")
            _progress.value = IngestionProgress.Failure(
                "Another ingestion is already running ($busyFor)"
            )
            // Do NOT stopSelf — the in-flight run owns the service.
            return START_NOT_STICKY
        }

        val action = intent?.action
        val mode: String
        val sourceFactory: () -> com.spendai.app.domain.ingestion.SmsSource
        val range: DateRange
        val displayLabel: String

        when (action) {
            ACTION_INGEST_PENDING -> {
                mode = "pending"
                sourceFactory = { DatabaseSmsSource(applicationContext.smsRepository()) }
                range = DateRange.unbounded()
                displayLabel = "Re-processing pending"
            }
            else -> {
                // Default: ingest the date range carried in the intent extras.
                val start = intent?.getLongExtra(EXTRA_START_MILLIS, 0L) ?: 0L
                val end = intent?.getLongExtra(EXTRA_END_MILLIS, 0L) ?: 0L
                range = if (start > 0L && end > start) {
                    DateRange(start, end)
                } else {
                    DateRange.unbounded()
                }
                mode = "range"
                sourceFactory = { ContentResolverSmsSource(applicationContext) }
                displayLabel = "Ingesting…"
            }
        }

        busy.set(true)
        busyFor = mode

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "SpendAi::IngestionWakeLock"
        ).apply {
            setReferenceCounted(false)
            // No timeout — the lock is released by stopSelfSafely()
            // when the pipeline finishes (success, failure, or
            // cancel). The OS releases it automatically if the
            // process dies.
            acquire()
        }

        _progress.value = IngestionProgress.LoadingFromSource(0)

        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(displayLabel, "Starting"),
        )

        runJob = scope.launch {
            val app = applicationContext as SpendAiApp

            // Engine init.
            try {
                if (app.gemmaInferenceEngine.state.value !is InferenceState.Ready) {
                    publishProgress(IngestionProgress.EngineInitialising(labelFor(app.gemmaInferenceEngine.state.value)))
                    app.gemmaInferenceEngine.initialize(applicationContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Engine init failed", t)
                publishProgress(
                    IngestionProgress.Failure(
                        "Engine failed to initialise: ${t.message ?: t.javaClass.simpleName}"
                    )
                )
                releaseBusy()
                stopSelfSafely()
                return@launch
            }

            // Permission check (READ_SMS). The pending path doesn't
            // need it (rows are already in the DB), so we skip
            // the check there.
            if (mode == "range") {
                val hasSms = ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.READ_SMS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasSms) {
                    publishProgress(IngestionProgress.Failure("READ_SMS permission denied"))
                    releaseBusy()
                    stopSelfSafely()
                    return@launch
                }
            }

            // Run the pipeline.
            val outcome: IngestionOutcome = if (mode == "pending") {
                app.ingestionPipeline.runPending(emit = { publishProgress(it) })
            } else {
                app.ingestionPipeline.run(
                    source = sourceFactory(),
                    range = range,
                    emit = { publishProgress(it) },
                )
            }
            Log.d(TAG, "Pipeline finished (mode=$mode): $outcome")
            releaseBusy()
            stopSelfSafely()
        }
        // Redeliver the intent on a system-initiated restart so the
        // pipeline resumes the same work after a kill. The pipeline
        // is naturally idempotent because raw_sms.processedAt
        // advances on every terminal state, so a restart picks up
        // where it left off.
        return START_REDELIVER_INTENT
    }

    private fun releaseBusy() {
        busy.set(false)
        busyFor = ""
    }

    private suspend fun publishProgress(progress: IngestionProgress) {
        _progress.value = progress
        val (title, text) = when (progress) {
            is IngestionProgress.EngineInitialising -> "Starting engine" to
                progress.currentState
            is IngestionProgress.LoadingFromSource -> "Ingesting…" to
                "Loaded ${progress.seenSoFar} messages"
            is IngestionProgress.MessageParsed -> "Ingesting" to
                "Parsed ${progress.messageIndex + 1}/${progress.totalMessages}"
            is IngestionProgress.MessageCommitted -> "Ingesting" to
                "Committed ${progress.messageIndex + 1}/${progress.totalMessages}"
            is IngestionProgress.MessageSkipped -> "Skipped" to progress.reason
            is IngestionProgress.Done -> "Done" to doneLine(progress)
            is IngestionProgress.Failure -> "Failed" to progress.message
            is IngestionProgress.Cancelled -> "Cancelled" to ""
            IngestionProgress.Idle -> "Ingest" to ""
        }
        val notification = buildNotification(title, text)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun doneLine(progress: IngestionProgress.Done): String {
        val s = progress.summary
        val parts = buildList {
            add("${s.committedTransactions} committed")
            if (s.ignored > 0) add("${s.ignored} ignored")
            if (s.skippedByA1 > 0) add("${s.skippedByA1} skipped (A1)")
            if (s.skippedByA2 > 0) add("${s.skippedByA2} skipped (A2)")
        }
        return parts.joinToString(", ")
    }

    private fun stopSelfSafely() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runJob?.cancel()
        scope.cancel()
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
        releaseBusy()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS ingestion",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress of the local SMS ingestion run."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val cancelIntent = Intent(this, IngestionService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_cartoon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_cross_cartoon,
                "Cancel",
                cancelPending,
            )
            .build()
    }

    companion object {
        private const val TAG = "IngestionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "spendai.ingest"
        const val ACTION_CANCEL = "com.spendai.app.action.INGEST_CANCEL"
        const val ACTION_INGEST_PENDING = "com.spendai.app.action.INGEST_PENDING"
        const val EXTRA_START_MILLIS = "spendai.extra.START_MILLIS"
        const val EXTRA_END_MILLIS = "spendai.extra.END_MILLIS"

        /**
         * Re-entrancy guard. The flag is flipped to `true` the
         * moment a run starts and back to `false` when the
         * pipeline finishes (success, failure, or cancel). A
         * second `onStartCommand` arriving while this is `true`
         * is dropped with a `Failure` event.
         */
        private val busy = AtomicBoolean(false)

        private val _progress = MutableStateFlow<IngestionProgress>(IngestionProgress.Idle)
        val progress: StateFlow<IngestionProgress> = _progress.asStateFlow()

        fun start(context: Context, range: DateRange) {
            val intent = Intent(context, IngestionService::class.java).apply {
                putExtra(EXTRA_START_MILLIS, range.startMillis)
                putExtra(EXTRA_END_MILLIS, range.endMillis)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Start the "Re-process pending" pipeline. The service
         * ignores the date range and re-runs A1+A2 on every
         * `raw_sms` row whose `processedAt` is still null.
         */
        fun startPending(context: Context) {
            val intent = Intent(context, IngestionService::class.java).apply {
                action = ACTION_INGEST_PENDING
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, IngestionService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}

private fun android.content.Context.smsRepository(): com.spendai.app.data.repository.SmsRepository =
    (this as SpendAiApp).smsRepository

private fun labelFor(state: InferenceState): String = when (state) {
    is InferenceState.Uninitialized -> "Not loaded"
    is InferenceState.Loading -> "Loading…"
    is InferenceState.Ready -> "Ready on ${state.backendLabel}"
    is InferenceState.Busy -> state.progress.toLabel()
    is InferenceState.Error -> "Error: ${state.message}"
}

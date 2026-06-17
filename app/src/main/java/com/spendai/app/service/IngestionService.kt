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

/**
 * Foreground service that drives an [com.spendai.app.domain.ingestion.IngestionPipeline]
 * run for a user-picked date range. Started from
 * [com.spendai.app.ui.home.HomeViewModel] when the user taps "Ingest"
 * and selects Yesterday / Last 7 days / Last 30 days.
 *
 * ## Pre-flight checks
 *
 * Before [startForeground] the service:
 *  1. Checks `READ_SMS`. If denied, emits
 *     `IngestionProgress.Failure("READ_SMS permission denied...")` and
 *     stops — the OS provider returns null rows when the caller
 *     can't read SMS, which would silently produce a 0-message run.
 *  2. Initialises the [com.spendai.app.inference.GemmaInferenceEngine]
 *     if it isn't already READY. Engine load is up to 10s on first
 *     run; we surface this on the notification so the user sees
 *     "Engine: Loading on NPU..." while it warms up.
 *
 * ## Progress surface
 *
 * Each [IngestionProgress] event from the pipeline is republished to
 * a process-scoped [StateFlow][progress] (companion object) and to a
 * foreground notification. The home's [com.spendai.app.ui.home.HomeViewModel]
 * collects the StateFlow; the notification is the user's
 * out-of-app feedback channel.
 *
 * ## Reliability (v0.6)
 *
 *  - `onStartCommand` returns `START_REDELIVER_INTENT` so the OS
 *    restarts the service after a kill. The pipeline is naturally
 *    idempotent because `raw_sms.status` advances on every commit,
 *    so a restart resumes the remaining UNPARSED rows without
 *    re-doing finished work.
 *  - The wake lock is acquired with no timeout and released
 *    manually when the pipeline finishes. The 15-minute cap that
 *    the previous version used was too short for a 30-day
 *    historical run; the OS releases the lock automatically if
 *    the process dies.
 *  - The [ACTION_REPROCESS] intent action routes through
 *    [IngestionPipeline.runPending] to re-process any
 *    raw_sms row that does not have a corresponding
 *    `spend_transaction`. This is the recovery path for messages
 *    that ended up in a stuck state because the previous run was
 *    killed by Doze / OOM.
 */
class IngestionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            runJob?.cancel()
            val app = applicationContext as SpendAiApp
            runCatching { kotlinx.coroutines.runBlocking { app.gemmaInferenceEngine.cancelCurrent() } }
                .onFailure { Log.w(TAG, "engine.cancelCurrent() failed", it) }
            _progress.value = IngestionProgress.Idle
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPROCESS) {
            return startReprocess()
        }

        val start = intent?.getLongExtra(EXTRA_START_MILLIS, 0L) ?: 0L
        val end = intent?.getLongExtra(EXTRA_END_MILLIS, 0L) ?: 0L
        val range = if (start > 0L && end > start) DateRange(start, end) else DateRange.unbounded()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpendAi::IngestionWakeLock").apply {
            setReferenceCounted(false)
            // No timeout — the lock is released by stopSelfSafely()
            // when the pipeline finishes (success, failure, or
            // cancel). The OS releases it automatically if the
            // process dies. The 15-minute cap from the previous
            // version was too short for a 30-day historical run.
            acquire()
        }

        _progress.value = IngestionProgress.LoadingFromSource(0)

        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Ingesting…", "Starting"),
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
                stopSelfSafely()
                return@launch
            }

            // Permission check (READ_SMS).
            val hasSms = ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.READ_SMS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasSms) {
                publishProgress(IngestionProgress.Failure("READ_SMS permission denied"))
                stopSelfSafely()
                return@launch
            }

            // Run the pipeline.
            val outcome = app.ingestionPipeline.run(
                source = ContentResolverSmsSource(applicationContext),
                range = range,
                emit = { progress -> publishProgress(progress) },
            )
            Log.d(TAG, "Pipeline finished: $outcome")
            stopSelfSafely()
        }
        // Redeliver the intent on a system-initiated restart so the
        // pipeline resumes the same range after a kill. The pipeline
        // is naturally idempotent because raw_sms.status advances on
        // every commit, so a restart picks up where it left off.
        return START_REDELIVER_INTENT
    }

    /**
     * Start the "Re-process pending" pipeline. Bypasses the date
     * range and the permission check (it doesn't need the SMS
     * provider — the rows are already in the DB).
     */
    private fun startReprocess(): Int {
        Log.d(TAG, "ACTION_REPROCESS received")
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpendAi::IngestionWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        _progress.value = IngestionProgress.LoadingFromSource(0)
        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Re-processing pending…", "Starting"),
        )

        runJob = scope.launch {
            val app = applicationContext as SpendAiApp
            try {
                if (app.gemmaInferenceEngine.state.value !is InferenceState.Ready) {
                    publishProgress(IngestionProgress.EngineInitialising(labelFor(app.gemmaInferenceEngine.state.value)))
                    app.gemmaInferenceEngine.initialize(applicationContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Engine init failed during reprocess", t)
                publishProgress(
                    IngestionProgress.Failure(
                        "Engine failed to initialise: ${t.message ?: t.javaClass.simpleName}"
                    )
                )
                stopSelfSafely()
                return@launch
            }

            val outcome = app.ingestionPipeline.runPending(
                emit = { progress -> publishProgress(progress) },
            )
            Log.d(TAG, "runPending finished: $outcome")
            stopSelfSafely()
        }
        return START_REDELIVER_INTENT
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
        const val ACTION_REPROCESS = "com.spendai.app.action.INGEST_REPROCESS"
        const val EXTRA_START_MILLIS = "spendai.extra.START_MILLIS"
        const val EXTRA_END_MILLIS = "spendai.extra.END_MILLIS"

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
         * ignores the date range and re-runs A1+A2 on any
         * raw_sms row that does not have a corresponding
         * `spend_transaction`.
         */
        fun startReprocess(context: Context) {
            val intent = Intent(context, IngestionService::class.java).apply {
                action = ACTION_REPROCESS
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

private fun labelFor(state: InferenceState): String = when (state) {
    is InferenceState.Uninitialized -> "Not loaded"
    is InferenceState.Loading -> "Loading…"
    is InferenceState.Ready -> "Ready on ${state.backendLabel}"
    is InferenceState.Busy -> state.progress.toLabel()
    is InferenceState.Error -> "Error: ${state.message}"
}

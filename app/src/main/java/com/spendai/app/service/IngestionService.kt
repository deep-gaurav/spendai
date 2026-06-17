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
 *     `IngestionProgress.Failure("READ_SMS permission denied…")` and
 *     stops — the OS provider returns null rows when the caller
 *     can't read SMS, which would silently produce a 0-message run.
 *  2. Initialises the [com.spendai.app.inference.GemmaInferenceEngine]
 *     if it isn't already READY. Engine load is up to 10s on first
 *     run; we surface this on the notification so the user sees
 *     "Engine: Loading on NPU…" while it warms up.
 *
 * ## Progress surface
 *
 * Each [IngestionProgress] event from the pipeline is republished to
 * a process-scoped [StateFlow][progress] (companion object) and to a
 * foreground notification. The home's [com.spendai.app.ui.home.HomeViewModel]
 * collects the StateFlow; the notification is the user's
 * out-of-app feedback channel.
 */
class IngestionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            runJob?.cancel()
            // Cancel the in-flight inference on the C++ side as well.
            // Coroutine cancellation alone does not reach the native
            // decode thread — the model would keep running until the
            // pipeline caught the eventual LiteRtLmJniException.
            // conversation.cancelProcess() is the real cancel.
            val app = applicationContext as SpendAiApp
            runCatching { kotlinx.coroutines.runBlocking { app.gemmaInferenceEngine.cancelCurrent() } }
                .onFailure { Log.w(TAG, "engine.cancelCurrent() failed", it) }
            // Reset to Idle, not Cancelled. Idle is the clean re-ingestable
            // sticky state; Cancelled would still render the "Cancelled"
            // line on the home card instead of the "Pick a range" CTA.
            _progress.value = IngestionProgress.Idle
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val start = intent?.getLongExtra(EXTRA_START_MILLIS, 0L) ?: 0L
        val end = intent?.getLongExtra(EXTRA_END_MILLIS, 0L) ?: 0L
        val range = if (start > 0L && end > start) DateRange(start, end) else DateRange.unbounded()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpendAi::IngestionWakeLock").apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L) // 15 mins safeguard
        }

        _progress.value = IngestionProgress.LoadingFromSource(0)

        ensureNotificationChannel()
        val initial = buildNotification("Ingesting…", "Checking permissions")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        runJob = scope.launch {
            val app = applicationContext as SpendAiApp

            // 1. READ_SMS pre-check. Without it the OS provider returns
            //    null and the pipeline silently completes with 0 rows.
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.READ_SMS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                publishProgress(
                    IngestionProgress.Failure(
                        "READ_SMS permission denied. Grant it in the Ingest sheet."
                    )
                )
                stopSelfSafely()
                return@launch
            }

            // 2. Auto-initialise the engine if it isn't already READY.
            //    Engine load is the single longest pause in the run;
            //    surfacing the state on the notification is the only
            //    way the user knows "it's not stuck, the model is loading".
            if (app.gemmaInferenceEngine.state.value !is InferenceState.Ready) {
                publishProgress(
                    IngestionProgress.EngineInitialising(
                        currentState = app.gemmaInferenceEngine.state.value.javaClass.simpleName
                    )
                )
                try {
                    app.gemmaInferenceEngine.initialize(applicationContext)
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
            }

            // 3. Run the pipeline.
            val outcome = app.ingestionPipeline.run(
                source = ContentResolverSmsSource(applicationContext),
                range = range,
                emit = { progress -> publishProgress(progress) },
            )
            Log.d(TAG, "Pipeline finished: $outcome")
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    private suspend fun publishProgress(progress: IngestionProgress) {
        _progress.value = progress
        val (title, text) = when (progress) {
            is IngestionProgress.EngineInitialising -> "Starting engine" to
                progress.currentState
            is IngestionProgress.LoadingFromSource -> "Ingesting…" to
                "Loaded ${progress.seenSoFar} messages"
            is IngestionProgress.DayStarting -> "Day ${progress.dayIndex} of ${progress.totalDays}" to
                "${progress.messageCount} messages"
            is IngestionProgress.MessageParsed -> "Day ${progress.dayIndex}" to
                "Parsed ${progress.messageIndex}/${progress.totalMessages}"
            is IngestionProgress.MessageResolved -> "Day ${progress.dayIndex}" to
                "Resolved ${progress.messageIndex}/${progress.totalMessages}"
            is IngestionProgress.CommittingDay -> "Day ${progress.dayIndex} of ${progress.totalDays}" to
                "Committing…"
            is IngestionProgress.DayCommitted ->
                "Day ${progress.dayIndex}/${progress.totalDays}" to
                "Committed (total: ${progress.commitCount})"
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
            add("${s.needsReview} to review")
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

        fun cancel(context: Context) {
            val intent = Intent(context, IngestionService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}

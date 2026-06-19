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
import com.spendai.app.data.local.entity.RepromptJob
import com.spendai.app.data.local.entity.RepromptJobStatus
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionOutcome
import com.spendai.app.domain.ingestion.IngestionSummary
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.domain.ingestion.sources.ContentResolverSmsSource
import com.spendai.app.domain.ingestion.sources.DatabaseSmsSource
import com.spendai.app.inference.InferenceState
import com.spendai.app.ui.ACTION_OPEN_TRANSACTION
import com.spendai.app.ui.EXTRA_TRANSACTION_ID
import com.spendai.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that drives an [com.spendai.app.domain.ingestion.IngestionPipeline]
 * run. Started from:
 *  - the home screen (date range, user-picked via the picker),
 *  - the home screen "Re-process pending" CTA (re-process every
 *    row whose `processedAt` is still null),
 *  - the SMS receiver (kick the service on every new message),
 *  - the periodic WorkManager (24h safety net),
 *  - the edit screen's "Reprompt A3" button (per-transaction A3
 *    re-decision with the user-typed prompt).
 *
 * v7 is the only ingestion executor. WorkManager and the receiver
 * are thin handoffs that fire an intent on this service.
 *
 * ## Pre-flight checks
 *
 * Before [startForeground] the service:
 *  1. Checks `READ_SMS` (range mode only). If denied, emits
 *     `IngestionProgress.Failure("READ_SMS permission denied...")` and
 *     stops — the OS provider returns null rows when the caller
 *     can't read SMS, which would silently produce a 0-message run.
 *  2. Initialises the [com.spendai.app.inference.GemmaInferenceEngine]
 *     if it isn't already READY.
 *
 * ## Run modes
 *
 * The service handles two distinct run types via [RunMode]:
 *
 *  - [RunMode.Ingestion]: standard range / pending re-process.
 *    Notification posts to the `spendai.ingest` channel as
 *    "Ingesting…". Reuses the v6 single-job behaviour.
 *  - [RunMode.Reprompt]: per-transaction A3 re-decision with the
 *    user-typed override prompt. Notification posts as
 *    "Reprompting…". Persists a [RepromptJob] row so process
 *    death does not drop the prompt.
 *
 * The re-entrancy guard rejects a second intent of either kind
 * while a run is in flight. A `Reprompt` cannot pre-empt an
 * `Ingestion` (and vice-versa); the user has to wait.
 *
 * ## Reliability (v6)
 *
 *  - **Re-entrancy guard**: the companion holds an [AtomicBoolean busy]
 *    that is `true` from the moment a run starts until it finishes.
 *    A second `onStartCommand` arriving while `busy` is true is
 *    published as a `Failure` event and the intent is dropped
 *    (`START_NOT_STICKY` for it). The in-flight run is not
 *    disturbed.
 *  - `onStartCommand` returns `START_REDELIVER_INTENT` so the OS
 *    restarts the service after a kill. The pipeline is naturally
 *    idempotent because `raw_sms.processedAt` advances on every
 *    terminal state, so a restart resumes the remaining pending
 *    rows without re-doing finished work. The reprompt path is
 *    additionally guarded by the [RepromptJob] row, which the
 *    cold-start scan re-drives if the service is killed mid-run.
 *  - The wake lock is acquired with no timeout and released
 *    manually when the pipeline finishes.
 *  - Long-lived foreground service with `dataSync` type. On
 *    Android 14+, the type must match the actual work; the
 *    manifest declaration is already correct.
 *  - **Reprompt retry**: a transient error from the LLM (429 /
 *    500 / 502 / 503 / 504) is caught by the service, the
 *    [RepromptJob] `attemptCount` is incremented, the notification
 *    shows "Retrying in 60s…", and the pipeline is re-invoked
 *    after a 60s `delay`. Capped at [MAX_REPROMPT_ATTEMPTS] (3).
 *    Non-transient errors fail immediately.
 */
class IngestionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * The current run's mode, or null if the service is idle. Used
     * by the re-entrancy guard to publish a precise error message
     * ("another ingestion is already running" vs "another reprompt
     * is already running") and by [ACTION_CANCEL] to know what
     * kind of run to cancel.
     */
    private var runMode: RunMode? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // Cold-start scan: pick up reprompt jobs that were running
        // when the process died. Best-effort: if the service is
        // already busy (e.g. an ingestion is in flight from the
        // periodic worker), the scan is a no-op and the next cold
        // start will try again.
        scanStaleRepromptJobs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            runJob?.cancel()
            runMode = null
            val app = applicationContext as SpendAiApp
            runCatching { kotlinx.coroutines.runBlocking { app.gemmaInferenceEngine.cancelCurrent() } }
                .onFailure { Log.w(TAG, "engine.cancelCurrent() failed", it) }
            _progress.value = IngestionProgress.Idle
            _repromptProgress.value = IngestionProgress.Idle
            busy.set(false)
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (busy.get()) {
            val current = runMode
            val label = when (current) {
                is RunMode.Ingestion -> "ingestion"
                is RunMode.Reprompt -> "reprompt for transaction ${current.transactionId ?: "(unknown)"}"
                null -> "another run"
            }
            Log.w(TAG, "$label already running; dropping new intent")
            val failure = IngestionProgress.Failure("Another $label is already running")
            when (intent?.action) {
                ACTION_REPROMPT -> _repromptProgress.value = failure
                else -> _progress.value = failure
            }
            return START_NOT_STICKY
        }

        val action = intent?.action

        if (action == ACTION_REPROMPT) {
            return startRepromptFromIntent(intent, startId)
        }

        return startIngestionFromIntent(intent, startId)
    }

    private fun startIngestionFromIntent(intent: Intent?, startId: Int): Int {
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
                displayLabel = "Ingesting…"
            }
            else -> {
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
        runMode = RunMode.Ingestion(mode)

        acquireWakeLock()
        _progress.value = IngestionProgress.LoadingFromSource(0)
        startForeground(NOTIFICATION_ID_INGEST, buildIngestNotification(displayLabel, "Starting"))

        runJob = scope.launch {
            val app = applicationContext as SpendAiApp

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
        return START_REDELIVER_INTENT
    }

    private fun startRepromptFromIntent(intent: Intent?, startId: Int): Int {
        val rawSmsIds = intent?.getLongArrayExtra(EXTRA_REPROMPT_RAW_SMS_IDS)?.toList().orEmpty()
        val userPrompt = intent?.getStringExtra(EXTRA_REPROMPT_USER_PROMPT).orEmpty().trim()
        val transactionId = intent?.getLongExtra(EXTRA_REPROMPT_TRANSACTION_ID, -1L)?.takeIf { it > 0L }
        val resumeJobId = intent?.getLongExtra(EXTRA_REPROMPT_RESUME_JOB_ID, -1L)?.takeIf { it > 0L }

        if (rawSmsIds.isEmpty() || userPrompt.isEmpty()) {
            Log.w(TAG, "Reprompt intent missing rawSmsIds or userPrompt; ignoring")
            _repromptProgress.value = IngestionProgress.Failure("Reprompt missing ids or prompt")
            return START_NOT_STICKY
        }

        busy.set(true)
        runMode = RunMode.Reprompt(transactionId, rawSmsIds)

        acquireWakeLock()
        _repromptProgress.value = IngestionProgress.EngineInitialising("Starting")
        startForeground(
            NOTIFICATION_ID_REPROMPT,
            buildRepromptNotification("Reprompting…", "Starting", transactionId, ongoing = true),
        )

        runJob = scope.launch {
            val app = applicationContext as SpendAiApp
            val now = System.currentTimeMillis()

            // Persist a job row up front. For a user-initiated
            // reprompt we insert a new PENDING row. For a cold-start
            // resume we reuse the existing row by id.
            val jobId: Long = if (resumeJobId != null) {
                app.repromptJobRepository.markAttempt(
                    id = resumeJobId,
                    status = RepromptJobStatus.RUNNING,
                    attemptCount = 1,
                    lastAttemptAt = now,
                )
                resumeJobId
            } else {
                app.repromptJobRepository.insert(
                    RepromptJob(
                        rawSmsIds = encodeIdList(rawSmsIds),
                        userPrompt = userPrompt,
                        transactionId = transactionId,
                        createdAt = now,
                        status = RepromptJobStatus.RUNNING.name,
                        attemptCount = 1,
                        lastAttemptAt = now,
                    )
                )
            }
            publishRepromptJobUpdate(app, transactionId, jobId)

            // Engine init.
            try {
                if (app.gemmaInferenceEngine.state.value !is InferenceState.Ready) {
                    _repromptProgress.value = IngestionProgress.EngineInitialising(
                        labelFor(app.gemmaInferenceEngine.state.value),
                    )
                    app.gemmaInferenceEngine.initialize(applicationContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Engine init failed for reprompt", t)
                finishReprompt(
                    app = app,
                    jobId = jobId,
                    transactionId = transactionId,
                    status = RepromptJobStatus.FAILED,
                    error = "Engine failed to initialise: ${t.message ?: t.javaClass.simpleName}",
                )
                return@launch
            }

            // Reprompt loop with transient-error retry.
            var attempt = 1
            var lastError: Throwable? = null
            while (attempt <= MAX_REPROMPT_ATTEMPTS) {
                if (attempt > 1) {
                    val waitSec = REPROMPT_RETRY_BACKOFF_MS / 1000
                    _repromptProgress.value = IngestionProgress.EngineInitialising(
                        "Retrying in ${waitSec}s (attempt $attempt/$MAX_REPROMPT_ATTEMPTS)",
                    )
                    delay(REPROMPT_RETRY_BACKOFF_MS)
                }

                app.repromptJobRepository.markAttempt(
                    id = jobId,
                    status = RepromptJobStatus.RUNNING,
                    attemptCount = attempt,
                    lastAttemptAt = System.currentTimeMillis(),
                )

                val outcome: IngestionOutcome = try {
                    app.ingestionPipeline.runWithReprompt(
                        rawSmsIds = rawSmsIds,
                        userPrompt = userPrompt,
                        emit = { publishRepromptProgress(it) },
                    )
                } catch (ce: CancellationException) {
                    Log.i(TAG, "Reprompt cancelled (jobId=$jobId)")
                    finishReprompt(
                        app = app,
                        jobId = jobId,
                        transactionId = transactionId,
                        status = RepromptJobStatus.FAILED,
                        error = "Cancelled",
                    )
                    throw ce
                } catch (t: Throwable) {
                    lastError = t
                    if (isTransientError(t) && attempt < MAX_REPROMPT_ATTEMPTS) {
                        Log.w(TAG, "Transient reprompt error (attempt $attempt); will retry", t)
                        attempt++
                        continue
                    }
                    Log.e(TAG, "Reprompt failed (jobId=$jobId)", t)
                    finishReprompt(
                        app = app,
                        jobId = jobId,
                        transactionId = transactionId,
                        status = RepromptJobStatus.FAILED,
                        error = t.message ?: t.javaClass.simpleName,
                    )
                    return@launch
                }

                when (outcome) {
                    is IngestionOutcome.Success -> {
                        Log.d(TAG, "Reprompt finished (jobId=$jobId): ${outcome.summary}")
                        finishReprompt(
                            app = app,
                            jobId = jobId,
                            transactionId = transactionId,
                            status = RepromptJobStatus.COMPLETED,
                            error = null,
                        )
                        return@launch
                    }
                    is IngestionOutcome.Failure -> {
                        val msg = outcome.message
                        // A "Failure" from the pipeline is the
                        // inner emit path (e.g. "No raw SMS ids
                        // supplied"); it is not a transient HTTP
                        // error so we don't retry.
                        Log.w(TAG, "Reprompt returned failure (jobId=$jobId): $msg")
                        finishReprompt(
                            app = app,
                            jobId = jobId,
                            transactionId = transactionId,
                            status = RepromptJobStatus.FAILED,
                            error = msg,
                        )
                        return@launch
                    }
                }
            }

            // Loop exited without returning: we ran out of retries.
            finishReprompt(
                app = app,
                jobId = jobId,
                transactionId = transactionId,
                status = RepromptJobStatus.FAILED,
                error = lastError?.message ?: "Transient errors exceeded ${MAX_REPROMPT_ATTEMPTS} attempts",
            )
        }
        return START_REDELIVER_INTENT
    }

    /**
     * Mark the job terminal, post the terminal notification, prune
     * the audit log, and stop the service. Idempotent: a duplicate
     * finish call on the same jobId is a no-op.
     */
    private suspend fun finishReprompt(
        app: SpendAiApp,
        jobId: Long,
        transactionId: Long?,
        status: RepromptJobStatus,
        error: String?,
    ) {
        val now = System.currentTimeMillis()
        app.repromptJobRepository.markTerminal(
            id = jobId,
            status = status,
            completedAt = now,
            errorMessage = error,
        )
        runCatching { app.repromptJobRepository.pruneToMostRecent() }
            .onFailure { Log.w(TAG, "RepromptJob prune failed", it) }
        publishRepromptJobUpdate(app, transactionId, jobId)

        val (title, text) = when (status) {
            RepromptJobStatus.COMPLETED -> "Reprompt done" to (error ?: "Completed")
            RepromptJobStatus.FAILED -> "Reprompt failed" to (error ?: "Unknown error")
            else -> "Reprompt" to ""
        }
        val notification = buildRepromptNotification(title, text, transactionId, ongoing = false)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_REPROMPT, notification)
        _repromptProgress.value = if (status == RepromptJobStatus.COMPLETED) {
            IngestionProgress.Done(IngestionSummary.EMPTY)
        } else {
            IngestionProgress.Failure(error ?: "Reprompt failed")
        }

        releaseBusy()
        stopSelfSafely()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "SpendAi::IngestionWakeLock"
        ).apply {
            setReferenceCounted(false)
            // No timeout — released manually by stopSelfSafely().
            acquire()
        }
    }

    private fun releaseBusy() {
        busy.set(false)
        runMode = null
    }

    private fun isTransientError(t: Throwable): Boolean {
        // Walk the cause chain so wrapped IOExceptions are caught.
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is IOException) {
                val msg = cur.message.orEmpty()
                if (TRANSIENT_ERROR_REGEX.containsMatchIn(msg)) return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun encodeIdList(ids: List<Long>): String = "[" + ids.joinToString(",") + "]"

    /**
     * Re-publish the latest job row for [transactionId] (if any)
     * on the process-scoped StateFlow so the edit screen can show
     * a "Reprompting…" banner. Called on every job state
     * transition.
     */
    private suspend fun publishRepromptJobUpdate(
        app: SpendAiApp,
        transactionId: Long?,
        jobId: Long,
    ) {
        val job = app.repromptJobRepository.getById(jobId) ?: return
        val current = _repromptJobsByTransactionId.value.toMutableMap()
        if (transactionId != null) {
            // Keep the latest job per transaction so the banner
            // only renders the most recent reprompt.
            if (job.status == RepromptJobStatus.COMPLETED.name ||
                job.status == RepromptJobStatus.FAILED.name
            ) {
                // Clear any stale entry for this transaction once
                // the run is terminal. The next reprompt will
                // re-populate the entry.
                current.remove(transactionId)
            } else {
                current[transactionId] = job
            }
        }
        _repromptJobsByTransactionId.value = current
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
        val notification = buildIngestNotification(title, text)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_INGEST, notification)
    }

    private suspend fun publishRepromptProgress(progress: IngestionProgress) {
        _repromptProgress.value = progress
        val (title, text) = when (progress) {
            is IngestionProgress.EngineInitialising -> "Reprompting" to progress.currentState
            is IngestionProgress.LoadingFromSource -> "Reprompting" to
                "Loaded ${progress.seenSoFar} messages"
            is IngestionProgress.MessageParsed -> "Reprompting" to
                "Parsed ${progress.messageIndex + 1}/${progress.totalMessages}"
            is IngestionProgress.MessageCommitted -> "Reprompting" to
                "Committed ${progress.messageIndex + 1}/${progress.totalMessages}"
            is IngestionProgress.MessageSkipped -> "Reprompt" to
                "Skipped ${progress.messageIndex + 1}/${progress.totalMessages}: ${progress.reason}"
            is IngestionProgress.Done -> "Reprompt done" to doneLine(progress)
            is IngestionProgress.Failure -> "Reprompt failed" to progress.message
            is IngestionProgress.Cancelled -> "Reprompt cancelled" to ""
            IngestionProgress.Idle -> "Reprompt" to ""
        }
        val currentMode = runMode
        val transactionId = (currentMode as? RunMode.Reprompt)?.transactionId
        val notification = buildRepromptNotification(title, text, transactionId, ongoing = true)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_REPROMPT, notification)
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

    private fun buildIngestNotification(title: String, text: String): android.app.Notification {
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

    private fun buildRepromptNotification(
        title: String,
        text: String,
        transactionId: Long?,
        ongoing: Boolean,
    ): android.app.Notification {
        val cancelIntent = Intent(this, IngestionService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_cartoon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (ongoing) {
            builder.addAction(
                R.drawable.ic_cross_cartoon,
                "Cancel",
                cancelPending,
            )
        } else if (transactionId != null) {
            // Terminal notification: deep-link to the transaction
            // so the user can re-inspect the result without
            // searching for it.
            val openIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_TRANSACTION
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                this, transactionId.toInt(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(openPending)
            builder.setAutoCancel(true)
            builder.addAction(
                R.drawable.ic_sms_cartoon,
                "Open transaction",
                openPending,
            )
        }
        return builder.build()
    }

    /**
     * Cold-start scan: pick up [RepromptJob] rows that are still
     * PENDING or RUNNING but whose [RepromptJob.lastAttemptAt] is
     * older than [REPROMPT_STALE_AFTER_MS]. Re-drives the most
     * recent one through the service. A no-op when the service is
     * already busy (e.g. an ingestion is in flight).
     */
    private fun scanStaleRepromptJobs() {
        if (busy.get()) return
        val app = applicationContext as SpendAiApp
        val cutoff = System.currentTimeMillis() - REPROMPT_STALE_AFTER_MS
        val stale = runCatching {
            kotlinx.coroutines.runBlocking { app.repromptJobRepository.getStale(cutoff) }
        }.getOrDefault(emptyList())
        val next = stale.lastOrNull() ?: return
        Log.i(TAG, "Cold-start scan resuming reprompt jobId=${next.id}")
        val rawIds = decodeIdList(next.rawSmsIds)
        if (rawIds.isEmpty() || next.userPrompt.isBlank()) {
            // Stale row is corrupt (no ids / no prompt). Mark it
            // failed so the next scan does not loop on it.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    app.repromptJobRepository.markTerminal(
                        id = next.id,
                        status = RepromptJobStatus.FAILED,
                        completedAt = System.currentTimeMillis(),
                        errorMessage = "Stale reprompt missing ids or prompt",
                    )
                }
            }.onFailure { Log.w(TAG, "Failed to mark corrupt stale job as failed", it) }
            return
        }
        val resumeIntent = Intent(this, IngestionService::class.java).apply {
            action = ACTION_REPROMPT
            putExtra(EXTRA_REPROMPT_RAW_SMS_IDS, rawIds.toLongArray())
            putExtra(EXTRA_REPROMPT_USER_PROMPT, next.userPrompt)
            putExtra(EXTRA_REPROMPT_TRANSACTION_ID, next.transactionId ?: -1L)
            putExtra(EXTRA_REPROMPT_RESUME_JOB_ID, next.id)
        }
        try {
            ContextCompat.startForegroundService(this, resumeIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start service for stale reprompt jobId=${next.id}", t)
            runCatching {
                kotlinx.coroutines.runBlocking {
                    app.repromptJobRepository.markTerminal(
                        id = next.id,
                        status = RepromptJobStatus.FAILED,
                        completedAt = System.currentTimeMillis(),
                        errorMessage = "Failed to resume: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun decodeIdList(json: String): List<Long> {
        if (json.isBlank()) return emptyList()
        val trimmed = json.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    /**
     * Discriminated union over the two kinds of run the service
     * drives. Used by the re-entrancy guard and [ACTION_CANCEL] to
     * know what is in flight.
     */
    private sealed class RunMode {
        data class Ingestion(val subMode: String) : RunMode()
        data class Reprompt(
            val transactionId: Long?,
            val rawSmsIds: List<Long>,
        ) : RunMode()
    }

    companion object {
        private const val TAG = "IngestionService"
        private const val NOTIFICATION_ID_INGEST = 1001
        private const val NOTIFICATION_ID_REPROMPT = 1002
        private const val CHANNEL_ID = "spendai.ingest"
        const val ACTION_CANCEL = "com.spendai.app.action.INGEST_CANCEL"
        const val ACTION_INGEST_PENDING = "com.spendai.app.action.INGEST_PENDING"
        const val ACTION_REPROMPT = "com.spendai.app.action.REPROMPT"
        const val EXTRA_START_MILLIS = "spendai.extra.START_MILLIS"
        const val EXTRA_END_MILLIS = "spendai.extra.END_MILLIS"
        const val EXTRA_REPROMPT_RAW_SMS_IDS = "spendai.extra.REPROMPT_RAW_SMS_IDS"
        const val EXTRA_REPROMPT_USER_PROMPT = "spendai.extra.REPROMPT_USER_PROMPT"
        const val EXTRA_REPROMPT_TRANSACTION_ID = "spendai.extra.REPROMPT_TRANSACTION_ID"
        const val EXTRA_REPROMPT_RESUME_JOB_ID = "spendai.extra.REPROMPT_RESUME_JOB_ID"

        /**
         * Max retry attempts on a transient (429 / 5xx) LLM error.
         * `@JvmField var` so tests can override it via reflection
         * (the field is a real mutable static on the enclosing
         * class, not a `final val` that the JVM would refuse to
         * write to).
         */
        @JvmField var MAX_REPROMPT_ATTEMPTS: Int = 3

        /**
         * Wait between transient retries. Matches the engine's
         * internal backoff. `@JvmField var` so tests can shorten
         * it without waiting minutes for a retry loop to finish.
         */
        @JvmField var REPROMPT_RETRY_BACKOFF_MS: Long = 60_000L

        /**
         * A reprompt job is considered "stale" (and eligible for
         * cold-start re-drive) when its last attempt is older than
         * this. Picked at 10 minutes so an actively-running reprompt
         * (which can take longer than 5 minutes on a slow Gemini
         * response) is not accidentally re-driven. `@JvmField var`
         * so tests can shrink the threshold.
         */
        @JvmField var REPROMPT_STALE_AFTER_MS: Long = 10 * 60 * 1000L

        private val TRANSIENT_ERROR_REGEX = Regex("\\b(429|500|502|503|504)\\b")

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

        /**
         * Reprompt-scoped progress stream. Mirrors [progress] but
         * only emits events from a reprompt run, so the edit screen
         * can collect its own run without mixing with ingestion
         * events.
         */
        private val _repromptProgress = MutableStateFlow<IngestionProgress>(IngestionProgress.Idle)
        val repromptProgress: StateFlow<IngestionProgress> = _repromptProgress.asStateFlow()

        /**
         * Process-scoped cache of the latest non-terminal reprompt
         * job per [com.spendai.app.data.local.entity.RepromptJob.transactionId].
         * The edit screen collects this to render the
         * "Reprompting…" banner even if the user navigated away
         * and back. Cleared on terminal completion.
         */
        private val _repromptJobsByTransactionId =
            MutableStateFlow<Map<Long, RepromptJob>>(emptyMap())
        val repromptJobsByTransactionId: StateFlow<Map<Long, RepromptJob>> =
            _repromptJobsByTransactionId.asStateFlow()

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

        /**
         * Start a per-transaction A3 reprompt. The service posts
         * progress to [repromptProgress] and [repromptJobsByTransactionId]
         * so the edit screen can render the result and the running
         * banner.
         */
        fun startReprompt(
            context: Context,
            rawSmsIds: List<Long>,
            userPrompt: String,
            transactionId: Long? = null,
        ) {
            val intent = Intent(context, IngestionService::class.java).apply {
                action = ACTION_REPROMPT
                putExtra(EXTRA_REPROMPT_RAW_SMS_IDS, rawSmsIds.toLongArray())
                putExtra(EXTRA_REPROMPT_USER_PROMPT, userPrompt)
                if (transactionId != null) {
                    putExtra(EXTRA_REPROMPT_TRANSACTION_ID, transactionId)
                }
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

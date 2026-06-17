package com.spendai.app.domain.ingestion

import android.util.Log
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.IngestionLog
import com.spendai.app.data.local.entity.IngestionLogA1
import com.spendai.app.data.local.entity.IngestionLogA2
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.repository.IngestionLogRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.domain.agent.A1Outcome
import com.spendai.app.domain.agent.A2FailureException
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single owner of the A1→A2 agent loop. Pure (no Android UI,
 * no notification, no WorkManager) so it can be unit-tested with a
 * mocked engine and a `ListSmsSource`.
 *
 * Two callers:
 *  - [com.spendai.app.worker.DailyParsingWorker] — uses
 *    `DatabaseSmsSource` and an unbounded range to drain whatever's
 *    pending after the receiver captured new SMS.
 *  - [com.spendai.app.service.IngestionService] — uses
 *    `ContentResolverSmsSource` and a user-picked date range for
 *    foreground historical ingestion.
 *
 * ## Per-message flow (Phase 3+)
 *
 * The pipeline is now a flat loop over unparsed SMS rows. A1 parses
 * (or reuses a cached `parsed_sms` row) and A2 resolves the
 * entities AND commits the `spend_transaction` row in a single
 * per-message call. The previous day-batched commit step (A3) is
 * gone — each message becomes a real row as soon as A2 returns.
 *
 * ## Cache-hit retry
 *
 * A message whose `parsed_sms` row exists but has no transaction is
 * re-fed to A2 on the next run. The previous version skipped these
 * permanently to avoid loops, but that left stuck messages with
 * no audit trail beyond "(no prompt/response captured)". We now
 * actually re-run A2 and log the new attempt — the audit pane
 * shows the most recent try, so the user can see if the model is
 * still failing or has recovered.
 *
 * ## Audit log
 *
 * Every processed message gets one [IngestionLog] row capturing
 * the A1/A2 prompts, raw model responses, outcomes, and any skip
 * reasons. Retention is enforced at the end of each run via
 * [IngestionLogRepository.pruneToMostRecent].
 *
 * Idempotent: A1 is skipped when `parsedSmsRepository.getByRawSms(id)`
 * already has a row (re-running the same range is a no-op), with one
 * exception: rows matching the synthetic-IGNORE signature
 * (empty `a1RawJson`, `a1Confidence = 0.0`) are treated as a cache
 * miss and re-parsed, per the v3 fix.
 */
class IngestionPipeline(
    private val database: AppDatabase,
    private val smsRepository: SmsRepository,
    private val parsedSmsRepository: ParsedSmsRepository,
    private val ingestionLogRepository: IngestionLogRepository,
    private val agent1: Agent1SmsParser,
    private val agent2: Agent2EntityResolver,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun run(
        source: SmsSource,
        range: DateRange,
        emit: suspend (IngestionProgress) -> Unit,
    ): IngestionOutcome = withContext(Dispatchers.IO) {
        var loadedCount = 0
        var parsedCount = 0
        var ignoredCount = 0
        var committedCount = 0
        var skippedByA1 = 0
        var skippedByA2 = 0
        var lastDay: LocalDate? = null

        try {
            val loaded = source.load(range) { msg ->
                smsRepository.insert(msg)
                loadedCount++
                emit(IngestionProgress.LoadingFromSource(loadedCount))
            }
            Log.d(TAG, "Source loaded $loaded messages (loadedCount=$loadedCount range=$range)")

            val unparsed = if (range == DateRange.unbounded()) {
                smsRepository.unparsedOnce()
            } else {
                smsRepository.unparsedInRange(range.startMillis, range.endMillis)
            }
            if (unparsed.isEmpty()) {
                emit(IngestionProgress.Done(IngestionSummary.EMPTY))
                return@withContext IngestionOutcome.Success(IngestionSummary.EMPTY)
            }

            val totalMessages = unparsed.size
            unparsed.forEachIndexed { messageIndex, message ->
                val day = range.toLocalDate(message.timestamp, zone)
                if (day != lastDay) {
                    Log.d(TAG, "starting day=$day messagesRemaining=$totalMessages")
                    lastDay = day
                }
                processMessage(
                    message = message,
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    emit = emit,
                    onParsed = { parsedCount++ },
                    onIgnored = { ignoredCount++ },
                    onCommitted = { committedCount++ },
                    onSkippedByA1 = { skippedByA1++ },
                    onSkippedByA2 = { skippedByA2++ },
                )
            }

            runCatching { ingestionLogRepository.pruneToMostRecent() }
                .onFailure { Log.w(TAG, "IngestionLog prune failed", it) }

            val summary = IngestionSummary(
                totalMessages = totalMessages,
                parsed = parsedCount,
                ignored = ignoredCount,
                skippedByA1 = skippedByA1,
                skippedByA2 = skippedByA2,
                committedTransactions = committedCount,
            )
            emit(IngestionProgress.Done(summary))
            IngestionOutcome.Success(summary)
        } catch (ce: CancellationException) {
            Log.i(TAG, "Pipeline cancelled")
            emit(IngestionProgress.Cancelled)
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline failed", t)
            emit(IngestionProgress.Failure(t.message ?: t.javaClass.simpleName))
            IngestionOutcome.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun processMessage(
        message: RawSmsMessage,
        messageIndex: Int,
        totalMessages: Int,
        emit: suspend (IngestionProgress) -> Unit,
        onParsed: () -> Unit,
        onIgnored: () -> Unit,
        onCommitted: () -> Unit,
        onSkippedByA1: () -> Unit,
        onSkippedByA2: () -> Unit,
    ) {
        val ingestedAt = System.currentTimeMillis()

        val cached = parsedSmsRepository.getByRawSms(message.id)
        val isSyntheticIgnore = cached != null &&
            cached.kind == ParsedSmsKind.IGNORE.name &&
            cached.a1RawJson.isEmpty() &&
            cached.a1Confidence == 0.0f

        val a1: com.spendai.app.domain.agent.A1Outcome? = if (cached != null && !isSyntheticIgnore) {
            null
        } else {
            if (isSyntheticIgnore) {
                Log.i(
                    TAG,
                    "Re-parsing synthetic IGNORE for rawSmsId=${message.id} " +
                        "from previous run; deleting the placeholder row.",
                )
                parsedSmsRepository.deleteByRawSms(message.id)
            }
            try {
                agent1.parse(message)
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "A1 failed for rawSmsId=${message.id} (${t.message}); skipping",
                    t,
                )
                emit(
                    IngestionProgress.MessageSkipped(
                        messageIndex = messageIndex,
                        totalMessages = totalMessages,
                        reason = t.message ?: t.javaClass.simpleName,
                    ),
                )
                recordLog(
                    rawSmsId = message.id,
                    parsedSmsId = null,
                    transactionId = null,
                    ingestedAt = ingestedAt,
                    a1Outcome = IngestionLogA1.SKIPPED_A1,
                    a1Error = t.message ?: t.javaClass.simpleName,
                )
                onSkippedByA1()
                return
            }
        }
        if (a1 == null) {
            if (cached != null && !isSyntheticIgnore) {
                handleCacheHit(
                    message = message,
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    emit = emit,
                    ingestedAt = ingestedAt,
                    cached = cached,
                    onParsed = onParsed,
                    onIgnored = onIgnored,
                    onCommitted = onCommitted,
                    onSkippedByA2 = onSkippedByA2,
                )
                return
            }
            throw IllegalStateException("Engine became unready mid-run")
        }

        emit(
            IngestionProgress.MessageParsed(
                messageIndex = messageIndex,
                totalMessages = totalMessages,
                kind = a1.parsed.kind,
            )
        )
        smsRepository.setParsedSmsId(message.id, a1.parsed.id)
        if (a1.parsed.kind == ParsedSmsKind.IGNORE.name) {
            smsRepository.markIgnored(message.id)
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = a1.parsed.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.IGNORE,
                a1Prompt = a1.prompt,
                a1Response = a1.response,
                a1Confidence = a1.parsed.a1Confidence,
                a2Outcome = IngestionLogA2.NOT_RUN,
            )
            onIgnored()
            return
        }
        onParsed()

        val a2 = try {
            agent2.resolveAndCommit(a1.parsed)
        } catch (e: A2FailureException) {
            Log.w(TAG, "A2 failed for parsedSmsId=${a1.parsed.id}; skipping", e)
            emit(
                IngestionProgress.MessageSkipped(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    reason = e.message ?: e.javaClass.simpleName,
                )
            )
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = a1.parsed.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.OK,
                a1Prompt = a1.prompt,
                a1Response = a1.response,
                a1Confidence = a1.parsed.a1Confidence,
                a2Outcome = IngestionLogA2.SKIPPED_A2,
                a2Prompt = e.prompt,
                a2Response = e.response,
                a2Error = e.message ?: e.javaClass.simpleName,
            )
            onSkippedByA2()
            return
        } catch (t: Throwable) {
            // A2FailureException is the expected failure shape; any
            // other throw is a bug (or an engine exception that the
            // resolver did not wrap). Log it as-is — the prompt
            // is unrecoverable here.
            Log.w(TAG, "A2 unexpected throw for parsedSmsId=${a1.parsed.id}; skipping", t)
            emit(
                IngestionProgress.MessageSkipped(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    reason = t.message ?: t.javaClass.simpleName,
                )
            )
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = a1.parsed.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.OK,
                a1Prompt = a1.prompt,
                a1Response = a1.response,
                a1Confidence = a1.parsed.a1Confidence,
                a2Outcome = IngestionLogA2.SKIPPED_A2,
                a2Error = t.message ?: t.javaClass.simpleName,
            )
            onSkippedByA2()
            return
        }

        emit(
            IngestionProgress.MessageCommitted(
                messageIndex = messageIndex,
                totalMessages = totalMessages,
                transactionId = a2.transactionId,
            )
        )
        smsRepository.markParsed(message.id)
        recordLog(
            rawSmsId = message.id,
            parsedSmsId = a1.parsed.id,
            transactionId = a2.transactionId,
            ingestedAt = ingestedAt,
            a1Outcome = IngestionLogA1.OK,
            a1Prompt = a1.prompt,
            a1Response = a1.response,
            a1Confidence = a1.parsed.a1Confidence,
            a2Outcome = IngestionLogA2.COMMITTED,
            a2Prompt = a2.prompt,
            a2Response = a2.response,
            a2Confidence = a2.a2Confidence,
        )
        onCommitted()
    }

    /**
     * A1 was a cache hit (the `parsed_sms` row was already
     * committed by an earlier run). Re-feed A2 if there's no
     * transaction yet — that means A2 either failed or never
     * ran for this message. The retry captures the new
     * A2 prompt/response in the audit log so the user can see
     * whether the model recovered or is still failing.
     */
    private suspend fun handleCacheHit(
        message: RawSmsMessage,
        messageIndex: Int,
        totalMessages: Int,
        emit: suspend (IngestionProgress) -> Unit,
        ingestedAt: Long,
        cached: com.spendai.app.data.local.entity.ParsedSms,
        onParsed: () -> Unit,
        onIgnored: () -> Unit,
        onCommitted: () -> Unit,
        onSkippedByA2: () -> Unit,
    ) {
        emit(
            IngestionProgress.MessageParsed(
                messageIndex = messageIndex,
                totalMessages = totalMessages,
                kind = cached.kind,
            )
        )
        smsRepository.setParsedSmsId(message.id, cached.id)
        if (cached.kind == ParsedSmsKind.IGNORE.name) {
            smsRepository.markIgnored(message.id)
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = cached.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.IGNORE,
                a1Confidence = cached.a1Confidence,
                a2Outcome = IngestionLogA2.NOT_RUN,
            )
            onIgnored()
            return
        }
        onParsed()

        val txnRepo = com.spendai.app.data.repository.TransactionRepository(
            database.transactionDao()
        )
        val existingTxn = txnRepo.getByParsedSms(cached.id)
        if (existingTxn != null) {
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = cached.id,
                transactionId = existingTxn.id,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.OK,
                a1Confidence = cached.a1Confidence,
                a1Response = cached.a1RawJson.ifBlank { null },
                a2Outcome = IngestionLogA2.COMMITTED,
                a2Confidence = existingTxn.confidence,
            )
            emit(
                IngestionProgress.MessageCommitted(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    transactionId = existingTxn.id,
                )
            )
            smsRepository.markParsed(message.id)
            onCommitted()
            return
        }

        // Cache hit but A2 never produced a transaction. Retry.
        // The original A1 model output is stored on
        // parsed_sms.a1RawJson (may be empty in pathological
        // cases) — surface it as the A1 response so the audit
        // row isn't blank. The A1 prompt isn't persisted
        // anywhere, so the audit row leaves it null.
        val a2 = try {
            agent2.resolveAndCommit(cached)
        } catch (e: A2FailureException) {
            Log.w(
                TAG,
                "A2 retry on cache hit failed for parsedSmsId=${cached.id}; skipping",
                e,
            )
            emit(
                IngestionProgress.MessageSkipped(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    reason = "A2 retry: ${e.message ?: e.javaClass.simpleName}",
                )
            )
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = cached.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.OK,
                a1Confidence = cached.a1Confidence,
                a1Response = cached.a1RawJson.ifBlank { null },
                a2Outcome = IngestionLogA2.SKIPPED_A2,
                a2Prompt = e.prompt,
                a2Response = e.response,
                a2Error = e.message ?: e.javaClass.simpleName,
            )
            onSkippedByA2()
            return
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "A2 retry on cache hit had unexpected throw for parsedSmsId=${cached.id}; skipping",
                t,
            )
            emit(
                IngestionProgress.MessageSkipped(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    reason = "A2 retry: ${t.message ?: t.javaClass.simpleName}",
                )
            )
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = cached.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.OK,
                a1Confidence = cached.a1Confidence,
                a1Response = cached.a1RawJson.ifBlank { null },
                a2Outcome = IngestionLogA2.SKIPPED_A2,
                a2Error = t.message ?: t.javaClass.simpleName,
            )
            onSkippedByA2()
            return
        }

        emit(
            IngestionProgress.MessageCommitted(
                messageIndex = messageIndex,
                totalMessages = totalMessages,
                transactionId = a2.transactionId,
            )
        )
        smsRepository.markParsed(message.id)
        recordLog(
            rawSmsId = message.id,
            parsedSmsId = cached.id,
            transactionId = a2.transactionId,
            ingestedAt = ingestedAt,
            a1Outcome = IngestionLogA1.OK,
            a1Confidence = cached.a1Confidence,
            a1Response = cached.a1RawJson.ifBlank { null },
            a2Outcome = IngestionLogA2.COMMITTED,
            a2Prompt = a2.prompt,
            a2Response = a2.response,
            a2Confidence = a2.a2Confidence,
        )
        onCommitted()
    }

    private suspend fun recordLog(
        rawSmsId: Long,
        parsedSmsId: Long?,
        transactionId: Long?,
        ingestedAt: Long,
        a1Outcome: String,
        a1Prompt: String? = null,
        a1Response: String? = null,
        a1Error: String? = null,
        a1Confidence: Float? = null,
        a2Outcome: String? = null,
        a2Prompt: String? = null,
        a2Response: String? = null,
        a2Error: String? = null,
        a2Confidence: Float? = null,
    ) {
        runCatching {
            ingestionLogRepository.insert(
                IngestionLog(
                    rawSmsId = rawSmsId,
                    parsedSmsId = parsedSmsId,
                    transactionId = transactionId,
                    ingestedAt = ingestedAt,
                    a1Outcome = a1Outcome,
                    a1Prompt = a1Prompt,
                    a1Response = a1Response,
                    a1Error = a1Error,
                    a1Confidence = a1Confidence,
                    a2Outcome = a2Outcome,
                    a2Prompt = a2Prompt,
                    a2Response = a2Response,
                    a2Error = a2Error,
                    a2Confidence = a2Confidence,
                )
            )
        }.onFailure { Log.w(TAG, "IngestionLog insert failed for rawSmsId=$rawSmsId", it) }
    }

    private companion object {
        const val TAG = "IngestionPipeline"
    }
}

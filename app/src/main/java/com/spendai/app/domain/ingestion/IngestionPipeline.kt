package com.spendai.app.domain.ingestion

import android.util.Log
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.IngestionLog
import com.spendai.app.data.local.entity.IngestionLogA1
import com.spendai.app.data.local.entity.IngestionLogA2
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.repository.IngestionLogRepository
import com.spendai.app.data.repository.ManualCorrectionRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.domain.agent.A1Outcome
import com.spendai.app.domain.agent.A2FailureException
import com.spendai.app.domain.agent.A3FailureException
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import com.spendai.app.domain.agent.Agent3Auditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single owner of the A1→A2→A3 agent loop. Pure (no Android UI,
 * no notification, no WorkManager) so it can be unit-tested with a
 * mocked engine and a `ListSmsSource`.
 *
 * Two callers:
 *  - [com.spendai.app.service.IngestionService] — uses
 *    `ContentResolverSmsSource` for foreground historical ingestion
 *    and `DatabaseSmsSource` for "re-process pending".
 *  - [com.spendai.app.worker.DailyParsingWorker] — the per-message
 *    retry path delegates to [runOne].
 *
 * ## Per-message flow
 *
 * The pipeline is a flat loop over the input SMS rows. A1 parses
 * (or reuses a cached `parsed_sms` row), A2 resolves the candidate
 * transaction entities, and A3 audits the candidate, double-checks
 * for mistakes, and commits it in real-time.
 *
 * ## Idempotency (v6)
 *
 * The "pending" query is `status = UNPARSED AND processedAt IS NULL`.
 * Terminal state transitions:
 *
 *  - TRANSACTION committed → `markProcessed` (status=PARSED,
 *    processedAt=now, lastError=null).
 *  - A1 says IGNORE → `markIgnoredProcessed` (status=IGNORED,
 *    processedAt=now, lastError=null).
 *  - A1 or A2 throws → `markSkipped` (status stays UNPARSED,
 *    processedAt stays null, lastError=reason). A future run
 *    picks the row up again.
 *
 * A row that has `processedAt` set is never re-picked by the
 * pending query, so retries are safe even after a partial run
 * gets killed by Doze / OOM mid-loop.
 */
class IngestionPipeline(
    private val database: AppDatabase,
    private val smsRepository: SmsRepository,
    private val parsedSmsRepository: ParsedSmsRepository,
    private val ingestionLogRepository: IngestionLogRepository,
    private val manualCorrectionRepository: ManualCorrectionRepository? = null,
    private val agent1: Agent1SmsParser,
    private val agent2: Agent2EntityResolver,
    private val agent3: Agent3Auditor,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Run the pipeline over the rows the [source] yields for [range],
     * then process the in-DB pending rows that fall inside [range].
     */
    suspend fun run(
        source: SmsSource,
        range: DateRange,
        emit: suspend (IngestionProgress) -> Unit,
    ): IngestionOutcome = withContext(Dispatchers.IO) {
        var loadedCount = 0
        try {
            source.load(range) { msg ->
                smsRepository.insert(msg)
                loadedCount++
                emit(IngestionProgress.LoadingFromSource(loadedCount))
            }
            Log.d(TAG, "Source loaded $loadedCount messages (range=$range)")

            val pending = smsRepository.pendingInRange(range.startMillis, range.endMillis)
            if (pending.isEmpty()) {
                emit(IngestionProgress.Done(IngestionSummary.EMPTY))
                return@withContext IngestionOutcome.Success(IngestionSummary.EMPTY)
            }
            val summary = processMessages(pending, emit)
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

    /**
     * Reprompt path. Persists a [com.spendai.app.data.local.entity.ManualCorrection]
     * with the user-typed prompt, then re-runs A3 on every supplied raw_sms id,
     * passing the user-typed prompt as the override for every call. The pipeline
     * is naturally idempotent: re-running A3 with a new decision un-commits or
     * modifies prior transactions through its `modifications` list, so a single
     * reprompt can fix the source row, the linked duplicates, and the link
     * edges in one go.
     */
    suspend fun runWithReprompt(
        rawSmsIds: List<Long>,
        userPrompt: String,
        emit: suspend (IngestionProgress) -> Unit,
    ): IngestionOutcome = withContext(Dispatchers.IO) {
        try {
            if (rawSmsIds.isEmpty()) {
                emit(IngestionProgress.Failure("No raw SMS ids supplied to reprompt"))
                return@withContext IngestionOutcome.Failure("No raw SMS ids supplied to reprompt")
            }
            if (userPrompt.isBlank()) {
                emit(IngestionProgress.Failure("Reprompt prompt was blank"))
                return@withContext IngestionOutcome.Failure("Reprompt prompt was blank")
            }
            val messages = rawSmsIds.mapNotNull { smsRepository.getById(it) }
            if (messages.isEmpty()) {
                emit(IngestionProgress.Failure("None of $rawSmsIds matched a raw SMS row"))
                return@withContext IngestionOutcome.Failure("None of $rawSmsIds matched a raw SMS row")
            }
            val now = System.currentTimeMillis()
            val linkedIds = rawSmsIds.filter { it != messages.first().id }
            val linkedJson = "[" + linkedIds.joinToString(",") + "]"
            if (manualCorrectionRepository != null) {
                runCatching {
                    manualCorrectionRepository.insert(
                        com.spendai.app.data.local.entity.ManualCorrection(
                            rawSmsId = messages.first().id,
                            linkedSmsIds = linkedJson,
                            userPrompt = userPrompt,
                            createdAt = now,
                        )
                    )
                }.onFailure { Log.w(TAG, "ManualCorrection insert failed", it) }
            }
            val summary = processMessages(messages, emit, overridePrompt = userPrompt)
            emit(IngestionProgress.Done(summary))
            IngestionOutcome.Success(summary)
        } catch (ce: CancellationException) {
            Log.i(TAG, "runWithReprompt cancelled")
            emit(IngestionProgress.Cancelled)
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "runWithReprompt failed", t)
            emit(IngestionProgress.Failure(t.message ?: t.javaClass.simpleName))
            IngestionOutcome.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Re-process every pending row in the DB, regardless of range.
     * Backs the "Re-process pending" CTA and the periodic
     * WorkManager safety net.
     */
    suspend fun runPending(
        emit: suspend (IngestionProgress) -> Unit,
    ): IngestionOutcome = withContext(Dispatchers.IO) {
        try {
            val pending = smsRepository.pendingOnce()
            if (pending.isEmpty()) {
                emit(IngestionProgress.Done(IngestionSummary.EMPTY))
                return@withContext IngestionOutcome.Success(IngestionSummary.EMPTY)
            }
            Log.d(TAG, "runPending found ${pending.size} messages to re-process")
            val summary = processMessages(pending, emit)
            emit(IngestionProgress.Done(summary))
            IngestionOutcome.Success(summary)
        } catch (ce: CancellationException) {
            Log.i(TAG, "runPending cancelled")
            emit(IngestionProgress.Cancelled)
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "runPending failed", t)
            emit(IngestionProgress.Failure(t.message ?: t.javaClass.simpleName))
            IngestionOutcome.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Per-message retry path. Used by the debug log's "Retry" button
     * for messages stuck in a SKIPPED_A1 / SKIPPED_A2 loop.
     */
    suspend fun runOne(
        rawSmsId: Long,
        emit: suspend (IngestionProgress) -> Unit,
    ): IngestionOutcome = withContext(Dispatchers.IO) {
        try {
            val message = smsRepository.getById(rawSmsId)
            if (message == null) {
                emit(IngestionProgress.Failure("rawSmsId=$rawSmsId not found"))
                return@withContext IngestionOutcome.Failure("rawSmsId=$rawSmsId not found")
            }
            val summary = processMessages(listOf(message), emit)
            emit(IngestionProgress.Done(summary))
            IngestionOutcome.Success(summary)
        } catch (ce: CancellationException) {
            Log.i(TAG, "runOne cancelled")
            emit(IngestionProgress.Cancelled)
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "runOne failed", t)
            emit(IngestionProgress.Failure(t.message ?: t.javaClass.simpleName))
            IngestionOutcome.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun processMessages(
        pending: List<RawSmsMessage>,
        emit: suspend (IngestionProgress) -> Unit,
        overridePrompt: String? = null,
    ): IngestionSummary {
        var parsedCount = 0
        var ignoredCount = 0
        var committedCount = 0
        var skippedByA1 = 0
        var skippedByA2 = 0
        var lastDay: LocalDate? = null
        val totalMessages = pending.size

        pending.forEachIndexed { messageIndex, message ->
            val day = instantToLocalDate(message.timestamp)
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
                overridePrompt = overridePrompt,
            )
        }

        runCatching { ingestionLogRepository.pruneToMostRecent() }
            .onFailure { Log.w(TAG, "IngestionLog prune failed", it) }
        manualCorrectionRepository?.let { repo ->
            runCatching { repo.pruneToMostRecent() }
                .onFailure { Log.w(TAG, "ManualCorrection prune failed", it) }
        }

        return IngestionSummary(
            totalMessages = totalMessages,
            parsed = parsedCount,
            ignored = ignoredCount,
            skippedByA1 = skippedByA1,
            skippedByA2 = skippedByA2,
            committedTransactions = committedCount,
        )
    }

    private fun instantToLocalDate(timestamp: Long): LocalDate =
        java.time.Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

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
        overridePrompt: String? = null,
    ) {
        val ingestedAt = System.currentTimeMillis()
        val userPrompt = overridePrompt

        val cached = parsedSmsRepository.getByRawSms(message.id)
        val isSyntheticIgnore = cached != null &&
            cached.kind == ParsedSmsKind.IGNORE.name &&
            cached.a1RawJson.isEmpty() &&
            cached.a1Confidence == 0.0f

        val a1: A1Outcome? = if (cached != null && !isSyntheticIgnore) {
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
                smsRepository.markSkipped(message.id, t.message ?: t.javaClass.simpleName)
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
                    userPrompt = userPrompt,
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
                    overridePrompt = overridePrompt,
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
            smsRepository.markIgnoredProcessed(message.id, ingestedAt)
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
                userPrompt = userPrompt,
            )
            onIgnored()
            return
        }
        onParsed()

        val a2Candidate = try {
            agent2.resolveCandidate(a1.parsed, smsTimestampMillis = message.timestamp)
        } catch (e: A2FailureException) {
            Log.w(TAG, "A2 failed for parsedSmsId=${a1.parsed.id}; skipping", e)
            smsRepository.markSkipped(message.id, e.message ?: e.javaClass.simpleName)
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
                userPrompt = userPrompt,
            )
            onSkippedByA2()
            return
        } catch (t: Throwable) {
            Log.w(TAG, "A2 unexpected throw for parsedSmsId=${a1.parsed.id}; skipping", t)
            smsRepository.markSkipped(message.id, t.message ?: t.javaClass.simpleName)
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
                userPrompt = userPrompt,
            )
            return
        }

        val a3 = try {
            agent3.reviewAndCommit(
                candidate = a2Candidate.candidate,
                rawSmsId = message.id,
                rawSmsText = message.msgBody,
                a2Prompt = a2Candidate.prompt,
                a2Response = a2Candidate.response
            )
        } catch (e: A3FailureException) {
            Log.w(TAG, "A3 failed for parsedSmsId=${a1.parsed.id}; skipping", e)
            smsRepository.markSkipped(message.id, e.message ?: e.javaClass.simpleName)
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
                a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}\n\nA3 Prompt:\n${e.prompt}",
                a2Response = "A2 Response:\n${a2Candidate.response}\n\nA3 Response:\n${e.response}",
                a2Error = e.message ?: e.javaClass.simpleName,
                userPrompt = userPrompt,
            )
            onSkippedByA2()
            return
        } catch (t: Throwable) {
            Log.w(TAG, "A3 unexpected throw for parsedSmsId=${a1.parsed.id}; skipping", t)
            smsRepository.markSkipped(message.id, t.message ?: t.javaClass.simpleName)
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
                a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}",
                a2Response = "A2 Response:\n${a2Candidate.response}",
                a2Error = t.message ?: t.javaClass.simpleName,
                userPrompt = userPrompt,
            )
            onSkippedByA2()
            return
        }

        if (a3.isIgnored) {
            smsRepository.markIgnoredProcessed(message.id, ingestedAt)
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = a1.parsed.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.OK,
                a1Prompt = a1.prompt,
                a1Response = a1.response,
                a1Confidence = a1.parsed.a1Confidence,
                a2Outcome = IngestionLogA2.NOT_RUN,
                a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}\n\nA3 Prompt:\n${a3.prompt}",
                a2Response = "A2 Response:\n${a2Candidate.response}\n\nA3 Response:\n${a3.response}",
                a2Confidence = a2Candidate.contract.a2Confidence,
                userPrompt = userPrompt,
            )
            onIgnored()
            return
        }

        emit(
            IngestionProgress.MessageCommitted(
                messageIndex = messageIndex,
                totalMessages = totalMessages,
                transactionId = a3.transactionId,
            )
        )
        smsRepository.markProcessed(message.id, ingestedAt)
        recordLog(
            rawSmsId = message.id,
            parsedSmsId = a1.parsed.id,
            transactionId = a3.transactionId,
            ingestedAt = ingestedAt,
            a1Outcome = IngestionLogA1.OK,
            a1Prompt = a1.prompt,
            a1Response = a1.response,
            a1Confidence = a1.parsed.a1Confidence,
            a2Outcome = if (a3.isDuplicate) IngestionLogA2.DUPLICATE else IngestionLogA2.COMMITTED,
            a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}\n\nA3 Prompt:\n${a3.prompt}",
            a2Response = "A2 Response:\n${a2Candidate.response}\n\nA3 Response:\n${a3.response}",
            a2Confidence = a2Candidate.contract.a2Confidence,
            userPrompt = userPrompt,
        )
        onCommitted()
    }

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
        overridePrompt: String? = null,
    ) {
        val userPrompt = overridePrompt
        emit(
            IngestionProgress.MessageParsed(
                messageIndex = messageIndex,
                totalMessages = totalMessages,
                kind = cached.kind,
            )
        )
        smsRepository.setParsedSmsId(message.id, cached.id)
        if (cached.kind == ParsedSmsKind.IGNORE.name) {
            smsRepository.markIgnoredProcessed(message.id, ingestedAt)
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = cached.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.IGNORE,
                a1Confidence = cached.a1Confidence,
                a2Outcome = IngestionLogA2.NOT_RUN,
                userPrompt = userPrompt,
            )
            onIgnored()
            return
        }
        onParsed()

        val txnRepo = com.spendai.app.data.repository.TransactionRepository(
            database.transactionDao()
        )
        val existingTxn = txnRepo.getByParsedSms(cached.id)
        // Reprompt path: when the user typed an override prompt,
        // skip the cache-hit short-circuit so A2 + A3 run again
        // and have a chance to issue modifications (DELETED for
        // the previous transaction, etc.) on the existing rows.
        if (existingTxn != null && overridePrompt == null) {
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
                userPrompt = userPrompt,
            )
            emit(
                IngestionProgress.MessageCommitted(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    transactionId = existingTxn.id,
                )
            )
            smsRepository.markProcessed(message.id, ingestedAt)
            onCommitted()
            return
        }

        val a2Candidate = try {
            agent2.resolveCandidate(cached, smsTimestampMillis = message.timestamp)
        } catch (e: A2FailureException) {
            Log.w(
                TAG,
                "A2 retry on cache hit failed for parsedSmsId=${cached.id}; skipping",
                e,
            )
            smsRepository.markSkipped(message.id, "A2 retry: ${e.message ?: e.javaClass.simpleName}")
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
                userPrompt = userPrompt,
            )
            onSkippedByA2()
            return
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "A2 retry on cache hit had unexpected throw for parsedSmsId=${cached.id}; skipping",
                t,
            )
            smsRepository.markSkipped(message.id, "A2 retry: ${t.message ?: t.javaClass.simpleName}")
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
                userPrompt = userPrompt,
            )
            return
        }

        val a3 = try {
            agent3.reviewAndCommit(
                candidate = a2Candidate.candidate,
                rawSmsId = message.id,
                rawSmsText = message.msgBody,
                a2Prompt = a2Candidate.prompt,
                a2Response = a2Candidate.response
            )
        } catch (e: A3FailureException) {
            Log.w(TAG, "A3 retry on cache hit failed for parsedSmsId=${cached.id}; skipping", e)
            smsRepository.markSkipped(message.id, "A3 retry: ${e.message ?: e.javaClass.simpleName}")
            emit(
                IngestionProgress.MessageSkipped(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    reason = "A3 retry: ${e.message ?: e.javaClass.simpleName}",
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
                a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}\n\nA3 Prompt:\n${e.prompt}",
                a2Response = "A2 Response:\n${a2Candidate.response}\n\nA3 Response:\n${e.response}",
                a2Error = e.message ?: e.javaClass.simpleName,
                userPrompt = userPrompt,
            )
            return
        } catch (t: Throwable) {
            Log.w(TAG, "A3 retry on cache hit unexpected throw for parsedSmsId=${cached.id}; skipping", t)
            smsRepository.markSkipped(message.id, "A3 retry: ${t.message ?: t.javaClass.simpleName}")
            emit(
                IngestionProgress.MessageSkipped(
                    messageIndex = messageIndex,
                    totalMessages = totalMessages,
                    reason = "A3 retry: ${t.message ?: t.javaClass.simpleName}",
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
                a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}",
                a2Response = "A2 Response:\n${a2Candidate.response}",
                a2Error = t.message ?: t.javaClass.simpleName,
                userPrompt = userPrompt,
            )
            return
        }

        if (a3.isIgnored) {
            smsRepository.markIgnoredProcessed(message.id, ingestedAt)
            recordLog(
                rawSmsId = message.id,
                parsedSmsId = cached.id,
                transactionId = null,
                ingestedAt = ingestedAt,
                a1Outcome = IngestionLogA1.OK,
                a1Confidence = cached.a1Confidence,
                a1Response = cached.a1RawJson.ifBlank { null },
                a2Outcome = IngestionLogA2.NOT_RUN,
                a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}\n\nA3 Prompt:\n${a3.prompt}",
                a2Response = "A2 Response:\n${a2Candidate.response}\n\nA3 Response:\n${a3.response}",
                a2Confidence = a2Candidate.contract.a2Confidence,
                userPrompt = userPrompt,
            )
            onIgnored()
            return
        }

        emit(
            IngestionProgress.MessageCommitted(
                messageIndex = messageIndex,
                totalMessages = totalMessages,
                transactionId = a3.transactionId,
            )
        )
        smsRepository.markProcessed(message.id, ingestedAt)
        recordLog(
            rawSmsId = message.id,
            parsedSmsId = cached.id,
            transactionId = a3.transactionId,
            ingestedAt = ingestedAt,
            a1Outcome = IngestionLogA1.OK,
            a1Confidence = cached.a1Confidence,
            a1Response = cached.a1RawJson.ifBlank { null },
            a2Outcome = if (a3.isDuplicate) IngestionLogA2.DUPLICATE else IngestionLogA2.COMMITTED,
            a2Prompt = "A2 Prompt:\n${a2Candidate.prompt}\n\nA3 Prompt:\n${a3.prompt}",
            a2Response = "A2 Response:\n${a2Candidate.response}\n\nA3 Response:\n${a3.response}",
            a2Confidence = a2Candidate.contract.a2Confidence,
            userPrompt = userPrompt,
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
        userPrompt: String? = null,
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
                    userPrompt = userPrompt,
                )
            )
        }.onFailure { Log.w(TAG, "IngestionLog insert failed for rawSmsId=$rawSmsId", it) }
    }

    private companion object {
        const val TAG = "IngestionPipeline"
    }
}

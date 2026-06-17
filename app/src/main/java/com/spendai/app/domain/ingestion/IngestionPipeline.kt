package com.spendai.app.domain.ingestion

import android.util.Log
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import com.spendai.app.domain.agent.Agent3DayCommitter
import com.spendai.app.domain.agent.MaterialisedResolution
import com.spendai.app.domain.agent.applyCommits
import com.spendai.app.domain.agent.applyLinks
import com.spendai.app.domain.agent.materialise
import com.spendai.app.domain.agent.queueNewSourceReviews
import androidx.room.withTransaction
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
 *  - [com.spendai.app.worker.DailyParsingWorker] — uses
 *    `DatabaseSmsSource` and an unbounded range to drain whatever's
 *    pending after the receiver captured new SMS.
 *  - [com.spendai.app.service.IngestionService] — uses
 *    `ContentResolverSmsSource` and a user-picked date range for
 *    foreground historical ingestion.
 *
 * The pipeline groups the messages it loaded by local date and runs
 * A1+A2 per message and A3 per day, so a UPI self-transfer pair that
 * spans two messages in the same 24h window is detected by A2's DB
 * context bundle and committed by A3 with a `SELF_TRANSFER` link.
 *
 * Idempotent: A1 is skipped when `parsedSmsRepository.getByRawSms(id)`
 * already has a row (re-running the same range is a no-op).
 */
class IngestionPipeline(
    private val database: AppDatabase,
    private val smsRepository: SmsRepository,
    private val parsedSmsRepository: ParsedSmsRepository,
    private val agent1: Agent1SmsParser,
    private val agent2: Agent2EntityResolver,
    private val agent3: Agent3DayCommitter,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Load messages from [source] (filtered to [range]), persist them
     * via the pipeline's own Room writes, then run the agent loop.
     *
     * @param emit called once per pipeline event; safe to call from
     *   the main dispatcher.
     * @return the terminal [IngestionOutcome]. Hard failures
     *   (engine not READY, A2 crashes mid-run) surface as
     *   [IngestionOutcome.Failure]. The pipeline does NOT throw — the
     *   caller maps the outcome to whatever UI / notification text.
     */
    suspend fun run(
        source: SmsSource,
        range: DateRange,
        emit: suspend (IngestionProgress) -> Unit,
    ): IngestionOutcome = withContext(Dispatchers.IO) {
        var totalMessages = 0
        var parsedCount = 0
        var ignoredCount = 0
        var committedCount = 0
        var needsReviewCount = 0
        var skippedByA1 = 0
        var skippedByA2 = 0
        val sourceBuckets = mutableSetOf<Long>()

        try {
            // 1. Load from source (no-op for DatabaseSmsSource).
            val loaded = source.load(range) { msg ->
                smsRepository.insert(msg)
                totalMessages++
                emit(IngestionProgress.LoadingFromSource(totalMessages))
            }
            Log.d(TAG, "Source loaded $loaded messages (range=$range)")

            // 2. Fetch the just-inserted rows from the DB. For the
            //    worker path (unbounded range) this picks up whatever
            //    the receiver captured; for the service path it's
            //    just the rows we just inserted.
            val unparsed = if (range == DateRange.unbounded()) {
                smsRepository.unparsedOnce()
            } else {
                smsRepository.unparsedInRange(range.startMillis, range.endMillis)
            }
            if (unparsed.isEmpty()) {
                emit(IngestionProgress.Done(IngestionSummary.EMPTY))
                return@withContext IngestionOutcome.Success(IngestionSummary.EMPTY)
            }

            // 3. Group by local day and iterate in date order.
            val byDay: Map<LocalDate, List<com.spendai.app.data.local.entity.RawSmsMessage>> =
                unparsed.groupBy { range.toLocalDate(it.timestamp, zone) }
                    .toSortedMap()
            val totalDays = byDay.size
            var dayIndex = 0

            val now = System.currentTimeMillis()

            for ((day, dayMessages) in byDay) {
                dayIndex++
                emit(
                    IngestionProgress.DayStarting(
                        dayIndex = dayIndex,
                        totalDays = totalDays,
                        messageCount = dayMessages.size,
                    )
                )
                val materialised = mutableListOf<MaterialisedResolution>()
                var messageIndex = 0
                for (message in dayMessages) {
                    messageIndex++

                    // Idempotency: skip A1 if a parsed_sms row already
                    // exists for this raw_sms (a previous run handled
                    // it; the raw_sms row is still UNPARSED in that
                    // case because the receiver / pipeline didn't
                    // re-set it).
                    val cached = parsedSmsRepository.getByRawSms(message.id)
                    // Synthetic-IGNORE detection. Older Agent1SmsParser
                    // versions swallowed engine exceptions and persisted
                    // an A1Contract(kind="IGNORE", confidence=0.0) with
                    // an empty a1RawJson. Real model IGNOREs always have
                    // non-empty a1RawJson and confidence=1.0 per the A1
                    // prompt rules, so this signature is a reliable
                    // "this row was a placeholder, please re-parse"
                    // signal. We log the recovery so it shows up in
                    // logcat and the user can see the catch-up happening.
                    val isSyntheticIgnore = cached != null &&
                        cached.kind == ParsedSmsKind.IGNORE.name &&
                        cached.a1RawJson.isEmpty() &&
                        cached.a1Confidence == 0.0f
                    val parsed: com.spendai.app.data.local.entity.ParsedSms
                    if (cached != null && !isSyntheticIgnore) {
                        parsed = cached
                    } else {
                        if (isSyntheticIgnore) {
                            Log.i(
                                TAG,
                                "Re-parsing synthetic IGNORE for rawSmsId=${message.id} " +
                                    "from previous run; deleting the placeholder row.",
                            )
                            parsedSmsRepository.deleteByRawSms(message.id)
                        }
                        val a1Result = try {
                            agent1.parse(message)
                        } catch (t: Throwable) {
                            // Per-message inference failure. The engine
                            // has already rebuilt its conversation
                            // (or marked itself Error) so the NEXT
                            // call still works. We just skip this
                            // message and let the run continue.
                            Log.w(
                                TAG,
                                "A1 failed for rawSmsId=${message.id} (${t.message}); skipping",
                                t,
                            )
                            emit(
                                IngestionProgress.MessageSkipped(
                                    dayIndex = dayIndex,
                                    messageIndex = messageIndex,
                                    totalMessages = dayMessages.size,
                                    reason = t.message ?: t.javaClass.simpleName,
                                ),
                            )
                            skippedByA1++
                            continue
                        }
                        if (a1Result == null) {
                            // Engine isn't Ready (init failed) and
                            // the failure wasn't a per-message error.
                            // There's no point continuing the run;
                            // the worker will retry with a fresh
                            // initialize() once it sees Error state.
                            return@withContext IngestionOutcome.Failure(
                                "Engine became unready mid-run"
                            )
                        }
                        parsed = a1Result
                        smsRepository.setParsedSmsId(message.id, parsed.id)
                    }
                    emit(
                        IngestionProgress.MessageParsed(
                            dayIndex = dayIndex,
                            messageIndex = messageIndex,
                            totalMessages = dayMessages.size,
                            kind = parsed.kind,
                        )
                    )
                    if (parsed.kind == ParsedSmsKind.IGNORE.name) {
                        smsRepository.markIgnored(message.id)
                        ignoredCount++
                        continue
                    }
                    parsedCount++

                    val resolution = try {
                        agent2.resolve(parsed)
                    } catch (t: Throwable) {
                        // A2 returned malformed JSON or threw on parse.
                        // The right move is to skip THIS message and
                        // continue with the rest of the run — aborting
                        // would leave every other message in the range
                        // unprocessed. raw_sms stays UNPARSED so the
                        // next worker run can retry; the parsed_sms
                        // row A1 wrote is kept too, so the next run
                        // will skip A1 and go straight to A2.
                        Log.w(TAG, "A2 failed for parsedSmsId=${parsed.id}; skipping", t)
                        skippedByA2++
                        emit(
                            IngestionProgress.MessageSkipped(
                                dayIndex = dayIndex,
                                messageIndex = messageIndex,
                                totalMessages = dayMessages.size,
                                reason = t.message ?: t.javaClass.simpleName,
                            )
                        )
                        continue
                    }
                    emit(
                        IngestionProgress.MessageResolved(
                            dayIndex = dayIndex,
                            messageIndex = messageIndex,
                            totalMessages = dayMessages.size,
                            a2Confidence = resolution.a2Confidence,
                        )
                    )
                    val material = resolution.materialise(
                        sourceRepository = sourceRepository(),
                        accountRepository = accountRepository(),
                        merchantRepository = merchantRepository(),
                        rawSmsId = message.id,
                        now = now,
                    )
                    material.sourceCandidate.let { src ->
                        when (src) {
                            is com.spendai.app.domain.model.SourceCandidate.New ->
                                sourceBuckets.add(-1L)
                            is com.spendai.app.domain.model.SourceCandidate.Existing ->
                                sourceBuckets.add(src.sourceId)
                        }
                    }
                    materialised += material
                    smsRepository.markParsed(message.id)
                }

                if (materialised.isEmpty()) continue

                emit(IngestionProgress.CommittingDay(dayIndex, totalDays))
                val daySummary = buildDaySummary(materialised)
                val commits = agent3.commit(materialised, daySummary)

                if (commits == null) {
                    // A3 failed: queue every materialised resolution to
                    // pending_review rather than commit partial garbage.
                    queueAllToReview(materialised, now, dayIndex, totalDays)
                    needsReviewCount += materialised.size
                } else {
                    val (added, review) = applyAllInTxn(commits, materialised, now)
                    committedCount += added
                    needsReviewCount += review
                }
                emit(
                    IngestionProgress.DayCommitted(
                        dayIndex = dayIndex,
                        totalDays = totalDays,
                        commitCount = committedCount,
                    )
                )
            }

            val summary = IngestionSummary(
                totalMessages = totalMessages,
                parsed = parsedCount,
                ignored = ignoredCount,
                skippedByA1 = skippedByA1,
                skippedByA2 = skippedByA2,
                committedTransactions = committedCount,
                needsReview = needsReviewCount,
                sourceBuckets = sourceBuckets.size,
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

    // ---------- helpers (avoid passing 7 args to materialise) ----------

    private fun sourceRepository() = com.spendai.app.data.repository.FinancialSourceRepository(
        database.financialSourceDao()
    )
    private fun accountRepository() = com.spendai.app.data.repository.AccountRepository(
        database.accountDao()
    )
    private fun merchantRepository() = com.spendai.app.data.repository.MerchantRepository(
        database.merchantDao()
    )
    private fun transactionRepository() = com.spendai.app.data.repository.TransactionRepository(
        database.transactionDao()
    )
    private fun transactionLinkRepository() = com.spendai.app.data.repository.TransactionLinkRepository(
        database.transactionLinkDao()
    )
    private fun pendingReviewRepository() = com.spendai.app.data.repository.PendingReviewRepository(
        database.pendingReviewDao()
    )

    private suspend fun applyAllInTxn(
        commits: List<com.spendai.app.domain.model.Commit>,
        materialised: List<MaterialisedResolution>,
        now: Long,
    ): Pair<Int, Int> = database.withTransaction {
        val parsedSmsToTxnId = applyCommits(
            commits = commits,
            transactionRepository = transactionRepository(),
            transactionLinkRepository = transactionLinkRepository(),
            pendingReviewRepository = pendingReviewRepository(),
            sourceRepository = sourceRepository(),
            now = now,
        )
        applyLinks(
            commits = commits,
            parsedSmsToTxnId = parsedSmsToTxnId,
            transactionLinkRepository = transactionLinkRepository(),
            now = now,
        )
        val added = commits.count { it.needsReview.not() }
        val review = commits.count { it.needsReview }
        added to review
    }

    private suspend fun queueAllToReview(
        materialised: List<MaterialisedResolution>,
        now: Long,
        dayIndex: Int,
        totalDays: Int,
    ) {
        database.withTransaction {
            for (res in materialised) {
                val parsed = parsedSmsRepository.getById(res.parsedSmsId) ?: continue
                val txnId = transactionRepository().insert(
                    com.spendai.app.data.local.entity.Transaction(
                        accountId = res.accountId,
                        merchantId = res.merchantId,
                        rawSmsId = parsed.rawSmsId,
                        parsedSmsId = res.parsedSmsId,
                        amountPaise = 0L,
                        currency = "INR",
                        direction = com.spendai.app.data.local.entity.TransactionDirection.DEBIT.name,
                        txnAtMillis = now,
                        status = com.spendai.app.data.local.entity.TransactionStatus.NEEDS_REVIEW.name,
                        confidence = res.a2Confidence,
                        notes = "Auto-queued: A3 returned no parseable output (day $dayIndex/$totalDays).",
                        createdAt = now,
                    )
                )
                pendingReviewRepository().insert(
                    com.spendai.app.data.local.entity.PendingReview(
                        kind = com.spendai.app.data.local.entity.PendingReviewKind.TRANSACTION.name,
                        targetId = txnId,
                        promptSummary = "Could not auto-commit; please confirm details.",
                        suggestedJson = "{}",
                        createdAt = now,
                    )
                )
            }
        }
        // Source-level review cards (new senders).
        queueNewSourceReviews(
            resolutions = materialised,
            sourceRepository = sourceRepository(),
            pendingReviewRepository = pendingReviewRepository(),
            now = now,
        )
    }

    private fun buildDaySummary(materialised: List<MaterialisedResolution>): String =
        "materialisedCount=${materialised.size} " +
            "minAccountId=${materialised.minOfOrNull { it.accountId } ?: 0} " +
            "maxAccountId=${materialised.maxOfOrNull { it.accountId } ?: 0}"

    private companion object {
        const val TAG = "IngestionPipeline"
    }
}

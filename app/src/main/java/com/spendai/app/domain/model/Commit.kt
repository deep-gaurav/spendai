package com.spendai.app.domain.model

import com.spendai.app.data.local.entity.PendingReviewKind
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.local.entity.TransactionStatus

/**
 * Agent 3's output: a list of [Commit] records describing what to
 * actually write to the database. The worker applies these in a single
 * Room `@Transaction`.
 */
data class Commit(
    val parsedSmsId: Long,
    val finalTransaction: FinalTransaction,
    val confidence: Float,
    val linksToCreate: List<CommitLink>,
    val needsReview: Boolean,
    val reviewKind: PendingReviewKind = PendingReviewKind.TRANSACTION,
)

data class FinalTransaction(
    val accountId: Long,
    val merchantId: Long?,
    val rawSmsId: Long,
    val parsedSmsId: Long,
    val amountPaise: Long,
    val currency: String,
    val direction: TransactionDirection,
    val txnAtMillis: Long,
    val channel: String?,
    val referenceNo: String?,
    val status: TransactionStatus,
    val notes: String?,
)

/**
 * A directed edge from THIS commit's transaction to another
 * (already-committed or about-to-be-committed in this batch) transaction.
 *
 * `partnerParsedSmsId` is used to resolve to a row id once both
 * sides of the edge have been inserted. The worker maintains a
 * `parsedSmsId -> transactionId` map as it walks the batch.
 */
data class CommitLink(
    val partnerParsedSmsId: Long,
    val linkType: TransactionLinkType,
    val confidence: Float,
)

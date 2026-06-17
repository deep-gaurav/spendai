package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.domain.model.Commit
import com.spendai.app.domain.model.CommitLink
import com.spendai.app.domain.model.FinalTransaction
import kotlinx.serialization.Serializable

@Serializable
data class A3Contract(
    val commits: List<CommitChoice> = emptyList(),
)

@Serializable
data class CommitChoice(
    val parsedSmsId: Long,
    val finalTransaction: FinalTransactionChoice,
    val confidence: Float = 0f,
    val linksToCreate: List<CommitLinkChoice> = emptyList(),
    val needsReview: Boolean = false,
) {
    fun toCommit(): Commit = Commit(
        parsedSmsId = parsedSmsId,
        finalTransaction = finalTransaction.toDomain(),
        confidence = confidence,
        linksToCreate = linksToCreate.map {
            CommitLink(
                partnerParsedSmsId = it.partnerParsedSmsId,
                linkType = runCatching { TransactionLinkType.valueOf(it.linkType) }
                    .getOrDefault(TransactionLinkType.SELF_TRANSFER),
                confidence = it.confidence,
            )
        },
        needsReview = needsReview,
    )
}

@Serializable
data class FinalTransactionChoice(
    val accountId: Long,
    val merchantId: Long? = null,
    val rawSmsId: Long,
    val parsedSmsId: Long,
    val amountPaise: Long,
    val currency: String = "INR",
    val direction: String = TransactionDirection.DEBIT.name,
    val txnAtMillis: Long,
    val channel: String? = null,
    val referenceNo: String? = null,
    val status: String = TransactionStatus.CONFIRMED.name,
    val notes: String? = null,
) {
    fun toDomain(): FinalTransaction = FinalTransaction(
        accountId = accountId,
        merchantId = merchantId,
        rawSmsId = rawSmsId,
        parsedSmsId = parsedSmsId,
        amountPaise = amountPaise,
        currency = currency,
        direction = runCatching { TransactionDirection.valueOf(direction) }
            .getOrDefault(TransactionDirection.DEBIT),
        txnAtMillis = txnAtMillis,
        channel = channel,
        referenceNo = referenceNo,
        status = runCatching { TransactionStatus.valueOf(status) }
            .getOrDefault(TransactionStatus.CONFIRMED),
        notes = notes,
    )
}

@Serializable
data class CommitLinkChoice(
    val partnerParsedSmsId: Long,
    val linkType: String,
    val confidence: Float,
)

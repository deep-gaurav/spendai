package com.spendai.app.domain.agent

import kotlinx.serialization.Serializable

@Serializable
data class A3Contract(
    val currentDecision: A3CurrentDecision,
    val modifications: List<A3Modification> = emptyList()
)

@Serializable
data class A3CurrentDecision(
    val decision: String, // "COMMIT", "DUPLICATE", "IGNORE"
    val accountId: Long? = null,
    val merchantId: Long? = null,
    val categoryId: Long? = null,
    val direction: String? = null, // "DEBIT", "CREDIT"
    val amountPaise: Long? = null,
    val title: String? = null,
    val referenceNo: String? = null,
    val duplicateOfTransactionId: Long? = null,
    val transferLinkWithTransactionId: Long? = null,
    val transferLinkType: String? = null // "SELF_TRANSFER", "REFUND_OF", "REVERSAL_OF", "SPLIT_OF"
)

@Serializable
data class A3Modification(
    val transactionId: Long,
    val direction: String? = null, // "DEBIT", "CREDIT"
    val accountId: Long? = null,
    val merchantId: Long? = null,
    val categoryId: Long? = null,
    val title: String? = null,
    val referenceNo: String? = null,
    val status: String? = null, // "DELETED" or normal
    val transferLinkWithTransactionId: Long? = null,
    val transferLinkType: String? = null
)

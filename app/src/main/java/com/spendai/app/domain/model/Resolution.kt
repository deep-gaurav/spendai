package com.spendai.app.domain.model

import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.local.entity.SourceInstrumentType

/**
 * Agent 2's output for a single [com.spendai.app.data.local.entity.ParsedSms].
 * In-memory only — the worker hands a list of these to Agent 3.
 *
 * Each *Candidate is one of two shapes:
 *  - "existing": the agent is pointing at a row we already have
 *  - "new": the agent is proposing a row to create
 *
 * The worker does NOT persist any of this until A3 confirms a commit.
 */
data class Resolution(
    val parsedSmsId: Long,
    val sourceCandidate: SourceCandidate,
    val accountCandidate: AccountCandidate,
    val merchantCandidate: MerchantCandidate,
    val possibleLink: PossibleLink?,
    val a2Confidence: Float,
)

sealed interface SourceCandidate {
    val confidence: Float

    data class Existing(
        val sourceId: Long,
        override val confidence: Float,
    ) : SourceCandidate

    data class New(
        val sourceKey: String,
        val deducedType: String,
        val suggestedBankName: String?,
        val suggestedInstrumentType: SourceInstrumentType,
        val suggestedDisplayName: String?,
        override val confidence: Float,
    ) : SourceCandidate
}

sealed interface AccountCandidate {
    val confidence: Float

    data class Existing(
        val accountId: Long,
        override val confidence: Float,
    ) : AccountCandidate

    data class New(
        val instrumentType: SourceInstrumentType,
        val issuer: String,
        val maskedNumber: String,
        val currency: String,
        override val confidence: Float,
    ) : AccountCandidate
}

sealed interface MerchantCandidate {
    val confidence: Float

    data class Existing(
        val merchantId: Long,
        override val confidence: Float,
    ) : MerchantCandidate

    data class New(
        val name: String,
        val normalizedName: String,
        val vpa: String?,
        override val confidence: Float,
    ) : MerchantCandidate

    /** For P2P transfers where the counterparty is not a recognisable merchant. */
    data class None(
        override val confidence: Float,
    ) : MerchantCandidate
}

data class PossibleLink(
    val partnerTransactionId: Long,
    val linkType: TransactionLinkType,
    val confidence: Float,
)

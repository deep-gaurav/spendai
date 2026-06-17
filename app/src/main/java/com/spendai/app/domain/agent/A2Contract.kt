package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.SourceInstrumentType
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.domain.model.AccountCandidate
import com.spendai.app.domain.model.MerchantCandidate
import com.spendai.app.domain.model.PossibleLink
import com.spendai.app.domain.model.Resolution
import com.spendai.app.domain.model.SourceCandidate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Typed view of Agent 2's JSON output. The source / account / merchant
 * candidates use kotlinx-serialization's sealed-class polymorphism
 * (JsonClassDiscriminator = "kind") so the model can return
 * {"kind": "existing", "sourceId": 5, "confidence": 0.9} or
 * {"kind": "new", "sourceKey": "...", "confidence": 0.8} from the
 * same field.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class A2Contract(
    val parsedSmsId: Long = 0L,
    val source: SourceChoice,
    val account: AccountChoice,
    val merchant: MerchantChoice,
    val possibleLink: LinkChoice? = null,
    val a2Confidence: Float = 0f,
) {
    fun toResolution(overrideParsedSmsId: Long): Resolution = Resolution(
        parsedSmsId = overrideParsedSmsId,
        sourceCandidate = source.toDomain(),
        accountCandidate = account.toDomain(),
        merchantCandidate = merchant.toDomain(),
        possibleLink = possibleLink?.toDomain(),
        a2Confidence = a2Confidence,
    )

    companion object {
        fun fromResolution(r: Resolution): A2Contract = A2Contract(
            parsedSmsId = r.parsedSmsId,
            source = r.sourceCandidate.toContract(),
            account = r.accountCandidate.toContract(),
            merchant = r.merchantCandidate.toContract(),
            possibleLink = r.possibleLink?.let {
                LinkChoice(
                    partnerParsedSmsId = it.partnerTransactionId,
                    linkType = it.linkType.name,
                    confidence = it.confidence,
                )
            },
            a2Confidence = r.a2Confidence,
        )
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class SourceChoice {
    abstract val confidence: Float
    abstract fun toDomain(): SourceCandidate

    @Serializable
    @kotlinx.serialization.SerialName("existing")
    data class Existing(
        val sourceId: Long,
        override val confidence: Float,
    ) : SourceChoice() {
        override fun toDomain() = SourceCandidate.Existing(sourceId, confidence)
    }

    @Serializable
    @kotlinx.serialization.SerialName("new")
    data class New(
        val sourceKey: String,
        val deducedType: String,
        val suggestedBankName: String? = null,
        val suggestedInstrumentType: String = SourceInstrumentType.UNKNOWN.name,
        val suggestedDisplayName: String? = null,
        override val confidence: Float,
    ) : SourceChoice() {
        override fun toDomain(): SourceCandidate = SourceCandidate.New(
            sourceKey = sourceKey,
            deducedType = deducedType,
            suggestedBankName = suggestedBankName,
            suggestedInstrumentType = runCatching {
                SourceInstrumentType.valueOf(suggestedInstrumentType)
            }.getOrDefault(SourceInstrumentType.UNKNOWN),
            suggestedDisplayName = suggestedDisplayName,
            confidence = confidence,
        )
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class AccountChoice {
    abstract val confidence: Float
    abstract fun toDomain(): AccountCandidate

    @Serializable
    @kotlinx.serialization.SerialName("existing")
    data class Existing(
        val accountId: Long,
        override val confidence: Float,
    ) : AccountChoice() {
        override fun toDomain() = AccountCandidate.Existing(accountId, confidence)
    }

    @Serializable
    @kotlinx.serialization.SerialName("new")
    data class New(
        val instrumentType: String = SourceInstrumentType.UNKNOWN.name,
        val issuer: String,
        val maskedNumber: String,
        val currency: String = "INR",
        override val confidence: Float,
    ) : AccountChoice() {
        override fun toDomain(): AccountCandidate = AccountCandidate.New(
            instrumentType = runCatching {
                SourceInstrumentType.valueOf(instrumentType)
            }.getOrDefault(SourceInstrumentType.UNKNOWN),
            issuer = issuer,
            maskedNumber = maskedNumber,
            currency = currency,
            confidence = confidence,
        )
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class MerchantChoice {
    abstract val confidence: Float
    abstract fun toDomain(): MerchantCandidate

    @Serializable
    @kotlinx.serialization.SerialName("existing")
    data class Existing(
        val merchantId: Long,
        override val confidence: Float,
    ) : MerchantChoice() {
        override fun toDomain() = MerchantCandidate.Existing(merchantId, confidence)
    }

    @Serializable
    @kotlinx.serialization.SerialName("new")
    data class New(
        val name: String,
        val normalizedName: String,
        val vpa: String? = null,
        override val confidence: Float,
    ) : MerchantChoice() {
        override fun toDomain() = MerchantCandidate.New(name, normalizedName, vpa, confidence)
    }

    @Serializable
    @kotlinx.serialization.SerialName("none")
    data class None(
        override val confidence: Float,
    ) : MerchantChoice() {
        override fun toDomain() = MerchantCandidate.None(confidence)
    }
}

@Serializable
data class LinkChoice(
    val partnerParsedSmsId: Long,
    val linkType: String,
    val confidence: Float,
) {
    fun toDomain(): PossibleLink = PossibleLink(
        partnerTransactionId = partnerParsedSmsId,
        linkType = runCatching { TransactionLinkType.valueOf(linkType) }
            .getOrDefault(TransactionLinkType.SELF_TRANSFER),
        confidence = confidence,
    )
}

private fun SourceCandidate.toContract(): SourceChoice = when (this) {
    is SourceCandidate.Existing -> SourceChoice.Existing(sourceId, confidence)
    is SourceCandidate.New -> SourceChoice.New(
        sourceKey = sourceKey,
        deducedType = deducedType,
        suggestedBankName = suggestedBankName,
        suggestedInstrumentType = suggestedInstrumentType.name,
        suggestedDisplayName = suggestedDisplayName,
        confidence = confidence,
    )
}

private fun AccountCandidate.toContract(): AccountChoice = when (this) {
    is AccountCandidate.Existing -> AccountChoice.Existing(accountId, confidence)
    is AccountCandidate.New -> AccountChoice.New(
        instrumentType = instrumentType.name,
        issuer = issuer,
        maskedNumber = maskedNumber,
        currency = currency,
        confidence = confidence,
    )
}

private fun MerchantCandidate.toContract(): MerchantChoice = when (this) {
    is MerchantCandidate.Existing -> MerchantChoice.Existing(merchantId, confidence)
    is MerchantCandidate.New -> MerchantChoice.New(name, normalizedName, vpa, confidence)
    is MerchantCandidate.None -> MerchantChoice.None(confidence)
}

package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.SourceInstrumentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Typed view of Agent 2's JSON output. The source / account / merchant
 * candidates use kotlinx-serialization's sealed-class polymorphism
 * (JsonClassDiscriminator = "kind") so the model can return
 * {"kind": "existing", "sourceId": 5, "confidence": 0.9} or
 * {"kind": "new", "sourceKey": "...", "confidence": 0.8} from the
 * same field.
 *
 * `a2Confidence` is the resolver's overall confidence in the
 * resolution. A2 always commits the transaction when A1 said
 * TRANSACTION — the confidence is preserved on the [com.spendai.app.data.local.entity.Transaction]
 * row for the edit UI to display.
 *
 * ## Title and category
 *
 * A2 is also asked to emit a freeform `title` and a `categoryName` /
 * `categoryEmoji` pair. Categories are first-class entities: the
 * resolver looks the name up by its normalised form and creates a
 * new [com.spendai.app.data.local.entity.Category] row on first
 * sight. The `title` is preserved on the [com.spendai.app.data.local.entity.Transaction]
 * row verbatim; the [com.spendai.app.domain.model.TransactionTitle]
 * helper is the render-time fallback for when the LLM omits it.
 *
 * All three are optional — the contract tolerates a model that
 * decides not to commit a category for borderline cases.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class A2Contract(
    val parsedSmsId: Long = 0L,
    val source: SourceChoice,
    val account: AccountChoice,
    val merchant: MerchantChoice,
    val a2Confidence: Float = 0f,
    val title: String? = null,
    val categoryName: String? = null,
    val categoryEmoji: String? = null,
    val duplicateOfTransactionId: Long? = null,
    val transferLinkWithTransactionId: Long? = null,
    val transferLinkType: String? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class SourceChoice {
    abstract val confidence: Float

    @Serializable
    @kotlinx.serialization.SerialName("existing")
    data class Existing(
        val sourceId: Long,
        override val confidence: Float,
    ) : SourceChoice()

    @Serializable
    @kotlinx.serialization.SerialName("new")
    data class New(
        val sourceKey: String,
        val deducedType: String,
        val suggestedBankName: String? = null,
        val suggestedInstrumentType: String = SourceInstrumentType.UNKNOWN.name,
        val suggestedDisplayName: String? = null,
        override val confidence: Float,
    ) : SourceChoice()
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class AccountChoice {
    abstract val confidence: Float

    @Serializable
    @kotlinx.serialization.SerialName("existing")
    data class Existing(
        val accountId: Long,
        override val confidence: Float,
    ) : AccountChoice()

    @Serializable
    @kotlinx.serialization.SerialName("new")
    data class New(
        val instrumentType: String = SourceInstrumentType.UNKNOWN.name,
        val issuer: String,
        val maskedNumber: String,
        val currency: String = "INR",
        override val confidence: Float,
    ) : AccountChoice()
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class MerchantChoice {
    abstract val confidence: Float

    @Serializable
    @kotlinx.serialization.SerialName("existing")
    data class Existing(
        val merchantId: Long,
        override val confidence: Float,
    ) : MerchantChoice()

    @Serializable
    @kotlinx.serialization.SerialName("new")
    data class New(
        val name: String,
        val normalizedName: String,
        val vpa: String? = null,
        override val confidence: Float,
    ) : MerchantChoice()

    @Serializable
    @kotlinx.serialization.SerialName("none")
    data class None(
        override val confidence: Float,
    ) : MerchantChoice()
}

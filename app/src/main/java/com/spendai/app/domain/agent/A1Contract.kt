package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import kotlinx.serialization.Serializable

/**
 * Typed view of Agent 1's JSON output. Decoupled from the [ParsedSms]
 * Room entity so the model's contract can evolve independently of
 * the schema.
 */
@Serializable
data class A1Contract(
    val kind: String = "IGNORE",
    val amountPaise: Long? = null,
    val currency: String? = null,
    val direction: String? = null,
    val txnAtMillis: Long? = null,
    val channel: String? = null,
    val sourceKeyHint: String? = null,
    val merchantRaw: String? = null,
    val cardLast4Hint: String? = null,
    val accountLast4Hint: String? = null,
    val referenceNo: String? = null,
    val confidence: Float = 0f,
) {
    val isIgnore: Boolean get() = kind.equals("IGNORE", ignoreCase = true)

    fun toEntity(rawSmsId: Long, rawJson: String, parsedAt: Long): ParsedSms = ParsedSms(
        rawSmsId = rawSmsId,
        parsedAt = parsedAt,
        kind = if (isIgnore) ParsedSmsKind.IGNORE.name else ParsedSmsKind.TRANSACTION.name,
        amountPaise = if (isIgnore) null else amountPaise,
        currency = if (isIgnore) null else currency,
        direction = if (isIgnore) null else direction,
        txnAtMillis = if (isIgnore) null else txnAtMillis,
        channel = if (isIgnore) null else channel,
        sourceKeyHint = if (isIgnore) null else sourceKeyHint,
        merchantRaw = if (isIgnore) null else merchantRaw,
        cardLast4Hint = if (isIgnore) null else cardLast4Hint,
        accountLast4Hint = if (isIgnore) null else accountLast4Hint,
        referenceNo = if (isIgnore) null else referenceNo,
        a1Confidence = confidence,
        a1RawJson = rawJson,
    )

    companion object {
        fun fromEntity(p: ParsedSms): A1Contract = A1Contract(
            kind = p.kind,
            amountPaise = p.amountPaise,
            currency = p.currency,
            direction = p.direction,
            txnAtMillis = p.txnAtMillis,
            channel = p.channel,
            sourceKeyHint = p.sourceKeyHint,
            merchantRaw = p.merchantRaw,
            cardLast4Hint = p.cardLast4Hint,
            accountLast4Hint = p.accountLast4Hint,
            referenceNo = p.referenceNo,
            confidence = p.a1Confidence,
        )
    }
}

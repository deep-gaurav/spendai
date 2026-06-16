package com.spendai.app.data.local.entity

/**
 * Lifecycle state of a captured SMS message.
 *
 *  - [UNPARSED]: just arrived from [com.spendai.app.receiver.SmsReceiver] and
 *    has not yet been processed by the [com.spendai.app.worker.DailyParsingWorker].
 *  - [PARSED]: the LLM produced a usable expense record from the message body.
 *  - [IGNORED]: the message was a non-financial SMS (OTP, marketing, etc.) and
 *    is no longer interesting.
 *
 * Stored as a string column via [com.spendai.app.data.local.Converters] so
 * schema migrations stay trivial.
 */
enum class SmsStatus {
    UNPARSED,
    PARSED,
    IGNORED
}

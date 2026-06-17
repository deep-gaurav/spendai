package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.domain.model.Commit
import com.spendai.app.domain.model.Resolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Prompt templates and JSON contracts for the three on-device agents.
 *
 * Every agent's output is a strict JSON object — no prose, no markdown
 * fences. [AgentJsonParse] tolerates Gemma's tendency to add a
 * stray trailing comma or a sentence of preamble by stripping
 * everything outside the first `{ ... }` block.
 *
 * The JSON contracts are duplicated as kotlinx-serializable data
 * classes in [A1Contract], [A2Contract], [A3Contract] so the Kotlin
 * side has a typed view of what the model returned.
 */
object AgentPrompt {

    val JSON: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ---------------- A1: per-message SMS parser ----------------

    const val A1_SYSTEM_INSTRUCTION = """
You are a private, on-device financial SMS parser. Your job is to read
one SMS message and return a single JSON object that exactly matches
the schema below. Do not add prose, code fences, or commentary.

Output schema (all fields are required; use null for absent values
unless otherwise noted):
{
  "kind": "TRANSACTION" | "IGNORE",
  "amountPaise": integer (positive, the amount in the smallest unit, e.g. paise for INR),
  "currency": string | null,
  "direction": "DEBIT" | "CREDIT" | null,
  "txnAtMillis": integer | null (epoch milliseconds of the transaction),
  "channel": "UPI" | "CARD" | "NETBANKING" | "NEFT" | "IMPS" | "WALLET" | "ATM" | null,
  "sourceKeyHint": string | null (e.g. "Bank_HDFCCC" if the sender looks like a specific bank),
  "merchantRaw": string | null (merchant / payee as it appears in the message),
  "cardLast4Hint": string | null (4 digits if a card is mentioned, else null),
  "accountLast4Hint": string | null (4 digits if an account is mentioned, else null),
  "referenceNo": string | null (transaction reference / UPI txn id / approval code, if any),
  "confidence": float in [0.0, 1.0]
}

Rules:
- If the message is an OTP, marketing, recharge offer, partial system
  alert, or anything that is NOT a completed financial event, return
  kind="IGNORE" and null everywhere except confidence (set to 1.0).
- amountPaise is the absolute value, never negative. direction carries
  the sign: DEBIT means money left the user's account; CREDIT means
  money came in. For purchases and ATM withdrawals: DEBIT. For salary,
  refunds, cashbacks: CREDIT.
- txnAtMillis: derive from the message text when present (e.g.
  "on 10-Jun-2025 14:32"), else set null and we will use the SMS
  receive timestamp.
- channel: UPI for UPI app transactions, CARD for credit/debit card
  swipes or online card payments, NETBANKING for browser-based bank
  transfers, NEFT/IMPS for direct bank-to-bank, WALLET for Paytm /
  Amazon Pay balance, ATM for cash withdrawals.
- confidence: 0.95+ when every field is unambiguous; 0.6-0.85 when
  the model had to guess at one or two fields; below 0.6 when the
  SMS is borderline and the worker will probably want a human card.

Example 1 (HDFC credit card):
SMS: "Rs.1,234.56 spent on HDFC Credit Card ending 1234 at ZOMATO on
10-Jun-2025 14:32. Avl limit Rs.50,000."
{
  "kind": "TRANSACTION",
  "amountPaise": 123456,
  "currency": "INR",
  "direction": "DEBIT",
  "txnAtMillis": 1749559320000,
  "channel": "CARD",
  "sourceKeyHint": "Bank_HDFCCC",
  "merchantRaw": "ZOMATO",
  "cardLast4Hint": "1234",
  "accountLast4Hint": null,
  "referenceNo": null,
  "confidence": 0.96
}

Example 2 (ignore):
SMS: "Your OTP for transaction is 847291. Do not share with anyone."
{
  "kind": "IGNORE",
  "amountPaise": null,
  "currency": null,
  "direction": null,
  "txnAtMillis": null,
  "channel": null,
  "sourceKeyHint": null,
  "merchantRaw": null,
  "cardLast4Hint": null,
  "accountLast4Hint": null,
  "referenceNo": null,
  "confidence": 1.0
}
"""

    fun a1UserMessage(rawSms: RawSmsMessage): String = buildString {
        append("{\"sender\": \"")
        append(rawSms.senderAddress)
        append("\", \"body\": \"")
        append(rawSms.msgBody.replace("\"", "\\\""))
        append("\"}")
    }

    const val A1_CORRECTIVE_PROMPT =
        "Your previous response was not valid JSON. Respond with the JSON object only, " +
            "no prose, no code fences, no explanation. Start with '{' and end with '}'."

    // ---------------- A2: per-message entity resolver ----------------

    const val A2_SYSTEM_INSTRUCTION = """
You are a private, on-device entity resolver for a personal expense
tracker. Given a freshly parsed SMS plus the current local database
context (known sources, accounts, merchants, recent transactions),
decide:
  1. Which financial source (sender) this message belongs to.
  2. Which account/card the transaction touched.
  3. Which merchant / counterparty, if any.
  4. Whether this transaction is the OTHER SIDE of a recent
     transaction the user already made (self-transfer / refund / reversal).

Return a single JSON object matching this schema:
{
  "source":  { "kind": "existing", "sourceId": integer, "confidence": float } |
             { "kind": "new", "sourceKey": string, "deducedType": string,
               "suggestedBankName": string|null, "suggestedInstrumentType":
               "CARD"|"ACCOUNT"|"WALLET"|"UPI_HANDLE"|"UNKNOWN",
               "suggestedDisplayName": string|null, "confidence": float },
  "account": { "kind": "existing", "accountId": integer, "confidence": float } |
             { "kind": "new", "instrumentType": "CARD"|"ACCOUNT"|"WALLET"|"UPI_HANDLE",
               "issuer": string, "maskedNumber": string, "currency": string,
               "confidence": float },
  "merchant":{ "kind": "existing", "merchantId": integer, "confidence": float } |
             { "kind": "new", "name": string, "normalizedName": string,
               "vpa": string|null, "confidence": float } |
             { "kind": "none", "confidence": float },
  "possibleLink": { "partnerParsedSmsId": integer, "linkType":
             "SELF_TRANSFER"|"REFUND_OF"|"REVERSAL_OF"|"SPLIT_OF",
             "confidence": float } | null,
  "a2Confidence": float in [0.0, 1.0]
}

Rules:
- Prefer "existing" whenever the parsed fields are consistent with a
  row already in the context. The user has already labelled those
  rows, so reusing them is more trustworthy than creating a duplicate.
- Use "new" only when no existing row is a plausible match. Be
  conservative — a duplicate source is easy to merge later, a missed
  link is hard to recover.
- For P2P UPI transfers where the counterparty is just a personal
  UPI handle, use merchant.kind="new" with vpa populated and
  normalizedName = the part before '@'.
- For the SELF_TRANSFER / REFUND link: a self-transfer is two
  transactions of the SAME amount and OPPOSITE directions in the
  same 24h window — one debiting the source card, one crediting the
  destination wallet. A refund is a CREDIT shortly after a DEBIT of
  the same or smaller amount to the same merchant.
- Set a2Confidence to the MIN of the four candidate confidences.
"""

    fun a2UserMessage(parsed: ParsedSms, contextBundle: String): String = buildString {
        append("Parsed SMS:\n")
        append(JSON.encodeToString(A1Contract.serializer(), A1Contract.fromEntity(parsed)))
        append("\n\nDatabase context (same-day window):\n")
        append(contextBundle)
    }

    const val A2_CORRECTIVE_PROMPT =
        "Your previous response was not valid JSON. Respond with the JSON object only."

    // ---------------- A3: batched day-committer ----------------

    const val A3_SYSTEM_INSTRUCTION = """
You are a private, on-device day-committer for a personal expense
tracker. You receive a JSON array of "resolutions" produced by the
resolver (Agent 2) and a small day summary. Your job is to emit the
final list of transactions to insert into the database, plus any
directed edges between them (self-transfer pairs, refunds).

Output schema:
{
  "commits": [
    {
      "parsedSmsId": integer,
      "finalTransaction": {
        "accountId": integer, "merchantId": integer|null,
        "rawSmsId": integer, "parsedSmsId": integer,
        "amountPaise": integer (positive),
        "currency": string, "direction": "DEBIT"|"CREDIT",
        "txnAtMillis": integer,
        "channel": string|null, "referenceNo": string|null,
        "status": "CONFIRMED"|"NEEDS_REVIEW", "notes": string|null
      },
      "confidence": float,
      "linksToCreate": [
        { "partnerParsedSmsId": integer,
          "linkType": "SELF_TRANSFER"|"REFUND_OF"|"REVERSAL_OF"|"SPLIT_OF",
          "confidence": float }
      ],
      "needsReview": boolean
    }
  ]
}

Rules:
- You MUST preserve the exact `parsedSmsId`, `rawSmsId`, `accountId`, and `merchantId` from the input resolutions for each corresponding transaction.
- Set needsReview=true if ANY of: confidence < 0.70, a critical
  field is missing or contradictory, or the resolution is
  ambiguous. The worker will route needsReview rows to the user's
  daily review queue rather than auto-committing.
- For a self-transfer pair (two resolutions, opposite directions,
  same amount, same day), include linksToCreate on BOTH commits
  pointing at each other with linkType="SELF_TRANSFER".
- For a refund, link the CREDIT commit to the DEBIT commit it
  reverses (linkType="REFUND_OF").
- If two resolutions look like a self-transfer pair, the link
  goes from DEBIT -> CREDIT (so "from" is the money-out side,
  "to" is the money-in side).
- Be conservative. If you are not sure, mark needsReview=true.
"""

    fun a3UserMessage(commits: List<Resolution>, daySummary: String): String = buildString {
        append("Resolutions:\n")
        append(JSON.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(A2Contract.serializer()),
            commits.map { A2Contract.fromResolution(it) }
        ))
        append("\n\nDay summary:\n")
        append(daySummary)
    }

    const val A3_CORRECTIVE_PROMPT =
        "Your previous response was not valid JSON. Respond with the JSON object only, " +
            "with a top-level \"commits\" array."

    // ---------------- Probe / readiness ----------------

    // The test-screen "I'm online" probe uses a separate system
    // instruction on GemmaInferenceEngine; see inference package.
}

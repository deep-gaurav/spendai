package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.RawSmsMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Prompt templates and JSON contracts for the on-device agents.
 *
 * Every agent's output is a strict JSON object — no prose, no markdown
 * fences. [AgentJsonParse] tolerates Gemma's tendency to add a
 * stray trailing comma or a sentence of preamble by stripping
 * everything outside the first `{ ... }` block.
 *
 * The JSON contracts are duplicated as kotlinx-serializable data
 * classes in [A1Contract] and [A2Contract] so the Kotlin side has a
 * typed view of what the model returned. There is no A3 anymore —
 * A2 resolves entities AND commits the transaction in a single
 * per-message call, so the day-batched commit step is gone.
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
- If the message is an OTP, marketing/promotional offer, coupon, recharge offer, partial system alert, pre-approved loan offer, or anything that is NOT a completed financial event on a real bank account, credit card, or active digital wallet, return kind="IGNORE" and null everywhere except confidence (set to 1.0).
- Explicitly IGNORE promotional, marketing, or coupon reward messages (e.g. "Credited: Rs 100 Wallet Credits! Get 24% OFF" or "Lenskart has credited 500 Gold credits" or "You have received 50 reward points"). These are marketing promos, not real money transactions.
- Note: Do NOT ignore real digital wallet top-up / loading events where the user adds money to a digital wallet (e.g., loading balance into Swiggy Money, Paytm, Amazon Pay, etc.). These represent real financial transactions and must be parsed as kind="TRANSACTION".
- amountPaise is the absolute value, never negative. direction carries
  the sign: DEBIT means money left the user's account; CREDIT means
  money came in. For purchases and ATM withdrawals: DEBIT. For salary,
  refunds, cashbacks, and payments received towards a credit card (money incoming to credit card account): CREDIT.
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

    // ---------------- A2: per-message entity resolver + committer ----------------

    /**
     * A2 takes a freshly parsed SMS plus the current local database
     * context (the top-N sources, accounts, and merchants) and
     * returns a single JSON object describing which existing rows to
     * link the transaction to, or which new rows to create. The
     * caller commits the resulting `spend_transaction` row in the
     * same Room transaction as the entity materialisation.
     *
     * Auto-commit is unconditional: if A1 returned `kind=TRANSACTION`,
     * A2 commits. A2's `a2Confidence` is preserved on the
     * transaction row only for the edit UI to surface — it does NOT
     * gate the commit. (A1's `kind=IGNORE` is the sole "don't commit"
     * gate; the pipeline routes IGNOREs to `markIgnored` before A2
     * is even called.)
     */
    const val A2_SYSTEM_INSTRUCTION = """
Link this SMS to existing rows or propose new ones. Return ONLY a
JSON object — no prose, no thinking, no explanation.

Schema (one object, top-level fields in this order):
{
  "source":  {"kind":"existing","sourceId":int,"confidence":float}
           | {"kind":"new","sourceKey":str,"deducedType":str,
              "suggestedBankName":str|null,
              "suggestedInstrumentType":"CARD"|"ACCOUNT"|"WALLET"|"UPI_HANDLE"|"UNKNOWN",
              "suggestedDisplayName":str|null,"confidence":float},
  "account": {"kind":"existing","accountId":int,"confidence":float}
           | {"kind":"new","instrumentType":"CARD"|"ACCOUNT"|"WALLET"|"UPI_HANDLE",
              "issuer":str,"maskedNumber":str,"currency":str,"confidence":float},
  "merchant":{"kind":"existing","merchantId":int,"confidence":float}
           | {"kind":"new","name":str,"normalizedName":str,"vpa":str|null,"confidence":float}
           | {"kind":"none","confidence":float},
  "a2Confidence": float in [0.0, 1.0],
  "title": str|null,
  "categoryName": str|null,
  "categoryEmoji": str|null,
  "duplicateOfTransactionId": int|null,
  "transferLinkWithTransactionId": int|null,
  "transferLinkType": "SELF_TRANSFER"|"REFUND_OF"|"REVERSAL_OF"|"SPLIT_OF"|null
}

Rules:
- Prefer "existing" when plausible (the user already labelled those rows).
- "new" only when no existing row fits.
- P2P UPI handle counterparty: merchant.kind="new" with vpa set, normalizedName = part before '@'.
- a2Confidence = MIN of the three candidate confidences. It does not gate commit.
- title (optional): a short, 2-6 word human label that captures the
  transaction. Use the merchant name when natural ("Lunch at Zomato",
  "Coffee at Third Wave"). For P2P UPI transfers without a merchant,
  use "UPI to <name>" or "UPI from <name>". For salary/credit, use
  "Salary" or "<Bank> Salary". For card autopay / subscription,
  use "<Merchant> subscription". Set to null if you cannot produce
  a clear, useful label.
- categoryName (optional): the most fitting category. Pick from
  common conventions (Food, Fuel, Salary, Subscription, Rent,
  Travel, Health, Groceries, Shopping, Transfer, Bills,
  Entertainment, Other) but you MAY introduce a new category name
  if none of those fit well. The same merchant should map to the
  same categoryName across transactions. Set to null if you are
  not confident enough to commit a category.
- categoryEmoji (optional, only meaningful alongside categoryName):
  a single emoji that visually represents the category
  (e.g. food->burger, fuel->pump, salary->money bag, subscription->tv,
  groceries->cart, transport->bus, shopping->bags, bills->receipt,
  entertainment->clapper, health->pill, transfer->arrows). Pick
  something the user will recognise at a glance. Defaults to a
  generic money emoji if omitted.
- duplicateOfTransactionId (optional): If the current SMS reports a transaction that is already recorded in the "recentTransactions" list of the database context, specify the duplicate transaction's ID here.
  * E.g., multiple credit SMS messages for a single transfer.
  * E.g., a purchase confirmation SMS (from Swiggy, Zomato, Amazon, etc.) and a bank account debit SMS for the same purchase are duplicates. Map the duplicate to the existing bank transaction.
  * CRITICAL: Do NOT mark a CREDIT as a duplicate of a DEBIT (or vice versa), even if they have the same amount and reference number. Opposite directions represent the two sides of a transfer, which should be linked using transferLinkWithTransactionId, not duplicateOfTransactionId.
  If set, we will NOT create a new transaction, but will update the existing transaction with any new info.
- transferLinkWithTransactionId (optional): If the current SMS represents the other side of a transfer, refund, or reversal transaction listed in the "recentTransactions" list of the database context, set this to the ID of that transaction. You can set BOTH duplicateOfTransactionId and transferLinkWithTransactionId if a transaction is a duplicate of a credit/debit and needs to be linked to the other side of a transfer.
- transferLinkType (optional): If transferLinkWithTransactionId is set, specify the link type (e.g., "SELF_TRANSFER" for own-account transfers, "REFUND_OF" for refunds, "REVERSAL_OF" for reversals).
- merchant isSelf handling: Each entry in knownMerchants has a boolean `isSelf` field and a `metadata` array. If a known merchant is marked isSelf=true, that counterparty is the user themself (their own name appearing in a UPI handle, their own card nickname, etc.). For any incoming SMS whose resolved counterparty is an isSelf merchant, set `merchant.kind = "none"` so the row drops out of spend / income aggregates. Do not invent a real merchant on behalf of a self event. Apply the same rule when the SMS describes a self-event (UPI to self, etc.) — the merchant on the receiving row may not be a real third party.
- merchant metadata handling: The `metadata` array carries freeform context the user has saved. A `CATEGORY_HINT` is the user's preferred category for the merchant; treat it as a strong default for `categoryName`. A `NOTE` is freeform context (e.g. "pani puri vendor"); honour it as a hint when picking `categoryName` and the `title`. A `LABEL` is a more specific display name; prefer it over the raw `name` when building `title`. Never invent metadata; only use what the bundle gives you.
"""

    fun a2UserMessage(parsed: ParsedSms, contextBundle: String): String = buildString {
        append("Parsed SMS:\n")
        append(JSON.encodeToString(A1Contract.serializer(), A1Contract.fromEntity(parsed)))
        append("\n\nDatabase context (recent sources / accounts / merchants):\n")
        append(contextBundle)
    }

    const val A2_CORRECTIVE_PROMPT =
        "{\"source\":{\"kind\":\"existing\",\"sourceId\":1,\"confidence\":0.9}," +
            "\"account\":{\"kind\":\"existing\",\"accountId\":1,\"confidence\":0.9}," +
            "\"merchant\":{\"kind\":\"existing\",\"merchantId\":1,\"confidence\":0.9}," +
            "\"a2Confidence\":0.9}"

    // ---------------- A3: transaction auditor ----------------
    
    @Serializable
    data class A3ContextTransaction(
        val id: Long,
        val rawSmsText: String,
        val amountPaise: Long,
        val direction: String,
        val accountId: Long,
        val accountLabel: String,
        val merchantName: String?,
        val referenceNo: String?,
        val title: String?
    )

    @Serializable
    data class A3CandidateInfo(
        val rawSmsText: String,
        val amountPaise: Long,
        val direction: String,
        val accountId: Long,
        val accountLabel: String,
        val merchantName: String?,
        val referenceNo: String?,
        val title: String?
    )

    const val A3_SYSTEM_INSTRUCTION = """
You are a private, on-device financial transaction auditor.
Your job is to review a candidate transaction (its raw SMS, parsed data, and resolved database entities) alongside the database context of the 20 closest recent transactions (including their raw SMS text and how they were committed).

You must output a single JSON object that matches the schema below. Do not add prose, thinking, explanation, or code fences.

Schema:
{
  "currentDecision": {
    "decision": "COMMIT" | "DUPLICATE" | "IGNORE",
    "accountId": integer | null,
    "merchantId": integer | null,
    "categoryId": integer | null,
    "direction": "DEBIT" | "CREDIT" | null,
    "amountPaise": integer | null,
    "title": string | null,
    "referenceNo": string | null,
    "duplicateOfTransactionId": integer | null,
    "transferLinkWithTransactionId": integer | null,
    "transferLinkType": "SELF_TRANSFER" | "REFUND_OF" | "REVERSAL_OF" | "SPLIT_OF" | null
  },
  "modifications": [
    {
      "transactionId": integer,
      "direction": "DEBIT" | "CREDIT" | null,
      "accountId": integer | null,
      "merchantId": integer | null,
      "categoryId": integer | null,
      "title": string | null,
      "referenceNo": string | null,
      "status": "DELETED" | null,
      "transferLinkWithTransactionId": integer | null,
      "transferLinkType": "SELF_TRANSFER" | "REFUND_OF" | "REVERSAL_OF" | "SPLIT_OF" | null
    }
  ]
}

Rules:
1. **Deduplication**:
   - If the current candidate is a duplicate of a transaction already in recentTransactions, set currentDecision.decision = "DUPLICATE" and duplicateOfTransactionId to that transaction's ID.
   - A duplicate is when the same financial event is reported twice on the same account (or a bank debit and its corresponding purchase confirmation from a merchant like Swiggy/Zomato).
   - CRITICAL: A CREDIT cannot be a duplicate of a DEBIT (and vice versa). Different directions are the two sides of a transfer, which must be linked, not marked as duplicates.
2. **Transfer Linking**:
   - If the current candidate represents the other side of a transfer (e.g. Debit from Slice and Credit to DBS, or Debit from Slice and Credit to IndusInd CC), set transferLinkWithTransactionId to the other transaction's ID, and transferLinkType to "SELF_TRANSFER".
   - If the current candidate is a duplicate of an existing transaction but that existing transaction was NOT linked to the other side of the transfer, you should set decision = "DUPLICATE", duplicateOfTransactionId = existingId, AND set transferLinkWithTransactionId to the transfer partner's ID. We will link the existing transaction to the partner.
3. **Double Check / Error Correction**:
   - Review the current candidate transaction against its raw SMS. Agent 1 or Agent 2 might have made a mistake (e.g. wrong direction, wrong account, wrong merchant, wrong category, wrong amount, wrong title, or wrong reference number). If you find any mistakes, correct them by specifying the correct values in the corresponding fields of `currentDecision`. Use null for these fields if no correction is needed.
   - Review the raw SMS texts of the recent transactions. If you notice a mistake in a previously committed transaction (e.g., A1/A2 parsed a credit card payment received as a DEBIT instead of a CREDIT), output a modification for that transactionId correcting its direction to "CREDIT" or other fields.
   - If a previous transaction was a duplicate that was incorrectly committed, you can output a modification for it with status = "DELETED" to remove it.
4. **Reminders & Fake Transactions**:
   - Explicitly IGNORE payment reminders, bills due, or system alerts (e.g. loan payment due reminder, credit card due reminder) which do not represent a completed money transfer. If such a reminder was previously committed as a transaction, output a modification for its transactionId with status = "DELETED" to remove it.
   - When a loan/credit card payment actually occurs, it should be logged as a DEBIT from the source bank account, and any corresponding CREDIT to the destination loan/card account should either be linked as a transfer or IGNORED so it's not double-counted (since it is the receiving end of a transfer).
   - E.g., if a transaction reminder of 45K was issued on June 8th (and added as debit), and the actual transaction happened on June 10th (and also added as debit), you MUST ignore/delete the June 8th reminder (mark its transactionId as status = "DELETED") and keep the actual June 10th debit. If there is a credit for it on June 11th to the loan account, that credit should be IGNORED (or linked as a transfer partner of the June 10th debit, not recorded as income/credit, since it's just paying the loan). This avoids double counting and keeps the net debit to exactly 1 debit of 45K.
"""

    fun a3UserMessage(
        recent: List<A3ContextTransaction>,
        candidate: A3CandidateInfo
    ): String = a3UserMessage(recent, candidate, emptyList(), null)

    /**
     * Full A3 user message with optional manual corrections and a
     * one-off override prompt. The corrections are the user's
     * persisted rules (newest first, capped at the repo's
     * MAX_INJECTED). The override is the prompt the user typed
     * for this specific run; it sits at the top of the message so
     * the model sees it first.
     */
    fun a3UserMessage(
        recent: List<A3ContextTransaction>,
        candidate: A3CandidateInfo,
        manualCorrections: List<ManualCorrectionRow>,
        overridePrompt: String?,
    ): String = buildString {
        if (!overridePrompt.isNullOrBlank()) {
            append("## Override for this run\n")
            append("The user has provided the following instruction for this single audit. Honour it as a hard override for the candidate below.\n")
            append(overridePrompt.trim())
            append("\n\n")
        }
        if (manualCorrections.isNotEmpty()) {
            append("## Manual corrections (user rules)\n")
            append("The user has confirmed the following rules. Apply them as additional constraints when deciding COMMIT / DUPLICATE / IGNORE and when setting transfer links. Treat each rule as a hard override for the situations it describes.\n")
            manualCorrections.forEachIndexed { i, c ->
                append("")
                append(i + 1).append(". [")
                append(c.timestampLabel).append("] ")
                if (c.linkedSmsIds.isNotEmpty()) {
                    append("(rawSmsId=").append(c.rawSmsId).append(", linked=[")
                    append(c.linkedSmsIds.joinToString(",")).append("]) ")
                } else {
                    append("(rawSmsId=").append(c.rawSmsId).append(") ")
                }
                append(c.userPrompt).append("\n")
            }
            append("\n")
        }
        append("Recent Transactions (Database Context):\n")
        append(JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(A3ContextTransaction.serializer()), recent))
        append("\n\nCurrent Candidate Transaction:\n")
        append(JSON.encodeToString(A3CandidateInfo.serializer(), candidate))
    }

    /**
     * Lightweight DTO for the manual corrections A3 receives.
     * Decouples the prompt formatter from the Room entity so the
     * formatter stays trivially testable.
     */
    data class ManualCorrectionRow(
        val rawSmsId: Long,
        val linkedSmsIds: List<Long>,
        val userPrompt: String,
        val timestampLabel: String,
    )

    const val A3_CORRECTIVE_PROMPT =
        "Your previous response was not valid JSON. Respond with the JSON object only, " +
            "no prose, no code fences, no explanation. Start with '{' and end with '}'."

    // ---------------- Probe / readiness ----------------

    // The test-screen "I'm online" probe uses a separate system
    // instruction on GemmaInferenceEngine; see inference package.
}

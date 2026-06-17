package com.spendai.app.domain.model

/**
 * Pure-Kotlin normaliser for merchant names. Strips corporate suffixes,
 * collapses whitespace, lowercases. The result is the dedup key on
 * [com.spendai.app.data.local.entity.Merchant.normalizedName].
 *
 * No LLM, no network — this must be deterministic so two different
 * SMS that mention "Zomato" and "ZOMATO PVT LTD" both resolve to the
 * same `merchant.id`.
 */
object MerchantNormalizer {

    private val SUFFIXES = listOf(
        "private limited",
        "pvt ltd",
        "pvt. ltd.",
        "pvt. ltd",
        "pvt ltd.",
        "ltd",
        "ltd.",
        "limited",
        "india",
        "inc",
        "inc.",
        "llc",
        "llp",
    )

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var current = raw.lowercase().trim()
        // Iterate: strip suffix then trailing punctuation, until no change.
        repeat(3) {
            val before = current
            for (suffix in SUFFIXES) {
                if (current.endsWith(" $suffix")) {
                    current = current.removeSuffix(" $suffix")
                }
            }
            current = current.trimEnd('.', ',', '-', '_', '#', '*', '!', '?', '\'', '"')
                .replace(Regex("\\s+"), " ").trim()
            if (current == before) return current
        }
        return current
    }
}

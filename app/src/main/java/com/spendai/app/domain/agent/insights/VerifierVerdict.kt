package com.spendai.app.domain.agent.insights

import kotlinx.serialization.Serializable

/**
 * The verdict the verifier model emits at the end of its
 * grounding check.
 *
 * The verifier takes four inputs - the user's question, the
 * SQL the agent ran, the rows the tool returned, and the
 * agent's free-form answer - and decides whether the answer's
 * specific claims are supported by the rows.
 *
 * The contract is intentionally tiny so the verifier's JSON
 * is short and parseable. The orchestrator only acts on
 * [verdict] == "fabricated" combined with a non-empty
 * [unverifiedClaims]; everything else is a pass.
 */
@Serializable
data class VerifierVerdict(
    val verdict: String,
    val unverifiedClaims: List<String> = emptyList(),
    val evidence: String = "",
) {
    /**
     * True when the model should NOT accept the answer.
     * `verdict == "fabricated"` AND there is at least one
     * specific unverified claim to point at. A bare
     * "fabricated" with an empty list is treated as a pass
     * because there is nothing concrete to ask the model to
     * fix on the re-prompt.
     */
    val isFabrication: Boolean
        get() = verdict.equals("fabricated", ignoreCase = true) && unverifiedClaims.isNotEmpty()

    companion object {
        /**
         * Default verdict the orchestrator assumes when the
         * verifier call itself fails (engine error, malformed
         * JSON, timeout). Defaulting to "fabricated" is the
         * safe path: it forces a re-prompt rather than
         * silently trusting the model's answer.
         */
        val FAIL_SAFE: VerifierVerdict = VerifierVerdict(
            verdict = "fabricated",
            unverifiedClaims = listOf("(verifier call failed; please re-verify your own answer)"),
            evidence = "The grounding verifier could not run. Treat the previous answer as unverified.",
        )
    }
}

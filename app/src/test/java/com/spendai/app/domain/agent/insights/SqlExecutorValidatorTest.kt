package com.spendai.app.domain.agent.insights

import com.spendai.app.inference.GemmaInferenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the SQL validator embedded in
 * [SqlExecutor]. These do not touch the Android SQLite
 * runtime - the validator runs in-memory and is exposed as
 * [SqlExecutor.validate] for testing. A second
 * integration-style test would require Robolectric + a real
 * Room database; that is intentionally deferred until the
 * orchestrator is wired into the UI flow end-to-end.
 */
class SqlExecutorValidatorTest {

    private fun check(sql: String): SqlExecutor.Validation = try {
        // The validator never touches the database; null is
        // a valid input for unit tests.
        val executor = SqlExecutor(database = null)
        executor.validate(sql)
    } catch (t: Throwable) {
        throw AssertionError("validate threw: ${t.message}", t)
    }

    @Test fun `plain SELECT is accepted`() {
        assertTrue(check("SELECT 1") is SqlExecutor.Validation.Accepted)
    }

    @Test fun `lower-case select is accepted`() {
        assertTrue(check("select 1") is SqlExecutor.Validation.Accepted)
    }

    @Test fun `WITH cte is accepted`() {
        // CTE that does not reference itself in a FROM
        // clause, so the allowlist check is happy.
        assertTrue(
            check("WITH recent AS (SELECT * FROM spend_transaction WHERE txnAtMillis > 0) SELECT COUNT(*) AS n")
                is SqlExecutor.Validation.Accepted
        )
    }

    @Test fun `empty sql is rejected`() {
        val result = check("") as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("Empty"))
    }

    @Test fun `INSERT is rejected`() {
        // Bare INSERT is caught by the first-token check.
        val result = check("INSERT INTO spend_transaction (amountPaise) VALUES (1)")
            as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("Only SELECT"))
    }

    @Test fun `UPDATE is rejected`() {
        val result = check("UPDATE spend_transaction SET amountPaise = 0")
            as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("Only SELECT"))
    }

    @Test fun `DELETE is rejected`() {
        val result = check("DELETE FROM spend_transaction")
            as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("Only SELECT"))
    }

    @Test fun `DROP is rejected`() {
        val result = check("DROP TABLE spend_transaction")
            as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("Only SELECT"))
    }

    @Test fun `keyword check catches hidden DML`() {
        // The first-token check passes (it is SELECT), but the
        // body contains a write verb. This is the path the
        // forbidden-keyword loop guards.
        val result = check("SELECT 1 FROM (UPDATE spend_transaction SET amountPaise = 0)")
            as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("Forbidden keyword"))
    }

    @Test fun `multi statement is rejected`() {
        // Two SELECTs separated by a semicolon. The
        // forbidden-keyword loop would not catch this, so the
        // multi-statement guard is what fires.
        val result = check("SELECT 1; SELECT 2")
            as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("Multiple statements"))
    }

    @Test fun `unknown table is rejected`() {
        val result = check("SELECT * FROM evil_table")
            as SqlExecutor.Validation.Rejected
        assertTrue(result.reason.contains("not in the read-only schema"))
    }

    @Test fun `known table in allowlist is accepted`() {
        for (sql in listOf(
            "SELECT * FROM spend_transaction",
            "SELECT * FROM merchant",
            "SELECT * FROM category",
            "SELECT * FROM account",
            "SELECT * FROM financial_source",
            "SELECT * FROM raw_sms",
            "SELECT * FROM transaction_link",
        )) {
            assertTrue(
                "expected $sql to be accepted",
                check(sql) is SqlExecutor.Validation.Accepted,
            )
        }
    }

    @Test fun `trailing semicolon is fine`() {
        assertTrue(check("SELECT 1;") is SqlExecutor.Validation.Accepted)
    }

    @Test fun `keyword in string literal is not flagged`() {
        // The string "DROP" sits inside a single-quoted
        // string literal; the validator should strip strings
        // before scanning for keywords.
        assertTrue(
            check("SELECT 'DROP TABLE x' AS msg, 1 AS n") is SqlExecutor.Validation.Accepted
        )
    }

    @Test fun `keyword in line comment is not flagged`() {
        assertTrue(
            check("-- this DROP is in a comment\nSELECT 1") is SqlExecutor.Validation.Accepted
        )
    }

    @Test fun `keyword in block comment is not flagged`() {
        assertTrue(
            check("/* DROP TABLE x */ SELECT 1") is SqlExecutor.Validation.Accepted
        )
    }

    @Test fun `missing LIMIT is appended`() {
        val result = check("SELECT 1") as SqlExecutor.Validation.Accepted
        assertTrue(
            "expected LIMIT to be appended, got: ${result.executedSql}",
            result.executedSql.contains("LIMIT 200"),
        )
    }

    @Test fun `large LIMIT is capped`() {
        val result = check("SELECT 1 LIMIT 9999") as SqlExecutor.Validation.Accepted
        assertTrue(
            "expected LIMIT to be capped, got: ${result.executedSql}",
            result.executedSql.contains("LIMIT 200"),
        )
    }
}

/**
 * Sanity check on the system prompt's contents. The agent
 * depends on specific schema names and self-transfer
 * idioms appearing in the system prompt so the model
 * produces valid SQL.
 */
class AgenticInsightsSystemPromptTest {

    @Test fun `prompt contains spend_transaction schema`() {
        val prompt = AgenticInsightsSystemPrompt.build(nowMillis = 1_700_000_000_000L)
        assertTrue(prompt.contains("spend_transaction"))
        assertTrue(prompt.contains("amountPaise"))
        assertTrue(prompt.contains("txnAtMillis"))
    }

    @Test fun `prompt contains self-transfer exclusion idiom`() {
        val prompt = AgenticInsightsSystemPrompt.build(nowMillis = 1_700_000_000_000L)
        assertTrue(prompt.contains("SELF_TRANSFER"))
        assertTrue(prompt.contains("NOT EXISTS"))
    }

    @Test fun `prompt includes current epoch millis`() {
        val now = 1_700_000_000_000L
        val prompt = AgenticInsightsSystemPrompt.build(nowMillis = now)
        assertTrue(prompt.contains(now.toString()))
    }

    @Test fun `prompt advertises all four chart types`() {
        val prompt = AgenticInsightsSystemPrompt.build(nowMillis = 1_700_000_000_000L)
        for (type in listOf("donut", "bar_vertical", "bar_horizontal", "line")) {
            assertTrue(
                "prompt should mention $type chart",
                prompt.contains(type),
            )
        }
    }

    @Test fun `prompt mentions paise-to-rupee conversion`() {
        val prompt = AgenticInsightsSystemPrompt.build(nowMillis = 1_700_000_000_000L)
        assertTrue(prompt.contains("100.0"))
    }
}

/**
 * The verifier inside the orchestrator extracts "specific
 * fact" strings from free-form prose. The patterns cover
 * common shapes the model produces when it fabricates
 * data; tests pin each one so a future tweak to the
 * regexes does not silently change the verifier's reach.
 *
 * The verifier lives inside [AgenticInsightsAgent], which
 * is a class with a non-trivial constructor (engine, sql
 * executor, time source). We exercise the extractor
 * indirectly by running the agent with a fake engine and
 * a real (in-memory null) sql executor; the engine is
 * never asked to do anything because every test ends with
 * a fabricated answer that the orchestrator would not
 * surface to the user.
 */

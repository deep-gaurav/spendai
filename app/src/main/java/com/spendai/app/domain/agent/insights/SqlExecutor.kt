package com.spendai.app.domain.agent.insights

import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.spendai.app.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bounded, read-only SQL gateway the agentic flow uses to
 * touch the database.
 *
 * ## Why a custom executor (instead of a Room @RawQuery DAO)
 *
 * Room's `RawQuery` requires a Kotlin function whose body is
 * supplied at call time, which cannot be loaded from an
 * LLM-generated string at runtime. The cleanest alternative is
 * to drop down to the underlying `SupportSQLiteDatabase` and
 * run the query directly. The risk is that the LLM can produce
 * arbitrary SQL, so this layer is the entire safety boundary
 * between the model and the user's data.
 *
 * ## Hard rules enforced here
 *
 *  1. Read-only. The first non-whitespace, non-comment token
 *     must be `SELECT` or `WITH`. CTEs (`WITH ... SELECT`) are
 *     allowed; recursive CTEs are not specifically blocked, but
 *     the orchestrator's `LIMIT` cap keeps them bounded.
 *  2. Single statement. No semicolons followed by another
 *     statement. PRAGMA, ATTACH, VACUUM, REINDEX and friends
 *     are forbidden.
 *  3. Allowlisted tables. The schema section of the system
 *     prompt is the only place we list tables. Any reference to
 *     a table outside that list is rejected. Column-level
 *     allowlisting is intentionally not done — Room can be
 *     extended in future versions and the LLM should be free to
 *     select the columns the schema advertises.
 *  4. Forced LIMIT. The model is asked to set a sensible LIMIT.
 *     If it does not, the executor appends `LIMIT 200`. If it
 *     does, the executor caps it at [MAX_ROW_CAP]. This is the
 *     ceiling that protects the UI from a runaway query.
 *  5. Bounded execution. The query runs on Dispatchers.IO. The
 *     row iteration reads the cursor lazily so a 200-row
 *     response with 20 columns stays well under a few hundred
 *     KB of JSON.
 *
 * ## Why a regex pre-pass (and not a real SQL parser)
 *
 * A real SQL parser (e.g. JSqlParser) would give us perfect
 * comment and string-literal stripping. The cost is a 1+ MB
 * dependency and a slower call. The regex pre-pass below
 * handles the cases we care about for v1: line comments, block
 * comments, single-quoted string literals, and double-quoted
 * identifiers. False negatives (e.g. an exotic escape inside a
 * string) are caught by the read-only check on the *stripped*
 * statement, not the raw one.
 */
class SqlExecutor(private val database: AppDatabase?) {

    /**
     * One row from a successful query, materialised as a
     * `Map<String, JsonElement>` so it round-trips through
     * `kotlinx.serialization` without losing type information
     * (numbers stay numbers, strings stay strings, nulls stay
     * `JsonNull`).
     */
    @Serializable
    data class QueryResult(
        val columns: List<String>,
        val rows: List<Map<String, JsonElement>>,
        val rowCount: Int,
        val truncated: Boolean,
    )

    /**
     * Run a SELECT against the user's database. The query is
     * validated before execution; on any validation failure the
     * returned [Result] is empty with `truncated = false` and
     * [lastError] describes the rejection reason. The model
     * sees this error message on the next turn and can fix the
     * query.
     *
     * @param sql the model-supplied SQL
     * @return the [Result] plus a [lastError] string (null on
     *   success). Two return fields keep the call site linear
     *   and avoid a sealed-class for what is, at the wire
     *   level, a single JSON object.
     */
    suspend fun run(
        sql: String,
    ): Pair<QueryResult, String?> = withContext(Dispatchers.IO) {
        val validation = validate(sql)
        if (validation is Validation.Rejected) {
            Log.w(TAG, "SQL rejected: ${validation.reason}")
            return@withContext EMPTY_RESULT to validation.reason
        }
        val accepted = validation as Validation.Accepted
        val db = database?.openHelper?.readableDatabase
            ?: return@withContext EMPTY_RESULT to "Database is not available."
        try {
            val result = executeAndMaterialize(db, accepted.executedSql)
            result to null
        } catch (t: Throwable) {
            Log.w(TAG, "SQL execution failed: ${t.message}", t)
            EMPTY_RESULT to "Execution failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * Result of the validation step. [Accepted] carries the
     * possibly-modified SQL (with LIMIT injected / capped) so
     * the executor does not need to re-parse.
     */
    internal sealed class Validation {
        data class Accepted(val executedSql: String) : Validation()
        data class Rejected(val reason: String) : Validation()
    }

    internal fun validate(rawSql: String): Validation {
        val trimmed = rawSql.trim()
        if (trimmed.isEmpty()) {
            return Validation.Rejected("Empty SQL")
        }

        // Strip comments and string literals so the keyword
        // check does not false-positive on text inside a string.
        // We replace the contents with spaces of the same length
        // to keep the offset of subsequent tokens stable.
        val stripped = stripCommentsAndStrings(trimmed)

        // First non-whitespace token must be SELECT or WITH.
        val firstToken = stripped.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() }
            ?: return Validation.Rejected("Could not parse first token")
        val upper = firstToken.uppercase()
        if (upper != "SELECT" && upper != "WITH") {
            return Validation.Rejected(
                "Only SELECT (or WITH ... SELECT) is allowed; got '$firstToken'",
            )
        }

        // Reject write side-effects. The list intentionally
        // covers anything that can mutate the database.
        val forbidden = listOf(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
            "REPLACE", "ATTACH", "DETACH", "VACUUM", "REINDEX",
            "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "PRAGMA",
            "TRUNCATE",
        )
        for (keyword in forbidden) {
            val pattern = Regex("\\b" + keyword + "\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(stripped)) {
                return Validation.Rejected("Forbidden keyword: $keyword")
            }
        }

        // Allowlisted tables. We strip the same string/comment
        // bodies the keyword check used, then scan for any
        // `FROM` / `JOIN` clause and verify the referenced
        // table is in the allowlist.
        for (table in referencedTables(stripped)) {
            if (table !in ALLOWED_TABLES) {
                return Validation.Rejected(
                    "Table '$table' is not in the read-only schema. " +
                        "Allowed: ${ALLOWED_TABLES.joinToString(", ")}",
                )
            }
        }

        // Multi-statement check. Reject any semicolon that is
        // not the trailing terminator.
        val noTrailing = trimmed.trimEnd().trimEnd(';')
        if (';' in noTrailing) {
            return Validation.Rejected("Multiple statements are not allowed")
        }

        // LIMIT enforcement. If absent, append `LIMIT 200`. If
        // present and greater than [MAX_ROW_CAP], cap it.
        val (withLimit, limitTouched) = enforceLimit(trimmed)
        if (limitTouched != null) {
            Log.d(TAG, "LIMIT policy applied: $limitTouched")
        }
        return Validation.Accepted(withLimit)
    }

    private fun stripCommentsAndStrings(input: String): String {
        val out = CharArray(input.length)
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            // Line comment -- ... \n
            if (c == '-' && i + 1 < n && input[i + 1] == '-') {
                while (i < n && input[i] != '\n') {
                    out[i] = ' '
                    i++
                }
                continue
            }
            // Block comment /* ... */
            if (c == '/' && i + 1 < n && input[i + 1] == '*') {
                out[i] = ' '
                out[i + 1] = ' '
                i += 2
                while (i + 1 < n && !(input[i] == '*' && input[i + 1] == '/')) {
                    out[i] = ' '
                    i++
                }
                if (i + 1 < n) {
                    out[i] = ' '
                    out[i + 1] = ' '
                    i += 2
                }
                continue
            }
            // Single-quoted string literal. SQLite uses '' to
            // escape a single quote inside a string.
            if (c == '\'') {
                out[i] = ' '
                i++
                while (i < n) {
                    if (input[i] == '\'' && i + 1 < n && input[i + 1] == '\'') {
                        out[i] = ' '
                        out[i + 1] = ' '
                        i += 2
                        continue
                    }
                    if (input[i] == '\'') {
                        out[i] = ' '
                        i++
                        break
                    }
                    out[i] = ' '
                    i++
                }
                continue
            }
            // Double-quoted identifier. Replace with spaces but
            // keep the length.
            if (c == '"') {
                out[i] = ' '
                i++
                while (i < n) {
                    if (input[i] == '"' && i + 1 < n && input[i + 1] == '"') {
                        out[i] = ' '
                        out[i + 1] = ' '
                        i += 2
                        continue
                    }
                    if (input[i] == '"') {
                        out[i] = ' '
                        i++
                        break
                    }
                    out[i] = ' '
                    i++
                }
                continue
            }
            out[i] = c
            i++
        }
        return String(out)
    }

    /**
     * Extract every table name referenced in FROM / JOIN
     * clauses. This is a deliberately conservative scan: it
     * returns the token immediately after `FROM` or `JOIN`, then
     * additionally captures tokens following `JOIN` variants.
     * Aliases are stripped. Subqueries are not analysed
     * recursively, but because we only allow SELECTs the
     * outermost FROM/JOIN is what matters for the allowlist.
     */
    private fun referencedTables(stripped: String): Set<String> {
        val out = mutableSetOf<String>()
        val pattern = Regex(
            "(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)",
            RegexOption.IGNORE_CASE,
        )
        for (match in pattern.findAll(stripped)) {
            val raw = match.groupValues[1]
            out.add(raw.lowercase())
        }
        return out
    }

    /**
     * Returns the SQL with a LIMIT clause enforced. The
     * returned second value describes what was changed (or
     * null if no change was needed) so the caller can log it.
     */
    private fun enforceLimit(sql: String): Pair<String, String?> {
        val match = Regex("\\bLIMIT\\s+(\\d+)", RegexOption.IGNORE_CASE).find(sql)
        if (match == null) {
            val cleaned = sql.trimEnd().trimEnd(';')
            return "$cleaned LIMIT $MAX_ROW_CAP" to "appended LIMIT $MAX_ROW_CAP"
        }
        val n = match.groupValues[1].toLongOrNull() ?: return sql.trimEnd().trimEnd(';') + " LIMIT $MAX_ROW_CAP" to "appended LIMIT $MAX_ROW_CAP"
        if (n > MAX_ROW_CAP) {
            val replaced = sql.replaceRange(match.range, "LIMIT $MAX_ROW_CAP")
            return replaced to "capped LIMIT $n to $MAX_ROW_CAP"
        }
        return sql to null
    }

    private fun executeAndMaterialize(
        db: SupportSQLiteDatabase,
        sql: String,
    ): QueryResult {
        val query: SupportSQLiteQuery = androidx.sqlite.db.SimpleSQLiteQuery(sql)
        val cursor: Cursor = db.query(query)
        cursor.use { c ->
            val columnNames = c.columnNames.toList()
            val rows = ArrayList<Map<String, JsonElement>>(c.count.coerceAtMost(MAX_ROW_CAP))
            var count = 0
            while (c.moveToNext() && count < MAX_ROW_CAP) {
                val row = LinkedHashMap<String, JsonElement>(columnNames.size)
                for ((idx, name) in columnNames.withIndex()) {
                    row[name] = cursorValueAsJson(c, idx)
                }
                rows.add(row)
                count++
            }
            val truncated = c.count > MAX_ROW_CAP
            return QueryResult(
                columns = columnNames,
                rows = rows,
                rowCount = rows.size,
                truncated = truncated,
            )
        }
    }

    private fun cursorValueAsJson(c: Cursor, idx: Int): JsonElement {
        return when (c.getType(idx)) {
            Cursor.FIELD_TYPE_NULL -> JsonNull
            Cursor.FIELD_TYPE_INTEGER -> {
                val v = c.getLong(idx)
                JsonPrimitive(v)
            }
            Cursor.FIELD_TYPE_FLOAT -> {
                val v = c.getDouble(idx)
                JsonPrimitive(v)
            }
            Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(idx) ?: "")
            Cursor.FIELD_TYPE_BLOB -> {
                // BLOB columns are rare in our schema. Surface a
                // stringy placeholder rather than base64 — the
                // model never needs to see raw bytes.
                val len = try { c.getBlob(idx).size } catch (_: Throwable) { 0 }
                JsonPrimitive("<blob:$len bytes>")
            }
            else -> JsonNull
        }
    }

    companion object {
        private const val TAG = "SqlExecutor"

        /**
         * Hard cap on rows returned to the model. The
         * orchestrator can lower this, but 200 is the ceiling
         * enforced by [enforceLimit].
         */
        const val MAX_ROW_CAP: Int = 200

        /**
         * The set of tables the agent is allowed to read from.
         * Keep this in sync with the schema section of
         * [AgenticInsightsSystemPrompt].
         */
        val ALLOWED_TABLES: Set<String> = setOf(
            "spend_transaction",
            "merchant",
            "category",
            "account",
            "financial_source",
            "raw_sms",
            "transaction_link",
        )

        private val EMPTY_RESULT = SqlExecutor.QueryResult(
            columns = emptyList(),
            rows = emptyList(),
            rowCount = 0,
            truncated = false,
        )

        /**
         * Convenience for the agent orchestrator: render a
         * [Result] as a compact JSON object the model can
         * re-read. Kept here (not in the orchestrator) so the
         * format is owned by the same module that decides what
         * the rows look like.
         */
        fun resultAsJson(result: QueryResult): String {
            val rowSerializer = ListSerializer(
                MapSerializer(String.serializer(), JsonElement.serializer())
            )
            val rowsLiteral = Json.encodeToString(rowSerializer, result.rows)
            val obj = buildJsonObject {
                put("status", "success")
                put("rowCount", result.rowCount)
                // `isEmpty` + `hint` make the zero-row case
                // impossible to gloss over. The Gemma 4 31B
                // model otherwise fabricates values from prior
                // knowledge of the user; the explicit "do not
                // invent" hint is the single most effective
                // mitigation we have at the prompt layer.
                put("isEmpty", result.rows.isEmpty())
                if (result.rows.isEmpty()) {
                    put(
                        "hint",
                        "No rows matched. The query succeeded but the database " +
                            "returned zero matching records. You MUST NOT invent a " +
                            "date, amount, merchant, or any other fact. Tell the user " +
                            "you have no matching data and suggest a wider range or a " +
                            "different filter.",
                    )
                }
                put("columns", result.columns.joinToString(","))
                put("rows", JsonPrimitive(rowsLiteral))
                put("truncated", result.truncated)
            }
            return obj.toString()
        }
    }
}

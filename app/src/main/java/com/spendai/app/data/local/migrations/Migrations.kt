package com.spendai.app.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: introduces the multi-agent pipeline tables.
 *
 *   - Evolves [com.spendai.app.data.local.entity.FinancialSource] with
 *     display name, bank name, account last4, instrument type, status
 *     and confirmedAt. All columns are additive; existing rows get the
 *     `status = NEEDS_REVIEW` default.
 *   - Evolves [com.spendai.app.data.local.entity.RawSmsMessage] with
 *     `parsedSmsId` (nullable FK to [com.spendai.app.data.local.entity.ParsedSms]).
 *     The FK is not enforced for v1-migrated rows because SQLite can't
 *     ADD CONSTRAINT via ALTER — v2's CREATE TABLE for raw_sms enforces
 *     it for new rows.
 *   - Creates parsed_sms, account, merchant, transaction,
 *     transaction_link, pending_review with the indexes Room would
 *     generate for v2.
 *
 * Order matters: parsed_sms is created BEFORE the ALTER on raw_sms
 * adds parsedSmsId, because the column conceptually points at it.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. parsed_sms — must exist before raw_sms.parsedSmsId is added.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parsed_sms` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`rawSmsId` INTEGER NOT NULL, " +
                "`parsedAt` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`amountPaise` INTEGER, " +
                "`currency` TEXT, " +
                "`direction` TEXT, " +
                "`txnAtMillis` INTEGER, " +
                "`channel` TEXT, " +
                "`sourceKeyHint` TEXT, " +
                "`merchantRaw` TEXT, " +
                "`cardLast4Hint` TEXT, " +
                "`accountLast4Hint` TEXT, " +
                "`referenceNo` TEXT, " +
                "`a1Confidence` REAL NOT NULL, " +
                "`a1RawJson` TEXT NOT NULL, " +
                "FOREIGN KEY(`rawSmsId`) REFERENCES `raw_sms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_parsed_sms_rawSmsId` " +
                "ON `parsed_sms` (`rawSmsId`)"
        )

        // 2. account
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `account` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceId` INTEGER NOT NULL, " +
                "`instrumentType` TEXT NOT NULL, " +
                "`issuer` TEXT NOT NULL, " +
                "`maskedNumber` TEXT NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`holderName` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sourceId`) REFERENCES `financial_source`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_account_sourceId_maskedNumber` " +
                "ON `account` (`sourceId`, `maskedNumber`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_account_sourceId` " +
                "ON `account` (`sourceId`)"
        )

        // 3. merchant
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`normalizedName` TEXT NOT NULL, " +
                "`vpa` TEXT, " +
                "`category` TEXT, " +
                "`firstSeenAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_normalizedName` " +
                "ON `merchant` (`normalizedName`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_merchant_vpa` " +
                "ON `merchant` (`vpa`)"
        )

        // 4. transaction
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `spend_transaction` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER NOT NULL, " +
                "`merchantId` INTEGER, " +
                "`rawSmsId` INTEGER NOT NULL, " +
                "`parsedSmsId` INTEGER NOT NULL, " +
                "`amountPaise` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`direction` TEXT NOT NULL, " +
                "`txnAtMillis` INTEGER NOT NULL, " +
                "`channel` TEXT, " +
                "`referenceNo` TEXT, " +
                "`status` TEXT NOT NULL DEFAULT 'CONFIRMED', " +
                "`confidence` REAL NOT NULL, " +
                "`notes` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                "FOREIGN KEY(`merchantId`) REFERENCES `merchant`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL, " +
                "FOREIGN KEY(`rawSmsId`) REFERENCES `raw_sms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`parsedSmsId`) REFERENCES `parsed_sms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_txnAtMillis` " +
                "ON `spend_transaction` (`txnAtMillis`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_accountId_txnAtMillis` " +
                "ON `spend_transaction` (`accountId`, `txnAtMillis`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_merchantId` " +
                "ON `spend_transaction` (`merchantId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_rawSmsId` " +
                "ON `spend_transaction` (`rawSmsId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_parsedSmsId` " +
                "ON `spend_transaction` (`parsedSmsId`)"
        )

        // 5. transaction_link
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transaction_link` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`fromTransactionId` INTEGER NOT NULL, " +
                "`toTransactionId` INTEGER NOT NULL, " +
                "`linkType` TEXT NOT NULL, " +
                "`confidence` REAL NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`fromTransactionId`) REFERENCES `spend_transaction`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`toTransactionId`) REFERENCES `spend_transaction`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_transaction_link_fromTransactionId_toTransactionId_linkType` " +
                "ON `transaction_link` (`fromTransactionId`, `toTransactionId`, `linkType`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_link_fromTransactionId` " +
                "ON `transaction_link` (`fromTransactionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transaction_link_toTransactionId` " +
                "ON `transaction_link` (`toTransactionId`)"
        )

        // 6. pending_review
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_review` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`targetId` INTEGER NOT NULL, " +
                "`promptSummary` TEXT NOT NULL, " +
                "`suggestedJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`resolvedAt` INTEGER, " +
                "`resolution` TEXT)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_review_kind_resolvedAt` " +
                "ON `pending_review` (`kind`, `resolvedAt`)"
        )

        // 7. Evolve raw_sms by rebuilding it (so we can add the FK
        //    to parsed_sms — SQLite can't ALTER TABLE ADD CONSTRAINT).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `raw_sms_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`senderAddress` TEXT NOT NULL, " +
                "`msgBody` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`parsedSmsId` INTEGER, " +
                "FOREIGN KEY(`parsedSmsId`) REFERENCES `parsed_sms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL(
            "INSERT INTO `raw_sms_new` (id, senderAddress, msgBody, timestamp, status) " +
                "SELECT id, senderAddress, msgBody, timestamp, status FROM `raw_sms`"
        )
        db.execSQL("DROP TABLE `raw_sms`")
        db.execSQL("ALTER TABLE `raw_sms_new` RENAME TO `raw_sms`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_raw_sms_status` " +
                "ON `raw_sms` (`status`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_sms_senderAddress_timestamp` " +
                "ON `raw_sms` (`senderAddress`, `timestamp`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_raw_sms_parsedSmsId` " +
                "ON `raw_sms` (`parsedSmsId`)"
        )

        // 8. Evolve financial_source (additive).
        db.execSQL("ALTER TABLE `financial_source` ADD COLUMN `displayName` TEXT")
        db.execSQL("ALTER TABLE `financial_source` ADD COLUMN `bankName` TEXT")
        db.execSQL("ALTER TABLE `financial_source` ADD COLUMN `accountLast4` TEXT")
        db.execSQL(
            "ALTER TABLE `financial_source` ADD COLUMN `instrumentType` " +
                "TEXT NOT NULL DEFAULT 'UNKNOWN'"
        )
        db.execSQL(
            "ALTER TABLE `financial_source` ADD COLUMN `status` " +
                "TEXT NOT NULL DEFAULT 'NEEDS_REVIEW'"
        )
        db.execSQL(
            "ALTER TABLE `financial_source` ADD COLUMN `confirmedAt` INTEGER"
        )
    }
}

/**
 * v2 → v3: adds the [com.spendai.app.data.local.entity.IngestionLog]
 * audit table. Purely additive — every existing row is preserved,
 * the only new artefact is the `ingestion_log` table and its three
 * supporting indices.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ingestion_log` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`rawSmsId` INTEGER NOT NULL, " +
                "`parsedSmsId` INTEGER, " +
                "`transactionId` INTEGER, " +
                "`ingestedAt` INTEGER NOT NULL, " +
                "`a1Outcome` TEXT NOT NULL, " +
                "`a1Confidence` REAL, " +
                "`a1Prompt` TEXT, " +
                "`a1Response` TEXT, " +
                "`a1Error` TEXT, " +
                "`a2Outcome` TEXT, " +
                "`a2Confidence` REAL, " +
                "`a2Prompt` TEXT, " +
                "`a2Response` TEXT, " +
                "`a2Error` TEXT, " +
                "FOREIGN KEY(`rawSmsId`) REFERENCES `raw_sms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`parsedSmsId`) REFERENCES `parsed_sms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL, " +
                "FOREIGN KEY(`transactionId`) REFERENCES `spend_transaction`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ingestion_log_rawSmsId` " +
                "ON `ingestion_log` (`rawSmsId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ingestion_log_parsedSmsId` " +
                "ON `ingestion_log` (`parsedSmsId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ingestion_log_transactionId` " +
                "ON `ingestion_log` (`transactionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ingestion_log_ingestedAt` " +
                "ON `ingestion_log` (`ingestedAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ingestion_log_a2Outcome` " +
                "ON `ingestion_log` (`a2Outcome`)"
        )
    }
}

/**
 * v5 → v6: idempotency columns on `raw_sms`.
 *
 * Adds two nullable columns to the existing `raw_sms` table:
 *
 *  - `processedAt`: epoch millis when the row reached a terminal
 *    state. `null` means "still pending — a future run may pick
 *    this up". Replaces the v5 "not in spend_transaction" check.
 *  - `lastError`: last A1/A2 error string. Cleared on the next
 *    successful commit. Surfaced on the debug log.
 *
 * The new composite index `(status, processedAt, timestamp)` is the
 * workhorse for the service's "give me pending in range" query.
 *
 * Note: existing rows from v5 have `processedAt = null`, so they
 * are correctly picked up by the new pending query on first run.
 * The pipeline marks them processedAt on the first terminal state
 * they reach.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `raw_sms` ADD COLUMN `processedAt` INTEGER")
        db.execSQL("ALTER TABLE `raw_sms` ADD COLUMN `lastError` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_raw_sms_status_processedAt_timestamp` " +
                "ON `raw_sms` (`status`, `processedAt`, `timestamp`)"
        )
    }
}

/**
 * v6 -> v7: manual corrections + audit-log user prompt.
 *
 * Adds two things:
 *
 *  - New ManualCorrection table. Persists the user-typed
 *    instructions that override A3's default decision; the most
 *    recent 15 rows are injected into the A3 system prompt on every
 *    subsequent run so the model stops making the same mistake the
 *    user had to correct. FK on rawSmsId is ON DELETE CASCADE so
 *    deleting a raw_sms row cleans up its corrections.
 *  - New ingestion_log.userPrompt column (nullable TEXT). Captures
 *    the override prompt the user typed on a reprompt run so the
 *    debug log can show why A3 did that alongside the model
 *    response. Default NULL on add so old rows stay unannotated.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `manual_correction` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`rawSmsId` INTEGER NOT NULL, " +
                "`linkedSmsIds` TEXT NOT NULL, " +
                "`userPrompt` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`rawSmsId`) REFERENCES `raw_sms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_manual_correction_rawSmsId` " +
                "ON `manual_correction` (`rawSmsId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_manual_correction_createdAt` " +
                "ON `manual_correction` (`createdAt`)"
        )
        db.execSQL("ALTER TABLE `ingestion_log` ADD COLUMN `userPrompt` TEXT")
    }
}

/**
 * v7 -> v8: durable A3 reprompt jobs.
 *
 * Adds the [com.spendai.app.data.local.entity.RepromptJob] table.
 * The reprompt flow on the edit screen runs on the foreground
 * [com.spendai.app.service.IngestionService] (action
 * `ACTION_REPROMPT`). This table is the durable execution record
 * of every job the service is asked to perform — a row is inserted
 * with `status = RUNNING` the moment the service starts a reprompt
 * and flipped to `COMPLETED` / `FAILED` on terminal completion.
 *
 * The cold-start scan in the service re-drives rows that are
 * still `PENDING` or `RUNNING` but whose `lastAttemptAt` is older
 * than 10 minutes, so a process death does not silently drop the
 * user's prompt. The companion `manual_correction` row (v6 → v7)
 * remains the durable *lesson*; this table is the *execution* of
 * that lesson.
 *
 * Foreign key on `transactionId` is `ON DELETE SET NULL` so
 * deleting a transaction from the edit screen does not
 * cascade-kill the job record.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reprompt_job` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`rawSmsIds` TEXT NOT NULL, " +
                "`userPrompt` TEXT NOT NULL, " +
                "`transactionId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`errorMessage` TEXT, " +
                "`attemptCount` INTEGER NOT NULL, " +
                "`lastAttemptAt` INTEGER, " +
                "`completedAt` INTEGER, " +
                "FOREIGN KEY(`transactionId`) REFERENCES `spend_transaction`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reprompt_job_status` " +
                "ON `reprompt_job` (`status`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reprompt_job_createdAt` " +
                "ON `reprompt_job` (`createdAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reprompt_job_transactionId` " +
                "ON `reprompt_job` (`transactionId`)"
        )
    }
}

/**
 * v8 -> v9: merchant self-flag and freeform metadata.
 *
 * Adds two things:
 *
 *  - `merchant.isSelf` (INTEGER NOT NULL DEFAULT 0). A boolean
 *    flag the user flips when a counterparty is themself (their
 *    own name in a UPI handle, their own card nickname, etc.).
 *    Indexed because the InsightsDao exclusion predicate
 *    `t.merchantId IN (SELECT id FROM merchant WHERE isSelf = 1)`
 *    runs against every aggregate query.
 *  - New `merchant_metadata` table. A small key-value store the
 *    user fills via Ask AI or the Merchants management screen
 *    with NOTE / CATEGORY_HINT / LABEL entries. A2 reads these
 *    rows when it materialises the merchant into the prompt
 *    bundle, so a `CATEGORY_HINT` becomes the merchant's category
 *    on the next SMS. The unique index on `(merchantId, kind)`
 *    is the dedup key for upserts.
 *
 * FK on `merchant_metadata.merchantId` is `ON DELETE CASCADE` so
 * removing a merchant cleans up its notes. The new column has a
 * default of 0 so all existing merchant rows stay at
 * `isSelf = 0` after the migration.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `merchant` ADD COLUMN `isSelf` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_merchant_isSelf` " +
                "ON `merchant` (`isSelf`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant_metadata` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`merchantId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`merchantId`) REFERENCES `merchant`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_metadata_merchantId_kind` " +
                "ON `merchant_metadata` (`merchantId`, `kind`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_merchant_metadata_merchantId` " +
                "ON `merchant_metadata` (`merchantId`)"
        )
    }
}

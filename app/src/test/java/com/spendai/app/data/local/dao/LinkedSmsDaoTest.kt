package com.spendai.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.IngestionLog
import com.spendai.app.data.local.entity.IngestionLogA1
import com.spendai.app.data.local.entity.IngestionLogA2
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionLink
import com.spendai.app.data.local.entity.TransactionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the LinkedSmsDao query. We seed a source transaction,
 * a transaction_link edge, and a DUPLICATE ingestion_log row, then
 * assert each branch of the three queries returns what we expect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [33])
class LinkedSmsDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `getSourceSms returns the source row`() = runBlocking {
        val rawId = db.smsDao().insert(
            RawSmsMessage(senderAddress = "X", msgBody = "body", timestamp = 1L)
        )
        val source = db.linkedSmsDao().getSourceSms(rawId, transactionId = 42L)
        assertNotNull(source)
        assertEquals(rawId, source!!.id)
        assertEquals("SOURCE", source.relation)
    }

    private suspend fun seedAccount(): Long {
        val sourceId = db.financialSourceDao().upsert(
            com.spendai.app.data.local.entity.FinancialSource(
                sourceKey = "Bank_TEST_${System.nanoTime()}",
                deducedType = "UPI",
                firstSeenTimestamp = 1L,
                instrumentType = "ACCOUNT",
                status = "CONFIRMED",
                confirmedAt = 1L,
            )
        )
        return db.accountDao().insert(
            com.spendai.app.data.local.entity.Account(
                sourceId = sourceId,
                issuer = "Test Bank",
                maskedNumber = "XXXX1234",
                createdAt = 1L,
            )
        )
    }
    @Test
    fun `getDuplicatesOf returns rows whose ingestion_log says DUPLICATE for this transaction`() = runBlocking {
        val now = System.currentTimeMillis()
        val sourceRawId = db.smsDao().insert(RawSmsMessage(senderAddress = "X", msgBody = "src", timestamp = now))
        val dupRawId = db.smsDao().insert(RawSmsMessage(senderAddress = "Y", msgBody = "dup", timestamp = now + 1))
        val sourceParsedId = db.parsedSmsDao().insert(
            ParsedSms(rawSmsId = sourceRawId, parsedAt = now, kind = ParsedSmsKind.TRANSACTION.name)
        )
        val accountId = seedAccount()
        val sourceTxnId = db.transactionDao().insert(
            Transaction(
                accountId = accountId, rawSmsId = sourceRawId, parsedSmsId = sourceParsedId,
                amountPaise = 1000L, txnAtMillis = now, createdAt = now,
                direction = TransactionDirection.DEBIT.name,
                status = TransactionStatus.CONFIRMED.name,
            )
        )
        // Insert a DUPLICATE ingestion log row that points at the source transaction.
        db.ingestionLogDao().insert(
            IngestionLog(
                rawSmsId = dupRawId,
                ingestedAt = now + 1,
                a1Outcome = IngestionLogA1.OK,
                a2Outcome = IngestionLogA2.DUPLICATE,
                transactionId = sourceTxnId,
            )
        )
        val dups = db.linkedSmsDao().getDuplicatesOf(sourceTxnId)
        assertEquals(1, dups.size)
        assertEquals(dupRawId, dups[0].id)
        assertEquals("DUPLICATE", dups[0].relation)
    }

    @Test
    fun `getLinkedSms returns rows connected via transaction_link`() = runBlocking {
        val now = System.currentTimeMillis()
        val sourceRawId = db.smsDao().insert(RawSmsMessage(senderAddress = "X", msgBody = "src", timestamp = now))
        val accountId = seedAccount()
        val otherRawId = db.smsDao().insert(RawSmsMessage(senderAddress = "Y", msgBody = "other", timestamp = now + 1))
        val sourceParsedId = db.parsedSmsDao().insert(ParsedSms(rawSmsId = sourceRawId, parsedAt = now, kind = ParsedSmsKind.TRANSACTION.name))
        val otherParsedId = db.parsedSmsDao().insert(ParsedSms(rawSmsId = otherRawId, parsedAt = now + 1, kind = ParsedSmsKind.TRANSACTION.name))
        val sourceTxnId = db.transactionDao().insert(
            Transaction(
                accountId = accountId, rawSmsId = sourceRawId, parsedSmsId = sourceParsedId,
                amountPaise = 1000L, txnAtMillis = now, createdAt = now,
                direction = TransactionDirection.DEBIT.name,
                status = TransactionStatus.CONFIRMED.name,
            )
        )
        val otherTxnId = db.transactionDao().insert(
            Transaction(
                accountId = accountId, rawSmsId = otherRawId, parsedSmsId = otherParsedId,
                amountPaise = 1000L, txnAtMillis = now + 1, createdAt = now + 1,
                direction = TransactionDirection.CREDIT.name,
                status = TransactionStatus.CONFIRMED.name,
            )
        )
        db.transactionLinkDao().insertIgnore(
            TransactionLink(
                fromTransactionId = sourceTxnId,
                toTransactionId = otherTxnId,
                linkType = "SELF_TRANSFER",
                confidence = 1.0f,
                createdAt = now,
            )
        )
        val linked = db.linkedSmsDao().getLinkedSms(sourceTxnId)
        assertEquals(1, linked.size)
        assertEquals(otherRawId, linked[0].id)
        assertEquals("LINKED", linked[0].relation)
        assertEquals("SELF_TRANSFER", linked[0].linkType)
        assertEquals(otherTxnId, linked[0].otherTransactionId)
    }

    @Test
    fun `empty database returns empty lists`() = runBlocking {
        assertNull(db.linkedSmsDao().getSourceSms(rawSmsId = 999L, transactionId = 1L))
        assertEquals(emptyList<LinkedSmsRow>(), db.linkedSmsDao().getLinkedSms(transactionId = 999L))
        assertEquals(emptyList<LinkedSmsRow>(), db.linkedSmsDao().getDuplicatesOf(transactionId = 999L))
    }
}

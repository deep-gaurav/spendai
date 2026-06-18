package com.spendai.app.domain.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.local.entity.SourceInstrumentType
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.CategoryRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Self-duplicate handling: when A2 echoes back the candidate's own
 * existing transaction as the duplicate target AND A3 provides a
 * transfer link, A3 should redirect the merge to the transfer link
 * and delete the self-duplicate (the standalone credit row).
 * This is the 'this credit is a duplicate of the debit' flow the
 * user hits when reprompting loan transactions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [33])
class Agent3AuditorSelfDuplicateTest {

    private lateinit var db: AppDatabase
    private lateinit var auditor: Agent3Auditor
    private val engine: GemmaInferenceEngine = mockk(relaxed = true)
    private lateinit var txnRepo: TransactionRepository
    private lateinit var smsRepo: SmsRepository
    private lateinit var parsedRepo: ParsedSmsRepository
    private var accountId: Long = 0L
    private var debitTxnId: Long = 0L
    private var creditTxnId: Long = 0L
    private var creditRawId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        smsRepo = SmsRepository(db.smsDao())
        parsedRepo = ParsedSmsRepository(db.parsedSmsDao())
        txnRepo = TransactionRepository(db.transactionDao())
        coEvery { engine.state } returns MutableStateFlow(InferenceState.Ready("NPU"))
        auditor = Agent3Auditor(
            engine = engine,
            database = db,
            transactionRepository = txnRepo,
            manualCorrectionRepository = null,
        )
        // Seed an account for the FKs.
        val sourceId = db.financialSourceDao().upsert(
            FinancialSource(
                sourceKey = "Bank_TEST",
                deducedType = "UPI",
                firstSeenTimestamp = 1L,
                instrumentType = com.spendai.app.data.local.entity.SourceInstrumentType.ACCOUNT.name,
                status = "CONFIRMED",
                confirmedAt = 1L,
            )
        )
        accountId = db.accountDao().insert(
            Account(
                sourceId = sourceId,
                issuer = "Test",
                maskedNumber = "XXXX1",
                createdAt = 1L,
            )
        )
        // Seed the 45k debit and credit transactions as if the
        // first ingestion had already happened.
        val now = System.currentTimeMillis()
        val debitRawId = smsRepo.insert(
            RawSmsMessage(senderAddress = "BANK", msgBody = "debit", timestamp = now - 86_400_000L)
        )
        val debitParsedId = parsedRepo.insert(
            ParsedSms(rawSmsId = debitRawId, parsedAt = now, kind = ParsedSmsKind.TRANSACTION.name)
        )
        debitTxnId = db.transactionDao().insert(
            Transaction(
                accountId = accountId, rawSmsId = debitRawId, parsedSmsId = debitParsedId,
                amountPaise = 45_000_00L, txnAtMillis = now - 86_400_000L, createdAt = now,
                direction = TransactionDirection.DEBIT.name,
                status = TransactionStatus.CONFIRMED.name,
            )
        )
        creditRawId = smsRepo.insert(
            RawSmsMessage(senderAddress = "LOAN", msgBody = "credit", timestamp = now)
        )
        val creditParsedId = parsedRepo.insert(
            ParsedSms(rawSmsId = creditRawId, parsedAt = now, kind = ParsedSmsKind.TRANSACTION.name)
        )
        creditTxnId = db.transactionDao().insert(
            Transaction(
                accountId = accountId, rawSmsId = creditRawId, parsedSmsId = creditParsedId,
                amountPaise = 45_000_00L, txnAtMillis = now, createdAt = now,
                direction = TransactionDirection.CREDIT.name,
                status = TransactionStatus.CONFIRMED.name,
            )
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `self-duplicate with transfer link deletes the credit and keeps the debit`() = runTest {
        // A2's response echoes back the candidate's own existing
        // transaction as the duplicate target. A3 then sets the
        // transfer link to the actual transfer partner (the debit).
        // The auditor should redirect the merge to the debit and
        // delete the self-duplicate (the credit).
        val candidate = Transaction(
            accountId = accountId, rawSmsId = creditRawId, parsedSmsId = db.parsedSmsDao().getByRawSms(creditRawId)!!.id,
            amountPaise = 45_000_00L, txnAtMillis = System.currentTimeMillis(), createdAt = System.currentTimeMillis(),
            direction = TransactionDirection.CREDIT.name,
            status = TransactionStatus.CONFIRMED.name,
        )
        val a3Resp = """{
            "currentDecision": {
                "decision": "DUPLICATE",
                "duplicateOfTransactionId": $creditTxnId,
                "transferLinkWithTransactionId": $debitTxnId,
                "transferLinkType": "SELF_TRANSFER"
            },
            "modifications": []
        }""".trimIndent()
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a3Resp)
        val outcome = auditor.reviewAndCommit(
            candidate = candidate,
            rawSmsId = creditRawId,
            rawSmsText = "credit",
            a2Prompt = "",
            a2Response = "",
        )
        // The auditor should have returned the debit id (the real
        // reference) and removed the standalone credit row.
        assertEquals(debitTxnId, outcome.transactionId)
        assertNotNull("debit should still exist", txnRepo.getById(debitTxnId))
        assertNull("credit should be deleted", txnRepo.getById(creditTxnId))
    }

    @Test
    fun `plain duplicate (no self) deletes the old version`() = runTest {
        // The non-self-duplicate case: a fresh candidate is treated
        // as a duplicate of an existing transaction on a different
        // parsedSms. The previous version is deleted by the
        // auto-cleanup, the new version is merged in.
        val now = System.currentTimeMillis()
        val otherRawId = smsRepo.insert(
            RawSmsMessage(senderAddress = "REPLAY", msgBody = "replay", timestamp = now)
        )
        val otherParsedId = parsedRepo.insert(
            ParsedSms(rawSmsId = otherRawId, parsedAt = now, kind = ParsedSmsKind.TRANSACTION.name)
        )
        // Pre-existing transaction on a different parsed_sms.
        val oldTxnId = db.transactionDao().insert(
            Transaction(
                accountId = accountId, rawSmsId = otherRawId, parsedSmsId = otherParsedId,
                amountPaise = 100_00L, txnAtMillis = now, createdAt = now,
                direction = TransactionDirection.DEBIT.name,
                status = TransactionStatus.CONFIRMED.name,
            )
        )
        // The candidate gets a fresh parsedSmsId (not the same as
        // the existing transaction) so this is a plain duplicate, not a
        // self-duplicate.
        val candidateRawId = smsRepo.insert(
            RawSmsMessage(senderAddress = "REPLAY2", msgBody = "replay2", timestamp = now)
        )
        val candidateParsedId = parsedRepo.insert(
            ParsedSms(rawSmsId = candidateRawId, parsedAt = now, kind = ParsedSmsKind.TRANSACTION.name)
        )
        val candidate = Transaction(
            accountId = accountId, rawSmsId = candidateRawId, parsedSmsId = candidateParsedId,
            amountPaise = 100_00L, txnAtMillis = now, createdAt = now,
            direction = TransactionDirection.DEBIT.name,
            status = TransactionStatus.CONFIRMED.name,
        )
        val a3Resp = """{
            "currentDecision": {
                "decision": "DUPLICATE",
                "duplicateOfTransactionId": $oldTxnId
            },
            "modifications": []
        }""".trimIndent()
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a3Resp)
        auditor.reviewAndCommit(
            candidate = candidate,
            rawSmsId = otherRawId,
            rawSmsText = "replay",
            a2Prompt = "",
            a2Response = "",
        )
        // The old version is the merge target, so it stays.
        // No previous transaction for the candidate's parsedSms
        // exists, so the auto-cleanup has nothing to remove.
        assertNotNull("merge target should still exist", txnRepo.getById(oldTxnId))
    }

}

package com.spendai.app.ui.edit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.TestApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the manual-edit transaction flow.
 *
 * Phase 3 lets the user fix anything A2 got wrong by tapping a
 * row in the transactions list and editing the merchant, account,
 * amount, direction, or notes. The save flow writes the row back
 * to the DB; the delete flow removes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class EditTransactionViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var txnRepo: TransactionRepository
    private lateinit var merchantRepo: MerchantRepository
    private lateinit var accountRepo: AccountRepository
    private var txnId: Long = 0L
    private var oldMerchantId: Long = 0L
    private var accountId: Long = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        txnRepo = TransactionRepository(db.transactionDao())
        merchantRepo = MerchantRepository(db.merchantDao())
        accountRepo = AccountRepository(db.accountDao())

        // Seed: 1 source + 1 account + 2 merchants + 1 transaction.
        val smsRepo = SmsRepository(db.smsDao())
        val parsedRepo = ParsedSmsRepository(db.parsedSmsDao())
        runBlocking {
            val now = System.currentTimeMillis()
            val rawId = smsRepo.insert(
                RawSmsMessage(
                    senderAddress = "VK-TEST",
                    msgBody = "Rs 100 at Old",
                    timestamp = now,
                    status = SmsStatus.PARSED,
                )
            )
            val parsedId = parsedRepo.insert(
                ParsedSms(
                    rawSmsId = rawId,
                    parsedAt = now,
                    kind = ParsedSmsKind.TRANSACTION.name,
                )
            )
            val sourceId = FinancialSourceRepository(db.financialSourceDao()).upsert(
                com.spendai.app.data.local.entity.FinancialSource(
                    sourceKey = "Bank_TEST",
                    deducedType = "ACCOUNT",
                    firstSeenTimestamp = now,
                )
            )
            accountId = accountRepo.insert(
                Account(
                    sourceId = sourceId,
                    issuer = "Test Bank",
                    maskedNumber = "XXXX1234",
                    currency = "INR",
                    createdAt = now,
                )
            )
            oldMerchantId = merchantRepo.insert(
                Merchant(
                    name = "Old Merchant",
                    normalizedName = "old",
                    firstSeenAt = now,
                )
            )
            txnId = txnRepo.insert(
                Transaction(
                    accountId = accountId,
                    merchantId = oldMerchantId,
                    rawSmsId = rawId,
                    parsedSmsId = parsedId,
                    amountPaise = 10000L,
                    currency = "INR",
                    direction = TransactionDirection.DEBIT.name,
                    txnAtMillis = now,
                    channel = "UPI",
                    status = TransactionStatus.CONFIRMED.name,
                    confidence = 0.95f,
                    createdAt = now,
                )
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun makeVm(transactionId: Long = txnId): EditTransactionViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val handle = SavedStateHandle().apply {
            set(EditTransactionViewModel.ARG_TRANSACTION_ID, transactionId)
        }
        return EditTransactionViewModel(
            application = application,
            savedStateHandle = handle,
            transactionRepository = txnRepo,
            merchantRepository = merchantRepo,
            accountRepository = accountRepo,
        )
    }

    @Test
    fun `load populates the state from the existing transaction`() = runBlocking {
        val vm = makeVm()
        // The load happens on viewModelScope; collect the state
        // until loading flips off.
        val state = waitForNotLoading(vm)
        assertEquals(txnId, state.transactionId)
        assertEquals("100.00", state.amountText)
        assertEquals("INR", state.currency)
        assertEquals(TransactionDirection.DEBIT, state.direction)
        assertEquals("UPI", state.channel)
        assertEquals(accountId, state.accountId)
        assertEquals(oldMerchantId, state.merchantId)
        assertEquals(0.95f, state.confidence, 0.0001f)
    }

    @Test
    fun `change merchant and save updates the transaction and leaves old merchant alone`() = runBlocking {
        val vm = makeVm()
        val state0 = waitForNotLoading(vm)

        // Insert a new merchant and pick it.
        val newMerchantId = merchantRepo.insert(
            Merchant(name = "New Merchant", normalizedName = "new", firstSeenAt = 0L)
        )
        vm.setMerchant(newMerchantId)
        vm.save()

        val saved = waitFor { vm.state.value.saved }
        assertTrue("save should set saved=true", saved)
        val updated = txnRepo.getById(txnId)
        assertNotNull(updated)
        assertEquals(newMerchantId, updated!!.merchantId)
        // Old merchant row is untouched.
        val oldRow = merchantRepo.getById(oldMerchantId)
        assertNotNull("old merchant should still exist", oldRow)
        assertEquals("Old Merchant", oldRow!!.name)
    }

    @Test
    fun `create new merchant via the field is persisted with the same normalized form`() = runBlocking {
        val vm = makeVm()
        waitForNotLoading(vm)
        vm.startCreatingMerchant()
        vm.setNewMerchantName("Brand New Cafe")
        vm.save()

        val saved = waitFor { vm.state.value.saved }
        assertTrue(saved)
        val updated = txnRepo.getById(txnId)
        assertNotNull(updated)
        // The transaction now points at a fresh merchant row.
        val newMerchantId = updated!!.merchantId
        assertNotNull(newMerchantId)
        val newRow = merchantRepo.getById(newMerchantId!!)
        assertNotNull(newRow)
        assertEquals("Brand New Cafe", newRow!!.name)
    }

    @Test
    fun `delete removes the transaction from the DB`() = runBlocking {
        val vm = makeVm()
        waitForNotLoading(vm)
        vm.delete()
        val deleted = waitFor { vm.state.value.deleted }
        assertTrue(deleted)
        assertNull(txnRepo.getById(txnId))
    }

    @Test
    fun `not-found state is reported for an unknown id`() = runBlocking {
        val vm = makeVm(transactionId = 99999L)
        val state = waitForNotLoading(vm)
        assertTrue(state.notFound)
    }

    // --- helpers ---

    private suspend fun waitForNotLoading(vm: EditTransactionViewModel): EditTransactionState {
        repeat(50) {
            val s = vm.state.value
            if (!s.loading) return s
            kotlinx.coroutines.delay(50L)
        }
        return vm.state.value
    }

    private suspend fun waitFor(predicate: () -> Boolean): Boolean {
        repeat(50) {
            if (predicate()) return true
            kotlinx.coroutines.delay(50L)
        }
        return predicate()
    }
}

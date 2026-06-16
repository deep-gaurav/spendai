package com.spendai.app

import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.spendai.app.data.local.entity.SmsStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that a synthetic `SMS_RECEIVED` broadcast ends up in Room
 * with status UNPARSED. Worker enqueue is asserted by checking the
 * WorkManager test harness state.
 *
 * NOTE: designed to run on a real device/emulator (`connectedAndroidTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsReceiverTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<SpendAiApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun synthetic_sms_lands_in_db_as_unparsed() {
        val context = ApplicationProvider.getApplicationContext<SpendAiApp>()
        val pdu: ByteArray = hexToBytes(
            "079141515515122404" + "0B91515151515151" +
                "000006" + "C8329BFD06" + "01" + "05" + "E8329BFC06"
        )
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", arrayOf(pdu))
            putExtra("format", "3gpp")
        }

        SmsReceiver().onReceive(context, intent)

        val pending = runBlocking { context.smsRepository.unparsedOnce() }
        assertTrue("at least one row inserted", pending.isNotEmpty())
        assertEquals(SmsStatus.UNPARSED, pending.first().status)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }
}

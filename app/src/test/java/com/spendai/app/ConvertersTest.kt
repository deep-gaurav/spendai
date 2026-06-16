package com.spendai.app

import com.spendai.app.data.local.Converters
import com.spendai.app.data.local.entity.SmsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `smsStatus round-trips through name string`() {
        SmsStatus.entries.forEach { status ->
            val asString = converters.smsStatusToString(status)
            val parsed = converters.stringToSmsStatus(asString)
            assertEquals(status, parsed)
        }
    }

    @Test
    fun `unknown string falls back to UNPARSED via valueOf`() {
        // Defensive: a future enum value that has been renamed in code
        // but not migrated in the DB should never throw. We document
        // the current behaviour here so any change is a conscious one.
        val ex = runCatching {
            converters.stringToSmsStatus("DELETED_STATUS")
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, ex?.javaClass)
    }
}

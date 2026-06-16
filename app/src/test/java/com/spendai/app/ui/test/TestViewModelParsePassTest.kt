package com.spendai.app.ui.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the response parser used by the test screen.
 * The parser is intentionally tiny so it is easy to lock down its
 * exact behaviour: trim, lowercase, optional single trailing
 * punctuation/quote character.
 */
class TestViewModelParsePassTest {

    @Test
    fun `exact match passes`() {
        assertTrue(TestViewModel.parsePass("I\u2019m online"))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertTrue(TestViewModel.parsePass("   I\u2019m online   "))
    }

    @Test
    fun `uppercase input passes`() {
        assertTrue(TestViewModel.parsePass("I\u2019M ONLINE"))
    }

    @Test
    fun `mixed case and surrounding whitespace passes`() {
        assertTrue(TestViewModel.parsePass("  i\u2019M ONLINE\n"))
    }

    @Test
    fun `trailing period passes`() {
        assertTrue(TestViewModel.parsePass("I\u2019m online."))
    }

    @Test
    fun `trailing exclamation passes`() {
        assertTrue(TestViewModel.parsePass("I\u2019m online!"))
    }

    @Test
    fun `trailing quote passes`() {
        assertTrue(TestViewModel.parsePass("I\u2019m online\""))
    }

    @Test
    fun `totally wrong text fails`() {
        assertFalse(TestViewModel.parsePass("hello world"))
    }

    @Test
    fun `partial match fails`() {
        assertFalse(TestViewModel.parsePass("I\u2019m"))
    }

    @Test
    fun `empty string fails`() {
        assertFalse(TestViewModel.parsePass(""))
    }

    @Test
    fun `only whitespace fails`() {
        assertFalse(TestViewModel.parsePass("   \n  "))
    }

    @Test
    fun `model preamble with expected answer still fails strict check`() {
        assertFalse(TestViewModel.parsePass("Sure! I\u2019m online"))
    }

    @Test
    fun `surrounding double quotes are stripped`() {
        assertTrue(TestViewModel.parsePass("\"I\u2019m online\""))
    }

    @Test
    fun `surrounding asterisks are stripped`() {
        assertTrue(TestViewModel.parsePass("*I\u2019m online*"))
    }

    @Test
    fun `surrounding backticks are stripped`() {
        assertTrue(TestViewModel.parsePass("`I\u2019m online`"))
    }

    @Test
    fun `leading and trailing punctuation both stripped`() {
        assertTrue(TestViewModel.parsePass("\"I\u2019m online!\""))
    }

    @Test
    fun `expected constant is the actual probe prompt string`() {
        assertEquals("i\u2019m online", "i\u2019m online")
        assertTrue(PROBE_PROMPT.contains("I\u2019m online"))
    }
}

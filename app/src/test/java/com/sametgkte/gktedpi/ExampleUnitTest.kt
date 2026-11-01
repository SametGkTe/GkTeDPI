package com.sametgkte.gktedpi

import com.sametgkte.gktedpi.core.ByeDpiProxyUIPreferences
import com.sametgkte.gktedpi.utility.isValidPortFilter
import com.sametgkte.gktedpi.utility.shellSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun shellSplit_parsesQuotedAndUnquotedFlags() {
        val args = shellSplit("-d 1+s -r 1+s -l ':GET / HTTP/1.1'")
        assertEquals(listOf("-d", "1+s", "-r", "1+s", "-l", ":GET / HTTP/1.1"), args)
    }

    @Test
    fun uiPreferences_buildsCompleteCommandArgs() {
        val prefs = ByeDpiProxyUIPreferences(
            ip = "127.0.0.1",
            port = 1080,
            desyncMethod = ByeDpiProxyUIPreferences.DesyncMethod.Fake,
            splitPosition = -1,
            offsetFlag = ByeDpiProxyUIPreferences.OffsetFlag.None,
            md5Sig = true,
            fakeTtl = 8,
            tlsRecordSplit = true,
            tlsRecordSplitPosition = 1,
            tlsRecordSplitAtSni = true,
            udpFakeCount = 3,
        )
        val cmd = prefs.toCommandArgs().toList()
        assertTrue(cmd.contains("-f"))
        assertTrue(cmd.contains("-1"))
        assertTrue(cmd.contains("-S"))
        assertTrue(cmd.contains("-r"))
        assertTrue(cmd.contains("1+s"))
        assertTrue(cmd.contains("-a"))
    }

    @Test
    fun portFilterValidation_worksCorrectly() {
        assertTrue(isValidPortFilter(""))
        assertTrue(isValidPortFilter("443"))
        assertTrue(isValidPortFilter("80-443"))
        assertFalse(isValidPortFilter("443-80"))
        assertFalse(isValidPortFilter("70000"))
    }
}

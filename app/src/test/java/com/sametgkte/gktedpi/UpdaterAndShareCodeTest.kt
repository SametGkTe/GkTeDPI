package com.sametgkte.gktedpi

import com.sametgkte.gktedpi.profiles.DpiProfile
import com.sametgkte.gktedpi.updater.GitHubAppUpdater
import com.sametgkte.gktedpi.utility.PublishEnhancements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterAndShareCodeTest {

    @Test
    fun `semantic version comparator detects newer tags accurately`() {
        assertTrue(GitHubAppUpdater.isNewerVersion("v5.2.0", "5.1.0"))
        assertTrue(GitHubAppUpdater.isNewerVersion("6.0.0", "5.9.9"))
        assertTrue(GitHubAppUpdater.isNewerVersion("v5.1.1-release", "5.1.0"))
        assertFalse(GitHubAppUpdater.isNewerVersion("v5.1.0", "5.1.0"))
        assertFalse(GitHubAppUpdater.isNewerVersion("v5.0.0", "5.1.0"))
        assertFalse(GitHubAppUpdater.isNewerVersion("invalid", "5.1.0"))
    }

    @Test
    fun `release json parser extracts universal apk and changelog`() {
        val sampleJson = """
            {
              "tag_name": "v5.2.0",
              "name": "GkTeDPI v5.2.0 Yayın Sürümü",
              "body": "- Yeni İSS stratejileri\n- Hız iyileştirmeleri",
              "html_url": "https://github.com/SametGkTe/GkTeDPI/releases/tag/v5.2.0",
              "assets": [
                {
                  "name": "GkTeDPI-v5.2.0-release.apk",
                  "browser_download_url": "https://github.com/SametGkTe/GkTeDPI/releases/download/v5.2.0/GkTeDPI-v5.2.0-release.apk",
                  "size": 7024640
                }
              ]
            }
        """.trimIndent()

        val parsed = GitHubAppUpdater.parseReleaseJson(sampleJson, listOf("arm64-v8a"))
        assertEquals("v5.2.0", parsed.tagName)
        assertEquals("5.2.0", parsed.cleanVersion)
        assertEquals("GkTeDPI-v5.2.0-release.apk", parsed.apkName)
        assertEquals(7024640L, parsed.apkSizeBytes)
        assertTrue(parsed.apkDownloadUrl!!.endsWith(".apk"))
        assertTrue(parsed.changelog.contains("Yeni İSS"))
    }

    @Test
    fun `profile share code roundtrips UTF-8 Turkish profile cleanly`() {
        val original = DpiProfile(
            id = "test-id",
            name = "TR • Özel Süper Mod (ŞĞÜÇİÖ)",
            useCommandLine = true,
            commandArgs = "-d 1 -r 1+s -M h,d -a 2 --md5sig",
            dnsIp = "1.1.1.1",
            dnsSecondary = "1.0.0.1",
            vpnMtu = 1420,
            lanBypass = true,
            ipv6Enable = false,
        )

        val code = PublishEnhancements.encodeProfileShareCode(original)
        assertTrue(code.startsWith("gktedpi://profile?data="))

        val decoded = PublishEnhancements.decodeProfileShareCode(code)
        assertNotNull(decoded)
        assertEquals("TR • Özel Süper Mod (ŞĞÜÇİÖ)", decoded!!.name)
        assertEquals("-d 1 -r 1+s -M h,d -a 2 --md5sig", decoded.commandArgs)
        assertEquals("1.1.1.1", decoded.dnsIp)
        assertEquals(1420, decoded.vpnMtu)

        assertNull(PublishEnhancements.decodeProfileShareCode("not-a-valid-code"))
    }
}

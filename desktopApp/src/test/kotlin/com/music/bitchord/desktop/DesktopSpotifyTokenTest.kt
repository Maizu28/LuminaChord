package com.music.bitchord.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DesktopSpotifyTokenTest {

    @Test
    fun sanitizeCookieStripsPrefixQuotesAndAttributes() {
        assertEquals("AQB123xyz", DesktopSpotifyToken.sanitizeCookie("AQB123xyz"))
        assertEquals("AQB123xyz", DesktopSpotifyToken.sanitizeCookie("  sp_dc=AQB123xyz; Domain=.spotify.com; Path=/ "))
        assertEquals("AQB123xyz", DesktopSpotifyToken.sanitizeCookie("\"SP_DC=AQB123xyz\""))
    }

    @Test
    fun parsesDynamicTotpSecretsFromJsBundleAndComputesSixDigitTotp() {
        val sampleJs = """
            let ej=[{secret:',7/*F("rLJ2oxaKL^f+E1xvP@N',version:61},{secret:'OmE{ZA.J^":0FG\\Uz?[@WW',version:60}];
        """.trimIndent()
        val parsed = DesktopSpotifyToken.parseSecretsFromBundleJs(sampleJs)
        assertArrayEquals(",7/*F(\"rLJ2oxaKL^f+E1xvP@N".map { it.code }.toIntArray(), parsed[61])
        assertArrayEquals("""OmE{ZA.J^":0FG\Uz?[@WW""".map { it.code }.toIntArray(), parsed[60])

        val code = DesktopSpotifyToken.totp(parsed.getValue(61), 1795702414L)
        assertNotNull(code)
        assertEquals(6, code!!.length)
    }
}

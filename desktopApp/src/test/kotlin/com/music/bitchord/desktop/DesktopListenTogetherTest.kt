package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopListenTogetherTest {

    @Test
    fun `official server matches Android mobile default`() {
        assertEquals("https://bitchord-listen-together.onrender.com", DesktopListenTogether.OFFICIAL_SERVER)
        assertEquals("https://api.bitchord.kushagrasingh.in", DesktopListenTogether.BACKUP_SERVER)
    }

    @Test
    fun `server address is canonicalized before it is persisted`() {
        assertEquals(
            "https://party.example.com/base",
            DesktopListenTogether.normalizeServerUrl(" PARTY.Example.com/base/ ").getOrThrow(),
        )
        assertEquals("", DesktopListenTogether.normalizeServerUrl("  ").getOrThrow())
    }

    @Test
    fun `unsafe or ambiguous server addresses are rejected`() {
        listOf(
            "ftp://party.example.com",
            "https://user:pass@party.example.com",
            "https://party.example.com?secret=yes",
            "https://party.example.com/#fragment",
            "https://party.example.com/a/../b",
            "party example.com",
            "https://party.example.com:99999",
        ).forEach { address ->
            assertTrue(
                DesktopListenTogether.normalizeServerUrl(address).isFailure,
                "$address should not be accepted",
            )
        }
    }

    @Test
    fun `party input parser handles Android invite links, custom schemes, and bare codes`() {
        val schemeWithServer = DesktopListenTogether.parsePartyInput(
            "bitchord://party/ab12cd?server=https%3A%2F%2Fcustom.example.com%2F",
        )
        assertEquals("AB12CD", schemeWithServer?.code)
        assertEquals("https://custom.example.com", schemeWithServer?.serverUrl)

        val officialInvite = DesktopListenTogether.parsePartyInput(
            "https://bitchord.kushagrasingh.in/invite/xy89zw",
        )
        assertEquals("XY89ZW", officialInvite?.code)
        assertNull(officialInvite?.serverUrl)

        val renderInvite = DesktopListenTogether.parsePartyInput(
            "https://bitchord-listen-together.onrender.com/invite/q1w2e3",
        )
        assertEquals("Q1W2E3", renderInvite?.code)
        assertEquals("https://bitchord-listen-together.onrender.com", renderInvite?.serverUrl)

        val plainCode = DesktopListenTogether.parsePartyInput(" m9n8b7 ")
        assertEquals("M9N8B7", plainCode?.code)
        assertNull(plainCode?.serverUrl)
    }

    @Test
    fun `default code lookup probes both Render and secondary official API servers`() {
        val candidates = DesktopListenTogether.candidateServersForCode(
            code = "ABC123",
            explicitServer = null,
            customServer = "",
            idleServer = DesktopListenTogether.OFFICIAL_SERVER,
        )
        assertEquals(
            listOf(
                DesktopListenTogether.OFFICIAL_SERVER,
                DesktopListenTogether.BACKUP_SERVER,
            ),
            candidates,
        )

        val customCandidates = DesktopListenTogether.candidateServersForCode(
            code = "ABC123",
            explicitServer = null,
            customServer = "https://my.party.example",
            idleServer = DesktopListenTogether.OFFICIAL_SERVER,
        )
        assertEquals(listOf("https://my.party.example"), customCandidates)

        val inviteCandidates = DesktopListenTogether.candidateServersForCode(
            code = "ABC123",
            explicitServer = "https://api.bitchord.kushagrasingh.in",
            customServer = "https://my.party.example",
            idleServer = DesktopListenTogether.OFFICIAL_SERVER,
        )
        assertEquals(listOf("https://api.bitchord.kushagrasingh.in"), inviteCandidates)
    }

    @Test
    fun `default invite url uses canonical BitChord domain`() {
        assertEquals(
            "https://bitchord.kushagrasingh.in/invite/ABC123",
            DesktopListenTogether.inviteUrl("abc123"),
        )
    }
}

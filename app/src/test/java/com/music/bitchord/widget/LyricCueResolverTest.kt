package com.music.bitchord.widget

import com.music.bitchord.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricCueResolverTest {

    private val loading = "Loading lyrics…"
    private val unavailable = "No synced lyrics"

    @Test
    fun resolve_whileLoadingEmpty_showsLoadingAndTrackMeta() {
        val result = LyricCueResolver.resolve(
            lines = null,
            positionMs = 0L,
            title = "Sakura no Yoru",
            artist = "Tsukii",
            loadingLabel = loading,
            unavailableLabel = unavailable,
            lyricsLoading = true,
        )
        assertEquals(loading, result.current)
        assertEquals("Sakura no Yoru · Tsukii", result.next)
        assertFalse(result.hasSyncedLines)
    }

    @Test
    fun resolve_unsyncedLyrics_marksNoSyncedAndKeepsFallback() {
        val plain = listOf(
            LyricLine(0L, "First line"),
            LyricLine(0L, "Second line"),
        )
        val result = LyricCueResolver.resolve(
            lines = plain,
            positionMs = 15_000L,
            title = "Sakura no Yoru",
            artist = "Tsukii",
            loadingLabel = loading,
            unavailableLabel = unavailable,
            lyricsLoading = false,
        )
        assertEquals(unavailable, result.current)
        assertEquals("First line", result.next)
        assertFalse(result.hasSyncedLines)
    }

    @Test
    fun resolve_beforeFirstTimestamp_showsIntroNoteAndFirstLyric() {
        val synced = listOf(
            LyricLine(12_000L, "Nijinda sora ni sakura ga mau"),
            LyricLine(18_500L, "Kimi to mita yoru wa still in my mind"),
        )
        val result = LyricCueResolver.resolve(
            lines = synced,
            positionMs = 3_000L,
            title = "Sakura no Yoru",
            artist = "Tsukii",
            loadingLabel = loading,
            unavailableLabel = unavailable,
            lyricsLoading = false,
        )
        assertEquals("♪  • • •", result.current)
        assertEquals("Nijinda sora ni sakura ga mau", result.next)
        assertTrue(result.hasSyncedLines)
        assertTrue(result.isInstrumentalIntroOrGap)
    }

    @Test
    fun resolve_activeLineSkipsInterludeGapForNextLine() {
        val synced = listOf(
            LyricLine(10_000L, "Nijinda sora ni sakura ga mau"),
            LyricLine(15_000L, ""),
            LyricLine(22_000L, "Kimi to mita yoru wa still in my mind"),
            LyricLine(28_000L, "Final line"),
        )

        val onFirst = LyricCueResolver.resolve(
            lines = synced,
            positionMs = 11_000L,
            title = "Sakura no Yoru",
            artist = "Tsukii",
            loadingLabel = loading,
            unavailableLabel = unavailable,
            lyricsLoading = false,
        )
        assertEquals("Nijinda sora ni sakura ga mau", onFirst.current)
        assertEquals("Kimi to mita yoru wa still in my mind", onFirst.next)
        assertFalse(onFirst.isInstrumentalIntroOrGap)

        val inGap = LyricCueResolver.resolve(
            lines = synced,
            positionMs = 17_000L,
            title = "Sakura no Yoru",
            artist = "Tsukii",
            loadingLabel = loading,
            unavailableLabel = unavailable,
            lyricsLoading = false,
        )
        assertEquals("•••", inGap.current)
        assertEquals("Kimi to mita yoru wa still in my mind", inGap.next)
        assertTrue(inGap.isInstrumentalIntroOrGap)

        val onFinal = LyricCueResolver.resolve(
            lines = synced,
            positionMs = 30_000L,
            title = "Sakura no Yoru",
            artist = "Tsukii",
            loadingLabel = loading,
            unavailableLabel = unavailable,
            lyricsLoading = false,
        )
        assertEquals("Final line", onFinal.current)
        assertEquals("", onFinal.next)
    }

    @Test
    fun formatTime_and_progressFraction_behaveCleanlyAtBoundaries() {
        assertEquals("0:00", LyricCueResolver.formatTime(-100L))
        assertEquals("0:00", LyricCueResolver.formatTime(0L))
        assertEquals("1:24", LyricCueResolver.formatTime(84_000L))
        assertEquals("3:56", LyricCueResolver.formatTime(236_000L))
        assertEquals("1:02:05", LyricCueResolver.formatTime(3_725_000L))

        assertEquals(0f, LyricCueResolver.progressFraction(10_000L, 0L), 0.0001f)
        assertEquals(0.3559f, LyricCueResolver.progressFraction(84_000L, 236_000L), 0.001f)
        assertEquals(1f, LyricCueResolver.progressFraction(300_000L, 236_000L), 0.0001f)
    }
}

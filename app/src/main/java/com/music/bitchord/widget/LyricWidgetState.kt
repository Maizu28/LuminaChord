package com.music.bitchord.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.music.bitchord.data.lyrics.LyricLine
import java.util.Locale

internal data class LyricDisplayPair(
    val current: String,
    val next: String,
    val hasSyncedLines: Boolean,
    val isInstrumentalIntroOrGap: Boolean = false,
)

/**
 * Pure JVM helper for resolving current & upcoming lyric lines plus
 * progress/timestamp formatting so behavior is straightforward to unit test.
 */
internal object LyricCueResolver {

    fun isSynced(lines: List<LyricLine>?): Boolean =
        lines != null && lines.any { it.timeMs > 0L }

    fun resolve(
        lines: List<LyricLine>?,
        positionMs: Long,
        title: String,
        artist: String,
        loadingLabel: String,
        unavailableLabel: String,
        lyricsLoading: Boolean,
    ): LyricDisplayPair {
        val subtitleFallback = listOf(title.trim(), artist.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

        if (lines.isNullOrEmpty()) {
            return LyricDisplayPair(
                current = if (lyricsLoading) loadingLabel else unavailableLabel,
                next = subtitleFallback,
                hasSyncedLines = false,
            )
        }

        if (!isSynced(lines)) {
            val plain = lines.asSequence()
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .take(2)
                .toList()
            if (plain.isEmpty()) {
                return LyricDisplayPair(
                    current = unavailableLabel,
                    next = subtitleFallback,
                    hasSyncedLines = false,
                )
            }
            return LyricDisplayPair(
                current = unavailableLabel,
                next = plain.first(),
                hasSyncedLines = false,
            )
        }

        val clampedPos = positionMs.coerceAtLeast(0L)
        val activeIdx = lines.indexOfLast { it.timeMs <= clampedPos }
        if (activeIdx < 0) {
            val firstSung = lines.firstOrNull { !it.isGap && it.text.isNotBlank() }?.text?.trim().orEmpty()
            return if (firstSung.isEmpty()) {
                LyricDisplayPair(
                    current = unavailableLabel,
                    next = subtitleFallback,
                    hasSyncedLines = false,
                )
            } else {
                LyricDisplayPair(
                    current = "♪  • • •",
                    next = firstSung,
                    hasSyncedLines = true,
                    isInstrumentalIntroOrGap = true,
                )
            }
        }

        val active = lines[activeIdx]
        val nextSung = lines.asSequence()
            .drop(activeIdx + 1)
            .firstOrNull { !it.isGap && it.text.isNotBlank() }
            ?.text
            ?.trim()
            .orEmpty()

        return if (active.isGap || active.text.isBlank()) {
            LyricDisplayPair(
                current = if (nextSung.isNotEmpty()) "•••" else "♪",
                next = nextSung.ifEmpty { subtitleFallback },
                hasSyncedLines = true,
                isInstrumentalIntroOrGap = true,
            )
        } else {
            LyricDisplayPair(
                current = active.text.trim(),
                next = nextSung,
                hasSyncedLines = true,
                isInstrumentalIntroOrGap = false,
            )
        }
    }

    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}

/**
 * Persists dynamic lyric lines and playback timing separately from [MediaWidgetSnapshot]
 * so 1-second progress & lyric ticks never rewrite transport state or trigger
 * heavy non-lyric widget re-renders.
 */
internal data class LyricWidgetState(
    val mediaId: String? = null,
    val currentLyric: String = "",
    val nextLyric: String = "",
    val hasSyncedLyrics: Boolean = false,
    val lyricsLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    companion object {
        private const val PREFS_NAME = "bitchord_lyric_widget"
        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_CURRENT_LYRIC = "current_lyric"
        private const val KEY_NEXT_LYRIC = "next_lyric"
        private const val KEY_HAS_SYNCED = "has_synced"
        private const val KEY_LOADING = "lyrics_loading"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_DURATION_MS = "duration_ms"

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun save(context: Context, state: LyricWidgetState) {
            prefs(context).edit {
                if (state.mediaId != null) putString(KEY_MEDIA_ID, state.mediaId) else remove(KEY_MEDIA_ID)
                putString(KEY_CURRENT_LYRIC, state.currentLyric)
                putString(KEY_NEXT_LYRIC, state.nextLyric)
                putBoolean(KEY_HAS_SYNCED, state.hasSyncedLyrics)
                putBoolean(KEY_LOADING, state.lyricsLoading)
                putLong(KEY_POSITION_MS, state.positionMs.coerceAtLeast(0L))
                putLong(KEY_DURATION_MS, state.durationMs.coerceAtLeast(0L))
            }
        }

        fun load(context: Context): LyricWidgetState {
            val p = prefs(context)
            return LyricWidgetState(
                mediaId = p.getString(KEY_MEDIA_ID, null),
                currentLyric = p.getString(KEY_CURRENT_LYRIC, null).orEmpty(),
                nextLyric = p.getString(KEY_NEXT_LYRIC, null).orEmpty(),
                hasSyncedLyrics = p.getBoolean(KEY_HAS_SYNCED, false),
                lyricsLoading = p.getBoolean(KEY_LOADING, false),
                positionMs = p.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
                durationMs = p.getLong(KEY_DURATION_MS, 0L).coerceAtLeast(0L),
            )
        }
    }
}

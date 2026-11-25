package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 4×2 Lyric Player widget (album cover, title, artist, like heart, 2-line synced lyrics,
 * live progress bar with timestamps, and circular glass transport buttons).
 */
class LyricWidgetWide : LyricWidget(fallbackWidthDp = 310, isPill = false)

/**
 * 4×1 Lyric Only capsule widget (waveform glyph, vertical divider, 2-line synced lyrics).
 */
class LyricWidgetPill : LyricWidget(fallbackWidthDp = 310, isPill = true)

/**
 * Shared AppWidgetProvider logic for both Liquid Glass Lyric widget variants ([LyricWidgetWide]
 * and [LyricWidgetPill]).
 *
 * Uses two update paths:
 * - Full render ([refresh]): builds the blurred artwork card/capsule + cover art + click intents
 *   whenever track, play state, like state, widget size, or widget placement changes.
 * - Lightweight partial tick ([tick]): pushes only changed lyric text, timestamps, and the tiny
 *   progress bar bitmap via [AppWidgetManager.partiallyUpdateAppWidget] during playback so heavy
 *   background art bitmaps are never re-encoded or transferred every second.
 */
abstract class LyricWidget(
    private val fallbackWidthDp: Int,
    private val isPill: Boolean,
) : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context.applicationContext
        val snapshot = MediaWidgetSnapshot.load(app)
        val lyricState = LyricWidgetState.load(app)
        // If a song is already active and this newly placed widget hasn't received lyrics yet,
        // nudge PlaybackService (when running) to push the live lyric buffer immediately.
        if (snapshot.mediaId != null && snapshot.mediaId != lyricState.mediaId) {
            app.sendBroadcast(
                Intent(ACTION_SYNC_LYRICS_WIDGET).setPackage(app.packageName),
            )
        }
        renderAsync(app, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) {
        renderAsync(context.applicationContext, intArrayOf(id))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            initializedIds.remove(id)
            measuredSizeMap.remove(id)
        }
    }

    override fun onDisabled(context: Context) {
        if (!hasPlacedWidgets(context)) {
            initializedIds.clear()
            measuredSizeMap.clear()
        }
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                renderVariant(
                    context = context,
                    ids = ids,
                    fallbackWidthDp = fallbackWidthDp,
                    isPill = isPill,
                    deadline = SystemClock.elapsedRealtime() + BROADCAST_BUDGET_MS,
                )
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    internal companion object {
        const val ACTION_SYNC_LYRICS_WIDGET = "com.music.bitchord.widget.SYNC_LYRICS_WIDGET"

        private val PROVIDERS = listOf(
            LyricWidgetWide::class.java to 310,
            LyricWidgetPill::class.java to 310,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val initializedIds = ConcurrentHashMap.newKeySet<Int>()
        private val measuredSizeMap = ConcurrentHashMap<Int, WidgetSize>()

        private const val BROADCAST_BUDGET_MS = 9_000L
        private const val RENDER_TIMEOUT_MS = 8_000L
        private const val MAX_BG_SIDE_PX = 960f

        private data class WidgetSize(
            val widthDp: Float,
            val widthPx: Int,
            val heightPx: Int,
            val cornerRadiusPx: Float,
            val density: Float,
            val progressWidthPx: Int,
            val progressHeightPx: Int,
        )

        fun hasPlacedWidgets(context: Context): Boolean {
            val app = context.applicationContext
            val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return false
            return PROVIDERS.any { (cls, _) ->
                val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, cls)) }.getOrNull()
                ids != null && ids.isNotEmpty()
            }
        }

        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                for ((provider, fallbackWidthDp) in PROVIDERS) {
                    val ids = runCatching {
                        manager.getAppWidgetIds(ComponentName(app, provider))
                    }.getOrNull()
                    if (ids == null || ids.isEmpty()) continue
                    renderVariant(
                        context = app,
                        ids = ids,
                        fallbackWidthDp = fallbackWidthDp,
                        isPill = provider == LyricWidgetPill::class.java,
                        deadline = null,
                    )
                }
            }
        }

        /**
         * Lightweight tick called by PlaybackService every ~1s (or on seek / lyric line change).
         * Uses [AppWidgetManager.partiallyUpdateAppWidget] for already initialized widgets so only
         * text views and the slim progress bar bitmap update, without reloading cover art.
         */
        fun tick(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val snapshot = MediaWidgetSnapshot.load(app)
                val lyricState = LyricWidgetState.load(app)
                val packageName = app.packageName

                for ((provider, fallbackWidthDp) in PROVIDERS) {
                    val ids = runCatching {
                        manager.getAppWidgetIds(ComponentName(app, provider))
                    }.getOrNull()
                    if (ids == null || ids.isEmpty()) continue

                    val isPill = provider == LyricWidgetPill::class.java
                    val uninitialized = ids.filterNot { initializedIds.contains(it) }.toIntArray()
                    if (uninitialized.isNotEmpty()) {
                        renderVariant(app, uninitialized, fallbackWidthDp, isPill, deadline = null)
                    }

                    for (id in ids) {
                        if (!initializedIds.contains(id)) continue
                        val size = measuredSizeMap[id] ?: measure(app, manager, id, fallbackWidthDp, isPill)
                        val partial = buildPartialViews(app, packageName, snapshot, lyricState, size, isPill)
                        runCatching { manager.partiallyUpdateAppWidget(id, partial) }
                    }
                }
            }
        }

        private suspend fun renderVariant(
            context: Context,
            ids: IntArray,
            fallbackWidthDp: Int,
            isPill: Boolean,
            deadline: Long?,
        ) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val lyricState = LyricWidgetState.load(context)
            val artKey = snapshot.mediaId?.takeIf { !snapshot.artworkUrl.isNullOrBlank() }

            var loadedArtwork: android.graphics.Bitmap? = null
            var artworkAttempted = false

            for (id in ids) {
                val size = measure(context, manager, id, fallbackWidthDp, isPill)
                measuredSizeMap[id] = size

                val coverPx = context.resources.getDimensionPixelSize(R.dimen.widget_lyric_cover_size)
                    .coerceAtLeast(1)

                val cachedBackdrop = if (isPill) {
                    LyricWidgetArt.peekPillBackdrop(artKey, size.widthPx, size.heightPx)
                } else {
                    LyricWidgetArt.peekWideBackdrop(artKey, size.widthPx, size.heightPx)
                }
                val cachedCover = if (isPill || artKey == null) null else LyricWidgetArt.peekCover(artKey, coverPx)

                val needArtwork = (cachedBackdrop == null || (!isPill && artKey != null && cachedCover == null)) &&
                    !snapshot.artworkUrl.isNullOrBlank()

                if (needArtwork && !artworkAttempted) {
                    artworkAttempted = true
                    val budget = when (deadline) {
                        null -> RENDER_TIMEOUT_MS
                        else -> minOf(RENDER_TIMEOUT_MS, deadline - SystemClock.elapsedRealtime())
                    }.coerceAtLeast(400L)
                    loadedArtwork = withTimeoutOrNull(budget) {
                        LyricWidgetArt.loadArtwork(context, snapshot.artworkUrl)
                    }
                }

                val backdrop = cachedBackdrop ?: if (isPill) {
                    LyricWidgetArt.renderPillBackdrop(
                        key = artKey,
                        artwork = loadedArtwork,
                        widthPx = size.widthPx,
                        heightPx = size.heightPx,
                        density = size.density,
                    )
                } else {
                    LyricWidgetArt.renderWideBackdrop(
                        key = artKey,
                        artwork = loadedArtwork,
                        widthPx = size.widthPx,
                        heightPx = size.heightPx,
                        cornerRadiusPx = size.cornerRadiusPx,
                        density = size.density,
                    )
                }

                val cover = if (isPill) {
                    null
                } else {
                    cachedCover ?: LyricWidgetArt.renderCoverArt(
                        key = artKey,
                        artwork = loadedArtwork,
                        sizePx = coverPx,
                        cornerRadiusPx = context.resources.getDimension(R.dimen.widget_lyric_cover_radius),
                        density = size.density,
                    )
                }

                val views = if (isPill) {
                    buildPillViews(context, snapshot, lyricState, backdrop)
                } else {
                    buildWideViews(context, snapshot, lyricState, size, backdrop, cover)
                }

                val pushed = runCatching {
                    manager.updateAppWidget(id, views)
                    true
                }.getOrDefault(false)
                if (pushed) {
                    initializedIds.add(id)
                }
            }
        }

        private fun buildWideViews(
            context: Context,
            snapshot: MediaWidgetSnapshot,
            lyricState: LyricWidgetState,
            size: WidgetSize,
            backdrop: android.graphics.Bitmap,
            cover: android.graphics.Bitmap?,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lyric_wide)
            val open = openPlayer(context)

            views.setImageViewBitmap(R.id.widget_lyric_bg, backdrop)
            if (cover != null) {
                views.setImageViewBitmap(R.id.widget_lyric_art, cover)
            } else {
                views.setImageViewResource(R.id.widget_lyric_art, R.drawable.widget_lyric_art_placeholder)
            }

            val hasTrack = snapshot.mediaId != null
            views.setTextViewText(
                R.id.widget_lyric_title,
                if (hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
            )
            views.setTextViewText(
                R.id.widget_lyric_artist,
                if (hasTrack) snapshot.artist else context.getString(R.string.widget_open_bitchord),
            )

            // Like glass circle visibility + state.
            if (snapshot.canLike) {
                views.setViewVisibility(R.id.widget_lyric_like, View.VISIBLE)
                views.setImageViewResource(
                    R.id.widget_lyric_like,
                    if (snapshot.isLiked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart,
                )
                views.setInt(
                    R.id.widget_lyric_like,
                    "setBackgroundResource",
                    if (snapshot.isLiked) R.drawable.widget_glass_circle_primary else R.drawable.widget_glass_circle,
                )
                views.setOnClickPendingIntent(
                    R.id.widget_lyric_like,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_LIKE),
                )
            } else {
                views.setViewVisibility(R.id.widget_lyric_like, View.GONE)
            }

            // Dynamic lyric lines & progress bar + timestamps.
            applyLyricAndProgressToWide(context, views, snapshot, lyricState, size)

            // Transport glass buttons.
            views.setImageViewResource(
                R.id.widget_lyric_toggle,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_lyric_toggle,
                context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
            )
            views.setOnClickPendingIntent(
                R.id.widget_lyric_toggle,
                MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE),
            )

            bindTransportButton(
                views = views,
                viewId = R.id.widget_lyric_previous,
                enabled = snapshot.hasPrevious,
                pendingIntent = MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_PREVIOUS),
            )
            bindTransportButton(
                views = views,
                viewId = R.id.widget_lyric_next_track,
                enabled = snapshot.hasNext,
                pendingIntent = MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_NEXT),
            )

            views.setOnClickPendingIntent(R.id.widget_lyric_root, open)
            return views
        }

        private fun buildPillViews(
            context: Context,
            snapshot: MediaWidgetSnapshot,
            lyricState: LyricWidgetState,
            backdrop: android.graphics.Bitmap,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lyric_pill)
            views.setImageViewBitmap(R.id.widget_lyric_pill_bg, backdrop)
            applyLyricToPill(context, views, snapshot, lyricState)
            views.setOnClickPendingIntent(R.id.widget_lyric_pill_root, openPlayer(context))
            return views
        }

        private fun buildPartialViews(
            context: Context,
            packageName: String,
            snapshot: MediaWidgetSnapshot,
            lyricState: LyricWidgetState,
            size: WidgetSize,
            isPill: Boolean,
        ): RemoteViews {
            val layoutId = if (isPill) R.layout.widget_lyric_pill else R.layout.widget_lyric_wide
            val views = RemoteViews(packageName, layoutId)
            if (isPill) {
                applyLyricToPill(context, views, snapshot, lyricState)
            } else {
                applyLyricAndProgressToWide(context, views, snapshot, lyricState, size)
            }
            return views
        }

        private fun applyLyricAndProgressToWide(
            context: Context,
            views: RemoteViews,
            snapshot: MediaWidgetSnapshot,
            lyricState: LyricWidgetState,
            size: WidgetSize,
        ) {
            val (primary, secondary) = resolvedDisplayLines(context, snapshot, lyricState, isWide = true)
            views.setTextViewText(R.id.widget_lyric_current, primary)
            if (secondary.isNotEmpty()) {
                views.setTextViewText(R.id.widget_lyric_next, secondary)
                views.setViewVisibility(R.id.widget_lyric_next, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_lyric_next, View.GONE)
            }

            val alignedTiming = snapshot.mediaId != null && snapshot.mediaId == lyricState.mediaId
            val posMs = if (alignedTiming) lyricState.positionMs else 0L
            val durMs = if (alignedTiming) lyricState.durationMs else 0L
            val fraction = LyricCueResolver.progressFraction(posMs, durMs)

            views.setTextViewText(R.id.widget_lyric_elapsed, LyricCueResolver.formatTime(posMs))
            views.setTextViewText(R.id.widget_lyric_duration, LyricCueResolver.formatTime(durMs))
            views.setImageViewBitmap(
                R.id.widget_lyric_progress,
                LyricWidgetArt.renderProgressTrack(
                    widthPx = size.progressWidthPx,
                    heightPx = size.progressHeightPx,
                    density = size.density,
                    fraction = fraction,
                ),
            )
        }

        private fun applyLyricToPill(
            context: Context,
            views: RemoteViews,
            snapshot: MediaWidgetSnapshot,
            lyricState: LyricWidgetState,
        ) {
            val (primary, secondary) = resolvedDisplayLines(context, snapshot, lyricState, isWide = false)
            views.setTextViewText(R.id.widget_lyric_pill_current, primary)
            if (secondary.isNotEmpty()) {
                views.setTextViewText(R.id.widget_lyric_pill_next, secondary)
                views.setViewVisibility(R.id.widget_lyric_pill_next, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_lyric_pill_next, View.GONE)
            }
        }

        private fun resolvedDisplayLines(
            context: Context,
            snapshot: MediaWidgetSnapshot,
            lyricState: LyricWidgetState,
            isWide: Boolean,
        ): Pair<String, String> {
            val mediaId = snapshot.mediaId
            if (mediaId == null) {
                return if (isWide) {
                    context.getString(R.string.widget_lyric_tap_to_play) to ""
                } else {
                    context.getString(R.string.widget_nothing_played) to context.getString(R.string.widget_open_bitchord)
                }
            }

            if (lyricState.mediaId != mediaId) {
                val trackLine = listOf(snapshot.title, snapshot.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                return context.getString(R.string.widget_lyric_loading) to if (isWide) "" else trackLine
            }

            val primary = lyricState.currentLyric.ifBlank {
                if (lyricState.lyricsLoading) {
                    context.getString(R.string.widget_lyric_loading)
                } else {
                    context.getString(R.string.widget_lyric_unavailable)
                }
            }
            val secondary = lyricState.nextLyric
            return primary to secondary
        }

        private fun bindTransportButton(
            views: RemoteViews,
            viewId: Int,
            enabled: Boolean,
            pendingIntent: PendingIntent,
        ) {
            views.setInt(viewId, "setImageAlpha", if (enabled) 255 else 110)
            views.setOnClickPendingIntent(viewId, pendingIntent)
        }

        private fun openPlayer(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                R.id.widget_lyric_root,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun measure(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            fallbackWidthDp: Int,
            isPill: Boolean,
        ): WidgetSize {
            val resources = context.resources
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            val widthDp = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                0,
            )?.takeIf { it > 0 } ?: fallbackWidthDp

            val heightDp = if (isPill) {
                // Pill widget height is fixed to R.dimen.widget_lyric_pill_height.
                resources.getDimension(R.dimen.widget_lyric_pill_height) / resources.displayMetrics.density
            } else {
                (options?.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                    0,
                )?.takeIf { it > 0 } ?: 144).toFloat()
            }

            val density = resources.displayMetrics.density
            val rawW = widthDp * density
            val rawH = heightDp * density
            val longest = max(rawW, rawH).coerceAtLeast(1f)
            val scale = if (longest > MAX_BG_SIDE_PX) MAX_BG_SIDE_PX / longest else 1f

            val coverDp = resources.getDimension(R.dimen.widget_lyric_cover_size) / density
            val buttonSmDp = resources.getDimension(R.dimen.widget_lyric_button_sm) / density
            val buttonLgDp = resources.getDimension(R.dimen.widget_lyric_button_lg) / density
            // Horizontal chrome eaten before the progress bar: 14 + cover + 12 + 12 + (sm+6+lg+6+sm) + 14.
            val occupiedWidthDp = 14f + coverDp + 12f + 12f + buttonSmDp + 6f + buttonLgDp + 6f + buttonSmDp + 14f
            val progressWidthDp = (widthDp - occupiedWidthDp).coerceAtLeast(64f)
            val progressHeightDp = 12f
            val progressHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                progressHeightDp,
                resources.displayMetrics,
            ).roundToInt().coerceAtLeast(12)

            return WidgetSize(
                widthDp = widthDp.toFloat(),
                widthPx = (rawW * scale).roundToInt().coerceAtLeast(1),
                heightPx = (rawH * scale).roundToInt().coerceAtLeast(1),
                cornerRadiusPx = resources.getDimension(R.dimen.widget_corner_radius) * scale,
                density = density * scale,
                progressWidthPx = (progressWidthDp * density).roundToInt().coerceIn(48, 480),
                progressHeightPx = progressHeightPx.coerceIn(12, 48),
            )
        }
    }
}

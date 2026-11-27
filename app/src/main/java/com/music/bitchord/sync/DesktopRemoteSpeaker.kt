package com.music.bitchord.sync

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import com.music.bitchord.playback.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Exposes an active LuminaChord Desktop playback session as a [RemoteSpeaker] inside
 * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s [MediaSession].
 *
 * This makes Android system surfaces — Quick Settings media card, lock screen controls,
 * media notification, and the system "Streaming" remote volume slider — reflect Desktop's
 * live track, artwork, progress, play/pause, skip, and Windows hardware master volume.
 */
internal class DesktopRemoteSpeaker(
    private val scope: CoroutineScope,
) : RemoteSpeaker {

    override var sink: CastSink? = null

    private data class SnapshotKey(
        val active: Boolean,
        val trackKey: String,
        val queueSignature: Int,
        val queueIndex: Int,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val isLiked: Boolean,
        val shuffle: Boolean,
        val volumePercent: Int,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    )

    @Volatile private var cachedItems: List<MediaItem> = emptyList()
    @Volatile private var cachedQueueIndex: Int = 0
    @Volatile private var cachedDurationsMs: List<Long> = emptyList()
    @Volatile private var cachedTimeline: Timeline = Timeline.EMPTY
    @Volatile private var cachedMetadata: MediaMetadata = MediaMetadata.EMPTY
    @Volatile private var cachedHasPrevious: Boolean = false
    @Volatile private var cachedHasNext: Boolean = false
    @Volatile private var cachedIsLiked: Boolean = false
    @Volatile private var cachedCanLike: Boolean = false
    @Volatile private var cachedShuffle: Boolean = false

    private var lastKey: SnapshotKey? = null

    init {
        scope.launch(Dispatchers.Main.immediate) {
            combine(
                DesktopSyncManager.status,
                DesktopSyncManager.desktopOutputActive,
                DesktopSyncManager.desktopNowPlaying,
                DesktopSyncManager.desktopQueue,
            ) { status, outputActive, nowPlaying, queue ->
                Quad(status, outputActive, nowPlaying, queue)
            }.collect { (status, outputActive, np, q) ->
                val nowActive = outputActive && status is DesktopSyncStatus.Connected && np != null
                val wasActive = lastKey?.active == true
                if (!nowActive || np == null) {
                    val oldKey = lastKey
                    lastKey = SnapshotKey(
                        active = false,
                        trackKey = "",
                        queueSignature = 0,
                        queueIndex = 0,
                        isPlaying = false,
                        isLoading = false,
                        isLiked = false,
                        shuffle = false,
                        volumePercent = 100,
                        hasPrevious = false,
                        hasNext = false,
                    )
                    if (wasActive) {
                        clearCachedTrack()
                        sink?.onRemoteRouteChanged()
                        sink?.onRemoteTrackOrQueueChanged()
                    } else if (oldKey != null && oldKey != lastKey) {
                        sink?.onRemotePlaybackChanged()
                    }
                    return@collect
                }

                val mappedSongs = q.songs.ifEmpty { listOf(np.toSong()) }
                val idx = q.queueIndex.coerceIn(0, (mappedSongs.size - 1).coerceAtLeast(0))
                val items = ArrayList<MediaItem>(mappedSongs.size)
                val durations = ArrayList<Long>(mappedSongs.size)
                var sig = 17
                mappedSongs.forEachIndexed { i, song ->
                    val enriched = if (i == idx) {
                        song.copy(
                            thumbnailUrl = np.thumbnailUrl ?: song.thumbnailUrl,
                            albumName = np.album ?: song.albumName,
                            artistId = np.artistId ?: song.artistId,
                            albumId = np.albumId ?: song.albumId,
                            isVideo = np.isVideo,
                        )
                    } else {
                        song
                    }
                    val durMs = if (i == idx) {
                        np.durationMs.takeIf { it > 0L } ?: enriched.durationMillis()
                    } else {
                        enriched.durationMillis()
                    }.coerceAtLeast(0L)
                    durations += durMs

                    val baseItem = enriched.toMediaItem()
                    val metaBuilder = baseItem.mediaMetadata.buildUpon()
                        .setTitle(enriched.title)
                        .setArtist(enriched.artist)
                    if (!enriched.albumName.isNullOrBlank()) metaBuilder.setAlbumTitle(enriched.albumName)
                    val art = (enriched.thumbnailUrl ?: np.thumbnailUrl)?.takeIf { it.isNotBlank() }
                    if (art != null) metaBuilder.setArtworkUri(art.toUri())
                    items += baseItem.buildUpon().setMediaMetadata(metaBuilder.build()).build()

                    sig = 31 * sig + enriched.videoId.hashCode()
                    sig = 31 * sig + enriched.title.hashCode()
                    sig = 31 * sig + (art?.hashCode() ?: 0)
                }

                val newKey = SnapshotKey(
                    active = true,
                    trackKey = "${np.videoId}|${np.title}|${np.artist}|${np.thumbnailUrl}",
                    queueSignature = sig,
                    queueIndex = idx,
                    isPlaying = np.isPlaying,
                    isLoading = np.isLoading,
                    isLiked = np.isLiked,
                    shuffle = np.shuffle,
                    volumePercent = (np.volume.coerceIn(0f, 1f) * 100f).roundToInt(),
                    hasPrevious = q.hasPrevious || idx > 0,
                    hasNext = q.hasNext || idx < items.lastIndex,
                )

                val prev = lastKey
                lastKey = newKey
                cachedItems = items
                cachedQueueIndex = idx
                cachedDurationsMs = durations
                cachedHasPrevious = newKey.hasPrevious
                cachedHasNext = newKey.hasNext
                cachedIsLiked = np.isLiked
                cachedCanLike = np.videoId.isNotBlank() && !np.videoId.startsWith("local:")
                cachedShuffle = np.shuffle
                cachedMetadata = items.getOrNull(idx)?.mediaMetadata ?: MediaMetadata.EMPTY

                if (prev?.queueSignature != newKey.queueSignature || prev.queueIndex != newKey.queueIndex || prev.trackKey != newKey.trackKey) {
                    cachedTimeline = DesktopTimeline(items, durations)
                }

                when {
                    !wasActive -> {
                        sink?.onRemoteRouteChanged()
                        sink?.onRemoteTrackOrQueueChanged()
                    }
                    prev?.trackKey != newKey.trackKey ||
                        prev.queueSignature != newKey.queueSignature ||
                        prev.queueIndex != newKey.queueIndex ||
                        prev.isLiked != newKey.isLiked ||
                        prev.shuffle != newKey.shuffle ||
                        prev.hasPrevious != newKey.hasPrevious ||
                        prev.hasNext != newKey.hasNext -> {
                        sink?.onRemoteTrackOrQueueChanged()
                    }
                    else -> {
                        if (prev?.isPlaying != newKey.isPlaying || prev.isLoading != newKey.isLoading) {
                            sink?.onRemotePlaybackChanged()
                        }
                        if (prev?.volumePercent != newKey.volumePercent) {
                            sink?.onRemoteVolumeChanged()
                        }
                    }
                }
            }
        }
    }

    private fun clearCachedTrack() {
        cachedItems = emptyList()
        cachedDurationsMs = emptyList()
        cachedQueueIndex = 0
        cachedTimeline = Timeline.EMPTY
        cachedMetadata = MediaMetadata.EMPTY
        cachedHasPrevious = false
        cachedHasNext = false
        cachedIsLiked = false
        cachedCanLike = false
        cachedShuffle = false
    }

    override val active: Boolean
        get() = DesktopSyncManager.desktopOutputActive.value &&
            DesktopSyncManager.status.value is DesktopSyncStatus.Connected &&
            DesktopSyncManager.desktopNowPlaying.value != null

    override val playWhenReady: Boolean
        get() = DesktopSyncManager.desktopNowPlaying.value?.isPlaying == true

    override val isPlaying: Boolean
        get() = DesktopSyncManager.desktopNowPlaying.value?.let { it.isPlaying && !it.isLoading } == true

    override val playbackState: Int
        get() = when {
            !active -> Player.STATE_IDLE
            DesktopSyncManager.desktopNowPlaying.value?.isLoading == true -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

    override val positionMs: Long
        get() = DesktopSyncManager.desktopPosition.positionMs.coerceAtLeast(0L)

    override val durationMs: Long
        get() = DesktopSyncManager.desktopNowPlaying.value?.durationMs?.takeIf { it > 0L } ?: C.TIME_UNSET

    override val deviceVolume: Int
        get() = ((DesktopSyncManager.desktopNowPlaying.value?.volume ?: 1f).coerceIn(0f, 1f) * 100f).roundToInt()

    val isLiked: Boolean get() = cachedIsLiked
    val canLike: Boolean get() = cachedCanLike
    val shuffleEnabled: Boolean get() = cachedShuffle

    fun timeline(): Timeline = cachedTimeline
    fun mediaItemCount(): Int = cachedItems.size
    fun mediaItemAt(index: Int): MediaItem = cachedItems[index]
    fun currentMediaItemIndex(): Int = cachedQueueIndex.coerceIn(0, (cachedItems.size - 1).coerceAtLeast(0))
    fun currentMediaItem(): MediaItem? = cachedItems.getOrNull(cachedQueueIndex)
    fun currentMetadata(): MediaMetadata = cachedMetadata
    fun hasPrevious(): Boolean = cachedHasPrevious
    fun hasNext(): Boolean = cachedHasNext

    override fun play() = DesktopSyncManager.sendRemoteCommand("play")
    override fun pause() = DesktopSyncManager.sendRemoteCommand("pause")
    override fun seekTo(positionMs: Long) = DesktopSyncManager.sendSeek(positionMs)
    override fun setVolume(fraction: Float) = DesktopSyncManager.setDesktopVolume(fraction)

    fun next() = DesktopSyncManager.sendRemoteCommand("next")
    fun previous() = DesktopSyncManager.sendRemoteCommand("previous")
    fun seekOrJump(index: Int, positionMs: Long) {
        if (index == cachedQueueIndex) {
            seekTo(positionMs)
        } else if (index in cachedItems.indices) {
            DesktopSyncManager.jumpDesktopQueue(index)
        }
    }

    fun toggleShuffle() = DesktopSyncManager.toggleShuffleOnDesktop()
    fun toggleFavorite() {
        val videoId = DesktopSyncManager.desktopNowPlaying.value?.videoId?.takeIf { it.isNotBlank() } ?: return
        DesktopSyncManager.toggleLikeOnDesktop(videoId)
    }

    override fun onLocalTransition(item: MediaItem?, reason: Int) = Unit
    override fun onLocalQueueChanged() = Unit
    override fun onLocalRepeatChanged(repeatMode: Int) = Unit
    override fun onLocalStartedPlaying(player: Player) = Unit

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private class DesktopTimeline(
        private val items: List<MediaItem>,
        private val durationsMs: List<Long>,
    ) : Timeline() {
        override fun getWindowCount(): Int = items.size

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            val item = items[windowIndex]
            val durMs = durationsMs.getOrElse(windowIndex) { 0L }
            val durUs = if (durMs > 0L) durMs * 1_000L else C.TIME_UNSET
            window.set(
                item.mediaId + "#$windowIndex",
                item,
                null,
                C.TIME_UNSET,
                C.TIME_UNSET,
                C.TIME_UNSET,
                durMs > 0L,
                false,
                null,
                0L,
                durUs,
                windowIndex,
                windowIndex,
                0L,
            )
            return window
        }

        override fun getPeriodCount(): Int = items.size

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val item = items[periodIndex]
            val durMs = durationsMs.getOrElse(periodIndex) { 0L }
            val durUs = if (durMs > 0L) durMs * 1_000L else C.TIME_UNSET
            val id = if (setIds) item.mediaId + "#p$periodIndex" else null
            period.set(id, id, periodIndex, durUs, 0L)
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            val s = uid as? String ?: return C.INDEX_UNSET
            val idx = s.substringAfterLast("#p", "").toIntOrNull() ?: return C.INDEX_UNSET
            return if (idx in items.indices) idx else C.INDEX_UNSET
        }

        override fun getUidOfPeriod(periodIndex: Int): Any = items[periodIndex].mediaId + "#p$periodIndex"
    }
}

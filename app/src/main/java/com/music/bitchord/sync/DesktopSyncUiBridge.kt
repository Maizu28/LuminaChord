package com.music.bitchord.sync

import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueCoordinator
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.clearUserQueueWithUndo
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.removeQueueEntriesWithUndo
import com.music.bitchord.playback.removeQueueItemWithUndo
import com.music.bitchord.playback.replayFromHistory
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import com.music.bitchord.ui.LyricsProviderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class DesktopOverlayState(
    val usingDesktopOutput: Boolean,
    val desktopNowPlaying: DesktopNowPlayingTrack?,
    val desktopLyrics: DesktopLyricsState,
    val player: PlayerState,
    val shuffleEnabled: Boolean,
)

data class OverlayLyricsResolution(
    val lines: List<LyricLine>?,
    val source: LyricsSource?,
    val providerStates: Map<LyricsSource, LyricsProviderState>,
    val unavailable: Boolean,
)

@Composable
fun rememberDesktopOverlayState(
    controller: MediaController?,
    localPlayer: PlayerState,
    localShuffleEnabled: Boolean,
    localLyrics: List<LyricLine>? = null,
    localLyricsSource: LyricsSource? = null,
    localLyricsChecked: Boolean = false,
    onToggleLikeSong: ((Song) -> Unit)? = null,
): DesktopOverlayState {
    val context = LocalContext.current
    LaunchedEffect(Unit) { DesktopSyncManager.init(context) }

    val desktopSyncStatus by DesktopSyncManager.status.collectAsStateWithLifecycle()
    val desktopOutputActive by DesktopSyncManager.desktopOutputActive.collectAsStateWithLifecycle()
    val desktopNowPlaying by DesktopSyncManager.desktopNowPlaying.collectAsStateWithLifecycle()
    val desktopQueueState by DesktopSyncManager.desktopQueue.collectAsStateWithLifecycle()
    val desktopLyricsState by DesktopSyncManager.desktopLyrics.collectAsStateWithLifecycle()

    val usingDesktopOutput = desktopOutputActive &&
        desktopSyncStatus is DesktopSyncStatus.Connected &&
        desktopNowPlaying != null

    val effectivePlayer = remember(localPlayer, usingDesktopOutput, desktopNowPlaying, desktopQueueState) {
        if (!usingDesktopOutput) {
            localPlayer
        } else {
            val np = desktopNowPlaying!!
            val mappedQueue = desktopQueueState.songs.ifEmpty { listOf(np.toSong()) }
            val mappedIdx = desktopQueueState.queueIndex.coerceIn(0, (mappedQueue.size - 1).coerceAtLeast(0))
            val curSong = mappedQueue.getOrNull(mappedIdx)?.let { queued ->
                queued.copy(
                    thumbnailUrl = np.thumbnailUrl ?: queued.thumbnailUrl,
                    albumName = np.album ?: queued.albumName,
                    artistId = np.artistId ?: queued.artistId,
                    albumId = np.albumId ?: queued.albumId,
                    isVideo = np.isVideo,
                )
            } ?: np.toSong()
            PlayerState(
                song = curSong,
                isPlaying = np.isPlaying,
                position = DesktopSyncManager.desktopPosition,
                durationMs = np.durationMs.takeIf { it > 0L } ?: curSong.durationMillis(),
                isLoading = np.isLoading,
                repeatMode = np.repeatMode,
                queue = mappedQueue,
                queueIndex = mappedIdx,
                hasPrevious = desktopQueueState.hasPrevious || mappedIdx > 0,
                hasNext = desktopQueueState.hasNext || mappedIdx < mappedQueue.lastIndex,
            )
        }
    }

    val audioMgr = remember(context) { context.getSystemService(AudioManager::class.java) }
    val bridgeScope = rememberCoroutineScope()
    DisposableEffect(controller, audioMgr, bridgeScope, onToggleLikeSong) {
        DesktopSyncManager.mobileBridge = createMobilePlaybackBridge(controller, audioMgr, bridgeScope, onToggleLikeSong)
        onDispose { DesktopSyncManager.mobileBridge = null }
    }

    LaunchedEffect(
        desktopSyncStatus,
        usingDesktopOutput,
        localPlayer.song?.videoId,
        localLyrics,
        localLyricsSource,
        localLyricsChecked,
    ) {
        if (!usingDesktopOutput) {
            val activeSong = localPlayer.song
            DesktopSyncManager.updateMobileLyrics(
                videoId = activeSong?.videoId.orEmpty(),
                lines = localLyrics.orEmpty(),
                source = localLyricsSource?.label,
                loading = !localLyricsChecked && activeSong != null,
                unavailable = localLyricsChecked && localLyrics.isNullOrEmpty(),
            )
        }
    }

    LaunchedEffect(
        desktopSyncStatus,
        usingDesktopOutput,
        localPlayer.song?.videoId,
        localPlayer.isPlaying,
        localPlayer.queueIndex,
        localPlayer.queue.size,
        localPlayer.repeatMode,
        localShuffleEnabled,
    ) {
        if (desktopSyncStatus is DesktopSyncStatus.Connected && !usingDesktopOutput) {
            DesktopSyncManager.mobileBridge?.currentPlaybackSnapshot()?.let {
                DesktopSyncManager.pushMobileSnapshot(it)
            }
        }
    }

    val shuffleEnabled = if (usingDesktopOutput) (desktopNowPlaying?.shuffle ?: false) else localShuffleEnabled
    return DesktopOverlayState(
        usingDesktopOutput = usingDesktopOutput,
        desktopNowPlaying = desktopNowPlaying,
        desktopLyrics = desktopLyricsState,
        player = effectivePlayer,
        shuffleEnabled = shuffleEnabled,
    )
}

private fun createMobilePlaybackBridge(
    controller: MediaController?,
    audioMgr: AudioManager?,
    bridgeScope: CoroutineScope,
    onToggleLikeSong: ((Song) -> Unit)? = null,
): MobilePlaybackBridge = object : MobilePlaybackBridge {
    override suspend fun currentPlaybackSnapshot(): MobilePlaybackSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            val c = controller ?: return@withContext null
            if (!c.isConnected) return@withContext null
            runCatching {
                val item = c.currentMediaItem?.toSong()
                val curQueue = (0 until c.mediaItemCount).map { idx -> c.getMediaItemAt(idx).toSong() }
                val maxVol = audioMgr?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
                val curVol = (audioMgr?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVol
                MobilePlaybackSnapshot(
                    song = item,
                    isPlaying = c.isPlaying,
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = c.duration.coerceAtLeast(0L),
                    volume = curVol.coerceIn(0f, 1f),
                    shuffle = QueueShuffle.enabled.value,
                    repeatMode = c.repeatMode,
                    queue = curQueue,
                    queueIndex = c.currentMediaItemIndex.coerceAtLeast(0),
                )
            }.getOrNull()
        }

    override fun pauseLocalPlayback() {
        bridgeScope.launch(Dispatchers.Main.immediate) {
            val c = controller ?: return@launch
            if (c.isConnected) c.pause()
        }
    }

    override fun playFromDesktop(songs: List<Song>, startIndex: Int, isOneOff: Boolean, isPlaying: Boolean) {
        if (songs.isEmpty()) return
        bridgeScope.launch(Dispatchers.Main.immediate) {
            val c = controller ?: return@launch
            if (!c.isConnected) return@launch
            val currentTimeline = (0 until c.mediaItemCount).map { idx -> c.getMediaItemAt(idx).toSong() }
            val currentIndex = c.currentMediaItemIndex
            val first = songs.getOrNull(startIndex) ?: songs.first()
            val source = QueueSource(
                title = first.playbackSource ?: first.albumName ?: "Desktop",
                type = first.playbackSourceType ?: PlaybackSourceType.QUEUE,
                id = first.playbackSourceId,
            )
            if (isOneOff) {
                val queue = QueueCoordinator.buildOneOffQueue(
                    currentTimeline = currentTimeline,
                    currentIndex = currentIndex,
                    tappedSong = first,
                    source = source,
                )
                c.playSongs(
                    songs = queue,
                    startIndex = 0,
                    startPositionMs = 0L,
                    playWhenReady = isPlaying,
                    preserveOrder = true,
                )
            } else {
                val built = QueueCoordinator.buildContextQueue(
                    currentTimeline = currentTimeline,
                    currentIndex = currentIndex,
                    newContextSongs = songs,
                    selectedIndex = startIndex.coerceIn(songs.indices),
                    contextSource = source,
                )
                c.playSongs(
                    songs = built.timeline,
                    startIndex = built.startIndex,
                    startPositionMs = 0L,
                    playWhenReady = isPlaying,
                    preserveOrder = false,
                )
            }
        }
    }

    override fun enqueueFromDesktop(song: Song, playNext: Boolean) {
        bridgeScope.launch(Dispatchers.Main.immediate) {
            val c = controller ?: return@launch
            if (!c.isConnected) return@launch
            val current = c.currentMediaItem?.toSong()
            val timeline = (0 until c.mediaItemCount).map { idx -> c.getMediaItemAt(idx).toSong() }
            val at = QueueCoordinator.findUserQueueInsertionIndex(
                timeline = timeline,
                currentIndex = c.currentMediaItemIndex,
                isNext = playNext,
            )
            val queued = song.copy(
                radioName = current?.radioName,
                playbackSource = current?.playbackSource ?: song.playbackSource ?: "Queue",
                playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                playbackSourceId = current?.playbackSourceId,
            ).asQueueEntry(QueueTier.USER_QUEUE)
            c.addMediaItem(at, queued.toMediaItem())
        }
    }

    override fun applyHandoffFromDesktop(
        songs: List<Song>,
        startIndex: Int,
        positionMs: Long,
        isPlaying: Boolean,
        shuffle: Boolean?,
        repeatMode: Int?,
    ) {
        bridgeScope.launch(Dispatchers.Main.immediate) {
            val c = controller ?: return@launch
            if (!c.isConnected) return@launch
            if (repeatMode != null) {
                val mapped = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                AppSettings.setRepeatMode(mapped)
                c.repeatMode = mapped
            }
            c.playSongs(
                songs = songs,
                startIndex = startIndex,
                startPositionMs = positionMs,
                playWhenReady = isPlaying,
                preserveOrder = true,
            )
        }
    }

    override fun applyRemoteCommandFromDesktop(
        action: String,
        value: String?,
        positionMs: Long?,
        index: Int?,
        from: Int?,
        to: Int?,
        shuffle: Boolean?,
        repeatMode: Int?,
    ) {
        bridgeScope.launch(Dispatchers.Main.immediate) {
            val c = controller ?: return@launch
            if (!c.isConnected) return@launch
            when (action.lowercase()) {
                "play_pause" -> if (c.isPlaying) c.pause() else c.play()
                "play" -> c.play()
                "pause" -> c.pause()
                "next" -> c.seekToNextMediaItem()
                "previous" -> c.seekToPrevious()
                "seek" -> {
                    val target = positionMs ?: value?.toLongOrNull() ?: return@launch
                    c.seekTo(target.coerceAtLeast(0L))
                }
                "volume" -> {
                    val v = value?.toFloatOrNull()?.coerceIn(0f, 1f) ?: return@launch
                    val maxVol = audioMgr?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
                    audioMgr?.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVol).roundToInt(), 0)
                }
                "toggle_shuffle" -> QueueShuffle.toggle(c)
                "set_shuffle" -> {
                    val want = shuffle ?: value?.toBooleanStrictOrNull() ?: return@launch
                    if (QueueShuffle.enabled.value != want) QueueShuffle.toggle(c)
                }
                "cycle_repeat" -> {
                    val next = when (c.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    AppSettings.setRepeatMode(next)
                    c.repeatMode = next
                }
                "set_repeat" -> {
                    val next = (repeatMode ?: value?.toIntOrNull())?.coerceIn(0, 2) ?: return@launch
                    AppSettings.setRepeatMode(next)
                    c.repeatMode = next
                }
                "jump_queue" -> {
                    val idx = index ?: value?.toIntOrNull() ?: return@launch
                    if (idx in 0 until c.mediaItemCount) {
                        c.seekTo(idx, 0L)
                        c.play()
                    }
                }
                "remove_queue" -> {
                    val idx = index ?: value?.toIntOrNull() ?: return@launch
                    if (idx in 0 until c.mediaItemCount && idx != c.currentMediaItemIndex) {
                        c.removeMediaItem(idx)
                    }
                }
                "move_queue" -> {
                    val fromIdx = from ?: return@launch
                    val toIdx = to ?: return@launch
                    if (fromIdx in 0 until c.mediaItemCount && toIdx in 0 until c.mediaItemCount && fromIdx != toIdx) {
                        c.moveMediaItem(fromIdx, toIdx)
                    }
                }
                "clear_queue" -> c.clearUserQueueWithUndo()
                "toggle_like" -> {
                    val song = c.currentMediaItem?.toSong() ?: return@launch
                    onToggleLikeSong?.invoke(song)
                }
            }
        }
    }
}

fun isDesktopOutputReady(): Boolean =
    DesktopSyncManager.desktopOutputActive.value && DesktopSyncManager.status.value is DesktopSyncStatus.Connected

fun overlayTogglePlayPause(
    usingDesktopOutput: Boolean,
    controller: MediaController?,
    showHostOnlyNotice: () -> Unit,
) {
    if (usingDesktopOutput) {
        DesktopSyncManager.sendRemoteCommand("play_pause")
    } else controller?.let { c ->
        val party = ListenTogether.state.value
        if (party.controlsLocked && !party.playback.isPlaying && !c.isPlaying) {
            showHostOnlyNotice()
        } else if (c.isPlaying) {
            c.pause()
        } else {
            c.play()
        }
    }
}

fun overlayNext(usingDesktopOutput: Boolean, controller: MediaController?) {
    if (usingDesktopOutput) DesktopSyncManager.sendRemoteCommand("next")
    else controller?.seekToNextMediaItem()
}

fun overlayPrevious(usingDesktopOutput: Boolean, controller: MediaController?) {
    if (usingDesktopOutput) DesktopSyncManager.sendRemoteCommand("previous")
    else controller?.seekToPrevious()
}

fun overlayPreviousRestarts(
    usingDesktopOutput: Boolean,
    player: PlayerState,
    controller: MediaController?,
): Boolean {
    if (usingDesktopOutput) {
        return DesktopSyncManager.desktopPosition.positionMs > 3_000L || !player.hasPrevious
    }
    return controller?.let {
        it.currentPosition > it.maxSeekToPreviousPosition || !it.hasPreviousMediaItem()
    } ?: true
}

fun overlayCurrentPlaybackSong(
    usingDesktopOutput: Boolean,
    desktopNowPlaying: DesktopNowPlayingTrack?,
    controller: MediaController?,
): Song? = if (usingDesktopOutput) desktopNowPlaying?.toSong() else controller?.currentMediaItem?.toSong()

fun overlaySeekFraction(
    usingDesktopOutput: Boolean,
    playerDurationMs: Long,
    controller: MediaController?,
    fraction: Float,
    seekEndGuardMs: Long,
) {
    if (usingDesktopOutput) {
        if (playerDurationMs > 0L) DesktopSyncManager.sendSeek((fraction * playerDurationMs).toLong())
    } else controller?.let { c ->
        val duration = c.duration
        if (duration > 0) {
            c.seekTo((fraction * duration).toLong().coerceIn(0L, (duration - seekEndGuardMs).coerceAtLeast(0L)))
        }
    }
}

fun overlaySeekMs(
    usingDesktopOutput: Boolean,
    playerDurationMs: Long,
    controller: MediaController?,
    target: Long,
    seekEndGuardMs: Long,
) {
    if (usingDesktopOutput) {
        val clamped = if (playerDurationMs > 0L) target.coerceIn(0L, playerDurationMs) else target.coerceAtLeast(0L)
        DesktopSyncManager.sendSeek(clamped)
    } else controller?.let { c ->
        val duration = c.duration
        c.seekTo(
            if (duration > 0) target.coerceIn(0L, (duration - seekEndGuardMs).coerceAtLeast(0L))
            else target.coerceAtLeast(0L),
        )
    }
}

fun overlayCycleRepeat(usingDesktopOutput: Boolean, controller: MediaController?) {
    if (usingDesktopOutput) {
        DesktopSyncManager.cycleRepeatOnDesktop()
    } else controller?.let {
        val next = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        AppSettings.setRepeatMode(next)
        it.repeatMode = next
    }
}

fun overlayJumpTo(
    usingDesktopOutput: Boolean,
    player: PlayerState,
    controller: MediaController?,
    scope: CoroutineScope,
    index: Int,
) {
    if (usingDesktopOutput) {
        DesktopSyncManager.jumpDesktopQueue(index)
    } else controller?.let { c ->
        val historySong = player.queue.getOrNull(index)
            ?.takeIf { index < c.currentMediaItemIndex }
        if (historySong == null) {
            c.seekTo(index, 0L)
            c.play()
        } else {
            scope.launch {
                if (!c.replayFromHistory(index, historySong)) c.seekTo(index, 0L)
                c.play()
            }
        }
    }
}

fun overlayRemoveFromQueue(
    usingDesktopOutput: Boolean,
    player: PlayerState,
    controller: MediaController?,
    scope: CoroutineScope,
    index: Int,
) {
    if (usingDesktopOutput) {
        DesktopSyncManager.removeFromDesktopQueue(index)
    } else {
        val queueSong = player.queue.getOrNull(index)
        if (controller != null && queueSong != null) scope.launch { controller.removeQueueItemWithUndo(index, queueSong) }
    }
}

suspend fun overlayRemoveQueueEntries(
    usingDesktopOutput: Boolean,
    player: PlayerState,
    controller: MediaController?,
    entries: List<QueueEntryIdentity>,
) {
    if (usingDesktopOutput) {
        val indicesToRemove = player.queue.indices.filter { idx ->
            val item = player.queue[idx]
            entries.any { entry ->
                (entry.queueEntryId != null && entry.queueEntryId == item.queueEntryId) ||
                    entry.mediaId == item.videoId
            }
        }.sortedDescending()
        indicesToRemove.forEach { DesktopSyncManager.removeFromDesktopQueue(it) }
    } else {
        controller?.removeQueueEntriesWithUndo(entries)
    }
}

fun overlayClearQueue(
    usingDesktopOutput: Boolean,
    controller: MediaController?,
    scope: CoroutineScope,
) {
    if (usingDesktopOutput) {
        DesktopSyncManager.clearDesktopQueue()
    } else controller?.let { c ->
        scope.launch { c.clearUserQueueWithUndo() }
    }
}

fun resolveOverlayLyrics(
    usingDesktopOutput: Boolean,
    desktopLyricsState: DesktopLyricsState,
    displayedSongVideoId: String,
    localLyrics: List<LyricLine>?,
    localSource: LyricsSource?,
    localProviderStates: Map<LyricsSource, LyricsProviderState>,
    lyricsChecked: Boolean,
): OverlayLyricsResolution {
    val desktopHasLines = usingDesktopOutput &&
        desktopLyricsState.lines.isNotEmpty() &&
        (desktopLyricsState.videoId.isBlank() || desktopLyricsState.videoId == displayedSongVideoId)
    return OverlayLyricsResolution(
        lines = if (desktopHasLines) desktopLyricsState.lines else localLyrics,
        source = if (desktopHasLines) (desktopLyricsState.source ?: localSource) else localSource,
        providerStates = if (desktopHasLines && desktopLyricsState.source != null) {
            mapOf(desktopLyricsState.source to LyricsProviderState.FOUND)
        } else {
            localProviderStates
        },
        unavailable = if (usingDesktopOutput) {
            desktopLyricsState.lines.isEmpty() && !desktopLyricsState.loading && lyricsChecked && localLyrics.isNullOrEmpty()
        } else {
            lyricsChecked && localLyrics.isNullOrEmpty()
        },
    )
}

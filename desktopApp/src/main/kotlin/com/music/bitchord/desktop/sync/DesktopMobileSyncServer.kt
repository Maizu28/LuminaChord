package com.music.bitchord.desktop.sync

import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricSyllable
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.DesktopLocalArtwork
import com.music.bitchord.desktop.DesktopLocalMusic
import com.music.bitchord.desktop.DesktopPersistence
import com.music.bitchord.desktop.DesktopPlaylist
import com.music.bitchord.desktop.DesktopPlaylistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

data class DesktopNowPlayingInfo(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val thumbnailUrl: String? = null,
    val volume: Float = 1.0f,
    val videoId: String = "",
    val durationText: String? = null,
    val isLoading: Boolean = false,
    val artistId: String? = null,
    val albumId: String? = null,
    val isVideo: Boolean = false,
    val isLiked: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

data class DesktopQueueSnapshot(
    val version: Long = 0L,
    val queueIndex: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val songs: List<Song> = emptyList(),
)

data class DesktopLyricsSnapshot(
    val version: Long = 0L,
    val videoId: String = "",
    val source: String? = null,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
)

data class DesktopHandoffState(
    val songs: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

data class SyncRemoteCommand(
    val action: String = "",
    val value: String? = null,
    val index: Int? = null,
    val from: Int? = null,
    val to: Int? = null,
    val positionMs: Long? = null,
    val isPlaying: Boolean? = null,
    val shuffle: Boolean? = null,
    val repeatMode: Int? = null,
    val playNext: Boolean? = null,
    val song: SyncedSongDto? = null,
    val songs: List<SyncedSongDto>? = null,
)

data class MobileSessionSnapshot(
    val deviceName: String = "Android Device",
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val volume: Float = 1.0f,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val queueIndex: Int = 0,
    val songs: List<SyncedSongDto> = emptyList(),
    val lyrics: DesktopLyricsSnapshot? = null,
    val receivedAtMs: Long = System.currentTimeMillis(),
) {
    fun estimatedPositionMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0L)
        val delta = (nowMs - receivedAtMs).coerceIn(0L, 60_000L)
        val pos = positionMs.coerceAtLeast(0L) + delta
        return if (durationMs > 0L) pos.coerceAtMost(durationMs) else pos
    }
}

data class ConnectedMobileClient(
    val id: String,
    val deviceName: String,
    val ip: String,
    val pairedAt: Long,
    var lastSeen: Long = pairedAt,
)

data class NetworkCandidate(
    val ip: String,
    val displayName: String,
    val broadcastIp: String?,
    val isVirtualOrTunnel: Boolean,
    val score: Int,
)

data class LibrarySyncData(
    val likedIds: Set<String> = emptySet(),
    val playlists: List<DesktopPlaylist> = emptyList(),
    val history: List<Song> = emptyList(),
)

@Serializable
data class SyncedSongDto(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val localPath: String? = null,
    val fileName: String? = null,
)

@Serializable
data class SyncedPlaylistDto(
    val id: String,
    val title: String,
    val songs: List<SyncedSongDto> = emptyList(),
    val kind: String = "local",
)

@Serializable
private data class PairRequestDto(
    val device: String = "Android Device",
    val code: String = "",
    val transport: String = "",
)

@Serializable
private data class RemoteCommandDto(
    val action: String = "",
    val value: String? = null,
    val index: Int? = null,
    val from: Int? = null,
    val to: Int? = null,
    val positionMs: Long? = null,
    val isPlaying: Boolean? = null,
    val shuffle: Boolean? = null,
    val repeatMode: Int? = null,
    val playNext: Boolean? = null,
    val song: SyncedSongDto? = null,
    val songs: List<SyncedSongDto>? = null,
)

@Serializable
private data class QueueSnapshotDto(
    val version: Long = 0L,
    val queueIndex: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val songs: List<SyncedSongDto> = emptyList(),
)

@Serializable
private data class LyricSyllableDto(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
)

@Serializable
private data class LyricWordDto(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val syllables: List<LyricSyllableDto> = emptyList(),
)

@Serializable
private data class BackgroundLyricLineDto(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWordDto> = emptyList(),
    val sungUntilMs: Long? = null,
    val alignment: String = "Start",
)

@Serializable
private data class LyricLineDto(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWordDto> = emptyList(),
    val sungUntilMs: Long? = null,
    val alignment: String = "Start",
    val background: BackgroundLyricLineDto? = null,
)

@Serializable
private data class LyricsSnapshotDto(
    val version: Long = 0L,
    val videoId: String = "",
    val source: String? = null,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val lines: List<LyricLineDto> = emptyList(),
)

@Serializable
private data class PendingMobileCommandDto(
    val id: Long,
    val action: String,
    val value: String? = null,
    val index: Int? = null,
    val from: Int? = null,
    val to: Int? = null,
    val positionMs: Long? = null,
    val isPlaying: Boolean? = null,
    val shuffle: Boolean? = null,
    val repeatMode: Int? = null,
    val playNext: Boolean? = null,
    val song: SyncedSongDto? = null,
    val songs: List<SyncedSongDto>? = null,
)

@Serializable
private data class HandoffResponseDto(
    val status: String = "ok",
    val queueIndex: Int = 0,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val songs: List<SyncedSongDto> = emptyList(),
)

@Serializable
private data class MobileStatePushDto(
    val deviceName: String = "Android Device",
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val volume: Float = 1.0f,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val queueIndex: Int = 0,
    val songs: List<SyncedSongDto> = emptyList(),
    val lyrics: LyricsSnapshotDto? = null,
)

@Serializable
private data class SyncExchangeDto(
    val device: String = "Android Device",
    val syncPlaylists: Boolean = true,
    val syncFavorites: Boolean = true,
    val syncHistory: Boolean = true,
    val timestamp: Long = 0L,
    val likedIds: List<String> = emptyList(),
    val playlists: List<SyncedPlaylistDto> = emptyList(),
    val history: List<SyncedSongDto> = emptyList(),
)

@Serializable
private data class SyncResponseDto(
    val status: String,
    val timestamp: Long,
    val message: String,
    val mergedLikedCount: Int = 0,
    val mergedPlaylistsCount: Int = 0,
    val mergedHistoryCount: Int = 0,
    val likedIds: List<String> = emptyList(),
    val playlists: List<SyncedPlaylistDto> = emptyList(),
    val history: List<SyncedSongDto> = emptyList(),
)

@Serializable
private data class LocalSongDto(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val path: String = "",
)

@Serializable
private data class LocalMusicListResponseDto(
    val status: String,
    val folder: String,
    val count: Int,
    val songs: List<LocalSongDto>,
)

@Serializable
private data class LocalMergeMobileSongDto(
    val title: String,
    val artist: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
)

@Serializable
private data class LocalMergeRequestDto(
    val mobileCount: Int = 0,
    val mobileSongs: List<LocalMergeMobileSongDto> = emptyList(),
)

@Serializable
private data class LocalMergeResponseDto(
    val status: String,
    val desktopCount: Int,
    val mobileCount: Int,
    val missingOnMobileCount: Int,
    val missingOnDesktopCount: Int,
    val missingOnMobile: List<LocalSongDto> = emptyList(),
    val message: String,
)

@Serializable
private data class NowPlayingDto(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val thumbnailUrl: String? = null,
    val volume: Float = 1.0f,
    val videoId: String = "",
    val durationText: String? = null,
    val isLoading: Boolean = false,
    val artistId: String? = null,
    val albumId: String? = null,
    val isVideo: Boolean = false,
    val isLiked: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

@Serializable
private data class StatusResponseDto(
    val status: String,
    val app: String,
    val version: String,
    val platform: String,
    val deviceName: String,
    val serverTime: Long,
    val primaryIp: String,
    val allIps: List<String>,
    val port: Int,
    val requirePairingCode: Boolean,
    val connectedDevicesCount: Int,
    val localMusicCount: Int,
    val activeOutput: String = "desktop",
    val queueVersion: Long = 0L,
    val lyricsVersion: Long = 0L,
    val nowPlaying: NowPlayingDto? = null,
    val queue: QueueSnapshotDto? = null,
    val lyrics: LyricsSnapshotDto? = null,
    val pendingCommands: List<PendingMobileCommandDto> = emptyList(),
)

@Serializable
private data class BeaconPayloadDto(
    val deviceName: String,
    val ip: String,
    val ips: List<String>,
    val port: Int,
    val code: String,
    val requirePairingCode: Boolean,
)

/**
 * Embedded HTTP server and UDP beacon broadcaster on Desktop Windows to connect
 * with the LuminaChord Android mobile app over local Wi-Fi LAN or USB ADB reverse tunnel.
 */
object DesktopMobileSyncServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var beaconJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var persistence: DesktopPersistence? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val KEY_SYNC_SERVER_ENABLED = "mobile_sync_server_enabled"
    private const val KEY_SYNC_PORT = "mobile_sync_port"
    private const val KEY_SYNC_PAIRING_CODE = "mobile_sync_pairing_code"
    private const val KEY_SYNC_REQUIRE_CODE = "mobile_sync_require_code"
    private const val KEY_SYNC_BEACON_ENABLED = "mobile_sync_beacon_enabled"
    private const val KEY_SYNC_PREFERRED_IP = "mobile_sync_preferred_ip"

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(8688)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _pairingCode = MutableStateFlow("4829")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _requirePairingCode = MutableStateFlow(true)
    val requirePairingCode: StateFlow<Boolean> = _requirePairingCode.asStateFlow()

    private val _autoDiscoverBeacon = MutableStateFlow(true)
    val autoDiscoverBeacon: StateFlow<Boolean> = _autoDiscoverBeacon.asStateFlow()

    private val _preferredIp = MutableStateFlow<String?>(null)
    val preferredIp: StateFlow<String?> = _preferredIp.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedMobileClient>>(emptyList())
    val connectedClients: StateFlow<List<ConnectedMobileClient>> = _connectedClients.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    private val _lastActionMessage = MutableStateFlow<String?>("Siap menerima koneksi dari aplikasi mobile")
    val lastActionMessage: StateFlow<String?> = _lastActionMessage.asStateFlow()

    private val _activeAudioOutput = MutableStateFlow("desktop")
    val activeAudioOutput: StateFlow<String> = _activeAudioOutput.asStateFlow()

    private val _mobileSession = MutableStateFlow<MobileSessionSnapshot?>(null)
    val mobileSession: StateFlow<MobileSessionSnapshot?> = _mobileSession.asStateFlow()

    private val pendingCommandsLock = Any()
    private val pendingMobileCommands = mutableListOf<PendingMobileCommandDto>()
    private var nextCommandId = 1L

    var nowPlayingProvider: (() -> DesktopNowPlayingInfo?)? = null
    var queueSnapshotProvider: (() -> DesktopQueueSnapshot?)? = null
    var lyricsSnapshotProvider: (() -> DesktopLyricsSnapshot?)? = null
    var onRemoteCommand: ((action: String, value: String?) -> Boolean)? = null
    var onStructuredRemoteCommand: ((SyncRemoteCommand) -> Boolean)? = null
    var onHandoffToDesktop: ((songs: List<SyncedSongDto>, startIndex: Int, positionMs: Long, isPlaying: Boolean, shuffle: Boolean?, repeatMode: Int?) -> Boolean)? = null
    var onPrepareHandoffToMobile: (() -> DesktopHandoffState?)? = null
    var onPauseDesktopPlayback: (() -> Unit)? = null
    var onSyncReceived: ((jsonPayload: String) -> Unit)? = null
    var librarySnapshotProvider: (() -> LibrarySyncData)? = null
    var onLibraryMergeReceived: ((LibrarySyncData) -> LibrarySyncData)? = null
    var onLocalMusicUpdated: (() -> Unit)? = null
    var onPlaylistsUpdated: (() -> Unit)? = null
    var localSongsProvider: (() -> List<Song>)? = null
    /** Validates whether USB loopback auto-pairing is allowed (active Sync + LuminaChord open on phone). */
    var usbTunnelValidator: (() -> Boolean) = { AdbSyncManager.hasActiveAppTunnel() }

    fun init(savedPersistence: DesktopPersistence) {
        persistence = savedPersistence
        val enabled = savedPersistence.boolean(KEY_SYNC_SERVER_ENABLED, true)
        val savedPort = savedPersistence.int(KEY_SYNC_PORT, 8688)
        val savedCode = savedPersistence.string(KEY_SYNC_PAIRING_CODE, "").ifBlank {
            generateRandomPin().also { savedPersistence.saveString(KEY_SYNC_PAIRING_CODE, it) }
        }
        val requireCode = savedPersistence.boolean(KEY_SYNC_REQUIRE_CODE, true)
        val beacon = savedPersistence.boolean(KEY_SYNC_BEACON_ENABLED, true)
        val prefIp = savedPersistence.string(KEY_SYNC_PREFERRED_IP, "").ifBlank { null }

        _isEnabled.value = enabled
        _port.value = if (savedPort in 1024..65535) savedPort else 8688
        _pairingCode.value = savedCode
        _requirePairingCode.value = requireCode
        _autoDiscoverBeacon.value = beacon
        _preferredIp.value = prefIp

        if (enabled) {
            startServer()
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        persistence?.saveBoolean(KEY_SYNC_SERVER_ENABLED, enabled)
        if (enabled) {
            startServer()
        } else {
            stopServer()
        }
    }

    fun setPort(newPort: Int) {
        if (newPort !in 1024..65535) return
        val changed = _port.value != newPort
        _port.value = newPort
        persistence?.saveInt(KEY_SYNC_PORT, newPort)
        if (changed && _isEnabled.value) {
            restartServer()
        }
    }

    fun setRequirePairingCode(require: Boolean) {
        _requirePairingCode.value = require
        persistence?.saveBoolean(KEY_SYNC_REQUIRE_CODE, require)
    }

    fun setAutoDiscoverBeacon(enabled: Boolean) {
        _autoDiscoverBeacon.value = enabled
        persistence?.saveBoolean(KEY_SYNC_BEACON_ENABLED, enabled)
        if (enabled && _isRunning.value) {
            startBeaconBroadcast()
        } else {
            beaconJob?.cancel()
            beaconJob = null
        }
    }

    fun setPreferredIp(ip: String?) {
        val clean = ip?.trim()?.ifBlank { null }
        _preferredIp.value = clean
        persistence?.saveString(KEY_SYNC_PREFERRED_IP, clean.orEmpty())
    }

    fun refreshPairingCode() {
        val newCode = generateRandomPin()
        _pairingCode.value = newCode
        persistence?.saveString(KEY_SYNC_PAIRING_CODE, newCode)
        _lastActionMessage.value = "PIN baru di-generate: $newCode"
    }

    fun setCustomPairingCode(code: String) {
        val clean = code.filter(Char::isDigit).take(6)
        _pairingCode.value = clean
        persistence?.saveString(KEY_SYNC_PAIRING_CODE, clean)
    }

    fun disconnectClient(id: String) {
        _connectedClients.value = _connectedClients.value.filterNot { it.id == id }
        if (_connectedClients.value.isEmpty()) {
            _activeAudioOutput.value = "desktop"
            _mobileSession.value = null
            synchronized(pendingCommandsLock) { pendingMobileCommands.clear() }
        }
        _lastActionMessage.value = "Perangkat mobile telah diputuskan"
    }

    fun disconnectAllClients() {
        _connectedClients.value = emptyList()
        _activeAudioOutput.value = "desktop"
        _mobileSession.value = null
        synchronized(pendingCommandsLock) { pendingMobileCommands.clear() }
        _lastActionMessage.value = "Semua perangkat mobile telah diputuskan"
    }

    fun setActiveOutputLocal(target: String) {
        _activeAudioOutput.value = target
    }

    /** Ensures paired Mobile playback is paused & switched to Desktop mode whenever Desktop starts playing locally. */
    fun notifyDesktopPlaybackStarted() {
        val wasMobile = _activeAudioOutput.value == "mobile"
        _activeAudioOutput.value = "desktop"
        if (_connectedClients.value.isNotEmpty() && (wasMobile || _mobileSession.value?.isPlaying == true)) {
            sendCommandToMobile(action = "pause_for_desktop_handoff")
        }
    }

    fun songToRemoteDto(song: Song): SyncedSongDto =
        song.toRemoteDto(getPrimaryIpAddress(), _port.value)

    /** Queues a playback/control command to be executed by the connected Android Mobile app. */
    fun sendCommandToMobile(
        action: String,
        value: String? = null,
        index: Int? = null,
        from: Int? = null,
        to: Int? = null,
        positionMs: Long? = null,
        isPlaying: Boolean? = null,
        shuffle: Boolean? = null,
        repeatMode: Int? = null,
        playNext: Boolean? = null,
        song: SyncedSongDto? = null,
        songs: List<SyncedSongDto>? = null,
    ) {
        if (_connectedClients.value.isEmpty()) return
        synchronized(pendingCommandsLock) {
            // Coalesce rapid volume/seek commands so Mobile only applies the latest target.
            if (action == "seek" || action == "volume") {
                pendingMobileCommands.removeAll { it.action == action }
            }
            pendingMobileCommands.add(
                PendingMobileCommandDto(
                    id = nextCommandId++,
                    action = action,
                    value = value,
                    index = index,
                    from = from,
                    to = to,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    playNext = playNext,
                    song = song,
                    songs = songs,
                ),
            )
            while (pendingMobileCommands.size > 32) {
                pendingMobileCommands.removeAt(0)
            }
        }
    }

    /** Switches audio output from Windows Desktop to the connected Android Mobile device, handing off playback if Desktop is active or attaching cleanly when Mobile is already playing. */
    fun switchOutputToMobile(handoffIfDesktopPlaying: Boolean = true): Boolean {
        if (_connectedClients.value.isEmpty()) return false
        val primaryIp = getPrimaryIpAddress()
        val desktopPlayingNow = nowPlayingProvider?.invoke()?.isPlaying == true
        val mobile = _mobileSession.value
        val shouldHandoff = handoffIfDesktopPlaying && (
            desktopPlayingNow || mobile == null || mobile.title.isBlank()
            )
        _activeAudioOutput.value = "mobile"
        val snap = if (shouldHandoff) onPrepareHandoffToMobile?.invoke() else null
        if (!shouldHandoff) {
            onPauseDesktopPlayback?.invoke()
        }
        if (snap != null && snap.songs.isNotEmpty() && (snap.isPlaying || mobile == null || mobile.title.isBlank())) {
            val dtoSongs = snap.songs.map { it.toRemoteDto(primaryIp, _port.value) }
            sendCommandToMobile(
                action = "handoff_play",
                index = snap.queueIndex,
                positionMs = snap.positionMs,
                isPlaying = snap.isPlaying,
                shuffle = snap.shuffle,
                repeatMode = snap.repeatMode,
                songs = dtoSongs,
            )
            _lastActionMessage.value = "Audio dipindahkan ke HP Android"
        } else {
            sendCommandToMobile(action = "select_mobile_output")
            _lastActionMessage.value = "Output audio diatur ke HP Android"
        }
        return true
    }

    /** Pulls playback back from Android Mobile onto Windows Desktop at the latest known position & queue. */
    fun pullPlaybackFromMobileToDesktop(): Boolean {
        _activeAudioOutput.value = "desktop"
        val mobile = _mobileSession.value
        if (mobile != null && (mobile.songs.isNotEmpty() || mobile.title.isNotBlank())) {
            val songs = if (mobile.songs.isNotEmpty()) {
                mobile.songs
            } else {
                listOf(
                    SyncedSongDto(
                        videoId = mobile.videoId.ifBlank { "remote:${mobile.title}" },
                        title = mobile.title,
                        artist = mobile.artist,
                        thumbnailUrl = mobile.thumbnailUrl,
                        albumName = mobile.album,
                    ),
                )
            }
            sendCommandToMobile(action = "pause_for_desktop_handoff")
            onHandoffToDesktop?.invoke(
                songs,
                mobile.queueIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0)),
                mobile.estimatedPositionMs(),
                mobile.isPlaying,
                mobile.shuffle,
                mobile.repeatMode,
            )
            _lastActionMessage.value = "Audio diambil alih kembali ke Desktop Windows"
            return true
        }
        sendCommandToMobile(action = "select_desktop_output")
        return true
    }

    private val virtualInterfaceMarkers = listOf(
        "vethernet", "virtual", "vmware", "vbox", "virtualbox", "wsl", "hyper-v",
        "docker", "tailscale", "zerotier", "radmin", "hamachi", "openvpn", "wireguard",
        "wintun", "tap", "tun", "loopback", "bluetooth", "wi-fi direct", "teredo", "isatap",
    )

    fun getNetworkCandidates(): List<NetworkCandidate> {
        val candidates = mutableListOf<NetworkCandidate>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val label = "${iface.name} ${iface.displayName}".lowercase(Locale.ROOT)
                val isVirtualOrTunnel = virtualInterfaceMarkers.any { it in label }

                for (ifAddr in iface.interfaceAddresses) {
                    val addr = ifAddr.address
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("169.254.") || ip == "0.0.0.0") continue

                    var score = 0
                    if (addr.isSiteLocalAddress) score += 100
                    if ("wi-fi" in label || "wifi" in label || "wlan" in label || "wireless" in label) score += 80
                    if ("ethernet" in label || label.startsWith("eth")) score += 60
                    if (ip.startsWith("192.168.")) score += 40
                    else if (ip.startsWith("10.")) score += 20
                    else if (ip.startsWith("172.")) score += 10
                    if (ip.startsWith("192.168.1.") || ip.startsWith("192.168.0.")) score += 15
                    if (isVirtualOrTunnel) score -= 200

                    candidates.add(
                        NetworkCandidate(
                            ip = ip,
                            displayName = iface.displayName.takeIf { it.isNotBlank() } ?: iface.name,
                            broadcastIp = ifAddr.broadcast?.hostAddress,
                            isVirtualOrTunnel = isVirtualOrTunnel,
                            score = score,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return candidates.sortedByDescending { it.score }
    }

    fun getLocalIpAddresses(): List<String> {
        val candidates = getNetworkCandidates()
        val realOnes = candidates.filterNot { it.isVirtualOrTunnel }.map { it.ip }
        val result = if (realOnes.isNotEmpty()) realOnes else candidates.map { it.ip }
        return result.ifEmpty { listOf("127.0.0.1") }
    }

    fun getPrimaryIpAddress(): String {
        val available = getLocalIpAddresses()
        val preferred = _preferredIp.value
        if (preferred != null && preferred in available) return preferred
        return available.firstOrNull() ?: "127.0.0.1"
    }

    fun getPairingQrPayload(): String {
        val ip = getPrimaryIpAddress()
        val ips = getLocalIpAddresses().distinct().joinToString(",")
        val p = _port.value
        val code = _pairingCode.value
        val encodedIps = runCatching { URLEncoder.encode(ips, StandardCharsets.UTF_8.name()) }.getOrDefault(ip)
        return "luminachord://sync?ip=$ip&ips=$encodedIps&port=$p&pin=$code&name=LuminaChord+Windows"
    }

    private fun generateRandomPin(): String = "%04d".format(Random.nextInt(1000, 9999))

    @Synchronized
    fun startServer() {
        if (_isRunning.value) return
        val currentPort = _port.value
        serverJob = scope.launch {
            try {
                val ss = ServerSocket(currentPort, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                _isRunning.value = true
                _lastActionMessage.value = "Server aktif di port $currentPort (${getPrimaryIpAddress()})"
                startBeaconBroadcast()

                while (isActive && !ss.isClosed) {
                    try {
                        val clientSocket = ss.accept()
                        scope.launch { handleClientSocket(clientSocket) }
                    } catch (_: Exception) {
                        if (!ss.isClosed) {
                            delay(500)
                        }
                    }
                }
            } catch (e: Exception) {
                _isRunning.value = false
                _lastActionMessage.value = "Gagal memulai server di port $currentPort: ${e.message}"
            } finally {
                _isRunning.value = false
            }
        }
    }

    @Synchronized
    fun stopServer() {
        beaconJob?.cancel()
        beaconJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        _isRunning.value = false
        _lastActionMessage.value = "Server dinonaktifkan"
    }

    fun restartServer() {
        stopServer()
        scope.launch {
            delay(200)
            startServer()
        }
    }

    private fun startBeaconBroadcast() {
        beaconJob?.cancel()
        if (!_autoDiscoverBeacon.value) return
        beaconJob = scope.launch {
            while (isActive && _isRunning.value) {
                try {
                    val primaryIp = getPrimaryIpAddress()
                    val candidates = getNetworkCandidates().filterNot { it.isVirtualOrTunnel }
                    val ips = candidates.map { it.ip }.ifEmpty { getLocalIpAddresses() }
                    val port = _port.value
                    val dto = BeaconPayloadDto(
                        deviceName = "LuminaChord Desktop (Windows)",
                        ip = primaryIp,
                        ips = ips,
                        port = port,
                        code = _pairingCode.value,
                        requirePairingCode = _requirePairingCode.value,
                    )
                    val beaconPayload = "LUMINACHORD_DESKTOP_BEACON|" + json.encodeToString(dto)
                    val bytes = beaconPayload.toByteArray(StandardCharsets.UTF_8)

                    val targets = if (candidates.isNotEmpty()) candidates else getNetworkCandidates()
                    if (targets.isEmpty()) {
                        sendUdpPacket(null, "255.255.255.255", port, bytes)
                    } else {
                        for (candidate in targets) {
                            candidate.broadcastIp?.let { bcast ->
                                sendUdpPacket(candidate.ip, bcast, port, bytes)
                            }
                            sendUdpPacket(candidate.ip, "255.255.255.255", port, bytes)
                        }
                    }
                } catch (_: Exception) {
                }
                delay(2000)
            }
        }
    }

    private fun sendUdpPacket(localBindIp: String?, targetIp: String, port: Int, bytes: ByteArray) {
        runCatching {
            val socket = if (localBindIp != null) {
                DatagramSocket(0, InetAddress.getByName(localBindIp))
            } else {
                DatagramSocket()
            }
            socket.use { s ->
                s.broadcast = true
                val dest = InetAddress.getByName(targetIp)
                s.send(DatagramPacket(bytes, bytes.size, dest, port))
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.ROOT)
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")

            var contentLength = 0L
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colonIdx + 1).trim()
                    if (key == "content-length") {
                        contentLength = value.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    }
                }
            }

            val clientIp = s.inetAddress?.hostAddress ?: "Unknown"

            if (method == "POST" && path == "/api/sync/local-music/upload") {
                handlePostLocalMusicUpload(s, input, output, fullPath, contentLength, clientIp)
                return
            }

            val bodyBytes = if (contentLength in 1..16_777_216L) {
                readExactBytes(input, contentLength.toInt())
            } else {
                ByteArray(0)
            }
            val body = String(bodyBytes, StandardCharsets.UTF_8)

            when {
                method == "OPTIONS" -> {
                    sendHttpResponse(output, 204, "No Content", "application/json", "")
                }
                method == "GET" && path == "/api/status" -> {
                    handleGetStatus(output, clientIp, fullPath)
                }
                method == "GET" && path == "/api/artwork" -> {
                    handleGetArtwork(output, fullPath)
                }
                method == "POST" && path == "/api/pair" -> {
                    handlePostPair(output, clientIp, body)
                }
                method == "POST" && path == "/api/remote" -> {
                    handlePostRemote(output, clientIp, body)
                }
                method == "POST" && path == "/api/mobile/state" -> {
                    handlePostMobileState(output, clientIp, body)
                }
                method == "GET" && path == "/api/sync" -> {
                    handleGetSync(output)
                }
                method == "POST" && path == "/api/sync" -> {
                    handlePostSync(output, clientIp, body)
                }
                method == "GET" && path == "/api/sync/local-music" -> {
                    handleGetLocalMusic(output)
                }
                method == "GET" && path == "/api/sync/local-music/file" -> {
                    handleGetLocalMusicFile(s, output, fullPath)
                }
                method == "POST" && path == "/api/sync/local-music/merge" -> {
                    handlePostLocalMusicMerge(output, clientIp, body)
                }
                else -> {
                    sendHttpResponse(
                        output,
                        404,
                        "Not Found",
                        "application/json",
                        """{"status":"error","message":"Endpoint $path tidak ditemukan"}""",
                    )
                }
            }
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
            if (baos.size() > 65_536) break
            b = input.read()
        }
        return baos.toString(StandardCharsets.UTF_8)
    }

    private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) buffer else buffer.copyOf(offset)
    }

    private fun isAllowedArtworkOrAudioFile(file: File, allowAudio: Boolean): Boolean {
        val canonical = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return false
        val cacheDir = runCatching { DesktopLocalArtwork.cacheDirectory.toFile().canonicalFile.toPath() }.getOrNull()
        if (cacheDir != null && canonical.startsWith(cacheDir)) return true

        val musicFolder = runCatching { DesktopLocalMusic.folder()?.toFile()?.canonicalFile?.toPath() }.getOrNull()
        val defaultMusic = runCatching { Paths.get(System.getProperty("user.home"), "Music").toFile().canonicalFile.toPath() }.getOrNull()
        val defaultDownloads = runCatching { Paths.get(System.getProperty("user.home"), "Downloads").toFile().canonicalFile.toPath() }.getOrNull()
        val appDownloads = runCatching { com.music.bitchord.desktop.DesktopDownloadManager.directory().toFile().canonicalFile.toPath() }.getOrNull()

        if ((musicFolder != null && canonical.startsWith(musicFolder)) ||
            (defaultMusic != null && canonical.startsWith(defaultMusic)) ||
            (defaultDownloads != null && canonical.startsWith(defaultDownloads)) ||
            (appDownloads != null && canonical.startsWith(appDownloads))
        ) {
            return true
        }

        if (allowAudio) {
            val scanned = localSongsProvider?.invoke() ?: DesktopLocalMusic.scan()
            if (scanned.any { it.localPath != null && runCatching { File(it.localPath!!).canonicalFile.toPath() == canonical }.getOrDefault(false) }) {
                return true
            }
        }
        return false
    }

    private fun handleGetArtwork(output: BufferedOutputStream, fullPath: String) {
        val encodedPath = queryParam(fullPath, "path").orEmpty()
        val filePath = runCatching { URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.name()) }.getOrNull()
        val file = filePath?.let { File(it) }
        if (file != null && file.isFile && file.canRead() && isAllowedArtworkOrAudioFile(file, allowAudio = false)) {
            val bytes = file.readBytes()
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
            output.write(header.toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes)
            output.flush()
        } else {
            val header = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }

    private fun resolveArtworkUrl(raw: String?, primaryIp: String): String? = raw?.let { url ->
        if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            val encoded = runCatching { URLEncoder.encode(url, StandardCharsets.UTF_8.name()) }.getOrDefault(url)
            "http://$primaryIp:${_port.value}/api/artwork?path=$encoded"
        }
    }

    private fun handleGetStatus(output: BufferedOutputStream, clientIp: String, fullPath: String = "/api/status") {
        val primaryIp = getPrimaryIpAddress()
        val requestedQRev = queryParam(fullPath, "qRev")?.toLongOrNull() ?: -1L
        val requestedLRev = queryParam(fullPath, "lRev")?.toLongOrNull() ?: -1L
        val ackId = queryParam(fullPath, "ack")?.toLongOrNull() ?: 0L

        if (ackId > 0L) {
            synchronized(pendingCommandsLock) {
                pendingMobileCommands.removeAll { it.id <= ackId }
            }
        }

        val nowPlaying = nowPlayingProvider?.invoke()
        val nowPlayingDto = nowPlaying?.let { np ->
            NowPlayingDto(
                title = np.title,
                artist = np.artist,
                album = np.album,
                durationMs = np.durationMs,
                positionMs = np.positionMs,
                isPlaying = np.isPlaying,
                thumbnailUrl = resolveArtworkUrl(np.thumbnailUrl, primaryIp),
                volume = np.volume,
                videoId = np.videoId,
                durationText = np.durationText,
                isLoading = np.isLoading,
                artistId = np.artistId,
                albumId = np.albumId,
                isVideo = np.isVideo,
                isLiked = np.isLiked,
                shuffle = np.shuffle,
                repeatMode = np.repeatMode,
            )
        }

        val queueSnap = queueSnapshotProvider?.invoke()
        val currentQueueVer = queueSnap?.version ?: 0L
        val queueDto = if (queueSnap != null && requestedQRev != currentQueueVer) {
            QueueSnapshotDto(
                version = queueSnap.version,
                queueIndex = queueSnap.queueIndex,
                hasPrevious = queueSnap.hasPrevious,
                hasNext = queueSnap.hasNext,
                songs = queueSnap.songs.map { it.toRemoteDto(primaryIp, _port.value) },
            )
        } else {
            null
        }

        val lyricsSnap = lyricsSnapshotProvider?.invoke()
        val currentLyricsVer = lyricsSnap?.version ?: 0L
        val lyricsDto = if (lyricsSnap != null && requestedLRev != currentLyricsVer) {
            LyricsSnapshotDto(
                version = lyricsSnap.version,
                videoId = lyricsSnap.videoId,
                source = lyricsSnap.source,
                loading = lyricsSnap.loading,
                unavailable = lyricsSnap.unavailable,
                lines = lyricsSnap.lines.map { it.toDto() },
            )
        } else {
            null
        }

        val commandsCopy = synchronized(pendingCommandsLock) { pendingMobileCommands.toList() }

        _connectedClients.value = _connectedClients.value.map {
            if (it.ip == clientIp) it.copy(lastSeen = System.currentTimeMillis()) else it
        }

        val count = currentLocalSongs().size
        val dto = StatusResponseDto(
            status = "ok",
            app = "LuminaChord",
            version = "1.10",
            platform = "Windows",
            deviceName = "LuminaChord Desktop (Windows)",
            serverTime = System.currentTimeMillis(),
            primaryIp = primaryIp,
            allIps = getLocalIpAddresses(),
            port = _port.value,
            requirePairingCode = _requirePairingCode.value,
            connectedDevicesCount = _connectedClients.value.size,
            localMusicCount = count,
            activeOutput = _activeAudioOutput.value,
            queueVersion = currentQueueVer,
            lyricsVersion = currentLyricsVer,
            nowPlaying = nowPlayingDto,
            queue = queueDto,
            lyrics = lyricsDto,
            pendingCommands = commandsCopy,
        )

        sendHttpResponse(output, 200, "OK", "application/json", json.encodeToString(dto))
    }

    private fun registerOrTouchClient(clientIp: String, deviceName: String) {
        val now = System.currentTimeMillis()
        val existing = _connectedClients.value.firstOrNull { it.ip == clientIp }
        if (existing == null) {
            _connectedClients.value = _connectedClients.value + ConnectedMobileClient(
                id = UUID.randomUUID().toString(),
                deviceName = deviceName,
                ip = clientIp,
                pairedAt = now,
                lastSeen = now,
            )
        } else {
            _connectedClients.value = _connectedClients.value.map {
                if (it.ip == clientIp) it.copy(deviceName = deviceName, lastSeen = now) else it
            }
        }
    }

    private fun handlePostPair(output: BufferedOutputStream, clientIp: String, body: String) {
        val req = runCatching { json.decodeFromString<PairRequestDto>(body) }.getOrNull() ?: PairRequestDto()
        val codeReceived = req.code.trim()
        val deviceName = req.device.ifBlank { "Android Device" }
        val isLoopback = clientIp == "127.0.0.1" || clientIp == "::1" || clientIp == "0:0:0:0:0:0:0:1"
        val trustedUsbTunnel = isLoopback &&
            req.transport.equals("usb", ignoreCase = true) &&
            _isEnabled.value &&
            _isRunning.value &&
            usbTunnelValidator.invoke()

        if (_requirePairingCode.value && !trustedUsbTunnel && codeReceived != _pairingCode.value) {
            val errJson = """{"status":"error","message":"Kode pairing (PIN) salah! Periksa PIN di LuminaChord Desktop"}"""
            sendHttpResponse(output, 403, "Forbidden", "application/json", errJson)
            _lastActionMessage.value = "Percobaan koneksi ditolak dari $deviceName ($clientIp): PIN tidak valid"
            return
        }

        registerOrTouchClient(clientIp, deviceName)
        val displayEndpoint = if (trustedUsbTunnel) "USB Terhubung" else clientIp
        _lastActionMessage.value = "Terhubung dengan $deviceName ($displayEndpoint)"

        val now = System.currentTimeMillis()
        val resJson = """{"status":"success","deviceName":"LuminaChord Desktop (Windows)","message":"Berhasil terhubung dengan LuminaChord Desktop","serverTime":$now}"""
        sendHttpResponse(output, 200, "OK", "application/json", resJson)
    }

    private fun handlePostRemote(output: BufferedOutputStream, clientIp: String, body: String) {
        val req = runCatching { json.decodeFromString<RemoteCommandDto>(body) }.getOrNull() ?: RemoteCommandDto()
        val actionLower = req.action.lowercase(Locale.ROOT)
        val primaryIp = getPrimaryIpAddress()

        when (actionLower) {
            "prepare_handoff_to_mobile" -> {
                _activeAudioOutput.value = "mobile"
                val snap = onPrepareHandoffToMobile?.invoke()
                val res = if (snap != null) {
                    HandoffResponseDto(
                        status = "ok",
                        queueIndex = snap.queueIndex,
                        positionMs = snap.positionMs,
                        isPlaying = snap.isPlaying,
                        shuffle = snap.shuffle,
                        repeatMode = snap.repeatMode,
                        songs = snap.songs.map { it.toRemoteDto(primaryIp, _port.value) },
                    )
                } else {
                    HandoffResponseDto(status = "empty")
                }
                _lastActionMessage.value = "Handoff audio ke HP Android ($clientIp)"
                sendHttpResponse(output, 200, "OK", "application/json", json.encodeToString(res))
                return
            }
            "handoff_to_desktop" -> {
                val transferSongs = req.songs?.takeIf { it.isNotEmpty() } ?: listOfNotNull(req.song)
                val ok = if (transferSongs.isNotEmpty()) {
                    onHandoffToDesktop?.invoke(
                        transferSongs,
                        (req.index ?: 0).coerceIn(0, (transferSongs.size - 1).coerceAtLeast(0)),
                        req.positionMs?.coerceAtLeast(0L) ?: 0L,
                        req.isPlaying ?: true,
                        req.shuffle,
                        req.repeatMode,
                    ) ?: false
                } else {
                    false
                }
                _activeAudioOutput.value = "desktop"
                val status = if (ok) "ok" else "ignored"
                _lastActionMessage.value = "Audio dipindahkan ke Desktop dari $clientIp"
                sendHttpResponse(output, 200, "OK", "application/json", """{"status":"$status","action":"handoff_to_desktop"}""")
                return
            }
            "select_desktop_output" -> {
                _activeAudioOutput.value = "desktop"
                sendHttpResponse(output, 200, "OK", "application/json", """{"status":"ok","action":"select_desktop_output"}""")
                return
            }
            "select_mobile_output" -> {
                _activeAudioOutput.value = "mobile"
                sendHttpResponse(output, 200, "OK", "application/json", """{"status":"ok","action":"select_mobile_output"}""")
                return
            }
        }

        val structured = SyncRemoteCommand(
            action = req.action,
            value = req.value,
            index = req.index,
            from = req.from,
            to = req.to,
            positionMs = req.positionMs,
            isPlaying = req.isPlaying,
            shuffle = req.shuffle,
            repeatMode = req.repeatMode,
            playNext = req.playNext,
            song = req.song,
            songs = req.songs,
        )
        val handled = (onStructuredRemoteCommand?.invoke(structured) == true) ||
            (onRemoteCommand?.invoke(req.action, req.value) == true)
        val status = if (handled) "ok" else "ignored"
        _lastActionMessage.value = "Remote command: ${req.action} ($status) dari $clientIp"

        val resJson = """{"status":"$status","action":${json.encodeToString(req.action)}}"""
        sendHttpResponse(output, 200, "OK", "application/json", resJson)
    }

    private fun handlePostMobileState(output: BufferedOutputStream, clientIp: String, body: String) {
        val req = runCatching { json.decodeFromString<MobileStatePushDto>(body) }.getOrNull()
        if (req != null) {
            registerOrTouchClient(clientIp, req.deviceName.ifBlank { "Android Device" })
            val prev = _mobileSession.value
            val resolvedLyrics = req.lyrics?.toDomain()
                ?: prev?.lyrics?.takeIf { prev.videoId == req.videoId && req.videoId.isNotBlank() }

            // Auto-follow Mobile when Android starts playing while Desktop is not actively playing locally.
            if (req.isPlaying && req.title.isNotBlank() && _activeAudioOutput.value != "mobile") {
                val desktopPlaying = nowPlayingProvider?.invoke()?.isPlaying == true
                if (!desktopPlaying) {
                    _activeAudioOutput.value = "mobile"
                    onPauseDesktopPlayback?.invoke()
                }
            } else if (req.isPlaying && _activeAudioOutput.value == "mobile") {
                if (nowPlayingProvider?.invoke()?.isPlaying == true) {
                    onPauseDesktopPlayback?.invoke()
                }
            }

            _mobileSession.value = MobileSessionSnapshot(
                deviceName = req.deviceName.ifBlank { "Android Device" },
                videoId = req.videoId,
                title = req.title,
                artist = req.artist,
                album = req.album,
                thumbnailUrl = req.thumbnailUrl,
                durationMs = req.durationMs,
                positionMs = req.positionMs,
                isPlaying = req.isPlaying,
                volume = req.volume,
                shuffle = req.shuffle,
                repeatMode = req.repeatMode,
                queueIndex = req.queueIndex,
                songs = req.songs,
                lyrics = resolvedLyrics,
                receivedAtMs = System.currentTimeMillis(),
            )
        }
        sendHttpResponse(output, 200, "OK", "application/json", """{"status":"ok","activeOutput":${json.encodeToString(_activeAudioOutput.value)}}""")
    }

    private fun handleGetSync(output: BufferedOutputStream) {
        val snapshot = librarySnapshotProvider?.invoke() ?: LibrarySyncData()
        val now = System.currentTimeMillis()
        val dto = SyncResponseDto(
            status = "ok",
            timestamp = _lastSyncTime.value ?: now,
            message = "Desktop library siap disinkronkan",
            mergedLikedCount = snapshot.likedIds.size,
            mergedPlaylistsCount = snapshot.playlists.size,
            mergedHistoryCount = snapshot.history.size,
            likedIds = snapshot.likedIds.toList(),
            playlists = snapshot.playlists.map { it.toDto() },
            history = snapshot.history.map { it.toDto() },
        )
        sendHttpResponse(output, 200, "OK", "application/json", json.encodeToString(dto))
    }

    private fun handlePostSync(output: BufferedOutputStream, clientIp: String, body: String) {
        val req = runCatching { json.decodeFromString<SyncExchangeDto>(body) }.getOrNull() ?: SyncExchangeDto()
        registerOrTouchClient(clientIp, req.device.ifBlank { "Android Device" })
        onSyncReceived?.invoke(body)

        val incoming = LibrarySyncData(
            likedIds = if (req.syncFavorites) req.likedIds.filter(String::isNotBlank).toSet() else emptySet(),
            playlists = if (req.syncPlaylists) req.playlists.map { it.toDesktopPlaylist() } else emptyList(),
            history = if (req.syncHistory) req.history.map { it.toSong() } else emptyList(),
        )

        val merged = onLibraryMergeReceived?.invoke(incoming)
            ?: librarySnapshotProvider?.invoke()
            ?: incoming

        val now = System.currentTimeMillis()
        _lastSyncTime.value = now
        _lastActionMessage.value = "Sinkronisasi selesai dengan ${req.device} (${merged.playlists.size} playlist, ${merged.likedIds.size} favorit)"

        val response = SyncResponseDto(
            status = "synced",
            timestamp = now,
            message = "Sinkronisasi dua arah berhasil (${merged.likedIds.size} favorit, ${merged.playlists.size} playlist, ${merged.history.size} riwayat)",
            mergedLikedCount = merged.likedIds.size,
            mergedPlaylistsCount = merged.playlists.size,
            mergedHistoryCount = merged.history.size,
            likedIds = if (req.syncFavorites) merged.likedIds.toList() else emptyList(),
            playlists = if (req.syncPlaylists) merged.playlists.map { it.toDto() } else emptyList(),
            history = if (req.syncHistory) merged.history.map { it.toDto() } else emptyList(),
        )
        sendHttpResponse(output, 200, "OK", "application/json", json.encodeToString(response))
    }

    private fun currentLocalSongs(): List<Song> = localSongsProvider?.invoke()?.takeIf { it.isNotEmpty() } ?: DesktopLocalMusic.scan()

    private fun Song.toLocalDto(): LocalSongDto {
        val f = localPath?.let { File(it) }
        return LocalSongDto(
            id = videoId,
            title = title,
            artist = artist,
            album = albumName.orEmpty(),
            duration = durationText.orEmpty(),
            fileName = f?.name.orEmpty(),
            sizeBytes = f?.takeIf { it.isFile }?.length() ?: 0L,
            path = localPath.orEmpty(),
        )
    }

    private fun handleGetLocalMusic(output: BufferedOutputStream) {
        val songs = currentLocalSongs()
        val folder = DesktopLocalMusic.folder()?.toString() ?: Paths.get(System.getProperty("user.home"), "Music").toString()
        val dto = LocalMusicListResponseDto(
            status = "ok",
            folder = folder,
            count = songs.size,
            songs = songs.map { it.toLocalDto() },
        )
        sendHttpResponse(output, 200, "OK", "application/json", json.encodeToString(dto))
    }

    private fun handleGetLocalMusicFile(socket: Socket, output: BufferedOutputStream, fullPath: String) {
        val encodedPath = queryParam(fullPath, "path").orEmpty()
        val filePath = runCatching { URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.name()) }.getOrNull()
        val file = filePath?.let { File(it) }
        if (file != null && file.isFile && file.canRead() && isAllowedArtworkOrAudioFile(file, allowAudio = true)) {
            socket.soTimeout = 120_000
            val size = file.length()
            val ext = file.extension.lowercase(Locale.ROOT)
            val mime = when (ext) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "ogg" -> "audio/ogg"
                "m4a", "aac" -> "audio/mp4"
                "wav" -> "audio/wav"
                "opus" -> "audio/opus"
                else -> "application/octet-stream"
            }
            val safeName = file.name.replace("\"", "_")
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: $size\r\n" +
                "Content-Disposition: attachment; filename=\"$safeName\"\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
            output.write(header.toByteArray(StandardCharsets.US_ASCII))
            FileInputStream(file).use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = fis.read(buf)
                    if (read == -1) break
                    output.write(buf, 0, read)
                }
            }
            output.flush()
        } else {
            val header = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }

    private fun handlePostLocalMusicUpload(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        fullPath: String,
        contentLength: Long,
        clientIp: String,
    ) {
        socket.soTimeout = 120_000
        val rawName = queryParam(fullPath, "filename")?.let {
            runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull()
        }.orEmpty()
        val sanitized = rawName
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .trim('.')
            .ifBlank { "mobile_track_${System.currentTimeMillis()}.mp3" }

        if (contentLength <= 0L || contentLength > 512L * 1024L * 1024L) {
            sendHttpResponse(output, 400, "Bad Request", "application/json", """{"status":"error","message":"Ukuran file tidak valid"}""")
            return
        }

        val targetDir: Path = DesktopLocalMusic.folder()
            ?: Paths.get(System.getProperty("user.home"), "Music", "LuminaChord")
        runCatching { Files.createDirectories(targetDir) }

        val targetPath = targetDir.resolve(sanitized)
        val tempPath = targetDir.resolve(".$sanitized.part")

        val receivedOk = runCatching {
            var remaining = contentLength
            val buf = ByteArray(64 * 1024)
            FileOutputStream(tempPath.toFile()).use { fos ->
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = input.read(buf, 0, toRead)
                    if (read == -1) break
                    fos.write(buf, 0, read)
                    remaining -= read
                }
            }
            if (remaining == 0L) {
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                true
            } else {
                runCatching { Files.deleteIfExists(tempPath) }
                false
            }
        }.getOrElse {
            runCatching { Files.deleteIfExists(tempPath) }
            false
        }

        if (receivedOk) {
            _lastSyncTime.value = System.currentTimeMillis()
            _lastActionMessage.value = "Lagu '$sanitized' diterima dari HP ($clientIp)"
            onLocalMusicUpdated?.invoke()
            sendHttpResponse(
                output,
                200,
                "OK",
                "application/json",
                """{"status":"ok","fileName":${json.encodeToString(sanitized)},"message":"File berhasil diterima desktop"}""",
            )
        } else {
            sendHttpResponse(output, 500, "Internal Server Error", "application/json", """{"status":"error","message":"Gagal menyimpan file"}""")
        }
    }

    private fun normalizeKey(title: String, artist: String, fileName: String): String {
        val cleanFile = fileName.substringBeforeLast('.').lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val cleanTitle = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val cleanArtist = artist.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        return if (cleanTitle.isNotBlank()) "$cleanTitle|$cleanArtist" else cleanFile
    }

    private fun handlePostLocalMusicMerge(output: BufferedOutputStream, clientIp: String, body: String) {
        val req = runCatching { json.decodeFromString<LocalMergeRequestDto>(body) }.getOrNull() ?: LocalMergeRequestDto()
        val desktopSongs = currentLocalSongs()

        val mobileKeys = req.mobileSongs.mapTo(HashSet()) { m ->
            normalizeKey(m.title, m.artist, m.fileName)
        }
        val mobileFiles = req.mobileSongs.mapTo(HashSet()) { it.fileName.lowercase(Locale.ROOT) }

        val missingOnMobile = desktopSongs.filter { s ->
            val dto = s.toLocalDto()
            val k = normalizeKey(dto.title, dto.artist, dto.fileName)
            val fileMatch = dto.fileName.isNotBlank() && dto.fileName.lowercase(Locale.ROOT) in mobileFiles
            val metaMatch = k in mobileKeys
            val titleOnlyMatch = req.mobileSongs.any { m ->
                m.fileName.substringBeforeLast('.').equals(dto.fileName.substringBeforeLast('.'), ignoreCase = true) ||
                    (m.title.isNotBlank() && m.title.equals(dto.title, ignoreCase = true) && (m.artist.isBlank() || m.artist.equals(dto.artist, ignoreCase = true)))
            }
            !(fileMatch || metaMatch || titleOnlyMatch)
        }.map { it.toLocalDto() }

        val desktopKeys = desktopSongs.mapTo(HashSet()) { s ->
            val dto = s.toLocalDto()
            normalizeKey(dto.title, dto.artist, dto.fileName)
        }
        val desktopFiles = desktopSongs.mapTo(HashSet()) { it.toLocalDto().fileName.lowercase(Locale.ROOT) }
        val missingOnDesktopCount = req.mobileSongs.count { m ->
            val k = normalizeKey(m.title, m.artist, m.fileName)
            val fMatch = m.fileName.isNotBlank() && m.fileName.lowercase(Locale.ROOT) in desktopFiles
            !(fMatch || k in desktopKeys)
        }

        _lastActionMessage.value = "Merge musik lokal ($clientIp): ${missingOnMobile.size} perlu ke HP, $missingOnDesktopCount dari HP"
        val res = LocalMergeResponseDto(
            status = "ok",
            desktopCount = desktopSongs.size,
            mobileCount = req.mobileCount.coerceAtLeast(req.mobileSongs.size),
            missingOnMobileCount = missingOnMobile.size,
            missingOnDesktopCount = missingOnDesktopCount,
            missingOnMobile = missingOnMobile,
            message = "Analisis merge selesai: ${missingOnMobile.size} lagu desktop belum ada di HP, $missingOnDesktopCount lagu HP belum ada di PC",
        )
        sendHttpResponse(output, 200, "OK", "application/json", json.encodeToString(res))
    }

    /** Checks whether LuminaChord already has Windows Firewall inbound Allow rules. */
    fun checkFirewallStatus(): String {
        return runCatching {
            val p = ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=LuminaChord Sync")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (out.contains("LuminaChord Sync", ignoreCase = true) && out.contains("Allow", ignoreCase = true)) {
                return "Port ${_port.value} (LuminaChord Sync) sudah diizinkan di Windows Firewall"
            }
            val p2 = ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=luminachord.exe")
                .redirectErrorStream(true)
                .start()
            val out2 = p2.inputStream.bufferedReader().readText()
            p2.waitFor()
            if (out2.contains("Allow", ignoreCase = true)) {
                "luminachord.exe sudah diizinkan di Windows Firewall"
            } else {
                "Port ${_port.value} belum punya aturan Allow khusus di Firewall"
            }
        }.getOrDefault("Status Firewall tidak diketahui")
    }

    /** Requests a single Windows UAC prompt to allow inbound TCP/UDP traffic on the sync port. */
    fun openWindowsFirewallPort(): Boolean {
        val currentPort = _port.value
        val netshScript = "netsh advfirewall firewall delete rule name=\"LuminaChord Sync\" & " +
            "netsh advfirewall firewall add rule name=\"LuminaChord Sync\" dir=in action=allow protocol=TCP localport=$currentPort profile=any & " +
            "netsh advfirewall firewall add rule name=\"LuminaChord Sync\" dir=in action=allow protocol=UDP localport=$currentPort profile=any"
        return runCatching {
            val ps = ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-Command",
                "Start-Process cmd -ArgumentList '/c ${netshScript.replace("'", "''")}' -Verb RunAs -Wait",
            ).start()
            val exit = ps.waitFor()
            if (exit == 0) {
                _lastActionMessage.value = "Firewall Windows port $currentPort berhasil dibuka untuk Private & Public!"
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun queryParam(fullPath: String, key: String): String? {
        val query = fullPath.substringAfter("?", "")
        if (query.isEmpty()) return null
        return query.split("&")
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("$key=")
    }

    private fun sendHttpResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        content: String,
    ) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType; charset=UTF-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun DesktopPlaylist.toDto(): SyncedPlaylistDto = SyncedPlaylistDto(
        id = id,
        title = title,
        songs = songs.map { it.toDto() },
        kind = DesktopPlaylistStore.kindFor(id),
    )

    private fun SyncedPlaylistDto.toDesktopPlaylist(): DesktopPlaylist {
        val resolvedId = id.ifBlank { UUID.randomUUID().toString() }
        DesktopPlaylistStore.rememberKind(resolvedId, kind)
        var lazyLocalSongs: List<Song>? = null
        fun localCatalog(): List<Song> {
            val cached = lazyLocalSongs
            if (cached != null) return cached
            return currentLocalSongs().also { lazyLocalSongs = it }
        }
        return DesktopPlaylist(
            id = resolvedId,
            title = title.ifBlank { "Synced Playlist" },
            songs = songs.map { dto ->
                val base = dto.toSong()
                val rawPath = dto.localPath?.takeIf { it.isNotBlank() }
                val existsOnPc = rawPath != null && runCatching { Files.isRegularFile(Path.of(rawPath)) }.getOrDefault(false)
                if (existsOnPc) {
                    base
                } else if (dto.videoId.startsWith("local:") || rawPath != null || !dto.fileName.isNullOrBlank()) {
                    val wantedName = dto.fileName?.takeIf { it.isNotBlank() }
                        ?: rawPath?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
                    val matched = localCatalog().firstOrNull { candidate ->
                        val candName = candidate.localPath?.let { File(it).name }
                        (wantedName != null && candName != null && candName.equals(wantedName, ignoreCase = true)) ||
                            (candidate.title.equals(dto.title, ignoreCase = true) &&
                                (dto.artist.isBlank() || candidate.artist.equals(dto.artist, ignoreCase = true)))
                    }
                    if (matched != null) {
                        base.copy(
                            videoId = if (base.videoId.isBlank() || base.videoId.startsWith("local:")) matched.videoId else base.videoId,
                            localPath = matched.localPath,
                            thumbnailUrl = base.thumbnailUrl ?: matched.thumbnailUrl,
                        )
                    } else {
                        base
                    }
                } else {
                    base
                }
            },
        )
    }

    private fun Song.toRemoteDto(primaryIp: String, port: Int): SyncedSongDto {
        val thumb = thumbnailUrl?.let { raw ->
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                raw
            } else {
                val encoded = runCatching { URLEncoder.encode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
                "http://$primaryIp:$port/api/artwork?path=$encoded"
            }
        }
        return SyncedSongDto(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumb,
            durationText = durationText,
            artistId = artistId,
            albumId = albumId,
            albumName = albumName,
            isVideo = isVideo,
            localPath = localPath,
        )
    }

    private fun LyricsSnapshotDto.toDomain(): DesktopLyricsSnapshot = DesktopLyricsSnapshot(
        version = version,
        videoId = videoId,
        source = source,
        loading = loading,
        unavailable = unavailable,
        lines = lines.map { it.toDomain() },
    )

    private fun LyricWordDto.toDomain(): LyricWord = LyricWord(
        startMs = startMs,
        endMs = endMs,
        text = text,
        syllables = syllables.map { LyricSyllable(it.startMs, it.endMs, it.startChar, it.endChar) },
    )

    private fun LyricLineDto.toDomain(): LyricLine = LyricLine(
        timeMs = timeMs,
        text = text,
        words = words.map { it.toDomain() },
        sungUntilMs = sungUntilMs,
        alignment = if (alignment.equals("End", ignoreCase = true)) LyricAlignment.End else LyricAlignment.Start,
        background = background?.let { bg ->
            LyricLine(
                timeMs = bg.timeMs,
                text = bg.text,
                words = bg.words.map { it.toDomain() },
                sungUntilMs = bg.sungUntilMs,
                alignment = if (bg.alignment.equals("End", ignoreCase = true)) LyricAlignment.End else LyricAlignment.Start,
            )
        },
    )

    private fun LyricWord.toDto(): LyricWordDto = LyricWordDto(
        startMs = startMs,
        endMs = endMs,
        text = text,
        syllables = syllables.map { LyricSyllableDto(it.startMs, it.endMs, it.startChar, it.endChar) },
    )

    private fun LyricLine.toDto(): LyricLineDto = LyricLineDto(
        timeMs = timeMs,
        text = text,
        words = words.map { it.toDto() },
        sungUntilMs = sungUntilMs,
        alignment = alignment.name,
        background = background?.let { bg ->
            BackgroundLyricLineDto(
                timeMs = bg.timeMs,
                text = bg.text,
                words = bg.words.map { it.toDto() },
                sungUntilMs = bg.sungUntilMs,
                alignment = bg.alignment.name,
            )
        },
    )

    private fun Song.toDto(): SyncedSongDto = SyncedSongDto(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl?.takeIf { it.startsWith("http") },
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        localPath = localPath,
        fileName = localPath?.takeIf { it.isNotBlank() }?.let { runCatching { File(it).name }.getOrNull() },
    )

    private fun SyncedSongDto.toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        localPath = localPath,
    )

    fun formatLastSynced(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "Belum pernah"
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

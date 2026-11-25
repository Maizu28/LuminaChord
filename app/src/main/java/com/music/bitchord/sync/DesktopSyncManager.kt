package com.music.bitchord.sync

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.snapshots.Snapshot
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricSyllable
import com.music.bitchord.data.lyrics.LyricWord
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface DesktopSyncStatus {
    data object Disconnected : DesktopSyncStatus
    data object Searching : DesktopSyncStatus
    data object Connecting : DesktopSyncStatus
    data class Connected(val deviceName: String, val host: String, val port: Int) : DesktopSyncStatus
}

data class DesktopNowPlayingTrack(
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
) {
    fun toSong(): Song = Song(
        videoId = videoId.ifBlank { "desktop:$title|$artist" },
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = album,
        isVideo = isVideo,
    )
}

data class DesktopQueueState(
    val version: Long = 0L,
    val queueIndex: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val songs: List<Song> = emptyList(),
)

data class DesktopLyricsState(
    val version: Long = 0L,
    val videoId: String = "",
    val source: LyricsSource? = null,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
)

data class MobilePlaybackSnapshot(
    val song: Song?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
    val shuffle: Boolean,
    val repeatMode: Int,
    val queue: List<Song>,
    val queueIndex: Int,
)

interface MobilePlaybackBridge {
    suspend fun currentPlaybackSnapshot(): MobilePlaybackSnapshot?
    fun pauseLocalPlayback()
    fun applyHandoffFromDesktop(
        songs: List<Song>,
        startIndex: Int,
        positionMs: Long,
        isPlaying: Boolean,
        shuffle: Boolean?,
        repeatMode: Int?,
    )
    fun applyRemoteCommandFromDesktop(
        action: String,
        value: String? = null,
        positionMs: Long? = null,
    )
}

/**
 * Manages real connection, two-way synchronization, local music merging, and remote playback control
 * between LuminaChord Mobile (Android) and LuminaChord Desktop (Windows) over Wi-Fi LAN or USB ADB.
 */
object DesktopSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var pollingJob: Job? = null

    private val _status = MutableStateFlow<DesktopSyncStatus>(DesktopSyncStatus.Disconnected)
    val status: StateFlow<DesktopSyncStatus> = _status.asStateFlow()

    private val _hostIp = MutableStateFlow("192.168.1.100")
    val hostIp: StateFlow<String> = _hostIp.asStateFlow()

    private val _port = MutableStateFlow(8688)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _autoDiscover = MutableStateFlow(true)
    val autoDiscover: StateFlow<Boolean> = _autoDiscover.asStateFlow()

    private val _syncPlaylists = MutableStateFlow(true)
    val syncPlaylists: StateFlow<Boolean> = _syncPlaylists.asStateFlow()

    private val _syncFavorites = MutableStateFlow(true)
    val syncFavorites: StateFlow<Boolean> = _syncFavorites.asStateFlow()

    private val _syncHistory = MutableStateFlow(true)
    val syncHistory: StateFlow<Boolean> = _syncHistory.asStateFlow()

    private val _shareNowPlaying = MutableStateFlow(true)
    val shareNowPlaying: StateFlow<Boolean> = _shareNowPlaying.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _transferProgress = MutableStateFlow<String?>(null)
    val transferProgress: StateFlow<String?> = _transferProgress.asStateFlow()

    private val _lastSyncedTime = MutableStateFlow<Long?>(null)
    val lastSyncedTime: StateFlow<Long?> = _lastSyncedTime.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _desktopNowPlaying = MutableStateFlow<DesktopNowPlayingTrack?>(null)
    val desktopNowPlaying: StateFlow<DesktopNowPlayingTrack?> = _desktopNowPlaying.asStateFlow()

    private val _desktopQueue = MutableStateFlow(DesktopQueueState())
    val desktopQueue: StateFlow<DesktopQueueState> = _desktopQueue.asStateFlow()

    private val _desktopLyrics = MutableStateFlow(DesktopLyricsState())
    val desktopLyrics: StateFlow<DesktopLyricsState> = _desktopLyrics.asStateFlow()

    private val _desktopOutputActive = MutableStateFlow(false)
    val desktopOutputActive: StateFlow<Boolean> = _desktopOutputActive.asStateFlow()

    val desktopPosition: PlaybackPosition = PlaybackPosition()

    @Volatile
    var mobileBridge: MobilePlaybackBridge? = null

    @Volatile
    private var lastSeekSentElapsedMs: Long = 0L

    @Volatile
    private var lastReportedTrackKey: String? = null

    @Volatile
    private var lastReportedPlaying: Boolean = false

    @Volatile
    private var lastAckedCommandId: Long = 0L

    private val _desktopLocalMusicCount = MutableStateFlow<Int?>(null)
    val desktopLocalMusicCount: StateFlow<Int?> = _desktopLocalMusicCount.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (prefs != null) return
        val p = ctx.getSharedPreferences("luminachord_desktop_sync", Context.MODE_PRIVATE)
        prefs = p
        _hostIp.value = p.getString("host_ip", "192.168.1.100") ?: "192.168.1.100"
        _port.value = p.getInt("port", 8688)
        _pairingCode.value = p.getString("pairing_code", "") ?: ""
        _autoDiscover.value = p.getBoolean("auto_discover", true)
        _syncPlaylists.value = p.getBoolean("sync_playlists", true)
        _syncFavorites.value = p.getBoolean("sync_favorites", true)
        _syncHistory.value = p.getBoolean("sync_history", true)
        _shareNowPlaying.value = p.getBoolean("share_now_playing", true)
        val last = p.getLong("last_synced_time", -1L)
        _lastSyncedTime.value = if (last > 0) last else null

        val wasConnected = p.getBoolean("is_connected", false)
        if (wasConnected) {
            val savedIp = _hostIp.value
            val savedPort = _port.value
            val savedCode = _pairingCode.value
            scope.launch {
                val alive = probeLuminaDesktop(savedIp, savedPort, 1800)
                if (alive != null) {
                    pairOverHttp(savedIp, savedPort, savedCode)
                } else if (_autoDiscover.value) {
                    autoDiscoverInternal(autoTriggered = true)
                } else {
                    _status.value = DesktopSyncStatus.Disconnected
                }
            }
        }
    }

    fun setHostIp(ip: String) {
        val clean = ip.trim()
        _hostIp.value = clean
        prefs?.edit()?.putString("host_ip", clean)?.apply()
    }

    fun setPort(port: Int) {
        _port.value = port
        prefs?.edit()?.putInt("port", port)?.apply()
    }

    fun setPairingCode(code: String) {
        val clean = code.trim()
        _pairingCode.value = clean
        prefs?.edit()?.putString("pairing_code", clean)?.apply()
    }

    fun setAutoDiscover(enabled: Boolean) {
        _autoDiscover.value = enabled
        prefs?.edit()?.putBoolean("auto_discover", enabled)?.apply()
    }

    fun setSyncPlaylists(enabled: Boolean) {
        _syncPlaylists.value = enabled
        prefs?.edit()?.putBoolean("sync_playlists", enabled)?.apply()
    }

    fun setSyncFavorites(enabled: Boolean) {
        _syncFavorites.value = enabled
        prefs?.edit()?.putBoolean("sync_favorites", enabled)?.apply()
    }

    fun setSyncHistory(enabled: Boolean) {
        _syncHistory.value = enabled
        prefs?.edit()?.putBoolean("sync_history", enabled)?.apply()
    }

    fun setShareNowPlaying(enabled: Boolean) {
        _shareNowPlaying.value = enabled
        prefs?.edit()?.putBoolean("share_now_playing", enabled)?.apply()
    }

    fun connect(ip: String, port: Int, code: String) {
        connectCandidates(listOf(ip, "127.0.0.1"), port, code, primaryHint = ip)
    }

    fun connectUsbAdb(port: Int = _port.value, code: String = _pairingCode.value) {
        connectCandidates(listOf("127.0.0.1"), port, code, primaryHint = "127.0.0.1")
    }

    fun connectCandidates(
        candidates: List<String>,
        port: Int,
        code: String,
        primaryHint: String? = null,
    ) {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return
        val firstIp = primaryHint?.trim()?.takeIf { it.isNotBlank() } ?: cleaned.first()
        setHostIp(firstIp)
        setPort(port)
        setPairingCode(code)

        scope.launch {
            _status.value = DesktopSyncStatus.Connecting
            _syncMessage.value = "Menghubungi desktop di $firstIp:$port..."

            // First try resolving which candidate IP actually responds (helps on multi-NIC Windows or USB ADB)
            val reachableIp = if (cleaned.size > 1) {
                probeBestCandidate(cleaned, port) ?: firstIp
            } else {
                firstIp
            }

            if (reachableIp != firstIp) {
                setHostIp(reachableIp)
            }
            pairOverHttp(reachableIp, port, code)
        }
    }

    private suspend fun probeBestCandidate(ips: List<String>, port: Int): String? = coroutineScope {
        val jobs = ips.map { ip ->
            async(Dispatchers.IO) {
                if (probeLuminaDesktop(ip, port, 1600) != null) ip else null
            }
        }
        jobs.awaitAll().firstOrNull { it != null }
    }

    private fun pairOverHttp(ip: String, port: Int, code: String) {
        try {
            val url = URL("http://$ip:$port/api/pair")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val myDevice = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Device" }
            val payloadObj = JSONObject()
                .put("device", myDevice)
                .put("code", code)
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payloadObj.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val resJson = runCatching { JSONObject(responseText) }.getOrNull()
                val deviceName = resJson?.optString("deviceName")?.takeIf { it.isNotBlank() }
                    ?: "LuminaChord Desktop (Windows)"

                setHostIp(ip)
                setPort(port)
                setPairingCode(code)

                _status.value = DesktopSyncStatus.Connected(deviceName, ip, port)
                prefs?.edit()
                    ?.putBoolean("is_connected", true)
                    ?.putString("device_name", deviceName)
                    ?.apply()
                val viaUsb = if (ip == "127.0.0.1") " via USB ADB" else ""
                _syncMessage.value = "Berhasil terhubung dengan $deviceName$viaUsb ($ip:$port)!"
                startPolling(ip, port)
                fetchStatusOnce(ip, port)
                fetchDesktopLocalMusic()
            } else if (responseCode == 403) {
                _status.value = DesktopSyncStatus.Disconnected
                prefs?.edit()?.putBoolean("is_connected", false)?.apply()
                _syncMessage.value = "Kode pairing (PIN) salah! Periksa PIN di LuminaChord Windows PC."
            } else {
                _status.value = DesktopSyncStatus.Disconnected
                prefs?.edit()?.putBoolean("is_connected", false)?.apply()
                _syncMessage.value = "Server desktop merespons dengan kode error: HTTP $responseCode"
            }
        } catch (e: ConnectException) {
            _status.value = DesktopSyncStatus.Disconnected
            prefs?.edit()?.putBoolean("is_connected", false)?.apply()
            _syncMessage.value = "Koneksi ditolak di $ip:$port. Pastikan server LuminaChord Sync di Windows aktif dan Firewall tidak memblokir port $port."
        } catch (e: SocketTimeoutException) {
            _status.value = DesktopSyncStatus.Disconnected
            prefs?.edit()?.putBoolean("is_connected", false)?.apply()
            _syncMessage.value = "Timeout menghubungi $ip:$port. Pastikan PC & HP di Wi-Fi yang sama (atau colok kabel USB + ADB Reverse) & aktifkan izin Firewall di Windows."
        } catch (e: Exception) {
            _status.value = DesktopSyncStatus.Disconnected
            prefs?.edit()?.putBoolean("is_connected", false)?.apply()
            val detail = "${e.javaClass.simpleName}: ${e.message ?: "Error tidak diketahui"}"
            _syncMessage.value = "Gagal terhubung ke $ip:$port ($detail)"
        }
    }

    /**
     * Parses QR code content scanned from Windows Desktop and initiates immediate pairing.
     */
    fun parseAndConnectFromQr(qrContent: String): Boolean {
        val trimmed = qrContent.trim()
        try {
            if (trimmed.startsWith("luminachord://sync", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                val ip = uri.getQueryParameter("ip")
                val ipsParam = uri.getQueryParameter("ips")
                val portStr = uri.getQueryParameter("port")
                val pin = uri.getQueryParameter("pin") ?: uri.getQueryParameter("code") ?: ""
                val p = portStr?.toIntOrNull() ?: 8688

                val extraIps = ipsParam?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                val candidates = buildList {
                    if (!ip.isNullOrBlank()) add(ip.trim())
                    addAll(extraIps)
                    add("127.0.0.1")
                }.distinct()

                if (candidates.isNotEmpty() && (!ip.isNullOrBlank() || extraIps.isNotEmpty())) {
                    connectCandidates(candidates, p, pin, primaryHint = ip)
                    return true
                }
            } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val obj = JSONObject(trimmed)
                val ip = obj.optString("ip", "")
                val ipsArr = obj.optJSONArray("ips")
                val extraIps = if (ipsArr != null) {
                    (0 until ipsArr.length()).mapNotNull { i -> ipsArr.optString(i).takeIf { it.isNotBlank() } }
                } else emptyList()
                val p = obj.optInt("port", 8688)
                val pin = obj.optString("pin", obj.optString("code", ""))
                val candidates = (listOfNotNull(ip.takeIf { it.isNotBlank() }) + extraIps + "127.0.0.1").distinct()
                if (candidates.size > 1 || ip.isNotBlank()) {
                    connectCandidates(candidates, p, pin, primaryHint = ip.takeIf { it.isNotBlank() })
                    return true
                }
            } else if (trimmed.contains(":")) {
                val parts = trimmed.split(":")
                val ip = parts[0].trim()
                val p = parts.getOrNull(1)?.toIntOrNull() ?: 8688
                val pin = parts.getOrNull(2)?.trim() ?: ""
                if (ip.count { it == '.' } == 3) {
                    connect(ip, p, pin)
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun fetchDesktopLocalMusic() {
        val currentStatus = _status.value
        if (currentStatus !is DesktopSyncStatus.Connected) return
        scope.launch {
            try {
                val url = URL("http://${currentStatus.host}:${currentStatus.port}/api/sync/local-music")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val obj = JSONObject(text)
                    val count = obj.optInt("count", 0)
                    _desktopLocalMusicCount.value = count
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Truly compares Android local music against Desktop local music, downloads missing tracks from Desktop
     * into `/Music/LuminaChord/`, and optionally uploads unique Android tracks back to Desktop.
     */
    fun mergeLocalMusicWithDesktop(context: Context? = appContext, twoWay: Boolean = true) {
        val currentStatus = _status.value
        if (currentStatus !is DesktopSyncStatus.Connected) {
            _syncMessage.value = "Hubungkan ke PC Desktop terlebih dahulu untuk merge musik lokal."
            return
        }
        val ctx = context?.applicationContext ?: appContext ?: return
        if (_isSyncing.value) return

        scope.launch {
            _isSyncing.value = true
            _transferProgress.value = "Memindai pustaka musik lokal HP..."
            _syncMessage.value = "Menyelaraskan koleksi musik lokal antara HP dan Desktop..."

            try {
                val mobileSongs = LocalMediaRepository.getLocalMusic(ctx)
                val mobileMetaArr = JSONArray()
                val mobileFileByKey = HashMap<String, Song>()

                for (s in mobileSongs) {
                    val fileName = resolveFileName(ctx, s)
                    val metaObj = JSONObject()
                        .put("title", s.title)
                        .put("artist", s.artist)
                        .put("fileName", fileName)
                        .put("sizeBytes", 0L)
                    mobileMetaArr.put(metaObj)
                    mobileFileByKey[fileName.lowercase(Locale.ROOT)] = s
                    mobileFileByKey["${s.title.lowercase(Locale.ROOT)}|${s.artist.lowercase(Locale.ROOT)}"] = s
                }

                // Step 1: Analyze diff on Desktop
                val mergeUrl = URL("http://${currentStatus.host}:${currentStatus.port}/api/sync/local-music/merge")
                val mergeConn = (mergeUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val reqPayload = JSONObject()
                    .put("mobileCount", mobileSongs.size)
                    .put("mobileSongs", mobileMetaArr)
                OutputStreamWriter(mergeConn.outputStream, StandardCharsets.UTF_8).use { it.write(reqPayload.toString()) }

                if (mergeConn.responseCode != 200) {
                    _syncMessage.value = "Gagal menganalisis merge musik lokal (HTTP ${mergeConn.responseCode})"
                    return@launch
                }

                val mergeResText = mergeConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val mergeResJson = JSONObject(mergeResText)
                _desktopLocalMusicCount.value = mergeResJson.optInt("desktopCount", 0)

                val missingArr = mergeResJson.optJSONArray("missingOnMobile") ?: JSONArray()
                var downloaded = 0
                val totalToDownload = missingArr.length()

                // Step 2: Download missing Desktop tracks into Android Music/LuminaChord
                for (i in 0 until totalToDownload) {
                    val item = missingArr.optJSONObject(i) ?: continue
                    val remotePath = item.optString("path", "")
                    val fileName = item.optString("fileName", "").ifBlank {
                        val artist = item.optString("artist", "")
                        val title = item.optString("title", "track_$i")
                        val ext = remotePath.substringAfterLast('.', "mp3").ifBlank { "mp3" }
                        if (artist.isNotBlank()) "$artist - $title.$ext" else "$title.$ext"
                    }.replace(Regex("[\\\\/:*?\"<>|]"), "_")

                    if (remotePath.isBlank()) continue
                    _transferProgress.value = "Mengunduh dari PC (${i + 1}/$totalToDownload): $fileName"

                    if (downloadAudioFromDesktop(ctx, currentStatus.host, currentStatus.port, remotePath, fileName)) {
                        downloaded++
                    }
                }

                // Step 3: Optionally upload unique Android local songs to Desktop
                var uploaded = 0
                if (twoWay) {
                    val desktopListUrl = URL("http://${currentStatus.host}:${currentStatus.port}/api/sync/local-music")
                    val listConn = (desktopListUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 6000
                        readTimeout = 6000
                    }
                    if (listConn.responseCode == 200) {
                        val listJson = JSONObject(listConn.inputStream.bufferedReader().use { it.readText() })
                        val desktopArr = listJson.optJSONArray("songs") ?: JSONArray()
                        val desktopKeys = HashSet<String>()
                        val desktopFiles = HashSet<String>()
                        for (i in 0 until desktopArr.length()) {
                            val d = desktopArr.optJSONObject(i) ?: continue
                            val df = d.optString("fileName", "").lowercase(Locale.ROOT)
                            if (df.isNotBlank()) desktopFiles.add(df)
                            val dt = d.optString("title", "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                            val da = d.optString("artist", "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                            if (dt.isNotBlank()) desktopKeys.add("$dt|$da")
                        }

                        val toUpload = mobileSongs.filter { s ->
                            val fn = resolveFileName(ctx, s).lowercase(Locale.ROOT)
                            val k = "${s.title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")}|${s.artist.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")}"
                            fn !in desktopFiles && k !in desktopKeys
                        }

                        for ((idx, song) in toUpload.withIndex()) {
                            val fn = resolveFileName(ctx, song)
                            _transferProgress.value = "Mengirim ke PC (${idx + 1}/${toUpload.size}): $fn"
                            if (uploadAudioToDesktop(ctx, currentStatus.host, currentStatus.port, song, fn)) {
                                uploaded++
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                _lastSyncedTime.value = now
                prefs?.edit()?.putLong("last_synced_time", now)?.apply()
                _transferProgress.value = null
                _syncMessage.value = if (downloaded == 0 && uploaded == 0) {
                    "Koleksi musik lokal HP (${mobileSongs.size}) dan Desktop sudah sinkron!"
                } else {
                    "Merge selesai: $downloaded lagu diunduh ke HP, $uploaded lagu dikirim ke Desktop Windows!"
                }
                fetchDesktopLocalMusic()
            } catch (e: Exception) {
                _transferProgress.value = null
                _syncMessage.value = "Error saat merge musik lokal: ${e.message}"
            } finally {
                _isSyncing.value = false
                _transferProgress.value = null
            }
        }
    }

    private fun resolveFileName(context: Context, song: Song): String {
        song.localPath?.let { path ->
            val name = File(path).name
            if (name.isNotBlank()) return name
        }
        song.localUri?.let { uriStr ->
            runCatching {
                context.contentResolver.query(Uri.parse(uriStr), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val dn = c.getString(0)
                        if (!dn.isNullOrBlank()) return dn
                    }
                }
            }
        }
        val cleanArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleanArtist.isNotBlank()) "$cleanArtist - $cleanTitle.mp3" else "$cleanTitle.mp3"
    }

    private fun mimeForFileName(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "flac" -> "audio/flac"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/x-wav"
        else -> "audio/mpeg"
    }

    private fun downloadAudioFromDesktop(
        context: Context,
        host: String,
        port: Int,
        remotePath: String,
        fileName: String,
    ): Boolean {
        return runCatching {
            val encoded = URLEncoder.encode(remotePath, StandardCharsets.UTF_8.name())
            val url = URL("http://$host:$port/api/sync/local-music/file?path=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 120_000
            }
            if (conn.responseCode != 200) return false

            val mime = mimeForFileName(fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LuminaChord")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        conn.inputStream.use { inp -> inp.copyTo(out, 64 * 1024) }
                    } ?: run {
                        resolver.delete(itemUri, null, null)
                        return false
                    }
                    resolver.update(itemUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    true
                } catch (e: Exception) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "LuminaChord")
                if (!folder.exists()) folder.mkdirs()
                val target = File(folder, fileName)
                val part = File(folder, ".$fileName.part")
                FileOutputStream(part).use { out ->
                    conn.inputStream.use { inp -> inp.copyTo(out, 64 * 1024) }
                }
                if (part.renameTo(target)) {
                    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
                    true
                } else {
                    part.delete()
                    false
                }
            }
        }.getOrDefault(false)
    }

    private fun uploadAudioToDesktop(
        context: Context,
        host: String,
        port: Int,
        song: Song,
        fileName: String,
    ): Boolean {
        return runCatching {
            val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
            val url = URL("http://$host:$port/api/sync/local-music/upload?filename=$encodedName")
            val inputStream = when {
                !song.localUri.isNullOrBlank() -> context.contentResolver.openInputStream(Uri.parse(song.localUri))
                !song.localPath.isNullOrBlank() -> File(song.localPath!!).takeIf { it.isFile }?.inputStream()
                else -> null
            } ?: return false

            val size: Long = when {
                !song.localPath.isNullOrBlank() -> File(song.localPath!!).length()
                !song.localUri.isNullOrBlank() -> context.contentResolver.openAssetFileDescriptor(Uri.parse(song.localUri), "r")?.use { it.length } ?: -1L
                else -> -1L
            }

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 120_000
                doOutput = true
                if (size > 0) setFixedLengthStreamingMode(size) else setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            conn.outputStream.use { out ->
                inputStream.use { inp -> inp.copyTo(out, 64 * 1024) }
            }
            conn.responseCode == 200
        }.getOrDefault(false)
    }

    fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        _desktopOutputActive.value = false
        _desktopNowPlaying.value = null
        _desktopQueue.value = DesktopQueueState()
        _desktopLyrics.value = DesktopLyricsState()
        lastReportedTrackKey = null
        lastReportedPlaying = false
        lastAckedCommandId = 0L
        desktopPosition.report(0L, isPlaying = false)
        _status.value = DesktopSyncStatus.Disconnected
        prefs?.edit()?.putBoolean("is_connected", false)?.apply()
        _syncMessage.value = "Terputus dari desktop"
    }

    fun setDesktopOutputActive(active: Boolean) {
        val connected = _status.value is DesktopSyncStatus.Connected
        _desktopOutputActive.value = active && connected
        if (_desktopOutputActive.value) {
            val status = _status.value as? DesktopSyncStatus.Connected ?: return
            scope.launch { fetchStatusOnce(status.host, status.port) }
        }
    }

    /** Switches audio output to LuminaChord Desktop (Windows):
     * - If Phone is currently playing, pauses Phone and hands off queue + position to Desktop.
     * - If Phone is paused and Desktop already has an active track, keeps Desktop playing and routes Mobile controls/lyrics/queue to Desktop.
     */
    fun switchOutputToDesktop(handoffIfMobilePlaying: Boolean = true) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        scope.launch {
            val bridge = mobileBridge
            val mobileSnap = bridge?.currentPlaybackSnapshot()
            val desktopTrack = _desktopNowPlaying.value

            val shouldTransferMobile = handoffIfMobilePlaying && mobileSnap?.song != null && (
                mobileSnap.isPlaying || desktopTrack == null
            )

            if (shouldTransferMobile && mobileSnap?.song != null) {
                bridge.pauseLocalPlayback()
                val songsToTransfer = mobileSnap.queue.ifEmpty { listOf(mobileSnap.song) }
                val startIdx = mobileSnap.queueIndex.coerceIn(0, (songsToTransfer.size - 1).coerceAtLeast(0))
                val wasPlaying = mobileSnap.isPlaying
                val posMs = mobileSnap.positionMs.coerceAtLeast(0L)
                _desktopOutputActive.value = true
                Snapshot.withMutableSnapshot {
                    desktopPosition.seeks++
                }
                desktopPosition.report(posMs, isPlaying = wasPlaying)
                val payload = JSONObject()
                    .put("action", "handoff_to_desktop")
                    .put("index", startIdx)
                    .put("positionMs", posMs)
                    .put("isPlaying", wasPlaying)
                    .put("shuffle", mobileSnap.shuffle)
                    .put("repeatMode", mobileSnap.repeatMode)
                    .put("songs", JSONArray().apply {
                        songsToTransfer.forEach { put(songToJson(it)) }
                    })
                postRemotePayload(currentStatus.host, currentStatus.port, payload)
                fetchStatusOnce(currentStatus.host, currentStatus.port)
            } else {
                _desktopOutputActive.value = true
                postRemotePayload(
                    currentStatus.host,
                    currentStatus.port,
                    JSONObject().put("action", "select_desktop_output"),
                )
                fetchStatusOnce(currentStatus.host, currentStatus.port)
            }
        }
    }

    /** Switches audio output back to Mobile (Android), pulling Desktop's live track, queue, and exact playhead position. */
    fun switchOutputToMobile(handoffFromDesktop: Boolean = true) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected
        val bridge = mobileBridge
        _desktopOutputActive.value = false
        if (currentStatus == null) return

        scope.launch {
            if (!handoffFromDesktop || bridge == null) {
                postRemotePayload(
                    currentStatus.host,
                    currentStatus.port,
                    JSONObject().put("action", "select_mobile_output"),
                )
                return@launch
            }
            val res = postRemotePayload(
                currentStatus.host,
                currentStatus.port,
                JSONObject().put("action", "prepare_handoff_to_mobile"),
            )
            val obj = res?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj != null && obj.optString("status") == "ok") {
                val arr = obj.optJSONArray("songs") ?: JSONArray()
                val songs = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { jsonToRemoteSong(it, currentStatus.host, currentStatus.port) }
                }
                val startIdx = obj.optInt("queueIndex", 0)
                val posMs = obj.optLong("positionMs", 0L)
                val wasPlaying = obj.optBoolean("isPlaying", true)
                val shuffle = if (obj.has("shuffle")) obj.optBoolean("shuffle") else null
                val repeatMode = if (obj.has("repeatMode")) obj.optInt("repeatMode") else null
                if (songs.isNotEmpty()) {
                    bridge.applyHandoffFromDesktop(
                        songs = songs,
                        startIndex = startIdx.coerceIn(songs.indices),
                        positionMs = posMs.coerceAtLeast(0L),
                        isPlaying = wasPlaying,
                        shuffle = shuffle,
                        repeatMode = repeatMode,
                    )
                }
            }
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun autoDiscover() {
        scope.launch {
            autoDiscoverInternal(autoTriggered = false)
        }
    }

    @SuppressLint("WifiManagerLeak")
    private suspend fun autoDiscoverInternal(autoTriggered: Boolean) {
        _status.value = DesktopSyncStatus.Searching
        _syncMessage.value = "Mencari LuminaChord Desktop (Wi-Fi LAN & USB ADB)..."

        var discoveredIp: String? = null
        var discoveredPort = _port.value
        var discoveredCode = _pairingCode.value
        var discoveredName: String? = null
        var requiresPin = false

        // 1. Listen for UDP Broadcast Beacon with MulticastLock & SO_REUSEADDR
        val wifiMgr = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifiMgr?.createMulticastLock("LuminaChordSyncLock")?.apply { setReferenceCounted(true); acquire() } }.getOrNull()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(8688))
                soTimeout = 2400
            }
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
            if (text.startsWith("LUMINACHORD_DESKTOP_BEACON|")) {
                val jsonText = text.substringAfter("LUMINACHORD_DESKTOP_BEACON|")
                val obj = JSONObject(jsonText)
                val primary = obj.optString("ip", "")
                val ipsArr = obj.optJSONArray("ips")
                val candidates = mutableListOf<String>()
                if (primary.isNotBlank()) candidates.add(primary)
                if (ipsArr != null) {
                    for (i in 0 until ipsArr.length()) {
                        val s = ipsArr.optString(i, "")
                        if (s.isNotBlank()) candidates.add(s)
                    }
                }
                val senderIp = packet.address?.hostAddress
                if (!senderIp.isNullOrBlank()) candidates.add(senderIp)

                discoveredPort = obj.optInt("port", 8688)
                discoveredCode = obj.optString("code", _pairingCode.value)
                discoveredName = obj.optString("deviceName", "LuminaChord Desktop (Windows)")
                requiresPin = obj.optBoolean("requirePairingCode", false)

                val reachable = probeBestCandidate(candidates.distinct(), discoveredPort) ?: candidates.firstOrNull()
                if (!reachable.isNullOrBlank()) {
                    discoveredIp = reachable
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket?.close() }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }

        // 2. Priority direct probe: ADB USB loopback (127.0.0.1), saved IP, and Emulator host (10.0.2.2)
        if (discoveredIp == null) {
            val priorityTargets = listOf("127.0.0.1", _hostIp.value, "10.0.2.2")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            for (ip in priorityTargets) {
                val info = probeLuminaDesktop(ip, _port.value, 900)
                if (info != null) {
                    discoveredIp = ip
                    discoveredPort = _port.value
                    discoveredName = info.optString("deviceName", "LuminaChord Desktop (Windows)")
                    requiresPin = info.optBoolean("requirePairingCode", false)
                    break
                }
            }
        }

        // 3. Fast parallel /24 Wi-Fi subnet sweep (bypasses router AP isolation & UDP firewall filtering)
        if (discoveredIp == null) {
            val found = sweepLocalSubnets(_port.value)
            if (found != null) {
                discoveredIp = found.first
                discoveredPort = _port.value
                discoveredName = found.second.optString("deviceName", "LuminaChord Desktop (Windows)")
                requiresPin = found.second.optBoolean("requirePairingCode", false)
            }
        }

        if (discoveredIp != null) {
            setHostIp(discoveredIp)
            setPort(discoveredPort)
            if (discoveredCode.isNotBlank()) {
                setPairingCode(discoveredCode)
            }

            if (discoveredCode.isNotBlank() || !requiresPin) {
                _syncMessage.value = "Ditemukan ${discoveredName ?: "Desktop"} di $discoveredIp:$discoveredPort! Menghubungkan..."
                pairOverHttp(discoveredIp, discoveredPort, discoveredCode)
            } else {
                _status.value = DesktopSyncStatus.Disconnected
                _syncMessage.value = "Desktop ditemukan di $discoveredIp:$discoveredPort! Masukkan PIN pairing dari layar PC atau scan QR Code."
            }
        } else {
            _status.value = DesktopSyncStatus.Disconnected
            if (!autoTriggered) {
                _syncMessage.value = "Desktop tidak ditemukan otomatis. Pastikan aplikasi Desktop berjalan di Wi-Fi yang sama (atau colok USB + aktifkan USB Debugging), lalu coba Scan QR atau izinkan Firewall Windows."
            }
        }
    }

    private suspend fun sweepLocalSubnets(port: Int): Pair<String, JSONObject>? = coroutineScope {
        val localIps = getPhoneIpv4Addresses()
        val prefixes = localIps
            .filter { !it.startsWith("127.") && !it.startsWith("169.254.") }
            .map { it.substringBeforeLast('.') }
            .distinct()

        for (prefix in prefixes) {
            val candidates = (1..254).map { "$prefix.$it" }.filter { it !in localIps }
            for (batch in candidates.chunked(42)) {
                val results = batch.map { targetIp ->
                    async(Dispatchers.IO) {
                        if (isTcpPortOpen(targetIp, port, 320)) {
                            val json = probeLuminaDesktop(targetIp, port, 900)
                            if (json != null) targetIp to json else null
                        } else null
                    }
                }.awaitAll()
                val match = results.firstOrNull { it != null }
                if (match != null) return@coroutineScope match
            }
        }
        null
    }

    private fun getPhoneIpv4Addresses(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        }
        return out
    }

    private fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeLuminaDesktop(ip: String, port: Int, timeoutMs: Int): JSONObject? {
        return try {
            val url = URL("http://$ip:$port/api/status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val obj = JSONObject(text)
                if (obj.optString("app").equals("LuminaChord", ignoreCase = true)) obj else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Synchronizes Playlists, Liked/Favorite tracks, and History two-way with LuminaChord Desktop.
     */
    fun syncNow(context: Context? = appContext) {
        val currentStatus = _status.value
        if (currentStatus !is DesktopSyncStatus.Connected) {
            _syncMessage.value = "Tidak dapat melakukan sinkronisasi: Perangkat belum terhubung."
            return
        }
        val ctx = context?.applicationContext ?: appContext

        scope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Mengirim & menyinkronkan data dengan desktop..."

            try {
                if (ctx != null) {
                    LocalPlaylistStore.init(ctx)
                }

                // Collect Favorites
                val likedIdsList = buildList {
                    if (_syncFavorites.value) {
                        addAll(
                            LikeState.overrides.value
                                .filterValues { it == LikeStatus.LIKE }
                                .keys
                                .filter { it.isNotBlank() },
                        )
                    }
                }.distinct()

                // Collect Playlists from LocalPlaylistStore
                val playlistsArr = JSONArray()
                if (_syncPlaylists.value) {
                    for (pl in LocalPlaylistStore.playlists.value) {
                        val songsArr = JSONArray()
                        for (s in pl.songs) {
                            if (s.videoId.isBlank()) continue
                            songsArr.put(songToJson(s))
                        }
                        playlistsArr.put(
                            JSONObject()
                                .put("id", pl.id)
                                .put("title", pl.title)
                                .put("songs", songsArr),
                        )
                    }
                }

                val payload = JSONObject()
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    .put("syncPlaylists", _syncPlaylists.value)
                    .put("syncFavorites", _syncFavorites.value)
                    .put("syncHistory", _syncHistory.value)
                    .put("timestamp", System.currentTimeMillis())
                    .put("likedIds", JSONArray(likedIdsList))
                    .put("playlists", playlistsArr)
                    .put("history", JSONArray())

                val url = URL("http://${currentStatus.host}:${currentStatus.port}/api/sync")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload.toString()) }

                if (conn.responseCode == 200) {
                    val resText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val resJson = runCatching { JSONObject(resText) }.getOrNull()

                    if (resJson != null) {
                        if (_syncFavorites.value) {
                            val mergedLikesArr = resJson.optJSONArray("likedIds")
                            if (mergedLikesArr != null) {
                                val desktopLikes = HashSet<String>()
                                for (i in 0 until mergedLikesArr.length()) {
                                    val id = mergedLikesArr.optString(i, "")
                                    if (id.isNotBlank() && !id.startsWith("local:")) {
                                        desktopLikes.add(id)
                                    }
                                }
                                LikeState.seedLiked(desktopLikes)
                            }
                        }

                        if (_syncPlaylists.value) {
                            val plArr = resJson.optJSONArray("playlists")
                            if (plArr != null) {
                                val desktopPlaylists = mutableListOf<LocalPlaylist>()
                                for (i in 0 until plArr.length()) {
                                    val pObj = plArr.optJSONObject(i) ?: continue
                                    val id = pObj.optString("id", "").ifBlank { "sync_${System.currentTimeMillis()}_$i" }
                                    val title = pObj.optString("title", "").ifBlank { continue }
                                    val sArr = pObj.optJSONArray("songs") ?: JSONArray()
                                    val songs = (0 until sArr.length()).mapNotNull { j ->
                                        sArr.optJSONObject(j)?.let(::jsonToSong)
                                    }
                                    desktopPlaylists.add(LocalPlaylist(id = id, title = title, songs = songs))
                                }
                                LocalPlaylistStore.mergeFromSync(desktopPlaylists)
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    _lastSyncedTime.value = now
                    prefs?.edit()?.putLong("last_synced_time", now)?.apply()
                    _syncMessage.value = resJson?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "Sinkronisasi berhasil diselesaikan!"
                } else {
                    _syncMessage.value = "Gagal sinkronisasi: HTTP ${conn.responseCode}"
                }
            } catch (e: Exception) {
                _syncMessage.value = "Gagal melakukan sinkronisasi: ${e.message}"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun songToJson(s: Song): JSONObject = JSONObject().apply {
        put("videoId", s.videoId)
        put("title", s.title)
        put("artist", s.artist)
        put("thumbnailUrl", s.thumbnailUrl)
        put("durationText", s.durationText)
        put("artistId", s.artistId)
        put("albumId", s.albumId)
        put("albumName", s.albumName)
        put("isVideo", s.isVideo)
    }

    private fun jsonToSong(obj: JSONObject): Song? {
        val id = obj.optString("videoId", "")
        val title = obj.optString("title", "")
        if (id.isBlank() || title.isBlank()) return null
        return Song(
            videoId = id,
            title = title,
            artist = obj.optString("artist", ""),
            thumbnailUrl = obj.optString("thumbnailUrl", "").takeIf { it.isNotBlank() && it != "null" },
            durationText = obj.optString("durationText", "").takeIf { it.isNotBlank() && it != "null" },
            artistId = obj.optString("artistId", "").takeIf { it.isNotBlank() && it != "null" },
            albumId = obj.optString("albumId", "").takeIf { it.isNotBlank() && it != "null" },
            albumName = obj.optString("albumName", "").takeIf { it.isNotBlank() && it != "null" },
            isVideo = obj.optBoolean("isVideo", false),
        )
    }

    fun sendRemoteCommand(action: String, value: String? = null) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        scope.launch {
            val payload = JSONObject()
                .put("action", action)
                .put("value", value ?: "")
            postRemotePayload(currentStatus.host, currentStatus.port, payload)
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun sendSeek(positionMs: Long) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        lastSeekSentElapsedMs = SystemClock.elapsedRealtime()
        val currentlyPlaying = _desktopNowPlaying.value?.isPlaying == true
        Snapshot.withMutableSnapshot {
            desktopPosition.seeks++
        }
        desktopPosition.report(clamped, isPlaying = currentlyPlaying)
        scope.launch {
            val payload = JSONObject()
                .put("action", "seek")
                .put("positionMs", clamped)
                .put("value", clamped.toString())
            postRemotePayload(currentStatus.host, currentStatus.port, payload)
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun setDesktopVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _desktopNowPlaying.value = _desktopNowPlaying.value?.copy(volume = clamped)
        sendRemoteCommand("volume", clamped.toString())
    }

    fun jumpDesktopQueue(index: Int) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        scope.launch {
            val payload = JSONObject().put("action", "jump_queue").put("index", index)
            postRemotePayload(currentStatus.host, currentStatus.port, payload)
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun moveInDesktopQueue(from: Int, to: Int) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        if (from == to) return
        // Optimistic reorder in local UI state while server applies it cleanly
        val cur = _desktopQueue.value
        if (from in cur.songs.indices && to in cur.songs.indices) {
            val list = cur.songs.toMutableList()
            val item = list.removeAt(from)
            list.add(to, item)
            val newIdx = when {
                cur.queueIndex == from -> to
                from < cur.queueIndex && to >= cur.queueIndex -> cur.queueIndex - 1
                from > cur.queueIndex && to <= cur.queueIndex -> cur.queueIndex + 1
                else -> cur.queueIndex
            }
            _desktopQueue.value = cur.copy(songs = list, queueIndex = newIdx)
        }
        scope.launch {
            val payload = JSONObject().put("action", "move_queue").put("from", from).put("to", to)
            postRemotePayload(currentStatus.host, currentStatus.port, payload)
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun removeFromDesktopQueue(index: Int) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        scope.launch {
            val payload = JSONObject().put("action", "remove_queue").put("index", index)
            postRemotePayload(currentStatus.host, currentStatus.port, payload)
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun clearDesktopQueue() {
        sendRemoteCommand("clear_queue")
    }

    fun toggleShuffleOnDesktop() {
        sendRemoteCommand("toggle_shuffle")
    }

    fun cycleRepeatOnDesktop() {
        sendRemoteCommand("cycle_repeat")
    }

    fun toggleLikeOnDesktop(videoId: String) {
        sendRemoteCommand("toggle_like", videoId)
    }

    fun enqueueOnDesktop(song: Song, playNext: Boolean) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        scope.launch {
            val payload = JSONObject()
                .put("action", "enqueue_song")
                .put("playNext", playNext)
                .put("song", songToJson(song))
            postRemotePayload(currentStatus.host, currentStatus.port, payload)
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun playSongsOnDesktop(songs: List<Song>, startIndex: Int) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        if (songs.isEmpty()) return
        scope.launch {
            val payload = JSONObject()
                .put("action", "play_songs")
                .put("index", startIndex.coerceIn(songs.indices))
                .put("songs", JSONArray().apply { songs.forEach { put(songToJson(it)) } })
            postRemotePayload(currentStatus.host, currentStatus.port, payload)
            fetchStatusOnce(currentStatus.host, currentStatus.port)
        }
    }

    fun pushMobileSnapshot(snapshot: MobilePlaybackSnapshot) {
        val currentStatus = _status.value as? DesktopSyncStatus.Connected ?: return
        if (!_shareNowPlaying.value) return
        scope.launch {
            try {
                val url = URL("http://${currentStatus.host}:${currentStatus.port}/api/mobile/state")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2500
                    readTimeout = 2500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Device" }
                val payload = JSONObject()
                    .put("deviceName", deviceName)
                    .put("videoId", snapshot.song?.videoId.orEmpty())
                    .put("title", snapshot.song?.title.orEmpty())
                    .put("artist", snapshot.song?.artist.orEmpty())
                    .put("album", snapshot.song?.albumName ?: JSONObject.NULL)
                    .put("thumbnailUrl", snapshot.song?.thumbnailUrl ?: JSONObject.NULL)
                    .put("durationMs", snapshot.durationMs)
                    .put("positionMs", snapshot.positionMs)
                    .put("isPlaying", snapshot.isPlaying)
                    .put("volume", snapshot.volume.toDouble())
                    .put("shuffle", snapshot.shuffle)
                    .put("repeatMode", snapshot.repeatMode)
                    .put("queueIndex", snapshot.queueIndex)
                    .put("songs", JSONArray().apply { snapshot.queue.take(60).forEach { put(songToJson(it)) } })
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload.toString()) }
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    private fun postRemotePayload(host: String, port: Int, payload: JSONObject): String? {
        return try {
            val url = URL("http://$host:$port/api/remote")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3500
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload.toString()) }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun startPolling(host: String, port: Int) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var failCount = 0
            while (isActive && _status.value is DesktopSyncStatus.Connected) {
                val success = fetchStatusOnce(host, port)
                if (success) {
                    failCount = 0
                    if (!_desktopOutputActive.value && _shareNowPlaying.value) {
                        val snap = mobileBridge?.currentPlaybackSnapshot()
                        if (snap != null && snap.song != null) {
                            pushMobileSnapshot(snap)
                        }
                    }
                } else {
                    failCount++
                    if (failCount >= 6) {
                        _desktopOutputActive.value = false
                        _status.value = DesktopSyncStatus.Disconnected
                        _desktopNowPlaying.value = null
                        _desktopQueue.value = DesktopQueueState()
                        _desktopLyrics.value = DesktopLyricsState()
                        prefs?.edit()?.putBoolean("is_connected", false)?.apply()
                        _syncMessage.value = "Koneksi ke desktop terputus"
                        break
                    }
                }
                val fast = _desktopOutputActive.value || _desktopNowPlaying.value?.isPlaying == true
                delay(if (fast) 550L else 1800L)
            }
        }
    }

    private fun fetchStatusOnce(host: String, port: Int): Boolean {
        return try {
            val qRev = _desktopQueue.value.version
            val lRev = _desktopLyrics.value.version
            val ack = lastAckedCommandId
            val url = URL("http://$host:$port/api/status?qRev=$qRev&lRev=$lRev&ack=$ack")
            val startedElapsed = SystemClock.elapsedRealtime()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val rttMs = (SystemClock.elapsedRealtime() - startedElapsed).coerceIn(0L, 1200L)
                parseStatus(text, host, port, rttMs)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun rewriteDesktopArtworkUrl(raw: String?, connectedHost: String, connectedPort: Int): String? {
        var thumb = raw?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (connectedHost == "127.0.0.1" && thumb.contains("/api/artwork")) {
            val pathQuery = thumb.substringAfter("/api/artwork", "")
            thumb = "http://127.0.0.1:$connectedPort/api/artwork$pathQuery"
        }
        return thumb
    }

    private fun jsonToRemoteSong(obj: JSONObject, connectedHost: String, connectedPort: Int): Song? {
        val base = jsonToSong(obj) ?: return null
        val thumb = rewriteDesktopArtworkUrl(base.thumbnailUrl, connectedHost, connectedPort)
        val lp = obj.optString("localPath", "").takeIf { it.isNotBlank() && it != "null" }
        var resolvedLocalUri: String? = null
        if (base.videoId.startsWith("local:")) {
            val ctx = appContext
            val localMatches = if (ctx != null) runCatching {
                kotlinx.coroutines.runBlocking { LocalMediaRepository.getLocalMusic(ctx) }
            }.getOrNull().orEmpty() else emptyList()
            val fname = lp?.substringAfterLast('/')?.substringAfterLast('\\')
            val matched = localMatches.firstOrNull { candidate ->
                candidate.videoId == base.videoId ||
                    (!fname.isNullOrBlank() && candidate.title.equals(base.title, ignoreCase = true)) ||
                    (candidate.title.equals(base.title, ignoreCase = true) && (base.artist.isBlank() || candidate.artist.equals(base.artist, ignoreCase = true)))
            }
            if (matched != null) {
                return matched
            }
            if (!lp.isNullOrBlank()) {
                val encoded = runCatching { URLEncoder.encode(lp, StandardCharsets.UTF_8.name()) }.getOrDefault(lp)
                resolvedLocalUri = "http://$connectedHost:$connectedPort/api/sync/local-music/file?path=$encoded"
            }
        }
        return base.copy(thumbnailUrl = thumb, localUri = resolvedLocalUri)
    }

    private fun parseLyricWords(arr: JSONArray?): List<LyricWord> {
        if (arr == null || arr.length() == 0) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val sylArr = obj.optJSONArray("syllables")
            val syllables = if (sylArr != null && sylArr.length() > 0) {
                (0 until sylArr.length()).mapNotNull { j ->
                    val sObj = sylArr.optJSONObject(j) ?: return@mapNotNull null
                    LyricSyllable(
                        startMs = sObj.optLong("startMs", 0L),
                        endMs = sObj.optLong("endMs", 0L),
                        startChar = sObj.optInt("startChar", 0),
                        endChar = sObj.optInt("endChar", 0),
                    )
                }
            } else {
                emptyList()
            }
            LyricWord(
                startMs = obj.optLong("startMs", 0L),
                endMs = obj.optLong("endMs", 0L),
                text = obj.optString("text", ""),
                syllables = syllables,
            )
        }
    }

    private fun parseLyricLines(arr: JSONArray?): List<LyricLine> {
        if (arr == null || arr.length() == 0) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val alignment = if (obj.optString("alignment", "Start").equals("End", ignoreCase = true)) {
                LyricAlignment.End
            } else {
                LyricAlignment.Start
            }
            val bgObj = obj.optJSONObject("background")
            val bg = if (bgObj != null) {
                LyricLine(
                    timeMs = bgObj.optLong("timeMs", 0L),
                    text = bgObj.optString("text", ""),
                    words = parseLyricWords(bgObj.optJSONArray("words")),
                    sungUntilMs = if (bgObj.has("sungUntilMs") && !bgObj.isNull("sungUntilMs")) bgObj.optLong("sungUntilMs") else null,
                    alignment = if (bgObj.optString("alignment", "Start").equals("End", ignoreCase = true)) LyricAlignment.End else LyricAlignment.Start,
                )
            } else null
            LyricLine(
                timeMs = obj.optLong("timeMs", 0L),
                text = obj.optString("text", ""),
                words = parseLyricWords(obj.optJSONArray("words")),
                sungUntilMs = if (obj.has("sungUntilMs") && !obj.isNull("sungUntilMs")) obj.optLong("sungUntilMs") else null,
                background = bg,
                alignment = alignment,
            )
        }
    }

    private fun parseStatus(jsonStr: String, connectedHost: String, connectedPort: Int, rttMs: Long = 0L) {
        val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return
        val localCount = obj.optInt("localMusicCount", -1)
        if (localCount >= 0) {
            _desktopLocalMusicCount.value = localCount
        }

        // Process any pending commands pushed from Windows Desktop (handoff/remote controls)
        val cmdsArr = obj.optJSONArray("pendingCommands")
        if (cmdsArr != null && cmdsArr.length() > 0) {
            val bridge = mobileBridge
            var maxId = lastAckedCommandId
            for (i in 0 until cmdsArr.length()) {
                val cmdObj = cmdsArr.optJSONObject(i) ?: continue
                val id = cmdObj.optLong("id", 0L)
                val action = cmdObj.optString("action", "")
                val value = cmdObj.optString("value", "").takeIf { it.isNotBlank() && it != "null" }
                val posMs = if (cmdObj.has("positionMs") && !cmdObj.isNull("positionMs")) cmdObj.optLong("positionMs") else null
                if (id > maxId) maxId = id

                when (action.lowercase(Locale.ROOT)) {
                    "handoff_play" -> {
                        val arr = cmdObj.optJSONArray("songs") ?: JSONArray()
                        val songs = (0 until arr.length()).mapNotNull { j ->
                            arr.optJSONObject(j)?.let { jsonToRemoteSong(it, connectedHost, connectedPort) }
                        }
                        val idx = cmdObj.optInt("index", 0)
                        val startPos = posMs ?: 0L
                        val playNow = cmdObj.optBoolean("isPlaying", true)
                        val shuffle = if (cmdObj.has("shuffle") && !cmdObj.isNull("shuffle")) cmdObj.optBoolean("shuffle") else null
                        val repeat = if (cmdObj.has("repeatMode") && !cmdObj.isNull("repeatMode")) cmdObj.optInt("repeatMode") else null
                        _desktopOutputActive.value = false
                        if (songs.isNotEmpty()) {
                            bridge?.applyHandoffFromDesktop(songs, idx.coerceIn(songs.indices), startPos, playNow, shuffle, repeat)
                        }
                    }
                    "pause_for_desktop_handoff" -> {
                        bridge?.pauseLocalPlayback()
                        _desktopOutputActive.value = true
                    }
                    "select_desktop_output" -> {
                        _desktopOutputActive.value = true
                    }
                    "select_mobile_output" -> {
                        _desktopOutputActive.value = false
                    }
                    else -> {
                        bridge?.applyRemoteCommandFromDesktop(action, value, posMs)
                    }
                }
            }
            if (maxId > lastAckedCommandId) {
                lastAckedCommandId = maxId
            }
        }

        // Parse queue diff if included
        val qObj = obj.optJSONObject("queue")
        if (qObj != null) {
            val sArr = qObj.optJSONArray("songs") ?: JSONArray()
            val qSongs = (0 until sArr.length()).mapNotNull { i ->
                sArr.optJSONObject(i)?.let { jsonToRemoteSong(it, connectedHost, connectedPort) }
            }
            _desktopQueue.value = DesktopQueueState(
                version = qObj.optLong("version", _desktopQueue.value.version),
                queueIndex = qObj.optInt("queueIndex", 0),
                hasPrevious = qObj.optBoolean("hasPrevious", false),
                hasNext = qObj.optBoolean("hasNext", false),
                songs = qSongs,
            )
        }

        // Parse lyrics diff if included
        val lObj = obj.optJSONObject("lyrics")
        if (lObj != null) {
            val sourceLabel = lObj.optString("source", "").takeIf { it.isNotBlank() && it != "null" }
            val mappedSource = sourceLabel?.let { lbl ->
                LyricsSource.entries.firstOrNull {
                    it.label.equals(lbl, ignoreCase = true) || it.name.equals(lbl, ignoreCase = true)
                }
            }
            _desktopLyrics.value = DesktopLyricsState(
                version = lObj.optLong("version", _desktopLyrics.value.version),
                videoId = lObj.optString("videoId", ""),
                source = mappedSource,
                loading = lObj.optBoolean("loading", false),
                unavailable = lObj.optBoolean("unavailable", false),
                lines = parseLyricLines(lObj.optJSONArray("lines")),
            )
        }

        if (!_shareNowPlaying.value) {
            _desktopNowPlaying.value = null
            desktopPosition.report(0L, isPlaying = false)
            return
        }

        val np = obj.optJSONObject("nowPlaying")
        if (np == null) {
            _desktopNowPlaying.value = null
            desktopPosition.report(0L, isPlaying = false)
            return
        }

        val title = np.optString("title", "").takeIf { it.isNotBlank() } ?: return
        val artist = np.optString("artist", "")
        val album = np.optString("album", "").takeIf { it.isNotBlank() && it != "null" }
        val durationMs = np.optLong("durationMs", 0L)
        val rawPosMs = np.optLong("positionMs", 0L)
        val isPlaying = np.optBoolean("isPlaying", false)
        val isLoading = np.optBoolean("isLoading", false)
        val volume = np.optDouble("volume", 1.0).toFloat()
        val videoId = np.optString("videoId", "")
        val durationText = np.optString("durationText", "").takeIf { it.isNotBlank() && it != "null" }
        val artistId = np.optString("artistId", "").takeIf { it.isNotBlank() && it != "null" }
        val albumId = np.optString("albumId", "").takeIf { it.isNotBlank() && it != "null" }
        val isVideo = np.optBoolean("isVideo", false)
        val isLiked = np.optBoolean("isLiked", false)
        val shuffle = np.optBoolean("shuffle", false)
        val repeatMode = np.optInt("repeatMode", 0)
        val thumb = rewriteDesktopArtworkUrl(np.optString("thumbnailUrl", ""), connectedHost, connectedPort)

        val adjustedPos = if (isPlaying && !isLoading) {
            val comp = rawPosMs + (rttMs / 2L).coerceIn(0L, 400L)
            if (durationMs > 0L) comp.coerceAtMost(durationMs) else comp
        } else {
            rawPosMs
        }

        val inSeekGrace = (SystemClock.elapsedRealtime() - lastSeekSentElapsedMs) < 900L
        val trackKey = "$videoId|$title|$artist"
        if (!inSeekGrace) {
            val prevPos = desktopPosition.positionMs
            val jumped = trackKey != lastReportedTrackKey || kotlin.math.abs(adjustedPos - prevPos) > 1600L
            if (jumped) {
                Snapshot.withMutableSnapshot {
                    desktopPosition.seeks++
                }
            }
            lastReportedTrackKey = trackKey
            lastReportedPlaying = isPlaying
            desktopPosition.report(adjustedPos, isPlaying = isPlaying && !isLoading)
        }

        _desktopNowPlaying.value = DesktopNowPlayingTrack(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            positionMs = if (inSeekGrace) desktopPosition.positionMs else adjustedPos,
            isPlaying = isPlaying,
            thumbnailUrl = thumb,
            volume = volume,
            videoId = videoId,
            durationText = durationText,
            isLoading = isLoading,
            artistId = artistId,
            albumId = albumId,
            isVideo = isVideo,
            isLiked = isLiked,
            shuffle = shuffle,
            repeatMode = repeatMode,
        )
    }

    fun formatLastSynced(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "Never"
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

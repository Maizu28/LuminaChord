package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A playlist of files on this device, made in the Local Music folder's
 * Playlists tab — the offline player's own playlists, which never touch the
 * account. [LIKED_ID] is always there and always first.
 *
 * Tracks are held by [Song.localUri], the one thing about a file that the
 * folder, the player and a download all agree on. A track's row is kept whole
 * so the page can be drawn without listing the device first; whether the file
 * is still there is asked when the page is read (see
 * [LocalMediaRepository.present]), not by rewriting the record.
 */
data class OfflinePlaylist(
    val id: String,
    val title: String,
    /** In the order they were added; the newest like first for [LIKED_ID]. */
    val songs: List<Song>,
) {
    val browseId: String get() = OfflinePlaylists.PREFIX + id
    val isLiked: Boolean get() = id == OfflinePlaylists.LIKED_ID
}

/**
 * The store behind [OfflinePlaylist]: in memory as a [StateFlow], on disk as
 * one JSON document written off the main thread.
 *
 * Writes are conflated — ten likes in a row are ten state updates and at most
 * a couple of disk writes, each of the newest state — so nothing here ever
 * waits on the disk.
 */
object OfflinePlaylists {
    /**
     * Under `local:` like every other page whose tracks are on the device —
     * which keeps the download button off it — in a segment of its own.
     */
    const val PREFIX = "local:offline:"
    const val LIKED_ID = "liked"

    private const val PREF_NAME = "bitchord_offline_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"
    private const val KEY_FOLDER_MIGRATED = "playlists_folder_migrated_v1"

    private lateinit var prefs: SharedPreferences
    private var appContext: Context? = null
    private var overrideDirectory: File? = null
    private var overrideWriteAccess: Boolean? = null
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by [init] to the app's own wording for the built-in playlist. */
    private var likedTitle = "Liked songs"

    private val _playlists = MutableStateFlow(listOf(OfflinePlaylist(LIKED_ID, likedTitle, emptyList())))
    val playlists: StateFlow<List<OfflinePlaylist>> = _playlists.asStateFlow()

    private val _liked = MutableStateFlow<Set<String>>(emptySet())

    /** The [Song.localUri]s in [LIKED_ID], for a heart to be drawn without a scan. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    fun init(context: Context, likedTitle: String) {
        if (this::prefs.isInitialized) return
        this.likedTitle = likedTitle
        appContext = context.applicationContext
        prefs = appContext!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        synchronized(lock) { publish(loadFromSources()) }
        // Everything after what was just read, however soon it comes: a like
        // made before this collector is up is still the newest state when it is.
        val loaded = _playlists.value
        io.launch {
            playlists.filter { it !== loaded }.conflate().collect { persist(it) }
        }
    }

    internal fun initForTest(
        preferences: SharedPreferences,
        directory: File?,
        writeAccess: Boolean = directory != null,
        likedTitle: String = "Liked songs",
    ) {
        this.likedTitle = likedTitle
        appContext = null
        overrideDirectory = directory
        overrideWriteAccess = writeAccess
        prefs = preferences
        synchronized(lock) { publish(loadFromSources()) }
    }

    fun refreshFromStorage(context: Context? = appContext) {
        if (context != null) appContext = context.applicationContext
        if (!this::prefs.isInitialized && overrideWriteAccess == null) return
        synchronized(lock) { publish(loadFromSources()) }
    }

    private fun canWriteFolder(): Boolean {
        overrideWriteAccess?.let { return it }
        val ctx = appContext ?: return false
        return PlaylistStorage.hasWriteAccess(ctx)
    }

    private fun activeDirectory(): File =
        overrideDirectory ?: PlaylistStorage.defaultPublicDirectory()

    fun isOfflinePlaylist(browseId: String?): Boolean = browseId?.startsWith(PREFIX) == true

    fun isLikedPage(browseId: String?): Boolean = browseId == PREFIX + LIKED_ID

    fun get(browseId: String?): OfflinePlaylist? {
        val id = browseId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        return _playlists.value.firstOrNull { it.id == id }
    }

    fun isLiked(song: Song): Boolean = song.localUri?.let { it in _liked.value } == true

    /** Likes or unlikes a device file; returns whether it is liked now. */
    fun toggleLike(song: Song): Boolean {
        val key = song.localUri ?: return false
        var nowLiked = false
        mutate { list ->
            nowLiked = key !in _liked.value
            list.map { playlist ->
                if (!playlist.isLiked) return@map playlist
                val rest = playlist.songs.filterNot { it.localUri == key }
                playlist.copy(songs = if (nowLiked) listOf(song.stored()) + rest else rest)
            }
        }
        return nowLiked
    }

    /** A new, empty playlist — or one holding [songs], when it was made to take them. */
    fun create(title: String, songs: List<Song> = emptyList()): OfflinePlaylist {
        val playlist = OfflinePlaylist(
            id = "p" + System.currentTimeMillis().toString(36),
            title = title.trim(),
            songs = songs.filter { it.localUri != null }.distinctBy { it.localUri }.map { it.stored() },
        )
        mutate { it + playlist }
        return playlist
    }

    class AddResult(val added: Int, val alreadyThere: Int)

    /**
     * Adds [songs] to every playlist in [ids], each at most once per playlist.
     * [AddResult.added] counts the playlists that took at least one of them.
     */
    fun add(ids: Collection<String>, songs: List<Song>): AddResult {
        val incoming = songs.filter { it.localUri != null }.distinctBy { it.localUri }
        var added = 0
        var alreadyThere = 0
        mutate { list ->
            list.map { playlist ->
                if (playlist.id !in ids) return@map playlist
                val known = playlist.songs.mapTo(HashSet()) { it.localUri }
                val fresh = incoming.filter { known.add(it.localUri) }.map { it.stored() }
                if (fresh.isEmpty()) {
                    alreadyThere++
                    return@map playlist
                }
                added++
                // Liked keeps its newest first, as a like from the menu does.
                playlist.copy(songs = if (playlist.isLiked) fresh + playlist.songs else playlist.songs + fresh)
            }
        }
        return AddResult(added, alreadyThere)
    }

    fun remove(browseId: String, song: Song) {
        val id = browseId.removePrefix(PREFIX)
        val key = song.localUri ?: return
        mutate { list ->
            list.map { if (it.id == id) it.copy(songs = it.songs.filterNot { s -> s.localUri == key }) else it }
        }
    }

    /** Liked is the app's, not the user's: it can't be renamed or deleted. */
    fun rename(browseId: String, title: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID || title.isBlank()) return
        mutate { list -> list.map { if (it.id == id) it.copy(title = title.trim()) else it } }
    }

    fun delete(browseId: String) {
        val id = browseId.removePrefix(PREFIX)
        if (id == LIKED_ID) return
        mutate { list -> list.filterNot { it.id == id } }
    }

    fun mergeFromSync(incoming: List<OfflinePlaylist>): Int {
        if (incoming.isEmpty()) return 0
        var mergedCount = 0
        mutate { list ->
            val updated = list.toMutableList()
            for (inList in incoming) {
                if (inList.isLiked) continue
                val cleanTitle = inList.title.trim()
                if (cleanTitle.isEmpty()) continue
                val validSongs = inList.songs.filter { !it.localUri.isNullOrBlank() || !it.localPath.isNullOrBlank() }
                val existingIdx = updated.indexOfFirst {
                    !it.isLiked && (it.id == inList.id || it.title.trim().equals(cleanTitle, ignoreCase = true))
                }
                if (existingIdx >= 0) {
                    val existing = updated[existingIdx]
                    val seenUris = existing.songs.mapNotNullTo(HashSet()) { it.localUri ?: it.localPath }
                    val seenTitles = existing.songs.mapTo(HashSet()) { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                    val fresh = validSongs.filter { song ->
                        val key = song.localUri ?: song.localPath
                        val uniqueKey = key == null || seenUris.add(key)
                        val uniqueTitle = seenTitles.add("${song.title.lowercase()}|${song.artist.lowercase()}")
                        uniqueKey && uniqueTitle
                    }.map { it.stored() }
                    if (fresh.isNotEmpty()) {
                        updated[existingIdx] = existing.copy(songs = existing.songs + fresh)
                        mergedCount++
                    }
                } else {
                    updated.add(inList.copy(songs = validSongs.map { it.stored() }))
                    mergedCount++
                }
            }
            updated
        }
        return mergedCount
    }

    private inline fun mutate(change: (List<OfflinePlaylist>) -> List<OfflinePlaylist>) {
        synchronized(lock) { publish(change(_playlists.value)) }
    }

    /** Sets the list and the liked set together, Liked pinned first. */
    private fun publish(list: List<OfflinePlaylist>) {
        val liked = list.firstOrNull { it.isLiked } ?: OfflinePlaylist(LIKED_ID, likedTitle, emptyList())
        val ordered = listOf(liked.copy(title = likedTitle)) + list.filterNot { it.isLiked }
        _playlists.value = ordered
        _liked.value = liked.songs.mapNotNullTo(HashSet()) { it.localUri }
    }

    /**
     * Only what playing and drawing a row needs. The queue fields a song picks
     * up on its way through the player are left behind: they describe where it
     * was played from last, not the file.
     */
    private fun Song.stored(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        localUri = localUri,
        localPath = localPath,
        localDateAddedSeconds = localDateAddedSeconds,
        localDateModifiedSeconds = localDateModifiedSeconds,
        trackNumber = trackNumber,
    )

    private fun loadFromSources(): List<OfflinePlaylist> {
        val prefsList = loadFromPrefs()
        if (!canWriteFolder()) return prefsList

        val dir = activeDirectory()
        val diskUser = PlaylistStorage.readDirectory(dir, PlaylistStorage.KIND_OFFLINE)
            .filterNot { it.id == LIKED_ID }
            .map { OfflinePlaylist(id = it.id, title = it.title, songs = it.songs) }

        val liked = prefsList.firstOrNull { it.isLiked }
        val migratedAlready = this::prefs.isInitialized && prefs.getBoolean(KEY_FOLDER_MIGRATED, false)
        if (!migratedAlready) {
            val prefsUser = prefsList.filterNot { it.isLiked }
            val unmigrated = prefsUser.filter { legacy ->
                diskUser.none { existing ->
                    existing.id == legacy.id || existing.title.trim().equals(legacy.title.trim(), ignoreCase = true)
                }
            }
            val mergedUser = if (unmigrated.isNotEmpty()) {
                val combined = diskUser + unmigrated
                saveUserPlaylistsToDirectory(combined)
                combined
            } else {
                if (!dir.exists()) dir.mkdirs()
                diskUser
            }
            if (this::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_FOLDER_MIGRATED, true).apply()
            }
            return listOfNotNull(liked) + mergedUser
        }
        return listOfNotNull(liked) + diskUser
    }

    private fun loadFromPrefs(): List<OfflinePlaylist> {
        if (!this::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val songs = obj.getJSONArray("songs")
                OfflinePlaylist(
                    id = obj.getString("id"),
                    title = obj.optString("title"),
                    songs = (0 until songs.length()).mapNotNull { songFrom(songs.getJSONObject(it)) },
                )
            }
        }.getOrElse {
            DebugLog.w("BitChord", "Offline playlists unreadable: ${it.message}")
            emptyList()
        }
    }

    internal fun persist(list: List<OfflinePlaylist>) {
        if (this::prefs.isInitialized) {
            runCatching {
                val array = JSONArray()
                list.forEach { playlist ->
                    val songs = JSONArray()
                    playlist.songs.forEach { songs.put(it.toJson()) }
                    array.put(
                        JSONObject()
                            .put("id", playlist.id)
                            .put("title", playlist.title)
                            .put("songs", songs),
                    )
                }
                prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
            }.onFailure { DebugLog.w("BitChord", "Offline playlists not saved: ${it.message}") }
        }
        if (canWriteFolder()) {
            if (saveUserPlaylistsToDirectory(list.filterNot { it.isLiked }) && this::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_FOLDER_MIGRATED, true).apply()
            }
        }
    }

    private fun saveUserPlaylistsToDirectory(userPlaylists: List<OfflinePlaylist>): Boolean =
        PlaylistStorage.saveKind(
            playlists = userPlaylists.mapIndexed { idx, pl ->
                StoredPlaylist(
                    id = pl.id,
                    title = pl.title,
                    kind = PlaylistStorage.KIND_OFFLINE,
                    sortOrder = idx,
                    songs = pl.songs,
                )
            },
            kind = PlaylistStorage.KIND_OFFLINE,
            directory = activeDirectory(),
        )

    private fun Song.toJson(): JSONObject = PlaylistStorage.songToJson(this)

    private fun songFrom(obj: JSONObject): Song? = PlaylistStorage.songFromJson(obj)
}

/**
 * As the Playlists tab lists it and its page opens — the same [LocalGroup]
 * an album out of the folder is. Liked keeps its heart rather than borrowing
 * the sleeve of whatever was liked last.
 */
fun OfflinePlaylist.toGroup(): LocalGroup = LocalGroup(
    browseId = browseId,
    title = title,
    subtitle = "",
    thumbnailUrl = if (isLiked) null else songs.firstNotNullOfOrNull { it.thumbnailUrl },
    type = com.music.bitchord.data.model.BrowseType.PLAYLIST,
    songs = songs,
)

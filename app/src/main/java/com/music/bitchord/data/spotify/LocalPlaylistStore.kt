package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.PlaylistStorage
import com.music.bitchord.data.StoredPlaylist
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"
    private const val KEY_FOLDER_MIGRATED = "playlists_folder_migrated_v1"

    private lateinit var prefs: SharedPreferences
    private var appContext: Context? = null
    private var overrideDirectory: File? = null
    private var overrideWriteAccess: Boolean? = null

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        overrideDirectory = null
        overrideWriteAccess = null
        refreshFromStorage(appContext!!)
    }

    internal fun initForTest(
        preferences: SharedPreferences? = null,
        directory: File? = null,
        writeAccess: Boolean = directory != null,
    ) {
        appContext = null
        overrideDirectory = directory
        overrideWriteAccess = writeAccess
        if (preferences != null) {
            prefs = preferences
        }
        reloadForCurrentState()
    }

    fun refreshFromStorage(context: Context? = appContext) {
        if (context != null) appContext = context.applicationContext
        reloadForCurrentState()
    }

    private fun canWriteFolder(): Boolean {
        overrideWriteAccess?.let { return it }
        val ctx = appContext ?: return false
        return PlaylistStorage.hasWriteAccess(ctx)
    }

    private fun activeDirectory(): File =
        overrideDirectory ?: PlaylistStorage.defaultPublicDirectory()

    private fun reloadForCurrentState() {
        val fromPrefs = loadFromPrefs()
        if (!canWriteFolder()) {
            if (this::prefs.isInitialized) {
                _playlists.value = fromPrefs
            }
            return
        }

        val dir = activeDirectory()
        val fromDisk = PlaylistStorage.readDirectory(dir, PlaylistStorage.KIND_LOCAL)
            .map { LocalPlaylist(id = it.id, title = it.title, songs = it.songs) }

        val migratedAlready = this::prefs.isInitialized && prefs.getBoolean(KEY_FOLDER_MIGRATED, false)
        if (!migratedAlready) {
            val unmigrated = fromPrefs.filter { legacy ->
                fromDisk.none { existing ->
                    existing.id == legacy.id || existing.title.trim().equals(legacy.title.trim(), ignoreCase = true)
                }
            }
            val merged = if (unmigrated.isNotEmpty()) {
                val combined = fromDisk + unmigrated
                writeToDirectory(combined)
                combined
            } else {
                // Ensure the public Playlists folder exists right away when access is available.
                if (!dir.exists()) dir.mkdirs()
                fromDisk
            }
            if (this::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_FOLDER_MIGRATED, true).apply()
            }
            _playlists.value = merged
            persistToPrefs(merged)
        } else {
            _playlists.value = fromDisk
            persistToPrefs(fromDisk)
        }
    }

    private fun loadFromPrefs(): List<LocalPlaylist> {
        if (!this::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.optJSONArray("songs") ?: JSONArray()
                val songs = buildList {
                    for (j in 0 until songsArray.length()) {
                        val sObj = songsArray.optJSONObject(j) ?: continue
                        PlaylistStorage.songFromJson(sObj)?.let(::add)
                    }
                }
                list.add(LocalPlaylist(id, title, songs))
            }
            list
        }.getOrDefault(emptyList())
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val cleanTitle = title.trim().ifBlank { "Playlist" }
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = cleanTitle, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    /**
     * Adds [songs] to an existing local playlist (deduplicating by [Song.videoId] / local path),
     * returning the count of newly added tracks.
     */
    fun addSongs(browseId: String, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val cleanId = browseId.removePrefix("local:playlist:").removePrefix("VL")
        var addedCount = 0
        val updated = _playlists.value.map { playlist ->
            if (playlist.id != cleanId && playlist.id != browseId && playlist.browseId != browseId) {
                return@map playlist
            }
            val seenIds = playlist.songs.mapTo(HashSet()) { it.videoId }
            val seenPaths = playlist.songs.mapNotNullTo(HashSet()) { it.localPath ?: it.localUri }
            val fresh = songs.filter { s ->
                val uniqueId = s.videoId.isBlank() || seenIds.add(s.videoId)
                val keyPath = s.localPath ?: s.localUri
                val uniquePath = keyPath == null || seenPaths.add(keyPath)
                uniqueId && uniquePath
            }
            addedCount = fresh.size
            if (fresh.isEmpty()) playlist else playlist.copy(songs = playlist.songs + fresh)
        }
        if (addedCount > 0) {
            _playlists.value = updated
            persist(updated)
        }
        return addedCount
    }

    fun deletePlaylist(id: String) {
        val cleanId = id.removePrefix("local:playlist:")
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == cleanId }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank()) return
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = cleanTitle)
            } else {
                it
            }
        }
        _playlists.value = updated
        persist(updated)
    }

    /**
     * Takes one row of [song] out of the playlist at [browseId]. The first
     * copy, when the playlist holds it twice: rows here carry no per-row id
     * to say which copy was picked, and they play the same either way.
     */
    fun removeSong(browseId: String, song: Song) {
        val updated = _playlists.value.map { playlist ->
            if (playlist.browseId != browseId && playlist.id != browseId) return@map playlist
            val index = playlist.songs.indexOfFirst {
                it.videoId == song.videoId && (song.localUri == null || it.localUri == song.localUri)
            }.takeIf { it >= 0 } ?: playlist.songs.indexOfFirst { it.videoId == song.videoId }
            if (index < 0) playlist else playlist.copy(songs = playlist.songs.filterIndexed { i, _ -> i != index })
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    /**
     * Merges playlists synced from LuminaChord Desktop into the local store without duplicating songs.
     */
    fun mergeFromSync(incoming: List<LocalPlaylist>): Int {
        if (incoming.isEmpty()) return 0
        val current = _playlists.value.toMutableList()
        var mergedCount = 0
        for (inList in incoming) {
            val cleanTitle = inList.title.trim()
            if (cleanTitle.isEmpty()) continue
            val existingIdx = current.indexOfFirst {
                it.id == inList.id || it.title.trim().equals(cleanTitle, ignoreCase = true)
            }
            if (existingIdx >= 0) {
                val existing = current[existingIdx]
                val seenIds = existing.songs.mapTo(HashSet()) { it.videoId }
                val seenTitleArtist = existing.songs.mapTo(HashSet()) {
                    "${it.title.lowercase()}|${it.artist.lowercase()}"
                }
                val additions = inList.songs.filter { song ->
                    val hasRealYtId = song.videoId.isNotBlank() && !song.videoId.startsWith("local:")
                    val ta = "${song.title.lowercase()}|${song.artist.lowercase()}"
                    if (hasRealYtId) {
                        seenIds.add(song.videoId)
                    } else {
                        (song.videoId.isBlank() || seenIds.add(song.videoId)) &&
                            (song.title.isBlank() || seenTitleArtist.add(ta))
                    }
                }
                if (additions.isNotEmpty()) {
                    current[existingIdx] = existing.copy(songs = existing.songs + additions)
                    mergedCount++
                }
            } else {
                current.add(inList)
                mergedCount++
            }
        }
        if (mergedCount > 0) {
            _playlists.value = current
            persist(current)
        }
        return mergedCount
    }

    private fun persist(list: List<LocalPlaylist>) {
        persistToPrefs(list)
        if (canWriteFolder()) {
            if (writeToDirectory(list) && this::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_FOLDER_MIGRATED, true).apply()
            }
        }
    }

    private fun writeToDirectory(list: List<LocalPlaylist>): Boolean =
        PlaylistStorage.saveKind(
            playlists = list.mapIndexed { idx, pl ->
                StoredPlaylist(
                    id = pl.id,
                    title = pl.title,
                    kind = PlaylistStorage.KIND_LOCAL,
                    sortOrder = idx,
                    songs = pl.songs,
                )
            },
            kind = PlaylistStorage.KIND_LOCAL,
            directory = activeDirectory(),
        )

    private fun persistToPrefs(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    songsArray.put(PlaylistStorage.songToJson(song))
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}

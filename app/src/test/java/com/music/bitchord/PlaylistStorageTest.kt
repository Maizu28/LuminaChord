package com.music.bitchord

import com.music.bitchord.data.OfflinePlaylist
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.PlaylistStorage
import com.music.bitchord.data.StoredPlaylist
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun resetStores() {
        LocalPlaylistStore.initForTest(preferences = null, directory = null, writeAccess = false)
    }

    private fun song(id: String, title: String = "Song $id", localPath: String? = null) = Song(
        videoId = id,
        title = title,
        artist = "Artist",
        thumbnailUrl = null,
        localPath = localPath,
        localUri = localPath?.let { "file://$it" },
    )

    @Test
    fun `public playlist folder constant points to emulated LuminaChord Playlists`() {
        assertEquals("LuminaChord/Playlists", PlaylistStorage.DEFAULT_PUBLIC_RELATIVE_PATH)
        assertEquals("/storage/emulated/0/LuminaChord/Playlists", PlaylistStorage.DEFAULT_PUBLIC_PATH_LABEL)
    }

    @Test
    fun `saveKind and readDirectory persist local and offline playlists without colliding`() {
        val dir = tempFolder.newFolder("LuminaChordPlaylists")

        assertTrue(
            PlaylistStorage.saveKind(
                playlists = listOf(
                    StoredPlaylist(
                        id = "loc_1",
                        title = "Road Trip",
                        kind = PlaylistStorage.KIND_LOCAL,
                        songs = listOf(song("yt123", "Drive")),
                    ),
                ),
                kind = PlaylistStorage.KIND_LOCAL,
                directory = dir,
            ),
        )
        assertTrue(
            PlaylistStorage.saveKind(
                playlists = listOf(
                    StoredPlaylist(
                        id = "off_1",
                        title = "Road Trip",
                        kind = PlaylistStorage.KIND_OFFLINE,
                        songs = listOf(song("local:/storage/emulated/0/LuminaChord/Music/Track.mp3", "Track", "/storage/emulated/0/LuminaChord/Music/Track.mp3")),
                    ),
                ),
                kind = PlaylistStorage.KIND_OFFLINE,
                directory = dir,
            ),
        )

        assertTrue(File(dir, "Road Trip.json").isFile)
        assertTrue(File(dir, "Road Trip_off_1.json").isFile)

        val locals = PlaylistStorage.readDirectory(dir, PlaylistStorage.KIND_LOCAL)
        val offlines = PlaylistStorage.readDirectory(dir, PlaylistStorage.KIND_OFFLINE)
        assertEquals(listOf("loc_1"), locals.map { it.id })
        assertEquals(listOf("off_1"), offlines.map { it.id })
        assertEquals("Drive", locals[0].songs[0].title)
        assertEquals("Track", offlines[0].songs[0].title)

        // Deleting local kind leaves offline kind intact on disk
        assertTrue(PlaylistStorage.saveKind(emptyList(), PlaylistStorage.KIND_LOCAL, dir))
        assertFalse(File(dir, "Road Trip.json").exists())
        assertTrue(File(dir, "Road Trip_off_1.json").isFile)
    }

    @Test
    fun `LocalPlaylistStore persists creates renames additions and deletes into playlist directory`() {
        val dir = tempFolder.newFolder("StorePlaylists")
        LocalPlaylistStore.initForTest(preferences = null, directory = dir, writeAccess = true)

        val created = LocalPlaylistStore.savePlaylist("Late: Night?", listOf(song("abc1", "Midnight")))
        assertTrue(File(dir, "Late Night.json").isFile)

        val added = LocalPlaylistStore.addSongs(created.browseId, listOf(song("abc2", "Dawn"), song("abc1", "Midnight")))
        assertEquals(1, added)

        LocalPlaylistStore.renamePlaylist(created.browseId, "Morning Drive")
        assertFalse(File(dir, "Late Night.json").exists())
        assertTrue(File(dir, "Morning Drive.json").isFile)

        // Reloading cleanly from disk reproduces everything including song order
        LocalPlaylistStore.initForTest(preferences = null, directory = dir, writeAccess = true)
        val reloaded = LocalPlaylistStore.playlists.value
        assertEquals(1, reloaded.size)
        assertEquals("Morning Drive", reloaded[0].title)
        assertEquals(listOf("abc1", "abc2"), reloaded[0].songs.map { it.videoId })

        LocalPlaylistStore.deletePlaylist(created.browseId)
        assertFalse(File(dir, "Morning Drive.json").exists())
    }

    @Test
    fun `resolveSong matches synced windows playlist track against android local library`() {
        val localTrack = Song(
            videoId = "local:content://media/external/audio/media/77",
            title = "Anthem",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "content://media/external/audio/media/77",
            localPath = "/storage/emulated/0/LuminaChord/Music/Artist - Anthem.mp3",
        )
        val incomingFromWindows = Song(
            videoId = "local:C:\\LuminaChord\\Music\\Artist - Anthem.mp3",
            title = "Anthem",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = null,
            localPath = "C:\\LuminaChord\\Music\\Artist - Anthem.mp3",
        )

        val resolved = PlaylistStorage.resolveSong(incomingFromWindows, fileNameHint = "Artist - Anthem.mp3") {
            listOf(localTrack)
        }
        assertEquals("content://media/external/audio/media/77", resolved.localUri)
        assertEquals("/storage/emulated/0/LuminaChord/Music/Artist - Anthem.mp3", resolved.localPath)
    }

    @Test
    fun `OfflinePlaylists mergeFromSync avoids duplicate songs`() {
        val merged = OfflinePlaylist(
            id = "sync_off",
            title = "Workout",
            songs = listOf(song("local:1", "Push", "/storage/emulated/0/LuminaChord/Music/Push.mp3")),
        )
        val firstCount = OfflinePlaylists.mergeFromSync(listOf(merged))
        val secondCount = OfflinePlaylists.mergeFromSync(listOf(merged))
        assertEquals(1, firstCount)
        assertEquals(0, secondCount)
        OfflinePlaylists.delete("${OfflinePlaylists.PREFIX}sync_off")
    }
}

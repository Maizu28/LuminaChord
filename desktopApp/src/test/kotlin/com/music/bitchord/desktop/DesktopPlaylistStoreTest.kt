package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaylistStoreTest {

    private fun song(id: String, title: String = "Track $id", localPath: String? = null) = Song(
        videoId = id,
        title = title,
        artist = "Artist",
        thumbnailUrl = null,
        localPath = localPath,
    )

    @Test
    fun `default directory uses C drive LuminaChord Playlists on Windows`() {
        assertEquals(Path.of("C:\\LuminaChord\\Playlists"), DesktopPlaylistStore.defaultDirectory(windows = true))
        val mac = DesktopPlaylistStore.defaultDirectory(windows = false).toString().replace('\\', '/')
        assertTrue(mac.endsWith("/LuminaChord/Playlists"), "Expected user home Playlists path, got $mac")
    }

    @Test
    fun `saveAll writes readable json files handles rename collision and deletes removed playlists`() {
        val dir = Files.createTempDirectory("desktop-playlists-test")
        try {
            val first = DesktopPlaylist(id = "pl_1", title = "Chill: Mix?", songs = listOf(song("abc123", "Night Ride")))
            val duplicateTitle = DesktopPlaylist(id = "pl_2", title = "Chill: Mix?", songs = listOf(song("xyz789", "Daybreak")))

            assertTrue(DesktopPlaylistStore.saveAll(listOf(first, duplicateTitle), dir))
            val files = Files.list(dir).use { it.toList() }
            assertEquals(2, files.size)
            assertTrue(files.any { it.fileName.toString() == "Chill Mix.json" })
            assertTrue(files.any { it.fileName.toString().startsWith("Chill Mix_pl_2") && it.fileName.toString().endsWith(".json") })

            val loaded = DesktopPlaylistStore.readDirectory(dir, localSongsProvider = { emptyList() })
            assertEquals(listOf("pl_1", "pl_2"), loaded.map { it.id })
            assertEquals(listOf("Night Ride"), loaded[0].songs.map { it.title })

            // Rename first playlist -> old file removed, new file written
            val renamed = first.copy(title = "Late Night")
            assertTrue(DesktopPlaylistStore.saveAll(listOf(renamed), dir))
            assertFalse(dir.resolve("Chill Mix.json").exists())
            assertTrue(dir.resolve("Late Night.json").exists())

            // Deleting remaining playlist deletes its file on disk
            assertTrue(DesktopPlaylistStore.saveAll(emptyList(), dir))
            val afterDelete = Files.list(dir).use { it.toList() }
            assertTrue(afterDelete.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load migrates legacy preferences once and does not resurrect deleted files`() {
        val dir = Files.createTempDirectory("desktop-playlists-migrate")
        try {
            var migrated = false
            val legacy = listOf(DesktopPlaylist(id = "leg_1", title = "Legacy Favs", songs = listOf(song("v1"))))

            val firstLoad = DesktopPlaylistStore.load(
                directory = dir,
                legacyPlaylists = legacy,
                alreadyMigrated = migrated,
                markMigrated = { migrated = true },
                localSongsProvider = { emptyList() },
            )
            assertTrue(migrated)
            assertEquals(1, firstLoad.size)
            assertTrue(dir.resolve("Legacy Favs.json").exists())

            // Simulate deleting the file manually on disk while legacy pref list still exists
            Files.delete(dir.resolve("Legacy Favs.json"))
            val secondLoad = DesktopPlaylistStore.load(
                directory = dir,
                legacyPlaylists = legacy,
                alreadyMigrated = migrated,
                markMigrated = {},
                localSongsProvider = { emptyList() },
            )
            assertTrue(secondLoad.isEmpty(), "Deleted playlist file must stay deleted after migration")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readDirectory resolves synced local tracks by file name against desktop library`() {
        val dir = Files.createTempDirectory("desktop-playlists-resolve")
        val musicDir = Files.createTempDirectory("desktop-music-resolve")
        try {
            val localFile = musicDir.resolve("Artist - Anthem.mp3")
            Files.write(localFile, byteArrayOf(1, 2, 3))

            val jsonContent = """
                {
                  "id": "android_p1",
                  "title": "From Phone",
                  "kind": "offline",
                  "songs": [
                    {
                      "videoId": "local:content://media/external/audio/media/42",
                      "title": "Anthem",
                      "artist": "Artist",
                      "localPath": "/storage/emulated/0/LuminaChord/Music/Artist - Anthem.mp3",
                      "fileName": "Artist - Anthem.mp3"
                    }
                  ]
                }
            """.trimIndent()
            Files.writeString(dir.resolve("From Phone.json"), jsonContent)

            val desktopCatalog = listOf(
                Song(
                    videoId = "local:${localFile.toAbsolutePath()}",
                    title = "Anthem",
                    artist = "Artist",
                    thumbnailUrl = null,
                    localPath = localFile.toAbsolutePath().toString(),
                ),
            )
            val loaded = DesktopPlaylistStore.readDirectory(dir, localSongsProvider = { desktopCatalog })
            assertEquals(1, loaded.size)
            assertEquals(localFile.toAbsolutePath().toString(), loaded[0].songs[0].localPath)
            assertEquals("offline", DesktopPlaylistStore.kindFor("android_p1"))
        } finally {
            dir.toFile().deleteRecursively()
            musicDir.toFile().deleteRecursively()
        }
    }
}

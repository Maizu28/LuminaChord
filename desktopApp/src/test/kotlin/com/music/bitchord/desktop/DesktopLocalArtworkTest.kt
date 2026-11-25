package com.music.bitchord.desktop

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopLocalArtworkTest {

    @Test
    fun testRealUserAudioFiles() {
        val testFile = File("C:/Lagu/Music/TWICE - What is Love-.mp3")
        if (!testFile.exists()) return

        val tags = DesktopLocalArtwork.readTags(testFile.toPath())
        assertNotNull(tags, "Tags should be read")
        println("Title: ${tags.title}, Artist: ${tags.artist}, Album: ${tags.album}, Artwork: ${tags.artworkPath}")
        assertNotNull(tags.artworkPath, "Artwork path should not be null")
        val artFile = File(tags.artworkPath)
        assertTrue(artFile.exists() && artFile.length() > 0, "Artwork file must exist and not be empty")
    }
}

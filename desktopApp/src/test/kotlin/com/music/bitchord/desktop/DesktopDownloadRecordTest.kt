package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A download record is a claim about a folder this app does not own.
 *
 * The listener manages that folder with a file manager, so an entry can outlive the file it names.
 * Trusted rather than checked, such an entry showed the track as downloaded, refused to fetch it
 * again, and then handed the decoder a path that was not there — which surfaced only as
 * "could not open stream".
 */
class DesktopDownloadRecordTest {

    private val scratch = Files.createTempDirectory("bitchord-downloads")

    @AfterTest
    fun cleanUp() {
        scratch.toFile().deleteRecursively()
    }

    private fun song(id: String, path: String?) = Song(
        videoId = id,
        title = id,
        artist = "a",
        thumbnailUrl = "",
        localPath = path,
        localUri = path?.let { java.io.File(it).toURI().toString() },
    )

    private fun realFile(name: String): String {
        val file = scratch.resolve(name)
        Files.write(file, ByteArray(2048))
        return file.toAbsolutePath().toString()
    }

    @Test
    fun `a record whose file is there is honoured`() {
        val saved = song("a", realFile("a.m4a"))
        assertTrue(DesktopDownloadManager.isSaved(saved))
        assertEquals(saved.localPath, DesktopDownloadManager.savedFile(saved)?.toString())
    }

    @Test
    fun `a record whose file has gone is not`() {
        val gone = song("b", scratch.resolve("never-written.m4a").toString())
        assertFalse(DesktopDownloadManager.isSaved(gone))
        assertNull(DesktopDownloadManager.savedFile(gone))
    }

    @Test
    fun `an empty file is not a download`() {
        val empty = scratch.resolve("empty.m4a")
        Files.write(empty, ByteArray(0))
        assertFalse(DesktopDownloadManager.isSaved(song("c", empty.toString())))
    }

    @Test
    fun `a track that was never downloaded has no file`() {
        assertNull(DesktopDownloadManager.savedFile(song("d", null)))
        assertFalse(DesktopDownloadManager.isSaved(song("d", null)))
    }

    @Test
    fun `verifying a list keeps only what is on disk`() {
        val here = song("here", realFile("here.m4a"))
        val gone = song("gone", scratch.resolve("gone.m4a").toString())
        val never = song("never", null)
        assertEquals(listOf("here"), DesktopDownloadManager.verified(listOf(here, gone, never)).map { it.videoId })
    }

    @Test
    fun `deleting removes the file and says so`() {
        val saved = song("e", realFile("e.m4a"))
        assertTrue(DesktopDownloadManager.delete(saved))
        assertFalse(DesktopDownloadManager.isSaved(saved))
        // Deleting again is not an error, just nothing to do.
        assertFalse(DesktopDownloadManager.delete(saved))
    }

    @Test
    fun `a file uri with spaces and brackets is resolved`() {
        // The name the downloader actually writes: "Justin Bieber - Peaches (feat. …).m4a".
        val saved = song("f", realFile("Artist - Track (feat. Someone).m4a"))
        assertTrue(DesktopDownloadManager.isSaved(saved))
        // Even with only the uri to go on.
        val uriOnly = Song(
            videoId = "f",
            title = "f",
            artist = "a",
            thumbnailUrl = "",
            localUri = saved.localUri,
        )
        assertTrue(DesktopDownloadManager.isSaved(uriOnly))
    }

    @Test
    fun `what reaches the decoder is a path, not a percent-encoded uri`() {
        // FFmpeg does not percent-decode what it is handed. A file:// uri for a name with spaces
        // and brackets in it sent the decoder looking for "Justin%20Bieber%20-%20…" and it
        // answered "No such file or directory" for a file that was sitting right there.
        val saved = song("g", realFile("Justin Bieber - Peaches (feat. Daniel Caesar & Giveon).m4a"))
        val handed = DesktopDownloadManager.savedFile(saved)!!.toAbsolutePath().toString()
        assertFalse(handed.contains("%"), handed)
        assertFalse(handed.startsWith("file:"), handed)
        assertTrue(java.io.File(handed).isFile, handed)
        // The recorded uri is still a uri — it is the record, not what is opened.
        assertTrue(saved.localUri!!.startsWith("file:"))
        assertTrue(saved.localUri!!.contains("%20"))
    }

    @Test
    fun `download directory follows selected local folder or defaults to LuminaChord Music`() {
        val chosen = scratch.resolve("MyMusic")
        assertEquals(chosen, DesktopDownloadManager.directory(selectedLocalFolder = chosen, windows = true))
        assertEquals(
            java.nio.file.Path.of("C:\\LuminaChord\\Music"),
            DesktopDownloadManager.directory(selectedLocalFolder = null, windows = true),
        )
        assertEquals(
            java.nio.file.Path.of(System.getProperty("user.home"), "LuminaChord", "Music"),
            DesktopDownloadManager.directory(selectedLocalFolder = null, windows = false),
        )
    }

    @Test
    fun `download format defaults to MP3 and preserves lossless FLAC`() {
        assertEquals(DesktopDownloadFormat.MP3, DesktopDownloadFormat.fromPreference(""))
        assertEquals(DesktopDownloadFormat.WEBM, DesktopDownloadFormat.fromPreference("webm"))
        assertEquals("flac", DesktopDownloadManager.targetExtension("flac", DesktopDownloadFormat.MP3))
        assertEquals("flac", DesktopDownloadManager.targetExtension("FLAC", DesktopDownloadFormat.WEBM))
        assertEquals("mp3", DesktopDownloadManager.targetExtension("m4a", DesktopDownloadFormat.MP3))
        assertEquals("mp3", DesktopDownloadManager.targetExtension("webm", DesktopDownloadFormat.MP3))
        assertEquals("webm", DesktopDownloadManager.targetExtension("m4a", DesktopDownloadFormat.WEBM))
    }

    @Test
    fun `migrating legacy downloads moves files and recovers surviving tracks`() {
        val legacyDir = scratch.resolve("legacy-BitChord").also { Files.createDirectories(it) }
        val targetDir = scratch.resolve("LuminaChord-Music").also { Files.createDirectories(it) }
        val trackedFile = legacyDir.resolve("NIKI - Take A Chance With Me.webm").also {
            Files.write(it, ByteArray(4096) { 1 })
        }
        val orphanLegacy = legacyDir.resolve("Lady Gaga - Paparazzi.mp3").also {
            Files.write(it, ByteArray(4096) { 2 })
        }
        val alreadyTarget = targetDir.resolve("Existing - Track.mp3").also {
            Files.write(it, ByteArray(4096) { 3 })
        }
        val recorded = listOf(song("yt-1", trackedFile.toAbsolutePath().toString()))

        val outcome = DesktopDownloadManager.migrateLegacyDownloads(
            recorded = recorded,
            targetDir = targetDir,
            legacyDir = legacyDir,
            adoptTargetUnrecorded = true,
        )

        assertTrue(outcome.changed)
        assertFalse(Files.exists(trackedFile))
        assertFalse(Files.exists(orphanLegacy))
        assertTrue(Files.exists(targetDir.resolve("NIKI - Take A Chance With Me.webm")))
        assertTrue(Files.exists(targetDir.resolve("Lady Gaga - Paparazzi.mp3")))
        assertTrue(Files.exists(alreadyTarget))
        val migrated = outcome.downloads.first { it.videoId == "yt-1" }
        assertEquals(targetDir.resolve("NIKI - Take A Chance With Me.webm").toString(), migrated.localPath)
        assertEquals(3, outcome.downloads.size)
    }

    @Test
    fun `ffmpeg encoder converts wav audio into valid mp3 and webm files`() {
        val wav = writeTestWav(scratch.resolve("tone.wav"))
        val track = Song(videoId = "enc-1", title = "Tone", artist = "Tester", thumbnailUrl = "")

        val mp3Out = scratch.resolve("out.mp3")
        DesktopDownloadEncoder.transcode(wav, mp3Out, DesktopDownloadFormat.MP3, "HIGH", track)
        val mp3Header = Files.readAllBytes(mp3Out).take(3)
        assertTrue(Files.size(mp3Out) > 512L)
        assertEquals(listOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()), mp3Header)

        val webmOut = scratch.resolve("out.webm")
        DesktopDownloadEncoder.transcode(wav, webmOut, DesktopDownloadFormat.WEBM, "HIGH", track)
        val webmHeader = Files.readAllBytes(webmOut).take(4).map { it.toInt() and 0xFF }
        assertTrue(Files.size(webmOut) > 512L)
        assertEquals(listOf(0x1A, 0x45, 0xDF, 0xA3), webmHeader)
    }

    private fun writeTestWav(path: java.nio.file.Path): java.nio.file.Path {
        val sampleRate = 44100
        val channels = 2
        val samples = sampleRate / 2 // 0.5 seconds
        val dataBytes = samples * channels * 2
        val out = java.io.ByteArrayOutputStream(44 + dataBytes)
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLe(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun writeShortLe(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }
        writeAscii("RIFF"); writeIntLe(36 + dataBytes); writeAscii("WAVE")
        writeAscii("fmt "); writeIntLe(16); writeShortLe(1); writeShortLe(channels)
        writeIntLe(sampleRate); writeIntLe(sampleRate * channels * 2)
        writeShortLe(channels * 2); writeShortLe(16)
        writeAscii("data"); writeIntLe(dataBytes)
        for (i in 0 until samples * channels) {
            val s = (kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * (i / channels) / sampleRate) * 12000).toInt()
            writeShortLe(s)
        }
        Files.write(path, out.toByteArray())
        return path
    }
}

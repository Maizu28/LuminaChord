package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import org.bytedeco.ffmpeg.ffmpeg as FFmpegProgram
import org.bytedeco.javacpp.Loader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Converts downloaded desktop audio streams into the user's chosen container (MP3 or WebM) using
 * the bundled JavaCPP FFmpeg binary and embeds basic tags / artwork when supported by the target.
 */
internal object DesktopDownloadEncoder {

    private val ffmpegBinary: String by lazy {
        Loader.load(FFmpegProgram::class.java)
    }

    /**
     * Transcodes [source] into [output] in [format] at a bitrate suited to [quality].
     *
     * Runs FFmpeg out-of-process using the native binary shipped inside `org.bytedeco:ffmpeg` so
     * resampling, frame sizing, and ID3v2/WebM muxing use FFmpeg's own tested pipelines.
     */
    fun transcode(
        source: Path,
        output: Path,
        format: DesktopDownloadFormat,
        quality: String,
        song: Song,
        timeoutSeconds: Long = 120,
    ) {
        val coverFile = if (format == DesktopDownloadFormat.MP3) fetchCoverTemp(song) else null
        try {
            val result = runFfmpeg(source, output, format, quality, song, coverFile, timeoutSeconds)
            if (result != null && coverFile != null) {
                // Retry once without attached cover in case a malformed thumbnail image rejected muxing.
                Files.deleteIfExists(output)
                val fallback = runFfmpeg(source, output, format, quality, song, null, timeoutSeconds)
                if (fallback != null) error(fallback)
            } else if (result != null) {
                error(result)
            }
        } finally {
            coverFile?.delete()
        }
        check(Files.isRegularFile(output) && Files.size(output) > 0L) {
            "Audio conversion produced an empty file"
        }
    }

    private fun runFfmpeg(
        source: Path,
        output: Path,
        format: DesktopDownloadFormat,
        quality: String,
        song: Song,
        coverFile: File?,
        timeoutSeconds: Long,
    ): String? {
        val args = buildArgs(
            ffmpeg = ffmpegBinary,
            source = source.toAbsolutePath().toString(),
            output = output.toAbsolutePath().toString(),
            format = format,
            quality = quality,
            song = song,
            coverPath = coverFile?.absolutePath,
        )
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val logText = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "Audio conversion timed out"
        }
        val code = process.exitValue()
        return if (code == 0) {
            null
        } else {
            val detail = logText.lineSequence().map(String::trim).filter(String::isNotBlank).lastOrNull()
            "Audio conversion failed (${detail ?: "exit $code"})"
        }
    }

    internal fun buildArgs(
        ffmpeg: String,
        source: String,
        output: String,
        format: DesktopDownloadFormat,
        quality: String,
        song: Song,
        coverPath: String?,
    ): List<String> = buildList {
        add(ffmpeg)
        add("-y")
        add("-v")
        add("error")
        add("-i")
        add(source)
        val attachCover = format == DesktopDownloadFormat.MP3 && coverPath != null
        if (coverPath != null && format == DesktopDownloadFormat.MP3) {
            add("-i")
            add(coverPath)
        }
        add("-map")
        add("0:a:0")
        when (format) {
            DesktopDownloadFormat.MP3 -> {
                if (attachCover) {
                    add("-map")
                    add("1:v:0")
                    add("-c:v")
                    add("mjpeg")
                    add("-disposition:v:0")
                    add("attached_pic")
                } else {
                    add("-vn")
                }
                val bitrate = if (quality.equals("STANDARD", ignoreCase = true)) "192k" else "320k"
                add("-c:a")
                add("libmp3lame")
                add("-b:a")
                add(bitrate)
                add("-id3v2_version")
                add("3")
                addMetadata(song)
                add("-f")
                add("mp3")
            }
            DesktopDownloadFormat.WEBM -> {
                add("-vn")
                val bitrate = if (quality.equals("STANDARD", ignoreCase = true)) "128k" else "192k"
                add("-c:a")
                add("libopus")
                add("-b:a")
                add(bitrate)
                addMetadata(song)
                add("-f")
                add("webm")
            }
        }
        add(output)
    }

    private fun MutableList<String>.addMetadata(song: Song) {
        if (song.title.isNotBlank()) {
            add("-metadata")
            add("title=${song.title}")
        }
        if (song.artist.isNotBlank()) {
            add("-metadata")
            add("artist=${song.artist}")
        }
        val album = song.albumName?.takeIf(String::isNotBlank)
        if (album != null) {
            add("-metadata")
            add("album=$album")
        }
    }

    private fun fetchCoverTemp(song: Song): File? {
        val url = song.artworkAt(1000) ?: return null
        return runCatching {
            val request = okhttp3.Request.Builder().url(url).build()
            DesktopDownloadHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) return@runCatching null
                val temp = File.createTempFile("luminachord-cover-", ".jpg")
                temp.writeBytes(bytes)
                temp
            }
        }.getOrNull()
    }
}

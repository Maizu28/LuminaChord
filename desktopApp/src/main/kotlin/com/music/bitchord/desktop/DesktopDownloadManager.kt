package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

enum class DesktopDownloadFormat(val extension: String, val label: String) {
    MP3("mp3", "MP3"),
    WEBM("webm", "WebM"),
    ;

    companion object {
        fun fromPreference(raw: String?): DesktopDownloadFormat = entries.firstOrNull {
            it.name.equals(raw, ignoreCase = true) || it.extension.equals(raw, ignoreCase = true)
        } ?: MP3
    }
}

/** Downloads resolved audio into the configured Local Music folder or the app's Music directory. */
object DesktopDownloadManager {
    internal const val KEY_DOWNLOAD_FORMAT = "download_format"
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private fun isWindowsOs(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    fun defaultDirectory(windows: Boolean = isWindowsOs()): Path = if (windows) {
        Path.of("C:\\LuminaChord\\Music")
    } else {
        Path.of(System.getProperty("user.home"), "LuminaChord", "Music")
    }

    internal fun fallbackDefaultDirectory(windows: Boolean = isWindowsOs()): Path? = if (windows) {
        Path.of(System.getProperty("user.home"), "LuminaChord", "Music")
    } else {
        null
    }

    fun legacyDirectory(): Path = Path.of(System.getProperty("user.home"), "Music", "BitChord")

    fun directory(selectedLocalFolder: Path? = DesktopLocalMusic.folder(), windows: Boolean = isWindowsOs()): Path =
        selectedLocalFolder ?: defaultDirectory(windows)

    fun directoryLabel(): String = directory().toAbsolutePath().normalize().toString()

    internal fun ensureWritableDirectory(primary: Path = directory()): Path {
        val attempt = runCatching { Files.createDirectories(primary); primary }
        if (attempt.isSuccess && Files.isDirectory(primary) && Files.isWritable(primary)) {
            return primary
        }
        val fallback = fallbackDefaultDirectory()
        if (DesktopLocalMusic.folder() == null && fallback != null) {
            runCatching {
                Files.createDirectories(fallback)
                return fallback
            }
        }
        return attempt.getOrThrow()
    }

    /**
     * Resolves the output extension and whether FFmpeg conversion is required.
     *
     * Lossless FLAC sources are always preserved verbatim as `.flac`; any other stream is saved
     * or transcoded to [preferredFormat] (`.mp3` or `.webm`).
     */
    internal fun targetExtension(sourceExtension: String, preferredFormat: DesktopDownloadFormat): String =
        if (sourceExtension.equals("flac", ignoreCase = true)) "flac" else preferredFormat.extension

    /** Keeps an interrupted transfer in place and resumes it when the user tries again. */
    suspend fun download(
        song: Song,
        quality: String = DesktopPersistence().string("download_quality", "LOSSLESS"),
        format: DesktopDownloadFormat = DesktopDownloadFormat.fromPreference(
            DesktopPersistence().string(KEY_DOWNLOAD_FORMAT),
        ),
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result<Song> {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[song.videoId] = job
        return try {
            require(song.localPath == null) { "This track is already local" }
            val directory = ensureWritableDirectory()
            val safeName = buildString {
                append(song.artist)
                append(" - ")
                append(song.title)
            }.replace(ILLEGAL_FILENAME, "_").trim().take(180).ifBlank { song.videoId }

            // Preserve an existing lossless FLAC or already-converted target track on disk.
            val existingFlac = directory.resolve("$safeName.flac")
            if (Files.isRegularFile(existingFlac) && Files.size(existingFlac) > 0L) {
                return Result.success(song.copy(localUri = existingFlac.toUri().toString(), localPath = existingFlac.toString()))
            }
            val requestedTarget = directory.resolve("$safeName.${format.extension}")
            if (Files.isRegularFile(requestedTarget) && Files.size(requestedTarget) > 0L) {
                return Result.success(
                    song.copy(localUri = requestedTarget.toUri().toString(), localPath = requestedTarget.toString()),
                )
            }

            val stream = DesktopMusicSources.resolve(song, quality, forDownload = true).getOrThrow()
            // A manifest is an index of the audio, not the audio: fetched as a file it saved a few
            // KB of XML as `.flac`. Its segments are fMP4, so it can only become FLAC or M4A.
            val manifest = DesktopManifestDownload.isManifest(stream)
            val rawExtension = stream.format.fileExtension()
            val sourceExtension = if (manifest && rawExtension != "flac") "m4a" else rawExtension
            val finalExtension = targetExtension(sourceExtension, format)
            val target = directory.resolve("$safeName.$finalExtension")
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                return Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
            }

            val needsTranscode = !sourceExtension.equals(finalExtension, ignoreCase = true)
            val sourceTemp = directory.resolve(".$safeName.$sourceExtension.src.part")
            val temporary = directory.resolve(".$safeName.$finalExtension.part")
            val downloadDestination = if (needsTranscode) sourceTemp else temporary

            if (manifest) {
                // Segments are fetched and joined whole, so there is no partial file to resume.
                Files.deleteIfExists(downloadDestination)
                Files.newOutputStream(downloadDestination).buffered().use { output ->
                    DesktopManifestDownload.save(
                        stream,
                        sourceExtension,
                        output,
                        tempDir = File(System.getProperty("java.io.tmpdir")),
                        userAgent = DOWNLOAD_USER_AGENT,
                    ) { done, total -> onProgress(done, total) }
                }
                check(Files.size(downloadDestination) > 0L) { "Download failed: nothing was sent" }
            } else {
                val existing = if (Files.isRegularFile(downloadDestination)) Files.size(downloadDestination) else 0L
                val request = DesktopDownloadHttp.request(
                    stream.url,
                    DOWNLOAD_USER_AGENT,
                    stream.headers,
                    rangeFrom = existing.takeIf { it > 0L },
                )
                DesktopDownloadHttp.client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
                    val body = response.body
                    val append = existing > 0L && response.code == HTTP_PARTIAL
                    val offset = if (append) existing else 0L
                    val total = response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?: body.contentLength().takeIf { it > 0L }?.let { it + offset }
                    if (!append && existing > 0L) Files.deleteIfExists(downloadDestination)
                    Files.newOutputStream(
                        downloadDestination,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
                    ).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = offset
                            onProgress(downloaded, total)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                downloaded += read
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                check(Files.size(downloadDestination) > 0L) { "Download failed: nothing was sent" }
            }

            if (needsTranscode) {
                try {
                    Files.deleteIfExists(temporary)
                    val targetFormat = if (finalExtension == "mp3") DesktopDownloadFormat.MP3 else DesktopDownloadFormat.WEBM
                    DesktopDownloadEncoder.transcode(
                        source = downloadDestination,
                        output = temporary,
                        format = targetFormat,
                        quality = quality,
                        song = song,
                    )
                } finally {
                    runCatching { Files.deleteIfExists(downloadDestination) }
                }
            }

            moveReplacing(temporary, target)
            Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        } finally {
            if (job != null) activeJobs.remove(song.videoId, job)
        }
    }

    /**
     * Moves previously downloaded songs from [legacyDir] into [targetDir] and updates [recorded] paths.
     *
     * When [adoptTargetUnrecorded] is true (default dedicated `LuminaChord/Music` folder), surviving
     * audio files already on disk without a saved preference record (such as after an app reinstall)
     * are also recovered into the returned list.
     */
    internal fun migrateLegacyDownloads(
        recorded: List<Song>,
        targetDir: Path = ensureWritableDirectory(),
        legacyDir: Path = legacyDirectory(),
        adoptTargetUnrecorded: Boolean = DesktopLocalMusic.folder() == null,
    ): MigrationOutcome {
        val normalizedTarget = targetDir.toAbsolutePath().normalize()
        val normalizedLegacy = legacyDir.toAbsolutePath().normalize()
        val sameFolder = normalizedTarget == normalizedLegacy
        var changed = false

        val migratedRecorded = recorded.map { song ->
            val currentFile = savedFile(song)?.toAbsolutePath()?.normalize() ?: return@map song
            if (!sameFolder && currentFile.startsWith(normalizedLegacy)) {
                val moved = runCatching { moveIntoDirectory(currentFile, normalizedTarget) }.getOrNull()
                if (moved != null && Files.isRegularFile(moved) && Files.size(moved) > 0L) {
                    changed = true
                    return@map song.copy(localUri = moved.toUri().toString(), localPath = moved.toString())
                }
            }
            song
        }

        val knownPaths = migratedRecorded.mapNotNull { savedFile(it)?.toAbsolutePath()?.normalize() }.toMutableSet()
        val recovered = mutableListOf<Song>()

        if (!sameFolder && Files.isDirectory(normalizedLegacy)) {
            listAudioFiles(normalizedLegacy).forEach { oldFile ->
                val moved = runCatching { moveIntoDirectory(oldFile, normalizedTarget) }.getOrNull() ?: return@forEach
                changed = true
                if (knownPaths.add(moved)) {
                    recovered += DesktopLocalMusic.songFromFile(moved)
                }
            }
        }

        if (adoptTargetUnrecorded && Files.isDirectory(normalizedTarget)) {
            listAudioFiles(normalizedTarget).forEach { existing ->
                if (knownPaths.add(existing)) {
                    recovered += DesktopLocalMusic.songFromFile(existing)
                    changed = true
                }
            }
        }

        val verified = verified(migratedRecorded + recovered)
        if (verified.size != recorded.size) changed = true
        return MigrationOutcome(downloads = verified, changed = changed)
    }

    internal data class MigrationOutcome(val downloads: List<Song>, val changed: Boolean)

    private fun listAudioFiles(dir: Path): List<Path> = runCatching {
        Files.list(dir).use { stream ->
            stream.filter { path ->
                Files.isRegularFile(path) &&
                    !path.fileName.toString().endsWith(".part", ignoreCase = true) &&
                    runCatching { Files.size(path) > 0L }.getOrDefault(false) &&
                    DesktopLocalMusic.isSupportedAudioExtension(path.extension)
            }.map { it.toAbsolutePath().normalize() }.toList()
        }
    }.getOrDefault(emptyList())

    private fun moveIntoDirectory(source: Path, targetDir: Path): Path {
        Files.createDirectories(targetDir)
        val destination = targetDir.resolve(source.fileName).toAbsolutePath().normalize()
        if (source == destination) return destination
        if (Files.isRegularFile(destination) && Files.size(destination) > 0L) {
            return destination
        }
        val stage = targetDir.resolve(".${source.fileName}.migrate.part")
        Files.deleteIfExists(stage)
        return try {
            Files.copy(source, stage, StandardCopyOption.REPLACE_EXISTING)
            check(Files.size(stage) == Files.size(source)) { "Copied size mismatch" }
            moveReplacing(stage, destination)
            Files.deleteIfExists(source)
            destination
        } finally {
            runCatching { Files.deleteIfExists(stage) }
        }
    }

    private fun moveReplacing(source: Path, target: Path) {
        runCatching {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun cancel(videoId: String) {
        activeJobs[videoId]?.cancel(CancellationException("Download cancelled"))
    }

    /**
     * The file saved for [song], or null when the record has outlived it.
     *
     * A download record is a claim about a folder this app does not own: the listener is expected
     * to manage it with a file manager, so an entry can name a file that has since been moved or
     * deleted. Android checks before it answers for the same reason — without it the player is
     * handed a path that is simply not there and the track refuses to play, saying only "could not
     * open stream".
     */
    fun savedFile(song: Song): Path? {
        val path = song.localPath?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: song.localUri?.takeIf { it.startsWith("file:") }
                ?.let { runCatching { Path.of(URI(it)) }.getOrNull() }
            ?: return null
        return path.takeIf { Files.isRegularFile(it) && runCatching { Files.size(it) > 0 }.getOrDefault(false) }
    }

    /** Whether [song] is really on this computer, rather than merely recorded as being. */
    fun isSaved(song: Song): Boolean = savedFile(song) != null

    /**
     * The records that still name a file, with the rest dropped.
     *
     * A claim that has just been shown to be false is not worth keeping to be shown false again on
     * the next play.
     */
    fun verified(downloads: List<Song>): List<Song> = downloads.filter(::isSaved)

    /** Deletes the file saved for [song]. Returns whether there was one to delete. */
    fun delete(song: Song): Boolean {
        val file = savedFile(song) ?: return false
        return runCatching { Files.deleteIfExists(file) }
            .onFailure { DesktopTrackLog.log("could not delete the download of '${song.title}': ${it.message}") }
            .getOrDefault(false)
    }

    internal fun DesktopStreamFormat.fileExtension(): String {
        val codec = codec.orEmpty().substringAfterLast('/').substringBefore(';').lowercase()
        return when (codec) {
            "flac" -> "flac"
            "wav", "wave" -> "wav"
            "alac" -> "m4a"
            "aac" -> "m4a"
            "mp3" -> "mp3"
            "ogg", "opus" -> "ogg"
            "webm" -> "webm"
            else -> "m4a"
        }
    }

    private const val HTTP_PARTIAL = 206
    private const val DOWNLOAD_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val ILLEGAL_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
}

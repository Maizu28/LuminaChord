package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Serializable
internal data class PortablePlaylistSong(
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val localUri: String? = null,
    val localPath: String? = null,
    val fileName: String? = null,
    val isVideo: Boolean = false,
    val isVideoOrigin: Boolean = false,
    val setVideoId: String? = null,
    val fromAutoplay: Boolean = false,
    val localDateAddedSeconds: Long? = null,
    val localDateModifiedSeconds: Long? = null,
    val sourceQuality: String? = null,
    val isExplicit: Boolean? = null,
    val trackNumber: Int? = null,
)

@Serializable
internal data class PortablePlaylistFile(
    val id: String = "",
    val title: String = "",
    val kind: String = "local",
    val updatedAt: Long = 0L,
    val sortOrder: Int = 0,
    val songs: List<PortablePlaylistSong> = emptyList(),
)

/**
 * Stores Desktop local playlists as portable JSON files inside `C:\LuminaChord\Playlists` on
 * Windows (falling back to `~/LuminaChord/Playlists` on non-Windows or unwritable `C:\`).
 *
 * The JSON schema mirrors Android's `/storage/emulated/0/LuminaChord/Playlists` directory so
 * playlists stay intact across reinstalls and can sync cleanly between Windows and Android.
 */
object DesktopPlaylistStore {
    private val illegalFileChars = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    private val whitespaceRuns = Regex("\\s+")
    private val reservedWindowsNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private const val MAX_STEM_CHARS = 96
    private const val KIND_LOCAL = "local"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fileByPlaylistId = ConcurrentHashMap<String, Path>()
    private val kindByPlaylistId = ConcurrentHashMap<String, String>()
    @Volatile
    private var lastSelfWriteMs: Long = 0L

    private fun isWindowsOs(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    fun defaultDirectory(windows: Boolean = isWindowsOs()): Path = if (windows) {
        Path.of("C:\\LuminaChord\\Playlists")
    } else {
        Path.of(System.getProperty("user.home"), "LuminaChord", "Playlists")
    }

    internal fun fallbackDefaultDirectory(windows: Boolean = isWindowsOs()): Path? = if (windows) {
        Path.of(System.getProperty("user.home"), "LuminaChord", "Playlists")
    } else {
        null
    }

    fun directory(windows: Boolean = isWindowsOs()): Path {
        val primary = defaultDirectory(windows)
        if (Files.isDirectory(primary) && Files.isWritable(primary)) return primary
        if (!Files.exists(primary)) {
            val created = runCatching { Files.createDirectories(primary); primary }.getOrNull()
            if (created != null && Files.isWritable(created)) return created
        }
        val fallback = fallbackDefaultDirectory(windows)
        if (fallback != null) {
            if (Files.isDirectory(fallback) && Files.isWritable(fallback)) return fallback
            runCatching { Files.createDirectories(fallback) }
            if (Files.isDirectory(fallback) && Files.isWritable(fallback)) return fallback
        }
        return primary
    }

    fun directoryLabel(): String = directory().toAbsolutePath().normalize().toString()

    internal fun kindFor(id: String): String = kindByPlaylistId[id] ?: KIND_LOCAL

    internal fun rememberKind(id: String, kind: String) {
        if (id.isNotBlank() && kind.isNotBlank()) {
            kindByPlaylistId[id] = kind
        }
    }

    internal fun ensureWritableDirectory(primary: Path = directory()): Path {
        val attempt = runCatching { Files.createDirectories(primary); primary }
        if (attempt.isSuccess && Files.isDirectory(primary) && Files.isWritable(primary)) {
            return primary
        }
        val fallback = fallbackDefaultDirectory()
        if (fallback != null && primary != fallback) {
            runCatching {
                Files.createDirectories(fallback)
                if (Files.isWritable(fallback)) return fallback
            }
        }
        return attempt.getOrThrow()
    }

    internal fun sanitiseFileName(raw: String): String {
        val cleaned = raw
            .replace(illegalFileChars, " ")
            .replace(whitespaceRuns, " ")
            .trim()
            .trim('.')
            .trim()
            .take(MAX_STEM_CHARS)
            .trim()
        if (cleaned.isBlank()) return "Playlist"
        return if (cleaned.uppercase(Locale.ROOT) in reservedWindowsNames) "${cleaned}_playlist" else cleaned
    }

    internal fun load(
        directory: Path = directory(),
        legacyPlaylists: List<DesktopPlaylist> = emptyList(),
        alreadyMigrated: Boolean = true,
        markMigrated: () -> Unit = {},
        localSongsProvider: () -> List<Song> = { DesktopLocalMusic.scan() },
    ): List<DesktopPlaylist> {
        runCatching { ensureWritableDirectory(directory) }
        val loaded = readDirectory(directory, localSongsProvider)

        if (!alreadyMigrated) {
            val unmigrated = legacyPlaylists.filter { legacy ->
                loaded.none { existing ->
                    existing.id == legacy.id || existing.title.equals(legacy.title, ignoreCase = true)
                }
            }
            if (unmigrated.isNotEmpty()) {
                val merged = loaded + unmigrated
                runCatching { saveAll(merged, directory) }
                markMigrated()
                return merged
            }
            markMigrated()
        }
        return loaded
    }

    internal fun readDirectory(
        directory: Path = directory(),
        localSongsProvider: () -> List<Song> = { DesktopLocalMusic.scan() },
    ): List<DesktopPlaylist> {
        if (!Files.isDirectory(directory)) return emptyList()
        val files = runCatching {
            Files.list(directory).use { stream ->
                stream.filter { path ->
                    Files.isRegularFile(path) &&
                        !path.fileName.toString().startsWith(".") &&
                        path.extension.equals("json", ignoreCase = true)
                }.toList()
            }
        }.getOrDefault(emptyList())

        val parsed = mutableListOf<Triple<PortablePlaylistFile, Path, Long>>()
        for (file in files) {
            val raw = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            val payload = runCatching { json.decodeFromString<PortablePlaylistFile>(raw) }.getOrNull() ?: continue
            val mtime = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
            val resolvedId = payload.id.trim().ifBlank { "file_${sanitiseFileName(file.nameWithoutExtension)}" }
            val resolvedTitle = payload.title.trim().ifBlank { file.nameWithoutExtension.ifBlank { "Playlist" } }
            parsed += Triple(
                payload.copy(
                    id = resolvedId,
                    title = resolvedTitle,
                    updatedAt = if (payload.updatedAt > 0L) payload.updatedAt else mtime,
                ),
                file,
                mtime,
            )
        }

        val deduplicated = linkedMapOf<String, Triple<PortablePlaylistFile, Path, Long>>()
        for (entry in parsed) {
            val key = entry.first.id
            val prev = deduplicated[key]
            if (prev == null || entry.first.updatedAt > prev.first.updatedAt) {
                deduplicated[key] = entry
            }
        }

        val ordered = deduplicated.values.sortedWith(
            compareBy<Triple<PortablePlaylistFile, Path, Long>> { it.first.sortOrder }
                .thenByDescending { it.first.updatedAt }
                .thenBy { it.first.title.lowercase(Locale.ROOT) },
        )

        var lazyLocalSongs: List<Song>? = null
        fun localSongs(): List<Song> {
            val cached = lazyLocalSongs
            if (cached != null) return cached
            return localSongsProvider().also { lazyLocalSongs = it }
        }

        return ordered.map { (payload, path, _) ->
            fileByPlaylistId[payload.id] = path
            kindByPlaylistId[payload.id] = payload.kind.ifBlank { KIND_LOCAL }
            DesktopPlaylist(
                id = payload.id,
                title = payload.title,
                songs = payload.songs.map { dto -> resolveSong(dto, ::localSongs) },
            )
        }
    }

    internal fun saveAll(
        playlists: List<DesktopPlaylist>,
        directory: Path = directory(),
    ): Boolean {
        val targetDir = runCatching { ensureWritableDirectory(directory) }.getOrNull() ?: return false
        lastSelfWriteMs = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val existingOnDisk = readExistingFileMap(targetDir)
        val claimedNames = HashSet<String>()
        val activeIds = HashSet<String>(playlists.size)

        playlists.forEachIndexed { index, playlist ->
            activeIds += playlist.id
            val safeId = playlist.id.replace(illegalFileChars, "_").take(12).ifBlank { index.toString() }
            val baseStem = sanitiseFileName(playlist.title)
            val preferredName = if (claimedNames.add(baseStem.lowercase(Locale.ROOT))) {
                "$baseStem.json"
            } else {
                val uniqueStem = "${baseStem}_${safeId}".take(MAX_STEM_CHARS + 16)
                claimedNames.add(uniqueStem.lowercase(Locale.ROOT))
                "$uniqueStem.json"
            }

            val targetFile = targetDir.resolve(preferredName)
            val previousFile = fileByPlaylistId[playlist.id] ?: existingOnDisk[playlist.id]
            val kind = kindByPlaylistId[playlist.id] ?: KIND_LOCAL

            val payload = PortablePlaylistFile(
                id = playlist.id,
                title = playlist.title.trim().ifBlank { "Playlist" },
                kind = kind,
                updatedAt = now,
                sortOrder = index,
                songs = playlist.songs.map { it.toPortable() },
            )
            val tmpFile = targetDir.resolve(".$preferredName.${System.nanoTime()}.tmp")
            val written = runCatching {
                Files.writeString(tmpFile, json.encodeToString(payload), StandardCharsets.UTF_8)
                Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }
            if (written.isFailure) {
                runCatching { Files.deleteIfExists(tmpFile) }
                return false
            }
            if (previousFile != null && previousFile.normalize() != targetFile.normalize()) {
                runCatching { Files.deleteIfExists(previousFile) }
            }
            fileByPlaylistId[playlist.id] = targetFile
        }

        // Remove stale JSON files for playlists deleted on Desktop.
        existingOnDisk.forEach { (id, path) ->
            if (id !in activeIds) {
                runCatching { Files.deleteIfExists(path) }
                fileByPlaylistId.remove(id)
                kindByPlaylistId.remove(id)
            }
        }
        return true
    }

    /**
     * Watches the Playlists directory on disk for external changes (copied `.json` files, manual edits
     * or deletions) and invokes [onExternalChange] when changes stabilize.
     */
    internal fun watchLoop(
        directory: Path = directory(),
        isRunning: () -> Boolean,
        onExternalChange: () -> Unit,
    ) {
        val dir = runCatching { ensureWritableDirectory(directory) }.getOrNull() ?: return
        val watcher = runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() ?: return
        watcher.use { service ->
            runCatching { dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) }.getOrNull() ?: return
            var pendingTriggerAt = 0L
            while (isRunning()) {
                val key: WatchKey? = runCatching { service.poll(350, TimeUnit.MILLISECONDS) }.getOrNull()
                if (key != null) {
                    var relevant = false
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        val rel = event.context() as? Path
                        val name = rel?.fileName?.toString().orEmpty()
                        if (kind == OVERFLOW || (name.endsWith(".json", ignoreCase = true) && !name.startsWith("."))) {
                            relevant = true
                        }
                    }
                    key.reset()
                    if (relevant) {
                        val now = System.currentTimeMillis()
                        if (now - lastSelfWriteMs > 800L) {
                            pendingTriggerAt = now + 250L
                        }
                    }
                } else if (pendingTriggerAt > 0L && System.currentTimeMillis() >= pendingTriggerAt) {
                    pendingTriggerAt = 0L
                    onExternalChange()
                }
            }
        }
    }

    private fun readExistingFileMap(directory: Path): Map<String, Path> {
        if (!Files.isDirectory(directory)) return emptyMap()
        val result = linkedMapOf<String, Path>()
        runCatching {
            Files.list(directory).use { stream ->
                stream.filter {
                    Files.isRegularFile(it) &&
                        !it.fileName.toString().startsWith(".") &&
                        it.extension.equals("json", ignoreCase = true)
                }.forEach { file ->
                    val raw = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull()
                    val parsed = raw?.let { runCatching { json.decodeFromString<PortablePlaylistFile>(it) }.getOrNull() }
                    val id = parsed?.id?.trim().orEmpty().ifBlank { "file_${sanitiseFileName(file.nameWithoutExtension)}" }
                    result[id] = file
                }
            }
        }
        return result
    }

    private fun Song.toPortable(): PortablePlaylistSong {
        val derivedFileName = localPath?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { File(raw).name }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return PortablePlaylistSong(
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
            fileName = derivedFileName,
            isVideo = isVideo,
            isVideoOrigin = isVideoOrigin,
            setVideoId = setVideoId,
            fromAutoplay = fromAutoplay,
            localDateAddedSeconds = localDateAddedSeconds,
            localDateModifiedSeconds = localDateModifiedSeconds,
            sourceQuality = sourceQuality,
            isExplicit = isExplicit,
            trackNumber = trackNumber,
        )
    }

    private fun resolveSong(dto: PortablePlaylistSong, localSongs: () -> List<Song>): Song {
        val rawPath = dto.localPath?.takeIf { it.isNotBlank() }
        val existingPath = rawPath?.takeIf { runCatching { Files.isRegularFile(Path.of(it)) }.getOrDefault(false) }
        var resolvedPath = existingPath
        var resolvedVideoId = dto.videoId
        var resolvedThumbnail = dto.thumbnailUrl

        val isLocalRef = dto.videoId.startsWith("local:") || rawPath != null || !dto.fileName.isNullOrBlank()
        if (resolvedPath == null && isLocalRef) {
            val wantedFileName = dto.fileName?.takeIf { it.isNotBlank() }
                ?: rawPath?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            val matched = localSongs().firstOrNull { candidate ->
                val candFile = candidate.localPath?.let { File(it).name }
                (wantedFileName != null && candFile != null && candFile.equals(wantedFileName, ignoreCase = true)) ||
                    (candidate.title.equals(dto.title, ignoreCase = true) &&
                        (dto.artist.isBlank() || candidate.artist.equals(dto.artist, ignoreCase = true)))
            }
            if (matched != null) {
                resolvedPath = matched.localPath
                if (resolvedVideoId.isBlank() || resolvedVideoId.startsWith("local:")) {
                    resolvedVideoId = matched.videoId
                }
                if (resolvedThumbnail.isNullOrBlank()) {
                    resolvedThumbnail = matched.thumbnailUrl
                }
            } else {
                resolvedPath = rawPath
            }
        }

        val finalVideoId = resolvedVideoId.ifBlank {
            resolvedPath?.let { "local:$it" }
                ?: "local:${dto.title}:${dto.artist}"
        }
        return Song(
            videoId = finalVideoId,
            title = dto.title,
            artist = dto.artist,
            thumbnailUrl = resolvedThumbnail,
            durationText = dto.durationText,
            artistId = dto.artistId,
            albumId = dto.albumId,
            albumName = dto.albumName,
            localUri = dto.localUri,
            localPath = resolvedPath,
            isVideo = dto.isVideo,
            isVideoOrigin = dto.isVideoOrigin,
            setVideoId = dto.setVideoId,
            fromAutoplay = dto.fromAutoplay,
            localDateAddedSeconds = dto.localDateAddedSeconds,
            localDateModifiedSeconds = dto.localDateModifiedSeconds,
            sourceQuality = dto.sourceQuality,
            isExplicit = dto.isExplicit,
            trackNumber = dto.trackNumber,
        )
    }
}

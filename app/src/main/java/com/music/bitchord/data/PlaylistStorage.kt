package com.music.bitchord.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class StoredPlaylist(
    val id: String,
    val title: String,
    val kind: String = PlaylistStorage.KIND_LOCAL,
    val updatedAt: Long = 0L,
    val sortOrder: Int = 0,
    val songs: List<Song> = emptyList(),
)

@Serializable
internal data class PortablePlaylistSongDto(
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
internal data class PortablePlaylistFileDto(
    val id: String = "",
    val title: String = "",
    val kind: String = PlaylistStorage.KIND_LOCAL,
    val updatedAt: Long = 0L,
    val sortOrder: Int = 0,
    val songs: List<PortablePlaylistSongDto> = emptyList(),
)

/**
 * Shared public-storage persistence for Android local playlists inside
 * `/storage/emulated/0/LuminaChord/Playlists`.
 *
 * Uses the exact same per-playlist `.json` schema as Desktop (`C:\LuminaChord\Playlists`) so
 * playlists survive app uninstalls and sync cleanly between Android and Windows.
 */
object PlaylistStorage {
    const val DEFAULT_PUBLIC_RELATIVE_PATH = "LuminaChord/Playlists"
    const val DEFAULT_PUBLIC_PATH_LABEL = "/storage/emulated/0/LuminaChord/Playlists"

    internal const val KIND_LOCAL = "local"
    internal const val KIND_OFFLINE = "offline"

    private val illegalFileChars = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    private val whitespaceRuns = Regex("\\s+")
    private const val MAX_STEM_CHARS = 96

    private val fileJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun defaultPublicDirectory(): File =
        File(Environment.getExternalStorageDirectory(), DEFAULT_PUBLIC_RELATIVE_PATH)

    fun needsAllFilesPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasWriteAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
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
        return cleaned.ifBlank { "Playlist" }
    }

    private fun Song.toPortableDto(): PortablePlaylistSongDto {
        val safeVideoId = videoId.ifBlank {
            localPath?.let { "local:$it" }
                ?: localUri?.let { "local:$it" }
                ?: "local:$title:$artist"
        }
        val derivedFileName = localPath?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { File(raw).name }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return PortablePlaylistSongDto(
            videoId = safeVideoId,
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

    private fun PortablePlaylistSongDto.toSong(): Song? {
        val cleanLocalPath = localPath?.takeIf { it.isNotBlank() }
        val cleanLocalUri = localUri?.takeIf { it.isNotBlank() }
        val resolvedVideoId = videoId.ifBlank {
            cleanLocalUri ?: cleanLocalPath?.let { "local:$it" } ?: "local:$title:$artist"
        }
        if (resolvedVideoId.isBlank() && title.isBlank()) return null
        return Song(
            videoId = resolvedVideoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
            durationText = durationText?.takeIf { it.isNotBlank() },
            artistId = artistId?.takeIf { it.isNotBlank() },
            albumId = albumId?.takeIf { it.isNotBlank() },
            albumName = albumName?.takeIf { it.isNotBlank() },
            localUri = cleanLocalUri,
            localPath = cleanLocalPath,
            isVideo = isVideo,
            isVideoOrigin = isVideoOrigin,
            setVideoId = setVideoId?.takeIf { it.isNotBlank() },
            fromAutoplay = fromAutoplay,
            localDateAddedSeconds = localDateAddedSeconds,
            localDateModifiedSeconds = localDateModifiedSeconds,
            sourceQuality = sourceQuality?.takeIf { it.isNotBlank() },
            isExplicit = isExplicit,
            trackNumber = trackNumber,
        )
    }

    internal fun songToJson(song: Song): JSONObject = JSONObject().apply {
        val dto = song.toPortableDto()
        put("videoId", dto.videoId)
        put("title", dto.title)
        put("artist", dto.artist)
        putOpt("thumbnailUrl", dto.thumbnailUrl)
        putOpt("durationText", dto.durationText)
        putOpt("artistId", dto.artistId)
        putOpt("albumId", dto.albumId)
        putOpt("albumName", dto.albumName)
        putOpt("localUri", dto.localUri)
        putOpt("localPath", dto.localPath)
        putOpt("fileName", dto.fileName)
        put("isVideo", dto.isVideo)
        put("isVideoOrigin", dto.isVideoOrigin)
        putOpt("setVideoId", dto.setVideoId)
        put("fromAutoplay", dto.fromAutoplay)
        dto.localDateAddedSeconds?.let { put("localDateAddedSeconds", it) }
        dto.localDateModifiedSeconds?.let { put("localDateModifiedSeconds", it) }
        putOpt("sourceQuality", dto.sourceQuality)
        dto.isExplicit?.let { put("isExplicit", it) }
        dto.trackNumber?.let { put("trackNumber", it) }
    }

    internal fun songFromJson(obj: JSONObject, requireLocalUri: Boolean = false): Song? {
        fun JSONObject.optText(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

        val localUri = obj.optText("localUri")
        if (requireLocalUri && localUri == null) return null

        val localPath = obj.optText("localPath")
        val videoId = obj.optText("videoId")
            ?: localUri
            ?: localPath?.let { "local:$it" }
            ?: return null
        val title = obj.optString("title", "")
        if (title.isBlank() && videoId.isBlank()) return null

        val isVideo = obj.optBoolean("isVideo", false)
        val added = when {
            obj.has("localDateAddedSeconds") && !obj.isNull("localDateAddedSeconds") -> obj.optLong("localDateAddedSeconds")
            obj.has("added") && !obj.isNull("added") -> obj.optLong("added")
            else -> null
        }
        val modified = when {
            obj.has("localDateModifiedSeconds") && !obj.isNull("localDateModifiedSeconds") -> obj.optLong("localDateModifiedSeconds")
            obj.has("modified") && !obj.isNull("modified") -> obj.optLong("modified")
            else -> null
        }
        val track = when {
            obj.has("trackNumber") && !obj.isNull("trackNumber") -> obj.optInt("trackNumber")
            obj.has("track") && !obj.isNull("track") -> obj.optInt("track")
            else -> null
        }
        val explicit = if (obj.has("isExplicit") && !obj.isNull("isExplicit")) obj.optBoolean("isExplicit") else null

        return Song(
            videoId = videoId,
            title = title,
            artist = obj.optString("artist", ""),
            thumbnailUrl = obj.optText("thumbnailUrl"),
            durationText = obj.optText("durationText"),
            artistId = obj.optText("artistId"),
            albumId = obj.optText("albumId"),
            albumName = obj.optText("albumName"),
            localUri = localUri,
            localPath = localPath,
            isVideo = isVideo,
            isVideoOrigin = if (obj.has("isVideoOrigin") && !obj.isNull("isVideoOrigin")) obj.optBoolean("isVideoOrigin") else isVideo,
            setVideoId = obj.optText("setVideoId"),
            fromAutoplay = obj.optBoolean("fromAutoplay", false),
            localDateAddedSeconds = added,
            localDateModifiedSeconds = modified,
            sourceQuality = obj.optText("sourceQuality"),
            isExplicit = explicit,
            trackNumber = track,
        )
    }

    internal fun resolveSong(
        song: Song,
        fileNameHint: String? = null,
        localCatalogProvider: () -> List<Song>,
    ): Song {
        val rawPath = song.localPath?.takeIf { it.isNotBlank() }
        val fileExists = rawPath != null && runCatching { File(rawPath).isFile }.getOrDefault(false)
        val songUri = song.localUri
        val hasValidLocalUri = !songUri.isNullOrBlank() && (
            !songUri.startsWith("file://") || fileExists
            )
        if (hasValidLocalUri && fileExists) return song

        val isLocalRef = song.videoId.startsWith("local:") ||
            rawPath != null ||
            !fileNameHint.isNullOrBlank() ||
            !songUri.isNullOrBlank()
        if (!isLocalRef) return song

        val wantedName = fileNameHint?.takeIf { it.isNotBlank() }
            ?: rawPath?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
        val matched = localCatalogProvider().firstOrNull { candidate ->
            val candName = candidate.localPath?.let { File(it).name }
            (song.videoId.startsWith("local:") && candidate.videoId == song.videoId) ||
                (wantedName != null && candName != null && candName.equals(wantedName, ignoreCase = true)) ||
                (candidate.title.equals(song.title, ignoreCase = true) &&
                    (song.artist.isBlank() || candidate.artist.equals(song.artist, ignoreCase = true)))
        } ?: return song

        return song.copy(
            videoId = if (song.videoId.isBlank() || song.videoId.startsWith("local:")) matched.videoId else song.videoId,
            localUri = matched.localUri ?: song.localUri,
            localPath = matched.localPath ?: song.localPath,
            thumbnailUrl = song.thumbnailUrl ?: matched.thumbnailUrl,
            durationText = song.durationText ?: matched.durationText,
        )
    }

    internal fun readDirectory(
        directory: File = defaultPublicDirectory(),
        kindFilter: String? = null,
    ): List<StoredPlaylist> {
        if (!directory.isDirectory) return emptyList()
        val files = directory.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.equals("json", ignoreCase = true)
        } ?: return emptyList()

        val parsed = mutableListOf<StoredPlaylist>()
        for (file in files) {
            val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
            val dto = runCatching { fileJson.decodeFromString<PortablePlaylistFileDto>(raw) }.getOrNull() ?: continue
            val rawKind = dto.kind.trim().lowercase(Locale.ROOT).ifBlank { KIND_LOCAL }
            if (kindFilter != null) {
                val matchesKind = when (kindFilter) {
                    KIND_OFFLINE -> rawKind == KIND_OFFLINE
                    else -> rawKind != KIND_OFFLINE
                }
                if (!matchesKind) continue
            }
            val id = dto.id.trim().ifBlank { "file_${sanitiseFileName(file.nameWithoutExtension)}" }
            val title = dto.title.trim().ifBlank { file.nameWithoutExtension.ifBlank { "Playlist" } }
            val updatedAt = dto.updatedAt.takeIf { it > 0L } ?: file.lastModified()
            parsed += StoredPlaylist(
                id = id,
                title = title,
                kind = rawKind,
                updatedAt = updatedAt,
                sortOrder = dto.sortOrder,
                songs = dto.songs.mapNotNull { it.toSong() },
            )
        }

        val dedup = linkedMapOf<String, StoredPlaylist>()
        for (item in parsed) {
            val prev = dedup[item.id]
            if (prev == null || item.updatedAt > prev.updatedAt) {
                dedup[item.id] = item
            }
        }
        return dedup.values.sortedWith(
            compareBy<StoredPlaylist> { it.sortOrder }
                .thenByDescending { it.updatedAt }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
    }

    internal fun saveKind(
        playlists: List<StoredPlaylist>,
        kind: String,
        directory: File = defaultPublicDirectory(),
    ): Boolean {
        if (!directory.exists() && !directory.mkdirs()) return false
        if (!directory.isDirectory) return false

        val existingFiles = directory.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.equals("json", ignoreCase = true)
        }.orEmpty()

        val otherKindStems = HashSet<String>()
        val existingByIdForKind = linkedMapOf<String, File>()
        for (file in existingFiles) {
            val parsed = runCatching {
                fileJson.decodeFromString<PortablePlaylistFileDto>(file.readText(StandardCharsets.UTF_8))
            }.getOrNull()
            val fileKind = parsed?.kind?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { KIND_LOCAL }
            val isSameKind = if (kind == KIND_OFFLINE) fileKind == KIND_OFFLINE else fileKind != KIND_OFFLINE
            if (isSameKind) {
                val id = parsed?.id?.trim().orEmpty()
                    .ifBlank { "file_${sanitiseFileName(file.nameWithoutExtension)}" }
                existingByIdForKind[id] = file
            } else {
                otherKindStems += file.nameWithoutExtension.lowercase(Locale.ROOT)
            }
        }

        val now = System.currentTimeMillis()
        val claimedStems = HashSet<String>(otherKindStems)
        val activeIds = HashSet<String>(playlists.size)

        playlists.forEachIndexed { index, playlist ->
            activeIds += playlist.id
            val safeId = playlist.id.replace(illegalFileChars, "_").take(12).ifBlank { index.toString() }
            val baseStem = sanitiseFileName(playlist.title)
            val chosenStem = if (claimedStems.add(baseStem.lowercase(Locale.ROOT))) {
                baseStem
            } else {
                val unique = "${baseStem}_${safeId}".take(MAX_STEM_CHARS + 16)
                claimedStems.add(unique.lowercase(Locale.ROOT))
                unique
            }
            val targetFile = File(directory, "$chosenStem.json")
            val previousFile = existingByIdForKind[playlist.id]

            val dto = PortablePlaylistFileDto(
                id = playlist.id,
                title = playlist.title.trim().ifBlank { "Playlist" },
                kind = kind,
                updatedAt = now,
                sortOrder = index,
                songs = playlist.songs.map { it.toPortableDto() },
            )
            val encoded = fileJson.encodeToString(dto)

            val tmpFile = File(directory, ".${chosenStem}.${System.nanoTime()}.tmp")
            val ok = runCatching {
                tmpFile.writeText(encoded, StandardCharsets.UTF_8)
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    targetFile.writeText(encoded, StandardCharsets.UTF_8)
                    tmpFile.delete()
                }
                true
            }.getOrElse {
                tmpFile.delete()
                false
            }
            if (!ok) return false

            if (previousFile != null && previousFile.absolutePath != targetFile.absolutePath) {
                runCatching { previousFile.delete() }
            }
        }

        existingByIdForKind.forEach { (id, file) ->
            if (id !in activeIds) {
                runCatching { file.delete() }
            }
        }
        return true
    }
}

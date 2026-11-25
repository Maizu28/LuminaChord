package com.music.bitchord.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Extracts and caches embedded and companion cover art and tags from local audio files.
 */
object DesktopLocalArtwork {

    data class AudioTags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNumber: Int? = null,
        val artworkPath: String? = null,
    )

    val cacheDirectory: Path by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?: System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.home")}/.cache"
        Path.of(base, "LuminaChord", "cache", "artwork").also {
            runCatching { Files.createDirectories(it) }
        }
    }

    /**
     * Reads tags (title, artist, album, track number, embedded/companion artwork) from a local audio file.
     */
    fun readTags(path: Path): AudioTags? {
        val file = path.toFile()
        if (!file.exists() || !file.canRead() || file.length() < 128) return null

        return runCatching {
            val ext = path.extension.lowercase()
            when (ext) {
                "mp3" -> readId3Tags(path, file)
                "flac" -> readFlacTags(path, file)
                "m4a", "mp4", "aac" -> readMp4Tags(path, file)
                "ogg", "opus" -> readOggTags(path, file)
                else -> {
                    val art = resolveArtwork(path, null, null)
                    AudioTags(artworkPath = art)
                }
            }
        }.getOrNull()
    }

    /**
     * Resolves artwork for an audio file without re-extracting full tags:
     * 1. Checks companion files in folder / sibling cover_art folder.
     * 2. Checks cached extracted artwork.
     * 3. Extracts embedded picture if present.
     */
    fun resolveArtwork(path: Path, title: String? = null, artist: String? = null): String? {
        val file = path.toFile()
        if (!file.exists() || !file.canRead() || file.length() < 128) return null

        // 1. Companion artwork (fastest, keeps original high-res folder image)
        findCompanionArtwork(path, title, artist)?.let { return it }

        // 2. Extracted artwork cache
        val cacheKey = hashPath(path, file.lastModified())
        val cachedFile = cacheDirectory.resolve("$cacheKey.art").toFile()
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return cachedFile.absolutePath
        }

        // 3. Extract embedded artwork
        val extractedBytes = extractEmbeddedBytes(path, file) ?: return null
        return runCatching {
            cachedFile.writeBytes(extractedBytes)
            cachedFile.absolutePath
        }.getOrNull()
    }

    private fun findCompanionArtwork(path: Path, title: String?, artist: String?): String? {
        val parent = path.parent ?: return null
        val nameWithoutExt = path.nameWithoutExtension

        val nameCandidates = mutableListOf(
            nameWithoutExt,
            "cover",
            "folder",
            "front",
            "album",
            "artwork",
        )
        if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
            nameCandidates.add("$artist - $title")
            nameCandidates.add(title)
        }

        val extensions = listOf("jpg", "jpeg", "png", "webp")
        val illegalChars = Regex("[\\\\/:*?\"<>|]")

        // Check same directory
        for (rawName in nameCandidates) {
            val name = rawName.replace(illegalChars, "_")
            for (ext in extensions) {
                val candidate = runCatching { parent.resolve("$name.$ext") }.getOrNull() ?: continue
                if (runCatching { Files.isRegularFile(candidate) && Files.isReadable(candidate) }.getOrDefault(false)) {
                    return candidate.toAbsolutePath().toString()
                }
            }
        }

        // Check sibling or parent directories (e.g. C:\Lagu\cover_art)
        val searchDirs = listOfNotNull(
            runCatching { parent.resolve("cover_art") }.getOrNull(),
            runCatching { parent.resolve("covers") }.getOrNull(),
            runCatching { parent.resolve("artwork") }.getOrNull(),
            runCatching { parent.parent?.resolve("cover_art") }.getOrNull(),
            runCatching { parent.parent?.resolve("covers") }.getOrNull(),
            runCatching { parent.parent?.resolve("artwork") }.getOrNull(),
            runCatching { parent.parent?.resolve("albumart") }.getOrNull(),
        )

        for (dir in searchDirs) {
            if (runCatching { Files.isDirectory(dir) }.getOrDefault(false)) {
                for (rawName in nameCandidates) {
                    val name = rawName.replace(illegalChars, "_")
                    for (ext in extensions) {
                        val candidate = runCatching { dir.resolve("$name.$ext") }.getOrNull() ?: continue
                        if (runCatching { Files.isRegularFile(candidate) && Files.isReadable(candidate) }.getOrDefault(false)) {
                            return candidate.toAbsolutePath().toString()
                        }
                    }
                }
            }
        }

        return null
    }

    private fun extractEmbeddedBytes(path: Path, file: File): ByteArray? {
        return runCatching {
            val ext = path.extension.lowercase()
            when (ext) {
                "mp3" -> extractId3ArtworkBytes(file)
                "flac" -> extractFlacArtworkBytes(file)
                "m4a", "mp4", "aac" -> extractMp4ArtworkBytes(file)
                "ogg", "opus" -> extractOggArtworkBytes(file)
                else -> null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------------------------------------
    // MP3 (ID3v2) Parser
    // ---------------------------------------------------------------------------------------------

    private fun readId3Tags(path: Path, file: File): AudioTags? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) return null
            val header = ByteArray(10)
            raf.readFully(header)
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                val companion = findCompanionArtwork(path, null, null)
                return AudioTags(artworkPath = companion)
            }

            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = synchsafeInt(header, 6)
            if (tagSize <= 0) return null

            val toRead = minOf(tagSize, 15_000_000)
            val tagBytes = ByteArray(toRead)
            raf.readFully(tagBytes)

            var offset = 0
            if (version >= 3 && (flags and 0x40) != 0) {
                // Extended header
                val extSize = if (version == 4) synchsafeInt(tagBytes, 0) else readInt32(tagBytes, 0)
                offset += maxOf(extSize, 4)
            }

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var trackNumber: Int? = null
            var artworkBytes: ByteArray? = null

            if (version == 2) {
                // ID3v2.2 (6-byte frame header)
                while (offset + 6 <= tagBytes.size) {
                    if (tagBytes[offset].toInt() == 0) break
                    val frameId = String(tagBytes, offset, 3, StandardCharsets.US_ASCII)
                    val frameSize = readInt24(tagBytes, offset + 3)
                    if (frameSize <= 0 || offset + 6 + frameSize > tagBytes.size) break
                    val payload = tagBytes.copyOfRange(offset + 6, offset + 6 + frameSize)
                    offset += 6 + frameSize

                    when (frameId) {
                        "TT2" -> title = decodeId3Text(payload)
                        "TP1" -> artist = decodeId3Text(payload)
                        "TAL" -> album = decodeId3Text(payload)
                        "TRK" -> trackNumber = parseTrackNumber(decodeId3Text(payload))
                        "PIC" -> if (artworkBytes == null) artworkBytes = findImageBytes(payload)
                    }
                }
            } else {
                // ID3v2.3 / ID3v2.4 (10-byte frame header)
                while (offset + 10 <= tagBytes.size) {
                    if (tagBytes[offset].toInt() == 0) break
                    val frameId = String(tagBytes, offset, 4, StandardCharsets.US_ASCII)
                    val rawSize = if (version == 4) synchsafeInt(tagBytes, offset + 4) else readInt32(tagBytes, offset + 4)
                    val frameSize = if (rawSize > 0 && offset + 10 + rawSize <= tagBytes.size) {
                        rawSize
                    } else {
                        // Some non-standard v2.4 encoders use raw 32-bit int instead of synchsafe
                        val fallback = readInt32(tagBytes, offset + 4)
                        if (fallback > 0 && offset + 10 + fallback <= tagBytes.size) fallback else rawSize
                    }

                    if (frameSize <= 0 || offset + 10 + frameSize > tagBytes.size) break
                    val payload = tagBytes.copyOfRange(offset + 10, offset + 10 + frameSize)
                    offset += 10 + frameSize

                    when (frameId) {
                        "TIT2" -> title = decodeId3Text(payload)
                        "TPE1" -> artist = decodeId3Text(payload)
                        "TALB" -> album = decodeId3Text(payload)
                        "TRCK" -> trackNumber = parseTrackNumber(decodeId3Text(payload))
                        "APIC" -> if (artworkBytes == null) artworkBytes = findImageBytes(payload)
                    }
                }
            }

            var artworkPath = findCompanionArtwork(path, title, artist)
            if (artworkPath == null && artworkBytes != null && artworkBytes.isNotEmpty()) {
                val cacheKey = hashPath(path, file.lastModified())
                val cachedFile = cacheDirectory.resolve("$cacheKey.art").toFile()
                runCatching {
                    cachedFile.writeBytes(artworkBytes)
                    artworkPath = cachedFile.absolutePath
                }
            }

            return AudioTags(
                title = title?.takeIf(String::isNotBlank),
                artist = artist?.takeIf(String::isNotBlank),
                album = album?.takeIf(String::isNotBlank),
                trackNumber = trackNumber,
                artworkPath = artworkPath,
            )
        }
    }

    private fun extractId3ArtworkBytes(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) return null
            val header = ByteArray(10)
            raf.readFully(header)
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null

            val version = header[3].toInt() and 0xFF
            val tagSize = synchsafeInt(header, 6)
            if (tagSize <= 0) return null

            val toRead = minOf(tagSize, 15_000_000)
            val tagBytes = ByteArray(toRead)
            raf.readFully(tagBytes)

            var offset = 0
            if (version == 2) {
                while (offset + 6 <= tagBytes.size) {
                    if (tagBytes[offset].toInt() == 0) break
                    val frameId = String(tagBytes, offset, 3, StandardCharsets.US_ASCII)
                    val frameSize = readInt24(tagBytes, offset + 3)
                    if (frameSize <= 0 || offset + 6 + frameSize > tagBytes.size) break
                    if (frameId == "PIC") {
                        val payload = tagBytes.copyOfRange(offset + 6, offset + 6 + frameSize)
                        return findImageBytes(payload)
                    }
                    offset += 6 + frameSize
                }
            } else {
                while (offset + 10 <= tagBytes.size) {
                    if (tagBytes[offset].toInt() == 0) break
                    val frameId = String(tagBytes, offset, 4, StandardCharsets.US_ASCII)
                    val frameSize = readInt32(tagBytes, offset + 4).let {
                        if (it in 1..(tagBytes.size - offset - 10)) it else synchsafeInt(tagBytes, offset + 4)
                    }
                    if (frameSize <= 0 || offset + 10 + frameSize > tagBytes.size) break
                    if (frameId == "APIC") {
                        val payload = tagBytes.copyOfRange(offset + 10, offset + 10 + frameSize)
                        return findImageBytes(payload)
                    }
                    offset += 10 + frameSize
                }
            }
        }
        return null
    }

    private fun decodeId3Text(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val textBytes = payload.copyOfRange(1, payload.size)
        return runCatching {
            val raw = when (encoding) {
                1 -> String(textBytes, StandardCharsets.UTF_16)
                2 -> String(textBytes, StandardCharsets.UTF_16BE)
                3 -> String(textBytes, StandardCharsets.UTF_8)
                else -> String(textBytes, StandardCharsets.ISO_8859_1)
            }
            raw.trim('\u0000', ' ', '\t', '\n', '\r')
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun parseTrackNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val part = text.substringBefore('/').trim()
        return part.toIntOrNull()
    }

    // ---------------------------------------------------------------------------------------------
    // FLAC Parser
    // ---------------------------------------------------------------------------------------------

    private fun readFlacTags(path: Path, file: File): AudioTags? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) return null
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, StandardCharsets.US_ASCII) != "fLaC") return null

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var trackNumber: Int? = null
            var artworkBytes: ByteArray? = null

            var isLast = false
            while (!isLast && raf.filePointer + 4 <= raf.length()) {
                val blockHeader = ByteArray(4)
                raf.readFully(blockHeader)
                val byte0 = blockHeader[0].toInt() and 0xFF
                isLast = (byte0 and 0x80) != 0
                val blockType = byte0 and 0x7F
                val length = readInt24(blockHeader, 1)
                if (length < 0 || raf.filePointer + length > raf.length()) break

                when (blockType) {
                    4 -> { // VORBIS_COMMENT
                        val commentBytes = ByteArray(length)
                        raf.readFully(commentBytes)
                        val comments = parseVorbisComments(commentBytes)
                        if (title == null) title = comments["TITLE"]
                        if (artist == null) artist = comments["ARTIST"]
                        if (album == null) album = comments["ALBUM"]
                        if (trackNumber == null) trackNumber = comments["TRACKNUMBER"]?.toIntOrNull()
                        if (artworkBytes == null && comments["METADATA_BLOCK_PICTURE"] != null) {
                            artworkBytes = runCatching {
                                parseFlacPicture(Base64.getDecoder().decode(comments["METADATA_BLOCK_PICTURE"]))
                            }.getOrNull()
                        }
                    }
                    6 -> { // PICTURE
                        val pictureBlockBytes = ByteArray(length)
                        raf.readFully(pictureBlockBytes)
                        if (artworkBytes == null) {
                            artworkBytes = parseFlacPicture(pictureBlockBytes)
                        }
                    }
                    else -> raf.skipBytes(length)
                }
            }

            var artworkPath = findCompanionArtwork(path, title, artist)
            if (artworkPath == null && artworkBytes != null && artworkBytes.isNotEmpty()) {
                val cacheKey = hashPath(path, file.lastModified())
                val cachedFile = cacheDirectory.resolve("$cacheKey.art").toFile()
                runCatching {
                    cachedFile.writeBytes(artworkBytes)
                    artworkPath = cachedFile.absolutePath
                }
            }

            return AudioTags(
                title = title?.takeIf(String::isNotBlank),
                artist = artist?.takeIf(String::isNotBlank),
                album = album?.takeIf(String::isNotBlank),
                trackNumber = trackNumber,
                artworkPath = artworkPath,
            )
        }
    }

    private fun extractFlacArtworkBytes(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) return null
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, StandardCharsets.US_ASCII) != "fLaC") return null

            var isLast = false
            while (!isLast && raf.filePointer + 4 <= raf.length()) {
                val blockHeader = ByteArray(4)
                raf.readFully(blockHeader)
                val byte0 = blockHeader[0].toInt() and 0xFF
                isLast = (byte0 and 0x80) != 0
                val blockType = byte0 and 0x7F
                val length = readInt24(blockHeader, 1)
                if (length < 0 || raf.filePointer + length > raf.length()) break

                if (blockType == 6) {
                    val pictureBlockBytes = ByteArray(length)
                    raf.readFully(pictureBlockBytes)
                    return parseFlacPicture(pictureBlockBytes)
                } else if (blockType == 4) {
                    val commentBytes = ByteArray(length)
                    raf.readFully(commentBytes)
                    val comments = parseVorbisComments(commentBytes)
                    comments["METADATA_BLOCK_PICTURE"]?.let { b64 ->
                        return runCatching { parseFlacPicture(Base64.getDecoder().decode(b64)) }.getOrNull()
                    }
                } else {
                    raf.skipBytes(length)
                }
            }
        }
        return null
    }

    private fun parseFlacPicture(data: ByteArray): ByteArray? {
        if (data.size < 32) return null
        var pos = 0
        pos += 4 // picture type
        if (pos + 4 > data.size) return null
        val mimeLen = readInt32(data, pos)
        pos += 4 + mimeLen
        if (pos + 4 > data.size) return null
        val descLen = readInt32(data, pos)
        pos += 4 + descLen
        pos += 16 // width, height, depth, colors
        if (pos + 4 > data.size) return null
        val dataLen = readInt32(data, pos)
        pos += 4
        if (dataLen > 0 && pos + dataLen <= data.size) {
            return data.copyOfRange(pos, pos + dataLen)
        }
        return findImageBytes(data)
    }

    private fun parseVorbisComments(data: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (data.size < 8) return map
        var pos = 0
        val vendorLen = readInt32LittleEndian(data, pos)
        pos += 4 + vendorLen
        if (pos + 4 > data.size) return map
        val commentCount = readInt32LittleEndian(data, pos)
        pos += 4

        for (i in 0 until commentCount) {
            if (pos + 4 > data.size) break
            val length = readInt32LittleEndian(data, pos)
            pos += 4
            if (length <= 0 || pos + length > data.size) break
            val str = String(data, pos, length, StandardCharsets.UTF_8)
            pos += length
            val equalIdx = str.indexOf('=')
            if (equalIdx > 0) {
                val key = str.substring(0, equalIdx).uppercase()
                val value = str.substring(equalIdx + 1)
                map[key] = value
            }
        }
        return map
    }

    // ---------------------------------------------------------------------------------------------
    // MP4 / M4A Parser
    // ---------------------------------------------------------------------------------------------

    private fun readMp4Tags(path: Path, file: File): AudioTags? {
        val companion = findCompanionArtwork(path, null, null)
        val extractedArt = extractMp4ArtworkBytes(file)
        var artworkPath = companion
        if (artworkPath == null && extractedArt != null) {
            val cacheKey = hashPath(path, file.lastModified())
            val cachedFile = cacheDirectory.resolve("$cacheKey.art").toFile()
            runCatching {
                cachedFile.writeBytes(extractedArt)
                artworkPath = cachedFile.absolutePath
            }
        }
        return AudioTags(artworkPath = artworkPath)
    }

    private fun extractMp4ArtworkBytes(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(raf.length(), 20_000_000L).toInt()
            val bytes = ByteArray(len)
            raf.readFully(bytes)
            val covrIdx = indexOfAscii(bytes, "covr")
            if (covrIdx >= 0 && covrIdx + 24 < bytes.size) {
                // Inside covr atom, find data atom
                val dataIdx = indexOfAscii(bytes, "data", covrIdx)
                if (dataIdx in covrIdx..(covrIdx + 30)) {
                    val dataSize = readInt32(bytes, dataIdx - 4)
                    val imgStart = dataIdx + 12
                    val imgEnd = dataIdx - 4 + dataSize
                    if (dataSize > 16 && imgEnd <= bytes.size) {
                        return bytes.copyOfRange(imgStart, imgEnd)
                    }
                }
                return findImageBytes(bytes.copyOfRange(covrIdx, minOf(bytes.size, covrIdx + 5_000_000)))
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // OGG / OPUS Parser
    // ---------------------------------------------------------------------------------------------

    private fun readOggTags(path: Path, file: File): AudioTags? {
        val companion = findCompanionArtwork(path, null, null)
        val extractedArt = extractOggArtworkBytes(file)
        var artworkPath = companion
        if (artworkPath == null && extractedArt != null) {
            val cacheKey = hashPath(path, file.lastModified())
            val cachedFile = cacheDirectory.resolve("$cacheKey.art").toFile()
            runCatching {
                cachedFile.writeBytes(extractedArt)
                artworkPath = cachedFile.absolutePath
            }
        }
        return AudioTags(artworkPath = artworkPath)
    }

    private fun extractOggArtworkBytes(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(raf.length(), 2_000_000L).toInt()
            val bytes = ByteArray(len)
            raf.readFully(bytes)
            val marker = "METADATA_BLOCK_PICTURE="
            val idx = indexOfAscii(bytes, marker)
            if (idx >= 0) {
                val start = idx + marker.length
                var end = start
                while (end < bytes.size && bytes[end] > 32) end++
                val b64 = String(bytes, start, end - start, StandardCharsets.US_ASCII)
                return runCatching { parseFlacPicture(Base64.getDecoder().decode(b64)) }.getOrNull()
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Binary Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Scans payload for image magic header (JPEG, PNG, WebP, BMP, GIF) and returns the full image bytes.
     */
    fun findImageBytes(payload: ByteArray): ByteArray? {
        if (payload.size < 16) return null
        val limit = minOf(payload.size - 4, 300)
        for (i in 0..limit) {
            // JPEG: FF D8 FF
            if ((payload[i].toInt() and 0xFF) == 0xFF &&
                (payload[i + 1].toInt() and 0xFF) == 0xD8 &&
                (payload[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                return payload.copyOfRange(i, payload.size)
            }
            // PNG: 89 50 4E 47
            if ((payload[i].toInt() and 0xFF) == 0x89 &&
                payload[i + 1] == 'P'.code.toByte() &&
                payload[i + 2] == 'N'.code.toByte() &&
                payload[i + 3] == 'G'.code.toByte()
            ) {
                return payload.copyOfRange(i, payload.size)
            }
            // WEBP: RIFF .... WEBP
            if (payload[i] == 'R'.code.toByte() &&
                payload[i + 1] == 'I'.code.toByte() &&
                payload[i + 2] == 'F'.code.toByte() &&
                payload[i + 3] == 'F'.code.toByte() &&
                i + 11 < payload.size &&
                payload[i + 8] == 'W'.code.toByte() &&
                payload[i + 9] == 'E'.code.toByte() &&
                payload[i + 10] == 'B'.code.toByte() &&
                payload[i + 11] == 'P'.code.toByte()
            ) {
                return payload.copyOfRange(i, payload.size)
            }
            // GIF: GIF8
            if (payload[i] == 'G'.code.toByte() &&
                payload[i + 1] == 'I'.code.toByte() &&
                payload[i + 2] == 'F'.code.toByte() &&
                payload[i + 3] == '8'.code.toByte()
            ) {
                return payload.copyOfRange(i, payload.size)
            }
            // BMP: BM
            if (payload[i] == 'B'.code.toByte() && payload[i + 1] == 'M'.code.toByte() && i + 14 < payload.size) {
                return payload.copyOfRange(i, payload.size)
            }
        }
        return null
    }

    private fun synchsafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readInt24(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private fun readInt32LittleEndian(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun indexOfAscii(bytes: ByteArray, target: String, startOffset: Int = 0): Int {
        val targetBytes = target.toByteArray(StandardCharsets.US_ASCII)
        val max = bytes.size - targetBytes.size
        for (i in startOffset..max) {
            var match = true
            for (j in targetBytes.indices) {
                if (bytes[i + j] != targetBytes[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun hashPath(path: Path, lastModified: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "${path.toAbsolutePath()}|$lastModified"
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

package com.music.bitchord.download

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Prepends an ID3v2.3 tag — title, artist, album, lyrics (`USLT`), cover (`APIC`) — to an MP3
 * byte stream (replacing any existing leading ID3v2 header if present).
 *
 * Tested against [EmbeddedLyrics][com.music.bitchord.data.lyrics.EmbeddedLyrics] and compatible with
 * standard desktop/car/Android ID3v2.3 readers via UTF-16LE with BOM text frames.
 */
object Mp3Tagger {

    private val ID3_MAGIC = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
    ): ByteArray {
        val frames = buildList {
            if (title.isNotBlank()) add(textFrame("TIT2", title))
            if (artist.isNotBlank()) add(textFrame("TPE1", artist))
            if (!album.isNullOrBlank()) add(textFrame("TALB", album))
            if (!lyrics.isNullOrBlank()) add(usltFrame(lyrics))
            if (cover != null && cover.isNotEmpty()) {
                add(apicFrame(cover, coverMime.ifBlank { "image/jpeg" }))
            }
        }
        if (frames.isEmpty()) return bytes

        val audioOffset = existingId3Size(bytes)
        val audioLen = bytes.size - audioOffset
        if (audioLen <= 0) return bytes

        val tagBodySize = frames.sumOf { it.size }
        val out = ByteArrayOutputStream(10 + tagBodySize + audioLen)
        out.write(ID3_MAGIC)
        out.write(3) // ID3v2.3
        out.write(0)
        out.write(0) // flags
        writeSyncsafe(out, tagBodySize)
        frames.forEach(out::write)
        out.write(bytes, audioOffset, audioLen)
        return out.toByteArray()
    }

    private fun existingId3Size(bytes: ByteArray): Int {
        if (bytes.size < 10) return 0
        if (bytes[0] != ID3_MAGIC[0] || bytes[1] != ID3_MAGIC[1] || bytes[2] != ID3_MAGIC[2]) return 0
        val major = bytes[3].toInt() and 0xFF
        if (major !in 2..4) return 0
        val bodySize = ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)
        val footer = if ((bytes[5].toInt() and 0x10) != 0) 10 else 0
        val total = 10 + bodySize + footer
        return if (total in 10 until bytes.size) total else 0
    }

    private fun textFrame(id: String, value: String): ByteArray {
        val encoded = encodeUtf16LeWithBom(value)
        val body = ByteArray(1 + encoded.size)
        body[0] = 0x01 // UTF-16 with BOM
        encoded.copyInto(body, 1)
        return wrapFrame(id, body)
    }

    private fun usltFrame(lyrics: String): ByteArray {
        val encoded = encodeUtf16LeWithBom(lyrics)
        val out = ByteArrayOutputStream(1 + 3 + 2 + encoded.size)
        out.write(0x01) // UTF-16 with BOM
        out.write('e'.code)
        out.write('n'.code)
        out.write('g'.code)
        // Empty description terminated by UTF-16 BOM/null (FF FE 00 00 or 00 00)
        out.write(0x00)
        out.write(0x00)
        out.write(encoded)
        return wrapFrame("USLT", out.toByteArray())
    }

    private fun apicFrame(cover: ByteArray, mime: String): ByteArray {
        val mimeBytes = mime.toByteArray(StandardCharsets.ISO_8859_1)
        val out = ByteArrayOutputStream(1 + mimeBytes.size + 1 + 1 + 1 + cover.size)
        out.write(0x00) // ISO-8859-1 description
        out.write(mimeBytes)
        out.write(0x00)
        out.write(0x03) // Cover (front)
        out.write(0x00) // empty description terminator
        out.write(cover)
        return wrapFrame("APIC", out.toByteArray())
    }

    private fun wrapFrame(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(10 + body.size)
        out.write(id.toByteArray(StandardCharsets.ISO_8859_1), 0, 4)
        writeU32Be(out, body.size)
        out.write(0)
        out.write(0)
        out.write(body)
        return out.toByteArray()
    }

    private fun encodeUtf16LeWithBom(value: String): ByteArray {
        val utf16le = value.toByteArray(StandardCharsets.UTF_16LE)
        val out = ByteArray(2 + utf16le.size)
        out[0] = 0xFF.toByte()
        out[1] = 0xFE.toByte()
        utf16le.copyInto(out, 2)
        return out
    }

    private fun writeU32Be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeSyncsafe(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 21) and 0x7F)
        out.write((value ushr 14) and 0x7F)
        out.write((value ushr 7) and 0x7F)
        out.write(value and 0x7F)
    }
}

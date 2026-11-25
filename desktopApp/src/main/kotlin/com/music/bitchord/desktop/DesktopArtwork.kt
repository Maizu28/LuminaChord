package com.music.bitchord.desktop

import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.icons.BitChordIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.decodeToImageBitmap
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Decoded artwork, kept to a budget.
 *
 * It used to be an unbounded map. Every distinct URL a session ever loaded stayed decoded for the
 * life of the process, so browsing Explore and Search for a while was hundreds of megabytes of
 * bitmaps nothing would ever ask for again — which is where the gigabyte people reported was going.
 */
internal object DesktopArtworkCache {

    /**
     * A sixth of the heap, within reason.
     *
     * Derived rather than fixed so the budget still makes sense against whatever `-Xmx` the
     * launcher was given, and clamped at both ends so a small heap still caches something and a
     * large one does not decide it may hold half a gigabyte of thumbnails.
     */
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    /** Four bytes a pixel, which is what every format here decodes to. */
    private val images = DesktopByteBudgetLru<ImageBitmap>(budgetBytes) {
        it.width.toLong() * it.height.toLong() * 4L
    }

    /** Everything held, dropped — what the Storage settings' "Clear image cache" does. */
    fun clear() = images.clear()

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        images.get(url)?.let { return it }
        return runCatching {
            loadBytes(url)?.decodeToImageBitmap()
        }.getOrNull()?.also { images.put(url, it) }
    }

    internal fun loadBytes(url: String): ByteArray? {
        // 1. Direct local file path (Windows e.g. C:\... or Unix /...)
        val directFile = runCatching { File(url) }.getOrNull()
        if (directFile != null && directFile.isFile && directFile.canRead()) {
            return runCatching { directFile.readBytes() }.getOrNull()
        }

        // 2. file: URI (e.g. file:///C:/... or file:/...)
        if (url.startsWith("file:", ignoreCase = true)) {
            val fileFromUri = runCatching {
                val clean = url.removePrefix("file://").removePrefix("file:")
                val decoded = URLDecoder.decode(clean, StandardCharsets.UTF_8.name())
                val f1 = File(decoded)
                if (f1.isFile) f1 else File(clean)
            }.getOrNull()
            if (fileFromUri != null && fileFromUri.isFile && fileFromUri.canRead()) {
                return runCatching { fileFromUri.readBytes() }.getOrNull()
            }
        }

        // 3. Remote URL (http / https)
        return runCatching {
            URI(url).toURL().openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }.getInputStream().use { it.readBytes() }
        }.getOrNull()
    }
}

/** Artwork, fetched at the size the surface drawing it actually needs. */
@Composable
fun DesktopArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    px: Int? = null,
) {
    val sized = if (px != null && url?.startsWith("http", ignoreCase = true) == true) url.artworkAt(px) else url
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = sized) {
        value = withContext(Dispatchers.IO) {
            DesktopArtworkCache.load(sized)
        }
    }
    if (bitmap == null) {
        Box(modifier.background(Color(0xFF2B2B35)), contentAlignment = Alignment.Center) {
            Icon(
                BitChordIcons.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.22f),
                modifier = Modifier.fillMaxSize(0.48f),
            )
        }
    } else {
        Image(
            painter = BitmapPainter(bitmap!!),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

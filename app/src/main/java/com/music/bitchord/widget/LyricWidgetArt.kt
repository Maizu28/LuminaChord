package com.music.bitchord.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.createBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Renders the translucent Liquid Glass backgrounds, rounded square album cover,
 * and smooth progress bar bitmap for LuminaChord's 4×2 and 4×1 Lyric widgets.
 */
internal object LyricWidgetArt {

    private const val CARD_CACHE_MAX_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CARD_CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun peekWideBackdrop(key: String?, widthPx: Int, heightPx: Int): Bitmap? =
        cache.get(wideBackdropKey(key, widthPx, heightPx))

    fun peekPillBackdrop(key: String?, widthPx: Int, heightPx: Int): Bitmap? =
        cache.get(pillBackdropKey(key, widthPx, heightPx))

    fun peekCover(key: String?, sizePx: Int): Bitmap? =
        cache.get(coverKey(key, sizePx))

    suspend fun loadArtwork(context: Context, artworkUrl: String?): Bitmap? {
        if (artworkUrl.isNullOrBlank()) return null
        val candidates = listOfNotNull(
            artworkUrl.artworkAt(CARD_ART_PX),
            artworkUrl,
        ).distinct()
        val loader = SingletonImageLoader.get(context.applicationContext)
        for (candidate in candidates) {
            val result = runCatching {
                val request = ImageRequest.Builder(context.applicationContext)
                    .data(candidate)
                    .allowHardware(false)
                    .size(CARD_ART_PX)
                    .build()
                (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
            if (result != null && result.width > 0 && result.height > 0) {
                return result
            }
        }
        return null
    }

    fun renderWideBackdrop(
        key: String?,
        artwork: Bitmap?,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        density: Float,
    ): Bitmap {
        val cacheKey = wideBackdropKey(key, widthPx, heightPx)
        cache.get(cacheKey)?.let { return it }

        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val raw = createBitmap(w, h)
        val canvas = Canvas(raw)

        // 1. Blurred ambient album backdrop (soft recognizable silhouettes on right).
        drawBlurredBackdrop(canvas, artwork, w, h, blurRadius = 26, shiftRight = true)

        // 2. Deep velvet twilight frost tint so text & glass controls always read cleanly.
        val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    0xB818152B.toInt(),
                    0x8C231D38.toInt(),
                    0xA8121020.toInt(),
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tintPaint)

        // Subtle radial luminescence near cover + bottom right.
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.22f, h * 0.35f, max(w, h) * 0.65f,
                intArrayOf(0x229C84D8, 0x00000000),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glowPaint)

        // 3. Liquid Glass flowing bottom-left & bottom-right caustic ribbons.
        drawCausticWaves(canvas, w.toFloat(), h.toFloat(), density, isCapsule = false)

        // Clip cleanly to launcher-matched rounded card geometry.
        val clipped = clipRoundedRect(raw, cornerRadiusPx)
        if (clipped !== raw) raw.recycle()

        // 4. Crisp diagonal specular glass rim stroke around outer perimeter.
        drawGlassRim(clipped, cornerRadiusPx, density)

        cache.put(cacheKey, clipped)
        return clipped
    }

    fun renderPillBackdrop(
        key: String?,
        artwork: Bitmap?,
        widthPx: Int,
        heightPx: Int,
        density: Float,
    ): Bitmap {
        val cacheKey = pillBackdropKey(key, widthPx, heightPx)
        cache.get(cacheKey)?.let { return it }

        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val raw = createBitmap(w, h)
        val canvas = Canvas(raw)

        drawBlurredBackdrop(canvas, artwork, w, h, blurRadius = 34, shiftRight = false)

        // Translucent frosted slate-violet gradient matching Image 2 capsule.
        val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    0xBA1A182D.toInt(),
                    0x9E25223C.toInt(),
                    0xB4191729.toInt(),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tintPaint)

        // Soft top-edge glass sheen + subtle bottom-right caustic fold.
        val topWash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.42f,
                intArrayOf(0x24FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.42f, topWash)

        drawCausticWaves(canvas, w.toFloat(), h.toFloat(), density, isCapsule = true)

        val radiusPx = h * 0.5f
        val clipped = clipRoundedRect(raw, radiusPx)
        if (clipped !== raw) raw.recycle()

        drawGlassRim(clipped, radiusPx, density)

        cache.put(cacheKey, clipped)
        return clipped
    }

    fun renderCoverArt(
        key: String?,
        artwork: Bitmap?,
        sizePx: Int,
        cornerRadiusPx: Float,
        density: Float,
    ): Bitmap? {
        if (artwork == null) return null
        val cacheKey = coverKey(key, sizePx)
        cache.get(cacheKey)?.let { return it }

        val s = sizePx.coerceAtLeast(1)
        val square = createBitmap(s, s)
        val canvas = Canvas(square)
        drawCentreCropped(canvas, artwork, s, s)

        val clipped = clipRoundedRect(square, cornerRadiusPx)
        if (clipped !== square) square.recycle()

        // Subtle glass edge border around the square artwork.
        val rimCanvas = Canvas(clipped)
        val inset = (0.75f * density).coerceAtLeast(1f)
        val rimRadius = (cornerRadiusPx - inset * 0.5f).coerceAtLeast(0f)
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = inset
            color = 0x45FFFFFF
        }
        rimCanvas.drawRoundRect(
            RectF(inset * 0.5f, inset * 0.5f, s - inset * 0.5f, s - inset * 0.5f),
            rimRadius,
            rimRadius,
            rimPaint,
        )

        cache.put(cacheKey, clipped)
        return clipped
    }

    fun renderProgressTrack(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        fraction: Float,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(24)
        val h = heightPx.coerceAtLeast(8)
        val f = fraction.coerceIn(0f, 1f)

        val out = createBitmap(w, h)
        val canvas = Canvas(out)

        val thumbRadius = (3.6f * density).coerceIn(2.5f, h * 0.34f)
        val haloRadius = thumbRadius * 1.45f
        val trackPad = haloRadius + 1f
        val trackStart = trackPad
        val trackEnd = (w - trackPad).coerceAtLeast(trackStart + 4f)
        val cy = h * 0.5f
        val thumbX = trackStart + (trackEnd - trackStart) * f

        // Unfilled rail.
        val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = (2.4f * density).coerceIn(2f, h * 0.3f)
            color = 0x42FFFFFF
        }
        canvas.drawLine(trackStart, cy, trackEnd, cy, railPaint)

        // Elapsed white rail.
        if (thumbX > trackStart) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = (2.8f * density).coerceIn(2f, h * 0.34f)
                color = Color.WHITE
            }
            canvas.drawLine(trackStart, cy, thumbX, cy, fillPaint)
        }

        // Soft glowing halo & solid white thumb ●.
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x45FFFFFF
        }
        canvas.drawCircle(thumbX, cy, haloRadius, haloPaint)

        val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint)

        return out
    }

    private fun drawBlurredBackdrop(
        canvas: Canvas,
        artwork: Bitmap?,
        w: Int,
        h: Int,
        blurRadius: Int,
        shiftRight: Boolean,
    ) {
        if (artwork == null) {
            val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(0xFF433864.toInt(), 0xFF231F38.toInt(), 0xFF151324.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fallbackPaint)
            return
        }

        // Work at downscaled resolution (~0.22x) for buttery blur speed and minimal allocations.
        val scale = 0.22f
        val sw = (w * scale).roundToInt().coerceIn(24, 240)
        val sh = (h * scale).roundToInt().coerceIn(16, 160)
        val tiny = createBitmap(sw, sh)
        val tinyCanvas = Canvas(tiny)
        if (shiftRight) {
            // Bias crop slightly right/up so cover textures sit prettily in the open right expanse.
            drawCentreCropped(tinyCanvas, artwork, sw, sh, biasX = 0.62f, biasY = 0.42f)
        } else {
            drawCentreCropped(tinyCanvas, artwork, sw, sh, biasX = 0.5f, biasY = 0.5f)
        }

        val scaledRadius = max(2, (blurRadius * scale).roundToInt())
        blurBitmapInPlace(tiny, scaledRadius)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(tiny, null, Rect(0, 0, w, h), paint)
        tiny.recycle()
    }

    private fun drawCausticWaves(
        canvas: Canvas,
        w: Float,
        h: Float,
        density: Float,
        isCapsule: Boolean,
    ) {
        if (isCapsule) {
            // Crisp liquid-glass fold accent in the bottom-right corner matching Image 2.
            val foldPath = Path().apply {
                moveTo(w * 0.78f, h)
                cubicTo(w * 0.86f, h * 0.90f, w * 0.94f, h * 0.72f, w, h * 0.46f)
                lineTo(w, h)
                close()
            }
            val foldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    w * 0.78f, h, w, h * 0.5f,
                    intArrayOf(0x00FFFFFF, 0x1EFFFFFF, 0x33B8A7EE),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(foldPath, foldFill)

            val foldHighlight = Path().apply {
                moveTo(w * 0.79f, h * 0.98f)
                cubicTo(w * 0.87f, h * 0.91f, w * 0.95f, h * 0.75f, w * 0.995f, h * 0.50f)
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (1.1f * density).coerceAtLeast(1.2f)
                shader = LinearGradient(
                    w * 0.78f, h, w, h * 0.5f,
                    intArrayOf(0x08FFFFFF, 0x58FFFFFF, 0x22FFFFFF),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(foldHighlight, linePaint)
            return
        }

        // Bottom-left sweeping caustic curve matching Image 1.
        val leftWave = Path().apply {
            moveTo(0f, h * 0.62f)
            cubicTo(w * 0.08f, h * 0.82f, w * 0.18f, h * 0.94f, w * 0.34f, h)
            lineTo(0f, h)
            close()
        }
        val leftFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.65f, w * 0.3f, h,
                intArrayOf(0x28F6D8F4, 0x10FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(leftWave, leftFill)

        val leftArc = Path().apply {
            moveTo(0f, h * 0.60f)
            cubicTo(w * 0.09f, h * 0.82f, w * 0.19f, h * 0.94f, w * 0.34f, h)
        }
        val arcPaintLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1.15f * density).coerceAtLeast(1.2f)
            shader = LinearGradient(
                0f, h * 0.60f, w * 0.34f, h,
                intArrayOf(0x48FFFFFF, 0x28FFFFFF, 0x05FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(leftArc, arcPaintLeft)

        // Bottom-right caustic shimmer matching Image 1.
        val rightWave = Path().apply {
            moveTo(w * 0.64f, h)
            cubicTo(w * 0.78f, h * 0.92f, w * 0.90f, h * 0.78f, w, h * 0.56f)
            lineTo(w, h)
            close()
        }
        val rightFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                w * 0.64f, h, w, h * 0.56f,
                intArrayOf(0x00FFFFFF, 0x18FFFFFF, 0x24C6B4FF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(rightWave, rightFill)

        val rightArc = Path().apply {
            moveTo(w * 0.64f, h)
            cubicTo(w * 0.79f, h * 0.92f, w * 0.91f, h * 0.78f, w, h * 0.58f)
        }
        val arcPaintRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1.25f * density).coerceAtLeast(1.3f)
            shader = LinearGradient(
                w * 0.64f, h, w, h * 0.58f,
                intArrayOf(0x10FFFFFF, 0x62FFFFFF, 0x28FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(rightArc, arcPaintRight)
    }

    private fun drawGlassRim(bitmap: Bitmap, cornerRadiusPx: Float, density: Float) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val stroke = (1.15f * density).coerceIn(1f, 3.5f)
        val half = stroke * 0.5f
        val rect = RectF(half, half, w - half, h - half)
        val r = (cornerRadiusPx - half).coerceAtLeast(0f)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0x8AFFFFFF.toInt(), 0x22FFFFFF, 0x18FFFFFF, 0x6EFFFFFF),
                floatArrayOf(0f, 0.35f, 0.65f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRoundRect(rect, r, r, rimPaint)
    }

    private fun clipRoundedRect(src: Bitmap, radiusPx: Float): Bitmap {
        val w = src.width
        val h = src.height
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radiusPx, radiusPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun drawCentreCropped(
        canvas: Canvas,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        biasX: Float = 0.5f,
        biasY: Float = 0.5f,
    ) {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val dw = targetW.toFloat()
        val dh = targetH.toFloat()
        val scale = max(dw / sw, dh / sh)
        val scaledW = sw * scale
        val scaledH = sh * scale
        val dx = -((scaledW - dw) * biasX)
        val dy = -((scaledH - dh) * biasY)
        val dst = RectF(dx, dy, dx + scaledW, dy + scaledH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, null, dst, paint)
    }

    private fun blurBitmapInPlace(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1 || radius < 1) return

        val pixels = IntArray(w * h)
        val scratch = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val sigma = max(1f, radius * 0.5f)
        val boxRadius = ((sqrt((12f * sigma * sigma / 3f) + 1f) - 1f) * 0.5f).roundToInt().coerceAtLeast(1)

        repeat(3) {
            boxPass(pixels, scratch, w, h, boxRadius, horizontal = true)
            boxPass(scratch, pixels, w, h, boxRadius, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun boxPass(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val window = 2 * radius + 1
        if (horizontal) {
            for (y in 0 until h) {
                val row = y * w
                var r = 0
                var g = 0
                var b = 0
                for (dx in -radius..radius) {
                    val c = src[row + dx.coerceIn(0, w - 1)]
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                    val outIdx = (x - radius).coerceIn(0, w - 1)
                    val inIdx = (x + radius + 1).coerceIn(0, w - 1)
                    val cout = src[row + outIdx]
                    val cin = src[row + inIdx]
                    r += ((cin ushr 16) and 0xFF) - ((cout ushr 16) and 0xFF)
                    g += ((cin ushr 8) and 0xFF) - ((cout ushr 8) and 0xFF)
                    b += (cin and 0xFF) - (cout and 0xFF)
                }
            }
        } else {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in -radius..radius) {
                    val c = src[dy.coerceIn(0, h - 1) * w + x]
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                    val outIdx = (y - radius).coerceIn(0, h - 1) * w + x
                    val inIdx = (y + radius + 1).coerceIn(0, h - 1) * w + x
                    val cout = src[outIdx]
                    val cin = src[inIdx]
                    r += ((cin ushr 16) and 0xFF) - ((cout ushr 16) and 0xFF)
                    g += ((cin ushr 8) and 0xFF) - ((cout ushr 8) and 0xFF)
                    b += (cin and 0xFF) - (cout and 0xFF)
                }
            }
        }
    }

    private fun wideBackdropKey(key: String?, w: Int, h: Int) = "lwide|${key.orEmpty()}|$w|$h"
    private fun pillBackdropKey(key: String?, w: Int, h: Int) = "lpill|${key.orEmpty()}|$w|$h"
    private fun coverKey(key: String?, sizePx: Int) = "lcover|${key.orEmpty()}|$sizePx"
}

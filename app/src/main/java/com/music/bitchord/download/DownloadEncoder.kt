package com.music.bitchord.download

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes downloaded audio streams with Android's `MediaExtractor`/`MediaCodec` and encodes
 * them to MP3 (via pure-Java LAME) or WebM/Opus (via Android's Opus encoder + WebM muxer).
 */
internal object DownloadEncoder {

    private const val CODEC_TIMEOUT_US = 10_000L
    private const val OPUS_SAMPLE_RATE = 48_000
    private val LAME_SAMPLE_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    fun supportsWebmOpusEncode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    suspend fun transcodeToMp3(
        sourceFile: File,
        outputFile: File,
        bitrateKbps: Int,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        outputFile.delete()
        var encoder: Mp3StreamEncoder? = null
        var resampler: LinearResampler? = null
        outputFile.outputStream().buffered().use { out ->
            try {
                decodeAudioPcm(sourceFile, onProgress) { pcm, frames, sampleRate, channels ->
                    val targetChannels = channels.coerceIn(1, 2)
                    val targetRate = nearestLameRate(sampleRate)
                    val enc = encoder ?: Mp3StreamEncoder(targetRate, targetChannels, bitrateKbps, out).also {
                        encoder = it
                        if (sampleRate != targetRate) {
                            resampler = LinearResampler(sampleRate, targetRate, targetChannels)
                        }
                    }
                    val mixed = if (channels > 2) downmixToStereo(pcm, frames, channels) else pcm
                    val activeResampler = resampler
                    if (activeResampler != null) {
                        val (resampled, resampledFrames) = activeResampler.process(mixed, frames)
                        enc.writeInterleavedShorts(resampled, resampledFrames)
                    } else {
                        enc.writeInterleavedShorts(mixed, frames)
                    }
                }
                encoder?.finish()
                out.flush()
            } finally {
                encoder?.closeQuietly()
            }
        }
        check(outputFile.isFile && outputFile.length() > 0L) { "MP3 conversion produced an empty file" }
    }

    suspend fun transcodeToWebm(
        sourceFile: File,
        outputFile: File,
        bitrateKbps: Int,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        check(supportsWebmOpusEncode()) { "WebM Opus encoding is not supported on this Android version" }
        outputFile.delete()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerTrack = -1
        var muxerStarted = false
        var resampler: LinearResampler? = null
        var outChannels = 2
        var samplesWritten = 0L

        fun drainEncoder(endOfStream: Boolean) {
            val codec = encoder ?: return
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) break
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Unexpected encoder format change" }
                        val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
                        muxer = m
                        muxerTrack = m.addTrack(codec.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)
                        if (encoded != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (muxerStarted) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer?.writeSampleData(muxerTrack, encoded, info)
                            }
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIndex, false)
                        if (eos) break
                    }
                }
            }
        }

        fun feedEncoder(samples: ShortArray, frames: Int, channels: Int) {
            val codec = encoder ?: return
            var frameOffset = 0
            while (frameOffset < frames) {
                val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIndex < 0) {
                    drainEncoder(endOfStream = false)
                    continue
                }
                val inBuf = codec.getInputBuffer(inIndex) ?: continue
                inBuf.clear()
                inBuf.order(ByteOrder.LITTLE_ENDIAN)
                val maxFrames = inBuf.remaining() / (channels * 2)
                val count = minOf(maxFrames, frames - frameOffset)
                val baseIdx = frameOffset * channels
                for (i in 0 until count * channels) {
                    inBuf.putShort(samples[baseIdx + i])
                }
                val ptsUs = samplesWritten * 1_000_000L / OPUS_SAMPLE_RATE
                codec.queueInputBuffer(inIndex, 0, count * channels * 2, ptsUs, 0)
                samplesWritten += count
                frameOffset += count
                drainEncoder(endOfStream = false)
            }
        }

        try {
            decodeAudioPcm(sourceFile, onProgress) { pcm, frames, sampleRate, channels ->
                if (encoder == null) {
                    outChannels = channels.coerceIn(1, 2)
                    val format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_OPUS,
                        OPUS_SAMPLE_RATE,
                        outChannels,
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    }
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                    if (sampleRate != OPUS_SAMPLE_RATE) {
                        resampler = LinearResampler(sampleRate, OPUS_SAMPLE_RATE, outChannels)
                    }
                }
                val mixed = if (channels > 2) downmixToStereo(pcm, frames, channels) else pcm
                val activeResampler = resampler
                if (activeResampler != null) {
                    val (resampled, resampledFrames) = activeResampler.process(mixed, frames)
                    feedEncoder(resampled, resampledFrames, outChannels)
                } else {
                    feedEncoder(mixed, frames, outChannels)
                }
            }

            encoder?.let { codec ->
                val eosIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (eosIdx >= 0) {
                    val ptsUs = samplesWritten * 1_000_000L / OPUS_SAMPLE_RATE
                    codec.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(endOfStream = true)
            }
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
        check(outputFile.isFile && outputFile.length() > 0L) { "WebM conversion produced an empty file" }
    }

    private suspend fun decodeAudioPcm(
        sourceFile: File,
        onProgress: (done: Long, total: Long) -> Unit,
        onPcm: (samples: ShortArray, frames: Int, sampleRate: Int, channels: Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = format
                    break
                }
            }
            val selected = inputFormat ?: error("No audio stream found to convert")
            extractor.selectTrack(audioTrack)
            val mime = selected.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME type")
            if (mime.equals("audio/raw", ignoreCase = true)) {
                extractor.release()
                if (decodeWavFallback(sourceFile, onProgress, onPcm)) return
                error("Unsupported raw WAV audio stream")
            }
            val durationUs = if (selected.containsKey(MediaFormat.KEY_DURATION)) {
                selected.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else {
                1L
            }

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(selected, null, null, 0)
                start()
            }

            var sampleRate = selected.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = selected.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = if (selected.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                selected.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReported = 0L

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inIdx, 0, size, pts, extractor.sampleFlags)
                            extractor.advance()
                            if (pts > lastReported && durationUs > 1L) {
                                lastReported = pts
                                onProgress(pts.coerceAtMost(durationUs), durationUs)
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = decoder.outputFormat
                        sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = extractShorts(outBuf, pcmEncoding)
                            val frames = if (channels > 0) shorts.size / channels else 0
                            if (frames > 0) {
                                onPcm(shorts, frames, sampleRate, channels)
                            }
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos) outputDone = true
                    }
                }
            }
            onProgress(durationUs, durationUs)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun decodeWavFallback(
        sourceFile: File,
        onProgress: (done: Long, total: Long) -> Unit,
        onPcm: (samples: ShortArray, frames: Int, sampleRate: Int, channels: Int) -> Unit,
    ): Boolean {
        val bytes = runCatching { sourceFile.readBytes() }.getOrNull() ?: return false
        if (bytes.size < 44) return false
        val header = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        if (header != "RIFF" || wave != "WAVE") return false
        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var audioFormat = 1
        var channels = 2
        var sampleRate = 44100
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = le.getInt(pos + 4).coerceAtLeast(0)
            val body = pos + 8
            if (body + chunkSize > bytes.size) break
            when (id) {
                "fmt " -> if (chunkSize >= 16) {
                    audioFormat = le.getShort(body).toInt() and 0xFFFF
                    channels = le.getShort(body + 2).toInt() and 0xFFFF
                    sampleRate = le.getInt(body + 4)
                    bitsPerSample = le.getShort(body + 14).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = body
                    dataSize = chunkSize
                    break
                }
            }
            pos = body + chunkSize + (chunkSize and 1)
        }
        if (dataOffset < 0 || dataSize <= 0 || audioFormat != 1 || bitsPerSample != 16 || channels <= 0) {
            return false
        }
        val frameBytes = channels * 2
        val totalFrames = (dataSize / frameBytes).toLong()
        var offset = 0
        val chunkFrames = 4096
        while (offset < totalFrames) {
            currentCoroutineContext().ensureActive()
            val nFrames = minOf(chunkFrames, (totalFrames - offset).toInt())
            val start = dataOffset + offset * frameBytes
            val shorts = ShortArray(nFrames * channels) { idx -> le.getShort(start + idx * 2) }
            onPcm(shorts, nFrames, sampleRate, channels)
            offset += nFrames
            onProgress(offset.toLong(), totalFrames)
        }
        return true
    }

    private fun extractShorts(buffer: ByteBuffer, pcmEncoding: Int): ShortArray {
        val dup = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = dup.asFloatBuffer()
                ShortArray(fb.remaining()) {
                    (fb.get().coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val out = ShortArray(dup.remaining())
                for (i in out.indices) {
                    val unsigned = dup.get().toInt() and 0xFF
                    out[i] = ((unsigned - 128) shl 8).toShort()
                }
                out
            }
            else -> {
                val sb = dup.asShortBuffer()
                ShortArray(sb.remaining()).also { sb.get(it) }
            }
        }
    }

    private fun downmixToStereo(pcm: ShortArray, frames: Int, channels: Int): ShortArray {
        if (channels <= 2) return pcm
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val base = f * channels
            out[f * 2] = pcm[base]
            out[f * 2 + 1] = pcm[base + 1]
        }
        return out
    }

    private fun nearestLameRate(sampleRate: Int): Int {
        if (sampleRate in LAME_SAMPLE_RATES) return sampleRate
        return LAME_SAMPLE_RATES.minBy { kotlin.math.abs(it - sampleRate) }
    }

    private class LinearResampler(
        private val inRate: Int,
        private val outRate: Int,
        private val channels: Int,
    ) {
        private val step = inRate.toDouble() / outRate.toDouble()
        private var pos = 0.0
        private var prevFrame = ShortArray(channels)
        private var hasPrev = false

        fun process(input: ShortArray, inFrames: Int): Pair<ShortArray, Int> {
            if (inFrames <= 0) return ShortArray(0) to 0
            val estimatedOut = ((inFrames / step) + 8).toInt()
            val out = ShortArray(estimatedOut * channels)
            var outFrames = 0

            while (true) {
                val idx0 = pos.toInt()
                val idx1 = idx0 + 1
                if (idx1 >= inFrames) break
                val frac = pos - idx0
                for (c in 0 until channels) {
                    val s0 = if (idx0 < 0 && hasPrev) prevFrame[c] else input[idx0.coerceAtLeast(0) * channels + c]
                    val s1 = input[idx1 * channels + c]
                    val sample = s0 + (s1 - s0) * frac
                    out[outFrames * channels + c] = sample.roundToInt().coerceIn(-32768, 32767).toShort()
                }
                outFrames++
                pos += step
            }
            for (c in 0 until channels) {
                prevFrame[c] = input[(inFrames - 1) * channels + c]
            }
            hasPrev = true
            pos -= inFrames
            if (pos < -1.0) pos = 0.0
            return out to outFrames
        }
    }

    /** Pure-Java LAME encoder without `javax.sound.sampled` dependencies. */
    internal class Mp3StreamEncoder(
        sampleRate: Int,
        private val channels: Int,
        bitrateKbps: Int,
        private val sink: OutputStream,
    ) {
        private val lame = Lame()
        private val gfp: LameGlobalFlags
        private var left = IntArray(4096)
        private var right = IntArray(4096)
        private var mp3Buffer = ByteArray(16_384)
        private var closed = false

        init {
            val gaud = GetAudio()
            val ga = GainAnalysis()
            val bs = BitStream()
            val p = Presets()
            val qupvt = QuantizePVT()
            val qu = Quantize()
            val vbr = VBRTag()
            val ver = Version()
            val id3 = ID3Tag()
            val rv = Reservoir()
            val tak = Takehiro()
            val parse = Parse()
            val mpg = MPGLib()
            val intf = Interface()
            val common = Common()
            lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
            bs.setModules(ga, mpg, ver, vbr)
            id3.setModules(bs, ver)
            p.setModules(lame)
            qu.setModules(bs, rv, qupvt, tak)
            qupvt.setModules(tak, rv, lame.enc.psy)
            rv.setModules(bs)
            tak.setModules(qupvt)
            vbr.setModules(lame, bs, ver)
            gaud.setModules(parse, mpg)
            parse.setModules(ver, id3, p)
            mpg.setModules(intf, common)
            intf.setModules(vbr, common)

            gfp = lame.lame_init().apply {
                num_channels = channels
                in_samplerate = sampleRate
                out_samplerate = sampleRate
                mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
                VBR = VbrMode.vbr_off
                brate = bitrateKbps
                quality = 2
                bWriteVbrTag = false
                write_id3tag_automatic = false
            }
            check(lame.lame_init_params(gfp) >= 0) { "Failed to initialize MP3 encoder" }
        }

        fun writeInterleavedShorts(interleaved: ShortArray, frames: Int) {
            if (frames <= 0) return
            if (left.size < frames) {
                left = IntArray(frames)
                right = IntArray(frames)
            }
            val neededMp3 = (1.25 * frames + 7200).toInt()
            if (mp3Buffer.size < neededMp3) mp3Buffer = ByteArray(neededMp3)

            if (channels == 1) {
                for (i in 0 until frames) {
                    val sample = interleaved[i].toInt() shl 16
                    left[i] = sample
                    right[i] = sample
                }
            } else {
                for (i in 0 until frames) {
                    left[i] = interleaved[i * 2].toInt() shl 16
                    right[i] = interleaved[i * 2 + 1].toInt() shl 16
                }
            }
            val written = lame.lame_encode_buffer_int(gfp, left, right, frames, mp3Buffer, 0, mp3Buffer.size)
            check(written >= 0) { "MP3 encode error ($written)" }
            if (written > 0) sink.write(mp3Buffer, 0, written)
        }

        fun finish() {
            if (mp3Buffer.size < 8192) mp3Buffer = ByteArray(8192)
            val flushed = lame.lame_encode_flush(gfp, mp3Buffer, 0, mp3Buffer.size)
            check(flushed >= 0) { "MP3 flush error ($flushed)" }
            if (flushed > 0) sink.write(mp3Buffer, 0, flushed)
        }

        fun closeQuietly() {
            if (!closed) {
                closed = true
                runCatching { lame.lame_close(gfp) }
            }
        }
    }
}

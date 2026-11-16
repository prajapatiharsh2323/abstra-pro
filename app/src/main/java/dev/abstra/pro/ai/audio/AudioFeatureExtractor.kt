package dev.abstra.pro.ai.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Optional local audio analysis.
 *
 * Decodes a small number of bounded PCM windows (never the whole track) with
 * Android's native media APIs, computes compact audio signatures (timbre,
 * chroma, tempo, scalar dynamics), and never blocks playlist generation: any
 * failure simply returns null / an UNSUPPORTED or FAILED status and the
 * metadata-only profile stays in place.
 *
 * Rules enforced here:
 *  - strictly bounded sampled audio duration (3 x ~5s windows),
 *  - decoder/extractor resources always released,
 *  - unsupported or inaccessible files are skipped safely,
 *  - cooperative cancellation between decode steps.
 */
class AudioFeatureExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AudioFeatureExtractor"

        /** Number of sampled windows per track. */
        private const val WINDOW_COUNT = 3

        /** Length of each sampled window in seconds. */
        private const val WINDOW_SECONDS = 5f

        /** Where windows are taken from: 20%, 50% and 80% of the track. */
        private val WINDOW_POSITIONS = floatArrayOf(0.2f, 0.5f, 0.8f)

        /** Hard wall-clock budget per window to avoid stuck decoders. */
        private const val WINDOW_TIMEOUT_MS = 12_000L
    }

    /**
     * Extracts a full audio signature, or null when the file cannot be read
     * or decoded at all. [onStatus] receives UNSUPPORTED/FAILED hints so the
     * caller can persist the outcome and never retry hopeless files.
     */
    suspend fun extract(
        song: Song,
        onStatus: ((String) -> Unit)? = null
    ): AudioSignature? = withContext(Dispatchers.IO) {
        runCatching { extractInternal(song, onStatus) }
            .getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onStatus?.invoke(AudioSignature.STATUS_FAILED)
                null
            }
    }

    private suspend fun extractInternal(
        song: Song,
        onStatus: ((String) -> Unit)?
    ): AudioSignature? {
        if (song.durationMs <= 0L) {
            onStatus?.invoke(AudioSignature.STATUS_UNSUPPORTED)
            return null
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            coroutineContext.ensureActive()
            setDataSource(extractor, song)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                onStatus?.invoke(AudioSignature.STATUS_UNSUPPORTED)
                return null
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                onStatus?.invoke(AudioSignature.STATUS_UNSUPPORTED)
                return null
            }
            extractor.selectTrack(audioTrackIndex)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val results = mutableListOf<SignatureDspResult>()
            for (position in WINDOW_POSITIONS) {
                coroutineContext.ensureActive()
                if (results.size >= WINDOW_COUNT) break

                val maxStartMs = (song.durationMs - (WINDOW_SECONDS * 1000L).toLong())
                    .coerceAtLeast(0L)
                val startUs = (song.durationMs * position).toLong()
                    .coerceAtMost(maxStartMs * 1000L)
                val pcm = decodeWindow(
                    extractor = extractor,
                    codec = codec,
                    initialFormat = format,
                    startUs = startUs,
                    windowSeconds = WINDOW_SECONDS
                ) ?: continue
                AudioDsp.computeSignatureFeatures(pcm.first, pcm.second, WINDOW_SECONDS)
                    ?.let { results += it }
            }
            codec.stop()

            if (results.isEmpty()) {
                onStatus?.invoke(AudioSignature.STATUS_FAILED)
                return null
            }
            return aggregate(song, results)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.d(TAG, "Audio analysis skipped for ${song.title}: ${e.message}")
            onStatus?.invoke(AudioSignature.STATUS_FAILED)
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {}
            try {
                codec?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Pools per-window features into one signature. MFCC statistics are
     * averaged across windows; chroma is averaged and renormalized; tempo
     * comes from the window with the strongest estimate.
     */
    private fun aggregate(song: Song, results: List<SignatureDspResult>): AudioSignature {
        fun avg(select: (SignatureDspResult) -> Float) = results.map(select).average().toFloat()
        val count = results.size

        val coverage = results.map { it.coverage }.average().toFloat()
        val confidence = (0.4f + 0.6f * coverage * (count.toFloat() / WINDOW_COUNT))
            .coerceIn(0f, 0.95f)

        val mfccMean = FloatArray(AudioSignature.MFCC_COUNT)
        for (i in mfccMean.indices) mfccMean[i] = results.map { it.mfccMean[i] }.average().toFloat()
        val mfccStd = FloatArray(AudioSignature.MFCC_COUNT)
        for (i in mfccStd.indices) mfccStd[i] = results.map { it.mfccStd[i] }.average().toFloat()

        // Chroma: only average windows with meaningful confidence.
        val tonalWindows = results.filter { it.chromaConfidence > 0.15f }
        var chroma = FloatArray(AudioSignature.CHROMA_COUNT)
        var chromaConfidence = 0f
        if (tonalWindows.isNotEmpty()) {
            var sum = 0f
            chroma = FloatArray(AudioSignature.CHROMA_COUNT) { i ->
                val v = tonalWindows.map { it.chroma[i] }.average().toFloat().also { sum += it }
                v
            }
            if (sum > 1e-6f) {
                for (i in chroma.indices) chroma[i] /= sum
                chromaConfidence = tonalWindows.map { it.chromaConfidence }.average().toFloat()
            }
        }

        // Tempo: trust the most confident window that produces an estimate.
        var tempoBpm = 0f
        var tempoConfidence = 0f
        for (result in results) {
            if (result.tempoConfidence > tempoConfidence) {
                tempoBpm = result.tempoBpm
                tempoConfidence = result.tempoConfidence
            }
        }

        return AudioSignature(
            songId = song.id,
            mfccMean = mfccMean,
            mfccStd = mfccStd,
            chroma = chroma,
            chromaConfidence = chromaConfidence,
            tempoBpm = tempoBpm,
            tempoConfidence = tempoConfidence,
            rmsEnergy = avg { it.rmsEnergy },
            dynamicRange = avg { it.dynamicRange },
            spectralCentroid = avg { it.spectralCentroid },
            spectralFlux = avg { it.spectralFlux },
            onsetDensity = avg { it.onsetDensity },
            rhythmActivity = avg { it.rhythmActivity },
            featureConfidence = confidence,
            extractorVersion = AudioSignature.EXTRACTOR_VERSION,
            metadataFingerprint = song.id, // replaced by engine with the real fingerprint
            analysisStatus = AudioSignature.STATUS_SUCCESS,
            analyzedAtMs = System.currentTimeMillis()
        )
    }

    private fun setDataSource(extractor: MediaExtractor, song: Song) {
        val uriString = song.uriString
        if (uriString.isNotBlank() && uriString.startsWith("content://")) {
            extractor.setDataSource(context, Uri.parse(uriString), null)
        } else if (song.filePath.isNotBlank()) {
            extractor.setDataSource(song.filePath)
        } else if (uriString.isNotBlank()) {
            extractor.setDataSource(uriString)
        } else {
            throw IllegalArgumentException("No usable data source for ${song.id}")
        }
    }

    /**
     * Decodes up to [windowSeconds] of PCM starting at [startUs].
     * Returns mono float samples and the actual sample rate, or null.
     */
    private suspend fun decodeWindow(
        extractor: MediaExtractor,
        codec: MediaCodec,
        initialFormat: MediaFormat,
        startUs: Long,
        windowSeconds: Float
    ): Pair<FloatArray, Int>? {
        val startedAt = System.currentTimeMillis()
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        var sampleRate = 44_100
        var channelCount = 2
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        if (initialFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sampleRate = initialFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        }
        if (initialFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channelCount = initialFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }

        val samplesNeeded = (sampleRate * windowSeconds).toInt()
        val mono = ArrayList<Float>(samplesNeeded)
        val bufferInfo = MediaCodec.BufferInfo()

        var inputDone = false
        var outputDone = false

        while (!outputDone && mono.size < samplesNeeded) {
            if (System.currentTimeMillis() - startedAt > WINDOW_TIMEOUT_MS) break
            coroutineContext.ensureActive()

            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer == null) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    } else {
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ) {
                        pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        appendPcm(
                            outputBuffer, bufferInfo, pcmEncoding, channelCount, mono, samplesNeeded
                        )
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                else -> {
                    // Try again; bounded by the wall-clock budget above.
                }
            }
        }

        if (mono.size < sampleRate) return null // less than ~1 second decoded
        val samples = FloatArray(mono.size)
        for (i in samples.indices) samples[i] = mono[i]
        return samples to sampleRate
    }

    private fun appendPcm(
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        pcmEncoding: Int,
        channelCount: Int,
        out: ArrayList<Float>,
        maxSamples: Int
    ) {
        buffer.order(ByteOrder.nativeOrder())
        when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val count = (info.size / 4) / channelCount
                for (frame in 0 until count) {
                    if (out.size >= maxSamples) return
                    var sum = 0f
                    for (ch in 0 until channelCount) sum += buffer.float
                    out.add(sum / channelCount)
                }
            }
            android.media.AudioFormat.ENCODING_PCM_8BIT -> {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val count = info.size / channelCount
                for (frame in 0 until count) {
                    if (out.size >= maxSamples) return
                    var sum = 0f
                    for (ch in 0 until channelCount) sum += (buffer.get().toInt() - 128) / 128f
                    out.add(sum / channelCount)
                }
            }
            else -> { // 16-bit default
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val count = (info.size / 2) / channelCount
                for (frame in 0 until count) {
                    if (out.size >= maxSamples) return
                    var sum = 0
                    for (ch in 0 until channelCount) sum += buffer.short.toInt()
                    out.add(sum / channelCount / 32768f)
                }
            }
        }
    }
}

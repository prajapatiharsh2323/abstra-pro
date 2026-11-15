package dev.abstra.pro.ai

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

data class DspResult(
    val rmsEnergy: Float,
    val dynamicRange: Float,
    val spectralCentroid: Float,
    val spectralFlux: Float,
    val onsetDensity: Float,
    val rhythmActivity: Float,
    val coverage: Float
)

/**
 * Pure-Kotlin signal feature calculations over short PCM windows.
 * Signal-level measurements only — no trained mood/emotion model.
 */
object AudioDsp {

    private const val FRAME_SIZE = 1024
    private const val HOP_SIZE = 512

    /** dBFS mapping window for RMS normalization. */
    private const val RMS_FLOOR_DB = -60f
    private const val RMS_CEIL_DB = 0f

    /** dB spread considered "wide dynamics". */
    private const val DYNAMIC_RANGE_SPAN_DB = 35f

    /** Frequency window for spectral centroid normalization (log2 scale). */
    private const val CENTROID_MIN_HZ = 100f
    private const val CENTROID_MAX_HZ = 8000f

    /** Onsets per second that maps to full rhythmic activity. */
    private const val ONSET_DENSITY_MAX = 8f

    fun computeFeatures(pcm: FloatArray, sampleRate: Int, requestedWindowSeconds: Float): DspResult? {
        if (pcm.size < FRAME_SIZE || sampleRate <= 0) return null

        val frames = (pcm.size - FRAME_SIZE) / HOP_SIZE + 1
        if (frames < 5) return null

        val window = hann(FRAME_SIZE)
        val rmsValues = FloatArray(frames)
        val centroids = FloatArray(frames)
        val fluxValues = FloatArray(frames)

        val spectrum = FloatArray(FRAME_SIZE / 2)
        val prevSpectrum = FloatArray(FRAME_SIZE / 2)
        var hasPrev = false
        var spectrumMax = 1e-9f

        for (frameIndex in 0 until frames) {
            val offset = frameIndex * HOP_SIZE

            // Frame RMS
            var sumSquares = 0.0
            for (i in 0 until FRAME_SIZE) {
                val s = pcm[offset + i]
                sumSquares += (s * s).toDouble()
            }
            val rms = sqrt(sumSquares / FRAME_SIZE).toFloat()
            rmsValues[frameIndex] = rms

            // Magnitude spectrum (windowed)
            val re = FloatArray(FRAME_SIZE)
            val im = FloatArray(FRAME_SIZE)
            for (i in 0 until FRAME_SIZE) {
                re[i] = pcm[offset + i] * window[i]
            }
            fft(re, im)
            for (i in spectrum.indices) {
                val mag = sqrt(re[i] * re[i] + im[i] * im[i])
                spectrum[i] = mag
                if (mag > spectrumMax) spectrumMax = mag
            }

            // Spectral centroid (Hz)
            var magSum = 0.0
            var freqWeighted = 0.0
            for (i in spectrum.indices) {
                val mag = spectrum[i].toDouble()
                magSum += mag
                freqWeighted += mag * i
            }
            val binHz = sampleRate.toDouble() / FRAME_SIZE
            val centroidHz = if (magSum > 1e-9) (freqWeighted / magSum) * binHz else 0.0
            centroids[frameIndex] = centroidHz.toFloat()

            // Spectral flux (positive magnitude differences, normalized)
            if (hasPrev && spectrumMax > 1e-9f) {
                var flux = 0f
                for (i in spectrum.indices) {
                    val diff = (spectrum[i] - prevSpectrum[i]) / spectrumMax
                    if (diff > 0f) flux += diff
                }
                fluxValues[frameIndex] = flux / spectrum.size
            }
            System.arraycopy(spectrum, 0, prevSpectrum, 0, spectrum.size)
            hasPrev = true
        }

        // Aggregate RMS energy (dB mapping)
        var rmsDbSum = 0.0
        for (rms in rmsValues) {
            rmsDbSum += (20.0 * log10((rms + 1e-9f).toDouble())).coerceIn(RMS_FLOOR_DB.toDouble(), RMS_CEIL_DB.toDouble())
        }
        val meanRmsDb = (rmsDbSum / frames).toFloat()
        val rmsEnergy = ((meanRmsDb - RMS_FLOOR_DB) / (RMS_CEIL_DB - RMS_FLOOR_DB)).coerceIn(0f, 1f)

        // Dynamic range (P95 - P10 of frame RMS in dB)
        val sortedRmsDb = FloatArray(frames) { i ->
            (20.0 * log10((rmsValues[i] + 1e-9f).toDouble()))
                .coerceIn(RMS_FLOOR_DB.toDouble(), RMS_CEIL_DB.toDouble()).toFloat()
        }.also { it.sort() }
        val p95 = sortedRmsDb[((sortedRmsDb.size - 1) * 0.95).toInt().coerceIn(0, sortedRmsDb.size - 1)]
        val p10 = sortedRmsDb[((sortedRmsDb.size - 1) * 0.10).toInt().coerceIn(0, sortedRmsDb.size - 1)]
        val dynamicRange = ((p95 - p10) / DYNAMIC_RANGE_SPAN_DB).coerceIn(0f, 1f)

        // Spectral centroid (log frequency mapping)
        var centroidSum = 0.0
        var centroidCount = 0
        for (hz in centroids) {
            if (hz <= 0f) continue
            val norm = (log2((hz / CENTROID_MIN_HZ).coerceAtLeast(1e-6f).toDouble()) /
                    log2(CENTROID_MAX_HZ / CENTROID_MIN_HZ)).coerceIn(0.0, 1.0)
            centroidSum += norm
            centroidCount++
        }
        val spectralCentroid = if (centroidCount > 0) (centroidSum / centroidCount).toFloat() else 0.4f

        // Spectral flux mean
        val fluxFrames = fluxValues.count { it > 0f }
        val spectralFlux = if (fluxFrames > 0) {
            (fluxValues.sum() / fluxFrames / 0.25f).coerceIn(0f, 1f)
        } else 0f

        // Onset density: frames whose flux exceeds mean + 1.5 x std
        val fluxPositive = fluxValues.filter { it > 0f }
        val onsetDensity = if (fluxPositive.size >= 4) {
            val mean = fluxPositive.average().toFloat()
            var variance = 0.0
            for (f in fluxPositive) variance += ((f - mean) * (f - mean)).toDouble()
            val std = sqrt(variance / fluxPositive.size).toFloat()
            val threshold = mean + 1.5f * std
            val onsets = fluxPositive.count { it > threshold }
            val seconds = (frames * HOP_SIZE).toFloat() / sampleRate
            val onsetsPerSecond = onsets / seconds.coerceAtLeast(0.5f)
            (onsetsPerSecond / ONSET_DENSITY_MAX).coerceIn(0f, 1f)
        } else 0.3f

        val rhythmActivity = (0.6f * onsetDensity + 0.4f * spectralFlux).coerceIn(0f, 1f)

        val coverage = (pcm.size.toFloat() / (sampleRate * requestedWindowSeconds))
            .coerceIn(0f, 1f)

        return DspResult(
            rmsEnergy = rmsEnergy,
            dynamicRange = dynamicRange,
            spectralCentroid = spectralCentroid,
            spectralFlux = spectralFlux,
            onsetDensity = onsetDensity,
            rhythmActivity = rhythmActivity,
            coverage = coverage
        )
    }

    fun hann(size: Int): FloatArray {
        val window = FloatArray(size)
        for (i in 0 until size) {
            window[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
        return window
    }

    /**
     * In-place iterative radix-2 FFT. Arrays must have a power-of-two length.
     */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }

        // Bit reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val half = len / 2
            val angleStep = -2.0 * PI / len
            var blockStart = 0
            while (blockStart < n) {
                var k = 0
                for (i in blockStart until blockStart + half) {
                    val angle = angleStep * k
                    val wRe = cos(angle).toFloat()
                    val wIm = sin(angle).toFloat()
                    val aRe = re[i + half] * wRe - im[i + half] * wIm
                    val aIm = re[i + half] * wIm + im[i + half] * wRe
                    re[i + half] = re[i] - aRe
                    im[i + half] = im[i] - aIm
                    re[i] += aRe
                    im[i] += aIm
                    k++
                }
                blockStart += len
            }
            len = len shl 1
        }
    }

    fun rms(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) sum += (s * s).toDouble()
        return sqrt(sum / pcm.size).toFloat()
    }

    fun dbfs(value: Float): Float = 20f * log10(abs(value) + 1e-9f)
}

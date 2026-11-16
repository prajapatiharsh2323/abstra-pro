package dev.abstra.pro.ai.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scalar signal-level features of one analyzed window.
 * Signal measurements only — no trained mood/emotion model.
 */
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
 * Extended per-window features: everything in [DspResult] plus the timbral
 * (MFCC), harmonic (chroma) and tempo representations used to compare how
 * songs actually sound.
 */
data class SignatureDspResult(
    val rmsEnergy: Float,
    val dynamicRange: Float,
    val spectralCentroid: Float,
    val spectralFlux: Float,
    val onsetDensity: Float,
    val rhythmActivity: Float,
    val mfccMean: FloatArray,
    val mfccStd: FloatArray,
    val chroma: FloatArray,
    val chromaConfidence: Float,
    val tempoBpm: Float,
    val tempoConfidence: Float,
    val coverage: Float
)

/**
 * Pure-Kotlin signal feature calculations over short PCM windows.
 *
 * Working arrays, mel filter banks and the DCT matrix are allocated once
 * per analysis and reused across frames — no per-frame allocations in the
 * hot loop beyond the reusable spectrum buffers.
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

    // ── MFCC / chroma configuration ──
    private const val MEL_FILTERS = 26
    private const val MEL_FMIN_HZ = 80f
    private const val MEL_FMAX_HZ = 16000f
    private const val MFCC_COUNT = 13

    // ── Tempo estimation configuration ──
    private const val TEMPO_MIN_BPM = 60f
    private const val TEMPO_MAX_BPM = 200f
    /** Preferred middle of the range used to resolve half/double-time ambiguity. */
    private const val TEMPO_PRIOR_BPM = 120f
    private const val MIN_ENVELOPE_FRAMES = 60

    /** Cached per-sample-rate analysis prerequisites. */
    private class AnalysisKit(val sampleRate: Int) {
        val window: FloatArray = hann(FRAME_SIZE)
        val melFilters: Array<MelFilter> = buildMelFilters(sampleRate)
        val dct: Array<FloatArray> = buildDct(MFCC_COUNT, MEL_FILTERS)
    }

    private class MelFilter(val index: Int, val startBin: Int, val weights: FloatArray)


    private val kits = ConcurrentHashMap<Int, AnalysisKit>()

    private fun kitFor(sampleRate: Int): AnalysisKit =
        kits.getOrPut(sampleRate) { AnalysisKit(sampleRate) }

    // ═════════════════════════════════════════════════════════════
    // Public entry points
    // ═════════════════════════════════════════════════════════════

    /** Scalar-only features (V1-compatible behaviour). */
    fun computeFeatures(pcm: FloatArray, sampleRate: Int, requestedWindowSeconds: Float): DspResult? {
        val full = computeSignatureFeatures(pcm, sampleRate, requestedWindowSeconds) ?: return null
        return DspResult(
            rmsEnergy = full.rmsEnergy,
            dynamicRange = full.dynamicRange,
            spectralCentroid = full.spectralCentroid,
            spectralFlux = full.spectralFlux,
            onsetDensity = full.onsetDensity,
            rhythmActivity = full.rhythmActivity,
            coverage = full.coverage
        )
    }

    /**
     * Full signature features from one bounded PCM window. Returns null when
     * the audio is too short or the sample rate is invalid.
     */
    fun computeSignatureFeatures(
        pcm: FloatArray,
        sampleRate: Int,
        requestedWindowSeconds: Float
    ): SignatureDspResult? {
        if (pcm.size < FRAME_SIZE || sampleRate <= 0) return null
        val frames = (pcm.size - FRAME_SIZE) / HOP_SIZE + 1
        if (frames < 5) return null

        val kit = kitFor(sampleRate)
        val bins = FRAME_SIZE / 2

        // Reusable working buffers — allocated once per analysis.
        val re = FloatArray(FRAME_SIZE)
        val im = FloatArray(FRAME_SIZE)
        val spectrum = FloatArray(bins)
        val prevSpectrum = FloatArray(bins)
        val melEnergies = FloatArray(MEL_FILTERS)
        val mfccFrame = FloatArray(MFCC_COUNT)
        val chromaAcc = DoubleArray(12)
        val onsetEnvelope = FloatArray(frames)
        val mfccAccumulator = MfccAccumulator()

        val rmsValues = FloatArray(frames)
        val centroids = FloatArray(frames)
        val fluxValues = FloatArray(frames)

        var hasPrev = false
        var spectrumMax = 1e-9f
        var chromaTotal = 0.0

        for (frameIndex in 0 until frames) {
            val offset = frameIndex * HOP_SIZE

            // Frame RMS (raw, unwindowed — same convention as V1).
            var sumSquares = 0.0
            for (i in 0 until FRAME_SIZE) {
                val s = pcm[offset + i]
                sumSquares += (s * s).toDouble()
            }
            val rms = sqrt(sumSquares / FRAME_SIZE).toFloat()
            rmsValues[frameIndex] = rms

            // Magnitude spectrum (windowed).
            for (i in 0 until FRAME_SIZE) {
                re[i] = pcm[offset + i] * kit.window[i]
                im[i] = 0f
            }
            fft(re, im)
            for (i in spectrum.indices) {
                val mag = sqrt(re[i] * re[i] + im[i] * im[i])
                spectrum[i] = mag
                if (mag > spectrumMax) spectrumMax = mag
            }

            // Spectral centroid (Hz).
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

            // Spectral flux (positive magnitude differences, normalized).
            var flux = 0f
            if (hasPrev && spectrumMax > 1e-9f) {
                for (i in spectrum.indices) {
                    val diff = (spectrum[i] - prevSpectrum[i]) / spectrumMax
                    if (diff > 0f) flux += diff
                }
                flux /= spectrum.size
            }
            fluxValues[frameIndex] = flux
            System.arraycopy(spectrum, 0, prevSpectrum, 0, bins)
            hasPrev = true

            // Onset-strength envelope for tempo estimation.
            onsetEnvelope[frameIndex] = flux

            // MFCC: mel energies -> log compression -> DCT.
            for (f in melEnergies.indices) melEnergies[f] = 0f
            for (filter in kit.melFilters) {
                var energy = 0f
                for (b in 0 until filter.weights.size) {
                    energy += spectrum[filter.startBin + b] * filter.weights[b]
                }
                melEnergies[filter.index] = ln(energy + 1e-9f)
            }
            for (c in 0 until MFCC_COUNT) {
                var sum = 0.0
                val row = kit.dct[c]
                for (f in 0 until MEL_FILTERS) sum += row[f] * melEnergies[f]
                mfccFrame[c] = sum.toFloat()
            }
            mfccAccumulator.add(mfccFrame)

            // Chroma: map bin frequencies to pitch classes.
            if (magSum > 1e-9) {
                for (bin in 1 until bins) {
                    val mag = spectrum[bin]
                    if (mag <= 1e-6f) continue
                    val hz = bin * binHz
                    if (hz < 65f || hz > 5000f) continue
                    val midi = 69.0 + 12.0 * log2(hz / 440.0)
                    val pitchClass = (((Math.round(midi) % 12) + 12) % 12).toInt()
                    val m = mag.toDouble()
                    chromaAcc[pitchClass] += m
                    chromaTotal += m
                }
            }
        }

        // ── Scalar aggregates (identical math to V1) ──
        var rmsDbSum = 0.0
        for (rms in rmsValues) {
            rmsDbSum += (20.0 * log10((rms + 1e-9f).toDouble())).coerceIn(RMS_FLOOR_DB.toDouble(), RMS_CEIL_DB.toDouble())
        }
        val meanRmsDb = (rmsDbSum / frames).toFloat()
        val rmsEnergy = ((meanRmsDb - RMS_FLOOR_DB) / (RMS_CEIL_DB - RMS_FLOOR_DB)).coerceIn(0f, 1f)

        val sortedRmsDb = FloatArray(frames) { i ->
            (20.0 * log10((rmsValues[i] + 1e-9f).toDouble()))
                .coerceIn(RMS_FLOOR_DB.toDouble(), RMS_CEIL_DB.toDouble()).toFloat()
        }.also { it.sort() }
        val p95 = sortedRmsDb[((sortedRmsDb.size - 1) * 0.95).toInt().coerceIn(0, sortedRmsDb.size - 1)]
        val p10 = sortedRmsDb[((sortedRmsDb.size - 1) * 0.10).toInt().coerceIn(0, sortedRmsDb.size - 1)]
        val dynamicRange = ((p95 - p10) / DYNAMIC_RANGE_SPAN_DB).coerceIn(0f, 1f)

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

        val fluxFrames = fluxValues.count { it > 0f }
        val spectralFlux = if (fluxFrames > 0) {
            (fluxValues.sum() / fluxFrames / 0.25f).coerceIn(0f, 1f)
        } else 0f

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

        // ── MFCC statistics ──
        val mfccMeanArr = mfccAccumulator.mean()
        val mfccStdArr = mfccAccumulator.std(mfccMeanArr)

        // ── Chroma ──
        var chromaNorm = FloatArray(12)
        var chromaConfidence = 0f
        if (chromaTotal > 1e-6) {
            var sum = 0.0
            for (i in 0 until 12) sum += chromaAcc[i]
            if (sum > 1e-9) {
                val sorted = chromaAcc.copyOf().also { it.sort() }.reversedArray()
                val top3 = (sorted[0] + sorted[1] + sorted[2]) / sum
                // Tonal content concentrates in a few pitch classes; noise and
                // percussion spread it out. Map top-3 share to confidence.
                chromaConfidence = ((top3 - 0.30) / 0.55).coerceIn(0.0, 1.0).toFloat()
                chromaNorm = FloatArray(12) { (chromaAcc[it] / sum).toFloat() }
            }
        }

        // ── Tempo ──
        val hopSeconds = HOP_SIZE.toFloat() / sampleRate
        val tempo = estimateTempo(onsetEnvelope, hopSeconds)

        return SignatureDspResult(
            rmsEnergy = rmsEnergy,
            dynamicRange = dynamicRange,
            spectralCentroid = spectralCentroid,
            spectralFlux = spectralFlux,
            onsetDensity = onsetDensity,
            rhythmActivity = rhythmActivity,
            mfccMean = mfccMeanArr,
            mfccStd = mfccStdArr,
            chroma = chromaNorm,
            chromaConfidence = chromaConfidence,
            tempoBpm = tempo?.first ?: 0f,
            tempoConfidence = tempo?.second ?: 0f,
            coverage = coverage
        )
    }

        // Small streaming accumulator over MFCC frames (local to this call).
    private class MfccAccumulator {
        private val count = IntArray(MFCC_COUNT)
        private val mean = DoubleArray(MFCC_COUNT)
        private val m2 = DoubleArray(MFCC_COUNT)

        fun add(frame: FloatArray) {
            for (c in 0 until MFCC_COUNT) {
                count[c]++
                val delta = frame[c] - mean[c]
                mean[c] += delta / count[c]
                m2[c] += delta * (frame[c] - mean[c])
            }
        }

        fun mean(): FloatArray = FloatArray(MFCC_COUNT) { mean[it].toFloat() }

        fun std(meanOut: FloatArray): FloatArray = FloatArray(MFCC_COUNT) { c ->
            if (count[c] > 1) sqrt(m2[c] / (count[c] - 1)).toFloat() else 0f
        }
    }

    /**
     * Autocorrelation-based tempo estimate over the onset-strength envelope.
     * Search range [TEMPO_MIN_BPM, TEMPO_MAX_BPM]; half-time and double-time
     * candidates compete with the raw peak, resolved toward the prior center.
     * Returns (bpm, confidence) or null when the envelope is unusable.
     */
    fun estimateTempo(envelope: FloatArray, hopSeconds: Float): Pair<Float, Float>? {
        if (envelope.size < MIN_ENVELOPE_FRAMES || hopSeconds <= 0f) return null

        // Normalize: subtract mean, keep rectified fluctuation.
        val mean = envelope.average()
        val centered = FloatArray(envelope.size) { (envelope[it] - mean).toFloat() }
        var energy = 0.0
        for (v in centered) energy += v.toDouble() * v
        if (energy < 1e-12) return null // flat envelope (silence or constant flux)

        // Slow tempos have the longest lag between beats: the minimum lag
        // corresponds to TEMPO_MAX_BPM, the maximum lag to TEMPO_MIN_BPM.
        val lagMin = ((60f / TEMPO_MAX_BPM) / hopSeconds).toInt().coerceAtLeast(2)
        val lagMax = ((60f / TEMPO_MIN_BPM) / hopSeconds).toInt()
            .coerceAtMost(envelope.size / 2)
        if (lagMax <= lagMin) return null

        // Autocorrelation, normalized by lag-0 energy.
        val scores = FloatArray(lagMax + 1)
        scores[0] = 1f
        for (lag in lagMin..lagMax) {
            var sum = 0.0
            for (i in 0 until envelope.size - lag) sum += centered[i] * centered[i + lag]
            scores[lag] = (sum / energy).toFloat().coerceIn(0f, 1f)
        }

        // Comb-filter score: peak plus harmonic support at multiples.
        val best = HashMap<Int, Float>()
        for (lag in lagMin..lagMax) {
            var score = scores[lag]
            var harmonicWeight = 1f
            var mult = 2
            while (lag * mult <= lagMax) {
                score += scores[lag * mult] * (0.5f / mult)
                harmonicWeight += 0.5f / mult
                mult++
            }
            best[lag] = score / harmonicWeight
        }

        var bestLag = -1
        var bestScore = -1f
        for ((lag, score) in best) {
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestScore <= 0f) return null

        // Resolve half-time / double-time ambiguity: among candidates with a
        // comparable score, prefer the tempo closest to the prior center.
        val candidates = mutableListOf(bestLag)
        val halfLag = bestLag * 2
        val doubleLag = bestLag / 2
        if (doubleLag in lagMin..lagMax && (best[doubleLag] ?: 0f) >= 0.8f * bestScore) candidates += doubleLag
        if (halfLag <= lagMax && (best[halfLag] ?: 0f) >= 0.8f * bestScore) candidates += halfLag

        var chosenLag = bestLag
        var chosenDistance = Double.MAX_VALUE
        for (lag in candidates) {
            val bpm = 60f / (lag * hopSeconds)
            val distance = abs(log2((bpm / TEMPO_PRIOR_BPM).coerceAtLeast(1e-6f).toDouble()))
            if (distance < chosenDistance) {
                chosenDistance = distance
                chosenLag = lag
            }
        }

        val bpm = 60f / (chosenLag * hopSeconds)
        if (bpm < TEMPO_MIN_BPM * 0.5f || bpm > TEMPO_MAX_BPM * 2f) return null

        // Confidence: peak prominence against the median of all lags, and the
        // absolute strength of the peak.
        val medianScore = best.values.sorted()[best.size / 2]
        val prominence = ((bestScore - medianScore).coerceAtLeast(0f) / 0.5f).coerceIn(0f, 1f)
        val strength = (bestScore / 0.6f).coerceIn(0f, 1f)
        val confidence = (0.4f * prominence + 0.6f * strength).coerceIn(0f, 1f)

        return bpm to confidence
    }

    // ─────────────────────────────────────────────────────────────
    // Prerequisites
    // ─────────────────────────────────────────────────────────────

    private fun buildMelFilters(sampleRate: Int): Array<MelFilter> {
        val bins = FRAME_SIZE / 2
        val binHz = sampleRate.toDouble() / FRAME_SIZE
        val fmax = min(MEL_FMAX_HZ, sampleRate / 2f)

        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

        val melMin = hzToMel(MEL_FMIN_HZ.toDouble())
        val melMax = hzToMel(fmax.toDouble())
        val melPoints = DoubleArray(MEL_FILTERS + 2) { i ->
            melMin + (melMax - melMin) * i / (MEL_FILTERS + 1)
        }
        val hzPoints = melPoints.map { melToHz(it) }
        val binPoints = hzPoints.map { (it / binHz).coerceIn(0.0, (bins - 1).toDouble()) }

        val filters = arrayOfNulls<MelFilter>(MEL_FILTERS)
        for (f in 0 until MEL_FILTERS) {
            val left = binPoints[f]
            val center = binPoints[f + 1]
            val right = binPoints[f + 2]
            val startBin = left.toInt()
            val endBin = (right.toInt() + 1).coerceAtMost(bins)
            if (endBin <= startBin) {
                filters[f] = MelFilter(f, 0, FloatArray(0))
                continue
            }
            val weights = FloatArray(endBin - startBin)
            for (b in startBin until endBin) {
                val x = b.toDouble()
                val w = when {
                    x <= center && x >= left ->
                        if (center > left) ((x - left) / (center - left)).toFloat() else 0f
                    x > center && x <= right ->
                        if (right > center) ((right - x) / (right - center)).toFloat() else 0f
                    else -> 0f
                }
                weights[b - startBin] = w
            }
            filters[f] = MelFilter(f, startBin, weights)
        }
        @Suppress("UNCHECKED_CAST")
        return filters as Array<MelFilter>
    }

    /** DCT-II matrix: [coeff][filter]. */
    private fun buildDct(coeffCount: Int, filterCount: Int): Array<FloatArray> {
        val matrix = Array(coeffCount) { FloatArray(filterCount) }
        for (c in 0 until coeffCount) {
            for (f in 0 until filterCount) {
                matrix[c][f] = cos(PI * c * (2.0 * f + 1.0) / (2.0 * filterCount)).toFloat()
            }
        }
        return matrix
    }

    // ─────────────────────────────────────────────────────────────
    // Primitives (public: covered by existing tests)
    // ─────────────────────────────────────────────────────────────

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

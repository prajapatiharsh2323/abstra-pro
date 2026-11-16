package dev.abstra.pro.ai.graph

import dev.abstra.pro.ai.model.AudioSignature
import kotlin.math.max
import kotlin.math.min

/**
 * Robust between-track normalization for audio scalar features.
 *
 * Computes library-level median / IQR statistics so that features from
 * different formats and mastering levels become comparable, without
 * destroying genuine between-track differences (no per-track normalization).
 * Extreme outliers are clipped to the central range.
 */
class RobustScaler private constructor(
    private val medians: Map<String, Float>,
    private val iqrs: Map<String, Float>
) {

    /**
     * Normalizes one scalar feature to roughly 0..1, where 0.5 is the
     * library median. Values beyond ±[CLIP_IQR] IQRs are clipped.
     */
    fun normalize(feature: String, value: Float): Float {
        val median = medians[feature]
        val iqr = iqrs[feature]
        if (median == null || iqr == null || iqr < MIN_IQR) {
            // No usable library statistics: fall back to the raw normalized
            // convention (features are already stored 0..1).
            return value.coerceIn(0f, 1f)
        }
        val clipped = ((value - median) / iqr).coerceIn(-CLIP_IQR, CLIP_IQR)
        return ((clipped / (2f * CLIP_IQR)) + 0.5f).coerceIn(0f, 1f)
    }

    companion object {
        private const val CLIP_IQR = 3f
        private const val MIN_IQR = 1e-3f

        /** Scalar feature names used in the audio signature. */
        val FEATURES = listOf(
            "rmsEnergy", "dynamicRange", "spectralCentroid",
            "spectralFlux", "onsetDensity", "rhythmActivity"
        )

        fun of(signatures: Collection<AudioSignature>): RobustScaler {
            val medians = HashMap<String, Float>()
            val iqrs = HashMap<String, Float>()
            if (signatures.size < 5) {
                // Too few samples for robust statistics; degenerate scaler.
                return RobustScaler(medians, iqrs)
            }
            for (feature in FEATURES) {
                val values = signatures.map { signatureValue(it, feature) }
                    .filter { it.isFinite() }
                    .sorted()
                if (values.size < 5) continue
                val median = percentile(values, 0.5)
                val q1 = percentile(values, 0.25)
                val q3 = percentile(values, 0.75)
                medians[feature] = median
                iqrs[feature] = max(q3 - q1, MIN_IQR)
            }
            return RobustScaler(medians, iqrs)
        }

        private fun signatureValue(signature: AudioSignature, feature: String): Float = when (feature) {
            "rmsEnergy" -> signature.rmsEnergy
            "dynamicRange" -> signature.dynamicRange
            "spectralCentroid" -> signature.spectralCentroid
            "spectralFlux" -> signature.spectralFlux
            "onsetDensity" -> signature.onsetDensity
            "rhythmActivity" -> signature.rhythmActivity
            else -> 0.5f
        }

        private fun percentile(sorted: List<Float>, p: Double): Float {
            if (sorted.isEmpty()) return 0.5f
            val index = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
            val low = index.toInt()
            val high = min(low + 1, sorted.size - 1)
            val t = (index - low).toFloat()
            return sorted[low] * (1f - t) + sorted[high] * t
        }
    }
}

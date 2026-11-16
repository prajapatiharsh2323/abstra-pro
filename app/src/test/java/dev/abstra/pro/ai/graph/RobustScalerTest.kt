package dev.abstra.pro.ai.graph

import dev.abstra.pro.ai.model.AudioSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robust library-level normalization: median maps to 0.5, outliers clip,
 * and too-few samples gracefully fall back to the raw convention.
 */
class RobustScalerTest {

    private fun signature(songId: String, rmsEnergy: Float, spectralCentroid: Float = 0.5f): AudioSignature =
        AudioSignature(
            songId = songId,
            mfccMean = FloatArray(13) { 0.1f },
            mfccStd = FloatArray(13) { 0.05f },
            chroma = FloatArray(12) { 1f / 12f },
            chromaConfidence = 0.9f,
            tempoBpm = 120f,
            tempoConfidence = 0.9f,
            rmsEnergy = rmsEnergy,
            dynamicRange = 0.5f,
            spectralCentroid = spectralCentroid,
            spectralFlux = 0.5f,
            onsetDensity = 0.5f,
            rhythmActivity = 0.5f,
            featureConfidence = 0.9f,
            extractorVersion = AudioSignature.EXTRACTOR_VERSION,
            metadataFingerprint = "fp-$songId"
        )

    @Test
    fun `library median maps to the neutral center`() {
        // rmsEnergy values 0.0 .. 0.9 -> median 0.45.
        val signatures = (0..9).map { signature("s$it", rmsEnergy = it * 0.1f) }
        val scaler = RobustScaler.of(signatures)

        assertEquals(0.5f, scaler.normalize("rmsEnergy", 0.45f), 0.01f)
    }

    @Test
    fun `symmetric values spread around the median`() {
        val signatures = (0..9).map { signature("s$it", rmsEnergy = it * 0.1f) }
        val scaler = RobustScaler.of(signatures)

        val low = scaler.normalize("rmsEnergy", 0.0f)
        val high = scaler.normalize("rmsEnergy", 0.9f)
        assertTrue("low $low", low < 0.5f)
        assertTrue("high $high", high > 0.5f)
        assertEquals(low, 1f - high, 0.01f)
    }

    @Test
    fun `extreme outliers are clipped`() {
        val signatures = (0..9).map { signature("s$it", rmsEnergy = it * 0.1f) }
        val scaler = RobustScaler.of(signatures)

        assertEquals(0f, scaler.normalize("rmsEnergy", -10f), 0.001f)
        assertEquals(1f, scaler.normalize("rmsEnergy", 10f), 0.001f)
    }

    @Test
    fun `degenerate statistics fall back to the raw convention`() {
        // Fewer than 5 signatures: no usable statistics.
        val scaler = RobustScaler.of(
            listOf(signature("a", 0.2f), signature("b", 0.4f))
        )
        assertEquals(0.5f, scaler.normalize("rmsEnergy", 0.5f), 0f)
        assertEquals(1f, scaler.normalize("rmsEnergy", 2f), 0f)
        assertEquals(0f, scaler.normalize("rmsEnergy", -1f), 0f)
    }

    @Test
    fun `unknown feature names fall back to the raw convention`() {
        val scaler = RobustScaler.of((0..9).map { signature("s$it", rmsEnergy = it * 0.1f) })
        assertEquals(0.3f, scaler.normalize("notAFeature", 0.3f), 0f)
    }
}

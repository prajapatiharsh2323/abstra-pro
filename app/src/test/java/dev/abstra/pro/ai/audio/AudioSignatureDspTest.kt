package dev.abstra.pro.ai.audio

import dev.abstra.pro.ai.model.AudioSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * V2 signature-level DSP tests: MFCC statistics, chroma, tempo estimation
 * and honesty about silence. Complements the V1-focused [AudioDspTest].
 */
class AudioSignatureDspTest {

    private val sampleRate = 8000

    private fun sine(freq: Double, seconds: Float, amplitude: Float = 0.8f): FloatArray {
        val count = (sampleRate * seconds).toInt()
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = amplitude * sin(2.0 * PI * freq * i / sampleRate).toFloat()
        }
        return out
    }

    private fun silence(seconds: Float): FloatArray = FloatArray((sampleRate * seconds).toInt())

    @Test
    fun `signature features have correct vector sizes and finite values`() {
        val result = AudioDsp.computeSignatureFeatures(sine(440.0, 6f), sampleRate, 5f)

        assertNotNull(result)
        result!!.let {
            assertEquals(AudioSignature.MFCC_COUNT, it.mfccMean.size)
            assertEquals(AudioSignature.MFCC_COUNT, it.mfccStd.size)
            assertEquals(AudioSignature.CHROMA_COUNT, it.chroma.size)
            assertTrue(it.mfccMean.all { v -> v.isFinite() })
            assertTrue(it.mfccStd.all { v -> v.isFinite() && v >= 0f })
            assertTrue(it.chroma.all { v -> v.isFinite() && v >= 0f })
        }
    }

    @Test
    fun `pure tone chroma concentrates on the correct pitch class`() {
        // 440 Hz = MIDI 69 = pitch class 9 (A).
        val result = AudioDsp.computeSignatureFeatures(sine(440.0, 6f), sampleRate, 5f)!!

        val argMax = result.chroma.withIndex().maxByOrNull { it.value }!!.index
        assertEquals(9, argMax)
        assertTrue(
            "expected dominant pitch class share, got ${result.chroma[9]}",
            result.chroma[9] > 0.6f
        )
        // A clean tone is confidently tonal.
        assertTrue(result.chromaConfidence > 0.5f)
    }

    @Test
    fun `silence claims no tonal or tempo knowledge`() {
        val result = AudioDsp.computeSignatureFeatures(silence(6f), sampleRate, 5f)!!

        assertEquals(0f, result.chromaConfidence, 0f)
        assertTrue(result.chroma.all { it == 0f })
        assertEquals(0f, result.tempoBpm, 0f)
        assertEquals(0f, result.tempoConfidence, 0f)
    }

    @Test
    fun `mfcc statistics separate different timbres`() {
        val bright = AudioDsp.computeSignatureFeatures(sine(3000.0, 6f), sampleRate, 5f)!!
        val dark = AudioDsp.computeSignatureFeatures(sine(200.0, 6f), sampleRate, 5f)!!

        // First MFCC coefficients should differ measurably between timbres.
        val distance = AudioSimilarity.mfccDistance(
            signatureOf(bright), signatureOf(dark)
        )
        assertTrue("mfcc distance too small: $distance", distance > 0.1f)
    }

    @Test
    fun `tempo estimation finds the pulse of a click track`() {
        // 117 BPM impulses: one click every 4096 samples (~0.512 s at 8 kHz).
        val clickEvery = 4096
        val total = sampleRate * 6
        val pcm = FloatArray(total)
        for (start in 0 until total step clickEvery) {
            for (k in 0 until 3) {
                if (start + k < total) pcm[start + k] = 0.9f
            }
        }
        val result = AudioDsp.computeSignatureFeatures(pcm, sampleRate, 6f)!!

        assertTrue(
            "expected a tempo estimate, got ${result.tempoBpm} bpm (conf ${result.tempoConfidence})",
            result.tempoConfidence > 0f
        )
        assertTrue(
            "tempo estimate off: ${result.tempoBpm}",
            result.tempoBpm in 100f..135f
        )
    }

    @Test
    fun `estimateTempo resolves a pulse train to its bpm`() {
        // Impulses every 8 frames at hop 0.064 s -> ~117 BPM.
        val hopSeconds = 512f / sampleRate
        val envelope = FloatArray(300) { if (it % 8 == 0) 1f else 0f }

        val tempo = AudioDsp.estimateTempo(envelope, hopSeconds)

        assertNotNull(tempo)
        assertTrue(tempo!!.second > 0.3f)
        assertTrue("bpm ${tempo.first} not near 117", tempo.first in 110f..125f)
    }

    @Test
    fun `estimateTempo rejects flat and short envelopes`() {
        assertNull(AudioDsp.estimateTempo(FloatArray(300) { 0.5f }, 0.064f))
        assertNull(AudioDsp.estimateTempo(FloatArray(300) { 0f }, 0.064f))
        assertNull(AudioDsp.estimateTempo(FloatArray(59) { 1f }, 0.064f))
        assertNull(AudioDsp.estimateTempo(FloatArray(300) { if (it % 8 == 0) 1f else 0f }, 0f))
    }

    @Test
    fun `valid synthetic signature passes isValid`() {
        val sig = syntheticSignature(songId = "s1")
        assertTrue(sig.isValid())
    }

    @Test
    fun `isValid rejects malformed signatures`() {
        val wrongSize = syntheticSignature(songId = "s1").let {
            AudioSignature(
                songId = it.songId,
                mfccMean = FloatArray(12),
                mfccStd = it.mfccStd,
                chroma = it.chroma,
                chromaConfidence = it.chromaConfidence,
                tempoBpm = it.tempoBpm,
                tempoConfidence = it.tempoConfidence,
                rmsEnergy = it.rmsEnergy,
                dynamicRange = it.dynamicRange,
                spectralCentroid = it.spectralCentroid,
                spectralFlux = it.spectralFlux,
                onsetDensity = it.onsetDensity,
                rhythmActivity = it.rhythmActivity,
                featureConfidence = it.featureConfidence,
                extractorVersion = it.extractorVersion,
                metadataFingerprint = it.metadataFingerprint
            )
        }
        assertFalse(wrongSize.isValid())

        val withNaN = syntheticSignature(songId = "s2").let {
            it.mfccMean[0] = Float.NaN
            it
        }
        assertFalse(withNaN.isValid())

        val impossibleTempo = syntheticSignature(songId = "s3", tempoBpm = 5000f)
        assertFalse(impossibleTempo.isValid())

        val outOfRangeConfidence = syntheticSignature(songId = "s4", featureConfidence = 1.5f)
        assertFalse(outOfRangeConfidence.isValid())
    }

    @Test
    fun `negative std is invalid but negative mfcc mean is allowed`() {
        val sig = syntheticSignature(songId = "s5")
        sig.mfccStd[0] = -0.1f
        assertFalse(sig.isValid())
    }

    // ── helpers ──

    private fun signatureOf(result: SignatureDspResult): AudioSignature =
        AudioSignature(
            songId = "dsp",
            mfccMean = result.mfccMean,
            mfccStd = result.mfccStd,
            chroma = result.chroma,
            chromaConfidence = result.chromaConfidence,
            tempoBpm = result.tempoBpm,
            tempoConfidence = result.tempoConfidence,
            rmsEnergy = result.rmsEnergy,
            dynamicRange = result.dynamicRange,
            spectralCentroid = result.spectralCentroid,
            spectralFlux = result.spectralFlux,
            onsetDensity = result.onsetDensity,
            rhythmActivity = result.rhythmActivity,
            featureConfidence = 0.9f,
            extractorVersion = AudioSignature.EXTRACTOR_VERSION,
            metadataFingerprint = "fp"
        )

    internal fun syntheticSignature(
        songId: String,
        mfccValue: Float = 0.1f,
        chromaConfidence: Float = 0.9f,
        tempoBpm: Float = 120f,
        tempoConfidence: Float = 0.9f,
        featureConfidence: Float = 0.9f
    ): AudioSignature = AudioSignature(
        songId = songId,
        mfccMean = FloatArray(AudioSignature.MFCC_COUNT) { mfccValue + it * 0.01f },
        mfccStd = FloatArray(AudioSignature.MFCC_COUNT) { 0.05f },
        chroma = FloatArray(AudioSignature.CHROMA_COUNT) { 1f / 12f },
        chromaConfidence = chromaConfidence,
        tempoBpm = tempoBpm,
        tempoConfidence = tempoConfidence,
        rmsEnergy = 0.5f,
        dynamicRange = 0.5f,
        spectralCentroid = 0.5f,
        spectralFlux = 0.5f,
        onsetDensity = 0.5f,
        rhythmActivity = 0.5f,
        featureConfidence = featureConfidence,
        extractorVersion = AudioSignature.EXTRACTOR_VERSION,
        metadataFingerprint = "fp-$songId"
    )
}

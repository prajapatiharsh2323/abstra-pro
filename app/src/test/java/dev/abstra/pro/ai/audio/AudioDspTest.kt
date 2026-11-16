package dev.abstra.pro.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioDspTest {

    private val sampleRate = 8000

    private fun seconds(n: Float): FloatArray {
        val count = (sampleRate * n).toInt()
        return FloatArray(count)
    }

    private fun sine(freq: Double, seconds: Float, amplitude: Float = 0.8f): FloatArray {
        val count = (sampleRate * seconds).toInt()
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = amplitude * sin(2.0 * PI * freq * i / sampleRate).toFloat()
        }
        return out
    }

    @Test
    fun `too short audio returns null`() {
        assertNull(AudioDsp.computeFeatures(FloatArray(1024), sampleRate, 5f))
        // ~0.37s of audio yields fewer than 5 analysis frames.
        assertNull(AudioDsp.computeFeatures(FloatArray(3000), sampleRate, 5f))
        assertNull(AudioDsp.computeFeatures(FloatArray(0), sampleRate, 5f))
    }

    @Test
    fun `silence maps to near-zero rms energy`() {
        val features = AudioDsp.computeFeatures(seconds(6f), sampleRate, 5f)

        assertNotNull(features)
        assertTrue(features!!.rmsEnergy < 0.05f)
    }

    @Test
    fun `loud signal maps to high rms energy`() {
        val features = AudioDsp.computeFeatures(sine(440.0, 6f, amplitude = 0.9f), sampleRate, 5f)

        assertNotNull(features)
        assertTrue(features!!.rmsEnergy > 0.6f)
    }

    @Test
    fun `all features stay within normalized range`() {
        val features = AudioDsp.computeFeatures(sine(1000.0, 6f), sampleRate, 5f)

        assertNotNull(features)
        for (value in listOf(
            features!!.rmsEnergy, features.dynamicRange, features.spectralCentroid,
            features.spectralFlux, features.onsetDensity, features.rhythmActivity, features.coverage
        )) {
            assertTrue("value out of range: $value", value in 0f..1f)
        }
        // Full requested window was provided, so coverage is complete.
        assertEquals(1f, features!!.coverage, 0.05f)
    }

    @Test
    fun `partial coverage is reported honestly`() {
        // Ask for a 5s window but only provide ~2s of audio.
        val features = AudioDsp.computeFeatures(sine(440.0, 2f), sampleRate, 5f)

        assertNotNull(features)
        assertTrue(features!!.coverage < 0.5f)
    }

    @Test
    fun `fft of a pure sine concentrates energy in one bin`() {
        val n = 1024
        val bins = n / 2
        val binIndex = 17
        val re = FloatArray(n) { i -> sin(2.0 * PI * binIndex * i / n).toFloat() }
        val im = FloatArray(n)

        AudioDsp.fft(re, im)

        val magnitudes = FloatArray(bins) { i ->
            kotlin.math.sqrt(re[i] * re[i] + im[i] * im[i])
        }
        val peakIndex = magnitudes.withIndex().maxByOrNull { it.value }!!.index
        assertEquals(binIndex, peakIndex)
        // Energy is concentrated: peak is far larger than other bins.
        val peak = magnitudes[peakIndex]
        val average = magnitudes.sum() / bins
        assertTrue(peak > average * 50f)
    }

    @Test
    fun `fft is invertible`() {
        val n = 256
        val original = FloatArray(n) { i -> sin(2.0 * PI * 10 * i / n).toFloat() }
        val re = original.copyOf()
        val im = FloatArray(n)

        AudioDsp.fft(re, im)
        // Conjugate for inverse transform.
        for (i in 0 until n) im[i] = -im[i]
        AudioDsp.fft(re, im)
        for (i in 0 until n) {
            re[i] /= n
            assertEquals(original[i], re[i], 0.01f)
        }
    }

    @Test
    fun `hann window starts and ends at zero`() {
        val window = AudioDsp.hann(64)
        assertEquals(0f, window.first(), 0.001f)
        assertEquals(0f, window.last(), 0.001f)
        assertEquals(1f, window.max(), 0.001f)
    }

    @Test
    fun `dbfs mapping is sane`() {
        // Digital silence is floored at the 1e-9 epsilon: 20*log10(1e-9) = -180 dB.
        assertEquals(-180f, AudioDsp.dbfs(0f), 0.01f)
        assertEquals(-20f, AudioDsp.dbfs(0.1f), 0.01f)
    }
}

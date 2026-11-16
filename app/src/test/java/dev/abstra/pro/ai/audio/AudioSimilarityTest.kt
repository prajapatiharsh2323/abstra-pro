package dev.abstra.pro.ai.audio

import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audio similarity: confidence-aware exclusion of unreliable information,
 * shift-invariant chroma and half/double-time tempo compatibility.
 */
class AudioSimilarityTest {

    private fun signature(
        songId: String = "s",
        mfccValue: Float = 0.1f,
        chroma: FloatArray = FloatArray(12) { 1f / 12f },
        chromaConfidence: Float = 0.9f,
        tempoBpm: Float = 120f,
        tempoConfidence: Float = 0.9f,
        status: String = AudioSignature.STATUS_SUCCESS,
        mfccSize: Int = 13
    ): AudioSignature = AudioSignature(
        songId = songId,
        mfccMean = FloatArray(mfccSize) { mfccValue },
        mfccStd = FloatArray(mfccSize) { 0.05f },
        chroma = chroma,
        chromaConfidence = chromaConfidence,
        tempoBpm = tempoBpm,
        tempoConfidence = tempoConfidence,
        rmsEnergy = 0.5f,
        dynamicRange = 0.5f,
        spectralCentroid = 0.5f,
        spectralFlux = 0.5f,
        onsetDensity = 0.5f,
        rhythmActivity = 0.5f,
        featureConfidence = 0.9f,
        extractorVersion = AudioSignature.EXTRACTOR_VERSION,
        metadataFingerprint = "fp-$songId",
        analysisStatus = status
    )

    private fun song(id: String, artist: String = "A", album: String = "Al", genre: String? = "Rock") =
        Song(
            id = id, title = "Song $id", artist = artist, album = album,
            durationMs = 200_000L, uriString = "content://x/$id", folderName = "Music",
            filePath = "/storage/$id.mp3", dateAdded = 0L, genre = genre
        )

    @Test
    fun `identical signatures are fully similar`() {
        val a = signature("a")
        val b = signature("b")
        assertEquals(1f, AudioSimilarity.similarity(a, b)!!, 1e-4f)
    }

    @Test
    fun `small timbre differences lower similarity without destroying it`() {
        val a = signature("a", mfccValue = 0.1f)
        val b = signature("b", mfccValue = 0.18f)
        val sim = AudioSimilarity.similarity(a, b)!!
        assertTrue("expected similarity below 1, got $sim", sim < 1f)
        assertTrue("expected similarity above 0.5, got $sim", sim > 0.5f)
    }

    @Test
    fun `invalid or failed signatures return null for fallback`() {
        assertNull(AudioSimilarity.similarity(signature("a", mfccSize = 12), signature("b")))
        assertNull(AudioSimilarity.similarity(signature("a"), signature("b", status = AudioSignature.STATUS_FAILED)))
    }

    @Test
    fun `low chroma confidence excludes chroma from the comparison`() {
        // Whole-tone vs major triad: not transpositions of each other.
        val chromaA = FloatArray(12) { if (it == 0 || it == 4 || it == 7) 1f else 0f }
        val chromaB = FloatArray(12) { if (it % 2 == 0) 1f else 0f }
        val reliable = signature("a", chroma = chromaA)
        val other = signature("b", chroma = chromaB)

        // With reliable chroma the opposing tonal profiles count.
        val withChroma = AudioSimilarity.similarity(reliable, other)!!
        // With unreliable chroma the difference is excluded, not counted.
        val withoutChroma = AudioSimilarity.similarity(
            reliable.copySelf(chromaConfidence = 0.1f),
            other.copySelf(chromaConfidence = 0.1f)
        )!!

        assertTrue(withChroma < 1f)
        assertEquals(1f, withoutChroma, 1e-4f)
    }

    @Test
    fun `low tempo confidence excludes tempo from the comparison`() {
        val a = signature("a", tempoBpm = 70f)
        val b = signature("b", tempoBpm = 190f)

        val withTempo = AudioSimilarity.similarity(a, b)!!
        val withoutTempo = AudioSimilarity.similarity(
            a, b.copySelf(tempoConfidence = 0.1f)
        )!!

        assertTrue(withTempo < 1f)
        assertEquals(1f, withoutTempo, 1e-4f)
    }

    @Test
    fun `chroma distance is invariant to pitch-class shifts`() {
        val major = FloatArray(12) { if (it == 0 || it == 4 || it == 7) 1f else 0f }
        val transposed = FloatArray(12) { if (it == 3 || it == 7 || it == 10) 1f else 0f }

        assertEquals(0f, AudioSimilarity.chromaDistance(major, transposed), 0.01f)
        assertEquals(0f, AudioSimilarity.chromaDistance(major, major), 0f)
    }

    @Test
    fun `chroma distance distinguishes unrelated profiles`() {
        // Major triad vs whole-tone scale: no pitch-class shift maps one
        // onto the other.
        val major = FloatArray(12) { if (it == 0 || it == 4 || it == 7) 1f else 0f }
        val wholeTone = FloatArray(12) { if (it % 2 == 0) 1f else 0f }

        val distance = AudioSimilarity.chromaDistance(major, wholeTone)
        assertTrue("expected unrelated profiles to differ, got $distance", distance in 0.15f..0.35f)
    }

    @Test
    fun `tempo distance accepts half and double time relationships`() {
        assertEquals(0f, AudioSimilarity.tempoDistance(120f, 240f), 0.01f)
        assertEquals(0f, AudioSimilarity.tempoDistance(120f, 60f), 0.01f)
        assertTrue(AudioSimilarity.tempoDistance(120f, 126f) < 0.1f)
        // 60 vs 200 is beyond the half/double-time compatibility range but
        // the best-fit probing keeps it from being maximal.
        val far = AudioSimilarity.tempoDistance(60f, 200f)
        assertTrue("expected a clearly distant tempo, got $far", far > 0.4f)
        // Unknown tempo is neutral, not dissimilar.
        assertEquals(AudioSimilarity.NEUTRAL_SIMILARITY, AudioSimilarity.tempoDistance(0f, 120f), 0f)
    }

    @Test
    fun `metadata similarity handles the obvious cases`() {
        val a = song("a")
        assertEquals(1f, AudioSimilarity.metadataSimilarity(a, a), 0f)

        val sameEverything = song("b")
        assertTrue(AudioSimilarity.metadataSimilarity(a, sameEverything) > 0.7f)

        val different = song("c", artist = "Other", album = "Different", genre = "Jazz")
        assertTrue(
            AudioSimilarity.metadataSimilarity(a, different) <
                    AudioSimilarity.metadataSimilarity(a, sameEverything)
        )

        val unknown = song("d", artist = "", album = "", genre = null)
        val neutral = AudioSimilarity.metadataSimilarity(a, unknown)
        assertTrue("unknown metadata should stay near neutral, got $neutral", neutral in 0.3f..0.7f)
    }

    @Test
    fun `similarity of silent-but-valid signatures stays neutral`() {
        val a = signature("a", mfccValue = 0f, chromaConfidence = 0f, tempoConfidence = 0f)
        val b = signature("b", mfccValue = 0f, chromaConfidence = 0f, tempoConfidence = 0f)
        assertNotNull(AudioSimilarity.similarity(a, b))
        assertEquals(1f, AudioSimilarity.similarity(a, b)!!, 1e-4f)
    }

    private fun AudioSignature.copySelf(
        chromaConfidence: Float = this.chromaConfidence,
        tempoConfidence: Float = this.tempoConfidence
    ): AudioSignature = AudioSignature(
        songId = songId,
        mfccMean = mfccMean,
        mfccStd = mfccStd,
        chroma = chroma,
        chromaConfidence = chromaConfidence,
        tempoBpm = tempoBpm,
        tempoConfidence = tempoConfidence,
        rmsEnergy = rmsEnergy,
        dynamicRange = dynamicRange,
        spectralCentroid = spectralCentroid,
        spectralFlux = spectralFlux,
        onsetDensity = onsetDensity,
        rhythmActivity = rhythmActivity,
        featureConfidence = featureConfidence,
        extractorVersion = extractorVersion,
        metadataFingerprint = metadataFingerprint,
        analysisStatus = analysisStatus,
        analyzedAtMs = analyzedAtMs
    )
}

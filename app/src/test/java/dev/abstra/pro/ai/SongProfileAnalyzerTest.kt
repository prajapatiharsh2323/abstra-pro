package dev.abstra.pro.ai

import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the metadata-first profile analyzer.
 */
class SongProfileAnalyzerTest {

    private fun song(
        id: String = "s1",
        title: String = "Test Song",
        artist: String = "Artist",
        album: String = "Album",
        genre: String? = null
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = 200_000L,
        uriString = "content://test/$id",
        folderName = "Music",
        filePath = "/storage/music/$id.mp3",
        dateAdded = 1_000_000L,
        genre = genre
    )

    @Test
    fun `no evidence produces neutral low-confidence profile`() {
        val profile = SongProfileAnalyzer.analyze(song())

        assertEquals(0.5f, profile.energy, 0.01f)
        assertTrue(profile.energyConfidence < 0.05f)
        assertTrue(profile.acousticConfidence < 0.05f)
        assertEquals(SongProfileAnalyzer.PROFILE_SOURCE_METADATA, profile.profileSource)
        assertTrue(profile.tags.isEmpty())
    }

    @Test
    fun `acoustic title signals low energy and high acousticness`() {
        val profile = SongProfileAnalyzer.analyze(song(title = "Sunset (Acoustic Version)"))

        assertTrue(profile.energy < 0.4f)
        assertTrue(profile.acousticness > 0.6f)
        assertTrue("acoustic" in profile.tags)
        assertTrue(profile.acousticConfidence > 0.3f)
    }

    @Test
    fun `remix title signals higher energy`() {
        val plain = SongProfileAnalyzer.analyze(song(title = "Sunset"))
        val remix = SongProfileAnalyzer.analyze(song(title = "Sunset (Club Remix)"))

        assertTrue(remix.energy > plain.energy)
        assertTrue("remix" in remix.tags)
    }

    @Test
    fun `instrumental keywords raise instrumental likelihood`() {
        val profile = SongProfileAnalyzer.analyze(song(title = "Dreaming (Instrumental)"))

        assertTrue(profile.instrumentalLikelihood > 0.5f)
        assertTrue("instrumental" in profile.tags)
    }

    @Test
    fun `genre family contributes moderate evidence`() {
        val profile = SongProfileAnalyzer.analyze(song(title = "Track 1", genre = "Metal"))

        assertTrue(profile.energy > 0.7f)
        assertTrue(profile.energyConfidence > 0.2f)
        // Metadata-only confidence is capped below 1.0.
        assertTrue(profile.energyConfidence <= SongProfileAnalyzerTestCap)
    }

    @Test
    fun `conflicting evidence lowers confidence`() {
        // "Acoustic" (low energy) + "Banger" (high energy) conflict.
        val profile = SongProfileAnalyzer.analyze(song(title = "Acoustic Banger"))
        val single = SongProfileAnalyzer.analyze(song(title = "Banger"))

        assertTrue(profile.energyConfidence < single.energyConfidence)
    }

    @Test
    fun `fingerprint is stable and changes with metadata`() {
        val a = SongProfileAnalyzer.fingerprint(song())
        val b = SongProfileAnalyzer.fingerprint(song())
        val c = SongProfileAnalyzer.fingerprint(song(title = "Different"))

        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun `combineFeatures weights audio over metadata when confident`() {
        val blended = SongProfileAnalyzer.combineFeatures(
            metadataValue = 0.5f,
            metadataConfidence = 0.6f,
            audioValue = 0.9f,
            audioConfidence = 1f
        )
        // Audio contributes 65% of the combined weight.
        assertTrue(blended > 0.75f)
    }

    @Test
    fun `blendFeatures produces BLENDED profile with higher energy confidence`() {
        val metadata = SongProfileAnalyzer.analyze(song(title = "Track", genre = "Rock"))
        val features = dev.abstra.pro.ai.model.AudioFeatures(
            songId = metadata.songId,
            rmsEnergy = 0.9f,
            dynamicRange = 0.5f,
            spectralCentroid = 0.6f,
            spectralFlux = 0.4f,
            onsetDensity = 0.7f,
            rhythmActivity = 0.6f,
            confidence = 0.8f,
            analyzedAtMs = 0L
        )
        val blended = SongProfileAnalyzer.blendFeatures(metadata, features)

        assertEquals(SongProfileAnalyzer.PROFILE_SOURCE_BLENDED, blended.profileSource)
        assertTrue(blended.energyConfidence > metadata.energyConfidence)
        assertTrue(blended.rhythmActivity != null)
    }

    @Test
    fun `blendFeatures with zero confidence keeps metadata profile`() {
        val metadata = SongProfileAnalyzer.analyze(song(title = "Track"))
        val features = dev.abstra.pro.ai.model.AudioFeatures(
            songId = metadata.songId,
            rmsEnergy = 0.9f,
            dynamicRange = 0.5f,
            spectralCentroid = 0.6f,
            spectralFlux = 0.4f,
            onsetDensity = 0.7f,
            rhythmActivity = 0.6f,
            confidence = 0f,
            analyzedAtMs = 0L
        )
        assertEquals(metadata, SongProfileAnalyzer.blendFeatures(metadata, features))
    }

    @Test
    fun `genreCompatibility distinguishes families`() {
        assertEquals(1f, SongProfileAnalyzer.genreCompatibility("Rock", "Metal"), 0.01f)
        assertEquals(0.6f, SongProfileAnalyzer.genreCompatibility(null, "Rock"), 0.01f)
        assertEquals(0.4f, SongProfileAnalyzer.genreCompatibility("Rock", "Classical"), 0.01f)
    }

    private companion object {
        const val SongProfileAnalyzerTestCap = 0.85f
    }
}

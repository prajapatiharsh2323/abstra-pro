package dev.abstra.pro.ai

import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AffinityModelTest {

    private fun song(
        id: String,
        artist: String = "Artist A",
        genre: String? = null,
        favorite: Boolean = false
    ) = Song(
        id = id,
        title = "Song $id",
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        uriString = "content://test/$id",
        folderName = "Music",
        filePath = "/storage/music/$id.mp3",
        dateAdded = 0L,
        genre = genre,
        isFavorite = favorite
    )

    @Test
    fun `no history and no favorite is neutral with zero confidence`() {
        val affinity = AffinityModel.trackAffinity(null, isFavorite = false)

        assertEquals(AffinityModel.NEUTRAL_AFFINITY, affinity.value, 0.01f)
        assertEquals(0f, affinity.confidence, 0.01f)
    }

    @Test
    fun `favorite is strongly liked`() {
        val affinity = AffinityModel.trackAffinity(null, isFavorite = true)

        assertTrue(affinity.value >= 0.9f)
        assertEquals(1f, affinity.confidence, 0.01f)
    }

    @Test
    fun `high completions with no skips scores high`() {
        val telemetry = PlaybackTelemetryEntity(
            songId = "s1",
            playCount = 10,
            fullCompletions = 9,
            fastSkips = 0,
            repeatCount = 2
        )
        val affinity = AffinityModel.trackAffinity(telemetry, isFavorite = false)

        // No favorites and modest frequency: mid-high, clearly positive.
        assertTrue(affinity.value > 0.45f)
        assertEquals(0.9f, affinity.completionRate, 0.01f)
        val skipper = AffinityModel.trackAffinity(
            telemetry.copy(fullCompletions = 0, fastSkips = 8), isFavorite = false
        )
        assertTrue("skipping must hurt", affinity.value > skipper.value)
    }

    @Test
    fun `heavy fast skipping scores low`() {
        val telemetry = PlaybackTelemetryEntity(
            songId = "s1",
            playCount = 10,
            fullCompletions = 1,
            fastSkips = 8
        )
        val affinity = AffinityModel.trackAffinity(telemetry, isFavorite = false)

        assertTrue(affinity.value < 0.35f)
        assertTrue(affinity.quickSkipRate > 0.7f)
    }

    @Test
    fun `aggregates average favorite artist tracks`() {
        val songs = listOf(
            song("s1", favorite = true),
            song("s2", favorite = true),
            song("s3", artist = "Artist B", favorite = true)
        )
        val aggregates = AffinityModel.buildAggregates(songs, emptyMap(), emptyMap())

        val a = aggregates.artist.getValue("artist a")
        val b = aggregates.artist.getValue("artist b")
        // Smoothed averages sit above the neutral midpoint for favorites.
        assertTrue(a.value > AffinityModel.NEUTRAL_AFFINITY)
        assertTrue(b.value > AffinityModel.NEUTRAL_AFFINITY)
        // Confidence grows with evidence, but stays below 1 with so few plays.
        assertTrue(a.confidence > b.confidence)
        assertTrue(a.confidence < 1f)
    }

    @Test
    fun `songs without history are excluded from aggregates`() {
        val songs = listOf(song("s1"), song("s2"))
        val aggregates = AffinityModel.buildAggregates(songs, emptyMap(), emptyMap())

        assertTrue(aggregates.artist.isEmpty())
        assertTrue(aggregates.genre.isEmpty())
    }

    @Test
    fun `genre aggregation splits composite genres`() {
        val songs = listOf(song("s1", genre = "Rock; Pop", favorite = true))
        val aggregates = AffinityModel.buildAggregates(songs, emptyMap(), emptyMap())

        assertTrue("rock" in aggregates.genre)
        assertTrue("pop" in aggregates.genre)
    }
}

package dev.abstra.pro.ai.recommendation

import dev.abstra.pro.data.db.entities.PlaybackFeedbackEntity
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confidence-aware taste model: time decay, Bayesian smoothing, and the
 * rule that a single skip never becomes a negative PageRank seed.
 */
class UserTasteModelTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun event(
        songId: String,
        type: String,
        ageDays: Long = 0
    ) = PlaybackFeedbackEntity(
        songId = songId,
        eventType = type,
        occurredAtMs = now - ageDays * day,
        listenedMs = 0L,
        durationMs = 0L
    )

    private fun song(id: String, artist: String = "A") = Song(
        id = id, title = "Song $id", artist = artist, album = "Al",
        durationMs = 200_000L, uriString = "content://x/$id", folderName = "Music",
        filePath = "/storage/$id.mp3", dateAdded = 0L, genre = null
    )

    @Test
    fun `decay weight halves at the half life`() {
        assertEquals(1f, UserTasteModel.decayWeight(0f, 10f), 1e-4f)
        assertEquals(0.5f, UserTasteModel.decayWeight(10f, 10f), 0.01f)
        assertEquals(0.25f, UserTasteModel.decayWeight(20f, 10f), 0.01f)
    }

    @Test
    fun `event strengths follow the documented scale`() {
        assertEquals(UserTasteModel.STRENGTH_FAVORITE_ADDED, UserTasteModel.eventStrength(PlaybackFeedbackEntity.EVENT_FAVORITE_ADDED), 0f)
        assertEquals(UserTasteModel.STRENGTH_COMPLETION, UserTasteModel.eventStrength(PlaybackFeedbackEntity.EVENT_COMPLETION), 0f)
        assertEquals(UserTasteModel.STRENGTH_FAST_SKIP, UserTasteModel.eventStrength(PlaybackFeedbackEntity.EVENT_FAST_SKIP), 0f)
        assertEquals(0f, UserTasteModel.eventStrength("UNKNOWN"), 0f)
    }

    @Test
    fun `songs without events are absent and therefore neutral`() {
        val tastes = UserTasteModel.buildTrackTastes(emptyList(), emptyMap(), now)
        assertFalse(tastes.containsKey("anything"))
    }

    @Test
    fun `a recent favorite is strong confident positive evidence`() {
        val tastes = UserTasteModel.buildTrackTastes(
            listOf(event("s", PlaybackFeedbackEntity.EVENT_FAVORITE_ADDED)),
            emptyMap(), now
        )
        val taste = tastes.getValue("s")

        assertTrue("positive ${taste.positive}", taste.positive > 0.9f)
        assertTrue("confidence ${taste.confidence}", taste.confidence > 0.9f)
        assertTrue(taste.negative < 0.01f)
    }

    @Test
    fun `old evidence loses influence`() {
        val recent = UserTasteModel.buildTrackTastes(
            listOf(event("recent", PlaybackFeedbackEntity.EVENT_FAVORITE_ADDED)),
            emptyMap(), now
        ).getValue("recent")
        val old = UserTasteModel.buildTrackTastes(
            listOf(event("old", PlaybackFeedbackEntity.EVENT_FAVORITE_ADDED, ageDays = 400)),
            emptyMap(), now
        ).getValue("old")

        assertTrue("old ${old.positive} should be below recent ${recent.positive}", old.positive < recent.positive)
        // 400 days is more than two long-term half lives.
        assertTrue(old.positive < 0.6f)
    }

    @Test
    fun `completion rate and recent engagement are reported`() {
        val tastes = UserTasteModel.buildTrackTastes(
            listOf(
                event("s", PlaybackFeedbackEntity.EVENT_COMPLETION),
                event("s", PlaybackFeedbackEntity.EVENT_MEANINGFUL_PLAY)
            ),
            emptyMap(), now
        )
        val taste = tastes.getValue("s")

        assertEquals(0.5f, taste.completionRate, 0.01f)
        assertTrue(taste.recentEngagement > 0f)
    }

    @Test
    fun `telemetry is only a supporting fallback for unknown songs`() {
        val telemetry = mapOf(
            "played" to PlaybackTelemetryEntity(songId = "played", playCount = 5)
        )
        val tastes = UserTasteModel.buildTrackTastes(emptyList(), telemetry, now)

        // Telemetry alone creates no taste claims — feedback events do.
        assertFalse(tastes.containsKey("played"))
    }

    @Test
    fun `repeated fast skips become a negative seed`() {
        val events = (0 until 3).map { event("s", PlaybackFeedbackEntity.EVENT_FAST_SKIP) }
        val tastes = UserTasteModel.buildTrackTastes(events, emptyMap(), now)
        val seeds = UserTasteModel.buildNegativeSeeds(tastes)

        assertTrue("s" in seeds)
        assertTrue(seeds.getValue("s") > 0f)
    }

    @Test
    fun `a single fast skip is never a negative seed`() {
        val tastes = UserTasteModel.buildTrackTastes(
            listOf(event("s", PlaybackFeedbackEntity.EVENT_FAST_SKIP)),
            emptyMap(), now
        )
        val seeds = UserTasteModel.buildNegativeSeeds(tastes)

        assertFalse("single skip must not seed", "s" in seeds)
    }

    @Test
    fun `two fast skips are still not enough`() {
        val tastes = UserTasteModel.buildTrackTastes(
            listOf(
                event("s", PlaybackFeedbackEntity.EVENT_FAST_SKIP),
                event("s", PlaybackFeedbackEntity.EVENT_FAST_SKIP, ageDays = 1)
            ),
            emptyMap(), now
        )

        assertFalse("s" in UserTasteModel.buildNegativeSeeds(tastes))
    }

    @Test
    fun `aggregates shrink toward the prior and cap prolific songs`() {
        val tastes = mapOf(
            "a" to UserTasteModel.TrackTaste(positive = 1f, negative = 0f, confidence = 1f, negativeCount = 0),
            "b" to UserTasteModel.TrackTaste(positive = 1f, negative = 0f, confidence = 1f, negativeCount = 0),
            "unknown" to UserTasteModel.TrackTaste(positive = 0f, negative = 0f, confidence = 0f, negativeCount = 0)
        )
        val songs = listOf(song("a"), song("b"), song("unknown"))
        val aggregates = UserTasteModel.buildAggregates(songs, tastes) { listOf("artist") }

        val artist = aggregates.getValue("artist")
        // Two perfect tracks with prior shrinkage: (2 + 0.5*2)/(2 + 2) = 0.75.
        assertEquals(0.75f, artist.value, 0.01f)
        // Two songs -> confidence 2/4.
        assertEquals(0.5f, artist.confidence, 0.001f)
    }

    @Test
    fun `aggregate confidence caps at one after enough distinct songs`() {
        val tastes = (0 until 10).associate { i ->
            "s$i" to UserTasteModel.TrackTaste(positive = 1f, negative = 0f, confidence = 1f, negativeCount = 0)
        }
        val songs = (0 until 10).map { song("s$it") }
        val aggregates = UserTasteModel.buildAggregates(songs, tastes) { listOf("artist") }

        assertEquals(1f, aggregates.getValue("artist").confidence, 0.001f)
    }
}

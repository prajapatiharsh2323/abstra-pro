package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.ai.model.TimeOfDay
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistScorerTest {

    private val context = ScoringContext(
        nowMs = 1_700_000_000_000L,
        timeOfDay = TimeOfDay.AFTERNOON,
        isWeekend = false
    )

    private fun song(
        id: String,
        genre: String? = null,
        favorite: Boolean = false,
        dateAdded: Long = 0L
    ) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        uriString = "content://test/$id",
        folderName = "Music",
        filePath = "/storage/music/$id.mp3",
        dateAdded = dateAdded,
        genre = genre,
        isFavorite = favorite
    )

    private fun profile(
        songId: String,
        energy: Float,
        energyConfidence: Float = 0.8f
    ) = SongProfile(
        songId = songId,
        energy = energy,
        acousticness = 0.3f,
        instrumentalLikelihood = 0.1f,
        tags = emptySet(),
        energyConfidence = energyConfidence,
        metadataFingerprint = "fp",
        analysisVersion = 1
    )

    private fun baseScored(
        id: String = "s1",
        energy: Float = 0.5f,
        favorite: Boolean = false,
        telemetry: PlaybackTelemetryEntity? = null
    ): dev.abstra.pro.ai.BaseScores {
        val s = song(id, favorite = favorite)
        return PlaylistScorer.scoreBase(s, profile(id, energy), telemetry, context)
    }

    @Test
    fun `favorite song gets favorite reason`() {
        val base = baseScored(favorite = true)

        assertTrue(dev.abstra.pro.ai.model.RecommendationReason.FAVORITE_SONG in base.reasons)
        assertTrue(base.preferenceValue >= 0.9f)
    }

    @Test
    fun `unplayed song is a fresh discovery`() {
        val base = baseScored()

        assertTrue(dev.abstra.pro.ai.model.RecommendationReason.FRESH_DISCOVERY in base.reasons)
        assertTrue(base.freshnessValue > 0.7f)
    }

    @Test
    fun `recently played song has low freshness`() {
        val now = 1_700_000_000_000L
        val telemetry = PlaybackTelemetryEntity(
            songId = "s1",
            playCount = 5,
            lastPlayedTimestamp = now - 1L * 24L * 60L * 60L * 1000L
        )
        val base = baseScored(telemetry = telemetry)

        assertTrue(base.freshnessValue < 0.2f)
    }

    @Test
    fun `long-unplayed song is not stale`() {
        val now = 1_700_000_000_000L
        val telemetry = PlaybackTelemetryEntity(
            songId = "s1",
            playCount = 5,
            lastPlayedTimestamp = now - 30L * 24L * 60L * 60L * 1000L
        )
        val base = baseScored(telemetry = telemetry)

        assertTrue(dev.abstra.pro.ai.model.RecommendationReason.NOT_PLAYED_RECENTLY in base.reasons)
        assertTrue(base.freshnessValue > 0.7f)
    }

    @Test
    fun `energetic song scores higher for workout than chill preset`() {
        val workout = MixPresetCatalog.byId(MixPresetCatalog.PRESET_WORKOUT)
        val chill = MixPresetCatalog.byId(MixPresetCatalog.PRESET_CHILL)

        val base = baseScored(energy = 0.9f)
        val forWorkout = PlaylistScorer.score(base, workout)
        val forChill = PlaylistScorer.score(base, chill)

        assertTrue(forWorkout.score > forChill.score)
        assertTrue(forWorkout.moodValue > forChill.moodValue)
    }

    @Test
    fun `low confidence mood signals do not penalize score`() {
        // Profile with zero confidence: mood weight must be redistributed,
        // not counted as a bad mood match.
        val preset = MixPresetCatalog.byId(MixPresetCatalog.PRESET_WORKOUT)
        val unconfident = profile("s1", energy = 0.9f, energyConfidence = 0f)

        val base = PlaylistScorer.scoreBase(song("s1"), unconfident, null, context)
        val scored = PlaylistScorer.score(base, preset)

        assertEquals(0f, scored.moodConfidence, 0.01f)
        // Freshness still contributes: the score must not collapse.
        assertTrue(scored.score > 0.2f)
    }

    @Test
    fun `weights sum to one`() {
        assertEquals(
            1f,
            PlaylistScorer.WEIGHT_MOOD + PlaylistScorer.WEIGHT_PREFERENCE +
                    PlaylistScorer.WEIGHT_ARTIST_GENRE + PlaylistScorer.WEIGHT_FRESHNESS +
                    PlaylistScorer.WEIGHT_CONTEXT,
            0.001f
        )
    }
}

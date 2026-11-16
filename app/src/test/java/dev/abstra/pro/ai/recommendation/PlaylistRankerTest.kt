package dev.abstra.pro.ai.recommendation

import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confidence-aware final ranker:
 * S = clamp(0.5 + 2 * Σ w_i * c_i * (v_i - 0.5), 0, 1)
 * Missing information leaves the neutral baseline; it is never re-normalized.
 */
class PlaylistRankerTest {

    private fun song(id: String) = Song(
        id = id, title = "Song $id", artist = "A", album = "Al",
        durationMs = 200_000L, uriString = "content://x/$id", folderName = "Music",
        filePath = "/storage/$id.mp3", dateAdded = 0L, genre = null
    )

    private fun profile(songId: String) = SongProfile(
        songId = songId,
        energy = 0.5f,
        acousticness = 0.3f,
        instrumentalLikelihood = 0.1f,
        metadataFingerprint = "fp",
        analysisVersion = 2
    )

    private fun input(
        id: String = "s",
        fusedScore: Float = 0f,
        pprScore: Float? = null,
        audioSimilarity: Float? = null,
        preference: UserTasteModel.TrackTaste? = null,
        moodValue: Float = 0.5f,
        moodConfidence: Float = 0f,
        artistGenreValue: Float = 0.5f,
        artistGenreConfidence: Float = 0f,
        noveltyValue: Float = 0.5f,
        noveltyConfidence: Float = 0f,
        playCount: Int = 0
    ) = PlaylistRanker.CandidateInput(
        song = song(id),
        profile = profile(id),
        fusedScore = fusedScore,
        pprScore = pprScore,
        audioSimilarity = audioSimilarity,
        preference = preference,
        moodValue = moodValue,
        moodConfidence = moodConfidence,
        artistGenreValue = artistGenreValue,
        artistGenreConfidence = artistGenreConfidence,
        noveltyValue = noveltyValue,
        noveltyConfidence = noveltyConfidence,
        playCount = playCount
    )

    @Test
    fun `no information means the neutral baseline`() {
        val scored = PlaylistRanker.score(input())

        assertEquals(0.5f, scored.score, 1e-4f)
        assertEquals(0f, scored.confidence, 1e-4f)
        assertFalse(scored.isDiscovery)
    }

    @Test
    fun `strong measured signals push the score up and clamp at one`() {
        val scored = PlaylistRanker.score(
            input(
                fusedScore = 1f,
                audioSimilarity = 1f,
                preference = UserTasteModel.TrackTaste(
                    positive = 1f, negative = 0f, confidence = 1f
                ),
                moodValue = 1f,
                moodConfidence = 1f,
                artistGenreValue = 1f,
                artistGenreConfidence = 1f,
                noveltyValue = 1f,
                noveltyConfidence = 1f
            )
        )

        assertEquals(1f, scored.score, 1e-4f)
        assertTrue(scored.confidence > 0.9f)
    }

    @Test
    fun `strong negative preference pushes the score below the baseline`() {
        val scored = PlaylistRanker.score(
            input(
                preference = UserTasteModel.TrackTaste(
                    positive = 0f, negative = 1f, confidence = 1f
                )
            )
        )

        // Only the preference component is active: 0.5 + 2 * 0.2 * 1 * (-0.5).
        assertEquals(0.3f, scored.score, 0.01f)
    }

    @Test
    fun `low confidence limits how much a value can move the score`() {
        val extremeLowConfidence = PlaylistRanker.score(
            input(moodValue = 0f, moodConfidence = 0.05f)
        )
        val confidentSameValue = PlaylistRanker.score(
            input(moodValue = 0f, moodConfidence = 1f)
        )

        assertTrue(extremeLowConfidence.score > confidentSameValue.score)
        // Nearly no influence at all.
        assertTrue(kotlin.math.abs(extremeLowConfidence.score - 0.5f) < 0.05f)
    }

    @Test
    fun `ppr score is combined with the fused retrieval score`() {
        val withoutPpr = PlaylistRanker.score(input(fusedScore = 0.8f))
        val withPpr = PlaylistRanker.score(input(fusedScore = 0.8f, pprScore = 0.002f))

        assertTrue(withPpr.score > withoutPpr.score)
    }

    @Test
    fun `discovery requires relevance evidence and few plays`() {
        val relevantUnplayed = PlaylistRanker.score(input(fusedScore = 0.6f))
        val playedALot = PlaylistRanker.score(input(fusedScore = 0.6f, playCount = 5))
        val irrelevantUnplayed = PlaylistRanker.score(input())

        assertTrue(relevantUnplayed.isDiscovery)
        assertFalse(playedALot.isDiscovery)
        assertFalse(irrelevantUnplayed.isDiscovery)
    }

    @Test
    fun `component diagnostics are recorded`() {
        val scored = PlaylistRanker.score(input(audioSimilarity = 0.9f))

        val names = scored.components.map { it.name }
        assertTrue(
            setOf(
                "graph_relevance", "audio_similarity", "preference",
                "mood", "artist_genre", "novelty"
            ).all { it in names }
        )
        val audio = scored.components.first { it.name == "audio_similarity" }
        assertEquals(0.9f, audio.value, 0f)
        assertTrue(audio.confidence > 0f)
    }

    @Test
    fun `static weights are unchanged from the documented configuration`() {
        assertEquals(
            PlaylistRanker.WEIGHT_GRAPH_RELEVANCE + PlaylistRanker.WEIGHT_AUDIO_SIMILARITY +
                    PlaylistRanker.WEIGHT_PREFERENCE + PlaylistRanker.WEIGHT_MOOD +
                    PlaylistRanker.WEIGHT_ARTIST_GENRE + PlaylistRanker.WEIGHT_NOVELTY,
            PlaylistRanker.sumWeights(),
            1e-4f
        )
    }
}

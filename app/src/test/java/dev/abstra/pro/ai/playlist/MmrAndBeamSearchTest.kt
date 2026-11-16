package dev.abstra.pro.ai.playlist

import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.ai.recommendation.PlaylistRanker
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate-pool diversity (MMR) and beam-search sequencing: path quality
 * beyond greedy relevance, diversity constraints, and determinism.
 */
class MmrAndBeamSearchTest {

    // ── MMR ──

    private data class C(val id: String, val relevance: Float)

    @Test
    fun `small pools are returned sorted by relevance`() {
        val selected = Mmr.select(
            candidates = listOf(C("a", 0.3f), C("b", 0.9f), C("c", 0.6f)),
            relevanceOf = { it.relevance },
            idOf = { it.id },
            similarityOf = { _, _ -> 0f },
            targetSize = 5
        )
        assertEquals(listOf("b", "c", "a"), selected.map { it.id })
    }

    @Test
    fun `mmr trades relevance for diversity among similar candidates`() {
        // Three near-identical relevant candidates and three equally similar
        // slightly-less-relevant ones: greedy top-2 would take a1 and a2.
        val candidates = listOf(
            C("a1", 1.0f), C("a2", 1.0f), C("a3", 1.0f),
            C("b1", 0.95f), C("b2", 0.95f), C("b3", 0.95f)
        )
        fun sim(x: C, y: C): Float = if (x.id[0] == y.id[0]) 1f else 0f

        val selected = Mmr.select(
            candidates, { it.relevance }, { it.id }, ::sim, targetSize = 2
        )

        assertEquals(2, selected.size)
        assertEquals("a1", selected[0].id)
        // The second pick must come from the other group despite lower relevance.
        assertTrue("expected a cross-group pick, got ${selected[1].id}", selected[1].id.startsWith("b"))
    }

    @Test
    fun `relevance still wins when candidates are dissimilar`() {
        val candidates = listOf(C("low", 0.2f), C("high", 1.0f))
        val selected = Mmr.select(
            candidates, { it.relevance }, { it.id }, { _, _ -> 0f }, targetSize = 1
        )
        assertEquals("high", selected[0].id)
    }

    // ── Beam search sequencer ──

    private fun song(id: String, artist: String, durationMs: Long = 60_000L) = Song(
        id = id, title = "Song $id", artist = artist, album = "Album",
        durationMs = durationMs, uriString = "content://x/$id", folderName = "Music",
        filePath = "/storage/$id.mp3", dateAdded = 0L, genre = null
    )

    private fun profile(songId: String) = SongProfile(
        songId = songId,
        energy = 0.5f,
        acousticness = 0.3f,
        instrumentalLikelihood = 0.1f,
        energyConfidence = 0.8f,
        metadataFingerprint = "fp",
        analysisVersion = 2
    )

    private fun signature(songId: String, mfccValue: Float) = AudioSignature(
        songId = songId,
        mfccMean = FloatArray(13) { mfccValue },
        mfccStd = FloatArray(13) { 0.05f },
        chroma = FloatArray(12) { 1f / 12f },
        chromaConfidence = 0.9f,
        tempoBpm = 120f,
        tempoConfidence = 0.9f,
        rmsEnergy = 0.5f,
        dynamicRange = 0.5f,
        spectralCentroid = 0.5f,
        spectralFlux = 0.5f,
        onsetDensity = 0.5f,
        rhythmActivity = 0.5f,
        featureConfidence = 0.9f,
        extractorVersion = AudioSignature.EXTRACTOR_VERSION,
        metadataFingerprint = "fp-$songId"
    )

    private fun scored(id: String, fusedScore: Float): PlaylistRanker.Scored {
        val song = song(id, artist = "shared")
        return PlaylistRanker.score(
            PlaylistRanker.CandidateInput(
                song = song,
                profile = profile(id),
                fusedScore = fusedScore,
                pprScore = null,
                audioSimilarity = null,
                preference = null
            )
        )
    }

    private fun input(
        candidates: List<PlaylistRanker.Scored>,
        signatures: Map<String, AudioSignature>,
        seed: Long = 42L,
        excludeRecentlyPlayed: Boolean = false,
        recentlyPlayedIds: Set<String> = emptySet()
    ): BeamSearchSequencer.Input = BeamSearchSequencer.Input(
        candidates = candidates,
        preset = MixPreset(id = "focus", title = "Focus", subtitle = "", description = ""),
        request = PlaylistRequest(
            mood = "focus",
            targetDurationMs = 300_000L,
            excludeRecentlyPlayed = excludeRecentlyPlayed,
            refreshSeed = seed
        ),
        songs = candidates.associate { it.input.song.id to it.input.song },
        transitionContext = BeamSearchSequencer.TransitionContext(
            signatures = signatures,
            scaler = null as RobustScaler?
        ),
        recentlyPlayedIds = recentlyPlayedIds
    )

    /**
     * Greedy-vs-path: relevance-greedy ordering interleaves two timbres
     * (mean transition 0.7); the beam reorders same-timbre songs together
     * (mean transition 0.9) without giving up any relevance.
     */
    @Test
    fun `beam search beats the relevance-greedy order on path quality`() {
        val candidates = listOf(
            scored("a", 0.9f),   // timbre X
            scored("b", 0.85f),  // timbre Y
            scored("c", 0.6f),   // timbre X
            scored("d", 0.55f)   // timbre Y
        )
        val signatures = mapOf(
            "a" to signature("a", 0.9f), "c" to signature("c", 0.9f),
            "b" to signature("b", 0.1f), "d" to signature("d", 0.1f)
        )
        val input = input(candidates, signatures)

        val tracks = BeamSearchSequencer.sequence(input)

        // All songs used, no duplicates, highest relevance first.
        assertEquals(candidates.size, tracks.size)
        assertEquals(tracks.size, tracks.map { it.songId }.toSet().size)
        assertEquals("a", tracks[0].songId)

        // The produced path is measurably better than the relevance-greedy
        // order [a, b, c, d], whose mean transition is exactly 0.7.
        val ids = tracks.map { it.songId }
        var transitionSum = 0f
        for (i in 1 until ids.size) {
            transitionSum += BeamSearchSequencer.transitionScore(
                previous = candidates.first { it.input.song.id == ids[i - 1] },
                candidate = candidates.first { it.input.song.id == ids[i] },
                progress = i.toFloat() / ids.size,
                input = input
            )
        }
        val meanTransition = transitionSum / (ids.size - 1)
        assertTrue(
            "mean transition $meanTransition did not beat greedy 0.7",
            meanTransition > 0.85f
        )
    }

    @Test
    fun `sequencing is deterministic for identical inputs`() {
        val candidates = (1..8).map { scored("s$it", 1f - it * 0.05f) }
        val signatures = candidates.associate { it.input.song.id to signature(it.input.song.id, 0.3f) }

        val first = BeamSearchSequencer.sequence(input(candidates, signatures, seed = 7L))
        val second = BeamSearchSequencer.sequence(input(candidates, signatures, seed = 7L))

        assertEquals(first.map { it.songId }, second.map { it.songId })
        assertEquals(first.map { it.score }, second.map { it.score })
    }

    @Test
    fun `output contains no duplicate songs`() {
        val candidates = (1..12).map { scored("s$it", 1f - it * 0.03f) }
        val signatures = candidates.associate { it.input.song.id to signature(it.input.song.id, 0.3f) }

        val tracks = BeamSearchSequencer.sequence(input(candidates, signatures, seed = 3L))

        assertEquals(tracks.size, tracks.map { it.songId }.toSet().size)
        assertTrue(tracks.isNotEmpty())
    }

    @Test
    fun `recently played songs are excluded when enough alternatives remain`() {
        val candidates = (1..22).map { scored("s$it", 1f - it * 0.02f) }
        val signatures = candidates.associate { it.input.song.id to signature(it.input.song.id, 0.3f) }
        val recent = setOf("s1", "s2", "s3", "s4")

        val tracks = BeamSearchSequencer.sequence(
            input(candidates, signatures, seed = 11L, excludeRecentlyPlayed = true, recentlyPlayedIds = recent)
        )

        assertTrue(tracks.isNotEmpty())
        assertTrue(
            "recently played leaked: ${tracks.map { it.songId }}",
            tracks.none { it.songId in recent }
        )
    }

    @Test
    fun `exclusion is relaxed when the library is too small`() {
        val candidates = (1..12).map { scored("s$it", 1f - it * 0.05f) }
        val signatures = candidates.associate { it.input.song.id to signature(it.input.song.id, 0.3f) }

        val tracks = BeamSearchSequencer.sequence(
            input(candidates, signatures, seed = 5L, excludeRecentlyPlayed = true, recentlyPlayedIds = setOf("s1", "s2", "s3"))
        )

        // Excluding 3 of 12 candidates would leave only 9 — below the
        // minimum viable pool, so the exclusion is relaxed instead.
        assertTrue("s1" in tracks.map { it.songId })
    }

    @Test
    fun `seed song starts the playlist`() {
        val candidates = (1..6).map { scored("s$it", 1f - it * 0.1f) }
        val signatures = candidates.associate { it.input.song.id to signature(it.input.song.id, 0.3f) }

        val tracks = BeamSearchSequencer.sequence(
            input(candidates, signatures, seed = 9L).copy(
                request = PlaylistRequest(
                    mood = "focus",
                    targetDurationMs = 300_000L,
                    seedSongId = "s3",
                    refreshSeed = 9L
                )
            )
        )

        assertEquals("s3", tracks[0].songId)
    }

    @Test
    fun `empty candidates produce an empty playlist`() {
        val tracks = BeamSearchSequencer.sequence(
            input(emptyList(), emptyMap())
        )
        assertTrue(tracks.isEmpty())
    }
}

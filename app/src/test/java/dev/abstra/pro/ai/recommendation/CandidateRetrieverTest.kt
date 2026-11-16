package dev.abstra.pro.ai.recommendation

import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate retrieval and RRF fusion across the complementary sources:
 * audio neighbours, Personalized PageRank, mood and earned discovery.
 */
class CandidateRetrieverTest {

    private fun song(id: String, artist: String = "A", album: String = "Al", genre: String? = "Rock") =
        Song(
            id = id, title = "Song $id", artist = artist, album = album,
            durationMs = 200_000L, uriString = "content://x/$id", folderName = "Music",
            filePath = "/storage/$id.mp3", dateAdded = 0L, genre = genre
        )

    private fun profile(songId: String, energy: Float = 0.5f) = SongProfile(
        songId = songId,
        energy = energy,
        acousticness = 0.3f,
        instrumentalLikelihood = 0.1f,
        energyConfidence = 0.8f,
        metadataFingerprint = "fp",
        analysisVersion = 2
    )

    private fun signature(songId: String) = AudioSignature(
        songId = songId,
        mfccMean = FloatArray(13) { 0.1f },
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

    private val preset = MixPreset(
        id = "focus", title = "Focus", subtitle = "", description = ""
    )

    private val songs = (1..8).map { song("s$it") }
    private val profiles = songs.associate { it.id to profile(it.id) }
    private val signatures = songs.associate { it.id to signature(it.id) }

    @Test
    fun `all four sources contribute to the fused pool`() {
        val retriever = CandidateRetriever(
            songs = songs,
            profiles = profiles,
            signatures = signatures,
            scaler = null,
            audioGraph = mapOf("s1" to listOf("s2", "s3")),
            pprScores = mapOf("s4" to 0.002f, "s5" to 0.001f),
            trackTastes = emptyMap(),
            favoriteIds = setOf("s1"),
            anchorSongIds = emptyList()
        )

        val result = retriever.retrieve(preset, seedSongId = null)

        assertTrue(result.fusedScores.isNotEmpty())
        assertEquals(
            setOf(
                CandidateRetriever.SOURCE_AUDIO_NEIGHBOURS,
                CandidateRetriever.SOURCE_PPR,
                CandidateRetriever.SOURCE_MOOD,
                CandidateRetriever.SOURCE_DISCOVERY
            ),
            result.sourceRanks.keys
        )
        // Audio neighbours of the favorite are retrieved.
        assertTrue("s2" in result.fusedScores)
        assertTrue("s3" in result.fusedScores)
        // PPR leaders are retrieved.
        assertTrue("s4" in result.fusedScores)
        // Ranks are recorded per source.
        assertEquals(1, result.sourceRanks.getValue(CandidateRetriever.SOURCE_AUDIO_NEIGHBOURS).getValue("s2"))
        assertEquals(2, result.sourceRanks.getValue(CandidateRetriever.SOURCE_AUDIO_NEIGHBOURS).getValue("s3"))
        assertEquals(1, result.sourceRanks.getValue(CandidateRetriever.SOURCE_PPR).getValue("s4"))
    }

    @Test
    fun `fusion rewards multi-source agreement`() {
        val retriever = CandidateRetriever(
            songs = songs,
            profiles = profiles,
            signatures = signatures,
            scaler = null,
            audioGraph = mapOf("s1" to listOf("s2", "s8")),
            pprScores = mapOf("s2" to 0.002f),
            trackTastes = emptyMap(),
            favoriteIds = setOf("s1"),
            anchorSongIds = emptyList()
        )

        val result = retriever.retrieve(preset, seedSongId = null)

        // s2 appears in audio, ppr, mood and discovery sources;
        // s8 only in audio, mood and discovery.
        assertTrue(result.fusedScores.getValue("s2") > result.fusedScores.getValue("s8"))
    }

    @Test
    fun `seed song anchors audio neighbour retrieval`() {
        val retriever = CandidateRetriever(
            songs = songs,
            profiles = profiles,
            signatures = signatures,
            scaler = null,
            audioGraph = mapOf("s1" to listOf("s2"), "s6" to listOf("s7")),
            pprScores = emptyMap(),
            trackTastes = emptyMap(),
            favoriteIds = emptySet(),
            anchorSongIds = emptyList()
        )

        val result = retriever.retrieve(preset, seedSongId = "s6")

        val audioRanks = result.sourceRanks[CandidateRetriever.SOURCE_AUDIO_NEIGHBOURS]
        assertEquals(1, audioRanks?.getValue("s7"))
    }

    @Test
    fun `discovery excludes already-anchored songs`() {
        val retriever = CandidateRetriever(
            songs = songs,
            profiles = profiles,
            signatures = signatures,
            scaler = null,
            audioGraph = emptyMap(),
            pprScores = emptyMap(),
            trackTastes = emptyMap(),
            favoriteIds = setOf("s1"),
            anchorSongIds = emptyList()
        )

        val result = retriever.retrieve(preset, seedSongId = null)

        val discoveryRanks = result.sourceRanks.getValue(CandidateRetriever.SOURCE_DISCOVERY)
        assertTrue(discoveryRanks.isNotEmpty())
        assertTrue("anchor song must not be its own discovery", "s1" !in discoveryRanks)
    }

    @Test
    fun `songs without profiles are never candidates`() {
        val retriever = CandidateRetriever(
            songs = listOf(song("no-profile"), song("ok")),
            profiles = mapOf("ok" to profile("ok")),
            signatures = emptyMap(),
            scaler = null as RobustScaler?,
            audioGraph = mapOf("ok" to listOf("no-profile")),
            pprScores = emptyMap(),
            trackTastes = emptyMap(),
            favoriteIds = emptySet(),
            anchorSongIds = emptyList()
        )

        val result = retriever.retrieve(preset, seedSongId = "ok")

        // no-profile has no profile: it cannot be an audio neighbour or mood match.
        assertTrue("no-profile" !in result.sourceRanks.getValue(CandidateRetriever.SOURCE_MOOD).keys)
        assertTrue("ok" in result.sourceRanks.getValue(CandidateRetriever.SOURCE_MOOD).keys)
    }

    // ── Cold-start fallback ──

    @Test
    fun `cold start with no evidence still yields a baseline pool`() {
        // Fresh install: no favourites, no history, no tastes, no signatures
        // and metadata whose profiles carry zero confidence. Every personalised
        // source is empty — the mixes must not disappear because of it.
        val zeroConfidence = songs.associate { it.id to profile(it.id).copy(energyConfidence = 0f) }
        val retriever = CandidateRetriever(
            songs = songs,
            profiles = zeroConfidence,
            signatures = emptyMap(),
            scaler = null as RobustScaler?,
            audioGraph = emptyMap(),
            pprScores = emptyMap(),
            trackTastes = emptyMap(),
            favoriteIds = emptySet(),
            anchorSongIds = emptyList()
        )

        val result = retriever.retrieve(preset, seedSongId = null)

        assertTrue("baseline pool must not be empty", result.fusedScores.isNotEmpty())
        assertEquals(
            setOf(CandidateRetriever.SOURCE_BASELINE),
            result.sourceRanks.keys
        )
    }

    @Test
    fun `baseline pool is flavoured by the preset energy target`() {
        val zeroConfidence = mapOf(
            "quiet" to profile("quiet", energy = 0.2f).copy(energyConfidence = 0f),
            "loud" to profile("loud", energy = 0.9f).copy(energyConfidence = 0f)
        )
        val quietSong = song("quiet")
        val loudSong = song("loud")

        fun retriever() = CandidateRetriever(
            songs = listOf(quietSong, loudSong),
            profiles = zeroConfidence,
            signatures = emptyMap(),
            scaler = null as RobustScaler?,
            audioGraph = emptyMap(),
            pprScores = emptyMap(),
            trackTastes = emptyMap(),
            favoriteIds = emptySet(),
            anchorSongIds = emptyList()
        )

        val chill = retriever().retrieve(preset.copy(targetEnergy = 0.2f), seedSongId = null)
        val highEnergy = retriever().retrieve(preset.copy(targetEnergy = 0.9f), seedSongId = null)

        val chillRanks = chill.sourceRanks.getValue(CandidateRetriever.SOURCE_BASELINE)
        val highRanks = highEnergy.sourceRanks.getValue(CandidateRetriever.SOURCE_BASELINE)
        assertEquals(1, chillRanks.getValue("quiet"))
        assertEquals(1, highRanks.getValue("loud"))
    }
}

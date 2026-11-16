package dev.abstra.pro.ai.recommendation

import dev.abstra.pro.ai.audio.AudioSimilarity
import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.data.model.Song

/**
 * Retrieves recommendation candidates from several complementary sources
 * (audio neighbours, personalized PageRank, mood compatibility, earned
 * discovery) and fuses their rankings with Reciprocal Rank Fusion.
 *
 * No single global score is taken at face value; each source contributes a
 * ranking and the fusion combines them scale-free.
 */
class CandidateRetriever(
    private val songs: List<Song>,
    private val profiles: Map<String, SongProfile>,
    private val signatures: Map<String, AudioSignature>,
    private val scaler: RobustScaler?,
    private val audioGraph: Map<String, List<String>>,
    private val pprScores: Map<String, Float>,
    private val trackTastes: Map<String, UserTasteModel.TrackTaste>,
    private val favoriteIds: Set<String>,
    private val anchorSongIds: List<String>
) {

    data class Retrieved(
        val fusedScores: Map<String, Float>,
        val sourceRanks: Map<String, Map<String, Int>>
    )

    /**
     * Retrieves and fuses candidates for one preset. [seedSongId] anchors
     * audio-neighbour retrieval when the user started from a song.
     */
    fun retrieve(preset: MixPreset, seedSongId: String?): Retrieved {
        val anchors = buildAnchors(seedSongId, preset)

        val sources = mutableListOf<RankFusion.Source>()

        // Source A — audio neighbours of positive anchors.
        val audioNeighbours = retrieveAudioNeighbours(anchors)
        if (audioNeighbours.isNotEmpty()) {
            sources += RankFusion.Source(SOURCE_AUDIO_NEIGHBOURS, SOURCE_WEIGHT_AUDIO, audioNeighbours)
        }

        // Source B — Personalized PageRank top tracks.
        val pprTop = pprScores.entries
            .filter { it.value > 0f && it.key in profiles }
            .sortedByDescending { it.value }
            .take(PPR_TOP_LIMIT)
            .map { it.key }
        if (pprTop.isNotEmpty()) {
            sources += RankFusion.Source(SOURCE_PPR, SOURCE_WEIGHT_PPR, pprTop)
        }

        // Source C — mood compatibility.
        val moodTop = retrieveMoodMatches(preset)
        if (moodTop.isNotEmpty()) {
            sources += RankFusion.Source(SOURCE_MOOD, SOURCE_WEIGHT_MOOD, moodTop)
        }

        // Source D — earned discovery.
        val discoveryTop = retrieveDiscovery(anchors, preset)
        if (discoveryTop.isNotEmpty()) {
            sources += RankFusion.Source(SOURCE_DISCOVERY, SOURCE_WEIGHT_DISCOVERY, discoveryTop)
        }

        val fused = RankFusion.fuse(sources)
        val sourceRanks = HashMap<String, Map<String, Int>>()
        for (source in sources) {
            val ranks = HashMap<String, Int>()
            source.rankedIds.forEachIndexed { index, id -> ranks[id] = index + 1 }
            sourceRanks[source.name] = ranks
        }
        return Retrieved(fused, sourceRanks)
    }

    // ── Anchors ──

    private fun buildAnchors(seedSongId: String?, preset: MixPreset): List<String> {
        val anchors = LinkedHashSet<String>()
        if (seedSongId != null) anchors.add(seedSongId)
        anchors.addAll(favoriteIds)
        // Strong positive taste.
        trackTastes.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, UserTasteModel.TrackTaste>> { it.value.positive * it.value.confidence }
                    .thenBy { it.key }
            )
            .take(ANCHOR_LIMIT)
            .filter { it.value.positive >= 0.55f && it.value.confidence >= 0.25f }
            .forEach { anchors.add(it.key) }
        anchors.addAll(anchorSongIds.take(ANCHOR_SESSION_LIMIT))
        return anchors.filter { it in profiles }.toList()
    }

    private fun retrieveAudioNeighbours(anchors: List<String>): List<String> {
        val scores = HashMap<String, Float>()
        for ((index, anchor) in anchors.withIndex()) {
            val decay = 1f / (index * 0.5f + 1f)
            for ((rank, neighbourId) in (audioGraph[anchor] ?: emptyList()).withIndex()) {
                val contribution = decay / (rank + 1)
                scores[neighbourId] = (scores[neighbourId] ?: 0f) + contribution
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .take(AUDIO_NEIGHBOUR_LIMIT)
            .map { it.key }
    }

    private fun retrieveMoodMatches(preset: MixPreset): List<String> {
        data class Scored(val songId: String, val value: Float, val confidence: Float)

        val scored = mutableListOf<Scored>()
        moodLoop@ for (song in songs) {
            val profile = profiles[song.id] ?: continue
            val signature = signatures[song.id]
            var value = 0f
            var confidence = 0f

            // Energy.
            val energyMatch = gaussian(profile.energy, preset.targetEnergy, preset.energyTolerance)
            value += energyMatch * 0.6f
            confidence += 0.6f * profile.energyConfidence

            // Rhythm.
            val rhythmTarget = preset.targetRhythmActivity
            if (rhythmTarget != null) {
                val rhythm = signature?.rhythmActivity ?: profile.rhythmActivity
                if (rhythm != null) {
                    val match = gaussian(rhythm, rhythmTarget, preset.rhythmTolerance)
                    value += match * 0.2f
                    confidence += 0.2f * 0.7f
                }
            }

            // Acousticness.
            preset.targetAcousticness?.let { target ->
                if (profile.acousticConfidence > 0f) {
                    value += gaussian(profile.acousticness, target, preset.acousticnessTolerance) * 0.15f
                    confidence += 0.15f * profile.acousticConfidence
                }
            }

            // Instrumental likelihood.
            preset.targetInstrumental?.let { target ->
                if (profile.instrumentalConfidence > 0f) {
                    value += gaussian(profile.instrumentalLikelihood, target, 0.35f) * 0.05f
                    confidence += 0.05f * profile.instrumentalConfidence
                }
            }

            if (confidence <= 0f) continue
            val normalized = (value / 1f).coerceIn(0f, 1f)
            scored += Scored(song.id, normalized, confidence)
        }
        return scored
            .filter { it.confidence > 0f }
            .sortedWith(compareByDescending<Scored> { it.value * (0.5f + 0.5f * it.confidence) }.thenBy { it.songId })
            .take(MOOD_LIMIT)
            .map { it.songId }
    }

    /**
     * Discovery: unplayed tracks with real relevance — audio similarity to
     * anchors or graph connectivity. Unplayed alone is never enough.
     */
    private fun retrieveDiscovery(anchors: List<String>, preset: MixPreset): List<String> {
        val anchorSignatures = anchors.mapNotNull { signatures[it] }
        val anchorIds = anchors.toSet()

        data class Scored(val songId: String, val relevance: Float)

        val scored = mutableListOf<Scored>()
        for (song in songs) {
            if (song.id in anchorIds) continue
            val taste = trackTastes[song.id]
            if (taste != null && taste.confidence > 0.3f) continue // already well-known

            val profile = profiles[song.id] ?: continue
            var relevance = 0f

            // Audio similarity to anchors.
            val signature = signatures[song.id]
            if (signature != null && anchorSignatures.isNotEmpty()) {
                var best = 0f
                for (anchor in anchorSignatures) {
                    val sim = AudioSimilarity.similarity(signature, anchor, scaler) ?: continue
                    if (sim > best) best = sim
                }
                relevance += best * 0.6f
            }

            // Metadata compatibility with anchors (artist/genre).
            var bestMetadata = 0f
            for (anchorId in anchors) {
                val anchorSong = songs.firstOrNull { it.id == anchorId } ?: continue
                val sim = AudioSimilarity.metadataSimilarity(song, anchorSong)
                if (sim > bestMetadata) bestMetadata = sim
            }
            relevance += bestMetadata * 0.4f

            // A mood requirement still applies.
            val energyMatch = gaussian(profile.energy, preset.targetEnergy, preset.energyTolerance * 1.5f)
            relevance *= (0.7f + 0.3f * energyMatch)

            if (relevance >= MIN_DISCOVERY_RELEVANCE) {
                scored += Scored(song.id, relevance)
            }
        }
        return scored
            .sortedWith(compareByDescending<Scored> { it.relevance }.thenBy { it.songId })
            .take(DISCOVERY_LIMIT)
            .map { it.songId }
    }

    private fun gaussian(x: Float, target: Float, sigma: Float): Float {
        if (sigma <= 0f) return if (x == target) 1f else 0f
        val d = (x - target) / sigma
        return kotlin.math.exp(-0.5f * d * d)
    }

    companion object {
        const val SOURCE_AUDIO_NEIGHBOURS = "audio_neighbours"
        const val SOURCE_PPR = "personalized_pagerank"
        const val SOURCE_MOOD = "mood"
        const val SOURCE_DISCOVERY = "discovery"

        const val SOURCE_WEIGHT_AUDIO = 1.0f
        const val SOURCE_WEIGHT_PPR = 1.2f
        const val SOURCE_WEIGHT_MOOD = 0.8f
        const val SOURCE_WEIGHT_DISCOVERY = 0.6f

        private const val ANCHOR_LIMIT = 12
        private const val ANCHOR_SESSION_LIMIT = 3
        private const val AUDIO_NEIGHBOUR_LIMIT = 80
        private const val PPR_TOP_LIMIT = 60
        private const val MOOD_LIMIT = 60
        private const val DISCOVERY_LIMIT = 40
        private const val MIN_DISCOVERY_RELEVANCE = 0.55f
    }
}

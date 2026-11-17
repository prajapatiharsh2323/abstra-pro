package dev.abstra.pro.ai.recommendation

import dev.abstra.pro.ai.audio.AudioSimilarity
import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.MixStrategy
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song

/**
 * Retrieves recommendation candidates from several complementary sources
 * (audio neighbours, personalized PageRank, mood compatibility, earned
 * discovery, plus preset-strategy sources) and fuses their rankings with
 * Reciprocal Rank Fusion.
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
    private val anchorSongIds: List<String>,
    private val telemetry: Map<String, PlaybackTelemetryEntity> = emptyMap(),
    /** The spotlight artist's own tracks, when the preset asks for them. */
    private val spotlightSongIds: List<String> = emptyList()
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

        // Strategy-specific sources — genuinely different candidate origins
        // per listening purpose, not just different target numbers.
        when (preset.strategy) {
            MixStrategy.FORGOTTEN_GEMS -> {
                val forgotten = retrieveForgotten(preset)
                if (forgotten.isNotEmpty()) {
                    sources += RankFusion.Source(
                        SOURCE_FORGOTTEN, SOURCE_WEIGHT_FORGOTTEN, forgotten
                    )
                }
            }
            MixStrategy.ACOUSTIC_LIVE -> {
                val acoustic = retrieveAcoustic(preset)
                if (acoustic.isNotEmpty()) {
                    sources += RankFusion.Source(
                        SOURCE_ACOUSTIC, SOURCE_WEIGHT_ACOUSTIC, acoustic
                    )
                }
            }
            MixStrategy.ARTIST_SPOTLIGHT -> {
                val spotlight = retrieveSpotlight()
                if (spotlight.isNotEmpty()) {
                    sources += RankFusion.Source(
                        SOURCE_SPOTLIGHT_ARTIST, SOURCE_WEIGHT_SPOTLIGHT, spotlight
                    )
                }
            }
            MixStrategy.TIME_OF_DAY, MixStrategy.DEFAULT -> Unit
        }

        // Cold-start fallback: when none of the personalised sources have any
        // evidence (fresh install — no favourites, no history, no anchors —
        // and metadata that carries no confidence), the pool would be empty
        // and every preset would resolve to null, so no mixes appear at all.
        // Rank the library by preset energy compatibility; profile.energy
        // always has a base-prior value even at zero confidence, and the
        // ordering is deterministic. Honest: it is explicitly a baseline,
        // never fabricated preference.
        if (sources.isEmpty()) {
            val baseline = songs.mapNotNull { song ->
                val profile = profiles[song.id] ?: return@mapNotNull null
                song.id to gaussian(profile.energy, preset.targetEnergy, preset.energyTolerance)
            }
                .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
                .take(BASELINE_LIMIT)
                .map { it.first }
            if (baseline.isNotEmpty()) {
                sources += RankFusion.Source(SOURCE_BASELINE, SOURCE_WEIGHT_BASELINE, baseline)
            }
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

            // Tag evidence (title/genre-derived): a small, honest modifier —
            // never the main signal, and only when the preset declares tags.
            var tagValue = 0f
            var tagWeight = 0f
            val tagHits = profile.tags.intersect(preset.preferredTags)
            if (tagHits.isNotEmpty()) {
                tagValue += 1f
                tagWeight += 0.08f
            }
            val tagMisses = profile.tags.intersect(preset.avoidedTags)
            if (tagMisses.isNotEmpty()) {
                tagValue -= 1f
                tagWeight += 0.08f
            }
            if (tagWeight > 0f) {
                value += tagValue * tagWeight
                confidence += tagWeight * 0.5f
            }

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

        // Precomputed once (O(N)): id -> song, and normalized metadata keys
        // for the anchors. The per-song loop then does no list scans and no
        // string normalization at all.
        val songById = songs.associateBy { it.id }
        val anchorKeys = anchors.mapNotNull { songById[it]?.let(AudioSimilarity.MetadataKey::of) }

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
            if (anchorKeys.isNotEmpty()) {
                val songKey = AudioSimilarity.MetadataKey.of(song)
                for (anchorKey in anchorKeys) {
                    val sim = AudioSimilarity.metadataSimilarity(songKey, anchorKey)
                    if (sim > bestMetadata) bestMetadata = sim
                }
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

    /**
     * Forgotten gems: previously enjoyed tracks that have drifted out of
     * listening habits. Positive evidence (taste or favorite) plus real
     * staleness is required — an unplayed track is not a forgotten gem, and
     * a merely old track without positive evidence is not either.
     */
    private fun retrieveForgotten(preset: MixPreset): List<String> {
        val now = System.currentTimeMillis()

        data class Scored(val songId: String, val value: Float)

        val scored = mutableListOf<Scored>()
        for (song in songs) {
            val t = telemetry[song.id] ?: continue
            if (t.playCount <= 0) continue
            val daysSince = if (t.lastPlayedTimestamp > 0L) {
                ((now - t.lastPlayedTimestamp).coerceAtLeast(0L)) / DAY_MS
            } else continue
            if (daysSince < FORGOTTEN_MIN_DAYS) continue

            val taste = trackTastes[song.id]
            val positive = when {
                taste != null && taste.confidence >= 0.2f && taste.positive >= 0.55f ->
                    taste.positive * taste.confidence
                song.isFavorite -> 0.7f
                else -> continue
            }

            val profile = profiles[song.id] ?: continue
            val mood = gaussian(profile.energy, preset.targetEnergy, preset.energyTolerance * 1.5f)
            val staleness = (daysSince / 30f).coerceIn(0.6f, 1.4f)
            scored += Scored(song.id, positive * staleness * (0.7f + 0.3f * mood))
        }
        return scored
            .sortedWith(compareByDescending<Scored> { it.value }.thenBy { it.songId })
            .take(FORGOTTEN_LIMIT)
            .map { it.songId }
    }

    /**
     * Acoustic / live / instrumental candidates. Metadata tags and the
     * acoustic/instrumental profile dimensions are evidence with explicit
     * confidence; a title keyword alone earns only the tag's small share.
     */
    private fun retrieveAcoustic(preset: MixPreset): List<String> {
        data class Scored(val songId: String, val value: Float)

        val scored = mutableListOf<Scored>()
        for (song in songs) {
            val profile = profiles[song.id] ?: continue
            val acousticScore = profile.acousticness * profile.acousticConfidence
            val instrumentalScore =
                profile.instrumentalLikelihood * profile.instrumentalConfidence
            val tagHits = profile.tags.intersect(ACOUSTIC_TAGS)
            val value = acousticScore * 0.55f + instrumentalScore * 0.2f +
                    if (tagHits.isNotEmpty()) 0.2f else 0f
            if (value < MIN_ACOUSTIC_SCORE) continue
            val mood = gaussian(profile.energy, preset.targetEnergy, preset.energyTolerance * 1.5f)
            scored += Scored(song.id, value * (0.7f + 0.3f * mood))
        }
        return scored
            .sortedWith(compareByDescending<Scored> { it.value }.thenBy { it.songId })
            .take(ACOUSTIC_LIMIT)
            .map { it.songId }
    }

    /**
     * The spotlight artist's own tracks, ranked by earned preference — the
     * anchor sequence; cross-artist discovery comes from the audio-neighbour
     * source built on the same anchors.
     */
    private fun retrieveSpotlight(): List<String> {
        data class Scored(val songId: String, val value: Float)

        return spotlightSongIds
            .filter { it in profiles }
            .map { id ->
                val taste = trackTastes[id]
                val t = telemetry[id]
                val preference = taste?.let { it.positive * (0.3f + 0.7f * it.confidence) }
                    ?: (t?.playCount?.toFloat()?.let { c -> (c / (c + 6f)) } ?: 0f)
                Scored(id, preference)
            }
            .filter { it.value > 0f || it.songId in favoriteIds }
            .sortedWith(compareByDescending<Scored> { it.value }.thenBy { it.songId })
            .take(SPOTLIGHT_LIMIT)
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
        const val SOURCE_BASELINE = "baseline"
        const val SOURCE_FORGOTTEN = "forgotten_gems"
        const val SOURCE_ACOUSTIC = "acoustic_live"
        const val SOURCE_SPOTLIGHT_ARTIST = "spotlight_artist"

        const val SOURCE_WEIGHT_AUDIO = 1.0f
        const val SOURCE_WEIGHT_PPR = 1.2f
        const val SOURCE_WEIGHT_MOOD = 0.8f
        const val SOURCE_WEIGHT_DISCOVERY = 0.6f
        const val SOURCE_WEIGHT_BASELINE = 0.5f
        const val SOURCE_WEIGHT_FORGOTTEN = 1.4f
        const val SOURCE_WEIGHT_ACOUSTIC = 1.2f
        const val SOURCE_WEIGHT_SPOTLIGHT = 1.1f

        val ACOUSTIC_TAGS = setOf("acoustic", "live", "instrumental", "unplugged")

        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val ANCHOR_LIMIT = 12
        private const val ANCHOR_SESSION_LIMIT = 8
        private const val AUDIO_NEIGHBOUR_LIMIT = 80
        private const val PPR_TOP_LIMIT = 60
        private const val MOOD_LIMIT = 60
        private const val DISCOVERY_LIMIT = 40
        private const val BASELINE_LIMIT = 80
        private const val FORGOTTEN_LIMIT = 50
        private const val ACOUSTIC_LIMIT = 50
        private const val SPOTLIGHT_LIMIT = 30
        private const val MIN_DISCOVERY_RELEVANCE = 0.55f
        private const val MIN_ACOUSTIC_SCORE = 0.25f

        /** A gem must be genuinely drifted away from, not just not-yesterday. */
        const val FORGOTTEN_MIN_DAYS = 14
    }
}

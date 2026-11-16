package dev.abstra.pro.ai.recommendation

import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.RecommendationReason
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.ai.playlist.RecommendationDiagnostics
import dev.abstra.pro.data.model.Song

/**
 * Final recommendation ranker (V2).
 *
 * Ranks an already-retrieved, already-fused candidate pool. Every optional
 * signal carries its own confidence and contributes only what it can
 * support — missing information leaves the neutral baseline in place and is
 * never re-normalized into a fabricated score:
 *
 *     S = clamp(0.5 + 2 * Σ w_i * c_i * (v_i - 0.5), 0, 1)
 *
 * Freshness is subordinate: unplayed means unknown, not liked.
 */
object PlaylistRanker {

    const val WEIGHT_GRAPH_RELEVANCE = 0.26f
    const val WEIGHT_AUDIO_SIMILARITY = 0.24f
    const val WEIGHT_PREFERENCE = 0.20f
    const val WEIGHT_MOOD = 0.20f
    const val WEIGHT_ARTIST_GENRE = 0.06f
    const val WEIGHT_NOVELTY = 0.04f

    data class CandidateInput(
        val song: Song,
        val profile: SongProfile,
        /** Fused retrieval score (0..1 normalized against the pool max). */
        val fusedScore: Float,
        /** Personalized PageRank, or null when no graph relevance exists. */
        val pprScore: Float?,
        /** Max audio similarity to anchors, or null without signatures. */
        val audioSimilarity: Float?,
        /** Track preference, or null without history. */
        val preference: UserTasteModel.TrackTaste?,
        /** Artist/genre affinity value + confidence. */
        val artistGenreValue: Float = 0.5f,
        val artistGenreConfidence: Float = 0f,
        /** Mood compatibility (0..1) + confidence. */
        val moodValue: Float = 0.5f,
        val moodConfidence: Float = 0f,
        /** Novelty value (0..1) + confidence, precomputed by the caller. */
        val noveltyValue: Float = 0.5f,
        val noveltyConfidence: Float = 0f,
        val sourceRanks: Map<String, Int> = emptyMap(),
        /** Normalized play count (0..1); drives the discovery flag. */
        val playCount: Int = 0
    )

    data class Scored(
        val input: CandidateInput,
        val score: Float,
        val confidence: Float,
        val components: List<Component>,
        val isDiscovery: Boolean
    )

    data class Component(val name: String, val value: Float, val weight: Float, val confidence: Float)

    /**
     * Scores one candidate. Static (position-independent) dimensions only;
     * mood must be provided per playlist position by the sequencer.
     */
    fun score(input: CandidateInput): Scored {
        val components = mutableListOf<Component>()

        // Graph relevance: fused retrieval score scaled by PPR when present.
        val graphRelevance = when (val ppr = input.pprScore) {
            null -> input.fusedScore
            else -> (0.5f * input.fusedScore + 0.5f * minOf(1f, ppr * GRAPH_PPR_SCALE))
        }
        val graphConfidence = if (input.fusedScore > 0f) 0.8f else 0f
        components += Component("graph_relevance", graphRelevance, WEIGHT_GRAPH_RELEVANCE, graphConfidence)

        // Audio similarity — only when actually measured.
        val audioSim = input.audioSimilarity
        components += Component(
            "audio_similarity",
            audioSim ?: 0.5f,
            WEIGHT_AUDIO_SIMILARITY,
            if (audioSim != null) AUDIO_CONFIDENCE else 0f
        )

        // Track preference — only with history.
        val taste = input.preference
        val preferenceValue = taste?.positive
        val preferenceConfidence = taste?.confidence ?: 0f
        components += Component(
            "preference",
            preferenceValue ?: 0.5f,
            WEIGHT_PREFERENCE,
            preferenceConfidence
        )

        // Mood.
        components += Component("mood", input.moodValue, WEIGHT_MOOD, input.moodConfidence)

        // Artist/genre affinity.
        components += Component(
            "artist_genre",
            input.artistGenreValue,
            WEIGHT_ARTIST_GENRE,
            input.artistGenreConfidence
        )

        // Novelty — subordinate and honest about unknowns.
        components += Component(
            "novelty",
            input.noveltyValue,
            WEIGHT_NOVELTY,
            input.noveltyConfidence
        )

        var weightedSum = 0f
        var totalWeight = 0f
        var totalConfidenceMass = 0f
        for (component in components) {
            val effective = component.weight * component.confidence
            weightedSum += effective * (component.value - 0.5f)
            totalWeight += effective
            totalConfidenceMass += component.confidence * component.weight
        }
        val centered = 2f * weightedSum
        val score = (0.5f + centered).coerceIn(0f, 1f)
        val confidence = (totalWeight / sumWeights()).coerceIn(0f, 1f)

        val isDiscovery = input.playCount <= 1 &&
                (audioSim != null || input.pprScore != null || input.fusedScore > 0f)

        return Scored(
            input = input,
            score = score,
            confidence = confidence,
            components = components,
            isDiscovery = isDiscovery
        )
    }

    /** Static weights sum, used to normalize confidence. */
    fun sumWeights(): Float =
        WEIGHT_GRAPH_RELEVANCE + WEIGHT_AUDIO_SIMILARITY + WEIGHT_PREFERENCE +
                WEIGHT_MOOD + WEIGHT_ARTIST_GENRE + WEIGHT_NOVELTY

    /** Builds honest diagnostics for a scored candidate. */
    fun diagnostics(scored: Scored): RecommendationDiagnostics {
        val input = scored.input
        val taste = input.preference
        val reasons = mutableSetOf<String>()

        if ((input.audioSimilarity ?: 0f) >= 0.7f && (taste?.completionRate ?: 0f) >= 0.5f) {
            reasons += RecommendationDiagnostics.REASON_SIMILAR_SOUND_TO_FREQUENTLY_FINISHED
        }
        if ((input.audioSimilarity ?: 0f) >= 0.7f || (input.pprScore ?: 0f) >= GRAPH_PPR_SCALE * 0.6f) {
            reasons += RecommendationDiagnostics.REASON_CONNECTED_TO_FAVORITES
        }
        if (input.moodValue >= 0.7f && input.moodConfidence >= 0.4f) {
            reasons += RecommendationDiagnostics.REASON_MATCHES_MOOD
        }
        if (scored.isDiscovery && (input.audioSimilarity ?: 0f) >= 0.55f) {
            reasons += RecommendationDiagnostics.REASON_RELEVANT_UNPLAYED
        }
        if (input.song.isFavorite) reasons += RecommendationDiagnostics.REASON_FAVORITE
        if (input.artistGenreValue >= 0.7f && input.artistGenreConfidence >= 0.5f) {
            reasons += RecommendationDiagnostics.REASON_ARTIST_AFFINITY
        }
        if ((taste?.positive ?: 0f) >= 0.6f && (taste?.confidence ?: 0f) >= 0.4f &&
            input.noveltyValue >= 0.7f
        ) {
            reasons += RecommendationDiagnostics.REASON_FORGOTTEN_FAVORITE
        }

        return RecommendationDiagnostics(
            songId = input.song.id,
            audioSimilarity = input.audioSimilarity,
            pprScore = input.pprScore,
            preferenceValue = taste?.positive,
            preferenceConfidence = taste?.confidence ?: 0f,
            moodMatch = if (input.moodConfidence > 0f) input.moodValue else null,
            moodConfidence = input.moodConfidence,
            sourceRanks = input.sourceRanks,
            isDiscovery = scored.isDiscovery,
            confidence = scored.confidence,
            reasonCodes = reasons
        )
    }

    /** Converts diagnostics back into the V1 reason enum for UI compat. */
    fun toLegacyReasons(diagnostics: RecommendationDiagnostics): Set<RecommendationReason> {
        val reasons = mutableSetOf<RecommendationReason>()
        if (RecommendationDiagnostics.REASON_FAVORITE in diagnostics.reasonCodes) {
            reasons += RecommendationReason.FAVORITE_SONG
        }
        if (RecommendationDiagnostics.REASON_ARTIST_AFFINITY in diagnostics.reasonCodes) {
            reasons += RecommendationReason.FAVORITE_ARTIST
        }
        if (RecommendationDiagnostics.REASON_MATCHES_MOOD in diagnostics.reasonCodes) {
            reasons += RecommendationReason.MOOD_MATCH
        }
        if (RecommendationDiagnostics.REASON_RELEVANT_UNPLAYED in diagnostics.reasonCodes ||
            diagnostics.isDiscovery
        ) {
            reasons += RecommendationReason.FRESH_DISCOVERY
        }
        if (RecommendationDiagnostics.REASON_FORGOTTEN_FAVORITE in diagnostics.reasonCodes) {
            reasons += RecommendationReason.NOT_PLAYED_RECENTLY
        }
        return reasons
    }

    private const val GRAPH_PPR_SCALE = 500f
    private const val AUDIO_CONFIDENCE = 0.85f
}

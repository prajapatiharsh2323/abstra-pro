package dev.abstra.pro.ai.playlist

/**
 * Per-track recommendation diagnostics for debugging and honest
 * explanations. Never shown as misleading percentages in the production UI;
 * surfaced in debug builds and structured test output.
 */
data class RecommendationDiagnostics(
    val songId: String,

    /** Max audio similarity to any anchor song (null: no signatures). */
    val audioSimilarity: Float? = null,

    /** Personalized PageRank score (null: no graph history). */
    val pprScore: Float? = null,

    /** Track preference value (null: no history). */
    val preferenceValue: Float? = null,
    val preferenceConfidence: Float = 0f,

    /** Mood compatibility at the given preset / position. */
    val moodMatch: Float? = null,
    val moodConfidence: Float = 0f,

    /** Rank within each retrieval source (1-based), keyed by source name. */
    val sourceRanks: Map<String, Int> = emptyMap(),

    /** True when this track is an earned discovery candidate. */
    val isDiscovery: Boolean = false,

    /** Transition quality from the previous track in the sequence (0..1). */
    val transitionScore: Float? = null,

    /** Final recommendation confidence (0..1). */
    val confidence: Float = 0f,

    /** Reason codes supported by measured signals. */
    val reasonCodes: Set<String> = emptySet()
) {
    companion object {
        // Honest, signal-backed explanation codes.
        const val REASON_SIMILAR_SOUND_TO_FREQUENTLY_FINISHED =
            "similar_sound_to_frequently_finished"
        const val REASON_CONNECTED_TO_FAVORITES = "connected_to_favorites"
        const val REASON_MATCHES_MOOD = "matches_requested_characteristics"
        const val REASON_RELEVANT_UNPLAYED = "relevant_unplayed"
        const val REASON_FITS_PREVIOUS_TRACK = "fits_previous_track"
        const val REASON_FAVORITE = "favorite"
        const val REASON_ARTIST_AFFINITY = "artist_you_enjoy"
        const val REASON_FORGOTTEN_FAVORITE = "not_played_recently"
    }
}

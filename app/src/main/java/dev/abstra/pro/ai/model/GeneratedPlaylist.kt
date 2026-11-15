package dev.abstra.pro.ai.model

/**
 * Why a song was recommended. Only reasons backed by actual signals are
 * attached; the UI converts them to readable explanations.
 */
enum class RecommendationReason {
    MOOD_MATCH,
    FAVORITE_ARTIST,
    FAVORITE_SONG,
    HIGH_COMPLETION_RATE,
    FRESH_DISCOVERY,
    NOT_PLAYED_RECENTLY,
    SMOOTH_TRANSITION,
    MATCHES_LISTENING_CONTEXT
}

data class RecommendedTrack(
    val songId: String,
    /** Overall recommendation score (0.0 .. 1.0). */
    val score: Float,
    val reasons: Set<RecommendationReason>
)

/**
 * The output of the recommendation engine: an ordered collection of song IDs,
 * scores and recommendation reasons. Playback/queue handling stays in the
 * existing player system.
 */
data class GeneratedPlaylist(
    /** Mix preset identifier this playlist was generated from. */
    val presetId: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val tracks: List<RecommendedTrack>,
    val generatedAtMs: Long,
    val refreshSeed: Long,
    val totalDurationMs: Long
)

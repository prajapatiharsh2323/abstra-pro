package dev.abstra.pro.ai.model

/**
 * A UI-independent description of what the playlist generator should achieve.
 * Contains no Compose types, Android UI objects or playback-service references.
 */
data class PlaylistRequest(
    /** Mix preset identifier, e.g. "focus", "chill", "daily_flow". */
    val mood: String,
    val targetDurationMs: Long = DEFAULT_TARGET_DURATION_MS,
    /** Fraction of the playlist reserved for rarely-played / unexplored tracks. */
    val discoveryRatio: Float = 0.20f,
    val excludeRecentlyPlayed: Boolean = true,
    val seedSongId: String? = null,
    /** Deterministic seed; mixes with the same seed and library are identical. */
    val refreshSeed: Long = 0L
) {
    companion object {
        const val DEFAULT_TARGET_DURATION_MS: Long = 60L * 60L * 1000L
    }
}

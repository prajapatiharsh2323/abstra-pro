package dev.abstra.pro.ai.model

/**
 * Cached recommendation profile for a single song.
 *
 * All characteristics are normalized to 0.0..1.0 and carry an explicit
 * confidence so the recommendation pipeline knows how much to trust them.
 * Profiles never claim to know more than the available information supports.
 */
data class SongProfile(
    val songId: String,

    /** Low-energy versus energetic music. */
    val energy: Float,

    /** Acoustic / unplugged characteristics. */
    val acousticness: Float,

    /** Whether the track appears instrumental. */
    val instrumentalLikelihood: Float,

    /** Optional audio-derived rhythmic activity. */
    val rhythmActivity: Float? = null,

    /** chill, live, ambient, acoustic, remix, upbeat, instrumental, etc. */
    val tags: Set<String> = emptySet(),

    /** Reliability of each inferred characteristic (0.0 .. 1.0). */
    val energyConfidence: Float = 0.0f,
    val acousticConfidence: Float = 0.0f,
    val instrumentalConfidence: Float = 0.0f,

    /** "METADATA" or "BLENDED" (metadata refined by optional audio analysis). */
    val profileSource: String = "METADATA",

    /** Hash of the effective metadata the profile was built from. */
    val metadataFingerprint: String,

    /** Version of the analyzer that produced this profile. */
    val analysisVersion: Int
) {
    companion object {
        fun empty(songId: String, fingerprint: String, version: Int): SongProfile = SongProfile(
            songId = songId,
            energy = 0.5f,
            acousticness = 0.3f,
            instrumentalLikelihood = 0.1f,
            metadataFingerprint = fingerprint,
            analysisVersion = version
        )
    }
}

package dev.abstra.pro.ai.model

/**
 * Configuration-driven definition of a mood preset. Presets are recommendation
 * targets for the one shared pipeline — not separate algorithms.
 */
data class MixPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,

    /** Base target energy when no sequencing curve overrides it. */
    val targetEnergy: Float = 0.5f,
    /** How far a song's energy may drift from the target before it is penalized. */
    val energyTolerance: Float = 0.35f,

    val targetAcousticness: Float? = null,
    val acousticnessTolerance: Float = 0.5f,
    val targetInstrumental: Float? = null,

    /** Optional target rhythmic activity (audio-derived when available). */
    val targetRhythmActivity: Float? = null,
    val rhythmTolerance: Float = 0.3f,

    /** Optional target spectral brightness (0 = dark, 1 = bright). */
    val targetBrightness: Float? = null,

    /** Optional tempo window in BPM; low-confidence estimates never
     *  disqualify a track — they only fail to earn the tempo bonus. */
    val targetTempoMinBpm: Float? = null,
    val targetTempoMaxBpm: Float? = null,

    val preferredTags: Set<String> = emptySet(),
    val avoidedTags: Set<String> = emptySet(),

    /** Optional energy curve across playlist progress (0.0 = start, 1.0 = end). */
    val energyCurve: List<EnergyCurvePoint>? = null,

    val targetDurationMs: Long = 60L * 60L * 1000L,
    val discoveryRatio: Float = 0.15f,
    val excludeRecentlyPlayed: Boolean = true,
    val maxTracks: Int = 50
) {
    /** Interpolated target energy at a playlist progress fraction. */
    fun energyAt(fraction: Float): Float {
        val curve = energyCurve ?: return targetEnergy
        if (curve.isEmpty()) return targetEnergy
        val sorted = curve.sortedBy { it.fraction }
        val f = fraction.coerceIn(0f, 1f)
        var prev = sorted.first()
        for (point in sorted) {
            if (point.fraction >= f) {
                val span = point.fraction - prev.fraction
                if (span <= 0f) return point.energy
                val t = (f - prev.fraction) / span
                return prev.energy + (point.energy - prev.energy) * t
            }
            prev = point
        }
        return sorted.last().energy
    }
}

data class EnergyCurvePoint(
    /** Progress fraction (0.0 .. 1.0). */
    val fraction: Float,
    val energy: Float
)

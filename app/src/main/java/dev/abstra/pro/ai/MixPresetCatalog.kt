package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.EnergyCurvePoint
import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.PlaylistRequest

/**
 * Configuration-driven mood presets. Focus and Night use the same scoring and
 * sequencing pipeline as every other preset — only the target values differ.
 */
object MixPresetCatalog {

    const val PRESET_DAILY_FLOW = "daily_flow"
    const val PRESET_DISCOVERY = "discovery"
    const val PRESET_UPBEAT = "upbeat"
    const val PRESET_CHILL = "chill"
    const val PRESET_FOCUS = "focus"
    const val PRESET_WORKOUT = "workout"
    const val PRESET_NIGHT = "night"

    val presets: List<MixPreset> = listOf(
        MixPreset(
            id = PRESET_DAILY_FLOW,
            title = "Daily Flow",
            subtitle = "Your day in music",
            description = "Personalized from your recent listening behaviour, with balanced energy and gentle discovery.",
            targetEnergy = 0.55f,
            energyTolerance = 0.4f,
            targetRhythmActivity = 0.45f,
            rhythmTolerance = 0.4f,
            preferredTags = setOf("upbeat", "chill", "focus"),
            avoidedTags = setOf(),
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.20f,
            maxTracks = 50
        ),
        MixPreset(
            id = PRESET_DISCOVERY,
            title = "Discovery",
            subtitle = "Hidden gems you might love",
            description = "A mix of familiar anchors and promising unfamiliar tracks, chosen to fit your taste.",
            targetEnergy = 0.55f,
            energyTolerance = 0.45f,
            targetRhythmActivity = 0.45f,
            rhythmTolerance = 0.45f,
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.70f,
            excludeRecentlyPlayed = true,
            maxTracks = 45
        ),
        MixPreset(
            id = PRESET_UPBEAT,
            title = "Upbeat",
            subtitle = "Bright, rhythmic energy",
            description = "Energetic tracks with rhythmic drive for movement and momentum.",
            targetEnergy = 0.78f,
            energyTolerance = 0.28f,
            targetRhythmActivity = 0.7f,
            rhythmTolerance = 0.3f,
            targetBrightness = 0.6f,
            targetTempoMinBpm = 100f,
            targetTempoMaxBpm = 160f,
            targetAcousticness = 0.3f,
            acousticnessTolerance = 0.6f,
            preferredTags = setOf("upbeat", "remix"),
            avoidedTags = setOf("ambient", "lo-fi"),
            targetDurationMs = 45L * 60L * 1000L,
            discoveryRatio = 0.20f,
            maxTracks = 45
        ),
        MixPreset(
            id = PRESET_CHILL,
            title = "Chill",
            subtitle = "Soft, low-energy textures",
            description = "Consistently calm listening with gentle variation — great for unwinding.",
            targetEnergy = 0.28f,
            energyTolerance = 0.22f,
            targetRhythmActivity = 0.3f,
            rhythmTolerance = 0.3f,
            targetBrightness = 0.4f,
            targetAcousticness = 0.5f,
            acousticnessTolerance = 0.55f,
            preferredTags = setOf("chill", "ambient", "acoustic", "lo-fi"),
            avoidedTags = setOf("remix"),
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.15f,
            maxTracks = 50
        ),
        MixPreset(
            id = PRESET_FOCUS,
            title = "Focus",
            subtitle = "Steady, low-distraction listening",
            description = "Stable, lower-to-medium energy with instrumental and focus-friendly characteristics.",
            targetEnergy = 0.42f,
            energyTolerance = 0.25f,
            targetRhythmActivity = 0.35f,
            rhythmTolerance = 0.3f,
            targetBrightness = 0.45f,
            targetAcousticness = 0.45f,
            acousticnessTolerance = 0.5f,
            targetInstrumental = 0.5f,
            preferredTags = setOf("focus", "instrumental", "ambient", "lo-fi"),
            avoidedTags = setOf("remix", "live", "upbeat"),
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.10f,
            maxTracks = 50
        ),
        MixPreset(
            id = PRESET_WORKOUT,
            title = "Workout",
            subtitle = "Warm-up, peak, cooldown",
            description = "A gradual energy build-up, a high-energy middle and a cooldown finish.",
            targetEnergy = 0.75f,
            energyTolerance = 0.35f,
            targetRhythmActivity = 0.75f,
            rhythmTolerance = 0.35f,
            targetTempoMinBpm = 110f,
            targetTempoMaxBpm = 180f,
            preferredTags = setOf("upbeat", "workout"),
            avoidedTags = setOf("ambient"),
            energyCurve = listOf(
                EnergyCurvePoint(0.0f, 0.4f),
                EnergyCurvePoint(0.2f, 0.6f),
                EnergyCurvePoint(0.5f, 0.85f),
                EnergyCurvePoint(0.75f, 0.9f),
                EnergyCurvePoint(1.0f, 0.4f)
            ),
            targetDurationMs = 45L * 60L * 1000L,
            discoveryRatio = 0.15f,
            maxTracks = 40
        ),
        MixPreset(
            id = PRESET_NIGHT,
            title = "Night",
            subtitle = "Late-night calm",
            description = "Lower energy and suitable late-night listening characteristics.",
            targetEnergy = 0.32f,
            energyTolerance = 0.25f,
            targetRhythmActivity = 0.3f,
            rhythmTolerance = 0.35f,
            targetBrightness = 0.35f,
            targetAcousticness = 0.4f,
            acousticnessTolerance = 0.55f,
            preferredTags = setOf("night", "ambient", "chill"),
            avoidedTags = setOf("remix", "upbeat"),
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.15f,
            maxTracks = 50
        )
    )

    private val byId = presets.associateBy { it.id }

    fun byId(id: String): MixPreset = byId[id] ?: presets.first()

    /** A stable per-refresh seed derived from the base seed and preset id. */
    fun seedFor(preset: MixPreset, baseSeed: Long): Long =
        baseSeed * 31L + (preset.id.hashCode().toLong() and 0xFFFFL)
}

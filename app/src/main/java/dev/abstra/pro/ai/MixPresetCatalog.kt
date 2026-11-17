package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.MixStrategy
import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.TimeOfDay

/**
 * Configuration-driven mood presets. Every preset uses the same scoring and
 * sequencing pipeline — only targets and retrieval strategies differ.
 *
 * The lineup is the eight listening categories:
 * DAILY_FLOW, DISCOVERY, ENERGY, CHILL, TIME_OF_DAY, FORGOTTEN_GEMS,
 * ACOUSTIC_LIVE, ARTIST_SPOTLIGHT.
 */
object MixPresetCatalog {

    const val PRESET_DAILY_FLOW = "daily_flow"
    const val PRESET_DISCOVERY = "discovery"
    const val PRESET_ENERGY = "upbeat"
    const val PRESET_CHILL = "chill"
    const val PRESET_TIME_OF_DAY = "time_of_day"
    const val PRESET_FORGOTTEN_GEMS = "forgotten_gems"
    const val PRESET_ACOUSTIC_LIVE = "acoustic_live"
    const val PRESET_ARTIST_SPOTLIGHT = "artist_spotlight"

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
            preferredTags = setOf("upbeat", "chill"),
            avoidedTags = setOf(),
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.20f,
            maxTracks = 50
        ),
        MixPreset(
            id = PRESET_DISCOVERY,
            title = "Deep Discovery",
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
            id = PRESET_ENERGY,
            title = "Energy Boost",
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
            preferredTags = setOf("upbeat"),
            avoidedTags = setOf("ambient", "lo-fi", "acoustic"),
            targetDurationMs = 45L * 60L * 1000L,
            discoveryRatio = 0.20f,
            maxTracks = 45
        ),
        MixPreset(
            id = PRESET_CHILL,
            title = "Chill & Focus",
            subtitle = "Soft, low-distraction listening",
            description = "Consistently calm listening with gentle, low-distraction characteristics.",
            targetEnergy = 0.32f,
            energyTolerance = 0.25f,
            targetRhythmActivity = 0.3f,
            rhythmTolerance = 0.3f,
            targetBrightness = 0.42f,
            targetAcousticness = 0.5f,
            acousticnessTolerance = 0.55f,
            targetInstrumental = 0.4f,
            preferredTags = setOf("chill", "ambient", "acoustic", "lo-fi", "focus", "instrumental"),
            avoidedTags = setOf("remix", "live", "upbeat"),
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.15f,
            maxTracks = 50
        ),
        MixPreset(
            id = PRESET_TIME_OF_DAY,
            title = "Time of Day",
            subtitle = "Music for this moment",
            description = "Built from what you actually listen to in this part of the day.",
            strategy = MixStrategy.TIME_OF_DAY,
            targetEnergy = 0.5f,
            energyTolerance = 0.45f,
            targetRhythmActivity = 0.45f,
            rhythmTolerance = 0.45f,
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.20f,
            excludeRecentlyPlayed = true,
            maxTracks = 45
        ),
        MixPreset(
            id = PRESET_FORGOTTEN_GEMS,
            title = "Forgotten Gems",
            subtitle = "Great songs you drifted away from",
            description = "Tracks you clearly enjoyed but have not heard in a while.",
            strategy = MixStrategy.FORGOTTEN_GEMS,
            targetEnergy = 0.55f,
            energyTolerance = 0.5f,
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.0f,
            excludeRecentlyPlayed = true,
            maxTracks = 45
        ),
        MixPreset(
            id = PRESET_ACOUSTIC_LIVE,
            title = "Acoustic & Live",
            subtitle = "Stripped-down and on stage",
            description = "Acoustic, live and instrumental recordings, from metadata and audio analysis.",
            strategy = MixStrategy.ACOUSTIC_LIVE,
            targetEnergy = 0.4f,
            energyTolerance = 0.4f,
            targetAcousticness = 0.8f,
            acousticnessTolerance = 0.35f,
            targetInstrumental = 0.35f,
            preferredTags = setOf("acoustic", "live", "instrumental", "unplugged"),
            avoidedTags = setOf("remix"),
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.20f,
            maxTracks = 45
        ),
        MixPreset(
            id = PRESET_ARTIST_SPOTLIGHT,
            title = "Artist Spotlight",
            subtitle = "A personal radio session",
            description = "Built around an artist you enjoy, opening into related music by other artists.",
            strategy = MixStrategy.ARTIST_SPOTLIGHT,
            targetEnergy = 0.55f,
            energyTolerance = 0.45f,
            targetDurationMs = 60L * 60L * 1000L,
            discoveryRatio = 0.35f,
            excludeRecentlyPlayed = true,
            maxTracks = 45
        )
    )

    private val byId = presets.associateBy { it.id }

    fun byId(id: String): MixPreset = byId[id] ?: presets.first()

    /**
     * The contextual preset adapted to the current time bucket: real targets
     * and titles change with the moment, backed by bucket-specific listening
     * evidence (never by clock time alone — cold start keeps the base preset).
     */
    fun timeOfDayPreset(bucket: TimeOfDay): MixPreset {
        val base = byId(PRESET_TIME_OF_DAY)
        return when (bucket) {
            TimeOfDay.MORNING -> base.copy(
                title = "Morning Mix",
                subtitle = "Ease into the day",
                targetEnergy = 0.55f,
                targetRhythmActivity = 0.45f
            )
            TimeOfDay.AFTERNOON -> base.copy(
                title = "Afternoon Mix",
                subtitle = "Keep the day moving",
                targetEnergy = 0.62f,
                targetRhythmActivity = 0.5f
            )
            TimeOfDay.EVENING -> base.copy(
                title = "Evening Mix",
                subtitle = "Wind the day down",
                targetEnergy = 0.45f,
                targetRhythmActivity = 0.4f
            )
            TimeOfDay.NIGHT -> base.copy(
                title = "Night Mix",
                subtitle = "Late-night calm",
                targetEnergy = 0.3f,
                targetRhythmActivity = 0.3f,
                targetBrightness = 0.35f
            )
        }
    }

    /** A stable per-refresh seed derived from the base seed and preset id. */
    fun seedFor(preset: MixPreset, baseSeed: Long): Long =
        baseSeed * 31L + (preset.id.hashCode().toLong() and 0xFFFFL)
}

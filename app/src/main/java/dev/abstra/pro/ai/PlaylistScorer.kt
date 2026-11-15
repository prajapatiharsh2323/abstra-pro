package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.RecommendationReason
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.ai.model.TimeOfDay
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import kotlin.math.abs
import kotlin.math.min

/** Snapshot of the user's context used for scoring. */
data class ScoringContext(
    val nowMs: Long,
    val timeOfDay: TimeOfDay,
    val isWeekend: Boolean,
    val artistAffinity: Map<String, AffinityModel.AggregateAffinity> = emptyMap(),
    val genreAffinity: Map<String, AffinityModel.AggregateAffinity> = emptyMap(),
    val tagAffinity: Map<String, AffinityModel.AggregateAffinity> = emptyMap(),
    val recentlyPlayedIds: Set<String> = emptySet()
)

/**
 * Preset-independent scoring of a song: everything that does not depend on
 * the requested mood. Computed once per library, then combined per preset.
 */
data class BaseScores(
    val song: Song,
    val profile: SongProfile,
    val playCount: Int,
    val preferenceValue: Float,
    val preferenceConfidence: Float,
    val completionRate: Float,
    val artistGenreValue: Float,
    val artistGenreConfidence: Float,
    val freshnessValue: Float,
    val contextValue: Float,
    val contextConfidence: Float,
    val reasons: Set<RecommendationReason>
)

/** Preset-dependent final score for a song. */
data class ScoredSong(
    val base: BaseScores,
    val score: Float,
    val moodValue: Float,
    val moodConfidence: Float,
    val reasons: Set<RecommendationReason>
)

/**
 * Scores every eligible library song using five dimensions:
 *
 * | Signal                     | Weight |
 * | Mood compatibility         | 40%    |
 * | Track preference           | 25%    |
 * | Artist and genre preference| 10%    |
 * | Freshness / novelty        | 20%    |
 * | Listening context          | 5%     |
 *
 * Weights are configurable constants. When a signal is missing or unreliable
 * its confidence drops, and the remaining weights are redistributed — a
 * poorly tagged library still produces useful, non-fabricated scores.
 */
object PlaylistScorer {

    const val WEIGHT_MOOD = 0.40f
    const val WEIGHT_PREFERENCE = 0.25f
    const val WEIGHT_ARTIST_GENRE = 0.10f
    const val WEIGHT_FRESHNESS = 0.20f
    const val WEIGHT_CONTEXT = 0.05f

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** Minimum listening history before context contributes anything. */
    private const val CONTEXT_MIN_PLAYS = 10

    // ─────────────────────────────────────────────────────────────
    // Base (mood-independent) scoring
    // ─────────────────────────────────────────────────────────────

    fun scoreBase(
        song: Song,
        profile: SongProfile,
        telemetry: PlaybackTelemetryEntity?,
        context: ScoringContext
    ): BaseScores {
        val affinity = AffinityModel.trackAffinity(telemetry, song.isFavorite)
        val artistGenre = artistGenreScore(song, profile, context)
        val freshness = freshnessScore(song, telemetry, context.nowMs)
        val (contextValue, contextConfidence) = contextScore(telemetry, context)

        val reasons = mutableSetOf<RecommendationReason>()

        if (song.isFavorite) reasons += RecommendationReason.FAVORITE_SONG
        if (affinity.completionRate >= 0.7f && (telemetry?.playCount ?: 0) >= 3) {
            reasons += RecommendationReason.HIGH_COMPLETION_RATE
        }
        val artistAgg = context.artistAffinity[AffinityModel.normalizeArtistKey(song)]
        if (artistAgg != null && artistAgg.value >= 0.7f && artistAgg.confidence >= 0.5f) {
            reasons += RecommendationReason.FAVORITE_ARTIST
        }
        val playCount = telemetry?.playCount ?: 0
        if (playCount <= 1) reasons += RecommendationReason.FRESH_DISCOVERY
        if (playCount >= 2 && (telemetry?.lastPlayedTimestamp ?: 0L) > 0L) {
            val days = (context.nowMs - telemetry!!.lastPlayedTimestamp) / (DAY_MS)
            if (days >= 21) reasons += RecommendationReason.NOT_PLAYED_RECENTLY
        }
        if (contextValue >= 0.6f && contextConfidence >= 0.5f) {
            reasons += RecommendationReason.MATCHES_LISTENING_CONTEXT
        }

        return BaseScores(
            song = song,
            profile = profile,
            playCount = playCount,
            preferenceValue = affinity.value,
            preferenceConfidence = affinity.confidence,
            completionRate = affinity.completionRate,
            artistGenreValue = artistGenre.first,
            artistGenreConfidence = artistGenre.second,
            freshnessValue = freshness,
            contextValue = contextValue,
            contextConfidence = contextConfidence,
            reasons = reasons
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Mood-dependent scoring
    // ─────────────────────────────────────────────────────────────

    /**
     * Combines base scores with mood compatibility for a preset.
     * [flowEnergy] lets the sequencer target an energy curve position.
     */
    fun score(base: BaseScores, preset: MixPreset, flowEnergy: Float? = null): ScoredSong {
        val (moodValue, moodConfidence) = moodScore(base, preset, flowEnergy)

        val components = listOf(
            Triple(moodValue, WEIGHT_MOOD, moodConfidence),
            Triple(base.preferenceValue, WEIGHT_PREFERENCE, base.preferenceConfidence),
            Triple(base.artistGenreValue, WEIGHT_ARTIST_GENRE, base.artistGenreConfidence),
            Triple(base.freshnessValue, WEIGHT_FRESHNESS, 1f),
            Triple(base.contextValue, WEIGHT_CONTEXT, base.contextConfidence)
        )

        var totalWeight = 0f
        var weightedSum = 0f
        for ((value, weight, confidence) in components) {
            val effectiveWeight = weight * confidence
            totalWeight += effectiveWeight
            weightedSum += effectiveWeight * value
        }
        val score = if (totalWeight > 0.001f) {
            (weightedSum / totalWeight).coerceIn(0f, 1f)
        } else {
            0.4f
        }

        val reasons = if (moodValue >= 0.75f && moodConfidence >= 0.4f) {
            base.reasons + RecommendationReason.MOOD_MATCH
        } else {
            base.reasons
        }

        return ScoredSong(
            base = base,
            score = score,
            moodValue = moodValue,
            moodConfidence = moodConfidence,
            reasons = reasons
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Dimension implementations
    // ─────────────────────────────────────────────────────────────

    private fun moodScore(
        base: BaseScores,
        preset: MixPreset,
        flowEnergy: Float?
    ): Pair<Float, Float> {
        val profile = base.profile
        val targetEnergy = flowEnergy ?: preset.targetEnergy

        // Feature matches with their own confidences.
        val featureParts = mutableListOf<Pair<Float, Float>>()

        if (profile.energyConfidence > 0f) {
            val match = 1f - min(1f, abs(profile.energy - targetEnergy) / preset.energyTolerance)
            featureParts += match to (0.60f * profile.energyConfidence)
        }
        preset.targetAcousticness?.let { target ->
            if (profile.acousticConfidence > 0f) {
                val match = 1f - min(1f, abs(profile.acousticness - target) / preset.acousticnessTolerance)
                featureParts += match to (0.25f * profile.acousticConfidence)
            }
        }
        preset.targetInstrumental?.let { target ->
            if (profile.instrumentalConfidence > 0f) {
                val match = 1f - min(1f, abs(profile.instrumentalLikelihood - target))
                featureParts += match to (0.15f * profile.instrumentalConfidence)
            }
        }

        val featureValue: Float? = if (featureParts.isEmpty()) null else weightedAverage(featureParts)
        var featureConfidenceRaw = 0f
        for (part in featureParts) featureConfidenceRaw += part.second
        val featureConfidence = min(1f, featureConfidenceRaw)

        // Genre/tag compatibility as a supporting signal.
        val preferredHits = profile.tags.intersect(preset.preferredTags)
        val avoidedHits = profile.tags.intersect(preset.avoidedTags)
        val tagValue = (0.5f + 0.2f * preferredHits.size - 0.25f * avoidedHits.size).coerceIn(0f, 1f)
        val tagConfidence = if (preferredHits.isEmpty() && avoidedHits.isEmpty()) 0f else 0.35f

        return when {
            featureValue != null && featureConfidence > 0f -> {
                val parts = mutableListOf(featureValue to featureConfidence)
                if (tagConfidence > 0f) parts += tagValue to tagConfidence
                weightedAverage(parts) to min(1f, featureConfidence + tagConfidence * 0.5f)
            }
            tagConfidence > 0f -> tagValue to tagConfidence
            // No reliable information: neutral value, zero confidence, so the
            // mood weight is redistributed instead of penalizing the song.
            else -> 0.5f to 0f
        }
    }

    private fun artistGenreScore(
        song: Song,
        profile: SongProfile,
        context: ScoringContext
    ): Pair<Float, Float> {
        val parts = mutableListOf<Pair<Float, Float>>()

        context.artistAffinity[AffinityModel.normalizeArtistKey(song)]?.let {
            if (it.confidence > 0f) parts += it.value to it.confidence
        }

        var bestGenre: Pair<Float, Float>? = null
        for (genre in AffinityModel.normalizeGenres(song.genre)) {
            val agg = context.genreAffinity[genre] ?: continue
            if (agg.confidence <= 0f) continue
            val current = bestGenre
            if (current == null || agg.confidence > current.second) {
                bestGenre = agg.value to agg.confidence
            }
        }
        bestGenre?.let { parts += it }

        if (profile.tags.isNotEmpty()) {
            var tagSum = 0f
            var tagWeight = 0f
            for (tag in profile.tags) {
                val agg = context.tagAffinity[tag] ?: continue
                if (agg.confidence <= 0f) continue
                tagSum += agg.value * agg.confidence
                tagWeight += agg.confidence
            }
            if (tagWeight > 0f) {
                parts += (tagSum / tagWeight) to min(1f, tagWeight)
            }
        }

        var partsConfidence = 0f
        for (part in parts) partsConfidence += part.second
        return if (parts.isEmpty()) {
            AffinityModel.NEUTRAL_AFFINITY to 0f
        } else {
            weightedAverage(parts) to min(1f, partsConfidence)
        }
    }

    private fun freshnessScore(
        song: Song,
        telemetry: PlaybackTelemetryEntity?,
        nowMs: Long
    ): Float {
        val unplayed = telemetry == null || telemetry.playCount <= 0
        if (unplayed) {
            val ageDays = ((nowMs - song.dateAdded).coerceAtLeast(0L)) / DAY_MS
            // A brand new song is a promising discovery, not a dislike.
            return if (ageDays <= 14) 0.9f else 0.75f
        }
        val t = telemetry!!
        val last = t.lastPlayedTimestamp
        if (last <= 0L) return 0.75f
        val days = ((nowMs - last).coerceAtLeast(0L)) / DAY_MS
        return when {
            days <= 2 -> 0.05f
            days <= 7 -> 0.05f + (days - 2) * 0.075f
            days <= 21 -> 0.45f + (days - 7) * 0.021f
            else -> 0.85f
        }
    }

    private fun contextScore(
        telemetry: PlaybackTelemetryEntity?,
        context: ScoringContext
    ): Pair<Float, Float> {
        val t = telemetry ?: return 0.5f to 0f
        val total = t.morningPlays + t.afternoonPlays + t.eveningPlays + t.nightPlays
        if (total < CONTEXT_MIN_PLAYS) return 0.5f to 0f

        val bucketPlays = when (context.timeOfDay) {
            TimeOfDay.MORNING -> t.morningPlays
            TimeOfDay.AFTERNOON -> t.afternoonPlays
            TimeOfDay.EVENING -> t.eveningPlays
            TimeOfDay.NIGHT -> t.nightPlays
        }
        val share = bucketPlays.toFloat() / total
        var value = 0.5f + (share - 0.25f) * 2f

        // Weekend / weekday is a minor supporting adjustment.
        val weekendShare = t.weekendPlays.toFloat() / (t.weekendPlays + t.weekdayPlays).coerceAtLeast(1)
        value += if (context.isWeekend) (weekendShare - 0.4f) * 0.4f
        else (0.6f - weekendShare) * 0.4f

        return value.coerceIn(0f, 1f) to min(1f, total / 30f)
    }

    private fun weightedAverage(parts: List<Pair<Float, Float>>): Float {
        var sum = 0f
        var weight = 0f
        for ((value, confidence) in parts) {
            sum += value * confidence
            weight += confidence
        }
        return if (weight <= 0f) 0.5f else sum / weight
    }
}

package dev.abstra.pro.ai.recommendation

import dev.abstra.pro.data.db.entities.PlaybackFeedbackEntity
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Confidence-aware user taste model built from the timestamped playback
 * feedback history plus the aggregate telemetry.
 *
 * Principles:
 *  - no history is neutral, never disliked;
 *  - recent taste and long-term taste are separate windows;
 *  - old events gradually lose influence (exponential time decay);
 *  - artist/genre aggregates shrink toward a neutral prior so an artist
 *    with many songs does not outrank one with two just by opportunity.
 */
object UserTasteModel {

    /** Half-life (days) of the long-term taste window. */
    const val LONG_TERM_HALF_LIFE_DAYS = 180f

    /** Half-life (days) of the recent taste window. */
    const val RECENT_HALF_LIFE_DAYS = 21f

    /** Feedback strength (relative, tunable — not psychological truth). */
    const val STRENGTH_FAVORITE_ADDED = 5f
    const val STRENGTH_ADDED_TO_PLAYLIST = 3f
    const val STRENGTH_COMPLETION = 2f
    const val STRENGTH_MEANINGFUL_PLAY = 1f
    const val STRENGTH_REPLAY = 2f
    const val STRENGTH_FAST_SKIP = -2f
    const val STRENGTH_FAVORITE_REMOVED = -2f

    /** Strong repeated negative evidence before a track is "disliked". */
    const val MIN_NEGATIVE_EVIDENCE = 3

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** Time decay: weight = exp(-ln2 * age / halfLife). */
    fun decayWeight(ageDays: Float, halfLifeDays: Float): Float {
        if (halfLifeDays <= 0f) return 1f
        return exp((-kotlin.math.ln(2.0) * ageDays / halfLifeDays).toFloat())
    }

    fun eventStrength(eventType: String): Float = when (eventType) {
        PlaybackFeedbackEntity.EVENT_FAVORITE_ADDED -> STRENGTH_FAVORITE_ADDED
        PlaybackFeedbackEntity.EVENT_ADDED_TO_USER_PLAYLIST -> STRENGTH_ADDED_TO_PLAYLIST
        PlaybackFeedbackEntity.EVENT_COMPLETION -> STRENGTH_COMPLETION
        PlaybackFeedbackEntity.EVENT_MEANINGFUL_PLAY -> STRENGTH_MEANINGFUL_PLAY
        PlaybackFeedbackEntity.EVENT_REPLAY -> STRENGTH_REPLAY
        PlaybackFeedbackEntity.EVENT_FAST_SKIP -> STRENGTH_FAST_SKIP
        PlaybackFeedbackEntity.EVENT_FAVORITE_REMOVED -> STRENGTH_FAVORITE_REMOVED
        else -> 0f
    }

    data class TrackTaste(
        /** Positive preference, Bayesian-smoothed toward 0.5 (0..1). */
        val positive: Float,
        /** Negative preference strength 0..1 (0 = no negative evidence). */
        val negative: Float,
        /** Confidence that we actually know this track's reception (0..1). */
        val confidence: Float,
        /** How many negative events were recorded (evidence count). */
        val negativeCount: Int = 0,

        /** Completion rate from feedback events (0..1), 0 when unknown. */
        val completionRate: Float = 0f,
        /** Recent engagement relative to the recent window (0..1). */
        val recentEngagement: Float = 0f
    )

    /**
     * Builds per-track taste from feedback events (long window) and the
     * aggregate telemetry as a supporting fallback.
     */
    fun buildTrackTastes(
        events: List<PlaybackFeedbackEntity>,
        telemetry: Map<String, PlaybackTelemetryEntity>,
        nowMs: Long
    ): Map<String, TrackTaste> {
        class Acc {
            var posSum = 0f
            var posWeight = 0f
            var recentPosSum = 0f
            var recentPosWeight = 0f
            var negSum = 0f
            var negCount = 0
            var completions = 0
            var plays = 0
        }

        val accs = HashMap<String, Acc>()
        for (event in events) {
            val acc = accs.getOrPut(event.songId) { Acc() }
            val strength = eventStrength(event.eventType)
            val ageDays = ((nowMs - event.occurredAtMs).coerceAtLeast(0L)).toFloat() / DAY_MS
            val longWeight = decayWeight(ageDays, LONG_TERM_HALF_LIFE_DAYS)
            val recentWeight = decayWeight(ageDays, RECENT_HALF_LIFE_DAYS)

            if (strength >= 0f) {
                acc.posSum += strength * longWeight
                acc.posWeight += strength
                acc.recentPosSum += strength * recentWeight
                acc.recentPosWeight += strength
                if (event.eventType == PlaybackFeedbackEntity.EVENT_COMPLETION) acc.completions++
                if (event.eventType == PlaybackFeedbackEntity.EVENT_MEANINGFUL_PLAY ||
                    event.eventType == PlaybackFeedbackEntity.EVENT_COMPLETION ||
                    event.eventType == PlaybackFeedbackEntity.EVENT_REPLAY
                ) acc.plays++
            } else {
                acc.negSum += -strength * longWeight
                acc.negCount++
            }
        }

        val result = HashMap<String, TrackTaste>(accs.size)
        for ((songId, acc) in accs) {
            // Bayesian smoothing: evidence shrunk toward the neutral prior.
            val evidence = acc.posWeight
            val smoothedPositive = if (evidence > 0f) {
                (acc.posSum + 1f * 2f) / (evidence + 2f) // prior 0.5 * strength scale 2
            } else {
                0.5f
            }
            val negativeStrength = (acc.negSum / MAX_NEGATIVE_SCALE).coerceIn(0f, 1f)
            val completionRate = if (acc.plays > 0) {
                (acc.completions.toFloat() / acc.plays).coerceIn(0f, 1f)
            } else 0f
            val recentEngagement = if (acc.recentPosWeight > 0f) {
                (acc.recentPosSum + 1f) / (acc.recentPosWeight + 2f)
            } else 0f

            val confidence = if (evidence + acc.negSum <= 0f) {
                0f
            } else {
                min(1f, (evidence + acc.negSum * 0.5f) / MIN_CONFIDENCE_EVIDENCE)
            }

            result[songId] = TrackTaste(
                positive = smoothedPositive.coerceIn(0f, 1f),
                negative = negativeStrength,
                negativeCount = acc.negCount,
                confidence = confidence,
                completionRate = completionRate,
                recentEngagement = recentEngagement.coerceIn(0f, 1f)
            )
        }
        return result
    }

    /** Aggregate preference for artists/genres/tags with shrinkage. */
    data class AggregateTaste(val value: Float, val confidence: Float)

    fun buildAggregates(
        songs: List<Song>,
        trackTastes: Map<String, TrackTaste>,
        keyOf: (Song) -> List<String>
    ): Map<String, AggregateTaste> {
        class Acc {
            var sum = 0f
            var weight = 0f
            var count = 0
        }

        val accs = HashMap<String, Acc>()
        for (song in songs) {
            val taste = trackTastes[song.id] ?: continue
            if (taste.confidence <= 0f) continue
            for (key in keyOf(song)) {
                val acc = accs.getOrPut(key) { Acc() }
                // One song's evidence is capped so prolific artists cannot
                // accumulate unlimited weight from a single hit.
                val evidence = min(1f, taste.confidence)
                acc.sum += taste.positive * evidence
                acc.weight += evidence
                acc.count++
            }
        }
        val result = HashMap<String, AggregateTaste>(accs.size)
        for ((key, acc) in accs) {
            val count = acc.count.coerceAtLeast(1)
            val distinctSongs = min(count, MAX_AGGREGATE_SONG_CONTRIBUTION)
            val shrunk = (acc.sum + 0.5f * PRIOR_PSEUDO_COUNT) /
                    (acc.weight + PRIOR_PSEUDO_COUNT)
            result[key] = AggregateTaste(
                value = shrunk.coerceIn(0f, 1f),
                confidence = min(1f, distinctSongs.toFloat() / 4f)
            )
        }
        return result
    }

    /**
     * Negative seed weights for PageRank, built ONLY from reliable negative
     * evidence: repeated fast skips ([MIN_NEGATIVE_EVIDENCE] or more).
     * A single early skip is never a negative seed.
     */
    fun buildNegativeSeeds(
        trackTastes: Map<String, TrackTaste>,
        minStrength: Float = 0.15f
    ): Map<String, Float> = trackTastes
        .filter { (_, taste) ->
            taste.negativeCount >= MIN_NEGATIVE_EVIDENCE &&
                    taste.negative >= minStrength && taste.confidence > 0f
        }
        .mapValues { (_, taste) -> taste.negative * taste.confidence }

    // ── Tuning constants ──
    private const val MIN_CONFIDENCE_EVIDENCE = 4f
    private const val MAX_NEGATIVE_SCALE = 6f
    private const val PRIOR_PSEUDO_COUNT = 2f
    private const val MAX_AGGREGATE_SONG_CONTRIBUTION = 8
}

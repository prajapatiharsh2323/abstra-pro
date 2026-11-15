package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Normalized, confidence-aware preference model built from playback
 * telemetry. Counters are normalized instead of adding raw counts
 * indefinitely, so a single heavily played song cannot dominate forever,
 * and a song with no history is neutral — never disliked.
 */
object AffinityModel {

    /** Neutral value used when there is no information at all. */
    const val NEUTRAL_AFFINITY = 0.5f

    /** Prior strength for smoothed aggregate averages. */
    private const val AGGREGATE_PRIOR = 0.5f
    private const val AGGREGATE_PSEUDO_COUNT = 3f

    data class TrackAffinity(
        val value: Float,
        val confidence: Float,
        val completionRate: Float,
        val quickSkipRate: Float
    )

    data class AggregateAffinity(
        val value: Float,
        val confidence: Float
    )

    data class Aggregates(
        val artist: Map<String, AggregateAffinity>,
        val genre: Map<String, AggregateAffinity>,
        val tag: Map<String, AggregateAffinity>
    )

    /**
     * Track affinity in 0..1 with a confidence (0..1).
     *
     *   0.35 x Completion Rate
     * + 0.25 x Favorite Signal
     * + 0.20 x Normalized Play Frequency
     * + 0.20 x Replay Signal
     * - 0.35 x Quick Skip Rate
     */
    fun trackAffinity(telemetry: PlaybackTelemetryEntity?, isFavorite: Boolean): TrackAffinity {
        if (telemetry == null && !isFavorite) {
            return TrackAffinity(NEUTRAL_AFFINITY, 0f, 0f, 0f)
        }
        val t = telemetry ?: PlaybackTelemetryEntity(songId = "")
        val plays = max(1, t.playCount)
        val completionRate = (t.fullCompletions.toFloat() / plays).coerceIn(0f, 1f)
        val frequency = t.playCount.toFloat() / (t.playCount + 6f)
        val replayRate = (t.repeatCount.toFloat() / plays).coerceIn(0f, 1f)
        val quickSkipRate = (t.fastSkips.toFloat() / plays).coerceIn(0f, 1f)
        val favoriteSignal = if (isFavorite) 1f else 0f

        var value = 0.35f * completionRate +
                0.25f * favoriteSignal +
                0.20f * frequency +
                0.20f * replayRate -
                0.35f * quickSkipRate
        value = value.coerceIn(0f, 1f)
        if (isFavorite) value = max(value, 0.9f)

        val confidence = if (isFavorite) 1f else min(1f, t.playCount / 5f)
        return TrackAffinity(value, confidence, completionRate, quickSkipRate)
    }

    /**
     * Aggregates artist, genre and tag preferences from positive listening
     * behaviour only. Averages are smoothed with a prior so an artist with
     * many tracks does not automatically dominate.
     */
    fun buildAggregates(
        songs: List<Song>,
        telemetry: Map<String, PlaybackTelemetryEntity>,
        profiles: Map<String, SongProfile>
    ): Aggregates {
        val artistAcc = mutableMapOf<String, Accumulator>()
        val genreAcc = mutableMapOf<String, Accumulator>()
        val tagAcc = mutableMapOf<String, Accumulator>()

        for (song in songs) {
            val t = telemetry[song.id]
            val hasHistory = t != null && t.playCount > 0
            if (!hasHistory && !song.isFavorite) continue
            val affinity = trackAffinity(t, song.isFavorite)

            val artistKey = song.artist.trim().lowercase(Locale.ROOT)
            if (artistKey.isNotBlank() && artistKey != "unknown artist") {
                artistAcc.getOrPut(artistKey) { Accumulator() }.add(affinity.value)
            }

            for (genre in normalizeGenres(song.genre)) {
                genreAcc.getOrPut(genre) { Accumulator() }.add(affinity.value)
            }

            profiles[song.id]?.tags?.forEach { tag ->
                tagAcc.getOrPut(tag) { Accumulator() }.add(affinity.value)
            }
        }

        return Aggregates(
            artist = artistAcc.mapValues { it.value.toAggregate() },
            genre = genreAcc.mapValues { it.value.toAggregate() },
            tag = tagAcc.mapValues { it.value.toAggregate() }
        )
    }

    fun normalizeArtistKey(song: Song): String = song.artist.trim().lowercase(Locale.ROOT)

    fun normalizeGenres(genre: String?): List<String> {
        if (genre.isNullOrBlank()) return emptyList()
        return genre.split(',', ';', '|', '/', '&')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    private class Accumulator {
        var sum = 0f
        var count = 0

        fun add(value: Float) {
            sum += value
            count++
        }

        fun toAggregate(): AggregateAffinity {
            val smoothed = (sum + AGGREGATE_PRIOR * AGGREGATE_PSEUDO_COUNT) /
                    (count + AGGREGATE_PSEUDO_COUNT)
            return AggregateAffinity(
                value = smoothed.coerceIn(0f, 1f),
                confidence = min(1f, count / 4f)
            )
        }
    }
}

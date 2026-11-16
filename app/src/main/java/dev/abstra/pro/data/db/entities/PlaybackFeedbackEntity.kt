package dev.abstra.pro.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Compact timestamped playback feedback history. Unlike the aggregate
 * [PlaybackTelemetryEntity] counters, these events carry timestamps so the
 * taste model can apply time decay and separate recent from long-term taste.
 *
 * Events are recorded only at meaningful interaction boundaries — never on
 * every progress tick. Retention is bounded (rolling window + event cap).
 */
@Entity(
    tableName = "playback_feedback",
    indices = [Index("songId"), Index("occurredAtMs")]
)
data class PlaybackFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val songId: String,
    val eventType: String,
    val occurredAtMs: Long,

    val listenedMs: Long = 0L,
    val durationMs: Long = 0L
) {
    companion object {
        const val EVENT_MEANINGFUL_PLAY = "MEANINGFUL_PLAY"
        const val EVENT_COMPLETION = "COMPLETION"
        const val EVENT_REPLAY = "REPLAY"
        const val EVENT_FAST_SKIP = "FAST_SKIP"
        const val EVENT_FAVORITE_ADDED = "FAVORITE_ADDED"
        const val EVENT_FAVORITE_REMOVED = "FAVORITE_REMOVED"
        const val EVENT_ADDED_TO_USER_PLAYLIST = "ADDED_TO_USER_PLAYLIST"
    }
}

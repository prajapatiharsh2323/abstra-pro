package dev.abstra.pro.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.abstra.pro.data.db.entities.PlaybackFeedbackEntity

@Dao
interface PlaybackFeedbackDao {

    @Insert
    suspend fun insert(event: PlaybackFeedbackEntity)

    @Insert
    suspend fun insertAll(events: List<PlaybackFeedbackEntity>)

    @Query("SELECT * FROM playback_feedback ORDER BY occurredAtMs ASC")
    suspend fun getAllSync(): List<PlaybackFeedbackEntity>

    @Query("SELECT * FROM playback_feedback WHERE occurredAtMs >= :sinceMs ORDER BY occurredAtMs ASC")
    suspend fun getEventsSince(sinceMs: Long): List<PlaybackFeedbackEntity>

    @Query("SELECT COUNT(*) FROM playback_feedback")
    suspend fun countAll(): Int

    @Query("SELECT MIN(occurredAtMs) FROM playback_feedback")
    suspend fun oldestEventMs(): Long?

    /** Deletes the oldest events beyond the newest [keep] rows. */
    @Query(
        "DELETE FROM playback_feedback WHERE id NOT IN " +
                "(SELECT id FROM playback_feedback ORDER BY occurredAtMs DESC, id DESC LIMIT :keep)"
    )
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM playback_feedback WHERE songId = :songId")
    suspend fun deleteEventsForSong(songId: String)

    @Query("DELETE FROM playback_feedback WHERE occurredAtMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("DELETE FROM playback_feedback")
    suspend fun clearAll()
}

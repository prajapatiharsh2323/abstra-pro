package dev.abstra.pro.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.abstra.pro.data.db.entities.SongNeighborEntity

@Dao
interface SongNeighborDao {

    @Query("SELECT * FROM song_neighbors")
    suspend fun getAllSync(): List<SongNeighborEntity>

    @Query("SELECT * FROM song_neighbors WHERE songId = :songId")
    suspend fun getNeighborsFor(songId: String): List<SongNeighborEntity>

    @Query("SELECT * FROM song_neighbors WHERE neighborSongId = :songId")
    suspend fun getReverseNeighborsFor(songId: String): List<SongNeighborEntity>

    @Query("SELECT COUNT(*) FROM song_neighbors")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(edges: List<SongNeighborEntity>)

    @Query("DELETE FROM song_neighbors WHERE songId = :songId AND edgeType = :edgeType")
    suspend fun deleteEdgesFor(songId: String, edgeType: String)

    @Query("DELETE FROM song_neighbors WHERE neighborSongId = :songId")
    suspend fun deleteReverseEdgesFor(songId: String)

    @Query("DELETE FROM song_neighbors WHERE songId = :songId OR neighborSongId = :songId")
    suspend fun deleteAllEdgesOf(songId: String)

    @Query("DELETE FROM song_neighbors")
    suspend fun clearAll()

    /** Atomic replacement of one song's outgoing edges for one edge type. */
    @Transaction
    suspend fun replaceNeighborsFor(songId: String, edgeType: String, edges: List<SongNeighborEntity>) {
        deleteEdgesFor(songId, edgeType)
        if (edges.isNotEmpty()) insertAll(edges)
    }
}

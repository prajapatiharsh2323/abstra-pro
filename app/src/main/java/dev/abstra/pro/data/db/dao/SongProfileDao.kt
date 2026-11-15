package dev.abstra.pro.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.abstra.pro.data.db.entities.SongProfileEntity

@Dao
interface SongProfileDao {
    @Query("SELECT * FROM song_profiles")
    suspend fun getAllSync(): List<SongProfileEntity>

    @Query("SELECT * FROM song_profiles WHERE songId = :songId LIMIT 1")
    suspend fun getProfile(songId: String): SongProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<SongProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: SongProfileEntity)

    @Query("DELETE FROM song_profiles WHERE songId = :songId")
    suspend fun deleteProfile(songId: String)

    @Query("DELETE FROM song_profiles")
    suspend fun clearAll()
}

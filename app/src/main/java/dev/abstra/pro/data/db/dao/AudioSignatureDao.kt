package dev.abstra.pro.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.abstra.pro.data.db.entities.AudioSignatureEntity

@Dao
interface AudioSignatureDao {

    @Query("SELECT * FROM audio_signatures")
    suspend fun getAllSync(): List<AudioSignatureEntity>

    @Query("SELECT * FROM audio_signatures WHERE songId = :songId LIMIT 1")
    suspend fun getSignature(songId: String): AudioSignatureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signature: AudioSignatureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(signatures: List<AudioSignatureEntity>)

    @Query("DELETE FROM audio_signatures WHERE songId = :songId")
    suspend fun deleteSignature(songId: String)

    @Query("DELETE FROM audio_signatures")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM audio_signatures WHERE analysisStatus = 'SUCCESS'")
    suspend fun countSuccess(): Int

    @Query("SELECT COUNT(*) FROM audio_signatures")
    suspend fun countAll(): Int
}

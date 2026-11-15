package dev.abstra.pro.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.abstra.pro.ai.SongProfileAnalyzer
import dev.abstra.pro.ai.model.SongProfile

/**
 * Cached recommendation profile for one song. Derived data only — it is
 * always rebuilt from effective metadata (and optional audio analysis), so
 * it is not part of the SAF backup.
 */
@Entity(tableName = "song_profiles")
data class SongProfileEntity(
    @PrimaryKey val songId: String,

    val energy: Float,
    val acousticness: Float,
    val instrumentalLikelihood: Float,
    val rhythmActivity: Float?,

    /** Delimiter-encoded tag set. */
    val tags: String,

    val energyConfidence: Float,
    val acousticConfidence: Float,
    val instrumentalConfidence: Float,

    val profileSource: String,
    val metadataFingerprint: String,
    val analysisVersion: Int,

    val audioAnalyzedAt: Long? = null,
    val updatedAt: Long
) {
    companion object {
        private const val TAG_DELIMITER = "\u001F"

        fun encodeTags(tags: Set<String>): String =
            if (tags.isEmpty()) "" else tags.sorted().joinToString(TAG_DELIMITER)

        fun decodeTags(encoded: String): Set<String> =
            if (encoded.isBlank()) emptySet() else encoded.split(TAG_DELIMITER).filter { it.isNotBlank() }.toSet()
    }
}

fun SongProfileEntity.toDomain(): SongProfile = SongProfile(
    songId = songId,
    energy = energy,
    acousticness = acousticness,
    instrumentalLikelihood = instrumentalLikelihood,
    rhythmActivity = rhythmActivity,
    tags = SongProfileEntity.decodeTags(tags),
    energyConfidence = energyConfidence,
    acousticConfidence = acousticConfidence,
    instrumentalConfidence = instrumentalConfidence,
    profileSource = profileSource,
    metadataFingerprint = metadataFingerprint,
    analysisVersion = analysisVersion
)

fun SongProfile.toEntity(
    updatedAt: Long,
    audioAnalyzedAt: Long? = null
): SongProfileEntity {
    val source = if (profileSource == SongProfileAnalyzer.PROFILE_SOURCE_BLENDED || rhythmActivity != null) {
        profileSource
    } else {
        SongProfileAnalyzer.PROFILE_SOURCE_METADATA
    }
    return SongProfileEntity(
        songId = songId,
        energy = energy,
        acousticness = acousticness,
        instrumentalLikelihood = instrumentalLikelihood,
        rhythmActivity = rhythmActivity,
        tags = SongProfileEntity.encodeTags(tags),
        energyConfidence = energyConfidence,
        acousticConfidence = acousticConfidence,
        instrumentalConfidence = instrumentalConfidence,
        profileSource = source,
        metadataFingerprint = metadataFingerprint,
        analysisVersion = analysisVersion,
        audioAnalyzedAt = audioAnalyzedAt,
        updatedAt = updatedAt
    )
}

package dev.abstra.pro.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.abstra.pro.ai.model.AudioSignature

/**
 * Persisted audio signature for one song. Vectors are stored as compact
 * Float32 blobs ([FloatVectorCodec]); scalars keep their 0..1 normalization.
 * Derived data only — always rebuildable from the source audio, so it is
 * not part of the SAF backup.
 */
@Entity(tableName = "audio_signatures")
data class AudioSignatureEntity(
    @PrimaryKey val songId: String,

    val mfccMeanBlob: ByteArray,
    val mfccStdBlob: ByteArray,
    val chromaBlob: ByteArray,

    val chromaConfidence: Float = 0f,
    val tempoBpm: Float = 0f,
    val tempoConfidence: Float = 0f,

    val rmsEnergy: Float = 0f,
    val dynamicRange: Float = 0f,
    val spectralCentroid: Float = 0f,
    val spectralFlux: Float = 0f,
    val onsetDensity: Float = 0f,
    val rhythmActivity: Float = 0f,

    val featureConfidence: Float = 0f,
    val extractorVersion: Int = 0,
    val metadataFingerprint: String = "",
    val analyzedAtMs: Long = 0L,

    /** SUCCESS, UNSUPPORTED or FAILED. */
    val analysisStatus: String = AudioSignature.STATUS_SUCCESS
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSignatureEntity) return false
        return songId == other.songId &&
                mfccMeanBlob.contentEquals(other.mfccMeanBlob) &&
                mfccStdBlob.contentEquals(other.mfccStdBlob) &&
                chromaBlob.contentEquals(other.chromaBlob) &&
                chromaConfidence == other.chromaConfidence &&
                tempoBpm == other.tempoBpm &&
                tempoConfidence == other.tempoConfidence &&
                rmsEnergy == other.rmsEnergy &&
                dynamicRange == other.dynamicRange &&
                spectralCentroid == other.spectralCentroid &&
                spectralFlux == other.spectralFlux &&
                onsetDensity == other.onsetDensity &&
                rhythmActivity == other.rhythmActivity &&
                featureConfidence == other.featureConfidence &&
                extractorVersion == other.extractorVersion &&
                metadataFingerprint == other.metadataFingerprint &&
                analyzedAtMs == other.analyzedAtMs &&
                analysisStatus == other.analysisStatus
    }

    override fun hashCode(): Int {
        var result = songId.hashCode()
        result = 31 * result + mfccMeanBlob.contentHashCode()
        result = 31 * result + mfccStdBlob.contentHashCode()
        result = 31 * result + chromaBlob.contentHashCode()
        result = 31 * result + chromaConfidence.hashCode()
        result = 31 * result + tempoBpm.hashCode()
        result = 31 * result + tempoConfidence.hashCode()
        result = 31 * result + rmsEnergy.hashCode()
        result = 31 * result + dynamicRange.hashCode()
        result = 31 * result + spectralCentroid.hashCode()
        result = 31 * result + spectralFlux.hashCode()
        result = 31 * result + onsetDensity.hashCode()
        result = 31 * result + rhythmActivity.hashCode()
        result = 31 * result + featureConfidence.hashCode()
        result = 31 * result + extractorVersion
        result = 31 * result + metadataFingerprint.hashCode()
        result = 31 * result + analyzedAtMs.hashCode()
        result = 31 * result + analysisStatus.hashCode()
        return result
    }
}

fun AudioSignatureEntity.toDomain(): AudioSignature = AudioSignature(
    songId = songId,
    mfccMean = FloatVectorCodec.decode(mfccMeanBlob),
    mfccStd = FloatVectorCodec.decode(mfccStdBlob),
    chroma = FloatVectorCodec.decode(chromaBlob),
    chromaConfidence = chromaConfidence,
    tempoBpm = tempoBpm,
    tempoConfidence = tempoConfidence,
    rmsEnergy = rmsEnergy,
    dynamicRange = dynamicRange,
    spectralCentroid = spectralCentroid,
    spectralFlux = spectralFlux,
    onsetDensity = onsetDensity,
    rhythmActivity = rhythmActivity,
    featureConfidence = featureConfidence,
    extractorVersion = extractorVersion,
    metadataFingerprint = metadataFingerprint,
    analysisStatus = analysisStatus,
    analyzedAtMs = analyzedAtMs
)

fun AudioSignature.toEntity(): AudioSignatureEntity = AudioSignatureEntity(
    songId = songId,
    mfccMeanBlob = FloatVectorCodec.encode(mfccMean),
    mfccStdBlob = FloatVectorCodec.encode(mfccStd),
    chromaBlob = FloatVectorCodec.encode(chroma),
    chromaConfidence = chromaConfidence,
    tempoBpm = tempoBpm,
    tempoConfidence = tempoConfidence,
    rmsEnergy = rmsEnergy,
    dynamicRange = dynamicRange,
    spectralCentroid = spectralCentroid,
    spectralFlux = spectralFlux,
    onsetDensity = onsetDensity,
    rhythmActivity = rhythmActivity,
    featureConfidence = featureConfidence,
    extractorVersion = extractorVersion,
    metadataFingerprint = metadataFingerprint,
    analyzedAtMs = analyzedAtMs,
    analysisStatus = analysisStatus
)

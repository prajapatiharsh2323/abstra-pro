package dev.abstra.pro.ai.model

/**
 * Compact mathematical representation of how a track actually sounds.
 *
 * Produced by bounded sampling of the audio (a few short decoded windows)
 * and pure-Kotlin DSP. This is a small feature vector — never PCM audio —
 * and is the input for audio similarity, the song graph and transitions.
 *
 * All scalar characteristics are normalized to 0..1 in the same convention
 * as [dev.abstra.pro.ai.model.AudioFeatures]. Vector-valued groups carry
 * their own confidence so missing/unreliable information (short, noisy or
 * percussive audio) never masquerades as dissimilarity.
 */
class AudioSignature(
    val songId: String,

    /** Mean of the 13 MFCC coefficients across analysis frames. */
    val mfccMean: FloatArray,

    /** Std of the 13 MFCC coefficients across analysis frames. */
    val mfccStd: FloatArray,

    /** Normalized 12-dimensional chroma (sums to ~1). */
    val chroma: FloatArray,

    /** Reliability of the chroma vector (0 = unusable, 1 = tonal and clean). */
    val chromaConfidence: Float,

    /** Estimated tempo in BPM; 0 when no reliable estimate exists. */
    val tempoBpm: Float,

    /** Reliability of the tempo estimate (0..1). */
    val tempoConfidence: Float,

    /** Signal intensity (loudness), 0..1. Measures intensity, not emotion. */
    val rmsEnergy: Float,

    /** How much signal intensity varies, 0..1. */
    val dynamicRange: Float,

    /** Spectral brightness, 0..1. */
    val spectralCentroid: Float,

    /** How much the frequency content changes between frames, 0..1. */
    val spectralFlux: Float,

    /** Onset density, 0..1. */
    val onsetDensity: Float,

    /** Combined rhythmic activity, 0..1. */
    val rhythmActivity: Float,

    /** Overall reliability of the whole signature (0..1). */
    val featureConfidence: Float,

    /** Version of the extraction pipeline that produced this signature. */
    val extractorVersion: Int,

    /** Fingerprint of the source metadata/file this signature was built from. */
    val metadataFingerprint: String,

    /** SUCCESS, UNSUPPORTED or FAILED. */
    val analysisStatus: String = STATUS_SUCCESS,

    val analyzedAtMs: Long = 0L
) {

    /** Structural sanity: correct lengths, finite values, normalized ranges. */
    fun isValid(): Boolean {
        if (mfccMean.size != MFCC_COUNT || mfccStd.size != MFCC_COUNT) return false
        if (chroma.size != CHROMA_COUNT) return false
        if (songId.isBlank()) return false
        if (!mfccMean.all { it.isFinite() }) return false
        if (!mfccStd.all { it.isFinite() && it >= 0f }) return false
        if (!chroma.all { it.isFinite() && it >= 0f }) return false
        for (value in listOf(
            chromaConfidence, tempoBpm, tempoConfidence, rmsEnergy, dynamicRange,
            spectralCentroid, spectralFlux, onsetDensity, rhythmActivity, featureConfidence
        )) {
            if (!value.isFinite()) return false
        }
        if (tempoBpm != 0f && (tempoBpm < MIN_TEMPO_BPM * 0.5f || tempoBpm > MAX_TEMPO_BPM * 2f)) return false
        if (featureConfidence < 0f || featureConfidence > 1f) return false
        if (chromaConfidence < 0f || chromaConfidence > 1f) return false
        if (tempoConfidence < 0f || tempoConfidence > 1f) return false
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSignature) return false
        return songId == other.songId &&
                mfccMean.contentEquals(other.mfccMean) &&
                mfccStd.contentEquals(other.mfccStd) &&
                chroma.contentEquals(other.chroma) &&
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
                analysisStatus == other.analysisStatus &&
                analyzedAtMs == other.analyzedAtMs
    }

    override fun hashCode(): Int {
        var result = songId.hashCode()
        result = 31 * result + mfccMean.contentHashCode()
        result = 31 * result + mfccStd.contentHashCode()
        result = 31 * result + chroma.contentHashCode()
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
        result = 31 * result + analysisStatus.hashCode()
        result = 31 * result + analyzedAtMs.hashCode()
        return result
    }

    companion object {
        const val MFCC_COUNT = 13
        const val CHROMA_COUNT = 12

        /** BPM search bounds for tempo estimation. */
        const val MIN_TEMPO_BPM = 60f
        const val MAX_TEMPO_BPM = 200f

        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_UNSUPPORTED = "UNSUPPORTED"
        const val STATUS_FAILED = "FAILED"

        /** Current extraction pipeline version. Bump when features change. */
        const val EXTRACTOR_VERSION = 2
    }
}

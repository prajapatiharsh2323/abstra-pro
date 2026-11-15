package dev.abstra.pro.ai.model

/**
 * Signal-level measurements extracted from a few short decoded windows of a
 * track. These are objective audio measurements — not a trained understanding
 * of human emotion. They are used to refine energy and rhythm activity only.
 */
data class AudioFeatures(
    val songId: String,

    /** Approximate audio intensity (0 = very quiet, 1 = loud). */
    val rmsEnergy: Float,

    /** How much the signal intensity varies (0 = flat, 1 = wide dynamics). */
    val dynamicRange: Float,

    /** Approximate spectral brightness (0 = dark, 1 = bright). */
    val spectralCentroid: Float,

    /** How much the frequency content changes between frames (0..1). */
    val spectralFlux: Float,

    /** Approximate rhythmic activity derived from onset density (0..1). */
    val onsetDensity: Float,

    /** Combined rhythmic activity estimate (0..1). */
    val rhythmActivity: Float,

    /** Confidence based on how much audio was actually decoded (0..1). */
    val confidence: Float,

    val analyzedAtMs: Long
)

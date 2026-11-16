package dev.abstra.pro.ai.audio

import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.data.model.Song
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How similar two songs actually sound.
 *
 * Audio similarity compares feature groups with their own confidences:
 * missing or unreliable information (low chroma confidence, weak tempo)
 * is excluded from the comparison instead of counting as dissimilarity.
 *
 * Metadata similarity is a confidence-aware fallback used when audio
 * signatures are unavailable. Same artist does not mean identical sound,
 * and different artists do not mean different sound.
 */
object AudioSimilarity {

    /** Relative weights of the feature groups (starting configuration). */
    const val WEIGHT_MFCC = 0.40f
    const val WEIGHT_CHROMA = 0.25f
    const val WEIGHT_TEMPO = 0.15f
    const val WEIGHT_RHYTHM = 0.10f
    const val WEIGHT_DYNAMICS = 0.10f

    /** Tempo estimates below this confidence never affect similarity. */
    private const val MIN_TEMPO_CONFIDENCE = 0.25f

    /** Chroma confidence below which chroma is excluded. */
    private const val MIN_CHROMA_CONFIDENCE = 0.20f

    /** Scale for converting MFCC euclidean distance into a 0..1 distance. */
    private const val MFCC_DISTANCE_SCALE = 6f

    /** Scale for the exponential similarity mapping. */
    private const val SIMILARITY_SCALE = 2.2f

    /** A song pair with usable signatures at least reaches this similarity
     *  when nothing is measured (e.g. both silent); pure metadata fallback. */
    const val NEUTRAL_SIMILARITY = 0.5f

    /**
     * Audio similarity between two signatures (0..1).
     * Requires both signatures to be valid; otherwise returns null so the
     * caller can fall back to metadata.
     */
    fun similarity(a: AudioSignature, b: AudioSignature, scaler: RobustScaler? = null): Float? {
        if (!a.isValid() || !b.isValid()) return null
        if (a.analysisStatus != AudioSignature.STATUS_SUCCESS) return null
        if (b.analysisStatus != AudioSignature.STATUS_SUCCESS) return null

        var weightedDistance = 0f
        var totalWeight = 0f

        // MFCC timbre (always measurable for analyzed audio).
        val mfccDistance = mfccDistance(a, b)
        weightedDistance += WEIGHT_MFCC * mfccDistance
        totalWeight += WEIGHT_MFCC

        // Chroma — only when both estimates are reliable.
        if (a.chromaConfidence >= MIN_CHROMA_CONFIDENCE && b.chromaConfidence >= MIN_CHROMA_CONFIDENCE) {
            weightedDistance += WEIGHT_CHROMA * chromaDistance(a.chroma, b.chroma)
            totalWeight += WEIGHT_CHROMA
        }

        // Tempo — only when both estimates are reliable.
        if (a.tempoConfidence >= MIN_TEMPO_CONFIDENCE && b.tempoConfidence >= MIN_TEMPO_CONFIDENCE) {
            weightedDistance += WEIGHT_TEMPO * tempoDistance(a.tempoBpm, b.tempoBpm)
            totalWeight += WEIGHT_TEMPO
        }

        // Rhythm compatibility (onset density + rhythmic activity).
        val aRhythm = if (scaler != null) scaler.normalize("onsetDensity", a.onsetDensity) else a.onsetDensity
        val bRhythm = if (scaler != null) scaler.normalize("onsetDensity", b.onsetDensity) else b.onsetDensity
        val rhythmDistance = (abs(aRhythm - bRhythm) + abs(a.rhythmActivity - b.rhythmActivity)) / 2f
        weightedDistance += WEIGHT_RHYTHM * rhythmDistance
        totalWeight += WEIGHT_RHYTHM

        // Dynamics and spectral profile.
        val dynamicsDistance = dynamicsDistance(a, b, scaler)
        weightedDistance += WEIGHT_DYNAMICS * dynamicsDistance
        totalWeight += WEIGHT_DYNAMICS

        if (totalWeight <= 0f) return null
        val distance = (weightedDistance / totalWeight).coerceIn(0f, 1f)
        return exp(-SIMILARITY_SCALE * distance)
    }

    /** Normalized euclidean distance over MFCC means and standard deviations. */
    fun mfccDistance(a: AudioSignature, b: AudioSignature): Float {
        var sum = 0.0
        var dimensions = 0
        for (i in a.mfccMean.indices) {
            val dm = (a.mfccMean[i] - b.mfccMean[i]).toDouble()
            sum += dm * dm
            dimensions++
        }
        for (i in a.mfccStd.indices) {
            val ds = (a.mfccStd[i] - b.mfccStd[i]).toDouble()
            sum += ds * ds
            dimensions++
        }
        val euclidean = sqrt(sum / dimensions.coerceAtLeast(1).toDouble())
        return (euclidean * MFCC_DISTANCE_SCALE).toFloat().coerceIn(0f, 1f)
    }

    /** Chroma distance as normalized cosine distance, probing circular
     *  pitch-class shifts so transposed equivalents count as compatible. */
    fun chromaDistance(a: FloatArray, b: FloatArray): Float {
        var best = 1f
        for (shift in 0 until 12) {
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                val va = a[i].toDouble()
                val vb = b[(i + shift) % 12].toDouble()
                dot += va * vb
                normA += va * va
                normB += vb * vb
            }
            val denom = sqrt(normA) * sqrt(normB)
            if (denom > 1e-9) {
                val cosine = (dot / denom).coerceIn(-1.0, 1.0)
                best = min(best, ((1.0 - cosine) / 2.0).toFloat())
            }
        }
        return best.coerceIn(0f, 1f)
    }

    /**
     * Tempo compatibility across direct, half-time and double-time
     * relationships. Uses log-ratio distance against the best fit.
     */
    fun tempoDistance(bpmA: Float, bpmB: Float): Float {
        if (bpmA <= 0f || bpmB <= 0f) return NEUTRAL_SIMILARITY
        var best = Float.MAX_VALUE
        for (factor in floatArrayOf(1f, 2f, 0.5f)) {
            val ratio = (bpmA / (bpmB * factor)).coerceAtLeast(1e-6f)
            val logRatio = abs(kotlin.math.ln(ratio))
            best = min(best, logRatio)
        }
        // One octave apart (= factor 2) is compatible; two octaves distant.
        return (best / kotlin.math.ln(3.0).toFloat()).coerceIn(0f, 1f)
    }

    private fun dynamicsDistance(
        a: AudioSignature,
        b: AudioSignature,
        scaler: RobustScaler?
    ): Float {
        fun norm(feature: String, value: Float): Float =
            if (scaler != null) scaler.normalize(feature, value) else value

        var sum = 0f
        var count = 0
        for (feature in RobustScaler.FEATURES) {
            if (feature == "onsetDensity" || feature == "rhythmActivity") continue
            val va = norm(feature, signatureValue(a, feature))
            val vb = norm(feature, signatureValue(b, feature))
            sum += abs(va - vb)
            count++
        }
        return if (count == 0) 0f else (sum / count).coerceIn(0f, 1f)
    }

    private fun signatureValue(signature: AudioSignature, feature: String): Float = when (feature) {
        "rmsEnergy" -> signature.rmsEnergy
        "dynamicRange" -> signature.dynamicRange
        "spectralCentroid" -> signature.spectralCentroid
        "spectralFlux" -> signature.spectralFlux
        else -> 0.5f
    }

    // ═════════════════════════════════════════════════════════════
    // Metadata similarity (fallback when audio is unavailable)
    // ═════════════════════════════════════════════════════════════

    private const val METADATA_ARTIST_WEIGHT = 0.40f
    private const val METADATA_ALBUM_WEIGHT = 0.20f
    private const val METADATA_GENRE_WEIGHT = 0.30f
    private const val METADATA_KEYWORD_WEIGHT = 0.10f

    /**
     * Metadata-only similarity (0..1). Confidence-aware: unknown values
     * contribute a neutral 0.5 with reduced weight rather than dissimilarity.
     */
    fun metadataSimilarity(a: Song, b: Song): Float {
        if (a.id == b.id) return 1f

        val artistScore = when {
            normArtist(a).isBlank() || normArtist(b).isBlank() -> 0.5f to 0.4f
            normArtist(a) == normArtist(b) -> 1f to 1f
            else -> 0.35f to 0.5f // same album often implies shared artist styles
        }

        val albumScore = when {
            normAlbum(a).isBlank() || normAlbum(b).isBlank() -> 0.5f to 0.4f
            normAlbum(a) == normAlbum(b) -> 1f to 0.9f
            else -> 0.4f to 0.6f
        }

        val genresA = dev.abstra.pro.ai.SongProfileAnalyzer.genreFamilies(a.genre)
        val genresB = dev.abstra.pro.ai.SongProfileAnalyzer.genreFamilies(b.genre)
        val genreScore = when {
            genresA.isEmpty() || genresB.isEmpty() -> 0.5f to 0.4f
            genresA.intersect(genresB).isNotEmpty() -> 0.9f to 0.7f
            else -> 0.3f to 0.7f
        }

        val keywordScore = keywordOverlap(a.title, b.title) * 0.5f +
                keywordOverlap(a.album, b.album) * 0.5f

        var weighted = 0f
        var totalWeight = 0f
        val parts = listOf(
            artistScore.first to (METADATA_ARTIST_WEIGHT * artistScore.second),
            albumScore.first to (METADATA_ALBUM_WEIGHT * albumScore.second),
            genreScore.first to (METADATA_GENRE_WEIGHT * genreScore.second),
            keywordScore to (METADATA_KEYWORD_WEIGHT)
        )
        for ((value, weight) in parts) {
            weighted += value * weight
            totalWeight += weight
        }
        return if (totalWeight <= 0f) NEUTRAL_SIMILARITY
        else (weighted / totalWeight).coerceIn(0f, 1f)
    }

    private fun keywordOverlap(a: String?, b: String?): Float {
        val wordsA = significantWords(a)
        val wordsB = significantWords(b)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.5f
        val overlap = wordsA.intersect(wordsB).size
        val smaller = minOf(wordsA.size, wordsB.size).coerceAtLeast(1)
        return (0.3f + 0.7f * overlap / smaller).coerceIn(0f, 1f)
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "of", "in", "on", "at", "to",
        "feat", "ft", "remastered", "version", "edit", "mix", "single"
    )

    private fun significantWords(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        return text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
    }

    private fun normArtist(song: Song): String =
        song.artist.trim().lowercase(Locale.ROOT)

    private fun normAlbum(song: Song): String =
        song.album.trim().lowercase(Locale.ROOT)
}

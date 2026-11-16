package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.AudioFeatures
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.data.model.Song
import java.security.MessageDigest
import java.util.Locale

/**
 * Metadata-first song profile analyzer.
 *
 * Pure Kotlin: no Android APIs, no file IO, no network. Uses a small
 * maintained keyword dictionary and genre-family mapping with
 * confidence-weighted inference. Rules are deliberately conservative —
 * genre is evidence, not an unquestionable classification, and duration is
 * never used to decide that a track is energetic or calm.
 */
object SongProfileAnalyzer {

    const val ANALYSIS_VERSION = 1
    const val PROFILE_SOURCE_METADATA = "METADATA"
    const val PROFILE_SOURCE_BLENDED = "BLENDED"

    /** Metadata evidence is weak on its own; this caps how confident a
     *  metadata-only profile can ever get. */
    private const val CONFIDENCE_CAP = 0.85f

    private const val BASE_ENERGY = 0.5f
    private const val BASE_ACOUSTIC = 0.3f
    private const val BASE_INSTRUMENTAL = 0.1f

    // ─────────────────────────────────────────────────────────────
    // Keyword dictionary (title / album evidence)
    // ─────────────────────────────────────────────────────────────

    private class KeywordRule(
        val keywords: List<String>,
        val energy: Float? = null,
        val energyConfidence: Float = 0f,
        val acoustic: Float? = null,
        val acousticConfidence: Float = 0f,
        val instrumental: Float? = null,
        val instrumentalConfidence: Float = 0f,
        val tags: Set<String> = emptySet()
    )

    private val TITLE_RULES = listOf(
        KeywordRule(
            keywords = listOf("acoustic", "unplugged", "stripped", "piano version", "guitar version", "no drums"),
            energy = 0.25f, energyConfidence = 0.30f,
            acoustic = 0.85f, acousticConfidence = 0.55f,
            tags = setOf("acoustic")
        ),
        KeywordRule(
            keywords = listOf("live", "in concert", "en vivo", "live at", "session", "mtv"),
            energy = 0.58f, energyConfidence = 0.15f,
            tags = setOf("live")
        ),
        KeywordRule(
            keywords = listOf("instrumental", "no vocals", "without vocals", "off vocal", "backing track"),
            instrumental = 0.9f, instrumentalConfidence = 0.55f,
            tags = setOf("instrumental")
        ),
        KeywordRule(
            keywords = listOf("lofi", "lo-fi", "lo fi", "slowed", "slowed and reverb", "reverb", "sleep", "relax", "lullaby", "chill", "calm"),
            energy = 0.25f, energyConfidence = 0.40f,
            acoustic = 0.45f, acousticConfidence = 0.15f,
            tags = setOf("chill", "lo-fi")
        ),
        KeywordRule(
            keywords = listOf("ambient", "drone", "atmospheric", "meditation", "spa"),
            energy = 0.18f, energyConfidence = 0.40f,
            acoustic = 0.5f, acousticConfidence = 0.2f,
            instrumental = 0.6f, instrumentalConfidence = 0.2f,
            tags = setOf("ambient", "focus")
        ),
        KeywordRule(
            keywords = listOf("remix", "extended mix", "club mix", "bootleg", "vip mix", "speed up", "sped up"),
            energy = 0.68f, energyConfidence = 0.30f,
            acoustic = 0.1f, acousticConfidence = 0.20f,
            tags = setOf("remix", "upbeat")
        ),
        KeywordRule(
            keywords = listOf("banger", "hype", "party", "anthem", "pump", "workout", "gym", "training", "energy"),
            energy = 0.8f, energyConfidence = 0.35f,
            tags = setOf("upbeat", "workout")
        ),
        KeywordRule(
            keywords = listOf("night", "nocturne", "midnight", "moonlight", "dream"),
            energy = 0.35f, energyConfidence = 0.20f,
            tags = setOf("night")
        ),
        KeywordRule(
            keywords = listOf("focus", "study", "concentration", "brain"),
            energy = 0.35f, energyConfidence = 0.25f,
            tags = setOf("focus")
        )
    )

    // ─────────────────────────────────────────────────────────────
    // Genre families: (energy, acousticness, instrumental) priors
    // ─────────────────────────────────────────────────────────────

    private class GenrePrior(
        val family: String,
        val energy: Float,
        val energyConfidence: Float,
        val acoustic: Float,
        val acousticConfidence: Float,
        val instrumental: Float,
        val instrumentalConfidence: Float,
        val tags: Set<String> = emptySet()
    )

    private val GENRE_PRIORS: List<GenrePrior> = listOf(
        GenrePrior("metal", 0.92f, 0.40f, 0.12f, 0.30f, 0.05f, 0.20f),
        GenrePrior("hardcore", 0.88f, 0.35f, 0.12f, 0.25f, 0.05f, 0.20f),
        GenrePrior("punk", 0.82f, 0.35f, 0.2f, 0.25f, 0.05f, 0.20f, setOf("upbeat")),
        GenrePrior("grunge", 0.72f, 0.25f, 0.3f, 0.2f, 0.05f, 0.15f),
        GenrePrior("rock", 0.65f, 0.25f, 0.35f, 0.20f, 0.08f, 0.15f),
        GenrePrior("alternative", 0.55f, 0.18f, 0.4f, 0.15f, 0.1f, 0.12f),
        GenrePrior("indie", 0.55f, 0.18f, 0.45f, 0.15f, 0.1f, 0.12f),
        GenrePrior("edm", 0.82f, 0.35f, 0.05f, 0.35f, 0.3f, 0.25f, setOf("upbeat")),
        GenrePrior("dance", 0.8f, 0.35f, 0.05f, 0.35f, 0.3f, 0.25f, setOf("upbeat")),
        GenrePrior("house", 0.8f, 0.32f, 0.05f, 0.35f, 0.35f, 0.25f, setOf("upbeat")),
        GenrePrior("techno", 0.82f, 0.32f, 0.05f, 0.35f, 0.4f, 0.3f),
        GenrePrior("trance", 0.8f, 0.32f, 0.05f, 0.35f, 0.3f, 0.25f),
        GenrePrior("dubstep", 0.85f, 0.35f, 0.05f, 0.35f, 0.3f, 0.25f, setOf("upbeat")),
        GenrePrior("drum and bass", 0.85f, 0.35f, 0.05f, 0.3f, 0.3f, 0.25f, setOf("upbeat")),
        GenrePrior("electronic", 0.65f, 0.25f, 0.08f, 0.30f, 0.3f, 0.25f),
        GenrePrior("electropop", 0.7f, 0.25f, 0.08f, 0.25f, 0.2f, 0.2f),
        GenrePrior("synthpop", 0.68f, 0.25f, 0.15f, 0.25f, 0.2f, 0.2f),
        GenrePrior("hip hop", 0.68f, 0.25f, 0.08f, 0.25f, 0.1f, 0.2f),
        GenrePrior("hip-hop", 0.68f, 0.25f, 0.08f, 0.25f, 0.1f, 0.2f),
        GenrePrior("rap", 0.68f, 0.25f, 0.08f, 0.25f, 0.1f, 0.2f),
        GenrePrior("trap", 0.72f, 0.28f, 0.08f, 0.25f, 0.15f, 0.2f),
        GenrePrior("pop", 0.6f, 0.20f, 0.25f, 0.18f, 0.1f, 0.12f),
        GenrePrior("disco", 0.72f, 0.25f, 0.2f, 0.2f, 0.1f, 0.15f, setOf("upbeat")),
        GenrePrior("r&b", 0.5f, 0.25f, 0.3f, 0.2f, 0.1f, 0.15f),
        GenrePrior("rhythm and blues", 0.5f, 0.25f, 0.3f, 0.2f, 0.1f, 0.15f),
        GenrePrior("soul", 0.5f, 0.25f, 0.35f, 0.2f, 0.1f, 0.15f),
        GenrePrior("funk", 0.65f, 0.22f, 0.3f, 0.2f, 0.15f, 0.15f, setOf("upbeat")),
        GenrePrior("reggaeton", 0.7f, 0.25f, 0.15f, 0.2f, 0.1f, 0.15f),
        GenrePrior("ambient", 0.18f, 0.40f, 0.5f, 0.25f, 0.6f, 0.35f, setOf("ambient")),
        GenrePrior("drone", 0.15f, 0.40f, 0.5f, 0.25f, 0.7f, 0.35f, setOf("ambient")),
        GenrePrior("classical", 0.3f, 0.35f, 0.75f, 0.35f, 0.85f, 0.50f, setOf("instrumental")),
        GenrePrior("orchestral", 0.3f, 0.35f, 0.75f, 0.35f, 0.85f, 0.50f, setOf("instrumental")),
        GenrePrior("symphony", 0.3f, 0.35f, 0.75f, 0.35f, 0.85f, 0.50f, setOf("instrumental")),
        GenrePrior("piano", 0.3f, 0.3f, 0.8f, 0.3f, 0.8f, 0.45f, setOf("instrumental")),
        GenrePrior("instrumental", 0.35f, 0.3f, 0.5f, 0.25f, 0.9f, 0.55f, setOf("instrumental")),
        GenrePrior("acoustic", 0.35f, 0.35f, 0.85f, 0.40f, 0.2f, 0.2f, setOf("acoustic")),
        GenrePrior("unplugged", 0.35f, 0.35f, 0.85f, 0.40f, 0.2f, 0.2f, setOf("acoustic")),
        GenrePrior("folk", 0.4f, 0.28f, 0.8f, 0.35f, 0.15f, 0.2f, setOf("acoustic")),
        GenrePrior("singer-songwriter", 0.4f, 0.25f, 0.75f, 0.3f, 0.15f, 0.2f, setOf("acoustic")),
        GenrePrior("lo-fi", 0.22f, 0.35f, 0.4f, 0.20f, 0.3f, 0.25f, setOf("chill", "lo-fi")),
        GenrePrior("lofi", 0.22f, 0.35f, 0.4f, 0.20f, 0.3f, 0.25f, setOf("chill", "lo-fi")),
        GenrePrior("chill", 0.25f, 0.35f, 0.4f, 0.20f, 0.25f, 0.25f, setOf("chill")),
        GenrePrior("jazz", 0.45f, 0.20f, 0.6f, 0.20f, 0.4f, 0.20f),
        GenrePrior("blues", 0.45f, 0.20f, 0.6f, 0.25f, 0.2f, 0.18f),
        GenrePrior("country", 0.55f, 0.20f, 0.5f, 0.20f, 0.1f, 0.15f),
        GenrePrior("gospel", 0.5f, 0.20f, 0.4f, 0.20f, 0.2f, 0.15f),
        GenrePrior("reggae", 0.55f, 0.20f, 0.3f, 0.18f, 0.1f, 0.15f),
        GenrePrior("soundtrack", 0.4f, 0.25f, 0.4f, 0.20f, 0.65f, 0.30f, setOf("instrumental")),
        GenrePrior("score", 0.4f, 0.25f, 0.4f, 0.20f, 0.65f, 0.30f, setOf("instrumental")),
        GenrePrior("world", 0.5f, 0.15f, 0.4f, 0.15f, 0.3f, 0.15f),
        GenrePrior("traditional", 0.5f, 0.15f, 0.5f, 0.15f, 0.3f, 0.15f)
    )

    /**
     * Broader genre groups used for transition compatibility, so related
     * styles (rock/metal, house/techno, r&b/soul) count as compatible while
     * genuinely distant ones (rock/classical) do not. A family may belong
     * to several groups. Keys must match the GenrePrior family strings.
     */
    private val FAMILY_GROUPS: Map<String, Set<String>> = mapOf(
        "metal" to setOf("rock"),
        "hardcore" to setOf("rock"),
        "punk" to setOf("rock"),
        "grunge" to setOf("rock"),
        "rock" to setOf("rock"),
        "alternative" to setOf("rock"),
        "indie" to setOf("rock", "pop"),
        "edm" to setOf("electronic"),
        "dance" to setOf("electronic"),
        "house" to setOf("electronic"),
        "techno" to setOf("electronic"),
        "trance" to setOf("electronic"),
        "dubstep" to setOf("electronic"),
        "drum and bass" to setOf("electronic"),
        "electronic" to setOf("electronic"),
        "electropop" to setOf("electronic", "pop"),
        "synthpop" to setOf("electronic", "pop"),
        "hip hop" to setOf("hip-hop"),
        "hip-hop" to setOf("hip-hop"),
        "rap" to setOf("hip-hop"),
        "trap" to setOf("hip-hop", "electronic"),
        "pop" to setOf("pop"),
        "disco" to setOf("pop", "electronic"),
        "r&b" to setOf("r&b"),
        "rhythm and blues" to setOf("r&b"),
        "soul" to setOf("r&b"),
        "funk" to setOf("r&b"),
        "reggaeton" to setOf("latin"),
        "ambient" to setOf("ambient", "electronic"),
        "drone" to setOf("ambient"),
        "classical" to setOf("classical"),
        "orchestral" to setOf("classical"),
        "symphony" to setOf("classical"),
        "piano" to setOf("classical"),
        "instrumental" to setOf("classical"),
        "acoustic" to setOf("acoustic"),
        "unplugged" to setOf("acoustic"),
        "folk" to setOf("acoustic"),
        "singer-songwriter" to setOf("acoustic"),
        "lo-fi" to setOf("chill", "electronic"),
        "lofi" to setOf("chill", "electronic"),
        "chill" to setOf("chill"),
        "jazz" to setOf("jazz"),
        "blues" to setOf("jazz", "r&b"),
        "country" to setOf("acoustic", "folk-country"),
        "gospel" to setOf("r&b"),
        "reggae" to setOf("reggae"),
        "soundtrack" to setOf("classical"),
        "score" to setOf("classical"),
        "world" to setOf("world"),
        "traditional" to setOf("world")
    )

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /** Stable hash of the effective metadata a profile was built from. */
    fun fingerprint(song: Song): String {
        val payload = listOf(
            song.title.lowercase(Locale.ROOT).trim(),
            song.artist.lowercase(Locale.ROOT).trim(),
            song.album.lowercase(Locale.ROOT).trim(),
            song.genre?.lowercase(Locale.ROOT)?.trim().orEmpty(),
            song.durationMs.toString(),
            song.filePath.lowercase(Locale.ROOT).trim(),
            song.fileSize.toString(),
            song.lastModified.toString()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun analyze(song: Song): SongProfile {
        val fingerprint = fingerprint(song)

        val title = song.title.lowercase(Locale.ROOT)
        val album = song.album.lowercase(Locale.ROOT)
        val artist = song.artist.lowercase(Locale.ROOT)

        val energyEvidence = EvidenceAggregator()
        val acousticEvidence = EvidenceAggregator()
        val instrumentalEvidence = EvidenceAggregator()
        val tags = mutableSetOf<String>()

        // Title keyword evidence — strongest metadata signal.
        for (rule in TITLE_RULES) {
            if (rule.keywords.any { title.contains(it) }) {
                rule.energy?.let { energyEvidence.add(it, rule.energyConfidence) }
                rule.acoustic?.let { acousticEvidence.add(it, rule.acousticConfidence) }
                rule.instrumental?.let { instrumentalEvidence.add(it, rule.instrumentalConfidence) }
                tags += rule.tags
            } else if (rule.keywords.any { album.contains(it) }) {
                // Album evidence is somewhat weaker than direct title evidence.
                rule.energy?.let { energyEvidence.add(it, rule.energyConfidence * 0.7f) }
                rule.acoustic?.let { acousticEvidence.add(it, rule.acousticConfidence * 0.7f) }
                rule.instrumental?.let { instrumentalEvidence.add(it, rule.instrumentalConfidence * 0.7f) }
                tags += rule.tags
            }
        }

        // Artist name is rarely informative; only the album artist variants of
        // the acoustic/live patterns apply. Kept intentionally narrow.
        if (artist.contains("orchestra") || artist.contains("ensemble")) {
            instrumentalEvidence.add(0.7f, 0.25f)
            tags += "instrumental"
        }

        // Genre family evidence — moderate.
        for (genre in splitGenres(song.genre).take(3)) {
            val prior = genrePrior(genre) ?: continue
            energyEvidence.add(prior.energy, prior.energyConfidence)
            acousticEvidence.add(prior.acoustic, prior.acousticConfidence)
            instrumentalEvidence.add(prior.instrumental, prior.instrumentalConfidence)
            tags += prior.tags
        }

        val energy = energyEvidence.resolve(BASE_ENERGY)
        val acoustic = acousticEvidence.resolve(BASE_ACOUSTIC)
        val instrumental = instrumentalEvidence.resolve(BASE_INSTRUMENTAL)

        // Derived tags from the final estimates.
        if (energy.value >= 0.72f) tags += "upbeat"
        if (energy.value <= 0.35f) tags += "chill"
        if (instrumental.value >= 0.6f) tags += "instrumental"

        return SongProfile(
            songId = song.id,
            energy = energy.value,
            acousticness = acoustic.value,
            instrumentalLikelihood = instrumental.value,
            rhythmActivity = null,
            tags = tags,
            energyConfidence = energy.confidence,
            acousticConfidence = acoustic.confidence,
            instrumentalConfidence = instrumental.confidence,
            profileSource = PROFILE_SOURCE_METADATA,
            metadataFingerprint = fingerprint,
            analysisVersion = ANALYSIS_VERSION
        )
    }

    /**
     * Confidence-weighted blending of a metadata estimate with an audio
     * measurement. Starting policy: audio contributes 65% of the weight when
     * a measurement is available, because it measures the actual signal.
     */
    fun combineFeatures(
        metadataValue: Float,
        metadataConfidence: Float,
        audioValue: Float,
        audioConfidence: Float
    ): Float {
        val metadataWeight = metadataConfidence.coerceIn(0f, 1f) * 0.35f
        val audioWeight = audioConfidence.coerceIn(0f, 1f) * 0.65f
        val totalWeight = metadataWeight + audioWeight
        if (totalWeight <= 0f) return metadataValue
        return (metadataValue * metadataWeight + audioValue * audioWeight) / totalWeight
    }

    /**
     * Refines a metadata profile with optional audio measurements. Only the
     * characteristics audio analysis actually measures are touched:
     * energy and rhythm activity.
     */
    fun blendFeatures(metadata: SongProfile, audio: AudioFeatures): SongProfile {
        val audioConfidence = audio.confidence.coerceIn(0f, 1f)
        if (audioConfidence <= 0f) return metadata

        val blendedEnergy = combineFeatures(
            metadataValue = metadata.energy,
            metadataConfidence = metadata.energyConfidence,
            audioValue = audio.rmsEnergy,
            audioConfidence = audioConfidence
        )

        val newEnergyConfidence = min(1f, metadata.energyConfidence + audioConfidence * 0.5f)

        return metadata.copy(
            energy = blendedEnergy.coerceIn(0f, 1f),
            rhythmActivity = audio.rhythmActivity.coerceIn(0f, 1f),
            energyConfidence = newEnergyConfidence,
            profileSource = PROFILE_SOURCE_BLENDED
        )
    }

    /**
     * Refines a metadata profile with a persisted [AudioSignature]. More
     * dimensions are touched than with raw [AudioFeatures]: brightness and
     * spectral movement inform the acoustic/instrumental picture only when
     * the measurements are confident enough.
     */
    fun blendFeatures(metadata: SongProfile, signature: dev.abstra.pro.ai.model.AudioSignature): SongProfile {
        val audioConfidence = signature.featureConfidence.coerceIn(0f, 1f)
        if (audioConfidence <= 0f) return metadata

        val blendedEnergy = combineFeatures(
            metadataValue = metadata.energy,
            metadataConfidence = metadata.energyConfidence,
            audioValue = signature.rmsEnergy,
            audioConfidence = audioConfidence
        )

        var acoustic = metadata.acousticness
        var acousticConfidence = metadata.acousticConfidence
        if (signature.spectralCentroid > 0f) {
            // Bright, synthetic spectra suggest produced/non-acoustic material;
            // dark, sparse spectra lean acoustic. A weak, confidence-damped push.
            val brightnessPush = (0.5f - signature.spectralCentroid) * 0.35f
            val blendedAcoustic = (acoustic + brightnessPush * audioConfidence).coerceIn(0f, 1f)
            acoustic = combineFeatures(
                metadataValue = acoustic,
                metadataConfidence = acousticConfidence,
                audioValue = blendedAcoustic,
                audioConfidence = audioConfidence * 0.5f
            )
            acousticConfidence = min(1f, acousticConfidence + audioConfidence * 0.25f)
        }

        val newEnergyConfidence = min(1f, metadata.energyConfidence + audioConfidence * 0.5f)

        return metadata.copy(
            energy = blendedEnergy.coerceIn(0f, 1f),
            acousticness = acoustic,
            acousticConfidence = acousticConfidence,
            rhythmActivity = signature.rhythmActivity.coerceIn(0f, 1f),
            energyConfidence = newEnergyConfidence,
            profileSource = PROFILE_SOURCE_BLENDED
        )
    }

    /**
     * Are two genres related enough to count as a smooth transition?
     * Returns 1.0 (same family or broader group), 0.6 (no data), or
     * 0.4 (different families).
     */
    fun genreCompatibility(genreA: String?, genreB: String?): Float {
        val a = genreFamilies(genreA)
        val b = genreFamilies(genreB)
        if (a.isEmpty() || b.isEmpty()) return 0.6f
        return if (a.intersect(b).isNotEmpty()) 1f else 0.4f
    }

    fun genreFamilies(genre: String?): Set<String> {
        if (genre.isNullOrBlank()) return emptySet()
        val families = mutableSetOf<String>()
        for (g in splitGenres(genre)) {
            val prior = genrePrior(g) ?: continue
            families += prior.family
            families += FAMILY_GROUPS[prior.family].orEmpty()
        }
        return families
    }

    // ─────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────

    private class Evidence {
        var weightedSum = 0f
        var totalWeight = 0f
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE

        fun add(value: Float, confidence: Float) {
            val w = confidence.coerceIn(0f, 1f)
            if (w <= 0f) return
            weightedSum += value * w
            totalWeight += w
            if (value < min) min = value
            if (value > max) max = value
        }
    }

    private class EvidenceAggregator {
        private val evidence = Evidence()

        fun add(value: Float, confidence: Float) = evidence.add(value, confidence)

        fun resolve(base: Float): Resolved {
            val e = evidence
            if (e.totalWeight <= 0f) return Resolved(base.coerceIn(0.05f, 0.98f), 0f)
            val spread = e.max - e.min
            // Conflicting evidence lowers confidence instead of inventing a
            // classification, but only when the spread is meaningful. The
            // penalty must actually reduce confidence below a single clean
            // signal of the same total weight (hence 0.5, not higher).
            val conflictPenalty = if (spread > 0.5f) 0.5f else 1f
            val confidence = min(CONFIDENCE_CAP, e.totalWeight) * conflictPenalty
            val value = e.weightedSum / e.totalWeight
            return Resolved(value.coerceIn(0.05f, 0.98f), confidence)
        }
    }

    private data class Resolved(val value: Float, val confidence: Float)

    private fun splitGenres(genre: String?): List<String> {
        if (genre.isNullOrBlank()) return emptyList()
        return genre.split(',', ';', '|', '/', '&')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    private fun genrePrior(genre: String): GenrePrior? {
        for (prior in GENRE_PRIORS) {
            if (genre == prior.family || genre.contains(prior.family)) return prior
        }
        return null
    }

    private fun min(a: Float, b: Float): Float = if (a < b) a else b
}

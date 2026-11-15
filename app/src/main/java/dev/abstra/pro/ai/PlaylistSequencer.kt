package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.RecommendationReason
import dev.abstra.pro.ai.model.SongProfile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

data class SequenceInput(
    val scored: List<ScoredSong>,
    val preset: MixPreset,
    val request: PlaylistRequest,
    val nowMs: Long,
    /** Markov transition counts: fromSongId -> toSongId -> count. */
    val transitionCounts: Map<String, Map<String, Int>> = emptyMap(),
    /** Total outgoing transitions per song (for normalizing counts). */
    val outgoingTotals: Map<String, Int> = emptyMap(),
    /** Recently played song ids the mix should avoid when alternatives exist. */
    val recentlyPlayedIds: Set<String> = emptySet()
)

/**
 * Turns individually good songs into a good listening experience:
 * diversity, transition quality and mood flow — without turning the player
 * into an optimization engine.
 */
object PlaylistSequencer {

    const val NEXT_QUALITY_WEIGHT = 0.70f
    const val NEXT_TRANSITION_WEIGHT = 0.20f
    const val NEXT_DIVERSITY_WEIGHT = 0.10f

    private const val SHORTLIST_MIN = 120
    private const val SHORTLIST_MAX = 200
    private const val ARTIST_WINDOW = 5
    private const val ARTIST_WINDOW_MAX = 2
    private const val ALBUM_WINDOW = 3
    private const val ALBUM_WINDOW_MAX = 2
    private const val SMOOTH_TRANSITION_THRESHOLD = 0.7f

    fun sequence(input: SequenceInput): List<dev.abstra.pro.ai.model.RecommendedTrack> {
        val scored = input.scored
        if (scored.isEmpty()) return emptyList()

        var eligible = scored
        if (input.request.excludeRecentlyPlayed && input.recentlyPlayedIds.isNotEmpty()) {
            val filtered = eligible.filter { it.base.song.id !in input.recentlyPlayedIds }
            // Relax when the library is small — exclusion must not starve the mix.
            if (filtered.size >= 10) eligible = filtered
        }

        val shortlist = eligible
            .sortedWith(compareByDescending<ScoredSong> { it.score }.thenBy { it.base.song.id })
            .take(min(SHORTLIST_MAX, max(SHORTLIST_MIN, eligible.size)))
            .toMutableList()

        // Seed handling
        val seed = input.request.seedSongId?.let { id -> shortlist.firstOrNull { it.base.song.id == id } }
        if (seed != null) shortlist.remove(seed)

        val targetMs = input.request.targetDurationMs.coerceAtLeast(5L * 60L * 1000L)
        val targetCount = estimateTargetCount(shortlist, targetMs)
        val random = Random(input.request.refreshSeed)

        val selected = mutableListOf<Pair<ScoredSong, Float>>() // song + transition score
        var totalDurationMs = 0L
        var discoveryCount = 0

        fun isDiscovery(s: ScoredSong): Boolean = s.base.playCount <= 1

        val first = seed ?: shortlist.maxByOrNull { it.score } ?: return emptyList()
        if (seed == null) shortlist.remove(first)
        selected += first to 1f
        totalDurationMs += durationMs(first)
        if (isDiscovery(first)) discoveryCount++

        val artistWindow = ArrayDeque(listOf(artistKey(first.base.song)))
        val albumWindow = ArrayDeque(listOf(albumKey(first.base.song)))

        while (totalDurationMs < targetMs &&
            shortlist.isNotEmpty() &&
            selected.size < input.preset.maxTracks
        ) {
            val progress = (totalDurationMs.toFloat() / targetMs).coerceIn(0f, 1f)
            val flowEnergy = input.preset.energyAt(progress)
            val current = selected.last().first
            val currentArtist = artistKey(current.base.song)
            val currentAlbum = albumKey(current.base.song)
            val recentArtists = artistWindow.groupingBy { it }.eachCount()
            val recentAlbums = albumWindow.groupingBy { it }.eachCount()

            val remainingSlots = (targetCount - selected.size).coerceAtLeast(1)
            val discoveryNeeded = ceil(targetCount * input.request.discoveryRatio).toInt()
            val mustDiscover = discoveryCount < discoveryNeeded &&
                    remainingSlots <= (discoveryNeeded - discoveryCount)

            val relaxed = shortlist.size < 12

            var best: ScoredSong? = null
            var bestScore = -1f
            var bestTransition = 0f

            for (candidate in shortlist) {
                val candidateArtist = artistKey(candidate.base.song)
                val candidateAlbum = albumKey(candidate.base.song)

                if (!relaxed) {
                    // No same artist consecutively when alternatives exist.
                    if (candidateArtist == currentArtist) continue
                    // Limit an artist to ~2 tracks in a rolling group of five.
                    if ((recentArtists[candidateArtist] ?: 0) >= ARTIST_WINDOW_MAX) continue
                    // Avoid repeatedly selecting songs from the same album.
                    if (candidateAlbum != currentAlbum &&
                        (recentAlbums[candidateAlbum] ?: 0) >= ALBUM_WINDOW_MAX
                    ) continue
                }
                if (mustDiscover && !isDiscovery(candidate)) continue

                val transition = transitionScore(
                    currentProfile = current.base.profile,
                    candidateProfile = candidate.base.profile,
                    flowEnergy = flowEnergy,
                    currentGenre = current.base.song.genre,
                    candidateGenre = candidate.base.song.genre,
                    input = input
                )
                val diversity = diversityScore(
                    candidateArtist, candidateAlbum,
                    currentArtist, currentAlbum,
                    recentArtists, recentAlbums
                )

                var nextScore = NEXT_QUALITY_WEIGHT * candidate.score +
                        NEXT_TRANSITION_WEIGHT * transition +
                        NEXT_DIVERSITY_WEIGHT * diversity
                // Deterministic micro-jitter for seeded variety between refreshes.
                nextScore += random.nextFloat() * 0.01f

                if (nextScore > bestScore) {
                    bestScore = nextScore
                    best = candidate
                    bestTransition = transition
                }
            }

            val chosen = best ?: break
            shortlist.remove(chosen)
            selected += chosen to bestTransition
            totalDurationMs += durationMs(chosen)
            artistWindow.addLast(artistKey(chosen.base.song))
            if (artistWindow.size > ARTIST_WINDOW) artistWindow.removeFirst()
            albumWindow.addLast(albumKey(chosen.base.song))
            if (albumWindow.size > ALBUM_WINDOW) albumWindow.removeFirst()
            if (isDiscovery(chosen)) discoveryCount++
        }

        return selected.map { (song, transition) ->
            val reasons = song.reasons.toMutableSet()
            if (transition >= SMOOTH_TRANSITION_THRESHOLD) {
                reasons += RecommendationReason.SMOOTH_TRANSITION
            }
            dev.abstra.pro.ai.model.RecommendedTrack(
                songId = song.base.song.id,
                score = song.score,
                reasons = reasons
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun durationMs(song: ScoredSong): Long = song.base.song.durationMs.coerceAtLeast(1000L)

    private fun artistKey(song: dev.abstra.pro.data.model.Song): String =
        song.artist.trim().lowercase()

    private fun albumKey(song: dev.abstra.pro.data.model.Song): String =
        song.album.trim().lowercase()

    private fun estimateTargetCount(shortlist: List<ScoredSong>, targetMs: Long): Int {
        if (shortlist.isEmpty()) return 20
        val avg = shortlist.take(30)
            .map { durationMs(it) }
            .average().toFloat().coerceAtLeast(60_000f)
        return (targetMs / avg).roundToInt().coerceIn(6, 60)
    }

    private fun transitionScore(
        currentProfile: SongProfile,
        candidateProfile: SongProfile,
        flowEnergy: Float?,
        currentGenre: String?,
        candidateGenre: String?,
        input: SequenceInput
    ): Float {
        val targetEnergy = flowEnergy ?: currentProfile.energy
        val energyFit = if (candidateProfile.energyConfidence > 0f) {
            1f - min(1f, abs(candidateProfile.energy - targetEnergy) / 0.35f)
        } else {
            0.6f
        }
        val acousticFit = if (candidateProfile.acousticConfidence > 0f && currentProfile.acousticConfidence > 0f) {
            1f - min(1f, abs(candidateProfile.acousticness - currentProfile.acousticness))
        } else {
            0.6f
        }
        val genreFit = SongProfileAnalyzer.genreCompatibility(currentGenre, candidateGenre)

        var value = 0.5f * energyFit + 0.25f * genreFit + 0.15f * acousticFit
        var weight = 0.9f

        // Markov transitions are a small optional bonus, never overpowering
        // mood matching or user preference.
        val count = input.transitionCounts[currentProfile.songId]?.get(candidateProfile.songId)
        if (count != null && count > 0) {
            val total = (input.outgoingTotals[currentProfile.songId] ?: 0).coerceAtLeast(1)
            val normalized = min(1f, count.toFloat() / total * 3f)
            value += 0.1f * normalized
            weight += 0.1f
        }

        return (value / weight).coerceIn(0f, 1f)
    }

    private fun diversityScore(
        candidateArtist: String,
        candidateAlbum: String,
        currentArtist: String,
        currentAlbum: String,
        recentArtists: Map<String, Int>,
        recentAlbums: Map<String, Int>
    ): Float {
        var diversity = 1f
        if (candidateArtist == currentArtist) diversity -= 0.5f
        if ((recentArtists[candidateArtist] ?: 0) >= 2) diversity -= 0.3f
        if (candidateAlbum == currentAlbum) diversity -= 0.2f
        if ((recentAlbums[candidateAlbum] ?: 0) >= 2) diversity -= 0.15f
        return diversity.coerceIn(0f, 1f)
    }
}

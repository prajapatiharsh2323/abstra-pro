package dev.abstra.pro.ai.recommendation

/**
 * Reciprocal Rank Fusion: combines ranked candidate lists from independent
 * retrieval sources without mixing unrelated score scales.
 *
 * RRF(s) = sum_i w_i / (60 + rank_i(s))
 */
object RankFusion {

    /** Standard RRF constant. */
    const val K = 60

    data class Source(val name: String, val weight: Float, val rankedIds: List<String>)

    /**
     * Fuses ranked lists into a map of song id -> fused score, and records
     * which sources contributed at which rank for diagnostics.
     */
    fun fuse(sources: List<Source>): Map<String, Float> {
        val scores = HashMap<String, Float>()
        for (source in sources) {
            for ((index, id) in source.rankedIds.withIndex()) {
                val contribution = source.weight / (K + index + 1)
                scores[id] = (scores[id] ?: 0f) + contribution
            }
        }
        return scores
    }
}

package dev.abstra.pro.ai.graph

import kotlin.math.abs

/**
 * Personalized PageRank over the sparse song similarity graph.
 *
 * Positive and negative preference are propagated separately from their own
 * seed sets and combined conservatively, so weak negative history can never
 * over-penalize a track. Fully deterministic.
 */
object PersonalizedPageRank {

    /** Restart (teleport) probability. */
    const val ALPHA = 0.22f

    /** Maximum iterations. */
    const val MAX_ITERATIONS = 20

    /** L1 convergence threshold for early stopping. */
    private const val CONVERGENCE_EPSILON = 1e-6f

    /** Negative propagation is dampened relative to positive. */
    const val NEGATIVE_PENALTY = 0.6f

    /**
     * Runs one personalized PageRank.
     *
     * @param graph normalized outgoing edges: nodeId -> (neighbor -> weight)
     * @param seeds seed weights (need not be normalized)
     */
    fun run(
        graph: Map<String, Map<String, Float>>,
        seeds: Map<String, Float>
    ): Map<String, Float> {
        if (graph.isEmpty()) return emptyMap()
        val seedTotal = seeds.values.sum()
        if (seedTotal <= 0f) return emptyMap()

        val seedVector = HashMap<String, Float>(seeds.size)
        for ((id, weight) in seeds) {
            seedVector[id] = (seedVector[id] ?: 0f) + weight / seedTotal
        }
        if (seedVector.isEmpty()) return emptyMap()

        // Nodes that can hold mass: graph sources, every referenced target
        // (sink songs must still be able to receive recommendations), and
        // seed nodes (seeds may be isolated).
        val nodes = HashSet<String>(graph.keys)
        for (targets in graph.values) nodes.addAll(targets.keys)
        nodes.addAll(seedVector.keys)
        var p = HashMap<String, Float>(seedVector)

        repeat(MAX_ITERATIONS) {
            val next = HashMap<String, Float>(nodes.size)
            for ((id, weight) in seedVector) {
                next[id] = (next[id] ?: 0f) + ALPHA * weight
            }
            for ((from, targets) in graph) {
                val mass = p[from] ?: continue
                if (mass <= 0f) continue
                val propagated = (1f - ALPHA) * mass
                for ((to, weight) in targets) {
                    if (to !in nodes) continue
                    next[to] = (next[to] ?: 0f) + propagated * weight
                }
            }
            var l1 = 0.0
            for (id in nodes) {
                l1 += abs((next[id] ?: 0f) - (p[id] ?: 0f)).toDouble()
            }
            p = next
            if (l1 < CONVERGENCE_EPSILON) return@repeat
        }

        // Normalize so results are comparable across runs and graphs.
        val total = p.values.sum()
        if (total <= 0f) return emptyMap()
        return p.mapValues { (_, v) -> v / total }
    }

    /**
     * Combined personalized score: positive PageRank minus a damped
     * negative PageRank. Negative seeds must come only from reliable
     * negative evidence.
     */
    fun personalizedScores(
        graph: Map<String, Map<String, Float>>,
        positiveSeeds: Map<String, Float>,
        negativeSeeds: Map<String, Float>
    ): Map<String, Float> {
        val positive = run(graph, positiveSeeds)
        val negative = if (negativeSeeds.isNotEmpty()) run(graph, negativeSeeds) else emptyMap()
        if (negative.isEmpty()) return positive
        val scores = HashMap<String, Float>(positive.size)
        for ((id, value) in positive) {
            scores[id] = (value - NEGATIVE_PENALTY * (negative[id] ?: 0f)).coerceAtLeast(0f)
        }
        // Include songs that only appear in the negative result.
        for ((id, value) in negative) {
            if (id !in scores) scores[id] = -NEGATIVE_PENALTY * value
        }
        return scores
    }
}

package dev.abstra.pro.ai.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personalized PageRank: seed propagation over the sparse song graph,
 * deterministic output, and damped negative preference.
 */
class PersonalizedPageRankTest {

    @Test
    fun `empty inputs produce empty results`() {
        assertTrue(PersonalizedPageRank.run(emptyMap(), mapOf("A" to 1f)).isEmpty())
        assertTrue(PersonalizedPageRank.run(mapOf("A" to mapOf("B" to 1f)), emptyMap()).isEmpty())
        assertTrue(PersonalizedPageRank.run(mapOf("A" to mapOf("B" to 1f)), mapOf("A" to 0f)).isEmpty())
    }

    @Test
    fun `mass flows from seeds along edges and normalizes to one`() {
        val graph = mapOf(
            "A" to mapOf("B" to 1f),
            "B" to mapOf("C" to 1f)
        )

        val result = PersonalizedPageRank.run(graph, mapOf("A" to 1f))

        assertEquals(1f, result.values.sum(), 0.01f)
        assertTrue("A ${result["A"]} vs B ${result["B"]}", result.getValue("A") > result.getValue("B"))
        assertTrue("B ${result["B"]} vs C ${result["C"]}", result.getValue("B") > result.getValue("C"))
        assertTrue(result.getValue("C") > 0f)
    }

    @Test
    fun `seeds may be isolated nodes`() {
        val graph = mapOf("B" to emptyMap<String, Float>())

        val result = PersonalizedPageRank.run(graph, mapOf("A" to 1f))

        // All remaining mass stays on the seed.
        assertEquals(1f, result.getValue("A"), 0.01f)
    }

    @Test
    fun `seed weights are proportional`() {
        val graph = mapOf(
            "A" to mapOf("X" to 1f),
            "B" to mapOf("Y" to 1f)
        )

        val result = PersonalizedPageRank.run(graph, mapOf("A" to 3f, "B" to 1f))

        assertTrue(result.getValue("X") > result.getValue("Y"))
        // But both sides receive mass.
        assertTrue(result.getValue("Y") > 0f)
    }

    @Test
    fun `unreachable graph nodes receive no mass`() {
        val graph = mapOf(
            "A" to mapOf("B" to 1f),
            "orphan" to mapOf("B" to 1f)
        )

        val result = PersonalizedPageRank.run(graph, mapOf("A" to 1f))

        assertEquals(0f, result["orphan"] ?: 0f, 0f)
    }

    @Test
    fun `deterministic for identical inputs`() {
        val graph = mapOf(
            "A" to mapOf("B" to 1f, "C" to 0.5f),
            "B" to mapOf("C" to 1f),
            "C" to mapOf("A" to 1f)
        )

        assertEquals(
            PersonalizedPageRank.run(graph, mapOf("A" to 1f, "B" to 0.5f)),
            PersonalizedPageRank.run(graph, mapOf("A" to 1f, "B" to 0.5f))
        )
    }

    @Test
    fun `negative seeds are damped not dominant`() {
        val graph = mapOf(
            "A" to mapOf("B" to 1f),
            "B" to mapOf("A" to 1f)
        )

        val positiveOnly = PersonalizedPageRank.personalizedScores(graph, mapOf("A" to 1f), emptyMap())
        val withNegative = PersonalizedPageRank.personalizedScores(graph, mapOf("A" to 1f), mapOf("B" to 1f))

        // The penalized node must score lower than with positive evidence only.
        assertTrue(withNegative.getValue("B") < positiveOnly.getValue("B"))
        // And damping is capped: negative never fully erases the base score.
        assertTrue(withNegative.getValue("A") > 0f)
    }

    @Test
    fun `negative-only nodes can go negative`() {
        // Separate component: the negative seed's branch never receives
        // positive mass, so its nodes end up penalized below zero.
        val graph = mapOf(
            "A" to mapOf("B" to 1f),
            "N" to mapOf("hated" to 1f)
        )

        val scores = PersonalizedPageRank.personalizedScores(graph, mapOf("A" to 1f), mapOf("N" to 1f))

        assertTrue(scores.getValue("hated") < 0f)
        // The positive branch is untouched by the negative seed.
        assertTrue(scores.getValue("A") > 0.5f)
    }
}

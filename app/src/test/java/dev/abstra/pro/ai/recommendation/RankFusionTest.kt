package dev.abstra.pro.ai.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reciprocal Rank Fusion: scale-free combination of ranked sources. */
class RankFusionTest {

    @Test
    fun `standard constant and formula are used`() {
        val scores = RankFusion.fuse(listOf(RankFusion.Source("a", 1f, listOf("x", "y"))))

        assertEquals(1f / (RankFusion.K + 1), scores.getValue("x"), 1e-6f)
        assertEquals(1f / (RankFusion.K + 2), scores.getValue("y"), 1e-6f)
    }

    @Test
    fun `appearing in multiple sources beats a single source`() {
        val scores = RankFusion.fuse(
            listOf(
                RankFusion.Source("a", 1f, listOf("x", "y")),
                RankFusion.Source("b", 1f, listOf("y", "z"))
            )
        )

        // y is ranked 1st in b and 2nd in a; x is 1st in a only.
        assertTrue(scores.getValue("y") > scores.getValue("x"))
        assertTrue(scores.getValue("x") > scores.getValue("z"))
    }

    @Test
    fun `source weights scale contributions`() {
        val light = RankFusion.fuse(listOf(RankFusion.Source("a", 1f, listOf("x"))))
        val heavy = RankFusion.fuse(listOf(RankFusion.Source("a", 2f, listOf("x"))))

        assertEquals(2f, heavy.getValue("x") / light.getValue("x"), 1e-4f)
    }

    @Test
    fun `rank position is respected within a source`() {
        val scores = RankFusion.fuse(listOf(RankFusion.Source("a", 1f, listOf("first", "second", "third"))))

        assertTrue(scores.getValue("first") > scores.getValue("second"))
        assertTrue(scores.getValue("second") > scores.getValue("third"))
    }

    @Test
    fun `empty sources produce an empty map`() {
        assertTrue(RankFusion.fuse(emptyList()).isEmpty())
        assertTrue(RankFusion.fuse(listOf(RankFusion.Source("a", 1f, emptyList()))).isEmpty())
    }
}

package dev.abstra.pro.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cache invalidation semantics (spec §14 / §18). */
class MixCachePolicyTest {

    private fun freshCache() = MixCacheData(
        dateKey = "2026-11-16",
        libraryFingerprint = "fp",
        metadataFingerprint = "mfp",
        graphVersion = 1,
        algorithmVersion = 2,
        presetVersion = 3,
        tasteRevision = 7L,
        contextBucket = "MORNING",
        baseSeed = 42L
    )

    private fun MixCacheData.isFreshFor(
        dateKey: String = "2026-11-16",
        libraryFingerprint: String = "fp",
        metadataFingerprint: String = "mfp",
        graphVersion: Int = 1,
        algorithmVersion: Int = 2,
        presetVersion: Int = 3,
        tasteRevision: Long = 7L,
        contextBucket: String = "MORNING"
    ) = isFresh(
        dateKey, libraryFingerprint, metadataFingerprint,
        graphVersion, algorithmVersion, presetVersion, tasteRevision, contextBucket
    )

    @Test
    fun `a fully matching cache is fresh`() {
        assertTrue(freshCache().isFreshFor())
    }

    @Test
    fun `a new day invalidates the cache`() {
        assertFalse(freshCache().isFreshFor(dateKey = "2026-11-17"))
    }

    @Test
    fun `library changes invalidate the cache`() {
        assertFalse(freshCache().isFreshFor(libraryFingerprint = "fp2"))
    }

    @Test
    fun `metadata changes invalidate the cache`() {
        assertFalse(freshCache().isFreshFor(metadataFingerprint = "mfp2"))
    }

    @Test
    fun `audio graph version changes invalidate the cache`() {
        assertFalse(freshCache().isFreshFor(graphVersion = 2))
    }

    @Test
    fun `algorithm or preset version changes invalidate the cache`() {
        assertFalse(freshCache().isFreshFor(algorithmVersion = 3))
        assertFalse(freshCache().isFreshFor(presetVersion = 4))
    }

    @Test
    fun `meaningful taste feedback invalidates the cache`() {
        assertFalse(freshCache().isFreshFor(tasteRevision = 8L))
    }

    @Test
    fun `a changed time-of-day context bucket invalidates the cache`() {
        assertFalse(freshCache().isFreshFor(contextBucket = "NIGHT"))
    }
}

package dev.abstra.pro.ai.perf

import dev.abstra.pro.data.db.dao.SongNeighborDao
import dev.abstra.pro.data.db.entities.SongNeighborEntity

/** In-memory no-op DAO for benchmarking the pure graph algorithms. */
object NoopNeighborDao : SongNeighborDao {
    override suspend fun getAllSync(): List<SongNeighborEntity> = emptyList()
    override suspend fun getNeighborsFor(songId: String): List<SongNeighborEntity> = emptyList()
    override suspend fun getReverseNeighborsFor(songId: String): List<SongNeighborEntity> = emptyList()
    override suspend fun countAll(): Int = 0
    override suspend fun insertAll(edges: List<SongNeighborEntity>) = Unit
    override suspend fun deleteEdgesFor(songId: String, edgeType: String) = Unit
    override suspend fun deleteReverseEdgesFor(songId: String) = Unit
    override suspend fun deleteAllEdgesOf(songId: String) = Unit
    override suspend fun clearAll() = Unit
}

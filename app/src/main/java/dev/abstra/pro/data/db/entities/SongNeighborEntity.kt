package dev.abstra.pro.data.db.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * One edge of the sparse song similarity graph. Each song keeps only its
 * strongest musical neighbours per edge type, never a dense pairwise matrix.
 * Historical listening transitions stay in the existing markov table.
 */
@Entity(
    tableName = "song_neighbors",
    primaryKeys = ["songId", "neighborSongId", "edgeType"],
    indices = [Index("neighborSongId")]
)
data class SongNeighborEntity(
    val songId: String,
    val neighborSongId: String,
    /** AUDIO or METADATA. */
    val edgeType: String,
    val similarity: Float,
    val graphVersion: Int,
    val updatedAtMs: Long
) {
    companion object {
        const val EDGE_AUDIO = "AUDIO"
        const val EDGE_METADATA = "METADATA"
    }
}

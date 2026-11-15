package dev.abstra.pro.ui.model

import androidx.compose.ui.graphics.Color
import dev.abstra.pro.data.model.Song

/**
 * UI-facing presentation of a generated AI mix. This is the only layer where
 * Compose types appear; the core recommendation engine stays Compose-free.
 */
data class AiMix(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
    val gradientColors: List<Color>,
    val iconName: String,
    val description: String
)

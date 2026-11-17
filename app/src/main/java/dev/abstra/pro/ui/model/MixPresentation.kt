package dev.abstra.pro.ui.model

import androidx.compose.ui.graphics.Color

/**
 * Visual identity of each mix preset. Presentation only — the recommendation
 * engine knows nothing about colors or icons.
 */
object MixPresentation {

    data class Visuals(
        val gradientColors: List<Color>,
        val iconName: String
    )

    private val visuals = mapOf(
        "daily_flow" to Visuals(
            listOf(Color(0xFFF06FA0), Color(0xFFE14E86), Color(0xFFD7BFF3)),
            "auto_awesome"
        ),
        "discovery" to Visuals(
            listOf(Color(0xFFFD9692), Color(0xFFF26F62), Color(0xFFFDECE8)),
            "explore"
        ),
        "upbeat" to Visuals(
            listOf(Color(0xFFFF4D7E), Color(0xFFFF6F5E), Color(0xFFF06FA0)),
            "bolt"
        ),
        "chill" to Visuals(
            listOf(Color(0xFF8D517B), Color(0xFFCCB0E9), Color(0xFF43283D)),
            "spa"
        ),
        "focus" to Visuals(
            listOf(Color(0xFF5B7FD4), Color(0xFF9BB8E8), Color(0xFF2D3A5C)),
            "center_focus_strong"
        ),
        "workout" to Visuals(
            listOf(Color(0xFFFF7A45), Color(0xFFFF3D5A), Color(0xFFFFB199)),
            "fitness_center"
        ),
        "night" to Visuals(
            listOf(Color(0xFF241825), Color(0xFF43283D), Color(0xFF8D517B)),
            "bedtime"
        ),
        "time_of_day" to Visuals(
            listOf(Color(0xFFF2A65A), Color(0xFFE86F51), Color(0xFF6E4B3C)),
            "schedule"
        ),
        "forgotten_gems" to Visuals(
            listOf(Color(0xFFC9A227), Color(0xFF9C7A1E), Color(0xFF4A3B0F)),
            "history"
        ),
        "acoustic_live" to Visuals(
            listOf(Color(0xFFB98A5E), Color(0xFF8A5A33), Color(0xFF3E2A1A)),
            "music_note"
        ),
        "artist_spotlight" to Visuals(
            listOf(Color(0xFF7E57C2), Color(0xFF5E35B1), Color(0xFF2A1B4A)),
            "mic"
        )
    )

    private val fallback = Visuals(
        listOf(Color(0xFFF06FA0), Color(0xFFE14E86), Color(0xFFD7BFF3)),
        "auto_awesome"
    )

    fun byPreset(presetId: String): Visuals = visuals[presetId] ?: fallback
}

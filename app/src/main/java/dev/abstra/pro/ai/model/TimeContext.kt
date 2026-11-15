package dev.abstra.pro.ai.model

import java.util.Calendar

/** Time of day for contextual listening habits (pure Kotlin, no Android UI). */
enum class TimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT;

    companion object {
        fun current(): TimeOfDay {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..11 -> MORNING
                in 12..17 -> AFTERNOON
                in 18..22 -> EVENING
                else -> NIGHT
            }
        }
    }
}

/** Day of week type. */
enum class DayType {
    WEEKDAY,
    WEEKEND;

    companion object {
        fun current(): DayType {
            val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            return if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) WEEKEND else WEEKDAY
        }
    }
}

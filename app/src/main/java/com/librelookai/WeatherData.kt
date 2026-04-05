package com.librelookai

data class WeatherData(
    val temperatureCelsius: Float,
    val weatherCode: Int,
    val fetchedAt: Long = System.currentTimeMillis(),
    val cityName: String = "",
) {
    /** True if this reading is younger than 30 minutes. */
    val isFresh: Boolean get() = System.currentTimeMillis() - fetchedAt < 30 * 60 * 1000L
}

/** Maps WMO weather interpretation codes to a representative emoji. */
fun wmoEmoji(code: Int): String = when (code) {
    0            -> "☀️"
    1            -> "🌤️"
    2            -> "⛅"
    3            -> "🌥️"
    45, 48       -> "🌫️"
    51, 53, 55,
    56, 57       -> "🌦️"
    61, 63, 65,
    66, 67       -> "🌧️"
    71, 73, 75,
    77           -> "🌨️"
    80, 81, 82   -> "🌦️"
    85, 86       -> "🌨️"
    95, 96, 99   -> "⛈️"
    else         -> "🌡️"
}

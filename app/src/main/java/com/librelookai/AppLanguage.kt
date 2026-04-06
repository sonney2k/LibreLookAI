package com.librelookai

import java.util.Locale

object AppLanguage {
    const val ENGLISH = "English"
    const val GERMAN  = "Deutsch"

    val options = listOf(ENGLISH, GERMAN)

    fun toLocale(language: String): Locale = when (language) {
        GERMAN -> Locale("de")
        else   -> Locale.ENGLISH
    }

    /** The language name Gemini understands (used in prompt instructions). */
    fun toGeminiName(language: String): String = when (language) {
        GERMAN -> "German"
        else   -> "English"
    }
}

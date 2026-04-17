package com.librelookai

data class UserPreferences(
    val gender: String = "",
    val yearOfBirth: Int? = null,
    /** Free-text description of style preferences / recommendation context. */
    val preferences: String = "",
    /** Display language for UI and AI responses. One of [AppLanguage.options]. */
    val language: String = AppLanguage.ENGLISH,
)

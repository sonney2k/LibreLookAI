package com.librelookai

data class UserPreferences(
    val gender: String = "",
    val yearOfBirth: Int? = null,
    /** Free-text description of style preferences / recommendation context. */
    val preferences: String = "",
    /** Display language for UI and AI responses. One of [AppLanguage.options]. */
    val language: String = AppLanguage.ENGLISH,
    /** Drive file ID of the user's front-facing photo used by the Try-on feature. */
    val tryOnFrontDriveId: String = "",
    /** Drive file ID of the user's side-facing photo used by the Try-on feature. */
    val tryOnSideDriveId: String = "",
    /** Drive file ID of the user's back-facing photo used by the Try-on feature. */
    val tryOnBackDriveId: String = "",
    /** Which context sections to feed into AI style suggestions. */
    val aiConsiderations: AiConsiderations = AiConsiderations(),
    /** Run on-device similarity check on imports/captures so users can spot duplicates before committing. */
    val dedupeOnImport: Boolean = true,
    /** Cosine-similarity threshold (0..1) above which two items are treated as duplicates. */
    val dedupeThreshold: Float = 0.88f,
)

/** Toggles controlling which context blocks the outfit-suggestion prompts include. */
data class AiConsiderations(
    val weather: Boolean = true,
    val location: Boolean = true,
    val trends: Boolean = true,
    val gender: Boolean = true,
    val age: Boolean = true,
    val preferences: Boolean = true,
)

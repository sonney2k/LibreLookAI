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
)

package com.librelookai.settings

data class ProfileUiState(
    val preferences: UserPreferences = UserPreferences(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val error: String? = null,
    /** Local absolute path of the cached try-on photo per slot, or null if not set. */
    val tryOnLocalPaths: Map<TryOnSlot, String> = emptyMap(),
    /** Set of slots currently uploading a new photo. */
    val tryOnUploading: Set<TryOnSlot> = emptySet(),
)

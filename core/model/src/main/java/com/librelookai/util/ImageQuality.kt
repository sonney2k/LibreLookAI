package com.librelookai.util

/**
 * User-selectable trade-off between Drive storage size and image fidelity.
 * Persisted in `UserPreferences.imageQuality`; mirrored into `ImageEncoding.tier`
 * (`:core:common`) by `StaticPreferenceMirrors`.
 */
enum class ImageQuality { BALANCED, HIGH, MAXIMUM }

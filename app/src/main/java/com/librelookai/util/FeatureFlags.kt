package com.librelookai.util

import com.librelookai.BuildConfig

/**
 * Build-time feature toggles for power-user / diagnostic surfaces.
 *
 * [powerFeatures] gates options that are useful for power users and debugging but clutter
 * the everyday UI: the bulk wardrobe maintenance ops (re-remove backgrounds / fix leftover
 * cutout pixels, surfaced in the Wardrobe header overflow menu) and the diagnostic settings
 * (KI-Ähnlichkeitsvorschau / image-quality picker in Settings ▸ Advanced).
 *
 * Backed by [BuildConfig.POWER_FEATURES_ENABLED] (`power.features.enabled` in
 * local.properties, default `false`). The Play release ships with it off.
 */
object FeatureFlags {
    val powerFeatures: Boolean get() = BuildConfig.POWER_FEATURES_ENABLED
}

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
 *
 * [powerFeaturesOverride] is the § 7 pinnable seam: tests (and a future debug screen) set it
 * to force either state at runtime; null falls through to the BuildConfig default. A full DI
 * interface was deliberately not used — most read sites are composables, where constructor
 * injection can't reach without a CompositionLocal layer (decision recorded in
 * plan/refactor.md § 7).
 */
object FeatureFlags {
    @Volatile
    var powerFeaturesOverride: Boolean? = null

    val powerFeatures: Boolean get() = powerFeaturesOverride ?: BuildConfig.POWER_FEATURES_ENABLED
}

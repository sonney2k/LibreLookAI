package com.librelookai.wardrobe

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Coil cache-buster overlay shared by the wardrobe/shopping derived views and the
 * bulk-maintenance use-cases (refactor § 5 slice 6): bumped whenever an item's image *bytes*
 * change under an unchanged driveId (rotate / reprocess / cutout-fix / WebP convert). Versions
 * are per-process display state — the store doesn't hold them; the derivation merges this map
 * in. Lives beside the store (`:core:database`, § 1 slice 2) because it rides the
 * store-derived views.
 */
@Singleton
class ItemVersions @Inject constructor() {
    private val _versions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val versions: StateFlow<Map<String, Long>> = _versions.asStateFlow()

    fun bump(vararg driveIds: String) {
        if (driveIds.isEmpty()) return
        val now = System.currentTimeMillis()
        _versions.update { it + driveIds.associateWith { now } }
    }
}

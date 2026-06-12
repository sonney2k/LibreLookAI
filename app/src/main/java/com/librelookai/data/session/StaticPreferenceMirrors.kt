package com.librelookai.data.session

import com.librelookai.ml.EmbeddingService
import com.librelookai.util.ImageEncoding
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Mirrors the two preferences that legacy static globals still read — the WebP encoder tier
 * (`ImageEncoding.tier`) and the on-device segmenter's foreground threshold — from
 * [UserPreferencesRepository] (refactor § 5 slice 2; replaces the per-pref `LaunchedEffect`
 * mirrors in AppContent). The statics themselves are § 3's problem; this only moves the
 * bridge out of composition. Started once from `LibreLookAIApp.onCreate`.
 */
@Singleton
class StaticPreferenceMirrors @Inject constructor(
    private val prefs: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            prefs.preferences.collect { p ->
                ImageEncoding.tier = p.imageQuality
                // Segmenter access can throw before the model is available — same
                // best-effort guard the old AppContent mirror used.
                runCatching { EmbeddingService.segmenter.foregroundThreshold = p.bgRemovalThreshold }
            }
        }
    }
}

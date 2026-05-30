package com.librelookai.onboarding

import android.content.Context

/**
 * Device-local "has the first-run tour been completed/dismissed" flag, stored in its own
 * SharedPreferences file. Read synchronously so [com.librelookai.AppContent] can decide whether to
 * show the onboarding overlay before any Drive data loads. Re-running the tour from Settings does
 * NOT clear this flag — the overlay is shown via an in-memory trigger instead.
 */
object OnboardingState {
    private const val PREFS = "librelookai_onboarding"
    private const val KEY_DONE = "tour_complete"

    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun setComplete(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, value).apply()
    }
}

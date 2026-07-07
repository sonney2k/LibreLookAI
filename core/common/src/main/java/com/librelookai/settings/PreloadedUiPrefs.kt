package com.librelookai.settings

import android.content.Context

/**
 * Synchronous SharedPrefs mirror of the three UI preferences the app must know *before* any
 * user data loads from Drive: language (the `Context.localized()` hot path), theme palette and
 * font (the first frame's theme). [com.librelookai.settings.ProfileViewModel] is the only
 * writer — it caches every saved/loaded snapshot here; everything else reads.
 */
object PreloadedUiPrefs {
    private const val LANG_PREFS = "librelookai_lang"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "wardrobe_theme"
    private const val KEY_FONT = "app_font"

    private fun prefs(context: Context) =
        context.getSharedPreferences(LANG_PREFS, Context.MODE_PRIVATE)

    /**
     * Last-saved UI language, available synchronously to non-Compose layers (ViewModels)
     * so strings they resolve honour the user's in-app language override rather than the
     * device locale. Falls back to the best-fit device language. See [Context.localized].
     */
    fun cachedLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, null)
            ?: AppLanguage.fromSystemLocale(java.util.Locale.getDefault())

    /** Last-saved theme id, available before any user data is loaded from Drive. */
    fun cachedTheme(context: Context): String =
        prefs(context).getString(KEY_THEME, null) ?: UserPreferences().wardrobeTheme

    /** Last-saved font id, available before any user data is loaded from Drive. */
    fun cachedFont(context: Context): String =
        prefs(context).getString(KEY_FONT, null) ?: UserPreferences().appFont

    fun cacheLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun cacheTheme(context: Context, theme: String) {
        prefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    fun cacheFont(context: Context, font: String) {
        prefs(context).edit().putString(KEY_FONT, font).apply()
    }
}

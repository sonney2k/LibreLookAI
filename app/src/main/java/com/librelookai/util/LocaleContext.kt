package com.librelookai.util

import android.content.Context
import com.librelookai.settings.AppLanguage
import com.librelookai.settings.ProfileViewModel

/**
 * Returns a copy of this context configured for the user's **in-app** language.
 *
 * The app applies its language override only at the Compose layer (`LocalContext provides
 * localizedContext` in `AppContent`); the Application context keeps the *device* locale. So
 * any string a ViewModel resolves via `getApplication().getString(...)` would ignore the
 * user's chosen language. Wrap the context with this helper first:
 *
 * ```
 * getApplication<Application>().localized().getString(R.string.error_save_failed)
 * ```
 *
 * Reads the last-saved language synchronously from [ProfileViewModel.cachedLanguage], so it is
 * accurate even right after a live language switch. UI code should keep using `stringResource`
 * (which already reads the localized `LocalContext`) — this is only for non-Compose layers.
 */
fun Context.localized(): Context {
    val locale = AppLanguage.toLocale(ProfileViewModel.cachedLanguage(this))
    val config = android.content.res.Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

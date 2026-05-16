package com.librelookai.util
import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

/**
 * Thin wrapper around Firebase Analytics.
 *
 * Two event shapes are used:
 *  - `screen_view` (Firebase standard) for tab/screen entry
 *  - `ui_action` (custom) for individual taps, with `screen` + `action` params
 *
 * Order is preserved by the event timestamps Analytics records server-side.
 */
object Analytics {
    private var fa: FirebaseAnalytics? = null

    fun init(context: Context) {
        if (fa == null) fa = Firebase.analytics
    }

    private fun fa(): FirebaseAnalytics? = fa

    fun setUserId(uid: String?) {
        fa()?.setUserId(uid)
    }

    fun screen(name: String) {
        fa()?.logEvent(
            FirebaseAnalytics.Event.SCREEN_VIEW,
            Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, name)
            },
        )
    }

    /** Generic UI action: a button press / tap on `screen`. */
    fun action(screen: String, action: String, extra: Map<String, String> = emptyMap()) {
        fa()?.logEvent(
            "ui_action",
            Bundle().apply {
                putString("screen", screen)
                putString("action", action)
                for ((k, v) in extra) putString(k, v.take(95))
            },
        )
    }

    /** Custom event with arbitrary params (kept simple for string values). */
    fun event(name: String, params: Map<String, String> = emptyMap()) {
        fa()?.logEvent(
            name,
            Bundle().apply {
                for ((k, v) in params) putString(k, v.take(95))
            },
        )
    }
}

package com.librelookai

/**
 * Re-launches the first-run onboarding tour as a fullscreen overlay. Provided by
 * [AppContent]; null when no host wired it (e.g. previews). Lets Settings offer a "take the tour"
 * row without the tour's visibility state having to live in Settings.
 *
 * The other viewer-header CompositionLocals ([LocalOpenSettings], [LocalClosetSelector],
 * [LocalGeminiProgress]) and [ViewerHeaderActions]/[LocationButton] live in
 * `core/designsystem` (`SharedChrome.kt`, § 1 slice 6) — same `com.librelookai` package.
 */
val LocalStartTour = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

package com.librelookai.util

/**
 * Process-global handshake between the system launch splash (held in `MainActivity` via
 * `setKeepOnScreenCondition`) and the first composition. The splash stays up until `contentReady`
 * flips true — set by `AppContent` once the initial restore has settled (or immediately when there
 * is nothing to restore: onboarding / signed-out) — so a general cold start never flashes the
 * `RestoreProgressOverlay` card behind the fading splash. `MainActivity` also caps the wait, so a
 * genuinely long reinstall-restore still releases the splash and shows the progress card.
 *
 * Plain `@Volatile` (not Compose state): the reader is `MainActivity`'s splash keep-condition
 * polled off-composition; the writer is a Compose `LaunchedEffect`. Reset per process (static init).
 */
object StartupGate {
    @Volatile
    var contentReady: Boolean = false
}

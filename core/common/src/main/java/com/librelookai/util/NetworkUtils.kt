package com.librelookai.util
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** `true` when the device has no internet connectivity. Read via `LocalIsOffline.current`. */
val LocalIsOffline = compositionLocalOf { false }

/**
 * System-bar insets captured at the activity scope and propagated to UI hosted in
 * `androidx.compose.ui.window.Dialog` windows. Compose's `WindowInsets.systemBars` (and modifier
 * siblings like `navigationBarsPadding()`) report 0 inside Dialog windows on a number of devices
 * because the OS only dispatches insets to the activity's window — meaning bottom buttons in a
 * fullscreen Dialog get clipped under the gesture/nav bar. The activity reads the live insets and
 * provides them here; dialog content applies the padding manually.
 */
val LocalSystemBarsPadding = compositionLocalOf { PaddingValues(0.dp) }

/**
 * Bottom inset (dp) to pad a fullscreen `androidx.compose.ui.window.Dialog`'s content / sticky
 * bottom row by, so action buttons (e.g. "Generate") never hide under the gesture/nav bar.
 *
 * **Call this OUTSIDE the `Dialog { }` block.** It reads [LocalView] expecting the activity's
 * decor view; inside a Dialog `LocalView.current` is the dialog's own decor view whose
 * `rootWindowInsets` can report 0. We take the max of three sources because each is unreliable
 * alone: [LocalSystemBarsPadding] (0 on some devices), the activity view's real `rootWindowInsets`
 * system-bars bottom, and a 48.dp floor as a last-resort guarantee.
 */
@Composable
fun rememberDialogBottomInset(): Dp {
    val barInsets = LocalSystemBarsPadding.current
    val view = LocalView.current
    val density = LocalDensity.current
    val rootInsetBottomDp = remember(view) {
        val raw = view.rootWindowInsets
        val bottomPx = if (raw != null) {
            WindowInsetsCompat.toWindowInsetsCompat(raw, view)
                .getInsets(WindowInsetsCompat.Type.systemBars())
                .bottom
        } else 0
        with(density) { bottomPx.toDp() }
    }
    return maxOf(barInsets.calculateBottomPadding(), rootInsetBottomDp, 48.dp)
}

private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

fun Context.isNetworkAvailable(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasValidatedInternet()
}

/**
 * Reactive network monitor that exposes a [StateFlow] reflecting whether the
 * device currently has *any* network with validated internet.
 *
 * We track the set of networks the framework reports as INTERNET+VALIDATED via
 * [ConnectivityManager.registerNetworkCallback] (a `NetworkRequest` rather than
 * the default-network variant — the latter only watches one route at a time and
 * has been observed to miss the offline transition on some OEMs when the
 * default network is torn down without a replacement).
 *
 * The flow is `true` iff at least one tracked network is currently validated.
 * [recheck] re-reads the active network as a backstop (e.g. on lifecycle
 * resume) in case the system silently dropped a callback while the app was
 * backgrounded — without it, the app could remain stuck "online" after coming
 * back from a flight-mode toggle.
 *
 * Callers must invoke [unregister] when done (Activity `onDestroy`,
 * `DisposableEffect.onDispose`).
 */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val validated = mutableSetOf<Network>()

    private val _isOnline = MutableStateFlow(context.isNetworkAvailable())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Wait for onCapabilitiesChanged to confirm INTERNET+VALIDATED before
            // flipping online — `onAvailable` alone fires for networks that are
            // still being validated (e.g. captive portals).
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            synchronized(validated) {
                if (capabilities.hasValidatedInternet()) validated.add(network)
                else validated.remove(network)
                _isOnline.value = validated.isNotEmpty()
            }
        }

        override fun onLost(network: Network) {
            synchronized(validated) {
                validated.remove(network)
                _isOnline.value = validated.isNotEmpty()
            }
        }

        override fun onUnavailable() {
            synchronized(validated) {
                validated.clear()
                _isOnline.value = false
            }
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    /**
     * Re-read the active network and reconcile the flow. Useful from
     * `Lifecycle.Event.ON_RESUME` to recover from missed callbacks while the
     * app was backgrounded.
     */
    fun recheck() {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasValidatedInternet() == true
        synchronized(validated) {
            if (!online) {
                // Drop any cached entries that no longer report validated.
                val it = validated.iterator()
                while (it.hasNext()) {
                    val n = it.next()
                    val c = cm.getNetworkCapabilities(n)
                    if (c == null || !c.hasValidatedInternet()) it.remove()
                }
            } else {
                validated.add(active)
            }
            _isOnline.value = validated.isNotEmpty()
        }
    }

    fun unregister() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}

package com.librelookai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** `true` when the device has no internet connectivity. Read via `LocalIsOffline.current`. */
val LocalIsOffline = compositionLocalOf { false }

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
 * Reactive network monitor that exposes a [StateFlow] reflecting the current
 * connectivity state of the device's *default* network.
 *
 * Implemented with [ConnectivityManager.registerDefaultNetworkCallback] so we
 * only watch the route the OS would actually use for outbound traffic, and we
 * trust the callback's [Network] argument rather than re-querying
 * `cm.activeNetwork` (which races during the offline transition — the old
 * implementation could observe a still-non-null active network in `onLost`
 * and keep the flow stuck at `true`).
 *
 * Callers must invoke [unregister] when done (Activity `onDestroy`,
 * `DisposableEffect.onDispose`) to avoid leaking the system callback.
 */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(context.isNetworkAvailable())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            _isOnline.value = capabilities.hasValidatedInternet()
        }

        override fun onLost(network: Network) {
            _isOnline.value = false
        }

        override fun onUnavailable() {
            _isOnline.value = false
        }
    }

    init {
        cm.registerDefaultNetworkCallback(callback)
    }

    fun unregister() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}

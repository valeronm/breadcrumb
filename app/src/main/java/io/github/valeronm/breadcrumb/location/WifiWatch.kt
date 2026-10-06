package io.github.valeronm.breadcrumb.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * Any Wi-Fi network the phone was joined to going away. No network's name is read, which would
 * need the location grant, and nothing scans, so a network the phone never joined produces no loss.
 *
 * Registered for as long as the recorder is armed: between losses it costs nothing.
 */
class WifiWatch(
    private val context: Context,
    private val onLost: () -> Unit,
) {

    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val cm = connectivity ?: return
        val registered = object : ConnectivityManager.NetworkCallback() {
            // Logged so a loss followed at once by a join reads as a switch between networks.
            override fun onAvailable(network: Network) {
                DebugLog.i(TAG, "wifi joined")
            }

            override fun onLost(network: Network) {
                DebugLog.i(TAG, "wifi lost")
                onLost()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        // The platform caps callbacks per app and throws past the cap.
        runCatching { cm.registerNetworkCallback(request, registered) }
            .onSuccess { callback = registered }
            .onFailure { DebugLog.w(TAG, "wifi watch: registration refused (${it.message})") }
    }

    fun stop() {
        callback?.let { registered -> runCatching { connectivity?.unregisterNetworkCallback(registered) } }
        callback = null
    }

    private companion object {
        const val TAG = "Breadcrumb"
    }
}

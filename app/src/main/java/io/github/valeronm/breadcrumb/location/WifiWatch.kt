package io.github.valeronm.breadcrumb.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import io.github.valeronm.breadcrumb.domain.recording.WifiLinks
import io.github.valeronm.breadcrumb.util.DebugLog

/**
 * The phone's last joined Wi-Fi connection going away. No network's name is read, and a network the
 * phone never joined produces no loss.
 *
 * A callback arrives only when a connection changes.
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
            private val links = WifiLinks()

            override fun onAvailable(network: Network) {
                links.onJoined("$network")
                DebugLog.i(TAG, "wifi joined (net $network)")
            }

            // A roam between bands or access points of one network changes the frequency and loses nothing.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val mhz = frequencyOf(capabilities) ?: return
                if (links.frequencyChanged("$network", mhz)) DebugLog.i(TAG, "wifi net $network on $mhz MHz")
            }

            override fun onLost(network: Network) {
                val lost = links.onLost("$network")
                val mhz = lost.frequencyMhz?.let { ", $it MHz" }.orEmpty()
                val still = if (lost.offWifi) "" else " — still on net ${lost.stillJoined.joinToString(", net ")}"
                DebugLog.i(TAG, "wifi lost (net $network$mhz)$still")
                if (lost.offWifi) onLost()
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

    private fun frequencyOf(capabilities: NetworkCapabilities): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return (capabilities.transportInfo as? WifiInfo)?.frequency?.takeIf { it > 0 }
    }

    private companion object {
        const val TAG = "Breadcrumb"
    }
}

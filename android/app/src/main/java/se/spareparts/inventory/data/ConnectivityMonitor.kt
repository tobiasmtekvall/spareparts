package se.spareparts.inventory.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks whether the device has any usable network (LAN Wi-Fi counts even without internet). */
class ConnectivityMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableSetOf<Network>()
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Fired when the device goes from offline to online. */
    var onBecameOnline: (() -> Unit)? = null

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(networks) { networks += network }
                update()
            }

            override fun onLost(network: Network) {
                synchronized(networks) { networks -= network }
                update()
            }
        })
    }

    private fun update() {
        val was = _online.value
        val now = synchronized(networks) { networks.isNotEmpty() } || currentlyOnline()
        _online.value = now
        if (now && !was) onBecameOnline?.invoke()
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

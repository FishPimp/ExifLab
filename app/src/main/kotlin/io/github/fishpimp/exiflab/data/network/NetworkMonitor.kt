package io.github.fishpimp.exiflab.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the device has a network that can reach the internet. Never makes a request itself. */
class NetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Connectivity right now, for a first value before [isConnected] emits. */
    fun isConnectedNow(): Boolean {
        val manager = connectivity ?: return true
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Emits the current connectivity, then every change. */
    val isConnected: Flow<Boolean> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            // Without a ConnectivityManager there is nothing to watch; let requests decide.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(isConnectedNow())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()
}

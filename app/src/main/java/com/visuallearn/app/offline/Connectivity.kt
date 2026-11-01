package com.visuallearn.app.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone has an internet connection right now. */
class Connectivity(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(hasInternet(manager.getNetworkCapabilities(manager.activeNetwork)))
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _online.value = hasInternet(capabilities)
            }

            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    private fun hasInternet(capabilities: NetworkCapabilities?) =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

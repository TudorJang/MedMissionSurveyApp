package com.medmission.survey.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredLaptop(val name: String, val host: String, val port: Int)

private const val SERVICE_TYPE = "_medmission._tcp."

interface NsdDiscoveryService {
    fun discover(): Flow<List<DiscoveredLaptop>>
}

class AndroidNsdDiscoveryService(private val context: Context) : NsdDiscoveryService {
    override fun discover(): Flow<List<DiscoveredLaptop>> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        // Written from NSD callback threads, read when building each emission.
        val found = ConcurrentHashMap<String, DiscoveredLaptop>()

        // NsdManager throws IllegalArgumentException("listener already in use") if the
        // same ResolveListener instance is passed to a second resolveService() while the
        // first is still in flight, which happens as soon as two laptops advertise at
        // once. Allocate a fresh listener per resolve.
        fun newResolveListener() = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                found[serviceInfo.serviceName] = DiscoveredLaptop(serviceInfo.serviceName, host, serviceInfo.port)
                trySend(found.values.toList())
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, newResolveListener())
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
                trySend(found.values.toList())
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose { nsdManager.stopServiceDiscovery(discoveryListener) }
    }
}

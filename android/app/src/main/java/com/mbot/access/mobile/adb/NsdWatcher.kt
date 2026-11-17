package com.mbot.access.mobile.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Descubre un servicio mDNS (`_adb-tls-pairing._tcp` o `_adb-tls-connect._tcp`) y mantiene la ultima direccion resuelta.
 * El de emparejamiento solo existe mientras el telefono muestra el dialogo "Vincular dispositivo con codigo".
 */
class NsdWatcher(ctx: Context, private val type: String) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = ConcurrentHashMap<String, InetSocketAddress>()
    @Volatile private var last: InetSocketAddress? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()

    /** Ultima direccion conocida de un servicio de ese tipo, o null. */
    fun current(): InetSocketAddress? = last

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { queue.add(info); resolveNext() }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                last = found.values.firstOrNull()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w("MbotAdb", "NSD $type: fallo al iniciar ($errorCode)") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return       // NsdManager (< API 34) solo admite una resolucion a la vez
        val info = queue.poll() ?: run { resolving.set(false); return }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolving.set(false); resolveNext() }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                if (host != null && serviceInfo.port > 0) {
                    val a = InetSocketAddress(host, serviceInfo.port)
                    found[serviceInfo.serviceName] = a
                    last = a
                }
                resolving.set(false)
                resolveNext()
            }
        })
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        found.clear(); last = null; queue.clear(); resolving.set(false)
    }

    /** Bloquea hasta que aparezca el servicio (o se agote el tiempo). */
    fun await(timeoutMs: Long): InetSocketAddress? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            last?.let { return it }
            try { Thread.sleep(200) } catch (_: InterruptedException) { return null }
        }
        return last
    }
}

package com.mbot.access.mobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** Anuncia el servidor como `_mbot._tcp` (mDNS) para que `phone pair` lo encuentre. El TXT lleva modelo y version de API, NUNCA el token. */
class NsdAdvertiser(private val ctx: Context) {
    private var nsd: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int) {
        stop()
        try {
            val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = Build.MODEL
                serviceType = "_mbot._tcp"
                setPort(port)
                setAttribute("model", Build.MODEL)
                setAttribute("api", AgentApi.API.toString())
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(MbotA11yService.TAG, "mDNS registrado como ${i.serviceName}") }
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(MbotA11yService.TAG, "mDNS fallo: $code") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m; listener = l
        } catch (e: Exception) {
            Log.w(MbotA11yService.TAG, "mDNS: ${e.message}")
        }
    }

    fun stop() {
        try { listener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        listener = null; nsd = null
    }
}

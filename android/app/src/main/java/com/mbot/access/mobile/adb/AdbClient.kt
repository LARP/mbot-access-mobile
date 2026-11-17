package com.mbot.access.mobile.adb

import android.net.ssl.SSLSockets
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** TLS de adb: el cliente presenta el certificado autofirmado de su clave RSA (adbd compara la clave publica con las emparejadas) y no valida el del telefono. */
object AdbTls {
    private const val ALIAS = "adb"

    fun context(key: PrivateKey, certDer: ByteArray): SSLContext {
        val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(cert) else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
        }
        val tm = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(km), arrayOf(tm), null) }
    }

    /** Sube un socket TCP a TLS 1.3 como cliente (handshake completo). */
    fun wrap(ctx: SSLContext, plain: Socket, host: String, port: Int): SSLSocket {
        val s = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        s.useClientMode = true
        s.enabledProtocols = arrayOf("TLSv1.3")
        s.startHandshake()
        return s
    }
}

/** Emparejamiento por el puerto de "Depuracion inalambrica > Vincular con codigo" (Android 11+). */
object AdbPairing {
    /** Devuelve el PeerInfo del telefono. Lanza IOException con un mensaje legible si el codigo es incorrecto. */
    fun pair(host: String, port: Int, code: String, ctx: SSLContext, adbPublicKey: ByteArray): ByteArray {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), 8000)
        plain.soTimeout = 15000
        val ssl = AdbTls.wrap(ctx, plain, host, port)
        try {
            // exportador TLS: "adb-label" + NUL, 64 bytes, sin contexto (tls_connection.cpp)
            val label = "adb-label" + 0.toChar()
            val exported: ByteArray = SSLSockets.exportKeyingMaterial(ssl, label, null, 64)
                ?: throw java.io.IOException("el TLS no pudo exportar material de clave")
            val secret: ByteArray = code.toByteArray(Charsets.US_ASCII) + exported
            return PairingExchange(true, secret, PairingPackets.peerInfo(adbPublicKey)).run(ssl.inputStream, ssl.outputStream)
        } finally {
            try { ssl.close() } catch (_: Exception) {}
        }
    }
}

/** Conexion adb por TLS (puerto de `_adb-tls-connect._tcp`): CNXN -> STLS -> TLS -> CNXN, y servicio `shell:`. */
class AdbConnection(private val host: String, private val port: Int, private val ctx: SSLContext) : Closeable {
    private var plain: Socket? = null
    private var tls: SSLSocket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    private var maxData = AdbWire.MAX_PAYLOAD
    private var nextId = 1

    fun connect(timeoutMs: Int = 8000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 15000
        plain = s
        val pin = s.getInputStream()
        val pout = s.getOutputStream()
        AdbWire.write(pout, AdbWire.A_CNXN, AdbWire.A_VERSION, AdbWire.MAX_PAYLOAD, AdbWire.clientBanner())
        val m = AdbWire.read(pin)
        when (m.command) {
            AdbWire.A_STLS -> {
                AdbWire.write(pout, AdbWire.A_STLS, AdbWire.A_STLS_VERSION, 0)
                val t = AdbTls.wrap(ctx, s, host, port)
                tls = t
                inp = t.inputStream; out = t.outputStream
                val c = AdbWire.read(inp!!)
                if (c.command != AdbWire.A_CNXN) throw java.io.IOException("adb: se esperaba CNXN tras TLS y llego ${c.name}")
                maxData = minOf(c.arg1, AdbWire.MAX_PAYLOAD).coerceAtLeast(4096)
            }
            AdbWire.A_AUTH -> throw java.io.IOException("el telefono pide autenticacion RSA clasica (no TLS): esta app solo soporta la depuracion inalambrica de Android 11+")
            else -> throw java.io.IOException("adb: respuesta inesperada ${m.name}")
        }
    }

    /** `shell:<comando>`: ejecuta y devuelve la salida (stdout+stderr mezclados) hasta que el telefono cierra el flujo. */
    fun shell(command: String, timeoutMs: Long = 20000): String {
        val i = inp ?: throw IllegalStateException("sin conectar")
        val o = out ?: throw IllegalStateException("sin conectar")
        val local = nextId++
        AdbWire.write(o, AdbWire.A_OPEN, local, 0, ("shell:" + command + "\u0000").toByteArray(Charsets.UTF_8))
        val buf = ByteArrayOutputStream()
        var remote = 0
        val t0 = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - t0 > timeoutMs) throw java.io.IOException("adb: tiempo agotado ejecutando el comando")
            val m = AdbWire.read(i, maxData)
            when (m.command) {
                AdbWire.A_OKAY -> if (remote == 0) remote = m.arg0
                AdbWire.A_WRTE -> {
                    if (remote == 0) remote = m.arg0
                    buf.write(m.data)
                    AdbWire.write(o, AdbWire.A_OKAY, local, remote)
                }
                AdbWire.A_CLSE -> {
                    AdbWire.write(o, AdbWire.A_CLSE, local, if (remote != 0) remote else m.arg0)
                    return buf.toString("UTF-8")
                }
                else -> {}
            }
        }
    }

    override fun close() {
        try { tls?.close() } catch (_: Exception) {}
        try { plain?.close() } catch (_: Exception) {}
    }
}

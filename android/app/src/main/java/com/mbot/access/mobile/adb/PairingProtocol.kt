package com.mbot.access.mobile.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HKDF (RFC 5869) con HMAC-SHA256. adb lo usa con sal vacia (= 32 bytes a cero) para derivar la clave AES. */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.update(t); mac.update(info); mac.update(byteArrayOf(i.toByte()))
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }
}

/** AES-128-GCM de adb (aes_128_gcm.cpp): clave = HKDF(clave SPAKE2, "adb pairing_auth aes-128-gcm key"), nonce de 12 bytes = contador u64 little-endian + 4 ceros, sin AAD. */
class PairingCipher(keyMaterial: ByteArray) {
    private val key = SecretKeySpec(Hkdf.sha256(keyMaterial, ByteArray(0), "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII), 16), "AES")
    private var encSeq = 0L
    private var decSeq = 0L

    private fun nonce(seq: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[i] = ((seq ushr (8 * i)) and 0xFF).toByte()
        return n
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(encSeq)))
        val out = c.doFinal(plain)
        encSeq++
        return out
    }

    /** Lanza (AEADBadTagException) si la etiqueta no coincide: contrasena distinta. */
    fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(decSeq)))
        val out = c.doFinal(data)
        decSeq++
        return out
    }
}

/** Marcos del emparejamiento (pairing_connection.cpp): cabecera de 6 bytes = version(1) tipo(1) longitud(u32 big-endian) + carga. */
object PairingPackets {
    const val VERSION = 1
    const val TYPE_SPAKE2_MSG = 0
    const val TYPE_PEER_INFO = 1
    const val PEER_INFO_SIZE = 8192            // kMaxPeerInfoSize
    const val MAX_PAYLOAD = PEER_INFO_SIZE * 2 // kMaxPayloadSize
    const val PEER_RSA_PUB_KEY = 0             // PeerInfoType::ADB_RSA_PUB_KEY

    fun header(type: Int, payloadLen: Int): ByteArray = byteArrayOf(
        VERSION.toByte(), type.toByte(),
        (payloadLen ushr 24).toByte(), (payloadLen ushr 16).toByte(), (payloadLen ushr 8).toByte(), payloadLen.toByte()
    )

    class Header(val type: Int, val payload: Int)

    /** Valida version, tipo y tamano como ReadHeader() de adb; lanza IllegalArgumentException si no son validos. */
    fun parseHeader(b: ByteArray): Header {
        require(b.size == 6) { "cabecera de tamano invalido" }
        val ver = b[0].toInt() and 0xFF
        require(ver == VERSION) { "version del protocolo de emparejamiento no soportada: $ver" }
        val type = b[1].toInt() and 0xFF
        require(type == TYPE_SPAKE2_MSG || type == TYPE_PEER_INFO) { "tipo de paquete desconocido: $type" }
        val len = ((b[2].toInt() and 0xFF) shl 24) or ((b[3].toInt() and 0xFF) shl 16) or ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        require(len in 1..MAX_PAYLOAD) { "tamano de carga fuera de rango: $len" }
        return Header(type, len)
    }

    /** PeerInfo de 8192 bytes: tipo (1 byte) + clave publica de adb ("base64 usuario@equipo") + ceros. */
    fun peerInfo(adbPublicKey: ByteArray): ByteArray {
        require(adbPublicKey.size < PEER_INFO_SIZE) { "clave publica demasiado grande" }
        val out = ByteArray(PEER_INFO_SIZE)
        out[0] = PEER_RSA_PUB_KEY.toByte()
        System.arraycopy(adbPublicKey, 0, out, 1, adbPublicKey.size)
        return out
    }
}

/**
 * Intercambio de emparejamiento sobre un canal ya cifrado (TLS 1.3): SPAKE2 -> PeerInfo cifrado en ambos sentidos.
 * `secret` = codigo de 6 digitos (bytes) + 64 bytes del exportador TLS ("adb-label\0"). Sirve para cliente y servidor (el servidor se usa en las pruebas).
 */
class PairingExchange(private val client: Boolean, secret: ByteArray, private val ownPeerInfo: ByteArray,
                      rng: (ByteArray) -> Unit = { java.security.SecureRandom().nextBytes(it) }) {
    companion object {
        val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)   // sizeof(kClientName): 16 bytes con el NUL
        val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
    }

    private val spake = Spake2(client, if (client) CLIENT_NAME else SERVER_NAME, if (client) SERVER_NAME else CLIENT_NAME, rng)
    private val ownMsg = spake.generateMsg(secret)

    private fun readFully(i: DataInputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        try { i.readFully(b) } catch (e: EOFException) { throw java.io.IOException("el telefono cerro la conexion (codigo incorrecto?)") }
        return b
    }

    private fun send(o: OutputStream, type: Int, body: ByteArray) {
        o.write(PairingPackets.header(type, body.size))
        o.write(body)
        o.flush()
    }

    /** Ejecuta el intercambio y devuelve el PeerInfo del otro lado (8192 bytes). */
    fun run(inp: InputStream, out: OutputStream): ByteArray {
        val din = DataInputStream(inp)
        send(out, PairingPackets.TYPE_SPAKE2_MSG, ownMsg)
        val h1 = PairingPackets.parseHeader(readFully(din, 6))
        require(h1.type == PairingPackets.TYPE_SPAKE2_MSG) { "se esperaba SPAKE2_MSG" }
        val theirMsg = readFully(din, h1.payload)
        val cipher = PairingCipher(spake.processMsg(theirMsg))
        send(out, PairingPackets.TYPE_PEER_INFO, cipher.encrypt(ownPeerInfo))
        val h2 = PairingPackets.parseHeader(readFully(din, 6))
        require(h2.type == PairingPackets.TYPE_PEER_INFO) { "se esperaba PEER_INFO" }
        val enc = readFully(din, h2.payload)
        val plain = try { cipher.decrypt(enc) } catch (e: java.security.GeneralSecurityException) { throw java.io.IOException("codigo de emparejamiento incorrecto") }
        require(plain.size == PairingPackets.PEER_INFO_SIZE) { "PeerInfo de tamano inesperado: ${plain.size}" }
        return plain
    }
}

package com.mbot.access.mobile.adb

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Clave publica de adb ("RSAPublicKey" de libmincrypt / android_pubkey_encode): 524 bytes little-endian que adbd compara con la del certificado TLS. */
object AdbPublicKey {
    const val ENCODED_SIZE = 524          // 4 + 4 + 256 + 256 + 4
    const val MODULUS_WORDS = 64          // RSA 2048 = 64 palabras de 32 bits

    /** struct { u32 palabras; u32 n0inv = -1/n[0] mod 2^32; u8 modulo[256]; u8 rr[256] = (2^2048)^2 mod n; u32 exponente } */
    fun encodeStruct(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        require(n.bitLength() in 2041..2048) { "adb solo usa claves RSA de 2048 bits" }
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(two32).modInverse(two32).negate().mod(two32)    // -1/n[0] mod 2^32
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val out = ByteArray(ENCODED_SIZE)
        le32(out, 0, MODULUS_WORDS.toLong())
        le32(out, 4, n0inv.toLong())
        copyLe(out, 8, n, 256)
        copyLe(out, 264, rr, 256)
        le32(out, 520, pub.publicExponent.toLong())
        return out
    }

    /** "BASE64(struct) nombre" tal cual lo guarda adbd en adb_keys y lo recibe en el PeerInfo del emparejamiento. */
    fun encodeWithName(pub: RSAPublicKey, name: String): ByteArray =
        (Base64.getEncoder().encodeToString(encodeStruct(pub)) + " " + name).toByteArray(Charsets.UTF_8)

    private fun le32(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun copyLe(out: ByteArray, off: Int, v: BigInteger, len: Int) {
        val be = v.toByteArray()
        for (i in 0 until len) {
            val idx = be.size - 1 - i
            if (idx >= 0) out[off + i] = be[idx]
        }
    }
}

/** Certificado X.509 autofirmado minimo en DER (sin dependencias): lo exige el TLS 1.3 de adb en ambos sentidos. */
object X509Builder {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(tag.toByte()) + len(body.size) + body
    }

    private fun integer(v: BigInteger) = der(0x02, v.toByteArray())
    private val OID_SHA256_RSA = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val OID_CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val ALG = der(0x30, OID_SHA256_RSA, byteArrayOf(0x05, 0x00))

    private fun name(cn: String) = der(0x30, der(0x31, der(0x30, OID_CN, der(0x0C, cn.toByteArray(Charsets.UTF_8)))))

    private fun utcTime(s: String) = der(0x17, s.toByteArray(Charsets.US_ASCII))

    /** Certificado v3 autofirmado (SHA256withRSA) para la clave dada. Valido 2020-2049 (UTCTime). */
    fun selfSigned(keys: KeyPair, cn: String = "00", rng: SecureRandom = SecureRandom()): ByteArray {
        val serial = ByteArray(16).also { rng.nextBytes(it); it[0] = (it[0].toInt() and 0x7F).toByte() }
        val tbs = der(0x30,
            der(0xA0, integer(BigInteger.valueOf(2))),                 // version v3
            integer(BigInteger(1, serial)),
            ALG,
            name(cn),
            der(0x30, utcTime("200101000000Z"), utcTime("491231235959Z")),
            name(cn),
            keys.public.encoded                                         // SubjectPublicKeyInfo ya viene en DER
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(tbs)
            sign()
        }
        return der(0x30, tbs, ALG, der(0x03, byteArrayOf(0) + sig))
    }
}

/** Generacion del par RSA 2048 de adb. */
object AdbKeyGen {
    fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}

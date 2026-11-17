package com.mbot.access.mobile.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Aritmetica de la curva edwards25519 con BigInteger (sin dependencias). Solo para el emparejamiento de adb (un puñado de
 * multiplicaciones escalares por emparejamiento): NO es de tiempo constante; no usar para otra cosa.
 */
object Ed25519 {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    /** Orden del subgrupo de orden primo. */
    val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** Punto en coordenadas extendidas (X:Y:Z:T), x=X/Z, y=Y/Z, xy=T/Z. */
    class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun mod(a: BigInteger) = a.mod(P)

    fun add(a: Point, b: Point): Point {
        val aa = mod(a.y.subtract(a.x).multiply(b.y.subtract(b.x)))
        val bb = mod(a.y.add(a.x).multiply(b.y.add(b.x)))
        val cc = mod(a.t.multiply(D2).multiply(b.t))
        val dd = mod(a.z.shiftLeft(1).multiply(b.z))
        val e = bb.subtract(aa)
        val f = dd.subtract(cc)
        val g = dd.add(cc)
        val h = bb.add(aa)
        return Point(mod(e.multiply(f)), mod(g.multiply(h)), mod(f.multiply(g)), mod(e.multiply(h)))
    }

    fun negate(a: Point) = Point(mod(a.x.negate()), a.y, a.z, mod(a.t.negate()))

    fun sub(a: Point, b: Point) = add(a, negate(b))

    /** k * pt para k >= 0 (cualquier tamaño: se usa tal cual, sin reducir mod L, porque los puntos pueden tener componente de orden pequeño). */
    fun mul(k: BigInteger, pt: Point): Point {
        var r = IDENTITY
        var q = pt
        var i = 0
        val n = k.bitLength()
        while (i < n) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
            i++
        }
        return r
    }

    private fun affine(p: Point): Pair<BigInteger, BigInteger> {
        val zi = p.z.modInverse(P)
        return Pair(mod(p.x.multiply(zi)), mod(p.y.multiply(zi)))
    }

    fun toAffine(p: Point): Pair<BigInteger, BigInteger> = affine(p)

    fun fromAffine(x: BigInteger, y: BigInteger) = Point(x, y, BigInteger.ONE, mod(x.multiply(y)))

    /** Codificacion de 32 bytes: y en little-endian con el bit de signo de x en el bit 255. */
    fun encode(p: Point): ByteArray {
        val (x, y) = affine(p)
        val out = leBytes(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Decodifica; null si no es un punto valido de la curva (como x25519_ge_frombytes_vartime). */
    fun decode(b: ByteArray): Point? {
        if (b.size != 32) return null
        val sign = (b[31].toInt() and 0x80) != 0
        val yb = b.copyOf()
        yb[31] = (yb[31].toInt() and 0x7F).toByte()
        val y = fromLe(yb)
        if (y >= P) return null
        val y2 = mod(y.multiply(y))
        val u = mod(y2.subtract(BigInteger.ONE))
        val v = mod(D.multiply(y2).add(BigInteger.ONE))
        // x^2 = u/v ; candidato x = (u/v)^((p+3)/8)
        val x2 = mod(u.multiply(v.modInverse(P)))
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (mod(x.multiply(x)) != x2) x = mod(x.multiply(SQRT_M1))
        if (mod(x.multiply(x)) != x2) return null
        if (x.signum() == 0 && sign) return null
        if (x.testBit(0) != sign) x = mod(x.negate())
        return fromAffine(x, y)
    }

    fun equal(a: Point, b: Point): Boolean {
        return mod(a.x.multiply(b.z)) == mod(b.x.multiply(a.z)) && mod(a.y.multiply(b.z)) == mod(b.y.multiply(a.z))
    }

    val BASE: Point = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"))!!

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun fromLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    fun leBytes(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray()          // con signo, big-endian
        val out = ByteArray(len)
        for (i in 0 until len) {
            val idx = be.size - 1 - i
            if (idx >= 0) out[i] = be[idx]
        }
        return out
    }
}

/**
 * SPAKE2 sobre edwards25519 exactamente como BoringSSL (crypto/curve25519/spake25519.cc), que es lo que usa adb
 * (pairing_auth.cpp): puntos M y N fijos, escalar de la contrasena = SHA-512 reducido + "password_scalar_hack" (multiplo de 8),
 * escalar efimero multiplicado por 8, transcript SHA-512 con prefijos de longitud de 8 bytes little-endian.
 * `client` = rol "alice" (usa M para enmascarar y N para desenmascarar), servidor = "bob".
 */
class Spake2(
    private val client: Boolean,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val rng: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) }
) {
    companion object {
        val M: Ed25519.Point = Ed25519.decode(Ed25519.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        val N: Ed25519.Point = Ed25519.decode(Ed25519.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!
        const val MSG_SIZE = 32
        const val KEY_SIZE = 64

        /** Escalar de la contrasena: SHA-512(pw) mod L, y se le suman L, 2L, 4L para dejarlo multiplo de 8. */
        fun passwordScalar(passwordHash: ByteArray): BigInteger {
            var w = Ed25519.fromLe(passwordHash).mod(Ed25519.L)
            if (w.testBit(0)) w = w.add(Ed25519.L)
            if (w.testBit(1)) w = w.add(Ed25519.L.shiftLeft(1))
            if (w.testBit(2)) w = w.add(Ed25519.L.shiftLeft(2))
            check(w.toInt() and 7 == 0)
            return w
        }

        private fun lenPrefixed(md: MessageDigest, data: ByteArray) {
            val len = ByteArray(8)
            var l = data.size.toLong()
            for (i in 0 until 8) { len[i] = (l and 0xFF).toByte(); l = l shr 8 }
            md.update(len); md.update(data)
        }
    }

    private var privateKey: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var w: BigInteger? = null
    private var myMsg: ByteArray? = null

    /** Primer mensaje (32 bytes) para el otro lado. */
    fun generateMsg(password: ByteArray): ByteArray {
        check(myMsg == null) { "SPAKE2: el mensaje ya se genero" }
        val r = ByteArray(64)
        rng(r)
        // x25519_sc_reduce + left_shift_3: x = (r mod L) * 8
        val x = Ed25519.fromLe(r).mod(Ed25519.L).shiftLeft(3)
        privateKey = x
        val p = Ed25519.mul(x, Ed25519.BASE)
        val h = MessageDigest.getInstance("SHA-512").digest(password)
        passwordHash = h
        val ws = passwordScalar(h)
        w = ws
        val mask = Ed25519.mul(ws, if (client) M else N)
        val pstar = Ed25519.add(p, mask)
        myMsg = Ed25519.encode(pstar)
        return myMsg!!
    }

    /** Procesa el mensaje del otro lado y devuelve la clave compartida de 64 bytes; lanza si el punto no es valido. */
    fun processMsg(theirMsg: ByteArray): ByteArray {
        val mine = checkNotNull(myMsg) { "SPAKE2: falta generateMsg" }
        require(theirMsg.size == MSG_SIZE) { "SPAKE2: mensaje de tamano invalido" }
        val qstar = Ed25519.decode(theirMsg) ?: throw IllegalArgumentException("SPAKE2: el punto del otro lado no es valido")
        val peersMask = Ed25519.mul(w!!, if (client) N else M)
        val q = Ed25519.sub(qstar, peersMask)
        val dh = Ed25519.mul(privateKey!!, q)
        val dhEnc = Ed25519.encode(dh)
        val md = MessageDigest.getInstance("SHA-512")
        if (client) {
            lenPrefixed(md, myName); lenPrefixed(md, theirName); lenPrefixed(md, mine); lenPrefixed(md, theirMsg)
        } else {
            lenPrefixed(md, theirName); lenPrefixed(md, myName); lenPrefixed(md, theirMsg); lenPrefixed(md, mine)
        }
        lenPrefixed(md, dhEnc)
        lenPrefixed(md, passwordHash!!)
        return md.digest()
    }
}

package com.mbot.access.mobile.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.security.MessageDigest

private fun h(s: String) = Ed25519.hex(s)
private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

class Ed25519Test {
    @Test fun baseEncodingRoundTrips() {
        val b = Ed25519.BASE
        assertEquals("5866666666666666666666666666666666666666666666666666666666666666", hex(Ed25519.encode(b)))
        val (x, y) = Ed25519.toAffine(b)
        assertEquals(BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"), x)
        assertEquals(BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"), y)
    }

    /** RFC 8032 7.1 TEST 1: la clave publica es (clamp(SHA512(semilla)[0..32]) * B). */
    @Test fun rfc8032PublicKeyVector() {
        val seed = h("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val hh = MessageDigest.getInstance("SHA-512").digest(seed)
        hh[0] = (hh[0].toInt() and 248).toByte(); hh[31] = (hh[31].toInt() and 127).toByte(); hh[31] = (hh[31].toInt() or 64).toByte()
        val a = Ed25519.fromLe(hh.copyOf(32))
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", hex(Ed25519.encode(Ed25519.mul(a, Ed25519.BASE))))
    }

    @Test fun groupOrderTimesBaseIsIdentity() {
        assertTrue(Ed25519.equal(Ed25519.mul(Ed25519.L, Ed25519.BASE), Ed25519.IDENTITY))
        assertFalse(Ed25519.equal(Ed25519.mul(Ed25519.L.subtract(BigInteger.ONE), Ed25519.BASE), Ed25519.IDENTITY))
    }

    /** Los puntos M y N de BoringSSL (spake25519.cc) con las coordenadas que documenta el codigo. */
    @Test fun spakePointsMatchBoringSslComments() {
        val (nx, ny) = Ed25519.toAffine(Spake2.N)
        assertEquals(BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"), nx)
        assertEquals(BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"), ny)
        val (mx, my) = Ed25519.toAffine(Spake2.M)
        assertEquals(BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"), mx)
        assertEquals(BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"), my)
    }

    @Test fun rejectsPointsNotOnCurve() {
        // y = 2 no tiene x valido en esta curva
        val bad = ByteArray(32).also { it[0] = 2 }
        assertNull(Ed25519.decode(bad))
        assertNull(Ed25519.decode(ByteArray(31)))
    }

    @Test fun addNegateIdentity() {
        val p = Ed25519.mul(BigInteger.valueOf(12345), Ed25519.BASE)
        assertTrue(Ed25519.equal(Ed25519.sub(p, p), Ed25519.IDENTITY))
        assertTrue(Ed25519.equal(Ed25519.add(p, Ed25519.IDENTITY), p))
    }
}

class Spake2Test {
    private val clientName = PairingExchange.CLIENT_NAME
    private val serverName = PairingExchange.SERVER_NAME
    private val secret = "123456".toByteArray() + ByteArray(64) { it.toByte() }
    private fun rnd(seed: String): (ByteArray) -> Unit = { b -> val s = h(seed); System.arraycopy(s, 0, b, 0, b.size) }

    @Test fun namesIncludeTrailingNul() {
        assertEquals(16, clientName.size); assertEquals(0, clientName.last().toInt())
        assertEquals(16, serverName.size); assertEquals(0, serverName.last().toInt())
    }

    /** Vector fijo generado por una referencia independiente (enteros de Python con formulas afines). */
    @Test fun matchesIndependentReferenceVector() {
        val a = Spake2(true, clientName, serverName, rnd("01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae1e8eff6fd040b121920272e353c434a51585f666d747b828990979ea5acb3ba"))
        val b = Spake2(false, serverName, clientName, rnd("05101b26313c47525d68737e89949faab5c0cbd6e1ecf7020d18232e39444f5a65707b86919ca7b2bdc8d3dee9f4ff0a15202b36414c57626d78838e99a4afba"))
        val ma = a.generateMsg(secret)
        val mb = b.generateMsg(secret)
        assertEquals("bbd98b24adc4889594836c8af5f77d6964b9ba2ee2dbf85836d79a93d5a2df27", hex(ma))
        assertEquals("177068b02f895eb7deea95be498225a7f0f328532a8ab7c7a47cb699e5f2c4ba", hex(mb))
        val ka = a.processMsg(mb)
        val kb = b.processMsg(ma)
        assertEquals("6940f0267bfb314416d8c8e3fc5781d60a2568b176f47a6ccbffdcda1660559b391a6828005e3697d4f4169928a6e427e9beaae0429ca1dd90b9e491cbc19843", hex(ka))
        assertArrayEquals(ka, kb)
        assertEquals(64, ka.size)
    }

    @Test fun randomRunsAgree() {
        repeat(5) {
            val a = Spake2(true, clientName, serverName)
            val b = Spake2(false, serverName, clientName)
            val ma = a.generateMsg(secret); val mb = b.generateMsg(secret)
            assertArrayEquals(a.processMsg(mb), b.processMsg(ma))
        }
    }

    @Test fun wrongPasswordGivesDifferentKey() {
        val a = Spake2(true, clientName, serverName)
        val b = Spake2(false, serverName, clientName)
        val ma = a.generateMsg("123456".toByteArray()); val mb = b.generateMsg("654321".toByteArray())
        assertFalse(a.processMsg(mb).contentEquals(b.processMsg(ma)))
    }

    @Test fun passwordScalarIsMultipleOfEightAndCongruentModL() {
        for (i in 0 until 20) {
            val hash = MessageDigest.getInstance("SHA-512").digest("pw$i".toByteArray())
            val w = Spake2.passwordScalar(hash)
            assertEquals(0, w.toInt() and 7)
            assertEquals(Ed25519.fromLe(hash).mod(Ed25519.L), w.mod(Ed25519.L))
            assertTrue(w.bitLength() <= 256)
        }
    }

    @Test fun rejectsInvalidPeerMessage() {
        val a = Spake2(true, clientName, serverName)
        a.generateMsg(secret)
        try { a.processMsg(ByteArray(32).also { it[0] = 2 }); fail("debia fallar") } catch (_: IllegalArgumentException) {}
        try { a.processMsg(ByteArray(31)); fail("debia fallar") } catch (_: IllegalArgumentException) {}
    }
}

class HkdfAndCipherTest {
    /** RFC 5869 A.1 (sal e info no vacias) y A.3 (sal e info vacias). */
    @Test fun rfc5869Vectors() {
        val okm1 = Hkdf.sha256(h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), h("000102030405060708090a0b0c"), h("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex(okm1))
        val okm3 = Hkdf.sha256(h("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), ByteArray(0), ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", hex(okm3))
    }

    @Test fun cipherMatchesReferenceAndCountsNonces() {
        val key = h("6940f0267bfb314416d8c8e3fc5781d60a2568b176f47a6ccbffdcda1660559b391a6828005e3697d4f4169928a6e427e9beaae0429ca1dd90b9e491cbc19843")
        val c = PairingCipher(key)
        val pt = h("686f6c6120616462686f6c6120616462686f6c6120616462686f6c6120616462")
        assertEquals("dc06f0dac0f7d995070009e4d49f81c181d6e1dcc63b59036ff028421b597e8bb7265c2df55a31dff2dd2611a7c2e271", hex(c.encrypt(pt)))     // nonce = contador 0
        assertEquals("339c2a8117af95ce8334d2d753fbd479a8b87409b52a7dc2bb12900af86ab2c6a89f25aed7d3a268ade785abe344cc25", hex(c.encrypt(pt)))     // nonce = contador 1
        val d = PairingCipher(key)
        assertArrayEquals(pt, d.decrypt(h("dc06f0dac0f7d995070009e4d49f81c181d6e1dcc63b59036ff028421b597e8bb7265c2df55a31dff2dd2611a7c2e271")))
        assertArrayEquals(pt, d.decrypt(h("339c2a8117af95ce8334d2d753fbd479a8b87409b52a7dc2bb12900af86ab2c6a89f25aed7d3a268ade785abe344cc25")))
    }

    @Test fun decryptFailsWithWrongKey() {
        val c = PairingCipher(h("6940f0267bfb314416d8c8e3fc5781d60a2568b176f47a6ccbffdcda1660559b391a6828005e3697d4f4169928a6e427e9beaae0429ca1dd90b9e491cbc19843"))
        val ct = c.encrypt(ByteArray(10))
        try { PairingCipher(ByteArray(64)).decrypt(ct); fail("debia fallar") } catch (_: java.security.GeneralSecurityException) {}
    }
}

class PairingPacketsTest {
    @Test fun headerLayout() {
        val b = PairingPackets.header(PairingPackets.TYPE_PEER_INFO, 8208)
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 0x20, 0x10), b)
        val p = PairingPackets.parseHeader(b)
        assertEquals(1, p.type); assertEquals(8208, p.payload)
    }

    @Test fun rejectsBadHeaders() {
        fun bad(b: ByteArray) { try { PairingPackets.parseHeader(b); fail("debia fallar") } catch (_: IllegalArgumentException) {} }
        bad(byteArrayOf(2, 0, 0, 0, 0, 32))        // version
        bad(byteArrayOf(1, 7, 0, 0, 0, 32))        // tipo
        bad(byteArrayOf(1, 0, 0, 0, 0, 0))         // carga 0
        bad(byteArrayOf(1, 0, 0, 1, 0, 1))         // carga > 16384
        bad(byteArrayOf(1, 0, 0))                  // corta
    }

    @Test fun peerInfoLayout() {
        val k = "AAAA mbot@test".toByteArray()
        val pi = PairingPackets.peerInfo(k)
        assertEquals(8192, pi.size)
        assertEquals(0, pi[0].toInt())                         // ADB_RSA_PUB_KEY
        assertArrayEquals(k, pi.copyOfRange(1, 1 + k.size))
        assertTrue(pi.copyOfRange(1 + k.size, pi.size).all { it.toInt() == 0 })   // relleno con ceros (= NUL final)
    }
}

class PairingExchangeTest {
    private fun run(clientSecret: ByteArray, serverSecret: ByteArray): Triple<ByteArray?, ByteArray?, Throwable?> {
        // dos tuberias cruzadas = canal duplex en memoria (en el telefono va dentro de TLS)
        val c2s = PipedOutputStream(); val s2cIn = PipedInputStream(c2s, 1 shl 16)
        val s2c = PipedOutputStream(); val c2sIn = PipedInputStream(s2c, 1 shl 16)
        val clientInfo = PairingPackets.peerInfo("CLIENTKEY mbot@pc".toByteArray())
        val serverInfo = ByteArray(8192).also { it[0] = 1; "GUID".toByteArray().copyInto(it, 1) }
        var gotByServer: ByteArray? = null; var srvErr: Throwable? = null
        val t = Thread {
            try { gotByServer = PairingExchange(false, serverSecret, serverInfo).run(s2cIn, s2c) } catch (e: Throwable) { srvErr = e; try { s2c.close() } catch (_: Exception) {} }
        }
        t.start()
        var gotByClient: ByteArray? = null; var cliErr: Throwable? = null
        try { gotByClient = PairingExchange(true, clientSecret, clientInfo).run(c2sIn, c2s) } catch (e: Throwable) { cliErr = e; try { c2s.close() } catch (_: Exception) {} }
        t.join(10000)
        assertNotNull(clientInfo)
        if (cliErr == null && srvErr == null) {
            assertArrayEquals(serverInfo, gotByClient); assertArrayEquals(clientInfo, gotByServer)
        }
        return Triple(gotByClient, gotByServer, cliErr ?: srvErr)
    }

    @Test fun bothSidesExchangePeerInfo() {
        val s = "123456".toByteArray() + ByteArray(64) { (it * 3).toByte() }
        val r = run(s, s)
        assertNull(r.third)
    }

    @Test fun wrongCodeFailsWithReadableError() {
        val ex = ByteArray(64) { (it * 3).toByte() }
        val r = run("123456".toByteArray() + ex, "123457".toByteArray() + ex)
        assertNotNull(r.third)
    }

    @Test fun differentTlsExporterFails() {
        val r = run("123456".toByteArray() + ByteArray(64) { 1 }, "123456".toByteArray() + ByteArray(64) { 2 })
        assertNotNull(r.third)
    }
}

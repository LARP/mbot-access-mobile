package com.mbot.access.mobile.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class AdbPublicKeyTest {
    private val n = BigInteger("ad9a3ebba431f6bb44b7883c7635515d6303e343a9547ae9a61c6baec19aca4923d852952e1daa549de2824e481730a878d5704b8ce5d37e6a5759eb863b385f5b8e064d108f8ef4ed64821e636650609f4abbb522735b3d681d0c02b145dcfab0872fad5a96d5333616eeb108baee0a91537cdaa62a07617c648636cb1b4f9137df51adc556f6c4d114dea4e941e90098aa7cd293750b7fa961b384caed89ebb2c10eed8f345c78f6b03a049b62125d0cee37ebd07017e39a6a7edb59f83c0a513dfb5d88b810826dc543229de4c6cc6bdd872ced122617ec28fec76bc94a3f257e8d53fa18d5de5ce1f85a46d48bf3cdbad793d45492c3caeb524c350e0541", 16)
    private val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger.valueOf(65537))) as RSAPublicKey

    /** Vector de una referencia independiente en Python; el mismo formato que ~/.android/adbkey.pub (comprobado contra el real). */
    @Test fun structMatchesReference() {
        assertEquals("QAAAAD91puFBBQ41TFLrysOSVNST17rN84vURlr44Vze1Rj6U41+JT9KyWvH/ijsFyYS7SyH3WvMxuSdIkPFbYIQuIhd+z1RCjz4Wdt+aprjF3DQ6zfuDF0SYpsEOrD2eFw0j+0OwbLrie3KhLNhqX8LdZPSfKqYAOlB6aTeFNHE9lbFrVHfN5FPG8s2hmR8YQcqptp8U5EK7roIse4WNjPVllqtL4ew+txFsQIMHWg9W3MitbtKn2BQZmMegmTt9I6PEE0GjltfODuG61lXan7T5YxLcNV4qDAXSE6C4p1Uqh0ulVLYI0nKmsGuaxym6XpUqUPjA2NdUTV2PIi3RLv2MaS7PpqtuEUG9YO1huCQkTyNpfUBFkc6aP6bnVqSS5EAPyjqDklkW6wgGxs7K31JO3V/MoniScTLzUeb7fpi7z0gpqFbAZU04M2vb5G+KlmZMKKKxAS0jGaZ/dr8tihgTh410fsAlojdwVKo+eHYrAJ1tmB5E4DgIbr9F2CN760+pZcbMS3fo4cclztRr0HZrMnODtex5Ic4/M4cC6pUXyd/ulipBxGXg6rdXUWjotArY8Kl77d5p98bf5Ekee/cH2D7X9sMMIzaWCzZFmTLbynrOHBAtJ+GcLC5eALgXaJNBE95mmXAMp3uD+6MsI3YK3oMH5OkePBWW2FDMfdaaNuZmIFTngEAAQA=", Base64.getEncoder().encodeToString(AdbPublicKey.encodeStruct(pub)))
    }

    @Test fun structInvariants() {
        val b = AdbPublicKey.encodeStruct(pub)
        assertEquals(524, b.size)
        fun le32(o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
        assertEquals(64, le32(0))
        assertEquals(0xFFFFFFFFL, (le32(4) * (n.toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL)      // n0inv * n[0] = -1 mod 2^32
        assertEquals(n, BigInteger(1, b.copyOfRange(8, 264).reversedArray()))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), BigInteger(1, b.copyOfRange(264, 520).reversedArray()))
        assertEquals(65537L, le32(520))
    }

    @Test fun nameIsAppended() {
        val s = String(AdbPublicKey.encodeWithName(pub, "mbot-access-mobile@Pixel_7"), Charsets.UTF_8)
        assertTrue(s.endsWith(" mbot-access-mobile@Pixel_7"))
        assertEquals(1, s.count { it == ' ' })
    }
}

class X509BuilderTest {
    @Test fun selfSignedCertificateParsesAndVerifies() {
        val keys: KeyPair = AdbKeyGen.generate()
        val der = X509Builder.selfSigned(keys)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(keys.public)                              // la firma SHA256withRSA es valida
        cert.checkValidity(java.util.Date())
        assertEquals("CN=00", cert.subjectX500Principal.name)
        assertEquals(3, cert.version)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertArrayEquals(keys.public.encoded, cert.publicKey.encoded)
    }

    @Test fun derLengthEncodingBoundaries() {
        assertArrayEquals(byteArrayOf(0x04, 0x01, 0x00), X509Builder.der(0x04, byteArrayOf(0)))
        val l200 = X509Builder.der(0x04, ByteArray(200))
        assertEquals(0x81.toByte(), l200[1]); assertEquals(200, l200[2].toInt() and 0xFF)
        val l300 = X509Builder.der(0x04, ByteArray(300))
        assertEquals(0x82.toByte(), l300[1]); assertEquals(1, l300[2].toInt()); assertEquals(44, l300[3].toInt())
    }
}

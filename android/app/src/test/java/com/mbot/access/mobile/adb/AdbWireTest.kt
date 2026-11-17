package com.mbot.access.mobile.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class AdbWireTest {
    @Test fun commandConstantsSpellTheirNames() {
        assertEquals("CNXN", AdbWire.commandName(AdbWire.A_CNXN))
        assertEquals("OPEN", AdbWire.commandName(AdbWire.A_OPEN))
        assertEquals("OKAY", AdbWire.commandName(AdbWire.A_OKAY))
        assertEquals("CLSE", AdbWire.commandName(AdbWire.A_CLSE))
        assertEquals("WRTE", AdbWire.commandName(AdbWire.A_WRTE))
        assertEquals("AUTH", AdbWire.commandName(AdbWire.A_AUTH))
        assertEquals("STLS", AdbWire.commandName(AdbWire.A_STLS))
    }

    @Test fun headerLayoutLittleEndianWithMagicAndChecksum() {
        val b = AdbWire.encode(AdbWire.A_OPEN, 1, 0, "shell:id\u0000".toByteArray())
        assertEquals(24 + 9, b.size)
        // command "OPEN" little-endian = 4f 50 45 4e ; arg0 = 1 ; arg1 = 0 ; len = 9 ; checksum = suma de bytes ; magic = ~command
        assertArrayEquals(byteArrayOf(0x4f, 0x50, 0x45, 0x4e), b.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), b.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(9, 0, 0, 0), b.copyOfRange(12, 16))
        val sum = "shell:id".toByteArray().sumOf { it.toInt() and 0xFF }
        assertEquals(sum, (b[16].toInt() and 0xFF) or ((b[17].toInt() and 0xFF) shl 8))
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 0xAF.toByte(), 0xBA.toByte(), 0xB1.toByte()), b.copyOfRange(20, 24))
    }

    @Test fun roundTrip() {
        val data = ByteArray(1000) { it.toByte() }
        val m = AdbWire.read(ByteArrayInputStream(AdbWire.encode(AdbWire.A_WRTE, 7, 9, data)))
        assertEquals(AdbWire.A_WRTE, m.command); assertEquals(7, m.arg0); assertEquals(9, m.arg1)
        assertArrayEquals(data, m.data)
    }

    @Test fun rejectsBadMagicAndOversize() {
        val b = AdbWire.encode(AdbWire.A_OKAY, 1, 2)
        b[20] = 0
        try { AdbWire.read(ByteArrayInputStream(b)); fail("debia fallar") } catch (_: IOException) {}
        val big = AdbWire.encode(AdbWire.A_WRTE, 1, 2, ByteArray(100))
        try { AdbWire.read(ByteArrayInputStream(big), 50); fail("debia fallar") } catch (_: IOException) {}
        try { AdbWire.read(ByteArrayInputStream(ByteArray(10))); fail("debia fallar") } catch (_: Exception) {}
    }

    @Test fun clientBannerHasNoDelayedAck() {
        val s = String(AdbWire.clientBanner(), Charsets.US_ASCII)
        assertEquals("host::features=shell_v2,cmd\u0000", s)
    }
}

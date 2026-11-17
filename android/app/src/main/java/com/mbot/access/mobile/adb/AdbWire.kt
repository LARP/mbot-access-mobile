package com.mbot.access.mobile.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Mensajes del protocolo adb (adb.h): cabecera de 24 bytes little-endian (6 x u32) + carga. */
object AdbWire {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541
    const val A_STLS = 0x534C5453
    const val A_VERSION = 0x01000001          // sin checksum
    const val A_STLS_VERSION = 0x01000000
    const val MAX_PAYLOAD = 256 * 1024
    const val HEADER = 24

    class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray) {
        val name: String get() = commandName(command)
    }

    fun commandName(c: Int): String = String(byteArrayOf(c.toByte(), (c shr 8).toByte(), (c shr 16).toByte(), (c shr 24).toByte()), Charsets.US_ASCII)

    private fun le(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte(); b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
    }

    private fun rd(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    /** Suma de bytes de la carga (el checksum antiguo; adbd ya no lo comprueba desde A_VERSION_SKIP_CHECKSUM pero se envia igual). */
    fun checksum(data: ByteArray): Int { var s = 0; for (x in data) s += x.toInt() and 0xFF; return s }

    fun encode(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val h = ByteArray(HEADER)
        le(h, 0, command); le(h, 4, arg0); le(h, 8, arg1); le(h, 12, data.size); le(h, 16, checksum(data)); le(h, 20, command xor -1)
        return h + data
    }

    fun write(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        out.write(encode(command, arg0, arg1, data))
        out.flush()
    }

    /** Lee un mensaje; valida magic y tamano. */
    fun read(inp: InputStream, maxPayload: Int = MAX_PAYLOAD): Message {
        val d = DataInputStream(inp)
        val h = ByteArray(HEADER)
        try { d.readFully(h) } catch (e: EOFException) { throw java.io.IOException("adb: conexion cerrada") }
        val cmd = rd(h, 0)
        if (rd(h, 20) != (cmd xor -1)) throw java.io.IOException("adb: cabecera invalida (magic)")
        val n = rd(h, 12)
        if (n < 0 || n > maxPayload) throw java.io.IOException("adb: carga demasiado grande: $n")
        val data = ByteArray(n)
        d.readFully(data)
        return Message(cmd, rd(h, 4), rd(h, 8), data)
    }

    /** Banner de CNXN del cliente: "host::features=..." con NUL final. Sin delayed_ack, para que OPEN lleve arg1=0. */
    fun clientBanner(): ByteArray = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.US_ASCII)
}

package com.mbot.access.mobile.adb

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyStore
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Identidad de adb de la app: par RSA 2048, certificado autofirmado y clave publica en formato adb. */
class AdbIdentity(val keys: KeyPair, val certDer: ByteArray, val adbPublicKey: ByteArray)

/**
 * Guarda la clave privada RSA del cliente adb cifrada (AES-256-GCM) con una clave que vive en el AndroidKeyStore (no exportable,
 * con hardware si existe) y el fichero cifrado en el almacenamiento privado de la app. La clave RSA en si es de software porque el
 * TLS 1.3 de adb exige firmas RSA-PSS que el Keystore solo da en algunos equipos.
 */
class AdbKeyVault(private val ctx: Context) {
    companion object {
        private const val ALIAS = "mbot.adb.wrap"
        private const val FILE = "adb_key.bin"
    }

    private val file get() = File(ctx.filesDir, FILE)

    fun exists(): Boolean = file.isFile

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }

    private fun save(pkcs8: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val blob = c.iv + c.doFinal(pkcs8)
        file.writeBytes(blob)
    }

    private fun loadPkcs8(): ByteArray {
        val blob = file.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Carga la identidad; si no existe la crea. El nombre va en la clave publica ("mbot-access-mobile@modelo"). */
    fun loadOrCreate(): AdbIdentity {
        val kf = KeyFactory.getInstance("RSA")
        val keys: KeyPair = if (exists()) {
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(loadPkcs8())) as RSAPrivateCrtKey
            val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
            KeyPair(pub, priv)
        } else {
            AdbKeyGen.generate().also { save(it.private.encoded) }
        }
        val name = "mbot-access-mobile@" + Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return AdbIdentity(keys, X509Builder.selfSigned(keys), AdbPublicKey.encodeWithName(keys.public as RSAPublicKey, name))
    }

    fun delete() {
        file.delete()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
    }
}

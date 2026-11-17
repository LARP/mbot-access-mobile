package com.mbot.access.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.mbot.access.mobile.helper.HelperProto

/**
 * Receptor del Binder del asistente (como en Shizuku). Esta exportado porque el asistente corre con otra UID (shell),
 * pero SOLO acepta la llamada `binder` si el kernel dice que quien llama es shell (2000) o root: cualquier otra app es rechazada.
 */
class HelperProvider : ContentProvider() {
    private val appBinder = Binder()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperProto.METHOD_BINDER) return null
        val uid = Binder.getCallingUid()
        if (!HelperProto.serverTrusted(uid)) {
            Log.w(MbotA11yService.TAG, "proveedor del asistente: llamada rechazada de la uid $uid")
            return Bundle().apply { putBoolean("ok", false); putString("error", "uid no autorizada") }
        }
        extras?.classLoader = HelperProvider::class.java.classLoader
        val b = extras?.getBinder(HelperProto.EXTRA_BINDER) ?: return Bundle().apply { putBoolean("ok", false); putString("error", "sin binder") }
        HelperClient.attach(b)
        context?.let { Advanced.setDesired(it, true) }      // el usuario arranco el asistente: tras un reinicio se reintenta (forma B, Android 11+)
        return Bundle().apply { putBoolean("ok", true); putBinder(HelperProto.EXTRA_APP_BINDER, appBinder) }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

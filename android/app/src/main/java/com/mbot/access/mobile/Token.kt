package com.mbot.access.mobile

import android.content.Context
import java.security.SecureRandom

/** Token Bearer del servidor: 24 caracteres url-safe, generado la primera vez y guardado en SharedPreferences. */
object Token {
    private const val PREFS = "mbot"
    private const val KEY = "token"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun get(ctx: Context): String {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(KEY, null)?.let { if (it.length >= 24) return it }
        return regenerate(ctx)
    }

    fun regenerate(ctx: Context): String {
        val rnd = SecureRandom()
        val t = buildString { repeat(24) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, t).putBoolean("legacy_revoked", false).commit()
        return t
    }
}

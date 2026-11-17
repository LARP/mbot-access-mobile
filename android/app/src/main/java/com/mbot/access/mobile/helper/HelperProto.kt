package com.mbot.access.mobile.helper

/**
 * Protocolo app <-> asistente (helper). Lo comparten los dos lados.
 *
 * Transporte: Binder (como Shizuku). El asistente (uid shell) entrega su Binder a la app llamando al ContentProvider
 * `AUTHORITY` de la app (el proveedor solo acepta la llamada si Binder.getCallingUid() es shell/root). Despues la app hace
 * transact(CODE_CALL): peticion = interface token + JSON (String); respuesta = excepcion/NoException + JSON (String) + bytes opcionales.
 * Seguridad: el kernel (binder) pone la UID del que llama; el asistente solo atiende a la UID de la app.
 * (Un socket local abstracto no sirve: SELinux de Android 9 prohibe untrusted_app -> shell connectto.)
 */
object HelperProto {
    const val AUTHORITY = "com.mbot.access.mobile.helper"
    const val DESCRIPTOR = "com.mbot.access.mobile.IHelper"
    const val CODE_CALL = 1
    const val METHOD_BINDER = "binder"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_APP_BINDER = "app_binder"
    const val VERSION = "1.0.0"
    const val PROTO = 2
    const val MAX_BIN = 700 * 1024        // un Parcel de binder admite ~1 MB: capturas por encima se recomprimen
    const val UID_SHELL = 2000
    const val UID_ROOT = 0

    /** Quien puede hablar con el asistente: solo la UID de la app. Decision pura (con prueba unitaria). */
    fun clientAllowed(peerUid: Int, appUid: Int): Boolean = appUid > 0 && peerUid == appUid

    /** Quien puede entregar el Binder a la app: shell o root (el asistente lo lanza adb). */
    fun serverTrusted(peerUid: Int): Boolean = peerUid == UID_SHELL || peerUid == UID_ROOT

    /** Teclas por nombre (ENTER, WAKE, ...) o numero. -1 si no se reconoce. */
    fun keyCode(name: String): Int {
        val k = name.trim().uppercase().removePrefix("KEYCODE_")
        k.toIntOrNull()?.let { return it }
        return NAMES[k] ?: -1
    }

    private val NAMES = mapOf(
        "HOME" to 3, "BACK" to 4, "CALL" to 5, "ENDCALL" to 6, "DPAD_UP" to 19, "DPAD_DOWN" to 20, "DPAD_LEFT" to 21,
        "DPAD_RIGHT" to 22, "DPAD_CENTER" to 23, "VOLUME_UP" to 24, "VOLUME_DOWN" to 25, "POWER" to 26, "CAMERA" to 27,
        "CLEAR" to 28, "TAB" to 61, "SPACE" to 62, "ENTER" to 66, "DEL" to 67, "BACKSPACE" to 67, "FORWARD_DEL" to 112,
        "MENU" to 82, "SEARCH" to 84, "PAGE_UP" to 92, "PAGE_DOWN" to 93, "ESCAPE" to 111, "ESC" to 111,
        "MOVE_HOME" to 122, "MOVE_END" to 123, "APP_SWITCH" to 187, "RECENTS" to 187, "SLEEP" to 223, "WAKE" to 224, "WAKEUP" to 224,
        "MEDIA_PLAY_PAUSE" to 85, "MEDIA_NEXT" to 87, "MEDIA_PREVIOUS" to 88, "NOTIFICATION" to 83,
        "BRIGHTNESS_UP" to 221, "BRIGHTNESS_DOWN" to 220, "MUTE" to 164, "PASTE" to 279, "COPY" to 278, "CUT" to 277
    )
}

package com.mbot.access.mobile

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mbot.access.mobile.adb.WirelessAdb

/** Recibe el codigo de 6 digitos escrito en la notificacion de emparejamiento (RemoteInput). No exportado. */
class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WirelessAdb.ACTION_CODE) return
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(WirelessAdb.KEY_CODE)?.toString() ?: return
        WirelessAdb.submitCode(context, code)
    }
}

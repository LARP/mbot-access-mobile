package com.mbot.access.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Botones "Detener" / "Reanudar" de la notificacion. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Indicator.ACTION_STOP -> AgentState.pause(AgentState.PAUSE_USER)
            Indicator.ACTION_RESUME -> AgentState.resume()
        }
        if (MbotA11yService.instance == null) Indicator.cancelNotification(context)   // sin servicio nadie actualiza el aviso
    }
}

/**
 * Aviso visible mientras el agente actua: una pastilla "Agente trabajando · Detener" arriba (TYPE_ACCESSIBILITY_OVERLAY, no
 * necesita SYSTEM_ALERT_WINDOW) y una notificacion con la misma accion. Se oculta ~5 s despues del ultimo comando.
 * Durante los gestos la pastilla deja pasar los toques para no interceptar los nuestros.
 */
class Indicator(private val svc: MbotA11yService) {
    companion object {
        const val ACTION_STOP = "com.mbot.access.mobile.STOP"
        const val ACTION_RESUME = "com.mbot.access.mobile.RESUME"
        private const val CHANNEL = "mbot_agent"
        private const val NOTIF_ID = 4242
        private const val HIDE_MS = 5000L

        fun cancelNotification(ctx: Context) {
            try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pill: LinearLayout? = null
    private var label: TextView? = null
    private var action: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var shown = false
    private val hide = Runnable { hidePill(); if (!AgentState.paused) cancelNotification(svc) }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), svc.resources.displayMetrics).toInt()

    private fun build() {
        val bg = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xEE202124.toInt()) }
        val l = TextView(svc).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        val a = TextView(svc).apply {
            setTextColor(0xFFFFB74D.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener {
                if (AgentState.paused) AgentState.resume() else AgentState.pause(AgentState.PAUSE_USER)
            }
        }
        pill = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dp(14), dp(2), dp(10), dp(2))
            addView(l); addView(a)
        }
        label = l; action = a
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(2) }
    }

    private fun showPill() {
        if (pill == null) build()
        val p = pill ?: return
        if (AgentState.paused) { label?.text = "Agente en pausa ·"; action?.text = "Reanudar" }
        else { label?.text = "Agente trabajando ·"; action?.text = "Detener" }
        if (!shown) {
            try { wm.addView(p, lp); shown = true } catch (e: Exception) { android.util.Log.w(MbotA11yService.TAG, "overlay: ${e.message}") }
        }
    }

    private fun hidePill() {
        val p = pill
        if (shown && p != null) { try { wm.removeView(p) } catch (_: Exception) {}; shown = false }
    }

    /** Llamar al empezar y al terminar cada comando que actua. */
    fun pulse() {
        main.post {
            showPill()
            postNotification()
            main.removeCallbacks(hide)
            main.postDelayed(hide, HIDE_MS)
        }
    }

    /** La pausa cambio (boton, notificacion o pausa automatica): actualiza pastilla y notificacion. */
    fun refresh() {
        main.post {
            if (AgentState.paused) {
                showPill(); postNotification()
                main.removeCallbacks(hide)
                main.postDelayed(hide, HIDE_MS * 2)
            } else {
                if (shown) showPill()    // actualiza el texto si sigue visible
                cancelNotification(svc)
                main.removeCallbacks(hide)
                main.postDelayed(hide, 1500)
            }
        }
    }

    /** Deja pasar (o no) los toques a traves de la pastilla; se espera a que el cambio se aplique. */
    fun passThrough(on: Boolean) {
        val latch = CountDownLatch(1)
        main.post {
            try {
                val p = pill; val l = lp
                if (shown && p != null && l != null) {
                    l.flags = if (on) l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    wm.updateViewLayout(p, l)
                    p.visibility = if (on) View.INVISIBLE else View.VISIBLE   // fuera de la vista mientras dura el gesto
                }
            } catch (_: Exception) {}
            latch.countDown()
        }
        latch.await(300, TimeUnit.MILLISECONDS)
    }

    private fun postNotification() {
        try {
            val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agente activo", NotificationManager.IMPORTANCE_LOW))
            }
            val act = if (AgentState.paused) ACTION_RESUME else ACTION_STOP
            val pi = PendingIntent.getBroadcast(svc, 0, Intent(svc, ControlReceiver::class.java).setAction(act),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(svc, CHANNEL) else Notification.Builder(svc)
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (AgentState.paused) "Agente en pausa" else "Agente trabajando")
                .setContentText(if (AgentState.paused) AgentState.pauseReason else "Un agente esta usando tu telefono")
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, if (AgentState.paused) "Reanudar" else "Detener", pi).build())
            nm.notify(NOTIF_ID, b.build())
        } catch (e: Exception) {
            android.util.Log.w(MbotA11yService.TAG, "notificacion: ${e.message}")
        }
    }

    fun destroy() {
        main.removeCallbacks(hide)
        main.post { hidePill() }
        cancelNotification(svc)
    }
}

package com.mbot.access.mobile.helper

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/** Entrada real: IInputManager.injectInputEvent (la misma via que `input tap`), por reflexion. */
object Inj {
    private const val MODE_WAIT_FOR_FINISH = 2

    private val mgr: Any by lazy { Reflect.iface("android.hardware.input.IInputManager", Reflect.service("input")) }
    private val inject: Method by lazy {
        Reflect.method(mgr.javaClass, "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType!!)
            ?: throw NoSuchMethodException("IInputManager.injectInputEvent")
    }
    private val setDisplayId: Method? by lazy { Reflect.method(InputEvent::class.java, "setDisplayId", Int::class.javaPrimitiveType!!) }

    fun available(): Boolean = try { inject; true } catch (_: Throwable) { false }
    fun displaySupport(): Boolean = setDisplayId != null

    private fun send(ev: InputEvent, display: Int): Boolean {
        if (display != 0) {
            val m = setDisplayId ?: throw UnsupportedOperationException("InputEvent.setDisplayId no existe en este Android")
            m.invoke(ev, display)
        }
        return inject.invoke(mgr, ev, MODE_WAIT_FOR_FINISH) as Boolean
    }

    private fun motion(down: Long, action: Int, x: Float, y: Float): MotionEvent {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        return e
    }

    fun tap(x: Float, y: Float, display: Int = 0, holdMs: Long = 20): Boolean {
        val down = SystemClock.uptimeMillis()
        val d = motion(down, MotionEvent.ACTION_DOWN, x, y)
        var ok = send(d, display)
        d.recycle()
        if (holdMs > 0) SystemClock.sleep(holdMs)
        val u = motion(down, MotionEvent.ACTION_UP, x, y)
        ok = send(u, display) && ok
        u.recycle()
        return ok
    }

    /** Deslizamiento lineal en pasos de ~16 ms (como un dedo). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, display: Int = 0): Boolean {
        val dur = ms.coerceIn(1, 60000)
        val down = SystemClock.uptimeMillis()
        val d = motion(down, MotionEvent.ACTION_DOWN, x1, y1)
        var ok = send(d, display)
        d.recycle()
        val steps = (dur / 16).toInt().coerceIn(1, 3000)
        for (i in 1..steps) {
            val due = down + dur * i / steps
            val wait = due - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val f = i.toFloat() / steps
            val mv = motion(down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
            ok = send(mv, display) && ok
            mv.recycle()
        }
        val u = motion(down, MotionEvent.ACTION_UP, x2, y2)
        ok = send(u, display) && ok
        u.recycle()
        return ok
    }

    fun key(code: Int, display: Int = 0, longPress: Boolean = false): Boolean {
        val down = SystemClock.uptimeMillis()
        fun ev(action: Int, repeat: Int = 0, flags: Int = 0) = KeyEvent(down, SystemClock.uptimeMillis(), action, code, repeat, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
        var ok = send(ev(KeyEvent.ACTION_DOWN), display)
        if (longPress) {
            SystemClock.sleep(600)
            ok = send(ev(KeyEvent.ACTION_DOWN, 1, KeyEvent.FLAG_LONG_PRESS), display) && ok
        } else SystemClock.sleep(10)
        return send(ev(KeyEvent.ACTION_UP), display) && ok
    }
}

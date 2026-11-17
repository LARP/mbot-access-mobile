package com.mbot.access.mobile

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Datos para volver a encontrar un nodo en una lectura posterior. Sin texto de campos de contrasena. */
class Loc(val rid: String, val text: String, val desc: String, val cls: String, val pkg: String, val bounds: Rect)

class Entry(val node: AccessibilityNodeInfo, val loc: Loc)

class Snapshot(val seq: Int, val vd: Boolean = false) {
    val entries = HashMap<String, Entry>()
}

/** Las ultimas 4 lecturas (/v1/dump): id "eN" -> nodo + localizador. */
object Snapshots {
    private const val KEEP = 4
    private val list = ArrayList<Snapshot>()
    private var seq = 0

    @Synchronized
    fun begin(vd: Boolean = false): Snapshot = Snapshot(++seq, vd)

    @Synchronized
    fun commit(s: Snapshot) {
        list.add(s)
        while (list.size > KEEP) list.removeAt(0)
    }

    @Synchronized
    fun latest(): Snapshot? = list.lastOrNull()

    @Synchronized
    fun get(seq: Int): Snapshot? = list.firstOrNull { it.seq == seq }
}

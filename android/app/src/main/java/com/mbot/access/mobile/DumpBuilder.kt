package com.mbot.access.mobile

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Genera el arbol de pantalla en el MISMO formato XML que `uiautomator dump`, para que el parser de
 * phone_agent.py (read/compact_read) funcione sin cambios. Primero va la ventana activa (el paquete del
 * primer nodo se compara con el foco), despues el resto de ventanas de apps/dialogos de arriba a abajo.
 */
object DumpBuilder {
    private const val MAX_NODES = 8000

    fun escape(s: CharSequence?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c == '\n' -> sb.append("&#10;")
                c == '\r' -> sb.append("&#13;")
                c == '\t' -> sb.append("&#9;")
                c.code < 0x20 || c.code == 0x7F -> {}                       // caracteres de control no validos en XML 1.0
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) || c.code == 0xFFFE || c.code == 0xFFFF -> {}   // suelto/invalido
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Raices de las ventanas que se leen (activa primero), sin nuestra propia app. El llamador no las recicla (el arbol se usa tras volcar). */
    fun roots(svc: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val wins = try { svc.windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        val ordered = wins
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }
                .thenByDescending { it.isFocused }
                .thenByDescending { it.layer })
        for (w in ordered) {
            val root = try { w.root } catch (e: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            // no volcamos nuestra propia app, ni la barra de estado/navegacion salvo que sea la ventana activa (panel de notificaciones)
            val skip = pkg == svc.packageName || (pkg == "com.android.systemui" && !w.isActive && !w.isFocused)
            if (!skip) out.add(root) else recycle(root)
        }
        // si no hay nada que mostrar, probar la raiz activa clasica (algunos equipos no listan ventanas)
        if (out.isEmpty()) {
            val root = try { svc.rootInActiveWindow } catch (e: Exception) { null }
            if (root != null) {
                if (root.packageName?.toString() != svc.packageName) out.add(root) else recycle(root)
            }
        }
        return out
    }

    /** Volcado XML + registro de la lectura (ids eN por nodo "listado") en [Snapshots]. */
    fun build(svc: AccessibilityService, rotation: Int, fixedRoots: List<AccessibilityNodeInfo>? = null): String {
        val snap = Snapshots.begin(vd = fixedRoots != null)
        val sb = StringBuilder(16 * 1024)
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
        sb.append("<hierarchy rotation=\"").append(rotation).append("\" snap=\"").append(snap.seq).append("\">")
        val counter = intArrayOf(0)
        val eids = intArrayOf(0)
        var idx = 0
        for (root in fixedRoots ?: roots(svc)) {
            val kept = node(sb, root, idx++, counter, snap, eids)
            if (!kept) recycle(root)
        }
        sb.append("</hierarchy>")
        Snapshots.commit(snap)
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo) {
        try { n.recycle() } catch (_: Exception) {}
    }

    /** Un nodo se "lista" (recibe id eN) con los mismos criterios con que phone_agent.py lo muestra en `look`. */
    private fun listed(n: AccessibilityNodeInfo, pwd: Boolean): Boolean {
        val t = !pwd && !n.text.isNullOrEmpty()
        val d = !n.contentDescription.isNullOrEmpty()
        return t || d || n.isClickable || n.isLongClickable || n.isEditable || (n.isFocusable && d)
    }

    /** Devuelve true si el nodo se guardo en la lectura (entonces NO se recicla: lo usa tap_node). */
    private fun node(sb: StringBuilder, n: AccessibilityNodeInfo, index: Int, counter: IntArray, snap: Snapshot, eids: IntArray): Boolean {
        if (counter[0]++ > MAX_NODES) return false
        val r = Rect()
        n.getBoundsInScreen(r)
        val pwd = n.isPassword
        var eid: String? = null
        if (listed(n, pwd)) {
            eid = "e" + (++eids[0])
            snap.entries[eid] = Entry(n, Loc(n.viewIdResourceName ?: "", if (pwd) "" else n.text?.toString() ?: "",
                n.contentDescription?.toString() ?: "", n.className?.toString() ?: "", n.packageName?.toString() ?: "", Rect(r)))
        }
        sb.append("<node index=\"").append(index).append('"')
        sb.append(" text=\"").append(if (pwd) "" else escape(n.text)).append('"')
        sb.append(" resource-id=\"").append(escape(n.viewIdResourceName)).append('"')
        sb.append(" class=\"").append(escape(n.className)).append('"')
        sb.append(" package=\"").append(escape(n.packageName)).append('"')
        sb.append(" content-desc=\"").append(escape(n.contentDescription)).append('"')
        sb.append(" checkable=\"").append(n.isCheckable).append('"')
        sb.append(" checked=\"").append(n.isChecked).append('"')
        sb.append(" clickable=\"").append(n.isClickable).append('"')
        sb.append(" enabled=\"").append(n.isEnabled).append('"')
        sb.append(" focusable=\"").append(n.isFocusable).append('"')
        sb.append(" focused=\"").append(n.isFocused).append('"')
        sb.append(" scrollable=\"").append(n.isScrollable).append('"')
        sb.append(" long-clickable=\"").append(n.isLongClickable).append('"')
        sb.append(" password=\"").append(pwd).append('"')
        sb.append(" selected=\"").append(n.isSelected).append('"')
        sb.append(" bounds=\"[").append(r.left).append(',').append(r.top).append("][").append(r.right).append(',').append(r.bottom).append("]\"")
        if (eid != null) sb.append(" eid=\"").append(eid).append('"')
        val count = n.childCount
        if (count == 0) {
            sb.append(" />")
            return eid != null
        }
        sb.append('>')
        var ci = 0
        for (i in 0 until count) {
            val c = try { n.getChild(i) } catch (e: Exception) { null } ?: continue
            // como uiautomator: se omiten los nodos no visibles para el usuario (fuera de pantalla en listas, etc.)
            var kept = false
            if (c.isVisibleToUser) kept = node(sb, c, ci++, counter, snap, eids)
            if (!kept) recycle(c)
        }
        sb.append("</node>")
        return eid != null
    }
}

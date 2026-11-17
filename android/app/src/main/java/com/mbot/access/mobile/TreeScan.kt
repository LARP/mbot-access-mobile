package com.mbot.access.mobile

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Recorridos ligeros del arbol: huella (para detectar cambios), busqueda del elemento bajo un punto y relocalizacion por localizador. */
object TreeScan {
    private const val MAX_NODES = 3500
    private const val MAX_DEPTH = 60

    /** Lo que hay bajo un punto: textos/descripciones e ids del nodo mas profundo y de su ancestro clicable. */
    class Hit(val texts: List<String>, val ids: List<String>) {
        val label: String get() = texts.firstOrNull { it.isNotBlank() } ?: ids.firstOrNull { it.isNotBlank() }?.substringAfter('/') ?: "(sin etiqueta)"
    }

    class Result(val hash: Long, val hit: Hit?)

    private class Path {
        val text = arrayOfNulls<String>(MAX_DEPTH + 1)
        val desc = arrayOfNulls<String>(MAX_DEPTH + 1)
        val rid = arrayOfNulls<String>(MAX_DEPTH + 1)
        val click = BooleanArray(MAX_DEPTH + 1)
    }

    private fun mix(h: Long, v: Long): Long = (h xor v) * 1099511628211L
    private fun mixS(h: Long, s: CharSequence?): Long {
        var x = mix(h, 0x9E37L)
        if (s != null) for (i in 0 until s.length) x = mix(x, s[i].code.toLong())
        return x
    }

    /** Huella del arbol visible y, si px >= 0, el elemento bajo (px, py). */
    fun scan(svc: AccessibilityService, px: Int = -1, py: Int = -1): Result {
        var h = 1469598103934665603L
        var hit: Hit? = null
        val cnt = intArrayOf(0)
        val path = Path()
        val r = Rect()

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (cnt[0]++ > MAX_NODES || depth > MAX_DEPTH) return
            n.getBoundsInScreen(r)
            val pwd = n.isPassword
            val text = if (pwd) null else n.text
            h = mixS(h, n.className)
            h = mixS(h, text)
            h = mixS(h, n.contentDescription)
            h = mixS(h, n.viewIdResourceName)
            h = mix(h, r.left.toLong()); h = mix(h, r.top.toLong()); h = mix(h, r.right.toLong()); h = mix(h, r.bottom.toLong())
            h = mix(h, (if (n.isChecked) 1L else 0L) + (if (n.isSelected) 2L else 0L) + (if (n.isEnabled) 4L else 0L) + (if (n.isFocused) 8L else 0L))
            if (px >= 0) {
                path.text[depth] = text?.toString(); path.desc[depth] = n.contentDescription?.toString()
                path.rid[depth] = n.viewIdResourceName; path.click[depth] = n.isClickable
                if (r.contains(px, py)) {
                    val texts = ArrayList<String>(); val ids = ArrayList<String>()
                    texts.add(path.text[depth] ?: ""); texts.add(path.desc[depth] ?: ""); ids.add(path.rid[depth] ?: "")
                    var k = depth
                    while (k >= 0 && !path.click[k]) k--
                    if (k >= 0 && k != depth) { texts.add(path.text[k] ?: ""); texts.add(path.desc[k] ?: ""); ids.add(path.rid[k] ?: "") }
                    hit = Hit(texts.filter { it.isNotBlank() }, ids.filter { it.isNotBlank() })
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                if (c.isVisibleToUser) walk(c, depth + 1)
            }
        }
        for (root in DumpBuilder.roots(svc)) walk(root, 0)
        return Result(h, hit)
    }

    fun hash(svc: AccessibilityService): Long = scan(svc).hash

    private fun sim(a: String, b: String): Boolean = a.isNotEmpty() && a == b

    /** Busca en el arbol actual el nodo que mejor encaja con el localizador (id, texto, descripcion; desempate por cercania). */
    fun relocate(svc: AccessibilityService, loc: Loc): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestDist = Int.MAX_VALUE
        val cnt = intArrayOf(0)
        val r = Rect()
        val cx = loc.bounds.centerX(); val cy = loc.bounds.centerY()

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (cnt[0]++ > MAX_NODES * 2 || depth > MAX_DEPTH) return
            val pwd = n.isPassword
            var score = 0
            if (sim(loc.rid, n.viewIdResourceName ?: "")) score += 4
            if (!pwd && sim(loc.text, n.text?.toString() ?: "")) score += 3
            if (sim(loc.desc, n.contentDescription?.toString() ?: "")) score += 3
            if (score >= 3) {
                if (loc.cls.isNotEmpty() && loc.cls == n.className?.toString()) score += 1
                if (loc.pkg.isNotEmpty() && loc.pkg == n.packageName?.toString()) score += 1
                n.getBoundsInScreen(r)
                val dist = Math.abs(r.centerX() - cx) + Math.abs(r.centerY() - cy)
                if (score > bestScore || (score == bestScore && dist < bestDist)) { best = n; bestScore = score; bestDist = dist }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                if (c.isVisibleToUser) walk(c, depth + 1)
            }
        }
        for (root in DumpBuilder.roots(svc)) walk(root, 0)
        return best
    }

    /** Texto visible del nodo; si no tiene, el de sus descendientes cercanos (botones cuyo texto esta en un hijo). */
    fun labelOf(n: AccessibilityNodeInfo): String {
        n.text?.toString()?.takeIf { it.isNotBlank() && !n.isPassword }?.let { return it }
        n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = ArrayList<String>()
        fun sub(x: AccessibilityNodeInfo, d: Int) {
            if (d > 3 || parts.size >= 6) return
            for (i in 0 until x.childCount) {
                val c = try { x.getChild(i) } catch (_: Exception) { null } ?: continue
                val t = (if (c.isPassword) null else c.text?.toString())?.takeIf { it.isNotBlank() } ?: c.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (t != null) parts.add(t)
                sub(c, d + 1)
            }
        }
        sub(n, 0)
        return parts.joinToString(" ")
    }
}

package com.mbot.access.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Resultado de /v1/shot: bytes JPEG + factor de escala, o un error. */
class ShotResult(val jpeg: ByteArray?, val scale: Double, val status: Int, val error: String?)

/** Lo que el servidor HTTP sabe hacer con el telefono. Cada metodo es seguro de llamar desde hilos de trabajo. */
class AgentApi(private val svc: MbotA11yService) {
    private val cmdLock = Any()
    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val VERSION = "1.0.0"
        const val API = 3
        /** Capacidades del protocolo (las anuncia /v1/capabilities; el PC las muestra con `phone N caps`). */
        val FEATURES = listOf("ids", "wait_change", "notifs", "pair", "risk")
        val COMMANDS = listOf("tap", "tap_node", "swipe", "key", "text", "launch", "open", "apps", "info")
        /** Comandos nuevos del modo avanzado (solo funcionan con el asistente vivo; se anuncian en caps.advanced_commands). */
        val ADV_COMMANDS = listOf("helper_quit", "helper_probe", "clip_get", "clip_set", "secure_grant", "secure_revoke", "vd_start", "vd_stop", "vd_status",
            "vd_launch", "vd_tap", "vd_swipe", "vd_key", "vd_text")
        /** Comandos que actuan sobre el telefono: se bloquean en pausa (HTTP 423), muestran el aviso y esperan el cambio de pantalla. */
        /** Comandos que actuan: con la pausa activa devuelven 423. Parar (helper_quit, vd_stop, secure_revoke) y leer siguen permitidos. */
        val ACTING = setOf("tap", "tap_node", "swipe", "key", "text", "launch", "open", "vd_launch", "vd_tap", "vd_swipe", "vd_key", "vd_text",
            "clip_set", "secure_grant", "vd_start")
        /** De los anteriores, los que actuan sobre la pantalla principal: esperan a que cambie. Los vd_* actuan en otra pantalla. */
        private val WAITING = setOf("tap", "tap_node", "swipe", "key", "text", "launch", "open")
        private const val NEED_ADV = "requiere modo avanzado: arranca el asistente con 'phone N advanced start' (ver 'phone N caps')"
        private const val QUIET_MS = 300L
        private const val CAP_MS = 2500L
        private const val CAP_LAUNCH_MS = 4000L
        private const val NO_BASICO = "no disponible en modo basico"
        /** Teclas que con el asistente vivo se inyectan de verdad (en basico: ENTER solo Android 11+ y WAKE nunca). */
        private val HELPER_FIRST = setOf("ENTER", "66", "WAKE", "224", "WAKEUP", "POWER", "26")
        private val ANR_RE = Regex("no responde|isn't responding|not responding", RegexOption.IGNORE_CASE)
    }

    private fun ok(): JSONObject = JSONObject().put("ok", true)
    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    // ---------------------------------------------------------------- ping / capacidades
    fun ping(): JSONObject = JSONObject().put("app", "mbot-access-mobile").put("version", VERSION).put("api", API)

    fun capabilities(): JSONObject {
        val h = HelperClient.info()
        val feats = ArrayList(FEATURES)
        if (h != null) {
            for (f in HelperClient.features()) feats += when (f) {
                "input" -> "adv_input"; "shot" -> "adv_shot"; "display_input" -> "vd_input"; "vdisplay" -> "vd"
                else -> f      // clip, secure
            }
        }
        val helper = JSONObject().put("alive", h != null)
        if (h != null) helper.put("version", h.optString("version")).put("uid", h.optInt("uid")).put("pid", h.optInt("pid"))
            .put("uptime_ms", h.optLong("uptime_ms")).put("stale", HelperClient.stale()).put("vd", h.optJSONObject("vd") ?: JSONObject.NULL)
        val o = JSONObject()
            .put("mode", if (h != null) "advanced" else "basic").put("sdk", Build.VERSION.SDK_INT)
            .put("commands", JSONArray(COMMANDS)).put("shot", Build.VERSION.SDK_INT >= 30 || HelperClient.has("shot"))
            .put("api", API).put("paused", AgentState.paused).put("notifs", notifAccess())
            .put("version", VERSION)
            .put("permissions", JSONObject().put("accessibility", true).put("notifications", notifAccess()).put("battery_exempt", batteryExempt())
                .put("write_secure_settings", svc.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED))
            .put("features", JSONArray(feats))
            .put("helper", helper)
        if (h != null) o.put("advanced_commands", JSONArray(ADV_COMMANDS))
        return o
    }

    /** true si la app esta fuera de la optimizacion de bateria (no la matan en segundo plano). */
    private fun batteryExempt(): Boolean = try {
        (svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(svc.packageName)
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- pantalla
    @Suppress("DEPRECATION")
    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    private fun windowList(): List<AccessibilityWindowInfo> = try { svc.windows } catch (e: Exception) { emptyList() }

    private fun homePackage(): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = svc.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName
    }

    @Suppress("DEPRECATION")
    private fun release(n: AccessibilityNodeInfo?) { try { n?.recycle() } catch (_: Exception) {} }

    /** Mismas claves que Phone.state() en phone_agent.py, mas ime_visible/density/width/height. */
    fun state(): JSONObject {
        val pm = svc.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = svc.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val awake = pm.isInteractive
        val locked = km.isKeyguardLocked
        val wins = windowList()
        val active = wins.firstOrNull { it.isActive } ?: wins.firstOrNull { it.isFocused }
        var pkg: String? = null
        var raw: String? = null
        if (active != null) {
            val root = try { active.root } catch (e: Exception) { null }
            pkg = root?.packageName?.toString()
            raw = listOfNotNull(active.title?.toString(), root?.className?.toString()).joinToString(" ").ifBlank { null }
            release(root)
        } else {
            // sin ventana activa: algunos equipos solo exponen la raiz activa clasica
            val root = try { svc.rootInActiveWindow } catch (e: Exception) { null }
            if (root != null) {
                pkg = root.packageName?.toString()
                raw = root.className?.toString()
                release(root)
            }
        }
        val home = homePackage()
        val kind: String? = when {
            active == null && pkg == null -> "transicion"
            raw != null && ANR_RE.containsMatchIn(raw) -> "anr"
            else -> null
        }
        val size = realSize()
        return JSONObject()
            .put("screen_on", awake).put("locked", locked).put("secure", false)
            .put("focus_package", pkg ?: JSONObject.NULL)
            .put("home_package", home ?: JSONObject.NULL)
            .put("is_home", awake && !locked && pkg != null && pkg == home)
            .put("focus_kind", kind ?: JSONObject.NULL)
            .put("focus_raw", raw ?: JSONObject.NULL)
            .put("ime_visible", wins.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            .put("density", svc.resources.displayMetrics.densityDpi)
            .put("width", size[0]).put("height", size[1])
            .put("paused", AgentState.paused)
    }

    fun dump(): String = DumpBuilder.build(svc, rotation())

    /**
     * Raiz de la app de la pantalla virtual (Android < 11: la Accesibilidad no lista ventanas de otras pantallas, pero
     * rootInActiveWindow devuelve la de la pantalla virtual mientras esa app es la ventana ACTIVA). null si no lo es.
     */
    private fun vdRoot(): AccessibilityNodeInfo? {
        val vd = HelperClient.info()?.optJSONObject("vd") ?: return null
        if (!vd.optBoolean("active")) return null
        val pk = vd.optJSONArray("launched") ?: return null
        val pkgs = (0 until pk.length()).map { pk.optString(it) }.toSet()
        // Android 11+: la Accesibilidad ve las ventanas de todas las pantallas; se lee la de la pantalla virtual
        // aunque la ventana activa este en la pantalla real.
        if (Build.VERSION.SDK_INT >= 30) {
            val id = vd.optInt("id", -1)
            val wins = try { svc.windowsOnAllDisplays.get(id) } catch (_: Exception) { null }
            if (wins != null) {
                for (w in wins) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    if (root.packageName?.toString() in pkgs) return root
                    release(root)
                }
            }
        }
        val r = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return null
        val mine = windowList().map { it.id }.toSet()
        if (r.windowId in mine || r.packageName?.toString() !in pkgs) { release(r); return null }
        return r
    }

    /** Volcado XML de la app de la pantalla virtual, o null (con el motivo en vdError). */
    fun vdDump(): String? {
        val r = vdRoot() ?: return null
        return DumpBuilder.build(svc, rotation(), listOf(r))
    }

    fun vdDumpError(): String =
        if (!HelperClient.alive()) NEED_ADV
        else if (HelperClient.info()?.optJSONObject("vd")?.optBoolean("active") != true) "la pantalla virtual no esta activa (vd start)"
        else "no se puede leer la pantalla virtual: la app lanzada alli (vd launch) debe ser la ventana activa; en Android < 11 tocar la pantalla real la desactiva (repite vd launch)"

    // ---------------------------------------------------------------- captura (Android 11+)
    /** Captura por el asistente (SurfaceControl/screencap, o el ultimo fotograma de la pantalla virtual con target="vd"). */
    private fun helperShot(width: Int, quality: Int, target: String?): ShotResult {
        val r = HelperClient.call("shot", { put("w", width).put("q", quality); if (target != null) put("target", target) })
            ?: return ShotResult(null, 1.0, 501, if (target == "vd") "pantalla virtual: $NEED_ADV" else "captura no disponible: el asistente no responde")
        if (!r.ok || r.bin == null) return ShotResult(null, 1.0, 500, r.error)
        return ShotResult(r.bin, r.json.optDouble("scale", 1.0), 200, null)
    }

    fun shot(width: Int, quality: Int, target: String? = null): ShotResult {
        if (target == "vd") return helperShot(width, quality, "vd")
        if (Build.VERSION.SDK_INT < 30) {
            if (HelperClient.has("shot")) return helperShot(width, quality, null)
            return ShotResult(null, 1.0, 501, "captura no disponible: requiere Android 11+ (API 30), este equipo es API ${Build.VERSION.SDK_INT}")
        }
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        var errCode = 0
        val exec = Executors.newSingleThreadExecutor()
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val b = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        bmp = b?.copy(Bitmap.Config.ARGB_8888, false)
                        b?.recycle()
                        hb.close()
                    } catch (e: Exception) {
                        errCode = -1
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) { errCode = errorCode; latch.countDown() }
            })
            if (!latch.await(6, TimeUnit.SECONDS)) return ShotResult(null, 1.0, 504, "tiempo agotado al capturar")
        } finally {
            exec.shutdown()
        }
        val src = bmp
        if (src == null) {
            val msg = when (errCode) {
                3 -> "captura demasiado seguida (minimo ~1 s entre capturas)"
                6 -> "ventana protegida (FLAG_SECURE): no se puede capturar"
                2 -> "el servicio no tiene permiso de captura (reactiva la Accesibilidad)"
                else -> "captura fallida (codigo $errCode)"
            }
            if (errCode != 3 && HelperClient.has("shot")) return helperShot(width, quality, null)   // el asistente es la alternativa
            return ShotResult(null, 1.0, if (errCode == 3) 429 else 500, msg)
        }
        val w = if (width in 16 until src.width) width else src.width
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)
        val scaled = if (w != src.width) Bitmap.createScaledBitmap(src, w, h, true) else src
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(5, 95), bos)
        val scale = src.width.toDouble() / w
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return ShotResult(bos.toByteArray(), scale, 200, null)
    }

    // ---------------------------------------------------------------- comandos
    fun isActing(c: String) = c in ACTING

    /** Ejecuta un comando. Los que actuan: aviso visible, espera de cambio de pantalla (changed/ms) y aviso de riesgo. */
    fun cmd(body: JSONObject): JSONObject = synchronized(cmdLock) {
        val c = body.optString("cmd")
        val acting = c in ACTING
        val waits = c in WAITING
        if (acting) { AgentState.begin(); svc.indicator?.pulse() }
        val t0 = SystemClock.uptimeMillis()
        try {
            var h0 = 0L
            var hit: TreeScan.Hit? = null
            if (waits) {
                if (c == "tap") {
                    val x = body.optDouble("x", -1.0).toInt(); val y = body.optDouble("y", -1.0).toInt()
                    val r = TreeScan.scan(svc, x, y); h0 = r.hash; hit = r.hit
                } else h0 = TreeScan.hash(svc)
            }
            val res = try {
                when (c) {
                    "tap" -> tap(body.getDouble("x").toFloat(), body.getDouble("y").toFloat(), hit, body.optString("via").ifBlank { null })
                    "tap_node" -> tapNode(body.optString("id"), if (body.has("snap")) body.optInt("snap") else null)
                    "swipe" -> swipe(body.getDouble("x1").toFloat(), body.getDouble("y1").toFloat(),
                        body.getDouble("x2").toFloat(), body.getDouble("y2").toFloat(), body.optLong("ms", 300), body.optString("via").ifBlank { null })
                    "key" -> key(body.optString("key"))
                    "text" -> text(body.optString("text", ""))
                    "launch" -> launch(body.optString("package"))
                    "open" -> open(body.optString("url"))
                    "apps" -> apps()
                    "info" -> info()
                    "helper_quit" -> { Advanced.setDesired(svc, false); helperOp("quit") }      // `phone N advanced stop`: parar a proposito
                    "helper_probe" -> helperOp("probe") { put("bench", body.optBoolean("bench")) }
                    "clip_get" -> helperOp("clip_get")
                    "clip_set" -> helperOp("clip_set") { put("text", body.optString("text", "")) }
                    "secure_grant" -> helperOp("grant_secure")
                    "secure_revoke" -> helperOp("revoke_secure")
                    "vd_start" -> helperOp("vd_start") { put("w", body.optInt("w", 720)).put("h", body.optInt("h", 1280)).put("dpi", body.optInt("dpi", 320)) }
                    "vd_stop" -> helperOp("vd_stop") { put("close_apps", body.optBoolean("close_apps", true)) }
                    "vd_status" -> helperOp("vd_status")
                    "vd_launch" -> vdLaunch(body.optString("package"))
                    "vd_tap" -> helperOp("vd_tap") { put("x", body.getDouble("x")).put("y", body.getDouble("y")) }
                    "vd_swipe" -> helperOp("vd_swipe") { put("x1", body.getDouble("x1")).put("y1", body.getDouble("y1"))
                        .put("x2", body.getDouble("x2")).put("y2", body.getDouble("y2")).put("ms", body.optLong("ms", 300)) }
                    "vd_key" -> helperOp("vd_key") { put("key", body.optString("key")) }
                    "vd_text" -> vdText(body.optString("text", ""))
                    "" -> err("falta 'cmd'")
                    else -> err("comando desconocido: $c")
                }
            } catch (e: org.json.JSONException) {
                err("argumentos invalidos: ${e.message}")
            } catch (e: Exception) {
                err("${e.javaClass.simpleName}: ${e.message}")
            }
            if (waits && res.optBoolean("ok")) {
                val cap = if (c == "launch" || c == "open") CAP_LAUNCH_MS else CAP_MS
                res.put("changed", waitChange(h0, cap)).put("ms", SystemClock.uptimeMillis() - t0)
            }
            res
        } finally {
            if (acting) { AgentState.end(); svc.indicator?.pulse() }
        }
    }

    /**
     * Espera a que la pantalla cambie tras una accion (idea de ChangeWait de Mika): hay que ver los eventos de accesibilidad
     * en silencio ~300 ms Y que la huella del arbol sea distinta -> changed=true enseguida. Si no cambia, sigue hasta el tope
     * (2,5 s; launch/open 4 s) y devuelve false. Si los eventos no paran (animacion continua) compara al llegar al tope.
     */
    private fun waitChange(h0: Long, capMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        var lastHashAt = 0L
        var lastEvSeen = -1L
        while (true) {
            val now = SystemClock.uptimeMillis()
            val ev = AgentState.lastEvent
            val quiet = now - ev >= QUIET_MS
            if (now - start >= capMs) return TreeScan.hash(svc) != h0
            if (quiet && now - start >= 120 && (ev != lastEvSeen || now - lastHashAt >= 600)) {
                lastEvSeen = ev; lastHashAt = now
                if (TreeScan.hash(svc) != h0) return true
            }
            try { Thread.sleep(40) } catch (_: InterruptedException) { return false }
        }
    }

    /** Pasa una operacion al asistente y devuelve su respuesta tal cual (ya trae ok/error). */
    private fun helperOp(op: String, build: JSONObject.() -> Unit = {}): JSONObject {
        val r = HelperClient.call(op, build) ?: return err(NEED_ADV)
        return r.json
    }

    /** Escribe en el campo con foco de la app de la pantalla virtual (SET_TEXT por Accesibilidad). */
    private fun vdText(s: String): JSONObject {
        val r = vdRoot() ?: return err(vdDumpError())
        val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return err("no hay un campo de texto con foco en la pantalla virtual (toca el campo con vd tap @eN)")
        if (!f.isEditable) return err("el elemento con foco no es editable")
        if (f.isPassword) return err("campo de contraseña: lo escribe el usuario")
        return if (setText(f, currentText(f) + s)) ok() else err("el campo rechazo SET_TEXT")
    }

    private fun vdLaunch(pkg: String): JSONObject {
        if (pkg.isBlank()) return err("falta 'package'")
        val comp = svc.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return err("sin actividad lanzable para $pkg")
        return helperOp("vd_launch") { put("package", pkg).put("component", comp) }
    }

    /** Toque/deslizamiento por el asistente (entrada real). null = el asistente no esta: el llamador cae a la Accesibilidad. */
    private fun viaHelper(req: JSONObject, via: String?): JSONObject? {
        if (via == "a11y" || !HelperClient.has("input")) return null
        val t0 = SystemClock.uptimeMillis()
        val r = HelperClient.call(req) ?: run { HelperClient.invalidate(); return null }
        return if (r.ok && r.json.optBoolean("injected", true)) ok().put("channel", "helper").put("inject_ms", SystemClock.uptimeMillis() - t0)
        else err(if (r.ok) "el sistema rechazo el evento inyectado" else r.error)
    }

    private fun gesture(path: Path, durMs: Long): JSONObject {
        val dur = durMs.coerceIn(1, 60000)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        val latch = CountDownLatch(1)
        var done = false
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }
        val gt0 = SystemClock.uptimeMillis()
        svc.indicator?.passThrough(true)          // la pastilla deja pasar el gesto
        try {
            if (!svc.dispatchGesture(g, cb, main)) return err("gesto rechazado por el sistema")
            if (!latch.await(dur + 3000, TimeUnit.MILLISECONDS)) return err("tiempo agotado esperando el gesto")
        } finally {
            svc.indicator?.passThrough(false)
        }
        return if (done) ok().put("channel", "gesture").put("inject_ms", SystemClock.uptimeMillis() - gt0) else err("gesto cancelado (pantalla bloqueada o el usuario toco la pantalla)")
    }

    private fun inScreen(x: Float, y: Float): Boolean {
        val s = realSize()
        return x >= 0 && y >= 0 && x <= s[0] && y <= s[1]
    }

    /** Marca la respuesta con riesgo alto si lo que hay bajo el toque parece enviar/pagar/borrar/confirmar. */
    private fun markRisk(res: JSONObject, word: String?, label: String): JSONObject {
        if (word != null) res.put("riesgo", "alto").put("label", label.take(60))
        return res
    }

    private fun tap(x: Float, y: Float, hit: TreeScan.Hit?, via: String? = null): JSONObject {
        if (!inScreen(x, y)) return err("coordenadas fuera de pantalla")
        val word = hit?.let { Risk.check(it.texts, it.ids) }
        // un solo intento: el asistente si esta, y solo si no responde (nada se envio) la Accesibilidad
        val res = viaHelper(JSONObject().put("op", "tap").put("x", x.toDouble()).put("y", y.toDouble()), via)
            ?: gesture(Path().apply { moveTo(x, y) }, 50)
        return markRisk(res, word, hit?.label ?: "")
    }

    /** tap @eN: clic por nodo (o su ancestro clicable); en lecturas antiguas relocaliza; sin accion de clic posible, gesto al centro. */
    private fun tapNode(rawId: String, snapSeq: Int?): JSONObject {
        val id = rawId.trim().removePrefix("@")
        if (id.isEmpty()) return err("falta 'id'")
        val latest = Snapshots.latest() ?: return err("no hay lecturas guardadas: ejecuta look primero")
        val snap = if (snapSeq == null) latest else Snapshots.get(snapSeq) ?: return err("ids caducados (la lectura ya no se guarda): ejecuta look de nuevo")
        val ent = snap.entries[id] ?: return err("id desconocido @$id en la lectura ${snap.seq}")
        var node: AccessibilityNodeInfo? = null
        var how = "node"
        if (snap.vd) {
            // pantalla virtual (Android < 11): relocalizar y comprobar visibilidad no funcionan alli; se usa el nodo guardado tal cual
            node = ent.node
            how = "vd_node"
        } else if (snap === latest) {
            // lectura mas reciente: refrescar el nodo guardado y comprobar que sigue siendo el mismo
            val n = ent.node
            val same = try {
                @Suppress("DEPRECATION") n.refresh() &&
                    (ent.loc.rid.isEmpty() || (n.viewIdResourceName ?: "") == ent.loc.rid) &&
                    (ent.loc.text.isEmpty() || n.isPassword || (n.text?.toString() ?: "") == ent.loc.text) &&
                    (ent.loc.desc.isEmpty() || (n.contentDescription?.toString() ?: "") == ent.loc.desc) && n.isVisibleToUser
            } catch (_: Exception) { false }
            if (same) node = n
        }
        if (node == null) { node = TreeScan.relocate(svc, ent.loc); how = "relocated" }
        if (node == null) return err("el elemento @$id ya no esta en pantalla: ejecuta look de nuevo")
        // ancestro (o el propio nodo) con accion de clic
        var target: AccessibilityNodeInfo? = node
        var up = 0
        while (target != null && !target.isClickable && up < 8) { target = target.parent; up++ }
        if (target != null && !target.isClickable) target = null
        val label = TreeScan.labelOf(target ?: node).ifBlank { TreeScan.labelOf(node) }
        val word = Risk.check(listOf(label, node.text?.toString() ?: "", node.contentDescription?.toString() ?: ""),
            listOf(node.viewIdResourceName ?: "", target?.viewIdResourceName ?: ""))
        val r = Rect().also { (target ?: node).getBoundsInScreen(it) }
        val res: JSONObject
        if (target != null) {
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val ch = when { how == "relocated" -> "relocated_click"; target === node -> "node_click"; else -> "ancestor_click" }
            res = when {
                clicked -> ok().put("channel", ch)
                word != null -> err("el nodo rechazo el clic; accion de riesgo ($word): un solo intento, sin canal alternativo")
                snap.vd -> err("el nodo de la pantalla virtual rechazo el clic (sin gestos por coordenadas en esa pantalla en Android < 10)")
                else -> gestureAt(r).put("fallback", "el nodo rechazo ACTION_CLICK")
            }
        } else {
            res = if (snap.vd) err("el elemento @$id no tiene accion de clic en la pantalla virtual") else gestureAt(r)   // nada clicable: gesto al centro (unico intento)
        }
        res.put("id", "@$id")
        return markRisk(res, word, label)
    }

    private fun gestureAt(r: Rect): JSONObject {
        if (r.width() <= 0 || r.height() <= 0) return err("el elemento no tiene area visible")
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    private fun tapAt(x: Float, y: Float): JSONObject {
        if (!inScreen(x, y)) return err("el elemento esta fuera de pantalla")
        return gesture(Path().apply { moveTo(x, y) }, 50)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, via: String? = null): JSONObject {
        if (!inScreen(x1, y1) || !inScreen(x2, y2)) return err("coordenadas fuera de pantalla")
        viaHelper(JSONObject().put("op", "swipe").put("x1", x1.toDouble()).put("y1", y1.toDouble()).put("x2", x2.toDouble())
            .put("y2", y2.toDouble()).put("ms", ms), via)?.let { return it }
        return gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)
    }

    private fun global(action: Int): JSONObject =
        if (svc.performGlobalAction(action)) ok() else err("el sistema rechazo la accion")

    /** Tecla real por el asistente. null = no hay asistente (o no responde): el llamador devuelve el error de siempre. */
    private fun helperKey(k: String): JSONObject? {
        if (!HelperClient.has("input")) return null
        val r = HelperClient.call("key", { put("key", k) }) ?: run { HelperClient.invalidate(); return null }
        return if (r.ok && r.json.optBoolean("injected", true)) ok().put("channel", "helper") else err(if (r.ok) "el sistema rechazo la tecla" else r.error)
    }

    private fun key(k: String): JSONObject {
        val name = k.trim().uppercase().removePrefix("KEYCODE_")
        if (name in HELPER_FIRST) helperKey(k)?.let { return it }     // ENTER, WAKE...: con el asistente son teclas reales
        return when (name) {
            "BACK", "4" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            "HOME", "3" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            "RECENTS", "APP_SWITCH", "187" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "POWER_DIALOG" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "LOCK_SCREEN", "SLEEP", "223" ->
                if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                else err("bloquear pantalla requiere Android 9+")
            "ENTER", "66" -> enter()
            "DEL", "67" -> del()
            "WAKE", "224" -> err("WAKE $NO_BASICO (un servicio de accesibilidad no puede encender la pantalla; con el modo avanzado si: phone N advanced start)")
            else -> helperKey(k) ?: err("tecla no soportada en modo basico: $k")
        }
    }

    // ---------------------------------------------------------------- campo de texto con foco
    private fun findEditable(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try { svc.rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        for (w in windowList()) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            try { w.root?.let { roots.add(it) } } catch (_: Exception) {}
        }
        for (r in roots) {
            val f = try { r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
            if (f != null && f.isEditable) return f
        }
        for (r in roots) {                         // respaldo: recorrer buscando un editable con foco
            findFocusedEditable(r, 0)?.let { return it }
        }
        return null
    }

    private fun findFocusedEditable(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (n.isEditable && n.isFocused) return n
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            findFocusedEditable(c, depth + 1)?.let { return it }
        }
        return null
    }

    /** Texto real del campo, ignorando el hint (placeholder). */
    private fun currentText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun setText(n: AccessibilityNodeInfo, s: String): Boolean {
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return false
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, s.length)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    private fun text(s: String): JSONObject {
        val n = findEditable() ?: return err("no hay un campo de texto con foco (toca el campo primero)")
        if (n.isPassword) return err("campo de contraseña: lo escribe el usuario")
        return if (setText(n, currentText(n) + s)) ok() else err("el campo rechazo SET_TEXT")
    }

    private fun del(): JSONObject {
        val n = findEditable() ?: return err("no hay un campo de texto con foco")
        val cur = currentText(n)
        if (cur.isEmpty()) return ok()
        val cut = cur.offsetByCodePoints(cur.length, -1)
        return if (setText(n, cur.substring(0, cut))) ok() else err("el campo rechazo SET_TEXT")
    }

    private fun enter(): JSONObject {
        val n = findEditable() ?: return err("no hay un campo de texto con foco para ENTER")
        if (Build.VERSION.SDK_INT >= 30) {
            return if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) ok()
            else err("el campo rechazo ACTION_IME_ENTER")
        }
        // Android < 11: sin ACTION_IME_ENTER. En campos multilinea se inserta un salto de linea; si no, no hay forma.
        if (n.isMultiLine) return if (setText(n, currentText(n) + "\n")) ok() else err("el campo rechazo SET_TEXT")
        return err("ENTER requiere Android 11+ en modo basico (en campos de una linea toca el boton de buscar/enviar del teclado)")
    }

    // ---------------------------------------------------------------- apps / intents
    private fun launch(pkg: String): JSONObject {
        if (pkg.isBlank()) return err("falta 'package'")
        val i = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return err("sin actividad lanzable para $pkg")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        svc.startActivity(i)
        return ok()
    }

    private fun open(url: String): JSONObject {
        if (url.isBlank()) return err("falta 'url'")
        return try {
            svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ok()
        } catch (e: android.content.ActivityNotFoundException) {
            err("ninguna app puede abrir: $url")
        }
    }

    // ---------------------------------------------------------------- notificaciones
    fun notifAccess(): Boolean {
        val list = Settings.Secure.getString(svc.contentResolver, "enabled_notification_listeners") ?: return false
        return list.split(':').any { android.content.ComponentName.unflattenFromString(it)?.packageName == svc.packageName }
    }

    /** Notificaciones activas (sin las "en curso" del sistema salvo all=true). Cada una: package, app, title, text, time, ongoing. */
    fun notifs(all: Boolean): JSONObject {
        if (!notifAccess()) {
            return err("falta permiso de acceso a notificaciones: en el telefono abre Mbot Agent y activa el paso 'Acceso a notificaciones' " +
                "(o Ajustes > Notificaciones > Acceso a notificaciones > Mbot Agent). En Android 13+ instalado a mano puede hacer falta antes " +
                "'Permitir ajustes restringidos' en Informacion de la app")
        }
        val l = MbotNotifService.instance
            ?: return err("el servicio de notificaciones aun no esta conectado: espera unos segundos y repite (o desactiva y reactiva el acceso)")
        val pm = svc.packageManager
        val arr = JSONArray()
        val sbns = try { l.activeNotifications } catch (e: Exception) { return err("no se pudieron leer las notificaciones: ${e.message}") }
        for (sbn in sbns ?: emptyArray()) {
            val pkg = sbn.packageName
            if (pkg == svc.packageName) continue
            val ongoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
            val system = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (!all && ongoing && (system || pkg == "android" || pkg == "com.android.systemui")) continue
            val ex = sbn.notification.extras
            val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val label = try { appInfo?.let { pm.getApplicationLabel(it).toString() } } catch (_: Exception) { null } ?: pkg
            arr.put(JSONObject().put("package", pkg).put("app", label).put("title", title).put("text", text)
                .put("time", sbn.postTime).put("ongoing", ongoing))
        }
        return ok().put("notifs", arr)
    }

    private fun apps(): JSONObject {
        val pm = svc.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (ri in pm.queryIntentActivities(q, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }) {
            val p = ri.activityInfo.packageName
            if (!seen.add(p)) continue
            val flags = ri.activityInfo.applicationInfo.flags
            val system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            arr.put(JSONObject().put("package", p).put("label", ri.loadLabel(pm).toString()).put("system", system))
        }
        return ok().put("apps", arr)
    }

    @Suppress("DEPRECATION")
    private fun info(): JSONObject {
        val bat = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val cm = svc.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "ninguna"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "movil"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "otra"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val wifiOn = try {
            (svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (_: Exception) { false }
        val airplane = Settings.Global.getInt(svc.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val size = realSize()
        return ok()
            .put("brand", Build.BRAND).put("model", Build.MODEL)
            .put("release", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("battery", if (level >= 0) level * 100 / scale else -1).put("charging", charging)
            .put("network", net).put("wifi_on", wifiOn).put("internet", validated)
            .put("width", size[0]).put("height", size[1]).put("density", svc.resources.displayMetrics.densityDpi)
            .put("airplane", airplane)
            .put("locale", java.util.Locale.getDefault().language)
    }
}

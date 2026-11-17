package com.mbot.access.mobile

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mbot.access.mobile.adb.WirelessAdb
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.DateFormat
import java.util.Date

/**
 * Pantalla unica: asistente de permisos (se marca solo al volver a la app), vinculacion de PC con codigo,
 * lista de PC vinculados, control de pausa y, al final, la conexion manual (token) de la Fase 0.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        private var n = 0
        override fun run() { refreshLive(); if (++n % 3 == 0) pollAdvanced(); ui.postDelayed(this, 1000) }
    }

    // pasos del asistente: [0]=titulo con marca, [1]=detalle, [2]=contenedor de botones
    private class Step(val title: TextView, val detail: TextView, val buttons: LinearLayout)
    private lateinit var stA11y: Step
    private lateinit var stNotif: Step
    private lateinit var stPost: Step
    private lateinit var stBatt: Step
    private lateinit var stAuto: Step

    private lateinit var summary: TextView
    private lateinit var codeView: TextView
    private lateinit var clientsBox: LinearLayout
    private lateinit var pauseView: TextView
    private lateinit var pauseBtn: Button
    private lateinit var ips: TextView
    private lateinit var tokenView: TextView
    private lateinit var cmdView: TextView
    private var lastClientsKey = ""

    // Modo avanzado
    private class AdvInfo(val alive: Boolean, val stale: Boolean, val version: String, val features: String, val uptimeS: Long,
                          val secure: Boolean, val paired: Boolean, val busy: Boolean, val status: String)
    private lateinit var advState: TextView
    private lateinit var advDetail: TextView
    private lateinit var advButtons: LinearLayout
    private lateinit var advPcCmd: TextView
    @Volatile private var advInfo: AdvInfo? = null
    private var lastAdvKey = ""
    @Volatile private var advPolling = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, bold: Boolean = false, size: Float = 15f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun copy(text: String, what: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("mbot", text))
        Toast.makeText(this, "$what copiado", Toast.LENGTH_SHORT).show()
    }

    private fun section(col: LinearLayout, text: String) {
        col.addView(label(text, bold = true, size = 18f).apply { setPadding(0, dp(22), 0, dp(4)) })
    }

    private fun step(col: LinearLayout, title: String): Step {
        val t = label(title, bold = true)
        val d = label("", size = 13f).apply { setPadding(dp(4), 0, 0, dp(2)) }
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(t); col.addView(d); col.addView(b)
        return Step(t, d, b)
    }

    private fun setStep(s: Step, title: String, ok: Boolean, detail: String, vararg btns: Pair<String, () -> Unit>) {
        s.title.text = (if (ok) "✔ " else "✘ ") + title
        s.title.setTextColor(if (ok) 0xFF1B7F3B.toInt() else 0xFFB3261E.toInt())
        s.detail.text = detail
        s.detail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        s.buttons.removeAllViews()
        for ((txt, fn) in btns) s.buttons.addView(button(txt, fn))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }
        col.addView(label("Mbot Agent", bold = true, size = 22f))
        summary = label("")
        col.addView(summary)

        section(col, "Preparar el teléfono")
        stA11y = step(col, "1. Servicio de Accesibilidad")
        stNotif = step(col, "2. Acceso a notificaciones")
        stPost = step(col, "3. Avisos de la app")
        stBatt = step(col, "4. Ahorro de batería")
        stAuto = step(col, "5. Segundo plano del fabricante")

        section(col, "Vincular un PC")
        col.addView(label("Pulsa el botón y escribe en el PC:  phone pair   (el PC encuentra este teléfono solo; si no, phone pair IP CÓDIGO).", size = 13f))
        col.addView(button("Vincular un PC") { Pairing.newCode(); refreshLive() })
        codeView = label("", size = 30f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(codeView)
        col.addView(label("PC vinculados", bold = true))
        clientsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(clientsBox)

        section(col, "Control del agente")
        pauseView = label("")
        col.addView(pauseView)
        pauseBtn = button("") {
            if (AgentState.paused) AgentState.resume() else AgentState.pause(AgentState.PAUSE_USER)
            refreshLive()
        }
        col.addView(pauseBtn)
        col.addView(CheckBox(this).apply {
            text = "Pausar solo si toco la pantalla mientras el agente trabaja"
            isChecked = AgentState.autoPause(this@MainActivity)
            setOnCheckedChangeListener { _, v -> AgentState.setAutoPause(this@MainActivity, v) }
        })

        buildAdvancedSection(col)

        section(col, "Conexión manual (avanzado)")
        col.addView(label("Dirección del teléfono (Wi-Fi)", bold = true))
        ips = label("")
        ips.setTextIsSelectable(true)
        col.addView(ips)
        col.addView(label("Token único (sigue funcionando con phone connect)", bold = true))
        tokenView = label("", size = 17f)
        tokenView.typeface = Typeface.MONOSPACE
        tokenView.setTextIsSelectable(true)
        col.addView(tokenView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Copiar token") { copy(Token.get(this@MainActivity), "Token") })
        row.addView(button("Regenerar") {
            Token.regenerate(this@MainActivity)
            refreshLive()
            Toast.makeText(this, "Token nuevo: reconecta desde el PC", Toast.LENGTH_SHORT).show()
        })
        col.addView(row)
        cmdView = label("", size = 14f)
        cmdView.typeface = Typeface.MONOSPACE
        cmdView.setTextIsSelectable(true)
        col.addView(cmdView)
        col.addView(button("Copiar comando") { copy(cmdView.text.toString(), "Comando") })

        col.addView(label("El servidor solo acepta conexiones desde redes privadas (misma Wi-Fi) y exige token. " +
            "Puerto ${AgentServer.PORT}.", size = 13f))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refreshSteps()
        refreshLive()
        pollAdvanced()
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    // ------------------------------------------------------------------ modo avanzado
    private fun buildAdvancedSection(col: LinearLayout) {
        section(col, "Modo avanzado (opcional)")
        advState = label("", bold = true)
        col.addView(advState)
        advDetail = label("", size = 13f)
        col.addView(advDetail)
        col.addView(label("Un asistente con identidad shell (como Shizuku) da al agente entrada real (ENTER, encender la pantalla, cualquier tecla), " +
            "captura de pantalla en Android 9/10, portapapeles y una pantalla virtual oculta. Sin él todo funciona como siempre (modo básico).", size = 13f))

        col.addView(label("Forma A · desde el PC por USB (Android 9 en adelante)", bold = true))
        col.addView(label("Conecta el teléfono por USB con la Depuración USB activada y autorizada y ejecuta en el PC (N = número de phone devices):", size = 13f))
        advPcCmd = label("phone N advanced start", size = 15f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(advPcCmd)
        col.addView(button("Copiar comando") { copy("phone N advanced start", "Comando") })
        col.addView(label("Para pararlo: phone N advanced stop. Tras reiniciar el teléfono el asistente muere y hay que volver a arrancarlo.", size = 13f))

        col.addView(label("Forma B · sin PC, con Depuración inalámbrica (Android 11+)", bold = true))
        col.addView(label(
            if (WirelessAdb.supported()) "1) Pulsa \"Emparejar\": se abren las Opciones de desarrollador. 2) Activa Depuración inalámbrica (requiere Wi-Fi) y entra en " +
                "\"Vincular dispositivo con código\". 3) Responde a la notificación de Mbot Agent con el código de 6 dígitos. Después el asistente arranca solo " +
                "y, si se cae o reinicias, la app lo reintenta. Probado en Android 12 (Xiaomi); la reconexión tras reiniciar aún no."
            else "No disponible: requiere Android 11 o superior (este equipo es Android ${Build.VERSION.RELEASE}). Usa la forma A.", size = 13f))
        advButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(advButtons)

        col.addView(CheckBox(this).apply {
            text = "Reactivar la Accesibilidad si se cae (solo con WRITE_SECURE_SETTINGS)"
            isChecked = Advanced.autoHeal(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Advanced.setAutoHeal(this@MainActivity, v) }
        })

        col.addView(label("Advertencias", bold = true))
        col.addView(label("• Algunas apps de banca y de pago detectan las Opciones de desarrollador y la depuración (USB o inalámbrica) y se niegan a funcionar. " +
            "Si usas alguna, desactiva el modo avanzado y las Opciones de desarrollador.\n" +
            "• La Depuración inalámbrica solo funciona con Wi-Fi (no con datos móviles) y se apaga sola al cambiar de red.\n" +
            "• WRITE_SECURE_SETTINGS permite a la app activar su propia Accesibilidad y la Depuración inalámbrica; no se pide nunca por pantalla: solo lo concede el asistente.\n" +
            "• Cómo desactivarlo: \"Desactivar modo avanzado\" (para el asistente y revoca ese permiso) y después apaga la Depuración USB/inalámbrica en las Opciones de desarrollador.", size = 13f))
    }

    /** Estado del asistente: se consulta en un hilo (es una llamada Binder) y se pinta en la interfaz. */
    private fun pollAdvanced() {
        if (advPolling) return
        advPolling = true
        Thread {
            try {
                val h = HelperClient.info(force = true)
                advInfo = AdvInfo(h != null, HelperClient.stale(), h?.optString("version") ?: "", HelperClient.features().joinToString(" "),
                    (h?.optLong("uptime_ms") ?: 0L) / 1000, Advanced.hasSecure(this), WirelessAdb.isPaired(this), WirelessAdb.busy, WirelessAdb.status)
                ui.post { renderAdvanced() }
            } catch (_: Exception) {
            } finally { advPolling = false }
        }.start()
    }

    private fun bg(msg: String?, work: () -> String) {
        Thread {
            val r = try { work() } catch (e: Exception) { "ERR ${e.message}" }
            ui.post { Toast.makeText(this, msg?.let { "$it: $r" } ?: r, Toast.LENGTH_LONG).show(); pollAdvanced() }
        }.start()
    }

    private fun renderAdvanced() {
        val i = advInfo ?: return
        if (i.alive) put(advState, "AVANZADO: asistente activo (v${i.version}, hace ${i.uptimeS} s)" + if (i.stale) " · versión distinta de la app: reinícialo" else "", 0xFF1B7F3B.toInt())
        else put(advState, "BÁSICO: asistente parado (solo Accesibilidad)", 0xFF555555.toInt())
        put(advDetail, (if (i.alive) "Funciones: ${i.features}\n" else "") + "WRITE_SECURE_SETTINGS: " + (if (i.secure) "concedido" else "no concedido") +
            (if (WirelessAdb.supported()) " · emparejado (inalámbrico): " + (if (i.paired) "sí" else "no") else "") +
            (if (i.status.isNotBlank()) "\n" + i.status else ""))
        val key = "${i.alive}|${i.secure}|${i.paired}|${i.busy}"
        if (key == lastAdvKey) return
        lastAdvKey = key
        advButtons.removeAllViews()
        if (WirelessAdb.supported()) {
            advButtons.addView(button("Emparejar con depuración inalámbrica") {
                bg(null) { WirelessAdb.startPairing(this) }
                try { WirelessAdb.openDeveloperSettings(this) } catch (_: Exception) { Toast.makeText(this, "Abre Ajustes > Opciones de desarrollador", Toast.LENGTH_LONG).show() }
            })
            if (i.paired) {
                advButtons.addView(button(if (i.busy) "Conectando…" else "Iniciar asistente (inalámbrico)") { if (!i.busy) WirelessAdb.connectAndStartAsync(this) })
                advButtons.addView(button("Olvidar emparejamiento") { WirelessAdb.unpair(this); pollAdvanced() })
            }
        }
        if (i.alive) {
            if (!i.secure) advButtons.addView(button("Conceder WRITE_SECURE_SETTINGS (por el asistente)") {
                bg("WRITE_SECURE_SETTINGS") { val r = HelperClient.call("grant_secure"); if (r?.ok == true) "concedido" else "ERR ${r?.error ?: "sin asistente"}" }
            }) else advButtons.addView(button("Revocar WRITE_SECURE_SETTINGS") {
                bg("WRITE_SECURE_SETTINGS") { val r = HelperClient.call("revoke_secure"); if (r?.ok == true) "revocado" else "ERR ${r?.error ?: "sin asistente"}" }
            })
            advButtons.addView(button("Detener asistente") {
                bg("Asistente") { Advanced.setDesired(this, false); val r = HelperClient.call("quit"); HelperClient.invalidate(); if (r != null) "parado" else "ya estaba parado" }
            })
            advButtons.addView(button("Desactivar modo avanzado (para y revoca)") { bg("Modo avanzado") { Advanced.disable(this) } })
        }
    }

    // ------------------------------------------------------------------ comprobaciones
    private fun a11yEnabled(): Boolean {
        val me = ComponentName(this, MbotA11yService::class.java)
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun notifEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun postGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun battOk(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun appInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun wifiAddresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) out.add(a.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Intentos conocidos por fabricante hacia la pantalla de "inicio automático / segundo plano". Se prueban en orden. */
    private fun autostartIntents(): Pair<String, List<ComponentName>> {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        fun c(p: String, cl: String) = ComponentName(p, cl)
        return when {
            "huawei" in m || "honor" in m -> Pair("Huawei/Honor: Administrador del teléfono › Inicio de aplicaciones › Mbot Agent › desactiva \"Administrar automáticamente\" y activa Inicio automático, Inicio secundario y Ejecución en segundo plano.",
                listOf(c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    c("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                    c("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")))
            "xiaomi" in m || "redmi" in m || "poco" in m -> Pair("Xiaomi/Redmi/POCO: Seguridad › Permisos › Inicio automático › activa Mbot Agent. En la ficha de la app pon Ahorro de batería en \"Sin restricciones\".",
                listOf(c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            "samsung" in m -> Pair("Samsung: Ajustes › Batería › Límites de uso en segundo plano › quita Mbot Agent de \"Apps en suspensión\" y añádela a \"Apps que nunca se suspenden\".",
                listOf(c("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    c("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    c("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")))
            "oppo" in m || "realme" in m || "oneplus" in m -> Pair("Oppo/Realme/OnePlus: Gestor del teléfono › Permisos de privacidad › Inicio automático (o Ajustes › Batería › Optimización) › permite Mbot Agent.",
                listOf(c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                    c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")))
            "vivo" in m || "iqoo" in m -> Pair("Vivo: iManager › Gestor de aplicaciones › Inicio automático (o Ajustes › Batería › Consumo en segundo plano) › permite Mbot Agent.",
                listOf(c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    c("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")))
            else -> Pair("Si el agente se corta con la pantalla apagada, abre Información de la app › Batería y elige \"Sin restricciones\" (o similar).", emptyList())
        }
    }

    private fun openAutostart() {
        for (cn in autostartIntents().second) {
            try {
                startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* este componente no existe en este modelo: probar el siguiente */ }
        }
        Toast.makeText(this, "No se encontró la pantalla del fabricante: abro la información de la app", Toast.LENGTH_LONG).show()
        appInfo()
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { appInfo() }
        }
    }

    // ------------------------------------------------------------------ refresco
    /** Se llama en onResume: cada paso se marca solo según el estado real. */
    private fun refreshSteps() {
        val on = a11yEnabled()
        val running = MbotA11yService.instance != null
        val restricted = if (Build.VERSION.SDK_INT >= 33 && !on)
            "Si el interruptor sale gris o dice \"Ajuste restringido\" (apps instaladas a mano en Android 13+): Información de la app › ⋮ (arriba a la derecha) › Permitir ajustes restringidos, y vuelve a intentarlo. " else ""
        setStep(stA11y, "Servicio de Accesibilidad", on && running,
            when {
                on && running -> "Activo: el servidor escucha."
                on -> "Activado en ajustes, arrancando… (si no cambia, desactívalo y vuélvelo a activar)."
                else -> restricted + "Pulsa el botón, busca \"Mbot Agent\" y actívalo."
            },
            *(if (on) arrayOf(Pair("Ajustes de Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            else arrayOf(Pair("Abrir ajustes de Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                Pair("Información de la app") { appInfo() })))

        val n = notifEnabled()
        setStep(stNotif, "Acceso a notificaciones", n,
            if (n) "Activo: el agente puede leer las notificaciones (phone notifs)."
            else "Opcional pero recomendado: permite leer notificaciones sin abrir apps." +
                if (Build.VERSION.SDK_INT >= 33) " En Android 13+ instalado a mano puede hacer falta antes \"Permitir ajustes restringidos\" en Información de la app." else "",
            Pair("Abrir acceso a notificaciones") {
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { appInfo() }
            })

        val p = postGranted()
        setStep(stPost, "Avisos de la app (Android 13+)", p,
            if (Build.VERSION.SDK_INT < 33) "No hace falta en este Android." else if (p) "Concedido: se muestra el aviso \"Agente trabajando\" con el botón Detener."
            else "Permite los avisos para ver el botón Detener también en las notificaciones.",
            *(if (p) emptyArray() else arrayOf(Pair("Permitir avisos") {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })))

        val b = battOk()
        setStep(stBatt, "Ahorro de batería", b,
            if (b) "Mbot Agent está exenta de la optimización de batería." else "Sin esto el sistema puede dormir el servicio con la pantalla apagada.",
            *(if (b) emptyArray() else arrayOf(Pair("Quitar optimización de batería") { requestIgnoreBattery() })))

        val (guide, comps) = autostartIntents()
        val done = getSharedPreferences("mbot", MODE_PRIVATE).getBoolean("autostart_done", false)
        setStep(stAuto, "Segundo plano del fabricante (${Build.MANUFACTURER})", done, guide,
            Pair(if (comps.isEmpty()) "Información de la app" else "Abrir pantalla del fabricante") { openAutostart() },
            Pair(if (done) "Desmarcar" else "Ya lo hice") {
                getSharedPreferences("mbot", MODE_PRIVATE).edit().putBoolean("autostart_done", !done).apply()
                refreshSteps()
            })
    }

    private fun fmtLeft(ms: Long): String { val s = (ms + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /** Cambia el texto/color solo si hace falta: tocar la vista cada segundo generaria eventos de accesibilidad sin parar. */
    private fun put(t: TextView, text: String, color: Int? = null) {
        if (t.text.toString() != text) t.text = text
        if (color != null && t.currentTextColor != color) t.setTextColor(color)
    }

    /** Parte que cambia sola: código y cuenta atrás, clientes, pausa, direcciones y resumen. Una vez por segundo. */
    private fun refreshLive() {
        val on = a11yEnabled() && MbotA11yService.instance != null
        put(summary, if (on) "ACTIVO: el servidor escucha en el puerto ${AgentServer.PORT}." else "INACTIVO: completa el paso 1.", if (on) 0xFF1B7F3B.toInt() else 0xFFB3261E.toInt())

        val code = Pairing.active()
        if (code != null) {
            put(codeView, code.first.chunked(3).joinToString(" ") + "\n" + "caduca en ${fmtLeft(code.second)}")
            if (codeView.visibility != View.VISIBLE) codeView.visibility = View.VISIBLE
        } else if (codeView.visibility != View.GONE) {
            codeView.visibility = View.GONE
        }

        val clients = Pairing.clients(this)
        val legacy = !Pairing.legacyRevoked(this)
        val key = clients.joinToString(",") { it.id } + "|" + legacy
        if (key != lastClientsKey) {
            lastClientsKey = key
            clientsBox.removeAllViews()
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            fun row(name: String, sub: String, id: String) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(TextView(this).apply {
                    text = "$name\n$sub"; textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                r.addView(button("Revocar") { Pairing.revoke(this, id); lastClientsKey = ""; refreshLive() })
                clientsBox.addView(r)
            }
            if (legacy) row("Token único (manual)", "phone connect URL TOKEN", Pairing.LEGACY_ID)
            for (c in clients) row(c.name, "vinculado " + fmt.format(Date(c.created)), c.id)
            if (!legacy && clients.isEmpty()) clientsBox.addView(label("(ninguno)", size = 13f))
        }

        if (AgentState.paused) {
            put(pauseView, "PAUSADO: " + AgentState.pauseReason + ". Los comandos que actúan devuelven error hasta que reanudes.", 0xFFB3261E.toInt())
            put(pauseBtn, "Reanudar al agente")
        } else {
            put(pauseView, "El agente puede actuar. Si toca algo y quieres pararlo, usa el botón.", 0xFF1B7F3B.toInt())
            put(pauseBtn, "Pausar al agente")
        }

        val list = wifiAddresses()
        put(ips, if (list.isEmpty()) "Sin IP: conecta el teléfono a la Wi-Fi." else list.joinToString("\n") { "$it:${AgentServer.PORT}" })
        val token = Token.get(this)
        put(tokenView, if (Pairing.legacyRevoked(this)) "(revocado: pulsa Regenerar para activarlo de nuevo)" else token)
        val ip = list.firstOrNull() ?: "IP_DEL_TELEFONO"
        put(cmdView, "phone connect http://$ip:${AgentServer.PORT} $token")
    }
}

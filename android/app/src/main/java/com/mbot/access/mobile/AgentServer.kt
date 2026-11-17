package com.mbot.access.mobile

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Servidor HTTP minimo (solo java.net) para la API /v1. Solo acepta direcciones loopback o privadas y exige token Bearer. */
class AgentServer(private val ctx: Context, private val api: AgentApi) {
    companion object {
        const val PORT = 8765
        private const val MAX_BODY = 1 shl 20

        /** loopback, 10/8, 172.16/12, 192.168/16, 100.64/10, 169.254/16, fe80::/10 y fc00::/7. */
        fun isAllowed(a: InetAddress): Boolean {
            if (a.isLoopbackAddress) return true
            val b = a.address
            return when (a) {
                is Inet4Address -> {
                    val o0 = b[0].toInt() and 0xFF
                    val o1 = b[1].toInt() and 0xFF
                    o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) ||
                        (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254)
                }
                is Inet6Address -> {
                    val o0 = b[0].toInt() and 0xFF
                    val o1 = b[1].toInt() and 0xFF
                    (o0 == 0xFE && (o1 and 0xC0) == 0x80) || (o0 and 0xFE) == 0xFC
                }
                else -> false
            }
        }
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)
    private class Resp(val status: Int, val type: String, val body: ByteArray, val extra: Map<String, String> = emptyMap())

    @Volatile private var running = false
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT))
        socket = s
        pool = Executors.newFixedThreadPool(6)
        running = true
        Thread({
            while (running) {
                try {
                    val c = s.accept()
                    pool?.execute { handle(c) }
                } catch (e: Exception) {
                    if (running) Log.w(MbotA11yService.TAG, "accept: ${e.message}")
                }
            }
        }, "mbot-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        pool?.shutdownNow()
        socket = null
    }

    // ------------------------------------------------------------------ HTTP
    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15000
            c.use {
                val resp = try {
                    if (!isAllowed(c.inetAddress)) json(403, JSONObject().put("error", "direccion no permitida (solo red local)"))
                    else {
                        val req = readRequest(BufferedInputStream(c.getInputStream()))
                        if (req == null) json(400, JSONObject().put("error", "peticion invalida")) else route(req)
                    }
                } catch (e: Exception) {
                    Log.w(MbotA11yService.TAG, "peticion: ${e.message}")
                    json(500, JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}"))
                }
                write(c, resp)
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(i: InputStream): String? {
        val bo = ByteArrayOutputStream()
        while (true) {
            val b = i.read()
            if (b < 0) return if (bo.size() == 0) null else bo.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) bo.write(b)
            if (bo.size() > 16384) return null
        }
        return bo.toString("UTF-8")
    }

    private fun readRequest(i: InputStream): Req? {
        val line = readLine(i) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(i) ?: return null
            if (h.isEmpty()) break
            val k = h.indexOf(':')
            if (k > 0) headers[h.substring(0, k).trim().lowercase()] = h.substring(k + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = i.read(body, off, len - off)
            if (n < 0) return null
            off += n
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = HashMap<String, String>()
        if (q >= 0) for (kv in target.substring(q + 1).split("&")) {
            if (kv.isEmpty()) continue
            val e = kv.indexOf('=')
            val k = java.net.URLDecoder.decode(if (e >= 0) kv.substring(0, e) else kv, "UTF-8")
            query[k] = if (e >= 0) java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8") else ""
        }
        return Req(parts[0].uppercase(), path, query, headers, body)
    }

    private fun json(status: Int, o: JSONObject) = Resp(status, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun write(c: Socket, r: Resp) {
        val reason = when (r.status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 423 -> "Locked"; 429 -> "Too Many Requests"; 501 -> "Not Implemented"; 504 -> "Gateway Timeout"
            else -> "Error"
        }
        val sb = StringBuilder("HTTP/1.1 ${r.status} $reason\r\n")
        sb.append("Content-Type: ").append(r.type).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in r.extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        val o = c.getOutputStream()
        o.write(sb.toString().toByteArray(Charsets.UTF_8))
        o.write(r.body)
        o.flush()
    }

    private fun authorized(req: Req): Boolean {
        val h = req.headers["authorization"] ?: return false
        if (!h.startsWith("Bearer ", ignoreCase = true)) return false
        val got = h.substring(7).trim().toByteArray(Charsets.UTF_8)
        return Pairing.authorize(ctx, String(got, Charsets.UTF_8)) != null
    }

    /** POST /v1/pair {code, client} (sin token): si el codigo es correcto entrega un token NUEVO para ese cliente. */
    private fun pair(req: Req): Resp {
        if (req.method != "POST") return json(405, JSONObject().put("error", "usa POST"))
        val body = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (e: Exception) { null }
            ?: return json(400, JSONObject().put("ok", false).put("error", "cuerpo JSON invalido"))
        val a = Pairing.check(body.optString("code"))
        if (!a.ok) return json(a.status, JSONObject().put("ok", false).put("error", a.error))
        val (client, token) = Pairing.addClient(ctx, body.optString("client", "PC"))
        Log.i(MbotA11yService.TAG, "PC vinculado: ${client.name}")
        return json(200, JSONObject().put("ok", true).put("token", token).put("client", client.name)
            .put("model", android.os.Build.MODEL).put("api", AgentApi.API))
    }

    private fun route(req: Req): Resp {
        if (req.path == "/v1/ping") {
            return if (req.method == "GET") json(200, api.ping()) else json(405, JSONObject().put("error", "usa GET"))
        }
        if (req.path == "/v1/pair") return pair(req)
        if (!req.path.startsWith("/v1/")) return json(404, JSONObject().put("error", "no existe"))
        if (!authorized(req)) return json(401, JSONObject().put("error", "token invalido o ausente"))
        return when (req.path) {
            "/v1/capabilities" -> if (req.method == "GET") json(200, api.capabilities()) else json(405, JSONObject().put("error", "usa GET"))
            "/v1/state" -> if (req.method == "GET") json(200, api.state()) else json(405, JSONObject().put("error", "usa GET"))
            "/v1/dump" -> if (req.method == "GET") {
                if (req.query["display"] == "vd") {
                    val x = api.vdDump()
                    if (x != null) Resp(200, "text/xml; charset=utf-8", x.toByteArray(Charsets.UTF_8))
                    else json(409, JSONObject().put("ok", false).put("error", api.vdDumpError()))
                } else Resp(200, "text/xml; charset=utf-8", api.dump().toByteArray(Charsets.UTF_8))
            } else json(405, JSONObject().put("error", "usa GET"))
            "/v1/notifs" -> if (req.method == "GET") json(200, api.notifs(req.query["all"] == "1")) else json(405, JSONObject().put("error", "usa GET"))
            "/v1/shot" -> if (req.method == "GET") {
                val w = req.query["w"]?.toIntOrNull() ?: 360
                val q = req.query["q"]?.toIntOrNull() ?: 45
                val r = api.shot(w, q, req.query["display"]?.takeIf { it == "vd" })
                if (r.jpeg != null) Resp(200, "image/jpeg", r.jpeg, mapOf("X-Scale" to String.format(java.util.Locale.US, "%.4f", r.scale)))
                else json(r.status, JSONObject().put("ok", false).put("error", r.error ?: "error"))
            } else json(405, JSONObject().put("error", "usa GET"))
            "/v1/cmd" -> if (req.method == "POST") {
                val body = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (e: Exception) { null }
                if (body == null) json(400, JSONObject().put("ok", false).put("error", "cuerpo JSON invalido"))
                else if (AgentState.paused && api.isActing(body.optString("cmd"))) json(423, AgentState.pausedError())
                else json(200, api.cmd(body))
            } else json(405, JSONObject().put("error", "usa POST"))
            else -> json(404, JSONObject().put("error", "no existe"))
        }
    }
}

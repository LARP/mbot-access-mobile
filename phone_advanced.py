"""Modo avanzado (Fase 3): asistente shell en el telefono. `phone N advanced start|stop|status|grant|revoke`, `clip`, `vd`.
El asistente se arranca por adb (forma A, desde el PC); despues el agente lo usa a traves del transporte app (la app le habla por Binder).
Este modulo recibe el modulo phone_agent como argumento (pa) para no importarlo dos veces.
"""
import os, re, time

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "helper", "mbot-helper.sh")
REMOTE = "/data/local/tmp/mbot-helper.sh"
PIDFILE = "/data/local/tmp/mbot-helper.pid"
PKG = "com.mbot.access.mobile"
NL = chr(10)
NEED_USB = ("ERR 'advanced' necesita el telefono por USB con depuracion autorizada (adb devices debe mostrarlo como 'device'). "
            "Conecta y autoriza, o en Android 11+ usa el boton 'Emparejar con depuracion inalambrica' de la app (Modo avanzado)")
NEED_ADV = "ERR requiere modo avanzado: arranca el asistente con 'phone N advanced start' (ver 'phone app caps')"


def adb_serial(pa, serial):
    """Serial adb a usar: el indicado si no es 'app'; si no, el primer USB autorizado (o el primero autorizado)."""
    if serial and serial != "app":
        return serial
    ok = [d["serial"] for d in pa._safe_devices() if d["state"] == "device"]
    usb = [s for s in ok if ":" not in s]
    return (usb or ok or [None])[0]


def _sh(pa, serial, cmd, timeout=30):
    return pa._run(["-s", serial, "shell", cmd], timeout=timeout).strip()


def _caps(pa):
    """Capacidades vivas por la app, o None si no hay conexion app."""
    try:
        conn = pa.app_conn()
        return pa._http_json(conn, "GET", "/v1/capabilities", timeout=6) if conn else None
    except Exception:
        return None


def _wait_mode(pa, want, secs):
    t0 = time.time()
    c = None
    while time.time() - t0 < secs:
        c = _caps(pa)
        if c is not None and (c.get("mode") == want):
            return c, time.time() - t0
        time.sleep(0.5)
    return c, time.time() - t0


def _describe(c):
    if c is None:
        return "app: sin conexion (phone pair / phone connect): no puedo ver el estado desde la app"
    h = c.get("helper") or {}
    perms = c.get("permissions") or {}
    s = f"app: modo={c.get('mode')} v{c.get('version')}"
    if h.get("alive"):
        s += (f" asistente v{h.get('version')} uid={h.get('uid')} pid={h.get('pid')} activo={int((h.get('uptime_ms') or 0) / 1000)}s"
              + (" OBSOLETO (reinicialo: advanced start)" if h.get("stale") else ""))
        s += NL + "funciones: " + " ".join(f for f in c.get("features", []) if f in ("adv_input", "adv_shot", "clip", "secure", "vd", "vd_input"))
    s += NL + "WRITE_SECURE_SETTINGS=" + ("concedido" if perms.get("write_secure_settings") else "no concedido")
    return s


def advanced_cmd(pa, serial, a):
    sub = (a[0] if a else "status").lower()
    if sub not in ("start", "stop", "status", "grant", "revoke"):
        return "ERR uso: phone N advanced start|stop|status|grant|revoke"
    ser = adb_serial(pa, serial)
    if sub == "status":
        lines = []
        if ser:
            pid = _sh(pa, ser, f"cat {PIDFILE} 2>/dev/null")
            alive = bool(pid) and _sh(pa, ser, f"kill -0 {pid} 2>/dev/null && echo si") == "si"
            lines.append(f"adb {ser}: asistente " + (f"en marcha pid={pid}" if alive else "parado"))
        else:
            lines.append("adb: sin telefono por USB (solo puedo ver el estado por la app)")
        lines.append(_describe(_caps(pa)))
        return NL.join(lines)
    if sub in ("grant", "revoke"):
        c = _caps(pa)
        if c and (c.get("helper") or {}).get("alive"):   # por el asistente (shell)
            conn = pa.app_conn()
            r = pa._http_json(conn, "POST", "/v1/cmd", {"cmd": "secure_" + sub}, timeout=20)
            if not r.get("ok"):
                return "ERR " + (r.get("error") or "fallo")
            how = "asistente"
        elif ser:
            out = _sh(pa, ser, f"pm {sub} {PKG} android.permission.WRITE_SECURE_SETTINGS")
            if out:
                return "ERR " + out
            how = "adb"
        else:
            return NEED_USB
        c = _caps(pa)
        has = bool(((c or {}).get("permissions") or {}).get("write_secure_settings"))
        return f"ok WRITE_SECURE_SETTINGS {'concedido' if sub == 'grant' else 'revocado'} (via {how}); la app lo ve: {'concedido' if has else 'no concedido'}"
    if not ser:
        return NEED_USB
    if not os.path.isfile(SCRIPT):
        return f"ERR falta {SCRIPT}"
    if sub == "start":
        t0 = time.time()
        pa._run(["-s", ser, "push", SCRIPT, REMOTE], timeout=20)
        out = _sh(pa, ser, f"sh {REMOTE}", timeout=40)
        t_script = time.time() - t0
        if not out.startswith("ok"):
            return out if out.startswith("ERR") else "ERR " + out
        c, t_app = _wait_mode(pa, "advanced", 10) if pa.app_conn() else (None, 0)
        res = out + f" ({t_script:.1f} s)"
        if pa.app_conn():
            if c is not None and c.get("mode") == "advanced":
                res += NL + f"app: modo=advanced ({t_script + t_app:.1f} s hasta que la app lo ve)"
            else:
                res += NL + ("AVISO la app aun no ve el asistente (Accesibilidad de la app desactivada o proceso dormido?): "
                             "abre la app Mbot Agent y repite 'phone app caps'")
        else:
            res += NL + "(sin conexion app guardada: no puedo comprobar que la app lo ve)"
        return res
    # stop
    if pa.app_conn():   # avisa a la app de que es a proposito (si no, en Android 11+ intentaria reiniciarlo por la forma B)
        try:
            pa._http_json(pa.app_conn(), "POST", "/v1/cmd", {"cmd": "helper_quit"}, timeout=6)
        except Exception:
            pass
    out = _sh(pa, ser, f"sh {REMOTE} stop")
    c, _ = _wait_mode(pa, "basic", 5) if pa.app_conn() else (None, 0)
    res = out if out.startswith("ok") else ("ERR " + out if out else "ok parado")
    if pa.app_conn():
        res += NL + ("app: modo=basic" if c is not None and c.get("mode") == "basic" else "AVISO la app sigue viendo el asistente: " + _describe(c))
    return res


# ---------------------------------------------------------------- clip / vd (solo AppPhone con asistente)
def _need_app(p, what):
    if not getattr(p, "app_mode", False):
        return f"ERR '{what}' solo existe en modo app con el asistente: usa 'phone app {what} ...' (y 'phone N advanced start' antes)"
    return None


def clip_cmd(p, a):
    bad = _need_app(p, "clip")
    if bad:
        return bad
    sub = (a[0] if a else "").lower()
    if sub == "get":
        r = p.cmd("clip_get")
        return r.get("text") or "(portapapeles vacio)"
    if sub == "set":
        p.cmd("clip_set", text=" ".join(a[1:]))
        return "ok"
    return "ERR uso: phone app clip get | clip set \"texto\""


def _size(s):
    m = re.fullmatch(r"(\d+)x(\d+)", s)
    return (int(m.group(1)), int(m.group(2))) if m else None


def vd_cmd(p, a, grep=None, pa=None):
    bad = _need_app(p, "vd")
    if bad:
        return bad
    sub = (a[0] if a else "status").lower()
    rest = a[1:]
    if sub == "start":
        kw = {}
        for x in rest:
            sz = _size(x)
            if sz:
                kw["w"], kw["h"] = sz
            elif x.isdigit():
                kw["dpi"] = int(x)
        r = p.cmd("vd_start", **kw)
        v = r.get("vd") or {}
        return f"ok pantalla virtual id={v.get('id')} {v.get('width')}x{v.get('height')}@{v.get('dpi')}dpi (oculta; lanza apps con 'vd launch <app>')"
    if sub == "stop":
        r = p.cmd("vd_stop", close_apps="--keep" not in rest)
        return "ok parada" + (f"; cerradas: {', '.join(r.get('closed'))}" if r.get("closed") else "")
    if sub == "status":
        v = p.cmd("vd_status").get("vd") or {}
        if not v.get("active"):
            return "pantalla virtual: inactiva"
        return (f"pantalla virtual: activa id={v.get('id')} {v.get('width')}x{v.get('height')}@{v.get('dpi')}dpi fotogramas={v.get('frames')} "
                f"apps={','.join(v.get('launched') or []) or '-'}")
    if sub == "launch":
        if not rest:
            return "ERR uso: vd launch <app|paquete>"
        pkg, err = p.resolve_package(" ".join(rest))
        if err:
            return err
        r = p.cmd("vd_launch", package=pkg)
        return f"ok {pkg} en la pantalla virtual" + (f" ({r.get('result')})" if r.get("result") else "")
    if sub == "shot":
        return p.vd_shot(rest[0] if rest else "vd_shot.jpg")
    if sub in ("look", "read"):
        p.vd_target = True
        try:
            body = pa.compact_read(p, max_items=60, grep=grep, explore=False)
        finally:
            p.vd_target = False
        return "VD (pantalla virtual)" + NL + body
    if sub == "text":
        p.cmd("vd_text", text=" ".join(rest))
        return "ok"
    if sub == "tap" and rest and rest[0].startswith("@e"):
        r = p.tap_node(rest[0])
        return f"ok {rest[0]}" + (f" via={r['channel']}" if r.get("channel") else "")
    if sub == "tap" and len(rest) >= 2:
        p.cmd("vd_tap", x=int(float(rest[0])), y=int(float(rest[1])))
        return "ok"
    if sub == "swipe" and len(rest) >= 4:
        p.cmd("vd_swipe", x1=int(float(rest[0])), y1=int(float(rest[1])), x2=int(float(rest[2])), y2=int(float(rest[3])),
              ms=int(float(rest[4])) if len(rest) > 4 else 300)
        return "ok"
    if sub == "key" and rest:
        p.cmd("vd_key", key=rest[0])
        return "ok"
    return ("ERR uso: vd start [WxH] [dpi] | stop [--keep] | status | launch <app> | look [--grep X] | tap @eN | text \"..\" | shot [ruta] | "
            "tap X Y | swipe X1 Y1 X2 Y2 [ms] | key K  (X Y/swipe/key solo en Android 10+)")

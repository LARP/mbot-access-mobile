"""mbot-access-mobile — CLI/API para que un agente opere el telefono por ADB. Salida COMPACTA por defecto (pocos tokens).

Uso:  phone devices   |   phone <dispositivo> <comando> [args]   (dispositivo = numero de la lista o serial)
Serial por defecto: variable PHONE_SERIAL, o el primer dispositivo USB, o el primero disponible.

Comandos
  (tap/tap_text/scroll/scroll_to/launch/back/key devuelven tambien la pantalla resultante; -q lo desactiva; --grep X filtra lineas; --diff solo cambios respecto a la pantalla anterior; look --full sin truncar y con clase/id/bounds)
  remember <nombre> [--last N] [--desc "..."] [--result texto] [x=VALOR]  guarda como macro las ultimas N acciones exitosas | forget <nombre>
  goto "texto" [--tap] | graph  (PROTOTIPO) navega por grafo de pantallas aprendidas
  macros | run <macro> [k=v]  memoria de rutas con estado borrador|prueba|verificada (memory/macros.json); ver phone_macros.py
  macro promote|demote <nombre>  cambia a mano el estado de un macro
  help --agent [--json]       manual compacto para agentes (generado desde phone_help.SPECS) | caps: capacidades vivas del dispositivo
  know [app]                  fichas de conocimiento por app (knowledge/apps/<n>/APP.md): lista o imprime una
  look                        estado + texto de pantalla en UNA llamada (el mas barato para decidir). Detecta login, overlays/dialogos de sistema, iconos sin etiqueta, pantalla desplazable, teclado
  info                        modelo, Android, bateria, pantalla, wifi, internet, modo avion
  top                         actividad en primer plano + ventanas visibles (detecta overlays/dialogos)
  notifs                      notificaciones activas (app: titulo - texto) sin abrir apps
  apps [filtro]               apps de usuario instaladas (paquetes)
  logs [paquete]              errores recientes de logcat (crashes/ANR) del paquete o del sistema
  open <url|deeplink>         abre un enlace (puede mostrar 'Abrir con')
  wait <texto|paquete> [seg]  espera a que aparezca el texto o la app (por defecto 15 s)
  icons                       hoja numerada logs/icons.png con los botones de solo icono de la pantalla
  launch <app|paquete>        acepta nombre ('instagram', 'play store') o paquete; avisa si falla
  state                       HOME/NO-HOME, app en primer plano, pantalla, bloqueo
  read                        elementos con texto: 'Etiqueta @x,y *'  (* = tocable)
  shot [ruta.jpg]             captura JPEG pequena (360px) solo si el texto no basta
  home | back | recents | wake | sleep
  ensure_home                 si no esta en home, pulsa Home (reintenta) y devuelve el estado
  tap X Y | tap_text "texto"  toque por coordenadas o por texto/descripcion visible
  swipe X1 Y1 X2 Y2 [ms] | scroll up|down
  accounts                    cuentas Google del telefono (emails)
  scroll_to "texto"           scroll hasta que el texto sea visible
  check                       verifica adb/scrcpy/pillow y recomienda como instalarlos
  connect <url> <token> | disconnect   modo app (sin depuracion USB): guarda/borra la conexion en logs/app_conn.json. Dispositivo 'app' (o PHONE_URL/PHONE_TOKEN)
  pair [CODIGO] | pair IP CODIGO   modo app: vincula este PC con el telefono (la app muestra el codigo en 'Vincular un PC'); sin IP busca por mDNS
  tap @eN                     (modo app) toca el elemento con id @eN que muestra 'look' (clic por nodo; mas fiable que coordenadas)
  advanced start|stop|status|grant|revoke  modo avanzado: asistente shell en el telefono (se arranca por USB; luego se usa por el transporte app). Ver README
  clip get | clip set "texto"  (app + avanzado) portapapeles | vd start|launch|shot|tap|swipe|key|status|stop  (app + avanzado) pantalla virtual oculta
  text "hola" | key ENTER | launch paquete | live (abre vista en vivo scrcpy) | devices
--json antes del comando entrega la salida completa en JSON.
Desde Python:  from phone_agent import Phone; p = Phone(); p.ensure_home()
"""
import contextlib, glob, io, json, os, re, shutil, subprocess, sys, time
import xml.etree.ElementTree as ET

import phone_help

HERE = os.path.dirname(os.path.abspath(__file__))
EXE = ".exe" if os.name == "nt" else ""


def find_adb():
    """Orden: variable ADB, ./platform-tools, PATH, ubicaciones tipicas. None si no hay."""
    cands = [os.environ.get("ADB"), os.path.join(HERE, "platform-tools", "adb" + EXE), shutil.which("adb")]
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            cands.append(os.path.join(os.environ[var], "platform-tools", "adb" + EXE))
    la = os.environ.get("LOCALAPPDATA", "")
    cands += [os.path.join(la, "Android", "Sdk", "platform-tools", "adb.exe"),
              r"C:\Android\Sdk\platform-tools\adb.exe",
              os.path.expanduser("~/Android/Sdk/platform-tools/adb"), "/usr/bin/adb", "/opt/homebrew/bin/adb"]
    return next((c for c in cands if c and os.path.isfile(c)), None)


def find_scrcpy():
    hits = glob.glob(os.path.join(HERE, "scrcpy", "**", "scrcpy" + EXE), recursive=True)
    return (hits[0] if hits else None) or shutil.which("scrcpy")


ADB = find_adb()
INSTALL_HINT = {
    "adb": "adb NO esta instalado. Recomendado: winget install Google.PlatformTools  "
           "(o descarga https://developer.android.com/tools/releases/platform-tools y descomprime en ./platform-tools)",
    "scrcpy": "scrcpy NO esta instalado (opcional, solo para 'live'). Recomendado: winget install Genymobile.scrcpy  "
              "(o https://github.com/Genymobile/scrcpy/releases y descomprime en ./scrcpy)",
    "pillow": "Pillow NO esta instalado (opcional, solo para 'shot'). Recomendado: pip install pillow",
}


def check():
    lines = [f"adb: {ADB}" if ADB else "ERR " + INSTALL_HINT["adb"]]
    sc = find_scrcpy()
    lines.append(f"scrcpy: {sc}" if sc else "AVISO " + INSTALL_HINT["scrcpy"])
    try:
        import PIL  # noqa: F401
        lines.append("pillow: ok")
    except ImportError:
        lines.append("AVISO " + INSTALL_HINT["pillow"])
    if ADB:
        n = [d for d in list_devices() if d["state"] == "device"]
        lines.append(f"dispositivos autorizados: {len(n)}"
                     + ("" if n else "  (conecta por USB, activa Depuracion USB y acepta el aviso en el telefono)"))
    return NL.join(lines)
NOWIN = 0x08000000 if os.name == "nt" else 0
NL = chr(10)
PNG_MAGIC = bytes([0x89, 0x50, 0x4E, 0x47])


def _run(args, binary=False, timeout=20):
    if not ADB:
        sys.exit("ERR " + INSTALL_HINT["adb"])
    r = subprocess.run([ADB] + args, capture_output=True, timeout=timeout, creationflags=NOWIN)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def list_devices():
    out = []
    for line in _run(["devices", "-l"]).splitlines()[1:]:
        p = line.split()
        if len(p) >= 2:
            out.append({"serial": p[0], "state": p[1], "info": " ".join(p[2:])})
    return out


class Phone:
    DUMP_MSGS = {}   # mensajes de error de _dump propios del transporte (ver AppPhone)
    app_mode = False   # True en AppPhone (phone_macros/phone_graph lo consultan sin importar la clase)

    def __init__(self, serial=None):
        self.serial = serial or os.environ.get("PHONE_SERIAL") or self._default()
        if not self.serial:
            raise RuntimeError("No hay dispositivo autorizado (adb devices)")

    @staticmethod
    def _default():
        ok = [d["serial"] for d in list_devices() if d["state"] == "device"]
        usb = [s for s in ok if ":" not in s]
        return (usb or ok or [None])[0]

    def adb(self, *a, binary=False, timeout=20):
        return _run(["-s", self.serial] + list(a), binary, timeout)

    def sh(self, cmd):
        return self.adb("shell", cmd).strip()

    def caps(self):
        """Capacidades equivalentes a /v1/capabilities, construidas en local (modo adb)."""
        sdk = self.sh("getprop ro.build.version.sdk").strip()
        try:
            import PIL  # noqa: F401
            pil = True
        except ImportError:
            pil = False
        return {"transport": "adb", "mode": "adb", "sdk": int(sdk) if sdk.isdigit() else None, "version": None, "permissions": None,
                "paused": None, "features": [], "shot": pil, "shot_note": "" if pil else "falta Pillow: pip install pillow",
                "unavailable": phone_help._unavail("adb"),
                "notes": ["adb: sin ids @eN ni espera por cambio (pausa fija de ~1 s tras cada accion); sin pausa/aviso en el telefono",
                          "modo advanced: `phone N advanced start` arranca el asistente shell; despues se usa por el transporte app (`phone app caps`)"]}

    # ---- estado
    def home_package(self):
        out = self.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        for line in out.splitlines():
            if "/" in line:
                return line.strip().split("/")[0]
        return None

    _STATE_CMD = ("dumpsys power | grep mWakefulness=; echo @@; "
                  "dumpsys window | grep -E 'mCurrentFocus=|isKeyguardShowing=|mShowingLockscreen=|mDreamingLockscreen='; echo @@; "
                  "dumpsys input_method | grep -o 'mInputShown=[a-z]*'; echo @@; "
                  "dumpsys window windows | grep -E '^  Window #| fl='")

    def _devcache(self, key, fn, ttl=86400):
        """Valores casi constantes del equipo (launcher, densidad) en logs/dev_<serial>.json: evita llamadas adb en cada comando."""
        path = os.path.join(HERE, "logs", "dev_" + re.sub(r"[^\w.-]", "_", self.serial) + ".json")
        try:
            d = json.load(open(path, encoding="utf-8"))
        except Exception:
            d = {}
        v = d.get(key)
        if v and time.time() - v[1] < ttl:
            return v[0]
        val = fn()
        if val is not None:
            d[key] = [val, time.time()]
            try:
                os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
                json.dump(d, open(path, "w", encoding="utf-8"))
            except Exception:
                pass
        return val

    def state(self):
        parts = (self.sh(self._STATE_CMD).split("@@") + [""] * 4)[:4]
        pw, win, ime, wins = parts
        awake = "mWakefulness=Awake" in pw
        locked = bool(re.search(r"isKeyguardShowing=true|mShowingLockscreen=true|mDreamingLockscreen=true", win))
        m = re.search(r"mCurrentFocus=(Window\{[^\n]*\}|null)", win)
        focus_raw = m.group(1) if m else None
        pm = re.search(r"Window\{\S+ \S+ (?:Application (?:Not Responding|Error): )?([\w.]+)[/ }]", focus_raw or "")
        pkg = pm.group(1) if pm else None
        kind = "anr" if focus_raw and "Not Responding" in focus_raw else ("transicion" if focus_raw in (None, "null") else None)
        self._ime = "mInputShown=true" in ime
        # FLAG_SECURE de la ventana en foco (captura negra, uiautomator suele ver poco)
        secure, cur = False, None
        hm = re.search(r"Window\{(\w+)", focus_raw or "")
        for line in wins.splitlines():
            if line.startswith("  Window #"):
                wm = re.search(r"Window\{(\w+)", line)
                cur = wm.group(1) if wm else None
            elif " fl=" in line and hm and cur == hm.group(1) and re.search(r"\bSECURE\b", line):
                secure = True
        home = self._devcache("home", self.home_package)
        is_home = bool(awake and not locked and pkg and pkg == home)
        return {"serial": self.serial, "screen_on": awake, "locked": locked, "secure": secure,
                "focus_package": pkg, "home_package": home, "is_home": is_home, "focus_kind": kind, "focus_raw": focus_raw}

    def prefetch_state(self):
        """Lanza state() en paralelo con el volcado de pantalla (ahorra ~0.4 s)."""
        import threading
        self._st = {}
        def work():
            try:
                self._st.update(self.state())
            except Exception:
                pass   # take_state() reintenta en el hilo principal y propaga el error
        self._st_t = threading.Thread(target=work, daemon=True)
        self._st_t.start()

    def take_state(self):
        t = getattr(self, "_st_t", None)
        if t:
            t.join(8)
            self._st_t = None
        return self._st if getattr(self, "_st", None) else self.state()

    def fingerprint(self):
        """Huella del dispositivo: marca, Android (sdk) e idioma. Sirve para avisar si un macro se grabo en otro equipo."""
        props = self.sh("getprop ro.product.brand; getprop ro.build.version.sdk; getprop persist.sys.locale; getprop ro.product.locale").splitlines()
        props += [""] * 4
        lang = (props[2] or props[3] or "?").split("-")[0].split("_")[0]
        return {"brand": props[0].strip().upper(), "sdk": props[1].strip(), "lang": lang.strip().lower()}

    # ---- lectura de pantalla
    def density(self):
        def f():
            out = self.sh("wm density").splitlines()
            m = re.search(r"(\d+)\s*$", out[-1]) if out else None
            return int(m.group(1)) if m else None
        return self._devcache("dpi", f) or 420

    def keyboard_visible(self):
        return self._ime if hasattr(self, "_ime") else "mInputShown=true" in self.sh("dumpsys input_method")

    def _dump(self, timeout=14):
        """Volcado FRESCO. Devuelve (xml, error). error: 'idle' (uiautomator no logro estabilidad: no reintentar por archivo,
        tardaria otros 10 s), 'timeout', 'nodump'."""
        try:
            if not getattr(self, "_no_tty", False):
                xml = self.adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=timeout)
                if "could not get idle state" in xml:
                    return None, "idle"
                i, j = xml.find("<?xml"), xml.rfind("</hierarchy>")
                if i >= 0 and j >= 0:
                    return xml[i:j + len("</hierarchy>")], None
                self._no_tty = True   # este equipo no admite /dev/tty: usar archivo en adelante
            self.sh("rm -f /sdcard/_ui.xml; uiautomator dump /sdcard/_ui.xml")
            xml = self.adb("exec-out", "cat", "/sdcard/_ui.xml", timeout=timeout)
        except subprocess.TimeoutExpired:
            return None, "timeout"
        i, j = xml.find("<?xml"), xml.rfind("</hierarchy>")
        return (xml[i:j + len("</hierarchy>")], None) if i >= 0 and j >= 0 else (None, "nodump")

    def read(self, settle=False):
        """settle=True: reintenta con espera creciente (0.3/0.6/1.0/1.5 s, ~8 s max) si el volcado falla, esta casi vacio
        (WebView/Flutter construyen su arbol tarde), o su paquete no coincide con la ventana en foco (transicion)."""
        delays, t0, tries, err, prev, st, warn = [0.3, 0.6, 1.0, 1.5], time.time(), 0, None, None, None, None
        idle_hits = 0
        while True:
            xml, err = self._dump()
            tries += 1
            if not settle:
                break
            if st is None:
                st = self.take_state()
            elif xml is None or tries > 1:
                st = self.state()
            if xml is None:
                if err == "idle":
                    idle_hits += 1
                    if idle_hits > 1:
                        break
                retry = True
            else:
                labeled = len(re.findall(r'(?:text|content-desc)="[^"]', xml))
                dpk = re.search(r'package="([^"]+)"', xml)
                dpk = dpk.group(1) if dpk else None
                fpk = st.get("focus_package")
                mismatch = bool(fpk and "." in fpk and dpk and dpk != fpk)
                lazy = bool(re.search(r"WebView|FlutterView", xml)) and labeled < 3
                retry = mismatch or labeled < 3
                warn = f"volcado de {dpk}, foco en {fpk}" if mismatch else None
                if not retry or (xml and hash(xml) == prev and not mismatch):
                    break
                prev = hash(xml)
                if lazy and tries >= 2:
                    break
            if tries > len(delays) or time.time() - t0 > 8:
                break
            time.sleep(delays[min(tries - 1, len(delays) - 1)])
        if xml is None:
            msg = {"idle": "uiautomator no logra estabilidad (video/animacion): espera y repite", "timeout": "uiautomator no responde (timeout)"}
            msg.update(self.DUMP_MSGS)
            return {"error": msg.get(err, "uiautomator sin datos (pantalla bloqueada/apagada, transicion o contenido en movimiento)")}
        root = ET.fromstring(xml)
        els = []
        absorbed = set()   # nodos hijos cuyo texto/id se fusiona en su boton clickable sin etiqueta
        scrollable = False
        for n in root.iter("node"):
            if n.get("scrollable") == "true":
                scrollable = True
            if id(n) in absorbed:
                continue
            t, d, rid = n.get("text", ""), n.get("content-desc", ""), n.get("resource-id", "")
            click = n.get("clickable") == "true"
            weak = (not click) and (n.get("long-clickable") == "true" or (n.get("focusable") == "true" and bool(d)))
            if not (t or d or click or weak):
                continue
            sub = ""
            if click and not (t or d) and not any(c.get("clickable") == "true" for c in n.iter("node") if c is not n):
                subs, ids = [], []
                for c in n.iter("node"):
                    if c is n:
                        continue
                    ct = (c.get("text", "") or c.get("content-desc", "")).strip()
                    if ct:
                        subs.append(ct)
                        absorbed.add(id(c))
                    elif c.get("resource-id"):
                        ids.append(c.get("resource-id").split("/")[-1])
                        absorbed.add(id(c))
                sub = " ".join(subs)
                if not sub and ids:
                    rid = rid or ids[0]
            b = list(map(int, re.findall(r"\d+", n.get("bounds", "0 0 0 0"))))
            wrapper = bool(click and not (t or d or sub) and any((c.get("text") or c.get("content-desc")) for c in n.iter("node") if c is not n))
            el = {"wrapper": wrapper, "text": t or sub, "desc": d, "id": rid, "class": n.get("class", "").split(".")[-1],
                  "clickable": click, "bounds": b,
                  "center": [(b[0] + b[2]) // 2, (b[1] + b[3]) // 2],
                  "checkable": n.get("checkable") == "true", "checked": n.get("checked") == "true",
                  "selected": n.get("selected") == "true", "enabled": n.get("enabled") != "false",
                  "password": n.get("password") == "true", "edit": "EditText" in n.get("class", ""),
                  "hint": n.get("hint", ""), "weak": weak}
            if n.get("eid"):   # solo modo app (ids @eN)
                el["eid"] = n.get("eid")
            els.append(el)
        first = root.find("node")
        res = {"package": first.get("package") if first is not None else None, "elements": els, "scrollable": scrollable}
        sm = re.search(r'<hierarchy[^>]*\bsnap="(\d+)"', xml)
        if sm:   # solo modo app: numero de lectura en el telefono
            res["snap"] = int(sm.group(1))
        res["views"] = sorted(set(re.findall(r"WebView|FlutterView|GLSurfaceView|SurfaceView|UnityPlayer|TextureView", xml)))
        if settle:
            res["density"] = self.density()
            res["focus_raw"] = (st or {}).get("focus_raw")
            res["secure"] = bool((st or {}).get("secure"))
            if warn:
                res["warn"] = warn
        return res

    def capture(self):
        """Captura como imagen PIL. Usa screencap crudo (~3x mas rapido que -p); respaldo a PNG."""
        import io
        from PIL import Image
        import struct
        raw = self.adb("exec-out", "screencap", binary=True)
        if len(raw) > 12:
            w, h, fmt = struct.unpack("<3I", raw[:12])
            off = len(raw) - w * h * 4
            if fmt == 1 and 12 <= off <= 32 and 0 < w < 10000 and 0 < h < 10000:
                return Image.frombuffer("RGBA", (w, h), raw[off:], "raw", "RGBA", 0, 1).convert("RGB")
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        if png[:4] != PNG_MAGIC:
            return None
        return Image.open(io.BytesIO(png)).convert("RGB")

    def screenshot(self, path="shot.png"):
        png = self.adb("exec-out", "screencap", "-p", binary=True)
        if png[:4] != PNG_MAGIC:
            return {"error": "captura fallida (pantalla apagada o ventana protegida)"}
        with open(path, "wb") as f:
            f.write(png)
        return {"path": os.path.abspath(path), "bytes": len(png)}

    def small_shot(self, path="shot.jpg", width=360, quality=45):
        im = self.capture()
        if im is None:
            return "ERR captura fallida"
        k = im.width / width
        im.resize((width, int(im.height / k))).save(path, "JPEG", quality=quality)
        return f"{os.path.abspath(path)} coord_real = coord_imagen * {k:.2f}"

    # ---- acciones
    def key(self, k): self.sh(f"input keyevent {k}")
    def home(self): self.key(3)
    def back(self): self.key(4)
    def recents(self): self.key(187)
    def sleep(self): self.key(223)
    def wake(self): self.key(224)

    def ensure_home(self, tries=3):
        st = self.state()
        n = 0
        while not st["is_home"] and n < tries:
            if not st["screen_on"]:
                self.wake(); time.sleep(0.6)
            self.home(); time.sleep(0.8)
            st = self.state(); n += 1
        st["pressed_home"] = n
        return st

    def tap(self, x, y): self.sh(f"input tap {int(x)} {int(y)}")

    def tap_text(self, needle):
        """Prioriza coincidencia exacta > empieza con > contiene (evita 'Pantalla' -> 'Pantalla principal...')."""
        n = needle.lower().strip()
        best = None
        for e in self.read().get("elements", []):
            for label in (e["text"], e["desc"]):
                l = label.lower().strip()
                rank = 0 if l == n else 1 if l.startswith(n) else 2 if n in l else None
                if rank is not None and (best is None or rank < best[0]):
                    best = (rank, e)
        if best:
            e = best[1]
            self.tap(*e["center"])
            return {"tapped": e["text"] or e["desc"], "at": e["center"]}
        return {"error": f"no se encontro '{needle}' en pantalla"}

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.sh(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")

    def scroll(self, direction):
        m = re.search(r"(\d+)x(\d+)", self.sh("wm size"))
        w, h = int(m.group(1)), int(m.group(2))
        a, b = (0.7, 0.3) if direction == "down" else (0.3, 0.7)
        self.swipe(w // 2, int(h * a), w // 2, int(h * b), 300)

    def text(self, s):
        special = "&|;<>()$`\\\"'*?[]#~=%!{}^"
        esc = "".join("%s" if c == " " else ("\\" + c if c in special else c) for c in s)
        self.adb("shell", "input", "text", esc)

    def read_text(self):
        return " | ".join(e["text"] or e["desc"] for e in self.read().get("elements", []))

    def scroll_to(self, needle, max_scrolls=8):
        """Hace scroll hacia abajo hasta que 'needle' sea visible. Devuelve el elemento o None."""
        n = needle.lower()
        for _ in range(max_scrolls + 1):
            for e in self.read().get("elements", []):
                if n in e["text"].lower() or n in e["desc"].lower():
                    return e
            self.scroll("down"); time.sleep(0 if self.app_mode else 0.6)   # modo app: el telefono ya espera el cambio
        return None

    def accounts(self):
        """Emails de cuentas Google. dumpsys account oculta los nombres, asi que se lee de Ajustes.
        Camino: intent SYNC_SETTINGS; si abre la raiz de Ajustes (Huawei), scroll a 'cuentas' -> fila Google."""
        mail = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
        types = re.findall(r"type=([\w.]+)", self.sh("dumpsys account"))

        def emails():
            return {m for e in self.read().get("elements", []) for m in mail.findall(e["text"] + " " + e["desc"])}

        self.sh("am force-stop com.android.settings")
        self.sh("am start -a android.settings.SYNC_SETTINGS")
        time.sleep(2)
        found = emails()
        if not found:
            row = self.scroll_to("google", 0)            # fila Google ya visible (lista de cuentas)
            if not row:
                entry = self.scroll_to("cuentas") or self.scroll_to("accounts", 0)
                if entry:
                    self.tap(*entry["center"]); time.sleep(2)
                    row = self.scroll_to("google", 2)
            if row:
                self.tap(*row["center"]); time.sleep(2)
                found = emails()
        self.home()
        return {"google_registered": "com.google" in types, "emails": sorted(found), "types": sorted(set(types))}


    # ---- potencia ADB (solo lectura salvo 'open')
    def info(self):
        """Resumen del equipo: modelo, Android, bateria, pantalla, red/internet, modo avion."""
        g = lambda k: self.sh(f"getprop {k}").strip()
        bat = self.sh("dumpsys battery")
        lvl = re.search(r"level:\s*(\d+)", bat)
        pw = "cargando" if re.search(r"(AC|USB|Wireless) powered: true", bat) else "bateria"
        size = re.search(r"(\d+x\d+)", self.sh("wm size"))
        air = self.sh("settings get global airplane_mode_on").strip() == "1"
        ping = self.sh("ping -c 1 -W 2 8.8.8.8")
        net = "si" if re.search(r"1 (packets )?received|bytes from", ping) else "no"
        wifi = "on" if self.sh("settings get global wifi_on").strip() == "1" else "off"
        up = self.sh("dumpsys power | grep -m1 -E 'mWakefulness='").strip()
        return (f"{g('ro.product.brand')} {g('ro.product.model')} android={g('ro.build.version.release')} sdk={g('ro.build.version.sdk')} "
                f"bateria={lvl.group(1) if lvl else '?'}% ({pw}) pantalla={size.group(1) if size else '?'}@{self.density()}dpi "
                f"wifi={wifi} internet={net} avion={'si' if air else 'no'} {up}")

    def top(self):
        """Actividad en primer plano + ventanas visibles de apps (detecta overlays/dialogos sobre la app)."""
        res = self.sh("dumpsys activity activities")
        m = re.search(r"(?:topResumedActivity|mResumedActivity|ResumedActivity)[^\n]*? ([\w.]+)/([\w.$]+)", res)
        top = f"{m.group(1)}/{m.group(2)}" if m else "?"
        st = self.state()
        wins = []
        for blk in self.sh("dumpsys window windows").split("  Window #")[1:]:
            nm = re.search(r"Window\{\S+ \S+ ([^}]+)\}", blk)
            if not nm:
                continue
            name = nm.group(1).strip()
            vis = "mViewVisibility=0x0" in blk and "mHasSurface=true" in blk
            if vis and not re.search(r"StatusBar|NavigationBar|Wallpaper|InputMethod|ScreenDecor|pointer", name):
                wins.append(name)
        return (f"top={top} foco={st['focus_package']}{' FOCO=' + st['focus_kind'] if st.get('focus_kind') else ''}"
                + NL + "ventanas visibles: " + (" | ".join(list(dict.fromkeys(wins))[:8]) or "(ninguna)"))

    def notifications(self, limit=15):
        """Notificaciones activas: 'paquete: titulo - texto'. Util para ver novedades sin abrir apps."""
        out = self.adb("shell", "dumpsys notification --noredact")
        items, cur = [], None

        def clean(v):
            v = v.strip()
            m = re.match(r"^\w+ \((.*)\)$", v)
            return (m.group(1) if m else v).strip()

        for line in out.splitlines():
            if "NotificationRecord" in line and " pkg=" in line:
                m = re.search(r" pkg=([\w.]+)", line)
                cur = {"pkg": m.group(1) if m else "?", "title": "", "text": ""}
                items.append(cur)
            elif cur is not None:
                t = re.search(r"android\.title=(.*)", line)
                x = re.search(r"android\.text=(.*)", line)
                if t and not cur["title"]:
                    cur["title"] = clean(t.group(1))
                if x and not cur["text"]:
                    cur["text"] = clean(x.group(1))
        rows, seen = [], set()
        for it in items:
            r = f"{it['pkg']}: {it['title']}" + (f" - {it['text']}" if it["text"] else "")
            if (it["title"] or it["text"]) and r not in seen:
                seen.add(r)
                rows.append(r[:160])
        return NL.join(rows[:limit]) if rows else "(sin notificaciones legibles)"

    def apps(self, flt=None):
        """Apps de usuario instaladas (paquetes). Filtro opcional por subcadena."""
        pk = sorted(l.split(":", 1)[1].strip() for l in self.sh("pm list packages -3").splitlines() if ":" in l)
        if flt:
            pk = [x for x in pk if flt.lower() in x.lower()]
        return NL.join(pk) if pk else "(ninguna)"

    def logs(self, pkg=None, n=15):
        """Errores recientes de logcat (de una app si se da paquete, si no del sistema). Para diagnosticar crashes/ANR."""
        pid = self.sh(f"pidof {pkg}").strip().split()[0] if pkg and self.sh(f"pidof {pkg}").strip() else None
        cmd = f"logcat -d -v brief --pid={pid} *:W" if pid else "logcat -d -v brief -t 400 *:E"
        lines = [l for l in self.sh(cmd).splitlines() if l.strip() and not l.startswith("---") and " avc: " not in l]
        key = [l for l in lines if re.search(r"FATAL|ANR|Exception|Error", l)] or lines
        head = f"pid={pid}" if pid else ("(app sin proceso activo)" if pkg else "sistema")
        return head + NL + NL.join(l[:170] for l in key[-n:]) if key else head + NL + "(sin errores recientes)"

    def open_url(self, url):
        """Abre un enlace/deeplink (am start VIEW). Puede mostrar un selector 'Abrir con'."""
        out = self.sh("am start -a android.intent.action.VIEW -d " + "'" + url.replace("'", "") + "'")
        return "ERR " + out.splitlines()[-1] if "Error" in out or "unable" in out.lower() else "ok " + url

    def wait_for(self, target, secs=15):
        """Espera hasta que aparezca un paquete en primer plano (si contiene '.') o un texto visible. Devuelve 'ok ...' o 'ERR timeout'."""
        # 'a|b' = cualquiera de las alternativas (p.ej. 'Abrir|Desinstalar'); devuelve cual aparecio
        end = time.time() + float(secs)
        alts = [t.strip() for t in target.split("|") if t.strip()] or [target]
        pkgs = [t for t in alts if "." in t and " " not in t]
        texts = [t for t in alts if t not in pkgs]
        while time.time() < end:
            if pkgs and self.state().get("focus_package") in pkgs:
                return f"ok {self.state().get('focus_package')} en primer plano"
            if texts:
                for e in self.read().get("elements", []):
                    lab = (e["text"] + " " + e["desc"]).lower()
                    hit = next((t for t in texts if t.lower() in lab), None)
                    if hit:
                        return f"ok '{hit}' visible @{e['center'][0]},{e['center'][1]}"
            time.sleep(0.7 if float(secs) <= 30 else 2.0)
        return f"ERR timeout {secs}s esperando '{target}'"

    def launch(self, name):
        """Acepta paquete o nombre de app. Devuelve 'ok pkg' o 'ERR ...'. Recuerda la app objetivo (logs/target_*.txt)."""
        pkg, err = resolve_app(self, name)
        if err:
            return err
        err = self._start_pkg(pkg)
        if err:
            return err
        try:
            os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
            open(_target_path(self.serial), "w", encoding="utf-8").write(pkg)
        except Exception:
            pass
        return f"ok {pkg}"

    def _start_pkg(self, pkg):
        """Abre el paquete. Devuelve None si fue bien o 'ERR ...'."""
        out = self.sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
        if "No activities found" in out or "monkey aborted" in out.lower() or "Error" in out and "Events injected: 1" not in out:
            return f"ERR no se pudo abrir {pkg}: " + (out.splitlines()[0] if out else "sin salida")
        return None

    def installed_packages(self):
        """Conjunto de paquetes instalados (lo usa resolve_app)."""
        return set(l.split(":", 1)[1].strip() for l in self.sh("pm list packages").splitlines() if ":" in l)

    def label_match(self, key, exact=False):
        """Paquetes cuya etiqueta visible coincide con 'key' (solo modo app; con adb no hay etiquetas baratas)."""
        return []


# ---------------------------------------------------------------------------------------------------------------
# Transporte 'app': el telefono ejecuta la app Mbot Agent (Servicio de Accesibilidad + HTTP en la Wi-Fi), sin adb.
# ---------------------------------------------------------------------------------------------------------------
APP_PORT = 8765
NO_APP = "no disponible en modo app (basico)"


class AppError(RuntimeError):
    """Error del transporte app. kind: 'timeout' | 'conn' | 'auth' | 'http' | 'cmd'; status: codigo HTTP si lo hay."""
    def __init__(self, msg, kind="cmd", status=None):
        super().__init__(msg)
        self.kind, self.status = kind, status


def _conn_path():
    return os.path.join(HERE, "logs", "app_conn.json")


def norm_url(u):
    """'192.168.1.5' | 'http://192.168.1.5:8765/' -> 'http://192.168.1.5:8765'."""
    u = u.strip().rstrip("/")
    if "://" not in u:
        u = "http://" + u
    if not re.search(r":\d+$", u):
        u += f":{APP_PORT}"
    return u


def app_conn():
    """Conexion al telefono: {'url','token'} o None. PHONE_URL / PHONE_TOKEN mandan sobre logs/app_conn.json."""
    try:
        f = json.load(open(_conn_path(), encoding="utf-8"))
    except Exception:
        f = {}
    url = os.environ.get("PHONE_URL") or f.get("url")
    tok = os.environ.get("PHONE_TOKEN") or f.get("token")
    return {"url": norm_url(url), "token": tok or ""} if url else None


_OPENER = None


def _http(conn, method, path, body=None, timeout=10, auth=True):
    """Peticion al telefono. Devuelve (bytes, headers). Lanza AppError con mensaje claro. Sin proxies (es la LAN)."""
    import urllib.request, urllib.error, socket
    global _OPENER
    if _OPENER is None:
        _OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(conn["url"] + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    if auth:
        req.add_header("Authorization", "Bearer " + conn.get("token", ""))
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            return r.read(), r.headers
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            msg = json.loads(raw.decode("utf-8", "replace")).get("error") or ""
        except Exception:
            msg = ""
        if e.code == 401:
            raise AppError("token invalido o revocado: ejecuta 'phone pair' (o 'phone connect URL TOKEN')", "auth", 401)
        if e.code == 403 and (not msg or "direccion" in msg):
            raise AppError("el telefono rechazo esta direccion (solo acepta redes privadas/loopback)", "auth", 403)
        raise AppError(msg or f"HTTP {e.code}", "paused" if e.code == 423 else "http", e.code)
    except (socket.timeout, TimeoutError):
        raise AppError(f"tiempo agotado esperando al telefono ({conn['url']})", "timeout")
    except urllib.error.URLError as e:
        if isinstance(e.reason, (socket.timeout, TimeoutError)):
            raise AppError(f"tiempo agotado esperando al telefono ({conn['url']})", "timeout")
        raise AppError(f"no se alcanza el telefono en {conn['url']} ({e.reason}): misma Wi-Fi, Accesibilidad activa en la app, "
                       "y 'phone connect URL TOKEN' correcto", "conn")
    except OSError as e:
        raise AppError(f"no se alcanza el telefono en {conn['url']} ({e})", "conn")


def _http_json(conn, method, path, body=None, timeout=10, auth=True):
    raw, _ = _http(conn, method, path, body, timeout, auth)
    try:
        return json.loads(raw.decode("utf-8", "replace"))
    except Exception:
        raise AppError("respuesta del telefono no es JSON")


def app_connect(url, token):
    """phone connect: verifica /v1/ping y /v1/capabilities y guarda logs/app_conn.json."""
    conn = {"url": norm_url(url), "token": token}
    try:
        ping = _http_json(conn, "GET", "/v1/ping", timeout=4, auth=False)
        if ping.get("app") != "mbot-access-mobile":
            return "ERR " + conn["url"] + " responde, pero no es la app mbot-access-mobile"
        caps = _http_json(conn, "GET", "/v1/capabilities", timeout=5)
    except AppError as e:
        return "ERR " + str(e)
    try:
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        with open(_conn_path(), "w", encoding="utf-8") as f:
            json.dump(conn, f)
    except Exception as e:
        return f"ERR no se pudo guardar la conexion: {e}"
    model = ""
    try:
        i = _http_json(conn, "POST", "/v1/cmd", {"cmd": "info"}, timeout=6)
        model = f"{i.get('brand', '')} {i.get('model', '')} android={i.get('release', '?')} "
    except AppError:
        pass
    return (f"ok conectado a {conn['url']} {model}sdk={caps.get('sdk')} modo={caps.get('mode')} app v{ping.get('version')} "
            f"shot={'si' if caps.get('shot') else 'no (requiere Android 11+)'}" + NL
            + "usa: phone app look   (o phone look si no hay dispositivos adb)")


def _local_ipv4s():
    """IPv4 locales utiles para enviar la consulta mDNS por cada interfaz (sin loopback ni link-local)."""
    import socket
    ips = set()
    try:
        for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(ai[4][0])
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("224.0.0.251", 5353))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith(("127.", "169.254.", "0.")))


def _dns_qname(name):
    out = b""
    for part in name.strip(".").split("."):
        b = part.encode("utf-8")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def _dns_query(questions):
    """Consulta mDNS minima: [(nombre, tipo)]. Bit QU (0x8000) en la clase: pide respuesta unicast (sirve aunque el firewall corte el multicast)."""
    import struct
    pkt = struct.pack(">HHHHHH", 0, 0, len(questions), 0, 0, 0)
    for name, qtype in questions:
        pkt += _dns_qname(name) + struct.pack(">HH", qtype, 0x8001)
    return pkt


def _dns_name(data, off):
    """Lee un nombre DNS con compresion. Devuelve (nombre, offset_siguiente)."""
    labels, jumped, end, hops = [], False, off, 0
    while True:
        if off >= len(data) or hops > 40:
            raise ValueError("nombre DNS invalido")
        n = data[off]
        if n == 0:
            off += 1
            break
        if n & 0xC0 == 0xC0:
            ptr = ((n & 0x3F) << 8) | data[off + 1]
            if not jumped:
                end = off + 2
            off, jumped, hops = ptr, True, hops + 1
            continue
        labels.append(data[off + 1:off + 1 + n].decode("utf-8", "replace"))
        off += 1 + n
    return ".".join(labels), (end if jumped else off)


def _dns_parse(data):
    """Devuelve la lista de registros (nombre, tipo, valor): PTR->nombre, SRV->(puerto, destino), A->ip, TXT->dict."""
    import struct
    recs = []
    qd, an, ns, ar = struct.unpack(">HHHH", data[4:12])
    off = 12
    for _ in range(qd):
        _, off = _dns_name(data, off)
        off += 4
    for _ in range(an + ns + ar):
        name, off = _dns_name(data, off)
        rtype, _cls, _ttl, rdlen = struct.unpack(">HHIH", data[off:off + 10])
        off += 10
        rd = data[off:off + rdlen]
        if rtype == 12:
            recs.append((name, 12, _dns_name(data, off)[0]))
        elif rtype == 33 and rdlen >= 7:
            port = struct.unpack(">H", rd[4:6])[0]
            recs.append((name, 33, (port, _dns_name(data, off + 6)[0])))
        elif rtype == 1 and rdlen == 4:
            recs.append((name, 1, ".".join(str(b) for b in rd)))
        elif rtype == 16:
            kv, i = {}, 0
            while i < len(rd):
                n = rd[i]
                item = rd[i + 1:i + 1 + n].decode("utf-8", "replace")
                if "=" in item:
                    k, v = item.split("=", 1)
                    kv[k.lower()] = v
                i += 1 + n
            recs.append((name, 16, kv))
        off += rdlen
    return recs


def mdns_discover(timeout=2.5):
    """Busca telefonos con la app (_mbot._tcp) por mDNS, solo con la libreria estandar. Devuelve [{name,ip,port,model,api}]."""
    import socket, select
    svc = "_mbot._tcp.local"
    socks = []
    for ip in (_local_ipv4s() or [""]):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind((ip, 0))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            if ip:
                s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
            socks.append(s)
        except OSError:
            pass
    if not socks:
        return []
    ptr, srv, addr, txt, src = {}, {}, {}, {}, {}
    q1 = _dns_query([(svc, 12)])
    sent2 = set()
    end = time.time() + timeout
    next_send, sends = 0.0, 0
    t0 = time.time()
    while time.time() < end:
        now = time.time() - t0
        if now >= next_send and sends < 3:
            for s in socks:
                try:
                    s.sendto(q1, ("224.0.0.251", 5353))
                except OSError:
                    pass
            sends += 1
            next_send = now + 0.8
        r, _, _ = select.select(socks, [], [], 0.15)
        for s in r:
            try:
                data, frm = s.recvfrom(4096)
                recs = _dns_parse(data)
            except (OSError, ValueError, IndexError):
                continue
            for name, rtype, val in recs:
                if rtype == 12 and name.lower() == svc:
                    ptr[val] = frm[0]
                    src[val] = frm[0]
                elif rtype == 33:
                    srv[name] = val
                    src.setdefault(name, frm[0])
                elif rtype == 1:
                    addr[name.lower()] = val
                elif rtype == 16:
                    txt[name] = val
        # instancias sin SRV/TXT: preguntarlos directamente
        for inst in list(ptr):
            if inst not in srv and inst not in sent2:
                sent2.add(inst)
                q2 = _dns_query([(inst, 33), (inst, 16)])
                for s in socks:
                    try:
                        s.sendto(q2, ("224.0.0.251", 5353))
                    except OSError:
                        pass
        if ptr and all(i in srv for i in ptr) and now > 1.0:
            break
    for s in socks:
        s.close()
    out = []
    for inst, frm_ip in ptr.items():
        if inst not in srv:
            continue
        port, target = srv[inst]
        kv = txt.get(inst, {})
        out.append({"name": inst.split("._mbot")[0], "ip": addr.get(target.lower()) or frm_ip, "port": port,
                    "model": kv.get("model", ""), "api": kv.get("api", "")})
    return out


def _client_name():
    import socket
    try:
        return socket.gethostname() or "PC"
    except OSError:
        return "PC"


def app_pair(url, code):
    """POST /v1/pair (sin token) con el codigo de la app; guarda el token NUEVO de este PC en logs/app_conn.json."""
    conn = {"url": norm_url(url), "token": ""}
    code = str(code).strip()
    if not re.fullmatch(r"\d{6}", code):
        return "ERR el codigo son 6 digitos (los muestra la app en 'Vincular un PC')"
    try:
        ping = _http_json(conn, "GET", "/v1/ping", timeout=4, auth=False)
        if ping.get("app") != "mbot-access-mobile":
            return "ERR " + conn["url"] + " responde, pero no es la app mbot-access-mobile"
        r = _http_json(conn, "POST", "/v1/pair", {"code": code, "client": _client_name()}, timeout=8, auth=False)
    except AppError as e:
        return "ERR " + str(e)
    if not r.get("ok") or not r.get("token"):
        return "ERR " + str(r.get("error") or "el telefono no entrego token")
    conn["token"] = r["token"]
    try:
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        with open(_conn_path(), "w", encoding="utf-8") as f:
            json.dump({"url": conn["url"], "token": conn["token"], "name": r.get("model", "")}, f)
    except Exception as e:
        return f"ERR vinculado, pero no se pudo guardar la conexion: {e}"
    return f"ok vinculado con {r.get('model', 'el telefono')} en {conn['url']} (token propio de este PC; se puede revocar desde la app)" + NL + \
           "usa: phone app look   (o phone look si no hay dispositivos adb)"


def app_pair_cmd(args):
    """phone pair [CODIGO] | phone pair IP CODIGO."""
    ip = code = None
    for x in args:
        if re.fullmatch(r"\d{6}", x):
            code = x
        else:
            ip = x
    if ip:   # forma directa: sin mDNS (routers que bloquean multicast)
        if not code:
            code = _ask_code()
        return app_pair(ip, code) if code else "ERR uso: phone pair IP CODIGO (el codigo de 6 digitos lo muestra la app en 'Vincular un PC')"
    found = mdns_discover()
    if not found:
        return ("ERR no se encontro ningun telefono por mDNS en 3 s. Causas habituales: el router aisla los equipos o bloquea multicast, "
                "el Firewall de Windows bloquea las respuestas UDP a python.exe (permite Python en redes privadas), o la app/Accesibilidad no esta activa "
                "(misma Wi-Fi que el PC)." + NL + "Alternativa: phone pair IP CODIGO   (la IP aparece en la app, p. ej. phone pair 192.168.1.20 123456)")
    lines = [f"{i} {f['name']} http://{f['ip']}:{f['port']}" + (f" (api {f['api']})" if f["api"] else "") for i, f in enumerate(found, 1)]
    if len(found) > 1:
        return NL.join(lines) + NL + "ERR hay varios telefonos: usa phone pair IP CODIGO con el que quieras"
    f = found[0]
    if not code:
        code = _ask_code()
        if not code:
            return NL.join(lines) + NL + "ERR falta el codigo: abre la app > 'Vincular un PC' y ejecuta: phone pair CODIGO"
    return lines[0] + NL + app_pair(f"{f['ip']}:{f['port']}", code)


def _ask_code():
    try:
        if sys.stdin and sys.stdin.isatty():
            sys.stderr.write("Codigo de 6 digitos que muestra la app: ")
            sys.stderr.flush()
            c = sys.stdin.readline().strip()
            return c or None
    except Exception:
        pass
    return None


def app_disconnect():
    try:
        os.remove(_conn_path())
        return "ok desconectado (logs/app_conn.json borrado)" + (
            "; PHONE_URL sigue definido en el entorno" if os.environ.get("PHONE_URL") else "")
    except FileNotFoundError:
        return "ok no habia conexion guardada" + ("; PHONE_URL sigue definido en el entorno" if os.environ.get("PHONE_URL") else "")


def app_device_row():
    """Fila para 'devices': dict con serial 'app' o None si no hay conexion. Consulta info con timeout corto."""
    conn = app_conn()
    if not conn:
        return None
    model, state = "", "device"
    try:
        i = _http_json(conn, "POST", "/v1/cmd", {"cmd": "info"}, timeout=3)
        model = (i.get("model") or "").replace(" ", "_")
    except AppError as e:
        state = "offline" if e.kind in ("conn", "timeout") else "unauthorized"
    return {"serial": "app", "state": state, "info": f"model:{model} url:{conn['url']}", "url": conn["url"], "model": model}


class AppPhone(Phone):
    """Phone sobre la app Mbot Agent (HTTP + Accesibilidad). Mismas primitivas que Phone; lo que depende de adb se reimplementa
    o devuelve 'no disponible en modo app (basico)'. Los errores se lanzan como AppError (main() los imprime como 'ERR ...')."""

    app_mode = True
    vd_target = False   # True mientras `vd look` lee la app de la pantalla virtual en vez de la pantalla real
    DUMP_MSGS = {"timeout": "el telefono no responde al volcado (timeout)",
                 "nodump": "sin elementos legibles: pantalla bloqueada/apagada, app propia (Mbot Agent no se vuelca) o ventana sin arbol de accesibilidad"}

    def __init__(self, serial=None):
        self.serial = "app"
        self.conn = app_conn()
        if not self.conn:
            raise AppError("no hay conexion con el telefono: ejecuta 'phone connect http://IP:8765 TOKEN' (o define PHONE_URL y PHONE_TOKEN)", "conn")
        self._dens = None
        self._pk = None
        self.last = {}   # ultima respuesta de una accion: changed, ms, channel, riesgo, label

    # -- bajo nivel
    def cmd(self, name, **kw):
        r = _http_json(self.conn, "POST", "/v1/cmd", dict(cmd=name, **kw), timeout=15)
        if not r.get("ok", False):
            raise AppError(r.get("error") or "error del telefono", "paused" if r.get("paused") else "cmd")
        if "changed" in r:   # comandos que actuan: el telefono ya espero el cambio de pantalla
            self.last = r
        return r

    def adb(self, *a, binary=False, timeout=20):
        raise AppError(NO_APP)

    def sh(self, cmd):
        raise AppError(NO_APP)

    def caps(self):
        c = _http_json(self.conn, "GET", "/v1/capabilities", timeout=6)
        c["transport"] = "app"
        c["features"] = c.get("features") or []
        c["permissions"] = c.get("permissions") or ({"notifications": c["notifs"]} if "notifs" in c else None)
        c["version"] = c.get("version")
        c["shot_note"] = "" if c.get("shot") else "requiere Android 11+"
        c["unavailable"] = phone_help._unavail("app")
        h = c.get("helper") or {}
        if c.get("mode") == "advanced":
            c["unavailable"] = [x for x in c["unavailable"] if x != "wake"]
            c["notes"] = ["modo advanced: asistente shell activo (toques/teclas inyectados, wake, captura en Android < 11, portapapeles `clip`, pantalla virtual `vd`)"]
            if h.get("stale"):
                c["notes"].append("el asistente es de otra version de la app: reinicialo con `phone N advanced start`")
        else:
            c["unavailable"] = c["unavailable"] + ["wake"]
            c["notes"] = ["modo basic: asistente parado; para el modo avanzado (wake, ENTER < Android 11, shot < Android 11, clip, vd): `phone N advanced start` (por USB)"]
        return c

    # -- estado
    def state(self):
        st = _http_json(self.conn, "GET", "/v1/state", timeout=8)
        st["serial"] = "app"
        self._ime = bool(st.get("ime_visible"))
        self._dens = st.get("density") or self._dens
        return st

    def home_package(self):
        return self.state().get("home_package")

    def density(self):
        return self._dens or self.state().get("density") or 420

    def keyboard_visible(self):
        return self._ime if hasattr(self, "_ime") else bool(self.state().get("ime_visible"))

    def fingerprint(self):
        i = self.cmd("info")
        return {"brand": (i.get("brand") or "").upper(), "sdk": str(i.get("sdk", "")), "lang": (i.get("locale") or "?").lower()}

    def _dump(self, timeout=14):
        try:
            raw, _ = _http(self.conn, "GET", "/v1/dump?display=vd" if self.vd_target else "/v1/dump", timeout=timeout)
        except AppError as e:
            if e.kind == "timeout":
                return None, "timeout"
            raise
        xml = raw.decode("utf-8", "replace")
        i, j = xml.find("<?xml"), xml.rfind("</hierarchy>")
        if i < 0 or j < 0 or "<node" not in xml:
            return None, "nodump"
        return xml[i:j + len("</hierarchy>")], None

    # -- captura (Android 11+)
    def _shot_bytes(self, w, q):
        raw, hdr = _http(self.conn, "GET", f"/v1/shot?w={int(w)}&q={int(q)}", timeout=12)
        try:
            k = float(hdr.get("X-Scale") or 1)
        except ValueError:
            k = 1.0
        return raw, k

    def capture(self):
        import io
        from PIL import Image
        try:
            raw, _ = self._shot_bytes(0, 85)   # w=0: tamano real (los recortes de iconos usan coordenadas reales)
        except AppError:
            return None
        return Image.open(io.BytesIO(raw)).convert("RGB")

    def screenshot(self, path="shot.jpg"):
        try:
            raw, _ = self._shot_bytes(0, 90)
        except AppError as e:
            return {"error": str(e)}
        with open(path, "wb") as f:
            f.write(raw)   # JPEG (el modo app no entrega PNG)
        return {"path": os.path.abspath(path), "bytes": len(raw)}

    def small_shot(self, path="shot.jpg", width=360, quality=45):
        try:
            raw, k = self._shot_bytes(width, quality)
        except AppError as e:
            return "ERR " + str(e)
        with open(path, "wb") as f:
            f.write(raw)
        return f"{os.path.abspath(path)} coord_real = coord_imagen * {k:.2f}"

    # -- acciones
    def key(self, k):
        self.cmd("key", key=str(k))

    def resolve_package(self, name):
        """(paquete, None) | (None, 'ERR ...'): mismo resolvedor que launch (alias de fichas, etiqueta, paquete)."""
        return resolve_app(self, name)

    def vd_shot(self, path="vd_shot.jpg", width=360, quality=45):
        """Captura de la pantalla virtual (JPEG pequeno, como shot)."""
        try:
            raw, hdr = _http(self.conn, "GET", f"/v1/shot?display=vd&w={int(width)}&q={int(quality)}", timeout=12)
        except AppError as e:
            return "ERR " + str(e)
        try:
            k = float(hdr.get("X-Scale") or 1)
        except ValueError:
            k = 1.0
        with open(path, "wb") as f:
            f.write(raw)
        return f"{os.path.abspath(path)} coord_real = coord_imagen * {k:.2f}"

    def tap(self, x, y):
        self.cmd("tap", x=int(float(x)), y=int(float(y)))

    def tap_node(self, ref, snap=None):
        """tap @eN: clic por nodo en el telefono. Usa la lectura (snap) en la que 'look' mostro ese id."""
        eid = ref.lstrip("@")
        if snap is None:
            snap = _load_ids().get(eid)
        kw = {"id": eid}
        if snap:
            kw["snap"] = int(snap)
        r = self.cmd("tap_node", **kw)
        return r

    def tap_text(self, needle):
        """Como Phone.tap_text, pero prefiere el clic por nodo (id @eN) al toque por coordenadas."""
        n = needle.lower().strip()
        best = None
        rd = self.read()
        for e in rd.get("elements", []):
            for label in (e["text"], e["desc"]):
                l = label.lower().strip()
                rank = 0 if l == n else 1 if l.startswith(n) else 2 if n in l else None
                if rank is not None and (best is None or rank < best[0]):
                    best = (rank, e)
        if not best:
            return {"error": f"no se encontro '{needle}' en pantalla"}
        e = best[1]
        via = None
        if e.get("eid"):
            via = self.tap_node(e["eid"], rd.get("snap")).get("channel")
        else:
            self.tap(*e["center"])
        return {"tapped": e["text"] or e["desc"], "at": e["center"], "via": via}

    def ensure_home(self, tries=3):
        st = self.state()
        n = 0
        while not st["is_home"] and n < tries:
            if not st["screen_on"]:
                self.wake()
            self.home()
            st = self.state(); n += 1
        st["pressed_home"] = n
        return st

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.cmd("swipe", x1=int(float(x1)), y1=int(float(y1)), x2=int(float(x2)), y2=int(float(y2)), ms=int(float(ms)))

    def scroll(self, direction):
        st = self.state()
        w, h = st["width"], st["height"]
        a, b = (0.7, 0.3) if direction == "down" else (0.3, 0.7)
        self.swipe(w // 2, int(h * a), w // 2, int(h * b), 300)

    def text(self, s):
        self.cmd("text", text=s)   # Unicode completo (acentos, emoji); necesita un campo de texto con foco

    def _start_pkg(self, pkg):
        try:
            self.cmd("launch", package=pkg)
        except AppError as e:
            return f"ERR no se pudo abrir {pkg}: {e}"
        return None

    def open_url(self, url):
        try:
            self.cmd("open", url=url)
        except AppError as e:
            return "ERR " + str(e)
        return "ok " + url

    # -- apps / info
    def _apps(self):
        if self._pk is None:
            self._pk = self.cmd("apps").get("apps", [])
        return self._pk

    def installed_packages(self):
        return set(a["package"] for a in self._apps())

    def label_match(self, key, exact=False):
        k = key.lower().strip()
        return sorted(a["package"] for a in self._apps()
                      if (a["label"].lower().strip() == k if exact else k in a["label"].lower()))

    def apps(self, flt=None):
        """Apps lanzables: sin filtro, solo las de usuario (como 'pm list packages -3'); con filtro tambien las del sistema.
        Formato 'paquete (etiqueta)'; el filtro busca en ambos."""
        rows = []
        for a in self._apps():
            if flt:
                if flt.lower() not in (a["package"] + " " + a["label"]).lower():
                    continue
            elif a.get("system"):
                continue
            rows.append(f"{a['package']} ({a['label']})")
        return NL.join(sorted(rows)) if rows else "(ninguna)"

    def info(self):
        i = self.cmd("info")
        st = self.state()
        return (f"{i.get('brand', '')} {i.get('model', '')} android={i.get('release')} sdk={i.get('sdk')} "
                f"bateria={i.get('battery')}% ({'cargando' if i.get('charging') else 'bateria'}) "
                f"pantalla={i.get('width')}x{i.get('height')}@{i.get('density')}dpi "
                f"wifi={'on' if i.get('wifi_on') else 'off'} red={i.get('network')} internet={'si' if i.get('internet') else 'no'} "
                f"avion={'si' if i.get('airplane') else 'no'} screen={'on' if st.get('screen_on') else 'off'} modo=app")

    def top(self):
        """Version minima: paquete en foco + paquetes de las ventanas que aparecen en el volcado."""
        st = self.state()
        xml, _ = self._dump()
        pk = list(dict.fromkeys(re.findall(r'<node index="\d+"[^>]*? package="([^"]+)"', xml or "")))
        return (f"top=? foco={st['focus_package']}{' FOCO=' + st['focus_kind'] if st.get('focus_kind') else ''}"
                + NL + "ventanas visibles: " + (" | ".join(pk[:8]) or "(ninguna)"))

    def logs(self, pkg=None, n=15):
        raise AppError(NO_APP)

    def notifications(self, limit=15):
        """Notificaciones activas via NotificationListenerService de la app. Mismo formato que con adb: 'paquete: titulo - texto'."""
        r = _http_json(self.conn, "GET", "/v1/notifs", timeout=10)
        if not r.get("ok", False):
            raise AppError(r.get("error") or "error del telefono")
        rows, seen = [], set()
        for it in r.get("notifs", []):
            t, x = " ".join((it.get("title") or "").split()), " ".join((it.get("text") or "").split())   # una fila por notificacion
            row = f"{it.get('package', '?')}: {t}" + (f" - {x}" if x else "")
            if (t or x) and row not in seen:
                seen.add(row)
                rows.append(row[:160])
        return NL.join(rows[:limit]) if rows else "(sin notificaciones legibles)"

    def accounts(self):
        raise AppError(NO_APP)

def model_of(d):
    m = re.search(r"model:(\S+)", d["info"])
    return (m.group(1) if m else "") + (" (wifi)" if ":" in d["serial"] else " (usb)")


def _safe_devices():
    """list_devices() que no aborta si adb no esta instalado (en modo app no hace falta)."""
    try:
        return list_devices()
    except (SystemExit, Exception):
        return []


def want_app(serial):
    """True si hay que usar el transporte app: dispositivo 'app', PHONE_URL definido, o conexion guardada y ningun adb autorizado."""
    if serial:
        return serial == "app"
    if os.environ.get("PHONE_SERIAL"):
        return False
    if os.environ.get("PHONE_URL"):
        return True
    return bool(app_conn()) and not any(d["state"] == "device" for d in _safe_devices())


def make_phone(serial=None):
    return AppPhone(serial) if want_app(serial) else Phone(serial)


def resolve_device(ref):
    """ref = numero de la lista 'devices' (1,2..) o serial exacto."""
    if ref.lower() == "app":
        return "app"
    devs = _safe_devices()
    if ref.isdigit() and 1 <= int(ref) <= len(devs):
        return devs[int(ref) - 1]["serial"]
    if ref.isdigit() and int(ref) == len(devs) + 1 and app_conn():   # la conexion app va al final de 'phone devices'
        return "app"
    if ref.isdigit():
        sys.exit(f"ERR dispositivo {ref} no existe; usa: phone devices")
    return ref


def live(serial):
    sc = find_scrcpy()
    if not sc:
        return "ERR " + INSTALL_HINT["scrcpy"]
    env = dict(os.environ, ADB=ADB)
    subprocess.Popen([sc, "-s", serial, "--no-audio", "-m", "800", "-b", "4M", "--max-fps", "30",
                      "--prefer-text", "--stay-awake", "--window-title", "PhoneLive"], env=env)
    return "ok vista en vivo abierta"


COMMANDS = set(phone_help.NAMES)   # fuente unica: phone_help.SPECS


TRACE_CMDS = {"tap", "tap_text", "scroll", "scroll_to", "launch", "back", "key", "text", "home"}


def trace(serial, cmd, a, out):
    """Registra acciones exitosas en logs/trace.jsonl (insumo de 'remember'): accion, args, pkg y etiquetas de la pantalla resultante."""
    try:
        pkg, labels = None, []
        if NL + "--" + NL in out:
            for line in out.split(NL + "--" + NL, 1)[1].splitlines():
                if line.startswith("pkg="):
                    pkg = line[4:]
                elif not line.startswith(("HOME", "NO-HOME", "=")):
                    labels.append(re.sub(r"^@e\d+ ", "", re.sub(r" @\d+,\d+( [*~])?$", "", line)))
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        with open(os.path.join(HERE, "logs", "trace.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps({"ts": time.time(), "serial": serial, "do": cmd, "args": " ".join(a), "pkg": pkg,
                                "labels": labels[:40]}, ensure_ascii=False) + NL)
    except Exception:
        pass


LOOK_AFTER = {"tap", "tap_text", "scroll", "scroll_to", "launch", "back", "key", "swipe"}


def _grep_lines(lines, grep):
    alts = [x.strip().lower() for x in grep.split("|") if x.strip()]
    return [l for l in lines if any(x in l.lstrip("+-").rsplit(" @", 1)[0].lower() for x in alts)]


def _cache_path(serial):
    return os.path.join(HERE, "logs", "last_" + re.sub(r"[^\w.-]", "_", serial) + ".json")


_ICON_WORDS = {"AT", "PLUS", "CHECK", "CLOSE", "SEARCH", "MORE", "BACK", "MENU", "CHEVRON", "ARROW", "STAR", "HEART"}
_HINTS = [(("google", "gsi"), "Google"), (("apple",), "Apple"), (("facebook", "fb"), "Facebook"),
          (("mail", "email"), "correo"), (("phone", "sms", "call"), "telefono"), (("passkey",), "passkey"),
          (("fingerprint", "biometric"), "huella"), (("notification", "notifications", "bell"), "notificaciones"),
          (("search",), "buscar"), (("menu", "hamburger"), "menu"), (("close", "dismiss"), "cerrar"),
          (("back", "up"), "atras"), (("share",), "compartir"), (("more", "overflow"), "mas opciones"),
          (("settings", "gear"), "ajustes"), (("camera",), "camara")]
APP_ALIASES = {"x": "com.twitter.android", "twitter": "com.twitter.android", "facebook": "com.facebook.katana",
               "messenger": "com.facebook.orca", "instagram": "com.instagram.android", "whatsapp": "com.whatsapp",
               "telegram": "org.telegram.messenger", "spotify": "com.spotify.music", "tiktok": "com.zhiliaoapp.musically",
               "chrome": "com.android.chrome", "play store": "com.android.vending", "playstore": "com.android.vending",
               "google play": "com.android.vending", "ajustes": "com.android.settings", "configuracion": "com.android.settings",
               "settings": "com.android.settings", "youtube": "com.google.android.youtube", "gmail": "com.google.android.gm",
               "netflix": "com.netflix.mediaclient", "linkedin": "com.linkedin.android", "snapchat": "com.snapchat.android"}
_LOGIN_RE = r"iniciar sesi|log ?in\b|sign ?in|continuar con|continue with|crear cuenta|create account|reg[ií]strate|sign ?up|contrase|password"


def _ids_path():
    return os.path.join(HERE, "logs", "app_ids.json")


def _load_ids():
    """{eid: lectura} de los ids que 'look' mostro (modo app). Cada id pertenece a la lectura en que se imprimio."""
    try:
        return json.load(open(_ids_path(), encoding="utf-8"))
    except Exception:
        return {}


def _save_ids(snap, eids, merge=False):
    try:
        ids = _load_ids() if merge else {}
        for e in eids:
            ids[e] = snap
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        with open(_ids_path(), "w", encoding="utf-8") as f:
            json.dump(ids, f)
    except Exception:
        pass


def _clean_label(t):
    """Quita tokens decorativos de icono (<icon>, CHEVRON_RIGHT...). Palabras sueltas (AT, CLOSE...) solo si hay <icon>."""
    has_icon = "<icon>" in t
    toks = [w for w in t.replace("<icon>", " ").split()
            if not (re.fullmatch(r"[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+", w) or (has_icon and w in _ICON_WORDS))]
    c = " ".join(toks).strip(" |")
    return c or t.replace("<icon>", " ").strip()


def _hint_from_id(rid):
    toks = set(t for t in re.split(r"[^a-z0-9]+", (rid or "").split("/")[-1].lower()) if t)
    for keys, v in _HINTS:
        if toks & set(keys):
            return v
    return ""


def _target_path(serial):
    return os.path.join(HERE, "logs", "target_" + re.sub(r"[^\w.-]", "_", serial) + ".txt")


def _get_target(serial):
    try:
        return open(_target_path(serial), encoding="utf-8").read().strip() or None
    except Exception:
        return None


def resolve_app(p, name):
    """nombre de app o paquete -> (paquete, None) | (None, mensaje de error). Alias conocidos > ultimo segmento exacto > subcadena."""
    n = name.strip()
    if "." in n:
        return n, None
    key = n.lower()
    pk = p.installed_packages()
    try:
        import phone_knowledge
        kp = [x for x in phone_knowledge.packages_for(key) if x in pk]
        if kp:   # alias/nombre de una ficha de conocimiento -> primer paquete instalado
            return kp[0], None
    except Exception:
        pass
    if key in APP_ALIASES and APP_ALIASES[key] in pk:
        return APP_ALIASES[key], None
    lab = p.label_match(key, exact=True)
    if len(lab) == 1:
        return lab[0], None
    flat = re.sub(r"\W+", "", key)
    exact = sorted(x for x in pk if x.split(".")[-1].lower() == flat)
    if len(exact) == 1:
        return exact[0], None
    cand = exact or sorted(x for x in pk if flat in x.lower())
    if len(cand) == 1:
        return cand[0], None
    if cand:
        return None, f"ERR '{name}' ambiguo: " + ", ".join(cand[:8])
    lab = p.label_match(key)
    if len(lab) == 1:
        return lab[0], None
    if lab:
        return None, f"ERR '{name}' ambiguo: " + ", ".join(lab[:8])
    return None, f"ERR app '{name}' no instalada (usa paquete exacto o instala desde Play Store)"


def classify_screen(pkg, text, focus_raw, target):
    """Detecta overlays/dialogos de sistema. Devuelve (clave, mensaje) o (None, None) para pantalla normal."""
    low = text.lower()
    fr = (focus_raw or "").lower()
    if "not responding" in fr or (pkg == "android" and re.search(r"no responde|isn't responding|not responding", low)):
        return "anr", "DIALOGO SISTEMA: una app no responde. Opciones: ESPERAR (recomendado) | CERRAR APP"
    if pkg and pkg.startswith("com.google.android.gms") and pkg != target:
        return "overlay", ("OVERLAY de Google (gestor de contraseñas / inicio con Google)" + (f" sobre {target}" if target else "")
                           + ": no es el formulario de la app. Cierralo ('Cerrar'/'X') para ver el login real; "
                             "no uses el autocompletado sin orden del usuario")
    chooser = bool(re.search(r"abrir con|open with|solo una vez|just once", low))
    if (pkg and ("permissioncontroller" in pkg or "packageinstaller" in pkg)) or re.search(r"resolveractivity|chooseractivity", fr) \
            or (pkg and pkg.endswith("internal.app") and chooser):
        kind = "SELECTOR 'Abrir con'" if chooser else "DIALOGO SISTEMA: permiso/instalador"
        return "sys", kind + ": elige una opcion de la lista (no es la pantalla de la app)"
    return None, None


# Obstaculos universales: (etiqueta, regex sobre el texto visible, nivel, consejo). Nivel A = el agente puede descartarlo;
# B = pedir al usuario antes de actuar; STOP = no tocar nada y avisar.
OBSTACLES = [
    ("CAPTCHA", r"captcha|no soy un robot|i'?m not a robot|verifica que eres humano|verify you are human|selecciona todas las", "B",
     "no resolver ni evadir; pedir al usuario que lo resuelva"),
    ("VERIFICACION/2FA", r"c[oó]digo de (verificaci[oó]n|seguridad|confirmaci[oó]n)|verification code|security code|two-?factor|doble factor|enter the code|ingresa el c[oó]digo", "B",
     "pedir el codigo al usuario; no adivinar ni reenviar en bucle"),
    ("ALERTA DE CUENTA", r"cuenta (suspendida|inhabilitada|bloqueada)|account (suspended|disabled|locked)|actividad (inusual|sospechosa)|unusual activity|suspicious", "STOP",
     "no tocar nada; informar al usuario"),
    ("PAGO/COMPRA", r"\b(pagar|comprar|suscribirse|subscribe|purchase|checkout|place order|realizar pedido|confirmar compra|buy now)\b", "B",
     "no confirmar sin orden explicita (item y total)"),
    ("CONFIRMACION DESTRUCTIVA", r"(eliminar|borrar|delete|remove|desinstalar|uninstall|cerrar sesi[oó]n|log ?out|sign ?out|vaciar|restablecer|reset).{0,80}(cancelar|cancel)|(cancelar|cancel).{0,80}(eliminar|borrar|delete|remove|desinstalar|uninstall|cerrar sesi[oó]n|log ?out)", "B",
     "pedir permiso; por defecto elegir Cancelar"),
    ("PERMISO", r"permitir|allow|while using the app|mientras la app|solo esta vez|only this time|no permitir|don'?t allow", "B",
     "no conceder por defecto; si la tarea no lo necesita elegir 'No permitir'; si lo necesita, confirmar con el usuario"),
    ("CONSENTIMIENTO/COOKIES", r"(?=.*(cookies|consentimiento|consent))(?=.*(aceptar todo|accept all|rechazar|reject|decline|solo esenciales|only essential|gestionar|manage))", "B",
     "preferir 'Rechazar'/'Solo esenciales'; aceptar terminos o crear cuenta requiere al usuario"),
    ("ERROR/REINTENTO", r"sin conexi[oó]n|no internet|sin internet|algo sali[oó] mal|something went wrong|intentar de nuevo|reintentar|try again|retry|no se pudo|couldn'?t load", "A",
     "reintentar UNA vez (espera 2s); si persiste, informar causa visible"),
    ("POPUP DESCARTABLE", r"ahora no|not now|m[aá]s tarde|maybe later|omitir|skip|no, gracias|no thanks|actualizar app|update available|califica|rate (us|this)|what'?s new|got it|entendido", "A",
     "descartar con 'Ahora no/Mas tarde/Omitir/Cerrar'; nunca 'Actualizar/Suscribir/Permitir'"),
]


def classify_obstacle(text):
    """Devuelve lista de (etiqueta, nivel, consejo) detectados en el texto visible (en minusculas)."""
    return [(t, lv, adv) for t, rx, lv, adv in OBSTACLES if re.search(rx, text)]


def icon_sheet(p, items, path="icons.png", per_row=6):
    """Hoja de contacto en cuadricula: recorta cada boton sin etiqueta de la captura, lo amplia y lo numera.
    Devuelve (ruta, aviso). Aviso no vacio si algun recorte sale negro (ventana protegida FLAG_SECURE)."""
    from PIL import ImageDraw, Image
    im = p.capture() if items else None
    if im is None:
        return None, "captura fallida"
    tiles, black = [], 0
    for num, e in items:
        x0, y0, x1, y1 = e["bounds"]
        c = im.crop((max(x0, 0), max(y0, 0), min(x1, im.width), min(y1, im.height)))
        if c.width and c.height and c.getextrema() == ((0, 0), (0, 0), (0, 0)):
            black += 1
        c = c.resize((160, 160))
        t = Image.new("RGB", (160, 190), "white")
        t.paste(c, (0, 30))
        ImageDraw.Draw(t).text((6, 8), f"#{num} @{e['center'][0]},{e['center'][1]}", fill="red")
        tiles.append(t)
    rows = (len(tiles) + per_row - 1) // per_row
    sheet = Image.new("RGB", (170 * min(per_row, len(tiles)), 190 * rows), "white")
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % per_row) * 170, (i // per_row) * 190))
    sheet.save(path)
    return os.path.abspath(path), ("recortes negros: ventana protegida, usa la captura/usuario" if black else "")


def _smart_trunc(s, n=50):
    """Corta en un limite natural (frase/coma/guion) y dice cuanto falta; conserva el final si es numerico (precio/hora)."""
    if len(s) <= n:
        return s
    for sep in (". ", "; ", ", ", " · ", " | ", " - "):
        k = s.rfind(sep, 0, n)
        if k >= n * 0.6:
            return s[:k] + f"…(+{len(s) - k})"
    if re.search(r"\d", s[-12:]):
        return s[:n - 14].rstrip() + "…" + s[-12:]
    k = s.rfind(" ", 0, n)
    k = k if k > n * 0.6 else n
    return s[:k] + f"…(+{len(s) - k})"


def _contained(a, b):
    return b[0] <= a[0] and b[1] <= a[1] and b[2] >= a[2] and b[3] >= a[3]


def compact_read(p, max_items=40, grep=None, diff=False, full=False, explore=False, want_icons=False):
    """Lista compacta de la pantalla. diff=True: solo +nuevos / -desaparecidos respecto a la ultima pantalla emitida.
    explore=True (look): ademas hace hasta 2 scrolls en pantallas de login desplazables y avisa de teclado visible."""
    r = p.read(settle=True)
    if "error" in r:
        return "ERR " + r["error"]
    dpx = max(r.get("density", 420) / 160.0, 1.0)
    lo, hi = int(18 * dpx), int(160 * dpx)

    def label_of(e):
        if not (e["text"] or e["desc"]):
            return ""
        return _clean_label((e["text"] or e["desc"]).replace(NL, " "))

    els = r["elements"]
    labels = [label_of(e) for e in els]
    clickables = [(e["bounds"], labels[i].lower()) for i, e in enumerate(els) if e["clickable"] and labels[i]]
    # candidatos a icono sin etiqueta, ordenados por (y, x) y numerados
    cand = []
    for i, e in enumerate(els):
        bw, bh = e["bounds"][2] - e["bounds"][0], e["bounds"][3] - e["bounds"][1]
        if not labels[i] and e["clickable"] and not e.get("wrapper") and not e.get("checkable") and lo <= bw <= hi and lo <= bh <= hi:
            cand.append(i)
    cand.sort(key=lambda i: (els[i]["center"][1] // 20, els[i]["center"][0]))
    num = {i: k for k, i in enumerate(cand, 1)}
    seen, lines, unl = set(), [], []
    eidmap = {}   # linea -> id @eN (solo modo app); se antepone al imprimir, las lineas internas no cambian

    def pref(l):
        return ("@" + eidmap[l] + " " + l) if l in eidmap else l

    for i, e in enumerate(els):
        label = labels[i]
        if label and not e["clickable"]:   # dedupe por contencion: texto ya incluido en la etiqueta de un boton que lo contiene
            ll = label.lower()
            if any(ll in cl and _contained(e["bounds"], cb) for cb, cl in clickables):
                continue
        if not label and e.get("checkable") and e["clickable"]:
            label = "interruptor " + (e["id"].split("/")[-1].replace("_", " ") if e["id"] else "sin id")
        if not label and i in num:
            hint = _hint_from_id(e["id"])
            unl.append((num[i], e))
            label = (f"[icono #{num[i]} sin etiqueta" + (f" id={e['id'].split('/')[-1]}" if e["id"] else "")
                     + (f" posible={hint}" if hint else "") + "]")
        key = (label, tuple(e["center"]))
        if not label or key in seen:
            continue
        seen.add(key)
        shown = label if (full or label.startswith("[icono") or e.get("edit")) else _smart_trunc(label)
        pre = ("[x] " if e.get("checked") else "[ ] ") if e.get("checkable") else ""
        if e.get("edit"):
            pre += "[campo pwd] " if e.get("password") else "[campo] "
        suf = (" (sel)" if e.get("selected") else "") + ("" if e.get("enabled", True) else " (inactivo)")
        shown = pre + shown + suf
        extra = f" [{e['class']} {e['id'].split('/')[-1]} {e['bounds']}]" if full else ""
        mark = " *" if e["clickable"] else (" ~" if e.get("weak") else "")
        lines.append(f"{shown} @{e['center'][0]},{e['center'][1]}{mark}{extra}")
        if e.get("eid"):
            eidmap[lines[-1]] = e["eid"]
    pkg = r["package"]
    if not lines:
        if r.get("secure"):
            return f"ESTADO: ventana protegida (FLAG_SECURE, pkg={pkg}): no se puede leer ni capturar. Pide ayuda al usuario"
        why = ("contenido en " + "/".join(r["views"]) + " sin accesibilidad") if r.get("views") else "cargando o contenido no accesible"
        shot = ""
        try:
            ss = p.small_shot(os.path.join(HERE, "logs", "auto_shot.jpg"))
            shot = "" if ss.startswith("ERR") else " Captura automatica: " + ss
        except Exception:
            pass
        return f"ESTADO: pantalla sin elementos legibles (pkg={pkg}; {why}). Espera 2s y repite 'look'." + shot
    old = None
    try:
        with open(_cache_path(p.serial), encoding="utf-8") as f:
            old = json.load(f)
    except Exception:
        pass
    try:
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        with open(_cache_path(p.serial), "w", encoding="utf-8") as f:
            json.dump({"pkg": pkg, "lines": lines}, f, ensure_ascii=False)
    except Exception:
        pass
    if diff and old and old.get("pkg") == pkg:
        added = ["+" + l for l in lines if l not in old["lines"]]
        gone = ["-" + l.rsplit(" @", 1)[0] for l in old["lines"] if l not in lines]
        if grep:
            added, gone = _grep_lines(added, grep), _grep_lines(gone, grep)
        body = [("+" + pref(x[1:])) if x.startswith("+") else x for x in added[:max_items]] + gone[:10]
        if eidmap and r.get("snap"):
            _save_ids(r["snap"], [eidmap[l] for l in lines if l in eidmap], merge=True)
        return f"pkg={pkg} (diff)" + NL + (NL.join(body) if body else "=sin cambios")
    # cabecera: se calcula sobre TODAS las lineas (antes de --grep)
    all_lines = lines
    low = " ".join(l.rsplit(" @", 1)[0].lower() for l in all_lines)
    target = _get_target(p.serial)
    kind, msg = classify_screen(pkg, low, r.get("focus_raw"), target)
    head, notes = "", []
    if msg:
        head = msg + NL
    else:
        if len(all_lines) <= 30:   # obstaculos solo en pantallas/dialogos cortos (no en feeds ni articulos)
            for tag, lv, adv in classify_obstacle(low):
                head += f"OBSTACULO[{lv}] {tag}: {adv}" + NL
        nclick = sum(1 for l in all_lines if l.endswith(" *"))
        if re.search(_LOGIN_RE, low) and len(all_lines) <= 25 and nclick <= 12:   # pantallas largas (articulos, feeds) no son un login
            opts = [l.rsplit(" @", 1)[0] for l in all_lines if l.endswith(" *")]
            extra_opts = []
            if explore and r.get("scrollable"):
                done = 0
                for _ in range(2):
                    p.scroll("down"); time.sleep(0 if p.app_mode else 0.8); done += 1
                    for e2 in p.read().get("elements", []):
                        l2 = label_of(e2)
                        if l2 and e2["clickable"] and _smart_trunc(l2) not in opts and _smart_trunc(l2) not in extra_opts:
                            extra_opts.append(_smart_trunc(l2))
                for _ in range(done):
                    p.scroll("up"); time.sleep(0 if p.app_mode else 0.4)
            head = "PANTALLA DE LOGIN. Opciones clicables: " + " | ".join(opts) + NL
            if extra_opts:
                head += "Mas abajo (tras scroll): " + " | ".join(extra_opts) + NL
            want_icons = want_icons or bool(unl)
    if r.get("warn"):
        notes.append(r["warn"])
    if r.get("secure") and not msg:
        notes.append("ventana protegida (FLAG_SECURE): la captura saldra negra")
    if r.get("scrollable") and not msg:
        notes.append("pantalla desplazable: puede haber mas contenido (scroll)")
    if explore and not msg and p.keyboard_visible():
        notes.append("TECLADO visible: puede tapar opciones del pie de pantalla")
    if grep:
        lines = _grep_lines(lines, grep) or ["(sin coincidencias para '" + grep + "')"]
    more = f" (+{len(lines) - max_items} mas)" if len(lines) > max_items else ""
    tail = ""
    if unl:
        sheet, warn = None, ""
        if want_icons or full:
            try:
                sheet, warn = icon_sheet(p, unl, os.path.join(HERE, "logs", "icons.png"))
            except Exception:
                pass
        tail = NL + f"AVISO: {len(unl)} boton(es) de solo icono (#1..#{len(unl)}, orden arriba-abajo/izq-der). " + (
            f"Mira la hoja numerada {sheet} para identificarlos (Google/Apple/correo...)" + (f" [{warn}]" if warn else "")
            if sheet else "Ejecuta 'icons' para generar la hoja numerada, o 'shot'")
    for n_ in notes:
        tail += NL + "NOTA: " + n_
    shown_lines = lines[:max_items]
    if eidmap and r.get("snap"):
        _save_ids(r["snap"], [eidmap[l] for l in shown_lines if l in eidmap])
    return head + f"pkg={pkg}" + NL + NL.join(pref(l) for l in shown_lines) + more + tail


def compact_state(st):
    return (f"{'HOME' if st['is_home'] else 'NO-HOME'} pkg={st['focus_package']} "
            f"screen={'on' if st['screen_on'] else 'off'} locked={'si' if st['locked'] else 'no'}"
            + (f" FOCO={st['focus_kind']}" if st.get("focus_kind") else ""))


def main(argv):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    serial, full = None, False
    while argv[:1] in (["--serial"], ["--json"]):
        if argv[0] == "--serial":
            serial, argv = argv[1], argv[2:]
        else:
            full, argv = True, argv[1:]
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if argv[0] not in COMMANDS and len(argv) > 1:   # forma: phone <dispositivo> <comando> ...
        serial, argv = resolve_device(argv[0]), argv[1:]
    cmd, a = argv[0], argv[1:]
    quiet, grep, diff = False, None, False
    if "-q" in a:
        a = [x for x in a if x != "-q"]; quiet = True
    lookfull = "--full" in a
    a = [x for x in a if x != "--full"]
    if "--diff" in a:
        a = [x for x in a if x != "--diff"]; diff = True
    if "--grep" in a:
        i = a.index("--grep"); grep = a[i + 1] if i + 1 < len(a) else None; a = a[:i] + a[i + 2:]
    if cmd == "check":
        print(check())
        return 0
    if cmd == "connect":
        print(app_connect(a[0], a[1]) if len(a) >= 2 else "ERR uso: phone connect http://IP:8765 TOKEN")
        return 0
    if cmd == "disconnect":
        print(app_disconnect())
        return 0
    if cmd == "pair":
        print(app_pair_cmd(a))
        return 0
    if cmd == "help":
        if "--agent" in a:
            print(phone_help.help_agent(full or "--json" in a))
        else:
            print(__doc__)
        return 0
    if cmd == "know":
        import phone_knowledge
        print(phone_knowledge.know_cmd(" ".join(a) if a else None))
        return 0
    if cmd == "devices":
        d = _safe_devices()
        row = app_device_row()
        if full:
            print(json.dumps(d + ([row] if row else []), ensure_ascii=False))
        else:
            lines = [f"{i} {x['serial']} {x['state']} {model_of(x)}" for i, x in enumerate(d, 1)]
            if row:
                lines.append(f"{len(d) + 1} app {row['state']} {row['model']} (app) {row['url']}")
            print(NL.join(lines))
        return 0
    if cmd == "remember":
        import phone_macros
        serial_ = make_phone(serial).serial
        kv = {x.split("=", 1)[0]: x.split("=", 1)[1] for x in a if "=" in x and not x.startswith("--")}
        last = int(a[a.index("--last") + 1]) if "--last" in a else 3
        desc = a[a.index("--desc") + 1] if "--desc" in a else ""
        res_ = a[a.index("--result") + 1] if "--result" in a else None
        print(phone_macros.remember(serial_, a[0], last, desc, kv, res_, make_phone(serial_).fingerprint()))
        return 0
    if cmd == "graph":
        import phone_graph
        print(phone_graph.summary())
        return 0
    if cmd == "goto":
        import phone_graph
        tap_ = "--tap" in a
        a = [x for x in a if x != "--tap"]
        print(phone_graph.goto(make_phone(serial), " ".join(a), tap=tap_, grep=grep))
        return 0
    if cmd == "forget":
        import phone_macros
        print(phone_macros.forget(a[0]))
        return 0
    if cmd == "macros":
        import phone_macros
        print(phone_macros.list_macros())
        return 0
    if cmd == "macro":
        import phone_macros
        print(phone_macros.set_state(a[1], a[0]) if len(a) >= 2 else "ERR uso: phone macro promote|demote <nombre>")
        return 0
    if cmd == "advanced":
        import phone_advanced
        print(phone_advanced.advanced_cmd(sys.modules[__name__], serial, a))
        return 0
    p = make_phone(serial)
    if isinstance(p, AppPhone) and cmd in ("logs", "live", "accounts"):
        print("ERR " + NO_APP)
        return 0
    simple = {"home": p.home, "back": p.back, "recents": p.recents, "wake": p.wake, "sleep": p.sleep}
    if cmd in simple:
        simple[cmd](); out = "ok"
    elif cmd == "caps":
        c = p.caps()
        out = c if full or "--json" in a else phone_help.format_caps(c)
    elif cmd == "state": out = p.state() if full else compact_state(p.state())
    elif cmd == "ensure_home": out = p.ensure_home() if full else compact_state(p.ensure_home())
    elif cmd == "read": out = p.read() if full else compact_read(p, grep=grep, diff=diff)
    elif cmd == "look":
        p.prefetch_state()
        body = compact_read(p, max_items=200 if lookfull else 40, grep=grep, diff=diff, full=lookfull, explore=True)
        out = compact_state(p.take_state()) + NL + body
    elif cmd == "info": out = p.info()
    elif cmd == "top": out = p.top()
    elif cmd == "notifs": out = p.notifications()
    elif cmd == "apps": out = p.apps(a[0] if a else None)
    elif cmd == "logs": out = p.logs(a[0] if a else None)
    elif cmd == "open": out = p.open_url(a[0])
    elif cmd == "wait": out = p.wait_for(" ".join(a[:-1]) if len(a) > 1 and a[-1].isdigit() else " ".join(a), a[-1] if len(a) > 1 and a[-1].isdigit() else 15)
    elif cmd == "icons": out = compact_read(p, want_icons=True, full=False, max_items=60, grep="icono")
    elif cmd == "shot": out = p.small_shot(*a[:1])
    elif cmd == "screenshot": out = p.screenshot(*a[:1])
    elif cmd == "tap" and a[:1] and a[0].startswith("@e"):
        if not isinstance(p, AppPhone):
            out = "ERR los ids @eN solo existen en modo app (phone app look / phone app tap @e12)"
        else:
            r_ = p.tap_node(a[0])
            out = f"ok {a[0]}" + (f" via={r_['channel']}" if r_.get("channel") else "")
    elif cmd == "tap": p.tap(a[0], a[1]); out = "ok"
    elif cmd == "tap_text": out = p.tap_text(" ".join(a))
    elif cmd == "swipe": p.swipe(*a); out = "ok"
    elif cmd == "scroll": p.scroll(a[0]); out = "ok"
    elif cmd == "text": p.text(" ".join(a)); out = "ok"
    elif cmd == "key": p.key(a[0]); out = "ok"
    elif cmd == "launch": out = p.launch(" ".join(a))
    elif cmd == "clip":
        import phone_advanced
        out = phone_advanced.clip_cmd(p, a)
    elif cmd == "vd":
        import phone_advanced
        out = phone_advanced.vd_cmd(p, a, grep, sys.modules[__name__])
    elif cmd == "live": out = live(p.serial)
    elif cmd == "run":
        import phone_macros
        kv = {x.split("=", 1)[0]: x.split("=", 1)[1] for x in a[1:] if "=" in x}
        out = phone_macros.run(p, a[0], kv, grep=grep)
    elif cmd == "scroll_to":
        e = p.scroll_to(" ".join(a))
        out = f"ok {e['text'] or e['desc']} @{e['center'][0]},{e['center'][1]}" if e else "ERR no encontrado"
    elif cmd == "accounts":
        r = p.accounts()
        out = r if full else (f"google={'si' if r['google_registered'] else 'no'} emails=" + (",".join(r["emails"]) or "ninguno visible"))
    else: out = f"ERR comando desconocido: {cmd}"
    if isinstance(out, dict) and not full:
        out = "ERR " + out["error"] if "error" in out else (
            f"ok {out['tapped']} @{out['at'][0]},{out['at'][1]}" + (f" via={out['via']}" if out.get("via") else "") if "tapped" in out
            else json.dumps(out, ensure_ascii=False))
    out = out if isinstance(out, str) else json.dumps(out, ensure_ascii=False)
    app_last = getattr(p, "last", {}) if isinstance(p, AppPhone) else {}
    nochange = app_last.get("changed") is False and not out.startswith("ERR") and not full
    if cmd in LOOK_AFTER and not quiet and not full and not out.startswith("ERR"):
        if isinstance(p, AppPhone):
            # modo app: el telefono ya espero el cambio de pantalla; sin cambio no hace falta volver a leer
            if nochange:
                out += NL + "--" + NL + "=sin cambios"
            else:
                out += NL + "--" + NL + compact_state(p.state()) + NL + compact_read(p, grep=grep, diff=diff)
        else:
            time.sleep(1.0)   # deja que la UI se asiente y devuelve la pantalla resultante (ahorra una llamada 'look')
            out += NL + "--" + NL + compact_state(p.state()) + NL + compact_read(p, grep=grep, diff=diff)
    elif nochange and cmd in ("tap", "tap_text", "swipe", "scroll", "key", "text", "launch", "open", "back", "home", "recents", "scroll_to"):
        out += NL + "=sin cambios"
    if cmd in TRACE_CMDS and not out.startswith("ERR"):
        trace(p.serial, cmd, a, out)
        try:
            import phone_graph
            pk, lb = phone_graph.screen(p) if NL + "--" + NL not in out else (None, None)
            if NL + "--" + NL in out:
                part = out.split(NL + "--" + NL, 1)[1].splitlines()
                pk = next((l[4:] for l in part if l.startswith("pkg=")), None)
                lb = [re.sub(r"^@e\d+ ", "", re.sub(r" @\d+,\d+( [*~])?$", "", l)) for l in part if not l.startswith(("HOME", "NO-HOME", "pkg=", "="))]
            phone_graph.record(p.serial, cmd, " ".join(a), pk, lb)
        except Exception:
            pass
    if app_last.get("riesgo") and not out.startswith("ERR") and not full:
        out += NL + "AVISO accion de riesgo alto: " + (app_last.get("label") or "(sin etiqueta)")
    if not full and (cmd in ("look", "read") or (cmd in LOOK_AFTER and not quiet)):
        try:
            import phone_knowledge
            ap = phone_knowledge.app_line(out, force=lookfull)
            if ap:
                out += NL + ap
        except Exception:
            pass
    print(out)
    return 0


def log_run(argv, out, ms):
    """Una linea por comando en logs/runs.jsonl. Etiquetas por entorno: PHONE_TASK, PHONE_MODE, PHONE_RUN."""
    try:
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        rec = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "run": os.environ.get("PHONE_RUN", ""),
               "task": os.environ.get("PHONE_TASK", ""), "mode": os.environ.get("PHONE_MODE", ""),
               "cmd": " ".join(argv[:2] + ["***"] if argv[:1] == ["connect"] else (argv[:1] + ["***"] if argv[:1] == ["pair"] else argv)), "out_chars": len(out), "est_tokens": len(out) // 4, "ms": ms,
               "ok": not out.startswith("ERR")}
        with open(os.path.join(HERE, "logs", "runs.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + NL)
    except Exception:
        pass


def stats():
    path = os.path.join(HERE, "logs", "runs.jsonl")
    if not os.path.isfile(path):
        return "sin datos (logs/runs.jsonl)"
    agg = {}
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        if not r["task"]:
            continue
        k = (r["task"], r["mode"], r["run"])
        a = agg.setdefault(k, {"calls": 0, "chars": 0, "tok": 0, "ms": 0, "ok": True})
        a["calls"] += 1; a["chars"] += r["out_chars"]; a["tok"] += r["est_tokens"]; a["ms"] += r["ms"]; a["ok"] &= r["ok"]
    rows = ["task mode run | llamadas chars ~tokens ms ok"]
    for (t, m, ru), a in sorted(agg.items()):
        rows.append(f"{t} {m} {ru or '-'} | {a['calls']} {a['chars']} {a['tok']} {a['ms']} {'si' if a['ok'] else 'NO'}")
    return NL.join(rows)


def run_cli(args, catch=False):
    """Ejecuta un comando como la CLI y devuelve (salida, codigo) SIN escribir en stdout (lo usa el servidor MCP).
    Captura la salida de main(), convierte sys.exit/AppError en texto 'ERR ...' y anota logs/runs.jsonl."""
    buf, t0 = io.StringIO(), time.time()
    with contextlib.redirect_stdout(buf):
        try:
            code = main(list(args))
        except SystemExit as e:
            code = e.code
            if isinstance(code, str):   # sys.exit("ERR ...") (p.ej. adb no instalado): mostrar el mensaje
                print(code)
        except AppError as e:
            print("ERR " + str(e))
            code = 0
        except Exception as e:   # catch=True (MCP): fallo inesperado como texto; si no, traza normal
            if not catch:
                raise
            print(f"ERR {type(e).__name__}: {e}")
            code = 1
    out = buf.getvalue()
    log_run(list(args), out.strip(), int((time.time() - t0) * 1000))
    return out, code


if __name__ == "__main__":
    args = sys.argv[1:]
    if args[:1] == ["stats"]:
        print(stats()); sys.exit(0)
    out, code = run_cli(args)
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stdout.write(out)
    sys.exit(code if isinstance(code, int) else (1 if code else 0))

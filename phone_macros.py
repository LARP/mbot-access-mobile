"""Memoria de rutas (macros) verificadas. Se usa desde phone_agent.py:
  phone N macros                 lista macros (una linea)
  phone N run <macro> [k=v ...]  reproduce el macro; cada paso se verifica con 'expect'
Si un paso falla devuelve 'FAIL paso=i/n ...' + la pantalla actual, para seguir explorando desde ahi.
Estados (campo "estado"): borrador -> prueba -> verificada. 'remember' crea borrador; 'run' avisa en borrador/prueba.
  borrador -> prueba tras 1 exito; prueba -> verificada tras 3 exitos seguidos (con huella coincidente);
  fallo en prueba/verificada: fallos++; verificada con 2 fallos seguidos baja a prueba. 'phone N macro promote|demote <n>' a mano.
Contadores: ok, fallos, ultimo (fecha) + ok_racha / fallo_racha (consecutivos).
Formato en memory/macros.json:
  {"nombre": {"desc": "...", "result_grep": "@", "steps": [
      {"use": "otro_macro"},
      {"do": "tap_text", "args": "Pantalla|Display", "expect": {"pkg": "settings", "text": "Brillo|Brightness"}}]}}
'do': stop, launch, am, tap_text, scroll_to, tap, key, back, home, text, ensure_home, wait.
Textos con alternativas separadas por '|'. Parametros: {{nombre}} en args.
"""
import json, os, re, time

import phone_agent as pa

PATH = os.path.join(pa.HERE, "memory", "macros.json")
NL = chr(10)


ESTADOS = ("borrador", "prueba", "verificada")


def _migrate(db):
    """Migracion automatica y sin perdida al formato con estados. Devuelve True si cambio algo.
    fail->fallos, last->ultimo, fail_streak->fallo_racha, obsolete->estado 'prueba' (+nota). Sin 'estado': curados a mano
    -> verificada; los de 'remember' -> borrador."""
    changed = False
    for name, m in db.items():
        if "estado" in m:
            continue
        changed = True
        m["fallos"] = m.pop("fail", m.get("fallos", 0))
        if "last" in m:
            m["ultimo"] = m.pop("last")
        m["fallo_racha"] = m.pop("fail_streak", 0)
        m.setdefault("ok", 0)
        m.setdefault("ok_racha", 0)
        if m.pop("obsolete", False):
            m["estado"] = "prueba"
            m.setdefault("nota", "marcado obsoleto antes de la Fase 2 (fallaba); pendiente de revisar")
        elif m.get("source") == "remember":
            m["estado"] = "borrador"
        else:
            m["estado"] = "verificada"
    return changed


def load():
    if not os.path.isfile(PATH):
        return {}
    with open(PATH, encoding="utf-8") as f:
        db = json.load(f)
    if _migrate(db):   # copia de seguridad una sola vez (no pisa una .bak anterior)
        try:
            import shutil
            if not os.path.isfile(PATH + ".bak"):
                shutil.copyfile(PATH, PATH + ".bak")
        except OSError:
            pass
        save(db)
    return db


def save(db):
    os.makedirs(os.path.dirname(PATH), exist_ok=True)
    with open(PATH, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)


def list_macros():
    db = load()
    if not db:
        return "sin macros (memory/macros.json)"
    return NL.join(f"{k} [{v.get('estado', '?')}]: {v.get('desc', '')} (ok={v.get('ok', 0)} fallos={v.get('fallos', 0)} ultimo={v.get('ultimo', '-')})"
                   + (f" NOTA {v['nota']}" if v.get("nota") else "") for k, v in db.items())


def set_state(name, action):
    """phone N macro promote|demote <nombre>: cambio manual de estado."""
    db = load()
    if name not in db:
        return f"ERR macro desconocido: {name}"
    if action not in ("promote", "demote"):
        return "ERR uso: phone N macro promote|demote <nombre>"
    m = db[name]
    i = ESTADOS.index(m.get("estado", "borrador"))
    j = min(i + 1, 2) if action == "promote" else max(i - 1, 0)
    if j == i:
        return f"OK {name} ya esta en '{ESTADOS[i]}'"
    m["estado"], m["ok_racha"], m["fallo_racha"] = ESTADOS[j], 0, 0
    save(db)
    return f"OK {name}: {ESTADOS[i]} -> {ESTADOS[j]} (manual)"


def flatten(db, name, params, depth=0):
    if name not in db:
        raise KeyError(f"macro desconocido: {name}")
    if depth > 6:
        raise RecursionError("macros anidados demasiado profundo")
    steps = []
    for st in db[name]["steps"]:
        if "use" in st:
            steps += flatten(db, st["use"], params, depth + 1)
        else:
            st = dict(st)
            st["args"] = re.sub(r"\{\{(\w+)\}\}", lambda m: str(params.get(m.group(1), m.group(0))), st.get("args", ""))
            steps.append(st)
    return steps


def _alts(s):
    return [x.strip().lower() for x in s.split("|") if x.strip()]


def _first_alt(p, fn, args):
    """Ejecuta fn(p, alt) con cada alternativa hasta que alguna funcione."""
    last = None
    for alt in [x.strip() for x in args.split("|")]:
        last = fn(p, alt)
        if last:
            return last
    return last


def _tap_text(p, args):
    r = None
    for alt in [x.strip() for x in args.split("|")]:
        r = p.tap_text(alt)
        if "error" not in r:
            return r
    return r


def _scroll_to(p, args):
    for alt in [x.strip() for x in args.split("|")]:
        e = p.scroll_to(alt, 0)
        if e:
            return {"ok": True}
    for alt in [x.strip() for x in args.split("|")]:
        e = p.scroll_to(alt)
        if e:
            return {"ok": True}
    return {"error": f"no se encontro '{args}'"}


def do_step(p, st):
    d, a = st["do"], st.get("args", "")
    if d == "stop":   # modo app: sin force-stop; se omite (launch + expect siguen validando el punto de partida)
        if not getattr(p, "app_mode", False):
            p.sh(f"am force-stop {a}")
        return {}
    if d == "launch": p.launch(a); return {}
    if d == "am": p.sh(f"am start {a}"); return {}
    if d == "tap_text": return _tap_text(p, a)
    if d == "scroll_to": return _scroll_to(p, a)
    if d == "tap": x, y = a.split(); p.tap(x, y); return {}
    if d == "scroll": p.scroll(a or "down"); return {}
    if d == "key": p.key(a); return {}
    if d == "back": p.back(); return {}
    if d == "home": p.home(); return {}
    if d == "text": p.text(a); return {}
    if d == "ensure_home": p.ensure_home(); return {}
    if d == "wait": time.sleep(float(a or 1)); return {}
    return {"error": f"accion desconocida: {d}"}


def check_expect(p, exp, timeout=4.0):
    """Reintenta hasta 'timeout' s. Devuelve True si pkg y/o texto esperados estan en pantalla."""
    t0 = time.time()
    while True:
        r = p.read()
        els = r.get("elements", [])
        blob = " | ".join((e["text"] + " " + e["desc"]).lower() for e in els)
        ok = True
        if "pkg" in exp:
            ok &= exp["pkg"].lower() in (r.get("package") or "").lower()
        if "text" in exp:
            ok &= any(alt in blob for alt in _alts(exp["text"]))
        if ok:
            return True
        if time.time() - t0 > timeout:
            return False
        time.sleep(0.7)


def run(p, name, params=None, grep=None, full=False):
    params = params or {}
    db = load()
    try:
        steps = flatten(db, name, params)
    except (KeyError, RecursionError) as e:
        return "ERR " + str(e)
    n = len(steps)
    for i, st in enumerate(steps, 1):
        res = do_step(p, st)
        if res.get("error"):
            return _fail(p, db, name, i, n, st, res["error"])
        time.sleep(0.8 if st["do"] != "wait" else 0)
        if st["do"] in ("tap_text", "launch", "back", "key", "tap"):
            try:
                import phone_graph
                pk, lb = phone_graph.screen(p)
                phone_graph.record(p.serial, st["do"], st.get("args", ""), pk, lb)
            except Exception:
                pass
        if "expect" in st and not check_expect(p, st["expect"]):
            return _fail(p, db, name, i, n, st, "esperado " + json.dumps(st["expect"], ensure_ascii=False))
    warn = _fp_warning(p, db[name])
    est0 = db[name].get("estado", "borrador")
    note = _record_ok(db[name], match=not warn)
    save(db)
    g = grep or db[name].get("result_grep")
    head = (f"OK {name} {n}/{n}" + (NL + f"AVISO macro en {est0}: aun no verificada, comprobar el resultado" if est0 != "verificada" else "")
            + (NL + warn if warn else "") + (NL + note if note else ""))
    if full or db[name].get("show_result", True):
        return head + NL + pa.compact_state(p.state()) + NL + pa.compact_read(p, grep=g)
    return head


def _fp_warning(p, macro):
    fp = macro.get("fp")
    if not fp:
        return ""
    cur = p.fingerprint()
    diff = {k: (fp[k], cur[k]) for k in ("brand", "lang") if fp.get(k) and fp[k] != cur[k]}
    return ("AVISO huella distinta (" + ", ".join(f"{k}: macro={a} dispositivo={b}" for k, (a, b) in diff.items()) + ")") if diff else ""


def _record_ok(m, match=True):
    """Exito del macro: contadores y transicion de estado. Devuelve una nota si cambio de estado."""
    m["ok"] = m.get("ok", 0) + 1
    m["fallo_racha"] = 0
    m["ultimo"] = time.strftime("%Y-%m-%d")
    est = m.get("estado", "borrador")
    if est == "borrador":
        m["estado"], m["ok_racha"] = "prueba", 0
        return "NOTA macro sube a 'prueba' (1 exito)"
    if est == "prueba" and match:
        m["ok_racha"] = m.get("ok_racha", 0) + 1
        if m["ok_racha"] >= 3:
            m["estado"], m["ok_racha"] = "verificada", 0
            return "NOTA macro sube a 'verificada' (3 exitos seguidos)"
    return ""


def _record_fail(m):
    """Fallo del macro. Devuelve una nota si cambio de estado."""
    m["fallos"] = m.get("fallos", 0) + 1
    m["fallo_racha"] = m.get("fallo_racha", 0) + 1
    m["ok_racha"] = 0
    m["ultimo_fallo"] = time.strftime("%Y-%m-%d")
    if m.get("estado") == "verificada" and m["fallo_racha"] >= 2:
        m["estado"], m["fallo_racha"] = "prueba", 0
        return "NOTA macro baja a 'prueba' (2 fallos seguidos): revisar y corregir"
    return ""


def _fail(p, db, name, i, n, st, why):
    m = db[name]
    est0 = m.get("estado", "borrador")
    note = _record_fail(m)
    save(db)
    return (f"FAIL {name} paso={i}/{n} {st['do']} '{st.get('args', '')}' {why}" + NL
            + (f"AVISO macro en {est0}" + NL if est0 != "verificada" else "") + (note + NL if note else "")
            + pa.compact_state(p.state()) + NL + pa.compact_read(p))


# ---------------------------------------------------------------- remember
GENERIC = ("desplazarse", "busqueda", "búsqueda", "buscar", "mas opciones", "más opciones", "navegar", "back", "up", "search")


def _noisy(label):
    """Etiquetas poco fiables para 'expect': cortas, con muchos digitos (horas/fechas) o botones genericos de barra."""
    l = label.lower()
    return len(label) < 4 or sum(c.isdigit() for c in label) >= 2 or any(g in l for g in GENERIC)


def remember(serial, name, last, desc, params, result_grep=None, fp=None):
    """Convierte las ultimas N acciones exitosas (logs/trace.jsonl) en un macro con 'expect' derivado de la pantalla resultante."""
    path = os.path.join(pa.HERE, "logs", "trace.jsonl")
    if not os.path.isfile(path):
        return "ERR sin traza: ejecuta acciones primero (tap_text, scroll_to, launch...)"
    ents = [json.loads(l) for l in open(path, encoding="utf-8-sig") if l.strip()]
    ents = [e for e in ents if e["serial"] == serial][-last:]
    if not ents:
        return "ERR no hay acciones registradas para este dispositivo"
    steps, prev, warn = [], set(), []
    for e in ents:
        d, args = e["do"], e["args"]
        for k, v in params.items():
            args = args.replace(v, "{{" + k + "}}")
        if d == "tap":
            warn.append(f"'tap {args}' usa coordenadas (fragil entre dispositivos)")
        if d in ("scroll", "swipe"):
            args = args or "down"
        if d == "launch":
            steps.append({"do": "stop", "args": args})
        st = {"do": d, "args": args}
        exp = {}
        if e.get("pkg"):
            exp["pkg"] = e["pkg"]
        labels = [l for l in e.get("labels", []) if l]
        new = [l for l in labels if l not in prev and not _noisy(l) and l.lower() != args.lower()]
        mail = [l for l in labels if "@" in l]
        if mail:
            exp["text"] = "@"                               # no guardar emails reales
        elif new:
            exp["text"] = new[0]
        if exp and d not in ("scroll", "swipe"):
            st["expect"] = exp
        steps.append(st)
        prev = set(labels)
    db = load()
    db[name] = {"desc": desc or f"grabado de {len(ents)} acciones", "source": "remember", "estado": "borrador", "ok": 0, "fallos": 0,
                "ok_racha": 0, "fallo_racha": 0, "ultimo": time.strftime("%Y-%m-%d"), "steps": steps}
    if result_grep:
        db[name]["result_grep"] = result_grep
    if fp:
        db[name]["fp"] = fp
    save(db)
    lines = [f"OK macro '{name}' guardado como borrador ({len(steps)} pasos): pasa a prueba tras 1 exito y a verificada tras 3 mas"]
    for i, st in enumerate(steps, 1):
        lines.append(f"{i}. {st['do']} {st.get('args', '')}" + (f"  -> espera {json.dumps(st['expect'], ensure_ascii=False)}" if "expect" in st else ""))
    lines += ["AVISO " + w for w in warn]
    return NL.join(lines)


def forget(name):
    db = load()
    if name not in db:
        return f"ERR macro desconocido: {name}"
    del db[name]
    save(db)
    return f"OK macro '{name}' eliminado"

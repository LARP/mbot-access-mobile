"""PROTOTIPO: grafo de navegacion. Nodos = pantallas (pkg + etiquetas estables, comparadas por similitud Jaccard);
aristas = acciones (tap_text, launch, back...). Se aprende solo de lo que se ejecuta (acciones sueltas y macros).
  phone N goto "texto" [--tap]   navega por el camino mas corto conocido hasta una pantalla donde 'texto' sea visible
  phone N graph                  resumen del grafo
Persistencia: memory/graph.json
"""
import json, os, time

import phone_agent as pa
import phone_macros as pm

PATH = os.path.join(pa.HERE, "memory", "graph.json")
NL = chr(10)
SIM = 0.6          # similitud minima para considerar que dos pantallas son el mismo nodo
IGNORE_PKGS = ("com.android.systemui", "com.android.keyguard")
RECENT = 120       # s: una accion encadena arista solo si la anterior fue hace menos de esto


def load():
    try:
        with open(PATH, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"nodes": {}, "edges": [], "last": {}}


def save(db):
    os.makedirs(os.path.dirname(PATH), exist_ok=True)
    with open(PATH, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False)


def stable(labels):
    return sorted({l.strip() for l in labels if l and not pm._noisy(l.strip())})[:60]


def sim(a, b):
    a, b = set(a), set(b)
    return len(a & b) / len(a | b) if a | b else 0.0


def find_node(db, pkg, labels):
    best, bs = None, 0.0
    for nid, n in db["nodes"].items():
        if n["pkg"] != pkg:
            continue
        s = sim(n["labels"], labels)
        if s > bs:
            best, bs = nid, s
    return best if bs >= SIM else None


def observe(db, pkg, labels):
    labels = stable(labels)
    nid = find_node(db, pkg, labels)
    if nid is None:
        nid = f"n{len(db['nodes']) + 1}"
        db["nodes"][nid] = {"pkg": pkg, "labels": labels}
    else:   # fusiona etiquetas vistas (la pantalla puede mostrar mas contenido al hacer scroll)
        n = db["nodes"][nid]
        n["labels"] = sorted(set(n["labels"]) | set(labels))[:80]
    return nid


def record(serial, do, args, pkg, labels):
    """Registra el resultado de una accion: nodo destino y, si hay origen reciente, la arista."""
    if not pkg or not labels or pkg in IGNORE_PKGS:      # no aprender la pantalla de bloqueo ni la barra del sistema
        return
    db = load()
    dst = observe(db, pkg, labels)
    last = db["last"].get(serial)
    if do == "launch":
        src = "*"
    elif last and time.time() - last["ts"] < RECENT:
        src = last["node"]
    else:
        src = None
    if src and src != dst and do in ("tap_text", "launch", "back", "key", "tap"):
        for e in db["edges"]:
            if e["from"] == src and e["to"] == dst and e["do"] == do and e["args"] == args:
                e["n"] += 1
                break
        else:
            db["edges"].append({"from": src, "to": dst, "do": do, "args": args, "n": 1})
    db["last"][serial] = {"node": dst, "ts": time.time()}
    save(db)


def screen(p):
    r = p.read()
    if "error" in r:
        return None, []
    return r["package"], [(e["text"] or e["desc"]).strip() for e in r["elements"] if (e["text"] or e["desc"]).strip()]


def _rank(db, nid, needle):
    """0 = etiqueta propia (igual o empieza con el texto), 1 = solo aparece dentro de otra etiqueta (subtitulo), None = no esta."""
    n, best = needle.lower(), None
    for l in db["nodes"][nid]["labels"]:
        ll = l.lower()
        if ll == n or ll.startswith(n) and "," not in ll:
            return 0
        if n in ll:
            best = 1
    return best


def _path(db, cur, goals):
    """BFS. 'cur' puede ser None (desconocida): solo aristas desde '*' (launch). Devuelve lista de aristas."""
    from collections import deque
    adj = {}
    for e in db["edges"]:
        adj.setdefault(e["from"], []).append(e)
    starts = [("*", None)] if cur is None else [(cur, None), ("*", None)]
    seen = {s[0] for s in starts}
    dq = deque((s, []) for s, _ in starts)
    while dq:
        node, path = dq.popleft()
        for e in adj.get(node, []):
            if e["to"] in seen:
                continue
            np_ = path + [e]
            if e["to"] in goals:
                return np_
            seen.add(e["to"])
            dq.append((e["to"], np_))
    return None


def goto(p, needle, tap=False, grep=None):
    st = p.state()
    if st["locked"] or not st["screen_on"]:
        return "ERR telefono bloqueado o con pantalla apagada: desbloquear primero (el PIN lo escribe el usuario)"
    db = load()
    pkg, labels = screen(p)
    cur = find_node(db, pkg, stable(labels)) if pkg else None
    nl = needle.lower()
    if any(l.lower() == nl or (l.lower().startswith(nl) and "," not in l) for l in labels):   # ya visible: sin navegar
        steps = []
    else:
        ranks = {nid: _rank(db, nid, needle) for nid in db["nodes"]}
        ranks = {k: v for k, v in ranks.items() if v is not None}
        goals = {k for k, v in ranks.items() if v == 0} or set(ranks)      # prefiere pantallas con etiqueta propia
        if not goals:
            return f"ERR sin ruta conocida a '{needle}' (no esta en ninguna pantalla aprendida); explora y se aprendera"
        steps = _path(db, cur, goals)
        if steps is None:
            return f"ERR sin camino desde la pantalla actual a '{needle}'; usa ensure_home y reintenta"
    n = len(steps)
    for i, e in enumerate(steps, 1):
        if e["do"] == "launch":
            pm.do_step(p, {"do": "stop", "args": e["args"]})
        if e["do"] == "tap_text":
            pm._scroll_to(p, e["args"])
        res = pm.do_step(p, {"do": e["do"], "args": e["args"]})
        if res.get("error"):
            return f"FAIL goto paso={i}/{n} {e['do']} '{e['args']}' {res['error']}" + NL + pa.compact_read(p)
        for attempt in range(5):                       # la UI puede tardar en asentarse: reintenta ~4 s antes de fallar
            time.sleep(0.9)
            pkg, labels = screen(p)
            dn = find_node(db, pkg, stable(labels)) if pkg else None
            if dn == e["to"] or sim(db["nodes"][e["to"]]["labels"], stable(labels)) >= 0.4:
                break
        else:
            return f"FAIL goto paso={i}/{n} pantalla inesperada tras {e['do']} '{e['args']}'" + NL + pa.compact_read(p)
        record(p.serial, e["do"], e["args"], pkg, labels)
    msg = f"OK goto '{needle}' {n} pasos"
    if tap:
        r = p.tap_text(needle)
        if "error" in r:
            return "FAIL " + r["error"]
        time.sleep(0.9)
        msg += f" + tap '{r['tapped']}'"
        pkg, labels = screen(p)
        record(p.serial, "tap_text", needle, pkg, labels)
    return msg + NL + pa.compact_state(p.state()) + NL + pa.compact_read(p, grep=grep or needle)


def summary():
    db = load()
    return f"nodos={len(db['nodes'])} aristas={len(db['edges'])}" + NL + NL.join(
        f"{e['from']} -[{e['do']} {e['args']}]-> {e['to']} (x{e['n']})" for e in db["edges"])

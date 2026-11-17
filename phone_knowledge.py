"""Conocimiento por app (Fase 2): fichas knowledge/apps/<nombre>/APP.md con frontmatter.
  phone know               lista las fichas (nombre, paquetes, alias)
  phone know <app>         imprime la ficha (por nombre, alias, paquete o etiqueta de lanzador)
`look`/`read`/acciones anaden UNA linea 'APP: <display> -> knowledge/apps/<n>/APP.md' la primera vez que ven un
paquete con ficha en la "sesion" (logs/know_seen.json; se reinicia tras 30 min sin uso). `--full` la muestra siempre.
Solo libreria estandar: el frontmatter se lee con un parser minimo (clave: valor, listas en linea o con guiones).
"""
import json, os, re, time

HERE = os.path.dirname(os.path.abspath(__file__))
KDIR = os.path.join(HERE, "knowledge", "apps")
NL = chr(10)
IDLE_S = 30 * 60
_INDEX = None


def parse_frontmatter(text):
    """Devuelve (meta, cuerpo). meta: dict de str o list[str]. Sin frontmatter -> ({}, texto)."""
    text = text.lstrip("﻿")
    m = re.match(r"---\s*\r?\n(.*?)\r?\n---\s*\r?\n?(.*)\Z", text, re.S)
    if not m:
        return {}, text
    meta, key = {}, None
    for raw in m.group(1).splitlines():
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        li = re.match(r"\s+-\s+(.*)", line)
        if li and key and isinstance(meta.get(key), list):
            meta[key].append(_scalar(li.group(1)))
            continue
        kv = re.match(r"([A-Za-z_][\w-]*)\s*:\s*(.*)", line)
        if not kv:
            continue
        key, val = kv.group(1), kv.group(2).strip()
        if val == "":
            meta[key] = []
        elif val.startswith("[") and val.endswith("]"):
            meta[key] = [_scalar(x) for x in _split_list(val[1:-1]) if x.strip()]
        else:
            meta[key] = _scalar(val)
    return meta, m.group(2)


def _split_list(s):
    out, cur, q = [], "", None
    for c in s:
        if q:
            cur += c
            if c == q:
                q = None
        elif c in "\"'":
            q = c
            cur += c
        elif c == ",":
            out.append(cur)
            cur = ""
        else:
            cur += c
    out.append(cur)
    return out


def _scalar(v):
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        v = v[1:-1]
    return v


def load_index(force=False):
    """Indice {nombre: ficha} (se carga una vez por proceso). ficha: name, display, packages, aliases, open, description, path."""
    global _INDEX
    if _INDEX is not None and not force:
        return _INDEX
    idx = {}
    if os.path.isdir(KDIR):
        for d in sorted(os.listdir(KDIR)):
            f = os.path.join(KDIR, d, "APP.md")
            if not os.path.isfile(f):
                continue
            try:
                meta, _ = parse_frontmatter(open(f, encoding="utf-8").read())
            except Exception:
                continue
            name = meta.get("name") or d
            lst = lambda k: [x for x in (meta.get(k) if isinstance(meta.get(k), list) else [meta.get(k)] if meta.get(k) else []) if x]
            idx[name] = {"name": name, "display": meta.get("display") or name, "packages": lst("packages"),
                         "aliases": lst("aliases"), "open": meta.get("open") or "", "description": meta.get("description") or "",
                         "path": f, "rel": f"knowledge/apps/{d}/APP.md"}
    _INDEX = idx
    return idx


def find(key, strict=False):
    """Ficha por nombre, alias, paquete o display/open (sin distinguir mayusculas). None si no hay."""
    k = (key or "").strip().lower()
    if not k:
        return None
    idx = load_index()
    for pass_ in range(2 if strict else 3):
        for a in idx.values():
            if pass_ == 0 and (k == a["name"].lower() or k in (p.lower() for p in a["packages"])):
                return a
            if pass_ == 1 and (k in (x.lower() for x in a["aliases"]) or k == a["display"].lower() or k == a["open"].lower()):
                return a
            if pass_ == 2 and len(k) >= 4 and k in a["name"].lower():
                return a
    return None


def for_package(pkg):
    pkg = (pkg or "").lower()
    for a in load_index().values():
        if pkg in (p.lower() for p in a["packages"]):
            return a
    return None


# ---- sesion: avisar de una ficha solo la primera vez que se ve el paquete
def _seen_path():
    return os.path.join(HERE, "logs", "know_seen.json")


def first_time(pkg, now=None):
    """True si es la primera vez que se ve 'pkg' en la sesion actual (y lo registra). Pasados 30 min sin uso la sesion se reinicia."""
    now = time.time() if now is None else now
    try:
        d = json.load(open(_seen_path(), encoding="utf-8"))
    except Exception:
        d = {}
    if now - d.get("ts", 0) > IDLE_S:
        d = {"seen": []}
    seen = d.setdefault("seen", [])
    new = pkg not in seen
    if new:
        seen.append(pkg)
    d["ts"] = now
    try:
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        with open(_seen_path(), "w", encoding="utf-8") as f:
            json.dump(d, f)
    except Exception:
        pass
    return new


def app_line(out, force=False):
    """Linea 'APP: ...' para la salida 'out' (con linea 'pkg=<paquete>') o '' si no aplica / ya se mostro en la sesion."""
    m = re.search(r"^pkg=([\w.]+)", out, re.M)
    if not m:
        return ""
    a = for_package(m.group(1))
    if not a:
        return ""
    if not (first_time(m.group(1)) or force):
        return ""
    return f"APP: {a['display']} -> {a['rel']}"


# ---- phone know
def know_cmd(arg=None):
    idx = load_index()
    if not arg:
        if not idx:
            return "sin fichas (knowledge/apps/<nombre>/APP.md)"
        rows = [f"{a['name']}: {a['display']} | {', '.join(a['packages'])} | alias: {', '.join(a['aliases'])}" for a in idx.values()]
        return NL.join(rows) + NL + "Ver una: phone know <nombre|alias|paquete>"
    a = find(arg)
    if not a:
        return f"ERR sin ficha para '{arg}'. Fichas: " + ", ".join(idx) + " (formato y como anadir: knowledge/README.md)"
    return f"# {a['rel']}" + NL + open(a["path"], encoding="utf-8").read().strip()


def packages_for(key):
    """Paquetes candidatos de una ficha resuelta por alias/nombre (para 'launch <nombre>'). [] si no hay ficha."""
    a = find(key, strict=True)
    return list(a["packages"]) if a else []

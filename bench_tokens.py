"""Prueba de ahorro de tokens: compara el flujo ANTIGUO (phone_look repetido) con el NUEVO (phone_wait / phone_install)
en el telefono real, llamando a las mismas funciones que usa el servidor MCP (mcp_server.call_tool).

Uso:  python bench_tokens.py [dispositivo] [--gap 6] [--reps 3]
  dispositivo: serial adb (p.ej. 192.168.100.206:39849), numero de 'phone devices' o 'app'
  --gap: segundos que tarda un agente entre dos phone_look (pensar + llamar); por defecto 6
No instala ni borra nada: solo abre la ficha de Play Store de una app YA instalada (BCI) y espera.
Con --install PAQUETE instala DE VERDAD una app gratis (flujo nuevo y antiguo) y la desinstala despues.

Modelo de coste (medido en la sesion de instalacion de BCI): cada llamada de herramienta obliga a releer
el contexto fijo (~69k tokens de instrucciones, cacheado = 0,1x), mas ~150 tokens que escribe el agente (5x)
y la salida de la herramienta, que entra en el contexto (escritura de cache = 2x).
"""
import json, math, os, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mcp_server as m  # noqa: E402

BASE_CTX = 69000      # tokens de instrucciones + lista de herramientas releidos por llamada
AGENT_OUT = 150       # tokens que escribe el agente por llamada (razonamiento corto + argumentos)
OLD_TOOLS_CHARS = 7911  # lista de herramientas de mcp_server 0.1.0 (medido antes del cambio)
PKG = "cl.bci.app.personas"
DONE = "Desinstalar|Abrir|Uninstall|Open"


def tok(chars):
    return math.ceil(chars / 3.5)   # aproximacion para texto en espanol con ids y coordenadas


def call_cost(out_chars):
    return BASE_CTX * 0.1 + AGENT_OUT * 5 + tok(out_chars) * 2


def call(name, **a):
    t = time.time()
    txt = "".join(c.get("text", "") for c in m.call_tool(name, a))
    return txt, time.time() - t


def reset(dev):
    call("phone_key", device=dev, key="HOME")
    time.sleep(1.5)


def old_wait(dev, gap):
    """Como lo hacia el agente: abrir la ficha y mirar la pantalla una y otra vez hasta ver el boton."""
    calls, chars, t0 = 0, 0, time.time()
    txt, _ = call("phone_open_url", device=dev, url=f"market://details?id={PKG}")
    calls, chars = calls + 1, chars + len(txt)
    first = True
    while True:
        time.sleep(gap)
        txt, _ = call("phone_look", device=dev, **({} if first else {"grep": DONE + "|Instalar|%"}))
        first = False
        calls, chars = calls + 1, chars + len(txt)
        if any(w in txt for w in DONE.split("|")) or time.time() - t0 > 90:
            break
    return calls, chars, time.time() - t0


def new_wait(dev):
    t0 = time.time()
    a, _ = call("phone_open_url", device=dev, url=f"market://details?id={PKG}")
    b, _ = call("phone_wait", device=dev, target=DONE, secs=60)
    return 2, len(a) + len(b), time.time() - t0, b


def new_install(dev):
    t0 = time.time()
    txt, _ = call("phone_install", device=dev, package=PKG)
    return 1, len(txt), time.time() - t0, txt


def _adb_uninstall(dev, pkg):
    import subprocess
    adb = os.environ.get("ADB", "adb")
    return subprocess.run([adb] + (["-s", dev] if dev and dev != "app" and not dev.isdigit() else []) + ["uninstall", pkg],
                          capture_output=True, text=True, timeout=60).stdout.strip()


def real_install(dev, pkg, gap):
    """Instalacion REAL de una app gratis: flujo nuevo (phone_install) y antiguo (tocar Instalar + look repetido).
    Desinstala la app despues de cada medicion (y al final pase lo que pase)."""
    out = {}
    try:
        _adb_uninstall(dev, pkg)
        reset(dev)
        t0 = time.time()
        txt, _ = call("phone_install", device=dev, package=pkg, secs=300)
        out["nuevo: phone_install"] = (1, len(txt), time.time() - t0)
        print("  phone_install ->", txt.splitlines()[0])
        print("  desinstalar ->", _adb_uninstall(dev, pkg))
        reset(dev)

        calls, chars, t0 = 0, 0, time.time()
        for name, a in (("phone_open_url", {"url": f"market://details?id={pkg}"}), ("phone_look", {})):
            if name == "phone_look":
                time.sleep(gap)
            txt, _ = call(name, device=dev, **a)
            calls, chars = calls + 1, chars + len(txt)
        txt, _ = call("phone_tap", device=dev, target="Instalar")
        calls, chars = calls + 1, chars + len(txt)
        while time.time() - t0 < 300:
            time.sleep(gap)
            txt, _ = call("phone_look", device=dev, grep="Abrir|Desinstalar|Cancelar|Instalando|%|Pendiente")
            calls, chars = calls + 1, chars + len(txt)
            if "Abrir" in txt or "Desinstalar" in txt:
                break
        out["antiguo: tocar + look repetido"] = (calls, chars, time.time() - t0)
        print(f"  antiguo -> {calls} llamadas hasta ver 'Abrir'")
    finally:
        print("  desinstalar ->", _adb_uninstall(dev, pkg))
        reset(dev)
    print(f"\nInstalacion REAL de {pkg}")
    print(f"{'flujo':32} {'llamadas':>8} {'salida tok':>10} {'seg':>6} {'coste efectivo':>15}")
    cost = {}
    for k, (c, ch, s) in out.items():
        cost[k] = c * call_cost(ch / c)
        print(f"{k:32} {c:8d} {tok(ch):10d} {s:6.1f} {cost[k]:15,.0f}")
    if len(cost) == 2:
        print(f"  ahorro: {100 * (1 - cost['nuevo: phone_install'] / cost['antiguo: tocar + look repetido']):.0f}%")
    with open(os.path.join(HERE, "logs", "bench_install.json"), "w", encoding="utf-8") as f:
        json.dump({"dev": dev, "pkg": pkg, "gap": gap, "rows": out, "cost": cost, "ts": time.strftime("%Y-%m-%d %H:%M:%S")}, f, indent=1)


def main():
    args = sys.argv[1:]
    if "--install" in args:   # python bench_tokens.py DEV --install PAQUETE [--gap 6]: instala y desinstala de verdad
        i = args.index("--install"); pkg = args[i + 1]
        gap = float(args[args.index("--gap") + 1]) if "--gap" in args else 6.0
        os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
        real_install(args[0] if args[0] != "--install" else None, pkg, gap)
        return
    gap = float(args[args.index("--gap") + 1]) if "--gap" in args else 6.0
    reps = int(args[args.index("--reps") + 1]) if "--reps" in args else 3
    pos = [x for i, x in enumerate(args) if not x.startswith("--") and (i == 0 or not args[i - 1].startswith("--"))]
    dev = pos[0] if pos else None

    new_tools = len(json.dumps([{"name": n, "description": d, "inputSchema": s} for n, d, s in m.TOOLS], ensure_ascii=False))
    print(f"dispositivo={dev or '(unico)'} gap={gap}s reps={reps}")
    print(f"lista de herramientas: antes {OLD_TOOLS_CHARS} chars (~{tok(OLD_TOOLS_CHARS)} tok) -> ahora {new_tools} chars "
          f"(~{tok(new_tools)} tok); se relee en CADA llamada")

    rows = {"antiguo: look repetido": [], "nuevo: phone_wait": [], "nuevo: phone_install": []}
    for r in range(reps):
        reset(dev)
        c, ch, s = old_wait(dev, gap)
        rows["antiguo: look repetido"].append((c, ch, s))
        reset(dev)
        c, ch, s, out = new_wait(dev)
        rows["nuevo: phone_wait"].append((c, ch, s))
        if r == 0:
            print("  phone_wait ->", out.splitlines()[0])
        reset(dev)
        c, ch, s, out = new_install(dev)
        rows["nuevo: phone_install"].append((c, ch, s))
        if r == 0:
            print("  phone_install ->", out.splitlines()[0])
    reset(dev)

    print("\nEscenario: abrir la ficha de BCI en Play Store y esperar a que se vea el boton final")
    print(f"{'flujo':26} {'llamadas':>8} {'salida tok':>10} {'seg':>6} {'coste efectivo':>15}")
    res = {}
    for k, v in rows.items():
        c = sum(x[0] for x in v) / len(v); ch = sum(x[1] for x in v) / len(v); s = sum(x[2] for x in v) / len(v)
        cost = sum(call_cost(x[1] / x[0]) * x[0] for x in v) / len(v)
        res[k] = cost
        print(f"{k:26} {c:8.1f} {tok(ch):10d} {s:6.1f} {cost:15,.0f}")
    old = res["antiguo: look repetido"]
    for k in ("nuevo: phone_wait", "nuevo: phone_install"):
        print(f"  ahorro {k}: {100 * (1 - res[k] / old):.0f}%")

    # Proyeccion con los datos reales de la instalacion de BCI (107 MB): 9 looks de progreso + 6 llamadas mas
    old_real = 15 * call_cost(400)
    new_real = (1 + 2) * call_cost(400)   # devices + phone_install (+ un look final opcional)
    print(f"\nProyeccion instalacion real de BCI (medido: 15 llamadas, ~152k efectivos en la sesion):")
    print(f"  antiguo ~{old_real:,.0f}  ->  nuevo ~{new_real:,.0f}  (ahorro {100 * (1 - new_real / old_real):.0f}%)")

    os.makedirs(os.path.join(HERE, "logs"), exist_ok=True)
    with open(os.path.join(HERE, "logs", "bench_tokens.json"), "w", encoding="utf-8") as f:
        json.dump({"dev": dev, "gap": gap, "reps": reps, "rows": rows, "cost": res, "tools_chars": [OLD_TOOLS_CHARS, new_tools],
                   "proyeccion_bci": [old_real, new_real], "ts": time.strftime("%Y-%m-%d %H:%M:%S")}, f, ensure_ascii=False, indent=1)
    print("detalle: logs/bench_tokens.json")


if __name__ == "__main__":
    main()

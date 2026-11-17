"""Benchmark: exploracion en frio vs 'goto' (grafo) en 3 tareas nuevas. Uso: python bench_graph.py [N] [serial]"""
import subprocess, sys, time, os, json, collections
N = int(sys.argv[1]) if len(sys.argv) > 1 else 3
S = sys.argv[2] if len(sys.argv) > 2 else "SERIAL"
ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
def P(*a):
    r = subprocess.run([sys.executable, "phone_agent.py", "--serial", S, *a], capture_output=True, text=True, encoding="utf-8")
    return r.stdout.strip()
def reset():
    P("ensure_home"); subprocess.run([ADB, "-s", S, "shell", "am", "force-stop", "com.android.settings"]); time.sleep(0.5)
TASKS = {
 "A tamano_texto": dict(target="Tamaño de texto", tap=True, marker="Vista previa",
    cold=[("launch","com.android.settings","-q"),("look",),("tap_text","Pantalla","-q"),("look",),("tap_text","Tamaño de texto","-q"),("look",)]),
 "B descanso_visual": dict(target="Descanso visual", tap=True, marker="FILTRO DE LUZ AZUL",
    cold=[("launch","com.android.settings","-q"),("look",),("tap_text","Pantalla","-q"),("look",),("tap_text","Descanso visual","-q"),("look",)]),
 "C agregar_cuenta": dict(target="Agregar cuenta", tap=False, marker="Agregar cuenta",
    cold=[("launch","com.android.settings","-q"),("look",),("scroll","down","-q"),("scroll","down","-q"),("scroll","down","-q"),("look",),("tap_text","Usuarios y cuentas","-q"),("look",)]),
}
res = collections.defaultdict(list)
for i in range(N):
    for name, t in TASKS.items():
        for mode in ("cold", "goto"):
            reset(); t0 = time.time(); calls = chars = 0; last = ""
            if mode == "cold":
                for c in t["cold"]:
                    if c[0] == "scroll": time.sleep(0.8)
                    o = P(*c); calls += 1; chars += len(o); last = o; time.sleep(0.6)
            else:
                o = P("goto", t["target"], *(["--tap"] if t["tap"] else [])); calls = 1; chars = len(o); last = o
            chk = P("look")                      # verificacion independiente, no se cuenta en llamadas/tokens
            ok = t["marker"] in chk
            res[(name, mode)].append((calls, chars // 4, time.time() - t0, ok, last if i == 0 else ""))
print("tarea | modo | llamadas ~tokens seg exito")
for (name, mode), v in sorted(res.items()):
    n = len(v); print(name, mode, "|", round(sum(x[0] for x in v) / n, 1), round(sum(x[1] for x in v) / n), round(sum(x[2] for x in v) / n, 1), f"{sum(x[3] for x in v)}/{n}")
print("--- salida goto (1a ejecucion) ---")
for (name, mode), v in sorted(res.items()):
    if mode == "goto": print(name, ":", v[0][4].splitlines()[0:3])

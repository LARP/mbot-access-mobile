"""Prueba del servidor MCP contra el dispositivo real: lo lanza como subproceso y hace
initialize -> tools/list -> tools/call (phone_devices, phone_know, phone_look, phone_shot).
Uso: python test_mcp.py [dispositivo]   (imprime solo resumenes: no vuelca el contenido de la pantalla)
"""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
dev = sys.argv[1] if len(sys.argv) > 1 else None

p = subprocess.Popen([sys.executable, os.path.join(HERE, "mcp_server.py")], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True, encoding="utf-8")
_id = 0


def send(method, params=None, notify=False):
    global _id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        _id += 1
        msg["id"] = _id
    p.stdin.write(json.dumps(msg) + "\n")
    p.stdin.flush()
    if notify:
        return None
    line = p.stdout.readline()
    assert line, "el servidor cerro stdout: " + p.stderr.read()
    r = json.loads(line)   # si algo ensucia stdout, esto falla
    assert r["id"] == _id, r
    return r


def tool(name, **args):
    r = send("tools/call", {"name": name, "arguments": args})
    assert "result" in r, r
    return r["result"]


def txt(res):
    return next((c["text"] for c in res["content"] if c["type"] == "text"), "")


r = send("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "0"}})
print("initialize:", r["result"]["protocolVersion"], r["result"]["serverInfo"])
send("notifications/initialized", notify=True)
print("ping:", send("ping")["result"])
tl = send("tools/list")["result"]["tools"]
print("tools/list:", len(tl), "herramientas:", ", ".join(t["name"] for t in tl))
d = tool("phone_devices")
print("phone_devices:", d["isError"], "|", txt(d).replace("\n", " ; ")[:200])
k = tool("phone_know", app="chrome")
print("phone_know chrome:", k["isError"], txt(k).splitlines()[0])
h = tool("phone_help")
print("phone_help:", len(txt(h).splitlines()), "lineas")
a = {"device": dev} if dev else {}
c = tool("phone_caps", **a)
print("phone_caps:", txt(c).splitlines()[0])
lk = tool("phone_look", **a)
t = txt(lk)
print("phone_look: isError=%s lineas=%d primera=%r" % (lk["isError"], len(t.splitlines()), t.splitlines()[0] if t else ""))
sh = tool("phone_shot", **a)
kinds = [c["type"] for c in sh["content"]]
print("phone_shot:", kinds, txt(sh)[:80])
bad = send("tools/call", {"name": "no_existe", "arguments": {}})
print("herramienta desconocida:", bad.get("error", {}).get("code"))
unk = send("foo/bar")
print("metodo desconocido:", unk.get("error", {}).get("code"))
p.stdin.close()
p.wait(timeout=5)
print("stderr del servidor:", repr(p.stderr.read()[:200]))
print("OK: stdout solo tuvo JSON-RPC")

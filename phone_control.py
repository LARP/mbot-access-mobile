"""Control basico de Android por ADB: vista en vivo + clic/arrastre + botones + texto.
Uso:  python phone_control.py [serial]
Clic = tap | Arrastrar = swipe | Rueda = scroll | Clic derecho = Atras
"""
import ctypes, subprocess, sys, threading, time, io, tkinter as tk
from tkinter import ttk

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
NOWIN = 0x08000000  # CREATE_NO_WINDOW


def adb(*args, serial=None, binary=False, timeout=15):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout, creationflags=NOWIN)
    return r.stdout if binary else r.stdout.decode(errors="replace").strip()


def pick_serial():
    if len(sys.argv) > 1:
        return sys.argv[1]
    for line in adb("devices").splitlines()[1:]:
        p = line.split()
        if len(p) >= 2 and p[1] == "device":
            return p[0]
    return None


try:
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    pass


class App:
    def __init__(self, serial):
        self.s = serial
        self.root = tk.Tk()
        self.root.title(f"Phone control - {serial}")
        w, h = self.screen_size()
        self.dw, self.dh = w, h
        sh_ = self.root.winfo_screenheight()
        self.scale = min(1.0, (sh_ - 160) / h)  # se ajusta a la altura del monitor
        self.cw, self.ch = int(w * self.scale), int(h * self.scale)
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)
        self.canvas = tk.Canvas(self.root, width=self.cw, height=self.ch, bg="black", highlightthickness=0)
        self.canvas.grid(row=0, column=0, sticky="nsew")
        self.canvas.bind("<Configure>", self.on_resize)
        self.root.geometry("+20+10")
        self.img_id = None
        self.photo = None
        self.press = None
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)
        self.canvas.bind("<Button-3>", lambda e: self.key(4))
        self.canvas.bind("<MouseWheel>", self.on_wheel)

        side = ttk.Frame(self.root, padding=6)
        side.grid(row=0, column=1, sticky="n")
        btns = [("Atras", lambda: self.key(4)), ("Inicio", lambda: self.key(3)),
                ("Recientes", lambda: self.key(187)), ("Encender/Apagar pantalla", lambda: self.key(26)),
                ("Vol +", lambda: self.key(24)), ("Vol -", lambda: self.key(25)),
                ("Notificaciones", lambda: self.sh("cmd statusbar expand-notifications")),
                ("Desbloquear (swipe arriba)", self.unlock),
                ("Enter", lambda: self.key(66)), ("Borrar", lambda: self.key(67)),
                ("Captura -> PNG", self.save_shot)]
        for t, f in btns:
            ttk.Button(side, text=t, command=f, width=26).pack(pady=1)
        ttk.Label(side, text="Texto a escribir:").pack(pady=(8, 0))
        self.entry = ttk.Entry(side, width=28)
        self.entry.pack()
        self.entry.bind("<Return>", lambda e: self.send_text())
        ttk.Button(side, text="Enviar texto", command=self.send_text, width=26).pack(pady=2)
        ttk.Label(side, text="PIN/numero (ej. 1234):").pack(pady=(8, 0))
        self.pin = ttk.Entry(side, width=28)
        self.pin.pack()
        ttk.Button(side, text="Escribir PIN + Enter", command=self.send_pin, width=26).pack(pady=2)
        self.status = ttk.Label(side, text="", foreground="gray")
        self.status.pack(pady=8)

        self.running = True
        self.last_png = None
        threading.Thread(target=self.loop, daemon=True).start()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(100, self.draw)
        self.root.mainloop()

    # ---- adb helpers
    def sh(self, c):
        threading.Thread(target=lambda: adb("shell", c, serial=self.s), daemon=True).start()

    def key(self, code):
        self.sh(f"input keyevent {code}")

    def screen_size(self):
        out = adb("shell", "wm", "size", serial=self.s)
        m = out.split(":")[-1].strip().split("x")
        return int(m[0]), int(m[1])

    def send_text(self):
        t = self.entry.get()
        if t:
            esc = "".join("%s" if c == " " else ("\\" + c if c in "&|;<>()$`\\\"'*?[]#~=%!{}^" else c) for c in t)
            self.sh(f"input text '{esc}'" if "'" not in esc else f'input text "{esc}"')
            self.entry.delete(0, "end")

    def send_pin(self):
        p = self.pin.get()
        if p:
            self.sh(f"input text {p}; input keyevent 66")
            self.pin.delete(0, "end")

    def unlock(self):
        x, h = self.dw // 2, self.dh
        self.sh(f"input keyevent 224; input swipe {x} {int(h*.8)} {x} {int(h*.2)} 250")

    def save_shot(self):
        if self.last_png:
            fn = f"captura_{int(time.time())}.png"
            open(fn, "wb").write(self.last_png)
            self.status.config(text=f"Guardada {fn}")

    def on_resize(self, e):
        self.scale = min(e.width / self.dw, e.height / self.dh)
        self.cw, self.ch = max(1, int(self.dw * self.scale)), max(1, int(self.dh * self.scale))
        self._drawn = None

    # ---- mouse
    def to_dev(self, e):
        return int(e.x / self.scale), int(e.y / self.scale)

    def on_press(self, e):
        self.press = (self.to_dev(e), time.time())

    def on_release(self, e):
        if not self.press:
            return
        (x1, y1), t0 = self.press
        x2, y2 = self.to_dev(e)
        ms = max(100, int((time.time() - t0) * 1000))
        if abs(x2 - x1) < 15 and abs(y2 - y1) < 15:
            if ms > 600:
                self.sh(f"input swipe {x1} {y1} {x1} {y1} {ms}")  # pulsacion larga
            else:
                self.sh(f"input tap {x1} {y1}")
        else:
            self.sh(f"input swipe {x1} {y1} {x2} {y2} {min(ms, 800)}")
        self.press = None

    def on_wheel(self, e):
        x, y = self.to_dev(e)
        d = int(self.dh * 0.25) * (1 if e.delta > 0 else -1)
        self.sh(f"input swipe {x} {y} {x} {y + d} 200")

    # ---- live view
    def loop(self):
        while self.running:
            try:
                png = adb("exec-out", "screencap", "-p", serial=self.s, binary=True, timeout=10)
                if png[:4] == b"\x89PNG":
                    self.last_png = png
            except Exception:
                pass
            time.sleep(0.1)

    def draw(self):
        png = self.last_png
        if png and png is not getattr(self, "_drawn", None):
            self._drawn = png
            try:
                from PIL import Image, ImageTk
                im = Image.open(io.BytesIO(png)).resize((self.cw, self.ch))
                self.photo = ImageTk.PhotoImage(im)
            except ImportError:
                p = tk.PhotoImage(data=png)
                k = max(1, round(1 / self.scale))
                self.photo = p.subsample(k, k) if self.scale < 1 else p
            if self.img_id is None:
                self.img_id = self.canvas.create_image(0, 0, anchor="nw", image=self.photo)
            else:
                self.canvas.itemconfig(self.img_id, image=self.photo)
        self.root.after(80, self.draw)

    def close(self):
        self.running = False
        self.root.destroy()


if __name__ == "__main__":
    s = pick_serial()
    if not s:
        sys.exit("No hay dispositivo autorizado. Revisa 'adb devices'.")
    App(s)

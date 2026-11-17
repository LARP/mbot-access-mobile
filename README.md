# mbot-access-mobile

> Comportamiento universal del agente ante popups, errores y logins: ver [AGENT_PROTOCOL.md](AGENT_PROTOCOL.md). `look` marca `OBSTACULO[A|B|STOP]` con el consejo.

Control de Android por USB (pantalla rota)

**Para un agente de IA (Claude Code u otro) que no conoce el proyecto:** punto de entrada único = `phone help --agent` (manual compacto, `--json` para máquina) y `phone N caps` (capacidades vivas). Skill de Claude Code: [skill/README.md](skill/README.md). Conocimiento por app: [knowledge/README.md](knowledge/README.md) (`phone know`). Servidor MCP: ver "Servidor MCP" más abajo.

Un solo comando: `phone <dispositivo> <accion>`. Salida corta (pensada para agentes, pocos tokens).

## Instalacion en otro PC
1. Copiar esta carpeta completa (incluye `scrcpy/`, portable).
2. Ejecutar `phone check`: detecta adb, scrcpy y Pillow, y si falta algo **recomienda como instalarlo**:
   - adb (obligatorio): `winget install Google.PlatformTools` (o descomprimir platform-tools en `./platform-tools`)
   - scrcpy (opcional, solo `live`): `winget install Genymobile.scrcpy`
   - Pillow (opcional, solo `shot`): `pip install pillow`
3. Telefono: activar Depuracion USB, conectar y aceptar el aviso. Wi-Fi: `adb connect IP:PUERTO`.

adb se busca en: variable `ADB`, `./platform-tools`, PATH, ANDROID_HOME y rutas tipicas. Requiere Python 3.

## Uso
```
phone check                        # verifica requisitos y recomienda instalacion
phone pair [CODIGO]                # modo app: vincula este PC con el telefono (mDNS + codigo de 6 digitos)
phone connect http://IP:8765 TOKEN # modo app, forma manual (token unico); ver seccion 'Modo app'
phone devices                      # lista: "1 SERIAL device MODELO (usb|wifi)"
phone 1 look                       # estado + texto de pantalla (mejor primer paso)
phone 1 ensure_home                # si no esta en home, pulsa Home
phone 1 tap_text "Chrome"          # tocar por texto visible
phone 1 tap 540 1200               # tocar por coordenadas
phone 1 scroll down                # scroll up | down
phone 1 text "hola mundo"          # escribir (solo ASCII)
phone 1 key ENTER                  # tambien BACK, HOME, 66...
phone 1 back | home | recents | wake | sleep
phone 1 launch com.android.settings
phone 1 shot                       # captura JPEG 360px (solo si el texto no alcanza)
phone 1 live                       # abre vista en vivo fluida (scrcpy) para el humano
phone help --agent [--json]        # manual para agentes (generado desde phone_help.SPECS: no se desincroniza)
phone 1 caps                       # capacidades: modo adb|basic, permisos, pausa, funciones (en app: /v1/capabilities)
phone know [app]                   # fichas de conocimiento por app (knowledge/apps/<n>/APP.md)
```
`<dispositivo>` = numero de `phone devices` o el serial. Con un solo dispositivo USB puedes omitirlo
(`phone look`). Anteponer `--json` da salida completa.

## Modo app (sin depuración USB)
Alternativa a adb para un teléfono sin modo desarrollador: la app `android/` (Mbot Agent) usa solo un Servicio de Accesibilidad y un servidor HTTP en la Wi-Fi (puerto 8765, solo redes privadas, token Bearer). Los mismos comandos `phone ...` funcionan contra ella. Plan completo en [ROADMAP.md](ROADMAP.md) (Fase 0).

1. Compilar (JDK 17 + Android SDK 34): `cd android && gradlew assembleDebug` → `android/app/build/outputs/apk/debug/app-debug.apk`. Instalar el APK en el teléfono (archivo o `adb install -r`).
2. En el teléfono: abrir **Mbot Agent**. El asistente "Preparar el teléfono" se marca solo al volver a la app:
   1. Servicio de Accesibilidad (en Android 13+ instalado a mano: Información de la app › ⋮ › **Permitir ajustes restringidos**, hay botón para abrirla).
   2. Acceso a notificaciones (para `phone notifs`).
   3. Avisos de la app (Android 13+): para ver "Detener" también en la notificación.
   4. Quitar la optimización de batería.
   5. Segundo plano del fabricante (Huawei/Honor, Xiaomi, Samsung, Oppo/Realme/OnePlus, Vivo: abre la pantalla conocida o, si no existe, Información de la app; no se puede detectar, se marca a mano con "Ya lo hice").
3. **Vincular** (recomendado, sin copiar tokens): en la app pulsa **Vincular un PC** (código de 6 dígitos, 2 minutos, un solo uso, 5 fallos lo invalidan) y en el PC, misma Wi-Fi:
   - `phone pair` busca teléfonos por mDNS (`_mbot._tcp`, solo stdlib de Python, ~2 s) y pregunta el código; o directamente `phone pair 123456`.
   - `phone pair IP CODIGO` (p. ej. `phone pair 192.168.1.20 123456`) salta el descubrimiento: úsalo si el router aísla los equipos/bloquea multicast o el Firewall de Windows bloquea las respuestas UDP a `python.exe` (en ese caso `phone pair` dice que no encontró nada y sugiere esta forma).
   - Cada PC recibe su **propio token** (la app guarda solo su hash). La app lista los PC vinculados con un botón **Revocar**; un token revocado da `ERR token invalido o revocado` (401). El token unico de la Fase 0 sigue funcionando (aparece como "Token único (manual)" y también se puede revocar).
   - Forma manual: `phone connect http://IP:8765 TOKEN` (verifica con `/v1/ping` y `/v1/capabilities`). `phone disconnect` borra `logs/app_conn.json`. Las variables `PHONE_URL` / `PHONE_TOKEN` mandan sobre el archivo.
4. Usar: `phone app look`, `phone app tap @e12`, `phone app tap_text "Ajustes"`, `phone app text "hola ñandú ✓"`... `phone devices` lista la conexión como `N app device MODELO (app) URL`. Sin dispositivo adb autorizado (o con `PHONE_URL`), `phone look` ya elige el modo app solo.
   Con adb a mano (solo para pruebas): `adb forward tcp:18765 tcp:8765` y `phone connect http://127.0.0.1:18765 TOKEN`.

Novedades de la Fase 1 (solo modo app; con adb la salida no cambia):
- **Ids `@eN`**: cada lectura numera los elementos (`@e12 Ajustes @540,1200 *`). `phone app tap @e12` hace clic **por nodo** (o por su ancestro clicable) en el teléfono: no depende de coordenadas ni de que el elemento se haya movido. Si el id es de una lectura anterior se relocaliza por id/texto/descripción en el árbol actual (el teléfono guarda las 4 últimas lecturas; más viejas: `ERR ids caducados`, repetir `look`). Si el nodo no admite clic, toca el centro. La salida indica el canal: `via=node_click|ancestor_click|relocated_click|gesture`. `tap_text` en modo app también prefiere el clic por nodo. Con `--diff` los ids de líneas no repetidas siguen valiendo para la lectura donde se imprimieron.
- **Espera por cambio de pantalla**: cada acción (`tap`, `tap @eN`, `swipe`, `scroll`, `key`, `text`, `launch`, `open`) espera en el teléfono a que pasen ~300 ms sin eventos de accesibilidad y el árbol sea distinto → devuelve en cuanto cambia (típico 0,4-1,2 s). Si no cambia, espera hasta el tope (2,5 s; `launch`/`open` 4 s) y el PC imprime `=sin cambios` (sin volver a leer la pantalla). El PC ya no usa pausas fijas en modo app.
- **`phone app notifs`**: notificaciones activas con el mismo formato que con adb (`paquete: titulo - texto`), sin las "en curso" del sistema. Necesita el permiso de acceso a notificaciones; si falta: `ERR falta permiso de acceso a notificaciones: ...` con cómo darlo.
- **Aviso visible y pausa**: mientras el agente actúa se muestra una pastilla "Agente trabajando · **Detener**" arriba (capa de accesibilidad, no necesita permiso de superposición; se oculta a los ~5 s y deja pasar los gestos) y una notificación con la misma acción. **Detener** (pastilla o notificación) pausa al agente: las acciones devuelven HTTP 423 → `ERR pausado por el usuario`; las lecturas (`look`, `state`, `read`, `info`, `apps`, `notifs`) siguen funcionando. Se reanuda solo desde el teléfono (botón "Reanudar" de la pastilla/notificación o de la app). Si el usuario **toca el teléfono** mientras el agente trabaja (clics, pulsación larga o un deslizamiento largo fuera de un comando en curso y de los 700 ms posteriores, y solo si el agente actuó hace menos de 2 min) la pausa es automática: `ERR pausado: el usuario esta usando el telefono`. Se puede desactivar en la app ("Pausar solo si toco la pantalla...").
- **Reglas en el teléfono**: `text` sobre un campo de contraseña → `ERR campo de contraseña: lo escribe el usuario` (y los campos de contraseña nunca devuelven su texto). Un toque (por nodo, `tap_text` o coordenadas) sobre algo que parece enviar/pagar/comprar/eliminar/borrar/confirmar/publicar (lista ES+EN, por etiqueta o id) se hace en **un solo intento, sin canal alternativo**, y el PC añade `AVISO accion de riesgo alto: <etiqueta>` tras la pantalla. Es un aviso, no un bloqueo: el agente debe tratarlo como nivel B (ver AGENT_PROTOCOL.md).

Diferencias y límites del modo básico:
- No disponibles (`ERR no disponible en modo app (basico)`): `logs`, `live`, `accounts`; `top` es una versión mínima (foco + paquetes de las ventanas). Los pasos `stop` de los macros se omiten y los `am` fallan.
- `shot`/`icons`: solo Android 11+ (`takeScreenshot`); en Android 10 o menos devuelve `ERR captura no disponible`. `screenshot` guarda JPEG.
- `wake` no existe (una app de accesibilidad no puede encender la pantalla): `ERR WAKE no disponible en modo basico`. `sleep` bloquea la pantalla (Android 9+) y luego no se puede volver a despertar desde el PC.
- En modo básico `key ENTER` solo funciona en Android 11+ (o en campos multilínea) y `wake` no existe; `key DEL` borra el último carácter del campo con foco. Otras teclas: `ERR tecla no soportada`. Con el **modo avanzado** (abajo) `key` inyecta cualquier tecla real (ENTER, WAKE, POWER, códigos) también en Android 9.
- `text` necesita un campo de texto con foco (tocarlo antes), añade al contenido actual (ignora el hint) y admite Unicode completo.
- `look` no incluye la propia app Mbot Agent (muestra el token) ni la barra de estado; los nodos se omiten si no son visibles, como uiautomator. Los campos de contraseña salen con `[campo pwd]` y sin texto.
- El token del PC se guarda en claro en `logs/app_conn.json` (ni el token ni el código de `phone pair` se registran en `logs/runs.jsonl`). Cualquier equipo de la LAN con un token válido controla el teléfono: revocarlo en la app (lista de PC vinculados) o regenerar el token único si se filtra. El anuncio mDNS lleva solo modelo y versión de API, nunca el token.
- Mientras `adb` use `uiautomator` el sistema suelta nuestro servicio de Accesibilidad unos segundos (se reconecta solo): no mezclar `phone` por adb y por app a la vez en las pruebas.

## Conocimiento para el agente (Fase 2)
- `phone help --agent` imprime (~85 líneas) el manual: transporte, comandos con sintaxis, marcas de salida (`@eN`, `*`/`~`, `[campo]`, `=sin cambios`, `ERR`, `AVISO`, `OBSTACULO[...]`, `PANTALLA DE LOGIN`), bucle, riesgos y qué falta en cada modo. La lista de comandos sale de `phone_help.SPECS`, que también define `COMMANDS` del dispatcher (`python phone_help.py --check` comprueba que coinciden).
- `phone N caps`: en modo app muestra `/v1/capabilities` (`mode: basic`, `permissions {accessibility, notifications, battery_exempt}`, `paused`, `features [ids wait_change notifs pair risk]`, `shot`, `version`); en adb se construye en local (`modo=adb`). El modo `advanced` está reservado para la Fase 3 y todavía no existe.
- **Skill** para Claude Code: `skill/mbot-phone/SKILL.md` + instalación en [skill/README.md](skill/README.md) (variable `MBOT_HOME`).
- **Fichas por app**: `knowledge/apps/<nombre>/APP.md` (formato en [knowledge/README.md](knowledge/README.md)); `look`/`read`/acciones añaden `APP: <app> -> knowledge/...` una vez por sesión (30 min de inactividad la reinician; `--full` siempre). `phone know [app]` las lista/imprime y `launch <alias>` las usa.
- **Servidor MCP** (`mcp_server.py`, solo stdlib, stdio): herramientas `phone_devices`, `phone_help`, `phone_caps`, `phone_look`, `phone_tap`, `phone_text`, `phone_key`, `phone_scroll`, `phone_launch`, `phone_open_url`, `phone_notifs`, `phone_info`, `phone_shot` (imagen), `phone_run_macro`, `phone_macros`, `phone_advanced`, `phone_know`. Registrar: `claude mcp add mbot-phone -- python D:\App\Mobile_Agent\mcp_server.py`. Prueba: `python test_mcp.py [dispositivo]`.

## Memoria de rutas (macros)
- Cada macro tiene un **estado**: `borrador` (recién grabado con `remember`) → `prueba` (tras 1 éxito) → `verificada` (tras 3 éxitos seguidos con la huella coincidente). Un fallo suma `fallos`; una verificada con 2 fallos seguidos baja a `prueba`. `run` avisa `AVISO macro en <estado>` si no es verificada. A mano: `phone N macro promote|demote <nombre>`. Contadores: `ok`, `fallos`, `ultimo` (+ `ok_racha`, `fallo_racha`). El archivo antiguo se migra solo (copia en `memory/macros.json.bak`).
- `phone N macros` lista las rutas guardadas con su estado; `phone N run brillo` o `phone N run cuenta_google` las ejecuta en UNA llamada.
- Se definen en `memory/macros.json` (pasos `do/args/expect`, composicion con `use`, alternativas `A|B`, parametros `{{x}}`).
- Cada paso se verifica con `expect`; si falla devuelve `FAIL paso=i/n` + la pantalla actual: explorar desde ahi (con tap_text/scroll_to) y corregir el macro.
- Aprender: tras resolver algo explorando, `phone N remember <nombre> --last 4 --desc "..." --result "texto"` guarda las ultimas 4 acciones exitosas como macro (expect derivado de la pantalla; los emails no se guardan, solo '@'). Parametros: `remember llamar --last 3 contacto=Ana` convierte 'Ana' en `{{contacto}}`. `phone N forget <nombre>` lo borra.
- Revisar el macro grabado en `memory/macros.json`; evitar pasos `tap X Y` (coordenadas fragiles).
- `--diff` (en look o en cualquier accion con pantalla): devuelve solo `+nuevo / -desaparecido` respecto a la ultima pantalla emitida; util para popups, interruptores o scroll corto. Al cambiar de pantalla completa en la misma app no ahorra nada.
- Huella: `remember` guarda marca/idioma del dispositivo; `run` avisa (`AVISO huella distinta`) si el macro se grabo en otro equipo o idioma (los `expect` siguen validando cada paso).
- `goto` (grafo de pantallas, prototipo): `phone N goto "Tamaño de texto" --tap` navega por el camino mas corto entre pantallas ya aprendidas (se aprenden solas al ejecutar acciones y macros) y deja la pantalla pedida; `phone N graph` resume el grafo (`memory/graph.json`). Pide telefono desbloqueado. Solo funciona si las pantallas del camino ya se visitaron; si no hay ruta responde `ERR sin ruta conocida`: explorar y se aprendera.
- Benchmarks: `bench.sh` (macros), `bench_graph.py` (goto vs exploracion en frio).
- Medicion: `logs/runs.jsonl` + `phone stats` (etiquetas PHONE_TASK / PHONE_MODE / PHONE_RUN). Las acciones devuelven la pantalla resultante (`-q` la quita, `--grep X` filtra).

## Receta para un agente
0. Antes de explorar: `phone N caps`, `phone N macros` (si hay uno que sirve, `phone N run <macro>`) y, si `look` indica `APP: ...`, `phone know <app>`.
1. `phone devices` y elegir dispositivo.
2. `phone N ensure_home`.
3. Repetir: `phone N look` -> decidir -> `tap_text` / `tap` / `scroll` / `text`.
4. `shot` solo si `look` no muestra nada util (juegos, imagenes).

## Notas
- `look`/`read` fallan con pantalla bloqueada: desbloquear antes (el PIN lo escribe el usuario).
- Las coordenadas de `read`/`look` (`@x,y`) son reales; en `shot` multiplicar por el factor indicado.
- No usar `live` y capturas frecuentes a la vez: compiten por el cable USB.
- `scrcpy` esta en `scrcpy/` (v5.0.1). `phone_live.bat [serial]` tambien abre la vista en vivo.

## Protocolo: pantallas de login o desconocidas
1. Ejecutar `phone 1 look --full` (sin truncar, con clase/id/bounds). `look` ya avisa de botones sin etiqueta (`[icono sin etiqueta ...]`) y marca `PANTALLA DE LOGIN` listando las opciones clicables.
2. Si aparece `AVISO: N boton(es) de solo icono`, abrir la imagen `logs/icons.png` (hoja numerada #1..#N con cada icono recortado y sus coordenadas) e identificar cada boton (Google, Apple, Facebook, correo, telefono...). `shot` queda para ver la pantalla completa. Si el id sugiere algo, `look` lo anota como `posible=...`.
3. Enumerar TODAS las opciones de acceso (incluidos botones de solo icono, enlaces del pie como "Crear cuenta" o "Olvidaste...", y lo que quede tras scroll) antes de responder.
4. No concluir "no hay sesion" sin esa lista; preguntar al usuario que metodo usar. No dar por existente o inexistente una opcion sin haberla visto en `look` o `shot`.
5. El agente puede llegar hasta el formulario elegido, pero usuario, contrasenas y codigos los escribe siempre el usuario.

Notas de `look`: limpia tokens decorativos de iconos (`<icon>`, `CHEVRON_RIGHT`, `AT`), fusiona el texto de un boton con su etiqueta hija usando el centro del boton, y solo marca `PANTALLA DE LOGIN` en pantallas cortas (no en articulos/feeds largos). Probado en X, Chrome, Play Store, Ajustes y el launcher.

### Robustez de `look` (P0/P1)
- Volcado fresco (borra el XML previo) y reintento hasta estabilizar si la pantalla esta vacia/en transicion; si sigue vacia: `ESTADO: pantalla sin elementos legibles ... usa shot`.
- Overlays/dialogos: `DIALOGO SISTEMA` (app no responde -> ESPERAR), `SELECTOR 'Abrir con'`, permisos, y `OVERLAY de Google` (gestor de contrasenas) sobre la app lanzada. En esos casos NO se marca login: cerrar el overlay y volver a mirar.
- `launch` acepta nombre ('instagram', 'play store') o paquete; avisa si no existe y recuerda la app objetivo.
- Login: si la pantalla es desplazable hace hasta 2 scrolls y lista "Mas abajo (tras scroll)"; avisa si el teclado tapa opciones y si la pantalla es desplazable.
- Iconos sin etiqueta: numerados por posicion (arriba-abajo, izq-der); `icons` o login generan `logs/icons.png` (cuadricula; avisa si los recortes salen negros por ventana protegida). Umbrales en dp (independientes de resolucion).
- Dedupe por contencion (no repite el texto de un boton ya listado).

### Comandos ADB extra (solo lectura salvo `open`/`wait`)
`info` (equipo, bateria, red, internet) | `top` (actividad en foco + ventanas visibles/overlays) | `notifs` (notificaciones sin abrir apps) | `apps [filtro]` | `logs [paquete]` (crashes/ANR de logcat) | `open <url>` | `wait <texto|paquete> [seg]`. El volcado de pantalla usa `uiautomator dump /dev/tty` (un solo viaje, sin archivo) con respaldo al metodo anterior.

### Lectura robusta y rapida (v3)
- `look` ~2.3 s (antes ~3.9 s): estado en UNA llamada adb, en paralelo con el volcado; launcher/densidad en cache (`logs/dev_*.json`). El minimo con `uiautomator dump` es ~2 s (arranque JVM + espera de idle); bajar de 1 s exigiria un servidor persistente en el telefono (no instalado).
- `shot`/iconos usan captura cruda (~1.1 s, antes ~3.3 s).
- Reintentos con espera creciente; si uiautomator no logra estabilidad (video/animacion) no repite por archivo; compara paquete del volcado con el foco; avisa si no coinciden.
- Pantallas ilegibles: detecta `FLAG_SECURE` (no capturable), nombra WebView/Flutter/juego y toma captura automatica (`logs/auto_shot.jpg`).
- Marcas: `[x]`/`[ ]` casillas e interruptores, `[campo]`/`[campo pwd]` campos de texto, `(sel)`, `(inactivo)`, `~` = probablemente tocable (Flutter/Compose). Truncado inteligente con `…(+N)`.

## Modo avanzado (Fase 3)

Un **asistente** (`com.mbot.access.mobile.helper.Main`) corre con identidad shell (uid 2000) mediante `app_process`, como Shizuku, y la app habla con él por Binder (el asistente entrega su Binder al ContentProvider de la app; solo atiende a la UID de la app). Da lo que la Accesibilidad no puede:

| Función | Basico | Avanzado (probado en Android 9) |
|---|---|---|
| `tap`/`swipe` | gesto de accesibilidad (~61 ms) | evento real inyectado (~29 ms) |
| `key ENTER`, `key 66`, cualquier tecla | solo ENTER en Android 11+ | siempre |
| `wake` | no | si |
| `shot` | Android 11+ | si (Android 9: SurfaceControl, ~130 ms) |
| `clip get/set` | no | si (Android 10+ puede negar la lectura: sin probar) |
| `vd ...` pantalla virtual oculta | no | parcial en Android 9 (ver abajo) |
| `WRITE_SECURE_SETTINGS` | no | `advanced grant` (la app reactiva su Accesibilidad si se cae) |

`phone N caps` dice `modo=advanced` (con `funciones:`) cuando el asistente esta vivo y `basic` si no; con el asistente parado todo se comporta como antes.

**Forma A (desde el PC por USB; probada):** con la Depuracion USB autorizada:
```
phone 1 advanced start      # sube helper/mbot-helper.sh, arranca app_process en segundo plano (~2 s) y comprueba que la app lo ve
phone 1 advanced status     # adb + app
phone 1 advanced grant      # concede WRITE_SECURE_SETTINGS (por el asistente); revoke lo quita
phone 1 advanced stop       # para el asistente (la app deja de reintentarlo)
```
El asistente muere al reiniciar el telefono: hay que repetir `start` (forma A). Tras `adb install -r` sigue vivo con el codigo viejo (`caps` avisa "OBSOLETO"): repetir `start`.

**Forma B (sin PC, Android 11+, SIN PROBAR en dispositivo):** en la app, seccion "Modo avanzado": "Emparejar con depuracion inalambrica" abre Opciones de desarrollador; con "Vincular dispositivo con codigo" abierto, el codigo de 6 digitos se escribe en una notificacion de la app (RemoteInput). La app es cliente adb (emparejamiento SPAKE2 + TLS 1.3, clave RSA en el almacenamiento privado cifrada con el Keystore), conecta a `_adb-tls-connect._tcp` y ejecuta el mismo script. Si el asistente cae (reinicio) y esta emparejada, lo reintenta sola (con `WRITE_SECURE_SETTINGS` enciende antes `adb_wifi_enabled`). El protocolo se contrasto con AOSP y con el `adb` real del PC (`tools/fake_adbd_pair.py`, `tools/fake_adbd_connect.py`) y las partes puras tienen pruebas JVM (`cd android && ./gradlew testDebugUnitTest`).

**Pantalla virtual (`phone app vd ...`)**: `vd start [WxH] [dpi]`, `vd launch <app>`, `vd shot [ruta]`, `vd look`, `vd tap @eN`, `vd text "..."`, `vd status`, `vd stop`. En Android 9 funciona: crear la pantalla, lanzar la app alli (no se ve en la pantalla real), capturar su contenido, leer su arbol y pulsar por id (clic de accesibilidad). NO funciona: `vd tap X Y`, `vd swipe`, `vd key` (la inyeccion con displayId llega en Android 10) y el arbol solo se lee mientras esa app es la ventana activa; mientras existe, `state` puede mostrar su paquete como foco. `vd stop` cierra (force-stop) las apps lanzadas alli.

**Advertencias:** algunas apps de banca detectan las Opciones de desarrollador/depuracion; la depuracion inalambrica requiere Wi-Fi; desactivar: boton "Desactivar modo avanzado" de la app (o `advanced revoke` + `advanced stop`) y apagar la depuracion en Opciones de desarrollador.

# Conocimiento por app (`knowledge/apps/<nombre>/APP.md`)

Una ficha por app con lo que un agente debe saber antes de tocarla: interfaz, tareas habituales con nuestros comandos, rarezas y qué acciones son de riesgo. Se cargan solas: no hay que registrarlas.

## Cómo las usa `phone`
- `phone know` lista las fichas; `phone know <nombre|alias|paquete>` imprime una.
- `look`, `read` y las acciones que devuelven pantalla añaden **una línea al final**: `APP: <display> -> knowledge/apps/<n>/APP.md`, solo la primera vez que se ve ese paquete en la "sesión" (estado en `logs/know_seen.json`; se reinicia tras 30 min sin uso). `look --full` la muestra siempre. No se añade con `--json`.
- `phone N launch <nombre>` resuelve primero por `aliases`/`name` de las fichas (al primer paquete instalado de `packages`) y después por la lógica habitual.

## Formato
```
---
name: whatsapp                  # = nombre de la carpeta, minúsculas y guiones
display: WhatsApp               # nombre para mostrar
packages: ["com.whatsapp"]      # paquetes (varios si cambia por fabricante). Lista en línea o con "- item"
aliases: ["WhatsApp", "Whats"]  # nombres con los que lo llamaría un usuario (ES + EN); evita genéricos ("Música", "Correo")
open: WhatsApp                  # etiqueta que muestra el lanzador
description: Mensajería         # una línea
---
## Qué es
## Interfaz
## Tareas frecuentes
## Fenómenos conocidos
## Riesgos
```
El frontmatter se lee con un parser mínimo (clave: valor; listas `[a, b]` o con guiones; sin YAML anidado). Las secciones van en español y son texto libre (Markdown).

## Qué escribir
- **Qué es**: una o dos frases y el paquete.
- **Interfaz**: etiquetas reales que ve `look`, zonas de la pantalla, cómo se pagina o busca.
- **Tareas frecuentes**: listas de pasos con comandos `phone N ...` (preferir `@eN` y `tap_text`; evitar coordenadas salvo que sean estables).
- **Fenómenos conocidos**: falsos positivos de `look`, pantallas de bienvenida, esperas, cosas que dejan la pantalla vacía.
- **Riesgos**: qué acciones son nivel **B** (pedir al usuario) o **STOP** en esa app (ver AGENT_PROTOCOL.md) y qué datos son privados.

## Reglas
- Solo hechos **genéricos y estables** o **verificados en un dispositivo**. Lo que no se ha comprobado se marca **`(sin verificar)`**.
- Nunca incluyas datos personales (nombres, mensajes, correos, ubicaciones) ni credenciales. Si verificas en un teléfono real, anota solo la estructura de la interfaz.
- Texto corto: la ficha se lee entera con `phone know`, cada línea cuesta tokens.
- Para añadir una: crea `knowledge/apps/<nombre>/APP.md`, comprueba con `phone know <nombre>` y que `phone know` la lista; si cambias paquetes o alias en caliente, no hace falta reiniciar nada (el índice se carga una vez por comando).

## Estado de verificación de las fichas iniciales
Verificadas parcialmente en Huawei ANE-LX3 (Android 9, español): `android-settings`, `huawei-launcher`, `calculator`; mínimamente `chrome` (contador de pestañas, menú) y `play-store` (avisos descartables). Escritas desde conocimiento general, todo sin verificar: `gmail`, `whatsapp`, `telegram`, `x`, `youtube`, `spotify`, `google-maps`.

Formato inspirado en las fichas `APP.md` de [Mika](https://github.com/wangzan101/Mika) (Apache-2.0); el texto es propio. Ver `NOTICE` en la raíz del proyecto.

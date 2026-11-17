---
name: huawei-launcher
display: Pantalla de inicio de Huawei (EMUI)
packages: ["com.huawei.android.launcher"]
aliases: ["Launcher", "Lanzador", "Pantalla de inicio", "Huawei Home"]
open: Inicio
description: Lanzador de EMUI/Huawei con páginas, dock, widgets y búsqueda
---
## Qué es
El lanzador (pantalla de inicio) de los Huawei/Honor con EMUI, paquete `com.huawei.android.launcher`. `ensure_home` y `home` lo dejan aquí; `state` indica `HOME` cuando es el paquete en foco.

## Interfaz
Verificado en Huawei ANE-LX3 (Android 9, español):
- Un elemento `Pantalla N de M` (p. ej. `Pantalla 1 de 3 @540,1030`) es el indicador de página: dice en qué página del lanzador se está. Cambia al deslizar.
- Parte superior: widget de reloj/clima (`Clima`, fecha y hora, `Agregar ciudad`) y el widget `Buscar` con `Búsqueda de Google` y `Búsqueda por voz`.
- Cuadrícula de iconos con su nombre como etiqueta (`Ajustes`, `Chrome`, `Play Store`, `Galería`…); un icono con avisos pendientes lo dice en la etiqueta (`Mensajes; 26 notificaciones`). Fila inferior: `Aplicaciones del dock`.
- `look` marca `NOTA: pantalla desplazable` siempre: es el paginado horizontal, no hay scroll vertical.

## Tareas frecuentes
- Volver al inicio: `phone N ensure_home`.
- Abrir una app por su icono: `phone N look`, `phone N tap @eN` (o `tap_text "Chrome"`); mejor `phone N launch <nombre|paquete>`.
- Cambiar de página: `phone N swipe 900 1500 150 1500 -q` (siguiente) y `phone N swipe 150 1500 900 1500 -q` (anterior); verificar con `phone N look --grep "Pantalla"` (lee `Pantalla 2 de 3`). Coordenadas verificadas en 1080x2340.
- Si la app no está en la página actual, recorrer las páginas hasta `M` y mirar; o usar `launch`.

## Fenómenos conocidos
- Paginado: desde la página 1, deslizar a la derecha NO es "página 0": abre el panel de Google (paquete `com.google.android.googlequicksearchbox`, sin `Pantalla N de M`), que puede mostrar contenido personalizado. Volver con `home`.
- Si `look` avisa `volcado de com.google...quicksearchbox, foco en com.huawei.android.launcher`, el panel de Google estaba a medio animar: esperar 2 s y repetir.
- Deslizar hacia abajo en un hueco de la pantalla de inicio abre la búsqueda global de EMUI, que sugiere contactos y apps recientes (sin verificar a propósito: es contenido privado). No abrirla salvo orden del usuario.
- Mantener pulsado un icono o hueco abre el modo edición (widgets, fondos, iconos) (sin verificar): salir con `back`.

## Riesgos
- Nivel B: la búsqueda global (muestra contactos), arrastrar un icono a "Desinstalar"/"Eliminar", editar la pantalla (borra widgets o carpetas).
- Mantener pulsado + arrastrar es destructivo: no hacerlo.

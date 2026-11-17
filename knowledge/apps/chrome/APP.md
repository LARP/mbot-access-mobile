---
name: chrome
display: Chrome
packages: ["com.android.chrome"]
aliases: ["Chrome", "Google Chrome"]
open: Chrome
description: Navegador web de Google
---
## Qué es
Navegador Chrome (`com.android.chrome`). Para abrir páginas, buscar y gestionar pestañas. Una URL directa es más fiable que teclear: `phone N open https://...`.

## Interfaz
- Parte superior: barra de direcciones (campo con la URL o `Buscar o escribir URL`, texto exacto sin verificar en cada versión) y el contador de pestañas, que sale como `N pestañas abiertas; presiona para cambiar de pestaña` (verificado en Android 9, español).
- `Más opciones` (menú de tres puntos) (verificado).
- El contenido de la página son nodos web; muchas páginas tienen árbol pobre (usar `look --full`, `scroll`, y `shot` si no hay texto).
- Pantalla de nueva pestaña: accesos directos y noticias personalizadas (sin verificar): su contenido puede ser privado, no leerlo si la tarea no lo pide.

## Tareas frecuentes
- Abrir una web: `phone N open https://ejemplo.com` y `phone N look`.
- Buscar: `phone N tap_text "Buscar o escribir URL"` (o el id `@eN` de la barra), `phone N text "consulta"`, `phone N key ENTER` (en modo app ENTER solo Android 11+; si falla, tocar la sugerencia o el botón de buscar).
- Cambiar de pestaña: tocar el contador de pestañas y elegir una miniatura.
- Cerrar un banner de cookies: preferir `Rechazar`/`Solo esenciales`.

## Fenómenos conocidos
- Primera apertura: pantallas de bienvenida/inicio de sesión de Chrome (`Aceptar y continuar`, `Sin cuenta`) (sin verificar): no aceptar términos ni iniciar sesión sin el usuario.
- Diálogos de permiso del sitio (ubicación, notificaciones) aparecen como diálogo de Chrome, no del sistema (sin verificar).
- Pestañas restauradas e incógnito pueden abrir páginas privadas anteriores: no leerlas.

## Riesgos
- Nivel B: iniciar sesión, autocompletar contraseñas o tarjetas (nunca), aceptar cookies/términos, permisos de sitio, descargas, enviar formularios, borrar historial/datos de navegación, sincronización.
- STOP: avisos de "sitio peligroso"/suplantación o de seguridad.

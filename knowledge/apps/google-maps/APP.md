---
name: google-maps
display: Google Maps
packages: ["com.google.android.apps.maps"]
aliases: ["Maps", "Google Maps", "Mapas de Google"]
open: Maps
description: Mapas, rutas y navegación
---
## Qué es
Google Maps (`com.google.android.apps.maps`). Buscar lugares, rutas y navegación. Los lugares guardados, el historial de ubicaciones y la ubicación actual son datos muy privados. (Ficha escrita sin abrir la app: todo "sin verificar".)

## Interfaz
- Mapa a pantalla completa (casi sin accesibilidad: `look` ve pocos elementos), barra `Buscar aquí` arriba, botones de capas y `Mi ubicación`, y hoja inferior con lugares.
- Ficha de lugar: `Cómo llegar`, `Iniciar`, `Guardar`, `Compartir`, horarios y reseñas.
- Pestañas inferiores: Explorar, Ir, Guardado, Contribuir, Novedades (varían).

## Tareas frecuentes
- Ver cómo llegar a un sitio: `phone N open "geo:0,0?q=<texto>"` o `https://maps.google.com/?q=<texto>`, y `look`.
- Buscar: tocar `Buscar aquí`, `text "..."`, tocar el resultado, `Cómo llegar`.

## Fenómenos conocidos
- Pide permiso de ubicación: nivel B (`No permitir` si la tarea no lo necesita).
- El mapa en sí no es legible: usar `shot` para ver el mapa y `look` para los controles.
- Si hay una navegación activa, no tocar el mapa (arrastres cambian la ruta).

## Riesgos
- Nivel B: permiso de ubicación, compartir ubicación en tiempo real, guardar o borrar lugares, iniciar navegación, escribir reseñas o fotos, gestionar el historial de ubicaciones.
- Nunca describir ubicaciones de casa/trabajo guardadas sin que lo pida el usuario.

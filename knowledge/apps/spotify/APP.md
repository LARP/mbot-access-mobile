---
name: spotify
display: Spotify
packages: ["com.spotify.music"]
aliases: ["Spotify"]
open: Spotify
description: Reproductor de música y podcasts
---
## Qué es
Spotify (`com.spotify.music`). Escuchar música y podcasts. (Ficha escrita sin abrir la app: todo "sin verificar".)

## Interfaz
- Pestañas inferiores: Inicio, Buscar, Tu biblioteca (y Premium); barra de reproducción mínima encima con título y play/pausa.
- Reproductor completo: anterior, play/pausa, siguiente, aleatorio, repetir, "me gusta".
- Parte de los elementos son vistas propias con etiquetas accesibles solo por icono (usar `icons`/`shot` si faltan).

## Tareas frecuentes
- Pausar/reanudar: `phone N launch spotify`, `phone N look`, tocar el botón play/pausa (por `@eN`).
- Buscar música: pestaña Buscar, `text "..."`, tocar resultado.

## Fenómenos conocidos
- Cuentas gratuitas: anuncios de audio, saltos limitados y avisos de Premium: `Ahora no`/cerrar (nivel A).
- La barra de reproducción cambia de etiqueta con la canción: no usarla como `expect` de un macro.

## Riesgos
- Nivel B: iniciar sesión, Premium y pagos, compartir, borrar listas, seguir/dejar de seguir, cambiar de cuenta, cualquier cosa que se publique.
- El historial de escucha es personal.

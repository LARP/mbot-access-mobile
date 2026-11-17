---
name: youtube
display: YouTube
packages: ["com.google.android.youtube", "com.google.android.apps.youtube.music"]
aliases: ["YouTube"]
open: YouTube
description: Reproductor y buscador de vídeos
---
## Qué es
YouTube (`com.google.android.youtube`). Ver y buscar vídeos. (Ficha escrita sin abrir la app: todo "sin verificar".)

## Interfaz
- Inicio con recomendaciones basadas en el historial (privadas); pestañas inferiores (Inicio, Shorts, Suscripciones, Tú); lupa y campana arriba.
- Reproductor con controles que se ocultan: tocar el vídeo los muestra; `look` ve pocos elementos si el vídeo ocupa la pantalla.
- Anuncios con `Omitir anuncio` tras unos segundos.

## Tareas frecuentes
- Buscar y abrir un vídeo: `phone N open "https://www.youtube.com/results?search_query=<texto>"` (puede salir `Abrir con`: elegir YouTube "Solo una vez") y tocar el resultado.
- Pausar/reanudar: tocar el vídeo y el botón de pausa.

## Fenómenos conocidos
- `look` en reproducción puede tardar o salir vacío (animación): esperar y repetir.
- Anuncios: `Omitir anuncio` (nivel A); si no hay botón, esperar.
- Primera vez: ofertas de YouTube Premium: `Ahora no`.

## Riesgos
- Nivel B: iniciar sesión, suscribirse a canales o a Premium (pago), comentar, subir, borrar historial, cambiar privacidad.
- El historial y las recomendaciones son personales: no describirlos sin que lo pida el usuario.

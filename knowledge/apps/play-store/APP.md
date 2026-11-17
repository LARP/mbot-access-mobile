---
name: play-store
display: Google Play Store
packages: ["com.android.vending"]
aliases: ["Play Store", "Google Play", "PlayStore"]
open: Play Store
description: Tienda de apps de Google para buscar, instalar y actualizar aplicaciones
---
## Qué es
Tienda oficial (`com.android.vending`) para instalar, actualizar y desinstalar apps.

## Interfaz
- Inicio con carrusel y pestañas (Juegos, Apps, Libros… varía con la región) (sin verificar la disposición exacta).
- Abre con frecuencia banners/diálogos descartables (p. ej. "Elegir categorías de apps"): `look` los marca `OBSTACULO[A]` (verificado en Android 9).
- Barra de búsqueda arriba; icono de perfil arriba a la derecha (cuenta, ajustes, métodos de pago) (sin verificar).
- Ficha de una app: botones `Instalar`, `Actualizar`, `Desinstalar`, `Abrir` y valoración (sin verificar).

## Tareas frecuentes
- Ver la ficha de una app sin instalar: `phone N open "market://details?id=<paquete>"` (puede salir `Abrir con`), luego `phone N look`.
- Buscar: tocar la barra de búsqueda, `phone N text "nombre"`, tocar la sugerencia.
- Comprobar actualizaciones: Perfil > Administrar apps y dispositivo (sin verificar). Solo leer.

## Fenómenos conocidos
- El botón `Instalar` puede mostrar el precio (`1,99 €`): eso es una compra.
- Los resultados patrocinados (`Anuncio`) se mezclan con los reales: comprobar el nombre del desarrollador antes de actuar.
- La primera vez puede pedir verificar la cuenta o aceptar términos.

## Riesgos
- Nivel B: instalar, actualizar, desinstalar, cualquier precio, suscripciones, métodos de pago, canjear códigos, Play Protect.
- STOP: pantallas de pago/verificación de identidad.
- Nada de lo anterior sin orden expresa del usuario (app concreta y, si hay precio, el total).

---
name: android-settings
display: Ajustes de Android
packages: ["com.android.settings"]
aliases: ["Ajustes", "Configuración", "Configuracion", "Settings", "Android Settings"]
open: Ajustes
description: App de sistema para configurar Wi-Fi, pantalla, batería, aplicaciones y cuentas
---
## Qué es
Ajustes del sistema (`com.android.settings`). Sirve para consultar y cambiar la configuración del teléfono. Casi todo lo que se toca aquí es de nivel B (cambiar ajustes del sistema o de cuenta): leer es libre, cambiar pide permiso del usuario.

## Interfaz
Verificado en Huawei ANE-LX3 (EMUI, Android 9, español):
- Raíz: campo `[campo] Búsqueda` arriba y una lista de filas `Título Subtítulo…`: ID de Huawei, Conexiones inalámbricas y redes, Conexión del dispositivo, Pantalla principal y fondo de pantalla, Pantalla (Brillo, Descanso visual…), Sonidos, Notificaciones, Aplicaciones, Batería, Memoria, Balance digital, Seguridad y privacidad, Asistencia inteligente, Usuarios y cuentas, Google, Sistema. Las últimas filas están tras scroll.
- Cada fila es un solo elemento clicable con título y subtítulo juntos (`Pantalla Brillo, Descanso visual…(+36)`): si el título solo coincide con otra fila, añadir la primera palabra del subtítulo en `tap_text`.
- Las subpantallas empiezan con `Desplazarse hacia arriba` (botón atrás de la barra) y el título centrado.
- En otros fabricantes (Samsung, Xiaomi, Pixel) los títulos y el orden cambian (sin verificar).

## Tareas frecuentes
- Abrir Ajustes limpio: `phone N launch com.android.settings` (macro `abrir_ajustes`).
- Ver Pantalla > Brillo: `phone N run brillo` (el macro `ir_pantalla` deja la pantalla de Pantalla).
- Cuenta Google: `phone N run cuenta_google` (muestra el email: dato privado, no repetirlo si no hace falta).
- Buscar un ajuste: `phone N tap_text "Búsqueda"`, `phone N text "brillo"`, `phone N look`.
- Estado de red o batería sin tocar nada: `phone N info`.

## Fenómenos conocidos
- `look` marca `PANTALLA DE LOGIN` en la raíz por la fila "Iniciar sesión en el ID de Huawei": es un falso positivo, ignorarlo.
- `tap_text "Pantalla"` pulsa "Pantalla principal y fondo de pantalla" (empieza igual): usar `"Pantalla Brillo"` o el id `@eN`.
- Al reabrir, Ajustes puede quedarse en la última subpantalla visitada: con adb el macro hace `am force-stop`; en modo app (no existe) usar `back` hasta la raíz.
- Los interruptores salen como `[x]`/`[ ]` en `look`.

## Riesgos
- Nivel B: Wi-Fi/Bluetooth/modo avión (cortan la conexión de la app o de adb), borrar datos o caché de apps, desinstalar, restablecer, cuentas (añadir/quitar), permisos de apps, seguridad y bloqueo de pantalla, opciones de desarrollador, accesibilidad (la propia app Mbot depende de ella).
- STOP: cualquier "Restablecer"/"Borrar todo" o avisos de seguridad.
- Contraseñas de Wi-Fi y PIN: las escribe el usuario.

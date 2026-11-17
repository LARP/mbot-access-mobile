---
name: calculator
display: Calculadora
packages: ["com.android.calculator2", "com.google.android.calculator", "com.huawei.calculator", "com.sec.android.app.popupcalculator", "com.miui.calculator"]
aliases: ["Calculadora", "Calculator", "Calc"]
open: Calculadora
description: Calculadora básica del sistema
---
## Qué es
Calculadora del sistema. El paquete depende del fabricante; en el Huawei de pruebas (Android 9) es `com.android.calculator2` (verificado). Es la app más segura para probar la herramienta.

## Interfaz
Verificado en Huawei ANE-LX3 (español):
- Los botones salen como elementos clicables con su etiqueta accesible: dígitos `0`..`9`, `,` (decimal), `%`, `más`, `menos`, `multiplicación`, `división`, `igual a`, `borrar`, `eliminar`, `mc`, `m+`, `m-`, `mr`.
- `borrar` y `eliminar` son teclas de la calculadora (borrar todo y retroceso), no acciones destructivas reales, pero el aviso de riesgo (`AVISO accion de riesgo alto`) puede dispararse por su nombre: ignorarlo aquí.
- El visor es un `[campo]` arriba con la expresión o el resultado.
- En otros fabricantes los nombres cambian (`C`, `÷`, `×`, `=`) (sin verificar).

## Tareas frecuentes
- Calcular 7+8: `phone N launch calculator`, `phone N tap_text 7`, `tap_text más`, `tap_text 8`, `tap_text "igual a"`, `phone N look`.
- Mejor con ids en modo app: `phone app tap @eN` para cada tecla del último `look`.
- Limpiar el visor: `tap_text borrar`.

## Fenómenos conocidos
- Cada pulsación devuelve la pantalla; con `-q` se ahorran tokens al encadenar teclas ya conocidas.
- `tap_text` prefiere la etiqueta exacta (`7` no confunde con otras teclas); si hay duda usar `@eN`.

## Riesgos
- Ninguno relevante (nivel A). No hay red ni datos personales.

# Skill `mbot-phone` para Claude Code

`mbot-phone/SKILL.md` enseña a un agente a operar el teléfono del usuario con la herramienta `phone` de este proyecto (adb o app Mbot). Se activa sola cuando el usuario pide algo en su móvil (palabras clave en español e inglés en la `description`).

## Requisitos
- Este proyecto (mbot-access-mobile) en el PC, con Python 3 (`phone check` verifica adb/scrcpy/Pillow).
- Un teléfono conectado: por adb (`phone devices`) o con la app Mbot vinculada (`phone pair`).

## Instalar
1. Define la variable de entorno **`MBOT_HOME`** con la carpeta del proyecto (la que contiene `phone_agent.py` y `phone.bat`):
   - Windows (PowerShell, permanente): `setx MBOT_HOME "D:\App\Mobile_Agent"` y abre una terminal nueva.
   - Linux/macOS: `export MBOT_HOME=/ruta/a/Mobile_Agent` (añádelo a `~/.bashrc` o `~/.zshrc`).
2. Copia la carpeta `mbot-phone` (la que tiene `SKILL.md`) a un directorio de skills de Claude Code:
   - Para todos los proyectos: `~/.claude/skills/mbot-phone/` (Windows: `%USERPROFILE%\.claude\skills\mbot-phone\`).
   - Solo para un proyecto: `<proyecto>/.claude/skills/mbot-phone/`.
   ```
   # Windows (cmd)
   xcopy /E /I "%MBOT_HOME%\skill\mbot-phone" "%USERPROFILE%\.claude\skills\mbot-phone"
   # Linux/macOS
   mkdir -p ~/.claude/skills && cp -r "$MBOT_HOME/skill/mbot-phone" ~/.claude/skills/
   ```
3. Reinicia Claude Code (o abre una sesión nueva) y pide, por ejemplo: "mira qué hay en la pantalla de mi móvil".

La skill llama a `phone` así: `python "$MBOT_HOME/phone_agent.py" ...` (Windows: `"%MBOT_HOME%\phone.bat" ...`). Si `MBOT_HOME` falta, la skill pregunta por la ruta.

## Alternativa: servidor MCP
En lugar de (o además de) la skill se puede registrar el servidor MCP del proyecto, que expone las mismas operaciones como herramientas `phone_*`:
```
claude mcp add mbot-phone -- python "%MBOT_HOME%\mcp_server.py"
```
La skill y el MCP se complementan: la skill da el criterio (bucle, riesgos, privacidad); el MCP evita escribir comandos de consola.

## Otros agentes
Cualquier agente que no use skills puede empezar por `phone help --agent` (manual compacto) y [AGENT_PROTOCOL.md](../AGENT_PROTOCOL.md).

## Mantenimiento
`SKILL.md` es corto a propósito: el detalle vivo está en `phone help --agent`, `phone N caps` y las fichas `knowledge/apps/*/APP.md`. Si se añaden comandos, solo hay que tocar `phone_help.SPECS`.

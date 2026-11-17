@echo off
rem Vista en vivo. Uso: phone_live.bat [dispositivo]  (numero o serial; sin argumento: primer dispositivo)
python "%~dp0phone_agent.py" %* live

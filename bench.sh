#!/bin/bash
# Benchmark A/B: T1 (email Google) y T2 (abrir Brillo) en modos cold / phase2 / macro. Uso: bench.sh [N] [serial]
N=${1:-5}; S=${2:-$(python phone_agent.py devices | head -1 | awk '{print $2}')}
P="python phone_agent.py --serial $S"
reset(){ $P ensure_home >/dev/null; adb -s $S shell am force-stop com.android.settings; }
for ((i=1;i<=N;i++)); do
 export PHONE_RUN=$i
 # ---- T2
 export PHONE_TASK=T2
 export PHONE_MODE=b-cold; reset
 $P launch com.android.settings -q >/dev/null; sleep 2; $P look >/dev/null; $P tap_text Pantalla -q >/dev/null; sleep 2; $P look >/dev/null; $P tap_text Brillo -q >/dev/null; sleep 1; $P look >/dev/null
 export PHONE_MODE=b-phase2; reset
 $P launch com.android.settings -q >/dev/null; sleep 2; $P tap_text Pantalla --grep Brillo >/dev/null; $P tap_text Brillo --grep Brillo >/dev/null
 export PHONE_MODE=b-macro; reset
 $P run brillo >/dev/null
 # ---- T1
 export PHONE_TASK=T1
 export PHONE_MODE=b-cold; reset
 $P launch com.android.settings -q >/dev/null; sleep 2; $P look >/dev/null
 for k in 1 2 3; do $P scroll down -q >/dev/null; sleep 1; done; $P look >/dev/null
 $P tap_text "Usuarios y cuentas" -q >/dev/null; sleep 2; $P look >/dev/null; $P tap_text Google -q >/dev/null; sleep 2; $P look >/dev/null
 export PHONE_MODE=b-phase2; reset
 $P launch com.android.settings -q >/dev/null; $P scroll_to "Usuarios y cuentas" --grep cuentas >/dev/null; $P tap_text "Usuarios y cuentas" --grep Google >/dev/null; $P tap_text Google --grep @ >/dev/null
 export PHONE_MODE=b-macro; reset
 $P run cuenta_google >/dev/null
done
$P ensure_home >/dev/null

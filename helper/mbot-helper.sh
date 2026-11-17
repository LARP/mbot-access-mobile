#!/system/bin/sh
# Lanza (o para) el asistente de Mbot Agent con identidad shell. Lo sube y ejecuta `phone N advanced start`.
PKG=com.mbot.access.mobile
DIR=/data/local/tmp
PIDF=$DIR/mbot-helper.pid
LOG=$DIR/mbot-helper.log
stop_old() {
  if [ -f $PIDF ]; then
    P=$(cat $PIDF)
    kill $P 2>/dev/null
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
      kill -0 $P 2>/dev/null || break
      sleep 0.1
    done
    kill -9 $P 2>/dev/null
    rm -f $PIDF
  fi
}
if [ "$1" = "stop" ]; then stop_old; echo "ok parado"; exit 0; fi
APK=$(pm path $PKG | head -n 1 | sed 's/^package://')
if [ -z "$APK" ]; then echo "ERR Mbot Agent no esta instalada"; exit 1; fi
APPUID=$(pm list packages -U $PKG | grep "^package:$PKG uid:" | head -n 1 | sed 's/.*uid://')
if [ -z "$APPUID" ]; then echo "ERR no se pudo obtener la UID de $PKG"; exit 1; fi
if [ "$1" = "selftest" ]; then
  # comprueba que el asistente rechaza llamadas de una UID que no es la de la app (aqui: la shell)
  app_process -Djava.class.path=$APK /system/bin --nice-name=mbot_helper_test com.mbot.access.mobile.helper.Main --uid=$APPUID --pkg=$PKG --selftest
  exit $?
fi
stop_old
rm -f $LOG
nohup setsid app_process -Djava.class.path=$APK /system/bin --nice-name=mbot_helper com.mbot.access.mobile.helper.Main --uid=$APPUID --pkg=$PKG > $LOG 2>&1 < /dev/null &
for i in $(seq 1 100); do
  if grep -q "^READY" $LOG 2>/dev/null; then
    PID=$(sed -n 's/^READY pid=\([0-9]*\).*/\1/p' $LOG | head -n 1)
    echo $PID > $PIDF
    echo "ok asistente en marcha pid=$PID uid_app=$APPUID"
    exit 0
  fi
  if grep -q "^ERR" $LOG 2>/dev/null; then break; fi
  sleep 0.1
done
echo "ERR el asistente no arranco:"; tail -n 5 $LOG
exit 1

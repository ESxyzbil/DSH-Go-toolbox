#!/system/bin/sh
# Plus 键监听（root 进程，由工具箱用 setsid 拉起）
# 设备与键名从 /data/local/tmp/ptt_key.conf 读取，可通过「学习按键」自动生成
CONF=/data/local/tmp/ptt_key.conf
[ -f "$CONF" ] && . "$CONF"
DEV=${DEV:-/dev/input/event0}
NAME=${NAME:-BTN_TRIGGER_HAPPY32}
PKG=io.github.ccch1mneyyy.dsh_app

while true; do
  ev=$(getevent -l -c 1 "$DEV" 2>/dev/null)
  case "$ev" in
    *"$NAME"*DOWN*)
      ( if [ -z "$(pidof $PKG)" ]; then
          am start --windowingMode 5 -n $PKG/.shortcuts.EntryActivity -a $PKG.action.OPEN_APP >/dev/null 2>&1
          sleep 0.8
        fi
        am broadcast -a com.dshfix.ptt.START -p $PKG >/dev/null 2>&1 ) &
      ;;
    *"$NAME"*UP*)
      am broadcast -a com.dshfix.ptt.STOP -p $PKG >/dev/null 2>&1
      ;;
  esac
done

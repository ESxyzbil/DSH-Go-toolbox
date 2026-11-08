#!/system/bin/sh
# Plus 键监听（root 进程，由工具箱用 setsid 拉起；不写任何落盘状态）
PKG=io.github.ccch1mneyyy.dsh_app
DEV=/dev/input/event0
while true; do
  ev=$(getevent -l -c 1 "$DEV" 2>/dev/null)
  case "$ev" in
    *BTN_TRIGGER_HAPPY32*DOWN*)
      ( if [ -z "$(pidof $PKG)" ]; then
          am start --windowingMode 5 -n $PKG/.shortcuts.EntryActivity -a $PKG.action.OPEN_APP >/dev/null 2>&1
          sleep 0.8
        fi
        am broadcast -a com.dshfix.ptt.START -p $PKG >/dev/null 2>&1 ) &
      ;;
    *BTN_TRIGGER_HAPPY32*UP*)
      am broadcast -a com.dshfix.ptt.STOP -p $PKG >/dev/null 2>&1
      ;;
  esac
done

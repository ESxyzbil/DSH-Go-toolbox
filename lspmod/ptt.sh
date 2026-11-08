#!/system/bin/sh
# Plus 键按住说话（不新建会话）：
#   1) OPEN_APP 把 App 唤到前台（kind=null，不会新建会话）
#   2) 广播 START 让模块点网页里的麦克风
#   3) 监听 BTN_TRIGGER_HAPPY32 抬起，广播 STOP 停止并发送
am start -n io.github.ccch1mneyyy.dsh_app/.shortcuts.EntryActivity \
  -a io.github.ccch1mneyyy.dsh_app.action.OPEN_APP >/dev/null 2>&1

sleep 0.8
am broadcast -a com.dshfix.ptt.START -p io.github.ccch1mneyyy.dsh_app >/dev/null 2>&1
sleep 1.2
am broadcast -a com.dshfix.ptt.START -p io.github.ccch1mneyyy.dsh_app >/dev/null 2>&1

rm -f /sdcard/ptt_ge.txt
getevent -l /dev/input/event0 > /sdcard/ptt_ge.txt 2>&1 &
GPID=$!

i=0
while [ $i -lt 400 ]; do
  if grep -q "BTN_TRIGGER_HAPPY32.*UP" /sdcard/ptt_ge.txt 2>/dev/null; then
    break
  fi
  sleep 0.05
  i=$((i+1))
done

kill $GPID 2>/dev/null
am broadcast -a com.dshfix.ptt.STOP -p io.github.ccch1mneyyy.dsh_app >/dev/null 2>&1

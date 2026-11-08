package com.dshfix.modaudio;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 只干一件事：按 content-desc 找到 DSH Go 原生界面的按钮并点击。
 * 不读内容、不留数据，找到就点。
 */
public class ClickService extends AccessibilityService {

    private static final String TAG = "DSH-MOD";
    private static final String PKG = "io.github.ccch1mneyyy.dsh_app";
    private static volatile ClickService sInst;

    private BroadcastReceiver rx;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInst = this;
        rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && !"dshfix-7c1f".equals(intent.getStringExtra("t"))) return;
                if (intent != null && "com.dshfix.ptt.STARTED".equals(intent.getAction())) {
                    OverlayUI.get(context).resetDismiss();
                    return;
                }
                if (intent != null && "com.dshfix.ptt.STATE".equals(intent.getAction())) {
                    if (intent.getBooleanExtra("hide", false)) {
                        OverlayUI.get(context).hide();
                    } else {
                        OverlayUI.get(context).update(
                                intent.getStringExtra("input"),
                                intent.getStringExtra("msgs"),
                                intent.getStringExtra("sent"));
                    }
                    return;
                }
                if (intent != null && "com.dshfix.ptt.NEED_FOREGROUND".equals(intent.getAction())) {
                    Log.i(TAG, "a11y: bring app to front (float)");
                    final String cmd = "am start --windowingMode 5 -n "
                            + PKG + "/.shortcuts.EntryActivity -a " + PKG + ".action.OPEN_APP";
                    new Thread(new Runnable() {
                        public void run() { PttService.runRoot(cmd); }
                    }).start();
                    return;
                }
                String target = intent == null ? "" : intent.getStringExtra("target");
                Log.i(TAG, "a11y click request: " + target);
                click(target);
            }
        };
        IntentFilter f = new IntentFilter("com.dshfix.ptt.NATIVE_CLICK");
        f.addAction("com.dshfix.ptt.NEED_FOREGROUND");
        f.addAction("com.dshfix.ptt.STATE");
        f.addAction("com.dshfix.ptt.STARTED");
                registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        Log.i(TAG, "a11y service ready");
    }

    /** target=mic 找语音键；target=stop 停录；target=send 发送。找不到会重试 */
    private void click(String target) { click(target, 0); }

    private void click(final String target, final int attempt) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { Log.i(TAG, "a11y: no root"); return; }
            String[] keys;
            String[] avoid;
            if ("send".equals(target)) {
                keys = new String[]{"发送", "send"};
                avoid = new String[]{"取消"};
            } else if ("stop".equals(target)) {
                keys = new String[]{"停止语音输入", "停止"};
                avoid = new String[]{"取消"};
            } else {
                keys = new String[]{"语音输入", "麦克风"};
                avoid = new String[]{"停止", "取消"};
            }
            AccessibilityNodeInfo hit = find(root, keys, avoid, 0);
            if (hit == null) {
                Log.i(TAG, "a11y: node not found for " + target + " try=" + attempt);
                retry(target, attempt);
                return;
            }
            boolean ok = hit.isClickable()
                    && hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "a11y: click " + target + " -> " + ok + " try=" + attempt);
            if (!ok) { retry(target, attempt); return; }
            if ("send".equals(target)) {
                // 发送完成后把浮窗收进后台
                new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        Log.i(TAG, "a11y: collapse window (HOME)");
                        performGlobalAction(GLOBAL_ACTION_HOME);
                    }
                }, 1200);
            }
        } catch (Throwable t) {
            Log.e(TAG, "a11y click err", t);
        }
    }

    private void retry(final String target, final int attempt) {
        if (attempt >= 8) return;
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            public void run() { click(target, attempt + 1); }
        }, 700);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String[] keys,
                                       String[] avoid, int depth) {
        if (node == null || depth > 40) return null;
        CharSequence d = node.getContentDescription();
        if (d != null) {
            String s = d.toString();
            boolean hit = false;
            for (String k : keys) {
                if (s.contains(k)) { hit = true; break; }
            }
            if (hit && avoid != null) {
                for (String a : avoid) {
                    if (s.contains(a)) { hit = false; break; }
                }
            }
            if (hit) {
                // 必须是可点击的：向上找可点击祖先，找不到就当没命中
                AccessibilityNodeInfo c = node;
                int guard = 0;
                while (c != null && !c.isClickable() && guard++ < 8) {
                    c = c.getParent();
                }
                if (c != null && c.isClickable()) return c;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo r = find(c, keys, avoid, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        sInst = null;
        try { if (rx != null) unregisterReceiver(rx); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    static boolean alive() { return sInst != null; }
}

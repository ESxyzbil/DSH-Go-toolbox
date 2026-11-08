package com.dshfix.modaudio;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * dsh-Go 修复模块 v2.0
 * 1) 补 MODIFY_AUDIO_SETTINGS 权限检查
 * 2) WebView 站点权限放行音频采集
 * 3) VOICE_INPUT 时在网页里点麦克风（JS）
 * 4) 按住说话：键松开后停止录音并发送
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "DSH-MOD";
    private static final String TARGET_PKG = "io.github.ccch1mneyyy.dsh_app";
    private static final String PERM = "android.permission.MODIFY_AUDIO_SETTINGS";
    private static final String RES_AUDIO = "android.webkit.resource.AUDIO_CAPTURE";
    private static final String ACTION_VOICE = "io.github.ccch1mneyyy.dsh_app.action.VOICE_INPUT";
    private static final int PERMISSION_GRANTED = 0;

    private static WeakReference<Object> sWebView = new WeakReference<Object>(null);
    private static volatile boolean sVoicePending = false;
    private static volatile boolean sArmed = false;
    private static volatile int sPttKey = Integer.MIN_VALUE;
    private static volatile boolean sReceiverReady = false;
    private static volatile boolean sNativeClicked = false;
    private static final java.util.List<WeakReference<Object>> sChannels =
            java.util.Collections.synchronizedList(
                    new java.util.ArrayList<WeakReference<Object>>());
    private static volatile long sDownAt = 0L;
    private static volatile boolean sVoiceStarted = false;
    private static Runnable sStartRun = null;

    private static volatile long sSysDownAt = 0L;
    private static volatile boolean sSysStarted = false;
    private static Runnable sSysRun = null;

    private static WeakReference<Activity> sAct = new WeakReference<Activity>(null);
    private static View sOverlayRoot;
    private static TextView sOverlayText;

    private static void ensureOverlay(Activity act) {
        if (sOverlayRoot != null || act == null) return;
        try {
            FrameLayout root = new FrameLayout(act);
            root.setClickable(false);
            root.setFocusable(false);

            LinearLayout pill = new LinearLayout(act);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xCC1A1A1A);
            g.setCornerRadius(120f);
            pill.setBackground(g);
            pill.setPadding(52, 22, 52, 22);

            TextView dot = new TextView(act);
            dot.setText("●");
            dot.setTextColor(0xFFFF5252);
            dot.setTextSize(11);
            TextView tv = new TextView(act);
            tv.setText("聆听中…");
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(15);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = 18;
            tv.setLayoutParams(tp);
            pill.addView(dot);
            pill.addView(tv);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = 210;
            root.addView(pill, lp);
            root.setVisibility(View.GONE);

            act.addContentView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sOverlayRoot = root;
            sOverlayText = tv;
            Log.i(TAG, "overlay added");
        } catch (Throwable t) {
            Log.e(TAG, "overlay fail", t);
        }
    }

    private static void setOverlay(final boolean show, final String text, final long autoHideMs) {
        Looper main = Looper.getMainLooper();
        if (main == null) return;
        new Handler(main).post(new Runnable() {
            public void run() {
                try {
                    if (sOverlayRoot == null) ensureOverlay(sAct.get());
                    if (sOverlayRoot == null) return;
                    if (text != null && sOverlayText != null) sOverlayText.setText(text);
                    sOverlayRoot.setVisibility(show ? View.VISIBLE : View.GONE);
                    if (autoHideMs > 0) {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            public void run() {
                                if (sOverlayRoot != null) sOverlayRoot.setVisibility(View.GONE);
                            }
                        }, autoHideMs);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "overlay show fail", t);
                }
            }
        });
    }

    private static void hookSystemServer(XC_LoadPackage.LoadPackageParam lp) {
        Log.i(TAG, "system_server mode");
        XposedBridge.log("[dsh-mod] system_server mode, classLoader=" + lp.classLoader);
        final XC_MethodHook kh = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    KeyEvent ev = (KeyEvent) param.args[0];
                    if (ev == null) return;
                    if (ev.getKeyCode() != 0) return; // 只认 KEYCODE_UNKNOWN
                    int action = ev.getAction();
                    long now = android.os.SystemClock.uptimeMillis();
                    Log.i(TAG, "sys key action=" + action);
                    XposedBridge.log("[dsh-mod] sys key action=" + action);
                    if (action == KeyEvent.ACTION_DOWN) {
                        if (sSysDownAt == 0) {
                            sSysDownAt = now;
                            scheduleSysStart();
                        }
                    } else if (action == KeyEvent.ACTION_UP) {
                        if (sSysDownAt != 0) {
                            long dur = now - sSysDownAt;
                            sSysDownAt = 0;
                            cancelSysStart();
                            Log.i(TAG, "sys UP dur=" + dur + " started=" + sSysStarted);
                            if (sSysStarted) {
                                sSysStarted = false;
                                sysStop();
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "sys key err", t);
                }
            }
        };
        String[] methods = {"interceptKeyBeforeQueueing", "interceptKeyBeforeDispatching"};
        for (String m : methods) {
            try {
                XposedHelpers.findAndHookMethod("com.android.server.wm.PhoneWindowManager",
                        lp.classLoader, m, KeyEvent.class, int.class, kh);
                Log.i(TAG, "system key hook ok: " + m);
                XposedBridge.log("[dsh-mod] system key hook ok: " + m);
            } catch (Throwable t) {
                Log.e(TAG, "system key hook failed: " + m, t);
                XposedBridge.log("[dsh-mod] system key hook failed: " + m + " -> " + t);
            }
        }
    }

    private static Context sysCtx() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("currentActivityThread").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(th);
        } catch (Throwable t) {
            Log.e(TAG, "sysCtx failed", t);
            return null;
        }
    }

    private static void scheduleSysStart() {
        Looper main = Looper.getMainLooper();
        if (main == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (sSysDownAt == 0) return;
                try {
                    Context c = sysCtx();
                    Intent i = new Intent(ACTION_VOICE);
                    i.setClassName(TARGET_PKG, "io.github.ccch1mneyyy.dsh_app.shortcuts.EntryActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Class<?> uh = Class.forName("android.os.UserHandle");
                    Object cur = uh.getField("CURRENT").get(null);
                    c.getClass().getMethod("startActivityAsUser", Intent.class, uh).invoke(c, i, cur);
                    sSysStarted = true;
                    Log.i(TAG, "sysStart ok");
                } catch (Throwable t) {
                    Log.e(TAG, "sysStart failed", t);
                }
            }
        };
        sSysRun = r;
        new Handler(main).postDelayed(r, 350);
    }

    private static void cancelSysStart() {
        Runnable r = sSysRun;
        sSysRun = null;
        if (r == null) return;
        Looper main = Looper.getMainLooper();
        if (main != null) new Handler(main).removeCallbacks(r);
    }

    private static void sysStop() {
        try {
            Context c = sysCtx();
            Intent i = new Intent("com.dshfix.ptt.STOP");
            i.setPackage(TARGET_PKG);
            c.sendBroadcast(i);
            Log.i(TAG, "sysStop broadcast");
        } catch (Throwable t) {
            Log.e(TAG, "sysStop failed", t);
        }
    }

    private static final String JS_CLICK =
        "(function(){try{"
      + "var els=[].slice.call(document.querySelectorAll('button,[role=\"button\"],a'));"
      + "var best=null;"
      + "for(var i=0;i<els.length;i++){var e=els[i];"
      + "var s=((e.getAttribute&&(e.getAttribute('aria-label')||e.getAttribute('title')))||'')+'';"
      + "if(s&&s.length<30&&/麦克风|语音|录音|voice|microphone/i.test(s)){best=e;break;}}"
      + "if(!best){var t=[].slice.call(document.querySelectorAll('button,[role=\"button\"]'));"
      + "for(var j=0;j<t.length;j++){var tx=(t[j].textContent||'').trim();"
      + "if(/^(语音输入|麦克风|语音)$/.test(tx)){best=t[j];break;}}}"
      + "if(best){best.click();"
      + "console.log('DSH-JS clicked:'+((best.getAttribute&&(best.getAttribute('aria-label')||best.textContent))||'').trim().slice(0,20));"
      + "return 'clicked';}"
      + "var lb=[].slice.call(document.querySelectorAll('[aria-label]')).slice(0,15).map(function(e){return e.getAttribute('aria-label');}).join('|');"
      + "console.log('DSH-JS none labels='+lb);return 'none';"
      + "}catch(err){console.log('DSH-JS err '+err);return 'err';}})()";

    // 松开：先停录音，轮询等转写落地再发送（按钮禁用时用 Enter 兜底）
    private static final String JS_STOP_SEND =
        "(function(){try{"
      + "function byLabel(re){var a=[].slice.call(document.querySelectorAll('button,[role=\\\"button\\\"]'));"
      + "for(var i=0;i<a.length;i++){var e=a[i];var s=((e.getAttribute&&(e.getAttribute('aria-label')||e.getAttribute('title')))||'')+'';"
      + "if(re.test(s))return e;}return null;}"
      + "var mic=byLabel(/停止|麦克风|语音|voice|mic/i);"
      + "if(mic){mic.click();console.log('DSH-JS stop');}"
      + "var n=0;var t=setInterval(function(){n++;"
      + "var box=document.querySelector('textarea,[contenteditable=\\\"true\\\"]');"
      + "var txt=box?(((box.value!==undefined?box.value:box.textContent)||'')+'').trim():'';"
      + "var send=byLabel(/发送|send/i);"
      + "if(send&&!send.disabled&&txt.length>0){send.click();console.log('DSH-JS sent btn len='+txt.length);clearInterval(t);return;}"
      + "if(txt.length>0&&n>=2&&box){box.focus();"
      + "var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
      + "box.dispatchEvent(new KeyboardEvent('keydown',o));"
      + "box.dispatchEvent(new KeyboardEvent('keypress',o));"
      + "box.dispatchEvent(new KeyboardEvent('keyup',o));"
      + "console.log('DSH-JS enter len='+txt.length);clearInterval(t);return;}"
      + "if(n>24){console.log('DSH-JS send timeout len='+txt.length);clearInterval(t);}"
      + "},500);"
      + "return 'ok';}catch(err){console.log('DSH-JS err2 '+err);return 'err';}})()";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null) return;
        Log.i(TAG, "injected -> " + lpparam.packageName + " / " + lpparam.processName);
        XposedBridge.log("[dsh-mod] seen pkg=" + lpparam.packageName + " proc=" + lpparam.processName);
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            if ("android".equals(lpparam.packageName)) hookSystemServer(lpparam);
            return;
        }

        XC_MethodHook grant = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) return;
                if (PERM.equals(param.args[0])) param.setResult(Integer.valueOf(PERMISSION_GRANTED));
            }
        };
        hook("android.app.ContextImpl", lpparam, grant, "checkPermission", String.class, int.class, int.class);
        hook("android.app.ContextImpl", lpparam, grant, "checkCallingOrSelfPermission", String.class);
        hook("android.app.ApplicationPackageManager", lpparam, grant, "checkPermission", String.class, String.class);

        try {
            Class<?> prCls = Class.forName("android.webkit.PermissionRequest");
            XposedHelpers.findAndHookMethod("android.webkit.WebChromeClient", lpparam.classLoader,
                    "onPermissionRequest", prCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "onPermissionRequest(base) -> grant");
                            grantAudio(param.args[0]);
                            param.setResult(null);
                        }
                    });
            Log.i(TAG, "base webchrome hook ok");
        } catch (Throwable t) {
            Log.e(TAG, "base webchrome hook failed", t);
        }

        // 尽早抓到 WebView 实例（构造时就记下来，冷启动也能命中）
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebView", lpparam.classLoader,
                    "<init>", android.content.Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sWebView = new WeakReference<Object>(param.thisObject);
                            Log.i(TAG, "webview created");
                        }
                    });
            Log.i(TAG, "webview ctor hook ok");
        } catch (Throwable t) {
            Log.e(TAG, "webview ctor hook failed", t);
        }

        try {
            final Class<?> prCls2 = Class.forName("android.webkit.PermissionRequest");
            XposedHelpers.findAndHookMethod("android.webkit.WebView", lpparam.classLoader,
                    "setWebChromeClient", "android.webkit.WebChromeClient", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sWebView = new WeakReference<Object>(param.thisObject);
                            Object client = param.args[0];
                            if (client == null) return;
                            Class<?> c = client.getClass();
                            try {
                                XposedHelpers.findAndHookMethod(c.getName(), lpparam.classLoader,
                                        "onPermissionRequest", prCls2, new XC_MethodHook() {
                                            @Override
                                            protected void beforeHookedMethod(MethodHookParam p) {
                                                Log.i(TAG, "onPermissionRequest(app) -> grant");
                                                grantAudio(p.args[0]);
                                                p.setResult(null);
                                            }
                                        });
                                Log.i(TAG, "hooked app client " + c.getName());
                            } catch (Throwable ignore) {
                                Log.i(TAG, "app client no override: " + c.getName());
                            }
                        }
                    });
            Log.i(TAG, "setWebChromeClient hook ok");
        } catch (Throwable t) {
            Log.e(TAG, "setWebChromeClient hook failed", t);
        }


        // 给前台服务补上 microphone 类型（清单已打补丁，才敢或这一位）
        try {
            XposedHelpers.findAndHookMethod("android.app.Service", lpparam.classLoader,
                    "startForeground", int.class, android.app.Notification.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object svc = param.thisObject;
                                if (svc == null
                                        || !svc.getClass().getName().contains("DshService")) return;
                                int t = (Integer) param.args[2];
                                if ((t & 0x80) == 0) {
                                    param.args[2] = t | 0x80;  // MICROPHONE
                                    Log.i(TAG, "startForeground type -> 0x"
                                            + Integer.toHexString((Integer) param.args[2]));
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            Log.i(TAG, "fgs type hook ok");
        } catch (Throwable t) {
            Log.e(TAG, "fgs type hook failed", t);
        }

        // 任意 Activity 创建时就登记停止广播，避免漏注册
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                    "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity act = (Activity) param.thisObject;
                                if (act.getClass().getName().endsWith("MainActivity")) {
                                    sAct = new WeakReference<Activity>(act);
                                }
                                registerStopReceiver((Context) param.thisObject);
                            } catch (Throwable t) {
                                Log.e(TAG, "reg in onCreate failed", t);
                            }
                        }
                    });
            Log.i(TAG, "activity onCreate reg hook ok");
        } catch (Throwable t) {
            Log.e(TAG, "activity onCreate reg hook failed", t);
        }

        // Activity 销毁时丢弃旧的覆盖层引用，避免重建后贴不上去
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                    "onDestroy", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object act = param.thisObject;
                                if (act == sAct.get()) {
                                    sOverlayRoot = null;
                                    sOverlayText = null;
                                    sAct = new WeakReference<Activity>(null);
                                    Log.i(TAG, "overlay refs cleared (activity destroyed)");
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "clear overlay failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "onDestroy hook failed", t);
        }

        // 探针：记录 Flutter MethodChannel 的每次调用，找出原生录音的入口
        try {
            final String MC = "io.flutter.plugin.common.MethodChannel";
            Class.forName(MC, false, lpparam.classLoader);
            XC_MethodHook spy = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object ch = param.thisObject;
                        String name = "";
                        try {
                            Object r = ch.getClass().getMethod("getName").invoke(ch);
                            if (r != null) name = (String) r;
                        } catch (Throwable ignored) { }
                        Object method = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        Object args = param.args != null && param.args.length > 1 ? param.args[1] : null;
                        Log.i(TAG, "CHCALL " + name + " #" + method + " args=" + args);
                    } catch (Throwable t) {
                        Log.e(TAG, "spy err", t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(MC, lpparam.classLoader, "invokeMethod",
                    String.class, Object.class, spy);
            XposedHelpers.findAndHookMethod(MC, lpparam.classLoader, "invokeMethod",
                    String.class, Object.class,
                    "io.flutter.plugin.common.MethodChannel$Result", spy);
            // 同时记下 channel 对象本身，便于以后反向调用
            XposedHelpers.findAndHookMethod(MC, lpparam.classLoader, "setMethodCallHandler",
                    "io.flutter.plugin.common.MethodChannel$MethodCallHandler",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] == null) return;
                            try {
                                Object r = param.thisObject.getClass().getMethod("getName")
                                        .invoke(param.thisObject);
                                sChannels.add(new WeakReference<Object>(param.thisObject));
                                Log.i(TAG, "CHREG " + r);
                            } catch (Throwable ignored) { }
                        }
                    });
            Log.i(TAG, "flutter channel spy ok");
        } catch (Throwable t) {
            Log.e(TAG, "flutter channel spy failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod("io.github.ccch1mneyyy.dsh_app.shortcuts.EntryActivity",
                    lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Intent it = ((Activity) param.thisObject).getIntent();
                                String act = it == null ? null : it.getAction();
                                Log.i(TAG, "EntryActivity action=" + act);
                                if (ACTION_VOICE.equals(act)) {
                                    sVoicePending = true;
                                    sArmed = true;
                                    sPttKey = Integer.MIN_VALUE;
                                    registerStopReceiver((Context) param.thisObject);
                                    tryVoice(0);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "entry hook err", t);
                            }
                        }
                    });
            Log.i(TAG, "entry hook ok");
        } catch (Throwable t) {
            Log.e(TAG, "entry hook failed", t);
        }

        // 按键抬起 = 停止并发送
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                    "dispatchKeyEvent", KeyEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                KeyEvent ev = (KeyEvent) param.args[0];
                                if (ev == null) return;
                                int action = ev.getAction();
                                int code = ev.getKeyCode();
                                if (!isPttKey(code)) return;
                                long now = android.os.SystemClock.uptimeMillis();
                                if (action == KeyEvent.ACTION_DOWN) {
                                    if (sDownAt == 0) {
                                        sDownAt = now;
                                        sPttKey = code;
                                        Log.i(TAG, "ptt DOWN code=" + code);
                                        scheduleStart((Activity) param.thisObject);
                                    }
                                } else if (action == KeyEvent.ACTION_UP) {
                                    if (sDownAt != 0 && code == sPttKey) {
                                        long dur = now - sDownAt;
                                        sDownAt = 0;
                                        cancelStart();
                                        Log.i(TAG, "ptt UP dur=" + dur + " recording=" + sVoiceStarted);
                                        if (sVoiceStarted) {
                                            sVoiceStarted = false;
                                            sArmed = false;
                                            runJs(JS_STOP_SEND);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "key hook err", t);
                            }
                        }
                    });
            Log.i(TAG, "key hook ok");
        } catch (Throwable t) {
            Log.e(TAG, "key hook failed", t);
        }
    }

    private static boolean isPttKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_CAMERA:
                return false;
            default:
                return true;
        }
    }

    private static void scheduleStart(final Activity act) {
        Looper main = Looper.getMainLooper();
        if (main == null) return;
        final Handler h = new Handler(main);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (sDownAt == 0) return;
                try {
                    Intent i = new Intent(ACTION_VOICE);
                    i.setClassName(TARGET_PKG, "io.github.ccch1mneyyy.dsh_app.shortcuts.EntryActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(i);
                    sVoiceStarted = true;
                    Log.i(TAG, "ptt start voice");
                } catch (Throwable t) {
                    Log.e(TAG, "startVoice failed", t);
                }
            }
        };
        sStartRun = r;
        h.postDelayed(r, 350);
    }

    private static void cancelStart() {
        Runnable r = sStartRun;
        sStartRun = null;
        if (r == null) return;
        Looper main = Looper.getMainLooper();
        if (main != null) new Handler(main).removeCallbacks(r);
    }

    private static synchronized void registerStopReceiver(Context ctx) {
        if (sReceiverReady || ctx == null) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String a = intent == null ? null : intent.getAction();
                    if ("com.dshfix.ptt.CLOSE".equals(a)) {
                        Log.i(TAG, "receiver: CLOSE -> stop polling");
                        sVoicePending = false;
                        stopPoll();
                        return;
                    }
                    if ("com.dshfix.ptt.START".equals(a)) {
                        Log.i(TAG, "broadcast ptt.START -> click mic");
                        sNativeClicked = false;
                        setOverlay(true, "聆听中…", 0);
                        startPoll(sAct.get());
                        try {
                            Activity a2 = sAct.get();
                            if (a2 != null) {
                                Intent st = new Intent("com.dshfix.ptt.STARTED");
                                st.setPackage("com.dshfix.modaudio");
                                st.putExtra("t", "dshfix-7c1f");
                                a2.sendBroadcast(st);
                            }
                        } catch (Throwable ignored) { }
                        sVoicePending = true;
                        sArmed = true;
                        tryVoice(0);
                        return;
                    }
                    Log.i(TAG, "broadcast ptt.STOP -> stop&send");
                    sLastSent = sLastInput == null ? "" : sLastInput;
                    sVoicePending = false;
                    setOverlay(true, "识别中…", 2600);
                    sArmed = false;
                    if (sWebView.get() == null) {
                        // 原生界面：点「停止语音输入」，再点发送
                        sendNativeClick("stop");
                        Looper m = Looper.getMainLooper();
                        if (m != null) {
                            new Handler(m).postDelayed(new Runnable() {
                                public void run() { sendNativeClick("send"); }
                            }, 2000);
                        }
                    } else {
                        runJs(JS_STOP_SEND);
                    }
                    Looper m2 = Looper.getMainLooper();
                    if (m2 != null) {
                        if (sHideTask != null) new Handler(m2).removeCallbacks(sHideTask);
                        sHideTask = new Runnable() {
                            public void run() { stopPoll(); sendHide(); }
                        };
                        new Handler(m2).postDelayed(sHideTask, 22000);
                    }
                }
            };
            IntentFilter f = new IntentFilter("com.dshfix.ptt.STOP");
            f.addAction("com.dshfix.ptt.START");
            f.addAction("com.dshfix.ptt.CLOSE");
            try {
                // API 33+ 需要显式导出口径，shell 广播才送得进来
                Context.class.getMethod("registerReceiver", BroadcastReceiver.class,
                        IntentFilter.class, int.class).invoke(ctx, r, f, Integer.valueOf(2));
            } catch (Throwable t) {
                ctx.registerReceiver(r, f);
            }
            sReceiverReady = true;
            Log.i(TAG, "stop receiver registered");
        } catch (Throwable t) {
            Log.e(TAG, "registerStopReceiver failed", t);
        }
    }

    /** 原生界面需要可见才能点，让工具箱把 App 拉起来（小窗） */
    private static void requestForeground() {
        try {
            Activity a = sAct.get();
            if (a == null) return;
            Intent i = new Intent("com.dshfix.ptt.NEED_FOREGROUND");
            i.putExtra("t", "dshfix-7c1f");
            i.setPackage("com.dshfix.modaudio");
            a.sendBroadcast(i);
            Log.i(TAG, "request foreground (float)");
        } catch (Throwable t) {
            Log.e(TAG, "request foreground fail", t);
        }
    }

    /** 让工具箱的无障碍服务去点原生界面的按钮 */
    private static void sendNativeClick(String target) {
        try {
            Activity a = sAct.get();
            if (a == null) { Log.i(TAG, "native click: no activity"); return; }
            Intent i = new Intent("com.dshfix.ptt.NATIVE_CLICK");
            i.setPackage("com.dshfix.modaudio");
            i.putExtra("target", target);
            i.putExtra("t", "dshfix-7c1f");
            a.sendBroadcast(i);
            Log.i(TAG, "native click broadcast: " + target);
        } catch (Throwable t) {
            Log.e(TAG, "native click bcast fail", t);
        }
    }

    private static void tryVoice(final int attempt) {
        if (attempt > 12 || !sVoicePending) {
            if (attempt > 12) {
                sVoicePending = false;
                Log.i(TAG, "tryVoice: give up, no webview");
                if (!sNativeClicked) {
                    sNativeClicked = true;
                    sendNativeClick("mic");
                    setOverlay(true, "聆听中…", 0);
                }
            }
            return;
        }
        // 原生界面没有 WebView：第一次就点原生按钮，别等 9 秒
        if (attempt == 0 && sWebView.get() == null) {
            Log.i(TAG, "tryVoice: no webview at once -> native click");
            sNativeClicked = true;
            sendNativeClick("mic");
            requestForeground();
        }
        Looper main = Looper.getMainLooper();
        if (main == null) { Log.i(TAG, "tryVoice: no main looper"); return; }
        new Handler(main).postDelayed(new Runnable() {
            @Override
            public void run() {
                Object wv = sWebView.get();
                if (wv == null) { Log.i(TAG, "tryVoice#" + attempt + " webview null"); tryVoice(attempt + 1); return; }
                if (evalJs(wv, JS_CLICK)) {
                    Log.i(TAG, "tryVoice#" + attempt + " js sent");
                    sVoicePending = false;
                } else {
                    tryVoice(attempt + 1);
                }
            }
        }, attempt == 0 ? 150 : 400);
    }


    interface JsCallback { void onValue(String v); }

    /** 带返回值的 JS（结果是 JSON 字符串） */
    private static void evalJsResult(Object wv, final String js, final JsCallback cb) {
        try {
            Class<?> vc = Class.forName("android.webkit.ValueCallback");
            java.lang.reflect.InvocationHandler h = new java.lang.reflect.InvocationHandler() {
                public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                    try {
                        if ("onReceiveValue".equals(m.getName()) && args != null && args.length > 0) {
                            cb.onValue((String) args[0]);
                        }
                    } catch (Throwable ignored) { }
                    return null;
                }
            };
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    vc.getClassLoader(), new Class[]{vc}, h);
            java.lang.reflect.Method mm = wv.getClass().getMethod(
                    "evaluateJavascript", String.class, vc);
            mm.invoke(wv, js, proxy);
        } catch (Throwable t) {
            Log.e(TAG, "evalJsResult failed", t);
        }
    }

    /** 抓当前 DOM 状态：输入框 / 本轮我的消息 / 助手回复 */
    private static final String JS_DEBUG =
        "(function(){try{ var root0=document.querySelector('[class*=\"scrollBody\"]')||document.body; var items=[].slice.call(root0.querySelectorAll('[class*=\"flowItem\"]')); var out=[]; for(var i=Math.max(0,items.length-5);i<items.length;i++){var e=items[i]; var md=e.querySelector('[class*=\"markdown\"]')?'M':'-'; var img=e.querySelector('img')?'A':'-'; var btn=e.querySelector('button')?'B':'-'; var slots=[];var all=[].slice.call(e.querySelectorAll('[data-slot]')); for(var j=0;j<all.length&&j<3;j++)slots.push(all[j].getAttribute('data-slot')); var cls=[];var ch=[].slice.call(e.querySelectorAll('div[class]')); for(var k=0;k<ch.length&&k<3;k++)cls.push(String(ch[k].className).slice(0,18)); var t=((e.innerText||'')+'').replace(/\\s+/g,' ').trim().slice(0,12); out.push('{'+md+img+btn+'|'+slots.join(',')+'|'+cls.join(',')+'|'+t+'}');} return out.join(' ~ '); }catch(e){return 'DBG_ERR '+e;}})()";

    private static final String JS_STATE =
        "(function(){try{ function clean(t){var ls=((t||'')+'').split(/\\n+/),o=[]; for(var i=0;i<ls.length;i++){var q=ls[i].replace(/\\s+$/,'').trim(); if(!q)continue; if(/^\\d{1,2}:\\d{2}$/.test(q))continue; if(/^\\d{1,2}月\\d{1,2}日?$/.test(q))continue; if(/用时\\s*\\d+\\s*秒/.test(q))continue; if(/^\\d+\\s*秒/.test(q))continue; if(/tokens?/i.test(q))continue; if(/^\\d+\\s*步/.test(q))continue; if(/^已完成/.test(q))continue; if(/^加载更早/.test(q))continue; if(/^\\d+\\s*轮/.test(q))continue; o.push(q);}return o.join('\\n');} var box=document.querySelector('textarea,[contenteditable=\"true\"]'); var input=box?(((box.value!==undefined?box.value:box.textContent)||'')+'').trim():''; var root0=document.querySelector('[class*=\"scrollBody\"]')||document.body; var items=[].slice.call(root0.querySelectorAll('[class*=\"flowItem\"]')); var msgs=[]; for(var i=0;i<items.length;i++){var e=items[i]; var mine=!!e.querySelector('[data-slot*=\"chat.user\"],[data-slot*=\"user.text\"],[class*=\"userRow\"],[class*=\"_bubble_\"]'); var ai=!!e.querySelector('[class*=\"markdown\"],[data-slot*=\"assistant-actions\"]'); if(!mine&&!ai)continue; var tail=!!e.querySelector('[data-slot*=\"turnTail\"]'); var t=clean(e.innerText); if(t.length<1)continue; if(tail&&t.length<24)continue; msgs.push({t:t.slice(0,1500),me:mine?1:0});} if(msgs.length>30)msgs=msgs.slice(msgs.length-30); return JSON.stringify({i:input,m:msgs}); }catch(e){return JSON.stringify({e:''+e});}})()";



    private static final String JS_PROBE =
        "(function(){try{"
      + "var box=document.querySelector('textarea,[contenteditable=\"true\"]');"
      + "if(!box)return 'NOBOX';"
      + "var out=[],p=box;"
      + "for(var i=0;i<8&&p;i++){p=p.parentElement;if(!p)break;"
      + "var kids=[].slice.call(p.children);"
      + "var lens=kids.slice(0,10).map(function(k){return ((k.innerText||'').trim().length);});"
      + "var c=((p.className&&p.className.baseVal!==undefined?p.className.baseVal:p.className)||'').slice(0,18);"
      + "out.push('L'+i+'<'+(p.tagName||'')+'.'+c+'>c'+kids.length+'['+lens.join(',')+']');}"
      + "return out.join(' ~ ');}catch(e){return 'PROBE_ERR '+e;}})()";

    private static Runnable sHideTask;
    private static Handler sPollHandler;
    private static Runnable sPollTask;

    private static void startPoll(final Activity act) {
        stopPoll();
        Looper m = Looper.getMainLooper();
        if (m == null) return;
        sPollHandler = new Handler(m);
        sPollTask = new Runnable() {
            int n = 0;
            public void run() {
                n++;
                Object wv = sWebView.get();
                if (wv == null || n > 90) { stopPoll(); return; }
                evalJsResult(wv, JS_STATE, new JsCallback() {
                    public void onValue(String raw) {
                        try {
                            if (raw == null || "null".equals(raw)) return;
                            Object v = new org.json.JSONTokener(raw).nextValue();
                            String json = String.valueOf(v);
                            org.json.JSONObject o = new org.json.JSONObject(json);
                            Log.i(TAG, "STATE d=" + o.optString("d") + " u=" + o.optString("u").length()
                                    + " r=" + o.optString("r").length());
                            if (sPollHandler != null) {
                                org.json.JSONArray ma = o.optJSONArray("m");
                                if (ma != null) {
                                    sendState(act, o.optString("i", null), ma.toString());
                                }
                            }
                        } catch (Throwable ignored) { }
                    }
                });
                if (sPollHandler != null) sPollHandler.postDelayed(this, 700);
            }
        };
        sPollHandler.postDelayed(new Runnable() {
            public void run() {
                Object wv = sWebView.get();
                if (wv != null) evalJsResult(wv, JS_PROBE, new JsCallback() {
                    public void onValue(String v) { Log.i(TAG, "PROBE " + v); }
                });
                if (wv != null) evalJsResult(wv, JS_DEBUG, new JsCallback() {
                    public void onValue(String v) { Log.i(TAG, "DBG " + v); }
                });
            }
        }, 500);
        sPollHandler.postDelayed(sPollTask, 400);
    }

    private static void stopPoll() {
        try {
            if (sPollHandler != null && sPollTask != null) sPollHandler.removeCallbacks(sPollTask);
        } catch (Throwable ignored) { }
        sPollHandler = null;
        sPollTask = null;
    }

    private static volatile String sLastSent = "";
    private static volatile String sLastInput = "";

    private static void sendState(Activity act, String input, String msgsJson) {
        try {
            if (act == null) return;
            Intent i = new Intent("com.dshfix.ptt.STATE");
            i.setPackage("com.dshfix.modaudio");
            i.putExtra("t", "dshfix-7c1f");
            if (input != null && !input.isEmpty()) i.putExtra("input", input);
            if (msgsJson != null && !msgsJson.isEmpty()) i.putExtra("msgs", msgsJson);
            if (sLastSent != null && !sLastSent.isEmpty()) i.putExtra("sent", sLastSent);
            act.sendBroadcast(i);
        } catch (Throwable t) {
            Log.e(TAG, "sendState fail", t);
        }
    }

    private static void sendHide() {
        try {
            Activity a = sAct.get();
            if (a == null) return;
            Intent i = new Intent("com.dshfix.ptt.STATE");
            i.setPackage("com.dshfix.modaudio");
            i.putExtra("t", "dshfix-7c1f");
            i.putExtra("hide", true);
            a.sendBroadcast(i);
        } catch (Throwable ignored) { }
    }

    private static void runJs(final String js) {
        Looper main = Looper.getMainLooper();
        if (main == null) return;
        new Handler(main).post(new Runnable() {
            @Override
            public void run() {
                Object wv = sWebView.get();
                if (wv == null) { Log.i(TAG, "runJs: webview null"); return; }
                evalJs(wv, js);
            }
        });
    }

    private static boolean evalJs(Object wv, String js) {
        try {
            Method m = wv.getClass().getMethod("evaluateJavascript", String.class,
                    Class.forName("android.webkit.ValueCallback"));
            m.invoke(wv, js, null);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "evalJs failed", t);
            return false;
        }
    }

    private static void grantAudio(Object req) {
        if (req == null) return;
        try {
            Method m = req.getClass().getMethod("grant", String[].class);
            m.invoke(req, (Object) new String[]{RES_AUDIO});
            Log.i(TAG, "granted " + RES_AUDIO);
        } catch (Throwable t) {
            Log.e(TAG, "grant failed", t);
        }
    }

    private static void hook(String cls, XC_LoadPackage.LoadPackageParam lp,
                            XC_MethodHook cb, String method, Object... types) {
        try {
            Object[] args = new Object[types.length + 1];
            System.arraycopy(types, 0, args, 0, types.length);
            args[types.length] = cb;
            XposedHelpers.findAndHookMethod(cls, lp.classLoader, method, args);
        } catch (Throwable t) {
            Log.i(TAG, "skip " + cls + "#" + method);
        }
    }
}

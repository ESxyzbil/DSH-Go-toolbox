package com.dshfix.modaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 常驻通知 + 看护按键监听。
 *
 * 按键监听不落盘：用 root 直接跑一段内联 shell（su -c '...'），
 * 它脱离 App 进程组，不受 ColorOS virtualFreeze 影响。
 */
public class PttService extends Service {

    private static final String TAG = "DSH-MOD";
    private static final String CH = "ptt";
    /** 用来在 pgrep 里认出自己，避免自匹配（不要改成裸脚本名） */
    private static final String MARK = "DSHPTT_MARK";

    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "按键服务",
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        nb.setContentTitle("DSH 工具箱")
          .setContentText("Plus 键监听中")
          .setSmallIcon(android.R.drawable.ic_btn_speak_now)
          .setOngoing(true);
        startForeground(1, nb.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            getSharedPreferences("ptt", MODE_PRIVATE).edit().putBoolean("running", true).apply();
            ensureA11y();
            ensureOverlayPerm();
            new Thread(new Runnable() {
                public void run() { watch(); }
            }, "ptt-guard").start();
        }
        return START_STICKY;
    }

    private void watch() {
        ensureDaemon();
        while (running) {
            try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
            ensureDaemon();
        }
    }

    /** 把 assets 里的监听脚本写到 /data/local/tmp（App 自动写，用户无需干预） */
    private String scriptPath() {
        try {
            java.io.InputStream is = getAssets().open("ptt_daemon.sh");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            String b64 = android.util.Base64.encodeToString(bo.toByteArray(),
                    android.util.Base64.NO_WRAP);
            runRoot("echo '" + b64 + "' | base64 -d > /data/local/tmp/ptt_loop.sh "
                    + "&& chmod 755 /data/local/tmp/ptt_loop.sh");
            return "/data/local/tmp/ptt_loop.sh";
        } catch (Throwable t) {
            Log.e(TAG, "write script failed", t);
            return null;
        }
    }

    /** 已经在跑就不动；不在就起一个（root + setsid，脱离 App 会话） */
    private void ensureDaemon() {
        try {
            String alive = runRoot("pgrep -f 'ptt_loo[p]' >/dev/null 2>&1 && echo yes");
            if (alive != null && alive.contains("yes")) return;
            String p = scriptPath();
            if (p == null) return;
            Log.i(TAG, "ptt: starting key listener");
            runRoot("setsid sh " + p + " </dev/null >/dev/null 2>&1 &");
        } catch (Throwable t) {
            Log.e(TAG, "ensure daemon failed", t);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        getSharedPreferences("ptt", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** 供设置页的“测试跳转”使用 */
    static String startCmd(android.content.SharedPreferences sp) {
        String PKG = "io.github.ccch1mneyyy.dsh_app";
        String sid = sp.getString("session", "");
        String host = sp.getString("host", "");
        StringBuilder sb = new StringBuilder();
        sb.append("am start -n ").append(PKG).append("/.shortcuts.EntryActivity");
        if (sid != null && sid.length() > 0) {
            sb.append(" -a ").append(PKG).append(".action.OPEN_CHAT");
            sb.append(" --es session_id ").append(sid);
            if (host != null && host.length() > 0) sb.append(" --es host ").append(host);
        } else {
            sb.append(" -a ").append(PKG).append(".action.OPEN_APP");
        }
        return sb.toString();
    }

    /** 用 root 授予“显示在其他应用上层” */
    static void ensureOverlayPerm() {
        String out = runRoot("appops set com.dshfix.modaudio SYSTEM_ALERT_WINDOW allow");
        Log.i(TAG, "overlay perm: " + out);
    }

    /** 用 root 先关再开 dsh-Go 的无障碍服务（等价于重启它） */
    static void restartDshA11y() {
        String svc = "io.github.ccch1mneyyy.dsh_app/.phone.a11y.PhoneA11yService";
        String off = "CUR=$(settings get secure enabled_accessibility_services); "
                + "NEW=$(echo \"$CUR\" | sed 's#io[.]github[.]ccch1mneyyy[.]dsh_app/[^:]*##g' | sed 's#::#:#g; s#^:##; s#:$##'); "
                + "settings put secure enabled_accessibility_services \"$NEW\"; echo \"off=$NEW\"";
        String on = "CUR=$(settings get secure enabled_accessibility_services); "
                + "case \"$CUR\" in *io.github.ccch1mneyyy.dsh_app*) ;; "
                + "*) if [ -n \"$CUR\" ]; then settings put secure enabled_accessibility_services \"$CUR:" + svc + "\"; "
                + "else settings put secure enabled_accessibility_services \"" + svc + "\"; fi;; esac; "
                + "settings put secure accessibility_enabled 1; echo \"on=$(settings get secure enabled_accessibility_services)\"";
        Log.i(TAG, "dsh a11y off: " + runRoot(off));
        try { Thread.sleep(900); } catch (InterruptedException ignored) { }
        Log.i(TAG, "dsh a11y on: " + runRoot(on));
    }

    /** 用 root 把无障碍服务写回系统设置（重装后会被清掉） */
    static void ensureA11y() {
        String cmd = "CUR=$(settings get secure enabled_accessibility_services); "
                + "case \"$CUR\" in *com.dshfix.modaudio*) ;; "
                + "*) settings put secure enabled_accessibility_services \"$CUR:com.dshfix.modaudio/com.dshfix.modaudio.ClickService\";; esac; "
                + "settings put secure accessibility_enabled 1";
        String out = runRoot(cmd);
        Log.i(TAG, "a11y ensure: " + out);
    }

    /** 用 root 执行一条命令，返回输出；失败返回 null */
    public static String runRoot(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "runRoot failed: " + cmd, t);
            return null;
        }
    }
}

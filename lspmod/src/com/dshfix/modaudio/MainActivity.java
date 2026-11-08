package com.dshfix.modaudio;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 模块的设置与状态页（MIUIX 风格） */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#F5F6F8");
    private static final int CARD = Color.WHITE;
    private static final int PRIMARY = Color.parseColor("#3482FF");
    private static final int TEXT = Color.parseColor("#1A1A1A");
    private static final int SUB = Color.parseColor("#8A8F99");
    private static final int SOFT = Color.parseColor("#EAF1FF");

    private float d;
    private TextView statusRoot;
    private TextView statusSvc;
    private TextView statusSess;
    private Button toggle;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        sp = getSharedPreferences("ptt", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int statusBar = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) statusBar = getResources().getDimensionPixelSize(resId);
        root.setPadding(dp(20), statusBar + dp(18), dp(20), dp(40));
        sv.addView(root);

        TextView title = text("DSH 工具箱", 26, TEXT, true);
        root.addView(title);
        TextView sub = text("按住 Plus 键说话 · 松手自动发送", 13, SUB, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        // 状态卡
        LinearLayout st = card();
        statusRoot = row(st, "Root");
        statusSvc = row(st, "按键服务");
        statusSess = row(st, "默认会话");
        root.addView(st);

        // 设置卡
        LinearLayout cfg = card();
        TextView cfgTitle = text("启动行为", 15, TEXT, true);
        cfg.addView(cfgTitle);

        TextView lbl = text("按键时：有会话 ID 就跳到它，留空则停在你当前会话", 12, SUB, false);
        lbl.setPadding(0, dp(10), 0, dp(2));
        cfg.addView(lbl);

        miniField(cfg, "会话 ID（例 session-3f2a…）", "session", "");
        miniField(cfg, "主机 host（可选，留空默认 local）", "host", "");
        root.addView(cfg);

        // 操作卡
        LinearLayout act = card();
        toggle = pill("启动按键服务", PRIMARY, Color.WHITE);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleService(); }
        });
        act.addView(toggle);

        Button t1 = pill("测试：开始录音", SOFT, PRIMARY);
        t1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PttService.runRoot("am broadcast -a com.dshfix.ptt.START -p io.github.ccch1mneyyy.dsh_app");
                toast("已发送 START");
            }
        });
        addWithTop(act, t1, 10);

        Button t2 = pill("测试：停止并发送", SOFT, PRIMARY);
        t2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PttService.runRoot("am broadcast -a com.dshfix.ptt.STOP -p io.github.ccch1mneyyy.dsh_app");
                toast("已发送 STOP");
            }
        });
        addWithTop(act, t2, 10);

        Button t3 = pill("测试：跳转到该会话", SOFT, PRIMARY);
        t3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String cmd = PttService.startCmd(sp);
                PttService.runRoot(cmd);
                toast("已执行跳转");
            }
        });
        addWithTop(act, t3, 10);

        Button t4 = pill("打开 DSH Go（不跳会话）", SOFT, PRIMARY);
        t4.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PttService.runRoot("am start -n io.github.ccch1mneyyy.dsh_app/.shortcuts.EntryActivity"
                        + " -a io.github.ccch1mneyyy.dsh_app.action.OPEN_APP");
                toast("已打开");
            }
        });
        addWithTop(act, t4, 10);

        Button t5 = pill("实验：放行后台麦克风", SOFT, PRIMARY);
        t5.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String out = PttService.runRoot(
                        "appops set --uid io.github.ccch1mneyyy.dsh_app RECORD_AUDIO allow; "
                        + "appops get io.github.ccch1mneyyy.dsh_app RECORD_AUDIO");
                toast(out == null ? "执行失败" : out.trim());
                android.util.Log.i("DSH-MOD", "appops result: " + out);
                refresh();
            }
        });
        addWithTop(act, t5, 10);

        Button t8 = pill("学习按键（按下目标键）", SOFT, PRIMARY);
        t8.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toast("请按一下目标按键…（10 秒内）");
                new Thread(new Runnable() {
                    public void run() {
                        final String r = PttService.learnKey();
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (r == null) {
                                    toast("没抓到按键，再试一次");
                                    return;
                                }
                                String[] kv = r.split("\\|");
                                getSharedPreferences("ptt", MODE_PRIVATE).edit()
                                        .putString("keyDev", kv[0])
                                        .putString("keyName", kv.length > 1 ? kv[1] : "BTN_TRIGGER_HAPPY32")
                                        .apply();
                                PttService.runRoot("pkill -9 -f 'ptt_loo[p]'");
                                toast("已学会：" + kv[kv.length - 1]);
                            }
                        });
                    }
                }).start();
            }
        });
        addWithTop(act, t8, 10);

        Button t7 = pill("重启 DSH 无障碍（root）", SOFT, PRIMARY);
        t7.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toast("正在重启…");
                new Thread(new Runnable() {
                    public void run() { PttService.restartDshA11y(); }
                }).start();
            }
        });
        addWithTop(act, t7, 10);

        Button t6 = pill("修复无障碍授权", SOFT, PRIMARY);
        t6.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                PttService.ensureA11y();
                toast("已尝试启用无障碍");
            }
        });
        addWithTop(act, t6, 10);
        root.addView(act);

        TextView tips = text("使用前：\n"
                + "1. LSPosed 启用本模块，作用域勾选 DSH Go\n"
                + "2. FolkPatch 给本 App 授予 root\n"
                + "3. DSH Go 打开网页版并进入某个会话\n"
                + "4. 按住 Plus 键说话，松手自动发送\n\n"
                + "日志看 logcat 的 DSH-MOD 标签", 12, SUB, false);
        tips.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(tips);

        setContentView(sv);
        refresh();
        try { PttService.ensureA11y(); } catch (Throwable ignored) { }
        if (sp.getBoolean("running", true)) {
            try {
                startForegroundService(new Intent(this, PttService.class));
                sp.edit().putBoolean("running", true).apply();
            } catch (Throwable ignored) { }
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void toggleService() {
        boolean running = sp.getBoolean("running", false);
        if (running) {
            stopService(new Intent(this, PttService.class));
        } else {
            startForegroundService(new Intent(this, PttService.class));
        }
        sp.edit().putBoolean("running", !running).apply();
        refresh();
    }

    private void refresh() {
        boolean running = sp.getBoolean("running", false);
        String id = PttService.runRoot("id");
        boolean root = id != null && id.contains("uid=0");
        statusRoot.setText("Root：" + (root ? "已授权" : "未授权"));
        statusSvc.setText("按键服务：" + (running ? "运行中" : "已停止"));
        String sid = sp.getString("session", "");
        statusSess.setText("默认会话：" + (sid.isEmpty() ? "跟随当前" : sid));
        toggle.setText(running ? "停止按键服务" : "启动按键服务");
    }

    private void miniField(LinearLayout parent, String hint, final String key, String def) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13);
        et.setSingleLine(true);
        et.setText(sp.getString(key, def));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2F3F5"));
        bg.setCornerRadius(dp(12));
        et.setBackground(bg);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                sp.edit().putString(key, s.toString().trim()).apply();
            }
        });
        parent.addView(et, lp);
    }

    // ---------- UI helpers ----------

    private int dp(float v) { return (int) (v * d + 0.5f); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipChildren(false);
        c.setClipToPadding(false);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(20));
        c.setBackground(g);
        c.setPadding(dp(22), dp(16), dp(22), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView row(LinearLayout parent, String key) {
        TextView t = new TextView(this);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setPadding(0, dp(6), 0, dp(6));
        parent.addView(t);
        return t;
    }

    private Button pill(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(fg);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(999));
        b.setBackground(g);
        b.setMinHeight(dp(48));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return b;
    }

    private void addWithTop(LinearLayout parent, View v, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(top);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        parent.addView(v, lp);
    }
}

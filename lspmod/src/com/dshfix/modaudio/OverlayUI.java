package com.dshfix.modaudio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

/**
 * 悬浮抽屉：识别文本 + 会话消息（我的在右，回复在左，MIUIX 风格气泡）。
 * 底边固定，拖指示条改变高度；× 或下拉收起。
 */
public class OverlayUI {

    private static final String TAG = "DSH-MOD";
    private static OverlayUI sInst;

    private static final int INK = 0xFF12121A;
    private static final int SUB = 0xFF8C8C99;
    private static final int LINE = 0x14000000;
    private static final int BUBBLE_ME = 0xFF2B62FF;
    private static final int BUBBLE_AI = 0xFFFFFFFF;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View root;
    private TextView tvInput;
    private LinearLayout msgList;
    private ScrollView svList;
    private WindowManager.LayoutParams lp;

    private boolean attached = false;
    private int baseH = 0;
    private int bottomEdge = 0;
    private Runnable pendingHide;

    private final java.util.List<String> curText = new java.util.ArrayList<String>();
    private final java.util.List<Integer> curRole = new java.util.ArrayList<Integer>();
    private final java.util.List<TextView> curView = new java.util.ArrayList<TextView>();

    private boolean needScrollBottom = true;
    private boolean dismissed = false;
    private long dismissedAt = 0;
    private String dismissedInput = "";

    private OverlayUI(Context c) {
        this.ctx = c.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized OverlayUI get(Context c) {
        if (sInst == null) sInst = new OverlayUI(c);
        return sInst;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    public void update(final String input, final String msgsJson, final String sent) {
        ui.post(new Runnable() {
            public void run() {
                // 用户主动关掉后，同一轮的数据不再把它拉回来
                String in = input == null ? "" : input.trim();
                if (dismissed) {
                    boolean stale = System.currentTimeMillis() - dismissedAt > 60000;
                    if (!stale && (in.isEmpty() || in.equals(dismissedInput))) return;
                    dismissed = false;
                }
                cancelHide();
                ensureView();
                if (in.isEmpty()) tvInput.setVisibility(View.GONE);
                else {
                    tvInput.setVisibility(View.VISIBLE);
                    tvInput.setText(in);
                }
                renderMsgs(msgsJson, sent);
                if (!attached) show();
            }
        });
    }

    private boolean isAtBottom() {
        try {
            View child = svList.getChildAt(0);
            if (child == null) return true;
            int gap = child.getBottom() - svList.getScrollY() - svList.getHeight();
            return gap < dp(48);
        } catch (Throwable t) {
            return true;
        }
    }

    private void renderMsgs(String msgsJson, String sent) {
        JSONArray arr = null;
        try { arr = new JSONArray(msgsJson == null ? "[]" : msgsJson); } catch (Throwable ignored) { }
        if (arr == null || svList == null) return;

        final java.util.List<String> texts = new java.util.ArrayList<String>();
        final java.util.List<Integer> roles = new java.util.ArrayList<Integer>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("t", "");
            if (t == null || t.trim().isEmpty()) continue;
            texts.add(t.trim());
            roles.add(o.optInt("me", 0));
        }

        boolean wasAtBottom = isAtBottom() || needScrollBottom;
        needScrollBottom = false;
        boolean sameShape = texts.size() == curText.size();

        if (sameShape) {
            for (int i = 0; i < texts.size(); i++) {
                if (!roles.get(i).equals(curRole.get(i))) { sameShape = false; break; }
            }
        }

        if (sameShape) {
            // 结构没变：只更新变化的文本（流式追加就只动最后一条）
            for (int i = 0; i < texts.size(); i++) {
                if (!texts.get(i).equals(curText.get(i))) {
                    curText.set(i, texts.get(i));
                    TextView tv = curView.get(i);
                    if (tv != null) tv.setText(texts.get(i));
                }
            }
        } else {
            msgList.removeAllViews();
            curText.clear(); curRole.clear(); curView.clear();
            for (int i = 0; i < texts.size(); i++) {
                TextView tv = addBubble(texts.get(i), roles.get(i) == 1);
                curText.add(texts.get(i));
                curRole.add(roles.get(i));
                curView.add(tv);
            }
        }

        if (wasAtBottom && svList != null) {
            svList.post(new Runnable() {
                public void run() {
                    try { svList.fullScroll(View.FOCUS_DOWN); } catch (Throwable ignored) { }
                }
            });
        }
    }

    private TextView addBubble(String text, boolean mine) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        row.setGravity(mine ? Gravity.END : Gravity.START);

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(mine ? 0xFFFFFFFF : INK);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mine ? BUBBLE_ME : BUBBLE_AI);
        bg.setCornerRadius(dp(16));
        if (!mine) bg.setStroke(dp(1), LINE);
        tv.setBackground(bg);

        int maxW = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.78f);
        tv.setMaxWidth(maxW);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(tv, tlp);
        msgList.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    public void resetDismiss() {
        ui.post(new Runnable() {
            public void run() { dismissed = false; }
        });
    }

    public void closeByUser() {
        ui.post(new Runnable() {
            public void run() {
                dismissed = true;
                dismissedAt = System.currentTimeMillis();
                dismissedInput = tvInput.getText() == null ? "" : tvInput.getText().toString().trim();
                notifyClose();
                hideNow();
            }
        });
    }

    public void hide() {
        ui.post(new Runnable() {
            public void run() {
                cancelHide();
                pendingHide = new Runnable() {
                    public void run() { hideNow(); }
                };
                ui.postDelayed(pendingHide, 3000);
            }
        });
    }

    private void cancelHide() {
        if (pendingHide != null) {
            ui.removeCallbacks(pendingHide);
            pendingHide = null;
        }
    }

    private void ensureView() {
        if (root != null) return;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(6), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2FFFFFF);
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), LINE);
        card.setBackground(bg);

        LinearLayout grabber = new LinearLayout(ctx);
        grabber.setOrientation(LinearLayout.VERTICAL);
        grabber.setGravity(Gravity.CENTER_HORIZONTAL);
        grabber.setPadding(0, dp(8), 0, dp(2));
        View bar = new View(ctx);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xFFD5D5DE);
        bd.setCornerRadius(dp(2));
        bar.setBackground(bd);
        grabber.addView(bar, new LinearLayout.LayoutParams(dp(38), dp(4)));
        card.addView(grabber);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(ctx);
        dot.setText("●");
        dot.setTextSize(9);
        dot.setTextColor(0xFF2B62FF);
        head.addView(dot);

        TextView title = new TextView(ctx);
        title.setText("  DSH 语音");
        title.setTextSize(12);
        title.setTextColor(SUB);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(13);
        close.setTextColor(0xFFB0B0BC);
        close.setPadding(dp(12), dp(4), 0, dp(4));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeByUser(); }
        });
        head.addView(close);
        card.addView(head);

        tvInput = new TextView(ctx);
        tvInput.setTextSize(16);
        tvInput.setTextColor(INK);
        tvInput.setLineSpacing(dp(3), 1f);
        tvInput.setPadding(0, dp(6), 0, dp(2));
        tvInput.setMaxLines(2);
        tvInput.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvInput);

        svList = new ScrollView(ctx);
        svList.setVerticalScrollBarEnabled(false);
        svList.setClipToPadding(false);
        msgList = new LinearLayout(ctx);
        msgList.setOrientation(LinearLayout.VERTICAL);
        svList.addView(msgList, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        card.addView(svList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root = card;
        card.setOnTouchListener(new View.OnTouchListener() {
            float startRawY;
            int startH;
            boolean dragging;
            android.view.VelocityTracker vt;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = e.getY() < dp(64);
                        if (!dragging || lp == null) return false;
                        startRawY = e.getRawY();
                        startH = lp.height;
                        vt = android.view.VelocityTracker.obtain();
                        vt.addMovement(e);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging || lp == null) return false;
                        if (vt != null) vt.addMovement(e);
                        float dy = startRawY - e.getRawY();
                        int min = dp(150);
                        int max = (int) (baseH * 3.2f);
                        int want = Math.max(min, Math.min(max, startH + (int) dy));
                        applyGeom(want);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging || lp == null) return false;
                        dragging = false;
                        float vy = 0;
                        if (vt != null) {
                            vt.computeCurrentVelocity(1000);
                            vy = vt.getYVelocity();
                            vt.recycle();
                            vt = null;
                        }
                        int grown = lp.height - startH;
                        boolean open = vy < -900 && grown > dp(60);
                        boolean close = vy > 800 || lp.height <= dp(175);
                        if (open) {
                            Log.i(TAG, "overlay drag up -> open app");
                            hideNow();
                            new Thread(new Runnable() {
                                public void run() {
                                    PttService.runRoot("am start -n io.github.ccch1mneyyy.dsh_app/.shortcuts.EntryActivity"
                                            + " -a io.github.ccch1mneyyy.dsh_app.action.OPEN_APP");
                                }
                            }).start();
                        } else if (close) {
                            closeByUser();
                        } else {
                            baseH = Math.max(dp(150), lp.height);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void applyGeom(int h) {
        if (lp == null || !attached) return;
        lp.height = h;
        lp.y = bottomEdge - h;
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) { }
    }

    private void show() {
        try {
            WindowMetrics m = wm.getCurrentWindowMetrics();
            int fullW = m.getBounds().width();
            int fullH = m.getBounds().height();
            Insets nav = m.getWindowInsets().getInsets(WindowInsets.Type.navigationBars());
            bottomEdge = fullH - nav.bottom - dp(6);
            baseH = (int) (fullH / 5f);

            lp = new WindowManager.LayoutParams(
                    fullW - dp(20),
                    dp(60),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = bottomEdge - dp(60);
            wm.addView(root, lp);
            attached = true;
            needScrollBottom = true;
            Log.i(TAG, "overlay attached bottom=" + bottomEdge + " base=" + baseH);
            animateHeight(dp(60), baseH);
        } catch (Throwable t) {
            Log.e(TAG, "overlay attach failed (no permission?)", t);
        }
    }

    private void animateHeight(int from, int to) {
        if (lp == null) return;
        ValueAnimator va = ValueAnimator.ofInt(from, to);
        va.setDuration(220);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) { applyGeom((Integer) a.getAnimatedValue()); }
        });
        va.start();
    }

    private void hideNow() {
        ui.post(new Runnable() {
            public void run() {
                cancelHide();
                if (!attached || root == null || lp == null) return;
                ValueAnimator va = ValueAnimator.ofInt(lp.height, 0);
                va.setDuration(180);
                va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator a) { applyGeom((Integer) a.getAnimatedValue()); }
                });
                va.addListener(new AnimatorListenerAdapter() {
                    public void onAnimationEnd(Animator a) { detach(); }
                });
                va.start();
            }
        });
    }

    private void notifyClose() {
        try {
            android.content.Intent i = new android.content.Intent("com.dshfix.ptt.CLOSE");
            i.setPackage("io.github.ccch1mneyyy.dsh_app");
            i.putExtra("t", "dshfix-7c1f");
            ctx.sendBroadcast(i);
            Log.i(TAG, "notify close -> module");
        } catch (Throwable ignored) { }
    }

    private void detach() {
        if (!attached || root == null) return;
        try { wm.removeView(root); } catch (Throwable ignored) { }
        attached = false;
        lp = null;
        Log.i(TAG, "overlay detached");
    }
}

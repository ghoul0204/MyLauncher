package com.wei.mylauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * LINE 來電時的備用接聽畫面：有些三星手機 LINE 來電只響鈴、沒有接聽按鈕，
 * 這個畫面直接觸發 LINE 通知裡的「接聽／拒絕」動作。
 */
public class CallActivity extends Activity {

    static WeakReference<CallActivity> current = new WeakReference<>(null);
    private String pendingAction;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (LineListener.callKey == null) { finish(); return; }
        current = new WeakReference<>(this);
        setContentView(buildUi());
        pendingAction = getIntent().getAction();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        pendingAction = intent.getAction();
    }

    @Override
    protected void onResume() {
        super.onResume();
        final String a = pendingAction;
        pendingAction = null;
        // 從通知按鈕直接按「接聽／拒絕」：畫面出現後馬上執行
        if ("answer".equals(a)) getWindow().getDecorView().postDelayed(this::answer, 150);
        else if ("decline".equals(a)) getWindow().getDecorView().postDelayed(this::decline, 150);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current.get() == this) current = new WeakReference<>(null);
    }

    static void closeIfOpen() {
        CallActivity a = current.get();
        if (a != null) a.finish();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(dp(24), dp(96), dp(24), dp(64));

        TextView sub = new TextView(this);
        sub.setText(LineListener.TEST_KEY.equals(LineListener.callKey) ? "LINE 來電（測試）" : "LINE 來電");
        sub.setTextColor(0xFF06C755);
        sub.setTextSize(18);
        root.addView(sub);

        TextView name = new TextView(this);
        name.setText(LineListener.callName);
        name.setTextColor(Color.WHITE);
        name.setTextSize(34);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(12), 0, 0);
        root.addView(name);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(roundBtn("拒絕", 0xFFE53935, v -> decline()));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(72), 1));
        row.addView(roundBtn("接聽", 0xFF06C755, v -> answer()));
        root.addView(row);

        TextView open = new TextView(this);
        open.setText("開啟 LINE 來電畫面");
        open.setTextColor(0xCCFFFFFF);
        open.setTextSize(15);
        open.setPadding(dp(16), dp(28), dp(16), dp(8));
        open.setOnClickListener(v -> {
            if (fire(LineListener.callScreen)) finish();
            else toast("LINE 沒有提供來電畫面");
        });
        root.addView(open);
        return root;
    }

    private TextView roundBtn(String label, int color, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        t.setBackground(g);
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(96), dp(96)));
        return t;
    }

    void answer() {
        if (LineListener.TEST_KEY.equals(LineListener.callKey)) { toast("測試成功：接聽按鈕可以正常顯示"); done(); return; }
        boolean ok = fire(LineListener.callAnswer);
        if (!ok) ok = fire(LineListener.callScreen);   // 沒有直接接聽的動作 → 打開 LINE 來電畫面
        if (!ok) toast("無法接聽，請直接打開 LINE");
        done();
    }

    void decline() {
        if (!LineListener.TEST_KEY.equals(LineListener.callKey)) fire(LineListener.callDecline);
        done();
    }

    private void done() {
        LineListener.clearCall(this);
        finish();
    }

    private boolean fire(PendingIntent pi) {
        if (pi == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(this, 0, null, null, null, null, o.toBundle());
            } else {
                pi.send();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

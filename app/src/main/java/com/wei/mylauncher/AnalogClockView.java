package com.wei.mylauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Calendar;

/** 指針時鐘（時針、分針、秒針），每秒更新一次。 */
public class AnalogClockView extends View {

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hour = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minute = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint second = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            invalidate();
            // 對齊到下一秒整，指針才會準時跳動
            postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    public AnalogClockView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        face.setStyle(Paint.Style.STROKE);
        face.setStrokeWidth(2 * d);
        face.setColor(0xCCFFFFFF);
        tick.setColor(Color.WHITE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        hour.setColor(Color.WHITE);
        hour.setStrokeWidth(6 * d);
        hour.setStrokeCap(Paint.Cap.ROUND);
        minute.setColor(Color.WHITE);
        minute.setStrokeWidth(4 * d);
        minute.setStrokeCap(Paint.Cap.ROUND);
        second.setColor(0xFFFF5252);
        second.setStrokeWidth(1.6f * d);
        second.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xFFFF5252);
        for (Paint p : new Paint[]{face, tick, hour, minute, second}) p.setShadowLayer(3 * d, 0, d, 0x88000000);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(ticker);
        post(ticker);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(ticker);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - face.getStrokeWidth() * 2;
        float d = getResources().getDisplayMetrics().density;

        c.drawCircle(cx, cy, r, face);
        for (int i = 0; i < 60; i++) {
            boolean big = i % 5 == 0;
            tick.setStrokeWidth((big ? 3f : 1.2f) * d);
            double a = Math.toRadians(i * 6);
            float r1 = r - (big ? 12 : 6) * d, r2 = r - 2 * d;
            c.drawLine(cx + (float) (Math.sin(a) * r1), cy - (float) (Math.cos(a) * r1),
                    cx + (float) (Math.sin(a) * r2), cy - (float) (Math.cos(a) * r2), tick);
        }

        Calendar cal = Calendar.getInstance();
        int s = cal.get(Calendar.SECOND);
        float m = cal.get(Calendar.MINUTE) + s / 60f;
        float hr = cal.get(Calendar.HOUR) + m / 60f;
        hand(c, cx, cy, hr * 30, r * 0.5f, 0, hour);
        hand(c, cx, cy, m * 6, r * 0.75f, 0, minute);
        hand(c, cx, cy, s * 6, r * 0.88f, r * 0.15f, second);
        c.drawCircle(cx, cy, 4 * d, dot);
    }

    private void hand(Canvas c, float cx, float cy, float deg, float len, float tail, Paint p) {
        double a = Math.toRadians(deg);
        float sx = (float) Math.sin(a), sy = (float) Math.cos(a);
        c.drawLine(cx - sx * tail, cy + sy * tail, cx + sx * len, cy - sy * len, p);
    }
}

package com.wei.mylauncher;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** 左右翻頁容器：第 0 頁是自訂左滑頁，第 1 頁是主畫面。 */
public class PagerView extends HorizontalScrollView {
    private final LinearLayout strip;
    private VelocityTracker vt;
    private float ix, iy;
    private int page = 0;

    public PagerView(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);
        strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void addPage(View v) {
        strip.addView(v, new LinearLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public int getPage() { return page; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0) return;
        final int width = w;
        post(() -> {
            for (int i = 0; i < strip.getChildCount(); i++) {
                View c = strip.getChildAt(i);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp.width != width) { lp.width = width; c.setLayoutParams(lp); }
            }
            scrollTo(page * width, 0);
        });
    }

    public void setPageNow(int p) {
        page = p;
        post(() -> scrollTo(page * getWidth(), 0));
    }

    public void snapTo(int p) {
        int n = strip.getChildCount();
        page = Math.max(0, Math.min(n - 1, p));
        ObjectAnimator a = ObjectAnimator.ofInt(this, "scrollX", page * getWidth());
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator());
        a.start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            ix = e.getX();
            iy = e.getY();
        } else if (act == MotionEvent.ACTION_MOVE) {
            // 偏垂直的滑動交給裡面的捲動區，不翻頁
            if (Math.abs(e.getY() - iy) > Math.abs(e.getX() - ix)) return false;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        boolean r = super.onTouchEvent(e);
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            vt.computeCurrentVelocity(1000);
            float v = vt.getXVelocity();
            vt.recycle();
            vt = null;
            int w = getWidth();
            if (w == 0) return r;
            float pos = (float) getScrollX() / w;
            float thr = 300 * getResources().getDisplayMetrics().density;
            int target;
            if (v < -thr) target = (int) Math.ceil(pos);
            else if (v > thr) target = (int) Math.floor(pos);
            else target = Math.round(pos);
            snapTo(target);
        }
        return r;
    }

    @Override
    public void fling(int velocityX) {
        // 不用慣性捲動，改由 onTouchEvent 對齊到整頁
    }
}

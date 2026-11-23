package com.wei.mylauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final int HOST_ID = 2024;
    static final int REQ_BIND = 11, REQ_CONFIG = 12;
    static final int DLG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static class App {
        String key, label;
        ComponentName cn;
        Drawable icon;
    }

    interface Pick { void picked(App a); }

    SharedPreferences sp;
    AppWidgetManager awm;
    AppWidgetHost host;
    PagerView pager;
    FrameLayout root;
    LinearLayout leftList, homeGrid;
    TextView homeHint, editBtn, drawerTitle;
    View drawer;
    EditText drawerSearch;
    DrawerAdapter drawerAdapter;
    Pick pick;
    boolean editMode;
    int pendingWidgetId = -1;

    final ArrayList<String> homeApps = new ArrayList<>();
    final ArrayList<JSONObject> leftItems = new ArrayList<>();
    final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    List<App> apps = new ArrayList<>();
    final Map<String, App> appMap = new HashMap<>();
    final ExecutorService bg = Executors.newSingleThreadExecutor();

    final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadApps(); }
    };

    // ───────────────────────── 生命週期 ─────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        sp = getSharedPreferences("launcher", MODE_PRIVATE);
        awm = AppWidgetManager.getInstance(this);
        host = new AppWidgetHost(this, HOST_ID);
        if (b != null) pendingWidgetId = b.getInt("pending", -1);
        loadState();

        root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        pager = new PagerView(this);
        pager.addPage(buildLeftPage());
        pager.addPage(buildHomePage());
        root.addView(pager, new FrameLayout.LayoutParams(MATCH, MATCH));
        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);

        pager.setPageNow(1);
        refreshHome();
        refreshLeft();
        reloadApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgRx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(pkgRx, f);
    }

    @Override protected void onStart() {
        super.onStart();
        try { host.startListening(); } catch (Exception ignored) { }
    }

    @Override protected void onStop() {
        super.onStop();
        try { host.stopListening(); } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgRx); } catch (Exception ignored) { }
        bg.shutdown();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("pending", pendingWidgetId);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean wasInFront = hasWindowFocus();
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        if (wasInFront) {               // 已在桌面時再按 Home：回主畫面
            if (editMode) setEditMode(false);
            pager.snapTo(1);
        }
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (editMode) setEditMode(false);
        else pager.snapTo(1);
    }

    // ───────────────────────── 資料儲存 ─────────────────────────

    void loadState() {
        try {
            JSONArray a = new JSONArray(sp.getString("home", "[]"));
            for (int i = 0; i < a.length(); i++) homeApps.add(a.getString(i));
        } catch (JSONException ignored) { }
        if (!sp.contains("left")) {
            leftItems.add(obj("type", "note", "text",
                    "這是你的左滑頁。\n按右上「編輯」可調整，按下方「＋ 新增項目」加入小工具、App 捷徑或便條。"));
            return;
        }
        try {
            JSONArray a = new JSONArray(sp.getString("left", "[]"));
            for (int i = 0; i < a.length(); i++) leftItems.add(a.getJSONObject(i));
        } catch (JSONException ignored) { }
    }

    void saveState() {
        JSONArray l = new JSONArray();
        for (JSONObject o : leftItems) l.put(o);
        sp.edit().putString("home", new JSONArray(homeApps).toString())
                .putString("left", l.toString()).apply();
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); }
        catch (JSONException ignored) { }
        return o;
    }

    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (JSONException ignored) { }
    }

    static ArrayList<String> appsOf(JSONObject it) {
        ArrayList<String> l = new ArrayList<>();
        JSONArray a = it.optJSONArray("apps");
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    // ───────────────────────── App 清單 ─────────────────────────

    void reloadApps() {
        bg.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            final ArrayList<App> list = new ArrayList<>();
            for (ResolveInfo r : ris) {
                if (r.activityInfo.packageName.equals(getPackageName())) continue;
                App a = new App();
                a.cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
                a.key = a.cn.flattenToString();
                a.label = String.valueOf(r.loadLabel(pm));
                a.icon = r.loadIcon(pm);
                list.add(a);
            }
            final Collator col = Collator.getInstance(Locale.TAIWAN);
            Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                apps = list;
                appMap.clear();
                for (App a : list) appMap.put(a.key, a);
                drawerAdapter.filter(drawerSearch.getText().toString());
                refreshHome();
                refreshLeft();
            });
        });
    }

    void launch(App a) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(a.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            toast("無法開啟 " + a.label);
        }
    }

    void openAppInfo(App a) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", a.cn.getPackageName(), null)));
        } catch (Exception ignored) { }
    }

    void uninstall(App a) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.fromParts("package", a.cn.getPackageName(), null)));
        } catch (Exception ignored) { }
    }

    /** 從 App 抽屜長按出現的選單 */
    void appMenu(final App a) {
        String[] items = {"加到主畫面", "加到左滑頁（App 捷徑）", "應用程式資訊", "解除安裝"};
        new AlertDialog.Builder(this, DLG).setTitle(a.label).setItems(items, (d, w) -> {
            if (w == 0) {
                if (!homeApps.contains(a.key)) homeApps.add(a.key);
                saveState(); refreshHome(); toast("已加到主畫面");
            } else if (w == 1) {
                JSONObject g = null;
                for (JSONObject o : leftItems) if ("apps".equals(o.optString("type"))) { g = o; break; }
                if (g == null) { g = obj("type", "apps", "title", "常用 App", "apps", new JSONArray()); leftItems.add(g); }
                ArrayList<String> l = appsOf(g);
                if (!l.contains(a.key)) l.add(a.key);
                put(g, "apps", new JSONArray(l));
                saveState(); refreshLeft(); toast("已加到左滑頁");
            } else if (w == 2) openAppInfo(a);
            else uninstall(a);
        }).show();
    }

    // ───────────────────────── 主畫面 ─────────────────────────

    View buildHomePage() {
        final LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(24), dp(16), dp(12));

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(64);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        styleText(clock);
        p.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("M月d日 EEEE");
        date.setFormat12Hour("M月d日 EEEE");
        date.setTextSize(18);
        styleText(date);
        p.addView(date);

        View spacer = new View(this);
        p.addView(spacer, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        homeHint = text("在「所有應用程式」裡長按 App，可以加到主畫面", 13);
        homeHint.setGravity(Gravity.CENTER);
        p.addView(homeHint);

        homeGrid = new LinearLayout(this);
        homeGrid.setOrientation(LinearLayout.VERTICAL);
        p.addView(homeGrid, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView all = pill("︿  所有應用程式", v -> openDrawer(null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(12);
        p.addView(all, lp);

        // 往上滑開 App 抽屜；長按空白處開選單
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (vy < -800 && Math.abs(vy) > Math.abs(vx)) { openDrawer(null); return true; }
                return false;
            }
            @Override public void onLongPress(MotionEvent e) { homeMenu(); }
        });
        p.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        return p;
    }

    void refreshHome() {
        homeGrid.removeAllViews();
        ArrayList<App> list = new ArrayList<>();
        for (String k : homeApps) { App a = appMap.get(k); if (a != null) list.add(a); }
        homeHint.setVisibility(homeApps.isEmpty() ? View.VISIBLE : View.GONE);
        fillGrid(homeGrid, list, 5, a -> homeAppMenu(a));
    }

    void homeAppMenu(final App a) {
        String[] items = {"從主畫面移除", "往前移", "往後移", "應用程式資訊"};
        new AlertDialog.Builder(this, DLG).setTitle(a.label).setItems(items, (d, w) -> {
            int i = homeApps.indexOf(a.key);
            if (i < 0) return;
            if (w == 0) homeApps.remove(i);
            else if (w == 1 && i > 0) Collections.swap(homeApps, i, i - 1);
            else if (w == 2 && i < homeApps.size() - 1) Collections.swap(homeApps, i, i + 1);
            else if (w == 3) { openAppInfo(a); return; }
            saveState(); refreshHome();
        }).show();
    }

    void homeMenu() {
        String[] items = {"新增小工具到左滑頁", "更換桌布", "系統設定"};
        new AlertDialog.Builder(this, DLG).setItems(items, (d, w) -> {
            if (w == 0) showWidgetPicker();
            else if (w == 1) {
                try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "選擇桌布")); }
                catch (Exception ignored) { }
            } else {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
            }
        }).show();
    }

    // ───────────────────────── 左滑頁 ─────────────────────────

    View buildLeftPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0x99000000);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(16), dp(12), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("我的頁面", 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        editBtn = pill("編輯", v -> setEditMode(!editMode));
        header.addView(editBtn);
        col.addView(header);

        leftList = new LinearLayout(this);
        leftList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(MATCH, WRAP);
        llp.topMargin = dp(12);
        col.addView(leftList, llp);

        TextView add = pill("＋  新增項目", v -> addItemMenu());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(WRAP, WRAP);
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        alp.topMargin = dp(8);
        col.addView(add, alp);

        sv.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        return sv;
    }

    void setEditMode(boolean on) {
        editMode = on;
        refreshLeft();
    }

    void refreshLeft() {
        editBtn.setText(editMode ? "完成" : "編輯");
        leftList.removeAllViews();
        for (int i = 0; i < leftItems.size(); i++) {
            final JSONObject it = leftItems.get(i);
            String type = it.optString("type");
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.setBackground(round(0x26FFFFFF, 16));
            if (editMode) card.addView(editBar(i, it, type));

            if ("widget".equals(type)) {
                card.addView(widgetView(it));
            } else if ("apps".equals(type)) {
                String t = it.optString("title");
                if (!TextUtils.isEmpty(t)) {
                    TextView tv = text(t, 15);
                    tv.setPadding(dp(4), 0, 0, dp(6));
                    card.addView(tv);
                }
                ArrayList<App> list = new ArrayList<>();
                for (String k : appsOf(it)) { App a = appMap.get(k); if (a != null) list.add(a); }
                if (list.isEmpty()) {
                    TextView e = text("（還沒有 App，按「編輯」→「＋App」加入）", 13);
                    card.addView(e);
                }
                LinearLayout grid = new LinearLayout(this);
                grid.setOrientation(LinearLayout.VERTICAL);
                fillGrid(grid, list, 5, a -> groupAppMenu(it, a));
                card.addView(grid);
            } else if ("note".equals(type)) {
                EditText et = new EditText(this);
                et.setText(it.optString("text"));
                et.setHint("寫點什麼…");
                et.setTextColor(Color.WHITE);
                et.setHintTextColor(0x88FFFFFF);
                et.setTextSize(15);
                et.setBackground(null);
                et.setGravity(Gravity.TOP | Gravity.START);
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                et.setMinLines(2);
                et.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    public void afterTextChanged(Editable s) { put(it, "text", s.toString()); saveState(); }
                });
                card.addView(et);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.bottomMargin = dp(10);
            leftList.addView(card, lp);
        }
    }

    View editBar(final int i, final JSONObject it, final String type) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 0, 0, dp(8));
        bar.addView(smallBtn("↑", v -> moveItem(i, -1)));
        bar.addView(smallBtn("↓", v -> moveItem(i, 1)));
        if ("widget".equals(type)) {
            bar.addView(smallBtn("加高", v -> resizeWidget(it, 40)));
            bar.addView(smallBtn("減高", v -> resizeWidget(it, -40)));
        } else if ("apps".equals(type)) {
            bar.addView(smallBtn("＋App", v -> openDrawer(a -> {
                ArrayList<String> l = appsOf(it);
                if (!l.contains(a.key)) l.add(a.key);
                put(it, "apps", new JSONArray(l));
                saveState(); refreshLeft();
            })));
            bar.addView(smallBtn("改名", v -> renameGroup(it)));
        }
        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(smallBtn("刪除", v -> confirmRemove(it)));
        return bar;
    }

    void moveItem(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= leftItems.size()) return;
        Collections.swap(leftItems, i, j);
        saveState(); refreshLeft();
    }

    void resizeWidget(JSONObject it, int delta) {
        int h = Math.max(60, Math.min(800, it.optInt("h", 150) + delta));
        put(it, "h", h);
        saveState(); refreshLeft();
    }

    void renameGroup(final JSONObject it) {
        final EditText et = new EditText(this);
        et.setText(it.optString("title"));
        et.setSingleLine();
        new AlertDialog.Builder(this, DLG).setTitle("名稱（可留空）").setView(et)
                .setPositiveButton("確定", (d, w) -> { put(it, "title", et.getText().toString()); saveState(); refreshLeft(); })
                .setNegativeButton("取消", null).show();
    }

    void confirmRemove(final JSONObject it) {
        new AlertDialog.Builder(this, DLG).setMessage("確定刪除這個項目？")
                .setPositiveButton("刪除", (d, w) -> {
                    if ("widget".equals(it.optString("type"))) {
                        int id = it.optInt("id", -1);
                        if (id != -1) {
                            try { host.deleteAppWidgetId(id); } catch (Exception ignored) { }
                            widgetViews.remove(id);
                        }
                    }
                    leftItems.remove(it);
                    saveState(); refreshLeft();
                })
                .setNegativeButton("取消", null).show();
    }

    void groupAppMenu(final JSONObject it, final App a) {
        String[] items = {"從這組移除", "應用程式資訊"};
        new AlertDialog.Builder(this, DLG).setTitle(a.label).setItems(items, (d, w) -> {
            if (w == 0) {
                ArrayList<String> l = appsOf(it);
                l.remove(a.key);
                put(it, "apps", new JSONArray(l));
                saveState(); refreshLeft();
            } else openAppInfo(a);
        }).show();
    }

    void addItemMenu() {
        String[] items = {"Android 小工具（行事曆、天氣、記事…）", "App 捷徑組", "便條"};
        new AlertDialog.Builder(this, DLG).setTitle("新增到左滑頁").setItems(items, (d, w) -> {
            if (w == 0) showWidgetPicker();
            else if (w == 1) {
                final JSONObject g = obj("type", "apps", "title", "常用 App", "apps", new JSONArray());
                leftItems.add(g);
                saveState(); refreshLeft();
                openDrawer(a -> {
                    ArrayList<String> l = appsOf(g);
                    if (!l.contains(a.key)) l.add(a.key);
                    put(g, "apps", new JSONArray(l));
                    saveState(); refreshLeft();
                });
            } else {
                leftItems.add(obj("type", "note", "text", ""));
                saveState(); refreshLeft();
            }
        }).show();
    }

    // ───────────────────────── Android 小工具 ─────────────────────────

    View widgetView(JSONObject it) {
        int id = it.optInt("id", -1);
        int h = it.optInt("h", 150);
        AppWidgetProviderInfo info = id == -1 ? null : awm.getAppWidgetInfo(id);
        if (info == null) return text("（這個小工具已失效，可在「編輯」模式刪除）", 13);
        AppWidgetHostView v = widgetViews.get(id);
        if (v == null) {
            v = host.createView(this, id, info);
            widgetViews.put(id, v);
        }
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(h)));
        int wdp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density) - 40;
        try { v.updateAppWidgetSize(null, wdp, h, wdp, h); } catch (Exception ignored) { }
        return v;
    }

    void showWidgetPicker() {
        final PackageManager pm = getPackageManager();
        List<AppWidgetProviderInfo> all = awm.getInstalledProviders();
        if (all == null || all.isEmpty()) { toast("找不到可用的小工具"); return; }
        final ArrayList<AppWidgetProviderInfo> ps = new ArrayList<>();
        final Map<AppWidgetProviderInfo, String> names = new HashMap<>();
        for (AppWidgetProviderInfo info : all) {
            String pkg = info.provider.getPackageName();
            String app;
            try { app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
            catch (Exception e) { app = pkg; }
            String wl = String.valueOf(info.loadLabel(pm));
            names.put(info, app.equals(wl) ? app : app + "・" + wl);
            ps.add(info);
        }
        final Collator col = Collator.getInstance(Locale.TAIWAN);
        Collections.sort(ps, (x, y) -> col.compare(names.get(x), names.get(y)));
        String[] arr = new String[ps.size()];
        for (int i = 0; i < ps.size(); i++) arr[i] = names.get(ps.get(i));
        new AlertDialog.Builder(this, DLG).setTitle("選擇小工具")
                .setItems(arr, (d, w) -> bindWidget(ps.get(w))).show();
    }

    void bindWidget(AppWidgetProviderInfo info) {
        int id = host.allocateAppWidgetId();
        boolean ok;
        try { ok = awm.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null); }
        catch (Exception e) { ok = false; }
        if (ok) { configureOrAdd(id); return; }
        pendingWidgetId = id;
        Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try { startActivityForResult(i, REQ_BIND); }
        catch (Exception e) { host.deleteAppWidgetId(id); pendingWidgetId = -1; toast("系統不允許加入小工具"); }
    }

    void configureOrAdd(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) { host.deleteAppWidgetId(id); toast("小工具加入失敗"); return; }
        if (info.configure != null) {
            pendingWidgetId = id;
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null);
                return;
            } catch (Exception ignored) {
                pendingWidgetId = -1;
            }
        }
        addWidget(id);
    }

    void addWidget(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        float dens = getResources().getDisplayMetrics().density;
        int h = 150;
        if (info != null) h = Math.max(80, Math.min(500, Math.round(info.minHeight / dens) + 16));
        leftItems.add(obj("type", "widget", "id", id, "h", h));
        saveState(); refreshLeft();
        pager.snapTo(0);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_BIND && req != REQ_CONFIG) return;
        int id = data != null ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) : pendingWidgetId;
        pendingWidgetId = -1;
        if (id == -1) return;
        if (res != RESULT_OK) { host.deleteAppWidgetId(id); return; }
        if (req == REQ_BIND) configureOrAdd(id);
        else addWidget(id);
    }

    // ───────────────────────── App 抽屜 ─────────────────────────

    View buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(0xF0121212);
        d.setPadding(dp(12), dp(16), dp(12), dp(8));
        d.setClickable(true);

        drawerTitle = text("點選要加入的 App", 16);
        drawerTitle.setPadding(dp(4), 0, 0, dp(8));
        d.addView(drawerTitle);

        drawerSearch = new EditText(this);
        drawerSearch.setHint("搜尋 App");
        drawerSearch.setSingleLine();
        drawerSearch.setTextColor(Color.WHITE);
        drawerSearch.setHintTextColor(0x88FFFFFF);
        drawerSearch.setBackground(round(0x33FFFFFF, 24));
        drawerSearch.setPadding(dp(16), dp(10), dp(16), dp(10));
        drawerSearch.setImeOptions(EditorInfo.IME_ACTION_GO);
        drawerSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { drawerAdapter.filter(s.toString()); }
        });
        drawerSearch.setOnEditorActionListener((v, actionId, ev) -> {
            if (drawerAdapter.getCount() > 0) onDrawerTap(drawerAdapter.get(0));
            return true;
        });
        d.addView(drawerSearch, new LinearLayout.LayoutParams(MATCH, WRAP));

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(12));
        grid.setPadding(0, dp(12), 0, 0);
        grid.setClipToPadding(false);
        drawerAdapter = new DrawerAdapter();
        grid.setAdapter(drawerAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onDrawerTap(drawerAdapter.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pick != null) return false;
            appMenu(drawerAdapter.get(pos));
            return true;
        });
        d.addView(grid, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return d;
    }

    void onDrawerTap(App a) {
        if (pick != null) {
            Pick p = pick;
            closeDrawer();
            p.picked(a);
        } else {
            launch(a);
            closeDrawer();
        }
    }

    void openDrawer(Pick p) {
        pick = p;
        drawerTitle.setVisibility(p != null ? View.VISIBLE : View.GONE);
        drawerSearch.setText("");
        drawerAdapter.filter("");
        drawer.setVisibility(View.VISIBLE);
    }

    void closeDrawer() {
        pick = null;
        drawer.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(drawerSearch.getWindowToken(), 0);
    }

    class DrawerAdapter extends BaseAdapter {
        List<App> shown = new ArrayList<>();

        void filter(String q) {
            String s = q.trim().toLowerCase(Locale.ROOT);
            ArrayList<App> l = new ArrayList<>();
            for (App a : apps)
                if (s.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(s)
                        || a.cn.getPackageName().toLowerCase(Locale.ROOT).contains(s)) l.add(a);
            shown = l;
            notifyDataSetChanged();
        }

        App get(int i) { return shown.get(i); }
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout v = cv instanceof LinearLayout ? (LinearLayout) cv : iconCell();
            App a = shown.get(pos);
            ((ImageView) v.getChildAt(0)).setImageDrawable(a.icon);
            ((TextView) v.getChildAt(1)).setText(a.label);
            return v;
        }
    }

    // ───────────────────────── 小工具函式 ─────────────────────────

    interface AppAction { void run(App a); }

    LinearLayout iconCell() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp(2), dp(4), dp(2), dp(4));
        ImageView iv = new ImageView(this);
        v.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text("", 12);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        v.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        return v;
    }

    void fillGrid(LinearLayout container, List<App> list, int cols, final AppAction onLong) {
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            final App a = list.get(i);
            LinearLayout cell = iconCell();
            ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
            ((TextView) cell.getChildAt(1)).setText(a.label);
            cell.setOnClickListener(v -> launch(a));
            cell.setOnLongClickListener(v -> { onLong.run(a); return true; });
            row.addView(cell, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        if (row != null) {
            int rem = list.size() % cols;
            if (rem != 0) for (int k = rem; k < cols; k++)
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    TextView text(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        styleText(t);
        return t;
    }

    void styleText(TextView t) {
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(4, 0, 1, 0x99000000);
    }

    TextView pill(String s, View.OnClickListener l) {
        TextView t = text(s, 15);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setBackground(round(0x33FFFFFF, 20));
        t.setOnClickListener(l);
        return t;
    }

    TextView smallBtn(String s, View.OnClickListener l) {
        TextView t = text(s, 13);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(round(0x33FFFFFF, 12));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}

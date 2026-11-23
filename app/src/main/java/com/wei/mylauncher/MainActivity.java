package com.wei.mylauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
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
import java.text.SimpleDateFormat;
import java.util.Date;
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
        UserHandle user;
        boolean work;
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

    // LINE 訊息區塊
    LinearLayout lineList;
    String lineChat;              // null = 全部聊天室
    int lineLimit = 40;
    boolean lastListenerOk;

    /** 主畫面項目：String = App 的 key；JSONObject = 資料夾 {type:folder, name, apps:[key...]} */
    final ArrayList<Object> homeItems = new ArrayList<>();
    View folderPanel;
    TextView folderTitle;
    LinearLayout folderGrid;
    JSONObject openFolder;
    final ArrayList<JSONObject> leftItems = new ArrayList<>();
    final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    List<App> apps = new ArrayList<>();
    final Map<String, App> appMap = new HashMap<>();
    final ExecutorService bg = Executors.newSingleThreadExecutor();

    LauncherApps la;
    UserManager um;
    UserHandle workUser;          // Shelter / 公司的工作資料夾
    boolean workQuiet;            // 工作資料夾是否已暫停
    boolean showWork;             // App 抽屜目前在「工作」分頁
    TextView tabPersonal, tabWork, workToggle;
    View tabsRow;

    final BroadcastReceiver profileRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadApps(); }
    };

    final LauncherApps.Callback appsCb = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackageAdded(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { reloadApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reloadApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reloadApps(); }
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
        la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        um = (UserManager) getSystemService(Context.USER_SERVICE);
        host = new AppWidgetHost(this, HOST_ID);
        if (b != null) pendingWidgetId = b.getInt("pending", -1);
        loadState();

        root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        pager = new PagerView(this);
        pager.addPage(buildLeftPage());
        pager.addPage(buildHomePage());
        root.addView(pager, new FrameLayout.LayoutParams(MATCH, MATCH));
        folderPanel = buildFolderPanel();
        folderPanel.setVisibility(View.GONE);
        root.addView(folderPanel, new FrameLayout.LayoutParams(MATCH, MATCH));
        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);

        pager.setPageNow(1);
        refreshHome();
        refreshLeft();
        reloadApps();

        la.registerCallback(appsCb, new Handler(Looper.getMainLooper()));
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        f.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        f.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        f.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        f.addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(profileRx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(profileRx, f);
    }

    @Override protected void onStart() {
        super.onStart();
        try { host.startListening(); } catch (Exception ignored) { }
        LineListener.onNew = () -> { if (lineList != null) fillLineList(); };
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ok = listenerEnabled();
        if (ok != lastListenerOk) { lastListenerOk = ok; refreshLeft(); }   // 剛開好通知存取權 → 拿掉提示
        else if (lineList != null) fillLineList();
    }

    @Override protected void onStop() {
        super.onStop();
        try { host.stopListening(); } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        LineListener.onNew = null;
        try { unregisterReceiver(profileRx); } catch (Exception ignored) { }
        try { la.unregisterCallback(appsCb); } catch (Exception ignored) { }
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
        if (folderPanel.getVisibility() == View.VISIBLE) closeFolderPanel();
        if (wasInFront) {               // 已在桌面時再按 Home：回主畫面
            if (editMode) setEditMode(false);
            pager.snapTo(1);
        }
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (folderPanel.getVisibility() == View.VISIBLE) closeFolderPanel();
        else if (editMode) setEditMode(false);
        else pager.snapTo(1);
    }

    // ───────────────────────── 資料儲存 ─────────────────────────

    void loadState() {
        try {
            JSONArray a = new JSONArray(sp.getString("home", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Object o = a.get(i);
                if (o instanceof String || o instanceof JSONObject) homeItems.add(o);
            }
        } catch (JSONException ignored) { }
        if (!sp.contains("left")) {
            leftItems.add(obj("type", "note", "text",
                    "這是你的左滑頁。\n按右上「編輯」可調整，按下方「＋ 新增項目」加入小工具、App 捷徑或便條。"));
        } else {
            try {
                JSONArray a = new JSONArray(sp.getString("left", "[]"));
                for (int i = 0; i < a.length(); i++) leftItems.add(a.getJSONObject(i));
            } catch (JSONException ignored) { }
        }
        // 第一次更新到有 LINE 功能的版本：自動把「LINE 訊息」放在左滑頁最上面
        if (!sp.getBoolean("line_added", false)) {
            leftItems.add(0, obj("type", "line"));
            sp.edit().putBoolean("line_added", true).apply();
            saveState();
        }
    }

    void saveState() {
        JSONArray l = new JSONArray();
        for (JSONObject o : leftItems) l.put(o);
        JSONArray h = new JSONArray();
        for (Object o : homeItems) h.put(o);
        sp.edit().putString("home", h.toString())
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
            final ArrayList<App> list = new ArrayList<>();
            UserHandle me = Process.myUserHandle();
            UserHandle wu = null;
            boolean wq = false;
            List<UserHandle> profiles;
            try { profiles = la.getProfiles(); } catch (Exception e) { profiles = Collections.singletonList(me); }
            for (UserHandle u : profiles) {
                boolean work = !u.equals(me);
                long serial = um.getSerialNumberForUser(u);
                if (work) {
                    wu = u;
                    try { wq = um.isQuietModeEnabled(u); } catch (Exception ignored) { }
                }
                List<LauncherActivityInfo> infos;
                try { infos = la.getActivityList(null, u); } catch (Exception e) { continue; }
                for (LauncherActivityInfo li : infos) {
                    if (!work && li.getComponentName().getPackageName().equals(getPackageName())) continue;
                    App a = new App();
                    a.cn = li.getComponentName();
                    a.user = u;
                    a.work = work;
                    // 個人 App 的 key 維持舊格式，工作 App 加上使用者序號區分
                    a.key = work ? a.cn.flattenToString() + "#" + serial : a.cn.flattenToString();
                    a.label = String.valueOf(li.getLabel());
                    try { a.icon = li.getBadgedIcon(0); } catch (Exception e) { a.icon = li.getIcon(0); }
                    list.add(a);
                }
            }
            final Collator col = Collator.getInstance(Locale.TAIWAN);
            Collections.sort(list, (x, y) -> col.compare(x.label, y.label));
            final UserHandle fwu = wu;
            final boolean fwq = wq;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                workUser = fwu;
                workQuiet = fwq;
                if (workUser == null) showWork = false;
                updateTabs();
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
        if (a.work && workQuiet) {
            new AlertDialog.Builder(this, DLG).setMessage("工作應用程式目前已暫停，要開啟嗎？")
                    .setPositiveButton("開啟", (d, w) -> setWorkPaused(false))
                    .setNegativeButton("取消", null).show();
            return;
        }
        try {
            la.startMainActivity(a.cn, a.user, null, null);
            if (folderPanel != null && folderPanel.getVisibility() == View.VISIBLE) closeFolderPanel();
        } catch (Exception e) {
            toast("無法開啟 " + a.label);
        }
    }

    void openAppInfo(App a) {
        try { la.startAppDetailsActivity(a.cn, a.user, null, null); }
        catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", a.cn.getPackageName(), null)));
            } catch (Exception ignored) { }
        }
    }

    void uninstall(App a) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE,
                    Uri.fromParts("package", a.cn.getPackageName(), null));
            i.putExtra(Intent.EXTRA_USER, a.user);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    /** 暫停／開啟工作資料夾（需要「我的桌面」是預設桌面，Android 9 以上） */
    void setWorkPaused(boolean pause) {
        if (workUser == null) return;
        if (Build.VERSION.SDK_INT < 28) { toast("這個功能需要 Android 9 以上"); return; }
        try {
            um.requestQuietModeEnabled(pause, workUser);
        } catch (Exception e) {
            toast("無法切換，請先把「我的桌面」設為預設桌面");
        }
    }

    /** 從 App 抽屜長按出現的選單 */
    void appMenu(final App a) {
        String[] items = {"加到主畫面", "加到主畫面的資料夾…", "加到左滑頁（App 捷徑）", "應用程式資訊", "解除安裝"};
        new AlertDialog.Builder(this, DLG).setTitle(a.label).setItems(items, (d, w) -> {
            if (w == 0) {
                if (!homeItems.contains(a.key)) homeItems.add(a.key);
                saveState(); refreshHome(); toast("已加到主畫面");
            } else if (w == 1) {
                chooseFolder(f -> {
                    ArrayList<String> l = appsOf(f);
                    if (!l.contains(a.key)) l.add(a.key);
                    put(f, "apps", new JSONArray(l));
                    saveState(); refreshHome(); toast("已放進「" + f.optString("name") + "」");
                }, homeItems.size());
            } else if (w == 2) {
                JSONObject g = null;
                for (JSONObject o : leftItems) if ("apps".equals(o.optString("type"))) { g = o; break; }
                if (g == null) { g = obj("type", "apps", "title", "常用 App", "apps", new JSONArray()); leftItems.add(g); }
                ArrayList<String> l = appsOf(g);
                if (!l.contains(a.key)) l.add(a.key);
                put(g, "apps", new JSONArray(l));
                saveState(); refreshLeft(); toast("已加到左滑頁");
            } else if (w == 3) openAppInfo(a);
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

        homeHint = text("在「所有應用程式」裡長按 App，可以加到主畫面或資料夾", 13);
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
        ArrayList<View> cells = new ArrayList<>();
        for (Object o : homeItems) {
            if (o instanceof JSONObject) {
                cells.add(folderCell((JSONObject) o));
            } else {
                App a = appMap.get((String) o);
                if (a != null) cells.add(appCell(a, x -> homeAppMenu(x)));
            }
        }
        homeHint.setVisibility(homeItems.isEmpty() ? View.VISIBLE : View.GONE);
        fillCells(homeGrid, cells, 5);
        if (openFolder != null) refreshFolderPanel();
    }

    void homeAppMenu(final App a) {
        String[] items = {"放進資料夾…", "從主畫面移除", "往前移", "往後移", "應用程式資訊"};
        new AlertDialog.Builder(this, DLG).setTitle(a.label).setItems(items, (d, w) -> {
            final int i = homeItems.indexOf(a.key);
            if (i < 0) return;
            if (w == 0) {
                chooseFolder(f -> {
                    homeItems.remove(a.key);
                    ArrayList<String> l = appsOf(f);
                    if (!l.contains(a.key)) l.add(a.key);
                    put(f, "apps", new JSONArray(l));
                    saveState(); refreshHome();
                }, i);
                return;
            }
            if (w == 1) homeItems.remove(i);
            else if (w == 2 && i > 0) Collections.swap(homeItems, i, i - 1);
            else if (w == 3 && i < homeItems.size() - 1) Collections.swap(homeItems, i, i + 1);
            else if (w == 4) { openAppInfo(a); return; }
            saveState(); refreshHome();
        }).show();
    }

    // ───────────────────────── 資料夾 ─────────────────────────

    interface FolderAction { void run(JSONObject folder); }

    /** 選一個現有資料夾，或建立新資料夾（新資料夾放在主畫面第 insertAt 個位置） */
    void chooseFolder(final FolderAction then, final int insertAt) {
        final ArrayList<JSONObject> folders = new ArrayList<>();
        for (Object o : homeItems) if (o instanceof JSONObject) folders.add((JSONObject) o);
        String[] names = new String[folders.size() + 1];
        for (int i = 0; i < folders.size(); i++) names[i] = "📁 " + folders.get(i).optString("name");
        names[folders.size()] = "＋ 新資料夾…";
        new AlertDialog.Builder(this, DLG).setTitle("放進哪個資料夾？").setItems(names, (d, w) -> {
            if (w < folders.size()) { then.run(folders.get(w)); return; }
            askName("新資料夾名稱", "", name -> {
                JSONObject f = obj("type", "folder", "name", name, "apps", new JSONArray());
                homeItems.add(Math.max(0, Math.min(insertAt, homeItems.size())), f);
                then.run(f);
            });
        }).show();
    }

    interface NameAction { void run(String name); }

    void askName(String title, String current, final NameAction then) {
        final EditText et = new EditText(this);
        et.setText(current);
        et.setHint("例如：工作、社群、影音、工具");
        et.setSingleLine();
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this, DLG).setTitle(title).setView(et)
                .setPositiveButton("確定", (d, w) -> {
                    String n = et.getText().toString().trim();
                    then.run(n.isEmpty() ? "資料夾" : n);
                })
                .setNegativeButton("取消", null).show();
    }

    /** 資料夾圖示：最多顯示前 4 個 App 的小圖 */
    View folderCell(final JSONObject f) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(dp(2), dp(4), dp(2), dp(4));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackground(round(0x55FFFFFF, 14));
        ArrayList<String> keys = appsOf(f);
        ArrayList<App> shown = new ArrayList<>();
        for (String k : keys) { App a = appMap.get(k); if (a != null) shown.add(a); if (shown.size() == 4) break; }
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                ImageView iv = new ImageView(this);
                int idx = r * 2 + c;
                if (idx < shown.size()) iv.setImageDrawable(shown.get(idx).icon);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(iv, lp);
            }
            box.addView(row);
        }
        v.addView(box, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView t = text(f.optString("name"), 12);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        v.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));

        v.setOnClickListener(x -> openFolderPanel(f));
        v.setOnLongClickListener(x -> { folderMenu(f); return true; });
        return v;
    }

    void folderMenu(final JSONObject f) {
        String[] items = {"打開", "重新命名", "往前移", "往後移", "解散（App 放回主畫面）", "刪除資料夾"};
        new AlertDialog.Builder(this, DLG).setTitle("📁 " + f.optString("name")).setItems(items, (d, w) -> {
            int i = homeItems.indexOf(f);
            if (i < 0) return;
            if (w == 0) { openFolderPanel(f); return; }
            if (w == 1) { askName("重新命名", f.optString("name"), n -> { put(f, "name", n); saveState(); refreshHome(); }); return; }
            if (w == 2 && i > 0) Collections.swap(homeItems, i, i - 1);
            else if (w == 3 && i < homeItems.size() - 1) Collections.swap(homeItems, i, i + 1);
            else if (w == 4) {
                homeItems.remove(i);
                int pos = i;
                for (String k : appsOf(f)) if (!homeItems.contains(k)) homeItems.add(pos++, k);
            } else if (w == 5) {
                new AlertDialog.Builder(this, DLG)
                        .setMessage("刪除資料夾「" + f.optString("name") + "」？\n（只會移除桌面上的捷徑，App 本身不會被刪除）")
                        .setPositiveButton("刪除", (d2, w2) -> { homeItems.remove(f); saveState(); refreshHome(); })
                        .setNegativeButton("取消", null).show();
                return;
            }
            saveState(); refreshHome();
        }).show();
    }

    View buildFolderPanel() {
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0x99000000);
        dim.setOnClickListener(v -> closeFolderPanel());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(12));
        card.setBackground(round(0xF0202124, 24));
        card.setClickable(true);   // 點卡片本身不會關閉

        folderTitle = text("", 20);
        folderTitle.setTypeface(Typeface.DEFAULT_BOLD);
        folderTitle.setGravity(Gravity.CENTER);
        folderTitle.setPadding(0, 0, 0, dp(12));
        folderTitle.setOnClickListener(v -> {
            final JSONObject f = openFolder;
            if (f != null) askName("重新命名", f.optString("name"), n -> { put(f, "name", n); saveState(); refreshHome(); });
        });
        card.addView(folderTitle);

        ScrollView sv = new ScrollView(this);
        folderGrid = new LinearLayout(this);
        folderGrid.setOrientation(LinearLayout.VERTICAL);
        sv.addView(folderGrid, new FrameLayout.LayoutParams(MATCH, WRAP));
        card.addView(sv, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.setPadding(0, dp(12), 0, 0);
        btns.addView(smallBtn("＋ 加入 App", v -> {
            final JSONObject f = openFolder;
            if (f == null) return;
            openDrawer(a -> {
                ArrayList<String> l = appsOf(f);
                if (!l.contains(a.key)) l.add(a.key);
                put(f, "apps", new JSONArray(l));
                homeItems.remove(a.key);   // 原本在主畫面上的就搬進來
                saveState(); refreshHome();
            });
        }));
        btns.addView(smallBtn("關閉", v -> closeFolderPanel()));
        card.addView(btns);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER);
        lp.setMargins(dp(20), dp(40), dp(20), dp(40));
        dim.addView(card, lp);
        return dim;
    }

    void openFolderPanel(JSONObject f) {
        openFolder = f;
        refreshFolderPanel();
        folderPanel.setVisibility(View.VISIBLE);
    }

    void closeFolderPanel() {
        openFolder = null;
        folderPanel.setVisibility(View.GONE);
    }

    void refreshFolderPanel() {
        final JSONObject f = openFolder;
        if (f == null) return;
        if (!homeItems.contains(f)) { closeFolderPanel(); return; }
        folderTitle.setText(f.optString("name") + "  ✎");
        folderGrid.removeAllViews();
        ArrayList<View> cells = new ArrayList<>();
        for (String k : appsOf(f)) {
            App a = appMap.get(k);
            if (a != null) cells.add(appCell(a, x -> folderAppMenu(f, x)));
        }
        if (cells.isEmpty()) {
            TextView e = text("資料夾是空的，按下方「＋ 加入 App」", 13);
            e.setGravity(Gravity.CENTER);
            folderGrid.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        fillCells(folderGrid, cells, 4);
    }

    void folderAppMenu(final JSONObject f, final App a) {
        String[] items = {"移到主畫面", "從資料夾移除", "往前移", "往後移", "應用程式資訊"};
        new AlertDialog.Builder(this, DLG).setTitle(a.label).setItems(items, (d, w) -> {
            ArrayList<String> l = appsOf(f);
            int i = l.indexOf(a.key);
            if (i < 0) return;
            if (w == 0) {
                l.remove(i);
                int pos = homeItems.indexOf(f) + 1;
                if (!homeItems.contains(a.key)) homeItems.add(pos, a.key);
            } else if (w == 1) l.remove(i);
            else if (w == 2 && i > 0) Collections.swap(l, i, i - 1);
            else if (w == 3 && i < l.size() - 1) Collections.swap(l, i, i + 1);
            else if (w == 4) { openAppInfo(a); return; }
            put(f, "apps", new JSONArray(l));
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
        lineList = null;
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
            } else if ("line".equals(type)) {
                card.addView(lineCard());
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
        String[] items = {"Android 小工具（行事曆、天氣、記事…）", "App 捷徑組", "便條", "LINE 訊息"};
        new AlertDialog.Builder(this, DLG).setTitle("新增到左滑頁").setItems(items, (d, w) -> {
            if (w == 0) showWidgetPicker();
            else if (w == 3) {
                leftItems.add(0, obj("type", "line"));
                saveState(); refreshLeft();
            }
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

    // ───────────────────────── LINE 訊息 ─────────────────────────

    boolean listenerEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName() + "/");
    }

    void openListenerSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, LineListener.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try { startActivity(i); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    View lineCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text("LINE 訊息", 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        header.addView(smallBtn(lineChat == null ? "全部聊天室 ▾" : lineChat + " ▾", v -> chooseLineChat()));
        header.addView(smallBtn("⋯", v -> lineMenu()));
        box.addView(header);

        if (!listenerEnabled()) {
            TextView w = text("還沒開啟通知存取權，LINE 訊息不會被記錄。\n"
                    + "按下面按鈕，把「我的桌面」打開。如果開關是灰色按不動：到「應用程式資訊」→ 右上角 ⋮ →「允許受限制的設定」，再回來打開。", 13);
            w.setTextColor(0xFFFFD27F);
            w.setPadding(0, dp(8), 0, dp(6));
            box.addView(w);
            LinearLayout b = new LinearLayout(this);
            b.addView(smallBtn("前往開啟", v -> openListenerSettings()));
            b.addView(smallBtn("應用程式資訊", v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null)));
                } catch (Exception ignored) { }
            }));
            box.addView(b);
        }

        lineList = new LinearLayout(this);
        lineList.setOrientation(LinearLayout.VERTICAL);
        lineList.setPadding(0, dp(6), 0, 0);
        box.addView(lineList, new LinearLayout.LayoutParams(MATCH, WRAP));
        fillLineList();
        return box;
    }

    void fillLineList() {
        if (lineList == null) return;
        lineList.removeAllViews();
        List<LineStore.Msg> ms = LineStore.get(this).recent(lineChat, lineLimit);
        if (ms.isEmpty()) {
            TextView e = text(lineChat == null ? "還沒有訊息。之後收到的 LINE 會一則一則出現在這裡。" : "這個聊天室沒有紀錄。", 13);
            e.setTextColor(0xAAFFFFFF);
            e.setPadding(0, dp(6), 0, dp(6));
            lineList.addView(e);
            return;
        }
        SimpleDateFormat today = new SimpleDateFormat("HH:mm", Locale.TAIWAN);
        SimpleDateFormat other = new SimpleDateFormat("M/d HH:mm", Locale.TAIWAN);
        String todayStr = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        SimpleDateFormat dayKey = new SimpleDateFormat("yyyyMMdd", Locale.US);
        for (final LineStore.Msg m : ms) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            row.setBackground(round(0x14FFFFFF, 10));

            Date d = new Date(m.time);
            String when = (todayStr.equals(dayKey.format(d)) ? today : other).format(d);
            StringBuilder meta = new StringBuilder();
            if (lineChat == null) meta.append(m.chat);
            if (!m.sender.equals(m.chat)) meta.append(meta.length() > 0 ? "・" : "").append(m.sender);
            if (m.work) meta.append(" 💼");
            meta.append("   ").append(when);
            TextView mt = text(meta.toString(), 12);
            mt.setTextColor(0xAAFFFFFF);
            row.addView(mt);

            TextView body = text(m.body, 15);
            body.setPadding(0, dp(2), 0, 0);
            row.addView(body);

            row.setOnClickListener(v -> openLineChat(m.chat));
            row.setOnLongClickListener(v -> { lineMsgMenu(m); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.bottomMargin = dp(6);
            lineList.addView(row, lp);
        }
        if (ms.size() >= lineLimit) {
            TextView more = smallBtn("顯示更多", v -> { lineLimit += 50; fillLineList(); });
            lineList.addView(more);
        }
    }

    void chooseLineChat() {
        final List<String[]> chats = LineStore.get(this).chats();
        String[] names = new String[chats.size() + 1];
        names[0] = "全部聊天室";
        for (int i = 0; i < chats.size(); i++) names[i + 1] = chats.get(i)[0] + "（" + chats.get(i)[1] + "）";
        new AlertDialog.Builder(this, DLG).setTitle("選擇聊天室").setItems(names, (d, w) -> {
            lineChat = w == 0 ? null : chats.get(w - 1)[0];
            lineLimit = 40;
            refreshLeft();
        }).show();
    }

    void lineMsgMenu(final LineStore.Msg m) {
        String[] items = {"複製文字", "只看「" + m.chat + "」", "刪除這則"};
        new AlertDialog.Builder(this, DLG).setItems(items, (d, w) -> {
            if (w == 0) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("LINE", m.body));
                toast("已複製");
            } else if (w == 1) {
                lineChat = m.chat; lineLimit = 40; refreshLeft();
            } else {
                LineStore.get(this).delete(m.id); fillLineList();
            }
        }).show();
    }

    void lineMenu() {
        String[] items = {"通知存取權設定", "診斷資訊（複製給 Claude）", "清除全部 LINE 紀錄"};
        new AlertDialog.Builder(this, DLG).setItems(items, (d, w) -> {
            if (w == 0) openListenerSettings();
            else if (w == 1) {
                StringBuilder sb = new StringBuilder();
                synchronized (LineListener.diag) { for (String s : LineListener.diag) sb.append(s).append("\n"); }
                final String txt = sb.length() == 0 ? "（目前沒有資料：收到幾則 LINE 之後再看）" : sb.toString();
                new AlertDialog.Builder(this, DLG).setTitle("最近的 LINE 通知原始內容").setMessage(txt)
                        .setPositiveButton("複製", (d2, w2) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("diag", txt));
                            toast("已複製");
                        })
                        .setNegativeButton("關閉", null).show();
            } else {
                new AlertDialog.Builder(this, DLG).setMessage("清除全部 LINE 紀錄？無法復原。")
                        .setPositiveButton("清除", (d2, w2) -> { LineStore.get(this).clear(); lineChat = null; refreshLeft(); })
                        .setNegativeButton("取消", null).show();
            }
        }).show();
    }

    /** 點訊息：盡量直接打開那個聊天室，不行就打開 LINE */
    void openLineChat(String chat) {
        PendingIntent pi = LineListener.chatIntents.get(chat);
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pi.send(this, 0, null, null, null, null, o.toBundle());
                } else {
                    pi.send();
                }
                return;
            } catch (Exception ignored) { }
        }
        Intent i = getPackageManager().getLaunchIntentForPackage(LineListener.LINE);
        if (i != null) {
            try { startActivity(i); return; } catch (Exception ignored) { }
        }
        toast("找不到 LINE");
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
        final ArrayList<AppWidgetProviderInfo> ps = new ArrayList<>();
        final Map<AppWidgetProviderInfo, String> names = new HashMap<>();
        UserHandle me = Process.myUserHandle();
        List<UserHandle> profiles;
        try { profiles = la.getProfiles(); } catch (Exception e) { profiles = Collections.singletonList(me); }
        for (UserHandle u : profiles) {
            boolean work = !u.equals(me);
            List<AppWidgetProviderInfo> all;
            try { all = awm.getInstalledProvidersForProfile(u); } catch (Exception e) { continue; }
            if (all == null) continue;
            for (AppWidgetProviderInfo info : all) {
                String pkg = info.provider.getPackageName();
                String app;
                try { app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
                catch (Exception e) { app = pkg; }
                String wl = String.valueOf(info.loadLabel(pm));
                String name = app.equals(wl) ? app : app + "・" + wl;
                names.put(info, work ? "【工作】" + name : name);
                ps.add(info);
            }
        }
        if (ps.isEmpty()) { toast("找不到可用的小工具"); return; }
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

        // 個人／工作分頁（有 Shelter 等工作資料夾時才出現）
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(0, 0, 0, dp(10));
        tabPersonal = smallBtn("個人", v -> { showWork = false; updateTabs(); drawerAdapter.filter(drawerSearch.getText().toString()); });
        tabWork = smallBtn("工作", v -> { showWork = true; updateTabs(); drawerAdapter.filter(drawerSearch.getText().toString()); });
        tabs.addView(tabPersonal);
        tabs.addView(tabWork);
        View tsp = new View(this);
        tabs.addView(tsp, new LinearLayout.LayoutParams(0, 1, 1f));
        workToggle = smallBtn("", v -> setWorkPaused(!workQuiet));
        tabs.addView(workToggle);
        tabsRow = tabs;
        d.addView(tabs);

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

    void updateTabs() {
        if (tabsRow == null) return;
        tabsRow.setVisibility(workUser != null ? View.VISIBLE : View.GONE);
        tabPersonal.setBackground(round(showWork ? 0x22FFFFFF : 0x88FFFFFF, 12));
        tabWork.setBackground(round(showWork ? 0x88FFFFFF : 0x22FFFFFF, 12));
        workToggle.setVisibility(showWork ? View.VISIBLE : View.GONE);
        workToggle.setText(workQuiet ? "▶ 開啟工作 App" : "⏸ 暫停工作 App");
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
            boolean onlyWork = showWork && workUser != null;
            for (App a : apps)
                if (a.work == onlyWork)
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
            v.setAlpha(a.work && workQuiet ? 0.4f : 1f);
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

    View appCell(final App a, final AppAction onLong) {
        LinearLayout cell = iconCell();
        ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
        ((TextView) cell.getChildAt(1)).setText(a.label);
        cell.setAlpha(a.work && workQuiet ? 0.4f : 1f);
        cell.setOnClickListener(v -> launch(a));
        cell.setOnLongClickListener(v -> { onLong.run(a); return true; });
        return cell;
    }

    void fillGrid(LinearLayout container, List<App> list, int cols, final AppAction onLong) {
        ArrayList<View> cells = new ArrayList<>();
        for (App a : list) cells.add(appCell(a, onLong));
        fillCells(container, cells, cols);
    }

    void fillCells(LinearLayout container, List<View> cells, int cols) {
        LinearLayout row = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            row.addView(cells.get(i), new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        if (row != null) {
            int rem = cells.size() % cols;
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

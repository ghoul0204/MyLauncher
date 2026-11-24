package com.wei.mylauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 讀取 LINE 的通知，把每一則訊息存進 LineStore。
 * LINE 同一個聊天室只會留一則（最新的）通知，所以要在通知跳出來的當下記下來。
 */
public class LineListener extends NotificationListenerService {

    static final String LINE = "jp.naver.line.android";

    /** 有新訊息時通知桌面更新畫面 */
    static volatile Runnable onNew;
    /** 每個聊天室最近一次通知的「點擊動作」，用來直接打開那個聊天室 */
    static final Map<String, PendingIntent> chatIntents = new ConcurrentHashMap<>();
    /** 最近 10 則 LINE 通知的原始內容（診斷用，只存在記憶體） */
    static final ArrayDeque<String> diag = new ArrayDeque<>();

    // ── LINE 來電接聽按鈕 ──
    static final String TEST_KEY = "test";
    static final String CALL_CH = "line_call";
    static final int CALL_NOTIF_ID = 7001;
    static volatile String callKey, callName;
    static volatile PendingIntent callAnswer, callDecline, callScreen;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try { handle(sbn); } catch (Throwable ignored) { }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // LINE 的來電通知消失（接起來、掛斷、未接）→ 收掉我們的接聽畫面
        try {
            if (sbn.getKey().equals(callKey)) {
                clearCall(this);
                CallActivity.closeIfOpen();
            }
        } catch (Throwable ignored) { }
    }

    private void handle(StatusBarNotification sbn) {
        if (!LINE.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        addDiag(sbn, n, ex);
        if (handleCall(sbn, n, ex)) return;   // 來電通知不當成訊息記錄
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;   // 通話中等
        if (ex == null) return;

        boolean work = !sbn.getUser().equals(Process.myUserHandle());
        CharSequence conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        String chat = str(conv != null ? conv : ex.getCharSequence(Notification.EXTRA_TITLE));
        if (chat.isEmpty()) chat = "LINE";
        if (n.contentIntent != null) chatIntents.put(chat, n.contentIntent);

        LineStore db = LineStore.get(this);
        boolean added = false;
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            // 對話型通知：裡面通常帶著最近好幾則訊息，逐則存（重複的會自動略過）
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                String text = str(b.getCharSequence("text"));
                long time = b.getLong("time", sbn.getPostTime());
                String sender = str(b.getCharSequence("sender"));
                if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                    Object pp = b.get("sender_person");
                    if (pp instanceof Person) sender = str(((Person) pp).getName());
                }
                if (sender.isEmpty()) sender = "我";
                String type = b.getString("type");
                if (text.isEmpty() && type != null) text = type.startsWith("image") ? "[圖片]" : "[檔案]";
                if (text.isEmpty()) continue;
                LineStore.Msg m = msg(chat, sender, text, time, work);
                if (db.add(m, chat + "|" + sender + "|" + time + "|" + text)) added = true;
            }
        } else {
            // 一般通知：標題＋內容
            String text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) return;
            long t = sbn.getPostTime();
            LineStore.Msg m = msg(chat, chat, text, t, work);
            added = db.add(m, chat + "|" + t + "|" + text);
        }
        if (added) {
            final Runnable r = onNew;
            if (r != null) new Handler(Looper.getMainLooper()).post(r);
        }
    }

    /** 是 LINE 通話通知就回傳 true；是「來電」就顯示我們自己的接聽按鈕 */
    private boolean handleCall(StatusBarNotification sbn, Notification n, Bundle ex) {
        PendingIntent answer = null, decline = null;
        if (ex != null) {
            Object a = ex.get("android.answerIntent");
            if (a instanceof PendingIntent) answer = (PendingIntent) a;
            Object d = ex.get("android.declineIntent");
            if (d instanceof PendingIntent) decline = (PendingIntent) d;
            if (decline == null) {
                Object h = ex.get("android.hangUpIntent");
                if (h instanceof PendingIntent) decline = (PendingIntent) h;
            }
        }
        if (n.actions != null) {
            for (Notification.Action act : n.actions) {
                if (act == null || act.actionIntent == null) continue;
                String t = act.title == null ? "" : act.title.toString().toLowerCase(Locale.ROOT);
                if (answer == null && (t.contains("接聽") || t.contains("接听") || t.contains("應答") || t.contains("応答")
                        || t.contains("接受") || t.contains("answer") || t.contains("accept"))) {
                    answer = act.actionIntent;
                } else if (decline == null && (t.contains("拒") || t.contains("掛斷") || t.contains("挂断")
                        || t.contains("decline") || t.contains("reject"))) {
                    decline = act.actionIntent;
                }
            }
        }
        String template = ex == null ? null : ex.getString(Notification.EXTRA_TEMPLATE);
        boolean isCall = Notification.CATEGORY_CALL.equals(n.category)
                || (template != null && template.contains("CallStyle"));
        if (!isCall && answer == null) return false;          // 不是通話
        int callType = ex == null ? 0 : ex.getInt("android.callType", 0);   // 1 = 來電, 2 = 通話中
        boolean incoming = callType == 1 || (callType == 0 && (answer != null || n.fullScreenIntent != null));
        if (!incoming) return true;                            // 通話中的通知：不處理
        if (!getSharedPreferences("launcher", MODE_PRIVATE).getBoolean("call_helper", true)) return true;

        String name = ex == null ? "" : str(ex.getCharSequence(Notification.EXTRA_TITLE));
        if (ex != null && Build.VERSION.SDK_INT >= 28) {
            Object p = ex.get("android.callPerson");
            if (p instanceof Person && ((Person) p).getName() != null) name = str(((Person) p).getName());
        }
        callKey = sbn.getKey();
        callName = name.isEmpty() ? "LINE 來電" : name;
        callAnswer = answer;
        callDecline = decline;
        callScreen = n.fullScreenIntent != null ? n.fullScreenIntent : n.contentIntent;
        showCallNotification(this);
        return true;
    }

    /** 顯示「接聽／拒絕」通知；鎖定時會直接跳出全螢幕接聽畫面 */
    static void showCallNotification(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CALL_CH, "LINE 來電接聽按鈕", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);             // 鈴聲由 LINE 自己響
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent full = PendingIntent.getActivity(c, 1,
                new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl);
        PendingIntent ans = PendingIntent.getActivity(c, 2,
                new Intent(c, CallActivity.class).setAction("answer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl);
        PendingIntent dec = PendingIntent.getActivity(c, 3,
                new Intent(c, CallActivity.class).setAction("decline").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl);

        Notification.Builder b = new Notification.Builder(c, CALL_CH)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(callName)
                .setContentText(TEST_KEY.equals(callKey) ? "LINE 來電（測試）" : "LINE 來電：點這裡接聽")
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(full)
                .setFullScreenIntent(full, true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, android.R.drawable.sym_call_missed), "拒絕", dec).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, android.R.drawable.sym_call_incoming), "接聽", ans).build());
        try { nm.notify(CALL_NOTIF_ID, b.build()); } catch (Exception ignored) { }

        // 有「顯示在其他 App 上層」權限時，直接把接聽畫面叫出來（避開三星「簡要」彈出樣式沒有按鈕的問題）
        if (Settings.canDrawOverlays(c)) {
            try { c.startActivity(new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }

    static void clearCall(Context c) {
        callKey = null;
        callAnswer = callDecline = callScreen = null;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(CALL_NOTIF_ID);
    }

    /** 設定頁的「測試」：不用真的有人打來，就能確認接聽畫面會不會跳出來 */
    static void testCall(Context c) {
        callKey = TEST_KEY;
        callName = "測試來電";
        callAnswer = callDecline = callScreen = null;
        showCallNotification(c);
    }

    private static LineStore.Msg msg(String chat, String sender, String body, long time, boolean work) {
        LineStore.Msg m = new LineStore.Msg();
        m.chat = chat;
        m.sender = sender;
        m.body = body;
        m.time = time;
        m.work = work;
        return m;
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    private static void addDiag(StatusBarNotification sbn, Notification n, Bundle ex) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(new Date()))
                .append(" id=").append(sbn.getId())
                .append(" summary=").append((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                .append(" style=").append(ex == null ? null : ex.getString(Notification.EXTRA_TEMPLATE))
                .append('\n');
        if (ex != null) {
            for (String k : ex.keySet()) {
                Object v;
                try { v = ex.get(k); } catch (Throwable t) { v = "?"; }
                if (v instanceof Parcelable[]) {
                    sb.append("  ").append(k).append(" = [").append(((Parcelable[]) v).length).append("]\n");
                    for (Parcelable p : (Parcelable[]) v) {
                        if (p instanceof Bundle) {
                            Bundle b = (Bundle) p;
                            sb.append("     ");
                            for (String bk : b.keySet()) {
                                Object bv;
                                try { bv = b.get(bk); } catch (Throwable t) { bv = "?"; }
                                sb.append(bk).append('=').append(cut(bv)).append("; ");
                            }
                            sb.append('\n');
                        }
                    }
                } else {
                    sb.append("  ").append(k).append(" = ").append(cut(v)).append('\n');
                }
            }
        }
        synchronized (diag) {
            diag.addFirst(sb.toString());
            while (diag.size() > 10) diag.removeLast();
        }
    }

    private static String cut(Object v) {
        String s = String.valueOf(v);
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}

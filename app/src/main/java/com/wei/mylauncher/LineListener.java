package com.wei.mylauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
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

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try { handle(sbn); } catch (Throwable ignored) { }
    }

    private void handle(StatusBarNotification sbn) {
        if (!LINE.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        addDiag(sbn, n, ex);
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

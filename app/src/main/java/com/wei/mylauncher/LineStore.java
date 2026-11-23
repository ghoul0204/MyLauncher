package com.wei.mylauncher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** LINE 訊息資料庫：每則訊息一筆，只存在手機裡。 */
public class LineStore extends SQLiteOpenHelper {

    public static class Msg {
        public long id, time;
        public String chat, sender, body;
        public boolean work;
    }

    private static LineStore inst;

    public static synchronized LineStore get(Context c) {
        if (inst == null) inst = new LineStore(c.getApplicationContext());
        return inst;
    }

    private LineStore(Context c) { super(c, "line.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE msg(_id INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT, sender TEXT, body TEXT,"
                + " time INTEGER, work INTEGER DEFAULT 0, uniq TEXT UNIQUE)");
        db.execSQL("CREATE INDEX idx_time ON msg(time)");
        db.execSQL("CREATE INDEX idx_chat ON msg(chat, time)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    /** 新增一則；重複的（同一則訊息被 LINE 重新發通知）會略過。回傳是否真的新增 */
    public synchronized boolean add(Msg m, String uniq) {
        SQLiteDatabase db = getWritableDatabase();
        // 同聊天室、同人、同內容、時間差 1 分鐘內 → 視為同一則
        try (Cursor c = db.rawQuery("SELECT 1 FROM msg WHERE chat=? AND sender=? AND body=? AND ABS(time-?)<60000 LIMIT 1",
                new String[]{m.chat, m.sender, m.body, String.valueOf(m.time)})) {
            if (c.moveToFirst()) return false;
        }
        ContentValues v = new ContentValues();
        v.put("chat", m.chat);
        v.put("sender", m.sender);
        v.put("body", m.body);
        v.put("time", m.time);
        v.put("work", m.work ? 1 : 0);
        v.put("uniq", uniq);
        long id = db.insertWithOnConflict("msg", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id != -1 && id % 200 == 0) {
            // 最多保留 5000 則
            db.execSQL("DELETE FROM msg WHERE _id NOT IN (SELECT _id FROM msg ORDER BY time DESC LIMIT 5000)");
        }
        return id != -1;
    }

    public synchronized Msg last(String chat) {
        List<Msg> l = recent(chat, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** chat 為 null 時取全部聊天室 */
    public synchronized List<Msg> recent(String chat, int limit) {
        ArrayList<Msg> out = new ArrayList<>();
        String sql = "SELECT _id, chat, sender, body, time, work FROM msg"
                + (chat != null ? " WHERE chat=?" : "") + " ORDER BY time DESC, _id DESC LIMIT " + limit;
        try (Cursor c = getReadableDatabase().rawQuery(sql, chat != null ? new String[]{chat} : null)) {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id = c.getLong(0);
                m.chat = c.getString(1);
                m.sender = c.getString(2);
                m.body = c.getString(3);
                m.time = c.getLong(4);
                m.work = c.getInt(5) != 0;
                out.add(m);
            }
        }
        return out;
    }

    /** 聊天室清單（最近有訊息的排前面）：{名稱, 則數} */
    public synchronized List<String[]> chats() {
        ArrayList<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT chat, COUNT(*) FROM msg GROUP BY chat ORDER BY MAX(time) DESC", null)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), String.valueOf(c.getInt(1))});
        }
        return out;
    }

    public synchronized void delete(long id) {
        getWritableDatabase().delete("msg", "_id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void clear() {
        getWritableDatabase().delete("msg", null, null);
    }
}

package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 10.106 — этап 3г «Сотрудники и пункты» (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
 *
 * Сотрудник (принял приглашение, право «Отвечает в чате»): «Мой пункт» → «Чат пункта» — переписки клиентов по
 * инструментам своих пунктов (имя клиента, инструмент, пункт, последнее сообщение, непрочитанные) → переписка:
 * сообщения клиента слева, ответы проката (владельца и сотрудников) справа, «Вы» — у своих; внизу поле и «Отправить».
 * Ответ уходит от имени проката в ту же переписку — клиент видит его как обычно (как ответ владельца), сотрудник клиенту
 * не виден. Голосовое и фото клиента — открываются системным проигрывателем / просмотром; сам сотрудник пишет текстом.
 * «Новое сообщение · инструмент» — при открытом окне каждые полминуты, при закрытом — фоновая проверка (переключатель
 * «Сообщения»); нажатие открывает эту переписку.
 *
 * Всё — функциями базы (SQL 20261031_staff_chat.sql): iva_staff_chats, iva_staff_chat, iva_staff_chat_read,
 * iva_staff_chat_send. Почты клиента приложение сотрудника не видит — только метку (md5). Без SQL — «заработает после
 * обновления базы». Новых вставок в smali нет: вызовы — из IvaStaffPatch («Мой пункт») и IvaNotifyPatch (tick, bgRun, route).
 */
public class IvaStaffChatPatch {
    static final String CHATS_RPC = "/rest/v1/rpc/iva_staff_chats", THREAD_RPC = "/rest/v1/rpc/iva_staff_chat";
    static final String READ_RPC = "/rest/v1/rpc/iva_staff_chat_read", SEND_RPC = "/rest/v1/rpc/iva_staff_chat_send";
    // 10.120: переписки с пунктом выдачи («Спросить в чате» на странице проката, SQL 20261111) — «pt:<пункт>»
    static final String P_CHATS_RPC = "/rest/v1/rpc/iva_staff_point_chats", P_THREAD_RPC = "/rest/v1/rpc/iva_staff_point_chat";
    static final String P_READ_RPC = "/rest/v1/rpc/iva_staff_point_chat_read", P_SEND_RPC = "/rest/v1/rpc/iva_staff_point_chat_send";
    static final String POINT_ASK = "Вопрос пункту";
    static final String TITLE = "Чат пункта", OPEN = "staffchat";
    static final String NO_DB = "Чат пункта заработает после обновления базы (SQL 10.106)";
    static final int ID_NOTE = 7207, MAX_LEN = 2000;
    static final long NO_DB_PAUSE_MS = 30L * 60000L, THREAD_TICK_MS = 15000L;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    /** Проверять ли чат: принятый сотрудник с правом «Отвечает в чате». */
    static boolean want(String status, boolean[] r) {
        return "active".equals(status) && r != null && r.length >= 3 && r[2];
    }

    static boolean isTool(String id) { return id != null && id.trim().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); }

    static boolean isPeer(String p) { return p != null && p.trim().matches("[0-9a-f]{32}"); }

    /** Переписка: по инструменту (uuid) или с пунктом («pt:uuid», 10.120). */
    static boolean isThread(String t) { return isTool(t) || IvaProkatPatch.isKey(t == null ? "" : t.trim()); }

    static boolean isPt(String t) { return t != null && IvaProkatPatch.isKey(t.trim()); }

    static String threadRpc(String t) { return isPt(t) ? P_THREAD_RPC : THREAD_RPC; }
    static String readRpc(String t) { return isPt(t) ? P_READ_RPC : READ_RPC; }
    static String sendRpc(String t) { return isPt(t) ? P_SEND_RPC : SEND_RPC; }

    /** {"p_tool":"…","p_peer":"…"} — только номер инструмента (uuid) и метка клиента (md5); иначе null. */
    static String threadBody(String tool, String peer) {
        if (isPt(tool) && isPeer(peer))
            return "{\"p_point\":\"" + IvaProkatPatch.idOf(tool.trim()) + "\",\"p_peer\":\"" + peer.trim() + "\"}";
        if (!isTool(tool) || !isPeer(peer)) return null;
        return "{\"p_tool\":\"" + tool.trim().toLowerCase(Locale.ROOT) + "\",\"p_peer\":\"" + peer.trim() + "\"}";
    }

    /** Тело ответа; пустое или длиннее 2000 — null (поле подскажет само). */
    static String sendBody(String tool, String peer, String text) {
        String b = threadBody(tool, peer), t = text == null ? "" : text.trim();
        if (b == null || t.isEmpty() || t.length() > MAX_LEN) return null;
        return b.substring(0, b.length() - 1) + ",\"p_text\":" + IvaNotifyPatch.jsonStr(t) + "}";
    }

    /** Что не так с текстом до отправки: «» — можно отправлять. */
    static String textError(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "Напишите сообщение";
        if (t.length() > MAX_LEN) return "Сообщение длиннее 2000 знаков — разбейте на части";
        return "";
    }

    /** Строка под именем в списке: ответ проката — «Прокат: …». */
    static String preview(String text, boolean mine) {
        String t = IvaNotifyPatch.oneLine(text, 90);
        return mine ? "Прокат: " + t : t;
    }

    static String unreadText(int n) { return n <= 0 ? "" : n > 99 ? "99+" : String.valueOf(n); }

    /** Подпись над своим пузырём: «Вы» — ответил я, «Прокат» — владелец или другой сотрудник; у клиента — пусто. */
    static String whoText(boolean mine, boolean byMe) { return !mine ? "" : byMe ? "Вы" : "Прокат"; }

    static String subTitle(String tool, String point) {
        String t = tool == null ? "" : tool.trim(), p = point == null ? "" : point.trim();
        return t.isEmpty() ? p : p.isEmpty() ? t : t + " · " + p;
    }

    /** Голосовое: «🎤 Голосовое · 0:12 — нажмите, чтобы послушать». */
    static String voiceText(int sec) {
        return "🎤 Голосовое" + (sec > 0 ? " · " + IvaChatPatch.durLabel(sec) : "") + " — нажмите, чтобы послушать";
    }

    /** Цель уведомления: «инструмент|метка»; разбор — {инструмент, метка} или null. */
    static String target(String tool, String peer) { return isThread(tool) && isPeer(peer) ? tool.trim() + "|" + peer.trim() : ""; }

    static String[] parseTarget(String t) {
        if (t == null) return null;
        int i = t.indexOf('|');
        if (i <= 0) return null;
        String tool = t.substring(0, i), peer = t.substring(i + 1);
        return isThread(tool) && isPeer(peer) ? new String[]{tool.trim(), peer.trim()} : null;
    }

    /** Последние сообщения клиентов в переписках с непрочитанными — о них уведомление. */
    static List<String> notifyIds(List<Chat> cs) {
        List<String> out = new ArrayList<String>();
        if (cs != null) for (Chat c : cs) if (c.unread > 0 && !c.lastMine && !c.lastClient.isEmpty()) out.add(c.lastClient);
        return out;
    }

    /** «Новое сообщение · Дрель» / «Новые сообщения: 3». */
    static String noteTitle(int n, String tool) {
        if (n > 1) return "Новые сообщения: " + n;
        String t = IvaNotifyPatch.oneLine(tool, 40);
        return t.isEmpty() ? "Новое сообщение" : "Новое сообщение · " + t;
    }

    static String noteText(int n, String client, String text) {
        if (n > 1) return "Откройте «" + TITLE + "»";
        String c = client == null || client.trim().isEmpty() ? "Клиент" : client.trim();
        return c + ": " + IvaNotifyPatch.oneLine(text, 120);
    }

    /** Фразы базы (RAISE EXCEPTION в 20261031_staff_chat.sql и «Цензура» чата), которые показываем как есть. */
    static final String[] DB_PHRASES = {
        "Нет права «Отвечает в чате» — обратитесь к владельцу проката", "Напишите сообщение",
        "Сообщение длиннее 2000 знаков — разбейте на части", "Переписка не найдена в вашем пункте"};
    static final String MAT_PHRASE = "В сообщении есть нецензурные слова. Уберите их, пожалуйста.";

    static String dbError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        for (String p : DB_PHRASES) if (b.contains(p)) return p;
        if (b.contains(MAT_PHRASE)) return MAT_PHRASE;
        if (b.contains("PGRST202") || "404".equals(c)) return NO_DB;
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Нет права — войдите заново";
        return c.isEmpty() ? "Не получилось — нет связи с сервером" : "Не получилось (ответ " + c + ")";
    }

    // ───────────── разбор ответа базы ─────────────

    static final class Chat {
        String tool = "", peer = "", client = "", toolName = "", img = "", point = "", last = "", at = "", lastClient = "";
        boolean lastMine;
        int unread;
    }

    static final class Msg {
        String id = "", text = "", img = "", audio = "", at = "";
        boolean mine, byMe, read;
        int dur;
    }

    static List<Chat> parseChats(String body) throws Exception {
        List<Chat> out = new ArrayList<Chat>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Chat c = new Chat();
            c.tool = str(o, "tool_id");
            c.peer = str(o, "peer");
            c.client = str(o, "client_name");
            c.toolName = str(o, "tool_name");
            c.img = str(o, "tool_img");
            c.point = str(o, "point_name");
            c.last = str(o, "last_text");
            c.at = str(o, "last_at");
            c.lastMine = o.optBoolean("last_mine");
            c.unread = o.optInt("unread", 0);
            c.lastClient = str(o, "last_client_id");
            if (c.tool.isEmpty()) {                                       // 10.120: переписка с пунктом
                c.tool = IvaProkatPatch.key(str(o, "point_id"));
                c.toolName = POINT_ASK;
            }
            if (isThread(c.tool) && isPeer(c.peer)) out.add(c);
        }
        return out;
    }

    /** По инструментам + с пунктом; новые сверху (время из базы — одного вида, сравнимо как строка). */
    static List<Chat> merge(List<Chat> a, List<Chat> b) {
        List<Chat> out = new ArrayList<Chat>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        java.util.Collections.sort(out, new java.util.Comparator<Chat>() {
            @Override public int compare(Chat x, Chat y) {
                long tx = IvaChatPatch.parseIso(x.at), ty = IvaChatPatch.parseIso(y.at);
                return tx == ty ? 0 : tx > ty ? -1 : 1;
            }
        });
        return out;
    }

    /** Переписки пунктов (без SQL 10.120 — пусто, не ошибка). */
    static List<Chat> pointChats(String[] r) {
        try {
            if (r == null || r[0] == null || !r[0].startsWith("2")) return new ArrayList<Chat>();
            return parseChats(r.length > 1 ? r[1] : "");
        } catch (Throwable e) { return new ArrayList<Chat>(); }
    }

    static List<Msg> parseThread(String body) throws Exception {
        List<Msg> out = new ArrayList<Msg>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Msg m = new Msg();
            m.id = str(o, "id");
            m.text = str(o, "text");
            m.img = str(o, "img");
            m.audio = str(o, "audio");
            m.at = str(o, "created_at");
            m.mine = o.optBoolean("mine");
            m.byMe = o.optBoolean("by_me");
            m.read = o.optBoolean("is_read");
            m.dur = o.optInt("audio_dur", 0);
            out.add(m);
        }
        return out;
    }

    static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k, "").trim(); }

    // ───────────── уведомление «Новое сообщение» (IvaNotifyPatch.tick и bgRun, не главный поток) ─────────────

    static void check(Context c, String email) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("iva_staff", 0);
        IvaStaffPatch.Me m = IvaStaffPatch.parseMe(sp.getString("me_" + email, ""));   // кэш «Мой пункт» (кабинет)
        if (m == null || !want(m.status, m.r)) return;                                 // не сотрудник с чатом — в базу не ходим
        long now = System.currentTimeMillis();
        if (now < sp.getLong("chat_nodb_" + email, 0L)) return;
        String[] r = IvaNotifyPatch.api(c, CHATS_RPC, "POST", "{}");
        if (r == null) return;
        if (!r[0].startsWith("2")) {
            if (NO_DB.equals(dbError(r[0], r.length > 1 ? r[1] : ""))) sp.edit().putLong("chat_nodb_" + email, now + NO_DB_PAUSE_MS).apply();
            return;
        }
        List<Chat> cs = parseChats(r.length > 1 ? r[1] : "");
        if (now >= sp.getLong("pchat_nodb_" + email, 0L)) {                          // 10.120: и переписки пунктов
            String[] pr = null;
            try { pr = IvaNotifyPatch.api(c, P_CHATS_RPC, "POST", "{}"); } catch (Throwable ignore) {}
            if (pr != null && !pr[0].startsWith("2") && NO_DB.equals(dbError(pr[0], pr.length > 1 ? pr[1] : "")))
                sp.edit().putLong("pchat_nodb_" + email, now + NO_DB_PAUSE_MS).apply();
            cs = merge(cs, pointChats(pr));
        }
        List<String> ids = notifyIds(cs);
        SharedPreferences p = IvaNotifyPatch.prefs(c);
        String k = IvaNotifyPatch.key("seen_sc", email);
        Set<String> seen = IvaNotifyPatch.splitSet(p.getString(k, null));
        List<String> nw = IvaNotifyPatch.fresh(seen, ids);                               // первый раз — молча запоминаем
        p.edit().putString(k, IvaNotifyPatch.joinSet(IvaNotifyPatch.remember(seen, ids))).apply();
        if (nw.isEmpty() || !IvaNotifyPatch.on(c, IvaNotifyPatch.K_MSG, false)) return;
        Chat first = null;
        for (Chat x : cs) if (x.lastClient.equals(nw.get(0))) { first = x; break; }
        if (first == null) return;
        Chat open = openChat;
        if (open != null && open.tool.equals(first.tool) && open.peer.equals(first.peer) && nw.size() == 1) return;   // эта переписка на экране
        IvaNotifyPatch.showNote(c, IvaNotifyPatch.CH_MSG, ID_NOTE, noteTitle(nw.size(), first.toolName),
            noteText(nw.size(), first.client, first.last), OPEN, nw.size() == 1 ? target(first.tool, first.peer) : "");
    }

    /** Нажали на уведомление: кабинет → «Чат пункта» (одна переписка — сразу она). */
    static void openFromNote(Activity a, String tgt) {
        IvaNotifyPatch.tab(a, "profile");
        IvaStaffPatch.Me m = null;
        try { m = IvaStaffPatch.parseMe(IvaStaffPatch.prefs(a).getString("me_" + IvaPickupPatch.me(a), "")); } catch (Throwable ignore) {}
        if (m == null || !want(m.status, m.r)) return;
        open(a, m.company, parseTarget(tgt));
    }

    // ───────────── страница «Чат пункта» ─────────────

    static volatile Chat openChat;                       // переписка на экране (не уведомлять о ней)

    static final class Ui {
        Activity a;
        IvaChiefPatch.Page page;
        LinearLayout items;
        TextView status;
        boolean busy;
        String[] want;                                    // открыть эту переписку после загрузки (из уведомления)
        int cText, cGray, cOrange;
    }

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static void open(final Activity a, String company) { open(a, company, null); }

    static void open(final Activity a, String company, String[] target) {
        final Ui u = new Ui();
        u.a = a;
        u.want = target;
        u.cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8);
        u.cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        u.cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        String c = company == null || company.trim().isEmpty() ? "Прокат" : company.trim();
        u.page = IvaChiefPatch.page(a, TITLE, "«" + c + "»: вопросы клиентов вашим пунктам и по их инструментам. Клиент видит ответ от проката.", false);
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(dp(a, 2), dp(a, 4), 0, 0);
        u.page.list.addView(u.status, new LinearLayout.LayoutParams(-1, -2));
        u.items = new LinearLayout(a);
        u.items.setOrientation(LinearLayout.VERTICAL);
        u.page.list.addView(u.items, new LinearLayout.LayoutParams(-1, -2));
        reload(u);
    }

    static void reload(final Ui u) {
        if (u.busy) return;
        u.busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Chat> got = null;
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, CHATS_RPC, "POST", "{}", true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (err.isEmpty()) {
                        got = parseChats(r.length > 1 ? r[1] : "");
                        String[] pr = null;
                        try { pr = IvaClientPatch.apiCall(u.a, P_CHATS_RPC, "POST", "{}", true); } catch (Throwable ignore) {}
                        got = merge(got, pointChats(pr));                       // 10.120: и переписки пунктов
                    }
                } catch (Throwable e) { err = dbError("", ""); }
                final List<Chat> fc = got;
                final String fe = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            u.busy = false;
                            if (!u.page.open()) return;
                            if (fc == null) { u.status.setText(fe); u.status.setTextColor(u.cOrange); return; }
                            draw(u, fc);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static String when(String iso) {
        long ms = IvaChatPatch.parseIso(iso);
        if (ms <= 0) return "";
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        return IvaChatPatch.dayIndex(now, tz) == IvaChatPatch.dayIndex(ms, tz) ? IvaChatPatch.hm(ms, tz)
            : new SimpleDateFormat("dd.MM", Locale.ROOT).format(new Date(ms));
    }

    static void draw(final Ui u, List<Chat> cs) {
        final Activity a = u.a;
        u.items.removeAllViews();
        u.status.setTextColor(u.cGray);
        u.status.setText(cs.isEmpty() ? "Пока никто не писал. Здесь появятся вопросы клиентов вашим пунктам и об их инструментах." : "");
        u.status.setVisibility(cs.isEmpty() ? View.VISIBLE : View.GONE);
        Chat jump = null;
        for (final Chat c : cs) {
            if (u.want != null && u.want[0].equalsIgnoreCase(c.tool) && u.want[1].equals(c.peer)) jump = c;
            LinearLayout card = IvaChiefPatch.card(a);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(IvaChiefPatch.text(a, c.client.isEmpty() ? "Клиент" : c.client, 15f, u.cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
            top.addView(IvaChiefPatch.text(a, when(c.at), 12f, u.cGray, false));
            card.addView(top);
            TextView tl = IvaChiefPatch.text(a, subTitle(c.toolName, c.point), 13f, u.cGray, false);
            tl.setPadding(0, dp(a, 4), 0, 0);
            card.addView(tl);
            LinearLayout bottom = new LinearLayout(a);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(a, 4), 0, 0);
            TextView pv = IvaChiefPatch.text(a, preview(c.last, c.lastMine), 14f, c.unread > 0 ? u.cText : u.cGray, c.unread > 0);
            pv.setSingleLine(true);
            pv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bottom.addView(pv, new LinearLayout.LayoutParams(0, -2, 1f));
            String un = unreadText(c.unread);
            if (!un.isEmpty()) {
                TextView b = IvaChiefPatch.text(a, un, 12f, u.cOrange, true);
                b.setBackground(IvaPatch.accentBg(a, u.cOrange, 10));
                b.setPadding(dp(a, 8), dp(a, 2), dp(a, 8), dp(a, 2));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
                bl.leftMargin = dp(a, 8);
                bottom.addView(b, bl);
            }
            card.addView(bottom);
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openThread(u, c); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            u.items.addView(card, lp);
        }
        if (u.want != null) {
            String[] w = u.want;
            u.want = null;                                   // один раз
            if (jump != null) openThread(u, jump);
            else if (w != null) IvaStaffPatch.toast(a, "Переписка не найдена — возможно, инструмент перенесли в другой пункт");
        }
    }

    // ───────────── переписка ─────────────

    static final class Th {
        Ui u;
        Chat c;
        IvaChiefPatch.Page page;
        ScrollView sc;
        EditText input;
        TextView send, status;
        List<Msg> shown;
        boolean busy, sending, marked;
        IvaChatWhoPatch.Box who;                      // 10.122: «Отвечает Иван»
    }

    static void openThread(final Ui u, final Chat c) {
        final Activity a = u.a;
        final Th t = new Th();
        t.u = u;
        t.c = c;
        t.page = IvaChiefPatch.page(a, c.client.isEmpty() ? "Клиент" : c.client, subTitle(c.toolName, c.point), false);
        try { t.page.d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Throwable ignore) {}
        t.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        t.page.list.addView(t.status, new LinearLayout.LayoutParams(-1, -2));
        View scv = (View) t.page.list.getParent();
        if (scv instanceof ScrollView) t.sc = (ScrollView) scv;
        ViewGroup root = scv == null ? null : (ViewGroup) scv.getParent();
        // строка ввода — под прокруткой (закреплена), как в «Чате»
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 12));
        t.input = new EditText(a);
        t.input.setHint("Ответ клиенту");
        t.input.setTextColor(u.cText);
        t.input.setHintTextColor(IvaStaffPatch.color(a, "gray2", 0xff6b7080));
        t.input.setTextSize(15f);
        t.input.setMaxLines(5);
        Drawable fb = IvaSearchPatch.drawable(a, "bg_field");
        if (fb != null) t.input.setBackground(fb);
        t.input.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        bar.addView(t.input, new LinearLayout.LayoutParams(0, -2, 1f));
        t.send = IvaPickupPatch.addButton(a, u.cOrange);
        t.send.setText("Отправить");
        t.send.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = dp(a, 8);
        bar.addView(t.send, sl);
        if (root != null) root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        else t.page.list.addView(bar);
        try {                                            // 10.122: кто ещё отвечает — над строкой ввода
            ViewGroup bp = (ViewGroup) bar.getParent();
            if (bp instanceof LinearLayout) {
                t.who = IvaChatWhoPatch.attach(a, (LinearLayout) bp, bp.indexOfChild(bar), new IvaChatWhoPatch.Alive() {
                    @Override public boolean ok() { return t.page.open(); }
                });
                IvaChatWhoPatch.setThread(t.who, IvaChatWhoPatch.thread(c.tool, c.peer));
                IvaChatWhoPatch.watch(t.who, t.input);
            }
        } catch (Throwable ignore) { t.who = null; }
        t.send.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { send(t); } });
        openChat = c;
        t.page.d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (openChat == c) openChat = null;
                try { IvaChatWhoPatch.setThread(t.who, ""); } catch (Throwable ignore) {}
                if (u.page.open()) reload(u);                   // вернулись к списку — счётчики заново
            }
        });
        load(t);
        tick(t);
    }

    static void tick(final Th t) {
        IvaChiefPatch.h().postDelayed(new Runnable() {
            @Override public void run() {
                if (!t.page.open()) return;
                load(t);
                tick(t);
            }
        }, THREAD_TICK_MS);
    }

    static void load(final Th t) {
        final String body = threadBody(t.c.tool, t.c.peer);
        if (body == null || t.busy) return;
        t.busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Msg> got = null;
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(t.u.a, threadRpc(t.c.tool), "POST", body, true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (err.isEmpty()) got = parseThread(r.length > 1 ? r[1] : "");
                    boolean unread = false;
                    if (got != null) for (Msg m : got) if (!m.mine && !m.read) unread = true;
                    if (unread) IvaClientPatch.apiCall(t.u.a, readRpc(t.c.tool), "POST", body, true);   // открыл — прочитано (и у владельца)
                } catch (Throwable e) { err = dbError("", ""); }
                final List<Msg> fm = got;
                final String fe = err;
                t.u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            t.busy = false;
                            if (!t.page.open()) return;
                            if (fm == null) { t.status.setText(fe); t.status.setTextColor(t.u.cOrange); t.status.setVisibility(View.VISIBLE); return; }
                            if (same(t.shown, fm)) return;           // ничего нового — не перерисовываем
                            t.shown = fm;
                            drawThread(t);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static boolean same(List<Msg> a, List<Msg> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).id.equals(b.get(i).id) || a.get(i).read != b.get(i).read) return false;
        return true;
    }

    static void drawThread(final Th t) {
        final Activity a = t.u.a;
        LinearLayout list = t.page.list;
        list.removeAllViews();
        list.addView(t.status, new LinearLayout.LayoutParams(-1, -2));
        t.status.setTextColor(t.u.cGray);
        t.status.setText(t.shown.isEmpty() ? "Сообщений нет" : "");
        t.status.setVisibility(t.shown.isEmpty() ? View.VISIBLE : View.GONE);
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis(), prevDay = Long.MIN_VALUE;
        int maxW = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.72f);
        for (final Msg m : t.shown) {
            long ms = IvaChatPatch.parseIso(m.at);
            long day = ms > 0 ? IvaChatPatch.dayIndex(ms, tz) : prevDay;
            if (ms > 0 && day != prevDay) {
                TextView d = IvaChiefPatch.text(a, IvaChatPatch.dayLabel(ms, now, tz), 12f, t.u.cGray, false);
                d.setGravity(Gravity.CENTER);
                d.setPadding(0, dp(a, 10), 0, dp(a, 4));
                list.addView(d, new LinearLayout.LayoutParams(-1, -2));
                prevDay = day;
            }
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(m.mine ? Gravity.END : Gravity.START);
            String who = whoText(m.mine, m.byMe);
            if (!who.isEmpty()) col.addView(IvaChiefPatch.text(a, who, 11f, t.u.cGray, false));
            if (!m.img.isEmpty()) {
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                IvaChatPatch.setBg(a, iv, "bg_photo");
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(a, 150), dp(a, 110));
                il.bottomMargin = dp(a, 4);
                col.addView(iv, il);
                IvaChatPatch.loadPhoto(a, iv, m.img, "msg" + m.id);
                iv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view(a, m.img, "image/*"); } });
            }
            String body = !m.audio.isEmpty() ? voiceText(m.dur) : m.text;
            if (!body.isEmpty()) {
                TextView b = IvaChiefPatch.text(a, body, 15f, m.mine ? t.u.cOrange : t.u.cText, false);
                IvaChatPatch.setBg(a, b, m.mine ? "bg_bubble_me" : "bg_bubble_other");
                b.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
                b.setMaxWidth(maxW);
                col.addView(b, new LinearLayout.LayoutParams(-2, -2));
                if (!m.audio.isEmpty()) b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { view(a, m.audio, "audio/*"); } });
            }
            String time = ms > 0 ? IvaChatPatch.hm(ms, tz) : "";
            if (m.mine && !time.isEmpty()) time = time + (m.read ? "  ✓✓" : "  ✓");
            TextView tt = IvaChiefPatch.text(a, time, 10f, t.u.cGray, false);
            tt.setPadding(0, dp(a, 2), 0, 0);
            col.addView(tt);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = dp(a, 6);
            list.addView(col, cl);
        }
        if (t.sc != null) t.sc.post(new Runnable() { @Override public void run() { try { t.sc.fullScroll(View.FOCUS_DOWN); } catch (Throwable ignore) {} } });
    }

    /** Голосовое / фото клиента — системным проигрывателем или просмотром (своё воспроизведение — в «Чате» владельца). */
    static void view(Activity a, String url, String type) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(Uri.parse(url), type);
            a.startActivity(it);
        } catch (Throwable e) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Throwable e2) { IvaStaffPatch.toast(a, "Нечем открыть — установите проигрыватель или браузер"); }
        }
    }

    static void send(final Th t) {
        if (!t.sending && IvaChatWhoPatch.confirmSend(t.who, new Runnable() { @Override public void run() { try { sendNow(t); } catch (Throwable ignore) {} } })) return;
        sendNow(t);
    }

    static void sendNow(final Th t) {
        final Activity a = t.u.a;
        if (t.sending) return;
        final String text = t.input.getText().toString().trim();
        String e = textError(text);
        if (e.isEmpty()) { try { e = IvaCensorPatch.fieldError(text); } catch (Throwable ignore) {} }   // мат — не отправляем (база тоже не примет)
        if (!e.isEmpty()) { IvaStaffPatch.toast(a, e); return; }
        final String body = sendBody(t.c.tool, t.c.peer, text);
        if (body == null) return;
        t.sending = true;
        t.send.setEnabled(false);
        t.send.setAlpha(0.6f);
        new Thread(new Runnable() {
            @Override public void run() {
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(a, sendRpc(t.c.tool), "POST", body, true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable x) { err = dbError("", ""); }
                final String fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            t.sending = false;
                            t.send.setEnabled(true);
                            t.send.setAlpha(1f);
                            if (!fe.isEmpty()) { IvaStaffPatch.toast(a, fe); return; }   // текст остаётся в поле
                            t.input.setText("");
                            t.busy = false;
                            load(t);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }
}

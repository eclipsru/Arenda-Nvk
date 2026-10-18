package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 10.104 — этап 3б «Сотрудники и пункты» (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
 *
 * Сотрудник (принял приглашение, право «Выдаёт и принимает» или «Получает уведомления»): «Мой пункт» → «Заявки пункта» —
 * отдельная страница: «Новые» / «Выданы» / «Закрыты» (за 7 дней), у заявки — пункт, инструменты, клиент (имя, телефон),
 * получение, комментарий, срок; кнопка «Выдал» (новая) или «Вернули» (выдана) — с подтверждением. Свою заявку (сотрудник
 * оформил её как клиент) отмечает, только если владелец включил «Может выдавать заявки самому себе».
 * С правом «Получает уведомления» — «Новая заявка» по своим пунктам (окно открыто — каждые полминуты, закрыто — фоновая
 * проверка, как у владельца); нажатие открывает «Заявки пункта».
 *
 * Всё — функциями базы (SQL 20261029_staff_orders.sql): iva_staff_parts (только свои пункты, без денег и почты клиента)
 * и iva_staff_set_status (право, свой пункт, new → rented → closed, «самому себе»). Кто нажал — база пишет в журнал
 * статусов сама. Без SQL — «заработают после обновления базы». Новых вставок в smali нет: вызовы — из IvaStaffPatch
 * («Мой пункт») и IvaNotifyPatch (tick, bgRun, route).
 */
public class IvaStaffOrdersPatch {
    static final String PARTS_RPC = "/rest/v1/rpc/iva_staff_parts", MARK_RPC = "/rest/v1/rpc/iva_staff_set_status";
    static final String TITLE = "Заявки пункта", OPEN = "staff";
    static final String NO_DB = "Заявки пункта заработают после обновления базы (SQL 10.104)";
    static final String[] TABS = {"Новые", "Выданы", "Закрыты"};
    static final int ID_NOTE = 7205;
    static final long NO_DB_PAUSE_MS = 30L * 60000L;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    /** {"p_part":123,"p_status":"rented"} — только номер из цифр и «Выдал» / «Вернули»; иначе null. */
    static String markBody(String partId, String status) {
        String id = partId == null ? "" : partId.trim();
        if (!id.matches("[0-9]{1,18}") || !("rented".equals(status) || "closed".equals(status))) return null;
        return "{\"p_part\":" + id + ",\"p_status\":\"" + status + "\"}";
    }

    /** Следующее состояние по кнопке: новая → «Выдана», выдана → «Закрыта». */
    static String next(String st) { return "new".equals(st) ? "rented" : "rented".equals(st) ? "closed" : ""; }

    static String actionText(String st) { return "new".equals(st) ? "Выдал" : "rented".equals(st) ? "Вернули" : ""; }

    static String statusText(String st) {
        return "new".equals(st) ? "Новая" : "rented".equals(st) ? "Выдана" : "closed".equals(st) ? "Закрыта" : st == null ? "" : st;
    }

    static String confirmTitle(String st) { return "new".equals(st) ? "Отметить «Выдал»?" : "Отметить «Вернули»?"; }

    static String confirmText(String st, String tools) {
        String t = IvaNotifyPatch.oneLine(tools, 120);
        String what = "new".equals(st) ? "Срок аренды начнётся сейчас." : "Заявка закроется, инструмент снова свободен.";
        return t.isEmpty() ? what : t + "\n" + what;
    }

    /** Вкладка: 0 — новые, 1 — выданы, 2 — закрыты; -1 — неизвестное состояние. */
    static int tabOf(String st) { return "new".equals(st) ? 0 : "rented".equals(st) ? 1 : "closed".equals(st) ? 2 : -1; }

    static int[] counts(List<String> statuses) {
        int[] n = new int[3];
        if (statuses != null) for (String s : statuses) { int t = tabOf(s); if (t >= 0) n[t]++; }
        return n;
    }

    /** С какой вкладки открыть: есть новые — «Новые», иначе есть выданные — «Выданы», иначе «Новые». */
    static int firstTab(int[] n) { return n == null || n[0] > 0 || n[1] == 0 ? 0 : 1; }

    static String tabText(int i, int[] n) { int c = n == null ? 0 : n[i]; return c > 0 ? TABS[i] + " · " + c : TABS[i]; }

    static String emptyText(int tab) {
        return tab == 0 ? "Новых заявок нет" : tab == 1 ? "Выданных сейчас нет" : "Закрытых за 7 дней нет";
    }

    /** 1 день, 2 дня, 5 дней, 21 день. */
    static String daysText(int d) {
        int n = Math.abs(d) % 100, k = n % 10;
        String w = n >= 11 && n <= 14 ? "дней" : k == 1 ? "день" : k >= 2 && k <= 4 ? "дня" : "дней";
        return d + " " + w;
    }

    /** Новые заявки, о которых сотруднику положено уведомление (право «Получает уведомления»). */
    static List<String> notifyIds(List<String> ids, List<String> statuses, List<Boolean> notify) {
        List<String> out = new ArrayList<String>();
        if (ids == null || statuses == null || notify == null) return out;
        for (int i = 0; i < ids.size() && i < statuses.size() && i < notify.size(); i++)
            if ("new".equals(statuses.get(i)) && Boolean.TRUE.equals(notify.get(i))) out.add(ids.get(i));
        return out;
    }

    /** «Новая заявка · Склад» / «Новые заявки: 3». */
    static String noteTitle(int n, String point) {
        if (n > 1) return IvaNotifyPatch.orderTitle(n);
        String p = point == null ? "" : point.trim();
        return p.isEmpty() ? IvaNotifyPatch.orderTitle(1) : IvaNotifyPatch.orderTitle(1) + " · " + p;
    }

    static String noteText(int n, String tools) { return n > 1 ? "Откройте «" + TITLE + "»" : IvaNotifyPatch.orderText(tools); }

    /** Проверять ли заявки: принятый сотрудник с правом «Выдаёт» или «Уведомления». */
    static boolean want(String status, boolean[] r) {
        return "active".equals(status) && r != null && r.length >= 4 && (r[0] || r[3]);
    }

    /** Фразы базы (RAISE EXCEPTION в 20261029_staff_orders.sql), которые показываем как есть. */
    static final String[] DB_PHRASES = {
        "Можно отметить только «Выдал» или «Вернули»", "Заявка не найдена в вашем пункте",
        "Свою заявку выдаёт и принимает другой сотрудник или владелец", "Заявка уже в другом состоянии — обновите список"};

    static String dbError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        for (String p : DB_PHRASES) if (b.contains(p)) return p;
        if (b.contains("PGRST202") || "404".equals(c)) return NO_DB;
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Нет права — войдите заново";
        return c.isEmpty() ? "Не получилось — нет связи с сервером" : "Не получилось (ответ " + c + ")";
    }

    // ───────────── разбор ответа базы ─────────────

    static final class Part {
        String id = "", order = "", point = "", tools = "", status = "", created = "", rented = "", name = "", tel = "", get = "", comment = "";
        int days;
        boolean mine, act, notify;
    }

    static List<Part> parse(String body) throws Exception {
        List<Part> out = new ArrayList<Part>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Part p = new Part();
            p.id = o.optString("part_id", "");
            p.order = o.optString("order_id", "");
            p.point = str(o, "point_name");
            p.tools = str(o, "tools_text");
            p.status = str(o, "status");
            p.created = str(o, "created_at");
            p.rented = str(o, "rented_at");
            p.days = o.optInt("days", 0);
            p.name = str(o, "client_name");
            p.tel = str(o, "client_tel");
            p.get = str(o, "get_method");
            p.comment = str(o, "comment");
            p.mine = o.optBoolean("mine");
            p.act = o.optBoolean("can_act");
            p.notify = o.optBoolean("can_notify");
            if (!p.id.isEmpty()) out.add(p);
        }
        return out;
    }

    static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k, "").trim(); }

    // ───────────── уведомление «Новая заявка» (IvaNotifyPatch.tick и bgRun, не главный поток) ─────────────

    static void check(Context c, String email) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("iva_staff", 0);
        IvaStaffPatch.Me m = IvaStaffPatch.parseMe(sp.getString("me_" + email, ""));   // кэш «Мой пункт» (кабинет)
        if (m == null || !want(m.status, m.r)) return;                                 // не сотрудник — в базу не ходим
        long now = System.currentTimeMillis();
        if (now < sp.getLong("parts_nodb_" + email, 0L)) return;
        String[] r = IvaNotifyPatch.api(c, PARTS_RPC, "POST", "{}");
        if (r == null) return;
        if (!r[0].startsWith("2")) {
            if (NO_DB.equals(dbError(r[0], r.length > 1 ? r[1] : ""))) sp.edit().putLong("parts_nodb_" + email, now + NO_DB_PAUSE_MS).apply();
            return;
        }
        List<Part> ps = parse(r.length > 1 ? r[1] : "");
        List<String> ids = new ArrayList<String>(), sts = new ArrayList<String>();
        List<Boolean> nt = new ArrayList<Boolean>();
        for (Part p : ps) { ids.add(p.id); sts.add(p.status); nt.add(p.notify); }
        List<String> now0 = notifyIds(ids, sts, nt);
        SharedPreferences p = IvaNotifyPatch.prefs(c);
        String k = IvaNotifyPatch.key("seen_s", email);
        Set<String> seen = IvaNotifyPatch.splitSet(p.getString(k, null));
        List<String> nw = IvaNotifyPatch.fresh(seen, now0);                            // первый раз — молча запоминаем
        p.edit().putString(k, IvaNotifyPatch.joinSet(IvaNotifyPatch.remember(seen, now0))).apply();
        if (nw.isEmpty() || !IvaNotifyPatch.on(c, IvaNotifyPatch.K_ORD, true)) return;
        String point = "", tools = "";
        for (Part x : ps) if (x.id.equals(nw.get(0))) { point = x.point; tools = x.tools; break; }
        if (c instanceof Activity) {
            final Activity a = (Activity) c;
            a.runOnUiThread(new Runnable() {
                @Override public void run() { try { Toast.makeText(a, "Новая заявка в пункте!", Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {} }
            });
        }
        IvaNotifyPatch.showNote(c, IvaNotifyPatch.CH_ORD, ID_NOTE, noteTitle(nw.size(), point), noteText(nw.size(), tools), OPEN, nw.get(0));
    }

    /** Нажали на уведомление: кабинет → «Заявки пункта». */
    static void openFromNote(Activity a) {
        IvaNotifyPatch.tab(a, "profile");
        IvaStaffPatch.Me m = null;
        try { m = IvaStaffPatch.parseMe(IvaStaffPatch.prefs(a).getString("me_" + IvaPickupPatch.me(a), "")); } catch (Throwable ignore) {}
        if (m != null && want(m.status, m.r)) open(a, m.company);
    }

    // ───────────── страница «Заявки пункта» ─────────────

    static final class Ui {
        Activity a;
        IvaChiefPatch.Page page;
        LinearLayout tabs, items;
        TextView status;
        List<Part> parts;
        int tab = -1;
        boolean busy;
        int cText, cGray, cOrange;
    }

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static void open(final Activity a, String company) {
        final Ui u = new Ui();
        u.a = a;
        u.cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8);
        u.cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        u.cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        String c = company == null || company.trim().isEmpty() ? "Прокат" : company.trim();
        u.page = IvaChiefPatch.page(a, TITLE, "«" + c + "»: заявки ваших пунктов. Закрытые видны 7 дней.", false);
        u.tabs = new LinearLayout(a);
        u.tabs.setOrientation(LinearLayout.HORIZONTAL);
        u.page.list.addView(u.tabs, new LinearLayout.LayoutParams(-1, -2));
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(dp(a, 2), dp(a, 12), 0, 0);
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
                List<Part> got = null;
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, PARTS_RPC, "POST", "{}", true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (err.isEmpty()) got = parse(r.length > 1 ? r[1] : "");
                    if (got != null) IvaOrderViewPatch.load(u.a, "staff");   // 10.121: инструменты с фото, пункт, сроки (нет SQL — как было)
                } catch (Throwable e) { err = dbError("", ""); }
                final List<Part> fp = got;
                final String fe = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            u.busy = false;
                            if (!u.page.open()) return;
                            if (fp == null) { u.status.setText(fe); u.status.setTextColor(u.cOrange); return; }
                            u.parts = fp;
                            draw(u);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void draw(final Ui u) {
        final Activity a = u.a;
        List<String> sts = new ArrayList<String>();
        for (Part p : u.parts) sts.add(p.status);
        int[] n = counts(sts);
        if (u.tab < 0) u.tab = firstTab(n);
        u.tabs.removeAllViews();
        for (int i = 0; i < 3; i++) {
            final int ti = i;
            TextView ch = IvaSearchPatch.chip(a, tabText(i, n), i == u.tab, false);
            ch.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i < 2) cl.rightMargin = dp(a, 8);
            u.tabs.addView(ch, cl);
            ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { u.tab = ti; draw(u); } });
        }
        u.items.removeAllViews();
        int shown = 0;
        for (final Part p : u.parts) {
            if (tabOf(p.status) != u.tab) continue;
            shown++;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            u.items.addView(card(u, p), lp);
        }
        u.status.setTextColor(u.cGray);
        u.status.setText(shown == 0 ? emptyText(u.tab) : "");
        u.status.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    static TextView line(Ui u, String s, int color) {
        TextView t = IvaChiefPatch.text(u.a, s, 14f, color, false);
        t.setPadding(0, dp(u.a, 4), 0, 0);
        return t;
    }

    static String when(String iso) {
        long ms = IvaChatPatch.parseIso(iso);
        return ms <= 0 ? "" : new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(new Date(ms));
    }

    static View card(final Ui u, final Part p) {
        final Activity a = u.a;
        LinearLayout c = IvaChiefPatch.card(a);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String w = when(p.created);
        top.addView(IvaChiefPatch.text(a, "Заявка №" + p.order + (w.isEmpty() ? "" : " · " + w), 15f, u.cText, true),
            new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(IvaOrderViewPatch.badge(a, IvaOrderViewPatch.kind(p.status, IvaOrderViewPatch.partEnd(p.id), System.currentTimeMillis())));   // 10.121
        c.addView(top);
        String sp = "";
        try { sp = IvaOrderViewPatch.staffPoint(p.id); } catch (Throwable ignore) {}
        if (!sp.isEmpty() || !p.point.isEmpty()) c.addView(line(u, "Пункт: " + (sp.isEmpty() ? p.point : sp), u.cGray));
        View items = null;
        try { items = IvaOrderViewPatch.staffItems(a, p.id); } catch (Throwable ignore) {}   // 10.121: инструменты с фото и ценой
        if (items != null) c.addView(items);
        else if (!p.tools.isEmpty()) c.addView(line(u, p.tools, u.cText));
        if (!p.name.isEmpty()) c.addView(line(u, "Клиент: " + p.name + (p.mine ? " (это вы)" : ""), u.cText));
        if (!p.tel.isEmpty()) {
            TextView t = line(u, p.tel, u.cOrange);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { a.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.tel.replaceAll("[^0-9+]", "")))); } catch (Throwable ignore) {}
                }
            });
            c.addView(t);
        }
        if (!p.get.isEmpty()) c.addView(line(u, "Получение: " + p.get, u.cGray));
        if (!p.comment.isEmpty()) c.addView(line(u, "Комментарий: " + p.comment, u.cGray));
        String r = when(p.rented);
        if ("rented".equals(p.status) && !r.isEmpty()) {
            c.addView(line(u, "Выдана " + r + (p.days > 0 ? " · на " + daysText(p.days) : ""), u.cGray));
            List<IvaNotifyPatch.RentEnd> es = IvaRentPatch.byPart.get(p.id);   // 10.121: таймер, как у владельца
            if (es != null && !es.isEmpty()) {
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.topMargin = dp(a, 4);
                c.addView(IvaRentPatch.timerView(a, es, 13f, true), tl);
            }
        }
        else if (p.days > 0 && !"closed".equals(p.status)) c.addView(line(u, "Срок: " + daysText(p.days), u.cGray));
        if (p.act && !next(p.status).isEmpty()) {
            final TextView b = IvaPickupPatch.addButton(a, u.cOrange);
            b.setText(actionText(p.status));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = dp(a, 12);
            c.addView(b, bl);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    IvaClientPatch.styledChoice(a, confirmTitle(p.status), confirmText(p.status, p.tools), new String[]{actionText(p.status)},
                        new IvaClientPatch.Choice() { @Override public void pick(int i) { if (i == 0) mark(u, p, b); } });
                }
            });
        } else if (p.mine && !next(p.status).isEmpty()) {
            c.addView(line(u, "Свою заявку отмечает другой сотрудник или владелец", u.cGray));
        }
        return c;
    }

    static void mark(final Ui u, final Part p, final TextView btn) {
        final String body = markBody(p.id, next(p.status));
        if (body == null) return;
        btn.setEnabled(false);
        btn.setAlpha(0.6f);
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, MARK_RPC, "POST", body, true);
                    e = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable x) { e = dbError("", ""); }
                final String fe = e;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            btn.setEnabled(true);
                            btn.setAlpha(1f);
                            IvaStaffPatch.toast(u.a, fe.isEmpty() ? ("new".equals(p.status) ? "Готово: заявка выдана" : "Готово: заявка закрыта") : fe);
                            reload(u);                                       // перечитать: состояние могло смениться
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }
}

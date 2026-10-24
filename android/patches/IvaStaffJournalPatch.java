package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 10.107 — этап 4 «Сотрудники и пункты» (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
 *
 * Владельцу проката:
 *  • шторка заявки («Заявки» → карточка) — строки «Выдал: Иван · 17.10.2026, 14:05» и «Принял: Вы · …»
 *    (закрыта без выдачи — «Закрыл»). Кто нажал — пишет база (SQL 20261101_staff_journal.sql), подделать нельзя;
 *  • «Настройки → Настройка проката» → «Сводка по пунктам»: по каждому пункту — заявки сейчас (новые / выдано),
 *    за 7 дней / 30 дней / всё время — заявки, закрыто, аренда и комиссия, инструменты, сотрудники,
 *    кто сколько выдал и принял;
 *  • чат: под ответом, который написал сотрудник, — его имя («Иван · 14:05 ✓✓»). Клиент по-прежнему видит ответ
 *    от проката.
 * Сотрудник и клиент этого не видят (база отдаёт только владельцу). Без SQL — журнала и имён просто нет, «Сводка» —
 * «заработает после обновления базы». Новых вставок в smali нет: вызовы — из IvaOrderPatch.ordOpenRow (шторка),
 * IvaShopPatch.openPage («Настройка проката»), IvaChatPatch (загрузка, подпись под пузырём, перерисовка).
 */
public class IvaStaffJournalPatch {
    static final String PART_RPC = "/rest/v1/rpc/iva_part_journal", SUM_RPC = "/rest/v1/rpc/iva_points_summary",
        CHAT_RPC = "/rest/v1/rpc/iva_chat_staff_names";
    static final String SUM_TITLE = "Сводка по пунктам", SUM_SUB = "Заявки, выдачи и сотрудники каждого пункта";
    static final String NO_DB = "Сводка заработает после обновления базы (SQL 10.107)";
    static final int[] DAYS = {7, 30, 0};
    static final String[] DAYS_TEXT = {"7 дней", "30 дней", "Всё время"};
    static final long NO_DB_PAUSE_MS = 30L * 60000L;
    static final int CHAT_BATCH = 300;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static final String[] DB_PHRASES = {"Только владелец проката"};

    static String dbError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        for (String p : DB_PHRASES) if (b.contains(p)) return p;
        if (b.contains("PGRST202") || "404".equals(c)) return NO_DB;
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Нет права — войдите заново";
        return c.isEmpty() ? "Не получилось — нет связи с сервером" : "Не получилось (ответ " + c + ")";
    }

    static String t(String s) { return s == null ? "" : s.trim(); }

    /** Журнал нужен у выданной и закрытой части (номер — число). */
    static boolean want(String part, String status) {
        String p = t(part), s = t(status);
        return p.matches("\\d{1,18}") && ("rented".equals(s) || "closed".equals(s));
    }

    /** «17.10.2026, 14:05» в поясе телефона; не разобрать — пусто. */
    static String when(String iso, TimeZone tz) {
        long ms = IvaChatPatch.parseIso(t(iso));
        if (ms < 0) return "";
        return IvaChatPatch.dmy(ms, tz) + ", " + IvaChatPatch.hm(ms, tz);
    }

    static String whoAt(String who, String at) {
        String w = t(who), a = t(at);
        return w.isEmpty() ? a : a.isEmpty() ? w : w + " · " + a;
    }

    /** Строки шторки: «Выдал» и «Принял» (закрыта без выдачи — «Закрыл»); пустые не показываем. */
    static List<String[]> rows(String status, String issuedWho, String issuedAt, String retWho, String retAt, TimeZone tz) {
        List<String[]> out = new ArrayList<String[]>();
        String iss = whoAt(issuedWho, when(issuedAt, tz)), ret = whoAt(retWho, when(retAt, tz));
        if (!iss.isEmpty()) out.add(new String[]{"Выдал", iss});
        if ("closed".equals(t(status)) && !ret.isEmpty()) out.add(new String[]{iss.isEmpty() ? "Закрыл" : "Принял", ret});
        return out;
    }

    /** Номера своих сообщений, про которые ещё не спрашивали (не больше CHAT_BATCH). */
    static List<String> needIds(List<String[]> idFrom, String my, Set<String> asked) {
        List<String> out = new ArrayList<String>();
        String me = t(my).toLowerCase(Locale.ROOT);
        if (me.isEmpty()) return out;
        for (String[] m : idFrom) {
            if (m == null || m.length < 2) continue;
            String id = t(m[0]);
            if (id.isEmpty() || !id.matches("[0-9A-Za-z-]{1,40}") || asked.contains(id) || out.contains(id)) continue;
            if (!me.equals(t(m[1]).toLowerCase(Locale.ROOT))) continue;
            out.add(id);
            if (out.size() >= CHAT_BATCH) break;
        }
        return out;
    }

    static String idsBody(List<String> ids) {
        StringBuilder b = new StringBuilder("{\"p_ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(ids.get(i).replaceAll("[^0-9A-Za-z-]", "")).append('"');
        }
        return b.append("]}").toString();
    }

    static String daysBody(int days) { return "{\"p_days\":" + Math.max(0, days) + "}"; }

    static String pointTitle(String name, String city) {
        String n = t(name), c = t(city);
        if (n.isEmpty()) n = "Пункт";
        return c.isEmpty() || n.toLowerCase(Locale.ROOT).contains(c.toLowerCase(Locale.ROOT)) ? n : n + " · " + c;
    }

    static String nowLine(int fresh, int rented) { return "Сейчас: новых " + fresh + " · выдано " + rented; }

    static String periodLine(int days, int parts, int closed) {
        String p = days <= 0 ? "За всё время" : "За " + days + " " + dayWord(days);
        return p + ": заявок " + parts + " · закрыто " + closed;
    }

    static String dayWord(int n) {
        int a = Math.abs(n) % 100, b = a % 10;
        if (a >= 11 && a <= 14) return "дней";
        return b == 1 ? "день" : b >= 2 && b <= 4 ? "дня" : "дней";
    }

    static String moneyLine(String rent, String fee) {
        String r = IvaOrderPatch.admMoney(rent), f = IvaOrderPatch.admMoney(fee);
        if (r.isEmpty()) r = "0 ₽";
        if (f.isEmpty()) f = "0 ₽";
        return "Аренда: " + r + " · комиссия: " + f;
    }

    static String toolsLine(int all, int shown, int pending) {
        return "Инструменты: " + all + ", клиенты видят " + shown + (pending > 0 ? ", на одобрении " + pending : "");
    }

    static String staffLine(int staff, int invited) {
        return "Сотрудники: " + staff + (invited > 0 ? " (ещё приглашены: " + invited + ")" : "");
    }

    static String personLine(String who, int issued, int returned) {
        String w = t(who);
        return (w.isEmpty() ? "Без имени" : w) + " — выдал " + issued + ", принял " + returned;
    }

    // ───────────── 24.10.2026 (10.128): «Выдал / Принял» на карточках заявок владельца (SQL 20261118) ─────────────
    // Просьба владельца 24.10.2026: в заявках владельца видно, кто из сотрудников выдал инструмент и кто принял обратно.
    // Одним запросом iva_parts_journal по всем выданным и закрытым частям — вместе с карточками (IvaOrderViewPatch.load,
    // фоном, только владельцу). Возврат по срокам (10.124) — «принял: …» у каждого срока. Нет SQL — строк просто нет.

    static final String PARTS_RPC = "/rest/v1/rpc/iva_parts_journal";
    static final int PARTS_MAX = 500;
    static final Map<String, JSONObject> partsJ = java.util.Collections.synchronizedMap(new HashMap<String, JSONObject>());
    static volatile long partsNoDbUntil;

    /** {"p_parts":[1,2]} — только номера из цифр, без повторов, не больше PARTS_MAX; нечего спрашивать — null. */
    static String partsBody(java.util.Collection<String> ids) {
        if (ids == null) return null;
        List<String> ok = new ArrayList<String>();
        for (String id : ids) {
            String s = t(id);
            if (s.matches("\\d{1,18}") && !ok.contains(s)) ok.add(s);
            if (ok.size() >= PARTS_MAX) break;
        }
        if (ok.isEmpty()) return null;
        StringBuilder b = new StringBuilder("{\"p_parts\":[");
        for (int i = 0; i < ok.size(); i++) { if (i > 0) b.append(','); b.append(ok.get(i)); }
        return b.append("]}").toString();
    }

    /** Номера выданных и закрытых частей из строк iva_order_cards('admin'). */
    static List<String> partIds(java.util.Collection<JSONObject> rows) {
        List<String> out = new ArrayList<String>();
        if (rows != null) for (JSONObject o : rows) {
            JSONArray ps = o == null ? null : o.optJSONArray("parts");
            for (int i = 0; ps != null && i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p != null && want(p.optString("part_id", ""), t(p.optString("status", "")).toLowerCase(Locale.ROOT)))
                    out.add(t(p.optString("part_id", "")));
            }
        }
        return out;
    }

    /** Ответ функции → номер части → журнал (строка может прийти как есть или под именем функции). */
    static Map<String, JSONObject> parseParts(String body) {
        Map<String, JSONObject> m = new HashMap<String, JSONObject>();
        try {
            JSONArray arr = new JSONArray(body == null ? "[]" : body.trim());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optJSONObject("iva_parts_journal") != null) o = o.optJSONObject("iva_parts_journal");
                if (o != null && !t(str(o, "part_id")).isEmpty()) m.put(t(str(o, "part_id")), o);
            }
        } catch (Throwable ignore) {}
        return m;
    }

    /** «Выдал: Иван · 24.10 13:35»; нет имени — пусто. */
    static String whoLine(String label, String who, long at) {
        String w = t(who);
        if (w.isEmpty()) return "";
        return t(label) + ": " + w + (at > 0 ? " · " + IvaOrderViewPatch.dt(at) : "");
    }

    /** Возвращённые инструменты журнала: {номер инструмента, кто принял, когда (ISO)}. */
    static List<String[]> jItems(JSONObject j) {
        List<String[]> out = new ArrayList<String[]>();
        JSONArray ji = j == null ? null : j.optJSONArray("items");
        for (int i = 0; ji != null && i < ji.length(); i++) {
            JSONObject x = ji.optJSONObject(i);
            if (x != null) out.add(new String[]{t(str(x, "id")), t(str(x, "returned_who")), t(str(x, "returned_at"))});
        }
        return out;
    }

    /** Кто принял срок: инструмент срока с тем же временем возврата (±1 мин), иначе первый по номеру инструмента. */
    static String termWho(List<String[]> items, List<String> groupIds, long returnedAt) {
        String byId = "";
        if (items == null || groupIds == null) return "";
        for (String gid : groupIds) {
            String id = t(gid);
            if (id.isEmpty()) continue;
            for (String[] x : items) {
                if (x == null || x.length < 3 || !id.equals(t(x[0])) || t(x[1]).isEmpty()) continue;
                long at = IvaChatPatch.parseIso(t(x[2]));
                if (returnedAt > 0 && at > 0 && Math.abs(at - returnedAt) <= 60000L) return t(x[1]);
                if (byId.isEmpty()) byId = t(x[1]);
            }
        }
        return byId;
    }

    static String termWho(JSONObject j, JSONArray groupItems, long returnedAt) {
        List<String> ids = new ArrayList<String>();
        for (int g = 0; groupItems != null && g < groupItems.length(); g++) {
            JSONObject it = groupItems.optJSONObject(g);
            if (it != null) ids.add(it.optString("id", ""));
        }
        return termWho(jItems(j), ids, returnedAt);
    }

    /** Принимали по частям (разные люди или разное время) — «принял» показываем у каждого срока, а не одной строкой. */
    static boolean splitReturn(List<String[]> items) {
        Set<String> seen = new HashSet<String>();
        if (items != null) for (String[] x : items) if (x != null && x.length >= 3 && !t(x[1]).isEmpty()) seen.add(t(x[1]) + "|" + t(x[2]));
        return seen.size() > 1;
    }

    /** Строки карточки: «Выдал …»; у закрытой, принятой разом, — «Принял …» (закрыта без выдачи — «Закрыл»). */
    static List<String> cardLines(String status, String issWho, String issAt, String retWho, String retAt, boolean split) {
        List<String> out = new ArrayList<String>();
        String st = t(status).toLowerCase(Locale.ROOT);
        if (!"rented".equals(st) && !"closed".equals(st)) return out;
        String iss = whoLine("Выдал", issWho, IvaChatPatch.parseIso(t(issAt)));
        if (!iss.isEmpty()) out.add(iss);
        if ("closed".equals(st) && !split) {
            String ret = whoLine(iss.isEmpty() ? "Закрыл" : "Принял", retWho, IvaChatPatch.parseIso(t(retAt)));
            if (!ret.isEmpty()) out.add(ret);
        }
        return out;
    }

    static List<String> cardLines(JSONObject j, String status) {
        if (j == null) return new ArrayList<String>();
        return cardLines(status, str(j, "issued_who"), str(j, "issued_at"), str(j, "returned_who"), str(j, "returned_at"), splitReturn(jItems(j)));
    }

    static JSONObject partJ(String partId) { return partId == null ? null : partsJ.get(t(partId)); }

    /** Из IvaOrderViewPatch.load (фоном, только владельцу): журнал выданных и закрытых частей одним запросом. */
    static void loadParts(Context a, java.util.Collection<JSONObject> rows) {
        if (System.currentTimeMillis() < partsNoDbUntil) return;
        String b = partsBody(partIds(rows));
        if (b == null) { partsJ.clear(); return; }
        try {
            String[] r = a instanceof Activity ? IvaClientPatch.apiCall((Activity) a, PARTS_RPC, "POST", b, true) : IvaNotifyPatch.api(a, PARTS_RPC, "POST", b);
            if (r == null || r[0] == null) return;
            String tx = r.length > 1 && r[1] != null ? r[1].trim() : "";
            if (!r[0].startsWith("2")) {
                if (NO_DB.equals(dbError(r[0], tx))) partsNoDbUntil = System.currentTimeMillis() + NO_DB_PAUSE_MS;
                return;
            }
            Map<String, JSONObject> got = parseParts(tx);
            synchronized (partsJ) { partsJ.clear(); partsJ.putAll(got); }
        } catch (Throwable ignore) {}
    }

    /** Из IvaOrderViewPatch.admCard: строки «Выдал / Принял» серым под шапкой карточки. */
    static void card(Activity a, LinearLayout box, String partId, String status) {
        List<String> ls = cardLines(partJ(partId), status);
        if (ls.isEmpty()) return;
        int cGray = IvaOrderPatch.color(a, "gray", 0xff9aa1b0);
        for (String l : ls) {
            TextView t = IvaChiefPatch.text(a, l, 12f, cGray, false);
            t.setPadding(0, IvaChiefPatch.dp(a, 6), 0, 0);
            box.addView(t);
        }
    }

    // ───────────── шторка заявки владельца ─────────────

    static final Map<String, List<String[]>> partCache = new HashMap<String, List<String[]>>();
    static volatile long partNoDbUntil;

    /** Из IvaOrderPatch.ordOpenRow — после строк пункта выдачи. */
    static void sheet(final Activity a, LinearLayout body, Map<String, String> r, boolean adm) {
        if (!adm || r == null) return;
        final String part = t(r.get("part_id")), status = t(r.get("status"));
        if (!want(part, status)) return;
        final String key = part + "|" + status;
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        body.addView(box, new LinearLayout.LayoutParams(-1, -2));
        List<String[]> got;
        synchronized (partCache) { got = partCache.get(key); }
        JSONObject pj = partJ(part);                                    // 10.128: уже загружено вместе с карточками
        if (got == null && pj != null) got = rows(status, str(pj, "issued_who"), str(pj, "issued_at"),
            str(pj, "returned_who"), str(pj, "returned_at"), TimeZone.getDefault());
        if (got != null) { fill(a, box, got); return; }
        if (System.currentTimeMillis() < partNoDbUntil) return;
        new Thread(new Runnable() {
            @Override public void run() {
                List<String[]> rows = null;
                try {
                    String[] res = IvaClientPatch.apiCall(a, PART_RPC, "POST", "{\"p_part\":" + part + "}", true);
                    if (res != null && res[0] != null && res[0].startsWith("2")) {
                        JSONArray arr = new JSONArray(res.length > 1 && res[1] != null ? res[1] : "[]");
                        JSONObject o = arr.length() > 0 ? arr.optJSONObject(0) : null;
                        if (o != null) rows = rows(str(o, "status"), str(o, "issued_who"), str(o, "issued_at"),
                            str(o, "returned_who"), str(o, "returned_at"), TimeZone.getDefault());
                    } else if (res != null && !dbError(res[0], res.length > 1 ? res[1] : "").equals(NO_DB)) {
                        return;
                    } else if (res != null) partNoDbUntil = System.currentTimeMillis() + NO_DB_PAUSE_MS;
                } catch (Throwable ignore) {}
                if (rows == null) return;
                final List<String[]> fr = rows;
                synchronized (partCache) { if (partCache.size() > 200) partCache.clear(); partCache.put(key, fr); }
                a.runOnUiThread(new Runnable() { @Override public void run() { try { fill(a, box, fr); } catch (Throwable ignore) {} } });
            }
        }).start();
    }

    static void fill(Activity a, LinearLayout box, List<String[]> rows) {
        box.removeAllViews();
        for (String[] f : rows) IvaOrderPatch.ordFieldView(a, box, f, false);
    }

    static String str(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, "").trim(); }

    // ───────────── чат владельца: кто из сотрудников ответил ─────────────

    static final Map<String, String> chatNames = new HashMap<String, String>();
    static final Set<String> chatAsked = new HashSet<String>();
    static volatile int chatVer;
    static volatile long chatNoDbUntil;
    static volatile String chatFor = "";

    /** Из IvaChatPatch.load (не главный поток, только админу): спросить про свои новые сообщения. */
    static void chatLoad(Activity a, List<IvaChatPatch.Msg> msgs, String my) {
        String me = t(my).toLowerCase(Locale.ROOT);
        if (me.isEmpty() || msgs == null) return;
        if (!me.equals(chatFor)) {
            synchronized (chatNames) { chatNames.clear(); chatAsked.clear(); }
            chatFor = me;
            chatVer++;
        }
        if (System.currentTimeMillis() < chatNoDbUntil) return;
        List<String[]> idFrom = new ArrayList<String[]>();
        for (IvaChatPatch.Msg m : msgs) if (m != null) idFrom.add(new String[]{m.id, m.from});
        List<String> need;
        synchronized (chatNames) { need = needIds(idFrom, me, chatAsked); }
        if (need.isEmpty()) return;
        try {
            String[] r = IvaClientPatch.apiCall(a, CHAT_RPC, "POST", idsBody(need), true);
            if (r == null || r[0] == null) return;
            if (!r[0].startsWith("2")) {
                if (dbError(r[0], r.length > 1 ? r[1] : "").equals(NO_DB)) chatNoDbUntil = System.currentTimeMillis() + NO_DB_PAUSE_MS;
                return;
            }
            JSONArray arr = new JSONArray(r.length > 1 && r[1] != null ? r[1] : "[]");
            boolean changed = false;
            synchronized (chatNames) {
                if (!me.equals(chatFor)) return;
                chatAsked.addAll(need);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    String id = str(o, "id"), who = str(o, "who");
                    if (!id.isEmpty() && !who.isEmpty() && !who.equals(chatNames.get(id))) { chatNames.put(id, who); changed = true; }
                }
            }
            if (changed) chatVer++;
        } catch (Throwable ignore) {}
    }

    /** Имя сотрудника под своим пузырём («» — ответил сам владелец). */
    static String who(String id) {
        if (id == null || id.isEmpty()) return "";
        synchronized (chatNames) { String w = chatNames.get(id); return w == null ? "" : w; }
    }

    // ───────────── «Настройка проката» → «Сводка по пунктам» ─────────────

    /** Из IvaShopPatch.openPage. */
    static void settingsRow(final Activity a, LinearLayout list, final int cText, final int cGray, final int cOrange) {
        float den = a.getResources().getDisplayMetrics().density;
        LinearLayout row = IvaShopPatch.row(a, list, new IvaShopPatch.Icon(IvaShopPatch.Icon.SHOP, cOrange, den), SUM_TITLE, SUM_SUB, cText, cGray);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { open(a); } catch (Throwable ignore) {} }
        });
    }

    static final class Ui {
        Activity a;
        IvaChiefPatch.Page page;
        LinearLayout chips, items;
        TextView status;
        int days = 30, seq;
        int cText, cGray, cOrange;
    }

    static void open(final Activity a) {
        final Ui u = new Ui();
        u.a = a;
        u.cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8);
        u.cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        u.cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        u.page = IvaChiefPatch.page(a, SUM_TITLE, "Видите только вы. Сотрудники видят лишь заявки своего пункта.", false);
        u.chips = new LinearLayout(a);
        u.chips.setOrientation(LinearLayout.HORIZONTAL);
        u.page.list.addView(u.chips, new LinearLayout.LayoutParams(-1, -2));
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(IvaChiefPatch.dp(a, 2), IvaChiefPatch.dp(a, 10), 0, 0);
        u.page.list.addView(u.status, new LinearLayout.LayoutParams(-1, -2));
        u.items = new LinearLayout(a);
        u.items.setOrientation(LinearLayout.VERTICAL);
        u.page.list.addView(u.items, new LinearLayout.LayoutParams(-1, -2));
        drawChips(u);
        reload(u);
    }

    static void drawChips(final Ui u) {
        Activity a = u.a;
        u.chips.removeAllViews();
        for (int i = 0; i < DAYS.length; i++) {
            final int d = DAYS[i];
            TextView c = IvaSearchPatch.chip(a, DAYS_TEXT[i], d == u.days, false);
            c.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = IvaChiefPatch.dp(a, 8);
            u.chips.addView(c, lp);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (u.days == d) return;
                    u.days = d;
                    drawChips(u);
                    reload(u);
                }
            });
        }
    }

    static void reload(final Ui u) {
        final int seq = ++u.seq, days = u.days;
        u.status.setVisibility(View.VISIBLE);
        u.status.setTextColor(u.cGray);
        u.status.setText("Загружаем…");
        new Thread(new Runnable() {
            @Override public void run() {
                JSONArray got = null;
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, SUM_RPC, "POST", daysBody(days), true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (err.isEmpty()) {
                        JSONObject o = new JSONObject(r.length > 1 && r[1] != null ? r[1] : "{}");
                        got = o.optJSONArray("points");
                        if (got == null) got = new JSONArray();
                    }
                } catch (Throwable e) { err = dbError("", ""); }
                final JSONArray fp = got;
                final String fe = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (seq != u.seq || !u.page.open()) return;
                            if (fp == null) { u.items.removeAllViews(); u.status.setText(fe); u.status.setTextColor(u.cOrange); return; }
                            draw(u, fp, days);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void draw(Ui u, JSONArray ps, int days) {
        Activity a = u.a;
        u.items.removeAllViews();
        int shown = 0;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null || !realPoint(str(p, "id"))) continue;   // 10.111: раздела «Без пункта» нет (решение владельца)
            shown++;
            LinearLayout c = IvaChiefPatch.card(a);
            c.addView(IvaChiefPatch.text(a, pointTitle(str(p, "name"), str(p, "city")), 15f, u.cText, true));
            line(a, c, nowLine(p.optInt("new", 0), p.optInt("rented", 0)), u.cText);
            line(a, c, periodLine(days, p.optInt("parts", 0), p.optInt("closed", 0)), u.cText);
            line(a, c, moneyLine(str(p, "rent"), str(p, "fee")), u.cGray);
            line(a, c, toolsLine(p.optInt("tools", 0), p.optInt("shown", 0), p.optInt("pending", 0)), u.cGray);
            line(a, c, staffLine(p.optInt("staff", 0), p.optInt("invited", 0)), u.cGray);
            JSONArray pp = p.optJSONArray("people");
            if (pp != null && pp.length() > 0) {
                TextView h = IvaChiefPatch.text(a, "Кто выдавал и принимал", 13f, u.cGray, true);
                h.setPadding(0, IvaChiefPatch.dp(a, 10), 0, 0);
                c.addView(h);
                for (int j = 0; j < pp.length(); j++) {
                    JSONObject o = pp.optJSONObject(j);
                    if (o != null) line(a, c, personLine(str(o, "who"), o.optInt("issued", 0), o.optInt("returned", 0)), u.cText);
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = IvaChiefPatch.dp(a, 10);
            u.items.addView(c, lp);
        }
        u.status.setText(shown == 0 ? "Пунктов выдачи пока нет — добавьте их в «Пункты выдачи»" : "");
        u.status.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    /** 10.111 (решение владельца 17.10.2026): в «Сводке» — только настоящие пункты. Заявки и инструменты без пункта
     *  (база отдаёт их строкой с пустым id — «Без пункта») не показываются и ни к какому пункту не прибавляются. */
    static boolean realPoint(String id) { return id != null && !id.trim().isEmpty() && !"null".equals(id.trim()); }

    static void line(Activity a, LinearLayout c, String s, int color) {
        TextView x = IvaChiefPatch.text(a, s, 14f, color, false);
        x.setPadding(0, IvaChiefPatch.dp(a, 4), 0, 0);
        c.addView(x);
    }
}

package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 10.121 — заявки целиком (просьба владельца 18.10.2026): «в заявках вся информация, красиво, в стиле сайта, понятно
 * и легко читать; вернуть таймер». У клиента, владельца и сотрудника.
 *
 * Данные — одной функцией базы iva_order_cards('client' | 'admin' | 'staff') (SQL 20261112_v121.sql): у каждой части
 * заявки — инструменты (фото, срок, цена, залог), когда выдан, правило срока (для таймера), пункт выдачи с адресом,
 * графиком и телефоном, сумма. Клиенту — свои заявки без своих ФИО и телефона и без комиссии; владельцу — части своего
 * проката (с комиссией); сотруднику — части своих пунктов (без комиссии). Почта клиента не приходит никому.
 *
 * Вид — как карточка заявки на сайте (cabinet.html): плашка состояния (новая — оранжевая, в аренде — жёлтая,
 * закрыта — зелёная, срок вышел — красная), «дата · №», инструменты строками с фото, «N суток · цена», пункт выдачи,
 * получение, таймер и «Итого». Таймер считается по тем же данным (IvaRentPatch.end) — не зависит от отдельной загрузки
 * сроков. Без SQL — всё как в 10.120 (штатные строки и прежний таймер).
 *
 * Новых вставок в smali нет: вызовы — из IvaOrderPatch (карточка и подробности клиента, карточки владельца),
 * IvaStaffOrdersPatch («Заявки пункта»).
 */
public class IvaOrderViewPatch {
    static final String RPC = "/rest/v1/rpc/iva_order_cards";
    static final String VIEW_TAG = "iva_ord_view";
    static final long NO_DB_MS = 10 * 60000L, ADM_FRESH_MS = 20000L;
    static final int PIC_DP = 48;

    /** «o:» + номер заявки (клиент) / «p:» + номер части (владелец, сотрудник) → строка функции базы. */
    static final Map<String, JSONObject> cards = Collections.synchronizedMap(new HashMap<String, JSONObject>());
    static volatile long noDbUntil, admAt;
    static volatile boolean admBusy;
    static volatile String admSig = "";

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    /** {"p_scope":"client"} — только client / admin / staff; иначе null. */
    static String body(String scope) {
        return "client".equals(scope) || "admin".equals(scope) || "staff".equals(scope) ? "{\"p_scope\":\"" + scope + "\"}" : null;
    }

    static String norm(String st) {
        String s = st == null ? "" : st.trim().toLowerCase(Locale.ROOT);
        return "rented".equals(s) || "closed".equals(s) || "cancelled".equals(s) ? s : "new";
    }

    /** Состояние для плашки: 0 — новая, 1 — в аренде, 2 — закрыта, 3 — срок вышел (в аренде, конец прошёл). */
    static int kind(String st, long end, long now) {
        String s = norm(st);
        if ("closed".equals(s)) return 2;
        if ("cancelled".equals(s)) return 4;                        // 10.122: отменил клиент
        if ("rented".equals(s)) return end > 0 && now >= end ? 3 : 1;
        return 0;
    }

    static final String[] KIND_TEXT = {"Новая", "В аренде", "Закрыта", "Срок вышел", "Отменена клиентом"};
    static final int[] KIND_COLOR = {0xffff8f1f, 0xffffb74d, 0xff4caf50, 0xffff8a80, 0xff9aa1b0};
    static final int[] KIND_BG = {0xff2a2013, 0x26ffb74d, 0x264caf50, 0x26ff8a80, 0x269aa1b0};

    /** Общее состояние заявки из частей: есть новая — «Новая», иначе есть в аренде — «В аренде», иначе «Закрыта». */
    static String orderStatus(List<String> parts) {
        if (parts == null || parts.isEmpty()) return "new";
        boolean rented = false, open = false, done = false;
        for (String p : parts) {
            String s = norm(p);
            if ("new".equals(s)) open = true;
            else if ("rented".equals(s)) rented = true;
            else if ("closed".equals(s)) done = true;
        }
        return open ? "new" : rented ? "rented" : done ? "closed" : "cancelled";   // 10.122: все части отменены — «Отменена клиентом»
    }

    /** 1 сутки, 2 суток, 5 суток, 21 сутки. */
    static String days(int n) {
        int m10 = n % 10, m100 = n % 100;
        return n + (m10 == 1 && m100 != 11 ? " сутки" : " суток");
    }

    /** 1200 → «1 200 ₽», 99.5 → «99,50 ₽»; NaN / меньше нуля — «». */
    static String money(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return "";
        if (Math.abs(v - Math.rint(v)) < 0.005) {
            String s = String.valueOf(Math.round(v));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                if (i > 0 && (s.length() - i) % 3 == 0) b.append('\u00a0');
                b.append(s.charAt(i));
            }
            return b.append("\u00a0₽").toString();
        }
        return String.format(Locale.ROOT, "%.2f", v).replace('.', ',') + "\u00a0₽";
    }

    static double num(Object o) {
        if (o == null || o == JSONObject.NULL) return Double.NaN;
        try { return o instanceof Number ? ((Number) o).doubleValue() : Double.parseDouble(String.valueOf(o).trim().replace(',', '.')); }
        catch (Throwable e) { return Double.NaN; }
    }

    static int intOf(Object o, int def) {
        double d = num(o);
        return Double.isNaN(d) ? def : (int) Math.round(d);
    }

    /** «3 суток · 400 ₽/сутки · залог 2 000 ₽» (пустое не пишем). */
    static String itemLine(int d, double price, double deposit) {
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(days(d));
        String p = money(price);
        if (!p.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(p).append("/сутки");
        String z = deposit > 0 ? money(deposit) : "";
        if (!z.isEmpty()) b.append(b.length() > 0 ? " · " : "").append("залог ").append(z);
        return b.toString();
    }

    /** Сумма за инструмент: цена × сутки; чего-то нет — NaN. */
    static double itemSum(int d, double price) {
        return d > 0 && !Double.isNaN(price) && price >= 0 ? d * price : Double.NaN;
    }

    /** «Склад · Новосибирск, ул. Ленина, 1» — название и адрес (город — если его нет в адресе). */
    static String pointLine(String name, String address, String city) {
        String n = name == null ? "" : name.trim(), ad = address == null ? "" : address.trim(), c = city == null ? "" : city.trim();
        if (!c.isEmpty() && !ad.toLowerCase(Locale.ROOT).contains(c.toLowerCase(Locale.ROOT))) ad = ad.isEmpty() ? c : c + ", " + ad;
        return n.isEmpty() ? ad : ad.isEmpty() ? n : n + " · " + ad;
    }

    static String hm(int m) { return (m / 60) + ":" + (m % 60 < 10 ? "0" : "") + (m % 60); }

    /** График пункта на день (0 — Пн … 6 — Вс): «сегодня 9:00–19:00» / «сегодня выходной» / «» (графика нет). */
    static String today(int[] open, int[] close, int dow) {
        if (open == null || close == null || open.length != 7 || close.length != 7 || dow < 0 || dow > 6) return "";
        if (open[dow] < 0 || close[dow] < 0) return "сегодня выходной";
        return "сегодня " + hm(open[dow]) + "–" + hm(close[dow]);
    }

    static int[] ints(JSONArray a) {
        if (a == null || a.length() != 7) return null;
        int[] out = new int[7];
        for (int i = 0; i < 7; i++) out[i] = a.optInt(i, -1);
        return out;
    }

    /** Срок части в сутках: наибольший у инструментов, иначе срок заявки. */
    static int partDays(JSONArray items, int orderDays) {
        int d = 0;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) d = Math.max(d, Math.min(365, intOf(it.opt("days"), 0)));
        }
        return d > 0 ? d : Math.max(0, Math.min(365, orderDays));
    }

    // ───────────── загрузка (фон) ─────────────

    /** Фон. Загружает заявки своей роли. false — не получилось (нет SQL, нет связи): тогда показываем как в 10.120. */
    static boolean load(Context a, String scope) {
        String b = body(scope);
        if (b == null || System.currentTimeMillis() < noDbUntil) return false;
        try {
            String[] r = a instanceof Activity ? IvaClientPatch.apiCall((Activity) a, RPC, "POST", b, true) : IvaNotifyPatch.api(a, RPC, "POST", b);
            if (r == null) return false;
            String t = r.length > 1 && r[1] != null ? r[1].trim() : "";
            if ("404".equals(r[0]) || t.contains("PGRST202")) { noDbUntil = System.currentTimeMillis() + NO_DB_MS; return false; }
            if (!r[0].startsWith("2") || !t.startsWith("[")) return false;
            JSONArray arr = new JSONArray(t);
            Map<String, JSONObject> got = new HashMap<String, JSONObject>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if ("client".equals(scope)) got.put("o:" + o.optString("order_id", ""), o);
                else got.put("p:" + o.optString("part_id", ""), o);
            }
            String pre = "client".equals(scope) ? "o:" : "p:";
            synchronized (cards) {
                List<String> drop = new ArrayList<String>();
                for (String k : cards.keySet()) if (k.startsWith(pre)) drop.add(k);
                for (String k : drop) cards.remove(k);
                cards.putAll(got);
            }
            timers(got.values(), "client".equals(scope));
            return true;
        } catch (Throwable e) { return false; }
    }

    /** Таймер: сроки выданных частей — в те же списки, что рисует IvaRentPatch (если их ещё нет). */
    static void timers(java.util.Collection<JSONObject> rows, boolean client) {
        for (JSONObject o : rows) {
            JSONArray ps = o.optJSONArray("parts");
            for (int i = 0; ps != null && i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null || !"rented".equals(norm(p.optString("status", "")))) continue;
                long at = IvaChatPatch.parseIso(p.optString("rented_at", ""));
                if (at <= 0) continue;
                String pid = p.optString("part_id", ""), oid = p.optString("order_id", o.optString("order_id", ""));
                int d = partDays(p.optJSONArray("items"), intOf(o.opt("days"), 0));
                List<IvaNotifyPatch.RentEnd> es = IvaNotifyPatch.rentEnds(pid, oid, p.optString("tools_text", ""), d, at, IvaRentPatch.ruleOf(p));
                if (es.isEmpty()) continue;
                if (client) {
                    synchronized (IvaRentPatch.byOrder) {
                        List<IvaNotifyPatch.RentEnd> l = IvaRentPatch.byOrder.get(oid);
                        if (l == null) { IvaRentPatch.byOrder.put(oid, l = new ArrayList<IvaNotifyPatch.RentEnd>()); }
                        java.util.Set<String> have = new java.util.HashSet<String>();
                        for (IvaNotifyPatch.RentEnd e : l) have.add(e.key);
                        for (IvaNotifyPatch.RentEnd e : es) if (!have.contains(e.key)) l.add(e);
                    }
                } else IvaRentPatch.byPart.put(pid, es);
            }
        }
    }

    static JSONObject order(String orderId) { return orderId == null || orderId.trim().isEmpty() ? null : cards.get("o:" + orderId.trim()); }

    static JSONObject part(String partId) { return partId == null || partId.trim().isEmpty() ? null : cards.get("p:" + partId.trim()); }

    /** Владелец открыл «Заявки»: свежие данные не старше 20 с (или появилась незнакомая часть) — фоном, потом перерисовать. */
    static void admNeed(final Activity a, List<?> parts) {
        if (admBusy || System.currentTimeMillis() < noDbUntil) return;
        StringBuilder sig = new StringBuilder();
        boolean unknown = false;
        if (parts != null) for (Object o : parts) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            String id = j.optString("id", "");
            sig.append(id).append(':').append(j.optString("status", "")).append(';');
            if (part(id) == null) unknown = true;
        }
        long now = System.currentTimeMillis();
        boolean changed = !sig.toString().equals(admSig);
        if (!(unknown && changed) && now - admAt < ADM_FRESH_MS) return;
        admSig = sig.toString();
        admAt = now;
        admBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try { ok = load(a, "admin"); } catch (Throwable ignore) {}
                admBusy = false;
                if (ok) IvaOrderPatch.ordH().post(new Runnable() {          // штатный список заново — карточки с данными
                    @Override public void run() { try { IvaOrderPointPatch.render(a); } catch (Throwable ignore) {} }
                });
            }
        }).start();
    }

    // ───────────── вид ─────────────

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static int col(Activity a, String n, int def) { return IvaOrderPatch.color(a, n, def); }

    static TextView tx(Activity a, String s, float sp, int color, boolean bold) { return IvaChiefPatch.text(a, s, sp, color, bold); }

    /** Плашка состояния, как на сайте: цвет текста и мягкий фон того же цвета, скругление 10. */
    static TextView badge(Activity a, int k) {
        TextView b = tx(a, KIND_TEXT[k], 12f, KIND_COLOR[k], true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(KIND_BG[k]);
        g.setCornerRadius(dp(a, 10));
        b.setBackground(g);
        b.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        return b;
    }

    /** Конец аренды части (0 — не выдана или нет данных). */
    static long endOf(JSONObject p, int orderDays) {
        if (p == null || !"rented".equals(norm(p.optString("status", "")))) return 0;
        long at = IvaChatPatch.parseIso(p.optString("rented_at", ""));
        int d = partDays(p.optJSONArray("items"), orderDays);
        return IvaRentPatch.end(at, d, IvaRentPatch.ruleOf(p));
    }

    /** Верх карточки: плашка слева, «дата · №» справа. */
    static View head(Activity a, int k, String date, String no) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(badge(a, k), new LinearLayout.LayoutParams(-2, -2));
        String r = (date == null ? "" : date.trim()) + (no == null || no.isEmpty() ? "" : (date == null || date.trim().isEmpty() ? "" : " · ") + "№" + no);
        TextView t = tx(a, r, 12f, col(a, "gray", 0xff9aa1b0), false);
        t.setGravity(Gravity.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(a, 10);
        row.addView(t, tl);
        return row;
    }

    /** Инструменты строками: фото 48, название, «срок · цена · залог», справа сумма. Возвращает {сумма, залог}. */
    static double[] items(Activity a, LinearLayout box, JSONArray items, int orderDays, boolean big) {
        double sum = 0, dep = 0;
        boolean sumOk = items != null && items.length() > 0;
        int cText = col(a, "text", 0xfff2f4f8), cGray = col(a, "gray", 0xff9aa1b0);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            int d = intOf(it.opt("days"), 0);
            if (d <= 0) d = orderDays;
            double price = num(it.opt("price")), deposit = num(it.opt("deposit"));
            double s = itemSum(d, price);
            if (Double.isNaN(s)) sumOk = false; else sum += s;
            if (!Double.isNaN(deposit) && deposit > 0) dep += deposit;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            try {
                View pic = IvaChatPatch.toolPic(a, it, it.optString("id", "i" + i), PIC_DP);
                row.addView(pic, new LinearLayout.LayoutParams(dp(a, PIC_DP), dp(a, PIC_DP)));
            } catch (Throwable ignore) {}
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            String nm = it.optString("name", "").trim();
            c.addView(tx(a, nm.isEmpty() || "null".equals(nm) ? "Инструмент" : nm, big ? 15f : 14f, cText, true));
            String l = itemLine(d, price, deposit);
            if (!l.isEmpty()) {
                TextView lt = tx(a, l, 12f, cGray, false);
                lt.setPadding(0, dp(a, 2), 0, 0);
                c.addView(lt);
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            cl.leftMargin = dp(a, 12);
            row.addView(c, cl);
            if (!Double.isNaN(s)) {
                TextView st = tx(a, money(s), 14f, cText, true);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
                sl.leftMargin = dp(a, 8);
                row.addView(st, sl);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(a, i == 0 ? 12 : 10);
            box.addView(row, rl);
        }
        return new double[]{sumOk ? sum : Double.NaN, dep};
    }

    static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(col(a, "line", 0xff262b35));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, Math.max(1, dp(a, 1)));
        l.topMargin = dp(a, 12);
        v.setLayoutParams(l);
        return v;
    }

    /** «Подпись» серым и значение под ней (как поля карточки на сайте). */
    static void field(Activity a, LinearLayout box, String label, String value, boolean accent) {
        if (value == null || value.trim().isEmpty()) return;
        TextView l = tx(a, label, 12f, col(a, "gray", 0xff9aa1b0), false);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = dp(a, 10);
        box.addView(l, ll);
        TextView v = tx(a, value.trim(), 14f, accent ? col(a, "orange", 0xffff8f1f) : col(a, "text", 0xfff2f4f8), accent);
        v.setPadding(0, dp(a, 2), 0, 0);
        box.addView(v);
    }

    static String pointText(JSONObject p, boolean withToday) {
        JSONObject pt = p == null ? null : p.optJSONObject("point");
        if (pt == null) {
            String ad = p == null ? "" : p.optString("prokat_addr", "").trim();
            return "null".equals(ad) ? "" : ad;
        }
        String s = pointLine(pt.optString("name", ""), pt.optString("address", ""), pt.optString("city", ""));
        if (withToday) {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone(p.optString("tz", "Europe/Moscow")));
            String t = today(ints(pt.optJSONArray("open_min")), ints(pt.optJSONArray("close_min")), IvaNotifyPatch.dowMon(c.get(Calendar.DAY_OF_WEEK)));
            if (!t.isEmpty()) s += "\n" + t.substring(0, 1).toUpperCase(new Locale("ru")) + t.substring(1);
        }
        return s;
    }

    /** Телефон для «Позвонить» у клиента: пункта (или проката, пока в пункте нет сотрудника — так отдаёт база). */
    static String pointTel(String orderId) {
        JSONObject o = order(orderId);
        JSONArray ps = o == null ? null : o.optJSONArray("parts");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null || "closed".equals(norm(p.optString("status", "")))) continue;
            JSONObject pt = p.optJSONObject("point");
            String t = pt == null ? "" : pt.optString("tel", "").trim();
            if (t.isEmpty() || "null".equals(t)) t = pt == null ? p.optString("prokat_tel", "").trim() : "";
            if (!t.isEmpty() && !"null".equals(t)) return t;
        }
        return "";
    }

    static List<String> statuses(JSONArray ps) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; ps != null && i < ps.length(); i++) { JSONObject p = ps.optJSONObject(i); if (p != null) out.add(p.optString("status", "")); }
        return out;
    }

    /** Карточка заявки клиента целиком. null — данных нет (тогда штатная/прежняя карточка 10.120). */
    static LinearLayout clientCard(final Activity a, Map<String, String> r, boolean archived) {
        JSONObject o = order(IvaOrderPatch.ordVal(r.get("id")));
        if (o == null) return null;
        JSONArray ps = o.optJSONArray("parts");
        if (ps == null || ps.length() == 0) return null;
        int orderDays = intOf(o.opt("days"), 0);
        long now = System.currentTimeMillis(), end = 0;
        for (int i = 0; i < ps.length(); i++) {
            long e = endOf(ps.optJSONObject(i), orderDays);
            if (e > 0 && (end == 0 || e < end)) end = e;
        }
        String st = orderStatus(statuses(ps));
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setTag(VIEW_TAG);
        c.addView(head(a, kind(st, end, now), IvaOrderPatch.ordNice(a, o.optString("created_at", "")), o.optString("order_id", "")));
        double sum = 0, dep = 0;
        boolean sumOk = true;
        int cGray = col(a, "gray", 0xff9aa1b0);
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            if (ps.length() > 1) {                                       // несколько прокатов — у каждой части свой заголовок
                String co = p.optString("company", "").trim();
                LinearLayout h = new LinearLayout(a);
                h.setOrientation(LinearLayout.HORIZONTAL);
                h.setGravity(Gravity.CENTER_VERTICAL);
                h.addView(tx(a, co.isEmpty() ? "Прокат" : "«" + co + "»", 13f, col(a, "text", 0xfff2f4f8), true), new LinearLayout.LayoutParams(0, -2, 1f));
                h.addView(badge(a, kind(p.optString("status", ""), endOf(p, orderDays), now)));
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
                hl.topMargin = dp(a, 12);
                c.addView(h, hl);
            }
            double[] s = items(a, c, p.optJSONArray("items"), orderDays, false);
            if (Double.isNaN(s[0])) sumOk = false; else sum += s[0];
            dep += s[1];
            String pt = pointText(p, false);
            if (!pt.isEmpty()) {
                TextView t = tx(a, "Пункт выдачи: " + pt, 12f, cGray, false);
                t.setPadding(0, dp(a, 8), 0, 0);
                c.addView(t);
            }
        }
        String gm = o.optString("get_method", "").trim();
        if (!gm.isEmpty() && !"null".equals(gm)) {
            TextView g = tx(a, "Получение: " + gm, 12f, cGray, false);
            g.setPadding(0, dp(a, 4), 0, 0);
            c.addView(g);
        }
        if (!archived) try { IvaRentPatch.clientCard(a, c, r); } catch (Throwable ignore) {}   // таймер «дд.чч.мм»
        c.addView(divider(a));
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        String total = sumOk && sum > 0 ? "Итого " + money(sum) : "";
        if (dep > 0) total += (total.isEmpty() ? "Залог " : " · залог ") + money(dep);
        foot.addView(tx(a, total, 14f, col(a, "text", 0xfff2f4f8), true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView more = tx(a, IvaOrderPatch.ORD_MORE, 12f, col(a, "orange", 0xffff8f1f), true);
        more.setTag(IvaOrderPatch.ORD_MORE_TAG);
        foot.addView(more);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(a, 10);
        c.addView(foot, fl);
        return c;
    }

    /** Подробности: «Состав заявки» — инструменты с фото и ценой, у каждой части пункт с графиком и телефоном, итог. */
    static void sheet(Activity a, LinearLayout body, Map<String, String> r, boolean adm) {
        JSONObject o = adm ? part(IvaOrderPatch.ordVal(r.get("part_id"))) : order(IvaOrderPatch.ordVal(r.get("id")));
        if (o == null) return;
        JSONArray ps = o.optJSONArray("parts");
        if (ps == null || ps.length() == 0) return;
        int orderDays = intOf(o.opt("days"), 0);
        LinearLayout card = IvaChiefPatch.card(a);
        card.setTag(VIEW_TAG);
        TextView h = tx(a, "Состав заявки", 16f, col(a, "text", 0xfff2f4f8), true);
        card.addView(h);
        double sum = 0, dep = 0;
        boolean sumOk = true;
        long now = System.currentTimeMillis();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            if (i > 0) card.addView(divider(a));
            if (ps.length() > 1) {
                String co = p.optString("company", "").trim();
                LinearLayout hr = new LinearLayout(a);
                hr.setOrientation(LinearLayout.HORIZONTAL);
                hr.setGravity(Gravity.CENTER_VERTICAL);
                hr.addView(tx(a, co.isEmpty() ? "Прокат" : "«" + co + "»", 14f, col(a, "text", 0xfff2f4f8), true), new LinearLayout.LayoutParams(0, -2, 1f));
                hr.addView(badge(a, kind(p.optString("status", ""), endOf(p, orderDays), now)));
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
                hl.topMargin = dp(a, 10);
                card.addView(hr, hl);
            }
            double[] s = items(a, card, p.optJSONArray("items"), orderDays, true);
            if (Double.isNaN(s[0])) sumOk = false; else sum += s[0];
            dep += s[1];
            field(a, card, "Пункт выдачи", pointText(p, true), false);
            JSONObject pt = p.optJSONObject("point");
            String tel = pt == null ? p.optString("prokat_tel", "") : pt.optString("tel", "");
            if (!adm) field(a, card, "Телефон", "null".equals(tel) ? "" : tel, true);
            String wh = p.optString("work_hours", "").trim();
            if (pt == null && !wh.isEmpty() && !"null".equals(wh)) field(a, card, "Часы работы", wh, false);
            long rented = IvaChatPatch.parseIso(p.optString("rented_at", ""));
            if (rented > 0 && !"new".equals(norm(p.optString("status", "")))) {
                String when = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new java.util.Date(rented));
                long e = endOf(p, orderDays);
                String until = e > 0 ? new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new java.util.Date(e)) : "";
                field(a, card, "Выдано", when + (until.isEmpty() || !"rented".equals(norm(p.optString("status", ""))) ? "" : " · вернуть до " + until), false);
            }
            if (adm && p.has("fee") && !p.isNull("fee")) {
                double fee = num(p.opt("fee"));
                if (!Double.isNaN(fee)) field(a, card, "Комиссия 5 %", money(fee), false);
            }
        }
        String gm = o.optString("get_method", "").trim();
        if (!"null".equals(gm)) field(a, card, "Получение", gm, false);
        String ad = o.optString("address", "").trim();
        if (!ad.isEmpty() && !"null".equals(ad) && gm.toLowerCase(Locale.ROOT).contains("достав")) field(a, card, "Адрес доставки", ad, false);
        String total = sumOk && sum > 0 ? money(sum) : "";
        if (!total.isEmpty() || dep > 0) {
            card.addView(divider(a));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(tx(a, "Итого за аренду", 14f, col(a, "gray", 0xff9aa1b0), false), new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(tx(a, total.isEmpty() ? "—" : total, 17f, col(a, "orange", 0xffff8f1f), true));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(a, 10);
            card.addView(row, rl);
            if (dep > 0) {
                TextView z = tx(a, "Залог при выдаче: " + money(dep) + " — вернут при возврате", 12f, col(a, "gray", 0xff9aa1b0), false);
                z.setPadding(0, dp(a, 4), 0, 0);
                card.addView(z);
            }
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(a, 14);
        body.addView(card, cl);
        try { IvaOrderEditPatch.block(a, body, o, adm); } catch (Throwable ignore) {}   // 10.122: отмена / изменение заявки
    }

    /** Карточка заявки у владельца (штатный список заявок клиента): плашка и «№», инструменты с фото вместо строки названий. */
    static boolean admCard(Activity a, LinearLayout card, String partId) {
        if (card.findViewWithTag(VIEW_TAG) != null) return true;
        JSONObject o = part(partId);
        JSONArray ps = o == null ? null : o.optJSONArray("parts");
        JSONObject p = ps == null || ps.length() == 0 ? null : ps.optJSONObject(0);
        if (p == null || card.getOrientation() != LinearLayout.VERTICAL || card.getChildCount() < 2
            || !(card.getChildAt(0) instanceof TextView) || !(card.getChildAt(1) instanceof TextView)) return false;
        int orderDays = intOf(o.opt("days"), 0);
        TextView date = (TextView) card.getChildAt(0), names = (TextView) card.getChildAt(1);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(VIEW_TAG);
        box.addView(head(a, kind(p.optString("status", ""), endOf(p, orderDays), System.currentTimeMillis()), String.valueOf(date.getText()), o.optString("order_id", "")));
        items(a, box, p.optJSONArray("items"), orderDays, false);
        String pt = pointText(p, false);
        if (!pt.isEmpty()) {
            TextView t = tx(a, "Пункт выдачи: " + pt, 12f, col(a, "gray", 0xff9aa1b0), false);
            t.setPadding(0, dp(a, 8), 0, 0);
            box.addView(t);
        }
        try { TextView pl = IvaOrderEditPatch.pendingLine(a, p); if (pl != null) box.addView(pl); } catch (Throwable ignore) {}   // 10.122
        date.setVisibility(View.GONE);                                  // штатные строки не переносим — прячем, наши — вместо них
        names.setVisibility(View.GONE);
        card.addView(box, 2, new LinearLayout.LayoutParams(-1, -2));
        return true;
    }

    /** «Заявки пункта» у сотрудника: инструменты с фото и пункт с адресом вместо строки названий. */
    static View staffItems(Activity a, String partId) {
        JSONObject o = part(partId);
        JSONArray ps = o == null ? null : o.optJSONArray("parts");
        JSONObject p = ps == null || ps.length() == 0 ? null : ps.optJSONObject(0);
        if (p == null) return null;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(VIEW_TAG);
        double[] s = items(a, box, p.optJSONArray("items"), intOf(o.opt("days"), 0), false);
        if (!Double.isNaN(s[0]) && s[0] > 0) {
            TextView t = tx(a, "Итого " + money(s[0]) + (s[1] > 0 ? " · залог " + money(s[1]) : ""), 13f, col(a, "text", 0xfff2f4f8), true);
            t.setPadding(0, dp(a, 8), 0, 0);
            box.addView(t);
        }
        return box;
    }

    /** Пункт сотрудника: «Склад · адрес» (или пусто). */
    static String staffPoint(String partId) {
        JSONObject o = part(partId);
        JSONArray ps = o == null ? null : o.optJSONArray("parts");
        return ps == null || ps.length() == 0 ? "" : pointText(ps.optJSONObject(0), false);
    }

    /** Конец аренды части сотрудника / владельца (для плашки «Срок вышел»). */
    static long partEnd(String partId) {
        JSONObject o = part(partId);
        JSONArray ps = o == null ? null : o.optJSONArray("parts");
        return ps == null || ps.length() == 0 ? 0 : endOf(ps.optJSONObject(0), intOf(o.opt("days"), 0));
    }

    /** Есть ли данные заявки (подробности: строку «Инструменты» и прежний блок пункта не повторяем). */
    static boolean has(Map<String, String> r, boolean adm) {
        if (r == null) return false;
        return (adm ? part(IvaOrderPatch.ordVal(r.get("part_id"))) : order(IvaOrderPatch.ordVal(r.get("id")))) != null;
    }
}

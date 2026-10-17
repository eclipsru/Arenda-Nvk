package ru.prokatnvsk.app;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 10.102 — этап 2 «Сотрудники и пункты выдачи»: заявки по пунктам (решения владельца 17.10.2026,
 * docs/СОТРУДНИКИ-И-ПУНКТЫ.md; база — SQL 20261027_order_points.sql).
 *
 *  * Заявка уходит со списком инструментов и сроком каждого (iva_submit_order, p_items) — база делит её по
 *    «прокат + пункт» и считает комиссию по инструментам части. Нет SQL (ответ «нет такой функции») — заявка уходит
 *    штатной submit_order, как в 10.101. Другие ошибки не повторяем (заявка могла сохраниться).
 *  * «Самовывоз» в итоге и в заявке — адрес пункта инструментов (у инструмента с пунктом база хранит адрес пункта);
 *    нет — как раньше (адрес проката).
 *  * Прокат с двумя и больше пунктами: над списком «Заявок» — «Все пункты / Пункт 1 / …». Фильтр — по пункту части
 *    заявки (order_parts.point_id): штатный список (admParts) подменяется отобранным и перерисовывается штатным
 *    renderAdmOrders; новая загрузка штатного списка — фильтр применяется заново. В подробностях заявки —
 *    «Пункт выдачи».
 *  * Клиент: в подробностях заявки по каждой части — пункт, адрес, время работы, телефон (iva_my_part_points);
 *    «Адрес выдачи» и «Часы работы» проката тогда не показываются (их заменяет пункт).
 *  Новых вставок в smali нет: вызовы — из IvaOrderPatch (отправка, admPics, ordRender, ordFetch, ordOpenRow) и
 *  IvaNotifyPatch.openOrder (переход из уведомления — фильтр сбрасывается на «Все пункты»).
 */
final class IvaOrderPointPatch {
    private IvaOrderPointPatch() {}

    static final String SUBMIT_RPC = "/rest/v1/rpc/iva_submit_order";
    static final String OLD_RPC = "/rest/v1/rpc/submit_order";
    static final String CLIENT_RPC = "/rest/v1/rpc/iva_my_part_points";
    static final String PARTS_PATH = "/rest/v1/order_parts?select=id,point_id&order=id.desc&limit=300&owner_email=eq.";
    static final String TAG = "iva_ord_point";
    static final String ALL = "Все пункты", LABEL = "Пункт выдачи";
    static final long FRESH_MS = 60_000L, MISS_MS = 5_000L, NO_RPC_MS = 10 * 60_000L;
    static final int MAX_ITEMS = 50;

    // ════════════════════════════════ чистая логика (IvaPatchTest) ════════════════════════════════

    static boolean uuid(String s) { return IvaToolPointPatch.uuid(s); }

    /** Список для p_items: [{"id":"…","days":N}] — только настоящие id, без повторов, не больше 50; пусто — null. */
    static String itemsJson(List<IvaOrderPatch.Line> ls) {
        if (ls == null) return null;
        Set<String> seen = new LinkedHashSet<String>();
        StringBuilder b = new StringBuilder("[");
        for (IvaOrderPatch.Line l : ls) {
            if (l == null || l.srv || !uuid(l.id)) continue;
            String id = l.id.toLowerCase(Locale.ROOT);
            if (!seen.add(id)) continue;
            if (seen.size() > MAX_ITEMS) break;
            if (b.length() > 1) b.append(',');
            b.append("{\"id\":\"").append(id).append("\",\"days\":").append(IvaOrderPatch.clampDays(l.days)).append('}');
        }
        return b.length() == 1 ? null : b.append(']').toString();
    }

    /**
     * Ответ «такой функции нет» (SQL 10.102 не применён) — только тогда заявку можно отправить штатно ещё раз:
     * заявка точно не сохранилась. Любой другой ответ (и ошибка сети) — не повторяем.
     */
    static boolean noRpc(String code, String body) {
        String b = body == null ? "" : body;
        return "404".equals(code) && (b.contains("PGRST202") || (b.contains("iva_submit_order") && b.contains("Could not find")));
    }

    /** Адрес самовывоза: адреса пунктов инструментов без повторов («; »); нет ни одного — прежний. */
    static String joinAddr(List<String> addrs, String fallback) {
        Set<String> u = new LinkedHashSet<String>();
        if (addrs != null) for (String s : addrs) { String t = s == null ? "" : s.trim(); if (!t.isEmpty() && !"null".equals(t)) u.add(t); }
        if (u.isEmpty()) return fallback == null ? "" : fallback;
        StringBuilder b = new StringBuilder();
        for (String s : u) { if (b.length() > 0) b.append("; "); b.append(s); }
        return b.toString();
    }

    /** Какие части оставить фильтром: sel пусто — все; иначе — у которых пункт sel (номера по порядку). */
    static List<Integer> keep(List<String> partIds, Map<String, String> partPoint, String sel) {
        List<Integer> out = new ArrayList<Integer>();
        if (partIds == null) return out;
        String s = sel == null ? "" : sel;
        for (int i = 0; i < partIds.size(); i++) {
            if (s.isEmpty()) { out.add(i); continue; }
            String p = partPoint == null ? null : partPoint.get(partIds.get(i));
            if (s.equalsIgnoreCase(p == null ? "" : p)) out.add(i);
        }
        return out;
    }

    /** Подпись пункта: «Склад · Новосибирск» (как кнопки в форме). */
    static String pointText(IvaPickupPatch.Point p) { return p == null ? "" : IvaToolPointPatch.chipText(p); }

    /** Строки подробностей клиента по частям: пункт, инструменты (если частей больше одной), адрес, время, телефон. */
    static List<String[]> clientFields(List<IvaPickupPatch.Point> ps, List<String> tools) {
        List<String[]> out = new ArrayList<String[]>();
        if (ps == null) return out;
        for (int i = 0; i < ps.size(); i++) {
            IvaPickupPatch.Point p = ps.get(i);
            add(out, LABEL, pointText(p));
            if (ps.size() > 1 && tools != null && i < tools.size()) add(out, "Инструменты", tools.get(i));
            add(out, "Адрес", p.address);
            add(out, "Время работы", IvaPickupPatch.hoursText(p.open, p.close));
            add(out, "Телефон", p.tel);
        }
        return out;
    }

    static void add(List<String[]> out, String label, String v) {
        String t = v == null ? "" : v.trim();
        if (!t.isEmpty() && !"null".equals(t)) out.add(new String[]{label, t});
    }

    /** Подпись набора частей (для сравнения «список уже отфильтрован нами»). */
    static String sig(List<?> parts) {
        StringBuilder b = new StringBuilder();
        if (parts != null) for (Object o : parts) b.append(o instanceof JSONObject ? ((JSONObject) o).optString("id", "") : "?").append(',');
        return b.toString();
    }

    // ════════════════════════════════ отправка заявки ════════════════════════════════

    static volatile long noRpcAt = 0;

    /** Из IvaOrderPatch.sendOrder (в своём потоке): со списком — iva_submit_order; нет функции — штатная submit_order. */
    static String[] submit(Activity a, JSONObject body, List<IvaOrderPatch.Line> ls) throws Exception {
        String its = itemsJson(ls);
        if (its != null && System.currentTimeMillis() - noRpcAt > NO_RPC_MS) {
            JSONObject b2 = new JSONObject(body.toString());
            b2.put("p_items", new JSONArray(its));
            String[] r = IvaClientPatch.apiCall(a, SUBMIT_RPC, "POST", b2.toString(), true);
            if (r == null || !noRpc(r[0], r.length > 1 ? r[1] : "")) return r;
            noRpcAt = System.currentTimeMillis();                       // без SQL 10.102 — штатно, как 10.101
        }
        return IvaClientPatch.apiCall(a, OLD_RPC, "POST", body.toString(), true);
    }

    /** Адрес самовывоза по пунктам инструментов корзины (каталог в памяти); нет — fallback. */
    static String pickupFor(Activity a, List<IvaOrderPatch.Line> ls, String fallback) {
        try {
            Map<String, String> addr = new HashMap<String, String>();
            for (Object o : IvaSearchPatch.allToolsQuiet(a)) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject j = (JSONObject) o;
                if (!uuid(IvaToolPointPatch.pointOf(j))) continue;
                String ad = j.isNull("pickup_address") ? "" : j.optString("pickup_address", "");
                addr.put(j.optString("id", ""), ad);
            }
            List<String> got = new ArrayList<String>();
            for (IvaOrderPatch.Line l : ls) { String s = addr.get(l.id); if (s != null) got.add(s); }
            return joinAddr(got, fallback);
        } catch (Throwable e) { return fallback; }
    }

    // ════════════════════════════════ прокат: пункты частей и фильтр ════════════════════════════════

    static final Map<String, String> partPoint = java.util.Collections.synchronizedMap(new HashMap<String, String>());
    static volatile List<IvaPickupPatch.Point> points = null;
    static volatile String admFor = "";
    static volatile long admAt = 0;
    static volatile boolean admLoading = false, admCol = false;
    static volatile String sel = "";
    static ArrayList<Object> full, mineList;
    static String mineSig = "";

    /** Пункты проката и пункты его частей — фоном: устарели или в списке есть часть, которой нет в ответе. */
    static void admNeed(final Activity a, List<?> parts) {
        final String me = IvaPickupPatch.me(a);
        if (me.isEmpty() || admLoading) return;
        long now = System.currentTimeMillis();
        boolean miss = false;
        if (parts != null && admCol) for (Object o : parts)
            if (o instanceof JSONObject && !partPoint.containsKey(((JSONObject) o).optString("id", ""))) { miss = true; break; }
        if (me.equals(admFor) && now - admAt < FRESH_MS && !(miss && now - admAt > MISS_MS)) return;
        admLoading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                List<IvaPickupPatch.Point> ps = null;
                Map<String, String> map = null;
                boolean col = false;
                try {
                    ps = IvaPickupPatch.load(a, new String[1]);
                    if (ps != null && !ps.isEmpty()) {
                        String[] r = IvaClientPatch.apiCall(a, PARTS_PATH + java.net.URLEncoder.encode(me, "UTF-8"), "GET", null, true);
                        if (r != null && r[0] != null && r[0].startsWith("2")) {
                            col = true;
                            map = new HashMap<String, String>();
                            JSONArray arr = new JSONArray(r.length > 1 && r[1] != null ? r[1].trim() : "[]");
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o == null) continue;
                                String pid = o.isNull("point_id") ? "" : o.optString("point_id", "");
                                map.put(o.optString("id", ""), uuid(pid) ? pid.toLowerCase(Locale.ROOT) : "");
                            }
                        }
                    }
                } catch (Throwable ignore) {}
                final List<IvaPickupPatch.Point> fps = ps;
                final Map<String, String> fmap = map;
                final boolean fcol = col;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        admLoading = false;
                        if (!me.equals(IvaPickupPatch.me(a))) return;          // вход сменился
                        admFor = me;
                        admAt = System.currentTimeMillis();
                        if (fps != null) points = fps;
                        admCol = fcol;
                        if (fmap != null) { synchronized (partPoint) { partPoint.clear(); partPoint.putAll(fmap); } }
                        try { admFilter(a); } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    /** Фильтр виден: у проката два и больше пунктов и в базе есть пункт части (SQL 10.102). */
    static boolean filterOn() {
        List<IvaPickupPatch.Point> ps = points;
        return admCol && ps != null && ps.size() >= 2;
    }

    /**
     * Из IvaOrderPatch.admPics (после штатной перерисовки списка прокату): строка фильтра и подмена admParts
     * отобранными. true — список перерисован заново (admPics продолжит на следующем проходе).
     */
    @SuppressWarnings("unchecked")
    static boolean admFilter(Activity a) {
        Object cur = IvaClientPatch.get(a, "admParts");
        if (!(cur instanceof ArrayList)) return false;
        boolean ours = cur == mineList && sig((List<?>) cur).equals(mineSig);
        if (!ours) full = (ArrayList<Object>) cur;                   // свежий штатный список
        admNeed(a, full);
        boolean on = filterOn();
        if (on && !sel.isEmpty()) {                                   // выбранного пункта больше нет — все пункты
            boolean found = false;
            for (IvaPickupPatch.Point p : points) if (p.id.equalsIgnoreCase(sel)) found = true;
            if (!found) sel = "";
        }
        paintChips(a, on);
        if (!on || sel.isEmpty()) {
            if (!ours) return false;
            mineList = null; mineSig = "";
            IvaClientPatch.set(a, "admParts", full);
            render(a);
            return true;
        }
        List<String> ids = new ArrayList<String>();
        for (Object o : full) ids.add(o instanceof JSONObject ? ((JSONObject) o).optString("id", "") : "");
        ArrayList<Object> f = new ArrayList<Object>();
        synchronized (partPoint) { for (Integer i : keep(ids, partPoint, sel)) f.add(full.get(i)); }
        if (ours && sig(f).equals(mineSig)) return false;             // уже отобрано
        mineList = f; mineSig = sig(f);
        IvaClientPatch.set(a, "admParts", f);
        render(a);
        return true;
    }

    static void render(Activity a) {
        try {
            Method m = a.getClass().getDeclaredMethod("renderAdmOrders");
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignore) {}
    }

    /** Переход из уведомления к заявке: все пункты (иначе нужной заявки может не быть в списке). */
    static void showAll(Activity a) {
        sel = "";
        Object cur = IvaClientPatch.get(a, "admParts");
        if (cur != null && cur == mineList && full != null) IvaClientPatch.set(a, "admParts", full);
        mineList = null; mineSig = "";
        try { paintChips(a, filterOn()); } catch (Throwable ignore) {}
    }

    /** Клиент (или вышли из проката) — строки фильтра нет. */
    static void hide(Activity a) {
        View v = row(a);
        if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
    }

    static View row(Activity a) {
        Object po = IvaClientPatch.get(a, "pageOrders");
        return po instanceof View ? ((View) po).findViewWithTag(TAG) : null;
    }

    static final Map<View, String> drawn = new java.util.WeakHashMap<View, String>();

    static void paintChips(final Activity a, boolean on) {
        Object po = IvaClientPatch.get(a, "pageOrders");
        if (!(po instanceof LinearLayout)) return;
        LinearLayout page = (LinearLayout) po;
        View r = page.findViewWithTag(TAG);
        if (!on) { if (r != null) r.setVisibility(View.GONE); return; }
        if (r == null) {
            View scroll = IvaOrderPatch.idView(a, "ordersScroll");
            HorizontalScrollView sc = new HorizontalScrollView(a);
            sc.setTag(TAG);
            sc.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(a);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setTag(TAG + ":chips");
            chips.setPadding(IvaPatch.dp(a, 16), IvaPatch.dp(a, 8), IvaPatch.dp(a, 8), IvaPatch.dp(a, 4));
            sc.addView(chips, new LinearLayout.LayoutParams(-2, -2));
            int at = scroll != null && scroll.getParent() == page ? page.indexOfChild(scroll) : 0;
            page.addView(sc, at, new LinearLayout.LayoutParams(-1, -2));
            r = sc;
        }
        r.setVisibility(View.VISIBLE);
        LinearLayout chips = (LinearLayout) r.findViewWithTag(TAG + ":chips");
        List<IvaPickupPatch.Point> ps = points;
        StringBuilder s = new StringBuilder(sel).append('|');
        for (IvaPickupPatch.Point p : ps) s.append(p.id).append(pointText(p)).append(';');
        if (s.toString().equals(drawn.get(chips))) return;
        drawn.put(chips, s.toString());
        chips.removeAllViews();
        chip(a, chips, ALL, "", sel.isEmpty());
        for (IvaPickupPatch.Point p : ps) chip(a, chips, pointText(p), p.id.toLowerCase(Locale.ROOT), p.id.equalsIgnoreCase(sel));
    }

    static void chip(final Activity a, LinearLayout chips, String text, final String id, boolean on) {
        TextView c = IvaSearchPatch.chip(a, text, on, false);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (id.equals(sel)) return;
                sel = id;
                IvaClientPatch.set(a, "admDrill", Boolean.FALSE);        // к списку клиентов выбранного пункта
                try { if (!admFilter(a)) render(a); } catch (Throwable ignore) {}
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.rightMargin = IvaPatch.dp(a, 8);
        chips.addView(c, cl);
    }

    // ════════════════════════════════ клиент: пункты частей ════════════════════════════════

    static final Map<String, List<IvaPickupPatch.Point>> clientPts = java.util.Collections.synchronizedMap(new HashMap<String, List<IvaPickupPatch.Point>>());
    static final Map<String, List<String>> clientTools = java.util.Collections.synchronizedMap(new HashMap<String, List<String>>());

    /** Из IvaOrderPatch.ordFetch (уже в своём потоке): пункты частей своих заявок; нет SQL — пусто. */
    static void loadClient(Activity a) {
        try {
            String[] r = IvaClientPatch.apiCall(a, CLIENT_RPC, "POST", "{}", true);
            if (r == null || r[0] == null || !r[0].startsWith("2")) return;
            JSONArray arr = new JSONArray(r.length > 1 && r[1] != null ? r[1].trim() : "[]");
            Map<String, List<IvaPickupPatch.Point>> pts = new LinkedHashMap<String, List<IvaPickupPatch.Point>>();
            Map<String, List<String>> tl = new LinkedHashMap<String, List<String>>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String oid = o.isNull("order_id") ? "" : String.valueOf(o.opt("order_id"));
                IvaPickupPatch.Point p = IvaToolPointPatch.parseCard(o);
                p.id = o.isNull("point_id") ? "" : o.optString("point_id", "");
                if (oid.isEmpty() || !uuid(p.id)) continue;
                if (!pts.containsKey(oid)) { pts.put(oid, new ArrayList<IvaPickupPatch.Point>()); tl.put(oid, new ArrayList<String>()); }
                pts.get(oid).add(p);
                tl.get(oid).add(o.isNull("tools_text") ? "" : o.optString("tools_text", ""));
            }
            synchronized (clientPts) { clientPts.clear(); clientPts.putAll(pts); }
            synchronized (clientTools) { clientTools.clear(); clientTools.putAll(tl); }
        } catch (Throwable ignore) {}
    }

    /** У заявки клиента есть пункт — «Адрес выдачи» и «Часы работы» проката не показываем. */
    static boolean clientHas(String orderId) {
        List<IvaPickupPatch.Point> ps = orderId == null ? null : clientPts.get(orderId);
        return ps != null && !ps.isEmpty();
    }

    // ════════════════════════════════ подробности заявки ════════════════════════════════

    /** Из IvaOrderPatch.ordOpenRow: прокату — «Пункт выдачи» части; клиенту — пункты частей заявки. */
    static void sheet(Activity a, LinearLayout body, Map<String, String> r, boolean adm) {
        if (adm) {
            String pid = partPoint.get(IvaOrderPatch.ordVal(r.get("part_id")));
            List<IvaPickupPatch.Point> ps = points;
            if (pid == null || pid.isEmpty() || ps == null) return;
            for (IvaPickupPatch.Point p : ps)
                if (p.id.equalsIgnoreCase(pid)) {
                    IvaOrderPatch.ordFieldView(a, body, new String[]{LABEL, pointText(p)}, false);
                    String ad = p.address == null ? "" : p.address.trim();
                    if (!ad.isEmpty()) IvaOrderPatch.ordFieldView(a, body, new String[]{"Адрес", ad}, false);
                }
            return;
        }
        String oid = IvaOrderPatch.ordVal(r.get("id"));
        List<IvaPickupPatch.Point> ps = clientPts.get(oid);
        if (ps == null || ps.isEmpty()) return;
        for (String[] f : clientFields(new ArrayList<IvaPickupPatch.Point>(ps), clientTools.get(oid)))
            IvaOrderPatch.ordFieldView(a, body, f, false);
    }
}

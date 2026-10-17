package ru.prokatnvsk.app;

import android.app.Activity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 10.101 — этап 1 «Сотрудники и пункты выдачи»: инструмент закреплён за пунктом выдачи (решения владельца 16–17.10.2026,
 * docs/СОТРУДНИКИ-И-ПУНКТЫ.md; база — SQL 20261026_tool_points.sql).
 *
 *  * В форме «Добавить инструмент» (раздел «Условия и доставка») и в правке инструмента — «Пункт выдачи»: свои пункты
 *    кнопками, под ними адрес и график выбранного. Видно, когда пунктов два и больше (в правке — и когда у инструмента
 *    пункта ещё нет, а пункт один: инструмент с сайта). Один пункт — инструмент встаёт на него сам (это делает база).
 *  * Выбор записывается после штатного успеха «Опубликовать» / «Сохранить» (штатный успех открывает кабинет —
 *    showTab("admhub"), как запись вида — IvaKindPatch). Новое объявление база уже поставила на первый пункт; пишем,
 *    только если выбран другой.
 *  * Карточка инструмента: вместо «Адрес выдачи / Время работы» проката — пункт инструмента: «Пункт выдачи», «Адрес»,
 *    «Время работы» (по дням), «Телефон» (текстом). Данные — iva_tool_points (без email пункта и владельца).
 *  * Без SQL 20261026 всё как в 10.100: поля нет, карточка — штатная.
 *  Новых вставок в smali нет: формы — IvaKindPatch.refreshRows / IvaFormPatch.setup, запись — IvaKindPatch.onSaveTouch
 *  и IvaKindPatch.onTab (оба из IvaPatch).
 */
final class IvaToolPointPatch {
    private IvaToolPointPatch() {}

    static final String TAG = "iva_tool_point";
    static final String LABEL = "Пункт выдачи";
    static final String RPC = "/rest/v1/rpc/iva_tool_points";
    static final long MINE_FRESH_MS = 2 * 60_000L, CARD_FRESH_MS = 10 * 60_000L;
    static final int MAX_IDS = 200;

    // ════════════════════════════════ чистая логика (IvaPatchTest) ════════════════════════════════

    static boolean uuid(String s) { return s != null && s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); }

    /** {"point_id":"…"}; не id — null (не шлём). */
    static String body(String id) { return uuid(id) ? "{\"point_id\":\"" + id.toLowerCase(java.util.Locale.ROOT) + "\"}" : null; }

    /** Показывать ли «Пункт выдачи»: k=0 — «Добавить», k=1 — правка. n — сколько своих пунктов; noPoint — у инструмента в правке пункта нет. */
    static boolean show(int k, int n, boolean col, boolean noPoint) {
        if (!col) return false;
        return n >= 2 || (k == 1 && n == 1 && noPoint);
    }

    /** Писать ли после успеха: «Добавить» — выбран не первый (первый ставит база); правка — выбран не тот, что был. */
    static boolean needWrite(int k, String sel, String first, String orig) {
        if (!uuid(sel)) return false;
        return k == 0 ? !sel.equalsIgnoreCase(first == null ? "" : first) : !sel.equalsIgnoreCase(orig == null ? "" : orig);
    }

    /** Выбранный в форме или (пусто) первый. */
    static String current(String sel, List<IvaPickupPatch.Point> mine) {
        if (mine == null || mine.isEmpty()) return "";
        if (sel != null && !sel.isEmpty()) for (IvaPickupPatch.Point p : mine) if (sel.equals(p.id)) return sel;
        return mine.get(0).id;
    }

    /** Тело вызова iva_tool_points: только id, без повторов, не больше 200. */
    static String rpcBody(java.util.Collection<String> ids) {
        Set<String> u = new LinkedHashSet<String>();
        if (ids != null) for (String s : ids) {
            if (u.size() >= MAX_IDS) break;
            if (uuid(s)) u.add(s.toLowerCase(java.util.Locale.ROOT));
        }
        StringBuilder b = new StringBuilder("{\"p_ids\":[");
        int i = 0;
        for (String s : u) { if (i++ > 0) b.append(','); b.append('"').append(s).append('"'); }
        return b.append("]}").toString();
    }

    /** Текст в карточке инструмента. */
    static String cardText(IvaPickupPatch.Point p) {
        if (p == null) return "";
        StringBuilder b = new StringBuilder();
        String name = p.name == null ? "" : p.name.trim();
        if (!name.isEmpty()) b.append(LABEL).append(": ").append(name);
        String ad = p.address == null ? "" : p.address.trim();
        if (!ad.isEmpty()) b.append(b.length() > 0 ? "\n" : "").append("Адрес: ").append(ad);
        String h = IvaPickupPatch.hoursText(p.open, p.close);
        if (!h.isEmpty()) b.append(b.length() > 0 ? "\n" : "").append("Время работы: ").append(h);
        String t = p.tel == null ? "" : p.tel.trim();
        if (!t.isEmpty()) b.append(b.length() > 0 ? "\n" : "").append("Телефон: ").append(t);
        return b.toString();
    }

    /** Подпись кнопки пункта: «Склад · Новосибирск» (город — если есть и его нет в названии). */
    static String chipText(IvaPickupPatch.Point p) {
        String n = p.name == null ? "" : p.name.trim(), c = p.city == null ? "" : p.city.trim();
        if (c.isEmpty() || n.toLowerCase(java.util.Locale.ROOT).contains(c.toLowerCase(java.util.Locale.ROOT))) return n;
        return n + " · " + c;
    }

    /** Под кнопками: адрес и график выбранного пункта. */
    static String subText(IvaPickupPatch.Point p) {
        if (p == null) return "";
        String ad = p.address == null ? "" : p.address.trim(), h = IvaPickupPatch.hoursText(p.open, p.close);
        return ad + (ad.isEmpty() || h.isEmpty() ? "" : "\n") + h;
    }

    /** Ответ «нет столбца point_id» — SQL 20261026 не применён. */
    static boolean noColumn(String code, String body) {
        String b = body == null ? "" : body;
        return b.contains("point_id") && (b.contains("42703") || b.contains("PGRST204") || b.contains("does not exist") || b.contains("Could not find"));
    }

    /** Запись пункта не удалась — понятной фразой. */
    static String writeError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        if (b.contains("Пункт выдачи не найден")) return "Пункт выдачи не сохранился: это пункт другого проката";
        if (noColumn(c, b)) return "Пункт выдачи заработает после обновления базы (SQL 10.101)";
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Пункт выдачи не сохранился: нет права";
        return c.isEmpty() ? "Пункт выдачи не сохранился — нет связи с сервером" : "Пункт выдачи не сохранился (ответ " + c + ")";
    }

    static IvaPickupPatch.Point parseCard(JSONObject o) {
        IvaPickupPatch.Point p = new IvaPickupPatch.Point();
        p.id = o.optString("id", "");
        p.name = o.isNull("name") ? "" : o.optString("name", "");
        p.address = o.isNull("address") ? "" : o.optString("address", "");
        p.city = o.isNull("city") ? "" : o.optString("city", "");
        p.tel = o.isNull("tel") ? "" : o.optString("tel", "");
        int[] op = IvaPickupPatch.arr(String.valueOf(o.opt("open_min"))), cl = IvaPickupPatch.arr(String.valueOf(o.opt("close_min")));
        if (op != null && cl != null) { p.open = op; p.close = cl; }
        return p;
    }

    // ════════════════════════════════ состояние ════════════════════════════════

    static volatile List<IvaPickupPatch.Point> mine = null;    // свои пункты (админ)
    static volatile String mineFor = "";
    static volatile long mineAt = 0;
    static volatile boolean mineLoading = false;
    /** В базе есть tools.point_id: null — ещё не знаем, иначе ответ последней проверки. */
    static volatile Boolean col = null;
    static final String[] sel = {"", ""};
    static volatile String editId = "", orig = "", editOwner = "";
    static volatile boolean editLoaded = false;

    static volatile long pendingAt = 0;
    static volatile int pendingForm = -1;
    static volatile String pendingPoint = "", pendingId = "", pendingName = "", pendingEmail = "";
    static volatile String pendingStaff = "";                   // 10.105: номер сотрудника «Каталог»

    static final Map<String, IvaPickupPatch.Point> cards = new HashMap<String, IvaPickupPatch.Point>();
    static final Map<String, Long> cardsAt = new HashMap<String, Long>();
    static volatile boolean cardsLoading = false;
    static final Map<View, String> drawn = new java.util.WeakHashMap<View, String>();

    // ════════════════════════════════ формы ════════════════════════════════

    /** Из IvaKindPatch.onTab: правка открыта — пункт инструмента из базы; успех — запись; карточка. */
    static void onTab(Activity a, String tab) {
        try { if ("tooledit".equals(tab)) openedEdit(a); } catch (Throwable ignore) {}
        try { consumePending(a, tab); } catch (Throwable ignore) {}
        try { if ("detail".equals(tab)) onDetail(a); } catch (Throwable ignore) {}
    }

    /** Из IvaKindPatch.refreshRows (onTab / onHub): поле в правке; свои пункты — подгрузить, если устарели. */
    static void refresh(Activity a) {
        ensureMine(a);
        Object t = IvaClientPatch.get(a, "teTerms");
        if (t instanceof View && ((View) t).getParent() instanceof LinearLayout) {
            LinearLayout form = (LinearLayout) ((View) t).getParent();
            View row = form.findViewWithTag(TAG + ":1");
            if (row == null) {
                View cond = form.findViewWithTag(IvaPatch.COND_ROW_TAG + ":teTerms");
                int at = form.indexOfChild(cond != null && cond.getParent() == form ? cond : (View) t);
                row = build(a, 1);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = IvaPatch.dp(a, 12);
                form.addView(row, at, lp);
            }
            paint(a, (LinearLayout) row, 1);
        }
    }

    /** Из IvaFormPatch.setup (форма «Добавить»): блок есть; место — IvaFormPatch.ORDER. */
    static void ensure(Activity a, LinearLayout form) {
        ensureMine(a);
        View row = form.findViewWithTag(TAG + ":0");
        if (row == null) {
            row = build(a, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = IvaPatch.dp(a, 12);
            View save = IvaPatch.view(a, "atSave");
            form.addView(row, save != null && save.getParent() == form ? form.indexOfChild(save) : form.getChildCount(), lp);
        }
        paint(a, (LinearLayout) row, 0);
    }

    static LinearLayout build(Activity a, int k) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(TAG + ":" + k);
        box.setVisibility(View.GONE);
        TextView l = new TextView(a);
        l.setText(LABEL);
        l.setTextColor(IvaPatch.color(a, "gray", 0xff9aa1b0));
        l.setTextSize(13f);
        box.addView(l, new LinearLayout.LayoutParams(-2, -2));
        HorizontalScrollView sc = new HorizontalScrollView(a);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setTag(TAG + ":chips");
        sc.addView(chips, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = IvaPatch.dp(a, 6);
        box.addView(sc, sl);
        TextView s = new TextView(a);
        s.setTag(TAG + ":sub");
        s.setTextColor(IvaPatch.color(a, "gray", 0xff9aa1b0));
        s.setTextSize(13f);
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2);
        ul.topMargin = IvaPatch.dp(a, 6);
        box.addView(s, ul);
        return box;
    }

    static void paint(final Activity a, final LinearLayout box, final int k) {
        final List<IvaPickupPatch.Point> ps = mine;
        boolean waiting = k == 1 && (!editLoaded || !ownEdit(a));   // чужой инструмент (создатель) — без поля
        boolean vis = ps != null && !waiting && show(k, ps.size(), Boolean.TRUE.equals(col), orig.isEmpty());
        box.setVisibility(vis ? View.VISIBLE : View.GONE);
        if (!vis) return;
        String cur = k == 0 ? current(sel[0], ps) : sel[1];
        LinearLayout chips = (LinearLayout) box.findViewWithTag(TAG + ":chips");
        TextView sub = (TextView) box.findViewWithTag(TAG + ":sub");
        StringBuilder sig = new StringBuilder(cur).append('|');
        for (IvaPickupPatch.Point p : ps) sig.append(p.id).append(chipText(p)).append(';');
        if (sig.toString().equals(drawn.get(chips))) return;           // ничего не менялось — не перерисовываем
        drawn.put(chips, sig.toString());
        chips.removeAllViews();
        IvaPickupPatch.Point on = null;
        for (final IvaPickupPatch.Point p : ps) {
            boolean me = p.id.equals(cur);
            if (me) on = p;
            TextView c = IvaSearchPatch.chip(a, chipText(p), me, false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sel[k] = p.id;
                    try { paint(a, box, k); } catch (Throwable ignore) {}
                }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.rightMargin = IvaPatch.dp(a, 8);
            chips.addView(c, cl);
        }
        String st = on == null ? (k == 1 ? "Пункт не выбран — адрес выдачи свой (добавлен на сайте)" : "") : subText(on);
        sub.setText(st);
        sub.setVisibility(st.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static void repaint(Activity a) {
        try { refresh(a); } catch (Throwable ignore) {}
        try {
            Object no = IvaClientPatch.get(a, "atName");
            if (no instanceof View && ((View) no).getParent() instanceof LinearLayout) {
                View r = ((LinearLayout) ((View) no).getParent()).findViewWithTag(TAG + ":0");
                if (r instanceof LinearLayout) paint(a, (LinearLayout) r, 0);
            }
        } catch (Throwable ignore) {}
    }

    /** Правка своего инструмента: владелец — я; 10.105: или я сотрудник «Каталог» (база пустит только в свои пункты). */
    static boolean ownEdit(Activity a) {
        if (editOwner.equals(IvaPickupPatch.me(a))) return true;
        try { return IvaStaffToolsPatch.staff(a); } catch (Throwable e) { return false; }
    }

    /** Свои пункты и есть ли столбец point_id — фоном, не чаще раза в 2 минуты (и при смене входа). */
    static void ensureMine(final Activity a) {
        final String me = IvaPickupPatch.me(a);
        if (me.isEmpty() || mineLoading) return;
        if (me.equals(mineFor) && System.currentTimeMillis() - mineAt < MINE_FRESH_MS) return;
        mineLoading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                List<IvaPickupPatch.Point> ps = new ArrayList<IvaPickupPatch.Point>();
                Boolean c = col;
                boolean ok = false;
                List<IvaPickupPatch.Point> sp = null;
                try { sp = IvaStaffToolsPatch.points(a); } catch (Throwable ignore) {}   // 10.105: сотруднику — его пункты
                if (sp != null) { ps = new ArrayList<IvaPickupPatch.Point>(sp); ok = true; }
                else try { ps = IvaPickupPatch.load(a, new String[1]); ok = true; } catch (Throwable ignore) {}
                if (ok && !ps.isEmpty()) {
                    try {
                        String[] r = IvaClientPatch.apiCall(a, "/rest/v1/tools?select=point_id&limit=1", "GET", null, true);
                        if (r != null && r[0] != null && r[0].startsWith("2")) c = true;
                        else if (r != null && noColumn(r[0], r.length > 1 ? r[1] : "")) c = false;
                    } catch (Throwable ignore) {}
                }
                final List<IvaPickupPatch.Point> got = ps;
                final Boolean hasCol = c;
                final boolean done = ok;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mineLoading = false;
                        if (!me.equals(IvaPickupPatch.me(a))) return;   // вход сменился
                        if (done) { mine = got; mineFor = me; mineAt = System.currentTimeMillis(); col = hasCol; }
                        else if (!me.equals(mineFor)) { mine = null; mineFor = ""; }
                        repaint(a);
                    }
                });
            }
        }).start();
    }

    /** Открыли правку: другой инструмент — его пункт из базы (до ответа поля нет и выбор не пишется). */
    static void openedEdit(final Activity a) {
        Object o = IvaClientPatch.get(a, "editToolId");
        final String id = o instanceof String ? IvaKindPatch.clean((String) o) : "";
        if (id.isEmpty() || id.equals(editId)) return;
        editId = id; editLoaded = false; sel[1] = ""; orig = ""; editOwner = "";
        new Thread(new Runnable() {
            @Override public void run() {
                String p = "", ow = "";
                boolean ok = false;
                Boolean c = col;
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/tools?select=point_id,owner_email&id=eq." + IvaKindPatch.enc(id), "GET", null, true);
                    if (r != null && r[0] != null && r[0].startsWith("2")) {
                        JSONArray arr = new JSONArray(r.length > 1 && r[1] != null ? r[1].trim() : "[]");
                        JSONObject j = arr.length() > 0 ? arr.optJSONObject(0) : null;
                        p = j == null || j.isNull("point_id") ? "" : IvaKindPatch.clean(j.optString("point_id", ""));
                        ow = j == null || j.isNull("owner_email") ? "" : IvaKindPatch.clean(j.optString("owner_email", "")).toLowerCase(java.util.Locale.ROOT);
                        ok = true; c = true;
                    } else if (r != null && noColumn(r[0], r.length > 1 ? r[1] : "")) c = false;
                } catch (Throwable ignore) {}
                final String pid = p, owner = ow;
                final boolean done = ok;
                final Boolean hasCol = c;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        col = hasCol;
                        if (!id.equals(editId)) return;
                        if (done) { orig = pid; sel[1] = pid; editOwner = owner; editLoaded = true; }
                        else editId = "";
                        repaint(a);
                    }
                });
            }
        }).start();
    }

    /** Нажали «Опубликовать» (k=0) / «Сохранить» (k=1): запомнить, что записать после штатного успеха. */
    static void onSaveTouch(Activity a, int k) {
        pendingAt = 0;
        try { pendingStaff = IvaStaffToolsPatch.staffId(a); } catch (Throwable e) { pendingStaff = ""; }
        List<IvaPickupPatch.Point> ps = mine;
        if (ps == null || !Boolean.TRUE.equals(col) || !show(k, ps.size(), true, orig.isEmpty())) return;
        if (k == 0) {
            String cur = current(sel[0], ps);
            if (!needWrite(0, cur, ps.get(0).id, "")) return;
            Object no = IvaClientPatch.get(a, "atName");
            String name = no instanceof TextView ? String.valueOf(((TextView) no).getText()).trim() : "";
            String email = IvaPickupPatch.me(a);
            if (name.isEmpty() || email.isEmpty()) return;
            pendingPoint = cur; pendingId = ""; pendingName = name; pendingEmail = email;
        } else {
            if (!editLoaded || editId.isEmpty() || !ownEdit(a) || !needWrite(1, sel[1], "", orig)) return;
            pendingPoint = sel[1]; pendingId = editId;
        }
        pendingForm = k;
        pendingAt = System.currentTimeMillis();
    }

    static void consumePending(final Activity a, String tab) {
        if (pendingAt == 0) return;
        boolean write = IvaKindPatch.pendingWrite(tab, pendingAt, System.currentTimeMillis());
        pendingAt = 0;
        if (!write) return;
        final int k = pendingForm;
        final String point = pendingPoint, id = pendingId, name = pendingName, email = pendingEmail, staffId = pendingStaff;
        final String body = body(point);
        if (body == null) return;
        if (k == 0) sel[0] = "";                                         // опубликовано — форма «Добавить» снова с первым пунктом
        new Thread(new Runnable() {
            @Override public void run() {
                String msg = "";
                try {
                    String target = id;
                    if (k == 0) {
                        String[] r = IvaClientPatch.apiCall(a, IvaKindPatch.lookupPath(email, name,
                            IvaKindPatch.isoUtc(System.currentTimeMillis() - IvaKindPatch.FRESH_MS), staffId), "GET", null, true);
                        if (r == null || r[0] == null || !r[0].startsWith("2")) return;
                        JSONArray arr = new JSONArray(r.length > 1 && r[1] != null ? r[1].trim() : "[]");
                        JSONObject o = arr.length() > 0 ? arr.optJSONObject(0) : null;
                        if (o == null) return;
                        target = IvaKindPatch.clean(o.optString("id", ""));
                    }
                    if (target.isEmpty()) return;
                    String[] p = IvaClientPatch.apiCall(a, IvaKindPatch.patchPath(target), "PATCH", body, true);
                    msg = writeError(p == null ? "" : p[0], p != null && p.length > 1 ? p[1] : "");
                    if (msg.isEmpty()) {
                        final String tid = target;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (k == 1 && tid.equals(editId)) orig = point;
                                if (staffId.isEmpty()) try { patchCatalog(a, tid, point); } catch (Throwable ignore) {}   // у сотрудника в пунктах нет координат
                            }
                        });
                    }
                } catch (Throwable e) { msg = writeError("", ""); }
                if (!msg.isEmpty()) {
                    final String m = msg;
                    a.runOnUiThread(new Runnable() { @Override public void run() { IvaPickupPatch.toast(a, m); } });
                }
            }
        }).start();
    }

    /** Каталог в памяти: у инструмента — новый пункт и его адрес (карточка и расстояние — сразу верные). */
    static void patchCatalog(Activity a, String id, String point) throws Exception {
        IvaPickupPatch.Point p = null;
        List<IvaPickupPatch.Point> ps = mine;
        if (ps != null) for (IvaPickupPatch.Point x : ps) if (point.equals(x.id)) p = x;
        List<Object> lists = new ArrayList<Object>();
        lists.add(IvaClientPatch.get(a, "tools")); lists.add(IvaSearchPatch.fullTools);
        for (Object l : lists) {
            if (!(l instanceof ArrayList)) continue;
            for (Object o : (ArrayList) l) {
                if (!(o instanceof JSONObject) || !id.equals(((JSONObject) o).optString("id", ""))) continue;
                JSONObject j = (JSONObject) o;
                j.put("point_id", point);
                if (p != null) {
                    j.put("pickup_address", p.address); j.put("pickup_city", p.city);
                    j.put("pickup_lat", p.lat == null || p.lng == null ? JSONObject.NULL : p.lat);
                    j.put("pickup_lng", p.lat == null || p.lng == null ? JSONObject.NULL : p.lng);
                }
            }
        }
    }

    // ════════════════════════════════ карточка инструмента ════════════════════════════════

    static String pointOf(Object it) {
        if (!(it instanceof JSONObject)) return "";
        JSONObject j = (JSONObject) it;
        return j.isNull("point_id") ? "" : IvaKindPatch.clean(j.optString("point_id", ""));
    }

    static void onDetail(final Activity a) {
        final Object it = IvaClientPatch.get(a, "detItem");
        if (Boolean.TRUE.equals(IvaClientPatch.get(a, "detIsSrv"))) return;
        final String pid = pointOf(it).toLowerCase(java.util.Locale.ROOT);
        if (!uuid(pid)) return;                                          // пункта нет (или нет SQL) — штатный адрес
        IvaPickupPatch.Point p;
        Long at;
        synchronized (cards) { p = cards.get(pid); at = cardsAt.get(pid); }
        if (p != null) paintDetail(a, p);
        if ((at != null && System.currentTimeMillis() - at < CARD_FRESH_MS) || cardsLoading) return;
        cardsLoading = true;
        final List<String> ids = new ArrayList<String>();
        ids.add(pid);
        Object t = IvaClientPatch.get(a, "tools");                       // заодно пункты всего каталога — следующие карточки сразу
        if (t instanceof ArrayList) for (Object o : (ArrayList) t) {
            String x = pointOf(o).toLowerCase(java.util.Locale.ROOT);
            if (uuid(x) && !ids.contains(x) && ids.size() < MAX_IDS) ids.add(x);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, RPC, "POST", rpcBody(ids), true);
                    if (r != null && r[0] != null && r[0].startsWith("2")) {
                        JSONArray arr = new JSONArray(r.length > 1 && r[1] != null ? r[1].trim() : "[]");
                        long now = System.currentTimeMillis();
                        synchronized (cards) {
                            for (String x : ids) { cards.remove(x); cardsAt.put(x, now); }   // пункта нет в ответе — штатный адрес
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o == null) continue;
                                IvaPickupPatch.Point q = parseCard(o);
                                if (uuid(q.id)) cards.put(q.id.toLowerCase(java.util.Locale.ROOT), q);
                            }
                        }
                    }
                } catch (Throwable ignore) {}
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        cardsLoading = false;
                        if (IvaClientPatch.get(a, "detItem") != it) return;      // уже открыт другой инструмент
                        IvaPickupPatch.Point q;
                        synchronized (cards) { q = cards.get(pid); }
                        if (q != null) paintDetail(a, q);
                    }
                });
            }
        }).start();
    }

    static void paintDetail(Activity a, IvaPickupPatch.Point p) {
        Object v = IvaClientPatch.get(a, "detAddr");
        String s = cardText(p);
        if (!(v instanceof TextView) || s.isEmpty()) return;
        TextView t = (TextView) v;
        if (!s.contentEquals(t.getText())) t.setText(s);
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
    }
}

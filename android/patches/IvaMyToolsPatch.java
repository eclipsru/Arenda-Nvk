package ru.prokatnvsk.app;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 10.122 (просьба владельца 18.10.2026): «Мой инструмент» — список пунктов выдачи; нажали на пункт — раскрываются его
 * инструменты; у инструмента — «Просмотреть», «Редактировать», «Перенести в другой пункт», «Удалить».
 *  • владелец: вместо штатного списка (atListBox) — свой блок рядом с ним (штатные виды не переносятся, только
 *    прячется atListBox, пока открыт «Мой инструмент»; не загрузилось — штатный список возвращается);
 *  • сотрудник «Каталог» (вид «Как у админов»): тот же вид в разделе логотипа (IvaStaffToolsPatch.renderHub);
 *    «Перенести» — только владелец; «Удалить» — по праву «Удаляет инструмент», всегда после одобрения владельца.
 */
public class IvaMyToolsPatch {
    static final String TAG = "iva_my_tools_10_122";
    static final String NO_POINT = "Пункт не выбран";
    static final String[] ACTIONS_OWNER = {"Просмотреть", "Редактировать", "Перенести в другой пункт", "Удалить"};
    static final String[] ACTIONS_STAFF = {"Просмотреть", "Редактировать", "Удалить"};
    static final String MOVE_RPC = "/rest/v1/rpc/iva_tool_move";
    static final String CARD_RPC = "/rest/v1/rpc/iva_tool_edit_card";
    static final String COLS = "id,name,price,active,img,point_id,review";
    static final Set<String> open = new HashSet<String>();        // раскрытые пункты (на время работы приложения)
    static volatile int gen = 0;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static final class Item {
        String id = "", name = "", price = "", point = "", status = "", hint = "";
        boolean warn;
        JSONObject raw;
    }

    static final class Group {
        String id = "", name = "", address = "";
        List<Item> items = new ArrayList<Item>();
    }

    static String str(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, "").trim(); }

    /** Пункты по порядку, в каждом — его инструменты по названию; инструменты без пункта (или чужого) — «Пункт не выбран». */
    static List<Group> groups(List<IvaPickupPatch.Point> points, List<Item> items) {
        List<Group> out = new ArrayList<Group>();
        for (IvaPickupPatch.Point p : points == null ? new ArrayList<IvaPickupPatch.Point>() : points) {
            Group g = new Group();
            g.id = p.id.toLowerCase(Locale.ROOT);
            g.name = p.name;
            g.address = p.address;
            out.add(g);
        }
        Group none = new Group();
        none.name = NO_POINT;
        for (Item t : items == null ? new ArrayList<Item>() : items) {
            Group to = none;
            for (Group g : out) if (!g.id.isEmpty() && g.id.equalsIgnoreCase(t.point)) { to = g; break; }
            to.items.add(t);
        }
        if (!none.items.isEmpty()) out.add(none);
        for (Group g : out) {
            java.util.Collections.sort(g.items, new java.util.Comparator<Item>() {
                @Override public int compare(Item x, Item y) { return x.name.toLowerCase(Locale.ROOT).compareTo(y.name.toLowerCase(Locale.ROOT)); }
            });
        }
        return out;
    }

    static String countText(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? "инструмент" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "инструмента" : "инструментов");
        return n == 0 ? "Нет инструментов" : n + " " + w;
    }

    static String priceText(String price) {
        String p = price == null ? "" : price.trim();
        if (p.endsWith(".0")) p = p.substring(0, p.length() - 2);
        return p.isEmpty() || "null".equals(p) ? "" : p + " ₽/сутки";
    }

    /** Список владельца (GET tools) → инструменты. */
    static List<Item> parseOwner(String body) throws Exception {
        List<Item> out = new ArrayList<Item>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || str(o, "id").isEmpty()) continue;
            Item x = new Item();
            x.id = str(o, "id");
            x.name = str(o, "name");
            x.price = str(o, "price");
            x.point = str(o, "point_id");
            String rv = str(o, "review");
            x.status = IvaStaffToolsPatch.statusText(rv, o.optBoolean("active", false));
            x.warn = !rv.isEmpty();
            x.raw = o;
            out.add(x);
        }
        return out;
    }

    /** Инструменты сотрудника (iva_staff_tools, IvaStaffToolsPatch.Tool) → инструменты. */
    static List<Item> fromStaff(List<IvaStaffToolsPatch.Tool> tools) {
        List<Item> out = new ArrayList<Item>();
        for (IvaStaffToolsPatch.Tool t : tools == null ? new ArrayList<IvaStaffToolsPatch.Tool>() : tools) {
            Item x = new Item();
            x.id = t.id;
            x.name = t.name;
            x.price = t.price;
            x.point = str(t.raw, "point_id");
            x.status = "delete".equals(t.edit) ? "Удаление на одобрении" : IvaStaffToolsPatch.statusText(t.review, t.active, t.edit);
            x.hint = "delete".equals(t.edit) ? "Инструмент удалится, когда владелец одобрит" : IvaStaffToolsPatch.statusHint(t.review, t.edit);
            x.warn = !t.review.isEmpty() || !t.edit.isEmpty();
            x.raw = t.raw;
            out.add(x);
        }
        return out;
    }

    static String moveBody(String tool, String point) {
        if (!IvaToolPointPatch.uuid(tool) || !IvaToolPointPatch.uuid(point)) return null;
        return "{\"p_id\":\"" + tool.trim().toLowerCase(Locale.ROOT) + "\",\"p_point\":\"" + point.trim().toLowerCase(Locale.ROOT) + "\"}";
    }

    /** Текст ошибки базы (raise exception) или запасной. */
    static String errText(String code, String body, String fallback) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        if (b.contains("PGRST202") || "404".equals(c)) return "Нужен SQL 10.122 — владелец применяет его в Supabase";
        try {
            JSONObject o = new JSONObject(b);
            if ("P0001".equals(o.optString("code", ""))) {
                String m = o.optString("message", "").trim();
                if (!m.isEmpty() && m.length() <= 200) return m;
            }
        } catch (Throwable ignore) {}
        if ("401".equals(c) || "403".equals(c) || b.contains("42501")) return "Нет права — войдите заново";
        return c.isEmpty() ? "Не получилось — нет связи с сервером" : fallback;
    }

    // ───────────── владелец: «Мой инструмент» вместо штатного списка ─────────────

    /** Из IvaPatch.onHub: он стоит в начале showAdmHub — смотрим, что открылось, когда showAdmHub закончит. */
    static void later(final Activity a) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { try { onHub(a); } catch (Throwable ignore) {} }
        });
    }

    /** Из IvaPatch.onTab: снова кабинет (после правки, удаления) — список заново. */
    static void onTab(Activity a, String tab) { if ("admhub".equals(tab)) later(a); }

    /** Кабинет переключил «Мой инструмент» / «Добавить инструмент». */
    static void onHub(final Activity a) {
        if (!IvaChatPatch.isAdmin(a)) return;
        Object lb = IvaClientPatch.get(a, "atListBox"), fb = IvaClientPatch.get(a, "atFormBox");
        if (!(lb instanceof LinearLayout) || !(((View) lb).getParent() instanceof LinearLayout)) return;
        final LinearLayout list = (LinearLayout) lb;
        LinearLayout parent = (LinearLayout) list.getParent();
        View mine = parent.findViewWithTag(TAG);
        boolean my = !(fb instanceof View) || ((View) fb).getVisibility() != View.VISIBLE;
        if (!my) { if (mine != null) mine.setVisibility(View.GONE); return; }
        if (mine == null) {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setTag(TAG);
            parent.addView(box, parent.indexOfChild(list) + 1, new LinearLayout.LayoutParams(-1, -2));
            mine = box;
        }
        mine.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        loadOwner(a, (LinearLayout) mine, list);
    }

    static void loadOwner(final Activity a, final LinearLayout box, final LinearLayout nativeList) {
        final int g = ++gen;
        final int cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        if (box.getChildCount() == 0) {
            TextView s = IvaChiefPatch.text(a, "Загружаем…", 13f, cGray, false);
            s.setPadding(dp(a, 4), dp(a, 8), 0, 0);
            box.addView(s);
        }
        final String me = IvaChatPatch.str(IvaClientPatch.get(a, "userEmail")).trim();
        new Thread(new Runnable() {
            @Override public void run() {
                List<IvaPickupPatch.Point> ps = null;
                List<Item> items = null;
                try { ps = IvaPickupPatch.load(a, new String[1]); } catch (Throwable ignore) {}
                try {
                    String q = "/rest/v1/tools?select=" + COLS + "&owner_email=eq." + java.net.URLEncoder.encode(me, "UTF-8") + "&order=name.asc&limit=500";
                    String[] r = IvaClientPatch.apiCall(a, q, "GET", null, true);
                    if (r != null && r[0] != null && r[0].startsWith("2")) items = parseOwner(r.length > 1 ? r[1] : "");
                } catch (Throwable ignore) {}
                final List<IvaPickupPatch.Point> fp = ps;
                final List<Item> fi = items;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (g != gen || box.getVisibility() != View.VISIBLE) return;
                        if (fp == null || fi == null) {             // не загрузилось — штатный список, как раньше
                            box.setVisibility(View.GONE);
                            nativeList.setVisibility(View.VISIBLE);
                            return;
                        }
                        draw(a, box, groups(fp, fi), true, fp, new Runnable() {
                            @Override public void run() { loadOwner(a, box, nativeList); }
                        });
                    }
                });
            }
        }).start();
    }

    // ───────────── общий вид ─────────────

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static void draw(final Activity a, final LinearLayout box, final List<Group> gs, final boolean owner,
                     final List<IvaPickupPatch.Point> points, final Runnable reload) {
        final int cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8), cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0),
            cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        box.removeAllViews();
        if (gs.isEmpty()) {
            TextView t = IvaChiefPatch.text(a, owner ? "Пунктов выдачи пока нет — добавьте пункт в «Настройке проката»" : "В ваших пунктах пока нет инструментов", 14f, cGray, false);
            t.setPadding(dp(a, 4), dp(a, 10), 0, 0);
            box.addView(t);
            return;
        }
        for (final Group g : gs) {
            final LinearLayout c = IvaChiefPatch.card(a);
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(IvaChiefPatch.text(a, g.name.isEmpty() ? "Пункт выдачи" : g.name, 16f, cText, true));
            String sub = countText(g.items.size()) + (g.address.isEmpty() ? "" : " · " + g.address);
            TextView st = IvaChiefPatch.text(a, sub, 13f, cGray, false);
            st.setPadding(0, dp(a, 2), 0, 0);
            col.addView(st);
            head.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            final String key = g.id.isEmpty() ? "-" : g.id;
            final TextView arrow = IvaChiefPatch.text(a, open.contains(key) ? "▾" : "▸", 18f, cOrange, true);
            arrow.setPadding(dp(a, 10), 0, dp(a, 4), 0);
            head.addView(arrow);
            c.addView(head);
            final LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            c.addView(body, new LinearLayout.LayoutParams(-1, -2));
            final Runnable fill = new Runnable() {
                @Override public void run() {
                    body.removeAllViews();
                    boolean on = open.contains(key);
                    arrow.setText(on ? "▾" : "▸");
                    if (!on) return;
                    if (g.items.isEmpty()) {
                        TextView t = IvaChiefPatch.text(a, "В этом пункте пока нет инструментов", 13f, cGray, false);
                        t.setPadding(0, dp(a, 10), 0, 0);
                        body.addView(t);
                    }
                    for (final Item t : g.items) body.addView(row(a, t, owner, points, reload, cText, cGray, cOrange));
                }
            };
            head.setClickable(true);
            head.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (open.contains(key)) open.remove(key); else open.add(key);
                    fill.run();
                }
            });
            fill.run();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            box.addView(c, lp);
        }
    }

    /** 10.123: цвет плашки состояния: 0 — опубликован (зелёный), 1 — скрыт (серый), 2 — отклонён (красный), 3 — ждёт (жёлтый). */
    static int statusKind(String status) {
        String st = status == null ? "" : status.trim();
        if ("Опубликован".equals(st)) return 0;
        if (st.isEmpty() || "Скрыт".equals(st)) return 1;
        if (st.contains("тклон")) return 2;
        return 3;
    }

    static final int[] KIND_COLOR = {0xff4caf50, 0xff9aa1b0, 0xffff8a80, 0xffffb74d};
    static final int[] KIND_BG = {0x264caf50, 0x269aa1b0, 0x26ff8a80, 0x26ffb74d};

    /** 10.123 (просьба владельца): инструмент — карточкой: фото, название, цена, состояние; нажатие — действия. */
    static View row(final Activity a, final Item t, final boolean owner, final List<IvaPickupPatch.Point> points,
                    final Runnable reload, int cText, int cGray, int cOrange) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(IvaStaffPatch.color(a, "bg", 0xff101217));
        bg.setStroke(Math.max(1, dp(a, 1)), IvaStaffPatch.color(a, "line", 0xff262b35));
        bg.setCornerRadius(dp(a, 14));
        r.setBackground(bg);
        View pic = null;
        try { pic = IvaChatPatch.toolPic(a, t.raw, t.id, 64); } catch (Throwable ignore) {}
        if (pic != null) {
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(a, 64), dp(a, 64));
            pl.rightMargin = dp(a, 12);
            r.addView(pic, pl);
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = IvaChiefPatch.text(a, t.name.isEmpty() ? "Без названия" : t.name, 15f, cText, true);
        nm.setMaxLines(2);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(nm);
        String pr = priceText(t.price);
        if (!pr.isEmpty()) {
            TextView x = IvaChiefPatch.text(a, pr, 14f, cOrange, true);
            x.setPadding(0, dp(a, 3), 0, 0);
            col.addView(x);
        }
        if (!t.status.isEmpty()) {
            int k = statusKind(t.status);
            TextView b = IvaChiefPatch.text(a, t.status, 12f, KIND_COLOR[k], true);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(KIND_BG[k]);
            g.setCornerRadius(dp(a, 10));
            b.setBackground(g);
            b.setPadding(dp(a, 10), dp(a, 3), dp(a, 10), dp(a, 3));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.topMargin = dp(a, 6);
            col.addView(b, bl);
        }
        if (!t.hint.isEmpty()) {
            TextView x = IvaChiefPatch.text(a, t.hint, 12f, cGray, false);
            x.setPadding(0, dp(a, 4), 0, 0);
            col.addView(x);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView more = IvaChiefPatch.text(a, "›", 22f, cGray, false);
        more.setPadding(dp(a, 8), 0, 0, 0);
        r.addView(more);
        r.setClickable(true);
        r.setContentDescription((t.name.isEmpty() ? "Инструмент" : t.name) + (t.status.isEmpty() ? "" : ", " + t.status) + " — действия");
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { actions(a, t, owner, points, reload); }
        });
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(a, 10);
        wrap.addView(r, wl);
        return wrap;
    }

    static void actions(final Activity a, final Item t, final boolean owner, final List<IvaPickupPatch.Point> points, final Runnable reload) {
        final String[] items = owner ? ACTIONS_OWNER : ACTIONS_STAFF;
        IvaClientPatch.styledChoice(a, t.name.isEmpty() ? "Инструмент" : t.name, priceText(t.price), items, new IvaClientPatch.Choice() {
            @Override public void pick(int i) {
                if (i < 0 || i >= items.length) return;
                String act = items[i];
                if ("Просмотреть".equals(act)) view(a, t);
                else if ("Редактировать".equals(act)) edit(a, t, owner);
                else if ("Перенести в другой пункт".equals(act)) move(a, t, points, reload);
                else if ("Удалить".equals(act)) {
                    if (owner) delete(a, t, reload); else IvaToolEditPatch.staffDelete(a, t.id, t.name, reload);
                }
            }
        });
    }

    /** Карточка инструмента как у клиента: из каталога; нет там (скрыт, на одобрении) — по данным инструмента. */
    static void view(final Activity a, final Item t) {
        JSONObject found = null;
        try {
            for (Object o : IvaSearchPatch.allTools(a))
                if (o instanceof JSONObject && t.id.equals(((JSONObject) o).optString("id", ""))) { found = (JSONObject) o; break; }
        } catch (Throwable ignore) {}
        if (found != null) { openDetail(a, found); return; }
        fetchCard(a, t.id, new Card() {
            @Override public void got(JSONObject j, String err) {
                if (j == null) { Toast.makeText(a, err, Toast.LENGTH_LONG).show(); return; }
                try { if (j.isNull("owner_email")) j.put("owner_email", IvaChatPatch.str(IvaClientPatch.get(a, "userEmail")).trim()); } catch (Throwable ignore) {}
                openDetail(a, j);
            }
        });
    }

    static void openDetail(Activity a, JSONObject j) {
        try {
            IvaChatPatch.call(a, "openDetail", new Class<?>[]{JSONObject.class, boolean.class}, j, false);
        } catch (Throwable e) {
            Toast.makeText(a, "Не открылось — найдите инструмент в каталоге", Toast.LENGTH_LONG).show();
        }
    }

    /** «Редактировать»: штатная правка сразу со всеми полями (описание, условия, все фото). */
    static void edit(final Activity a, final Item t, final boolean owner) {
        fetchCard(a, t.id, new Card() {
            @Override public void got(JSONObject j, String err) {
                JSONObject use = j != null ? j : t.raw;
                if (use == null) { Toast.makeText(a, err, Toast.LENGTH_LONG).show(); return; }
                IvaToolEditPatch.markFull(str(use, "id"));
                try {
                    IvaChatPatch.call(a, "openToolEdit", new Class<?>[]{JSONObject.class}, use);
                } catch (Throwable e) { Toast.makeText(a, "Не получилось открыть инструмент", Toast.LENGTH_LONG).show(); }
            }
        });
    }

    interface Card { void got(JSONObject j, String err); }

    /** Инструмент целиком (iva_tool_edit_card; без SQL 10.122 у владельца — те же поля напрямую). */
    static void fetchCard(final Activity a, final String id, final Card cb) {
        if (!IvaToolPointPatch.uuid(id)) { cb.got(null, "Инструмент не найден"); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject j = null;
                String err = "Не получилось — нет связи с сервером";
                try {
                    String[] r = IvaClientPatch.apiCall(a, CARD_RPC, "POST", "{\"p_id\":\"" + id.trim().toLowerCase(Locale.ROOT) + "\"}", true);
                    String b = r != null && r.length > 1 && r[1] != null ? r[1].trim() : "";
                    if (r != null && r[0] != null && r[0].startsWith("2") && b.startsWith("{")) j = new JSONObject(b);
                    else if (r != null && (b.contains("PGRST202") || "404".equals(r[0])) && IvaChatPatch.isAdmin(a)) j = IvaToolEditPatch.direct(a, id);
                    else if (r != null) err = errText(r[0], b, "Не получилось открыть инструмент");
                } catch (Throwable ignore) {}
                final JSONObject fj = j;
                final String fe = err;
                a.runOnUiThread(new Runnable() { @Override public void run() { cb.got(fj, fe); } });
            }
        }).start();
    }

    static void move(final Activity a, final Item t, final List<IvaPickupPatch.Point> points, final Runnable reload) {
        final List<IvaPickupPatch.Point> other = new ArrayList<IvaPickupPatch.Point>();
        for (IvaPickupPatch.Point p : points == null ? new ArrayList<IvaPickupPatch.Point>() : points)
            if (!p.id.equalsIgnoreCase(t.point)) other.add(p);
        if (other.isEmpty()) { Toast.makeText(a, "Другого пункта нет — добавьте его в «Пунктах выдачи»", Toast.LENGTH_LONG).show(); return; }
        String[] names = new String[other.size()];
        for (int i = 0; i < names.length; i++) names[i] = IvaToolPointPatch.chipText(other.get(i));
        IvaClientPatch.styledChoice(a, "Перенести в другой пункт", "«" + t.name + "» будут выдавать в выбранном пункте", names, new IvaClientPatch.Choice() {
            @Override public void pick(int i) {
                if (i < 0 || i >= other.size()) return;
                final String body = moveBody(t.id, other.get(i).id);
                if (body == null) return;
                new Thread(new Runnable() {
                    @Override public void run() {
                        String e;
                        try {
                            String[] r = IvaClientPatch.apiCall(a, MOVE_RPC, "POST", body, true);
                            e = r == null ? "Не получилось" : errText(r[0], r.length > 1 ? r[1] : "", "Не получилось перенести");
                        } catch (Throwable x) { e = "Не получилось — нет связи с сервером"; }
                        final String fe = e;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(a, fe.isEmpty() ? "Перенесено" : fe, Toast.LENGTH_LONG).show();
                                if (fe.isEmpty()) { reloadCatalog(a); if (reload != null) reload.run(); }
                            }
                        });
                    }
                }).start();
            }
        });
    }

    static void delete(final Activity a, final Item t, final Runnable reload) {
        IvaClientPatch.styledChoice(a, "Удалить инструмент?", "«" + t.name + "» исчезнет из каталога. Это нельзя отменить.", new String[]{"Удалить"},
            new IvaClientPatch.Choice() {
                @Override public void pick(int i) {
                    if (i != 0 || !IvaToolPointPatch.uuid(t.id)) return;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            String e;
                            try {
                                String[] r = IvaClientPatch.apiCall(a, "/rest/v1/tools?id=eq." + t.id.trim().toLowerCase(Locale.ROOT), "DELETE", null, true);
                                e = r == null ? "Не получилось" : errText(r[0], r.length > 1 ? r[1] : "", "Не получилось удалить");
                            } catch (Throwable x) { e = "Не получилось — нет связи с сервером"; }
                            final String fe = e;
                            a.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    Toast.makeText(a, fe.isEmpty() ? "Инструмент удалён" : fe, Toast.LENGTH_LONG).show();
                                    if (fe.isEmpty()) { reloadCatalog(a); if (reload != null) reload.run(); }
                                }
                            });
                        }
                    }).start();
                }
            });
    }

    static void reloadCatalog(Activity a) {
        try { IvaChatPatch.call(a, "loadCatalog", new Class<?>[0]); } catch (Throwable ignore) {}
    }

    // ───────────── сотрудник: тот же вид (раздел логотипа) ─────────────

    /** Из IvaStaffToolsPatch.draw (раздел логотипа): пункты сотрудника → инструменты. */
    static void drawStaff(final Activity a, LinearLayout box, List<IvaStaffToolsPatch.Tool> tools, Runnable reload) {
        List<IvaPickupPatch.Point> pts = null;
        try { pts = IvaStaffToolsPatch.points(a); } catch (Throwable ignore) {}
        if (pts == null) pts = new ArrayList<IvaPickupPatch.Point>();
        List<Item> items = fromStaff(tools);
        List<Group> gs = groups(pts, items);
        if (gs.size() == 1 && !open.contains(gs.get(0).id.isEmpty() ? "-" : gs.get(0).id)) open.add(gs.get(0).id.isEmpty() ? "-" : gs.get(0).id);
        draw(a, box, gs, false, pts, reload);
    }
}

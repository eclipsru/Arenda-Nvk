package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 10.120 — страница проката и чат пункта выдачи (просьба владельца 18.10.2026).
 *
 * Карточка инструмента: в строке «Продавец» — «Прокат», название и аватарка проката (если у инструмента есть пункт
 * и применён SQL 20261111). Нажатие — страница проката: аватарка сверху, пункт выдачи этого инструмента (название,
 * адрес, график, телефон), «Спросить в чате» (переписка именно с этим пунктом — app_messages.point_id), «Позвонить»
 * (только телефон пункта; нет телефона — нет кнопки), «Пункты выдачи» (все пункты проката карточками, «Перейти» —
 * эта же страница для того пункта) и инструменты этого пункта (из уже загруженного штатного каталога; нажатие —
 * штатная карточка). Переписку пункта видят владелец (выключатель «Чаты пунктов у меня» в «Настройке проката»,
 * по умолчанию включён) и сотрудники с правом «Отвечает в чате» этого пункта (IvaStaffChatPatch).
 *
 * Без SQL — как 10.119 (штатная строка «Продавец», штатная страница продавца). Новых вставок в smali нет:
 * вход — IvaPatch.onTab («detail»), IvaShopPatch («Настройка проката»), IvaChatPatch (переписка «pt:…»).
 */
final class IvaProkatPatch {
    private IvaProkatPatch() {}

    static final String RPC = "/rest/v1/rpc/iva_prokat_points";
    static final String PT = "pt:";
    static final String LABEL = "Прокат", ASK = "Спросить в чате", CALL = "Позвонить", POINTS = "Пункты выдачи", GO = "Перейти";
    static final String K_OWNER = "pt_chat_owner";        // выключатель владельца (настройки уведомлений телефона)
    static final String TOGGLE_TITLE = "Чаты пунктов у меня";
    static final String TOGGLE_ON = "Вопросы клиентов пунктам видите вы и сотрудники пункта с правом «Отвечает в чате»";
    static final String TOGGLE_OFF = "Выключено: вопросы пунктам видят только сотрудники пункта с правом «Отвечает в чате»";
    static final long FRESH_MS = 5 * 60_000L, NO_DB_MS = 10 * 60_000L;

    // ════════════════════════════════ чистая логика (IvaPatchTest) ════════════════════════════════

    static final class PP {
        String id = "", name = "", address = "", city = "", tel = "", company = "", avatar = "";
        int[] open, close;
        int tools;
        boolean main;
    }

    /** {"p_point":"…"}; не uuid — null (не шлём). */
    static String body(String id) {
        String t = id == null ? "" : id.trim();
        return IvaToolPointPatch.uuid(t) ? "{\"p_point\":\"" + t.toLowerCase(Locale.ROOT) + "\"}" : null;
    }

    static String s(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, "").trim(); }

    static List<PP> parse(String body) throws Exception {
        List<PP> out = new ArrayList<PP>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            PP p = new PP();
            p.id = s(o, "id").toLowerCase(Locale.ROOT);
            p.name = s(o, "name");
            p.address = s(o, "address");
            p.city = s(o, "city");
            p.tel = s(o, "tel");
            p.company = s(o, "company");
            p.avatar = s(o, "avatar");
            p.tools = o.optInt("tools", 0);
            p.main = o.optBoolean("main", false);
            int[] op = IvaPickupPatch.arr(String.valueOf(o.opt("open_min"))), cl = IvaPickupPatch.arr(String.valueOf(o.opt("close_min")));
            if (op != null && cl != null) { p.open = op; p.close = cl; }
            if (IvaToolPointPatch.uuid(p.id)) out.add(p);
        }
        return out;
    }

    static PP find(List<PP> ps, String id) {
        if (ps == null || id == null) return null;
        for (PP p : ps) if (p.id.equalsIgnoreCase(id.trim())) return p;
        return null;
    }

    /** Адрес для набора: «tel:+73831112233»; меньше 5 цифр — «» (кнопки нет). */
    static String telUri(String tel) {
        String t = tel == null ? "" : tel.trim();
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') d.append(c);
            else if (c == '+' && d.length() == 0) d.append(c);
        }
        int n = d.length() - (d.length() > 0 && d.charAt(0) == '+' ? 1 : 0);
        return n < 5 ? "" : "tel:" + d;
    }

    /** «Инструментов: 3» / «Инструментов пока нет». */
    static String toolsText(int n) { return n <= 0 ? "Инструментов пока нет" : "Инструментов: " + n; }

    static String pointsText(int n) { return n > 1 ? POINTS + " · " + n : POINTS; }

    /** Ключ переписки пункта внутри «Чата»: «pt:uuid». */
    static String key(String id) { String t = id == null ? "" : id.trim(); return IvaToolPointPatch.uuid(t) ? PT + t.toLowerCase(Locale.ROOT) : ""; }

    static boolean isKey(String k) { return k != null && k.startsWith(PT) && IvaToolPointPatch.uuid(k.substring(PT.length())); }

    static String idOf(String k) { return isKey(k) ? k.substring(PT.length()) : ""; }

    /** Подпись переписки пункта: «Пункт выдачи «Склад»». */
    static String chatTitle(String name) {
        String n = name == null ? "" : name.trim();
        return n.isEmpty() ? "Пункт выдачи" : "Пункт выдачи «" + n + "»";
    }

    /** Строки карточки пункта: название — отдельно; ниже адрес, телефон, график. */
    static String infoText(PP p) {
        if (p == null) return "";
        StringBuilder b = new StringBuilder();
        if (!p.address.isEmpty()) b.append(p.address);
        String h = p.open == null ? "" : IvaPickupPatch.hoursText(p.open, p.close);
        if (!h.isEmpty()) b.append(b.length() > 0 ? "\n" : "").append("Время работы: ").append(h);
        if (!p.tel.isEmpty()) b.append(b.length() > 0 ? "\n" : "").append("Телефон: ").append(p.tel);
        return b.toString();
    }

    /** Инструменты пункта из каталога (полный — если есть): только показанные, без повторов, по порядку каталога. */
    static List<JSONObject> toolsOf(List<?> all, String pid) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (all == null || pid == null || pid.isEmpty()) return out;
        Set<String> seen = new HashSet<String>();
        for (Object o : all) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if (!pid.equalsIgnoreCase(IvaToolPointPatch.pointOf(j))) continue;
            if (j.has("active") && !j.optBoolean("active", true)) continue;
            String id = s(j, "id");
            if (id.isEmpty() || !seen.add(id)) continue;
            out.add(j);
        }
        return out;
    }

    // ════════════════════════════════ загрузка (не главный поток) ════════════════════════════════

    static final Map<String, List<PP>> cache = new HashMap<String, List<PP>>();
    static final Map<String, Long> cacheAt = new HashMap<String, Long>();
    static volatile long noDbUntil = 0;

    static List<PP> cached(String pid) {
        synchronized (cache) {
            Long at = cacheAt.get(pid);
            return at != null && System.currentTimeMillis() - at < FRESH_MS ? cache.get(pid) : null;
        }
    }

    /** Пункты проката по его пункту; null — нет связи или SQL; пустой — пункт не показывается. */
    static List<PP> fetch(Context a, String pid) {
        String id = pid == null ? "" : pid.trim().toLowerCase(Locale.ROOT);
        String b = body(id);
        if (b == null) return null;
        List<PP> c = cached(id);
        if (c != null) return c;
        if (System.currentTimeMillis() < noDbUntil) return null;
        try {
            String[] r = a instanceof Activity ? IvaClientPatch.apiCall((Activity) a, RPC, "POST", b, true)
                : IvaNotifyPatch.api(a, RPC, "POST", b);
            if (r == null) return null;
            if ("404".equals(r[0]) || (r.length > 1 && r[1] != null && r[1].contains("PGRST202"))) { noDbUntil = System.currentTimeMillis() + NO_DB_MS; return null; }
            if (!r[0].startsWith("2")) return null;
            List<PP> ps = parse(r.length > 1 ? r[1] : "");
            long now = System.currentTimeMillis();
            synchronized (cache) {
                cache.put(id, ps); cacheAt.put(id, now);
                for (PP p : ps) if (!cache.containsKey(p.id) || cacheAt.get(p.id) == null || now - cacheAt.get(p.id) >= FRESH_MS) { cache.put(p.id, ps); cacheAt.put(p.id, now); }
            }
            return ps;
        } catch (Throwable e) { return null; }
    }

    /** Объект переписки пункта для «Чата» (без "id" — штатный код не примет его за инструмент). */
    static JSONObject chatObj(String pid, PP p) {
        try {
            JSONObject o = new JSONObject();
            o.put("iva_point", pid.toLowerCase(Locale.ROOT));
            o.put("name", chatTitle(p == null ? "" : p.name));
            o.put("img", p == null ? "" : p.avatar);
            if (p != null) o.put("company", p.company);
            return o;
        } catch (Throwable e) { return null; }
    }

    // ════════════════════════════════ выключатель владельца ════════════════════════════════

    static boolean ownerSees(Context c) {
        try { return IvaNotifyPatch.prefs(c).getBoolean(K_OWNER, true); } catch (Throwable e) { return true; }
    }

    /** «Настройка проката» → «Чаты пунктов у меня» (после «На одобрении»). */
    static void settingsRow(final Activity a, LinearLayout list, int cText, final int cGray, int cOrange) {
        float den = a.getResources().getDisplayMetrics().density;
        final LinearLayout row = IvaShopPatch.row(a, list, new IvaShopPatch.Icon(IvaShopPatch.Icon.PEOPLE, cOrange, den), TOGGLE_TITLE,
            ownerSees(a) ? TOGGLE_ON : TOGGLE_OFF, cText, cGray);
        if (row.getChildCount() > 2) row.removeViewAt(2);                      // стрелка → выключатель
        final Switch sw = new Switch(a);
        sw.setChecked(ownerSees(a));
        IvaNotifyPatch.tint(sw, cOrange);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                try {
                    IvaNotifyPatch.prefs(a).edit().putBoolean(K_OWNER, v).apply();
                    TextView t = IvaStaffToolsPatch.sub(row);
                    if (t != null) t.setText(v ? TOGGLE_ON : TOGGLE_OFF);
                    IvaChatPatch.renderedSig = "";
                    IvaChatPatch.adminsAt = 0;                                    // «Чат» перечитает
                } catch (Throwable ignore) {}
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = IvaChiefPatch.dp(a, 8);
        row.addView(sw, sl);
        row.setContentDescription(TOGGLE_TITLE);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sw.toggle(); } });
    }

    // ════════════════════════════════ карточка инструмента ════════════════════════════════

    static void onTab(Activity a, String tab) {
        if ("detail".equals(tab)) try { onDetail(a); } catch (Throwable ignore) {}
    }

    static void onDetail(final Activity a) {
        final Object it = IvaClientPatch.get(a, "detItem");
        if (Boolean.TRUE.equals(IvaClientPatch.get(a, "detIsSrv"))) return;
        Object nm = IvaClientPatch.get(a, "detSellerName");
        if (nm instanceof TextView) {                                          // «Продавец» → «Прокат» (подпись над именем)
            try {
                ViewGroup col = (ViewGroup) ((TextView) nm).getParent();
                View first = col.getChildAt(0);
                if (first instanceof TextView && first != nm) ((TextView) first).setText(LABEL);
            } catch (Throwable ignore) {}
        }
        final String pid = IvaToolPointPatch.pointOf(it).toLowerCase(Locale.ROOT);
        if (!IvaToolPointPatch.uuid(pid)) return;                              // без пункта — штатная строка
        List<PP> ps = cached(pid);
        if (ps != null) { paint(a, it, pid, ps); return; }
        if (System.currentTimeMillis() < noDbUntil) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<PP> got = fetch(a, pid);
                if (got == null) return;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { if (IvaClientPatch.get(a, "detItem") == it) paint(a, it, pid, got); } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void paint(final Activity a, Object it, final String pid, List<PP> ps) {
        final PP p = find(ps, pid);
        if (p == null) return;                                                 // пункт не показывается — штатная строка
        final String owner = it instanceof JSONObject ? s((JSONObject) it, "owner_email") : "";
        Object nm = IvaClientPatch.get(a, "detSellerName");
        if (nm instanceof TextView && !p.company.isEmpty()) ((TextView) nm).setText(p.company);
        Object av = IvaClientPatch.get(a, "detSellerAvF");
        if (av instanceof FrameLayout && !p.avatar.isEmpty()) {
            FrameLayout f = (FrameLayout) av;
            f.removeAllViews();
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            IvaChatPatch.round(iv, 0, true);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            IvaChatPatch.loadPhoto(a, iv, p.avatar, "prk" + Integer.toHexString(p.avatar.hashCode()));
        }
        Object row = IvaClientPatch.get(a, "detSeller");
        if (row instanceof View) {
            ((View) row).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { try { openPage(a, pid, owner); } catch (Throwable ignore) {} }
            });
        }
    }

    // ════════════════════════════════ страница проката ════════════════════════════════

    static final List<WeakReference<Dialog>> pages = new ArrayList<WeakReference<Dialog>>();

    static void track(IvaChiefPatch.Page pg) { synchronized (pages) { pages.add(new WeakReference<Dialog>(pg.d)); } }

    /** Закрыть все наши страницы (переход в штатную карточку или в «Чат»). */
    static void closeAll() {
        synchronized (pages) {
            for (WeakReference<Dialog> w : pages) {
                Dialog d = w.get();
                if (d != null) try { if (d.isShowing()) d.dismiss(); } catch (Throwable ignore) {}
            }
            pages.clear();
        }
    }

    static int c(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }
    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static void openPage(final Activity a, final String pid, final String owner) {
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, LABEL, null, false);
        track(pg);
        final TextView st = IvaChiefPatch.text(a, "Загружаем…", 13f, c(a, "gray", 0xff9aa1b0), false);
        st.setPadding(dp(a, 2), dp(a, 8), 0, 0);
        pg.list.addView(st, new LinearLayout.LayoutParams(-1, -2));
        List<PP> ps = cached(pid);
        if (ps != null) { draw(a, pg, pid, owner, ps); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                final List<PP> got = fetch(a, pid);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!pg.open()) return;
                            if (got == null || find(got, pid) == null) {
                                st.setText(got == null ? "Не загрузилось — нет связи с сервером" : "Пункт выдачи больше не работает");
                                st.setTextColor(c(a, "orange", 0xffff8f1f));
                                return;
                            }
                            draw(a, pg, pid, owner, got);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void draw(final Activity a, final IvaChiefPatch.Page pg, final String pid, final String owner, final List<PP> ps) {
        final int cText = c(a, "text", 0xfff2f4f8), cGray = c(a, "gray", 0xff9aa1b0), cOrange = c(a, "orange", 0xffff8f1f);
        final PP p = find(ps, pid);
        LinearLayout list = pg.list;
        list.removeAllViews();
        if (p == null) return;
        // аватарка проката сверху и название
        View ava;
        if (!p.avatar.isEmpty()) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            IvaChatPatch.setBg(a, iv, "bg_photo");
            IvaChatPatch.round(iv, 0, true);
            IvaChatPatch.loadPhoto(a, iv, p.avatar, "prk" + Integer.toHexString(p.avatar.hashCode()));
            ava = iv;
        } else {
            TextView l = IvaChiefPatch.text(a, IvaChatPatch.letter(p.company), 34f, cOrange, true);
            l.setGravity(Gravity.CENTER);
            l.setBackground(IvaPatch.accentBg(a, cOrange, 48));
            ava = l;
        }
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(a, 96), dp(a, 96));
        al.gravity = Gravity.CENTER_HORIZONTAL;
        al.topMargin = dp(a, 4);
        list.addView(ava, al);
        TextView co = IvaChiefPatch.text(a, p.company.isEmpty() ? LABEL : p.company, 20f, cText, true);
        co.setGravity(Gravity.CENTER);
        co.setPadding(0, dp(a, 10), 0, 0);
        list.addView(co, new LinearLayout.LayoutParams(-1, -2));
        // пункт выдачи
        LinearLayout card = IvaChiefPatch.card(a);
        card.addView(IvaChiefPatch.text(a, chatTitle(p.name), 16f, cText, true));
        String info = infoText(p);
        if (!info.isEmpty()) {
            TextView t = IvaChiefPatch.text(a, info, 14f, cGray, false);
            t.setPadding(0, dp(a, 6), 0, 0);
            card.addView(t);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(a, 14);
        list.addView(card, cl);
        // «Спросить в чате» и «Позвонить»
        final TextView ask = IvaPickupPatch.addButton(a, cOrange);
        ask.setText(ASK);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(a, 12);
        list.addView(ask, bl);
        ask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { ask(a, pid, owner, p, v); } catch (Throwable ignore) {} }
        });
        final String tel = telUri(p.tel);
        if (!tel.isEmpty()) {
            TextView call = IvaSearchPatch.chip(a, CALL + " · " + p.tel, true, false);
            call.setGravity(Gravity.CENTER);
            call.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
            list.addView(call, new LinearLayout.LayoutParams(bl));
            call.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { a.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(tel))); }
                    catch (Throwable e) { Toast.makeText(a, "Нечем позвонить — наберите " + p.tel, Toast.LENGTH_LONG).show(); }
                }
            });
        }
        TextView pts = IvaSearchPatch.chip(a, pointsText(ps.size()), false, false);
        pts.setGravity(Gravity.CENTER);
        pts.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        list.addView(pts, new LinearLayout.LayoutParams(bl));
        pts.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openPoints(a, pg, pid, owner, ps); } catch (Throwable ignore) {} }
        });
        // инструменты пункта
        TextView h = IvaChiefPatch.text(a, "Инструменты пункта", 16f, cText, true);
        h.setPadding(dp(a, 2), dp(a, 20), 0, dp(a, 2));
        list.addView(h, new LinearLayout.LayoutParams(-1, -2));
        Object full = IvaSearchPatch.fullTools;
        List<?> all = full instanceof List && !((List) full).isEmpty() ? (List<?>) full
            : IvaClientPatch.get(a, "tools") instanceof List ? (List<?>) IvaClientPatch.get(a, "tools") : null;
        List<JSONObject> ts = toolsOf(all, pid);
        if (ts.isEmpty()) {
            TextView e = IvaChiefPatch.text(a, "Инструментов в этом пункте пока нет", 13f, cGray, false);
            e.setPadding(dp(a, 2), dp(a, 6), 0, 0);
            list.addView(e, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final JSONObject t : ts) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
            if (bg != null) r.setBackground(bg);
            r.setPadding(dp(a, 10), dp(a, 10), dp(a, 14), dp(a, 10));
            r.setClickable(true);
            r.addView(IvaChatPatch.toolPic(a, t, s(t, "id"), 56), new LinearLayout.LayoutParams(dp(a, 56), dp(a, 56)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView n = IvaChiefPatch.text(a, s(t, "name").isEmpty() ? "Инструмент" : s(t, "name"), 15f, cText, true);
            n.setMaxLines(2);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(n);
            String pr = IvaStaffToolsPatch.priceText(s(t, "price"));
            if (!pr.isEmpty()) {
                TextView x = IvaChiefPatch.text(a, pr, 13f, cOrange, false);
                x.setPadding(0, dp(a, 3), 0, 0);
                col.addView(x);
            }
            LinearLayout.LayoutParams ccl = new LinearLayout.LayoutParams(0, -2, 1f);
            ccl.leftMargin = dp(a, 12);
            r.addView(col, ccl);
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { try { openTool(a, t); } catch (Throwable ignore) {} }
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(a, 8);
            list.addView(r, rl);
        }
    }

    /** «Спросить в чате»: переписка с пунктом (без входа и у админа — как штатное «Спросить…»). */
    static void ask(Activity a, String pid, String owner, PP p, View v) {
        String me = IvaChatPatch.str(IvaClientPatch.get(a, "userEmail")).trim();
        if (!IvaChatPatch.loggedIn(a) || IvaChatPatch.isAdmin(a) || owner.isEmpty() || owner.equalsIgnoreCase(me)) {
            closeAll();
            IvaChatPatch.nativeAsk(a, v);
            return;
        }
        closeAll();
        IvaChatPatch.openPoint(a, owner, pid, chatObj(pid, p));
    }

    static void openTool(Activity a, JSONObject t) {
        closeAll();
        try {
            IvaChatPatch.call(a, "openDetail", new Class<?>[]{JSONObject.class, boolean.class}, t, false);
        } catch (Throwable e) {
            Toast.makeText(a, "Не открылось — найдите инструмент в каталоге", Toast.LENGTH_LONG).show();
        }
    }

    /** «Пункты выдачи»: карточки, как у владельца, с «Перейти». */
    static void openPoints(final Activity a, final IvaChiefPatch.Page parent, final String pid, final String owner, List<PP> ps) {
        final int cText = c(a, "text", 0xfff2f4f8), cGray = c(a, "gray", 0xff9aa1b0), cOrange = c(a, "orange", 0xffff8f1f);
        PP me = find(ps, pid);
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, POINTS, me == null || me.company.isEmpty() ? null : me.company, false);
        track(pg);
        for (final PP p : ps) {
            LinearLayout card = IvaChiefPatch.card(a);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(IvaChiefPatch.text(a, p.name, 16f, cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
            if (p.id.equalsIgnoreCase(pid)) top.addView(IvaChiefPatch.text(a, "вы здесь", 12f, cOrange, false));
            card.addView(top);
            TextView ad = IvaChiefPatch.text(a, p.address, 14f, cText, false);
            ad.setPadding(0, dp(a, 6), 0, 0);
            card.addView(ad);
            if (!p.tel.isEmpty()) {
                TextView tl = IvaChiefPatch.text(a, IvaPickupPatch.telLine(p.tel), 13f, cText, false);
                tl.setPadding(0, dp(a, 4), 0, 0);
                card.addView(tl);
            }
            String hrs = p.open == null ? "" : IvaPickupPatch.hoursText(p.open, p.close);
            if (!hrs.isEmpty()) {
                TextView hr = IvaChiefPatch.text(a, hrs, 13f, cGray, false);
                hr.setPadding(0, dp(a, 4), 0, 0);
                card.addView(hr);
            }
            TextView tc = IvaChiefPatch.text(a, toolsText(p.tools), 13f, cGray, false);
            tc.setPadding(0, dp(a, 4), 0, 0);
            card.addView(tc);
            TextView go = IvaSearchPatch.chip(a, GO, true, false);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
            gl.topMargin = dp(a, 10);
            card.addView(go, gl);
            go.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        if (p.id.equalsIgnoreCase(pid)) { pg.d.dismiss(); return; }      // этот пункт уже открыт
                        pg.d.dismiss();
                        if (parent != null && parent.open()) parent.d.dismiss();
                        openPage(a, p.id, owner);
                    } catch (Throwable ignore) {}
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            pg.list.addView(card, lp);
        }
    }
}

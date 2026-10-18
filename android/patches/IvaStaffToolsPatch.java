package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 10.105 — этап 3в «Сотрудники и пункты» (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
 *
 * Сотрудник с правом «Выкладывает инструмент»: «Мой пункт» → «Инструменты пункта» — инструменты своих пунктов
 * (ждущие одобрения и отклонённые — первыми), кнопка «Добавить инструмент». Форма — ШТАТНАЯ, та же, что у владельца
 * (фото, название, категория, вид, модель, цена, залог, описание, условия, доставка, пункт выдачи — только свои пункты):
 * «Добавить» открывает штатную страницу добавления без вкладок кабинета («Мои инструменты» сотруднику не показываем),
 * нажатие на инструмент — штатную правку (сохранить, скрыть/показать, удалить). После «Опубликовать» / «Сохранить» /
 * «Удалить» штатный код открывает кабинет владельца (showTab("admhub")) — сотрудника возвращаем в «Инструменты пункта».
 * Вид, модель и пункт после публикации пишутся как у владельца (IvaKindPatch / IvaToolPointPatch), но свой новый
 * инструмент сотрудник ищет по своему номеру (tools.staff_id), а не по почте владельца.
 *
 * Владелец: «Настройки → Настройка проката» → «Инструменты сотрудников» (сразу / после моего одобрения, по умолчанию —
 * после одобрения) и «На одобрении · N» (карточка, «Опубликовать» / «Отклонить»); уведомление «Инструмент на
 * одобрение» (переключатель «Новая заявка»), нажатие открывает «На одобрении».
 *
 * Права — в базе (SQL 20261030_staff_tools.sql): правила доступа к tools, триггер (владелец — прокат, пункт — свой,
 * «на одобрении»), функции iva_staff_tools / iva_tools_pending / iva_tool_review / iva_staff_publish(_set).
 * Без SQL — «заработают после обновления базы». Новых вставок в smali нет: вызовы — из IvaPatch.onTab / onBack,
 * IvaStaffPatch («Мой пункт»), IvaShopPatch («Настройка проката»), IvaNotifyPatch (tick, bgRun, route).
 */
public class IvaStaffToolsPatch {
    static final String LIST_RPC = "/rest/v1/rpc/iva_staff_tools", PENDING_RPC = "/rest/v1/rpc/iva_tools_pending",
        REVIEW_RPC = "/rest/v1/rpc/iva_tool_review", MODE_RPC = "/rest/v1/rpc/iva_staff_publish",
        CARD_RPC = "/rest/v1/rpc/iva_tool_pending_card",
        MODE_SET_RPC = "/rest/v1/rpc/iva_staff_publish_set";
    static final String TITLE = "Инструменты пункта", ADD = "Добавить инструмент";
    static final String REVIEW_TITLE = "На одобрении", MODE_TITLE = "Инструменты сотрудников", OPEN = "review";
    static final String NO_DB = "Инструменты сотрудников заработают после обновления базы (SQL 10.105)";
    static final String[] MODES = {"now", "approve"};
    static final String[] MODE_TEXT = {"Публиковать сразу", "После моего одобрения"};
    static final int ID_NOTE = 7206;
    static final long NO_DB_PAUSE_MS = 30L * 60000L;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    /** Показывать ли «Инструменты пункта»: принятый сотрудник с правом «Выкладывает инструмент». */
    static boolean want(String status, boolean[] r) { return "active".equals(status) && r != null && r.length >= 2 && r[1]; }

    /** Штатный успех открыл кабинет владельца — сотрудника (не админа) возвращаем в «Инструменты пункта». */
    static boolean redirect(String tab, boolean staff, boolean admin) { return staff && !admin && "admhub".equals(tab); }

    /** Системная «Назад» на штатной форме или правке у сотрудника — тоже в «Инструменты пункта». */
    static boolean backTab(String tab, boolean staff, boolean admin) {
        return staff && !admin && ("addtool".equals(tab) || "tooledit".equals(tab));
    }

    static String statusText(String review, boolean active) {
        if ("pending".equals(review)) return "На одобрении";
        if ("rejected".equals(review)) return "Отклонён";
        return active ? "Опубликован" : "Скрыт";
    }

    static String statusHint(String review) {
        if ("pending".equals(review)) return "Клиенты увидят после одобрения владельца";
        if ("rejected".equals(review)) return "Владелец отклонил — исправьте и сохраните, он посмотрит снова";
        return "";
    }

    /** «400 ₽/сутки»; пусто или не число — пусто. */
    static String priceText(String raw) {
        String s = raw == null ? "" : raw.trim().replace(',', '.');
        if (s.isEmpty() || "null".equals(s)) return "";
        try {
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return "";
            return Math.round(d) + " ₽/сутки";
        } catch (Throwable e) { return ""; }
    }

    static String line2(String price, String point) {
        String p = priceText(price), q = point == null ? "" : point.trim();
        return p.isEmpty() ? q : q.isEmpty() ? p : p + " · " + q;
    }

    /** {"p_id":"…","p_ok":true} — только номер-uuid; иначе null. */
    static String reviewBody(String id, boolean ok) {
        if (!IvaToolPointPatch.uuid(id)) return null;
        return "{\"p_id\":\"" + id.trim().toLowerCase(Locale.ROOT) + "\",\"p_ok\":" + ok + "}";
    }

    static String modeBody(String mode) {
        return "now".equals(mode) || "approve".equals(mode) ? "{\"p_mode\":\"" + mode + "\"}" : null;
    }

    /** Ответ iva_staff_publish(): "now" / "approve"; null или что-то другое — пусто. */
    static String modeOf(String body) {
        String t = body == null ? "" : body.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) t = t.substring(1, t.length() - 1);
        return "now".equals(t) || "approve".equals(t) ? t : "";
    }

    static String modeText(String mode) { return "now".equals(mode) ? MODE_TEXT[0] : "approve".equals(mode) ? MODE_TEXT[1] : ""; }

    static String reviewRow(int n) { return n > 0 ? REVIEW_TITLE + " · " + n : REVIEW_TITLE; }

    static String noteTitle(int n) { return n > 1 ? "Инструменты на одобрение: " + n : "Инструмент на одобрение"; }

    static String noteText(int n, String name, String staff) {
        if (n > 1) return "Откройте «Настройка проката → " + REVIEW_TITLE + "»";
        String t = IvaNotifyPatch.oneLine(name, 80), s = staff == null ? "" : staff.trim();
        return s.isEmpty() ? t : t + " · добавил(а) " + s;
    }

    /** Фразы базы (RAISE EXCEPTION в 20261030_staff_tools.sql), которые показываем как есть. */
    static final String[] DB_PHRASES = {
        "Пункт выдачи — только из ваших пунктов", "У вас нет пункта выдачи — обратитесь к владельцу проката",
        "Инструмент уже не ждёт одобрения — обновите список", "Только владелец проката", "Неизвестный режим публикации"};

    static String dbError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        for (String p : DB_PHRASES) if (b.contains(p)) return p;
        if (b.contains("PGRST202") || "404".equals(c)) return NO_DB;
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Нет права — войдите заново";
        return c.isEmpty() ? "Не получилось — нет связи с сервером" : "Не получилось (ответ " + c + ")";
    }

    // ───────────── разбор ответов ─────────────

    static final class Tool {
        String id = "", name = "", price = "", point = "", review = "";
        boolean active, mine;
        JSONObject raw;
    }

    static String str(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, "").trim(); }

    static List<Tool> parseTools(String body) throws Exception {
        List<Tool> out = new ArrayList<Tool>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Tool x = new Tool();
            x.id = str(o, "id");
            x.name = str(o, "name");
            x.price = str(o, "price");
            x.point = str(o, "point_name");
            x.review = str(o, "review");
            x.active = o.optBoolean("active", false);
            x.mine = o.optBoolean("mine", false);
            x.raw = o;
            if (!x.id.isEmpty()) out.add(x);
        }
        return out;
    }

    static final class Pending { String id = "", name = "", price = "", point = "", staff = ""; }

    // ── 10.120: «На одобрении» → нажатие на карточку — инструмент целиком (просьба владельца 18.10.2026) ──

    static String js(JSONObject j, String k) {
        if (j == null || !j.has(k) || j.isNull(k)) return "";
        String v = j.optString(k, "").trim();
        return "null".equals(v) ? "" : v;
    }

    /** Фото инструмента: img, затем imgs (без повторов и пустых). */
    static List<String> photos(JSONObject j) {
        List<String> out = new ArrayList<String>();
        String one = js(j, "img");
        if (one.startsWith("http")) out.add(one);
        JSONArray arr = j == null ? null : j.optJSONArray("imgs");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String u = arr.optString(i, "").trim();
            if (u.startsWith("http") && !out.contains(u)) out.add(u);
        }
        return out;
    }

    /** «2 000 ₽» из числа; не число — «». */
    static String rub(String raw) {
        String s = raw == null ? "" : raw.trim().replace(',', '.');
        if (s.isEmpty() || "null".equals(s)) return "";
        try {
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return "";
            return Math.round(d) + " ₽";
        } catch (Throwable e) { return ""; }
    }

    /** Строки карточки «подпись — значение» (пустые поля не показываем). catTitle / kindTitle — названия из справочников. */
    static List<String[]> cardRows(JSONObject j, String catTitle, String kindTitle) {
        List<String[]> out = new ArrayList<String[]>();
        if (j == null) return out;
        String cat = catTitle == null || catTitle.trim().isEmpty() ? js(j, "cat_label") : catTitle.trim();
        if (cat.isEmpty()) cat = js(j, "cat");
        String kind = kindTitle == null || kindTitle.trim().isEmpty() ? js(j, "kind") : kindTitle.trim();
        String del = j.optBoolean("delivery", false)
            ? (rub(js(j, "delivery_price")).isEmpty() ? "Есть" : "Есть, " + rub(js(j, "delivery_price")) + " за 1 км") : "Нет, только самовывоз";
        String[][] rows = {
            {"Название", js(j, "name")}, {"Категория", cat}, {"Вид", kind}, {"Модель", js(j, "model_text")},
            {"Цена", priceText(js(j, "price"))}, {"Залог", rub(js(j, "deposit"))}, {"Описание", js(j, "descr")},
            {"Условия аренды", js(j, "terms")}, {"Доставка", del}, {"Пункт выдачи", js(j, "point_name")},
            {"Добавил(а)", js(j, "staff_name")}};
        for (String[] r : rows) if (!r[1].isEmpty()) out.add(r);
        return out;
    }

    static List<Pending> parsePending(String body) throws Exception {
        List<Pending> out = new ArrayList<Pending>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Pending p = new Pending();
            p.id = str(o, "id");
            p.name = str(o, "name");
            p.price = str(o, "price");
            p.point = str(o, "point_name");
            p.staff = str(o, "staff_name");
            if (!p.id.isEmpty()) out.add(p);
        }
        return out;
    }

    // ───────────── кто вошёл: сотрудник «Каталог» (кэш «Мой пункт», IvaStaffPatch) ─────────────

    static volatile String meBody = null;
    static volatile IvaStaffPatch.Me meParsed = null;

    static IvaStaffPatch.Me cached(Activity a) {
        String e = IvaPickupPatch.me(a);
        if (e.isEmpty()) return null;
        String body = IvaStaffPatch.prefs(a).getString("me_" + e, "");
        if (body == null || body.isEmpty()) return null;
        if (body.equals(meBody)) return meParsed;
        IvaStaffPatch.Me m = null;
        try { m = IvaStaffPatch.parseMe(body); } catch (Throwable ignore) {}
        meParsed = m;
        meBody = body;
        return m;
    }

    /** Вошёл сотрудник с правом «Выкладывает инструмент» (не владелец проката). */
    static boolean staff(Activity a) {
        try {
            if (!IvaNotifyPatch.loggedIn(a) || IvaNotifyPatch.isAdmin(a)) return false;
            IvaStaffPatch.Me m = cached(a);
            return m != null && want(m.status, m.r);
        } catch (Throwable e) { return false; }
    }

    /** Пункты сотрудника «Каталог» (для поля «Пункт выдачи» в форме) или null — не сотрудник. */
    static List<IvaPickupPatch.Point> points(Activity a) {
        if (!staff(a)) return null;
        IvaStaffPatch.Me m = cached(a);
        return m == null ? null : m.points;
    }

    /** Номер сотрудника (tools.staff_id) или "" — не сотрудник. */
    static String staffId(Activity a) {
        if (!staff(a)) return "";
        IvaStaffPatch.Me m = cached(a);
        return m == null ? "" : m.id;
    }

    // ───────────── штатная форма у сотрудника: без вкладок кабинета, возврат на свою страницу ─────────────

    static WeakReference<Dialog> minePage, toolsPage;

    static void close(WeakReference<Dialog> r) {
        Dialog d = r == null ? null : r.get();
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignore) {}
    }

    /** Из IvaPatch.onTab (последним): вкладки «Мои инструменты / Добавить» — прятать у сотрудника; кабинет — возврат. */
    static void onTab(final Activity a, String tab) {
        String t = tab == null ? "" : tab;
        boolean form = "addtool".equals(t) || "admhub".equals(t) || "tooledit".equals(t);
        boolean st = form && staff(a);
        boolean hm = st && hubMode(a);                       // 10.113: вид «как у админов» — вкладки кабинета видны
        hubTabs(a, st && !hm);
        if (hm) installTabs(a);
        if (redirect(t, st, false)) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { try { backToList(a); } catch (Throwable ignore) {} }
            });
        }
    }

    /** Из IvaPatch.onBack: сотрудник на штатной форме / правке — «Назад» ведёт в «Инструменты пункта». */
    static boolean onBack(Activity a, String curTab) {
        if (!backTab(curTab, staff(a), false)) return false;
        backToList(a);
        return true;
    }

    static void backToList(Activity a) {
        if (hubMode(a) && IvaClientPatch.openHub(a, 1)) return;   // 10.113: вид «как у админов» — в «Мой инструмент»
        IvaNotifyPatch.tab(a, "profile");
        IvaStaffPatch.Me m = cached(a);
        if (m != null && want(m.status, m.r)) open(a, m.company);
    }

    static WeakReference<View> hiddenRow;

    /** Ряд вкладок кабинета (родитель hubTabMy): у сотрудника — скрыт, у владельца — как был (возвращаем только свой). */
    static void hubTabs(Activity a, boolean hide) {
        int id = a.getResources().getIdentifier("hubTabMy", "id", a.getPackageName());
        View my = id == 0 ? null : a.findViewById(id);
        if (my == null || !(my.getParent() instanceof View)) return;
        View row = (View) my.getParent();
        if (hide) {
            if (row.getVisibility() != View.GONE) { row.setVisibility(View.GONE); hiddenRow = new WeakReference<View>(row); }
        } else {
            View h = hiddenRow == null ? null : hiddenRow.get();
            if (h == row) { row.setVisibility(View.VISIBLE); hiddenRow = null; }
        }
    }

    /** «Добавить инструмент»: штатная страница добавления, сразу на форме (как вкладка «Добавить» у владельца). */
    static void openAdd(Activity a) {
        close(toolsPage);
        close(minePage);
        IvaNotifyPatch.tab(a, "addtool");
        try {
            Method m = a.getClass().getDeclaredMethod("showAdmHub", int.class);
            m.setAccessible(true);
            m.invoke(a, 1);
        } catch (Throwable ignore) {}
        hubTabs(a, true);
    }

    /** Нажали на инструмент: штатная правка с его данными. */
    static void openEdit(Activity a, Tool t) {
        if (t == null || t.raw == null) return;
        close(toolsPage);
        close(minePage);
        try {
            Method m = a.getClass().getDeclaredMethod("openToolEdit", JSONObject.class);
            m.setAccessible(true);
            m.invoke(a, t.raw);
        } catch (Throwable e) { IvaStaffPatch.toast(a, "Не получилось открыть инструмент"); }
    }

    // ───────────── 10.113: вид «как у админов» (IvaLogoPatch) — «Мой инструмент» / «Добавить инструмент» ─────────────

    /** Сотрудник «Каталог» выбрал «Вид раздела с логотипом → как у админов». */
    static boolean hubMode(Activity a) {
        try { return staff(a) && IvaLogoPatch.ADMIN.equals(IvaLogoPatch.view(a)); } catch (Throwable e) { return false; }
    }

    /** «Мой инструмент» в разделе логотипа: инструменты пунктов сотрудника (тот же список, что «Инструменты пункта»). */
    static void renderHub(Activity a, LinearLayout content, int gen) {
        final Ui u = new Ui();
        u.a = a;
        u.hubGen = gen;
        u.cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8);
        u.cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        u.cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(dp(a, 4), dp(a, 8), 0, 0);
        content.addView(u.status, new LinearLayout.LayoutParams(-1, -2));
        u.items = new LinearLayout(a);
        u.items.setOrientation(LinearLayout.VERTICAL);
        content.addView(u.items, new LinearLayout.LayoutParams(-1, -2));
        reload(u);
    }

    /** «Добавить инструмент» из раздела логотипа: штатная форма, вкладки кабинета видны («Мой инструмент» — наш раздел). */
    static void openAddHub(Activity a) {
        close(toolsPage);
        close(minePage);
        IvaNotifyPatch.tab(a, "addtool");
        try {
            Method m = a.getClass().getDeclaredMethod("showAdmHub", int.class);
            m.setAccessible(true);
            m.invoke(a, 1);
        } catch (Throwable ignore) {}
        hubTabs(a, false);
        installTabs(a);
    }

    static WeakReference<View> tabMy;

    /** Вкладка «Мой инструмент» кабинета: у сотрудника с видом «как у админов» — наш раздел, у всех остальных — как
     *  штатная (showAdmHub(0), то же, что делает штатный обработчик). «Добавить инструмент» остаётся штатной. */
    static void installTabs(final Activity a) {
        int id = a.getResources().getIdentifier("hubTabMy", "id", a.getPackageName());
        View my = id == 0 ? null : a.findViewById(id);
        if (my == null || (tabMy != null && tabMy.get() == my)) return;
        my.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hubMode(a) && IvaClientPatch.openHub(a, 1)) return;
                try {
                    Method m = a.getClass().getDeclaredMethod("showAdmHub", int.class);
                    m.setAccessible(true);
                    m.invoke(a, 0);
                } catch (Throwable ignore) {}
            }
        });
        tabMy = new WeakReference<View>(my);
    }

    // ───────────── страница «Инструменты пункта» (сотрудник) ─────────────

    static final class Ui {
        Activity a;
        int hubGen = -1;            // 10.113: список в разделе логотипа (страницы нет) — поколение отрисовки
        IvaChiefPatch.Page page;
        LinearLayout items;
        TextView status;
        boolean busy;
        int cText, cGray, cOrange;
    }

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static void open(final Activity a, String company) {
        close(toolsPage);
        final Ui u = new Ui();
        u.a = a;
        u.cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8);
        u.cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        u.cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        String c = company == null || company.trim().isEmpty() ? "Прокат" : company.trim();
        u.page = IvaChiefPatch.page(a, TITLE, "«" + c + "»: инструменты ваших пунктов. Новые могут ждать одобрения владельца.", false);
        toolsPage = new WeakReference<Dialog>(u.page.d);
        TextView add = IvaPickupPatch.addButton(a, u.cOrange);
        add.setText(ADD);
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { openAdd(a); } catch (Throwable ignore) {} } });
        u.page.list.addView(add, new LinearLayout.LayoutParams(-1, -2));
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
                List<Tool> got = null;
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, LIST_RPC, "POST", "{}", true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (err.isEmpty()) got = parseTools(r.length > 1 ? r[1] : "");
                } catch (Throwable e) { err = dbError("", ""); }
                final List<Tool> ft = got;
                final String fe = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            u.busy = false;
                            if (u.page != null ? !u.page.open() : !IvaClientPatch.hubAlive(u.hubGen)) return;
                            if (ft == null) { u.status.setText(fe); u.status.setTextColor(u.cOrange); return; }
                            draw(u, ft);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void draw(final Ui u, List<Tool> tools) {
        final Activity a = u.a;
        u.items.removeAllViews();
        for (final Tool t : tools) {
            LinearLayout c = IvaChiefPatch.card(a);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(IvaChiefPatch.text(a, t.name.isEmpty() ? "Без названия" : t.name, 15f, u.cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
            boolean warn = !t.review.isEmpty();
            TextView s = IvaChiefPatch.text(a, statusText(t.review, t.active), 12f, warn ? u.cOrange : u.cGray, warn);
            s.setPadding(dp(a, 8), 0, 0, 0);
            top.addView(s);
            c.addView(top);
            String l2 = line2(t.price, t.point);
            if (!l2.isEmpty()) {
                TextView x = IvaChiefPatch.text(a, l2, 14f, u.cGray, false);
                x.setPadding(0, dp(a, 4), 0, 0);
                c.addView(x);
            }
            String h = statusHint(t.review);
            if (!h.isEmpty()) {
                TextView x = IvaChiefPatch.text(a, h, 13f, u.cGray, false);
                x.setPadding(0, dp(a, 4), 0, 0);
                c.addView(x);
            }
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openEdit(a, t); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            u.items.addView(c, lp);
        }
        u.status.setTextColor(u.cGray);
        u.status.setText(tools.isEmpty() ? "В ваших пунктах пока нет инструментов" : "");
        u.status.setVisibility(tools.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ───────────── владелец: «Настройка проката» → режим и «На одобрении» ─────────────

    /** 10.113 (просьба владельца 17.10.2026): общей строки «Инструменты сотрудников» больше нет — выключатель у каждого
     *  пункта (IvaPickupPatch, форма пункта). Остаётся строка «На одобрении · N». */
    static final boolean MODE_ROW = false;

    /** Из IvaShopPatch.openPage: «На одобрении · N» (до 10.113 — ещё и «Инструменты сотрудников»). */
    static void settingsSection(final Activity a, LinearLayout list, final int cText, final int cGray, final int cOrange) {
        float den = a.getResources().getDisplayMetrics().density;
        final LinearLayout modeRow = MODE_ROW ? IvaShopPatch.row(a, list, new IvaShopPatch.Icon(IvaShopPatch.Icon.CARD, cOrange, den), MODE_TITLE, "Загружаем…", cText, cGray) : null;
        final LinearLayout revRow = IvaShopPatch.row(a, list, new IvaShopPatch.Icon(IvaShopPatch.Icon.SHOP, cOrange, den), REVIEW_TITLE, "Загружаем…", cText, cGray);
        final String[] mode = {""};
        final Runnable refresh = new Runnable() { @Override public void run() { loadSection(a, modeRow, revRow, mode); } };
        sectionRefresh = new WeakReference<Runnable>(refresh);                 // 10.116: «на одобрение» пришло — счётчик сразу
        if (modeRow != null) modeRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaClientPatch.styledChoice(a, MODE_TITLE, "Как публиковать новые инструменты, которые добавили сотрудники. Правки — всегда сразу.",
                    MODE_TEXT, new IvaClientPatch.Choice() {
                        @Override public void pick(int i) { if (i >= 0 && i < MODES.length) setMode(a, MODES[i], refresh); }
                    });
            }
        });
        revRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openReview(a, refresh); } catch (Throwable ignore) {} }
        });
        refresh.run();
    }

    static TextView sub(LinearLayout row) {
        try {
            ViewGroup col = (ViewGroup) row.getChildAt(1);
            return (TextView) col.getChildAt(1);
        } catch (Throwable e) { return null; }
    }

    static TextView title(LinearLayout row) {
        try {
            ViewGroup col = (ViewGroup) row.getChildAt(1);
            return (TextView) col.getChildAt(0);
        } catch (Throwable e) { return null; }
    }

    static void loadSection(final Activity a, final LinearLayout modeRow, final LinearLayout revRow, final String[] mode) {
        new Thread(new Runnable() {
            @Override public void run() {
                String m = "", e1, e2;
                int n = -1;
                if (modeRow == null) e1 = "";                       // 10.113: строки режима нет — не спрашиваем
                else try {
                    String[] r = IvaClientPatch.apiCall(a, MODE_RPC, "POST", "{}", true);
                    e1 = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (e1.isEmpty()) m = modeOf(r.length > 1 ? r[1] : "");
                } catch (Throwable x) { e1 = dbError("", ""); }
                try {
                    String[] r = IvaClientPatch.apiCall(a, PENDING_RPC, "POST", "{}", true);
                    e2 = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (e2.isEmpty()) n = parsePending(r.length > 1 ? r[1] : "").size();
                } catch (Throwable x) { e2 = dbError("", ""); }
                final String fm = m, f1 = e1, f2 = e2;
                final int fn = n;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            mode[0] = fm;
                            TextView s1 = sub(modeRow), s2 = sub(revRow), t2 = title(revRow);
                            if (s1 != null) s1.setText(!f1.isEmpty() ? f1 : modeText(fm).isEmpty() ? MODE_TEXT[1] : modeText(fm));
                            if (t2 != null) t2.setText(reviewRow(Math.max(0, fn)));
                            if (s2 != null) s2.setText(!f2.isEmpty() ? f2 : fn > 0 ? "Добавили сотрудники — клиенты пока не видят" : "Ничего не ждёт одобрения");
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void setMode(final Activity a, String mode, final Runnable done) {
        final String body = modeBody(mode);
        if (body == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                try {
                    String[] r = IvaClientPatch.apiCall(a, MODE_SET_RPC, "POST", body, true);
                    e = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable x) { e = dbError("", ""); }
                final String fe = e;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        IvaStaffPatch.toast(a, fe.isEmpty() ? "Сохранено" : fe);
                        try { done.run(); } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static final class Rev {
        Activity a;
        IvaChiefPatch.Page page;
        LinearLayout items;
        TextView status;
        Runnable changed;
        boolean busy;
        int cText, cGray, cOrange;
    }

    static WeakReference<Rev> openRev;                    // 10.116: открытая «На одобрении» — обновить сразу
    static WeakReference<Runnable> sectionRefresh;

    static void openReview(final Activity a, Runnable changed) {
        final Rev u = new Rev();
        openRev = new WeakReference<Rev>(u);
        u.a = a;
        u.changed = changed;
        u.cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8);
        u.cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        u.cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        u.page = IvaChiefPatch.page(a, REVIEW_TITLE, "Инструменты, которые добавили сотрудники. Клиенты их не видят, пока вы не опубликуете.", false);
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(dp(a, 2), dp(a, 4), 0, 0);
        u.page.list.addView(u.status, new LinearLayout.LayoutParams(-1, -2));
        u.items = new LinearLayout(a);
        u.items.setOrientation(LinearLayout.VERTICAL);
        u.page.list.addView(u.items, new LinearLayout.LayoutParams(-1, -2));
        reloadReview(u);
    }

    static void reloadReview(final Rev u) {
        if (u.busy) return;
        u.busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Pending> got = null;
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, PENDING_RPC, "POST", "{}", true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (err.isEmpty()) got = parsePending(r.length > 1 ? r[1] : "");
                } catch (Throwable e) { err = dbError("", ""); }
                final List<Pending> fp = got;
                final String fe = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            u.busy = false;
                            if (!u.page.open()) return;
                            if (fp == null) { u.status.setText(fe); u.status.setTextColor(u.cOrange); return; }
                            drawReview(u, fp);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void drawReview(final Rev u, List<Pending> ps) {
        final Activity a = u.a;
        u.items.removeAllViews();
        for (final Pending p : ps) {
            LinearLayout c = IvaChiefPatch.card(a);
            c.addView(IvaChiefPatch.text(a, p.name.isEmpty() ? "Без названия" : p.name, 15f, u.cText, true));
            c.setClickable(true);                                          // 10.120: нажатие — инструмент целиком
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { openCard(u, p); } catch (Throwable ignore) {} } });
            TextView more = IvaChiefPatch.text(a, "Нажмите, чтобы посмотреть целиком", 12f, u.cOrange, false);
            more.setPadding(0, dp(a, 2), 0, 0);
            c.addView(more);
            String l2 = line2(p.price, p.point);
            if (!l2.isEmpty()) {
                TextView x = IvaChiefPatch.text(a, l2, 14f, u.cGray, false);
                x.setPadding(0, dp(a, 4), 0, 0);
                c.addView(x);
            }
            if (!p.staff.isEmpty()) {
                TextView x = IvaChiefPatch.text(a, "Добавил(а): " + p.staff, 13f, u.cGray, false);
                x.setPadding(0, dp(a, 4), 0, 0);
                c.addView(x);
            }
            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, dp(a, 12), 0, 0);
            final TextView yes = IvaPickupPatch.addButton(a, u.cOrange);
            yes.setText("Опубликовать");
            final TextView no = IvaSearchPatch.chip(a, "Отклонить", false, false);
            no.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(0, -2, 1f);
            yl.rightMargin = dp(a, 8);
            btns.addView(yes, yl);
            btns.addView(no, new LinearLayout.LayoutParams(0, -1, 1f));
            c.addView(btns);
            yes.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { review(u, p, true, yes); } });
            no.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    IvaClientPatch.styledChoice(a, "Отклонить инструмент?", "Клиенты его не увидят. Сотрудник увидит «Отклонён» и сможет исправить.",
                        new String[]{"Отклонить"}, new IvaClientPatch.Choice() { @Override public void pick(int i) { if (i == 0) review(u, p, false, no); } });
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            u.items.addView(c, lp);
        }
        u.status.setTextColor(u.cGray);
        u.status.setText(ps.isEmpty() ? "Ничего не ждёт одобрения" : "");
        u.status.setVisibility(ps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Страница инструмента на одобрении: фото, все поля, внизу «Опубликовать» / «Отклонить». */
    static void openCard(final Rev u, final Pending p) {
        final Activity a = u.a;
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, REVIEW_TITLE, p.name.isEmpty() ? null : p.name, false);
        final TextView st = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        st.setPadding(dp(a, 2), dp(a, 4), 0, 0);
        pg.list.addView(st, new LinearLayout.LayoutParams(-1, -2));
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject got = null;
                String err = "";
                try {
                    String[] r = IvaClientPatch.apiCall(a, CARD_RPC, "POST", "{\"p_id\":\"" + p.id.trim().toLowerCase(Locale.ROOT) + "\"}", true);
                    String b = r != null && r.length > 1 && r[1] != null ? r[1].trim() : "";
                    if (r != null && r[0] != null && r[0].startsWith("2") && b.startsWith("{")) got = new JSONObject(b);
                    else if (r != null && ("404".equals(r[0]) || b.contains("PGRST202"))) err = "Целиком покажем после обновления базы (SQL 10.120) — пока главное:";
                    else if (b.contains("Инструмент не найден")) err = "Инструмент уже не ждёт одобрения — список обновлён";
                    else err = r == null ? "Не загрузилось — нет связи с сервером" : "Не загрузилось (ответ " + r[0] + ")";
                } catch (Throwable e) { err = "Не загрузилось — нет связи с сервером"; }
                final JSONObject fj = got;
                final String fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!pg.open()) return;
                            JSONObject j = fj;
                            if (j == null) {                                // без SQL — то, что уже знаем
                                j = new JSONObject();
                                j.put("name", p.name); j.put("price", p.price); j.put("point_name", p.point); j.put("staff_name", p.staff);
                            }
                            drawCard(u, pg, p, j, fe);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void drawCard(final Rev u, final IvaChiefPatch.Page pg, final Pending p, JSONObject j, String note) {
        final Activity a = u.a;
        LinearLayout list = pg.list;
        list.removeAllViews();
        if (note != null && !note.isEmpty()) {
            TextView n = IvaChiefPatch.text(a, note, 13f, u.cOrange, false);
            n.setPadding(dp(a, 2), dp(a, 4), 0, dp(a, 6));
            list.addView(n, new LinearLayout.LayoutParams(-1, -2));
        }
        List<String> ph = photos(j);
        if (!ph.isEmpty()) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < ph.size(); i++) {
                final String url = ph.get(i);
                android.widget.ImageView iv = new android.widget.ImageView(a);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                IvaChatPatch.setBg(a, iv, "bg_photo");
                IvaChatPatch.round(iv, dp(a, 12), false);
                IvaChatPatch.loadPhoto(a, iv, url, "rev" + p.id + "_" + i);
                iv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { IvaStaffChatPatch.view(a, url, "image/" + "*"); } });
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(a, ph.size() == 1 ? 300 : 220), dp(a, 180));
                if (i > 0) il.leftMargin = dp(a, 8);
                row.addView(iv, il);
            }
            hs.addView(row);
            list.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        }
        String cat = js(j, "cat"), sub = js(j, "sub"), catTitle = "", kindTitle = "";
        try {
            String ct = cat.isEmpty() ? null : IvaKindPatch.nativeTitle(a, "cats", null, cat);
            String sc = sub.isEmpty() ? null : IvaKindPatch.nativeTitle(a, "subcats", cat, sub);
            catTitle = (ct == null ? "" : ct) + (sc == null || sc.isEmpty() ? "" : (ct == null ? "" : " · ") + sc);
            kindTitle = IvaKindPatch.titleOf(IvaKindPatch.list(), js(j, "kind"));
        } catch (Throwable ignore) {}
        LinearLayout card = IvaChiefPatch.card(a);
        boolean first = true;
        for (String[] r : cardRows(j, catTitle, kindTitle)) {
            TextView l = IvaChiefPatch.text(a, r[0], 12f, u.cGray, false);
            if (!first) l.setPadding(0, dp(a, 10), 0, 0);
            card.addView(l);
            TextView v = IvaChiefPatch.text(a, r[1], 15f, u.cText, "Название".equals(r[0]));
            v.setPadding(0, dp(a, 2), 0, 0);
            card.addView(v);
            first = false;
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(a, 12);
        list.addView(card, cl);
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(a, 14), 0, 0);
        final TextView yes = IvaPickupPatch.addButton(a, u.cOrange);
        yes.setText("Опубликовать");
        final TextView no = IvaSearchPatch.chip(a, "Отклонить", false, false);
        no.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(0, -2, 1f);
        yl.rightMargin = dp(a, 8);
        btns.addView(yes, yl);
        btns.addView(no, new LinearLayout.LayoutParams(0, -1, 1f));
        list.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        final Runnable close = new Runnable() { @Override public void run() { try { if (pg.open()) pg.d.dismiss(); } catch (Throwable ignore) {} } };
        yes.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { review(u, p, true, yes, close); } });
        no.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaClientPatch.styledChoice(a, "Отклонить инструмент?", "Клиенты его не увидят. Сотрудник увидит «Отклонён» и сможет исправить.",
                    new String[]{"Отклонить"}, new IvaClientPatch.Choice() { @Override public void pick(int i) { if (i == 0) review(u, p, false, no, close); } });
            }
        });
    }

    static void review(final Rev u, final Pending p, final boolean ok, final TextView btn) { review(u, p, ok, btn, null); }

    static void review(final Rev u, final Pending p, final boolean ok, final TextView btn, final Runnable done) {
        final String body = reviewBody(p.id, ok);
        if (body == null) return;
        btn.setEnabled(false);
        btn.setAlpha(0.6f);
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, REVIEW_RPC, "POST", body, true);
                    e = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable x) { e = dbError("", ""); }
                final String fe = e;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            btn.setEnabled(true);
                            btn.setAlpha(1f);
                            IvaStaffPatch.toast(u.a, fe.isEmpty() ? (ok ? "Опубликовано — клиенты видят" : "Отклонено") : fe);
                            if (fe.isEmpty()) try { IvaNotifyPatch.call(u.a, "loadCatalog"); } catch (Throwable ignore) {}   // каталог — с новым
                            if (fe.isEmpty() && done != null) done.run();     // 10.120: со страницы инструмента — назад к списку
                            reloadReview(u);
                            if (u.changed != null) u.changed.run();
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    // ───────────── уведомление «Инструмент на одобрение» (IvaNotifyPatch.tick и bgRun, владельцу, не главный поток) ─────────────

    static final Object CHECK_LOCK = new Object();         // 10.116: опрос, фон и Realtime — не показать одно и то же дважды

    /** Сколько ждёт одобрения (-1 — неизвестно). Новые — уведомление (свой переключатель «Инструменты на одобрение»). */
    static int check(Context c, String email) throws Exception {
        synchronized (CHECK_LOCK) {
        SharedPreferences sp = c.getSharedPreferences("iva_staff", 0);
        long now = System.currentTimeMillis();
        if (now < sp.getLong("review_nodb_" + email, 0L)) return -1;
        String[] r = IvaNotifyPatch.api(c, PENDING_RPC, "POST", "{}");
        if (r == null) return -1;
        if (!r[0].startsWith("2")) {
            if (NO_DB.equals(dbError(r[0], r.length > 1 ? r[1] : ""))) sp.edit().putLong("review_nodb_" + email, now + NO_DB_PAUSE_MS).apply();
            return -1;
        }
        List<Pending> ps = parsePending(r.length > 1 ? r[1] : "");
        List<String> ids = new ArrayList<String>();
        for (Pending p : ps) ids.add(p.id);
        SharedPreferences p = IvaNotifyPatch.prefs(c);
        String k = IvaNotifyPatch.key("seen_tr", email);
        Set<String> seen = IvaNotifyPatch.splitSet(p.getString(k, null));
        List<String> nw = IvaNotifyPatch.fresh(seen, ids);                                // первый раз — молча запоминаем
        p.edit().putString(k, IvaNotifyPatch.joinSet(IvaNotifyPatch.remember(seen, ids))).apply();
        if (nw.isEmpty() || !IvaNotifyPatch.on(c, IvaNotifyPatch.K_REV, true)) return ps.size();
        String name = "", who = "";
        for (Pending x : ps) if (x.id.equals(nw.get(0))) { name = x.name; who = x.staff; break; }
        if (c instanceof Activity) {
            final Activity a = (Activity) c;
            a.runOnUiThread(new Runnable() {
                @Override public void run() { try { Toast.makeText(a, "Инструмент на одобрение!", Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {} }
            });
        }
        IvaNotifyPatch.showNote(c, IvaNotifyPatch.CH_REV, ID_NOTE, noteTitle(nw.size()), noteText(nw.size(), name, who), OPEN, nw.get(0));
        return ps.size();
        }
    }

    /** 10.116: сколько ждёт одобрения — для строки «Настройка проката» в кабинете (-1 — неизвестно; фоновый поток). */
    static int pendingCount(Activity a) throws Exception {
        String[] r = IvaClientPatch.apiCall(a, PENDING_RPC, "POST", "{}", true);
        if (r == null || !r[0].startsWith("2")) return -1;
        return parsePending(r.length > 1 ? r[1] : "").size();
    }

    /** 10.116: база отметила «на одобрение» (Realtime → IvaAccessPatch.poke): уведомление, «На одобрении» и счётчики — сразу. */
    static void live(final Activity a) {
        if (a == null || a.isFinishing() || !IvaNotifyPatch.loggedIn(a) || !IvaNotifyPatch.isAdmin(a)) return;
        final String email = IvaPickupPatch.me(a);
        if (email.isEmpty()) return;
        a.getSharedPreferences("iva_staff", 0).edit().remove("review_nodb_" + email).apply();   // SQL могли только что применить
        new Thread(new Runnable() {
            @Override public void run() {
                int n = -1;
                try { n = check(a, email); } catch (Throwable ignore) {}
                final int fn = n;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { if (fn >= 0) IvaShopPatch.cabinetCount(a, fn); } catch (Throwable ignore) {}
                        Rev u = openRev == null ? null : openRev.get();
                        try { if (u != null && u.page.open()) reloadReview(u); } catch (Throwable ignore) {}
                        Runnable rf = sectionRefresh == null ? null : sectionRefresh.get();
                        try { if (rf != null) rf.run(); } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    // ───────────── 10.116: сотруднику отказали в публикации — причина словами (IvaPatch.publishError) ─────────────
    // Владелец 17.10.2026: у сотрудника «Опубликовать» → «Не опубликовано: нет прав на публикацию. Учётная запись а…»
    // (обрезано, и про арендодателя — не про сотрудника). Теперь коротко во всплывающей строке, а причина — окном:
    // база сама говорит, чего не хватает (20261107: iva_staff_publish_why), без SQL 10.116 — по iva_staff_catalog.

    static final String WHY_RPC = "/rest/v1/rpc/iva_staff_publish_why", CAT_RPC = "/rest/v1/rpc/iva_staff_catalog";
    static final String REFUSED_TITLE = "Не опубликовано";
    static final String WHY_NO_SQL = "В базе ещё нет обновления «сотрудник выкладывает инструмент». Владельцу: выполнить в Supabase "
        + "файлы 20261029_staff_orders.sql и 20261030_staff_tools.sql, затем 20261107_staff_publish_check.sql.";
    static final String WHY_NO_RIGHT = "База не видит у вас права «Выкладывает инструмент»: приглашение не принято, право выключено "
        + "или прокат владельца приостановлен. Владелец проверяет: «Кабинет» → «Настройка проката» → «Сотрудники».";
    static final String WHY_UNKNOWN = "Права сотрудника в базе есть, но запись не прошла. Точную причину покажет обновление базы — "
        + "владельцу: выполнить в Supabase 20261108_staff_publish_probe.sql (внизу будет причина по каждому сотруднику).";
    static volatile long whyAt;

    // 10.118 (снимок владельца 17.10.2026: «база не пустила запись», а 20261107 — «В базе всё в порядке»): 20261107 смотрит
    // только, есть ли право и пункт, а база отказывает на самой записи — правилами доступа после всех триггеров.
    // Теперь база делает пробную запись как из формы (всегда с откатом) и называет шаг: правило, триггер, владелец, пункт.
    static final String TRY_RPC = "/rest/v1/rpc/iva_staff_publish_try";
    static final int RAW_MAX = 240;

    /** Ответ iva_staff_publish_try: {"code","text","raw"} → фраза (+ ответ базы); "" — не разобрали (тогда как в 10.116). */
    static String tryOf(String body) {
        try {
            String b = body == null ? "" : body.trim();
            if (b.startsWith("[")) b = new org.json.JSONArray(b).optString(0, "");
            JSONObject o = new JSONObject(b);
            return tryText(str(o, "text"), str(o, "raw"));
        } catch (Throwable e) { return ""; }
    }

    /** Фраза пробы + ответ базы отдельной строкой (если его нет в фразе). */
    static String tryText(String t, String raw) {
        String x = t == null ? "" : t.trim(), r = raw == null ? "" : raw.trim();
        if (x.isEmpty()) return "";
        return r.isEmpty() || x.contains(r) ? x : x + "\n\n" + rawLine(r);
    }

    /** «Ответ базы: …» — коротко (для снимка владельцу). */
    static String rawLine(String raw) {
        String r = raw == null ? "" : raw.trim().replace('\n', ' ');
        if (r.length() > RAW_MAX) r = r.substring(0, RAW_MAX) + "…";
        return "Ответ базы: " + r;
    }

    /** Какой шаг не прошёл: 2 — запись инструмента (в ответе таблица tools), 1 — фото (хранилище), 0 — неизвестно. */
    static int refusalStep(String msg) {
        String m = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("\"tools\"") || m.contains("table tools")) return 2;
        if (m.contains("row-level security")) return 1;
        return 0;
    }

    /** Ответ iva_staff_publish_why: {"code","text"} → фраза ("" — не разобрали). */
    static String whyOf(String body) {
        try {
            String b = body == null ? "" : body.trim();
            if (b.startsWith("[")) b = new org.json.JSONArray(b).optString(0, "");
            JSONObject o = new JSONObject(b);
            return str(o, "text");
        } catch (Throwable e) { return ""; }
    }

    /** Без SQL 10.116: ответ iva_staff_catalog → фраза. */
    static String catWhy(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body.trim();
        if (NO_DB.equals(dbError(c, b))) return WHY_NO_SQL;
        if (c.startsWith("2")) return b.contains("true") ? WHY_UNKNOWN : WHY_NO_RIGHT;
        return c.isEmpty() ? "Не удалось узнать причину — нет связи с сервером." : "Не удалось узнать причину (ответ " + c + ").";
    }

    static String refusalText(int step, String why) {
        String head = step == 2 ? "Объявление не сохранилось: база не пустила запись." : step == 1 ? "Фото не загрузилось: хранилище не пустило."
            : "База не пустила публикацию.";
        return head + "\n\n" + (why == null || why.isEmpty() ? WHY_UNKNOWN : why);
    }

    /** 10.118: и что база ответила самой публикации (если этого ещё нет в причине). */
    static String refusalText(int step, String why, String msg) {
        String t = refusalText(step, why), m = msg == null ? "" : msg.trim();
        return m.isEmpty() || t.contains(m) || t.contains("Ответ базы:") ? t : t + "\n\n" + rawLine(m);
    }

    /** Из IvaPatch.publishError (главный поток): спросить базу в фоне и показать окно. */
    static void explainRefusal(final Activity a, final String msg) {
        long now = System.currentTimeMillis();
        if (a == null || a.isFinishing() || now - whyAt < 3000) return;
        whyAt = now;
        final int step = refusalStep(msg);
        new Thread(new Runnable() {
            @Override public void run() {
                String why = "";
                try {
                    String[] t = IvaClientPatch.apiCall(a, TRY_RPC, "POST", "{}", true);      // 10.118: проба записи
                    if (t != null && t[0].startsWith("2")) why = tryOf(t.length > 1 ? t[1] : "");
                    if (why.isEmpty()) {
                        String[] r = IvaClientPatch.apiCall(a, WHY_RPC, "POST", "{}", true);
                        if (r != null && r[0].startsWith("2")) why = whyOf(r.length > 1 ? r[1] : "");
                    }
                    if (why.isEmpty()) {
                        String[] c = IvaClientPatch.apiCall(a, CAT_RPC, "POST", "{}", true);
                        why = c == null ? catWhy("", "") : catWhy(c[0], c.length > 1 ? c[1] : "");
                    }
                } catch (Throwable e) { why = catWhy("", ""); }
                final String text = refusalText(step, why, msg);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { try { IvaAccessPatch.info(a, REFUSED_TITLE, text); } catch (Throwable ignore) {} }
                });
            }
        }).start();
    }

    /** Нажали на уведомление: кабинет → «На одобрении». */
    static void openFromNote(Activity a) {
        IvaNotifyPatch.tab(a, "profile");
        if (IvaNotifyPatch.isAdmin(a)) openReview(a, null);
    }
}

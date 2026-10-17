package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 10.103 — этап 3а «Сотрудники и пункты» (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
 *
 * Владелец: «Пункты выдачи» → у пункта «Сотрудники» → отдельная страница со списком (имя, почта, состояние, права) и
 * «Добавить»; сотрудник → своя страница: почта (вход), имя, телефон, права (галочки и наборы «Выдача» / «Каталог»),
 * «Может выдавать заявки самому себе», пункты (если их два и больше), «Пригласить» / «Сохранить», «Убрать из сотрудников».
 *
 * Сотрудник (вошёл под своей почтой, не владелец проката): в кабинете — «Приглашение в сотрудники» с «Принять» /
 * «Отказаться»; после «Принять» — строка «Мой пункт» → страница: пункт(ы), адрес, время работы, телефон пункта, права.
 * 10.104 (этап 3б): на странице «Мой пункт» — кнопка «Заявки пункта» (IvaStaffOrdersPatch). Инструмент и чат — этап 3в.
 *
 * Всё — функциями базы (SQL 20261028_staff.sql): писать в таблицы напрямую нельзя никому, база сама проверяет, что
 * пункт свой, человек — не владелец проката и не сотрудник другого. Без SQL — «заработает после обновления базы».
 * Новых вставок в smali нет: вызовы — из IvaPickupPatch (страница пунктов) и IvaNotifyPatch.cabinet (кабинет).
 */
public class IvaStaffPatch {
    static final String LIST_RPC = "/rest/v1/rpc/iva_staff_list", SAVE_RPC = "/rest/v1/rpc/iva_staff_save",
        REMOVE_RPC = "/rest/v1/rpc/iva_staff_remove", ME_RPC = "/rest/v1/rpc/iva_staff_me", ANSWER_RPC = "/rest/v1/rpc/iva_staff_answer";
    /** 10.115: сотрудник — в пункт / из пункта (SQL 20261106; без него — через iva_staff_save, как раньше). */
    static final String LINK_RPC = "/rest/v1/rpc/iva_staff_link";
    static final String ALL_SUB = "ФИО, телефон, почта, пункты и права";
    static final String ALL_HEAD = "Все сотрудники проката. Нажмите на карточку — изменить данные, права и пункты выдачи. "
        + "Клиенты имён и телефонов сотрудников не видят.";
    static final String NO_POINTS = "Сначала добавьте пункт выдачи: «Настройка проката» → «Пункты выдачи»";
    static final String UNLINK = "Убрать из пункта", NEW_STAFF = "Новый сотрудник";
    static final String RIGHTS_HINT = "Владелец проката изменил ваши права";
    static final String TITLE = "Сотрудники", BOX_TAG = "iva_staff_box";
    static final String NO_DB = "Сотрудники заработают после обновления базы (SQL 10.103)";
    static final String[] RIGHTS = {"Выдаёт и принимает", "Выкладывает инструмент", "Отвечает в чате", "Получает уведомления"};
    static final String[] RIGHTS_SUB = {"Отмечает «Выдал» и «Вернули» по заявкам своего пункта",
        "Добавляет инструмент в свой пункт", "Переписывается с клиентами своего пункта", "«Новая заявка» в своём пункте"};
    static final String P_ISSUE = "Выдача", P_CATALOG = "Каталог";
    static final String RIGHTS_LABEL = "Права — можно выбрать оба набора или отметить по одному";
    static final String SELF = "Может выдавать заявки самому себе",
        SELF_SUB = "Если сотрудник сам берёт инструмент в аренду. Выключено — «Выдал» и «Вернули» по его заявке отмечает другой";
    static final long ME_EVERY_MS = 60000L;
    static final int MAX = 50;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static final class Staff {
        String id = "", email = "", name = "", tel = "", status = "invited";
        boolean[] r = new boolean[4];
        boolean self;
        List<String> points = new ArrayList<String>();
    }

    /** Права набора: «Выдача» — выдаёт, чат, уведомления; «Каталог» — выкладывает инструмент. */
    static boolean[] preset(String name) {
        if (P_ISSUE.equals(name)) return new boolean[]{true, false, true, true};
        if (P_CATALOG.equals(name)) return new boolean[]{false, true, false, false};
        return new boolean[4];
    }

    /** Какой набор совпадает с отмеченными правами ("" — свой набор). */
    static String presetOf(boolean[] r) {
        if (r == null || r.length != 4) return "";
        for (String p : new String[]{P_ISSUE, P_CATALOG}) if (java.util.Arrays.equals(preset(p), r)) return p;
        return "";
    }

    /** 10.114: набор отмечен целиком — его кнопка выделена (обе кнопки, «Выдача» и «Каталог», могут быть выделены сразу). */
    static boolean presetOn(String name, boolean[] r) {
        boolean[] p = preset(name);
        if (r == null || r.length != 4) return false;
        boolean any = false;
        for (int i = 0; i < 4; i++) if (p[i]) { any = true; if (!r[i]) return false; }
        return any;
    }

    /** 10.114: нажали на набор — был отмечен: снять его права, нет: добавить; остальные права не трогаются. */
    static boolean[] togglePreset(String name, boolean[] r) {
        boolean[] out = r == null || r.length != 4 ? new boolean[4] : r.clone();
        boolean on = presetOn(name, out);
        boolean[] p = preset(name);
        for (int i = 0; i < 4; i++) if (p[i]) out[i] = !on;
        return out;
    }

    static String rightsText(boolean[] r) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; r != null && i < 4 && i < r.length; i++) if (r[i]) b.append(b.length() > 0 ? " · " : "").append(RIGHTS[i]);
        return b.length() == 0 ? "Без прав" : b.toString();
    }

    static String statusText(String st) {
        if ("active".equals(st)) return "Работает";
        if ("declined".equals(st)) return "Отказался";
        return "Ждёт ответа";
    }

    static String chipText(int n) { return n > 0 ? TITLE + " · " + n : TITLE; }

    /** Ошибка формы (как проверяет база) или "". */
    static String formErr(boolean isNew, String email, String me, String name, String tel, int points, boolean[] r) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (isNew) {
            if (e.isEmpty()) return "Введите почту, под которой сотрудник входит в приложение";
            if (!IvaPickupPatch.emailOk(e)) return "Похоже, в email ошибка (пример: ivan@mail.ru)";
            if (me != null && e.equals(me.trim().toLowerCase(Locale.ROOT))) return "Это ваша почта — владельца проката добавлять не нужно";
        }
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) return "Введите ФИО сотрудника";                 // 10.115: «Имя» → «ФИО» (решение владельца)
        if (n.length() > 80) return "ФИО — не длиннее 80 символов";
        String t = IvaPickupPatch.telErr(tel);
        if (!t.isEmpty()) return t;
        if (points < 1) return "Отметьте хотя бы один пункт выдачи";
        boolean any = false;
        for (int i = 0; r != null && i < r.length; i++) any |= r[i];
        if (!any) return "Отметьте хотя бы одно право";
        return "";
    }

    static String jsonStr(String s) { return IvaNotifyPatch.jsonStr(s); }

    /** Тело iva_staff_save: id — null (новый) или uuid; пункты — только uuid. */
    static String saveBody(String id, String email, String name, String tel, List<String> points, boolean[] r, boolean self) {
        StringBuilder ps = new StringBuilder();
        for (String p : points == null ? Collections.<String>emptyList() : points)
            if (IvaToolPointPatch.uuid(p)) ps.append(ps.length() > 0 ? "," : "").append(jsonStr(p.toLowerCase(Locale.ROOT)));
        boolean[] x = r != null && r.length == 4 ? r : new boolean[4];
        return "{\"p_id\":" + (IvaToolPointPatch.uuid(id) ? jsonStr(id.toLowerCase(Locale.ROOT)) : "null")
            + ",\"p_email\":" + jsonStr(email == null ? "" : email.trim().toLowerCase(Locale.ROOT))
            + ",\"p_name\":" + jsonStr(name == null ? "" : name.trim().replaceAll("\\s+", " "))
            + ",\"p_tel\":" + jsonStr(tel == null ? "" : tel.trim())
            + ",\"p_points\":[" + ps + "]"
            + ",\"p_issue\":" + x[0] + ",\"p_catalog\":" + x[1] + ",\"p_chat\":" + x[2] + ",\"p_notify\":" + x[3]
            + ",\"p_self\":" + self + "}";
    }

    static String idBody(String id) { return IvaToolPointPatch.uuid(id) ? "{\"p_id\":" + jsonStr(id.toLowerCase(Locale.ROOT)) + "}" : null; }

    static String answerBody(String id, boolean accept) {
        return IvaToolPointPatch.uuid(id) ? "{\"p_id\":" + jsonStr(id.toLowerCase(Locale.ROOT)) + ",\"p_accept\":" + accept + "}" : null;
    }

    /** Фразы базы (RAISE EXCEPTION в 20261028_staff.sql), которые показываем как есть. */
    static final String[] DB_PHRASES = {
        "Отметьте хотя бы один пункт выдачи", "Пункт выдачи не найден у вашего проката", "Отметьте хотя бы одно право",
        "Укажите имя сотрудника (до 80 символов)", "Проверьте телефон: 10–15 цифр", "Похоже, в email ошибка (пример: ivan@mail.ru)",
        "Это ваша почта — владельца проката добавлять не нужно", "Это владелец проката — его нельзя сделать сотрудником",
        "Этот человек уже сотрудник другого проката (или приглашён им)", "Не больше 50 сотрудников",
        "Сотрудник не найден — возможно, его уже убрали", "Приглашение не найдено — возможно, владелец его отменил",
        "Вы уже сотрудник — убрать вас может владелец проката", "Сотрудников добавляет владелец проката",
        "Сотрудников убирает владелец проката"};

    static String dbError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        for (String p : DB_PHRASES) if (b.contains(p)) return p;
        if (b.contains("iva_staff_one_owner")) return DB_PHRASES[8];
        if (b.contains("PGRST202") || b.contains("PGRST205") || b.contains("42P01") || "404".equals(c)) return NO_DB;
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Нет права — войдите заново";
        return c.isEmpty() ? "Не получилось — нет связи с сервером" : "Не получилось (ответ " + c + ")";
    }

    /** «Ива-Прокат» приглашает вас сотрудником пункта «Склад» / пунктов «Склад», «Бердск». */
    static String inviteText(String company, List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names == null ? Collections.<String>emptyList() : names)
            if (n != null && !n.trim().isEmpty()) b.append(b.length() > 0 ? ", " : "").append('«').append(n.trim()).append('»');
        String c = company == null || company.trim().isEmpty() ? "Прокат" : company.trim();
        if (b.length() == 0) return "«" + c + "» приглашает вас сотрудником";
        return "«" + c + "» приглашает вас сотрудником " + (names.size() > 1 ? "пунктов " : "пункта ") + b;
    }

    /** Подпись строки «Мой пункт»: «Склад · Ива-Прокат» / «2 пункта · Ива-Прокат». */
    static String mineSub(String company, List<String> names) {
        int n = names == null ? 0 : names.size();
        String c = company == null || company.trim().isEmpty() ? "Прокат" : company.trim();
        if (n == 0) return c;
        return (n == 1 ? names.get(0) : IvaPickupPatch.countText(n)) + " · " + c;
    }

    /** Сотрудники пункта (по порядку добавления). */
    static List<Staff> ofPoint(List<Staff> all, String pointId) {
        List<Staff> out = new ArrayList<Staff>();
        if (all == null || pointId == null) return out;
        for (Staff s : all) if (s.points.contains(pointId.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }

    /** Названия пунктов по id (порядок — как у владельца); кроме skip. */
    static List<String> pointNames(List<IvaPickupPatch.Point> all, List<String> ids, String skip) {
        List<String> out = new ArrayList<String>();
        if (all == null || ids == null) return out;
        for (IvaPickupPatch.Point p : all)
            if (ids.contains(p.id.toLowerCase(Locale.ROOT)) && !p.id.equalsIgnoreCase(skip == null ? "" : skip)) out.add(p.name);
        return out;
    }

    /** 10.115: тело iva_staff_link (сотрудник — в пункт / из пункта); не uuid — null. */
    static String linkBody(String id, String point, boolean on) {
        if (!IvaToolPointPatch.uuid(id) || !IvaToolPointPatch.uuid(point)) return null;
        return "{\"p_id\":" + jsonStr(id.toLowerCase(Locale.ROOT)) + ",\"p_point\":" + jsonStr(point.toLowerCase(Locale.ROOT))
            + ",\"p_on\":" + on + "}";
    }

    /** 10.115: пункты сотрудника после «в пункт» / «из пункта» (для запасного пути через iva_staff_save). */
    static List<String> linked(List<String> points, String point, boolean on) {
        List<String> out = new ArrayList<String>();
        String id = point == null ? "" : point.toLowerCase(Locale.ROOT);
        for (String p : points == null ? Collections.<String>emptyList() : points)
            if (!p.equalsIgnoreCase(id) && !out.contains(p.toLowerCase(Locale.ROOT))) out.add(p.toLowerCase(Locale.ROOT));
        if (on && !id.isEmpty()) out.add(id);
        return out;
    }

    /** 10.115: «Убрать из пункта» — это его последний пункт (тогда он больше не сотрудник). */
    static boolean lastPoint(List<String> points, String point) { return linked(points, point, false).isEmpty(); }

    static String unlinkText(String name, List<String> others, boolean last) {
        if (last) return "Это его единственный пункт — «" + name + "» перестанет быть сотрудником, доступ пропадёт сразу. "
            + "Пригласить снова можно в любой момент.";
        return "В других пунктах (" + join(others) + ") он останется, права — прежние.";
    }

    /** 10.115: «Пункт выдачи: Склад» / «Пункты выдачи: Склад, Бердск» (карточка на странице «Сотрудники»). */
    static String pointsLine(List<String> names) {
        if (names == null || names.isEmpty()) return "Пункт выдачи не выбран";
        return (names.size() == 1 ? "Пункт выдачи: " : "Пункты выдачи: ") + join(names);
    }

    /** «Склад», «Бердск» */
    static String quoted(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) b.append(b.length() > 0 ? ", " : "").append('«').append(n).append('»');
        return b.toString();
    }

    static String join(List<String> names) { return join(names, ", "); }

    static String join(List<String> names, String sep) {
        StringBuilder b = new StringBuilder();
        for (String n : names == null ? Collections.<String>emptyList() : names) b.append(b.length() > 0 ? sep : "").append(n);
        return b.toString();
    }

    /** 10.115: всё, что сотрудник видит о пункте (название, адрес, телефон, время, режим) — сравнить «было / стало». */
    static String pointKey(IvaPickupPatch.Point p) {
        return p.name + "|" + p.address + "|" + p.city + "|" + p.tel + "|" + p.publish + "|" + p.lat + "|" + p.lng
            + "|" + java.util.Arrays.toString(p.open) + "|" + java.util.Arrays.toString(p.close);
    }

    static boolean has(List<IvaPickupPatch.Point> ps, String id) {
        for (IvaPickupPatch.Point p : ps) if (p.id.equalsIgnoreCase(id)) return true;
        return false;
    }

    /** 10.115: пункты сотрудника поменялись (добавили / убрали). */
    static boolean pointsMoved(List<IvaPickupPatch.Point> was, List<IvaPickupPatch.Point> now) {
        List<IvaPickupPatch.Point> a = was == null ? Collections.<IvaPickupPatch.Point>emptyList() : was;
        List<IvaPickupPatch.Point> b = now == null ? Collections.<IvaPickupPatch.Point>emptyList() : now;
        for (IvaPickupPatch.Point p : a) if (!has(b, p.id)) return true;
        for (IvaPickupPatch.Point p : b) if (!has(a, p.id)) return true;
        return false;
    }

    /** 10.115: короткая подсказка сотруднику — что поменял владелец (пусто — ничего из видимого). */
    static String staffHint(boolean[] r1, boolean s1, List<IvaPickupPatch.Point> was, boolean[] r2, boolean s2, List<IvaPickupPatch.Point> now) {
        List<IvaPickupPatch.Point> a = was == null ? Collections.<IvaPickupPatch.Point>emptyList() : was;
        List<IvaPickupPatch.Point> b = now == null ? Collections.<IvaPickupPatch.Point>emptyList() : now;
        List<String> gone = new ArrayList<String>(), added = new ArrayList<String>(), changed = new ArrayList<String>();
        for (IvaPickupPatch.Point p : a) if (!has(b, p.id)) gone.add(p.name);
        for (IvaPickupPatch.Point p : b) {
            if (!has(a, p.id)) { added.add(p.name); continue; }
            for (IvaPickupPatch.Point o : a) if (o.id.equalsIgnoreCase(p.id) && !pointKey(o).equals(pointKey(p))) changed.add(p.name);
        }
        List<String> parts = new ArrayList<String>();
        if (!gone.isEmpty()) parts.add((gone.size() == 1 ? "Вас убрали из пункта " : "Вас убрали из пунктов ") + quoted(gone));
        if (!added.isEmpty()) parts.add((added.size() == 1 ? "Вас добавили в пункт " : "Вас добавили в пункты ") + quoted(added));
        if (s1 != s2 || !java.util.Arrays.equals(r1, r2)) parts.add(RIGHTS_HINT);
        if (!changed.isEmpty()) parts.add((changed.size() == 1 ? "Изменились данные пункта " : "Изменились данные пунктов ") + quoted(changed));
        return join(parts, ". ");
    }

    /** 10.115: подсказка владельцу — кто из сотрудников ответил на приглашение. */
    static String ownerHint(List<Staff> was, List<Staff> now) {
        if (was == null || now == null) return "";
        List<String> parts = new ArrayList<String>();
        for (Staff s : now) {
            String old = null;
            for (Staff o : was) if (o.id.equals(s.id)) old = o.status;
            if (old == null || old.equals(s.status)) continue;
            if ("active".equals(s.status)) parts.add("«" + s.name + "» принял приглашение");
            else if ("declined".equals(s.status)) parts.add("«" + s.name + "» отказался от приглашения");
        }
        return join(parts, ". ");
    }

    // ───────────── разбор ответов базы ─────────────

    static List<Staff> parseList(String body) throws Exception {
        List<Staff> out = new ArrayList<Staff>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Staff s = new Staff();
            s.id = o.optString("id", "");
            s.email = o.optString("email", "");
            s.name = o.optString("name", "");
            s.tel = o.isNull("tel") ? "" : o.optString("tel", "");
            s.status = o.optString("status", "invited");
            s.r = new boolean[]{o.optBoolean("can_issue"), o.optBoolean("can_catalog"), o.optBoolean("can_chat"), o.optBoolean("can_notify")};
            s.self = o.optBoolean("self_issue");
            JSONArray ps = o.optJSONArray("point_ids");
            for (int j = 0; ps != null && j < ps.length(); j++) s.points.add(ps.optString(j, "").toLowerCase(Locale.ROOT));
            out.add(s);
        }
        return out;
    }

    // ───────────── владелец: список (для «Сотрудники · N» на странице пунктов) ─────────────

    /** Последний список своих сотрудников; null — не загружали или нет SQL 10.103. */
    static volatile List<Staff> cache;
    static volatile boolean noDb;

    /** В фоне (поток страницы пунктов): перечитать список. */
    static void fetch(Activity a) {
        try {
            String[] r = IvaClientPatch.apiCall(a, LIST_RPC, "POST", "{}", true);
            if (r != null && r[0].startsWith("2")) { cache = parseList(r.length > 1 ? r[1] : ""); noDb = false; }
            else if (r != null && NO_DB.equals(dbError(r[0], r.length > 1 ? r[1] : ""))) { cache = null; noDb = true; }
        } catch (Throwable ignore) {}
    }

    /** Сколько сотрудников у пункта (0 — нет или не знаем). */
    static int count(String pointId) { List<Staff> c = cache; return c == null ? 0 : ofPoint(c, pointId).size(); }

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }

    static void toast(Activity a, String s) { try { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {} }

    // ───────────── владелец: страница «Сотрудники» пункта ─────────────

    static final class Ui {
        Activity a;
        IvaChiefPatch.Page page;
        LinearLayout items;
        TextView status;
        IvaPickupPatch.Point point;                 // null — 10.115: страница «Сотрудники» всего проката
        List<IvaPickupPatch.Point> all;
        Runnable changed;
        int cText, cGray, cOrange, cRed = 0xffff5a5f;
    }

    /** 10.115: открытые страницы «Сотрудники» — обновить, когда сотрудник ответил на приглашение. */
    static final List<java.lang.ref.WeakReference<Ui>> uis = new ArrayList<java.lang.ref.WeakReference<Ui>>();

    static Ui newUi(Activity a, IvaPickupPatch.Point p, List<IvaPickupPatch.Point> all, Runnable changed) {
        Ui u = new Ui();
        u.a = a;
        u.point = p;
        u.all = all == null ? new ArrayList<IvaPickupPatch.Point>() : new ArrayList<IvaPickupPatch.Point>(all);
        u.changed = changed;
        u.cText = color(a, "text", 0xfff2f4f8);
        u.cGray = color(a, "gray", 0xff9aa1b0);
        u.cOrange = color(a, "orange", 0xffff8f1f);
        synchronized (uis) {
            for (int i = uis.size() - 1; i >= 0; i--) { Ui x = uis.get(i).get(); if (x == null || x.page == null || !x.page.d.isShowing()) uis.remove(i); }
            uis.add(new java.lang.ref.WeakReference<Ui>(u));
        }
        return u;
    }

    static void body(final Ui u, View.OnClickListener onAdd) {
        Activity a = u.a;
        LinearLayout list = u.page.list;
        TextView add = IvaPickupPatch.addButton(a, u.cOrange);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(a, 4);
        list.addView(add, al);
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(dp(a, 2), dp(a, 12), 0, 0);
        list.addView(u.status, new LinearLayout.LayoutParams(-1, -2));
        u.items = new LinearLayout(a);
        u.items.setOrientation(LinearLayout.VERTICAL);
        list.addView(u.items, new LinearLayout.LayoutParams(-1, -2));
        add.setOnClickListener(onAdd);
    }

    /** Пункт → «Сотрудники»: «Добавить» (свой сотрудник или новый) и карточки с «Убрать из пункта»; карточки не нажимаются. */
    static void openPoint(final Activity a, IvaPickupPatch.Point p, List<IvaPickupPatch.Point> all, Runnable changed) {
        final Ui u = newUi(a, p, all, changed);
        u.page = IvaChiefPatch.page(a, TITLE, "Пункт «" + p.name + "». Сотрудник входит в приложение под своей почтой и сам "
            + "принимает приглашение. Данные и права — «Настройка проката» → «Сотрудники».", false);
        body(u, new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<Staff> c = cache;
                if (noDb) { toast(a, NO_DB); return; }
                openAdd(u, c == null ? new ArrayList<Staff>() : c);
            }
        });
        reload(u);
    }

    /** 10.115: «Настройка проката» → «Сотрудники»: «Добавить» сверху, ниже карточки всех сотрудников (нажать — изменить). */
    static void openAll(final Activity a) {
        final Ui u = newUi(a, null, null, null);
        u.page = IvaChiefPatch.page(a, TITLE, ALL_HEAD, false);
        body(u, new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<Staff> c = cache;
                if (noDb) { toast(a, NO_DB); return; }
                if (u.all.isEmpty()) { toast(a, NO_POINTS); return; }
                if (c != null && c.size() >= MAX) { toast(a, "Не больше " + MAX + " сотрудников"); return; }
                openStaff(u, null);
            }
        });
        reload(u);
    }

    /** 10.115: строка «Сотрудники» в «Настройка проката» (третья). */
    static void settingsRow(final Activity a, LinearLayout list, final int cText, final int cGray, final int cOrange) {
        float den = a.getResources().getDisplayMetrics().density;
        LinearLayout row = IvaShopPatch.row(a, list, new IvaShopPatch.Icon(IvaShopPatch.Icon.PEOPLE, cOrange, den), TITLE, ALL_SUB, cText, cGray);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openAll(a); } catch (Throwable ignore) {} }
        });
    }

    static void reload(final Ui u) {
        new Thread(new Runnable() {
            @Override public void run() {
                List<Staff> got = null;
                List<IvaPickupPatch.Point> ps = null;
                String err = "";
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, LIST_RPC, "POST", "{}", true);
                    if (r != null && r[0].startsWith("2")) got = parseList(r.length > 1 ? r[1] : "");
                    else err = dbError(r == null ? "" : r[0], r != null && r.length > 1 ? r[1] : "");
                    if (got != null && u.point == null) ps = IvaPickupPatch.load(u.a, new String[1]);   // 10.115: пункты проката
                } catch (Throwable e) { if (got == null) err = dbError("", ""); }
                final List<Staff> fs = got;
                final List<IvaPickupPatch.Point> fps = ps;
                final String ferr = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            noDb = NO_DB.equals(ferr);
                            if (fs == null) { u.status.setText(ferr); u.status.setTextColor(u.cOrange); return; }
                            cache = fs;
                            if (fps != null) u.all = fps;
                            draw(u, fs);
                            if (u.changed != null) u.changed.run();
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static TextView line(Activity a, String s, int color, int top) {
        TextView t = IvaChiefPatch.text(a, s, 13f, color, false);
        t.setPadding(0, dp(a, top), 0, 0);
        return t;
    }

    /** Карточка сотрудника: ФИО и состояние, телефон, почта, пункты, права. */
    static LinearLayout staffCard(Ui u, Staff s, boolean pointsLine) {
        Activity a = u.a;
        LinearLayout c = IvaChiefPatch.card(a);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(IvaChiefPatch.text(a, s.name, 16f, u.cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
        int sc = "active".equals(s.status) ? u.cText : "declined".equals(s.status) ? u.cGray : u.cOrange;
        top.addView(IvaChiefPatch.text(a, statusText(s.status), 12f, sc, "invited".equals(s.status)));
        c.addView(top);
        if (!s.tel.trim().isEmpty()) c.addView(line(a, s.tel.trim(), u.cText, 4));
        c.addView(line(a, s.email, u.cGray, 4));
        if (pointsLine) c.addView(line(a, pointsLine(pointNames(u.all, s.points, null)), u.cText, 6));
        else {
            List<String> more = pointNames(u.all, s.points, u.point.id);
            if (!more.isEmpty()) c.addView(line(a, "Ещё пункты: " + join(more), u.cGray, 4));
        }
        c.addView(line(a, rightsText(s.r) + (s.self ? " · сам себе — можно" : ""), u.cGray, 6));
        return c;
    }

    static void draw(final Ui u, List<Staff> all) {
        final Activity a = u.a;
        u.items.removeAllViews();
        final boolean whole = u.point == null;
        List<Staff> mine = whole ? all : ofPoint(all, u.point.id);
        u.status.setTextColor(u.cGray);
        if (whole && u.all.isEmpty()) u.status.setText(NO_POINTS);
        else u.status.setText(mine.isEmpty() ? "Сотрудников пока нет — нажмите «Добавить»" : "Сотрудников: " + mine.size());
        for (final Staff s : mine) {
            LinearLayout c = staffCard(u, s, whole);
            if (whole) {                                                  // 10.115: правка — только здесь
                c.setClickable(true);
                c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openStaff(u, s); } });
            } else {                                                      // 10.115: у пункта — только «Убрать из пункта»
                final TextView rm = IvaSearchPatch.chip(a, UNLINK, false, false);
                rm.setTextColor(u.cRed);
                rm.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = dp(a, 10);
                c.addView(rm, rl);
                rm.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { askUnlink(u, s, rm); } });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            u.items.addView(c, lp);
        }
    }

    // ───────────── 10.115: пункт → «Сотрудники»: в пункт / из пункта ─────────────

    static void askUnlink(final Ui u, final Staff s, final TextView btn) {
        final boolean last = lastPoint(s.points, u.point.id);
        IvaClientPatch.styledChoice(u.a, "Убрать «" + s.name + "» из пункта «" + u.point.name + "»?",
            unlinkText(s.name, pointNames(u.all, s.points, u.point.id), last), new String[]{"Убрать"},
            new IvaClientPatch.Choice() {
                @Override public void pick(int i) {
                    if (i == 0) link(u, s, false, btn, null, last ? "«" + s.name + "» больше не сотрудник"
                        : "«" + s.name + "» убран из пункта «" + u.point.name + "»");
                }
            });
    }

    /** «Добавить» у пункта: свои сотрудники, которых в пункте нет (нажать — прикрепить), внизу — «Новый сотрудник». */
    static void openAdd(final Ui u, List<Staff> all) {
        final Activity a = u.a;
        List<Staff> free = new ArrayList<Staff>();
        for (Staff s : all) if (!s.points.contains(u.point.id.toLowerCase(Locale.ROOT))) free.add(s);
        if (free.isEmpty()) {
            if (all.size() >= MAX) { toast(a, "Не больше " + MAX + " сотрудников"); return; }
            openStaff(u, null);                                          // своих нет — сразу новый
            return;
        }
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, "Добавить в пункт", "Пункт «" + u.point.name
            + "». Нажмите на сотрудника — он будет работать и в этом пункте, права прежние. Или пригласите нового.", false);
        for (final Staff s : free) {
            LinearLayout c = staffCard(u, s, true);
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(final View v) {
                    link(u, s, true, v, pg, "«" + s.name + "» теперь и в пункте «" + u.point.name + "»");
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            pg.list.addView(c, lp);
        }
        TextView nw = IvaPickupPatch.addButton(a, u.cOrange);
        nw.setText(NEW_STAFF);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = dp(a, 14);
        pg.list.addView(nw, nl);
        final int total = all.size();
        nw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (total >= MAX) { toast(a, "Не больше " + MAX + " сотрудников"); return; }
                try { pg.d.dismiss(); } catch (Throwable ignore) {}
                openStaff(u, null);
            }
        });
    }

    /** В пункт / из пункта: iva_staff_link; без SQL 10.115 — как раньше, через iva_staff_save (из последнего — iva_staff_remove). */
    static void link(final Ui u, final Staff s, final boolean on, final View btn, final IvaChiefPatch.Page pg, final String done) {
        final String body = linkBody(s.id, u.point.id, on);
        if (body == null) return;
        btn.setEnabled(false);
        btn.setAlpha(0.6f);
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, LINK_RPC, "POST", body, true);
                    e = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    if (NO_DB.equals(e)) {
                        List<String> ps = linked(s.points, u.point.id, on);
                        r = ps.isEmpty() ? IvaClientPatch.apiCall(u.a, REMOVE_RPC, "POST", idBody(s.id), true)
                            : IvaClientPatch.apiCall(u.a, SAVE_RPC, "POST", saveBody(s.id, s.email, s.name, s.tel, ps, s.r, s.self), true);
                        e = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                    }
                } catch (Throwable x) { e = dbError("", ""); }
                final String fe = e;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            btn.setEnabled(true);
                            btn.setAlpha(1f);
                            if (!fe.isEmpty()) { toast(u.a, fe); return; }
                            toast(u.a, done);
                            if (pg != null) try { pg.d.dismiss(); } catch (Throwable ignore) {}
                            reload(u);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    // ───────────── 10.115: владелец — сотрудник ответил на приглашение (Realtime) ─────────────

    static volatile boolean ownerBusy;

    /** Из IvaAccessPatch (фон, по отметке «роль поменялась»): список уже открывали — перечитать, подсказка, перерисовать. */
    static void ownerPoke(final Activity a) {
        final List<Staff> was = cache;
        if (was == null || ownerBusy) return;
        ownerBusy = true;
        try {
            String[] r = IvaClientPatch.apiCall(a, LIST_RPC, "POST", "{}", true);
            if (r == null || !r[0].startsWith("2")) return;
            final List<Staff> now = parseList(r.length > 1 ? r[1] : "");
            final String hint = ownerHint(was, now);
            cache = now;
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    List<Ui> open = new ArrayList<Ui>();
                    synchronized (uis) { for (java.lang.ref.WeakReference<Ui> w : uis) { Ui u = w.get(); if (u != null) open.add(u); } }
                    for (Ui u : open) {
                        try {
                            if (u.page == null || !u.page.d.isShowing() || u.items == null) continue;
                            draw(u, now);
                            if (u.changed != null) u.changed.run();
                        } catch (Throwable ignore) {}
                    }
                    if (!hint.isEmpty()) toast(a, hint);
                }
            });
        } catch (Throwable ignore) {
        } finally { ownerBusy = false; }
    }

    // ───────────── владелец: страница сотрудника (новый или правка) ─────────────

    static TextView label(Activity a, String s, int cGray) {
        TextView t = IvaChiefPatch.text(a, s, 13f, cGray, true);
        t.setPadding(dp(a, 2), dp(a, 14), 0, dp(a, 6));
        return t;
    }

    static EditText field(Activity a, String hint, String value, int cText) {
        EditText f = new EditText(a);
        f.setSingleLine(true);
        f.setHint(hint);
        f.setText(value);
        f.setTextColor(cText);
        f.setHintTextColor(color(a, "gray2", 0xff6b7080));
        f.setTextSize(15f);
        Drawable fb = IvaSearchPatch.drawable(a, "bg_field");
        if (fb != null) f.setBackground(fb);
        f.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        return f;
    }

    static Switch sw(Activity a, LinearLayout card, String title, String sub, boolean on, int cText, int cGray, int cOrange) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, 8), 0, dp(a, 8));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(IvaChiefPatch.text(a, title, 15f, cText, false));
        if (sub != null && !sub.isEmpty()) col.addView(IvaChiefPatch.text(a, sub, 12f, cGray, false));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch s = new Switch(a);
        s.setChecked(on);
        IvaNotifyPatch.tint(s, cOrange);
        row.addView(s, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { s.toggle(); } });
        card.addView(row);
        return s;
    }

    static void openStaff(final Ui u, final Staff old) {
        final Activity a = u.a;
        final boolean isNew = old == null;
        final Staff s = new Staff();
        if (isNew) {
            s.r = preset(P_ISSUE);
            if (u.point != null) s.points.add(u.point.id.toLowerCase(Locale.ROOT));
            else if (u.all.size() == 1) s.points.add(u.all.get(0).id.toLowerCase(Locale.ROOT));   // 10.115: пункт один — он и есть
        }
        else {
            s.id = old.id; s.email = old.email; s.name = old.name; s.tel = old.tel; s.status = old.status;
            s.r = old.r.clone(); s.self = old.self; s.points = new ArrayList<String>(old.points);
        }
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, isNew ? "Новый сотрудник" : old.name,
            isNew ? "Сотрудник увидит приглашение в приложении, когда войдёт под этой почтой: «Профиль» → «Принять»."
                  : old.email + " · " + statusText(old.status), false);
        try { pg.d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Throwable ignore) {}
        LinearLayout box = pg.list;
        final EditText fEmail;
        if (isNew) {
            box.addView(label(a, "Почта сотрудника (вход в приложение)", u.cGray));
            fEmail = field(a, "ivan@mail.ru", "", u.cText);
            fEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            box.addView(fEmail, new LinearLayout.LayoutParams(-1, -2));
        } else fEmail = null;
        box.addView(label(a, "ФИО", u.cGray));
        final EditText fName = field(a, "Например: Иванов Иван Иванович", s.name, u.cText);
        fName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(fName, new LinearLayout.LayoutParams(-1, -2));
        box.addView(label(a, "Телефон (необязательно, клиенты его не видят)", u.cGray));
        final EditText fTel = field(a, "+7 900 123-45-67", s.tel, u.cText);
        fTel.setInputType(InputType.TYPE_CLASS_PHONE);
        box.addView(fTel, new LinearLayout.LayoutParams(-1, -2));

        box.addView(label(a, RIGHTS_LABEL, u.cGray));
        final LinearLayout presets = new LinearLayout(a);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(presets, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout rc = IvaChiefPatch.card(a);
        box.addView(rc);
        final Switch[] rs = new Switch[4];
        final Runnable[] paint = new Runnable[1];
        for (int i = 0; i < 4; i++) {
            final int k = i;
            rs[i] = sw(a, rc, RIGHTS[i], RIGHTS_SUB[i], s.r[i], u.cText, u.cGray, u.cOrange);
            rs[i].setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) { s.r[k] = v; if (paint[0] != null) paint[0].run(); }
            });
        }
        paint[0] = new Runnable() {
            @Override public void run() {
                presets.removeAllViews();
                for (final String p : new String[]{P_ISSUE, P_CATALOG}) {
                    // 10.114: наборы складываются — можно выбрать оба («Выдача» и «Каталог»), повторное нажатие снимает
                    TextView ch = IvaSearchPatch.chip(a, p, presetOn(p, s.r), false);
                    ch.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            boolean[] x = togglePreset(p, s.r);
                            for (int i = 0; i < 4; i++) rs[i].setChecked(x[i]);   // слушатели сами перерисуют наборы
                        }
                    });
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
                    cl.rightMargin = dp(a, 8);
                    presets.addView(ch, cl);
                }
            }
        };
        paint[0].run();
        LinearLayout sc = IvaChiefPatch.card(a);
        box.addView(sc);
        final Switch fSelf = sw(a, sc, SELF, SELF_SUB, s.self, u.cText, u.cGray, u.cOrange);

        if (u.all.size() >= 2) {
            box.addView(label(a, "Пункты выдачи", u.cGray));
            final LinearLayout pts = new LinearLayout(a);
            pts.setOrientation(LinearLayout.VERTICAL);
            box.addView(pts, new LinearLayout.LayoutParams(-1, -2));
            final Runnable[] drawPts = new Runnable[1];
            drawPts[0] = new Runnable() {
                @Override public void run() {
                    pts.removeAllViews();
                    for (final IvaPickupPatch.Point p : u.all) {
                        final String id = p.id.toLowerCase(Locale.ROOT);
                        TextView ch = IvaSearchPatch.chip(a, IvaToolPointPatch.chipText(p), s.points.contains(id), false);
                        ch.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                if (s.points.contains(id)) s.points.remove(id); else s.points.add(id);
                                drawPts[0].run();
                            }
                        });
                        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
                        cl.topMargin = dp(a, 6);
                        pts.addView(ch, cl);
                    }
                }
            };
            drawPts[0].run();
        }

        final TextView err = IvaChiefPatch.text(a, "", 13f, u.cRed, false);
        err.setPadding(dp(a, 2), dp(a, 10), 0, 0);
        err.setVisibility(View.GONE);
        box.addView(err, new LinearLayout.LayoutParams(-1, -2));
        final TextView save = IvaPickupPatch.addButton(a, u.cOrange);
        save.setText(isNew ? "Пригласить" : "Сохранить");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(a, 14);
        box.addView(save, sl);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaPickupPatch.hideKb(a, v);
                String em = fEmail == null ? s.email : fEmail.getText().toString();
                String e = formErr(isNew, em, IvaPickupPatch.me(a), fName.getText().toString(), fTel.getText().toString(), s.points.size(), s.r);
                IvaPickupPatch.setErr(err, e);
                if (!e.isEmpty()) return;
                final String body = saveBody(isNew ? null : s.id, em, fName.getText().toString(), fTel.getText().toString(), s.points, s.r, fSelf.isChecked());
                final String who = fName.getText().toString().trim();
                write(u, pg, err, save, SAVE_RPC, body, isNew ? "Приглашение готово: пусть «" + who + "» войдёт в приложение под этой почтой" : "Сохранено");
            }
        });
        if (!isNew) {
            TextView rm = IvaSearchPatch.chip(a, "Убрать из сотрудников", false, false);
            rm.setTextColor(u.cRed);
            rm.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(a, 12);
            box.addView(rm, rl);
            rm.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    IvaClientPatch.styledChoice(a, "Убрать «" + s.name + "» из сотрудников?",
                        "Доступ к пунктам пропадёт сразу. Пригласить снова можно в любой момент.", new String[]{"Убрать"},
                        new IvaClientPatch.Choice() {
                            @Override public void pick(int i) { if (i == 0) write(u, pg, err, save, REMOVE_RPC, idBody(s.id), "«" + s.name + "» больше не сотрудник"); }
                        });
                }
            });
        }
    }

    static void write(final Ui u, final IvaChiefPatch.Page pg, final TextView err, final TextView btn, final String path,
                      final String body, final String done) {
        if (body == null) return;
        btn.setEnabled(false);
        btn.setAlpha(0.6f);
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, path, "POST", body, true);
                    e = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable x) { e = dbError("", ""); }
                final String fe = e;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            btn.setEnabled(true);
                            btn.setAlpha(1f);
                            if (!fe.isEmpty()) { IvaPickupPatch.setErr(err, fe); return; }
                            toast(u.a, done);
                            try { pg.d.dismiss(); } catch (Throwable ignore) {}
                            reload(u);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    // ───────────── сотрудник: приглашение и «Мой пункт» в кабинете ─────────────

    static final class Me {
        String id = "", status = "", company = "";
        boolean[] r = new boolean[4];
        boolean self;
        List<IvaPickupPatch.Point> points = new ArrayList<IvaPickupPatch.Point>();
    }

    static Me parseMe(String body) throws Exception {
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return null;
        JSONArray arr = new JSONArray(t);
        JSONObject o = arr.length() > 0 ? arr.optJSONObject(0) : null;
        if (o == null) return null;
        Me m = new Me();
        m.id = o.optString("id", "");
        m.status = o.optString("status", "");
        m.company = o.optString("company", "");
        m.r = new boolean[]{o.optBoolean("can_issue"), o.optBoolean("can_catalog"), o.optBoolean("can_chat"), o.optBoolean("can_notify")};
        m.self = o.optBoolean("self_issue");
        Object ps = o.opt("points");
        m.points = IvaPickupPatch.parse(ps == null ? "[]" : ps.toString());
        return IvaToolPointPatch.uuid(m.id) ? m : null;
    }

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_staff", 0); }
    static volatile long meAt;
    static volatile String meFor = "";
    static volatile boolean meBusy;

    /** Из IvaNotifyPatch.cabinet (смена раздела): в кабинете — приглашение / «Мой пункт»; владельцу и гостю — ничего. */
    static void onShowTab(final Activity a, String tab) {
        if (!"profile".equals(tab)) return;
        try { IvaLogoPatch.cabinet(a); } catch (Throwable ignore) {}       // 10.113: «Мои инструменты проката» у админа
        final String me = IvaPickupPatch.me(a);
        boolean want = IvaNotifyPatch.loggedIn(a) && !me.isEmpty() && !IvaNotifyPatch.isAdmin(a);
        if (!want) { drawMe(a, null); return; }
        Me cached = null;
        try { cached = parseMe(prefs(a).getString("me_" + me, "")); } catch (Throwable ignore) {}
        drawMe(a, cached);
        long now = System.currentTimeMillis();
        if (meBusy || (me.equals(meFor) && meAt > 0 && now - meAt < ME_EVERY_MS)) return;
        fetchMe(a, me);
    }

    static void fetchMe(final Activity a, final String me) {
        meBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, ME_RPC, "POST", "{}", true);
                    if (r != null && r[0].startsWith("2")) {
                        final String body = r.length > 1 && r[1] != null ? r[1] : "[]";
                        prefs(a).edit().putString("me_" + me, body).apply();
                        meAt = System.currentTimeMillis();
                        meFor = me;
                        final Me m = parseMe(body);
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() { try { if (me.equals(IvaPickupPatch.me(a))) drawMe(a, m); } catch (Throwable ignore) {} }
                        });
                    } else if (r != null && NO_DB.equals(dbError(r[0], r.length > 1 ? r[1] : ""))) {
                        meAt = System.currentTimeMillis();                       // нет SQL — не спрашиваем каждую секунду
                        meFor = me;
                    }
                } catch (Throwable ignore) {
                } finally { meBusy = false; }
            }
        }).start();
    }

    /** Блок над «Настройками» (или над «Инструкцией»): приглашение, «Мой пункт» или ничего. */
    static void drawMe(final Activity a, final Me m) {
        int id = a.getResources().getIdentifier("profHelp", "id", a.getPackageName());
        View help = id == 0 ? null : a.findViewById(id);
        if (help == null || !(help.getParent() instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) help.getParent();
        View old = box.findViewWithTag(BOX_TAG);
        if (m == null) { if (old != null) box.removeView(old); return; }
        LinearLayout holder;
        if (old instanceof LinearLayout) holder = (LinearLayout) old;
        else {
            holder = new LinearLayout(a);
            holder.setTag(BOX_TAG);
            holder.setOrientation(LinearLayout.VERTICAL);
            View settings = box.findViewWithTag(IvaNotifyPatch.ROW_TAG);
            int at = box.indexOfChild(settings != null && settings.getParent() == box ? settings : help);
            if (at < 0) return;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 12);
            box.addView(holder, at, lp);
        }
        holder.removeAllViews();
        if ("invited".equals(m.status)) holder.addView(inviteCard(a, m));
        else if ("active".equals(m.status)) holder.addView(mineRow(a, m));
        else box.removeView(holder);
    }

    static List<String> names(List<IvaPickupPatch.Point> ps) {
        List<String> out = new ArrayList<String>();
        for (IvaPickupPatch.Point p : ps) out.add(p.name);
        return out;
    }

    static View inviteCard(final Activity a, final Me m) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        LinearLayout c = IvaChiefPatch.card(a);
        c.setBackground(IvaPatch.accentBg(a, cOrange, 12));
        c.addView(IvaChiefPatch.text(a, "Приглашение в сотрудники", 16f, cOrange, true));
        TextView t = IvaChiefPatch.text(a, inviteText(m.company, names(m.points)), 14f, cText, false);
        t.setPadding(0, dp(a, 6), 0, 0);
        c.addView(t);
        TextView r = IvaChiefPatch.text(a, "Права: " + rightsText(m.r), 13f, cGray, false);
        r.setPadding(0, dp(a, 4), 0, 0);
        c.addView(r);
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(a, 12), 0, 0);
        final TextView yes = IvaPickupPatch.addButton(a, cOrange);
        yes.setText("Принять");
        final TextView no = IvaSearchPatch.chip(a, "Отказаться", false, false);
        no.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(0, -2, 1f);
        yl.rightMargin = dp(a, 8);
        btns.addView(yes, yl);
        btns.addView(no, new LinearLayout.LayoutParams(0, -1, 1f));
        c.addView(btns);
        yes.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { answer(a, m, true, yes); } });
        no.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaClientPatch.styledChoice(a, "Отказаться от приглашения?", "Прокат увидит, что вы отказались.", new String[]{"Отказаться"},
                    new IvaClientPatch.Choice() { @Override public void pick(int i) { if (i == 0) answer(a, m, false, no); } });
            }
        });
        return c;
    }

    static void answer(final Activity a, final Me m, final boolean accept, final TextView btn) {
        final String body = answerBody(m.id, accept);
        if (body == null) return;
        btn.setEnabled(false);
        btn.setAlpha(0.6f);
        final String me = IvaPickupPatch.me(a);
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                try {
                    String[] r = IvaClientPatch.apiCall(a, ANSWER_RPC, "POST", body, true);
                    e = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable x) { e = dbError("", ""); }
                final String fe = e;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            btn.setEnabled(true);
                            btn.setAlpha(1f);
                            if (!fe.isEmpty()) { toast(a, fe); }
                            else toast(a, accept ? "Готово: вы сотрудник «" + m.company + "»" : "Вы отказались от приглашения");
                            meAt = 0;
                            fetchMe(a, me);                                      // перечитать: «Мой пункт» или ничего
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static View mineRow(final Activity a, final Me m) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) row.setBackground(bg);
        row.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        row.setClickable(true);
        ImageView ic = new ImageView(a);
        ic.setImageDrawable(new IvaPickupPatch.Pin(cOrange, a.getResources().getDisplayMetrics().density));
        row.addView(ic, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(IvaChiefPatch.text(a, "Мой пункт", 15f, cText, false));
        col.addView(IvaChiefPatch.text(a, mineSub(m.company, names(m.points)), 13f, cGray, false));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 14);
        row.addView(col, cl);
        ImageView chev = new ImageView(a);
        Drawable ch = IvaSearchPatch.drawable(a, "ic_chevron");
        if (ch != null) { chev.setImageDrawable(ch); chev.setColorFilter(cGray); row.addView(chev, new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18))); }
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { openMine(a, m); } catch (Throwable ignore) {} } });
        return row;
    }

    static void openMine(Activity a, Me m) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        IvaChiefPatch.Page pg = IvaChiefPatch.page(a, "Мой пункт", "Вы сотрудник «" + m.company + "». Права меняет владелец проката.", false);
        IvaStaffToolsPatch.minePage = new java.lang.ref.WeakReference<android.app.Dialog>(pg.d);   // 10.105: закрыть перед штатной формой
        if (IvaStaffToolsPatch.want(m.status, m.r)) {                   // 10.105: инструменты пункта — добавить, править
            final Activity fa = a;
            final String co = m.company;
            TextView tl = IvaPickupPatch.addButton(a, color(a, "orange", 0xffff8f1f));
            tl.setText(IvaStaffToolsPatch.TITLE);
            tl.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { IvaStaffToolsPatch.open(fa, co); } catch (Throwable ignore) {} } });
            LinearLayout.LayoutParams tll = new LinearLayout.LayoutParams(-1, -2);
            tll.bottomMargin = dp(a, 10);
            pg.list.addView(tl, tll);
        }
        if (IvaStaffOrdersPatch.want(m.status, m.r)) {                   // 10.104: заявки пункта — «Выдал» / «Вернули»
            final Activity fa = a;
            final String co = m.company;
            TextView ord = IvaPickupPatch.addButton(a, color(a, "orange", 0xffff8f1f));
            ord.setText(IvaStaffOrdersPatch.TITLE);
            ord.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { IvaStaffOrdersPatch.open(fa, co); } catch (Throwable ignore) {} } });
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2);
            ol.bottomMargin = dp(a, 10);
            pg.list.addView(ord, ol);
        }
        if (IvaStaffChatPatch.want(m.status, m.r)) {                     // 10.106: чат пункта — ответы клиентам
            final Activity fa = a;
            final String co = m.company;
            TextView ch = IvaPickupPatch.addButton(a, color(a, "orange", 0xffff8f1f));
            ch.setText(IvaStaffChatPatch.TITLE);
            ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { IvaStaffChatPatch.open(fa, co); } catch (Throwable ignore) {} } });
            LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(-1, -2);
            chl.bottomMargin = dp(a, 10);
            pg.list.addView(ch, chl);
        }
        for (IvaPickupPatch.Point p : m.points) {
            LinearLayout c = IvaChiefPatch.card(a);
            c.addView(IvaChiefPatch.text(a, IvaToolPointPatch.chipText(p), 16f, cText, true));
            String ct = IvaToolPointPatch.cardText(p);
            int nl = ct.indexOf('\n');                                   // первая строка («Пункт выдачи: …») — уже в заголовке
            TextView t = IvaChiefPatch.text(a, nl >= 0 ? ct.substring(nl + 1) : "", 14f, cText, false);
            t.setPadding(0, dp(a, 6), 0, 0);
            c.addView(t);
            pg.list.addView(c);
        }
        LinearLayout r = IvaChiefPatch.card(a);
        r.addView(IvaChiefPatch.text(a, "Ваши права", 16f, cText, true));
        for (int i = 0; i < 4; i++) {
            TextView t = IvaChiefPatch.text(a, (m.r[i] ? "✓ " : "— ") + RIGHTS[i], 14f, m.r[i] ? cText : cGray, false);
            t.setPadding(0, dp(a, 6), 0, 0);
            r.addView(t);
        }
        TextView s = IvaChiefPatch.text(a, m.self ? "Свои заявки можете выдавать сами" : "Свои заявки выдаёт другой сотрудник", 13f, cGray, false);
        s.setPadding(0, dp(a, 8), 0, 0);
        r.addView(s);
        pg.list.addView(r);
        if (IvaStaffToolsPatch.want(m.status, m.r)) {                   // 10.113: «Вид раздела с логотипом» — в самом низу
            try { IvaLogoPatch.settingsRow(a, pg.list, cText, cGray, color(a, "orange", 0xffff8f1f)); } catch (Throwable ignore) {}
        }
    }
}

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
    static final String TITLE = "Сотрудники", BOX_TAG = "iva_staff_box";
    static final String NO_DB = "Сотрудники заработают после обновления базы (SQL 10.103)";
    static final String[] RIGHTS = {"Выдаёт и принимает", "Выкладывает инструмент", "Отвечает в чате", "Получает уведомления"};
    static final String[] RIGHTS_SUB = {"Отмечает «Выдал» и «Вернули» по заявкам своего пункта",
        "Добавляет инструмент в свой пункт", "Переписывается с клиентами своего пункта", "«Новая заявка» в своём пункте"};
    static final String P_ISSUE = "Выдача", P_CATALOG = "Каталог";
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
        if (n.isEmpty()) return "Введите имя сотрудника";
        if (n.length() > 80) return "Имя — не длиннее 80 символов";
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
        IvaPickupPatch.Point point;
        List<IvaPickupPatch.Point> all;
        Runnable changed;
        int cText, cGray, cOrange, cRed = 0xffff5a5f;
    }

    static void openPoint(final Activity a, IvaPickupPatch.Point p, List<IvaPickupPatch.Point> all, Runnable changed) {
        final Ui u = new Ui();
        u.a = a;
        u.point = p;
        u.all = all == null ? new ArrayList<IvaPickupPatch.Point>() : new ArrayList<IvaPickupPatch.Point>(all);
        u.changed = changed;
        u.cText = color(a, "text", 0xfff2f4f8);
        u.cGray = color(a, "gray", 0xff9aa1b0);
        u.cOrange = color(a, "orange", 0xffff8f1f);
        u.page = IvaChiefPatch.page(a, TITLE, "Пункт «" + p.name + "». Сотрудник входит в приложение под своей почтой и сам "
            + "принимает приглашение. Клиенты имён и телефонов сотрудников не видят.", false);
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
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<Staff> c = cache;
                if (noDb) { toast(a, NO_DB); return; }
                if (c != null && c.size() >= MAX) { toast(a, "Не больше " + MAX + " сотрудников"); return; }
                openStaff(u, null);
            }
        });
        reload(u);
    }

    static void reload(final Ui u) {
        new Thread(new Runnable() {
            @Override public void run() {
                List<Staff> got = null;
                String err = "";
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, LIST_RPC, "POST", "{}", true);
                    if (r != null && r[0].startsWith("2")) got = parseList(r.length > 1 ? r[1] : "");
                    else err = dbError(r == null ? "" : r[0], r != null && r.length > 1 ? r[1] : "");
                } catch (Throwable e) { err = dbError("", ""); }
                final List<Staff> fs = got;
                final String ferr = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            noDb = NO_DB.equals(ferr);
                            if (fs == null) { u.status.setText(ferr); u.status.setTextColor(u.cOrange); return; }
                            cache = fs;
                            draw(u, fs);
                            if (u.changed != null) u.changed.run();
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void draw(final Ui u, List<Staff> all) {
        final Activity a = u.a;
        u.items.removeAllViews();
        List<Staff> mine = ofPoint(all, u.point.id);
        u.status.setTextColor(u.cGray);
        u.status.setText(mine.isEmpty() ? "Сотрудников пока нет — нажмите «Добавить»" : "Сотрудников: " + mine.size());
        for (final Staff s : mine) {
            LinearLayout c = IvaChiefPatch.card(a);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(IvaChiefPatch.text(a, s.name, 16f, u.cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
            int sc = "active".equals(s.status) ? u.cText : "declined".equals(s.status) ? u.cGray : u.cOrange;
            top.addView(IvaChiefPatch.text(a, statusText(s.status), 12f, sc, "invited".equals(s.status)));
            c.addView(top);
            TextView em = IvaChiefPatch.text(a, s.email, 13f, u.cGray, false);
            em.setPadding(0, dp(a, 4), 0, 0);
            c.addView(em);
            if (!s.tel.trim().isEmpty()) {
                TextView tl = IvaChiefPatch.text(a, s.tel.trim(), 13f, u.cText, false);
                tl.setPadding(0, dp(a, 4), 0, 0);
                c.addView(tl);
            }
            TextView rt = IvaChiefPatch.text(a, rightsText(s.r) + (s.self ? " · сам себе — можно" : ""), 13f, u.cGray, false);
            rt.setPadding(0, dp(a, 6), 0, 0);
            c.addView(rt);
            List<String> more = pointNames(u.all, s.points, u.point.id);
            if (!more.isEmpty()) {
                TextView mo = IvaChiefPatch.text(a, "Ещё пункты: " + android.text.TextUtils.join(", ", more), 13f, u.cGray, false);
                mo.setPadding(0, dp(a, 4), 0, 0);
                c.addView(mo);
            }
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openStaff(u, s); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            u.items.addView(c, lp);
        }
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
        if (isNew) { s.r = preset(P_ISSUE); s.points.add(u.point.id.toLowerCase(Locale.ROOT)); }
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
        box.addView(label(a, "Имя", u.cGray));
        final EditText fName = field(a, "Например: Иван", s.name, u.cText);
        fName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        box.addView(fName, new LinearLayout.LayoutParams(-1, -2));
        box.addView(label(a, "Телефон (необязательно, клиенты его не видят)", u.cGray));
        final EditText fTel = field(a, "+7 900 123-45-67", s.tel, u.cText);
        fTel.setInputType(InputType.TYPE_CLASS_PHONE);
        box.addView(fTel, new LinearLayout.LayoutParams(-1, -2));

        box.addView(label(a, "Права", u.cGray));
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
                String cur = presetOf(s.r);
                for (final String p : new String[]{P_ISSUE, P_CATALOG}) {
                    TextView ch = IvaSearchPatch.chip(a, p, p.equals(cur), false);
                    ch.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            boolean[] x = preset(p);
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
    }
}

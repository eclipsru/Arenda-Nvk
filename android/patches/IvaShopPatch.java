package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 10.98 — «Настройка проката» (просьба владельца 16.10.2026; только приложение и только админы).
 *
 * Настройки → «Настройка проката» (только админам) → отдельная страница с тремя пунктами, каждый — своей страницей:
 *   • «Данные проката» — вверху аватарка проката (кругом; просмотр; из галереи с выбором области и размера круга),
 *     ниже поля с названиями НАД полями: «Название проката», «Адрес организации», «ИП организации»,
 *     «Номер телефона (для связи с начальником)», «Время работы» (с … до …), «Рабочие дни» (Пн…Вс);
 *   • «Пункты выдачи» (бывший «Пункт выдачи» 10.95, переименован) — перенесён сюда из «Настроек»;
 *   • «Срок аренды» (10.77) — перенесён сюда из «Настроек».
 *
 * Решение владельца (16.10): это те же данные, что были в «Мои данные», — оттуда их убрать. В «Мои данные» остаются
 * только «Фамилия», «Имя», «Отчество», «Номер телефона» и мессенджеры. Поэтому данные проката пишутся в те же поля
 * admins, что и раньше (их по-прежнему видят клиент в заявке и сайт):
 *   «Название проката» → admins.company, «Адрес организации» → admins.org_address,
 *   «Время работы» + «Рабочие дни» → admins.work_hours одной строкой, как раньше («Пн–Пт 09:00–18:00»);
 *   новое (SQL 20261024_rental_data.sql): «ИП организации» → org_ip, телефон → org_tel, дни → work_days,
 *   аватарка проката → org_avatar (файл — штатной загрузкой фото, как аватарка админа).
 * Без SQL работает всё, кроме новых полей (они показываются с подсказкой и не сохраняются).
 * «ФИО» в «Мои данные» — три поля; в admins.full_name уходит «Фамилия Имя Отчество» (как одно поле раньше).
 *
 * Новых вставок в smali нет: строка — из IvaNotifyPatch.openSettings, «Мои данные» — из IvaPatch.onTab("adminData").
 */
public class IvaShopPatch {
    static final String ROW_TITLE = "Настройка проката", ROW_SUB = "Данные проката, пункты выдачи, сотрудники, срок аренды";
    static final String DATA_TITLE = "Данные проката", DATA_SUB = "Название, адрес, ИП, телефон и время работы";
    static final String L_NAME = "Название проката", L_ADDR = "Адрес организации", L_IP = "ИП организации",
        L_TEL = "Номер телефона (для связи с начальником)", L_TIME = "Время работы", L_DAYS = "Рабочие дни";
    static final String NO_DB = "«ИП», телефон, рабочие дни и аватарка проката заработают после обновления базы (SQL 10.98)";
    static final String MOVED = "Название проката, адрес организации и время работы — в «Настройки → Настройка проката → Данные проката»";
    static final String COLS_OLD = "company,org_address,work_hours";
    static final String COLS_NEW = "org_ip,org_tel,work_days,org_avatar";
    static final int FROM_DEF = 9 * 60, TO_DEF = 18 * 60, ALL_DAYS = 127;
    static final String[] DAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    static final String TAG_FIO = "iva_shop_fio_10_98", TAG_TEL = "iva_shop_tel_10_98", TAG_NOTE = "iva_shop_note_10_98";

    /** Данные проката (как строка admins). from/to — минуты, -1 — не указано; days — биты Пн…Вс (0 — не указаны). */
    static final class Data {
        String name = "", addr = "", ip = "", tel = "", avatar = "", hoursRaw = "";
        int from = -1, to = -1, days = 0;
        boolean full;     // новые поля (SQL 10.98) есть в базе
    }

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static String clean(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }

    static String hm(int min) { return (min / 60 < 10 ? "0" : "") + (min / 60) + ":" + (min % 60 < 10 ? "0" : "") + (min % 60); }

    static final Pattern TIME = Pattern.compile("(\\d{1,2})[:.](\\d{2})\\s*(?:[-–—]|до)\\s*(\\d{1,2})[:.](\\d{2})");

    /** «8:00–20:00» где-то в тексте → {480, 1200}; нет / ошибка — null. Конец «24:00» — 1440. */
    static int[] parseTime(String s) {
        if (s == null) return null;
        Matcher m = TIME.matcher(s);
        if (!m.find()) return null;
        int h1 = Integer.parseInt(m.group(1)), m1 = Integer.parseInt(m.group(2));
        int h2 = Integer.parseInt(m.group(3)), m2 = Integer.parseInt(m.group(4));
        if (h1 > 23 || m1 > 59 || m2 > 59 || h2 > 24 || (h2 == 24 && m2 != 0)) return null;
        int f = h1 * 60 + m1, t = h2 * 60 + m2;
        return f < t ? new int[]{f, t} : null;
    }

    static final Pattern DAY = Pattern.compile("(?<![а-яё])(пн|вт|ср|чт|пт|сб|вс)(?![а-яё])(?:\\s*[-–—]\\s*(?<![а-яё])(пн|вт|ср|чт|пт|сб|вс)(?![а-яё]))?");

    static int dayIdx(String s) {
        for (int i = 0; i < 7; i++) if (DAYS[i].toLowerCase(java.util.Locale.ROOT).equals(s)) return i;
        return -1;
    }

    /** Дни из прежнего текста «Пн–Сб 8:00–20:00» → биты Пн…Вс; «ежедневно», «без выходных» — все; не нашли — 0. */
    static int daysFromText(String s) {
        String t = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        if (t.contains("ежедневно") || t.contains("без выходных") || t.contains("круглосуточно")) return ALL_DAYS;
        int mask = 0;
        Matcher m = DAY.matcher(t);
        while (m.find()) {
            int a = dayIdx(m.group(1));
            int b = m.group(2) == null ? a : dayIdx(m.group(2));
            if (a < 0 || b < 0) continue;
            for (int i = a; ; i = (i + 1) % 7) { mask |= 1 << i; if (i == b) break; }
        }
        return mask;
    }

    /** Биты Пн…Вс → «Пн–Пт», «Пн, Ср, Пт», «Сб, Вс», «Ежедневно»; 0 — "". */
    static String daysText(int mask) {
        mask &= ALL_DAYS;
        if (mask == 0) return "";
        if (mask == ALL_DAYS) return "Ежедневно";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < 7) {
            if ((mask & (1 << i)) == 0) { i++; continue; }
            int j = i;
            while (j + 1 < 7 && (mask & (1 << (j + 1))) != 0) j++;
            if (sb.length() > 0) sb.append(", ");
            if (j - i >= 2) sb.append(DAYS[i]).append("–").append(DAYS[j]);
            else if (j == i + 1) sb.append(DAYS[i]).append(", ").append(DAYS[j]);
            else sb.append(DAYS[i]);
            i = j + 1;
        }
        return sb.toString();
    }

    /** Строка для admins.work_hours (её видят клиент в заявке и сайт): «Пн–Пт 09:00–18:00». Время не выбрано — прежний текст. */
    static String hoursText(int days, int from, int to, String old) {
        if (from < 0 || to <= from) return old == null ? "" : old.trim();
        String d = daysText(days);
        String t = hm(from) + "–" + (to >= 1440 ? "24:00" : hm(to));
        return d.isEmpty() ? t : d + " " + t;
    }

    static String nameErr(String s) {
        String t = clean(s);
        if (t.length() < 2) return "Введите название проката";
        if (t.length() > 100) return "Название — не длиннее 100 символов";
        return "";
    }

    static String addrErr(String s) {
        String t = clean(s);
        if (!t.isEmpty() && t.length() < 5) return "Введите адрес полностью: город, улица, дом";
        if (t.length() > 300) return "Адрес — не длиннее 300 символов";
        return "";
    }

    static String ipErr(String s) {
        return clean(s).length() > 150 ? "Не длиннее 150 символов" : "";
    }

    /** Телефон необязателен; если указан — 10–15 цифр, только цифры, пробелы, + ( ) . - (как на сайте). */
    static String telErr(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return "";
        int digits = t.replaceAll("[^0-9]", "").length();
        if (!t.matches("\\+?[0-9() .-]+") || digits < 10 || digits > 15) return "Проверьте номер: 10–15 цифр, например +7 900 123-45-67";
        return "";
    }

    static String timeErr(int from, int to) {
        if (from < 0 && to < 0) return "";
        if (from < 0 || to < 0) return "Выберите и начало, и конец";
        return to > from ? "" : "Конец должен быть позже начала";
    }

    /** Строка admins → Data (Map — чтобы проверялось без org.json). */
    static Data fromRow(Map<String, String> r, boolean full) {
        Data d = new Data();
        d.full = full;
        d.name = val(r, "company");
        d.addr = val(r, "org_address");
        d.hoursRaw = val(r, "work_hours");
        int[] t = parseTime(d.hoursRaw);
        if (t != null) { d.from = t[0]; d.to = t[1]; }
        d.ip = val(r, "org_ip");
        d.tel = val(r, "org_tel");
        d.avatar = val(r, "org_avatar");
        int days = 0;
        try { days = Integer.parseInt(val(r, "work_days")); } catch (Throwable ignore) {}
        d.days = days > 0 && days <= ALL_DAYS ? days : daysFromText(d.hoursRaw);
        return d;
    }

    static String val(Map<String, String> r, String k) {
        String v = r == null ? null : r.get(k);
        return v == null || "null".equals(v) ? "" : v.trim();
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Тело PATCH admins: прежние поля всегда, новые — если они есть в базе (full). */
    static String body(Data d, boolean full) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"company\":").append(q(clean(d.name)));
        b.append(",\"org_address\":").append(q(clean(d.addr)));
        b.append(",\"work_hours\":").append(q(hoursText(d.days, d.from, d.to, d.hoursRaw)));
        if (full) {
            b.append(",\"org_ip\":").append(q(clean(d.ip)));
            b.append(",\"org_tel\":").append(q(d.tel == null ? "" : d.tel.trim()));
            b.append(",\"work_days\":").append(d.days & ALL_DAYS);
        }
        return b.append('}').toString();
    }

    /** Ответ «такой колонки нет» (SQL 10.98 не применён). */
    static boolean noColumn(String code, String body) {
        String b = body == null ? "" : body;
        return b.contains("42703") || b.contains("PGRST204") || (b.contains("column") && (b.contains("does not exist") || b.contains("Could not find")));
    }

    /** «Фамилия Имя Отчество» → три поля. Одно слово — имя; два — фамилия и имя; больше — остальное в отчество.
     * saved — как разложил сам админ (если из него получается та же строка — берём его). */
    static String[] splitFio(String full, String[] saved) {
        String t = clean(full);
        if (saved != null && saved.length == 3 && joinFio(saved[0], saved[1], saved[2]).equals(t)) return saved.clone();
        if (t.isEmpty()) return new String[]{"", "", ""};
        String[] w = t.split(" ");
        if (w.length == 1) return new String[]{"", w[0], ""};
        if (w.length == 2) return new String[]{w[0], w[1], ""};
        StringBuilder o = new StringBuilder();
        for (int i = 2; i < w.length; i++) { if (o.length() > 0) o.append(' '); o.append(w[i]); }
        return new String[]{w[0], w[1], o.toString()};
    }

    static String joinFio(String s, String n, String o) {
        return clean(clean(s) + " " + clean(n) + " " + clean(o));
    }

    // ───────────── строка в «Настройках» и страница «Настройка проката» ─────────────

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }

    static void toast(Activity a, String s) {
        try { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
    }

    /** Строка как у «Пункты выдачи» и «Срок аренды»: значок, название, пояснение, стрелка; нажатие — страница. */
    static LinearLayout row(Activity a, LinearLayout page, Drawable icon, String title, String sub, int cText, int cGray) {
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Drawable hbg = IvaOrderPatch.drawable(a, "bg_card");
        if (hbg != null) head.setBackground(hbg);
        int p16 = dp(a, 16), p14 = dp(a, 14);
        head.setPadding(p16, p14, p16, p14);
        head.setClickable(true);
        ImageView ic = new ImageView(a);
        ic.setImageDrawable(icon);
        head.addView(ic, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(cText);
        col.addView(t);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(13f);
        s.setTextColor(cGray);
        col.addView(s);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = p14;
        head.addView(col, cl);
        ImageView chev = new ImageView(a);
        Drawable chd = IvaOrderPatch.drawable(a, "ic_chevron");
        if (chd != null) { chev.setImageDrawable(chd); chev.setColorFilter(cGray); }
        head.addView(chev, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(a, 10);
        page.addView(head, hl);
        head.setContentDescription(title + ", открыть");
        return head;
    }

    /** «Настройки» → «Настройка проката» (только админам: openSettings зовёт при admin). */
    static void settingsRow(final Activity a, LinearLayout page, final int cText, final int cGray, final int cOrange) {
        float den = a.getResources().getDisplayMetrics().density;
        LinearLayout head = row(a, page, new Icon(Icon.SHOP, cOrange, den), ROW_TITLE, ROW_SUB, cText, cGray);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openPage(a, cText, cGray, cOrange); } catch (Throwable ignore) {} }
        });
    }

    /** Страница «Настройка проката»: «Данные проката», «Пункты выдачи», «Срок аренды» — каждый своей страницей. */
    static void openPage(final Activity a, final int cText, final int cGray, final int cOrange) {
        IvaChiefPatch.Page p = IvaChiefPatch.page(a, ROW_TITLE, null, false);
        LinearLayout list = p.list;
        float den = a.getResources().getDisplayMetrics().density;
        LinearLayout data = row(a, list, new Icon(Icon.CARD, cOrange, den), DATA_TITLE, DATA_SUB, cText, cGray);
        data.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openData(a); } catch (Throwable ignore) {} }
        });
        try { IvaPickupPatch.settingsRow(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}
        try { IvaStaffPatch.settingsRow(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}           // 10.115: «Сотрудники» — третьим
        try { IvaRentPatch.settingsSection(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}
        try { IvaStaffToolsPatch.settingsSection(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}   // 10.105
        try { IvaStaffJournalPatch.settingsRow(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}    // 10.107: «Сводка по пунктам»
        try { IvaLogoPatch.settingsRow(a, list, cText, cGray, cOrange); } catch (Throwable ignore) {}            // 10.113: в самом низу
    }

    /** Значки строк (цвет приложения, линией 2dp): витрина проката и карточка данных. */
    static final class Icon extends Drawable {
        static final int SHOP = 1, CARD = 2, PEOPLE = 3;
        final int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int kind, int color, float density) {
            this.kind = kind;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * density);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas c) {
            float l = getBounds().left, t = getBounds().top, w = getBounds().width(), h = getBounds().height();
            if (kind == SHOP) {
                Path roof = new Path();                         // козырёк витрины
                roof.moveTo(l + w * 0.12f, t + h * 0.38f);
                roof.lineTo(l + w * 0.22f, t + h * 0.14f);
                roof.lineTo(l + w * 0.78f, t + h * 0.14f);
                roof.lineTo(l + w * 0.88f, t + h * 0.38f);
                roof.close();
                c.drawPath(roof, p);
                c.drawRect(l + w * 0.18f, t + h * 0.38f, l + w * 0.82f, t + h * 0.86f, p);
                c.drawRect(l + w * 0.42f, t + h * 0.58f, l + w * 0.58f, t + h * 0.86f, p);   // дверь
            } else if (kind == PEOPLE) {                         // 10.115: два человека — «Сотрудники»
                c.drawCircle(l + w * 0.36f, t + h * 0.32f, w * 0.14f, p);
                c.drawArc(new RectF(l + w * 0.1f, t + h * 0.54f, l + w * 0.62f, t + h * 1.06f), 180f, 180f, false, p);
                c.drawCircle(l + w * 0.7f, t + h * 0.36f, w * 0.11f, p);
                c.drawArc(new RectF(l + w * 0.56f, t + h * 0.6f, l + w * 0.94f, t + h * 0.98f), 200f, 160f, false, p);
            } else {
                c.drawRoundRect(new RectF(l + w * 0.08f, t + h * 0.2f, l + w * 0.92f, t + h * 0.8f), w * 0.1f, w * 0.1f, p);
                c.drawCircle(l + w * 0.32f, t + h * 0.44f, w * 0.09f, p);
                c.drawLine(l + w * 0.2f, t + h * 0.66f, l + w * 0.44f, t + h * 0.66f, p);
                c.drawLine(l + w * 0.56f, t + h * 0.4f, l + w * 0.8f, t + h * 0.4f, p);
                c.drawLine(l + w * 0.56f, t + h * 0.56f, l + w * 0.76f, t + h * 0.56f, p);
            }
        }

        @Override public void setAlpha(int al) { p.setAlpha(al); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ───────────── страница «Данные проката» ─────────────

    static final class Ui {
        Activity a;
        IvaChiefPatch.Page page;
        Data d = new Data();
        View[] avatar;
        TextView avatarHint, status, from, to, noDb;
        EditText name, addr, ip, tel;
        TextView nameE, addrE, ipE, telE, timeE;
        TextView[] dayChips = new TextView[7];
        LinearLayout days;
        TextView save;
        boolean loaded, saving;
        int cText, cGray, cOrange, cRed = 0xffff5a5f;
    }

    static String me(Activity a) { return IvaAvatarPatch.myEmail(a); }

    static String rowPath(Activity a, String cols) { return "/rest/v1/admins?select=" + cols + "&email=eq." + IvaAvatarPatch.encode(me(a)); }

    static Map<String, String> toMap(JSONObject o) {
        Map<String, String> m = new HashMap<String, String>();
        if (o == null) return m;
        Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); m.put(k, o.isNull(k) ? null : String.valueOf(o.opt(k))); }
        return m;
    }

    /** Строка админа: сначала со всеми полями; нет новых колонок (SQL не применён) — только прежние. */
    static Data load(Activity a) throws Exception {
        String[] r = IvaClientPatch.apiCall(a, rowPath(a, COLS_OLD + "," + COLS_NEW), "GET", null, true);
        boolean full = true;
        if (r != null && !r[0].startsWith("2") && noColumn(r[0], r.length > 1 ? r[1] : "")) {
            full = false;
            r = IvaClientPatch.apiCall(a, rowPath(a, COLS_OLD), "GET", null, true);
        }
        if (r == null) throw new Exception("Не загрузилось — нет связи с сервером");
        if (!r[0].startsWith("2")) throw new Exception("Не загрузилось: " + IvaClientPatch.serverMessage(r[0], r.length > 1 ? r[1] : ""));
        JSONArray rows = new JSONArray(r.length > 1 && r[1] != null ? r[1] : "[]");
        if (rows.length() == 0) throw new Exception("Запись админа не найдена");
        return fromRow(toMap(rows.getJSONObject(0)), full);
    }

    static TextView label(Ui u, String s) {
        TextView t = IvaChiefPatch.text(u.a, s, 13f, u.cGray, true);
        t.setPadding(dp(u.a, 2), dp(u.a, 14), 0, dp(u.a, 6));
        return t;
    }

    static TextView err(Ui u) {
        TextView t = IvaChiefPatch.text(u.a, "", 12f, u.cRed, false);
        t.setPadding(dp(u.a, 2), dp(u.a, 4), 0, 0);
        t.setVisibility(View.GONE);
        return t;
    }

    static void setErr(TextView t, String s) { t.setText(s); t.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE); }

    static EditText field(Ui u, LinearLayout list, String lbl, String hint, int type) {
        list.addView(label(u, lbl));
        EditText f = new EditText(u.a);
        f.setSingleLine(true);
        f.setHint(hint);
        f.setInputType(type);
        f.setTextColor(u.cText);
        f.setHintTextColor(color(u.a, "gray2", 0xff6b7080));
        f.setTextSize(15f);
        Drawable fb = IvaSearchPatch.drawable(u.a, "bg_field");
        if (fb != null) f.setBackground(fb);
        f.setPadding(dp(u.a, 14), dp(u.a, 12), dp(u.a, 14), dp(u.a, 12));
        list.addView(f, new LinearLayout.LayoutParams(-1, -2));
        return f;
    }

    /** Время «с … / до …»: как поле, нажатие — выбор часов (тот же, что в «Пунктах выдачи»). */
    static TextView timeBox(Ui u) {
        TextView t = IvaChiefPatch.text(u.a, "", 15f, u.cText, false);
        Drawable fb = IvaSearchPatch.drawable(u.a, "bg_field");
        if (fb != null) t.setBackground(fb);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(u.a, 14), dp(u.a, 13), dp(u.a, 14), dp(u.a, 13));
        t.setClickable(true);
        return t;
    }

    static void openData(final Activity a) {
        final Ui u = new Ui();
        u.a = a;
        u.cText = color(a, "text", 0xfff2f4f8);
        u.cGray = color(a, "gray", 0xff9aa1b0);
        u.cOrange = color(a, "orange", 0xffff8f1f);
        u.page = IvaChiefPatch.page(a, DATA_TITLE, null, false);
        try { u.page.d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Throwable ignore) {}
        LinearLayout list = u.page.list;

        // 1. Аватарка проката — в самом верху
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(0, dp(a, 6), 0, dp(a, 4));
        u.avatar = IvaAvatarPatch.circleBox(a, IvaAvatarPatch.PAGE_DP, "+");
        u.avatar[0].setContentDescription("Аватарка проката");
        top.addView(u.avatar[0], new LinearLayout.LayoutParams(dp(a, IvaAvatarPatch.PAGE_DP), dp(a, IvaAvatarPatch.PAGE_DP)));
        u.avatarHint = IvaChiefPatch.text(a, "Аватарка проката · нажмите, чтобы добавить", 13f, u.cGray, false);
        u.avatarHint.setGravity(Gravity.CENTER);
        u.avatarHint.setPadding(0, dp(a, 8), 0, 0);
        top.addView(u.avatarHint, new LinearLayout.LayoutParams(-1, -2));
        list.addView(top, new LinearLayout.LayoutParams(-1, -2));
        u.avatar[0].setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { avatarTap(u); } catch (Throwable ignore) {} }
        });

        u.noDb = IvaChiefPatch.text(a, NO_DB, 12f, u.cOrange, false);
        u.noDb.setPadding(dp(a, 2), dp(a, 10), 0, 0);
        u.noDb.setVisibility(View.GONE);
        list.addView(u.noDb);

        // 2. Поля — название над каждым
        u.name = field(u, list, L_NAME, "Например: Прокат-НВ", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        list.addView(u.nameE = err(u));
        u.addr = field(u, list, L_ADDR, "Город, улица, дом", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final LinearLayout sug = new LinearLayout(a);
        sug.setOrientation(LinearLayout.VERTICAL);
        sug.setVisibility(View.GONE);
        Drawable sb = IvaSearchPatch.drawable(a, "bg_field");
        if (sb != null) sug.setBackground(sb);
        LinearLayout.LayoutParams sgl = new LinearLayout.LayoutParams(-1, -2);
        sgl.topMargin = dp(a, 4);
        list.addView(sug, sgl);
        list.addView(u.addrE = err(u));
        try {   // подсказки адреса — те же, что в «Пунктах выдачи» (Photon / OpenStreetMap, как на сайте)
            IvaPickupPatch.Ui pu = new IvaPickupPatch.Ui();
            pu.a = a; pu.cText = u.cText; pu.cGray = u.cGray; pu.cOrange = u.cOrange;
            IvaPickupPatch.hookSuggest(pu, u.addr, sug, new IvaPickupPatch.Point());
        } catch (Throwable ignore) {}
        u.ip = field(u, list, L_IP, "Например: ИП Иванов Иван Иванович", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        list.addView(u.ipE = err(u));
        u.tel = field(u, list, L_TEL, "+7 900 123-45-67", InputType.TYPE_CLASS_PHONE);
        list.addView(u.telE = err(u));

        // Время работы: с … до …
        list.addView(label(u, L_TIME));
        LinearLayout times = new LinearLayout(a);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);
        u.from = timeBox(u);
        u.to = timeBox(u);
        times.addView(u.from, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView dash = IvaChiefPatch.text(a, "—", 15f, u.cGray, false);
        dash.setPadding(dp(a, 10), 0, dp(a, 10), 0);
        times.addView(dash);
        times.addView(u.to, new LinearLayout.LayoutParams(0, -2, 1f));
        list.addView(times, new LinearLayout.LayoutParams(-1, -2));
        list.addView(u.timeE = err(u));
        u.from.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaPickupPatch.time(a, "Начало работы", u.d.from >= 0 ? u.d.from : FROM_DEF, new IvaPickupPatch.TimeSet() {
                    @Override public void set(int m) { u.d.from = m; if (u.d.to < 0) u.d.to = Math.min(1439, Math.max(m + 60, TO_DEF)); drawTimes(u); }
                });
            }
        });
        u.to.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaPickupPatch.time(a, "Конец работы", u.d.to >= 0 && u.d.to < 1440 ? u.d.to : TO_DEF, new IvaPickupPatch.TimeSet() {
                    @Override public void set(int m) { u.d.to = m; if (u.d.from < 0) u.d.from = Math.min(FROM_DEF, Math.max(0, m - 60)); drawTimes(u); }
                });
            }
        });

        // Рабочие дни: Пн…Вс
        list.addView(label(u, L_DAYS));
        u.days = new LinearLayout(a);
        u.days.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 7; i++) {
            final int di = i;
            TextView c = IvaSearchPatch.chip(a, DAYS[i], false, false);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, 0, 0, 0);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { u.d.days ^= 1 << di; drawDays(u); }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(a, 40), 1f);
            if (i > 0) cl.leftMargin = dp(a, 5);
            u.days.addView(c, cl);
            u.dayChips[i] = c;
        }
        list.addView(u.days, new LinearLayout.LayoutParams(-1, -2));

        // Сохранить
        u.save = new TextView(a);
        u.save.setText("Сохранить");
        u.save.setTextSize(15f);
        IvaPatch.accentButton(a, u.save);                  // 10.96: «рамкой»
        u.save.setGravity(Gravity.CENTER);
        u.save.setPadding(dp(a, 12), dp(a, 14), dp(a, 12), dp(a, 14));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, -2);
        svl.topMargin = dp(a, 22);
        list.addView(u.save, svl);
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(dp(a, 2), dp(a, 10), 0, 0);
        list.addView(u.status);
        u.save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { save(u); } catch (Throwable ignore) {} }
        });
        drawTimes(u);
        drawDays(u);
        setEnabled(u, false);

        new Thread(new Runnable() {
            @Override public void run() {
                Data d = null;
                String e = "";
                try { d = load(a); } catch (Throwable t) { e = IvaAvatarPatch.errText(t); }
                final Data fd = d;
                final String fe = e;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!u.page.open()) return;
                        if (fd == null) { u.status.setText(fe); u.status.setTextColor(u.cOrange); return; }
                        u.d = fd;
                        u.loaded = true;
                        fill(u);
                        setEnabled(u, true);
                        u.status.setText("");
                        loadAvatar(u);
                    }
                });
            }
        }).start();
    }

    static void setEnabled(Ui u, boolean on) {
        for (View v : new View[]{u.name, u.addr, u.ip, u.tel, u.from, u.to, u.save}) if (v != null) v.setEnabled(on);
        boolean extra = on && u.d.full;
        u.ip.setEnabled(extra);
        u.tel.setEnabled(extra);
        for (TextView c : u.dayChips) if (c != null) c.setEnabled(extra);
        u.save.setAlpha(on ? 1f : 0.5f);
    }

    static void fill(Ui u) {
        u.name.setText(u.d.name);
        u.addr.setText(u.d.addr);
        u.ip.setText(u.d.ip);
        u.tel.setText(u.d.tel);
        u.noDb.setVisibility(u.d.full ? View.GONE : View.VISIBLE);
        if (u.d.from < 0 && !u.d.hoursRaw.isEmpty()) {
            u.timeE.setTextColor(u.cGray);
            setErr(u.timeE, "Сейчас указано: «" + u.d.hoursRaw + "». Выберите время — строка обновится");
        }
        drawTimes(u);
        drawDays(u);
    }

    static void drawTimes(Ui u) {
        u.from.setText(u.d.from >= 0 ? "с " + hm(u.d.from) : "с — выбрать");
        u.from.setTextColor(u.d.from >= 0 ? u.cText : color(u.a, "gray2", 0xff6b7080));
        u.to.setText(u.d.to >= 0 ? "до " + (u.d.to >= 1440 ? "24:00" : hm(u.d.to)) : "до — выбрать");
        u.to.setTextColor(u.d.to >= 0 ? u.cText : color(u.a, "gray2", 0xff6b7080));
    }

    static void drawDays(Ui u) {
        for (int i = 0; i < 7; i++) {
            TextView c = u.dayChips[i];
            if (c == null) continue;
            boolean on = (u.d.days & (1 << i)) != 0;
            Drawable b = IvaSearchPatch.drawable(u.a, on ? "bg_chip_on" : "bg_chip");
            if (b != null) c.setBackground(b);
            else c.setBackground(on ? IvaPatch.accentBg(u.a, u.cOrange, 20) : IvaPatch.outlineShape(u.a.getResources().getDisplayMetrics().density,
                color(u.a, "card", 0xff1a1d24), 0xff3a4050, 20));
            c.setTextColor(on ? u.cOrange : u.cText);   // 10.96: выбранный — рамкой и цветом рамки
            c.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            c.setContentDescription(IvaNotifyPatch.DAYS_FULL[i] + (on ? ": рабочий" : ": выходной"));
        }
    }

    static void save(final Ui u) {
        if (!u.loaded || u.saving) return;
        final Activity a = u.a;
        u.d.name = String.valueOf(u.name.getText());
        u.d.addr = String.valueOf(u.addr.getText());
        u.d.ip = String.valueOf(u.ip.getText());
        u.d.tel = String.valueOf(u.tel.getText()).trim();
        String ne = nameErr(u.d.name), ae = addrErr(u.d.addr), ie = u.d.full ? ipErr(u.d.ip) : "", te = u.d.full ? telErr(u.d.tel) : "";
        if (ne.isEmpty()) try { ne = IvaCensorPatch.fieldError(u.d.name); } catch (Throwable ignore) {}
        if (ae.isEmpty() && !clean(u.d.addr).isEmpty()) try { ae = IvaCensorPatch.fieldError(u.d.addr); } catch (Throwable ignore) {}
        if (ie.isEmpty() && !clean(u.d.ip).isEmpty()) try { ie = IvaCensorPatch.fieldError(u.d.ip); } catch (Throwable ignore) {}
        String tm = timeErr(u.d.from, u.d.to);
        u.timeE.setTextColor(u.cRed);
        setErr(u.nameE, ne); setErr(u.addrE, ae); setErr(u.ipE, ie); setErr(u.telE, te);
        if (!tm.isEmpty() || u.d.from >= 0) setErr(u.timeE, tm);
        if (!ne.isEmpty() || !ae.isEmpty() || !ie.isEmpty() || !te.isEmpty() || !tm.isEmpty()) {
            u.status.setText("Проверьте поля, отмеченные красным");
            u.status.setTextColor(u.cRed);
            return;
        }
        IvaPickupPatch.hideKb(a, u.name);
        u.saving = true;
        u.save.setText("Сохраняем…");
        u.status.setText("");
        final String body = body(u.d, u.d.full);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = "";
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/admins?email=eq." + IvaAvatarPatch.encode(me(a)), "PATCH", body, true);
                    if (r == null) err = "нет ответа сервера";
                    else if (!r[0].startsWith("2")) err = IvaClientPatch.serverMessage(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable e) { err = IvaAvatarPatch.errText(e); }
                final String ferr = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        u.saving = false;
                        u.save.setText("Сохранить");
                        if (!ferr.isEmpty()) {
                            u.status.setText("Не сохранено: " + ferr);
                            u.status.setTextColor(u.cRed);
                            return;
                        }
                        u.d.hoursRaw = hoursText(u.d.days, u.d.from, u.d.to, u.d.hoursRaw);
                        syncNative(a, u.d);
                        u.status.setText("Сохранено");
                        u.status.setTextColor(u.cGray);
                        toast(a, "Данные проката сохранены");
                    }
                });
            }
        }).start();
    }

    /** Скрытые поля штатных «Мои данные» — те же значения, чтобы их «Сохранить» не вернуло прежние. */
    static void syncNative(Activity a, Data d) {
        try {
            Object c = IvaClientPatch.get(a, "adCompany"), o = IvaClientPatch.get(a, "adOrgAddr"), h = IvaClientPatch.get(a, "adHours");
            if (c instanceof EditText) ((EditText) c).setText(clean(d.name));
            if (o instanceof EditText) ((EditText) o).setText(clean(d.addr));
            if (h instanceof EditText) ((EditText) h).setText(d.hoursRaw);
            Object p = IvaClientPatch.get(a, "admProfile");
            if (p instanceof JSONObject) {
                ((JSONObject) p).put("company", clean(d.name));
                ((JSONObject) p).put("org_address", clean(d.addr));
                ((JSONObject) p).put("work_hours", d.hoursRaw);
            }
        } catch (Throwable ignore) {}
    }

    // ── аватарка проката ──

    static void loadAvatar(final Ui u) {
        final String url = u.d.avatar;
        if (!IvaAvatarPatch.isUrl(url)) { showAvatar(u, null); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try { b = IvaAvatarPatch.urlBitmap(url, dp(u.a, IvaAvatarPatch.PAGE_DP) * 2); } catch (Throwable ignore) {}
                final Bitmap fb = b;
                u.a.runOnUiThread(new Runnable() { @Override public void run() { if (u.page.open() && url.equals(u.d.avatar)) showAvatar(u, fb); } });
            }
        }).start();
    }

    static void showAvatar(Ui u, Bitmap b) {
        IvaAvatarPatch.putCircle(u.avatar, b, dp(u.a, IvaAvatarPatch.PAGE_DP));
        u.avatarHint.setText(b == null ? "Аватарка проката · нажмите, чтобы добавить" : "Аватарка проката · нажмите, чтобы посмотреть или изменить");
    }

    static void avatarTap(final Ui u) {
        if (!u.loaded) return;
        if (!u.d.full) { toast(u.a, NO_DB); return; }
        final Bitmap b = IvaAvatarPatch.photoOf((ImageView) u.avatar[1]);
        Runnable edit = new Runnable() { @Override public void run() { editAvatar(u); } };
        if (b == null) { edit.run(); return; }
        final String url = u.d.avatar;
        final IvaAvatarPatch.Loader hi = new IvaAvatarPatch.Loader() {
            @Override public Bitmap load() throws Exception { return IvaAvatarPatch.urlBitmap(url, IvaAvatarPatch.VIEW_PX); }
        };
        // 10.109: «Редактировать» — та же аватарка проката: область и поворот; «Выбрать другое фото» — галерея
        IvaAvatarPatch.viewer(u.a, b, hi, new Runnable() {
            @Override public void run() { IvaAvatarPatch.edit(u.a, IvaAvatarPatch.slot("shop", me(u.a)), b, hi, avatarDone(u)); }
        }, edit);
    }

    /** Галерея → область кругом → файл (штатная загрузка фото) → admins.org_avatar. Сохраняется сразу. */
    static void editAvatar(final Ui u) {
        boolean ok = IvaAvatarPatch.pick(u.a, avatarDone(u));
        if (!ok) toast(u.a, "Не удалось открыть выбор фото");
    }

    /** Готовый квадрат аватарки проката → файл → admins.org_avatar. */
    static IvaAvatarPatch.Done avatarDone(final Ui u) {
        final Activity a = u.a;
        return new IvaAvatarPatch.Done() {
            @Override public void done(byte[] jpeg, final Bitmap preview) {
                if (!u.page.open()) return;
                u.avatarHint.setText("Сохраняем аватарку…");
                final byte[] fj = jpeg;
                new Thread(new Runnable() {
                    @Override public void run() {
                        String err = "", url = "";
                        try {
                            url = IvaAvatarPatch.upload(a, fj);
                            String[] r = IvaClientPatch.apiCall(a, "/rest/v1/admins?email=eq." + IvaAvatarPatch.encode(me(a)), "PATCH",
                                "{\"org_avatar\":" + q(url) + "}", true);
                            if (r == null) err = "нет ответа сервера";
                            else if (!r[0].startsWith("2")) err = noColumn(r[0], r.length > 1 ? r[1] : "") ? NO_DB : IvaClientPatch.serverMessage(r[0], r.length > 1 ? r[1] : "");
                        } catch (Throwable e) { err = IvaAvatarPatch.errText(e); }
                        final String ferr = err, furl = url;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (!ferr.isEmpty()) {
                                    showAvatar(u, IvaAvatarPatch.photoOf((ImageView) u.avatar[1]));
                                    toast(a, "Аватарка не сохранена: " + ferr);
                                    return;
                                }
                                u.d.avatar = furl;
                                showAvatar(u, preview);
                                IvaAvatarPatch.saved(a, IvaAvatarPatch.slot("shop", me(a)));   // 10.109: исходник — для «Редактировать»
                                toast(a, "Аватарка проката сохранена");
                            }
                        });
                    }
                }).start();
            }
        };
    }

    // ───────────── «Мои данные»: только Фамилия, Имя, Отчество, Номер телефона и мессенджеры ─────────────

    /** IvaPatch.onTab — после штатного переключения раздела (и штатного loadAdminData). */
    static void onTab(Activity a, String tab) {
        if ("adminData".equals(tab)) myData(a);
    }

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_shop", 0); }

    static String[] savedFio(Activity a) {
        String s = prefs(a).getString("fio_" + me(a).toLowerCase(java.util.Locale.ROOT), null);
        if (s == null) return null;
        String[] p = s.split("\u0001", -1);
        return p.length == 3 ? p : null;
    }

    static void rememberFio(Activity a, String s, String n, String o) {
        prefs(a).edit().putString("fio_" + me(a).toLowerCase(java.util.Locale.ROOT), clean(s) + "\u0001" + clean(n) + "\u0001" + clean(o)).apply();
    }

    static void myData(final Activity a) {
        Object fioRaw = IvaClientPatch.get(a, "adFio"), telRaw = IvaClientPatch.get(a, "adPhone");
        if (!(fioRaw instanceof EditText) || !(((EditText) fioRaw).getParent() instanceof ViewGroup)) return;
        final EditText fio = (EditText) fioRaw;
        final ViewGroup parent = (ViewGroup) fio.getParent();
        // перенесено в «Данные проката» (значения остаются в скрытых полях — штатное «Сохранить» их не стирает)
        for (String f : new String[]{"adCompany", "adOrgAddr", "adAddr", "adHours"}) {
            Object v = IvaClientPatch.get(a, f);
            if (v instanceof View) {
                ((View) v).setVisibility(View.GONE);
                hideSuggest(parent, (View) v);
            }
        }
        final int cGray = color(a, "gray", 0xff9aa1b0);
        if (parent.findViewWithTag(TAG_FIO) == null) {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setTag(TAG_FIO);
            final EditText[] f = new EditText[3];
            String[] labels = {"Фамилия", "Имя", "Отчество"};
            for (int i = 0; i < 3; i++) {
                TextView l = IvaChiefPatch.text(a, labels[i], 13f, cGray, true);
                l.setPadding(dp(a, 2), dp(a, 14), 0, dp(a, 6));
                box.addView(l);
                f[i] = copyField(a, fio, labels[i]);
                box.addView(f[i], new LinearLayout.LayoutParams(-1, -2));
            }
            parent.addView(box, parent.indexOfChild(fio), new LinearLayout.LayoutParams(-1, -2));
            fio.setVisibility(View.GONE);
            final boolean[] quiet = new boolean[1];
            final Runnable fromNative = new Runnable() {
                @Override public void run() {
                    String[] p = splitFio(String.valueOf(fio.getText()), savedFio(a));
                    quiet[0] = true;
                    for (int i = 0; i < 3; i++) if (!p[i].equals(String.valueOf(f[i].getText()))) f[i].setText(p[i]);
                    quiet[0] = false;
                }
            };
            TextWatcher toNative = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (quiet[0]) return;
                    String sn = String.valueOf(f[0].getText()), nn = String.valueOf(f[1].getText()), on = String.valueOf(f[2].getText());
                    rememberFio(a, sn, nn, on);
                    quiet[0] = true;
                    fio.setText(joinFio(sn, nn, on));       // штатное «Сохранить» берёт ФИО отсюда
                    quiet[0] = false;
                }
            };
            for (EditText e : f) e.addTextChangedListener(toNative);
            fio.addTextChangedListener(new TextWatcher() {    // штатный loadAdminData пишет ФИО позже (из сети)
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { if (!quiet[0]) fromNative.run(); }
            });
            fromNative.run();
        }
        if (telRaw instanceof EditText && parent.findViewWithTag(TAG_TEL) == null) {
            View tel = (View) telRaw;
            TextView l = IvaChiefPatch.text(a, "Номер телефона", 13f, cGray, true);
            l.setTag(TAG_TEL);
            l.setPadding(dp(a, 2), dp(a, 14), 0, dp(a, 6));
            parent.addView(l, parent.indexOfChild(tel));
            if (tel.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {   // отступ теперь у подписи
                ((ViewGroup.MarginLayoutParams) tel.getLayoutParams()).topMargin = 0;
                tel.requestLayout();
            }
        }
        // куда переехали данные проката — серая строка под телефоном
        Object anchor = telRaw instanceof View ? telRaw : fio;
        if (parent.findViewWithTag(TAG_NOTE) == null && anchor instanceof View) {
            TextView note = IvaChiefPatch.text(a, MOVED, 12f, color(a, "gray2", 0xff6b7080), false);
            note.setTag(TAG_NOTE);
            note.setPadding(dp(a, 2), dp(a, 8), 0, 0);
            parent.addView(note, parent.indexOfChild((View) anchor) + 1, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** Список подсказок адреса сразу под скрытым полем — тоже скрыть. */
    static void hideSuggest(ViewGroup parent, View field) {
        View next = parent.getChildAt(parent.indexOfChild(field) + 1);
        if (next instanceof LinearLayout && !(next instanceof EditText) && ((LinearLayout) next).getOrientation() == LinearLayout.VERTICAL
            && next.getTag() == null) next.setVisibility(View.GONE);
    }

    /** Поле в том же виде, что штатное «ФИО» (фон, отступы, цвета, отступ сверху). */
    static EditText copyField(Activity a, EditText like, String hint) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        e.setTextSize(15f);
        e.setTextColor(like.getCurrentTextColor());
        if (like.getHintTextColors() != null) e.setHintTextColor(like.getHintTextColors());
        Drawable bg = like.getBackground();
        if (bg != null && bg.getConstantState() != null) e.setBackground(bg.getConstantState().newDrawable().mutate());
        e.setPadding(like.getPaddingLeft(), like.getPaddingTop(), like.getPaddingRight(), like.getPaddingBottom());
        return e;
    }
}

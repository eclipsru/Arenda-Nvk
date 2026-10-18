package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 10.95 — «Пункт выдачи» в настройках админа (просьба владельца 16.10.2026).
 *
 * Настройки → «Пункт выдачи» (только админам) → отдельная страница. Вверху — «Добавить» (тёмная, оранжевая рамка и
 * текст); нажатие — сразу под ней форма целиком (решение владельца): «Название», «Адрес» (полный, с подсказками —
 * тот же Photon / OpenStreetMap, что на сайте), «График» (часы открытия и закрытия на каждый день недели или выходной),
 * «Email проката» (пустое поле, проверяется). Пунктов может быть несколько (до 20): у каждого «Изменить» и «Удалить».
 * 10.113: поля «Email проката» больше нет (решение владельца); вместо него — выключатель «Инструменты сотрудников»
 * этого пункта (сразу / после одобрения владельца), он же строкой на карточке пункта.
 *
 * Решение владельца: «Срок аренды → До закрытия пункта» берёт время закрытия из графика ПЕРВОГО пункта — это делает
 * база (SQL 20261023_pickup_points.sql); в «Сроке аренды» дни тогда только показываются (меняются здесь).
 *
 * Хранится в базе public.iva_pickup_points: видит и меняет только сам админ (создатель видит все). Клиентам пункты
 * пока не показываются. Новых вставок в smali нет: строка — из IvaNotifyPatch.openSettings.
 */
public class IvaPickupPatch {
    static final String TABLE = "/rest/v1/iva_pickup_points";
    static final String COLS = "id,name,address,city,lat,lng,open_min,close_min,email,created_at";
    static final String COLS_TEL = COLS + ",tel";       // 10.99: телефон пункта (SQL 20261025_pickup_tel.sql)
    static final String L_TEL = "Телефон пункта выдачи";
    static final String NO_TEL = "Телефон пункта заработает после обновления базы (SQL 10.99)";
    static final long ANIM_MS = 280;
    /** В базе есть колонка tel (последняя загрузка). Без SQL 10.99 — пункты без телефона, как в 10.95. */
    static volatile boolean telDb = true;
    static final String PHOTON = "https://photon.komoot.io/api/";
    static final String ROW_TITLE = "Пункты выдачи", ROW_SUB = "Адрес, график, телефон и сотрудники";
    // 10.113 (просьба владельца 17.10.2026): «Инструменты сотрудников» — у каждого пункта своим выключателем
    // (SQL 20261104_point_staff_publish.sql); поля «Email проката» в форме больше нет.
    static final String PUB_TITLE = "Инструменты сотрудников — после моего одобрения";
    static final String PUB_SUB = "Включено: новый инструмент и правку уже опубликованного, которые сделает сотрудник этого пункта, "
        + "клиенты увидят только после вашего одобрения («Настройка проката» → «На одобрении»); до этого — прежний вариант. "
        + "Выключено: сразу, без одобрения.";
    static final String NO_PUB = "Выключатель «Инструменты сотрудников» заработает после обновления базы (SQL 10.113)";
    /** В базе есть колонка staff_publish (последняя загрузка). Без SQL 10.113 — как в 10.112 (email вписываем свой). */
    static volatile boolean pubDb = true;
    static final String PAGE_SUB = "Где вы выдаёте инструмент. По графику первого пункта считается «Срок аренды → До закрытия пункта».";
    static final String ADD = "Добавить";
    static final String NO_DB = "Заработает после обновления базы (SQL 10.95)";
    static final String POINT_BUSY = "В этом пункте есть инструмент — сначала перенесите его в другой пункт (правка инструмента → «Пункт выдачи»)";
    static final int MAX = 20, OPEN_DEF = 9 * 60, CLOSE_DEF = 19 * 60;
    static final long SUGGEST_DELAY_MS = 450;

    /** Пункт выдачи (как строка базы). open/close — минуты Пн…Вс; выходной — -1 и -1. */
    static final class Point {
        String id = "", name = "", address = "", city = "", email = "", tel = "";
        String publish = "approve";       // 10.113: «now» — инструменты сотрудников сразу, иначе после одобрения
        Double lat, lng;
        int[] open = fill(OPEN_DEF), close = fill(CLOSE_DEF);
    }

    static int[] fill(int v) { int[] x = new int[7]; java.util.Arrays.fill(x, v); return x; }

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static boolean emailOk(String s) {
        String e = s == null ? "" : s.trim();
        return e.length() <= 254 && e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}");
    }

    static String nameErr(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return "Введите название пункта";
        if (t.length() > 80) return "Название — не длиннее 80 символов";
        return "";
    }

    static String addrErr(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (t.length() < 5) return "Введите полный адрес: город, улица, дом";
        if (t.length() > 300) return "Адрес — не длиннее 300 символов";
        return "";
    }

    /** Телефон пункта необязателен; если указан — 10–15 цифр (та же проверка, что в «Данных проката» и в базе). */
    static String telErr(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() > 30) return "Проверьте номер: 10–15 цифр, например +7 900 123-45-67";
        return IvaShopPatch.telErr(t);
    }

    static String telLine(String tel) {
        String t = tel == null ? "" : tel.trim();
        return "Телефон: " + (t.isEmpty() ? "не указан" : t);
    }

    /** 10.113: строка на карточке пункта. */
    static String publishLine(String mode) {
        return "Инструменты сотрудников: " + ("now".equals(mode) ? "сразу, без одобрения" : "после вашего одобрения");
    }

    static String publishOf(String v) { return "now".equals(v == null ? "" : v.trim()) ? "now" : "approve"; }

    static String emailErr(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return "Введите email проката";
        return emailOk(t) ? "" : "Похоже, в email ошибка (пример: prokat@mail.ru)";
    }

    /** Как проверка базы: 7 дней; выходной — -1/-1; рабочий — 0 ≤ открытие < закрытие ≤ 23:59; хотя бы один рабочий. */
    static boolean hoursOk(int[] o, int[] c) {
        if (o == null || c == null || o.length != 7 || c.length != 7) return false;
        boolean any = false;
        for (int i = 0; i < 7; i++) {
            if (o[i] == -1 && c[i] == -1) continue;
            if (o[i] < 0 || o[i] >= c[i] || c[i] > 1439) return false;
            any = true;
        }
        return any;
    }

    /** «[540,…]» / «{540,…}» → 7 чисел; что-то не так — null. */
    static int[] arr(String s) {
        String t = s == null ? "" : s.replaceAll("[\\[\\]{}\\s]", "");
        String[] xs = t.split(",");
        if (xs.length != 7) return null;
        int[] out = new int[7];
        try {
            for (int i = 0; i < 7; i++) {
                out[i] = Integer.parseInt(xs[i]);
                if (out[i] < -1 || out[i] > 1439) return null;
            }
        } catch (Throwable e) { return null; }
        return out;
    }

    static String arrJson(int[] x) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 7; i++) { if (i > 0) b.append(','); b.append(x[i]); }
        return b.append(']').toString();
    }

    static String dayText(int o, int c) { return o < 0 ? "выходной" : IvaNotifyPatch.hm(o) + "–" + IvaNotifyPatch.hm(c); }

    /** «Пн–Пт 09:00–19:00 · Сб 10:00–16:00 · Вс выходной» — подряд идущие одинаковые дни вместе. */
    static String hoursText(int[] o, int[] c) {
        if (o == null || c == null || o.length != 7 || c.length != 7) return "";
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < 7) {
            int j = i;
            while (j + 1 < 7 && o[j + 1] == o[i] && c[j + 1] == c[i]) j++;
            if (b.length() > 0) b.append(" · ");
            b.append(IvaNotifyPatch.DAYS[i]);
            if (j > i) b.append(j == i + 1 ? ", " : "–").append(IvaNotifyPatch.DAYS[j]);
            b.append(' ').append(dayText(o[i], c[i]));
            i = j + 1;
        }
        return b.toString();
    }

    static String countText(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? "пункт" : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "пункта" : "пунктов";
        return n + " " + w;
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Throwable e) { return ""; }
    }

    static String listPath(String email) { return listPath(email, false); }

    static String listPath(String email, boolean tel) { return listPath(email, tel, false); }

    /** 10.113: + staff_publish (выключатель «Инструменты сотрудников» у пункта). */
    static String listPath(String email, boolean tel, boolean pub) {
        return TABLE + "?select=" + (tel ? COLS_TEL : COLS) + (pub ? ",staff_publish" : "") + "&owner_email=eq." + enc(email == null ? "" : email.trim().toLowerCase(Locale.ROOT))
            + "&order=created_at.asc,id.asc";
    }

    static String idPath(String id) { return TABLE + "?id=eq." + enc(id); }

    /** Тело записи (только разрешённые столбцы; владелец — сам вход, ставит база). */
    static String body(Point p) {
        return "{\"name\":" + IvaNotifyPatch.jsonStr(p.name.trim().replaceAll("\\s+", " "))
            + ",\"address\":" + IvaNotifyPatch.jsonStr(p.address.trim().replaceAll("\\s+", " "))
            + ",\"city\":" + IvaNotifyPatch.jsonStr(p.city == null ? "" : p.city.trim())
            + ",\"lat\":" + (p.lat == null || p.lng == null ? "null" : String.valueOf(p.lat))
            + ",\"lng\":" + (p.lat == null || p.lng == null ? "null" : String.valueOf(p.lng))
            + ",\"open_min\":" + arrJson(p.open) + ",\"close_min\":" + arrJson(p.close)
            + ",\"email\":" + IvaNotifyPatch.jsonStr(p.email.trim().toLowerCase(Locale.ROOT)) + "}";
    }

    /** 10.99: то же + телефон пункта (если колонка есть в базе). */
    static String body(Point p, boolean tel) {
        String b = body(p);
        if (!tel) return b;
        return b.substring(0, b.length() - 1) + ",\"tel\":" + IvaNotifyPatch.jsonStr(p.tel == null ? "" : p.tel.trim()) + "}";
    }

    /** 10.113: запись с выключателем и без email (поле убрано). Без SQL 10.113 — прежняя запись с email. */
    static String body(Point p, boolean tel, boolean pub) {
        if (!pub) return body(p, tel);
        return "{\"name\":" + IvaNotifyPatch.jsonStr(p.name.trim().replaceAll("\\s+", " "))
            + ",\"address\":" + IvaNotifyPatch.jsonStr(p.address.trim().replaceAll("\\s+", " "))
            + ",\"city\":" + IvaNotifyPatch.jsonStr(p.city == null ? "" : p.city.trim())
            + ",\"lat\":" + (p.lat == null || p.lng == null ? "null" : String.valueOf(p.lat))
            + ",\"lng\":" + (p.lat == null || p.lng == null ? "null" : String.valueOf(p.lng))
            + ",\"open_min\":" + arrJson(p.open) + ",\"close_min\":" + arrJson(p.close)
            + (tel ? ",\"tel\":" + IvaNotifyPatch.jsonStr(p.tel == null ? "" : p.tel.trim()) : "")
            + ",\"staff_publish\":\"" + publishOf(p.publish) + "\"}";
    }

    /** 10.113: ответ «колонки staff_publish нет» — SQL 10.113 не применён. */
    static boolean noPub(String code, String body) {
        String b = body == null ? "" : body;
        return b.contains("staff_publish") && (b.contains("42703") || b.contains("PGRST204") || b.contains("does not exist") || b.contains("Could not find"));
    }

    /** Ответ «такой колонки нет» — SQL 10.99 не применён. */
    static boolean noTel(String code, String body) {
        String b = body == null ? "" : body;
        return b.contains("tel") && (b.contains("42703") || b.contains("PGRST204") || b.contains("does not exist") || b.contains("Could not find"));
    }

    /** Ответ базы — понятной фразой; успех — "". */
    static String dbError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        if (b.contains("Не больше 20")) return "Не больше 20 пунктов выдачи";
        if (b.contains("tools_point_fk")) return POINT_BUSY;                // 10.101: за пунктом есть инструмент
        if (noPub(c, b)) return NO_PUB;
        if (noTel(c, b)) return NO_TEL;
        if (b.contains("iva_pickup_tel")) return "Проверьте телефон: 10–15 цифр";
        if (b.contains("PGRST205") || b.contains("42P01") || "404".equals(c)) return NO_DB;
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Нет права: пункты добавляет действующий админ";
        if (b.contains("23514")) return "База не приняла данные — проверьте поля";
        return c.isEmpty() ? "Не сохранилось — нет связи с сервером" : "Не сохранилось (ответ " + c + ")";
    }

    static String photonUrl(String q) {
        String t = q == null ? "" : q.trim().replaceAll("\\s+", " ");
        if (t.length() > 200) t = t.substring(0, 200);
        return PHOTON + "?q=" + enc(t) + "&lang=default&countrycode=RU&limit=8";
    }

    static boolean ru(String s) { return s != null && s.matches("(?s).*[А-Яа-яЁё].*") && !s.matches("(?s).*[A-Za-z].*"); }

    static String clean(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }

    /** Населённый пункт без «город» / «г.» / «пос.»… */
    static String locality(String s) {
        String t = clean(s).replaceAll("^(?iu)(город\\s+|г[.\\s]+|пос[её]лок\\s+|пос[.\\s]+|пгт[.\\s]+|село\\s+|деревня\\s+)", "").trim();
        return ru(t) && t.length() <= 100 ? t : "";
    }

    /** Подсказка из свойств Photon (как IvaGeo.feature у сайта, режим «адрес»): {адрес, город, регион} или null. */
    static String[] addrOf(Map<String, String> p) {
        if (p == null || !"RU".equalsIgnoreCase(clean(p.get("countrycode")))) return null;
        String key = clean(p.get("osm_key")), value = clean(p.get("osm_value")), type = clean(p.get("type"));
        boolean place = "place".equals(key) && ("city".equals(value) || "town".equals(value) || "village".equals(value)
            || "hamlet".equals(value) || "isolated_dwelling".equals(value));
        boolean isCity = "city".equals(type) || place;
        String raw = isCity ? p.get("name") : firstOf(p.get("city"), p.get("town"), p.get("village"), p.get("locality"));
        String loc = locality(raw);
        if (loc.isEmpty()) return null;
        String region = ru(clean(p.get("state"))) ? clean(p.get("state")) : "";
        String street = clean(firstOf(p.get("street"), ("street".equals(type) || "highway".equals(key)) ? p.get("name") : ""));
        if (!street.isEmpty() && !ru(street)) return null;
        String num = clean(p.get("housenumber"));
        List<String> parts = new ArrayList<String>();
        parts.add(loc);
        if (!street.isEmpty()) parts.add(street);
        if (!num.isEmpty()) parts.add("д. " + num);
        String nm = clean(p.get("name"));
        if (street.isEmpty() && num.isEmpty() && !isCity && !nm.isEmpty() && !nm.equals(loc)) {
            if (!ru(nm)) return null;
            parts.add(nm);
        }
        StringBuilder b = new StringBuilder();
        for (String x : parts) { if (b.length() > 0) b.append(", "); b.append(x); }
        return new String[]{b.toString(), loc, region};
    }

    static String firstOf(String... xs) {
        for (String x : xs) if (x != null && !x.trim().isEmpty()) return x;
        return "";
    }

    // ───────────── запоминание (для строки в настройках и «Срока аренды») ─────────────

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_pickup", 0); }

    static String me(Activity a) {
        Object e = IvaClientPatch.get(a, "userEmail");
        return e instanceof String ? ((String) e).trim().toLowerCase(Locale.ROOT) : "";
    }

    static void remember(Activity a, List<Point> ps) {
        String k = me(a);
        if (k.isEmpty()) return;
        Point f = ps.isEmpty() ? null : ps.get(0);
        prefs(a).edit().putInt("n_" + k, ps.size()).putString("main_" + k, f == null ? "" : f.name)
            .putString("close_" + k, f == null ? "" : arrJson(f.close)).apply();
    }

    /** Название первого пункта ("" — пунктов нет или ещё не загружали). */
    static String mainName(Activity a) { return prefs(a).getString("main_" + me(a), ""); }

    /** Время закрытия первого пункта (Пн…Вс, -1 — выходной) или null. */
    static int[] mainClose(Activity a) { return mainName(a).isEmpty() ? null : arr(prefs(a).getString("close_" + me(a), "")); }

    static String rowSub(Activity a) {
        int n = prefs(a).getInt("n_" + me(a), 0);
        String m = mainName(a);
        return n <= 0 || m.isEmpty() ? ROW_SUB : countText(n) + " · первый — " + m;
    }

    // ───────────── сеть ─────────────

    static List<Point> parse(String body) throws Exception {
        List<Point> out = new ArrayList<Point>();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return out;
        JSONArray arr = new JSONArray(t);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Point p = new Point();
            p.id = o.optString("id", "");
            p.name = o.optString("name", "");
            p.address = o.optString("address", "");
            p.city = o.isNull("city") ? "" : o.optString("city", "");
            p.email = o.isNull("email") ? "" : o.optString("email", "");
            p.publish = publishOf(o.isNull("staff_publish") ? "" : o.optString("staff_publish", ""));
            p.tel = o.isNull("tel") ? "" : o.optString("tel", "");
            if (!o.isNull("lat") && !o.isNull("lng")) { p.lat = o.optDouble("lat"); p.lng = o.optDouble("lng"); }
            int[] op = arr(String.valueOf(o.opt("open_min"))), cl = arr(String.valueOf(o.opt("close_min")));
            if (op != null && cl != null) { p.open = op; p.close = cl; }
            out.add(p);
        }
        return out;
    }

    /** Список своих пунктов из базы; [0] — код ответа, ошибка — исключение. */
    static List<Point> load(Activity a, String[] code) throws Exception {
        // 10.113: сначала с выключателем и телефоном; нет колонки — без неё (без SQL 10.113 / 10.99 — как раньше)
        boolean pub = true, tel = true;
        String[] r = null;
        for (int i = 0; i < 4; i++) {
            pub = i < 2;
            tel = i % 2 == 0;
            r = IvaClientPatch.apiCall(a, listPath(me(a), tel, pub), "GET", null, true);
            if (r == null || r[0].startsWith("2")) break;
            String b = r.length > 1 ? r[1] : "";
            if (!noPub(r[0], b) && !noTel(r[0], b)) break;
        }
        if (r != null && r[0].startsWith("2")) { telDb = tel; pubDb = pub; }
        code[0] = r == null ? "" : r[0];
        if (r == null || !r[0].startsWith("2")) throw new Exception(dbError(code[0], r != null && r.length > 1 ? r[1] : ""));
        return parse(r.length > 1 ? r[1] : "");
    }

    static String httpGet(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setUseCaches(true);
            c.setRequestProperty("User-Agent", "Iva-Android (prokat app)");
            c.setRequestProperty("Accept-Language", "ru");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) { out.write(buf, 0, r); if (out.size() > 262144) break; }
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** Подсказки адреса: {адрес, город, регион, широта, долгота}; сбой — null. */
    static List<String[]> suggest(String q) {
        String body = httpGet(photonUrl(q));
        if (body == null) return null;
        List<String[]> out = new ArrayList<String[]>();
        try {
            JSONArray fs = new JSONObject(body).optJSONArray("features");
            if (fs == null) return null;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (int i = 0; i < fs.length() && out.size() < 6; i++) {
                JSONObject f = fs.optJSONObject(i);
                JSONObject pr = f == null ? null : f.optJSONObject("properties");
                if (pr == null) continue;
                Map<String, String> m = new HashMap<String, String>();
                java.util.Iterator<String> it = pr.keys();
                while (it.hasNext()) { String k = it.next(); m.put(k, pr.optString(k, "")); }
                String[] r = addrOf(m);
                if (r == null || !seen.add(r[0] + "|" + r[2])) continue;
                JSONObject g = f.optJSONObject("geometry");
                JSONArray co = g == null ? null : g.optJSONArray("coordinates");
                String lng = co != null && co.length() == 2 ? String.valueOf(co.optDouble(0)) : "", lat = co != null && co.length() == 2 ? String.valueOf(co.optDouble(1)) : "";
                out.add(new String[]{r[0], r[1], r[2], lat, lng});
            }
        } catch (Throwable e) {
            return null;
        }
        return out;
    }

    // ───────────── строка в «Настройках» ─────────────

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }

    static void settingsRow(final Activity a, LinearLayout page, final int cText, final int cGray, final int cOrange) {
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Drawable hbg = IvaOrderPatch.drawable(a, "bg_card");
        if (hbg != null) head.setBackground(hbg);
        int p16 = dp(a, 16), p14 = dp(a, 14);
        head.setPadding(p16, p14, p16, p14);
        head.setClickable(true);
        android.widget.ImageView ic = new android.widget.ImageView(a);
        ic.setImageDrawable(new Pin(cOrange, a.getResources().getDisplayMetrics().density));
        head.addView(ic, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(ROW_TITLE);
        t.setTextSize(15f);
        t.setTextColor(cText);
        col.addView(t);
        final TextView sub = new TextView(a);
        sub.setText(rowSub(a));
        sub.setTextSize(13f);
        sub.setTextColor(cGray);
        col.addView(sub);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = p14;
        head.addView(col, cl);
        android.widget.ImageView chev = new android.widget.ImageView(a);
        Drawable chd = IvaOrderPatch.drawable(a, "ic_chevron");
        if (chd != null) { chev.setImageDrawable(chd); chev.setColorFilter(cGray); }
        head.addView(chev, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(a, 10);
        page.addView(head, hl);
        subRef = new WeakReference<TextView>(sub);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openPage(a); } catch (Throwable ignore) {} }
        });
        refreshCache(a);   // из базы — актуальные (могли поменять с другого телефона)
    }

    static volatile WeakReference<TextView> subRef = new WeakReference<TextView>(null);

    static void refreshCache(final Activity a) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Point> ps = load(a, new String[1]);
                    a.runOnUiThread(new Runnable() { @Override public void run() { applyCache(a, ps); } });
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    static void applyCache(Activity a, List<Point> ps) {
        remember(a, ps);
        TextView s = subRef.get();
        if (s != null) s.setText(rowSub(a));
        try { IvaRentPatch.relink(); } catch (Throwable ignore) {}   // «Срок аренды» — по графику первого пункта
        IvaToolPointPatch.mineAt = 0;                                // 10.101: «Пункт выдачи» в формах перечитает пункты
    }

    /** Значок «метка на карте» (цвет приложения). */
    static final class Pin extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;
        Pin(int color, float density) { p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f * density); d = density; }
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height(), cx = getBounds().left + w / 2f, top = getBounds().top;
            float r = Math.min(w, h) * 0.32f, cy = top + h * 0.40f;
            Path path = new Path();
            path.moveTo(cx, top + h * 0.94f);
            path.cubicTo(cx - r * 0.55f, cy + r * 1.35f, cx - r, cy + r * 0.75f, cx - r, cy);
            path.arcTo(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f);
            path.cubicTo(cx + r, cy + r * 0.75f, cx + r * 0.55f, cy + r * 1.35f, cx, top + h * 0.94f);
            path.close();
            c.drawPath(path, p);
            c.drawCircle(cx, cy, r * 0.38f, p);
        }
        @Override public void setAlpha(int al) { p.setAlpha(al); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ───────────── страница «Пункт выдачи» ─────────────

    static final class Ui {
        Activity a;
        IvaChiefPatch.Page page;
        LinearLayout form, items;
        TextView status;
        List<Point> points = new ArrayList<Point>();
        int cText, cGray, cOrange, cRed = 0xffff5a5f;
        Runnable editClose;     // 10.99: открытое изменение в карточке — как его закрыть
    }

    static void openPage(final Activity a) {
        final Ui u = new Ui();
        u.a = a;
        u.cText = color(a, "text", 0xfff2f4f8);
        u.cGray = color(a, "gray", 0xff9aa1b0);
        u.cOrange = color(a, "orange", 0xffff8f1f);
        u.page = IvaChiefPatch.page(a, ROW_TITLE, PAGE_SUB, false);
        try { u.page.d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Throwable ignore) {}
        LinearLayout list = u.page.list;
        TextView add = addButton(a, u.cOrange);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(a, 4);
        list.addView(add, al);
        u.form = new LinearLayout(a);
        u.form.setOrientation(LinearLayout.VERTICAL);
        u.form.setVisibility(View.GONE);
        list.addView(u.form, new LinearLayout.LayoutParams(-1, -2));
        u.status = IvaChiefPatch.text(a, "Загружаем…", 13f, u.cGray, false);
        u.status.setPadding(dp(a, 2), dp(a, 12), 0, 0);
        list.addView(u.status, new LinearLayout.LayoutParams(-1, -2));
        u.items = new LinearLayout(a);
        u.items.setOrientation(LinearLayout.VERTICAL);
        list.addView(u.items, new LinearLayout.LayoutParams(-1, -2));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (u.points.size() >= MAX) { toast(a, "Не больше " + MAX + " пунктов выдачи"); return; }
                showForm(u, new Point(), true);
            }
        });
        reload(u);
    }

    /** «Добавить»: тёмная (цвет карточек), оранжевая рамка и оранжевый жирный текст — как просил владелец. */
    static TextView addButton(Activity a, int cOrange) {
        TextView b = new TextView(a);
        b.setText(ADD);
        b.setTextSize(15f);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(cOrange);
        b.setGravity(Gravity.CENTER);
        b.setBackground(IvaPatch.accentBg(a, cOrange, 12));   // 10.96: этот вид — образец для всех кнопок приложения
        b.setPadding(dp(a, 14), dp(a, 14), dp(a, 14), dp(a, 14));
        b.setClickable(true);
        return b;
    }

    static void reload(final Ui u) {
        new Thread(new Runnable() {
            @Override public void run() {
                List<Point> ps = null;
                String err = "";
                try { ps = load(u.a, new String[1]); IvaStaffPatch.fetch(u.a); } catch (Throwable e) {
                    String m = e.getMessage();
                    err = m == null || m.isEmpty() ? "Не загрузилось — нет связи с сервером" : m;
                }
                final List<Point> fps = ps;
                final String ferr = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (fps == null) { u.status.setText(ferr); u.status.setTextColor(u.cOrange); return; }
                            u.points = fps;
                            applyCache(u.a, fps);
                            drawList(u);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void drawList(final Ui u) {
        final Activity a = u.a;
        u.items.removeAllViews();
        u.editClose = null;
        u.status.setTextColor(u.cGray);
        u.status.setText(u.points.isEmpty() ? "Пунктов пока нет — нажмите «Добавить»" : countText(u.points.size()));
        for (int i = 0; i < u.points.size(); i++) {
            final Point p = u.points.get(i);
            final LinearLayout c = IvaChiefPatch.card(a);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(IvaChiefPatch.text(a, p.name, 16f, u.cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
            if (i == 0) top.addView(IvaChiefPatch.text(a, "по нему «Срок аренды»", 12f, u.cOrange, false));
            c.addView(top);
            // всё, что под названием, — в info: при «Изменить» оно уступает место полям (карточка растягивается вниз)
            final LinearLayout info = new LinearLayout(a);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView ad = IvaChiefPatch.text(a, p.address, 14f, u.cText, false);
            ad.setPadding(0, dp(a, 6), 0, 0);
            info.addView(ad);
            if (telDb) {   // 10.99: телефон пункта
                TextView tl = IvaChiefPatch.text(a, telLine(p.tel), 13f, p.tel.trim().isEmpty() ? u.cGray : u.cText, false);
                tl.setPadding(0, dp(a, 4), 0, 0);
                info.addView(tl);
            }
            TextView hr = IvaChiefPatch.text(a, hoursText(p.open, p.close), 13f, u.cGray, false);
            hr.setPadding(0, dp(a, 4), 0, 0);
            info.addView(hr);
            if (pubDb) {   // 10.113: «Инструменты сотрудников» пункта (вместо «Email проката»)
                TextView pb = IvaChiefPatch.text(a, publishLine(p.publish), 13f, u.cGray, false);
                pb.setPadding(0, dp(a, 4), 0, 0);
                info.addView(pb);
            }
            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, dp(a, 10), 0, 0);
            TextView ed = IvaSearchPatch.chip(a, "Изменить", true, false);
            TextView del = IvaSearchPatch.chip(a, "Удалить", false, false);
            ed.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { editInCard(u, copy(p), c, info); }
            });
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { askDelete(u, p); }
            });
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
            el.rightMargin = dp(a, 8);
            btns.addView(ed, el);
            btns.addView(del, new LinearLayout.LayoutParams(-2, -2));
            info.addView(btns);
            // 10.103: «Сотрудники» пункта — отдельной строкой (три кнопки в ряд не помещаются на узком экране)
            TextView staff = IvaSearchPatch.chip(a, IvaStaffPatch.chipText(IvaStaffPatch.count(p.id)), false, false);
            staff.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        IvaStaffPatch.openPoint(a, p, u.points, new Runnable() { @Override public void run() { try { drawList(u); } catch (Throwable ignore) {} } });
                    } catch (Throwable ignore) {}
                }
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = dp(a, 8);
            info.addView(staff, sl);
            c.addView(info);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);
            u.items.addView(c, lp);
        }
    }

    static Point copy(Point s) {
        Point p = new Point();
        p.id = s.id; p.name = s.name; p.address = s.address; p.city = s.city; p.email = s.email; p.tel = s.tel; p.publish = s.publish; p.lat = s.lat; p.lng = s.lng;
        p.open = s.open.clone(); p.close = s.close.clone();
        return p;
    }

    static void askDelete(final Ui u, final Point p) {
        IvaClientPatch.styledChoice(u.a, "Удалить пункт «" + p.name + "»?",
            ((u.points.size() > 1 && u.points.get(0).id.equals(p.id)
                ? "Это первый пункт — «Срок аренды» станет считаться по графику следующего." : "")
                + (IvaStaffPatch.count(p.id) > 0 ? " Сотрудники этого пункта потеряют к нему доступ." : "")).trim(),
            new String[]{"Удалить"}, new IvaClientPatch.Choice() {
                @Override public void pick(int i) { if (i == 0) write(u, "DELETE", idPath(p.id), null, "Пункт удалён"); }
            });
    }

    static void write(final Ui u, final String method, final String path, final String body, final String done) {
        u.status.setText("Сохраняем…");
        u.status.setTextColor(u.cGray);
        new Thread(new Runnable() {
            @Override public void run() {
                String err;
                try {
                    String[] r = IvaClientPatch.apiCall(u.a, path, method, body, true);
                    err = r == null ? dbError("", "") : dbError(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable e) { err = dbError("", ""); }
                final String ferr = err;
                u.a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ferr.isEmpty()) { u.status.setText(ferr); u.status.setTextColor(u.cOrange); toast(u.a, ferr); return; }
                        toast(u.a, done);
                        u.form.removeAllViews();
                        u.form.setVisibility(View.GONE);
                        u.editClose = null;
                        reload(u);
                    }
                });
            }
        }).start();
    }

    // ── плавное раскрытие (10.99): высота от «как было» до «как стало», затем снова по содержимому ──

    /** Высота вида по содержимому при его нынешней ширине. */
    static int wrapHeight(View v) {
        int w = v.getWidth();
        if (w <= 0) return -1;
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return v.getMeasuredHeight();
    }

    /** Высота from → to (вниз — растягивается, вверх — сжимается; лишнее обрезается краем карточки). */
    static void animateHeight(final View v, int from, int to, final Runnable end) {
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || from < 0 || to < 0 || from == to) {
            if (lp != null) { lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; v.setLayoutParams(lp); }
            if (end != null) end.run();
            return;
        }
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(from, to);
        va.setDuration(ANIM_MS);
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator an) {
                lp.height = (Integer) an.getAnimatedValue();
                v.setLayoutParams(lp);
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean done;
            void finish() {
                if (done) return;
                done = true;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;   // дальше — по содержимому (ошибки полей, подсказки адреса)
                v.setLayoutParams(lp);
                if (end != null) try { end.run(); } catch (Throwable ignore) {}
            }
            @Override public void onAnimationEnd(android.animation.Animator an) { finish(); }
            @Override public void onAnimationCancel(android.animation.Animator an) { finish(); }
        });
        lp.height = from;
        v.setLayoutParams(lp);
        va.start();
    }

    static void fadeIn(View v) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ANIM_MS).start();
    }

    static void scrollTo(Ui u, View child) {
        try {
            View sv = (View) u.page.list.getParent();
            if (!(sv instanceof ScrollView)) return;
            int y = 0;
            for (View v = child; v != null && v != u.page.list; v = (View) v.getParent()) y += v.getTop();
            ((ScrollView) sv).smoothScrollTo(0, Math.max(0, y - dp(u.a, 8)));
        } catch (Throwable ignore) {}
    }

    /** Закрыть открытую форму (новый пункт сверху или изменение в карточке). */
    static void closeForms(Ui u, boolean animated) {
        Runnable ec = u.editClose;
        u.editClose = null;
        if (ec != null) try { ec.run(); } catch (Throwable ignore) {}
        if (u.form.getVisibility() == View.VISIBLE) {
            if (animated) closeTopForm(u);
            else { u.form.removeAllViews(); u.form.setVisibility(View.GONE); }
        }
    }

    static void closeTopForm(final Ui u) {
        hideKb(u.a, u.form);
        animateHeight(u.form, u.form.getHeight(), 0, new Runnable() {
            @Override public void run() { u.form.removeAllViews(); u.form.setVisibility(View.GONE); }
        });
    }

    /** «Добавить»: форма нового пункта — сверху, раскрывается вниз. */
    static void showForm(final Ui u, final Point p, final boolean isNew) {
        final Activity a = u.a;
        closeForms(u, false);
        final LinearLayout f = u.form;
        f.removeAllViews();
        f.setVisibility(View.VISIBLE);
        LinearLayout box = IvaChiefPatch.card(a);
        f.addView(box);
        box.addView(IvaChiefPatch.text(a, isNew ? "Новый пункт выдачи" : "Изменить пункт", 16f, u.cText, true));
        final EditText name = buildForm(u, p, isNew, box, new Runnable() { @Override public void run() { closeTopForm(u); } });
        // форма — сразу под «Добавить»: прокрутка наверх, раскрытие вниз и курсор в «Название»
        f.post(new Runnable() {
            @Override public void run() {
                try {
                    animateHeight(f, 0, wrapHeight(f), null);
                    fadeIn(f);
                    View sv = (View) u.page.list.getParent();
                    if (sv instanceof ScrollView) ((ScrollView) sv).smoothScrollTo(0, 0);
                    name.requestFocus();
                    if (isNew) {
                        InputMethodManager im = (InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE);
                        if (im != null) im.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Throwable ignore) {}
            }
        });
    }

    /** «Изменить»: карточка растягивается вниз — вместо адреса, графика и кнопок появляются поля (10.99). */
    static void editInCard(final Ui u, final Point p, final LinearLayout card, final View info) {
        final Activity a = u.a;
        if (card.findViewWithTag(EDIT_TAG) != null) return;      // уже открыта
        closeForms(u, false);
        final int from = card.getHeight();
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(EDIT_TAG);
        View line = new View(a);                                   // тонкая черта: ниже — изменение
        line.setBackgroundColor(0x22ffffff);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(a, 1)));
        ll.topMargin = dp(a, 10);
        box.addView(line, ll);
        final Runnable[] close = new Runnable[1];
        close[0] = new Runnable() {
            @Override public void run() {
                if (u.editClose == close[0]) u.editClose = null;
                hideKb(a, card);
                int start = card.getHeight();
                box.setVisibility(View.GONE);
                info.setVisibility(View.VISIBLE);
                int to = wrapHeight(card);
                box.setVisibility(View.VISIBLE);
                box.animate().alpha(0f).setDuration(ANIM_MS).start();
                fadeIn(info);
                animateHeight(card, start, to, new Runnable() { @Override public void run() { card.removeView(box); } });
            }
        };
        buildForm(u, p, false, box, close[0]);
        info.setVisibility(View.GONE);
        card.addView(box, new LinearLayout.LayoutParams(-1, -2));
        u.editClose = close[0];
        fadeIn(box);
        animateHeight(card, from, wrapHeight(card), new Runnable() { @Override public void run() { scrollTo(u, card); } });
    }

    static final String EDIT_TAG = "iva_pickup_edit_10_99";

    /** Поля пункта в box: Название, Адрес (подсказки), Телефон пункта выдачи, График, «Инструменты сотрудников» (10.113);
     *  «Сохранить» / «Отмена». */
    static EditText buildForm(final Ui u, final Point p, final boolean isNew, final LinearLayout box, final Runnable onCancel) {
        final Activity a = u.a;

        // Название
        box.addView(label(u, "Название"));
        final EditText name = field(u, "Например: Склад на Ленина", p.name);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        final TextView nameE = err(u);
        box.addView(nameE);

        // Адрес — полный, с подсказками
        box.addView(label(u, "Адрес"));
        final EditText addr = field(u, "Полностью: город, улица, дом", p.address);
        box.addView(addr, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout sug = new LinearLayout(a);
        sug.setOrientation(LinearLayout.VERTICAL);
        sug.setVisibility(View.GONE);
        Drawable sb = IvaSearchPatch.drawable(a, "bg_field");
        if (sb != null) sug.setBackground(sb);
        LinearLayout.LayoutParams sgl = new LinearLayout.LayoutParams(-1, -2);
        sgl.topMargin = dp(a, 4);
        box.addView(sug, sgl);
        final TextView addrE = err(u);
        box.addView(addrE);
        hookSuggest(u, addr, sug, p);

        // Телефон пункта выдачи (10.99)
        box.addView(label(u, L_TEL));
        final EditText tel = field(u, "+7 900 123-45-67", p.tel);
        tel.setInputType(InputType.TYPE_CLASS_PHONE);
        box.addView(tel, new LinearLayout.LayoutParams(-1, -2));
        final TextView telE = err(u);
        box.addView(telE);
        if (!telDb) {
            tel.setEnabled(false);
            telE.setTextColor(u.cOrange);
            setErr(telE, NO_TEL);
        }

        // График
        box.addView(label(u, "График"));
        final LinearLayout days = new LinearLayout(a);
        days.setOrientation(LinearLayout.VERTICAL);
        box.addView(days, new LinearLayout.LayoutParams(-1, -2));
        final Runnable[] redraw = new Runnable[1];
        redraw[0] = new Runnable() { @Override public void run() { drawDays(u, days, p, redraw[0]); } };
        redraw[0].run();
        TextView copyAll = IvaChiefPatch.text(a, "Время понедельника — на все дни", 14f, u.cOrange, true);
        copyAll.setPadding(0, dp(a, 10), 0, dp(a, 2));
        copyAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (int i = 1; i < 7; i++) { p.open[i] = p.open[0]; p.close[i] = p.close[0]; }
                redraw[0].run();
            }
        });
        box.addView(copyAll);
        final TextView daysE = err(u);
        box.addView(daysE);

        // 10.113: «Инструменты сотрудников» этого пункта — выключатель с пояснением (поля «Email проката» больше нет)
        box.addView(label(u, "Инструменты сотрудников"));
        final android.widget.Switch pubSw = IvaStaffPatch.sw(a, box, PUB_TITLE, PUB_SUB, !"now".equals(p.publish), u.cText, u.cGray, u.cOrange);
        if (!pubDb) {
            pubSw.setEnabled(false);
            ((View) pubSw.getParent()).setEnabled(false);
            TextView pubE = err(u);
            pubE.setTextColor(u.cOrange);
            box.addView(pubE);
            setErr(pubE, NO_PUB);
        }

        // Сохранить / Отмена
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(a, 16), 0, 0);
        TextView save = new TextView(a);
        save.setText("Сохранить");
        save.setTextSize(15f);
        IvaPatch.accentButton(a, save);                  // 10.96: как «Добавить» — рамкой
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(a, 12), dp(a, 13), dp(a, 12), dp(a, 13));
        TextView cancel = IvaChiefPatch.text(a, "Отмена", 15f, u.cGray, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(a, 12), dp(a, 13), dp(a, 12), dp(a, 13));
        btns.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = dp(a, 8);
        btns.addView(cancel, cl);
        box.addView(btns);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideKb(a, name); onCancel.run(); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.name = String.valueOf(name.getText());
                p.address = String.valueOf(addr.getText());
                if (pubDb) p.publish = pubSw.isChecked() ? "approve" : "now";
                else if (isNew || p.email.trim().isEmpty()) p.email = me(a);   // без SQL 10.113 база требует email — свой вход
                if (telDb) p.tel = String.valueOf(tel.getText()).trim();
                String ne = nameErr(p.name), ae = addrErr(p.address), te = telDb ? telErr(p.tel) : "";
                if (ne.isEmpty()) try { ne = IvaCensorPatch.fieldError(p.name); } catch (Throwable ignore) {}
                if (ae.isEmpty()) try { ae = IvaCensorPatch.fieldError(p.address); } catch (Throwable ignore) {}
                String de = hoursOk(p.open, p.close) ? "" : "Нужен хотя бы один рабочий день; закрытие — позже открытия";
                setErr(nameE, ne); setErr(addrE, ae); setErr(daysE, de);
                if (telDb) { telE.setTextColor(u.cRed); setErr(telE, te); }
                if (!ne.isEmpty() || !ae.isEmpty() || !de.isEmpty() || !te.isEmpty()) return;
                hideKb(a, name);
                if (isNew) write(u, "POST", TABLE, body(p, telDb, pubDb), "Пункт «" + p.name.trim() + "» добавлен");
                else write(u, "PATCH", idPath(p.id), body(p, telDb, pubDb), "Пункт сохранён");
            }
        });
        return name;
    }

    // ── форма: Название, Адрес (подсказки), Телефон пункта, График, «Инструменты сотрудников» ──

    static TextView label(Ui u, String s) {
        TextView t = IvaChiefPatch.text(u.a, s, 13f, u.cGray, true);
        t.setPadding(dp(u.a, 2), dp(u.a, 14), 0, dp(u.a, 6));
        return t;
    }

    static EditText field(Ui u, String hint, String value) {
        Activity a = u.a;
        EditText f = new EditText(a);
        f.setSingleLine(true);
        f.setHint(hint);
        f.setText(value);
        f.setTextColor(u.cText);
        f.setHintTextColor(color(a, "gray2", 0xff6b7080));
        f.setTextSize(15f);
        Drawable fb = IvaSearchPatch.drawable(a, "bg_field");
        if (fb != null) f.setBackground(fb);
        f.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        return f;
    }

    static TextView err(Ui u) {
        TextView t = IvaChiefPatch.text(u.a, "", 12f, u.cRed, false);
        t.setPadding(dp(u.a, 2), dp(u.a, 4), 0, 0);
        t.setVisibility(View.GONE);
        return t;
    }

    static void setErr(TextView t, String s) { t.setText(s); t.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE); }

    static void hideKb(Activity a, View v) {
        try {
            InputMethodManager im = (InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignore) {}
    }

    /** 7 строк: «Пн  09:00 – 19:00  [рабочий]»; время — нажатием (сначала открытие, затем закрытие). */
    static void drawDays(final Ui u, LinearLayout days, final Point p, final Runnable redraw) {
        final Activity a = u.a;
        days.removeAllViews();
        for (int i = 0; i < 7; i++) {
            final int d = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView dl = IvaChiefPatch.text(a, IvaNotifyPatch.DAYS[i], 15f, u.cText, true);
            row.addView(dl, new LinearLayout.LayoutParams(dp(a, 40), -2));
            boolean on = p.open[i] >= 0;
            TextView tt = IvaChiefPatch.text(a, on ? IvaNotifyPatch.hm(p.open[i]) + " – " + IvaNotifyPatch.hm(p.close[i]) : "Выходной",
                15f, on ? u.cOrange : u.cGray, on);
            tt.setPadding(0, dp(a, 10), dp(a, 8), dp(a, 10));
            row.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch sw = new Switch(a);
            IvaNotifyPatch.tint(sw, u.cOrange);
            sw.setChecked(on);
            sw.setContentDescription(IvaNotifyPatch.DAYS_FULL[i] + ": рабочий день");
            row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
            tt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (p.open[d] >= 0) pickHours(u, p, d, redraw); }
            });
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                    if (v) { p.open[d] = OPEN_DEF; p.close[d] = CLOSE_DEF; }
                    else { p.open[d] = -1; p.close[d] = -1; }
                    redraw.run();
                }
            });
            days.addView(row);
        }
    }

    static void pickHours(final Ui u, final Point p, final int d, final Runnable redraw) {
        final Activity a = u.a;
        time(a, "Открытие — " + IvaNotifyPatch.DAYS_FULL[d], p.open[d], new TimeSet() {
            @Override public void set(final int open) {
                time(a, "Закрытие — " + IvaNotifyPatch.DAYS_FULL[d], Math.max(p.close[d], open + 60 > 1439 ? 1439 : open + 60), new TimeSet() {
                    @Override public void set(int close) {
                        if (close <= open) { toast(a, "Закрытие должно быть позже открытия"); return; }
                        p.open[d] = open;
                        p.close[d] = close;
                        redraw.run();
                    }
                });
            }
        });
    }

    interface TimeSet { void set(int min); }

    static void time(Activity a, String title, int m, final TimeSet cb) {
        try {
            int v = m >= 0 ? m : OPEN_DEF;
            android.app.TimePickerDialog d = new android.app.TimePickerDialog(a, android.R.style.Theme_Material_Dialog,
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(android.widget.TimePicker t, int h, int mi) { cb.set(h * 60 + mi); }
                }, v / 60, v % 60, true);
            d.setTitle(title);
            d.show();
        } catch (Throwable e) {
            toast(a, "Не удалось открыть выбор времени");
        }
    }

    /** Подсказки адреса: после паузы в наборе (от 3 букв) — список под полем; выбрали — адрес и координаты. */
    // ───────────── 10.121: первый пункт из «Данных проката» ─────────────

    /** Тело rpc/iva_pickup_first: адрес, город и координаты подсказки (если выбрана), график Пн…Вс или null. */
    static String firstBody(String addr, Point p, int[][] hours) {
        StringBuilder b = new StringBuilder("{\"p_address\":").append(IvaShopPatch.q(addr == null ? "" : addr.trim()));
        b.append(",\"p_city\":").append(IvaShopPatch.q(p == null || p.city == null ? "" : p.city.trim()));
        b.append(",\"p_lat\":").append(p == null || p.lat == null || p.lng == null ? "null" : String.valueOf(p.lat));
        b.append(",\"p_lng\":").append(p == null || p.lat == null || p.lng == null ? "null" : String.valueOf(p.lng));
        for (int k = 0; k < 2; k++) {
            b.append(k == 0 ? ",\"p_open\":" : ",\"p_close\":");
            int[] h = hours == null || hours.length < 2 ? null : hours[k];
            if (h == null || h.length != 7) { b.append("null"); continue; }
            b.append('[');
            for (int i = 0; i < 7; i++) b.append(i == 0 ? "" : ",").append(h[i]);
            b.append(']');
        }
        return b.append('}').toString();
    }

    /** Ответ iva_pickup_first: id созданного пункта или null (пункты уже есть / первый уже создавали). */
    static boolean firstMade(String resp) {
        String t = resp == null ? "" : resp.trim().replace("\"", "");
        return t.length() >= 32 && !"null".equals(t);
    }

    /** Фон. Создаёт первый пункт, если у проката их ещё нет (один раз; дальше — «Пункты выдачи»). Имя пункта или "". */
    static String first(Activity a, String addr, Point p, int[][] hours, String company) throws Exception {
        String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_pickup_first", "POST", firstBody(addr, p, hours), true);
        if (r == null || !r[0].startsWith("2") || !firstMade(r.length > 1 ? r[1] : "")) return "";
        String co = company == null ? "" : company.trim();
        return co.isEmpty() ? "Пункт выдачи" : co;
    }

    static void hookSuggest(final Ui u, final EditText addr, final LinearLayout sug, final Point p) {
        final Activity a = u.a;
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] quiet = new boolean[1];
        final int[] seq = new int[1];
        final Runnable ask = new Runnable() {
            @Override public void run() {
                final String q = String.valueOf(addr.getText()).trim();
                if (q.length() < 3) { sug.setVisibility(View.GONE); return; }
                final int my = ++seq[0];
                new Thread(new Runnable() {
                    @Override public void run() {
                        final List<String[]> rows = suggest(q);
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (my != seq[0] || !addr.hasFocus()) return;
                                sug.removeAllViews();
                                if (rows == null || rows.isEmpty()) {
                                    if (rows == null) {
                                        TextView t = IvaChiefPatch.text(a, "Подсказки адреса недоступны — введите адрес полностью", 13f, u.cGray, false);
                                        t.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
                                        sug.addView(t);
                                        sug.setVisibility(View.VISIBLE);
                                    } else sug.setVisibility(View.GONE);
                                    return;
                                }
                                for (final String[] r : rows) {
                                    LinearLayout item = new LinearLayout(a);
                                    item.setOrientation(LinearLayout.VERTICAL);
                                    item.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
                                    item.setClickable(true);
                                    item.addView(IvaChiefPatch.text(a, r[0], 14f, u.cText, false));
                                    if (!r[2].isEmpty()) item.addView(IvaChiefPatch.text(a, r[2], 12f, u.cGray, false));
                                    item.setOnClickListener(new View.OnClickListener() {
                                        @Override public void onClick(View v) {
                                            quiet[0] = true;
                                            addr.setText(r[0]);
                                            addr.setSelection(addr.getText().length());
                                            quiet[0] = false;
                                            p.city = r[1];
                                            try { p.lat = Double.valueOf(r[3]); p.lng = Double.valueOf(r[4]); } catch (Throwable e) { p.lat = null; p.lng = null; }
                                            sug.setVisibility(View.GONE);
                                        }
                                    });
                                    sug.addView(item);
                                }
                                sug.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                }).start();
            }
        };
        addr.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (quiet[0]) return;
                p.lat = null; p.lng = null; p.city = "";   // изменили вручную — координаты подсказки уже не про этот адрес
                h.removeCallbacks(ask);
                h.postDelayed(ask, SUGGEST_DELAY_MS);
            }
        });
        addr.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { if (!has) sug.setVisibility(View.GONE); }
        });
    }

    // ───────────── 10.114: подсказки полного адреса в любом поле адреса ─────────────
    // Профиль («Адрес»), заявка («Адрес доставки» — вместо штатных подсказок, которые искали только по Новочеркасску),
    // пункты выдачи и «Данные проката» (там — с 10.95 / 10.98). Подсказки — те же (Photon / OpenStreetMap, вся Россия).

    static final String SUG_TAG = "iva_addr_sug_10_114";
    static final java.util.Map<EditText, LinearLayout> sugs = new java.util.WeakHashMap<EditText, LinearLayout>();

    /** Список подсказок сразу под полем (в том же столбце) и подсказки по набору; повторно — тот же список. */
    static LinearLayout attachSuggest(Activity a, EditText field) {
        if (field == null) return null;
        View anchor = anchor(field);                     // штатное поле заявки лежит в рамке (FrameLayout) — список под рамкой
        if (anchor == null) return null;
        LinearLayout col = (LinearLayout) anchor.getParent();
        LinearLayout had = sugs.get(field);
        if (had != null && had.getParent() == col) return had;
        LinearLayout sug = new LinearLayout(a);
        sug.setOrientation(LinearLayout.VERTICAL);
        sug.setVisibility(View.GONE);
        sug.setTag(SUG_TAG);
        Drawable sb = IvaSearchPatch.drawable(a, "bg_field");
        if (sb != null) sug.setBackground(sb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 4);
        col.addView(sug, col.indexOfChild(anchor) + 1, lp);
        Ui u = new Ui();
        u.a = a;
        u.cText = color(a, "text", 0xfff2f4f8);
        u.cGray = color(a, "gray", 0xff9aa1b0);
        u.cOrange = color(a, "orange", 0xffff8f1f);
        hookSuggest(u, field, sug, new Point());
        sugs.put(field, sug);
        return sug;
    }

    /** Поле или его рамка (не выше двух уровней), лежащие прямо в вертикальном столбце; нет — null. */
    static View anchor(View field) {
        View v = field;
        for (int i = 0; i < 3 && v != null; i++) {
            Object p = v.getParent();
            if (p instanceof LinearLayout && ((LinearLayout) p).getOrientation() == LinearLayout.VERTICAL) return v;
            v = p instanceof View ? (View) p : null;
        }
        return null;
    }

    /** Штатный список подсказок (только Новочеркасск) — схлопнуть: штатный код может его показать, но он нулевой высоты. */
    static void collapse(View v) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.height != 0) { lp.height = 0; v.setLayoutParams(lp); }
    }

    static void toast(Activity a, String s) {
        try { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
    }
}

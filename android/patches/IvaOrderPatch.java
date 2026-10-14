// 10.55: заявка в приложении — ФИО по полям, без количества, свой срок у каждого инструмента,
// «+ Добавить инструмент» этого же проката, доставка — средняя, одна заявка — один прокат.
// Вход — только IvaPatch.onTab / onSummary / onPrice (новых smali-хуков нет).
package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IvaOrderPatch {
    private IvaOrderPatch() {}

    static final String LINES_TAG = "iva_order_lines";
    static final String HINT_TAG = "iva_order_deliv_hint";
    static final String DAYS_KEY = "iva_days";
    static final String DEFAULT_OWNER = "eclips.ru@mail.ru";
    static final String DEFAULT_PICKUP = "г. Новочеркасск, ул. Маресьева, 36";
    static final int MIN_DAYS = 1, MAX_DAYS = 90;          // как штатный счётчик «Срок аренды (дней)»
    static final String ADD_TITLE = "+ Добавить инструмент";
    static final String PICK_HINT = "Показаны только инструменты с доставкой. Если выбрать самовывоз, инструментов в списке может стать больше.";
    static final String REPLACE_MSG = "В заявке уже есть инструменты другого проката. В одну заявку можно добавить инструменты только одного проката.\n\nЗаменить их этим инструментом?";

    static WeakReference<Activity> act;
    static WeakReference<View> hookedSend, hookedDet;
    static boolean posted, sending;
    static String delivSig = "";
    static String[] lastFill;              // что мы сами вписали в Фамилию / Имя / Отчество

    // ───────────────────────── чистая модель (проверяется в IvaPatchTest) ─────────────────────────

    static final class Line {
        String key = "", id = "", name = "", owner = DEFAULT_OWNER;
        double price, dep, dprice;
        boolean deliv, srv;
        int days = 1;
    }

    static Line line(String key, String name, double price, double dep, boolean deliv, double dprice, boolean srv, String owner, int days) {
        Line l = new Line();
        l.key = key == null ? "" : key;
        l.name = name == null ? "" : name;
        l.price = price;
        l.dep = dep;
        l.deliv = deliv;
        l.dprice = dprice;
        l.srv = srv;
        l.owner = owner == null || owner.trim().isEmpty() ? DEFAULT_OWNER : owner.trim();
        l.days = clampDays(days);
        return l;
    }

    static int clampDays(int d) { return Math.max(MIN_DAYS, Math.min(MAX_DAYS, d)); }

    /** Как штатный plural: 1 день, 2 дня, 5 дней, 11 дней, 21 день. */
    static String daysWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "день";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "дня";
        return "дней";
    }

    static String daysText(int n) { return n + " " + daysWord(n); }

    static String money(long v) { return IvaPatch.money(v); }

    static long rent(Line l) { return l.price > 0 ? Math.round(l.price * l.days) : 0; }

    static long totalRent(List<Line> ls) {
        long s = 0;
        for (Line l : ls) s += rent(l);
        return s;
    }

    static long totalDep(List<Line> ls) {
        double s = 0;
        for (Line l : ls) if (l.dep > 0) s += l.dep;
        return Math.round(s);
    }

    static int maxDays(List<Line> ls) {
        int m = MIN_DAYS;
        for (Line l : ls) m = Math.max(m, l.days);
        return m;
    }

    static boolean sameDays(List<Line> ls) {
        for (Line l : ls) if (l.days != ls.get(0).days) return false;
        return true;
    }

    /** Доставку предлагаем, только если она есть у каждого инструмента заявки (решение владельца). */
    static boolean deliveryAllowed(List<Line> ls) {
        int tools = 0;
        for (Line l : ls) {
            if (l.srv) continue;
            tools++;
            if (!l.deliv) return false;
        }
        return tools > 0;
    }

    /** Инструменты без доставки — для подсказки «Доставка недоступна». */
    static String noDeliveryNames(List<Line> ls) {
        StringBuilder b = new StringBuilder();
        for (Line l : ls) {
            if (l.srv || l.deliv) continue;
            if (b.length() > 0) b.append(", ");
            b.append('«').append(l.name).append('»');
        }
        return b.toString();
    }

    static boolean anyDelivery(List<Line> ls) {
        for (Line l : ls) if (!l.srv && l.deliv) return true;
        return false;
    }

    /** Сколько цен за км складывает штатный расчёт (инструменты с доставкой и ценой). */
    static int rateCount(List<Line> ls) {
        int n = 0;
        for (Line l : ls) if (!l.srv && l.deliv && l.dprice > 0) n++;
        return n;
    }

    /** Штатный расчёт: км × сумма цен за км. Средняя доставка: та же сумма / число цен (прокат один — км общие). */
    static String averaged(String s, int n) {
        if (s == null || n <= 1) return s;
        int i = s.indexOf(IvaPatch.RUB);
        long r = IvaPatch.rub(s);
        if (i <= 0 || r <= 0) return s;
        return money(Math.round(r / (double) n)) + s.substring(i);
    }

    static long delivRub(String delivTxt) {
        if (delivTxt == null || delivTxt.indexOf(IvaPatch.RUB) < 0) return 0;
        long r = IvaPatch.rub(delivTxt);
        return r < 0 ? 0 : r;
    }

    /** p_tools: «Дрель (3 дня), Пила (5 дней)». */
    static String toolsText(List<Line> ls) {
        StringBuilder b = new StringBuilder();
        for (Line l : ls) {
            if (b.length() > 0) b.append(", ");
            b.append(l.name).append(" (").append(daysText(l.days)).append(')');
        }
        return b.toString();
    }

    static String lineText(Line l) {
        if (l.price > 0) return l.name + " — " + money(Math.round(l.price)) + IvaPatch.RUB + " × " + daysText(l.days) + " = " + money(rent(l)) + IvaPatch.RUB;
        return l.name + " — " + daysText(l.days) + ", цену уточнит прокат";
    }

    static String termText(List<Line> ls) {
        if (ls.isEmpty()) return "";
        return sameDays(ls) ? daysText(ls.get(0).days) : "у каждого инструмента свой, до " + daysText(maxDays(ls));
    }

    /** Чек в комментарий заявки — как штатный, но со сроком и суммой у каждого инструмента. */
    static String receipt(List<Line> ls, boolean deliv, String delivTxt) {
        StringBuilder b = new StringBuilder("— — — ЧЕК (предварительно) — — —\n");
        for (Line l : ls) b.append("• ").append(lineText(l)).append('\n');
        b.append("Срок: ").append(termText(ls)).append('\n');
        long rent = totalRent(ls), dep = totalDep(ls), dr = deliv ? delivRub(delivTxt) : 0;
        b.append("Аренда: ").append(money(rent)).append(IvaPatch.RUB).append('\n');
        if (dep > 0) b.append("Залог (возвращается при возврате и проверке инструмента): ").append(money(dep)).append(IvaPatch.RUB).append('\n');
        if (deliv) b.append("Доставка: ").append(delivTxt == null ? "рассчитаем при подтверждении" : delivTxt).append('\n');
        else b.append("Доставка: самовывоз\n");
        b.append("ИТОГО: ").append(money(rent + dep + dr)).append(IvaPatch.RUB);
        return b.toString();
    }

    /** Итог под формой — как штатный updateSummary, но аренда по срокам каждого инструмента. */
    static String summary(List<Line> ls, boolean deliv, String delivTxt, String pickup, String cmt) {
        if (ls.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        long rent = totalRent(ls), dep = totalDep(ls), dr = deliv ? delivRub(delivTxt) : 0;
        b.append(sameDays(ls) ? "Аренда (" + daysText(ls.get(0).days) + "): " : "Аренда (у каждого инструмента свой срок): ")
         .append(money(rent)).append(IvaPatch.RUB).append('\n');
        if (dep > 0) b.append("Залог (возвратный): ").append(money(dep)).append(IvaPatch.RUB).append('\n');
        if (deliv) b.append("Доставка: ").append(delivTxt == null ? "введите адрес — рассчитаем автоматически" : delivTxt).append('\n');
        else b.append("Самовывоз: ").append(pickup == null || pickup.isEmpty() ? "адрес уточнит прокат" : pickup).append('\n');
        b.append("ИТОГО: ").append(money(rent + dep + dr)).append(IvaPatch.RUB);
        if (deliv && dr <= 0) b.append(" + доставка");
        b.append('\n').append("Залог возвращается при возврате и проверке инструмента.");
        String c = cmt == null ? "" : cmt.trim();
        if (!c.isEmpty()) b.append("\nКомментарий: ").append(c);
        return b.toString();
    }

    static String cartOwner(List<Line> ls) { return ls.isEmpty() ? null : ls.get(0).owner; }

    /** Другой прокат? (пустой владелец штатно — владелец каталога) */
    static boolean conflict(String cartOwner, String newOwner) {
        if (cartOwner == null) return false;
        String n = newOwner == null || newOwner.trim().isEmpty() ? DEFAULT_OWNER : newOwner.trim();
        return !cartOwner.equalsIgnoreCase(n);
    }

    static int defaultDays(List<Line> ls) { return ls.isEmpty() ? MIN_DAYS : ls.get(ls.size() - 1).days; }

    // ── ФИО: из профиля по полям; если профиля нет — разбор одной строки имени ──

    static String low(String s) { return s == null ? "" : s.toLowerCase(new Locale("ru")).replace('ё', 'е'); }

    static boolean isPatronymic(String w) {
        String s = low(w);
        if (s.length() < 5) return false;
        String[] ends = {"ович", "евич", "ьич", "овна", "евна", "ична", "инична"};
        for (String e : ends) if (s.endsWith(e)) return true;
        return false;
    }

    static final String[] NAMES_LIKE_SURNAME = {"марина", "полина", "алина", "кристина", "ирина", "галина", "нина", "валентина",
        "екатерина", "ангелина", "карина", "арина", "дина", "регина", "элина", "эвелина", "зарина", "янина", "каролина",
        "константин", "валентин", "вениамин", "мартин", "владлен", "лев"};

    static boolean looksSurname(String w) {
        String s = low(w);
        if (s.length() < 3) return false;
        for (String n : NAMES_LIKE_SURNAME) if (s.equals(n)) return false;
        String[] ends = {"ов", "ова", "ев", "ева", "ин", "ина", "ын", "ына", "ский", "ская", "цкий", "цкая", "енко", "ко",
            "ук", "юк", "чук", "их", "ых", "ян", "дзе", "швили"};
        for (String e : ends) if (s.endsWith(e)) return true;
        return false;
    }

    /** «Петров Иван Сергеевич», «Иван Петров», «Иван» → {Фамилия, Имя, Отчество}. */
    static String[] splitName(String full) {
        String t = full == null ? "" : full.replace('\u00a0', ' ').trim();
        if (t.isEmpty() || "null".equals(t)) return new String[] {"", "", ""};
        String[] w = t.split("\\s+");
        if (w.length == 1) return new String[] {"", w[0], ""};
        if (w.length == 2) {
            if (isPatronymic(w[1])) return new String[] {"", w[0], w[1]};
            if (looksSurname(w[0]) && !looksSurname(w[1])) return new String[] {w[0], w[1], ""};
            return new String[] {w[1], w[0], ""};
        }
        if (w.length == 3 && !isPatronymic(w[2]) && isPatronymic(w[1])) return new String[] {w[2], w[0], w[1]};
        StringBuilder rest = new StringBuilder();
        for (int i = 2; i < w.length; i++) { if (rest.length() > 0) rest.append(' '); rest.append(w[i]); }
        return new String[] {w[0], w[1], rest.toString()};
    }

    static String clean(String s) {
        String t = s == null ? "" : s.trim();
        return "null".equals(t) ? "" : t;
    }

    static String[] fio(String surname, String given, String patronymic, String fullName) {
        String s = clean(surname), n = clean(given), p = clean(patronymic);
        if (!n.isEmpty()) return new String[] {s, n, p};
        return splitName(fullName);
    }

    /** Поля ещё не трогали: пусто, или штатно вписано всё имя в «Имя», или это наше прошлое заполнение. */
    static boolean pristine(String last, String name, String patr, String userName, String[] ours) {
        String l = clean(last), n = clean(name), p = clean(patr), u = clean(userName);
        if (ours != null && l.equals(ours[0]) && n.equals(ours[1]) && p.equals(ours[2])) return true;
        return l.isEmpty() && p.isEmpty() && (n.isEmpty() || n.equals(u));
    }

    // ───────────────────────── связь со штатными данными ─────────────────────────

    static Object get(Object o, String f) { return IvaClientPatch.get(o, f); }

    static String str(JSONObject j, String k) {
        if (j == null || !j.has(k) || j.isNull(k)) return "";
        String s = j.optString(k, "");
        return "null".equals(s) ? "" : s;
    }

    static double num(JSONObject j, String k) {
        if (j == null || !j.has(k) || j.isNull(k)) return 0;
        double d = j.optDouble(k, 0);
        return Double.isNaN(d) ? 0 : d;
    }

    @SuppressWarnings("unchecked")
    static LinkedHashMap<String, Object> selected(Activity a) {
        Object s = get(a, "selected");
        return s instanceof LinkedHashMap ? (LinkedHashMap<String, Object>) s : null;
    }

    static Line lineOf(String key, JSONObject j) {
        int days = j.optInt(DAYS_KEY, 0);
        Line l = line(key, j.optString("name", ""), num(j, "price"), num(j, "dep"), j.optBoolean("deliv", false),
            num(j, "dprice"), j.optBoolean("srv", false), j.optString("owner", DEFAULT_OWNER), days <= 0 ? MIN_DAYS : days);
        l.id = str(j, "id");
        return l;
    }

    static List<Line> lines(Activity a) {
        List<Line> out = new ArrayList<Line>();
        LinkedHashMap<String, Object> sel = selected(a);
        if (sel == null) return out;
        for (Map.Entry<String, Object> e : sel.entrySet())
            if (e.getValue() instanceof JSONObject) out.add(lineOf(e.getKey(), (JSONObject) e.getValue()));
        return out;
    }

    /** Количество больше не выбирается: у каждой строки qty = 1 и свой срок. */
    static void normalize(Activity a) {
        LinkedHashMap<String, Object> sel = selected(a);
        if (sel == null) return;
        for (Object o : sel.values()) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            try {
                if (j.optInt("qty", 1) != 1) j.put("qty", 1);
                int d = j.optInt(DAYS_KEY, 0);
                if (d <= 0) { Object nd = get(a, "days"); j.put(DAYS_KEY, clampDays(nd instanceof Integer ? (Integer) nd : MIN_DAYS)); }
                else if (d != clampDays(d)) j.put(DAYS_KEY, clampDays(d));
            } catch (Throwable ignore) {}
        }
    }

    /** Строка заявки из карточки каталога — те же ключи, что штатный «В заявку» (setupOrder$122) + id, img, срок. */
    static JSONObject itemFromTool(JSONObject t, int days) throws Exception {
        double price = num(t, "price");
        double dep = t.has("deposit") && !t.isNull("deposit") ? t.optDouble("deposit", 0) : Double.NaN;
        if (Double.isNaN(dep) || dep <= 0) dep = 5 * price;          // штатно: нет залога → 5 × цена
        JSONObject j = new JSONObject();
        j.put("key", "t" + str(t, "id"));
        j.put("name", t.optString("name", ""));
        j.put("price", price);
        j.put("dep", dep);
        j.put("qty", 1);
        j.put("srv", false);
        j.put("deliv", t.optBoolean("delivery", false));
        j.put("dprice", num(t, "delivery_price"));
        j.put("owner", t.optString("owner_email", DEFAULT_OWNER));
        j.put("id", str(t, "id"));
        j.put("img", str(t, "img"));
        j.put(DAYS_KEY, clampDays(days));
        return j;
    }

    static String pickupOf(Activity a, String owner) {
        try {
            Object m = get(a, "admAddr");
            if (m instanceof Map && owner != null) {
                for (Object k : ((Map<?, ?>) m).keySet()) {
                    if (k != null && owner.equalsIgnoreCase(String.valueOf(k))) {
                        Object v = ((Map<?, ?>) m).get(k);
                        String s = v == null ? "" : String.valueOf(v).trim();
                        if (!s.isEmpty() && !"null".equals(s)) return s;
                    }
                }
            }
        } catch (Throwable ignore) {}
        return owner == null || DEFAULT_OWNER.equalsIgnoreCase(owner) ? DEFAULT_PICKUP : "";
    }

    static void callNative(Activity a, String name) {
        try {
            Method m = a.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignore) {}
    }

    static void redraw(Activity a) {
        callNative(a, "renderOrderLines");
        callNative(a, "updateSummary");
    }

    static String text(Object v) {
        if (!(v instanceof TextView)) return "";
        CharSequence c = ((TextView) v).getText();
        return c == null ? "" : c.toString().trim();
    }

    static boolean deliveryChecked(Activity a) {
        Object rb = get(a, "rbDelivery");
        return rb instanceof RadioButton && ((RadioButton) rb).getVisibility() == View.VISIBLE && ((RadioButton) rb).isChecked();
    }

    static int dp(Activity a, float v) { return IvaSearchPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }
    static Drawable drawable(Activity a, String n) { return IvaSearchPatch.drawable(a, n); }

    // ───────────────────────── вход ─────────────────────────

    /** Из IvaPatch.onTab: перехват «В заявку» и «Отправить заявку», на вкладке заявки — свои строки и ФИО. */
    public static void onShowTab(Activity a, String tab) {
        act = new WeakReference<Activity>(a);
        try { hookButtons(a); } catch (Throwable ignore) {}
        if ("order".equals(tab)) {
            try { refresh(a); } catch (Throwable ignore) {}
            try { fillFio(a, true); } catch (Throwable ignore) {}
        }
        if ("orders".equals(tab)) {
            try { clientOrders(a); } catch (Throwable ignore) {}
        }
        if ("profile".equals(tab)) {
            try { profileArchiveRow(a); } catch (Throwable ignore) {}
        }
    }

    /** Из IvaPatch.onSummary (начало штатного updateSummary): свои строки и итог — сразу после него. */
    public static void onSummary(final Activity a) {
        act = new WeakReference<Activity>(a);
        if (posted) return;
        posted = true;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                posted = false;
                try { refresh(a); } catch (Throwable ignore) {}
            }
        });
    }

    /** Из IvaPatch.onPrice: доставка — средняя по ценам за км инструментов заявки (а не сумма). */
    public static String onPrice(String s) {
        try {
            Activity a = act == null ? null : act.get();
            if (a == null || s == null) return s;
            return averaged(s, rateCount(lines(a)));
        } catch (Throwable e) {
            return s;
        }
    }

    static void hookButtons(final Activity a) {
        Object send = get(a, "btnSend");
        if (send instanceof View && (hookedSend == null || hookedSend.get() != send)) {
            ((View) send).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { send(a); }
            });
            hookedSend = new WeakReference<View>((View) send);
        }
        int id = a.getResources().getIdentifier("detOrder", "id", a.getPackageName());
        View det = id == 0 ? null : a.findViewById(id);
        if (det != null && (hookedDet == null || hookedDet.get() != det)) {
            det.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onDetOrder(a, v); }
            });
            hookedDet = new WeakReference<View>(det);
        }
    }

    // ── «В заявку» в карточке: один прокат в заявке; ключ по настоящему id (штатно у всех "t0") ──

    static void onDetOrder(final Activity a, final View v) {
        try {
            Object d = get(a, "detItem");
            if (!(d instanceof JSONObject)) { nativeAdd(a, v); return; }
            final JSONObject det = (JSONObject) d;
            final boolean srv = Boolean.TRUE.equals(get(a, "detIsSrv"));
            String owner = srv ? DEFAULT_OWNER : det.optString("owner_email", DEFAULT_OWNER);
            LinkedHashMap<String, Object> sel = selected(a);
            String key = "t" + str(det, "id");
            if (!srv && sel != null && !str(det, "id").isEmpty() && sel.containsKey(key)) {
                Toast.makeText(a, "Этот инструмент уже в заявке", Toast.LENGTH_SHORT).show();
                showOrder(a);
                return;
            }
            List<Line> ls = lines(a);
            if (conflict(cartOwner(ls), owner)) {
                new AlertDialog.Builder(a)
                    .setTitle("Другой прокат")
                    .setMessage(REPLACE_MSG)
                    .setPositiveButton("Заменить", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface di, int w) {
                            LinkedHashMap<String, Object> s = selected(a);
                            if (s != null) s.clear();
                            addFromDetail(a, v, det, srv, MIN_DAYS);
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
                return;
            }
            addFromDetail(a, v, det, srv, defaultDays(ls));
        } catch (Throwable e) {
            nativeAdd(a, v);
        }
    }

    static void nativeAdd(Activity a, View v) {
        try {
            Method m = a.getClass().getDeclaredMethod("lambda$setupOrder$122$ru-prokatnvsk-app-MainActivity", View.class);
            m.setAccessible(true);
            m.invoke(a, v);
        } catch (Throwable ignore) {}
    }

    /** Штатное добавление (тост, перерисовка, переход в заявку), затем — настоящий ключ, id, картинка и срок. */
    static void addFromDetail(Activity a, View v, JSONObject det, boolean srv, int days) {
        nativeAdd(a, v);
        if (srv) return;
        try {
            LinkedHashMap<String, Object> sel = selected(a);
            String id = str(det, "id");
            if (sel == null || id.isEmpty()) return;
            String nativeKey = "t" + det.optLong("id");
            Object o = sel.get(nativeKey);
            if (!(o instanceof JSONObject)) return;
            JSONObject j = (JSONObject) o;
            String key = "t" + id;
            j.put("key", key);
            j.put("id", id);
            j.put("img", str(det, "img"));
            j.put("qty", 1);
            j.put(DAYS_KEY, clampDays(days));
            if (!key.equals(nativeKey)) { sel.remove(nativeKey); sel.put(key, j); }
            redraw(a);
            recalcDelivery(a);
        } catch (Throwable ignore) {}
    }

    static void showOrder(Activity a) {
        try {
            Method m = a.getClass().getDeclaredMethod("showTab", String.class);
            m.setAccessible(true);
            m.invoke(a, "order");
        } catch (Throwable ignore) {}
    }

    // ───────────────────────── вкладка заявки ─────────────────────────

    static LinearLayout container(Activity a) {
        Object ol = get(a, "orderLines");
        if (!(ol instanceof View)) return null;
        View orderLines = (View) ol;
        ViewGroup parent = (ViewGroup) orderLines.getParent();
        if (parent == null) return null;
        View c = parent.findViewWithTag(LINES_TAG);
        if (c instanceof LinearLayout) return (LinearLayout) c;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(LINES_TAG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 12);
        parent.addView(box, parent.indexOfChild(orderLines) + 1, lp);
        return box;
    }

    /** Штатный общий счётчик «Срок аренды (дней)» — его строка. */
    static View daysRow(Activity a) {
        Object dm = get(a, "dMinus");
        if (!(dm instanceof View)) return null;
        View p = (View) ((View) dm).getParent();
        return p == null ? null : (View) p.getParent();
    }

    static void refresh(Activity a) {
        LinearLayout box = container(a);
        if (box == null) return;
        normalize(a);
        List<Line> ls = lines(a);
        Object ol = get(a, "orderLines");
        if (ol instanceof View && ((View) ol).getVisibility() != View.GONE) ((View) ol).setVisibility(View.GONE);
        View dr = daysRow(a);
        if (dr != null && dr.getVisibility() != View.GONE) dr.setVisibility(View.GONE);
        IvaClientPatch.set(a, "days", Integer.valueOf(maxDays(ls)));    // p_days штатных мест — самый длинный срок
        hookButtons(a);
        render(a, box, ls);
        enforceDelivery(a, ls);
        Object sum = get(a, "ordSum");
        if (sum instanceof TextView) {
            boolean deliv = deliveryChecked(a);
            Object dt = get(a, "delivTxt");
            String owner = cartOwner(ls);
            String t = summary(ls, deliv, dt instanceof String ? (String) dt : null, pickupOf(a, owner), text(get(a, "oCmt")));
            if (!t.contentEquals(((TextView) sum).getText())) ((TextView) sum).setText(t);
        }
        // состав доставки поменялся, а адрес уже введён — пересчитать (штатно пересчёт только при вводе адреса)
        StringBuilder sig = new StringBuilder();
        for (Line l : ls) if (l.deliv && !l.srv) sig.append(l.key).append(';');
        String s = sig.toString();
        if (!s.equals(delivSig)) {
            boolean first = delivSig.isEmpty() && s.isEmpty();
            delivSig = s;
            if (!first) recalcDelivery(a);
        }
    }

    static void recalcDelivery(Activity a) {
        if (deliveryChecked(a) && !text(get(a, "oAddr")).isEmpty()) callNative(a, "calcDelivery");
    }

    /** Есть инструмент без доставки — только самовывоз (как просил владелец) и короткая подсказка. */
    static void enforceDelivery(Activity a, List<Line> ls) {
        Object rb = get(a, "rbDelivery"), rp = get(a, "rbPickup"), rg = get(a, "rgMethod");
        if (!(rb instanceof RadioButton)) return;
        RadioButton d = (RadioButton) rb;
        boolean allowed = deliveryAllowed(ls);
        if (!allowed) {
            if (d.getVisibility() != View.GONE) d.setVisibility(View.GONE);
            if (d.isChecked() && rp instanceof RadioButton) ((RadioButton) rp).setChecked(true);
        }
        if (!(rg instanceof View)) return;
        ViewGroup parent = (ViewGroup) ((View) rg).getParent();
        if (parent == null) return;
        TextView hint = (TextView) parent.findViewWithTag(HINT_TAG);
        boolean show = !allowed && anyDelivery(ls);
        if (hint == null) {
            if (!show) return;
            hint = new TextView(a);
            hint.setTag(HINT_TAG);
            hint.setTextSize(13f);
            hint.setTextColor(color(a, "gray", 0xff9aa1b0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 4);
            parent.addView(hint, parent.indexOfChild((View) rg) + 1, lp);
        }
        if (show) {
            String t = "Доставка недоступна: " + noDeliveryNames(ls) + " — только самовывоз.";
            if (!t.contentEquals(hint.getText())) hint.setText(t);
        }
        int vis = show ? View.VISIBLE : View.GONE;
        if (hint.getVisibility() != vis) hint.setVisibility(vis);
    }

    static void render(final Activity a, LinearLayout box, List<Line> ls) {
        box.removeAllViews();
        if (ls.isEmpty()) { box.setVisibility(View.GONE); return; }
        box.setVisibility(View.VISIBLE);
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        for (final Line l : ls) {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            Drawable bg = drawable(a, "bg_card");
            if (bg != null) card.setBackground(bg);
            card.setPadding(dp(a, 14), dp(a, 10), dp(a, 6), dp(a, 10));

            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(a);
            name.setText(l.price > 0 ? l.name + " — " + money(Math.round(l.price)) + IvaPatch.RUB + "/сут" : l.name);
            name.setTextColor(cText);
            name.setTextSize(15f);
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView del = new TextView(a);
            del.setText("✕");
            del.setTextSize(18f);
            del.setTextColor(cGray);
            del.setGravity(Gravity.CENTER);
            del.setContentDescription("Убрать из заявки");
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    LinkedHashMap<String, Object> s = selected(a);
                    if (s != null) s.remove(l.key);
                    redraw(a);
                }
            });
            top.addView(del, new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)));
            card.addView(top);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 4), dp(a, 8), 0);
            TextView lbl = new TextView(a);
            lbl.setText(l.price > 0 ? "Срок аренды · " + money(rent(l)) + IvaPatch.RUB : "Срок аренды");
            lbl.setTextColor(cGray);
            lbl.setTextSize(14f);
            row.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(stepper(a, l, cText, cOrange));
            card.addView(row);

            if (!l.srv && !l.deliv) {
                TextView nd = new TextView(a);
                nd.setText("Без доставки — только самовывоз");
                nd.setTextColor(cGray);
                nd.setTextSize(12f);
                card.addView(nd);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.bottomMargin = dp(a, 8);
            box.addView(card, clp);
        }
        TextView add = new TextView(a);
        add.setText(ADD_TITLE);
        add.setTextColor(cOrange);
        add.setTextSize(15f);
        add.setTypeface(null, android.graphics.Typeface.BOLD);
        add.setGravity(Gravity.CENTER);
        Drawable bg = drawable(a, "bg_card");
        if (bg != null) add.setBackground(bg);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPicker(a); }
        });
        box.addView(add, new LinearLayout.LayoutParams(-1, dp(a, 48)));
    }

    /** Свой срок у инструмента — тот же вид, что штатный счётчик (bg_stepper, − N +), 1…90. */
    static View stepper(final Activity a, final Line l, int cText, int cOrange) {
        LinearLayout st = new LinearLayout(a);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = drawable(a, "bg_stepper");
        if (bg != null) st.setBackground(bg);
        TextView minus = new TextView(a), val = new TextView(a), plus = new TextView(a);
        minus.setText("−");
        plus.setText("+");
        for (TextView b : new TextView[] {minus, plus}) {
            b.setTextSize(22f);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
            b.setTextColor(cOrange);
            b.setGravity(Gravity.CENTER);
        }
        val.setText(daysText(l.days));
        val.setTextSize(15f);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        val.setTextColor(cText);
        val.setGravity(Gravity.CENTER);
        minus.setContentDescription("Меньше дней");
        plus.setContentDescription("Больше дней");
        minus.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setDays(a, l.key, l.days - 1); } });
        plus.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setDays(a, l.key, l.days + 1); } });
        st.addView(minus, new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)));
        st.addView(val, new LinearLayout.LayoutParams(dp(a, 72), -2));
        st.addView(plus, new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)));
        return st;
    }

    static void setDays(Activity a, String key, int days) {
        try {
            LinkedHashMap<String, Object> s = selected(a);
            Object o = s == null ? null : s.get(key);
            if (!(o instanceof JSONObject)) return;
            int d = clampDays(days);
            if (((JSONObject) o).optInt(DAYS_KEY, 0) == d) return;
            ((JSONObject) o).put(DAYS_KEY, d);
            callNative(a, "updateSummary");
        } catch (Throwable ignore) {}
    }

    // ── «+ Добавить инструмент»: карточки этого же проката (штатный feedCard) ──

    static void openPicker(final Activity a) {
        try {
            final List<Line> ls = lines(a);
            final String owner = cartOwner(ls);
            if (owner == null) return;
            final boolean deliv = deliveryChecked(a);
            ArrayList<JSONObject> list = new ArrayList<JSONObject>();
            int hidden = 0;
            LinkedHashMap<String, Object> sel = selected(a);
            for (Object o : IvaSearchPatch.allTools(a)) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject t = (JSONObject) o;
                String id = str(t, "id");
                if (id.isEmpty() || !owner.equalsIgnoreCase(t.optString("owner_email", DEFAULT_OWNER))) continue;
                if ("rented".equals(t.optString("t_status", ""))) continue;          // как штатная лента
                if (sel != null && sel.containsKey("t" + id)) continue;
                if (deliv && !t.optBoolean("delivery", false)) { hidden++; continue; }
                list.add(t);
            }
            showPicker(a, list, deliv && hidden > 0, defaultDays(ls));
        } catch (Throwable e) {
            try { Toast.makeText(a, "Не удалось открыть список инструментов", Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        }
    }

    static void showPicker(final Activity a, List<JSONObject> list, boolean hint, final int days) throws Exception {
        final int cBg = color(a, "bg", 0xff101217), cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 4), 0, dp(a, 16), 0);
        TextView back = new TextView(a);
        back.setText("←");
        back.setTextSize(24f);
        back.setTextColor(cText);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Назад");
        bar.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        TextView title = new TextView(a);
        title.setText("Добавить инструмент");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(cText);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(a, 56)));

        ScrollView scroll = new ScrollView(a);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 24));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView sub = new TextView(a);
        sub.setText("Инструменты этого же проката — в одну заявку.");
        sub.setTextSize(13f);
        sub.setTextColor(cGray);
        sub.setPadding(dp(a, 4), 0, dp(a, 4), dp(a, 8));
        body.addView(sub);
        if (hint) {
            TextView h = new TextView(a);
            h.setText(PICK_HINT);
            h.setTextSize(13f);
            h.setTextColor(cGray);
            h.setPadding(dp(a, 4), 0, dp(a, 4), dp(a, 10));
            body.addView(h);
        }

        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });

        if (list.isEmpty()) {
            TextView e = new TextView(a);
            e.setText(hint ? "Других инструментов с доставкой у этого проката нет." : "Других инструментов у этого проката нет.");
            e.setTextSize(14f);
            e.setTextColor(cGray);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(a, 24), 0, 0);
            body.addView(e);
        }
        Method fc = a.getClass().getDeclaredMethod("feedCard", JSONObject.class, boolean.class);
        fc.setAccessible(true);
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            final JSONObject t = list.get(i);
            if (i % 2 == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            View card = (View) fc.invoke(a, t, Boolean.FALSE);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    addFromPicker(a, t, days);
                }
            });
            row.addView(card);
        }
        if (row != null && list.size() % 2 == 1) row.addView(new Space(a), new LinearLayout.LayoutParams(0, 1, 1f));

        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(cBg));
        }
        d.show();
    }

    static void addFromPicker(Activity a, JSONObject t, int days) {
        try {
            LinkedHashMap<String, Object> sel = selected(a);
            if (sel == null) return;
            JSONObject j = itemFromTool(t, days);
            sel.put(j.getString("key"), j);
            Toast.makeText(a, "Добавлено в заявку", Toast.LENGTH_SHORT).show();
            redraw(a);
            recalcDelivery(a);
        } catch (Throwable ignore) {}
    }

    // ── ФИО по полям ──

    static void fillFio(final Activity a, boolean retry) {
        EditText last = (EditText) get(a, "oLast"), name = (EditText) get(a, "oName"), patr = (EditText) get(a, "oPatr");
        if (last == null || name == null || patr == null) return;
        Object un = get(a, "userName"), ue = get(a, "userEmail");
        String userName = un == null ? "" : String.valueOf(un);
        if (!pristine(text(last), text(name), text(patr), userName, lastFill)) return;
        JSONObject p = null;
        boolean loaded = IvaClientPatch.clientProfileLoaded && ue != null
            && String.valueOf(ue).equalsIgnoreCase(IvaClientPatch.clientProfileEmail);
        if (loaded) p = IvaClientPatch.clientProfile;
        String[] f = p == null ? splitName(userName)
            : fio(p.optString("surname", ""), p.optString("given_name", ""), p.optString("patronymic", ""), userName);
        if (f[1].isEmpty()) return;
        if (!f[0].equals(text(last))) last.setText(f[0]);
        if (!f[1].equals(text(name))) name.setText(f[1]);
        if (!f[2].equals(text(patr))) patr.setText(f[2]);
        lastFill = f;
        if (!loaded && retry && IvaClientPatch.isClient(a)) {
            try { IvaClientPatch.loadClientProfile(a, false); } catch (Throwable ignore) {}
            Handler h = new Handler(Looper.getMainLooper());
            Runnable again = new Runnable() { @Override public void run() { try { fillFio(a, false); } catch (Throwable ignore) {} } };
            h.postDelayed(again, 1500);
            h.postDelayed(again, 4000);
        }
    }

    // ── «Отправить заявку»: одна заявка, свой срок у каждого инструмента ──

    static void toast(Activity a, String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }

    static void send(final Activity a) {
        try {
            LinkedHashMap<String, Object> sel = selected(a);
            Object token = get(a, "accessToken");
            if (sel == null || sel.isEmpty() || !(token instanceof String) || ((String) token).isEmpty()) {
                callNative(a, "sendOrder");                       // штатные «Сначала выберите…» / «Войдите…»
                return;
            }
            if (sending) return;
            normalize(a);
            List<Line> ls = lines(a);
            String name = text(get(a, "oName")), phone = text(get(a, "oPhone"));
            if (name.isEmpty()) { toast(a, "Укажите имя"); return; }
            if (phone.replaceAll("\\D", "").length() < 10) { toast(a, "Укажите телефон"); return; }
            boolean deliv = deliveryChecked(a) && deliveryAllowed(ls);
            String addr = text(get(a, "oAddr"));
            if (deliv && addr.isEmpty()) { toast(a, "Укажите адрес доставки"); return; }
            String fullName = (text(get(a, "oLast")) + " " + name + " " + text(get(a, "oPatr"))).trim().replaceAll(" +", " ");
            Object dt = get(a, "delivTxt");
            String delivTxt = dt instanceof String ? (String) dt : null;
            String receipt = receipt(ls, deliv, delivTxt);
            String cmt = text(get(a, "oCmt"));
            String owner = cartOwner(ls);
            String pickup = pickupOf(a, owner);
            String method = deliv
                ? "Доставка: " + addr + (delivTxt != null ? " — " + delivTxt : "")
                : "Самовывоз, " + (pickup.isEmpty() ? "адрес уточнит прокат" : pickup);
            Object ue = get(a, "userEmail");
            final JSONObject body = new JSONObject();
            body.put("p_name", fullName);
            body.put("p_phone", phone);
            body.put("p_tools", toolsText(ls));
            body.put("p_get_method", method);
            body.put("p_address", deliv ? addr : "");
            body.put("p_comment", cmt.isEmpty() ? receipt : cmt + "\n\n" + receipt);
            body.put("p_user_email", ue == null ? "" : String.valueOf(ue));
            body.put("p_days", maxDays(ls));
            final TextView btn = (TextView) get(a, "btnSend");
            if (btn != null) btn.setText("Отправляем…");
            sending = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    String err = null;
                    try {
                        String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/submit_order", "POST", body.toString(), true);
                        if (r == null) err = "нет ответа сервера";
                        else if (!r[0].startsWith("2")) err = IvaClientPatch.serverMessage(r[0], r[1]);
                    } catch (Throwable e) {
                        err = e.getMessage() == null ? "нет связи с сервером" : e.getMessage();
                    }
                    final String fe = err;
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            sending = false;
                            if (fe == null) sent(a);
                            else failed(a, fe);
                        }
                    });
                }
            }).start();
        } catch (Throwable e) {
            sending = false;
            callNative(a, "sendOrder");
        }
    }

    /** Успех — штатный lambda$sendOrder$126: кнопка, очистка заявки, экран «Заявка отправлена». */
    static void sent(Activity a) {
        lastFill = null;
        delivSig = "";
        try {
            Method m = a.getClass().getDeclaredMethod("lambda$sendOrder$126$ru-prokatnvsk-app-MainActivity");
            m.setAccessible(true);
            m.invoke(a);
            return;
        } catch (Throwable ignore) {}
        Object btn = get(a, "btnSend");
        if (btn instanceof TextView) ((TextView) btn).setText("Отправить заявку");
        LinkedHashMap<String, Object> sel = selected(a);
        if (sel != null) sel.clear();
        try {
            Method m = a.getClass().getDeclaredMethod("showScreen", View.class);
            m.setAccessible(true);
            m.invoke(a, get(a, "scrSuccess"));
        } catch (Throwable ignore) {}
    }

    static void failed(Activity a, String err) {
        Object btn = get(a, "btnSend");
        if (btn instanceof TextView) ((TextView) btn).setText("Отправить заявку");
        String msg = err;
        try {
            Method m = a.getClass().getDeclaredMethod("humanError", String.class);
            m.setAccessible(true);
            Object h = m.invoke(a, err);
            if (h instanceof String && !((String) h).isEmpty()) msg = (String) h;
        } catch (Throwable ignore) {}
        Toast.makeText(a, "Не удалось отправить: " + msg, Toast.LENGTH_LONG).show();
    }


    // ───────────────────────── 10.62–10.63: заявки клиента ─────────────────────────
    // 10.62: заявки нажимаются → шторка 85% со всеми данными. 10.63 (просьба и решения владельца 14.10.2026):
    //  • свой список вместо штатного (штатный renderOrders продолжает грузиться, но у клиента скрыт) — без мигания
    //    убранных в архив; не загрузилось — штатный список, как в 10.62 (нажатие по карточке, «Подробнее ›»);
    //  • «Архив заявок»: в базе (app_order_archived, SQL у владельца); убрать — кнопкой «В архив» в подробностях или
    //    долгим нажатием на карточку, только закрытые заявки; в «Кабинете» — пункт «Архив заявок», заявки там тоже
    //    нажимаются, есть «Вернуть из архива»;
    //  • в подробностях — данные заявки и проката (название, контактное лицо, телефон, адрес выдачи, часы работы),
    //    а не самого клиента (его имя, телефон и адрес не показываются).
    // У админа — ничего не меняется (штатный список).
    static final String ORD_PATH = "/rest/v1/orders?select=*&order=created_at.desc&limit=100";
    static final String ORD_PATH_PARTS = "/rest/v1/orders?select=*,order_parts(owner_email,status)&order=created_at.desc&limit=100";
    static final String ORD_PARTS_RPC = "/rest/v1/rpc/iva_my_order_parts";
    static final String ARCH_PATH = "/rest/v1/app_order_archived?select=order_id&limit=1000";
    static final String ARCH_RPC = "/rest/v1/rpc/iva_archive_order";
    static final String PROKAT_PATH = "/rest/v1/admins?select=email,full_name,company,address,phone,work_hours&email=in.";
    static final String ORD_KEY = "iva_ord:", ORD_MORE_TAG = "iva_ord_more", ORD_MORE = "Подробнее ›";
    static final String ORD_LIST_TAG = "iva_ord_list", ARCH_ROW_TAG = "iva_arch_row";
    static final String[] ORD_COLS = {"id", "created_at", "status", "tools", "days", "get_method", "comment"};
    static final String ORD_OWNERS = "_owners", ORD_PARTS = "_parts";
    static final long ORD_ATTACH_MS = 250, ORD_REFETCH_MS = 5000;
    static final float ORD_SHEET = 0.85f;
    static final String ARCH_TITLE = "Архив заявок", ARCH_SUB = "Закрытые заявки, убранные из «Заявок»";
    static final String ARCH_ASK = "Убрать заявку в архив?";
    static final String ARCH_ASK_TEXT = "Она пропадёт из «Заявок». Найти её можно в «Кабинете» → «Архив заявок».";
    static final String ARCH_BACK_ASK = "Вернуть заявку из архива?";
    static final String ARCH_ON = "В архив", ARCH_OFF = "Вернуть из архива";
    static final String ARCH_ONLY_CLOSED = "В архив можно убрать только закрытую заявку";
    static final String ARCH_NO_DB = "Архив заявок заработает после обновления базы";
    static final String ARCH_EMPTY = "В архиве пока пусто.\n\nЗакрытую заявку можно убрать сюда из «Заявок» — долгим нажатием на неё или кнопкой «В архив» в подробностях.";
    static final String ORD_EMPTY = "Заявок пока нет.\nВыберите инструмент и отправьте первую заявку!";
    static final String ORD_ALL_ARCHIVED = "Все заявки в архиве.\nОни в «Кабинете» → «Архив заявок».";
    static WeakReference<View> ordHooked = new WeakReference<View>(null);
    static List<Map<String, String>> ordRows = new ArrayList<Map<String, String>>();
    static final java.util.Set<String> ordArchived = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    static final Map<String, Map<String, String>> ordProkat = java.util.Collections.synchronizedMap(new java.util.HashMap<String, Map<String, String>>());
    static String ordFor = "";
    static long ordAt = 0;
    static boolean ordLoading = false, ordReady = false, ordFailed = false, archMissing = false;
    static Handler ordH;                 // создаётся при первом обращении (не в статическом блоке класса)

    static Handler ordH() {
        if (ordH == null) ordH = new Handler(Looper.getMainLooper());
        return ordH;
    }
    static Runnable ordRun;
    static Dialog ordDialog, archDialog;
    static LinearLayout archList;

    static void clientOrders(final Activity a) {
        Object o = get(a, "ordersList");
        if (o instanceof LinearLayout && ordHooked.get() != o) {
            // 10.67: и у админа — штатный renderAdmOrders перестроил список → наши картинки (ordRender → admPics)
            ((LinearLayout) o).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { ordSchedule(a); }
                @Override public void onChildViewRemoved(View parent, View child) {}
            });
            ordHooked = new WeakReference<View>((View) o);
        }
        if (!(o instanceof LinearLayout) || !IvaClientPatch.isClient(a)) { try { ordRender(a); } catch (Throwable ignore) {} return; }
        ordSafeRender(a);
        ordFetch(a);   // раздел открыли — свежие статусы (штатный loadOrders тоже грузит заново)
    }

    /** Свой раздел не построился (любая ошибка) — штатный список, и его карточки всё равно нажимаются. */
    static void ordSafeRender(Activity a) {
        try { ordRender(a); }
        catch (Throwable t) {
            try { View sc = idView(a, "ordersScroll"); if (sc != null) sc.setVisibility(View.VISIBLE); } catch (Throwable ignore) {}
            try {
                Object po = get(a, "pageOrders");
                View root = po instanceof View ? ((View) po).findViewWithTag(ORD_ROOT_TAG) : null;
                if (root != null) root.setVisibility(View.GONE);
            } catch (Throwable ignore) {}
            try { ordAttach(a); } catch (Throwable ignore) {}
        }
    }

    static void ordSchedule(final Activity a) {
        if (ordRun != null) ordH().removeCallbacks(ordRun);
        ordRun = new Runnable() { @Override public void run() { ordSafeRender(a); } };
        ordH().postDelayed(ordRun, ORD_ATTACH_MS);
    }

    static String ordMe(Activity a) {
        Object e = get(a, "userEmail");
        return e instanceof String ? ((String) e).trim().toLowerCase(Locale.ROOT) : "";
    }

    static boolean ordMine(Activity a) { return ordReady && ordMe(a).equals(ordFor); }

    static void ordFetch(final Activity a) {
        if (ordLoading) return;
        ordLoading = true;
        final String me = ordMe(a);
        Object re = get(a, "userEmail");
        final String raw = re instanceof String ? ((String) re).trim() : "";
        new Thread(new Runnable() {
            @Override public void run() {
                List<Map<String, String>> rows = null;
                java.util.Set<String> arch = null;
                boolean noArch = false;
                try {
                    String[] r = IvaClientPatch.apiCall(a, ordPath(ORD_PATH_PARTS, raw), "GET", null, true);
                    if (r == null || !"200".equals(r[0])) r = IvaClientPatch.apiCall(a, ordPath(ORD_PATH, raw), "GET", null, true);
                    if (r != null && r.length > 1 && "200".equals(r[0])) rows = ordParse(r[1]);
                } catch (Throwable ignore) {}
                if (rows != null) {
                    try {   // части заявок (прокат и «Выдал/Вернули») — функцией из SQL 10.63, если её ещё нет — как получилось выше
                        String[] r = IvaClientPatch.apiCall(a, ORD_PARTS_RPC, "POST", "{}", true);
                        if (r != null && r.length > 1 && "200".equals(r[0])) ordMergeParts(rows, ordParseParts(r[1]));
                    } catch (Throwable ignore) {}
                    try {
                        String[] r = IvaClientPatch.apiCall(a, ARCH_PATH, "GET", null, true);
                        if (r != null && "404".equals(r[0])) noArch = true;
                        else if (r != null && r.length > 1 && "200".equals(r[0])) arch = ordParseArch(r[1]);
                    } catch (Throwable ignore) {}
                    ordLoadProkat(a, ordOwnersOf(rows));
                }
                final List<Map<String, String>> got = rows;
                final java.util.Set<String> fa = arch;
                final boolean fna = noArch;
                ordH().post(new Runnable() {
                    @Override public void run() {
                        ordLoading = false;
                        ordAt = System.currentTimeMillis();
                        if (got != null) {
                            ordRows = got; ordFor = me; ordReady = true; ordFailed = false;
                            archMissing = fna;
                            if (fa != null) { synchronized (ordArchived) { ordArchived.clear(); ordArchived.addAll(fa); } }
                            else if (fna) ordArchived.clear();
                        } else if (!me.equals(ordFor) || !ordReady) ordFailed = true;
                        ordSafeRender(a);
                        try { archRender(a); } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static List<Map<String, String>> ordParse(String body) throws Exception {
        JSONArray arr = new JSONArray(body == null ? "[]" : body.trim());
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Map<String, String> m = new LinkedHashMap<String, String>();
            for (String k : ORD_COLS) m.put(k, o.isNull(k) ? "" : String.valueOf(o.opt(k)));
            List<String> owners = new ArrayList<String>(), parts = new ArrayList<String>();
            JSONArray ps = o.optJSONArray("order_parts");
            for (int k = 0; ps != null && k < ps.length(); k++) {
                JSONObject p = ps.optJSONObject(k);
                if (p == null) continue;
                owners.add(p.isNull("owner_email") ? "" : p.optString("owner_email", ""));
                parts.add(p.isNull("status") ? "" : p.optString("status", ""));
            }
            m.put(ORD_OWNERS, ordJoin(owners));
            m.put(ORD_PARTS, ordJoinAll(parts));
            out.add(m);
        }
        return out;
    }

    /** Ответ iva_my_order_parts → id заявки → [владельцы, статусы частей]. */
    static Map<String, String[]> ordParseParts(String body) throws Exception {
        JSONArray arr = new JSONArray(body == null ? "[]" : body.trim());
        Map<String, List<String>> ow = new LinkedHashMap<String, List<String>>(), st = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            String id = p.isNull("order_id") ? "" : String.valueOf(p.opt("order_id"));
            if (id.isEmpty()) continue;
            if (!ow.containsKey(id)) { ow.put(id, new ArrayList<String>()); st.put(id, new ArrayList<String>()); }
            ow.get(id).add(p.isNull("owner_email") ? "" : p.optString("owner_email", ""));
            st.get(id).add(p.isNull("status") ? "" : p.optString("status", ""));
        }
        Map<String, String[]> out = new LinkedHashMap<String, String[]>();
        for (String id : ow.keySet()) out.put(id, new String[]{ordJoin(ow.get(id)), ordJoinAll(st.get(id))});
        return out;
    }

    /** Части из функции базы точнее, чем видимые по RLS: подставляем их в заявки. */
    static void ordMergeParts(List<Map<String, String>> rows, Map<String, String[]> parts) {
        if (rows == null || parts == null) return;
        for (Map<String, String> r : rows) {
            String[] p = parts.get(r.get("id"));
            if (p == null) continue;
            r.put(ORD_OWNERS, p[0]);
            r.put(ORD_PARTS, p[1]);
        }
    }

    static java.util.Set<String> ordParseArch(String body) throws Exception {
        JSONArray arr = new JSONArray(body == null ? "[]" : body.trim());
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String id = o == null || o.isNull("order_id") ? "" : String.valueOf(o.opt("order_id")).trim();
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** Почты без повторов и пустых, нижний регистр, через запятую. */
    static String ordJoin(List<String> xs) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<String>();
        if (xs != null) for (String x : xs) { String c = x == null ? "" : x.trim().toLowerCase(Locale.ROOT); if (!c.isEmpty() && c.indexOf(',') < 0) s.add(c); }
        StringBuilder b = new StringBuilder();
        for (String x : s) { if (b.length() > 0) b.append(','); b.append(x); }
        return b.toString();
    }

    /** Статусы частей — все по порядку (пустой = new), через запятую. */
    static String ordJoinAll(List<String> xs) {
        StringBuilder b = new StringBuilder();
        if (xs != null) for (String x : xs) {
            String c = x == null ? "" : x.trim().toLowerCase(Locale.ROOT);
            if (b.length() > 0) b.append(',');
            b.append(c.isEmpty() ? "new" : c);
        }
        return b.toString();
    }

    static java.util.Set<String> ordOwnersOf(List<Map<String, String>> rows) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (rows != null) for (Map<String, String> r : rows) for (String e : ordSplit(r.get(ORD_OWNERS))) out.add(e);
        return out;
    }

    static List<String> ordSplit(String csv) {
        List<String> out = new ArrayList<String>();
        for (String p : (csv == null ? "" : csv).split(",")) { String c = p.trim(); if (!c.isEmpty()) out.add(c); }
        return out;
    }

    /** Данные прокатов (как на странице проката в приложении: admins), в фоне; уже загруженные не спрашиваем. */
    static void ordLoadProkat(Activity a, java.util.Collection<String> emails) {
        List<String> need = new ArrayList<String>();
        for (String e : emails) if (!ordProkat.containsKey(e)) need.add(e);
        if (need.isEmpty()) return;
        try {
            StringBuilder q = new StringBuilder("(");
            for (String e : need) {
                if (q.length() > 1) q.append(',');
                q.append("%22").append(java.net.URLEncoder.encode(e, "UTF-8")).append("%22");
            }
            q.append(')');
            String[] r = IvaClientPatch.apiCall(a, PROKAT_PATH + q, "GET", null, true);
            if (r == null || r.length < 2 || !"200".equals(r[0])) return;
            JSONArray arr = new JSONArray(r[1] == null ? "[]" : r[1].trim());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Map<String, String> m = new LinkedHashMap<String, String>();
                for (String k : new String[]{"email", "full_name", "company", "address", "phone", "work_hours"})
                    m.put(k, o.isNull(k) ? "" : o.optString(k, ""));
                ordProkat.put(m.get("email").trim().toLowerCase(Locale.ROOT), m);
            }
        } catch (Throwable ignore) {}
    }

    /** Штатная дата карточки (niceDate). */
    static String ordNice(Activity a, String at) {
        try {
            Method m = a.getClass().getDeclaredMethod("niceDate", String.class);
            m.setAccessible(true);
            return String.valueOf(m.invoke(a, at));
        } catch (Throwable e) { return at == null ? "" : at; }
    }

    /** Ключ карточки: дата, инструменты и номер среди одинаковых (две заявки в одну минуту на одно и то же). */
    static String ordKey(String date, String tools, int nth) {
        return ORD_KEY + nth + "\u0001" + (date == null ? "" : date.trim()) + "\u0001" + (tools == null ? "" : tools.trim());
    }

    /** Заявка по ключу карточки: nth-я заявка с такой же датой и инструментами; нет — -1. */
    static int ordFind(List<String> dates, List<String> tools, String key) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < dates.size() && i < tools.size(); i++) {
            String base = ordKey(dates.get(i), tools.get(i), 0);
            Integer c = counts.get(base);
            int nth = c == null ? 0 : c;
            counts.put(base, nth + 1);
            if (ordKey(dates.get(i), tools.get(i), nth).equals(key)) return i;
        }
        return -1;
    }

    /**
     * Статус заявки. «Выдал»/«Вернули» прокат отмечает в части заявки (order_parts.status), поэтому если части
     * известны — по ним: хоть одна new → «new»; иначе хоть одна rented → «rented»; иначе (все закрыты) — «closed».
     * Частей нет — статус самой заявки (пусто = new).
     */
    static String ordEffStatus(String orderStatus, String partsCsv) {
        List<String> ps = ordSplit(partsCsv);
        if (ps.isEmpty()) {
            String s = orderStatus == null ? "" : orderStatus.trim().toLowerCase(Locale.ROOT);
            return s.isEmpty() ? "new" : s;
        }
        if (ps.contains("new")) return "new";
        if (ps.contains("rented")) return "rented";
        return "closed";
    }

    /** Статус словами как на сайте: new — «Новая», rented — «В аренде», иначе — «Закрыта». */
    static String ordStatus(String st) {
        String s = st == null || st.trim().isEmpty() ? "new" : st.trim();
        return "new".equals(s) ? "Новая" : "rented".equals(s) ? "В аренде" : "Закрыта";
    }

    static String ordStatusOf(Map<String, String> r) { return ordEffStatus(r.get("status"), r.get(ORD_PARTS)); }

    /** В архив — только закрытые (решение владельца 14.10.2026). То же правило в iva_archive_order. */
    static boolean ordClosed(Map<String, String> r) { return "Закрыта".equals(ordStatus(ordStatusOf(r))); }

    /** Строки шторки о заявке; данных клиента (имя, телефон, адрес) нет — решение владельца 14.10.2026. */
    static List<String[]> ordFields(Map<String, String> r, String nice) {
        List<String[]> out = new ArrayList<String[]>();
        ordAdd(out, "Дата", nice == null || nice.trim().isEmpty() ? r.get("created_at") : nice);
        if (!r.containsKey(ORD_PARTIAL)) out.add(new String[]{"Статус", ordStatus(ordStatusOf(r))});   // по карточке статус не известен
        ordAdd(out, "Инструменты", r.get("tools"));
        String days = r.get("days") == null ? "" : r.get("days").trim();
        ordAdd(out, "Срок", days.matches("\\d+") ? (days.equals("0") ? "" : days + " дн.") : days);
        ordAdd(out, "Получение", r.get("get_method"));
        ordAdd(out, "Комментарий", r.get("comment"));
        ordAdd(out, "Номер заявки", r.get("id"));
        return out;
    }

    /** Строки о прокате: название (компания, иначе имя), контактное лицо, телефон, адрес выдачи, часы работы. */
    static List<String[]> ordProkatFields(Map<String, String> ad) {
        List<String[]> out = new ArrayList<String[]>();
        if (ad == null) return out;
        String company = ordVal(ad.get("company")), name = ordVal(ad.get("full_name"));
        ordAdd(out, "Прокат", company.isEmpty() ? name : company);
        if (!company.isEmpty() && !name.isEmpty() && !name.equalsIgnoreCase(company)) ordAdd(out, "Контактное лицо", name);
        ordAdd(out, "Телефон", ad.get("phone"));
        ordAdd(out, "Адрес выдачи", ad.get("address"));
        ordAdd(out, "Часы работы", ad.get("work_hours"));
        return out;
    }

    static String ordVal(String v) { return v == null || "null".equals(v.trim()) ? "" : v.trim(); }

    static void ordAdd(List<String[]> out, String label, String v) {
        if (v != null && !v.trim().isEmpty() && !"null".equals(v.trim())) out.add(new String[]{label, v.trim()});
    }

    /**
     * Названия инструментов из текста заявки: «Дрель (3 дн.), Пила ×2» → [дрель, пила]. Нужны, только если части
     * заявки не видны: прокат тогда находится по каталогу (у инструмента есть владелец).
     */
    static List<String> ordToolNames(String tools) {
        List<String> out = new ArrayList<String>();
        for (String p : (tools == null ? "" : tools).split("[,;\\n]")) {
            String n = p.trim();
            n = n.replaceAll("\\s*\\([^()]*\\)\\s*$", "");
            n = n.replaceAll("\\s*[\u00d7xх*]\\s*\\d+\\s*$", "");
            n = n.trim().toLowerCase(Locale.ROOT);
            if (n.length() >= 2 && !out.contains(n)) out.add(n);
        }
        return out;
    }

    /** Владельцы заявки: по частям; не видны — по каталогу (тот же инструмент по названию). */
    static List<String> ordOwners(Activity a, Map<String, String> r) {
        List<String> own = ordSplit(r.get(ORD_OWNERS));
        if (!own.isEmpty()) return own;
        try {
            List<String> names = ordToolNames(r.get("tools"));
            java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<String>();
            for (Object t : IvaSearchPatch.allTools(a)) {
                if (!(t instanceof JSONObject)) continue;
                JSONObject j = (JSONObject) t;
                if (names.contains(j.optString("name", "").trim().toLowerCase(Locale.ROOT))) {
                    String e = j.optString("owner_email", "").trim().toLowerCase(Locale.ROOT);
                    if (!e.isEmpty()) found.add(e);
                }
            }
            own.addAll(found);
        } catch (Throwable ignore) {}
        return own;
    }

    // ── список «Заявки» у клиента ──
    // 10.64: штатный список лежит в PullScrollView (ordersScroll), а ScrollView держит только одного ребёнка — в 10.63
    // наш список рядом с ним не вставлялся (ошибка глушилась) и оставались штатные ненажимаемые карточки. Теперь наш
    // раздел — отдельный ребёнок страницы pageOrders (обычный LinearLayout) вместо ordersScroll, со своей прокруткой
    // (тот же PullScrollView — «потянуть вниз, чтобы обновить») и двумя вкладками как в разделе логотипа:
    // «Активные заявки» (новые и в аренде) и «Закрытые заявки» (кроме убранных в архив). Все карточки нажимаются.
    static final String ORD_ROOT_TAG = "iva_ord_root", ORD_TAB_ACTIVE = "iva_ord_tab_active", ORD_TAB_CLOSED = "iva_ord_tab_closed";
    static final String[] ORD_TABS = {"Активные заявки", "Закрытые заявки"};
    static final String ORD_NO_ACTIVE = "Активных заявок нет.\nВыберите инструмент и отправьте заявку!";
    static final String ORD_NO_CLOSED = "Закрытых заявок пока нет.";
    static final String ORD_NO_CLOSED_ARCH = "Закрытых заявок нет.\nУбранные в архив — в «Кабинете» → «Архив заявок».";
    static final String ORD_ARCH_TIP = "Удержите закрытую заявку, чтобы убрать её в архив";
    static int ordTab = 0;

    /** Какие заявки на вкладке: 0 — активные (не закрытые), 1 — закрытые; убранные в архив — нигде. */
    static boolean ordOnTab(boolean closed, boolean archived, int tab) {
        if (archived) return false;
        return tab == 1 ? closed : !closed;
    }

    /** Запрос заявок только свои — как на сайте (cabinet.html: user_email=eq.почта). */
    static String ordPath(String base, String email) {
        String e = email == null ? "" : email.trim();
        if (e.isEmpty()) return base;
        try { return base + "&user_email=eq." + java.net.URLEncoder.encode(e, "UTF-8"); } catch (Throwable x) { return base; }
    }

    static View idView(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    static void ordRender(final Activity a) {
        Object po = get(a, "pageOrders");
        if (!(po instanceof LinearLayout)) return;
        LinearLayout page = (LinearLayout) po;
        View scroll = idView(a, "ordersScroll");
        View root = null;
        for (int i = 0; i < page.getChildCount(); i++) if (ORD_ROOT_TAG.equals(page.getChildAt(i).getTag())) root = page.getChildAt(i);
        boolean client = IvaClientPatch.isClient(a);
        if (!client || (!ordMine(a) && ordFailed)) {       // админ / гость — штатный список; не загрузилось — тоже (как 10.62)
            if (scroll != null) scroll.setVisibility(View.VISIBLE);
            if (root != null) root.setVisibility(View.GONE);
            if (client) ordAttach(a);
            else try { admPics(a); } catch (Throwable ignore) {}   // 10.67: картинки на карточках админа
            return;
        }
        if (root == null) {
            root = ordBuildRoot(a);
            page.addView(root, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        if (scroll != null) scroll.setVisibility(View.GONE);
        root.setVisibility(View.VISIBLE);
        IvaClientPatch.styleTabs(a, root, new String[]{ORD_TAB_ACTIVE, ORD_TAB_CLOSED}, ordTab);
        View lv = root.findViewWithTag(ORD_LIST_TAG);
        if (!(lv instanceof LinearLayout)) return;
        LinearLayout mine = (LinearLayout) lv;
        mine.removeAllViews();
        if (!ordMine(a)) { mine.addView(ordHint(a, "Загрузка заявок…")); return; }
        int shown = 0, archived = 0;
        List<View> cards = new ArrayList<View>();
        for (Map<String, String> r : ordRows) {
            boolean arch = ordArchived.contains(r.get("id"));
            if (arch) archived++;
            if (!ordOnTab(ordClosed(r), arch, ordTab)) continue;
            cards.add(ordCardView(a, r, false));
            shown++;
        }
        if (ordTab == 1 && shown > 0 && !archMissing) {
            TextView tip = new TextView(a);
            tip.setText(ORD_ARCH_TIP);
            tip.setTextColor(color(a, "gray2", 0xff6b7080));
            tip.setTextSize(12f);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, 0, 0, dp(a, 10));
            mine.addView(tip, new LinearLayout.LayoutParams(-1, -2));
        }
        for (View c : cards) mine.addView(c);
        if (shown == 0) mine.addView(ordHint(a, ordRows.isEmpty() ? ORD_EMPTY
            : ordTab == 0 ? ORD_NO_ACTIVE : archived > 0 ? ORD_NO_CLOSED_ARCH : ORD_NO_CLOSED));
    }

    /** Раздел «Заявки» клиента: вкладки (карточка как в разделе логотипа) и прокручиваемый список. */
    static View ordBuildRoot(final Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag(ORD_ROOT_TAG);
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(IvaClientPatch.tabCard(a));
        String[] tags = {ORD_TAB_ACTIVE, ORD_TAB_CLOSED};
        for (int i = 0; i < tags.length; i++) {
            final int idx = i;
            tabs.addView(IvaClientPatch.tabButton(a, ORD_TABS[i], tags[i], new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (ordTab == idx) return;
                    ordTab = idx;
                    ordSafeRender(a);
                }
            }), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.setMargins(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));   // как в разделе логотипа: поля 16dp, снизу 12dp
        root.addView(tabs, tl);
        ScrollView sc = ordScroll(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag(ORD_LIST_TAG);
        list.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 24));
        sc.addView(list, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    /** Прокрутка как у штатного списка: PullScrollView («потянуть — обновить»); нет — обычный ScrollView. */
    static ScrollView ordScroll(final Activity a) {
        ScrollView sc;
        try {
            sc = (ScrollView) Class.forName("ru.prokatnvsk.app.PullScrollView").getConstructor(android.content.Context.class).newInstance(a);
            Runnable refresh = new Runnable() {
                @Override public void run() {
                    try { callNative(a, "loadOrders"); } catch (Throwable ignore) {}
                    ordAt = 0;
                    ordFetch(a);
                }
            };
            java.lang.reflect.Field f = sc.getClass().getDeclaredField("onRefresh");
            f.setAccessible(true);
            f.set(sc, refresh);
        } catch (Throwable e) {
            sc = new ScrollView(a);
        }
        sc.setVerticalScrollBarEnabled(false);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sc.setFillViewport(true);
        return sc;
    }

    static TextView ordHint(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(color(a, "gray", 0xff9aa1b0));
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 16), dp(a, 32), dp(a, 16), dp(a, 32));
        return t;
    }

    /** Карточка как штатная (renderOrders): bg_card, 14dp, дата оранжевая 13sp, инструменты 14sp, получение 12sp. */
    static View ordCardView(final Activity a, final Map<String, String> r, final boolean archived) {
        // 10.66: карточка — строка: слева текст (как было), справа скруглённые картинки инструментов заявки
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = drawable(a, "bg_card");
        if (bg != null) card.setBackground(bg);
        card.setPadding(dp(a, 14), dp(a, 14), dp(a, 14), dp(a, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 10);
        card.setLayoutParams(lp);
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        card.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        try {
            View pics = ordPicsView(a, r);
            if (pics != null) {
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
                pl.leftMargin = dp(a, 12);
                card.addView(pics, pl);
            }
        } catch (Throwable ignore) {}   // без картинок карточка остаётся как была
        TextView d = new TextView(a);
        d.setText(ordNice(a, r.get("created_at")));
        d.setTextColor(color(a, "orange", 0xffff8f1f));
        d.setTextSize(13f);
        d.setTypeface(null, Typeface.BOLD);
        c.addView(d);
        TextView t = new TextView(a);
        t.setText(ordVal(r.get("tools")));
        t.setTextColor(color(a, "text", 0xfff2f4f8));
        t.setTextSize(14f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(a, 4);
        c.addView(t, tl);
        String gm = ordVal(r.get("get_method"));
        if (!gm.isEmpty()) {
            TextView g = new TextView(a);
            g.setText(gm);
            g.setTextColor(color(a, "gray", 0xff9aa1b0));
            g.setTextSize(12f);
            c.addView(g);
        }
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView st = new TextView(a);
        st.setText(ordStatus(ordStatusOf(r)));
        st.setTextColor(ordClosed(r) ? color(a, "gray2", 0xff6b7080) : color(a, "orange", 0xffff8f1f));   // активная — оранжевым
        st.setTextSize(12f);
        foot.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView more = new TextView(a);
        more.setTag(ORD_MORE_TAG);
        more.setText(ORD_MORE);
        more.setTextColor(color(a, "orange", 0xffff8f1f));
        more.setTextSize(12f);
        more.setTypeface(null, Typeface.BOLD);
        foot.addView(more, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(a, 6);
        c.addView(foot, fl);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { ordOpenRow(a, r, archived); } catch (Throwable ignore) {} }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { try { ordAskArchive(a, r, !archived); } catch (Throwable ignore) {} return true; }
        });
        return card;
    }

    // ── 10.66: картинки инструментов на карточке заявки (просьба владельца 14.10.2026) ──
    // В заявке инструменты — текстом («Дрель (3 дня), Пила (5 дней)»), без id; картинку берём из каталога по названию
    // (сначала у проката этой заявки), как ordOwners. Не нашли (снят с публикации, каталог ещё не загрузился) — гаечный
    // ключ, как в чате. Справа до двух картинок 56 dp со скруглением 12 dp; инструментов больше — на второй «+N».
    static final int ORD_PICS_MAX = 2, ORD_PIC_DP = 56;

    /** Для каждого названия из заявки — номер инструмента в каталоге (у проката заявки — в первую очередь), -1 — нет. */
    static List<Integer> ordPicIdx(List<String> names, List<String> catNames, List<String> catOwners, List<String> owners) {
        List<Integer> out = new ArrayList<Integer>();
        for (String n : names) {
            int any = -1, own = -1;
            for (int i = 0; i < catNames.size(); i++) {
                String cn = catNames.get(i) == null ? "" : catNames.get(i).trim().toLowerCase(Locale.ROOT);
                if (!cn.equals(n)) continue;
                if (any < 0) any = i;
                String ce = catOwners.get(i) == null ? "" : catOwners.get(i).trim().toLowerCase(Locale.ROOT);
                if (owners != null && owners.contains(ce)) { own = i; break; }
            }
            out.add(own >= 0 ? own : any);
        }
        return out;
    }

    /** Сколько картинок рисовать и что написать на последней: {картинок, «+N» (0 — не писать)}. */
    static int[] ordPicsPlan(int tools) {
        if (tools <= 0) return new int[]{0, 0};
        if (tools <= ORD_PICS_MAX) return new int[]{tools, 0};
        return new int[]{ORD_PICS_MAX, tools - ORD_PICS_MAX + 1};
    }

    static View ordPicsView(Activity a, Map<String, String> r) {
        List<String> names = ordToolNames(r.get("tools"));
        int[] plan = ordPicsPlan(names.size());
        if (plan[0] == 0) return null;
        List<JSONObject> cat = new ArrayList<JSONObject>();
        List<String> cn = new ArrayList<String>(), co = new ArrayList<String>();
        for (Object t : IvaSearchPatch.allTools(a)) {
            if (!(t instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) t;
            cat.add(j);
            cn.add(j.optString("name", ""));
            co.add(j.optString("owner_email", ""));
        }
        List<String> owners = new ArrayList<String>();
        for (String e : ordSplit(r.get(ORD_OWNERS))) owners.add(e.trim().toLowerCase(Locale.ROOT));
        List<Integer> idx = ordPicIdx(names, cn, co, owners);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < plan[0]; i++) {
            JSONObject tool = idx.get(i) >= 0 ? cat.get(idx.get(i)) : null;
            String id = tool == null ? "" : tool.optString("id", "");
            View pic = IvaChatPatch.toolPic(a, tool, id, ORD_PIC_DP);   // штатный loadPhoto, тот же кэш, что в чате
            FrameLayout cell = new FrameLayout(a);
            cell.addView(pic, new FrameLayout.LayoutParams(-1, -1));
            if (i == plan[0] - 1 && plan[1] > 0) {
                TextView more = new TextView(a);
                more.setText("+" + plan[1]);
                more.setTextColor(0xffffffff);
                more.setTextSize(15f);
                more.setTypeface(null, Typeface.BOLD);
                more.setGravity(Gravity.CENTER);
                more.setBackgroundColor(0x99000000);
                IvaChatPatch.round(more, dp(a, 12), false);
                cell.addView(more, new FrameLayout.LayoutParams(-1, -1));
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(a, ORD_PIC_DP), dp(a, ORD_PIC_DP));
            if (i > 0) cl.leftMargin = dp(a, 6);
            row.addView(cell, cl);
        }
        return row;
    }

    // ── 10.67: картинки и в заявках у админа (просьба владельца 14.10.2026) ──
    // Список админа штатный (renderAdmOrders): сначала карточки клиентов (имя, «N заявок · M активн.», кружок статуса),
    // по нажатию — заявки клиента (дата, инструменты, комиссия, кнопки). Карточки идут в том же порядке, что admParts,
    // сгруппированные по partClientKey (у клиента — все его заявки, без фильтров). Картинки те же, что у клиента
    // (ordPicsView: до двух 56 dp, «+N»), каталог — сначала у проката заявки (owner_email части, т.е. у самого админа).
    // Клиент — справа перед кружком (инструменты всех его заявок), заявка — справа от даты и названий.
    // Число карточек не совпало с данными (другой вариант экрана) — ничего не трогаем.
    static final String ADM_PICS_TAG = "iva_adm_pics";

    /** Данные для карточек админа в порядке штатного экрана: по клиенту (все его заявки) или заявки одного клиента. */
    static List<Map<String, String>> admPicRows(List<String> keys, List<String> tools, List<String> owners, boolean drill, String drillKey) {
        LinkedHashMap<String, List<Integer>> groups = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i) == null ? "" : keys.get(i);
            List<Integer> g = groups.get(k);
            if (g == null) { g = new ArrayList<Integer>(); groups.put(k, g); }
            g.add(i);
        }
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        if (drill) {
            for (Integer i : admDrillIdx(keys, drillKey)) out.add(admPicRow(tools, owners, java.util.Collections.singletonList(i)));
        } else {
            for (List<Integer> g : groups.values()) out.add(admPicRow(tools, owners, g));
        }
        return out;
    }

    static Map<String, String> admPicRow(List<String> tools, List<String> owners, List<Integer> idx) {
        StringBuilder t = new StringBuilder(), o = new StringBuilder();
        for (Integer i : idx) {
            String s = i < tools.size() ? admClean(tools.get(i)) : "";
            if (!s.isEmpty()) { if (t.length() > 0) t.append(", "); t.append(s); }
            String e = i < owners.size() ? admClean(owners.get(i)) : "";
            if (!e.isEmpty() && !ordSplit(o.toString()).contains(e)) { if (o.length() > 0) o.append(","); o.append(e); }
        }
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("tools", t.toString());
        m.put(ORD_OWNERS, o.toString());
        return m;
    }

    static String admClean(String v) {
        String s = v == null ? "" : v.trim();
        return "null".equals(s) ? "" : s;      // optString у JSON null — «null»
    }

    /** Карточки штатного списка: LinearLayout с содержимым (подпись, «‹ Все клиенты», «Заявок ещё нет» — не они). */
    static List<LinearLayout> admCards(ViewGroup list) {
        List<LinearLayout> out = new ArrayList<LinearLayout>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c instanceof LinearLayout && ((LinearLayout) c).getChildCount() >= 2) out.add((LinearLayout) c);
        }
        return out;
    }

    static void admPics(Activity a) {
        Object ol = get(a, "ordersList"), parts = get(a, "admParts");
        if (!(ol instanceof LinearLayout) || !(parts instanceof List)) return;
        List<String> keys = new ArrayList<String>(), tools = new ArrayList<String>(), owners = new ArrayList<String>();
        for (Object o : (List<?>) parts) {
            if (!(o instanceof JSONObject)) return;
            JSONObject j = (JSONObject) o;
            keys.add(IvaNotifyPatch.clientKey(a, j));
            tools.add(j.optString("tools_text", ""));
            owners.add(j.optString("owner_email", ""));
        }
        boolean drill = Boolean.TRUE.equals(get(a, "admDrill"));
        List<Map<String, String>> rows = admPicRows(keys, tools, owners, drill, String.valueOf(get(a, "admClientKey")));
        List<LinearLayout> cards = admCards((ViewGroup) ol);
        if (rows.isEmpty() || cards.size() != rows.size()) return;
        List<Integer> mine = drill ? admDrillIdx(keys, String.valueOf(get(a, "admClientKey"))) : null;
        for (int i = 0; i < cards.size(); i++) {
            try { admPicCard(a, cards.get(i), rows.get(i), drill); } catch (Throwable ignore) {}
            if (mine != null && i < mine.size())   // 10.68: нажатие на заявку — подробности
                try { admMore(a, cards.get(i), (JSONObject) ((List<?>) parts).get(mine.get(i))); } catch (Throwable ignore) {}
        }
    }

    /** Номера частей (в admParts) одного клиента — в порядке штатных карточек его заявок. */
    static List<Integer> admDrillIdx(List<String> keys, String drillKey) {
        List<Integer> out = new ArrayList<Integer>();
        String k = drillKey == null ? "" : drillKey;
        for (int i = 0; i < keys.size(); i++) if (k.equals(keys.get(i) == null ? "" : keys.get(i))) out.add(i);
        return out;
    }

    static void admPicCard(Activity a, LinearLayout card, Map<String, String> r, boolean drill) {
        if (card.findViewWithTag(ADM_PICS_TAG) != null) return;       // уже есть (список не перестраивался)
        if (drill) {
            if (card.getOrientation() != LinearLayout.VERTICAL
                || !(card.getChildAt(0) instanceof TextView) || !(card.getChildAt(1) instanceof TextView)) return;
        } else if (card.getOrientation() != LinearLayout.HORIZONTAL) return;
        View pics = ordPicsView(a, r);
        if (pics == null) return;
        pics.setTag(ADM_PICS_TAG);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.leftMargin = dp(a, 10);
        if (!drill) {                                                  // клиент: [имя и счётчики | картинки | кружок]
            pl.rightMargin = dp(a, 10);
            pl.gravity = Gravity.CENTER_VERTICAL;
            card.addView(pics, card.getChildCount() - 1, pl);
            return;
        }
        // заявка: дата и инструменты — в столбец слева, картинки справа; комиссия, статус и кнопки — как были, ниже
        View date = card.getChildAt(0), names = card.getChildAt(1);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        card.removeView(names);
        card.removeView(date);
        try {
            col.addView(date);
            col.addView(names);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(pics, pl);
            card.addView(row, 0, new LinearLayout.LayoutParams(-1, -2));
        } catch (Throwable t) {                                         // не вышло — штатные строки на место
            if (date.getParent() instanceof ViewGroup) ((ViewGroup) date.getParent()).removeView(date);
            if (names.getParent() instanceof ViewGroup) ((ViewGroup) names.getParent()).removeView(names);
            if (row.getParent() == card) card.removeView(row);
            card.addView(date, 0);
            card.addView(names, 1);
        }
    }

    /** Запасной путь (наш список не загрузился): штатные карточки — нажатие и «Подробнее ›», как в 10.62. */
    static void ordAttach(final Activity a) {
        Object o = get(a, "ordersList");
        if (!(o instanceof LinearLayout) || !IvaClientPatch.isClient(a)) return;
        LinearLayout list = (LinearLayout) o;
        boolean mine = ordMe(a).equals(ordFor);
        List<String> dates = new ArrayList<String>(), tools = new ArrayList<String>();
        if (mine) for (Map<String, String> r : ordRows) { dates.add(ordNice(a, r.get("created_at"))); tools.add(r.get("tools")); }
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
        boolean missing = false;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (!ordCard(c)) continue;
            LinearLayout card = (LinearLayout) c;
            String date = String.valueOf(((TextView) card.getChildAt(0)).getText());
            String tl = String.valueOf(((TextView) card.getChildAt(1)).getText());
            String base = ordKey(date, tl, 0);
            Integer n = counts.get(base);
            int nth = n == null ? 0 : n;
            counts.put(base, nth + 1);
            final String key = ordKey(date, tl, nth);
            final int idx = ordFind(dates, tools, key);
            if (idx < 0) missing = true;
            String tagNow = idx < 0 ? key + "\u0001card" : key;
            if (tagNow.equals(card.getTag())) continue;
            card.setTag(tagNow);
            card.setClickable(true);
            final Map<String, String> row = idx >= 0 ? ordRows.get(idx)
                : ordCardRow(date, tl, card.getChildCount() > 2 && card.getChildAt(2) instanceof TextView ? String.valueOf(((TextView) card.getChildAt(2)).getText()) : "");
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { try { ordOpenRow(a, row, ordArchived.contains(row.get("id"))); } catch (Throwable ignore) {} }
            });
            if (card.findViewWithTag(ORD_MORE_TAG) == null) {
                TextView more = new TextView(a);
                more.setTag(ORD_MORE_TAG);
                more.setText(ORD_MORE);
                more.setTextColor(color(a, "orange", 0xffff8f1f));
                more.setTextSize(12f);
                more.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
                ml.topMargin = dp(a, 6);
                card.addView(more, ml);
            }
        }
        if (missing && !ordLoading && System.currentTimeMillis() - ordAt > ORD_REFETCH_MS) ordFetch(a);
    }

    /** Заявка только по тексту штатной карточки (данные не загрузились): дата, инструменты, получение. */
    static Map<String, String> ordCardRow(String nice, String tools, String getMethod) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("tools", tools == null ? "" : tools);
        m.put("get_method", getMethod == null ? "" : getMethod);
        m.put(ORD_NICE, nice == null ? "" : nice);
        m.put(ORD_PARTIAL, "1");
        return m;
    }
    static final String ORD_NICE = "_nice", ORD_PARTIAL = "_partial";

    /** Штатная карточка заявки клиента: вертикальная, первые два ребёнка — тексты (дата и инструменты). */
    static boolean ordCard(View c) {
        if (!(c instanceof LinearLayout)) return false;
        LinearLayout l = (LinearLayout) c;
        return l.getOrientation() == LinearLayout.VERTICAL && l.getChildCount() >= 2
            && l.getChildAt(0) instanceof TextView && l.getChildAt(1) instanceof TextView;
    }

    // ── архив ──

    static void ordAskArchive(final Activity a, final Map<String, String> r, final boolean on) {
        if (archMissing) { Toast.makeText(a, ARCH_NO_DB, Toast.LENGTH_LONG).show(); return; }
        if (on && !ordClosed(r)) { Toast.makeText(a, ARCH_ONLY_CLOSED, Toast.LENGTH_LONG).show(); return; }
        IvaClientPatch.styledChoice(a, on ? ARCH_ASK : ARCH_BACK_ASK, on ? ARCH_ASK_TEXT : "Заявка снова появится в «Заявках».",
            new String[]{on ? ARCH_ON : ARCH_OFF}, new IvaClientPatch.Choice() {
                @Override public void pick(int i) { ordArchive(a, r, on); }
            });
    }

    static String archBody(String id, boolean on) throws Exception {
        JSONObject b = new JSONObject();
        b.put("p_id", id);
        b.put("p_on", on);
        return b.toString();
    }

    static void ordArchive(final Activity a, final Map<String, String> r, final boolean on) {
        final String id = ordVal(r.get("id"));
        if (id.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String code = "";
                try {
                    String[] x = IvaClientPatch.apiCall(a, ARCH_RPC, "POST", archBody(id, on), true);
                    code = x == null ? "" : String.valueOf(x[0]);
                    if ("200".equals(code) && x.length > 1 && x[1] != null) code = x[1].trim().equals("1") ? "ok" : "no";
                } catch (Throwable ignore) {}
                final String fc = code;
                ordH().post(new Runnable() {
                    @Override public void run() {
                        if ("ok".equals(fc)) {
                            if (on) ordArchived.add(id); else ordArchived.remove(id);
                            try { if (ordDialog != null) ordDialog.dismiss(); } catch (Throwable ignore) {}
                            ordSafeRender(a);
                            try { archRender(a); } catch (Throwable ignore) {}
                            Toast.makeText(a, on ? "Заявка в архиве" : "Заявка снова в «Заявках»", Toast.LENGTH_SHORT).show();
                        } else if ("404".equals(fc)) {
                            archMissing = true;
                            Toast.makeText(a, ARCH_NO_DB, Toast.LENGTH_LONG).show();
                        } else if ("no".equals(fc)) {
                            Toast.makeText(a, ARCH_ONLY_CLOSED, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(a, "Не получилось — проверьте интернет", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /** Пункт «Архив заявок» в кабинете клиента — как штатные строки кабинета (bg_card, значок, подпись, «›»). */
    static void profileArchiveRow(final Activity a) {
        int callId = a.getResources().getIdentifier("rowCall", "id", a.getPackageName());
        View call = callId == 0 ? null : a.findViewById(callId);
        if (call == null || !(call.getParent() instanceof ViewGroup)) return;
        ViewGroup list = (ViewGroup) call.getParent();
        View row = list.findViewWithTag(ARCH_ROW_TAG);
        boolean client = IvaClientPatch.isClient(a);
        if (row == null) {
            if (!client) return;
            row = archRowView(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 12);
            list.addView(row, list.indexOfChild(call), lp);
        }
        row.setVisibility(client ? View.VISIBLE : View.GONE);
    }

    static View archRowView(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(ARCH_ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = drawable(a, "bg_card");
        if (bg != null) row.setBackground(bg);
        row.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
        android.widget.ImageView ic = new android.widget.ImageView(a);
        Drawable cart = drawable(a, "ic_cart");
        if (cart != null) { ic.setImageDrawable(cart.mutate()); ic.setColorFilter(color(a, "orange", 0xffff8f1f)); }
        row.addView(ic, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(ARCH_TITLE);
        t.setTextColor(color(a, "text", 0xfff2f4f8));
        t.setTextSize(15f);
        col.addView(t);
        TextView s = new TextView(a);
        s.setText(ARCH_SUB);
        s.setTextColor(color(a, "gray", 0xff9aa1b0));
        s.setTextSize(13f);
        col.addView(s);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 14);
        row.addView(col, cl);
        android.widget.ImageView ch = new android.widget.ImageView(a);
        Drawable chev = drawable(a, "ic_chevron");
        if (chev != null) ch.setImageDrawable(chev);
        row.addView(ch, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openArchive(a); } catch (Throwable ignore) {} }
        });
        return row;
    }

    /** Экран «Архив заявок» (окно на весь экран, как выбор города; «Назад» закрывает). */
    static void openArchive(final Activity a) {
        if (archDialog != null && archDialog.isShowing()) return;
        int cBg = color(a, "bg", 0xff101217);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        TextView back = new TextView(a);
        back.setText("‹ Назад");
        back.setTextColor(color(a, "orange", 0xffff8f1f));
        back.setTextSize(15f);
        back.setTypeface(null, Typeface.BOLD);
        back.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 8));
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = new TextView(a);
        title.setText(ARCH_TITLE);
        title.setTextColor(color(a, "text", 0xfff2f4f8));
        title.setTextSize(21f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        root.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView sub = new TextView(a);
        sub.setText(ARCH_SUB);
        sub.setTextColor(color(a, "gray2", 0xff6b7080));
        sub.setTextSize(12f);
        sub.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 8));
        root.addView(sub, new LinearLayout.LayoutParams(-2, -2));
        ScrollView sc = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 24));
        sc.addView(list, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(cBg));
            try {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.setStatusBarColor(cBg);
                w.setNavigationBarColor(cBg);
            } catch (Throwable ignore) {}
        }
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface x) { if (archDialog == d) { archDialog = null; archList = null; } }
        });
        archDialog = d;
        archList = list;
        archRender(a);
        d.show();
        ordFetch(a);   // свежие заявки и архив
    }

    static void archRender(Activity a) {
        LinearLayout list = archList;
        if (list == null || archDialog == null || !archDialog.isShowing()) return;
        list.removeAllViews();
        if (!ordMine(a)) { list.addView(ordHint(a, ordFailed ? "Не удалось загрузить заявки — проверьте интернет" : "Загрузка…")); return; }
        if (archMissing) { list.addView(ordHint(a, ARCH_NO_DB)); return; }
        int n = 0;
        for (Map<String, String> r : ordRows) {
            if (!ordArchived.contains(r.get("id"))) continue;
            list.addView(ordCardView(a, r, true));
            n++;
        }
        if (n == 0) list.addView(ordHint(a, ARCH_EMPTY));
    }

    // ── подробности заявки (шторка 85%) ──

    static void ordOpen(final Activity a, String key) {
        List<String> dates = new ArrayList<String>(), tools = new ArrayList<String>();
        for (Map<String, String> r : ordRows) { dates.add(ordNice(a, r.get("created_at"))); tools.add(r.get("tools")); }
        int i = ordFind(dates, tools, key);
        if (i >= 0) ordOpenRow(a, ordRows.get(i), ordArchived.contains(ordRows.get(i).get("id")));
    }

    static void ordOpenRow(final Activity a, final Map<String, String> r, final boolean archived) {
        if (ordDialog != null && ordDialog.isShowing()) return;
        int cBg = color(a, "bg", 0xff101217), cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        int cLine = color(a, "line", 0xff262b35), cOrange = color(a, "orange", 0xffff8f1f);

        final FrameLayout root = new FrameLayout(a);
        final View scrim = new View(a);
        scrim.setBackgroundColor(0x8c000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        final LinearLayout sh = new LinearLayout(a);
        sh.setOrientation(LinearLayout.VERTICAL);
        sh.setClickable(true);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(cBg);
        float rr = dp(a, 18);
        sbg.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
        sh.setBackground(sbg);
        int screen = a.getResources().getDisplayMetrics().heightPixels;
        root.addView(sh, new FrameLayout.LayoutParams(-1, Math.round(screen * ORD_SHEET), Gravity.BOTTOM));

        View grip = new View(a);                     // «язычок» как у шторки фильтра
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(cLine);
        gbg.setCornerRadius(dp(a, 2));
        grip.setBackground(gbg);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.topMargin = dp(a, 10);
        sh.addView(grip, gl);

        TextView title = new TextView(a);
        title.setText(archived ? "Заявка в архиве" : "Заявка");
        title.setTextColor(cText);
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 8));
        sh.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sc = new ScrollView(a);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 12));
        sc.addView(body, new FrameLayout.LayoutParams(-1, -2));
        sh.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        String nice = r.containsKey(ORD_NICE) ? r.get(ORD_NICE) : ordNice(a, r.get("created_at"));
        final boolean adm = r.containsKey(ADM_ROW);           // 10.68: заявка у админа — вместо «Прокат» блок «Клиент»
        for (String[] f : adm ? admFields(r, nice) : ordFields(r, nice)) ordFieldView(a, body, f, "Статус".equals(f[0]));
        if (adm) admClientBlock(a, body, r);
        final LinearLayout prokat = new LinearLayout(a);
        prokat.setOrientation(LinearLayout.VERTICAL);
        if (!adm) body.addView(prokat, new LinearLayout.LayoutParams(-1, -2));
        final List<String> owners = adm ? new ArrayList<String>() : ordOwners(a, r);
        if (!adm) ordFillProkat(a, prokat, owners);
        // 10.70: закрытая заявка клиента — «Оставить отзыв» по каждому инструменту (право проверяет база, как на сайте)
        if (!adm && !r.containsKey(ORD_PARTIAL) && (archived || ordClosed(r))) try { IvaReviewPatch.orderBlock(a, body, r); } catch (Throwable ignore) {}
        boolean missing = false;
        for (String e : owners) if (!ordProkat.containsKey(e)) missing = true;
        if (missing) {
            new Thread(new Runnable() {
                @Override public void run() {
                    ordLoadProkat(a, owners);
                    ordH().post(new Runnable() { @Override public void run() { try { ordFillProkat(a, prokat, owners); } catch (Throwable ignore) {} } });
                }
            }).start();
        }

        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        // 10.76: активная заявка — «Написать» и «Позвонить»: клиенту — прокату, админу — клиенту (первым может любой)
        if (ordActive(r, archived)) try { ordContactRow(a, sh, d, r, owners, adm); } catch (Throwable ignore) {}
        if (!adm && !r.containsKey(ORD_PARTIAL) && (archived || ordClosed(r))) {   // «В архив» — только у закрытых; в архиве — «Вернуть» (у админа — штатная «В архив →» на карточке)
            TextView arch = new TextView(a);
            arch.setText(archived ? ARCH_OFF : ARCH_ON);
            arch.setGravity(Gravity.CENTER);
            arch.setTextColor(cOrange);
            arch.setTextSize(15f);
            arch.setTypeface(null, Typeface.BOLD);
            arch.setBackground(IvaClientPatch.choiceBg(a, "card", 0xff1a1d24, IvaClientPatch.CHOICE_RADIUS_DP));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, dp(a, 50));
            al.setMargins(dp(a, 16), dp(a, 8), dp(a, 16), 0);
            sh.addView(arch, al);
            arch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (archMissing) { Toast.makeText(a, ARCH_NO_DB, Toast.LENGTH_LONG).show(); return; }
                    ordArchive(a, r, !archived);
                }
            });
        }
        TextView close = new TextView(a);
        close.setText("Закрыть");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(Color.WHITE);
        close.setTextSize(15f);
        close.setTypeface(null, Typeface.BOLD);
        Drawable btn = drawable(a, "bg_btn_orange");
        if (btn != null) close.setBackground(btn); else close.setBackgroundColor(cOrange);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, dp(a, 50));
        cl.setMargins(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 16));
        sh.addView(close, cl);

        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);   // системная «Назад» закрывает шторку
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        View.OnClickListener dismiss = new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } };
        scrim.setOnClickListener(dismiss);
        close.setOnClickListener(dismiss);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface x) { if (ordDialog == d) ordDialog = null; }
        });
        ordDialog = d;
        d.show();
        sh.setTranslationY(screen);                  // выезжает снизу, как шторка фильтра
        sh.animate().translationY(0).setDuration(220).start();
    }

    // ───────────── 10.76: связаться из активной заявки (просьба владельца 14.10.2026) ─────────────
    // В подробностях активной заявки (новая или в аренде) — две кнопки: «Написать» открывает общую переписку в «Чате»
    // (клиент — с прокатом заявки, админ — с клиентом; писать первым может любой), «Позвонить» — набор номера
    // (телефон проката из его профиля, у админа — телефон из заявки). Звонок только через набор номера — без разрешений.
    static final String CONTACT_WRITE = "Написать", CONTACT_CALL = "Позвонить";
    static final String CONTACT_NO_PHONE_CLIENT = "Клиент не указал телефон — напишите ему";
    static final String CONTACT_NO_PHONE_PROKAT = "Телефон проката не указан или ещё загружается — напишите в чат";
    static final String CONTACT_NO_EMAIL = "Клиент оформил заявку без входа в приложение — позвоните ему";
    static final String CONTACT_NO_PROKAT = "Прокат этой заявки не найден";
    static final String CONTACT_NO_CHAT = "Не удалось открыть чат";

    /** Активная заявка — новая или в аренде, не в архиве (у админа — статус его части заявки). */
    static boolean ordActive(Map<String, String> r, boolean archived) {
        return r != null && !archived && !"Закрыта".equals(ordStatus(ordStatusOf(r)));
    }

    /**
     * С кем связаться: {почта для чата (как в базе), телефон, имя}. Админу — клиент заявки; клиенту — прокат заявки
     * (первый, чьи данные загружены; пока не загружены — почта из заявки, без телефона).
     */
    static String[] ordContact(boolean adm, Map<String, String> r, List<String> owners, Map<String, Map<String, String>> prokat) {
        if (adm) return new String[]{ordVal(r.get("user_email")), ordVal(r.get("phone")), ordVal(r.get("name"))};
        for (String e : owners) {
            Map<String, String> ad = prokat == null ? null : prokat.get(e);
            if (ad == null) continue;
            String mail = ordVal(ad.get("email")), company = ordVal(ad.get("company")), name = ordVal(ad.get("full_name"));
            return new String[]{mail.isEmpty() ? e : mail, ordVal(ad.get("phone")), company.isEmpty() ? name : company};
        }
        return new String[]{owners.isEmpty() ? "" : ordVal(owners.get(0)), "", ""};
    }

    /** «+7 (900) 123-45-67» → «tel:+79001234567»; меньше 5 цифр — пусто (звонить некуда). */
    static String dialUri(String phone) {
        String d = ordVal(phone).replaceAll("[^0-9+]", "");
        return d.replace("+", "").length() < 5 ? "" : "tel:" + d;
    }

    static void ordContactRow(final Activity a, LinearLayout sh, final Dialog d, final Map<String, String> r,
                              final List<String> owners, final boolean adm) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View write = ordContactBtn(a, CONTACT_WRITE, "ic_chat"), call = ordContactBtn(a, CONTACT_CALL, "ic_phone");
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, dp(a, 50), 1f);
        wl.rightMargin = dp(a, 10);
        row.addView(write, wl);
        row.addView(call, new LinearLayout.LayoutParams(0, dp(a, 50), 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.setMargins(dp(a, 16), dp(a, 8), dp(a, 16), 0);
        sh.addView(row, rl);
        write.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String[] k = ordContact(adm, r, owners, ordProkat);
                if (k[0].isEmpty()) { Toast.makeText(a, adm ? CONTACT_NO_EMAIL : CONTACT_NO_PROKAT, Toast.LENGTH_LONG).show(); return; }
                try { d.dismiss(); } catch (Throwable ignore) {}
                boolean ok = false;
                try { ok = IvaChatPatch.openWith(a, k[0]); } catch (Throwable ignore) {}
                if (!ok) Toast.makeText(a, CONTACT_NO_CHAT, Toast.LENGTH_SHORT).show();
            }
        });
        call.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String uri = dialUri(ordContact(adm, r, owners, ordProkat)[1]);
                if (uri.isEmpty()) { Toast.makeText(a, adm ? CONTACT_NO_PHONE_CLIENT : CONTACT_NO_PHONE_PROKAT, Toast.LENGTH_LONG).show(); return; }
                try { a.startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse(uri))); }
                catch (Throwable e) { Toast.makeText(a, "Не удалось открыть набор номера", Toast.LENGTH_SHORT).show(); }
            }
        });
    }

    /** Кнопка как «В архив»: карточка темы, оранжевый жирный текст, слева значок приложения (ic_chat / ic_phone). */
    static View ordContactBtn(Activity a, String text, String icon) {
        int orange = color(a, "orange", 0xffff8f1f);
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setBackground(IvaClientPatch.choiceBg(a, "card", 0xff1a1d24, IvaClientPatch.CHOICE_RADIUS_DP));
        b.setContentDescription(text);
        Drawable ic = drawable(a, icon);
        if (ic != null) {
            android.widget.ImageView iv = new android.widget.ImageView(a);
            iv.setImageDrawable(ic.mutate());
            iv.setColorFilter(orange);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18));
            il.rightMargin = dp(a, 8);
            b.addView(iv, il);
        }
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(orange);
        t.setTextSize(15f);
        t.setTypeface(null, Typeface.BOLD);
        b.addView(t, new LinearLayout.LayoutParams(-2, -2));
        return b;
    }

    /** 10.76: нажали на напоминание об аренде — «Заявки», а когда список загрузится — подробности этой заявки. */
    static void openById(final Activity a, final String orderId) {
        IvaNotifyPatch.tab(a, "orders");
        final String id = ordVal(orderId);
        if (id.isEmpty()) return;
        final long until = System.currentTimeMillis() + 15000;
        ordH().postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (a.isFinishing() || !"orders".equals(String.valueOf(get(a, "curTab")))) return;   // ушли — не мешаем
                    if (ordMine(a)) {
                        for (Map<String, String> r : new ArrayList<Map<String, String>>(ordRows)) {
                            if (!id.equals(ordVal(r.get("id")))) continue;
                            if (ordDialog == null || !ordDialog.isShowing()) ordOpenRow(a, r, ordArchived.contains(id));
                            return;
                        }
                        if (!ordLoading) return;          // свежий список пришёл, а заявки в нём нет — остаётся список
                    }
                    if (System.currentTimeMillis() < until) ordH().postDelayed(this, 300);
                } catch (Throwable ignore) {}
            }
        }, 300);
    }

    static void ordFieldView(final Activity a, LinearLayout body, final String[] f, boolean accent) {
        TextView l = new TextView(a);
        l.setText(f[0]);
        l.setTextColor(color(a, "gray", 0xff9aa1b0));
        l.setTextSize(12f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = dp(a, 12);
        body.addView(l, ll);
        TextView v = new TextView(a);
        v.setText(f[1]);
        v.setTextSize(15f);
        v.setTextIsSelectable(!"Телефон".equals(f[0]));
        v.setTextColor(accent ? color(a, "orange", 0xffff8f1f) : color(a, "text", 0xfff2f4f8));
        if (accent) v.setTypeface(null, Typeface.BOLD);
        if ("Телефон".equals(f[0])) {                          // телефон проката — нажать, чтобы позвонить (набор номера)
            v.setTextColor(color(a, "orange", 0xffff8f1f));
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    try { a.startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + f[1].replaceAll("[^0-9+]", "")))); }
                    catch (Throwable ignore) {}
                }
            });
        }
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, -2);
        vl.topMargin = dp(a, 2);
        body.addView(v, vl);
    }

    /** Блок «Прокат» в шторке: по каждому прокату заявки (обычно один). */
    static void ordFillProkat(Activity a, LinearLayout box, List<String> owners) {
        box.removeAllViews();
        TextView h = new TextView(a);
        h.setText("Прокат");
        h.setTextColor(color(a, "text", 0xfff2f4f8));
        h.setTextSize(16f);
        h.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(a, 22);
        box.addView(h, hl);
        int shown = 0;
        boolean loading = false;
        for (String e : owners) {
            Map<String, String> ad = ordProkat.get(e);
            if (ad == null) { loading = true; continue; }
            for (String[] f : ordProkatFields(ad)) ordFieldView(a, box, f, false);
            shown++;
        }
        if (shown == 0) {
            TextView t = new TextView(a);
            t.setText(loading ? "Загрузка данных проката…" : "Данные проката не найдены. Напишите в «Чат» — прокат ответит.");
            t.setTextColor(color(a, "gray", 0xff9aa1b0));
            t.setTextSize(13f);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = dp(a, 8);
            box.addView(t, tl);
        }
    }

    // ───────────── 10.68: подробности заявки у админа (решение владельца 14.10.2026) ─────────────
    // Нажатие на карточку заявки (внутри клиента) — та же шторка, что у клиента (85 %, те же строки и вид), только
    // вместо «Прокат» — «Клиент»: имя, телефон (нажать — набор номера), e-mail. Всё это админ и так видит: на штатной
    // карточке («Позвонить: …») и в штатной «Аренды» (rentalDetail). Данные — из уже загруженного списка (admParts:
    // order_parts + orders), своих запросов нет. Кнопки карточки («Выдал», «Вернули», «В архив →», «Позвонить») — штатные,
    // нажимаются как раньше: у них свои обработчики, у самой карточки штатного нажатия нет.
    static final String ADM_ROW = "_adm", ADM_MORE_TAG = "iva_adm_more";

    /** Строка шторки из части заявки (order_parts) и самой заявки (orders) — как их загрузил штатный loadOrders. */
    static Map<String, String> admRow(JSONObject part) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        JSONObject o = part.optJSONObject("orders");
        m.put(ADM_ROW, "1");
        m.put("id", ordVal(part.optString("order_id", "")));
        m.put("part_id", ordVal(part.optString("id", "")));
        m.put("status", ordVal(part.optString("status", "")));
        m.put("tools", ordVal(part.optString("tools_text", "")));
        m.put("rent_sum", ordVal(part.optString("rent_sum", "")));
        m.put("fee", ordVal(part.optString("fee", "")));
        String at = ordVal(part.optString("created_at", ""));
        if (o != null) {
            if (at.isEmpty()) at = ordVal(o.optString("created_at", ""));
            m.put("get_method", ordVal(o.optString("get_method", "")));
            m.put("comment", ordVal(o.optString("comment", "")));
            m.put("name", ordVal(o.optString("name", "")));
            m.put("phone", ordVal(o.optString("phone", "")));
            m.put("user_email", ordVal(o.optString("user_email", "")));
        }
        m.put("created_at", at);
        return m;
    }

    /** Строки шторки админа: о заявке (без «Срока» — его штатный список не загружает, сроки — в названиях). */
    static List<String[]> admFields(Map<String, String> r, String nice) {
        List<String[]> out = new ArrayList<String[]>();
        ordAdd(out, "Дата", nice == null || nice.trim().isEmpty() ? r.get("created_at") : nice);
        out.add(new String[]{"Статус", ordStatus(r.get("status"))});
        ordAdd(out, "Инструменты", r.get("tools"));
        ordAdd(out, "Получение", r.get("get_method"));
        ordAdd(out, "Комментарий", r.get("comment"));
        String rent = admMoney(r.get("rent_sum")), fee = admMoney(r.get("fee"));
        ordAdd(out, "Аренда", rent.isEmpty() ? "" : rent + (fee.isEmpty() ? "" : " · комиссия 5 %: " + fee));
        ordAdd(out, "Номер заявки", r.get("id"));
        return out;
    }

    /** Строки блока «Клиент». */
    static List<String[]> admClientFields(Map<String, String> r) {
        List<String[]> out = new ArrayList<String[]>();
        ordAdd(out, "Имя", r.get("name"));
        ordAdd(out, "Телефон", r.get("phone"));
        ordAdd(out, "E-mail", r.get("user_email"));
        return out;
    }

    /** «1200» / «1200.0» → «1200 ₽», «99.5» → «99,50 ₽»; пусто / не число — пусто. */
    static String admMoney(String v) {
        String s = ordVal(v);
        if (s.isEmpty()) return "";
        try {
            double d = Double.parseDouble(s);
            if (Math.abs(d - Math.rint(d)) < 0.005) return Math.round(d) + " ₽";
            return String.format(Locale.ROOT, "%.2f", d).replace('.', ',') + " ₽";
        } catch (Throwable e) { return ""; }
    }

    static void admClientBlock(Activity a, LinearLayout body, Map<String, String> r) {
        List<String[]> fs = admClientFields(r);
        if (fs.isEmpty()) return;
        TextView h = new TextView(a);
        h.setText("Клиент");
        h.setTextColor(color(a, "text", 0xfff2f4f8));
        h.setTextSize(16f);
        h.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(a, 22);
        body.addView(h, hl);
        for (String[] f : fs) ordFieldView(a, body, f, false);
    }

    /** Карточка заявки у админа: нажатие — подробности, под названиями — «Подробнее ›». */
    static void admMore(final Activity a, LinearLayout card, JSONObject part) {
        if (part == null || card.findViewWithTag(ADM_MORE_TAG) != null) return;
        final Map<String, String> row = admRow(part);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { ordOpenRow(a, row, false); } catch (Throwable ignore) {} }
        });
        TextView more = new TextView(a);
        more.setTag(ADM_MORE_TAG);
        more.setText(ORD_MORE);
        more.setTextColor(color(a, "orange", 0xffff8f1f));
        more.setTextSize(12f);
        more.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = dp(a, 6);
        int at = card.getChildAt(0) instanceof TextView ? 2 : 1;      // после даты и названий (или их строки с картинками)
        card.addView(more, Math.min(at, card.getChildCount()), ml);
    }
}

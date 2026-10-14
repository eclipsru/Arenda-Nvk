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


    // ───────────────────────── 10.62: заявки клиента нажимаются ─────────────────────────
    // Штатный renderOrders рисует у клиента карточку: дата (niceDate), инструменты, способ получения. Карточки
    // не меняем и не пересоздаём — только делаем их нажимаемыми и добавляем «Подробнее ›». Полные данные заявки —
    // тот же запрос, что у штатного loadOrders (RLS отдаёт клиенту только его заявки), но select=*.
    // Нажатие открывает шторку на 85% экрана (как фильтр) со всеми полями заявки. У админа — ничего не меняется.
    static final String ORD_PATH = "/rest/v1/orders?select=*&order=created_at.desc&limit=30";
    static final String ORD_KEY = "iva_ord:", ORD_MORE_TAG = "iva_ord_more", ORD_MORE = "Подробнее ›";
    static final String[] ORD_COLS = {"id", "created_at", "status", "tools", "days", "get_method", "address", "name", "phone", "comment"};
    static final long ORD_ATTACH_MS = 250, ORD_REFETCH_MS = 5000;
    static final float ORD_SHEET = 0.85f;
    static WeakReference<View> ordHooked = new WeakReference<View>(null);
    static List<Map<String, String>> ordRows = new ArrayList<Map<String, String>>();
    static String ordFor = "";
    static long ordAt = 0;
    static boolean ordLoading = false;
    static Handler ordH;                 // создаётся при первом обращении (не в статическом блоке класса)

    static Handler ordH() {
        if (ordH == null) ordH = new Handler(Looper.getMainLooper());
        return ordH;
    }
    static Runnable ordRun;
    static Dialog ordDialog;

    static void clientOrders(final Activity a) {
        Object o = get(a, "ordersList");
        if (!(o instanceof LinearLayout) || !IvaClientPatch.isClient(a)) return;
        if (ordHooked.get() != o) {
            ((LinearLayout) o).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { ordSchedule(a); }
                @Override public void onChildViewRemoved(View parent, View child) {}
            });
            ordHooked = new WeakReference<View>((View) o);
        }
        ordFetch(a);   // раздел открыли — свежие статусы (штатный loadOrders тоже грузит заново)
    }

    static void ordSchedule(final Activity a) {
        if (ordRun != null) ordH().removeCallbacks(ordRun);
        ordRun = new Runnable() { @Override public void run() { try { ordAttach(a); } catch (Throwable ignore) {} } };
        ordH().postDelayed(ordRun, ORD_ATTACH_MS);
    }

    static String ordMe(Activity a) {
        Object e = get(a, "userEmail");
        return e instanceof String ? ((String) e).trim().toLowerCase(Locale.ROOT) : "";
    }

    static void ordFetch(final Activity a) {
        if (ordLoading) return;
        ordLoading = true;
        final String me = ordMe(a);
        new Thread(new Runnable() {
            @Override public void run() {
                List<Map<String, String>> rows = null;
                try {
                    String[] r = IvaClientPatch.apiCall(a, ORD_PATH, "GET", null, true);
                    if (r != null && r.length > 1 && "200".equals(r[0])) rows = ordParse(r[1]);
                } catch (Throwable ignore) {}
                final List<Map<String, String>> got = rows;
                ordH().post(new Runnable() {
                    @Override public void run() {
                        ordLoading = false;
                        ordAt = System.currentTimeMillis();
                        if (got != null) { ordRows = got; ordFor = me; }
                        try { ordAttach(a); } catch (Throwable ignore) {}
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
            out.add(m);
        }
        return out;
    }

    /** Штатная дата карточки (niceDate) — по ней и тексту инструментов карточка находит свою заявку. */
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
            if (ordFind(dates, tools, key) < 0) { missing = true; continue; }
            if (key.equals(card.getTag())) continue;
            card.setTag(key);
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { try { ordOpen(a, key); } catch (Throwable ignore) {} }
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
        // новая заявка или другой вход — данные ещё старые: один раз догружаем (не чаще раза в 5 с)
        if (missing && !ordLoading && System.currentTimeMillis() - ordAt > ORD_REFETCH_MS) ordFetch(a);
    }

    /** Штатная карточка заявки клиента: вертикальная, первые два ребёнка — тексты (дата и инструменты). */
    static boolean ordCard(View c) {
        if (!(c instanceof LinearLayout)) return false;
        LinearLayout l = (LinearLayout) c;
        return l.getOrientation() == LinearLayout.VERTICAL && l.getChildCount() >= 2
            && l.getChildAt(0) instanceof TextView && l.getChildAt(1) instanceof TextView;
    }

    /** Статус как на сайте (cabinet.html badge): пусто/new — «Новая», rented — «В аренде», иначе — «Закрыта». */
    static String ordStatus(String st) {
        String s = st == null || st.trim().isEmpty() ? "new" : st.trim();
        return "new".equals(s) ? "Новая" : "rented".equals(s) ? "В аренде" : "Закрыта";
    }

    /** Строки шторки «подпись — значение»; пустые поля не показываются. */
    static List<String[]> ordFields(Map<String, String> r, String nice) {
        List<String[]> out = new ArrayList<String[]>();
        ordAdd(out, "Дата", nice == null || nice.trim().isEmpty() ? r.get("created_at") : nice);
        out.add(new String[]{"Статус", ordStatus(r.get("status"))});
        ordAdd(out, "Инструменты", r.get("tools"));
        String days = r.get("days") == null ? "" : r.get("days").trim();
        ordAdd(out, "Срок", days.matches("\\d+") ? (days.equals("0") ? "" : days + " дн.") : days);
        ordAdd(out, "Получение", r.get("get_method"));
        ordAdd(out, "Адрес", r.get("address"));
        ordAdd(out, "Имя", r.get("name"));
        ordAdd(out, "Телефон", r.get("phone"));
        ordAdd(out, "Комментарий", r.get("comment"));
        ordAdd(out, "Номер заявки", r.get("id"));
        return out;
    }

    static void ordAdd(List<String[]> out, String label, String v) {
        if (v != null && !v.trim().isEmpty() && !"null".equals(v.trim())) out.add(new String[]{label, v.trim()});
    }

    static void ordOpen(final Activity a, String key) {
        List<String> dates = new ArrayList<String>(), tools = new ArrayList<String>();
        for (Map<String, String> r : ordRows) { dates.add(ordNice(a, r.get("created_at"))); tools.add(r.get("tools")); }
        int i = ordFind(dates, tools, key);
        if (i < 0) return;
        if (ordDialog != null && ordDialog.isShowing()) return;
        Map<String, String> r = ordRows.get(i);
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
        title.setText("Заявка");
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
        for (String[] f : ordFields(r, ordNice(a, r.get("created_at")))) {
            TextView l = new TextView(a);
            l.setText(f[0]);
            l.setTextColor(cGray);
            l.setTextSize(12f);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.topMargin = dp(a, 12);
            body.addView(l, ll);
            TextView v = new TextView(a);
            v.setText(f[1]);
            v.setTextSize(15f);
            v.setTextIsSelectable(true);
            boolean st = "Статус".equals(f[0]);
            v.setTextColor(st ? cOrange : cText);
            if (st) v.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, -2);
            vl.topMargin = dp(a, 2);
            body.addView(v, vl);
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

        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
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
}

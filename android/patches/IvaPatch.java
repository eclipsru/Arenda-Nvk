// Ива — доработки приложения 10.15 поверх 10.14 (исходников 10.14 нет, только APK).
// Этот класс компилируется в GitHub Actions (build-apk.yml) и добавляется в APK отдельным classes3.dex.
// Точки вызова вставляет android/patches/apply.py в smali-код MainActivity (5 мест, см. там).
// Поля MainActivity читаются через reflection: класс не зависит от исходников приложения.
//
// Что делает (баг 1 от владельца, 04.10.2026):
//  1) «Туда и обратно» в заявке: галочка под «Самовывоз / Доставка», ×2 к доставке, текст попадает в заявку.
//  2) «Доставка · от N ₽/км» видно сразу, ещё до выбора доставки (раньше — пустая строка «Доставка»).
//  3) Исправление скрытой ошибки 10.14: доставка от 1 000 ₽ считалась в итоге как 1 ₽
//     (разделитель тысяч — обычный пробел, а parseRub режет по первому пробелу). Теперь неразрывный пробел.
//  4) Ограниченный админ (limited_admins — долг по комиссии) видит объяснение, а не молча пропавшую вкладку «Добавить».
//  5) Отказ базы в правах при публикации объясняется словами, а не текстом «row-level security».
package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

public final class IvaPatch {
    static final String NB = "\u00a0";       // неразрывный пробел
    static final String RUB = " \u20bd";     // " ₽"
    static final String TAG = "iva_round_trip";

    static volatile boolean round = false;   // выбрано «туда и обратно»
    static volatile String base = null;      // последний расчёт доставки «в одну сторону»
    static volatile String shown = null;     // что мы вернули приложению (с учётом ×2)
    static boolean limitedShown = false;

    private IvaPatch() {}

    // ── 1. Расчёт доставки готов (вызывается из lambda$calcDelivery перед записью delivTxt) ──
    public static String onPrice(String s) {
        if (s == null) return null;
        String one = normalize(s);
        base = one;
        String out = round ? doubled(one) : one;
        shown = out;
        return out;
    }

    /** «1 200 ₽ (12.3 км)» → «1 200 ₽ (12.3 км)» с неразрывным пробелом внутри суммы. */
    static String normalize(String s) {
        int i = s.indexOf(RUB);
        if (i <= 0) return s;
        return s.substring(0, i).replace(' ', '\u00a0') + s.substring(i);
    }

    static long rub(String s) {
        int i = s.indexOf(RUB);
        if (i <= 0) return -1;
        String d = s.substring(0, i).replaceAll("[^0-9]", "");
        if (d.length() == 0) return -1;
        try { return Long.parseLong(d); } catch (Exception e) { return -1; }
    }

    static String money(long v) {
        return String.format(Locale.US, "%,d", v).replace(",", NB);
    }

    /** Строка доставки «туда и обратно». Сумма — первой: по ней приложение считает ИТОГО. */
    static String doubled(String one) {
        long r = rub(one);
        if (r < 0) {
            // нет цены (цену за км уточнит владелец) — только пометка
            return one.indexOf(" \u043a\u043c") >= 0 ? one + " \u00b7 \u0442\u0443\u0434\u0430 \u0438 \u043e\u0431\u0440\u0430\u0442\u043d\u043e" : one;
        }
        String rest = one.substring(one.indexOf(RUB) + RUB.length()).trim();
        if (rest.startsWith("(") && rest.endsWith(")")) rest = rest.substring(1, rest.length() - 1);
        StringBuilder b = new StringBuilder();
        b.append(money(r * 2)).append(RUB)
         .append(" (\u0442\u0443\u0434\u0430 \u0438 \u043e\u0431\u0440\u0430\u0442\u043d\u043e: 2 \u00d7 ")   // (туда и обратно: 2 ×
         .append(money(r)).append(RUB);
        if (rest.length() > 0) b.append(", ").append(rest).append(" \u0432 \u043e\u0434\u043d\u0443 \u0441\u0442\u043e\u0440\u043e\u043d\u0443"); // в одну сторону
        b.append(')');
        return b.toString();
    }

    // ── 2. Перерисовка итога заявки (начало updateSummary) ──
    public static void onSummary(final Activity a) {
        try {
            RadioButton rb = (RadioButton) get(a, "rbDelivery");
            View rg = (View) get(a, "rgMethod");
            if (rb == null || rg == null) return;

            double rate = minRate(a);
            String label = rate > 0
                ? "\u0414\u043e\u0441\u0442\u0430\u0432\u043a\u0430 \u00b7 \u043e\u0442 " + money(Math.round(rate)) + RUB + "/\u043a\u043c"  // Доставка · от N ₽/км
                : "\u0414\u043e\u0441\u0442\u0430\u0432\u043a\u0430";
            if (!label.contentEquals(rb.getText())) rb.setText(label);

            ViewGroup parent = (ViewGroup) rg.getParent();
            if (parent == null) return;
            CheckBox cb = (CheckBox) parent.findViewWithTag(TAG);
            if (cb == null) {
                cb = new CheckBox(a);
                cb.setTag(TAG);
                cb.setText("\u0422\u0443\u0434\u0430 \u0438 \u043e\u0431\u0440\u0430\u0442\u043d\u043e (\u00d72) \u2014 \u0437\u0430\u0431\u0435\u0440\u0451\u043c \u0438\u043d\u0441\u0442\u0440\u0443\u043c\u0435\u043d\u0442 \u043f\u043e\u0441\u043b\u0435 \u0430\u0440\u0435\u043d\u0434\u044b"); // Туда и обратно (×2) — заберём инструмент после аренды
                cb.setTextSize(14f);
                cb.setTextColor(rb.getCurrentTextColor());
                if (android.os.Build.VERSION.SDK_INT >= 21) cb.setButtonTintList(rb.getButtonTintList());
                cb.setChecked(round);
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton v, boolean on) { setRound(a, on); }
                });
                parent.addView(cb, parent.indexOfChild(rg) + 1);
            }
            boolean show = rb.getVisibility() == View.VISIBLE && rb.isChecked();
            int vis = show ? View.VISIBLE : View.GONE;
            if (cb.getVisibility() != vis) cb.setVisibility(vis);
        } catch (Throwable ignore) {
            // доработка не должна ломать оформление заявки
        }
    }

    static void setRound(Activity a, boolean on) {
        round = on;
        try {
            Object cur = get(a, "delivTxt");
            String b = base;
            if (b != null && cur != null && cur.equals(shown)) {
                String out = on ? doubled(b) : b;
                shown = out;
                set(a, "delivTxt", out);
            }
            Method m = a.getClass().getDeclaredMethod("updateSummary");
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignore) {}
    }

    /** Минимальная цена за км среди выбранных инструментов с доставкой. */
    static double minRate(Activity a) {
        double min = 0;
        try {
            Object sel = get(a, "selected");
            if (!(sel instanceof Map)) return 0;
            for (Object o : ((Map<?, ?>) sel).values()) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject j = (JSONObject) o;
                if (!j.optBoolean("deliv")) continue;
                double p = j.optDouble("dprice", 0);
                if (p > 0 && (min == 0 || p < min)) min = p;
            }
        } catch (Throwable ignore) {}
        return min;
    }

    // ── 4. Кабинет админа (showAdmHub): объяснить, почему нет вкладки «Добавить» ──
    public static void onHub(Activity a) {
        try {
            Object lim = get(a, "meLimited");
            if (!(lim instanceof Boolean) || !((Boolean) lim) || limitedShown) return;
            limitedShown = true;
            new AlertDialog.Builder(a)
                .setTitle("\u0414\u043e\u0431\u0430\u0432\u043b\u0435\u043d\u0438\u0435 \u0438\u043d\u0441\u0442\u0440\u0443\u043c\u0435\u043d\u0442\u043e\u0432 \u043f\u0440\u0438\u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u043e") // Добавление инструментов приостановлено
                .setMessage("\u0417\u0430\u0434\u043e\u043b\u0436\u0435\u043d\u043d\u043e\u0441\u0442\u044c \u043f\u043e \u043a\u043e\u043c\u0438\u0441\u0441\u0438\u0438 \u043f\u0440\u0435\u0432\u044b\u0441\u0438\u043b\u0430 \u043b\u0438\u043c\u0438\u0442, \u043f\u043e\u044d\u0442\u043e\u043c\u0443 \u0432\u043a\u043b\u0430\u0434\u043a\u0430 \u00ab\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c\u00bb \u0441\u043a\u0440\u044b\u0442\u0430. \u041f\u043e\u0433\u0430\u0441\u0438\u0442\u0435 \u0437\u0430\u0434\u043e\u043b\u0436\u0435\u043d\u043d\u043e\u0441\u0442\u044c \u0438\u043b\u0438 \u043d\u0430\u043f\u0438\u0448\u0438\u0442\u0435 \u0432\u043b\u0430\u0434\u0435\u043b\u044c\u0446\u0443 \u043f\u043b\u043e\u0449\u0430\u0434\u043a\u0438.")
                // Задолженность по комиссии превысила лимит, поэтому вкладка «Добавить» скрыта. Погасите задолженность или напишите владельцу площадки.
                .setPositiveButton("\u041f\u043e\u043d\u044f\u0442\u043d\u043e", null) // Понятно
                .show();
        } catch (Throwable ignore) {}
    }

    // ── 5. Публикация не прошла (lambda$setupAddTool$255, после humanError) ──
    public static String publishError(String msg) {
        if (msg == null) return null;
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("row-level security") || m.contains("42501") || m.contains("permission denied")) {
            // нет прав: учётная запись арендодателя не активна / не подтверждена / почта входа другая
            return "\u043d\u0435\u0442 \u043f\u0440\u0430\u0432 \u043d\u0430 \u043f\u0443\u0431\u043b\u0438\u043a\u0430\u0446\u0438\u044e. \u0423\u0447\u0451\u0442\u043d\u0430\u044f \u0437\u0430\u043f\u0438\u0441\u044c \u0430\u0440\u0435\u043d\u0434\u043e\u0434\u0430\u0442\u0435\u043b\u044f \u043d\u0435 \u0430\u043a\u0442\u0438\u0432\u043d\u0430 \u0438\u043b\u0438 \u0432\u044b \u0432\u043e\u0448\u043b\u0438 \u0441 \u0434\u0440\u0443\u0433\u043e\u0439 \u043f\u043e\u0447\u0442\u043e\u0439. \u041d\u0430\u043f\u0438\u0448\u0438\u0442\u0435 \u0432\u043b\u0430\u0434\u0435\u043b\u044c\u0446\u0443 \u043f\u043b\u043e\u0449\u0430\u0434\u043a\u0438 (\u043a\u043e\u0434 42501)";
        }
        if (m.contains("jwt expired") || m.contains("invalid jwt")) {
            // сессия истекла — выйдите и войдите снова
            return "\u0441\u0435\u0441\u0441\u0438\u044f \u0438\u0441\u0442\u0435\u043a\u043b\u0430 \u2014 \u0432\u044b\u0439\u0434\u0438\u0442\u0435 \u0438\u0437 \u043f\u0440\u043e\u0444\u0438\u043b\u044f \u0438 \u0432\u043e\u0439\u0434\u0438\u0442\u0435 \u0441\u043d\u043e\u0432\u0430";
        }
        return msg;
    }

    // ── reflection ──
    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignore) {}
        }
        throw new NoSuchFieldException(name);
    }
    static Object get(Object o, String name) {
        try { return field(o.getClass(), name).get(o); } catch (Throwable e) { return null; }
    }
    static void set(Object o, String name, Object v) {
        try { field(o.getClass(), name).set(o, v); } catch (Throwable ignore) {}
    }
}

// Ива — доработки приложения поверх 10.14 (исходников 10.14 нет, только APK). Сборки: 10.15 (п. 1–5), 10.16 (+ п. 6),
// 10.17 (+ п. 7–10: нижнее меню), 10.18 (+ п. 11: выбранный раздел нижнего меню),
// 10.19 (+ п. 12: окантовка аватарки в открытом «Кабинете»), 10.20 (+ п. 13: виньетка фоновой картинки главной),
// 10.21 (+ п. 14: скруглённые углы фоновой картинки главной).
// Этот класс компилируется в GitHub Actions
// (build-apk.yml) и добавляется в APK отдельным classes3.dex. Точки вызова вставляет android/patches/apply.py
// в smali-код MainActivity (11 мест, 13 вызовов IvaPatch, потому что onPrice вызывается 4 раза).
// Поля MainActivity читаются через reflection: класс не зависит от исходников приложения.
//
// Что делает (баг 1 от владельца, 04.10.2026):
//  1) «Туда и обратно» в заявке: галочка под «Самовывоз / Доставка», ×2 к доставке, текст попадает в заявку.
//  2) «Доставка · от N ₽/км» видно сразу, ещё до выбора доставки (раньше — пустая строка «Доставка»).
//  3) Исправление скрытой ошибки 10.14: доставка от 1 000 ₽ считалась в итоге как 1 ₽
//     (разделитель тысяч — обычный пробел, а parseRub режет по первому пробелу). Теперь неразрывный пробел.
//  4) Ограниченный админ (limited_admins — долг по комиссии) видит объяснение, а не молча пропавшую вкладку «Добавить».
//  5) Отказ базы в правах при публикации объясняется словами, а не текстом «row-level security».
//  6) (обращение владельца 07.10.2026) «Нет связи с сервером» показывается вместе с технической причиной —
//     какой адрес и что именно не вышло: без этого «провайдер режет трафик» и «сервер лежит» не отличить.
//  7) (10.17, просьба владельца) вместо «+» в нижнем меню — круглый логотип приложения.
//  8) (10.17) вход и запуск: профиль админа подгружается сразу, чтобы аватарка появилась в меню без захода в «Кабинет».
//  9) (10.17) «Кабинет» в нижнем меню: загруженная аватарка кружком 40dp вместо иконки.
// 10) (10.17) выход: аватарка убирается, иконка «Кабинета» снова на месте.
// 11) (10.18) выбранный раздел нижнего меню получает оранжевую окантовку, значок и подпись —
//     оранжевые; заливки оранжевого круга больше нет. Логотип и аватарка не перекрашиваются.
// 12) (10.19) когда открыт «Кабинет», на самой аватарке появляется такая же оранжевая окантовка,
//     как на логотипе и остальных вкладках; при переходе в другой раздел окантовка исчезает.
// 13) (10.20) на изображении pageBg главной страницы появляется настоящая виньетка:
//     центр остаётся светлее, края темнее; слой стоит под поиском и категориями и не перехватывает их касания.
// 14) (10.21) у этой фоновой картинки скруглены все четыре угла, не затрагивая поиск и категории.
package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

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
        // 10.55: доставка — средняя по ценам за км инструментов заявки (штатно — сумма)
        String one = normalize(IvaOrderPatch.onPrice(s));
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
        // 10.55: свои строки заявки (срок у каждого инструмента) и итог — сразу после штатного updateSummary
        try { IvaOrderPatch.onSummary(a); } catch (Throwable ignore) {}
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

    // ── 6. Сбой связи (MainActivity.humanError): к общей фразе добавляем причину словами ──
    // Зачем: 07.10.2026 владелец на двух телефонах видел «Нет связи с сервером. Проверьте интернет…»,
    // хотя интернет работал, а сайт стал обновляться медленно. Общая фраза скрывает, что именно не вышло:
    // адрес сервера не определился, подключение не состоялось или истекло время ожидания (12 и 15 секунд).
    // Без этой строки «провайдер режет трафик до сервера» и «сервер не отвечает» не отличить.
    static final String NET_MSG = "\u041d\u0435\u0442 \u0441\u0432\u044f\u0437\u0438 \u0441 \u0441\u0435\u0440\u0432\u0435\u0440\u043e\u043c. \u041f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 \u0438 \u043f\u043e\u043f\u0440\u043e\u0431\u0443\u0439\u0442\u0435 \u0435\u0449\u0451 \u0440\u0430\u0437.";
    static final String TECH = "\u0422\u0435\u0445\u043d\u0438\u0447\u0435\u0441\u043a\u0438: ";

    public static String netError(String raw) {
        String t = brief(raw);
        if (t.length() == 0) return NET_MSG;
        return NET_MSG + "\n\n" + TECH + t;
    }

    /** Причина в одну строку и не длиннее 200 знаков — иначе не влезет в окно сообщения. */
    static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        if (t.length() > 200) t = t.substring(0, 197) + "\u2026";
        return t;
    }

    // ── 7–10. Нижнее меню (10.17) ──
    // Аватарка — поле admins.avatar: тот же адрес, что на сайте в шапке кабинета. Картинку грузит штатный
    // loadPhoto приложения (кэш, фоновый поток; при ошибке остаётся иконка). Иконку navProfIc не трогаем:
    // поверх неё кладём круглый вид CircleImageView 40dp, при выходе он убирается и иконка видна снова.
    static final String AV_TAG = "iva_nav_avatar";
    static final String AV_RING_TAG = "iva_nav_avatar_ring";
    static final String HOME_VIGNETTE_TAG = "iva_home_vignette";

    /**
     * 13–14) Фоновая картинка главной страницы уже лежит в вертикальном контейнере pageHome:
     * searchBox и динамические catGrid/feedGrid идут следующими детьми. Оборачиваем только
     * pageBg и виньетку в FrameLayout: картинка получает скруглённые углы, а поиск и плитки
     * остаются соседями снаружи и не затемняются, не сдвигаются и не теряют касания.
     */
    static void installHomeVignette(Activity a) {
        LinearLayout parent = null;
        ImageView bg = null;
        int index = -1;
        ViewGroup.LayoutParams source = null;
        FrameLayout frame = null;
        try {
            Object raw = get(a, "pageBg");
            if (!(raw instanceof ImageView)) return;
            bg = (ImageView) raw;
            ViewParent rawParent = bg.getParent();
            if (!(rawParent instanceof LinearLayout)) return;
            parent = (LinearLayout) rawParent;
            if (parent.findViewWithTag(HOME_VIGNETTE_TAG) != null) return;
            index = parent.indexOfChild(bg);
            if (index < 0) return;

            float density = a.getResources().getDisplayMetrics().density;
            source = bg.getLayoutParams();
            if (source == null) source = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(141, density));

            frame = new FrameLayout(a);
            frame.setTag(HOME_VIGNETTE_TAG);
            frame.setClipChildren(true);
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                final float radius = px(16, density);
                frame.setClipToOutline(true);
                frame.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
            }

            // The wrapper replaces pageBg in the same slot, so the vertical layout and
            // the intentionally negative margin before the search box stay unchanged.
            parent.removeViewAt(index);
            frame.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            HomeVignette overlay = new HomeVignette(a);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            frame.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            parent.addView(frame, index, source);
        } catch (Throwable ignore) {
            // Restore the original image if a device rejects an unusual layout parameter.
            try {
                if (parent != null && bg != null && bg.getParent() != parent) {
                    if (frame != null && frame.getParent() == parent) parent.removeView(frame);
                    if (bg.getParent() instanceof ViewGroup) {
                        ((ViewGroup) bg.getParent()).removeView(bg);
                    }
                    parent.addView(bg, Math.max(0, Math.min(index, parent.getChildCount())), source);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Transparent in the centre, progressively black towards the image edges. */
    static final class HomeVignette extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RadialGradient gradient;

        HomeVignette(Context context) {
            super(context);
            setWillNotDraw(false);
            paint.setDither(true);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            if (w <= 0 || h <= 0) {
                gradient = null;
                return;
            }
            float radius = (float) Math.hypot(w / 2f, h / 2f) * 0.82f;
            gradient = new RadialGradient(
                w / 2f, h / 2f, radius,
                new int[] { 0x00000000, 0x16000000, 0x58000000, 0xb8000000 },
                new float[] { 0f, 0.38f, 0.72f, 1f },
                Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (gradient == null) return;
            paint.setShader(gradient);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }
    }

    /** 7) Конец onCreate: «+» → логотип. Затем профиль (аватарка), если уже вошли. */
    public static void onCreated(Activity a) {
        try {
            logoOnPlus(a, a.getResources().getDisplayMetrics().density);
            // restoreSession() мог выбрать вкладку до этого вызова; повторяем её стиль после замены «+».
            onTab(a, (String) get(a, "curTab"));
            installHomeVignette(a);
        } catch (Throwable ignore) {
            // логотип и подсветка — только внешний вид: без них меню работает как раньше
        }
        reloadProfile(a);
    }

    /** 8) Конец saveSession (вход и восстановление сессии): профиль админа → аватарка в меню. */
    public static void onSession(Activity a) {
        reloadProfile(a);
    }

    /** 9) lambda$loadAdminProfile$201, сразу после чтения поля avatar. Пустой адрес — аватарки нет. */
    public static void onAvatar(final Activity a, final String url) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() { applyAvatar(a, url); }
        });
    }

    // ── 11–12. Выбранный раздел нижнего меню и окантовка аватарки (10.18–10.19) ──
    // Штатный setCircle() вызывается в showTab() и рисует оранжевую заливку с белым значком.
    // Этот вызов стоит после всех четырёх setCircle: заливка убирается, а выбранный пункт получает
    // прозрачный круг с оранжевой окантовкой и оранжевые текст/значок.
    /**
     * 13. Новый метод MainActivity.onNewIntent (10.59): нажали на уведомление, когда приложение уже открыто
     * или свёрнуто. Штатных методов не меняет; переход — в IvaNotifyPatch. Любая ошибка — приложение просто
     * показывается как было.
     */
    public static void onNewIntent(Activity a, android.content.Intent it) {
        try { IvaNotifyPatch.onNewIntent(a, it); } catch (Throwable ignore) {}
    }

    public static void onTab(Activity a, String tab) {
        try { installBack(a); } catch (Throwable ignore) {}   // 10.63: системная «Назад» — внутри приложения
        try {
            String t = tab == null ? "" : tab;
            int orange = navColor(a, "orange", 0xffff9800);
            int gray = navColor(a, "gray", 0xff9aa0a6);
            styleNavItem(a, get(a, "circHome"), get(a, "navHomeIc"), get(a, "navHomeTxt"),
                navSelected(t, "home"), orange, gray);
            styleNavItem(a, get(a, "circChat"), get(a, "navChatIc"), get(a, "navChatTxt"),
                navSelected(t, "chat"), orange, gray);
            styleNavItem(a, get(a, "circOrders"), get(a, "navOrdersIc"), get(a, "navOrdersTxt"),
                navSelected(t, "orders"), orange, gray);
            boolean profile = navSelected(t, "profile");
            styleNavItem(a, get(a, "circProfile"), get(a, "navProfIc"), get(a, "navProfTxt"),
                profile, orange, gray);
            styleAvatarOutline(a, profile, orange);
            styleLogoNav(a, isLogoTab(t), orange);
            // Аватарка — фотография, а не иконка нижнего меню. Никогда не накладываем на неё tint.
            clearAvatarFilter(a);
        } catch (Throwable ignore) {
            // подсветка — только внешний вид: переход между разделами не должен ломаться
        }
        // 10.43: штатный переход прячет раздел клиента «Избранное / Мои инструменты» (логотип в меню).
        try { IvaClientPatch.onShowTab(a, tab); } catch (Throwable ignore) {}
        // 10.45: строка «Ваш город», ручка и панель фильтров на главной (ставятся и прячутся здесь же).
        try { IvaSearchPatch.onShowTab(a, tab); } catch (Throwable ignore) {}
        // 10.54: чат — список переписок по инструментам, закреплённая карточка, аватарки, без автосообщения.
        try { IvaChatPatch.onShowTab(a, tab); } catch (Throwable ignore) {}
        // 10.55: заявка — ФИО по полям, без количества, свой срок у инструмента, «+ Добавить инструмент», один прокат.
        try { IvaOrderPatch.onShowTab(a, tab); } catch (Throwable ignore) {}
        // 10.56: «Настройки» в кабинете (уведомления с переключателями) и свой опрос вместо штатного.
        try { IvaNotifyPatch.onShowTab(a, tab); } catch (Throwable ignore) {}
    }

    /** Чистая проверка выбора пункта — вынесена отдельно, чтобы её можно было проверить без Activity. */
    static boolean navSelected(String current, String item) {
        return current != null && item != null && item.equals(current);
    }

    /** Центральный логотип ведёт в заявку, в раздел добавления инструментов или (у клиента, 10.43)
     * в раздел «Избранное / Мои инструменты». */
    static boolean isLogoTab(String tab) {
        return "order".equals(tab) || "admhub".equals(tab) || "addtool".equals(tab) || "clienthub".equals(tab);
    }

    static int navColor(Activity a, String name, int fallback) {
        try {
            int rid = a.getResources().getIdentifier(name, "color", a.getPackageName());
            return rid == 0 ? fallback : a.getResources().getColor(rid);
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    static void styleNavItem(Activity a, Object circle, Object icon, Object label,
                             boolean selected, int orange, int gray) {
        if (circle instanceof View) {
            outline((View) circle, selected, a.getResources().getDisplayMetrics().density, orange);
        }
        if (icon instanceof ImageView) {
            ((ImageView) icon).setColorFilter(selected ? orange : gray);
        }
        if (label instanceof TextView) {
            ((TextView) label).setTextColor(selected ? orange : gray);
        }
    }

    /** Прозрачный круг без заливки; окантовка появляется только у выбранного раздела. */
    static void outline(View view, boolean selected, float density, int orange) {
        if (view == null) return;
        if (!selected) {
            view.setBackgroundColor(Color.TRANSPARENT);
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        bg.setStroke(px(2, density), orange);
        view.setBackground(bg);
    }

    /** Центральный логотип не перекрашиваем; меняем только его внешний круг. */
    static void styleLogoNav(Activity a, boolean selected, int orange) {
        View plus = a.findViewById(id(a, "navPlus"));
        View col = plus instanceof ViewGroup ? ((ViewGroup) plus).getChildAt(0) : null;
        View circle = col instanceof ViewGroup ? ((ViewGroup) col).getChildAt(0) : null;
        View icon = circle instanceof ViewGroup ? ((ViewGroup) circle).getChildAt(0) : null;
        if (circle != null) outline(circle, selected, a.getResources().getDisplayMetrics().density, orange);
        if (icon instanceof ImageView) ((ImageView) icon).clearColorFilter();
    }

    static void clearAvatarFilter(Activity a) {
        ViewGroup box = navBox(a);
        View avatar = box == null ? null : box.findViewWithTag(AV_TAG);
        if (avatar instanceof ImageView) ((ImageView) avatar).clearColorFilter();
    }

    /** Окантовка поверх фотографии: родительский круг убираем, чтобы не получить двойной ободок. */
    static void styleAvatarOutline(Activity a, boolean selected, int orange) {
        ViewGroup box = navBox(a);
        View avatar = box == null ? null : box.findViewWithTag(AV_TAG);
        View ring = box == null ? null : box.findViewWithTag(AV_RING_TAG);
        float density = a.getResources().getDisplayMetrics().density;
        View profileCircle = get(a, "circProfile") instanceof View ? (View) get(a, "circProfile") : null;

        if (!(avatar instanceof ImageView)) {
            if (ring != null && box != null) box.removeView(ring);
            if (profileCircle != null) outline(profileCircle, selected, density, orange);
            return;
        }

        // Фотография закрывает фон circProfile, поэтому рисуем прозрачный слой-кольцо
        // поверх самой аватарки. Логотип и остальные пункты по-прежнему используют свой круг.
        if (profileCircle != null) outline(profileCircle, false, density, orange);
        int size = px(40, density);
        if (selected) {
            if (ring == null) {
                ring = new View(a);
                ring.setTag(AV_RING_TAG);
                ring.setClickable(false);
                box.addView(ring, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            }
            outline(ring, true, density, orange);
        } else if (ring != null) {
            box.removeView(ring);
        }
    }

    /** 10) Конец logoutLocal: аватарку убираем, иконка «Кабинета» снова видна. */
    public static void onLogout(final Activity a) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    ViewGroup box = navBox(a);
                    View av = box == null ? null : box.findViewWithTag(AV_TAG);
                    View ring = box == null ? null : box.findViewWithTag(AV_RING_TAG);
                    if (av != null) box.removeView(av);
                    if (ring != null) box.removeView(ring);
                    onTab(a, (String) get(a, "curTab"));
                } catch (Throwable ignore) {
                    // иконка остаётся на месте
                }
            }
        });
    }

    /** «+» (navPlus) → круглый логотип: тот же ic_logo, что на заставке, размером 50dp.
     * Оранжевая окантовка рисуется только styleLogoNav() для активного раздела. */
    static void logoOnPlus(Activity a, float d) {
        View plus = a.findViewById(id(a, "navPlus"));
        View col = plus instanceof ViewGroup ? ((ViewGroup) plus).getChildAt(0) : null;      // столбик кнопки
        View circ = col instanceof ViewGroup ? ((ViewGroup) col).getChildAt(0) : null;       // оранжевый кружок 50dp
        View ic = circ instanceof FrameLayout ? ((FrameLayout) circ).getChildAt(0) : null;   // иконка «+» 26dp
        if (!(ic instanceof ImageView)) return;
        int s = px(50, d);
        Bitmap logo = roundLogo(a, s, px(2, d));
        if (logo == null) return;
        ic.setLayoutParams(new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        ((ImageView) ic).setImageBitmap(logo);
        ((ImageView) ic).clearColorFilter();
    }

    /** Логотип из ресурсов приложения (ic_logo) → круг size×size; inset — прозрачный отступ,
     * в который при активном разделе попадает оранжевая окантовка родительского круга. */
    static Bitmap roundLogo(Activity a, int size, int inset) {
        int rid = a.getResources().getIdentifier("ic_logo", "drawable", a.getPackageName());
        if (rid == 0) return null;
        Bitmap src = BitmapFactory.decodeResource(a.getResources(), rid);
        return src == null ? null : circle(src, size, inset);
    }

    /** Картинка, обрезанная по кругу: края срезаются, центр остаётся (так же, как у аватарки). */
    static Bitmap circle(Bitmap src, int size, int inset) {
        int w = src.getWidth(), h = src.getHeight();
        float side = size - 2f * inset;
        if (w <= 0 || h <= 0 || side <= 0) return null;
        float s = Math.max(side / w, side / h);
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(inset + (side - w * s) / 2f, inset + (side - h * s) / 2f);
        BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(sh);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawCircle(size / 2f, size / 2f, side / 2f, p);
        return out;
    }

    /** Показать аватарку в кружке «Кабинета» или убрать её (если адреса нет или уже вышли из кабинета). */
    static void applyAvatar(Activity a, String url) {
        try {
            ViewGroup box = navBox(a);
            if (box == null) return;
            View av = box.findViewWithTag(AV_TAG);
            // ответ сервера может прийти уже после выхода — тогда аватарку не возвращаем
            if (!wantsAvatar(url) || get(a, "accessToken") == null) {
                if (av != null) box.removeView(av);
                int orange = navColor(a, "orange", 0xffff9800);
                styleAvatarOutline(a, navSelected((String) get(a, "curTab"), "profile"), orange);
                return;
            }
            if (av == null) {
                av = newCircle(a);
                av.setTag(AV_TAG);
                int s = px(40, a.getResources().getDisplayMetrics().density);
                box.addView(av, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            }
            loadPhoto(a, (ImageView) av, url.trim());
            int orange = navColor(a, "orange", 0xffff9800);
            styleAvatarOutline(a, navSelected((String) get(a, "curTab"), "profile"), orange);
        } catch (Throwable ignore) {
            // аватарка не должна ломать меню: останется иконка
        }
    }

    /** Адрес аватарки — только http(s). Пустое поле и любой другой текст означают «аватарки нет». */
    static boolean wantsAvatar(String url) {
        if (url == null) return false;
        String u = url.trim();
        return u.startsWith("https://") || u.startsWith("http://");
    }

    /** dp → пиксели экрана. */
    static int px(float dp, float density) {
        return Math.round(dp * density);
    }

    static int id(Activity a, String name) {
        return a.getResources().getIdentifier(name, "id", a.getPackageName());
    }

    /** Кружок «Кабинета» (40dp) — родитель иконки navProfIc. Сюда кладём аватарку. */
    static ViewGroup navBox(Activity a) {
        Object ic = get(a, "navProfIc");
        ViewParent p = ic instanceof View ? ((View) ic).getParent() : null;
        return p instanceof ViewGroup ? (ViewGroup) p : null;
    }

    /** Круглая картинка — штатный класс приложения CircleImageView (в android.jar его нет, поэтому через reflection). */
    static ImageView newCircle(Activity a) throws Exception {
        Class<?> c = Class.forName("ru.prokatnvsk.app.CircleImageView");
        return (ImageView) c.getConstructor(Context.class).newInstance(a);
    }

    /** Штатная загрузка фото приложения: кэш, фоновый поток, при ошибке — без изменений. Ключ кэша — сам адрес. */
    static void loadPhoto(Activity a, ImageView v, String url) throws Exception {
        Method m = a.getClass().getDeclaredMethod("loadPhoto", ImageView.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(a, v, url, "navav" + url);
    }

    /** Профиль админа (в нём аватарка) грузим при запуске и входе, а не только когда открыт «Кабинет». */
    static void reloadProfile(Activity a) {
        try {
            if (get(a, "accessToken") == null) return;
            Method m = a.getClass().getDeclaredMethod("loadAdminProfile");
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignore) {}
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

    // ───────────── 10.63: кнопка «Назад» на телефоне работает внутри приложения (просьба владельца 14.10.2026) ─────────────
    // В 10.14 своего onBackPressed нет — «Назад» закрывала приложение с любого экрана. Новых вставок в smali нет:
    // обёртка окна (Window.Callback) ловит только клавишу «Назад», всё остальное отдаёт приложению как было.
    // Порядок: открытый фильтр → кнопка «‹ …» на экране (штатные «‹ Назад», «‹ Кабинет», «‹ Все клиенты», наша
    // «‹ Назад к чатам») → экран «Заявка отправлена» → «На главную» → со вкладок и раздела логотипа — на главную →
    // на главной (и на экране входа) — свернуть приложение, как кнопка «Домой» (решение владельца 14.10.2026).
    // Окна поверх (шторка заявки, архив, выбор города, вопросы) закрываются «Назад» сами — это отдельные окна.
    static final String BACK_PANEL = "panel", BACK_BUTTON = "button", BACK_SUCCESS = "success", BACK_HOME = "home", BACK_HIDE = "background";
    static final String BACK_MARK = "\u2039";   // «‹»

    /** Что делает «Назад» — чистая функция (проверяется тестом). */
    static String backAction(boolean panelOpen, boolean successShown, boolean mainShown, boolean backButton, String tab, boolean hubOpen) {
        if (panelOpen) return BACK_PANEL;
        if (successShown) return BACK_SUCCESS;
        if (!mainShown) return BACK_HIDE;                      // вход / заставка — свернуть
        if (backButton) return BACK_BUTTON;
        if (hubOpen) return BACK_HOME;
        return "home".equals(tab == null ? "" : tab) ? BACK_HIDE : BACK_HOME;
    }

    static void installBack(final Activity a) {
        final android.view.Window w = a.getWindow();
        if (w == null) return;
        final android.view.Window.Callback orig = w.getCallback();
        if (orig == null || java.lang.reflect.Proxy.isProxyClass(orig.getClass())) return;   // уже стоит
        Object px = java.lang.reflect.Proxy.newProxyInstance(IvaPatch.class.getClassLoader(),
            new Class<?>[]{android.view.Window.Callback.class}, new java.lang.reflect.InvocationHandler() {
                @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                    if (args != null && args.length == 1 && args[0] instanceof android.view.KeyEvent && "dispatchKeyEvent".equals(m.getName())) {
                        android.view.KeyEvent e = (android.view.KeyEvent) args[0];
                        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
                            if (e.getAction() == android.view.KeyEvent.ACTION_UP && !e.isCanceled()) {
                                try { onBack(a); } catch (Throwable t) { try { a.moveTaskToBack(true); } catch (Throwable ignore) {} }
                            }
                            return Boolean.TRUE;
                        }
                    }
                    try { return m.invoke(orig, args); }
                    catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause() != null ? ex.getCause() : ex; }
                }
            });
        w.setCallback((android.view.Window.Callback) px);
    }

    static boolean shown(Activity a, String idName) {
        int id = a.getResources().getIdentifier(idName, "id", a.getPackageName());
        View v = id == 0 ? null : a.findViewById(id);
        return v != null && v.isShown();
    }

    /** Видимая кнопка «‹ …» на экране (кроме нижнего меню): первая по порядку. */
    static View backButton(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView && !(v instanceof android.widget.EditText) && v.isShown()) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().trim().startsWith(BACK_MARK) && v.hasOnClickListeners()) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = backButton(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    static void onBack(Activity a) {
        ViewGroup pages = IvaClientPatch.hubPages(a);
        View btn = pages == null ? null : backButton(pages);
        Object ct = get(a, "curTab");
        String act = backAction(IvaSearchPatch.panelOpen(), shown(a, "scrSuccess"), shown(a, "scrMain"), btn != null,
            ct instanceof String ? (String) ct : "", IvaClientPatch.hubVisible);
        if (BACK_PANEL.equals(act)) IvaSearchPatch.closePanel(a, true);
        else if (BACK_SUCCESS.equals(act)) {
            int id = a.getResources().getIdentifier("btnHome", "id", a.getPackageName());
            View h = id == 0 ? null : a.findViewById(id);
            if (h != null) h.performClick(); else a.moveTaskToBack(true);
        }
        else if (BACK_BUTTON.equals(act)) btn.performClick();
        else if (BACK_HOME.equals(act)) IvaNotifyPatch.tab(a, "home");
        else a.moveTaskToBack(true);
    }
}

// Ива — доработки приложения поверх 10.14 (исходников 10.14 нет, только APK). Сборки: 10.15 (п. 1–5), 10.16 (+ п. 6),
// 10.17 (+ п. 7–10: нижнее меню), 10.18 (+ п. 11: выбранный раздел нижнего меню),
// 10.19 (+ п. 12: окантовка аватарки в открытом «Кабинете»), 10.20 (+ п. 13: виньетка фоновой картинки главной),
// 10.21 (+ п. 14: скруглённые углы фоновой картинки главной),
// 10.22 (+ п. 15: новые сообщения в чате приложения подгружаются без ручного обновления).
// Этот класс компилируется в GitHub Actions
// (build-apk.yml) и добавляется в APK отдельным classes3.dex. Точки вызова вставляет android/patches/apply.py
// в smali-код MainActivity (17 мест; renderFeed имеет два выхода, onPrice вызывается 4 раза).
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
// 15) (10.22) пока открыт раздел «Сообщения», чат приложения перечитывается каждые 5 секунд:
//     ответ собеседника появляется сам, без выхода из чата и ручного обновления страницы.
package ru.prokatnvsk.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
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
    static final String PROFILE_BUTTON_TAG = "iva_client_profile_button";
    static final String FILTER_BUTTON_TAG = "iva_catalog_filter_button";
    static final String DISTANCE_TAG = "iva_distance_";
    static final int CLIENT_AVATAR_REQUEST = 6403;
    static final int CLIENT_LOCATION_REQUEST = 6404;
    static final long CHAT_REFRESH_MS = 5000L;

    // Клиентский профиль не смешивается с admins. Состояние формы живёт только
    // пока открыто окно и очищается при выходе из аккаунта.
    static JSONObject clientProfile;
    static String clientProfileEmail = "";
    static String clientProfileUid = "";
    static AlertDialog clientProfileDialog;
    static EditText[] clientProfileFields;
    static TextView clientAvatarStatus;
    static String clientAvatarPath = "";

    // Фильтр главной страницы native: те же семь параметров, что и web-каталог.
    static ArrayList allNativeTools;
    static boolean nativeFilterActive = false;
    static String nativeFilterQuery = "";
    static String nativeFilterCat = "";
    static String nativeFilterSub = "";
    static String nativeFilterCity = "";
    static String nativeFilterFrom = "";
    static String nativeFilterTo = "";
    static String nativeFilterConds = "";
    static boolean nativeFilterDelivery = false;

    // Геолокация телефона имеет приоритет; адрес из приватного customer_profiles
    // используется как fallback, если разрешение не выдано или GPS недоступен.
    static double[] clientCoords;
    static boolean clientCoordsLoading = false;
    static boolean locationAsked = false;
    static JSONObject activeDetail;
    static boolean activeDetailIsService = false;
    static String deliveryEstimateKey = "";
    static boolean deliveryEstimateLoading = false;
    static final HashMap<String, double[]> nativeGeoCache = new HashMap<String, double[]>();
    static final HashMap<String, String> nativeDistanceCache = new HashMap<String, String>();
    // Lazy: the JVM test uses Android's compile-time stubs, whose Handler constructor throws.
    // The real APK initializes this only after the Activity has entered the chat tab.
    static Handler CHAT_HANDLER;
    static WeakReference<Activity> chatActivity = new WeakReference<Activity>(null);
    static final Runnable CHAT_TICK = new Runnable() {
        @Override public void run() {
            Activity a = chatActivity.get();
            if (a == null || a.isFinishing() ||
                (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) {
                chatActivity.clear();
                return;
            }
            try {
                Method m = a.getClass().getDeclaredMethod("loadChat");
                m.setAccessible(true);
                m.invoke(a);
            } catch (Throwable ignore) {
                // A refresh failure must not break navigation; the next tick retries.
            }
            if (CHAT_HANDLER != null) CHAT_HANDLER.postDelayed(this, CHAT_REFRESH_MS);
        }
    };

    /** Refresh the native chat while its tab is visible; stop it on every other tab. */
    static void chatPoll(Activity a, boolean active) {
        if (CHAT_HANDLER != null) CHAT_HANDLER.removeCallbacks(CHAT_TICK);
        chatActivity.clear();
        if (active && a != null) {
            if (CHAT_HANDLER == null) CHAT_HANDLER = new Handler(Looper.getMainLooper());
            chatActivity = new WeakReference<Activity>(a);
            CHAT_HANDLER.post(CHAT_TICK);
        }
    }

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
            installHomeFilter(a);
        } catch (Throwable ignore) {
            // логотип и подсветка — только внешний вид: без них меню работает как раньше
        }
        reloadProfile(a);
        onProfile(a);
    }

    /** 8) Конец saveSession (вход и восстановление сессии): профиль админа → аватарка в меню. */
    public static void onSession(Activity a) {
        reloadProfile(a);
        onProfile(a);
        installHomeFilter(a);
    }

    /** 9) lambda$loadAdminProfile$201, сразу после чтения поля avatar. Пустой адрес — аватарки нет. */
    public static void onAvatar(final Activity a, final String url) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() { applyAvatar(a, url); }
        });
    }

    // ── 16. Профиль обычного клиента (отдельно от admins) ──
    static void ensureClientProfile() {
        if (clientProfile == null) clientProfile = new JSONObject();
    }

    static boolean isClient(Activity a) {
        Object token = get(a, "accessToken");
        if (!(token instanceof String) || ((String) token).trim().isEmpty()) return false;
        try {
            Method m = a.getClass().getDeclaredMethod("isAdminEmail");
            m.setAccessible(true);
            Object v = m.invoke(a);
            return !(v instanceof Boolean) || !((Boolean) v);
        } catch (Throwable ignore) {
            // Если у конкретного варианта APK нет метода-проверки, не прячем
            // пользовательские настройки: admins всё равно видят свой штатный UI.
            return true;
        }
    }

    static ViewGroup profileHost(Activity a) {
        Object raw = get(a, "pageProfile");
        if (!(raw instanceof ViewGroup)) return null;
        ViewGroup page = (ViewGroup) raw;
        if (page instanceof ScrollView && page.getChildCount() > 0 && page.getChildAt(0) instanceof ViewGroup)
            return (ViewGroup) page.getChildAt(0);
        if (!(page instanceof LinearLayout) && page.getChildCount() == 1 && page.getChildAt(0) instanceof ViewGroup)
            return (ViewGroup) page.getChildAt(0);
        return page;
    }

    /** Кнопка добавляется в уже существующую прокручиваемую страницу, а не в
     * landlord_profiles/admins. Поэтому обычный клиент не видит админские поля. */
    static void installClientProfile(final Activity a) {
        if (!isClient(a)) return;
        try {
            final ViewGroup host = profileHost(a);
            if (host == null || host.findViewWithTag(PROFILE_BUTTON_TAG) != null) return;
            Button b = new Button(a);
            b.setTag(PROFILE_BUTTON_TAG);
            b.setText("\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u043f\u0440\u043e\u0444\u0438\u043b\u044f \u043a\u043b\u0438\u0435\u043d\u0442\u0430");
            b.setAllCaps(false);
            b.setTextSize(14f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(px(16, a.getResources().getDisplayMetrics().density), px(8, a.getResources().getDisplayMetrics().density), px(16, a.getResources().getDisplayMetrics().density), px(8, a.getResources().getDisplayMetrics().density));
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showClientProfile(a); }
            });
            host.addView(b);
            loadClientProfile(a);
        } catch (Throwable ignore) {}
    }

    static EditText profileEdit(Activity a, LinearLayout box, String label, String value, int inputType) {
        TextView l = new TextView(a);
        l.setText(label);
        l.setTextSize(13f);
        l.setTextColor(Color.GRAY);
        l.setPadding(0, px(8, a.getResources().getDisplayMetrics().density), 0, px(3, a.getResources().getDisplayMetrics().density));
        box.addView(l);
        EditText e = new EditText(a);
        e.setSingleLine(false);
        e.setText(value == null ? "" : value);
        e.setTextSize(15f);
        e.setInputType(inputType);
        e.setPadding(px(10, a.getResources().getDisplayMetrics().density), px(8, a.getResources().getDisplayMetrics().density), px(10, a.getResources().getDisplayMetrics().density), px(8, a.getResources().getDisplayMetrics().density));
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    static String activityText(Activity a, String name) {
        Object value = get(a, name);
        return value == null ? "" : String.valueOf(value);
    }

    static String profileName(JSONObject p) {
        String s = p == null ? "" : p.optString("surname", "").trim();
        String n = p == null ? "" : p.optString("given_name", "").trim();
        String o = p == null ? "" : p.optString("patronymic", "").trim();
        String all = (s + " " + n + " " + o).trim().replaceAll("\\s+", " ");
        if (all.isEmpty()) {
            Object v = p == null ? null : get(p, "legacy_name");
            all = v == null ? "" : String.valueOf(v);
        }
        return all;
    }

    static void loadClientProfile(final Activity a) {
        ensureClientProfile();
        if (!isClient(a)) return;
        final String email = String.valueOf(get(a, "userEmail"));
        if (email.equalsIgnoreCase(clientProfileEmail) && clientProfile.length() > 0) return;
        clientProfileEmail = email;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = apiCall(a, "/rest/v1/rpc/get_my_customer_profile", "POST", "{}", true);
                    if (r == null || !r[0].startsWith("2")) return;
                    final JSONObject p = rpcFirst(r[1]);
                    clientProfile = p;
                    clientProfileUid = p.optString("user_id", "");
                    clientAvatarPath = p.optString("avatar_path", "");
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        Object dialog = clientProfileDialog;
                        if (dialog instanceof AlertDialog && ((AlertDialog) dialog).isShowing()) fillClientProfileFields();
                    }});
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    static void fillClientProfileFields() {
        ensureClientProfile();
        if (clientProfileFields == null || clientProfileFields.length < 9) return;
        String[] v = new String[] {
            clientProfile.optString("surname", ""), clientProfile.optString("given_name", ""),
            clientProfile.optString("patronymic", ""), clientProfile.optString("phone", ""),
            clientProfile.optString("address", "")
        };
        for (int i = 0; i < v.length && i < clientProfileFields.length; i++) clientProfileFields[i].setText(v[i]);
        JSONObject pp = clientProfile.optJSONObject("passport_data");
        if (pp == null) pp = new JSONObject();
        clientProfileFields[5].setText(pp.optString("series", ""));
        clientProfileFields[6].setText(pp.optString("number", ""));
        clientProfileFields[7].setText(pp.optString("issued_by", ""));
        clientProfileFields[8].setText(pp.optString("issued_at", ""));
        if (clientAvatarStatus != null) clientAvatarStatus.setText(clientProfile.optString("avatar_path", "").isEmpty() ? "Аватарка ещё не загружена" : "Аватарка загружена");
    }

    static void showClientProfile(final Activity a) {
        ensureClientProfile();
        if (!isClient(a)) return;
        loadClientProfile(a);
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20, a.getResources().getDisplayMetrics().density), px(4, a.getResources().getDisplayMetrics().density), px(20, a.getResources().getDisplayMetrics().density), 0);
        TextView privacy = new TextView(a);
        privacy.setText("Фамилия, имя, отчество, телефон и адрес нужны для оформления аренды. Паспортные данные необязательны и помогают ускорить оформление договора; они не публикуются для арендодателя.");
        privacy.setTextSize(12.5f);
        privacy.setTextColor(Color.GRAY);
        privacy.setLineSpacing(0, 1.15f);
        box.addView(privacy);
        EditText surname = profileEdit(a, box, "Фамилия", clientProfile.optString("surname", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText given = profileEdit(a, box, "Имя *", clientProfile.optString("given_name", activityText(a, "userName")), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText patronymic = profileEdit(a, box, "Отчество", clientProfile.optString("patronymic", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText phone = profileEdit(a, box, "Номер телефона", clientProfile.optString("phone", activityText(a, "userPhone")), InputType.TYPE_CLASS_PHONE);
        EditText address = profileEdit(a, box, "Адрес", clientProfile.optString("address", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText passportSeries = profileEdit(a, box, "Паспорт: серия (необязательно)", "", InputType.TYPE_CLASS_TEXT);
        EditText passportNumber = profileEdit(a, box, "Паспорт: номер (необязательно)", "", InputType.TYPE_CLASS_NUMBER);
        EditText passportIssuedBy = profileEdit(a, box, "Паспорт: кем выдан", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText passportIssuedAt = profileEdit(a, box, "Паспорт: дата выдачи", "", InputType.TYPE_CLASS_DATETIME);
        clientProfileFields = new EditText[] {surname, given, patronymic, phone, address, passportSeries, passportNumber, passportIssuedBy, passportIssuedAt};
        fillClientProfileFields();

        Button av = new Button(a);
        av.setText("Загрузить аватарку"); av.setAllCaps(false); av.setTextSize(14f);
        box.addView(av, new LinearLayout.LayoutParams(-1, -2));
        clientAvatarStatus = new TextView(a);
        clientAvatarStatus.setTextSize(12f); clientAvatarStatus.setTextColor(Color.GRAY);
        box.addView(clientAvatarStatus);
        av.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try {
                Intent i = new Intent("android.intent.action.GET_CONTENT"); i.setType("image/*");
                a.startActivityForResult(Intent.createChooser(i, "Аватарка профиля"), CLIENT_AVATAR_REQUEST);
            } catch (Throwable t) { Toast.makeText(a, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show(); }
        }});

        AlertDialog d = new AlertDialog.Builder(a).setTitle("Профиль клиента").setView(box).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create();
        clientProfileDialog = d;
        d.setOnShowListener(new DialogInterface.OnShowListener() { @Override public void onShow(DialogInterface x) {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                saveClientProfile(a, d);
            }});
        }});
        d.setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface x) {
            clientProfileDialog = null; clientProfileFields = null; clientAvatarStatus = null;
        }});
        d.show();
    }

    static void saveClientProfile(final Activity a, final AlertDialog d) {
        ensureClientProfile();
        if (clientProfileFields == null || clientProfileFields.length < 9) return;
        final String given = clientProfileFields[1].getText().toString().trim();
        if (given.isEmpty()) { clientProfileFields[1].setError("Укажите имя"); return; }
        final JSONObject body = new JSONObject();
        try {
            JSONObject pp = new JSONObject();
            pp.put("series", clientProfileFields[5].getText().toString().trim());
            pp.put("number", clientProfileFields[6].getText().toString().trim());
            pp.put("issued_by", clientProfileFields[7].getText().toString().trim());
            pp.put("issued_at", clientProfileFields[8].getText().toString().trim());
            body.put("p_surname", clientProfileFields[0].getText().toString().trim());
            body.put("p_given_name", given);
            body.put("p_patronymic", clientProfileFields[2].getText().toString().trim());
            body.put("p_phone", clientProfileFields[3].getText().toString().trim());
            body.put("p_city", clientProfile.optString("city", "")); body.put("p_address", clientProfileFields[4].getText().toString().trim());
            body.put("p_passport_data", pp); body.put("p_avatar_path", clientAvatarPath);
        } catch (Throwable e) { return; }
        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        new Thread(new Runnable() { @Override public void run() {
            try {
                String[] r = apiCall(a, "/rest/v1/rpc/save_my_customer_profile", "POST", body.toString(), true);
                if (r == null || !r[0].startsWith("2")) throw new Exception(r == null ? "Нет ответа" : r[1]);
                clientProfile = rpcFirst(r[1]);
                clientProfileUid = clientProfile.optString("user_id", clientProfileUid);
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    set(a, "userName", profileName(clientProfile));
                    set(a, "userPhone", clientProfile.optString("phone", ""));
                    clientCoords = null; clientCoordsLoading = false; synchronized (nativeDistanceCache) { nativeDistanceCache.clear(); }
                    d.dismiss();
                    try { Method m = a.getClass().getDeclaredMethod("renderProfile"); m.setAccessible(true); m.invoke(a); } catch (Throwable ignore) {}
                    Toast.makeText(a, "Профиль клиента сохранён", Toast.LENGTH_SHORT).show();
                }});
            } catch (final Throwable e) {
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    Toast.makeText(a, "Не удалось сохранить профиль: " + brief(e.getMessage()), Toast.LENGTH_LONG).show();
                }});
            }
        }}).start();
    }

    /** 9) Профиль подмешивается в native после штатного renderProfile. */
    public static void onProfile(Activity a) {
        ensureClientProfile();
        try { installClientProfile(a); loadClientProfile(a); } catch (Throwable ignore) {}
    }

    // ── 11–12. Выбранный раздел нижнего меню и окантовка аватарки (10.18–10.19) ──
    // Штатный setCircle() вызывается в showTab() и рисует оранжевую заливку с белым значком.
    // Этот вызов стоит после всех четырёх setCircle: заливка убирается, а выбранный пункт получает
    // прозрачный круг с оранжевой окантовкой и оранжевые текст/значок.
    public static void onTab(Activity a, String tab) {
        String t = tab == null ? "" : tab;
        try {
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
        chatPoll(a, "chat".equals(t));
    }

    /** Чистая проверка выбора пункта — вынесена отдельно, чтобы её можно было проверить без Activity. */
    static boolean navSelected(String current, String item) {
        return current != null && item != null && item.equals(current);
    }

    /** Центральный логотип ведёт в заявку или в раздел добавления инструментов. */
    static boolean isLogoTab(String tab) {
        return "order".equals(tab) || "admhub".equals(tab) || "addtool".equals(tab);
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
                    ViewGroup ph = profileHost(a);
                    if (ph != null) { View pb = ph.findViewWithTag(PROFILE_BUTTON_TAG); if (pb != null) ph.removeView(pb); }
                    clientProfile = new JSONObject(); clientProfileEmail = ""; clientProfileUid = ""; clientAvatarPath = ""; clientCoords = null; clientCoordsLoading = false; activeDetail = null; synchronized (nativeDistanceCache) { nativeDistanceCache.clear(); } synchronized (nativeGeoCache) { nativeGeoCache.clear(); } allNativeTools = null; nativeFilterActive = false; nativeFilterQuery = nativeFilterCat = nativeFilterSub = nativeFilterCity = nativeFilterFrom = nativeFilterTo = nativeFilterConds = ""; nativeFilterDelivery = false;
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

    // ── 17. Безопасные вызовы Supabase и аватар клиента ──
    static final String SB_URL = "https://wdxdeatphizclskfmfxi.supabase.co";
    static final String SB_KEY = "sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE";

    static String[] apiCall(Activity a, String path, String method, String body, boolean bearer) throws Exception {
        Class<?> c = Class.forName("ru.prokatnvsk.app.MainActivity$Api");
        Method m = c.getDeclaredMethod("call", String.class, String.class, String.class, boolean.class, String.class);
        m.setAccessible(true);
        Object token = get(a, "accessToken");
        Object out = m.invoke(null, path.startsWith("http") ? path : SB_URL + path, method, body, bearer, token instanceof String ? token : null);
        return (String[]) out;
    }

    static JSONObject rpcFirst(String body) throws Exception {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("[")) {
            JSONArray rows = new JSONArray(text);
            return rows.length() > 0 ? rows.getJSONObject(0) : new JSONObject();
        }
        return text.startsWith("{") ? new JSONObject(text) : new JSONObject();
    }

    static byte[] readUri(Activity a, android.net.Uri uri) throws Exception {
        InputStream in = a.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("файл не читается");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n; if (total > 8 * 1024 * 1024) throw new Exception("файл больше 8 МБ");
            out.write(buf, 0, n);
        }
        in.close(); return out.toByteArray();
    }

    static String authUid(Activity a) {
        if (clientProfileUid != null && !clientProfileUid.isEmpty()) return clientProfileUid;
        try {
            String[] r = apiCall(a, "/auth/v1/user", "GET", null, true);
            if (r != null && r[0].startsWith("2")) {
                clientProfileUid = new JSONObject(r[1]).optString("id", "");
            }
        } catch (Throwable ignore) {}
        return clientProfileUid;
    }

    static String uploadClientAvatar(Activity a, byte[] data) throws Exception {
        String uid = authUid(a);
        if (uid == null || uid.isEmpty()) throw new Exception("не найден пользователь");
        String path = uid + "/avatar.jpg";
        HttpURLConnection c = (HttpURLConnection) new URL(SB_URL + "/storage/v1/object/customer-avatars/" + path).openConnection();
        c.setRequestMethod("PUT"); c.setDoOutput(true); c.setConnectTimeout(12000); c.setReadTimeout(15000);
        c.setRequestProperty("apikey", SB_KEY);
        c.setRequestProperty("Authorization", "Bearer " + String.valueOf(get(a, "accessToken")));
        c.setRequestProperty("Content-Type", "image/jpeg"); c.setRequestProperty("x-upsert", "true");
        OutputStream out = c.getOutputStream(); out.write(data); out.flush(); out.close();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return path;
    }

    public static void onActivityResult(final Activity a, final int request, final int result, final Intent data) {
        if (request != CLIENT_AVATAR_REQUEST || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (!isClient(a)) return;
        if (clientAvatarStatus != null) clientAvatarStatus.setText("Загружаем аватарку…");
        new Thread(new Runnable() { @Override public void run() {
            try {
                final String path = uploadClientAvatar(a, readUri(a, data.getData()));
                clientAvatarPath = path; clientProfileUid = authUid(a);
                try { clientProfile.put("avatar_path", path); } catch (Throwable ignore) {}
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    if (clientAvatarStatus != null) clientAvatarStatus.setText("Аватарка загружена");
                }});
            } catch (final Throwable e) {
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    if (clientAvatarStatus != null) clientAvatarStatus.setText("Не удалось загрузить аватарку");
                    Toast.makeText(a, "Аватарка: " + brief(e.getMessage()), Toast.LENGTH_LONG).show();
                }});
            }
        }}).start();
    }

    // ── 18. Компактный фильтр native на главной ──
    static EditText filterField(Activity a, LinearLayout box, String hint, String value) {
        EditText e = new EditText(a); e.setHint(hint); e.setText(value == null ? "" : value); e.setSingleLine(true); e.setTextSize(14f);
        e.setPadding(px(10, a.getResources().getDisplayMetrics().density), px(8, a.getResources().getDisplayMetrics().density), px(10, a.getResources().getDisplayMetrics().density), px(5, a.getResources().getDisplayMetrics().density));
        box.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }

    static void installHomeFilter(final Activity a) {
        try {
            Object raw = get(a, "searchBox");
            if (!(raw instanceof EditText)) return;
            ViewParent parent = ((View) raw).getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup p = (ViewGroup) parent;
            if (p.findViewWithTag(FILTER_BUTTON_TAG) != null) return;
            Button b = new Button(a); b.setTag(FILTER_BUTTON_TAG); b.setText("Фильтр каталога"); b.setAllCaps(false); b.setTextSize(13f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(40, a.getResources().getDisplayMetrics().density));
            lp.setMargins(0, px(4, a.getResources().getDisplayMetrics().density), 0, px(4, a.getResources().getDisplayMetrics().density)); b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showNativeFilter(a); }});
            p.addView(b, Math.min(p.getChildCount(), p.indexOfChild((View) raw) + 1));
        } catch (Throwable ignore) {}
    }

    static ArrayList sourceTools(Activity a) {
        if (allNativeTools == null) {
            Object v = get(a, "tools");
            if (v instanceof ArrayList) allNativeTools = new ArrayList((ArrayList) v);
        }
        return allNativeTools == null ? new ArrayList() : allNativeTools;
    }

    static boolean textMatch(String value, String wanted) {
        return wanted == null || wanted.trim().isEmpty() || (value != null && value.toLowerCase(Locale.ROOT).contains(wanted.trim().toLowerCase(Locale.ROOT)));
    }

    static ArrayList filteredTools(Activity a, ArrayList source) {
        ArrayList out = new ArrayList();
        String q = nativeFilterQuery.trim().toLowerCase(Locale.ROOT);
        for (Object o : source) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if ("rented".equals(j.optString("t_status", ""))) continue;
            String hay = (j.optString("name", "") + " " + j.optString("descr", "") + " " + j.optString("terms", "")).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            if (!textMatch(j.optString("cat", ""), nativeFilterCat)) continue;
            if (!textMatch(j.optString("sub", ""), nativeFilterSub)) continue;
            String city = j.optString("pickup_city", j.optString("city", ""));
            if (!textMatch(city, nativeFilterCity)) continue;
            if (!nativeFilterConds.trim().isEmpty()) {
                String condition = j.optString("cond", j.optString("condition", "")).toLowerCase(Locale.ROOT);
                boolean conditionOk = false;
                String[] wanted = nativeFilterConds.toLowerCase(Locale.ROOT).split(",");
                for (String w : wanted) if (!w.trim().isEmpty() && condition.contains(w.trim())) conditionOk = true;
                if (!conditionOk) continue;
            }
            double price = j.optDouble("price", 0);
            try { if (!nativeFilterFrom.trim().isEmpty() && price < Double.parseDouble(nativeFilterFrom.trim())) continue; } catch (Throwable ignore) {}
            try { if (!nativeFilterTo.trim().isEmpty() && price > Double.parseDouble(nativeFilterTo.trim())) continue; } catch (Throwable ignore) {}
            if (nativeFilterDelivery && !j.optBoolean("delivery", false)) continue;
            out.add(j);
        }
        return out;
    }

    static void applyNativeFilter(Activity a) {
        ArrayList source = sourceTools(a);
        nativeFilterActive = !(nativeFilterQuery + nativeFilterCat + nativeFilterSub + nativeFilterCity + nativeFilterFrom + nativeFilterTo + nativeFilterConds).trim().isEmpty() || nativeFilterDelivery;
        ArrayList result = nativeFilterActive ? filteredTools(a, source) : source;
        set(a, "tools", result);
        try { Method m = a.getClass().getDeclaredMethod("renderFeed"); m.setAccessible(true); m.invoke(a); } catch (Throwable ignore) {}
    }

    static void showNativeFilter(final Activity a) {
        final LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(18, a.getResources().getDisplayMetrics().density), 0, px(18, a.getResources().getDisplayMetrics().density), 0);
        final EditText q = filterField(a, box, "Поиск по названию", nativeFilterQuery);
        final EditText cat = filterField(a, box, "Категория", nativeFilterCat);
        final EditText sub = filterField(a, box, "Подкатегория", nativeFilterSub);
        final EditText city = filterField(a, box, "Город выдачи", nativeFilterCity);
        final EditText conds = filterField(a, box, "Состояние: new, good, work", nativeFilterConds);
        final EditText from = filterField(a, box, "Цена от, ₽", nativeFilterFrom); from.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText to = filterField(a, box, "Цена до, ₽", nativeFilterTo); to.setInputType(InputType.TYPE_CLASS_NUMBER);
        final CheckBox delivery = new CheckBox(a); delivery.setText("Только объявления с доставкой"); delivery.setChecked(nativeFilterDelivery); box.addView(delivery);
        TextView hint = new TextView(a); hint.setText("Можно оставить поля пустыми. Категории и подкатегории принимают ключ или часть названия."); hint.setTextSize(12f); hint.setTextColor(Color.GRAY); box.addView(hint);
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("Фильтр каталога").setView(box).setNegativeButton("Отмена", null).setNeutralButton("Сбросить", null).setPositiveButton("Показать", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() { @Override public void onShow(DialogInterface x) {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                nativeFilterQuery = nativeFilterCat = nativeFilterSub = nativeFilterCity = nativeFilterFrom = nativeFilterTo = nativeFilterConds = ""; nativeFilterDelivery = false; applyNativeFilter(a); d.dismiss();
            }});
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                nativeFilterQuery=q.getText().toString(); nativeFilterCat=cat.getText().toString(); nativeFilterSub=sub.getText().toString(); nativeFilterCity=city.getText().toString(); nativeFilterConds=conds.getText().toString(); nativeFilterFrom=from.getText().toString(); nativeFilterTo=to.getText().toString(); nativeFilterDelivery=delivery.isChecked(); applyNativeFilter(a); d.dismiss();
            }});
        }});
        d.show();
    }

    static void onCatalogLoaded(Activity a) {
        Object v = get(a, "tools");
        if (!(v instanceof ArrayList)) return;
        allNativeTools = new ArrayList((ArrayList) v);
        synchronized (nativeDistanceCache) { nativeDistanceCache.clear(); }
        if (nativeFilterActive) set(a, "tools", filteredTools(a, allNativeTools));
    }

    // ── 19. Расстояние на карточках native ──
    static double[] jsonCoords(JSONObject j) {
        try {
            if (!j.has("pickup_lat") || !j.has("pickup_lng") || j.isNull("pickup_lat") || j.isNull("pickup_lng")) return null;
            double lat = j.optDouble("pickup_lat", Double.NaN), lon = j.optDouble("pickup_lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            return new double[] {lon, lat};
        } catch (Throwable e) { return null; }
    }

    static double[] geocodeNative(Activity a, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String key = text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        synchronized (nativeGeoCache) {
            if (nativeGeoCache.containsKey(key)) return nativeGeoCache.get(key);
        }
        try {
            Method m = a.getClass().getDeclaredMethod("geocode", String.class); m.setAccessible(true);
            Object v = m.invoke(a, text);
            double[] result = v instanceof double[] ? (double[]) v : null;
            synchronized (nativeGeoCache) { nativeGeoCache.put(key, result); }
            return result;
        } catch (Throwable ignore) {
            synchronized (nativeGeoCache) { nativeGeoCache.put(key, null); }
            return null;
        }
    }

    static double haversineKm(double[] a, double[] b) {
        double dLat = Math.toRadians(b[1] - a[1]), dLon = Math.toRadians(b[0] - a[0]);
        double q = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(a[1])) * Math.cos(Math.toRadians(b[1])) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return Math.max(1, Math.ceil(6371 * 2 * Math.atan2(Math.sqrt(q), Math.sqrt(1 - q))));
    }

    /** Используем уже встроенный в MainActivity маршрутный расчёт, а не
     * дублируем его API. Если сеть/метод недоступны, карточка всё равно
     * получает понятную прямолинейную оценку. */
    static double routeDistanceKm(Activity a, double[] from, double[] to) {
        try {
            Method m = a.getClass().getDeclaredMethod("routeKm", double[].class, double[].class);
            m.setAccessible(true);
            Object value = m.invoke(a, from, to);
            if (value instanceof Number) {
                double km = ((Number) value).doubleValue();
                if (!Double.isNaN(km) && !Double.isInfinite(km) && km > 0) return Math.ceil(km);
            }
        } catch (Throwable ignore) {}
        return haversineKm(from, to);
    }

    static String pickupText(Activity a, JSONObject j) {
        String s = j.optString("pickup_address", "").trim();
        if (s.isEmpty()) {
            String terms = j.optString("terms", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Адрес выдачи:\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(terms);
            if (m.find()) s = m.group(1).trim();
        }
        if (s.isEmpty()) s = j.optString("pickup_city", "").trim();
        if (s.isEmpty()) {
            Object addresses = get(a, "admAddr");
            if (addresses instanceof Map) s = String.valueOf(((Map) addresses).get(j.optString("owner_email", "").toLowerCase(Locale.ROOT)));
        }
        return "null".equals(s) ? "" : s;
    }

    static ArrayList visibleNativeTools(Activity a) {
        ArrayList list = new ArrayList(); Object v = get(a, "tools");
        if (!(v instanceof ArrayList)) return list;
        String catMode = String.valueOf(get(a, "catMode"));
        String cat = String.valueOf(get(a, "curCat")); String sub = String.valueOf(get(a, "curSub"));
        String q = String.valueOf(get(a, "query")); if (q == null || "null".equals(q)) q = ""; q = q.toLowerCase(Locale.ROOT);
        for (Object o : (ArrayList) v) if (o instanceof JSONObject) {
            JSONObject j = (JSONObject)o;
            if ("rented".equals(j.optString("t_status", ""))) continue;
            if ("cat".equals(catMode) && !cat.isEmpty() && !inCatNative(a, j, cat)) continue;
            if ("cat".equals(catMode) && !sub.isEmpty() && !sub.equals(j.optString("sub", ""))) continue;
            String hay=(j.optString("name","")+" "+j.optString("descr","")).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            list.add(j);
        }
        return list;
    }

    static boolean inCatNative(Activity a, JSONObject j, String cat) {
        if (cat.equals(j.optString("cat", ""))) return true;
        return false;
    }

    static void addDistanceLabels(final Activity a) {
        try {
            Object raw = get(a, "feedGrid"); if (!(raw instanceof ViewGroup)) return;
            ViewGroup grid = (ViewGroup) raw; ArrayList tools = visibleNativeTools(a); int n = 0;
            for (int r = 0; r < grid.getChildCount() && n < tools.size(); r++) {
                View row = grid.getChildAt(r); if (!(row instanceof ViewGroup)) continue;
                ViewGroup rg = (ViewGroup) row;
                for (int c = 0; c < rg.getChildCount() && n < tools.size(); c++) {
                    View card = rg.getChildAt(c); JSONObject j = (JSONObject) tools.get(n++);
                    if (!(card instanceof ViewGroup)) continue;
                    ViewGroup cg = (ViewGroup) card;
                    String id = j.optString("id", j.optString("name", ""));
                    TextView label = (TextView) cg.findViewWithTag(DISTANCE_TAG + id);
                    if (label == null) {
                        label = new TextView(a); label.setTag(DISTANCE_TAG + id); label.setTextSize(11f); label.setTextColor(Color.GRAY); label.setPadding(px(10, a.getResources().getDisplayMetrics().density), px(4, a.getResources().getDisplayMetrics().density), px(4, a.getResources().getDisplayMetrics().density), px(4, a.getResources().getDisplayMetrics().density));
                        cg.addView(label, new LinearLayout.LayoutParams(-1, -2));
                    }
                    if (clientCoords == null) label.setText(isClient(a) ? "Расстояние: разрешите геолокацию или укажите адрес в профиле" : "Расстояние: войдите и укажите адрес в профиле");
                    else if (jsonCoords(j) == null && pickupText(a, j).isEmpty()) label.setText("Расстояние: пункт выдачи уточняется");
                    else label.setText("Расстояние: рассчитываем…");
                }
            }
            if (clientCoords == null || !clientCoordsLoading) computeNativeDistances(a, tools);
        } catch (Throwable ignore) {}
    }

    static void computeNativeDistances(final Activity a, final ArrayList tools) {
        if (clientCoords == null || tools == null || tools.isEmpty()) return;
        new Thread(new Runnable() { @Override public void run() {
            final HashMap<String, String> result = new HashMap<String, String>();
            for (Object o : tools) if (o instanceof JSONObject) {
                JSONObject j = (JSONObject)o; String id=j.optString("id", j.optString("name", ""));
                synchronized (nativeDistanceCache) {
                    if (nativeDistanceCache.containsKey(id)) { result.put(id, nativeDistanceCache.get(id)); continue; }
                }
                double[] dest=jsonCoords(j); if (dest == null) dest=geocodeNative(a, pickupText(a,j));
                String text = dest == null ? "Расстояние: пункт выдачи уточняется" : "Расстояние до пункта: " + ((long)routeDistanceKm(a, clientCoords, dest)) + " км";
                synchronized (nativeDistanceCache) { nativeDistanceCache.put(id, text); }
                result.put(id, text);
            }
            a.runOnUiThread(new Runnable() { @Override public void run() {
                Object raw=get(a,"feedGrid"); if (!(raw instanceof ViewGroup)) return;
                ViewGroup grid=(ViewGroup)raw;
                for (int r=0;r<grid.getChildCount();r++) { View row=grid.getChildAt(r); if (!(row instanceof ViewGroup)) continue; ViewGroup rg=(ViewGroup)row;
                    for(int c=0;c<rg.getChildCount();c++){View card=rg.getChildAt(c);if(!(card instanceof ViewGroup))continue;ViewGroup cg=(ViewGroup)card;
                        for(String id:result.keySet()){View v=cg.findViewWithTag(DISTANCE_TAG+id);if(v instanceof TextView)((TextView)v).setText(result.get(id));}
                    }
                }
            }});
        }}).start();
    }

    static boolean hasLocationPermission(Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static double[] lastPhoneLocation(Activity a) {
        try {
            if (!hasLocationPermission(a)) {
                if (!locationAsked) { locationAsked=true; a.requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, CLIENT_LOCATION_REQUEST); }
                return null;
            }
            LocationManager lm=(LocationManager)a.getSystemService(Context.LOCATION_SERVICE); Location best=null;
            Location g=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER), n=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if(g!=null)best=g;if(n!=null&&(best==null||n.getTime()>best.getTime()))best=n;
            return best==null?null:new double[]{best.getLongitude(),best.getLatitude()};
        } catch(Throwable ignore){return null;}
    }

    static void loadNativeClientCoords(final Activity a) {
        ensureClientProfile();
        if (!isClient(a) || clientCoordsLoading || clientCoords != null) return;
        clientCoordsLoading=true;
        new Thread(new Runnable(){@Override public void run(){
            double[] point=lastPhoneLocation(a);
            if(point==null){
                try{
                    String[] r=apiCall(a,"/rest/v1/rpc/get_my_customer_profile","POST","{}",true);
                    if(r!=null&&r[0].startsWith("2")){JSONObject p=rpcFirst(r[1]);clientProfile=p;clientProfileUid=p.optString("user_id",clientProfileUid);point=geocodeNative(a,p.optString("address",""));}
                }catch(Throwable ignore){}
            }
            final double[] found=point;
            a.runOnUiThread(new Runnable(){@Override public void run(){clientCoords=found;clientCoordsLoading=false;addDistanceLabels(a);updateNativeDelivery(a);}});
        }}).start();
    }

    public static void onLocationPermission(Activity a, int[] grants) {
        locationAsked=true; clientCoordsLoading=false; loadNativeClientCoords(a);
    }

    /** Вызывается после каждого штатного renderFeed: карточки получают
     * расстояние, а при первом показе запускается разрешённая геолокация. */
    public static void onFeed(Activity a) {
        try {
            installHomeFilter(a);
            if (isClient(a) && !hasLocationPermission(a) && !locationAsked) {
                locationAsked = true;
                a.requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, CLIENT_LOCATION_REQUEST);
            }
            addDistanceLabels(a); loadNativeClientCoords(a);
        } catch(Throwable ignore) {}
    }

    // ── 20. Автоматическая оценка доставки в открытой карточке ──
    public static void onDetail(Activity a, JSONObject item, boolean service) {
        activeDetail = item; activeDetailIsService = service; deliveryEstimateKey = ""; deliveryEstimateLoading = false;
        updateNativeDelivery(a);
        loadNativeClientCoords(a);
    }

    static void updateNativeDelivery(final Activity a) {
        if (activeDetail == null || activeDetailIsService || !activeDetail.optBoolean("delivery", false)) return;
        final Object raw = get(a, "detDeliv"); if (!(raw instanceof TextView)) return;
        final TextView view = (TextView) raw;
        final String id = activeDetail.optString("id", activeDetail.optString("name", ""));
        if (clientCoords == null) {
            view.setText("Есть доставка: укажите адрес в профиле или разрешите геолокацию — стоимость рассчитается автоматически");
            return;
        }
        if (deliveryEstimateLoading && id.equals(deliveryEstimateKey)) return;
        deliveryEstimateLoading = true; deliveryEstimateKey = id;
        final JSONObject item = activeDetail; final double[] from = clientCoords;
        new Thread(new Runnable() { @Override public void run() {
            double[] to = jsonCoords(item); if (to == null) to = geocodeNative(a, pickupText(a, item));
            final String text;
            if (to == null) text = "Есть доставка: пункт выдачи не удалось определить — стоимость подтвердит арендодатель";
            else {
                long km = (long) routeDistanceKm(a, from, to); long rate = Math.round(item.optDouble("delivery_price", 40)); if (rate <= 0) rate = 40;
                text = "Доставка до вашего адреса: " + km + " км × " + rate + " ₽/км ≈ " + (km * rate) + " ₽";
            }
            a.runOnUiThread(new Runnable() { @Override public void run() {
                deliveryEstimateLoading = false;
                if (activeDetail == item && activeDetail.optString("id", activeDetail.optString("name", "")).equals(id)) view.setText(text);
            }});
        }}).start();
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

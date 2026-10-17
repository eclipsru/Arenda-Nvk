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
// 16) (10.30–10.39) профиль обычного клиента: штатная строка «Клиент» в кабинете открывает настройки
//     (ФИО, телефон, адрес, необязательные паспортные данные, фото) — тогда формой-диалогом AlertDialog.
//     (10.40) настройки — отдельный полноэкранный экран ProfileScreen: шапка «←» и заголовок, прокрутка,
//     закреплённая кнопка «Сохранить» (доступна только после загрузки записи с сервера), выбор даты выдачи,
//     подтверждение при выходе с несохранёнными изменениями; фото уменьшается до 640 px и возвращается
//     через вспомогательный Fragment AvatarPicker. Новых точек вставки в MainActivity нет (только onProfile).
//     (10.41) фото клиента видно и в строке «Клиент» (штатный кружок avatarImg), и в кружке «Кабинета»
//     нижнего меню: картинка из приватного bucket скачивается по токену, при выходе убирается.
//     (10.42) вместо «Клиент» в строке кабинета — имя из поля «Имя» профиля; ФИО и телефон сохраняются
//     в настройках телефона (ключи name/phone штатного saveSession) и переживают перезапуск.
//   16.5 (10.43) логотип в нижнем меню у клиента открывает раздел «Избранное / Мои инструменты» вместо
//     формы заявки: карточки отмеченных сердечком инструментов и инструментов из прошлых заявок
//     (штатный feedCard). У админа и гостя логотип прежний. Скрытие — из onTab, новых точек вставки нет.
package ru.prokatnvsk.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public final class IvaClientPatch {
    static final String NB = "\u00a0";       // неразрывный пробел
    static final String RUB = " \u20bd";     // " ₽"
    static final String TAG = "iva_round_trip";

    static volatile boolean round = false;   // выбрано «туда и обратно»
    static volatile String base = null;      // последний расчёт доставки «в одну сторону»
    static volatile String shown = null;     // что мы вернули приложению (с учётом ×2)
    static boolean limitedShown = false;

    private IvaClientPatch() {}

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
    static final String FILTER_BUTTON_TAG = "iva_catalog_filter_button";
    static final String DISTANCE_TAG = "iva_distance_";
    static final int CLIENT_AVATAR_REQUEST = 6403;
    static final int CLIENT_LOCATION_REQUEST = 6404;
    static final long CHAT_REFRESH_MS = 5000L;

    // Клиентский профиль не смешивается с admins. Состояние экрана живёт только,
    // пока экран открыт; кэш профиля сбрасывается при смене аккаунта или выходе.
    static JSONObject clientProfile;
    static String clientProfileEmail = "";
    static String clientProfileUid = "";
    static volatile boolean clientProfileLoaded = false;    // кэш clientProfile относится к clientProfileEmail
    static volatile boolean clientProfileLoading = false;
    static ProfileScreen clientScreen;                      // открытый экран настроек (null — закрыт)
    static final String AVATAR_PICKER_TAG = "iva_client_avatar_picker";
    static View clientProfileHead;
    static String clientAvatarPath = "";
    // 10.41: фото клиента в строке «Клиент» (штатный кружок avatarImg) и в нижнем меню.
    // Картинка из приватного хранилища скачивается один раз и живёт до выхода из аккаунта.
    static volatile Bitmap clientAvatarBitmap;
    static volatile String clientAvatarBitmapKey = "";            // почта|путь, к которым относится картинка
    static volatile String clientAvatarFailedKey = "";            // после ошибки не качаем при каждом переключении вкладки
    static volatile boolean clientAvatarFetching = false;
    static boolean clientHeadPhotoShown = false;           // штатный avatarImg показан нами (вернуть буквы при выходе)

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
        // Запуск не зависит от нового клиентского профиля: профиль, фильтр,
        // GPS и доставка подключаются лениво из соответствующих экранов.
        // Если конкретная прошивка/вариант 10.14 не содержит ожидаемого
        // поля или метода, исходный каталог всё равно должен открыться.
        try { reloadProfile(a); } catch (Throwable ignore) {}
    }

    /** 8) Конец saveSession (вход и восстановление сессии): профиль админа → аватарка в меню. */
    public static void onSession(Activity a) {
        try { reloadProfile(a); } catch (Throwable ignore) {}
    }

    /** 9) lambda$loadAdminProfile$201, сразу после чтения поля avatar. Пустой адрес — аватарки нет. */
    public static void onAvatar(final Activity a, final String url) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() { applyAvatar(a, url); }
        });
    }

    // ── 16. Профиль обычного клиента (отдельно от admins) ──
    // 10.40: настройки открываются отдельным полноэкранным экраном (своя шапка «←»,
    // прокрутка, закреплённая кнопка «Сохранить», системная «Назад» закрывает экран),
    // а не формой-диалогом AlertDialog поверх кабинета, как в 10.33–10.39.
    // Новых точек вставки в MainActivity нет: экран — окно Dialog с полноэкранной
    // темой, которое открывается из уже подключённого hook onProfile (строка «Клиент»).
    // Выбор фото возвращается во вспомогательный Fragment (AvatarPicker), поэтому
    // hook onActivityResult в MainActivity по-прежнему не нужен (он отключён с 10.39).
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

    /** Клиент не получает отдельную светлую карточку в кабинете: штатная
     * строка «Клиент» остаётся единственной точкой входа, а данные и настройки
     * открываются по нажатию на неё. */
    static void installClientProfile(final Activity a) {
        if (!isClient(a)) return;
        try {
            installClientProfileHead(a);
            loadClientProfile(a, false);
        } catch (Throwable ignore) {}
    }

    /** Штатная тёмная строка «Клиент» в верхней части кабинета становится
     * входом в экран настроек профиля. Кнопка «Выйти» внутри строки
     * остаётся штатной: её обработчик у дочерней кнопки имеет приоритет. */
    static void installClientProfileHead(final Activity a) {
        try {
            Object raw = get(a, "profileHead");
            if (!(raw instanceof View)) {
                int rid = id(a, "profileHead");
                if (rid != 0) raw = a.findViewById(rid);
            }
            if (!(raw instanceof View)) return;
            View head = (View) raw;
            ViewGroup host = profileHost(a);
            View current = head;
            int depth = 0;
            // profileHead may be the text inside the dark row, not the row itself.
            // Put the same action on the row and its immediate wrapper(s), but stop
            // before pageProfile so the rest of the cabinet is not clickable.
            while (current != null && current != host && depth < 3) {
                final View clickable = current;
                clickable.setClickable(true);
                clickable.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openClientProfileScreen(a); }
                });
                clientProfileHead = clickable;
                ViewParent p = current.getParent();
                current = p instanceof View ? (View) p : null;
                depth++;
            }
        } catch (Throwable ignore) {}
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

    /** Профиль клиента из приватной записи customer_profiles (RPC берёт пользователя из JWT).
     * Кэш — до смены аккаунта; force — перечитать с сервера. Итог сообщается открытому экрану. */
    static void loadClientProfile(final Activity a, boolean force) {
        ensureClientProfile();
        if (!isClient(a)) return;
        final String email = String.valueOf(get(a, "userEmail"));
        if (!email.equalsIgnoreCase(clientProfileEmail)) {
            // Другой аккаунт: чужие данные не показываем и не сохраняем поверх.
            clientProfile = new JSONObject();
            clientProfileLoaded = false;
            clientProfileUid = "";
            clientAvatarPath = "";
            clientProfileEmail = email;
        }
        if (clientProfileLoading || (clientProfileLoaded && !force)) return;
        clientProfileLoading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    String[] r = apiCall(a, "/rest/v1/rpc/get_my_customer_profile", "POST", "{}", true);
                    if (r == null) throw new Exception("нет ответа сервера");
                    if (!r[0].startsWith("2")) throw new Exception(serverMessage(r[0], r[1]));
                    JSONObject p = rpcFirst(r[1]);
                    if (email.equalsIgnoreCase(clientProfileEmail)) {
                        clientProfile = p;
                        clientProfileUid = p.optString("user_id", clientProfileUid);
                        clientAvatarPath = p.optString("avatar_path", "");
                        clientProfileLoaded = true;
                    } else {
                        throw new Exception("аккаунт сменился, откройте профиль ещё раз");
                    }
                } catch (Throwable e) {
                    error = brief(e.getMessage());
                    if (error.isEmpty()) error = "нет связи с сервером";
                } finally {
                    clientProfileLoading = false;
                }
                final String err = error;
                if (err == null) clientAvatarFailedKey = "";   // свежая запись — фото можно попробовать снова
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    ProfileScreen s = clientScreen;
                    if (s != null) s.onProfileLoaded(err);
                    if (err == null) {
                        try { showClientName(a); } catch (Throwable ignore) {}
                        try { showClientAvatar(a); } catch (Throwable ignore) {}
                    }
                }});
            }
        }).start();
    }

    /** Открыть экран настроек профиля клиента (повторное нажатие не плодит экраны). */
    static void openClientProfileScreen(final Activity a) {
        try {
            ensureClientProfile();
            if (!isClient(a) || a.isFinishing()) return;
            ProfileScreen open = clientScreen;
            // Экран, оставшийся от пересозданной Activity, не мешает открыть новый.
            if (open != null && open.a == a && open.isShowing()) return;
            ProfileScreen s = new ProfileScreen(a);
            clientScreen = s;
            // Сначала — сверка аккаунта (кэш другого клиента сбрасывается) и загрузка при необходимости.
            loadClientProfile(a, false);
            s.show();
            if (clientProfileLoaded && !clientProfileLoading) s.onProfileLoaded(null);
        } catch (Throwable t) {
            clientScreen = null;
            try { Toast.makeText(a, "Не удалось открыть настройки профиля", Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        }
    }

    // ── 16.1. Чистая логика экрана (проверяется IvaPatchTest без телефона) ──

    /** Текст ошибки сервера: поле message из ответа PostgREST; отдельно — «функции нет» (не применена миграция). */
    static String serverMessage(String code, String body) {
        String c = code == null ? "" : code.trim();
        String b = body == null ? "" : body;
        if (b.contains("PGRST202") || b.contains("Could not find the function"))
            return "на сервере ещё не включены настройки профиля клиента (нужна миграция базы)";
        String msg = "";
        try {
            msg = new JSONObject(b).optString("message", "");
        } catch (Throwable ignore) {
            // в JVM-тесте org.json — заглушка; разбираем простое поле вручную
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(b);
            if (m.find()) msg = m.group(1).replace("\\\"", "\"");
        }
        msg = brief(msg.isEmpty() ? b : msg);
        if (msg.isEmpty()) return "ошибка сервера" + (c.isEmpty() ? "" : " (HTTP " + c + ")");
        return msg;
    }

    /** «2015-03-07» → {2015, 3, 7}; «07.03.2015» (так вводили в 10.34–10.39) — тоже. Иначе null. */
    static int[] dateParts(String v) {
        String s = v == null ? "" : v.trim();
        java.util.regex.Matcher iso = java.util.regex.Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matcher(s);
        java.util.regex.Matcher ru = java.util.regex.Pattern.compile("^(\\d{1,2})[./](\\d{1,2})[./](\\d{4})$").matcher(s);
        int y, m, d;
        if (iso.find()) { y = Integer.parseInt(iso.group(1)); m = Integer.parseInt(iso.group(2)); d = Integer.parseInt(iso.group(3)); }
        else if (ru.find()) { y = Integer.parseInt(ru.group(3)); m = Integer.parseInt(ru.group(2)); d = Integer.parseInt(ru.group(1)); }
        else return null;
        if (y < 1900 || y > 2100 || m < 1 || m > 12 || d < 1 || d > 31) return null;
        return new int[] {y, m, d};
    }

    /** Дата для базы — в том же виде, что и у поля type=date на сайте: ГГГГ-ММ-ДД. */
    static String isoDate(int y, int month1, int d) {
        return String.format(Locale.US, "%04d-%02d-%02d", y, month1, d);
    }

    /** Дата для экрана: ДД.ММ.ГГГГ; нераспознанный старый текст показываем как есть. */
    static String dateForDisplay(String v) {
        int[] p = dateParts(v);
        if (p == null) return v == null ? "" : v.trim();
        return String.format(Locale.US, "%02d.%02d.%04d", p[2], p[1], p[0]);
    }

    /** Подпись кнопки внизу экрана. */
    static String saveButtonText(boolean loaded, boolean saving, boolean uploading) {
        if (saving) return "Сохраняем…";
        if (uploading) return "Загружаем фото…";
        if (!loaded) return "Загружаем профиль…";
        return "Сохранить";
    }

    /** Во сколько раз уменьшить фото при чтении, чтобы большая сторона осталась ≥ max (степень двойки). */
    static int avatarSample(int w, int h, int max) {
        int side = Math.max(w, h), sample = 1;
        if (max <= 0) return 1;
        while (side / (sample * 2) >= max) sample *= 2;
        return sample;
    }

    /** Буквы в кружке, пока фото нет. */
    static String initialsOf(String name) {
        String[] parts = (name == null ? "" : name.trim()).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.appendCodePoint(Character.toUpperCase(p.codePointAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    // ── 16.2. Экран настроек профиля клиента ──
    static final class ProfileScreen {
        // Индексы значений формы (порядок важен для values()/baseline).
        static final int SURNAME = 0, GIVEN = 1, PATRONYMIC = 2, PHONE = 3, ADDRESS = 4,
            SERIES = 5, NUMBER = 6, ISSUED_BY = 7, ISSUED_AT = 8, COUNT = 9;

        final Activity a;
        final float d;
        final int cBg, cCard, cField, cFieldLine, cGray, cGray2, cLine, cOrange, cRed, cText;
        Dialog dialog;
        LinearLayout root;
        ScrollView scroll;
        final EditText[] edits = new EditText[COUNT];   // ISSUED_AT — не EditText, а выбор даты
        TextView issuedAtView;
        String issuedAt = "";
        String[] baseline = new String[COUNT];
        TextView loadText, retryText, errorText, avatarStatus, avatarInitials;
        ImageView avatarImage;
        Button saveBtn, avatarBtn;
        boolean loaded = false, saving = false, uploading = false, avatarChanged = false;
        int avatarVersion = 0;

        ProfileScreen(Activity a) {
            this.a = a;
            this.d = a.getResources().getDisplayMetrics().density;
            // Цвета — из ресурсов самого приложения (тот же тёмный стиль, что в кабинете).
            cBg = navColor(a, "bg", 0xff101217);
            cCard = navColor(a, "card", 0xff1a1d24);
            cField = navColor(a, "field", 0xff171a21);
            cFieldLine = navColor(a, "fieldline", 0xff3a4050);
            cGray = navColor(a, "gray", 0xff9aa1b0);
            cGray2 = navColor(a, "gray2", 0xff6b7080);
            cLine = navColor(a, "line", 0xff262b35);
            cOrange = navColor(a, "orange", 0xffff8f1f);
            cRed = navColor(a, "red", 0xffff8a80);
            cText = navColor(a, "text", 0xfff2f4f8);
        }

        int dp(float v) { return px(v, d); }

        boolean isShowing() {
            try { return dialog != null && dialog.isShowing(); } catch (Throwable ignore) { return false; }
        }

        void show() {
            build();
            dialog = new Dialog(a, android.R.style.Theme_Material_NoActionBar) {
                @Override public void onBackPressed() { requestClose(); }
            };
            dialog.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
            dialog.setCancelable(true);
            android.view.Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(-1, -1);
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(cBg));
                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
                try {
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    w.setStatusBarColor(cBg);
                    w.setNavigationBarColor(cBg);
                } catch (Throwable ignore) {}
            }
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface x) {
                    if (clientScreen == ProfileScreen.this) clientScreen = null;
                }
            });
            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override public void onShow(DialogInterface x) {
                    try {
                        // Экран «въезжает» справа, как переход на новую страницу.
                        root.setAlpha(0f);
                        root.setTranslationX(dp(36));
                        root.animate().alpha(1f).translationX(0f).setDuration(180).start();
                    } catch (Throwable ignore) {}
                }
            });
            dialog.show();
        }

        void build() {
            root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(cBg);
            root.setFocusableInTouchMode(true);   // фокус не прыгает в первое поле при открытии

            // Шапка: «←» + заголовок + почта аккаунта.
            LinearLayout bar = new LinearLayout(a);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(4), 0, dp(16), 0);
            TextView back = new TextView(a);
            back.setText("\u2190");
            back.setTextSize(24f);
            back.setTextColor(cText);
            back.setGravity(Gravity.CENTER);
            back.setContentDescription("Назад");
            back.setClickable(true);
            touchFeedback(back, true);
            back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { requestClose(); } });
            bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout titles = new LinearLayout(a);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.setPadding(dp(4), 0, 0, 0);
            TextView title = new TextView(a);
            title.setText("Настройки профиля");
            title.setTextSize(18f);
            title.setTextColor(cText);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setSingleLine(true);
            titles.addView(title);
            String email = activityText(a, "userEmail").trim();
            if (!email.isEmpty() && !"null".equals(email)) {
                TextView sub = new TextView(a);
                sub.setText(email);
                sub.setTextSize(12.5f);
                sub.setTextColor(cGray);
                sub.setSingleLine(true);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                titles.addView(sub);
            }
            bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
            root.addView(bar, new LinearLayout.LayoutParams(-1, dp(60)));
            root.addView(divider(), new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));

            // Строка состояния загрузки (и «Повторить» при ошибке).
            LinearLayout loadRow = new LinearLayout(a);
            loadRow.setGravity(Gravity.CENTER_VERTICAL);
            loadRow.setPadding(dp(16), dp(8), dp(16), dp(8));
            loadRow.setBackgroundColor(cCard);
            loadText = new TextView(a);
            loadText.setTextSize(13f);
            loadText.setTextColor(cGray);
            loadText.setText("Загружаем профиль…");
            loadRow.addView(loadText, new LinearLayout.LayoutParams(0, -2, 1f));
            retryText = new TextView(a);
            retryText.setText("Повторить");
            retryText.setTextSize(14f);
            retryText.setTextColor(cOrange);
            retryText.setTypeface(null, android.graphics.Typeface.BOLD);
            retryText.setPadding(dp(12), dp(6), 0, dp(6));
            retryText.setVisibility(View.GONE);
            touchFeedback(retryText, false);
            retryText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                retryText.setVisibility(View.GONE);
                loadText.setTextColor(cGray);
                loadText.setText("Загружаем профиль…");
                loadClientProfile(a, true);
            }});
            loadRow.addView(retryText, new LinearLayout.LayoutParams(-2, -2));
            root.addView(loadRow, new LinearLayout.LayoutParams(-1, -2));

            scroll = new ScrollView(a);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            LinearLayout content = new LinearLayout(a);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(18), dp(16), dp(24));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

            buildAvatar(content);

            LinearLayout personal = card(content, "Личные данные", null);
            edits[SURNAME] = field(personal, "Фамилия", 100, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            edits[GIVEN] = field(personal, "Имя *", 100, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            edits[PATRONYMIC] = field(personal, "Отчество", 100, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
            edits[PHONE] = field(personal, "Номер телефона", 50, InputType.TYPE_CLASS_PHONE);
            edits[ADDRESS] = field(personal, "Адрес", 500, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
            edits[ADDRESS].setHint("Город, улица, дом, квартира");
            try { IvaPickupPatch.attachSuggest(a, edits[ADDRESS]); } catch (Throwable ignore) {}   // 10.114: подсказки полного адреса
            note(personal, "По адресу считается расстояние до пункта выдачи. Арендодателю в карточке адрес не показывается.");

            LinearLayout passport = card(content, "Паспортные данные", "необязательно");
            note(passport, "Помогают быстрее оформить договор аренды. Хранятся в защищённой записи клиента и не показываются арендодателю. Поля можно оставить пустыми.");
            LinearLayout pair = new LinearLayout(a);
            pair.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left = new LinearLayout(a);
            left.setOrientation(LinearLayout.VERTICAL);
            LinearLayout right = new LinearLayout(a);
            right.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(6);
            pair.addView(left, lp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1.4f);
            rp.leftMargin = dp(6);
            pair.addView(right, rp);
            passport.addView(pair, new LinearLayout.LayoutParams(-1, -2));
            edits[SERIES] = field(left, "Серия", 20, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            edits[NUMBER] = field(right, "Номер", 30, InputType.TYPE_CLASS_NUMBER);
            edits[ISSUED_BY] = field(passport, "Кем выдан", 200, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            issuedAtView = dateField(passport, "Дата выдачи");
            android.text.TextWatcher nameWatcher = new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) { updateInitials(); }
            };
            edits[GIVEN].addTextChangedListener(nameWatcher);
            edits[SURNAME].addTextChangedListener(nameWatcher);

            // Низ экрана: ошибка сохранения + закреплённая кнопка.
            root.addView(divider(), new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
            LinearLayout bottom = new LinearLayout(a);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setPadding(dp(16), dp(10), dp(16), dp(12));
            errorText = new TextView(a);
            errorText.setTextSize(13f);
            errorText.setTextColor(cRed);
            errorText.setPadding(0, 0, 0, dp(8));
            errorText.setVisibility(View.GONE);
            bottom.addView(errorText, new LinearLayout.LayoutParams(-1, -2));
            saveBtn = new Button(a);
            saveBtn.setAllCaps(false);
            saveBtn.setTextSize(15.5f);
            saveBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            saveBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
            bottom.addView(saveBtn, new LinearLayout.LayoutParams(-1, dp(50)));
            root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

            fill(currentValuesFromCache(), false);
            refreshSave();
        }

        /** Штатная «волна» касания из темы приложения (если тема её даёт). */
        void touchFeedback(View v, boolean borderless) {
            try {
                android.util.TypedValue tv = new android.util.TypedValue();
                int attr = borderless ? android.R.attr.selectableItemBackgroundBorderless : android.R.attr.selectableItemBackground;
                if (a.getTheme().resolveAttribute(attr, tv, true) && tv.resourceId != 0)
                    v.setBackgroundResource(tv.resourceId);
            } catch (Throwable ignore) {}
        }

        View divider() {
            View v = new View(a);
            v.setBackgroundColor(cLine);
            return v;
        }

        GradientDrawable rounded(int fill, int stroke, float radius) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(dp(radius));
            if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
            return g;
        }

        void buildAvatar(LinearLayout content) {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(0, dp(4), 0, dp(18));
            FrameLayout circleBox = new FrameLayout(a);
            avatarInitials = new TextView(a);
            avatarInitials.setTextSize(32f);
            avatarInitials.setTextColor(cOrange);
            avatarInitials.setTypeface(null, android.graphics.Typeface.BOLD);
            avatarInitials.setGravity(Gravity.CENTER);
            circleBox.addView(avatarInitials, new FrameLayout.LayoutParams(-1, -1));
            avatarImage = new ImageView(a);
            avatarImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            circleBox.addView(avatarImage, new FrameLayout.LayoutParams(-1, -1));
            // 10.98: круг «рамкой» (10.96) — только пока фото нет; под фото краёв нет. Крупнее: 96 → 120dp.
            circleBox.setBackground(IvaAvatarPatch.hole(a, avatarImage));
            circleBox.setClickable(true);
            circleBox.setContentDescription("Фото профиля");
            circleBox.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tapAvatar(); } });
            box.addView(circleBox, new LinearLayout.LayoutParams(dp(AVATAR_DP), dp(AVATAR_DP)));
            avatarBtn = new Button(a);
            avatarBtn.setText("Изменить фото");
            avatarBtn.setAllCaps(false);
            avatarBtn.setTextSize(14f);
            avatarBtn.setTextColor(cOrange);
            avatarBtn.setBackground(rounded(Color.TRANSPARENT, cOrange, 20));
            avatarBtn.setPadding(dp(18), 0, dp(18), 0);
            avatarBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickAvatar(); } });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(40));
            bp.topMargin = dp(12);
            box.addView(avatarBtn, bp);
            avatarStatus = new TextView(a);
            avatarStatus.setTextSize(12.5f);
            avatarStatus.setTextColor(cGray);
            avatarStatus.setGravity(Gravity.CENTER);
            avatarStatus.setPadding(0, dp(6), 0, 0);
            avatarStatus.setText("JPG или PNG");
            box.addView(avatarStatus, new LinearLayout.LayoutParams(-1, -2));
            content.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout card(LinearLayout content, String title, String badge) {
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(16), dp(14), dp(16), dp(16));
            c.setBackground(rounded(cCard, cLine, 14));
            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(16f);
            t.setTextColor(cText);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            head.addView(t);
            if (badge != null) {
                TextView b = new TextView(a);
                b.setText(badge);
                b.setTextSize(11.5f);
                b.setTextColor(cGray);
                b.setPadding(dp(8), dp(2), dp(8), dp(2));
                b.setBackground(rounded(cField, cFieldLine, 10));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                blp.leftMargin = dp(8);
                head.addView(b, blp);
            }
            c.addView(head);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(14);
            content.addView(c, lp);
            return c;
        }

        void note(LinearLayout box, String text) {
            TextView n = new TextView(a);
            n.setText(text);
            n.setTextSize(12.5f);
            n.setTextColor(cGray);
            n.setLineSpacing(0, 1.15f);
            n.setPadding(0, dp(8), 0, 0);
            box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        }

        TextView label(LinearLayout box, String text) {
            TextView l = new TextView(a);
            l.setText(text);
            l.setTextSize(13f);
            l.setTextColor(cGray);
            l.setPadding(0, dp(12), 0, dp(5));
            box.addView(l);
            return l;
        }

        android.graphics.drawable.Drawable fieldBg() {
            android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
            s.addState(new int[] {android.R.attr.state_focused}, rounded(cField, cOrange, 10));
            s.addState(new int[] {}, rounded(cField, cFieldLine, 10));
            return s;
        }

        EditText field(LinearLayout box, String labelText, int max, int inputType) {
            label(box, labelText);
            EditText e = new EditText(a);
            e.setInputType(inputType);
            e.setTextSize(15.5f);
            e.setTextColor(cText);
            e.setHintTextColor(cGray2);
            e.setPadding(dp(12), dp(11), dp(12), dp(11));
            e.setBackground(fieldBg());
            e.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(max)});
            if ((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) e.setSingleLine(true);
            else { e.setMinLines(1); e.setMaxLines(4); }
            box.addView(e, new LinearLayout.LayoutParams(-1, -2));
            return e;
        }

        TextView dateField(LinearLayout box, String labelText) {
            label(box, labelText);
            TextView v = new TextView(a);
            v.setTextSize(15.5f);
            v.setTextColor(cText);
            v.setHintTextColor(cGray2);
            v.setHint("Выберите дату");
            v.setPadding(dp(12), dp(11), dp(12), dp(11));
            v.setBackground(fieldBg());
            v.setClickable(true);
            v.setFocusable(false);
            v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { pickDate(); } });
            box.addView(v, new LinearLayout.LayoutParams(-1, -2));
            return v;
        }

        void setIssuedAt(String v) {
            issuedAt = v == null ? "" : v.trim();
            if (issuedAtView != null) issuedAtView.setText(dateForDisplay(issuedAt));
        }

        void pickDate() {
            try {
                int[] p = dateParts(issuedAt);
                java.util.Calendar now = java.util.Calendar.getInstance();
                int y = p != null ? p[0] : now.get(java.util.Calendar.YEAR) - 5;
                int m0 = p != null ? p[1] - 1 : now.get(java.util.Calendar.MONTH);
                int dd = p != null ? p[2] : now.get(java.util.Calendar.DAY_OF_MONTH);
                android.app.DatePickerDialog picker = new android.app.DatePickerDialog(a, android.R.style.Theme_Material_Dialog,
                    new android.app.DatePickerDialog.OnDateSetListener() {
                        @Override public void onDateSet(android.widget.DatePicker view, int year, int month, int day) {
                            setIssuedAt(isoDate(year, month + 1, day));
                        }
                    }, y, m0, dd);
                try { picker.getDatePicker().setMaxDate(System.currentTimeMillis()); } catch (Throwable ignore) {}
                picker.setButton(DialogInterface.BUTTON_NEUTRAL, "Очистить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface x, int which) { setIssuedAt(""); }
                });
                picker.show();
            } catch (Throwable t) {
                Toast.makeText(a, "Не удалось открыть выбор даты", Toast.LENGTH_SHORT).show();
            }
        }

        /** Значения формы в порядке индексов (дата — в виде для базы). */
        String[] values() {
            String[] v = new String[COUNT];
            for (int i = 0; i < COUNT; i++)
                v[i] = i == ISSUED_AT ? issuedAt : (edits[i] == null ? "" : edits[i].getText().toString().trim());
            return v;
        }

        /** Что показать до/после загрузки: запись с сервера, а пока её нет — имя и телефон из аккаунта. */
        String[] currentValuesFromCache() {
            ensureClientProfile();
            JSONObject p = clientProfile;
            JSONObject pp = p.optJSONObject("passport_data");
            if (pp == null) pp = new JSONObject();
            String[] v = new String[COUNT];
            boolean have = clientProfileLoaded && p.length() > 0;
            v[SURNAME] = p.optString("surname", "");
            v[GIVEN] = p.optString("given_name", "");
            if (v[GIVEN].trim().isEmpty() && !have) v[GIVEN] = cleanAccountValue(activityText(a, "userName"));
            v[PATRONYMIC] = p.optString("patronymic", "");
            v[PHONE] = p.optString("phone", "");
            if (v[PHONE].trim().isEmpty() && !have) v[PHONE] = cleanAccountValue(activityText(a, "userPhone"));
            v[ADDRESS] = p.optString("address", "");
            v[SERIES] = pp.optString("series", "");
            v[NUMBER] = pp.optString("number", "");
            v[ISSUED_BY] = pp.optString("issued_by", "");
            v[ISSUED_AT] = pp.optString("issued_at", "");
            for (int i = 0; i < COUNT; i++) v[i] = v[i] == null ? "" : v[i].trim();
            return v;
        }

        String cleanAccountValue(String s) {
            String v = s == null ? "" : s.trim();
            return "null".equals(v) ? "" : v;
        }

        /** Заполнить поля. onlyUntouched — не трогать то, что клиент уже успел изменить. */
        void fill(String[] v, boolean onlyUntouched) {
            String[] cur = values();
            for (int i = 0; i < COUNT; i++) {
                boolean untouched = baseline[i] == null || cur[i].equals(baseline[i]);
                if (!onlyUntouched || untouched) {
                    if (i == ISSUED_AT) setIssuedAt(v[i]);
                    else if (edits[i] != null) edits[i].setText(v[i]);
                }
                baseline[i] = v[i];
            }
            updateInitials();
        }

        void updateInitials() {
            String[] v = values();
            String name = (v[GIVEN] + " " + v[SURNAME]).trim();
            if (avatarInitials != null) avatarInitials.setText(initialsOf(name));
        }

        boolean dirty() {
            return avatarChanged || !java.util.Arrays.equals(values(), baseline);
        }

        void refreshSave() {
            if (saveBtn == null) return;
            boolean enabled = loaded && !saving && !uploading;
            saveBtn.setEnabled(enabled);
            saveBtn.setText(saveButtonText(loaded, saving, uploading));
            // 10.96: «рамкой» — тёмная, оранжевая рамка и текст; пока нельзя нажать — серая рамка и серый текст
            saveBtn.setBackground(enabled ? IvaPatch.accentBg(a, cOrange, 12) : rounded(cCard, cFieldLine, 12));
            saveBtn.setTextColor(enabled ? cOrange : cGray);
            if (avatarBtn != null) avatarBtn.setEnabled(loaded && !uploading && !saving);
        }

        /** Итог загрузки профиля: null — успешно. */
        void onProfileLoaded(String error) {
            if (clientScreen != this) return;
            if (error != null) {
                if (!loaded) {
                    loadText.setTextColor(cRed);
                    loadText.setText("Не удалось загрузить профиль: " + error);
                    retryText.setVisibility(View.VISIBLE);
                }
                return;
            }
            boolean first = !loaded;
            loaded = true;
            if (first) fill(currentValuesFromCache(), true);
            View row = (View) loadText.getParent();
            if (row != null) row.setVisibility(View.GONE);
            if (first) loadAvatarPreview();
            refreshSave();
        }

        void showError(String msg) {
            if (errorText == null) return;
            if (msg == null || msg.isEmpty()) { errorText.setVisibility(View.GONE); return; }
            errorText.setText(msg);
            errorText.setVisibility(View.VISIBLE);
        }

        void requestClose() {
            try {
                if (saving) { Toast.makeText(a, "Подождите, профиль сохраняется", Toast.LENGTH_SHORT).show(); return; }
                if (!dirty()) { close(); return; }
                new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Выйти без сохранения?")
                    .setMessage("Изменения в профиле не сохранены.")
                    .setPositiveButton("Остаться", null)
                    .setNegativeButton("Выйти", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface x, int which) { close(); }
                    })
                    .show();
            } catch (Throwable t) {
                close();
            }
        }

        void close() {
            try {
                View f = dialog == null ? null : dialog.getCurrentFocus();
                if (f != null) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        a.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
                }
            } catch (Throwable ignore) {}
            try { if (dialog != null) dialog.dismiss(); } catch (Throwable ignore) {}
            if (clientScreen == this) clientScreen = null;
        }

        void save() {
            if (!loaded || saving || uploading) return;
            showError(null);
            final String[] v = values();
            if (v[GIVEN].isEmpty()) {
                edits[GIVEN].setError("Укажите имя");
                edits[GIVEN].requestFocus();
                try { scroll.smoothScrollTo(0, Math.max(0, ((View) edits[GIVEN].getParent()).getTop() + edits[GIVEN].getTop() - dp(60))); } catch (Throwable ignore) {}
                return;
            }
            final JSONObject body = new JSONObject();
            try {
                JSONObject pp = new JSONObject();
                pp.put("series", v[SERIES]);
                pp.put("number", v[NUMBER]);
                pp.put("issued_by", v[ISSUED_BY]);
                pp.put("issued_at", v[ISSUED_AT]);
                body.put("p_surname", v[SURNAME]);
                body.put("p_given_name", v[GIVEN]);
                body.put("p_patronymic", v[PATRONYMIC]);
                body.put("p_phone", v[PHONE]);
                // Город на этом экране не редактируется — сохраняем тот, что уже в записи.
                body.put("p_city", clientProfile.optString("city", ""));
                body.put("p_address", v[ADDRESS]);
                body.put("p_passport_data", pp);
                body.put("p_avatar_path", clientAvatarPath == null ? "" : clientAvatarPath);
            } catch (Throwable e) {
                showError("Не удалось подготовить данные профиля");
                return;
            }
            saving = true;
            refreshSave();
            new Thread(new Runnable() { @Override public void run() {
                try {
                    String[] r = apiCall(a, "/rest/v1/rpc/save_my_customer_profile", "POST", body.toString(), true);
                    if (r == null) throw new Exception("нет ответа сервера");
                    if (!r[0].startsWith("2")) throw new Exception(serverMessage(r[0], r[1]));
                    final JSONObject saved = rpcFirst(r[1]);
                    clientProfile = saved.length() > 0 ? saved : clientProfile;
                    clientProfileUid = clientProfile.optString("user_id", clientProfileUid);
                    clientAvatarPath = clientProfile.optString("avatar_path", clientAvatarPath);
                    clientProfileLoaded = true;
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        saving = false;
                        // ФИО и телефон — в userName/userPhone и в настройки телефона (переживут перезапуск).
                        rememberClientName(a);
                        clientCoords = null; clientCoordsLoading = false;
                        synchronized (nativeDistanceCache) { nativeDistanceCache.clear(); }
                        avatarChanged = false;
                        close();
                        try { Method m = a.getClass().getDeclaredMethod("renderProfile"); m.setAccessible(true); m.invoke(a); } catch (Throwable ignore) {}
                        Toast.makeText(a, "Профиль сохранён", Toast.LENGTH_SHORT).show();
                    }});
                } catch (final Throwable e) {
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        saving = false;
                        String msg = brief(e.getMessage());
                        showError("Не удалось сохранить профиль: " + (msg.isEmpty() ? "нет связи с сервером" : msg));
                        refreshSave();
                    }});
                }
            }}).start();
        }

        // ── фото профиля ──
        /** 10.98: фото есть — просмотр, нет — сразу выбор. 10.109: в просмотре «Редактировать» и «Выбрать другое фото». */
        void tapAvatar() {
            final Bitmap b = IvaAvatarPatch.photoOf(avatarImage);
            if (b == null || uploading) { pickAvatar(); return; }
            final String path = clientAvatarPath == null ? "" : clientAvatarPath.trim();
            final IvaAvatarPatch.Loader hi = path.isEmpty() ? null : new IvaAvatarPatch.Loader() {
                @Override public Bitmap load() throws Exception { return decodeAvatar(downloadClientAvatar(a, path), IvaAvatarPatch.VIEW_PX); }
            };
            IvaAvatarPatch.viewer(a, b, hi, new Runnable() {
                @Override public void run() {
                    if (!loaded || uploading || saving) return;
                    IvaAvatarPatch.edit(a, IvaAvatarPatch.meSlot(a), b, hi, avatarDone());
                }
            }, new Runnable() { @Override public void run() { pickAvatar(); } });
        }

        /** Готовый квадрат → хранилище (только пока это окно открыто). */
        IvaAvatarPatch.Done avatarDone() {
            return new IvaAvatarPatch.Done() {
                @Override public void done(byte[] jpeg, Bitmap preview) {
                    if (clientScreen == ProfileScreen.this && isShowing()) uploadJpeg(jpeg, preview);
                }
            };
        }

        /** 10.98: галерея → выбор области кругом (IvaAvatarPatch) → загрузка готового квадрата. */
        void pickAvatar() {
            if (!loaded || uploading || saving) return;
            boolean ok = IvaAvatarPatch.pick(a, avatarDone());
            if (!ok) pickAvatarPlain();
        }

        /** Прежний выбор фото без выбора области — если окно выбора не открылось. */
        void pickAvatarPlain() {
            try {
                android.app.FragmentManager fm = a.getFragmentManager();
                android.app.Fragment f = fm.findFragmentByTag(AVATAR_PICKER_TAG);
                if (!(f instanceof AvatarPicker)) {
                    f = new AvatarPicker();
                    fm.beginTransaction().add(f, AVATAR_PICKER_TAG).commitAllowingStateLoss();
                    fm.executePendingTransactions();
                }
                final android.app.Fragment ff = f;
                boolean ok = IvaPhotoPatch.startGallery(1, new IvaPhotoPatch.Starter() {   // 10.109: галерея, не проводник
                    @Override public void start(Intent i) { ff.startActivityForResult(i, CLIENT_AVATAR_REQUEST); }
                });
                if (!ok) Toast.makeText(a, "Не удалось открыть галерею", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(a, "Не удалось открыть выбор фото", Toast.LENGTH_SHORT).show();
            }
        }

        /** 10.98: уже обрезанное фото (квадрат под кругом) — сразу в хранилище. */
        void uploadJpeg(final byte[] jpeg, final Bitmap shown) {
            if (jpeg == null || uploading) return;
            uploading = true;
            final int version = ++avatarVersion;
            avatarStatus.setTextColor(cGray);
            avatarStatus.setText("Загружаем фото…");
            if (shown != null) showAvatar(shown);
            refreshSave();
            final String before = clientAvatarPath == null ? "" : clientAvatarPath;
            new Thread(new Runnable() { @Override public void run() {
                try {
                    final String path = uploadClientAvatar(a, jpeg);
                    final Bitmap preview = shown != null ? shown : decodeAvatar(jpeg, dp(AVATAR_DP));
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        uploading = false;
                        clientAvatarPath = path;
                        if (version == avatarVersion && preview != null) showAvatar(preview);
                        rememberClientAvatar(avatarCacheKey(clientProfileEmail, path), preview);
                        showClientAvatar(a);
                        IvaAvatarPatch.saved(a, IvaAvatarPatch.meSlot(a));   // 10.109: исходник — для «Редактировать»
                        avatarChanged = !path.equals(before);
                        avatarStatus.setTextColor(cGray);
                        avatarStatus.setText(avatarChanged ? "Фото загружено — нажмите «Сохранить»" : "Фото обновлено");
                        refreshSave();
                    }});
                } catch (final Throwable e) {
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        uploading = false;
                        String msg = brief(e.getMessage());
                        avatarStatus.setTextColor(cRed);
                        avatarStatus.setText("Не удалось загрузить фото" + (msg.isEmpty() ? "" : ": " + msg));
                        refreshSave();
                    }});
                }
            }}).start();
        }

        void uploadAvatar(final android.net.Uri uri) {
            if (uri == null || uploading) return;
            uploading = true;
            final int version = ++avatarVersion;
            avatarStatus.setTextColor(cGray);
            avatarStatus.setText("Загружаем фото…");
            refreshSave();
            final String before = clientAvatarPath == null ? "" : clientAvatarPath;
            new Thread(new Runnable() { @Override public void run() {
                try {
                    byte[] raw = readUri(a, uri);
                    final byte[] jpeg = prepareAvatar(raw);
                    final String path = uploadClientAvatar(a, jpeg);
                    final Bitmap preview = decodeAvatar(jpeg, dp(AVATAR_DP));
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        uploading = false;
                        clientAvatarPath = path;
                        if (version == avatarVersion && preview != null) showAvatar(preview);
                        // Файл на сервере уже заменён: строка «Клиент» и меню берут новую картинку сразу,
                        // если этот путь уже в записи, иначе — после «Сохранить» (renderProfile → onProfile).
                        rememberClientAvatar(avatarCacheKey(clientProfileEmail, path), preview);
                        showClientAvatar(a);
                        // Файл на сервере уже заменён. Если путь в записи тот же — фото обновилось сразу;
                        // если фото первое — путь закрепится в записи кнопкой «Сохранить».
                        avatarChanged = !path.equals(before);
                        avatarStatus.setTextColor(cGray);
                        avatarStatus.setText(avatarChanged ? "Фото загружено — нажмите «Сохранить»" : "Фото обновлено");
                        refreshSave();
                    }});
                } catch (final Throwable e) {
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        uploading = false;
                        String msg = brief(e.getMessage());
                        avatarStatus.setTextColor(cRed);
                        avatarStatus.setText("Не удалось загрузить фото" + (msg.isEmpty() ? "" : ": " + msg));
                        refreshSave();
                    }});
                }
            }}).start();
        }

        void showAvatar(Bitmap b) {
            if (avatarImage == null || b == null) return;
            Bitmap round = circle(b, dp(AVATAR_DP), 0);
            avatarImage.setImageBitmap(round != null ? round : b);
            if (avatarInitials != null) avatarInitials.setVisibility(View.INVISIBLE);
        }

        /** Уже загруженное фото — из приватного хранилища по токену клиента. */
        void loadAvatarPreview() {
            final String path = clientAvatarPath == null ? "" : clientAvatarPath.trim();
            if (path.isEmpty()) { avatarStatus.setText("Фото ещё не добавлено"); return; }
            avatarStatus.setText("Фото загружено");
            final int version = avatarVersion;
            final String key = avatarCacheKey(clientProfileEmail, path);
            Bitmap cached = key.equals(clientAvatarBitmapKey) ? clientAvatarBitmap : null;
            if (cached != null) { showAvatar(cached); return; }
            new Thread(new Runnable() { @Override public void run() {
                try {
                    byte[] bytes = downloadClientAvatar(a, path);
                    final Bitmap b = decodeAvatar(bytes, dp(AVATAR_DP));
                    if (b != null && path.equals(savedAvatarPath())) rememberClientAvatar(key, b);
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        if (clientScreen == ProfileScreen.this && version == avatarVersion && b != null) showAvatar(b);
                    }});
                } catch (Throwable ignore) {
                    // без предпросмотра остаются буквы в кружке
                }
            }}).start();
        }
    }

    /** Невидимый помощник: результат системного выбора фото приходит сюда
     * (Activity.dispatchActivityResult → Fragment.onActivityResult), минуя MainActivity.onActivityResult. */
    public static final class AvatarPicker extends android.app.Fragment {
        public AvatarPicker() {}

        @Override public void onActivityResult(int request, int result, Intent data) {
            try { onAvatarPicked(request, result, data); } catch (Throwable ignore) {}
            try { getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss(); } catch (Throwable ignore) {}
        }
    }

    static void onAvatarPicked(int request, int result, Intent data) {
        if (request != CLIENT_AVATAR_REQUEST || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        ProfileScreen s = clientScreen;
        if (s == null || !s.isShowing()) return;   // экран уже закрыт — фото не загружаем
        s.uploadAvatar(data.getData());
    }

    /** 9) Профиль подмешивается в native после штатного renderProfile. */
    public static void onProfile(Activity a) {
        try { if (IvaAccessPatch.onProfile(a)) return; } catch (Throwable ignore) {}   // 10.110: удалённый админ — клиент (renderProfile заново)
        try {
            ensureClientProfile();
            installClientProfile(a);
        } catch (Throwable ignore) {}
        try { showClientName(a); } catch (Throwable ignore) {}
        try { showClientAvatar(a); } catch (Throwable ignore) {}
        try { IvaAvatarPatch.onProfile(a); } catch (Throwable ignore) {}   // 10.98: фото кругом без краёв, крупнее, просмотр
        try { installClientNav(a); } catch (Throwable ignore) {}
        try { IvaLogoPatch.cabinet(a); } catch (Throwable ignore) {}   // 10.113: строка «Мои инструменты проката» — есть / нет
        try { IvaShopPatch.cabinet(a); } catch (Throwable ignore) {}   // 10.116: «Настройка проката» — первой строкой кабинета (админу)
        // 10.117: строки, зависящие от роли, — после каждой перерисовки (роль при запуске приходит позже первого показа:
        // клиентский «Архив заявок» оставался рядом со штатным админским — архив «двоился»)
        try { IvaOrderPatch.profileArchiveRow(a); } catch (Throwable ignore) {}
        try { IvaNotifyPatch.ensureSupport(a); } catch (Throwable ignore) {}
        try { IvaAccessPatch.feeCard(a); } catch (Throwable ignore) {}   // 10.117: комиссия — только по полному ответу базы
        try { if (hubVisible && IvaLogoPatch.target(a) == IvaLogoPatch.NATIVE) callNativeTab(a, "home"); } catch (Throwable ignore) {}   // выход из аккаунта
    }

    // ── 16.4. Имя клиента в строке «Клиент» (10.42) ──
    // Штатный renderProfile пишет в строку userName из настроек телефона (имя при регистрации) или «Клиент».
    // Когда профиль загружен и поле «Имя» заполнено, в строке — это имя, а в кружке без фото — его первая буква.
    // Полное ФИО (фамилия имя отчество) кладём в userName и в настройки телефона: им штатный код
    // подставляет имя в заявку, и после перезапуска строка не мигает «Клиентом».

    /** Что писать в строке: имя из поля «Имя»; пустая строка — оставить штатный текст. */
    static String clientHeadName(String given) {
        String g = given == null ? "" : given.trim().replaceAll("\\s+", " ");
        if (g.length() > 60) g = g.substring(0, 60).trim();
        return g;
    }

    /** Буква в кружке без фото — как у штатного кода: первая буква имени заглавной. */
    static String clientHeadLetter(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "";
        return new String(Character.toChars(Character.toUpperCase(n.codePointAt(0))));
    }

    static void showClientName(Activity a) {
        try {
            if (!isClient(a)) return;   // админ: строку ведёт штатный код
            String email = String.valueOf(get(a, "userEmail"));
            if (!clientProfileLoaded || clientProfile == null || !email.equalsIgnoreCase(clientProfileEmail)) return;
            String name = clientHeadName(clientProfile.optString("given_name", ""));
            if (name.isEmpty()) return;
            Object pn = get(a, "profName");
            if (pn instanceof TextView) ((TextView) pn).setText(name);
            Object at = get(a, "avatarTxt");
            if (at instanceof TextView) ((TextView) at).setText(clientHeadLetter(name));
            rememberClientName(a);
        } catch (Throwable ignore) {
            // имя — только подпись: кабинет работает и со штатным «Клиент»
        }
    }

    /** ФИО и телефон из профиля → userName/userPhone и настройки телефона (те же ключи, что у штатного saveSession). */
    static void rememberClientName(Activity a) {
        try {
            if (clientProfile == null) return;
            String full = profileName(clientProfile).trim();
            String phone = clientProfile.optString("phone", "").trim();
            String curName = activityText(a, "userName"), curPhone = activityText(a, "userPhone");
            boolean nameChanged = !full.isEmpty() && !full.equals(curName);
            boolean phoneChanged = !phone.isEmpty() && !phone.equals(curPhone);
            if (!nameChanged && !phoneChanged) return;
            if (nameChanged) set(a, "userName", full);
            if (phoneChanged) set(a, "userPhone", phone);
            Object prefs = get(a, "prefs");
            if (prefs instanceof SharedPreferences) {
                SharedPreferences.Editor e = ((SharedPreferences) prefs).edit();
                if (nameChanged) e.putString("name", full);
                if (phoneChanged) e.putString("phone", phone);
                e.apply();
            }
        } catch (Throwable ignore) {}
    }

    // ── 16.5. Логотип в нижнем меню у клиента: «Избранное» и «Мои инструменты» (10.43) ──
    // Раньше логотип у клиента открывал форму заявки (штатный onPlus → showTab("order")). Теперь у клиента
    // открывается свой раздел с двумя вкладками вверху: «Избранное» — карточки инструментов, отмеченных
    // сердечком (штатный набор favs из таблицы likes), «Мои инструменты» — карточки инструментов из прошлых
    // заявок клиента (orders.tools, как в проверке права на отзыв). Карточки — штатные feedCard, как на главной:
    // нажатие открывает штатную карточку инструмента, сердечко работает как обычно; «Назад» в карточке
    // инструмента возвращает в раздел. Форма заявки по-прежнему открывается кнопкой «Заказать» в карточке.
    // Новых точек вставки нет: обработчики ставятся на штатные кнопки navPlus и detBack из onProfile,
    // скрытие раздела — из уже подключённого onTab. У админа и гостя логотип работает как раньше.
    static final String HUB_TAG = "iva_client_hub";
    static final String HUB_CONTENT_TAG = "iva_client_hub_content";
    static final String HUB_TAB_FAV_TAG = "iva_client_hub_fav";
    static final String HUB_TAB_MINE_TAG = "iva_client_hub_mine";
    static final String HUB_PAGE = "clienthub";            // curTab, пока открыт раздел (штатные проверки вкладок молчат)
    static volatile boolean hubVisible = false;
    static boolean hubStyling = false;                      // идёт подсветка логотипа через onTab — раздел не прятать
    static boolean detailFromHub = false;                   // карточка инструмента открыта из раздела — «Назад» вернёт сюда
    static int hubTab = 0;                                  // 0 — «Избранное», 1 — «Мои инструменты»
    static int hubGen = 0;                                  // поколение отрисовки: устаревшие ответы не рисуем
    static int hubCatalogWaits = 0;
    static WeakReference<View> hubNavPlus, hubDetBack;

    /** Статусы заявки, при которых инструмент уже был выдан клиенту. */
    static boolean isRentedStatus(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        return "rented".equals(v) || "closed".equals(v);
    }

    /** Часть заявки (одного арендодателя) отмечена «Выдал»/«Вернули» и относится к этому инструменту.
     * Пустой текст части — вся часть целиком (в заявке один арендодатель). */
    static boolean partMarksRented(String status, String partTools, String name) {
        if (!isRentedStatus(status)) return false;
        String pt = partTools == null ? "" : partTools.trim();
        return pt.isEmpty() || orderMentionsTool(pt, name);
    }

    /** Есть ли инструмент в тексте заявки. Текст заявки собирается как «Название ×1, Название 2 ×3»
     * (сайт и приложение), поэтому название должно стоять целиком: перед ним — начало, «,», «;», «•» или
     * перевод строки, после — «×N»/«xN», «,», «;», перевод строки или конец. «Перфоратор» не совпадёт
     * с «Перфоратор Bosch ×1». */
    static boolean orderMentionsTool(String toolsText, String name) {
        String text = toolsText == null ? "" : toolsText.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.length() < 2 || text.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = text.indexOf(n, from);
            if (i < 0) return false;
            from = i + 1;
            int b = i - 1;
            while (b >= 0 && text.charAt(b) == ' ') b--;
            boolean startOk = b < 0 || ",;\n\u2022".indexOf(text.charAt(b)) >= 0;
            int e = i + n.length();
            while (e < text.length() && text.charAt(e) == ' ') e++;
            boolean endOk = e >= text.length() || ",;\n\r".indexOf(text.charAt(e)) >= 0
                || ((text.charAt(e) == '\u00d7' || text.charAt(e) == 'x' || text.charAt(e) == '*')
                    && (e + 1 >= text.length() || Character.isDigit(text.charAt(e + 1)) || text.charAt(e + 1) == ' '));
            if (startOk && endOk) return true;
        }
    }

    /** Поставить обработчики на штатные кнопки: логотип (navPlus) и «Назад» в карточке инструмента (detBack).
     * Один раз на экран; у не-клиента нажатия уходят в штатные onPlus / showTab("home"). */
    static void installClientNav(final Activity a) {
        try {
            View plus = a.findViewById(id(a, "navPlus"));
            if (plus != null && (hubNavPlus == null || hubNavPlus.get() != plus)) {
                plus.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // 10.113: «Вид раздела с логотипом» — админ может выбрать раздел клиента, сотрудник — как у админа
                        int t = IvaLogoPatch.NATIVE;
                        try { t = IvaLogoPatch.target(a); } catch (Throwable ignore) {}
                        if (t == IvaLogoPatch.CLIENT_HUB && openHub(a, 0)) return;
                        if (t == IvaLogoPatch.STAFF_HUB && openHub(a, 1)) return;
                        callNative(a, "onPlus");
                    }
                });
                hubNavPlus = new WeakReference<View>(plus);
            }
            View back = a.findViewById(id(a, "detBack"));
            if (back != null && (hubDetBack == null || hubDetBack.get() != back)) {
                back.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean toHub = false;
                        try { toHub = detailFromHub && IvaLogoPatch.target(a) == IvaLogoPatch.CLIENT_HUB; } catch (Throwable ignore) {}
                        detailFromHub = false;
                        if (toHub && openClientHub(a)) return;
                        callNativeTab(a, "home");   // как штатная кнопка: на главную
                    }
                });
                hubDetBack = new WeakReference<View>(back);
            }
        } catch (Throwable ignore) {
            // кнопки остаются штатными
        }
    }

    static void callNative(Activity a, String method) {
        try {
            Method m = a.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignore) {}
    }

    static void callNativeTab(Activity a, String tab) {
        try {
            Method m = a.getClass().getDeclaredMethod("showTab", String.class);
            m.setAccessible(true);
            m.invoke(a, tab);
        } catch (Throwable ignore) {}
    }

    /** Из IvaPatch.onTab (конец штатного showTab): любой штатный переход прячет раздел. */
    public static void onShowTab(Activity a, String tab) {
        if (hubStyling) return;
        try { installClientNav(a); } catch (Throwable ignore) {}   // первый showTab при запуске — кнопки уже наши
        try { if ("profile".equals(tab)) profileContacts(a); } catch (Throwable ignore) {}
        try { adminTabsFlat(a); } catch (Throwable ignore) {}
        try {
            String t = tab == null ? "" : tab;
            if (hubVisible) {
                detailFromHub = "detail".equals(t);
                hideClientHub(a);
            } else if (!"detail".equals(t) && !"seller".equals(t)) {
                detailFromHub = false;
            }
        } catch (Throwable ignore) {}
    }

    // ── 10.62: в кабинете клиента нет «Позвонить», «Написать в МАКС» и «Адрес выдачи» (просьба владельца 14.10.2026) ──
    // 10.78: и у админа тоже (просьба владельца). Строки штатные (rowCall, rowMax, строка с addrInfoTxt) — только
    // скрываются, не удаляются: сами строки, их нажатия и текст адреса не меняются («Архив заявок» встаёт перед rowCall).
    static final String[] PROFILE_HIDE_IDS = {"rowCall", "rowMax"};
    static final boolean PROFILE_CONTACTS_SHOW = false;     // 10.78: ни у клиента, ни у админа

    static void profileContacts(Activity a) {
        boolean show = PROFILE_CONTACTS_SHOW;               // 10.78: раньше у админа строки были видны
        java.util.List<WeakReference<View>> seen = new ArrayList<WeakReference<View>>();
        for (String n : PROFILE_HIDE_IDS) {
            int id = a.getResources().getIdentifier(n, "id", a.getPackageName());
            View v = id == 0 ? null : a.findViewById(id);
            if (v != null) { v.setVisibility(show ? View.VISIBLE : View.GONE); seen.add(new WeakReference<View>(v)); }
        }
        Object t = get(a, "addrInfoTxt");                       // «Адрес выдачи»: строка = родитель родителя текста адреса
        if (t instanceof View && ((View) t).getParent() instanceof View) {
            ViewParent row = ((View) ((View) t).getParent()).getParent();
            if (row instanceof ViewGroup && isSingleRow(a, (ViewGroup) row)) {
                ((View) row).setVisibility(show ? View.VISIBLE : View.GONE);
                seen.add(new WeakReference<View>((View) row));
            }
        }
        contactViews = seen;
        contactsShow = show;
        try { watchContacts(a); } catch (Throwable ignore) {}
    }

    // ── 10.67: после «потянуть вниз» в кабинете клиента снова появлялись «Позвонить» и «Написать в МАКС» ──
    // Штатный renderProfile сам делает rowCall / rowMax видимыми, а зовут его не только при открытии вкладки (тогда
    // мы их прячем), но и обновление свайпом, фоновая проверка админа (checkSelfAdmin), выход. Ловить каждого — хрупко,
    // поэтому перед каждой отрисовкой окна (OnPreDrawListener) сверяем: у клиента строка видна — прячем и пропускаем
    // этот кадр, так что мигания нет. Только прячем: админу видимость не навязываем (как решит штатный код).
    static java.util.List<WeakReference<View>> contactViews = new ArrayList<WeakReference<View>>();
    static boolean contactsShow = true;
    static int contactsFixes = 0;
    static WeakReference<Activity> contactsWatched = new WeakReference<Activity>(null);
    static final int CONTACTS_FIX_MAX = 2;    // не вышло спрятать за 2 кадра — рисуем как есть, кадр не держим

    /** Надо ли спрятать: строки должны быть скрыты (с 10.78 — всегда), а какая-то из них видна. */
    static boolean contactsLeak(boolean show, boolean[] visible) {
        if (show || visible == null) return false;
        for (boolean v : visible) if (v) return true;
        return false;
    }

    static void watchContacts(final Activity a) {
        if (contactsWatched.get() == a) return;
        View decor = a.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try {
                    java.util.List<WeakReference<View>> views = contactViews;
                    boolean[] vis = new boolean[views.size()];
                    for (int i = 0; i < vis.length; i++) {
                        View v = views.get(i).get();
                        vis[i] = v != null && v.getVisibility() == View.VISIBLE;
                    }
                    if (!contactsLeak(contactsShow, vis)) { contactsFixes = 0; return true; }
                    if (contactsFixes >= CONTACTS_FIX_MAX) return true;
                    contactsFixes++;
                    profileContacts(a);           // заново прячем (с 10.78 — и у админа)
                    return false;                 // этот кадр не рисуем — строки не мелькнут
                } catch (Throwable ignore) { return true; }
            }
        });
        contactsWatched = new WeakReference<Activity>(a);
    }

    /** Это одна строка «Адрес выдачи», а не весь список кабинета: в ней нет других пунктов (версия, «Позвонить», «Инструкция»). */
    static boolean isSingleRow(Activity a, ViewGroup row) {
        for (String n : new String[]{"profVer", "rowCall", "rowMax", "profHelp", "profLend"}) {
            int id = a.getResources().getIdentifier(n, "id", a.getPackageName());
            if (id != 0 && row.findViewById(id) != null) return false;
        }
        return true;
    }

    // ── 10.63: окно выбора в стиле приложения (просьба владельца 14.10.2026) ──
    // Тёмная карточка (фон bg, рамка line), варианты — тёмные «пузыри» (card, рамка line) с оранжевым жирным текстом,
    // «Отмена» — такой же пузырь с серым текстом. Нажатие мимо, «Отмена» и системная «Назад» — ничего не выбрано.
    interface Choice { void pick(int index); }
    static final String CHOICE_CANCEL = "Отмена";
    static final int CHOICE_BUBBLE_DP = 50, CHOICE_RADIUS_DP = 14, CHOICE_CARD_RADIUS_DP = 20, CHOICE_MAX_DP = 360;

    static GradientDrawable choiceBg(Activity a, String fill, int def, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(navColor(a, fill, def));
        g.setStroke(Math.max(1, hubDp(a, 1)), navColor(a, "line", 0xff262b35));
        g.setCornerRadius(hubDp(a, radiusDp));
        return g;
    }

    static Dialog styledChoice(final Activity a, String title, String text, final String[] items, final Choice cb) {
        int orange = navColor(a, "orange", 0xffff8f1f), gray = navColor(a, "gray", 0xff9aa1b0), white = navColor(a, "text", 0xfff2f4f8);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(choiceBg(a, "bg", 0xff101217, CHOICE_CARD_RADIUS_DP));
        card.setPadding(hubDp(a, 18), hubDp(a, 18), hubDp(a, 18), hubDp(a, 14));
        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(white);
            t.setTextSize(17f);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (text != null && !text.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(text);
            t.setTextColor(gray);
            t.setTextSize(13f);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = hubDp(a, 6);
            card.addView(t, tl);
        }
        for (int i = 0; i <= items.length; i++) {
            final int idx = i < items.length ? i : -1;
            TextView b = new TextView(a);
            b.setText(idx >= 0 ? items[i] : CHOICE_CANCEL);
            b.setTextColor(idx >= 0 ? orange : gray);
            b.setTextSize(15f);
            b.setTypeface(null, idx >= 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(choiceBg(a, "card", 0xff1a1d24, CHOICE_RADIUS_DP));
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { d.dismiss(); } catch (Throwable ignore) {}
                    if (idx >= 0 && cb != null) try { cb.pick(idx); } catch (Throwable ignore) {}
                }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, hubDp(a, CHOICE_BUBBLE_DP));
            bl.topMargin = hubDp(a, i == 0 ? 16 : 8);
            card.addView(b, bl);
        }
        d.setContentView(card);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        d.show();
        if (w != null) {                                       // ширина — после show (иначе её перекрывает тема окна)
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screen - hubDp(a, 48), hubDp(a, CHOICE_MAX_DP)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    static ViewGroup hubPages(Activity a) {
        Object po = get(a, "pageOrder");
        ViewParent p = po instanceof View ? ((View) po).getParent() : null;
        return p instanceof ViewGroup ? (ViewGroup) p : null;
    }

    static void hideClientHub(Activity a) {
        hubVisible = false;
        try {
            ViewGroup pages = hubPages(a);
            View hub = pages == null ? null : pages.findViewWithTag(HUB_TAG);
            if (hub != null) hub.setVisibility(View.GONE);
        } catch (Throwable ignore) {}
    }

    /** Открыть раздел на месте страниц (нижнее меню остаётся). false — не вышло, пусть сработает штатный onPlus. */
    static boolean openClientHub(final Activity a) { return openHub(a, 0); }

    /** 10.113: какой раздел на месте страниц — 0 «Избранное / Мои инструменты» (клиент), 1 «Мой инструмент / Добавить
     *  инструмент» (сотрудник с видом «как у админов», IvaLogoPatch). */
    static int hubKind = 0, hubBuiltKind = -1;

    static boolean openHub(final Activity a, int kind) {
        try {
            ViewGroup pages = hubPages(a);
            if (pages == null) return false;
            View hub = pages.findViewWithTag(HUB_TAG);
            if (hub != null && hubBuiltKind != kind) { pages.removeView(hub); hub = null; }   // другие вкладки — собрать заново
            hubKind = kind;
            if (hub == null) {
                hub = buildClientHub(a);
                hub.setTag(HUB_TAG);
                pages.addView(hub, new FrameLayout.LayoutParams(-1, -1));
                hubBuiltKind = kind;
            }
            for (int i = 0; i < pages.getChildCount(); i++) {
                View c = pages.getChildAt(i);
                if (c != hub && c.getVisibility() == View.VISIBLE) c.setVisibility(View.GONE);
            }
            hub.setVisibility(View.VISIBLE);
            hubVisible = true;
            detailFromHub = false;
            hubCatalogWaits = 0;
            try { set(a, "curTab", HUB_PAGE); } catch (Throwable ignore) {}
            // Подсветка: логотип выбран, остальные пункты меню — нет (тот же код, что после штатного showTab).
            hubStyling = true;
            try { IvaPatch.onTab(a, HUB_PAGE); } catch (Throwable ignore) {} finally { hubStyling = false; }
            try {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    a.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(pages.getWindowToken(), 0);
            } catch (Throwable ignore) {}
            renderClientHub(a);
            return true;
        } catch (Throwable t) {
            hubVisible = false;
            return false;
        }
    }

    static int hubDp(Activity a, float v) {
        return px(v, a.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable hubPill(Activity a, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(hubDp(a, 20));
        if (stroke != 0) g.setStroke(Math.max(1, hubDp(a, 1)), stroke);
        return g;
    }

    static View buildClientHub(final Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClickable(true);   // нажатия не проходят на спрятанные страницы под разделом
        // 10.63: вкладки один в один как у админа (штатные hubTabMy/hubTabAdd): общая карточка-«пузырь» сверху
        // закруглена, снизу ровная — по размеру подчёркивания; текст 14sp, выбранная — оранжевая жирная и с линией 3dp.
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(tabCard(a));
        String[] names = IvaLogoPatch.TABS[hubKind == 1 ? 1 : 0];   // 10.113: у сотрудника «как у админов» — свои вкладки
        tabs.addView(hubTabButton(a, names[0], HUB_TAB_FAV_TAG, 0), new LinearLayout.LayoutParams(0, -2, 1f));
        tabs.addView(hubTabButton(a, names[1], HUB_TAB_MINE_TAG, 1), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.setMargins(hubDp(a, 16), hubDp(a, 16), hubDp(a, 16), hubDp(a, 12));   // как у админа: поля страницы 16dp, снизу 12dp
        root.addView(tabs, tl);
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setTag(HUB_CONTENT_TAG);
        content.setPadding(hubDp(a, 12), hubDp(a, 4), hubDp(a, 12), hubDp(a, 20));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    static final int HUB_TAB_TOP_DP = 12, HUB_TAB_LINE_TOP_DP = 10, HUB_TAB_LINE_DP = 3, HUB_TAB_RADIUS_DP = 16;
    static final String HUB_TAB_TEXT = "_text", HUB_TAB_LINE = "_line";

    /** Половина карточки: текст и подчёркивание под ним (как штатные hubTabMy / hubTabAdd). */
    static LinearLayout hubTabButton(final Activity a, String text, String tag, final int index) {
        return tabButton(a, text, tag, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hubKind == 1) {                                 // 10.113: сотрудник — «Добавить инструмент» — штатная форма
                    if (index == 1) { try { IvaStaffToolsPatch.openAddHub(a); } catch (Throwable ignore) {} }
                    return;
                }
                if (hubTab == index) return;
                hubTab = index;
                hubCatalogWaits = 0;
                renderClientHub(a);
            }
        });
    }

    /** 10.64: та же вкладка для любого раздела (раздел логотипа, «Заявки»): текст 14sp и линия 3dp под ним. */
    static LinearLayout tabButton(Activity a, String text, String tag, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);                       // как штатные: gravity center, paddingTop 12dp
        b.setPadding(0, hubDp(a, HUB_TAB_TOP_DP), 0, 0);
        b.setTag(tag);
        b.setClickable(true);
        TextView t = new TextView(a);
        t.setText(text);
        t.setTag(tag + HUB_TAB_TEXT);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14f);
        b.addView(t, new LinearLayout.LayoutParams(-2, -2));
        View line = new View(a);
        line.setTag(tag + HUB_TAB_LINE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, hubDp(a, HUB_TAB_LINE_DP));
        ll.topMargin = hubDp(a, HUB_TAB_LINE_TOP_DP);
        b.addView(line, ll);
        b.setOnClickListener(click);
        return b;
    }

    /** «Пузырь» вкладок: сверху закруглён как углы карточки, снизу ровный — заканчивается подчёркиванием. */
    static float[] hubBubbleRadii(float r) {
        return new float[]{r, r, r, r, 0, 0, 0, 0};
    }

    /** Карточка вкладок: цвета штатной bg_card (фон card, рамка 1dp line), углы 16dp только сверху. */
    static GradientDrawable tabCard(Activity a) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(navColor(a, "card", 0xff1a1d24));
        g.setStroke(Math.max(1, hubDp(a, 1)), navColor(a, "line", 0xff262b35));
        g.setCornerRadii(hubBubbleRadii(hubDp(a, HUB_TAB_RADIUS_DP)));
        return g;
    }

    /** 10.63: у админа (штатные вкладки «Мой инструмент / Добавить инструмент») карточка снизу ровная. */
    static void adminTabsFlat(Activity a) {
        int id = a.getResources().getIdentifier("hubTabMy", "id", a.getPackageName());
        View my = id == 0 ? null : a.findViewById(id);
        if (my == null || !(my.getParent() instanceof ViewGroup)) return;
        ViewGroup card = (ViewGroup) my.getParent();
        if (ADMIN_TABS_TAG.equals(card.getTag())) return;
        card.setBackground(tabCard(a));
        card.setTag(ADMIN_TABS_TAG);
    }
    static final String ADMIN_TABS_TAG = "iva_admin_tabs_flat";

    /** Как штатный showAdmHub: выбранная — оранжевая жирная и линия, другая — серая обычная, линии нет. */
    static void styleHubTabs(Activity a, View hub) {
        styleTabs(a, hub, new String[]{HUB_TAB_FAV_TAG, HUB_TAB_MINE_TAG}, hubKind == 1 ? 0 : hubTab);
    }

    static void styleTabs(Activity a, View root, String[] tags, int selected) {
        int orange = navColor(a, "orange", 0xffff8f1f);
        int gray = navColor(a, "gray", 0xff9aa1b0);
        for (int i = 0; i < tags.length; i++) {
            View v = root.findViewWithTag(tags[i]);
            if (v == null) continue;
            boolean on = selected == i;
            v.setBackground(null);
            View tv = v.findViewWithTag(tags[i] + HUB_TAB_TEXT);
            if (tv instanceof TextView) {
                ((TextView) tv).setTextColor(on ? orange : gray);
                ((TextView) tv).setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            View line = v.findViewWithTag(tags[i] + HUB_TAB_LINE);
            if (line != null) line.setBackgroundColor(on ? orange : Color.TRANSPARENT);
        }
    }

    /** 10.113: раздел сотрудника ещё на экране и это та же отрисовка (ответ сети не устарел). */
    static boolean hubAlive(int gen) { return hubVisible && hubKind == 1 && gen == hubGen; }

    static void renderClientHub(final Activity a) {
        try {
            ViewGroup pages = hubPages(a);
            final View hub = pages == null ? null : pages.findViewWithTag(HUB_TAG);
            if (hub == null || !hubVisible) return;
            styleHubTabs(a, hub);
            View cv = hub.findViewWithTag(HUB_CONTENT_TAG);
            if (!(cv instanceof LinearLayout)) return;
            LinearLayout content = (LinearLayout) cv;
            content.removeAllViews();
            final int gen = ++hubGen;
            if (hubKind == 1) { IvaStaffToolsPatch.renderHub(a, content, gen); return; }   // 10.113: инструменты пунктов
            ArrayList tools = hubTools(a);
            if (tools.isEmpty()) {
                // каталог (а с ним и избранное) ещё грузится штатным loadCatalog
                if (hubCatalogWaits < 10) {
                    hubCatalogWaits++;
                    content.addView(hubEmpty(a, "Загружаем каталог\u2026"));
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { if (hubVisible && gen == hubGen) renderClientHub(a); }
                    }, 1500);
                } else {
                    content.addView(hubEmpty(a, "Каталог не загрузился. Проверьте интернет и откройте раздел ещё раз."));
                }
                return;
            }
            if (hubTab == 0) renderHubFavorites(a, content, tools);
            else renderHubMine(a, content, tools, gen);
        } catch (Throwable ignore) {
            // раздел — только показ: остальное приложение не трогаем
        }
    }

    static ArrayList hubTools(Activity a) {
        // 10.45: в tools может лежать выдача по городу и фильтрам — раздел берёт полный каталог.
        try { return new ArrayList(IvaSearchPatch.allTools(a)); } catch (Throwable ignore) {}
        Object v = get(a, "tools");
        return v instanceof ArrayList ? new ArrayList((ArrayList) v) : new ArrayList();
    }

    static void renderHubFavorites(Activity a, LinearLayout content, ArrayList tools) {
        Object fv = get(a, "favs");
        java.util.Set favs = fv instanceof java.util.Set ? (java.util.Set) fv : new java.util.HashSet();
        ArrayList list = new ArrayList();
        for (Object o : tools) {
            if (!(o instanceof JSONObject)) continue;
            String tid = ((JSONObject) o).optString("id", "");
            if (!tid.isEmpty() && favs.contains("t" + tid)) list.add(o);
        }
        if (list.isEmpty()) {
            content.addView(hubEmpty(a, "В избранном пока пусто.\n\nНажмите на сердечко на карточке инструмента в каталоге — он появится здесь."));
            return;
        }
        addHubCards(a, content, list);
    }

    static void renderHubMine(final Activity a, final LinearLayout content, final ArrayList tools, final int gen) {
        content.addView(hubEmpty(a, "Загружаем ваши заявки\u2026"));
        final String email = String.valueOf(get(a, "userEmail")).trim().toLowerCase(Locale.ROOT);
        new Thread(new Runnable() { @Override public void run() {
            String error = null;
            JSONArray rows = null;
            boolean statusKnown = true;
            try {
                String q = "&user_email=eq." + java.net.URLEncoder.encode(email, "UTF-8") + "&order=created_at.desc&limit=100";
                Method get = a.getClass().getDeclaredMethod("apiGet", String.class);
                get.setAccessible(true);
                try {
                    // Выдачу отмечает арендодатель в части заявки (order_parts.status: «Выдал» → rented,
                    // «Вернули» → closed), поэтому сначала читаем заявки вместе с их частями. Если части
                    // клиенту не видны (политика RLS), PostgREST вернёт пустой список — тогда статус берётся
                    // из самой заявки.
                    rows = new JSONArray(String.valueOf(get.invoke(a, "/rest/v1/orders?select=created_at,tools,status,order_parts(status,tools_text)" + q)));
                } catch (Throwable first) {
                    try {
                        rows = new JSONArray(String.valueOf(get.invoke(a, "/rest/v1/orders?select=created_at,tools,status" + q)));
                    } catch (Throwable second) {
                        // если в таблице нет поля status — без разделения на «выдано» и «в заявке»
                        statusKnown = false;
                        rows = new JSONArray(String.valueOf(get.invoke(a, "/rest/v1/orders?select=created_at,tools" + q)));
                    }
                }
            } catch (Throwable e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                error = brief(c.getMessage());
                if (error.isEmpty()) error = "нет связи с сервером";
            }
            final String err = error;
            final JSONArray data = rows;
            final boolean known = statusKnown;
            a.runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    if (!hubVisible || gen != hubGen) return;
                    content.removeAllViews();
                    if (err != null) {
                        TextView t = hubEmpty(a, "Не удалось загрузить заявки: " + err + "\n\nНажмите, чтобы повторить.");
                        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { renderClientHub(a); } });
                        content.addView(t);
                        return;
                    }
                    showHubMine(a, content, tools, data, known);
                } catch (Throwable ignore) {}
            }});
        }}).start();
    }

    static void showHubMine(Activity a, LinearLayout content, ArrayList tools, JSONArray orders, boolean statusKnown) {
        if (orders == null || orders.length() == 0) {
            content.addView(hubEmpty(a, "Здесь появятся инструменты, которые вы брали в аренду.\n\nПока заявок нет — выберите инструмент в каталоге."));
            return;
        }
        java.util.LinkedHashMap<String, Object> rented = new java.util.LinkedHashMap<String, Object>();
        java.util.LinkedHashMap<String, Object> asked = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < orders.length(); i++) {          // заявки — от новых к старым
            JSONObject o = orders.optJSONObject(i);
            if (o == null) continue;
            String text = o.optString("tools", "");
            boolean orderDone = !statusKnown || isRentedStatus(o.optString("status", ""));
            JSONArray parts = o.optJSONArray("order_parts");
            for (Object t : tools) {
                if (!(t instanceof JSONObject)) continue;
                JSONObject tool = (JSONObject) t;
                String tid = tool.optString("id", "");
                String name = tool.optString("name", "");
                if (tid.isEmpty() || !orderMentionsTool(text, name)) continue;
                boolean done = orderDone;
                for (int k = 0; !done && parts != null && k < parts.length(); k++) {
                    JSONObject part = parts.optJSONObject(k);
                    if (part == null) continue;
                    done = partMarksRented(part.optString("status", ""), part.optString("tools_text", ""), name);
                }
                if (done) { asked.remove(tid); if (!rented.containsKey(tid)) rented.put(tid, tool); }
                else if (!rented.containsKey(tid) && !asked.containsKey(tid)) asked.put(tid, tool);
            }
        }
        if (rented.isEmpty() && asked.isEmpty()) {
            content.addView(hubEmpty(a, "Инструментов из ваших заявок сейчас нет в каталоге."));
            return;
        }
        if (!rented.isEmpty()) {
            if (!asked.isEmpty()) content.addView(hubSection(a, statusKnown ? "Брали в аренду" : "Из ваших заявок"));
            addHubCards(a, content, new ArrayList(rented.values()));
        }
        if (!asked.isEmpty()) {
            content.addView(hubSection(a, "В заявках — ещё не выданы"));
            if (rented.isEmpty()) {
                TextView hint = hubSection(a, "Инструмент перейдёт в «Брали в аренду», когда арендодатель отметит выдачу.");
                hint.setTypeface(null, android.graphics.Typeface.NORMAL);
                hint.setPadding(hubDp(a, 6), 0, hubDp(a, 6), hubDp(a, 8));
                content.addView(hint);
            }
            addHubCards(a, content, new ArrayList(asked.values()));
        }
    }

    /** Карточки по две в ряд — штатный feedCard (тот же вид и поведение, что на главной). */
    static void addHubCards(Activity a, LinearLayout content, ArrayList list) {
        Method card;
        try {
            card = a.getClass().getDeclaredMethod("feedCard", JSONObject.class, boolean.class);
            card.setAccessible(true);
        } catch (Throwable e) {
            content.addView(hubEmpty(a, "Не удалось показать карточки."));
            return;
        }
        for (int i = 0; i < list.size(); i += 2) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int added = 0;
            for (int j = i; j < Math.min(i + 2, list.size()); j++) {
                try {
                    Object v = card.invoke(a, list.get(j), false);
                    if (v instanceof View) { row.addView((View) v); added++; }
                } catch (Throwable ignore) {}
            }
            if (added == 0) continue;
            if (added == 1) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));   // половина ряда, как в сетке
            content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    static TextView hubEmpty(Activity a, String text) {
        try {
            Method m = a.getClass().getDeclaredMethod("emptyText", String.class);
            m.setAccessible(true);
            Object v = m.invoke(a, text);
            if (v instanceof TextView) return (TextView) v;
        } catch (Throwable ignore) {}
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(navColor(a, "gray", 0xff9aa1b0));
        t.setTextSize(15f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, hubDp(a, 50), 0, 0);
        return t;
    }

    static TextView hubSection(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(navColor(a, "gray", 0xff9aa1b0));
        t.setTextSize(13f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(hubDp(a, 6), hubDp(a, 12), hubDp(a, 6), hubDp(a, 6));
        return t;
    }

    // ── 16.3. Фото клиента в строке «Клиент» и в нижнем меню (10.41) ──
    // У админа фото ставит штатный код (поле admins.avatar, публичный адрес). У клиента фото лежит
    // в приватном bucket customer-avatars — штатный loadPhoto его не скачает (нужен токен), поэтому
    // картинку качаем сами по токену клиента и кладём в те же места: штатный CircleImageView avatarImg
    // строки «Клиент» (буквы avatarTxt прячем) и кружок «Кабинета» в нижнем меню (тег AV_TAG, как у админа:
    // окантовку рисует onTab, при выходе кружок убирает штатный hook onLogout).

    /** Ключ кэша картинки: почта аккаунта + путь в хранилище. Пустой путь — фото нет. */
    static String avatarCacheKey(String email, String path) {
        String p = path == null ? "" : path.trim();
        if (p.isEmpty()) return "";
        return (email == null ? "" : email.trim().toLowerCase(Locale.ROOT)) + "|" + p;
    }

    /** Путь к фото из сохранённой записи (не из несохранённой загрузки на открытом экране). */
    static String savedAvatarPath() {
        return clientProfile == null ? "" : clientProfile.optString("avatar_path", "").trim();
    }

    /** Показать фото клиента (или вернуть буквы). Только UI-поток; сеть — в фоне, один запрос за раз. */
    static void showClientAvatar(final Activity a) {
        try {
            Object token = get(a, "accessToken");
            boolean signedIn = token instanceof String && !((String) token).trim().isEmpty();
            if (!signedIn) {
                // Выход: фото прежнего клиента не остаётся ни в строке, ни в памяти.
                clientAvatarBitmap = null; clientAvatarBitmapKey = ""; clientAvatarFailedKey = "";
                if (clientHeadPhotoShown) restoreClientHead(a);
                return;
            }
            if (!isClient(a)) return;   // админ: строку и меню ведёт штатный код
            String email = String.valueOf(get(a, "userEmail"));
            if (!clientProfileLoaded || !email.equalsIgnoreCase(clientProfileEmail)) return;   // запись ещё грузится
            final String path = savedAvatarPath();
            final String key = avatarCacheKey(clientProfileEmail, path);
            if (key.isEmpty()) {
                if (clientHeadPhotoShown) restoreClientHead(a);
                removeClientNavAvatar(a);
                return;
            }
            Bitmap b = key.equals(clientAvatarBitmapKey) ? clientAvatarBitmap : null;
            if (b != null) { putClientAvatar(a, b); return; }
            if (clientAvatarFetching || key.equals(clientAvatarFailedKey)) return;
            clientAvatarFetching = true;
            final int size = px(96, a.getResources().getDisplayMetrics().density);
            new Thread(new Runnable() { @Override public void run() {
                boolean ok = false;
                try {
                    Bitmap d = decodeAvatar(downloadClientAvatar(a, path), size);
                    if (d != null) {
                        rememberClientAvatar(key, d);
                        ok = true;
                    }
                } catch (Throwable ignore) {
                    // нет связи или файла — остаются буквы и значок меню
                } finally {
                    if (!ok) clientAvatarFailedKey = key;
                    clientAvatarFetching = false;
                }
                try {
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        try { if (!a.isFinishing()) showClientAvatar(a); } catch (Throwable ignore) {}
                    }});
                } catch (Throwable ignore) {}
            }}).start();
        } catch (Throwable ignore) {
            // фото — только внешний вид: кабинет и меню работают и без него
        }
    }

    static void rememberClientAvatar(String key, Bitmap b) {
        if (key == null || key.isEmpty() || b == null) return;
        clientAvatarBitmap = b;
        clientAvatarBitmapKey = key;
        clientAvatarFailedKey = "";
    }

    static void putClientAvatar(Activity a, Bitmap b) {
        try {
            Object img = get(a, "avatarImg");
            Object txt = get(a, "avatarTxt");
            if (img instanceof ImageView) {
                ImageView iv = (ImageView) img;
                iv.setImageBitmap(b);
                iv.setPadding(0, 0, 0, 0);
                iv.clearColorFilter();
                iv.setVisibility(View.VISIBLE);
                if (txt instanceof View) ((View) txt).setVisibility(View.GONE);
                clientHeadPhotoShown = true;
            }
        } catch (Throwable ignore) {}
        try {
            ViewGroup box = navBox(a);
            if (box == null) return;
            View av = box.findViewWithTag(AV_TAG);
            if (!(av instanceof ImageView)) {
                if (av != null) box.removeView(av);
                av = newCircle(a);
                av.setTag(AV_TAG);
                int s = px(40, a.getResources().getDisplayMetrics().density);
                box.addView(av, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            }
            ((ImageView) av).setImageBitmap(b);
            ((ImageView) av).clearColorFilter();
            int orange = navColor(a, "orange", 0xffff9800);
            styleAvatarOutline(a, navSelected((String) get(a, "curTab"), "profile"), orange);
        } catch (Throwable ignore) {}
    }

    /** Буквы вместо фото в строке «Клиент» (так, как рисует штатный renderProfile). */
    static void restoreClientHead(Activity a) {
        try {
            Object img = get(a, "avatarImg");
            Object txt = get(a, "avatarTxt");
            if (img instanceof ImageView) {
                ((ImageView) img).setImageDrawable(null);
                ((ImageView) img).setVisibility(View.GONE);
            }
            if (txt instanceof View) ((View) txt).setVisibility(View.VISIBLE);
        } catch (Throwable ignore) {}
        clientHeadPhotoShown = false;
    }

    static void removeClientNavAvatar(Activity a) {
        try {
            ViewGroup box = navBox(a);
            View av = box == null ? null : box.findViewWithTag(AV_TAG);
            if (av == null) return;
            box.removeView(av);
            int orange = navColor(a, "orange", 0xffff9800);
            styleAvatarOutline(a, navSelected((String) get(a, "curTab"), "profile"), orange);
        } catch (Throwable ignore) {}
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
        try { chatPoll(a, "chat".equals(t)); } catch (Throwable ignore) {}
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
                    clientProfileHead = null;
                    clientProfile = new JSONObject(); clientProfileLoaded = false; clientProfileEmail = ""; clientProfileUid = ""; clientAvatarPath = ""; clientCoords = null; clientCoordsLoading = false; activeDetail = null; synchronized (nativeDistanceCache) { nativeDistanceCache.clear(); } synchronized (nativeGeoCache) { nativeGeoCache.clear(); } allNativeTools = null; nativeFilterActive = false; nativeFilterQuery = nativeFilterCat = nativeFilterSub = nativeFilterCity = nativeFilterFrom = nativeFilterTo = nativeFilterConds = ""; nativeFilterDelivery = false;
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

    /** Совместимость: прежняя точка входа 10.34–10.38. В MainActivity больше не вызывается
     * (hook onActivityResult отключён с 10.39); результат выбора фото принимает AvatarPicker. */
    public static void onActivityResult(final Activity a, final int request, final int result, final Intent data) {
        try { onAvatarPicked(request, result, data); } catch (Throwable ignore) {}
    }

    static final int AVATAR_MAX = 640;   // как на сайте: «JPG или PNG, до 640 px»
    static final int AVATAR_DP = 120;    // 10.98: кружок «Настроек профиля» крупнее (было 96)
    static final String NEED_NAME = "need_name";

    /** 10.98: фото, выбранное в строке «Кабинета», — путь в записи клиента; остальные поля отправляются такими,
     * как сохранены (та же функция, что у «Сохранить»). "" — готово; NEED_NAME — в записи нет имени (его требует база). */
    static String saveAvatarPath(Activity a, String path) {
        JSONObject p = clientProfile == null ? new JSONObject() : clientProfile;
        if (p.optString("given_name", "").trim().isEmpty()) return NEED_NAME;
        try {
            JSONObject pp = p.optJSONObject("passport_data");
            JSONObject body = new JSONObject();
            body.put("p_surname", p.optString("surname", ""));
            body.put("p_given_name", p.optString("given_name", ""));
            body.put("p_patronymic", p.optString("patronymic", ""));
            body.put("p_phone", p.optString("phone", ""));
            body.put("p_city", p.optString("city", ""));
            body.put("p_address", p.optString("address", ""));
            body.put("p_passport_data", pp != null ? pp : new JSONObject());
            body.put("p_avatar_path", path);
            String[] r = apiCall(a, "/rest/v1/rpc/save_my_customer_profile", "POST", body.toString(), true);
            if (r == null) return "нет ответа сервера";
            if (!r[0].startsWith("2")) return serverMessage(r[0], r.length > 1 ? r[1] : "");
            JSONObject saved = rpcFirst(r[1]);
            if (saved.length() > 0) {
                clientProfile = saved;
                clientAvatarPath = saved.optString("avatar_path", path);
            }
            return "";
        } catch (Throwable e) {
            String m = brief(e.getMessage());
            return m.isEmpty() ? "нет связи с сервером" : m;
        }
    }

    /** Фото из галереи → JPEG не больше 640 px по большей стороне, с учётом поворота камеры. */
    static byte[] prepareAvatar(byte[] raw) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("файл не похож на изображение");
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = avatarSample(o.outWidth, o.outHeight, AVATAR_MAX);
        Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
        if (src == null) throw new Exception("изображение не читается");
        int deg = exifDegrees(raw);
        int w = src.getWidth(), h = src.getHeight();
        float s = Math.min(1f, AVATAR_MAX / (float) Math.max(w, h));
        int sw = Math.max(1, Math.round(w * s)), sh = Math.max(1, Math.round(h * s));
        boolean swap = deg == 90 || deg == 270;
        int ow = swap ? sh : sw, oh = swap ? sw : sh;
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);   // прозрачный PNG → белый фон, а не чёрный
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate(-sw / 2f, -sh / 2f);
        m.postRotate(deg);
        m.postTranslate(ow / 2f, oh / 2f);
        c.drawBitmap(src, m, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 85, bytes);
        src.recycle();
        out.recycle();
        return bytes.toByteArray();
    }

    /** Поворот из EXIF (камера часто пишет фото «боком»). Android 7+; на старых — без поворота. */
    static int exifDegrees(byte[] raw) {
        if (android.os.Build.VERSION.SDK_INT < 24) return 0;
        try {
            android.media.ExifInterface e = new android.media.ExifInterface(new java.io.ByteArrayInputStream(raw));
            int o = e.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Throwable ignore) {}
        return 0;
    }

    /** Предпросмотр: картинка, уменьшенная примерно до size px. */
    static Bitmap decodeAvatar(byte[] bytes, int size) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = avatarSample(o.outWidth, o.outHeight, Math.max(1, size));
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Фото клиента из приватного bucket customer-avatars (читать может только он сам — политика RLS). */
    static byte[] downloadClientAvatar(Activity a, String path) throws Exception {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        HttpURLConnection c = (HttpURLConnection) new URL(SB_URL + "/storage/v1/object/authenticated/customer-avatars/" + clean).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("apikey", SB_KEY);
        c.setRequestProperty("Authorization", "Bearer " + String.valueOf(get(a, "accessToken")));
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 8 * 1024 * 1024) throw new Exception("файл больше 8 МБ");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
            c.disconnect();
        }
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
        try {
            Object v = get(a, "tools");
            if (!(v instanceof ArrayList)) return;
            allNativeTools = new ArrayList((ArrayList) v);
            synchronized (nativeDistanceCache) { nativeDistanceCache.clear(); }
            if (nativeFilterActive) set(a, "tools", filteredTools(a, allNativeTools));
        } catch (Throwable ignore) {}
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
        try {
            locationAsked=true; clientCoordsLoading=false; loadNativeClientCoords(a);
        } catch (Throwable ignore) {}
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
        try {
            activeDetail = item; activeDetailIsService = service; deliveryEstimateKey = ""; deliveryEstimateLoading = false;
            updateNativeDelivery(a);
            loadNativeClientCoords(a);
        } catch (Throwable ignore) {
            // Расчёт доставки не должен закрывать экран карточки.
        }
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

    // ───────────── 10.69: вход больше не слетает (решение владельца 14.10.2026) ─────────────
    // Причины (разбор 14.10, журнал): 1) штатный код обновляет короткий ключ только перед действиями админа — через час
    // остальные запросы получают 401, экраны пустеют; 2) при запуске restoreSession ждёт 401, а Supabase на истёкший ключ
    // отвечает 403 → logoutLocal (полный выход); 3) нет связи при запуске (Api.check → −1) → тоже logoutLocal.
    // Без новых вставок в smali, только через имеющиеся: onCreated (7), onSession (8), onLogout (10), onTab (11).
    //  а) пока приложение открыто — раз в минуту смотрим срок ключа и за 5 минут до конца обновляем по rt
    //     (в поля приложения и в штатное prokat_session, как штатный ensureFreshToken);
    //  б) копия штатного хранилища входа (iva_auth_bak), пока вход есть. Штатный logoutLocal зовут только кнопка
    //     «Выйти» (главный поток) и проверка при запуске (свой поток). Из своего потока — это не выход, а сбой
    //     проверки: обновляем ключ по rt из копии (мы и так в фоне), возвращаем хранилище и поля и открываем
    //     приложение штатным enterApp — как после удачной проверки. Нет связи / сервер занят — возвращаем вход как
    //     был (ключ обновится позже). Сервер ответил, что rt больше не действует (400) — выход настоящий.
    //  в) «Выйти» — копия стирается, выход как раньше.
    static final String AUTH_BAK = "iva_auth_bak", AUTH_SESSION = "prokat_session";
    static final long AUTH_TICK_MS = 60L * 1000, AUTH_AHEAD_MS = 5L * 60 * 1000, AUTH_FIRST_MS = 5L * 1000;   // 10.117: было 20 с
    static final Object AUTH_LOCK = new Object();
    static final int AUTH_NONE = 0, AUTH_KEEP = 1, AUTH_FRESH = 2;
    static volatile String authBakAt = "";
    static Handler authH;
    static Runnable authRun;

    /** Что делать после штатного logoutLocal: AUTH_NONE — выход настоящий, AUTH_KEEP — вернуть как был, AUTH_FRESH — с новой парой. */
    static int authDecision(boolean mainThread, boolean hasBackup, String refreshCode) {
        if (mainThread || !hasBackup) return AUTH_NONE;                       // «Выйти» или копии нет
        String c = refreshCode == null ? "" : refreshCode.trim();
        if (c.isEmpty() || "-1".equals(c)) return AUTH_KEEP;                  // нет связи
        if (c.startsWith("2")) return AUTH_FRESH;
        if (c.startsWith("5") || "408".equals(c) || "429".equals(c)) return AUTH_KEEP;   // сервер занят / не ответил
        return AUTH_NONE;                                                     // 400/401/403: rt больше не действует
    }

    /** Пора ли обновлять ключ: до конца меньше 5 минут (или уже истёк). Срок не разобрали — не трогаем. */
    static boolean authNeed(long expSec, long nowMs) { return expSec > 0 && expSec * 1000L - nowMs < AUTH_AHEAD_MS; }

    static boolean authEmpty(Object v) { return !(v instanceof String) || ((String) v).trim().isEmpty() || "null".equals(((String) v).trim()); }

    /** Копия штатного хранилища входа — только пока вход есть (оба ключа). Пишем, лишь когда ключ сменился. */
    static void authBackup(Context c) {
        try {
            SharedPreferences s = c.getSharedPreferences(AUTH_SESSION, Context.MODE_PRIVATE);
            Map<String, ?> all = s.getAll();
            Object at = all.get("at"), rt = all.get("rt");
            if (authEmpty(at) || authEmpty(rt) || authEmpty(all.get("email"))) return;
            if (at.equals(authBakAt)) return;
            SharedPreferences.Editor e = c.getSharedPreferences(AUTH_BAK, Context.MODE_PRIVATE).edit().clear();
            authPutAll(e, all);
            e.commit();
            authBakAt = (String) at;
        } catch (Throwable ignore) {}
    }

    @SuppressWarnings("unchecked")
    static void authPutAll(SharedPreferences.Editor e, Map<String, ?> all) {
        for (Map.Entry<String, ?> en : all.entrySet()) {
            Object v = en.getValue();
            if (v instanceof String) e.putString(en.getKey(), (String) v);
            else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
            else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
            else if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
            else if (v instanceof Float) e.putFloat(en.getKey(), (Float) v);
            else if (v instanceof java.util.Set) e.putStringSet(en.getKey(), (java.util.Set<String>) v);
        }
    }

    static void authForget(Context c) {
        try { c.getSharedPreferences(AUTH_BAK, Context.MODE_PRIVATE).edit().clear().commit(); } catch (Throwable ignore) {}
        authBakAt = "";
    }

    // ───────────── 10.119: в профиле — почта другого аккаунта (сообщение владельца 18.10, журнал) ─────────────
    // Штатный saveSession берёт почту из поля ввода, только если поле userEmail пустое, а имя и телефон — прежние.
    // Пустыми их делает лишь кнопка «Выйти». После выхода по сбою проверки входа (rt больше не действует) в полях
    // оставалась почта прежнего аккаунта — и вход в ДРУГОЙ аккаунт сохранялся с ней: ключ новый, а в профиле, в
    // хранилище и во всех проверках по почте — прежний аккаунт. Теперь: 1) такой выход очищает почту, имя и телефон
    // (как «Выйти»); 2) правда — почта в самом ключе (JWT): после входа, при запуске, при восстановлении входа,
    // раз в минуту и в фоновой проверке сверяем; не совпала — ставим почту из ключа, чужие имя и телефон стираем.

    /** Почта в поле/хранилище не та, что в ключе (почту ключа разобрали). */
    static boolean authEmailWrong(String cur, String jwtEmail) {
        if (jwtEmail == null || jwtEmail.trim().isEmpty()) return false;
        return cur == null || !jwtEmail.trim().equalsIgnoreCase(cur.trim());
    }

    /** Штатное хранилище входа: почта — из его ключа. true — исправили. */
    static boolean authPrefsEmailFix(SharedPreferences s) {
        String je = IvaNotifyPatch.jwtEmail(s.getString("at", ""));
        if (!authEmailWrong(s.getString("email", null), je)) return false;
        s.edit().putString("email", je).putString("name", "").putString(AUTH_PH, "").commit();
        authBakAt = "";                                                       // копию входа переписать
        return true;
    }

    /** Поля приложения и хранилище: почта — из ключа; чужие имя и телефон — стереть. true — что-то исправили. */
    static boolean authEmailFix(Activity a) {
        boolean fixed = false;
        try {
            Object at = get(a, "accessToken");
            if (authEmpty(at)) return false;
            String je = IvaNotifyPatch.jwtEmail((String) at);
            Object cur = get(a, "userEmail");
            if (authEmailWrong(cur instanceof String ? (String) cur : null, je)) {
                set(a, "userEmail", je);
                set(a, "userName", "");
                set(a, "userPhone", "");
                authBakAt = "";
                fixed = true;
            }
            if (authPrefsEmailFix(a.getSharedPreferences(AUTH_SESSION, Context.MODE_PRIVATE))) fixed = true;
        } catch (Throwable ignore) {}
        return fixed;
    }

    /** Выход по сбою проверки входа — как «Выйти»: в полях не остаётся почты, имени и телефона прежнего аккаунта. */
    static void authClearUser(Activity a) {
        set(a, "userEmail", null);
        set(a, "userName", "");
        set(a, "userPhone", "");
    }

    /** POST refresh_token → {код, access_token, refresh_token}; нет связи — {"-1", null, null}. */
    static String[] authRefresh(String rt) {
        try {
            String[] r = IvaNotifyPatch.nativeCall(SB_URL + "/auth/v1/token?grant_type=refresh_token", "POST",
                "{\"refresh_token\":" + IvaNotifyPatch.jsonStr(rt) + "}", false, null);
            if (r == null || r.length < 2 || r[0] == null) return new String[]{"-1", null, null};
            if (!r[0].startsWith("2")) return new String[]{r[0], null, null};
            JSONObject j = new JSONObject(r[1]);
            String at = j.optString("access_token", ""), nrt = j.optString("refresh_token", "");
            if (authEmpty(at) || authEmpty(nrt)) return new String[]{"500", null, null};   // странный ответ — как «сервер занят»
            return new String[]{r[0], at, nrt};
        } catch (Throwable e) { return new String[]{"-1", null, null}; }
    }

    /** Из IvaPatch.onLogout (конец штатного logoutLocal). true — вход возвращён, приложение откроется само. */
    static boolean authRecover(final Activity a) {
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        if (main) { authForget(a); return false; }                            // «Выйти» — выход как раньше
        Map<String, ?> bak;
        try { bak = a.getSharedPreferences(AUTH_BAK, Context.MODE_PRIVATE).getAll(); } catch (Throwable e) { return false; }
        Object bat = bak.get("at"), brt = bak.get("rt");
        boolean has = !authEmpty(bat) && !authEmpty(brt) && !authEmpty(bak.get("email"));
        String[] r = new String[]{"", null, null};
        int d;
        synchronized (AUTH_LOCK) {
            if (has) r = authRefresh((String) brt);
            d = authDecision(false, has, r[0]);
            if (d == AUTH_NONE) { if (has) authForget(a); authClearUser(a); return false; }   // 10.119
            String at = d == AUTH_FRESH ? r[1] : (String) bat, rt = d == AUTH_FRESH ? r[2] : (String) brt;
            SharedPreferences.Editor e = a.getSharedPreferences(AUTH_SESSION, Context.MODE_PRIVATE).edit();
            authPutAll(e, bak);
            e.putString("at", at).putString("rt", rt);
            e.commit();
            set(a, "accessToken", at);
            set(a, "refreshToken", rt);
            set(a, "userEmail", bak.get("email"));
            Object nm = bak.get("name"), ph = bak.get(AUTH_PH);
            set(a, "userName", nm instanceof String ? nm : "");
            set(a, "userPhone", ph instanceof String ? ph : "");
            authBakAt = "";
            authEmailFix(a);                                                  // 10.119: почта — из ключа, не из копии
            authBackup(a);
        }
        // Штатный код после logoutLocal покажет экран входа (ui → Lambda14 → showScreen). Ждём, пока он покажется
        // (экран входа в разметке скрыт, при запуске виден splash), и только потом открываем приложение — иначе экран
        // входа лёг бы поверх. Не дождались за 3 с — открываем всё равно.
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            int tries = 0;
            @Override public void run() {
                int st = authEnterStep(authVisible(a, "scrLogin"), authVisible(a, "scrMain"), tries++);
                if (st == AUTH_WAIT) { new Handler(Looper.getMainLooper()).postDelayed(this, AUTH_WAIT_MS); return; }
                if (st == AUTH_ENTER) try {
                    Method m = a.getClass().getDeclaredMethod("enterApp");
                    m.setAccessible(true);
                    m.invoke(a);
                } catch (Throwable ignore) {}
                try { IvaPatch.reloadProfile(a); } catch (Throwable ignore) {}
                try { authTick(a); } catch (Throwable ignore) {}   // ключ старый (не было связи) — обновить сразу
            }
        });
        return true;
    }
    static final String AUTH_PH = "ph" + "one";   // ключ телефона в штатном хранилище
    static final int AUTH_WAIT = 0, AUTH_ENTER = 1, AUTH_SKIP = 2, AUTH_WAIT_TRIES = 60;
    static final long AUTH_WAIT_MS = 50;

    /** Шаг ожидания после восстановления: экран входа показан — открыть; приложение уже открыто — не трогать; 3 с — открыть. */
    static int authEnterStep(boolean loginVisible, boolean mainVisible, int tries) {
        if (mainVisible) return AUTH_SKIP;
        if (loginVisible || tries >= AUTH_WAIT_TRIES) return AUTH_ENTER;
        return AUTH_WAIT;
    }

    static boolean authVisible(Activity a, String field) {
        Object v = get(a, field);
        return v instanceof View && ((View) v).getVisibility() == View.VISIBLE;
    }

    /** Из IvaPatch.onCreated: обновление ключа заранее, пока приложение открыто. */
    static void authStart(final Activity a) {
        if (authH == null) authH = new Handler(Looper.getMainLooper());
        if (authRun != null) authH.removeCallbacks(authRun);
        authRun = new Runnable() {
            @Override public void run() {
                if (a.isFinishing()) return;
                try { authTick(a); } catch (Throwable ignore) {}
                authH.postDelayed(this, AUTH_TICK_MS);
            }
        };
        authH.postDelayed(authRun, AUTH_FIRST_MS);
    }

    static void authTick(final Activity a) {
        Object fat = get(a, "accessToken");
        if (authEmpty(fat)) return;                                          // не вошли — нечего обновлять
        SharedPreferences s = a.getSharedPreferences(AUTH_SESSION, Context.MODE_PRIVATE);
        String pat = s.getString("at", ""), prt = s.getString("rt", "");
        // фоновая проверка уведомлений могла обновить пару, пока окно было свёрнуто, — берём её
        if (!authEmpty(pat) && !authEmpty(prt) && !pat.equals(fat)
            && IvaNotifyPatch.jwtExp(pat) > IvaNotifyPatch.jwtExp((String) fat)) {
            set(a, "accessToken", pat);
            set(a, "refreshToken", prt);
            fat = pat;
        }
        if (authEmailFix(a)) authHealProfile(a);                            // 10.119: почта другого аккаунта — исправить
        authBackup(a);
        if (!authNeed(IvaNotifyPatch.jwtExp((String) fat), System.currentTimeMillis())) return;
        final String used = (String) fat;
        new Thread(new Runnable() {
            @Override public void run() { try { authRenew(a, used); } catch (Throwable ignore) {} }
        }).start();
    }

    // ───────────── 10.117: кабинет без «0 ₽ / −… / +…» и без пустого профиля (разбор 17.10, журнал) ─────────────
    // Ключ живёт час. Пока телефон спал, минутная проверка стоит (Handler считает только время без сна), и первые
    // запросы после пробуждения идут с истёкшим ключом: штатные карточки кабинета получают 401 и показывают «0 ₽»,
    // пустые данные, клиентский вид у админа. Поэтому: ключ проверяется и при каждой смене раздела (IvaNotifyPatch),
    // первая проверка — через 5 с после запуска (было 20 с), наши запросы кабинета сначала берут свежий ключ
    // (authFresh), а если обновили уже истёкший — открытый «Кабинет» / «Заявки» перерисовываются со свежим ключом.

    /** Фоновый поток: ключ истекает (или истёк) — обновить сейчас, до запроса. */
    static void authFresh(Activity a) {
        Object t = get(a, "accessToken");
        if (authEmpty(t) || !authNeed(IvaNotifyPatch.jwtExp((String) t), System.currentTimeMillis())) return;
        authRenew(a, (String) t);
    }

    /** Истёк ли ключ, с которым ходили (запас 5 с): тогда штатные экраны, загруженные с ним, пустые. */
    static boolean authDead(long expSec, long nowMs) { return expSec > 0 && expSec * 1000L <= nowMs + 5000L; }

    /** Обновить пару по rt (один за раз, как штатный ensureFreshToken). true — обновили. */
    static boolean authRenew(final Activity a, String used) {
        synchronized (AUTH_LOCK) {
            try {
                if (!used.equals(get(a, "accessToken"))) return false;        // уже обновили
                Object rt = get(a, "refreshToken");
                if (authEmpty(rt)) return false;
                String[] r = authRefresh((String) rt);
                if (!r[0].startsWith("2")) return false;                     // не вышло — попробуем через минуту; не выходим
                if (!rt.equals(get(a, "refreshToken"))) return false;        // пока ходили — вышли
                set(a, "accessToken", r[1]);
                set(a, "refreshToken", r[2]);
                a.getSharedPreferences(AUTH_SESSION, Context.MODE_PRIVATE).edit()
                    .putString("at", r[1]).putString("rt", r[2]).commit();
                authBackup(a);
            } catch (Throwable e) { return false; }
        }
        if (authDead(IvaNotifyPatch.jwtExp(used), System.currentTimeMillis())) authHeal(a);
        return true;
    }

    /** Ключ был уже истёкшим — открытый «Кабинет» или «Заявки» загружались с ним: открыть раздел заново. */
    static void authHeal(final Activity a) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (a.isFinishing() || !IvaNotifyPatch.loggedIn(a)) return;
                    Object t = get(a, "curTab");
                    if (authHealTab(t instanceof String ? (String) t : "")) IvaNotifyPatch.tab(a, (String) t);
                } catch (Throwable ignore) {}
            }
        });
    }

    /** 10.119: почту исправили — открытый «Кабинет» / «Заявки» показать заново (с верной почтой и ролью). */
    static void authHealProfile(final Activity a) {
        authHeal(a);
    }

    static boolean authHealTab(String tab) { return "profile".equals(tab) || "orders".equals(tab); }
}

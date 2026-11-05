package ru.prokatnvsk.app;

// ───────────── 10.136: «Рекламный кабинет» (просьба владельца 05.11.2026) ─────────────
//
//  Только у создателя (eclips.ru@mail.ru): в «Панели владельца» под «Все администраторы →» — кнопка
//  «Рекламный кабинет →» в том же стиле (оранжевые буквы со стрелкой). Отдельная страница, сверху три раздела —
//  «Акции», «Инфа», «Маркет» (решение владельца 05.11: просто папки для порядка, конструктор везде одинаковый).
//  В каждом: «Добавить рекламу», ниже список реклам карточками (как «Мои инструменты» у админов); нажатие —
//  «Превью», «Редактировать», «Показать» / «Скрыть», «Сделать копию», «Удалить».
//
//  Конструктор (добавление и правка — одна форма, подписи над полями). Что взято у Firebase In-App Messaging и
//  OneSignal (документация, 05.11.2026): четыре вида показа — баннер на странице, окно по центру, полоска сверху,
//  во весь экран; кнопка действия (ссылка, раздел приложения, позвонить, закрыть); кому (гости — ещё не вошли,
//  клиенты, владельцы проката, сотрудники; «только недавно установившим» — как «First open» у Firebase); где
//  (экраны приложения); когда (начало и конец, часы, дни недели); как часто одному человеку (по умолчанию —
//  один раз, как у обоих сервисов); пробный показ «только мне»; счётчики «показали / нажали / закрыли» — только
//  числа (решение владельца 05.11). Маркировка («Реклама», рекламодатель, ИНН, erid) — по желанию.
//
//  Показ — «только когда я её включу и сразу»: приложение держит соединение Supabase Realtime (как стрелки 10.132)
//  на строку iva_ads_ping — включили / выключили / поменяли рекламу — приложение сразу перечитывает список.
//  Баннер на странице появляется сразу. Окна, полоска и «во весь экран» — при следующем переходе (открыли
//  приложение, перешли в другой раздел), а не посреди набора текста (решение владельца 05.11; правило Google Play
//  «Disruptive ads»: полноэкранная реклама не должна выскакивать неожиданно; крестик виден сразу).
//  Не больше одного окна за переход и не чаще раза в 30 секунд (как «Duration since last in-app» у OneSignal).
//
//  Нет SQL 20261124_ads.sql — ничего не показывается, кабинет пишет, какой SQL нужен. Новых вставок в smali нет:
//  вход — IvaPatch.onCreated (свой таймер раз в секунду) и «Панель владельца» (IvaAdminsPatch.cleanPanel).
//  Сеть — только в своих потоках. Персональные данные не собираются: счётчики — только числа.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;

public final class IvaAdsPatch {
    private IvaAdsPatch() {}

    // ════════════════════════════════ справочники ════════════════════════════════

    static final String LIVE_RPC = "/rest/v1/rpc/iva_ads_live", HIT_RPC = "/rest/v1/rpc/iva_ad_hit",
        LIST_RPC = "/rest/v1/rpc/iva_ads_list", SAVE_RPC = "/rest/v1/rpc/iva_ad_save",
        ACTIVE_RPC = "/rest/v1/rpc/iva_ad_set_active", DEL_RPC = "/rest/v1/rpc/iva_ad_delete";
    static final String BUCKET = "iva-ads";
    static final String PING_TABLE = "iva_ads_ping", TOPIC = "realtime:iva_ads", JOIN_REF = "1";
    static final String TITLE = "Рекламный кабинет", BTN_TEXT = "Рекламный кабинет →", BTN_TAG = "iva_ads_btn",
        NATIVE_ALL = "Все администраторы →", BANNER_TAG = "iva_ad_banner", TOP_TAG = "iva_ad_top";
    static final String CAB_SUB = "Видите только вы. Реклама показывается, только когда вы её включите, — сразу и только тем, "
        + "кому вы её настроили.";
    static final String NO_DB = "Рекламный кабинет заработает после обновления базы: выполните в Supabase файл 20261124_ads.sql.";
    static final String ADD = "+ Добавить рекламу";

    static final String[] SECTIONS = {"promo", "info", "market"};
    static final String[] SECTION_T = {"Акции", "Инфа", "Маркет"};
    static final String[] FORMATS = {"banner", "modal", "top", "full"};
    static final String[] FORMAT_T = {"Баннер на странице", "Окно по центру", "Полоска сверху", "Во весь экран"};
    static final String[] FORMAT_SUB = {
        "Карточка вверху страницы — не мешает, появляется сразу. Картинка лучше 2:1 (например 1200×600).",
        "Окно с крестиком при переходе в раздел. Картинка 1:1 или 4:5 (например 1080×1350).",
        "Небольшая полоска сверху, сама исчезает через 8 секунд. Картинка — маленький квадрат.",
        "На весь экран при переходе в раздел, крестик виден сразу. Картинка 9:16 (например 1080×1920)."};
    static final String[] AUD = {"guest", "client", "admin", "staff"};
    static final String[] AUD_T = {"Новые (ещё не вошли)", "Клиенты", "Владельцы проката", "Сотрудники"};
    static final String[] PLACES = {"login", "home", "detail", "order", "success", "chat", "orders", "profile", "seller"};
    static final String[] PLACE_T = {"Вход и регистрация", "Главная", "Карточка инструмента", "Оформление заявки",
        "Заявка отправлена", "Чаты", "Заявки", "Кабинет", "Страница проката"};
    static final String[] ACTIONS = {"close", "url", "tab", "phone"};
    static final String[] ACTION_T = {"Закрыть", "Открыть ссылку", "Раздел приложения", "Позвонить"};
    static final String[] TABS = {"home", "orders", "chat", "profile"};
    static final String[] TAB_T = {"Главная", "Заявки", "Чаты", "Кабинет"};
    static final String[] DAY_T = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    static final int[] NEW_DAYS = {0, 1, 3, 7, 30};
    static final String[] NEW_DAYS_T = {"Всем", "1 день", "3 дня", "7 дней", "30 дней"};
    static final String[] FREQ_T = {"Один раз", "Раз в день", "Каждый раз", "Свой вариант"};
    static final String[] ACTIONS_MENU_ON = {"Превью", "Редактировать", "Скрыть", "Сделать копию", "Удалить"};
    static final String[] ACTIONS_MENU_OFF = {"Превью", "Редактировать", "Показать", "Сделать копию", "Удалить"};

    static final long POPUP_GAP_MS = 30000L, TOP_HIDE_MS = 8000L, POLL_MS = 60000L, TICK_MS = 1000L, SESSION_GAP_MS = 30000L;
    static final long HOUR_MS = 3600000L, DAY_MS = 24L * HOUR_MS;

    static int idx(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    static String titleOf(String[] keys, String[] titles, String v) {
        int i = idx(keys, v);
        return i < 0 ? v : titles[i];
    }

    // ════════════════════════════════ реклама ════════════════════════════════

    static final class Ad {
        String id = "", section = "promo", name = "", format = "banner", img = "", headline = "", body = "",
            btnText = "", btnAction = "close", btnValue = "", btnPlace = "below", advertiser = "", inn = "", erid = "",
            updated = "";
        boolean active, testOnly, hideOnClose = true, adLabel;
        int imgW, imgH, newDays, hourFrom = 0, hourTo = 24, days = 127, maxShows = 1, gapHours, priority;
        long startAt, endAt, shows, clicks, closes;
        Set<String> audience = new LinkedHashSet<String>(Arrays.asList(AUD));
        Set<String> places = new LinkedHashSet<String>(Collections.singletonList("home"));

        String sig() { return id + "|" + updated; }
        boolean popup() { return popupFormat(format); }

        Ad copy() {
            Ad c = new Ad();
            c.id = id; c.section = section; c.name = name; c.format = format; c.img = img; c.headline = headline; c.body = body;
            c.btnText = btnText; c.btnAction = btnAction; c.btnValue = btnValue; c.btnPlace = btnPlace;
            c.advertiser = advertiser; c.inn = inn; c.erid = erid; c.updated = updated;
            c.active = active; c.testOnly = testOnly; c.hideOnClose = hideOnClose; c.adLabel = adLabel;
            c.imgW = imgW; c.imgH = imgH; c.newDays = newDays; c.hourFrom = hourFrom; c.hourTo = hourTo; c.days = days;
            c.maxShows = maxShows; c.gapHours = gapHours; c.priority = priority; c.startAt = startAt; c.endAt = endAt;
            c.shows = shows; c.clicks = clicks; c.closes = closes;
            c.audience = new LinkedHashSet<String>(audience); c.places = new LinkedHashSet<String>(places);
            return c;
        }
    }

    static boolean popupFormat(String f) { return "modal".equals(f) || "top".equals(f) || "full".equals(f); }

    // ───────────── правила показа (чистая логика — IvaPatchTest) ─────────────

    /** Часы показа [from, to) по времени телефона; from > to — через полночь (22–6); 0–24 — весь день. */
    static boolean inHours(int from, int to, int hour) {
        if (from <= 0 && to >= 24) return true;
        if (from == to) return true;
        if (from < to) return hour >= from && hour < to;
        return hour >= from || hour < to;
    }

    /** Дни недели — маска: Пн = 1, Вт = 2, … Вс = 64; dow — 1 (Пн) … 7 (Вс). */
    static boolean dayOk(int mask, int dow) {
        if (dow < 1 || dow > 7) return true;
        return (mask & (1 << (dow - 1))) != 0;
    }

    /** Calendar.DAY_OF_WEEK (Вс = 1 … Сб = 7) → 1 (Пн) … 7 (Вс). */
    static int dowOf(int calDow) { return calDow == Calendar.SUNDAY ? 7 : calDow - 1; }

    static boolean dateOk(long start, long end, long now) {
        return (start <= 0 || now >= start) && (end <= 0 || now < end);
    }

    /** Как часто: не больше maxShows раз (0 — без ограничения) и не чаще раза в gapHours часов. */
    static boolean freqOk(int maxShows, int gapHours, int shown, long lastAt, long now) {
        if (maxShows > 0 && shown >= maxShows) return false;
        return gapHours <= 0 || lastAt <= 0 || now - lastAt >= gapHours * HOUR_MS;
    }

    /** Кому: роль в списке; «только недавно установившим» — не больше newDays суток с установки. */
    static boolean audOk(Set<String> aud, String role, int newDays, long installedAt, long now) {
        if (aud == null || !aud.contains(role)) return false;
        return newDays <= 0 || installedAt <= 0 || now - installedAt <= newDays * DAY_MS;
    }

    static String roleOf(boolean logged, boolean admin, boolean staff) {
        if (!logged) return "guest";
        if (admin) return "admin";
        return staff ? "staff" : "client";
    }

    /** Где человек: экран входа, «Заявка отправлена» или штатный раздел (curTab). "" — не показываем. */
    static String placeOf(boolean login, boolean success, boolean main, String tab, boolean hub, boolean chatList) {
        if (login) return "login";
        if (success) return "success";
        if (!main || hub) return "";
        String t = tab == null ? "" : tab;
        if ("home".equals(t)) return "home";
        if ("detail".equals(t)) return "detail";
        if ("order".equals(t)) return "order";
        if ("orders".equals(t)) return "orders";
        if ("chat".equals(t)) return chatList ? "chat" : "";
        if ("profile".equals(t) || "admhub".equals(t) || "clienthub".equals(t)) return "profile";
        if ("seller".equals(t)) return "seller";
        return "";
    }

    /** Можно ли показать рекламу здесь и сейчас (без учёта частоты). Только включённая; пробная — только создателю, без «Кому». */
    static boolean fits(Ad ad, String place, String role, boolean chief, long installedAt, long now, int hour, int dow) {
        if (ad == null || place == null || place.isEmpty() || !ad.places.contains(place)) return false;
        if (!ad.active) return false;
        if (ad.testOnly) { if (!chief) return false; }
        else if (!audOk(ad.audience, role, ad.newDays, installedAt, now)) return false;
        return dateOk(ad.startAt, ad.endAt, now) && inHours(ad.hourFrom, ad.hourTo, hour) && dayOk(ad.days, dow);
    }

    /** Порядок: сначала «важные», потом свежие (по времени правки). */
    static List<Ad> sorted(List<Ad> in) {
        List<Ad> out = new ArrayList<Ad>(in);
        Collections.sort(out, new java.util.Comparator<Ad>() {
            @Override public int compare(Ad x, Ad y) {
                if (x.priority != y.priority) return y.priority - x.priority;
                return y.updated.compareTo(x.updated);
            }
        });
        return out;
    }

    // ───────────── время (ISO Supabase ↔ мс) ─────────────

    /** «2026-11-05T10:00:00+00:00», «…T10:00:00.123456+03:00», «…Z» → мс. Пусто / не разобрать — 0. */
    static long isoMs(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.length() < 19 || "null".equals(t)) return 0;
        try {
            int y = Integer.parseInt(t.substring(0, 4)), mo = Integer.parseInt(t.substring(5, 7)), d = Integer.parseInt(t.substring(8, 10));
            int h = Integer.parseInt(t.substring(11, 13)), mi = Integer.parseInt(t.substring(14, 16)), se = Integer.parseInt(t.substring(17, 19));
            int i = 19;
            if (i < t.length() && t.charAt(i) == '.') { i++; while (i < t.length() && Character.isDigit(t.charAt(i))) i++; }
            int off = 0;
            if (i < t.length()) {
                char c = t.charAt(i);
                if (c == '+' || c == '-') {
                    String z = t.substring(i + 1).replace(":", "");
                    int oh = Integer.parseInt(z.substring(0, 2)), om = z.length() >= 4 ? Integer.parseInt(z.substring(2, 4)) : 0;
                    off = (oh * 60 + om) * (c == '-' ? -1 : 1);
                }
            }
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            cal.clear();
            cal.set(y, mo - 1, d, h, mi, se);
            return cal.getTimeInMillis() - off * 60000L;
        } catch (Throwable e) { return 0; }
    }

    static String isoOf(long ms) {
        if (ms <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    /** «12.11.2026 10:00» по времени телефона. */
    static String dateText(long ms, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
        f.setTimeZone(tz == null ? TimeZone.getDefault() : tz);
        return f.format(new java.util.Date(ms));
    }

    static String hh(int h) { return (h < 10 ? "0" : "") + h + ":00"; }

    // ───────────── тексты карточки в кабинете ─────────────

    static final int ST_ON = 0, ST_OFF = 1, ST_ENDED = 2, ST_TEST = 3, ST_LATER = 4;

    static int state(Ad ad, long now) {
        if (ad.endAt > 0 && now >= ad.endAt) return ST_ENDED;
        if (!ad.active) return ST_OFF;
        if (ad.testOnly) return ST_TEST;
        if (ad.startAt > 0 && now < ad.startAt) return ST_LATER;
        return ST_ON;
    }

    static String stateText(Ad ad, long now, TimeZone tz) {
        switch (state(ad, now)) {
            case ST_ENDED: return "Закончилась";
            case ST_TEST: return "Пробная — видите только вы";
            case ST_OFF: return "Скрыта";
            case ST_LATER: return "Начнётся " + dateText(ad.startAt, tz);
            default: return "Показывается";
        }
    }

    /** Подпись: вид · где. */
    static String whereText(Ad ad) {
        StringBuilder b = new StringBuilder(titleOf(FORMATS, FORMAT_T, ad.format));
        List<String> ps = new ArrayList<String>();
        for (String p : PLACES) if (ad.places.contains(p)) ps.add(titleOf(PLACES, PLACE_T, p));
        for (int i = 0; i < ps.size(); i++) b.append(i == 0 ? " · " : ", ").append(ps.get(i));
        return b.toString();
    }

    static String statsText(Ad ad) {
        return "Показы " + ad.shows + " · нажали " + ad.clicks + " · закрыли " + ad.closes;
    }

    /** Пометка под рекламой: «Реклама · ООО Ромашка · ИНН 1234567890 · erid: abc». */
    static String labelText(Ad ad) {
        if (!ad.adLabel) return "";
        StringBuilder b = new StringBuilder("Реклама");
        if (!ad.advertiser.trim().isEmpty()) b.append(" · ").append(ad.advertiser.trim());
        if (!ad.inn.trim().isEmpty()) b.append(" · ИНН ").append(ad.inn.trim());
        if (!ad.erid.trim().isEmpty()) b.append(" · erid: ").append(ad.erid.trim());
        return b.toString();
    }

    /** Частота → пресет: 0 «Один раз», 1 «Раз в день», 2 «Каждый раз», 3 «Свой вариант». */
    static int freqPreset(int maxShows, int gapHours) {
        if (maxShows == 1 && gapHours == 0) return 0;
        if (maxShows == 0 && gapHours == 24) return 1;
        if (maxShows == 0 && gapHours == 0) return 2;
        return 3;
    }

    static int[] freqOf(int preset) {
        switch (preset) {
            case 0: return new int[]{1, 0};
            case 1: return new int[]{0, 24};
            case 2: return new int[]{0, 0};
            default: return null;
        }
    }

    /** Ссылка: без схемы — https://; только http(s). "" — не годится. */
    static String normUrl(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || t.contains(" ")) return "";
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")) return t.indexOf('.') > 0 ? t : "";
        if (low.contains("://") || low.startsWith("javascript:") || low.startsWith("intent:")) return "";
        return t.indexOf('.') > 0 ? "https://" + t : "";
    }

    static String phoneDigits(String s) {
        String t = s == null ? "" : s.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c) || (c == '+' && b.length() == 0)) b.append(c);
        }
        String d = b.toString().replace("+", "");
        return d.length() >= 5 && d.length() <= 15 ? b.toString() : "";
    }

    /** Проверка формы: "" — всё верно, иначе — что исправить (первая ошибка). */
    static String formErr(Ad ad) {
        if (ad.name.trim().isEmpty()) return "Укажите название рекламы (его видите только вы)";
        if (ad.img.isEmpty() && ad.headline.trim().isEmpty() && ad.body.trim().isEmpty())
            return "Добавьте картинку, заголовок или текст";
        if (ad.headline.length() > 120) return "Заголовок — не длиннее 120 знаков";
        if (ad.body.length() > 600) return "Текст — не длиннее 600 знаков";
        if (ad.btnText.length() > 40) return "Текст кнопки — не длиннее 40 знаков";
        if (!ad.btnText.trim().isEmpty() || !"close".equals(ad.btnAction)) {
            if ("url".equals(ad.btnAction) && normUrl(ad.btnValue).isEmpty()) return "Укажите ссылку для кнопки, например https://eclipsru.github.io/Arenda-Nvk/";
            if ("tab".equals(ad.btnAction) && idx(TABS, ad.btnValue) < 0) return "Выберите раздел приложения для кнопки";
            if ("phone".equals(ad.btnAction) && phoneDigits(ad.btnValue).isEmpty()) return "Укажите номер телефона для кнопки";
        }
        if (ad.audience.isEmpty()) return "Выберите, кому показывать";
        if (ad.places.isEmpty()) return "Выберите, где показывать";
        if (ad.days == 0) return "Выберите хотя бы один день недели";
        if (ad.startAt > 0 && ad.endAt > 0 && ad.endAt <= ad.startAt) return "Окончание должно быть позже начала";
        if (ad.maxShows < 0 || ad.maxShows > 1000) return "«Не больше, раз» — от 0 до 1000";
        if (ad.gapHours < 0 || ad.gapHours > 8760) return "Перерыв — от 0 до 8760 часов";
        if (!ad.inn.trim().isEmpty() && !ad.inn.trim().matches("[0-9]{10}|[0-9]{12}")) return "ИНН — 10 или 12 цифр";
        if (ad.adLabel && ad.advertiser.trim().isEmpty()) return "Для пометки «Реклама» укажите рекламодателя";
        return "";
    }

    // ───────────── JSON для сохранения (без org.json — IvaPatchTest) ─────────────

    static String q(String s) { return IvaRealtimePatch.q(s); }

    static String arr(Set<String> s, String[] order) {
        StringBuilder b = new StringBuilder("[");
        for (String k : order) if (s.contains(k)) { if (b.length() > 1) b.append(','); b.append(q(k)); }
        return b.append(']').toString();
    }

    static String saveBody(Ad ad) {
        StringBuilder b = new StringBuilder("{\"p\":{");
        if (!ad.id.isEmpty()) b.append("\"id\":").append(q(ad.id)).append(',');
        b.append("\"section\":").append(q(ad.section))
            .append(",\"name\":").append(q(ad.name.trim()))
            .append(",\"active\":").append(ad.active)
            .append(",\"test_only\":").append(ad.testOnly)
            .append(",\"format\":").append(q(ad.format))
            .append(",\"image_url\":").append(q(ad.img))
            .append(",\"img_w\":").append(ad.imgW)
            .append(",\"img_h\":").append(ad.imgH)
            .append(",\"headline\":").append(q(ad.headline.trim()))
            .append(",\"body\":").append(q(ad.body.trim()))
            .append(",\"btn_text\":").append(q(ad.btnText.trim()))
            .append(",\"btn_action\":").append(q(ad.btnAction))
            .append(",\"btn_value\":").append(q("url".equals(ad.btnAction) ? normUrl(ad.btnValue) : ad.btnValue.trim()))
            .append(",\"btn_place\":").append(q(ad.btnPlace))
            .append(",\"audience\":").append(arr(ad.audience, AUD))
            .append(",\"new_days\":").append(ad.newDays)
            .append(",\"places\":").append(arr(ad.places, PLACES))
            .append(",\"start_at\":").append(q(isoOf(ad.startAt)))
            .append(",\"end_at\":").append(q(isoOf(ad.endAt)))
            .append(",\"hour_from\":").append(ad.hourFrom)
            .append(",\"hour_to\":").append(ad.hourTo)
            .append(",\"days\":").append(ad.days)
            .append(",\"max_shows\":").append(ad.maxShows)
            .append(",\"gap_hours\":").append(ad.gapHours)
            .append(",\"hide_on_close\":").append(ad.hideOnClose)
            .append(",\"priority\":").append(ad.priority)
            .append(",\"ad_label\":").append(ad.adLabel)
            .append(",\"advertiser\":").append(q(ad.advertiser.trim()))
            .append(",\"adv_inn\":").append(q(ad.inn.trim()))
            .append(",\"erid\":").append(q(ad.erid.trim()));
        return b.append("}}").toString();
    }

    static String hitBody(String id, String kind) { return "{\"p_id\":" + q(id) + ",\"p_kind\":" + q(kind) + "}"; }

    /** Вход в канал отметки «рекламы поменялись». Гость — без ключа входа (роль anon). */
    static String joinMsg(String token) {
        String t = token == null ? "" : token.trim();
        return "{\"topic\":" + q(TOPIC) + ",\"event\":\"phx_join\",\"payload\":{\"config\":{"
            + "\"broadcast\":{\"ack\":false,\"self\":false},\"presence\":{\"key\":\"\",\"enabled\":false},"
            + "\"postgres_changes\":[{\"event\":\"*\",\"schema\":\"public\",\"table\":" + q(PING_TABLE) + "}],"
            + "\"private\":false}" + (t.isEmpty() ? "" : ",\"access_token\":" + q(t)) + "},\"ref\":" + q(JOIN_REF)
            + ",\"join_ref\":" + q(JOIN_REF) + "}";
    }

    // ───────────── разбор ответа базы ─────────────

    static Set<String> strSet(JSONArray a) {
        Set<String> s = new LinkedHashSet<String>();
        if (a != null) for (int i = 0; i < a.length(); i++) { String v = a.optString(i, "").trim(); if (!v.isEmpty()) s.add(v); }
        return s;
    }

    static String js(JSONObject j, String k) { return j.isNull(k) ? "" : j.optString(k, ""); }

    static Ad parse(JSONObject j) {
        Ad ad = new Ad();
        ad.id = js(j, "id"); ad.section = js(j, "section"); ad.name = js(j, "name"); ad.format = js(j, "format");
        ad.img = js(j, "image_url"); ad.headline = js(j, "headline"); ad.body = js(j, "body");
        ad.btnText = js(j, "btn_text"); ad.btnAction = js(j, "btn_action"); ad.btnValue = js(j, "btn_value"); ad.btnPlace = js(j, "btn_place");
        ad.advertiser = js(j, "advertiser"); ad.inn = js(j, "adv_inn"); ad.erid = js(j, "erid"); ad.updated = js(j, "updated_at");
        ad.active = j.optBoolean("active", true); ad.testOnly = j.optBoolean("test_only", false);
        ad.hideOnClose = j.optBoolean("hide_on_close", true); ad.adLabel = j.optBoolean("ad_label", false);
        ad.imgW = j.optInt("img_w", 0); ad.imgH = j.optInt("img_h", 0); ad.newDays = j.optInt("new_days", 0);
        ad.hourFrom = j.optInt("hour_from", 0); ad.hourTo = j.optInt("hour_to", 24); ad.days = j.optInt("days", 127);
        ad.maxShows = j.optInt("max_shows", 1); ad.gapHours = j.optInt("gap_hours", 0); ad.priority = j.optInt("priority", 0);
        ad.startAt = isoMs(js(j, "start_at")); ad.endAt = isoMs(js(j, "end_at"));
        ad.shows = j.optLong("shows", 0); ad.clicks = j.optLong("clicks", 0); ad.closes = j.optLong("closes", 0);
        ad.audience = strSet(j.optJSONArray("audience")); ad.places = strSet(j.optJSONArray("places"));
        if (idx(SECTIONS, ad.section) < 0) ad.section = "promo";
        if (idx(FORMATS, ad.format) < 0) ad.format = "banner";
        if (idx(ACTIONS, ad.btnAction) < 0) ad.btnAction = "close";
        return ad;
    }

    static List<Ad> parseList(String body) throws Exception {
        String t = body == null ? "" : body.trim();
        List<Ad> out = new ArrayList<Ad>();
        if (t.isEmpty() || "null".equals(t)) return out;
        JSONArray a = new JSONArray(t);
        for (int i = 0; i < a.length(); i++) { JSONObject j = a.optJSONObject(i); if (j != null) out.add(parse(j)); }
        return out;
    }

    /** Нет функции в базе (SQL не выполнен): 404 / PGRST202 / «Could not find the function». */
    static boolean noDb(String code, String body) {
        String b = body == null ? "" : body;
        return "404".equals(code) || b.contains("PGRST202") || b.contains("Could not find the function") || b.contains("42883");
    }

    // ════════════════════════════════ показ рекламы ════════════════════════════════

    static volatile List<Ad> live = new ArrayList<Ad>();
    static volatile boolean liveLoaded, liveNoDb, fetching;
    static volatile long lastFetch;
    static String lastWho = null, lastPlace = "", pendingPlace = "";
    static long lastPopupAt, bgSince, pendingAt, installedAt = -1;
    static final Set<String> visitShown = new HashSet<String>(), visitClosed = new HashSet<String>();
    static final WeakHashMap<View, String> banners = new WeakHashMap<View, String>();
    static WeakReference<Dialog> popupD = new WeakReference<Dialog>(null);
    static WeakReference<Activity> act = new WeakReference<Activity>(null);
    static Handler h;
    static Runnable tickR;

    static Handler h() { if (h == null) h = new Handler(Looper.getMainLooper()); return h; }
    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }
    static TextView text(Activity a, String s, float sp, int c, boolean bold) { return IvaChiefPatch.text(a, s, sp, c, bold); }
    static void toast(Activity a, String s) { try { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {} }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("iva_ads", 0); }
    static boolean chief(Activity a) { try { return IvaReviewPatch.chief(a); } catch (Throwable e) { return false; } }

    /** Из IvaPatch.onCreated: свой таймер раз в секунду (без новых вставок в smali) и первая загрузка. */
    static void onCreated(final Activity a) {
        act = new WeakReference<Activity>(a);
        if (installedAt < 0) {
            try { installedAt = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).firstInstallTime; }
            catch (Throwable e) { installedAt = 0; }
        }
        if (tickR == null) tickR = new Runnable() { @Override public void run() { tick(); } };
        h().removeCallbacks(tickR);
        h().postDelayed(tickR, TICK_MS);
    }

    static void tick() {
        Activity a = act.get();
        if (a == null || a.isFinishing()) return;
        try { step(a); } catch (Throwable ignore) {}
        h().postDelayed(tickR, TICK_MS);
    }

    static String role(Activity a) {
        boolean logged = IvaNotifyPatch.loggedIn(a);
        boolean admin = false, staff = false;
        if (logged) {
            try { admin = IvaNotifyPatch.isAdmin(a); } catch (Throwable ignore) {}
            try { staff = !admin && IvaArrowPatch.staffAny(a); } catch (Throwable ignore) {}
        }
        return roleOf(logged, admin, staff);
    }

    static boolean shown(Object o) { return o instanceof View && ((View) o).isShown(); }

    static String place(Activity a) {
        Object chatPage = IvaClientPatch.get(a, "pageChat");
        boolean hub = false;
        try { hub = IvaClientPatch.hubVisible; } catch (Throwable ignore) {}
        Object tab = IvaClientPatch.get(a, "curTab");
        return placeOf(shown(IvaClientPatch.get(a, "scrLogin")), shown(IvaClientPatch.get(a, "scrSuccess")),
            shown(IvaClientPatch.get(a, "scrMain")), tab instanceof String ? (String) tab : "", hub, shown(chatPage));
    }

    static void step(Activity a) {
        long now = System.currentTimeMillis();
        if (!IvaRealtimePatch.foreground()) { if (bgSince == 0) bgSince = now; return; }
        boolean resumed = bgSince > 0 && now - bgSince >= SESSION_GAP_MS;
        bgSince = 0;
        String who = IvaPickupPatch.me(a) + "|" + IvaNotifyPatch.loggedIn(a);
        if (!who.equals(lastWho)) { lastWho = who; fetch(a); }
        else if (resumed) fetch(a);
        else if (!rtJoined && now - lastFetch >= (liveNoDb ? 10 * POLL_MS : POLL_MS)) fetch(a);
        rtEnsure(a);
        String place = place(a);
        boolean moved = !place.equals(lastPlace);
        if (moved) { visitShown.clear(); visitClosed.clear(); pendingPlace = ""; }
        lastPlace = place;
        syncBanners(a, place, now);
        if ((moved || resumed) && !place.isEmpty()) { pendingPlace = place; pendingAt = now; }
        if (!pendingPlace.isEmpty() && pendingPlace.equals(place)) {
            if (now - pendingAt > 15000L) pendingPlace = "";            // данные / картинка не успели — до следующего перехода
            else popupTry(a, place, now);
        }
    }

    // ───────────── частота (на телефоне) ─────────────

    static int shownCount(Context c, String id) { return prefs(c).getInt("n_" + id, 0); }
    static long shownAt(Context c, String id) { return prefs(c).getLong("t_" + id, 0L); }
    static boolean closedForever(Context c, String id) { return prefs(c).getBoolean("x_" + id, false); }

    static void recordShow(Activity a, Ad ad, long now) {
        if (ad.testOnly) return;                                        // пробная — без счётчиков и частоты
        prefs(a).edit().putInt("n_" + ad.id, shownCount(a, ad.id) + 1).putLong("t_" + ad.id, now).apply();
        hit(a, ad.id, "show");
    }

    static void recordClose(Activity a, Ad ad, boolean preview, String place) {
        if (preview) return;
        if (!ad.testOnly) {
            hit(a, ad.id, "close");
            if (ad.hideOnClose) prefs(a).edit().putBoolean("x_" + ad.id, true).apply();
        }
        if (place != null) visitClosed.add(place + "|" + ad.id);
    }

    static boolean freqOk(Activity a, Ad ad, long now) {
        if (ad.testOnly) return true;
        if (closedForever(a, ad.id)) return false;
        return freqOk(ad.maxShows, ad.gapHours, shownCount(a, ad.id), shownAt(a, ad.id), now);
    }

    static Ad pick(Activity a, String place, long now, boolean popup, String keepSig) {
        String role = role(a);
        boolean ch = chief(a);
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY), dow = dowOf(cal.get(Calendar.DAY_OF_WEEK));
        for (Ad ad : live) {
            if (ad.popup() != popup) continue;
            if (!fits(ad, place, role, ch, installedAt, now, hour, dow)) continue;
            if (visitClosed.contains(place + "|" + ad.id)) continue;
            boolean already = visitShown.contains(place + "|" + ad.id) || ad.sig().equals(keepSig) && visitShown.contains(place + "|" + ad.id);
            if (!already && !freqOk(a, ad, now)) continue;
            return ad;
        }
        return null;
    }

    static boolean liveSig(String sig) {
        for (Ad ad : live) if (ad.sig().equals(sig)) return true;
        return false;
    }

    // ───────────── баннер на странице ─────────────

    static ViewGroup inner(View v, int depth) {
        if (v instanceof ScrollView) {
            View c = ((ScrollView) v).getChildAt(0);
            return c instanceof ViewGroup ? (ViewGroup) c : null;
        }
        if (v instanceof ViewGroup && depth < 2) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                ViewGroup r = inner(c, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    static View visiblePage(Activity a) {
        Object ph = IvaClientPatch.get(a, "pageHome");
        if (!(ph instanceof View) || !(((View) ph).getParent() instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) ((View) ph).getParent();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE) return c;
        }
        return null;
    }

    static ViewGroup container(Activity a, String place) {
        if ("success".equals(place) || "chat".equals(place)) {   // чаты: над списком диалогов IvaChatPatch, не внутри
            Object s = IvaClientPatch.get(a, "success".equals(place) ? "scrSuccess" : "pageChat");
            return s instanceof LinearLayout ? (ViewGroup) s : null;
        }
        View root = "login".equals(place) ? (View) IvaClientPatch.get(a, "scrLogin") : visiblePage(a);
        ViewGroup g = inner(root, 0);
        return g instanceof LinearLayout ? g : null;
    }

    static View bannerIn(ViewGroup box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (BANNER_TAG.equals(c.getTag())) return c;
        }
        return null;
    }

    static void drop(View v) {
        banners.remove(v);
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    static void syncBanners(final Activity a, final String place, long now) {
        for (View v : new ArrayList<View>(banners.keySet())) {
            String sig = banners.get(v);
            if (sig == null || !liveSig(sig)) drop(v);                  // выключили / поменяли / удалили — сразу убрать
        }
        if (place.isEmpty() || live.isEmpty()) return;
        ViewGroup box = container(a, place);
        if (box == null) return;
        View cur = bannerIn(box);
        String curSig = cur == null ? null : banners.get(cur);
        final Ad want = pick(a, place, now, false, curSig);
        if (cur != null && (want == null || !want.sig().equals(curSig))) { drop(cur); cur = null; }
        if (want == null) return;
        String key = place + "|" + want.id;
        if (cur == null) {
            final View[] self = new View[1];
            View v = adCard(a, want, new Runnable() {
                @Override public void run() { recordClose(a, want, false, place); drop(self[0]); }
            }, new Runnable() {
                @Override public void run() { act(a, want, false); }
            }, false);
            self[0] = v;
            v.setTag(BANNER_TAG);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            int side = box.getPaddingLeft() >= dp(a, 8) ? 0 : dp(a, 16);
            lp.setMargins(side, dp(a, 10), side, dp(a, 10));
            int at = 0;
            if ("success".equals(place)) at = box.getChildCount();
            else if ("login".equals(place) && box.getChildCount() > 0 && box.getChildAt(0) instanceof FrameLayout) at = 1;
            box.addView(v, Math.min(at, box.getChildCount()), lp);
            banners.put(v, want.sig());
        }
        if (!visitShown.contains(key)) { visitShown.add(key); recordShow(a, want, now); }
    }

    // ───────────── окно / полоска / во весь экран — при переходе ─────────────

    static boolean popupOpen() {
        Dialog d = popupD.get();
        return d != null && d.isShowing();
    }

    static void popupTry(Activity a, String place, long now) {
        if (!liveLoaded || popupOpen() || now - lastPopupAt < POPUP_GAP_MS) return;
        if (!a.hasWindowFocus()) return;                                 // открыта страница / окно приложения — не поверх
        Ad ad = pick(a, place, now, true, null);
        if (ad == null) { if (liveLoaded) pendingPlace = ""; return; }
        if (!ad.img.isEmpty() && cached(ad.img) == null) { load(a, ad.img, null); return; }   // картинка ещё грузится
        pendingPlace = "";
        lastPopupAt = now;
        visitShown.add(place + "|" + ad.id);
        recordShow(a, ad, now);
        show(a, ad, false, place);
    }

    static void show(Activity a, Ad ad, boolean preview, String place) {
        if ("top".equals(ad.format)) showTop(a, ad, preview, place);
        else if ("full".equals(ad.format)) showFull(a, ad, preview, place);
        else if ("modal".equals(ad.format)) showModal(a, ad, preview, place);
        else showBannerPreview(a, ad);
    }

    static boolean hasBtn(Ad ad) { return !ad.btnText.trim().isEmpty(); }
    static boolean hasAction(Ad ad) { return !"close".equals(ad.btnAction); }

    /** Кнопка действия: под картинкой — рамкой (как все кнопки приложения), на картинке — оранжевой заливкой. */
    static TextView cta(Activity a, Ad ad, boolean onImage) {
        int orange = color(a, "orange", 0xffff8f1f);
        TextView b = text(a, ad.btnText.trim(), 15f, onImage ? color(a, "bg", 0xff101217) : orange, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        if (onImage) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(orange);
            g.setCornerRadius(dp(a, 12));
            b.setBackground(g);
            b.setPadding(dp(a, 22), dp(a, 11), dp(a, 22), dp(a, 11));
        } else {
            b.setBackground(IvaPatch.accentBg(a, orange, 12));
            b.setPadding(dp(a, 14), dp(a, 13), dp(a, 14), dp(a, 13));
        }
        b.setClickable(true);
        return b;
    }

    static TextView closeX(Activity a, float sizeDp) {
        TextView x = text(a, "\u2715", 14f, 0xffffffff, true);
        x.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x99000000);
        x.setBackground(g);
        x.setClickable(true);
        x.setContentDescription("Закрыть рекламу");
        x.setLayoutParams(new FrameLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp), Gravity.TOP | Gravity.END));
        return x;
    }

    /** Картинка с заданной пропорцией (высота = ширина × ratio) — место не прыгает, пока грузится. */
    static final class AspectImage extends ImageView {
        final float ratio;
        AspectImage(Context c, float ratio) { super(c); this.ratio = ratio; setScaleType(ScaleType.CENTER_CROP); }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, Math.max(1, Math.round(w * ratio)));
        }
    }

    static float ratioOf(Ad ad, float def) {
        return ad.imgW > 0 && ad.imgH > 0 ? (float) ad.imgH / ad.imgW : def;
    }

    /** Карточка рекламы (баннер и окно): картинка, заголовок, текст, кнопка, пометка «Реклама», крестик. */
    static View adCard(final Activity a, final Ad ad, final Runnable onClose, final Runnable onTap, boolean dialog) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        FrameLayout wrap = new FrameLayout(a);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(a, dialog ? "bg" : "card", dialog ? 0xff101217 : 0xff1a1d24));
        bg.setStroke(Math.max(1, dp(a, 1)), color(a, "line", 0xff262b35));
        bg.setCornerRadius(dp(a, 16));
        card.setBackground(bg);
        card.setClipToOutline(true);
        boolean img = !ad.img.isEmpty();
        boolean btnOn = img && hasBtn(ad) && "on".equals(ad.btnPlace);
        if (img) {
            FrameLayout top = new FrameLayout(a);
            float r = ratioOf(ad, dialog ? 1f : 0.5f);
            if (dialog) r = Math.min(r, 1.3f); else r = Math.min(r, 1f);
            AspectImage iv = new AspectImage(a, r);
            iv.setBackgroundColor(color(a, "line", 0xff262b35));
            load(a, ad.img, iv);
            top.addView(iv, new FrameLayout.LayoutParams(-1, -2));
            if (btnOn) {
                TextView b = cta(a, ad, true);
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                bl.bottomMargin = dp(a, 14);
                top.addView(b, bl);
                b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onTap.run(); } });
            }
            card.addView(top, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout tx = new LinearLayout(a);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(a, 16), dp(a, 14), dp(a, img ? 16 : 44), dp(a, 14));
        if (!ad.headline.trim().isEmpty()) tx.addView(text(a, ad.headline.trim(), 17f, cText, true));
        if (!ad.body.trim().isEmpty()) {
            TextView t = text(a, ad.body.trim(), 14f, cGray, false);
            t.setLineSpacing(0, 1.15f);
            t.setPadding(0, ad.headline.trim().isEmpty() ? 0 : dp(a, 4), 0, 0);
            tx.addView(t);
        }
        if (hasBtn(ad) && !btnOn) {
            TextView b = cta(a, ad, false);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = dp(a, tx.getChildCount() > 0 ? 12 : 0);
            tx.addView(b, bl);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onTap.run(); } });
        }
        String lab = labelText(ad);
        if (!lab.isEmpty()) {
            TextView t = text(a, lab, 11f, color(a, "gray2", 0xff6b7080), false);
            t.setPadding(0, tx.getChildCount() > 0 ? dp(a, 8) : 0, 0, 0);
            tx.addView(t);
        }
        if (tx.getChildCount() > 0) card.addView(tx, new LinearLayout.LayoutParams(-1, -2));
        if (hasAction(ad)) {
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onTap.run(); } });
        }
        wrap.addView(card, new FrameLayout.LayoutParams(-1, -2));
        TextView x = closeX(a, 30);
        ((FrameLayout.LayoutParams) x.getLayoutParams()).setMargins(0, dp(a, 8), dp(a, 8), 0);
        x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onClose.run(); } });
        wrap.addView(x);
        wrap.setContentDescription("Реклама" + (ad.headline.trim().isEmpty() ? "" : ": " + ad.headline.trim()));
        return wrap;
    }

    static void dim(Dialog d, float amount) {
        android.view.Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(amount);
    }

    static void width(Activity a, Dialog d, int maxDp) {
        android.view.Window w = d.getWindow();
        if (w == null) return;
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.min(screen - dp(a, 40), dp(a, maxDp)), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static void showModal(final Activity a, final Ad ad, final boolean preview, final String place) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final boolean[] done = {false};
        View v = adCard(a, ad, new Runnable() {
            @Override public void run() { if (!done[0]) { done[0] = true; recordClose(a, ad, preview, null); } d.dismiss(); }
        }, new Runnable() {
            @Override public void run() { done[0] = true; d.dismiss(); act(a, ad, preview); }
        }, true);
        ScrollView sc = new ScrollView(a);
        sc.addView(v, new FrameLayout.LayoutParams(-1, -2));
        d.setContentView(sc);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(false);
        d.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override public void onCancel(android.content.DialogInterface di) { if (!done[0]) { done[0] = true; recordClose(a, ad, preview, null); } }
        });
        dim(d, 0.65f);
        d.show();
        width(a, d, 380);
        if (!preview) popupD = new WeakReference<Dialog>(d);
    }

    static void showFull(final Activity a, final Ad ad, final boolean preview, final String place) {
        int cBg = color(a, "bg", 0xff101217), cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        final boolean[] done = {false};
        final Runnable close = new Runnable() {
            @Override public void run() { if (!done[0]) { done[0] = true; recordClose(a, ad, preview, null); } d.dismiss(); }
        };
        final Runnable tap = new Runnable() {
            @Override public void run() { done[0] = true; d.dismiss(); act(a, ad, preview); }
        };
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(cBg);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean img = !ad.img.isEmpty();
        boolean btnOn = img && hasBtn(ad) && "on".equals(ad.btnPlace);
        if (img) {
            FrameLayout box = new FrameLayout(a);
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            load(a, ad.img, iv);
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            if (btnOn) {
                TextView b = cta(a, ad, true);
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                bl.bottomMargin = dp(a, 24);
                box.addView(b, bl);
                b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tap.run(); } });
            }
            if (hasAction(ad)) iv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tap.run(); } });
            col.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            col.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        LinearLayout tx = new LinearLayout(a);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(a, 24), dp(a, 16), dp(a, 24), dp(a, 28));
        if (!ad.headline.trim().isEmpty()) {
            TextView t = text(a, ad.headline.trim(), 22f, cText, true);
            t.setGravity(Gravity.CENTER);
            tx.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!ad.body.trim().isEmpty()) {
            TextView t = text(a, ad.body.trim(), 15f, cGray, false);
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.15f);
            t.setPadding(0, dp(a, 6), 0, 0);
            tx.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (hasBtn(ad) && !btnOn) {
            TextView b = cta(a, ad, false);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = dp(a, 16);
            tx.addView(b, bl);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tap.run(); } });
        }
        String lab = labelText(ad);
        if (!lab.isEmpty()) {
            TextView t = text(a, lab, 11f, color(a, "gray2", 0xff6b7080), false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(a, 10), 0, 0);
            tx.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!img) col.addView(tx, new LinearLayout.LayoutParams(-1, -2));
        else if (tx.getChildCount() > 0) col.addView(tx, new LinearLayout.LayoutParams(-1, -2));
        if (!img) col.addView(new View(a), new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        TextView x = closeX(a, 40);
        ((FrameLayout.LayoutParams) x.getLayoutParams()).setMargins(0, dp(a, 16), dp(a, 16), 0);
        x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { close.run(); } });
        root.addView(x);
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        d.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override public void onCancel(android.content.DialogInterface di) { if (!done[0]) { done[0] = true; recordClose(a, ad, preview, null); } }
        });
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(cBg));
            try { w.setStatusBarColor(cBg); w.setNavigationBarColor(cBg); } catch (Throwable ignore) {}
        }
        d.show();
        if (!preview) popupD = new WeakReference<Dialog>(d);
    }

    /** Полоска сверху: своё окно без затемнения — нажатия мимо проходят в приложение; само закрывается через 8 с. */
    static void showTop(final Activity a, final Ad ad, final boolean preview, final String place) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), orange = color(a, "orange", 0xffff8f1f);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final boolean[] done = {false};
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(a, "card", 0xff1a1d24));
        bg.setStroke(Math.max(1, dp(a, 1)), color(a, "line", 0xff262b35));
        bg.setCornerRadius(dp(a, 14));
        bar.setBackground(bg);
        bar.setPadding(dp(a, 10), dp(a, 10), dp(a, 6), dp(a, 10));
        if (!ad.img.isEmpty()) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable r = new GradientDrawable();
            r.setCornerRadius(dp(a, 10));
            r.setColor(color(a, "line", 0xff262b35));
            iv.setBackground(r);
            iv.setClipToOutline(true);
            load(a, ad.img, iv);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48));
            il.rightMargin = dp(a, 12);
            bar.addView(iv, il);
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        if (!ad.headline.trim().isEmpty()) {
            TextView t = text(a, ad.headline.trim(), 14f, cText, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
        }
        if (!ad.body.trim().isEmpty()) {
            TextView t = text(a, ad.body.trim(), 13f, cGray, false);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
        }
        if (hasBtn(ad)) col.addView(text(a, ad.btnText.trim() + " ›", 13f, orange, true));
        String lab = labelText(ad);
        if (!lab.isEmpty()) {
            TextView t = text(a, lab, 10f, color(a, "gray2", 0xff6b7080), false);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
        }
        bar.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = text(a, "\u2715", 15f, cGray, false);
        x.setGravity(Gravity.CENTER);
        x.setContentDescription("Закрыть рекламу");
        bar.addView(x, new LinearLayout.LayoutParams(dp(a, 36), dp(a, 36)));
        x.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!done[0]) { done[0] = true; recordClose(a, ad, preview, null); } d.dismiss(); }
        });
        bar.setClickable(true);
        bar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                done[0] = true;
                d.dismiss();
                if (hasAction(ad)) act(a, ad, preview);
            }
        });
        d.setContentView(bar);
        d.setCancelable(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            w.setWindowAnimations(android.R.style.Animation_Toast);
        }
        d.show();
        if (w != null) {
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.y = dp(a, 8);
            w.setAttributes(lp);
            w.setLayout(Math.min(screen - dp(a, 24), dp(a, 480)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (!preview) popupD = new WeakReference<Dialog>(d);
        h().postDelayed(new Runnable() {
            @Override public void run() { try { if (d.isShowing()) { done[0] = true; d.dismiss(); } } catch (Throwable ignore) {} }
        }, TOP_HIDE_MS);
    }

    /** Превью баннера: как на странице — карточка поверх затемнения, с подписью где покажется. */
    static void showBannerPreview(final Activity a, final Ad ad) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(a, "Так баннер выглядит вверху страницы: " + whereText(ad).replaceFirst("^[^·]*·\\s*", ""),
            12f, 0xffffffff, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 10));
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        col.addView(adCard(a, ad, new Runnable() { @Override public void run() { d.dismiss(); } },
            new Runnable() { @Override public void run() { d.dismiss(); act(a, ad, true); } }, false), new LinearLayout.LayoutParams(-1, -2));
        d.setContentView(col);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        dim(d, 0.75f);
        d.show();
        width(a, d, 420);
    }

    // ───────────── действие кнопки ─────────────

    static void act(Activity a, Ad ad, boolean preview) {
        if (!preview && !ad.testOnly) hit(a, ad.id, "click");
        try {
            if ("url".equals(ad.btnAction)) {
                String u = normUrl(ad.btnValue);
                if (!u.isEmpty()) a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
            } else if ("tab".equals(ad.btnAction)) {
                openTab(a, ad.btnValue);
            } else if ("phone".equals(ad.btnAction)) {
                String p = phoneDigits(ad.btnValue);
                if (!p.isEmpty()) a.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p)));
            }
        } catch (Throwable e) { toast(a, "Не удалось открыть"); }
    }

    static void openTab(Activity a, String tab) throws Exception {
        if (idx(TABS, tab) < 0) return;
        if (!IvaNotifyPatch.loggedIn(a) || !shown(IvaClientPatch.get(a, "scrMain"))) return;   // гость на экране входа — разделов ещё нет
        java.lang.reflect.Method m = a.getClass().getDeclaredMethod("showTab", String.class);
        m.setAccessible(true);
        m.invoke(a, tab);
    }

    // ───────────── сеть: список, счётчики ─────────────

    static void hit(final Activity a, final String id, final String kind) {
        if (id == null || id.isEmpty()) return;
        final boolean logged = IvaNotifyPatch.loggedIn(a);
        new Thread(new Runnable() {
            @Override public void run() { try { IvaClientPatch.apiCall(a, HIT_RPC, "POST", hitBody(id, kind), logged); } catch (Throwable ignore) {} }
        }, "iva-ad-hit").start();
    }

    static void fetch(final Activity a) {
        if (fetching) return;
        fetching = true;
        lastFetch = System.currentTimeMillis();
        final boolean logged = IvaNotifyPatch.loggedIn(a);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, LIVE_RPC, "POST", "{}", logged);
                    if (logged && (r == null || !r[0].startsWith("2")) && !(r != null && noDb(r[0], r.length > 1 ? r[1] : "")))
                        r = IvaClientPatch.apiCall(a, LIVE_RPC, "POST", "{}", false);   // ключ входа истёк — общие рекламы всё равно
                    if (r != null && r[0].startsWith("2")) {
                        final List<Ad> l = sorted(parseList(r.length > 1 ? r[1] : "[]"));
                        live = l;
                        liveNoDb = false;
                        liveLoaded = true;
                        for (Ad ad : l) if (!ad.img.isEmpty()) load(a, ad.img, null);
                    } else if (r != null && noDb(r[0], r.length > 1 ? r[1] : "")) {
                        live = new ArrayList<Ad>();
                        liveNoDb = true;
                        liveLoaded = true;
                    }
                } catch (Throwable ignore) {
                } finally { fetching = false; }
                a.runOnUiThread(new Runnable() { @Override public void run() { try { step(a); } catch (Throwable ignore) {} } });
            }
        }, "iva-ads-live").start();
    }

    // ───────────── картинки: память + файл в cache ─────────────

    static android.util.LruCache<String, Bitmap> mem;
    static final Set<String> loading = Collections.synchronizedSet(new HashSet<String>());
    static final WeakHashMap<ImageView, String> waiting = new WeakHashMap<ImageView, String>();

    static synchronized android.util.LruCache<String, Bitmap> mem() {
        if (mem == null) mem = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
        return mem;
    }

    static Bitmap cached(String url) { return url == null || url.isEmpty() ? null : mem().get(url); }

    static File cacheFile(Context c, String url) {
        File dir = new File(c.getCacheDir(), "iva_ads");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length() + ".img");
    }

    static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > max) throw new Exception("слишком большая картинка"); }
        in.close();
        return out.toByteArray();
    }

    static byte[] bytesOf(Context c, String url) throws Exception {
        File f = cacheFile(c, url);
        if (f.exists() && f.length() > 0) return readAll(new java.io.FileInputStream(f), 12 * 1024 * 1024);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(12000);
        con.setReadTimeout(20000);
        int code = con.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        byte[] b = readAll(con.getInputStream(), 12 * 1024 * 1024);
        try { FileOutputStream o = new FileOutputStream(f); o.write(b); o.close(); } catch (Throwable ignore) {}
        return b;
    }

    static void load(final Activity a, final String url, ImageView into) {
        if (url == null || url.isEmpty()) return;
        Bitmap b = cached(url);
        if (b != null) { if (into != null) into.setImageBitmap(b); return; }
        if (into != null) synchronized (waiting) { waiting.put(into, url); }
        if (!loading.add(url)) return;
        final Context app = a.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bm = null;
                try { bm = IvaPhotoPatch.decode(bytesOf(app, url), 1440); } catch (Throwable ignore) {}
                if (bm != null) mem().put(url, bm);
                loading.remove(url);
                final Bitmap fb = bm;
                if (fb == null) return;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        synchronized (waiting) {
                            for (Map.Entry<ImageView, String> e : new ArrayList<Map.Entry<ImageView, String>>(waiting.entrySet()))
                                if (url.equals(e.getValue()) && e.getKey() != null) { e.getKey().setImageBitmap(fb); waiting.remove(e.getKey()); }
                        }
                    }
                });
            }
        }, "iva-ad-img").start();
    }

    // ───────────── Supabase Realtime: отметка «рекламы поменялись» ─────────────

    static volatile boolean rtRun, rtJoined;
    static volatile int rtGen, rtFails;
    static volatile long rtNextTry;

    static void rtEnsure(final Activity a) {
        if (rtRun || liveNoDb || !liveLoaded || System.currentTimeMillis() < rtNextTry) return;
        rtRun = true;
        final int g = ++rtGen;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try { ok = rtLoop(g); } catch (Throwable ignore) {}
                rtFails = ok ? 0 : rtFails + 1;
                rtJoined = false;
                rtNextTry = System.currentTimeMillis() + IvaRealtimePatch.retryMs(Math.max(1, rtFails));
                rtRun = false;
            }
        }, "iva-ads-rt").start();
    }

    static String rtToken(Activity a) { return a != null && IvaNotifyPatch.loggedIn(a) ? IvaRealtimePatch.token(a) : ""; }

    static boolean rtLoop(int g) {
        IvaRealtimePatch.Ws ws = null;
        boolean ok = false;
        try {
            Activity a = act.get();
            if (a == null) return false;
            String tok = rtToken(a);
            ws = IvaRealtimePatch.Ws.open(IvaRealtimePatch.host(), 443, true, IvaRealtimePatch.path(IvaClientPatch.SB_KEY));
            ws.text(joinMsg(tok));
            long now = System.currentTimeMillis(), hbAt = now, rxAt = now, bgAt = 0;
            int ref = 2;
            while (g == rtGen) {
                String m = ws.read(IvaRealtimePatch.READ_MS);
                now = System.currentTimeMillis();
                a = act.get();
                if (a == null || a.isFinishing()) break;
                if (m != null) {
                    rxAt = now;
                    int k = IvaRealtimePatch.classify(m, TOPIC, JOIN_REF);
                    if (k == IvaRealtimePatch.M_JOIN_OK) { ok = true; rtJoined = true; fetch(a); }
                    else if (k == IvaRealtimePatch.M_CHANGE) fetch(a);
                    else if (k == IvaRealtimePatch.M_JOIN_ERR || k == IvaRealtimePatch.M_SYS_ERR || k == IvaRealtimePatch.M_CLOSED) break;
                }
                if (now - rxAt > IvaRealtimePatch.SILENCE_MS) break;
                if (now - hbAt >= IvaRealtimePatch.HEARTBEAT_MS) {
                    hbAt = now;
                    ws.text(IvaRealtimePatch.heartbeatMsg(ref++));
                    String t2 = rtToken(a);
                    if (!t2.equals(tok)) {
                        if (t2.isEmpty() || tok.isEmpty()) break;                // вошли / вышли — заново с новой ролью
                        tok = t2;
                        ws.text(IvaRealtimePatch.tokenMsg(TOPIC, JOIN_REF, tok, ref++));
                    }
                    if (IvaRealtimePatch.foreground()) bgAt = 0;
                    else if (bgAt == 0) bgAt = now;
                    else if (now - bgAt > IvaRealtimePatch.BG_GRACE_MS) break;   // свёрнуто — до возвращения
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (ws != null) ws.close();
        }
        return ok;
    }

    // ════════════════════════════════ кнопка в «Панели владельца» ════════════════════════════════

    static Button findBtn(ViewGroup g, String text) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof Button && text.contentEquals(((Button) c).getText())) return (Button) c;
            if (c instanceof ViewGroup) { Button b = findBtn((ViewGroup) c, text); if (b != null) return b; }
        }
        return null;
    }

    /** Из IvaAdminsPatch.cleanPanel: под «Все администраторы →» — «Рекламный кабинет →» тем же видом (копия стиля). */
    static void panelButton(final Activity a, ViewGroup box) {
        if (box == null || !chief(a)) return;
        Button nat = findBtn(box, NATIVE_ALL);
        if (nat == null || !(nat.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) nat.getParent();
        if (parent.findViewWithTag(BTN_TAG) != null) return;
        Button b = new Button(a);
        b.setTag(BTN_TAG);
        b.setText(BTN_TEXT);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, nat.getTextSize());
        b.setTextColor(nat.getTextColors());
        b.setTypeface(nat.getTypeface());
        b.setGravity(nat.getGravity());
        b.setPadding(nat.getPaddingLeft(), nat.getPaddingTop(), nat.getPaddingRight(), nat.getPaddingBottom());
        b.setMinHeight(nat.getMinHeight());
        b.setMinimumHeight(0);
        Drawable bg = nat.getBackground();
        if (bg != null && bg.getConstantState() != null) b.setBackground(bg.getConstantState().newDrawable().mutate());
        if (nat.getStateListAnimator() == null) b.setStateListAnimator(null);
        b.setElevation(nat.getElevation());
        ViewGroup.LayoutParams lp = nat.getLayoutParams();
        ViewGroup.LayoutParams copy = lp instanceof LinearLayout.LayoutParams ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp)
            : lp instanceof ViewGroup.MarginLayoutParams ? new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) lp)
            : new ViewGroup.LayoutParams(lp == null ? new ViewGroup.LayoutParams(-1, -2) : lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openCabinet(a); } catch (Throwable t) { toast(a, "Не удалось открыть рекламный кабинет"); } }
        });
        parent.addView(b, parent.indexOfChild(nat) + 1, copy);
    }

    // ════════════════════════════════ кабинет ════════════════════════════════

    static final class Cab {
        Activity a;
        IvaChiefPatch.Page p;
        String section = "promo", err = "";
        List<Ad> all = new ArrayList<Ad>();
        boolean loaded;
        LinearLayout tabs, body;
    }

    static WeakReference<Cab> openCab = new WeakReference<Cab>(null);

    static void openCabinet(final Activity a) {
        if (!chief(a)) return;
        final Cab c = new Cab();
        c.a = a;
        c.p = IvaChiefPatch.page(a, TITLE, CAB_SUB, false);
        c.tabs = new LinearLayout(a);
        c.tabs.setOrientation(LinearLayout.HORIZONTAL);
        c.p.list.addView(c.tabs, new LinearLayout.LayoutParams(-1, -2));
        c.body = new LinearLayout(a);
        c.body.setOrientation(LinearLayout.VERTICAL);
        c.p.list.addView(c.body, new LinearLayout.LayoutParams(-1, -2));
        openCab = new WeakReference<Cab>(c);
        render(c);
        reload(c);
    }

    static int count(Cab c, String section) {
        int n = 0;
        for (Ad ad : c.all) if (section.equals(ad.section)) n++;
        return n;
    }

    static void renderTabs(final Cab c) {
        final Activity a = c.a;
        c.tabs.removeAllViews();
        for (int i = 0; i < SECTIONS.length; i++) {
            final String s = SECTIONS[i];
            int n = count(c, s);
            TextView t = IvaSearchPatch.chip(a, SECTION_T[i] + (n > 0 ? " · " + n : ""), s.equals(c.section), false);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(a, 8);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { c.section = s; render(c); }
            });
            c.tabs.addView(t, lp);
        }
    }

    static void render(final Cab c) {
        final Activity a = c.a;
        renderTabs(c);
        c.body.removeAllViews();
        int cOrange = color(a, "orange", 0xffff8f1f);
        TextView add = IvaPickupPatch.addButton(a, cOrange);
        add.setText(ADD);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(a, 14);
        c.body.addView(add, al);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!c.err.isEmpty() && c.err.equals(NO_DB)) { toast(a, NO_DB); return; }
                editor(c, null);
            }
        });
        if (!c.loaded && c.err.isEmpty()) { c.body.addView(IvaChiefPatch.hint(a, "Загружаем…")); return; }
        if (!c.err.isEmpty()) { c.body.addView(IvaChiefPatch.hint(a, c.err)); return; }
        long now = System.currentTimeMillis();
        int n = 0;
        for (Ad ad : sorted(c.all)) {
            if (!c.section.equals(ad.section)) continue;
            c.body.addView(adRow(c, ad, now));
            n++;
        }
        if (n == 0) c.body.addView(IvaChiefPatch.hint(a, "В разделе «" + titleOf(SECTIONS, SECTION_T, c.section)
            + "» пока нет рекламы. Нажмите «Добавить рекламу» — откроется конструктор."));
        c.body.addView(IvaChiefPatch.note(a, "Как показывается реклама",
            "Только включённая и только тем, кому вы настроили. Включили или изменили — у всех обновится сразу, без перезапуска.\n"
            + "Баннер появляется на странице сразу. Окно, полоска и «во весь экран» — когда человек открывает приложение или "
            + "переходит в другой раздел, а не посреди набора текста; не чаще раза в 30 секунд.\n"
            + "«Пробная» видна только вам — проверьте, как выглядит, потом нажмите «Показать».\n"
            + "Чужую рекламу по закону нужно маркировать: пометка «Реклама», рекламодатель и erid (раздел «Маркировка» в конструкторе).", null));
    }

    static View adRow(final Cab c, final Ad ad, long now) {
        final Activity a = c.a;
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(a, "bg", 0xff101217));
        bg.setStroke(Math.max(1, dp(a, 1)), color(a, "line", 0xff262b35));
        bg.setCornerRadius(dp(a, 14));
        r.setBackground(bg);
        View pic;
        if (!ad.img.isEmpty()) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable pb = new GradientDrawable();
            pb.setCornerRadius(dp(a, 10));
            pb.setColor(color(a, "line", 0xff262b35));
            iv.setBackground(pb);
            iv.setClipToOutline(true);
            load(a, ad.img, iv);
            pic = iv;
        } else {
            String h = ad.headline.trim().isEmpty() ? ad.name.trim() : ad.headline.trim();
            TextView t = text(a, h.isEmpty() ? "?" : h.substring(0, 1).toUpperCase(Locale.ROOT), 24f, color(a, "orange", 0xffff8f1f), true);
            t.setGravity(Gravity.CENTER);
            GradientDrawable pb = new GradientDrawable();
            pb.setCornerRadius(dp(a, 10));
            pb.setColor(color(a, "card", 0xff1a1d24));
            t.setBackground(pb);
            pic = t;
        }
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(a, 64), dp(a, 64));
        pl.rightMargin = dp(a, 12);
        r.addView(pic, pl);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = text(a, ad.name.trim().isEmpty() ? "Без названия" : ad.name.trim(), 15f, cText, true);
        nm.setMaxLines(2);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm);
        int st = state(ad, now);
        int k = st == ST_ON ? 0 : st == ST_OFF ? 1 : st == ST_ENDED ? 2 : 3;
        TextView b = text(a, stateText(ad, now, null), 12f, IvaMyToolsPatch.KIND_COLOR[k], true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(IvaMyToolsPatch.KIND_BG[k]);
        g.setCornerRadius(dp(a, 10));
        b.setBackground(g);
        b.setPadding(dp(a, 10), dp(a, 3), dp(a, 10), dp(a, 3));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(a, 6);
        col.addView(b, bl);
        TextView w = text(a, whereText(ad), 12f, cGray, false);
        w.setPadding(0, dp(a, 4), 0, 0);
        w.setMaxLines(2);
        w.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(w);
        TextView s = text(a, statsText(ad), 12f, color(a, "gray2", 0xff6b7080), false);
        s.setPadding(0, dp(a, 2), 0, 0);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView more = text(a, "›", 22f, cGray, false);
        more.setPadding(dp(a, 8), 0, 0, 0);
        r.addView(more);
        r.setClickable(true);
        r.setContentDescription((ad.name.isEmpty() ? "Реклама" : ad.name) + ", " + stateText(ad, now, null) + " — действия");
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { actions(c, ad); } });
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(a, 10);
        wrap.addView(r, wl);
        return wrap;
    }

    static void actions(final Cab c, final Ad ad) {
        final Activity a = c.a;
        final boolean on = ad.active && !ad.testOnly;
        final String[] items = on ? ACTIONS_MENU_ON : ACTIONS_MENU_OFF;
        IvaClientPatch.styledChoice(a, ad.name.isEmpty() ? "Реклама" : ad.name,
            stateText(ad, System.currentTimeMillis(), null) + "\n" + statsText(ad), items, new IvaClientPatch.Choice() {
            @Override public void pick(int i) {
                if (i < 0 || i >= items.length) return;
                String act = items[i];
                if ("Превью".equals(act)) show(a, ad, true, null);
                else if ("Редактировать".equals(act)) editor(c, ad);
                else if ("Показать".equals(act)) setActive(c, ad, true);
                else if ("Скрыть".equals(act)) setActive(c, ad, false);
                else if ("Сделать копию".equals(act)) duplicate(c, ad);
                else if ("Удалить".equals(act)) confirmDelete(c, ad);
            }
        });
    }

    /** Текст ошибки из ответа PostgREST ({"message": "..."}). */
    static String msgOf(String body) {
        String b = body == null ? "" : body.trim();
        try { if (b.startsWith("{")) { String m = new JSONObject(b).optString("message", ""); if (!m.isEmpty()) return m; } } catch (Throwable ignore) {}
        return IvaClientPatch.brief(b.isEmpty() ? "нет связи с сервером" : b);
    }

    interface Done { void ok(String body); }

    /** RPC создателя в своём потоке; ошибка — тостом. */
    static void rpc(final Activity a, final String path, final String body, final Done done) {
        new Thread(new Runnable() {
            @Override public void run() {
                String[] r = null;
                String err;
                try { r = IvaClientPatch.apiCall(a, path, "POST", body, true); err = ""; }
                catch (Throwable e) { err = "нет связи с сервером"; }
                final String[] fr = r;
                final String ferr = fr != null && fr[0].startsWith("2") ? "" : !err.isEmpty() ? err
                    : fr != null && noDb(fr[0], fr.length > 1 ? fr[1] : "") ? NO_DB : msgOf(fr == null || fr.length < 2 ? "" : fr[1]);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (!ferr.isEmpty()) { toast(a, ferr.equals(NO_DB) ? NO_DB : "Не получилось: " + ferr); done.ok(null); return; }
                        done.ok(fr.length > 1 ? fr[1] : "");
                    }
                });
            }
        }, "iva-ads-rpc").start();
    }

    static void reload(final Cab c) {
        final Activity a = c.a;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Ad> l = null;
                String err = "";
                try {
                    String[] r = IvaClientPatch.apiCall(a, LIST_RPC, "POST", "{}", true);
                    if (r != null && r[0].startsWith("2")) l = parseList(r.length > 1 ? r[1] : "[]");
                    else if (r != null && noDb(r[0], r.length > 1 ? r[1] : "")) err = NO_DB;
                    else err = "Не загрузилось: " + msgOf(r == null || r.length < 2 ? "" : r[1]);
                } catch (Throwable e) { err = "Не загрузилось — нет связи с сервером"; }
                final List<Ad> fl = l;
                final String ferr = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!c.p.open()) return;
                        if (fl != null) { c.all = fl; c.loaded = true; c.err = ""; } else c.err = ferr;
                        render(c);
                    }
                });
            }
        }, "iva-ads-list").start();
    }

    static void changed(Cab c) {
        reload(c);
        fetch(c.a);                                   // у себя — сразу; у остальных — по отметке Realtime
    }

    static void setActive(final Cab c, final Ad ad, final boolean on) {
        if (on && ad.endAt > 0 && ad.endAt <= System.currentTimeMillis()) {
            toast(c.a, "Срок показа закончился — откройте «Редактировать» и поменяйте окончание");
            return;
        }
        rpc(c.a, ACTIVE_RPC, "{\"p_id\":" + q(ad.id) + ",\"p_on\":" + on + "}", new Done() {
            @Override public void ok(String body) {
                if (body == null) return;
                toast(c.a, on ? "Показывается — у всех, кому настроена, уже сейчас" : "Скрыта — у всех пропала сразу");
                changed(c);
            }
        });
    }

    static void duplicate(final Cab c, Ad ad) {
        Ad d = ad.copy();
        d.id = "";
        d.name = (ad.name.trim() + " (копия)").trim();
        if (d.name.length() > 120) d.name = d.name.substring(0, 120);
        d.active = false;
        d.testOnly = false;
        rpc(c.a, SAVE_RPC, saveBody(d), new Done() {
            @Override public void ok(String body) {
                if (body == null) return;
                toast(c.a, "Копия создана — она скрыта, поправьте и включите");
                changed(c);
            }
        });
    }

    static boolean usedElsewhere(Cab c, String url, String exceptId) {
        if (url == null || url.isEmpty()) return true;
        for (Ad ad : c.all) if (url.equals(ad.img) && !ad.id.equals(exceptId)) return true;
        return false;
    }

    static void confirmDelete(final Cab c, final Ad ad) {
        final String[] items = {"Удалить"};
        IvaClientPatch.styledChoice(c.a, "Удалить рекламу?", "«" + (ad.name.isEmpty() ? "Без названия" : ad.name)
            + "» пропадёт у всех сразу, счётчики тоже удалятся. Вернуть нельзя — если нужно только убрать с экранов, нажмите «Скрыть».",
            items, new IvaClientPatch.Choice() {
            @Override public void pick(int i) {
                if (i != 0) return;
                rpc(c.a, DEL_RPC, "{\"p_id\":" + q(ad.id) + "}", new Done() {
                    @Override public void ok(String body) {
                        if (body == null) return;
                        if (!usedElsewhere(c, ad.img, ad.id)) removeImage(c.a, ad.img);
                        toast(c.a, "Реклама удалена");
                        changed(c);
                    }
                });
            }
        });
    }

    // ───────────── хранилище картинок ─────────────

    static String publicPrefix() { return IvaClientPatch.SB_URL + "/storage/v1/object/public/" + BUCKET + "/"; }

    static String upload(Activity a, byte[] data, String ext, String mime) throws Exception {
        String name = java.util.UUID.randomUUID().toString() + "." + ext;
        HttpURLConnection c = (HttpURLConnection) new URL(IvaClientPatch.SB_URL + "/storage/v1/object/" + BUCKET + "/" + name).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("apikey", IvaClientPatch.SB_KEY);
        c.setRequestProperty("Authorization", "Bearer " + String.valueOf(IvaClientPatch.get(a, "accessToken")));
        c.setRequestProperty("Content-Type", mime);
        c.setRequestProperty("cache-control", "max-age=604800");
        c.setRequestProperty("x-upsert", "false");
        OutputStream out = c.getOutputStream();
        out.write(data);
        out.flush();
        out.close();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String body = "";
            try { body = new String(readAll(c.getErrorStream(), 65536), "UTF-8"); } catch (Throwable ignore) {}
            if (body.contains("Bucket not found")) throw new Exception("нет хранилища iva-ads — выполните SQL 20261124_ads.sql");
            if (code == 400 && body.contains("row-level security") || code == 403) throw new Exception("нет прав на загрузку — войдите как создатель");
            throw new Exception("HTTP " + code);
        }
        return publicPrefix() + name;
    }

    static void removeImage(final Activity a, final String url) {
        if (url == null || !url.startsWith(publicPrefix())) return;
        final String name = url.substring(publicPrefix().length());
        if (name.isEmpty() || name.contains("/")) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(IvaClientPatch.SB_URL + "/storage/v1/object/" + BUCKET + "/" + name).openConnection();
                    c.setRequestMethod("DELETE");
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("apikey", IvaClientPatch.SB_KEY);
                    c.setRequestProperty("Authorization", "Bearer " + String.valueOf(IvaClientPatch.get(a, "accessToken")));
                    c.getResponseCode();
                    c.disconnect();
                } catch (Throwable ignore) {}
            }
        }, "iva-ad-rm").start();
    }

    // ───────────── выбор картинки из галереи ─────────────

    static final int REQ = 7331;
    static final String PICKER_TAG = "iva_ads_picker";
    static Ed pendingEd;

    static void pickImage(final Ed e) {
        pendingEd = e;
        try {
            android.app.FragmentManager fm = e.a.getFragmentManager();
            android.app.Fragment f = fm.findFragmentByTag(PICKER_TAG);
            if (!(f instanceof Picker)) {
                f = new Picker();
                fm.beginTransaction().add(f, PICKER_TAG).commitAllowingStateLoss();
                fm.executePendingTransactions();
            }
            final android.app.Fragment ff = f;
            boolean ok = IvaPhotoPatch.startGallery(1, new IvaPhotoPatch.Starter() {
                @Override public void start(Intent i) { ff.startActivityForResult(i, REQ); }
            });
            if (!ok) { pendingEd = null; toast(e.a, "Не удалось открыть галерею"); }
        } catch (Throwable t) {
            pendingEd = null;
            toast(e.a, "Не удалось открыть галерею");
        }
    }

    /** Невидимый помощник: результат выбора картинки (как Picker у аватара). */
    public static final class Picker extends android.app.Fragment {
        public Picker() {}

        @Override public void onActivityResult(int request, int result, Intent data) {
            Activity a = getActivity();
            Uri uri = request == REQ && result == Activity.RESULT_OK && data != null ? data.getData() : null;
            try { if (a != null && request == REQ) picked(a, uri); } catch (Throwable ignore) {}
            try { getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss(); } catch (Throwable ignore) {}
        }
    }

    static boolean isPng(byte[] raw) { return raw != null && raw.length > 4 && (raw[0] & 0xff) == 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G'; }

    static void picked(final Activity a, final Uri uri) {
        final Ed e = pendingEd;
        pendingEd = null;
        if (uri == null || e == null || !e.p.open()) return;
        e.uploading = true;
        e.imgStatus.setText("Загружаем картинку…");
        e.imgStatus.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                String url = null, err = "";
                try {
                    byte[] raw = IvaClientPatch.readUri(a, uri);
                    b = IvaAvatarPatch.fitMax(IvaAvatarPatch.decode(raw, 2160), 1440);
                    boolean png = isPng(raw) && b.hasAlpha();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    b.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 86, bytes);
                    url = upload(a, bytes.toByteArray(), png ? "png" : "jpg", png ? "image/png" : "image/jpeg");
                    mem().put(url, b);
                } catch (Throwable t) { err = IvaClientPatch.brief(t.getMessage()); }
                final Bitmap fb = b;
                final String furl = url, ferr = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        e.uploading = false;
                        if (furl == null) {
                            e.imgStatus.setText("Картинка не загрузилась" + (ferr.isEmpty() ? "" : ": " + ferr));
                            return;
                        }
                        e.uploaded.add(furl);
                        e.f.img = furl;
                        e.f.imgW = fb.getWidth();
                        e.f.imgH = fb.getHeight();
                        syncImage(e);
                    }
                });
            }
        }, "iva-ad-upload").start();
    }

    // ════════════════════════════════ конструктор ════════════════════════════════

    /** Чипы с переносом строк. */
    static final class Flow extends ViewGroup {
        final int gap;
        Flow(Context c, int gap) { super(c); this.gap = gap; }

        @Override protected void onMeasure(int ws, int hs) {
            int maxW = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) maxW = Integer.MAX_VALUE / 2;
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += cw + gap;
                rowH = Math.max(rowH, ch);
            }
            int w = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? x : MeasureSpec.getSize(ws);
            setMeasuredDimension(w, y + rowH + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + cw, getPaddingTop() + y + ch);
                x += cw + gap;
                rowH = Math.max(rowH, ch);
            }
        }
    }

    interface Sel { boolean on(int i); void tap(int i); }

    static Flow chips(Activity a, String[] titles, Sel s) {
        Flow f = new Flow(a, dp(a, 8));
        fillChips(a, f, titles, s);
        return f;
    }

    static void fillChips(final Activity a, final Flow f, final String[] titles, final Sel s) {
        f.removeAllViews();
        for (int i = 0; i < titles.length; i++) {
            final int k = i;
            TextView c = IvaSearchPatch.chip(a, titles[i], s.on(i), false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { s.tap(k); fillChips(a, f, titles, s); }
            });
            f.addView(c);
        }
    }

    static final class Ed {
        Activity a;
        Cab c;
        Ad f, orig;
        IvaChiefPatch.Page p;
        boolean saved, busy, uploading;
        String initial = "";
        final List<String> uploaded = new ArrayList<String>();
        EditText name, headline, body, btnText, btnValue, maxShows, gap, advertiser, inn, erid;
        TextView formatSub, imgStatus, imgPick, imgClear, err, startV, endV, startX, endX, fromV, toV, valueLabel, saveBtn;
        Switch hideOnClose, priority, adLabel;
        View valueBox, tabBox, btnPlaceBox, customFreq;
        FrameLayout imgFrame;
        Flow btnPlaceChips;
        Runnable refreshAll;
    }

    static TextView small(Activity a, String s) {
        TextView t = text(a, s, 12f, color(a, "gray2", 0xff6b7080), false);
        t.setPadding(dp(a, 2), dp(a, 6), 0, 0);
        return t;
    }

    static EditText field(Activity a, String hint, String value) {
        return IvaStaffPatch.field(a, hint, value, color(a, "text", 0xfff2f4f8));
    }

    static TextView label(Activity a, String s) { return IvaStaffPatch.label(a, s, color(a, "gray", 0xff9aa1b0)); }

    static void limit(EditText e, int max) { e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)}); }

    /** Поле-кнопка (вид поля ввода): дата, время. */
    static TextView pseudo(Activity a, String s) {
        TextView t = text(a, s, 15f, color(a, "text", 0xfff2f4f8), false);
        Drawable fb = IvaSearchPatch.drawable(a, "bg_field");
        if (fb != null) t.setBackground(fb);
        t.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        t.setClickable(true);
        return t;
    }

    static int parseInt(EditText e, int def) {
        try { String s = e.getText().toString().trim(); return s.isEmpty() ? 0 : Integer.parseInt(s); } catch (Throwable t) { return def; }
    }

    static String txt(EditText e) { return e == null ? "" : e.getText().toString(); }

    static void collect(Ed e) {
        Ad f = e.f;
        f.name = txt(e.name);
        f.headline = txt(e.headline);
        f.body = txt(e.body);
        f.btnText = txt(e.btnText);
        if ("url".equals(f.btnAction) || "phone".equals(f.btnAction)) f.btnValue = txt(e.btnValue).trim();
        if (e.customFreq != null && e.customFreq.getVisibility() == View.VISIBLE) {
            f.maxShows = parseInt(e.maxShows, -1);
            f.gapHours = parseInt(e.gap, -1);
        }
        f.hideOnClose = e.hideOnClose.isChecked();
        f.priority = e.priority.isChecked() ? 1 : 0;
        f.adLabel = e.adLabel.isChecked();
        f.advertiser = txt(e.advertiser);
        f.inn = txt(e.inn);
        f.erid = txt(e.erid);
    }

    static void syncImage(Ed e) {
        Activity a = e.a;
        e.imgFrame.removeAllViews();
        boolean has = !e.f.img.isEmpty();
        if (has) {
            float r = Math.min(ratioOf(e.f, 0.5f), 1.4f);
            AspectImage iv = new AspectImage(a, r);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(color(a, "card", 0xff1a1d24));
            load(a, e.f.img, iv);
            e.imgFrame.addView(iv, new FrameLayout.LayoutParams(-1, -2));
        }
        e.imgFrame.setVisibility(has ? View.VISIBLE : View.GONE);
        e.imgPick.setText(has ? "Заменить картинку" : "Выбрать картинку из галереи");
        e.imgClear.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has && e.f.imgW > 0) {
            String warn = Math.max(e.f.imgW, e.f.imgH) < 600 ? " — маленькая, может выглядеть размыто" : "";
            e.imgStatus.setText("Картинка " + e.f.imgW + "×" + e.f.imgH + warn);
            e.imgStatus.setVisibility(View.VISIBLE);
        } else if (!e.uploading) e.imgStatus.setVisibility(View.GONE);
        if (e.btnPlaceBox != null) e.btnPlaceBox.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    static void syncAll(Ed e) {
        Ad f = e.f;
        e.formatSub.setText(FORMAT_SUB[Math.max(0, idx(FORMATS, f.format))]);
        boolean val = "url".equals(f.btnAction) || "phone".equals(f.btnAction);
        e.valueBox.setVisibility(val ? View.VISIBLE : View.GONE);
        if (val) {
            boolean url = "url".equals(f.btnAction);
            e.valueLabel.setText(url ? "Ссылка" : "Номер телефона");
            e.btnValue.setHint(url ? "https://eclipsru.github.io/Arenda-Nvk/" : "+7 900 000-00-00");
            e.btnValue.setInputType(url ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_CLASS_PHONE);
        }
        e.tabBox.setVisibility("tab".equals(f.btnAction) ? View.VISIBLE : View.GONE);
        e.startV.setText(f.startAt > 0 ? dateText(f.startAt, null) : "Сразу после включения");
        e.endV.setText(f.endAt > 0 ? dateText(f.endAt, null) : "Без окончания");
        e.startX.setVisibility(f.startAt > 0 ? View.VISIBLE : View.GONE);
        e.endX.setVisibility(f.endAt > 0 ? View.VISIBLE : View.GONE);
        e.fromV.setText("с " + hh(f.hourFrom));
        e.toV.setText("до " + hh(f.hourTo));
        syncImage(e);
    }

    static void pickDate(final Ed e, final boolean start) {
        final Activity a = e.a;
        long cur = start ? e.f.startAt : e.f.endAt;
        final Calendar cal = Calendar.getInstance();
        if (cur > 0) cal.setTimeInMillis(cur);
        else if (!start && e.f.startAt > 0) { cal.setTimeInMillis(e.f.startAt); cal.add(Calendar.DAY_OF_MONTH, 7); }
        else if (!start) cal.add(Calendar.DAY_OF_MONTH, 7);
        android.app.DatePickerDialog dd = new android.app.DatePickerDialog(a, android.R.style.Theme_Material_Dialog,
            new android.app.DatePickerDialog.OnDateSetListener() {
                @Override public void onDateSet(android.widget.DatePicker v, int y, int m, int d) {
                    cal.set(y, m, d);
                    new android.app.TimePickerDialog(a, android.R.style.Theme_Material_Dialog, new android.app.TimePickerDialog.OnTimeSetListener() {
                        @Override public void onTimeSet(android.widget.TimePicker tp, int h, int min) {
                            cal.set(Calendar.HOUR_OF_DAY, h);
                            cal.set(Calendar.MINUTE, min);
                            cal.set(Calendar.SECOND, 0);
                            cal.set(Calendar.MILLISECOND, 0);
                            if (start) e.f.startAt = cal.getTimeInMillis(); else e.f.endAt = cal.getTimeInMillis();
                            syncAll(e);
                        }
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
                }
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        if (start) dd.getDatePicker().setMinDate(System.currentTimeMillis() - DAY_MS);
        dd.show();
    }

    static void pickHour(final Ed e, final boolean from) {
        int cur = from ? e.f.hourFrom : (e.f.hourTo >= 24 ? 0 : e.f.hourTo);
        new android.app.TimePickerDialog(e.a, android.R.style.Theme_Material_Dialog, new android.app.TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(android.widget.TimePicker tp, int h, int min) {
                if (from) e.f.hourFrom = h; else e.f.hourTo = h == 0 ? 24 : h;
                syncAll(e);
            }
        }, cur, 0, true).show();
    }

    static LinearLayout row(Activity a) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    static TextView linkBtn(Activity a, String s) {
        TextView t = text(a, s, 14f, color(a, "orange", 0xffff8f1f), true);
        t.setPadding(dp(a, 12), dp(a, 10), dp(a, 4), dp(a, 10));
        t.setClickable(true);
        return t;
    }

    static void editor(final Cab c, final Ad old) {
        final Activity a = c.a;
        final Ed e = new Ed();
        e.a = a;
        e.c = c;
        e.orig = old;
        if (old == null) {
            e.f = new Ad();
            e.f.section = c.section;
            e.f.maxShows = 0;                         // баннер по умолчанию — каждый раз (решение 05.11: окна — один раз)
            e.f.gapHours = 0;
        } else e.f = old.copy();
        final Ad f = e.f;
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        e.p = IvaChiefPatch.page(a, old == null ? "Новая реклама" : "Редактировать рекламу",
            "Раздел «" + titleOf(SECTIONS, SECTION_T, f.section) + "». Поля, кроме названия, — по желанию; превью — до сохранения.", false);
        LinearLayout L = e.p.list;

        // 1. Основное
        L.addView(IvaChiefPatch.head(a, "Основное"));
        L.addView(label(a, "Название — видите только вы"));
        e.name = field(a, "Например: Скидка 20% на перфораторы", f.name);
        limit(e.name, 120);
        L.addView(e.name);
        L.addView(label(a, "Раздел кабинета"));
        L.addView(chips(a, SECTION_T, new Sel() {
            @Override public boolean on(int i) { return SECTIONS[i].equals(f.section); }
            @Override public void tap(int i) { f.section = SECTIONS[i]; }
        }));

        // 2. Вид показа
        L.addView(IvaChiefPatch.head(a, "Вид показа"));
        L.addView(chips(a, FORMAT_T, new Sel() {
            @Override public boolean on(int i) { return FORMATS[i].equals(f.format); }
            @Override public void tap(int i) {
                boolean wasPopup = f.popup();
                f.format = FORMATS[i];
                if (e.orig == null && wasPopup != f.popup()) {     // новая: окна — «Один раз», баннер — «Каждый раз»
                    int[] fr = freqOf(f.popup() ? 0 : 2);
                    f.maxShows = fr[0];
                    f.gapHours = fr[1];
                    e.refreshAll.run();
                }
                syncAll(e);
            }
        }));
        e.formatSub = small(a, "");
        L.addView(e.formatSub);

        // 3. Картинка
        L.addView(IvaChiefPatch.head(a, "Картинка"));
        e.imgFrame = new FrameLayout(a);
        e.imgFrame.setBackground(IvaPatch.accentBg(a, color(a, "line", 0xff262b35), 12));
        e.imgFrame.setPadding(dp(a, 1), dp(a, 1), dp(a, 1), dp(a, 1));
        L.addView(e.imgFrame, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout ir = row(a);
        e.imgPick = linkBtn(a, "Выбрать картинку из галереи");
        e.imgPick.setPadding(dp(a, 2), dp(a, 10), dp(a, 12), dp(a, 10));
        e.imgClear = linkBtn(a, "Убрать");
        e.imgClear.setTextColor(cGray);
        ir.addView(e.imgPick, new LinearLayout.LayoutParams(0, -2, 1f));
        ir.addView(e.imgClear);
        L.addView(ir);
        e.imgStatus = small(a, "");
        e.imgStatus.setVisibility(View.GONE);
        L.addView(e.imgStatus);
        L.addView(small(a, "Можно только картинку, только текст или вместе. Картинка уменьшается до 1440 px и хранится в Supabase."));
        e.imgPick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (e.uploading) toast(a, "Картинка ещё загружается"); else pickImage(e); }
        });
        e.imgClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { f.img = ""; f.imgW = 0; f.imgH = 0; syncImage(e); }
        });

        // 4. Текст
        L.addView(IvaChiefPatch.head(a, "Текст"));
        L.addView(label(a, "Заголовок"));
        e.headline = field(a, "Например: −20% на перфораторы до воскресенья", f.headline);
        limit(e.headline, 120);
        L.addView(e.headline);
        L.addView(label(a, "Текст"));
        e.body = field(a, "Пара строк: что предлагаете и почему это выгодно", f.body);
        e.body.setSingleLine(false);
        e.body.setMinLines(3);
        e.body.setGravity(Gravity.TOP | Gravity.START);
        e.body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        limit(e.body, 600);
        L.addView(e.body);

        // 5. Кнопка
        L.addView(IvaChiefPatch.head(a, "Кнопка"));
        L.addView(label(a, "Текст кнопки"));
        e.btnText = field(a, "Например: Подробнее", f.btnText);
        limit(e.btnText, 40);
        L.addView(e.btnText);
        L.addView(label(a, "Что делает нажатие"));
        L.addView(chips(a, ACTION_T, new Sel() {
            @Override public boolean on(int i) { return ACTIONS[i].equals(f.btnAction); }
            @Override public void tap(int i) {
                collect(e);
                if (!ACTIONS[i].equals(f.btnAction)) f.btnValue = "";
                f.btnAction = ACTIONS[i];
                e.btnValue.setText(f.btnValue);
                if ("tab".equals(f.btnAction) && idx(TABS, f.btnValue) < 0) { f.btnValue = "home"; e.refreshAll.run(); }
                syncAll(e);
            }
        }));
        LinearLayout vb = new LinearLayout(a);
        vb.setOrientation(LinearLayout.VERTICAL);
        e.valueLabel = label(a, "Ссылка");
        vb.addView(e.valueLabel);
        e.btnValue = field(a, "", "url".equals(f.btnAction) || "phone".equals(f.btnAction) ? f.btnValue : "");
        limit(e.btnValue, 500);
        vb.addView(e.btnValue);
        e.valueBox = vb;
        L.addView(vb);
        LinearLayout tb = new LinearLayout(a);
        tb.setOrientation(LinearLayout.VERTICAL);
        tb.addView(label(a, "Какой раздел открыть"));
        final Flow tabChips = chips(a, TAB_T, new Sel() {
            @Override public boolean on(int i) { return TABS[i].equals(f.btnValue); }
            @Override public void tap(int i) { f.btnValue = TABS[i]; }
        });
        tb.addView(tabChips);
        tb.addView(small(a, "У гостей (ещё не вошли) разделов нет — кнопка просто закроет рекламу."));
        e.tabBox = tb;
        L.addView(tb);
        LinearLayout pb = new LinearLayout(a);
        pb.setOrientation(LinearLayout.VERTICAL);
        pb.addView(label(a, "Где кнопка"));
        e.btnPlaceChips = chips(a, new String[]{"Под картинкой", "На картинке"}, new Sel() {
            @Override public boolean on(int i) { return (i == 1) == "on".equals(f.btnPlace); }
            @Override public void tap(int i) { f.btnPlace = i == 1 ? "on" : "below"; }
        });
        pb.addView(e.btnPlaceChips);
        e.btnPlaceBox = pb;
        L.addView(pb);
        L.addView(small(a, "Без текста кнопки нажатие на саму рекламу всё равно выполнит действие. Крестик «закрыть» есть всегда."));

        // 6. Кому
        L.addView(IvaChiefPatch.head(a, "Кому показывать"));
        L.addView(chips(a, AUD_T, new Sel() {
            @Override public boolean on(int i) { return f.audience.contains(AUD[i]); }
            @Override public void tap(int i) { if (!f.audience.remove(AUD[i])) f.audience.add(AUD[i]); }
        }));
        L.addView(small(a, "«Новые» — кто установил приложение и ещё не вошёл: им реклама видна на экране входа и регистрации."));
        L.addView(label(a, "Только недавно установившим"));
        final Flow newChips = chips(a, NEW_DAYS_T, new Sel() {
            @Override public boolean on(int i) { return NEW_DAYS[i] == f.newDays || i == 0 && idx2(NEW_DAYS, f.newDays) < 0 && f.newDays <= 0; }
            @Override public void tap(int i) { f.newDays = NEW_DAYS[i]; }
        });
        L.addView(newChips);
        L.addView(small(a, "Например, «3 дня» — увидят только те, кто установил приложение не больше 3 дней назад."));

        // 7. Где
        L.addView(IvaChiefPatch.head(a, "Где показывать"));
        L.addView(chips(a, PLACE_T, new Sel() {
            @Override public boolean on(int i) { return f.places.contains(PLACES[i]); }
            @Override public void tap(int i) { if (!f.places.remove(PLACES[i])) f.places.add(PLACES[i]); }
        }));
        L.addView(small(a, "Можно несколько страниц. «Кабинет» — и у клиентов, и у владельцев проката."));

        // 8. Когда
        L.addView(IvaChiefPatch.head(a, "Когда"));
        L.addView(label(a, "Начало"));
        LinearLayout sr = row(a);
        e.startV = pseudo(a, "");
        e.startX = linkBtn(a, "Убрать");
        e.startX.setTextColor(cGray);
        sr.addView(e.startV, new LinearLayout.LayoutParams(0, -2, 1f));
        sr.addView(e.startX);
        L.addView(sr);
        L.addView(label(a, "Окончание"));
        LinearLayout er = row(a);
        e.endV = pseudo(a, "");
        e.endX = linkBtn(a, "Убрать");
        e.endX.setTextColor(cGray);
        er.addView(e.endV, new LinearLayout.LayoutParams(0, -2, 1f));
        er.addView(e.endX);
        L.addView(er);
        e.startV.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickDate(e, true); } });
        e.endV.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickDate(e, false); } });
        e.startX.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { f.startAt = 0; syncAll(e); } });
        e.endX.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { f.endAt = 0; syncAll(e); } });
        L.addView(label(a, "Часы показа (время телефона)"));
        LinearLayout hr = row(a);
        e.fromV = pseudo(a, "");
        e.toV = pseudo(a, "");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(a, 8);
        hr.addView(e.fromV, new LinearLayout.LayoutParams(0, -2, 1f));
        hr.addView(e.toV, tl);
        TextView allDay = linkBtn(a, "Весь день");
        allDay.setTextColor(cGray);
        hr.addView(allDay);
        L.addView(hr);
        e.fromV.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickHour(e, true); } });
        e.toV.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickHour(e, false); } });
        allDay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { f.hourFrom = 0; f.hourTo = 24; syncAll(e); } });
        L.addView(small(a, "Например, с 22:00 до 06:00 — ночью. С 00:00 до 24:00 — весь день."));
        L.addView(label(a, "Дни недели"));
        L.addView(chips(a, DAY_T, new Sel() {
            @Override public boolean on(int i) { return (f.days & (1 << i)) != 0; }
            @Override public void tap(int i) { f.days ^= (1 << i); }
        }));

        // 9. Как часто
        L.addView(IvaChiefPatch.head(a, "Как часто одному человеку"));
        final Flow freqChips = chips(a, FREQ_T, freqSel(e));
        L.addView(freqChips);
        LinearLayout cf = new LinearLayout(a);
        cf.setOrientation(LinearLayout.VERTICAL);
        cf.addView(label(a, "Не больше, раз (0 — без ограничения)"));
        e.maxShows = field(a, "0", String.valueOf(Math.max(0, f.maxShows)));
        e.maxShows.setInputType(InputType.TYPE_CLASS_NUMBER);
        limit(e.maxShows, 4);
        cf.addView(e.maxShows);
        cf.addView(label(a, "Перерыв между показами, часов (0 — без перерыва)"));
        e.gap = field(a, "0", String.valueOf(Math.max(0, f.gapHours)));
        e.gap.setInputType(InputType.TYPE_CLASS_NUMBER);
        limit(e.gap, 4);
        cf.addView(e.gap);
        e.customFreq = cf;
        cf.setVisibility(freqPreset(f.maxShows, f.gapHours) == 3 ? View.VISIBLE : View.GONE);
        L.addView(cf);
        L.addView(small(a, "Например, «не больше 3 раз, перерыв 24 часа». Баннер считается один раз за заход на страницу."));
        LinearLayout swc = IvaChiefPatch.card(a);
        e.hideOnClose = IvaStaffPatch.sw(a, swc, "Закрыл — больше не показывать", "Нажал крестик — эта реклама ему больше не покажется",
            f.hideOnClose, cText, cGray, cOrange);
        e.priority = IvaStaffPatch.sw(a, swc, "Важная", "Если подходит несколько реклам — сначала эта", f.priority > 0, cText, cGray, cOrange);
        L.addView(swc);

        // 10. Маркировка
        L.addView(IvaChiefPatch.head(a, "Маркировка (по желанию)"));
        LinearLayout mc = IvaChiefPatch.card(a);
        e.adLabel = IvaStaffPatch.sw(a, mc, "Пометка «Реклама»", "Под рекламой: «Реклама · рекламодатель · ИНН · erid»", f.adLabel, cText, cGray, cOrange);
        L.addView(mc);
        L.addView(label(a, "Рекламодатель"));
        e.advertiser = field(a, "Например: ИП Иванов И. И.", f.advertiser);
        limit(e.advertiser, 200);
        L.addView(e.advertiser);
        L.addView(label(a, "ИНН рекламодателя"));
        e.inn = field(a, "10 или 12 цифр", f.inn);
        e.inn.setInputType(InputType.TYPE_CLASS_NUMBER);
        limit(e.inn, 12);
        L.addView(e.inn);
        L.addView(label(a, "erid (токен)"));
        e.erid = field(a, "Выдаёт оператор рекламных данных (ОРД)", f.erid);
        limit(e.erid, 100);
        L.addView(e.erid);
        L.addView(small(a, "Чужую рекламу по закону (ст. 18.1 ФЗ «О рекламе») нужно маркировать: пометка «Реклама», рекламодатель "
            + "и erid, полученный до показа. О своих услугах в своём приложении — обычно не нужно; если сомневаетесь — уточните у юриста."));

        // 11. Запуск
        L.addView(IvaChiefPatch.head(a, "Запуск"));
        L.addView(chips(a, new String[]{"Скрыта", "Пробная — только мне", "Показывать всем"}, new Sel() {
            @Override public boolean on(int i) { return i == (!f.active ? 0 : f.testOnly ? 1 : 2); }
            @Override public void tap(int i) { f.active = i > 0; f.testOnly = i == 1; }
        }));
        L.addView(small(a, "«Пробная» видна только вам и сразу — без учёта «Кому» и «Как часто», так удобно проверить вид и страницы."));

        e.err = text(a, "", 13f, color(a, "red", 0xffff6b6b), false);
        e.err.setPadding(dp(a, 2), dp(a, 14), 0, 0);
        e.err.setVisibility(View.GONE);
        L.addView(e.err);
        TextView prev = IvaPickupPatch.addButton(a, cOrange);
        prev.setText("Превью");
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = dp(a, 14);
        L.addView(prev, pl);
        e.saveBtn = IvaPickupPatch.addButton(a, cOrange);
        e.saveBtn.setText("Сохранить");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(a, 10);
        L.addView(e.saveBtn, sl);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                collect(e);
                IvaPickupPatch.hideKb(a, v);
                if (f.img.isEmpty() && f.headline.trim().isEmpty() && f.body.trim().isEmpty()) { IvaPickupPatch.setErr(e.err, "Добавьте картинку, заголовок или текст"); return; }
                IvaPickupPatch.setErr(e.err, "");
                show(a, f.copy(), true, null);
            }
        });
        e.saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { IvaPickupPatch.hideKb(a, v); save(e); }
        });

        e.refreshAll = new Runnable() {
            @Override public void run() {
                fillChips(a, freqChips, FREQ_T, freqSel(e));
                fillChips(a, tabChips, TAB_T, new Sel() {
                    @Override public boolean on(int i) { return TABS[i].equals(f.btnValue); }
                    @Override public void tap(int i) { f.btnValue = TABS[i]; }
                });
            }
        };
        syncAll(e);
        collect(e);
        e.initial = saveBody(f);

        // «Назад» с несохранёнными изменениями — спросить; закрыли без сохранения — убрать загруженные картинки
        View back = backView(e.p);
        final Runnable tryClose = new Runnable() {
            @Override public void run() {
                collect(e);
                if (e.saved || saveBody(f).equals(e.initial)) { e.p.d.dismiss(); return; }
                final String[] items = {"Выйти без сохранения"};
                IvaClientPatch.styledChoice(a, "Изменения не сохранены", "Если выйти, изменения пропадут.", items, new IvaClientPatch.Choice() {
                    @Override public void pick(int i) { if (i == 0) e.p.d.dismiss(); }
                });
            }
        };
        if (back != null) back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tryClose.run(); } });
        e.p.d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface di, int code, android.view.KeyEvent ev) {
                if (code != android.view.KeyEvent.KEYCODE_BACK) return false;
                if (ev.getAction() == android.view.KeyEvent.ACTION_UP) tryClose.run();
                return true;
            }
        });
        e.p.d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) {
                for (String u : e.uploaded) if (!e.saved || !u.equals(f.img)) removeImage(a, u);
            }
        });
    }

    static Sel freqSel(final Ed e) {
        final Ad f = e.f;
        return new Sel() {
            @Override public boolean on(int i) {
                return e.customFreq != null && e.customFreq.getVisibility() == View.VISIBLE ? i == 3 : freqPreset(f.maxShows, f.gapHours) == i;
            }
            @Override public void tap(int i) {
                int[] fr = freqOf(i);
                if (fr != null) { f.maxShows = fr[0]; f.gapHours = fr[1]; e.customFreq.setVisibility(View.GONE); }
                else {
                    e.maxShows.setText(String.valueOf(Math.max(0, f.maxShows)));
                    e.gap.setText(String.valueOf(Math.max(0, f.gapHours)));
                    e.customFreq.setVisibility(View.VISIBLE);
                }
            }
        };
    }

    static int idx2(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    /** «‹ Назад» страницы IvaChiefPatch.page — первый элемент корня. */
    static View backView(IvaChiefPatch.Page p) {
        try {
            View sc = (View) p.list.getParent();
            ViewGroup root = (ViewGroup) sc.getParent();
            View b = root.getChildAt(0);
            return b instanceof TextView ? b : null;
        } catch (Throwable t) { return null; }
    }

    static void save(final Ed e) {
        final Activity a = e.a;
        if (e.busy) return;
        collect(e);
        if (e.uploading) { IvaPickupPatch.setErr(e.err, "Подождите — картинка ещё загружается"); return; }
        if (e.customFreq.getVisibility() == View.VISIBLE && (e.f.maxShows < 0 || e.f.gapHours < 0)) {
            IvaPickupPatch.setErr(e.err, "«Как часто» — укажите числа");
            return;
        }
        String er = formErr(e.f);
        if (!er.isEmpty()) { IvaPickupPatch.setErr(e.err, er); return; }
        IvaPickupPatch.setErr(e.err, "");
        e.busy = true;
        e.saveBtn.setText("Сохраняем…");
        rpc(a, SAVE_RPC, saveBody(e.f), new Done() {
            @Override public void ok(String body) {
                e.busy = false;
                e.saveBtn.setText("Сохранить");
                if (body == null) { IvaPickupPatch.setErr(e.err, "Не сохранилось — попробуйте ещё раз"); return; }
                e.saved = true;
                if (e.orig != null && !e.orig.img.isEmpty() && !e.orig.img.equals(e.f.img) && !usedElsewhere(e.c, e.orig.img, e.orig.id))
                    removeImage(a, e.orig.img);
                e.p.d.dismiss();
                toast(a, !e.f.active ? "Сохранено — реклама скрыта" : e.f.testOnly ? "Сохранено — пробная, видите только вы"
                    : "Сохранено — уже показывается тем, кому настроена");
                changed(e.c);
            }
        });
    }
}

// IvaSearchPatch — поиск инструмента на главной (10.45, тестовая сборка).
//
// Что делает (задача владельца 12.10.2026):
//  1) Над строкой поиска — строка «Ваш город  ·  Ростов-на-Дону ▾». Нажатие открывает выбор города
//     с поиском по названию и подсказками (города, где есть инструменты, — с настоящим числом).
//  2) Город из профиля показывается выбранным при запуске (тот же порядок, что на сайте: таблица
//     customer_profiles → user_metadata.city → город из адреса профиля). Если города нет — «Выберите город»
//     и показывается весь каталог. Ручной выбор хранится в штатных настройках приложения (SharedPreferences
//     «prokat_session») и профиль не меняет.
//  3) Слева — тонкая ручка в отдельной полосе (отступ сетки слева увеличен, карточки ручку не задевают).
//     Потянуть вправо или нажать — открывается панель фильтров; влево, нажатие, «✕», затемнение или
//     системная «Назад» — закрывается. Вертикальное движение по ручке передаётся списку (прокрутка не ломается).
//  4) Фильтры — только по данным, которые уже есть в каталоге приложения: категория и подкатегория (общие
//     со штатной сеткой категорий), цена за сутки (price), доставка (delivery), состояние («Состояние: …»
//     в условиях объявления — так пишет форма сайта). Внизу закреплены «Сбросить» и «Показать N инструментов»,
//     N — настоящий подсчёт по каталогу с учётом поиска, категории, города и фильтров.
//
// Как устроено без новых точек вставки в чужой код: всё ставится из уже существующей точки — конца штатного
// showTab (IvaPatch.onTab). Штатный renderFeed показывает список из поля tools, поэтому на время действия
// города и фильтров в tools лежит отфильтрованный список, а полный хранится здесь (fullTools). Поле tools,
// кроме renderFeed, читает только renderSuggest (подсказки поиска — им тоже нужен город). Раздел логотипа
// «Избранное / Мои инструменты» берёт полный список через allTools().
// Свежий каталог (штатный loadCatalog кладёт в tools новый список и перерисовывает ленту) замечается по
// добавлению карточек в feedGrid (OnHierarchyChangeListener) — тогда фильтр применяется снова.
//
// Чистая логика (города, совпадения, подсчёт, подписи) — без Android и org.json: её проверяет IvaPatchTest.
package ru.prokatnvsk.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public final class IvaSearchPatch {

    // ── Разметка ──
    static final int BAR_DP = 22;            // 10.46: полоса с ручкой внизу, над нижним меню; лента кончается над ней
    static final int CITY_ROW_DP = 35;       // 10.51: строка «Ваш город» 35dp (10.49 — 34, до того 40)
    static final int CITY_BTN_DP = 31;       // 10.51: кнопки города и «Фильтр» 31dp (10.49 — 30)
    static final int SEARCH_DP = 38;         // 10.51: строка поиска 38dp (10.50 — 37, 10.49 — 36, штатно 44)
    static final int SHEET_BTN_DP = 48;      // 10.51: «Сбросить» и «Показать N» в шторке 48dp (было 50)
    static final int PRICE_FIELD_DP = 46;    // 10.51: поля цены «от» / «до» 46dp (было 48)
    static final int BG_RADIUS_DP = 16;      // 10.52: нижние углы картинки-шапки (как штатная виньетка 10.20); верхние — прямые
    static final float SHEET_MAX = 0.85f;    // шторка — 85% области страниц (10.48: вернули как в 10.46 по решению владельца)
    static final String CITY_ROW_TAG = "iva_search_city_row";
    static final String CHIPS_TAG = "iva_search_chips";
    static final String FILTER_BTN_TAG = "iva_search_filter_btn";   // 10.49: «Фильтр» справа от города
    static final String HANDLE_TAG = "iva_search_handle";
    static final String PANEL_TAG = "iva_search_panel";

    // ── Настройки (штатный файл приложения prokat_session; штатный выход его очищает) ──
    static final String PREFS = "prokat_session";
    static final String K_CITY = "iva_search_city";           // есть ключ — город выбран; "" — «Все города»
    static final String K_SEEN = "iva_search_profile_city";   // почта + город профиля, уже применённый к поиску

    static final int COND_NEW = 1, COND_GOOD = 2, COND_WORK = 4;
    static final String[] COND_TITLES = {"Новый", "Хорошее", "Рабочее"};
    static final int[] COND_BITS = {COND_NEW, COND_GOOD, COND_WORK};

    static final String NB = "\u00a0";
    static final String DEFAULT_CITY = "Новочеркасск";   // как на сайте (assets/sb.js toolToAd): старые объявления без адреса

    // Тот же список, что CITIES в assets/data.js (сверяет tests/apk-build-guard.cjs).
    static final String[] CITIES = {
        "Новочеркасск", "Ростов-на-Дону", "Аксай", "Шахты", "Персиановский", "Батайск", "Таганрог", "Новошахтинск", "Волгодонск", "Азов",
        "Каменск-Шахтинский", "Гуково", "Сальск", "Донецк", "Белая Калитва", "Красный Сулин", "Миллерово", "Зерноград", "Морозовск", "Зверево",
        "Пролетарск", "Цимлянск", "Семикаракорск", "Константиновск", "Матвеев Курган", "Чалтырь", "Орловский", "Зимовники", "Кагальницкая", "Тацинская",
        "Обливская", "Вешенская", "Багаевская", "Егорлыкская", "Целина", "Краснодар", "Сочи", "Новороссийск", "Анапа", "Геленджик",
        "Туапсе", "Армавир", "Кропоткин", "Тихорецк", "Ейск", "Крымск", "Славянск-на-Кубани", "Лабинск", "Белореченск", "Майкоп",
        "Ставрополь", "Пятигорск", "Кисловодск", "Ессентуки", "Минеральные Воды", "Москва", "Санкт-Петербург", "Новосибирск", "Екатеринбург", "Казань",
        "Нижний Новгород", "Челябинск", "Самара", "Омск", "Уфа", "Красноярск", "Воронеж", "Пермь", "Волгоград", "Саратов",
        "Тюмень", "Тольятти", "Ижевск", "Барнаул", "Ульяновск", "Иркутск", "Хабаровск", "Ярославль", "Владивосток", "Махачкала",
        "Томск", "Оренбург", "Кемерово", "Рязань", "Астрахань", "Пенза", "Липецк", "Калининград", "Тула", "Курск",
        "Севастополь", "Тверь", "Магнитогорск", "Иваново", "Брянск", "Белгород", "Владимир", "Архангельск", "Чита", "Калуга",
        "Смоленск", "Волжский", "Курган", "Орёл", "Череповец", "Вологда", "Владикавказ", "Мурманск", "Тамбов", "Грозный",
        "Кострома", "Петрозаводск", "Йошкар-Ола", "Сыктывкар", "Дзержинск", "Орск", "Нижнекамск", "Братск", "Благовещенск", "Энгельс",
        "Ангарск", "Королёв", "Великий Новгород", "Мытищи", "Старый Оскол", "Люберцы", "Псков", "Бийск", "Южно-Сахалинск", "Прокопьевск",
        "Подольск", "Северодвинск", "Абакан", "Норильск", "Сызрань", "Симферополь", "Ялта", "Евпатория", "Керчь",
    };

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 1. Чистая логика (проверяется в IvaPatchTest без телефона)
    // ════════════════════════════════════════════════════════════════════════════════════════════

    static final int CI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    static final Pattern P_SPLIT = Pattern.compile("[,;\\n]");
    static final Pattern P_STRIP = Pattern.compile("^(?:город\\s+|г[.\\s]+|пос[.\\s]+|пос[её]лок\\s+|пгт[.\\s]+|село\\s+|деревня\\s+|д\\.\\s*|с\\.\\s*)", CI);
    static final Pattern P_MARKED = Pattern.compile("^(?:г[.\\s]|город\\s|пос[.\\s]|пос[её]лок\\s|пгт[.\\s]|село\\s|деревня\\s)", CI);
    static final Pattern P_MUNICIPAL = Pattern.compile("сельсовет|муниципальн|городск(?:ой|ое) (?:округ|поселение)|сельское поселение", CI);
    static final Pattern P_COUNTRY = Pattern.compile("^(?:Россия|Российская Федерация)$", CI);
    static final Pattern P_REGION = Pattern.compile("(?:область|обл\\.?|район|р-н|край|республика|округ)(?:\\s|$)", CI);
    static final Pattern P_STREET_START = Pattern.compile("^(?:ул[.\\s]|улица\\s|проспект\\s|пр-т\\s|переулок\\s|пер[.\\s]|шоссе\\s|набережная\\s|дом\\s|д\\.\\s*\\d)", CI);
    static final Pattern P_STREET_SPLIT = Pattern.compile("\\s+(?:ул\\.?|улица|проспект|пр-т|переулок|пер\\.?|шоссе|набережная|дом)\\s", CI);
    static final Pattern P_STREET_WORD = Pattern.compile("(?:^|\\s)(?:улица|ул\\.?|проспект|переулок|шоссе|набережная|дом)(?:\\s|$)", CI);
    static final Pattern P_RU_NAME = Pattern.compile("^[А-ЯЁа-яё .()—–-]+$");
    static final Pattern P_CYR = Pattern.compile("[а-яё]", CI);
    static final Pattern P_LAT = Pattern.compile("[a-z]", CI);
    static final Pattern P_LETTER = Pattern.compile("[а-яёa-z-]", CI);
    static final Pattern P_ADDR_MARK = Pattern.compile("(?:^|[,;]\\s*)(?:г[.\\s]|город\\s|пос[её]лок\\s|пгт[.\\s]|село\\s|деревня\\s)", CI);
    static final Pattern P_ADDR_STREET = Pattern.compile("^(?:ул[.\\s]|улица\\s|проспект\\s|пр-т\\s|переулок\\s|пер[.\\s]|шоссе\\s|набережная\\s)", CI);
    static final Pattern P_ADDR_WORD = Pattern.compile("\\s(?:ул\\.?|улица|проспект|переулок|шоссе|дом)\\s", CI);
    static final Pattern P_TERMS_ADDR = Pattern.compile("Адрес выдачи:\\s*(.+)", CI);
    static final Pattern P_COND_NEW = Pattern.compile("Состояние:\\s*Новый", CI);
    static final Pattern P_COND_GOOD = Pattern.compile("Состояние:\\s*Хорошее", CI);
    static final Pattern P_COND_WORK = Pattern.compile("Состояние:\\s*Рабочее", CI);

    /** Пробелы (в т.ч. неразрывные) схлопываются, края обрезаются. null → "". */
    static String text(String s) {
        if (s == null) return "";
        // Как JS /\s+/ у сайта: кроме ASCII-пробелов — неразрывный и прочие пробелы Юникода (из скопированных адресов).
        return s.replaceAll("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+", " ").trim();
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(new Locale("ru")); }

    /** Населённый пункт из строки — перенос IvaGeo.city (assets/geo.js): улицы и районы не принимаются. */
    static String city(String raw) {
        String s = text(raw);
        if (s.isEmpty()) return "";
        String[] split = P_SPLIT.split(s);
        List<String> parts = new ArrayList<String>();
        for (String x : split) parts.add(x.trim());
        List<String> order = new ArrayList<String>();
        for (String x : parts) if (P_MARKED.matcher(x).find()) order.add(x);
        order.addAll(parts);
        for (String part : order) {
            String p = P_STRIP.matcher(part).replaceFirst("").trim();
            if (P_MUNICIPAL.matcher(p).find()) continue;
            if (p.isEmpty() || P_COUNTRY.matcher(p).find() || P_REGION.matcher(p).find()) continue;
            if (P_STREET_START.matcher(p).find()) continue;
            String pl = lower(p);
            for (String c : CITIES) {
                String cl = lower(c);
                if (pl.startsWith(cl) && (pl.length() == cl.length() || !P_LETTER.matcher(String.valueOf(p.charAt(cl.length()))).matches()))
                    return c;
            }
            p = P_STREET_SPLIT.split(p, 2)[0].trim();
            if (P_STREET_WORD.matcher(p).find()) continue;
            if (p.length() >= 2 && p.length() <= 100 && P_CYR.matcher(p).find() && !P_LAT.matcher(p).find() && P_RU_NAME.matcher(p).matches())
                return p;
        }
        return "";
    }

    /** Город из полного адреса — перенос IvaGeo.addressCity: «ул. Ленина, 5» без города даёт "". */
    static String addressCity(String raw) {
        String s = text(raw);
        String c = city(s);
        if (c.isEmpty()) return "";
        if (P_ADDR_MARK.matcher(s).find()) return c;
        if (P_ADDR_STREET.matcher(s).find()) return "";
        for (String k : CITIES) if (lower(k).equals(lower(c))) return c;
        if (s.contains(",") || s.contains(";") || P_ADDR_WORD.matcher(s).find()) return c;
        return "";
    }

    /** Адрес выдачи: отдельное поле или строка «Адрес выдачи: …» в условиях (старые объявления). */
    static String pickupAddress(String pickupAddress, String terms) {
        String a = pickupAddress == null ? "" : pickupAddress.trim();
        if (!a.isEmpty()) return a;
        Matcher m = P_TERMS_ADDR.matcher(terms == null ? "" : terms);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * Город объявления — ровно как на сайте (assets/sb.js toolToAd):
     * pickup_city → если есть адрес выдачи, город из него (не нашёлся — "" : город неизвестен, не придумываем) →
     * адреса нет совсем — Новочеркасск.
     */
    static String toolCity(String pickupCity, String pickupAddress, String terms) {
        String c = city(pickupCity);
        if (!c.isEmpty()) return c;
        String addr = pickupAddress(pickupAddress, terms);
        if (!addr.isEmpty()) return addressCity(addr);
        return DEFAULT_CITY;
    }

    /** Ключ для сравнения городов — как cityKey в assets/app.js. */
    static String cityKey(String raw) {
        String s = lower(city(raw)).replace('ё', 'е');
        s = s.replaceFirst("^(?:город\\s+|г\\.\\s*|г\\s+)", "");
        s = s.replaceAll("[–—]", "-").replaceAll("\\s*-\\s*", "-").replaceAll("\\s+", " ");
        return s.trim();
    }

    static boolean cityMatches(String a, String b) {
        String ka = cityKey(a);
        return !ka.isEmpty() && ka.equals(cityKey(b));
    }

    /** Состояние из условий объявления. 0 — не указано (такие объявления не попадают под фильтр состояния). */
    static int condFromTerms(String terms) {
        String t = terms == null ? "" : terms;
        if (P_COND_NEW.matcher(t).find()) return COND_NEW;
        if (P_COND_WORK.matcher(t).find()) return COND_WORK;
        if (P_COND_GOOD.matcher(t).find()) return COND_GOOD;
        return 0;
    }

    /** Те же условия, что в штатном renderFeed (+ inCat): аренда, категория, подкатегория, строка поиска. */
    static boolean nativeMatch(String tStatus, String cat, List<String> cats, String sub, String name, String descr,
                               String catMode, String curCat, String curSub, String query) {
        if ("rented".equals(tStatus)) return false;
        boolean catOn = "cat".equals(catMode);
        if (catOn && curCat != null && !curCat.isEmpty()) {
            boolean in = curCat.equals(cat == null ? "" : cat);
            if (!in && cats != null) for (String c : cats) if (curCat.equals(c)) { in = true; break; }
            if (!in) return false;
        }
        if (catOn && curSub != null && !curSub.isEmpty() && !curSub.equals(sub == null ? "" : sub)) return false;
        String hay = ((name == null ? "" : name) + " " + (descr == null ? "" : descr)).toLowerCase();
        return query == null || query.isEmpty() || hay.contains(query);
    }

    /** Город, цена за сутки, доставка, состояние. selCity "" — все города; from/to < 0 — без границы; conds 0 — любое. */
    static boolean extraMatch(String toolCity, double price, boolean delivery, int cond,
                              String selCity, long from, long to, boolean onlyDelivery, int conds) {
        if (selCity != null && !selCity.isEmpty() && !cityMatches(toolCity, selCity)) return false;
        long[] r = normRange(from, to);
        // Цена 0 / не указана — под границы цены не подходит (иначе «до 500» показывало бы объявления без цены).
        if ((r[0] >= 0 || r[1] >= 0) && !(price > 0)) return false;
        if (r[0] >= 0 && price < r[0]) return false;
        if (r[1] >= 0 && price > r[1]) return false;
        if (onlyDelivery && !delivery) return false;
        if (conds != 0 && (cond & conds) == 0) return false;
        return true;
    }

    /** Число из поля цены: только цифры; пусто → -1. */
    static long priceValue(String s) {
        String d = s == null ? "" : s.replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 9) return -1;
        try { return Long.parseLong(d); } catch (Throwable e) { return -1; }
    }

    static String money(long v) {
        return String.format(Locale.US, "%,d", v).replace(",", NB);
    }

    static String plural(long n, String one, String few, String many) {
        long m100 = Math.abs(n) % 100, m10 = Math.abs(n) % 10;
        if (m100 >= 11 && m100 <= 14) return many;
        if (m10 == 1) return one;
        if (m10 >= 2 && m10 <= 4) return few;
        return many;
    }

    static String toolsCount(long n) {
        return n + NB + plural(n, "инструмент", "инструмента", "инструментов");
    }

    /** Текст главной кнопки панели. */
    /** Сколько условий выбрано — столько же, сколько снимаемых «пузырей» под поиском. */
    static int activeCount(String cat, String sub, long from, long to, boolean delivery, int conds) {
        int n = 0;
        if (cat != null && !cat.isEmpty()) {
            n++;
            if (sub != null && !sub.isEmpty()) n++;
        }
        if (from >= 0 || to >= 0) n++;
        if (delivery) n++;
        n += Integer.bitCount(conds & 7);
        return n;
    }

    static String filterButtonText(int n) { return n > 0 ? "Фильтр \u00b7 " + n : "Фильтр"; }

    static String showLabel(int n) {
        return n <= 0 ? "Ничего не найдено" : "Показать " + toolsCount(n);
    }

    /** Подпись кнопки города в верхней строке. */
    static String cityButtonText(String sel, boolean chosen) {
        if (!chosen) return "Выберите город";
        return sel == null || sel.isEmpty() ? "Все города" : sel;
    }

    /** Подпись фильтра цены. */
    /** «от» больше «до» — границы меняются местами (а не пустая выдача). -1 — без границы. */
    static long[] normRange(long from, long to) {
        if (from >= 0 && to >= 0 && from > to) return new long[] {to, from};
        return new long[] {from, to};
    }

    /** Какие варианты (категории, подкатегории) показывать: только непустые, плюс выбранный; порядок — штатный. */
    static List<String> facetVisible(List<String> keys, Map<String, Integer> counts, String selected) {
        List<String> out = new ArrayList<String>();
        for (String k : keys) {
            if (k == null || k.isEmpty() || out.contains(k)) continue;
            Integer n = counts == null ? null : counts.get(k);
            if ((n != null && n > 0) || k.equals(selected)) out.add(k);
        }
        return out;
    }

    static final String[] RADIUS_TITLES = {"любой", "5 км", "10 км", "25 км", "50 км"};

    static String priceChip(long from, long to) {
        long[] r = normRange(from, to);
        from = r[0]; to = r[1];
        if (from >= 0 && to >= 0) return money(from) + "–" + money(to) + NB + "₽/сутки";
        if (from >= 0) return "от " + money(from) + NB + "₽/сутки";
        if (to >= 0) return "до " + money(to) + NB + "₽/сутки";
        return "";
    }

    /**
     * Город профиля → выбор поиска (как applyProfileCity в assets/app.js).
     * Возвращает {новое значение K_SEEN, город для поиска или null — выбор не менять}.
     * Новый (или изменённый) город профиля становится выбранным, если в этом запуске город не выбран вручную;
     * уже применённый город профиля не перебивает прежний ручной выбор.
     */
    static String[] profileDecision(String seen, String email, String profileCity, boolean manualThisRun) {
        String p = city(profileCity);
        if (p.isEmpty() || email == null || email.trim().isEmpty()) return new String[] {seen, null};
        String now = email.trim().toLowerCase(Locale.ROOT) + "\n" + cityKey(p);
        boolean same = now.equals(seen);
        return new String[] {now, (!same && !manualThisRun) ? p : null};
    }

    static String searchKey(String s) {
        return lower(text(s)).replace('ё', 'е');
    }

    /**
     * Порядок городов в выборе: город профиля, затем города, где есть инструменты (больше — выше),
     * затем остальные из общего списка. Только подходящие под набранный текст.
     */
    static List<String> pickerCities(String query, final Map<String, Integer> counts, String profileCity) {
        String q = searchKey(query);
        List<String> out = new ArrayList<String>();
        HashSet<String> seen = new HashSet<String>();
        String p = city(profileCity);
        if (!p.isEmpty() && (q.isEmpty() || searchKey(p).contains(q))) { out.add(p); seen.add(cityKey(p)); }
        List<String> withTools = new ArrayList<String>(counts.keySet());
        Collections.sort(withTools, new Comparator<String>() {
            @Override public int compare(String x, String y) {
                int cx = counts.get(x) == null ? 0 : counts.get(x), cy = counts.get(y) == null ? 0 : counts.get(y);
                if (cx != cy) return cy - cx;
                return x.compareTo(y);
            }
        });
        for (String c : withTools) {
            String k = cityKey(c);
            if (k.isEmpty() || seen.contains(k)) continue;
            if (!q.isEmpty() && !searchKey(c).contains(q)) continue;
            out.add(c); seen.add(k);
        }
        for (String c : CITIES) {
            String k = cityKey(c);
            if (seen.contains(k)) continue;
            if (!q.isEmpty() && !searchKey(c).contains(q)) continue;
            out.add(c); seen.add(k);
        }
        return out;
    }

    /** Набранный город, которого нет в списке, — можно выбрать как есть (если это похоже на название города). */
    static String customCity(String query, List<String> list) {
        String c = city(query);
        if (c.length() < 2) return "";
        for (String x : list) if (cityMatches(x, c)) return "";
        return c;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 2. Состояние
    // ════════════════════════════════════════════════════════════════════════════════════════════

    /** Условия панели. Категория — общая со штатной сеткой (curCat/curSub/catMode). */
    static final class Filter {
        String cat = "", sub = "";
        long from = -1, to = -1;
        boolean delivery = false;
        int conds = 0;

        Filter copy() {
            Filter f = new Filter();
            f.cat = cat; f.sub = sub; f.from = from; f.to = to; f.delivery = delivery; f.conds = conds;
            return f;
        }
        boolean extraActive() { return from >= 0 || to >= 0 || delivery || conds != 0; }
        void resetAll() { cat = ""; sub = ""; from = -1; to = -1; delivery = false; conds = 0; }
    }

    static String selCity = "";                 // выбранный город поиска ("" + chosen — «Все города»)
    static boolean cityChosen = false;          // false — «Выберите город»
    static boolean cityLoaded = false;          // прочитан из настроек
    static boolean manualThisRun = false;       // город выбран руками в этом запуске (профиль его не перебивает)
    static String stateEmail = null;            // аккаунт, для которого действует состояние (null — гость)
    static String profileCity = "";
    static String profileEmail = null;          // для какого аккаунта уже узнали город профиля
    static volatile boolean profileLoading = false;

    static Filter applied = new Filter();       // применённые цена/доставка/состояние (категория — в штатных полях)
    static Filter draft = null;                 // правка в открытой панели

    static ArrayList fullTools = null;          // полный каталог (как его загрузил штатный loadCatalog)
    static ArrayList appliedTools = null;       // то, что сейчас лежит в tools вместо полного (null — полный)

    static WeakReference<Activity> act;          // окно, в которое поставлены строка города, ручка и панель
    static boolean installing = false;
    static boolean homeVisible = false;
    static boolean catalogCheckPosted = false;

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 3. Подключение (из IvaPatch.onTab — конец штатного showTab)
    // ════════════════════════════════════════════════════════════════════════════════════════════

    public static void onShowTab(final Activity a, String tab) {
        final boolean home = "home".equals(tab);
        homeVisible = home;
        try {
            if (!ready(a)) {
                if (installing) return;
                installing = true;
                // После текущего showTab (при запуске он идёт ещё внутри onCreate) — отдельным шагом главного потока.
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        installing = false;
                        try { install(a); } catch (Throwable ignore) {}
                        try { syncVisibility(a, homeVisible); } catch (Throwable ignore) {}
                        try { if (homeVisible) onHomeShown(a); } catch (Throwable ignore) {}
                    }
                });
                return;
            }
            syncVisibility(a, home);
            if (home) onHomeShown(a);
        } catch (Throwable ignore) {
            // поиск — дополнение: штатная главная работает и без него
        }
    }

    /** 10.50: плитки категорий на главной скрыты. Штатный код видимость сетки не меняет — достаточно одного раза,
     * но при каждом показе главной повторяем (дёшево и надёжно). */
    static void hideCatGrid(Activity a) {
        Object cg = IvaClientPatch.get(a, "catGrid");
        if (cg instanceof View && ((View) cg).getVisibility() != View.GONE) ((View) cg).setVisibility(View.GONE);
    }

    /**
     * 10.52: картинка-шапка и ряд выбранных условий — на всю ширину экрана, на любом телефоне.
     * В штатной разметке у картинки уже стоят боковые отступы −14dp (во всю ширину), а у ряда условий — минус
     * боковые поля контейнера, но контейнер главной (поля 14dp) обрезал всё по своим полям. Снимаем обрезку только
     * у этого контейнера; отступы берём из его настоящих полей (а не числом) — так ширина совпадает с экраном
     * при любой плотности и ширине. Верхние углы картинки — прямые (она примыкает к шапке приложения и краям экрана),
     * нижние — скруглены, как в штатной виньетке. Повторный вызов ничего не меняет.
     */
    static void fullBleedHome(Activity a) {
        Object ph = IvaClientPatch.get(a, "pageHome");
        if (!(ph instanceof ScrollView) || ((ScrollView) ph).getChildCount() != 1) return;
        if (!(((ScrollView) ph).getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout content = (LinearLayout) ((ScrollView) ph).getChildAt(0);
        content.setClipToPadding(false);
        Object bg = IvaClientPatch.get(a, "pageBg");
        if (!(bg instanceof View)) return;
        View top = (View) bg;
        ViewParent vp = top.getParent();
        boolean framed = vp instanceof FrameLayout && IvaClientPatch.HOME_VIGNETTE_TAG.equals(((View) vp).getTag());
        if (framed) top = (View) vp;   // картинка в рамке виньетки (IvaPatch.installHomeVignette) — двигаем рамку
        if (top.getParent() != content || !(top.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) top.getLayoutParams();
        int l = -content.getPaddingLeft(), r = -content.getPaddingRight();
        if (lp.leftMargin != l || lp.rightMargin != r) {
            lp.leftMargin = l;
            lp.rightMargin = r;
            top.setLayoutParams(lp);
        }
        if (framed && android.os.Build.VERSION.SDK_INT >= 21 && !(top.getOutlineProvider() instanceof BottomRound)) {
            top.setOutlineProvider(new BottomRound(dp(a, BG_RADIUS_DP)));
            top.setClipToOutline(true);
            top.invalidateOutline();
        }
    }

    /** 10.52: скругление только снизу — прямоугольник продлён вверх на радиус, верхние углы уходят за край. */
    static final class BottomRound extends android.view.ViewOutlineProvider {
        final int radius;
        BottomRound(int radius) { this.radius = radius; }
        @Override public void getOutline(View view, android.graphics.Outline outline) {
            outline.setRoundRect(0, -radius, view.getWidth(), view.getHeight(), radius);
        }
    }

    static void onHomeShown(Activity a) {
        hideCatGrid(a);
        fullBleedHome(a);
        checkAccount(a);
        refreshCityRow(a);
        refreshChips(a);
        resolveProfileCity(a);
    }

    static float density(Activity a) { return a.getResources().getDisplayMetrics().density; }
    static int dp(Activity a, float v) { return Math.round(v * density(a)); }
    static int color(Activity a, String name, int fallback) { return IvaClientPatch.navColor(a, name, fallback); }

    static Drawable drawable(Activity a, String name) {
        try {
            int id = a.getResources().getIdentifier(name, "drawable", a.getPackageName());
            return id == 0 ? null : a.getResources().getDrawable(id);
        } catch (Throwable e) { return null; }
    }

    static ViewGroup pages(Activity a) {
        Object ph = IvaClientPatch.get(a, "pageHome");
        ViewParent p = ph instanceof View ? ((View) ph).getParent() : null;
        return p instanceof FrameLayout ? (FrameLayout) p : null;
    }

    /** Поставлено ли в это окно (после пересоздания Activity всё ставится заново). */
    static boolean ready(Activity a) {
        if (act == null || act.get() != a) return false;
        Object ph = IvaClientPatch.get(a, "pageHome");
        return ph instanceof View && ((View) ph).findViewWithTag(CITY_ROW_TAG) != null;
    }

    /** Один раз на окно: строка города, ряд активных фильтров, полоса под ручку, ручка, панель, слежение за лентой. */
    static void install(final Activity a) {
        if (ready(a)) return;
        Object ph = IvaClientPatch.get(a, "pageHome");
        Object sb = IvaClientPatch.get(a, "searchBox");
        Object fg = IvaClientPatch.get(a, "feedGrid");
        Object sg = IvaClientPatch.get(a, "suggestBox");
        // Сначала проверяем, что разметка именно та, что в 10.14; иначе ничего не трогаем.
        if (!(ph instanceof ScrollView) || !(sb instanceof View) || !(fg instanceof LinearLayout)) return;
        ScrollView home = (ScrollView) ph;
        if (home.getChildCount() != 1 || !(home.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout content = (LinearLayout) home.getChildAt(0);
        ViewParent sp = ((View) sb).getParent();
        if (!(sp instanceof View) || ((View) sp).getParent() != content) return;
        View searchFrame = (View) sp;
        if (!(searchFrame.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        if (((View) fg).getParent() != content) return;
        ViewGroup pages = pages(a);
        if (pages == null) return;
        if (content.findViewWithTag(CITY_ROW_TAG) != null) return;

        act = new WeakReference<Activity>(a);
        appliedTools = null;
        fullTools = null;
        loadCityFromPrefs(a);

        // 1) Полоса под ручку внизу (над нижним меню): главная кончается над ней — ручка не закрывает карточки.
        //    Отступы ленты и картинка-шапка — штатные (как в 10.44).
        if (home.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams hlp = (ViewGroup.MarginLayoutParams) home.getLayoutParams();
            hlp.bottomMargin = dp(a, BAR_DP);
            home.setLayoutParams(hlp);
        }

        // 2) 10.50: строка поиска — на своём штатном месте поверх картинки-шапки (отступ штатный, картинка не двигается),
        //    строка «Ваш город · Фильтр» — под ней, после подсказок поиска (подсказки остаются прямо под строкой поиска).
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) searchFrame.getLayoutParams();
        slp.height = dp(a, SEARCH_DP);   // 10.49–10.51: строка поиска тоньше штатных 44dp
        searchFrame.setLayoutParams(slp);
        View row = buildCityRow(a);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(a, CITY_ROW_DP));
        rlp.topMargin = dp(a, 8);
        int below = (sg instanceof View && ((View) sg).getParent() == content) ? content.indexOfChild((View) sg) : content.indexOfChild(searchFrame);
        content.addView(row, below + 1, rlp);

        // 2а) 10.50: плитки категорий на главной пока убраны (решение владельца). Штатная сетка по-прежнему
        //     заполняется (штатные поля catMode/curCat/curSub работают), но не видна; категория — в шторке фильтров.
        hideCatGrid(a);

        // 2б) 10.52: картинка-шапка — от края до края экрана (остаётся фоном под поиском и строкой города).
        fullBleedHome(a);

        // 3) Ряд активных фильтров — сразу под подсказками поиска (подсказки остаются прямо под строкой поиска).
        //    10.49: один ряд, листается вбок свайпом, без полосы прокрутки; ряд — от края до края экрана
        //    (10.52: виден от края до края — контейнер больше не обрезает по полям, см. fullBleedHome).
        HorizontalScrollView chipsScroll = new HorizontalScrollView(a);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipsScroll.setVisibility(View.GONE);
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setTag(CHIPS_TAG);
        int edge = content.getPaddingLeft();
        chips.setPadding(edge, 0, content.getPaddingRight(), 0);
        chipsScroll.addView(chips, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(a, 10);
        clp.leftMargin = -edge;
        clp.rightMargin = -content.getPaddingRight();
        content.addView(chipsScroll, content.indexOfChild(row) + 1, clp);   // 10.50: под строкой города

        // 4) Панель и ручка — поверх страниц (под нижним меню приложения), только пока открыта главная.
        FrameLayout panel = buildPanelRoot(a);
        pages.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        HandleView handle = new HandleView(a);
        handle.setTag(HANDLE_TAG);
        handle.setVisibility(View.GONE);
        pages.addView(handle, new FrameLayout.LayoutParams(-1, dp(a, BAR_DP), Gravity.BOTTOM));

        // 5) Свежий каталог и пустые надписи штатной ленты.
        ((LinearLayout) fg).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) { onFeedChild(a, child); }
            @Override public void onChildViewRemoved(View parent, View child) {}
        });

        refreshCityRow(a);
        refreshChips(a);
        if (anyActive()) applySearch(a);
    }

    static void syncVisibility(Activity a, boolean home) {
        ViewGroup pages = pages(a);
        if (pages == null) return;
        View handle = pages.findViewWithTag(HANDLE_TAG);
        if (!home) closePanel(a, false);
        if (handle != null) {
            handle.setVisibility(home ? View.VISIBLE : View.GONE);
            if (home) handle.bringToFront();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 4. Каталог и применение условий
    // ════════════════════════════════════════════════════════════════════════════════════════════

    static String js(JSONObject j, String k) {
        if (j == null || !j.has(k) || j.isNull(k)) return "";
        return j.optString(k, "");
    }

    // Город и состояние объявления считаются регулярными выражениями — запоминаем на объект каталога
    // (каталог меняется целиком: новый список — кеш очищается в allTools).
    static final java.util.IdentityHashMap<Object, String> cityCache = new java.util.IdentityHashMap<Object, String>();
    static final java.util.IdentityHashMap<Object, Integer> condCache = new java.util.IdentityHashMap<Object, Integer>();

    static String toolCityOf(JSONObject j) {
        String c = cityCache.get(j);
        if (c == null) {
            c = toolCity(js(j, "pickup_city"), js(j, "pickup_address"), js(j, "terms"));
            cityCache.put(j, c);
        }
        return c;
    }

    static int condOf(JSONObject j) {
        Integer c = condCache.get(j);
        if (c == null) {
            c = condFromTerms(js(j, "terms"));
            condCache.put(j, c);
        }
        return c;
    }

    static List<String> catsOf(JSONObject j) {
        JSONArray arr = j.optJSONArray("cats");
        if (arr == null) return null;
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        return out;
    }

    static boolean nativeMatch(JSONObject j, String catMode, String curCat, String curSub, String query) {
        // Поля читаются ровно как в штатном renderFeed/inCat: optString(k, "") — у Android для JSON null это "null",
        // и штатный поиск ищет по «Название null»; иначе «Показать N» разошлось бы с лентой на латинском запросе.
        return nativeMatch(j.optString("t_status", ""), j.optString("cat", ""), catsOf(j), j.optString("sub", ""),
            j.optString("name", ""), j.optString("descr", ""), catMode, curCat, curSub, query);
    }

    static boolean extraMatch(JSONObject j, String city, Filter f) {
        return extraMatch(toolCityOf(j), j.optDouble("price", 0), j.optBoolean("delivery", false), condOf(j),
            city, f.from, f.to, f.delivery, f.conds);
    }

    static String str(Object o) { return o instanceof String ? (String) o : ""; }

    /** Полный каталог: если штатный код положил в tools новый список — он и есть свежий полный. */
    static ArrayList allTools(Activity a) {
        Object t = IvaClientPatch.get(a, "tools");
        if (t instanceof ArrayList && t != appliedTools && t != fullTools) {
            fullTools = (ArrayList) t;
            cityCache.clear();
            condCache.clear();
        }
        return fullTools == null ? new ArrayList() : fullTools;
    }

    static boolean anyActive() {
        return (cityChosen && !selCity.isEmpty()) || applied.extraActive();
    }

    /** Город и фильтры → поле tools → штатный renderFeed (он добавит поиск, категорию и подкатегорию). */
    static void applySearch(Activity a) {
        ArrayList full = allTools(a);
        String city = cityChosen ? selCity : "";
        if (!anyActive()) {
            appliedTools = null;
            IvaClientPatch.set(a, "tools", full);
        } else {
            ArrayList out = new ArrayList();
            for (Object o : full) if (o instanceof JSONObject && extraMatch((JSONObject) o, city, applied)) out.add(o);
            appliedTools = out;
            IvaClientPatch.set(a, "tools", out);
        }
        callNative(a, "renderFeed");
        refreshChips(a);
        refreshHandle(a);
    }

    static void callNative(Activity a, String name) {
        try {
            Method m = a.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignore) {}
    }

    /** Сколько карточек покажет лента при этих условиях (та же логика, что renderFeed + город и фильтры). */
    static int countFor(Activity a, Filter f, String city) {
        String catMode = f.cat.isEmpty() ? "root" : "cat";
        String query = str(IvaClientPatch.get(a, "query"));
        int n = 0;
        for (Object o : allTools(a)) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if (nativeMatch(j, catMode, f.cat, f.sub, query) && extraMatch(j, city, f)) n++;
        }
        return n;
    }

    /** Текущее состояние (штатная категория + применённые фильтры) — основа для правки в панели. */
    static Filter current(Activity a) {
        Filter f = applied.copy();
        String mode = str(IvaClientPatch.get(a, "catMode"));
        f.cat = "cat".equals(mode) ? str(IvaClientPatch.get(a, "curCat")) : "";
        f.sub = "cat".equals(mode) ? str(IvaClientPatch.get(a, "curSub")) : "";
        return f;
    }

    /** Категория в штатных полях (одна на сетку категорий и панель) + перерисовка сетки. */
    static void setNativeCat(Activity a, String cat, String sub) {
        String c = cat == null ? "" : cat, s = sub == null ? "" : sub;
        IvaClientPatch.set(a, "curCat", c);
        IvaClientPatch.set(a, "curSub", c.isEmpty() ? "" : s);
        IvaClientPatch.set(a, "catMode", c.isEmpty() ? "root" : "cat");
        callNative(a, "renderCatGrid");
    }

    static boolean chipsRefreshPosted = false;

    static void onFeedChild(final Activity a, View child) {
        try {
            // 10.49: категорию могли выбрать в штатной сетке — ряд условий, «Фильтр · N» и ручку обновляем
            // один раз после перерисовки ленты (а не на каждую карточку).
            if (!chipsRefreshPosted) {
                chipsRefreshPosted = true;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        chipsRefreshPosted = false;
                        try { refreshChips(a); refreshHandle(a); } catch (Throwable ignore) {}
                    }
                });
            }
            if (child instanceof TextView) {
                String t = String.valueOf(((TextView) child).getText());
                if (anyActive() && t.startsWith("Каталог пуст") && !allToolsQuiet(a).isEmpty()) {
                    ((TextView) child).setText(cityChosen && !selCity.isEmpty()
                        ? "В городе " + selCity + " с такими условиями инструментов нет. Выберите другой город или сбросьте фильтры."
                        : "С такими условиями инструментов нет. Измените или сбросьте фильтры.");
                } else if (anyActive() && t.startsWith("Ничего не найдено")) {
                    ((TextView) child).setText("Ничего не найдено. Измените поиск, категорию, город или фильтры.");
                }
            }
            Object t = IvaClientPatch.get(a, "tools");
            if (t instanceof ArrayList && t != appliedTools && t != fullTools && !catalogCheckPosted) {
                // Штатный loadCatalog положил свежий каталог и рисует его целиком — после него применяем условия снова.
                catalogCheckPosted = true;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        catalogCheckPosted = false;
                        try {
                            allTools(a);
                            if (anyActive()) applySearch(a);
                            refreshPanel(a);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        } catch (Throwable ignore) {}
    }

    static ArrayList allToolsQuiet(Activity a) {
        Object t = IvaClientPatch.get(a, "tools");
        if (t instanceof ArrayList && t != appliedTools) return (ArrayList) t;
        return fullTools == null ? new ArrayList() : fullTools;
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 5. Город: настройки, профиль, верхняя строка, выбор
    // ════════════════════════════════════════════════════════════════════════════════════════════

    static SharedPreferences prefs(Activity a) {
        Object p = IvaClientPatch.get(a, "prefs");
        if (p instanceof SharedPreferences) return (SharedPreferences) p;
        return a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void loadCityFromPrefs(Activity a) {
        try {
            SharedPreferences p = prefs(a);
            cityChosen = p.contains(K_CITY);
            selCity = cityChosen ? city(p.getString(K_CITY, "")) : "";
        } catch (Throwable e) { cityChosen = false; selCity = ""; }
        cityLoaded = true;
    }

    static void saveCity(Activity a) {
        try { prefs(a).edit().putString(K_CITY, selCity).apply(); } catch (Throwable ignore) {}
    }

    static String currentEmail(Activity a) {
        Object token = IvaClientPatch.get(a, "accessToken");
        if (!(token instanceof String) || ((String) token).trim().isEmpty()) return null;
        String e = str(IvaClientPatch.get(a, "userEmail")).trim().toLowerCase(Locale.ROOT);
        return e.isEmpty() ? null : e;
    }

    /** Выход или вход другим аккаунтом: штатный выход очищает настройки — читаем выбор заново. */
    static void checkAccount(Activity a) {
        String e = currentEmail(a);
        boolean changed = e == null ? stateEmail != null : !e.equals(stateEmail);
        if (!changed && cityLoaded) return;
        stateEmail = e;
        manualThisRun = false;
        profileCity = "";
        profileEmail = null;
        loadCityFromPrefs(a);
        applied = new Filter();
        if (ready(a)) applySearch(a);
    }

    /** Город профиля: customer_profiles (если таблица есть) → user_metadata.city → город из адреса профиля. */
    static void resolveProfileCity(final Activity a) {
        final String email = currentEmail(a);
        if (email == null || email.equals(profileEmail) || profileLoading) return;
        profileLoading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String c = "", addr = "";
                try {
                    JSONObject p = null;
                    if (IvaClientPatch.clientProfileLoaded && email.equalsIgnoreCase(IvaClientPatch.clientProfileEmail))
                        p = IvaClientPatch.clientProfile;
                    if (p == null) {
                        try {
                            String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/get_my_customer_profile", "POST", "{}", true);
                            if (r != null && r[0].startsWith("2")) p = IvaClientPatch.rpcFirst(r[1]);
                        } catch (Throwable ignore) {}
                    }
                    if (p != null) { c = js(p, "city"); addr = js(p, "address"); }
                    if (city(c).isEmpty()) {
                        try {
                            String[] r = IvaClientPatch.apiCall(a, "/auth/v1/user", "GET", null, true);
                            if (r != null && r[0].startsWith("2")) {
                                JSONObject meta = new JSONObject(r[1]).optJSONObject("user_metadata");
                                if (meta != null) c = js(meta, "city");
                            }
                        } catch (Throwable ignore) {}
                    }
                    if (city(c).isEmpty() && !addr.isEmpty()) c = addressCity(addr);
                } catch (Throwable ignore) {}
                final String found = city(c);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        profileLoading = false;
                        try {
                            if (!email.equals(currentEmail(a))) return;   // аккаунт сменился, пока ждали ответ
                            profileEmail = email;
                            profileCity = found;
                            SharedPreferences p = prefs(a);
                            String[] d = profileDecision(p.getString(K_SEEN, ""), email, found, manualThisRun);
                            if (d[0] != null) p.edit().putString(K_SEEN, d[0]).apply();
                            if (d[1] != null && !(cityChosen && cityMatches(selCity, d[1]))) {
                                selCity = d[1];
                                cityChosen = true;
                                saveCity(a);
                                if (ready(a)) applySearch(a);
                            }
                            refreshCityRow(a);
                            refreshPanel(a);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    /** Выбор города пользователем (верхняя строка или панель). Профиль не меняется. */
    static void chooseCity(Activity a, String c) {
        selCity = c == null ? "" : city(c);
        cityChosen = true;
        manualThisRun = true;
        saveCity(a);
        applySearch(a);
        refreshCityRow(a);
        refreshPanel(a);
    }

    static TextView cityButton;
    static TextView cityLabel;
    static TextView filterButton;

    static View buildCityRow(final Activity a) {
        // 10.49: «Ваш город» и кнопка города рядом слева (кнопка меньше), справа — «Фильтр» (открывает шторку).
        LinearLayout row = new LinearLayout(a);
        row.setTag(CITY_ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(a);
        label.setText("Ваш город");
        label.setTextSize(13f);
        label.setTextColor(color(a, "text", 0xfff2f4f8));
        label.setShadowLayer(dp(a, 3), 0, dp(a, 1), 0xaa000000);   // поверх картинки шапки
        label.setSingleLine(true);
        label.setPadding(dp(a, 4), 0, dp(a, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(-2, -2));
        cityLabel = label;

        TextView btn = new TextView(a);
        btn.setTextSize(13f);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setTextColor(color(a, "text", 0xfff2f4f8));
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(a, 12), 0, dp(a, 10), 0);
        Drawable bgs = drawable(a, "bg_search");     // та же «стеклянная» рамка, что у строки поиска
        if (bgs != null) btn.setBackground(bgs);
        else btn.setBackground(pill(a, 0x33ffffff, color(a, "orange", 0xffff8f1f)));
        btn.setClickable(true);
        btn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openCityPicker(a); } });
        // кнопка города — по тексту, но не шире остатка строки (длинное название — с многоточием)
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, dp(a, CITY_BTN_DP), 1f);
        blp.width = 0;
        LinearLayout cityBox = new LinearLayout(a);   // обёртка: кнопка прижата к надписи, свободное место — справа
        cityBox.setOrientation(LinearLayout.HORIZONTAL);
        cityBox.setGravity(Gravity.CENTER_VERTICAL);
        cityBox.addView(btn, new LinearLayout.LayoutParams(-2, dp(a, CITY_BTN_DP)));
        row.addView(cityBox, blp);
        cityButton = btn;

        TextView fb = new TextView(a);
        fb.setTag(FILTER_BTN_TAG);
        fb.setTextSize(13f);
        fb.setTypeface(null, android.graphics.Typeface.BOLD);
        fb.setTextColor(color(a, "text", 0xfff2f4f8));
        fb.setSingleLine(true);
        fb.setGravity(Gravity.CENTER);
        fb.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        Drawable bgf = drawable(a, "bg_search");
        if (bgf != null) fb.setBackground(bgf);
        else fb.setBackground(pill(a, 0x33ffffff, color(a, "orange", 0xffff8f1f)));
        fb.setClickable(true);
        fb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openPanel(a); } });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, dp(a, CITY_BTN_DP));
        flp.leftMargin = dp(a, 8);
        row.addView(fb, flp);
        filterButton = fb;
        refreshFilterButton(a);
        return row;
    }

    /** «Фильтр» справа от города: число применённых условий и оранжевый цвет, если они есть. */
    static void refreshFilterButton(Activity a) {
        TextView b = filterButton;
        if (b == null) return;
        Filter f = current(a);
        int n = activeCount(f.cat, f.sub, f.from, f.to, f.delivery, f.conds);
        b.setText(filterButtonText(n));
        b.setTextColor(n > 0 ? color(a, "orange", 0xffff8f1f) : color(a, "text", 0xfff2f4f8));
        b.setContentDescription(n > 0 ? "Фильтры, выбрано условий: " + n + ". Открыть" : "Открыть фильтры");
    }

    static void refreshCityRow(Activity a) {
        TextView b = cityButton;
        if (b == null) return;
        String t = cityButtonText(selCity, cityChosen);
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(t + "  \u25be");
        s.setSpan(new android.text.style.ForegroundColorSpan(color(a, "orange", 0xffff8f1f)), t.length() + 2, s.length(), 0);
        if (!cityChosen) s.setSpan(new android.text.style.ForegroundColorSpan(color(a, "orange", 0xffff8f1f)), 0, t.length(), 0);
        b.setText(s);
        b.setContentDescription("Ваш город: " + t + ". Изменить");
    }

    static GradientDrawable pill(Activity a, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(a, 20));
        if (stroke != 0) g.setStroke(dp(a, 1), stroke);
        return g;
    }

    /** Города каталога с числом доступных сейчас инструментов (сданные в аренду штатная лента не показывает). */
    static LinkedHashMap<String, Integer> catalogCityCounts(Activity a) {
        LinkedHashMap<String, Integer> byKey = new LinkedHashMap<String, Integer>();
        LinkedHashMap<String, String> names = new LinkedHashMap<String, String>();
        for (Object o : allTools(a)) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if ("rented".equals(js(j, "t_status"))) continue;
            String c = toolCityOf(j), k = cityKey(c);
            if (k.isEmpty()) continue;
            if (!names.containsKey(k)) names.put(k, c);
            Integer n = byKey.get(k);
            byKey.put(k, n == null ? 1 : n + 1);
        }
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> e : byKey.entrySet()) out.put(names.get(e.getKey()), e.getValue());
        return out;
    }

    static int countIn(Map<String, Integer> counts, String c) {
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (cityMatches(e.getKey(), c)) return e.getValue();
        return 0;
    }

    static Dialog cityDialog;

    /** Полноэкранный выбор города: поиск по названию, подсказки с настоящим числом инструментов. */
    static void openCityPicker(final Activity a) {
        try {
            if (cityDialog != null && cityDialog.isShowing()) return;
        } catch (Throwable ignore) {}
        final int cBg = color(a, "bg", 0xff101217), cText = color(a, "text", 0xfff2f4f8),
            cGray = color(a, "gray", 0xff9aa1b0), cGray2 = color(a, "gray2", 0xff6b7080),
            cLine = color(a, "line", 0xff262b35), cOrange = color(a, "orange", 0xffff8f1f);
        final LinkedHashMap<String, Integer> counts = catalogCityCounts(a);
        int total = 0;
        for (Integer n : counts.values()) total += n;
        final int totalAll = total;

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setFocusableInTouchMode(true);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 4), 0, dp(a, 16), 0);
        TextView back = new TextView(a);
        back.setText("\u2190");
        back.setTextSize(24f);
        back.setTextColor(cText);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Назад");
        bar.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        TextView title = new TextView(a);
        title.setText("Ваш город");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(cText);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(a, 56)));

        final EditText q = new EditText(a);
        q.setHint("Начните вводить название города");
        q.setSingleLine(true);
        q.setTextSize(16f);
        q.setTextColor(cText);
        q.setHintTextColor(cGray2);
        q.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        q.setImeOptions(EditorInfo.IME_ACTION_DONE);
        q.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        Drawable fieldBg = drawable(a, "bg_field");
        if (fieldBg != null) q.setBackground(fieldBg);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, dp(a, 48));
        qlp.setMargins(dp(a, 16), 0, dp(a, 16), dp(a, 8));
        root.addView(q, qlp);

        final ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(a, 24));
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);   // системная «Назад» закрывает выбор, город не меняется
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(cBg));
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            try {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.setStatusBarColor(cBg);
                w.setNavigationBarColor(cBg);
            } catch (Throwable ignore) {}
        }
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });

        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override public void run() {
                list.removeAllViews();
                String query = q.getText().toString();
                if (searchKey(query).isEmpty()) {
                    list.addView(cityItem(a, d, "", "Все города", "Весь каталог · " + toolsCount(totalAll),
                        cityChosen && selCity.isEmpty(), cText, cGray, cOrange, cLine));
                }
                List<String> cities = pickerCities(query, counts, profileCity);
                String custom = customCity(query, cities);
                if (!custom.isEmpty()) {
                    list.addView(cityItem(a, d, custom, custom, "Выбрать этот город · инструментов пока нет",
                        false, cText, cGray, cOrange, cLine));
                }
                int shown = 0;
                for (String c : cities) {
                    if (shown++ >= 80) break;
                    int n = countIn(counts, c);
                    String sub = n > 0 ? toolsCount(n) : "Инструментов пока нет";
                    if (!profileCity.isEmpty() && cityMatches(c, profileCity)) sub = "Город из профиля · " + sub;
                    list.addView(cityItem(a, d, c, c, sub, cityChosen && cityMatches(selCity, c), cText, cGray, cOrange, cLine));
                }
                if (cities.isEmpty() && custom.isEmpty()) {
                    TextView none = new TextView(a);
                    none.setText("Город не найден. Проверьте название.");
                    none.setTextColor(cGray);
                    none.setTextSize(14f);
                    none.setPadding(dp(a, 16), dp(a, 18), dp(a, 16), dp(a, 8));
                    list.addView(none);
                }
                TextView note = new TextView(a);
                note.setText("Город влияет только на поиск и запоминается на этом телефоне. Город в профиле не меняется"
                    + (profileCity.isEmpty() ? "." : " (в профиле: " + profileCity + ")."));
                note.setTextColor(cGray2);
                note.setTextSize(12f);
                note.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), 0);
                list.addView(note);
            }
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { try { render[0].run(); scroll.scrollTo(0, 0); } catch (Throwable ignore) {} }
        });
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) { hideKeyboard(a, v); return true; }
        });
        render[0].run();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface x) { if (cityDialog == d) cityDialog = null; }
        });
        cityDialog = d;
        d.show();
    }

    static View cityItem(final Activity a, final Dialog d, final String value, String title, String sub, boolean selected,
                         int cText, int cGray, int cOrange, int cLine) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(a, 56));
        row.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));
        feedback(a, row);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16f);
        t.setTextColor(selected ? cOrange : cText);
        if (selected) t.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(t);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(12f);
        s.setTextColor(cGray);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (selected) {
            TextView mark = new TextView(a);
            mark.setText("\u2713");
            mark.setTextSize(18f);
            mark.setTextColor(cOrange);
            row.addView(mark);
        }
        row.setContentDescription(title + ", " + sub + (selected ? ", выбран" : ""));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { d.dismiss(); } catch (Throwable ignore) {}
                chooseCity(a, value);
            }
        });
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(a);
        line.setBackgroundColor(cLine);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(a, 0.5f)));
        llp.setMargins(dp(a, 16), 0, 0, 0);
        wrap.addView(line, llp);
        return wrap;
    }

    static void feedback(Activity a, View v) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0)
                v.setBackgroundResource(tv.resourceId);
        } catch (Throwable ignore) {}
        v.setClickable(true);
    }

    static void hideKeyboard(Activity a, View v) {
        try {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignore) {}
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 6. Активные фильтры (под строкой поиска и в панели) — каждый убирается отдельно
    // ════════════════════════════════════════════════════════════════════════════════════════════

    static String catTitle(Activity a, String key) {
        Object cats = IvaClientPatch.get(a, "cats");
        if (cats instanceof ArrayList) for (Object o : (ArrayList) cats)
            if (o instanceof JSONObject && key.equals(((JSONObject) o).optString("key"))) return ((JSONObject) o).optString("title", key);
        return key;
    }

    static String subTitle(Activity a, String cat, String key) {
        Object subs = IvaClientPatch.get(a, "subcats");
        if (subs instanceof ArrayList) for (Object o : (ArrayList) subs) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if (cat.equals(j.optString("cat")) && key.equals(j.optString("key"))) return j.optString("title", key);
        }
        return key;
    }

    interface ChipAction { void remove(); }

    static TextView chip(Activity a, String text, boolean on, boolean removable) {
        TextView c = new TextView(a);
        c.setText(removable ? text + "  \u2715" : text);
        c.setTextSize(13f);
        c.setSingleLine(true);
        c.setEllipsize(TextUtils.TruncateAt.END);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinHeight(dp(a, 36));
        c.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        Drawable b = drawable(a, on ? "bg_chip_on" : "bg_chip");
        if (b != null) c.setBackground(b);
        else c.setBackground(pill(a, on ? color(a, "orange", 0xffff8f1f) : color(a, "card", 0xff1a1d24), on ? 0 : 0xff3a4050));
        c.setTextColor(on ? 0xffffffff : color(a, "text", 0xfff2f4f8));
        c.setClickable(true);
        if (removable) c.setContentDescription(text + ". Убрать фильтр");
        return c;
    }

    /** 10.47: выбранное условие в шапке шторки — текст и серый ✕, без фона; высота 32dp (удобно нажать). */
    static TextView plainChip(Activity a, String text) {
        TextView c = new TextView(a);
        android.text.SpannableString s = new android.text.SpannableString(text + "  \u2715");
        s.setSpan(new android.text.style.ForegroundColorSpan(color(a, "gray", 0xff9aa1b0)), text.length(), s.length(), 0);
        c.setText(s);
        c.setTextSize(13f);
        c.setTextColor(color(a, "text", 0xfff2f4f8));
        c.setSingleLine(true);
        c.setEllipsize(TextUtils.TruncateAt.END);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinHeight(dp(a, 32));
        c.setClickable(true);
        c.setContentDescription(text + ". Убрать фильтр");
        feedback(a, c);
        return c;
    }

    /** Подписи и действия активных условий для данного набора (категория, подкатегория, цена, доставка, состояние). */
    static void addActiveChips(final Activity a, ViewGroup box, final Filter f, final Runnable changed) {
        addActiveChips(a, box, f, changed, false);
    }

    static void addActiveChips(final Activity a, ViewGroup box, final Filter f, final Runnable changed, final boolean plain) {
        if (!f.cat.isEmpty()) {
            final boolean hasSub = !f.sub.isEmpty();
            addChip(a, box, catTitle(a, f.cat), new ChipAction() { @Override public void remove() { f.cat = ""; f.sub = ""; } }, changed, plain);
            if (hasSub) addChip(a, box, subTitle(a, f.cat, f.sub), new ChipAction() { @Override public void remove() { f.sub = ""; } }, changed, plain);
        }
        if (f.from >= 0 || f.to >= 0)
            addChip(a, box, priceChip(f.from, f.to), new ChipAction() { @Override public void remove() { f.from = -1; f.to = -1; } }, changed, plain);
        if (f.delivery)
            addChip(a, box, "С доставкой", new ChipAction() { @Override public void remove() { f.delivery = false; } }, changed, plain);
        for (int i = 0; i < COND_BITS.length; i++) {
            final int bit = COND_BITS[i];
            if ((f.conds & bit) != 0)
                addChip(a, box, "Состояние: " + lower(COND_TITLES[i]), new ChipAction() { @Override public void remove() { f.conds &= ~bit; } }, changed, plain);
        }
    }

    static void addChip(Activity a, ViewGroup box, String text, final ChipAction action, final Runnable changed, boolean plain) {
        TextView c = plain ? plainChip(a, text) : chip(a, text, true, true);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.remove(); changed.run(); }
        });
        if (box instanceof LinearLayout) {   // один ряд с прокруткой вбок: шапка шторки (10.48), лента под поиском (10.49)
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (box.getChildCount() > 0) lp.leftMargin = dp(a, plain ? 16 : 6);
            box.addView(c, lp);
        } else box.addView(c);
    }

    /** Ряд под строкой поиска: применённые условия, нажатие убирает одно и сразу обновляет ленту. */
    static void refreshChips(final Activity a) {
        try {
            Object ph = IvaClientPatch.get(a, "pageHome");
            if (!(ph instanceof View)) return;
            refreshFilterButton(a);
            View v = ((View) ph).findViewWithTag(CHIPS_TAG);
            if (!(v instanceof LinearLayout)) return;
            LinearLayout box = (LinearLayout) v;
            box.removeAllViews();
            final Filter f = current(a);
            final String cat0 = f.cat, sub0 = f.sub;
            addActiveChips(a, box, f, new Runnable() {
                @Override public void run() {
                    if (!cat0.equals(f.cat) || !sub0.equals(f.sub)) setNativeCat(a, f.cat, f.sub);
                    applied.from = f.from; applied.to = f.to; applied.delivery = f.delivery; applied.conds = f.conds;
                    applySearch(a);
                }
            });
            // 10.49: кнопки «Фильтры» в ряду больше нет — шторку открывает «Фильтр» справа от города
            View scroller = box.getParent() instanceof View ? (View) box.getParent() : box;
            scroller.setVisibility(box.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        } catch (Throwable ignore) {}
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 7. Ручка и панель фильтров
    // ════════════════════════════════════════════════════════════════════════════════════════════

    static FrameLayout panelRoot;
    static View scrim;
    static LinearLayout sheet;
    static float progress = 0f;          // 0 — закрыта, 1 — открыта
    static ValueAnimator anim;
    static int sheetSize = 0;            // высота шторки, px

    static FrameLayout buildPanelRoot(final Activity a) {
        final FrameLayout root = new FrameLayout(a);
        root.setTag(PANEL_TAG);
        root.setVisibility(View.GONE);
        View s = new View(a);
        s.setBackgroundColor(0xff000000);
        s.setAlpha(0f);
        s.setClickable(true);   // пока панель открыта, карточки под затемнением не нажимаются
        s.setContentDescription("Закрыть фильтры");
        s.setOnTouchListener(new DragListener(a, DragListener.SCRIM));
        s.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closePanel(a, true); } });
        root.addView(s, new FrameLayout.LayoutParams(-1, -1));
        scrim = s;
        LinearLayout sh = new LinearLayout(a);
        sh.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(color(a, "bg", 0xff101217));
        float r = dp(a, 18);
        sbg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sh.setBackground(sbg);
        sh.setClickable(true);
        sh.setFocusableInTouchMode(true);   // фокус на панели — системная «Назад» приходит сюда (см. backKeys)
        if (android.os.Build.VERSION.SDK_INT >= 21) sh.setElevation(dp(a, 12));
        root.addView(sh, new FrameLayout.LayoutParams(-1, dp(a, 400), Gravity.BOTTOM));
        sheet = sh;
        panelRoot = root;
        // Высота шторки — 85% области страниц; с клавиатурой (adjustResize) область меньше — шторка тоже,
        // кнопки внизу остаются над клавиатурой.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int rr, int b, int ol, int ot, int orr, int ob) {
                if (b - t == ob - ot) return;
                v.post(new Runnable() { @Override public void run() { fitSheet(a); } });
            }
        });
        return root;
    }

    static int computeSheetHeight(Activity a) {
        int h = panelRoot != null && panelRoot.getHeight() > 0 ? panelRoot.getHeight() : 0;
        if (h <= 0) {
            ViewGroup pages = pages(a);
            h = pages != null && pages.getHeight() > 0 ? pages.getHeight() : a.getResources().getDisplayMetrics().heightPixels / 2;
        }
        return Math.max(dp(a, 200), Math.round(h * SHEET_MAX));
    }

    static void fitSheet(Activity a) {
        if (sheet == null || panelRoot == null || panelRoot.getVisibility() != View.VISIBLE) return;
        int h = computeSheetHeight(a);
        if (h == sheetSize) return;
        sheetSize = h;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        lp.height = h;
        sheet.setLayoutParams(lp);
        setProgress(a, progress);
    }

    static boolean panelOpen() { return panelRoot != null && panelRoot.getVisibility() == View.VISIBLE && progress > 0f; }

    /** Подготовить панель к показу: правка начинается с текущих условий. */
    static void preparePanel(Activity a) {
        if (panelRoot == null || sheet == null) return;
        if (panelRoot.getVisibility() != View.VISIBLE) {
            draft = current(a);
            sheetSize = computeSheetHeight(a);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
            lp.height = sheetSize;
            sheet.setLayoutParams(lp);
            setProgress(a, 0f);         // шторка внизу, за краем — показ панели не мелькнёт
            // 10.47: сначала панель видима, потом заполнение. В 10.46 было наоборот: refreshPanel (в конце buildSheet)
            // молчит у скрытой панели — категории, числа и надписи кнопок появлялись только после нажатия на «доставку».
            panelRoot.setVisibility(View.VISIBLE);
            panelRoot.bringToFront();   // шторка поверх полосы с ручкой
            buildSheet(a);
            hideKeyboard(a, panelRoot);
        }
    }

    static View handleView(Activity a) {
        ViewGroup pages = pages(a);
        return pages == null ? null : pages.findViewWithTag(HANDLE_TAG);
    }

    static void setProgress(Activity a, float p) {
        progress = Math.max(0f, Math.min(1f, p));
        if (sheet != null) sheet.setTranslationY((1f - progress) * sheetSize);
        if (scrim != null) scrim.setAlpha(0.55f * progress);
    }

    static void openPanel(Activity a) {
        if (!ready(a) || !homeVisible) return;
        preparePanel(a);
        animateTo(a, 1f);
    }

    static void closePanel(Activity a, boolean animate) {
        if (panelRoot == null) return;
        if (!animate) {
            if (anim != null) anim.cancel();
            setProgress(a, 0f);
            finishClose(a);
            return;
        }
        animateTo(a, 0f);
    }

    static void finishClose(Activity a) {
        if (panelRoot != null) panelRoot.setVisibility(View.GONE);
        draft = null;
        try { if (sheet != null) { hideKeyboard(a, sheet); sheet.clearFocus(); } } catch (Throwable ignore) {}
        refreshHandle(a);
    }

    static void animateTo(final Activity a, final float target) {
        if (anim != null) anim.cancel();
        float from = progress;
        ValueAnimator v = ValueAnimator.ofFloat(from, target);
        v.setDuration((long) (60 + 200 * Math.abs(target - from)));
        v.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator x) { setProgress(a, (Float) x.getAnimatedValue()); }
        });
        v.addListener(new AnimatorListenerAdapter() {
            boolean cancelled = false;
            @Override public void onAnimationCancel(Animator x) { cancelled = true; }
            @Override public void onAnimationEnd(Animator x) {
                if (cancelled) return;
                if (target <= 0f) finishClose(a);
                else if (sheet != null) {
                    sheet.requestFocus();
                    refreshHandle(a);
                }
            }
        });
        anim = v;
        v.start();
    }

    static void refreshHandle(Activity a) {
        View h = handleView(a);
        if (h instanceof HandleView) {
            HandleView hv = (HandleView) h;
            hv.active = anyActive() || !current(a).cat.isEmpty();
            hv.open = panelOpen();
            hv.setContentDescription(hv.open ? "Закрыть фильтры" : "Открыть фильтры");
            hv.invalidate();
        }
    }

    /** Полоса над нижним меню с «язычком» по центру: видимая полоска 36×4dp, нажимается вся полоса. */
    static final class HandleView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Activity a;
        boolean active = false, open = false;

        HandleView(final Activity a) {
            super(a);
            this.a = a;
            setClickable(true);
            setFocusable(false);
            setBackgroundColor(color(a, "bg", 0xff101217));
            setContentDescription("Открыть фильтры");
            setOnTouchListener(new DragListener(a, DragListener.HANDLE));
            // TalkBack и другие службы нажимают через performClick — тот же переключатель, что и касание.
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (panelOpen()) closePanel(a, true); else openPanel(a); }
            });
        }

        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = 36 * d, h = 4 * d;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            paint.setColor(active ? color(a, "orange", 0xffff8f1f) : color(a, "gray2", 0xff6b7080));
            c.drawRoundRect(r, h / 2, h / 2, paint);
            if (active) {   // точка — условия поиска включены
                paint.setColor(color(a, "orange", 0xffff8f1f));
                c.drawCircle(r.right + 9 * d, cy, 3 * d, paint);
            }
        }
    }

    /**
     * Перетаскивание по вертикали: вверх — шторка выезжает за пальцем, вниз — уезжает.
     * Короткое нажатие: ручка — открыть/закрыть, затемнение и полоса с «язычком» вверху шторки — закрыть, остальная шапка — ничего.
     */
    static final class DragListener implements View.OnTouchListener {
        static final int HANDLE = 0, SCRIM = 1, SHEET = 2, GRAB = 3;   // GRAB — полоса с «язычком» вверху шторки (с 10.49 тянуть шторку — только за неё)
        static final int NONE = 0, DRAG = 1, IGNORE = 2;
        final Activity a;
        final int kind;
        final int slop;
        int mode = NONE;
        float x0, y0, p0;
        VelocityTracker vt;

        DragListener(Activity a, int kind) {
            this.a = a;
            this.kind = kind;
            this.slop = ViewConfiguration.get(a).getScaledTouchSlop();
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            try { return handle(v, e); } catch (Throwable t) { mode = NONE; return false; }
        }

        boolean handle(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = NONE;
                    x0 = e.getRawX(); y0 = e.getRawY();
                    if (anim != null) anim.cancel();
                    p0 = panelRoot != null && panelRoot.getVisibility() == View.VISIBLE ? progress : 0f;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (mode == NONE) {
                        if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
                            if (kind == HANDLE && !homeVisible) { mode = IGNORE; return true; }
                            mode = DRAG;
                            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                            if (kind == HANDLE) preparePanel(a);
                        } else if (Math.abs(dx) > slop) {
                            mode = IGNORE;   // вбок — не жест шторки (и не нажатие)
                        }
                    }
                    if (mode == DRAG && sheetSize > 0) setProgress(a, p0 - dy / sheetSize);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (mode == DRAG) {
                        float vy = 0;
                        if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); }
                        float fling = 600 * density(a);
                        boolean open = vy < -fling || (vy < fling && progress > 0.5f);
                        animateTo(a, open ? 1f : 0f);
                    } else if (!up || mode == IGNORE) {
                        // Касание отменили (или увели вбок) после того, как оно остановило анимацию, — не оставляем на полпути.
                        if (panelRoot != null && panelRoot.getVisibility() == View.VISIBLE && progress > 0f && progress < 1f)
                            animateTo(a, progress > 0.5f ? 1f : 0f);
                    } else if (kind == HANDLE) {
                        if (panelOpen()) closePanel(a, true); else openPanel(a);
                    } else if (kind == SCRIM || kind == GRAB) {
                        closePanel(a, true);
                    }
                    mode = NONE;
                    if (vt != null) { vt.recycle(); vt = null; }
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    // ── Содержимое панели ──
    static Flow catBox, subBox, condBox;
    static LinearLayout panelChips;              // 10.48: ряд выбранных условий в шапке шторки
    static HorizontalScrollView panelChipsScroll;
    static TextView subTitleView, condTitleView, condNote, priceHint, cityLine, showBtn, resetBtn;
    static CheckBox deliveryBox;
    static EditText fromEdit, toEdit;
    static boolean syncing = false;

    static TextView sectionTitle(Activity a, String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextSize(13f);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setTextColor(color(a, "gray", 0xff9aa1b0));
        v.setPadding(0, dp(a, 18), 0, dp(a, 8));
        return v;
    }

    /** Системная «Назад», пока фокус в панели (на самой панели или в поле цены), закрывает панель. */
    static void backKeys(final Activity a, View v) {
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, KeyEvent e) {
                if (code != KeyEvent.KEYCODE_BACK || !panelOpen()) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) closePanel(a, true);
                return true;
            }
        });
    }

    static void buildSheet(final Activity a) {
        final LinearLayout sh = sheet;
        sh.removeAllViews();
        final int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0),
            cGray2 = color(a, "gray2", 0xff6b7080), cLine = color(a, "line", 0xff262b35), cOrange = color(a, "orange", 0xffff8f1f);
        backKeys(a, sh);

        // 10.48: полоса с «язычком» во всю ширину (22dp): нажатие закрывает шторку, потянуть вниз — тоже.
        // 10.49: закреплена только эта полоса — шапка («Фильтры · город ▾ · ✕» и выбранное) прокручивается вместе
        // с фильтрами (решение владельца), а закрыть шторку можно всегда, даже пролистав вниз.
        FrameLayout grabBar = new FrameLayout(a);
        grabBar.setContentDescription("Закрыть фильтры");
        grabBar.setClickable(true);
        grabBar.setOnTouchListener(new DragListener(a, DragListener.GRAB));
        grabBar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closePanel(a, true); } });
        View grab = new View(a);
        grab.setBackground(pill(a, cGray2, 0));
        grabBar.addView(grab, new FrameLayout.LayoutParams(dp(a, 36), dp(a, 4), Gravity.CENTER));
        sh.addView(grabBar, new LinearLayout.LayoutParams(-1, dp(a, 22)));

        ScrollView sc = new ScrollView(a);
        sc.setFillViewport(true);
        LinearLayout scroll = new LinearLayout(a);   // шапка + фильтры — одна прокручиваемая колонка
        scroll.setOrientation(LinearLayout.VERTICAL);
        sc.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
        sh.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Шапка (10.47 — компактная): одна строка «Фильтры · город ▾ · ✕», под ней выбранное — текстом с ✕.
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(a, 16), 0, dp(a, 2), 0);
        TextView title = new TextView(a);
        title.setText("Фильтры");
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(cText);
        head.addView(title, new LinearLayout.LayoutParams(-2, -2));
        // Город — тот же выбор, что в верхней строке (второго независимого выбора нет)
        TextView cl = new TextView(a);
        cl.setTextSize(13f);
        cl.setTextColor(cGray);
        cl.setSingleLine(true);
        cl.setEllipsize(TextUtils.TruncateAt.END);
        cl.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        cl.setMinHeight(dp(a, 40));
        cl.setPadding(dp(a, 12), 0, dp(a, 6), 0);
        feedback(a, cl);
        cl.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openCityPicker(a); } });
        head.addView(cl, new LinearLayout.LayoutParams(0, -2, 1f));
        cityLine = cl;
        TextView close = new TextView(a);
        close.setText("\u2715");
        close.setTextSize(18f);
        close.setTextColor(cText);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Закрыть фильтры");
        feedback(a, close);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closePanel(a, true); } });
        head.addView(close, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
        scroll.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Выбранные условия — простым текстом с ✕, каждое убирается отдельно.
        // 10.48: в один ряд, прокрутка вбок свайпом, без полосы прокрутки (шапка не растёт вниз).
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setFillViewport(false);
        LinearLayout pc = new LinearLayout(a);
        pc.setOrientation(LinearLayout.HORIZONTAL);
        pc.setGravity(Gravity.CENTER_VERTICAL);
        pc.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 4));
        hs.addView(pc, new FrameLayout.LayoutParams(-2, -2));
        scroll.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        panelChips = pc;
        panelChipsScroll = hs;

        View line = new View(a);
        line.setBackgroundColor(cLine);
        scroll.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(a, 1))));

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 16));
        scroll.addView(body, new LinearLayout.LayoutParams(-1, -2));

        body.addView(sectionTitle(a, "Категория"));
        catBox = new Flow(a, dp(a, 6));
        body.addView(catBox, new LinearLayout.LayoutParams(-1, -2));
        subTitleView = sectionTitle(a, "Подкатегория");
        body.addView(subTitleView);
        subBox = new Flow(a, dp(a, 6));
        body.addView(subBox, new LinearLayout.LayoutParams(-1, -2));

        body.addView(sectionTitle(a, "Цена за сутки, \u20bd"));
        LinearLayout prices = new LinearLayout(a);
        prices.setOrientation(LinearLayout.HORIZONTAL);
        fromEdit = priceField(a, "от", draft.from);
        toEdit = priceField(a, "до", draft.to);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, dp(a, PRICE_FIELD_DP), 1f);
        fl.rightMargin = dp(a, 8);
        prices.addView(fromEdit, fl);
        prices.addView(toEdit, new LinearLayout.LayoutParams(0, dp(a, PRICE_FIELD_DP), 1f));
        body.addView(prices, new LinearLayout.LayoutParams(-1, -2));
        priceHint = new TextView(a);
        priceHint.setTextSize(12f);
        priceHint.setTextColor(cGray2);
        priceHint.setPadding(0, dp(a, 6), 0, 0);
        body.addView(priceHint);

        body.addView(sectionTitle(a, "Доставка"));
        deliveryBox = new CheckBox(a);
        deliveryBox.setTextColor(cText);
        deliveryBox.setTextSize(15f);
        deliveryBox.setMinHeight(dp(a, 44));
        try { if (android.os.Build.VERSION.SDK_INT >= 21) deliveryBox.setButtonTintList(android.content.res.ColorStateList.valueOf(cOrange)); } catch (Throwable ignore) {}
        deliveryBox.setChecked(draft.delivery);
        deliveryBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (syncing || draft == null) return;
                draft.delivery = on;
                refreshPanel(a);
            }
        });
        body.addView(deliveryBox, new LinearLayout.LayoutParams(-1, -2));

        condTitleView = sectionTitle(a, "Состояние");
        body.addView(condTitleView);
        condBox = new Flow(a, dp(a, 6));
        body.addView(condBox, new LinearLayout.LayoutParams(-1, -2));
        condNote = new TextView(a);
        condNote.setTextSize(12f);
        condNote.setTextColor(cGray2);
        condNote.setPadding(0, dp(a, 6), 0, 0);
        body.addView(condNote);

        // Радиус — заготовка (решение владельца, 10.46): виден, но неактивен и на выдачу не влияет.
        // Ни геолокации, ни разрешений, ни расстояний: координат для настоящего радиуса у приложения пока нет.
        body.addView(sectionTitle(a, "Радиус \u00b7 скоро"));
        Flow rb = new Flow(a, dp(a, 6));
        for (int i = 0; i < RADIUS_TITLES.length; i++) {
            TextView rc = chip(a, RADIUS_TITLES[i], i == 0, false);
            rc.setEnabled(false);
            rc.setClickable(false);
            rc.setAlpha(0.45f);
            rb.addView(rc);
        }
        rb.setContentDescription("Радиус поиска появится позже, сейчас недоступен");
        body.addView(rb, new LinearLayout.LayoutParams(-1, -2));
        TextView rn = new TextView(a);
        rn.setText("Поиск по расстоянию появится позже. Сейчас выбор радиуса недоступен и на выдачу не влияет.");
        rn.setTextSize(12f);
        rn.setTextColor(cGray2);
        rn.setPadding(0, dp(a, 6), 0, 0);
        body.addView(rn);

        // Закреплённые действия
        View line2 = new View(a);
        line2.setBackgroundColor(cLine);
        sh.addView(line2, new LinearLayout.LayoutParams(-1, Math.max(1, dp(a, 1))));
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        TextView reset = new TextView(a);
        reset.setText("Сбросить");
        reset.setTextSize(15f);
        reset.setTypeface(null, android.graphics.Typeface.BOLD);
        reset.setTextColor(cText);
        reset.setGravity(Gravity.CENTER);
        Drawable cardBg = drawable(a, "bg_card");
        if (cardBg != null) reset.setBackground(cardBg);
        reset.setClickable(true);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (draft == null) return;
                draft.resetAll();
                syncInputs(a);
                refreshPanel(a);
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, dp(a, SHEET_BTN_DP), 1f);
        rl.rightMargin = dp(a, 8);
        foot.addView(reset, rl);
        resetBtn = reset;
        TextView show = new TextView(a);
        show.setTextSize(15f);
        show.setTypeface(null, android.graphics.Typeface.BOLD);
        show.setTextColor(0xffffffff);
        show.setGravity(Gravity.CENTER);
        show.setSingleLine(true);
        show.setEllipsize(TextUtils.TruncateAt.END);
        show.setPadding(dp(a, 8), 0, dp(a, 8), 0);
        Drawable ob = drawable(a, "bg_btn_orange");
        if (ob != null) show.setBackground(ob);
        else show.setBackground(pill(a, cOrange, 0));
        show.setClickable(true);
        show.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { applyDraft(a); } });
        foot.addView(show, new LinearLayout.LayoutParams(0, dp(a, SHEET_BTN_DP), 2f));
        showBtn = show;
        sh.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        refreshPanel(a);
    }

    static EditText priceField(final Activity a, String hint, long value) {
        final EditText e = new EditText(a);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(15f);
        e.setTextColor(color(a, "text", 0xfff2f4f8));
        e.setHintTextColor(color(a, "gray2", 0xff6b7080));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(7)});
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        Drawable fb = drawable(a, "bg_field");
        if (fb != null) e.setBackground(fb);
        if (value >= 0) e.setText(String.valueOf(value));
        backKeys(a, e);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (syncing || draft == null) return;
                draft.from = priceValue(fromEdit == null ? "" : fromEdit.getText().toString());
                draft.to = priceValue(toEdit == null ? "" : toEdit.getText().toString());
                refreshPanel(a);
            }
        });
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                hideKeyboard(a, v);
                if (sheet != null) sheet.requestFocus();
                return true;
            }
        });
        return e;
    }

    /** Поля ввода ← правка (после «Сбросить» и снятия условия кнопкой ✕). */
    static void syncInputs(Activity a) {
        if (draft == null) return;
        syncing = true;
        try {
            if (fromEdit != null) fromEdit.setText(draft.from >= 0 ? String.valueOf(draft.from) : "");
            if (toEdit != null) toEdit.setText(draft.to >= 0 ? String.valueOf(draft.to) : "");
            if (deliveryBox != null) deliveryBox.setChecked(draft.delivery);
        } finally { syncing = false; }
    }

    /** Обновить изменяемые части панели: город, условия, категории, подсказки, число на кнопке. */
    static void refreshPanel(final Activity a) {
        try {
            if (draft == null || sheet == null || panelRoot == null || panelRoot.getVisibility() != View.VISIBLE) return;
            final String city = cityChosen ? selCity : "";
            if (cityLine != null) {
                cityLine.setText(cityButtonText(selCity, cityChosen) + " \u25be");
                cityLine.setContentDescription("Город поиска: " + cityButtonText(selCity, cityChosen) + ". Изменить");
            }
            // Активные условия
            panelChips.removeAllViews();
            addActiveChips(a, panelChips, draft, new Runnable() {
                @Override public void run() { syncInputs(a); refreshPanel(a); }
            }, true);
            if (panelChipsScroll != null) panelChipsScroll.setVisibility(panelChips.getChildCount() > 0 ? View.VISIBLE : View.GONE);

            String query = str(IvaClientPatch.get(a, "query"));

            // Категории и подкатегории — штатные справочники cats/subcats (как в сетке на главной), но только те,
            // где при остальных условиях (город, поиск, цена, доставка, состояние) есть хоть один инструмент.
            Filter noCat = draft.copy();
            noCat.cat = ""; noCat.sub = "";
            List<String> catKeys = new ArrayList<String>();
            LinkedHashMap<String, String> catTitles = new LinkedHashMap<String, String>();
            Object cats = IvaClientPatch.get(a, "cats");
            if (cats instanceof ArrayList) for (Object o : (ArrayList) cats) {
                if (!(o instanceof JSONObject)) continue;
                String key = ((JSONObject) o).optString("key", "");
                if (key.isEmpty() || catTitles.containsKey(key)) continue;
                catKeys.add(key);
                catTitles.put(key, ((JSONObject) o).optString("title", key));
            }
            // Один проход по каталогу: то же правило, что countFor/лента (штатный inCat: cat или список cats).
            LinkedHashMap<String, Integer> catCounts = new LinkedHashMap<String, Integer>();
            LinkedHashMap<String, Integer> subCounts = new LinkedHashMap<String, Integer>();
            int allCount = 0;
            for (Object o : allTools(a)) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject j = (JSONObject) o;
                if (!nativeMatch(j, "root", "", "", query) || !extraMatch(j, city, noCat)) continue;
                allCount++;
                HashSet<String> in = new HashSet<String>();
                in.add(j.optString("cat", ""));
                List<String> cl2 = catsOf(j);
                if (cl2 != null) in.addAll(cl2);
                for (String k : in) if (catTitles.containsKey(k)) catCounts.put(k, (catCounts.containsKey(k) ? catCounts.get(k) : 0) + 1);
                if (!draft.cat.isEmpty() && in.contains(draft.cat)) {
                    String sk = j.optString("sub", "");
                    subCounts.put(sk, (subCounts.containsKey(sk) ? subCounts.get(sk) : 0) + 1);
                }
            }
            catBox.removeAllViews();
            addOption(a, catBox, "Все \u00b7 " + allCount, draft.cat.isEmpty(),
                new Runnable() { @Override public void run() { draft.cat = ""; draft.sub = ""; } });
            for (final String key : facetVisible(catKeys, catCounts, draft.cat)) {
                Integer cn = catCounts.get(key);
                addOption(a, catBox, catTitles.get(key) + " \u00b7 " + (cn == null ? 0 : cn), key.equals(draft.cat),
                    new Runnable() { @Override public void run() { draft.cat = key; draft.sub = ""; } });
            }
            subBox.removeAllViews();
            Object subs = IvaClientPatch.get(a, "subcats");
            if (!draft.cat.isEmpty() && subs instanceof ArrayList) {
                List<String> subKeys = new ArrayList<String>();
                LinkedHashMap<String, String> subTitles = new LinkedHashMap<String, String>();
                for (Object o : (ArrayList) subs) {
                    if (!(o instanceof JSONObject) || !draft.cat.equals(((JSONObject) o).optString("cat"))) continue;
                    String key = ((JSONObject) o).optString("key", "");
                    if (key.isEmpty() || subTitles.containsKey(key)) continue;
                    subKeys.add(key);
                    subTitles.put(key, ((JSONObject) o).optString("title", key));
                }
                for (final String key : facetVisible(subKeys, subCounts, draft.sub)) {
                    Integer sn = subCounts.get(key);
                    addOption(a, subBox, subTitles.get(key) + " \u00b7 " + (sn == null ? 0 : sn), key.equals(draft.sub),
                        new Runnable() { @Override public void run() { draft.sub = key.equals(draft.sub) ? "" : key; } });
                }
            }
            boolean hasSubs = subBox.getChildCount() > 0;
            subTitleView.setVisibility(hasSubs ? View.VISIBLE : View.GONE);
            subBox.setVisibility(hasSubs ? View.VISIBLE : View.GONE);

            // Подсказка цены и числа по доставке/состоянию — по выдаче при остальных условиях
            long min = -1, max = -1;
            int noPrice = 0;
            int withDelivery = 0;
            int[] condCounts = new int[COND_BITS.length];
            int withCond = 0;
            Filter base = draft.copy();
            base.from = -1; base.to = -1; base.delivery = false; base.conds = 0;
            String catMode = base.cat.isEmpty() ? "root" : "cat";
            for (Object o : allTools(a)) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject j = (JSONObject) o;
                if (!nativeMatch(j, catMode, base.cat, base.sub, query) || !extraMatch(j, city, base)) continue;
                double pd = j.optDouble("price", 0);
                if (!(pd > 0)) noPrice++;
                else {
                    long pr = Math.round(pd);
                    if (min < 0 || pr < min) min = pr;
                    if (max < 0 || pr > max) max = pr;
                }
                if (j.optBoolean("delivery", false)) withDelivery++;
                int c = condOf(j);
                if (c != 0) withCond++;
                for (int i = 0; i < COND_BITS.length; i++) if (c == COND_BITS[i]) condCounts[i]++;
            }
            StringBuilder ph = new StringBuilder();
            if (draft.from >= 0 && draft.to >= 0 && draft.from > draft.to)
                ph.append("«От» больше «до» — ищем от ").append(money(draft.to)).append(" до ").append(money(draft.from)).append(NB).append("\u20bd. ");
            ph.append(min < 0 ? "Цены у подходящих инструментов не указаны."
                : (min == max ? "Сейчас все с ценой — по " + money(min) + NB + "\u20bd в сутки."
                : "Сейчас в выдаче: от " + money(min) + " до " + money(max) + NB + "\u20bd в сутки."));
            if (noPrice > 0) ph.append(" Без цены — ").append(noPrice).append(": при выборе цены они не показываются.");
            priceHint.setText(ph.toString());
            syncing = true;
            try { deliveryBox.setText("Только с доставкой (" + withDelivery + ")"); } finally { syncing = false; }

            condBox.removeAllViews();
            for (int i = 0; i < COND_BITS.length; i++) {
                final int bit = COND_BITS[i];
                addOption(a, condBox, COND_TITLES[i] + " \u00b7 " + condCounts[i], (draft.conds & bit) != 0,
                    new Runnable() { @Override public void run() { draft.conds ^= bit; } });
            }
            condNote.setText(withCond == 0
                ? "В подходящих объявлениях состояние пока не указано."
                : "Состояние указано не во всех объявлениях: без него объявление при выборе состояния не показывается.");

            int n = countFor(a, draft, city);
            showBtn.setText(showLabel(n));
            showBtn.setAlpha(n > 0 ? 1f : 0.6f);
            showBtn.setEnabled(n > 0);
            showBtn.setContentDescription(showLabel(n));
        } catch (Throwable ignore) {}
    }

    static void addOption(final Activity a, Flow box, String text, boolean on, final Runnable select) {
        TextView c = chip(a, text, on, false);
        if (on) c.setContentDescription(text + ", выбрано");
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (draft == null) return;
                select.run();
                refreshPanel(a);
            }
        });
        box.addView(c);
    }

    /** «Показать N инструментов»: категория → штатные поля и сетка, остальное → лента. */
    static void applyDraft(Activity a) {
        if (draft == null) return;
        Filter d = draft;
        Filter cur = current(a);
        if (!cur.cat.equals(d.cat) || !cur.sub.equals(d.sub)) setNativeCat(a, d.cat, d.sub);
        applied.from = d.from; applied.to = d.to; applied.delivery = d.delivery; applied.conds = d.conds;
        applySearch(a);
        closePanel(a, true);
        try {   // к началу ленты, чтобы сразу было видно результат
            Object ph = IvaClientPatch.get(a, "pageHome");
            Object fg = IvaClientPatch.get(a, "feedGrid");
            if (ph instanceof ScrollView && fg instanceof View)
                ((ScrollView) ph).smoothScrollTo(0, Math.max(0, ((View) fg).getTop() - dp(a, 12)));
        } catch (Throwable ignore) {}
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════
    // 8. Простая «перетекающая» раскладка для кнопок-фильтров (без горизонтальной прокрутки)
    // ════════════════════════════════════════════════════════════════════════════════════════════

    static final class Flow extends ViewGroup {
        final int gap;

        Flow(Context c, int gap) { super(c); this.gap = gap; }

        @Override protected void onMeasure(int ws, int hs) {
            int mode = MeasureSpec.getMode(ws);
            int width = mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE / 2 : MeasureSpec.getSize(ws);
            int inner = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int x = 0, y = 0, rowH = 0, used = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > inner) { x = 0; y += rowH + gap; rowH = 0; }
                x += cw + gap;
                used = Math.max(used, x - gap);
                rowH = Math.max(rowH, ch);
            }
            int h = getPaddingTop() + y + rowH + getPaddingBottom();
            int w = mode == MeasureSpec.EXACTLY ? width : Math.min(width, used + getPaddingLeft() + getPaddingRight());
            setMeasuredDimension(w, resolveSize(h, hs));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int inner = Math.max(0, r - l - getPaddingLeft() - getPaddingRight());
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > inner) { x = 0; y += rowH + gap; rowH = 0; }
                c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + cw, getPaddingTop() + y + ch);
                x += cw + gap;
                rowH = Math.max(rowH, ch);
            }
        }
    }
}

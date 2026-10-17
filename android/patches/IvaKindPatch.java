// Ива 10.79 — «Вид инструмента» (решение владельца 15.10.2026, журнал — решение №28).
//
// Справочник видов — таблица iva_kinds (миграция supabase/migrations/20261015_category_tree.sql): вид → категория и
// подкатегория. Вид у объявления — столбец tools.kind (необязательный).
//
//  * Форма «Добавить инструмент» и правка инструмента: под «Подкатегорией» — поле «Вид инструмента (необязательно)»
//    с выпадающим списком видов выбранной подкатегории (как у штатных «Категории» / «Подкатегории»). У подкатегории
//    видов нет («Другое» или SQL ещё не применён) — поля нет. Сменили подкатегорию — вид, которого в ней нет, снимается.
//  * Сохранение: штатный код вид не знает, поэтому вид записывается отдельным запросом ПОСЛЕ штатного успеха (штатный
//    код при успехе показывает «Опубликовано» / «Сохранено» и открывает кабинет — showTab("admhub") → IvaPatch.onTab).
//    Нажатие «Опубликовать» / «Сохранить» лишь запоминает выбор (то же касание, что и у «Состояния», 10.66).
//    Правка — PATCH по id объявления; новое объявление — находим своё последнее с этим названием (за 30 минут) и PATCH.
//    Не опубликовалось — кабинет не открывается, запомненный выбор пропадает (через 3 минуты или при любой другой вкладке).
//  * Фильтр (IvaSearchPatch): раздел «Вид» после «Подкатегории» — только виды, где есть инструменты, с числом.
//
//  * 10.81 (этап 1 справочника моделей, решение владельца 15.10.2026 — журнал, решение №30): в форме «Добавить» админ
//    пишет название — категория, подкатегория и вид подставляются сами по словам из iva_kinds.words (миграция
//    20261016_kind_words.sql). Подстановка — так же, как штатный выбор из списка (те же поля и тексты). Выбранное
//    руками не перезаписывается. Слов в базе нет (SQL не применён) — автоподбора нет, всё как в 10.79.
//    Тот же алгоритм на сайте — assets/kind-guess.js; оба проверяются на tests/kind-guess-cases.txt.
//
// Новых вставок в smali нет: всё — из IvaPatch.onTab / onHub и касания кнопки сохранения (IvaPatch.condRow).
// Любая ошибка — формы и фильтр работают как раньше, без вида.
package ru.prokatnvsk.app;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class IvaKindPatch {
    private IvaKindPatch() {}

    // ════════════════════════════════ 1. Справочник (чистая логика — под тестами) ════════════════════════════════

    static final class Kind {
        final String key, cat, sub, title;
        final int sort;
        Kind(String key, String cat, String sub, String title, int sort) {
            this.key = key == null ? "" : key; this.cat = cat == null ? "" : cat; this.sub = sub == null ? "" : sub;
            this.title = title == null ? "" : title; this.sort = sort;
        }
    }

    static final String KINDS_PATH = "/rest/v1/iva_kinds?select=key,cat,sub,title,sort&active=eq.true&order=sort.asc,title.asc";
    static final String PREFS = "iva_kinds", PREFS_KEY = "list";
    static final String LABEL_HINT = "Вид инструмента (необязательно)", NO_KIND = "Без вида";
    static final String KIND_HINT_ADD = "Подставится по названию — или выберите";   // 10.87: форма «Добавить»
    static final long RETRY_MS = 60_000L;          // не загрузилось / таблицы нет — пробуем снова не чаще раза в минуту
    static final long REFRESH_MS = 30 * 60_000L;   // загрузилось — обновляем раз в полчаса (вдруг поправили список)
    static final long PENDING_MS = 3 * 60_000L;    // выбор, запомненный нажатием «Сохранить», живёт 3 минуты
    static final long FRESH_MS = 30 * 60_000L;     // новое объявление ищем среди созданных за последние 30 минут

    /** Виды подкатегории (категория + подкатегория) по порядку: sort, затем название. */
    static List<Kind> forSub(List<Kind> all, String cat, String sub) {
        List<Kind> out = new ArrayList<Kind>();
        if (all == null || cat == null || sub == null || cat.isEmpty() || sub.isEmpty()) return out;
        for (Kind k : all) if (k != null && cat.equals(k.cat) && sub.equals(k.sub) && !k.key.isEmpty()) out.add(k);
        Collections.sort(out, new Comparator<Kind>() {
            @Override public int compare(Kind x, Kind y) {
                if (x.sort != y.sort) return x.sort < y.sort ? -1 : 1;
                return x.title.compareTo(y.title);
            }
        });
        return out;
    }

    /** Подходит ли вид к категории и подкатегории. Пустой вид подходит всегда; справочника нет — не подходит. */
    static boolean fits(List<Kind> all, String cat, String sub, String kind) {
        if (kind == null || kind.isEmpty()) return true;
        for (Kind k : forSub(all, cat, sub)) if (kind.equals(k.key)) return true;
        return false;
    }

    /** Вид после смены категории / подкатегории: остаётся, только если есть в новой подкатегории. */
    static String keep(List<Kind> all, String cat, String sub, String kind) {
        return kind != null && !kind.isEmpty() && fits(all, cat, sub, kind) ? kind : "";
    }

    static String titleOf(List<Kind> all, String key) {
        if (all == null || key == null || key.isEmpty()) return "";
        for (Kind k : all) if (k != null && key.equals(k.key)) return k.title;
        return "";
    }

    /** Текст поля: выбранный вид или подсказка. */
    static String fieldText(List<Kind> all, String kind) {
        String t = titleOf(all, kind);
        return t.isEmpty() ? LABEL_HINT : t;
    }

    /** Значение поля из базы: JSON null у Android — строка "null". */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return "null".equals(t) ? "" : t;
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Throwable e) { return ""; }
    }

    /** Своё последнее объявление с этим названием, созданное не раньше since (ISO, UTC). */
    static String lookupPath(String email, String name, String since) { return lookupPath(email, name, since, ""); }

    /** 10.105: у сотрудника «Каталог» владелец инструмента — прокат, поэтому свой новый ищем по номеру сотрудника. */
    static String lookupPath(String email, String name, String since, String staffId) {
        String who = staffId != null && IvaToolPointPatch.uuid(staffId)
            ? "staff_id=eq." + enc(staffId.trim().toLowerCase(Locale.ROOT))
            : "owner_email=eq." + enc(email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
        return "/rest/v1/tools?select=id,kind&" + who
            + "&name=eq." + enc(name == null ? "" : name.trim()) + "&created_at=gte." + enc(since)
            + "&order=created_at.desc&limit=1";
    }

    static String patchPath(String id) { return "/rest/v1/tools?id=eq." + enc(id); }

    static String kindPath(String id) { return "/rest/v1/tools?select=kind&id=eq." + enc(id); }

    /** {"kind":"…"} или {"kind":null} (вид сняли). Ключи видов — латиница, цифры и дефис; иное не шлём. */
    static String patchBody(String kind) {
        String k = kind == null ? "" : kind.trim();
        if (k.isEmpty()) return "{\"kind\":null}";
        if (!k.matches("[a-z0-9-]{1,64}")) return null;
        return "{\"kind\":\"" + k + "\"}";
    }

    static String isoUtc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** Запомненный выбор ещё в силе: нажали «Сохранить» не раньше PENDING_MS назад (и не «в будущем» — часы сбили). */
    static boolean pendingFresh(long at, long now) { return at > 0 && now >= at && now - at <= PENDING_MS; }

    /** Первая вкладка после нажатия «Сохранить»: кабинет (штатный успех) — записать вид (true), любая другая — забыть. */
    static boolean pendingWrite(String tab, long at, long now) {
        return pendingFresh(at, now) && "admhub".equals(tab);
    }

    /** Место поля «Вид» в форме: сразу после выпадающего списка подкатегорий (или после самого поля, если списка нет). */
    static int rowIndex(int subIndex, int sugIndex) {
        if (subIndex < 0) return -1;
        return (sugIndex == subIndex + 1 ? sugIndex : subIndex) + 1;
    }

    // ───── 10.81: автоподбор по названию (чистая логика — под тестами, та же, что assets/kind-guess.js) ─────

    static final String WORDS_PATH = "/rest/v1/iva_kinds?select=key,words&active=eq.true";
    static final String PREFS_WORDS = "words";
    static final String SUB_HINT = "Подкатегория (необязательно)";   // штатный текст пустой подкатегории
    static final long AUTO_DELAY_MS = 400;                           // пауза после ввода, чтобы не дёргать поля на каждой букве

    static final class Guess {
        final String cat, sub, kind;
        Guess(String cat, String sub, String kind) {
            this.cat = cat == null ? "" : cat; this.sub = sub == null ? "" : sub; this.kind = kind == null ? "" : kind;
        }
        @Override public String toString() { return cat + "/" + sub + "/" + kind; }
    }

    /** Название → слова: строчные, ё→е, всё кроме латиницы, кириллицы и цифр — разделитель. */
    static String[] tokens(String s) {
        String t = (s == null ? "" : s).toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^a-zа-я0-9]+", " ").trim();
        return t.isEmpty() ? new String[0] : t.split(" ");
    }

    /** Очки записи «болгар 125»: каждая часть — начало какого-то слова названия; сумма длин частей, иначе 0. */
    static int entryScore(String entry, String[] toks) {
        String[] parts = tokens(entry);
        if (parts.length == 0) return 0;
        int total = 0;
        for (String p : parts) {
            boolean hit = false;
            for (String t : toks) if (t.startsWith(p)) { hit = true; break; }
            if (!hit) return 0;
            total += p.length();
        }
        return total;
    }

    /** Лучший вид: больше очков у лучшей записи, при равенстве — больше подошедших записей. Один лучший → всё;
     *  несколько в одной подкатегории → категория + подкатегория; в одной категории → категория; иначе — ничего. */
    static Guess guess(String name, List<Kind> all, java.util.Map<String, String[]> words) {
        Guess none = new Guess("", "", "");
        String[] toks = tokens(name);
        if (toks.length == 0 || all == null || words == null) return none;
        List<Kind> best = new ArrayList<Kind>();
        int bs = 0, bn = 0;
        for (Kind k : all) {
            if (k == null || k.key.isEmpty() || k.cat.isEmpty() || k.sub.isEmpty()) continue;
            String[] ws = words.get(k.key);
            if (ws == null) continue;
            int m = 0, n = 0;
            for (String w : ws) {
                int sc = entryScore(w, toks);
                if (sc > 0) { n++; if (sc > m) m = sc; }
            }
            if (m == 0) continue;
            if (m > bs || (m == bs && n > bn)) { bs = m; bn = n; best.clear(); best.add(k); }
            else if (m == bs && n == bn) best.add(k);
        }
        if (best.isEmpty()) return none;
        Kind f = best.get(0);
        if (best.size() == 1) return new Guess(f.cat, f.sub, f.key);
        boolean sameSub = true, sameCat = true;
        for (Kind k : best) {
            if (!k.cat.equals(f.cat)) { sameCat = false; sameSub = false; }
            else if (!k.sub.equals(f.sub)) sameSub = false;
        }
        if (sameSub) return new Guess(f.cat, f.sub, "");
        if (sameCat) return new Guess(f.cat, "", "");
        return none;
    }

    /** Поле можно менять: оно пустое или в нём всё ещё то, что подставили мы в прошлый раз. */
    static boolean mayFill(String cur, String lastAuto) {
        String c = cur == null ? "" : cur;
        return c.isEmpty() || c.equals(lastAuto == null ? "" : lastAuto);
    }

    /** Что поставить в форме: {категория, подкатегория, вид} или null — ничего не менять. cur — что в форме сейчас,
     *  auto — что подставили мы в прошлый раз. Категорию, выбранную руками, не меняем; подкатегорию и вид, выбранные
     *  руками, — тоже (пока категория та же). То же — ivaKindPlan в assets/kind-guess.js. */
    static String[] plan(String curCat, String curSub, String curKind, String autoCat, String autoSub, String autoKind, Guess g) {
        String cc = curCat == null ? "" : curCat, cs = curSub == null ? "" : curSub, ck = curKind == null ? "" : curKind;
        if (g == null || g.cat.isEmpty()) return null;
        if (!mayFill(cc, autoCat) && !cc.equals(g.cat)) return null;
        boolean catChanged = !g.cat.equals(cc);
        String sub = catChanged || mayFill(cs, autoSub) || cs.equals(g.sub) ? g.sub : cs;
        String kind;
        if (sub.equals(g.sub) && (catChanged || !sub.equals(cs) || mayFill(ck, autoKind) || ck.equals(g.kind))) kind = g.kind;
        else kind = sub.equals(cs) ? ck : "";
        if (g.cat.equals(cc) && sub.equals(cs) && kind.equals(ck)) return null;
        return new String[]{g.cat, sub, kind};
    }

    /** [{"key":"drill","words":["дрел","drill"]}, …] → вид → слова. Не массив (ошибка, нет столбца) — null. */
    static java.util.Map<String, String[]> parseWords(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (!t.startsWith("[")) return null;
        JSONArray arr = new JSONArray(t);
        java.util.Map<String, String[]> out = new java.util.HashMap<String, String[]>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String key = clean(o.optString("key", ""));
            JSONArray w = o.optJSONArray("words");
            if (key.isEmpty() || w == null) continue;
            List<String> ws = new ArrayList<String>();
            for (int j = 0; j < w.length(); j++) {
                String x = clean(w.optString(j, ""));
                if (!x.isEmpty()) ws.add(x);
            }
            if (!ws.isEmpty()) out.put(key, ws.toArray(new String[0]));
        }
        return out;
    }

    // ════════════════════════════════ 2. Загрузка справочника ════════════════════════════════

    static volatile List<Kind> kinds = null;       // null — ещё нет (или SQL не применён)
    static volatile java.util.Map<String, String[]> words = null;   // 10.81: слова автоподбора; null — нет (SQL не применён)
    static volatile boolean loading = false;
    static volatile long triedAt = 0, loadedAt = 0;

    static List<Kind> list() { List<Kind> k = kinds; return k == null ? new ArrayList<Kind>() : k; }

    static List<Kind> parse(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (!t.startsWith("[")) return null;
        JSONArray arr = new JSONArray(t);
        List<Kind> out = new ArrayList<Kind>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String key = clean(o.optString("key", ""));
            if (key.isEmpty()) continue;
            out.add(new Kind(key, clean(o.optString("cat", "")), clean(o.optString("sub", "")), clean(o.optString("title", key)), o.optInt("sort", 0)));
        }
        return out;
    }

    static void ensureLoaded(final Activity a) {
        try {
            if (kinds == null) {                                     // копия с телефона — сразу, без сети
                try {
                    List<Kind> cached = parse(a.getSharedPreferences(PREFS, 0).getString(PREFS_KEY, ""));
                    if (cached != null && !cached.isEmpty()) kinds = cached;
                } catch (Throwable ignore) {}
            }
            if (words == null) {
                try {
                    java.util.Map<String, String[]> cw = parseWords(a.getSharedPreferences(PREFS, 0).getString(PREFS_WORDS, ""));
                    if (cw != null && !cw.isEmpty()) words = cw;
                } catch (Throwable ignore) {}
            }
            long now = System.currentTimeMillis();
            if (loading) return;
            if (loadedAt > 0 ? now - loadedAt < REFRESH_MS : now - triedAt < RETRY_MS) return;
            loading = true;
            triedAt = now;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String[] r = IvaClientPatch.apiCall(a, KINDS_PATH, "GET", null, false);
                        if (r != null && r.length > 1 && r[0] != null && r[0].startsWith("2")) {
                            List<Kind> fresh = parse(r[1]);
                            if (fresh != null) {
                                kinds = fresh;
                                loadedAt = System.currentTimeMillis();
                                try { a.getSharedPreferences(PREFS, 0).edit().putString(PREFS_KEY, r[1]).apply(); } catch (Throwable ignore) {}
                                loadWords(a);
                                a.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        try { refreshRows(a); IvaSearchPatch.refreshPanel(a); } catch (Throwable ignore) {}
                                    }
                                });
                            }
                        } else if (r != null && r[0] != null && (r[0].startsWith("404") || r[0].startsWith("400"))) {
                            kinds = null;                                // таблицы нет (SQL не применён) — вида нигде нет
                            try { a.getSharedPreferences(PREFS, 0).edit().remove(PREFS_KEY).apply(); } catch (Throwable ignore) {}
                        }
                    } catch (Throwable ignore) {
                    } finally { loading = false; }
                }
            }).start();
        } catch (Throwable ignore) { loading = false; }
    }

    /** 10.81: слова автоподбора — отдельным запросом (тот же поток, после видов). Столбца нет (400) — автоподбора нет. */
    static void loadWords(Activity a) {
        try {
            String[] r = IvaClientPatch.apiCall(a, WORDS_PATH, "GET", null, false);
            if (r != null && r.length > 1 && r[0] != null && r[0].startsWith("2")) {
                java.util.Map<String, String[]> w = parseWords(r[1]);
                if (w != null) {
                    words = w;
                    try { a.getSharedPreferences(PREFS, 0).edit().putString(PREFS_WORDS, r[1]).apply(); } catch (Throwable ignore) {}
                }
            } else if (r != null && r[0] != null && (r[0].startsWith("404") || r[0].startsWith("400"))) {
                words = null;
                try { a.getSharedPreferences(PREFS, 0).edit().remove(PREFS_WORDS).apply(); } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
    }

    // ════════════════════════════════ 3. Поле «Вид» в формах ════════════════════════════════

    // 0 — «Добавить инструмент», 1 — правка инструмента
    static final String[][] FORM = {
        {"atCat", "atCatKey", "atSub", "atSubKey", "atSubSug", "atName"},
        {"teCat", "editToolCat", "teSub", "editToolSub", "teSubSug", "teName"},
    };
    static final String ROW_TAG = "iva_kind_row:";
    static final String[] sel = {"", ""};               // выбранный вид в форме
    static final String[] orig = {"", ""};              // вид объявления, открытого в правке (из базы)
    static volatile String editId = "";                  // какое объявление открыто в правке
    static volatile boolean editLoaded = false;          // вид открытого объявления уже пришёл из базы
    static final java.util.Map<View, Boolean> watched = new java.util.WeakHashMap<View, Boolean>();

    static String str(Object o) { return o instanceof String ? clean((String) o) : ""; }

    public static void onTab(Activity a, String tab) {
        try { ensureLoaded(a); } catch (Throwable ignore) {}
        try { if ("tooledit".equals(tab)) openedEdit(a); } catch (Throwable ignore) {}
        try { refreshRows(a); } catch (Throwable ignore) {}
        try { IvaModelPatch.onTab(a, tab); } catch (Throwable ignore) {}   // 10.82: опубликовали — модель и характеристики заново
        try { IvaFormPatch.onTab(a, tab); } catch (Throwable ignore) {}    // 10.87: опубликовали — черновик не нужен
        try { consumePending(a, tab); } catch (Throwable ignore) {}
        try { IvaToolPointPatch.onTab(a, tab); } catch (Throwable ignore) {}   // 10.101: пункт выдачи — правка, запись, карточка
    }

    /** Кабинет переключил «Добавить» / «Мои» (showAdmHub): только поле в форме. Запомненный выбор не трогаем — штатный
     *  успех открывает кабинет через showTab("admhub"), и showAdmHub может сработать раньше конца showTab. */
    public static void onHub(Activity a) {
        try { ensureLoaded(a); } catch (Throwable ignore) {}
        try { refreshRows(a); } catch (Throwable ignore) {}
    }

    /** Открыли правку: другой инструмент — вид берём из базы (до ответа поле показывает «…» и выбор не записывается). */
    static void openedEdit(final Activity a) {
        final String id = str(IvaClientPatch.get(a, "editToolId"));
        if (id.isEmpty() || id.equals(editId)) return;
        editId = id; editLoaded = false; sel[1] = ""; orig[1] = "";
        new Thread(new Runnable() {
            @Override public void run() {
                String k = "";
                boolean ok = false;
                try {
                    String[] r = IvaClientPatch.apiCall(a, kindPath(id), "GET", null, true);
                    if (r != null && r.length > 1 && r[0] != null && r[0].startsWith("2")) {
                        JSONArray arr = new JSONArray(r[1] == null ? "[]" : r[1].trim());
                        JSONObject o = arr.length() > 0 ? arr.optJSONObject(0) : null;
                        k = o == null || o.isNull("kind") ? "" : clean(o.optString("kind", ""));
                        ok = true;
                    }
                } catch (Throwable ignore) {}
                final String kind = k;
                final boolean done = ok;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!id.equals(editId)) return;          // уже открыли другой
                        if (done) { orig[1] = kind; sel[1] = kind; editLoaded = true; }
                        else editId = "";                         // нет ответа — спросим снова при следующем открытии
                        try { refreshRows(a); } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void refreshRows(Activity a) {
        for (int k = 0; k < 2; k++) {
            try { refreshRow(a, k); } catch (Throwable ignore) {}
        }
        try { arrange(a); } catch (Throwable ignore) {}               // 10.86: подписи и порядок пунктов формы «Добавить»
        try { IvaToolPointPatch.refresh(a); } catch (Throwable ignore) {}   // 10.101: «Пункт выдачи» в правке инструмента
    }

    static void refreshRow(final Activity a, final int k) {
        Object so = IvaClientPatch.get(a, FORM[k][2]);
        if (!(so instanceof TextView) || !(((View) so).getParent() instanceof LinearLayout)) return;
        final TextView subView = (TextView) so;
        LinearLayout form = (LinearLayout) subView.getParent();
        String tag = ROW_TAG + FORM[k][2];
        View row = form.findViewWithTag(tag);
        if (row == null) {
            Object sug = IvaClientPatch.get(a, FORM[k][4]);
            int idx = rowIndex(form.indexOfChild(subView), sug instanceof View ? form.indexOfChild((View) sug) : -1);
            if (idx < 0) return;
            row = build(a, k, tag, subView, sug instanceof View ? (View) sug : null);
            form.addView(row, Math.min(idx, form.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
            watch(a, subView);
            Object co = IvaClientPatch.get(a, FORM[k][0]);
            if (co instanceof TextView) watch(a, (TextView) co);
        }
        paint(a, k, row);
        if (k == 0) watchName(a);
        if (k == 0) IvaModelPatch.refresh(a);                         // 10.82: «Модель» и «Характеристики» под видом
    }

    // ───── 10.81: автоподбор в форме «Добавить» ─────

    static final String[] auto = {"", "", ""};          // что подставили мы в прошлый раз: категория, подкатегория, вид
    static int nameSeq = 0;

    /** Название в форме «Добавить» меняется — через паузу подбираем категорию, подкатегорию и вид. Штатный слушатель
     *  не заменяется (addTextChangedListener добавляет свой). */
    static void watchName(final Activity a) {
        Object no = IvaClientPatch.get(a, FORM[0][5]);
        if (!(no instanceof TextView)) return;
        final TextView nv = (TextView) no;
        if (watched.containsKey(nv)) return;
        watched.put(nv, Boolean.TRUE);
        nv.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                final int my = ++nameSeq;
                nv.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (my != nameSeq) return;               // ещё печатают
                        try { autofill(a); } catch (Throwable ignore) {}
                    }
                }, AUTO_DELAY_MS);
            }
        });
    }

    /** Заголовок из штатного списка (cats / subcats); нет в списке — null (не подставляем). */
    static String nativeTitle(Activity a, String list, String cat, String key) {
        Object l = IvaClientPatch.get(a, list);
        if (!(l instanceof ArrayList)) return null;
        for (Object o : (ArrayList) l) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if (!key.equals(j.optString("key", ""))) continue;
            if (cat != null && !cat.equals(j.optString("cat", ""))) continue;
            String t = clean(j.optString("title", ""));
            return t.isEmpty() ? key : t;
        }
        return null;
    }

    static void autofill(Activity a) {
        if (kinds == null || words == null) return;                  // SQL не применён — как в 10.79
        Object no = IvaClientPatch.get(a, FORM[0][5]);
        Object co = IvaClientPatch.get(a, FORM[0][0]), so = IvaClientPatch.get(a, FORM[0][2]);
        if (!(no instanceof TextView) || !(co instanceof TextView) || !(so instanceof TextView)) return;
        if (!((View) no).isShown()) return;                          // формы нет на экране
        Guess g = guess(String.valueOf(((TextView) no).getText()), list(), words);
        if (g.cat.isEmpty()) return;
        String catTitle = nativeTitle(a, "cats", null, g.cat);
        if (catTitle == null) return;                                // категории нет в штатном списке — не трогаем
        String subTitle = g.sub.isEmpty() ? "" : nativeTitle(a, "subcats", g.cat, g.sub);
        if (subTitle == null) { g = new Guess(g.cat, "", ""); subTitle = ""; }
        String curCat = str(IvaClientPatch.get(a, FORM[0][1])), curSub = str(IvaClientPatch.get(a, FORM[0][3]));
        String[] p = plan(curCat, curSub, sel[0], auto[0], auto[1], auto[2], g);
        if (p == null) return;
        applyPlan(a, p, curCat, curSub, catTitle, subTitle, (TextView) co, (TextView) so);
    }

    /** 10.82: выбрали модель из справочника — категория, подкатегория и вид модели (как штатный выбор). Считается
     *  подставленным нами: новое название может его сменить, выбранное руками — нет. */
    static void setFromModel(Activity a, String cat, String sub, String kind) {
        Object co = IvaClientPatch.get(a, FORM[0][0]), so = IvaClientPatch.get(a, FORM[0][2]);
        if (!(co instanceof TextView) || !(so instanceof TextView) || cat == null || cat.isEmpty()) return;
        String catTitle = nativeTitle(a, "cats", null, cat);
        if (catTitle == null) return;
        String subTitle = sub == null || sub.isEmpty() ? "" : nativeTitle(a, "subcats", cat, sub);
        if (subTitle == null) { sub = ""; subTitle = ""; }
        String k = sub == null || sub.isEmpty() || !fits(list(), cat, sub, kind) ? "" : kind;
        String curCat = str(IvaClientPatch.get(a, FORM[0][1])), curSub = str(IvaClientPatch.get(a, FORM[0][3]));
        applyPlan(a, new String[]{cat, sub == null ? "" : sub, k}, curCat, curSub, catTitle, subTitle, (TextView) co, (TextView) so);
    }

    /** Поставить в форме «Добавить» {категория, подкатегория, вид} так же, как штатный выбор из списков. */
    static void applyPlan(Activity a, String[] p, String curCat, String curSub, String catTitle, String subTitle, TextView catV, TextView subV) {
        int text = IvaPatch.color(a, "text", 0xfff2f4f8), hint = IvaPatch.color(a, "gray2", 0xff6b7080);
        hideDrop(a, "atCatSug"); hideDrop(a, "atSubSug");
        if (!p[0].equals(curCat)) {                                  // как штатный выбор категории
            IvaClientPatch.set(a, FORM[0][1], p[0]);
            catV.setText(catTitle);
            catV.setTextColor(text);
            IvaClientPatch.set(a, FORM[0][3], "");
            subV.setText(SUB_HINT);
            curSub = "";
            IvaNotifyPatch.flash(catV);
        }
        if (!p[1].equals(curSub)) {                                  // как штатный выбор подкатегории
            IvaClientPatch.set(a, FORM[0][3], p[1]);
            subV.setText(p[1].isEmpty() ? SUB_HINT : subTitle);
            subV.setTextColor(p[1].isEmpty() ? hint : text);
            if (!p[1].isEmpty()) IvaNotifyPatch.flash(subV);
        }
        boolean kindNew = !p[2].isEmpty() && !p[2].equals(sel[0]);
        sel[0] = p[2];
        auto[0] = p[0]; auto[1] = p[1]; auto[2] = p[2];
        refreshRows(a);
        if (kindNew) {
            View f = catV.getRootView() == null ? null : catV.getRootView().findViewWithTag(ROW_TAG + FORM[0][2] + ":field");
            if (f != null) IvaNotifyPatch.flash(f);
        }
    }

    static void hideDrop(Activity a, String field) {
        Object d = IvaClientPatch.get(a, field);
        if (d instanceof View) ((View) d).setVisibility(View.GONE);
    }

    /** Сменили категорию или подкатегорию (штатный код меняет текст поля) — поле «Вид» пересчитывается. */
    static void watch(final Activity a, final TextView tv) {
        if (watched.containsKey(tv)) return;
        watched.put(tv, Boolean.TRUE);
        tv.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                tv.post(new Runnable() { @Override public void run() { try { refreshRows(a); } catch (Throwable ignore) {} } });
            }
        });
    }

    static View build(final Activity a, final int k, final String tag, TextView subView, View subSug) {
        LinearLayout box = new LinearLayout(a);
        box.setTag(tag);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView f = new TextView(a);
        f.setTag(tag + ":field");
        f.setTextSize(15f);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setSingleLine(true);
        f.setEllipsize(android.text.TextUtils.TruncateAt.END);
        f.setPadding(subView.getPaddingLeft(), subView.getPaddingTop(), subView.getPaddingRight(), subView.getPaddingBottom());
        f.setBackground(copy(subView.getBackground(), a, "bg_field"));
        try {
            Drawable[] ds = subView.getCompoundDrawables();
            Drawable chev = ds != null && ds.length > 2 ? copy(ds[2], a, null) : null;
            if (chev != null) { chev.setBounds(ds[2].getBounds()); f.setCompoundDrawables(null, null, chev, null); }
            f.setCompoundDrawablePadding(subView.getCompoundDrawablePadding());
        } catch (Throwable ignore) {}
        f.setClickable(true);
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                View drop = v.getRootView() == null ? null : v.getRootView().findViewWithTag(tag + ":drop");
                if (!(drop instanceof LinearLayout)) return;
                if (drop.getVisibility() == View.VISIBLE) { drop.setVisibility(View.GONE); return; }
                fillDrop(a, k, tag, (LinearLayout) drop);
                drop.setVisibility(View.VISIBLE);
            }
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = IvaPatch.dp(a, 10);
        box.addView(f, fl);
        LinearLayout drop = new LinearLayout(a);
        drop.setTag(tag + ":drop");
        drop.setOrientation(LinearLayout.VERTICAL);
        drop.setVisibility(View.GONE);
        drop.setBackground(copy(subSug == null ? null : subSug.getBackground(), a, "bg_card"));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = IvaPatch.dp(a, 4);
        box.addView(drop, dl);
        return box;
    }

    static Drawable copy(Drawable d, Activity a, String fallback) {
        try {
            if (d != null && d.getConstantState() != null) return d.getConstantState().newDrawable(a.getResources()).mutate();
        } catch (Throwable ignore) {}
        return fallback == null ? null : IvaPatch.drawable(a, fallback);
    }

    static void fillDrop(final Activity a, final int k, final String tag, final LinearLayout drop) {
        drop.removeAllViews();
        String cat = str(IvaClientPatch.get(a, FORM[k][1])), sub = str(IvaClientPatch.get(a, FORM[k][3]));
        List<Kind> opts = forSub(list(), cat, sub);
        addItem(a, k, tag, drop, "", NO_KIND, sel[k].isEmpty());
        for (Kind kd : opts) addItem(a, k, tag, drop, kd.key, kd.title, kd.key.equals(sel[k]));
    }

    static void addItem(final Activity a, final int k, final String tag, final LinearLayout drop, final String key, String title, boolean on) {
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(on ? IvaPatch.color(a, "orange", 0xffff8f1f) : (key.isEmpty() ? IvaPatch.color(a, "gray", 0xff9aa1b0) : IvaPatch.color(a, "text", 0xfff2f4f8)));
        t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        t.setMinHeight(IvaPatch.dp(a, 44));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(IvaPatch.dp(a, 15), IvaPatch.dp(a, 10), IvaPatch.dp(a, 15), IvaPatch.dp(a, 10));
        IvaSearchPatch.feedback(a, t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sel[k] = key;
                drop.setVisibility(View.GONE);
                View root = v.getRootView();
                View row = root == null ? null : root.findViewWithTag(tag);
                if (row != null) paint(a, k, row);
                if (k == 0) try { IvaModelPatch.refresh(a); } catch (Throwable ignore) {}
            }
        });
        drop.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    static void paint(Activity a, int k, View row) {
        String cat = str(IvaClientPatch.get(a, FORM[k][1])), sub = str(IvaClientPatch.get(a, FORM[k][3]));
        List<Kind> all = list();
        boolean show = !forSub(all, cat, sub).isEmpty();
        if (kinds != null && !(k == 1 && !editLoaded)) sel[k] = keep(all, cat, sub, sel[k]);   // вида нет в подкатегории — снимаем
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        View f = row.findViewWithTag(ROW_TAG + FORM[k][2] + ":field");
        if (f instanceof TextView) {
            boolean waiting = k == 1 && !editLoaded;
            String ft = fieldText(all, sel[k]);
            if (k == 0 && sel[k].isEmpty()) ft = KIND_HINT_ADD;                 // 10.87: подпись уже над полем — внутри пример
            ((TextView) f).setText(waiting ? "Вид инструмента…" : ft);
            ((TextView) f).setTextColor(!waiting && !sel[k].isEmpty() ? IvaPatch.color(a, "text", 0xfff2f4f8) : IvaPatch.color(a, "gray2", 0xff6b7080));
            f.setEnabled(!waiting);
        }
        if (!show) {
            View d = row.findViewWithTag(ROW_TAG + FORM[k][2] + ":drop");
            if (d != null) d.setVisibility(View.GONE);
        }
    }

    // ════════════════════════════════ 4. Запись вида после штатного сохранения ════════════════════════════════

    static volatile long pendingAt = 0;
    static volatile int pendingForm = -1;
    static volatile String pendingKind = "", pendingId = "", pendingName = "", pendingEmail = "";
    static volatile String pendingStaff = "";            // 10.105: номер сотрудника «Каталог» (свой новый — по нему)
    static volatile String pendingExtra = "";            // 10.82: ,"model_id":…,"specs":{…} — только форма «Добавить»

    /** Нажали «Опубликовать» (k=0) / «Сохранить» (k=1): запоминаем, что записать, если штатное сохранение удастся. */
    static void onSaveTouch(Activity a, int k) {
        try { IvaToolPointPatch.onSaveTouch(a, k); } catch (Throwable ignore) {}   // 10.101: пункт выдачи — после штатного успеха
        try {
            pendingAt = 0;
            pendingExtra = "";
            if (k == 0) IvaModelPatch.onSaveTouch();
            if (kinds == null) return;                                   // SQL не применён — вида нет
            String name = "";
            Object no = IvaClientPatch.get(a, FORM[k][5]);
            if (no instanceof TextView) name = String.valueOf(((TextView) no).getText()).trim();
            String cat = str(IvaClientPatch.get(a, FORM[k][1]));
            if (name.isEmpty() || cat.isEmpty()) return;                 // штатная проверка всё равно не пустит
            String kind = keep(list(), cat, str(IvaClientPatch.get(a, FORM[k][3])), sel[k]);
            if (k == 1) {
                if (!editLoaded || editId.isEmpty() || kind.equals(orig[1])) return;   // вид не менялся — писать нечего
                pendingId = editId;
            } else {
                if (kind.isEmpty()) return;                              // новое объявление без вида — ничего не нужно
                pendingId = "";
                pendingName = name;
                pendingEmail = str(IvaClientPatch.get(a, "userEmail"));
                if (pendingEmail.isEmpty()) return;
                try { pendingStaff = IvaStaffToolsPatch.staffId(a); } catch (Throwable e) { pendingStaff = ""; }
                try { pendingExtra = IvaModelPatch.saveExtra(a, kind, name); } catch (Throwable e) { pendingExtra = ""; }   // 10.82
            }
            pendingKind = kind;
            pendingForm = k;
            pendingAt = System.currentTimeMillis();
        } catch (Throwable ignore) { pendingAt = 0; }
    }

    static void consumePending(final Activity a, String tab) {
        if (pendingAt == 0) return;
        boolean write = pendingWrite(tab, pendingAt, System.currentTimeMillis());
        pendingAt = 0;
        if (!write) return;
        final int k = pendingForm;
        final String kind = pendingKind, id = pendingId, name = pendingName, email = pendingEmail, extra = k == 0 ? pendingExtra : "";
        final String staffId = pendingStaff;
        final String body = patchBody(kind);
        if (body == null) return;
        if (k == 0) sel[0] = "";                                         // опубликовано — форма «Добавить» начинается заново
        else orig[1] = kind;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String target = id;
                    if (k == 0) {
                        String[] r = IvaClientPatch.apiCall(a, lookupPath(email, name, isoUtc(System.currentTimeMillis() - FRESH_MS), staffId), "GET", null, true);
                        if (r == null || r.length < 2 || r[0] == null || !r[0].startsWith("2")) return;
                        JSONArray arr = new JSONArray(r[1] == null ? "[]" : r[1].trim());
                        JSONObject o = arr.length() > 0 ? arr.optJSONObject(0) : null;
                        if (o == null) return;
                        if (!clean(o.isNull("kind") ? "" : o.optString("kind", "")).isEmpty()) return;   // вид уже есть — не трогаем
                        target = clean(o.optString("id", ""));
                    }
                    if (target.isEmpty()) return;
                    String full = IvaModelPatch.mergeBody(body, extra);
                    String[] p = IvaClientPatch.apiCall(a, patchPath(target), "PATCH", full, true);
                    String noKit = IvaModelPatch.mergeBody(body, IvaModelPatch.dropKit(extra));
                    if (!noKit.equals(full) && (p == null || p[0] == null || !p[0].startsWith("2")))   // 10.84: нет столбца kit — модель и характеристики
                        p = IvaClientPatch.apiCall(a, patchPath(target), "PATCH", noKit, true);
                    if (!full.equals(body) && (p == null || p[0] == null || !p[0].startsWith("2")))   // модель не приняли — хотя бы вид
                        p = IvaClientPatch.apiCall(a, patchPath(target), "PATCH", body, true);
                    if (p != null && p[0] != null && p[0].startsWith("2")) {
                        final String tid = target;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() { try { patchCatalog(a, tid, kind); } catch (Throwable ignore) {} }
                        });
                    }
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    // ════════════════════════════════ 10.86: раскладка формы «Добавить» (просьба владельца 15.10.2026) ════════════
    //  * Над каждым пунктом — его название мелким серым шрифтом, как над «Состоянием» (IvaPatch.condBuild): «Фотографии»,
    //    «Название инструмента», «Категория», «Подкатегория», «Вид инструмента», «Цена за сутки, ₽», «Залог, ₽»,
    //    «Описание», «Условия аренды» («Модель» — в своём блоке, IvaModelPatch.buildBlock).
    //  * «Категория», «Подкатегория» и «Вид инструмента» (с подписями и выпадающими списками) — над блоком
    //    IvaModelPatch.HELP (кнопки «Сгенерировать описание» в нём с 10.91 нет; нет справочника — над «Ценой»). «Модель» — под названием (IvaModelPatch.refresh).
    //  Штатные поля только переставляются внутри той же формы (removeView / addView — их слушатели и id не меняются);
    //  правка инструмента не меняется. Повторный вызов ничего не двигает, если всё уже на месте.

    static final String LBL = "iva_lbl:";
    static final String KIND_KEY = "kind";
    static final String[][] LABELS = {
        {"atPhotos", "Фотографии · до 5, первое — на обложке"}, {"atName", "Название инструмента *"}, {"atCat", "Категория *"},
        {"atSub", "Подкатегория"}, {KIND_KEY, "Вид инструмента"}, {"atPrice", "Цена за сутки, ₽ *"}, {"atDep", "Залог, ₽"},
        {"atTerms", "Условия аренды"},                                   // 10.87: «Описание» — заголовок раздела, своей подписи нет
    };
    static final int LABEL_TOP_DP = 12, FIELD_TOP_DP = 6;

    static View formChild(Activity a, LinearLayout form, String key) {
        if (key.startsWith(LBL)) return form.findViewWithTag(key);
        View v;
        if (KIND_KEY.equals(key)) v = form.findViewWithTag(ROW_TAG + FORM[0][2]);
        else {
            v = IvaPatch.view(a, key);                                   // 10.88: поле или id разметки («atSave»)
            if (v != null && "atPhotos".equals(key) && v.getParent() instanceof View) v = (View) v.getParent();   // лента фото — в HorizontalScrollView
        }
        return v != null && v.getParent() == form ? v : null;
    }

    static void topMargin(View v, int dp, Activity a) {
        if (v == null || !(v.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)) return;
        android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
        int px = IvaPatch.dp(a, dp);
        if (lp.topMargin != px) { lp.topMargin = px; v.setLayoutParams(lp); }
    }

    static void arrange(Activity a) {
        Object no = IvaClientPatch.get(a, FORM[0][5]);
        if (!(no instanceof View) || !(((View) no).getParent() instanceof LinearLayout)) return;
        LinearLayout form = (LinearLayout) ((View) no).getParent();
        // 1. подписи: есть у каждого пункта, стоят прямо над ним, видны вместе с ним
        for (String[] l : LABELS) {
            View f = formChild(a, form, l[0]);
            View lb = form.findViewWithTag(LBL + l[0]);
            if (f == null) { if (lb != null) lb.setVisibility(View.GONE); continue; }
            if (lb == null) {
                TextView t = new TextView(a);
                t.setTag(LBL + l[0]);
                t.setText(l[1]);
                t.setTextColor(IvaPatch.color(a, "gray", 0xff9aa1b0));
                t.setTextSize(13f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = IvaPatch.dp(a, LABEL_TOP_DP);
                form.addView(t, form.indexOfChild(f), lp);
                lb = t;
            } else if (lb.getParent() == form && form.indexOfChild(lb) != form.indexOfChild(f) - 1) {
                form.removeView(lb);
                form.addView(lb, form.indexOfChild(f));
            }
            int vis = f.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE;
            if (lb.getVisibility() != vis) lb.setVisibility(vis);
            if (KIND_KEY.equals(l[0])) topMargin(f.findViewWithTag(ROW_TAG + FORM[0][2] + ":field"), FIELD_TOP_DP, a);
            else topMargin(f, FIELD_TOP_DP, a);
        }
        // 2. 10.87: разделы, порядок пунктов, подсказки, ошибки, черновик, образцы; 10.88: частые условия (IvaFormPatch)
        try { IvaFormPatch.setup(a, form); } catch (Throwable ignore) {}
    }

    /** Каталог в памяти уже мог прийти без вида — дописываем, чтобы фильтр «Вид» сразу видел объявление. */
    static void patchCatalog(Activity a, String id, String kind) throws Exception {
        Object t = IvaClientPatch.get(a, "tools");
        List<Object> lists = new ArrayList<Object>();
        lists.add(t); lists.add(IvaSearchPatch.fullTools);
        for (Object l : lists) {
            if (!(l instanceof ArrayList)) continue;
            for (Object o : (ArrayList) l) {
                if (o instanceof JSONObject && id.equals(((JSONObject) o).optString("id", ""))) {
                    if (kind.isEmpty()) ((JSONObject) o).put("kind", JSONObject.NULL);
                    else ((JSONObject) o).put("kind", kind);
                }
            }
        }
    }
}

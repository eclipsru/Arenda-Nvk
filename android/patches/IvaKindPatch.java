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
    static String lookupPath(String email, String name, String since) {
        return "/rest/v1/tools?select=id,kind&owner_email=eq." + enc(email == null ? "" : email.trim().toLowerCase(Locale.ROOT))
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

    // ════════════════════════════════ 2. Загрузка справочника ════════════════════════════════

    static volatile List<Kind> kinds = null;       // null — ещё нет (или SQL не применён)
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
        try { consumePending(a, tab); } catch (Throwable ignore) {}
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
            ((TextView) f).setText(waiting ? "Вид инструмента…" : fieldText(all, sel[k]));
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

    /** Нажали «Опубликовать» (k=0) / «Сохранить» (k=1): запоминаем, что записать, если штатное сохранение удастся. */
    static void onSaveTouch(Activity a, int k) {
        try {
            pendingAt = 0;
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
        final String kind = pendingKind, id = pendingId, name = pendingName, email = pendingEmail;
        final String body = patchBody(kind);
        if (body == null) return;
        if (k == 0) sel[0] = "";                                         // опубликовано — форма «Добавить» начинается заново
        else orig[1] = kind;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String target = id;
                    if (k == 0) {
                        String[] r = IvaClientPatch.apiCall(a, lookupPath(email, name, isoUtc(System.currentTimeMillis() - FRESH_MS)), "GET", null, true);
                        if (r == null || r.length < 2 || r[0] == null || !r[0].startsWith("2")) return;
                        JSONArray arr = new JSONArray(r[1] == null ? "[]" : r[1].trim());
                        JSONObject o = arr.length() > 0 ? arr.optJSONObject(0) : null;
                        if (o == null) return;
                        if (!clean(o.isNull("kind") ? "" : o.optString("kind", "")).isEmpty()) return;   // вид уже есть — не трогаем
                        target = clean(o.optString("id", ""));
                    }
                    if (target.isEmpty()) return;
                    String[] p = IvaClientPatch.apiCall(a, patchPath(target), "PATCH", body, true);
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

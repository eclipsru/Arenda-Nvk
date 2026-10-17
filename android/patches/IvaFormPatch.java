package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 10.87 · Форма «Добавить инструмент» (просьба владельца 15.10.2026: «понятно, удобно, структурировано», решения — на нас).
 *  * Пункты собраны в разделы: «Что сдаёте» (фото, название, модель, категория / подкатегория / вид) → «Характеристики
 *    и состояние» → «Цена» → «Условия и доставка» → «Описание» → «Опубликовать» — в самом низу
 *    (кнопка «Сгенерировать описание» убрана в 10.91).
 *  * Внутри полей — примеры («Например: 800»), а не повтор подписи; обязательные подписи — со звёздочкой.
 *  * Цена за сутки обязательна. Нет названия, категории или цены — «Опубликовать» не срабатывает, под полем красная
 *    строка, экран прокручивается к первому такому полю (раньше пустая цена молча не отправлялась).
 *  * Черновик: заполненное (и фото) сохраняется само; открыли пустую форму — строка «Продолжить заполнение?».
 *    Опубликовали — черновик удаляется.
 *  * «Заполнить по образцу»: свои инструменты кнопками — всё, кроме фото (как «Создать копию» у Ozon).
 *  * 10.88 (просьба владельца 15.10): «Города выдачи» нет — пункт выдачи с адресом и временем работы уже в карточке.
 *    Под «Условиями аренды» — частые условия плашками: нажали — строка добавилась в условия, ещё раз — убралась;
 *    можно выбрать хоть все. Сначала — свои условия из прошлых объявлений, затем по питанию (бензин / аккумулятор), затем общие.
 *  * 10.89 (просьба владельца 15.10): сверху формы нет «Добавить инструмент», «Публикуется сразу…» и «Заполнить по
 *    образцу» — форма начинается с «Что сдаёте» (строка черновика остаётся — только когда он есть). Частые условия —
 *    одной строкой «Выбрать из частых условий ▾»: выбрали — условие в поле и пропало из списка (убрали из поля — вернулось).
 *    Фото — IvaPhotoPatch (5 окошек, большой просмотр, «Сделать фото» / «Из галереи»).
 *  * 10.88: кнопка «Опубликовать» в 10.14 — не поле MainActivity, а id разметки (atSave); раньше её не находили
 *    (IvaPatch.view) — она оставалась посреди формы, а проверка, «Состояние» и вид при нажатии не срабатывали.
 *  Штатные поля только переставляются внутри той же формы и получают другие подсказки — их слушатели, id и проверки
 *  не меняются; правка инструмента не меняется. Новых точек входа в штатный код нет (IvaPatch.onTab / onHub).
 */
final class IvaFormPatch {
    private IvaFormPatch() {}

    static final String SEC = "iva_sec:", ERR = "iva_err:", TERMS = "iva_terms", SAMPLE = "iva_sample", DRAFT = "iva_draft";
    static final String[][] SECTIONS = {
        {"1", "Что сдаёте"}, {"2", "Характеристики и состояние"}, {"3", "Цена"}, {"4", "Условия и доставка"}, {"5", "Описание"},
    };
    static final String[][] HINTS = {
        {"atName", "Например: перфоратор Makita HR2470"},
        {"atPrice", "Например: 800"},
        {"atDep", "Пусто — залог за 5 суток аренды"},
        {"atDescr", "Что умеет, для каких работ, что важно знать"},
        {"atTerms", "Напишите своё или выберите из списка ниже"},
        {"atDelPrice", "Цена доставки, ₽ за 1 км"},
    };
    static final String NATIVE_CAT_EMPTY = "Категория *", CAT_EMPTY = "Выберите категорию", SUB_EMPTY = "Выберите подкатегорию";
    static final String ERR_NAME = "Напишите название инструмента", ERR_CAT = "Выберите категорию", ERR_PRICE = "Укажите цену за сутки";
    static final String TERMS_PICK = "Выбрать из частых условий", TERMS_DONE = "Все частые условия уже добавлены";
    static final String TITLE_NATIVE = "Добавить инструмент", SUBTITLE_NATIVE = "Публикуется сразу";
    /** Общие условия проката инструмента (по одному на строку условий). */
    static final String[] TERMS_BASE = {
        "Оплата при получении", "Минимальный срок — 1 сутки",                         // 10.90: первая подсказка убрана (просьба владельца)
        "Залог возвращаем при сдаче", "Проверяем при выдаче и при возврате", "Вернуть чистым",
        "Расходники — за счёт арендатора", "Поломка по вине арендатора — ремонт за его счёт",
        "Покажем, как пользоваться", "Продление — предупредить заранее", "Оформляем договор аренды",
    };
    static final String[] TERMS_FUEL = {"Топливо — за счёт арендатора", "Вернуть с полным баком"};
    static final String[] TERMS_BATT = {"Аккумуляторы выдаём заряженными"};
    static final String SAMPLE_LABEL = "Заполнить по образцу своего инструмента (всё, кроме фото)";
    static final String DRAFT_CONT = "Продолжить", DRAFT_DROP = "Удалить черновик";
    static final int MAX_PICS = 5, SAMPLE_MAX = 15;
    static final boolean SHOW_SAMPLE = false;                                         // 10.89: строка «по образцу» убрана
    static final long SAVE_DELAY_MS = 1500, PUBLISH_QUIET_MS = 30000;

    /** Порядок пунктов формы. Ключ — штатное поле («atName»), подпись (IvaKindPatch.LBL + поле), «kind» или тег нашего блока. */
    static final String[] ORDER = {
        SAMPLE, DRAFT,
        SEC + "1", IvaKindPatch.LBL + "atPhotos", "atPhotos", IvaPhotoPatch.TAG, IvaKindPatch.LBL + "atName", "atName", ERR + "atName",
        IvaModelPatch.NAME_SUG, IvaModelPatch.BLOCK,
        IvaKindPatch.LBL + "atCat", "atCat", ERR + "atCat", "atCatSug", IvaKindPatch.LBL + "atSub", "atSub", "atSubSug",
        IvaKindPatch.LBL + IvaKindPatch.KIND_KEY, IvaKindPatch.KIND_KEY,
        SEC + "2", IvaModelPatch.SPECS, IvaModelPatch.KIT, IvaPatch.COND_ROW_TAG + ":atTerms",
        SEC + "3", IvaModelPatch.HELP + ":price", IvaKindPatch.LBL + "atPrice", "atPrice", ERR + "atPrice",
        IvaKindPatch.LBL + "atDep", "atDep",
        SEC + "4", IvaToolPointPatch.TAG + ":0", IvaKindPatch.LBL + "atTerms", "atTerms", TERMS, ERR + "atTerms", IvaPatch.DEL_ROW_TAG + ":atDelToggle", "atDelToggle", "atDelPrice",
        SEC + "5", "atDescr", IvaModelPatch.HELP, ERR + "atDescr",
        "atSave",
    };

    // ════════════════════════════════ чистая логика (под тестом) ════════════════════════════════

    /** Новый порядок детей: known — текущие индексы наших пунктов в нужном порядке. Пункты занимают те же места
     *  (по возрастанию), прочие дети (заголовок формы, отступ внизу) остаются где были. null — уже на месте. */
    static int[] permute(int count, int[] known) {
        if (known == null || known.length == 0 || count <= 0) return null;
        HashSet<Integer> seen = new HashSet<Integer>();
        for (int k : known) if (k < 0 || k >= count || !seen.add(k)) return null;
        int[] slots = known.clone();
        Arrays.sort(slots);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = i;
        boolean same = true;
        for (int j = 0; j < slots.length; j++) {
            out[slots[j]] = known[j];
            if (slots[j] != known[j]) same = false;
        }
        return same ? null : out;
    }

    /** Цена за сутки: целое число рублей больше нуля (пробелы внутри можно). */
    static boolean priceOk(String s) {
        if (s == null) return false;
        String t = s.replace(" ", "").replace("\u00a0", "").trim();
        if (!t.matches("[0-9]{1,7}([.,][0-9]{1,2})?")) return false;
        try { return Double.parseDouble(t.replace(',', '.')) > 0; } catch (Throwable e) { return false; }
    }

    /** Черновик в строку: «ключ=значение» построчно, перевод строки и «\» — экранируются. Порядок ключей сохраняется. */
    static String enc(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        if (m == null) return "";
        for (Map.Entry<String, String> e : m.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (k == null || k.isEmpty() || k.indexOf('=') >= 0 || k.indexOf('\n') >= 0 || v == null) continue;
            b.append(k).append('=').append(v.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")).append('\n');
        }
        return b.toString();
    }

    static LinkedHashMap<String, String> dec(String s) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        if (s == null) return m;
        for (String line : s.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String raw = line.substring(i + 1);
            StringBuilder v = new StringBuilder();
            for (int j = 0; j < raw.length(); j++) {
                char c = raw.charAt(j);
                if (c == '\\' && j + 1 < raw.length()) {
                    char n = raw.charAt(++j);
                    v.append(n == 'n' ? '\n' : n);
                } else v.append(c);
            }
            m.put(line.substring(0, i), v.toString());
        }
        return m;
    }

    /** Есть ли что сохранять: название, цена, описание, условия или фото. */
    static boolean hasContent(Map<String, String> st) {
        if (st == null) return false;
        for (String k : new String[]{"name", "price", "descr", "terms"}) if (!g(st, k).trim().isEmpty()) return true;
        return num(g(st, "pics")) > 0;
    }

    // ───── 10.88: частые условия аренды ─────

    /** Ключ строки условий для сравнения: без регистра, «ё» = «е», без маркера в начале и точки в конце. */
    static String termKey(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('\u00a0', ' ');
        t = t.replaceAll("^[\\s•·*–—-]+", "").replaceAll("[\\s.;,!]+$", "").replaceAll("\\s+", " ");
        return t.trim();
    }

    /** Есть ли условие отдельной строкой в поле «Условия аренды». */
    static boolean termHas(String terms, String p) {
        String k = termKey(p);
        if (terms == null || k.isEmpty()) return false;
        for (String line : terms.split("\n")) if (termKey(line).equals(k)) return true;
        return false;
    }

    /** Нажали плашку: условия нет — дописать отдельной строкой в конец; есть — убрать эту строку (остальное как было). */
    static String termToggle(String terms, String p) {
        String t = terms == null ? "" : terms.replace("\r", "");
        String k = termKey(p);
        if (k.isEmpty()) return t;
        if (termHas(t, p)) {
            StringBuilder b = new StringBuilder();
            for (String line : t.split("\n")) {
                if (termKey(line).equals(k)) continue;
                if (b.length() > 0) b.append('\n');
                b.append(line);
            }
            return b.toString().replaceAll("\\s+$", "");
        }
        String base = t.replaceAll("\\s+$", "");
        return base.isEmpty() ? p.trim() : base + "\n" + p.trim();
    }

    /** Частые условия: по питанию (бензин/дизель — топливо, аккумулятор — заряд), затем общие; без повторов. */
    static List<String> termOptions(String src) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        List<String> all = new ArrayList<String>();
        String s = src == null ? "" : src.trim();
        if ("Бензин".equals(s) || "Дизель".equals(s)) all.addAll(Arrays.asList(TERMS_FUEL));
        if (IvaModelPatch.BATTERY.equals(s)) all.addAll(Arrays.asList(TERMS_BATT));
        all.addAll(Arrays.asList(TERMS_BASE));
        for (String p : all) { String k = termKey(p); if (!k.isEmpty() && !m.containsKey(k)) m.put(k, p.trim()); }
        return new ArrayList<String>(m.values());
    }

    /** 10.89: что осталось в выпадающем списке — частые условия, которых ещё нет строкой в поле. */
    static List<String> termLeft(String src, String terms) {
        List<String> out = new ArrayList<String>();
        for (String p : termOptions(src)) if (!termHas(terms, p)) out.add(p);
        return out;
    }

    /** 10.89: штатный заголовок и подзаголовок формы (их скрываем). */
    static boolean nativeTop(CharSequence text) {
        String t = text == null ? "" : text.toString().trim();
        return t.equals(TITLE_NATIVE) || t.startsWith(SUBTITLE_NATIVE);
    }

    /** Строка черновика: «Перфоратор Makita HR2470 · 3 фото». */
    static String draftTitle(Map<String, String> st) {
        String n = g(st, "name").trim();
        int p = num(g(st, "pics"));
        String t = n.isEmpty() ? "без названия" : "«" + cut(n, 40) + "»";
        return "Есть незаконченный инструмент: " + t + (p > 0 ? " · " + p + " фото" : "");
    }

    static String cut(String s, int n) {
        String t = s == null ? "" : s.trim();
        return t.length() <= n ? t : t.substring(0, n - 1).trim() + "…";
    }

    /** Образцы: свои объявления, новые сверху, без повторов названия, не больше max. */
    static List<Map<String, String>> samples(List<Map<String, String>> rows, int max) {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        HashSet<String> seen = new HashSet<String>();
        if (rows == null) return out;
        for (Map<String, String> r : rows) {
            String n = g(r, "name").trim();
            String k = n.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
            if (n.isEmpty() || !seen.add(k)) continue;
            out.add(r);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** Объявление из базы → состояние формы (условия — без строки «Состояние: …», она становится кнопкой). */
    static LinkedHashMap<String, String> fromTool(JSONObject j) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        m.put("name", js(j, "name"));
        m.put("cat", js(j, "cat"));
        m.put("sub", js(j, "sub"));
        m.put("kind", js(j, "kind"));
        m.put("model", js(j, "model_id"));
        m.put("price", money(js(j, "price")));
        m.put("dep", money(js(j, "deposit")));
        m.put("descr", js(j, "descr"));
        String terms = js(j, "terms");
        int c = IvaPatch.condIndex(terms);
        m.put("terms", IvaPatch.condStrip(terms));
        m.put("cond", String.valueOf(c < 0 ? IvaPatch.COND_DEFAULT : c));
        m.put("deliv", j != null && j.optBoolean("delivery", false) ? "1" : "0");
        m.put("delp", money(js(j, "delivery_price")));
        JSONObject sp = j == null ? null : j.optJSONObject("specs");
        if (sp != null) for (Iterator<String> it = sp.keys(); it.hasNext(); ) {
            String k = it.next();
            String v = sp.isNull(k) ? "" : String.valueOf(sp.opt(k));
            if (!v.isEmpty() && !"null".equals(v)) m.put("spec." + k, v);
        }
        JSONArray kit = j == null ? null : j.optJSONArray("kit");
        if (kit != null) for (int i = 0; i < kit.length(); i++) {
            String v = IvaKindPatch.clean(kit.optString(i, ""));
            if (!v.isEmpty()) m.put("kit." + i, v);
        }
        return m;
    }

    /** «800.0» → «800», «1250.5» → «1250.5», пусто / 0 → пусто. */
    static String money(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || "null".equals(t)) return "";
        try {
            double d = Double.parseDouble(t);
            if (d <= 0) return "";
            if (d == Math.rint(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        } catch (Throwable e) { return ""; }
    }

    static String jsonEsc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    static Map<String, String> specsOf(Map<String, String> st) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : st.entrySet())
            if (e.getKey().startsWith("spec.") && e.getValue() != null && !e.getValue().isEmpty()) m.put(e.getKey().substring(5), e.getValue());
        return m;
    }

    static List<String> kitOf(Map<String, String> st) {
        List<String> l = new ArrayList<String>();
        for (Map.Entry<String, String> e : st.entrySet())
            if (e.getKey().startsWith("kit.") && e.getValue() != null && !e.getValue().isEmpty()) l.add(e.getValue());
        return l;
    }

    static String g(Map<String, String> m, String k) { String v = m == null ? null : m.get(k); return v == null ? "" : v; }

    static int num(String s) { try { return Integer.parseInt(s.trim()); } catch (Throwable e) { return 0; } }

    static String js(JSONObject j, String k) {
        if (j == null || !j.has(k) || j.isNull(k)) return "";
        return IvaKindPatch.clean(String.valueOf(j.opt(k)));
    }

    // ════════════════════════════════ раскладка ════════════════════════════════

    static final WeakHashMap<View, Boolean> watched = new WeakHashMap<View, Boolean>();
    static final WeakHashMap<View, String> sigs = new WeakHashMap<View, String>();   // что уже нарисовано в строках черновика / образцов
    static boolean saveBlocked = false;
    static long publishAt = 0, clearedFor = 0;
    static String lastSaved = null;
    static String picsSig = "";
    static long pendingModel = 0;
    static volatile List<JSONObject> mine = null;
    static volatile boolean mineLoading = false;
    static volatile long mineTriedAt = 0;

    /** Вызывается из IvaKindPatch.arrange (форма «Добавить» открыта или поменялась). */
    static void setup(Activity a, LinearLayout form) {
        ensureViews(a, form);
        hideTop(form);
        try { IvaPhotoPatch.setup(a, form); } catch (Throwable ignore) {}       // 10.89: 5 окошек фото
        hints(a);
        paintErrors(a, form, false);
        paintTop(a, form);
        paintTerms(a, form);
        try { IvaToolPointPatch.ensure(a, form); } catch (Throwable ignore) {}   // 10.101: «Пункт выдачи» (2+ пункта)
        layout(a, form);
        resolveModel(a);
        scheduleSave(a);
        try { arrows(a, form); } catch (Throwable ignore) {}                   // 10.91: стрелки списков
    }

    // ───── 10.91 (просьба владельца 15.10.2026): поля со списком и стрелкой справа — «Категория», «Подкатегория»,
    //  «Вид инструмента», «Выбрать из частых условий» ─────
    //  * список закрыт — стрелка вправо (как нарисована ic_chevron), открыт — вниз (поворот на 90°);
    //  * повторное нажатие на поле с открытым списком закрывает его (штатно «Категория» / «Подкатегория» открывались
    //    заново: hideDrops + showPickDrop) — свой OnTouchListener, штатный обработчик нажатия не заменяется;
    //  * «Далее» на клавиатуре (фокус переходит в следующее поле формы) закрывает открытые списки.
    static final float ARROW_OPEN = 90f, ARROW_CLOSED = 0f;
    static final java.util.Map<View, Boolean> arrowForms = new java.util.WeakHashMap<View, Boolean>();

    static float arrowAngle(boolean open) { return open ? ARROW_OPEN : ARROW_CLOSED; }

    /** Палец отпущен на поле, список которого был открыт в момент касания, — закрыть (и не открывать заново). */
    static boolean tapCloses(boolean openAtDown, boolean up) { return openAtDown && up; }

    /** Фокус ушёл из поля ввода формы в другое поле ввода («Далее» или нажатие на другое поле) — списки закрыть.
     *  Фокус просто снят (клавиатура спрятана) — ничего не делаем. */
    static boolean closeOnFocus(boolean oldInForm, boolean oldEdit, boolean newEdit, boolean same) {
        return oldInForm && oldEdit && newEdit && !same;
    }

    /** Стрелка справа, поворачиваемая без замены картинки (RotateDrawable из кода — только с API 23, у нас minSdk 21). */
    static final class Turn extends android.graphics.drawable.Drawable implements android.graphics.drawable.Drawable.Callback {
        final android.graphics.drawable.Drawable in;
        float deg = ARROW_CLOSED;
        Turn(android.graphics.drawable.Drawable in) { this.in = in; in.setCallback(this); }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            int s = c.save();
            c.rotate(deg, b.exactCenterX(), b.exactCenterY());
            in.setBounds(b);
            in.draw(c);
            c.restoreToCount(s);
        }
        @Override public void setAlpha(int al) { in.setAlpha(al); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { in.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return in.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return in.getIntrinsicHeight(); }
        @Override public ConstantState getConstantState() { return in.getConstantState(); }   // копия (IvaKindPatch.copy) — простая стрелка
        @Override public void invalidateDrawable(android.graphics.drawable.Drawable d) { invalidateSelf(); }
        @Override public void scheduleDrawable(android.graphics.drawable.Drawable d, Runnable r, long when) { scheduleSelf(r, when); }
        @Override public void unscheduleDrawable(android.graphics.drawable.Drawable d, Runnable r) { unscheduleSelf(r); }
    }

    /** Стрелка справа у поля: вниз — список открыт, вправо — закрыт. Стрелки нет — ничего. */
    static void arrow(TextView f, boolean open) {
        if (f == null) return;
        android.graphics.drawable.Drawable[] ds = f.getCompoundDrawables();
        if (ds == null || ds.length < 4 || ds[2] == null) return;
        Turn t;
        if (ds[2] instanceof Turn) t = (Turn) ds[2];
        else {
            t = new Turn(ds[2]);
            t.setBounds(ds[2].getBounds());
            f.setCompoundDrawables(ds[0], ds[1], t, ds[3]);
        }
        float d = arrowAngle(open);
        if (t.deg == d) return;
        t.deg = d;
        t.invalidateSelf();
        f.invalidate();
    }

    static boolean open(Object list) { return list instanceof View && ((View) list).getVisibility() == View.VISIBLE; }

    static View kindView(View form, String part) {
        return form.findViewWithTag(IvaKindPatch.ROW_TAG + IvaKindPatch.FORM[0][2] + part);
    }

    /** Стрелки «Категории», «Подкатегории» и «Вида» — по тому, виден ли их список («Частые условия» — в paintTerms). */
    static void syncArrows(Activity a, View form) {
        Object c = IvaClientPatch.get(a, "atCat"), s = IvaClientPatch.get(a, "atSub");
        if (c instanceof TextView) arrow((TextView) c, open(IvaClientPatch.get(a, "atCatSug")));
        if (s instanceof TextView) arrow((TextView) s, open(IvaClientPatch.get(a, "atSubSug")));
        View kf = kindView(form, ":field");
        if (kf instanceof TextView) arrow((TextView) kf, open(kindView(form, ":drop")));
    }

    /** Закрыть все списки формы: штатные «Категория» / «Подкатегория», «Вид», «Частые условия», подсказки названия и модели. */
    static void closeDrops(Activity a, LinearLayout form) {
        for (String f : new String[]{"atCatSug", "atSubSug"}) IvaKindPatch.hideDrop(a, f);
        for (View v : new View[]{kindView(form, ":drop"), form.findViewWithTag(IvaModelPatch.NAME_SUG),
                form.findViewWithTag(IvaModelPatch.BLOCK + ":drop")})
            if (v != null) v.setVisibility(View.GONE);
        if (termsOpen) { termsOpen = false; paintTerms(a, form); }
        syncArrows(a, form);
    }

    static boolean inside(View root, View v) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == root) return true;
        return false;
    }

    /** Повторное нажатие на штатное поле с открытым списком — закрыть список; штатное нажатие при этом не срабатывает. */
    static void tapToggle(final TextView field, final View list) {
        field.setOnTouchListener(new View.OnTouchListener() {
            boolean wasOpen = false;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                try {
                    int ac = e.getActionMasked();
                    if (ac == android.view.MotionEvent.ACTION_DOWN) wasOpen = open(list);
                    if (!tapCloses(wasOpen, ac == android.view.MotionEvent.ACTION_UP)) return false;
                    wasOpen = false;
                    list.setVisibility(View.GONE);
                    android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                    c.setAction(android.view.MotionEvent.ACTION_CANCEL);           // снять нажатое состояние без «клика»
                    v.onTouchEvent(c);
                    c.recycle();
                    return true;
                } catch (Throwable t) { return false; }
            }
        });
    }

    static void arrows(final Activity a, final LinearLayout form) {
        syncArrows(a, form);
        if (arrowForms.containsKey(form)) return;
        arrowForms.put(form, Boolean.TRUE);
        Object c = IvaClientPatch.get(a, "atCat"), cs = IvaClientPatch.get(a, "atCatSug");
        Object s = IvaClientPatch.get(a, "atSub"), ss = IvaClientPatch.get(a, "atSubSug");
        if (c instanceof TextView && cs instanceof View) tapToggle((TextView) c, (View) cs);
        if (s instanceof TextView && ss instanceof View) tapToggle((TextView) s, (View) ss);
        android.view.ViewTreeObserver vto = form.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {                                 // список открыли / закрыли — стрелка следом
                try { if (form.isShown()) syncArrows(a, form); } catch (Throwable ignore) {}
            }
        });
        vto.addOnGlobalFocusChangeListener(new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override public void onGlobalFocusChanged(View o, View n) {
                try {
                    if (closeOnFocus(o != null && inside(form, o), o instanceof EditText, n instanceof EditText, o == n))
                        closeDrops(a, form);
                } catch (Throwable ignore) {}
            }
        });
    }

    static void ensureViews(Activity a, LinearLayout form) {
        View save = IvaPatch.view(a, "atSave");                                     // 10.88: id разметки, не поле
        int at = save != null && save.getParent() == form ? form.indexOfChild(save) : form.getChildCount();
        for (int i = 0; i < SECTIONS.length; i++) {
            if (form.findViewWithTag(SEC + SECTIONS[i][0]) != null) continue;
            TextView t = new TextView(a);
            t.setTag(SEC + SECTIONS[i][0]);
            t.setText(SECTIONS[i][1]);
            t.setTextSize(17f);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(IvaPatch.color(a, "text", 0xfff2f4f8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = IvaPatch.dp(a, i == 0 ? 14 : 30);
            form.addView(t, at++, lp);
        }
        for (String f : new String[]{"atName", "atCat", "atPrice", "atTerms", "atDescr"}) {   // 10.92: + условия, описание
            if (form.findViewWithTag(ERR + f) != null) continue;
            TextView e = new TextView(a);
            e.setTag(ERR + f);
            e.setTextSize(13f);
            e.setTextColor(IvaPatch.color(a, "red", 0xffff5a5f));
            e.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = IvaPatch.dp(a, 4);
            form.addView(e, at++, lp);
        }
        if (form.findViewWithTag(TERMS) == null) {
            LinearLayout t = new LinearLayout(a);
            t.setTag(TERMS);
            t.setOrientation(LinearLayout.VERTICAL);
            form.addView(t, at++, new LinearLayout.LayoutParams(-1, -2));
        }
        if (form.findViewWithTag(SAMPLE) == null) {
            LinearLayout s = new LinearLayout(a);
            s.setTag(SAMPLE);
            s.setOrientation(LinearLayout.VERTICAL);
            s.setVisibility(View.GONE);
            form.addView(s, at++, new LinearLayout.LayoutParams(-1, -2));
        }
        if (form.findViewWithTag(DRAFT) == null) {
            LinearLayout d = new LinearLayout(a);
            d.setTag(DRAFT);
            d.setOrientation(LinearLayout.VERTICAL);
            d.setVisibility(View.GONE);
            form.addView(d, at, new LinearLayout.LayoutParams(-1, -2));
        }
        for (String f : new String[]{"atName", "atPrice", "atDep", "atDescr", "atTerms", "atDelPrice"}) watchField(a, f);
    }

    /** 10.89: «Добавить инструмент» и «Публикуется сразу…» — скрыты (штатные TextView без id и тега). */
    static void hideTop(LinearLayout form) {
        for (int i = 0; i < form.getChildCount(); i++) {
            View c = form.getChildAt(i);
            if (c.getTag() == null && c instanceof TextView && !(c instanceof EditText) && !(c instanceof android.widget.Button)
                    && c.getVisibility() != View.GONE && nativeTop(((TextView) c).getText())) c.setVisibility(View.GONE);
        }
    }

    static View child(Activity a, LinearLayout form, String key) {
        View v;
        if (key.startsWith(IvaKindPatch.LBL) || IvaKindPatch.KIND_KEY.equals(key) || (key.startsWith("at") && key.length() > 2
                && Character.isUpperCase(key.charAt(2)))) v = IvaKindPatch.formChild(a, form, key);
        else v = form.findViewWithTag(key);
        return v != null && v.getParent() == form ? v : null;
    }

    static void layout(Activity a, LinearLayout form) {
        List<View> want = new ArrayList<View>();
        for (String k : ORDER) {
            View v = child(a, form, k);
            if (v != null && !want.contains(v)) want.add(v);
        }
        int n = form.getChildCount();
        int[] known = new int[want.size()];
        for (int i = 0; i < known.length; i++) known[i] = form.indexOfChild(want.get(i));
        int[] plan = permute(n, known);
        if (plan == null) return;
        View[] old = new View[n];
        for (int i = 0; i < n; i++) old[i] = form.getChildAt(i);
        View focus = form.findFocus();
        for (int pos = 0; pos < n; pos++) {
            View w = old[plan[pos]];
            if (form.getChildAt(pos) == w) continue;
            form.removeView(w);
            form.addView(w, pos);
        }
        if (focus != null && !focus.isFocused()) focus.requestFocus();
        keepSaveLast(a, form);
    }

    /** 10.88: «Опубликовать» — последним видимым пунктом: что видно ниже кнопки (кроме штатного отступа) — над ней. */
    static void keepSaveLast(Activity a, LinearLayout form) {
        View save = child(a, form, "atSave");
        if (save == null) return;
        int i = form.indexOfChild(save) + 1;
        while (i < form.getChildCount()) {
            View c = form.getChildAt(i);
            if (c.getVisibility() == View.GONE || c instanceof android.widget.Space) { i++; continue; }
            form.removeView(c);
            form.addView(c, form.indexOfChild(save));
            i = form.indexOfChild(save) + 1;
        }
    }

    static void hints(Activity a) {
        for (String[] h : HINTS) {
            Object o = IvaClientPatch.get(a, h[0]);
            if (o instanceof TextView && !h[1].equals(String.valueOf(((TextView) o).getHint()))) ((TextView) o).setHint(h[1]);
        }
        Object c = IvaClientPatch.get(a, "atCat"), s = IvaClientPatch.get(a, "atSub");
        if (c instanceof TextView && str(IvaClientPatch.get(a, "atCatKey")).isEmpty()
                && NATIVE_CAT_EMPTY.equals(String.valueOf(((TextView) c).getText()))) ((TextView) c).setText(CAT_EMPTY);
        if (s instanceof TextView && str(IvaClientPatch.get(a, "atSubKey")).isEmpty()
                && IvaKindPatch.SUB_HINT.equals(String.valueOf(((TextView) s).getText()))) ((TextView) s).setText(SUB_EMPTY);
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    static String text(Activity a, String field) {
        Object o = IvaClientPatch.get(a, field);
        return o instanceof TextView ? String.valueOf(((TextView) o).getText()) : "";
    }

    // ───── проверка перед «Опубликовать» ─────

    /** Касание «Опубликовать» (IvaPatch.condRow). true — нажатие не пропускаем: под полями ошибки. */
    // 10.92: сомнительное слово в объявлении — публикуется, но база снимает его с показа до проверки создателем
    static String heldWord = "";

    /** Первое сомнительное слово (мат сюда не доходит — он не даёт опубликовать) или "". */
    static String heldWord(String all) {
        try { IvaCensorPatch.Res r = IvaCensorPatch.check(all); return r.level == 1 ? r.first(1) : ""; } catch (Throwable e) { return ""; }
    }

    static String heldMsg(String w) {
        return "Объявление отправлено на проверку: слово «" + w + "» похоже на грубое. Оно появится в каталоге после проверки.";
    }

    static boolean onSaveTouch(Activity a, int action) {
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            saveBlocked = false;
            Object no = IvaClientPatch.get(a, "atName");
            if (!(no instanceof View) || !(((View) no).getParent() instanceof LinearLayout)) return false;
            LinearLayout form = (LinearLayout) ((View) no).getParent();
            saveBlocked = paintErrors(a, form, true);
            if (!saveBlocked) publishAt = System.currentTimeMillis();
            heldWord = saveBlocked ? "" : heldWord(text(a, "atName") + "\n" + text(a, "atDescr") + "\n" + text(a, "atTerms"));
        }
        return saveBlocked;
    }

    /** show — нажали «Опубликовать»: показать все ошибки и прокрутить к первой. Иначе — только убрать исправленные. */
    static boolean paintErrors(Activity a, LinearLayout form, boolean show) {
        String[][] checks = {
            {"atName", text(a, "atName").trim().isEmpty() ? ERR_NAME : IvaCensorPatch.fieldError(text(a, "atName"))},
            {"atCat", str(IvaClientPatch.get(a, "atCatKey")).isEmpty() ? ERR_CAT : ""},
            {"atPrice", priceOk(text(a, "atPrice")) ? "" : ERR_PRICE},
            {"atTerms", IvaCensorPatch.fieldError(text(a, "atTerms"))},                  // 10.92: мат не публикуется
            {"atDescr", IvaCensorPatch.fieldError(text(a, "atDescr"))},
        };
        View first = null;
        for (String[] c : checks) {
            View e = form.findViewWithTag(ERR + c[0]);
            if (!(e instanceof TextView)) continue;
            boolean bad = !c[1].isEmpty();
            if (show) {
                ((TextView) e).setText(c[1]);
                e.setVisibility(bad ? View.VISIBLE : View.GONE);
                if (bad && first == null) {
                    Object f = IvaClientPatch.get(a, c[0]);
                    first = f instanceof View ? (View) f : e;
                }
            } else if (!bad && e.getVisibility() == View.VISIBLE) e.setVisibility(View.GONE);
        }
        if (first != null) {
            scrollTo(a, first);
            IvaNotifyPatch.flash(first);
        }
        return first != null;
    }

    static void scrollTo(Activity a, View v) {
        int y = 0;
        View cur = v;
        ViewParent p = v.getParent();
        while (p instanceof View && !(p instanceof ScrollView)) { y += cur.getTop(); cur = (View) p; p = cur.getParent(); }
        if (p instanceof ScrollView) {
            y += cur.getTop();
            ((ScrollView) p).smoothScrollTo(0, Math.max(0, y - IvaPatch.dp(a, 90)));
        }
    }

    static void watchField(final Activity a, final String field) {
        Object o = IvaClientPatch.get(a, field);
        if (!(o instanceof TextView) || watched.containsKey(o)) return;
        final TextView tv = (TextView) o;
        watched.put(tv, Boolean.TRUE);
        tv.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                try {
                    if (tv.getParent() instanceof LinearLayout) paintErrors(a, (LinearLayout) tv.getParent(), false);
                    if ("atName".equals(field) || "atPrice".equals(field)) paintTopLater(a, tv);
                    if ("atTerms".equals(field) && tv.getParent() instanceof LinearLayout) paintTerms(a, (LinearLayout) tv.getParent());
                    scheduleSave(a);
                } catch (Throwable ignore) {}
            }
        });
    }

    // ───── 10.89: частые условия — одной строкой с выпадающим списком под «Условиями аренды» ─────

    static boolean termsOpen = false;

    static void paintTerms(final Activity a, final LinearLayout form) {
        View bv = form.findViewWithTag(TERMS);
        if (!(bv instanceof LinearLayout)) return;
        final LinearLayout box = (LinearLayout) bv;
        final String terms = text(a, "atTerms");
        List<String> left = termLeft(str(IvaModelPatch.specVals.get(IvaModelPatch.SRC)), terms);
        StringBuilder sig = new StringBuilder(termsOpen ? "1|" : "0|");
        for (String p : left) sig.append(p).append('|');
        if (sig.toString().equals(sigs.get(box))) return;
        sigs.put(box, sig.toString());
        box.removeAllViews();
        if (left.isEmpty()) termsOpen = false;
        Object sv = IvaClientPatch.get(a, "atSub"), sug = IvaClientPatch.get(a, "atSubSug");
        TextView f = new TextView(a);
        f.setText(left.isEmpty() ? TERMS_DONE : TERMS_PICK);
        f.setTextSize(15f);
        f.setTextColor(left.isEmpty() ? IvaPatch.color(a, "gray2", 0xff6b7080) : IvaPatch.color(a, "text", 0xfff2f4f8));
        f.setGravity(android.view.Gravity.CENTER_VERTICAL);
        f.setSingleLine(true);
        f.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (sv instanceof TextView) {                                                 // как поле «Подкатегория»: фон, отступы, стрелка
            TextView st = (TextView) sv;
            f.setPadding(st.getPaddingLeft(), st.getPaddingTop(), st.getPaddingRight(), st.getPaddingBottom());
            f.setBackground(IvaKindPatch.copy(st.getBackground(), a, "bg_field"));
            try {
                android.graphics.drawable.Drawable[] ds = st.getCompoundDrawables();
                android.graphics.drawable.Drawable chev = ds != null && ds.length > 2 ? IvaKindPatch.copy(ds[2], a, null) : null;
                if (chev != null && !left.isEmpty()) {
                    chev.setBounds(ds[2].getBounds());
                    f.setCompoundDrawables(null, null, chev, null);
                    arrow(f, termsOpen);                                              // 10.91: открыт — вниз, закрыт — вправо
                }
                f.setCompoundDrawablePadding(st.getCompoundDrawablePadding());
            } catch (Throwable ignore) {}
        } else {
            f.setBackground(IvaPatch.drawable(a, "bg_field"));
            f.setPadding(IvaPatch.dp(a, 15), IvaPatch.dp(a, 15), IvaPatch.dp(a, 15), IvaPatch.dp(a, 15));
        }
        f.setContentDescription(left.isEmpty() ? TERMS_DONE : TERMS_PICK + (termsOpen ? ". Список открыт" : ". Открыть список"));
        if (!left.isEmpty()) {
            f.setClickable(true);
            f.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { termsOpen = !termsOpen; IvaSearchPatch.hideKeyboard(a, v); paintTerms(a, form); } catch (Throwable ignore) {}
                }
            });
        }
        box.addView(f, IvaModelPatch.lpTop(a, 10));
        if (!termsOpen || left.isEmpty()) return;
        LinearLayout drop = new LinearLayout(a);
        drop.setOrientation(LinearLayout.VERTICAL);
        drop.setBackground(IvaKindPatch.copy(sug instanceof View ? ((View) sug).getBackground() : null, a, "bg_card"));
        for (final String p : left) {
            TextView t = new TextView(a);
            t.setText(p);
            t.setTextSize(15f);
            t.setTextColor(IvaPatch.color(a, "text", 0xfff2f4f8));
            t.setMinHeight(IvaPatch.dp(a, 44));
            t.setGravity(android.view.Gravity.CENTER_VERTICAL);
            t.setPadding(IvaPatch.dp(a, 15), IvaPatch.dp(a, 10), IvaPatch.dp(a, 15), IvaPatch.dp(a, 10));
            t.setContentDescription(p + ". Добавить в условия");
            IvaSearchPatch.feedback(a, t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Object o = IvaClientPatch.get(a, "atTerms");
                        if (!(o instanceof EditText)) return;
                        EditText e = (EditText) o;
                        String cur = String.valueOf(e.getText());
                        if (termHas(cur, p)) return;
                        String nt = termToggle(cur, p);                               // дописать отдельной строкой
                        e.setText(nt);
                        e.setSelection(nt.length());
                        paintTerms(a, form);                                          // пункт пропал из списка
                    } catch (Throwable ignore) {}
                }
            });
            drop.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        box.addView(drop, IvaModelPatch.lpTop(a, 4));
    }

    // ───── свои инструменты: образцы и частые условия ─────

    static void loadMine(final Activity a) {
        long now = System.currentTimeMillis();
        if (mine != null || mineLoading || now - mineTriedAt < 60000) return;
        final String email = str(IvaClientPatch.get(a, "userEmail")).toLowerCase(Locale.ROOT);
        if (email.isEmpty()) return;
        mineLoading = true;
        mineTriedAt = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/tools?select=*&owner_email=eq." + IvaKindPatch.enc(email)
                        + "&order=created_at.desc&limit=40", "GET", null, true);
                    if (!IvaModelPatch.ok(r)) return;
                    JSONArray arr = new JSONArray(r[1].trim());
                    List<JSONObject> out = new ArrayList<JSONObject>();
                    for (int i = 0; i < arr.length(); i++) { JSONObject j = arr.optJSONObject(i); if (j != null) out.add(j); }
                    mine = out;
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try {
                                Object no = IvaClientPatch.get(a, "atName");
                                if (no instanceof View && ((View) no).getParent() instanceof LinearLayout) {
                                    paintTop(a, (LinearLayout) ((View) no).getParent());
                                    paintTerms(a, (LinearLayout) ((View) no).getParent());
                                }
                            } catch (Throwable ignore) {}
                        }
                    });
                } catch (Throwable ignore) {
                } finally { mineLoading = false; }
            }
        }).start();
    }

    /** Строки над формой (черновик, образцы) — только пока форма пустая. */
    static void paintTop(final Activity a, LinearLayout form) {
        if (SHOW_SAMPLE) loadMine(a);
        View dv = form.findViewWithTag(DRAFT), sv = form.findViewWithTag(SAMPLE);
        boolean empty = !hasContent(collect(a, false));
        final LinkedHashMap<String, String> d = empty ? draft(a) : null;
        if (dv instanceof LinearLayout) {
            LinearLayout box = (LinearLayout) dv;
            if (d == null || !hasContent(d)) box.setVisibility(View.GONE);
            else {
                String title = draftTitle(d);
                if (!title.equals(sigs.get(box))) {
                    box.removeAllViews();
                    sigs.put(box, title);
                    android.graphics.drawable.Drawable bg = IvaPatch.drawable(a, "bg_field");
                    if (bg != null) box.setBackground(bg);
                    box.setPadding(IvaPatch.dp(a, 14), IvaPatch.dp(a, 12), IvaPatch.dp(a, 14), IvaPatch.dp(a, 12));
                    TextView t = new TextView(a);
                    t.setText(title);
                    t.setTextSize(14f);
                    t.setTextColor(IvaPatch.color(a, "text", 0xfff2f4f8));
                    box.addView(t, new LinearLayout.LayoutParams(-1, -2));
                    LinearLayout row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    TextView go = IvaSearchPatch.chip(a, DRAFT_CONT, true, false);
                    go.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { try { apply(a, draft(a), true); } catch (Throwable ignore) {} }
                    });
                    TextView del = IvaSearchPatch.chip(a, DRAFT_DROP, false, false);
                    del.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try {
                                clearDraft(a);
                                View b = v.getRootView() == null ? null : v.getRootView().findViewWithTag(DRAFT);
                                if (b != null) b.setVisibility(View.GONE);
                            } catch (Throwable ignore) {}
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.rightMargin = IvaPatch.dp(a, 8);
                    row.addView(go, lp);
                    row.addView(del, new LinearLayout.LayoutParams(-2, -2));
                    box.addView(row, IvaModelPatch.lpTop(a, 10));
                    LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) box.getLayoutParams();
                    if (blp != null) { blp.topMargin = IvaPatch.dp(a, 12); box.setLayoutParams(blp); }
                }
                box.setVisibility(View.VISIBLE);
            }
        }
        if (sv != null) sv.setVisibility(View.GONE);                                  // 10.89: «по образцу» не показываем
        if (SHOW_SAMPLE && sv instanceof LinearLayout) {
            LinearLayout box = (LinearLayout) sv;
            List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
            List<JSONObject> l = mine;
            if (l != null) for (JSONObject j : l) { Map<String, String> m = fromTool(j); rows.add(m); }
            List<Map<String, String>> sm = samples(rows, SAMPLE_MAX);
            if (!empty || sm.isEmpty() || (d != null && hasContent(d))) { box.setVisibility(View.GONE); return; }
            StringBuilder sig = new StringBuilder();
            for (Map<String, String> m : sm) sig.append(g(m, "name")).append('|');
            if (!sig.toString().equals(sigs.get(box))) {
                box.removeAllViews();
                sigs.put(box, sig.toString());
                box.addView(IvaModelPatch.small(a, SAMPLE_LABEL), IvaModelPatch.lpTop(a, 12));
                HorizontalScrollView hs = new HorizontalScrollView(a);
                hs.setHorizontalScrollBarEnabled(false);
                hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                hs.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
                for (final Map<String, String> m : sm) {
                    TextView c = IvaSearchPatch.chip(a, cut(g(m, "name"), 28), false, false);
                    c.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try {
                                LinkedHashMap<String, String> st = new LinkedHashMap<String, String>(m);
                                st.remove("pics");
                                apply(a, st, false);
                            } catch (Throwable ignore) {}
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.rightMargin = IvaPatch.dp(a, 8);
                    row.addView(c, lp);
                }
                box.addView(hs, IvaModelPatch.lpTop(a, 6));
            }
            box.setVisibility(View.VISIBLE);
        }
    }

    static void paintTopLater(final Activity a, final View any) {
        any.removeCallbacks(TOP);
        topActivity = a;
        any.postDelayed(TOP, 400);
    }

    static Activity topActivity = null;
    static final Runnable TOP = new Runnable() {
        @Override public void run() {
            try {
                Activity a = topActivity;
                Object no = a == null ? null : IvaClientPatch.get(a, "atName");
                if (no instanceof View && ((View) no).getParent() instanceof LinearLayout) paintTop(a, (LinearLayout) ((View) no).getParent());
            } catch (Throwable ignore) {}
        }
    };

    // ───── состояние формы: собрать / поставить ─────

    @SuppressWarnings("unchecked")
    static LinkedHashMap<String, String> collect(Activity a, boolean full) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        m.put("name", text(a, "atName"));
        m.put("price", text(a, "atPrice"));
        m.put("dep", text(a, "atDep"));
        m.put("descr", text(a, "atDescr"));
        m.put("terms", IvaPatch.condStrip(text(a, "atTerms")));
        Object pics = IvaClientPatch.get(a, "atPics");
        m.put("pics", String.valueOf(pics instanceof ArrayList ? ((ArrayList) pics).size() : 0));
        if (!full) return m;
        m.put("cat", str(IvaClientPatch.get(a, "atCatKey")));
        m.put("sub", str(IvaClientPatch.get(a, "atSubKey")));
        m.put("kind", IvaModelPatch.kindNow());
        IvaModelPatch.Model ch = IvaModelPatch.chosen;
        m.put("model", ch == null ? "" : String.valueOf(ch.id));
        m.put("cond", String.valueOf(IvaPatch.condSel[0]));
        Object dl = IvaClientPatch.get(a, "atDeliv");
        m.put("deliv", dl instanceof Boolean && (Boolean) dl ? "1" : "0");
        m.put("delp", text(a, "atDelPrice"));
        for (Map.Entry<String, String> e : IvaModelPatch.specVals.entrySet())
            if (e.getValue() != null && !e.getValue().trim().isEmpty()) m.put("spec." + e.getKey(), e.getValue());
        int i = 0;
        for (String k : IvaModelPatch.kitSel) m.put("kit." + (i++), k);
        return m;
    }

    /** Поставить состояние в форму (черновик — с фото; образец — без). Штатные поля — как будто их заполнили руками. */
    @SuppressWarnings("unchecked")
    static void apply(Activity a, Map<String, String> st, boolean withPics) {
        if (st == null) return;
        Object no = IvaClientPatch.get(a, "atName");
        if (!(no instanceof TextView)) return;
        TextView nv = (TextView) no;
        IvaModelPatch.quiet = true;
        try {
            nv.setText(g(st, "name"));
            if (nv instanceof EditText) ((EditText) nv).setSelection(nv.getText().length());
        } finally { IvaModelPatch.quiet = false; }
        IvaKindPatch.nameSeq++;                                                      // автоподбор по названию не нужен
        IvaModelPatch.nameSeq++;
        String cat = g(st, "cat");
        if (!cat.isEmpty()) IvaKindPatch.setFromModel(a, cat, g(st, "sub"), g(st, "kind"));
        IvaModelPatch.specVals = new LinkedHashMap<String, String>(specsOf(st));
        IvaModelPatch.specAuto = new LinkedHashMap<String, String>();
        IvaModelPatch.specsKind = null;
        IvaModelPatch.kitSel = new ArrayList<String>(kitOf(st));
        IvaModelPatch.kitKind = null;
        IvaModelPatch.chosen = null;
        long mid = 0;
        try { mid = Long.parseLong(g(st, "model").trim()); } catch (Throwable ignore) {}
        pendingModel = mid;
        setText(a, "atPrice", g(st, "price"));
        setText(a, "atDep", g(st, "dep"));
        setText(a, "atDescr", g(st, "descr"));
        setText(a, "atTerms", g(st, "terms"));
        int cond = num(g(st, "cond"));
        IvaPatch.condSel[0] = cond >= 0 && cond < IvaPatch.COND_TITLES.length ? cond : IvaPatch.COND_DEFAULT;
        Object te = IvaClientPatch.get(a, "atTerms");
        if (te instanceof View) { IvaPatch.condSeen[0] = text(a, "atTerms"); IvaPatch.condPaint(a, (View) te, 0); }
        boolean wantDel = "1".equals(g(st, "deliv"));
        Object dl = IvaClientPatch.get(a, "atDeliv");
        boolean hasDel = dl instanceof Boolean && (Boolean) dl;
        if (wantDel != hasDel) {
            View root = nv.getRootView();
            View row = root == null ? null : root.findViewWithTag(IvaPatch.DEL_ROW_TAG + ":atDelToggle");
            if (row != null) row.performClick();
        }
        setText(a, "atDelPrice", g(st, "delp"));
        if (withPics) loadPics(a, num(g(st, "pics")));
        resolveModel(a);
        IvaModelPatch.refresh(a);
        IvaSearchPatch.hideKeyboard(a, nv);
        nv.clearFocus();
        IvaNotifyPatch.flash(nv);
        lastSaved = null;
        View root = nv.getRootView();
        View dv = root == null ? null : root.findViewWithTag(DRAFT), sv = root == null ? null : root.findViewWithTag(SAMPLE);
        if (dv != null) dv.setVisibility(View.GONE);
        if (sv != null) sv.setVisibility(View.GONE);
        scheduleSave(a);
    }

    static void setText(Activity a, String field, String v) {
        Object o = IvaClientPatch.get(a, field);
        if (o instanceof TextView && !String.valueOf(((TextView) o).getText()).equals(v)) ((TextView) o).setText(v);
    }

    /** Модель из черновика / образца — когда справочник загрузится (и если вид тот же). */
    static void resolveModel(Activity a) {
        long id = pendingModel;
        List<IvaModelPatch.Model> ms = IvaModelPatch.modelList;
        if (id == 0 || ms == null) return;
        pendingModel = 0;
        String kind = IvaModelPatch.kindNow();
        for (IvaModelPatch.Model m : ms) {
            if (m.id != id) continue;
            if (!m.kind.equals(kind) || IvaModelPatch.chosen != null) return;
            IvaModelPatch.chosen = m;
            IvaModelPatch.specsKind = null;
            try { IvaModelPatch.refresh(a); } catch (Throwable ignore) {}
            return;
        }
    }

    // ───── черновик ─────

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_form", 0); }

    static LinkedHashMap<String, String> draft(Activity a) {
        String s = prefs(a).getString("draft", "");
        return s.isEmpty() ? null : dec(s);
    }

    static File picFile(Activity a, int i) { return new File(new File(a.getFilesDir(), "iva_draft"), "p" + i + ".jpg"); }

    static void clearDraft(Activity a) {
        prefs(a).edit().remove("draft").apply();
        lastSaved = "";
        picsSig = "";
        for (int i = 0; i < MAX_PICS; i++) try { picFile(a, i).delete(); } catch (Throwable ignore) {}
    }

    static Activity saveActivity = null;
    static View saveHost = null;
    static final Runnable SAVE = new Runnable() {
        @Override public void run() { try { if (saveActivity != null) saveDraft(saveActivity); } catch (Throwable ignore) {} }
    };

    static void scheduleSave(Activity a) {
        Object no = IvaClientPatch.get(a, "atName");
        if (!(no instanceof View)) return;
        saveActivity = a;
        if (saveHost != null) saveHost.removeCallbacks(SAVE);
        saveHost = (View) no;
        saveHost.postDelayed(SAVE, SAVE_DELAY_MS);
    }

    /** Сохранить черновик, если в форме что-то есть и это не только что опубликованное. */
    @SuppressWarnings("unchecked")
    static void saveDraft(final Activity a) {
        if (System.currentTimeMillis() - publishAt < PUBLISH_QUIET_MS) return;
        Object no = IvaClientPatch.get(a, "atName");
        if (!(no instanceof View) || !((View) no).isShown()) return;
        LinkedHashMap<String, String> st = collect(a, true);
        if (!hasContent(st)) return;
        String s = enc(st);
        if (s.equals(lastSaved)) return;
        lastSaved = s;
        prefs(a).edit().putString("draft", s).apply();
        Object pics = IvaClientPatch.get(a, "atPics");
        if (!(pics instanceof ArrayList)) return;
        final List<Object> list = new ArrayList<Object>((ArrayList) pics);
        StringBuilder sig = new StringBuilder();
        for (Object o : list) sig.append(o instanceof byte[] ? ((byte[]) o).length + "/" + System.identityHashCode(o) : "?").append(',');
        if (sig.toString().equals(picsSig)) return;
        picsSig = sig.toString();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dir = new File(a.getFilesDir(), "iva_draft");
                    if (!dir.isDirectory() && !dir.mkdirs()) return;
                    for (int i = 0; i < MAX_PICS; i++) {
                        File f = picFile(a, i);
                        if (i >= list.size() || !(list.get(i) instanceof byte[])) { f.delete(); continue; }
                        FileOutputStream out = new FileOutputStream(f);
                        try { out.write((byte[]) list.get(i)); } finally { out.close(); }
                    }
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    @SuppressWarnings("unchecked")
    static void loadPics(Activity a, int n) {
        Object pics = IvaClientPatch.get(a, "atPics");
        if (!(pics instanceof ArrayList) || n <= 0) return;
        List<byte[]> got = new ArrayList<byte[]>();
        for (int i = 0; i < Math.min(n, MAX_PICS); i++) {
            File f = picFile(a, i);
            if (!f.isFile() || f.length() <= 0 || f.length() > 30L * 1024 * 1024) continue;
            try {
                InputStream in = new FileInputStream(f);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try { byte[] buf = new byte[16384]; int r; while ((r = in.read(buf)) > 0) b.write(buf, 0, r); } finally { in.close(); }
                got.add(b.toByteArray());
            } catch (Throwable ignore) {}
        }
        if (got.isEmpty()) return;
        ArrayList list = (ArrayList) pics;
        list.clear();
        list.addAll(got);
        try { Method m = a.getClass().getDeclaredMethod("renderAtPhotos"); m.setAccessible(true); m.invoke(a); } catch (Throwable ignore) {}
    }

    /** Первая вкладка после «Опубликовать»: кабинет — штатный успех, черновик больше не нужен. */
    static void onTab(Activity a, String tab) {
        long at = publishAt;
        if (at == 0 || at == clearedFor || !IvaKindPatch.pendingWrite(tab, at, System.currentTimeMillis())) return;
        clearedFor = at;
        clearDraft(a);
        if (!heldWord.isEmpty()) {                                                    // 10.92: объявление ждёт проверки
            try { android.widget.Toast.makeText(a, heldMsg(heldWord), android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {}
            heldWord = "";
        }
        Object no = IvaClientPatch.get(a, "atName");                                 // строка черновика могла успеть показаться
        View root = no instanceof View ? ((View) no).getRootView() : null;
        View dv = root == null ? null : root.findViewWithTag(DRAFT);
        if (dv != null) dv.setVisibility(View.GONE);
        mine = null;                                                                  // новый инструмент — в образцах
        mineTriedAt = 0;
    }
}

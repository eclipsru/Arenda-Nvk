// Ива 10.82 — справочник моделей, этап 2 (решение владельца 15.10.2026, журнал — решение №31).
//
// Справочник — таблицы iva_models / iva_brands / iva_spec_fields (миграции supabase/migrations/20261017_models*.sql).
// У объявления — tools.model_id (выбранная модель) и tools.specs (характеристики, объект «ключ поля → значение»).
//
//  * Форма «Добавить инструмент»: пишут название — под ним подсказки моделей («Makita HR2470»). Выбрали — название
//    становится названием модели, категория, подкатегория и вид ставятся как при штатном выборе, характеристики
//    заполняются. Ручные правки характеристик не перезаписываются.
//  * Под «Видом инструмента» — «Модель» (только модели этого вида, сверху бренды) и «Характеристики» (поля вида:
//    число — поле с цифровой клавиатурой, выбор — оранжевые кнопки, текст — поле). Всё необязательно.
//  * Сохранение — тем же запросом, которым IvaKindPatch пишет вид после штатного успеха (model_id и specs
//    дописываются к {"kind":…}). База не приняла (SQL не применён) — пишется только вид, как в 10.81.
//  * Справочник не загрузился (SQL не применён, нет сети) — ни подсказок, ни новых полей: форма как в 10.81.
//
// Тот же поиск на сайте — assets/models.js; оба проверяются на tests/model-search-cases.txt.
// Новых вставок в smali нет: всё вызывается из IvaKindPatch (поле «Вид» в форме и запись вида).
package ru.prokatnvsk.app;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IvaModelPatch {
    private IvaModelPatch() {}

    // ════════════════════════════════ 1. Поиск и характеристики (чистая логика — под тестами, как assets/models.js) ════

    static final class Model {
        final long id;
        final String kind, brand, model, title;
        final LinkedHashMap<String, String> specs;
        Model(long id, String kind, String brand, String model, String title, LinkedHashMap<String, String> specs) {
            this.id = id; this.kind = s(kind); this.brand = s(brand); this.model = s(model); this.title = s(title);
            this.specs = specs == null ? new LinkedHashMap<String, String>() : specs;
        }
    }

    static final class Field {
        final String kind, key, title, unit, type;
        final String[] options;
        final int sort;
        Field(String kind, String key, String title, String unit, String type, String[] options, int sort) {
            this.kind = s(kind); this.key = s(key); this.title = s(title); this.unit = s(unit); this.type = s(type);
            this.options = options == null ? new String[0] : options; this.sort = sort;
        }
        /** Подпись поля: «Мощность, Вт». */
        String label() { return unit.isEmpty() ? title : title + ", " + unit; }
    }

    static final class Entry {
        Model m;
        List<String> strong = new ArrayList<String>(), weak = new ArrayList<String>(), stems, codeToks;
        String key;
        int code;
    }

    static final class Index {
        final List<Entry> list = new ArrayList<Entry>();
        final List<String> brandToks = new ArrayList<String>(), stemToks = new ArrayList<String>();
    }

    static String s(String v) { return v == null ? "" : v; }

    static final Pattern LD = Pattern.compile("[a-zа-я]+|[0-9]+");
    static final Pattern DIGIT = Pattern.compile("[0-9]");

    static List<String> splitLD(String t) {
        List<String> out = new ArrayList<String>();
        Matcher m = LD.matcher(t);
        while (m.find()) out.add(m.group());
        return out;
    }

    static void pushU(List<String> arr, String v) {
        if (v != null && !v.isEmpty() && !arr.contains(v)) arr.add(v);
    }

    static List<String> toks(String v) {
        List<String> out = new ArrayList<String>();
        Collections.addAll(out, IvaKindPatch.tokens(v));
        return out;
    }

    /** brands: бренд → написания (по порядку справочника), words: вид → слова автоподбора (iva_kinds.words). */
    static Index index(List<Model> models, Map<String, String[]> brands, Map<String, String[]> words) {
        Index ix = new Index();
        Map<String, List<String>> al = new HashMap<String, List<String>>(), kw = new HashMap<String, List<String>>();
        if (brands != null) for (Map.Entry<String, String[]> b : brands.entrySet()) {
            List<String> at = new ArrayList<String>();
            if (b.getValue() != null) for (String x : b.getValue()) for (String w : toks(x)) pushU(at, w);
            al.put(b.getKey(), at);
        }
        if (words != null) for (Map.Entry<String, String[]> k : words.entrySet()) {
            List<String> kt = new ArrayList<String>();
            if (k.getValue() != null) for (String x : k.getValue()) for (String p : toks(x)) if (p.length() >= 3) pushU(kt, p);
            kw.put(k.getKey(), kt);
        }
        if (brands != null) for (String name : brands.keySet()) {
            List<String> nb = toks(name);
            if (al.get(name) != null) nb.addAll(al.get(name));
            for (String t : nb) if (t.length() >= 3) pushU(ix.brandToks, t);
        }
        for (List<String> kt : kw.values()) for (String t : kt) pushU(ix.stemToks, t);
        if (models != null) for (Model m : models) {
            if (m == null || m.brand.isEmpty() || m.model.isEmpty()) continue;
            Entry e = new Entry();
            e.m = m;
            List<String> mt = toks(m.model);
            for (String t : toks(m.brand)) pushU(e.strong, t);
            for (String t : mt) {
                pushU(e.strong, t);
                List<String> ld = splitLD(t);
                if (ld.size() > 1) for (String p : ld) pushU(e.strong, p);
            }
            if (mt.size() > 1) pushU(e.strong, join(mt));
            List<String> a = al.get(m.brand);
            if (a != null) for (String t : a) pushU(e.strong, t);
            for (String t : toks(m.title)) if (!e.strong.contains(t)) pushU(e.weak, t);
            List<String> st = kw.get(m.kind);
            e.stems = st == null ? new ArrayList<String>() : st;
            e.key = m.title.toLowerCase(Locale.ROOT);
            e.code = join(mt).length();
            e.codeToks = mt;
            ix.list.add(e);
        }
        return ix;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String x : l) b.append(x);
        return b.toString();
    }

    static final class QTok {
        final String w;
        final boolean digit, brand, stem;
        QTok(String w, boolean digit, boolean brand, boolean stem) { this.w = w; this.digit = digit; this.brand = brand; this.stem = stem; }
    }

    static List<QTok> queryInfo(List<String> q, Index ix) {
        List<QTok> out = new ArrayList<QTok>();
        for (String w : q) {
            boolean brand = false, stem = false;
            if (w.length() >= 3) for (String b : ix.brandToks) if (b.startsWith(w)) { brand = true; break; }
            for (String st : ix.stemToks) if (w.startsWith(st)) { stem = true; break; }
            out.add(new QTok(w, DIGIT.matcher(w).find(), brand, stem));
        }
        return out;
    }

    /** Сколько слов модели не покрыто запросом (слово запроса — начало слова модели; одна буква — целиком). */
    static int uncovered(Entry e, List<QTok> qi) {
        int n = 0;
        List<String> seen = new ArrayList<String>();
        for (String t : e.codeToks) {
            if (seen.contains(t)) continue;
            seen.add(t);
            boolean hit = false;
            for (QTok q : qi) if (t.equals(q.w) || (q.w.length() >= 2 && t.startsWith(q.w))) { hit = true; break; }
            if (!hit) n++;
        }
        return n;
    }

    /** Очки модели или null; miss[0] — сколько слов запроса не совпало. */
    static Integer score(Entry e, List<QTok> qi, int[] miss) {
        int total = 0;
        boolean hasStrong = false;
        miss[0] = 0;
        for (QTok q : qi) {
            String w = q.w;
            int best = 0;
            for (String st : e.strong) {
                if (st.equals(w)) { best = 2 * w.length() + 1; break; }
                if (w.length() >= 2 && st.startsWith(w) && 2 * w.length() > best) best = 2 * w.length();
            }
            if (best > 0) { hasStrong = true; total += best; continue; }
            boolean weak = false;
            for (String x : e.weak) if (x.startsWith(w)) { weak = true; break; }
            if (!weak) for (String x : e.stems) if (w.startsWith(x)) { weak = true; break; }
            if (weak) { total += w.length(); continue; }
            if (q.digit || q.brand) return null;
            miss[0]++;
            total -= q.stem ? 2 * w.length() : w.length();
        }
        return hasStrong && total > 0 ? Integer.valueOf(total) : null;
    }

    static final class Hit {
        final Entry e; final int s, miss, u, c;
        Hit(Entry e, int s, int miss, int u, int c) { this.e = e; this.s = s; this.miss = miss; this.u = u; this.c = c; }
    }

    /** Подсказки: kind и brand — фильтр (пусто — без фильтра); пустой запрос с видом — модели вида по названию. */
    static List<Model> search(String query, Index ix, String kind, String brand, int limit) {
        if (limit <= 0) limit = 8;
        List<String> q = toks(query);
        List<Model> out = new ArrayList<Model>();
        if (ix == null) return out;
        List<QTok> qi = queryInfo(q, ix);
        List<Hit> res = new ArrayList<Hit>();
        boolean full = false;
        int[] miss = new int[1];
        boolean byKind = kind != null && !kind.isEmpty(), byBrand = brand != null && !brand.isEmpty();
        for (Entry e : ix.list) {
            if (byKind && !e.m.kind.equals(kind)) continue;
            if (byBrand && !e.m.brand.equals(brand)) continue;
            int sc, ms = 0;
            if (q.isEmpty()) {
                if (!byKind) continue;
                sc = 0;
            } else {
                Integer r = score(e, qi, miss);
                if (r == null) continue;
                sc = r; ms = miss[0];
            }
            if (ms == 0) full = true;
            res.add(new Hit(e, sc, ms, q.isEmpty() ? 0 : uncovered(e, qi), q.isEmpty() ? 0 : e.code));
        }
        if (full) {
            List<Hit> f = new ArrayList<Hit>();
            for (Hit h : res) if (h.miss == 0) f.add(h);
            res = f;
        }
        Collections.sort(res, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.s != b.s) return b.s - a.s;
                if (a.u != b.u) return a.u - b.u;
                if (a.c != b.c) return a.c - b.c;
                return a.e.key.compareTo(b.e.key);
            }
        });
        for (int i = 0; i < res.size() && out.size() < limit; i++) out.add(res.get(i).e.m);
        return out;
    }

    /** Бренды, у которых есть модели этого вида — по алфавиту (без приоритетов). */
    static List<String> brands(Index ix, String kind) {
        List<String> seen = new ArrayList<String>();
        if (ix == null) return seen;
        for (Entry e : ix.list) {
            if (kind != null && !kind.isEmpty() && !e.m.kind.equals(kind)) continue;
            pushU(seen, e.m.brand);
        }
        Collections.sort(seen, new Comparator<String>() {
            @Override public int compare(String x, String y) { return x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT)); }
        });
        return seen;
    }

    static String g(Map<String, String> m, String k) {
        String v = m == null ? null : m.get(k);
        return v == null ? "" : v;
    }

    /** Подстановка характеристик модели: [0] — новое содержимое формы, [1] — что теперь стоит «от модели».
     *  Ставим, если поле пустое или в нём наше прошлое значение; наши прошлые значения, которых у новой модели нет,
     *  убираем; ручные правки не трогаем. То же — ivaSpecsFill в assets/models.js. */
    @SuppressWarnings("unchecked")
    static LinkedHashMap<String, String>[] specsFill(Map<String, String> cur, Map<String, String> lastAuto, Map<String, String> modelSpecs) {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>(), auto = new LinkedHashMap<String, String>();
        if (cur != null) for (Map.Entry<String, String> e : cur.entrySet()) values.put(e.getKey(), s(e.getValue()));
        if (lastAuto != null) for (String k : lastAuto.keySet()) {
            if (modelSpecs != null && modelSpecs.containsKey(k)) continue;
            if (g(values, k).equals(g(lastAuto, k))) values.put(k, "");
        }
        if (modelSpecs != null) for (Map.Entry<String, String> e : modelSpecs.entrySet()) {
            String c = g(values, e.getKey()), v = s(e.getValue());
            if (c.isEmpty() || c.equals(g(lastAuto, e.getKey()))) { values.put(e.getKey(), v); auto.put(e.getKey(), v); }
        }
        return new LinkedHashMap[]{values, auto};
    }

    /** Модель ещё подходит к названию: код модели слитно есть в названии слитно (Makita GBH2-26 ↔ GBH 2-26). */
    static boolean stillFits(String name, Model m) {
        if (m == null || m.model.isEmpty()) return false;
        String code = join(toks(m.model));
        return !code.isEmpty() && join(toks(name)).contains(code);
    }

    static final Pattern NUM = Pattern.compile("^\\d+(\\.\\d+)?$");

    /** Число как в JSON / JavaScript: «2.40» → «2.4», «007» → «7». */
    static String numText(String t) {
        return new BigDecimal(t).stripTrailingZeros().toPlainString();
    }

    static String jsonStr(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Характеристики к сохранению — JSON-объект в порядке полей вида или null (нечего сохранять). Число — число
     *  (запятая = точка), выбор — из вариантов, строка — без пробелов по краям. То же — ivaSpecsClean. */
    static String specsClean(Map<String, String> values, List<Field> fields) {
        StringBuilder b = new StringBuilder("{");
        int n = 0;
        if (fields != null) for (Field f : fields) {
            String raw = g(values, f.key).trim(), v;
            if (raw.isEmpty()) continue;
            if ("num".equals(f.type)) {
                String t = raw.replaceFirst(",", ".");
                if (!NUM.matcher(t).matches()) continue;
                v = numText(t);
            } else if ("choice".equals(f.type)) {
                boolean ok = false;
                for (String o : f.options) if (o.equals(raw)) { ok = true; break; }
                if (!ok) continue;
                v = jsonStr(raw);
            } else v = jsonStr(raw);
            if (n++ > 0) b.append(',');
            b.append(jsonStr(f.key)).append(':').append(v);
        }
        return n == 0 ? null : b.append('}').toString();
    }

    /** Плоский JSON-объект {"src":"Аккумулятор","volt":18} → ключ → строка (числа — как в JSON, null пропускается).
     *  Свой разбор: org.json в android.jar для тестов — заглушка, а характеристики — простые объекты. */
    static LinkedHashMap<String, String> parseFlat(String json) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        String t = json == null ? "" : json.trim();
        int[] p = {0};
        if (!t.startsWith("{")) return out;
        p[0] = 1;
        try {
            while (true) {
                skipWs(t, p);
                if (p[0] >= t.length() || t.charAt(p[0]) == '}') break;
                String k = readStr(t, p);
                skipWs(t, p);
                if (t.charAt(p[0]) != ':') return new LinkedHashMap<String, String>();
                p[0]++;
                skipWs(t, p);
                char c = t.charAt(p[0]);
                String v;
                if (c == '"') v = readStr(t, p);
                else {
                    int st = p[0];
                    while (p[0] < t.length() && ",}".indexOf(t.charAt(p[0])) < 0 && !Character.isWhitespace(t.charAt(p[0]))) p[0]++;
                    String raw = t.substring(st, p[0]);
                    if ("null".equals(raw)) v = null;
                    else if ("true".equals(raw) || "false".equals(raw)) v = raw;
                    else v = numText(raw);
                }
                if (v != null) out.put(k, v);
                skipWs(t, p);
                if (p[0] < t.length() && t.charAt(p[0]) == ',') { p[0]++; continue; }
                break;
            }
        } catch (Throwable e) { return new LinkedHashMap<String, String>(); }
        return out;
    }

    static void skipWs(String t, int[] p) { while (p[0] < t.length() && Character.isWhitespace(t.charAt(p[0]))) p[0]++; }

    static String readStr(String t, int[] p) {
        if (t.charAt(p[0]) != '"') throw new IllegalArgumentException("not a string");
        StringBuilder b = new StringBuilder();
        p[0]++;
        while (true) {
            char c = t.charAt(p[0]++);
            if (c == '"') break;
            if (c != '\\') { b.append(c); continue; }
            char e = t.charAt(p[0]++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(t.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                default: b.append(e);
            }
        }
        return b.toString();
    }

    /** Дописать к телу записи вида {"kind":"drill"} поля модели: ,"model_id":12,"specs":{…}. */
    static String mergeBody(String base, String extra) {
        if (base == null || extra == null || extra.isEmpty() || !base.endsWith("}")) return base;
        return base.substring(0, base.length() - 1) + "," + extra + "}";
    }

    /** Поля модели к записи: id модели (0 — нет) и характеристики (null — нет). Пусто — дописывать нечего. */
    static String extraBody(long modelId, String specsJson) {
        StringBuilder b = new StringBuilder();
        if (modelId > 0) b.append("\"model_id\":").append(modelId);
        if (specsJson != null) b.append(b.length() > 0 ? "," : "").append("\"specs\":").append(specsJson);
        return b.toString();
    }

    /** Поля вида по порядку (sort, затем ключ). */
    static List<Field> fieldsFor(List<Field> all, String kind) {
        List<Field> out = new ArrayList<Field>();
        if (all == null || kind == null || kind.isEmpty()) return out;
        for (Field f : all) if (kind.equals(f.kind)) out.add(f);
        Collections.sort(out, new Comparator<Field>() {
            @Override public int compare(Field x, Field y) { return x.sort != y.sort ? (x.sort < y.sort ? -1 : 1) : x.key.compareTo(y.key); }
        });
        return out;
    }

    /** Надпись модели в поле «Модель»: «Makita HR2470». */
    static String modelText(Model m) { return m == null ? "" : (m.brand + " " + m.model).trim(); }

    static String modelsPath(int offset) {
        return "/rest/v1/iva_models?select=id,kind,brand,model,title,specs&active=eq.true&order=id.asc&limit=" + PAGE + "&offset=" + offset;
    }

    static final int PAGE = 1000;                     // PostgREST отдаёт не больше 1000 строк за раз
    static final String BRANDS_PATH = "/rest/v1/iva_brands?select=name,aliases&order=name.asc";
    static final String FIELDS_PATH = "/rest/v1/iva_spec_fields?select=kind,key,title,unit,type,options,sort&order=kind.asc,sort.asc";
    static final String MODEL_LABEL = "Модель", MODEL_HINT = "Модель (необязательно)", SPECS_LABEL = "Характеристики";
    static final int NAME_SUGGEST = 6, MODEL_SUGGEST = 20;
    static final long DELAY_MS = 250;
    static final long RETRY_MS = 60_000L, REFRESH_MS = 30 * 60_000L;

    // ════════════════════════════════ 2. Загрузка справочника ════════════════════════════════

    static volatile Index idx = null;                // null — справочника нет (не загрузился или SQL не применён)
    static volatile List<Field> fields = null;
    static volatile Map<String, String[]> wordsUsed = null;
    static volatile boolean loading = false;
    static volatile long triedAt = 0, loadedAt = 0;

    static boolean ready() { return idx != null && fields != null; }

    static void ensureLoaded(final Activity a) {
        long now = System.currentTimeMillis();
        if (loading) return;
        if (loadedAt > 0 ? now - loadedAt < REFRESH_MS : now - triedAt < RETRY_MS) {
            if (idx != null && wordsUsed == null && IvaKindPatch.words != null) rebuildLater(a);   // слова видов пришли позже
            return;
        }
        loading = true;
        triedAt = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] rb = IvaClientPatch.apiCall(a, BRANDS_PATH, "GET", null, false);
                    String[] rf = IvaClientPatch.apiCall(a, FIELDS_PATH, "GET", null, false);
                    if (!ok(rb) || !ok(rf)) return;
                    LinkedHashMap<String, String[]> br = parseBrands(rb[1]);
                    List<Field> fl = parseFields(rf[1]);
                    List<Model> ms = new ArrayList<Model>();
                    for (int off = 0; off < 20 * PAGE; off += PAGE) {
                        String[] rm = IvaClientPatch.apiCall(a, modelsPath(off), "GET", null, false);
                        if (!ok(rm)) return;
                        int n = parseModels(rm[1], ms);
                        if (n < PAGE) break;
                    }
                    if (ms.isEmpty() || fl.isEmpty()) return;
                    Map<String, String[]> w = IvaKindPatch.words;
                    Index ix = index(ms, br, w);
                    brandMap = br; modelList = ms;
                    fields = fl; wordsUsed = w; idx = ix;
                    loadedAt = System.currentTimeMillis();
                    a.runOnUiThread(new Runnable() { @Override public void run() { try { refresh(a); } catch (Throwable ignore) {} } });
                } catch (Throwable ignore) {
                } finally { loading = false; }
            }
        }).start();
    }

    static volatile LinkedHashMap<String, String[]> brandMap = null;
    static volatile List<Model> modelList = null;

    static void rebuildLater(final Activity a) {
        final List<Model> ms = modelList;
        final LinkedHashMap<String, String[]> br = brandMap;
        final Map<String, String[]> w = IvaKindPatch.words;
        if (ms == null || br == null || w == null) return;
        wordsUsed = w;
        new Thread(new Runnable() {
            @Override public void run() { try { idx = index(ms, br, w); } catch (Throwable ignore) {} }
        }).start();
    }

    static boolean ok(String[] r) { return r != null && r.length > 1 && r[0] != null && r[0].startsWith("2") && r[1] != null; }

    static String[] strArr(JSONArray arr) {
        if (arr == null) return new String[0];
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            String x = IvaKindPatch.clean(arr.optString(i, ""));
            if (!x.isEmpty()) out.add(x);
        }
        return out.toArray(new String[0]);
    }

    static LinkedHashMap<String, String[]> parseBrands(String text) throws Exception {
        JSONArray arr = new JSONArray(text.trim());
        LinkedHashMap<String, String[]> out = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = IvaKindPatch.clean(o.optString("name", ""));
            if (!name.isEmpty()) out.put(name, strArr(o.optJSONArray("aliases")));
        }
        return out;
    }

    static List<Field> parseFields(String text) throws Exception {
        JSONArray arr = new JSONArray(text.trim());
        List<Field> out = new ArrayList<Field>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String kind = IvaKindPatch.clean(o.optString("kind", "")), key = IvaKindPatch.clean(o.optString("key", ""));
            String type = IvaKindPatch.clean(o.optString("type", ""));
            if (kind.isEmpty() || key.isEmpty() || !("num".equals(type) || "choice".equals(type) || "text".equals(type))) continue;
            out.add(new Field(kind, key, IvaKindPatch.clean(o.optString("title", key)), IvaKindPatch.clean(o.optString("unit", "")),
                type, strArr(o.optJSONArray("options")), o.optInt("sort", 0)));
        }
        return out;
    }

    static int parseModels(String text, List<Model> into) throws Exception {
        JSONArray arr = new JSONArray(text.trim());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id", 0);
            Object sp = o.opt("specs");
            if (id <= 0) continue;
            into.add(new Model(id, IvaKindPatch.clean(o.optString("kind", "")), IvaKindPatch.clean(o.optString("brand", "")),
                IvaKindPatch.clean(o.optString("model", "")), IvaKindPatch.clean(o.optString("title", "")),
                parseFlat(sp instanceof JSONObject ? sp.toString() : "{}")));
        }
        return arr.length();
    }

    // ════════════════════════════════ 3. Форма «Добавить инструмент» ════════════════════════════════

    static final String NAME_SUG = "iva_model_namesug", BLOCK = "iva_model_block", SPECS = "iva_model_specs";
    static Model chosen = null;                                   // выбранная модель (null — нет)
    static String brandSel = "";                                  // бренд-фильтр поля «Модель»
    static LinkedHashMap<String, String> specVals = new LinkedHashMap<String, String>(), specAuto = new LinkedHashMap<String, String>();
    static String shown = "";                                     // что поставили в поле «Модель» сами
    static String specsKind = null;                               // для какого вида построены поля характеристик
    static boolean quiet = false;                                 // меняем поля сами — слушатели молчат
    static int nameSeq = 0, modelSeq = 0;
    static final Map<View, Boolean> watched = new java.util.WeakHashMap<View, Boolean>();

    static int cText(Activity a) { return IvaPatch.color(a, "text", 0xfff2f4f8); }
    static int cGray(Activity a) { return IvaPatch.color(a, "gray", 0xff9aa1b0); }
    static int cHint(Activity a) { return IvaPatch.color(a, "gray2", 0xff6b7080); }

    static TextView nameView(Activity a) {
        Object o = IvaClientPatch.get(a, IvaKindPatch.FORM[0][5]);
        return o instanceof TextView ? (TextView) o : null;
    }

    static String kindNow() { return IvaKindPatch.kinds == null ? "" : IvaKindPatch.sel[0]; }

    /** Вызывается из IvaKindPatch.refreshRow (форма «Добавить»): подсказки под названием, «Модель», «Характеристики». */
    static void refresh(Activity a) {
        TextView nv = nameView(a);
        if (nv == null || !(nv.getParent() instanceof LinearLayout)) return;
        if (nv.isShown()) ensureLoaded(a);
        watchName(a, nv);
        if (!ready()) { hideAll(nv); return; }
        LinearLayout form = (LinearLayout) nv.getParent();
        String kind = kindNow();
        if (chosen != null && !chosen.kind.equals(kind)) chosen = null;          // вид сменили — модель другого вида снимается
        View kindRow = form.findViewWithTag(IvaKindPatch.ROW_TAG + IvaKindPatch.FORM[0][2]);
        View block = form.findViewWithTag(BLOCK), specs = form.findViewWithTag(SPECS);
        if (kindRow == null || kindRow.getParent() != form) { hideAll(nv); return; }
        if (block == null) { block = buildBlock(a, nv); form.addView(block, form.indexOfChild(kindRow) + 1, new LinearLayout.LayoutParams(-1, -2)); }
        if (specs == null) { specs = new LinearLayout(a); ((LinearLayout) specs).setOrientation(LinearLayout.VERTICAL); specs.setTag(SPECS);
            form.addView(specs, form.indexOfChild(block) + 1, new LinearLayout.LayoutParams(-1, -2)); }
        paintBlock(a, (LinearLayout) block, kind);
        paintSpecs(a, (LinearLayout) specs, kind, false);
    }

    static void hideAll(View any) {
        View root = any.getRootView();
        if (root == null) return;
        for (String t : new String[]{NAME_SUG, BLOCK, SPECS}) {
            View v = root.findViewWithTag(t);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    // ───── подсказки под названием ─────

    static void watchName(final Activity a, final TextView nv) {
        if (watched.containsKey(nv)) return;
        watched.put(nv, Boolean.TRUE);
        nv.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (quiet) return;
                final int my = ++nameSeq;
                nv.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (my != nameSeq) return;
                        try { nameChanged(a, nv); } catch (Throwable ignore) {}
                    }
                }, DELAY_MS);
            }
        });
        nv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (!has) { View sug = sugBox(nv, false); if (sug != null) sug.setVisibility(View.GONE); }
            }
        });
    }

    static void nameChanged(Activity a, TextView nv) {
        ensureLoaded(a);
        if (!ready()) return;
        String name = String.valueOf(nv.getText());
        if (chosen != null && !stillFits(name, chosen)) {                      // название уже не про эту модель
            chosen = null;
            refresh(a);
        }
        LinearLayout box = sugBox(nv, true);
        if (box == null) return;
        box.removeAllViews();
        List<Model> hits = nv.hasFocus() ? search(name, idx, "", "", NAME_SUGGEST) : new ArrayList<Model>();
        if (hits.size() == 1 && hits.get(0) == chosen && name.trim().equals(chosen.title)) hits.clear();
        for (Model m : hits) box.addView(item(a, m, m.title, nv), new LinearLayout.LayoutParams(-1, -2));
        box.setVisibility(hits.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static LinearLayout sugBox(TextView nv, boolean create) {
        if (!(nv.getParent() instanceof LinearLayout)) return null;
        LinearLayout form = (LinearLayout) nv.getParent();
        View v = form.findViewWithTag(NAME_SUG);
        if (v instanceof LinearLayout) return (LinearLayout) v;
        if (!create) return null;
        LinearLayout box = drop((Activity) nv.getContext(), NAME_SUG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = IvaPatch.dp((Activity) nv.getContext(), 4);
        form.addView(box, form.indexOfChild(nv) + 1, lp);
        return box;
    }

    static LinearLayout drop(Activity a, String tag) {
        LinearLayout box = new LinearLayout(a);
        box.setTag(tag);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        Object sug = IvaClientPatch.get(a, "atSubSug");
        box.setBackground(IvaKindPatch.copy(sug instanceof View ? ((View) sug).getBackground() : null, a, "bg_card"));
        return box;
    }

    static TextView item(final Activity a, final Model m, String text, final TextView nv) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(15f);
        t.setTextColor(cText(a));
        t.setMinHeight(IvaPatch.dp(a, 44));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(IvaPatch.dp(a, 15), IvaPatch.dp(a, 10), IvaPatch.dp(a, 15), IvaPatch.dp(a, 10));
        IvaSearchPatch.feedback(a, t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { choose(a, m); } catch (Throwable ignore) {} }
        });
        return t;
    }

    /** Выбрали модель: название, категория / подкатегория / вид (как штатный выбор), характеристики. */
    static void choose(Activity a, Model m) {
        TextView nv = nameView(a);
        if (nv == null || m == null) return;
        IvaKindPatch.Kind kd = null;
        for (IvaKindPatch.Kind k : IvaKindPatch.list()) if (k.key.equals(m.kind)) { kd = k; break; }
        chosen = m;
        LinkedHashMap<String, String>[] f = specsFill(specVals, specAuto, m.specs);
        specVals = f[0]; specAuto = f[1];
        quiet = true;
        try {
            nv.setText(m.title);
            if (nv instanceof EditText) ((EditText) nv).setSelection(nv.getText().length());
        } finally { quiet = false; }
        IvaKindPatch.nameSeq++;                                                   // автоподбор по названию не нужен — вид знаем
        if (kd != null) IvaKindPatch.setFromModel(a, kd.cat, kd.sub, kd.key);
        View sug = sugBox(nv, false);
        if (sug != null) sug.setVisibility(View.GONE);
        View root = nv.getRootView();
        View d = root == null ? null : root.findViewWithTag(BLOCK + ":drop");
        if (d != null) d.setVisibility(View.GONE);
        IvaSearchPatch.hideKeyboard(a, nv);
        nv.clearFocus();
        specsKind = null;                                                         // поля характеристик — заново, с новыми значениями
        refresh(a);
        IvaNotifyPatch.flash(nv);
    }

    // ───── «Модель» ─────

    static View buildBlock(final Activity a, final TextView nv) {
        LinearLayout box = new LinearLayout(a);
        box.setTag(BLOCK);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(a, MODEL_LABEL), lpTop(a, 14));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout chips = new LinearLayout(a);
        chips.setTag(BLOCK + ":chips");
        chips.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chips, new HorizontalScrollView.LayoutParams(-2, -2));
        box.addView(hs, lpTop(a, 6));
        final EditText f = new EditText(a);
        f.setTag(BLOCK + ":field");
        styleInput(a, f, nv);
        f.setHint(MODEL_HINT);
        f.setSingleLine(true);
        f.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (quiet) return;
                if (chosen != null && !String.valueOf(s).trim().equals(modelText(chosen))) chosen = null;   // модель стёрли или правят
                final int my = ++modelSeq;
                f.postDelayed(new Runnable() {
                    @Override public void run() { if (my == modelSeq) try { fillModelDrop(a, f); } catch (Throwable ignore) {} }
                }, DELAY_MS);
            }
        });
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                try {
                    if (has) fillModelDrop(a, f);
                    else { View d = dropOf(f); if (d != null) d.setVisibility(View.GONE); }
                } catch (Throwable ignore) {}
            }
        });
        box.addView(f, lpTop(a, 8));
        LinearLayout d = drop(a, BLOCK + ":drop");
        box.addView(d, lpTop(a, 4));
        return box;
    }

    static View dropOf(View f) {
        View root = f.getRootView();
        return root == null ? null : root.findViewWithTag(BLOCK + ":drop");
    }

    static void fillModelDrop(Activity a, EditText f) {
        View dv = dropOf(f);
        if (!(dv instanceof LinearLayout) || !ready()) return;
        LinearLayout d = (LinearLayout) dv;
        d.removeAllViews();
        String kind = kindNow();
        String q = String.valueOf(f.getText());
        List<Model> hits = kind.isEmpty() || !f.hasFocus() ? new ArrayList<Model>() : search(q, idx, kind, brandSel, MODEL_SUGGEST);
        if (chosen != null && q.trim().equals(modelText(chosen))) hits.clear();     // модель уже выбрана — список не нужен
        TextView nv = nameView(a);
        for (Model m : hits) d.addView(item(a, m, modelText(m), nv), new LinearLayout.LayoutParams(-1, -2));
        d.setVisibility(hits.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static void paintBlock(final Activity a, LinearLayout block, String kind) {
        List<String> bs = kind.isEmpty() ? new ArrayList<String>() : brands(idx, kind);
        block.setVisibility(bs.isEmpty() ? View.GONE : View.VISIBLE);
        if (!bs.contains(brandSel)) brandSel = "";
        View cv = block.findViewWithTag(BLOCK + ":chips");
        if (cv instanceof LinearLayout) {
            LinearLayout chips = (LinearLayout) cv;
            chips.removeAllViews();
            for (final String b : bs) {
                TextView c = IvaSearchPatch.chip(a, b, b.equals(brandSel), false);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            brandSel = b.equals(brandSel) ? "" : b;
                            refresh(a);
                            View fv = v.getRootView() == null ? null : v.getRootView().findViewWithTag(BLOCK + ":field");
                            if (fv instanceof EditText) { fv.requestFocus(); fillModelDrop(a, (EditText) fv); }
                        } catch (Throwable ignore) {}
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = IvaPatch.dp(a, 8);
                chips.addView(c, lp);
            }
        }
        View fv = block.findViewWithTag(BLOCK + ":field");
        if (fv instanceof EditText) {
            EditText f = (EditText) fv;
            String now = String.valueOf(f.getText());
            if (chosen != null) {
                if (!modelText(chosen).equals(now)) setQuiet(f, modelText(chosen));
                shown = modelText(chosen);
            } else if (!shown.isEmpty() && shown.equals(now)) setQuiet(f, "");      // модель сняли — убираем её надпись
            if (chosen == null) shown = "";
        }
        if (bs.isEmpty()) { View d = block.findViewWithTag(BLOCK + ":drop"); if (d != null) d.setVisibility(View.GONE); }
    }

    static void setQuiet(TextView v, String text) {
        quiet = true;
        try { v.setText(text); } finally { quiet = false; }
    }

    // ───── «Характеристики» ─────

    static void paintSpecs(final Activity a, LinearLayout box, String kind, boolean force) {
        List<Field> fl = fieldsFor(fields, kind);
        box.setVisibility(fl.isEmpty() ? View.GONE : View.VISIBLE);
        if (!force && kind.equals(specsKind)) return;
        specsKind = kind;
        box.removeAllViews();
        if (fl.isEmpty()) return;
        TextView nv = nameView(a);
        box.addView(label(a, SPECS_LABEL), lpTop(a, 14));
        for (final Field f : fl) {
            box.addView(small(a, f.label()), lpTop(a, 8));
            if ("choice".equals(f.type)) {
                HorizontalScrollView hs = new HorizontalScrollView(a);
                hs.setHorizontalScrollBarEnabled(false);
                hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
                final LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                hs.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
                paintChoice(a, row, f);
                box.addView(hs, lpTop(a, 4));
            } else {
                EditText e = new EditText(a);
                styleInput(a, e, nv);
                e.setSingleLine(true);
                e.setHint(f.unit);
                if ("num".equals(f.type)) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                setQuiet(e, g(specVals, f.key));
                e.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                    @Override public void afterTextChanged(android.text.Editable s) { if (!quiet) specVals.put(f.key, String.valueOf(s)); }
                });
                box.addView(e, lpTop(a, 4));
            }
        }
    }

    static void paintChoice(final Activity a, final LinearLayout row, final Field f) {
        row.removeAllViews();
        String cur = g(specVals, f.key);
        for (final String o : f.options) {
            TextView c = IvaSearchPatch.chip(a, o, o.equals(cur), false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    specVals.put(f.key, o.equals(g(specVals, f.key)) ? "" : o);
                    paintChoice(a, row, f);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = IvaPatch.dp(a, 8);
            row.addView(c, lp);
        }
    }

    static TextView label(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(15f);
        t.setTextColor(cText(a));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    static TextView small(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(cGray(a));
        return t;
    }

    static LinearLayout.LayoutParams lpTop(Activity a, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = IvaPatch.dp(a, top);
        return lp;
    }

    /** Поле — как штатное «Название»: тот же фон, отступы и размер текста. */
    static void styleInput(Activity a, EditText e, TextView like) {
        e.setTextSize(15f);
        e.setTextColor(cText(a));
        e.setHintTextColor(cHint(a));
        if (like != null) {
            e.setBackground(IvaKindPatch.copy(like.getBackground(), a, "bg_field"));
            e.setPadding(like.getPaddingLeft(), like.getPaddingTop(), like.getPaddingRight(), like.getPaddingBottom());
            e.setMinHeight(like.getHeight() > 0 ? like.getHeight() : IvaPatch.dp(a, 48));
        } else e.setBackground(IvaPatch.drawable(a, "bg_field"));
    }

    // ════════════════════════════════ 4. Запись (вместе с видом, IvaKindPatch.consumePending) ════════════════════

    /** Нажали «Опубликовать»: что дописать к записи вида. Справочник не загружен или вида нет — ничего. */
    static String saveExtra(Activity a, String kind, String name) {
        if (!ready() || kind == null || kind.isEmpty()) return "";
        long id = chosen != null && chosen.kind.equals(kind) && stillFits(name, chosen) ? chosen.id : 0;
        return extraBody(id, specsClean(specVals, fieldsFor(fields, kind)));
    }

    static volatile long savedAt = 0;

    /** Нажали «Опубликовать» (форма «Добавить»). */
    static void onSaveTouch() { savedAt = System.currentTimeMillis(); }

    /** Первая вкладка после «Опубликовать»: кабинет — штатный успех, форма начинается заново; другая — ничего. */
    static void onTab(Activity a, String tab) {
        long at = savedAt;
        if (at == 0) return;
        savedAt = 0;
        if (IvaKindPatch.pendingWrite(tab, at, System.currentTimeMillis())) reset(a);
    }

    /** Опубликовано — форма «Добавить» начинается заново. */
    static void reset(Activity a) {
        chosen = null; brandSel = ""; shown = "";
        specVals = new LinkedHashMap<String, String>(); specAuto = new LinkedHashMap<String, String>();
        specsKind = null;
        try {
            TextView nv = nameView(a);
            View root = nv == null ? null : nv.getRootView();
            View f = root == null ? null : root.findViewWithTag(BLOCK + ":field");
            if (f instanceof TextView) setQuiet((TextView) f, "");
            refresh(a);
        } catch (Throwable ignore) {}
    }
}

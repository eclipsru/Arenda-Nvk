// Ива 10.82–10.84 — справочник моделей, этапы 2–4 (решение владельца 15.10.2026, журнал — решение №31).
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
        int code, kn;                                  // kn (10.86) — сколько моделей у вида
    }

    static final class Index {
        final List<Entry> list = new ArrayList<Entry>();
        final List<String> brandToks = new ArrayList<String>(), stemToks = new ArrayList<String>();
        final List<String> vocab = new ArrayList<String>();   // 10.86: словарь для исправления опечаток (от 4 букв)
        final List<String> words = new ArrayList<String>();   // 10.86: все слова без цифр — «слово есть в справочнике»
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
        Map<String, Integer> perKind = new HashMap<String, Integer>();       // 10.86
        for (Entry e : ix.list) { Integer n = perKind.get(e.m.kind); perKind.put(e.m.kind, n == null ? 1 : n + 1); }
        for (Entry e : ix.list) e.kn = perKind.get(e.m.kind);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Entry e : ix.list) { for (String t : e.strong) addV(ix, seen, t); for (String t : e.weak) addV(ix, seen, t); }
        for (String t : ix.stemToks) addV(ix, seen, t);
        for (String t : ix.brandToks) addV(ix, seen, t);
        Collections.sort(ix.vocab);
        Collections.sort(ix.words);
        return ix;
    }

    static void addV(Index ix, java.util.Set<String> seen, String t) {
        if (DIGIT.matcher(t).find() || !seen.add(t)) return;
        ix.words.add(t);
        if (t.length() >= 4) ix.vocab.add(t);
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

    /** 10.86: слова-связки — не слово вида. */
    static final List<String> STOP = java.util.Arrays.asList("для", "под", "над", "без", "при", "или");

    /** Очки модели или null; miss[0] — сколько слов запроса не совпало, miss[1] — было ли сильное совпадение (1/0),
     *  miss[2] — сколько слов совпало со словом названия (10.86). */
    static Integer score(Entry e, List<QTok> qi, int[] miss) {
        int total = 0;
        boolean hasStrong = false, kindHit = false;
        miss[0] = 0; miss[1] = 0; miss[2] = 0;
        for (QTok q : qi) {
            String w = q.w;
            int best = 0;
            for (String st : e.strong) {
                if (st.equals(w)) { best = 2 * w.length() + 1; break; }
                if (w.length() >= 2 && st.startsWith(w) && 2 * w.length() > best) best = 2 * w.length();
            }
            if (best > 0) { hasStrong = true; total += best; continue; }
            boolean weak = false, kh = false;
            for (String x : e.weak) if (x.startsWith(w)) { weak = true; break; }
            if (weak) { kh = true; miss[2]++; }
            if (!weak) for (String x : e.stems) if (w.startsWith(x)) { weak = true; break; }
            // 10.86: слово вида — начало слова названия, начало слова вида или слово вида с другим окончанием (до 2 букв)
            if (!q.digit && w.length() >= 3 && !STOP.contains(w)) {
                if (!kh) for (String x : e.stems) if (x.startsWith(w) || (w.startsWith(x) && w.length() - x.length() <= 2)) { kh = true; break; }
            } else kh = false;
            if (kh) { weak = true; kindHit = true; }
            if (weak) { total += w.length(); continue; }
            if (q.digit || q.brand) return null;
            miss[0]++;
            total -= q.stem ? 2 * w.length() : w.length();
        }
        miss[1] = hasStrong ? 1 : 0;
        return (hasStrong || kindHit) && total > 0 ? Integer.valueOf(total) : null;
    }

    /** 10.86: другая раскладка — латиница ↔ кириллица по клавишам (строчные). */
    static final String LAY_EN = "`qwertyuiop[]asdfghjkl;'zxcvbnm,.", LAY_RU = "ёйцукенгшщзхъфывапролджэячсмитьбю";

    static String swapLayout(String s) {
        String t = s(s).toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int k = LAY_EN.indexOf(c);
            if (k >= 0) b.append(LAY_RU.charAt(k));
            else if ((k = LAY_RU.indexOf(c)) >= 0) b.append(LAY_EN.charAt(k));
            else b.append(c);
        }
        return b.toString();
    }

    /** 10.86: число ошибок между словами (вставка, удаление, замена, перестановка соседних букв). */
    static int typoDist(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + c);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) v = Math.min(v, d[i - 2][j - 2] + 1);
                d[i][j] = v;
            }
        }
        return d[n][m];
    }

    static boolean known(String w, Index ix) {
        for (String t : ix.words) if (t.startsWith(w)) return true;
        for (String t : ix.stemToks) if (w.startsWith(t)) return true;
        for (String t : ix.brandToks) if (t.startsWith(w)) return true;
        return false;
    }

    /** 10.86: запрос с исправленными опечатками или null (как ivaFuzzyQuery на сайте). */
    static String fuzzyQuery(String query, Index ix) {
        List<String> q = toks(query);
        boolean changed = false;
        for (int i = 0; ix != null && i < q.size(); i++) {
            String w = q.get(i);
            if (w.length() < 4 || DIGIT.matcher(w).find() || known(w, ix)) continue;
            int lim = w.length() >= 8 ? 2 : 1, bd = lim + 1;
            String best = null;
            for (String t : ix.vocab) {
                if (t.length() < w.length() - lim) continue;
                int d = typoDist(w, t);
                if (t.length() > w.length()) d = Math.min(d, typoDist(w, t.substring(0, w.length())));
                if (d < bd) { bd = d; best = t; }
            }
            if (best != null) { q.set(i, best); changed = true; }
        }
        return changed ? joinSp(q) : null;
    }

    static String joinSp(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String x : l) { if (b.length() > 0) b.append(' '); b.append(x); }
        return b.toString();
    }

    /** 10.86: латиница → кириллица для слов только из латинских букв, которых нет в справочнике (как ivaTranslit). */
    static final String[][] TR2 = {{"shch", "щ"}, {"sch", "щ"}, {"zh", "ж"}, {"kh", "х"}, {"ts", "ц"}, {"ch", "ч"}, {"sh", "ш"},
        {"yu", "ю"}, {"ya", "я"}, {"yo", "е"}, {"ye", "е"}};
    static final String TR1_EN = "abcdefghijklmnopqrstuvwxz", TR1_RU = "абкдефгхийклмнопкрстуввкз";
    static final Pattern LATIN = Pattern.compile("^[a-z]+$");

    static String translit(String query, Index ix) {
        List<String> q = toks(query);
        boolean changed = false;
        for (int i = 0; ix != null && i < q.size(); i++) {
            String w = q.get(i);
            if (!LATIN.matcher(w).matches() || known(w, ix)) continue;
            StringBuilder out = new StringBuilder();
            int p = 0;
            while (p < w.length()) {
                String[] hit = null;
                for (String[] t : TR2) if (w.startsWith(t[0], p)) { hit = t; break; }
                if (hit != null) { out.append(hit[1]); p += hit[0].length(); continue; }
                char c = w.charAt(p);
                if (c == 'y') out.append(p > 0 && "aeiouy".indexOf(w.charAt(p - 1)) >= 0 ? 'й' : 'ы');
                else if (c == 'x') out.append("кс");
                else out.append(TR1_RU.charAt(TR1_EN.indexOf(c)));
                p++;
            }
            if (!out.toString().equals(w)) { q.set(i, out.toString()); changed = true; }
        }
        return changed ? joinSp(q) : null;
    }

    static final class Hit {
        final Entry e; final int s, miss, u, c, kn, th; final boolean st;
        Hit(Entry e, int s, int miss, boolean st, int u, int c, int kn, int th) {
            this.e = e; this.s = s; this.miss = miss; this.st = st; this.u = u; this.c = c; this.kn = kn; this.th = th;
        }
    }

    static final class Found {
        final List<Model> list; final boolean full;
        Found(List<Model> list, boolean full) { this.list = list; this.full = full; }
    }

    /** Подсказки: kind и brand — фильтр (пусто — без фильтра); пустой запрос с видом — модели вида по названию.
     *  10.86: не совпали все слова — ещё раз с другой раскладкой, латиницей по-русски, исправлением опечаток
     *  (первый вариант, где совпали все слова, иначе первый непустой) — как ivaModelSearch на сайте. */
    static List<Model> search(String query, Index ix, String kind, String brand, int limit) {
        Found first = searchOnce(query, ix, kind, brand, limit);
        if (first.full || ix == null || toks(query).isEmpty()) return first.list;
        String sw = swapLayout(query);
        String[] alts = {sw.equals(s(query).toLowerCase(Locale.ROOT)) ? null : sw, translit(query, ix), fuzzyQuery(query, ix)};
        List<Model> any = first.list.isEmpty() ? null : first.list;
        for (String alt : alts) {
            if (alt == null) continue;
            Found r = searchOnce(alt, ix, kind, brand, limit);
            if (r.full) return r.list;
            if (any == null && !r.list.isEmpty()) any = r.list;
        }
        return any == null ? new ArrayList<Model>() : any;
    }

    static Found searchOnce(String query, Index ix, String kind, String brand, int limit) {
        if (limit <= 0) limit = 8;
        List<String> q = toks(query);
        List<Model> out = new ArrayList<Model>();
        if (ix == null) return new Found(out, false);
        List<QTok> qi = queryInfo(q, ix);
        List<Hit> res = new ArrayList<Hit>();
        boolean full = false;
        int[] miss = new int[3];
        boolean byKind = kind != null && !kind.isEmpty(), byBrand = brand != null && !brand.isEmpty();
        for (Entry e : ix.list) {
            if (byKind && !e.m.kind.equals(kind)) continue;
            if (byBrand && !e.m.brand.equals(brand)) continue;
            int sc, ms = 0, th = 0;
            boolean st = false;
            if (q.isEmpty()) {
                if (!byKind) continue;
                sc = 0;
            } else {
                Integer r = score(e, qi, miss);
                if (r == null) continue;
                sc = r; ms = miss[0]; st = miss[1] == 1; th = miss[2];
            }
            if (ms == 0) full = true;
            res.add(new Hit(e, sc, ms, st, st ? uncovered(e, qi) : 0, st ? e.code : 0, st || q.isEmpty() ? 0 : e.kn, st || q.isEmpty() ? 0 : th));
        }
        if (full) {
            List<Hit> f = new ArrayList<Hit>();
            for (Hit h : res) if (h.miss == 0) f.add(h);
            res = f;
        }
        Collections.sort(res, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.st != b.st) return a.st ? -1 : 1;
                if (a.s != b.s) return b.s - a.s;
                if (a.th != b.th) return b.th - a.th;
                if (a.kn != b.kn) return b.kn - a.kn;
                if (a.u != b.u) return a.u - b.u;
                if (a.c != b.c) return a.c - b.c;
                return a.e.key.compareTo(b.e.key);
            }
        });
        for (int i = 0; i < res.size() && out.size() < limit; i++) out.add(res.get(i).e.m);
        return new Found(out, full);
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

    /** 10.86 (решение владельца 15.10): поля характеристик к показу в форме «Добавить». fl — поля вида, modelSpecs —
     *  характеристики выбранной модели (нет — пусто), values — что в форме. «Питание» — только если у модели оно указано,
     *  одним вариантом — её; «Напряжение аккумулятора» — только при выбранном «Аккумуляторе» (у вида без «Питания» —
     *  всегда). Как ivaFormFields на сайте; общие случаи — tests/form-fields-cases.txt. */
    static final String SRC = "src", VOLT = "volt", BATTERY = "Аккумулятор";

    static List<Field> formFields(List<Field> fl, Map<String, String> modelSpecs, Map<String, String> values) {
        List<Field> out = new ArrayList<Field>();
        if (fl == null) return out;
        String src = modelSpecs == null ? "" : g(modelSpecs, SRC).trim();
        boolean hasSrc = false;
        for (Field f : fl) if (SRC.equals(f.key)) hasSrc = true;
        boolean srcOn = hasSrc && !src.isEmpty();
        String cur = srcOn && values != null && g(values, SRC).trim().equals(src) ? src : "";
        for (Field f : fl) {
            if (SRC.equals(f.key)) {
                if (srcOn) out.add(new Field(f.kind, f.key, f.title, f.unit, f.type, new String[]{src}, f.sort));
            } else if (VOLT.equals(f.key)) {
                if (!hasSrc || BATTERY.equals(cur)) out.add(f);
            } else out.add(f);
        }
        return out;
    }

    /** 10.89 (просьба владельца 15.10): нажали бренд над полем «Модель». Поле пустое — бренд встаёт в начало («Makita »).
     *  В поле другой бренд (из списка или прежний выбранный) — бренд меняется на нажатый, и список моделей — уже его
     *  (модель прежнего бренда убирается). Свой текст без бренда не трогаем. Сняли бренд — убираем его, только если в поле
     *  кроме него ничего нет. */
    static String brandPut(String cur, String prev, String next, List<String> brands) {
        String raw = cur == null ? "" : cur;
        String t = raw.replace('\u00a0', ' ').trim();
        String n = next == null ? "" : next.trim();
        if (t.isEmpty()) return n.isEmpty() ? "" : n + " ";
        List<String> all = new ArrayList<String>();
        if (brands != null) all.addAll(brands);
        if (prev != null && !prev.trim().isEmpty()) all.add(prev.trim());
        if (!n.isEmpty()) all.add(n);
        String lead = "", low = t.toLowerCase(Locale.ROOT);
        for (String b : all) {
            String bl = b == null ? "" : b.trim().toLowerCase(Locale.ROOT);
            if (bl.isEmpty() || bl.length() <= lead.length() || !low.startsWith(bl)) continue;
            if (low.length() == bl.length() || low.charAt(bl.length()) == ' ') lead = bl;
        }
        if (lead.isEmpty()) return raw;                                          // свой текст — как был
        if (n.isEmpty()) return t.length() == lead.length() ? "" : raw;          // сняли бренд
        if (lead.equals(n.toLowerCase(Locale.ROOT))) return raw;                 // тот же бренд
        return n + " ";
    }

    /** 10.89 (просьба владельца 15.10): модель — в названии. Что дописали в прошлый раз (old) в конце названия —
     *  меняем на новое; уже есть в названии — не дублируем (и не повторяем слова, которыми название уже кончается). */
    static String nameWithModel(String name, String old, String nw) {
        String n = name == null ? "" : name.replaceAll("\\s+$", "");
        String o = old == null ? "" : old.trim(), m = nw == null ? "" : nw.trim().replaceAll("\\s+", " ");
        String base = n;
        if (!o.isEmpty() && n.toLowerCase(Locale.ROOT).endsWith(o.toLowerCase(Locale.ROOT)))
            base = n.substring(0, n.length() - o.length()).replaceAll("\\s+$", "");
        if (m.isEmpty()) return base;
        if ((" " + base.toLowerCase(Locale.ROOT) + " ").contains(" " + m.toLowerCase(Locale.ROOT) + " ")) return base;
        String[] bw = base.trim().isEmpty() ? new String[0] : base.trim().split("\\s+"), mw = m.split(" ");
        int k = Math.min(bw.length, mw.length);
        for (; k > 0; k--) {                                                       // хвост названия = начало модели
            boolean same = true;
            for (int i = 0; i < k && same; i++) same = bw[bw.length - k + i].equalsIgnoreCase(mw[i]);
            if (same) break;
        }
        StringBuilder add = new StringBuilder();
        for (int i = k; i < mw.length; i++) add.append(add.length() > 0 ? " " : "").append(mw[i]);
        if (add.length() == 0) return base;
        return base.isEmpty() ? add.toString() : base + " " + add;
    }

    static String nameModel = "";                     // что из «Модели» дописано в название в последний раз

    /** Вписали модель руками — она же в конце названия (выбор из списка ставит название сам). */
    static void syncName(Activity a, EditText f) {
        if (chosen != null) return;
        TextView nv = nameView(a);
        if (nv == null) return;
        String m = String.valueOf(f.getText()).trim(), cur = String.valueOf(nv.getText());
        String nt = nameWithModel(cur, nameModel, m);
        nameModel = m;
        if (nt.equals(cur)) return;
        quiet = true;
        try { nv.setText(nt); } finally { quiet = false; }
    }

    /** Надпись модели в поле «Модель»: «Makita HR2470». */
    static String modelText(Model m) { return m == null ? "" : (m.brand + " " + m.model).trim(); }

    static String modelsPath(int offset) {
        return "/rest/v1/iva_models?select=id,kind,brand,model,title,specs&active=eq.true&order=id.asc&limit=" + PAGE + "&offset=" + offset;
    }

    static final int PAGE = 1000;                     // PostgREST отдаёт не больше 1000 строк за раз
    static final String BRANDS_PATH = "/rest/v1/iva_brands?select=name,aliases&order=name.asc";
    static final String FIELDS_PATH = "/rest/v1/iva_spec_fields?select=kind,key,title,unit,type,options,sort&order=kind.asc,sort.asc";
    static final String MODEL_LABEL = "Модель", MODEL_HINT = "Например: HR2470 — или выберите из списка", SPECS_LABEL = "Характеристики";
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
                    try {                                                            // 10.84: комплектация; нет таблицы — без неё
                        String[] rk = IvaClientPatch.apiCall(a, KIT_PATH, "GET", null, false);
                        if (ok(rk)) kitMap = parseKit(rk[1]);
                    } catch (Throwable ignore) {}
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
    static String specsKind = null;                               // для каких полей построены характеристики (10.86: вид + поля + варианты)
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
        if (block == null) {                                                     // 10.86: «Модель» — сразу под названием (и его подсказками)
            View sug = form.findViewWithTag(NAME_SUG);
            View after = sug != null && sug.getParent() == form ? sug : nv;
            block = buildBlock(a, nv);
            form.addView(block, form.indexOfChild(after) + 1, new LinearLayout.LayoutParams(-1, -2));
        }
        if (specs == null) { specs = new LinearLayout(a); ((LinearLayout) specs).setOrientation(LinearLayout.VERTICAL); specs.setTag(SPECS);
            form.addView(specs, form.indexOfChild(block) + 1, new LinearLayout.LayoutParams(-1, -2)); }
        paintBlock(a, (LinearLayout) block, kind);
        paintSpecs(a, (LinearLayout) specs, kind, false);
        paintExtras(a, form, specs, kind);                                       // 10.84: комплектация, цена, описание
        try { IvaKindPatch.arrange(a); } catch (Throwable ignore) {}             // 10.86: подписи и «Категория…» над блоком HELP
    }

    static void hideAll(View any) {
        View root = any.getRootView();
        if (root == null) return;
        for (String t : new String[]{NAME_SUG, BLOCK, SPECS, KIT, HELP, HELP + ":price"}) {
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
        nameModel = modelText(m);                                                 // 10.89: название кончается моделью
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
        box.addView(small(a, MODEL_LABEL), lpTop(a, 12));                       // 10.86: подпись мелким, как «Состояние»
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
                    @Override public void run() {
                        if (my != modelSeq) return;
                        try { syncName(a, f); } catch (Throwable ignore) {}            // 10.89: модель — в названии
                        try { fillModelDrop(a, f); } catch (Throwable ignore) {}
                    }
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
        TextView pv = small(a, "");                                               // 10.84: «Предложить модель»
        pv.setTag(PROP);
        pv.setVisibility(View.GONE);
        box.addView(pv, lpTop(a, 6));
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
        // 10.86: «Модель» под названием — и без вида (тогда по всем моделям, нужен хоть один символ)
        List<Model> hits = !f.hasFocus() || (kind.isEmpty() && q.trim().isEmpty()) ? new ArrayList<Model>()
            : search(q, idx, kind, kind.isEmpty() ? "" : brandSel, MODEL_SUGGEST);
        if (chosen != null && q.trim().equals(modelText(chosen))) hits.clear();     // модель уже выбрана — список не нужен
        TextView nv = nameView(a);
        for (Model m : hits) d.addView(item(a, m, modelText(m), nv), new LinearLayout.LayoutParams(-1, -2));
        d.setVisibility(hits.isEmpty() ? View.GONE : View.VISIBLE);
        View bl = f.getParent() instanceof LinearLayout ? (View) f.getParent() : null;
        if (bl instanceof LinearLayout) paintProp(a, (LinearLayout) bl, kind);
    }

    static void paintBlock(final Activity a, LinearLayout block, String kind) {
        List<String> bs = kind.isEmpty() ? new ArrayList<String>() : brands(idx, kind);
        block.setVisibility(View.VISIBLE);                                       // 10.86: всегда (под названием); бренды — когда вид известен
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
                            String prev = brandSel;
                            brandSel = b.equals(brandSel) ? "" : b;
                            View fv = v.getRootView() == null ? null : v.getRootView().findViewWithTag(BLOCK + ":field");
                            if (fv instanceof EditText) {                  // 10.89: бренд — текстом в поле «Модель»
                                EditText f = (EditText) fv;
                                String nt = brandPut(String.valueOf(f.getText()), prev, brandSel, brands(idx, kindNow()));
                                if (!nt.equals(String.valueOf(f.getText()))) { f.setText(nt); f.setSelection(nt.length()); }
                            }
                            refresh(a);
                            if (fv instanceof EditText) {
                                fv.requestFocus();
                                ((EditText) fv).setSelection(((EditText) fv).getText().length());
                                fillModelDrop(a, (EditText) fv);
                            }
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
        paintProp(a, block, kind);
    }

    static void setQuiet(TextView v, String text) {
        quiet = true;
        try { v.setText(text); } finally { quiet = false; }
    }

    // ───── «Характеристики» ─────

    /** 10.86: поля к показу — «Питание» только у выбранной модели, напряжение — при аккумуляторе (formFields). */
    static List<Field> visFields(String kind) {
        Model m = chosen != null && chosen.kind.equals(kind) ? chosen : null;
        return formFields(fieldsFor(fields, kind), m == null ? null : m.specs, specVals);
    }

    static String sigOf(String kind, List<Field> fl) {
        StringBuilder b = new StringBuilder(kind);
        for (Field f : fl) { b.append('|').append(f.key); for (String o : f.options) b.append(';').append(o); }
        return b.toString();
    }

    static void paintSpecs(final Activity a, LinearLayout box, String kind, boolean force) {
        List<Field> fl = visFields(kind);
        box.setVisibility(fl.isEmpty() ? View.GONE : View.VISIBLE);
        String sig = sigOf(kind, fl);
        if (!force && sig.equals(specsKind)) return;
        specsKind = sig;
        box.removeAllViews();
        if (fl.isEmpty()) return;
        TextView nv = nameView(a);
        boolean first = true;                                                    // 10.87: «Характеристики…» — заголовок раздела над блоком
        for (final Field f : fl) {
            box.addView(small(a, f.label()), lpTop(a, first ? 12 : 8));
            first = false;
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
                    if (SRC.equals(f.key)) {                                     // 10.86: питание сменилось — напряжение аккумулятора появляется / пропадает
                        View root = v.getRootView(), box = root == null ? null : root.findViewWithTag(SPECS);
                        if (box instanceof LinearLayout) try { paintSpecs(a, (LinearLayout) box, kindNow(), false); } catch (Throwable ignore) {}
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = IvaPatch.dp(a, 8);
            row.addView(c, lp);
        }
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
        String ex = extraBody(id, specsClean(specVals, visFields(kind)));      // 10.86: только показанные поля
        List<String> kit = kitClean(kitSel, kitMap.get(kind));                   // 10.84: комплектация — всегда последней (dropKit)
        if (kit != null) ex += (ex.isEmpty() ? "" : ",") + "\"kit\":" + kitJson(kit);
        return ex;
    }

    static volatile long savedAt = 0;

    /** Нажали «Опубликовать» (форма «Добавить»). */
    static void onSaveTouch() { savedAt = System.currentTimeMillis(); }

    /** Первая вкладка после «Опубликовать»: кабинет — штатный успех, форма начинается заново; другая — ничего. */
    static void onTab(Activity a, String tab) {
        if ("detail".equals(tab)) { try { onDetail(a); } catch (Throwable ignore) {} }   // 10.83: характеристики в карточке
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
        kitSel = new ArrayList<String>(); kitKind = null; lastDescr = "";        // 10.84
        try {
            TextView nv = nameView(a);
            View root = nv == null ? null : nv.getRootView();
            View f = root == null ? null : root.findViewWithTag(BLOCK + ":field");
            if (f instanceof TextView) setQuiet((TextView) f, "");
            refresh(a);
        } catch (Throwable ignore) {}
    }

    // ════════════════════════════════ 10.83: характеристики в карточке и в фильтре (этап 3) ════════════════════════════
    //  * Карточка инструмента (страница «detail»): блок «Характеристики» над отзывами — «Вид» и заполненные поля вида
    //    («Мощность — 780 Вт»). Ничего нет — блока нет. Данные — tools.kind и tools.specs из штатного каталога
    //    (select=*), подписи — iva_spec_fields (у клиента грузим только их, без моделей).
    //  * Фильтр (IvaSearchPatch): когда выбран вид — характеристики-выбор плашками («Питание: Аккумулятор»), только
    //    значения, которые есть у подходящих объявлений. Объявление без этой характеристики при выбранном значении
    //    не показывается (как «Состояние»).

    /** Строки карточки: [подпись, значение] по полям вида в их порядке. Число — с запятой, единица — через пробел. */
    static List<String[]> specRows(Map<String, String> specs, List<Field> flds) {
        List<String[]> out = new ArrayList<String[]>();
        if (specs == null || flds == null) return out;
        for (Field f : flds) {
            String v = g(specs, f.key).trim();
            if (v.isEmpty()) continue;
            String t = "num".equals(f.type) && NUM.matcher(v).matches() ? v.replace('.', ',') : v;
            if (!f.unit.isEmpty()) t += "\u00a0" + f.unit;
            out.add(new String[]{f.title, t});
        }
        return out;
    }

    /** Поля-выбор вида (для фильтра) — в порядке полей. */
    static List<Field> choiceFields(List<Field> all, String kind) {
        List<Field> out = new ArrayList<Field>();
        for (Field f : fieldsFor(all, kind)) if ("choice".equals(f.type) && f.options.length > 0) out.add(f);
        return out;
    }

    /** Характеристики объявления подходят под выбранные: каждое выбранное значение совпадает (нет значения — не подходит). */
    static boolean specsMatch(Map<String, String> tool, Map<String, String> sel) {
        if (sel == null) return true;
        for (Map.Entry<String, String> e : sel.entrySet())
            if (!s(e.getValue()).equals(g(tool, e.getKey()).trim())) return false;
        return true;
    }

    /** Надпись плашки выбранного условия: «Питание: Аккумулятор» (подписи не загрузились — только значение). */
    static String chipText(List<Field> all, String kind, String key, String value) {
        for (Field f : fieldsFor(all, kind)) if (f.key.equals(key)) return f.title + ": " + value;
        return s(value);
    }

    static final java.util.Map<Object, LinkedHashMap<String, String>> specCache = new java.util.WeakHashMap<Object, LinkedHashMap<String, String>>();

    /** tools.specs объявления из каталога (штатный JSONObject; у Android optString объекта — его JSON). */
    static LinkedHashMap<String, String> toolSpecs(JSONObject j) {
        if (j == null) return new LinkedHashMap<String, String>();
        LinkedHashMap<String, String> m = specCache.get(j);
        if (m == null) {
            m = parseFlat(j.has("specs") && !j.isNull("specs") ? j.optString("specs", "") : "");
            specCache.put(j, m);
        }
        return m;
    }

    static volatile boolean fieldsLoading = false;
    static volatile long fieldsTriedAt = 0;

    /** Подписи характеристик для клиента (без моделей). Пришли — перерисовать карточку и панель фильтра. */
    static void ensureFields(final Activity a) {
        if (fields != null || fieldsLoading || loading) return;
        long now = System.currentTimeMillis();
        if (now - fieldsTriedAt < RETRY_MS) return;
        fieldsLoading = true;
        fieldsTriedAt = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] rf = IvaClientPatch.apiCall(a, FIELDS_PATH, "GET", null, false);
                    if (!ok(rf)) return;
                    List<Field> fl = parseFields(rf[1]);
                    if (fl.isEmpty()) return;
                    if (fields == null) fields = fl;
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { onDetail(a); } catch (Throwable ignore) {}
                            try { IvaSearchPatch.refreshPanel(a); } catch (Throwable ignore) {}
                            try { IvaSearchPatch.refreshChips(a); } catch (Throwable ignore) {}
                        }
                    });
                } catch (Throwable ignore) {
                } finally { fieldsLoading = false; }
            }
        }).start();
    }

    static final String DET_TAG = "iva_model_det";
    static final String TERMS_HEAD = "Условия аренды";

    // ───── 10.89 (просьба владельца 15.10): в карточке — всё из формы, по разделам ─────
    //  «Характеристики»: Вид, Модель, Состояние (строка «Состояние: …» из условий), поля вида, Комплектация.
    //  «Условия аренды»: без строки «Состояние», несколько строк — списком.

    /** Первые строки «Характеристик»: вид, модель, состояние (пустое — строки нет). */
    static List<String[]> detHead(String kindTitle, String model, String terms) {
        List<String[]> rows = new ArrayList<String[]>();
        if (kindTitle != null && !kindTitle.trim().isEmpty()) rows.add(new String[]{"Вид", kindTitle.trim()});
        if (model != null && !model.trim().isEmpty()) rows.add(new String[]{MODEL_LABEL, model.trim()});
        int c = IvaPatch.condIndex(terms);
        if (c >= 0) rows.add(new String[]{IvaPatch.COND_LABEL, IvaPatch.COND_TITLES[c]});
        return rows;
    }

    /** Текст штатной строки условий в карточке: без «Состояния»; одна строка — «Условия аренды: …», несколько — списком. */
    static String cardTerms(String terms) {
        List<String> lines = new ArrayList<String>();
        for (String l : IvaPatch.condStrip(terms == null ? "" : terms).split("\n")) {
            String t = l.trim().replaceAll("^[•·*–—-]+\\s*", "").trim();
            if (!t.isEmpty()) lines.add(t);
        }
        if (lines.isEmpty()) return "";
        if (lines.size() == 1) return TERMS_HEAD + ": " + lines.get(0);
        StringBuilder b = new StringBuilder(TERMS_HEAD).append(':');
        for (String l : lines) b.append("\n• ").append(l);
        return b.toString();
    }

    static final Map<String, String> modelNames = java.util.Collections.synchronizedMap(new HashMap<String, String>());
    static final java.util.Set<String> modelAsked = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** «Makita HR2470» по model_id: из загруженного справочника, из памяти или (один раз) запросом — потом карточка ещё раз. */
    static String modelName(final Activity a, final String id) {
        if (id == null || !id.matches("[0-9]{1,12}")) return "";
        List<Model> ms = modelList;
        if (ms != null) for (Model m : ms) if (String.valueOf(m.id).equals(id)) return modelText(m);
        String got = modelNames.get(id);
        if (got != null) return got;
        if (a == null || !modelAsked.add(id)) return "";
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/iva_models?select=brand,model&id=eq." + id, "GET", null, false);
                    if (!ok(r)) return;
                    org.json.JSONArray arr = new org.json.JSONArray(r[1].trim());
                    JSONObject m = arr.length() > 0 ? arr.optJSONObject(0) : null;
                    if (m == null) return;
                    String t = (m.optString("brand", "") + " " + m.optString("model", "")).trim();
                    if (t.isEmpty()) return;
                    modelNames.put(id, t);
                    a.runOnUiThread(new Runnable() { @Override public void run() { try { onDetail(a); } catch (Throwable ignore) {} } });
                } catch (Throwable ignore) {}
            }
        }).start();
        return "";
    }

    static String jstr(JSONObject j, String k) {
        return j != null && j.has(k) && !j.isNull(k) ? j.optString(k, "").trim() : "";
    }

    /** Штатная строка условий карточки — по 10.89 (без «Состояния», списком); пусто — строки нет. */
    static void paintTerms(Activity a, JSONObject j) {
        Object tv = IvaClientPatch.get(a, "detTerms");
        if (!(tv instanceof TextView)) return;
        String t = cardTerms(jstr(j, "terms"));
        if (t.isEmpty()) { ((TextView) tv).setVisibility(View.GONE); return; }
        if (!t.equals(String.valueOf(((TextView) tv).getText()))) ((TextView) tv).setText(t);
    }

    /** Строки блока карточки: «Вид» и характеристики. Подписи ещё не пришли, а характеристики есть — пусто (ждём). */
    static List<String[]> detRows(Activity a, JSONObject j) {
        List<String[]> rows = new ArrayList<String[]>();
        String kind = j.has("kind") && !j.isNull("kind") ? j.optString("kind", "").trim() : "";
        LinkedHashMap<String, String> sp = toolSpecs(j);
        if (fields == null && !sp.isEmpty()) return rows;
        String kt = kind.isEmpty() ? "" : IvaKindPatch.titleOf(IvaKindPatch.list(), kind);
        rows.addAll(detHead(kt, modelName(a, jstr(j, "model_id")), jstr(j, "terms")));   // 10.89: + модель, состояние
        rows.addAll(specRows(sp, fieldsFor(fields, kind)));
        List<String> kit = j.has("kit") && !j.isNull("kit") ? parseStrList(j.optString("kit", "")) : new ArrayList<String>();   // 10.84
        if (!kit.isEmpty()) {
            StringBuilder kb = new StringBuilder();
            for (String k : kit) kb.append(kb.length() > 0 ? ", " : "").append(k);
            rows.add(new String[]{KIT_LABEL, kb.toString()});
        }
        return rows;
    }

    /** Страница инструмента: блок «Характеристики» над отзывами (IvaReviewPatch добавляет свой блок в конец). */
    static void onDetail(final Activity a) {
        Object it = IvaClientPatch.get(a, "detItem"), nm = IvaClientPatch.get(a, "detName");
        if (!(nm instanceof View) || !(((View) nm).getParent() instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) ((View) nm).getParent();
        View old = box.findViewWithTag(DET_TAG);
        boolean srv = Boolean.TRUE.equals(IvaClientPatch.get(a, "detIsSrv"));
        if (it instanceof JSONObject && !srv) try { paintTerms(a, (JSONObject) it); } catch (Throwable ignore) {}   // 10.89
        List<String[]> rows = it instanceof JSONObject && !srv ? detRows(a, (JSONObject) it) : new ArrayList<String[]>();
        if (it instanceof JSONObject && !srv && fields == null && !toolSpecs((JSONObject) it).isEmpty()) ensureFields(a);
        if (rows.isEmpty()) { if (old != null) old.setVisibility(View.GONE); return; }
        LinearLayout block = old instanceof LinearLayout ? (LinearLayout) old : null;
        if (block == null) {
            block = new LinearLayout(a);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setTag(DET_TAG);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = IvaPatch.dp(a, 18);
            View rv = box.findViewWithTag(IvaReviewPatch.DET_BLOCK_TAG);
            box.addView(block, rv != null && rv.getParent() == box ? box.indexOfChild(rv) : box.getChildCount(), bl);
        }
        block.setVisibility(View.VISIBLE);
        block.removeAllViews();
        TextView h = new TextView(a);
        h.setText(SPECS_LABEL);
        h.setTextSize(17f);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setTextColor(cText(a));
        block.addView(h, new LinearLayout.LayoutParams(-1, -2));
        int line = IvaPatch.color(a, "line", 0xff262b35);
        for (int i = 0; i < rows.size(); i++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, IvaPatch.dp(a, 9), 0, IvaPatch.dp(a, 9));
            TextView k = new TextView(a);
            k.setText(rows.get(i)[0]);
            k.setTextSize(14f);
            k.setTextColor(cGray(a));
            TextView v = new TextView(a);
            v.setText(rows.get(i)[1]);
            v.setTextSize(14f);
            v.setTextColor(cText(a));
            v.setTypeface(null, android.graphics.Typeface.BOLD);
            v.setGravity(Gravity.END);
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, -2, 1f);
            kl.rightMargin = IvaPatch.dp(a, 12);
            row.addView(k, kl);
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setContentDescription(rows.get(i)[0] + ": " + rows.get(i)[1]);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            if (i == 0) rl.topMargin = IvaPatch.dp(a, 6);
            block.addView(row, rl);
            if (i < rows.size() - 1) {
                View d = new View(a);
                d.setBackgroundColor(line);
                block.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, IvaPatch.dp(a, 1) / 2)));
            }
        }
    }

    // ════════════════════════════════ 10.84: этап 4 — предложить модель, цена, описание, комплектация ════════════════
    //  * «Модель» не нашлась — под полем «Нет в списке? Предложить «Makita HR9999»». Касание — запись в
    //    iva_model_proposals (SQL 20261018_models_stage4.sql); в справочник модель попадает после одобрения главным админом.
    //  * «Комплектация» — пункты вида (iva_kit_items) кнопками; отмеченное пишется в tools.kit вместе с видом.
    //  * Подсказка цены по активным объявлениям из каталога в памяти (той же модели от 2, иначе вида от 3) — только текст
    //    и «Подставить» в пустые поля; «Сгенерировать описание» — в пустое описание (или своё прошлое), ручное не трогаем.
    //  Те же правила на сайте — assets/models.js / model-form.js; оба проверяются на tests/model-stage4-cases.txt.

    static final Pattern WS = Pattern.compile("[\\s\\u00a0]+");

    static String squash(String v) { return WS.matcher(s(v)).replaceAll(" ").trim(); }
    static String low(String v) { return s(v).toLowerCase(Locale.ROOT); }

    /** Бренд и модель из текста поля «Модель» (как ivaProposalParts). null — предложить нечего. */
    static String[] proposalParts(String text, String brandSel, Map<String, String[]> brands) {
        String t = squash(text), lt = low(t), best = null;
        int bl = 0;
        if (t.isEmpty()) return null;
        if (brands != null) for (Map.Entry<String, String[]> b : brands.entrySet()) {
            if (b.getKey() == null || b.getKey().isEmpty()) continue;
            List<String> cands = new ArrayList<String>();
            cands.add(b.getKey());
            if (b.getValue() != null) Collections.addAll(cands, b.getValue());
            for (String c0 : cands) {
                String c = low(squash(c0));
                if (!c.isEmpty() && c.length() > bl && (lt.equals(c) || lt.startsWith(c + " "))) { best = b.getKey(); bl = c.length(); }
            }
        }
        String brand, model, sel = squash(brandSel);
        if (!sel.isEmpty()) {
            brand = sel;
            model = best != null && low(best).equals(low(sel)) ? t.substring(bl) : t;
        } else if (best != null) {
            brand = best; model = t.substring(bl);
        } else {
            int sp = t.indexOf(' ');
            if (sp < 0) return null;
            brand = t.substring(0, sp); model = t.substring(sp + 1);
        }
        model = squash(model);
        if (brand.isEmpty() || model.isEmpty() || brand.length() > 40 || model.length() > 60) return null;
        return new String[]{brand, model};
    }

    /** Название предложения: название объявления, если в нём есть код модели, иначе «Вид Бренд Модель» (до 120). */
    static String proposalTitle(String name, String[] parts, String kindTitle) {
        if (parts == null) return "";
        String code = join(toks(parts[1])), n = squash(name), out;
        if (!n.isEmpty() && !code.isEmpty() && join(toks(n)).contains(code)) out = n;
        else out = squash((kindTitle == null || kindTitle.isEmpty() ? "" : kindTitle + " ") + parts[0] + " " + parts[1]);
        return out.length() > 120 ? out.substring(0, 120) : out;
    }

    /** Модель уже есть у вида: текст слитно = модель слитно или бренд + модель слитно. */
    static Model exact(Index ix, String kind, String text) {
        String tj = join(toks(text));
        if (tj.isEmpty() || ix == null) return null;
        for (Entry e : ix.list) {
            String c = join(e.codeToks);
            if (e.m.kind.equals(kind) && !c.isEmpty() && (c.equals(tj) || (join(toks(e.m.brand)) + c).equals(tj))) return e.m;
        }
        return null;
    }

    /** Ошибка сервера → понятный текст (как ivaProposalErr). */
    static String propErr(String msg) {
        String m = s(msg), l = low(m);
        if (l.contains("already in directory")) return "Такая модель уже есть в справочнике — выберите её в подсказках.";
        if (l.contains("duplicate key") || l.contains("23505") || l.contains("pending_uq")) return "Эту модель уже предложили — она ждёт проверки.";
        if (l.contains("too many pending")) return "Слишком много предложений ждут проверки — дождитесь решения по ним.";
        if (l.contains("row-level security") || l.contains("42501") || l.contains("permission denied")) return "Предлагать модели могут только администраторы проката.";
        if (l.contains("iva_model_proposals") || l.contains("pgrst205") || l.contains("does not exist") || l.contains("schema cache")) return "Предложения заработают после обновления базы.";
        return "Не удалось отправить: " + (m.isEmpty() ? "нет связи" : m) + ".";
    }

    /** Тело записи предложения. */
    static String proposalBody(String kind, String[] parts, String title, String specsJson) {
        return "{\"kind\":" + jsonStr(kind) + ",\"brand\":" + jsonStr(parts[0]) + ",\"model\":" + jsonStr(parts[1])
            + ",\"title\":" + jsonStr(title) + ",\"specs\":" + (specsJson == null ? "{}" : specsJson) + "}";
    }

    static final class PRow {
        final double price, deposit;
        final long modelId;
        final String kind;
        PRow(double price, double deposit, long modelId, String kind) { this.price = price; this.deposit = deposit; this.modelId = modelId; this.kind = s(kind); }
    }

    static final class Hint {
        int n;
        boolean byModel;
        long[] price, deposit;                       // [от, до, середина]; deposit — null, если залог указан меньше чем у двух
    }

    static long round50(double v) { return v >= 100 ? Math.round(v / 50) * 50 : Math.round(v); }

    static long[] spread(List<Double> a) {
        int n = a.size();
        if (n == 0) return null;
        Collections.sort(a);
        double lo = n >= 5 ? a.get((n - 1) / 4) : a.get(0), hi = n >= 5 ? a.get((int) Math.ceil(3.0 * (n - 1) / 4)) : a.get(n - 1);
        double mid = n % 2 == 1 ? a.get((n - 1) / 2) : (a.get(n / 2 - 1) + a.get(n / 2)) / 2;
        return new long[]{round50(lo), round50(hi), round50(mid)};
    }

    static boolean pos(double v) { return !Double.isNaN(v) && !Double.isInfinite(v) && v > 0; }

    /** Подсказка цены (как ivaPriceHint): той же модели от 2 объявлений, иначе того же вида от 3. */
    static Hint priceHint(List<PRow> rows, long modelId, String kind) {
        List<PRow> pick = new ArrayList<PRow>();
        boolean byModel = false, any = false;
        if (rows == null) return null;
        if (modelId > 0) {
            for (PRow r : rows) if (r != null && pos(r.price) && r.modelId == modelId) pick.add(r);
            if (pick.size() >= 2) { byModel = true; any = true; }
        }
        if (!any && kind != null && !kind.isEmpty()) {
            pick.clear();
            for (PRow r : rows) if (r != null && pos(r.price) && kind.equals(r.kind)) pick.add(r);
            if (pick.size() >= 3) any = true;
        }
        if (!any) return null;
        List<Double> p = new ArrayList<Double>(), d = new ArrayList<Double>();
        for (PRow r : pick) { p.add(r.price); if (pos(r.deposit)) d.add(r.deposit); }
        Hint h = new Hint();
        h.n = pick.size(); h.byModel = byModel; h.price = spread(p); h.deposit = d.size() >= 2 ? spread(d) : null;
        return h;
    }

    static String groups(long v) {
        String t = String.valueOf(v);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            if (i > 0 && (t.length() - i) % 3 == 0) b.append('\u00a0');
            b.append(t.charAt(i));
        }
        return b.toString();
    }

    static String rangeT(long[] r) { return r[0] == r[1] ? groups(r[0]) + "\u00a0₽" : groups(r[0]) + "–" + groups(r[1]) + "\u00a0₽"; }

    /** «Обычно сдают за 700–1 000 ₽ в сутки, залог 3 000–5 000 ₽ (12 объявлений этой модели)». */
    static String hintText(Hint h) {
        if (h == null || h.price == null) return "";
        int n = h.n;
        String w = n % 10 == 1 && n % 100 != 11 ? "объявление" : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? "объявления" : "объявлений";
        return "Обычно сдают за " + rangeT(h.price) + " в сутки" + (h.deposit != null ? ", залог " + rangeT(h.deposit) : "")
            + " (" + n + " " + w + (h.byModel ? " этой модели" : " этого вида") + ")";
    }

    /** «Сгенерировать описание» (как ivaDescribe). Нет названия — "". */
    static String describe(String name, List<String[]> rows, List<String> kit) {
        String n = squash(name).replaceAll("[.!\\s]+$", "");
        if (n.isEmpty()) return "";
        StringBuilder b = new StringBuilder(n).append(" в аренду.");
        if (rows != null && !rows.isEmpty()) {
            b.append("\n\nХарактеристики:");
            for (String[] r : rows) b.append("\n— ").append(r[0]).append(": ").append(r[1]);
        }
        List<String> ks = new ArrayList<String>();
        if (kit != null) for (String k0 : kit) {
            String k = squash(k0);
            if (k.isEmpty()) continue;
            ks.add(k.length() > 1 && Character.isLowerCase(k.charAt(1)) ? k.substring(0, 1).toLowerCase(Locale.ROOT) + k.substring(1) : k);
        }
        if (!ks.isEmpty()) {
            b.append("\n\nВ комплекте: ");
            for (int i = 0; i < ks.size(); i++) b.append(i > 0 ? ", " : "").append(ks.get(i));
            b.append('.');
        }
        return b.toString();
    }

    /** Комплектация к сохранению: только пункты вида, в их порядке, без повторов; пусто — null. */
    static List<String> kitClean(List<String> sel, String[] items) {
        List<String> out = new ArrayList<String>();
        if (sel != null && items != null) for (String it : items) if (sel.contains(it) && !out.contains(it)) out.add(it);
        return out.isEmpty() ? null : out;
    }

    static String kitJson(List<String> kit) {
        if (kit == null) return null;
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < kit.size(); i++) b.append(i > 0 ? "," : "").append(jsonStr(kit.get(i)));
        return b.append(']').toString();
    }

    /** Характеристики формы для описания: как specsClean, но строками (число — с точкой). */
    static LinkedHashMap<String, String> cleanMap(Map<String, String> values, List<Field> flds) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (flds == null) return out;
        for (Field f : flds) {
            String raw = g(values, f.key).trim();
            if (raw.isEmpty()) continue;
            if ("num".equals(f.type)) {
                String t = raw.replace(',', '.');
                if (!NUM.matcher(t).matches()) continue;
                out.put(f.key, numText(t));
            } else if ("choice".equals(f.type)) {
                if (java.util.Arrays.asList(f.options).contains(raw)) out.put(f.key, raw);
            } else out.put(f.key, raw);
        }
        return out;
    }

    /** Список строк из JSON-массива (tools.kit у объявления: у Android optString массива — его JSON). */
    static List<String> parseStrList(String json) {
        List<String> out = new ArrayList<String>();
        String t = s(json).trim();
        if (!t.startsWith("[")) return out;
        int[] p = {1};
        boolean closed = false;
        try {
            while (p[0] < t.length()) {
                skipWs(t, p);
                if (p[0] >= t.length()) break;
                if (t.charAt(p[0]) == ']') { closed = true; break; }
                if (t.charAt(p[0]) == ',') { p[0]++; continue; }
                if (t.charAt(p[0]) != '"') return new ArrayList<String>();
                String v = readStr(t, p).trim();
                if (!v.isEmpty()) out.add(v);
            }
        } catch (Throwable e) { return new ArrayList<String>(); }
        return closed ? out : new ArrayList<String>();
    }

    /** Поля записи без комплектации (kit всегда последним) — для базы без столбца kit. */
    static String dropKit(String extra) {
        if (extra == null) return "";
        int i = extra.indexOf("\"kit\":");
        if (i < 0) return extra;
        String t = extra.substring(0, i);
        return t.endsWith(",") ? t.substring(0, t.length() - 1) : t;
    }

    // ───── 10.84: интерфейс этапа 4 (форма «Добавить») ─────

    static final String KIT_PATH = "/rest/v1/iva_kit_items?select=kind,items", PROPOSALS_PATH = "/rest/v1/iva_model_proposals";
    static final String KIT = "iva_model_kit", HELP = "iva_model_help", PROP = BLOCK + ":prop";
    static final String KIT_LABEL = "Комплектация", KIT_HINT = "отметьте, что отдаёте вместе с инструментом", DESCR_BTN = "Сгенерировать описание";
    /** 10.91: «Сгенерировать описание» убрана по просьбе владельца 15.10.2026 (код оставлен: вернуть — true). */
    static final boolean SHOW_GEN = false;
    static volatile Map<String, String[]> kitMap = new HashMap<String, String[]>();
    static List<String> kitSel = new ArrayList<String>();
    static String kitKind = null, lastDescr = "";
    static final Map<String, String> propState = new HashMap<String, String>();

    static Map<String, String[]> parseKit(String text) throws Exception {
        JSONArray arr = new JSONArray(text.trim());
        Map<String, String[]> out = new HashMap<String, String[]>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String k = IvaKindPatch.clean(o.optString("kind", ""));
            String[] it = strArr(o.optJSONArray("items"));
            if (!k.isEmpty() && it.length > 0) out.put(k, it);
        }
        return out;
    }

    static String fieldText(Activity a, String name) {
        Object o = IvaClientPatch.get(a, name);
        return o instanceof TextView ? String.valueOf(((TextView) o).getText()) : null;
    }

    static void paintExtras(Activity a, LinearLayout form, View after, String kind) {
        View kit = form.findViewWithTag(KIT), help = form.findViewWithTag(HELP);
        if (kit == null) { kit = new LinearLayout(a); ((LinearLayout) kit).setOrientation(LinearLayout.VERTICAL); kit.setTag(KIT);
            form.addView(kit, form.indexOfChild(after) + 1, new LinearLayout.LayoutParams(-1, -2)); }
        if (help == null) { help = buildHelp(a); form.addView(help, form.indexOfChild(kit) + 1, new LinearLayout.LayoutParams(-1, -2)); }
        if (form.findViewWithTag(HELP + ":price") == null) {                     // 10.87: подсказка цены — в разделе «Цена», над полем
            TextView price = small(a, "");
            price.setTag(HELP + ":price");
            price.setVisibility(View.GONE);
            form.addView(price, form.indexOfChild(help), lpTop(a, 10));
        }
        paintKit(a, (LinearLayout) kit, kind, false);
        paintHelp(a, (LinearLayout) help, kind);
    }

    /** «Комплектация»: пункты вида кнопками (несколько сразу). Пунктов нет — блока нет. */
    static void paintKit(final Activity a, LinearLayout box, String kind, boolean force) {
        String[] items = kind.isEmpty() ? null : kitMap.get(kind);
        box.setVisibility(items == null || items.length == 0 ? View.GONE : View.VISIBLE);
        if (!force && kind.equals(kitKind)) return;
        kitKind = kind;
        box.removeAllViews();
        if (items == null || items.length == 0) return;
        List<String> keep = kitClean(kitSel, items);
        kitSel = keep == null ? new ArrayList<String>() : keep;
        box.addView(small(a, KIT_LABEL + " — " + KIT_HINT), lpTop(a, 12));   // 10.87: подпись мелким, как у остальных пунктов
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        paintKitRow(a, row, items);
        box.addView(hs, lpTop(a, 6));
    }

    static void paintKitRow(final Activity a, final LinearLayout row, final String[] items) {
        row.removeAllViews();
        for (final String it : items) {
            TextView c = IvaSearchPatch.chip(a, it, kitSel.contains(it), false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        if (kitSel.contains(it)) kitSel.remove(it); else kitSel.add(it);
                        List<String> k = kitClean(kitSel, items);
                        kitSel = k == null ? new ArrayList<String>() : k;
                        paintKitRow(a, row, items);
                    } catch (Throwable ignore) {}
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = IvaPatch.dp(a, 8);
            row.addView(c, lp);
        }
    }

    static View buildHelp(final Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setTag(HELP);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView gen = IvaSearchPatch.chip(a, DESCR_BTN, false, false);
        gen.setTag(HELP + ":gen");
        if (!SHOW_GEN) gen.setVisibility(View.GONE);                             // 10.91: кнопки нет (просьба владельца)
        gen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { generate(a); } catch (Throwable ignore) {} }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.topMargin = IvaPatch.dp(a, 12);
        box.addView(gen, gl);
        TextView note = small(a, "");
        note.setTag(HELP + ":note");
        note.setVisibility(View.GONE);
        box.addView(note, lpTop(a, 6));
        return box;
    }

    /** Активные объявления из каталога в памяти (штатный select=*). */
    static List<PRow> catalogRows(Activity a) {
        List<PRow> out = new ArrayList<PRow>();
        Object l = IvaSearchPatch.fullTools instanceof ArrayList ? IvaSearchPatch.fullTools : IvaClientPatch.get(a, "tools");
        if (!(l instanceof ArrayList)) return out;
        for (Object o : (ArrayList) l) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            out.add(new PRow(j.optDouble("price", Double.NaN), j.optDouble("deposit", Double.NaN),
                j.isNull("model_id") ? 0 : j.optLong("model_id", 0), j.isNull("kind") ? "" : j.optString("kind", "")));
        }
        return out;
    }

    static void paintHelp(final Activity a, LinearLayout box, String kind) {
        box.setVisibility(View.VISIBLE);
        View pv = box.getParent() instanceof View ? ((View) box.getParent()).findViewWithTag(HELP + ":price") : null;   // 10.87: в форме
        if (!(pv instanceof TextView)) return;
        TextView price = (TextView) pv;
        final Hint h = kind.isEmpty() ? null : priceHint(catalogRows(a), chosen != null && chosen.kind.equals(kind) ? chosen.id : 0, kind);
        if (h == null) { price.setVisibility(View.GONE); return; }
        String txt = hintText(h);
        String cur = fieldText(a, "atPrice");
        boolean canFill = cur != null && cur.trim().isEmpty();
        String act = canFill ? " · Подставить " + groups(h.price[2]) + "\u00a0₽" : "";
        android.text.SpannableString sp = new android.text.SpannableString(txt + act);
        if (canFill) sp.setSpan(new android.text.style.ForegroundColorSpan(IvaPatch.color(a, "orange", 0xffff8f1f)),
            txt.length() + 3, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        price.setText(sp);
        price.setVisibility(View.VISIBLE);
        price.setOnClickListener(!canFill ? null : new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Object pf = IvaClientPatch.get(a, "atPrice"), df = IvaClientPatch.get(a, "atDep");
                    if (pf instanceof TextView && String.valueOf(((TextView) pf).getText()).trim().isEmpty()) {
                        ((TextView) pf).setText(String.valueOf(h.price[2]));
                        IvaNotifyPatch.flash((View) pf);
                    }
                    if (h.deposit != null && df instanceof TextView && String.valueOf(((TextView) df).getText()).trim().isEmpty())
                        ((TextView) df).setText(String.valueOf(h.deposit[2]));
                    refresh(a);
                } catch (Throwable ignore) {}
            }
        });
    }

    /** «Сгенерировать описание»: в пустое описание или вместо своего прошлого текста; написанное руками не трогаем. */
    static void generate(Activity a) {
        TextView nv = nameView(a);
        Object df = IvaClientPatch.get(a, "atDescr");
        View root = nv == null ? null : nv.getRootView();
        View nt = root == null ? null : root.findViewWithTag(HELP + ":note");
        if (nv == null || !(df instanceof TextView)) return;
        String kind = kindNow();
        List<Field> fl = fieldsFor(fields, kind);
        String[] items = kind.isEmpty() ? null : kitMap.get(kind);
        String text = describe(String.valueOf(nv.getText()), specRows(cleanMap(specVals, fl), fl), items == null ? null : kitClean(kitSel, items));
        String note = "";
        if (text.isEmpty()) note = "Сначала напишите название.";
        else {
            TextView d = (TextView) df;
            String cur = String.valueOf(d.getText());
            if (cur.trim().isEmpty() || cur.equals(lastDescr)) {
                d.setText(text);
                lastDescr = text;
                IvaNotifyPatch.flash(d);
            } else note = "Описание уже написано — сотрите его, чтобы сгенерировать заново.";
        }
        if (nt instanceof TextView) { ((TextView) nt).setText(note); nt.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE); }
    }

    /** Строка «Предложить модель» под полем «Модель». */
    static void paintProp(final Activity a, LinearLayout block, final String kind) {
        View pv = block.findViewWithTag(PROP), fv = block.findViewWithTag(BLOCK + ":field");
        if (!(pv instanceof TextView) || !(fv instanceof TextView)) return;
        TextView p = (TextView) pv;
        String q = String.valueOf(((TextView) fv).getText());
        final String[] parts = kind.isEmpty() || chosen != null || exact(idx, kind, q) != null || IvaCensorPatch.level(q) == 2   // 24.10.2026: мат не предлагаем
            ? null : proposalParts(q, brandSel, brandMap);
        if (parts == null) { p.setVisibility(View.GONE); p.setOnClickListener(null); return; }
        final String key = kind + "|" + low(parts[0]) + "|" + low(parts[1]), nm = parts[0] + " " + parts[1];
        String st = propState.get(key);
        p.setVisibility(View.VISIBLE);
        if ("ok".equals(st) || "sending".equals(st)) {
            p.setText("ok".equals(st) ? "«" + nm + "» отправлена главному админу на проверку. Объявление можно разместить уже сейчас."
                : "Отправляем «" + nm + "»…");
            p.setOnClickListener(null);
            return;
        }
        String pre = st != null ? st + " " : "Нет в списке? ", act = "Предложить «" + nm + "»", post = st != null ? "" : " — главный админ проверит и добавит в справочник.";
        android.text.SpannableString sp = new android.text.SpannableString(pre + act + post);
        sp.setSpan(new android.text.style.ForegroundColorSpan(IvaPatch.color(a, "orange", 0xffff8f1f)), pre.length(), pre.length() + act.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        p.setText(sp);
        p.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { sendProposal(a, kind, parts, key); } catch (Throwable ignore) {} }
        });
    }

    static void sendProposal(final Activity a, String kind, String[] parts, final String key) {
        TextView nv = nameView(a);
        String title = proposalTitle(nv == null ? "" : String.valueOf(nv.getText()), parts, IvaKindPatch.titleOf(IvaKindPatch.list(), kind));
        final String body = proposalBody(kind, parts, title, specsClean(specVals, visFields(kind)));
        propState.put(key, "sending");
        refresh(a);
        new Thread(new Runnable() {
            @Override public void run() {
                String st;
                try {
                    String[] r = IvaClientPatch.apiCall(a, PROPOSALS_PATH, "POST", body, true);
                    if (r != null && r[0] != null && r[0].startsWith("2")) st = "ok";
                    else {
                        String raw = r == null || r.length < 2 || r[1] == null ? "" : r[1].trim(), m = raw;
                        try { if (raw.startsWith("{")) { String mm = g(parseFlat(raw), "message"); if (!mm.isEmpty()) m = mm; } } catch (Throwable ignore) {}
                        st = propErr(m);
                    }
                } catch (Throwable e) { st = propErr(""); }
                final String fs = st;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { propState.put(key, fs); try { refresh(a); } catch (Throwable ignore) {} }
                });
            }
        }).start();
    }

    // ════════════════════════════════ 10.85: фильтр по характеристикам — числа «от–до» ════════════════════════════════
    //  * В шторке «Фильтр» под характеристиками-выбором — числовые поля вида («Мощность, Вт: от … до …»), только те,
    //    что указаны хоть у одного объявления этого вида; под полями — разброс у подходящих объявлений.
    //  * Объявление без нужной характеристики не скрывается, а показывается в конце списка (specState = 0).
    //  Те же правила на сайте — assets/models.js (ivaNum, ivaSpecState, ivaRangeChip, ivaRangeHint, ivaMissingNote);
    //  оба проверяются на tests/spec-filter-cases.txt.

    static final Pattern NUM_IN = Pattern.compile("^\\d{1,9}([.,]\\d{1,3})?$");

    /** Число характеристики или поля «от/до»: «800», «2,4», «1 500» → число; иначе NaN. */
    static double num(String v) {
        String t = s(v).replaceAll("[\\s\\u00a0]+", "");
        if (!NUM_IN.matcher(t).matches()) return Double.NaN;
        try { return Double.parseDouble(t.replace(',', '.')); } catch (Throwable e) { return Double.NaN; }
    }

    /** Числовые поля вида (для фильтра) — в порядке полей. */
    static List<Field> numFields(List<Field> all, String kind) {
        List<Field> out = new ArrayList<Field>();
        for (Field f : fieldsFor(all, kind)) if ("num".equals(f.type)) out.add(f);
        return out;
    }

    /** Границы по порядку («от» больше «до» — меняются местами); NaN — без границы. */
    static double[] normRange(double[] r) {
        double lo = r == null || r.length < 1 ? Double.NaN : r[0], hi = r == null || r.length < 2 ? Double.NaN : r[1];
        if (!Double.isNaN(lo) && !Double.isNaN(hi) && lo > hi) { double t = lo; lo = hi; hi = t; }
        return new double[]{lo, hi};
    }

    static boolean rangeOn(double[] r) { double[] n = normRange(r); return !Double.isNaN(n[0]) || !Double.isNaN(n[1]); }

    /** 1 — подходит; 0 — нужной характеристики нет (показываем в конце списка); -1 — не подходит. */
    static int specState(Map<String, String> tool, Map<String, String> sel, Map<String, double[]> ranges) {
        boolean missing = false;
        if (sel != null) for (Map.Entry<String, String> e : sel.entrySet()) {
            String v = g(tool, e.getKey()).trim();
            if (v.isEmpty()) missing = true;
            else if (!s(e.getValue()).equals(v)) return -1;
        }
        if (ranges != null) for (Map.Entry<String, double[]> e : ranges.entrySet()) {
            double[] r = normRange(e.getValue());
            if (Double.isNaN(r[0]) && Double.isNaN(r[1])) continue;
            double v = num(g(tool, e.getKey()));
            if (Double.isNaN(v)) missing = true;
            else if ((!Double.isNaN(r[0]) && v < r[0]) || (!Double.isNaN(r[1]) && v > r[1])) return -1;
        }
        return missing ? 0 : 1;
    }

    /** Число для надписи: «1 500», «2,4», «2,25» (не больше двух знаков после запятой). */
    static String numText(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        double r = Math.round(v * 100) / 100.0;
        if (r == Math.rint(r)) return groups((long) r);
        String t = java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
        return t.replace('.', ',');
    }

    /** Число для поля ввода: без пробелов («1500», «2,4»). */
    static String numInput(double v) { return numText(v).replace("\u00a0", ""); }

    /** «600–1 500 Вт», «от 600 Вт», «до 2,5 кг», «800 Вт». */
    static String rangeText(double[] r0, String unit) {
        double[] r = normRange(r0);
        String t;
        if (!Double.isNaN(r[0]) && !Double.isNaN(r[1])) t = r[0] == r[1] ? numText(r[0]) : numText(r[0]) + "–" + numText(r[1]);
        else if (!Double.isNaN(r[0])) t = "от " + numText(r[0]);
        else if (!Double.isNaN(r[1])) t = "до " + numText(r[1]);
        else return "";
        return s(unit).isEmpty() ? t : t + "\u00a0" + unit;
    }

    /** Надпись плашки: «Мощность: 600–1 500 Вт» (подписи не загрузились — только числа). */
    static String rangeChip(List<Field> all, String kind, String key, double[] r) {
        for (Field f : fieldsFor(all, kind)) if (f.key.equals(key)) return f.title + ": " + rangeText(r, f.unit);
        return rangeText(r, "");
    }

    /** Подсказка под полями «от/до»: разброс у подходящих объявлений. */
    static String rangeHint(double min, double max, int n, String unit) {
        if (n <= 0 || Double.isNaN(min) || Double.isNaN(max)) return "В подходящих объявлениях не указано.";
        String u = s(unit).isEmpty() ? "" : "\u00a0" + unit;
        if (min == max) return "Сейчас у всех, где указано, — " + numText(min) + u + ".";
        return "Сейчас в выдаче: от " + numText(min) + " до " + numText(max) + u + ".";
    }

    /** Строка под характеристиками: сколько объявлений без нужной характеристики (они в конце списка). */
    static String missingNote(int n) {
        if (n <= 0) return "";
        String w = n % 10 == 1 && n % 100 != 11 ? "объявление" : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? "объявления" : "объявлений";
        return "Ещё " + n + " " + w + " без указанной характеристики — в конце списка.";
    }
}

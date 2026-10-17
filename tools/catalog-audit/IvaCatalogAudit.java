package ru.prokatnvsk.app;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ива — проверка фильтров приложения (IvaSearchPatch) на настоящем каталоге. Только чтение, база не трогается.
 *
 * Вход — выгрузка tools/catalog-audit/fetch.py (tools.tsv, cats.tsv, subcats.tsv). Считает по каждому фильтру,
 * откуда данные и у скольких объявлений они есть, и сверяет «Показать N» с лентой: N считает панель
 * (IvaSearchPatch.nativeMatch + extraMatch), лента — НЕЗАВИСИМАЯ копия штатного renderFeed/inCat из 10.14
 * (см. ниже nativeFeed) по списку, который кладёт в tools applySearch. Расхождение — ошибка (код выхода 1).
 * Пишет app-cities.tsv (id → город и состояние по правилам приложения) для сверки с сайтом (site-compare.cjs).
 *
 * Запуск: java -cp <классы IvaSearchPatch>:<этот класс>:android.jar ru.prokatnvsk.app.IvaCatalogAudit /tmp/catalog
 */
public final class IvaCatalogAudit {

    static final String MISSING = "\u0001", NULL = "\u0002";

    /** Объявление: поля как в выгрузке (MISSING / NULL / текст). */
    static final class Tool {
        Map<String, String> f = new LinkedHashMap<String, String>();
        String cats;   // MISSING — нет массива, иначе элементы через \u001f

        String raw(String k) { String v = f.get(k); return v == null ? MISSING : v; }
        /** org.json Android optString(k, "") / optString(k): нет — "", JSON null — "null". */
        String opt(String k) { String v = raw(k); return MISSING.equals(v) ? "" : NULL.equals(v) ? "null" : v; }
        /** IvaSearchPatch.js: нет или null — "". */
        String js(String k) { String v = raw(k); return MISSING.equals(v) || NULL.equals(v) ? "" : v; }
        List<String> catsList() {
            if (cats == null || MISSING.equals(cats) || NULL.equals(cats)) return null;
            if (cats.isEmpty()) return new ArrayList<String>();
            return new ArrayList<String>(Arrays.asList(cats.split("\u001f", -1)));
        }
        /** Android optDouble("price", 0): число или строка-число, иначе 0. */
        double price() {
            String v = raw("price");
            if (MISSING.equals(v) || NULL.equals(v)) return 0;
            try { return Double.parseDouble(v.trim()); } catch (Throwable e) { return 0; }
        }
        boolean priceParsable() {
            String v = raw("price");
            if (MISSING.equals(v) || NULL.equals(v)) return true;
            try { Double.parseDouble(v.trim()); return true; } catch (Throwable e) { return false; }
        }
        /** Android optBoolean("delivery", false): true/"true" (без учёта регистра). */
        boolean delivery() { String v = raw("delivery"); return "true".equalsIgnoreCase(v); }
        String cityC; Integer condC;
        String city() { if (cityC == null) cityC = IvaSearchPatch.toolCity(js("pickup_city"), js("pickup_address"), js("terms")); return cityC; }
        int cond() { if (condC == null) condC = IvaSearchPatch.condFromTerms(js("terms")); return condC; }
    }

    static String unesc(String s) {
        if (MISSING.equals(s) || NULL.equals(s)) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else b.append(c);
        }
        return b.toString();
    }

    static List<String[]> readTsv(String path) throws Exception {
        List<String[]> rows = new ArrayList<String[]>();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8));
        String line;
        boolean head = true;
        while ((line = r.readLine()) != null) {
            if (head) { head = false; rows.add(line.split("\t", -1)); continue; }
            rows.add(line.split("\t", -1));
        }
        r.close();
        return rows;
    }

    // ── Независимая копия штатной ленты 10.14 (renderFeed + inCat, разобраны из classes.dex) ──
    static boolean nativeInCat(Tool t, String cat) {
        if (cat == null || cat.isEmpty()) return true;
        if (cat.equals(t.opt("cat"))) return true;
        List<String> cs = t.catsList();
        if (cs == null) return false;
        for (String c : cs) if (cat.equals(c)) return true;
        return false;
    }

    static boolean nativeFeed(Tool t, String catMode, String curCat, String curSub, String query) {
        if ("rented".equals(t.opt("t_status"))) return false;
        if ("cat".equals(catMode) && !curCat.isEmpty() && !nativeInCat(t, curCat)) return false;
        if ("cat".equals(catMode) && !curSub.isEmpty() && !curSub.equals(t.opt("sub"))) return false;
        String hay = (t.opt("name") + " " + t.opt("descr")).toLowerCase();
        return query.isEmpty() || hay.contains(query);
    }

    // ── Путь панели (как IvaSearchPatch.countFor / extraMatch(JSONObject …)) ──
    static boolean panelNative(Tool t, String catMode, String cat, String sub, String query) {
        return IvaSearchPatch.nativeMatch(t.opt("t_status"), t.opt("cat"), t.catsList(), t.opt("sub"), t.opt("name"), t.opt("descr"),
            catMode, cat, sub, query);
    }

    static boolean panelExtra(Tool t, String city, long from, long to, boolean del, int conds) {
        return IvaSearchPatch.extraMatch(t.city(), t.price(), t.delivery(), t.cond(), city, from, to, del, conds);
    }

    static PrintWriter out;
    static List<String> notices = new ArrayList<String>();
    static int errors = 0;

    static void section(String title, List<String> lines) {
        out.println("== " + title);
        for (String l : lines) out.println("   " + l);
        StringBuilder b = new StringBuilder();
        for (String l : lines) { if (b.length() > 0) b.append("%0A"); b.append(l.replace("%", "%25")); }
        notices.add("::notice title=Каталог · " + title + "::" + b);
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "/tmp/catalog";
        out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

        List<String[]> tr = readTsv(dir + "/tools.tsv");
        String[] head = tr.get(0);
        List<Tool> tools = new ArrayList<Tool>();
        for (int i = 1; i < tr.size(); i++) {
            String[] row = tr.get(i);
            if (row.length < head.length) continue;
            Tool t = new Tool();
            for (int k = 0; k < head.length; k++) {
                if ("cats".equals(head[k])) {
                    String v = row[k];
                    if (MISSING.equals(v) || NULL.equals(v)) t.cats = MISSING;
                    else {
                        String[] el = v.isEmpty() ? new String[0] : v.split("\u001f", -1);
                        StringBuilder b = new StringBuilder();
                        for (int e = 0; e < el.length; e++) { if (e > 0) b.append('\u001f'); b.append(unesc(el[e])); }
                        t.cats = b.toString();
                    }
                } else t.f.put(head[k], unesc(row[k]));
            }
            tools.add(t);
        }
        List<String[]> catRows = readTsv(dir + "/cats.tsv");
        List<String[]> subRows = readTsv(dir + "/subcats.tsv");
        LinkedHashMap<String, String> cats = new LinkedHashMap<String, String>();
        for (int i = 1; i < catRows.size(); i++) {
            String k = unesc(catRows.get(i)[0]);
            if (MISSING.equals(k) || NULL.equals(k) || k.isEmpty()) continue;
            cats.put(k, unesc(catRows.get(i)[1]));
        }
        LinkedHashMap<String, List<String>> subsOf = new LinkedHashMap<String, List<String>>();
        int subTotal = 0;
        for (int i = 1; i < subRows.size(); i++) {
            String c = unesc(subRows.get(i)[0]), k = unesc(subRows.get(i)[1]);
            if (MISSING.equals(k) || NULL.equals(k) || k.isEmpty()) continue;
            if (!subsOf.containsKey(c)) subsOf.put(c, new ArrayList<String>());
            subsOf.get(c).add(k);
            subTotal++;
        }

        int total = tools.size(), rented = 0;
        List<Tool> live = new ArrayList<Tool>();
        for (Tool t : tools) { if ("rented".equals(t.opt("t_status"))) rented++; else live.add(t); }
        int n = live.size();

        // ── Город ──
        int byPickup = 0, byAddr = 0, unknown = 0, byDefault = 0, pickupRawUnrecognized = 0, withCoords = 0;
        TreeMap<String, Integer> cityDist = new TreeMap<String, Integer>();
        for (Tool t : live) {
            String pc = t.js("pickup_city");
            String c = t.city();
            if (!IvaSearchPatch.city(pc).isEmpty()) byPickup++;
            else {
                if (!pc.trim().isEmpty()) pickupRawUnrecognized++;
                if (!IvaSearchPatch.pickupAddress(t.js("pickup_address"), t.js("terms")).isEmpty()) {
                    if (c.isEmpty()) unknown++; else byAddr++;
                } else byDefault++;
            }
            String key = c.isEmpty() ? "(город не определён)" : c;
            cityDist.put(key, (cityDist.containsKey(key) ? cityDist.get(key) : 0) + 1);
            if (!t.js("pickup_lat").isEmpty() && !t.js("pickup_lng").isEmpty()) withCoords++;
        }
        List<Map.Entry<String, Integer>> cd = new ArrayList<Map.Entry<String, Integer>>(cityDist.entrySet());
        java.util.Collections.sort(cd, new java.util.Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> x, Map.Entry<String, Integer> y) { return y.getValue() - x.getValue(); }
        });
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(8, cd.size()); i++) top.append(i == 0 ? "" : ", ").append(cd.get(i).getKey()).append(" ").append(cd.get(i).getValue());
        section("Город", Arrays.asList(
            "объявлений активных " + total + ", сданы сейчас (в ленте не видны) " + rented + ", в ленте " + n,
            "pickup_city распознан " + byPickup + "; город из адреса выдачи " + byAddr + "; адрес есть, города в нём нет " + unknown
                + "; адреса нет — Новочеркасск (как сайт) " + byDefault,
            "pickup_city заполнен, но не распознан как город " + pickupRawUnrecognized,
            "городов в выдаче " + cityDist.size() + ": " + top,
            "координаты pickup_lat/pickup_lng есть у " + withCoords + " из " + n + (tools.isEmpty() || tools.get(0).f.containsKey("pickup_lat") && !MISSING.equals(tools.get(0).raw("pickup_lat")) ? "" : " (полей координат в выгрузке нет)")));

        // ── Категория и подкатегория ──
        int orphan = 0, withCatsArr = 0;
        LinkedHashMap<String, Integer> catCount = new LinkedHashMap<String, Integer>();
        for (String k : cats.keySet()) catCount.put(k, 0);
        for (Tool t : live) {
            java.util.HashSet<String> in = new java.util.HashSet<String>();
            in.add(t.opt("cat"));
            List<String> cs = t.catsList();
            if (cs != null && !cs.isEmpty()) { withCatsArr++; in.addAll(cs); }
            boolean any = false;
            for (String k : in) if (catCount.containsKey(k)) { catCount.put(k, catCount.get(k) + 1); any = true; }
            if (!any) orphan++;
        }
        List<String> emptyCats = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : catCount.entrySet()) if (e.getValue() == 0) emptyCats.add(cats.get(e.getKey()));
        int withSub = 0, subUnknown = 0;
        int emptySubs = 0;
        LinkedHashMap<String, Integer> subCount = new LinkedHashMap<String, Integer>();
        for (Tool t : live) {
            String s = t.opt("sub");
            if (s.isEmpty() || "null".equals(s)) continue;
            withSub++;
            boolean known = false;
            for (Map.Entry<String, List<String>> e : subsOf.entrySet())
                if (e.getValue().contains(s) && nativeInCat(t, e.getKey())) { known = true; String kk = e.getKey() + "/" + s; subCount.put(kk, (subCount.containsKey(kk) ? subCount.get(kk) : 0) + 1); }
            if (!known) subUnknown++;
        }
        for (Map.Entry<String, List<String>> e : subsOf.entrySet())
            for (String s : e.getValue()) if (!subCount.containsKey(e.getKey() + "/" + s)) emptySubs++;
        // facetVisible на настоящих счётчиках: пустые скрываются, выбранная остаётся
        List<String> visible = IvaSearchPatch.facetVisible(new ArrayList<String>(cats.keySet()), catCount, "");
        section("Категория и подкатегория", Arrays.asList(
            "справочник cats " + cats.size() + "; в панели при «все города, без условий» видно " + visible.size()
                + " (пустых скрыто " + emptyCats.size() + (emptyCats.isEmpty() ? "" : ": " + join(emptyCats, 6)) + ")",
            "объявлений вне всех категорий справочника (видны только в «Все») " + orphan + "; со списком cats " + withCatsArr,
            "справочник subcats " + subTotal + "; объявлений с подкатегорией " + withSub + ", из них с неизвестной (нет в справочнике своей категории) " + subUnknown
                + "; пустых подкатегорий (скрыты) " + emptySubs));

        // ── Цена ──
        int priced = 0, zero = 0, bad = 0;
        double min = -1, max = -1;
        for (Tool t : live) {
            if (!t.priceParsable()) bad++;
            double p = t.price();
            if (p > 0) { priced++; if (min < 0 || p < min) min = p; if (max < 0 || p > max) max = p; } else zero++;
        }
        List<String> priceLines = new ArrayList<String>();
        priceLines.add("поле price (₽ за сутки): с ценой " + priced + ", 0 или пусто " + zero + " (при выборе цены не показываются), не число " + bad);
        priceLines.add(min < 0 ? "цен нет" : "от " + Math.round(min) + " до " + Math.round(max) + " ₽");
        // ввод: пусто, пробелы, не-цифры, «от» > «до»
        String[][] in = {{"", "-1"}, {"  ", "-1"}, {"1 500", "1500"}, {"abc", "-1"}, {"12a3", "123"}, {"0007", "7"}, {"9999999999", "-1"}};
        int okIn = 0;
        for (String[] c : in) if (IvaSearchPatch.priceValue(c[0]) == Long.parseLong(c[1])) okIn++; else errors++;
        long[] sw = IvaSearchPatch.normRange(1000, 300);
        int swapA = 0, swapB = 0;
        for (Tool t : live) {
            if (panelExtra(t, "", 1000, 300, false, 0)) swapA++;
            if (panelExtra(t, "", 300, 1000, false, 0)) swapB++;
        }
        if (swapA != swapB || sw[0] != 300) errors++;
        priceLines.add("разбор ввода " + okIn + "/" + in.length + " верно; «от 1000 до 300» = «от 300 до 1000»: " + swapA + " = " + swapB);
        section("Цена за сутки", priceLines);

        // ── Доставка ──
        int delT = 0, delF = 0, delNull = 0, delOther = 0;
        for (Tool t : live) {
            String v = t.raw("delivery");
            if ("true".equalsIgnoreCase(v)) delT++;
            else if ("false".equalsIgnoreCase(v)) delF++;
            else if (MISSING.equals(v) || NULL.equals(v)) delNull++;
            else delOther++;
        }
        section("Доставка", Arrays.asList("поле delivery: да " + delT + ", нет " + delF + ", пусто " + delNull + ", другое значение " + delOther));

        // ── Состояние ──
        int cNew = 0, cGood = 0, cWork = 0, cNone = 0, cOther = 0;
        TreeMap<String, Integer> otherVals = new TreeMap<String, Integer>();
        Pattern anyCond = Pattern.compile("Состояние:\\s*([^\\n]*)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        for (Tool t : live) {
            int c = t.cond();
            if (c == IvaSearchPatch.COND_NEW) cNew++;
            else if (c == IvaSearchPatch.COND_GOOD) cGood++;
            else if (c == IvaSearchPatch.COND_WORK) cWork++;
            else {
                Matcher m = anyCond.matcher(t.js("terms"));
                if (m.find()) { cOther++; String v = m.group(1).trim(); otherVals.put(v, (otherVals.containsKey(v) ? otherVals.get(v) : 0) + 1); }
                else cNone++;
            }
        }
        section("Состояние", Arrays.asList(
            "строка «Состояние: …» в условиях (terms): Новый " + cNew + ", Хорошее " + cGood + ", Рабочее " + cWork
                + ", не указано " + cNone + ", другое значение " + cOther + (otherVals.isEmpty() ? "" : " (" + join(new ArrayList<String>(otherVals.keySet()), 5) + ")"),
            "сайт считает «не указано» хорошим; приложение при выборе состояния такие не показывает"));

        // ── «Показать N» = лента: все сочетания ──
        List<String> cityOpts = new ArrayList<String>();
        cityOpts.add("");
        for (Map.Entry<String, Integer> e : cd) if (!e.getKey().startsWith("(")) cityOpts.add(e.getKey());
        if (cityOpts.size() > 9) cityOpts = cityOpts.subList(0, 9);
        cityOpts = new ArrayList<String>(cityOpts);
        cityOpts.add("Город-которого-нет");
        List<String[]> catOpts = new ArrayList<String[]>();
        catOpts.add(new String[] {"root", "", ""});
        for (String k : cats.keySet()) {
            catOpts.add(new String[] {"cat", k, ""});
            List<String> ss = subsOf.get(k);
            if (ss != null) for (String s : ss) catOpts.add(new String[] {"cat", k, s});
        }
        long[][] prices = {{-1, -1}, {-1, 500}, {500, -1}, {300, 1000}, {1000, 300}, {0, 0}};
        int[] condOpts = {0, 1, 2, 4, 1 | 4, 7};
        String[] queries = {"", "дрель", "а", "u", "null", "пила"};
        long combos = 0, mismatches = 0;
        String firstMismatch = "";
        for (String city : cityOpts) for (long[] pr : prices) for (int del = 0; del < 2; del++) for (int cond : condOpts) {
            // applySearch: в tools кладётся только то, что прошло город/цену/доставку/состояние
            List<Tool> applied = new ArrayList<Tool>();
            for (Tool t : tools) if (panelExtra(t, city, pr[0], pr[1], del == 1, cond)) applied.add(t);
            for (String[] co : catOpts) for (String q : queries) {
                int panel = 0, feed = 0;
                for (Tool t : applied) {
                    if (panelNative(t, co[0], co[1], co[2], q)) panel++;   // countFor
                    if (nativeFeed(t, co[0], co[1], co[2], q)) feed++;     // штатный renderFeed
                }
                combos++;
                if (panel != feed) {
                    mismatches++;
                    if (firstMismatch.isEmpty()) firstMismatch = "город «" + city + "», " + co[1] + "/" + co[2] + ", цена " + pr[0] + "–" + pr[1]
                        + ", доставка " + del + ", состояние " + cond + ", запрос «" + q + "»: панель " + panel + ", лента " + feed;
                }
            }
        }
        errors += mismatches;
        // одна категория в панели (один проход) = countFor с этой категорией
        int facetErr = 0;
        for (String city : cityOpts) for (String k : cats.keySet()) {
            int single = 0, onePass = 0;
            for (Tool t : tools) {
                if (panelExtra(t, city, -1, -1, false, 0) && panelNative(t, "cat", k, "", "")) single++;
                if (panelNative(t, "root", "", "", "") && panelExtra(t, city, -1, -1, false, 0)) {
                    java.util.HashSet<String> ins = new java.util.HashSet<String>();
                    ins.add(t.opt("cat"));
                    List<String> cs = t.catsList();
                    if (cs != null) ins.addAll(cs);
                    if (ins.contains(k)) onePass++;
                }
            }
            if (single != onePass) facetErr++;
        }
        errors += facetErr;
        section("«Показать N» = лента", Arrays.asList(
            "сочетаний город × категория/подкатегория × цена × доставка × состояние × запрос: " + combos + ", расхождений " + mismatches
                + (firstMismatch.isEmpty() ? "" : " — первое: " + firstMismatch),
            "числа у категорий в панели (один проход) = выдаче по категории: расхождений " + facetErr + " из " + (cityOpts.size() * cats.size())));

        // для сверки с сайтом
        PrintWriter w = new PrintWriter(new OutputStreamWriter(new java.io.FileOutputStream(dir + "/app-cities.tsv"), StandardCharsets.UTF_8));
        for (Tool t : tools) w.println(t.opt("id") + "\t" + t.city() + "\t" + t.cond());
        w.close();

        for (String s : notices) System.out.println(s);
        out.println(errors == 0 ? "ИТОГ: ошибок нет" : "ИТОГ: ошибок " + errors);
        if (errors > 0) System.exit(1);
    }

    static String join(List<String> xs, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(max, xs.size()); i++) b.append(i == 0 ? "" : ", ").append(xs.get(i));
        if (xs.size() > max) b.append(" …");
        return b.toString();
    }
}

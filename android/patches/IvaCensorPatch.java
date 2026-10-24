package ru.prokatnvsk.app;

// ───────────── 10.92: «Цензура» (просьба владельца 15.10.2026) ─────────────
// Решения владельца (ответы 15.10):
//  • явный мат нельзя написать: русскими буквами, латиницей (транслит), похожими латинскими буквами и цифрами,
//    по буквам через пробел, с точками, невидимыми символами, повторами — сохранение не проходит;
//  • сомнительное (грубые слова, слово, разбитое пробелом, звёздочки вместо букв) не публикуется до проверки:
//    база снимает объявление с показа / скрывает отзыв и кладёт запись в очередь «Цензура»;
//  • чат — только запрет явного мата (сомнительные сообщения проходят, их текст создателю не отправляется);
//  • «Цензура» — пункт кабинета только у создателя (eclips.ru@mail.ru), отдельная страница: что, где, кто, весь текст,
//    какое слово и почему; «Оставить» / «Скрыть»; «Это не мат» и «Запрещать всегда» — списки слов в базе.
// Проверка одна и та же в приложении (check) и в базе (public.iva_censor, supabase/migrations/20261021_censor.sql):
// выражения и таблицы замен одинаковые — сторож сверяет строки, общие случаи — tests/censor-cases.txt (IvaPatchTest
// и стенд tools/censor-stand.py на PostgreSQL). Решает всё равно база: приложение лишь заранее показывает понятное
// сообщение. Без новых вставок в smali: пункт кабинета — из IvaNotifyPatch.cabinet (IvaPatch.onTab).

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IvaCensorPatch {
    private IvaCensorPatch() {}

    // ════════════════════════════════ 1. Проверка текста (как public.iva_censor) ════════════════════════════════

    static final String C_UP = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ", C_LOW = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    static final String C_INVIS = "[\\u0300-\\u036f\\u00ad\\u200b-\\u200f\\u2060\\ufeff]";
    static final String C_SEP = "[._*'`~\"-]+";
    static final String C_SPLIT = "[^а-яa-z0-9@$]+";
    /** Похожие буквы: латиница и цифры, похожие на русские. */
    static final String V_FROM = "abcehkmnoprtuxy3046@$9", V_TO = "авсенкмпоргтихузочбася";
    /** Транслит: сначала сочетания (по порядку), потом по одной букве. */
    static final String[] P_PAIRS = {"shch", "щ", "sch", "щ", "sh", "ш", "ch", "ч", "zh", "ж", "kh", "х", "ts", "ц",
        "ya", "я", "ja", "я", "yu", "ю", "ju", "ю", "yo", "е", "jo", "е", "ye", "е", "je", "е"};
    static final String P_FROM = "abcdefghijklmnopqrstuvwxyz013469@$", P_TO = "абсдефгхийклмнопкрстуввхйзоизчбяас";
    /** Английские слова и бренды, которые транслит прочитал бы как мат (ebay, Eberspächer, Philips Hue, her). */
    static final String LAT_EXCL = "(ebay|ebb|ebm|ebon|ebook|ebola|eber|eben)|^(hues?|her|hers)$";
    static final String EN_MAT = "fuck|^fck|^fuk(ing|er|in)?$|^phuck|^motherf|^shit|^bitch|^cunt|^ashole|^dickhead|^bastard";
    static final String EXCL = "^(за|пере|под|недо|при)?страху|психу";
    static final String MAT = "хуй|хуе|хуя|хуи|хую|^хули$|пизд|пздц|^еб|^(за|на|по|вы|от|у|сь|разь|до|подь|при|про|обь|вь|изь|недо|пере|оть|взь)еб[аиулнкчшыо]"
        + "|^долбоеб|бляд|^(вы|по|за|у)?бля(т|ть|$)|^муд(ак|ил|оз|ач|ень|ло)|^пид[оа]?р|^педик(и|а|ов|у|ам)?$|залуп|^г[ао]ндон"
        + "|^манд(а|у|ы|ой|е)$|^мандавош|^шл(ю|у)х|^сук(а|и|у|ой|ин|ины|ину|ами)$|^(на|по|ни)?хер($|н|ов)|^(за|по|на|от)?дроч";
    static final String EN_RUDE = "^(dick|dicks|damn|crap|slut|whore|wtf|stfu|pis)$";
    static final String RUDE = "жоп|говн|дерьм|^сра(ть|л|ла|ли|ка|ный|ное|ная|ч)$|^(на|по|ни)?хрен|^гнид|^ублюд|^мраз(ь|и|ота)$|^твар(ь|и)$"
        + "|^урод(ы|а|у|ов|ина|ский|ская|ское|ские)?$|^дебил|^чмо(шник|шница)?$|^лох(и|а|у|ов|ам|ушка|ушки)?$|^задрот|^шалав|^педрил"
        + "|^сучар|^курв(а|ы|е|у|ой)$|^перд(еж|ет|ит|еть|ун|ак|уха)|^(за|от|вы)?трах(ать|ает|аю|ал|нуть|нул|ну|ни)";
    static final String MASK = "[а-яa-z]+\\*+[а-яa-z]+", MASK_FIRST = "^[хпбесмзxhpbes]";
    static final int MAX_LEN = 20000;

    static Pattern pLatExcl, pEnMat, pExcl, pMat, pEnRude, pRude, pMask, pMaskFirst, pInvis, pSep, pSplit, pLat, pLatOnly;

    static void compile() {
        if (pMat != null) return;
        pLatExcl = Pattern.compile(LAT_EXCL); pEnMat = Pattern.compile(EN_MAT); pExcl = Pattern.compile(EXCL);
        pEnRude = Pattern.compile(EN_RUDE); pRude = Pattern.compile(RUDE); pMask = Pattern.compile(MASK);
        pMaskFirst = Pattern.compile(MASK_FIRST); pInvis = Pattern.compile(C_INVIS); pSep = Pattern.compile(C_SEP);
        pSplit = Pattern.compile(C_SPLIT); pLat = Pattern.compile("[a-z0-9@$]"); pLatOnly = Pattern.compile("^[a-z]+$");
        pMat = Pattern.compile(MAT);
    }

    /** Что сработало: level 2 — мат, 1 — сомнительно; word — как в тексте; as — как прочитали; rule — почему. */
    static final class Hit {
        final int level; final String word, as, rule;
        Hit(int level, String word, String as, String rule) { this.level = level; this.word = word; this.as = as; this.rule = rule; }
        Hit with(String w, String r) { return new Hit(level, w, as, r); }
        @Override public String toString() { return level + ":" + word + ">" + as + ":" + rule; }
    }

    static final class Res {
        final int level; final List<Hit> hits;
        Res(int level, List<Hit> hits) { this.level = level; this.hits = hits; }
        /** Первое слово нужного уровня (для сообщения) или "". */
        String first(int lvl) { for (Hit h : hits) if (h.level == lvl) return h.word; return ""; }
    }

    static String collapse(String s) { return s.replaceAll("(.)\\1+", "$1"); }

    static String tr(String s, String from, String to) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = from.indexOf(c);
            b.append(k >= 0 ? to.charAt(k) : c);
        }
        return b.toString();
    }

    /** {похожие буквы, транслит}, оба без повторов букв (как iva_censor_vars). */
    static String[] vars(String t) {
        String v = tr(t, V_FROM, V_TO);
        String p = t;
        for (int i = 0; i < P_PAIRS.length; i += 2) p = p.replace(P_PAIRS[i], P_PAIRS[i + 1]);
        p = tr(p, P_FROM, P_TO);
        return new String[]{collapse(v), collapse(p)};
    }

    /** Одно слово (как iva_censor_tok): null — чисто. */
    static Hit tok(String t, Set<String> allow, List<String> ban) {
        compile();
        if (t == null || t.isEmpty()) return null;
        String c = collapse(t);
        if (allow.contains(t) || allow.contains(c)) return null;
        if (pLatExcl.matcher(t).find()) return null;
        boolean lat = pLat.matcher(t).find();
        if (pLatOnly.matcher(c).find() && pEnMat.matcher(c).find()) return new Hit(2, t, c, "en");
        String[] vs = vars(t);
        for (int i = 0; i < 2; i++) {
            String v = vs[i];
            if (pExcl.matcher(v).find()) continue;
            if (pMat.matcher(v).find()) return new Hit(2, t, v, !lat ? "word" : i == 0 ? "visual" : "translit");
            for (String w : ban) if (v.contains(w) || c.contains(w)) return new Hit(2, t, w, "ban");
        }
        if (pEnRude.matcher(c).find()) return new Hit(1, t, c, "en_rude");
        for (int i = 0; i < 2; i++) {
            String v = vs[i];
            if (pExcl.matcher(v).find()) continue;
            if (pRude.matcher(v).find()) return new Hit(1, t, v, "rude");
        }
        return null;
    }

    /** Подготовка: строчные, без ударений и невидимых символов, ё → е, ъ → ь. */
    static String low(String p) {
        compile();
        String s = p.length() > MAX_LEN ? p.substring(0, MAX_LEN) : p;
        s = tr(s, C_UP, C_LOW).toLowerCase(Locale.ROOT);
        s = pInvis.matcher(s).replaceAll("");
        return tr(s, "ёъ", "еь");
    }

    /** Весь текст (как public.iva_censor). */
    static Res check(String p, Set<String> allow, List<String> ban) {
        compile();
        List<Hit> hits = new ArrayList<Hit>();
        if (p == null || p.trim().isEmpty()) return new Res(0, hits);
        if (allow == null) allow = Collections.<String>emptySet();
        if (ban == null) ban = Collections.<String>emptyList();
        Set<String> words = new HashSet<String>();
        int lvl = 0;
        String lo = low(p);
        for (int pass = 1; pass <= 2; pass++) {
            String s = pSep.matcher(lo).replaceAll(pass == 1 ? "" : " ");
            List<String> toks = new ArrayList<String>(), shows = new ArrayList<String>();
            StringBuilder run = new StringBuilder(), runShow = new StringBuilder();
            for (String x : pSplit.split(s)) {
                if (x.isEmpty()) continue;
                if (x.codePointCount(0, x.length()) == 1) {
                    run.append(x);
                    if (runShow.length() > 0) runShow.append(' ');
                    runShow.append(x);
                } else {
                    if (run.length() > 0) { toks.add(run.toString()); shows.add(runShow.toString()); run.setLength(0); runShow.setLength(0); }
                    toks.add(x); shows.add(x);
                }
            }
            if (run.length() > 0) { toks.add(run.toString()); shows.add(runShow.toString()); }
            Hit prev = null;
            for (int i = 0; i < toks.size(); i++) {
                Hit h = tok(toks.get(i), allow, ban);
                if (h != null) {
                    if (!shows.get(i).equals(toks.get(i)) && h.level == 2) h = h.with(h.word, "letters");
                    h = h.with(shows.get(i), h.rule);
                    if (words.add(shows.get(i))) hits.add(h);
                    lvl = Math.max(lvl, h.level);
                } else if (i > 0 && prev == null) {
                    Hit j = tok(toks.get(i - 1) + toks.get(i), allow, ban);
                    String both = shows.get(i - 1) + " " + shows.get(i);
                    if (j != null && j.level == 2 && !words.contains(both)) {
                        hits.add(new Hit(1, both, j.as, "split"));
                        words.add(both);
                        lvl = Math.max(lvl, 1);
                    }
                }
                prev = h;
            }
        }
        Matcher m = pMask.matcher(lo);
        while (m.find()) {
            String w = m.group();
            if (pMaskFirst.matcher(w).find() && w.codePointCount(0, w.length()) >= 3 && !words.contains(w)) {
                hits.add(new Hit(1, w, w, "masked"));
                words.add(w);
                lvl = Math.max(lvl, 1);
            }
        }
        return new Res(lvl, hits);
    }

    /** Текст из файла случаев: \n — перевод строки, \\uXXXX — символ. */
    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == 'n') { b.append('\n'); i++; }
            else if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') { b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16)); i += 5; }
            else b.append(c);
        }
        return b.toString();
    }

    static String levelName(int l) { return l == 2 ? "mat" : l == 1 ? "suspect" : "ok"; }

    /** Почему сработало — по-русски (как iva_censor_reason). */
    static String why(String rule) {
        if ("word".equals(rule)) return "нецензурное слово";
        if ("visual".equals(rule)) return "нецензурное слово, написанное похожими латинскими буквами или цифрами";
        if ("translit".equals(rule)) return "нецензурное слово, написанное латиницей (транслитом)";
        if ("letters".equals(rule)) return "нецензурное слово, написанное по буквам через пробел";
        if ("ban".equals(rule)) return "слово из списка «Запрещать всегда»";
        if ("en".equals(rule)) return "английский мат";
        if ("rude".equals(rule)) return "грубое слово";
        if ("en_rude".equals(rule)) return "грубое английское слово";
        if ("split".equals(rule)) return "слово разбито пробелом — вместе похоже на мат";
        if ("masked".equals(rule)) return "звёздочки вместо букв — похоже на замаскированный мат";
        return "похоже на мат";
    }

    // ── слова создателя («Это не мат» / «Запрещать всегда») — из базы, не чаще раза в 5 минут ──
    static volatile Set<String> allowW = Collections.<String>emptySet();
    static volatile List<String> banW = Collections.<String>emptyList();
    static volatile long wordsAt = 0;
    static volatile boolean wordsBusy = false;
    static final long WORDS_EVERY_MS = 5 * 60 * 1000L;
    static final String WORDS_PATH = "/rest/v1/iva_censor_words?select=word,kind&limit=1000";

    static void parseWords(String body) throws Exception {
        JSONArray arr = new JSONArray(body.trim());
        Set<String> al = new HashSet<String>();
        List<String> bn = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String w = o.optString("word", "").trim();
            if (w.isEmpty()) continue;
            if ("allow".equals(o.optString("kind", ""))) al.add(w); else if ("ban".equals(o.optString("kind", ""))) bn.add(w);
        }
        allowW = al;
        banW = bn;
    }

    static void loadWords(final Activity a, boolean force) {
        long now = System.currentTimeMillis();
        if (wordsBusy || (!force && wordsAt > 0 && now - wordsAt < WORDS_EVERY_MS)) return;
        wordsBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, WORDS_PATH, "GET", null, false);
                    if (r != null && r[0] != null && r[0].startsWith("2") && r[1] != null) { parseWords(r[1]); wordsAt = System.currentTimeMillis(); }
                    else wordsAt = System.currentTimeMillis();                       // нет таблицы (SQL не выполнен) — не спрашиваем каждый раз
                } catch (Throwable ignore) {
                } finally { wordsBusy = false; }
            }
        }).start();
    }

    /** Проверка со словами создателя. */
    static Res check(String p) { return check(p, allowW, banW); }
    static int level(String p) { try { return check(p).level; } catch (Throwable e) { return 0; } }

    /** Текст ошибки под полем: мат — «уберите», иначе "". */
    static String fieldError(String text) {
        Res r;
        try { r = check(text); } catch (Throwable e) { return ""; }
        return r.level == 2 ? "Нецензурное слово «" + r.first(2) + "» — уберите его, пожалуйста" : "";
    }

    // ════════════════════════════════ 2. Кабинет создателя: пункт «Цензура» ════════════════════════════════

    static final String OWNER = "eclips.ru@mail.ru";
    static final String ROW_TAG = "iva_censor_row", TITLE = "Цензура";
    static final String SUB_IDLE = "Сомнительные объявления и отзывы — на проверку";
    static final String NO_DB = "Цензура заработает после обновления базы: выполните в Supabase файл 20261021_censor.sql.";
    static final String QUEUE_SEL = "select=id,created_at,source,ref_id,field,title,author_email,content,hits,reason,status,decided_at";
    static final String QUEUE_NEW = "/rest/v1/iva_censor_queue?" + QUEUE_SEL + "&status=eq.new&order=created_at.desc&limit=100";
    static final String QUEUE_DONE = "/rest/v1/iva_censor_queue?" + QUEUE_SEL + "&status=neq.new&order=decided_at.desc.nullslast&limit=60";
    static final String COUNT_PATH = "/rest/v1/iva_censor_queue?select=id&status=eq.new&limit=100";
    static final long COUNT_EVERY_MS = 60000L;
    static volatile int newCount = -1;           // -1 — неизвестно
    static volatile long countAt = 0;
    static volatile boolean countBusy = false, noDb = false;

    /** Пункт только у создателя, вошедшего в аккаунт. */
    static boolean rowFor(boolean logged, String email) {
        return logged && OWNER.equalsIgnoreCase(email == null ? "" : email.trim());
    }

    static String rowSub(int n, boolean missing) {
        if (missing) return "Нужно обновить базу (20261021_censor.sql)";
        if (n < 0) return SUB_IDLE;
        if (n == 0) return "На проверке ничего нет";
        return "На проверке: " + (n >= 100 ? "100+" : String.valueOf(n));
    }

    static boolean missingTable(String code, String body) {
        String b = body == null ? "" : body;
        return "404".equals(code) || b.contains("PGRST205") || b.contains("42P01") || b.contains("PGRST202") || b.contains("42883");
    }

    static void onTab(Activity a, String tab) {
        try { loadWords(a, false); } catch (Throwable ignore) {}
        if ("profile".equals(tab)) try { ensureRow(a); } catch (Throwable ignore) {}
    }

    static void ensureRow(final Activity a) {
        Object anchor = IvaClientPatch.get(a, "profVer");
        if (!(anchor instanceof View) || !(((View) anchor).getParent() instanceof View)) return;
        View ver = (View) ((View) anchor).getParent();
        if (!(ver.getParent() instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) ver.getParent();
        boolean want = rowFor(IvaNotifyPatch.loggedIn(a), String.valueOf(IvaClientPatch.get(a, "userEmail")));
        View row = box.findViewWithTag(ROW_TAG);
        if (row == null) {
            if (!want) return;
            row = rowView(a);
            View upd = box.findViewWithTag(IvaNotifyPatch.UPDATE_BTN_TAG);
            int at = box.indexOfChild(upd != null && upd.getParent() == box ? upd : ver);
            if (at < 0) return;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 12);
            box.addView(row, at, lp);
        }
        row.setVisibility(want ? View.VISIBLE : View.GONE);
        if (want) { paintRow(a); countCheck(a, false); }
    }

    static View rowView(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) row.setBackground(bg);
        row.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
        row.setClickable(true);
        TextView ic = new TextView(a);                                     // значок: щит с восклицательным знаком
        ic.setText("!");
        ic.setGravity(Gravity.CENTER);
        ic.setTextSize(13f);
        ic.setTypeface(null, Typeface.BOLD);
        ic.setTextColor(color(a, "orange", 0xffff8f1f));                   // 10.96: кружок «рамкой», а не заливкой
        ic.setBackground(IvaPatch.accentBg(a, color(a, "orange", 0xffff8f1f), -1));
        row.addView(ic, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(TITLE);
        t.setTextSize(15f);
        t.setTextColor(color(a, "text", 0xfff2f4f8));
        col.addView(t);
        TextView s = new TextView(a);
        s.setTag(ROW_TAG + ":sub");
        s.setText(SUB_IDLE);
        s.setTextSize(13f);
        s.setTextColor(color(a, "gray", 0xff9aa1b0));
        col.addView(s);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 14);
        row.addView(col, cl);
        ImageView chev = new ImageView(a);
        Drawable ch = IvaSearchPatch.drawable(a, "ic_chevron");
        if (ch != null) {
            chev.setImageDrawable(ch);
            chev.setColorFilter(color(a, "gray", 0xff9aa1b0));
            row.addView(chev, new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18)));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { open(a); } catch (Throwable ignore) {} }
        });
        return row;
    }

    static void paintRow(Activity a) {
        try {
            View sub = a.getWindow().getDecorView().findViewWithTag(ROW_TAG + ":sub");
            if (!(sub instanceof TextView)) return;
            ((TextView) sub).setText(rowSub(newCount, noDb));
            ((TextView) sub).setTextColor(newCount > 0 ? color(a, "orange", 0xffff8f1f) : color(a, "gray", 0xff9aa1b0));
        } catch (Throwable ignore) {}
    }

    static void countCheck(final Activity a, boolean force) {
        long now = System.currentTimeMillis();
        if (countBusy || (!force && countAt > 0 && now - countAt < COUNT_EVERY_MS)) return;
        countBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, COUNT_PATH, "GET", null, true);
                    if (r != null && r[0] != null && r[0].startsWith("2")) { newCount = new JSONArray(r[1].trim()).length(); noDb = false; }
                    else if (r != null && missingTable(r[0], r[1])) noDb = true;
                    countAt = System.currentTimeMillis();
                } catch (Throwable ignore) {
                } finally { countBusy = false; }
                h().post(new Runnable() { @Override public void run() { paintRow(a); } });
            }
        }).start();
    }

    // ════════════════════════════════ 3. Страница «Цензура» ════════════════════════════════

    static final String[] TABS = {"На проверке", "Решённые", "Слова"};
    static Dialog page;
    static LinearLayout pageList, pageTabs;
    static int pageTab = 0;
    static List<JSONObject> rowsNew = new ArrayList<JSONObject>(), rowsDone = new ArrayList<JSONObject>();
    static volatile boolean loading = false, loadFailed = false;
    static Handler h;
    static Handler h() { if (h == null) h = new Handler(Looper.getMainLooper()); return h; }
    static int dp(Activity a, float v) { return IvaSearchPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }

    static String sourceLabel(String source, String field) {
        String s = "tool".equals(source) ? "Объявление" : "review".equals(source) ? "Отзыв" : "Текст";
        String f = "name".equals(field) ? "название" : "descr".equals(field) ? "описание" : "terms".equals(field) ? "условия аренды"
            : "text".equals(field) ? "текст отзыва" : "author_name".equals(field) ? "имя автора" : field;
        return s + " · " + f;
    }

    static String statusLabel(String st) {
        if ("kept".equals(st)) return "Оставлено";
        if ("hidden".equals(st)) return "Скрыто";
        if ("fixed".equals(st)) return "Автор исправил текст";
        return "Ждёт решения";
    }

    /** «15.10 14:20» из ISO-времени базы (в часовом поясе телефона); не разобрали — как есть. */
    static String when(String iso) {
        if (iso == null || iso.length() < 16) return iso == null ? "" : iso;
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = in.parse(iso.substring(0, 19));
            return new java.text.SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(d);
        } catch (Throwable e) { return iso.substring(8, 10) + "." + iso.substring(5, 7) + " " + iso.substring(11, 16); }
    }

    /** Где в тексте подсветить слово (без учёта регистра); слова с пробелами — как есть. Нет — -1. */
    static int findWord(String content, String word) {
        if (content == null || word == null || word.isEmpty()) return -1;
        return content.toLowerCase(Locale.ROOT).replace('ё', 'е').indexOf(word.toLowerCase(Locale.ROOT));
    }

    static void open(final Activity a) {
        if (page != null && page.isShowing()) return;
        int cBg = color(a, "bg", 0xff101217);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        TextView back = new TextView(a);
        back.setText("‹ Назад");
        back.setTextColor(color(a, "orange", 0xffff8f1f));
        back.setTextSize(15f);
        back.setTypeface(null, Typeface.BOLD);
        back.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 8));
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = new TextView(a);
        title.setText(TITLE);
        title.setTextColor(color(a, "text", 0xfff2f4f8));
        title.setTextSize(21f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        root.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView sub = new TextView(a);
        sub.setText("Явный мат не сохраняется вовсе. Сомнительное не видно в каталоге, пока вы не решите.");
        sub.setTextColor(color(a, "gray2", 0xff6b7080));
        sub.setTextSize(12f);
        sub.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 8));
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 4));
        hs.addView(tabs, new FrameLayout.LayoutParams(-2, -2));
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        ScrollView sc = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 24));
        sc.addView(list, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(cBg));
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            try {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.setStatusBarColor(cBg);
                w.setNavigationBarColor(cBg);
            } catch (Throwable ignore) {}
        }
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface x) {
                if (page == d) { page = null; pageList = null; pageTabs = null; }
                try { countCheck(a, true); } catch (Throwable ignore) {}
            }
        });
        page = d;
        pageList = list;
        pageTabs = tabs;
        render(a);
        d.show();
        fetch(a);
        loadWords(a, true);
    }

    static void paintTabs(final Activity a) {
        LinearLayout tabs = pageTabs;
        if (tabs == null) return;
        tabs.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            String label = TABS[i] + (i == 0 && !rowsNew.isEmpty() ? " · " + rowsNew.size() : "");
            TextView c = IvaSearchPatch.chip(a, label, i == pageTab, false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pageTab = k; render(a); if (k < 2) fetch(a); else loadWords(a, true); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(a, 8);
            tabs.addView(c, lp);
        }
    }

    static TextView hint(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14f);
        t.setTextColor(color(a, "gray", 0xff9aa1b0));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 8), dp(a, 32), dp(a, 8), dp(a, 32));
        return t;
    }

    static void render(Activity a) {
        LinearLayout list = pageList;
        if (list == null || page == null || !page.isShowing()) return;
        paintTabs(a);
        list.removeAllViews();
        if (noDb) { list.addView(hint(a, NO_DB)); return; }
        if (pageTab == 2) { renderWords(a, list); return; }
        List<JSONObject> rows = pageTab == 0 ? rowsNew : rowsDone;
        if (rows.isEmpty()) {
            list.addView(hint(a, loading ? "Загрузка…" : loadFailed ? "Не удалось загрузить — проверьте интернет"
                : pageTab == 0 ? "На проверке ничего нет. Всё сомнительное появится здесь." : "Решённых пока нет."));
            return;
        }
        for (JSONObject r : rows) list.addView(card(a, r));
    }

    static void fetch(final Activity a) {
        if (loading) return;
        loading = true;
        loadFailed = false;
        new Thread(new Runnable() {
            @Override public void run() {
                List<JSONObject> nw = null, dn = null;
                boolean missing = false;
                try {
                    String[] r1 = IvaClientPatch.apiCall(a, QUEUE_NEW, "GET", null, true);
                    if (r1 != null && r1[0] != null && r1[0].startsWith("2")) nw = rows(r1[1]);
                    else if (r1 != null && missingTable(r1[0], r1[1])) missing = true;
                    if (!missing) {
                        String[] r2 = IvaClientPatch.apiCall(a, QUEUE_DONE, "GET", null, true);
                        if (r2 != null && r2[0] != null && r2[0].startsWith("2")) dn = rows(r2[1]);
                    }
                } catch (Throwable ignore) {}
                final List<JSONObject> fn = nw, fd = dn;
                final boolean fm = missing;
                h().post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        noDb = fm;
                        if (fn != null) { rowsNew = fn; newCount = fn.size(); countAt = System.currentTimeMillis(); }
                        if (fd != null) rowsDone = fd;
                        loadFailed = !fm && fn == null;
                        render(a);
                        paintRow(a);
                    }
                });
            }
        }).start();
    }

    static List<JSONObject> rows(String body) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray arr = new JSONArray(body == null ? "[]" : body.trim());
        for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.optJSONObject(i); if (o != null) out.add(o); }
        return out;
    }

    static String s(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, ""); }

    static TextView small(Activity a, String s, int col, float size) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(col);
        return t;
    }

    static View card(final Activity a, final JSONObject r) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) c.setBackground(bg);
        c.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 14));
        final String st = s(r, "status");
        c.addView(small(a, sourceLabel(s(r, "source"), s(r, "field")) + " · " + when(s(r, "created_at")), cOrange, 12f));
        TextView t = small(a, s(r, "title").isEmpty() ? "Без названия" : s(r, "title"), cText, 16f);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(a, 4), 0, 0);
        c.addView(t);
        if (!s(r, "author_email").isEmpty()) c.addView(small(a, "Автор: " + s(r, "author_email"), cGray, 13f));
        // весь текст, слова подсвечены
        String content = s(r, "content");
        JSONArray hits = r.optJSONArray("hits");
        SpannableStringBuilder sp = new SpannableStringBuilder(content);
        if (hits != null) for (int i = 0; i < hits.length(); i++) {
            JSONObject hh = hits.optJSONObject(i);
            int at = findWord(content, s(hh, "word"));
            if (at < 0) continue;
            int end = Math.min(content.length(), at + s(hh, "word").length());
            sp.setSpan(new BackgroundColorSpan(0x55ff5a5f), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(0xffffffff), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView body = new TextView(a);
        body.setText(sp);
        body.setTextSize(14f);
        body.setTextColor(cText);
        body.setTextIsSelectable(true);
        GradientDrawable box = new GradientDrawable();
        box.setColor(color(a, "bg", 0xff101217));
        box.setCornerRadius(dp(a, 10));
        body.setBackground(box);
        body.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(a, 8);
        c.addView(body, bl);
        TextView why = small(a, "Почему:\n" + s(r, "reason"), cGray, 13f);
        why.setPadding(0, dp(a, 8), 0, 0);
        c.addView(why);
        if ("new".equals(st)) {
            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            TextView keep = IvaSearchPatch.chip(a, "Оставить", true, false);
            TextView hide = IvaSearchPatch.chip(a, "Скрыть", false, false);
            keep.setContentDescription("Оставить: показать как есть");
            hide.setContentDescription("Скрыть: не показывать");
            keep.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { decide(a, r, true); } });
            hide.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { decide(a, r, false); } });
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-2, -2);
            kl.rightMargin = dp(a, 8);
            btns.addView(keep, kl);
            btns.addView(hide, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-1, -2);
            btl.topMargin = dp(a, 12);
            c.addView(btns, btl);
        } else {
            TextView done = small(a, statusLabel(st) + (s(r, "decided_at").isEmpty() ? "" : " · " + when(s(r, "decided_at"))),
                "kept".equals(st) ? color(a, "green", 0xff3ecf8e) : cGray, 13f);
            done.setTypeface(null, Typeface.BOLD);
            done.setPadding(0, dp(a, 10), 0, 0);
            c.addView(done);
        }
        // слова: «Это не мат» / «Запрещать всегда»
        if (hits != null) for (int i = 0; i < hits.length(); i++) {
            JSONObject hh = hits.optJSONObject(i);
            final String word = s(hh, "word").replace(" ", "");
            if (word.length() < 2) continue;
            LinearLayout wr = new LinearLayout(a);
            wr.setOrientation(LinearLayout.HORIZONTAL);
            wr.setGravity(Gravity.CENTER_VERTICAL);
            TextView wl = small(a, "«" + s(hh, "word") + "»", cText, 13f);
            LinearLayout.LayoutParams wll = new LinearLayout.LayoutParams(0, -2, 1f);
            wr.addView(wl, wll);
            TextView ok = IvaSearchPatch.chip(a, "Это не мат", false, false);
            TextView ban = IvaSearchPatch.chip(a, "Запрещать всегда", false, false);
            ok.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setWord(a, word, "allow"); } });
            ban.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setWord(a, word, "ban"); } });
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
            ol.rightMargin = dp(a, 6);
            wr.addView(ok, ol);
            wr.addView(ban, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams wrl = new LinearLayout.LayoutParams(-1, -2);
            wrl.topMargin = dp(a, 8);
            c.addView(wr, wrl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.bottomMargin = dp(a, 12);
        c.setLayoutParams(cl);
        return c;
    }

    static String decideBody(long id, boolean keep) { return "{\"p_id\":" + id + ",\"p_keep\":" + keep + "}"; }

    static String wordBody(String word, String kind) {
        return "{\"p_word\":" + IvaNotifyPatch.jsonStr(word) + ",\"p_kind\":" + IvaNotifyPatch.jsonStr(kind) + "}";
    }

    static void toast(Activity a, String s) { try { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {} }

    static void decide(final Activity a, final JSONObject r, final boolean keep) {
        final long id = r.optLong("id", 0);
        if (id <= 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String[] res = null;
                try { res = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_censor_decide", "POST", decideBody(id, keep), true); } catch (Throwable ignore) {}
                final boolean ok = res != null && res[0] != null && res[0].startsWith("2");
                final String body = res == null ? "" : String.valueOf(res[1]);
                h().post(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            toast(a, keep ? ("tool".equals(s(r, "source")) ? "Оставлено — объявление снова в каталоге" : "Оставлено — отзыв опубликован")
                                : ("tool".equals(s(r, "source")) ? "Скрыто — объявления нет в каталоге" : "Скрыто — отзыв не виден"));
                        } else toast(a, body.contains("already decided") ? "Уже решено" : "Не удалось — проверьте интернет");
                        fetch(a);
                    }
                });
            }
        }).start();
    }

    static void setWord(final Activity a, final String word, final String kind) {
        new Thread(new Runnable() {
            @Override public void run() {
                String[] res = null;
                try { res = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_censor_word", "POST", wordBody(word, kind), true); } catch (Throwable ignore) {}
                final boolean ok = res != null && res[0] != null && res[0].startsWith("2");
                final String body = res == null ? "" : String.valueOf(res[1]);
                h().post(new Runnable() {
                    @Override public void run() {
                        if (ok) toast(a, "allow".equals(kind) ? "«" + word + "» — больше не считается матом"
                            : "ban".equals(kind) ? "«" + word + "» — теперь запрещено всегда" : "«" + word + "» убрано из списков");
                        else toast(a, body.contains("от 2 до 40") ? "Слово — от 2 до 40 букв" : "Не удалось — проверьте интернет");
                        loadWords(a, true);
                        h().postDelayed(new Runnable() { @Override public void run() { render(a); } }, 800);
                    }
                });
            }
        }).start();
    }

    static void renderWords(final Activity a, LinearLayout list) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        list.addView(small(a, "Слово из списка «Это не мат» никогда не считается матом. Слово из «Запрещать всегда» "
            + "не сохраняется нигде — ни в объявлениях, ни в отзывах, ни в чате.", cGray, 13f));
        final EditText in = new EditText(a);
        in.setSingleLine(true);
        in.setHint("Слово");
        in.setTextColor(cText);
        in.setHintTextColor(color(a, "gray2", 0xff6b7080));
        in.setTextSize(15f);
        Drawable fb = IvaSearchPatch.drawable(a, "bg_field");
        if (fb != null) in.setBackground(fb);
        in.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = dp(a, 12);
        list.addView(in, il);
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView ok = IvaSearchPatch.chip(a, "Это не мат", false, false);
        TextView ban = IvaSearchPatch.chip(a, "Запрещать всегда", true, false);
        ok.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            String w = String.valueOf(in.getText()).trim(); if (!w.isEmpty()) { setWord(a, w, "allow"); in.setText(""); } } });
        ban.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            String w = String.valueOf(in.getText()).trim(); if (!w.isEmpty()) { setWord(a, w, "ban"); in.setText(""); } } });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
        ol.rightMargin = dp(a, 8);
        btns.addView(ok, ol);
        btns.addView(ban, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(a, 8);
        list.addView(btns, bl);
        wordList(a, list, "Запрещать всегда", new ArrayList<String>(banW));
        wordList(a, list, "Это не мат", new ArrayList<String>(allowW));
    }

    static void wordList(final Activity a, LinearLayout list, String title, List<String> words) {
        Collections.sort(words);
        TextView t = small(a, title + (words.isEmpty() ? " — пусто" : " · " + words.size()), color(a, "text", 0xfff2f4f8), 15f);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(a, 20), 0, dp(a, 4));
        list.addView(t);
        for (final String w : words) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 4), 0, dp(a, 4));
            row.addView(small(a, w, color(a, "text", 0xfff2f4f8), 14f), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView rm = IvaSearchPatch.chip(a, "Убрать", false, false);
            rm.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setWord(a, w, "none"); } });
            row.addView(rm, new LinearLayout.LayoutParams(-2, -2));
            list.addView(row);
        }
    }

    // ════════════════════════════════ 3. Этап 2 (24.10.2026): остальные поля ввода ════════════════════════════════
    //  Та же проверка (мат — нельзя отправить) теперь и там, где раньше её не было: форма «Изменить инструмент»
    //  (название, описание, условия, «Модель», характеристики), «Модель» и характеристики в «Добавить», заявка
    //  (ФИО, адрес, комментарий), причина отмены заявки, «Профиль» клиента, «Мои данные» админа, имя при регистрации,
    //  ФИО сотрудника; «Предложить модель» с матом не предлагается. Поиск и фильтры не проверяются: их никто не видит.
    //  Новых вставок в smali нет: штатные кнопки «Сохранить» / «Зарегистрироваться» — свой OnTouchListener (как 10.87),
    //  остальное — в своём коде. Решает всё равно база (триггеры 20261021_censor.sql на объявлениях, отзывах, чате).

    /** ФИО и имена людей: проверять ли (решение владельца). Настоящая фамилия может совпасть со словом из списка —
     *  создатель снимает такое слово кнопкой «Это не мат» в «Цензуре». */
    static boolean NAMES = true;
    static final String NAME_TAIL = " Если это ваша настоящая фамилия — напишите в «Чат поддержки».";

    /** Ошибка для поля с именем человека: как fieldError, с подсказкой про настоящую фамилию; NAMES = false — "". */
    static String nameError(String text) {
        if (!NAMES) return "";
        String e = fieldError(text);
        return e.isEmpty() ? "" : e + NAME_TAIL;
    }

    /** Мат в поле — ошибка у поля, фокус, прокрутка к нему, всплывающее сообщение. true — отправку не пускаем. */
    static boolean stopField(Activity a, Object o) {
        if (!(o instanceof TextView)) return false;
        TextView f = (TextView) o;
        if (f.getVisibility() != View.VISIBLE) return false;
        String e = names.containsKey(f) ? nameError(String.valueOf(f.getText())) : fieldError(String.valueOf(f.getText()));
        if (e.isEmpty()) return false;
        try { f.setError(e); f.requestFocus(); } catch (Throwable ignore) {}
        try { IvaFormPatch.scrollTo(a, f); } catch (Throwable ignore) {}
        try { IvaNotifyPatch.flash(f); } catch (Throwable ignore) {}
        toast(a, e);
        return true;
    }

    /** Поля с именем человека (ФИО, имя при регистрации) — для nameError. */
    static final java.util.Map<View, Boolean> names = new java.util.WeakHashMap<View, Boolean>();
    static <T extends View> T asName(T v) { if (v != null) names.put(v, Boolean.TRUE); return v; }

    static boolean stopFields(Activity a, Object... fs) {
        for (Object f : fs) if (stopField(a, f)) return true;
        return false;
    }

    /** Все видимые поля ввода внутри root (форма целиком: «Модель», характеристики и т. п.). */
    static boolean stopTree(Activity a, View root) {
        List<EditText> es = new ArrayList<EditText>();
        edits(root, es);
        for (EditText e : es) if (e.isShown() && stopField(a, e)) return true;
        return false;
    }

    static void edits(View v, List<EditText> out) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof EditText) { out.add((EditText) v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) edits(g.getChildAt(i), out);
        }
    }

    static final java.util.Map<View, Boolean> guarded = new java.util.WeakHashMap<View, Boolean>();

    /** Штатная кнопка: касание при мате в полях не пропускаем (как «Опубликовать» в 10.87). check — true: остановить. */
    interface Check { boolean stop(); }

    static void guardButton(View b, final Check c) {
        if (b == null || guarded.containsKey(b)) return;
        guarded.put(b, Boolean.TRUE);
        b.setOnTouchListener(new View.OnTouchListener() {
            boolean blocked = false;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    blocked = false;
                    if (v.isEnabled()) try { blocked = c.stop(); } catch (Throwable ignore) { blocked = false; }
                }
                return blocked;
            }
        });
    }

    /** Из IvaPatch.onCreated / onTab: регистрация (имя) и «Мои данные» админа (ФИО). */
    static void guardStock(final Activity a) {
        View submit = IvaPatch.view(a, "btnSubmit");
        Object rn = IvaClientPatch.get(a, "rName");
        if (rn instanceof View) asName((View) rn);
        guardButton(submit, new Check() {
            @Override public boolean stop() {
                View box = IvaPatch.view(a, "fieldName");                  // поле имени видно только на «Регистрации»
                return box != null && box.isShown() && stopField(a, IvaClientPatch.get(a, "rName"));
            }
        });
        guardButton(IvaPatch.view(a, "adSave"), new Check() {
            @Override public boolean stop() { return stopTree(a, IvaPatch.view(a, "pageAdmData")); }
        });
    }
}

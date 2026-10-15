package ru.prokatnvsk.app;

// ───────────── 10.70: отзывы и оценки, этап 1 (решения владельца 14.10.2026) ─────────────
// «Аккуратно вмонтировать систему отзывов и оценок, чтобы ничего не испортить». Те же отзывы, что на сайте:
// таблица reviews, tools.rating / tools.reviews_count (пересчитывает триггер базы), RPC can_user_review,
// submit_review, toggle_review_status, get_landlord_rating (миграция 20260924_reviews.sql). База и сайт не меняются.
//  • лента — 10.71: на картинке справа внизу «★ 4,8», под ценой «12 отзывов» / «Нет отзывов» (карточки в ряду одной высоты);
//  • страница инструмента — под названием «★ 4,8 · 12 отзывов», внизу блок «Отзывы»: свой отзыв (изменить),
//    «Оставить отзыв» (право — как на сайте: can_user_review), список; главному админу — «Скрыть спам» / «Восстановить»;
//  • страница проката — вместо «Отзывы: пока нет» общий рейтинг проката (если отзывы есть);
//  • закрытая заявка — в шторке подробностей «Оставить отзыв» по каждому инструменту заявки.
// Без новых вставок в smali: страница инструмента и проката — из IvaPatch.onTab (штатный showTab("detail"/"seller")),
// лента — после перерисовки (слежение за feedGrid в IvaSearchPatch), заявка — из шторки IvaOrderPatch.ordOpenRow.
// Персональные данные: e-mail авторов не запрашивается (только имя, как его показывает сайт).
// Любая ошибка — блок просто не появляется, штатные экраны работают как раньше.

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IvaReviewPatch {
    private IvaReviewPatch() {}

    static final String CARD_TAG = "iva_rv_card", DET_LINE_TAG = "iva_rv_det_line", DET_BLOCK_TAG = "iva_rv_det_block";
    static final String ORD_BLOCK_TAG = "iva_rv_ord";
    static final String CHIEF_EMAIL = "eclips.ru@mail.ru";   // главный админ — как в штатном коде (checkSelfAdmin, openDetail)
    static final int MAX_TEXT = 1000, LIST_FIRST = 5;
    static final long DET_FRESH_MS = 30L * 1000;
    // Колонки отзыва — без e-mail автора (на экране только имя, как на сайте).
    static final String REVIEW_COLS = "id,tool_id,author_id,author_name,rating,text,status,created_at,updated_at";
    static final String NO_REVIEWS = "Отзывов пока нет";
    static final String EMPTY_LIST = "Пока никто не оставил отзыв на этот инструмент. Будьте первыми!";
    static final String HINT_GUEST = "Оставить отзыв могут арендаторы после оформления заявки на инструмент. Войдите в аккаунт, если вы уже брали этот инструмент.";
    static final String HINT_NO_ORDER = "Отзыв могут оставить арендаторы после оформления заявки на этот инструмент.";
    static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
        "сентября", "октября", "ноября", "декабря"};

    // ═════════════════════════════ чистые функции (проверяются в IvaPatchTest) ═════════════════════════════

    static String fmtRating(double r) { return String.format(Locale.ROOT, "%.1f", r).replace('.', ','); }

    static String plural(int n, String one, String few, String many) {
        int m10 = Math.abs(n) % 10, m100 = Math.abs(n) % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    static String reviewsWord(int n) { return plural(n, "отзыв", "отзыва", "отзывов"); }

    /** Карточка ленты: «★ 4,8 (12)»; отзывов нет — пусто (строка не показывается). */
    static String cardLine(double rating, int count) {
        if (count <= 0 || !(rating > 0)) return "";
        return "★ " + fmtRating(rating) + " (" + count + ")";
    }

    // 10.71 (просьба владельца 14.10): в ленте рейтинг — на картинке справа внизу, под ценой — «12 отзывов» / «Нет отзывов»
    // у каждой карточки (карточки в ряду одной высоты, ряды не «сползают»).
    static final String NO_REVIEWS_CARD = "Нет отзывов";

    /** На картинке: «★ 4,8»; отзывов нет — пусто (плашки нет). */
    static String badgeText(double rating, int count) {
        if (count <= 0 || !(rating > 0)) return "";
        return "★ " + fmtRating(rating);
    }

    /** Под ценой: «12 отзывов» / «Нет отзывов». */
    static String countLine(int count) { return count <= 0 ? NO_REVIEWS_CARD : count + " " + reviewsWord(count); }

    /** Ключ карточки для выбора среди одинаковых названий: «число|рейтинг» (без отзывов — «0|»). */
    static String feedKey(double rating, int count) {
        return count <= 0 || !(rating > 0) ? "0|" : count + "|" + fmtRating(rating);
    }

    /** {плашка на картинке, строка под ценой} по ключу; ключа нет (не узнали карточку) — null. */
    static String[] feedTexts(String key) {
        if (key == null || key.isEmpty() || key.indexOf('|') < 0) return null;
        int n;
        try { n = Integer.parseInt(key.substring(0, key.indexOf('|'))); } catch (Throwable e) { return null; }
        String r = key.substring(key.indexOf('|') + 1);
        return new String[]{n <= 0 || r.isEmpty() ? "" : "★ " + r, countLine(n)};
    }

    // ───────────── 10.71: нецензурные слова в отзывах запрещены (решение владельца 14.10.2026) ─────────────
    // Та же проверка — в базе (триггер tr_reviews_no_mat, supabase/migrations/20261014_reviews_no_mat.sql: iva_has_mat),
    // выражения в приложении и в SQL одинаковые (сторож сверяет). Текст: строчные, ё→е, ъ→ь, похожие латинские буквы и
    // цифры → русские, точки/дефисы/звёздочки внутри слова убираются, слово — без повторов букв («сууука» → «сука»).
    // «Скрыть спам» у главного админа — только у отзывов с такими словами (у скрытых — «Восстановить»).
    static final String MAT_FROM = "ёъaceopxykmt306@", MAT_TO = "еьасеорхукмтзоба";
    static final String MAT_STRIP = "[._*'`~-]", MAT_SPLIT = "[^а-яa-z0-9]+";
    static final String MAT_ALLOW = "^(за|пере|под|недо|при)?страху|психу";
    static final String MAT_RE = "хуй|хуе|хуя|хуи|хую|^хули$|пизд|пздц|^еб|^(за|на|по|вы|от|у|сь|разь|до|подь|при|про|обь|вь|изь|недо|пере|оть|взь)еб[аиулнкчшыо]"
        + "|^долбоеб|^(вы|по|за|у)?бля(д|т|$)|^муд(ак|ил|оз|ач|ень|ло)|^пид[оа]?р|^педик(и|а|ов|у|ам)?$|залуп|^г[ао]ндон"
        + "|^манд(а|у|ы|ой|е)$|^мандавош|^шлюх|^сук(а|и|у|ой|ин|ины|ину|ами)$|^(на|по|ни)?хер($|н|ов)|^(за|по|на|от)?дроч";
    static final String MAT_MSG = "В отзыве есть нецензурные слова. Уберите их, пожалуйста.";
    static final String MAT_NAME_MSG = "В имени есть нецензурные слова. Измените имя в профиле.";
    static final String HELD_MSG = "Спасибо! В отзыве есть сомнительные слова — он появится после проверки.";   // 10.92
    static java.util.regex.Pattern matRe, matAllow;

    static String matNorm(String s) {
        String t = s.toLowerCase(new Locale("ru"));
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            int k = MAT_FROM.indexOf(ch);
            b.append(k >= 0 ? MAT_TO.charAt(k) : ch);
        }
        return b.toString().replaceAll(MAT_STRIP, "");
    }

    /** Есть ли в тексте нецензурные слова (как iva_has_mat в базе). 10.92: та же проверка, что iva_censor
     *  (латиница, транслит, буквы через пробел, слова создателя) — IvaCensorPatch; прежняя — запасная. */
    static boolean hasMat(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        try { if (IvaCensorPatch.check(s).level == 2) return true; } catch (Throwable ignore) {}
        if (matRe == null) { matRe = java.util.regex.Pattern.compile(MAT_RE); matAllow = java.util.regex.Pattern.compile(MAT_ALLOW); }
        for (String tok : matNorm(s).split(MAT_SPLIT)) {
            if (tok.isEmpty()) continue;
            String t = tok.replaceAll("(.)\\1+", "$1");
            if (matAllow.matcher(t).find()) continue;
            if (matRe.matcher(t).find()) return true;
        }
        return false;
    }

    /** Кнопка модерации: null — нет; "hidden" — «Скрыть спам» (только с матом); "published" — «Восстановить». */
    static String modAction(boolean chief, boolean hidden, boolean mat) {
        if (!chief) return null;
        if (hidden) return "published";
        return mat ? "hidden" : null;
    }

    /** Страница инструмента, под названием: «★ 4,8 · 12 отзывов» / «Отзывов пока нет». */
    static String summaryLine(double rating, int count) {
        if (count <= 0 || !(rating > 0)) return NO_REVIEWS;
        return "★ " + fmtRating(rating) + " · " + count + " " + reviewsWord(count);
    }

    /** Страница проката: рейтинг проката или null (отзывов нет — штатный текст остаётся). */
    static String sellerLine(double rating, int count) {
        if (count <= 0 || !(rating > 0)) return null;
        return "★ " + fmtRating(rating) + " · " + count + " " + reviewsWord(count) + " об инструментах проката";
    }

    /** Штатный текст «Отзывы: пока нет» (и «… · рейтинг не сформирован» из фоновой загрузки). */
    static boolean nativeNoReviews(String t) { return t != null && t.startsWith("Отзывы: пока нет"); }

    static String val(String v) { return v == null || "null".equals(v.trim()) ? "" : v.trim(); }

    static int ratingOf(Map<String, String> r) {
        try { return Math.max(0, Math.min(5, Integer.parseInt(val(r.get("rating"))))); } catch (Throwable e) { return 0; }
    }

    static boolean published(Map<String, String> r) { return "published".equals(val(r.get("status"))); }

    /** Средняя и число — только опубликованные (как сводка сайта и триггер базы). {средняя, число}. */
    static double[] summary(List<Map<String, String>> rs) {
        int n = 0, sum = 0;
        if (rs != null) for (Map<String, String> r : rs) {
            int v = ratingOf(r);
            if (!published(r) || v <= 0) continue;
            n++; sum += v;
        }
        return new double[]{n == 0 ? 0 : Math.round(sum * 10.0 / n) / 10.0, n};
    }

    /** Что в списке: опубликованные (главному админу — все); свой отзыв — отдельно над списком. */
    static List<Map<String, String>> visible(List<Map<String, String>> rs, boolean chief, String myId) {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        if (rs == null) return out;
        String my = val(myId);
        for (Map<String, String> r : rs) {
            if (!my.isEmpty() && my.equals(val(r.get("id")))) continue;
            if (published(r) || chief) out.add(r);
        }
        return out;
    }

    static String stars(int n) {
        n = Math.max(0, Math.min(5, n));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) b.append(i < n ? '★' : '☆');
        return b.toString();
    }

    /** «2026-10-14T…» → «14 октября 2026». */
    static String fmtDate(String iso) {
        String s = val(iso);
        if (s.length() < 10) return s;
        try {
            int y = Integer.parseInt(s.substring(0, 4)), m = Integer.parseInt(s.substring(5, 7)), d = Integer.parseInt(s.substring(8, 10));
            if (m < 1 || m > 12) return s.substring(0, 10);
            return d + " " + MONTHS[m - 1] + " " + y;
        } catch (Throwable e) { return s.substring(0, 10); }
    }

    /** Дата отзыва, как на сайте: изменения — по дате изменения и «(изменён)». */
    static String dateLine(Map<String, String> r) {
        String c = val(r.get("created_at")), u = val(r.get("updated_at"));
        boolean edited = !u.isEmpty() && !c.isEmpty() && !u.equals(c);
        return fmtDate(u.isEmpty() ? c : u) + (edited ? " (изменён)" : "");
    }

    /** Подсказка вместо кнопки: null — показать кнопку / свой отзыв; "" — ничего (свой инструмент). */
    static String formHint(boolean logged, boolean loaded, boolean allowed, String reason) {
        if (!logged) return HINT_GUEST;
        if (!loaded) return "";
        if ("own_tool".equals(reason)) return "";
        if (!allowed) return HINT_NO_ORDER;
        return null;
    }

    /** То же для шторки заявки (всплывающее сообщение). */
    static String reasonText(String reason) {
        if ("own_tool".equals(reason)) return "Нельзя оставить отзыв на собственный инструмент";
        if ("tool_not_found".equals(reason)) return "Инструмент больше не опубликован — отзыв оставить нельзя";
        if ("auth_required".equals(reason)) return "Войдите в аккаунт, чтобы оставить отзыв";
        return HINT_NO_ORDER;
    }

    /** Название на карточке ленты — как штатный shorten(name, 34). */
    static String shorten34(String s) {
        if (s == null) return "";
        return s.length() <= 34 ? s : s.substring(0, 33) + "…";
    }

    /** Строка для карточки среди инструментов с тем же названием: {текст цены, строка}. Неоднозначно — "". */
    static String chooseLine(List<String[]> cands, String cardPrice) {
        if (cands == null || cands.isEmpty()) return "";
        String first = cands.get(0)[1];
        boolean same = true;
        for (String[] c : cands) if (!c[1].equals(first)) same = false;
        if (same) return first;
        String found = null;
        for (String[] c : cands) {
            if (!c[0].equals(cardPrice)) continue;
            if (found != null && !found.equals(c[1])) return "";
            found = c[1];
        }
        return found == null ? "" : found;
    }

    /** Сообщение базы из ответа ({"message":"…"}); нет — общее. */
    static String errMessage(String code, String body) {
        if ("-1".equals(code)) return "нет связи с сервером";
        String b = body == null ? "" : body;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(b);
        if (m.find()) {
            String s = m.group(1);
            StringBuilder o = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\\' && i + 1 < s.length()) {
                    char nx = s.charAt(++i);
                    if (nx == 'u' && i + 4 < s.length()) {
                        try { o.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; continue; } catch (Throwable ignore) {}
                    }
                    o.append(nx == 'n' ? ' ' : nx);
                } else o.append(ch);
            }
            String t = o.toString().trim();
            if (!t.isEmpty()) return t;
        }
        return "ошибка сервера (" + code + ")";
    }

    static String submitBody(String toolId, int rating, String text, String name) {
        return "{\"p_tool_id\":" + IvaNotifyPatch.jsonStr(toolId) + ",\"p_rating\":" + Math.max(1, Math.min(5, rating))
            + ",\"p_text\":" + IvaNotifyPatch.jsonStr(text == null ? "" : text.trim()) + ",\"p_author_name\":" + IvaNotifyPatch.jsonStr(val(name)) + "}";
    }

    static boolean ok(String[] r) { return r != null && r.length > 1 && r[0] != null && r[0].startsWith("2"); }

    // ═════════════════════════════ общее ═════════════════════════════

    static int dp(Activity a, float v) { return IvaSearchPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }
    static Drawable drawable(Activity a, String n) { return IvaSearchPatch.drawable(a, n); }
    static Object get(Object o, String f) { return IvaClientPatch.get(o, f); }
    static Handler h;
    static Handler h() { if (h == null) h = new Handler(Looper.getMainLooper()); return h; }

    static boolean logged(Activity a) { return !IvaClientPatch.authEmpty(get(a, "accessToken")); }

    static boolean chief(Activity a) {
        Object e = get(a, "userEmail");
        return logged(a) && e instanceof String && CHIEF_EMAIL.equalsIgnoreCase(((String) e).trim());
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Throwable e) { return s; }
    }

    static double num(JSONObject j, String k) {
        if (j == null || !j.has(k) || j.isNull(k)) return 0;
        double d = j.optDouble(k, 0);
        return Double.isNaN(d) ? 0 : d;
    }

    /** «★» оранжевым, остальное — заданным цветом. */
    static CharSequence starText(Activity a, String s, int rest) {
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new ForegroundColorSpan(rest), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int orange = color(a, "orange", 0xffff8f1f);
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == '★') sp.setSpan(new ForegroundColorSpan(orange), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    static List<Map<String, String>> parseReviews(String body) throws Exception {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        JSONArray arr = new JSONArray(body == null || body.trim().isEmpty() ? "[]" : body);
        String[] keys = REVIEW_COLS.split(",");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;
            Map<String, String> m = new LinkedHashMap<String, String>();
            for (String k : keys) m.put(k, j.isNull(k) ? "" : j.optString(k, ""));
            out.add(m);
        }
        return out;
    }

    static Map<String, String> reviewOf(JSONObject j) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (j == null) return m;
        for (String k : REVIEW_COLS.split(",")) m.put(k, j.isNull(k) ? "" : j.optString(k, ""));
        return m;
    }

    // ═════════════════════════════ вход из IvaPatch.onTab ═════════════════════════════

    public static void onTab(Activity a, String tab) {
        if ("detail".equals(tab)) { try { onDetail(a); } catch (Throwable ignore) {} }
        else if ("seller".equals(tab)) { try { onSeller(a); } catch (Throwable ignore) {} }
    }

    // ═════════════════════════════ лента: «★ 4,8 (12)» на карточке ═════════════════════════════

    static boolean feedPosted;

    /** Из IvaSearchPatch.onFeedChild: после перерисовки ленты — один раз. */
    static void onFeedChild(final Activity a) {
        if (feedPosted) return;
        feedPosted = true;
        h().post(new Runnable() {
            @Override public void run() {
                feedPosted = false;
                try { decorateFeed(a); } catch (Throwable ignore) {}
            }
        });
    }

    static void decorateFeed(Activity a) {
        Object fg = get(a, "feedGrid");
        if (!(fg instanceof ViewGroup)) return;
        Map<String, List<JSONObject>> byName = new HashMap<String, List<JSONObject>>();
        for (Object o : IvaSearchPatch.allToolsQuiet(a)) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            String k = shorten34(j.optString("title", j.optString("name", "Без названия")));
            List<JSONObject> l = byName.get(k);
            if (l == null) { l = new ArrayList<JSONObject>(); byName.put(k, l); }
            l.add(j);
        }
        if (byName.isEmpty()) return;
        ViewGroup grid = (ViewGroup) fg;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View row = grid.getChildAt(i);
            if (isCard(row)) decorateCard(a, (LinearLayout) row, byName);
            else if (row instanceof ViewGroup) {
                ViewGroup rg = (ViewGroup) row;
                for (int c = 0; c < rg.getChildCount(); c++)
                    if (isCard(rg.getChildAt(c))) decorateCard(a, (LinearLayout) rg.getChildAt(c), byName);
            }
        }
    }

    /** Штатная карточка feedCard: вертикальная, [картинка (FrameLayout), подписи (LinearLayout: название, цена, …)]. */
    static boolean isCard(View v) {
        if (!(v instanceof LinearLayout)) return false;
        LinearLayout l = (LinearLayout) v;
        if (l.getOrientation() != LinearLayout.VERTICAL || l.getChildCount() < 2) return false;
        if (!(l.getChildAt(0) instanceof FrameLayout) || !(l.getChildAt(1) instanceof LinearLayout)) return false;
        LinearLayout info = (LinearLayout) l.getChildAt(1);
        return info.getChildCount() >= 2 && info.getChildAt(0) instanceof TextView && info.getChildAt(1) instanceof TextView;
    }

    static final String BADGE_TAG = "iva_rv_badge";

    static void decorateCard(Activity a, LinearLayout card, Map<String, List<JSONObject>> byName) {
        LinearLayout info = (LinearLayout) card.getChildAt(1);
        FrameLayout pic = (FrameLayout) card.getChildAt(0);
        String name = String.valueOf(((TextView) info.getChildAt(0)).getText());
        String price = String.valueOf(((TextView) info.getChildAt(1)).getText());
        List<JSONObject> same = byName.get(name);
        List<String[]> cands = new ArrayList<String[]>();
        if (same != null) for (JSONObject j : same)
            cands.add(new String[]{priceText(a, j), feedKey(num(j, "rating"), (int) num(j, "reviews_count"))});
        String[] tx = feedTexts(chooseLine(cands, price));   // null — карточку не узнали: строка пустая, но место за ней
        // ряд по две карточки: обе — на высоту ряда (низ карточек на одной линии, ряды не «сползают»)
        if (card.getParent() instanceof LinearLayout && ((LinearLayout) card.getParent()).getOrientation() == LinearLayout.HORIZONTAL
            && card.getLayoutParams() instanceof LinearLayout.LayoutParams && card.getLayoutParams().height != ViewGroup.LayoutParams.MATCH_PARENT) {
            LinearLayout.LayoutParams cl = (LinearLayout.LayoutParams) card.getLayoutParams();
            cl.height = ViewGroup.LayoutParams.MATCH_PARENT;
            card.setLayoutParams(cl);
        }
        // под ценой — «12 отзывов» / «Нет отзывов» (у всех карточек)
        View old = info.findViewWithTag(CARD_TAG);
        TextView tv = old instanceof TextView ? (TextView) old : null;
        if (tv == null) {
            tv = new TextView(a);
            tv.setTag(CARD_TAG);
            tv.setTextSize(12f);
            tv.setSingleLine(true);
            tv.setTextColor(color(a, "gray", 0xff9aa1b0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(a, 2);
            info.addView(tv, Math.min(2, info.getChildCount()), lp);
        }
        tv.setText(tx == null ? NO_REVIEWS_CARD : tx[1]);
        tv.setVisibility(tx == null ? View.INVISIBLE : View.VISIBLE);
        // на картинке справа внизу — «★ 4,8» (слева внизу — штатный значок доставки, справа вверху — избранное)
        View ob = pic.findViewWithTag(BADGE_TAG);
        if (tx == null || tx[0].isEmpty()) { if (ob != null) ob.setVisibility(View.GONE); return; }
        TextView b = ob instanceof TextView ? (TextView) ob : null;
        if (b == null) {
            b = new TextView(a);
            b.setTag(BADGE_TAG);
            b.setTextSize(12f);
            b.setTypeface(null, Typeface.BOLD);
            b.setSingleLine(true);
            b.setPadding(dp(a, 7), dp(a, 2), dp(a, 7), dp(a, 2));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xcc101217);                     // фон приложения, полупрозрачный
            bg.setCornerRadius(dp(a, 10));
            b.setBackground(bg);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            bl.setMargins(0, 0, dp(a, 6), dp(a, 6));
            pic.addView(b, bl);
        }
        b.setVisibility(View.VISIBLE);
        b.setText(starText(a, tx[0], color(a, "text", 0xfff2f4f8)));
    }

    /** Текст цены, как его пишет штатный feedCard: «от 500 ₽/сут» / «Цену уточняйте». */
    static String priceText(Activity a, JSONObject j) {
        if (!j.has("price") || j.isNull("price")) return "Цену уточняйте";
        double p = j.optDouble("price");
        if (Double.isNaN(p)) return "Цену уточняйте";
        try {
            Method m = a.getClass().getDeclaredMethod("money", double.class);
            m.setAccessible(true);
            return "от " + m.invoke(a, p) + " ₽/сут";
        } catch (Throwable e) { return ""; }
    }

    // ═════════════════════════════ страница инструмента ═════════════════════════════

    static String detTool = "";
    static long detLoaded;
    static int detGen;
    static List<Map<String, String>> detReviews;     // null — не загрузились
    static JSONObject detCan;                        // null — не вошли / не загрузилось
    static boolean detShowAll;

    static void onDetail(final Activity a) {
        Object it = get(a, "detItem"), nm = get(a, "detName");
        if (!(it instanceof JSONObject) || !(nm instanceof TextView) || !(((View) nm).getParent() instanceof LinearLayout)) return;
        final JSONObject item = (JSONObject) it;
        LinearLayout box = (LinearLayout) ((View) nm).getParent();
        String id = val(item.optString("id", ""));
        boolean srv = Boolean.TRUE.equals(get(a, "detIsSrv"));
        View oldLine = box.findViewWithTag(DET_LINE_TAG), oldBlock = box.findViewWithTag(DET_BLOCK_TAG);
        if (id.isEmpty() || srv) {                    // услуга / без id — отзывов нет
            if (oldLine != null) oldLine.setVisibility(View.GONE);
            if (oldBlock != null) oldBlock.setVisibility(View.GONE);
            return;
        }
        TextView line = oldLine instanceof TextView ? (TextView) oldLine : null;
        if (line == null) {
            line = new TextView(a);
            line.setTag(DET_LINE_TAG);
            line.setTextSize(14f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 4);
            box.addView(line, box.indexOfChild((View) nm) + 1, lp);
            line.setOnClickListener(new View.OnClickListener() {   // нажать — к отзывам
                @Override public void onClick(View v) { scrollToBlock(a); }
            });
        }
        line.setVisibility(View.VISIBLE);
        line.setText(starText(a, summaryLine(num(item, "rating"), (int) num(item, "reviews_count")), color(a, "gray", 0xff9aa1b0)));
        LinearLayout block = oldBlock instanceof LinearLayout ? (LinearLayout) oldBlock : null;
        if (block == null) {
            block = new LinearLayout(a);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setTag(DET_BLOCK_TAG);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = dp(a, 18);
            box.addView(block, bl);
        }
        block.setVisibility(View.VISIBLE);
        if (id.equals(detTool) && detReviews != null && System.currentTimeMillis() - detLoaded < DET_FRESH_MS) {
            render(a, block, item);
            return;
        }
        if (!id.equals(detTool)) { detReviews = null; detCan = null; detShowAll = false; }
        detTool = id;
        renderLoading(a, block);
        load(a, item);
    }

    static void scrollToBlock(Activity a) {
        try {
            Object pd = get(a, "pageDetail"), nm = get(a, "detName");
            if (!(pd instanceof ScrollView) || !(nm instanceof View)) return;
            View b = ((ViewGroup) ((View) nm).getParent()).findViewWithTag(DET_BLOCK_TAG);
            if (b == null) return;
            int y = b.getTop();
            for (View p = (View) b.getParent(); p != null && p != pd; p = p.getParent() instanceof View ? (View) p.getParent() : null) y += p.getTop();
            ((ScrollView) pd).smoothScrollTo(0, Math.max(0, y - dp(a, 12)));
        } catch (Throwable ignore) {}
    }

    static void load(final Activity a, final JSONObject item) {
        final int gen = ++detGen;
        final String id = val(item.optString("id", ""));
        new Thread(new Runnable() {
            @Override public void run() {
                List<Map<String, String>> rs = null;
                JSONObject can = null;
                boolean in = logged(a);
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/reviews?select=" + REVIEW_COLS + "&tool_id=eq." + enc(id)
                        + "&order=created_at.desc&limit=200", "GET", null, in);
                    if (ok(r)) rs = parseReviews(r[1]);
                } catch (Throwable ignore) {}
                if (in) try {
                    String[] c = IvaClientPatch.apiCall(a, "/rest/v1/rpc/can_user_review", "POST", "{\"p_tool_id\":" + IvaNotifyPatch.jsonStr(id) + "}", true);
                    if (ok(c)) can = IvaClientPatch.rpcFirst(c[1]);
                } catch (Throwable ignore) {}
                final List<Map<String, String>> frs = rs;
                final JSONObject fcan = can;
                h().post(new Runnable() {
                    @Override public void run() {
                        if (gen != detGen || !id.equals(detTool)) return;    // уже открыт другой инструмент
                        detReviews = frs;
                        detCan = fcan;
                        detLoaded = frs == null ? 0 : System.currentTimeMillis();
                        try { refreshDetail(a, item); } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    static void refreshDetail(Activity a, JSONObject item) {
        Object nm = get(a, "detName");
        if (!(nm instanceof View) || !(((View) nm).getParent() instanceof ViewGroup)) return;
        ViewGroup box = (ViewGroup) ((View) nm).getParent();
        if (get(a, "detItem") != item) return;
        View line = box.findViewWithTag(DET_LINE_TAG), block = box.findViewWithTag(DET_BLOCK_TAG);
        if (detReviews != null) {
            double[] s = summary(detReviews);
            // В памяти — свежие рейтинг и число (лента покажет их при следующей перерисовке; в базе их пересчитал триггер).
            try {
                if (num(item, "rating") != s[0] || (int) num(item, "reviews_count") != (int) s[1]) {
                    item.put("rating", s[0]);
                    item.put("reviews_count", (int) s[1]);
                }
            } catch (Throwable ignore) {}
            if (line instanceof TextView) ((TextView) line).setText(starText(a, summaryLine(s[0], (int) s[1]), color(a, "gray", 0xff9aa1b0)));
        }
        if (block instanceof LinearLayout) render(a, (LinearLayout) block, item);
    }

    static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    static LinearLayout.LayoutParams lp(Activity a, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = dp(a, top);
        return l;
    }

    static void header(Activity a, LinearLayout block) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = text(a, "Отзывы", 17f, color(a, "text", 0xfff2f4f8), true);
        row.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        if (detReviews != null) {
            double[] s = summary(detReviews);
            String c = cardLine(s[0], (int) s[1]);
            if (!c.isEmpty()) {
                TextView sv = new TextView(a);
                sv.setTextSize(15f);
                sv.setTypeface(null, Typeface.BOLD);
                sv.setText(starText(a, c, color(a, "text", 0xfff2f4f8)));
                row.addView(sv, new LinearLayout.LayoutParams(-2, -2));
            }
        }
        block.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    static void renderLoading(Activity a, LinearLayout block) {
        block.removeAllViews();
        header(a, block);
        block.addView(text(a, "Загрузка отзывов…", 14f, color(a, "gray", 0xff9aa1b0), false), lp(a, 8));
    }

    static void render(final Activity a, final LinearLayout block, final JSONObject item) {
        block.removeAllViews();
        header(a, block);
        int gray = color(a, "gray", 0xff9aa1b0);
        if (detReviews == null) {
            TextView t = text(a, "Не удалось загрузить отзывы. Нажмите, чтобы повторить.", 14f, gray, false);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { renderLoading(a, block); load(a, item); }
            });
            block.addView(t, lp(a, 8));
            return;
        }
        boolean in = logged(a);
        boolean allowed = detCan != null && detCan.optBoolean("allowed", false);
        String reason = detCan == null ? "" : val(detCan.optString("reason", ""));
        JSONObject mine = allowed && detCan.optBoolean("has_review", false) ? detCan.optJSONObject("review") : null;
        final Map<String, String> my = mine == null ? null : reviewOf(mine);
        String hint = formHint(in, detCan != null, allowed, reason);
        if (hint != null) {
            if (!hint.isEmpty()) block.addView(text(a, hint, 13f, gray, false), lp(a, 8));
        } else if (my != null) {
            block.addView(reviewCard(a, my, true, false, null), lp(a, 10));
        } else {
            TextView b = button(a, "Оставить отзыв", false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showForm(a, item, null, reloader(a, item)); }
            });
            block.addView(b, btnLp(a, 10));
        }
        final boolean ch = chief(a);
        List<Map<String, String>> list = visible(detReviews, ch, my == null ? "" : my.get("id"));
        if (list.isEmpty() && my == null) {
            block.addView(text(a, EMPTY_LIST, 14f, gray, false), lp(a, 12));
            return;
        }
        int n = detShowAll ? list.size() : Math.min(LIST_FIRST, list.size());
        for (int i = 0; i < n; i++) block.addView(reviewCard(a, list.get(i), false, ch, item), lp(a, 10));
        if (n < list.size()) {
            TextView more = button(a, "Показать все отзывы (" + list.size() + ")", false);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { detShowAll = true; render(a, block, item); }
            });
            block.addView(more, btnLp(a, 10));
        }
        if (my != null) {   // «Изменить» у своего отзыва
            View card = block.findViewWithTag("iva_rv_my_edit");
            if (card != null) card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showForm(a, item, my, reloader(a, item)); }
            });
        }
    }

    static Runnable reloader(final Activity a, final JSONObject item) {
        return new Runnable() {
            @Override public void run() {
                detLoaded = 0;
                if (get(a, "detItem") == item) {
                    Object nm = get(a, "detName");
                    if (nm instanceof View && ((View) nm).getParent() instanceof ViewGroup) {
                        View b = ((ViewGroup) ((View) nm).getParent()).findViewWithTag(DET_BLOCK_TAG);
                        if (b instanceof LinearLayout) renderLoading(a, (LinearLayout) b);
                    }
                    load(a, item);
                }
            }
        };
    }

    static TextView button(Activity a, String s, boolean primary) {
        TextView b = new TextView(a);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(15f);
        b.setTypeface(null, Typeface.BOLD);
        if (primary) {
            IvaPatch.accentButton(a, b);                 // 10.96: «рамкой» — тёмная, оранжевая рамка и текст
        } else {
            b.setTextColor(color(a, "orange", 0xffff8f1f));
            b.setBackground(IvaClientPatch.choiceBg(a, "card", 0xff1a1d24, IvaClientPatch.CHOICE_RADIUS_DP));
        }
        return b;
    }

    static LinearLayout.LayoutParams btnLp(Activity a, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, dp(a, 48));
        l.topMargin = dp(a, top);
        return l;
    }

    /** Карточка отзыва: имя и дата, звёзды, текст; свой — оранжевая рамка и «Изменить»; главному админу — модерация. */
    static View reviewCard(final Activity a, final Map<String, String> r, boolean mine, boolean chief, final JSONObject item) {
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        boolean hidden = !published(r);
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(a, "card", 0xff1a1d24));
        bg.setCornerRadius(dp(a, 14));
        if (mine) bg.setStroke(dp(a, 1), cOrange);
        c.setBackground(bg);
        if (hidden && !mine) c.setAlpha(0.6f);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String who = val(r.get("author_name"));
        if (who.isEmpty()) who = mine ? "Вы" : "Арендатор";
        TextView name = text(a, who, 15f, cText, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView date = text(a, dateLine(r), 12f, cGray, false);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.leftMargin = dp(a, 8);
        top.addView(date, dl);
        c.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        TextView st = text(a, stars(ratingOf(r)), 16f, cOrange, false);
        mid.addView(st, new LinearLayout.LayoutParams(-2, -2));
        if (mine) {
            TextView tag = text(a, "  Ваш отзыв", 12f, cOrange, true);
            mid.addView(tag, new LinearLayout.LayoutParams(-2, -2));
        }
        if (hidden) {
            TextView hd = text(a, "  Скрыт", 12f, color(a, "red", 0xffff4d4f), true);
            mid.addView(hd, new LinearLayout.LayoutParams(-2, -2));
        }
        c.addView(mid, lp(a, 4));

        String body = val(r.get("text"));
        if (!body.isEmpty()) {
            TextView t = text(a, body, 14f, cText, false);
            t.setTextIsSelectable(true);
            c.addView(t, lp(a, 6));
        }
        if (mine) {
            TextView ed = text(a, "Изменить отзыв ›", 14f, cOrange, true);
            ed.setTag("iva_rv_my_edit");
            ed.setPadding(0, dp(a, 8), 0, 0);
            c.addView(ed, new LinearLayout.LayoutParams(-2, -2));
        } else if (item != null && modAction(chief, hidden, hasMat(body) || hasMat(val(r.get("author_name")))) != null) {
            final String to = modAction(chief, hidden, true);
            final TextView mod = text(a, hidden ? "Восстановить" : "Скрыть спам", 13f, cOrange, true);
            mod.setPadding(0, dp(a, 8), 0, 0);
            mod.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { moderate(a, r, to, item, mod); }
            });
            c.addView(mod, new LinearLayout.LayoutParams(-2, -2));
        }
        return c;
    }

    /** Модерация — как на сайте (toggle_review_status; право проверяет база: только главный админ). */
    static void moderate(final Activity a, final Map<String, String> r, final String to, final JSONObject item, final TextView btn) {
        btn.setEnabled(false);
        btn.setAlpha(0.5f);
        final String id = val(r.get("id"));
        new Thread(new Runnable() {
            @Override public void run() {
                String[] res = null;
                try {
                    res = IvaClientPatch.apiCall(a, "/rest/v1/rpc/toggle_review_status", "POST",
                        "{\"p_review_id\":" + IvaNotifyPatch.jsonStr(id) + ",\"p_status\":" + IvaNotifyPatch.jsonStr(to) + "}", true);
                } catch (Throwable ignore) {}
                final String[] fr = res;
                h().post(new Runnable() {
                    @Override public void run() {
                        if (ok(fr)) {
                            Toast.makeText(a, "hidden".equals(to) ? "Отзыв скрыт" : "Отзыв опубликован", Toast.LENGTH_SHORT).show();
                            reloader(a, item).run();
                        } else {
                            btn.setEnabled(true);
                            btn.setAlpha(1f);
                            Toast.makeText(a, "Ошибка модерации: " + errMessage(fr == null ? "-1" : fr[0], fr == null ? "" : fr[1]), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    // ═════════════════════════════ страница проката ═════════════════════════════

    static String sellerFor = "";
    static String sellerText;                       // null — штатный текст остаётся
    static WeakReference<TextView> sellerWatched;
    static boolean sellerApplying;
    static int sellerGen;

    static void onSeller(final Activity a) {
        Object tv = get(a, "sellerRating"), em = get(a, "sellerEmail");
        if (!(tv instanceof TextView) || !(em instanceof String)) return;
        final TextView t = (TextView) tv;
        final String email = ((String) em).trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) return;
        if (sellerWatched == null || sellerWatched.get() != t) {
            sellerWatched = new WeakReference<TextView>(t);
            // Штатная фоновая загрузка проката пишет «Отзывы: пока нет · рейтинг не сформирован» позже нас — возвращаем рейтинг.
            t.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (sellerApplying || sellerText == null || !nativeNoReviews(String.valueOf(s))) return;
                    h().post(new Runnable() { @Override public void run() { applySeller(a, t); } });
                }
            });
        }
        if (!email.equals(sellerFor)) sellerText = null;
        sellerFor = email;
        applySeller(a, t);
        final int gen = ++sellerGen;
        new Thread(new Runnable() {
            @Override public void run() {
                String line = null;
                boolean got = false;
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/get_landlord_rating", "POST",
                        "{\"p_owner_email\":" + IvaNotifyPatch.jsonStr(email) + "}", logged(a));
                    if (ok(r)) {
                        JSONObject j = IvaClientPatch.rpcFirst(r[1]);
                        line = sellerLine(num(j, "rating"), (int) num(j, "reviews_count"));
                        got = true;
                    }
                } catch (Throwable ignore) {}
                final String fl = line;
                final boolean fg = got;
                h().post(new Runnable() {
                    @Override public void run() {
                        if (gen != sellerGen || !email.equals(sellerFor) || !fg) return;
                        sellerText = fl;
                        applySeller(a, t);
                    }
                });
            }
        }).start();
    }

    static void applySeller(Activity a, TextView t) {
        if (sellerText == null || !nativeNoReviews(String.valueOf(t.getText()))) return;
        sellerApplying = true;
        try { t.setText(starText(a, sellerText, t.getCurrentTextColor())); } finally { sellerApplying = false; }
    }

    // ═════════════════════════════ закрытая заявка: «Оставить отзыв» ═════════════════════════════

    /** Из IvaOrderPatch.ordOpenRow (заявка клиента, «Закрыта»): по строке на каждый инструмент из каталога. */
    static void orderBlock(final Activity a, LinearLayout body, Map<String, String> r) {
        if (!logged(a)) return;
        List<String> names = IvaOrderPatch.ordToolNames(r.get("tools"));
        if (names.isEmpty()) return;
        List<JSONObject> cat = new ArrayList<JSONObject>();
        List<String> cn = new ArrayList<String>(), co = new ArrayList<String>();
        for (Object t : IvaSearchPatch.allToolsQuiet(a)) {
            if (!(t instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) t;
            cat.add(j);
            cn.add(j.optString("name", ""));
            co.add(j.optString("owner_email", ""));
        }
        List<String> owners = new ArrayList<String>();
        for (String e : IvaOrderPatch.ordOwners(a, r)) owners.add(e.trim().toLowerCase(Locale.ROOT));
        List<Integer> idx = IvaOrderPatch.ordPicIdx(names, cn, co, owners);
        List<JSONObject> tools = new ArrayList<JSONObject>();
        List<String> seen = new ArrayList<String>();
        for (Integer i : idx) {
            if (i == null || i < 0) continue;
            JSONObject j = cat.get(i);
            String id = val(j.optString("id", ""));
            if (id.isEmpty() || seen.contains(id)) continue;
            seen.add(id);
            tools.add(j);
        }
        if (tools.isEmpty()) return;                  // инструментов заявки больше нет в каталоге — отзыв не оставить
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(ORD_BLOCK_TAG);
        TextView h = text(a, "Отзыв", 16f, color(a, "text", 0xfff2f4f8), true);
        box.addView(h, lp(a, 22));
        box.addView(text(a, tools.size() == 1 ? "Оцените инструмент — отзыв увидят другие арендаторы"
            : "Оцените инструменты — отзывы увидят другие арендаторы", 13f, color(a, "gray", 0xff9aa1b0), false), lp(a, 4));
        for (final JSONObject t : tools) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(a, 14), 0, dp(a, 14), 0);
            row.setBackground(IvaClientPatch.choiceBg(a, "card", 0xff1a1d24, IvaClientPatch.CHOICE_RADIUS_DP));
            TextView n = text(a, t.optString("name", ""), 15f, color(a, "text", 0xfff2f4f8), false);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView go = text(a, "Оставить отзыв ›", 14f, color(a, "orange", 0xffff8f1f), true);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
            gl.leftMargin = dp(a, 8);
            row.addView(go, gl);
            final View rv = row;
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openForTool(a, t, rv); }
            });
            box.addView(row, btnLp(a, 8));
        }
        body.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Право — как на сайте (can_user_review); есть свой отзыв — форма с ним. */
    static void openForTool(final Activity a, final JSONObject tool, final View row) {
        row.setEnabled(false);
        row.setAlpha(0.6f);
        final String id = val(tool.optString("id", ""));
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject can = null;
                String code = "-1";
                try {
                    String[] c = IvaClientPatch.apiCall(a, "/rest/v1/rpc/can_user_review", "POST", "{\"p_tool_id\":" + IvaNotifyPatch.jsonStr(id) + "}", true);
                    if (c != null) code = c[0];
                    if (ok(c)) can = IvaClientPatch.rpcFirst(c[1]);
                } catch (Throwable ignore) {}
                final JSONObject fc = can;
                final String fcode = code;
                h().post(new Runnable() {
                    @Override public void run() {
                        row.setEnabled(true);
                        row.setAlpha(1f);
                        if (fc == null) { Toast.makeText(a, "Не удалось проверить: " + errMessage(fcode, ""), Toast.LENGTH_LONG).show(); return; }
                        if (!fc.optBoolean("allowed", false)) { Toast.makeText(a, reasonText(val(fc.optString("reason", ""))), Toast.LENGTH_LONG).show(); return; }
                        JSONObject mine = fc.optBoolean("has_review", false) ? fc.optJSONObject("review") : null;
                        showForm(a, tool, mine == null ? null : reviewOf(mine), new Runnable() {
                            @Override public void run() {
                                if (get(a, "detItem") != null && id.equals(detTool)) detLoaded = 0;   // страница инструмента перечитает
                            }
                        });
                    }
                });
            }
        }).start();
    }

    // ═════════════════════════════ форма отзыва (шторка) ═════════════════════════════

    static Dialog formDialog;

    static void showForm(final Activity a, final JSONObject tool, final Map<String, String> mine, final Runnable done) {
        if (formDialog != null && formDialog.isShowing()) return;
        final int cBg = color(a, "bg", 0xff101217), cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        final int cLine = color(a, "line", 0xff262b35), cOrange = color(a, "orange", 0xffff8f1f);
        final boolean edit = mine != null;
        final int[] rating = {edit && ratingOf(mine) > 0 ? ratingOf(mine) : 5};

        final FrameLayout root = new FrameLayout(a);
        final View scrim = new View(a);
        scrim.setBackgroundColor(0x8c000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        final LinearLayout sh = new LinearLayout(a);
        sh.setOrientation(LinearLayout.VERTICAL);
        sh.setClickable(true);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(cBg);
        float rr = dp(a, 18);
        sbg.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
        sh.setBackground(sbg);
        root.addView(sh, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        View grip = new View(a);                      // «язычок» как у шторок заявки и фильтра
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(cLine);
        gbg.setCornerRadius(dp(a, 2));
        grip.setBackground(gbg);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.topMargin = dp(a, 10);
        sh.addView(grip, gl);

        ScrollView sc = new ScrollView(a);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 16));
        sc.addView(body, new FrameLayout.LayoutParams(-1, -2));
        sh.addView(sc, new LinearLayout.LayoutParams(-1, -2));

        body.addView(text(a, edit ? "Изменить отзыв" : "Оставить отзыв", 18f, cText, true), new LinearLayout.LayoutParams(-1, -2));
        body.addView(text(a, tool.optString("name", ""), 14f, cGray, false), lp(a, 2));

        final LinearLayout starRow = new LinearLayout(a);
        starRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] st = new TextView[5];
        for (int i = 0; i < 5; i++) {
            final int v = i + 1;
            st[i] = text(a, "★", 34f, cOrange, false);
            st[i].setPadding(0, 0, dp(a, 8), 0);
            st[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    rating[0] = v;
                    for (int k = 0; k < 5; k++) st[k].setTextColor(k < v ? cOrange : cLine);
                }
            });
            starRow.addView(st[i], new LinearLayout.LayoutParams(-2, -2));
        }
        for (int k = 0; k < 5; k++) st[k].setTextColor(k < rating[0] ? cOrange : cLine);
        body.addView(starRow, lp(a, 10));

        final EditText in = new EditText(a);
        in.setHint("Поделитесь впечатлениями: состояние инструмента, удобство работы, комплектация…");
        in.setHintTextColor(cGray);
        in.setTextColor(cText);
        in.setTextSize(15f);
        in.setMinLines(4);
        in.setGravity(Gravity.TOP | Gravity.START);
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEXT)});
        Drawable fb = drawable(a, "bg_field");
        if (fb != null) in.setBackground(fb);
        in.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        if (edit) in.setText(val(mine.get("text")));
        body.addView(in, lp(a, 10));

        final TextView count = text(a, in.getText().length() + "/" + MAX_TEXT, 12f, cGray, false);
        count.setGravity(Gravity.END);
        body.addView(count, lp(a, 4));
        in.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) {}
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) {}
            @Override public void afterTextChanged(Editable s) { count.setText(s.length() + "/" + MAX_TEXT); }
        });
        final TextView err = text(a, "", 13f, color(a, "red", 0xffff4d4f), false);
        err.setVisibility(View.GONE);
        body.addView(err, lp(a, 6));

        final TextView send = button(a, edit ? "Сохранить изменения" : "Опубликовать отзыв", true);
        body.addView(send, btnLp(a, 12));
        TextView cancel = button(a, "Отмена", false);
        body.addView(cancel, btnLp(a, 8));

        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        View.OnClickListener dismiss = new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } };
        scrim.setOnClickListener(dismiss);
        cancel.setOnClickListener(dismiss);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface x) { if (formDialog == d) formDialog = null; }
        });
        final String toolId = val(tool.optString("id", ""));
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!logged(a)) { err.setText("Войдите в аккаунт, чтобы оставить отзыв"); err.setVisibility(View.VISIBLE); return; }
                Object un0 = get(a, "userName");
                String bad = hasMat(String.valueOf(in.getText())) ? MAT_MSG : hasMat(un0 instanceof String ? (String) un0 : "") ? MAT_NAME_MSG : null;
                if (bad != null) { err.setText(bad); err.setVisibility(View.VISIBLE); return; }   // 10.71: мат не отправляем (база тоже не примет)
                send.setEnabled(false);
                send.setAlpha(0.6f);
                send.setText("Сохраняем…");
                err.setVisibility(View.GONE);
                Object un = get(a, "userName");
                final String body = submitBody(toolId, rating[0], String.valueOf(in.getText()), un instanceof String ? (String) un : "");
                final boolean held = IvaCensorPatch.level(String.valueOf(in.getText())) == 1
                    || IvaCensorPatch.level(un instanceof String ? (String) un : "") == 1;   // 10.92: сомнительное — после проверки
                new Thread(new Runnable() {
                    @Override public void run() {
                        String[] r = null;
                        try { r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/submit_review", "POST", body, true); } catch (Throwable ignore) {}
                        final String[] fr = r;
                        h().post(new Runnable() {
                            @Override public void run() {
                                if (ok(fr)) {
                                    Toast.makeText(a, held ? HELD_MSG : edit ? "Отзыв обновлён!" : "Спасибо за отзыв!", held ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                                    try { d.dismiss(); } catch (Throwable ignore) {}
                                    if (done != null) try { done.run(); } catch (Throwable ignore) {}
                                    return;
                                }
                                send.setEnabled(true);
                                send.setAlpha(1f);
                                send.setText(edit ? "Сохранить изменения" : "Опубликовать отзыв");
                                err.setText("Не удалось сохранить отзыв: " + errMessage(fr == null ? "-1" : fr[0], fr == null ? "" : fr[1]));
                                err.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                }).start();
            }
        });
        formDialog = d;
        d.show();
        int screen = a.getResources().getDisplayMetrics().heightPixels;
        sh.setTranslationY(screen);                    // выезжает снизу, как шторка заявки
        sh.animate().translationY(0).setDuration(220).start();
    }
}

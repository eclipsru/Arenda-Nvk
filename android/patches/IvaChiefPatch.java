package ru.prokatnvsk.app;

// 10.93 (просьба владельца 15.10.2026): кабинет создателя.
//
//   1. Пункт «Управление» — отдельная страница с пунктами, каждый тоже открывается отдельной страницей:
//      «Сводка», «Комиссия», «Фильтры», «Модерация», «Кандидаты», «Реклама Ива», «Платный контент».
//      Пока — логическое содержание (владелец: «потом доработаем»): где можно — настоящие цифры из базы
//      (только чтение), где решений ещё нет — что там будет и что нужно решить.
//   2. Пункт «Мои отзывы» — отзывы о моих инструментах и отзывы, которые написал я.
//   3. Из «Панели владельца» убрана карточка «Ваш доход с админов» (штатная, рисуется в chiefBox после
//      загрузки статистики) — прячется сразу при появлении (OnHierarchyChangeListener на chiefBox).
//      Остальная панель (статистика, заявки на оплату, админы) не тронута.
//      10.108: почта над заголовком, статистика под ним и «Добавить админа» прячутся (IvaAdminsPatch.panelChanged).
//
// Пункты видит только создатель eclips.ru@mail.ru (как «Цензуру»). Новых вставок в smali нет:
// вход — IvaNotifyPatch.onShowTab → IvaChiefPatch.onTab. Сеть — только в фоновых потоках. Персональные
// данные не запрашиваются (заявки арендодателей — без контактов).

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IvaChiefPatch {
    private IvaChiefPatch() {}

    // ════════════════════════════════ 1. Пункты кабинета ════════════════════════════════

    static final String ROW_MANAGE = "iva_chief_manage", ROW_REVIEWS = "iva_chief_reviews";
    static final String T_MANAGE = "Управление", S_MANAGE = "Сводка, комиссия, модерация, кандидаты и другое";
    static final String T_REVIEWS = "Мои отзывы", S_REVIEWS = "О моих инструментах и написанные мной";
    static final String INCOME_TITLE = "ВАШ ДОХОД С АДМИНОВ";

    /** Пункты «Управления» по порядку владельца: название, подпись, значок. */
    static final String[][] MENU = {
        {"Сводка", "Объявления, заявки, деньги, отзывы — главное одним экраном", "ic_grid"},
        {"Комиссия", "Начислено, получено, долги админов", "ic_receipt"},
        {"Фильтры", "Фильтры каталога и насколько заполнены объявления", "ic_layers"},
        {"Модерация", "«Цензура», снятые с показа, объявления без фото", "ic_eye"},
        {"Кандидаты", "Заявки арендодателей «Хочу сдавать»", "ic_user"},
        {"Реклама Ива", "Продвижение «Ивы» и рекламные места", "ic_send"},
        {"Платный контент", "Платные услуги для арендодателей", "ic_bolt"},
    };

    static WeakReference<ViewGroup> watchedBox = new WeakReference<ViewGroup>(null);

    static void onTab(Activity a, String tab) {
        if (!"profile".equals(tab)) return;
        try { hideIncome(a); } catch (Throwable ignore) {}
        try { ensureRows(a); } catch (Throwable ignore) {}
    }

    /** Карточка «Ваш доход с админов»: первый текст карточки — этот заголовок. */
    static boolean isIncomeTitle(CharSequence s) {
        return s != null && s.toString().trim().toUpperCase(Locale.ROOT).startsWith(INCOME_TITLE);
    }

    static boolean isIncomeCard(View v) {
        if (!(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        if (g.getChildCount() == 0 || !(g.getChildAt(0) instanceof TextView)) return false;
        return isIncomeTitle(((TextView) g.getChildAt(0)).getText());
    }

    static void hideIncome(final Activity a) {
        Object o = IvaClientPatch.get(a, "chiefBox");
        if (!(o instanceof ViewGroup)) return;
        ViewGroup box = (ViewGroup) o;
        for (int i = 0; i < box.getChildCount(); i++) {
            View c = box.getChildAt(i);
            if (isIncomeCard(c)) c.setVisibility(View.GONE);
        }
        if (watchedBox.get() == box) return;
        // карточка добавляется после загрузки статистики — прячем в момент добавления
        box.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) {
                try { if (isIncomeCard(child)) child.setVisibility(View.GONE); } catch (Throwable ignore) {}
                try { IvaAdminsPatch.panelChanged(a, (ViewGroup) parent); } catch (Throwable ignore) {}   // 10.108: без почты, статистики и «Добавить админа»
            }
            @Override public void onChildViewRemoved(View parent, View child) {}
        });
        watchedBox = new WeakReference<ViewGroup>(box);
    }

    static void ensureRows(final Activity a) {
        Object anchor = IvaClientPatch.get(a, "profVer");
        if (!(anchor instanceof View) || !(((View) anchor).getParent() instanceof View)) return;
        View ver = (View) ((View) anchor).getParent();
        if (!(ver.getParent() instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) ver.getParent();
        boolean want = IvaCensorPatch.rowFor(IvaNotifyPatch.loggedIn(a), String.valueOf(IvaClientPatch.get(a, "userEmail")));
        String[][] rows = {{ROW_MANAGE, T_MANAGE, S_MANAGE, "ic_gear"}, {ROW_REVIEWS, T_REVIEWS, S_REVIEWS, "ic_chat"}};
        for (final String[] r : rows) {
            View row = box.findViewWithTag(r[0]);
            if (row == null) {
                if (!want) continue;
                row = rowView(a, r[0], r[1], r[2], r[3], new Runnable() {
                    @Override public void run() { if (ROW_MANAGE.equals(r[0])) openManage(a); else openReviews(a); }
                });
                // перед «Цензурой» (если есть), иначе перед кнопкой обновления / версией — как «Цензура»
                View cz = box.findViewWithTag(IvaCensorPatch.ROW_TAG);
                View upd = box.findViewWithTag(IvaNotifyPatch.UPDATE_BTN_TAG);
                View before = cz != null && cz.getParent() == box ? cz : upd != null && upd.getParent() == box ? upd : ver;
                int at = box.indexOfChild(before);
                if (at < 0) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(a, 10);
                box.addView(row, at, lp);
            }
            row.setVisibility(want ? View.VISIBLE : View.GONE);
        }
    }

    /** Строка как штатные пункты кабинета: значок, название, подпись, стрелка. */
    static View rowView(Activity a, String tag, String title, String sub, String icon, final Runnable onTap) {
        LinearLayout row = new LinearLayout(a);
        if (tag != null) row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) row.setBackground(bg);
        row.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
        row.setClickable(true);
        Drawable ic = IvaSearchPatch.drawable(a, icon);
        if (ic != null) {
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(ic.mutate());
            iv.setColorFilter(color(a, "orange", 0xffff8f1f));
            row.addView(iv, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(a, title, 15f, color(a, "text", 0xfff2f4f8), false));
        if (sub != null && !sub.isEmpty()) col.addView(text(a, sub, 13f, color(a, "gray", 0xff9aa1b0), false));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 14);
        row.addView(col, cl);
        Drawable ch = IvaSearchPatch.drawable(a, "ic_chevron");
        if (ch != null) {
            ImageView chev = new ImageView(a);
            chev.setImageDrawable(ch.mutate());
            chev.setColorFilter(color(a, "gray", 0xff9aa1b0));
            row.addView(chev, new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18)));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { onTap.run(); } catch (Throwable ignore) {} }
        });
        return row;
    }

    // ════════════════════════════════ 2. Страницы ════════════════════════════════

    /** Открытая страница: окно, список, вкладки (могут не понадобиться). Страницы складываются стопкой. */
    static final class Page {
        Dialog d;
        LinearLayout list, tabs;
        boolean open() { return d != null && d.isShowing(); }
    }

    static Handler h;
    static Handler h() { if (h == null) h = new Handler(Looper.getMainLooper()); return h; }
    static int dp(Activity a, float v) { return IvaSearchPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }

    static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    static Page page(final Activity a, String title, String sub, boolean withTabs) {
        final Page p = new Page();
        int cBg = color(a, "bg", 0xff101217);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        TextView back = text(a, "‹ Назад", 15f, color(a, "orange", 0xffff8f1f), true);
        back.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 8));
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView t = text(a, title, 21f, color(a, "text", 0xfff2f4f8), true);
        t.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        root.addView(t, new LinearLayout.LayoutParams(-2, -2));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(a, sub, 12f, color(a, "gray2", 0xff6b7080), false);
            s.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 8));
            root.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        if (withTabs) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            p.tabs = new LinearLayout(a);
            p.tabs.setOrientation(LinearLayout.HORIZONTAL);
            p.tabs.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 4));
            hs.addView(p.tabs, new FrameLayout.LayoutParams(-2, -2));
            root.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView sc = new ScrollView(a);
        sc.setVerticalScrollBarEnabled(false);
        p.list = new LinearLayout(a);
        p.list.setOrientation(LinearLayout.VERTICAL);
        p.list.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 24));
        sc.addView(p.list, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(cBg));
            try {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.setStatusBarColor(cBg);
                w.setNavigationBarColor(cBg);
            } catch (Throwable ignore) {}
        }
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        p.d = d;
        IvaAccessPatch.track(d);   // 10.110: сняли админа / сотрудника — закрыть открытые страницы
        d.show();
        return p;
    }

    /**
     * 10.97 (правило владельца 16.10.2026): каждый пункт «Настроек» открывается ОТДЕЛЬНОЙ страницей — ничего не раскрывается
     * на месте. Готовое содержимое пункта переносится на новую страницу (как «Пункт выдачи»): переключатели, загрузка
     * и сохранение — те же объекты, состояние не теряется. Страница закрывается «‹ Назад» или кнопкой «Назад» телефона —
     * возвращаемся в «Настройки». onFocus — когда страница снова на экране (вернулись из настроек Android и т. п.).
     */
    static Page subPage(final Activity a, String title, View content, final Runnable onFocus) {
        Page p = page(a, title, null, false);
        android.view.ViewParent vp = content.getParent();
        if (vp instanceof ViewGroup) ((ViewGroup) vp).removeView(content);
        content.setVisibility(View.VISIBLE);
        p.list.addView(content, new LinearLayout.LayoutParams(-1, -2));
        if (onFocus != null) p.list.getViewTreeObserver().addOnWindowFocusChangeListener(new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override public void onWindowFocusChanged(boolean has) { if (has) try { onFocus.run(); } catch (Throwable ignore) {} }
        });
        return p;
    }

    static TextView hint(Activity a, String s) {
        TextView t = text(a, s, 14f, color(a, "gray", 0xff9aa1b0), false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 8), dp(a, 32), dp(a, 8), dp(a, 32));
        return t;
    }

    /** Заголовок раздела на странице. */
    static TextView head(Activity a, String s) {
        TextView t = text(a, s, 13f, color(a, "gray", 0xff9aa1b0), true);
        t.setPadding(dp(a, 2), dp(a, 18), 0, dp(a, 6));
        return t;
    }

    /** Карточка «название — значение» (строки). */
    static View kv(Activity a, List<String[]> rows) {
        LinearLayout c = card(a);
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, i == 0 ? 0 : dp(a, 8), 0, 0);
            TextView k = text(a, r[0], 14f, color(a, "gray", 0xff9aa1b0), false);
            line.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
            boolean warn = r.length > 2 && "warn".equals(r[2]), good = r.length > 2 && "good".equals(r[2]);
            TextView v = text(a, r[1], 14f, warn ? color(a, "orange", 0xffff8f1f) : good ? 0xff3ec27a : color(a, "text", 0xfff2f4f8), true);
            v.setGravity(Gravity.END);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.leftMargin = dp(a, 12);
            line.addView(v, vl);
            c.addView(line);
        }
        return c;
    }

    static LinearLayout card(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) c.setBackground(bg);
        c.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 8);
        c.setLayoutParams(lp);
        return c;
    }

    /** Карточка текста: заголовок и пояснение. */
    static View note(Activity a, String title, String body, String badge) {
        LinearLayout c = card(a);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(a, title, 15f, color(a, "text", 0xfff2f4f8), true), new LinearLayout.LayoutParams(0, -2, 1f));
        if (badge != null && !badge.isEmpty()) {
            TextView b = text(a, badge, 12f, color(a, "gray", 0xff9aa1b0), false);
            top.addView(b);
        }
        c.addView(top);
        if (body != null && !body.isEmpty()) {
            TextView t = text(a, body, 13f, color(a, "gray", 0xff9aa1b0), false);
            t.setPadding(0, dp(a, 4), 0, 0);
            c.addView(t);
        }
        return c;
    }

    // ── «Управление» ──
    static void openManage(final Activity a) {
        Page p = page(a, T_MANAGE, "Только для создателя. Цифры — из базы, только просмотр; решения принимаются в своих разделах.", false);
        for (int i = 0; i < MENU.length; i++) {
            final int k = i;
            View r = rowView(a, null, MENU[i][0], MENU[i][1], MENU[i][2], new Runnable() {
                @Override public void run() { openSection(a, k); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, i == 0 ? 4 : 10);
            p.list.addView(r, lp);
        }
        // 10.122: отмены заявок клиентами — журнал и доля по прокатам
        View cr = rowView(a, null, "Отмены заявок", "Журнал отмен клиентами и доля отмен по прокатам", "ic_receipt", new Runnable() {
            @Override public void run() { IvaOrderEditPatch.openChief(a); }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(a, 10);
        p.list.addView(cr, cl);
    }

    static void openSection(Activity a, int k) {
        if (k == 0) openSummary(a);
        else if (k == 1) openFee(a);
        else if (k == 2) openFilters(a);
        else if (k == 3) openModeration(a);
        else if (k == 4) openCandidates(a);
        else if (k == 5) openAds(a);
        else openPaid(a);
    }

    // Загрузка: несколько запросов в фоне → ответ (код, тело) по каждому → рисуем на главном потоке.
    interface Done { void on(String[][] res); }

    static void load(final Activity a, final Page p, final String[] paths, final Done done) {
        p.list.removeAllViews();
        p.list.addView(hint(a, "Загрузка…"));
        new Thread(new Runnable() {
            @Override public void run() {
                final String[][] res = new String[paths.length][];
                for (int i = 0; i < paths.length; i++) {
                    try { res[i] = IvaClientPatch.apiCall(a, paths[i], "GET", null, true); } catch (Throwable e) { res[i] = null; }
                }
                h().post(new Runnable() {
                    @Override public void run() {
                        if (!p.open()) return;
                        p.list.removeAllViews();
                        try { done.on(res); } catch (Throwable e) { p.list.addView(hint(a, "Не удалось показать данные")); }
                    }
                });
            }
        }).start();
    }

    static boolean ok(String[] r) { return r != null && r.length > 1 && r[0] != null && r[0].startsWith("2") && r[1] != null; }

    /** Ответ → строки «столбец → значение»: вложенный объект — «tools.name», массив — непустые элементы через \n,
     *  null — "" (ключ остаётся: так видно, что столбец в базе есть). Не разобрали / ошибка — null. */
    static List<Map<String, String>> arr(String[] r) {
        if (!ok(r)) return null;
        try {
            JSONArray ja = new JSONArray(r[1].trim());
            List<Map<String, String>> out = new ArrayList<Map<String, String>>();
            for (int i = 0; i < ja.length(); i++) {
                JSONObject j = ja.optJSONObject(i);
                if (j == null) continue;
                Map<String, String> m = new LinkedHashMap<String, String>();
                flat(j, "", m);
                out.add(m);
            }
            return out;
        } catch (Throwable e) { return null; }
    }

    static void flat(JSONObject j, String pre, Map<String, String> m) {
        java.util.Iterator<String> it = j.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = j.opt(k);
            if (v instanceof JSONObject) { flat((JSONObject) v, pre + k + ".", m); continue; }
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    String x = a.isNull(i) ? "" : a.optString(i, "").trim();
                    if (!x.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append(x);
                }
                m.put(pre + k, b.toString());
                continue;
            }
            m.put(pre + k, v == null || v == JSONObject.NULL ? "" : String.valueOf(v).trim());
        }
    }

    // ── денежные и счётные расчёты (без View — проверяются тестом) ──
    static String rub(double v) {
        long n = Math.round(v);
        String s = String.valueOf(Math.abs(n));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append('\u00a0');
            b.append(s.charAt(i));
        }
        return (n < 0 ? "−" : "") + b + " ₽";
    }

    static double num(Map<String, String> j, String k) {
        String v = str(j, k);
        if (v.isEmpty()) return 0;
        try { return Double.parseDouble(v.replace(',', '.')); } catch (Throwable e) { return 0; }
    }

    static String str(Map<String, String> j, String k) {
        String v = j == null ? null : j.get(k);
        return v == null || "null".equals(v.trim()) ? "" : v.trim();
    }

    static boolean activeOf(Map<String, String> t) { return !"false".equals(str(t, "active")); }

    /** Сколько дней назад (по UTC-дате из базы относительно now, мс). Не разобрали — -1. */
    static int daysAgo(String iso, long now) {
        if (iso == null || iso.length() < 10) return -1;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long t = f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso.substring(0, 10) + "T00:00:00").getTime();
            return (int) Math.max(0, (now - t) / 86400000L);
        } catch (Throwable e) { return -1; }
    }

    /** Объявления: [всего активных, свободно, забронировано, в аренде, снято с показа]. */
    static int[] toolCounts(List<Map<String, String>> tools) {
        int[] c = new int[5];
        if (tools == null) return c;
        for (Map<String, String> t : tools) {
            if (!activeOf(t)) { c[4]++; continue; }
            c[0]++;
            String st = str(t, "t_status");
            if ("booked".equals(st)) c[2]++; else if ("rented".equals(st)) c[3]++; else c[1]++;
        }
        return c;
    }

    /** Заявки: [всего, новые, в аренде, закрытые, за сегодня, за 7 дней, за 30 дней]. */
    static int[] orderCounts(List<Map<String, String>> parts, long now) {
        int[] c = new int[7];
        if (parts == null) return c;
        for (Map<String, String> p : parts) {
            c[0]++;
            String st = str(p, "status");
            if ("new".equals(st)) c[1]++; else if ("rented".equals(st)) c[2]++; else c[3]++;
            int d = daysAgo(str(p, "created_at"), now);
            if (d == 0) c[4]++;
            if (d >= 0 && d < 7) c[5]++;
            if (d >= 0 && d < 30) c[6]++;
        }
        return c;
    }

    /** Отзывы: [всего, скрытых, сумма оценок опубликованных ×1, опубликованных]. */
    static int[] reviewCounts(List<Map<String, String>> rv) {
        int[] c = new int[4];
        if (rv == null) return c;
        for (Map<String, String> r : rv) {
            c[0]++;
            if ("published".equals(str(r, "status"))) { c[3]++; c[2] += Math.max(0, Math.min(5, (int) num(r, "rating"))); }
            else c[1]++;
        }
        return c;
    }

    /** Комиссия — как сайт (moneyStats) и штатная панель: свои заявки создателя не в счёт. */
    static final class Money {
        double rent, fee, paid, pending;
        int pendingN;
        Map<String, double[]> byAdmin = new LinkedHashMap<String, double[]>();   // email → {начислено, оплачено, заявок}
        double owed() { return fee - paid; }
    }

    static Money money(List<Map<String, String>> parts, List<Map<String, String>> fees) {
        Money m = new Money();
        if (parts != null) for (Map<String, String> p : parts) {
            String em = str(p, "owner_email").toLowerCase(Locale.ROOT);
            if (em.isEmpty() || IvaCensorPatch.OWNER.equals(em)) continue;
            double fee = num(p, "fee");
            m.rent += num(p, "rent_sum");
            m.fee += fee;
            double[] x = m.byAdmin.get(em);
            if (x == null) m.byAdmin.put(em, x = new double[3]);
            x[0] += fee;
            x[2]++;
        }
        if (fees != null) for (Map<String, String> f : fees) {
            String em = str(f, "admin_email").toLowerCase(Locale.ROOT), st = str(f, "status");
            double amt = num(f, "amount");
            if ("confirmed".equals(st)) {
                m.paid += amt;
                double[] x = m.byAdmin.get(em);
                if (x == null) m.byAdmin.put(em, x = new double[3]);
                x[1] += amt;
            } else if ("pending".equals(st)) { m.pending += amt; m.pendingN++; }
        }
        return m;
    }

    /** Админы по убыванию долга. */
    static List<String> byDebt(final Money m) {
        List<String> ks = new ArrayList<String>(m.byAdmin.keySet());
        Collections.sort(ks, new Comparator<String>() {
            @Override public int compare(String x, String y) {
                double dx = m.byAdmin.get(x)[0] - m.byAdmin.get(x)[1], dy = m.byAdmin.get(y)[0] - m.byAdmin.get(y)[1];
                return dx == dy ? x.compareTo(y) : dx > dy ? -1 : 1;
            }
        });
        return ks;
    }

    /** Заполненность для фильтров: [активных, с подкатегорией, с видом, с моделью, с фото]. Вида/модели нет в ответе — -1. */
    static int[] coverage(List<Map<String, String>> tools) {
        int[] c = new int[5];
        if (tools == null) return c;
        boolean hasKind = false, hasModel = false;
        for (Map<String, String> t : tools) {
            if (t.containsKey("kind")) hasKind = true;
            if (t.containsKey("model_id")) hasModel = true;
            if (!activeOf(t)) continue;
            c[0]++;
            if (!str(t, "sub").isEmpty()) c[1]++;
            if (!str(t, "kind").isEmpty()) c[2]++;
            if (!str(t, "model_id").isEmpty()) c[3]++;
            if (hasPhoto(t)) c[4]++;
        }
        if (!hasKind) c[2] = -1;
        if (!hasModel) c[3] = -1;
        return c;
    }

    static boolean hasPhoto(Map<String, String> t) { return !str(t, "imgs").isEmpty() || !str(t, "img").isEmpty(); }

    static String share(int n, int of) {
        if (n < 0) return "нет в базе";
        if (of <= 0) return "0";
        return n + " из " + of + " · " + Math.round(n * 100.0 / of) + "%";
    }

    static String bizLabel(String t) {
        return "self_employed".equals(t) ? "Самозанятый" : "sole_trader".equals(t) ? "ИП" : "company".equals(t) ? "Компания" : t;
    }

    static String candStatus(String s) {
        return "pending".equals(s) ? "Ждёт решения" : "changes_requested".equals(s) ? "Нужны правки"
            : "approved".equals(s) ? "Одобрена" : "rejected".equals(s) ? "Отклонена" : s;
    }

    /** Заявки арендодателей: [ждут, нужны правки, одобрены, отклонены]. */
    static int[] candCounts(List<Map<String, String>> c) {
        int[] st = new int[4];
        String[] keys = {"pending", "changes_requested", "approved", "rejected"};
        if (c != null) for (Map<String, String> x : c) {
            String s = str(x, "status");
            for (int k = 0; k < 4; k++) if (keys[k].equals(s)) st[k]++;
        }
        return st;
    }

    static boolean missing(String[] r) {
        return r != null && r.length > 1 && IvaCensorPatch.missingTable(r[0], r[1]);
    }

    // ── запросы (только нужные столбцы; контактов нет) ──
    static final String Q_TOOLS = "/rest/v1/tools?select=active,t_status&limit=2000";
    static final String Q_PARTS = "/rest/v1/order_parts?select=owner_email,status,fee,rent_sum,created_at&order=id.desc&limit=1000";
    static final String Q_REVIEWS = "/rest/v1/reviews?select=rating,status&limit=2000";
    static final String Q_ADMINS = "/rest/v1/admins?select=role,active&limit=500";
    static final String Q_FEES = "/rest/v1/fee_payments?select=admin_email,amount,status,created_at&order=id.desc&limit=500";
    static final String Q_COVER = "/rest/v1/tools?select=active,sub,kind,model_id,imgs,img&limit=2000";
    static final String Q_COVER_OLD = "/rest/v1/tools?select=active,sub,imgs,img&limit=2000";
    static final String Q_MOD = "/rest/v1/tools?select=id,name,owner_email,active,imgs,img,created_at&order=created_at.desc&limit=500";
    static final String Q_CAND = "/rest/v1/landlord_applications?select=id,full_name,city,business_type,inventory_count,status,created_at"
        + "&order=created_at.desc&limit=100";

    // ── «Сводка» ──
    static void openSummary(final Activity a) {
        final Page p = page(a, "Сводка", "Главное по площадке. Заявки — последние 1000.", false);
        load(a, p, new String[]{Q_TOOLS, Q_PARTS, Q_REVIEWS, Q_ADMINS}, new Done() {
            @Override public void on(String[][] r) {
                long now = System.currentTimeMillis();
                int[] t = toolCounts(arr(r[0]));
                int[] o = orderCounts(arr(r[1]), now);
                int[] rv = reviewCounts(arr(r[2]));
                List<Map<String, String>> ad = arr(r[3]);
                int admins = 0;
                if (ad != null) for (Map<String, String> x : ad) if (activeOf(x) && !"chief".equals(str(x, "role"))) admins++;
                Money m = money(arr(r[1]), null);
                List<String[]> a1 = new ArrayList<String[]>();
                a1.add(new String[]{"В каталоге", String.valueOf(t[0])});
                a1.add(new String[]{"Свободно", String.valueOf(t[1]), "good"});
                a1.add(new String[]{"Забронировано", String.valueOf(t[2])});
                a1.add(new String[]{"В аренде", String.valueOf(t[3])});
                a1.add(new String[]{"Снято с показа", String.valueOf(t[4]), t[4] > 0 ? "warn" : ""});
                p.list.addView(head(a, "ОБЪЯВЛЕНИЯ"));
                p.list.addView(ok(r[0]) ? kv(a, a1) : note(a, "Не удалось загрузить", "Проверьте интернет и откройте снова.", ""));
                List<String[]> a2 = new ArrayList<String[]>();
                a2.add(new String[]{"Новые", String.valueOf(o[1]), o[1] > 0 ? "warn" : ""});
                a2.add(new String[]{"В аренде", String.valueOf(o[2])});
                a2.add(new String[]{"Закрытые", String.valueOf(o[3])});
                a2.add(new String[]{"Сегодня / 7 дней / 30 дней", o[4] + " / " + o[5] + " / " + o[6]});
                p.list.addView(head(a, "ЗАЯВКИ"));
                p.list.addView(ok(r[1]) ? kv(a, a2) : note(a, "Не удалось загрузить", "", ""));
                List<String[]> a3 = new ArrayList<String[]>();
                a3.add(new String[]{"Оборот аренды админов", rub(m.rent)});
                a3.add(new String[]{"Комиссия начислена", rub(m.fee)});
                a3.add(new String[]{"Подробно", "в «Комиссии»"});
                p.list.addView(head(a, "ДЕНЬГИ"));
                p.list.addView(kv(a, a3));
                List<String[]> a4 = new ArrayList<String[]>();
                a4.add(new String[]{"Опубликовано", String.valueOf(rv[3])});
                a4.add(new String[]{"Средняя оценка", rv[3] > 0 ? "★ " + IvaReviewPatch.fmtRating(rv[2] / (double) rv[3]) : "—"});
                a4.add(new String[]{"Скрыто", String.valueOf(rv[1])});
                a4.add(new String[]{"Цензура: на проверке", IvaCensorPatch.newCount < 0 ? "—" : String.valueOf(IvaCensorPatch.newCount),
                    IvaCensorPatch.newCount > 0 ? "warn" : ""});
                a4.add(new String[]{"Админов (без вас)", ad == null ? "—" : String.valueOf(admins)});
                p.list.addView(head(a, "ОТЗЫВЫ И ЛЮДИ"));
                p.list.addView(kv(a, a4));
            }
        });
    }

    // ── «Комиссия» ──
    static void openFee(final Activity a) {
        final Page p = page(a, "Комиссия", "Как на сайте: долг = начисленная комиссия минус подтверждённые оплаты. Ваши собственные заявки не в счёт.", false);
        load(a, p, new String[]{Q_PARTS, Q_FEES}, new Done() {
            @Override public void on(String[][] r) {
                if (!ok(r[0]) || !ok(r[1])) { p.list.addView(hint(a, "Не удалось загрузить — проверьте интернет")); return; }
                Money m = money(arr(r[0]), arr(r[1]));
                List<String[]> s = new ArrayList<String[]>();
                s.add(new String[]{"Начислено", rub(m.fee)});
                s.add(new String[]{"Получено", rub(m.paid), "good"});
                s.add(new String[]{"Долг админов", rub(m.owed()), m.owed() > 0.5 ? "warn" : ""});
                s.add(new String[]{"Ждут подтверждения", m.pendingN == 0 ? "нет" : m.pendingN + " · " + rub(m.pending), m.pendingN > 0 ? "warn" : ""});
                p.list.addView(head(a, "ИТОГО"));
                p.list.addView(kv(a, s));
                if (m.pendingN > 0)
                    p.list.addView(note(a, "Подтвердить оплату", "Заявки на оплату подтверждаются в «Панели владельца» в кабинете (кнопки «Подтвердить» / «Отклонить»).", ""));
                p.list.addView(head(a, "ПО АДМИНАМ"));
                List<String> ks = byDebt(m);
                if (ks.isEmpty()) { p.list.addView(hint(a, "Заявок у админов пока нет")); return; }
                for (String em : ks) {
                    double[] x = m.byAdmin.get(em);
                    List<String[]> rr = new ArrayList<String[]>();
                    rr.add(new String[]{em, ""});
                    rr.add(new String[]{"Заявок · начислено", (int) x[2] + " · " + rub(x[0])});
                    rr.add(new String[]{"Оплачено", rub(x[1])});
                    double debt = x[0] - x[1];
                    rr.add(new String[]{debt >= 0 ? "Долг" : "Переплата", rub(Math.abs(debt)), debt > 0.5 ? "warn" : debt < -0.5 ? "good" : ""});
                    p.list.addView(kv(a, rr));
                }
            }
        });
    }

    // ── «Фильтры» ──
    static final String[][] FILTERS = {
        {"Город", "Сверху на «Главной». Радиус — пока выключен."},
        {"Категория · Подкатегория · Вид", "По 3 плашки и «Все ▾». Вид появляется у подкатегорий, где он задан."},
        {"Цена за сутки", "От — до."},
        {"Состояние", "Новое / хорошее / рабочее — для объявлений, сохранённых после 10.89."},
        {"Бренд", "По названию и модели из справочника."},
        {"Характеристики", "От — до (мощность, вес, напряжение…) — только у объявлений с моделью из справочника."},
    };

    static void openFilters(final Activity a) {
        final Page p = page(a, "Фильтры", "Какие фильтры видят клиенты в каталоге и сколько объявлений под них заполнено.", false);
        load(a, p, new String[]{Q_COVER}, new Done() {
            @Override public void on(String[][] r) {
                if (ok(r[0])) { paintFilters(a, p, r[0]); return; }
                // нет столбцов вида / модели (SQL справочника не выполнен) — без них
                final String[][] first = r;
                load(a, p, new String[]{Q_COVER_OLD}, new Done() {
                    @Override public void on(String[][] r2) { paintFilters(a, p, ok(r2[0]) ? r2[0] : first[0]); }
                });
            }
        });
    }

    static void paintFilters(Activity a, Page p, String[] r) {
        p.list.addView(head(a, "ФИЛЬТРЫ КАТАЛОГА"));
        for (String[] f : FILTERS) p.list.addView(note(a, f[0], f[1], "включён"));
        p.list.addView(head(a, "ЗАПОЛНЕННОСТЬ ОБЪЯВЛЕНИЙ"));
        if (!ok(r)) { p.list.addView(hint(a, "Не удалось загрузить — проверьте интернет")); return; }
        int[] c = coverage(arr(r));
        List<String[]> s = new ArrayList<String[]>();
        s.add(new String[]{"В каталоге", String.valueOf(c[0])});
        s.add(new String[]{"С подкатегорией", share(c[1], c[0])});
        s.add(new String[]{"С видом", share(c[2], c[0]), c[2] >= 0 && c[2] < c[0] ? "warn" : ""});
        s.add(new String[]{"С моделью из справочника", share(c[3], c[0]), c[3] >= 0 && c[3] < c[0] ? "warn" : ""});
        s.add(new String[]{"С фото", share(c[4], c[0]), c[4] < c[0] ? "warn" : ""});
        p.list.addView(kv(a, s));
        p.list.addView(note(a, "Потом можно", "Менять порядок фильтров, прятать ненужные, добавлять свои (например, «С доставкой»).", "позже"));
    }

    // ── «Модерация» ──
    static void openModeration(final Activity a) {
        final Page p = page(a, "Модерация", "Что требует внимания: «Цензура», снятые с показа объявления, объявления без фото.", false);
        load(a, p, new String[]{Q_MOD}, new Done() {
            @Override public void on(String[][] r) {
                int n = IvaCensorPatch.newCount;
                View cz = rowView(a, null, "Цензура", n < 0 ? "Сомнительные объявления и отзывы" : n == 0 ? "На проверке ничего нет" : "На проверке: " + n,
                    "ic_eye", new Runnable() { @Override public void run() { IvaCensorPatch.open(a); } });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(a, 4);
                p.list.addView(cz, lp);
                List<Map<String, String>> t = arr(r[0]);
                if (t == null) { p.list.addView(hint(a, "Объявления не загрузились — проверьте интернет")); return; }
                List<Map<String, String>> off = new ArrayList<Map<String, String>>(), noPhoto = new ArrayList<Map<String, String>>();
                for (Map<String, String> x : t) { if (!activeOf(x)) off.add(x); else if (!hasPhoto(x)) noPhoto.add(x); }
                p.list.addView(head(a, "СНЯТЫ С ПОКАЗА · " + off.size()));
                if (off.isEmpty()) p.list.addView(note(a, "Нет", "Все объявления видны в каталоге.", ""));
                for (int i = 0; i < Math.min(off.size(), 30); i++) p.list.addView(toolLine(a, off.get(i)));
                p.list.addView(head(a, "БЕЗ ФОТО · " + noPhoto.size()));
                if (noPhoto.isEmpty()) p.list.addView(note(a, "Нет", "У всех объявлений в каталоге есть фото.", ""));
                for (int i = 0; i < Math.min(noPhoto.size(), 30); i++) p.list.addView(toolLine(a, noPhoto.get(i)));
                p.list.addView(note(a, "Потом можно", "Кнопки «Снять» / «Вернуть» и автопроверка фото — как на сайте.", "позже"));
            }
        });
    }

    static View toolLine(Activity a, Map<String, String> t) {
        String who = str(t, "owner_email");
        return note(a, str(t, "name").isEmpty() ? "Без названия" : str(t, "name"),
            (who.isEmpty() ? "" : who + " · ") + IvaReviewPatch.fmtDate(str(t, "created_at")), "");
    }

    // ── «Кандидаты» ──
    static void openCandidates(final Activity a) {
        final Page p = page(a, "Кандидаты", "Заявки «Хочу сдавать инструмент» с сайта. Контакты здесь не показываются.", false);
        load(a, p, new String[]{Q_CAND}, new Done() {
            @Override public void on(String[][] r) {
                if (missing(r[0])) { p.list.addView(hint(a, "Таблицы заявок арендодателей в базе нет")); return; }
                List<Map<String, String>> c = arr(r[0]);
                if (c == null) { p.list.addView(hint(a, "Не удалось загрузить — проверьте интернет")); return; }
                int[] st = candCounts(c);
                List<String[]> s = new ArrayList<String[]>();
                s.add(new String[]{"Ждут решения", String.valueOf(st[0]), st[0] > 0 ? "warn" : ""});
                s.add(new String[]{"Нужны правки", String.valueOf(st[1])});
                s.add(new String[]{"Одобрены", String.valueOf(st[2]), "good"});
                s.add(new String[]{"Отклонены", String.valueOf(st[3])});
                p.list.addView(head(a, "ЗАЯВКИ АРЕНДОДАТЕЛЕЙ"));
                p.list.addView(kv(a, s));
                p.list.addView(head(a, "ПОСЛЕДНИЕ"));
                if (c.isEmpty()) p.list.addView(hint(a, "Заявок пока нет. В приложении кандидаты пишут в чат «Хочу сдавать инструмент»."));
                for (Map<String, String> x : c) {
                    String body = str(x, "city") + " · " + bizLabel(str(x, "business_type")) + " · инструментов: " + (int) num(x, "inventory_count")
                        + "\n" + IvaReviewPatch.fmtDate(str(x, "created_at"));
                    p.list.addView(note(a, str(x, "full_name"), body, candStatus(str(x, "status"))));
                }
                p.list.addView(note(a, "Потом можно", "Одобрять и отклонять заявки прямо здесь (сейчас — на сайте, «Управление площадкой»).", "позже"));
            }
        });
    }

    // ── «Реклама Ива» и «Платный контент» — пока план (решений владельца ещё нет) ──
    static final String[][] ADS = {
        {"Страницы городов на сайте", "Ива в поиске по «аренда инструмента + город». Обращения со страниц считаются на сайте.", "работает"},
        {"Рассылка по базам проката", "Предложение базам проката в других городах — на сайте, «Рассылка по РФ».", "на сайте"},
        {"QR-наклейка на инструмент", "Наклейка «Взять в аренду через Иву» на инструмент арендодателей.", "идея"},
        {"Баннер на «Главной»", "Одно место над лентой: акция Ивы или партнёр.", "не включено"},
        {"«Рекомендуем» в ленте", "Карточка партнёра среди объявлений с пометкой «Реклама».", "не включено"},
        {"Уведомление по городу", "Раз в неделю — подборка или акция. Только тем, кто не выключил уведомления.", "не включено"},
    };

    static final String[][] PAID = {
        {"Поднять в ленте", "Объявление на 24 часа выше остальных в своей категории.", "цена не задана"},
        {"Выделить карточку", "Оранжевая рамка в ленте на 7 дней.", "цена не задана"},
        {"Закрепить в категории", "Первое место в подкатегории на 7 дней.", "цена не задана"},
        {"Подписка «Про»", "Больше фото, статистика просмотров, сниженная комиссия.", "цена не задана"},
    };

    static void openAds(Activity a) {
        Page p = page(a, "Реклама Ива", "Как продвигать «Иву» и где могут быть рекламные места. Пока план — включим по вашему решению.", false);
        p.list.addView(head(a, "ПРОДВИЖЕНИЕ И МЕСТА"));
        for (String[] x : ADS) p.list.addView(note(a, x[0], x[1], x[2]));
        p.list.addView(note(a, "Нужно решить", "Какие места включать, цены для партнёров, можно ли показывать рекламу клиентам.", ""));
    }

    static void openPaid(Activity a) {
        Page p = page(a, "Платный контент", "Платные услуги для арендодателей. Оплата в приложении пока не подключена.", false);
        p.list.addView(head(a, "УСЛУГИ"));
        for (String[] x : PAID) p.list.addView(note(a, x[0], x[1], x[2]));
        p.list.addView(note(a, "Нужно решить", "Цены, способ оплаты (как комиссия — переводом или онлайн-оплата) и кому доступно.", ""));
    }

    // ════════════════════════════════ 3. «Мои отзывы» ════════════════════════════════

    static final String[] REV_TABS = {"О моих инструментах", "Написал я"};

    /** Отзывы о моих инструментах: встроенный инструмент (tools!inner) с фильтром по владельцу. */
    static String qAboutMine(String email) {
        return "/rest/v1/reviews?select=" + IvaReviewPatch.REVIEW_COLS + ",tools!inner(name,owner_email)&tools.owner_email=eq."
            + IvaReviewPatch.enc(email) + "&order=created_at.desc&limit=200";
    }

    static String qByMe(String email) {
        return "/rest/v1/reviews?select=" + IvaReviewPatch.REVIEW_COLS + ",tools(name)&author_email=eq."
            + IvaReviewPatch.enc(email) + "&order=created_at.desc&limit=200";
    }

    static void openReviews(final Activity a) {
        final Page p = page(a, T_REVIEWS, "Скрытые отзывы видите только вы и автор.", true);
        showReviews(a, p, 0);
    }

    static void showReviews(final Activity a, final Page p, final int tab) {
        p.tabs.removeAllViews();
        for (int i = 0; i < REV_TABS.length; i++) {
            final int k = i;
            TextView c = IvaSearchPatch.chip(a, REV_TABS[i], i == tab, false);
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showReviews(a, p, k); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(a, 8);
            p.tabs.addView(c, lp);
        }
        String me = String.valueOf(IvaClientPatch.get(a, "userEmail")).trim().toLowerCase(Locale.ROOT);
        load(a, p, new String[]{tab == 0 ? qAboutMine(me) : qByMe(me)}, new Done() {
            @Override public void on(String[][] r) {
                List<Map<String, String>> rv = arr(r[0]);
                if (rv == null) { p.list.addView(hint(a, "Не удалось загрузить — проверьте интернет")); return; }
                int[] c = reviewCounts(rv);
                if (rv.isEmpty()) {
                    p.list.addView(hint(a, tab == 0 ? "О ваших инструментах отзывов пока нет" : "Вы ещё не писали отзывов"));
                    return;
                }
                List<String[]> s = new ArrayList<String[]>();
                s.add(new String[]{"Отзывов", String.valueOf(c[0])});
                s.add(new String[]{"Средняя оценка", c[3] > 0 ? "★ " + IvaReviewPatch.fmtRating(c[2] / (double) c[3]) : "—"});
                if (c[1] > 0) s.add(new String[]{"Скрыто", String.valueOf(c[1]), "warn"});
                p.list.addView(kv(a, s));
                for (Map<String, String> j : rv) p.list.addView(reviewCard(a, j, tab == 0));
            }
        });
    }

    /** Название инструмента из встроенного объекта tools. */
    static String toolName(Map<String, String> j) {
        String n = str(j, "tools.name");
        return n.isEmpty() ? "Инструмент" : n;
    }

    static View reviewCard(Activity a, Map<String, String> j, boolean aboutMine) {
        LinearLayout c = card(a);
        int rating = (int) num(j, "rating");
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView st = new TextView(a);
        st.setText(IvaReviewPatch.starText(a, IvaReviewPatch.stars(rating), color(a, "gray2", 0xff6b7080)));
        st.setTextSize(15f);
        top.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
        boolean hidden = !"published".equals(str(j, "status"));
        top.addView(text(a, hidden ? "Скрыт" : IvaReviewPatch.fmtDate(str(j, "created_at")), 12f,
            hidden ? color(a, "orange", 0xffff8f1f) : color(a, "gray", 0xff9aa1b0), hidden));
        c.addView(top);
        TextView tn = text(a, toolName(j), 14f, color(a, "text", 0xfff2f4f8), true);
        tn.setPadding(0, dp(a, 6), 0, 0);
        c.addView(tn);
        String body = str(j, "text");
        if (!body.isEmpty()) {
            TextView b = text(a, body, 14f, color(a, "text", 0xfff2f4f8), false);
            b.setPadding(0, dp(a, 4), 0, 0);
            c.addView(b);
        }
        String who = aboutMine ? str(j, "author_name") : "";
        if (!who.isEmpty() || hidden) {
            TextView w = text(a, (who.isEmpty() ? "" : who) + (hidden ? (who.isEmpty() ? "" : " · ") + IvaReviewPatch.fmtDate(str(j, "created_at")) : ""),
                12f, color(a, "gray", 0xff9aa1b0), false);
            w.setPadding(0, dp(a, 6), 0, 0);
            c.addView(w);
        }
        return c;
    }
}

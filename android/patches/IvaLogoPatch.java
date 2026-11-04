package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 10.113 — «Вид раздела с логотипом» (просьба владельца 17.10.2026).
 *
 * Админ («Настройки → Настройка проката», в самом низу) и сотрудник с правом «Выкладывает инструмент» («Кабинет →
 * Мой пункт», в самом низу): строка «Вид раздела с логотипом» → отдельная страница с двумя образцами экрана на выбор:
 *   1. «Как у клиентов» — вверху «Избранное» и «Мои инструменты» (раздел клиента, 10.43);
 *   2. «Как у админов» — вверху «Мой инструмент» и «Добавить инструмент».
 * Образцы нарисованы из цветов и элементов приложения (решение владельца: «образец из элементов приложения»).
 * По умолчанию — как было: у админа «как у админов», у сотрудника «как у клиентов». Выбор хранится на телефоне.
 *
 * Админ выбрал «как у клиентов» — свои инструменты и «Добавить инструмент» открываются строкой «Мои инструменты проката»
 * в «Кабинете» (решение владельца); выбрал обратно — строка пропадает.
 * Сотрудник выбрал «как у админов» — «Мой инструмент»: инструменты его пунктов (как «Инструменты пункта»),
 * «Добавить инструмент» — та же штатная форма, что в «Мой пункт» (решение владельца). Права — прежние, в базе.
 *
 * Новых вставок в smali нет: логотип — наш обработчик navPlus (IvaClientPatch.installClientNav, 10.43), строка в
 * «Кабинете» — из IvaStaffPatch.onShowTab, строки настроек — из IvaShopPatch.openPage и IvaStaffPatch.openMine.
 */
public class IvaLogoPatch {
    static final String TITLE = "Вид раздела с логотипом";
    static final String CLIENT = "client", ADMIN = "admin";
    static final String PAGE_SUB = "Что открывается по нажатию на логотип в нижнем меню. Выбор хранится на этом телефоне.";
    static final String[] NAMES = {"Как у клиентов", "Как у админов"};
    static final String[][] TABS = {{"Избранное", "Мои инструменты"}, {"Мой инструмент", "Добавить инструмент"}};
    static final String CAB_TAG = "iva_logo_cabinet_10_113";
    static final String CAB_TITLE = "Мои инструменты проката", CAB_SUB = "«Мой инструмент» и «Добавить инструмент»";
    static final String NOTE_ADMIN = "Если выбран вид «Как у клиентов», свои инструменты и «Добавить инструмент» — в «Кабинете», строка «"
        + CAB_TITLE + "».";
    static final String NOTE_STAFF = "Вид «Как у админов»: «Мой инструмент» — инструменты ваших пунктов, «Добавить инструмент» — та же форма, "
        + "что в «Мой пункт».";
    /** Что открывает логотип. */
    static final int NATIVE = 0, CLIENT_HUB = 1, STAFF_HUB = 2;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    /** Сохранённый выбор или вид по умолчанию: у админа — «как у админов», у остальных — «как у клиентов». */
    static String norm(String v, boolean admin) {
        String t = v == null ? "" : v.trim();
        if (CLIENT.equals(t) || ADMIN.equals(t)) return t;
        return admin ? ADMIN : CLIENT;
    }

    static int index(String view) { return ADMIN.equals(view) ? 1 : 0; }

    static String sub(String view) {
        int i = index(view);
        return NAMES[i] + ": «" + TABS[i][0] + "» и «" + TABS[i][1] + "»";
    }

    /** Логотип: гость — штатно; админ — штатный кабинет или раздел клиента; сотрудник «Каталог» — по выбору; клиент — раздел клиента. */
    static int target(boolean loggedIn, boolean admin, boolean staffCatalog, String view) {
        if (!loggedIn) return NATIVE;
        if (admin) return CLIENT.equals(view) ? CLIENT_HUB : NATIVE;
        return staffCatalog && ADMIN.equals(view) ? STAFF_HUB : CLIENT_HUB;
    }

    /** Строка «Мои инструменты проката» в «Кабинете» — только админу с видом «как у клиентов». */
    static boolean cabinetRow(boolean loggedIn, boolean admin, String view) { return loggedIn && admin && CLIENT.equals(view); }

    // ───────────── выбор на телефоне ─────────────

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_logo", 0); }

    static boolean admin(Activity a) { try { return IvaNotifyPatch.isAdmin(a); } catch (Throwable e) { return false; } }

    static boolean loggedIn(Activity a) { try { return IvaNotifyPatch.loggedIn(a); } catch (Throwable e) { return false; } }

    static String view(Activity a) {
        String me = IvaPickupPatch.me(a);
        return norm(me.isEmpty() ? "" : prefs(a).getString("v_" + me, ""), admin(a));
    }

    static void save(Activity a, String v) {
        String me = IvaPickupPatch.me(a);
        if (!me.isEmpty()) prefs(a).edit().putString("v_" + me, norm(v, admin(a))).apply();
    }

    /** Что открыть по нажатию на логотип (IvaClientPatch.installClientNav). */
    static int target(Activity a) {
        boolean st = false;
        try { st = IvaStaffToolsPatch.staff(a); } catch (Throwable ignore) {}
        return target(loggedIn(a), admin(a), st, view(a));
    }

    // ───────────── строка настроек и страница выбора ─────────────

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }

    /** В самом низу «Настройки проката» (админ) или «Мой пункт» (сотрудник «Каталог»). */
    static void settingsRow(final Activity a, LinearLayout list, final int cText, final int cGray, final int cOrange) {
        float den = a.getResources().getDisplayMetrics().density;
        final LinearLayout row = IvaShopPatch.row(a, list, new ScreenIcon(cOrange, den), TITLE, sub(view(a)), cText, cGray);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { openPage(a, row); } catch (Throwable ignore) {} }
        });
    }

    static void openPage(final Activity a, final LinearLayout row) {
        final int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        final int cLine = color(a, "line", 0xff262b35), cCard = color(a, "card", 0xff1a1d24);
        final boolean adm = admin(a);
        IvaChiefPatch.Page pg = IvaChiefPatch.page(a, TITLE, PAGE_SUB, false);
        LinearLayout pair = new LinearLayout(a);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout[] cols = new LinearLayout[2];
        final TextView[] marks = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final int k = i;
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 10));
            col.setClickable(true);
            View shot = new View(a);
            shot.setBackground(new Shot(a, k));
            shot.setContentDescription("Образец: " + NAMES[k]);
            col.addView(shot, new LinearLayout.LayoutParams(-1, dp(a, 250)));
            TextView t = IvaChiefPatch.text(a, NAMES[k], 15f, cText, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(a, 10), 0, 0);
            col.addView(t, new LinearLayout.LayoutParams(-1, -2));
            TextView s = IvaChiefPatch.text(a, "«" + TABS[k][0] + "» и «" + TABS[k][1] + "»", 12f, cGray, false);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(a, 2), 0, 0);
            col.addView(s, new LinearLayout.LayoutParams(-1, -2));
            TextView m = IvaChiefPatch.text(a, "", 13f, cOrange, true);
            m.setGravity(Gravity.CENTER);
            m.setPadding(0, dp(a, 8), 0, 0);
            col.addView(m, new LinearLayout.LayoutParams(-1, -2));
            marks[k] = m;
            cols[k] = col;
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (k == 0) cl.rightMargin = dp(a, 6); else cl.leftMargin = dp(a, 6);
            pair.addView(col, cl);
        }
        final Runnable paint = new Runnable() {
            @Override public void run() {
                int sel = index(view(a));
                for (int i = 0; i < 2; i++) {
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(cCard);
                    g.setCornerRadius(dp(a, 14));
                    g.setStroke(i == sel ? dp(a, 2) : Math.max(1, dp(a, 1)), i == sel ? cOrange : cLine);
                    cols[i].setBackground(g);
                    marks[i].setText(i == sel ? "✓ Выбрано" : "Выбрать");
                    marks[i].setTextColor(i == sel ? cOrange : cGray);
                }
            }
        };
        for (int i = 0; i < 2; i++) {
            final int k = i;
            cols[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        if (index(view(a)) == k) return;
                        save(a, k == 1 ? ADMIN : CLIENT);
                        paint.run();
                        TextView rs = IvaStaffToolsPatch.sub(row);
                        if (rs != null) rs.setText(sub(view(a)));
                        cabinet(a);
                        IvaStaffPatch.toast(a, "Сохранено: " + NAMES[k].toLowerCase(java.util.Locale.ROOT));
                    } catch (Throwable ignore) {}
                }
            });
        }
        paint.run();
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = dp(a, 4);
        pg.list.addView(pair, pl);
        TextView note = IvaChiefPatch.text(a, adm ? NOTE_ADMIN : NOTE_STAFF, 13f, cGray, false);
        note.setPadding(dp(a, 2), dp(a, 14), dp(a, 2), 0);
        pg.list.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    // ───────────── «Кабинет»: «Мои инструменты проката» у админа с видом «как у клиентов» ─────────────

    static WeakReference<View> cabRef;

    /** Из IvaStaffPatch.onShowTab("profile") и после выбора вида: строка над «Настройками» — есть или нет. */
    static void cabinet(final Activity a) {
        int id = a.getResources().getIdentifier("profHelp", "id", a.getPackageName());
        View help = id == 0 ? null : a.findViewById(id);
        if (help == null || !(help.getParent() instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) help.getParent();
        View old = box.findViewWithTag(CAB_TAG);
        boolean want = cabinetRow(loggedIn(a), admin(a), view(a));
        if (!want) { if (old != null) box.removeView(old); return; }
        if (old != null) return;
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        LinearLayout row = new LinearLayout(a);
        row.setTag(CAB_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) row.setBackground(bg);
        row.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        row.setClickable(true);
        ImageView ic = new ImageView(a);
        ic.setImageDrawable(new IvaShopPatch.Icon(IvaShopPatch.Icon.SHOP, cOrange, a.getResources().getDisplayMetrics().density));
        row.addView(ic, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(IvaChiefPatch.text(a, CAB_TITLE, 15f, cText, false));
        col.addView(IvaChiefPatch.text(a, CAB_SUB, 13f, cGray, false));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 14);
        row.addView(col, cl);
        ImageView chev = new ImageView(a);
        Drawable ch = IvaSearchPatch.drawable(a, "ic_chevron");
        if (ch != null) { chev.setImageDrawable(ch); chev.setColorFilter(cGray); row.addView(chev, new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18))); }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { IvaNotifyPatch.tab(a, "admhub"); } catch (Throwable ignore) {} }   // как штатный onPlus у админа
        });
        View settings = box.findViewWithTag(IvaNotifyPatch.ROW_TAG);
        int at = box.indexOfChild(settings != null && settings.getParent() == box ? settings : help);
        if (at < 0) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 12);
        box.addView(row, at, lp);
        cabRef = new WeakReference<View>(row);
    }

    // ───────────── рисунки ─────────────

    /** Значок строки: экран телефона с вкладками вверху и логотипом внизу (линией 2dp, цвет приложения). */
    static final class ScreenIcon extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScreenIcon(int color, float den) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * den);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override public void draw(Canvas c) {
            float l = getBounds().left, t = getBounds().top, w = getBounds().width(), h = getBounds().height();
            RectF r = new RectF(l + w * 0.22f, t + h * 0.06f, l + w * 0.78f, t + h * 0.94f);
            c.drawRoundRect(r, w * 0.1f, w * 0.1f, p);
            c.drawLine(r.left, t + h * 0.30f, r.right, t + h * 0.30f, p);
            c.drawLine(l + w * 0.5f, t + h * 0.12f, l + w * 0.5f, t + h * 0.30f, p);
            c.drawCircle(l + w * 0.5f, t + h * 0.78f, w * 0.07f, p);
        }
        @Override public void setAlpha(int al) { p.setAlpha(al); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Образец экрана: вкладки вверху (как у клиента / админа), карточки и нижнее меню с выбранным логотипом. */
    static final class Shot extends Drawable {
        final int kind, bg, card, line, text, gray, orange;
        final float d;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), font = new Paint(Paint.ANTI_ALIAS_FLAG);

        Shot(Activity a, int kind) {
            this.kind = kind;
            bg = color(a, "bg", 0xff0f1115);
            card = color(a, "card", 0xff1a1d24);
            line = color(a, "line", 0xff262b35);
            text = color(a, "text", 0xfff2f4f8);
            gray = color(a, "gray", 0xff9aa1b0);
            orange = color(a, "orange", 0xffff8f1f);
            d = a.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, d));
            font.setTextAlign(Paint.Align.CENTER);
        }

        RectF rr(float l, float t, float r, float b) { return new RectF(l, t, r, b); }

        void box(Canvas c, RectF r, float rad, int f, int s) {
            fill.setColor(f);
            c.drawRoundRect(r, rad, rad, fill);
            if (s != 0) { stroke.setColor(s); c.drawRoundRect(r, rad, rad, stroke); }
        }

        void bar(Canvas c, float l, float t, float w, float h, int col) {
            fill.setColor(col);
            c.drawRoundRect(rr(l, t, l + w, t + h), h / 2f, h / 2f, fill);
        }

        /** Подпись вкладки: уменьшается, пока не влезет в половину. */
        void label(Canvas c, String s, float cx, float base, float maxW, boolean on) {
            float size = 9.5f * d;
            font.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            font.setTextSize(size);
            float w = font.measureText(s);
            if (w > maxW && w > 0) font.setTextSize(Math.max(5f * d, size * maxW / w));
            font.setColor(on ? orange : gray);
            c.drawText(s, cx, base, font);
        }

        @Override public void draw(Canvas c) {
            float L = getBounds().left, T = getBounds().top, W = getBounds().width(), H = getBounds().height();
            float m = 7 * d;
            box(c, rr(L, T, L + W, T + H), 12 * d, bg, line);
            // вкладки: карточка, сверху закруглена, подчёркивание выбранной (как tabCard / штатные hubTabMy)
            float tl = L + m, tr = L + W - m, tt = T + m, tb = tt + 30 * d, half = (tr - tl) / 2f;
            Path tab = new Path();
            float rad = 8 * d;
            tab.addRoundRect(rr(tl, tt, tr, tb), new float[]{rad, rad, rad, rad, 0, 0, 0, 0}, Path.Direction.CW);
            fill.setColor(card);
            c.drawPath(tab, fill);
            stroke.setColor(line);
            c.drawPath(tab, stroke);
            String[] names = TABS[kind];
            label(c, names[0], tl + half / 2f, tt + 17 * d, half - 6 * d, true);
            label(c, names[1], tl + half * 1.5f, tt + 17 * d, half - 6 * d, false);
            fill.setColor(orange);
            c.drawRect(tl + half * 0.15f, tb - 2.5f * d, tl + half * 0.85f, tb, fill);
            // нижнее меню: пять значков, логотип в середине выбран
            float nb = T + H - 1, nt = nb - 30 * d;
            box(c, rr(L + 1, nt, L + W - 1, nb), 0, card, 0);
            stroke.setColor(line);
            c.drawLine(L + 1, nt, L + W - 1, nt, stroke);
            for (int i = 0; i < 5; i++) {
                float cx = L + W * (i + 0.5f) / 5f, cy = (nt + nb) / 2f;
                if (i == 2) { fill.setColor(orange); c.drawCircle(cx, cy, 8 * d, fill); }
                else { stroke.setColor(gray); c.drawCircle(cx, cy, 4.5f * d, stroke); }
            }
            float ct = tb + 9 * d, cb = nt - 7 * d;
            if (kind == 0) {
                // как у клиентов: карточки каталога по две в ряд (фото, название, цена, сердечко)
                float gw = (tr - tl - 6 * d) / 2f, gh = Math.min(108 * d, (cb - ct - 6 * d) / 2f);
                for (int i = 0; i < 4; i++) {
                    float x = tl + (i % 2) * (gw + 6 * d), y = ct + (i / 2) * (gh + 6 * d);
                    if (y + gh > cb) break;
                    box(c, rr(x, y, x + gw, y + gh), 8 * d, card, line);
                    box(c, rr(x + 4 * d, y + 4 * d, x + gw - 4 * d, y + gh * 0.56f), 6 * d, line, 0);
                    stroke.setColor(orange);
                    c.drawCircle(x + gw - 11 * d, y + 11 * d, 4 * d, stroke);
                    bar(c, x + 6 * d, y + gh * 0.64f, gw * 0.7f, 5 * d, gray);
                    bar(c, x + 6 * d, y + gh * 0.80f, gw * 0.4f, 5 * d, orange);
                }
            } else {
                // как у админов: список своих инструментов строками (фото слева, название, цена, статус)
                float rh = 44 * d;
                for (int i = 0; i < 4; i++) {
                    float y = ct + i * (rh + 6 * d);
                    if (y + rh > cb) break;
                    box(c, rr(tl, y, tr, y + rh), 8 * d, card, line);
                    box(c, rr(tl + 5 * d, y + 5 * d, tl + 5 * d + rh - 10 * d, y + rh - 5 * d), 6 * d, line, 0);
                    float x = tl + rh + 2 * d;
                    bar(c, x, y + 11 * d, (tr - x) * 0.62f, 5 * d, gray);
                    bar(c, x, y + 25 * d, (tr - x) * 0.34f, 5 * d, orange);
                    bar(c, tr - 22 * d, y + 11 * d, 15 * d, 5 * d, text);
                }
            }
        }

        @Override public void setAlpha(int al) { fill.setAlpha(al); stroke.setAlpha(al); font.setAlpha(al); }
        @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); stroke.setColorFilter(cf); font.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ───────────── 10.130: надпись в шапке (просьба владельца 04.11.2026) ─────────────
    // Вместо обычного текста «Ива-Инструмент в аренду» рядом с круглым логотипом — надпись с наклейки v15 «Контур-скорость»
    // (promo/decal/files/iva-steklo-15-kontur-skorost.pdf, без чёрной подложки, с «скоростными» линиями слева).
    // Ресурсы сборки не пересобираются — PNG (960×69, палитра, ~7,6 КБ) здесь, в коде. Оранжевый — цвет проката
    // (у пробной версии — её цвет). Не получилось — остаётся штатный текст.

    static final String HEADER_TEXT = "Ива-Инструмент в аренду", HEADER_TAG = "iva_header_v15";
    static final int HEADER_ORANGE = 0xffff8f1f, HEADER_MAX_DP = 22;
    static final String HEADER_PNG =
        "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" +
        "Wz5dpQAAAABJRU5ErkJggg==";

    /** Из IvaPatch.onCreated: в шапке главного экрана (scrMain) текст → надпись. */
    static void header(Activity a) {
        int id = a.getResources().getIdentifier("scrMain", "id", a.getPackageName());
        View scr = id == 0 ? null : a.findViewById(id);
        if (!(scr instanceof android.view.ViewGroup) || ((android.view.ViewGroup) scr).getChildCount() == 0) return;
        View top = ((android.view.ViewGroup) scr).getChildAt(0);
        if (top.findViewWithTag(HEADER_TAG) != null) return;
        TextView tv = findText(top, HEADER_TEXT, 3);
        if (tv == null || !(tv.getParent() instanceof LinearLayout)) return;
        android.graphics.Bitmap bm = headerBitmap(IvaClientPatch.navColor(a, "orange", HEADER_ORANGE));
        if (bm == null) return;
        ImageView iv = new ImageView(a);
        iv.setTag(HEADER_TAG);
        iv.setImageBitmap(bm);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_START);
        iv.setMaxHeight((int) (HEADER_MAX_DP * a.getResources().getDisplayMetrics().density + 0.5f));
        iv.setContentDescription(HEADER_TEXT);
        LinearLayout col = (LinearLayout) tv.getParent();
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(iv, col.indexOfChild(tv), new LinearLayout.LayoutParams(-1, -2));
        tv.setVisibility(View.GONE);                       // штатный текст не удаляем — только прячем
    }

    static TextView findText(View v, String text, int depth) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        if (depth <= 0 || !(v instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            TextView t = findText(g.getChildAt(i), text, depth - 1);
            if (t != null) return t;
        }
        return null;
    }

    /** Надпись; оранжевые точки — в цвет проката (белые и прозрачные не трогаем). */
    static android.graphics.Bitmap headerBitmap(int accent) {
        try {
            byte[] png = android.util.Base64.decode(HEADER_PNG, android.util.Base64.DEFAULT);
            android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (src == null || (accent | 0xff000000) == HEADER_ORANGE) return src;
            android.graphics.Bitmap bm = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) px[i] = recolor(px[i], accent);
            bm.setPixels(px, 0, w, 0, 0, w, h);
            return bm;
        } catch (Throwable e) { return null; }
    }

    /** Чистая логика: точка оранжевая (красный заметно больше синего) — цвет проката с той же прозрачностью. */
    static int recolor(int argb, int accent) {
        int al = argb >>> 24, r = (argb >> 16) & 0xff, b = argb & 0xff;
        if (al == 0 || r - b < 60) return argb;
        return (al << 24) | (accent & 0x00ffffff);
    }
}

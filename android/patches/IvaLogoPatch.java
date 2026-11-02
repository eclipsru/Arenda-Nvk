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
}

// 10.131 (просьба владельца 04.11.2026): «Стрелка оповещений». У сотрудника и у владельца проката (админа) на каждом
// входе в раздел, где появилось новое сообщение или заявка, — небольшая стрелка с числом новых. Клиентам — нет.
// Где: нижнее меню «Чат» (непрочитанные), «Заявки» (владельцу — новые заявки; сотруднику в виде с логотипом — новые
// заявки пункта), «Кабинет» сотрудника (новое в пункте) → строка «Мой пункт» → плитки «Заявки пункта» и «Чат пункта».
// Числа — из того же опроса уведомлений (раз в 30 с, IvaNotifyPatch.tick) и с открытых страниц пункта; после прочтения
// стрелка гаснет при следующем пересчёте (смена раздела, открытие списка — не чаще раза в 10 с, в фоне).
// «Настройки» → «Стрелка оповещений»: выключатель и 4 цвета (кислотно-зелёная, оранжевая, жёлтая, красная);
// хранится на телефоне. Стрелка рисуется поверх раздела (ViewOverlay) — разметка и нажатия не меняются.
package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class IvaArrowPatch {
    private IvaArrowPatch() {}

    static final String TITLE = "Стрелка оповещений";
    static final String SUB = "Стрелка с числом новых сообщений и заявок у раздела, где они появились";
    static final String K_ON = "arrow_on", K_COLOR = "arrow_color";   // в настройках уведомлений на телефоне (iva_notify)
    static final int[] COLORS = {0xffa6ff00, 0xffff8f1f, 0xffffea00, 0xffff3b30};
    static final String[] NAMES = {"Кислотно-зелёная", "Оранжевая", "Жёлтая", "Красная"};
    static final int DEF_COLOR = 1;                                   // по умолчанию — оранжевая, цвет приложения

    // что считаем
    static final int MSG = 0, ORD = 1, ST_CHAT = 2, ST_ORD = 3;
    // на чём стрелка (кроме нижнего меню)
    static final int S_ORD = 10, S_CHAT = 11, S_MINE = 12;
    static final String[] NAV = {"chat", "orders", "profile"};
    static final String[] NAV_ID = {"navChat", "navOrders", "navProfile"};
    static final String[] NAV_IC = {"navChatIc", "navOrdersIc", "navProfIc"};

    static final int[] cnt = new int[4];
    static final long RECOUNT_MS = 10000L;
    static volatile long recountAt;
    static volatile String who = "";
    static WeakReference<Activity> act;
    static final Map<View, Integer> marks = new WeakHashMap<View, Integer>();
    static final Map<View, View> anchors = new WeakHashMap<View, View>();
    static final Map<View, Tag> tags = new WeakHashMap<View, Tag>();
    static Handler main;                                              // создаётся при первом числе (в тестах Android нет)

    // ---------- чистые функции (IvaPatchTest st131) ----------

    /** Число на стрелке: 0 — стрелки нет, больше 99 — «99+». */
    static String label(int n) { return n <= 0 ? "" : n > 99 ? "99+" : String.valueOf(n); }

    static int colorAt(int i) { return COLORS[i >= 0 && i < COLORS.length ? i : DEF_COLOR]; }

    static String colorName(int i) { return NAMES[i >= 0 && i < NAMES.length ? i : DEF_COLOR]; }

    /** Число для кнопки нижнего меню. Клиенту — 0 (стрелки только сотруднику и владельцу). */
    static int forNav(String nav, boolean admin, boolean staff, boolean hub, int[] c) {
        if (c == null || c.length < 4 || (!admin && !staff)) return 0;
        if ("chat".equals(nav)) return Math.max(0, c[MSG]);
        if ("orders".equals(nav)) return admin ? Math.max(0, c[ORD]) : hub ? Math.max(0, c[ST_ORD]) : 0;
        if ("profile".equals(nav)) return staff && !admin ? Math.max(0, c[ST_CHAT]) + Math.max(0, c[ST_ORD]) : 0;
        return 0;
    }

    /** Число для плитки / строки сотрудника. */
    static int forKind(int kind, int[] c) {
        if (c == null || c.length < 4) return 0;
        if (kind == S_ORD) return Math.max(0, c[ST_ORD]);
        if (kind == S_CHAT) return Math.max(0, c[ST_CHAT]);
        if (kind == S_MINE) return Math.max(0, c[ST_CHAT]) + Math.max(0, c[ST_ORD]);
        return 0;
    }

    /** Подпись строки в «Настройках». */
    static String summary(boolean on, int color) { return on ? "Включена · " + colorName(color) : "Выключена"; }

    /** Левая граница стрелки: справа от значка, но целиком внутри раздела. */
    static int placeLeft(int anchorRight, int gap, int arrowW, int viewW, int edge) {
        int l = anchorRight + gap;
        int max = viewW - arrowW - edge;
        if (l > max) l = max;
        return l < 0 ? 0 : l;
    }

    // ---------- настройки ----------

    static SharedPreferences prefs(Context c) { return IvaNotifyPatch.prefs(c); }

    static boolean on(Context c) {
        try { return prefs(c).getBoolean(K_ON, true); } catch (Throwable e) { return true; }
    }

    static int colorIdx(Context c) {
        try { int i = prefs(c).getInt(K_COLOR, DEF_COLOR); return i >= 0 && i < COLORS.length ? i : DEF_COLOR; } catch (Throwable e) { return DEF_COLOR; }
    }

    /** Сотрудник любого пункта (по кэшу «Мой пункт», без запроса в базу). */
    static boolean staffAny(Activity a) {
        try {
            IvaStaffPatch.Me m = IvaStaffToolsPatch.cached(a);
            return m != null && "active".equals(m.status);
        } catch (Throwable e) { return false; }
    }

    // ---------- числа ----------

    /** Новое число (из опроса или со страницы). Меняется — стрелки перерисовываются на главном потоке. */
    static void setCount(Context c, int which, int n) {
        if (which < 0 || which >= cnt.length) return;
        int v = n < 0 ? 0 : n;
        synchronized (cnt) {
            if (cnt[which] == v) return;
            cnt[which] = v;
        }
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override public void run() {
                Activity a = act == null ? null : act.get();
                if (a == null && c instanceof Activity) a = (Activity) c;
                if (a != null) try { refresh(a); } catch (Throwable ignore) {}
            }
        });
    }

    static int[] snapshot() { synchronized (cnt) { return cnt.clone(); } }

    static boolean any() { int[] c = snapshot(); for (int x : c) if (x > 0) return true; return false; }

    /** Пересчёт вне очереди: те же проверки, что в опросе (заявки, сообщения, пункт). Не чаще раза в 10 с, в фоне,
     *  и только когда стрелка что-то показывает (новое и так приходит с опросом). */
    static void recount(final Activity a, boolean force) {
        if (a == null || a.isFinishing()) return;
        long now = System.currentTimeMillis();
        if (!force && (now - recountAt < RECOUNT_MS || !any())) return;
        if (IvaNotifyPatch.busy || !IvaNotifyPatch.loggedIn(a)) return;
        final boolean admin = IvaNotifyPatch.isAdmin(a);
        Object em = IvaNotifyPatch.get(a, "userEmail");
        final String email = em instanceof String ? ((String) em).trim() : "";
        if (email.isEmpty()) return;
        Object ct = IvaNotifyPatch.get(a, "curTab"), cw = IvaNotifyPatch.get(a, "chatWith");
        final String openWith = "chat".equals(ct) && a.hasWindowFocus() ? (cw instanceof String ? ((String) cw).trim() : "") : null;
        recountAt = now;
        IvaNotifyPatch.busy = true;                      // опрос этот круг пропустит — без двойных уведомлений
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (admin) try { IvaNotifyPatch.checkOrders(a, email); } catch (Throwable ignore) {}
                    try { IvaNotifyPatch.checkMessages(a, email, admin, openWith); } catch (Throwable ignore) {}
                    if (!admin) try { IvaStaffOrdersPatch.check(a, email); } catch (Throwable ignore) {}
                    if (!admin) try { IvaStaffChatPatch.check(a, email); } catch (Throwable ignore) {}
                } finally {
                    IvaNotifyPatch.busy = false;
                }
            }
        }).start();
    }

    // ---------- где стрелка ----------

    /** Плитка / строка, на которой показывать стрелку; anchor — значок, справа от которого она встанет (null — у правого края). */
    static void mark(Activity a, View v, View anchor, int kind) {
        if (v == null) return;
        marks.put(v, kind);
        if (anchor != null) anchors.put(v, anchor);
        try { refresh(a); } catch (Throwable ignore) {}
    }

    /** Смена раздела (IvaPatch.onTab): перерисовать и, если что-то показано, пересчитать. */
    static void onTab(Activity a) {
        try { refresh(a); } catch (Throwable ignore) {}
        try { recount(a, false); } catch (Throwable ignore) {}
    }

    /** Перерисовать все стрелки (главный поток). */
    static void refresh(Activity a) {
        if (a == null) return;
        act = new WeakReference<Activity>(a);
        boolean admin = false, staff = false, hub = false;
        String email = "";
        try {
            if (IvaNotifyPatch.loggedIn(a)) {
                admin = IvaNotifyPatch.isAdmin(a);
                staff = !admin && staffAny(a);
                Object em = IvaNotifyPatch.get(a, "userEmail");
                email = em instanceof String ? ((String) em).trim().toLowerCase() : "";
            }
        } catch (Throwable ignore) {}
        if (!email.equals(who)) {                        // вышли или вошёл другой — чужие числа не показываем
            who = email;
            synchronized (cnt) { for (int i = 0; i < cnt.length; i++) cnt[i] = 0; }
        }
        try { hub = staff && IvaStaffToolsPatch.hubMode(a); } catch (Throwable ignore) {}
        boolean show = (admin || staff) && on(a);
        int col = colorAt(colorIdx(a));
        int[] c = snapshot();
        for (int i = 0; i < NAV.length; i++) {
            View v = navItem(a, NAV_ID[i]);
            Object ic = IvaNotifyPatch.get(a, NAV_IC[i]);
            View anchor = ic instanceof View && ((View) ic).getParent() instanceof View ? (View) ((View) ic).getParent() : null;
            put(a, v, anchor, show ? forNav(NAV[i], admin, staff, hub, c) : 0, col, true);
        }
        for (Map.Entry<View, Integer> e : new ArrayList<Map.Entry<View, Integer>>(marks.entrySet())) {
            View v = e.getKey();
            if (v == null) continue;
            put(a, v, anchors.get(v), show ? forKind(e.getValue(), c) : 0, col, false);
        }
    }

    static View navItem(Activity a, String name) {
        try {
            int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
            return id == 0 ? null : a.findViewById(id);
        } catch (Throwable e) { return null; }
    }

    static final class Tag implements View.OnLayoutChangeListener {
        Arrow d;
        View anchor;
        boolean nav;
        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            try { place(v, this); } catch (Throwable ignore) {}
        }
    }

    static void put(Activity a, View v, View anchor, int n, int color, boolean nav) {
        if (v == null) return;
        Tag t = tags.get(v);
        if (n <= 0) {
            if (t != null) {
                try { v.getOverlay().remove(t.d); } catch (Throwable ignore) {}
                v.removeOnLayoutChangeListener(t);
                tags.remove(v);
                v.invalidate();
            }
            return;
        }
        if (t == null) {
            t = new Tag();
            t.d = new Arrow(a.getResources().getDisplayMetrics().density);
            tags.put(v, t);
            v.getOverlay().add(t.d);
            v.addOnLayoutChangeListener(t);
        }
        t.anchor = anchor;
        t.nav = nav;
        t.d.set(label(n), color);
        place(v, t);
        v.invalidate();
    }

    /** Справа от значка, по его середине (значка нет — у середины кнопки меню или у правого края строки, перед «›»). */
    static void place(View v, Tag t) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        float dd = t.d.dens;
        int aw = t.d.width(), ah = t.d.height();
        int left, top;
        int[] r = t.anchor == null || t.anchor.getWidth() <= 0 ? null : rel(t.anchor, v);
        if (r != null) {
            left = placeLeft(r[0] + t.anchor.getWidth(), Math.round(2 * dd), aw, w, Math.round(2 * dd));
            top = r[1] + (t.anchor.getHeight() - ah) / 2;
        } else if (t.nav) {                              // значок кнопки меню скрыт — у середины кнопки
            left = placeLeft(w / 2 + Math.round(8 * dd), 0, aw, w, Math.round(2 * dd));
            top = Math.round(4 * dd);
        } else {
            left = placeLeft(w - Math.round(46 * dd) - aw, 0, aw, w, Math.round(2 * dd));
            top = (h - ah) / 2;
        }
        if (top > h - ah) top = h - ah;
        if (top < 0) top = 0;
        t.d.setBounds(left, top, left + aw, top + ah);
    }

    /** Положение потомка внутри раздела; null — не внутри. */
    static int[] rel(View child, View root) {
        int x = 0, y = 0;
        View c = child;
        while (c != null && c != root) {
            x += c.getLeft() - c.getScrollX();
            y += c.getTop() - c.getScrollY();
            ViewParent p = c.getParent();
            c = p instanceof View ? (View) p : null;
        }
        return c == root ? new int[]{x, y} : null;
    }

    /** Стрелка влево (на раздел) с числом: тёмная, рамка и число — выбранным цветом (как кружки непрочитанного, 10.96). */
    static final class Arrow extends Drawable {
        final float dens;
        String text = "";
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        Arrow(float density) {
            dens = density <= 0 ? 1f : density;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xff101217);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * dens);
            line.setStrokeJoin(Paint.Join.ROUND);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(10.5f * dens);
            txt.setFakeBoldText(true);
            txt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        void set(String t, int color) {
            text = t == null ? "" : t;
            line.setColor(color);
            txt.setColor(color);
            invalidateSelf();
        }

        int tail() { return Math.round(6 * dens); }
        int width() { return tail() + Math.round(Math.max(18 * dens, txt.measureText(text) + 10 * dens)); }
        int height() { return Math.round(16 * dens); }
        @Override public int getIntrinsicWidth() { return width(); }
        @Override public int getIntrinsicHeight() { return height(); }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = line.getStrokeWidth() / 2f;
            float l = b.left + s, t = b.top + s, r = b.right - s, bt = b.bottom - s;
            float tl = l + tail(), cy = (t + bt) / 2f, rad = 4 * dens;
            Path p = new Path();
            p.addRoundRect(new RectF(tl, t, r, bt), rad, rad, Path.Direction.CW);
            Path tri = new Path();
            tri.moveTo(tl + rad, cy - 5 * dens);
            tri.lineTo(l, cy);
            tri.lineTo(tl + rad, cy + 5 * dens);
            tri.close();
            try { p.op(tri, Path.Op.UNION); } catch (Throwable e) { p.addPath(tri); }
            c.drawPath(p, fill);
            c.drawPath(p, line);
            Paint.FontMetrics fm = txt.getFontMetrics();
            c.drawText(text, (tl + r) / 2f, cy - (fm.ascent + fm.descent) / 2f, txt);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); line.setAlpha(alpha); txt.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------- «Настройки» → «Стрелка оповещений» ----------

    /** Строка в «Настройках» (под «Уведомлениями») — только сотруднику и владельцу; по нажатию — своя страница. */
    static void settingsRow(final Activity a, LinearLayout page, final int cText, final int cGray, final int cOrange) {
        if (a == null || page == null || !IvaNotifyPatch.loggedIn(a)) return;
        if (!IvaNotifyPatch.isAdmin(a) && !staffAny(a)) return;
        float dens = a.getResources().getDisplayMetrics().density;
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Drawable hbg = IvaSearchPatch.drawable(a, "bg_card");
        if (hbg != null) head.setBackground(hbg);
        head.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        head.setClickable(true);
        final ImageView icon = new ImageView(a);
        final Arrow preview = new Arrow(dens);
        preview.set("1", colorAt(colorIdx(a)));
        icon.setImageDrawable(preview);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        head.addView(icon, new LinearLayout.LayoutParams(dp(a, 26), dp(a, 22)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(a);
        title.setText(TITLE);
        title.setTextSize(15f);
        title.setTextColor(cText);
        col.addView(title);
        final TextView sub = new TextView(a);
        sub.setTextSize(13f);
        sub.setTextColor(cGray);
        sub.setText(summary(on(a), colorIdx(a)));
        col.addView(sub);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 10);
        head.addView(col, cl);
        ImageView chev = new ImageView(a);
        Drawable chd = IvaSearchPatch.drawable(a, "ic_chevron");
        if (chd != null) { chev.setImageDrawable(chd); chev.setColorFilter(cGray); }
        head.addView(chev, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(a, 10);
        page.addView(head, hl);
        head.setContentDescription(TITLE + ", открыть");
        final Runnable onChange = new Runnable() {
            @Override public void run() {
                sub.setText(summary(on(a), colorIdx(a)));
                preview.set("1", colorAt(colorIdx(a)));
                icon.invalidate();
                try { refresh(a); } catch (Throwable ignore) {}
            }
        };
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { IvaChiefPatch.subPage(a, TITLE, settingsBody(a, cText, cGray, cOrange, onChange), null); } catch (Throwable ignore) {}
            }
        });
    }

    /** Страница: выключатель и выбор цвета (выбранный — галочкой цвета приложения). */
    static View settingsBody(final Activity a, int cText, final int cGray, final int cOrange, final Runnable onChange) {
        float dens = a.getResources().getDisplayMetrics().density;
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) card.setBackground(bg);
        card.setPadding(dp(a, 16), dp(a, 6), dp(a, 12), dp(a, 6));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(a, 8);
        body.addView(card, cl);
        final Switch sw = IvaNotifyPatch.switchRow(a, card, TITLE, SUB, K_ON, cText, cGray, cOrange);

        body.addView(IvaNotifyPatch.label(a, "Цвет стрелки", cGray));
        final LinearLayout colors = new LinearLayout(a);
        colors.setOrientation(LinearLayout.VERTICAL);
        Drawable bg2 = IvaSearchPatch.drawable(a, "bg_card");
        if (bg2 != null) colors.setBackground(bg2);
        colors.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 4));
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, -2);
        kl.topMargin = dp(a, 8);
        body.addView(colors, kl);
        final List<TextView> checks = new ArrayList<TextView>();
        final Runnable redraw = new Runnable() {
            @Override public void run() {
                boolean en = on(a);
                int sel = colorIdx(a);
                for (int i = 0; i < checks.size(); i++) checks.get(i).setText(i == sel ? "✓" : "");
                colors.setAlpha(en ? 1f : 0.45f);
                for (int i = 0; i < colors.getChildCount(); i++) colors.getChildAt(i).setEnabled(en);
                if (onChange != null) onChange.run();
            }
        };
        for (int i = 0; i < COLORS.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 12), 0, dp(a, 12));
            row.setClickable(true);
            ImageView pv = new ImageView(a);
            Arrow ar = new Arrow(dens);
            ar.set("3", COLORS[i]);
            pv.setImageDrawable(ar);
            pv.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(pv, new LinearLayout.LayoutParams(dp(a, 30), dp(a, 20)));
            TextView name = new TextView(a);
            name.setText(NAMES[i]);
            name.setTextSize(15f);
            name.setTextColor(cText);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
            nl.leftMargin = dp(a, 12);
            row.addView(name, nl);
            TextView ck = new TextView(a);
            ck.setTextSize(17f);
            ck.setTextColor(cOrange);
            ck.setTypeface(null, android.graphics.Typeface.BOLD);
            checks.add(ck);
            row.addView(ck, new LinearLayout.LayoutParams(dp(a, 24), -2));
            row.setContentDescription(NAMES[i]);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!on(a)) return;
                    try { prefs(a).edit().putInt(K_COLOR, idx).apply(); } catch (Throwable ignore) {}
                    redraw.run();
                }
            });
            colors.addView(row);
        }
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                IvaNotifyPatch.setPref(a, K_ON, v);
                redraw.run();
            }
        });
        TextView hint = new TextView(a);
        hint.setText("Стрелка показывает, сколько новых сообщений и заявок ждёт в разделе: в нижнем меню, в кабинете и на плитках «Моего пункта». Гаснет, когда всё прочитано.");
        hint.setTextSize(13f);
        hint.setTextColor(cGray);
        hint.setPadding(dp(a, 4), dp(a, 14), dp(a, 4), 0);
        body.addView(hint);
        redraw.run();
        return body;
    }

    static int dp(Activity a, float v) { return IvaSearchPatch.dp(a, v); }
}

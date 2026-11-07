package ru.prokatnvsk.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 10.140 — «Обращения» и «Написать в чат» в заявках (просьба владельца 07.11.2026; решения: сообщения — один клиент
 * в день = одно обращение; звонки — нажатия «Позвонить» в приложении и на сайте; неделя — с понедельника, месяц —
 * с 1-го числа, по московскому времени).
 *
 *  * Владелец проката: в «Кабинете» под «Настройкой проката» — строка «Обращения». Нажатие — отдельная страница:
 *    вверху «Пункт выдачи» с выпадающим списком («Все пункты» и пункты этого проката), под ним окошко со счётчиком
 *    обращений — сколько всего и отдельно заявок, звонков, сообщений — и выбор «Сегодня / Неделя / Месяц / Всё время».
 *    Числа считает база (SQL 20261128_appeals.sql, iva_appeals) — только числа, без имён, телефонов и текстов.
 *    Нет SQL — страница пишет, какой файл выполнить.
 *  * «Позвонить» клиента (карточка инструмента, страница проката, заявка) — фоном iva_call_tap: прокат и пункт
 *    база определяет сама, пишет «прокат, пункт, приложение, время». Сам звонок приложение не видит — считаем нажатие.
 *  * Заявки у владельца: на карточке рядом со штатной «Позвонить: …» — «Написать в чат» (переписка с клиентом заявки,
 *    как «Написать» в подробностях). Клиент оформил заявку без входа — почты нет, кнопки нет (только звонок).
 *  Новых вставок в smali нет: вызовы — из IvaClientPatch.onProfile (строка кабинета), IvaOrderPatch.admMore (кнопка
 *  на карточке) и обработчиков «Позвонить» в IvaProkatPatch / IvaOrderPatch.
 */
final class IvaAppealsPatch {
    private IvaAppealsPatch() {}

    static final String RPC = "/rest/v1/rpc/iva_appeals", TAP_RPC = "/rest/v1/rpc/iva_call_tap";
    static final String TITLE = "Обращения", ROW_SUB = "Заявки, звонки и сообщения по пунктам", CAB_TAG = "iva_appeals_cab_row";
    static final String ALL = "Все пункты", POINT_LABEL = "Пункт выдачи";
    static final String[] PERIODS = {"Сегодня", "Неделя", "Месяц", "Всё время"};
    static final String[] KEYS = {"day", "week", "month", "all"};
    static final String[] KINDS = {"orders", "calls", "msgs"};
    static final String[] KIND_T = {"Заявки", "Звонки", "Сообщения"};
    static final String NO_DB = "«Обращения» заработают после обновления базы: выполните в Supabase файл 20261128_appeals.sql.";
    static final String FAIL = "Не удалось загрузить — проверьте интернет и откройте ещё раз";
    static final String NOTE = "Заявки — одна заявка = одно обращение. Звонки — нажатия «Позвонить» в приложении и на сайте "
        + "(сам звонок не виден; считаются с обновления 10.140). Сообщения — клиент написал прокату: один клиент за день = "
        + "одно обращение, свои сотрудники не считаются. Время — московское.";
    static final String CHAT_TAG = "iva_adm_chat_btn", CHAT_TEXT = "Написать в чат";
    static final long TAP_PAUSE_MS = 60_000L;

    // ════════════════════════════════ чистая логика (IvaPatchTest) ════════════════════════════════

    static boolean uuid(String s) { return IvaToolPointPatch.uuid(s); }

    /** {"p_point":"…"} — пункт; не uuid — все пункты: {"p_point":null}. */
    static String body(String point) {
        String p = point == null ? "" : point.trim().toLowerCase(Locale.ROOT);
        return uuid(p) ? "{\"p_point\":\"" + p + "\"}" : "{\"p_point\":null}";
    }

    /** Нажатие «Позвонить»: пункт, инструмент, почта проката; ничего нет — null (не шлём). */
    static String tapBody(String point, String tool, String owner) {
        String p = point == null ? "" : point.trim().toLowerCase(Locale.ROOT), t = tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
        String o = owner == null ? "" : owner.trim().toLowerCase(Locale.ROOT);
        if (!o.matches("[^@\\s\"\\\\]+@[^@\\s\"\\\\]+\\.[^@\\s\"\\\\]{2,}")) o = "";
        if (!uuid(p)) p = "";
        if (!uuid(t)) t = "";
        if (p.isEmpty() && t.isEmpty() && o.isEmpty()) return null;
        return "{\"p_point\":" + (p.isEmpty() ? "null" : "\"" + p + "\"") + ",\"p_tool\":" + (t.isEmpty() ? "null" : "\"" + t + "\"")
            + ",\"p_owner\":" + (o.isEmpty() ? "null" : "\"" + o + "\"") + ",\"p_src\":\"app\"}";
    }

    /** Ответ iva_appeals → [вид: заявки, звонки, сообщения][период: день, неделя, месяц, всё]; плохой ответ — null.
     *  Без org.json (как feeRpcParse): объект или обёртка {"iva_appeals":{…}} / [{…}] — ищем «"orders":{…}» и т. п. */
    static int[][] parse(String json) {
        if (json == null) return null;
        int[][] r = new int[KINDS.length][KEYS.length];
        for (int k = 0; k < KINDS.length; k++) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + KINDS[k] + "\"\\s*:\\s*\\{([^{}]*)\\}").matcher(json);
            if (!m.find()) return null;
            String in = m.group(1);
            for (int p = 0; p < KEYS.length; p++) {
                java.util.regex.Matcher n = java.util.regex.Pattern.compile("\"" + KEYS[p] + "\"\\s*:\\s*(-?\\d{1,9})").matcher(in);
                if (!n.find()) return null;
                r[k][p] = Math.max(0, Integer.parseInt(n.group(1)));
            }
        }
        return r;
    }

    /** Всего обращений за период. */
    static int total(int[][] r, int period) {
        if (r == null || period < 0 || period >= KEYS.length) return 0;
        int n = 0;
        for (int[] k : r) n += k[period];
        return n;
    }

    /** Нет функции в базе (SQL не выполнен). */
    static boolean noDb(String code, String body) {
        String b = body == null ? "" : body;
        return "404".equals(code) || b.contains("PGRST202") || b.contains("Could not find the function");
    }

    /** «1 обращение», «3 обращения», «5 обращений». */
    static String word(int n) {
        int m = Math.abs(n) % 100, d = m % 10;
        if (m > 10 && m < 20) return "обращений";
        if (d == 1) return "обращение";
        if (d >= 2 && d <= 4) return "обращения";
        return "обращений";
    }

    /** С какого времени считается период. */
    static String since(int period) {
        switch (period) {
            case 0: return "с 00:00 сегодня";
            case 1: return "с понедельника";
            case 2: return "с 1-го числа";
            default: return "за всё время";
        }
    }

    /** Подпись выпадающего списка: «Все пункты» или название пункта (с городом). */
    static String pointName(IvaPickupPatch.Point p) {
        if (p == null) return ALL;
        String n = IvaToolPointPatch.chipText(p);
        return n == null || n.trim().isEmpty() ? (p.address == null ? POINT_LABEL : p.address) : n;
    }

    // ════════════════════════════════ «Позвонить» → iva_call_tap ════════════════════════════════

    static final Map<String, Long> tapAt = new HashMap<String, Long>();

    /** Фоном, не чаще раза в минуту для того же пункта / инструмента / проката. Ошибки и отсутствие SQL — молча. */
    static void tap(final Activity a, String point, String tool, String owner) {
        final String b = tapBody(point, tool, owner);
        if (b == null || a == null) return;
        long now = System.currentTimeMillis();
        synchronized (tapAt) {
            Long t = tapAt.get(b);
            if (t != null && now - t < TAP_PAUSE_MS) return;
            tapAt.put(b, now);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try { IvaClientPatch.apiCall(a, TAP_RPC, "POST", b, false); } catch (Throwable ignore) {}
            }
        }).start();
    }

    // ════════════════════════════════ строка в «Кабинете» ════════════════════════════════

    /** Из IvaClientPatch.onProfile (после каждой перерисовки): владельцу проката — «Обращения» под «Настройкой проката». */
    static void cabinet(final Activity a) {
        int id = a.getResources().getIdentifier("chiefBox", "id", a.getPackageName());
        View anchor = id == 0 ? null : a.findViewById(id);
        if (anchor == null || !(anchor.getParent() instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) anchor.getParent();
        View old = box.findViewWithTag(CAB_TAG);
        boolean logged = IvaNotifyPatch.loggedIn(a);
        if (!IvaShopPatch.cabWant(logged, logged && IvaNotifyPatch.isAdmin(a))) {
            if (old != null) box.removeView(old);
            return;
        }
        if (old != null) return;
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        LinearLayout row = IvaShopPatch.row(a, box, new Icon(cOrange, a.getResources().getDisplayMetrics().density), TITLE, ROW_SUB, cText, cGray);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        box.removeView(row);                                              // row() кладёт в конец — переносим под «Настройку проката»
        row.setTag(CAB_TAG);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { open(a); } catch (Throwable ignore) {} }
        });
        View shop = box.findViewWithTag(IvaShopPatch.CAB_TAG);
        int at = shop != null && shop.getParent() == box ? box.indexOfChild(shop) + 1 : box.indexOfChild(anchor);
        if (at < 0) return;
        box.addView(row, at, lp);
    }

    // ════════════════════════════════ страница «Обращения» ════════════════════════════════

    static final class Ui {
        Activity a;
        IvaChiefPatch.Page pg;
        TextView pick, total, totalWord, since, status;
        LinearLayout tabs, rows;
        List<IvaPickupPatch.Point> points;
        String sel = "";
        int period = 0;
        int[][] data;
        int gen = 0;
        boolean pointsLoading = false;
    }

    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }
    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static void open(final Activity a) {
        final Ui u = new Ui();
        u.a = a;
        u.pg = IvaChiefPatch.page(a, TITLE, null, false);
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        LinearLayout list = u.pg.list;

        // вверху — выбор пункта с выпадающим списком
        TextView lab = IvaChiefPatch.text(a, POINT_LABEL, 13f, cGray, true);
        lab.setPadding(dp(a, 2), dp(a, 4), 0, dp(a, 6));
        list.addView(lab, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dd = new LinearLayout(a);
        dd.setOrientation(LinearLayout.HORIZONTAL);
        dd.setGravity(Gravity.CENTER_VERTICAL);
        dd.setBackground(IvaClientPatch.choiceBg(a, "card", 0xff1a1d24, IvaClientPatch.CHOICE_RADIUS_DP));
        dd.setPadding(dp(a, 16), 0, dp(a, 14), 0);
        dd.setClickable(true);
        u.pick = IvaChiefPatch.text(a, ALL, 15f, cText, true);
        u.pick.setSingleLine(true);
        u.pick.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dd.addView(u.pick, new LinearLayout.LayoutParams(0, -2, 1f));
        dd.addView(IvaChiefPatch.text(a, "▾", 18f, cOrange, true), new LinearLayout.LayoutParams(-2, -2));
        dd.setContentDescription(POINT_LABEL + ": выбрать");
        list.addView(dd, new LinearLayout.LayoutParams(-1, dp(a, 50)));
        dd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { choosePoint(u); } catch (Throwable ignore) {} }
        });

        // окошко со счётчиком
        LinearLayout card = IvaChiefPatch.card(a);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(a, 14);
        card.setLayoutParams(cl);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        u.tabs = new LinearLayout(a);
        u.tabs.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(u.tabs, new android.widget.FrameLayout.LayoutParams(-2, -2));
        card.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout big = new LinearLayout(a);
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.setGravity(Gravity.BOTTOM);
        big.setPadding(0, dp(a, 16), 0, 0);
        u.total = IvaChiefPatch.text(a, "…", 34f, cOrange, true);
        big.addView(u.total, new LinearLayout.LayoutParams(-2, -2));
        u.totalWord = IvaChiefPatch.text(a, "", 15f, cText, false);
        u.totalWord.setPadding(dp(a, 8), 0, 0, dp(a, 6));
        big.addView(u.totalWord, new LinearLayout.LayoutParams(-2, -2));
        card.addView(big, new LinearLayout.LayoutParams(-1, -2));
        u.since = IvaChiefPatch.text(a, "", 12f, cGray, false);
        card.addView(u.since, new LinearLayout.LayoutParams(-1, -2));
        u.rows = new LinearLayout(a);
        u.rows.setOrientation(LinearLayout.VERTICAL);
        u.rows.setPadding(0, dp(a, 10), 0, 0);
        card.addView(u.rows, new LinearLayout.LayoutParams(-1, -2));
        u.status = IvaChiefPatch.text(a, "", 13f, cGray, false);
        u.status.setPadding(0, dp(a, 10), 0, 0);
        u.status.setVisibility(View.GONE);
        card.addView(u.status, new LinearLayout.LayoutParams(-1, -2));
        list.addView(card);

        TextView note = IvaChiefPatch.text(a, NOTE, 12f, color(a, "gray2", 0xff6b7080), false);
        note.setPadding(dp(a, 2), dp(a, 12), dp(a, 2), 0);
        list.addView(note, new LinearLayout.LayoutParams(-1, -2));

        paintTabs(u);
        paint(u);
        loadPoints(u, false);
        load(u);
    }

    static void paintTabs(final Ui u) {
        Activity a = u.a;
        u.tabs.removeAllViews();
        for (int i = 0; i < PERIODS.length; i++) {
            final int idx = i;
            TextView c = IvaSearchPatch.chip(a, PERIODS[i], i == u.period, false);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) l.leftMargin = dp(a, 8);
            u.tabs.addView(c, l);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (u.period == idx) return;
                    u.period = idx;
                    try { paintTabs(u); paint(u); } catch (Throwable ignore) {}
                }
            });
        }
    }

    /** Числа выбранного периода (данные уже загружены — переключение периода без запроса). */
    static void paint(Ui u) {
        Activity a = u.a;
        int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0);
        u.since.setText(since(u.period));
        u.rows.removeAllViews();
        if (u.data == null) {
            u.total.setText("…");
            u.totalWord.setText("");
            return;
        }
        int t = total(u.data, u.period);
        u.total.setText(String.valueOf(t));
        u.totalWord.setText(word(t));
        for (int k = 0; k < KINDS.length; k++) {
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(a, 9), 0, dp(a, 9));
            line.addView(IvaChiefPatch.text(a, KIND_T[k], 15f, cGray, false), new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(IvaChiefPatch.text(a, String.valueOf(u.data[k][u.period]), 17f, cText, true), new LinearLayout.LayoutParams(-2, -2));
            if (k > 0) {
                View sep = new View(a);
                sep.setBackgroundColor(color(a, "line", 0xff262b35));
                u.rows.addView(sep, new LinearLayout.LayoutParams(-1, Math.max(1, dp(a, 1))));
            }
            u.rows.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    static void status(Ui u, String s) {
        u.status.setText(s == null ? "" : s);
        u.status.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Числа по выбранному пункту — фоном; ответ на прежний выбор (пункт сменили) не показываем. */
    static void load(final Ui u) {
        final Activity a = u.a;
        final int gen = ++u.gen;
        final String sel = u.sel;
        u.data = null;
        paint(u);
        status(u, "");
        new Thread(new Runnable() {
            @Override public void run() {
                String[] r = null;
                try {
                    IvaClientPatch.authFresh(a);
                    r = IvaClientPatch.apiCall(a, RPC, "POST", body(sel), true);
                } catch (Throwable ignore) {}
                final String code = r == null ? "" : String.valueOf(r[0]);
                final String b = r == null || r.length < 2 ? "" : r[1];
                final int[][] d = code.startsWith("2") ? parse(b) : null;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen != u.gen || !u.pg.open()) return;
                        if (d != null) { u.data = d; paint(u); status(u, ""); return; }
                        u.total.setText("—");
                        u.totalWord.setText("");
                        status(u, noDb(code, b) ? NO_DB : FAIL);
                    }
                });
            }
        }).start();
    }

    static void loadPoints(final Ui u, final boolean thenChoose) {
        if (u.pointsLoading) return;
        u.pointsLoading = true;
        final Activity a = u.a;
        new Thread(new Runnable() {
            @Override public void run() {
                List<IvaPickupPatch.Point> ps = null;
                try { ps = IvaPickupPatch.load(a, new String[1]); } catch (Throwable ignore) {}
                final List<IvaPickupPatch.Point> fps = ps;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        u.pointsLoading = false;
                        if (fps != null) u.points = fps;
                        if (thenChoose && u.pg.open()) {
                            if (fps == null) Toast.makeText(a, "Не удалось загрузить пункты — проверьте интернет", Toast.LENGTH_SHORT).show();
                            else try { choosePoint(u); } catch (Throwable ignore) {}
                        }
                    }
                });
            }
        }).start();
    }

    /** Выпадающий список: «Все пункты» и пункты проката (окно выбора в стиле приложения). */
    static void choosePoint(final Ui u) {
        final List<IvaPickupPatch.Point> ps = u.points;
        if (ps == null) {
            Toast.makeText(u.a, "Загружаем пункты…", Toast.LENGTH_SHORT).show();
            loadPoints(u, true);
            return;
        }
        final List<IvaPickupPatch.Point> ok = new ArrayList<IvaPickupPatch.Point>();
        for (IvaPickupPatch.Point p : ps) if (p != null && uuid(p.id)) ok.add(p);
        String[] items = new String[ok.size() + 1];
        items[0] = (u.sel.isEmpty() ? "✓ " : "") + ALL;
        for (int i = 0; i < ok.size(); i++) items[i + 1] = (ok.get(i).id.equalsIgnoreCase(u.sel) ? "✓ " : "") + pointName(ok.get(i));
        IvaClientPatch.styledChoice(u.a, POINT_LABEL, ok.isEmpty() ? "Пунктов выдачи пока нет — показываем все обращения проката" : null, items,
            new IvaClientPatch.Choice() {
                @Override public void pick(int i) {
                    String s = i <= 0 || i > ok.size() ? "" : ok.get(i - 1).id;
                    u.pick.setText(i <= 0 || i > ok.size() ? ALL : pointName(ok.get(i - 1)));
                    if (s.equalsIgnoreCase(u.sel) && u.data != null) return;
                    u.sel = s;
                    load(u);
                }
            });
    }

    // ════════════════════════════════ «Написать в чат» на карточке заявки ════════════════════════════════

    /** Почта клиента заявки из штатной части (order_parts + orders); «null» и пусто — "". */
    static String clientMail(JSONObject part) {
        JSONObject o = part == null ? null : part.optJSONObject("orders");
        String m = o == null || o.isNull("user_email") ? "" : o.optString("user_email", "").trim();
        return "null".equals(m) || !m.contains("@") ? "" : m;
    }

    /** Штатная кнопка «Позвонить: …» на карточке (ищем по тексту, внутри — не только первый уровень). */
    static TextView findCall(View v) {
        if (v == null) return null;
        if (v instanceof TextView) {
            String t = String.valueOf(((TextView) v).getText()).trim();
            return t.startsWith(IvaOrderPatch.CONTACT_CALL) ? (TextView) v : null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView f = findCall(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Из IvaOrderPatch.admMore: рядом с «Позвонить» — «Написать в чат» тем же видом; не помещаются в ряд — под ней. */
    static void chatButton(final Activity a, LinearLayout card, JSONObject part) {
        if (card == null || card.findViewWithTag(CHAT_TAG) != null) return;
        final String mail = clientMail(part);
        if (mail.isEmpty()) return;                                       // заявка без входа — писать некому
        final TextView call = findCall(card);
        TextView w = call instanceof Button ? new Button(a) : new TextView(a);
        w.setTag(CHAT_TAG);
        w.setText(CHAT_TEXT);
        w.setAllCaps(false);
        w.setSingleLine(true);
        w.setGravity(Gravity.CENTER);
        w.setContentDescription(CHAT_TEXT + " клиенту");
        if (call != null) {
            w.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, call.getTextSize());
            w.setTextColor(call.getTextColors());
            w.setTypeface(call.getTypeface());
            Drawable bg = call.getBackground();
            Drawable.ConstantState cs = bg == null ? null : bg.getConstantState();
            if (cs != null) w.setBackground(cs.newDrawable(a.getResources()).mutate());
            w.setPadding(call.getPaddingLeft(), call.getPaddingTop(), call.getPaddingRight(), call.getPaddingBottom());
            w.setMinHeight(0);
            w.setMinWidth(0);
            if (android.os.Build.VERSION.SDK_INT >= 21) w.setStateListAnimator(null);
        } else {
            w.setTextSize(13f);
            w.setTextColor(color(a, "orange", 0xffff8f1f));
            w.setTypeface(null, android.graphics.Typeface.BOLD);
            w.setBackground(IvaClientPatch.choiceBg(a, "card", 0xff1a1d24, IvaClientPatch.CHOICE_RADIUS_DP));
            w.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        }
        w.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = false;
                try { ok = IvaChatPatch.openWith(a, mail); } catch (Throwable ignore) {}
                if (!ok) Toast.makeText(a, IvaOrderPatch.CONTACT_NO_CHAT, Toast.LENGTH_SHORT).show();
            }
        });
        int h = dp(a, 36);
        if (call == null || !(call.getParent() instanceof LinearLayout)) {      // телефона нет — одна кнопка, где была бы «Позвонить»
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, h);
            l.topMargin = dp(a, 8);
            int at = Math.min(card.getChildCount(), card.findViewWithTag(IvaOrderPatch.ADM_MORE_TAG) != null
                ? card.indexOfChild(card.findViewWithTag(IvaOrderPatch.ADM_MORE_TAG)) + 1 : card.getChildCount());
            card.addView(w, Math.max(0, at), l);
            return;
        }
        LinearLayout parent = (LinearLayout) call.getParent();
        int idx = parent.indexOfChild(call);
        ViewGroup.LayoutParams old = call.getLayoutParams();
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        if (old instanceof LinearLayout.LayoutParams) rl.topMargin = ((LinearLayout.LayoutParams) old).topMargin;
        else rl.topMargin = dp(a, 8);
        parent.removeView(call);
        row.addView(call, new LinearLayout.LayoutParams(-2, old != null && old.height > 0 ? old.height : h));
        final LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, old != null && old.height > 0 ? old.height : h);
        wl.leftMargin = dp(a, 8);
        row.addView(w, wl);
        parent.addView(row, idx, rl);
        final TextView fw = w;
        final int gap = dp(a, 8);
        row.post(new Runnable() {                                         // не помещаются рядом (узкий экран, длинный номер) — столбиком
            @Override public void run() {
                try {
                    int un = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                    call.measure(un, un);
                    fw.measure(un, un);
                    int need = call.getMeasuredWidth() + gap + fw.getMeasuredWidth();
                    if (row.getWidth() > 0 && need > row.getWidth()) {
                        row.setOrientation(LinearLayout.VERTICAL);
                        row.setGravity(Gravity.START);
                        wl.leftMargin = 0;
                        wl.topMargin = gap;
                        fw.setLayoutParams(wl);
                    }
                } catch (Throwable ignore) {}
            }
        });
    }

    // ════════════════════════════════ значок строки ════════════════════════════════

    /** Облачко сообщения с трубкой внутри (цвет приложения, линией 2dp) — как значки «Настройки проката». */
    static final class Icon extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int color, float density) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * density);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas c) {
            float l = getBounds().left, t = getBounds().top, w = getBounds().width(), h = getBounds().height();
            RectF r = new RectF(l + w * 0.1f, t + h * 0.14f, l + w * 0.9f, t + h * 0.7f);
            c.drawRoundRect(r, w * 0.16f, w * 0.16f, p);
            Path tail = new Path();
            tail.moveTo(l + w * 0.3f, t + h * 0.7f);
            tail.lineTo(l + w * 0.24f, t + h * 0.88f);
            tail.lineTo(l + w * 0.46f, t + h * 0.7f);
            c.drawPath(tail, p);
            c.drawLine(l + w * 0.3f, t + h * 0.36f, l + w * 0.7f, t + h * 0.36f, p);
            c.drawLine(l + w * 0.3f, t + h * 0.5f, l + w * 0.56f, t + h * 0.5f, p);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

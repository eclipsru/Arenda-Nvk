/*
 * Ива 10.77 — окончание аренды (просьба владельца 14.10.2026).
 *
 *  • «Срок аренды» (10.78; было «Аренда») в настройках админа: как считать конец аренды —
 *      «Сутки»              — выдали в 14:30 → вернуть в 14:30 через столько суток, сколько в заявке;
 *      «До закрытия пункта» — выдали в 14:30 → вернуть до закрытия пункта через столько дней, сколько в заявке
 *                             (время закрытия — своё у каждого дня недели; выходной → до закрытия следующего
 *                             рабочего дня).
 *    Хранится в базе (SQL 20261014_rent_end_mode.sql), у каждого проката своё. Смена режима действует и на уже
 *    выданные инструменты (решение владельца): база отдаёт текущие настройки, конец считается здесь.
 *  • По этому концу клиент получает напоминание «за 1–3 часа» (10.76, IvaNotifyPatch.checkRent).
 *  • Живой таймер «чч.мм.сс» (10.78; было «дд.чч.мм») в активных заявках, отмеченных «Выдал»: у клиента — на карточке и в подробностях,
 *    у админа — на карточке заявки клиента и в подробностях. После конца срока — «Просрочено …» красным.
 *
 * Новых вставок в smali нет: вызывается из IvaNotifyPatch (настройки, напоминание) и IvaOrderPatch (заявки).
 */
package ru.prokatnvsk.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class IvaRentPatch {

    static final String MODE_DAY = "day", MODE_CLOSE = "close";
    static final String TZ_DEF = "Europe/Moscow";
    static final int CLOSE_DEF = 19 * 60;                 // по умолчанию пункт закрывается в 19:00
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final String GET_RPC = "/rest/v1/rpc/iva_rent_settings_get";
    static final String SET_RPC = "/rest/v1/rpc/iva_rent_settings_set";
    static final String ADM_RPC = "/rest/v1/rpc/iva_admin_rentals";
    static final String K_MODE = "rent_mode", K_CLOSE = "rent_close", K_TZ = "rent_tz";
    static final String TIMER_TAG = "iva_rent_timer";
    static final String HEAD_TITLE = "Срок аренды";   // 10.78: было «Аренда»
    static final String DAY_TITLE = "Сутки", DAY_SUB = "Выдали в 14:30 — вернуть в 14:30 через столько суток, сколько в заявке";
    static final String CLOSE_TITLE = "До закрытия пункта",
        CLOSE_SUB = "Выдали в 14:30 — вернуть до закрытия пункта через столько дней, сколько в заявке";
    static final String RENT_HINT = "Срок у каждого инструмента — из заявки. По этому времени клиент получает напоминание "
        + "об окончании аренды и видит таймер в заявке. Изменение действует сразу, и для уже выданных инструментов.";
    static final String CLOSE_HINT = "Выключенный день — выходной: если срок выпадает на него, вернуть можно до закрытия "
        + "следующего рабочего дня.";
    static final String NO_DB = "Заработает после обновления базы (SQL 10.77)";
    static final String TIMER_LABEL = "До конца аренды";

    // ───────────── правило и расчёт (без Android — проверяется в IvaPatchTest) ─────────────

    /** Как прокат считает конец аренды. close — минуты закрытия Пн…Вс, -1 — выходной. */
    static final class Rule {
        String mode = MODE_DAY, tz = TZ_DEF;
        int[] close = defClose();
        boolean byClose() { return MODE_CLOSE.equals(mode); }
    }

    static int[] defClose() { int[] c = new int[7]; java.util.Arrays.fill(c, CLOSE_DEF); return c; }

    /** «[1080,1080,…,-1]» (как отдаёт база) / «{…}» / «1080,…» → 7 значений; что-то не так — по умолчанию. */
    static int[] parseClose(String s) {
        String t = s == null ? "" : s.replaceAll("[\\[\\]{}\\s]", "");
        String[] xs = t.split(",");
        if (xs.length != 7) return defClose();
        int[] out = new int[7];
        try {
            for (int i = 0; i < 7; i++) {
                int v = Integer.parseInt(xs[i]);
                if (v < -1 || v > 1439) return defClose();
                out[i] = v;
            }
        } catch (Throwable e) { return defClose(); }
        return out;
    }

    static String closeJson(int[] c) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 7; i++) { if (i > 0) b.append(','); b.append(c == null || c.length != 7 ? CLOSE_DEF : c[i]); }
        return b.append(']').toString();
    }

    static Rule rule(String mode, String close, String tz) {
        Rule r = new Rule();
        r.mode = MODE_CLOSE.equals(mode == null ? "" : mode.trim()) ? MODE_CLOSE : MODE_DAY;
        r.close = parseClose(close);
        String z = tz == null ? "" : tz.trim();
        r.tz = z.isEmpty() || "null".equals(z) ? TZ_DEF : z;
        return r;
    }

    static Rule ruleOf(JSONObject o) {
        return o == null ? new Rule() : rule(o.optString("rent_mode", o.optString("mode", "")), o.optString("close_min", ""), o.optString("tz", ""));
    }

    static boolean anyOpen(int[] c) {
        if (c == null || c.length != 7) return false;
        for (int v : c) if (v >= 0) return true;
        return false;
    }

    /**
     * Конец аренды. «Сутки» (или правила нет) — время выдачи + дни × 24 ч. «До закрытия пункта» — в день выдачи + дни
     * (по часовому поясу проката) в его время закрытия; выходной — следующий рабочий день; все дни выходные — как «Сутки».
     */
    static long end(long rentedAt, int days, Rule r) {
        if (rentedAt <= 0 || days <= 0) return 0;
        long plain = rentedAt + days * DAY_MS;
        if (r == null || !r.byClose() || !anyOpen(r.close)) return plain;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(r.tz == null || r.tz.isEmpty() ? TZ_DEF : r.tz));
        c.setTimeInMillis(rentedAt);
        c.add(Calendar.DAY_OF_MONTH, days);
        for (int i = 0; i < 7; i++) {
            int m = r.close[IvaNotifyPatch.dowMon(c.get(Calendar.DAY_OF_WEEK))];
            if (m >= 0) {
                c.set(Calendar.HOUR_OF_DAY, m / 60);
                c.set(Calendar.MINUTE, m % 60);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return plain;
    }

    /** 10.78: сколько секунд показывать: до конца — с округлением вверх (00.00.00 ровно в срок), после — сколько прошло. */
    static long seconds(long end, long now) { return now < end ? (end - now + 999) / 1000 : (now - end) / 1000; }

    /** 10.78 (просьба владельца): «чч.мм.сс», часы не ограничены сутками: 1 день 5 ч 32 мин 7 с → «29.32.07». */
    static String hms(long sec) {
        long s = Math.max(0, sec);
        return String.format(Locale.ROOT, "%02d.%02d.%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static String timer(long end, long now) { return (now < end ? "Осталось " : "Просрочено ") + hms(seconds(end, now)); }

    /** «до 15.10 в 19:00» — по часам телефона. */
    static String until(long end, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(end);
        return String.format(Locale.ROOT, "до %02d.%02d в %02d:%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** 10.78: через сколько перерисовать — когда сменится секунда у любого из сроков (+20 мс), не чаще 5 раз в секунду. */
    static long nextTick(List<IvaNotifyPatch.RentEnd> ends, long now) {
        long best = 1000;
        for (IvaNotifyPatch.RentEnd e : ends) {
            long d = Math.abs(e.end - now) % 1000;
            if (now < e.end) { if (d == 0) d = 1000; }
            else d = 1000 - d;
            best = Math.min(best, d);
        }
        return Math.max(200, best + 20);
    }

    /** Подпись под «Срок аренды» в настройках. */
    static String summary(Rule r) {
        return r != null && r.byClose() ? CLOSE_TITLE + ": с 14:30 до закрытия" : DAY_TITLE + ": с 14:30 до 14:30";
    }

    // ───────────── данные: выданные инструменты (клиенту — по заявкам, админу — по частям) ─────────────

    static final Map<String, List<IvaNotifyPatch.RentEnd>> byOrder = Collections.synchronizedMap(new HashMap<String, List<IvaNotifyPatch.RentEnd>>());
    static final Map<String, List<IvaNotifyPatch.RentEnd>> byPart = Collections.synchronizedMap(new HashMap<String, List<IvaNotifyPatch.RentEnd>>());
    static volatile String admFor = "";
    static volatile long admAt, admMissingUntil;
    static volatile boolean admLoading, admRetry;

    /** Строки функции базы → сроки (по заявке или по части). */
    static Map<String, List<IvaNotifyPatch.RentEnd>> group(String body, boolean part) throws Exception {
        Map<String, List<IvaNotifyPatch.RentEnd>> out = new HashMap<String, List<IvaNotifyPatch.RentEnd>>();
        String t = body == null ? "" : body.trim();
        JSONArray rows = t.startsWith("[") ? new JSONArray(t) : new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            String pid = o.optString("part_id", ""), oid = o.optString("order_id", "");
            List<IvaNotifyPatch.RentEnd> es = IvaNotifyPatch.rentEnds(pid, oid, o.optString("tools_text", ""), o.optInt("days", 0),
                IvaChatPatch.parseIso(o.optString("rented_at", "")), ruleOf(o));
            if (es.isEmpty()) continue;
            String k = (part ? pid : oid).trim();
            List<IvaNotifyPatch.RentEnd> l = out.get(k);
            if (l == null) out.put(k, l = new ArrayList<IvaNotifyPatch.RentEnd>());
            l.addAll(es);
        }
        return out;
    }

    /** Клиент: свои выданные — вызывается из фона загрузки «Заявок» (IvaOrderPatch.ordFetch). Нет функции — пусто. */
    static void loadMine(Activity a) {
        try {
            String[] r = IvaClientPatch.apiCall(a, IvaNotifyPatch.RENT_RPC, "POST", "{}", true);
            if (r == null) return;
            if (r.length > 1 && r[0].startsWith("2")) {
                Map<String, List<IvaNotifyPatch.RentEnd>> g = group(r[1], false);
                synchronized (byOrder) { byOrder.clear(); byOrder.putAll(g); }
            } else if ("404".equals(r[0])) byOrder.clear();
        } catch (Throwable ignore) {}
    }

    /** Админ: загрузить свои выданные, если есть «в аренде» без срока или данные старше минуты. После — перерисовать. */
    static void admNeed(final Activity a, List<?> parts) {
        long now = System.currentTimeMillis();
        String me = IvaOrderPatch.ordMe(a);
        if (!me.equals(admFor)) { byPart.clear(); admAt = 0; admFor = me; }
        if (admLoading || now < admMissingUntil) return;
        boolean lack = false;
        if (parts != null) for (Object o : parts) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if ("rented".equalsIgnoreCase(j.optString("status", "").trim()) && !byPart.containsKey(j.optString("id", "").trim())) lack = true;
        }
        if (lack && now - admAt <= 5000 && !admRetry) {        // только что загружали — «Выдал» нажали сейчас: ещё раз через 5 с
            admRetry = true;
            IvaOrderPatch.ordH().postDelayed(new Runnable() {
                @Override public void run() { admRetry = false; try { IvaOrderPatch.ordSafeRender(a); } catch (Throwable ignore) {} }
            }, 5100 - (now - admAt));
        }
        if (!(lack && now - admAt > 5000) && now - admAt < 60000) return;
        admLoading = true;
        admAt = now;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean changed = false;
                try {
                    String[] r = IvaClientPatch.apiCall(a, ADM_RPC, "POST", "{}", true);
                    if (r != null && "404".equals(r[0])) admMissingUntil = System.currentTimeMillis() + 10 * 60000L;
                    else if (r != null && r.length > 1 && r[0].startsWith("2")) {
                        Map<String, List<IvaNotifyPatch.RentEnd>> g = group(r[1], true);
                        synchronized (byPart) {
                            changed = !sig(byPart).equals(sig(g));
                            byPart.clear();
                            byPart.putAll(g);
                        }
                    }
                } catch (Throwable ignore) {}
                admLoading = false;
                if (changed) IvaOrderPatch.ordH().post(new Runnable() {
                    @Override public void run() { try { IvaOrderPatch.ordSafeRender(a); } catch (Throwable ignore) {} }
                });
            }
        }).start();
    }

    /** Подпись сроков: ключи частей и концы. Изменилась (выдали, вернули, сменили режим) — таймеры перерисовать. */
    static String sig(List<IvaNotifyPatch.RentEnd> es) {
        StringBuilder b = new StringBuilder();
        if (es != null) for (IvaNotifyPatch.RentEnd e : es) b.append(e.key).append(';');
        return b.toString();
    }

    static String sig(Map<String, List<IvaNotifyPatch.RentEnd>> m) {
        java.util.TreeMap<String, String> t = new java.util.TreeMap<String, String>();
        synchronized (m) { for (Map.Entry<String, List<IvaNotifyPatch.RentEnd>> e : m.entrySet()) t.put(e.getKey(), sig(e.getValue())); }
        return t.toString();
    }

    static final Map<View, String> shown = Collections.synchronizedMap(new java.util.WeakHashMap<View, String>());

    // ───────────── таймер ─────────────

    /** Блок таймера: по строке на каждый срок (обычно одна). Тикает, пока виден на экране. */
    static LinearLayout timerView(final Activity a, final List<IvaNotifyPatch.RentEnd> ends, final float size, final boolean withUntil) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag(TIMER_TAG);
        final List<TextView> lines = new ArrayList<TextView>();
        final List<IvaNotifyPatch.RentEnd> es = new ArrayList<IvaNotifyPatch.RentEnd>(ends);
        Collections.sort(es, new java.util.Comparator<IvaNotifyPatch.RentEnd>() {
            @Override public int compare(IvaNotifyPatch.RentEnd x, IvaNotifyPatch.RentEnd y) { return x.end < y.end ? -1 : x.end > y.end ? 1 : 0; }
        });
        while (es.size() > 4) es.remove(es.size() - 1);
        for (int i = 0; i < es.size(); i++) {
            TextView t = new TextView(a);
            t.setTextSize(size);
            t.setTypeface(null, Typeface.BOLD);
            box.addView(t);
            lines.add(t);
        }
        final int cOrange = IvaOrderPatch.color(a, "orange", 0xffff8f1f), cRed = IvaOrderPatch.color(a, "red", 0xffff4d4f);
        final Runnable tick = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                TimeZone tz = TimeZone.getDefault();
                for (int i = 0; i < es.size(); i++) {
                    IvaNotifyPatch.RentEnd e = es.get(i);
                    String s = timer(e.end, now);
                    if (es.size() > 1) s = e.names + " — " + s.substring(0, 1).toLowerCase(new Locale("ru")) + s.substring(1);
                    if (withUntil) s += " · " + until(e.end, tz);
                    lines.get(i).setText(s);
                    lines.get(i).setTextColor(now < e.end ? cOrange : cRed);
                }
                box.removeCallbacks(this);
                if (box.getWindowToken() != null) box.postDelayed(this, nextTick(es, now));
            }
        };
        tick.run();
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { tick.run(); }
            @Override public void onViewDetachedFromWindow(View v) { v.removeCallbacks(tick); }
        });
        return box;
    }

    /** Карточка заявки клиента: таймер под названиями, если инструменты выданы. */
    static void clientCard(Activity a, LinearLayout col, Map<String, String> r) {
        if (r == null || !IvaOrderPatch.ordActive(r, false)) return;
        List<IvaNotifyPatch.RentEnd> es = byOrder.get(IvaOrderPatch.ordVal(r.get("id")));
        if (es == null || es.isEmpty()) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = IvaOrderPatch.dp(a, 6);
        col.addView(timerView(a, es, 13f, true), lp);
    }

    /** Карточка заявки у админа (список заявок клиента): таймер после дат и названий, перед «Подробнее ›». */
    static void admCard(Activity a, LinearLayout card, JSONObject part) {
        if (part == null) return;
        List<IvaNotifyPatch.RentEnd> es = "rented".equalsIgnoreCase(part.optString("status", "").trim()) ? byPart.get(part.optString("id", "").trim()) : null;
        View old = card.findViewWithTag(TIMER_TAG);
        if (old != null) {
            if (es != null && sig(es).equals(shown.get(old))) return;           // тот же срок — таймер уже тикает
            card.removeView(old);
        }
        if (es == null || es.isEmpty()) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = IvaOrderPatch.dp(a, 6);
        int at = card.getChildAt(0) instanceof TextView ? 2 : 1;
        View t = timerView(a, es, 13f, true);
        shown.put(t, sig(es));
        card.addView(t, Math.min(at, card.getChildCount()), lp);
    }

    /** Подробности активной выданной заявки: строка «До конца аренды» с таймером и временем возврата. */
    static void sheet(Activity a, LinearLayout body, Map<String, String> r, boolean adm, boolean archived) {
        if (r == null || !IvaOrderPatch.ordActive(r, archived)) return;
        List<IvaNotifyPatch.RentEnd> es = adm ? ("rented".equalsIgnoreCase(IvaOrderPatch.ordVal(r.get("status"))) ? byPart.get(IvaOrderPatch.ordVal(r.get("part_id"))) : null)
            : byOrder.get(IvaOrderPatch.ordVal(r.get("id")));
        if (es == null || es.isEmpty()) return;
        TextView l = new TextView(a);
        l.setText(TIMER_LABEL + " (чч.мм.сс)");
        l.setTextColor(IvaOrderPatch.color(a, "gray", 0xff9aa1b0));
        l.setTextSize(12f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = IvaOrderPatch.dp(a, 12);
        body.addView(l, ll);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, -2);
        vl.topMargin = IvaOrderPatch.dp(a, 2);
        body.addView(timerView(a, es, 15f, true), vl);
    }

    // ───────────── «Срок аренды» в настройках админа ─────────────

    static Rule cached(Activity a) {
        android.content.SharedPreferences p = IvaNotifyPatch.prefs(a);
        return rule(p.getString(K_MODE, MODE_DAY), p.getString(K_CLOSE, ""), p.getString(K_TZ, TZ_DEF));
    }

    static void cache(Activity a, Rule r) {
        IvaNotifyPatch.prefs(a).edit().putString(K_MODE, r.mode).putString(K_CLOSE, closeJson(r.close)).putString(K_TZ, r.tz).apply();
    }

    static String setBody(Rule r) {
        return "{\"p_mode\":\"" + (r.byClose() ? MODE_CLOSE : MODE_DAY) + "\",\"p_close\":" + closeJson(r.close)
            + ",\"p_tz\":\"" + (r.tz == null ? TZ_DEF : r.tz.replaceAll("[^A-Za-z0-9_/+\\-]", "")) + "\"}";
    }

    static Drawable clockIcon(final int color, final float density) {
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        return new Drawable() {
            @Override public void draw(android.graphics.Canvas c) {
                android.graphics.Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) return;
                int save = c.save();
                c.translate(b.left, b.top);
                c.scale(b.width() / 24f, b.height() / 24f);
                paint.setStrokeWidth(2f);
                c.drawCircle(12f, 12f, 9f, paint);
                c.drawLine(12f, 12f, 12f, 7f, paint);
                c.drawLine(12f, 12f, 15.5f, 14f, paint);
                c.restoreToCount(save);
            }
            @Override public void setAlpha(int al) { paint.setAlpha(al); invalidateSelf(); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return Math.round(24 * density); }
            @Override public int getIntrinsicHeight() { return Math.round(24 * density); }
        };
    }

    static GradientDrawable optionBg(Activity a, boolean on, int cOrange) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(IvaOrderPatch.color(a, "card", 0xff1a1d24));
        g.setCornerRadius(IvaOrderPatch.dp(a, 16));
        g.setStroke(IvaOrderPatch.dp(a, on ? 2 : 1), on ? cOrange : IvaOrderPatch.color(a, "line", 0xff262b35));
        return g;
    }

    /** «Срок аренды» под «Уведомлениями» (только у админов): строка в «Настройках», по нажатию — отдельная страница
     *  (10.97, правило владельца: каждый пункт настроек — отдельной страницей). Сохраняется в базу сразу после изменения. */
    static void settingsSection(final Activity a, LinearLayout page, final int cText, final int cGray, final int cOrange) {
        final Rule st = cached(a);
        final boolean[] edited = new boolean[1];

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Drawable hbg = IvaOrderPatch.drawable(a, "bg_card");
        if (hbg != null) head.setBackground(hbg);
        int p16 = IvaOrderPatch.dp(a, 16), p14 = IvaOrderPatch.dp(a, 14);
        head.setPadding(p16, p14, p16, p14);
        head.setClickable(true);
        android.widget.ImageView ic = new android.widget.ImageView(a);
        ic.setImageDrawable(clockIcon(cOrange, a.getResources().getDisplayMetrics().density));
        head.addView(ic, new LinearLayout.LayoutParams(IvaOrderPatch.dp(a, 22), IvaOrderPatch.dp(a, 22)));
        LinearLayout hcol = new LinearLayout(a);
        hcol.setOrientation(LinearLayout.VERTICAL);
        TextView ht = new TextView(a);
        ht.setText(HEAD_TITLE);
        ht.setTextSize(15f);
        ht.setTextColor(cText);
        hcol.addView(ht);
        final TextView hsub = new TextView(a);
        hsub.setTextSize(13f);
        hsub.setTextColor(cGray);
        hcol.addView(hsub);
        LinearLayout.LayoutParams hcl = new LinearLayout.LayoutParams(0, -2, 1f);
        hcl.leftMargin = p14;
        head.addView(hcol, hcl);
        final android.widget.ImageView chev = new android.widget.ImageView(a);
        Drawable chd = IvaOrderPatch.drawable(a, "ic_chevron");
        if (chd != null) { chev.setImageDrawable(chd); chev.setColorFilter(cGray); }
        head.addView(chev, new LinearLayout.LayoutParams(IvaOrderPatch.dp(a, 20), IvaOrderPatch.dp(a, 20)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = IvaOrderPatch.dp(a, 10);
        page.addView(head, hl);
        // 10.97: содержимое собирается здесь, но в «Настройки» не вставляется — по нажатию переносится на свою страницу
        final LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] ref = new Runnable[1];
        head.setContentDescription(HEAD_TITLE + ", открыть");
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { IvaChiefPatch.subPage(a, HEAD_TITLE, body, ref[0]); } catch (Throwable ignore) {}
            }
        });

        body.addView(IvaNotifyPatch.label(a, "Окончание аренды", cGray));
        final LinearLayout optDay = option(a, body, DAY_TITLE, DAY_SUB, cText, cGray);
        final LinearLayout optClose = option(a, body, CLOSE_TITLE, CLOSE_SUB, cText, cGray);

        final LinearLayout closeBox = new LinearLayout(a);
        closeBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(closeBox, new LinearLayout.LayoutParams(-1, -2));
        final TextView closeLabel = IvaNotifyPatch.label(a, "Пункт закрывается", cGray);
        closeBox.addView(closeLabel);
        LinearLayout sc = new LinearLayout(a);
        sc.setOrientation(LinearLayout.VERTICAL);
        Drawable sbg = IvaOrderPatch.drawable(a, "bg_card");
        if (sbg != null) sc.setBackground(sbg);
        sc.setPadding(p16, IvaOrderPatch.dp(a, 6), IvaOrderPatch.dp(a, 12), IvaOrderPatch.dp(a, 10));
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, -2);
        scl.topMargin = IvaOrderPatch.dp(a, 8);
        closeBox.addView(sc, scl);
        final TextView[] times = new TextView[7];
        final Switch[] open = new Switch[7];
        final boolean[] drawing = new boolean[1];
        final TextView status = new TextView(a);
        final Runnable[] save = new Runnable[1];
        for (int i = 0; i < 7; i++) {
            final int day = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, IvaOrderPatch.dp(a, 2), 0, IvaOrderPatch.dp(a, 2));
            TextView dl = new TextView(a);
            dl.setText(IvaNotifyPatch.DAYS[i]);
            dl.setTextSize(15f);
            dl.setTypeface(null, Typeface.BOLD);
            dl.setTextColor(cText);
            row.addView(dl, new LinearLayout.LayoutParams(IvaOrderPatch.dp(a, 40), -2));
            TextView tt = new TextView(a);
            tt.setTextSize(15f);
            tt.setPadding(0, IvaOrderPatch.dp(a, 10), IvaOrderPatch.dp(a, 8), IvaOrderPatch.dp(a, 10));
            row.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch ds = new Switch(a);
            IvaNotifyPatch.tint(ds, cOrange);
            row.addView(ds, new LinearLayout.LayoutParams(-2, -2));
            tt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (st.close[day] < 0) return;
                    if (IvaPickupPatch.mainClose(a) != null) { linkedToast(a); return; }   // 10.95: время — из пункта выдачи
                    pickClose(a, st, day, new Runnable() { @Override public void run() { edited[0] = true; ref[0].run(); save[0].run(); } });
                }
            });
            ds.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                    if (drawing[0]) return;
                    if (IvaPickupPatch.mainClose(a) != null) { linkedToast(a); ref[0].run(); return; }   // 10.95
                    if (!v) {
                        int openDays = 0;
                        for (int x : st.close) if (x >= 0) openDays++;
                        if (openDays <= 1 && st.close[day] >= 0) {
                            Toast.makeText(a, "Хотя бы один день должен быть рабочим", Toast.LENGTH_SHORT).show();
                            ref[0].run();
                            return;
                        }
                        st.close[day] = -1;
                    } else st.close[day] = CLOSE_DEF;
                    edited[0] = true;
                    ref[0].run();
                    save[0].run();
                }
            });
            times[i] = tt;
            open[i] = ds;
            sc.addView(row);
        }
        final TextView copy = new TextView(a);
        copy.setText("Время понедельника — на все дни");
        copy.setTextSize(14f);
        copy.setTypeface(null, Typeface.BOLD);
        copy.setTextColor(cOrange);
        copy.setPadding(0, IvaOrderPatch.dp(a, 10), 0, IvaOrderPatch.dp(a, 6));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (int i = 1; i < 7; i++) st.close[i] = st.close[0];
                edited[0] = true;
                ref[0].run();
                save[0].run();
            }
        });
        sc.addView(copy);
        final TextView ch = new TextView(a);
        ch.setText(CLOSE_HINT);
        ch.setTextSize(12f);
        ch.setTextColor(cGray);
        ch.setPadding(0, IvaOrderPatch.dp(a, 2), 0, IvaOrderPatch.dp(a, 4));
        sc.addView(ch);

        TextView hint = new TextView(a);
        hint.setText(RENT_HINT);
        hint.setTextSize(12f);
        hint.setTextColor(cGray);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = IvaOrderPatch.dp(a, 12);
        body.addView(hint, nl);
        status.setTextSize(13f);
        status.setTextColor(cGray);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = IvaOrderPatch.dp(a, 8);
        body.addView(status, sl);

        ref[0] = new Runnable() {
            @Override public void run() {
                boolean byClose = st.byClose();
                optDay.setBackground(optionBg(a, !byClose, cOrange));
                optClose.setBackground(optionBg(a, byClose, cOrange));
                ((TextView) optDay.getChildAt(0)).setTextColor(!byClose ? cOrange : cText);
                ((TextView) optClose.getChildAt(0)).setTextColor(byClose ? cOrange : cText);
                closeBox.setVisibility(byClose ? View.VISIBLE : View.GONE);
                // 10.95 (решение владельца): есть пункты выдачи — время закрытия из графика первого пункта, здесь только показ
                int[] pc = IvaPickupPatch.mainClose(a);
                if (pc != null) st.close = pc.clone();
                closeLabel.setText(pc != null ? "Пункт закрывается — по графику «" + IvaPickupPatch.mainName(a) + "»" : "Пункт закрывается");
                copy.setVisibility(pc != null ? View.GONE : View.VISIBLE);
                ch.setText(pc != null ? LINK_HINT : CLOSE_HINT);
                drawing[0] = true;
                for (int i = 0; i < 7; i++) {
                    boolean on = st.close[i] >= 0;
                    open[i].setChecked(on);
                    times[i].setText(on ? "до " + IvaNotifyPatch.hm(st.close[i]) : "Выходной");
                    times[i].setTextColor(on ? cOrange : cGray);
                }
                drawing[0] = false;
                hsub.setText(summary(st));
            }
        };
        final Runnable doSave = new Runnable() {
            @Override public void run() {
                st.tz = TimeZone.getDefault().getID();
                cache(a, st);
                status.setText("Сохраняем…");
                status.setTextColor(cGray);
                final String body = setBody(st);
                new Thread(new Runnable() {
                    @Override public void run() {
                        String[] r = null;
                        try { r = IvaClientPatch.apiCall(a, SET_RPC, "POST", body, true); } catch (Throwable ignore) {}
                        final String[] fr = r;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (fr != null && fr[0].startsWith("2")) {
                                    status.setText("Сохранено ✓ Клиенты увидят новый срок при следующем обновлении");
                                    status.setTextColor(cGray);
                                    admAt = 0;                         // таймеры админа — по новым настройкам
                                } else {
                                    status.setText(fr != null && "404".equals(fr[0]) ? NO_DB : "Не сохранилось — нет связи. Измените ещё раз, когда появится интернет");
                                    status.setTextColor(cOrange);
                                }
                            }
                        });
                    }
                }).start();
            }
        };
        save[0] = new Runnable() {
            @Override public void run() { status.removeCallbacks(doSave); status.postDelayed(doSave, 700); }
        };
        optDay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!st.byClose()) return;
                st.mode = MODE_DAY; edited[0] = true; ref[0].run(); save[0].run();
            }
        });
        optClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (st.byClose()) return;
                st.mode = MODE_CLOSE; edited[0] = true; ref[0].run(); save[0].run();
            }
        });
        ref[0].run();
        relinkRef = new java.lang.ref.WeakReference<Runnable>(ref[0]);

        // из базы — актуальные (с другого телефона могли поменять); пока грузится — сохранённые на телефоне
        new Thread(new Runnable() {
            @Override public void run() {
                String[] r = null;
                try { r = IvaClientPatch.apiCall(a, GET_RPC, "POST", "{}", true); } catch (Throwable ignore) {}
                final String[] fr = r;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (fr != null && "404".equals(fr[0])) { status.setText(NO_DB); status.setTextColor(cOrange); return; }
                            if (fr == null || fr.length < 2 || !fr[0].startsWith("2") || edited[0]) return;
                            String t = fr[1] == null ? "" : fr[1].trim();
                            JSONArray arr = t.startsWith("[") ? new JSONArray(t) : new JSONArray();
                            JSONObject o = arr.optJSONObject(0);
                            if (o == null) return;
                            Rule got = ruleOf(o);
                            st.mode = got.mode; st.close = got.close; st.tz = got.tz;
                            cache(a, st);
                            ref[0].run();
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }

    // 10.95: «Срок аренды» ↔ «Пункт выдачи»
    static final String LINK_HINT = "Время закрытия — из графика первого пункта выдачи; меняется в «Настройки → Настройка проката → Пункты выдачи». "
        + "Выходной день: если срок выпадает на него, вернуть можно до закрытия следующего рабочего дня.";
    static volatile java.lang.ref.WeakReference<Runnable> relinkRef = new java.lang.ref.WeakReference<Runnable>(null);

    /** Пункты выдачи загрузились или изменились — перерисовать «Срок аренды», если он открыт. */
    static void relink() {
        Runnable r = relinkRef.get();
        if (r != null) try { r.run(); } catch (Throwable ignore) {}
    }

    static void linkedToast(Activity a) {
        try { Toast.makeText(a, "Время закрытия — из графика пункта «" + IvaPickupPatch.mainName(a) + "». Измените его в «Настройка проката → Пункты выдачи»", Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {}
    }

    static LinearLayout option(Activity a, LinearLayout body, String title, String sub, int cText, int cGray) {
        LinearLayout o = new LinearLayout(a);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setClickable(true);
        int p = IvaOrderPatch.dp(a, 16);
        o.setPadding(p, IvaOrderPatch.dp(a, 12), p, IvaOrderPatch.dp(a, 12));
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(15f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(cText);
        o.addView(t);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(13f);
        s.setTextColor(cGray);
        o.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = IvaOrderPatch.dp(a, 8);
        body.addView(o, lp);
        return o;
    }

    static void pickClose(final Activity a, final Rule st, final int day, final Runnable after) {
        try {
            int m = st.close[day] >= 0 ? st.close[day] : CLOSE_DEF;
            android.app.TimePickerDialog d = new android.app.TimePickerDialog(a, android.R.style.Theme_Material_Dialog,
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(android.widget.TimePicker v, int h, int mi) {
                        st.close[day] = h * 60 + mi;
                        if (after != null) after.run();
                    }
                }, m / 60, m % 60, true);
            d.setTitle("Закрытие — " + IvaNotifyPatch.DAYS_FULL[day]);
            d.show();
        } catch (Throwable e) {
            try { Toast.makeText(a, "Не удалось открыть выбор времени", Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        }
    }
}

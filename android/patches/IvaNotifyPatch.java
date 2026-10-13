// 10.56: «Настройки» в кабинете → отдельная страница; пока одна настройка — «Уведомления»:
// общий переключатель и три вида («Новые сообщения», «Новые заявки» — только админам, «Обновления приложения»).
// Уведомления — системные (строка состояния, шторка, всплывающие), у каждого вида свой канал Android.
// Штатный опрос (pollRun, раз в 30 с) выключается; вместо него — наш с теми же запросами, отметкой «Заявки •»
// и проверкой настроек. Новое определяем по id (а не по числу), увиденное помним на телефоне.
// Работает, пока приложение открыто или свёрнуто; когда закрыто — этап 2 (10.57).
// Вход — только IvaPatch.onTab (новых smali-хуков нет).
package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class IvaNotifyPatch {
    private IvaNotifyPatch() {}

    static final String PREFS = "iva_notify";
    static final String K_ALL = "all", K_MSG = "msg", K_ORD = "orders", K_UPD = "update";
    static final String ROW_TAG = "iva_settings_row";
    static final String ROW_TITLE = "Настройки";
    static final String CH_MSG = "iva_msg", CH_ORD = "iva_orders", CH_UPD = "iva_update";
    static final int ID_MSG = 7201, ID_ORD = 7202, ID_UPD = 7203, ID_TEST = 7204;
    static final long POLL_MS = 30000L;                         // как штатный опрос
    static final long UPDATE_EVERY_MS = 6L * 60 * 60 * 1000;     // обновления — не чаще раза в 6 часов
    static final String UPDATE_URL = "https://eclipsru.github.io/Arenda-Nvk/app-update.json";   // тот же, что у штатного SelfUpdate
    static final String EXTRA_OPEN = "iva_open";
    static final int MAX_SEEN = 300;

    static WeakReference<Activity> act;
    static Handler poll;
    static volatile boolean busy;
    static final Runnable TICK = new Runnable() {
        @Override public void run() { tick(); }
    };

    // ───────────────────────── чистая модель (проверяется в IvaPatchTest) ─────────────────────────

    /** Показывать ли вид уведомления: общий переключатель и свой; «Заявки» — только админам. */
    static boolean allowed(boolean all, boolean own, String kind, boolean admin) {
        if (!all || !own) return false;
        return !K_ORD.equals(kind) || admin;
    }

    /** Новые id (по порядку ответа). seen == null — первый запуск на этом телефоне: только запомнить. */
    static List<String> fresh(Set<String> seen, List<String> now) {
        List<String> out = new ArrayList<String>();
        if (seen == null) return out;
        for (String id : now) if (id != null && !id.isEmpty() && !seen.contains(id)) out.add(id);
        return out;
    }

    /** Что помнить: текущие + прежние, не больше MAX_SEEN (свежие первыми). */
    static Set<String> remember(Set<String> seen, List<String> now) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String id : now) if (id != null && !id.isEmpty()) out.add(id);
        if (seen != null) for (String id : seen) { if (out.size() >= MAX_SEEN) break; out.add(id); }
        return out;
    }

    static String joinSet(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String id : s) { if (b.length() > 0) b.append(','); b.append(id); }
        return b.toString();
    }

    static Set<String> splitSet(String s) {
        if (s == null) return null;
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    static String oneLine(String s, int max) {
        String t = s == null ? "" : s.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if ("null".equals(t)) t = "";
        return t.length() > max ? t.substring(0, max - 1).trim() + "…" : t;
    }

    /** Текст уведомления о сообщении: начало текста; фото / голосовое — словами. */
    static String messagePreview(String text, String img, String audio) {
        String t = oneLine(text, 100);
        if (!t.isEmpty() && !"Голосовое сообщение".equals(t)) return t;
        if (audio != null && !audio.trim().isEmpty() && !"null".equals(audio.trim())) return "Голосовое сообщение";
        if (img != null && !img.trim().isEmpty() && !"null".equals(img.trim())) return "Фото";
        return t.isEmpty() ? "Вам написали в чат — откройте «Чат»" : t;
    }

    static String messageTitle(int n) { return n > 1 ? "Новые сообщения: " + n : "Новое сообщение"; }

    static String orderTitle(int n) { return n > 1 ? "Новые заявки: " + n : "Новая заявка"; }

    static String orderText(String toolsText) {
        String t = oneLine(toolsText, 100);
        return t.isEmpty() ? "Поступила новая заявка на ваш инструмент" : t;
    }

    static boolean updateDue(long last, long now) { return last <= 0 || now - last >= UPDATE_EVERY_MS || now < last; }

    /** Уведомить об обновлении: на сайте версия новее установленной и о ней ещё не сообщали. */
    static boolean updateNews(int remote, int installed, int notified) { return remote > installed && remote != notified; }

    static String updateText(String name) {
        String n = oneLine(name, 20);
        return (n.isEmpty() ? "Новая версия" : "Версия " + n) + " — откройте приложение и нажмите «Обновить»";
    }

    // ───────────────────────── настройки ─────────────────────────

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** По умолчанию всё включено (решение владельца). */
    static boolean pref(Context c, String k) { return prefs(c).getBoolean(k, true); }

    static void setPref(Context c, String k, boolean v) { prefs(c).edit().putBoolean(k, v).apply(); }

    static boolean on(Context c, String kind, boolean admin) { return allowed(pref(c, K_ALL), pref(c, kind), kind, admin); }

    static String key(String what, String email) { return what + "_" + (email == null ? "" : email.trim().toLowerCase()); }

    // ───────────────────────── вход ─────────────────────────

    public static void onShowTab(Activity a, String tab) {
        act = new WeakReference<Activity>(a);
        try { stopNative(a); } catch (Throwable ignore) {}
        try { startPoll(); } catch (Throwable ignore) {}
        try { openFromNotification(a); } catch (Throwable ignore) {}
        try { ensureRow(a); } catch (Throwable ignore) {}
    }

    static Object get(Object o, String f) { return IvaClientPatch.get(o, f); }

    static Object call(Activity a, String name) {
        try {
            Method m = a.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(a);
        } catch (Throwable e) { return null; }
    }

    /** Штатный опрос (уведомления без настроек) — выключаем; его работу делает наш tick. */
    static void stopNative(Activity a) {
        Object h = get(a, "pollHandler"), r = get(a, "pollRun");
        if (h instanceof Handler && r instanceof Runnable) ((Handler) h).removeCallbacks((Runnable) r);
        // если штатный опрос успели перезапустить (вход, проверка админа) — его первый проход только запоминает
        IvaClientPatch.set(a, "lastNewCnt", Integer.valueOf(-1));
        IvaClientPatch.set(a, "lastUnread", Integer.valueOf(-1));
    }

    static void startPoll() {
        if (poll == null) poll = new Handler(Looper.getMainLooper());
        poll.removeCallbacks(TICK);
        poll.postDelayed(TICK, 5000);
    }

    static boolean loggedIn(Activity a) {
        Object t = get(a, "accessToken");
        return t instanceof String && !((String) t).trim().isEmpty();
    }

    static boolean isAdmin(Activity a) {
        Object v = call(a, "isAdminEmail");
        return v instanceof Boolean && (Boolean) v;
    }

    static void tick() {
        final Activity a = act == null ? null : act.get();
        if (a == null || a.isFinishing()) return;            // окно закрыто — опрос останавливается (фон — этап 2)
        try { stopNative(a); } catch (Throwable ignore) {}
        poll.removeCallbacks(TICK);
        poll.postDelayed(TICK, POLL_MS);
        if (busy) return;
        busy = true;
        final boolean logged = loggedIn(a), admin = logged && isAdmin(a);
        Object em = get(a, "userEmail");
        final String email = em instanceof String ? (String) em : "";
        Object ct = get(a, "curTab");
        final boolean inChat = "chat".equals(ct) && a.hasWindowFocus();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (logged && admin && !email.isEmpty()) checkOrders(a, email);
                    if (logged && !email.isEmpty()) checkMessages(a, email, admin, inChat);
                    checkUpdate(a, admin);
                } catch (Throwable ignore) {
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    static JSONArray getArr(Activity a, String path) throws Exception {
        String[] r = IvaClientPatch.apiCall(a, path, "GET", null, true);
        if (r == null || !r[0].startsWith("2")) throw new Exception(r == null ? "нет ответа" : r[0]);
        String t = r.length > 1 && r[1] != null ? r[1].trim() : "";
        return t.startsWith("[") ? new JSONArray(t) : new JSONArray();
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable e) { return s; }
    }

    /** Как штатный опрос: новые заявки на инструменты админа, отметка «Заявки •» в меню. */
    static void checkOrders(final Activity a, String email) throws Exception {
        JSONArray rows = getArr(a, "/rest/v1/order_parts?select=id,tools_text&status=eq.new&archived=eq.false&owner_email=eq." + enc(email) + "&order=id.desc&limit=50");
        final int n = rows.length();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < n; i++) ids.add(rows.getJSONObject(i).optString("id", ""));
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                Object t = get(a, "navOrdersTxt");
                if (t instanceof TextView) ((TextView) t).setText(n > 0 ? "Заявки •" : "Заявки");
            }
        });
        SharedPreferences p = prefs(a);
        String k = key("seen_o", email);
        Set<String> seen = splitSet(p.getString(k, null));
        List<String> nw = fresh(seen, ids);
        p.edit().putString(k, joinSet(remember(seen, ids))).apply();
        if (nw.isEmpty() || !on(a, K_ORD, true)) return;
        String text = "";
        for (int i = 0; i < n; i++) {
            JSONObject o = rows.getJSONObject(i);
            if (o.optString("id", "").equals(nw.get(0))) { text = o.optString("tools_text", ""); break; }
        }
        final String title = orderTitle(nw.size()), body = nw.size() > 1 ? "Откройте «Заявки»" : orderText(text);
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(a, "Новая заявка!", Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {}
            }
        });
        showNote(a, CH_ORD, ID_ORD, title, body, "orders");
    }

    /** Непрочитанные входящие сообщения; в открытом чате — без уведомления (как штатно). */
    static void checkMessages(Activity a, String email, boolean admin, boolean inChat) throws Exception {
        JSONArray rows = getArr(a, "/rest/v1/app_messages?select=id,from_email,text,img,audio&to_email=eq." + enc(email)
            + "&is_read=eq.false&order=created_at.desc&limit=50");
        List<String> ids = new ArrayList<String>();
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject m = rows.getJSONObject(i);
            if (email.equalsIgnoreCase(m.optString("from_email", ""))) continue;
            ids.add(m.optString("id", ""));
            list.add(m);
        }
        SharedPreferences p = prefs(a);
        String k = key("seen_m", email);
        Set<String> seen = splitSet(p.getString(k, null));
        List<String> nw = fresh(seen, ids);
        p.edit().putString(k, joinSet(remember(seen, ids))).apply();
        if (nw.isEmpty() || inChat || !on(a, K_MSG, admin)) return;
        JSONObject last = list.get(ids.indexOf(nw.get(0)));
        showNote(a, CH_MSG, ID_MSG, messageTitle(nw.size()),
            messagePreview(last.optString("text", ""), last.optString("img", ""), last.optString("audio", "")), "chat");
    }

    static int installedCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Throwable e) { return 0; }
    }

    /** Вышла новая версия (тот же app-update.json, что у штатной кнопки «Обновить»). */
    static void checkUpdate(Activity a, boolean admin) throws Exception {
        if (!on(a, K_UPD, admin)) return;
        SharedPreferences p = prefs(a);
        long now = System.currentTimeMillis();
        if (!updateDue(p.getLong("upd_checked", 0), now)) return;
        String body = httpGet(UPDATE_URL);
        p.edit().putLong("upd_checked", now).apply();
        if (body == null) return;
        JSONObject j = new JSONObject(body.trim());
        int remote = j.optInt("versionCode", 0);
        if (!updateNews(remote, installedCode(a), p.getInt("upd_notified", 0))) return;
        p.edit().putInt("upd_notified", remote).apply();
        showNote(a, CH_UPD, ID_UPD, "Вышло обновление приложения", updateText(j.optString("versionName", "")), "update");
    }

    static String httpGet(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) { out.write(buf, 0, r); if (out.size() > 65536) break; }
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // ───────────────────────── системные уведомления ─────────────────────────

    static String channelName(String ch) {
        return CH_MSG.equals(ch) ? "Новые сообщения" : CH_ORD.equals(ch) ? "Новые заявки" : CH_UPD.equals(ch) ? "Обновления приложения" : "Ива";
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String[] ids = {CH_MSG, CH_ORD, CH_UPD};
        for (String id : ids) {
            if (nm.getNotificationChannel(id) != null) continue;
            int imp = CH_UPD.equals(id) ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel ch = new NotificationChannel(id, channelName(id), imp);
            ch.setDescription(CH_MSG.equals(id) ? "Когда вам пишут в чат" : CH_ORD.equals(id) ? "Заявки на ваши инструменты" : "Когда вышла новая версия приложения");
            nm.createNotificationChannel(ch);
        }
    }

    static boolean systemAllows(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled();
        } catch (Throwable e) { return true; }
    }

    /** Строка состояния + шторка + всплывающее (канал HIGH); на экране блокировки — без текста сообщения. */
    @SuppressWarnings("deprecation")
    static void showNote(Activity a, String ch, int id, String title, String text, String open) {
        try {
            Context c = a.getApplicationContext();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannels(c);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
            Intent it = new Intent(c, a.getClass());
            it.putExtra(EXTRA_OPEN, open);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(c, id, it, fl);
            int icon = c.getResources().getIdentifier("ic_chat", "drawable", c.getPackageName());
            b.setSmallIcon(icon != 0 ? icon : c.getApplicationInfo().icon)
             .setContentTitle(title)
             .setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text))
             .setContentIntent(pi)
             .setAutoCancel(true)
             .setWhen(System.currentTimeMillis())
             .setShowWhen(true)
             .setColor(IvaClientPatch.navColor(a, "orange", 0xffff8f1f));
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(CH_UPD.equals(ch) ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_HIGH);
                b.setDefaults(Notification.DEFAULT_ALL);
            }
            if (CH_MSG.equals(ch)) {
                b.setVisibility(Notification.VISIBILITY_PRIVATE);
                Notification.Builder pub = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
                pub.setSmallIcon(icon != 0 ? icon : c.getApplicationInfo().icon).setContentTitle(title).setContentText("Откройте приложение, чтобы прочитать");
                b.setPublicVersion(pub.build());
            }
            nm.notify(id, b.build());
        } catch (Throwable ignore) {}
    }

    /** Нажали на уведомление, когда приложение было закрыто: открыть нужный раздел. */
    static void openFromNotification(final Activity a) {
        Intent it = a.getIntent();
        if (it == null) return;
        final String open = it.getStringExtra(EXTRA_OPEN);
        if (open == null) return;
        it.removeExtra(EXTRA_OPEN);
        if (!"chat".equals(open) && !"orders".equals(open)) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    Method m = a.getClass().getDeclaredMethod("showTab", String.class);
                    m.setAccessible(true);
                    m.invoke(a, open);
                } catch (Throwable ignore) {}
            }
        });
    }

    // ───────────────────────── «Настройки» в кабинете ─────────────────────────

    static int dp(Activity a, float v) { return IvaSearchPatch.dp(a, v); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }
    static Drawable drawable(Activity a, String n) { return IvaSearchPatch.drawable(a, n); }

    /** Строка «Настройки» — как штатные строки кабинета (bg_card, 16dp, значок 20dp оранжевый, 15sp), перед «Инструкция». */
    static void ensureRow(final Activity a) {
        int id = a.getResources().getIdentifier("profHelp", "id", a.getPackageName());
        View help = id == 0 ? null : a.findViewById(id);
        if (help == null || !(help.getParent() instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) help.getParent();
        if (box.findViewWithTag(ROW_TAG) != null) return;
        LinearLayout row = new LinearLayout(a);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = drawable(a, "bg_card");
        if (bg != null) row.setBackground(bg);
        row.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
        row.setClickable(true);
        ImageView ic = new ImageView(a);
        Drawable gear = drawable(a, "ic_gear");
        if (gear != null) ic.setImageDrawable(gear);
        int orange = color(a, "orange", 0xffff8f1f);
        ic.setColorFilter(orange);
        row.addView(ic, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        TextView t = new TextView(a);
        t.setText(ROW_TITLE);
        t.setTextSize(15f);
        t.setTextColor(color(a, "text", 0xfff2f4f8));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(a, 14);
        row.addView(t, tl);
        ImageView chev = new ImageView(a);
        Drawable ch = drawable(a, "ic_chevron");
        if (ch != null) {
            chev.setImageDrawable(ch);
            chev.setColorFilter(color(a, "gray", 0xff9aa1b0));
            row.addView(chev, new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18)));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(a); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 10);
        box.addView(row, box.indexOfChild(help), lp);
    }

    static Dialog settings;

    static void openSettings(final Activity a) {
        try {
            if (settings != null && settings.isShowing()) return;
        } catch (Throwable ignore) {}
        try {
            final int cBg = color(a, "bg", 0xff101217), cText = color(a, "text", 0xfff2f4f8),
                cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
            final boolean admin = loggedIn(a) && isAdmin(a);
            LinearLayout root = new LinearLayout(a);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(cBg);

            LinearLayout bar = new LinearLayout(a);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(a, 4), 0, dp(a, 16), 0);
            TextView back = new TextView(a);
            back.setText("←");
            back.setTextSize(24f);
            back.setTextColor(cText);
            back.setGravity(Gravity.CENTER);
            back.setContentDescription("Назад");
            bar.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
            TextView title = new TextView(a);
            title.setText(ROW_TITLE);
            title.setTextSize(18f);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(cText);
            bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            root.addView(bar, new LinearLayout.LayoutParams(-1, dp(a, 56)));

            ScrollView scroll = new ScrollView(a);
            final LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 24));
            scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

            TextView sec = new TextView(a);
            sec.setText("Уведомления");
            sec.setTextSize(17f);
            sec.setTypeface(null, android.graphics.Typeface.BOLD);
            sec.setTextColor(cText);
            body.addView(sec);

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            Drawable bg = drawable(a, "bg_card");
            if (bg != null) card.setBackground(bg);
            card.setPadding(dp(a, 16), dp(a, 6), dp(a, 12), dp(a, 6));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = dp(a, 10);
            body.addView(card, cl);

            final Switch all = switchRow(a, card, "Уведомления", "Строка состояния, шторка и всплывающие уведомления", K_ALL, cText, cGray, cOrange);
            final List<Switch> kids = new ArrayList<Switch>();
            kids.add(switchRow(a, card, "Новые сообщения", "Когда вам пишут в чат", K_MSG, cText, cGray, cOrange));
            if (admin) kids.add(switchRow(a, card, "Новые заявки", "Заявки на ваши инструменты", K_ORD, cText, cGray, cOrange));
            kids.add(switchRow(a, card, "Обновления приложения", "Когда вышла новая версия", K_UPD, cText, cGray, cOrange));

            final TextView status = new TextView(a);
            status.setTextSize(13f);
            status.setTextColor(cGray);
            status.setLineSpacing(dp(a, 2), 1f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = dp(a, 12);
            body.addView(status, sl);

            final TextView sys = linkButton(a, body, "Открыть настройки уведомлений Android", cOrange);
            sys.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openSystemSettings(a); } });
            final TextView test = linkButton(a, body, "Показать пробное уведомление", cOrange);
            test.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!systemAllows(a)) { askPermission(a); openSystemSettingsIfDenied(a); return; }
                    showNote(a, CH_MSG, ID_TEST, "Ива: уведомления работают", "Так будут выглядеть уведомления о сообщениях, заявках и обновлениях", "chat");
                }
            });

            TextView note = new TextView(a);
            note.setText("Сейчас уведомления приходят, пока приложение открыто или свёрнуто. Когда приложение закрыто — в следующей версии.");
            note.setTextSize(12f);
            note.setTextColor(cGray);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
            nl.topMargin = dp(a, 14);
            body.addView(note, nl);

            final Runnable refresh = new Runnable() {
                @Override public void run() {
                    boolean master = all.isChecked();
                    for (Switch s : kids) { s.setEnabled(master); ((View) s.getParent()).setAlpha(master ? 1f : 0.45f); }
                    boolean ok = systemAllows(a);
                    status.setText(!master ? "Все уведомления выключены."
                        : ok ? "Android разрешает уведомления. Каждый вид можно отдельно настроить и в настройках Android."
                        : "Уведомления запрещены в настройках Android — включите их, иначе они не появятся.");
                    status.setTextColor(master && !ok ? cOrange : cGray);
                }
            };
            all.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                    setPref(a, K_ALL, v);
                    if (v && !systemAllows(a)) askPermission(a);
                    refresh.run();
                }
            });
            refresh.run();

            final Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
            back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
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
            // вернулись из настроек Android / окна разрешения — обновить строку состояния
            root.getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
                @Override public void onWindowFocusChanged(boolean has) { if (has) refresh.run(); }
            });
            settings = d;
            d.show();
        } catch (Throwable e) {
            try { Toast.makeText(a, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        }
    }

    static Switch switchRow(final Activity a, LinearLayout card, String title, String sub, final String k, int cText, int cGray, int cOrange) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, 10), 0, dp(a, 10));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(cText);
        col.addView(t);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextSize(13f);
        s.setTextColor(cGray);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(a);
        sw.setChecked(pref(a, k));
        if (Build.VERSION.SDK_INT >= 23) {
            int[][] st = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(st, new int[] {cOrange, 0xffd0d3da}));
            sw.setTrackTintList(new ColorStateList(st, new int[] {(cOrange & 0x00ffffff) | 0x88000000, 0x55a0a4ad}));
        }
        if (!K_ALL.equals(k)) {
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) { setPref(a, k, v); }
            });
        }
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (sw.isEnabled()) sw.toggle(); } });
        card.addView(row);
        return sw;
    }

    static TextView linkButton(Activity a, LinearLayout body, String text, int cOrange) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setTextSize(15f);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setTextColor(cOrange);
        b.setGravity(Gravity.CENTER);
        Drawable bg = drawable(a, "bg_card");
        if (bg != null) b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(a, 48));
        lp.topMargin = dp(a, 10);
        body.addView(b, lp);
        return b;
    }

    static void askPermission(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                a.requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 7101);
        } catch (Throwable ignore) {}
    }

    static void openSystemSettingsIfDenied(Activity a) {
        if (Build.VERSION.SDK_INT < 33) openSystemSettings(a);
    }

    static void openSystemSettings(Activity a) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
                i.putExtra("android.provider.extra.APP_PACKAGE", a.getPackageName());
            } else {
                i = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
                i.setData(android.net.Uri.parse("package:" + a.getPackageName()));
            }
            a.startActivity(i);
        } catch (Throwable e) {
            try { Toast.makeText(a, "Откройте «Настройки» телефона → Приложения → Ива → Уведомления", Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {}
        }
    }

}

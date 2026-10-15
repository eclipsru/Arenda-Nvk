package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 10.94 — пробная («жёлтая») версия приложения у eclips.ru@mail.ru и eclipsik.ru@mail.ru (просьба владельца 16.10.2026).
 *
 * Как на сайте (жёлтая карточка «тестовая версия»): бот сборки кладёт app-test.json и APK в корень сайта; всем остальным
 * пробная версия не видна. В кабинете этих двух входов — жёлтая кнопка «Обновить до версии N» (над оранжевой кнопкой
 * выпущенного обновления и «О приложении»). Нажатие — выбор:
 *   • «Обновить версию N» — скачать и сразу поставить (та же загрузка с процентами на кнопке, что у оранжевой, 10.80);
 *   • «Релиз версии N» — выпустить для всех: строка в app_releases, как кнопка «Подтвердить релиз» на сайте
 *     (сначала проверяем, что файл уже лежит на сайте, и спрашиваем подтверждение). Право выпуска проверяет база:
 *     eclips — с 05.10; eclipsik — после SQL 20261022_app_releases_tester.sql.
 * Кнопка видна, пока пробная версия новее выпущенной; выпустили — жёлтая исчезает, у всех появляется оранжевая.
 *
 * Уведомление «Уведомления пробной версии» (только у этих двух, можно выключить): новая жёлтая версия — сразу, как
 * приложение её увидит: открыто — проверка раз в минуту; закрыто — фоновая проверка Android (раз в ~15 минут).
 * Только чтение файла сайта; без личных данных.
 */
public class IvaTestPatch {
    static final String TEST_URL = IvaNotifyPatch.APK_BASE + "app-test.json";
    static final String[] TESTERS = {"eclips.ru@mail.ru", "eclipsik.ru@mail.ru"};
    static final String TEST_TAG = "iva_test_btn";
    static final String K_TEST = "test_build";                       // переключатель «Уведомления пробной версии»
    static final String CH_TEST = "iva_test_build";
    static final int ID_TEST_NOTE = 7205;
    static final long FEED_EVERY_MS = 60000L;                        // файл сайта — не чаще раза в минуту
    static final int YELLOW = 0xFFFFEA00, YELLOW_TRACK = 0x59FFEA00, INK = 0xFF111111;   // жёлтый — как у карточки сайта
    static final String RELEASE_POST = "/rest/v1/app_releases";
    static final String NOTE_TITLE = "Уведомления пробной версии", NOTE_SUB = "Сразу, как появится новая жёлтая версия";

    static volatile int tCode;
    static volatile String tName = "", tFile = "", tNotes = "";
    static volatile boolean tReinstall, tBusy, relPosting;
    static volatile long tAt;
    static volatile WeakReference<View> btn = new WeakReference<View>(null);

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    /** Пробная версия видна только этим двум входам. */
    static boolean isTester(String email) {
        String e = email == null ? "" : email.trim().toLowerCase();
        for (String t : TESTERS) if (t.equals(e)) return true;
        return false;
    }

    /** Имя файла из app-test.json: только «ProkatInstrumenta-X.Y.apk» (в корне сайта); иначе "". */
    static String feedFile(String apk) {
        String u = apk == null ? "" : apk.trim();
        if (u.startsWith(IvaNotifyPatch.APK_BASE)) u = u.substring(IvaNotifyPatch.APK_BASE.length());
        return u.matches("ProkatInstrumenta-[0-9]+\\.[0-9]+\\.apk") ? u : "";
    }

    /** Жёлтая кнопка: вход из двух, выпущенная версия известна, пробная новее выпущенной. */
    static boolean showTest(boolean tester, int test, int released) {
        return tester && test > 0 && released > 0 && test > released;
    }

    /** Надпись на жёлтой кнопке: ещё не стоит — «Обновить до версии N»; уже стоит — осталось выпустить. */
    static String title(int test, int installed, String name) {
        String n = name == null || name.isEmpty() ? String.valueOf(test) : name;
        return installed > 0 && installed >= test ? "Версия " + n + " · выпустить для всех" : "Обновить до версии " + n;
    }

    /** Варианты окна выбора: «Обновить версию N» и «Релиз версии N». */
    static String[] choices(String name) {
        return new String[]{"Обновить версию " + name, "Релиз версии " + name};
    }

    /** Тело строки app_releases — как у кнопки сайта «Подтвердить релиз» (заметка — не длиннее 500). */
    static String releaseBody(int code, String name, String file, boolean reinstall, String notes) {
        String n = notes == null ? "" : notes.trim();
        if (n.length() > 500) n = n.substring(0, 500);
        return "{\"version_code\":" + code + ",\"version_name\":" + IvaNotifyPatch.jsonStr(name) + ",\"apk_url\":" + IvaNotifyPatch.jsonStr(file)
            + ",\"reinstall\":" + reinstall + ",\"notes\":" + IvaNotifyPatch.jsonStr(n) + "}";
    }

    /** Ответ базы на выпуск — понятной фразой; успех — null. */
    static String releaseError(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return null;
        if (b.contains("23505")) return "Эта версия уже выпущена";
        if (b.contains("42501") || "401".equals(c) || "403".equals(c))
            return "Нет права выпускать версию (для eclipsik.ru@mail.ru нужен SQL 20261022)";
        if (b.contains("PGRST205") || "404".equals(c)) return "В базе нет таблицы релизов (SQL 20261005)";
        if (b.contains("23514")) return "База не приняла номер или файл версии";
        return "Не удалось выпустить версию (ответ " + (c.isEmpty() ? "—" : c) + ")";
    }

    /** Уведомлять ли о пробной версии: новее установленной, о ней ещё не сообщали (ни как о пробной, ни как о выпущенной). */
    static boolean testNews(int test, int installed, int notified, int updNotified) {
        return test > 0 && test > installed && test != notified && test != updNotified;
    }

    // ───────────── файл сайта ─────────────

    /** app-test.json: {code, name, file, notes, reinstall}; нет сети или странный файл — null. */
    static JSONObject feed() {
        try {
            String body = IvaNotifyPatch.httpGet(TEST_URL);
            if (body == null) return null;
            JSONObject j = new JSONObject(body.trim());
            int code = j.optInt("versionCode", 0);
            String name = j.optString("versionName", "");
            String file = feedFile(j.optString("apk", ""));
            if (code <= 0 || file.isEmpty() || !name.matches("[0-9]+\\.[0-9]+")) return null;
            JSONObject o = new JSONObject();
            o.put("code", code);
            o.put("name", name);
            o.put("file", file);
            o.put("notes", j.optString("notes", ""));
            o.put("reinstall", j.optBoolean("reinstall", false));
            return o;
        } catch (Throwable e) {
            return null;
        }
    }

    // ───────────── кабинет ─────────────

    static void onTab(Activity a, String tab) {
        if (!"profile".equals(tab)) return;
        try { apply(a); } catch (Throwable ignore) {}
        feedCheck(a, false);
    }

    static boolean me(Activity a) {
        return IvaNotifyPatch.loggedIn(a) && isTester(String.valueOf(IvaClientPatch.get(a, "userEmail")));
    }

    /** Сразу — по последнему известному; затем (не чаще раза в минуту, force — сейчас) читаем файл сайта в фоне. */
    static void feedCheck(final Activity a, boolean force) {
        if (!me(a)) return;
        long now = System.currentTimeMillis();
        if (tBusy || (!force && tAt > 0 && now - tAt < FEED_EVERY_MS)) return;
        tBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject j = feed();
                    if (j != null) {
                        tName = j.optString("name", "");
                        tFile = j.optString("file", "");
                        tNotes = j.optString("notes", "");
                        tReinstall = j.optBoolean("reinstall", false);
                        tCode = j.optInt("code", 0);
                        tAt = System.currentTimeMillis();
                    }
                } catch (Throwable ignore) {
                } finally {
                    tBusy = false;
                }
                post(a);
            }
        }).start();
    }

    static void post(final Activity a) {
        try {
            a.runOnUiThread(new Runnable() {
                @Override public void run() { try { apply(a); } catch (Throwable ignore) {} }
            });
        } catch (Throwable ignore) {}
    }

    /** Жёлтая кнопка над оранжевой (если есть), иначе над «О приложении»; не нужна — убираем. */
    static void apply(Activity a) {
        if (a.isFinishing()) return;
        Object anchor = IvaClientPatch.get(a, "profVer");
        if (!(anchor instanceof View)) return;
        ViewGroup box = IvaNotifyPatch.updateBox((View) anchor);
        if (box == null) return;
        int code = tCode, installed = IvaNotifyPatch.installedCode(a);
        boolean want = showTest(me(a), code, IvaNotifyPatch.relCode);
        View old = box.findViewWithTag(TEST_TAG);
        boolean driving = old != null && old == IvaNotifyPatch.updBtn.get() && IvaNotifyPatch.updState != IvaNotifyPatch.UPD_IDLE;
        if (old != null && !want && !driving) { box.removeView(old); return; }
        if (!want) return;
        String text = title(code, installed, tName);
        if (old != null) {
            if (!driving && old instanceof TextView && !text.contentEquals(((TextView) old).getText())) ((TextView) old).setText(text);
            return;
        }
        Object ver = ((View) anchor).getParent();
        View upd = box.findViewWithTag(IvaNotifyPatch.UPDATE_BTN_TAG);
        int at = box.indexOfChild(upd != null && upd.getParent() == box ? upd : ver instanceof View ? (View) ver : null);
        if (at < 0) return;
        View b = button(a, text);
        if (box instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = IvaNotifyPatch.dp(a, 10);
            box.addView(b, at, lp);
        } else {
            box.addView(b, at);
        }
        btn = new WeakReference<View>(b);
    }

    /** Вид — как у оранжевой кнопки обновления (15sp, жирный, радиус 12dp, заливка-прогресс), только жёлтая с тёмным текстом. */
    static View button(final Activity a, String text) {
        Button b = new Button(a);
        b.setTag(TEST_TAG);
        b.setTextColor(INK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setText(text);
        float r = IvaNotifyPatch.dp(a, 12);
        GradientDrawable track = new GradientDrawable();
        track.setColor(YELLOW_TRACK);
        track.setCornerRadius(r);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(YELLOW);
        fill.setCornerRadius(r);
        LayerDrawable bg = new LayerDrawable(new Drawable[]{track, new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        bg.setLevel(10000);
        b.setBackground(bg);
        b.setPadding(IvaNotifyPatch.dp(a, 14), IvaNotifyPatch.dp(a, 12), IvaNotifyPatch.dp(a, 14), IvaNotifyPatch.dp(a, 12));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { tap(a, v); } catch (Throwable ignore) {} }
        });
        return b;
    }

    /** Нажали жёлтую кнопку: «Обновить версию N» или «Релиз версии N». */
    static void tap(final Activity a, final View v) {
        final int code = tCode;
        final String name = tName.isEmpty() ? String.valueOf(code) : tName, file = tFile;
        if (code <= 0 || file.isEmpty()) return;
        int st = IvaNotifyPatch.updState;
        if (v == IvaNotifyPatch.updBtn.get() && st == IvaNotifyPatch.UPD_READY) {   // окно установки закрыли — сразу «Установить»
            IvaNotifyPatch.updateTap(a, code, name, IvaNotifyPatch.APK_BASE + file);
            return;
        }
        String notes = tNotes.trim();
        if (notes.length() > 300) notes = notes.substring(0, 300) + "…";
        IvaClientPatch.styledChoice(a, "Пробная версия " + name,
            (notes.isEmpty() ? "" : notes + "\n\n") + "Сейчас её видите только вы. «Релиз» — версия станет доступна всем.",
            choices(name), new IvaClientPatch.Choice() {
                @Override public void pick(int i) {
                    try {
                        if (i == 0) update(a, v, code, name, file);
                        else if (i == 1) confirmRelease(a, code, name, file);
                    } catch (Throwable ignore) {}
                }
            });
    }

    /** «Обновить версию N»: та же загрузка и установка, что у оранжевой кнопки (проценты — на жёлтой). */
    static void update(Activity a, View v, int code, String name, String file) {
        if (IvaNotifyPatch.installedCode(a) >= code) { toast(a, "Версия " + name + " уже установлена"); return; }
        int st = IvaNotifyPatch.updState;
        if (st != IvaNotifyPatch.UPD_IDLE && st != IvaNotifyPatch.UPD_READY) { toast(a, "Обновление уже скачивается"); return; }
        IvaNotifyPatch.updBtn = new WeakReference<View>(v);
        IvaNotifyPatch.updateTap(a, code, name, IvaNotifyPatch.APK_BASE + file);
    }

    static void confirmRelease(final Activity a, final int code, final String name, final String file) {
        IvaClientPatch.styledChoice(a, "Выпустить версию " + name + " для всех?",
            "У всех пользователей появится «Обновить до версии " + name + "». Отменить выпуск можно только в базе (SQL в 20261005).",
            new String[]{"Выпустить для всех"}, new IvaClientPatch.Choice() {
                @Override public void pick(int i) { if (i == 0) try { release(a, code, name, file); } catch (Throwable ignore) {} }
            });
    }

    /** «Релиз версии N»: файл уже на сайте → строка в app_releases (право проверяет база). */
    static void release(final Activity a, final int code, final String name, final String file) {
        if (relPosting) return;
        relPosting = true;
        final String body = releaseBody(code, name, file, tReinstall, tNotes);
        toast(a, "Выпускаем версию " + name + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err;
                try {
                    if (!onSite(IvaNotifyPatch.APK_BASE + file)) err = "Файла версии " + name + " ещё нет на сайте — подождите пару минут";
                    else {
                        String[] r = IvaClientPatch.apiCall(a, RELEASE_POST, "POST", body, true);
                        err = r == null ? "Нет ответа от базы" : releaseError(r[0], r.length > 1 ? r[1] : "");
                    }
                } catch (Throwable e) {
                    err = "Не удалось выпустить версию (" + IvaNotifyPatch.shortErr(e) + ")";
                }
                relPosting = false;
                if (err == null) {
                    IvaNotifyPatch.relName = name;
                    IvaNotifyPatch.relApk = IvaNotifyPatch.APK_BASE + file;
                    IvaNotifyPatch.relCode = code;
                    IvaNotifyPatch.relAt = System.currentTimeMillis();
                }
                final String msg = err == null ? "Версия " + name + " выпущена — теперь её видят все" : err;
                try {
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            toast(a, msg);
                            try { apply(a); } catch (Throwable ignore) {}
                            try { IvaNotifyPatch.applyRelease(a); } catch (Throwable ignore) {}
                        }
                    });
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    /** Файл уже лежит на сайте (HEAD → 200). */
    static boolean onSite(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            return c.getResponseCode() == 200;
        } catch (Throwable e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    static void toast(final Activity a, final String s) {
        try { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {}
    }

    // ───────────── уведомление ─────────────

    /** Новая жёлтая версия — уведомление (открыто приложение — раз в минуту, закрыто — фоновая проверка). */
    static void check(Context c, String email, boolean admin) {
        if (!isTester(email) || !IvaNotifyPatch.on(c, K_TEST, admin) || IvaNotifyPatch.quiet(c, admin)) return;
        SharedPreferences p = IvaNotifyPatch.prefs(c);
        long now = System.currentTimeMillis(), last = p.getLong("test_checked", 0);
        if (last > 0 && now >= last && now - last < FEED_EVERY_MS - 5000) return;
        JSONObject j = feed();
        p.edit().putLong("test_checked", now).apply();
        if (j == null) return;
        int code = j.optInt("code", 0);
        if (!testNews(code, IvaNotifyPatch.installedCode(c), p.getInt("test_notified", 0), p.getInt("upd_notified", 0))) return;
        p.edit().putInt("test_notified", code).apply();
        String name = j.optString("name", "");
        IvaNotifyPatch.showNote(c, CH_TEST, ID_TEST_NOTE, "Пробная версия " + name,
            "Новая жёлтая версия. Откройте — обновить или выпустить для всех", "test", code + "|" + name);
    }

    /** Нажали уведомление: «Профиль» и жёлтая кнопка (как переход к оранжевой). */
    static void openFromNote(final Activity a) {
        IvaNotifyPatch.tab(a, "profile");
        feedCheck(a, true);
        final long until = System.currentTimeMillis() + IvaNotifyPatch.ROUTE_WAIT_MS;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                View b = btn.get();
                if (b != null && b.getParent() != null) { IvaNotifyPatch.reveal(b); return; }
                if (System.currentTimeMillis() < until && !a.isFinishing()) h.postDelayed(this, IvaNotifyPatch.ROUTE_STEP_MS);
            }
        });
    }
}

// 10.56: «Настройки» в кабинете → отдельная страница; пока одна настройка — «Уведомления»:
// общий переключатель и три вида («Новые сообщения», «Новые заявки» — только админам, «Обновления приложения»).
// Уведомления — системные (строка состояния, шторка, всплывающие), у каждого вида свой канал Android.
// Штатный опрос (pollRun, раз в 30 с) выключается; вместо него — наш с теми же запросами, отметкой «Заявки •»
// и проверкой настроек. Новое определяем по id (а не по числу), увиденное помним на телефоне.
// Работает, пока приложение открыто или свёрнуто; когда закрыто — этап 2 (10.65, внизу файла): системный планировщик
// Android раз в ~15 минут делает те же проверки с теми же переключателями и той же памятью «уже показано».
// 10.57: значок — силуэт логотипа в строке состояния, цветной логотип в шторке; в «Чате» не уведомляем только о видимом;
//        первый запуск сообщает о непрочитанных за сутки.
// 10.73: у админов — расписание: в какие дни и часы приходят уведомления (одно на все виды, у каждого дня свои часы);
//        что пришло в другое время, показываем в начале разрешённого (решения владельца 14.10.2026).
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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    static final String EXTRA_TARGET = "iva_target";  // 10.59: что именно открыть — id сообщения / id заявки / «код|версия»
    static final long ROUTE_WAIT_MS = 15000;          // сколько ждать загрузки раздела после нажатия
    static final long ROUTE_STEP_MS = 250;
    static final String UPDATE_BTN_TAG = "iva_selfupdate_btn";   // штатная кнопка SelfUpdate «Обновить до версии …»
    static final int MAX_SEEN = 300;

    /** Значок строки состояния — силуэт дрели из логотипа (белый по прозрачному, 96×96; Android рисует его по прозрачности,
     *  цветной логотип там стал бы белым квадратом). Ресурсы в сборке не пересобираются — PNG здесь, в коде. */
    static final String STATUS_ICON_PNG =
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAANF0lEQVR42u2deZBcVRXGf697QtCEBAgBNZAAFSCAxSIYRXZUFgUlFKKFFCRsGpLSKgSLRUpZCtRSQoEYFgtksURAMVKlFsSoEEBlR8" +
        "EYlgQSEJCQgAYCM92ff9xzK2cuPT3dM/2m3yy36tV097z3+vbZ73fOPS+TxMho3yiNkCCXkRlts95O7BihVUtH2f5WAI1owMASPjPCV+yzbYBp9nk2woB8zEwq8fsAPwOeBhYBY9y5tW8y4oS7CaMa" +
        "MB2R8F3uuqOA2cCB9v+3gX2Bh+3/1REGNEZM6hCs5MwMwHjgy8DJwO7uvF8B5wJL7N6VuhMYxgzwhO4ApgPPAS8n/yubVsT3k4ETgFOBLd39njPC39ILI7sPScPtKNuBpA0kzZQ0RtIiSa9LOsb+N0" +
        "pSyV23s6T5do4f70qaJ2mCnVdKrqt7DCfCZ3bE90dL+rsRca6kOfb6DUmbu/MOkvQLI3Q67pM0PWFuU/MaqibIRyclO6KdPxZYbWbnN0AnMMrMTMU+vwxYCJwJ7B+Nhd1jFPAKcBHwI/d91UZj/6Fg" +
        "grIaEh0lMOtBIidL+pNJ7jJJe0laZe+rTqqriZRXJK1z76+XtKWbR6k/v6U0yKKVspPGeJRMaksuHv8iMNfeTwfmAauACSapk4H7gU1rxOlZIvElYDTwGPBZYBaw0r5TDTnaQQxFZI6wPpx7v819Hf" +
        "CuI8L+wDnAwcAbFiqeb4xbZkT8sGNU1gB91gEXmFl62z6rJKFr339ggX2Aj6HHAIcBRwC7ApuZLV5rhP0LsB3wBTu/mqzyu5oUtkiUFcCnbGVLI3H9UGCAd2ijTOXPBKY2SLiqi93lsJhqE/BLvPYd" +
        "YG/gCbtHV8tVvEAMSBc8XwLOBnZJ8JaSM09KCF1u4XyiFv0B+HTCxCHFgHSJ/xmz43s7wmcDBBx6v1C11/8DtjKfkvUp1CyoE04Jv48R/rCE8OUBnFM0M2XDeLYCTjImtJz47dIAH9lgTvVsCx1p0l" +
        "a3QuJxgrAWGGvfvRj4HLA18GjD2E4fpHAgCd/hVpxTgWuAB434SlauAzWnyIiHCQmUd4DXgN+Z5D+al/0fKA1IId8PAqcDXzVpi+amzMCPlbY4e58zgyXgeeCFplDNgvqAslu0bAzMAb4OTEywmoEm" +
        "foyojgQ+b77nSsN4nknmXs1VOnPSAL9g2dAc2hnAFLcwKrfR2cZxh2H72wKP9xtYKwAD/OQz4DjgLGCnhPBZG82OzPQ8ZBjRve0gfKtNUMlJM06tpyempiMxO13AbcBGwOEN4jP9GdEPXQtc6HxU1m" +
        "qIoXFx6B8sXEog3wMlLXTQbZfBuelrSfq5pF3dtTe581o9qpI63fsHJW0oqaPd0HpfNSCN5fc0iZ+RxPJRrb25+S1wMXCfvR9tod+LSWyeRwi8CrgcuIqAorYfh+ljTjW+nmYJioqTtC6XyPDSfK+k" +
        "Q921HU6LTrRUYFeNhEh/Jb8iaa2kSyVNKlpyqa/EnyTpMklvJeamlro/5hLdJGp/pJmD3rJSfR1xHucm358NRgaUXTL75QYI/7SkU6y6ICX8J60KIR2vJ36iFdL/tqUQy31JmheFAXHiX0ukq1qD8C" +
        "9KOkPS2Boma7qkX9cg1gpJp0uaaOescPfur/TP72vFQlEYECc+o4fIJo7Vki4yIpIkq3eSdEMN6X5N0nckbZp8108TIjY74rxWWr1OqUhmpxkGxIlPsgqCSuJwZSp+haQpNa6fIunyxFfInOI8V13g" +
        "jx0lvdAPDeh0BVP7Fln6G2FAnPhVyY+LxLnRCEYi9RMlXVijiqxT0nWSdkhKTJC0haRLLBrqiyP2UdcaSYcVnfi9MSAS5gOS3qxh7y+rwaixZv9frEGg2yXtUeOacZLOShx7tQ8ON46FFh4Xnvi9MS" +
        "BGLTMT6Y9/Z7lzR0k62SKfdNwl6YAa9x0tabak52o49r4Q/yFJRyVzyoYCA65KJD/+4OctJD3OYv10PCDpiB7i/2MlPdFPwntnO6+/FWpFhCIinDCV7tts4t/JBqSl40ngu4SdIjIIoMuOww2y2CuB" +
        "ovsLCm5j840FW2Ms//BSXsn0gYAiovou7gEki7BDp6u3PM1MSyrx+0n6fQ8gXSvGOkkLJD1rvmSNfX5BjbkMGhMUVfruOihlxG5ulzS+huPbXdJtSaTSSsJ7k7iyxv8e66GItzBHqQGMfyX1905lwC" +
        "Wm6hEh3Q74CfA34OgBSriPBV53xV0CdrZkkCjohsRGJnV/A0mSEyz1+CHgh1ZJcJJLwOSZcoxzG2c+wEPlHa7OqJAMqJeSjNUAWwJLjcDUYcZyc3wbJ1mwrE2/LeYg7gX2y7u6IQ8NiEWuK4Hbe0nb" +
        "Va2AaWNXcVBuI/Hjb5Mli7bivRXTg8IExRzt+ZZB6imcKzm7227C43zOu1bzc2RRzVBvE4pS8yzwA967pza9V9YgYfKOyePaYjSh3HDrHNOduZelZC56uZuwCyVubGtGIquJI86jAiL+mFeBW4E/Wy" +
        "S2oqiLsUbrgiKhNiHsLNzbmZx6kp8S/r+mScuAG3NgQtTYZVZsVfzRZAkKVs5xZY0FWWdy+IXbO5aZ2tbd7ymXNmzliN870xaFo4qMBZWalK6MsGltDnCQacM6k/CO5CgT9uPeaJHIbMJ2/g1dcVRu" +
        "VceEVgKVthVctdgE1asJmgp81Fadm9nn/yHsq7qf0Hsh1gj5LUUTbX0xvpf1RX9M0ceBv5LD5rp2MoCEoNU+nBcJcg1wCs3vYmykBLEDuAGYOVQZ4MPPUh0HrB6YUiG0eXk4Bw2I37kW2B7492BcCT" +
        "ej7l01jnrxfoQpHgXucZ+1ch1QMYDuhCJjQaUCfPePc1o5x/ufBGzgQMERBjiJz4A7CVuCyi02ETFQmAoc6mCSEQY4O10m9F+43pmzPMbcnO/fVifcXwGI3Uv+yfrNcq12yBVgN0K+ulDOuN2OKcIU" +
        "L5gpymOnSkzMfKWIzrgIrQqi7d/XwLNW4/YRb1pFSJWuSUJVP4+4Mq8OFw3wzngx8EgOWhDvN4H1G8LLNQgft9NGyCUbLgzwvmB+zj98ttO4Mt27bE0nNGDdne4dWIYFA6IW3E7Akcq0FruPRN+FkB" +
        "+O31klNGT6IwEz+qVp4QJCejV3TSgKA6JZWAPcnMPK2Iegc+z1x4zQdwMHuGipQmjScbUzR0PaCadmaBoBSe3IIRzNCEmhhYQ8ccyS+aSRCBm/DgLkfg/d0d8hqQG46GeJESgPZwxhU/gMd/8sIX7F" +
        "oIsSIaeg4WCC/HwyQuOMPM1dJYmGRPdC4VeB04BvELq75IYjFY0BUeLvAv6VAz6Ek/gsCQDKhP5A5zkzuMAWiKXhwoAolZ2ElGWe+E3FRUhdwKUE4O5OQhp1sYWmexDaYVbJob1PEdtWRse4BSFlOa" +
        "7FMXnaBPA6QuOODuDbhA4vkfERxlhqIWxnD6voIaMBXgtesXVBq0LSarIAu4PQRfc8s/VLjfi+knuUXbMDcCI5QNpF7x09vwXYUJXupfGLCMn6UwyaeIYAV9eKirxQzMohMissAyLBHiJUVvQlDveS" +
        "XCbkng+2EPQTJvHnESDwSh3pjqZvAusLfrPhoAEx8rimD4TvcpK81JzoAcAk4B/mcDe183orKI73WsL6kncNdQb4dpI3EQq6egtJPeE7CBv0TrUopotQI3o9oVQ9Er7eajsWG2xgry8eDmEoTs1jp8" +
        "VzCMVb9VTfL6LWAN8kbE16xlbVdwA7OlNTj/D+uQEdhGZ+h/XDFPair8WqlfT1p3MlLW+wDjTulLzY2iTsaRvEm9mVWavdzqmutrSkAjRsGgjiHyjpyV42caedteZb449pkm5tcldmSviXknY7ubY8" +
        "KBLxM0nbu84qnT08z8UT9Ba7ZrKkq2vsYVYTGpS226n1TJohy4C4kXqeK2fvqROKbNP3R+xxU99Nzm+W8LXa7QxYW7OiPEMmhnXb10ApPVb/AKHT+uOEKoezWV9dHUPEcgMwRAwlbya0VXjK/t/S58" +
        "MMpigoRiTLWF+Z4In1JKEZ7CGGVD5lhBufxPKNwhALbDV8vN0rrgO6GOhtTAVzwLskT6xbLul4c4jHSFraZIeV1HQtsoaB3sa3tctK0Z7xiPWWW21h6DhJh0h6pE4E1FNk05V0yp2RMLwQ7W2KBEdH" +
        "M7Q5oeXAROB7BiHUgpHr4T/Rty0xU3Vzcn1hNmsULR8QewvNMpzeJ2SaIfwKQs+Ka4G3XOKlcLtkisaACENMIdTnbNQLUJZGSa8BV9ixusiELyoWFLH/5YTe/h11iOejpLWGcO5GeOzgaof3DLldkg" +
        "PhCzLC9qKlhJ2X0H2zX8mFjTcA37dzfSw/KJ7TW9SUZAa8CXzLLa780/JiGeN0wuNRlrY1lu+n0ytqRqxM6Lq1CSFnuwUhKb7IsPl7Es2oMAhH0Z+oHSskNiGUjLzhTE0piZIYYUA+I41iChfLD3UG" +
        "eKJrsEv8YGXAkB3/B3PLZ+sQBTRvAAAAAElFTkSuQmCC";
    static final int AVATAR_PX = 192;               // цветной логотип в кружке слева (Android 11+)
    static final String SHORTCUT_PREFIX = "iva_note_";
    static final long SHORTCUT_DROP_MS = 5000;      // ярлык нужен только в момент показа — потом убираем из меню значка

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

    // ── 10.73: расписание уведомлений у админов (решения владельца 14.10.2026) ──
    // Одно расписание на сообщения, заявки и обновления; у каждого дня недели свои часы или «без уведомлений».
    // Вне расписания уведомление не показывается и не запоминается как показанное — оно придёт в начале разрешённого
    // времени (если сообщение уже прочитано или заявку уже взяли — не придёт). Время — часы телефона.
    static final String K_SCHED_ON = "sched_on", K_SCHED = "sched";
    static final String[] DAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    static final String[] DAYS_FULL = {"понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"};
    static final int SCHED_FROM = 9 * 60, SCHED_TO = 21 * 60;     // по умолчанию — каждый день с 09:00 до 21:00

    /** Расписание из строки «вкл,с,до;…» — 7 дней с понедельника, минуты от полуночи. Пусто или битое — 09:00–21:00. */
    static int[][] schedParse(String s) {
        int[][] d = new int[7][];
        String[] parts = s == null ? new String[0] : s.split(";", -1);
        for (int i = 0; i < 7; i++) {
            int[] v = {1, SCHED_FROM, SCHED_TO};
            if (i < parts.length) {
                String[] x = parts[i].split(",", -1);
                if (x.length == 3) try {
                    int on = Integer.parseInt(x[0].trim()), f = Integer.parseInt(x[1].trim()), t = Integer.parseInt(x[2].trim());
                    if ((on == 0 || on == 1) && f >= 0 && f < 1440 && t >= 0 && t < 1440) v = new int[] {on, f, t};
                } catch (NumberFormatException ignore) {}
            }
            d[i] = v;
        }
        return d;
    }

    static String schedFormat(int[][] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i > 0) b.append(';');
            b.append(d[i][0]).append(',').append(d[i][1]).append(',').append(d[i][2]);
        }
        return b.toString();
    }

    /** Можно ли уведомлять в день dow (0 = Пн) в минуту min. «С» позже «до» (22:00–08:00) — через полночь:
     *  часы после полуночи относятся ко дню начала. Одинаковые «с» и «до» — круглосуточно. */
    static boolean inSchedule(int[][] d, int dow, int min) {
        int[] t = d[dow];
        if (t[0] == 1) {
            if (t[1] == t[2]) return true;
            if (t[1] < t[2] ? (min >= t[1] && min < t[2]) : min >= t[1]) return true;
        }
        int[] y = d[(dow + 6) % 7];
        return y[0] == 1 && y[1] > y[2] && min < y[2];
    }

    /** Calendar.DAY_OF_WEEK (1 = воскресенье … 7 = суббота) → 0 = понедельник … 6 = воскресенье. */
    static int dowMon(int calendarDow) { return (calendarDow + 5) % 7; }

    /** Тихо ли сейчас: только у админа, только если расписание включено и сейчас не его время. */
    static boolean quietAt(boolean admin, boolean schedOn, int[][] d, int dow, int min) {
        return admin && schedOn && !inSchedule(d, dow, min);
    }

    /** Ближайшее начало разрешённого времени после (dow, min): {день, минута}; null — в расписании нет ни одного дня. */
    static int[] nextStart(int[][] d, int dow, int min) {
        for (int k = 1; k <= 7 * 1440; k++) {
            int m = min + k, day = (dow + m / 1440) % 7;
            if (inSchedule(d, day, m % 1440)) return new int[] {day, m % 1440};
        }
        return null;
    }

    static String hm(int min) { return (min / 60 < 10 ? "0" : "") + (min / 60) + ":" + (min % 60 < 10 ? "0" : "") + (min % 60); }

    /** Строка дня в настройках. */
    static String dayText(int[] t) {
        if (t[0] == 0) return "без уведомлений";
        if (t[1] == t[2]) return "круглосуточно";
        return hm(t[1]) + " – " + hm(t[2]) + (t[1] > t[2] ? " (через полночь)" : "");
    }

    /** Строка «сейчас» под переключателями (расписание включено). */
    static String schedStatus(int[][] d, int dow, int min) {
        if (inSchedule(d, dow, min)) return "Сейчас время по расписанию — уведомления приходят.";
        int[] n = nextStart(d, dow, min);
        if (n == null) return "В расписании нет ни одного дня — уведомления не придут.";
        return "Сейчас вне расписания — уведомления придут в " + DAYS[n[0]] + " в " + hm(n[1]) + ".";
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

    static final long FIRST_RUN_WINDOW_MS = 24L * 60 * 60 * 1000;

    /** «Чат» на экране (openWith != null): список ("") и открытая переписка с этим собеседником обновляются сами. */
    static boolean hiddenInChat(String openWith, String from) {
        if (openWith == null) return false;
        return openWith.isEmpty() || openWith.trim().equalsIgnoreCase(from == null ? "" : from.trim());
    }

    /** Время в виде, как у базы (UTC, до секунд) — строки такого вида сравниваются по порядку. */
    static String isoUtc(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    /** id сообщений не раньше since (created_at из базы: 2026-10-13T18:22:11.123+00:00). */
    static List<String> recent(List<String> ids, List<String> created, String since) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < ids.size() && i < created.size(); i++) {
            String c = created.get(i) == null ? "" : created.get(i).trim().replace(' ', 'T');
            if (c.length() >= 19 && c.substring(0, 19).compareTo(since) >= 0 && !ids.get(i).isEmpty()) out.add(ids.get(i));
        }
        return out;
    }

    static String messageTitle(int n) { return n > 1 ? "Новые сообщения: " + n : "Новое сообщение"; }

    static String orderTitle(int n) { return n > 1 ? "Новые заявки: " + n : "Новая заявка"; }

    static String orderText(String toolsText) {
        String t = oneLine(toolsText, 100);
        return t.isEmpty() ? "Поступила новая заявка на ваш инструмент" : t;
    }

    // 10.94 (просьба владельца 16.10): о выпущенной версии — у всех, как можно скорее: проверка раз в 5 минут
    // (открыто приложение) и при каждой фоновой проверке Android (закрыто, раз в ~15 минут). Раньше — раз в 6 часов.
    static final long UPDATE_NOTE_EVERY_MS = 5L * 60 * 1000;
    static final String UPD_SUB = "Когда для всех вышла новая версия — сразу";
    static boolean updateDue(long last, long now) { return last <= 0 || now - last >= UPDATE_NOTE_EVERY_MS - 5000 || now < last; }

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

    static int[][] loadSched(Context c) { return schedParse(prefs(c).getString(K_SCHED, "")); }

    static void saveSched(Context c, int[][] d) { prefs(c).edit().putString(K_SCHED, schedFormat(d)).apply(); }

    /** 10.73: сейчас вне расписания админа — уведомление ждёт начала разрешённого времени. Сбой — не мешаем уведомлениям. */
    static boolean quiet(Context c, boolean admin) {
        try {
            if (!admin || !prefs(c).getBoolean(K_SCHED_ON, false)) return false;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            return quietAt(true, true, loadSched(c), dowMon(cal.get(java.util.Calendar.DAY_OF_WEEK)),
                cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE));
        } catch (Throwable e) {
            return false;
        }
    }

    // ── 10.76: напоминание клиенту об окончании аренды (решения владельца 14.10.2026) ──
    // Конец = время «Выдал» (ставит база, SQL 20261014_rent_reminder.sql) + срок инструмента в сутках. Клиент выбирает,
    // за сколько напомнить: за 1, 2 или 3 часа; по умолчанию выключено. Открыто — проверка не чаще раза в 5 минут,
    // закрыто — каждая фоновая проверка (Android будит примерно раз в 15 минут, поэтому напоминание может прийти
    // на несколько минут позже выбранного). После конца срока не напоминаем. Админам — не показывается.
    static final String K_RENT = "rent", K_RENT_H = "rent_h";
    static final String CH_RENT = "iva_rent";
    static final int ID_RENT = 7300;
    static final int[] RENT_HOURS = {1, 2, 3};
    static final String RENT_RPC = "/rest/v1/rpc/iva_my_rentals";
    static final long RENT_EVERY_MS = 5L * 60 * 1000;
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final String RENT_TITLE = "Напоминать об окончании аренды";
    static final String RENT_SUB = "За сколько до конца срока — выберите ниже";
    static volatile long rentAt, rentMissingUntil;

    /** Конец аренды одной группы инструментов части заявки (с одинаковым сроком). */
    static final class RentEnd {
        String key = "", order = "", names = "";
        long end;
    }

    static int rentHours(int h) { return h == 2 || h == 3 ? h : 1; }

    static String hoursText(int h) { return h == 1 ? "1 час" : h + " часа"; }

    /** «Дрель (3 дня), Пила ×2 (1 день)» → {Дрель=3, Пила ×2=1}; у инструмента без срока — срок заявки (если есть). */
    static LinkedHashMap<String, Integer> rentTools(String toolsText, int days) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        java.util.regex.Pattern pt = java.util.regex.Pattern.compile("^(.*?)\\s*\\((\\d{1,3})\\s*(?:дн|день|дня|дней|сут)[^()]*\\)\\s*$");
        for (String part : (toolsText == null ? "" : toolsText).split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            java.util.regex.Matcher m = pt.matcher(t);
            String name = t;
            int d = days;
            if (m.find()) { name = m.group(1).trim(); d = Integer.parseInt(m.group(2)); }
            if (d <= 0 || d > 365 || name.isEmpty()) continue;
            if (!out.containsKey(name)) out.put(name, d);
        }
        return out;
    }

    /** Окончания аренды части заявки: инструменты с одинаковым сроком — одно напоминание. Нет времени выдачи — пусто. */
    static List<RentEnd> rentEnds(String partId, String orderId, String toolsText, int days, long rentedAt) {
        return rentEnds(partId, orderId, toolsText, days, rentedAt, null);
    }

    /** 10.77: то же по правилу проката («Сутки» или «До закрытия пункта», IvaRentPatch.end); правила нет — «Сутки». */
    static List<RentEnd> rentEnds(String partId, String orderId, String toolsText, int days, long rentedAt, IvaRentPatch.Rule rule) {
        List<RentEnd> out = new ArrayList<RentEnd>();
        if (rentedAt <= 0) return out;
        LinkedHashMap<Integer, StringBuilder> byDays = new LinkedHashMap<Integer, StringBuilder>();
        LinkedHashMap<String, Integer> tools = rentTools(toolsText, days);
        if (tools.isEmpty() && days > 0) tools.put("Инструменты по заявке", days);
        for (java.util.Map.Entry<String, Integer> e : tools.entrySet()) {
            StringBuilder b = byDays.get(e.getValue());
            if (b == null) byDays.put(e.getValue(), b = new StringBuilder());
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey());
        }
        for (java.util.Map.Entry<Integer, StringBuilder> e : byDays.entrySet()) {
            RentEnd r = new RentEnd();
            r.end = IvaRentPatch.end(rentedAt, e.getKey(), rule);
            r.key = "r" + (partId == null ? "" : partId.trim()) + "@" + r.end;
            r.order = orderId == null ? "" : orderId.trim();
            r.names = e.getValue().toString();
            out.add(r);
        }
        return out;
    }

    /** Пора напомнить: до конца осталось не больше выбранного числа часов, и срок ещё не вышел. */
    static boolean rentDue(long end, long now, int hours) {
        return now < end && end - now <= rentHours(hours) * 3600000L;
    }

    /** «Аренда заканчивается в 14:30» / «… завтра в 01:00» (по часам телефона). */
    static String rentTitle(long end, long now, java.util.TimeZone tz) {
        java.util.Calendar e = java.util.Calendar.getInstance(tz), n = java.util.Calendar.getInstance(tz);
        e.setTimeInMillis(end);
        n.setTimeInMillis(now);
        boolean today = e.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
            && e.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR);
        return "Аренда заканчивается " + (today ? "" : "завтра ")
            + String.format(Locale.ROOT, "в %02d:%02d", e.get(java.util.Calendar.HOUR_OF_DAY), e.get(java.util.Calendar.MINUTE));
    }

    static String rentText(String names) {
        return names + ". Продлить или договориться о возврате — в заявке: «Написать» или «Позвонить» прокату.";
    }

    /** Значение переключателя по умолчанию: всё включено (решение владельца), кроме расписания и напоминания об аренде. */
    static boolean defOn(String k) { return !K_SCHED_ON.equals(k) && !K_RENT.equals(k); }

    static boolean rentOn(Context c) { return pref(c, K_ALL) && prefs(c).getBoolean(K_RENT, false); }

    static String key(String what, String email) { return what + "_" + (email == null ? "" : email.trim().toLowerCase()); }

    // ───────────────────────── вход ─────────────────────────

    public static void onShowTab(Activity a, String tab) {
        act = new WeakReference<Activity>(a);
        try { stopNative(a); } catch (Throwable ignore) {}
        try { startPoll(); } catch (Throwable ignore) {}
        try { scheduleBg(a.getApplicationContext()); } catch (Throwable ignore) {}   // 10.65: этап 2 — когда приложение закрыто
        try { openFromNotification(a); } catch (Throwable ignore) {}
        try { ensureRow(a); } catch (Throwable ignore) {}
        try { cabinet(a, tab); } catch (Throwable ignore) {}   // 10.78: «Чат поддержки» и «Обновить до версии …»
        try { IvaCensorPatch.onTab(a, tab); } catch (Throwable ignore) {}   // 10.92: «Цензура» у создателя, слова создателя
        try { IvaChiefPatch.onTab(a, tab); } catch (Throwable ignore) {}    // 10.93: «Управление», «Мои отзывы»; без «Ваш доход с админов»
        try { IvaTestPatch.onTab(a, tab); } catch (Throwable ignore) {}     // 10.94: жёлтая «Обновить до версии N» у eclips и eclipsik
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

    /** 10.74: пришёл push — проверить сейчас (идёт проверка — повторить через 2 с, чтобы не пропустить новое). */
    static void wake() {
        if (poll == null) poll = new Handler(Looper.getMainLooper());
        poll.removeCallbacks(TICK);
        if (busy) poll.postDelayed(TICK, 2000); else poll.post(TICK);
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
        // 10.65: фоновой проверке (приложение закрыто) неоткуда узнать, админ ли это, — запоминаем здесь
        if (logged && !email.isEmpty()) try { prefs(a).edit().putBoolean(key("admin", email), admin).apply(); } catch (Throwable ignore) {}
        Object ct = get(a, "curTab"), cw = get(a, "chatWith");
        // «Чат» на экране: null — нет; "" — список чатов; почта — открытая переписка
        final String openWith = "chat".equals(ct) && a.hasWindowFocus() ? (cw instanceof String ? ((String) cw).trim() : "") : null;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (logged && admin && !email.isEmpty()) checkOrders(a, email);
                    if (logged && !email.isEmpty()) checkMessages(a, email, admin, openWith);
                    checkUpdate(a, admin);
                } catch (Throwable ignore) {
                }
                if (logged && !admin && !email.isEmpty()) try { checkRent(a, email, admin); } catch (Throwable ignore) {}   // 10.76
                if (logged && !email.isEmpty()) try { IvaTestPatch.check(a, email, admin); } catch (Throwable ignore) {}   // 10.94
                try { IvaPushPatch.sync(a, email, logged, admin); } catch (Throwable ignore) {   // 10.74: мгновенные (админам)
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    static JSONArray getArr(Context a, String path) throws Exception {
        // открыто — штатный токен из окна; закрыто (10.65) — токен сохранённого входа, тот же штатный Api.call
        String[] r = a instanceof Activity ? IvaClientPatch.apiCall((Activity) a, path, "GET", null, true)
            : nativeCall(IvaClientPatch.SB_URL + path, "GET", null, true, a instanceof BgCtx ? ((BgCtx) a).token : null);
        if (r == null || !r[0].startsWith("2")) throw new Exception(r == null ? "нет ответа" : r[0]);
        String t = r.length > 1 && r[1] != null ? r[1].trim() : "";
        return t.startsWith("[") ? new JSONArray(t) : new JSONArray();
    }

    /** Запрос к базе: открыто — штатный токен окна; закрыто — токен сохранённого входа (как getArr). */
    static String[] api(Context a, String path, String method, String body) throws Exception {
        return a instanceof Activity ? IvaClientPatch.apiCall((Activity) a, path, method, body, true)
            : nativeCall(IvaClientPatch.SB_URL + path, method, body, true, a instanceof BgCtx ? ((BgCtx) a).token : null);
    }

    /** 10.76: напоминание клиенту об окончании аренды (см. RentEnd). Каждое — один раз. */
    static void checkRent(Context a, String email, boolean admin) throws Exception {
        if (admin || email == null || email.trim().isEmpty() || !rentOn(a)) return;
        long now = System.currentTimeMillis();
        if (now < rentMissingUntil) return;
        if (a instanceof Activity && now - rentAt < RENT_EVERY_MS) return;
        rentAt = now;
        String[] r = api(a, RENT_RPC, "POST", "{}");
        if (r == null) return;
        if ("404".equals(r[0])) { rentMissingUntil = now + UPDATE_EVERY_MS; return; }   // SQL ещё не применён — тихо ждём
        if (!r[0].startsWith("2")) return;
        String t = r.length > 1 && r[1] != null ? r[1].trim() : "";
        JSONArray rows = t.startsWith("[") ? new JSONArray(t) : new JSONArray();
        SharedPreferences p = prefs(a);
        int hours = rentHours(p.getInt(K_RENT_H, 1));
        String k = key("seen_r", email);
        Set<String> seen = splitSet(p.getString(k, null));
        if (seen == null) seen = new LinkedHashSet<String>();
        List<RentEnd> due = new ArrayList<RentEnd>();
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            long at = IvaChatPatch.parseIso(o.optString("rented_at", ""));
            for (RentEnd e : rentEnds(o.optString("part_id", ""), o.optString("order_id", ""), o.optString("tools_text", ""), o.optInt("days", 0), at, IvaRentPatch.ruleOf(o))) {
                if (rentDue(e.end, now, hours) && !seen.contains(e.key)) { due.add(e); keys.add(e.key); }
            }
        }
        if (due.isEmpty()) return;
        p.edit().putString(k, joinSet(remember(seen, keys))).apply();
        for (RentEnd e : due)
            showNote(a, CH_RENT, ID_RENT + Math.abs(e.key.hashCode() % 500), rentTitle(e.end, now, java.util.TimeZone.getDefault()), rentText(e.names), "rent", e.order);
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable e) { return s; }
    }

    /** Как штатный опрос: новые заявки на инструменты админа, отметка «Заявки •» в меню. */
    static void checkOrders(final Context a, String email) throws Exception {
        JSONArray rows = getArr(a, "/rest/v1/order_parts?select=id,tools_text&status=eq.new&archived=eq.false&owner_email=eq." + enc(email) + "&order=id.desc&limit=50");
        final int n = rows.length();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < n; i++) ids.add(rows.getJSONObject(i).optString("id", ""));
        if (a instanceof Activity) ((Activity) a).runOnUiThread(new Runnable() {
            @Override public void run() {
                Object t = get(a, "navOrdersTxt");
                if (t instanceof TextView) ((TextView) t).setText(n > 0 ? "Заявки •" : "Заявки");
            }
        });
        if (quiet(a, true)) return;          // 10.73: вне расписания — не запоминаем, покажем в начале разрешённого времени
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
        if (a instanceof Activity) ((Activity) a).runOnUiThread(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(a, "Новая заявка!", Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {}
            }
        });
        showNote(a, CH_ORD, ID_ORD, title, body, "orders", orderTarget(nw));
    }

    /** Непрочитанные входящие сообщения. Не уведомляем только о том, что и так видно: открытая переписка с этим
     *  собеседником или список чатов (оба обновляются сами, 10.57). Письмо от другого при открытом чате — уведомление. */
    static void checkMessages(Context a, String email, boolean admin, String openWith) throws Exception {
        if (quiet(a, admin)) return;         // 10.73: вне расписания — ничего не запоминаем, покажем позже
        JSONArray rows = getArr(a, "/rest/v1/app_messages?select=id,from_email,text,img,audio,created_at&to_email=eq." + enc(email)
            + "&is_read=eq.false&order=created_at.desc&limit=50");
        List<String> ids = new ArrayList<String>(), created = new ArrayList<String>();
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject m = rows.getJSONObject(i);
            if (email.equalsIgnoreCase(m.optString("from_email", ""))) continue;
            ids.add(m.optString("id", ""));
            created.add(m.optString("created_at", ""));
            list.add(m);
        }
        SharedPreferences p = prefs(a);
        String k = key("seen_m", email);
        Set<String> seen = splitSet(p.getString(k, null));
        // первый запуск на телефоне: о непрочитанных за последние сутки — сообщить (а не молча запомнить)
        List<String> nw = seen == null ? recent(ids, created, isoUtc(System.currentTimeMillis() - FIRST_RUN_WINDOW_MS)) : fresh(seen, ids);
        p.edit().putString(k, joinSet(remember(seen, ids))).apply();
        List<String> show = new ArrayList<String>();
        for (String id : nw) if (!hiddenInChat(openWith, list.get(ids.indexOf(id)).optString("from_email", ""))) show.add(id);
        nw = show;
        if (nw.isEmpty() || !on(a, K_MSG, admin)) return;
        JSONObject last = list.get(ids.indexOf(nw.get(0)));
        List<String> from = new ArrayList<String>();
        for (String id : nw) from.add(list.get(ids.indexOf(id)).optString("from_email", ""));
        showNote(a, CH_MSG, ID_MSG, messageTitle(nw.size()),
            messagePreview(last.optString("text", ""), last.optString("img", ""), last.optString("audio", "")), "chat", messageTarget(nw, from));
    }

    static int installedCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Throwable e) { return 0; }
    }

    /** Вышла новая версия: 10.78 — выпущенный релиз («Подтвердить релиз», как у сайта и кнопки в кабинете), иначе app-update.json. */
    static void checkUpdate(Context a, boolean admin) throws Exception {
        if (!on(a, K_UPD, admin) || quiet(a, admin)) return;
        SharedPreferences p = prefs(a);
        long now = System.currentTimeMillis();
        if (!updateDue(p.getLong("upd_checked", 0), now)) return;
        JSONObject j = released();
        p.edit().putLong("upd_checked", now).apply();
        if (j == null) return;
        int remote = j.optInt("versionCode", 0);
        if (!updateNews(remote, installedCode(a), p.getInt("upd_notified", 0))) return;
        p.edit().putInt("upd_notified", remote).apply();
        showNote(a, CH_UPD, ID_UPD, "Вышло обновление приложения", updateText(j.optString("versionName", "")), "update",
            remote + "|" + j.optString("versionName", ""));
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
        return CH_MSG.equals(ch) ? "Новые сообщения" : CH_ORD.equals(ch) ? "Новые заявки" : CH_UPD.equals(ch) ? "Обновления приложения"
            : CH_RENT.equals(ch) ? "Окончание аренды" : IvaTestPatch.CH_TEST.equals(ch) ? "Пробная версия" : "Ива";
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String[] ids = {CH_MSG, CH_ORD, CH_UPD, CH_RENT, IvaTestPatch.CH_TEST};
        // 10.100: свой звук — категории с новыми id (+"_s1") и звуком владельца; старые удаляются (звук у созданной
        // категории Android менять не даёт). Выключенная человеком категория остаётся выключенной.
        boolean snd = IvaSoundPatch.ready(c);
        for (String id : ids) {
            String sid = IvaSoundPatch.channelId(id, snd);
            if (nm.getNotificationChannel(sid) != null) continue;
            int imp = CH_UPD.equals(id) ? NotificationManager.IMPORTANCE_DEFAULT : NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel old = snd ? nm.getNotificationChannel(id) : null;
            if (old != null && old.getImportance() == NotificationManager.IMPORTANCE_NONE) imp = NotificationManager.IMPORTANCE_NONE;
            NotificationChannel ch = new NotificationChannel(sid, channelName(id), imp);
            ch.setDescription(CH_MSG.equals(id) ? "Когда вам пишут в чат" : CH_ORD.equals(id) ? "Заявки на ваши инструменты"
                : CH_RENT.equals(id) ? "Скоро закончится срок аренды" : IvaTestPatch.CH_TEST.equals(id) ? IvaTestPatch.NOTE_SUB
                : "Когда вышла новая версия приложения");
            if (snd) IvaSoundPatch.apply(ch);
            nm.createNotificationChannel(ch);
            if (old != null) try { nm.deleteNotificationChannel(id); } catch (Throwable ignore) {}
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
    static void showNote(Context a, String ch, int id, String title, String text, String open) {
        showNote(a, ch, id, title, text, open, "");
    }

    static void showNote(Context a, String ch, int id, String title, String text, String open, String target) {
        try {
            Context c = a.getApplicationContext();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannels(c);
            String sysCh = IvaSoundPatch.channelId(ch, IvaSoundPatch.ready(c));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, sysCh) : new Notification.Builder(c);
            Intent it = new Intent(c, mainClass(a));
            it.putExtra(EXTRA_OPEN, open);
            it.putExtra(EXTRA_TARGET, target == null ? "" : target);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(c, id, it, fl);
            int icon = c.getResources().getIdentifier("ic_chat", "drawable", c.getPackageName());
            setIcons(c, b, icon);
            b
             .setContentTitle(title)
             .setContentText(text)
             .setContentIntent(pi)
             .setAutoCancel(true)
             .setWhen(System.currentTimeMillis())
             .setShowWhen(true)
             .setColor(ctxColor(a, "orange", 0xffff8f1f));
            // Android 11+: «разговор» — в кружке слева цветной логотип, силуэт — маленьким значком на нём.
            // Раньше — обычный вид: силуэт в кружке, без картинки справа.
            if (!conversation(a, b, ch, title, text, open, target)) b.setStyle(new Notification.BigTextStyle().bigText(text));
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(CH_UPD.equals(ch) ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_HIGH);
                IvaSoundPatch.legacy(c, b);
            }
            if (CH_MSG.equals(ch)) {
                b.setVisibility(Notification.VISIBILITY_PRIVATE);
                Notification.Builder pub = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, sysCh) : new Notification.Builder(c);
                setIcons(c, pub, icon);
                pub.setContentTitle(title).setContentText("Откройте приложение, чтобы прочитать");
                b.setPublicVersion(pub.build());
            }
            nm.notify(id, b.build());
        } catch (Throwable ignore) {}
    }

    static Bitmap statusBmp, logoBmp;

    static Bitmap statusIcon() {
        if (statusBmp == null) {
            try {
                byte[] png = android.util.Base64.decode(STATUS_ICON_PNG, android.util.Base64.DEFAULT);
                statusBmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            } catch (Throwable ignore) {}
        }
        return statusBmp;
    }

    /** Цветной логотип (ic_logo), уже обрезанный кругом, — для кружка слева у «разговора». */
    static Bitmap logo(Context c) {
        if (logoBmp == null) {
            try {
                int id = c.getResources().getIdentifier("ic_logo", "drawable", c.getPackageName());
                Bitmap src = id == 0 ? null : BitmapFactory.decodeResource(c.getResources(), id);
                if (src != null) {
                    Bitmap sq = Bitmap.createScaledBitmap(src, AVATAR_PX, AVATAR_PX, true);
                    Bitmap out = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(out);
                    android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    cv.drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, pt);
                    pt.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
                    cv.drawBitmap(sq, 0, 0, pt);
                    logoBmp = out;
                }
            } catch (Throwable ignore) {}
        }
        return logoBmp;
    }

    /** Строка состояния — силуэт логотипа (Android 6+; раньше — штатный значок). Большой картинки справа нет (решение владельца 14.10). */
    static void setIcons(Context c, Notification.Builder b, int fallback) {
        Bitmap si = Build.VERSION.SDK_INT >= 23 ? statusIcon() : null;
        if (si != null) b.setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(si));
        else b.setSmallIcon(fallback != 0 ? fallback : c.getApplicationInfo().icon);
    }

    static String shortcutLabel(String ch) {
        return CH_ORD.equals(ch) ? "Заявки" : CH_UPD.equals(ch) ? "Обновления" : CH_RENT.equals(ch) ? "Аренда" : "Сообщения";
    }

    /**
     * Уведомление-«разговор» (Android 11+): MessagingStyle + долгоживущий ярлык с логотипом.
     * Только так система (и Samsung One UI) рисует в кружке слева цветную картинку, а не одноцветный значок.
     * Не вышло — false, показываем обычный вид.
     */
    static boolean conversation(final Context a, Notification.Builder b, String ch, String title, String text, String open, String target) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            final Context c = a.getApplicationContext();
            Bitmap lg = logo(c);
            final android.content.pm.ShortcutManager sm = (android.content.pm.ShortcutManager) c.getSystemService(Context.SHORTCUT_SERVICE);
            if (lg == null || sm == null) return false;
            android.graphics.drawable.Icon av = android.graphics.drawable.Icon.createWithBitmap(lg);
            final String sid = SHORTCUT_PREFIX + ch;
            android.app.Person sender = new android.app.Person.Builder()
                .setName(title).setIcon(av).setKey(sid).setImportant(true).build();
            android.app.Person self = new android.app.Person.Builder().setName("Вы").build();
            Intent si = new Intent(Intent.ACTION_VIEW);
            si.setClass(c, mainClass(a));
            si.putExtra(EXTRA_OPEN, open);
            si.putExtra(EXTRA_TARGET, target == null ? "" : target);
            Set<String> cats = new java.util.HashSet<String>();
            cats.add("android.shortcut.conversation");
            android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(c, sid)
                .setShortLabel(shortcutLabel(ch))
                .setLongLived(true)
                .setPerson(sender)
                .setIcon(av)
                .setIntent(si)
                .setCategories(cats)
                .build();
            sm.pushDynamicShortcut(info);
            Notification.MessagingStyle ms = new Notification.MessagingStyle(self);
            ms.setConversationTitle(null);
            ms.setGroupConversation(false);
            ms.addMessage(new Notification.MessagingStyle.Message(text, System.currentTimeMillis(), sender));
            b.setStyle(ms);
            b.setShortcutId(sid);
            if (CH_MSG.equals(ch)) b.setCategory(Notification.CATEGORY_MESSAGE);
            // Система сама держит копию ярлыка, пока висит уведомление; из меню значка приложения его убираем.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        List<String> ids = new ArrayList<String>();
                        ids.add(sid);
                        sm.removeDynamicShortcuts(ids);
                    } catch (Throwable ignore) {}
                }
            }, SHORTCUT_DROP_MS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ───────────────────────── 10.59: нажатие на уведомление — сразу туда, где нужно ─────────────────────────

    /** Сообщения от одного собеседника — первое непрочитанное (список идёт от новых к старым); от нескольких — "" (список чатов). */
    static String messageTarget(List<String> nwIds, List<String> from) {
        if (nwIds == null || from == null || nwIds.isEmpty() || nwIds.size() != from.size()) return "";
        String one = from.get(0) == null ? "" : from.get(0).trim().toLowerCase();
        if (one.isEmpty()) return "";
        for (String f : from) if (!one.equals(f == null ? "" : f.trim().toLowerCase())) return "";
        return nwIds.get(nwIds.size() - 1);
    }

    /** Одна новая заявка — её id; несколько — "" (список клиентов). */
    static String orderTarget(List<String> nw) {
        return nw != null && nw.size() == 1 && nw.get(0) != null ? nw.get(0) : "";
    }

    /** Место карточки заявки среди заявок того же клиента (штатный экран клиента рисует их в этом порядке). -1 — нет. */
    static int indexInClient(List<String> partIds, List<String> keys, String partId) {
        int at = partIds.indexOf(partId);
        if (at < 0 || keys.size() != partIds.size()) return -1;
        String k = keys.get(at);
        int n = 0;
        for (int i = 0; i < at; i++) if (k.equals(keys.get(i))) n++;
        return n;
    }

    /** «код|версия» из уведомления об обновлении → код (0 — неизвестен). */
    static int targetCode(String t) {
        try { return Integer.parseInt(t.substring(0, t.indexOf('|')).trim()); } catch (Throwable e) { return 0; }
    }

    static String targetName(String t) {
        int i = t == null ? -1 : t.indexOf('|');
        return i < 0 ? "" : t.substring(i + 1).trim();
    }

    /** Холодный запуск по нажатию на уведомление (приложение было закрыто): первый showTab. */
    static void openFromNotification(final Activity a) {
        Intent it = a.getIntent();
        if (it == null || it.getStringExtra(EXTRA_OPEN) == null) return;
        route(a, it);
    }

    /** Приложение уже открыто или свёрнуто: новый метод MainActivity.onNewIntent → IvaPatch.onNewIntent → сюда. */
    static void onNewIntent(Activity a, Intent it) {
        act = new WeakReference<Activity>(a);
        if (it == null || it.getStringExtra(EXTRA_OPEN) == null) return;
        route(a, it);
    }

    static void route(final Activity a, Intent it) {
        final String open = it.getStringExtra(EXTRA_OPEN);
        String t = it.getStringExtra(EXTRA_TARGET);
        final String target = t == null ? "" : t.trim();
        it.removeExtra(EXTRA_OPEN);          // второй раз (поворот, возврат) не переходим
        it.removeExtra(EXTRA_TARGET);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    if ("chat".equals(open)) IvaChatPatch.openFromNote(a, target);
                    else if ("orders".equals(open)) openOrder(a, target);
                    else if ("update".equals(open)) openUpdate(a, target);
                    else if ("rent".equals(open)) IvaOrderPatch.openById(a, target);     // 10.76: эта заявка
                    else if ("test".equals(open)) IvaTestPatch.openFromNote(a);          // 10.94: жёлтая кнопка в «Профиле»
                } catch (Throwable ignore) {}
            }
        });
    }

    static void tab(Activity a, String t) {
        try {
            Method m = a.getClass().getDeclaredMethod("showTab", String.class);
            m.setAccessible(true);
            m.invoke(a, t);
        } catch (Throwable ignore) {}
    }

    /** Заявки (админ): дождаться штатной загрузки, открыть клиента этой заявки (как нажатие на него) и показать карточку. */
    static void openOrder(final Activity a, final String partId) {
        tab(a, "orders");
        if (partId.isEmpty()) return;
        final long until = System.currentTimeMillis() + ROUTE_WAIT_MS;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (a.isFinishing() || !"orders".equals(String.valueOf(get(a, "curTab")))) return;   // ушли — не мешаем
                    Object pb = get(a, "progOrders");
                    boolean busy = pb instanceof View && ((View) pb).getVisibility() == View.VISIBLE;
                    Object parts = get(a, "admParts");
                    List<String> ids = new ArrayList<String>(), keys = new ArrayList<String>();
                    JSONObject part = null;
                    if (!busy && parts instanceof List) {
                        for (Object o : (List<?>) parts) {
                            if (!(o instanceof JSONObject)) continue;
                            JSONObject j = (JSONObject) o;
                            ids.add(j.optString("id", ""));
                            keys.add(clientKey(a, j));
                            if (partId.equals(j.optString("id", ""))) part = j;
                        }
                    }
                    if (part == null) {
                        if (System.currentTimeMillis() < until) h.postDelayed(this, ROUTE_STEP_MS);
                        return;
                    }
                    String key = clientKey(a, part);
                    IvaClientPatch.set(a, "admClientKey", key);
                    IvaClientPatch.set(a, "admDrill", Boolean.TRUE);
                    call(a, "renderAdmOrders");
                    int idx = indexInClient(ids, keys, partId);
                    Object ol = get(a, "ordersList");
                    if (idx >= 0 && ol instanceof ViewGroup) {
                        ViewGroup list = (ViewGroup) ol;
                        int count = 0;
                        for (String k : keys) if (k.equals(key)) count++;
                        // штатно: «‹ Все клиенты», затем по карточке на заявку клиента
                        if (list.getChildCount() == count + 1) reveal(list.getChildAt(idx + 1));
                    }
                    try { IvaOrderPatch.ordOpenRow(a, IvaOrderPatch.admRow(part), false); } catch (Throwable ignore) {}   // 10.68: сразу подробности
                } catch (Throwable ignore) {}
            }
        }, ROUTE_STEP_MS);
    }

    static String clientKey(Activity a, JSONObject part) {
        try {
            Method m = a.getClass().getDeclaredMethod("partClientKey", JSONObject.class);
            m.setAccessible(true);
            return String.valueOf(m.invoke(a, part));
        } catch (Throwable e) { return ""; }
    }

    /** Обновление: «Кабинет» → штатная кнопка «Обновить до версии …» (SelfUpdate); нет — проверка релиза ещё раз (10.78). */
    static void openUpdate(final Activity a, final String target) {
        tab(a, "profile");
        final Object anchor = get(a, "profVer");
        if (!(anchor instanceof View)) return;
        final ViewGroup box = updateBox((View) anchor);
        if (box == null) return;
        View old = box.findViewWithTag(UPDATE_BTN_TAG);
        String ver = targetName(target);
        if (old instanceof TextView && !ver.isEmpty() && !String.valueOf(((TextView) old).getText()).contains(ver)) {
            box.removeView(old);             // кнопка со старой версией: штатная проверка вторую не добавит
            old = null;
        }
        if (old == null) releaseCheck(a, true);   // 10.78: та же проверка выпущенного релиза, что у кнопки в кабинете
        final long until = System.currentTimeMillis() + ROUTE_WAIT_MS;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                if (a.isFinishing() || !"profile".equals(String.valueOf(get(a, "curTab")))) return;
                View b = box.findViewWithTag(UPDATE_BTN_TAG);
                if (b != null) { reveal(b); return; }
                if (System.currentTimeMillis() < until) { h.postDelayed(this, ROUTE_STEP_MS); return; }
                int code = targetCode(target);
                try {
                    Toast.makeText(a, code > 0 && installedCode(a) >= code ? "Эта версия уже установлена"
                        : "Не удалось проверить обновление — проверьте интернет", Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {}
            }
        });
    }

    /** Куда штатный SelfUpdate кладёт кнопку: родитель родителя строки версии. */
    static ViewGroup updateBox(View anchor) {
        Object p = anchor.getParent();
        Object g = p instanceof View ? ((View) p).getParent() : null;
        return g instanceof ViewGroup ? (ViewGroup) g : null;
    }

    /** Прокрутить ближайший ScrollView к виду (с отступом 12dp сверху) и мягко подсветить его. */
    static void reveal(final View v) {
        if (v == null) return;
        v.post(new Runnable() {
            @Override public void run() {
                try {
                    int y = 0;
                    View c = v;
                    Object p = v.getParent();
                    while (p instanceof View && !(p instanceof ScrollView)) { y += c.getTop(); c = (View) p; p = c.getParent(); }
                    if (p instanceof ScrollView) {
                        y += c.getTop();
                        int pad = Math.round(12 * v.getResources().getDisplayMetrics().density);
                        ((ScrollView) p).smoothScrollTo(0, Math.max(0, revealY(y, pad)));
                    }
                } catch (Throwable ignore) {}
                flash(v);
            }
        });
    }

    static int revealY(int top, int pad) { return top - pad; }

    static final int FLASH_COLOR = 0x55FF8F1F;   // фирменный оранжевый, полупрозрачный
    static final long FLASH_MS = 1600;

    /** Подсветка поверх вида (Android 6+: foreground), гаснет за FLASH_MS. Фон и содержимое вида не меняются. */
    static void flash(final View v) {
        if (Build.VERSION.SDK_INT < 23 || v == null) return;
        try {
            final android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(FLASH_COLOR);
            g.setCornerRadius(12 * v.getResources().getDisplayMetrics().density);
            final Drawable before = v.getForeground();
            v.setForeground(g);
            android.animation.ValueAnimator an = android.animation.ValueAnimator.ofInt(255, 0);
            an.setStartDelay(500);
            an.setDuration(FLASH_MS);
            an.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator x) { g.setAlpha((Integer) x.getAnimatedValue()); }
            });
            an.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator x) { v.setForeground(before); }
            });
            an.start();
        } catch (Throwable ignore) {}
    }

    // ───────────────────────── 10.78: кабинет — «Чат поддержки» и «Обновить до версии …» ─────────────────────────
    // Просьба владельца 14.10.2026. Порядок в кабинете снизу: «О приложении» (строка profVer) ← над ней оранжевая
    // «Обновить до версии N» ← ещё выше (только у админов) «Чат поддержки» — переписка с создателем.
    // Кнопка — штатная (SelfUpdate.showButtonOnUi: оранжевая, скачивание и установка — штатные), но «какая версия
    // выпущена» берём там же, где сайт: последняя строка app_releases («Подтвердить релиз» создателя); строк нет —
    // app-update.json (старая 10.14, как у сайта). Выпущенная не новее установленной — кнопки нет совсем.
    static final String RELEASE_PATH = "/rest/v1/app_releases?select=version_code,version_name,apk_url&order=version_code.desc&limit=1";
    static final String APK_BASE = "https://eclipsru.github.io/Arenda-Nvk/";
    static final String APK_RAW = "https://github.com/eclipsru/Arenda-Nvk/raw/";
    static final long RELEASE_EVERY_MS = 60000L;      // при открытии кабинета — не чаще раза в минуту
    static final String SUPPORT_TAG = "iva_support_row", SUPPORT_TITLE = "Чат поддержки";
    static volatile int relCode, relShown;            // выпущенный код (0 — неизвестен); какой код мы показали кнопкой
    static volatile String relName = "", relApk = "";
    static volatile long relAt;
    static volatile boolean relBusy;

    /** Ссылка на APK из app_releases: только файлы этого проекта (как проверка в базе); иначе "". */
    static String releaseApk(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.matches("ProkatInstrumenta-[0-9]+\\.[0-9]+\\.apk")) return APK_BASE + u;
        if (u.startsWith(APK_RAW) && u.matches("https://github\\.com/eclipsru/Arenda-Nvk/raw/[A-Za-z0-9._/-]+/ProkatInstrumenta-[0-9]+\\.[0-9]+\\.apk")
            && !u.contains("..")) return u;
        return "";
    }

    /** Показывать ли «Обновить до версии …»: есть ссылка и выпущенная версия новее установленной. */
    static boolean updateWanted(int released, int installed, String apk) {
        return released > 0 && installed > 0 && released > installed && apk != null && !apk.isEmpty();
    }

    /** «Чат поддержки» — вошедшим админам, кроме самого создателя (писать самому себе некуда). */
    static boolean supportFor(boolean logged, boolean admin, String email) {
        return logged && admin && !IvaChatPatch.OWNER.equalsIgnoreCase(email == null ? "" : email.trim());
    }

    /** Выпущенная версия {versionCode, versionName, apk}: последняя строка app_releases, строк нет — app-update.json; сбой — null. */
    static JSONObject released() {
        try {
            String[] r = nativeCall(IvaClientPatch.SB_URL + RELEASE_PATH, "GET", null, false, null);
            String t = r != null && r.length > 1 && r[1] != null ? r[1].trim() : "";
            if (r != null && r[0].startsWith("2") && t.startsWith("[")) {
                JSONArray arr = new JSONArray(t);
                if (arr.length() > 0) {
                    JSONObject o = arr.getJSONObject(0);
                    String apk = releaseApk(o.optString("apk_url", ""));
                    int code = o.optInt("version_code", 0);
                    if (code <= 0 || apk.isEmpty()) return null;          // строка есть, но странная — ничего не показываем
                    String name = o.optString("version_name", "");
                    JSONObject j = new JSONObject();
                    j.put("versionCode", code);
                    j.put("versionName", "null".equals(name) ? "" : name);
                    j.put("apk", apk);
                    return j;
                }
            } else if (r == null || !(r[0].startsWith("4"))) {
                return null;                                              // нет сети / сбой базы — не знаем
            }
        } catch (Throwable e) {
            return null;
        }
        try {                                                             // релизов ещё нет (или нет таблицы) — как у сайта
            String body = httpGet(UPDATE_URL);
            if (body == null) return null;
            JSONObject j = new JSONObject(body.trim());
            j.put("apk", j.optString("apk", "").startsWith(APK_BASE) ? j.optString("apk", "") : "");
            return j;
        } catch (Throwable e) {
            return null;
        }
    }

    static void cabinet(Activity a, String tab) {
        ensureSupport(a);
        if ("profile".equals(tab)) releaseCheck(a, false);
    }

    /** Сразу — по последнему известному релизу; затем (не чаще раза в минуту, force — сейчас) спрашиваем базу в фоне. */
    static void releaseCheck(final Activity a, boolean force) {
        applyRelease(a);
        long now = System.currentTimeMillis();
        if (relBusy || (!force && relAt > 0 && now - relAt < RELEASE_EVERY_MS)) return;
        relBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                cleanupUpdates(a.getApplicationContext());   // 10.80: файлы уже установленных обновлений
                try {
                    JSONObject j = released();
                    if (j != null) {
                        relName = j.optString("versionName", "");
                        relApk = j.optString("apk", "");
                        relCode = j.optInt("versionCode", 0);
                        relAt = System.currentTimeMillis();
                    }
                } catch (Throwable ignore) {
                } finally {
                    relBusy = false;
                }
                try {
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { applyRelease(a); } catch (Throwable ignore) {}
                            try { IvaTestPatch.apply(a); } catch (Throwable ignore) {}   // 10.94: жёлтая ждёт номер выпущенной
                        }
                    });
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    /** 10.94: прогресс сейчас на жёлтой кнопке пробной версии. */
    static boolean testDriving() {
        View v = updBtn.get();
        return v != null && IvaTestPatch.TEST_TAG.equals(v.getTag()) && updState != UPD_IDLE;
    }

    /** Кнопка над «О приложении»: нужна — штатная SelfUpdate.showButtonOnUi; не нужна или другая версия — убираем. */
    static void applyRelease(Activity a) {
        if (a.isFinishing()) return;
        if (testDriving()) return;                                        // 10.94: качается жёлтая — оранжевую не трогаем
        Object anchor = get(a, "profVer");
        if (!(anchor instanceof View)) return;
        ViewGroup box = updateBox((View) anchor);
        if (box == null) return;
        int code = relCode;
        String apk = relApk, name = relName;
        if (code <= 0) return;                                            // ещё не знаем — ничего не трогаем
        boolean want = updateWanted(code, installedCode(a), apk);
        View old = box.findViewWithTag(UPDATE_BTN_TAG);
        // 10.80: кнопка — своя (штатную SelfUpdate с серыми окнами — убираем, если она успела появиться)
        if (old != null && (!want || relShown != code || old != updBtn.get())) { box.removeView(old); old = null; }
        if (!want || old != null) return;
        Object ver = ((View) anchor).getParent();
        int at = ver instanceof View ? box.indexOfChild((View) ver) : -1;
        if (at < 0) return;
        String shown = name.isEmpty() ? String.valueOf(code) : name;
        View b = updateButton(a, code, shown, apk);
        if (box instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 10);                                     // как у штатной кнопки
            box.addView(b, at, lp);
        } else {
            box.addView(b, at);
        }
        updBtn = new WeakReference<View>(b);
        relShown = code;
        if (updCode != code && (updState == UPD_IDLE || updState == UPD_READY)) { updState = UPD_IDLE; updPct = -1; }
        if (updCode == 0 || updState == UPD_IDLE) { updCode = code; updName = shown; }
        updRender();
    }

    // ───────────────────────── 10.80: обновление без окон — нажали, скачали, сразу установка ─────────────────────────
    // Просьба владельца 15.10.2026: серое окно «Всё готово!» не нужно, «Показать окно установки» ничего не делало.
    // Причина: в манифесте не было REQUEST_INSTALL_PACKAGES — системный установщик молча отменял установку (добавляет
    // android/patches/axml_install.py). Теперь кнопка своя (вид как у штатной: оранжевая, 15sp, белый жирный текст):
    // нажали → на кнопке «Скачиваем версию N · 45%» с заливкой слева направо → проверка файла (наш пакет, версия новее
    // установленной) → сразу системная установка (штатный SelfUpdate.commitInstall; ответ системы — штатный
    // StatusReceiver, после установки приложение открывается само — штатный ReplacedReceiver). Окна закрыли — на
    // кнопке «Установить версию N». Файл — во внутренней папке приложения (cache/iva-update-N.apk), в «Загрузках» и
    // в файловом менеджере его нет; после установки старые файлы удаляются сами.
    static final String UPD_FILE = "iva-update-", UPD_EXT = ".apk", UPD_PART = ".part";
    static final long UPD_MIN_BYTES = 200_000L, UPD_MAX_BYTES = 100L * 1024 * 1024;
    static final long UPD_RETRY_MS = 6000L;                // окно установки закрыли — через 6 с кнопка «Установить версию N»
    static final int UPD_IDLE = 0, UPD_DL = 1, UPD_CHECK = 2, UPD_INSTALL = 3, UPD_READY = 4;
    static final int UPD_ORANGE = 0xFFFF8F1F, UPD_TRACK = 0x59FF8F1F;   // фирменный оранжевый; дорожка — он же, прозрачнее
    static volatile int updState = UPD_IDLE, updPct = -1, updCode;
    static volatile String updName = "";
    static volatile WeakReference<View> updBtn = new WeakReference<View>(null);

    /** Текст на кнопке обновления по шагу. */
    static String updText(int state, String name, int pct) {
        String n = name == null || name.isEmpty() ? "" : " " + name;
        switch (state) {
            case UPD_DL: return "Скачиваем версию" + n + (pct >= 0 ? " · " + pct + "%" : "…");
            case UPD_CHECK: return "Проверяем файл…";
            case UPD_INSTALL: return "Открываем установку…";
            case UPD_READY: return "Установить версию" + n;
            default: return "Обновить до версии" + n;
        }
    }

    /** Проценты загрузки: длина неизвестна — -1; не больше 100. */
    static int updPercent(long done, long total) {
        if (total <= 0 || done < 0) return -1;
        return (int) Math.min(100L, done * 100L / total);
    }

    /** Заливка кнопки (0…10000): во время загрузки — проценты, иначе полная. */
    // 10.96 (кнопки «рамкой»): полоса видна только во время загрузки, в остальное время — одна рамка
    static int updLevel(int state, int pct) { return state == UPD_DL ? Math.max(0, Math.min(100, pct)) * 100 : 0; }

    /** Имя файла обновления в кэше. */
    static String updFileName(int code) { return UPD_FILE + code + UPD_EXT; }

    /** Старый файл обновления (уже установлен или старее): iva-update-N.apk[.part] с N ≤ установленного, и iva-update.apk. */
    static boolean staleUpdate(String file, int installed) {
        if (file == null) return false;
        if ("iva-update.apk".equals(file)) return true;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("iva-update-([0-9]{1,9})\\.apk(\\.part)?").matcher(file);
        return m.matches() && installed > 0 && Integer.parseInt(m.group(1)) <= installed;
    }

    /** Скачанный файл годится: наш пакет и версия новее установленной (подпись сверит сама система). */
    static boolean apkFits(String pkg, int ver, String wantPkg, int installed) {
        return pkg != null && wantPkg != null && pkg.equals(wantPkg) && installed > 0 && ver > installed;
    }

    static View updateButton(final Activity a, final int code, final String name, final String apk) {
        Button b = new Button(a);
        b.setTag(UPDATE_BTN_TAG);
        b.setTextColor(UPD_ORANGE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setText(updText(UPD_IDLE, name, -1));
        // 10.96: «рамкой» — тёмная, оранжевая рамка и текст; во время загрузки — полупрозрачная полоса по процентам
        b.setBackground(IvaPatch.accentProgressBg(a, UPD_ORANGE, 12));
        b.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { updateTap(a, code, name, apk); } catch (Throwable ignore) {} }
        });
        return b;
    }

    /** Кнопка по текущему шагу: текст, заливка (во время загрузки — проценты), нажимается только «Обновить»/«Установить». */
    static void updRender() {
        View v = updBtn.get();
        if (!(v instanceof TextView)) return;
        int st = updState;
        ((TextView) v).setText(updText(st, updName, updPct));
        Drawable bg = v.getBackground();
        if (bg != null) bg.setLevel(updLevel(st, updPct));
        v.setEnabled(st == UPD_IDLE || st == UPD_READY);
    }

    static void updPost() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { try { updRender(); } catch (Throwable ignore) {} }
        });
    }

    static void updToast(final Context c, final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { try { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); } catch (Throwable ignore) {} }
        });
    }

    /** Нажали «Обновить до версии N» / «Установить версию N»: скачать (если ещё нет годного файла) и сразу ставить. */
    static void updateTap(Activity a, final int code, final String name, final String apk) {
        if (updState != UPD_IDLE && updState != UPD_READY) return;   // уже качаем / ставим
        final Context app = a.getApplicationContext();
        final File f = new File(app.getCacheDir(), updFileName(code));
        updCode = code;
        updName = name;
        updState = UPD_DL;
        updPct = 0;
        updRender();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    if (!apkReady(app, f)) {
                        err = download(apk, f);
                        if (err == null) {
                            updState = UPD_CHECK;
                            updPost();
                            if (!apkReady(app, f)) { f.delete(); err = "файл не подходит"; }
                        }
                    }
                } catch (Throwable e) {
                    err = shortErr(e);
                }
                if (err != null) {
                    updState = UPD_IDLE;
                    updPct = -1;
                    updPost();
                    updToast(app, "Не удалось скачать обновление (" + err + "). Проверьте интернет и нажмите ещё раз");
                    return;
                }
                install(app, f);
            }
        }).start();
    }

    /** Годный ли скачанный файл: размер в пределах, это APK нашего приложения и он новее установленного. */
    static boolean apkReady(Context c, File f) {
        try {
            if (!f.isFile() || f.length() < UPD_MIN_BYTES || f.length() > UPD_MAX_BYTES) return false;
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            return pi != null && apkFits(pi.packageName, pi.versionCode, c.getPackageName(), installedCode(c));
        } catch (Throwable e) {
            return false;
        }
    }

    /** Скачать APK во временный файл и переименовать; прогресс — на кнопке. Ошибка — короткий текст, успех — null. */
    static String download(String url, File f) {
        if (releaseApk(url).isEmpty() && !url.startsWith(APK_BASE)) return "чужая ссылка";
        File part = new File(f.getPath() + UPD_PART);
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code != 200) return "сервер ответил " + code;
            long total = c.getContentLength();
            if (total > UPD_MAX_BYTES) return "слишком большой файл";
            in = c.getInputStream();
            out = new FileOutputStream(part);
            byte[] buf = new byte[32768];
            long done = 0;
            int n, shown = -2;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
                if (done > UPD_MAX_BYTES) return "слишком большой файл";
                int p = updPercent(done, total);
                if (p != shown) { shown = p; updPct = p; updPost(); }
            }
            out.getFD().sync();
            out.close();
            out = null;
            if (total > 0 && done != total) return "файл скачался не полностью";
            if (f.exists() && !f.delete()) return "не удалось заменить старый файл";
            if (!part.renameTo(f)) return "не удалось сохранить файл";
            return null;
        } catch (Throwable e) {
            return shortErr(e);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignore) {}
            if (part.exists()) part.delete();
        }
    }

    /** Сразу системная установка — штатным SelfUpdate.commitInstall (окно подтверждения откроет штатный StatusReceiver). */
    static void install(Context app, File f) {
        updState = UPD_INSTALL;
        updPost();
        try {
            if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls())
                updToast(app, "Android спросит разрешение на установку из «Ивы» — включите переключатель и нажмите «Назад»");
        } catch (Throwable ignore) {}
        try {
            Class<?> su = Class.forName(app.getPackageName() + ".SelfUpdate");
            Method m = su.getDeclaredMethod("commitInstall", Context.class, File.class);
            m.setAccessible(true);
            m.invoke(null, app, f);
        } catch (Throwable e) {
            Throwable c = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            updState = UPD_READY;
            updPost();
            updToast(app, "Не удалось начать установку (" + shortErr(c) + "). Нажмите «Установить» ещё раз");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (updState == UPD_INSTALL) { updState = UPD_READY; try { updRender(); } catch (Throwable ignore) {} }
            }
        }, UPD_RETRY_MS);
    }

    static String shortErr(Throwable e) {
        String m = e == null ? null : e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e == null ? "" : e.getClass().getSimpleName();
        m = m.trim();
        return m.isEmpty() ? "неизвестная ошибка" : m.length() > 80 ? m.substring(0, 80) : m;
    }

    /** Удалить из кэша файлы уже установленных обновлений. */
    static void cleanupUpdates(Context c) {
        try {
            File[] fs = c.getCacheDir().listFiles();
            if (fs == null) return;
            int installed = installedCode(c);
            for (File x : fs) if (staleUpdate(x.getName(), installed)) x.delete();
        } catch (Throwable ignore) {}
    }

    /** «Чат поддержки» — как строки кабинета (bg_card, 16dp, значок 20dp оранжевый, 15sp, ›), над «Обновить…» / «О приложении». */
    static void ensureSupport(final Activity a) {
        Object anchor = get(a, "profVer");
        if (!(anchor instanceof View) || !(((View) anchor).getParent() instanceof View)) return;
        View ver = (View) ((View) anchor).getParent();
        ViewGroup box = updateBox((View) anchor);
        if (!(box instanceof LinearLayout)) return;
        boolean want = supportFor(loggedIn(a), isAdmin(a), String.valueOf(get(a, "userEmail")));
        View row = box.findViewWithTag(SUPPORT_TAG);
        if (row == null) {
            if (!want) return;
            row = supportRow(a);
            View upd = box.findViewWithTag(UPDATE_BTN_TAG);
            int at = box.indexOfChild(upd != null && upd.getParent() == box ? upd : ver);
            if (at < 0) return;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(a, 12);
            box.addView(row, at, lp);
        }
        row.setVisibility(want ? View.VISIBLE : View.GONE);
    }

    static View supportRow(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(SUPPORT_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Drawable bg = drawable(a, "bg_card");
        if (bg != null) row.setBackground(bg);
        row.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
        row.setClickable(true);
        ImageView ic = new ImageView(a);
        Drawable chat = drawable(a, "ic_chat");
        if (chat != null) ic.setImageDrawable(chat);
        ic.setColorFilter(color(a, "orange", 0xffff8f1f));
        row.addView(ic, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        TextView t = new TextView(a);
        t.setText(SUPPORT_TITLE);
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
            @Override public void onClick(View v) {
                boolean ok = false;
                try { ok = IvaChatPatch.openWith(a, IvaChatPatch.OWNER); } catch (Throwable ignore) {}
                if (!ok) try { Toast.makeText(a, "Не удалось открыть чат — войдите в аккаунт", Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
            }
        });
        return row;
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
            final LinearLayout page = new LinearLayout(a);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 24));
            scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

            // 10.75: «Уведомления» — раскрывающаяся кнопка (как строки кабинета); всё про уведомления — внутри, по умолчанию свёрнуто
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            Drawable hbg = drawable(a, "bg_card");
            if (hbg != null) head.setBackground(hbg);
            head.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
            head.setClickable(true);
            ImageView bell = new ImageView(a);
            bell.setImageDrawable(bellIcon(cOrange));
            head.addView(bell, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
            LinearLayout hcol = new LinearLayout(a);
            hcol.setOrientation(LinearLayout.VERTICAL);
            TextView htitle = new TextView(a);
            htitle.setText(NOTIF_TITLE);
            htitle.setTextSize(15f);
            htitle.setTextColor(cText);
            hcol.addView(htitle);
            final TextView hsub = new TextView(a);
            hsub.setTextSize(13f);
            hsub.setTextColor(cGray);
            hcol.addView(hsub);
            LinearLayout.LayoutParams hcl = new LinearLayout.LayoutParams(0, -2, 1f);
            hcl.leftMargin = dp(a, 14);
            head.addView(hcol, hcl);
            final ImageView chev = new ImageView(a);
            Drawable chd = drawable(a, "ic_chevron");
            if (chd != null) {
                chev.setImageDrawable(chd);
                chev.setColorFilter(cGray);
            }
            head.addView(chev, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
            hl.topMargin = dp(a, 10);
            page.addView(head, hl);
            // 10.97 (правило владельца 16.10.2026): каждый пункт «Настроек» — отдельной страницей. Содержимое «Уведомлений»
            // собирается здесь же, но в «Настройки» не вставляется: по нажатию переносится на свою страницу (subPage).
            final LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            final Runnable[] onPage = new Runnable[1];
            // 10.98 (просьба владельца 16.10.2026): «Пункты выдачи» (10.95) и «Срок аренды» (10.77) перенесены
            // в «Настройка проката» — только админам, отдельная страница; там же «Данные проката».
            if (admin) try { IvaShopPatch.settingsRow(a, page, cText, cGray, cOrange); } catch (Throwable ignore) {}
            head.setContentDescription(NOTIF_TITLE + ", открыть");
            head.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { IvaChiefPatch.subPage(a, NOTIF_TITLE, body, onPage[0]); } catch (Throwable ignore) {}
                }
            });

            // в самом верху списка — настройки Android
            final TextView sys = linkButton(a, body, "Открыть настройки уведомлений Android", cOrange);
            sys.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openSystemSettings(a); } });

            body.addView(label(a, "Какие присылать", cGray));

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            Drawable bg = drawable(a, "bg_card");
            if (bg != null) card.setBackground(bg);
            card.setPadding(dp(a, 16), dp(a, 6), dp(a, 12), dp(a, 6));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = dp(a, 8);
            body.addView(card, cl);

            final Switch all = switchRow(a, card, "Все уведомления", "Строка состояния, шторка и всплывающие уведомления", K_ALL, cText, cGray, cOrange);
            final List<Switch> kids = new ArrayList<Switch>();
            kids.add(switchRow(a, card, "Новые сообщения", "Когда вам пишут в чат", K_MSG, cText, cGray, cOrange));
            if (admin) kids.add(switchRow(a, card, "Новые заявки", "Заявки на ваши инструменты", K_ORD, cText, cGray, cOrange));
            kids.add(switchRow(a, card, "Обновления приложения", UPD_SUB, K_UPD, cText, cGray, cOrange));
            // 10.94: только eclips и eclipsik — новая жёлтая (пробная) версия; можно выключить
            if (loggedIn(a) && IvaTestPatch.isTester(String.valueOf(get(a, "userEmail"))))
                kids.add(switchRow(a, card, IvaTestPatch.NOTE_TITLE, IvaTestPatch.NOTE_SUB, IvaTestPatch.K_TEST, cText, cGray, cOrange));
            // 10.76: клиентам — напоминание об окончании аренды и за сколько: 1, 2 или 3 часа (по умолчанию выключено)
            final Switch[] rentSw = new Switch[1];
            final TextView[] rentChips = new TextView[RENT_HOURS.length];
            final LinearLayout[] rentRow = new LinearLayout[1];
            final Runnable[] ref0 = new Runnable[1];
            if (loggedIn(a) && !admin) {
                rentSw[0] = switchRow(a, card, RENT_TITLE, RENT_SUB, K_RENT, cText, cGray, cOrange);
                kids.add(rentSw[0]);
                rentSw[0].setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                        setPref(a, K_RENT, v);
                        if (ref0[0] != null) ref0[0].run();
                    }
                });
                LinearLayout chips = new LinearLayout(a);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                chips.setPadding(0, 0, 0, dp(a, 12));
                for (int i = 0; i < RENT_HOURS.length; i++) {
                    final int h = RENT_HOURS[i];
                    TextView c = new TextView(a);
                    c.setText("За " + hoursText(h));
                    c.setTextSize(14f);
                    c.setGravity(Gravity.CENTER);
                    c.setPadding(dp(a, 14), 0, dp(a, 14), 0);
                    c.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!all.isChecked() || !rentSw[0].isChecked()) return;
                            prefs(a).edit().putInt(K_RENT_H, h).apply();
                            if (ref0[0] != null) ref0[0].run();
                        }
                    });
                    LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(-2, dp(a, 36));
                    if (i > 0) chl.leftMargin = dp(a, 8);
                    chips.addView(c, chl);
                    rentChips[i] = c;
                }
                rentRow[0] = chips;
                card.addView(chips, new LinearLayout.LayoutParams(-1, -2));
            }

            // 10.73: расписание — только админам
            final Switch[] schedSw = new Switch[1];
            final List<View> dayRows = new ArrayList<View>();
            final TextView[] dayTexts = new TextView[7];
            final Switch[] daySw = new Switch[7];
            final Runnable[] ref = new Runnable[1];
            final boolean[] drawing = new boolean[1];
            if (admin) {
                body.addView(label(a, "Расписание", cGray));
                LinearLayout sc = new LinearLayout(a);
                sc.setOrientation(LinearLayout.VERTICAL);
                Drawable sbg = drawable(a, "bg_card");
                if (sbg != null) sc.setBackground(sbg);
                sc.setPadding(dp(a, 16), dp(a, 6), dp(a, 12), dp(a, 10));
                LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, -2);
                scl.topMargin = dp(a, 8);
                body.addView(sc, scl);
                schedSw[0] = switchRow(a, sc, "По расписанию", SCHED_SUB, K_SCHED_ON, cText, cGray, cOrange);
                schedSw[0].setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                        setPref(a, K_SCHED_ON, v);
                        if (ref[0] != null) ref[0].run();
                    }
                });
                for (int i = 0; i < 7; i++) {
                    final int day = i;
                    LinearLayout row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(a, 2), 0, dp(a, 2));
                    TextView dl = new TextView(a);
                    dl.setText(DAYS[i]);
                    dl.setTextSize(15f);
                    dl.setTypeface(null, android.graphics.Typeface.BOLD);
                    dl.setTextColor(cText);
                    row.addView(dl, new LinearLayout.LayoutParams(dp(a, 40), -2));
                    TextView tt = new TextView(a);
                    tt.setTextSize(15f);
                    tt.setPadding(0, dp(a, 10), dp(a, 8), dp(a, 10));
                    row.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
                    Switch ds = new Switch(a);
                    tint(ds, cOrange);
                    row.addView(ds, new LinearLayout.LayoutParams(-2, -2));
                    tt.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (!all.isChecked() || !schedSw[0].isChecked() || !daySw[day].isChecked()) return;
                            pickHours(a, day, ref[0]);
                        }
                    });
                    ds.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                            if (drawing[0]) return;
                            int[][] d = loadSched(a);
                            d[day][0] = v ? 1 : 0;
                            saveSched(a, d);
                            if (ref[0] != null) ref[0].run();
                        }
                    });
                    dayTexts[i] = tt;
                    daySw[i] = ds;
                    dayRows.add(row);
                    sc.addView(row);
                }
                TextView copy = new TextView(a);
                copy.setText("Часы понедельника — на все дни");
                copy.setTextSize(14f);
                copy.setTypeface(null, android.graphics.Typeface.BOLD);
                copy.setTextColor(cOrange);
                copy.setPadding(0, dp(a, 10), 0, dp(a, 6));
                copy.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!all.isChecked() || !schedSw[0].isChecked()) return;
                        int[][] d = loadSched(a);
                        for (int i = 1; i < 7; i++) d[i] = d[0].clone();
                        saveSched(a, d);
                        if (ref[0] != null) ref[0].run();
                    }
                });
                dayRows.add(copy);
                sc.addView(copy);
                TextView sh = new TextView(a);
                sh.setText(SCHED_HINT);
                sh.setTextSize(12f);
                sh.setTextColor(cGray);
                sh.setPadding(0, dp(a, 2), 0, dp(a, 4));
                dayRows.add(sh);
                sc.addView(sh);
            }

            final TextView status = new TextView(a);
            status.setTextSize(13f);
            status.setTextColor(cGray);
            status.setLineSpacing(dp(a, 2), 1f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = dp(a, 12);
            body.addView(status, sl);

            final TextView test = linkButton(a, body, "Показать пробное уведомление", cOrange);
            test.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!systemAllows(a)) { askPermission(a); openSystemSettingsIfDenied(a); return; }
                    showNote(a, CH_MSG, ID_TEST, "Ива: уведомления работают", "Так будут выглядеть уведомления о сообщениях, заявках и обновлениях", "chat");
                }
            });

            TextView note = new TextView(a);
            note.setText(BG_NOTE);
            note.setTextSize(12f);
            note.setTextColor(cGray);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
            nl.topMargin = dp(a, 14);
            body.addView(note, nl);
            if (admin) {                     // 10.74: мгновенные уведомления (push) — только у админов
                final TextView push = new TextView(a);
                push.setText(IvaPushPatch.statusLine(a));
                push.setTextSize(12f);
                push.setTextColor(cGray);
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
                pl.topMargin = dp(a, 8);
                body.addView(push, pl);
                final int[] left = { 30 };
                final Runnable upd = new Runnable() {
                    @Override public void run() {
                        try {
                            push.setText(IvaPushPatch.statusLine(a));
                            if (--left[0] > 0 && push.getWindowToken() != null) push.postDelayed(this, 2000);
                        } catch (Throwable ignore) {}
                    }
                };
                push.postDelayed(upd, 2000);
                push.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        IvaPushPatch.retryNow(a);
                        push.setText(IvaPushPatch.statusText("", ""));
                        wake();
                        left[0] = 30;
                        push.removeCallbacks(upd);
                        push.postDelayed(upd, 2000);
                    }
                });
            }

            final Runnable refresh = new Runnable() {
                @Override public void run() {
                    boolean master = all.isChecked();
                    for (Switch s : kids) { s.setEnabled(master); ((View) s.getParent()).setAlpha(master ? 1f : 0.45f); }
                    boolean ok = systemAllows(a);
                    String sched = "";
                    boolean schedWarn = false;
                    if (schedSw[0] != null) {                     // 10.73: расписание админа
                        schedSw[0].setEnabled(master);
                        ((View) schedSw[0].getParent()).setAlpha(master ? 1f : 0.45f);
                        boolean son = master && schedSw[0].isChecked();
                        int[][] d = loadSched(a);
                        drawing[0] = true;
                        for (int i = 0; i < 7; i++) {
                            daySw[i].setChecked(d[i][0] == 1);
                            daySw[i].setEnabled(son);
                            dayTexts[i].setText(dayText(d[i]));
                            dayTexts[i].setTextColor(d[i][0] == 1 ? cOrange : cGray);
                        }
                        drawing[0] = false;
                        for (View r : dayRows) r.setAlpha(son ? 1f : 0.45f);
                        if (son) {
                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            int dow = dowMon(cal.get(java.util.Calendar.DAY_OF_WEEK));
                            int min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
                            sched = "\n" + schedStatus(d, dow, min);
                            schedWarn = nextStart(d, dow, min) == null;
                        }
                    }
                    status.setText((!master ? "Все уведомления выключены."
                        : ok ? "Android разрешает уведомления. Каждый вид можно отдельно настроить и в настройках Android."
                        : "Уведомления запрещены в настройках Android — включите их, иначе они не появятся.") + sched);
                    status.setTextColor(master && (!ok || schedWarn) ? cOrange : cGray);
                    if (rentSw[0] != null) {                      // 10.76: выбранное «за сколько» — оранжевым, как чипы фильтра
                        boolean ron = master && rentSw[0].isChecked();
                        int sel = rentHours(prefs(a).getInt(K_RENT_H, 1));
                        for (int i = 0; i < rentChips.length; i++) {
                            boolean on = RENT_HOURS[i] == sel;
                            Drawable cb = drawable(a, on ? "bg_chip_on" : "bg_chip");
                            if (cb != null) rentChips[i].setBackground(cb);
                            rentChips[i].setTextColor(on ? cOrange : cGray);   // 10.96: выбранный чип — рамкой, текст оранжевый
                            rentChips[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                            rentChips[i].setEnabled(ron);
                        }
                        rentRow[0].setAlpha(ron ? 1f : 0.45f);
                    }
                    hsub.setText(headSummary(master, ok, schedSw[0] != null && schedSw[0].isChecked()));
                    hsub.setTextColor(master && !ok ? cOrange : cGray);
                }
            };
            all.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                    setPref(a, K_ALL, v);
                    if (v && !systemAllows(a)) askPermission(a);
                    refresh.run();
                }
            });
            ref[0] = refresh;
            ref0[0] = refresh;
            onPage[0] = refresh;                 // 10.97: вернулись на страницу «Уведомления» из настроек Android — обновить
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

    static final String NOTIF_TITLE = "Уведомления";

    /** 10.75: строка под «Уведомления» в свёрнутом виде. */
    static String headSummary(boolean master, boolean systemOk, boolean sched) {
        if (!master) return "Выключены";
        if (!systemOk) return "Запрещены в настройках Android";
        return sched ? "Включены · по расписанию" : "Включены";
    }

    /** 10.75: подзаголовок внутри списка (серый, 13sp, жирный), как подписи разделов. */
    static TextView label(Activity a, String text, int cGray) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(cGray);
        t.setPadding(dp(a, 4), dp(a, 18), 0, 0);
        return t;
    }

    /** 10.75: значок-колокольчик — Material «notifications» (как остальные залитые значки 24×24), без новых ресурсов. */
    static Drawable bellIcon(final int color) {
        final android.graphics.Path p = new android.graphics.Path();
        p.moveTo(12f, 22f);
        p.cubicTo(13.1f, 22f, 14f, 21.1f, 14f, 20f);
        p.lineTo(10f, 20f);
        p.cubicTo(10f, 21.1f, 10.89f, 22f, 12f, 22f);
        p.close();
        p.moveTo(18f, 16f);
        p.lineTo(18f, 11f);
        p.cubicTo(18f, 7.93f, 16.36f, 5.36f, 13.5f, 4.68f);
        p.lineTo(13.5f, 4f);
        p.cubicTo(13.5f, 3.17f, 12.83f, 2.5f, 12f, 2.5f);
        p.cubicTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4f);
        p.lineTo(10.5f, 4.68f);
        p.cubicTo(7.63f, 5.36f, 6f, 7.92f, 6f, 11f);
        p.lineTo(6f, 16f);
        p.lineTo(4f, 18f);
        p.lineTo(4f, 19f);
        p.lineTo(20f, 19f);
        p.lineTo(20f, 18f);
        p.close();
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        return new Drawable() {
            @Override public void draw(android.graphics.Canvas c) {
                android.graphics.Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) return;
                int save = c.save();
                c.translate(b.left, b.top);
                c.scale(b.width() / 24f, b.height() / 24f);
                c.drawPath(p, paint);
                c.restoreToCount(save);
            }
            @Override public void setAlpha(int al) { paint.setAlpha(al); invalidateSelf(); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            @Override public int getIntrinsicWidth() { return 24; }
            @Override public int getIntrinsicHeight() { return 24; }
        };
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
        sw.setChecked(prefs(a).getBoolean(k, defOn(k)));   // всё включено, кроме расписания (10.73)
        tint(sw, cOrange);
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

    static void tint(Switch sw, int cOrange) {
        if (Build.VERSION.SDK_INT >= 23) {
            int[][] st = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(st, new int[] {cOrange, 0xffd0d3da}));
            sw.setTrackTintList(new ColorStateList(st, new int[] {(cOrange & 0x00ffffff) | 0x88000000, 0x55a0a4ad}));
        }
    }

    static final String SCHED_SUB = "Уведомления приходят только в выбранные дни и часы";
    static final String SCHED_HINT = "Что придёт в другое время, покажем, когда начнётся разрешённое (если вы ещё не прочитали "
        + "сообщение и заявку не взяли). Когда приложение закрыто, Android проверяет примерно раз в 15 минут. "
        + "«С» позже «до» (например, 22:00 – 08:00) — через полночь; одинаковое время — круглосуточно.";

    /** Часы дня: сначала «с», потом «до» (24 часа, как в телефоне). Отмена — ничего не меняется. */
    static void pickHours(final Activity a, final int day, final Runnable after) {
        try {
            final int[][] d = loadSched(a);
            android.app.TimePickerDialog from = new android.app.TimePickerDialog(a, android.R.style.Theme_Material_Dialog,
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(android.widget.TimePicker v, int h, int m) {
                        final int f = h * 60 + m;
                        try {
                            android.app.TimePickerDialog to = new android.app.TimePickerDialog(a, android.R.style.Theme_Material_Dialog,
                                new android.app.TimePickerDialog.OnTimeSetListener() {
                                    @Override public void onTimeSet(android.widget.TimePicker v2, int h2, int m2) {
                                        d[day][0] = 1;
                                        d[day][1] = f;
                                        d[day][2] = h2 * 60 + m2;
                                        saveSched(a, d);
                                        if (after != null) after.run();
                                    }
                                }, d[day][2] / 60, d[day][2] % 60, true);
                            to.setTitle("До какого времени — " + DAYS_FULL[day]);
                            to.show();
                        } catch (Throwable ignore) {}
                    }
                }, d[day][1] / 60, d[day][1] % 60, true);
            from.setTitle("С какого времени — " + DAYS_FULL[day]);
            from.show();
        } catch (Throwable e) {
            try { Toast.makeText(a, "Не удалось открыть выбор времени", Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
        }
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


    // ───────────── 10.65: этап 2 — проверка, когда приложение закрыто (решение владельца 13.10.2026, без Firebase) ─────────────
    // Системный планировщик Android (JobScheduler) раз в ~15 минут (чаще Android не даёт) будит службу Bg: те же запросы,
    // те же переключатели «Настроек», та же память «уже показано» (prefs iva_notify) — уведомление не придёт дважды.
    // Пока окно приложения живо — служба ничего не делает (работает этап 1, раз в 30 с).
    // Вход — сохранённый штатным входом токен (prokat_session: at, rt, email). Истёк — обновляем по rt и записываем новую
    // пару туда же, как штатный saveSession: в Supabase rt одноразовый, иначе при следующем запуске приложение разлогинит.
    // Не обновился — молча пропускаем (выход из аккаунта — только штатный). Админ ли это — запоминает этап 1.
    static final int BG_JOB_ID = 7210;
    static final long BG_EVERY_MS = 15L * 60 * 1000;
    static final long BG_REFRESH_AHEAD_MS = 2L * 60 * 1000;
    static final String SESSION_PREFS = "prokat_session";   // штатное хранилище входа (MainActivity.onCreate)
    static final String BG_NOTE = "Уведомления приходят и когда приложение закрыто: Android проверяет примерно раз в 15 минут "
        + "(в режиме экономии батареи — реже). На Xiaomi, Huawei и Honor для этого может понадобиться разрешить Иве «Автозапуск» "
        + "или «Без ограничений» в настройках батареи.";
    static volatile boolean bgScheduled;

    /** Контекст фоновой проверки: приложение + токен сохранённого входа (getArr берёт его отсюда). */
    static final class BgCtx extends android.content.ContextWrapper {
        final String token;
        BgCtx(Context base, String token) { super(base); this.token = token; }
    }

    /** Служба для JobScheduler (запись в манифесте — axml_service.py, с разрешением BIND_JOB_SERVICE). */
    public static final class Bg extends android.app.job.JobService {
        @Override public boolean onStartJob(final android.app.job.JobParameters params) {
            final Context c = getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    try { bgRun(c); } catch (Throwable ignore) {
                    } finally {
                        try { jobFinished(params, false); } catch (Throwable ignore) {}
                    }
                }
            }).start();
            return true;
        }
        @Override public boolean onStopJob(android.app.job.JobParameters params) { return false; }
    }

    static Class<?> mainClass(Context a) {
        if (a instanceof Activity) return a.getClass();
        try { return Class.forName("ru.prokatnvsk.app.MainActivity"); } catch (Throwable e) { return Activity.class; }
    }

    static int ctxColor(Context a, String n, int f) {
        try {
            int rid = a.getResources().getIdentifier(n, "color", a.getPackageName());
            return rid == 0 ? f : a.getResources().getColor(rid);
        } catch (Throwable e) { return f; }
    }

    /** Штатный MainActivity$Api.call(url, method, body, bearer, token) → {код, тело}. */
    static String[] nativeCall(String url, String method, String body, boolean bearer, String token) throws Exception {
        Class<?> c = Class.forName("ru.prokatnvsk.app.MainActivity$Api");
        Method m = c.getDeclaredMethod("call", String.class, String.class, String.class, boolean.class, String.class);
        m.setAccessible(true);
        return (String[]) m.invoke(null, url, method, body, bearer, token);
    }

    static boolean activityAlive() {
        Activity x = act == null ? null : act.get();
        return x != null && !x.isFinishing();
    }

    /** Ставим один раз; уже стоит с тем же периодом — не трогаем (перестановка сбила бы отсчёт). */
    static void scheduleBg(Context c) {
        if (bgScheduled || Build.VERSION.SDK_INT < 21) return;
        android.app.job.JobScheduler js = (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (android.app.job.JobInfo j : js.getAllPendingJobs())
            if (j.getId() == BG_JOB_ID && j.getIntervalMillis() == BG_EVERY_MS) { bgScheduled = true; return; }
        android.app.job.JobInfo.Builder b = new android.app.job.JobInfo.Builder(BG_JOB_ID, new android.content.ComponentName(c, Bg.class))
            .setPeriodic(BG_EVERY_MS)
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY);
        // после перезагрузки телефона — сама (нужно RECEIVE_BOOT_COMPLETED; без него — до первого открытия приложения)
        if (c.getPackageManager().checkPermission("android.permission.RECEIVE_BOOT_COMPLETED", c.getPackageName()) == PackageManager.PERMISSION_GRANTED)
            b.setPersisted(true);
        bgScheduled = js.schedule(b.build()) == android.app.job.JobScheduler.RESULT_SUCCESS;
    }

    /** Нужна ли фоновая проверка: окно закрыто и общий переключатель включён. */
    static boolean bgShouldRun(boolean alive, boolean all) { return !alive && all; }

    /** Токен пора обновить: срок неизвестен, истёк или истекает в ближайшие 2 минуты. */
    static boolean needRefresh(long expSec, long nowMs) { return expSec <= 0 || expSec * 1000L - nowMs < BG_REFRESH_AHEAD_MS; }

    /** Срок действия токена (поле exp из середины JWT, секунды); не разобрали — 0. */
    static long jwtExp(String jwt) {
        try {
            String[] parts = jwt == null ? new String[0] : jwt.split("\\.");
            if (parts.length < 2) return 0;
            String json = new String(b64url(parts[1]), "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"exp\"\\s*:\\s*(\\d+)").matcher(json);
            return m.find() ? Long.parseLong(m.group(1)) : 0;
        } catch (Throwable e) { return 0; }
    }

    static byte[] b64url(String s) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '+') ch = '-';
            else if (ch == '/') ch = '_';
            int v = abc.indexOf(ch);
            if (v < 0) continue;            // «=» и мусор
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) { bits -= 8; out.write((buf >> bits) & 0xff); }
        }
        return out.toByteArray();
    }

    static String jsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : (s == null ? "" : s).toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    /** Обновить токен по rt и записать новую пару в штатное хранилище (как saveSession). Не вышло — null. */
    static String bgRefresh(SharedPreferences s, String rt) {
        synchronized (IvaClientPatch.AUTH_LOCK) { return bgRefreshLocked(s, rt); }   // 10.69: одно обновление за раз
    }

    static String bgRefreshLocked(SharedPreferences s, String rt) {
        try {
            if (rt == null || rt.trim().isEmpty()) return null;
            if (!rt.equals(s.getString("rt", ""))) {                                  // 10.69: пока ждали — уже обновили (или вышли)
                String cur = s.getString("at", "");
                return cur == null || cur.trim().isEmpty() || "null".equals(cur.trim()) ? null : cur;
            }
            String[] r = nativeCall(IvaClientPatch.SB_URL + "/auth/v1/token?grant_type=refresh_token", "POST",
                "{\"refresh_token\":" + jsonStr(rt) + "}", false, null);
            if (r == null || r.length < 2 || r[0] == null || !r[0].startsWith("2")) return null;
            JSONObject j = new JSONObject(r[1]);
            String at = j.optString("access_token", ""), nrt = j.optString("refresh_token", "");
            if (at.isEmpty() || nrt.isEmpty() || "null".equals(at) || "null".equals(nrt)) return null;
            // 10.69: сервер уже сменил пару — старая через несколько секунд не действует. Раньше новую выбрасывали,
            // если окно успело открыться, — и вход слетал. Теперь сохраняем всегда, кроме случая «пока ходили — вошли/вышли»;
            // открытое окно подхватит пару из хранилища (IvaClientPatch.authTick).
            if (!rt.equals(s.getString("rt", ""))) return null;
            s.edit().putString("at", at).putString("rt", nrt).commit();
            return at;
        } catch (Throwable e) { return null; }
    }

    /** Одна фоновая проверка (поток службы Bg). */
    static void bgRun(Context app) {
        if (!bgShouldRun(activityAlive(), pref(app, K_ALL))) return;
        SharedPreferences s = app.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
        String at = s.getString("at", ""), email = s.getString("email", "");
        at = at == null ? "" : at.trim();
        email = email == null ? "" : email.trim();
        boolean logged = !at.isEmpty() && !email.isEmpty();
        if (logged && needRefresh(jwtExp(at), System.currentTimeMillis())) {
            String fresh = bgRefresh(s, s.getString("rt", ""));
            if (fresh == null) logged = false; else at = fresh;
        }
        if (logged) try { IvaClientPatch.authBackup(app); } catch (Throwable ignore) {}   // 10.69: копия входа
        BgCtx c = new BgCtx(app, logged ? at : null);
        boolean admin = logged && prefs(app).getBoolean(key("admin", email), false);
        if (logged && admin) try { checkOrders(c, email); } catch (Throwable ignore) {}
        if (logged) try { checkMessages(c, email, admin, null); } catch (Throwable ignore) {}
        try { checkUpdate(c, admin); } catch (Throwable ignore) {}
        if (logged && !admin) try { checkRent(c, email, admin); } catch (Throwable ignore) {}   // 10.76
        if (logged) try { IvaTestPatch.check(c, email, admin); } catch (Throwable ignore) {}    // 10.94: пробная версия
    }
}

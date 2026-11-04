package ru.prokatnvsk.app;

// 10.133 (просьба владельца 04.11.2026): «подключить платежи владельцев прокатов к моей ЮKassa».
// Решения владельца: платят только КОМИССИЮ площадки; кнопка — только в приложении; сумма любая, по умолчанию весь долг;
// чеки самозанятого владелец пробивает сам в «Мой налог» (ЮKassa с 29.12.2025 их не оформляет) — в «Панели владельца»
// список онлайн-оплат с отметкой «Чек пробит». Новых данных у админов не спрашиваем.
//
//   • Админ: под суммой карточки «Комиссия владельцу» в «Кабинете» — кнопка «Оплатить онлайн» (только когда сервер
//     ответил «подключено»: есть ключи ЮKassa). Страница «Оплата комиссии»: сумма (весь долг — подставлен), «Перейти к
//     оплате» → сервер (функция iva-pay) создаёт платёж → страница оплаты ЮKassa во вкладке браузера поверх приложения
//     (СБП, карта, SberPay…; данные карты в приложение не попадают). Вернулись — приложение само спрашивает итог:
//     каждые 5 с первые 2 минуты, потом раз в 20 с, до 15 минут; закрыли приложение — проверит при следующем «Кабинете».
//     Оплачено — «Оплата получена», карточка комиссии перечитывается (долг уменьшился).
//   • Засчитывает только сервер по ответу самой ЮKassa (SQL 20261123: строка «confirmed» в fee_payments) — приложение
//     ничего не «подтверждает» и не может.
//   • Владелец площадки: «Управление» → «Комиссия» — раздел «Онлайн-оплаты ЮKassa»: оплаченные, кто, сумма, когда, номер
//     ЮKassa и способ; «Чек пробит» / снять отметку; сверху — сколько чеков ещё не пробито.
//
// Без новых вставок в smali: вход — IvaAccessPatch.feeCard (после каждой перерисовки «Кабинета») и IvaChiefPatch.openFee.
// Сеть — только в своём потоке.

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class IvaPayPatch {
    private IvaPayPatch() {}

    static final String FN = IvaClientPatch.SB_URL + "/functions/v1/iva-pay";
    static final String Q_ROW = "/rest/v1/iva_yk_pay?select=id,status,amount&id=eq.";
    static final String Q_CHIEF = "/rest/v1/iva_yk_pay?select=id,admin_email,amount,status,yk_id,method,paid_at,receipt_done"
        + "&status=eq.succeeded&order=id.desc&limit=200";
    static final String Q_OPEN = "/rest/v1/iva_yk_pay?select=id&status=eq.pending&limit=200";
    static final String RECEIPT_RPC = "/rest/v1/rpc/iva_yk_receipt";
    static final String TITLE = "Оплата комиссии";
    static final String GO = "Перейти к оплате";
    static final String NATIVE_BTN = "Оплатить";
    static final String ASK = "Введите сумму для оплаты";
    static final String MANUAL = "Отправить запрос";
    static final String MANUAL_PATH = "/rest/v1/fee_payments";
    static final double OVER_MAX = 5000;          // 10.134: переплата — не больше 5 000 ₽ (решение владельца 04.11.2026)
    static final String BLOCKED = "Переплата больше 5 000 ₽ — вносить оплату пока не нужно: комиссия с новых аренд спишется из переплаты.";
    static final String TAG = "iva_yk_btn";
    static final String K_WAIT = "yk_wait_";
    static final double MIN = 1, MAX = 100000;
    static final long READY_TTL = 10 * 60 * 1000L;
    static final long POLL_MAX_MS = 15 * 60 * 1000L;
    static final long WAIT_KEEP_MS = 3L * 24 * 3600 * 1000;

    /** 0 — не знаем, 1 — подключено, 2 — нет, 3 — подключено в тестовом режиме ЮKassa. */
    static volatile int ready = 0;
    static volatile long readyAt = 0;
    static final AtomicBoolean READY_BUSY = new AtomicBoolean();
    static final AtomicBoolean POLL_BUSY = new AtomicBoolean();

    // ════════════════════════════════ чистые функции (IvaPatchTest) ════════════════════════════════

    /** «1 500,50» → 1500.5; не число, больше двух знаков после запятой, вне 1–100 000 — null. */
    static Double amount(String s) {
        String t = s == null ? "" : s.replace(" ", "").replace("\u00a0", "").replace(',', '.').trim();
        if (!t.matches("\\d{1,6}(\\.\\d{1,2})?")) return null;
        double v = Math.round(Double.parseDouble(t) * 100) / 100.0;
        return v >= MIN && v <= MAX ? Double.valueOf(v) : null;
    }

    /** Сумма для поля: 1500 → «1500», 1500.5 → «1500.50». */
    static String amountText(double v) {
        long k = Math.round(v * 100);
        return k % 100 == 0 ? String.valueOf(k / 100) : String.format(Locale.US, "%d.%02d", k / 100, k % 100);
    }

    /** Долг по кэшу карточки комиссии («начислено|оплачено|процент|время»): до копейки вверх; нет — 0. */
    static double debtOf(String cache) {
        double[] c = IvaAccessPatch.feeCache(cache);
        if (c == null) return 0;
        double d = c[0] - c[1];
        return d <= 0.004 ? 0 : Math.ceil(d * 100 - 1e-6) / 100.0;
    }

    /** 10.134: долг по числам карточки (начислено, оплачено): до копейки вверх; нет долга — 0. */
    static double debtOf(double fee, double paid) {
        double d = fee - paid;
        return d <= 0.004 ? 0 : Math.ceil(d * 100 - 1e-6) / 100.0;
    }

    /** 10.134: сколько можно внести, чтобы переплата не стала больше 5 000 ₽ (и не больше 100 000 за раз); меньше 1 — нельзя. */
    static double payMax(double fee, double paid) {
        double m = Math.floor((fee - paid + OVER_MAX) * 100 + 1e-6) / 100.0;
        return Math.min(m, MAX);
    }

    /** «К оплате 1 250 ₽» / «Переплата 300 ₽» — как на карточке комиссии. */
    static String sumLine(double fee, double paid) { return IvaAccessPatch.feeSumText(fee, paid); }

    /** Подсказка под полем. */
    static String limitText(double max) {
        return max >= MAX ? "От 1 до 100 000 ₽ за один раз" : "От 1 до " + rub(max) + " — переплата не больше 5 000 ₽";
    }

    /** Ошибка суммы или null — можно. */
    static String amountError(Double v, double max) {
        if (max < MIN) return BLOCKED;
        if (v == null) return "Введите сумму от 1 до " + rub(max);
        if (v > max + 0.004) return max >= MAX ? "Не больше 100 000 ₽ за один раз" : "Не больше " + rub(max) + " — переплата не может быть больше 5 000 ₽";
        return null;
    }

    /** Пояснение в окне: 1 / 3 — ЮKassa (3 — тестовый режим), 2 — переводом с запросом, 0 — ещё не знаем. */
    static String modeText(int r) {
        if (r == 1 || r == 3) return "Через ЮKassa: СБП, карта, SberPay. Долг уменьшится сам, как только ЮKassa подтвердит оплату."
            + (r == 3 ? "\nТестовый режим ЮKassa — настоящие деньги не списываются." : "");
        if (r == 2) return "Переведите сумму владельцу площадки и отправьте запрос — он подтвердит получение.";
        return "Проверяем способ оплаты…";
    }

    /** Способ оплаты для окна: ЮKassa не ответила / функции нет, а проверка уже была — переводом (2). */
    static int mode(boolean checked) { return ready == 0 ? (checked ? 2 : 0) : ready; }

    /** Запрос «я оплатил переводом» — как у штатного окна: строка «pending», подтверждает владелец площадки. */
    static String manualBody(String me, double v) {
        String e = (me == null ? "" : me).replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"admin_email\":\"" + e + "\",\"amount\":" + amountText(v) + ",\"note\":\"Оплата комиссии\",\"status\":\"pending\"}";
    }

    /** Тело запроса «создать платёж». */
    static String newBody(double v) { return "{\"a\":\"new\",\"amount\":\"" + amountText(v) + "\"}"; }

    static String checkBody(long id) { return "{\"a\":\"check\",\"id\":" + id + "}"; }

    /** Строка или число из JSON-ответа сервера (без org.json — проверяется на JVM); нет / не JSON — "". */
    static String jstr(String body, String key) {
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("{")) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key)
            + "\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(-?[0-9]+(?:\\.[0-9]+)?|true|false))").matcher(t);
        if (!m.find()) return "";
        if (m.group(2) != null) return m.group(2);
        return m.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\u0026", "&").replace("\\\\", "\\");
    }

    /** Ответ GET сервера → 1 подключено / 3 тестовый режим / 2 нет. */
    static int readyOf(String body) {
        if (!"true".equals(jstr(body, "ready"))) return 2;
        return "true".equals(jstr(body, "test")) ? 3 : 1;
    }

    /** Ошибка сервера → текст для админа. */
    static String errText(String code, String msg) {
        if ("not_ready".equals(code)) return "Онлайн-оплата пока не подключена — оплатите переводом, как раньше";
        if ("not_admin".equals(code)) return "Онлайн-оплата — только для админа проката";
        if ("bad_amount".equals(code)) return "Сумма — от 1 до 100 000 ₽";
        if ("too_many".equals(code)) return "Слишком много попыток подряд — подождите 10 минут";
        if ("auth".equals(code)) return "Войдите в аккаунт заново и повторите";
        if ("yookassa".equals(code)) return "ЮKassa не приняла платёж" + (msg == null || msg.isEmpty() ? "" : ": " + msg);
        return "Не получилось — проверьте интернет и попробуйте ещё раз";
    }

    /** Отметка «ждём итог оплаты»: «номер@время». */
    static String waitVal(long id, long ms) { return id + "@" + ms; }

    /** «номер@время» → {номер, время}; не разобрали — null. */
    static long[] waitParse(String v) {
        if (v == null) return null;
        int at = v.indexOf('@');
        if (at <= 0) return null;
        try {
            long id = Long.parseLong(v.substring(0, at)), ms = Long.parseLong(v.substring(at + 1));
            return id > 0 && ms > 0 ? new long[]{id, ms} : null;
        } catch (Throwable e) { return null; }
    }

    /** Пауза между проверками итога: первые 2 минуты — 5 с, потом 20 с. */
    static long pollDelay(long elapsedMs) { return elapsedMs < 120000L ? 5000L : 20000L; }

    /** Способ оплаты ЮKassa → по-русски. */
    static String method(String m) {
        if (m == null || m.isEmpty()) return "";
        if ("sbp".equals(m)) return "СБП";
        if ("bank_card".equals(m)) return "карта";
        if ("sberbank".equals(m)) return "SberPay";
        if ("yoo_money".equals(m)) return "ЮMoney";
        if ("tinkoff_bank".equals(m)) return "T-Pay";
        if ("mir_pay".equals(m)) return "Mir Pay";
        return m;
    }

    /** {оплачено онлайн, чеков не пробито, сумма без чека} по строкам: статус, сумма, «чек пробит». Не succeeded — не в счёт. */
    static double[] chiefTotals(String[] status, double[] amount, boolean[] receipt) {
        double paid = 0, left = 0; int n = 0;
        if (status != null) for (int i = 0; i < status.length; i++) {
            if (!"succeeded".equals(status[i])) continue;
            paid += amount[i];
            if (!receipt[i]) { n++; left += amount[i]; }
        }
        return new double[]{paid, n, left};
    }

    static String rub(double v) {
        long k = Math.round(v * 100);
        String s = String.format(Locale.US, "%,d", k / 100).replace(',', ' ');
        return (k % 100 == 0 ? s : s + String.format(Locale.US, ",%02d", k % 100)) + " ₽";
    }

    // ════════════════════════════════ админ: кнопка и страница оплаты ════════════════════════════════

    static String waitKey(String me) { return K_WAIT + me; }

    /** Из IvaAccessPatch.feeCard (главный поток): кнопка под суммой; узнать «подключено ли»; проверить начатую оплату. */
    static void card(final Activity a, final TextView[] v, final String me) {
        if (a == null || v == null || v.length < 3 || me == null || me.isEmpty()) return;
        hookNative(a);                                                     // 10.134: одна кнопка — родная «Оплатить»
        if (ready == 0 || System.currentTimeMillis() - readyAt > READY_TTL) fetchReady(a);
        long[] w = waitParse(IvaAccessPatch.prefs(a).getString(waitKey(me), ""));
        if (w != null) {
            long age = System.currentTimeMillis() - w[1];
            poll(a, me, w[0], age < 20 * 60 * 1000L ? POLL_MAX_MS : 0);
        }
    }

    /** 10.134: родная кнопка «Оплатить» карточки комиссии открывает наше окно (родное — простое окно Android и запрос
     *  к несуществующей функции yk-create-payment). Отдельной кнопки «Оплатить онлайн» больше нет — одна кнопка. */
    static void hookNative(final Activity a) {
        Object o = IvaClientPatch.get(a, "feePay");
        if (!(o instanceof TextView)) return;
        TextView b = (TextView) o;
        if (TAG.equals(b.getTag())) return;
        b.setTag(TAG);
        b.setText(NATIVE_BTN);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { open(a); } });
    }

    static void fetchReady(final Activity a) {
        if (!READY_BUSY.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, FN, "GET", null, false);
                    int was = ready;
                    ready = r != null && r.length > 1 && r[0] != null && r[0].startsWith("2") ? readyOf(r[1]) : (ready == 0 ? 0 : ready);
                    readyAt = System.currentTimeMillis();
                    if (ready != was && (ready == 1 || ready == 3)) a.runOnUiThread(new Runnable() {
                        @Override public void run() { try { IvaAccessPatch.feeCard(a); } catch (Throwable ignore) {} }
                    });
                } catch (Throwable ignore) {
                } finally { READY_BUSY.set(false); }
            }
        }).start();
    }

    /** Окно «Оплата комиссии» в стиле приложения (как окно приглашения): сумма к оплате / переплата, поле «Введите сумму
     *  для оплаты», «Весь долг». Переплата больше 5 000 ₽ — ввести сумму нельзя; сумма — не больше той, после которой
     *  переплата станет больше 5 000 ₽. ЮKassa подключена — «Перейти к оплате»; нет — «Отправить запрос», как раньше. */
    static void open(final Activity a) {
        final String me = IvaAccessPatch.me(a);
        if (me.isEmpty()) return;
        final int cText = IvaChiefPatch.color(a, "text", 0xfff2f4f8), cGray = IvaChiefPatch.color(a, "gray", 0xff9aa1b0);
        final int cRed = IvaChiefPatch.color(a, "redBright", 0xffff8f1f), cGreen = IvaChiefPatch.color(a, "greenBright", 0xff3ec27a);
        final int cOrange = IvaChiefPatch.color(a, "orange", 0xffff8f1f);
        final android.app.Dialog d = new android.app.Dialog(a);
        final LinearLayout card = IvaAccessPatch.card(a, d, TITLE, modeText(mode(ready != 0)));
        final TextView mode = (TextView) card.getChildAt(1);
        final TextView status = IvaChiefPatch.text(a, "…", 22f, cText, true);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = IvaChiefPatch.dp(a, 14);
        card.addView(status, sl);
        final TextView sub = IvaChiefPatch.text(a, "", 12f, cGray, false);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        // ── поле суммы ──
        final LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = IvaChiefPatch.dp(a, 16);
        card.addView(form, fl);
        TextView label = IvaChiefPatch.text(a, ASK, 14f, cText, true);
        label.setPadding(0, 0, 0, IvaChiefPatch.dp(a, 8));
        form.addView(label);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.Drawable fb = IvaSearchPatch.drawable(a, "bg_field");
        if (fb != null) box.setBackground(fb);
        box.setPadding(IvaChiefPatch.dp(a, 16), IvaChiefPatch.dp(a, 4), IvaChiefPatch.dp(a, 16), IvaChiefPatch.dp(a, 4));
        final EditText f = new EditText(a);
        f.setBackground(null);
        f.setHint("0");
        f.setHintTextColor(IvaChiefPatch.color(a, "gray2", 0xff6b7080));
        f.setTextColor(cText);
        f.setTextSize(24f);
        f.setTypeface(null, android.graphics.Typeface.BOLD);
        f.setSingleLine(true);
        f.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        f.setPadding(0, IvaChiefPatch.dp(a, 8), 0, IvaChiefPatch.dp(a, 8));
        f.setContentDescription(ASK);
        box.addView(f, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(IvaChiefPatch.text(a, "₽", 22f, cGray, true));
        form.addView(box, new LinearLayout.LayoutParams(-1, -2));
        final TextView chip = IvaSearchPatch.chip(a, "", false, false);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = IvaChiefPatch.dp(a, 10);
        cl.gravity = Gravity.CENTER_HORIZONTAL;
        form.addView(chip, cl);
        final TextView hint = IvaChiefPatch.text(a, "", 12f, cGray, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, IvaChiefPatch.dp(a, 8), 0, 0);
        form.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        final TextView go = IvaAccessPatch.bubble(a, card, "…", true, 16);
        final TextView no = IvaAccessPatch.bubble(a, card, "Отмена", false, 8);
        no.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { try { d.dismiss(); } catch (Throwable ignore) {} } });

        // состояние окна: {начислено, оплачено} — с телефона, затем свежие с сервера
        final double[] st = {Double.NaN, Double.NaN};
        final boolean[] rk = {ready != 0};                                // узнали, подключена ли ЮKassa (или не смогли — переводом)
        final Runnable paint = new Runnable() {
            @Override public void run() {
                boolean known = !Double.isNaN(st[0]);
                int m = mode(rk[0]);
                double debt = known ? debtOf(st[0], st[1]) : 0, max = known ? payMax(st[0], st[1]) : 0;
                status.setText(known ? sumLine(st[0], st[1]) : "…");
                status.setTextColor(known && debt > 0 ? cRed : known ? cGreen : cText);
                sub.setText(known ? "начислено " + IvaAccessPatch.feeMoney(st[0]) + " ₽ · оплачено " + IvaAccessPatch.feeMoney(st[1]) + " ₽" : "Загружаем сумму…");
                boolean blocked = known && max < MIN;
                form.setVisibility(blocked ? View.GONE : View.VISIBLE);
                mode.setText(blocked ? BLOCKED : modeText(m));
                if (known && debt >= MIN && max >= MIN) {
                    final double all = Math.min(debt, max);
                    chip.setText("Весь долг — " + rub(all));
                    chip.setVisibility(View.VISIBLE);
                    chip.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View x) { f.setText(amountText(all)); f.setSelection(f.getText().length()); }
                    });
                    if (f.getText().length() == 0) { f.setText(amountText(all)); f.setSelection(f.getText().length()); }
                } else chip.setVisibility(View.GONE);
                if (hint.getTag() == null) { hint.setText(known && !blocked ? limitText(max) : ""); hint.setTextColor(cGray); }
                go.setText(blocked ? "Понятно" : m == 0 ? "…" : (m == 1 || m == 3) ? GO : MANUAL);
                boolean can = blocked || (known && m != 0);
                go.setEnabled(can);
                go.setAlpha(can ? 1f : 0.6f);
            }
        };
        double[] c = IvaAccessPatch.feeCache(IvaAccessPatch.prefs(a).getString(IvaAccessPatch.feeCacheKey(me), ""));
        if (c != null) { st[0] = c[0]; st[1] = c[1]; }
        paint.run();
        f.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence q, int x, int y, int z) {}
            @Override public void onTextChanged(CharSequence q, int x, int y, int z) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (hint.getTag() == null) return;
                hint.setTag(null);
                hint.setText(Double.isNaN(st[0]) ? "" : limitText(payMax(st[0], st[1])));
                hint.setTextColor(cGray);
            }
        });
        // свежие суммы и «подключено ли» — в своём потоке
        new Thread(new Runnable() {
            @Override public void run() {
                double[] r = null;
                try {
                    IvaClientPatch.authFresh(a);
                    r = IvaAccessPatch.feeFetch(a, me);
                    if (r != null) {
                        double[] k = {r[0], r[1], r[2], System.currentTimeMillis()};
                        IvaAccessPatch.prefs(a).edit().putString(IvaAccessPatch.feeCacheKey(me), IvaAccessPatch.feeMoneyKey(k)).apply();
                        IvaAccessPatch.feePost(a, me, k, null);
                    }
                } catch (Throwable ignore) {}
                try {
                    String[] q = IvaClientPatch.apiCall(a, FN, "GET", null, false);
                    if (q != null && q.length > 1 && q[0] != null && q[0].startsWith("2")) ready = readyOf(q[1]);
                    else if (q != null && q[0] != null && q[0].startsWith("4")) ready = 2;   // функции нет (404) — переводом
                    readyAt = System.currentTimeMillis();
                } catch (Throwable ignore) {}
                final double[] fr = r;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            rk[0] = true;
                            if (fr != null) { st[0] = fr[0]; st[1] = fr[1]; }
                            else if (Double.isNaN(st[0])) { paint.run(); sub.setText("Не удалось загрузить сумму — проверьте интернет"); return; }
                            paint.run();
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();

        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (Double.isNaN(st[0]) || mode(rk[0]) == 0 && payMax(st[0], st[1]) >= MIN) return;
                double max = payMax(st[0], st[1]);
                if (max < MIN) { try { d.dismiss(); } catch (Throwable ignore) {} return; }
                final Double v = amount(f.getText().toString());
                String bad = amountError(v, max);
                if (bad != null) { hint.setText(bad); hint.setTextColor(cRed); hint.setTag("err"); return; }
                final int m = mode(rk[0]);
                final boolean online = m == 1 || m == 3;
                final String label0 = go.getText().toString();
                go.setEnabled(false);
                go.setAlpha(0.6f);
                go.setText(online ? "Создаём платёж…" : "Отправляем…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String err = null, url = "";
                        long id = 0;
                        try {
                            IvaClientPatch.authFresh(a);
                            if (online) {
                                String[] r = IvaClientPatch.apiCall(a, FN, "POST", newBody(v), true);
                                String body = r != null && r.length > 1 ? r[1] : "";
                                if (r == null || r[0] == null) err = errText("", "");
                                else if (!r[0].startsWith("2")) err = errText(jstr(body, "error"), jstr(body, "message"));
                                else {
                                    url = jstr(body, "url");
                                    try { id = Long.parseLong(jstr(body, "id")); } catch (Throwable e) { id = 0; }
                                    if (!url.startsWith("https://")) err = errText("", "");
                                }
                            } else {
                                String[] r = IvaClientPatch.apiCall(a, MANUAL_PATH, "POST", manualBody(me, v), true);
                                if (r == null || r[0] == null || !r[0].startsWith("2")) err = "Не удалось отправить запрос — проверьте интернет и попробуйте ещё раз";
                            }
                        } catch (Throwable e) { err = errText("", ""); }
                        final String fe = err, fu = url;
                        final long fid = id;
                        if (fe == null && online && fid > 0) IvaAccessPatch.prefs(a).edit().putString(waitKey(me), waitVal(fid, System.currentTimeMillis())).apply();
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                go.setEnabled(true);
                                go.setAlpha(1f);
                                go.setText(label0);
                                if (fe != null) { hint.setText(fe); hint.setTextColor(cRed); hint.setTag("err"); return; }
                                try { d.dismiss(); } catch (Throwable ignore) {}
                                if (!online) { IvaStaffPatch.toast(a, "Запрос отправлен — владелец площадки подтвердит оплату"); return; }
                                openUrl(a, fu);
                                if (fid > 0) poll(a, me, fid, POLL_MAX_MS);
                            }
                        });
                    }
                }).start();
            }
        });
        IvaAccessPatch.track(d);
        IvaAccessPatch.show(a, d, card, true);
        try {
            android.view.Window w = d.getWindow();
            if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignore) {}
    }

    /** Страница ЮKassa во вкладке браузера поверх приложения (Custom Tab без библиотек); не вышло — обычный браузер. */
    static void openUrl(Activity a, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            Bundle ex = new Bundle();
            ex.putBinder("android.support.customtabs.extra.SESSION", null);
            i.putExtras(ex);
            i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", IvaChiefPatch.color(a, "bg", 0xff101217));
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            IvaStaffPatch.toast(a, "Нет браузера, чтобы открыть страницу оплаты");
        } catch (Throwable e) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Throwable ignore) {}
        }
    }

    /** Спросить итог оплаты: сервер узнаёт у ЮKassa (check), читаем свою строку. budget 0 — один раз. Один опрос за раз. */
    static void poll(final Activity a, final String me, final long id, final long budget) {
        if (!POLL_BUSY.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    while (true) {
                        if (!me.equals(IvaAccessPatch.me(a)) || !IvaNotifyPatch.loggedIn(a)) return;
                        String st = "", amt = "";
                        try {
                            IvaClientPatch.authFresh(a);
                            IvaClientPatch.apiCall(a, FN, "POST", checkBody(id), true);
                            String[] r = IvaClientPatch.apiCall(a, Q_ROW + id, "GET", null, true);
                            if (r != null && r.length > 1 && r[0] != null && r[0].startsWith("2")) {
                                JSONArray ja = new JSONArray(r[1].trim());
                                JSONObject j = ja.length() > 0 ? ja.optJSONObject(0) : null;
                                if (j != null) { st = j.optString("status", ""); amt = j.optString("amount", ""); }
                                else st = "gone";
                            }
                        } catch (Throwable ignore) {}
                        if ("succeeded".equals(st) || "canceled".equals(st) || "gone".equals(st)) {
                            IvaAccessPatch.prefs(a).edit().remove(waitKey(me)).apply();
                            if (!"gone".equals(st)) done(a, "succeeded".equals(st), amt);
                            return;
                        }
                        long el = System.currentTimeMillis() - t0;
                        if (el >= budget) {
                            long[] w = waitParse(IvaAccessPatch.prefs(a).getString(waitKey(me), ""));
                            if (w != null && System.currentTimeMillis() - w[1] > WAIT_KEEP_MS) IvaAccessPatch.prefs(a).edit().remove(waitKey(me)).apply();
                            return;
                        }
                        Thread.sleep(pollDelay(el));
                    }
                } catch (Throwable ignore) {
                } finally { POLL_BUSY.set(false); }
            }
        }).start();
    }

    static void done(final Activity a, final boolean ok, final String amt) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Double v = amt == null || amt.isEmpty() ? null : Double.valueOf(amt);
                    IvaStaffPatch.toast(a, ok ? "Оплата получена" + (v == null ? "" : ": " + rub(v)) + " — долг обновлён"
                        : "Оплата не прошла — деньги не списаны");
                    if (ok) IvaAccessPatch.feeCard(a);
                } catch (Throwable ignore) {}
            }
        });
    }

    // ════════════════════════════════ владелец площадки: «Комиссия» → «Онлайн-оплаты ЮKassa» ════════════════════════════════

    /** Из IvaChiefPatch.openFee (главный поток): свой блок на этом месте списка, грузится отдельно. */
    static void chief(final Activity a, LinearLayout list) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        list.addView(box, new LinearLayout.LayoutParams(-1, -2));
        box.addView(IvaChiefPatch.head(a, "ОНЛАЙН-ОПЛАТЫ ЮKASSA"));
        final TextView wait = IvaChiefPatch.text(a, "Загрузка…", 13f, IvaChiefPatch.color(a, "gray", 0xff9aa1b0), false);
        box.addView(wait);
        new Thread(new Runnable() {
            @Override public void run() {
                String[] r = null, o = null;
                try { r = IvaClientPatch.apiCall(a, Q_CHIEF, "GET", null, true); } catch (Throwable ignore) {}
                try { o = IvaClientPatch.apiCall(a, Q_OPEN, "GET", null, true); } catch (Throwable ignore) {}
                final String[] fr = r, fo = o;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { chiefPaint(a, box, wait, fr, fo); } catch (Throwable e) { wait.setText("Не удалось показать онлайн-оплаты"); }
                    }
                });
            }
        }).start();
    }

    static void chiefPaint(final Activity a, LinearLayout box, TextView wait, String[] r, String[] o) throws Exception {
        if (r != null && r.length > 1 && IvaChiefPatch.missing(r)) {
            box.removeView(wait);
            box.addView(IvaChiefPatch.note(a, "Не подключено", "Выполните в Supabase SQL 20261123_yookassa_fee.sql и добавьте ключи ЮKassa — "
                + "как в журнале 10.133. До этого админы платят переводом, как раньше.", ""));
            return;
        }
        if (!IvaChiefPatch.ok(r)) { wait.setText("Не удалось загрузить — проверьте интернет"); return; }
        box.removeView(wait);
        JSONArray rows = new JSONArray(r[1].trim());
        int open = 0;
        try { if (IvaChiefPatch.ok(o)) open = new JSONArray(o[1].trim()).length(); } catch (Throwable ignore) {}
        String[] stv = new String[rows.length()];
        double[] amv = new double[rows.length()];
        boolean[] rcv = new boolean[rows.length()];
        for (int i = 0; i < rows.length(); i++) {
            JSONObject j = rows.optJSONObject(i);
            stv[i] = j == null ? "" : j.optString("status", "");
            amv[i] = j == null ? 0 : j.optDouble("amount", 0);
            rcv[i] = j != null && j.optBoolean("receipt_done", false);
        }
        double[] t = chiefTotals(stv, amv, rcv);
        java.util.List<String[]> s = new java.util.ArrayList<String[]>();
        s.add(new String[]{"Оплачено онлайн", rub(t[0]), "good"});
        s.add(new String[]{"Чек не пробит", t[1] == 0 ? "нет" : (int) t[1] + " · " + rub(t[2]), t[1] > 0 ? "warn" : ""});
        s.add(new String[]{"Начаты, не оплачены", String.valueOf(open)});
        box.addView(IvaChiefPatch.kv(a, s));
        box.addView(IvaChiefPatch.note(a, "Чеки — в «Мой налог»",
            "ЮKassa чеки самозанятых не оформляет. На каждую оплату пробейте чек: услуга «Комиссия площадки «Ива»», сумма и дата"
                + " оплаты; если админ — ИП или организация, укажите его ИНН. Чек отправьте админу на почту, затем отметьте «Чек пробит».", ""));
        if (rows.length() == 0) { box.addView(IvaChiefPatch.hint(a, "Онлайн-оплат пока нет")); return; }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject j = rows.optJSONObject(i);
            if (j != null) box.addView(chiefRow(a, j));
        }
    }

    static View chiefRow(final Activity a, final JSONObject j) {
        int cText = IvaChiefPatch.color(a, "text", 0xfff2f4f8), cGray = IvaChiefPatch.color(a, "gray", 0xff9aa1b0);
        final int cOrange = IvaChiefPatch.color(a, "orange", 0xffff8f1f);
        LinearLayout c = IvaChiefPatch.card(a);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(IvaChiefPatch.text(a, rub(j.optDouble("amount", 0)), 16f, cText, true), new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(IvaChiefPatch.text(a, IvaCensorPatch.when(j.optString("paid_at", "")), 12f, cGray, false));
        c.addView(top);
        TextView who = IvaChiefPatch.text(a, j.optString("admin_email", ""), 14f, cText, false);
        who.setPadding(0, IvaChiefPatch.dp(a, 4), 0, 0);
        who.setTextIsSelectable(true);
        c.addView(who);
        String m = method(j.optString("method", ""));
        TextView yk = IvaChiefPatch.text(a, "ЮKassa № " + j.optString("yk_id", "") + (m.isEmpty() ? "" : " · " + m), 12f, cGray, false);
        yk.setPadding(0, IvaChiefPatch.dp(a, 2), 0, 0);
        yk.setTextIsSelectable(true);
        c.addView(yk);
        final TextView b = IvaPickupPatch.addButton(a, cOrange);
        b.setTextSize(14f);
        b.setPadding(IvaChiefPatch.dp(a, 12), IvaChiefPatch.dp(a, 8), IvaChiefPatch.dp(a, 12), IvaChiefPatch.dp(a, 8));
        final boolean[] on = {j.optBoolean("receipt_done", false)};
        final Runnable paint = new Runnable() {
            @Override public void run() {
                b.setText(on[0] ? "✓ Чек пробит — снять отметку" : "Чек пробит");
                b.setTextColor(on[0] ? 0xff3ec27a : cOrange);
            }
        };
        paint.run();
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = IvaChiefPatch.dp(a, 10);
        c.addView(b, bl);
        final long id = j.optLong("id", 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                final boolean want = !on[0];
                b.setEnabled(false);
                b.setAlpha(0.6f);
                new Thread(new Runnable() {
                    @Override public void run() {
                        boolean ok = false;
                        try {
                            String[] r = IvaClientPatch.apiCall(a, RECEIPT_RPC, "POST", "{\"p_id\":" + id + ",\"p_done\":" + want + "}", true);
                            ok = r != null && r.length > 1 && r[0] != null && r[0].startsWith("2") && r[1].trim().equals("true");
                        } catch (Throwable ignore) {}
                        final boolean fok = ok;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                b.setEnabled(true);
                                b.setAlpha(1f);
                                if (!fok) { IvaStaffPatch.toast(a, "Не получилось — проверьте интернет"); return; }
                                on[0] = want;
                                paint.run();
                            }
                        });
                    }
                }).start();
            }
        });
        return c;
    }
}

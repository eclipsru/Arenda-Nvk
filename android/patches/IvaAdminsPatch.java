package ru.prokatnvsk.app;

// 10.108 (просьба владельца 17.10.2026): создатель приостанавливает и удаляет админов; порядок в «Панели владельца».
//
//   1. «Панель владельца» (профиль создателя): убраны почта над заголовком (дубль — почта уже есть в профиле) и строки
//      статистики под заголовком («Инструментов: …», «Заявки: …» — те же цифры есть в «Управление → Сводка»).
//      Поле «email нового админа» и кнопка «Добавить админа» из панели убраны — они теперь на странице
//      «Все администраторы» (над списком). Кнопка «Все администраторы →» и заявки на оплату — как были.
//   2. «Все администраторы»: сверху — «Добавить админа» (почта + кнопка). Удалённые админы в списке не показываются,
//      «(отключён)» называется «(приостановлен)».
//   3. Карточка админа: внизу, сразу после «Тариф и лимит», — карточка «ДОСТУП»: «Приостановить» / «Возобновить»
//      и «Удалить из админов» (с подтверждением). Что при этом происходит — делает база (SQL 20261102_admin_manage.sql):
//      пока приостановлен — не публикует и не правит, его инструменты и пункты клиенты не видят, сотрудники без доступа;
//      «Возобновить» — всё как было. «Удалить» — пропадает из списка, инструменты скрыты, история заявок и комиссии
//      остаётся; снова добавить эту почту — вернётся, инструменты скрыты (включит сам).
//
// Только у создателя eclips.ru@mail.ru (как «Цензура» и «Управление»). Новых вставок в smali нет: вход —
// IvaNotifyPatch.onShowTab → onTab и слушатель «Панели владельца» в IvaChiefPatch. Сеть — только в фоновых потоках.
// Персональные данные не запрашиваются: только почта, роль, доступ и число скрытых инструментов.

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class IvaAdminsPatch {
    private IvaAdminsPatch() {}

    static final String PANEL_TITLE = "ПАНЕЛЬ ВЛАДЕЛЬЦА";
    static final String NATIVE_ADD = "Добавить админа";
    static final String TARIFF_TITLE = "ТАРИФ И ЛИМИТ";
    static final String ADD_TAG = "iva_adm_add", CARD_TAG = "iva_adm_access";
    static final String ADD_TITLE = "Добавить админа", ADD_HINT = "email нового админа";
    static final String ADD_SUB = "Админ увидит свой кабинет, когда войдёт в приложение под этой почтой";
    static final String CARD_TITLE = "ДОСТУП";
    static final String PAUSE = "Приостановить", RESUME = "Возобновить", REMOVE = "Удалить из админов";
    static final String PAUSE_SUB = "Пока приостановлен: не публикует и не правит, его инструменты и пункты клиенты не видят, "
        + "сотрудники без доступа. «Возобновить» — всё вернётся как было.";
    static final String REMOVE_SUB = "Пропадёт из списка, его инструменты скроются, сотрудники потеряют доступ. "
        + "Заявки, комиссия и оплаты останутся в истории. Вернуть — снова добавить эту почту (инструменты останутся скрытыми, включит сам).";
    static final String NO_DB = "Заработает после обновления базы (SQL 10.108)";
    static final String OFF_NATIVE = " (отключён)", OFF_OURS = " (приостановлен)";
    static final String RPC_ADD = "/rest/v1/rpc/iva_admin_add", RPC_PAUSE = "/rest/v1/rpc/iva_admin_pause",
        RPC_REMOVE = "/rest/v1/rpc/iva_admin_remove", RPC_STATE = "/rest/v1/rpc/iva_admin_state",
        RPC_REMOVED = "/rest/v1/rpc/iva_admins_removed";
    static final String NATIVE_INSERT = "/rest/v1/admins";   // как штатное «Добавить админа» — если SQL 10.108 ещё нет

    static Handler ui;   // не в статическом поле класса: проверки IvaPatchTest идут без Android

    static Handler ui() { if (ui == null) ui = new Handler(Looper.getMainLooper()); return ui; }
    static WeakReference<ViewGroup> listBox = new WeakReference<ViewGroup>(null), infoBox = new WeakReference<ViewGroup>(null);
    static volatile Set<String> removed = new HashSet<String>();
    static boolean cardQueued;

    // ═══════════════ чистая логика (проверяется IvaPatchTest) ═══════════════

    /** Ошибка в почте нового админа или "" — как проверяет база. */
    static String emailErr(String raw, String me) {
        String e = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty()) return "Введите почту нового админа";
        if (e.length() > 254 || !e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")) return "Похоже, в email ошибка (пример: ivan@mail.ru)";
        if (me != null && e.equals(me.trim().toLowerCase(Locale.ROOT))) return "Это ваша почта — вы уже владелец площадки";
        return "";
    }

    /** Текст ошибки ответа базы для человека ("" — успех). */
    static String msgOf(String code, String body) {
        String c = code == null ? "" : code, b = body == null ? "" : body;
        if (c.startsWith("2")) return "";
        if (b.contains("PGRST202") || b.contains("PGRST205") || b.contains("42883") || "404".equals(c)) return NO_DB;
        String m = message(b);
        if (!m.isEmpty() && hasCyr(m)) return m;
        if (b.contains("42501") || "401".equals(c) || "403".equals(c)) return "Нет права — войдите заново";
        return c.isEmpty() ? "Не получилось — нет связи с сервером" : "Не получилось (ответ " + c + ")";
    }

    /** Поле "message" ответа PostgREST (с \\uXXXX), без org.json — чтобы проверялось вне телефона. */
    static String message(String body) {
        if (body == null) return "";
        int i = body.indexOf("\"message\"");
        if (i < 0) return "";
        i = body.indexOf('"', body.indexOf(':', i) + 1);
        if (i < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = i + 1; k < body.length(); k++) {
            char ch = body.charAt(k);
            if (ch == '"') return sb.toString();
            if (ch == '\\' && k + 1 < body.length()) {
                char n = body.charAt(++k);
                if (n == 'u' && k + 4 < body.length()) {
                    try { sb.append((char) Integer.parseInt(body.substring(k + 1, k + 5), 16)); k += 4; } catch (Throwable e) { return ""; }
                } else if (n == 'n') sb.append(' ');
                else sb.append(n);
            } else sb.append(ch);
        }
        return "";
    }

    static boolean hasCyr(String s) {
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c >= 'А' && c <= 'я' || c == 'ё' || c == 'Ё') return true; }
        return false;
    }

    /** Какие строки «Панели владельца» спрятать: почта прямо над заголовком и текст прямо под ним. */
    static boolean[] panelHide(String[] texts, String me) {
        boolean[] out = new boolean[texts == null ? 0 : texts.length];
        String m = me == null ? "" : me.trim();
        for (int i = 0; i < out.length; i++) {
            if (!PANEL_TITLE.equals(texts[i] == null ? null : texts[i].trim())) continue;
            if (i > 0 && texts[i - 1] != null && !m.isEmpty() && texts[i - 1].trim().equalsIgnoreCase(m)) out[i - 1] = true;
            if (i + 1 < out.length && texts[i + 1] != null) out[i + 1] = true;
        }
        return out;
    }

    /** Заголовок строки списка: «Иван (отключён)» → «Иван (приостановлен)». */
    static String rowTitle(String s) {
        return s != null && s.endsWith(OFF_NATIVE) ? s.substring(0, s.length() - OFF_NATIVE.length()) + OFF_OURS : s;
    }

    /** Строка состояния карточки «Доступ». */
    static String stateText(boolean active, int hidden) {
        if (active) return "Сейчас: активен";
        return "Сейчас: приостановлен" + (hidden > 0 ? " · скрыто инструментов: " + hidden : "");
    }

    /** Массив почт из ответа iva_admins_removed (["a@x.ru", …]) — без org.json. */
    static Set<String> parseRemoved(String body) {
        Set<String> out = new HashSet<String>();
        if (body == null) return out;
        String b = body.trim();
        if (!b.startsWith("[")) return out;
        int i = 0;
        while ((i = b.indexOf('"', i)) >= 0) {
            int j = b.indexOf('"', i + 1);
            if (j < 0) break;
            String e = b.substring(i + 1, j).trim().toLowerCase(Locale.ROOT);
            if (!e.isEmpty()) out.add(e);
            i = j + 1;
        }
        return out;
    }

    // ═══════════════ вход ═══════════════

    static boolean chief(Activity a) { return IvaReviewPatch.chief(a); }

    static void onTab(Activity a, String tab) {
        if (!chief(a)) return;
        if ("profile".equals(tab)) { try { cleanPanelLater(a); } catch (Throwable ignore) {} }
        else if ("admList".equals(tab)) { try { listPage(a); } catch (Throwable ignore) {} }
        else if ("admInfo".equals(tab)) { try { infoPage(a); } catch (Throwable ignore) {} }
    }

    // ═══════════════ 1. «Панель владельца» ═══════════════

    /** Из слушателя chiefBox (IvaChiefPatch): штатная панель добавила строку. */
    static void panelChanged(final Activity a, final ViewGroup box) {
        if (!chief(a)) return;
        cleanPanel(a, box);
        ui().post(new Runnable() { @Override public void run() { try { cleanPanel(a, box); } catch (Throwable ignore) {} } });
    }

    static void cleanPanelLater(final Activity a) {
        final Object o = IvaClientPatch.get(a, "chiefBox");
        if (!(o instanceof ViewGroup)) return;
        cleanPanel(a, (ViewGroup) o);
        ui().post(new Runnable() { @Override public void run() { try { cleanPanel(a, (ViewGroup) o); } catch (Throwable ignore) {} } });
    }

    static void cleanPanel(Activity a, ViewGroup box) {
        int n = box.getChildCount();
        String[] texts = new String[n];
        for (int i = 0; i < n; i++) {
            View c = box.getChildAt(i);
            texts[i] = c instanceof TextView && !(c instanceof Button) ? String.valueOf(((TextView) c).getText()) : null;
        }
        boolean[] hide = panelHide(texts, String.valueOf(IvaClientPatch.get(a, "userEmail")));
        for (int i = 0; i < n; i++) if (hide[i]) box.getChildAt(i).setVisibility(View.GONE);
        Object f = IvaClientPatch.get(a, "chiefEmail");
        if (f instanceof View) ((View) f).setVisibility(View.GONE);
        hideNativeAdd(box);
    }

    static void hideNativeAdd(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof Button && NATIVE_ADD.contentEquals(((Button) c).getText())) c.setVisibility(View.GONE);
            else if (c instanceof ViewGroup) hideNativeAdd((ViewGroup) c);
        }
    }

    // ═══════════════ 2. «Все администраторы» ═══════════════

    static void listPage(final Activity a) {
        Object o = IvaClientPatch.get(a, "admListBox");
        if (!(o instanceof ViewGroup)) return;
        final ViewGroup box = (ViewGroup) o;
        if (box.getParent() instanceof LinearLayout) {
            LinearLayout parent = (LinearLayout) box.getParent();
            if (parent.findViewWithTag(ADD_TAG) == null) parent.addView(addBlock(a), parent.indexOfChild(box));
        }
        if (listBox.get() != box) {
            box.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { try { fixRow(child); } catch (Throwable ignore) {} }
                @Override public void onChildViewRemoved(View parent, View child) {}
            });
            listBox = new WeakReference<ViewGroup>(box);
        }
        fixRows(box);
        loadRemoved(a);
    }

    static void fixRows(ViewGroup box) { for (int i = 0; i < box.getChildCount(); i++) fixRow(box.getChildAt(i)); }

    /** Строка штатного списка: [колонка: имя, почта, …][долг]. */
    static void fixRow(View row) {
        if (!(row instanceof ViewGroup) || ((ViewGroup) row).getChildCount() == 0) return;
        View col = ((ViewGroup) row).getChildAt(0);
        if (!(col instanceof ViewGroup) || ((ViewGroup) col).getChildCount() < 2) return;
        View t = ((ViewGroup) col).getChildAt(0), e = ((ViewGroup) col).getChildAt(1);
        if (t instanceof TextView) {
            String s = String.valueOf(((TextView) t).getText()), r = rowTitle(s);
            if (!r.equals(s)) ((TextView) t).setText(r);
        }
        if (e instanceof TextView) {
            String em = String.valueOf(((TextView) e).getText()).trim().toLowerCase(Locale.ROOT);
            row.setVisibility(removed.contains(em) ? View.GONE : View.VISIBLE);
        }
    }

    static void loadRemoved(final Activity a) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, RPC_REMOVED, "POST", "{}", true);
                    if (r == null || !r[0].startsWith("2")) return;
                    removed = parseRemoved(r.length > 1 ? r[1] : "");
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() { try { ViewGroup b = listBox.get(); if (b != null) fixRows(b); } catch (Throwable ignore) {} }
                    });
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    static View addBlock(final Activity a) {
        int cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8), cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        final int cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        LinearLayout c = IvaChiefPatch.card(a);
        c.setTag(ADD_TAG);
        c.addView(IvaChiefPatch.text(a, ADD_TITLE, 15f, cText, true));
        TextView sub = IvaChiefPatch.text(a, ADD_SUB, 12f, cGray, false);
        sub.setPadding(0, IvaChiefPatch.dp(a, 2), 0, 0);
        c.addView(sub);
        final EditText f = IvaStaffPatch.field(a, ADD_HINT, "", cText);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = IvaChiefPatch.dp(a, 10);
        c.addView(f, fl);
        final TextView err = IvaChiefPatch.text(a, "", 13f, 0xffff5a5f, false);
        err.setVisibility(View.GONE);
        err.setPadding(0, IvaChiefPatch.dp(a, 6), 0, 0);
        c.addView(err);
        final TextView b = IvaPickupPatch.addButton(a, cOrange);
        b.setText(ADD_TITLE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = IvaChiefPatch.dp(a, 10);
        c.addView(b, bl);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaPickupPatch.hideKb(a, v);
                final String em = f.getText().toString().trim().toLowerCase(Locale.ROOT);
                String e = emailErr(em, String.valueOf(IvaClientPatch.get(a, "userEmail")));
                IvaPickupPatch.setErr(err, e);
                if (!e.isEmpty()) return;
                busy(b, true);
                new Thread(new Runnable() {
                    @Override public void run() {
                        String fe;
                        boolean restored = false;
                        try {
                            String[] r = IvaClientPatch.apiCall(a, RPC_ADD, "POST", new JSONObject().put("p_email", em).toString(), true);
                            fe = r == null ? msgOf("", "") : msgOf(r[0], r.length > 1 ? r[1] : "");
                            if (fe.isEmpty()) restored = new JSONObject(r[1]).optBoolean("restored", false);
                            else if (NO_DB.equals(fe)) {
                                // база ещё без SQL 10.108 — как штатная кнопка
                                String body = new JSONObject().put("email", em).put("role", "admin").put("name", "").toString();
                                String[] n = IvaClientPatch.apiCall(a, NATIVE_INSERT, "POST", body, true);
                                fe = n == null ? msgOf("", "") : msgOf(n[0], n.length > 1 ? n[1] : "");
                                if (fe.equals(NO_DB)) fe = "Не получилось";
                            }
                        } catch (Throwable x) { fe = msgOf("", ""); }
                        final String ferr = fe;
                        final boolean back = restored;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                try {
                                    busy(b, false);
                                    if (!ferr.isEmpty()) { IvaPickupPatch.setErr(err, ferr); return; }
                                    f.setText("");
                                    IvaStaffPatch.toast(a, back ? "Админ возвращён: " + em + " — его инструменты скрыты, включит сам"
                                                                : "Админ добавлен: " + em);
                                    removed.remove(em);
                                    IvaChatPatch.callNative(a, "buildAdmList");
                                    loadRemoved(a);
                                } catch (Throwable ignore) {}
                            }
                        });
                    }
                }).start();
            }
        });
        return c;
    }

    static void busy(View b, boolean on) { b.setEnabled(!on); b.setAlpha(on ? 0.6f : 1f); }

    // ═══════════════ 3. Карточка админа — «ДОСТУП» ═══════════════

    static void infoPage(final Activity a) {
        Object o = IvaClientPatch.get(a, "admInfoBox");
        if (!(o instanceof ViewGroup)) return;
        final ViewGroup box = (ViewGroup) o;
        if (infoBox.get() != box) {
            box.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    if (CARD_TAG.equals(child.getTag())) return;
                    try { fixStatus(child); } catch (Throwable ignore) {}
                    queueCard(a, box);
                }
                @Override public void onChildViewRemoved(View parent, View child) {}
            });
            infoBox = new WeakReference<ViewGroup>(box);
        }
        queueCard(a, box);
    }

    /** «Статус: отключён» (штатная карточка) → «Статус: приостановлен». */
    static void fixStatus(View v) {
        if (v instanceof TextView) {
            String s = String.valueOf(((TextView) v).getText());
            if ("Статус: отключён".equals(s)) ((TextView) v).setText("Статус: приостановлен");
        } else if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) fixStatus(((ViewGroup) v).getChildAt(i));
        }
    }

    static void queueCard(final Activity a, final ViewGroup box) {
        if (cardQueued) return;
        cardQueued = true;
        ui().post(new Runnable() {
            @Override public void run() {
                cardQueued = false;
                try { placeCard(a, box); } catch (Throwable ignore) {}
            }
        });
    }

    static boolean isTariff(View v) {
        if (!(v instanceof ViewGroup) || ((ViewGroup) v).getChildCount() == 0) return false;
        View t = ((ViewGroup) v).getChildAt(0);
        return t instanceof TextView && TARIFF_TITLE.contentEquals(((TextView) t).getText());
    }

    static void placeCard(Activity a, ViewGroup box) {
        if (!chief(a)) return;
        String em = String.valueOf(IvaClientPatch.get(a, "admInfoEmail")).trim().toLowerCase(Locale.ROOT);
        String me = String.valueOf(IvaClientPatch.get(a, "userEmail")).trim().toLowerCase(Locale.ROOT);
        View old = box.findViewWithTag(CARD_TAG);
        // пока штатная страница грузится (одна строка «Загрузка…») или это сам создатель — карточки нет
        int tariff = -1;
        for (int i = 0; i < box.getChildCount(); i++) if (isTariff(box.getChildAt(i))) tariff = i;
        if (em.isEmpty() || "null".equals(em) || em.equals(me) || tariff < 0) {
            if (old != null) box.removeView(old);
            return;
        }
        if (old != null && em.equals(((View) old).getContentDescription() == null ? "" : String.valueOf(old.getContentDescription()))) {
            if (box.indexOfChild(old) != tariff + 1) { box.removeView(old); box.addView(old, Math.min(tariff + 1, box.getChildCount())); }
            return;
        }
        if (old != null) box.removeView(old);
        View card = accessCard(a, em);
        int at = -1;
        for (int i = 0; i < box.getChildCount(); i++) if (isTariff(box.getChildAt(i))) at = i + 1;
        box.addView(card, Math.min(at, box.getChildCount()));
    }

    static View accessCard(final Activity a, final String em) {
        final int cText = IvaStaffPatch.color(a, "text", 0xfff2f4f8), cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        final int cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        final LinearLayout c = IvaChiefPatch.card(a);
        ((LinearLayout.LayoutParams) c.getLayoutParams()).topMargin = IvaChiefPatch.dp(a, 10);
        c.setTag(CARD_TAG);
        c.setContentDescription(em);
        c.addView(IvaChiefPatch.text(a, CARD_TITLE, 14f, cOrange, true));
        final TextView state = IvaChiefPatch.text(a, "Загрузка…", 14f, cText, false);
        state.setPadding(0, IvaChiefPatch.dp(a, 6), 0, 0);
        c.addView(state);
        final LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        c.addView(body);
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                JSONObject j = null;
                try {
                    String[] r = IvaClientPatch.apiCall(a, RPC_STATE, "POST", new JSONObject().put("p_email", em).toString(), true);
                    e = r == null ? msgOf("", "") : msgOf(r[0], r.length > 1 ? r[1] : "");
                    if (e.isEmpty() && r[1] != null && r[1].trim().startsWith("{")) j = new JSONObject(r[1]);
                    else if (e.isEmpty()) e = "Админ не найден";
                } catch (Throwable x) { e = msgOf("", ""); }
                final String fe = e;
                final JSONObject fj = j;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!fe.isEmpty()) { state.setText(fe); state.setTextColor(cGray); return; }
                            if ("chief".equals(fj.optString("role")) || fj.optBoolean("removed")) {
                                if (c.getParent() instanceof ViewGroup) ((ViewGroup) c.getParent()).removeView(c);
                                return;
                            }
                            fill(a, c, state, body, em, fj.optBoolean("active", true), fj.optInt("hidden", 0), cText, cGray, cOrange);
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
        return c;
    }

    static void fill(final Activity a, final LinearLayout card, TextView state, LinearLayout body, final String em,
                     final boolean active, int hidden, int cText, int cGray, int cOrange) {
        state.setText(stateText(active, hidden));
        state.setTextColor(active ? cText : cOrange);
        body.removeAllViews();
        TextView sub = IvaChiefPatch.text(a, PAUSE_SUB, 12f, cGray, false);
        sub.setPadding(0, IvaChiefPatch.dp(a, 4), 0, 0);
        body.addView(sub);
        final TextView err = IvaChiefPatch.text(a, "", 13f, 0xffff5a5f, false);
        err.setVisibility(View.GONE);
        err.setPadding(0, IvaChiefPatch.dp(a, 6), 0, 0);
        final TextView main = IvaPickupPatch.addButton(a, cOrange);
        main.setText(active ? PAUSE : RESUME);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = IvaChiefPatch.dp(a, 10);
        body.addView(main, ml);
        final TextView rm = IvaSearchPatch.chip(a, REMOVE, false, false);
        rm.setTextColor(0xffff5a5f);
        rm.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = IvaChiefPatch.dp(a, 10);
        body.addView(rm, rl);
        body.addView(err);
        main.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!active) { act(a, main, err, RPC_PAUSE, em, false, "Админ возобновлён: " + em); return; }
                IvaClientPatch.styledChoice(a, "Приостановить " + em + "?", PAUSE_SUB, new String[]{PAUSE},
                    new IvaClientPatch.Choice() {
                        @Override public void pick(int i) { if (i == 0) act(a, main, err, RPC_PAUSE, em, true, "Админ приостановлен: " + em); }
                    });
            }
        });
        rm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IvaClientPatch.styledChoice(a, "Удалить " + em + " из админов?", REMOVE_SUB, new String[]{"Удалить"},
                    new IvaClientPatch.Choice() {
                        @Override public void pick(int i) { if (i == 0) act(a, rm, err, RPC_REMOVE, em, null, "Админ удалён: " + em); }
                    });
            }
        });
    }

    /** pause: true / false — iva_admin_pause; null — iva_admin_remove. */
    static void act(final Activity a, final View btn, final TextView err, final String path, final String em, final Boolean pause,
                    final String done) {
        busy(btn, true);
        new Thread(new Runnable() {
            @Override public void run() {
                String e;
                try {
                    JSONObject b = new JSONObject().put("p_email", em);
                    if (pause != null) b.put("p_pause", pause.booleanValue());
                    String[] r = IvaClientPatch.apiCall(a, path, "POST", b.toString(), true);
                    e = r == null ? msgOf("", "") : msgOf(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable x) { e = msgOf("", ""); }
                final String fe = e;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            busy(btn, false);
                            if (!fe.isEmpty()) { IvaPickupPatch.setErr(err, fe); return; }
                            IvaStaffPatch.toast(a, done);
                            if (pause == null) {
                                removed.add(em);
                                IvaChatPatch.callTab(a, "admList");
                            } else IvaChatPatch.callNative(a, "buildAdminPage");
                        } catch (Throwable ignore) {}
                    }
                });
            }
        }).start();
    }
}

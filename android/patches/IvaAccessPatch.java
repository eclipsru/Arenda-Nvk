package ru.prokatnvsk.app;

// 10.110 (просьба владельца 17.10.2026): убрали админа или сотрудника — у него сразу остаются только возможности
// клиента, без перезахода в приложение.
//
//   1. Админ. Штатный код узнаёт «админ ли я» один раз при входе (checkSelfAdmin: почта есть в списке admins —
//      признак selfAdmin) и больше не проверяет; удалённых (removed_at, 10.108) тоже считает админами — строка
//      в admins остаётся. Теперь каждые 30 секунд (наш опрос IvaNotifyPatch.tick) и при смене раздела (не чаще раза
//      в 8 секунд) спрашиваем базу: есть ли моя строка и не удалена ли. Удалили — selfAdmin = false, закрываем
//      открытые страницы кабинета, со страниц админа («Мои инструменты», «Добавить», «Заявки» админа …) — в профиль,
//      и сообщение «Вы больше не администратор». Добавили снова — кабинет возвращается так же, без перезахода.
//      Ответ запоминается на телефоне: после перезапуска удалённый админ сразу клиент, даже пока нет связи.
//      «Приостановить» (10.108) кабинет не забирает — как и было: публиковать и править не даёт база.
//   2. Сотрудник. Пока человек сотрудник или приглашён — каждые 30 секунд спрашиваем iva_staff_me (раньше —
//      только при открытии профиля, раз в минуту). Владелец убрал (или админ-владелец удалён/приостановлен) —
//      закрываем страницы пункта («Мой пункт», «Инструменты пункта», «Заявки пункта», «Чат пункта»), со штатной
//      формы инструмента — в профиль, сообщение «Вы больше не сотрудник». Новое приглашение появляется само.
//
// Создателя (eclips.ru@mail.ru) не трогаем. Решения только по ответу базы 2xx: нет связи, ошибка, нет столбца —
// ничего не меняем. Новых вставок в smali нет: вход — IvaNotifyPatch.onShowTab / tick и IvaClientPatch.onProfile.
// Сеть — только в фоновых потоках. Персональные данные не запрашиваются: только своя почта и отметка удаления.
//
// 10.112 (просьба владельца 17.10.2026): моментально. IvaRealtimePatch держит соединение Supabase Realtime и по событию
// базы зовёт poke — та же проверка сразу. Новое приглашение — окном поверх любого экрана («Принять» / «Отказаться» /
// «Позже», один раз на приглашение; карточка в «Кабинете» остаётся). Назначили админом — окно «Вы назначены
// администратором». Владелец поменял права сотрудника — страницы пункта открываются заново. Запасная проверка
// приглашения — раз в 30 секунд (было раз в минуту).

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class IvaAccessPatch {
    private IvaAccessPatch() {}

    static final String ADMIN_PATH = "/rest/v1/admins?select=email,removed_at&email=ilike.";
    static final String ADMIN_PATH_OLD = "/rest/v1/admins?select=email&email=ilike.";   // нет SQL 20261102
    static final long TAB_EVERY_MS = 8000L, STAFF_IDLE_MS = 30000L;   // 10.112: запасная проверка приглашения — каждые 30 с (основное — Realtime)

    static final String ADMIN_GONE_TITLE = "Вы больше не администратор";
    static final String ADMIN_GONE_TEXT = "Создатель площадки убрал вас из администраторов. Кабинет проката закрыт — "
        + "остались возможности клиента: поиск, избранное, заявки и чат.";
    static final String ADMIN_NEW_TITLE = "Вы назначены администратором";
    static final String ADMIN_NEW_TEXT = "Создатель площадки открыл вам кабинет проката: свои инструменты, заявки, пункты выдачи "
        + "и сотрудники. Всё — в «Кабинете».";
    static final String RIGHTS_TOAST = "Владелец проката изменил ваши права";
    static final String INVITE_TITLE = "Приглашение в сотрудники", INVITE_YES = "Принять", INVITE_NO = "Отказаться", INVITE_LATER = "Позже";
    static final String STAFF_GONE_TITLE = "Вы больше не сотрудник";
    static final String OK = "Понятно";

    /** Разделы штатного кода только для админа: при снятии — в профиль. */
    static final String[] ADMIN_TABS = {"admitems", "addtool", "admhub", "tooledit", "adminData", "admArch",
        "admList", "admInfo", "admRentals", "admMoney", "admPays"};
    /** Разделы, которые выглядят у админа и клиента по-разному: перерисовать на месте. */
    static final String[] REDRAW_TABS = {"profile", "orders", "chat"};
    /** Штатная форма инструмента у сотрудника (10.105). */
    static final String[] STAFF_TABS = {"addtool", "admhub", "tooledit"};

    // ───────────── чистая логика (IvaPatchTest) ─────────────

    /** Ответ admins по своей почте: TRUE — админ, FALSE — нет строки или удалён, null — ответ непонятен (ничего не менять). */
    static Boolean adminFromRows(String body, String me) {
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[")) return null;
        List<String[]> rows = new ArrayList<String[]>();
        try {
            JSONArray arr = new JSONArray(t);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) rows.add(new String[]{o.optString("email", ""), o.isNull("removed_at") ? null : o.optString("removed_at", null)});
            }
        } catch (Throwable e) { return null; }
        return adminFromPairs(rows, me);
    }

    /** Строки {почта, removed_at}: своя строка без отметки удаления — админ; удалена или нет строки — нет. */
    static Boolean adminFromPairs(List<String[]> rows, String me) {
        String em = me == null ? "" : me.trim();
        if (rows == null || em.isEmpty()) return null;
        for (String[] r : rows) {
            if (r == null || r.length == 0 || r[0] == null || !r[0].trim().equalsIgnoreCase(em)) continue;
            return removedEmpty(r.length > 1 ? r[1] : null);
        }
        return Boolean.FALSE;
    }

    static Boolean removedEmpty(String removedAt) {
        String r = removedAt == null ? "" : removedAt.trim();
        return r.isEmpty() || "null".equals(r) ? Boolean.TRUE : Boolean.FALSE;
    }

    /** 400 из-за отсутствующего столбца removed_at (SQL 20261102 ещё не применён) — спросить без него. */
    static boolean missingColumn(String code, String body) {
        String b = body == null ? "" : body;
        return code != null && code.startsWith("4") && b.contains("removed_at")
            && (b.contains("42703") || b.contains("PGRST204") || b.contains("column"));
    }

    static boolean in(String[] list, String t) {
        if (t == null) return false;
        for (String s : list) if (s.equals(t)) return true;
        return false;
    }

    static boolean adminTab(String t) { return in(ADMIN_TABS, t); }
    static boolean redrawTab(String t) { return in(REDRAW_TABS, t); }
    static boolean staffTab(String t) { return in(STAFF_TABS, t); }

    /** Что делать при смене признака админа: -1 — снять, 1 — вернуть, 0 — ничего. */
    static int adminChange(boolean chief, boolean ui, Boolean db) {
        if (chief || db == null) return 0;
        if (ui && !db) return -1;
        if (!ui && db) return 1;
        return 0;
    }

    static final int STAFF_SAME = 0, STAFF_LOST = 1, STAFF_INVITE_GONE = 2, STAFF_NEW = 3;

    static boolean staffOn(String status) { return "active".equals(status) || "invited".equals(status); }

    /** Смена статуса сотрудника между прошлым и новым ответом iva_staff_me ("" — не сотрудник). */
    static int staffChange(String was, String now) {
        String w = staffOn(was) ? was : "", n = staffOn(now) ? now : "";
        if (w.equals(n)) return STAFF_SAME;
        if ("active".equals(w) && n.isEmpty()) return STAFF_LOST;
        if ("invited".equals(w) && n.isEmpty()) return STAFF_INVITE_GONE;
        return STAFF_NEW;
    }

    /** Спрашивать iva_staff_me сейчас: сотрудник или приглашён — каждый раз, иначе раз в минуту. */
    static boolean staffDue(String cachedStatus, long last, long now) {
        return staffOn(cachedStatus) || last <= 0 || now - last >= STAFF_IDLE_MS;
    }

    static String staffGoneText(String company) {
        String c = company == null ? "" : company.trim();
        return (c.isEmpty() ? "Доступ сотрудника закрыт" : "Доступ сотрудника к прокату «" + c + "» закрыт")
            + " — владелец убрал вас из сотрудников. Остались возможности клиента: поиск, избранное, заявки и чат.";
    }

    // ───────────── открытые страницы (IvaChiefPatch.page, заявки) — закрыть разом ─────────────

    static final List<WeakReference<Dialog>> pages = new ArrayList<WeakReference<Dialog>>();

    static void track(Dialog d) {
        if (d == null) return;
        synchronized (pages) {
            for (int i = pages.size() - 1; i >= 0; i--) {
                Dialog x = pages.get(i).get();
                if (x == null || !x.isShowing()) pages.remove(i);
            }
            pages.add(new WeakReference<Dialog>(d));
        }
    }

    static void closePages() {
        List<Dialog> open = new ArrayList<Dialog>();
        synchronized (pages) {
            for (WeakReference<Dialog> r : pages) { Dialog d = r.get(); if (d != null) open.add(d); }
            pages.clear();
        }
        for (int i = open.size() - 1; i >= 0; i--) try { if (open.get(i).isShowing()) open.get(i).dismiss(); } catch (Throwable ignore) {}
    }

    // ───────────── вход ─────────────

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_access", 0); }
    static String adminKey(String me) { return "admin_" + me; }

    static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean();
    static volatile boolean again;
    static volatile long tabAt, staffAt;
    static volatile String staffFor = "";

    static String me(Activity a) { return IvaPickupPatch.me(a); }

    /** Из IvaNotifyPatch.onShowTab: сменили раздел — проверить (не чаще раза в 8 секунд). */
    static void onTab(final Activity a, String tab) {
        if (!IvaNotifyPatch.loggedIn(a) || IvaReviewPatch.chief(a)) return;
        final String me = me(a);
        long now = System.currentTimeMillis();
        if (me.isEmpty() || BUSY.get() || now - tabAt < TAB_EVERY_MS) return;
        tabAt = now;
        new Thread(new Runnable() {
            @Override public void run() { try { check(a, me); } catch (Throwable ignore) {} }
        }).start();
    }

    /** 10.112: из IvaRealtimePatch — база сообщила, что роль поменялась (или только подключились): проверить сразу,
     *  без паузы в 8 секунд и без минутной паузы сотрудника. Идёт проверка — повторить сразу после неё. */
    static void poke(final Activity a) {
        if (a == null || a.isFinishing() || !IvaNotifyPatch.loggedIn(a) || IvaReviewPatch.chief(a)) return;
        final String me = me(a);
        if (me.isEmpty()) return;
        staffAt = 0;
        again = true;
        if (BUSY.get()) return;
        new Thread(new Runnable() {
            @Override public void run() { try { check(a, me); } catch (Throwable ignore) {} }
        }).start();
    }

    /** Из IvaClientPatch.onProfile (после штатного renderProfile): удалённый по прошлому ответу базы — сразу клиент.
     *  Нужно при запуске: штатный checkSelfAdmin видит строку удалённого и ставит selfAdmin = true. */
    static boolean onProfile(Activity a) {
        if (!IvaNotifyPatch.loggedIn(a) || IvaReviewPatch.chief(a) || !IvaNotifyPatch.isAdmin(a)) return false;
        String me = me(a);
        if (me.isEmpty() || !prefs(a).contains(adminKey(me)) || prefs(a).getBoolean(adminKey(me), true)) return false;
        setAdmin(a, false);
        nativeCall(a, "renderProfile");
        return true;
    }

    /** Фоновый поток (tick / onTab): спросить базу и применить. */
    static void check(final Activity a, final String me) throws Exception {
        if (me == null || me.isEmpty() || IvaReviewPatch.chief(a) || !BUSY.compareAndSet(false, true)) return;
        try {
          do {
            again = false;
            Boolean db = askAdmin(a, me);
            if (db != null) {
                // 10.112: «назначили» объявляем, только если раньше база говорила «не админ» (при запуске штатная
                // проверка ещё идёт — уже назначенному админу окно не показываем)
                final boolean wasClient = prefs(a).contains(adminKey(me)) && !prefs(a).getBoolean(adminKey(me), true);
                prefs(a).edit().putBoolean(adminKey(me), db).apply();
                final boolean isAdm = db;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { try { applyAdmin(a, me, isAdm, wasClient); } catch (Throwable ignore) {} }
                });
            }
            boolean admin = db != null ? db : IvaNotifyPatch.isAdmin(a);
            if (!admin) checkStaff(a, me);
            if (again) staffAt = 0;                                       // пока проверяли — пришло новое событие
          } while (again && me.equals(me(a)));
        } finally { BUSY.set(false); }
    }

    static Boolean askAdmin(Activity a, String me) throws Exception {
        String q = IvaReviewPatch.enc(me);
        String[] r = IvaClientPatch.apiCall(a, ADMIN_PATH + q, "GET", null, true);
        if (r != null && !r[0].startsWith("2") && missingColumn(r[0], r.length > 1 ? r[1] : ""))
            r = IvaClientPatch.apiCall(a, ADMIN_PATH_OLD + q, "GET", null, true);
        if (r == null || !r[0].startsWith("2")) return null;
        return adminFromRows(r.length > 1 ? r[1] : "", me);
    }

    static void applyAdmin(Activity a, String me, boolean db, boolean announce) {
        if (!me.equals(me(a)) || !IvaNotifyPatch.loggedIn(a)) return;
        int ch = adminChange(IvaReviewPatch.chief(a), IvaNotifyPatch.isAdmin(a), db);
        if (ch < 0) {
            setAdmin(a, false);
            closePages();
            Object t = IvaClientPatch.get(a, "curTab");
            String tab = t instanceof String ? (String) t : "";
            try { Object o = IvaClientPatch.get(a, "navOrdersTxt"); if (o instanceof TextView) ((TextView) o).setText("Заявки"); } catch (Throwable ignore) {}
            nativeCall(a, "renderProfile");
            if (adminTab(tab)) IvaNotifyPatch.tab(a, "profile");
            else if (redrawTab(tab)) IvaNotifyPatch.tab(a, tab);
            info(a, ADMIN_GONE_TITLE, ADMIN_GONE_TEXT);
        } else if (ch > 0) {
            setAdmin(a, true);
            Object t = IvaClientPatch.get(a, "curTab");
            String tab = t instanceof String ? (String) t : "";
            nativeCall(a, "renderProfile");
            if (redrawTab(tab)) IvaNotifyPatch.tab(a, tab);
            if (announce) { closePages(); info(a, ADMIN_NEW_TITLE, ADMIN_NEW_TEXT); }
        }
    }

    static void setAdmin(Activity a, boolean on) { IvaClientPatch.set(a, "selfAdmin", on); }

    static void nativeCall(Activity a, String name) {
        try {
            Method m = a.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(a);
        } catch (Throwable ignore) {}
    }

    // ───────────── сотрудник ─────────────

    static void checkStaff(final Activity a, final String me) throws Exception {
        final String key = "me_" + me;
        final String oldBody = IvaStaffPatch.prefs(a).getString(key, "");
        IvaStaffPatch.Me was = null;
        try { was = IvaStaffPatch.parseMe(oldBody); } catch (Throwable ignore) {}
        long now = System.currentTimeMillis();
        if (!staffDue(was == null ? "" : was.status, me.equals(staffFor) ? staffAt : 0, now)) return;
        String[] r = IvaClientPatch.apiCall(a, IvaStaffPatch.ME_RPC, "POST", "{}", true);
        if (r == null || !r[0].startsWith("2")) {
            if (r != null && IvaStaffPatch.NO_DB.equals(IvaStaffPatch.dbError(r[0], r.length > 1 ? r[1] : ""))) { staffAt = now; staffFor = me; }
            return;
        }
        staffAt = now;
        staffFor = me;
        final String body = r.length > 1 && r[1] != null ? r[1] : "[]";
        IvaStaffPatch.Me m = null;
        try { m = IvaStaffPatch.parseMe(body); } catch (Throwable ignore) {}
        IvaStaffPatch.prefs(a).edit().putString(key, body).apply();
        IvaStaffPatch.meAt = now;
        IvaStaffPatch.meFor = me;
        final int ch = staffChange(was == null ? "" : was.status, m == null ? "" : m.status);
        final boolean rights = ch == STAFF_SAME && was != null && m != null && "active".equals(m.status)
            && rightsChanged(was.r, was.self, m.r, m.self);
        final boolean popup = m != null && "invited".equals(m.status) && !inviteShown(a, m.id);
        if (ch == STAFF_SAME && body.equals(oldBody) && !popup) return;
        final IvaStaffPatch.Me fm = m;
        final String company = was == null ? "" : was.company;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { applyStaff(a, me, ch, fm, company, rights); } catch (Throwable ignore) {}
                try { if (popup) showInvite(a, me, fm); } catch (Throwable ignore) {}
            }
        });
    }

    /** 10.112: владелец поменял права сотрудника (или «выдаёт свои заявки сам»). */
    static boolean rightsChanged(boolean[] r1, boolean s1, boolean[] r2, boolean s2) {
        return s1 != s2 || !java.util.Arrays.equals(r1, r2);
    }

    static String inviteKey(String id) { return "invite_" + (id == null ? "" : id); }
    static boolean inviteShown(Activity a, String id) { return prefs(a).getBoolean(inviteKey(id), false); }

    static void applyStaff(Activity a, String me, int ch, IvaStaffPatch.Me m, String company, boolean rights) {
        if (!me.equals(me(a)) || !IvaNotifyPatch.loggedIn(a) || IvaNotifyPatch.isAdmin(a)) return;
        Object t = IvaClientPatch.get(a, "curTab");
        String tab = t instanceof String ? (String) t : "";
        if (ch == STAFF_LOST) {
            IvaStaffToolsPatch.close(IvaStaffToolsPatch.toolsPage);
            IvaStaffToolsPatch.close(IvaStaffToolsPatch.minePage);
            closePages();
            if (staffTab(tab)) { IvaNotifyPatch.tab(a, "profile"); tab = "profile"; }
        }
        if (rights) {                                                     // 10.112: страницы пункта — с новыми правами
            boolean open = isOpen(IvaStaffToolsPatch.toolsPage) || isOpen(IvaStaffToolsPatch.minePage);
            IvaStaffToolsPatch.close(IvaStaffToolsPatch.toolsPage);
            IvaStaffToolsPatch.close(IvaStaffToolsPatch.minePage);
            closePages();
            if (staffTab(tab) && !IvaStaffToolsPatch.want(m.status, m.r)) { IvaNotifyPatch.tab(a, "profile"); tab = "profile"; }
            IvaStaffPatch.toast(a, open ? RIGHTS_TOAST + " — откройте «Мой пункт» заново" : RIGHTS_TOAST);
        }
        if ("profile".equals(tab)) IvaStaffPatch.drawMe(a, m);
        if (ch == STAFF_LOST) info(a, STAFF_GONE_TITLE, staffGoneText(company));
    }

    // ───────────── окна (как styledChoice: карточка, кнопки-«пузыри») ─────────────

    static void info(Activity a, String title, String text) {
        if (a.isFinishing()) return;
        final Dialog d = new Dialog(a);
        LinearLayout card = card(a, d, title, text);
        bubble(a, card, OK, true, 14).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { d.dismiss(); } catch (Throwable ignore) {} }
        });
        show(a, d, card, true);
    }

    static WeakReference<Dialog> invitePopup;

    /** 10.112: новое приглашение — сразу окном поверх любого экрана: «Принять» / «Отказаться» / «Позже».
     *  Показывается один раз на приглашение; карточка в «Кабинете» остаётся, пока не ответили. */
    static void showInvite(final Activity a, final String me, final IvaStaffPatch.Me m) {
        if (m == null || a.isFinishing() || !me.equals(me(a)) || !IvaNotifyPatch.loggedIn(a) || IvaNotifyPatch.isAdmin(a)) return;
        Dialog old = invitePopup == null ? null : invitePopup.get();
        if (old != null && old.isShowing()) return;
        prefs(a).edit().putBoolean(inviteKey(m.id), true).apply();
        final Dialog d = new Dialog(a);
        LinearLayout card = card(a, d, INVITE_TITLE, IvaStaffPatch.inviteText(m.company, IvaStaffPatch.names(m.points))
            + "\n\nПрава: " + IvaStaffPatch.rightsText(m.r));
        final TextView yes = bubble(a, card, INVITE_YES, true, 16);
        final TextView no = bubble(a, card, INVITE_NO, false, 8);
        TextView later = bubble(a, card, INVITE_LATER, false, 8);
        yes.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { d.dismiss(); } catch (Throwable ignore) {}
                IvaStaffPatch.answer(a, m, true, yes);
            }
        });
        no.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { d.dismiss(); } catch (Throwable ignore) {}
                IvaClientPatch.styledChoice(a, "Отказаться от приглашения?", "Прокат увидит, что вы отказались.", new String[]{INVITE_NO},
                    new IvaClientPatch.Choice() { @Override public void pick(int i) { if (i == 0) IvaStaffPatch.answer(a, m, false, no); } });
            }
        });
        later.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { d.dismiss(); } catch (Throwable ignore) {}
                IvaStaffPatch.toast(a, "Приглашение — в «Кабинете»");
            }
        });
        invitePopup = new WeakReference<Dialog>(d);
        show(a, d, card, false);
    }

    static LinearLayout card(Activity a, Dialog d, String title, String text) {
        int gray = IvaClientPatch.navColor(a, "gray", 0xff9aa1b0), white = IvaClientPatch.navColor(a, "text", 0xfff2f4f8);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(IvaClientPatch.choiceBg(a, "bg", 0xff101217, IvaClientPatch.CHOICE_CARD_RADIUS_DP));
        card.setPadding(IvaClientPatch.hubDp(a, 18), IvaClientPatch.hubDp(a, 18), IvaClientPatch.hubDp(a, 18), IvaClientPatch.hubDp(a, 14));
        TextView h = new TextView(a);
        h.setText(title);
        h.setTextColor(white);
        h.setTextSize(17f);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setGravity(Gravity.CENTER);
        card.addView(h, new LinearLayout.LayoutParams(-1, -2));
        TextView s = new TextView(a);
        s.setText(text);
        s.setTextColor(gray);
        s.setTextSize(13f);
        s.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = IvaClientPatch.hubDp(a, 6);
        card.addView(s, sl);
        return card;
    }

    static TextView bubble(Activity a, LinearLayout card, String label, boolean main, int topDp) {
        TextView b = new TextView(a);
        b.setText(label);
        b.setTextColor(main ? IvaClientPatch.navColor(a, "orange", 0xffff8f1f) : IvaClientPatch.navColor(a, "gray", 0xff9aa1b0));
        b.setTextSize(15f);
        b.setTypeface(null, main ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(IvaClientPatch.choiceBg(a, "card", 0xff1a1d24, IvaClientPatch.CHOICE_RADIUS_DP));
        b.setClickable(true);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, IvaClientPatch.hubDp(a, IvaClientPatch.CHOICE_BUBBLE_DP));
        bl.topMargin = IvaClientPatch.hubDp(a, topDp);
        card.addView(b, bl);
        return b;
    }

    static void show(Activity a, Dialog d, LinearLayout card, boolean cancelable) {
        d.setContentView(card);
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(cancelable);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        try { d.show(); } catch (Throwable e) { return; }
        if (w != null) {                                       // ширина — после show (как styledChoice)
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screen - IvaClientPatch.hubDp(a, 48), IvaClientPatch.hubDp(a, IvaClientPatch.CHOICE_MAX_DP)),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    static boolean isOpen(WeakReference<Dialog> r) {
        Dialog d = r == null ? null : r.get();
        return d != null && d.isShowing();
    }
}

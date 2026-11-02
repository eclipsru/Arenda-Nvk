package ru.prokatnvsk.app;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

/**
 * 10.122 (просьба владельца 18.10.2026): клиент пишет в прокат — переписку видят и владелец, и сотрудники с правом
 * чата. Над полем ввода — подсказка, чтобы не ответить вдвоём: «Иван (сотрудник) открыл переписку / печатает…»,
 * «Отвечает Иван», после ответа — «Ответил Иван в 14:05»; кнопка «Отвечу я». Второй всё равно может написать —
 * приложение сначала предупредит. Клиент подсказку не видит. Без SQL 10.122 подсказки просто нет.
 */
public class IvaChatWhoPatch {
    static final String MARK_RPC = "/rest/v1/rpc/iva_chat_mark", WHO_RPC = "/rest/v1/rpc/iva_chat_who";
    static final long TICK_MS = 5000L, TYPING_EVERY_MS = 3000L;
    static final String CLAIM = "Отвечу я", UNCLAIM = "Не отвечаю";

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static String md5(String email) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest((email == null ? "" : email.trim().toLowerCase(Locale.ROOT)).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return b.toString();
        } catch (Throwable e) { return ""; }
    }

    static boolean isMd5(String s) { return s != null && s.matches("[0-9a-f]{32}"); }

    /** Переписка: «t:<инструмент>:<md5 клиента>» или «p:<пункт>:<md5>» (tool — uuid или ключ пункта «pt:…»); иначе "". */
    static String thread(String tool, String peerMd5) {
        String t = tool == null ? "" : tool.trim();
        if (!isMd5(peerMd5)) return "";
        if (IvaProkatPatch.isKey(t)) return "p:" + IvaProkatPatch.idOf(t).toLowerCase(Locale.ROOT) + ":" + peerMd5;
        if (IvaToolPointPatch.uuid(t)) return "t:" + t.toLowerCase(Locale.ROOT) + ":" + peerMd5;
        return "";
    }

    static String body(String thread, String state) {
        if (thread == null || thread.isEmpty()) return null;
        return state == null ? "{\"p_thread\":\"" + thread + "\"}" : "{\"p_thread\":\"" + thread + "\",\"p_state\":\"" + state + "\"}";
    }

    static final class Who {
        String text = "", busyName = "";
        boolean show, busy;
    }

    static String nameOf(JSONObject o) {
        String n = o == null ? "" : o.optString("name", "").trim();
        if ("owner".equals(o == null ? "" : o.optString("role", ""))) return "Владелец";
        return (n.isEmpty() ? "Сотрудник" : n) + " (сотрудник)";
    }

    /** Ответ iva_chat_who → строка подсказки. mine — я нажал «Отвечу я». */
    static Who parse(String json, boolean mine, long now, TimeZone tz) {
        Who w = new Who();
        JSONObject o = null;
        try { String t = json == null ? "" : json.trim(); if (t.startsWith("{")) o = new JSONObject(t); } catch (Throwable ignore) {}
        JSONArray others = o == null ? null : o.optJSONArray("others");
        JSONObject pick = null;
        String kind = "";
        for (String k : new String[]{"typing", "claim", "open"}) {
            for (int i = 0; others != null && i < others.length() && pick == null; i++) {
                JSONObject x = others.optJSONObject(i);
                if (x != null && x.optBoolean(k, false)) { pick = x; kind = k; }
            }
            if (pick != null) break;
        }
        int more = others == null ? 0 : Math.max(0, others.length() - 1);
        String tail = more > 0 ? " · ещё " + more : "";
        if (pick != null) {
            String n = nameOf(pick);
            w.show = true;
            if ("typing".equals(kind)) { w.text = n + " печатает…"; w.busy = true; w.busyName = n; }
            else if ("claim".equals(kind)) { w.text = "Отвечает " + n; w.busy = true; w.busyName = n; }
            else w.text = n + " открыл(а) переписку";
            w.text += tail;
            return w;
        }
        JSONObject last = o == null ? null : o.optJSONObject("last");
        if (mine) { w.show = true; w.text = "Вы отвечаете — остальные видят «Отвечает…»"; return w; }
        if (last != null && !last.optBoolean("me", false)) {
            long ms = IvaChatPatch.parseIso(last.optString("at", ""));
            String n = "Владелец".equals(last.optString("name", "")) ? "владелец" : last.optString("name", "").trim();
            String when = ms <= 0 ? "" : (IvaChatPatch.dayIndex(ms, tz) == IvaChatPatch.dayIndex(now, tz) ? " в " : " " + IvaChatPatch.dayLabel(ms, now, tz) + " в ")
                + IvaChatPatch.hm(ms, tz);
            w.show = true;
            w.text = "Ответил(а) " + (n.isEmpty() ? "сотрудник" : n) + (ms > 0 ? when : "");
        }
        return w;
    }

    static String warnText(String name) { return "Сейчас отвечает " + name + ". Всё равно отправить?"; }

    // ───────────── подсказка над полем ввода ─────────────

    interface Alive { boolean ok(); }

    static final class Box {
        Activity a;
        LinearLayout bar;
        TextView text, btn;
        String thread = "";
        boolean claimed, noDb, busyTick;
        Who last;
        long typedAt;
        int gen;
        Alive alive;
    }

    static Box attach(Activity a, LinearLayout parent, int index, Alive alive) {
        final Box b = new Box();
        b.a = a;
        b.alive = alive;
        int cGray = IvaStaffPatch.color(a, "gray", 0xff9aa1b0);
        b.bar = new LinearLayout(a);
        b.bar.setOrientation(LinearLayout.HORIZONTAL);
        b.bar.setGravity(Gravity.CENTER_VERTICAL);
        b.bar.setPadding(dp(a, 14), dp(a, 6), dp(a, 14), dp(a, 2));
        b.bar.setVisibility(View.GONE);
        b.text = IvaChiefPatch.text(a, "", 13f, cGray, false);
        b.bar.addView(b.text, new LinearLayout.LayoutParams(0, -2, 1f));
        b.btn = IvaSearchPatch.chip(a, CLAIM, false, false);
        b.btn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.leftMargin = dp(a, 8);
        b.bar.addView(b.btn, bl);
        b.btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.claimed = !b.claimed;
                mark(b, b.claimed ? "claim" : "unclaim");
                paint(b);
                fetch(b);
            }
        });
        parent.addView(b.bar, Math.max(0, Math.min(index, parent.getChildCount())), new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    /** Открыли другую переписку (или закрыли — ""). */
    static void setThread(final Box b, String thread) {
        if (b == null) return;
        String t = thread == null ? "" : thread;
        if (t.equals(b.thread)) return;
        if (!b.thread.isEmpty()) mark(b, "close");
        b.thread = t;
        b.claimed = false;
        b.last = null;
        b.gen++;
        paint(b);
        if (t.isEmpty() || b.noDb) return;
        mark(b, "open");
        fetch(b);
        tick(b, b.gen);
    }

    static void tick(final Box b, final int g) {
        IvaChiefPatch.h().postDelayed(new Runnable() {
            @Override public void run() {
                if (g != b.gen || b.thread.isEmpty()) return;
                boolean ok = false;
                try { ok = b.alive == null || b.alive.ok(); } catch (Throwable ignore) {}
                if (!ok) { setThread(b, ""); return; }
                mark(b, "open");
                fetch(b);
                tick(b, g);
            }
        }, TICK_MS);
    }

    static void mark(final Box b, String state) {
        final String body = body(b.thread, state);
        if (body == null || b.noDb) return;
        final Activity a = b.a;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] r = IvaClientPatch.apiCall(a, MARK_RPC, "POST", body, true);
                    if (r != null && ("404".equals(r[0]) || (r.length > 1 && r[1] != null && r[1].contains("PGRST202")))) b.noDb = true;
                } catch (Throwable ignore) {}
            }
        }).start();
    }

    static void fetch(final Box b) {
        final String body = body(b.thread, null);
        if (body == null || b.noDb || b.busyTick) return;
        b.busyTick = true;
        final String th = b.thread;
        final Activity a = b.a;
        new Thread(new Runnable() {
            @Override public void run() {
                String got = null;
                try {
                    String[] r = IvaClientPatch.apiCall(a, WHO_RPC, "POST", body, true);
                    if (r != null && r[0] != null && r[0].startsWith("2")) got = r.length > 1 ? r[1] : "";
                    else if (r != null && ("404".equals(r[0]) || (r.length > 1 && r[1] != null && r[1].contains("PGRST202")))) b.noDb = true;
                } catch (Throwable ignore) {}
                final String fg = got;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        b.busyTick = false;
                        if (fg == null || !th.equals(b.thread)) return;
                        b.last = parse(fg, b.claimed, System.currentTimeMillis(), TimeZone.getDefault());
                        paint(b);
                    }
                });
            }
        }).start();
    }

    static void paint(Box b) {
        Who w = b.last;
        boolean show = !b.thread.isEmpty() && !b.noDb && (b.claimed || (w != null && w.show));
        int cGray = IvaStaffPatch.color(b.a, "gray", 0xff9aa1b0), cOrange = IvaStaffPatch.color(b.a, "orange", 0xffff8f1f);
        b.bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        String t = w != null && w.show ? w.text : "Вы отвечаете — остальные видят «Отвечает…»";
        if (b.claimed && (w == null || !w.busy)) t = "Вы отвечаете — остальные видят «Отвечает…»";
        b.text.setText(t);
        b.text.setTextColor(w != null && w.busy ? cOrange : cGray);
        b.btn.setText(b.claimed ? UNCLAIM : CLAIM);
    }

    /** Поле ввода: печатает — отметка не чаще раза в 3 с. */
    static void watch(final Box b, EditText input) {
        if (b == null || input == null || input.getTag(android.R.id.hint) != null) return;
        input.setTag(android.R.id.hint, Boolean.TRUE);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int bf, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (s == null || s.length() == 0 || b.thread.isEmpty()) return;
                long now = System.currentTimeMillis();
                if (now - b.typedAt < TYPING_EVERY_MS) return;
                b.typedAt = now;
                mark(b, "typing");
            }
        });
    }

    /** Перед отправкой: сейчас отвечает другой — спросить. true — спросили (send вызовется после «Отправить»). */
    static boolean confirmSend(Box b, final Runnable send) {
        if (b == null || b.claimed || b.last == null || !b.last.busy) return false;
        IvaClientPatch.styledChoice(b.a, "Сейчас отвечает другой", warnText(b.last.busyName), new String[]{"Всё равно отправить"},
            new IvaClientPatch.Choice() { @Override public void pick(int i) { if (i == 0) send.run(); } });
        return true;
    }
}

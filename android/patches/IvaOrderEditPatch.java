package ru.prokatnvsk.app;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 10.122 (просьба владельца 18.10.2026): клиент сам отменяет или меняет заявку — без комиссии и без лазейки.
 *  • «Отменить заявку» — пока ничего не выдано, с причиной; статус «Отменена клиентом», комиссии нет.
 *    Отменить может только клиент из своего аккаунта (проверяет база).
 *  • Через 2 дня клиента спрашивают «Вы в итоге взяли инструмент в этом прокате?»; «Да» — заявка закрывается
 *    с комиссией, создатель видит это в «Управлении» → «Отмены заявок» (журнал и доля отмен по прокатам).
 *  • «Изменить заявку» (до выдачи): срок, убрать инструмент, самовывоз вместо доставки, комментарий. Прокат
 *    подтверждает («Принять» / «Отклонить»); до решения видит прежний и новый вариант.
 * Без SQL 10.122 кнопки отвечают «Нужен SQL 10.122», остальное работает как в 10.121.
 */
public class IvaOrderEditPatch {
    static final String CANCEL_RPC = "/rest/v1/rpc/iva_order_cancel", EDIT_RPC = "/rest/v1/rpc/iva_order_edit",
        DECIDE_RPC = "/rest/v1/rpc/iva_order_edit_decide", FOLLOW_RPC = "/rest/v1/rpc/iva_order_followups",
        ANSWER_RPC = "/rest/v1/rpc/iva_order_followup_answer", STATS_RPC = "/rest/v1/rpc/iva_cancel_stats",
        JOURNAL_RPC = "/rest/v1/rpc/iva_cancel_journal";
    static final String CANCELLED = "Отменена клиентом";
    static final String[] REASONS = {"Передумал(а)", "Нашёл(ла) в другом месте", "Изменились планы", "Ошибся(лась) в заявке"};
    static final long FOLLOW_EVERY_MS = 15 * 60000L;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        String t = s == null ? "" : s;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static boolean num(String s) { return s != null && s.trim().matches("[0-9]{1,18}"); }

    static String reason(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }

    /** {"p_order":12,"p_reason":"…"}; номер не число или причина короче 2 знаков — null. */
    static String cancelBody(String order, String why) {
        String r = reason(why);
        if (!num(order) || r.length() < 2) return null;
        if (r.length() > 300) r = r.substring(0, 300);
        return "{\"p_order\":" + order.trim() + ",\"p_reason\":" + esc(r) + "}";
    }

    /** items — {id, days}; пусто, неверный номер или срок вне 1–365 — null. comment null — не менять. */
    static String editBody(String part, List<String[]> items, String comment, boolean pickup) {
        if (!num(part) || items == null || items.isEmpty()) return null;
        StringBuilder b = new StringBuilder("{\"p_part\":").append(part.trim()).append(",\"p_items\":[");
        for (int i = 0; i < items.size(); i++) {
            String[] it = items.get(i);
            if (it == null || it.length < 2 || it[0] == null || it[0].trim().isEmpty() || !num(it[1])) return null;
            int d = Integer.parseInt(it[1].trim());
            if (d < 1 || d > 365) return null;
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(esc(it[0].trim())).append(",\"days\":").append(d).append('}');
        }
        b.append("],\"p_comment\":").append(comment == null ? "null" : esc(comment.trim()));
        return b.append(",\"p_pickup\":").append(pickup).append('}').toString();
    }

    static String decideBody(String change, boolean ok) { return num(change) ? "{\"p_change\":" + change.trim() + ",\"p_ok\":" + ok + "}" : null; }

    static String answerBody(String id, String answer) {
        return num(id) && ("took".equals(answer) || "not".equals(answer) || "later".equals(answer))
            ? "{\"p_id\":" + id.trim() + ",\"p_answer\":\"" + answer + "\"}" : null;
    }

    /** Отменить можно, пока есть новая часть и ничего не выдано. */
    static boolean canCancel(List<String> statuses) {
        if (statuses == null) return false;
        boolean open = false;
        for (String s : statuses) {
            String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            if ("rented".equals(t) || "closed".equals(t)) return false;
            if ("new".equals(t) || t.isEmpty()) open = true;
        }
        return open;
    }

    static String decideDone(String res) {
        String r = res == null ? "" : res.replace("\"", "").trim();
        if ("accepted".equals(r)) return "Изменение принято — заявка обновлена";
        if ("rejected".equals(r)) return "Изменение отклонено — заявка осталась прежней";
        if ("issued".equals(r)) return "Инструмент уже выдан — изменение не применено";
        return "Готово";
    }

    static String answerDone(String res) {
        String r = res == null ? "" : res.replace("\"", "").trim();
        if ("took".equals(r)) return "Спасибо! Заявка отмечена как выполненная";
        if ("not".equals(r)) return "Спасибо за ответ";
        if ("later".equals(r)) return "Хорошо, спросим завтра";
        return "Вопрос уже не актуален";
    }

    /** Доля отмен: «3 из 20 (15 %)». */
    static String share(int cancelled, int parts) {
        if (parts <= 0) return "заявок нет";
        return cancelled + " из " + parts + " (" + Math.round(cancelled * 100.0 / parts) + " %)";
    }

    /** Подозрительно много отмен: от 3 штук и от 30 %. */
    static boolean alarm(int cancelled, int parts) { return parts > 0 && cancelled >= 3 && cancelled * 100 >= parts * 30; }

    static String errText(String code, String body, String fallback) {
        String b = body == null ? "" : body;
        if ("404".equals(code) || b.contains("PGRST202")) return "Нужен SQL 10.122 — владелец применяет его в Supabase";
        return IvaMyToolsPatch.errText(code, body, fallback);
    }

    // ───────────── вид: разница «было → стало» ─────────────

    static String daysOf(JSONObject it) { int d = it == null ? 0 : it.optInt("days", 0); return d > 0 ? IvaOrderViewPatch.days(d) : "—"; }

    static String s(JSONObject o, String k) { String v = o == null || o.isNull(k) ? "" : o.optString(k, ""); return v == null || "null".equals(v) ? "" : v.trim(); }

    static List<String> diff(JSONObject before, JSONObject after) {
        List<String> out = new ArrayList<String>();
        if (before == null || after == null) return out;
        JSONArray bi = before.optJSONArray("items"), ai = after.optJSONArray("items");
        for (int i = 0; bi != null && i < bi.length(); i++) {
            JSONObject b = bi.optJSONObject(i);
            if (b == null) continue;
            JSONObject a = null;
            for (int j = 0; ai != null && j < ai.length(); j++) {
                JSONObject x = ai.optJSONObject(j);
                if (x != null && s(x, "id").equals(s(b, "id"))) a = x;
            }
            String n = s(b, "name").isEmpty() ? "Инструмент" : s(b, "name");
            if (a == null) out.add("Убрать: " + n);
            else if (b.optInt("days", 0) != a.optInt("days", 0)) out.add(n + ": " + daysOf(b) + " → " + daysOf(a));
        }
        if (!s(before, "get_method").equals(s(after, "get_method")))
            out.add("Получение: " + (s(before, "get_method").isEmpty() ? "—" : s(before, "get_method")) + " → " + s(after, "get_method"));
        if (!s(before, "comment").equals(s(after, "comment")))
            out.add("Комментарий: " + (s(after, "comment").isEmpty() ? "убрать" : "«" + s(after, "comment") + "»"));
        return out;
    }

    // ───────────── подробности заявки: клиент и прокат ─────────────

    static int c(Activity a, String n, int def) { return IvaOrderPatch.color(a, n, def); }

    static TextView tx(Activity a, String t, float sp, int color, boolean bold) { return IvaChiefPatch.text(a, t, sp, color, bold); }

    static int dp(Activity a, float v) { return IvaChiefPatch.dp(a, v); }

    static void closeSheet() { try { if (IvaOrderPatch.ordDialog != null) IvaOrderPatch.ordDialog.dismiss(); } catch (Throwable ignore) {} }

    /** Из IvaOrderViewPatch.sheet: клиенту — отмена/изменение и их состояние; прокату — решение по изменению. */
    static void block(final Activity a, LinearLayout body, final JSONObject o, boolean adm) {
        JSONArray ps = o == null ? null : o.optJSONArray("parts");
        if (ps == null || ps.length() == 0) return;
        LinearLayout card = IvaChiefPatch.card(a);
        int n = 0;
        int cGray = c(a, "gray", 0xff9aa1b0), cOrange = c(a, "orange", 0xffff8f1f), cText = c(a, "text", 0xfff2f4f8);
        List<String> sts = new ArrayList<String>();
        final List<JSONObject> editable = new ArrayList<JSONObject>();
        for (int i = 0; i < ps.length(); i++) {
            final JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            String st = s(p, "status").toLowerCase(Locale.ROOT);
            sts.add(st);
            String co = s(p, "company");
            String pre = ps.length() > 1 && !co.isEmpty() ? "«" + co + "»: " : "";
            JSONObject cn = p.optJSONObject("cancel");
            if ("cancelled".equals(st)) {
                card.addView(tx(a, pre + (adm ? "Клиент отменил заявку" : CANCELLED), 15f, cText, true));
                if (cn != null && !s(cn, "reason").isEmpty()) card.addView(tx(a, "Причина: " + s(cn, "reason"), 13f, cGray, false));
                if (cn != null && "took".equals(s(cn, "answer"))) card.addView(tx(a, "Клиент ответил, что всё же взял инструмент", 13f, cGray, false));
                if (!adm) card.addView(tx(a, "Комиссия не начисляется", 12f, cGray, false));
                n++;
                continue;
            }
            JSONObject ch = p.optJSONObject("change");
            String cs = s(ch, "state");
            if (ch != null && !"void".equals(cs)) {
                List<String> lines = diff(ch.optJSONObject("before"), ch.optJSONObject("after"));
                String head = "pending".equals(cs) ? (adm ? "Клиент просит изменить заявку" : "Изменение ждёт подтверждения проката")
                    : "accepted".equals(cs) ? (adm ? "Вы приняли изменение заявки" : "Прокат принял изменение")
                    : (adm ? "Вы отклонили изменение заявки" : "Прокат отклонил изменение — действует прежний вариант");
                if (n > 0) card.addView(IvaOrderViewPatch.divider(a));
                card.addView(tx(a, pre + head, 15f, "pending".equals(cs) ? cOrange : cText, true));
                for (String l : lines) {
                    TextView t = tx(a, "• " + l, 13f, cText, false);
                    t.setPadding(0, dp(a, 4), 0, 0);
                    card.addView(t);
                }
                if ("pending".equals(cs) && adm) {
                    card.addView(tx(a, "Пока вы не решили, действует прежний вариант заявки.", 12f, cGray, false));
                    card.addView(decideRow(a, s(ch, "id"), new Runnable() { @Override public void run() { closeSheet(); admReload(a); } }));
                }
                n++;
            }
            if (!adm && "new".equals(st) && p.optBoolean("editable", false)) editable.add(p);
        }
        if (!adm && canCancel(sts)) {
            if (n > 0) card.addView(IvaOrderViewPatch.divider(a));
            card.addView(tx(a, "Пока инструмент не выдан, заявку можно изменить или отменить — без комиссии.", 13f, cGray, false));
            if (!editable.isEmpty()) {
                TextView ed = new TextView(a);
                ed.setText("Изменить заявку");
                ed.setGravity(Gravity.CENTER);
                ed.setTextSize(15f);
                IvaPatch.accentButton(a, ed);
                LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, dp(a, 46));
                el.topMargin = dp(a, 10);
                card.addView(ed, el);
                ed.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (editable.size() == 1) { openEdit(a, o, editable.get(0)); return; }
                        String[] names = new String[editable.size()];
                        for (int i = 0; i < names.length; i++) names[i] = s(editable.get(i), "company").isEmpty() ? "Прокат " + (i + 1) : "«" + s(editable.get(i), "company") + "»";
                        IvaClientPatch.styledChoice(a, "Какую часть изменить?", "В заявке инструменты из нескольких прокатов — каждый подтверждает своё.", names,
                            new IvaClientPatch.Choice() { @Override public void pick(int i) { if (i >= 0 && i < editable.size()) openEdit(a, o, editable.get(i)); } });
                    }
                });
            }
            TextView cancel = new TextView(a);
            cancel.setText("Отменить заявку");
            cancel.setGravity(Gravity.CENTER);
            cancel.setTextSize(15f);
            cancel.setTextColor(c(a, "red", 0xffff8a80));
            cancel.setBackground(IvaClientPatch.choiceBg(a, "bg", 0xff101217, IvaClientPatch.CHOICE_RADIUS_DP));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, dp(a, 46));
            cl.topMargin = dp(a, 8);
            card.addView(cancel, cl);
            cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openCancel(a, s(o, "order_id")); } });
            n++;
        }
        if (n == 0) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 14);
        body.addView(card, lp);
    }

    /** Строка «Изменение ждёт решения» на карточке в списке (владелец, сотрудник). */
    static TextView pendingLine(Activity a, JSONObject p) {
        JSONObject ch = p == null ? null : p.optJSONObject("change");
        if (ch == null || !"pending".equals(s(ch, "state")) || !"new".equals(s(p, "status"))) return null;
        TextView t = tx(a, "Клиент просит изменить заявку — откройте", 13f, c(a, "orange", 0xffff8f1f), true);
        t.setPadding(0, dp(a, 8), 0, 0);
        return t;
    }

    /** Сотрудник («Заявки пункта»): изменение, ждущее решения, — «было → стало» и кнопки. null — нечего показывать. */
    static View staffBlock(final Activity a, String partId, final Runnable reload) {
        JSONObject o = IvaOrderViewPatch.part(partId);
        JSONArray ps = o == null ? null : o.optJSONArray("parts");
        JSONObject p = ps == null || ps.length() == 0 ? null : ps.optJSONObject(0);
        JSONObject ch = p == null ? null : p.optJSONObject("change");
        if (ch == null || !"pending".equals(s(ch, "state")) || !"new".equals(s(p, "status"))) return null;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(a, 8), 0, 0);
        box.addView(tx(a, "Клиент просит изменить заявку", 14f, c(a, "orange", 0xffff8f1f), true));
        for (String l : diff(ch.optJSONObject("before"), ch.optJSONObject("after"))) box.addView(tx(a, "• " + l, 13f, c(a, "text", 0xfff2f4f8), false));
        box.addView(decideRow(a, s(ch, "id"), new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { IvaOrderViewPatch.load(a, "staff"); } catch (Throwable ignore) {}
                        a.runOnUiThread(reload);
                    }
                }).start();
            }
        }));
        return box;
    }

    static View decideRow(final Activity a, final String changeId, final Runnable done) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(a, 10), 0, 0);
        final TextView yes = IvaPickupPatch.addButton(a, c(a, "orange", 0xffff8f1f));
        yes.setText("Принять");
        yes.setGravity(Gravity.CENTER);
        final TextView no = IvaSearchPatch.chip(a, "Отклонить", false, false);
        no.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1f);
        l2.leftMargin = dp(a, 8);
        row.addView(yes, l1);
        row.addView(no, l2);
        View.OnClickListener cl = new View.OnClickListener() {
            @Override public void onClick(final View v) {
                final boolean ok = v == yes;
                final String body = decideBody(changeId, ok);
                if (body == null) return;
                yes.setEnabled(false); no.setEnabled(false);
                yes.setAlpha(0.6f); no.setAlpha(0.6f);
                new Thread(new Runnable() {
                    @Override public void run() {
                        String msg;
                        boolean good = false;
                        try {
                            String[] r = IvaClientPatch.apiCall(a, DECIDE_RPC, "POST", body, true);
                            if (r != null && r[0] != null && r[0].startsWith("2")) { msg = decideDone(r.length > 1 ? r[1] : ""); good = true; }
                            else msg = r == null ? "Нет связи — попробуйте ещё раз" : errText(r[0], r.length > 1 ? r[1] : "", "Не получилось");
                        } catch (Throwable e) { msg = "Нет связи — попробуйте ещё раз"; }
                        final String fm = msg;
                        final boolean fg = good;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                IvaStaffPatch.toast(a, fm);
                                yes.setEnabled(true); no.setEnabled(true);
                                yes.setAlpha(1f); no.setAlpha(1f);
                                if (fg && done != null) try { done.run(); } catch (Throwable ignore) {}
                            }
                        });
                    }
                }).start();
            }
        };
        yes.setOnClickListener(cl);
        no.setOnClickListener(cl);
        return row;
    }

    /** Владелец решил — свежие карточки заявок и перерисовка штатного списка. */
    static void admReload(final Activity a) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { IvaOrderViewPatch.admAt = 0; IvaOrderViewPatch.load(a, "admin"); } catch (Throwable ignore) {}
                a.runOnUiThread(new Runnable() { @Override public void run() { try { IvaOrderPointPatch.render(a); } catch (Throwable ignore) {} } });
            }
        }).start();
    }

    static EditText field(Activity a, String hint, String value) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextColor(c(a, "text", 0xfff2f4f8));
        e.setHintTextColor(c(a, "gray2", 0xff6b7080));
        e.setTextSize(15f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMaxLines(5);
        Drawable fb = IvaSearchPatch.drawable(a, "bg_field");
        if (fb != null) e.setBackground(fb);
        e.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        return e;
    }

    static TextView label(Activity a, String t) {
        TextView l = tx(a, t, 13f, c(a, "gray", 0xff9aa1b0), false);
        l.setPadding(0, dp(a, 14), 0, dp(a, 6));
        return l;
    }

    // ── «Отменить заявку» ──
    static void openCancel(final Activity a, final String orderId) {
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, "Отмена заявки", "Пока инструмент не выдан. Комиссия с проката не берётся.", false);
        pg.list.addView(label(a, "Почему отменяете? Прокат увидит причину"));
        final EditText why = field(a, "Причина", "");
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.VERTICAL);
        for (final String r : REASONS) {
            TextView ch = IvaSearchPatch.chip(a, r, false, false);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.bottomMargin = dp(a, 6);
            chips.addView(ch, l);
            ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { why.setText(r); why.setSelection(why.getText().length()); } });
        }
        pg.list.addView(chips);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(a, 6);
        pg.list.addView(why, wl);
        TextView note = tx(a, "Через пару дней приложение спросит, взяли ли вы всё же инструмент в этом прокате.", 12f, c(a, "gray", 0xff9aa1b0), false);
        note.setPadding(0, dp(a, 10), 0, 0);
        pg.list.addView(note);
        final TextView go = IvaPickupPatch.addButton(a, c(a, "orange", 0xffff8f1f));
        go.setText("Отменить заявку");
        go.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(a, 48));
        gl.topMargin = dp(a, 16);
        pg.list.addView(go, gl);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String body = cancelBody(orderId, why.getText().toString());
                if (body == null) { IvaStaffPatch.toast(a, "Укажите причину отмены"); return; }
                if (IvaCensorPatch.stopFields(a, why)) return;                       // 24.10.2026: прокат увидит причину — без мата
                go.setEnabled(false);
                go.setAlpha(0.6f);
                new Thread(new Runnable() {
                    @Override public void run() {
                        String msg = null;
                        try {
                            String[] r = IvaClientPatch.apiCall(a, CANCEL_RPC, "POST", body, true);
                            if (r == null || r[0] == null || !r[0].startsWith("2"))
                                msg = r == null ? "Нет связи — попробуйте ещё раз" : errText(r[0], r.length > 1 ? r[1] : "", "Не получилось отменить");
                        } catch (Throwable e) { msg = "Нет связи — попробуйте ещё раз"; }
                        final String fm = msg;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                go.setEnabled(true);
                                go.setAlpha(1f);
                                if (fm != null) { IvaStaffPatch.toast(a, fm); return; }
                                IvaStaffPatch.toast(a, "Заявка отменена. Прокат увидит причину");
                                try { pg.d.dismiss(); } catch (Throwable ignore) {}
                                closeSheet();
                                try { IvaOrderPatch.ordFetch(a); } catch (Throwable ignore) {}
                            }
                        });
                    }
                }).start();
            }
        });
    }

    // ── «Изменить заявку» ──
    static void openEdit(final Activity a, final JSONObject o, final JSONObject p) {
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, "Изменить заявку",
            "Прокат подтвердит изменение. До этого действует прежний вариант заявки.", false);
        final JSONArray items = p.optJSONArray("items");
        if (items == null || items.length() == 0) { pg.list.addView(IvaChiefPatch.hint(a, "Эту заявку изменить в приложении нельзя — напишите в прокат")); return; }
        final int orderDays = IvaOrderViewPatch.intOf(o.opt("days"), 1);
        final List<String> ids = new ArrayList<String>();
        final int[] days = new int[items.length()];
        final boolean[] drop = new boolean[items.length()];
        int cText = c(a, "text", 0xfff2f4f8), cGray = c(a, "gray", 0xff9aa1b0);
        pg.list.addView(label(a, "Инструменты и срок"));
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            final int k = i;
            ids.add(s(it, "id"));
            days[i] = Math.max(1, Math.min(365, it == null ? orderDays : it.optInt("days", Math.max(1, orderDays))));
            LinearLayout card = IvaChiefPatch.card(a);
            final TextView name = tx(a, s(it, "name").isEmpty() ? "Инструмент" : s(it, "name"), 15f, cText, true);
            card.addView(name);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 8), 0, 0);
            final TextView minus = IvaSearchPatch.chip(a, "  −  ", false, false);
            final TextView val = tx(a, IvaOrderViewPatch.days(days[i]), 15f, cText, true);
            val.setGravity(Gravity.CENTER);
            final TextView plus = IvaSearchPatch.chip(a, "  +  ", false, false);
            final TextView rm = IvaSearchPatch.chip(a, "Убрать", false, false);
            row.addView(minus);
            row.addView(val, new LinearLayout.LayoutParams(dp(a, 96), -2));
            row.addView(plus);
            View sp = new View(a);
            row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(rm);
            card.addView(row);
            View.OnClickListener step = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (drop[k]) return;
                    days[k] = Math.max(1, Math.min(365, days[k] + (v == plus ? 1 : -1)));
                    val.setText(IvaOrderViewPatch.days(days[k]));
                }
            };
            minus.setOnClickListener(step);
            plus.setOnClickListener(step);
            final int fGray = cGray, fText = cText;
            rm.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    drop[k] = !drop[k];
                    rm.setText(drop[k] ? "Вернуть" : "Убрать");
                    name.setTextColor(drop[k] ? fGray : fText);
                    val.setText(drop[k] ? "убран" : IvaOrderViewPatch.days(days[k]));
                }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.bottomMargin = dp(a, 8);
            pg.list.addView(card, cl);
        }
        String gm = s(o, "get_method");
        final boolean[] pickup = {false};
        if (gm.toLowerCase(Locale.ROOT).contains("достав")) {
            pg.list.addView(label(a, "Получение: " + gm));
            final TextView pk = IvaSearchPatch.chip(a, "Заберу сам — самовывоз", false, false);
            pg.list.addView(pk, new LinearLayout.LayoutParams(-2, -2));
            pk.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pickup[0] = !pickup[0];
                    pk.setText(pickup[0] ? "✓ Заберу сам — самовывоз" : "Заберу сам — самовывоз");
                    pk.setTextColor(pickup[0] ? c(a, "orange", 0xffff8f1f) : c(a, "text", 0xfff2f4f8));
                }
            });
        }
        pg.list.addView(label(a, "Комментарий для проката"));
        final String oldComment = s(o, "comment");
        final EditText cm = field(a, "Комментарий", oldComment);
        pg.list.addView(cm, new LinearLayout.LayoutParams(-1, -2));
        final TextView go = IvaPickupPatch.addButton(a, c(a, "orange", 0xffff8f1f));
        go.setText("Отправить в прокат");
        go.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(a, 48));
        gl.topMargin = dp(a, 16);
        pg.list.addView(go, gl);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<String[]> keep = new ArrayList<String[]>();
                for (int i = 0; i < ids.size(); i++) if (!drop[i]) keep.add(new String[]{ids.get(i), String.valueOf(days[i])});
                if (keep.isEmpty()) { IvaStaffPatch.toast(a, "Оставьте хотя бы один инструмент. Чтобы отказаться от всех — отмените заявку"); return; }
                String nc = cm.getText().toString().trim();
                String mat = "";
                try { mat = IvaCensorPatch.fieldError(nc); } catch (Throwable ignore) {}
                if (!mat.isEmpty()) { IvaStaffPatch.toast(a, mat); return; }
                final String body = editBody(s(p, "part_id"), keep, nc.equals(oldComment) ? null : nc, pickup[0]);
                if (body == null) { IvaStaffPatch.toast(a, "Не получилось — откройте заявку заново"); return; }
                go.setEnabled(false);
                go.setAlpha(0.6f);
                new Thread(new Runnable() {
                    @Override public void run() {
                        String msg = null;
                        try {
                            String[] r = IvaClientPatch.apiCall(a, EDIT_RPC, "POST", body, true);
                            if (r == null || r[0] == null || !r[0].startsWith("2"))
                                msg = r == null ? "Нет связи — попробуйте ещё раз" : errText(r[0], r.length > 1 ? r[1] : "", "Не получилось отправить");
                        } catch (Throwable e) { msg = "Нет связи — попробуйте ещё раз"; }
                        final String fm = msg;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                go.setEnabled(true);
                                go.setAlpha(1f);
                                if (fm != null) { IvaStaffPatch.toast(a, fm); return; }
                                IvaStaffPatch.toast(a, "Изменение отправлено — прокат подтвердит. До этого действует прежний вариант");
                                try { pg.d.dismiss(); } catch (Throwable ignore) {}
                                closeSheet();
                                try { IvaOrderPatch.ordFetch(a); } catch (Throwable ignore) {}
                            }
                        });
                    }
                }).start();
            }
        });
    }

    // ───────────── «Вы в итоге взяли инструмент?» ─────────────

    static long followAt;
    static volatile boolean followBusy;
    static final Set<String> asked = new HashSet<String>();

    /** Из IvaPatch.onTab: вошедший пользователь, не чаще раза в 15 минут — есть ли отмены старше 2 дней без ответа. */
    static void onTab(final Activity a, String tab) {
        if (followBusy || System.currentTimeMillis() - followAt < FOLLOW_EVERY_MS || !IvaChatPatch.loggedIn(a)) return;
        followAt = System.currentTimeMillis();
        followBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject row = null;
                try {
                    String[] r = IvaClientPatch.apiCall(a, FOLLOW_RPC, "POST", "{}", true);
                    String t = r != null && r.length > 1 && r[1] != null ? r[1].trim() : "";
                    if (r != null && "200".equals(r[0]) && t.startsWith("[")) {
                        JSONArray arr = new JSONArray(t);
                        for (int i = 0; i < arr.length() && row == null; i++) {
                            JSONObject x = arr.optJSONObject(i);
                            if (x != null && !asked.contains(s(x, "id"))) row = x;
                        }
                    }
                } catch (Throwable ignore) {}
                followBusy = false;
                final JSONObject fr = row;
                if (fr != null) a.runOnUiThread(new Runnable() { @Override public void run() { try { ask(a, fr); } catch (Throwable ignore) {} } });
            }
        }).start();
    }

    static void ask(final Activity a, JSONObject row) {
        final String id = s(row, "id");
        asked.add(id);
        String co = s(row, "company"), tools = s(row, "tools_text");
        String text = "Вы отменили заявку №" + s(row, "order_id") + (co.isEmpty() ? "" : " в прокате «" + co + "»")
            + (tools.isEmpty() ? "" : " (" + tools + ")") + ". Вы в итоге взяли инструмент в этом прокате?";
        IvaClientPatch.styledChoice(a, "Вы взяли инструмент?", text, new String[]{"Да, взял(а)", "Нет", "Спросить позже"},
            new IvaClientPatch.Choice() {
                @Override public void pick(int i) {
                    final String body = answerBody(id, i == 0 ? "took" : i == 1 ? "not" : "later");
                    if (body == null) return;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            String msg;
                            try {
                                String[] r = IvaClientPatch.apiCall(a, ANSWER_RPC, "POST", body, true);
                                msg = r != null && r[0] != null && r[0].startsWith("2") ? answerDone(r.length > 1 ? r[1] : "")
                                    : r == null ? "Нет связи — спросим позже" : errText(r[0], r.length > 1 ? r[1] : "", "Не получилось");
                            } catch (Throwable e) { msg = "Нет связи — спросим позже"; }
                            final String fm = msg;
                            a.runOnUiThread(new Runnable() { @Override public void run() { IvaStaffPatch.toast(a, fm); } });
                        }
                    }).start();
                }
            });
    }

    // ───────────── создатель: «Управление» → «Отмены заявок» ─────────────

    static void openChief(final Activity a) {
        final IvaChiefPatch.Page pg = IvaChiefPatch.page(a, "Отмены заявок",
            "Клиент отменил до выдачи — комиссии нет. Через 2 дня клиента спрашивают, взял ли он инструмент; «Да» — комиссия начисляется.", false);
        pg.list.addView(IvaChiefPatch.hint(a, "Загрузка…"));
        new Thread(new Runnable() {
            @Override public void run() {
                String[] st = null, jr = null;
                try { st = IvaClientPatch.apiCall(a, STATS_RPC, "POST", "{}", true); } catch (Throwable ignore) {}
                try { jr = IvaClientPatch.apiCall(a, JOURNAL_RPC, "POST", "{}", true); } catch (Throwable ignore) {}
                final String[] fs = st, fj = jr;
                a.runOnUiThread(new Runnable() { @Override public void run() { try { drawChief(a, pg, fs, fj); } catch (Throwable e) { pg.list.removeAllViews(); pg.list.addView(IvaChiefPatch.hint(a, "Не удалось показать данные")); } } });
            }
        }).start();
    }

    static JSONArray arr(String[] r) {
        try {
            String t = r != null && r.length > 1 && r[1] != null ? r[1].trim() : "";
            return r != null && r[0] != null && r[0].startsWith("2") && t.startsWith("[") ? new JSONArray(t) : null;
        } catch (Throwable e) { return null; }
    }

    static void drawChief(Activity a, IvaChiefPatch.Page pg, String[] st, String[] jr) {
        if (!pg.open()) return;
        pg.list.removeAllViews();
        JSONArray s1 = arr(st), j1 = arr(jr);
        if (s1 == null && j1 == null) {
            String code = st == null ? "" : st[0];
            pg.list.addView(IvaChiefPatch.hint(a, "404".equals(code) || (st != null && st.length > 1 && String.valueOf(st[1]).contains("PGRST202"))
                ? "Нужен SQL 10.122 — примените его в Supabase" : "Не удалось загрузить — проверьте интернет"));
            return;
        }
        int cText = c(a, "text", 0xfff2f4f8), cGray = c(a, "gray", 0xff9aa1b0), cRed = c(a, "red", 0xffff8a80);
        pg.list.addView(label(a, "Доля отмен по прокатам за 90 дней"));
        if (s1 == null || s1.length() == 0) pg.list.addView(IvaChiefPatch.hint(a, "Заявок за 90 дней нет"));
        for (int i = 0; s1 != null && i < s1.length(); i++) {
            JSONObject o = s1.optJSONObject(i);
            if (o == null) continue;
            int parts = o.optInt("parts", 0), cn = o.optInt("cancelled", 0), took = o.optInt("took", 0), ed = o.optInt("edits", 0);
            LinearLayout card = IvaChiefPatch.card(a);
            card.addView(tx(a, s(o, "company").isEmpty() ? "Прокат" : s(o, "company"), 15f, cText, true));
            card.addView(tx(a, "Отменено клиентами: " + share(cn, parts), 13f, alarm(cn, parts) ? cRed : cGray, alarm(cn, parts)));
            if (took > 0) card.addView(tx(a, "Взяли после отмены (комиссия начислена): " + took, 13f, cGray, false));
            if (ed > 0) card.addView(tx(a, "Принятых изменений: " + ed, 13f, cGray, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(a, 8);
            pg.list.addView(card, lp);
        }
        pg.list.addView(label(a, "Журнал отмен"));
        if (j1 == null || j1.length() == 0) pg.list.addView(IvaChiefPatch.hint(a, "Отмен пока не было"));
        for (int i = 0; j1 != null && i < j1.length(); i++) {
            JSONObject o = j1.optJSONObject(i);
            if (o == null) continue;
            LinearLayout card = IvaChiefPatch.card(a);
            String when = IvaOrderPatch.ordNice(a, s(o, "created_at"));
            card.addView(tx(a, "Заявка №" + s(o, "order_id") + " · " + (s(o, "company").isEmpty() ? "прокат" : s(o, "company")), 14f, cText, true));
            if (!when.isEmpty()) card.addView(tx(a, when, 12f, cGray, false));
            if (!s(o, "tools_text").isEmpty()) card.addView(tx(a, s(o, "tools_text"), 13f, cText, false));
            card.addView(tx(a, "Причина: " + (s(o, "reason").isEmpty() ? "—" : s(o, "reason")), 13f, cGray, false));
            String ans = s(o, "answer");
            String fee = s(o, "fee_after");
            card.addView(tx(a, "took".equals(ans) ? "Клиент ответил: взял — комиссия начислена" + (fee.isEmpty() ? "" : " (" + fee + " ₽)")
                : "not".equals(ans) ? "Клиент ответил: не брал" : "Ответа клиента пока нет", 13f, "took".equals(ans) ? cRed : cGray, "took".equals(ans)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(a, 8);
            pg.list.addView(card, lp);
        }
    }
}

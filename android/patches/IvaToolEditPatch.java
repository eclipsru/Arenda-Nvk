package ru.prokatnvsk.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * 10.122 (просьба владельца 18.10.2026): правка инструмента одинаковая у владельца и сотрудника.
 *  • штатный список владельца открывал правку не со всеми полями (без описания, условий, подкатегории и с одним фото —
 *    и «Сохранить» их стирал); теперь правка дозагружает инструмент целиком (iva_tool_edit_card, без SQL 10.122 у
 *    владельца — те же поля напрямую) и открывается заново штатным openToolEdit, пока человек ничего не менял;
 *  • над каждым полем — подпись, как в форме «Добавить инструмент» (штатные поля не переносятся);
 *  • сотрудник: кнопка «Удалить инструмент» — только с правом «Удаляет инструмент», и удаление всегда ждёт владельца.
 */
public class IvaToolEditPatch {
    static final String LBL = "iva_te_lbl:";
    static final String[][] LABELS = {
        {"tePhotos", "Фотографии · до 5, первое — на обложке"}, {"teName", "Название инструмента *"}, {"teCat", "Категория *"},
        {"teSub", "Подкатегория"}, {"tePrice", "Цена за сутки, ₽ *"}, {"teDep", "Залог, ₽"}, {"teDescr", "Описание"},
        {"teTerms", "Условия аренды"}, {"teDelPrice", "Доставка, ₽ за 1 км"},
    };
    static final String COLS = "id,cat,sub,active,name,price,deposit,descr,terms,imgs,img,delivery,delivery_price";
    static final String DEL_TEXT = "Удалить инструмент";
    static final String DEL_RPC = "/rest/v1/rpc/iva_staff_tool_delete";
    static final String DEL_SENT = "Запрос на удаление отправлен владельцу — до его решения инструмент остаётся в каталоге";
    static final String DEL_WAIT = "Удаление уже ждёт одобрения владельца";
    static volatile String fullDone = "";
    static volatile boolean delPending = false;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    static String s(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, "").trim(); }

    static int imgCount(JSONObject o) {
        JSONArray a = o == null ? null : o.optJSONArray("imgs");
        int n = a == null ? 0 : a.length();
        return n == 0 && !s(o, "img").isEmpty() ? 1 : n;
    }

    /** Открытая правка показывает не всё, что есть в базе (описание, условия, подкатегория, фото). */
    static boolean needsFull(JSONObject card, String descr, String terms, String sub, int imgs) {
        if (card == null) return false;
        return !s(card, "descr").equals(descr == null ? "" : descr.trim())
            || !s(card, "terms").equals(terms == null ? "" : terms.trim())
            || (!s(card, "sub").isEmpty() && !s(card, "sub").equals(sub == null ? "" : sub))
            || imgCount(card) > imgs;
    }

    static boolean isDelete(CharSequence t) { return t != null && DEL_TEXT.equals(t.toString().trim()); }

    static String delBody(String id) {
        String t = id == null ? "" : id.trim();
        return IvaToolPointPatch.uuid(t) ? "{\"p_id\":\"" + t.toLowerCase(Locale.ROOT) + "\"}" : null;
    }

    // ───────────── правка ─────────────

    /** IvaMyToolsPatch уже открыл правку целиком — не дозагружать. */
    static void markFull(String id) { fullDone = id == null ? "" : id; }

    /** Из IvaPatch.onTab. */
    static void onTab(final Activity a, String tab) {
        if (!"tooledit".equals(tab)) return;
        try { labels(a); } catch (Throwable ignore) {}
        try { deleteButton(a); } catch (Throwable ignore) {}
        try { full(a); } catch (Throwable ignore) {}
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { try { labels(a); deleteButton(a); } catch (Throwable ignore) {} }
        }, 400);
    }

    static String text(Activity a, String field) {
        Object o = IvaClientPatch.get(a, field);
        return o instanceof TextView ? ((TextView) o).getText().toString() : "";
    }

    static void full(final Activity a) {
        final String id = IvaChatPatch.str(IvaClientPatch.get(a, "editToolId")).trim();
        if (!IvaToolPointPatch.uuid(id) || id.equals(fullDone)) return;
        fullDone = id;
        delPending = false;
        final String name0 = text(a, "teName"), price0 = text(a, "tePrice");
        IvaMyToolsPatch.fetchCard(a, id, new IvaMyToolsPatch.Card() {
            @Override public void got(JSONObject j, String err) {
                if (j == null) return;
                if (!id.equals(IvaChatPatch.str(IvaClientPatch.get(a, "editToolId")).trim())) return;     // открыли другой
                if (!"tooledit".equals(IvaChatPatch.str(IvaClientPatch.get(a, "curTab")))) return;
                delPending = j.optBoolean("delete_pending", false);
                if (!name0.equals(text(a, "teName")) || !price0.equals(text(a, "tePrice"))) return;     // уже правит
                Object imgs = IvaClientPatch.get(a, "teImgs");
                int n = imgs instanceof List ? ((List) imgs).size() : 0;
                String sub = IvaChatPatch.str(IvaClientPatch.get(a, "editToolSub"));
                if (!needsFull(j, text(a, "teDescr"), text(a, "teTerms"), sub, n)) return;
                try {
                    IvaChatPatch.call(a, "openToolEdit", new Class<?>[]{JSONObject.class}, j);
                } catch (Throwable ignore) {}
            }
        });
    }

    /** Без SQL 10.122 — владельцу те же поля напрямую (свои инструменты он видит и скрытыми). Фоновый поток. */
    static JSONObject direct(Activity a, String id) {
        try {
            String[] r = IvaClientPatch.apiCall(a, "/rest/v1/tools?select=" + COLS + "&id=eq." + id.trim().toLowerCase(Locale.ROOT), "GET", null, true);
            if (r == null || r[0] == null || !r[0].startsWith("2")) return null;
            JSONArray arr = new JSONArray(r.length > 1 && r[1] != null ? r[1].trim() : "[]");
            return arr.length() > 0 ? arr.optJSONObject(0) : null;
        } catch (Throwable e) { return null; }
    }

    static LinearLayout form(Activity a) {
        Object n = IvaClientPatch.get(a, "teName");
        return n instanceof View && ((View) n).getParent() instanceof LinearLayout ? (LinearLayout) ((View) n).getParent() : null;
    }

    /** Поле — прямой потомок формы (лента фото лежит в прокрутке). */
    static View child(LinearLayout form, Object o) {
        View v = o instanceof View ? (View) o : null;
        for (int i = 0; v != null && i < 3; i++) {
            if (v.getParent() == form) return v;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    static void labels(Activity a) {
        final LinearLayout form = form(a);
        if (form == null) return;
        for (String[] l : LABELS) {
            View f = child(form, IvaClientPatch.get(a, l[0]));
            View lb = form.findViewWithTag(LBL + l[0]);
            if (f == null) { if (lb != null) lb.setVisibility(View.GONE); continue; }
            if (lb == null) {
                TextView t = new TextView(a);
                t.setTag(LBL + l[0]);
                t.setText(l[1]);
                t.setTextColor(IvaPatch.color(a, "gray", 0xff9aa1b0));
                t.setTextSize(13f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = IvaPatch.dp(a, IvaKindPatch.LABEL_TOP_DP);
                form.addView(t, form.indexOfChild(f), lp);
                lb = t;
            } else if (lb.getParent() == form && form.indexOfChild(lb) != form.indexOfChild(f) - 1) {
                form.removeView(lb);
                form.addView(lb, form.indexOfChild(f));
            }
            int vis = f.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE;
            if (lb.getVisibility() != vis) lb.setVisibility(vis);
            IvaKindPatch.topMargin(f, IvaKindPatch.FIELD_TOP_DP, a);
        }
        if (form.getTag(android.R.id.summary) == null) {          // «Доставка» включили / выключили — подпись цены вместе с полем
            form.setTag(android.R.id.summary, Boolean.TRUE);
            final java.lang.ref.WeakReference<Activity> wa = new java.lang.ref.WeakReference<Activity>(a);
            form.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    Activity x = wa.get();
                    if (x == null) return;
                    try {
                        View f = child(form, IvaClientPatch.get(x, "teDelPrice"));
                        View lb = form.findViewWithTag(LBL + "teDelPrice");
                        if (f == null || lb == null) return;
                        int vis = f.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE;
                        if (lb.getVisibility() != vis) lb.setVisibility(vis);
                    } catch (Throwable ignore) {}
                }
            });
        }
    }

    // ───────────── «Удалить инструмент» у сотрудника ─────────────

    static TextView findDelete(LinearLayout form) {
        for (int i = 0; form != null && i < form.getChildCount(); i++) {
            View c = form.getChildAt(i);
            if (c instanceof TextView && isDelete(((TextView) c).getText())) return (TextView) c;
        }
        return null;
    }

    static void deleteButton(final Activity a) {
        final TextView b = findDelete(form(a));
        if (b == null) return;
        boolean staff = IvaStaffToolsPatch.staff(a);
        IvaStaffPatch.Me m = staff ? IvaStaffToolsPatch.cached(a) : null;
        boolean can = !staff || (m != null && m.del);
        if (b.getVisibility() != (can ? View.VISIBLE : View.GONE)) b.setVisibility(can ? View.VISIBLE : View.GONE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!IvaStaffToolsPatch.staff(a)) {                // владелец — штатное удаление, как было
                    try { IvaChatPatch.call(a, "deleteToolEdit", new Class<?>[0]); } catch (Throwable ignore) {}
                    return;
                }
                if (delPending) { Toast.makeText(a, DEL_WAIT, Toast.LENGTH_LONG).show(); return; }
                String id = IvaChatPatch.str(IvaClientPatch.get(a, "editToolId")).trim();
                staffDelete(a, id, text(a, "teName"), null);
            }
        });
    }

    /** Сотрудник: «Удалить» — запрос владельцу (iva_staff_tool_delete). reload — обновить список; null — вернуться к списку. */
    static void staffDelete(final Activity a, final String id, String name, final Runnable reload) {
        final String body = delBody(id);
        if (body == null) return;
        IvaStaffPatch.Me m = IvaStaffToolsPatch.cached(a);
        if (m == null || !m.del) { Toast.makeText(a, "Удалять инструменты вам не разрешил владелец проката", Toast.LENGTH_LONG).show(); return; }
        String nm = name == null || name.trim().isEmpty() ? "Инструмент" : "«" + name.trim() + "»";
        IvaClientPatch.styledChoice(a, "Удалить инструмент?", nm + " удалится после одобрения владельца проката. До его решения инструмент остаётся в каталоге.",
            new String[]{"Отправить на удаление"}, new IvaClientPatch.Choice() {
                @Override public void pick(int i) {
                    if (i != 0) return;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            String e;
                            try {
                                String[] r = IvaClientPatch.apiCall(a, DEL_RPC, "POST", body, true);
                                e = r == null ? "Не получилось" : IvaMyToolsPatch.errText(r[0], r.length > 1 ? r[1] : "", "Не получилось отправить");
                            } catch (Throwable x) { e = "Не получилось — нет связи с сервером"; }
                            final String fe = e;
                            a.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    Toast.makeText(a, fe.isEmpty() ? DEL_SENT : fe, Toast.LENGTH_LONG).show();
                                    if (!fe.isEmpty()) return;
                                    delPending = true;
                                    if (reload != null) reload.run();
                                    else try { IvaStaffToolsPatch.backToList(a); } catch (Throwable ignore) {}
                                }
                            });
                        }
                    }).start();
                }
            });
    }
}

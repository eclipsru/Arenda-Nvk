package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 10.54 — вкладка «Чат» в приложении: переписки по инструментам.
 *
 * Штатный чат (renderChat) продолжает работать как раньше, но его прокрутка chatScroll скрыта, а вместо неё
 * в тот же pageChat (над штатной строкой ввода inputBar) кладётся наш слой:
 *  • список «Чаты» — отдельная строка на каждую пару «собеседник + инструмент» (tool_id в app_messages),
 *    под ним (у клиента) — «Написать прокату», штатный список администраторов;
 *  • открытая переписка — закреплённые сверху карточка инструмента и кнопка «‹ Назад к чатам»,
 *    сообщения: свои справа, собеседника слева, у каждого — круглая аватарка, под сообщением время.
 * Кнопка «Спросить об инструменте в чате» больше не отправляет автоматическое сообщение: открывает пустую
 * переписку по этому инструменту, а в списке чат появляется после первого сообщения клиента.
 *
 * Всё — через отражение к штатным полям и методам MainActivity, без новых smali-хуков: вход — IvaPatch.onTab.
 * Если наш слой не построился, штатный chatScroll остаётся видимым (откат — штатный вид).
 */
final class IvaChatPatch {
    private IvaChatPatch() {}

    static final String OWNER = "eclips.ru@mail.ru";
    static final String ROOT_TAG = "iva_chat_root";
    static final String MSG_SELECT = "id,from_email,to_email,text,img,audio,audio_dur,is_read,created_at,tool_id";
    static final String MSG_SELECT_OLD = "id,from_email,to_email,text,img,audio,audio_dur,is_read,created_at";
    static final String VOICE_TEXT = "Голосовое сообщение";
    static final String BACK_TITLE = "‹  Назад к чатам";
    static final long CACHE_MS = 60000L;
    static final long DEBOUNCE_MS = 300L;
    static final int LIST_AVATAR_DP = 46;
    static final int LIST_TOOL_DP = 44;
    static final int HEAD_TOOL_DP = 56;
    static final int MSG_AVATAR_DP = 30;

    // ───────────────────────── 1. Чистая модель (проверяется в IvaPatchTest без Android) ─────────────────────────

    static final class Msg {
        String id = "", from = "", to = "", text = "", img = "", audio = "", created = "", tool = "";
        int dur;
        boolean read;
        long at = -1;
    }

    static final class Chat {
        String partner = "";      // почта собеседника в нижнем регистре — ключ
        String partnerRaw = "";   // как в базе (для eq-фильтров штатного markRead)
        String tool = "";         // tool_id или "" — общий чат
        Msg last;
        int unread;
        int count;
    }

    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        return "null".equals(t) ? "" : t;
    }

    static String low(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }

    static Msg msg(String id, String from, String to, String text, String img, String audio,
                   int dur, boolean read, String created, String tool) {
        Msg m = new Msg();
        m.id = clean(id); m.from = clean(from); m.to = clean(to); m.text = clean(text);
        m.img = clean(img); m.audio = clean(audio); m.dur = Math.max(0, dur); m.read = read;
        m.created = clean(created); m.tool = clean(tool); m.at = parseIso(m.created);
        return m;
    }

    /** Владелец видит переписки всех администраторов (как штатный loadChat). */
    static boolean superView(String me) {
        return OWNER.equalsIgnoreCase(clean(me));
    }

    /** Собеседник — «другая сторона». Для владельца — сторона, которая не из администраторов (клиент). */
    static String partnerOf(Msg m, String me, Set<String> admins, boolean sup) {
        String f = low(m.from), t = low(m.to), my = low(me);
        if (sup) {
            boolean fa = f.equals(my) || (admins != null && admins.contains(f));
            boolean ta = t.equals(my) || (admins != null && admins.contains(t));
            if (fa && !ta) return t;
            if (ta && !fa) return f;
        }
        if (f.equals(my)) return t;
        if (t.equals(my)) return f;
        return sup ? f : "";
    }

    static String partnerRawOf(Msg m, String partner) {
        return low(m.from).equals(partner) ? m.from : m.to;
    }

    /** «Своё» сообщение рисуется справа. У владельца своя сторона — весь прокат (любой администратор). */
    static boolean ours(Msg m, String me, Set<String> admins, boolean sup) {
        String f = low(m.from);
        if (f.equals(low(me))) return true;
        return sup && admins != null && admins.contains(f) && !partnerOf(m, me, admins, true).equals(f);
    }

    /**
     * Инструмент сообщения: tool_id из базы, иначе метка этого телефона (голосовые до SQL), иначе — только для
     * голосового — инструмент предыдущего сообщения той же пары (штатная запись голоса tool_id не передаёт).
     * Текст без tool_id — общий чат (как раньше). Сообщения идут по возрастанию времени.
     */
    static Map<String, String> assignTools(List<Msg> asc, String me, Set<String> admins, boolean sup,
                                           Map<String, String> localTags) {
        HashMap<String, String> lastTool = new HashMap<String, String>();
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (Msg m : asc) {
            String p = partnerOf(m, me, admins, sup);
            if (p.isEmpty()) continue;
            String t = m.tool;
            if (t.isEmpty() && localTags != null && localTags.containsKey(m.id)) t = clean(localTags.get(m.id));
            if (t.isEmpty() && !m.audio.isEmpty() && lastTool.containsKey(p)) t = lastTool.get(p);
            out.put(m.id, t);
            lastTool.put(p, t);
        }
        return out;
    }

    static String chatKey(String partner, String tool) {
        return low(partner) + "|" + clean(tool);
    }

    // ── 10.72: переписка «Хочу сдавать инструмент» (просьба владельца 14.10.2026) ──
    // Клиент жмёт в кабинете «Хочу сдавать инструмент» → «Чат с владельцем»: отдельная переписка с владельцем.
    // В базе — app_messages.topic = 'lend' (SQL 20261014_chat_lend_topic.sql, применяет владелец); внутри приложения
    // это «инструмент» переписки LEND — ключ, удаление у себя, голосовые работают как у переписок по инструменту.
    // У владельца в карточке переписки вместо картинки инструмента — подмигивающий кислотно-зелёный смайлик,
    // у клиента — аватарка владельца (решение владельца 14.10.2026). Пока SQL не применён — общий чат, как раньше.
    static final String LEND = "lend";
    static final String LEND_TITLE = "Хочу сдавать инструмент";
    static final String LEND_HINT = "Напишите владельцу, какой инструмент хотите сдавать, — ответим и всё подключим. "
        + "После первого сообщения чат появится в списке «Чаты».";
    static final String MSG_SELECT_TOPIC = MSG_SELECT + ",topic";
    static final int SMILE_COLOR = 0xffa6ff00;     // кислотно-зелёный
    static final int SMILE_INK = 0xff101217;       // черты лица — фон приложения

    static boolean isLend(String tool) {
        return LEND.equals(clean(tool));
    }

    // ── 10.120: переписка с пунктом выдачи («Спросить в чате» на странице проката, IvaProkatPatch) ──
    // В базе — app_messages.point_id (SQL 20261111); внутри приложения «инструмент» переписки — «pt:<пункт>».
    static final String MSG_SELECT_POINT = MSG_SELECT_TOPIC + ",point_id";
    static boolean pointColumn = true;     // колонки point_id ещё нет (SQL не применён) — переписка пункта идёт в общий чат
    static long pointAt;
    static boolean pointTagMissing = false;

    static boolean isPoint(String tool) { return IvaProkatPatch.isKey(clean(tool)); }

    static boolean isPointObj(Object o) {
        return o instanceof JSONObject && IvaToolPointPatch.uuid(((JSONObject) o).optString("iva_point", ""));
    }

    /** «Инструмент» сообщения: tool_id, иначе пункт (point_id → «pt:…»), иначе тема, иначе общий чат. */
    static String msgTool(String toolId, String topic, String pointId) {
        if (!clean(toolId).isEmpty()) return clean(toolId);
        String k = IvaProkatPatch.key(clean(pointId));
        return k.isEmpty() ? msgTool(toolId, topic) : k;
    }

    /** «Инструмент» сообщения из базы: tool_id, иначе тема переписки (topic = 'lend'), иначе общий чат. */
    static String msgTool(String toolId, String topic) {
        String t = clean(toolId);
        if (!t.isEmpty()) return t;
        return LEND.equals(clean(topic)) ? LEND : "";
    }

    /** Смайлик — только у владельца (у клиента в этой переписке — аватарка владельца). */
    static boolean lendSmile(String me, String tool) {
        return isLend(tool) && superView(me);
    }

    /** Подпись переписки в списке: название инструмента / «Хочу сдавать инструмент» / «Общий чат». */
    // ── 10.78 (просьба владельца 14.10.2026): у админов в кабинете «Чат поддержки» — общая переписка с создателем.
    // У создателя в списке такие чаты (собеседник — админ) справа, где обычно картинка инструмента, помечены «Админ». ──
    static final String ADMIN_BADGE = "Админ", SUPPORT_SUB = "Чат поддержки";

    /** Создатель смотрит переписку с админом — справа «Админ» вместо картинки инструмента. */
    static boolean adminMark(String me, String partner, Set<String> admins) {
        String p = low(partner);
        return superView(me) && !p.isEmpty() && !p.equals(low(me)) && admins != null && admins.contains(p);
    }

    /** Общая переписка админа с создателем — «Чат поддержки» (у обоих) вместо «Общий чат». */
    static boolean supportChat(String me, String partner, Set<String> admins, String tool) {
        if (!clean(tool).isEmpty()) return false;
        if (adminMark(me, partner, admins)) return true;
        return !superView(me) && OWNER.equalsIgnoreCase(clean(partner)) && admins != null && admins.contains(low(me));
    }

    static String chatSub(String tool, String toolName) {
        if (isLend(tool)) return LEND_TITLE;
        if (clean(tool).isEmpty()) return "Общий чат";
        if (isPoint(tool)) return clean(toolName).isEmpty() ? IvaProkatPatch.chatTitle("") : clean(toolName);
        return clean(toolName).isEmpty() ? "Инструмент" : clean(toolName);
    }

    /** Тело отправки текста: tool_id — переписка по инструменту, topic — «Хочу сдавать», без них — общий чат. */
    static JSONObject sendBody(String from, String to, String text, String tool, boolean toolCol, boolean topicCol) throws Exception {
        return sendBody(from, to, text, tool, toolCol, topicCol, true);
    }

    /** 10.120: переписка пункта — point_id (без SQL — общий чат). */
    static JSONObject sendBody(String from, String to, String text, String tool, boolean toolCol, boolean topicCol, boolean pointCol) throws Exception {
        JSONObject b = new JSONObject();
        b.put("from_email", from);
        b.put("to_email", to);
        b.put("text", text);
        if (isLend(tool)) { if (topicCol) b.put("topic", LEND); }
        else if (isPoint(tool)) { if (pointCol) b.put("point_id", IvaProkatPatch.idOf(clean(tool))); }
        else if (!clean(tool).isEmpty() && toolCol) b.put("tool_id", tool);
        return b;
    }

    static boolean topicColumn = true;     // колонки topic ещё нет (SQL не применён) — «Хочу сдавать» идёт в общий чат
    static long topicAt;
    static boolean topicTagMissing = false;
    static JSONObject lendObj;

    /** Метка в штатном chatTool для переписки «Хочу сдавать»: штатные «Написать» кладут null — переписка сменится на общую. */
    static JSONObject lendObj() {
        if (lendObj == null) {
            try {
                JSONObject o = new JSONObject();
                o.put("iva_topic", LEND);
                o.put("name", LEND_TITLE);
                lendObj = o;
            } catch (Throwable ignore) {}
        }
        return lendObj;
    }

    static boolean isLendObj(Object o) {
        return o instanceof JSONObject && LEND.equals(((JSONObject) o).optString("iva_topic", ""));
    }

    /** Объект для штатного chatTool по «инструменту» переписки. */
    static JSONObject toolObjFor(String tool) {
        return isLend(tool) ? lendObj() : toolCache.get(tool);
    }

    /** Подмигивающий кислотно-зелёный смайлик (рисуется сам, без картинок). */
    static View smiley(Activity a) {
        return new View(a) {
            final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.RectF rf = new android.graphics.RectF();
            @Override protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f * 0.94f;
                if (r <= 0) return;
                fill.setStyle(android.graphics.Paint.Style.FILL);
                fill.setColor(SMILE_COLOR);
                c.drawCircle(cx, cy, r, fill);
                ink.setColor(SMILE_INK);
                ink.setStyle(android.graphics.Paint.Style.FILL);
                rf.set(cx - r * 0.44f, cy - r * 0.44f, cx - r * 0.20f, cy - r * 0.08f);   // левый глаз открыт
                c.drawOval(rf, ink);
                ink.setStyle(android.graphics.Paint.Style.STROKE);
                ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                ink.setStrokeWidth(r * 0.12f);
                rf.set(cx + r * 0.14f, cy - r * 0.38f, cx + r * 0.50f, cy - r * 0.06f);   // правый — подмигивает
                c.drawArc(rf, 200f, 140f, false, ink);
                rf.set(cx - r * 0.56f, cy - r * 0.30f, cx + r * 0.56f, cy + r * 0.58f);   // улыбка
                c.drawArc(rf, 25f, 130f, false, ink);
            }
        };
    }

    // ── 10.61: «удалить чат» — только у себя (решение владельца 14.10.2026) ──
    // Таблица app_chat_hidden: (кто, собеседник, инструмент) → hidden_until = время последнего сообщения на момент
    // удаления (из базы, а не часы телефона). Сообщения не позже него не показываются; новое — чат появляется,
    // только с новыми сообщениями. У собеседника ничего не меняется, сообщения в базе не удаляются.
    static final Map<String, Long> hidden = Collections.synchronizedMap(new HashMap<String, Long>());       // ключ чата → мс
    static final Map<String, Long> hiddenLocal = Collections.synchronizedMap(new HashMap<String, Long>());  // удалённые за этот запуск
    static long hiddenAt;
    static boolean hiddenMissing;        // таблицы ещё нет (SQL не применён) — удаление недоступно
    static final long HIDDEN_RETRY_MS = 600000L;
    static String hiddenFor = "";         // для какого аккаунта подхвачена копия с телефона
    static final String HIDDEN_PREFS = "iva_chat_hidden";

    /** Копия на телефоне (чтобы без интернета удалённые чаты не возвращались): {ключ чата: мс}. */
    static String hiddenToJson(Map<String, Long> h) {
        JSONObject o = new JSONObject();
        try { if (h != null) for (Map.Entry<String, Long> e : h.entrySet()) o.put(e.getKey(), e.getValue().longValue()); } catch (Throwable ignore) {}
        return o.toString();
    }

    static Map<String, Long> hiddenFromJson(String s) {
        HashMap<String, Long> out = new HashMap<String, Long>();
        try {
            JSONObject o = new JSONObject(clean(s).isEmpty() ? "{}" : s);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                long v = o.optLong(k, -1);
                if (k.indexOf('|') > 0 && v >= 0) out.put(k, v);
            }
        } catch (Throwable ignore) {}
        return out;
    }

    static void saveHidden(Activity a, String my) {
        try {
            String js;
            synchronized (hidden) { js = hiddenToJson(hidden); }
            a.getSharedPreferences(HIDDEN_PREFS, 0).edit().putString(low(my), js).apply();
        } catch (Throwable ignore) {}
    }
    static volatile int hiddenVer;       // меняется при каждом изменении — для перерисовки

    /** Сообщение удалённого у себя чата: время не позже «удалено до». Время не разобрано — тоже скрываем. */
    static boolean hiddenMsg(Map<String, Long> hid, String partner, String tool, long at) {
        if (hid == null || hid.isEmpty()) return false;
        Long u = hid.get(chatKey(partner, tool));
        return u != null && at <= u;
    }

    /** Ответ app_chat_hidden → ключ чата → мс (позднее из повторов). */
    static Map<String, Long> parseHidden(JSONArray arr) {
        HashMap<String, Long> out = new HashMap<String, Long>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String p = low(o.optString("partner", ""));
            long at = parseIso(o.optString("hidden_until", ""));
            if (p.isEmpty() || at < 0) continue;
            String k = chatKey(p, o.isNull("tool_id") ? "" : o.optString("tool_id", ""));
            Long was = out.get(k);
            if (was == null || at > was) out.put(k, at);
        }
        return out;
    }

    /** С сервера + удалённые за этот запуск (если сервер ещё не успел вернуть) — берём позднее. */
    static Map<String, Long> mergeHidden(Map<String, Long> server, Map<String, Long> local) {
        HashMap<String, Long> out = new HashMap<String, Long>();
        if (server != null) out.putAll(server);
        if (local != null) for (Map.Entry<String, Long> e : local.entrySet()) {
            Long was = out.get(e.getKey());
            if (was == null || e.getValue() > was) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Тело iva_hide_chat: собеседник, инструмент, «удалено до» = время последнего сообщения, как в базе. */
    static String hideBody(Chat c) throws Exception {
        JSONObject b = new JSONObject();
        b.put("p_partner", c.partner);
        b.put("p_tool", c.tool);
        b.put("p_until", c.last == null ? "" : c.last.created);
        return b.toString();
    }

    // ── 10.62: «Удалить у меня» для одного сообщения (просьба владельца 14.10.2026) ──
    // Таблица app_msg_hidden: (кто, id сообщения). Скрытое сообщение не показывается только этому человеку;
    // у собеседника и в базе оно остаётся. «Удалить у всех» — как раньше (DELETE), только для своих сообщений.
    static final Set<String> hiddenMsgs = Collections.synchronizedSet(new HashSet<String>());       // id → скрыто у меня
    static final Set<String> hiddenMsgsLocal = Collections.synchronizedSet(new HashSet<String>());  // скрытые за этот запуск
    static long hiddenMsgsAt;
    static boolean hiddenMsgsMissing;    // таблицы ещё нет (SQL не применён) — «Удалить у меня» недоступно
    static String hiddenMsgsFor = "";
    static final String HIDDEN_MSG_PREFS = "iva_msg_hidden";
    static final String DEL_TITLE = "Удалить сообщение?";
    static final String DEL_ME = "Удалить у меня", DEL_ALL = "Удалить у всех";
    static final String DEL_HINT = "«У меня» — сообщение останется у собеседника. «У всех» — пропадёт у обоих.";
    static final String DEL_HINT_ME = "Сообщение пропадёт только у вас — у собеседника оно останется.";

    static String deleteHint(boolean own) { return own ? DEL_HINT : DEL_HINT_ME; }
    static final String DEL_NO_DB = "«Удалить у меня» заработает после обновления базы";

    /** Копия на телефоне: id через запятую (id сообщений — числа/uuid, запятых в них нет). */
    static String msgIdsToStr(Set<String> ids) {
        StringBuilder b = new StringBuilder();
        if (ids != null) for (String id : new java.util.TreeSet<String>(ids)) {
            String c = clean(id);
            if (c.isEmpty() || c.indexOf(',') >= 0) continue;
            if (b.length() > 0) b.append(',');
            b.append(c);
        }
        return b.toString();
    }

    static Set<String> msgIdsFromStr(String s) {
        HashSet<String> out = new HashSet<String>();
        for (String p : clean(s).split(",")) { String c = clean(p); if (!c.isEmpty()) out.add(c); }
        return out;
    }

    /** Ответ app_msg_hidden → набор id. */
    static Set<String> parseHiddenMsgs(JSONArray arr) {
        HashSet<String> out = new HashSet<String>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String id = o == null || o.isNull("msg_id") ? "" : clean(o.optString("msg_id", ""));
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    static void saveHiddenMsgs(Activity a, String my) {
        try {
            String v;
            synchronized (hiddenMsgs) { v = msgIdsToStr(hiddenMsgs); }
            a.getSharedPreferences(HIDDEN_MSG_PREFS, 0).edit().putString(low(my), v).apply();
        } catch (Throwable ignore) {}
    }

    /** Варианты удаления: своё — у меня и у всех; чужое — только у меня. */
    static String[] deleteChoices(boolean own) {
        return own ? new String[]{DEL_ME, DEL_ALL} : new String[]{DEL_ME};
    }

    static List<Chat> buildChats(List<Msg> asc, String me, Set<String> admins, boolean sup,
                                 Map<String, String> localTags) {
        return buildChats(asc, me, admins, sup, localTags, hidden);
    }

    /** Список переписок: по паре «собеседник + инструмент», свежие сверху; удалённое у себя — не показываем. */
    static List<Chat> buildChats(List<Msg> asc, String me, Set<String> admins, boolean sup,
                                 Map<String, String> localTags, Map<String, Long> hid) {
        return buildChats(asc, me, admins, sup, localTags, hid, hiddenMsgs);
    }

    static List<Chat> buildChats(List<Msg> asc, String me, Set<String> admins, boolean sup,
                                 Map<String, String> localTags, Map<String, Long> hid, Set<String> ids) {
        Map<String, String> tools = assignTools(asc, me, admins, sup, localTags);   // по всем сообщениям — как до удаления
        LinkedHashMap<String, Chat> map = new LinkedHashMap<String, Chat>();
        String my = low(me);
        for (Msg m : asc) {
            if (!tools.containsKey(m.id)) continue;
            String p = partnerOf(m, me, admins, sup);
            String t = tools.get(m.id);
            if (hiddenMsg(hid, p, t, m.at) || (ids != null && ids.contains(m.id))) continue;
            String k = chatKey(p, t);
            Chat c = map.get(k);
            if (c == null) { c = new Chat(); c.partner = p; c.tool = t; map.put(k, c); }
            c.partnerRaw = partnerRawOf(m, p);
            c.last = m;
            c.count++;
            boolean toUs = low(m.to).equals(my) || (sup && admins != null && admins.contains(low(m.to)));
            if (!m.read && toUs && low(m.from).equals(p)) c.unread++;
        }
        ArrayList<Chat> list = new ArrayList<Chat>(map.values());
        Collections.sort(list, new Comparator<Chat>() {
            @Override public int compare(Chat x, Chat y) {
                long a = x.last == null ? -1 : x.last.at, b = y.last == null ? -1 : y.last.at;
                return a == b ? 0 : (a > b ? -1 : 1);
            }
        });
        return list;
    }

    /** Сообщения одной переписки (по возрастанию времени). */
    static List<Msg> thread(List<Msg> asc, String me, Set<String> admins, boolean sup,
                            Map<String, String> localTags, String partner, String tool) {
        return thread(asc, me, admins, sup, localTags, partner, tool, hidden);
    }

    static List<Msg> thread(List<Msg> asc, String me, Set<String> admins, boolean sup,
                            Map<String, String> localTags, String partner, String tool, Map<String, Long> hid) {
        return thread(asc, me, admins, sup, localTags, partner, tool, hid, hiddenMsgs);
    }

    static List<Msg> thread(List<Msg> asc, String me, Set<String> admins, boolean sup,
                            Map<String, String> localTags, String partner, String tool, Map<String, Long> hid, Set<String> ids) {
        Map<String, String> tools = assignTools(asc, me, admins, sup, localTags);
        ArrayList<Msg> out = new ArrayList<Msg>();
        String p = low(partner), t = clean(tool);
        for (Msg m : asc) {
            if (!tools.containsKey(m.id)) continue;
            if (ids != null && ids.contains(m.id)) continue;
            if (partnerOf(m, me, admins, sup).equals(p) && t.equals(tools.get(m.id)) && !hiddenMsg(hid, p, t, m.at)) out.add(m);
        }
        return out;
    }

    /**
     * Голосовые этого телефона, записанные в открытой переписке по инструменту: свои, с пустым tool_id,
     * собеседнику переписки, появились впервые после открытия (id не встречался в прошлых загрузках).
     */
    static List<String> voiceToTag(List<Msg> asc, String me, String partner, String tool,
                                   Set<String> seenBefore, Map<String, String> localTags) {
        ArrayList<String> out = new ArrayList<String>();
        if (clean(tool).isEmpty() || low(partner).isEmpty() || seenBefore == null) return out;
        for (Msg m : asc) {
            if (m.audio.isEmpty() || !m.tool.isEmpty() || m.id.isEmpty()) continue;
            if (!low(m.from).equals(low(me)) || !low(m.to).equals(low(partner))) continue;
            if (seenBefore.contains(m.id)) continue;
            if (localTags != null && localTags.containsKey(m.id)) continue;
            out.add(m.id);
        }
        return out;
    }

    /** ISO-время Supabase (UTC или со смещением) → миллисекунды; −1, если не разобрать. */
    static long parseIso(String iso) {
        String s = clean(iso);
        if (s.length() < 16) return -1;
        try {
            int y = Integer.parseInt(s.substring(0, 4)), mo = Integer.parseInt(s.substring(5, 7));
            int d = Integer.parseInt(s.substring(8, 10)), h = Integer.parseInt(s.substring(11, 13));
            int mi = Integer.parseInt(s.substring(14, 16));
            int sec = s.length() >= 19 && Character.isDigit(s.charAt(17)) ? Integer.parseInt(s.substring(17, 19)) : 0;
            int i = s.length() >= 19 ? 19 : 16;
            int ms = 0;
            if (i < s.length() && s.charAt(i) == '.') {
                int j = i + 1; String frac = "";
                while (j < s.length() && Character.isDigit(s.charAt(j))) { if (frac.length() < 3) frac += s.charAt(j); j++; }
                while (frac.length() < 3) frac += "0";
                ms = Integer.parseInt(frac);
                i = j;
            }
            int offMin = 0;
            if (i < s.length()) {
                char c = s.charAt(i);
                if (c == '+' || c == '-') {
                    String o = s.substring(i + 1).replace(":", "");
                    int oh = o.length() >= 2 ? Integer.parseInt(o.substring(0, 2)) : 0;
                    int om = o.length() >= 4 ? Integer.parseInt(o.substring(2, 4)) : 0;
                    offMin = (c == '-' ? -1 : 1) * (oh * 60 + om);
                }
            }
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            cal.clear();
            cal.set(y, mo - 1, d, h, mi, sec);
            return cal.getTimeInMillis() + ms - offMin * 60000L;
        } catch (Throwable e) {
            return -1;
        }
    }

    static Calendar cal(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.ROOT);
        c.setTimeInMillis(t);
        return c;
    }

    static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    static String hm(long t, TimeZone tz) {
        if (t < 0) return "";
        Calendar c = cal(t, tz);
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
    }

    static String dmy(long t, TimeZone tz) {
        if (t < 0) return "";
        Calendar c = cal(t, tz);
        return two(c.get(Calendar.DAY_OF_MONTH)) + "." + two(c.get(Calendar.MONTH) + 1) + "." + c.get(Calendar.YEAR);
    }

    static long dayIndex(long t, TimeZone tz) {
        return (t + tz.getOffset(t)) / 86400000L;
    }

    /** Разделитель дней в переписке. */
    static String dayLabel(long t, long now, TimeZone tz) {
        if (t < 0) return "";
        long d = dayIndex(now, tz) - dayIndex(t, tz);
        if (d == 0) return "Сегодня";
        if (d == 1) return "Вчера";
        return dmy(t, tz);
    }

    static String durLabel(int sec) {
        int s = Math.max(0, sec);
        return (s / 60) + ":" + two(s % 60);
    }

    /** Строка под именем в списке. */
    static String preview(Msg m, boolean mine) {
        if (m == null) return "";
        String body;
        if (!m.audio.isEmpty()) body = VOICE_TEXT + (m.dur > 0 ? " · " + durLabel(m.dur) : "");
        else if (!m.text.isEmpty()) body = m.text.replaceAll("\\s+", " ");
        else if (!m.img.isEmpty()) body = "Фото";
        else body = "";
        return (mine ? "Вы: " : "") + body;
    }

    /** Имя проката: компания, иначе ФИО/имя администратора, у владельца — «Владелец», иначе почта. */
    static String adminTitle(String company, String fullName, String name, String email) {
        String[] v = {company, fullName, name};
        for (String s : v) if (!clean(s).isEmpty()) return clean(s);
        if (OWNER.equalsIgnoreCase(clean(email))) return "Владелец";
        return clean(email).isEmpty() ? "Прокат" : clean(email);
    }

    static String letter(String name) {
        String n = clean(name);
        if (n.isEmpty()) return "?";
        int cp = n.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
    }

    static String toolIdOf(JSONObject tool) {
        return tool == null ? "" : clean(tool.optString("id", ""));
    }

    static String toolImg(JSONObject tool) {
        if (tool == null) return "";
        String img = clean(tool.optString("img", ""));
        if (!img.isEmpty()) return img;
        JSONArray arr = tool.optJSONArray("imgs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = clean(arr.optString(i, ""));
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    // ───────────────────────── 2. Состояние (живёт, пока жив процесс) ─────────────────────────

    static WeakReference<Activity> act = new WeakReference<Activity>(null);
    static WeakReference<View> hookedAsk = new WeakReference<View>(null);
    static WeakReference<View> hookedSend = new WeakReference<View>(null);
    static WeakReference<View> hookedLend = new WeakReference<View>(null);
    static WeakReference<View> hookedList = new WeakReference<View>(null);
    static LinearLayout root, header, body;
    static ScrollView scroll;
    static Handler handler;

    static String me = "";
    static boolean admin = false;
    static String openPartner = "", openPartnerRaw = "", openTool = "";
    static Object lastToolObj;                      // объект chatTool, который положили мы (чужая смена → перечитать)
    static String lastWith = "";
    static volatile List<Msg> msgs = new ArrayList<Msg>();
    static volatile boolean msgsReady = false;
    static volatile String msgsFor = "";                      // почта, к которой относятся msgs
    static final Set<String> seenIds = Collections.synchronizedSet(new HashSet<String>());
    static boolean seenReady = false;
    static final Map<String, String> localTags = Collections.synchronizedMap(new HashMap<String, String>());   // id сообщения → tool_id
    static final Map<String, JSONObject> toolCache = Collections.synchronizedMap(new HashMap<String, JSONObject>());
    static final Set<String> toolMissing = Collections.synchronizedSet(new HashSet<String>());
    static final LinkedHashMap<String, String[]> admins = new LinkedHashMap<String, String[]>(); // почта → {имя, аватар, active}
    static long adminsAt = 0;
    static final Map<String, String> clientNames = Collections.synchronizedMap(new HashMap<String, String>());
    static long ordersAt = 0;
    static final Map<String, String> peerPaths = Collections.synchronizedMap(new HashMap<String, String>());
    static final Map<String, Bitmap> peerBitmaps = Collections.synchronizedMap(new HashMap<String, Bitmap>());
    static final Set<String> peerFetching = Collections.synchronizedSet(new HashSet<String>());
    static long peersAt = 0;
    static boolean peersMissing = false, tagMissing = false;
    static boolean toolColumn = true;
    static volatile boolean loading = false;
    static volatile boolean pending = false;
    static String renderedSig = "";
    static boolean stickBottom = true;
    static String lastError = "";

    static final Runnable DEBOUNCED = new Runnable() {
        @Override public void run() {
            Activity a = act.get();
            if (a != null) refresh(a);
        }
    };

    // ───────────────────────── 3. Вход: каждое переключение вкладки ─────────────────────────

    public static void onShowTab(final Activity a, String tab) {
        try {
            act = new WeakReference<Activity>(a);
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            // При запуске showTab идёт ещё внутри onCreate (до setupChat) — ставим всё отдельным шагом.
            final boolean chat = "chat".equals(tab);
            if (!chat) voiceStop(a);                    // ушли из чата — голосовое замолкает
            handler.post(new Runnable() {
                @Override public void run() {
                    try { install(a); } catch (Throwable ignore) {}
                    if (chat) {
                        try { syncState(a); render(a); refresh(a); } catch (Throwable e) { fallback(a); }
                        startLive();
                    }
                }
            });
        } catch (Throwable ignore) {
            // чат — дополнение: штатная вкладка работает и без него
        }
    }

    static void install(Activity a) {
        hookAsk(a);
        hookSend(a);
        hookLend(a);
        Object page = IvaClientPatch.get(a, "pageChat");
        Object sc = IvaClientPatch.get(a, "chatScroll");
        if (!(page instanceof LinearLayout) || !(sc instanceof View)) return;
        LinearLayout pg = (LinearLayout) page;
        if (root == null || root.getParent() != pg || pg.findViewWithTag(ROOT_TAG) == null) buildRoot(a, pg, (View) sc);
        Object list = IvaClientPatch.get(a, "chatList");
        if (list instanceof ViewGroup && hookedList.get() != list) {
            ((ViewGroup) list).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { schedule(); }
                @Override public void onChildViewRemoved(View parent, View child) { schedule(); }
            });
            hookedList = new WeakReference<View>((View) list);
        }
    }

    /** Штатный renderChat перестроил скрытый список → наша загрузка (не чаще раза в 300 мс). */
    static void schedule() {
        if (handler == null) return;
        handler.removeCallbacks(DEBOUNCED);
        handler.postDelayed(DEBOUNCED, DEBOUNCE_MS);
    }

    static void buildRoot(Activity a, LinearLayout pg, View sc) {
        View old = pg.findViewWithTag(ROOT_TAG);
        if (old != null) pg.removeView(old);
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setTag(ROOT_TAG);
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 4));
        h.setVisibility(View.GONE);
        r.addView(h, new LinearLayout.LayoutParams(-1, -2));
        ScrollView s = new ScrollView(a);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 14));
        s.addView(b, new FrameLayout.LayoutParams(-1, -2));
        r.addView(s, new LinearLayout.LayoutParams(-1, 0, 1f));
        // Клавиатура уменьшила высоту — держим низ переписки на виду.
        s.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int rr, int bb, int ol, int ot, int or, int ob) {
                if (!openPartner.isEmpty() && (bb - t) < (ob - ot) && stickBottom) toBottom();
            }
        });
        s.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() {
                ScrollView sv = scroll;
                if (sv == null || sv.getChildCount() == 0 || !sv.isShown()) return;   // прокрутка других вкладок — не наша
                stickBottom = sv.getScrollY() + sv.getHeight() >= sv.getChildAt(0).getHeight() - dp(sv.getContext(), 48);
            }
        });
        int idx = pg.indexOfChild(sc);
        pg.addView(r, Math.max(0, idx), new LinearLayout.LayoutParams(-1, 0, 1f));
        root = r; header = h; scroll = s; body = b;
        renderedSig = "";
    }

    /** Наш слой не построился/упал — показываем штатный чат. */
    static void fallback(Activity a) {
        try {
            if (root != null) root.setVisibility(View.GONE);
            Object sc = IvaClientPatch.get(a, "chatScroll");
            if (sc instanceof View) ((View) sc).setVisibility(View.VISIBLE);
        } catch (Throwable ignore) {}
    }

    static boolean loggedIn(Activity a) {
        Object t = IvaClientPatch.get(a, "accessToken");
        return t instanceof String && !((String) t).trim().isEmpty();
    }

    static boolean isAdmin(Activity a) {
        try {
            Object v = call(a, "isAdminEmail", new Class<?>[0]);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable e) {
            return false;
        }
    }

    static String str(Object o) { return o instanceof String ? (String) o : ""; }

    // ───────────────────────── 4. Кнопки: «Спросить…» и «Отправить» ─────────────────────────

    static void hookAsk(final Activity a) {
        final View ask = a.findViewById(IvaClientPatch.id(a, "detAsk"));
        if (ask == null || hookedAsk.get() == ask) return;
        ask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    // Администратору — штатная подсказка, без входа — штатный экран входа.
                    if (!loggedIn(a) || isAdmin(a)) { nativeAsk(a, v); return; }
                    Object item = IvaClientPatch.get(a, "detItem");
                    JSONObject tool = item instanceof JSONObject ? (JSONObject) item : null;
                    String owner = clean(str(IvaClientPatch.get(a, "detOwner")));
                    if (owner.isEmpty()) owner = OWNER;
                    openThread(a, owner, toolIdOf(tool), tool);
                    callTab(a, "chat");
                    callNative(a, "loadChat");
                    render(a);
                } catch (Throwable e) {
                    nativeAsk(a, v);
                }
            }
        });
        hookedAsk = new WeakReference<View>(ask);
    }

    /** Кабинет → «Хочу сдавать инструмент» → «Чат с владельцем»: отдельная переписка (10.72). */
    static void hookLend(final Activity a) {
        final View b = a.findViewById(IvaClientPatch.id(a, "lendChat"));
        if (b == null || hookedLend.get() == b) return;
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    // Без входа — штатная подсказка и экран входа; сам владелец себе не пишет; без SQL — общий чат, как раньше.
                    if (!loggedIn(a) || superView(str(IvaClientPatch.get(a, "userEmail"))) || !topicColumn) { callNative(a, "lendChatSend"); return; }
                    openThread(a, OWNER, LEND, lendObj());
                    callTab(a, "chat");
                    callNative(a, "loadChat");
                    render(a);
                } catch (Throwable e) {
                    callNative(a, "lendChatSend");
                }
            }
        });
        hookedLend = new WeakReference<View>(b);
    }

    static void nativeAsk(Activity a, View v) {
        try {
            Method m = a.getClass().getDeclaredMethod("lambda$setupChat$88$ru-prokatnvsk-app-MainActivity", View.class);
            m.setAccessible(true);
            m.invoke(a, v);
        } catch (Throwable ignore) {}
    }

    /**
     * 10.76: «Написать» в активной заявке — общая переписка с этим человеком (у клиента — прокат, у админа — клиент).
     * Писать первым может любой: в списке чат появится после первого сообщения. Без входа / сам себе — false.
     */
    static boolean openWith(Activity a, String partnerRaw) {
        String p = clean(partnerRaw);
        if (p.isEmpty() || !loggedIn(a) || low(p).equals(low(str(IvaClientPatch.get(a, "userEmail"))))) return false;
        act = new WeakReference<Activity>(a);
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        voiceStop(a);
        openThread(a, p, "", null);
        callTab(a, "chat");
        callNative(a, "loadChat");
        render(a);
        return true;
    }

    /** 10.120: «Спросить в чате» на странице проката — переписка с этим пунктом выдачи. */
    static void openPoint(Activity a, String ownerRaw, String pointId, JSONObject obj) {
        String k = IvaProkatPatch.key(pointId);
        if (k.isEmpty() || obj == null) return;
        act = new WeakReference<Activity>(a);
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        voiceStop(a);
        toolCache.put(k, obj);
        toolMissing.remove(k);
        openThread(a, clean(ownerRaw), k, obj);
        callTab(a, "chat");
        callNative(a, "loadChat");
        render(a);
    }

    static void openThread(Activity a, String partnerRaw, String tool, JSONObject toolObj) {
        if (toolObj != null && !clean(tool).isEmpty() && !isLend(tool) && !toolCache.containsKey(tool)) toolCache.put(tool, toolObj);
        IvaClientPatch.set(a, "chatWith", partnerRaw);
        IvaClientPatch.set(a, "chatTool", toolObj);
        lastToolObj = toolObj;
        lastWith = low(partnerRaw);
        openPartner = low(partnerRaw);
        openPartnerRaw = partnerRaw;
        openTool = clean(tool);
        stickBottom = true;
        renderedSig = "";
    }

    // ── 10.59: нажали на уведомление о сообщении ──
    static final long NOTE_WAIT_MS = 20000;
    static String noteMsg = "";          // id сообщения: открыть его переписку, когда загрузится
    static long noteUntil;
    static volatile int loadStarted, loadDone;   // номера загрузок: начатой последней и последней завершённой
    static int noteGen;                   // loadStarted в момент нажатия: решают только загрузки, начатые после него
    static String flashMsg = "";          // показать и подсветить это сообщение при ближайшей отрисовке
    static View flashRow;

    /** Уведомление о сообщении: msgId — его переписка с прокруткой к нему; пусто (несколько собеседников) — список чатов. */
    public static void openFromNote(Activity a, String msgId) {
        act = new WeakReference<Activity>(a);
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        voiceStop(a);
        IvaClientPatch.set(a, "chatWith", null);
        IvaClientPatch.set(a, "chatTool", null);
        lastToolObj = null;
        lastWith = "";
        openPartner = ""; openPartnerRaw = ""; openTool = "";
        renderedSig = "";
        noteMsg = clean(msgId);
        noteUntil = System.currentTimeMillis() + NOTE_WAIT_MS;
        noteGen = loadStarted;
        callTab(a, "chat");                  // штатный переход → IvaPatch.onTab → onShowTab → загрузка → render → applyNote
    }

    /** Переписка сообщения: {собеседник, его адрес как в базе, инструмент} — по тем же правилам, что и список. null — нет. */
    static String[] locate(List<Msg> asc, String me, Set<String> admins, boolean sup, Map<String, String> localTags, String id) {
        if (asc == null || clean(id).isEmpty()) return null;
        Map<String, String> tools = assignTools(asc, me, admins, sup, localTags);
        for (Msg m : asc) {
            if (!m.id.equals(id) || !tools.containsKey(m.id)) continue;
            String p = partnerOf(m, me, admins, sup);
            if (p.isEmpty() || hiddenMsg(hidden, p, tools.get(m.id), m.at) || hiddenMsgs.contains(m.id)) return null;
            return new String[]{p, partnerRawOf(m, p), tools.get(m.id)};
        }
        return null;
    }

    static void applyNote(Activity a) {
        if (noteMsg.isEmpty()) return;
        if (System.currentTimeMillis() > noteUntil) { noteMsg = ""; return; }
        String my = low(str(IvaClientPatch.get(a, "userEmail")));
        if (!ready(my)) return;
        String[] t = locate(msgs, my, adminEmails(), superView(my), localTags, noteMsg);
        if (t == null) {
            if (loadDone > noteGen) noteMsg = "";     // загрузка, начатая после нажатия, прошла, а сообщения нет (удалено) — остаётся список
            return;
        }
        flashMsg = noteMsg;
        noteMsg = "";
        openThread(a, t[1].isEmpty() ? t[0] : t[1], t[2], toolObjFor(t[2]));
        callNative(a, "loadChat");           // как нажатие в списке: штатно отмечает переписку прочитанной
    }

    static void closeThread(Activity a) {
        IvaClientPatch.set(a, "chatWith", null);
        IvaClientPatch.set(a, "chatTool", null);
        lastToolObj = null;
        lastWith = "";
        openPartner = ""; openPartnerRaw = ""; openTool = "";
        voiceStop(a);
        renderedSig = "";
        callNative(a, "loadChat");
        render(a);
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    static void hookSend(final Activity a) {
        final View send = a.findViewById(IvaClientPatch.id(a, "chatSend"));
        if (send == null || hookedSend.get() == send) return;
        // Только нажатие: долгое нажатие (запись голоса) и касания остаются штатными.
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { send(a); } catch (Throwable e) { callNative(a, "sendChat"); }
            }
        });
        hookedSend = new WeakReference<View>(send);
        // 10.96: кружок отправки — рамкой (bg_nav_circle_on в ресурсах), белый самолётик → оранжевый
        if (send instanceof ViewGroup && ((ViewGroup) send).getChildCount() > 0 && ((ViewGroup) send).getChildAt(0) instanceof ImageView)
            ((ImageView) ((ViewGroup) send).getChildAt(0)).setColorFilter(color(a, "orange"));
    }

    static void send(final Activity a) {
        Object in = IvaClientPatch.get(a, "chatInput");
        final String partner = clean(str(IvaClientPatch.get(a, "chatWith")));
        if (!loggedIn(a) || !(in instanceof EditText) || partner.isEmpty()) { callNative(a, "sendChat"); return; }
        final EditText input = (EditText) in;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        final String from = clean(str(IvaClientPatch.get(a, "userEmail")));
        final String tool = low(partner).equals(openPartner) ? openTool : "";
        String mat = "";
        try { mat = IvaCensorPatch.fieldError(text); } catch (Throwable ignore) {}
        if (!mat.isEmpty()) {                                                          // 10.92: мат не отправляется (база тоже не примет)
            android.widget.Toast.makeText(a, mat, android.widget.Toast.LENGTH_LONG).show();
            return;                                                                    // текст остаётся в поле — можно исправить
        }
        input.setText("");
        stickBottom = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    JSONObject b = sendBody(from, partner, text, tool, toolColumn, topicColumn, pointColumn);
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/app_messages", "POST", b.toString(), true);
                    if (!ok(r) && b.has("point_id")) {
                        // Колонки point_id нет (SQL 10.120 не применён) — общий чат с прокатом, как раньше.
                        b.remove("point_id");
                        r = IvaClientPatch.apiCall(a, "/rest/v1/app_messages", "POST", b.toString(), true);
                    }
                    if (!ok(r) && b.has("topic")) {
                        // Колонки topic нет (SQL не применён) — общий чат, как раньше.
                        b.remove("topic");
                        r = IvaClientPatch.apiCall(a, "/rest/v1/app_messages", "POST", b.toString(), true);
                    }
                    if (!ok(r) && b.has("tool_id")) {
                        // Инструмент не принят базой (например, услуга вместо инструмента) — общий чат, как раньше.
                        b.remove("tool_id");
                        r = IvaClientPatch.apiCall(a, "/rest/v1/app_messages", "POST", b.toString(), true);
                    }
                    if (!ok(r)) err = r == null ? "нет ответа" : r[0] + " " + (r.length > 1 ? r[1] : "");
                } catch (Throwable e) {
                    err = String.valueOf(e.getMessage());
                }
                final String fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (fe != null) {
                            if (input.getText().toString().trim().isEmpty()) input.setText(text);
                            Toast.makeText(a, "Не отправлено: " + human(a, fe), Toast.LENGTH_LONG).show();
                            return;
                        }
                        callNative(a, "loadChat");
                        refresh(a);
                    }
                });
            }
        }).start();
    }

    static boolean ok(String[] r) {
        return r != null && r.length > 0 && r[0] != null && r[0].startsWith("2");
    }

    static String human(Activity a, String e) {
        try {
            Object v = call(a, "humanError", new Class<?>[]{String.class}, e);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        } catch (Throwable ignore) {}
        return e;
    }

    // ───────────────────────── 5. Загрузка ─────────────────────────

    /** Какая переписка открыта: читаем штатные chatWith/chatTool (их меняют и штатные кнопки «Написать»). */
    static void syncState(Activity a) {
        String with = clean(str(IvaClientPatch.get(a, "chatWith")));
        Object ct = IvaClientPatch.get(a, "chatTool");
        if (with.isEmpty()) {
            if (!openPartner.isEmpty()) renderedSig = "";
            openPartner = ""; openPartnerRaw = ""; openTool = ""; lastWith = ""; lastToolObj = null;
            return;
        }
        if (!low(with).equals(lastWith) || ct != lastToolObj) {
            JSONObject tool = ct instanceof JSONObject ? (JSONObject) ct : null;
            String tid = isLendObj(tool) ? LEND : isPointObj(tool) ? IvaProkatPatch.key(tool.optString("iva_point", "")) : toolIdOf(tool);
            if (tool != null && !tid.isEmpty() && !isLend(tid) && !toolCache.containsKey(tid)) toolCache.put(tid, tool);
            openPartner = low(with); openPartnerRaw = with; openTool = tid;
            lastWith = low(with); lastToolObj = ct;
            stickBottom = true;
            renderedSig = "";
        }
    }

    static void refresh(final Activity a) {
        if (!"chat".equals(str(IvaClientPatch.get(a, "curTab")))) return;
        try { syncState(a); render(a); } catch (Throwable e) { fallback(a); }
        if (!loggedIn(a)) return;
        if (loading) { pending = true; return; }
        loading = true;
        pending = false;
        final String my = low(str(IvaClientPatch.get(a, "userEmail")));
        final boolean adm = isAdmin(a);
        final String partner = openPartner, tool = openTool;
        final int gen = ++loadStarted;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    load(a, my, adm, partner, tool);
                    loadDone = Math.max(loadDone, gen);
                    lastError = "";
                } catch (Throwable e) {
                    lastError = "Нет связи — обновим через несколько секунд";
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if ((isLend(openTool) && !topicColumn) || (isPoint(openTool) && !pointColumn)) {   // SQL не применён — общий чат, как раньше
                            IvaClientPatch.set(a, "chatTool", null);
                            lastToolObj = null;
                            openTool = "";
                            renderedSig = "";
                        }
                        try { render(a); } catch (Throwable e) { fallback(a); }
                        if (pending) refresh(a);
                    }
                });
            }
        }).start();
    }

    /** Фильтр переписки: своя (или у владельца — всех прокатов). Один и тот же для загрузки и живой проверки. */
    static String msgFilter(String my, boolean sup) {
        if (sup) {
            StringBuilder in = new StringBuilder();
            HashSet<String> all = new HashSet<String>(adminEmails());
            all.add(OWNER);
            for (String e : all) { if (in.length() > 0) in.append(','); in.append(enc(e)); }
            return "&or=(from_email.in.(" + in + "),to_email.in.(" + in + "))";
        }
        return "&or=(from_email.eq." + enc(my) + ",to_email.eq." + enc(my) + ")";
    }

    // ───────────────────────── 10.57: живое обновление открытого чата ─────────────────────────
    // Штатно чат не обновлялся сам (loadChat — только по нажатиям): ответ собеседника появлялся
    // лишь после повторного входа в «Чат». Пока «Чат» на экране — раз в 4 с лёгкий запрос
    // (id и is_read последних 30 сообщений); есть новое или изменилось «прочитано» — штатный loadChat
    // (он же отмечает прочитанным открытую переписку, как при входе) и наша перерисовка.
    static final long LIVE_MS = 4000L;
    static final int LIVE_ROWS = 30;
    static volatile boolean liveBusy = false;
    static final Runnable LIVE = new Runnable() {
        @Override public void run() { live(); }
    };

    static void startLive() {
        if (handler == null) return;
        handler.removeCallbacks(LIVE);
        handler.postDelayed(LIVE, LIVE_MS);
    }

    /** Нужна ли перезагрузка: на сервере есть сообщение, которого нет у нас, или у него другое «прочитано». */
    static boolean liveChanged(List<String[]> server, List<Msg> local) {
        HashMap<String, Boolean> have = new HashMap<String, Boolean>();
        for (Msg m : local) have.put(m.id, m.read);
        for (String[] r : server) {
            Boolean rd = have.get(r[0]);
            if (rd == null || rd.booleanValue() != "true".equals(r[1])) return true;
        }
        return false;
    }

    static void live() {
        final Activity a = act.get();
        if (a == null || a.isFinishing()) return;
        if (!"chat".equals(str(IvaClientPatch.get(a, "curTab")))) return;   // ушли из «Чата» — проверка останавливается
        handler.postDelayed(LIVE, LIVE_MS);
        if (!a.hasWindowFocus() || !loggedIn(a) || liveBusy || loading) return;
        final String my = low(str(IvaClientPatch.get(a, "userEmail")));
        if (!ready(my)) return;
        liveBusy = true;
        final boolean sup = superView(my);
        final List<Msg> local = msgs;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean changed = false;
                try {
                    JSONArray rows = getArr(a, "/rest/v1/app_messages?select=id,is_read" + msgFilter(my, sup)
                        + "&order=created_at.desc&limit=" + LIVE_ROWS);
                    List<String[]> server = new ArrayList<String[]>();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject o = rows.optJSONObject(i);
                        if (o != null) server.add(new String[]{o.optString("id", ""), String.valueOf(o.optBoolean("is_read", false))});
                    }
                    changed = liveChanged(server, local);
                } catch (Throwable ignore) {}
                final boolean ch = changed;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        liveBusy = false;
                        if (!ch || !"chat".equals(str(IvaClientPatch.get(a, "curTab")))) return;
                        callNative(a, "loadChat");
                        refresh(a);
                    }
                });
            }
        }).start();
    }

    static JSONArray getArr(Activity a, String path) throws Exception {
        String[] r = IvaClientPatch.apiCall(a, path, "GET", null, true);
        if (!ok(r)) throw new Exception(r == null ? "нет ответа" : r[0]);
        String t = r.length > 1 && r[1] != null ? r[1].trim() : "";
        return t.startsWith("[") ? new JSONArray(t) : new JSONArray();
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable e) { return s; }
    }

    static void load(Activity a, String my, boolean adm, String partner, String tool) throws Exception {
        long now = System.currentTimeMillis();
        if (!my.equals(msgsFor)) {
            // другой аккаунт — всё с нуля
            msgs = new ArrayList<Msg>(); msgsReady = false; seenIds.clear(); seenReady = false;
            clientNames.clear(); ordersAt = 0; peerPaths.clear(); peerBitmaps.clear(); peersAt = 0;
            hidden.clear(); hiddenLocal.clear(); hiddenAt = 0; hiddenFor = ""; hiddenVer++;
            hiddenMsgs.clear(); hiddenMsgsLocal.clear(); hiddenMsgsAt = 0; hiddenMsgsFor = "";
            renderedSig = "";
        }
        if (now - adminsAt > CACHE_MS || admins.isEmpty()) {
            JSONArray arr;
            try { arr = getArr(a, "/rest/v1/admins?select=email,full_name,name,company,avatar,active&order=created_at.asc"); }
            catch (Throwable e) { arr = getArr(a, "/rest/v1/admins?select=email,full_name,avatar,active&order=created_at.asc"); }
            LinkedHashMap<String, String[]> next = new LinkedHashMap<String, String[]>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String e = low(o.optString("email", ""));
                if (e.isEmpty()) continue;
                String title = adminTitle(o.optString("company", ""), o.optString("full_name", ""), o.optString("name", ""), e);
                boolean active = !o.has("active") || o.optBoolean("active", true);
                next.put(e, new String[]{title, clean(o.optString("avatar", "")), active ? "1" : "0", clean(o.optString("email", ""))});
            }
            synchronized (admins) { admins.clear(); admins.putAll(next); }
            adminsAt = now;
        }
        if (!my.equals(hiddenFor)) {                                          // первая загрузка аккаунта — копия с телефона
            hiddenFor = my;
            try {
                Map<String, Long> cached = hiddenFromJson(a.getSharedPreferences(HIDDEN_PREFS, 0).getString(my, ""));
                if (!cached.isEmpty()) { synchronized (hidden) { hidden.putAll(mergeHidden(hidden, cached)); } hiddenVer++; }
            } catch (Throwable ignore) {}
        }
        if (now - hiddenAt > (hiddenMissing ? HIDDEN_RETRY_MS : CACHE_MS)) {      // нет таблицы — проверяем реже: SQL могут применить в любой момент
            hiddenAt = now;
            try {
                String[] r = IvaClientPatch.apiCall(a, "/rest/v1/app_chat_hidden?select=partner,tool_id,hidden_until&limit=1000", "GET", null, true);
                if (r != null && "404".equals(r[0])) hiddenMissing = true;          // SQL ещё не применён
                else if (ok(r)) {
                    hiddenMissing = false;
                    String t = r.length > 1 && r[1] != null ? r[1].trim() : "";
                    Map<String, Long> next;
                    synchronized (hiddenLocal) { next = mergeHidden(parseHidden(t.startsWith("[") ? new JSONArray(t) : new JSONArray()), hiddenLocal); }
                    synchronized (hidden) {
                        if (!hidden.equals(next)) { hidden.clear(); hidden.putAll(next); hiddenVer++; }
                    }
                    saveHidden(a, my);
                } else hiddenAt = 0;                                            // не ответил — спросим при следующей загрузке
            } catch (Throwable e) { hiddenAt = 0; }
        }
        loadHiddenMsgs(a, my, now);
        boolean sup = superView(my);
        String filter = msgFilter(my, sup);
        JSONArray rows;
        if (!topicColumn && now - topicAt > HIDDEN_RETRY_MS) topicColumn = true;       // SQL могут применить в любой момент
        if (!pointColumn && now - pointAt > HIDDEN_RETRY_MS) pointColumn = true;
        rows = null;
        if (toolColumn && topicColumn && pointColumn) {                                // 10.120: с пунктом выдачи
            try { rows = getArr(a, "/rest/v1/app_messages?select=" + MSG_SELECT_POINT + filter + "&order=created_at.desc&limit=300"); }
            catch (Throwable e) { pointColumn = false; pointAt = now; }
        }
        if (rows == null) try {
            rows = getArr(a, "/rest/v1/app_messages?select=" + (!toolColumn ? MSG_SELECT_OLD : topicColumn ? MSG_SELECT_TOPIC : MSG_SELECT) + filter
                + "&order=created_at.desc&limit=300");
        } catch (Throwable e) {
            if (!toolColumn) throw e;
            if (topicColumn) {
                topicColumn = false;                                                    // колонки topic нет — как до 10.72
                topicAt = now;
                try {
                    rows = getArr(a, "/rest/v1/app_messages?select=" + MSG_SELECT + filter + "&order=created_at.desc&limit=300");
                } catch (Throwable e2) {
                    rows = getArr(a, "/rest/v1/app_messages?select=" + MSG_SELECT_OLD + filter + "&order=created_at.desc&limit=300");
                    toolColumn = false;
                }
            } else {
                rows = getArr(a, "/rest/v1/app_messages?select=" + MSG_SELECT_OLD + filter + "&order=created_at.desc&limit=300");
                toolColumn = false;
            }
        }
        ArrayList<Msg> asc = new ArrayList<Msg>();
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            asc.add(msg(o.optString("id", ""), o.optString("from_email", ""), o.optString("to_email", ""),
                o.optString("text", ""), o.optString("img", ""), o.optString("audio", ""),
                o.optInt("audio_dur", 0), o.optBoolean("is_read", false), o.optString("created_at", ""),
                msgTool(o.isNull("tool_id") ? "" : o.optString("tool_id", ""), o.isNull("topic") ? "" : o.optString("topic", ""),
                    o.isNull("point_id") ? "" : o.optString("point_id", ""))));
        }
        if (adm && !IvaProkatPatch.ownerSees(a)) {                 // 10.120: владелец выключил «Чаты пунктов у меня»
            ArrayList<Msg> keep = new ArrayList<Msg>();
            for (Msg m : asc) if (!isPoint(m.tool)) keep.add(m);
            asc = keep;
        }
        // Голосовые, записанные в открытой переписке по инструменту, — метка на телефоне и (если есть SQL) в базе.
        if (seenReady && my.equals(msgsFor)) {
            List<String> tag = voiceToTag(asc, my, partner, tool, seenIds, localTags);
            if (!tag.isEmpty()) {
                synchronized (localTags) { for (String id : tag) localTags.put(id, tool); }
                if (isLend(tool) ? !topicTagMissing : isPoint(tool) ? !pointTagMissing : !tagMissing) {
                    try {
                        JSONObject b = new JSONObject();
                        JSONArray ids = new JSONArray();
                        for (String id : tag) ids.put(id);
                        b.put("p_ids", ids);
                        if (isPoint(tool)) {                // 10.120: переписка пункта — пункт
                            b.put("p_point", IvaProkatPatch.idOf(clean(tool)));
                            String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_tag_my_point", "POST", b.toString(), true);
                            if (r != null && "404".equals(r[0])) pointTagMissing = true;
                        } else if (isLend(tool)) {          // «Хочу сдавать» — тема, а не инструмент (10.72)
                            b.put("p_topic", LEND);
                            String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_tag_my_topic", "POST", b.toString(), true);
                            if (r != null && "404".equals(r[0])) topicTagMissing = true;
                        } else {
                            b.put("p_tool", tool);
                            String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_tag_my_messages", "POST", b.toString(), true);
                            if (r != null && "404".equals(r[0])) tagMissing = true;
                        }
                    } catch (Throwable ignore) {}
                }
            }
        }
        if (adm) try { IvaStaffJournalPatch.chatLoad(a, asc, my); } catch (Throwable ignore) {}   // 10.107: кто из сотрудников ответил
        // Инструменты переписок (название и картинка)
        HashSet<String> need = new HashSet<String>();
        Map<String, String> tools = assignTools(asc, my, adminEmails(), sup, localTags);
        HashSet<String> needPt = new HashSet<String>();
        for (String t : tools.values()) if (!t.isEmpty() && !isLend(t) && !toolCache.containsKey(t) && !toolMissing.contains(t)) (isPoint(t) ? needPt : need).add(t);
        if (!tool.isEmpty() && !isLend(tool) && !toolCache.containsKey(tool) && !toolMissing.contains(tool)) (isPoint(tool) ? needPt : need).add(tool);
        int ptLeft = 10;                                           // 10.120: название и аватарка пункта — по пунктам проката
        for (String t : needPt) {
            if (ptLeft-- <= 0) break;
            String pid = IvaProkatPatch.idOf(t);
            List<IvaProkatPatch.PP> ps = IvaProkatPatch.fetch(a, pid);
            IvaProkatPatch.PP pp = IvaProkatPatch.find(ps, pid);
            if (pp != null) { JSONObject o = IvaProkatPatch.chatObj(pid, pp); if (o != null) toolCache.put(t, o); }
            else if (ps != null) toolMissing.add(t);
        }
        if (!need.isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (String t : need) { if (ids.length() > 0) ids.append(','); ids.append(enc(t)); }
            try {
                JSONArray tr = getArr(a, "/rest/v1/tools?select=id,name,img,imgs&id=in.(" + ids + ")");
                for (int i = 0; i < tr.length(); i++) {
                    JSONObject o = tr.optJSONObject(i);
                    if (o != null && !toolIdOf(o).isEmpty()) synchronized (toolCache) { toolCache.put(toolIdOf(o), o); }
                }
                for (String t : need) if (!toolCache.containsKey(t)) toolMissing.add(t);
            } catch (Throwable ignore) {}
        }
        if (adm) {
            if (now - ordersAt > CACHE_MS) {
                try {
                    JSONArray or = getArr(a, "/rest/v1/orders?select=name,user_email&order=created_at.desc&limit=300");
                    HashMap<String, String> names = new HashMap<String, String>();
                    for (int i = 0; i < or.length(); i++) {
                        JSONObject o = or.optJSONObject(i);
                        if (o == null) continue;
                        String e = low(o.optString("user_email", "")), n = clean(o.optString("name", ""));
                        if (!e.isEmpty() && !n.isEmpty() && !names.containsKey(e)) names.put(e, n);
                    }
                    synchronized (clientNames) { clientNames.clear(); clientNames.putAll(names); }
                } catch (Throwable ignore) {}
                ordersAt = now;
            }
            if (!peersMissing && now - peersAt > CACHE_MS) {
                peersAt = now;
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_chat_peers", "POST", "{}", true);
                    if (r != null && "404".equals(r[0])) peersMissing = true;
                    else if (ok(r) && r.length > 1 && r[1] != null && r[1].trim().startsWith("[")) {
                        JSONArray pr = new JSONArray(r[1].trim());
                        synchronized (peerPaths) {
                            for (int i = 0; i < pr.length(); i++) {
                                JSONObject o = pr.optJSONObject(i);
                                if (o == null) continue;
                                String e = low(o.optString("email", "")), p = clean(o.optString("avatar_path", ""));
                                if (!e.isEmpty() && !p.isEmpty()) peerPaths.put(e, p);
                            }
                        }
                    }
                } catch (Throwable ignore) {}
            }
        }
        synchronized (seenIds) {
            for (Msg m : asc) seenIds.add(m.id);
        }
        seenReady = true;
        me = my;
        admin = adm;
        msgs = asc;
        msgsFor = my;
        msgsReady = true;
    }

    /** Данные загружены и относятся к текущему аккаунту (после смены входа — не показываем чужое). */
    static boolean ready(String my) {
        return msgsReady && low(my).equals(msgsFor);
    }

    static Set<String> adminEmails() {
        synchronized (admins) { return new HashSet<String>(admins.keySet()); }
    }

    // ───────────────────────── 6. Рисование ─────────────────────────

    static String signature(Activity a) {
        StringBuilder s = new StringBuilder();
        s.append(loggedIn(a)).append('|').append(openPartner).append('|').append(openTool).append('|')
            .append(ready(str(IvaClientPatch.get(a, "userEmail")))).append('|').append(lastError).append('|').append(toolCache.size()).append('|')
            .append(admins.size()).append('|').append(clientNames.size()).append('|').append(peerBitmaps.size())
            .append('|').append(localTags.size()).append('|').append(IvaStaffJournalPatch.chatVer)   // 10.107: имена сотрудников под ответами
            .append('|').append(hiddenVer).append('|');
        for (Msg m : msgs) s.append(m.id).append(m.read ? '+' : '-').append(m.tool).append(',');
        return s.toString();
    }

    static void render(Activity a) {
        if (root == null || header == null || body == null) return;
        Object sc = IvaClientPatch.get(a, "chatScroll");
        if (!loggedIn(a)) {
            // Без входа — штатный текст «Войдите в Кабинете…»
            root.setVisibility(View.GONE);
            if (sc instanceof View) ((View) sc).setVisibility(View.VISIBLE);
            renderedSig = "";
            return;
        }
        root.setVisibility(View.VISIBLE);
        if (sc instanceof View) ((View) sc).setVisibility(View.GONE);
        try { applyNote(a); } catch (Throwable ignore) {}
        String sig = signature(a);
        if (sig.equals(renderedSig)) return;
        renderedSig = sig;
        boolean keepBottom = stickBottom;
        header.removeAllViews();
        body.removeAllViews();
        String my = low(str(IvaClientPatch.get(a, "userEmail")));
        if (!openPartner.isEmpty()) {
            header.setVisibility(View.VISIBLE);
            renderHeader(a);
            flashRow = null;
            renderThread(a, my);
            if (flashRow != null) {          // открыли по уведомлению — к первому непрочитанному, с подсветкой
                flashMsg = "";
                IvaNotifyPatch.reveal(flashRow);
                flashRow = null;
            } else if (keepBottom) toBottom();
        } else {
            header.setVisibility(View.GONE);
            renderList(a, my);
        }
    }

    static void toBottom() {
        final ScrollView s = scroll;
        if (s == null) return;
        s.post(new Runnable() {
            @Override public void run() {
                if (s.getChildCount() > 0) s.scrollTo(0, Math.max(0, s.getChildAt(0).getHeight() - s.getHeight()));
            }
        });
    }

    static void renderHeader(final Activity a) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        setBg(a, card, "bg_card");
        card.setPadding(dp(a, 10), dp(a, 10), dp(a, 12), dp(a, 10));
        String small, title;
        String myH = low(str(IvaClientPatch.get(a, "userEmail")));
        if (isLend(openTool)) {                          // 10.72: «Хочу сдавать» — у владельца смайлик, у клиента аватарка владельца
            card.addView(lendSmile(myH, openTool) ? smiley(a) : avatar(a, openPartner, partnerTitle(openPartner), HEAD_TOOL_DP),
                new LinearLayout.LayoutParams(dp(a, HEAD_TOOL_DP), dp(a, HEAD_TOOL_DP)));
            small = LEND_TITLE;
            title = partnerTitle(openPartner);
        } else if (!openTool.isEmpty()) {
            JSONObject t = toolCache.get(openTool);
            card.addView(toolPic(a, t, openTool, HEAD_TOOL_DP), new LinearLayout.LayoutParams(dp(a, HEAD_TOOL_DP), dp(a, HEAD_TOOL_DP)));
            boolean pt = isPoint(openTool);
            small = (pt ? "Чат с пунктом выдачи · " : "Чат по инструменту · ") + partnerTitle(openPartner);
            title = t == null ? (pt ? IvaProkatPatch.chatTitle("") : "Инструмент") : clean(t.optString("name", "Инструмент"));
        } else {
            card.addView(avatar(a, openPartner, partnerTitle(openPartner), HEAD_TOOL_DP),
                new LinearLayout.LayoutParams(dp(a, HEAD_TOOL_DP), dp(a, HEAD_TOOL_DP)));
            small = supportChat(myH, openPartner, adminEmails(), openTool) ? SUPPORT_SUB : "Общий чат";
            title = partnerTitle(openPartner);
        }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView s = text(a, small, 11, "gray2", false);
        s.setSingleLine(true); s.setEllipsize(TextUtils.TruncateAt.END);
        TextView n = text(a, title.isEmpty() ? "Инструмент" : title, 15, "text", true);
        n.setMaxLines(2); n.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(s); col.addView(n);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 12);
        card.addView(col, cl);
        if (adminMark(myH, openPartner, adminEmails())) {        // 10.78: у создателя справа — «Админ»
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(a, 26));
            bl.leftMargin = dp(a, 10);
            card.addView(adminBadge(a), bl);
        }
        header.addView(card, new LinearLayout.LayoutParams(-1, -2));
        TextView back = text(a, BACK_TITLE, 15, "orange", true);
        back.setPadding(dp(a, 2), dp(a, 10), dp(a, 12), dp(a, 6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { closeThread(a); } catch (Throwable e) { fallback(a); }
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(-2, -2));
    }

    static String partnerTitle(String email) {
        String e = low(email);
        String[] ad;
        synchronized (admins) { ad = admins.get(e); }
        if (ad != null) return ad[0];
        if (OWNER.equals(e)) return "Владелец";
        String n;
        synchronized (clientNames) { n = clientNames.get(e); }
        if (n != null && !n.isEmpty()) return n;
        return clean(email).isEmpty() ? "Прокат" : clean(email);
    }

    static void renderThread(final Activity a, String my) {
        if (!ready(my)) { body.addView(hint(a, "Загрузка…")); return; }
        boolean sup = superView(my);
        Set<String> ad = adminEmails();
        List<Msg> list = thread(msgs, my, ad, sup, localTags, openPartner, openTool);
        if (list.isEmpty()) {
            body.addView(hint(a, isLend(openTool) ? LEND_HINT : isPoint(openTool)
                ? "Напишите вопрос пункту выдачи — ответит прокат. После первого сообщения чат появится в списке «Чаты»."
                : !openTool.isEmpty()
                ? "Напишите вопрос об этом инструменте — после первого сообщения чат появится в списке «Чаты»."
                : "Сообщений пока нет. Напишите первое сообщение."));
            return;
        }
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        long lastDay = Long.MIN_VALUE;
        for (Msg m : list) {
            long day = m.at < 0 ? lastDay : dayIndex(m.at, tz);
            if (m.at >= 0 && day != lastDay) {
                TextView d = text(a, dayLabel(m.at, now, tz), 12, "gray2", false);
                d.setGravity(Gravity.CENTER);
                d.setPadding(0, dp(a, 8), 0, dp(a, 8));
                body.addView(d, new LinearLayout.LayoutParams(-1, -2));
                lastDay = day;
            }
            View br = bubbleRow(a, m, ours(m, my, ad, sup), low(m.from).equals(my), tz);
            if (!flashMsg.isEmpty() && flashMsg.equals(m.id)) flashRow = br;
            body.addView(br);
        }
        if (!lastError.isEmpty()) body.addView(hint(a, lastError));
    }

    /** 10.62: скрытые у меня сообщения — копия с телефона при первой загрузке аккаунта, затем из базы. */
    static void loadHiddenMsgs(Activity a, String my, long now) {
        if (!my.equals(hiddenMsgsFor)) {
            hiddenMsgsFor = my;
            try {
                Set<String> cached = msgIdsFromStr(a.getSharedPreferences(HIDDEN_MSG_PREFS, 0).getString(my, ""));
                if (!cached.isEmpty()) { hiddenMsgs.addAll(cached); hiddenVer++; }
            } catch (Throwable ignore) {}
        }
        if (now - hiddenMsgsAt <= (hiddenMsgsMissing ? HIDDEN_RETRY_MS : CACHE_MS)) return;
        hiddenMsgsAt = now;
        try {
            String[] r = IvaClientPatch.apiCall(a, "/rest/v1/app_msg_hidden?select=msg_id&limit=5000", "GET", null, true);
            if (r != null && "404".equals(r[0])) hiddenMsgsMissing = true;   // SQL ещё не применён
            else if (ok(r)) {
                hiddenMsgsMissing = false;
                String t = r.length > 1 && r[1] != null ? r[1].trim() : "";
                Set<String> next = parseHiddenMsgs(t.startsWith("[") ? new JSONArray(t) : new JSONArray());
                synchronized (hiddenMsgsLocal) { next.addAll(hiddenMsgsLocal); }
                synchronized (hiddenMsgs) {
                    if (!hiddenMsgs.equals(next)) { hiddenMsgs.clear(); hiddenMsgs.addAll(next); hiddenVer++; }
                }
                saveHiddenMsgs(a, my);
            } else hiddenMsgsAt = 0;
        } catch (Throwable e) { hiddenMsgsAt = 0; }
    }

    static View bubbleRow(final Activity a, final Msg m, boolean mine, final boolean deletable, TimeZone tz) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(mine ? Gravity.END | Gravity.BOTTOM : Gravity.START | Gravity.BOTTOM);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = dp(a, 8);
        row.setLayoutParams(rl);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(mine ? Gravity.END : Gravity.START);
        int maxW = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.68f);

        if (!m.img.isEmpty()) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            setBg(a, iv, "bg_photo");
            round(iv, dp(a, 12), false);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(a, 150), dp(a, 110));
            il.gravity = mine ? Gravity.END : Gravity.START;
            il.bottomMargin = dp(a, 4);
            col.addView(iv, il);
            loadPhoto(a, iv, m.img, "msg" + m.id);
        }
        View voice = null;
        if (!m.audio.isEmpty()) {
            voice = voiceBubble(a, m, mine);
            col.addView(voice, new LinearLayout.LayoutParams(-2, -2));   // по своему размеру (без этого сжимался до ширины времени)
        } else if (!m.text.isEmpty()) {
            TextView t = text(a, m.text, 15, mine ? "orange" : "text", false);   // 10.96: свой пузырь — рамкой, текст оранжевый
            setBg(a, t, mine ? "bg_bubble_me" : "bg_bubble_other");
            t.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
            t.setMaxWidth(maxW);
            t.setTextIsSelectable(false);
            col.addView(t, new LinearLayout.LayoutParams(-2, -2));
        }
        String time = hm(m.at, tz);
        if (mine) time = time + (m.read ? "  ✓✓" : "  ✓");
        if (mine) try { String sw = IvaStaffJournalPatch.who(m.id); if (!sw.isEmpty()) time = sw + " · " + time; } catch (Throwable ignore) {}   // 10.107: ответил сотрудник
        LinearLayout meta = new LinearLayout(a);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView tt = text(a, time, 10, mine && m.read ? "orange" : "gray2", false);
        meta.addView(tt);
        // 10.62: долгое нажатие — на любом сообщении («Удалить у меня»; у своих ещё «Удалить у всех»), × — только у своих
        if (!m.id.isEmpty()) {
            if (deletable) {
                TextView x = text(a, "×", 14, "gray2", true);
                x.setPadding(dp(a, 8), 0, dp(a, 2), 0);
                x.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmDelete(a, m, true); }
                });
                meta.addView(x);
            }
            View.OnLongClickListener hold = new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { confirmDelete(a, m, deletable); return true; }
            };
            col.setOnLongClickListener(hold);
            if (voice != null) voice.setOnLongClickListener(hold);   // пузырь голосового сам ловит нажатия
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = dp(a, 2);
        col.addView(meta, ml);

        View ava = avatar(a, low(m.from), senderTitle(m), MSG_AVATAR_DP);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(a, MSG_AVATAR_DP), dp(a, MSG_AVATAR_DP));
        al.bottomMargin = dp(a, 16);
        if (mine) {
            al.leftMargin = dp(a, 6);
            row.addView(col, new LinearLayout.LayoutParams(-2, -2));
            row.addView(ava, al);
        } else {
            al.rightMargin = dp(a, 6);
            row.addView(ava, al);
            row.addView(col, new LinearLayout.LayoutParams(-2, -2));
        }
        return row;
    }

    static String senderTitle(Msg m) {
        String f = low(m.from);
        if (f.equals(me) && !admin) {
            String n = clean(str(act.get() == null ? null : IvaClientPatch.get(act.get(), "userName")));
            return n.isEmpty() ? "Я" : n;
        }
        return partnerTitle(f);
    }

    // ───────────────────────── Голосовые (10.60: как в ВК, цвета приложения) ─────────────────────────
    // Пузырь как у текста (свои — оранжевый bg_bubble_me, чужие — тёмный bg_bubble_other), слева круглая
    // кнопка ▶/❚❚, справа «волна» по настоящей громкости записи (считается из файла один раз, хранится
    // в кэше рядом с ним), под волной — длительность, во время воспроизведения — сколько прошло.
    // Нажатие на волну — перемотка. Ширина волны растёт с длительностью.
    // 10.58→10.60: пузырь добавлялся в колонку без размеров (по умолчанию «во всю ширину колонки»),
    // а колонка без своей ширины берёт её по подписи времени — пузырь сжимался до «01:57».
    // Воспроизведение (10.58): файл сначала скачивается в кэш, затем prepareAsync; состояние привязано
    // к id сообщения, а не к View — перерисовка чата не «отрывает» кнопку и волну от играющего плеера.

    static final int VOICE_TICK_MS = 100;
    static final int VOICE_PEAKS = 64;                // столько уровней громкости хранится; на экране — сколько столбиков влезает
    static final int VOICE_BTN_DP = 40;
    static final float VOICE_BAR_DP = 2f, VOICE_GAP_DP = 1.5f, VOICE_WAVE_H_DP = 24f, VOICE_BAR_MIN_DP = 2f;
    static final int VOICE_REST_OTHER = 0x59FFFFFF;   // на тёмном

    static android.media.MediaPlayer vMp;
    static String vKey = "";             // id сообщения (или ссылка), которое сейчас играет/грузится
    static boolean vLoading;             // идёт скачивание/подготовка
    static boolean vPlaying;
    static float vSeek = -1f;            // перемотка (доля), которую сделать, когда файл подготовится
    static WeakReference<VoiceUi> vUi = new WeakReference<VoiceUi>(null);
    static final Map<String, Integer> vDur = new HashMap<String, Integer>();   // ссылка → секунды
    static final Set<String> vDurAsked = new HashSet<String>();
    static final Map<String, float[]> vPeaks = new HashMap<String, float[]>(); // ссылка → громкость 0…1
    static final Set<String> vPeaksAsked = new HashSet<String>();
    static final Map<String, WeakReference<VoiceUi>> vShown = new HashMap<String, WeakReference<VoiceUi>>(); // ссылка → её пузырь на экране
    static final java.util.concurrent.ExecutorService vWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    static final class VoiceUi {
        VoiceBtn btn;
        VoiceWave wave;
        TextView dur;
        int total;                       // секунды, 0 — пока не знаем
    }

    /** Ширина волны, как в ВК: длиннее запись — длиннее волна. 72dp + 3dp/с, но не шире 190dp и 42% экрана. */
    static int voiceWavePx(int sec, int screenW, float density) {
        int min = Math.round(72 * density);
        int max = Math.max(min, Math.min(Math.round(190 * density), Math.round(screenW * 0.42f)));
        return Math.max(min, Math.min(max, Math.round((72 + 3 * Math.max(0, sec)) * density)));
    }

    /** Сколько столбиков влезает в ширину. */
    static int voiceBarCount(int widthPx, float bar, float gap) {
        if (widthPx <= 0 || bar <= 0) return 0;
        return Math.max(1, (int) ((widthPx + gap) / (bar + gap)));
    }

    /** n столбиков из сохранённых уровней (максимум по своему участку). Нет данных — нули (ровная линия). */
    static float[] voiceBars(float[] peaks, int n) {
        float[] r = new float[Math.max(0, n)];
        if (peaks == null || peaks.length == 0 || n <= 0) return r;
        for (int i = 0; i < n; i++) {
            int from = (int) ((long) i * peaks.length / n), to = (int) ((long) (i + 1) * peaks.length / n);
            if (to <= from) to = from + 1;
            float m = 0;
            for (int k = from; k < to && k < peaks.length; k++) m = Math.max(m, peaks[k]);
            r[i] = m;
        }
        return r;
    }

    /** Уровни громкости: блоки ~10 мс (максимум модуля отсчёта) → n участков (среднее) → 0…1 от самого громкого, тихое чуть приподнято. */
    static float[] voicePeaks(int[] blocks, int count, int n) {
        if (n <= 0) return new float[0];
        float[] r = new float[n];
        if (blocks == null || count <= 0) return r;
        count = Math.min(count, blocks.length);
        float max = 0;
        for (int i = 0; i < n; i++) {
            int from = (int) ((long) i * count / n), to = (int) ((long) (i + 1) * count / n);
            if (to <= from) to = from + 1;
            long sum = 0;
            int c = 0;
            for (int k = from; k < to && k < count; k++) { sum += Math.abs(blocks[k]); c++; }
            r[i] = c == 0 ? 0f : sum / (float) c;
            max = Math.max(max, r[i]);
        }
        if (max <= 0) return new float[n];
        for (int i = 0; i < n; i++) r[i] = (float) Math.pow(r[i] / max, 0.7);
        return r;
    }

    /** Уровни ↔ байты для кэша (байт на уровень). Чужая длина — null. */
    static byte[] voicePeaksBytes(float[] p) {
        byte[] b = new byte[p == null ? 0 : p.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Math.round(Math.max(0f, Math.min(1f, p[i])) * 255);
        return b;
    }

    static float[] voicePeaksFrom(byte[] b) {
        if (b == null || b.length != VOICE_PEAKS) return null;
        float[] p = new float[b.length];
        for (int i = 0; i < b.length; i++) p[i] = (b[i] & 0xFF) / 255f;
        return p;
    }

    /** Перемотка: доля волны → мс (не в самый конец, чтобы не закончилось сразу). */
    static int voiceSeekMs(float frac, int durMs) {
        if (durMs <= 0) return 0;
        float f = Math.max(0f, Math.min(1f, frac));
        return Math.min(Math.round(f * durMs), Math.max(0, durMs - 100));
    }

    /** Подпись под волной: идёт/на паузе — сколько прошло, иначе длительность. */
    static String voiceTimeLabel(int posMs, int totalSec) {
        if (posMs > 0) return durLabel(posMs / 1000);
        return totalSec > 0 ? durLabel(totalSec) : "";
    }

    /** Доля прослушанного. */
    static float voiceProgress(int posMs, int durMs) {
        if (durMs <= 0 || posMs <= 0) return 0f;
        return Math.min(1f, posMs / (float) durMs);
    }

    static String voiceKey(Msg m) {
        return m.id.isEmpty() ? m.audio : m.id;
    }

    /** Имя файла в кэше: из ссылки, только безопасные символы. */
    static String voiceCacheName(String url) {
        String u = clean(url);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        String tail = u.substring(u.lastIndexOf('/') + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < tail.length() && b.length() < 80; i++) {
            char c = tail.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        String n = b.toString();
        if (n.isEmpty() || n.startsWith(".")) n = "v" + n;
        return Integer.toHexString(u.hashCode()) + "-" + n;
    }

    /** Круглая кнопка: ▶, ❚❚ или крутящееся кольцо, пока файл скачивается. */
    static final class VoiceBtn extends View {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path path = new android.graphics.Path();
        final android.graphics.RectF rf = new android.graphics.RectF();
        final int fg;
        int state;                       // 0 ▶, 1 ❚❚, 2 загрузка

        /** 10.96: кружок «рамкой» (1,5dp) и значок — одним фирменным цветом. */
        VoiceBtn(android.content.Context c, int fg) { super(c); this.fg = fg; }

        void setState(int s) { if (s != state) { state = s; invalidate(); } }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f, cx = w / 2f, cy = h / 2f;
            float sw = Math.max(1f, IvaPatch.OUTLINE_DP * getResources().getDisplayMetrics().density);
            p.setPathEffect(null);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(fg);
            c.drawCircle(cx, cy, r - sw / 2f, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            if (state == 1) {
                float bw = r * 0.2f, bh = r * 0.78f, g = r * 0.14f;
                rf.set(cx - g - bw, cy - bh / 2, cx - g, cy + bh / 2);
                c.drawRoundRect(rf, bw / 3, bw / 3, p);
                rf.set(cx + g, cy - bh / 2, cx + g + bw, cy + bh / 2);
                c.drawRoundRect(rf, bw / 3, bw / 3, p);
            } else if (state == 2) {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.13f);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                float rr = r * 0.48f;
                rf.set(cx - rr, cy - rr, cx + rr, cy + rr);
                c.drawArc(rf, (android.os.SystemClock.uptimeMillis() % 1000) * 0.36f, 270, false, p);
                postInvalidateOnAnimation();
            } else {
                float tw = r * 0.7f, th = r * 0.8f, x0 = cx - tw / 3f;   // центр тяжести треугольника — в центре круга
                path.reset();
                path.moveTo(x0, cy - th / 2);
                path.lineTo(x0 + tw, cy);
                path.lineTo(x0, cy + th / 2);
                path.close();
                p.setPathEffect(new android.graphics.CornerPathEffect(r * 0.1f));
                c.drawPath(path, p);
                p.setPathEffect(null);
            }
        }
    }

    /** Волна: столбики по громкости, прослушанная часть ярче. */
    static final class VoiceWave extends View {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF rf = new android.graphics.RectF();
        final int played, rest;
        final float bar, gap, minH;
        float[] peaks;
        float progress;

        VoiceWave(android.content.Context c, float[] peaks, int played, int rest, float density) {
            super(c);
            this.peaks = peaks; this.played = played; this.rest = rest;
            bar = VOICE_BAR_DP * density; gap = VOICE_GAP_DP * density; minH = VOICE_BAR_MIN_DP * density;
        }

        void setPeaks(float[] pk) { peaks = pk; invalidate(); }

        void setProgress(float f) {
            f = Math.max(0f, Math.min(1f, f));
            if (f != progress) { progress = f; invalidate(); }
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            int n = voiceBarCount(getWidth(), bar, gap);
            float[] b = voiceBars(peaks, n);
            float h = getHeight(), cy = h / 2f;
            for (int i = 0; i < n; i++) {
                float bh = Math.max(minH, b[i] * h), x = i * (bar + gap);
                p.setColor((i + 0.5f) / n <= progress ? played : rest);
                rf.set(x, cy - bh / 2, x + bar, cy + bh / 2);
                c.drawRoundRect(rf, bar / 2, bar / 2, p);
            }
        }
    }

    static View voiceBubble(final Activity a, final Msg m, boolean mine) {
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        float den = a.getResources().getDisplayMetrics().density;
        int accent = color(a, "orange");
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        setBg(a, v, mine ? "bg_bubble_me" : "bg_bubble_other");   // как у текста: свои — оранжевой рамкой (10.96), чужие — тёмные
        v.setPadding(dp(a, 8), dp(a, 8), dp(a, 14), dp(a, 8));

        final VoiceUi ui = new VoiceUi();
        ui.btn = new VoiceBtn(a, accent);                          // 10.96: кружок «рамкой», значок оранжевый — у всех
        v.addView(ui.btn, new LinearLayout.LayoutParams(dp(a, VOICE_BTN_DP), dp(a, VOICE_BTN_DP)));

        int known = m.dur;
        if (known <= 0) {
            Integer c;
            synchronized (vDur) { c = vDur.get(m.audio); }
            if (c != null) known = c;
        }
        ui.total = known;
        float[] pk;
        synchronized (vPeaks) { pk = vPeaks.get(m.audio); }
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        ui.wave = new VoiceWave(a, pk, accent, VOICE_REST_OTHER, den);   // 10.96: пузырь тёмный у всех
        col.addView(ui.wave, new LinearLayout.LayoutParams(voiceWavePx(known, sw, den), dp(a, (int) VOICE_WAVE_H_DP)));
        ui.dur = text(a, known > 0 ? durLabel(known) : "", 12, mine ? "orange" : "gray", false);
        ui.dur.setSingleLine(true);
        if (mine) ui.dur.setAlpha(0.85f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = dp(a, 2);
        col.addView(ui.dur, dl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = dp(a, 10);
        v.addView(col, cl);

        final String key = voiceKey(m);
        synchronized (vShown) { vShown.put(m.audio, new WeakReference<VoiceUi>(ui)); }
        if (key.equals(vKey)) {
            // Перерисовка во время воспроизведения — подхватываем новые View.
            vUi = new WeakReference<VoiceUi>(ui);
            voiceShow(a);
        }
        if (known <= 0) voiceAskDuration(a, m.audio);
        if (pk == null) voiceAskWave(a, m.audio);

        View.OnClickListener tap = new View.OnClickListener() {
            @Override public void onClick(View x) { voiceTap(a, key, m.audio, ui); }
        };
        v.setOnClickListener(tap);
        ui.btn.setOnClickListener(tap);
        ui.wave.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View w, android.view.MotionEvent e) {
                int ac = e.getActionMasked();
                if (ac == android.view.MotionEvent.ACTION_UP) {          // прокрутку чата пальцем отдаёт CANCEL — тогда не перематываем
                    voiceSeek(a, key, m.audio, ui, e.getX() / Math.max(1, w.getWidth()));
                    return true;
                }
                return ac == android.view.MotionEvent.ACTION_DOWN || ac == android.view.MotionEvent.ACTION_MOVE;
            }
        });
        return v;
    }

    /** Кнопка, волна и подпись текущего голосового — по состоянию плеера. */
    static void voiceShow(Activity a) {
        VoiceUi u = vUi.get();
        if (u == null) return;
        u.btn.setState(vLoading ? 2 : vPlaying ? 1 : 0);
        int pos = -1, d = 0;
        try {
            if (vMp != null && !vLoading) { d = vMp.getDuration(); pos = vMp.getCurrentPosition(); }
        } catch (Throwable ignore) {}
        u.wave.setProgress(voiceProgress(pos, d));
        u.dur.setText(voiceTimeLabel(pos, u.total));
    }

    static void voiceReset(VoiceUi u) {
        if (u == null) return;
        u.btn.setState(0);
        u.wave.setProgress(0f);
        u.dur.setText(voiceTimeLabel(-1, u.total));
    }

    static final Runnable V_TICK = new Runnable() {
        @Override public void run() {
            Activity a = act.get();
            if (a == null || vMp == null || !vPlaying) return;
            voiceShow(a);
            handler.postDelayed(this, VOICE_TICK_MS);
        }
    };

    static void voiceTap(final Activity a, final String key, final String url, VoiceUi ui) {
        vUi = new WeakReference<VoiceUi>(ui);
        if (key.equals(vKey)) {
            if (vLoading) { voiceShow(a); return; }      // ещё скачивается — ждём
            if (vMp != null) {
                try {
                    if (vPlaying) {
                        vMp.pause();
                        vPlaying = false;
                        handler.removeCallbacks(V_TICK);
                    } else {
                        vMp.start();
                        vPlaying = true;
                        handler.removeCallbacks(V_TICK);
                        handler.post(V_TICK);
                    }
                } catch (Throwable e) {
                    voiceStop(a);
                    Toast.makeText(a, "Не удалось воспроизвести", Toast.LENGTH_SHORT).show();
                    return;
                }
                voiceShow(a);
                return;
            }
        }
        // Другое голосовое: старое останавливаем, новое скачиваем и играем.
        voiceStop(a);
        vUi = new WeakReference<VoiceUi>(ui);
        vKey = key;
        vLoading = true;
        voiceShow(a);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                java.io.File f = null;
                try { f = voiceFile(a, url); } catch (Throwable e) { err = e.getMessage(); }
                final java.io.File ff = f;
                final String fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!key.equals(vKey)) return;  // пока качали, нажали другое
                        if (ff == null) {
                            voiceStop(a);
                            Toast.makeText(a, "Голосовое не загрузилось" + (fe == null || fe.isEmpty() ? "" : " (" + fe + ")"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        voiceStart(a, key, url, ff);
                    }
                });
            }
        }).start();
    }

    /** Нажали на волну: перемотать туда (и играть); не играло — включить с этого места. */
    static void voiceSeek(Activity a, String key, String url, VoiceUi ui, float frac) {
        if (key.equals(vKey) && vMp != null && !vLoading) {
            vUi = new WeakReference<VoiceUi>(ui);
            try {
                vMp.seekTo(voiceSeekMs(frac, vMp.getDuration()));
                if (!vPlaying) {
                    vMp.start();
                    vPlaying = true;
                    handler.removeCallbacks(V_TICK);
                    handler.post(V_TICK);
                }
            } catch (Throwable e) {
                voiceStop(a);
                return;
            }
            voiceShow(a);
            return;
        }
        if (!key.equals(vKey)) voiceTap(a, key, url, ui);
        vSeek = frac;                                    // применится, когда файл подготовится
    }

    static void voiceStart(final Activity a, final String key, final String url, final java.io.File f) {
        final android.media.MediaPlayer mp = new android.media.MediaPlayer();
        vMp = mp;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            }
            mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(android.media.MediaPlayer p) {
                    if (vMp != p || !key.equals(vKey)) { try { p.release(); } catch (Throwable ignore) {} return; }
                    try {
                        int sec = Math.max(1, p.getDuration() / 1000);
                        synchronized (vDur) { vDur.put(url, sec); }
                        if (vSeek >= 0) p.seekTo(voiceSeekMs(vSeek, p.getDuration()));
                        vSeek = -1f;
                        p.start();
                        vLoading = false;
                        vPlaying = true;
                        voiceShow(a);
                        handler.removeCallbacks(V_TICK);
                        handler.post(V_TICK);
                    } catch (Throwable e) {
                        voiceFail(a, f, "");
                    }
                }
            });
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer p) {
                    if (vMp == p) voiceStop(a);
                }
            });
            mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override public boolean onError(android.media.MediaPlayer p, int what, int extra) {
                    if (vMp == p) voiceFail(a, f, what + "/" + extra);
                    return true;
                }
            });
            mp.setDataSource(f.getAbsolutePath());
            mp.prepareAsync();
        } catch (Throwable e) {
            voiceFail(a, f, e.getClass().getSimpleName());
        }
    }

    static void voiceFail(Activity a, java.io.File f, String code) {
        voiceStop(a);
        try { if (f != null) f.delete(); } catch (Throwable ignore) {}   // битый кэш — в следующий раз скачаем заново
        Toast.makeText(a, "Не удалось воспроизвести" + (code == null || code.isEmpty() ? "" : " (" + code + ")"), Toast.LENGTH_LONG).show();
    }

    /** Остановить и сбросить кнопку/полоску. */
    static void voiceStop(Activity a) {
        if (handler != null) handler.removeCallbacks(V_TICK);
        android.media.MediaPlayer mp = vMp;
        vMp = null;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignore) {}
            try { mp.release(); } catch (Throwable ignore) {}
        }
        vPlaying = false;
        vLoading = false;
        vKey = "";
        vSeek = -1f;
        voiceReset(vUi.get());
        vUi = new WeakReference<VoiceUi>(null);
    }

    /** Файл голосового в кэше приложения; если нет — скачать. Вызывать НЕ из UI-потока. */
    static java.io.File voiceFile(Activity a, String url) throws Exception {
        java.io.File dir = new java.io.File(a.getCacheDir(), "iva_voice");
        if (!dir.isDirectory()) dir.mkdirs();
        java.io.File f = new java.io.File(dir, voiceCacheName(url));
        if (f.isFile() && f.length() > 0) return f;
        // своё временное имя: кнопка и фоновая волна могут качать один файл одновременно
        java.io.File tmp = new java.io.File(dir, f.getName() + "." + System.nanoTime() + ".part");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(clean(url)).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception(String.valueOf(code));
            java.io.InputStream in = c.getInputStream();
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { out.close(); } catch (Throwable ignore) {}
                try { in.close(); } catch (Throwable ignore) {}
            }
        } finally {
            c.disconnect();
        }
        if (tmp.length() <= 0) { tmp.delete(); throw new Exception("пустой файл"); }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            if (!(f.isFile() && f.length() > 0)) throw new Exception("не сохранился");
        }
        return f;
    }

    /** Старые голосовые без audio_dur: длительность узнаём в фоне (по одному), потом перерисовываем. */
    static void voiceAskDuration(final Activity a, final String url) {
        synchronized (vDurAsked) { if (!vDurAsked.add(url)) return; }
        try {
            vWorker.execute(new Runnable() {
                @Override public void run() {
                    int sec = 0;
                    android.media.MediaPlayer p = null;
                    try {
                        java.io.File f = voiceFile(a, url);
                        p = new android.media.MediaPlayer();
                        p.setDataSource(f.getAbsolutePath());
                        p.prepare();
                        sec = Math.max(1, p.getDuration() / 1000);
                    } catch (Throwable ignore) {
                    } finally {
                        if (p != null) try { p.release(); } catch (Throwable ignore) {}
                    }
                    if (sec <= 0) return;
                    synchronized (vDur) { vDur.put(url, sec); }
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (handler == null) return;
                            handler.removeCallbacks(V_RERENDER);       // несколько длительностей подряд — одна перерисовка
                            handler.postDelayed(V_RERENDER, 300);
                        }
                    });
                }
            });
        } catch (Throwable ignore) {}
    }

    /** Волна: уровни громкости из кэша рядом с файлом, иначе — разбор файла (в фоне, по одному). Готово — дорисовать на месте. */
    static void voiceAskWave(final Activity a, final String url) {
        synchronized (vPeaksAsked) { if (!vPeaksAsked.add(url)) return; }
        try {
            vWorker.execute(new Runnable() {
                @Override public void run() {
                    float[] p = null;
                    try {
                        java.io.File f = voiceFile(a, url);
                        java.io.File wf = new java.io.File(f.getPath() + ".wave");
                        if (wf.isFile() && wf.length() == VOICE_PEAKS) p = voicePeaksFrom(readBytes(wf));
                        if (p == null) {
                            p = voiceDecode(f);
                            if (p != null) {
                                java.io.FileOutputStream o = new java.io.FileOutputStream(wf);
                                try { o.write(voicePeaksBytes(p)); } finally { o.close(); }
                            }
                        }
                    } catch (Throwable ignore) {}
                    if (p == null) {                                   // не вышло — ровная линия; в следующий раз попробуем снова
                        synchronized (vPeaksAsked) { vPeaksAsked.remove(url); }
                        return;
                    }
                    final float[] fp = p;
                    synchronized (vPeaks) { vPeaks.put(url, fp); }
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            WeakReference<VoiceUi> r;
                            synchronized (vShown) { r = vShown.get(url); }
                            VoiceUi u = r == null ? null : r.get();
                            if (u != null) u.wave.setPeaks(fp);
                        }
                    });
                }
            });
        } catch (Throwable ignore) {}
    }

    static byte[] readBytes(java.io.File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Громкость записи: декодируем звук (MediaCodec) и берём максимум по блокам ~10 мс. Не вышло — null. */
    static float[] voiceDecode(java.io.File f) throws Exception {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaCodec dec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            android.media.MediaFormat fmt = null;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat t = ex.getTrackFormat(i);
                String mm = t.getString(android.media.MediaFormat.KEY_MIME);
                if (mm != null && mm.startsWith("audio/")) { ex.selectTrack(i); fmt = t; mime = mm; break; }
            }
            if (fmt == null) return null;
            int rate = fmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)) : 1;
            dec = android.media.MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();
            int block = Math.max(1, rate / 100);
            int[] blocks = new int[1024];
            int nb = 0, inBlock = 0, cur = 0;
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            long until = System.currentTimeMillis() + 20000;
            while (!outEos && System.currentTimeMillis() < until) {
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        java.nio.ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) ch = Math.max(1, of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT));
                    if (of.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) block = Math.max(1, of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) / 100);
                } else if (oi >= 0) {
                    java.nio.ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        java.nio.ShortBuffer sb = ob.slice().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                        int cnt = sb.remaining();
                        for (int k = 0; k + ch - 1 < cnt; k += ch) {
                            int smp = Math.abs((int) sb.get(k));
                            if (smp > cur) cur = smp;
                            if (++inBlock >= block) {
                                if (nb == blocks.length) blocks = java.util.Arrays.copyOf(blocks, nb * 2);
                                blocks[nb++] = cur;
                                cur = 0;
                                inBlock = 0;
                            }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                }
            }
            if (!outEos) return null;                          // не успели — лучше ровная линия, чем обрезанная волна
            if (inBlock > 0) {
                if (nb == blocks.length) blocks = java.util.Arrays.copyOf(blocks, nb + 1);
                blocks[nb++] = cur;
            }
            return nb == 0 ? null : voicePeaks(blocks, nb, VOICE_PEAKS);
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Throwable ignore) {}
                try { dec.release(); } catch (Throwable ignore) {}
            }
            try { ex.release(); } catch (Throwable ignore) {}
        }
    }

    static final Runnable V_RERENDER = new Runnable() {
        @Override public void run() {
            Activity a = act.get();
            if (a == null) return;
            renderedSig = "";
            render(a);
        }
    };

    /** 10.62: выбор «Удалить у меня» / «Удалить у всех» (у чужого сообщения — только «у меня»). */
    static void confirmDelete(final Activity a, final Msg m, final boolean own) {
        try {
            final String[] items = deleteChoices(own);
            // 10.63: окно в стиле приложения — тёмные пузыри с оранжевым текстом (вместо системного AlertDialog)
            IvaClientPatch.styledChoice(a, DEL_TITLE, deleteHint(own), items, new IvaClientPatch.Choice() {
                @Override public void pick(int w) {
                    if (w < 0 || w >= items.length) return;
                    if (DEL_ALL.equals(items[w])) deleteForAll(a, m);
                    else hideMsg(a, m);
                }
            });
        } catch (Throwable ignore) {}
    }

    /** «Удалить у всех» — как до 10.62: сообщение удаляется из базы (только своё — RLS и кнопка только у своих). */
    static void deleteForAll(final Activity a, final Msg m) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean done = false;
                try {
                    done = ok(IvaClientPatch.apiCall(a, "/rest/v1/app_messages?id=eq." + enc(m.id), "DELETE", null, true));
                } catch (Throwable ignore) {}
                final boolean fd = done;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!fd) Toast.makeText(a, "Не удалось удалить", Toast.LENGTH_SHORT).show();
                        callNative(a, "loadChat");
                        refresh(a);
                    }
                });
            }
        }).start();
    }

    /** «Удалить у меня»: запись в app_msg_hidden через iva_hide_msg (почта — из входа); у собеседника остаётся. */
    static void hideMsg(final Activity a, final Msg m) {
        if (hiddenMsgsMissing) { Toast.makeText(a, DEL_NO_DB, Toast.LENGTH_LONG).show(); return; }
        final String id = m.id;
        final String my = low(str(IvaClientPatch.get(a, "userEmail")));
        new Thread(new Runnable() {
            @Override public void run() {
                String code = "";
                try {
                    JSONObject b = new JSONObject();
                    b.put("p_id", id);
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_hide_msg", "POST", b.toString(), true);
                    code = r == null ? "" : String.valueOf(r[0]);
                    if (ok(r) && r.length > 1 && r[1] != null && r[1].trim().equals("1")) code = "ok";
                } catch (Throwable ignore) {}
                final String fc = code;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if ("ok".equals(fc)) {
                            hiddenMsgsLocal.add(id);
                            hiddenMsgs.add(id);
                            hiddenVer++;
                            saveHiddenMsgs(a, my);
                            renderedSig = "";
                            render(a);
                        } else if ("404".equals(fc)) {
                            hiddenMsgsMissing = true;
                            Toast.makeText(a, DEL_NO_DB, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(a, "Не удалось удалить — проверьте интернет", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    static void renderList(final Activity a, String my) {
        boolean adm = isAdmin(a);
        body.addView(sectionTitle(a, adm ? "Переписки" : "Чаты"));
        if (!ready(my)) {
            body.addView(hint(a, lastError.isEmpty() ? "Загрузка…" : lastError));
        } else {
            boolean sup = superView(my);
            List<Chat> chats = buildChats(msgs, my, adminEmails(), sup, localTags);
            if (chats.isEmpty()) {
                body.addView(hint(a, adm ? "Писем пока нет."
                    : "Чатов пока нет. Задайте вопрос в карточке инструмента или напишите прокату ниже."));
            }
            TimeZone tz = TimeZone.getDefault();
            for (Chat c : chats) body.addView(chatRow(a, c, my, tz));
            if (!lastError.isEmpty()) body.addView(hint(a, lastError));
        }
        if (!adm) {
            TextView t = sectionTitle(a, "Написать прокату");
            t.setPadding(0, dp(a, 18), 0, dp(a, 8));
            body.addView(t);
            ArrayList<String[]> list = new ArrayList<String[]>();
            synchronized (admins) {
                for (Map.Entry<String, String[]> e : admins.entrySet()) {
                    if (!"1".equals(e.getValue()[2])) continue;
                    String[] row = {e.getKey(), e.getValue()[0], e.getValue()[3]};
                    if (OWNER.equals(e.getKey())) list.add(0, row); else list.add(row);
                }
            }
            if (list.isEmpty()) body.addView(hint(a, ready(my) ? "Администраторы ещё не назначены." : "Загрузка…"));
            for (final String[] r : list) {
                LinearLayout row = rowBase(a);
                row.addView(avatar(a, r[0], r[1], LIST_AVATAR_DP), new LinearLayout.LayoutParams(dp(a, LIST_AVATAR_DP), dp(a, LIST_AVATAR_DP)));
                LinearLayout col = new LinearLayout(a);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView n = text(a, r[1], 15, "text", true);
                n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(n);
                col.addView(text(a, "Задать вопрос о товарах", 12, "gray2", false));
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
                cl.leftMargin = dp(a, 12);
                row.addView(col, cl);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            openThread(a, r[2].isEmpty() ? r[0] : r[2], "", null);
                            callNative(a, "loadChat");
                            render(a);
                        } catch (Throwable e) { fallback(a); }
                    }
                });
                body.addView(row);
            }
        }
    }

    static View chatRow(final Activity a, final Chat c, String my, TimeZone tz) {
        LinearLayout row = rowBase(a);
        String title = partnerTitle(c.partner);
        row.addView(avatar(a, c.partner, title, LIST_AVATAR_DP), new LinearLayout.LayoutParams(dp(a, LIST_AVATAR_DP), dp(a, LIST_AVATAR_DP)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(a, title, 15, "text", true);
        n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(n);
        String sub;
        JSONObject tool = c.tool.isEmpty() || isLend(c.tool) ? null : toolCache.get(c.tool);
        sub = supportChat(my, c.partner, adminEmails(), c.tool) ? SUPPORT_SUB : chatSub(c.tool, tool == null ? "" : tool.optString("name", ""));
        TextView tl = text(a, sub, 12, "orange", false);
        tl.setSingleLine(true); tl.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(tl);
        boolean mine = c.last != null && low(c.last.from).equals(my);
        TextView pv = text(a, preview(c.last, mine), 13, c.unread > 0 ? "text" : "gray", c.unread > 0);
        pv.setSingleLine(true); pv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(pv);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 12);
        cl.rightMargin = dp(a, 8);
        row.addView(col, cl);

        LinearLayout when = new LinearLayout(a);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setGravity(Gravity.END);
        long at = c.last == null ? -1 : c.last.at;
        when.addView(text(a, hm(at, tz), 12, c.unread > 0 ? "orange" : "gray2", c.unread > 0));
        when.addView(text(a, dmy(at, tz), 11, "gray2", false));
        if (c.unread > 0) {
            TextView badge = text(a, String.valueOf(Math.min(99, c.unread)), 11, "orange", true);   // 10.96: кружок рамкой
            badge.setGravity(Gravity.CENTER);
            setBg(a, badge, "bg_badge");
            badge.setMinWidth(dp(a, 18));
            badge.setPadding(dp(a, 5), 0, dp(a, 5), 0);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(a, 18));
            bl.topMargin = dp(a, 3);
            bl.gravity = Gravity.END;
            when.addView(badge, bl);
        }
        row.addView(when, new LinearLayout.LayoutParams(-2, -2));
        if (lendSmile(my, c.tool)) {                    // 10.72: у владельца — подмигивающий кислотно-зелёный смайлик
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(a, LIST_TOOL_DP), dp(a, LIST_TOOL_DP));
            pl.leftMargin = dp(a, 10);
            row.addView(smiley(a), pl);
        } else if (adminMark(my, c.partner, adminEmails())) {    // 10.78: у создателя — «Админ» вместо картинки инструмента
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, dp(a, 26));
            pl.leftMargin = dp(a, 10);
            row.addView(adminBadge(a), pl);
        } else if (!c.tool.isEmpty() && !isLend(c.tool)) {
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(a, LIST_TOOL_DP), dp(a, LIST_TOOL_DP));
            pl.leftMargin = dp(a, 10);
            row.addView(toolPic(a, tool, c.tool, LIST_TOOL_DP), pl);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    openThread(a, c.partnerRaw.isEmpty() ? c.partner : c.partnerRaw, c.tool, toolObjFor(c.tool));
                    callNative(a, "loadChat");
                    render(a);
                } catch (Throwable e) { fallback(a); }
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { confirmHideChat(a, c); return true; }
        });
        return row;
    }

    static final String HIDE_TITLE = "Удалить чат?";
    static final String HIDE_TEXT = "Переписка пропадёт только у вас — у собеседника она останется. "
        + "Если вам напишут снова, чат появится с новыми сообщениями.";
    static final String HIDE_NO_DB = "Удаление чатов заработает после обновления базы";

    /** Долгое нажатие на чат в списке: только после подтверждения, только у себя. */
    static void confirmHideChat(final Activity a, final Chat c) {
        if (c == null || c.last == null || c.partner.isEmpty()) return;
        if (hiddenMissing) { Toast.makeText(a, HIDE_NO_DB, Toast.LENGTH_LONG).show(); return; }
        try {
            new AlertDialog.Builder(a)
                .setTitle(HIDE_TITLE)
                .setMessage(HIDE_TEXT)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { hideChat(a, c); }
                })
                .show();
        } catch (Throwable ignore) {}
    }

    static void hideChat(final Activity a, final Chat c) {
        final String key = chatKey(c.partner, c.tool);
        final long until = c.last.at;
        new Thread(new Runnable() {
            @Override public void run() {
                String code = "";
                try {
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_hide_chat", "POST", hideBody(c), true);
                    code = r == null ? "" : String.valueOf(r[0]);
                    if (ok(r) && r.length > 1 && r[1] != null && r[1].trim().equals("1")) code = "ok";
                } catch (Throwable ignore) {}
                final String fc = code;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if ("ok".equals(fc)) {
                            synchronized (hiddenLocal) { Long was = hiddenLocal.get(key); if (was == null || until > was) hiddenLocal.put(key, until); }
                            synchronized (hidden) { Long was = hidden.get(key); if (was == null || until > was) hidden.put(key, until); }
                            hiddenVer++;
                            saveHidden(a, me);
                            renderedSig = "";
                            render(a);
                            Toast.makeText(a, "Чат удалён", Toast.LENGTH_SHORT).show();
                        } else if ("404".equals(fc)) {
                            hiddenMissing = true;
                            Toast.makeText(a, HIDE_NO_DB, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(a, "Не удалось удалить чат — проверьте интернет", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    static LinearLayout rowBase(Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        setBg(a, row, "bg_card");
        row.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(a, 8);
        row.setLayoutParams(lp);
        return row;
    }

    static TextView sectionTitle(Activity a, String s) {
        TextView t = text(a, s, 12, "gray2", true);
        t.setPadding(0, dp(a, 4), 0, dp(a, 8));
        return t;
    }

    static TextView hint(Activity a, String s) {
        TextView t = text(a, s, 13, "gray", false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 12), dp(a, 18), dp(a, 12), dp(a, 18));
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    /** Круглая аватарка: фото администратора / клиента, иначе буква на штатном bg_avatar. */
    static View avatar(Activity a, String email, String name, int sizeDp) {
        FrameLayout f = new FrameLayout(a);
        setBg(a, f, "bg_avatar");
        TextView t = text(a, letter(name), Math.max(12, sizeDp * 2 / 5), "orange", true);   // 10.96: bg_avatar — рамкой
        f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        round(iv, 0, true);
        iv.setVisibility(View.GONE);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        String e = low(email);
        String[] ad;
        synchronized (admins) { ad = admins.get(e); }
        try {
            if (ad != null && !ad[1].isEmpty()) {
                iv.setVisibility(View.VISIBLE);
                loadPhoto(a, iv, ad[1], "ava" + e);
            } else {
                Bitmap b = clientBitmap(a, e);
                if (b != null) { iv.setImageBitmap(b); iv.setVisibility(View.VISIBLE); }
            }
        } catch (Throwable ignore) {}
        return f;
    }

    /** Фото клиента: своё — из кэша профиля; администратору — по пути из iva_chat_peers (после SQL). */
    static Bitmap clientBitmap(final Activity a, final String email) {
        if (email.isEmpty()) return null;
        if (email.equals(me) && !admin) {
            Bitmap b = IvaClientPatch.clientAvatarBitmap;
            String k = IvaClientPatch.clientAvatarBitmapKey;
            return b != null && k != null && k.startsWith(email + "|") ? b : null;
        }
        if (!admin) return null;
        synchronized (peerBitmaps) { if (peerBitmaps.containsKey(email)) return peerBitmaps.get(email); }
        final String path;
        synchronized (peerPaths) { path = peerPaths.get(email); }
        if (path == null || path.isEmpty()) return null;
        synchronized (peerFetching) { if (!peerFetching.add(email)) return null; }
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try {
                    Bitmap raw = IvaClientPatch.decodeAvatar(IvaClientPatch.downloadClientAvatar(a, path), 160);
                    b = raw == null ? null : IvaClientPatch.circle(raw, 160, 0);
                } catch (Throwable ignore) {}
                final Bitmap fb = b;
                synchronized (peerBitmaps) { peerBitmaps.put(email, fb); }   // null — больше не пытаемся до перезапуска
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { if (fb != null) { renderedSig = ""; render(a); } }
                });
            }
        }).start();
        return null;
    }

    /** Скруглённая картинка инструмента (штатный ключ кэша "ctool"+id), иначе гаечный ключ. */
    /** 10.78: «Админ» — «таблетка» приложения (bg_chip_on; с 10.96 — рамкой), оранжевый жирный текст. */
    static TextView adminBadge(Activity a) {
        TextView t = text(a, ADMIN_BADGE, 12, "orange", true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        setBg(a, t, "bg_chip_on");
        t.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        t.setLetterSpacing(0.05f);
        t.setContentDescription("Собеседник — админ");
        return t;
    }

    static View toolPic(Activity a, JSONObject tool, String id, int sizeDp) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        setBg(a, iv, "bg_photo");
        round(iv, dp(a, sizeDp >= 52 ? 12 : 10), false);
        String img = toolImg(tool);
        if (!img.isEmpty()) {
            loadPhoto(a, iv, img, "ctool" + id);
        } else {
            int w = res(a, "ic_wrench", "drawable");
            if (w != 0) {
                iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                iv.setImageResource(w);
                int p = dp(a, sizeDp / 4);
                iv.setPadding(p, p, p, p);
            }
        }
        return iv;
    }

    // ───────────────────────── 7. Мелкие помощники ─────────────────────────

    static void round(View v, final int radius, final boolean oval) {
        try {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline o) {
                    if (oval) o.setOval(0, 0, view.getWidth(), view.getHeight());
                    else o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            v.setClipToOutline(true);
        } catch (Throwable ignore) {}
    }

    static void loadPhoto(Activity a, ImageView iv, String url, String key) {
        try {
            call(a, "loadPhoto", new Class<?>[]{ImageView.class, String.class, String.class}, iv, url, key);
        } catch (Throwable ignore) {}
    }

    static TextView text(Activity a, String s, int sp, String color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color(a, color));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    static int color(Activity a, String name) {
        int fb = "orange".equals(name) ? 0xFFFF8F1F : "gray".equals(name) ? 0xFF9AA1B0 : "gray2".equals(name) ? 0xFF6B7080
            : "white".equals(name) ? 0xFFFFFFFF : 0xFFF2F4F8;
        return IvaClientPatch.navColor(a, name, fb);
    }

    static void setBg(Activity a, View v, String drawable) {
        int id = res(a, drawable, "drawable");
        if (id != 0) v.setBackgroundResource(id);
    }

    static int res(Activity a, String name, String type) {
        try { return a.getResources().getIdentifier(name, type, a.getPackageName()); } catch (Throwable e) { return 0; }
    }

    static int dp(android.content.Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Object call(Activity a, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = a.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(a, args);
    }

    static void callNative(Activity a, String name) {
        try { call(a, name, new Class<?>[0]); } catch (Throwable ignore) {}
    }

    static void callTab(Activity a, String tab) {
        try { call(a, "showTab", new Class<?>[]{String.class}, tab); } catch (Throwable ignore) {}
    }
}

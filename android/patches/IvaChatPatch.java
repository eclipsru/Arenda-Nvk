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

    /** Список переписок: по паре «собеседник + инструмент», свежие сверху. */
    static List<Chat> buildChats(List<Msg> asc, String me, Set<String> admins, boolean sup,
                                 Map<String, String> localTags) {
        Map<String, String> tools = assignTools(asc, me, admins, sup, localTags);
        LinkedHashMap<String, Chat> map = new LinkedHashMap<String, Chat>();
        String my = low(me);
        for (Msg m : asc) {
            if (!tools.containsKey(m.id)) continue;
            String p = partnerOf(m, me, admins, sup);
            String t = tools.get(m.id);
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
        Map<String, String> tools = assignTools(asc, me, admins, sup, localTags);
        ArrayList<Msg> out = new ArrayList<Msg>();
        String p = low(partner), t = clean(tool);
        for (Msg m : asc) {
            if (!tools.containsKey(m.id)) continue;
            if (partnerOf(m, me, admins, sup).equals(p) && t.equals(tools.get(m.id))) out.add(m);
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

    static void nativeAsk(Activity a, View v) {
        try {
            Method m = a.getClass().getDeclaredMethod("lambda$setupChat$88$ru-prokatnvsk-app-MainActivity", View.class);
            m.setAccessible(true);
            m.invoke(a, v);
        } catch (Throwable ignore) {}
    }

    static void openThread(Activity a, String partnerRaw, String tool, JSONObject toolObj) {
        if (toolObj != null && !clean(tool).isEmpty() && !toolCache.containsKey(tool)) toolCache.put(tool, toolObj);
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
        input.setText("");
        stickBottom = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    JSONObject b = new JSONObject();
                    b.put("from_email", from);
                    b.put("to_email", partner);
                    b.put("text", text);
                    if (!tool.isEmpty() && toolColumn) b.put("tool_id", tool);
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/app_messages", "POST", b.toString(), true);
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
            String tid = toolIdOf(tool);
            if (tool != null && !tid.isEmpty() && !toolCache.containsKey(tid)) toolCache.put(tid, tool);
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
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    load(a, my, adm, partner, tool);
                    lastError = "";
                } catch (Throwable e) {
                    lastError = "Нет связи — обновим через несколько секунд";
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading = false;
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
        boolean sup = superView(my);
        String filter = msgFilter(my, sup);
        JSONArray rows;
        try {
            rows = getArr(a, "/rest/v1/app_messages?select=" + (toolColumn ? MSG_SELECT : MSG_SELECT_OLD) + filter
                + "&order=created_at.desc&limit=300");
        } catch (Throwable e) {
            if (!toolColumn) throw e;
            rows = getArr(a, "/rest/v1/app_messages?select=" + MSG_SELECT_OLD + filter + "&order=created_at.desc&limit=300");
            toolColumn = false;
        }
        ArrayList<Msg> asc = new ArrayList<Msg>();
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            asc.add(msg(o.optString("id", ""), o.optString("from_email", ""), o.optString("to_email", ""),
                o.optString("text", ""), o.optString("img", ""), o.optString("audio", ""),
                o.optInt("audio_dur", 0), o.optBoolean("is_read", false), o.optString("created_at", ""),
                o.isNull("tool_id") ? "" : o.optString("tool_id", "")));
        }
        // Голосовые, записанные в открытой переписке по инструменту, — метка на телефоне и (если есть SQL) в базе.
        if (seenReady && my.equals(msgsFor)) {
            List<String> tag = voiceToTag(asc, my, partner, tool, seenIds, localTags);
            if (!tag.isEmpty()) {
                synchronized (localTags) { for (String id : tag) localTags.put(id, tool); }
                if (!tagMissing) {
                    try {
                        JSONObject b = new JSONObject();
                        JSONArray ids = new JSONArray();
                        for (String id : tag) ids.put(id);
                        b.put("p_ids", ids);
                        b.put("p_tool", tool);
                        String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_tag_my_messages", "POST", b.toString(), true);
                        if (r != null && "404".equals(r[0])) tagMissing = true;
                    } catch (Throwable ignore) {}
                }
            }
        }
        // Инструменты переписок (название и картинка)
        HashSet<String> need = new HashSet<String>();
        Map<String, String> tools = assignTools(asc, my, adminEmails(), sup, localTags);
        for (String t : tools.values()) if (!t.isEmpty() && !toolCache.containsKey(t) && !toolMissing.contains(t)) need.add(t);
        if (!tool.isEmpty() && !toolCache.containsKey(tool) && !toolMissing.contains(tool)) need.add(tool);
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
            .append('|').append(localTags.size()).append('|');
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
            renderThread(a, my);
            if (keepBottom) toBottom();
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
        if (!openTool.isEmpty()) {
            JSONObject t = toolCache.get(openTool);
            card.addView(toolPic(a, t, openTool, HEAD_TOOL_DP), new LinearLayout.LayoutParams(dp(a, HEAD_TOOL_DP), dp(a, HEAD_TOOL_DP)));
            small = "Чат по инструменту · " + partnerTitle(openPartner);
            title = t == null ? "Инструмент" : clean(t.optString("name", "Инструмент"));
        } else {
            card.addView(avatar(a, openPartner, partnerTitle(openPartner), HEAD_TOOL_DP),
                new LinearLayout.LayoutParams(dp(a, HEAD_TOOL_DP), dp(a, HEAD_TOOL_DP)));
            small = "Общий чат";
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
            body.addView(hint(a, !openTool.isEmpty()
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
            body.addView(bubbleRow(a, m, ours(m, my, ad, sup), low(m.from).equals(my), tz));
        }
        if (!lastError.isEmpty()) body.addView(hint(a, lastError));
    }

    static View bubbleRow(final Activity a, final Msg m, boolean mine, boolean deletable, TimeZone tz) {
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
        if (!m.audio.isEmpty()) {
            col.addView(voiceBubble(a, m, mine));
        } else if (!m.text.isEmpty()) {
            TextView t = text(a, m.text, 15, mine ? "white" : "text", false);
            setBg(a, t, mine ? "bg_bubble_me" : "bg_bubble_other");
            t.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 8));
            t.setMaxWidth(maxW);
            t.setTextIsSelectable(false);
            col.addView(t, new LinearLayout.LayoutParams(-2, -2));
        }
        String time = hm(m.at, tz);
        if (mine) time = time + (m.read ? "  ✓✓" : "  ✓");
        LinearLayout meta = new LinearLayout(a);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView tt = text(a, time, 10, mine && m.read ? "orange" : "gray2", false);
        meta.addView(tt);
        if (deletable && !m.id.isEmpty()) {
            TextView x = text(a, "×", 14, "gray2", true);
            x.setPadding(dp(a, 8), 0, dp(a, 2), 0);
            View.OnClickListener del = new View.OnClickListener() {
                @Override public void onClick(View v) { confirmDelete(a, m); }
            };
            x.setOnClickListener(del);
            meta.addView(x);
            col.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { confirmDelete(a, m); return true; }
            });
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

    // ───────────────────────── Голосовые (10.58) ─────────────────────────
    // Внешний вид — как штатный пузырь v9.8 (10.14 renderChat): тёмный bg_bubble_other для всех,
    // микрофон, «Голосовое», длительность, кнопка ▶/❚❚ справа, полоска прогресса снизу.
    // Воспроизведение своё: файл сначала скачивается в кэш (штатный playVoice делал prepare()
    // потоком по сети прямо в UI-потоке), состояние привязано к id сообщения, а не к View —
    // поэтому перерисовка чата не «отрывает» кнопку и полоску от играющего плеера.

    static final int VOICE_TICK_MS = 100;
    static final int VOICE_FILL = 0xFFFFC26B;
    static final int VOICE_TRACK_BG = 0x33FFFFFF;

    static android.media.MediaPlayer vMp;
    static String vKey = "";             // id сообщения (или ссылка), которое сейчас играет/грузится
    static boolean vLoading;             // идёт скачивание/подготовка
    static boolean vPlaying;
    static WeakReference<ImageView> vBtn = new WeakReference<ImageView>(null);
    static WeakReference<View> vFill = new WeakReference<View>(null);
    static WeakReference<View> vTrack = new WeakReference<View>(null);
    static final Map<String, Integer> vDur = new HashMap<String, Integer>();   // ссылка → секунды
    static final Set<String> vDurAsked = new HashSet<String>();
    static final java.util.concurrent.ExecutorService vWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Ширина полоски, как у штатного пузыря: 38% экрана, но 84…150dp. */
    static int voiceTrackPx(int screenW, float density) {
        int min = Math.round(84 * density), max = Math.round(150 * density);
        return Math.max(min, Math.min(max, Math.round(screenW * 0.38f)));
    }

    /** Ширина «Голосовое», как у штатного: 24% экрана, но 44…96dp. */
    static int voiceLabelMaxPx(int screenW, float density) {
        int min = Math.round(44 * density), max = Math.round(96 * density);
        return Math.max(min, Math.min(max, Math.round(screenW * 0.24f)));
    }

    /** Заполненная часть полоски. */
    static int voiceFillPx(int pos, int dur, int trackW) {
        if (dur <= 0 || trackW <= 0 || pos <= 0) return 0;
        return Math.round(trackW * Math.min(1f, pos / (float) dur));
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

    static String voiceKey(Msg m) {
        return m.id.isEmpty() ? m.audio : m.id;
    }

    static View voiceBubble(final Activity a, final Msg m, boolean mine) {
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        float den = a.getResources().getDisplayMetrics().density;
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        setBg(a, v, "bg_bubble_other");                 // как в v9.8: тёмный и у своих
        v.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mic = new ImageView(a);
        int micId = res(a, "ic_mic", "drawable");
        if (micId != 0) mic.setImageResource(micId);
        LinearLayout.LayoutParams mpl = new LinearLayout.LayoutParams(dp(a, 15), dp(a, 15));
        mpl.rightMargin = dp(a, 7);
        row.addView(mic, mpl);
        TextView label = text(a, "Голосовое", 14, "white", true);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(voiceLabelMaxPx(sw, den));
        row.addView(label);
        int known = m.dur;
        if (known <= 0) {
            Integer c;
            synchronized (vDur) { c = vDur.get(m.audio); }
            if (c != null) known = c;
        }
        TextView dur = text(a, known > 0 ? durLabel(known) : "", 14, mine ? "text" : "orange", true);
        dur.setSingleLine(true);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.leftMargin = dp(a, 5);
        row.addView(dur, dl);
        final ImageView btn = new ImageView(a);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setColorFilter(color(a, "white"));
        btn.setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24));
        bl.leftMargin = dp(a, 8);
        bl.gravity = Gravity.CENTER_VERTICAL;
        row.addView(btn, bl);
        v.addView(row, new LinearLayout.LayoutParams(-2, -2));

        final LinearLayout track = new LinearLayout(a);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(VOICE_TRACK_BG);
        tg.setCornerRadius(dp(a, 2));
        track.setBackground(tg);
        final View fill = new View(a);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(VOICE_FILL);
        fg.setCornerRadius(dp(a, 2));
        fill.setBackground(fg);
        track.addView(fill, new LinearLayout.LayoutParams(0, -1));
        final int trackW = voiceTrackPx(sw, den);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(trackW, dp(a, 4));
        tl.topMargin = dp(a, 7);
        v.addView(track, tl);

        final String key = voiceKey(m);
        if (key.equals(vKey)) {
            // Перерисовка во время воспроизведения — подхватываем новые View.
            vBtn = new WeakReference<ImageView>(btn);
            vFill = new WeakReference<View>(fill);
            vTrack = new WeakReference<View>(track);
            voiceShow(a);
        } else {
            voiceIcon(a, btn, false, false);
        }
        if (known <= 0) voiceAskDuration(a, m.audio);

        View.OnClickListener tap = new View.OnClickListener() {
            @Override public void onClick(View x) { voiceTap(a, key, m.audio, btn, fill, track); }
        };
        v.setOnClickListener(tap);
        btn.setOnClickListener(tap);
        return v;
    }

    static void voiceIcon(Activity a, ImageView btn, boolean playing, boolean loading) {
        if (btn == null) return;
        int id = res(a, playing ? "ic_pause" : "ic_play", "drawable");
        if (id != 0) btn.setImageResource(id);
        btn.setAlpha(loading ? 0.45f : 1f);
    }

    /** Кнопка и полоска текущего голосового — по состоянию плеера. */
    static void voiceShow(Activity a) {
        voiceIcon(a, vBtn.get(), vPlaying, vLoading);
        View f = vFill.get();
        View t = vTrack.get();
        if (f == null) return;
        int w = 0;
        try {
            if (vMp != null && !vLoading) {
                int tw = t != null && t.getWidth() > 0 ? t.getWidth() : (t != null && t.getLayoutParams() != null ? t.getLayoutParams().width : 0);
                w = voiceFillPx(vMp.getCurrentPosition(), vMp.getDuration(), tw);
            }
        } catch (Throwable ignore) {}
        ViewGroup.LayoutParams lp = f.getLayoutParams();
        if (lp != null && lp.width != w) { lp.width = w; f.setLayoutParams(lp); }
    }

    static final Runnable V_TICK = new Runnable() {
        @Override public void run() {
            Activity a = act.get();
            if (a == null || vMp == null || !vPlaying) return;
            voiceShow(a);
            handler.postDelayed(this, VOICE_TICK_MS);
        }
    };

    static void voiceTap(final Activity a, final String key, final String url, ImageView btn, View fill, View track) {
        vBtn = new WeakReference<ImageView>(btn);
        vFill = new WeakReference<View>(fill);
        vTrack = new WeakReference<View>(track);
        if (key.equals(vKey)) {
            if (vLoading) return;                       // ещё скачивается — ждём
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
        ImageView keepBtn = btn; View keepFill = fill; View keepTrack = track;
        voiceStop(a);
        vBtn = new WeakReference<ImageView>(keepBtn);
        vFill = new WeakReference<View>(keepFill);
        vTrack = new WeakReference<View>(keepTrack);
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
        if (a != null) {
            voiceIcon(a, vBtn.get(), false, false);
            View fl = vFill.get();
            if (fl != null && fl.getLayoutParams() != null) { fl.getLayoutParams().width = 0; fl.requestLayout(); }
        }
        vBtn = new WeakReference<ImageView>(null);
        vFill = new WeakReference<View>(null);
        vTrack = new WeakReference<View>(null);
    }

    /** Файл голосового в кэше приложения; если нет — скачать. Вызывать НЕ из UI-потока. */
    static java.io.File voiceFile(Activity a, String url) throws Exception {
        java.io.File dir = new java.io.File(a.getCacheDir(), "iva_voice");
        if (!dir.isDirectory()) dir.mkdirs();
        java.io.File f = new java.io.File(dir, voiceCacheName(url));
        if (f.isFile() && f.length() > 0) return f;
        java.io.File tmp = new java.io.File(dir, f.getName() + ".part");
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
        if (tmp.length() <= 0 || !tmp.renameTo(f)) { tmp.delete(); throw new Exception("пустой файл"); }
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

    static final Runnable V_RERENDER = new Runnable() {
        @Override public void run() {
            Activity a = act.get();
            if (a == null) return;
            renderedSig = "";
            render(a);
        }
    };

    static void confirmDelete(final Activity a, final Msg m) {
        try {
            new AlertDialog.Builder(a)
                .setMessage("Удалить сообщение?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
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
                })
                .show();
        } catch (Throwable ignore) {}
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
        JSONObject tool = c.tool.isEmpty() ? null : toolCache.get(c.tool);
        if (!c.tool.isEmpty()) sub = tool == null ? "Инструмент" : clean(tool.optString("name", "Инструмент"));
        else sub = "Общий чат";
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
            TextView badge = text(a, String.valueOf(Math.min(99, c.unread)), 11, "white", true);
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
        if (!c.tool.isEmpty()) {
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(a, LIST_TOOL_DP), dp(a, LIST_TOOL_DP));
            pl.leftMargin = dp(a, 10);
            row.addView(toolPic(a, tool, c.tool, LIST_TOOL_DP), pl);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    openThread(a, c.partnerRaw.isEmpty() ? c.partner : c.partnerRaw, c.tool, toolCache.get(c.tool));
                    callNative(a, "loadChat");
                    render(a);
                } catch (Throwable e) { fallback(a); }
            }
        });
        return row;
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
        TextView t = text(a, letter(name), Math.max(12, sizeDp * 2 / 5), "white", true);
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

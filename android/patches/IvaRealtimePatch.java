package ru.prokatnvsk.app;

// 10.112 (просьба владельца 17.10.2026): приглашение в сотрудники и права админа — моментально, без каких-либо действий.
//
//   Раньше (10.110) приложение само спрашивало базу раз в 30 секунд (приглашение — раз в минуту) и при смене раздела.
//   Теперь база сама сообщает приложению: пока приложение открыто, держим одно соединение Supabase Realtime
//   (WebSocket, протокол Phoenix 1.0.0, как supabase-js) и подписываемся на свою строку таблицы iva_role_ping
//   (SQL 20261103_role_ping.sql). Триггеры базы трогают эту строку, когда меняется что-то про роль человека: строка
//   admins (назначили, удалили, приостановили, вернули), iva_staff (пригласили, приняли, права, убрали),
//   iva_staff_points (пункты сотрудника). Пришло событие — сразу та же проверка, что в 10.110 (IvaAccessPatch.poke):
//   кабинет админа открывается / закрывается, приглашение появляется окном поверх любого экрана.
//
//   В событии нет данных — только «что-то поменялось»; что именно, приложение спрашивает обычными запросами
//   (свою строку admins и iva_staff_me). Читать iva_role_ping может только сам человек (RLS: своя почта), поэтому
//   чужие события не приходят. Ключ входа — тот же, что у приложения; обновился ключ (10.69) — отправляем новый.
//
//   Нет SQL, нет связи, Realtime выключен — переподключаемся всё реже (2 с … 5 мин), а проверка раз в 30 секунд
//   из 10.110 остаётся запасной. Приложение свёрнуто дольше 2 минут — соединение закрываем (батарея), открыли —
//   подключаемся снова (сразу при смене раздела или в течение 30 секунд) и сразу проверяем.
//   Создателю (eclips.ru@mail.ru) соединение не нужно — его роль не меняется. Новых вставок в smali нет: вход —
//   IvaNotifyPatch.onShowTab и tick. Сеть — только в своём потоке.

import android.app.Activity;
import android.app.ActivityManager;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IvaRealtimePatch {
    private IvaRealtimePatch() {}

    static final String TABLE = "iva_role_ping", TOPIC = "realtime:iva_role", JOIN_REF = "1";
    static final long HEARTBEAT_MS = 25000L, SILENCE_MS = 70000L, BG_GRACE_MS = 120000L;
    static final int READ_MS = 1000, CONNECT_MS = 10000, FRAME_MS = 15000, MAX_MSG = 1 << 20;
    static final long[] RETRY_MS = {2000L, 5000L, 15000L, 30000L, 60000L, 300000L};

    static String host() { return IvaClientPatch.SB_URL.replaceFirst("^https?://", "").replaceAll("/.*$", ""); }

    static String path(String key) { return "/realtime/v1/websocket?apikey=" + key + "&vsn=1.0.0"; }

    // ───────────── сообщения Phoenix (чистая логика, IvaPatchTest) ─────────────

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        String t = s == null ? "" : s;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Вход в канал: подписка на свою строку iva_role_ping (любое событие), ключ входа — для RLS. */
    static String joinMsg(String email, String token) {
        return "{\"topic\":" + q(TOPIC) + ",\"event\":\"phx_join\",\"payload\":{\"config\":{"
            + "\"broadcast\":{\"ack\":false,\"self\":false},\"presence\":{\"key\":\"\",\"enabled\":false},"
            + "\"postgres_changes\":[{\"event\":\"*\",\"schema\":\"public\",\"table\":" + q(TABLE)
            + ",\"filter\":" + q("email=eq." + (email == null ? "" : email.trim().toLowerCase())) + "}],"
            + "\"private\":false},\"access_token\":" + q(token) + "},\"ref\":" + q(JOIN_REF) + ",\"join_ref\":" + q(JOIN_REF) + "}";
    }

    static String heartbeatMsg(int ref) {
        return "{\"topic\":\"phoenix\",\"event\":\"heartbeat\",\"payload\":{},\"ref\":" + q(String.valueOf(ref)) + "}";
    }

    static String tokenMsg(String token, int ref) {
        return "{\"topic\":" + q(TOPIC) + ",\"event\":\"access_token\",\"payload\":{\"access_token\":" + q(token)
            + "},\"ref\":" + q(String.valueOf(ref)) + ",\"join_ref\":" + q(JOIN_REF) + "}";
    }

    static final int M_OTHER = 0, M_JOIN_OK = 1, M_JOIN_ERR = 2, M_CHANGE = 3, M_SYS_OK = 4, M_SYS_ERR = 5, M_CLOSED = 6;

    /** Что пришло от сервера: вход в канал удался / нет, событие, системное сообщение, канал закрыт. */
    @SuppressWarnings("unchecked")
    static int classify(String msg) {
        Object o;
        try { o = Json.parse(msg); } catch (Throwable e) { return M_OTHER; }
        if (!(o instanceof Map)) return M_OTHER;
        Map<String, Object> m = (Map<String, Object>) o;
        String topic = str(m.get("topic")), event = str(m.get("event")), ref = str(m.get("ref"));
        Object p = m.get("payload");
        Map<String, Object> pl = p instanceof Map ? (Map<String, Object>) p : new LinkedHashMap<String, Object>();
        String status = str(pl.get("status"));
        if (!TOPIC.equals(topic)) return M_OTHER;                         // «phoenix» — ответ на heartbeat
        if ("phx_reply".equals(event)) {
            if (!JOIN_REF.equals(ref)) return "error".equals(status) ? M_JOIN_ERR : M_OTHER;   // ответ на новый ключ
            return "ok".equals(status) ? M_JOIN_OK : M_JOIN_ERR;
        }
        if ("postgres_changes".equals(event)) return M_CHANGE;
        if ("system".equals(event)) return "ok".equals(status) ? M_SYS_OK : "error".equals(status) ? M_SYS_ERR : M_OTHER;
        if ("phx_error".equals(event) || "phx_close".equals(event)) return M_CLOSED;
        return M_OTHER;
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** Через сколько переподключаться после n-й неудачи подряд (1, 2, …). */
    static long retryMs(int n) { return RETRY_MS[Math.max(0, Math.min(n, RETRY_MS.length) - 1)]; }

    // ───────────── маленький разбор JSON (без org.json — проверяется в IvaPatchTest) ─────────────

    static final class Json {
        final String s;
        int i;
        Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s == null ? "" : s);
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("лишнее в конце");
            return v;
        }

        void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("пусто");
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                i++; ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws(); expect(':');
                    m.put(k, value());
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<Object>();
                i++; ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("непонятный символ");
            String n = s.substring(st, i);
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                try { return Long.parseLong(n); } catch (NumberFormatException ignore) {}
            }
            return Double.parseDouble(n);
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

        void expect(char c) {
            if (!peek(c)) throw new IllegalArgumentException("ждали " + c);
            i++;
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("строка не закрыта");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("строка не закрыта");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("\\u");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);
                }
            }
        }
    }

    // ───────────── WebSocket (RFC 6455) — только клиент, текстовые сообщения ─────────────

    static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String b64(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            b.append(B64[n >> 18 & 63]).append(B64[n >> 12 & 63]);
            b.append(i + 1 < d.length ? B64[n >> 6 & 63] : '=').append(i + 2 < d.length ? B64[n & 63] : '=');
        }
        return b.toString();
    }

    /** Что сервер обязан вернуть в Sec-WebSocket-Accept на наш ключ. */
    static String acceptFor(String key) throws Exception {
        return b64(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes("US-ASCII")));
    }

    static final class Ws {
        final Socket sock;
        final InputStream in;
        final OutputStream out;
        final SecureRandom rnd = new SecureRandom();
        final ByteArrayOutputStream frag = new ByteArrayOutputStream();

        Ws(Socket s) throws IOException {
            sock = s;
            in = new java.io.BufferedInputStream(s.getInputStream());
            out = s.getOutputStream();
        }

        /** Соединиться: tls — wss:// (проверка сертификата и имени сервера), иначе ws:// (только для проверки). */
        static Ws open(String host, int port, boolean tls, String path) throws Exception {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), CONNECT_MS);
            Socket s = raw;
            if (tls) {
                javax.net.ssl.SSLSocketFactory f = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) f.createSocket(raw, host, port, true);   // с именем — SNI
                try {                                                    // проверка имени в сертификате при рукопожатии
                    javax.net.ssl.SSLParameters sp = ss.getSSLParameters();
                    sp.setEndpointIdentificationAlgorithm("HTTPS");
                    ss.setSSLParameters(sp);
                } catch (Throwable ignore) {}
                ss.setSoTimeout(CONNECT_MS);
                ss.startHandshake();
                // и ещё раз — проверкой имени Android (как у HttpsURLConnection); в обычной Java её делает строка выше
                if (android() && !javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.getSession())) {
                    try { ss.close(); } catch (Throwable ignore) {}
                    throw new IOException("сертификат не для " + host);
                }
                s = ss;
            }
            s.setSoTimeout(CONNECT_MS);
            Ws w = new Ws(s);
            try { w.handshake(host, path); } catch (Exception e) { w.close(); throw e; }
            return w;
        }

        static boolean android() { return String.valueOf(System.getProperty("java.vm.name")).toLowerCase().contains("dalvik"); }

        void handshake(String host, String path) throws Exception {
            byte[] k = new byte[16];
            rnd.nextBytes(k);
            String key = b64(k);
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: Iva-Android\r\n\r\n";
            out.write(req.getBytes("UTF-8"));
            out.flush();
            String status = line();
            if (!status.startsWith("HTTP/1.1 101")) throw new IOException("сервер не открыл WebSocket: " + status);
            String accept = "";
            for (String h = line(); !h.isEmpty(); h = line()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) accept = h.substring(c + 1).trim();
            }
            if (!accept.equals(acceptFor(key))) throw new IOException("неверный ответ WebSocket");
        }

        String line() throws IOException {
            StringBuilder b = new StringBuilder();
            while (true) {
                int c = in.read();
                if (c < 0) throw new EOFException("соединение закрыто");
                if (c == '\n') break;
                if (c != '\r') b.append((char) c);
                if (b.length() > 8192) throw new IOException("слишком длинный заголовок");
            }
            return b.toString();
        }

        synchronized void send(int opcode, byte[] data) throws IOException {
            int n = data.length;
            ByteArrayOutputStream f = new ByteArrayOutputStream(n + 14);
            f.write(0x80 | opcode);
            if (n < 126) f.write(0x80 | n);
            else if (n < 65536) { f.write(0x80 | 126); f.write(n >> 8 & 0xff); f.write(n & 0xff); }
            else { f.write(0x80 | 127); for (int sh = 56; sh >= 0; sh -= 8) f.write((int) ((long) n >> sh & 0xff)); }
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            f.write(mask, 0, 4);
            for (int i = 0; i < n; i++) f.write(data[i] ^ mask[i & 3]);
            out.write(f.toByteArray());
            out.flush();
        }

        void text(String s) throws IOException { send(1, s.getBytes("UTF-8")); }

        /** Следующее текстовое сообщение; null — за waitMs ничего не пришло. Закрыли — EOFException. */
        String read(int waitMs) throws IOException {
            while (true) {
                int b0;
                sock.setSoTimeout(waitMs);
                try { b0 = in.read(); } catch (SocketTimeoutException e) { return null; }
                if (b0 < 0) throw new EOFException("соединение закрыто");
                sock.setSoTimeout(FRAME_MS);                             // начало кадра есть — дочитываем целиком
                int b1 = rd();
                boolean fin = (b0 & 0x80) != 0, masked = (b1 & 0x80) != 0;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = rd() << 8 | rd();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = len << 8 | rd(); }
                if (len < 0 || len > MAX_MSG) throw new IOException("слишком большое сообщение");
                byte[] mask = new byte[4];
                if (masked) for (int i = 0; i < 4; i++) mask[i] = (byte) rd();
                byte[] d = new byte[(int) len];
                for (int got = 0; got < d.length; ) {
                    int r = in.read(d, got, d.length - got);
                    if (r < 0) throw new EOFException("соединение закрыто");
                    got += r;
                }
                if (masked) for (int i = 0; i < d.length; i++) d[i] ^= mask[i & 3];
                if (op == 8) { try { send(8, new byte[0]); } catch (Throwable ignore) {} throw new EOFException("сервер закрыл соединение"); }
                if (op == 9) { send(10, d); continue; }
                if (op == 10) continue;
                if (op == 1 || op == 2) frag.reset();
                else if (op != 0) continue;
                frag.write(d, 0, d.length);
                if (frag.size() > MAX_MSG) throw new IOException("слишком большое сообщение");
                if (!fin) continue;
                String s = new String(frag.toByteArray(), "UTF-8");
                frag.reset();
                return s;
            }
        }

        int rd() throws IOException {
            int c = in.read();
            if (c < 0) throw new EOFException("соединение закрыто");
            return c;
        }

        void close() {
            try { send(8, new byte[0]); } catch (Throwable ignore) {}
            try { sock.close(); } catch (Throwable ignore) {}
        }
    }

    // ───────────── соединение приложения ─────────────

    static volatile boolean running;
    static volatile String runFor = "";
    static volatile int gen;
    static volatile int fails;
    static volatile long lastEvent;      // для журнала / проверки на телефоне
    static WeakReference<Activity> act;

    /** Из IvaNotifyPatch.onShowTab и tick (главный поток): держать соединение, пока человек вошёл и приложение открыто. */
    static void ensure(Activity a) {
        if (a == null) return;
        act = new WeakReference<Activity>(a);
        boolean want = IvaNotifyPatch.loggedIn(a) && !IvaReviewPatch.chief(a);
        String me = IvaPickupPatch.me(a);
        if (!want || me.isEmpty()) { stop(); return; }
        if (running) {
            if (!me.equals(runFor)) stop();                                // сменили аккаунт — старое закроется само
            return;
        }
        if (!foreground()) return;
        running = true;
        runFor = me;
        final int g = ++gen;
        final String email = me;
        new Thread(new Runnable() {
            @Override public void run() {
                try { session(g, email); } catch (Throwable ignore) {} finally { if (g == gen) running = false; }
            }
        }, "iva-realtime").start();
    }

    static void stop() { gen++; running = false; }

    static boolean foreground() {
        try {
            ActivityManager.RunningAppProcessInfo i = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(i);
            return i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
        } catch (Throwable e) { return true; }
    }

    static String token(Activity a) {
        Object t = a == null ? null : IvaClientPatch.get(a, "accessToken");
        return t instanceof String ? ((String) t).trim() : "";
    }

    /** Пока нужно: соединиться; оборвалось — снова через 2 с, неудачи подряд — всё реже (до 5 мин). */
    static void session(int g, String email) throws InterruptedException {
        while (g == gen) {
            boolean ok = loop(g, email);
            if (g != gen) return;
            fails = ok ? 1 : fails + 1;
            long until = System.currentTimeMillis() + retryMs(fails);
            while (g == gen && System.currentTimeMillis() < until) Thread.sleep(500);
            if (!wanted(email)) { if (g == gen) gen++; return; }
        }
    }

    static boolean wanted(String email) {
        Activity a = act == null ? null : act.get();
        return a != null && !a.isFinishing() && IvaNotifyPatch.loggedIn(a) && email.equals(IvaPickupPatch.me(a));
    }

    /** Одно соединение. true — вход в канал удался (потом оборвалось), false — не удалось подписаться. */
    static boolean loop(int g, String email) {
        Ws ws = null;
        boolean ok = false;
        try {
            Activity a = act == null ? null : act.get();
            String tok = token(a);
            if (a == null || tok.isEmpty()) return false;
            ws = Ws.open(host(), 443, true, path(IvaClientPatch.SB_KEY));
            ws.text(joinMsg(email, tok));
            long now = System.currentTimeMillis(), hbAt = now, rxAt = now, bgSince = 0;
            int ref = 2;
            while (g == gen) {
                String m = ws.read(READ_MS);
                now = System.currentTimeMillis();
                if (!wanted(email)) { if (g == gen) gen++; break; }          // вышли, сменили аккаунт, окно закрыто
                a = act.get();
                if (m != null) {
                    rxAt = now;
                    int k = classify(m);
                    if (k == M_JOIN_OK) { ok = true; fails = 0; IvaAccessPatch.poke(a); }   // подписались — сразу сверить
                    else if (k == M_CHANGE) { lastEvent = now; IvaAccessPatch.poke(a); }
                    else if (k == M_JOIN_ERR || k == M_SYS_ERR || k == M_CLOSED) break;
                }
                if (now - rxAt > SILENCE_MS) break;                          // сервер молчит — переподключиться
                if (now - hbAt >= HEARTBEAT_MS) {
                    hbAt = now;
                    ws.text(heartbeatMsg(ref++));
                    String t2 = token(a);
                    if (!t2.isEmpty() && !t2.equals(tok)) { tok = t2; ws.text(tokenMsg(tok, ref++)); }   // 10.69 обновил ключ
                    if (foreground()) bgSince = 0;
                    else if (bgSince == 0) bgSince = now;
                    else if (now - bgSince > BG_GRACE_MS) { if (g == gen) gen++; break; }   // свёрнуто — до возвращения
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (ws != null) ws.close();
        }
        return ok;
    }
}

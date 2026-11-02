package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 10.74 — мгновенные уведомления админам через Firebase (FCM), решение владельца 14.10.2026.
 *
 * Без библиотек Firebase (их в сборке нет — APK собирается из готового 10.14): только API самого Android.
 *   1. Публичные настройки проекта Firebase (project_id, номер отправителя, app_id, api_key) — от функции
 *      Supabase iva-push (?cfg=1); их кладёт владелец секретом FCM_GOOGLE_SERVICES. Нет — «не настроены».
 *   2. Firebase Installations (REST, как firebase-installations): своя «установка» (FID) и её токен.
 *   3. Токен FCM — у сервисов Google на телефоне (com.google.android.gms), тем же способом, что Firebase SDK:
 *      bindService(c2dm REGISTER) + Messenger, what=1, {pkg, oneWay, data:{…}} → {data:{registration_id|error}}.
 *      Протокол сверен с microG (PushRegisterService/PushRegisterHandler) и firebase-messaging GmsRpc.
 *   4. Токен — в базу (rpc iva_push_register, только админам). Раз в сутки и при смене аккаунта — заново;
 *      вышли из аккаунта или вошёл клиент — токен убираем (rpc iva_push_forget).
 *   5. Push от сервера — только сигнал «проверь сейчас», без текста сообщений (через Google ничего личного не идёт):
 *      приложение открыто — сразу обычная проверка; закрыто — та же фоновая проверка, что раз в 15 минут
 *      (IvaNotifyPatch.bgRun: расписание 10.73, переключатели, память «уже показано» — всё как было).
 * Без сервисов Google или пока владелец не настроил Firebase — всё как в 10.73 (проверка раз в ~15 минут).
 */
public final class IvaPushPatch {
    private IvaPushPatch() {}

    static final String GMS = "com.google.android.gms";
    static final String ACT_REGISTER = "com.google.android.c2dm.intent.REGISTER";
    static final String ACT_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    static final String FN_URL = IvaClientPatch.SB_URL + "/functions/v1/iva-push";
    static final String FIS_URL = "https://firebaseinstallations.googleapis.com/v1/projects/";
    static final String SDK_FIS = "a:17.2.0";
    static final String SDK_FCM = "fcm-24.0.0";
    static final String PREFS = "iva_push";
    static final String K_TOK = "tok", K_EMAIL = "email", K_OK = "ok_at", K_TRY = "try_at", K_ST = "st", K_ERR = "err";
    static final String K_FID = "fid", K_FRT = "frt", K_FAT = "fat", K_FEXP = "fexp", K_FAPP = "fapp";
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final long RETRY_MS = 30L * 60 * 1000;
    static final long FIS_AHEAD_MS = 60L * 60 * 1000;
    static volatile boolean running;
    static int lastId;

    // ───────────────────────── чистая логика (проверяется тестами) ─────────────────────────

    /** Регистрироваться ли сейчас: токен есть, на этот аккаунт и моложе суток — нет; иначе не чаще раза в 30 минут. */
    static boolean due(String regEmail, String email, String tok, long okAt, long tryAt, long now) {
        if (okAt > now) okAt = 0;          // часы перевели назад
        if (tryAt > now) tryAt = 0;
        boolean fresh = tok != null && !tok.isEmpty() && email != null && email.trim().equalsIgnoreCase(regEmail == null ? "" : regEmail.trim())
            && now - okAt < DAY_MS;
        return !fresh && now - tryAt >= RETRY_MS;
    }

    /** Убрать токен с сервера: на телефоне он записан на аккаунт, а сейчас вошёл не админ или никто. */
    static boolean shouldForget(String regEmail, String tok, boolean logged, boolean admin) {
        return regEmail != null && !regEmail.isEmpty() && tok != null && !tok.isEmpty() && !(logged && admin);
    }

    /** FID как в firebase-installations (RandomFidGenerator): 17 байт, первые 4 бита — 0111, 22 знака base64url. */
    static String fid(byte[] r) {
        byte[] b = new byte[17];
        System.arraycopy(r, 0, b, 0, 16);
        b[16] = b[0];
        b[0] = (byte) ((b[0] & 0x0f) | 0x70);
        return b64url(b).substring(0, 22);
    }

    static String b64url(byte[] d) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder s = new StringBuilder();
        int i = 0;
        for (; i + 2 < d.length; i += 3) {
            int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
            s.append(abc.charAt(v >> 18)).append(abc.charAt((v >> 12) & 63)).append(abc.charAt((v >> 6) & 63)).append(abc.charAt(v & 63));
        }
        if (d.length - i == 1) {
            int v = (d[i] & 0xff) << 16;
            s.append(abc.charAt(v >> 18)).append(abc.charAt((v >> 12) & 63));
        } else if (d.length - i == 2) {
            int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8);
            s.append(abc.charAt(v >> 18)).append(abc.charAt((v >> 12) & 63)).append(abc.charAt((v >> 6) & 63));
        }
        return s.toString();
    }

    /** Ответ сервисов Google: «|ID|3|:токен» → «токен» (старый вид ответа), обычный — как есть. */
    static String cleanReply(String v) {
        if (v == null) return null;
        if (v.startsWith("|")) {
            String[] parts = v.split("\\|");
            v = parts.length == 0 ? "" : parts[parts.length - 1];
            if (v.startsWith(":")) v = v.substring(1);
        }
        return v.trim();
    }

    /** Строковое поле из JSON без org.json (в тестах он заглушка); только простые строки. */
    static String jstr(String json, String key) {
        if (json == null) return "";
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\") : "";
    }

    /** Настройки Firebase от функции: {project_id, sender_id, app_id, api_key}; чего-то нет — null. */
    static String[] parseCfg(String json) {
        String p = jstr(json, "project_id"), s = jstr(json, "sender_id"), app = jstr(json, "app_id"), key = jstr(json, "api_key");
        if (p.isEmpty() || !s.matches("\\d{4,20}") || !app.matches("1:\\d+:android:[0-9a-fA-F]+") || key.isEmpty()) return null;
        return new String[] { p, s, app, key };
    }

    /** «expiresIn»: "604800s" → миллисекунды; не разобрали — 1 час. */
    static long expiresMs(String v) {
        try { return Long.parseLong(v.trim().replace("s", "")) * 1000L; } catch (Throwable e) { return 60L * 60 * 1000; }
    }

    static boolean fisFresh(String at, long exp, long now) { return at != null && !at.isEmpty() && exp - now > FIS_AHEAD_MS; }

    /** Поля запроса токена — как firebase-messaging GmsRpc.setDefaultAttributesToBundle (без v1-ключа). */
    static List<String[]> extras(String[] cfg, String fid, String fisAuth, int gmsv, int osv, int appVer, String appVerName) {
        List<String[]> x = new ArrayList<String[]>();
        x.add(new String[] { "scope", "*" });
        x.add(new String[] { "sender", cfg[1] });
        x.add(new String[] { "subtype", cfg[1] });
        x.add(new String[] { "gmp_app_id", cfg[2] });
        x.add(new String[] { "gmsv", Integer.toString(gmsv) });
        x.add(new String[] { "osv", Integer.toString(osv) });
        x.add(new String[] { "app_ver", Integer.toString(appVer) });
        x.add(new String[] { "app_ver_name", appVerName == null ? "" : appVerName });
        x.add(new String[] { "firebase-app-name-hash", appNameHash() });
        if (fisAuth != null && !fisAuth.isEmpty()) x.add(new String[] { "Goog-Firebase-Installations-Auth", fisAuth });
        x.add(new String[] { "appid", fid });
        x.add(new String[] { "cliv", SDK_FCM });
        return x;
    }

    /** base64url(SHA-1("[DEFAULT]")) — имя приложения Firebase по умолчанию. */
    static String appNameHash() {
        try { return b64url(MessageDigest.getInstance("SHA-1").digest("[DEFAULT]".getBytes("UTF-8"))); } catch (Throwable e) { return "[HASH-ERROR]"; }
    }

    static String hexUpper(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        return s.toString();
    }

    /** Строка в «Настройках»: что с мгновенными уведомлениями на этом телефоне. */
    static String statusText(String st, String err) {
        if ("ok".equals(st)) return "Мгновенные уведомления: подключены — приходят сразу, даже когда приложение закрыто.";
        if ("off".equals(st)) return "Мгновенные уведомления: владелец ещё не настроил Firebase. Пока проверяем раз в ~15 минут.";
        if ("sql".equals(st)) return "Мгновенные уведомления: сервер ещё не готов (нужен SQL). Пока проверяем раз в ~15 минут.";
        if ("nogms".equals(st)) return "Мгновенные уведомления: на телефоне нет сервисов Google. Проверяем раз в ~15 минут.";
        if ("err".equals(st)) return "Мгновенные уведомления: не подключились" + (err == null || err.isEmpty() ? "" : " (" + err + ")")
            + ", повторим через 30 минут. Пока проверяем раз в ~15 минут. Нажмите, чтобы повторить сейчас.";
        return "Мгновенные уведомления: подключаем…";
    }

    /** Короткий текст ошибки для строки состояния: без токенов и адресов, не длиннее 60 знаков. */
    static String shortErr(String s) {
        if (s == null) return "";
        s = s.replaceAll("[A-Za-z0-9_\\-:.]{40,}", "…").replaceAll("[^\\s@]+@[^\\s@]+", "…").replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    // ───────────────────────── точки входа ─────────────────────────

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Из IvaNotifyPatch.tick (поток опроса, окно открыто): подключить/обновить или убрать токен. Быстро, если делать нечего. */
    public static void sync(final Activity a, final String email, final boolean logged, final boolean admin) {
        try {
            final Context c = a.getApplicationContext();
            final SharedPreferences p = prefs(c);
            final String regEmail = p.getString(K_EMAIL, ""), tok = p.getString(K_TOK, "");
            if (shouldForget(regEmail, tok, logged, admin)) {
                if (!begin()) return;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            String[] r = IvaNotifyPatch.nativeCall(IvaClientPatch.SB_URL + "/rest/v1/rpc/iva_push_forget", "POST",
                                "{\"p_token\":" + IvaNotifyPatch.jsonStr(tok) + "}", false, null);
                            if (r != null && r[0] != null && (r[0].startsWith("2") || r[0].startsWith("404")))
                                p.edit().putString(K_EMAIL, "").putLong(K_OK, 0).putString(K_ST, "").apply();
                        } catch (Throwable ignore) {
                        } finally { running = false; }
                    }
                }).start();
                return;
            }
            if (!logged || !admin || email == null || email.trim().isEmpty()) return;
            if (!due(regEmail, email, tok, p.getLong(K_OK, 0), p.getLong(K_TRY, 0), System.currentTimeMillis())) return;
            if (!begin()) return;
            p.edit().putLong(K_TRY, System.currentTimeMillis()).apply();
            new Thread(new Runnable() {
                @Override public void run() {
                    try { register(a, c, email.trim()); } catch (Throwable e) { status(c, "err", e.getMessage()); }
                    finally { running = false; }
                }
            }).start();
        } catch (Throwable ignore) { running = false; }
    }

    static synchronized boolean begin() {
        if (running) return false;
        running = true;
        return true;
    }

    /** «Нажмите, чтобы повторить сейчас» в «Настройках». */
    static void retryNow(Context c) {
        try { prefs(c).edit().putLong(K_TRY, 0).putLong(K_OK, 0).apply(); } catch (Throwable ignore) {}
    }

    static void status(Context c, String st, String err) {
        try { prefs(c).edit().putString(K_ST, st).putString(K_ERR, shortErr(err)).apply(); } catch (Throwable ignore) {}
    }

    static String statusLine(Context c) {
        SharedPreferences p = prefs(c);
        return statusText(p.getString(K_ST, ""), p.getString(K_ERR, ""));
    }

    static boolean hasGms(Context c) {
        try { c.getPackageManager().getPackageInfo(GMS, 0); return true; } catch (Throwable e) { return false; }
    }

    static void register(Activity a, Context c, String email) throws Exception {
        String[] got = http("GET", FN_URL + "?cfg=1&t=" + System.currentTimeMillis(), null, null);
        if (got[0].equals("404")) { status(c, "off", ""); return; }                   // функция ещё не выложена
        if (!got[0].startsWith("2")) throw new Exception("настройки: HTTP " + got[0]);
        String[] cfg = parseCfg(got[1]);
        if (cfg == null) { status(c, "off", ""); return; }
        if (!hasGms(c)) { status(c, "nogms", ""); return; }
        SharedPreferences p = prefs(c);
        String auth = fisAuth(c, p, cfg);
        String fidNow = p.getString(K_FID, "");
        int gmsv = 0, appVer = 0;
        String appVerName = "";
        try { gmsv = c.getPackageManager().getPackageInfo(GMS, 0).versionCode; } catch (Throwable ignore) {}
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            appVer = pi.versionCode; appVerName = pi.versionName;
        } catch (Throwable ignore) {}
        Bundle data = new Bundle();
        for (String[] kv : extras(cfg, fidNow, auth, gmsv, Build.VERSION.SDK_INT, appVer, appVerName)) data.putString(kv[0], kv[1]);
        String tok = gmsToken(c, data);
        String[] r = IvaClientPatch.apiCall(a, "/rest/v1/rpc/iva_push_register", "POST",
            "{\"p_token\":" + IvaNotifyPatch.jsonStr(tok) + "}", true);
        String code = r == null || r[0] == null ? "" : r[0];
        String body = r != null && r.length > 1 && r[1] != null ? r[1] : "";
        if (code.equals("404") || body.contains("PGRST202")) { status(c, "sql", ""); return; }   // функции в базе ещё нет
        if (!code.startsWith("2")) throw new Exception("сервер: HTTP " + code);
        if (body.contains("not_admin")) { status(c, "", ""); return; }
        p.edit().putString(K_TOK, tok).putString(K_EMAIL, email).putLong(K_OK, System.currentTimeMillis())
            .putString(K_ST, "ok").putString(K_ERR, "").apply();
    }

    // ───────────────────────── Firebase Installations ─────────────────────────

    /** Токен «установки» Firebase: свежий — из памяти; иначе обновить; установки нет или её забыли — создать. */
    static String fisAuth(Context c, SharedPreferences p, String[] cfg) throws Exception {
        long now = System.currentTimeMillis();
        if (!cfg[2].equals(p.getString(K_FAPP, ""))) p.edit().remove(K_FID).remove(K_FRT).remove(K_FAT).remove(K_FEXP).putString(K_FAPP, cfg[2]).apply();
        String fid = p.getString(K_FID, ""), frt = p.getString(K_FRT, ""), at = p.getString(K_FAT, "");
        if (!fid.isEmpty() && fisFresh(at, p.getLong(K_FEXP, 0), now)) return at;
        if (!fid.isEmpty() && !frt.isEmpty()) {
            String[] r = http("POST", FIS_URL + cfg[0] + "/installations/" + fid + "/authTokens:generate", fisHeaders(c, cfg, "FIS_v2 " + frt),
                "{\"installation\":{\"sdkVersion\":\"" + SDK_FIS + "\"}}");
            if (r[0].startsWith("2")) {
                String tok = jstr(r[1], "token");
                if (!tok.isEmpty()) {
                    p.edit().putString(K_FAT, tok).putLong(K_FEXP, now + expiresMs(jstr(r[1], "expiresIn"))).apply();
                    return tok;
                }
            }
            if (!r[0].equals("401") && !r[0].equals("404")) throw new Exception("Firebase: HTTP " + r[0]);
            p.edit().remove(K_FID).remove(K_FRT).remove(K_FAT).remove(K_FEXP).apply();   // установку забыли — новая
        }
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        String nf = fid(rnd);
        String[] r = http("POST", FIS_URL + cfg[0] + "/installations", fisHeaders(c, cfg, null),
            "{\"fid\":\"" + nf + "\",\"appId\":" + IvaNotifyPatch.jsonStr(cfg[2]) + ",\"authVersion\":\"FIS_v2\",\"sdkVersion\":\"" + SDK_FIS + "\"}");
        if (!r[0].startsWith("2")) throw new Exception("Firebase: HTTP " + r[0]);
        String gotFid = jstr(r[1], "fid"), rt = jstr(r[1], "refreshToken"), tok = jstr(r[1], "token");
        if (rt.isEmpty() || tok.isEmpty()) throw new Exception("Firebase: пустой ответ");
        p.edit().putString(K_FID, gotFid.isEmpty() ? nf : gotFid).putString(K_FRT, rt).putString(K_FAT, tok)
            .putLong(K_FEXP, now + expiresMs(jstr(r[1], "expiresIn"))).apply();
        return tok;
    }

    static String[][] fisHeaders(Context c, String[] cfg, String auth) {
        List<String[]> h = new ArrayList<String[]>();
        h.add(new String[] { "Content-Type", "application/json" });
        h.add(new String[] { "Accept", "application/json" });
        h.add(new String[] { "Cache-Control", "no-cache" });
        h.add(new String[] { "X-Android-Package", c.getPackageName() });
        String cert = certSha1(c);
        if (!cert.isEmpty()) h.add(new String[] { "X-Android-Cert", cert });
        h.add(new String[] { "x-goog-api-key", cfg[3] });
        if (auth != null) h.add(new String[] { "Authorization", auth });
        return h.toArray(new String[0][]);
    }

    @SuppressWarnings("deprecation")
    static String certSha1(Context c) {
        try {
            android.content.pm.Signature[] s = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            return s == null || s.length == 0 ? "" : hexUpper(MessageDigest.getInstance("SHA-1").digest(s[0].toByteArray()));
        } catch (Throwable e) { return ""; }
    }

    /** HTTP-запрос → {код, тело}; сеть упала — {"0", сообщение}. */
    static String[] http(String method, String url, String[][] headers, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setUseCaches(false);
            if (headers != null) for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
            if (body != null) {
                c.setDoOutput(true);
                if (headers == null) c.setRequestProperty("Content-Type", "application/json");
                OutputStream out = c.getOutputStream();
                out.write(body.getBytes("UTF-8"));
                out.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 65536) break; }
                in.close();
            }
            return new String[] { Integer.toString(code), b.toString("UTF-8") };
        } catch (Throwable e) {
            return new String[] { "0", String.valueOf(e.getMessage()) };
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // ───────────────────────── токен FCM у сервисов Google ─────────────────────────

    static synchronized int nextId() { return ++lastId; }

    /** Запрос токена у сервисов Google; ответ — токен или исключение с кодом ошибки (SERVICE_NOT_AVAILABLE и т. п.). */
    static String gmsToken(Context c, Bundle extras) throws Exception {
        final Context app = c.getApplicationContext();
        final LinkedBlockingQueue<Message> replies = new LinkedBlockingQueue<Message>();
        final LinkedBlockingQueue<IBinder> binders = new LinkedBlockingQueue<IBinder>();
        HandlerThread ht = new HandlerThread("iva-push");
        ht.start();
        Messenger me = new Messenger(new Handler(ht.getLooper()) {
            @Override public void handleMessage(Message m) { replies.offer(Message.obtain(m)); }
        });
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) { if (b != null) binders.offer(b); }
            @Override public void onServiceDisconnected(ComponentName n) {}
        };
        boolean bound = false;
        int id = nextId();
        try {
            Intent bind = new Intent(ACT_REGISTER).setPackage(GMS);
            try { bound = app.bindService(bind, conn, Context.BIND_AUTO_CREATE); } catch (Throwable e) { bound = false; }
            if (bound) {
                IBinder b = binders.poll(15, TimeUnit.SECONDS);
                if (b == null) throw new Exception("SERVICE_NOT_AVAILABLE");
                Bundle d = new Bundle();
                d.putString("pkg", app.getPackageName());
                d.putBoolean("oneWay", false);
                d.putBundle("data", extras);
                Message m = Message.obtain();
                m.what = 1;
                m.arg1 = id;
                m.replyTo = me;
                m.setData(d);
                new Messenger(b).send(m);
            } else {
                // запасной путь (старые сервисы Google): служба + ответ в наш Messenger
                Intent it = new Intent(ACT_REGISTER).setPackage(GMS);
                it.putExtras(extras);
                it.putExtra("google.messenger", me);
                it.putExtra("kid", "|ID|" + id + "|");
                it.putExtra("X-kid", "|ID|" + id + "|");
                Intent self = new Intent().setPackage("com.google.example.invalidpackage");
                it.putExtra("app", PendingIntent.getBroadcast(app, 0, self, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
                if (app.startService(it) == null) throw new Exception("MISSING_INSTANCEID_SERVICE");
            }
            long until = System.currentTimeMillis() + 30000;
            while (true) {
                long left = until - System.currentTimeMillis();
                Message r = left > 0 ? replies.poll(left, TimeUnit.MILLISECONDS) : null;
                if (r == null) throw new Exception("TIMEOUT");
                Bundle res = null;
                if (r.obj instanceof Intent) res = ((Intent) r.obj).getExtras();
                else if (r.arg1 == id || !bound) {
                    Bundle rd = r.getData();
                    if (rd != null && rd.getBoolean("unsupported", false)) throw new Exception("UNSUPPORTED");
                    res = rd == null ? null : rd.getBundle("data");
                }
                if (res == null) continue;
                String tok = cleanReply(res.getString("registration_id"));
                if (tok != null && !tok.isEmpty()) return tok;
                String err = cleanReply(res.getString("error"));
                if (err != null && !err.isEmpty()) throw new Exception(err);
            }
        } finally {
            if (bound) try { app.unbindService(conn); } catch (Throwable ignore) {}
            try { ht.quit(); } catch (Throwable ignore) {}
        }
    }

    // ───────────────────────── приход push ─────────────────────────

    /** Приёмник сообщений FCM (запись в манифесте — axml_push.py: exported, permission c2dm SEND, action c2dm RECEIVE). */
    public static final class Rx extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            try {
                if (intent == null || !ACT_RECEIVE.equals(intent.getAction())) return;
                if (isOrderedBroadcast()) setResultCode(-1);              // как Firebase: сообщение принято
                final Context app = ctx.getApplicationContext();
                if (IvaNotifyPatch.activityAlive()) {                     // окно открыто — обычная проверка сразу
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { try { IvaNotifyPatch.wake(); } catch (Throwable ignore) {} }
                    });
                    return;
                }
                final PendingResult pr = goAsync();                      // закрыто — фоновая проверка, как раз в 15 минут
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { IvaNotifyPatch.bgRun(app); } catch (Throwable ignore) {
                        } finally { try { pr.finish(); } catch (Throwable ignore) {} }
                    }
                }).start();
            } catch (Throwable ignore) {}
        }
    }
}

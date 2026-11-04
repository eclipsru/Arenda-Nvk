package ru.prokatnvsk.app;

// 10.132 (просьба владельца 04.11.2026):
//
//   1. «Везде, где есть пункт выдачи, — кнопка „Показать на карте“; при нажатии — выбор карт, установленных у клиента».
//      Кнопка рамкой (как «Добавить»). Нажатие — системный выбор приложения (Intent.createChooser) для ссылки
//      geo:широта,долгота?q=широта,долгота(Название) — её понимают Яндекс Карты, 2ГИС, Google Карты, Maps.me, OsmAnd.
//      Координат у пункта нет (адрес набран без подсказки) — geo:0,0?q=адрес, карты ищут адрес сами.
//      Карт нет совсем — Яндекс Карты на сайте в браузере.
//      Места: карточка инструмента, страница проката и его «Пункты выдачи», заявки клиента и прокату (список,
//      подробности), «Мой пункт» сотрудника, «Пункты выдачи» владельца.
//
//   2. «На карточках главной на картинке в правом нижнем углу — расстояние от телефона до пункта выдачи».
//      Решение владельца: «Настройки» → «Расстояние до проката» с выключателем, по умолчанию ВЫКЛЮЧЕНО; разрешение на
//      геолокацию спрашивается только после включения; показывается всем, кто включил (клиент, сотрудник, владелец);
//      по прямой, «≈ 2,4 км». Место телефона — только в памяти приложения, никуда не отправляется. Координаты пункта —
//      pickup_lat / pickup_lng инструмента (штатный список каталога), нет — адрес пункта через Photon (как подсказки
//      адреса), запоминается на телефоне. Карточки — штатные (feedCard): после каждой отрисовки ленты (renderFeed)
//      ставим подпись в ту же рамку картинки, справа снизу (слева снизу — значок доставки, сверху — «избранное»).
//      Какая карточка какой инструмент — тем же отбором, что renderFeed, и сверяем название; не сошлось — не подписываем.
//
//   Без новых вставок в smali: вход — IvaPatch.onTab (лента) и вызовы из наших страниц. Ответ на запрос разрешения
//   (onRequestPermissionsResult) штатный и к нам не приходит: результат узнаём, когда страница настроек снова в фокусе.
//   Код запроса 7712 (штатный 771 — запись голоса). Сеть — только в своём потоке.

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class IvaGeoPatch {
    private IvaGeoPatch() {}

    static final String MAP = "Показать на карте", CHOOSER = "Открыть в картах";
    static final String TITLE = "Расстояние до проката";
    static final String SUB = "На карточках главной — сколько до пункта выдачи по прямой";
    static final String NOTE = "Место телефона нужно только для подсчёта на телефоне — в приложение и прокату оно не отправляется.";
    static final String DENIED = "Нет доступа к местоположению. Разрешите его приложению в настройках Android и включите снова.";
    static final String OPEN_SYS = "Открыть настройки Android";
    static final String K_DIST = "dist_on";                    // в настройках уведомлений на телефоне (iva_notify), по умолчанию выключено
    static final int REQ_LOC = 7712;                            // не 771: штатный onRequestPermissionsResult на 771 включает запись голоса
    static final String FINE = "android.permission.ACCESS_FINE_LOCATION", COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    static final long FRESH_MS = 10 * 60 * 1000L, FIX_WAIT_MS = 30000L, GEO_RETRY_MS = 24 * 3600 * 1000L;
    static final int GEO_MAX = 8;                              // адресов за один проход ленты

    // ════════════════════════════════ чистая логика (IvaPatchTest) ════════════════════════════════

    static boolean ok(Double lat, Double lng) {
        if (lat == null || lng == null || lat.isNaN() || lng.isNaN() || lat.isInfinite() || lng.isInfinite()) return false;
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return false;
        return !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6);
    }

    static String num(double v) { return String.format(Locale.ROOT, "%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", ""); }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return ""; }
    }

    static String clean(String s) {
        String t = s == null ? "" : s.trim();
        return "null".equals(t) ? "" : t.replaceAll("\\s+", " ");
    }

    /** «Новосибирск, ул. Ленина, 1» — город, если его нет в адресе. */
    static String query(String address, String city) {
        String ad = clean(address), c = clean(city);
        if (c.isEmpty() || ad.toLowerCase(Locale.ROOT).contains(c.toLowerCase(Locale.ROOT))) return ad;
        return ad.isEmpty() ? c : c + ", " + ad;
    }

    /** Ссылка для карт: координаты (с подписью) или поиск по адресу; нечего показать — "". */
    static String geoUri(String name, String address, String city, Double lat, Double lng) {
        if (ok(lat, lng)) {
            String ll = num(lat) + "," + num(lng);
            String label = clean(name).replace('(', ' ').replace(')', ' ').trim();
            if (label.isEmpty()) label = query(address, city).replace('(', ' ').replace(')', ' ').trim();
            return "geo:" + ll + "?q=" + ll + (label.isEmpty() ? "" : "(" + enc(label) + ")");
        }
        String q = query(address, city);
        return q.isEmpty() ? "" : "geo:0,0?q=" + enc(q);
    }

    /** Карт на телефоне нет — Яндекс Карты на сайте. */
    static String webUrl(String address, String city, Double lat, Double lng) {
        if (ok(lat, lng)) return "https://yandex.ru/maps/?pt=" + num(lng) + "," + num(lat) + "&z=16&l=map";
        String q = query(address, city);
        return q.isEmpty() ? "" : "https://yandex.ru/maps/?text=" + enc(q);
    }

    /** Расстояние по прямой (км), формула гаверсинусов. */
    static double km(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0088, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** «≈ 350 м», «≈ 2,4 км», «≈ 24 км», «≈ 1 250 км». */
    static String distText(double km) {
        if (Double.isNaN(km) || km < 0) return "";
        if (km < 0.995) {
            long m = Math.max(10, Math.round(km * 100) * 10);
            return m >= 1000 ? "≈ 1 км" : "≈ " + m + " м";
        }
        if (km < 9.95) return "≈ " + String.format(Locale.ROOT, "%.1f", km).replace(".0", "").replace('.', ',') + " км";
        long k = Math.round(km);
        String s = String.valueOf(k);
        if (k >= 1000) s = s.substring(0, s.length() - 3) + "\u00a0" + s.substring(s.length() - 3);
        return "≈ " + s + " км";
    }

    /** Отбор штатной ленты (renderFeed): не «сдан», категория (cat или cats), подкатегория, поиск по названию и описанию. */
    static boolean shown(String tStatus, String cat, List<String> cats, String sub, String name, String descr,
                         String catMode, String curCat, String curSub, String query) {
        if ("rented".equals(tStatus)) return false;
        boolean catOn = "cat".equals(catMode);
        if (catOn && curCat != null && !curCat.isEmpty()) {
            boolean in = curCat.equals(cat == null ? "" : cat);
            if (!in && cats != null) for (String c : cats) if (curCat.equals(c)) { in = true; break; }
            if (!in) return false;
        }
        if (catOn && curSub != null && !curSub.isEmpty() && !curSub.equals(sub == null ? "" : sub)) return false;
        String hay = ((name == null ? "" : name) + " " + (descr == null ? "" : descr)).toLowerCase();
        return query == null || query.isEmpty() || hay.contains(query);
    }

    /** Подпись карточки — это тот инструмент? (штатная подпись — shorten(name, 34), сравниваем начало). */
    static boolean sameTitle(String title, String name) {
        String t = title == null ? "" : title.trim(), n = name == null ? "" : name.trim();
        if (n.isEmpty()) return t.isEmpty();
        int k = Math.min(12, Math.min(t.length(), n.length()));
        return k > 0 && t.regionMatches(true, 0, n, 0, k);
    }

    static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        try { return Double.valueOf(s.replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    /** Запомненные координаты адреса: «55.03,82.92» / «-» (не нашёлся) + время. */
    static String cacheVal(Double lat, Double lng, long at) { return (ok(lat, lng) ? num(lat) + "," + num(lng) : "-") + "@" + at; }

    static double[] cacheLatLng(String v) {
        if (v == null) return null;
        int at = v.indexOf('@');
        String p = at >= 0 ? v.substring(0, at) : v;
        String[] x = p.split(",");
        if (x.length != 2) return null;
        Double la = dbl(x[0]), ln = dbl(x[1]);
        return ok(la, ln) ? new double[] {la, ln} : null;
    }

    static long cacheAt(String v) {
        int at = v == null ? -1 : v.indexOf('@');
        if (at < 0) return 0;
        try { return Long.parseLong(v.substring(at + 1)); } catch (NumberFormatException e) { return 0; }
    }

    // ════════════════════════════════ «Показать на карте» ════════════════════════════════

    static final Map<ViewGroup, View> mapBtns = new WeakHashMap<ViewGroup, View>();

    /** Кнопка рамкой «Показать на карте»; показать нечего — null. */
    static TextView button(final Activity a, final String name, final String address, final String city, final Double lat, final Double lng) {
        if (a == null || geoUri(name, address, city, lat, lng).isEmpty()) return null;
        int cOrange = IvaStaffPatch.color(a, "orange", 0xffff8f1f);
        TextView b = new TextView(a);
        b.setText(MAP);
        b.setTextSize(14f);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(cOrange);
        b.setGravity(Gravity.CENTER);
        b.setBackground(IvaPatch.accentBg(a, cOrange, 12));
        b.setPadding(dp(a, 14), dp(a, 9), dp(a, 14), dp(a, 9));
        try {
            IvaPickupPatch.Pin pin = new IvaPickupPatch.Pin(cOrange, a.getResources().getDisplayMetrics().density);
            pin.setBounds(0, 0, dp(a, 16), dp(a, 18));
            b.setCompoundDrawables(pin, null, null, null);
            b.setCompoundDrawablePadding(dp(a, 6));
        } catch (Throwable ignore) {}
        b.setClickable(true);
        b.setContentDescription(MAP + (clean(name).isEmpty() ? "" : ": " + clean(name)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(a, name, address, city, lat, lng); }
        });
        return b;
    }

    /** Добавить кнопку в столбец (full — во всю ширину). Повторный вызов для того же столбца заменяет кнопку. */
    static View add(Activity a, LinearLayout parent, String name, String address, String city, Double lat, Double lng, boolean full) {
        return place(a, parent, name, address, city, lat, lng, full, true);
    }

    /** once — одна кнопка на столбец (заменяется); иначе — ещё одна (заявка из нескольких частей с разными пунктами). */
    static View place(Activity a, LinearLayout parent, String name, String address, String city, Double lat, Double lng, boolean full, boolean once) {
        if (parent == null) return null;
        if (once) {
            View old = mapBtns.get(parent);
            if (old != null && old.getParent() == parent) parent.removeView(old);
        }
        TextView b = button(a, name, address, city, lat, lng);
        if (b == null) return null;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(full ? -1 : -2, -2);
        lp.topMargin = dp(a, once ? 10 : 6);
        parent.addView(b, lp);
        if (once) mapBtns.put(parent, b);
        return b;
    }

    /** Пункт из IvaPickupPatch (у владельца и сотрудника — с координатами, у заявок — без). */
    static View add(Activity a, LinearLayout parent, IvaPickupPatch.Point p, boolean full) {
        if (p == null) return null;
        return add(a, parent, p.name, p.address, p.city, p.lat, p.lng, full);
    }

    /** Пункт заявки (JSON «point» части или адрес проката, если пунктов нет). */
    static View addOrder(Activity a, LinearLayout parent, JSONObject part) {
        if (part == null) return null;
        JSONObject pt = part.optJSONObject("point");
        if (pt == null) {
            String ad = clean(part.optString("prokat_addr", ""));
            return ad.isEmpty() ? null : place(a, parent, "", ad, "", null, null, false, false);
        }
        return place(a, parent, clean(pt.optString("name", "")), clean(pt.optString("address", "")), clean(pt.optString("city", "")),
            dbl(pt.opt("lat")), dbl(pt.opt("lng")), false, false);
    }

    /** Системный выбор карт; карт нет — сайт Яндекс Карт. */
    static void open(Activity a, String name, String address, String city, Double lat, Double lng) {
        if (a == null) return;
        String g = geoUri(name, address, city, lat, lng);
        if (g.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(g));
            a.startActivity(Intent.createChooser(i, CHOOSER));
            return;
        } catch (ActivityNotFoundException e) {
            // ниже — сайт
        } catch (Throwable e) {
            // ниже — сайт
        }
        String w = webUrl(address, city, lat, lng);
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(w))); }
        catch (Throwable e) { Toast.makeText(a, "Нет приложения карт. Адрес: " + query(address, city), Toast.LENGTH_LONG).show(); }
    }

    /** Карточка инструмента: кнопка под штатным адресом (detAddr). Пункт известен — с его названием. */
    static void detail(Activity a, IvaPickupPatch.Point p) {
        Object v = IvaClientPatch.get(a, "detAddr");
        Object it = IvaClientPatch.get(a, "detItem");
        if (!(v instanceof TextView) || !(it instanceof JSONObject)) return;
        if (Boolean.TRUE.equals(IvaClientPatch.get(a, "detIsSrv"))) return;
        TextView t = (TextView) v;
        if (!(t.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) t.getParent();
        JSONObject j = (JSONObject) it;
        String ad = clean(j.optString("pickup_address", "")), city = clean(j.optString("pickup_city", ""));
        Double lat = dbl(j.opt("pickup_lat")), lng = dbl(j.opt("pickup_lng"));
        String name = p == null ? "" : clean(p.name);
        if (p != null) {
            if (ad.isEmpty()) ad = clean(p.address);
            if (city.isEmpty()) city = clean(p.city);
        }
        View old = mapBtns.get(parent);
        if (old != null && old.getParent() == parent) parent.removeView(old);
        mapBtns.remove(parent);
        if (t.getVisibility() != View.VISIBLE) return;
        TextView b = button(a, name.isEmpty() ? "Пункт выдачи" : name, ad, city, lat, lng);
        if (b == null) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(a, 8);
        lp.bottomMargin = dp(a, 4);
        parent.addView(b, parent.indexOfChild(t) + 1, lp);
        mapBtns.put(parent, b);
    }

    // ════════════════════════════════ «Расстояние до проката» ════════════════════════════════

    static SharedPreferences prefs(Context c) { return IvaNotifyPatch.prefs(c); }

    static boolean on(Context c) { try { return prefs(c).getBoolean(K_DIST, false); } catch (Throwable e) { return false; } }

    static void setOn(Context c, boolean v) { try { prefs(c).edit().putBoolean(K_DIST, v).apply(); } catch (Throwable ignore) {} }

    static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return c.checkSelfPermission(FINE) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(COARSE) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) { return false; }
    }

    static volatile double[] here;                 // {широта, долгота} телефона — только в памяти
    static volatile long hereAt;                   // когда получено (System.currentTimeMillis)
    static volatile boolean locating;
    static Handler main;

    static Handler main() { if (main == null) main = new Handler(Looper.getMainLooper()); return main; }

    /** Место телефона: последнее известное (gps / сеть / пассивно), устарело — одно новое определение. done — на главном потоке. */
    static void locate(final Activity a, final Runnable done) {
        if (a == null || !granted(a)) return;
        final LocationManager lm;
        try { lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE); } catch (Throwable e) { return; }
        if (lm == null) return;
        Location best = null;
        for (String p : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignore) {}
        }
        if (best != null) {
            double[] was = here;
            here = new double[] {best.getLatitude(), best.getLongitude()};
            hereAt = Math.min(System.currentTimeMillis(), best.getTime());
            if (was == null && done != null) main().post(done);
        }
        boolean stale = best == null || System.currentTimeMillis() - best.getTime() > FRESH_MS;
        if (!stale || locating) return;
        String prov = null;
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) prov = LocationManager.NETWORK_PROVIDER;
            else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) prov = LocationManager.GPS_PROVIDER;
        } catch (Throwable ignore) {}
        if (prov == null) return;
        locating = true;
        final LocationListener ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                locating = false;
                if (l == null) return;
                here = new double[] {l.getLatitude(), l.getLongitude()};
                hereAt = System.currentTimeMillis();
                if (done != null) try { done.run(); } catch (Throwable ignore) {}
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try { lm.requestSingleUpdate(prov, ll, Looper.getMainLooper()); }
        catch (Throwable e) { locating = false; return; }
        main().postDelayed(new Runnable() {
            @Override public void run() {
                if (!locating) return;
                locating = false;
                try { lm.removeUpdates(ll); } catch (Throwable ignore) {}
            }
        }, FIX_WAIT_MS);
    }

    // ───────────── подписи на карточках ленты ─────────────

    static final Map<View, Boolean> watched = new WeakHashMap<View, Boolean>();
    static final Map<FrameLayout, TextView> chips = new WeakHashMap<FrameLayout, TextView>();
    static WeakReference<View> lastFirst = new WeakReference<View>(null);
    static int lastCount = -1;
    static volatile boolean dirty;
    static final Map<String, String> geo = new HashMap<String, String>();   // адрес → «широта,долгота@время» (и на телефоне)
    static volatile boolean geocoding;

    /** IvaPatch.onTab: следить за лентой (после каждой отрисовки — подписи). */
    static void onTab(final Activity a) {
        Object g = IvaClientPatch.get(a, "feedGrid");
        if (!(g instanceof ViewGroup)) return;
        final ViewGroup grid = (ViewGroup) g;
        if (!watched.containsKey(grid)) {
            watched.put(grid, Boolean.TRUE);
            final WeakReference<Activity> wa = new WeakReference<Activity>(a);
            final WeakReference<ViewGroup> wg = new WeakReference<ViewGroup>(grid);
            grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        Activity x = wa.get();
                        ViewGroup gr = wg.get();
                        if (x == null || gr == null) return;
                        View first = gr.getChildCount() > 0 ? gr.getChildAt(0) : null;
                        if (!dirty && first == lastFirst.get() && gr.getChildCount() == lastCount) return;   // лента та же
                        lastFirst = new WeakReference<View>(first);
                        lastCount = gr.getChildCount();
                        dirty = false;
                        decorate(x, gr);
                    } catch (Throwable ignore) {}
                }
            });
        }
        dirty = true;
        if (on(a) && granted(a) && (here == null || System.currentTimeMillis() - hereAt > FRESH_MS)) {
            locate(a, redraw(a));
        }
        try { decorate(a, grid); } catch (Throwable ignore) {}
    }

    static Runnable redraw(Activity a) {
        final WeakReference<Activity> wa = new WeakReference<Activity>(a);
        return new Runnable() {
            @Override public void run() {
                Activity x = wa.get();
                Object g = x == null ? null : IvaClientPatch.get(x, "feedGrid");
                if (g instanceof ViewGroup) try { decorate(x, (ViewGroup) g); } catch (Throwable ignore) {}
            }
        };
    }

    static String s(JSONObject j, String k) { return j == null || j.isNull(k) ? "" : j.optString(k, ""); }

    static List<String> list(JSONArray arr) {
        if (arr == null) return null;
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, ""));
        return out;
    }

    /** Инструменты в том порядке, в каком их показывает renderFeed. */
    static List<JSONObject> visible(Activity a) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        Object t = IvaClientPatch.get(a, "tools");
        if (!(t instanceof List)) return out;
        String catMode = str(IvaClientPatch.get(a, "catMode")), curCat = str(IvaClientPatch.get(a, "curCat")),
            curSub = str(IvaClientPatch.get(a, "curSub")), query = str(IvaClientPatch.get(a, "query"));
        for (Object o : new ArrayList<Object>((List<?>) t)) {
            if (!(o instanceof JSONObject)) continue;
            JSONObject j = (JSONObject) o;
            if (shown(j.optString("t_status", ""), j.optString("cat"), list(j.optJSONArray("cats")), j.optString("sub", ""),
                j.optString("name", ""), j.optString("descr", ""), catMode, curCat, curSub, query)) out.add(j);
        }
        return out;
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** Подписи «≈ 2,4 км» на карточках ленты (главный поток). Выключено / нет доступа / не сошлось — подписей нет. */
    static void decorate(final Activity a, ViewGroup grid) {
        boolean show = on(a) && granted(a) && here != null;
        List<FrameLayout> frames = new ArrayList<FrameLayout>();
        List<String> titles = new ArrayList<String>();
        for (int r = 0; r < grid.getChildCount(); r++) {
            View row = grid.getChildAt(r);
            if (!(row instanceof LinearLayout) || ((LinearLayout) row).getOrientation() != LinearLayout.HORIZONTAL) continue;
            LinearLayout rg = (LinearLayout) row;
            for (int c = 0; c < rg.getChildCount(); c++) {
                View card = rg.getChildAt(c);
                if (!(card instanceof LinearLayout) || ((LinearLayout) card).getChildCount() < 2) continue;
                LinearLayout cg = (LinearLayout) card;
                if (!(cg.getChildAt(0) instanceof FrameLayout)) continue;
                frames.add((FrameLayout) cg.getChildAt(0));
                titles.add(title(cg.getChildAt(1)));
            }
        }
        if (frames.isEmpty()) return;
        List<JSONObject> tools = show ? visible(a) : null;
        if (show && tools.size() != frames.size()) show = false;                      // лента не та, что думаем, — не подписываем
        if (show) for (int i = 0; i < frames.size(); i++) if (!sameTitle(titles.get(i), tools.get(i).optString("name", ""))) { show = false; break; }
        List<String[]> need = new ArrayList<String[]>();
        double[] me = here;
        for (int i = 0; i < frames.size(); i++) {
            FrameLayout f = frames.get(i);
            String text = "";
            if (show && me != null) {
                JSONObject j = tools.get(i);
                double[] p = pointOf(a, j, need);
                if (p != null) text = distText(km(me[0], me[1], p[0], p[1]));
            }
            chip(a, f, text);
        }
        if (show && !need.isEmpty()) geocode(a, need);
    }

    static String title(View box) {
        if (box instanceof ViewGroup && ((ViewGroup) box).getChildCount() > 0 && ((ViewGroup) box).getChildAt(0) instanceof TextView)
            return String.valueOf(((TextView) ((ViewGroup) box).getChildAt(0)).getText());
        return "";
    }

    /** Координаты пункта инструмента: из каталога; нет — запомненные по адресу; нет и их — адрес в очередь Photon. */
    static double[] pointOf(Context c, JSONObject j, List<String[]> need) {
        Double lat = dbl(j.opt("pickup_lat")), lng = dbl(j.opt("pickup_lng"));
        if (ok(lat, lng)) return new double[] {lat, lng};
        String q = query(s(j, "pickup_address"), s(j, "pickup_city"));
        if (q.isEmpty()) return null;
        String v;
        synchronized (geo) {
            v = geo.get(q);
            if (v == null) {
                try { v = prefs(c).getString("geo_" + Integer.toHexString(q.hashCode()), null); } catch (Throwable ignore) {}
                if (v != null) geo.put(q, v);
            }
        }
        if (v != null) {
            double[] p = cacheLatLng(v);
            if (p != null || System.currentTimeMillis() - cacheAt(v) < GEO_RETRY_MS) return p;
        }
        boolean dup = false;
        for (String[] x : need) if (x[0].equals(q)) dup = true;
        if (!dup && need.size() < GEO_MAX) need.add(new String[] {q});
        return null;
    }

    /** Адреса пунктов без координат — через Photon (как подсказки адреса), в своём потоке; потом — подписи заново. */
    static void geocode(final Activity a, final List<String[]> need) {
        if (geocoding) return;
        geocoding = true;
        final Context app = a.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean any = false;
                try {
                    for (String[] x : need) {
                        String q = x[0];
                        Double lat = null, lng = null;
                        List<String[]> r = null;
                        try { r = IvaPickupPatch.suggest(q); } catch (Throwable ignore) {}
                        if (r == null) continue;                                    // нет связи — спросим в другой раз
                        for (String[] h : r) {
                            if (h.length < 5) continue;
                            Double la = dbl(h[3]), ln = dbl(h[4]);
                            if (ok(la, ln)) { lat = la; lng = ln; break; }
                        }
                        String v = cacheVal(lat, lng, System.currentTimeMillis());
                        synchronized (geo) { geo.put(q, v); }
                        try { prefs(app).edit().putString("geo_" + Integer.toHexString(q.hashCode()), v).apply(); } catch (Throwable ignore) {}
                        if (ok(lat, lng)) any = true;
                    }
                } finally {
                    geocoding = false;
                }
                if (any) main().post(redraw(a));
            }
        }, "iva-geo").start();
    }

    /** Подпись в правом нижнем углу картинки карточки; text пустой — убрать. */
    static void chip(Activity a, FrameLayout f, String text) {
        TextView t = chips.get(f);
        if (t != null && t.getParent() != f) t = null;
        if (text == null || text.isEmpty()) {
            if (t != null) f.removeView(t);
            chips.remove(f);
            return;
        }
        if (t == null) {
            t = new TextView(a);
            t.setTextSize(11f);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(0xfff2f4f8);
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xcc101217);
            bg.setCornerRadius(dp(a, 8));
            t.setBackground(bg);
            t.setPadding(dp(a, 6), dp(a, 3), dp(a, 6), dp(a, 3));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
            lp.rightMargin = dp(a, 6);
            lp.bottomMargin = dp(a, 6);
            f.addView(t, lp);
            chips.put(f, t);
        }
        if (!text.contentEquals(t.getText())) t.setText(text);
        t.setContentDescription("До пункта выдачи " + text.replace("≈", "около"));
    }

    // ───────────── «Настройки» → «Расстояние до проката» ─────────────

    static volatile boolean asked;                 // ждём ответ на запрос разрешения

    /** Строка в «Настройках» (под «Уведомлениями») — всем; по нажатию — своя страница с выключателем. */
    static void settingsRow(final Activity a, LinearLayout page, final int cText, final int cGray, final int cOrange) {
        if (a == null || page == null) return;
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.Drawable hbg = IvaSearchPatch.drawable(a, "bg_card");
        if (hbg != null) head.setBackground(hbg);
        head.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        head.setClickable(true);
        View icon = new View(a);
        IvaPickupPatch.Pin pin = new IvaPickupPatch.Pin(cOrange, a.getResources().getDisplayMetrics().density);
        icon.setBackground(pin);
        head.addView(icon, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 22)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(a);
        title.setText(TITLE);
        title.setTextSize(15f);
        title.setTextColor(cText);
        col.addView(title);
        final TextView sub = new TextView(a);
        sub.setTextSize(13f);
        sub.setTextColor(cGray);
        sub.setText(summary(a));
        col.addView(sub);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 12);
        head.addView(col, cl);
        android.widget.ImageView chev = new android.widget.ImageView(a);
        android.graphics.drawable.Drawable chd = IvaSearchPatch.drawable(a, "ic_chevron");
        if (chd != null) { chev.setImageDrawable(chd); chev.setColorFilter(cGray); }
        head.addView(chev, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(a, 10);
        page.addView(head, hl);
        head.setContentDescription(TITLE + ", открыть");
        final Runnable onChange = new Runnable() { @Override public void run() { sub.setText(summary(a)); } };
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { openPage(a, cText, cGray, cOrange, onChange); } catch (Throwable ignore) {}
            }
        });
    }

    static String summary(Context c) { return !on(c) ? "Выключено" : granted(c) ? "Включено · по прямой" : "Нет доступа к местоположению"; }

    static void openPage(final Activity a, int cText, final int cGray, final int cOrange, final Runnable onChange) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.Drawable bg = IvaSearchPatch.drawable(a, "bg_card");
        if (bg != null) card.setBackground(bg);
        card.setPadding(dp(a, 16), dp(a, 6), dp(a, 12), dp(a, 6));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(a, 8);
        body.addView(card, cl);
        final Switch sw = IvaNotifyPatch.switchRow(a, card, TITLE, SUB, K_DIST, cText, cGray, cOrange);
        sw.setChecked(on(a) && granted(a));
        TextView note = new TextView(a);
        note.setText(NOTE);
        note.setTextSize(13f);
        note.setTextColor(cGray);
        note.setPadding(dp(a, 4), dp(a, 10), dp(a, 4), 0);
        body.addView(note);
        final TextView warn = new TextView(a);
        warn.setText(DENIED);
        warn.setTextSize(13f);
        warn.setTextColor(cOrange);
        warn.setPadding(dp(a, 4), dp(a, 12), dp(a, 4), 0);
        warn.setVisibility(View.GONE);
        body.addView(warn);
        final TextView sys = new TextView(a);
        sys.setText(OPEN_SYS);
        sys.setTextSize(14f);
        sys.setTypeface(null, Typeface.BOLD);
        sys.setTextColor(cOrange);
        sys.setGravity(Gravity.CENTER);
        sys.setBackground(IvaPatch.accentBg(a, cOrange, 12));
        sys.setPadding(dp(a, 14), dp(a, 11), dp(a, 14), dp(a, 11));
        sys.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(a, 10);
        body.addView(sys, sl);
        sys.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.getPackageName())));
                } catch (Throwable ignore) {}
            }
        });
        if (on(a) && !granted(a)) { setOn(a, false); warn.setVisibility(View.VISIBLE); sys.setVisibility(View.VISIBLE); }
        final boolean[] quiet = {false};
        final Runnable denied = new Runnable() {
            @Override public void run() {
                asked = false;
                setOn(a, false);
                quiet[0] = true;
                sw.setChecked(false);
                quiet[0] = false;
                warn.setVisibility(View.VISIBLE);
                sys.setVisibility(View.VISIBLE);
                onChange.run();
                redraw(a).run();
            }
        };
        final Runnable allowed = new Runnable() {
            @Override public void run() {
                asked = false;
                setOn(a, true);
                warn.setVisibility(View.GONE);
                sys.setVisibility(View.GONE);
                onChange.run();
                locate(a, redraw(a));
                redraw(a).run();
            }
        };
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                if (quiet[0]) return;
                if (!v) {
                    asked = false;
                    setOn(a, false);
                    onChange.run();
                    redraw(a).run();
                    return;
                }
                if (granted(a)) { allowed.run(); return; }
                setOn(a, true);                                              // пока ждём ответ; отказ — выключим
                asked = true;
                try { a.requestPermissions(new String[] {FINE, COARSE}, REQ_LOC); }
                catch (Throwable e) { denied.run(); return; }
                // «Больше не спрашивать» — окно Android не появится, фокус не уйдёт: проверим сами
                final View page = b;
                main().postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!asked || !page.hasWindowFocus()) return;
                        if (granted(a)) allowed.run(); else denied.run();
                    }
                }, 1500);
            }
        });
        IvaChiefPatch.subPage(a, TITLE, body, new Runnable() {
            @Override public void run() {                                    // страница снова в фокусе — ответ на запрос
                if (!asked) return;
                if (granted(a)) allowed.run(); else denied.run();
            }
        });
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}

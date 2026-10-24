package ru.prokatnvsk.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 10.98 — аватарки (просьба владельца 16.10.2026: «теперь у всех»).
 *
 * 1) Круг без лишних краёв. Штатный кружок фото (CircleImageView) рисует картинку кругом, но фон под ним был другой
 *    формы: в «Мои данные» — серый скруглённый квадрат bg_photo (его углы торчали вокруг фото), в строке «Кабинета» —
 *    кольцо bg_avatar. Теперь фон — круг Hole, и он рисуется только пока фото нет (буквы / значок); под фото — ничего.
 *    Старые фото не квадратные: штатный кружок брал их левый верхний угол — теперь центр (squareFix).
 * 2) Крупнее: строка «Кабинета» 54 → 72dp, «Мои данные» и «Настройки профиля» 96 → 120dp.
 * 3) Просмотр: нажатие на фото — фото во весь экран (кругом) и «Изменить фото»; фото нет — сразу выбор из галереи.
 * 4) При загрузке — выбор области: круг на картинке двигается пальцем, размер — двумя пальцами или за точку на краю;
 *    сохраняется квадрат под кругом (512×512 JPEG), так что в любом кружке видно ровно выбранное.
 *
 * Новых вставок в smali нет: строка «Кабинета» — из IvaClientPatch.onProfile (после штатного renderProfile),
 * «Мои данные» — из IvaPatch.onTab("adminData") (после штатного loadAdminData), выбор фото — невидимый Fragment
 * (как AvatarPicker 10.39: результат приходит ему, минуя MainActivity.onActivityResult).
 * Фото админа — как штатно: файл через штатный uploadPhoto, адрес — в admins.avatar (то же поле, что пишет «Сохранить»
 * в «Мои данные»). Фото клиента — как в «Настройках профиля»: приватное хранилище customer-avatars.
 *
 * 10.109 (просьба владельца 17.10.2026): уже выбранную аватарку можно редактировать — в просмотре «Редактировать»
 *    (тот же выбор области на том же фото + «Повернуть» на 90°) и «Выбрать другое фото». Полное исходное фото хранится
 *    только на этом телефоне (папка приложения, по файлу на аккаунт и вид аватарки), на сервер, как и раньше, уходит
 *    квадрат под кругом. Исходник берётся, только если он — от той аватарки, что сейчас показана (отпечаток 8×8);
 *    иначе (другой телефон, старое фото, сменили на сайте) редактируется сохранённое фото. Выбор — из галереи
 *    (IvaPhotoPatch.startGallery), а не из проводника.
 */
public class IvaAvatarPatch {
    static final int REQ = 6405;
    static final String PICKER_TAG = "iva_avatar_crop_picker";
    static final int OUT_PX = 512, SRC_MAX = 1280, JPEG_Q = 90, VIEW_PX = 1024;
    static final int HEAD_DP = 72, PAGE_DP = 120;                 // было 54 (строка «Кабинета») и 96 (страницы)
    static final float HEAD_LETTERS_SP = 30f;                     // было 24
    static final String CROP_TITLE = "Выберите область";
    static final String CROP_HINT = "Двигайте круг пальцем. Размер круга — двумя пальцами или за точку на краю";
    static final String EDIT = "Изменить фото", CLOSE = "Закрыть", DONE = "Готово", CANCEL = "Отмена";
    static final String EDIT_THIS = "Редактировать", PICK_OTHER = "Выбрать другое фото", ROTATE = "Повернуть";
    static final String SRC_PREFS = "iva_avatar_src", SRC_DIR = "iva_avatar";
    static final int KEEP_PX = 1600;                              // исходник для выбора области и «Редактировать» — до 1600 px
    static final int SAME_BITS = 10;                              // отпечатки 8×8 отличаются не больше чем на 10 из 64
    static final String TAG_HEAD = "iva_avatar_head_10_98", TAG_DATA = "iva_avatar_data_10_98";

    /** Результат выбора: готовый JPEG (квадрат под кругом) и картинка для показа. */
    interface Done { void done(byte[] jpeg, Bitmap preview); }
    /** Картинка покрупнее для просмотра (в фоне). */
    interface Loader { Bitmap load() throws Exception; }

    static Done pending;

    // ───────────── чистые функции (IvaPatchTest) ─────────────

    /** Картинка imgW×imgH вписывается в вид vw×vh с отступом pad: {left, top, scale}. */
    static float[] fit(int vw, int vh, int imgW, int imgH, float pad) {
        if (imgW <= 0 || imgH <= 0) return new float[]{0f, 0f, 1f};
        float aw = Math.max(1f, vw - 2f * pad), ah = Math.max(1f, vh - 2f * pad);
        float s = Math.min(aw / imgW, ah / imgH);
        return new float[]{(vw - imgW * s) / 2f, (vh - imgH * s) / 2f, s};
    }

    /** Круг на картинке (картинка на экране — left, top, w, h): радиус от minR до половины меньшей стороны,
     *  центр — так, чтобы круг не выходил за картинку. {cx, cy, r}. */
    static float[] clamp(float cx, float cy, float r, float left, float top, float w, float h, float minR) {
        float maxR = Math.max(1f, Math.min(w, h) / 2f);
        float rr = Math.max(Math.min(minR, maxR), Math.min(maxR, r));
        float x = Math.max(left + rr, Math.min(left + w - rr, cx));
        float y = Math.max(top + rr, Math.min(top + h - rr, cy));
        return new float[]{x, y, rr};
    }

    /** Квадрат исходной картинки под кругом: {x, y, size} в пикселях картинки, всегда внутри imgW×imgH. */
    static int[] srcRect(float cx, float cy, float r, float left, float top, float scale, int imgW, int imgH) {
        int side = Math.max(1, Math.min(imgW, imgH));
        if (scale <= 0f || imgW <= 0 || imgH <= 0) return new int[]{0, 0, side};
        int size = Math.max(1, Math.min(Math.round(2f * r / scale), side));
        int x = Math.round((cx - r - left) / scale), y = Math.round((cy - r - top) / scale);
        x = Math.max(0, Math.min(Math.max(0, imgW - size), x));
        y = Math.max(0, Math.min(Math.max(0, imgH - size), y));
        return new int[]{x, y, size};
    }

    /** Центральный квадрат картинки w×h: {x, y, size}. */
    static int[] centerSquare(int w, int h) {
        int s = Math.max(1, Math.min(w, h));
        return new int[]{Math.max(0, (w - s) / 2), Math.max(0, (h - s) / 2), s};
    }

    /** Что взял палец: 2 — край круга или точку на нём (размер), 1 — круг или мимо него (сдвиг). */
    static int grab(float x, float y, float cx, float cy, float r, float edge) {
        double d = Math.hypot(x - cx, y - cy);
        double hx = cx + r * 0.7071, hy = cy + r * 0.7071;   // точка на краю — справа внизу
        if (Math.abs(d - r) <= edge || Math.hypot(x - hx, y - hy) <= edge * 1.4) return 2;
        return 1;
    }

    /** Адрес фото — только http(s). */
    static boolean isUrl(String s) {
        String u = s == null ? "" : s.trim();
        return u.startsWith("https://") || u.startsWith("http://");
    }

    // 10.109 — редактирование уже выбранной аватарки

    /** Квадрат {x, y, size} на картинке w×h → тот же квадрат на картинке, повёрнутой на 90° по часовой (h×w). */
    static int[] rotRect90(int[] r, int w, int h) {
        if (r == null || r.length < 3) return null;
        return new int[]{Math.max(0, h - (r[1] + r[2])), Math.max(0, r[0]), r[2]};
    }

    /** Квадрат картинки {x, y, size} → круг на экране {cx, cy, r} (картинка на экране — left, top, scale). */
    static float[] circleOf(int[] r, float left, float top, float scale) {
        float half = r[2] / 2f;
        return new float[]{left + (r[0] + half) * scale, top + (r[1] + half) * scale, half * scale};
    }

    /** Поворот (0/90/180/270) после ещё одного нажатия «Повернуть». */
    static int nextRot(int rot) { return ((rot / 90 + 1) % 4) * 90; }

    /** Ключ исходника: вид аватарки (me — своя, shop — проката) и почта без регистра; без почты — "". */
    static String slot(String kind, String email) {
        String e = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        return e.isEmpty() || "null".equals(e) ? "" : kind + ":" + e;
    }

    /** Что запомнить об исходнике: поворот, квадрат под кругом, отпечаток готовой аватарки. */
    static String packSaved(int rot, int[] rect, long hash) {
        return rot + "|" + rect[0] + "," + rect[1] + "," + rect[2] + "|" + Long.toHexString(hash);
    }

    /** {rot, x, y, size} и отпечаток — или null, если запись испорчена. */
    static long[] unpackSaved(String s) {
        try {
            String[] p = s.split("\\|");
            String[] r = p[1].split(",");
            int rot = Integer.parseInt(p[0]);
            long x = Long.parseLong(r[0]), y = Long.parseLong(r[1]), z = Long.parseLong(r[2]);
            if (rot % 90 != 0 || rot < 0 || rot > 270 || x < 0 || y < 0 || z <= 0) return null;
            return new long[]{rot, x, y, z, new java.math.BigInteger(p[2], 16).longValue()};
        } catch (Throwable e) {
            return null;
        }
    }

    /** Отпечаток 8×8 (64 точки ARGB): бит — точка светлее средней. Одинаков у той же картинки в любом размере и сжатии. */
    static long fingerprint(int[] px) {
        if (px == null || px.length < 64) return 0L;
        int[] g = new int[64];
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            int c = px[i];
            g[i] = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
            sum += g[i];
        }
        long mean = sum / 64, bits = 0L;
        for (int i = 0; i < 64; i++) if (g[i] > mean) bits |= 1L << i;
        return bits;
    }

    /** Та же картинка: отпечатки отличаются не больше чем на SAME_BITS. */
    static boolean samePhoto(long a, long b) { return Long.bitCount(a ^ b) <= SAME_BITS; }

    // ───────────── выбор из галереи → выбор области ─────────────

    static int dp(Activity a, float v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
    static int color(Activity a, String n, int f) { return IvaClientPatch.navColor(a, n, f); }

    static void toast(Activity a, String s) {
        try { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
    }

    /** Галерея → выбор области кругом → cb (главный поток). false — выбор фото не открылся. */
    static boolean pick(final Activity a, Done cb) {
        pending = cb;
        try {
            android.app.FragmentManager fm = a.getFragmentManager();
            android.app.Fragment f = fm.findFragmentByTag(PICKER_TAG);
            if (!(f instanceof Picker)) {
                f = new Picker();
                fm.beginTransaction().add(f, PICKER_TAG).commitAllowingStateLoss();
                fm.executePendingTransactions();
            }
            final android.app.Fragment ff = f;
            boolean ok = IvaPhotoPatch.startGallery(1, new IvaPhotoPatch.Starter() {   // 10.109: галерея, не проводник
                @Override public void start(Intent i) { ff.startActivityForResult(i, REQ); }
            });
            if (!ok) pending = null;
            return ok;
        } catch (Throwable t) {
            pending = null;
            return false;
        }
    }

    /** Невидимый помощник: результат системного выбора фото приходит сюда (как AvatarPicker 10.39). */
    public static final class Picker extends android.app.Fragment {
        public Picker() {}

        @Override public void onActivityResult(int request, int result, Intent data) {
            Activity a = getActivity();
            Uri uri = request == REQ && result == Activity.RESULT_OK && data != null ? data.getData() : null;
            try { if (a != null && request == REQ) picked(a, uri); } catch (Throwable ignore) {}
            try { getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss(); } catch (Throwable ignore) {}
        }
    }

    static void picked(final Activity a, final Uri uri) {
        final Done cb = pending;
        pending = null;
        if (uri == null || cb == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                String err = "";
                try { b = fitMax(decode(IvaClientPatch.readUri(a, uri), SRC_MAX), KEEP_PX); }
                catch (Throwable e) { err = IvaClientPatch.brief(e.getMessage()); }
                final Bitmap fb = b;
                final String ferr = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (fb == null) { toast(a, "Не удалось открыть фото" + (ferr.isEmpty() ? "" : ": " + ferr)); return; }
                        try { crop(a, fb, 0, null, false, cb); } catch (Throwable t) { toast(a, "Не удалось открыть выбор области"); }
                    }
                });
            }
        }).start();
    }

    /** Фото из галереи: уменьшено при чтении (большая сторона ≈ max…2·max) и повёрнуто по EXIF. */
    static Bitmap decode(byte[] raw, int max) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("файл не похож на изображение");
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = IvaClientPatch.avatarSample(o.outWidth, o.outHeight, max);
        Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
        if (src == null) throw new Exception("изображение не читается");
        int deg = IvaClientPatch.exifDegrees(raw);
        if (deg == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (r != src) src.recycle();
        return r;
    }

    /** Квадрат под кругом → OUT_PX×OUT_PX JPEG (прозрачное — белым, как prepareAvatar). */
    static byte[] cut(Bitmap src, int[] rect) {
        Bitmap out = Bitmap.createBitmap(OUT_PX, OUT_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, new Rect(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[2]), new Rect(0, 0, OUT_PX, OUT_PX),
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, bytes);
        out.recycle();
        return bytes.toByteArray();
    }

    /** Не больше max по длинной стороне (поворот и хранение исходника — без лишней памяти). */
    static Bitmap fitMax(Bitmap b, int max) {
        if (b == null || Math.max(b.getWidth(), b.getHeight()) <= max) return b;
        float f = (float) max / Math.max(b.getWidth(), b.getHeight());
        Bitmap r = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * f)), Math.max(1, Math.round(b.getHeight() * f)), true);
        if (r != b) b.recycle();
        return r;
    }

    /** Повёрнутая копия (0 — та же картинка). */
    static Bitmap rotated(Bitmap src, int deg) {
        if (deg % 360 == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /** Отпечаток картинки: середина (квадрат внутри круга — без прозрачных углов), сжатая до 8×8. */
    static long hashOf(Bitmap b) {
        if (b == null || b.isRecycled() || b.getWidth() < 4 || b.getHeight() < 4) return 0L;
        int side = Math.min(b.getWidth(), b.getHeight()), in = Math.max(2, Math.round(side * 0.68f));
        Bitmap mid = Bitmap.createBitmap(b, (b.getWidth() - in) / 2, (b.getHeight() - in) / 2, in, in);
        Bitmap s8 = Bitmap.createScaledBitmap(mid, 8, 8, true);
        int[] px = new int[64];
        s8.getPixels(px, 0, 8, 0, 0, 8, 8);
        if (s8 != mid && s8 != b) s8.recycle();
        if (mid != b) mid.recycle();
        return fingerprint(px);
    }

    /** Последний выбор области — чтобы после сохранения запомнить исходник (saved). */
    static Bitmap lastSrc;
    static int lastRot;
    static int[] lastRect;
    static long lastHash;
    static boolean lastFromFile;

    /** Окно выбора области: фото целиком, круг поверх (вне круга — затемнено), «Отмена» / «Повернуть» / «Готово».
     *  rot и rect — с чего начать (редактирование уже выбранной аватарки), иначе круг по центру. */
    static void crop(final Activity a, final Bitmap src, int rot, int[] rect, final boolean fromFile, final Done cb) {
        final int cText = color(a, "text", 0xfff2f4f8), cGray = color(a, "gray", 0xff9aa1b0), cOrange = color(a, "orange", 0xffff8f1f);
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff000000);
        TextView t = IvaChiefPatch.text(a, CROP_TITLE, 18f, cText, true);
        t.setPadding(dp(a, 16), dp(a, 18), dp(a, 16), dp(a, 2));
        root.addView(t);
        TextView h = IvaChiefPatch.text(a, CROP_HINT, 13f, cGray, false);
        h.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 8));
        root.addView(h);
        final CropView cv = new CropView(a, src, cOrange, rot, rect);
        root.addView(cv, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 18));
        TextView cancel = IvaChiefPatch.text(a, CANCEL, 15f, cGray, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(a, 18), dp(a, 13), dp(a, 18), dp(a, 13));
        final TextView done = new TextView(a);
        done.setText(DONE);
        done.setTextSize(15f);
        IvaPatch.accentButton(a, done);                       // 10.96: «рамкой», как «Добавить»
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(a, 12), dp(a, 13), dp(a, 12), dp(a, 13));
        btns.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        TextView turn = IvaChiefPatch.text(a, ROTATE, 15f, cText, true);   // 10.109: на 90° по часовой
        turn.setGravity(Gravity.CENTER);
        turn.setPadding(dp(a, 14), dp(a, 13), dp(a, 14), dp(a, 13));
        turn.setContentDescription("Повернуть фото на 90 градусов");
        btns.addView(turn, new LinearLayout.LayoutParams(-2, -2));
        turn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!done.isEnabled()) return;                   // уже готовим фото
                try { cv.rotate(); } catch (Throwable t) { toast(a, "Не удалось повернуть фото"); }
            }
        });
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f);
        dl.leftMargin = dp(a, 8);
        btns.addView(done, dl);
        root.addView(btns);
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!done.isEnabled()) return;
                done.setEnabled(false);
                done.setText("Готовим…");
                final int[] rect = cv.rect();
                final Bitmap shown = cv.bmp;
                final int rotNow = cv.rot;
                new Thread(new Runnable() {
                    @Override public void run() {
                        byte[] jpeg = null;
                        Bitmap preview = null;
                        try {
                            jpeg = cut(shown, rect);
                            preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                            lastSrc = src; lastRot = rotNow; lastRect = rect; lastFromFile = fromFile;
                            lastHash = hashOf(preview);
                        } catch (Throwable ignore) {}
                        final byte[] fj = jpeg;
                        final Bitmap fp = preview;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                try { d.dismiss(); } catch (Throwable ignore) {}
                                if (fj == null || fp == null) { toast(a, "Не удалось подготовить фото"); return; }
                                try { cb.done(fj, fp); } catch (Throwable ignore) {}
                            }
                        });
                    }
                }).start();
            }
        });
        d.show();
    }

    /** Фото и круг выбора: палец внутри — сдвиг, на краю (или на точке) — размер, два пальца — размер и сдвиг. */
    static final class CropView extends View {
        final Bitmap src;
        Bitmap bmp;               // src, повёрнутая на rot
        int rot;
        int[] initRect;           // с чего начать (квадрат на bmp), null — круг по центру
        final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path hole = new Path();
        final Matrix m = new Matrix();
        final float density;
        float left, top, scale, cx, cy, r;
        int mode;                 // 0 — нет, 1 — сдвиг, 2 — размер (край), 3 — два пальца
        float lx, ly, d0, r0, mx0, my0;
        boolean placed;

        CropView(Activity a, Bitmap b, int accent, int rot0, int[] rect0) {
            super(a);
            src = b;
            rot = rot0 % 360 == 0 ? 0 : rot0;
            bmp = rotated(b, rot);
            initRect = rect0;
            density = a.getResources().getDisplayMetrics().density;
            dim.setColor(0xb0000000);
            dim.setStyle(Paint.Style.FILL);
            ring.setColor(0xffffffff);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2f * density);
            dot.setColor(accent);
            dotRing.setColor(0xffffffff);
            dotRing.setStyle(Paint.Style.STROKE);
            dotRing.setStrokeWidth(2f * density);
            hole.setFillType(Path.FillType.EVEN_ODD);
        }

        float minR() { return 36f * density; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            place(w, h);
        }

        void place(int w, int h) {
            if (w <= 0 || h <= 0) return;
            float[] f = fit(w, h, bmp.getWidth(), bmp.getHeight(), 16f * density);
            float oldScale = scale, oldLeft = left, oldTop = top;
            left = f[0]; top = f[1]; scale = f[2];
            if (!placed && initRect != null && initRect.length >= 3 && initRect[2] > 0) {   // 10.109: где круг был в прошлый раз
                float[] c = circleOf(initRect, left, top, scale);
                cx = c[0]; cy = c[1]; r = c[2];
                placed = true;
            } else if (!placed) {
                float iw = bmp.getWidth() * scale, ih = bmp.getHeight() * scale;
                cx = left + iw / 2f; cy = top + ih / 2f; r = Math.min(iw, ih) * 0.42f;
                placed = true;
            } else if (oldScale > 0f) {          // поворот экрана: круг остаётся на том же месте картинки
                cx = left + (cx - oldLeft) / oldScale * scale;
                cy = top + (cy - oldTop) / oldScale * scale;
                r = r / oldScale * scale;
            }
            keep();
        }

        void keep() {
            float[] c = clamp(cx, cy, r, left, top, bmp.getWidth() * scale, bmp.getHeight() * scale, minR());
            cx = c[0]; cy = c[1]; r = c[2];
        }

        int[] rect() { return srcRect(cx, cy, r, left, top, scale, bmp.getWidth(), bmp.getHeight()); }

        /** «Повернуть»: фото на 90° по часовой, круг остаётся на той же части фото. */
        void rotate() {
            int[] now = placed ? rect() : null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            Bitmap next = rotated(src, nextRot(rot));
            if (bmp != src) bmp.recycle();
            bmp = next;
            rot = nextRot(rot);
            initRect = now == null ? null : rotRect90(now, w, h);
            placed = false;
            place(getWidth(), getHeight());
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (scale <= 0f) return;
            m.reset();
            m.postScale(scale, scale);
            m.postTranslate(left, top);
            c.drawBitmap(bmp, m, img);
            hole.reset();
            hole.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            hole.addCircle(cx, cy, r, Path.Direction.CW);
            c.drawPath(hole, dim);
            c.drawCircle(cx, cy, r, ring);
            float hx = cx + r * 0.7071f, hy = cy + r * 0.7071f, hr = 9f * density;
            c.drawCircle(hx, hy, hr, dot);
            c.drawCircle(hx, hy, hr, dotRing);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            try { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); } catch (Throwable ignore) {}
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lx = e.getX(); ly = e.getY();
                    mode = grab(lx, ly, cx, cy, r, 22f * density);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() >= 2) {
                        mode = 3;
                        d0 = (float) Math.max(1.0, Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        r0 = r;
                        mx0 = (e.getX(0) + e.getX(1)) / 2f; my0 = (e.getY(0) + e.getY(1)) / 2f;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mode == 3 && e.getPointerCount() >= 2) {
                        float dd = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                        float mx = (e.getX(0) + e.getX(1)) / 2f, my = (e.getY(0) + e.getY(1)) / 2f;
                        r = r0 * dd / d0;
                        cx += mx - mx0; cy += my - my0;
                        mx0 = mx; my0 = my;
                    } else if (mode == 2) {
                        r = (float) Math.hypot(e.getX() - cx, e.getY() - cy);
                    } else if (mode == 1) {
                        cx += e.getX() - lx; cy += e.getY() - ly;
                        lx = e.getX(); ly = e.getY();
                    }
                    keep();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    // остался один палец — дальше он двигает круг
                    int up = e.getActionIndex(), keepIdx = up == 0 ? 1 : 0;
                    lx = e.getX(keepIdx); ly = e.getY(keepIdx);
                    mode = 1;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mode = 0;
                    return true;
                default:
                    return true;
            }
        }
    }

    // ───────────── просмотр ─────────────

    /** Фото во весь экран (кругом). editable — с «Изменить фото». hi — картинка покрупнее (в фоне), можно null. */
    static void viewer(final Activity a, Bitmap first, final Loader hi, boolean editable, final Runnable onEdit) {
        viewer(a, first, hi, null, editable ? onEdit : null);
    }

    /** 10.109: просмотр своей аватарки — «Редактировать» (onEdit: область и поворот на том же фото)
     *  и «Выбрать другое фото» (onNew: галерея). null — кнопки нет. */
    static void viewer(final Activity a, Bitmap first, final Loader hi, final Runnable onEdit, final Runnable onNew) {
        final int cGray = color(a, "gray", 0xff9aa1b0);
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xf0000000);
        int sw = a.getResources().getDisplayMetrics().widthPixels, sh = a.getResources().getDisplayMetrics().heightPixels;
        final int size = Math.max(dp(a, 160), Math.min(Math.min(sw, sh) - dp(a, 48), dp(a, 360)));
        final ImageView iv = new ImageView(a);
        iv.setContentDescription("Фото");
        Bitmap r0 = first == null ? null : IvaClientPatch.circle(first, size, 0);
        if (r0 != null) iv.setImageBitmap(r0);
        root.addView(iv, new LinearLayout.LayoutParams(size, size));
        String[] labels = onEdit != null ? new String[]{EDIT_THIS, PICK_OTHER} : new String[]{EDIT};
        final Runnable[] acts = onEdit != null ? new Runnable[]{onEdit, onNew} : new Runnable[]{onNew};
        for (int k = 0; k < labels.length; k++) {
            if (acts[k] == null) continue;
            final Runnable act = acts[k];
            TextView edit = new TextView(a);
            edit.setText(labels[k]);
            edit.setTextSize(15f);
            IvaPatch.accentButton(a, edit);
            edit.setGravity(Gravity.CENTER);
            edit.setPadding(dp(a, 24), dp(a, 13), dp(a, 24), dp(a, 13));
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(Math.min(size, dp(a, 280)), -2);
            el.topMargin = dp(a, k == 0 ? 24 : 10);
            root.addView(edit, el);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); try { act.run(); } catch (Throwable ignore) {} }
            });
        }
        TextView close = IvaChiefPatch.text(a, CLOSE, 15f, cGray, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(a, 24), dp(a, 14), dp(a, 24), dp(a, 14));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = dp(a, 6);
        root.addView(close, cl);
        View.OnClickListener shut = new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } };
        close.setOnClickListener(shut);
        root.setOnClickListener(shut);                        // нажатие мимо — закрыть
        iv.setClickable(true);                                // а по самому фото — нет
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        d.setCancelable(true);
        try { d.getWindow().setBackgroundDrawable(new ColorDrawable(0xf0000000)); } catch (Throwable ignore) {}
        d.show();
        if (hi == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Bitmap b = hi.load();
                    final Bitmap rb = b == null ? null : IvaClientPatch.circle(b, size, 0);
                    if (rb != null) a.runOnUiThread(new Runnable() {
                        @Override public void run() { if (d.isShowing()) iv.setImageBitmap(rb); }
                    });
                } catch (Throwable ignore) {
                    // остаётся то, что уже показано
                }
            }
        }).start();
    }

    /** Публичная картинка по адресу (фото админа и проката). */
    static Bitmap urlBitmap(String url, int size) throws Exception {
        if (!isUrl(url)) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(url.trim()).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        InputStream in = c.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 8 * 1024 * 1024) throw new Exception("файл больше 8 МБ");
                out.write(buf, 0, n);
            }
            return IvaClientPatch.decodeAvatar(out.toByteArray(), size);
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
        }
    }

    // ───────────── 10.109: редактировать уже выбранную аватарку ─────────────

    static String meSlot(Activity a) { return slot("me", myEmail(a)); }

    static java.io.File srcFile(Activity a, String slot) {
        String name;
        try {
            byte[] h = java.security.MessageDigest.getInstance("MD5").digest(slot.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xff));
            name = b.toString();
        } catch (Throwable e) {
            name = Integer.toHexString(slot.hashCode());
        }
        return new java.io.File(new java.io.File(a.getFilesDir(), SRC_DIR), "a_" + name + ".jpg");
    }

    /** «Редактировать»: исходник с этого телефона (если он от показанной аватарки) — с прошлым кругом и поворотом;
     *  иначе само сохранённое фото (hi — покрупнее, first — то, что на экране). Дальше — тот же выбор области и cb. */
    static void edit(final Activity a, final String slot, final Bitmap first, final Loader hi, final Done cb) {
        final long shownHash = hashOf(first);
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap src = null;
                int rot = 0;
                int[] rect = null;
                boolean fromFile = false;
                try {
                    String saved = slot.isEmpty() ? null : a.getSharedPreferences(SRC_PREFS, 0).getString(slot, null);
                    long[] st = saved == null ? null : unpackSaved(saved);
                    java.io.File f = st == null ? null : srcFile(a, slot);
                    if (st != null && f.isFile() && shownHash != 0L && samePhoto(shownHash, st[4])) {
                        src = BitmapFactory.decodeFile(f.getAbsolutePath());
                        if (src != null) {
                            rot = (int) st[0];
                            rect = new int[]{(int) st[1], (int) st[2], (int) st[3]};
                            fromFile = true;
                        }
                    }
                } catch (Throwable ignore) { src = null; }
                if (src == null && hi != null) {
                    try { src = hi.load(); } catch (Throwable ignore) {}
                }
                if (src == null) src = first;
                if (src != null && !fromFile) rect = centerSquare(src.getWidth(), src.getHeight());   // круг — во всё фото
                final Bitmap fs = src;
                final int frot = rot;
                final int[] frect = rect;
                final boolean ff = fromFile;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (fs == null) { toast(a, "Не удалось открыть фото"); return; }
                        try { crop(a, fs, frot, frect, ff, cb); } catch (Throwable t) { toast(a, "Не удалось открыть выбор области"); }
                    }
                });
            }
        }).start();
    }

    /** Аватарка сохранена на сервере — запомнить её исходник на этом телефоне (только здесь, в папке приложения):
     *  одно фото на аккаунт и вид аватарки, прежнее заменяется. */
    static void saved(final Activity a, final String slot) {
        final Bitmap src = lastSrc;
        final int rot = lastRot;
        final int[] rect = lastRect;
        final long hash = lastHash;
        final boolean fromFile = lastFromFile;
        lastSrc = null; lastRect = null;
        if (slot == null || slot.isEmpty() || src == null || rect == null || hash == 0L) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.File f = srcFile(a, slot);
                    if (!fromFile || !f.isFile()) {
                        java.io.File dir = f.getParentFile();
                        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
                        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
                        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                        try { src.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, out); } finally { out.close(); }
                        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); return; } }
                    }
                    a.getSharedPreferences(SRC_PREFS, 0).edit().putString(slot, packSaved(rot, rect, hash)).commit();
                } catch (Throwable ignore) {
                    // не запомнили — в следующий раз редактируется сохранённое фото
                }
            }
        }).start();
    }

    // ───────────── круг без лишних краёв ─────────────

    /** Фото показано (а не буквы / серый значок): картинка, отступ 0 (штатный loadPhoto ставит 0, у значка 24dp). */
    static boolean hasPhoto(ImageView v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.getDrawable() instanceof BitmapDrawable && v.getPaddingLeft() == 0;
    }

    static Bitmap photoOf(ImageView v) {
        if (!hasPhoto(v)) return null;
        Bitmap b = ((BitmapDrawable) v.getDrawable()).getBitmap();
        return b == null || b.isRecycled() ? null : b;
    }

    /** Фон-круг «рамкой» (как 10.96), который рисуется, только пока фото нет. */
    static final class Hole extends Drawable {
        final WeakReference<ImageView> photo;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Hole(ImageView v, int fillColor, int ringColor, float ringPx) {
            photo = new WeakReference<ImageView>(v);
            fill.setColor(fillColor);
            ring.setColor(ringColor);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(ringPx);
        }

        @Override public void draw(Canvas c) {
            if (hasPhoto(photo.get())) return;            // под фото — ничего: никаких краёв вокруг круга
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill);
            float sw = ring.getStrokeWidth();
            if (sw > 0f) c.drawCircle(b.exactCenterX(), b.exactCenterY(), r - sw / 2f, ring);
        }

        @Override public void setAlpha(int al) { fill.setAlpha(al); ring.setAlpha(al); }
        @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); ring.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static Hole hole(Activity a, ImageView v) {
        return new Hole(v, color(a, "card", 0xff1a1d24), color(a, "orange", 0xffff8f1f),
            Math.max(1, Math.round(IvaPatch.OUTLINE_DP * a.getResources().getDisplayMetrics().density)));
    }

    /** Штатный кружок берёт левый верхний квадрат картинки — не квадратное фото заменяем его центром. */
    static void squareFix(final ImageView iv) {
        final boolean[] had = {hasPhoto(iv)};
        iv.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try {
                    boolean has = hasPhoto(iv);
                    if (has != had[0]) {              // фото появилось / пропало — фон-круг рамки перерисовать
                        had[0] = has;
                        iv.invalidate();
                        if (iv.getParent() instanceof View) ((View) iv.getParent()).invalidate();
                    }
                    Bitmap b = photoOf(iv);
                    if (b != null && b.getWidth() != b.getHeight()) {
                        int[] s = centerSquare(b.getWidth(), b.getHeight());
                        iv.setImageBitmap(Bitmap.createBitmap(b, s[0], s[1], s[2], s[2]));
                        return false;                         // этот кадр — заново, уже с центром
                    }
                } catch (Throwable ignore) {}
                return true;
            }
        });
    }

    // ───────────── строка «Кабинета» (у всех) ─────────────

    /** После штатного renderProfile (IvaClientPatch.onProfile). */
    static void onProfile(final Activity a) {
        Object img = IvaClientPatch.get(a, "avatarImg"), txt = IvaClientPatch.get(a, "avatarTxt");
        if (!(img instanceof ImageView) || !(((ImageView) img).getParent() instanceof ViewGroup)) return;
        final ImageView iv = (ImageView) img;
        ViewGroup frame = (ViewGroup) iv.getParent();
        if (!TAG_HEAD.equals(frame.getTag())) {
            ViewGroup.LayoutParams lp = frame.getLayoutParams();
            if (lp != null) { lp.width = dp(a, HEAD_DP); lp.height = dp(a, HEAD_DP); frame.setLayoutParams(lp); }
            frame.setBackground(hole(a, iv));               // было bg_avatar: кольцо выглядывало из-под фото
            if (txt instanceof TextView) ((TextView) txt).setTextSize(HEAD_LETTERS_SP);
            squareFix(iv);
            frame.setTag(TAG_HEAD);
        }
        Object token = IvaClientPatch.get(a, "accessToken");
        boolean signed = token instanceof String && !((String) token).trim().isEmpty();
        if (signed) {
            frame.setClickable(true);
            frame.setContentDescription("Фото профиля");
            frame.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { try { headTap(a); } catch (Throwable ignore) {} }
            });
        } else {
            frame.setOnClickListener(null);
            frame.setClickable(false);                       // гость: нажатие — как раньше, по всей строке
        }
    }

    static String myEmail(Activity a) {
        Object e = IvaClientPatch.get(a, "userEmail");
        return e == null ? "" : String.valueOf(e).trim();
    }

    static String adminAvatarUrl(Activity a) {
        Object p = IvaClientPatch.get(a, "admProfile");
        return p instanceof JSONObject ? ((JSONObject) p).optString("avatar", "").trim() : "";
    }

    /** Нажатие на фото в строке «Кабинета»: фото есть — просмотр, нет — сразу выбор. */
    static void headTap(final Activity a) {
        Object img = IvaClientPatch.get(a, "avatarImg");
        Bitmap b = img instanceof ImageView ? photoOf((ImageView) img) : null;
        final boolean client = IvaClientPatch.isClient(a);
        Runnable edit = new Runnable() { @Override public void run() { editProfilePhoto(a); } };
        if (b == null) { edit.run(); return; }
        final Loader hi = client
            ? new Loader() {
                @Override public Bitmap load() throws Exception {
                    String path = IvaClientPatch.savedAvatarPath();
                    return path.isEmpty() ? null : IvaClientPatch.decodeAvatar(IvaClientPatch.downloadClientAvatar(a, path), VIEW_PX);
                }
            }
            : new Loader() { @Override public Bitmap load() throws Exception { return urlBitmap(adminAvatarUrl(a), VIEW_PX); } };
        viewer(a, b, hi, editExisting(a, b, hi), edit);
    }

    /** 10.109: «Редактировать» своей аватарки (строка «Кабинета», «Мои данные») — тот же выбор области, сохранение как у нового. */
    static Runnable editExisting(final Activity a, final Bitmap b, final Loader hi) {
        return new Runnable() {
            @Override public void run() {
                if (IvaClientPatch.isClient(a) && !IvaClientPatch.clientProfileLoaded) { toast(a, "Профиль ещё загружается — попробуйте через пару секунд"); return; }
                edit(a, meSlot(a), b, hi, profileDone(a));
            }
        };
    }

    /** Готовый квадрат своей аватарки → сервер (админ — admins.avatar, клиент — его запись). */
    static Done profileDone(final Activity a) {
        final boolean client = IvaClientPatch.isClient(a);
        return new Done() {
            @Override public void done(byte[] jpeg, Bitmap preview) {
                if (client) saveClientPhoto(a, jpeg, preview);
                else saveAdminPhoto(a, jpeg, preview);
            }
        };
    }

    /** «Изменить фото» профиля: выбор области и сохранение сразу (админ — admins.avatar, клиент — его запись). */
    static void editProfilePhoto(final Activity a) {
        final boolean client = IvaClientPatch.isClient(a);
        if (client && !IvaClientPatch.clientProfileLoaded) { toast(a, "Профиль ещё загружается — попробуйте через пару секунд"); return; }
        boolean ok = pick(a, profileDone(a));
        if (!ok) toast(a, "Не удалось открыть выбор фото");
    }

    // ───────────── сохранение ─────────────

    /** Штатная загрузка файла фото (как «Сохранить» в «Мои данные»): ключ входа свежий, адрес файла — ответ. */
    static String upload(Activity a, byte[] jpeg) throws Exception {
        try {
            Method f = a.getClass().getDeclaredMethod("ensureFreshToken");
            f.setAccessible(true);
            f.invoke(a);
        } catch (Throwable ignore) {}
        Object token = IvaClientPatch.get(a, "accessToken");
        Method m = a.getClass().getDeclaredMethod("uploadPhoto", byte[].class, String.class);
        m.setAccessible(true);
        Object url = m.invoke(a, jpeg, token instanceof String ? token : null);
        String u = url == null ? "" : String.valueOf(url).trim();
        if (!isUrl(u)) throw new Exception("файл не загрузился");
        return u;
    }

    static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable e) { return s; }
    }

    static String errText(Throwable e) {
        String m = e == null ? "" : IvaClientPatch.brief(e.getMessage());
        return m.isEmpty() ? "нет связи с сервером" : m;
    }

    /** Фото админа: файл → admins.avatar (то же поле, что у штатного «Сохранить»), затем строка, меню и «Мои данные». */
    static void saveAdminPhoto(final Activity a, final byte[] jpeg, final Bitmap preview) {
        toast(a, "Сохраняем фото…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = "", url = "";
                try {
                    url = upload(a, jpeg);
                    JSONObject body = new JSONObject();
                    body.put("avatar", url);
                    String[] r = IvaClientPatch.apiCall(a, "/rest/v1/admins?email=eq." + encode(myEmail(a)), "PATCH", body.toString(), true);
                    if (r == null || !r[0].startsWith("2")) err = r == null ? "нет ответа сервера" : IvaClientPatch.serverMessage(r[0], r.length > 1 ? r[1] : "");
                } catch (Throwable e) { err = errText(e); }
                final String ferr = err, furl = url;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ferr.isEmpty()) { toast(a, "Фото не сохранено: " + ferr); return; }
                        try {
                            Object p = IvaClientPatch.get(a, "admProfile");
                            if (p instanceof JSONObject) ((JSONObject) p).put("avatar", furl);
                        } catch (Throwable ignore) {}
                        showHead(a, preview);
                        try {
                            Object av = IvaClientPatch.get(a, "adAvatar");
                            if (av instanceof ImageView) showOn((ImageView) av, preview);
                        } catch (Throwable ignore) {}
                        try { IvaClientPatch.applyAvatar(a, furl); } catch (Throwable ignore) {}   // нижнее меню
                        saved(a, meSlot(a));                         // 10.109: исходник — для «Редактировать»
                        toast(a, "Фото сохранено");
                    }
                });
            }
        }).start();
    }

    /** Фото клиента: файл в его хранилище; путь в записи, если его там ещё нет (остальные поля — как сохранены). */
    static void saveClientPhoto(final Activity a, final byte[] jpeg, final Bitmap preview) {
        toast(a, "Сохраняем фото…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = "", path = "";
                try {
                    path = IvaClientPatch.uploadClientAvatar(a, jpeg);
                    if (!path.equals(IvaClientPatch.savedAvatarPath())) err = IvaClientPatch.saveAvatarPath(a, path);
                } catch (Throwable e) { err = errText(e); }
                final String ferr = err, fpath = path;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (IvaClientPatch.NEED_NAME.equals(ferr)) {
                            toast(a, "Фото загружено. Укажите имя и нажмите «Сохранить» — тогда оно появится");
                            IvaClientPatch.openClientProfileScreen(a);
                            return;
                        }
                        if (!ferr.isEmpty()) { toast(a, "Фото не сохранено: " + ferr); return; }
                        IvaClientPatch.rememberClientAvatar(IvaClientPatch.avatarCacheKey(IvaClientPatch.clientProfileEmail, fpath), preview);
                        IvaClientPatch.showClientAvatar(a);
                        saved(a, meSlot(a));                         // 10.109: исходник — для «Редактировать»
                        toast(a, "Фото сохранено");
                    }
                });
            }
        }).start();
    }

    static void showOn(ImageView iv, Bitmap b) {
        if (iv == null || b == null) return;
        iv.setImageBitmap(b);
        iv.setPadding(0, 0, 0, 0);
        iv.clearColorFilter();
        iv.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = iv.getLayoutParams();
        if (iv.getParent() instanceof View) ((View) iv.getParent()).invalidate();
        if (lp != null) iv.requestLayout();
    }

    static void showHead(Activity a, Bitmap b) {
        Object img = IvaClientPatch.get(a, "avatarImg"), txt = IvaClientPatch.get(a, "avatarTxt");
        if (img instanceof ImageView) showOn((ImageView) img, b);
        if (txt instanceof View) ((View) txt).setVisibility(View.GONE);
    }

    // ───────────── «Мои данные» (админ): аватарка ─────────────

    /** IvaPatch.onTab — после штатного переключения раздела. */
    static void onTab(Activity a, String tab) {
        if ("adminData".equals(tab)) dataPage(a);
    }

    /** Штатный кружок «Мои данные»: 120dp, фон-круг вместо скруглённого квадрата, нажатие — просмотр / выбор области. */
    static void dataPage(final Activity a) {
        Object raw = IvaClientPatch.get(a, "adAvatar");
        if (!(raw instanceof ImageView)) return;
        final ImageView av = (ImageView) raw;
        if (!TAG_DATA.equals(av.getTag())) {
            ViewGroup.LayoutParams lp = av.getLayoutParams();
            if (lp != null) { lp.width = dp(a, PAGE_DP); lp.height = dp(a, PAGE_DP); av.setLayoutParams(lp); }
            av.setBackground(hole(a, av));                   // было bg_photo — скруглённый квадрат: углы торчали из-за круга
            squareFix(av);
            av.setTag(TAG_DATA);
        }
        View.OnClickListener tap = new View.OnClickListener() {
            @Override public void onClick(View v) { try { dataTap(a, av); } catch (Throwable ignore) {} }
        };
        av.setOnClickListener(tap);
        av.setContentDescription("Фото профиля");
        // подпись «Изменить фото» под кружком — то же действие
        if (av.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) av.getParent();
            View next = p.getChildAt(p.indexOfChild(av) + 1);
            if (next instanceof TextView && EDIT.contentEquals(((TextView) next).getText())) next.setOnClickListener(tap);
        }
    }

    static void dataTap(final Activity a, final ImageView av) {
        Bitmap b = photoOf(av);
        Runnable edit = new Runnable() { @Override public void run() { editProfilePhoto(a); } };
        if (b == null) { edit.run(); return; }
        Loader hi = new Loader() { @Override public Bitmap load() throws Exception { return urlBitmap(adminAvatarUrl(a), VIEW_PX); } };
        viewer(a, b, hi, editExisting(a, b, hi), edit);
    }

    // ───────────── общий кружок фото для своих страниц ─────────────

    /** Кружок size dp: буквы / «+» пока фото нет, фото — без краёв. [0] — FrameLayout, [1] — ImageView, [2] — TextView. */
    static View[] circleBox(Activity a, int sizeDp, String letters) {
        FrameLayout box = new FrameLayout(a);
        TextView t = new TextView(a);
        t.setText(letters);
        t.setTextSize(Math.max(18f, sizeDp / 3.4f));
        t.setTextColor(color(a, "orange", 0xffff8f1f));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        box.addView(t, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setVisibility(View.GONE);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        box.setBackground(hole(a, iv));
        box.setClickable(true);
        return new View[]{box, iv, t};
    }

    /** Показать фото в своём кружке (обрезано по кругу) или вернуть буквы. */
    static void putCircle(View[] box, Bitmap b, int px) {
        ImageView iv = (ImageView) box[1];
        if (b == null) {
            iv.setImageDrawable(null);
            iv.setVisibility(View.GONE);
            box[2].setVisibility(View.VISIBLE);
        } else {
            Bitmap r = IvaClientPatch.circle(b, px, 0);
            iv.setImageBitmap(r != null ? r : b);
            iv.setVisibility(View.VISIBLE);
            box[2].setVisibility(View.INVISIBLE);
        }
        box[0].invalidate();
    }
}

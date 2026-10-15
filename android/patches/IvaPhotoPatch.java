package ru.prokatnvsk.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 10.89 · Фото в форме «Добавить инструмент» (просьба владельца 15.10.2026).
 *  * Пять одинаковых окошек в ряд: фото — по порядку, «+» — в первом свободном (добавили — «+» переполз дальше),
 *    остальные — пустые. Над ними большой просмотр — только когда нажали на маленькое фото (ещё раз / на просмотр — скрыть).
 *  * «+» → «Сделать фото» / «Из галереи». Галерея — системный выбор фото (Android 11+ с обновлением — Photo Picker),
 *    иначе приложение-галерея (ACTION_PICK), а не проводник; можно выбрать сразу несколько (сколько осталось до 5).
 *    Камера — без новых разрешений и без FileProvider: Android 10+ — запись в MediaStore, раньше — файл в папке приложения.
 *    Снимок после чтения удаляется (в галерее телефона не остаётся).
 *  * Новые фото уменьшаются до 1600 px по длинной стороне, поворот по EXIF, JPEG 85 % (раньше уходили как есть, до 10 МБ).
 *  Данные — штатный список atPics (byte[]): штатные «Опубликовать», черновик и renderAtPhotos не меняются; штатная
 *  лента (HorizontalScrollView) скрыта и рисуется как прежде. Результат выбора принимает невидимый Picker
 *  (как AvatarPicker профиля) — onActivityResult MainActivity не трогаем.
 */
public final class IvaPhotoPatch {
    private IvaPhotoPatch() {}

    static final String TAG = "iva_photos", PICKER_TAG = "iva_photo_picker";
    static final int SLOTS = 5, MAX_SIDE = 1600, JPEG_Q = 85, THUMB_SIDE = 320, GAP_DP = 8;
    static final int REQ_CAM = 5411, REQ_GAL = 5412;
    static final long MAX_BYTES = 40L * 1024 * 1024;
    static final String PICK_TITLE = "Добавить фото", CAMERA = "Сделать фото", GALLERY = "Из галереи";
    static final String ERR_FULL = "Можно добавить до 5 фото", ERR_CAM = "Не нашлось приложения камеры",
        ERR_GAL = "Не удалось открыть галерею", ERR_READ = "Не удалось прочитать фото";
    static final int SLOT_PHOTO = 0, SLOT_PLUS = 1, SLOT_EMPTY = 2;

    // ════════════════════════════════ чистая логика (под тестом) ════════════════════════════════

    /** Что в окошках: n фото, затем «+» (если меньше 5), остальные пустые. */
    static int[] slots(int n) {
        int[] out = new int[SLOTS];
        int k = Math.max(0, Math.min(n, SLOTS));
        for (int i = 0; i < SLOTS; i++) out[i] = i < k ? SLOT_PHOTO : (i == k ? SLOT_PLUS : SLOT_EMPTY);
        return out;
    }

    /** Сколько ещё можно добавить. */
    static int left(int n) { return Math.max(0, SLOTS - Math.max(0, n)); }

    /** Шаг уменьшения при чтении: степень двойки, после которой длинная сторона ещё не меньше max. */
    static int sampleSize(int w, int h, int max) {
        int s = 1, side = Math.max(w, h);
        if (max <= 0) return 1;
        while (side / (s * 2) >= max) s *= 2;
        return s;
    }

    /** Поворот по EXIF Orientation: 6 → 90°, 3 → 180°, 8 → 270°, иначе 0. */
    static int rotationOf(int orientation) {
        switch (orientation) {
            case 6: return 90;
            case 3: return 180;
            case 8: return 270;
            default: return 0;
        }
    }

    /** Какое фото в просмотре после удаления фото removed: то же (номер сдвигается) или никакое (-1). */
    static int afterRemove(int sel, int removed) {
        if (sel < 0 || sel == removed) return -1;
        return sel > removed ? sel - 1 : sel;
    }

    /** Нажали маленькое фото i: открыть его в просмотре или (уже открыто) скрыть. */
    static int toggle(int sel, int i) { return sel == i ? -1 : i; }

    // ════════════════════════════════ окошки ════════════════════════════════

    static int selected = -1, busy = 0;
    static final WeakHashMap<View, Boolean> watched = new WeakHashMap<View, Boolean>();
    static final WeakHashMap<View, String> sigs = new WeakHashMap<View, String>();
    static final WeakHashMap<byte[], Bitmap> thumbs = new WeakHashMap<byte[], Bitmap>();   // ключ — сам массив (тождество)
    static Activity act = null;

    static final Runnable PAINT = new Runnable() {
        @Override public void run() {
            try {
                Activity a = act;
                LinearLayout box = a == null ? null : box(a);
                if (box != null) paint(a, box);
            } catch (Throwable ignore) {}
        }
    };

    static LinearLayout box(Activity a) {
        Object no = IvaClientPatch.get(a, "atName");
        if (!(no instanceof View) || !(((View) no).getParent() instanceof LinearLayout)) return null;
        View b = ((LinearLayout) ((View) no).getParent()).findViewWithTag(TAG);
        return b instanceof LinearLayout ? (LinearLayout) b : null;
    }

    /** Из IvaFormPatch.setup: наш ряд окошек — сразу под штатной лентой фото (её скрываем). */
    static void setup(final Activity a, LinearLayout form) {
        act = a;
        Object ph = IvaClientPatch.get(a, "atPhotos");
        if (!(ph instanceof LinearLayout)) return;
        final LinearLayout nat = (LinearLayout) ph;
        View hs = nat.getParent() instanceof View ? (View) nat.getParent() : nat;
        if (hs.getParent() != form) return;
        View b = form.findViewWithTag(TAG);
        if (b == null) {
            LinearLayout box = new LinearLayout(a);
            box.setTag(TAG);
            box.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = IvaPatch.dp(a, 10);
            form.addView(box, form.indexOfChild(hs) + 1, lp);
            b = box;
        }
        if (hs.getVisibility() != View.GONE) hs.setVisibility(View.GONE);
        if (!watched.containsKey(nat)) {                    // штатный renderAtPhotos (черновик, публикация) — перерисовать
            watched.put(nat, Boolean.TRUE);
            nat.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View p, View c) { later(nat); }
                @Override public void onChildViewRemoved(View p, View c) { later(nat); }
            });
        }
        if (b instanceof LinearLayout) paint(a, (LinearLayout) b);
    }

    static void later(View any) {
        any.removeCallbacks(PAINT);
        any.post(PAINT);
    }

    @SuppressWarnings("unchecked")
    static List<byte[]> pics(Activity a) {
        List<byte[]> out = new ArrayList<byte[]>();
        Object p = IvaClientPatch.get(a, "atPics");
        if (p instanceof ArrayList) for (Object o : (ArrayList) p) if (o instanceof byte[]) out.add((byte[]) o);
        return out;
    }

    static void paint(final Activity a, final LinearLayout box) {
        final List<byte[]> ps = pics(a);
        int n = Math.min(ps.size(), SLOTS);
        if (selected >= n) selected = -1;
        View form = (View) box.getParent();
        int avail = form == null ? 0 : form.getWidth() - form.getPaddingLeft() - form.getPaddingRight();
        if (avail <= 0) avail = a.getResources().getDisplayMetrics().widthPixels - IvaPatch.dp(a, 40);
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < n; i++) sig.append(System.identityHashCode(ps.get(i))).append(',');
        sig.append('|').append(selected).append('|').append(busy).append('|').append(avail);
        if (sig.toString().equals(sigs.get(box))) return;
        sigs.put(box, sig.toString());
        box.removeAllViews();
        int orange = IvaPatch.color(a, "orange", 0xffff8f1f), white = IvaPatch.color(a, "white", 0xffffffff);
        if (selected >= 0) {                                                       // большой просмотр — над окошками
            FrameLayout pv = new FrameLayout(a);
            android.graphics.drawable.Drawable bg = IvaPatch.drawable(a, "bg_photo");
            if (bg != null) pv.setBackground(bg);
            pv.setClipToOutline(true);
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap big = decode(ps.get(selected), Math.max(avail, IvaPatch.dp(a, 320)));
            if (big != null) iv.setImageBitmap(big);
            pv.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            pv.setContentDescription("Фото " + (selected + 1) + ". Нажмите, чтобы скрыть");
            pv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selected = -1; repaint(a); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.min(avail * 3 / 4, IvaPatch.dp(a, 320)));
            lp.bottomMargin = IvaPatch.dp(a, 8);
            box.addView(pv, lp);
        }
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        int[] kinds = slots(n);
        int gap = IvaPatch.dp(a, GAP_DP);
        for (int i = 0; i < SLOTS; i++) {
            FrameLayout t = square(a);
            final int idx = i;
            if (kinds[i] == SLOT_PHOTO) {
                android.graphics.drawable.Drawable bg = IvaPatch.drawable(a, "bg_photo");
                if (bg != null) t.setBackground(bg);
                t.setClipToOutline(true);
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Bitmap th = thumb(ps.get(i));
                if (th != null) iv.setImageBitmap(th);
                t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                if (i == selected) {
                    GradientDrawable fg = new GradientDrawable();
                    fg.setColor(0);
                    fg.setStroke(IvaPatch.dp(a, 2), orange);
                    fg.setCornerRadius(IvaPatch.dp(a, 12));
                    t.setForeground(fg);
                }
                TextView x = new TextView(a);                                      // «×» — как в штатной ленте
                x.setText("\u00d7");
                x.setTextColor(white);
                x.setTextSize(13f);
                x.setTypeface(null, android.graphics.Typeface.BOLD);
                x.setGravity(Gravity.CENTER);
                android.graphics.drawable.Drawable xb = IvaPatch.drawable(a, "bg_nav_circle_on");
                if (xb != null) x.setBackground(xb);
                x.setContentDescription("Удалить фото " + (i + 1));
                FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(IvaPatch.dp(a, 22), IvaPatch.dp(a, 22), Gravity.TOP | Gravity.END);
                xl.setMargins(0, IvaPatch.dp(a, 3), IvaPatch.dp(a, 3), 0);
                x.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { try { remove(a, idx); } catch (Throwable ignore) {} }
                });
                t.addView(x, xl);
                t.setContentDescription("Фото " + (i + 1) + (i == 0 ? ", обложка" : "") + ". Нажмите, чтобы посмотреть");
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { selected = toggle(selected, idx); repaint(a); }
                });
            } else {
                android.graphics.drawable.Drawable bg = IvaPatch.drawable(a, "bg_field");
                if (bg != null) t.setBackground(bg);
                if (kinds[i] == SLOT_PLUS) {
                    if (busy > 0) {
                        TextView w = new TextView(a);
                        w.setText("\u2026");
                        w.setTextColor(orange);
                        w.setTextSize(20f);
                        w.setGravity(Gravity.CENTER);
                        t.addView(w, new FrameLayout.LayoutParams(-1, -1));
                        t.setContentDescription("Фото обрабатывается");
                    } else {
                        ImageView plus = new ImageView(a);
                        int pid = a.getResources().getIdentifier("ic_plus", "drawable", a.getPackageName());
                        if (pid != 0) plus.setImageResource(pid);
                        plus.setColorFilter(orange);
                        t.addView(plus, new FrameLayout.LayoutParams(IvaPatch.dp(a, 24), IvaPatch.dp(a, 24), Gravity.CENTER));
                        t.setContentDescription("Добавить фото");
                    }
                } else {
                    t.setAlpha(0.45f);
                    t.setContentDescription("Свободное место для фото");
                }
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { try { choose(a); } catch (Throwable ignore) {} }
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i < SLOTS - 1) lp.rightMargin = gap;
            row.addView(t, lp);
        }
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    static void repaint(Activity a) {
        LinearLayout b = box(a);
        if (b != null) paint(a, b);
    }

    /** Квадратное окошко: высота = ширине (ширину делит ряд поровну). */
    static FrameLayout square(Activity a) {
        return new FrameLayout(a) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
        };
    }

    @SuppressWarnings("unchecked")
    static void remove(Activity a, int i) {
        Object p = IvaClientPatch.get(a, "atPics");
        if (!(p instanceof ArrayList) || i < 0 || i >= ((ArrayList) p).size()) return;
        ((ArrayList) p).remove(i);
        selected = afterRemove(selected, i);
        nativeRender(a);
        try { IvaFormPatch.scheduleSave(a); } catch (Throwable ignore) {}
        repaint(a);
    }

    static void nativeRender(Activity a) {
        try { Method m = a.getClass().getDeclaredMethod("renderAtPhotos"); m.setAccessible(true); m.invoke(a); } catch (Throwable ignore) {}
    }

    static Bitmap thumb(byte[] b) {
        Bitmap t = thumbs.get(b);
        if (t == null) { t = decode(b, THUMB_SIDE); if (t != null) thumbs.put(b, t); }
        return t;
    }

    /** Картинка для показа: уменьшенная (не меньше max по длинной стороне), с поворотом по EXIF (Android 7+). */
    static Bitmap decode(byte[] b, int max) {
        if (b == null || b.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sampleSize(o.outWidth, o.outHeight, max);
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, d);
            int rot = 0;
            if (Build.VERSION.SDK_INT >= 24) try {
                rot = rotationOf(new android.media.ExifInterface(new java.io.ByteArrayInputStream(b))
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1));
            } catch (Throwable ignore) {}
            if (bm == null || rot == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(rot);
            return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        } catch (Throwable e) { return null; }
    }

    // ════════════════════════════════ «+»: камера / галерея ════════════════════════════════

    static void choose(final Activity a) {
        if (busy > 0) return;
        if (left(pics(a).size()) <= 0) { Toast.makeText(a, ERR_FULL, Toast.LENGTH_SHORT).show(); return; }
        IvaSearchPatch.hideKeyboard(a, a.getWindow().getDecorView());
        IvaClientPatch.styledChoice(a, PICK_TITLE, null, new String[]{CAMERA, GALLERY}, new IvaClientPatch.Choice() {
            @Override public void pick(int w) {
                if (w == 0) camera(a);
                else if (w == 1) gallery(a);
            }
        });
    }

    /** Невидимый помощник: результат камеры / галереи приходит сюда (как AvatarPicker), MainActivity не трогаем. */
    public static final class Picker extends android.app.Fragment {
        public Picker() {}

        @Override public void onActivityResult(int request, int result, Intent data) {
            try { onResult(getActivity(), request, result, data); } catch (Throwable ignore) {}
            try { getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss(); } catch (Throwable ignore) {}
        }
    }

    static android.app.Fragment picker(Activity a) {
        android.app.FragmentManager fm = a.getFragmentManager();
        android.app.Fragment f = fm.findFragmentByTag(PICKER_TAG);
        if (!(f instanceof Picker)) {
            f = new Picker();
            fm.beginTransaction().add(f, PICKER_TAG).commitAllowingStateLoss();
            fm.executePendingTransactions();
        }
        return f;
    }

    static android.content.SharedPreferences prefs(Activity a) { return a.getSharedPreferences("iva_form", 0); }

    static void camera(Activity a) {
        Uri out = null;
        boolean file = false;
        try {
            if (Build.VERSION.SDK_INT >= 29) {                        // Android 10+: MediaStore, разрешения не нужны
                ContentValues cv = new ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "prokat_" + System.currentTimeMillis() + ".jpg");
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Prokat");
                out = a.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            } else {                                                  // раньше: файл в папке приложения (камера пишет сама)
                File dir = a.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    out = Uri.fromFile(new File(dir, "iva_cam_" + System.currentTimeMillis() + ".jpg"));
                    file = true;
                }
            }
        } catch (Throwable ignore) { out = null; }
        if (out == null) { Toast.makeText(a, ERR_CAM, Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!file) i.setClipData(android.content.ClipData.newRawUri("", out));
        prefs(a).edit().putString("cam", out.toString()).apply();
        android.os.StrictMode.VmPolicy old = null;
        if (file && Build.VERSION.SDK_INT >= 24) {                    // file:// камере (Android 7–9) — только на время запуска
            old = android.os.StrictMode.getVmPolicy();
            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
        }
        try {
            picker(a).startActivityForResult(i, REQ_CAM);
        } catch (Throwable e) {
            Toast.makeText(a, ERR_CAM, Toast.LENGTH_SHORT).show();
            drop(a, out);
            prefs(a).edit().remove("cam").apply();
        } finally {
            if (old != null) android.os.StrictMode.setVmPolicy(old);
        }
    }

    static boolean photoPicker() {
        if (Build.VERSION.SDK_INT >= 33) return true;
        try { return Build.VERSION.SDK_INT >= 30 && android.os.ext.SdkExtensions.getExtensionVersion(30) >= 2; }
        catch (Throwable e) { return false; }
    }

    static void gallery(Activity a) {
        int n = left(pics(a).size());
        if (n <= 0) return;
        List<Intent> tries = new ArrayList<Intent>();
        if (photoPicker()) {                                          // системный выбор фото (как галерея)
            Intent p = new Intent("android.provider.action.PICK_IMAGES");
            p.setType("image/*");
            if (n > 1) p.putExtra("android.provider.extra.PICK_IMAGES_MAX", n);
            tries.add(p);
        }
        Intent g = new Intent(Intent.ACTION_PICK);                    // приложение-галерея
        g.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        if (n > 1) g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        tries.add(g);
        Intent c = new Intent(Intent.ACTION_GET_CONTENT);             // запасной путь — если галереи нет
        c.setType("image/*");
        c.addCategory(Intent.CATEGORY_OPENABLE);
        if (n > 1) c.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        tries.add(c);
        for (Intent i : tries) {
            try { picker(a).startActivityForResult(i, REQ_GAL); return; } catch (Throwable ignore) {}
        }
        Toast.makeText(a, ERR_GAL, Toast.LENGTH_SHORT).show();
    }

    static void onResult(final Activity a, int request, int result, Intent data) {
        if (a == null) return;
        if (request == REQ_CAM) {
            String s = prefs(a).getString("cam", "");
            prefs(a).edit().remove("cam").apply();
            if (s.isEmpty()) return;
            Uri u = Uri.parse(s);
            if (result != Activity.RESULT_OK) { drop(a, u); return; }
            List<Uri> l = new ArrayList<Uri>();
            l.add(u);
            process(a, l, true);
        } else if (request == REQ_GAL) {
            if (result != Activity.RESULT_OK || data == null) return;
            List<Uri> l = new ArrayList<Uri>();
            android.content.ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) { Uri u = cd.getItemAt(i).getUri(); if (u != null && !l.contains(u)) l.add(u); }
            if (l.isEmpty() && data.getData() != null) l.add(data.getData());
            int n = left(pics(a).size());
            while (l.size() > n) l.remove(l.size() - 1);
            if (!l.isEmpty()) process(a, l, false);
        }
    }

    /** Прочитать и подготовить фото в фоне, затем — в штатный список (не больше 5) и перерисовать. */
    static void process(final Activity a, final List<Uri> uris, final boolean dropAfter) {
        busy++;
        repaint(a);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<byte[]> got = new ArrayList<byte[]>();
                for (Uri u : uris) {
                    byte[] b = null;
                    try { b = prepare(a, u); } catch (Throwable ignore) {}
                    if (b != null) got.add(b);
                    if (dropAfter) drop(a, u);
                }
                a.runOnUiThread(new Runnable() {
                    @Override @SuppressWarnings("unchecked") public void run() {
                        busy = Math.max(0, busy - 1);
                        try {
                            Object p = IvaClientPatch.get(a, "atPics");
                            if (p instanceof ArrayList) for (byte[] b : got) if (((ArrayList) p).size() < SLOTS) ((ArrayList) p).add(b);
                            if (!got.isEmpty()) { nativeRender(a); IvaFormPatch.scheduleSave(a); }
                            if (got.size() < uris.size()) Toast.makeText(a, ERR_READ, Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignore) {}
                        repaint(a);
                    }
                });
            }
        }).start();
    }

    /** Фото → JPEG: не больше 1600 px по длинной стороне, повёрнуто по EXIF. null — не картинка / не прочиталось. */
    static byte[] prepare(Activity a, Uri u) throws Exception {
        File tmp = new File(a.getCacheDir(), "iva_pick.tmp");
        try {
            InputStream in = a.getContentResolver().openInputStream(u);
            if (in == null) return null;
            long total = 0;
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_BYTES) return null;
                    out.write(buf, 0, r);
                }
            } finally { try { in.close(); } catch (Throwable ignore) {} out.close(); }
            if (total <= 0) return null;
            String path = tmp.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int rot = 0;
            try { rot = rotationOf(new android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)); }
            catch (Throwable ignore) {}
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sampleSize(o.outWidth, o.outHeight, MAX_SIDE);
            Bitmap bm = BitmapFactory.decodeFile(path, d);
            if (bm == null) return null;
            float f = Math.min(1f, (float) MAX_SIDE / Math.max(bm.getWidth(), bm.getHeight()));
            Bitmap res = bm;
            if (f < 1f || rot != 0) {
                Matrix m = new Matrix();
                if (f < 1f) m.postScale(f, f);
                if (rot != 0) m.postRotate(rot);
                res = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            res.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, bo);
            if (res != bm) res.recycle();
            bm.recycle();
            return bo.size() > 0 ? bo.toByteArray() : null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            try { tmp.delete(); } catch (Throwable ignore) {}
        }
    }

    /** Снимок камеры больше не нужен (прочитан или съёмку отменили) — удалить запись / файл. */
    static void drop(Activity a, Uri u) {
        if (u == null) return;
        try {
            if ("file".equals(u.getScheme())) { String p = u.getPath(); if (p != null) new File(p).delete(); }
            else if ("content".equals(u.getScheme())) a.getContentResolver().delete(u, null, null);
        } catch (Throwable ignore) {}
    }
}

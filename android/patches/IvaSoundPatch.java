package ru.prokatnvsk.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 10.100 — свой звук у всех уведомлений приложения (решение владельца 16.10.2026: «индивидуальный звук оповещения»,
 * файл владельца android/sounds/ringtone-sms-notification.mp3; в APK — копия без тегов assets/iva_notify.mp3).
 *
 * Как система получает звук. Звук категории (канала) Android берёт по адресу и играет сам — из чужого процесса,
 * поэтому файл из папки приложения (file://) не годится (targetSdk 34 — FileUriExposed, а система его не прочитает).
 * Отдаёт звук маленький провайдер этого класса — content://ru.prokatnvsk.app.ivasound/notify.mp3
 * (в манифест его добавляет android/patches/axml_sound.py): только чтение и только этот один файл.
 *
 * Категории. Звук уже созданной категории Android менять не даёт, поэтому категории пересоздаются с новыми
 * id (+"_s1"), а старые удаляются. Если человек выключил категорию — новая создаётся тоже выключенной.
 * Android 7 и раньше — звук ставится на само уведомление. Беззвучный режим и «Не беспокоить» работают как раньше.
 *
 * Если в APK нет звука или провайдера (сборка без них) — всё как в 10.99: старые категории со звуком по умолчанию.
 * Новый звук в будущем: заменить файл и поднять VER — категории пересоздадутся ещё раз.
 */
public final class IvaSoundPatch {
    private IvaSoundPatch() {}

    static final String AUTH = "ru.prokatnvsk.app.ivasound";
    static final String ASSET = "iva_notify.mp3";
    static final String FILE = "notify.mp3";
    static final String MIME = "audio/mpeg";
    static final int VER = 1;
    static final String TITLE = "Ива";

    private static Boolean ready;

    static Uri uri() {
        return Uri.parse(uriText());
    }

    static String uriText() {
        return "content://" + AUTH + "/" + FILE;
    }

    /** id системной категории: со своим звуком — новый (iva_msg → iva_msg_s1), без него — прежний. */
    static String channelId(String ch, boolean sound) {
        return sound ? ch + "_s" + VER : ch;
    }

    /** Отдаём только наш файл: путь ровно /notify.mp3 и режим только чтения. */
    static boolean servable(String path, String mode) {
        return ("/" + FILE).equals(path) && (mode == null || "r".equals(mode));
    }

    /** Значение колонки для запроса к провайдеру (то, что спрашивают плеер и «Звук уведомления» в настройках). */
    static Object column(String col, long size) {
        if ("_id".equals(col)) return 1L;
        if ("_display_name".equals(col)) return FILE;
        if ("title".equals(col)) return TITLE;
        if ("_size".equals(col)) return size;
        if ("mime_type".equals(col)) return MIME;
        return null;
    }

    /** Звук есть в APK и провайдер прописан в манифесте — тогда (и только тогда) включаем свой звук. */
    static boolean ready(Context c) {
        Boolean r = ready;
        if (r != null) return r;
        boolean ok = false;
        try {
            Context app = c.getApplicationContext();
            InputStream in = app.getAssets().open(ASSET);
            in.close();
            ok = app.getPackageManager().resolveContentProvider(AUTH, 0) != null;
        } catch (Throwable e) {
            ok = false;
        }
        ready = ok;
        return ok;
    }

    static AudioAttributes attrs() {
        return new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    }

    /** Android 8+: звук категории. */
    static void apply(NotificationChannel ch) {
        if (Build.VERSION.SDK_INT < 26) return;
        ch.setSound(uri(), attrs());
    }

    /** Android 7 и раньше: звук на уведомлении (вибрация и индикатор — по умолчанию, как было). */
    @SuppressWarnings("deprecation")
    static void legacy(Context c, Notification.Builder b) {
        if (ready(c)) {
            b.setDefaults(Notification.DEFAULT_VIBRATE | Notification.DEFAULT_LIGHTS);
            b.setSound(uri(), attrs());
        } else {
            b.setDefaults(Notification.DEFAULT_ALL);
        }
    }

    /** Копия звука в папке приложения (её и отдаёт провайдер целиком; из APK звук берётся один раз). */
    static synchronized File cached(Context c) throws java.io.IOException {
        File f = new File(c.getFilesDir(), "iva_notify_" + VER + ".mp3");
        if (f.isFile() && f.length() > 0) return f;
        File tmp = new File(c.getFilesDir(), "iva_notify_" + VER + ".tmp");
        InputStream in = c.getAssets().open(ASSET);
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
            out.close();
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
        return f;
    }

    /** Провайдер звука: только чтение одного файла; записать, удалить или прочитать что-то ещё нельзя. */
    public static final class Files extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        @Override public String getType(Uri uri) {
            return uri != null && servable(uri.getPath(), "r") ? MIME : null;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (uri == null || !servable(uri.getPath(), mode)) throw new FileNotFoundException("нет такого файла");
            try {
                return ParcelFileDescriptor.open(cached(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                throw e;
            } catch (Throwable e) {
                throw new FileNotFoundException("звук недоступен");
            }
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            if (uri == null || !servable(uri.getPath(), "r")) return null;
            String[] cols = projection != null ? projection : new String[] {"_display_name", "_size", "title"};
            long size = 0;
            try { size = cached(getContext()).length(); } catch (Throwable ignore) {}
            MatrixCursor mc = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) row[i] = column(cols[i], size);
            mc.addRow(row);
            return mc;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException("только чтение");
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            throw new UnsupportedOperationException("только чтение");
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException("только чтение");
        }
    }
}

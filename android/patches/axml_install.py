#!/usr/bin/env python3
"""10.80: разрешение на установку обновления в двоичном AndroidManifest.xml.

Добавляет перед <application>:
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>

Зачем: с Android 8 системный установщик молча отменяет установку из приложения, у которого в манифесте нет этого
разрешения (InstallStart: «needs to declare permission REQUEST_INSTALL_PACKAGES»). Поэтому кнопка «Обновить до
версии N» скачивала файл, а окно установки так и не появлялось. Разрешение не опасное и без запроса: Android один
раз спросит «Разрешить установку из этого источника» — переключатель для «Ивы».

Запускается после axml_push.py. Атрибут android:name уже есть в карте ресурсов; новые строки — в конец пула (имена
тегов и значения карта не покрывает). Идемпотентен: разрешение уже есть — ничего не меняет.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from axml_permissions import read_pool, encode_pool, chunks  # noqa: E402
from axml_service import start_tag, end_tag, AXML, RESOURCE_MAP, START_TAG, END_TAG, ANDROID_NS, ATTR_IDS, NONE, TYPE_STRING  # noqa: E402

INSTALL = "android.permission.REQUEST_INSTALL_PACKAGES"


def die(msg):
    print("ОШИБКА AXML (установка): " + msg, file=sys.stderr)
    if os.environ.get("GITHUB_ACTIONS"):
        print("::error title=Manifest::" + msg)
    raise SystemExit(1)


def main():
    if len(sys.argv) != 2:
        die("нужно: axml_install.py <AndroidManifest.xml>")
    path = sys.argv[1]
    raw = bytearray(open(path, "rb").read())
    if len(raw) < 8 or struct.unpack_from("<H", raw, 0)[0] != AXML:
        die("файл не является binary AndroidManifest.xml")
    pool_size = struct.unpack_from("<I", raw, 8 + 4)[0]
    strings, styles, flags, _ = read_pool(raw[8:8 + pool_size])
    if styles:
        die("в пуле строк есть стили — безопасная вставка не поддержана")
    pos = 8 + pool_size
    typ, hsz, rm_size = struct.unpack_from("<HHI", raw, pos)
    if typ != RESOURCE_MAP:
        die("после пула строк нет resource map")
    ids = list(struct.unpack_from("<%dI" % ((rm_size - 8) // 4), raw, pos + 8))
    body_start = pos + rm_size
    if ATTR_IDS["name"] not in ids:
        die("в resource map нет атрибута android:name")
    name_attr = ids.index(ATTR_IDS["name"])
    if ANDROID_NS not in strings:
        die("нет пространства имён android")
    ns = strings.index(ANDROID_NS)

    # уже есть такое разрешение — ничего не делаем
    app_start = None
    for p, t, h, s in chunks(raw, body_start):
        if t != START_TAG:
            continue
        nm = strings[struct.unpack_from("<I", raw, p + 20)[0]]
        if nm == "application" and app_start is None:
            app_start = p - body_start
        if nm == "uses-permission":
            a_start, a_size, a_count = struct.unpack_from("<HHH", raw, p + 24)
            for i in range(a_count):
                ap = p + 16 + a_start + i * a_size
                if raw[ap + 15] == TYPE_STRING and strings[struct.unpack_from("<I", raw, ap + 16)[0]] == INSTALL:
                    print("разрешение на установку обновления уже есть в манифесте")
                    return
    if app_start is None:
        die("не найден тег application")

    def index(value):
        if value in strings[len(ids):]:
            return strings.index(value, len(ids))
        strings.append(value)
        return len(strings) - 1
    s_uses, s_install = index("uses-permission"), index(INSTALL)

    body = bytearray(raw[body_start:])
    perm = start_tag(6, NONE, s_uses, [(ns, name_attr, s_install, TYPE_STRING, s_install)]) + end_tag(6, NONE, s_uses)
    body = body[:app_start] + perm + body[app_start:]

    rm = struct.pack("<HHI", RESOURCE_MAP, 8, 8 + 4 * len(ids)) + struct.pack("<%dI" % len(ids), *ids)
    out = bytearray(raw[:8]) + encode_pool(strings, 0, flags) + rm + body
    struct.pack_into("<I", out, 4, len(out))
    open(path, "wb").write(out)
    print("манифест: разрешение " + INSTALL)


if __name__ == "__main__":
    main()

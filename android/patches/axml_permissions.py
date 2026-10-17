#!/usr/bin/env python3
"""Добавляет runtime-разрешения геолокации в binary AndroidManifest.xml.

Сборка приложения идёт из уже выпущенного APK и использует apktool с -r, поэтому
обычный текстовый AndroidManifest.xml здесь недоступен. AXML остаётся бинарным:
мы расширяем его string pool и вставляем два стандартных uses-permission перед
application. Скрипт идемпотентен и не затрагивает существующие атрибуты.
"""
import os
import struct
import sys

AXML = 0x0003
STRING_POOL = 0x0001
RESOURCE_MAP = 0x0180
START_TAG = 0x0102
END_TAG = 0x0103
ANDROID_NS = "http://schemas.android.com/apk/res/android"
PERMISSIONS = (
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
)


def die(msg):
    print("ОШИБКА AXML: " + msg, file=sys.stderr)
    if os.environ.get("GITHUB_ACTIONS"):
        print("::error title=Manifest::" + msg)
    raise SystemExit(1)


def read_pool(data):
    if len(data) < 28:
        die("слишком короткий string pool")
    typ, hsz, size = struct.unpack_from("<HHI", data, 0)
    if typ != STRING_POOL or hsz != 28:
        die("первый chunk не string pool")
    count, styles, flags, strings_start, styles_start = struct.unpack_from("<IIIII", data, 8)
    if flags & 0x100:
        die("UTF-8 string pool не поддержан этой безопасной вставкой")
    offsets = struct.unpack_from("<%dI" % count, data, hsz)
    strings = []
    for off in offsets:
        p = strings_start + off
        if p + 2 > size:
            die("битый offset string pool")
        n = struct.unpack_from("<H", data, p)[0]
        q = p + 2
        strings.append(data[q:q + 2 * n].decode("utf-16-le"))
    return strings, styles, flags, styles_start


def encode_pool(strings, styles=0, flags=0):
    payload = bytearray()
    offsets = []
    for value in strings:
        offsets.append(len(payload))
        raw = value.encode("utf-16-le")
        payload += struct.pack("<H", len(value)) + raw + b"\x00\x00"
        while len(payload) % 4:
            payload += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(payload)
    header = struct.pack("<HHI", STRING_POOL, header_size, size)
    header += struct.pack("<IIIII", len(strings), styles, flags, strings_start, 0)
    return header + struct.pack("<%dI" % len(offsets), *offsets) + payload


def tag_chunk(kind, line, namespace, name, attr_namespace=None, attr_name=None, attr_value=None):
    if kind == START_TAG:
        body = struct.pack("<II", line, 0xffffffff)
        body += struct.pack("<II", namespace, name)
        if attr_name is None:
            body += struct.pack("<HHHHHH", 20, 20, 0, 0, 0, 0)
        else:
            body += struct.pack("<HHHHHH", 20, 20, 1, 0, 0, 0)
            # Attribute: android:name="android.permission.*".
            body += struct.pack("<III", attr_namespace, attr_name, attr_value)
            body += struct.pack("<HBBI", 8, 0, 3, attr_value)
        return struct.pack("<HHI", START_TAG, 16, 8 + len(body)) + body
    body = struct.pack("<II", line, 0xffffffff) + struct.pack("<II", namespace, name)
    return struct.pack("<HHI", END_TAG, 16, 8 + len(body)) + body


def chunks(data, start):
    pos = start
    while pos < len(data):
        if pos + 8 > len(data): die("обрезанный AXML chunk")
        typ, hsz, size = struct.unpack_from("<HHI", data, pos)
        if size < hsz or pos + size > len(data): die("некорректный размер AXML chunk")
        yield pos, typ, hsz, size
        pos += size


def main():
    if len(sys.argv) != 2:
        die("нужно: axml_permissions.py <AndroidManifest.xml>")
    path = sys.argv[1]
    raw = bytearray(open(path, "rb").read())
    if len(raw) < 8 or struct.unpack_from("<H", raw, 0)[0] != AXML:
        die("файл не является binary AndroidManifest.xml")
    pool_start = 8
    strings, styles, flags, styles_start = read_pool(raw[pool_start:])
    # read_pool received a slice, so its offsets are relative to pool_start; its
    # chunk size is the end of the first chunk.
    pool_size = struct.unpack_from("<I", raw, pool_start + 4)[0]
    rest_start = pool_start + pool_size

    # Find existing names and append only the strings we need. Appending keeps
    # every resource-map index used by the original APK stable.
    names = list(strings)
    def index(value):
        if value not in names:
            names.append(value)
        return names.index(value)
    android_ns = index(ANDROID_NS)
    uses_permission = index("uses-permission")
    attr_name = index("name")
    permission_indices = [index(x) for x in PERMISSIONS]

    existing = set()
    application_pos = None
    for pos, typ, hsz, size in chunks(raw, rest_start):
        if typ == START_TAG:
            name_idx = struct.unpack_from("<I", raw, pos + 20)[0]
            if name_idx == uses_permission:
                # A permission tag has its first attribute at pos+36 and the
                # typed value's data at +16 within that attribute.
                attr_start, attr_size, attr_count, _ = struct.unpack_from("<HHHH", raw, pos + 24)
                for i in range(attr_count):
                    ap = pos + 16 + attr_start + i * attr_size
                    an = struct.unpack_from("<I", raw, ap + 4)[0]
                    data_type = raw[ap + 15]
                    value = struct.unpack_from("<I", raw, ap + 16)[0]
                    if an == attr_name and data_type == 3:
                        existing.add(value)
            elif name_idx == index("application") and application_pos is None:
                application_pos = pos

    missing = [idx for idx in permission_indices if idx not in existing]
    if not missing:
        print("разрешения геолокации уже есть")
        return
    if application_pos is None:
        die("не найден тег application")

    new_pool = encode_pool(names, styles, flags)
    # Existing non-pool chunks keep their bytes; insert before application so
    # Android sees the permissions as children of manifest and siblings of app.
    inserted = bytearray()
    for line, value_idx in enumerate(missing, 3):
        inserted += tag_chunk(START_TAG, line, 0xffffffff, uses_permission, android_ns, attr_name, value_idx)
        inserted += tag_chunk(END_TAG, line, 0xffffffff, uses_permission)

    # application_pos is in the old stream. It shifts by the string-pool delta.
    before_app = application_pos - rest_start
    tail = raw[rest_start:]
    # application_pos is after any existing permission tags; inserting at this
    # exact offset avoids changing nesting or the resource map.
    new_body = tail[:before_app] + inserted + tail[before_app:]
    out = bytearray(raw[:pool_start]) + new_pool + new_body
    struct.pack_into("<I", out, 4, len(out))
    open(path, "wb").write(out)
    print("добавлены разрешения: " + ", ".join(names[i] for i in missing))


if __name__ == "__main__":
    main()

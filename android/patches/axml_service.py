#!/usr/bin/env python3
"""10.65: служба фоновой проверки уведомлений (этап 2) в двоичном AndroidManifest.xml.

Добавляет внутрь <application>:
    <service android:name="ru.prokatnvsk.app.IvaNotifyPatch$Bg"
             android:permission="android.permission.BIND_JOB_SERVICE"
             android:exported="false"/>
и перед <application>:
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
(чтобы после перезагрузки телефона проверка продолжалась сама; обычное разрешение, без запроса у пользователя).

Сборка идёт из выпущенного APK (apktool -r), манифест остаётся двоичным. Атрибута android:permission в нём нет,
а атрибуты Android ищет по карте ресурсов (resource map), которая покрывает первые строки пула. Поэтому строка
«permission» вставляется в конец покрытой части (с id 0x01010006), а все ссылки на строки после неё сдвигаются на 1;
остальные новые строки добавляются в конец пула. Атрибуты тега идут по возрастанию id (name < permission < exported) —
так их читает Android. Скрипт идемпотентен: если служба уже есть — ничего не меняет.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from axml_permissions import read_pool, encode_pool, chunks  # noqa: E402

AXML = 0x0003
STRING_POOL = 0x0001
RESOURCE_MAP = 0x0180
NS_START, NS_END, START_TAG, END_TAG, CDATA = 0x0100, 0x0101, 0x0102, 0x0103, 0x0104
ANDROID_NS = "http://schemas.android.com/apk/res/android"
SERVICE = "ru.prokatnvsk.app.IvaNotifyPatch$Bg"
BIND = "android.permission.BIND_JOB_SERVICE"
BOOT = "android.permission.RECEIVE_BOOT_COMPLETED"
ATTR_IDS = {"name": 0x01010003, "permission": 0x01010006, "exported": 0x01010010}
NONE = 0xFFFFFFFF
TYPE_STRING, TYPE_BOOL = 0x03, 0x12


def die(msg):
    print("ОШИБКА AXML (служба): " + msg, file=sys.stderr)
    if os.environ.get("GITHUB_ACTIONS"):
        print("::error title=Manifest::" + msg)
    raise SystemExit(1)


def remap_chunk(buf, pos, typ, hsz, size, m):
    """Сдвигает все ссылки на строки в одном XML-узле по функции m (кроме NONE)."""
    def fix(off):
        v = struct.unpack_from("<I", buf, off)[0]
        if v != NONE:
            struct.pack_into("<I", buf, off, m(v))
    if typ in (NS_START, NS_END, START_TAG, END_TAG, CDATA):
        fix(pos + 12)                                  # comment
    if typ in (NS_START, NS_END, END_TAG):
        fix(pos + 16); fix(pos + 20)                   # prefix/uri | ns/name
    elif typ == START_TAG:
        fix(pos + 16); fix(pos + 20)                   # ns, name
        a_start, a_size, a_count = struct.unpack_from("<HHH", buf, pos + 24)
        for i in range(a_count):
            ap = pos + 16 + a_start + i * a_size
            fix(ap); fix(ap + 4); fix(ap + 8)          # ns, name, raw value
            if buf[ap + 15] == TYPE_STRING:
                fix(ap + 16)                           # typed value: строка
    elif typ == CDATA:
        fix(pos + 16)
        if buf[pos + 27] == TYPE_STRING:
            fix(pos + 28)


def start_tag(line, ns, name, attrs):
    """attrs: [(ns, name, raw, type, data)] — уже отсортированы по id ресурса."""
    body = struct.pack("<II", line, NONE) + struct.pack("<II", ns, name)
    body += struct.pack("<HHHHHH", 20, 20, len(attrs), 0, 0, 0)
    for a_ns, a_name, raw, typ, data in attrs:
        body += struct.pack("<III", a_ns, a_name, raw) + struct.pack("<HBBI", 8, 0, typ, data)
    return struct.pack("<HHI", START_TAG, 16, 8 + len(body)) + body


def end_tag(line, ns, name):
    body = struct.pack("<II", line, NONE) + struct.pack("<II", ns, name)
    return struct.pack("<HHI", END_TAG, 16, 8 + len(body)) + body


def main():
    if len(sys.argv) != 2:
        die("нужно: axml_service.py <AndroidManifest.xml>")
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

    if SERVICE in strings:
        for p, t, h, s in chunks(raw, body_start):
            if t == START_TAG and strings[struct.unpack_from("<I", raw, p + 20)[0]] == "service":
                print("служба уведомлений уже есть в манифесте")
                return
    # 1) атрибут «permission» — в покрытую картой часть пула
    shift_from = None
    if ATTR_IDS["permission"] in ids:
        perm_attr = ids.index(ATTR_IDS["permission"])
    else:
        shift_from = len(ids)
        strings.insert(shift_from, "permission")
        ids.append(ATTR_IDS["permission"])
        perm_attr = shift_from
    m = (lambda v: v + 1 if v >= shift_from else v) if shift_from is not None else (lambda v: v)
    for nm in ("name", "exported"):
        if ATTR_IDS[nm] not in ids:
            die("в resource map нет атрибута android:" + nm)
    name_attr, exp_attr = ids.index(ATTR_IDS["name"]), ids.index(ATTR_IDS["exported"])

    def index(value):
        if value in strings[len(ids):]:
            return strings.index(value, len(ids))
        strings.append(value)
        return len(strings) - 1
    if ANDROID_NS not in strings:
        die("нет пространства имён android")
    ns = strings.index(ANDROID_NS)
    s_service, s_name, s_bind = index("service"), index(SERVICE), index(BIND)
    s_uses, s_boot = index("uses-permission"), index(BOOT)

    # 2) тело: сдвиг ссылок, поиск <application> и </application>, есть ли уже BOOT
    body = bytearray(raw[body_start:])
    app_start = app_end = None
    has_boot = False
    for p, t, h, s in chunks(body, 0):
        remap_chunk(body, p, t, h, s, m)
        if t in (START_TAG, END_TAG):
            nm = strings[struct.unpack_from("<I", body, p + 20)[0]]
            if t == START_TAG and nm == "application" and app_start is None:
                app_start = p
            if t == END_TAG and nm == "application":
                app_end = p
            if t == START_TAG and nm == "uses-permission":
                a_start, a_size, a_count = struct.unpack_from("<HHH", body, p + 24)
                for i in range(a_count):
                    ap = p + 16 + a_start + i * a_size
                    if body[ap + 15] == TYPE_STRING and struct.unpack_from("<I", body, ap + 16)[0] == s_boot:
                        has_boot = True
    if app_start is None or app_end is None or app_end < app_start:
        die("не найден тег application")
    line = 30
    service = start_tag(line, NONE, s_service, [
        (ns, name_attr, s_name, TYPE_STRING, s_name),
        (ns, perm_attr, s_bind, TYPE_STRING, s_bind),
        (ns, exp_attr, NONE, TYPE_BOOL, 0),
    ]) + end_tag(line, NONE, s_service)
    boot = b"" if has_boot else start_tag(5, NONE, s_uses, [(ns, name_attr, s_boot, TYPE_STRING, s_boot)]) + end_tag(5, NONE, s_uses)
    body = body[:app_start] + boot + body[app_start:app_end] + service + body[app_end:]

    rm = struct.pack("<HHI", RESOURCE_MAP, 8, 8 + 4 * len(ids)) + struct.pack("<%dI" % len(ids), *ids)
    out = bytearray(raw[:8]) + encode_pool(strings, 0, flags) + rm + body
    struct.pack_into("<I", out, 4, len(out))
    open(path, "wb").write(out)
    print("манифест: служба " + SERVICE + (", разрешение " + BOOT if boot else ""))


if __name__ == "__main__":
    main()

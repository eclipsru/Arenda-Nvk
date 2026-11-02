#!/usr/bin/env python3
"""10.100: провайдер своего звука уведомлений в двоичном AndroidManifest.xml.

Добавляет внутрь <application>:
    <provider android:name="ru.prokatnvsk.app.IvaSoundPatch$Files"
              android:exported="true"
              android:authorities="ru.prokatnvsk.app.ivasound"/>
Звук категории уведомлений Android играет сам (из системного процесса), поэтому файл ему отдаёт провайдер.
Провайдер только читает один файл — звук (assets/iva_notify.mp3); записать или прочитать что-то ещё нельзя.
exported="true" — чтобы звук прочитала системная оболочка любого производителя (на некоторых телефонах звук
играет не system_server); отдавать нечего, кроме самого звука.

Как в axml_service.py: атрибуты Android ищет по карте ресурсов, которая покрывает первые строки пула. Если строки
«authorities» (0x01010018) там нет — она вставляется в конец покрытой части, а все ссылки на строки после неё
сдвигаются на 1. Атрибуты тега — по возрастанию id (name 0x01010003 < exported 0x01010010 < authorities 0x01010018).
Запускается после axml_service.py / axml_push.py / axml_install.py. Идемпотентен: провайдер уже есть — ничего не меняет.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from axml_permissions import read_pool, encode_pool, chunks  # noqa: E402
from axml_service import (remap_chunk, start_tag, end_tag, AXML, RESOURCE_MAP, START_TAG,  # noqa: E402
                          END_TAG, ANDROID_NS, NONE, TYPE_STRING, TYPE_BOOL)

PROVIDER = "ru.prokatnvsk.app.IvaSoundPatch$Files"
AUTHORITY = "ru.prokatnvsk.app.ivasound"
IDS = {"name": 0x01010003, "exported": 0x01010010, "authorities": 0x01010018}


def die(msg):
    print("ОШИБКА AXML (звук): " + msg, file=sys.stderr)
    if os.environ.get("GITHUB_ACTIONS"):
        print("::error title=Manifest::" + msg)
    raise SystemExit(1)


def main():
    if len(sys.argv) != 2:
        die("нужно: axml_sound.py <AndroidManifest.xml>")
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

    if PROVIDER in strings:
        for p, t, h, s in chunks(raw, body_start):
            if t == START_TAG and strings[struct.unpack_from("<I", raw, p + 20)[0]] == "provider":
                print("провайдер звука уже есть в манифесте")
                return
    for nm in ("name", "exported"):
        if IDS[nm] not in ids:
            die("в resource map нет атрибута android:" + nm)
    # 1) атрибут «authorities» — в покрытую картой часть пула
    shift_from = None
    if IDS["authorities"] in ids:
        auth_attr = ids.index(IDS["authorities"])
        if strings[auth_attr] != "authorities":
            die("строка атрибута authorities не совпадает: " + strings[auth_attr])
    else:
        shift_from = len(ids)
        strings.insert(shift_from, "authorities")
        ids.append(IDS["authorities"])
        auth_attr = shift_from
    m = (lambda v: v + 1 if v >= shift_from else v) if shift_from is not None else (lambda v: v)
    name_attr, exp_attr = ids.index(IDS["name"]), ids.index(IDS["exported"])

    def index(value):
        if value in strings[len(ids):]:
            return strings.index(value, len(ids))
        strings.append(value)
        return len(strings) - 1
    if ANDROID_NS not in strings:
        die("нет пространства имён android")
    ns = strings.index(ANDROID_NS)
    s_provider, s_name, s_auth = index("provider"), index(PROVIDER), index(AUTHORITY)

    # 2) тело: сдвиг ссылок, поиск <application> … </application>
    body = bytearray(raw[body_start:])
    app_start = app_end = None
    for p, t, h, s in chunks(body, 0):
        remap_chunk(body, p, t, h, s, m)
        if t in (START_TAG, END_TAG):
            nm = strings[struct.unpack_from("<I", body, p + 20)[0]]
            if t == START_TAG and nm == "application" and app_start is None:
                app_start = p
            if t == END_TAG and nm == "application":
                app_end = p
    if app_start is None or app_end is None or app_end < app_start:
        die("не найден тег application")

    line = 50
    provider = start_tag(line, NONE, s_provider, [
        (ns, name_attr, s_name, TYPE_STRING, s_name),
        (ns, exp_attr, NONE, TYPE_BOOL, 0xFFFFFFFF),
        (ns, auth_attr, s_auth, TYPE_STRING, s_auth),
    ]) + end_tag(line, NONE, s_provider)
    body = body[:app_end] + provider + body[app_end:]

    rm = struct.pack("<HHI", RESOURCE_MAP, 8, 8 + 4 * len(ids)) + struct.pack("<%dI" % len(ids), *ids)
    out = bytearray(raw[:8]) + encode_pool(strings, 0, flags) + rm + body
    struct.pack_into("<I", out, 4, len(out))
    open(path, "wb").write(out)
    print("манифест: провайдер звука " + PROVIDER + " (" + AUTHORITY + ")")


if __name__ == "__main__":
    main()

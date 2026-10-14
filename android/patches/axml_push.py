#!/usr/bin/env python3
"""10.74: приём мгновенных уведомлений (FCM) в двоичном AndroidManifest.xml.

Добавляет перед <application>:
    <uses-permission android:name="com.google.android.c2dm.permission.RECEIVE"/>
    <queries><package android:name="com.google.android.gms"/></queries>
(обычное разрешение без запроса у пользователя; queries — чтобы на Android 11+ приложение «видело»
сервисы Google и могло попросить у них токен);
и внутрь <application>:
    <receiver android:name="ru.prokatnvsk.app.IvaPushPatch$Rx"
              android:permission="com.google.android.c2dm.permission.SEND"
              android:exported="true">
        <intent-filter><action android:name="com.google.android.c2dm.intent.RECEIVE"/></intent-filter>
    </receiver>
(как приёмник Firebase: прислать сообщение может только система сервисов Google — разрешение SEND есть только у неё).

Запускается ПОСЛЕ axml_service.py (тот уже кладёт атрибут «permission» в карту ресурсов); если вдруг его нет —
добавляет так же. Идемпотентен: приёмник уже есть — ничего не меняет.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from axml_permissions import read_pool, encode_pool, chunks  # noqa: E402
from axml_service import (remap_chunk, start_tag, end_tag, AXML, RESOURCE_MAP, START_TAG, END_TAG,  # noqa: E402
                          ANDROID_NS, ATTR_IDS, NONE, TYPE_STRING, TYPE_BOOL)

RECEIVER = "ru.prokatnvsk.app.IvaPushPatch$Rx"
SEND = "com.google.android.c2dm.permission.SEND"
RECV_PERM = "com.google.android.c2dm.permission.RECEIVE"
ACTION = "com.google.android.c2dm.intent.RECEIVE"
GMS = "com.google.android.gms"


def die(msg):
    print("ОШИБКА AXML (push): " + msg, file=sys.stderr)
    if os.environ.get("GITHUB_ACTIONS"):
        print("::error title=Manifest::" + msg)
    raise SystemExit(1)


def main():
    if len(sys.argv) != 2:
        die("нужно: axml_push.py <AndroidManifest.xml>")
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

    if RECEIVER in strings:
        for p, t, h, s in chunks(raw, body_start):
            if t == START_TAG and strings[struct.unpack_from("<I", raw, p + 20)[0]] == "receiver":
                for i in range(struct.unpack_from("<H", raw, p + 28)[0]):
                    ap = p + 16 + struct.unpack_from("<H", raw, p + 24)[0] + i * struct.unpack_from("<H", raw, p + 26)[0]
                    if raw[ap + 15] == TYPE_STRING and strings[struct.unpack_from("<I", raw, ap + 16)[0]] == RECEIVER:
                        print("приёмник push уже есть в манифесте")
                        return
    # 1) атрибут «permission» — в покрытую картой часть пула (если axml_service.py его ещё не добавил)
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
    s_receiver, s_name, s_send = index("receiver"), index(RECEIVER), index(SEND)
    s_filter, s_action, s_act = index("intent-filter"), index("action"), index(ACTION)
    s_uses, s_recv = index("uses-permission"), index(RECV_PERM)
    s_queries, s_package, s_gms = index("queries"), index("package"), index(GMS)

    # 2) тело: сдвиг ссылок, поиск <application> и </application>, есть ли уже разрешение и queries для GMS
    body = bytearray(raw[body_start:])
    app_start = app_end = None
    has_recv = has_gms_query = False
    in_queries = False
    for p, t, h, s in chunks(body, 0):
        remap_chunk(body, p, t, h, s, m)
        if t in (START_TAG, END_TAG):
            nm = strings[struct.unpack_from("<I", body, p + 20)[0]]
            if t == START_TAG and nm == "application" and app_start is None:
                app_start = p
            if t == END_TAG and nm == "application":
                app_end = p
            if nm == "queries":
                in_queries = t == START_TAG
            if t == START_TAG and nm in ("uses-permission", "package"):
                a_start, a_size, a_count = struct.unpack_from("<HHH", body, p + 24)
                for i in range(a_count):
                    ap = p + 16 + a_start + i * a_size
                    if body[ap + 15] != TYPE_STRING:
                        continue
                    v = struct.unpack_from("<I", body, ap + 16)[0]
                    if nm == "uses-permission" and v == s_recv:
                        has_recv = True
                    if nm == "package" and in_queries and v == s_gms:
                        has_gms_query = True
    if app_start is None or app_end is None or app_end < app_start:
        die("не найден тег application")

    line = 40
    receiver = (start_tag(line, NONE, s_receiver, [
        (ns, name_attr, s_name, TYPE_STRING, s_name),
        (ns, perm_attr, s_send, TYPE_STRING, s_send),
        (ns, exp_attr, NONE, TYPE_BOOL, 0xFFFFFFFF),
    ]) + start_tag(line + 1, NONE, s_filter, [])
        + start_tag(line + 2, NONE, s_action, [(ns, name_attr, s_act, TYPE_STRING, s_act)]) + end_tag(line + 2, NONE, s_action)
        + end_tag(line + 1, NONE, s_filter) + end_tag(line, NONE, s_receiver))
    before = b""
    if not has_recv:
        before += start_tag(6, NONE, s_uses, [(ns, name_attr, s_recv, TYPE_STRING, s_recv)]) + end_tag(6, NONE, s_uses)
    if not has_gms_query:
        before += (start_tag(7, NONE, s_queries, [])
                   + start_tag(8, NONE, s_package, [(ns, name_attr, s_gms, TYPE_STRING, s_gms)]) + end_tag(8, NONE, s_package)
                   + end_tag(7, NONE, s_queries))
    body = body[:app_start] + before + body[app_start:app_end] + receiver + body[app_end:]

    rm = struct.pack("<HHI", RESOURCE_MAP, 8, 8 + 4 * len(ids)) + struct.pack("<%dI" % len(ids), *ids)
    out = bytearray(raw[:8]) + encode_pool(strings, 0, flags) + rm + body
    struct.pack_into("<I", out, 4, len(out))
    open(path, "wb").write(out)
    print("манифест: приёмник " + RECEIVER + ("" if has_recv else ", разрешение " + RECV_PERM)
          + ("" if has_gms_query else ", queries " + GMS))


if __name__ == "__main__":
    main()

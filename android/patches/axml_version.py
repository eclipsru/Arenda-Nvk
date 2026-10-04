#!/usr/bin/env python3
"""Ива — смена версии прямо в двоичном AndroidManifest.xml (без пересборки ресурсов).

Запуск: python3 axml_version.py <AndroidManifest.xml> <старая versionName> <новая versionName> <новый versionCode>
Строго: versionName меняется только при той же длине (10.14 → 10.15), строка должна встречаться
в таблице строк ровно один раз; versionCode — атрибут android:versionCode (0x0101021b) тега <manifest>.
"""
import struct
import sys

VERSION_CODE = 0x0101021b
VERSION_NAME = 0x0101021c


def die(m):
    print('ОШИБКА ВЕРСИИ: ' + m, file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 5:
        die('нужно: axml_version.py <файл> <старая> <новая> <versionCode>')
    path, old, new, code = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
    if len(old) != len(new):
        die('новая versionName должна быть той же длины')
    b = bytearray(open(path, 'rb').read())
    if struct.unpack_from('<HHI', b, 0)[0] != 0x0003:
        die('не двоичный манифест')
    pos, strings, utf8, str_off, res_ids = 8, [], False, [], []
    pool_start = None
    while pos < len(b):
        typ, hsz, size = struct.unpack_from('<HHI', b, pos)
        if typ == 0x0001:  # таблица строк
            pool_start = pos
            cnt, _sty, flags, s_start, _ = struct.unpack_from('<IIIII', b, pos + 8)
            utf8 = bool(flags & 0x100)
            offs = struct.unpack_from('<%dI' % cnt, b, pos + hsz)
            for o in offs:
                p = pos + s_start + o
                if utf8:
                    n = b[p]; p += 2 if n & 0x80 else 1           # длина в символах
                    n = b[p]; q = p + (2 if n & 0x80 else 1)      # длина в байтах
                    ln = ((b[p] & 0x7f) << 8 | b[p + 1]) if n & 0x80 else n
                    strings.append(b[q:q + ln].decode('utf-8', 'replace')); str_off.append((q, ln))
                else:
                    ln = struct.unpack_from('<H', b, p)[0]; q = p + 2
                    strings.append(b[q:q + 2 * ln].decode('utf-16-le', 'replace')); str_off.append((q, 2 * ln))
        elif typ == 0x0180:  # карта id ресурсов
            res_ids = list(struct.unpack_from('<%dI' % ((size - hsz) // 4), b, pos + hsz))
        elif typ == 0x0102:  # начало тега
            name_idx = struct.unpack_from('<I', b, pos + 20)[0]
            if strings[name_idx] == 'manifest':
                a_start, a_size, a_cnt = struct.unpack_from('<HHH', b, pos + 24)
                code_done = name_done = False
                for k in range(a_cnt):
                    ap = pos + 16 + a_start + k * a_size
                    _ns, an, raw, _sz, _r0, dtype, data = struct.unpack_from('<IIIHBBI', b, ap)
                    rid = res_ids[an] if an < len(res_ids) else 0
                    if rid == VERSION_CODE:
                        if dtype not in (0x10, 0x11):
                            die('versionCode не число')
                        print(f'versionCode: {data} → {code}')
                        struct.pack_into('<I', b, ap + 16, code); code_done = True
                    elif rid == VERSION_NAME:
                        cur = strings[raw] if raw != 0xffffffff else strings[data]
                        if cur != old:
                            die(f'versionName в манифесте «{cur}», ожидалось «{old}»')
                        name_done = True
                if not (code_done and name_done):
                    die('в <manifest> нет versionCode/versionName')
                break
        pos += size
    hits = [i for i, s in enumerate(strings) if s == old]
    if len(hits) != 1:
        die(f'строка «{old}» в таблице строк встречается {len(hits)} раз, нужно 1')
    q, ln = str_off[hits[0]]
    enc = new.encode('utf-8' if utf8 else 'utf-16-le')
    if len(enc) != ln:
        die('длина строки не совпала')
    b[q:q + ln] = enc
    print(f'versionName: {old} → {new}')
    open(path, 'wb').write(bytes(b))


if __name__ == '__main__':
    main()

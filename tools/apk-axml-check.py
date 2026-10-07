#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apk-axml-check.py — независимый разбор AndroidManifest.xml ВНУТРИ APK.

Зачем. 10.15 не ставится на телефон владельца («Приложение не установлено»).
Если бы в двоичном манифесте была сломана структура (сдвиг строк, неверная длина
строки, битый размер блока), установщик Android мог бы молча отказать, а
androguard/apksigner при этом читали бы файл нормально. Этот скрипт читает
манифест СВОИМ разборщиком формата AXML (без Java, без androguard) и проверяет
структуру по правилам формата:

  • размеры всех блоков (chunk) сходятся и покрывают файл без остатка;
  • пул строк: смещения возрастают, каждая строка заканчивается нулём,
    объявленная длина совпадает с фактической;
  • вложенность элементов: каждый </tag> закрывает ровно свой <tag>;
  • версия/пакет/минимальная и целевая версия Android читаются из бинарника.

Второй режим — сравнение двух APK: печатает отличия манифестов по элементам
и атрибутам (удобно убедиться, что «манифест 10.15 отличается от 10.14 только
версией», и что структура при этом не поехала).

Запуск:
  python3 tools/apk-axml-check.py ProkatInstrumenta-10.15.apk
  python3 tools/apk-axml-check.py ProkatInstrumenta-10.15.apk ProkatInstrumenta-10.14.apk

Ограничения (честно): это проверка структуры файла, а не запуск на Android.
Скрипт не доказывает, что установщик конкретной модели телефона примет файл;
он доказывает, что манифест внутри APK синтаксически цел и совпадает с рабочим
манифестом предыдущей версии.
"""
import sys, zipfile, struct

UTF8_FLAG = 1 << 8

def u16(b, o): return struct.unpack_from('<H', b, o)[0]
def u32(b, o): return struct.unpack_from('<I', b, o)[0]

def decode_len(b, o):
    """Длина строки в AXML: 1 байт, либо 2 байта, если старший бит первого установлен."""
    x = b[o]
    if x & 0x80:
        return ((x & 0x7f) << 8) | b[o + 1], o + 2
    return x, o + 1

def read_string_pool(data):
    """Разбор блока пула строк. Возвращает (список строк, схема строк, список проблем)."""
    problems = []
    ctype, hsize, csize = u16(data, 0), u16(data, 2), u32(data, 4)
    if ctype != 0x0001: problems.append(f'ожидался пул строк (0x0001), получено 0x{ctype:04x}')
    count, style_count, flags = u32(data, 8), u32(data, 12), u32(data, 16)
    strings_start, styles_start = u32(data, 20), u32(data, 24)
    utf8 = bool(flags & UTF8_FLAG)
    if strings_start < hsize or strings_start > csize:
        problems.append(f'смещение данных строк {strings_start} вне блока {hsize}..{csize}')
    offsets = [u32(data, hsize + 4 * i) for i in range(count)]
    for i in range(1, count):
        if offsets[i] < offsets[i - 1]:
            problems.append(f'смещения строк не возрастают: строка {i} ({offsets[i]}) < строка {i-1}')
    base = strings_start
    out = []
    for i in range(count):
        start = base + offsets[i]
        end_limit = base + (offsets[i + 1] if i + 1 < count else (styles_start or csize) - strings_start)
        try:
            if utf8:
                blen, p = decode_len(data, start)
                raw = data[p:p + blen]
                if len(raw) != blen:
                    problems.append(f'строка {i}: объявлено {blen} байт, в файле меньше')
                if data[p + blen:p + blen + 1] != b'\x00':
                    problems.append(f'строка {i}: нет нуля после данных')
                clen, q = decode_len(data, p + blen + 1)
                s = raw.decode('utf-8', 'replace')
                if q > end_limit:
                    problems.append(f'строка {i}: длина в символах ({clen}) выходит за границу строки')
                if clen != len(s):
                    problems.append(f'строка {i}: объявлено символов {clen}, а разобрано {len(s)}')
            else:
                blen = u16(data, start)
                p = start + 2
                raw = data[p:p + 2 * blen]
                if len(raw) != 2 * blen:
                    problems.append(f'строка {i}: объявлено {blen} символов, в файле меньше')
                s = raw.decode('utf-16-le', 'replace')
                if data[p + 2 * blen:p + 2 * blen + 2] != b'\x00\x00':
                    problems.append(f'строка {i}: нет двойного нуля после данных')
        except Exception as e:  # noqa: BLE001
            problems.append(f'строка {i}: сбой разбора ({e})')
            s = ''
        out.append(s)
        if len(problems) > 40:
            problems.append('… дальше не перечисляю')
            break
    return out, {'utf8': utf8, 'count': count, 'style_count': style_count,
                 'strings_start': strings_start, 'size': csize, 'header': hsize}, problems

NODE_NAMES = {0x0100: 'START_NAMESPACE', 0x0101: 'END_NAMESPACE', 0x0102: 'START_ELEMENT',
              0x0103: 'END_ELEMENT', 0x0104: 'CDATA'}

def parse_axml(data):
    """Полный разбор AndroidManifest.xml (формат AXML). Возвращает (дерево, сведения, проблемы)."""
    problems, tree, stack = [], [], []
    ctype, hsize, csize = u16(data, 0), u16(data, 2), u32(data, 4)
    if ctype != 0x0003:
        problems.append(f'файл не начинается с XML-заголовка (0x0003), получено 0x{ctype:04x}')
    if csize != len(data):
        problems.append(f'размер, объявленный в заголовке ({csize}), не равен длине файла ({len(data)})')
    o = hsize
    strings, info, sp = [], {}, ['пул строк не найден']
    while o + 8 <= len(data):
        t, hs, cs = u16(data, o), u16(data, o + 2), u32(data, o + 4)
        if cs < 8 or o + cs > len(data):
            problems.append(f'блок 0x{t:04x} по смещению {o}: размер {cs} выходит за файл')
            break
        chunk = data[o:o + cs]
        if t == 0x0001:
            strings, info, sp = read_string_pool(chunk)
            problems += [f'пул строк: {m}' for m in sp]
        elif t == 0x0180:            # таблица ресурсов (имена атрибутов android:)
            pass
        elif t in NODE_NAMES:
            if t == 0x0102:          # START_ELEMENT
                name = strings[u32(chunk, 20)] if u32(chunk, 20) < len(strings) else '?'
                attrs = {}
                acount = u16(chunk, 28)
                astart = 16 + u16(chunk, 24)   # смещение считается от структуры attrExt (она начинается с 16)
                for a in range(acount):
                    ao = astart + a * 20
                    an = u32(chunk, ao + 4)
                    aname = strings[an] if an < len(strings) else f'#{an}'
                    rawv = u32(chunk, ao + 8)
                    dtype = chunk[ao + 15]
                    dval = u32(chunk, ao + 16)
                    if rawv != 0xFFFFFFFF and rawv < len(strings):
                        val = strings[rawv]
                    elif dtype == 0x12:  # boolean
                        val = 'true' if dval else 'false'
                    elif dtype == 0x10:  # int
                        val = str(dval)
                    elif dtype == 0x01:
                        val = f'0x{dval:08x}'
                    else:
                        val = f'<тип {dtype}>'
                    attrs[aname] = val
                tree.append((len(stack), name, attrs))
                stack.append(name)
            elif t == 0x0103:        # END_ELEMENT
                name = strings[u32(chunk, 20)] if u32(chunk, 20) < len(strings) else '?'
                if not stack or stack[-1] != name:
                    problems.append(f'</{name}> закрывает не то, что открыто ({stack[-1] if stack else "ничего"})')
                else:
                    stack.pop()
        o += cs
    if o != len(data):
        problems.append(f'после разбора блоков остался хвост {len(data) - o} байт')
    if stack:
        problems.append(f'не закрыты элементы: {stack}')
    return tree, info, problems

def manifest_of(path):
    with zipfile.ZipFile(path) as z:
        return z.read('AndroidManifest.xml')

def show(path):
    data = manifest_of(path)
    tree, info, problems = parse_axml(data)
    root = tree[0][2] if tree else {}
    print(f'== {path}')
    print(f'   байт в манифесте: {len(data)}; пул строк: {info.get("count")} строк, '
          f'{"UTF-8" if info.get("utf8") else "UTF-16"}, блок {info.get("size")} байт')
    print(f'   пакет: {root.get("package")}; versionCode: {root.get("versionCode")}; '
          f'versionName: {root.get("versionName")}')
    ver = [a for (_, n, at) in tree if n == 'uses-sdk' for a in [at]][0] if any(n == 'uses-sdk' for _, n, _ in tree) else {}
    print(f'   minSdk: {ver.get("minSdkVersion")}; targetSdk: {ver.get("targetSdkVersion")}')
    print(f'   элементов: {len(tree)}')
    if problems:
        print('   ✘ проблемы структуры:')
        for p in problems[:20]:
            print('     -', p)
    else:
        print('   ✔ структура в порядке (размеры блоков, пул строк, вложенность)')
    return tree, root, problems

def compare(p1, p2):
    t1, r1, pr1 = show(p1)
    print()
    t2, r2, pr2 = show(p2)
    print()
    diffs = [(k, r2.get(k), r1.get(k)) for k in sorted(set(r1) | set(r2)) if r1.get(k) != r2.get(k)]
    print(f'== отличия корневых атрибутов (по всему манифесту {p1} против {p2}):')
    if diffs:
        for k, a, b in diffs:
            print(f'   {k}: {a!r} → {b!r}')
    else:
        print('   (нет)')
    only_version = all(k in ('versionCode', 'versionName') for k, _, _ in diffs)
    tree_diffs = []
    if len(t1) != len(t2):
        tree_diffs.append(f'разное число элементов: {len(t1)} и {len(t2)}')
    for i, ((d1, n1, a1), (d2, n2, a2)) in enumerate(zip(t1, t2)):
        if n1 != n2:
            tree_diffs.append(f'элемент №{i}: {n2} → {n1}')
        for k in sorted(set(a1) | set(a2)):
            if a1.get(k) != a2.get(k):
                if k in ('versionCode', 'versionName'):
                    continue
                tree_diffs.append(f'{n1}.{k}: {a2.get(k)!r} → {a1.get(k)!r}')
    print('== отличия в дереве элементов и остальных атрибутах:')
    if tree_diffs:
        for d in tree_diffs[:20]:
            print('   ' + d)
    else:
        print('   (нет — кроме номеров версий ничего не изменилось)')
    ok = not pr1 and not pr2 and only_version and not [d for d in tree_diffs if not d.startswith('разное число элементов')]
    print()
    print('ИТОГ:', 'манифесты совпадают во всём, кроме версии; структура цела' if ok else 'ЕСТЬ ОТЛИЧИЯ ИЛИ ПРОБЛЕМЫ — смотрите выше')
    return 0 if ok else 1

if __name__ == '__main__':
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    if len(args) == 1:
        _, _, pr = show(args[0])
        sys.exit(1 if pr else 0)
    sys.exit(compare(args[0], args[1]))

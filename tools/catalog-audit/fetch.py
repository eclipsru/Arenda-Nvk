#!/usr/bin/env python3
"""Ива — выгрузка каталога для проверки фильтров приложения (только чтение).

Берёт ровно то, что берёт приложение 10.14 при запуске (loadCatalog):
  /rest/v1/tools?select=*&active=eq.true&order=sort.asc.nullslast,name.asc
  /rest/v1/cats?select=*&active=eq.true&order=sort.asc.nullslast
  /rest/v1/subcats?select=*&active=eq.true&order=sort.asc.nullslast
Адрес и публичный (publishable) ключ — из assets/sb.js, как у сайта. Ничего не пишет в базу.

Пишет tools.tsv, cats.tsv, subcats.tsv в папку из аргумента. Каждое поле кодируется так, чтобы Java
могла воспроизвести и штатное чтение Android (optString: null → "null", нет поля → ""), и чтение сайта:
  \\x01 — поля нет, \\x02 — JSON null, иначе текст (\\\\, \\t, \\n, \\r экранированы); массив cats — элементы через \\x1f.
Запуск: python3 tools/catalog-audit/fetch.py /tmp/catalog
"""
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
TOOL_FIELDS = ['id', 't_status', 'cat', 'cats', 'sub', 'name', 'descr', 'pickup_city', 'pickup_address', 'terms',
               'price', 'delivery', 'pickup_lat', 'pickup_lng']


def sb_config():
    src = open(os.path.join(ROOT, 'assets', 'sb.js'), encoding='utf-8').read()
    url = re.search(r"const SB\s*=\s*'([^']+)'", src).group(1)
    key = re.search(r"const KEY\s*=\s*'([^']+)'", src).group(1)
    return url, key


def get(url, key, path):
    req = urllib.request.Request(url + path, headers={'apikey': key, 'Authorization': 'Bearer ' + key,
                                                      'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode('utf-8'))


def esc(s):
    return s.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n').replace('\r', '\\r')


def enc(obj, k):
    if k not in obj:
        return '\x01'
    v = obj[k]
    if v is None:
        return '\x02'
    if k == 'cats':
        if not isinstance(v, list):
            return '\x01'   # штатный optJSONArray вернёт null
        return '\x1f'.join(esc(android_str(x)) for x in v)
    return esc(android_str(v))


def android_str(v):
    """Как org.json у Android превращает значение в строку (optString)."""
    if v is None:
        return 'null'
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, float) and v.is_integer():
        return repr(v)   # 500.0 — как Double.toString
    if isinstance(v, (dict, list)):
        return json.dumps(v, ensure_ascii=False, separators=(',', ':'))
    return str(v)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else '/tmp/catalog'
    os.makedirs(out, exist_ok=True)
    url, key = sb_config()
    tools = get(url, key, '/rest/v1/tools?select=*&active=eq.true&order=sort.asc.nullslast,name.asc')
    cats = get(url, key, '/rest/v1/cats?select=*&active=eq.true&order=sort.asc.nullslast')
    subs = get(url, key, '/rest/v1/subcats?select=*&active=eq.true&order=sort.asc.nullslast')
    with open(os.path.join(out, 'tools.tsv'), 'w', encoding='utf-8') as f:
        f.write('\t'.join(TOOL_FIELDS) + '\n')
        for t in tools:
            f.write('\t'.join(enc(t, k) for k in TOOL_FIELDS) + '\n')
    with open(os.path.join(out, 'cats.tsv'), 'w', encoding='utf-8') as f:
        f.write('key\ttitle\n')
        for c in cats:
            f.write(enc(c, 'key') + '\t' + enc(c, 'title') + '\n')
    with open(os.path.join(out, 'subcats.tsv'), 'w', encoding='utf-8') as f:
        f.write('cat\tkey\ttitle\n')
        for c in subs:
            f.write(enc(c, 'cat') + '\t' + enc(c, 'key') + '\t' + enc(c, 'title') + '\n')
    print('каталог: объявлений %d, категорий %d, подкатегорий %d' % (len(tools), len(cats), len(subs)))
    # поля, которые есть в выгрузке (для отчёта: есть ли координаты для радиуса)
    keys = sorted({k for t in tools for k in t.keys()})
    print('поля tools: ' + ', '.join(keys))


if __name__ == '__main__':
    main()

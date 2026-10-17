#!/usr/bin/env python3
"""Ива — справочник категорий в базе (для нового дерева категорий и «Видов»). Только чтение.

Печатает то, что и так видно сайту и приложению публичным ключом:
  * cats / subcats — все поля всех видимых строк (ключ, название, значок, порядок, активность);
  * tools — у каждого объявления только id, cat, cats, sub, active (без названий и контактов);
  * описание таблиц cats / subcats / tools из схемы PostgREST (колонки, типы, первичные ключи), если база её отдаёт;
  * есть ли уже iva_kinds и tools.kind (запрос с limit=0 — строки не читаются).
Адрес и публичный ключ — из assets/sb.js, как у сайта. Ничего не пишет в базу.
Запуск: python3 tools/catalog-audit/probe-cats.py
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')


def sb_config():
    src = open(os.path.join(ROOT, 'assets', 'sb.js'), encoding='utf-8').read()
    url = re.search(r"const SB\s*=\s*'([^']+)'", src).group(1)
    key = re.search(r"const KEY\s*=\s*'([^']+)'", src).group(1)
    return url, key


def get(url, key, path, accept='application/json'):
    req = urllib.request.Request(url + path, headers={'apikey': key, 'Authorization': 'Bearer ' + key,
                                                      'Accept': accept})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', 'replace')
        return e.code, body[:200]
    except Exception as e:
        return 0, type(e).__name__


def short(v):
    if v is None:
        return '∅'
    s = json.dumps(v, ensure_ascii=False) if isinstance(v, (list, dict)) else str(v)
    return s.replace('\n', ' ')[:40]


def main():
    url, key = sb_config()
    out = []
    st, cats = get(url, key, '/rest/v1/cats?select=*&order=sort.asc.nullslast')
    if st == 200:
        cols = sorted({k for c in cats for k in c})
        out.append('CATS %d [%s]' % (len(cats), ','.join(cols)))
        for c in cats:
            out.append('C ' + ' | '.join(k + '=' + short(c.get(k)) for k in cols))
    else:
        out.append('CATS ответ %s %s' % (st, cats))
    st, subs = get(url, key, '/rest/v1/subcats?select=*&order=cat.asc,sort.asc.nullslast')
    if st == 200:
        cols = sorted({k for c in subs for k in c})
        out.append('SUBCATS %d [%s]' % (len(subs), ','.join(cols)))
        for c in subs:
            out.append('S ' + ' | '.join(k + '=' + short(c.get(k)) for k in cols))
    else:
        out.append('SUBCATS ответ %s %s' % (st, subs))
    st, tools = get(url, key, '/rest/v1/tools?select=id,cat,cats,sub,active&order=id.asc')
    if st == 200:
        out.append('TOOLS %d' % len(tools))
        for t in tools:
            out.append('T ' + ' | '.join(k + '=' + short(t.get(k)) for k in ('id', 'cat', 'cats', 'sub', 'active')))
    else:
        out.append('TOOLS ответ %s %s' % (st, tools))
    for path in ('/rest/v1/iva_kinds?select=key&limit=0', '/rest/v1/tools?select=kind&limit=0'):
        st, body = get(url, key, path)
        out.append('PROBE %s → %s %s' % (path, st, '' if st == 200 else short(body)))
    st, spec = get(url, key, '/rest/v1/', 'application/openapi+json')
    if st == 200 and isinstance(spec, dict):
        defs = spec.get('definitions', {})
        for t in ('cats', 'subcats', 'tools'):
            d = defs.get(t)
            if not d:
                out.append('SCHEMA %s: нет описания' % t)
                continue
            req = set(d.get('required', []))
            parts = []
            for name, p in d.get('properties', {}).items():
                flag = ('!' if name in req else '') + ('PK' if 'Primary Key' in (p.get('description') or '') else '')
                fk = re.search(r"<fk table='(\w+)' column='(\w+)'", p.get('description') or '')
                parts.append('%s:%s%s%s%s' % (name, p.get('format', p.get('type', '?')),
                                              (' ' + flag) if flag else '',
                                              (' def=' + short(p['default'])) if 'default' in p else '',
                                              (' fk=' + fk.group(1) + '.' + fk.group(2)) if fk else ''))
            out.append('SCHEMA %s: %s' % (t, ', '.join(parts)))
    else:
        out.append('SCHEMA: схема не отдана (%s)' % st)
    print('\n'.join(out))
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Ива — какие таблицы и колонки есть в базе (для фильтров разделов 10.47). Только чтение, данные не читаются.

Каждый запрос — GET с limit=0: строки не возвращаются никогда. Ответ базы говорит только о схеме:
  200                      — колонка есть, анониму видна (строк всё равно 0: limit=0);
  401/403 или код 42501    — колонка есть, анониму закрыта (несуществующую колонку база отвергла бы раньше, 42703);
  400 с кодом 42703        — колонки нет;
  404 / PGRST205           — таблицы нет;
  PGRST200                 — связи между таблицами нет (вложенный select).
Адрес и публичный ключ — из assets/sb.js, как у сайта. Ничего не пишет в базу.
Запуск: python3 tools/catalog-audit/probe-columns.py
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')

# Что нужно знать для фильтров 10.47: статусы заявок клиента (orders / order_parts), поля карточки заявки,
# поля чата (app_messages). Колонки — те, что читают приложение 10.14, раздел логотипа 10.43 и сайт.
PROBES = [
    ('orders', ['created_at', 'tools', 'get_method', 'user_email', 'status', 'days', 'comment', 'address', 'name']),
    ('order_parts', ['order_id', 'owner_email', 'tools_text', 'status', 'archived', 'created_at']),
    ('orders', ['order_parts(status,tools_text)']),
    ('app_messages', ['from_email', 'to_email', 'text', 'img', 'audio', 'is_read', 'created_at']),
]


def sb_config():
    src = open(os.path.join(ROOT, 'assets', 'sb.js'), encoding='utf-8').read()
    url = re.search(r"const SB\s*=\s*'([^']+)'", src).group(1)
    key = re.search(r"const KEY\s*=\s*'([^']+)'", src).group(1)
    return url, key


def probe(url, key, table, col):
    path = '/rest/v1/' + table + '?select=' + col + '&limit=0'
    req = urllib.request.Request(url + path, headers={'apikey': key, 'Authorization': 'Bearer ' + key,
                                                      'Accept': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            r.read()
            return 'есть'
    except urllib.error.HTTPError as e:
        body = e.read().decode('utf-8', 'replace')
        try:
            code = str(json.loads(body).get('code', ''))
        except Exception:
            code = ''
        if code == '42703':
            return 'НЕТ'
        if code == 'PGRST205' or (e.code == 404 and 'table' in body):
            return 'нет таблицы'
        if code == 'PGRST200':
            return 'нет связи'
        if e.code in (401, 403) or code == '42501':
            return 'есть (закрыта)'
        return 'ответ %d %s' % (e.code, code)
    except Exception as e:   # сеть — не ошибка проверки
        return 'нет связи с базой: ' + type(e).__name__


def main():
    url, key = sb_config()
    lines = []
    for table, cols in PROBES:
        res = [c + ' ' + probe(url, key, table, c) for c in cols]
        lines.append(table + ': ' + ', '.join(res))
    print('\n'.join(lines))
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Ива — схема базы для чата приложения (10.54). Только чтение, строки не читаются.

1) Описание схемы PostgREST (GET /rest/v1/ — OpenAPI, только имена и типы колонок, без данных):
   типы app_messages.id / tool_id, tools.id, поля admins, customer_profiles.
2) Если описание закрыто — пробы колонок GET ...&limit=0 (как probe-columns.py): строки не возвращаются никогда.
3) Есть ли функции чата 10.54 (rpc с неверными аргументами: 404 PGRST202 — нет функции, иначе есть). Ничего не пишет.
Адрес и публичный ключ — из assets/sb.js, как у сайта.
Запуск: python3 tools/catalog-audit/probe-chat.py
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
TABLES = {
    'app_messages': ['id', 'from_email', 'to_email', 'text', 'img', 'audio', 'audio_dur', 'is_read', 'created_at', 'tool_id'],
    'tools': ['id', 'name', 'img', 'imgs', 'owner_email', 'active'],
    'admins': ['email', 'full_name', 'name', 'company', 'avatar', 'active', 'role'],
    'customer_profiles': ['user_id', 'given_name', 'surname', 'avatar_path'],
}
RPCS = ['iva_chat_peers', 'iva_tag_my_messages']


def sb_config():
    src = open(os.path.join(ROOT, 'assets', 'sb.js'), encoding='utf-8').read()
    url = re.search(r"const SB\s*=\s*'([^']+)'", src).group(1)
    key = re.search(r"const KEY\s*=\s*'([^']+)'", src).group(1)
    return url, key


def get(url, key, path, method='GET', body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url + path, data=data, method=method,
                                 headers={'apikey': key, 'Authorization': 'Bearer ' + key,
                                          'Accept': 'application/openapi+json, application/json',
                                          'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')


def code_of(body):
    try:
        return str(json.loads(body).get('code', ''))
    except Exception:
        return ''


def main():
    url, key = sb_config()
    out = []
    try:
        st, body = get(url, key, '/rest/v1/')
        spec = json.loads(body) if st == 200 else {}
    except Exception as e:
        spec = {}
        out.append('описание схемы: нет связи (' + type(e).__name__ + ')')
    defs = spec.get('definitions') or {}
    for table, cols in TABLES.items():
        d = defs.get(table)
        if d and d.get('properties'):
            props = d['properties']
            res = []
            for c in cols:
                p = props.get(c)
                res.append(c + ' ' + (('=' + (p.get('format') or p.get('type') or '?')) if p else 'НЕТ'))
            out.append(table + ' (схема): ' + ', '.join(res))
        else:
            res = []
            for c in cols:
                st, body = get(url, key, '/rest/v1/' + table + '?select=' + c + '&limit=0')
                cd = code_of(body)
                if st == 200:
                    res.append(c + ' есть')
                elif cd == '42703':
                    res.append(c + ' НЕТ')
                elif cd == 'PGRST205' or st == 404:
                    res = ['нет таблицы']
                    break
                elif st in (401, 403) or cd == '42501':
                    res.append(c + ' есть (закрыта)')
                else:
                    res.append(c + ' ответ %d %s' % (st, cd))
            out.append(table + ' (пробы): ' + ', '.join(res))
    for fn in RPCS:
        st, body = get(url, key, '/rest/v1/rpc/' + fn, 'POST', {'iva_probe_no_such_arg': 1})
        cd = code_of(body)
        try:
            hint = str(json.loads(body).get('hint') or '')[:120]
        except Exception:
            hint = ''
        out.append('rpc ' + fn + ': ответ %d %s %s' % (st, cd, hint))
    print('\n'.join(out))
    return 0


if __name__ == '__main__':
    sys.exit(main())

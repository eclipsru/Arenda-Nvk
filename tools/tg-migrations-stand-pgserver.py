#!/usr/bin/env python3
# ============================================================
#  Ива — одноразовый PostgreSQL из pgserver (PyPI) для стенда миграций бота.
#
#  Зачем: `tools/tg-migrations-stand.sh` проверяет миграции Telegram-бота только на
#  живой базе, а в песочнице агента системного PostgreSQL нет (нет и `sudo -u postgres`).
#  Скрипт поднимает свой PostgreSQL, прогоняет на нём стенд и гасит базу.
#
#  Запуск:  python3 tools/tg-migrations-stand-pgserver.py
#           (в check.sh — раздел 4.2, автоматически, если системной базы нет;
#            нужен python с пакетом pgserver: PG_PY=<python> bash tools/check.sh)
#  Код выхода = код выхода стенда (0 — все проверки пройдены, 2 — пропуск).
#  Откат: удалить файл — на сайт и базу не влияет.
# ============================================================
import os
import shutil
import subprocess
import sys
import tempfile

try:
    import pgserver
except ImportError:
    print('ПРОПУСК: нет пакета pgserver (pip install pgserver) — стенд миграций бота не выполнен.')
    sys.exit(2)

pgdata = tempfile.mkdtemp(prefix='iva-pg-')
psql = os.path.join(os.path.dirname(pgserver.__file__), 'pginstall', 'bin', 'psql')
pg_ctl = os.path.join(os.path.dirname(psql), 'pg_ctl')
srv = pgserver.get_server(pgdata, cleanup_mode=None)
rc = 2
try:
    uri = srv.get_uri()                     # postgresql://postgres:@/postgres?host=<каталог сокета>
    query = uri.split('?', 1)[1] if '?' in uri else ''
    host = dict(p.split('=', 1) for p in query.split('&') if '=' in p).get('host', '')
    env = dict(os.environ, PGHOST=host, PGUSER=str(getattr(srv, 'postgres_user', 'postgres') or 'postgres'), PSQL=psql)
    rc = subprocess.call(['bash', 'tools/tg-migrations-stand.sh'], env=env)
finally:
    subprocess.call([pg_ctl, '-D', pgdata, 'stop', '-m', 'immediate'],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shutil.rmtree(pgdata, ignore_errors=True)
sys.exit(rc)

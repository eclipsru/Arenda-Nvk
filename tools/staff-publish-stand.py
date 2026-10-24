# Стенд 10.116 «Сотрудник: причина отказа словами, „на одобрение“ — владельцу сразу»: 20261107_staff_publish_check.sql
# на настоящем PostgreSQL.
#
# Стенд А — база как в стенде 10.115 (все файлы сотрудников, упрощённое хранилище). Проверяет: файл применяется
# и повторяется, таблица в конце — «✅» и строки по сотрудникам; причина по почте: не вошёл, админ, не сотрудник,
# приглашён, без права «Выкладывает инструмент», без пункта, прокат выключен, нет правила фото, всё в порядке;
# приложение спрашивает только про себя (гость не может, чужую почту не спросить); инструмент сотрудника
# «на одобрение» → отметка владельцу (Realtime) и push «o», сразу видимый — без отметки, одобрили — отметка;
# сбой push не мешает публикации; откат из шапки.
# Стенд Б — база без 20261029/20261030: файл применяется, причина — «нет обновления», отметка не создаётся.
# Стенд В — база без 20261028: понятная остановка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-publish-stand.py
import ast
import json
import os
import subprocess
import sys
import tempfile
import time
import uuid

import pgserver
import pgserver.postgres_server

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def rd(p):
    return open(os.path.join(ROOT, p), encoding='utf-8').read()


def const(path, name):
    for node in ast.parse(rd(path)).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') == name:
            return ast.literal_eval(node.value)
    raise SystemExit('нет %s в %s' % (name, path))


RENT = rd('supabase/migrations/20261014_rent_end_mode.sql')
PICK = rd('supabase/migrations/20261023_pickup_points.sql')
TEL = rd('supabase/migrations/20261025_pickup_tel.sql')
TP = rd('supabase/migrations/20261026_tool_points.sql')
OP = rd('supabase/migrations/20261027_order_points.sql')
STAFF = rd('supabase/migrations/20261028_staff.sql')
SO = rd('supabase/migrations/20261029_staff_orders.sql')
ST = rd('supabase/migrations/20261030_staff_tools.sql')
MANAGE = rd('supabase/migrations/20261102_admin_manage.sql')
PING = rd('supabase/migrations/20261103_role_ping.sql')
PUBL = rd('supabase/migrations/20261104_point_staff_publish.sql')
PICKF = rd('supabase/migrations/20261105_staff_pick_point.sql')
LIVE = rd('supabase/migrations/20261106_staff_live.sql')
SQL = rd('supabase/migrations/20261107_staff_publish_check.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
ADM = const('tools/admin-manage-stand.py', 'ADM')
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--      '))
PUB = 'create publication supabase_realtime;'

PSQL = str(pgserver.postgres_server.POSTGRES_BIN_PATH / 'psql')
fails = 0


def check(name, cond, detail=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + (' — ' + str(detail)[:900] if detail else ''))


def mk():
    return pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')


def q(srv, sql):
    r = subprocess.run('%s %s' % (PSQL, srv.get_uri()), input=('\\set ON_ERROR_STOP on\n' + sql + "\n;\nselect 'IVA-END-OK' as mark;\n").encode(),
                       shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode('utf-8', 'replace').strip()
    return ('OK' if 'IVA-END-OK' in out else 'ERR'), out.replace('IVA-END-OK', '')


def val(srv, sql):
    st, out = q(srv, '\\pset tuples_only on\n\\pset format unaligned\n' + sql)
    lines = [l for l in out.splitlines() if l and not l.startswith(('Tuples only', 'Output format', 'mark'))]
    return ';'.join(x for x in lines if x not in ('BEGIN', 'SET', 'COMMIT', 'INSERT 0 1', 'UPDATE 1', 'DELETE 1', 'UPDATE 0', 'DELETE 0'))


def claims(email):
    c = {'role': 'authenticated'}
    if email:
        c['email'] = email
        c['sub'] = str(uuid.uuid5(uuid.NAMESPACE_DNS, email))
    return json.dumps(c).replace("'", "''")


def as_(srv, email, sql, role='authenticated'):
    st, out = q(srv, "begin; set local role %s; set local request.jwt.claims = '%s'; %s; commit;" % (role, claims(email), sql))
    if out.rstrip().endswith('ROLLBACK') or 'ERROR' in out:
        st = 'ERR'
    return st, out


def uval(srv, email, sql, role='authenticated'):
    return val(srv, "begin; set local role %s; set local request.jwt.claims = '%s';\n%s;\ncommit;" % (role, claims(email), sql))


def code(srv, email):
    return val(srv, "select public.iva_staff_publish_check('%s') ->> 'code'" % email)


def why(srv, email):
    return uval(srv, email, "select public.iva_staff_publish_why() ->> 'code'")


def ping(srv, email):
    return val(srv, "select coalesce((select extract(epoch from changed_at)::text from public.iva_role_ping where email = '%s'), '')" % email)


def moved(srv, email, action):
    before = ping(srv, email)
    time.sleep(0.02)
    r = action()
    return ping(srv, email) != before, r


def save(srv, owner, sid, email, name, points, issue, catalog):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, false, false, false)" %
                ('null' if sid is None else "'%s'" % sid, email, name, arr, b(issue), b(catalog)))


def ins_point(name, addr, mode='approve'):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email, staff_publish) "
            "values ('%s', '%s', 'Новосибирск', 55.0, 82.9, 'prokat@mail.ru', '%s')") % (name, addr, mode)


def pid(srv, name):
    return val(srv, "select id from public.iva_pickup_points where name = '%s'" % name)


def add(srv, email, name, point=None):
    """Как штатная форма: POST tools с owner_email = вошедший, active = true; пункт — из поля «Пункт выдачи»."""
    return as_(srv, email, "insert into public.tools(name, active, cat, owner_email, price%s) values ('%s', true, 'drill', '%s', 400%s)"
               % (', point_id' if point else '', name, email, ", '%s'" % point if point else ''))


def tool(srv, name):
    return val(srv, ("select coalesce(t.owner_email, '') || '|' || coalesce(pp.name, '') || '|' || coalesce(t.active::text, '') || '|' "
                     "|| coalesce(t.review, '') from public.tools t left join public.iva_pickup_points pp on pp.id = t.point_id "
                     "where t.name = '%s'") % name)


# ═══════════════ Стенд А ═══════════════
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX + PUB)
check('исходная база как в бою (с хранилищем и Realtime)', st == 'OK', out[-600:])
check('SQL 10.77–10.115 и admins как в бою применяются',
      all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PING, PUBL, PICKF, LIVE)))

st, out = q(srv, SQL)
check('20261107_staff_publish_check.sql применяется без ошибок', st == 'OK', out[-900:])
check('таблица в конце: 9 проверок «✅», других триггеров и RESTRICTIVE нет', st == 'OK' and out.count('✅') == 11 and '❌' not in out
      and '⚠️' not in out, out[-1500:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 11, out[-600:])

as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск, улица Свердлова, 7', 'now'))
SKLAD, BERDSK = pid(srv, 'Склад'), pid(srv, 'Бердск')
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD, BERDSK], False, True)
IVAN = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [SKLAD], True, False)
check('сотрудники созданы', len(PETR) == 36 and len(IVAN) == 36, (PETR, IVAN))

check('причина: не вошёл', code(srv, '') == 'no_login')
check('причина: администратор', code(srv, 'admin@x.ru') == 'admin')
check('причина: не сотрудник', code(srv, 'client@x.ru') == 'not_staff')
check('причина: приглашение не принято', code(srv, 'petr@x.ru') == 'invited')
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN)
check('принял — всё в порядке', code(srv, 'petr@x.ru') == 'ok')
check('причина: нет права «Выкладывает инструмент»', code(srv, 'ivan@x.ru') == 'no_catalog')
check('почта в другом регистре и с пробелами — та же', code(srv, '  Petr@X.ru ') == 'ok')
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
check('причина: прокат владельца выключен', code(srv, 'petr@x.ru') == 'owner_off')
st, out = add(srv, 'petr@x.ru', 'При выключенном')
check('и база действительно не пускает (та же причина отказа)', st == 'ERR' and 'row-level security' in out, out[-300:])
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")
# без триггеров: штатно последний пункт не снять (сотрудник уходит целиком) — нужна «сломанная» строка
q(srv, "set session_replication_role = replica; create table public.sp_keep as select * from public.iva_staff_points where staff_id = '%s'; "
       "delete from public.iva_staff_points where staff_id = '%s'" % (PETR, PETR))
check('причина: нет пункта выдачи', code(srv, 'petr@x.ru') == 'no_points')
q(srv, "set session_replication_role = replica; insert into public.iva_staff_points select * from public.sp_keep; drop table public.sp_keep")
q(srv, 'drop policy iva_staff_tool_photos_ins on storage.objects')
check('причина: нет правила фото', code(srv, 'petr@x.ru') == 'no_photo_rule')
check('Пётр без правила фото не загружает', as_(srv, 'petr@x.ru', "insert into storage.objects(bucket_id, name) values ('tool-photos', 'tools/a.jpg')")[0] == 'ERR')
st, out = q(srv, SQL)
check('файл ещё раз — правило фото снова есть', st == 'OK' and code(srv, 'petr@x.ru') == 'ok'
      and as_(srv, 'petr@x.ru', "insert into storage.objects(bucket_id, name) values ('tool-photos', 'tools/a.jpg')")[0] == 'OK', out[-400:])
check('таблица: Пётр — может, Иван — без права', '✅ может выкладывать инструмент' in out and 'сотрудник petr@x.ru (active)' in out
      and 'сотрудник ivan@x.ru (active)' in out and '❌ Нет права «Выкладывает инструмент»' in out, out[-900:])

check('приложение: про себя — тот же ответ', why(srv, 'petr@x.ru') == 'ok' and why(srv, 'ivan@x.ru') == 'no_catalog')
check('гость причину не спрашивает', as_(srv, '', 'select public.iva_staff_publish_why()', role='anon')[0] == 'ERR')
check('чужую почту приложению не спросить', as_(srv, 'petr@x.ru', "select public.iva_staff_publish_check('ivan@x.ru')")[0] == 'ERR')
check('ответ — только код и фраза (без почт, имён, телефонов)',
      uval(srv, 'petr@x.ru', "select string_agg(k, ',' order by k) from jsonb_object_keys(public.iva_staff_publish_why()) k") == 'code,text')

# «на одобрение» → отметка владельцу
mv, (st, out) = moved(srv, 'admin@x.ru', lambda: add(srv, 'petr@x.ru', 'Дрель склад'))
check('инструмент сотрудника «на одобрение» — сохранён', st == 'OK' and tool(srv, 'Дрель склад') == 'admin@x.ru|Склад|false|pending', (tool(srv, 'Дрель склад'), out[-300:]))
check('и у владельца сдвинулась отметка (Realtime)', mv)
mv, (st, out) = moved(srv, 'admin@x.ru', lambda: add(srv, 'petr@x.ru', 'Пила бердск', BERDSK))
check('сразу видимый (режим «сразу») — без отметки', st == 'OK' and tool(srv, 'Пила бердск') == 'admin@x.ru|Бердск|true|' and not mv, (tool(srv, 'Пила бердск'), mv))
mv, (st, out) = moved(srv, 'admin@x.ru', lambda: add(srv, 'admin@x.ru', 'Своя дрель'))
check('свой инструмент владельца — без отметки', st == 'OK' and not mv)
TID = val(srv, "select id from public.tools where name = 'Дрель склад'")
mv, r = moved(srv, 'admin@x.ru', lambda: uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', true)" % TID))
check('одобрили — отметка (счётчик «На одобрении» обновится сам)', mv and tool(srv, 'Дрель склад').endswith('|true|'), (r, tool(srv, 'Дрель склад')))
mv, r = moved(srv, 'admin@x.ru', lambda: q(srv, "update public.tools set price = 500 where name = 'Дрель склад'"))
check('правка без смены «одобрения» — без отметки', not mv)

# push «o» (10.74) — заглушка функции; сбой push не мешает публикации
q(srv, "create table public.push_log(kind text, email text); "
       "create function public.iva_push_enqueue(p_kind text, p_email text) returns void language sql security definer "
       "set search_path to 'public' as $$ insert into public.push_log values (p_kind, p_email) $$")
st, out = add(srv, 'petr@x.ru', 'Перфоратор')
check('«на одобрение» — push «o» владельцу', st == 'OK' and val(srv, "select string_agg(kind || ':' || email, ',') from public.push_log") == 'o:admin@x.ru',
      val(srv, 'select * from public.push_log'))
add(srv, 'petr@x.ru', 'Шлифмашина', BERDSK)
check('сразу видимый — без push', val(srv, 'select count(*) from public.push_log') == '1')
q(srv, "create or replace function public.iva_push_enqueue(p_kind text, p_email text) returns void language plpgsql as $$ begin raise exception 'push упал'; end $$")
st, out = add(srv, 'petr@x.ru', 'Болгарка')
check('push упал — инструмент всё равно сохранён «на одобрение»', st == 'OK' and tool(srv, 'Болгарка') == 'admin@x.ru|Склад|false|pending', out[-300:])

# откат
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK' and 'DROP TRIGGER' in RB and 'DROP FUNCTION' in RB, out[-600:])
check('после отката функций и отметки нет',
      val(srv, "select (to_regprocedure('public.iva_staff_publish_why()') is null)::text "
               "|| (to_regprocedure('public.iva_staff_publish_check(text)') is null)::text "
               "|| (select count(*) from pg_trigger where tgname = 'trg_iva_tool_review_ping')::text") == 'truetrue0')
check('и сотрудник выкладывает, как в 10.115', add(srv, 'petr@x.ru', 'После отката')[0] == 'OK'
      and tool(srv, 'После отката') == 'admin@x.ru|Склад|false|pending')
st, out = q(srv, SQL)
check('повторное применение после отката — без ошибок', st == 'OK' and code(srv, 'petr@x.ru') == 'ok', out[-600:])
srv.cleanup()

# ═══════════════ Стенд Б: без 20261029 / 20261030 ═══════════════
srv = mk()
q(srv, BASELINE + EXTRA + PROD + PRODX + PUB)
ok = all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, ADM, MANAGE, PING))
st, out = q(srv, SQL)
check('без SQL 10.104–10.105 — применяется', ok and st == 'OK', out[-600:])
check('и честно показывает, чего нет', '❌' in out and 'SQL 20261030' in out and 'Хранилища или 20261030 нет' in out
      and 'отметка «на одобрение» не создана' in out, out[-900:])
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5'))
SK = pid(srv, 'Склад')
P2 = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SK], False, True)
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % P2)
check('причина: нет обновления базы', code(srv, 'petr@x.ru') == 'no_sql' and why(srv, 'petr@x.ru') == 'no_sql')
st, out = add(srv, 'petr@x.ru', 'Без SQL')
check('и база действительно не пускает', st == 'ERR' and 'row-level security' in out, out[-300:])
srv.cleanup()

# ═══════════════ Стенд В: без 20261028 ═══════════════
srv = mk()
q(srv, BASELINE + EXTRA + PROD + PRODX)
st, out = q(srv, SQL)
check('без SQL 10.103 — понятная остановка', st == 'ERR' and '20261028_staff.sql' in out, out[-400:])
check('и ничего не создано', val(srv, "select (to_regprocedure('public.iva_staff_publish_why()') is null)::text") == 'true')
srv.cleanup()

print('Итог: %d ошибок' % fails)
sys.exit(1 if fails else 0)

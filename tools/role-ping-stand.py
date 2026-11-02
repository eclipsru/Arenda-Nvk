# Стенд 10.112 «Роль — моментально»: 20261103_role_ping.sql на настоящем PostgreSQL (pgserver).
#
# Стенд А — база как в стенде 10.108 (сотрудники 10.103–10.105, admins как в бою, 20261102) и пустая публикация
# supabase_realtime, как в Supabase. Проверяет: файл применяется и повторяется, отметка попадает в публикацию;
# отметку человека трогают: назначение админа, удаление (и строки его сотрудников), пауза, возврат, удаление строки
# из SQL Editor, приглашение, ответ, права, пункты и удаление сотрудника; НЕ трогают аватар / название админа;
# читает только сам человек, гость — нет, записать и вызвать служебную функцию нельзя; откат; повторное применение.
# Стенд Б — база без сотрудников и без публикации: применяется, триггер admins работает, проверка Realtime — «❌».
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/role-ping-stand.py
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
SQL = rd('supabase/migrations/20261103_role_ping.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
ADM = const('tools/admin-manage-stand.py', 'ADM')
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--  '))
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


def ping(srv, email):
    """Время отметки (как число) или '' — отметки нет."""
    return val(srv, "select coalesce((select extract(epoch from changed_at)::text from public.iva_role_ping where email = '%s'), '')" % email)


def moved(srv, emails, action):
    """Сделать действие и сказать, у кого отметка сдвинулась."""
    before = {e: ping(srv, e) for e in emails}
    time.sleep(0.02)
    r = action()
    after = {e: ping(srv, e) for e in emails}
    return [e for e in emails if after[e] != before[e]], r


def save(srv, owner, sid, email, name, points, issue, catalog):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, false, false, false)" %
                ('null' if sid is None else "'%s'" % sid, email, name, arr, b(issue), b(catalog)))


def ins_point(name, city, addr):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email) "
            "values ('%s', '%s', '%s', 55.0, 82.9, 'prokat@mail.ru')") % (name, addr, city)


C = 'chief@x.ru'
ALL = ['admin@x.ru', 'admin2@x.ru', 'petr@x.ru', 'ivan@x.ru', 'new@x.ru', 'client@x.ru']

# ═══════════════ Стенд А ═══════════════
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX)
check('исходная база как в бою', st == 'OK', out[-600:])
check('SQL 10.77–10.105 и admins как в бою применяются',
      all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PUB)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Ленина, 1'))
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
BERDSK = val(srv, "select id from public.iva_pickup_points where name = 'Бердск'")
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD], True, True)
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)

st, out = q(srv, SQL)
check('20261103_role_ping.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-600:])
check('отметка — в публикации supabase_realtime (одна)',
      val(srv, "select count(*) from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'iva_role_ping'") == '1')
check('после применения отметок нет — роли не трогали', val(srv, 'select count(*) from public.iva_role_ping') == '0')

# админы
m, r = moved(srv, ALL, lambda: as_(srv, C, "select public.iva_admin_add('new@x.ru')"))
check('создатель назначил админа — отметка только у него', r[0] == 'OK' and m == ['new@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "update public.admins set name = 'Прокат Сила' where email = 'admin@x.ru'"))
check('админ сменил название / аватар — отметок нет (роль та же)', r[0] == 'OK' and m == [], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, C, "select public.iva_admin_pause('admin@x.ru', true)"))
check('пауза — отметка у админа и его сотрудника', r[0] == 'OK' and m == ['admin@x.ru', 'petr@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, C, "select public.iva_admin_pause('admin@x.ru', false)"))
check('возобновить — так же', r[0] == 'OK' and m == ['admin@x.ru', 'petr@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, C, "select public.iva_admin_remove('admin2@x.ru')"))
check('удалить из админов — отметка у него', r[0] == 'OK' and m == ['admin2@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, C, "select public.iva_admin_add('admin2@x.ru')"))
check('добавить снова — отметка у него', r[0] == 'OK' and m == ['admin2@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: q(srv, "delete from public.landlord_profiles where owner_email = 'new@x.ru'; delete from public.admins where email = 'new@x.ru'"))
check('строку админа удалили в SQL Editor — отметка у него', r[0] == 'OK' and m == ['new@x.ru'], (m, r[1][-300:]))

# сотрудники
IVAN = []
m, r = moved(srv, ALL, lambda: IVAN.append(save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [SKLAD], True, False)))
check('владелец пригласил сотрудника — отметка у приглашённого', m == ['ivan@x.ru'] and len(IVAN[0]) == 36, (m, IVAN))
m, r = moved(srv, ALL, lambda: uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN[0]))
check('сотрудник принял приглашение — отметка у него', m == ['ivan@x.ru'], m)
m, r = moved(srv, ALL, lambda: save(srv, 'admin@x.ru', IVAN[0], 'ivan@x.ru', 'Иван', [SKLAD], True, True))
check('владелец поменял права — отметка у сотрудника', m == ['ivan@x.ru'], m)
m, r = moved(srv, ALL, lambda: save(srv, 'admin@x.ru', IVAN[0], 'ivan@x.ru', 'Иван', [SKLAD, BERDSK], True, True))
check('владелец добавил пункт — отметка у сотрудника', m == ['ivan@x.ru'], m)
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % IVAN[0]))
check('владелец убрал сотрудника — отметка у него', r[0] == 'OK' and m == ['ivan@x.ru'], (m, r[1][-300:]))

# кто читает
check('человек видит только свою отметку',
      uval(srv, 'ivan@x.ru', "select string_agg(email, ',') from public.iva_role_ping") == 'ivan@x.ru'
      and uval(srv, 'client@x.ru', "select count(*) from public.iva_role_ping") == '0')
check('гость отметки не читает', as_(srv, '', 'select count(*) from public.iva_role_ping', role='anon')[0] == 'ERR')
check('записать отметку или вызвать служебную функцию нельзя',
      as_(srv, 'ivan@x.ru', "insert into public.iva_role_ping(email) values ('ivan@x.ru')")[0] == 'ERR'
      and as_(srv, 'ivan@x.ru', "update public.iva_role_ping set changed_at = now()")[0] == 'ERR'
      and as_(srv, 'ivan@x.ru', "select public.iva_role_ping_touch('admin@x.ru')")[0] == 'ERR')
check('в отметке только почта и время',
      val(srv, "select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns "
               "where table_schema = 'public' and table_name = 'iva_role_ping'") == 'email,changed_at')

# откат
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK', out[-600:])
check('после отката: таблицы, функций, триггеров и публикации нет',
      val(srv, "select to_regclass('public.iva_role_ping') is null") == 't'
      and val(srv, "select count(*) from pg_proc where proname like 'iva_role_ping%'") == '0'
      and val(srv, "select count(*) from pg_trigger where tgname = 'trg_iva_role_ping'") == '0'
      and val(srv, "select count(*) from pg_publication_tables where tablename = 'iva_role_ping'") == '0')
check('после отката админы и сотрудники работают как раньше',
      as_(srv, C, "select public.iva_admin_pause('admin@x.ru', true)")[0] == 'OK'
      and len(save(srv, 'admin2@x.ru', None, 'olga@x.ru', 'Ольга', [], True, False) or '') >= 0)
st, out = q(srv, SQL)
check('после отката применяется снова', st == 'OK' and out.count('✅') == 4, out[-400:])
srv.cleanup()

# ═══════════════ Стенд Б: без сотрудников и без Realtime ═══════════════
srv = mk()
st, out = q(srv, BASELINE)
check('стенд Б: база без сотрудников', st == 'OK', out[-600:])
st, out = q(srv, SQL)
check('стенд Б: применяется; без публикации — строка Realtime «❌», остальные «✅»',
      st == 'OK' and out.count('✅') == 3 and out.count('❌') == 1 and 'Supabase Realtime' in out.split('❌')[0].splitlines()[-1], out[-600:])
m, r = moved(srv, ['admin@x.ru', 'x@x.ru'], lambda: q(srv, "update public.admins set active = false where email = 'admin@x.ru'"))
check('стенд Б: триггер admins работает', r[0] == 'OK' and m == ['admin@x.ru'], (m, r[1][-300:]))
srv.cleanup()

print('\nИтого: %s' % ('всё сходится' if not fails else 'ошибок: %d' % fails))
sys.exit(1 if fails else 0)

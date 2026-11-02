# Стенд 10.115 «Все правки доступа — сразу; сотрудник в пункт / из пункта»: 20261106_staff_live.sql на PostgreSQL.
#
# Стенд А — база как в стенде 10.112 (+ 20261103 и 20261104). Проверяет: файл применяется и повторяется; отметку
# «роль поменялась» ставят ещё и: новый тариф / лимит (только этому админу), ответ сотрудника на приглашение (и его
# владельцу), правка пункта (сотрудникам этого пункта, не других); НЕ ставят: аватар / название админа, пустая правка
# пункта, права сотрудника (владельцу); прежние отметки 10.112 — на месте. iva_staff_link: в пункт, из пункта, из
# последнего — больше не сотрудник; чужой сотрудник / чужой пункт / сотрудник / клиент / гость — нельзя; откат.
# Стенд Б — база без 20261103: файл останавливается с понятной фразой и ничего не меняет.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-live-stand.py
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
SQL = rd('supabase/migrations/20261106_staff_live.sql')
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
ALL = ['admin@x.ru', 'admin2@x.ru', 'petr@x.ru', 'ivan@x.ru', 'olga@x.ru', 'client@x.ru']


def link(email, sid, point, on):
    return as_(srv, email, "select public.iva_staff_link('%s', '%s', %s)" % (sid, point, 'true' if on else 'false'))


def pts(sid):
    return val(srv, "select coalesce(string_agg(pp.name, ',' order by pp.name), '') from public.iva_staff_points sp "
                    "join public.iva_pickup_points pp on pp.id = sp.point_id where sp.staff_id = '%s'" % sid)


# ═══════════════ Стенд А ═══════════════
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX)
check('исходная база как в бою', st == 'OK', out[-600:])
check('SQL 10.77–10.114 и admins как в бою применяются',
      all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PUB, PING, PUBL)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Ленина, 1'))
as_(srv, 'admin@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Ленина, 2'))
as_(srv, 'admin2@x.ru', ins_point('Чужой', 'Обь', 'Обь, улица Ленина, 3'))
SKLAD, BERDSK, ISK, ALIEN = (val(srv, "select id from public.iva_pickup_points where name = '%s'" % n) for n in ('Склад', 'Бердск', 'Искитим', 'Чужой'))
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD], True, True)
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
IVAN = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [BERDSK], True, False)
uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN)
OLGA = save(srv, 'admin2@x.ru', None, 'olga@x.ru', 'Ольга', [ALIEN], True, False)
check('сотрудники: Пётр — Склад, Иван — Бердск, Ольга — у другого владельца', pts(PETR) == 'Склад' and pts(IVAN) == 'Бердск' and len(OLGA) == 36)

st, out = q(srv, SQL)
check('20261106_staff_live.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-600:])

# админы
m, r = moved(srv, ALL, lambda: q(srv, "update public.admins set fee_pct = 7, debt_limit = 5000 where email = 'admin@x.ru'"))
check('создатель сменил тариф и лимит — отметка только у админа (не у сотрудников)', r[0] == 'OK' and m == ['admin@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: q(srv, "update public.admins set debt_limit = 0 where email = 'admin@x.ru'"))
check('только лимит — тоже', r[0] == 'OK' and m == ['admin@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "update public.admins set name = 'Прокат Сила' where email = 'admin@x.ru'"))
check('название / аватар — отметок нет', r[0] == 'OK' and m == [], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, C, "select public.iva_admin_pause('admin@x.ru', true)"))
check('пауза — как в 10.112: админ и его сотрудники', r[0] == 'OK' and m == ['admin@x.ru', 'petr@x.ru', 'ivan@x.ru'], (m, r[1][-300:]))
as_(srv, C, "select public.iva_admin_pause('admin@x.ru', false)")

# сотрудники
m, r = moved(srv, ALL, lambda: save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [BERDSK], True, True))
check('владелец поменял права — отметка у сотрудника, не у владельца', m == ['ivan@x.ru'], m)
m, r = moved(srv, ALL, lambda: uval(srv, 'olga@x.ru', "select public.iva_staff_answer('%s', false)" % OLGA))
check('сотрудник отказался — отметка у него и у его владельца', m == ['admin2@x.ru', 'olga@x.ru'], m)
OLGA2 = save(srv, 'admin2@x.ru', None, 'olga@x.ru', 'Ольга', [ALIEN], True, False)
m, r = moved(srv, ALL, lambda: uval(srv, 'olga@x.ru', "select public.iva_staff_answer('%s', true)" % OLGA2))
check('пригласили снова и принял — так же', OLGA2 == OLGA and m == ['admin2@x.ru', 'olga@x.ru'], (m, OLGA2))

# пункты
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "update public.iva_pickup_points set name = 'Склад-1' where id = '%s'" % SKLAD))
check('владелец переименовал пункт — отметка у его сотрудника (Пётр), не у других', r[0] == 'OK' and m == ['petr@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "update public.iva_pickup_points set tel = '+79001234567' where id = '%s'" % BERDSK))
check('телефон пункта — отметка у Ивана', r[0] == 'OK' and m == ['ivan@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "update public.iva_pickup_points set staff_publish = 'now' where id = '%s'" % BERDSK))
check('«Инструменты сотрудников» у пункта — отметка у Ивана', r[0] == 'OK' and m == ['ivan@x.ru'], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "update public.iva_pickup_points set name = name where id = '%s'" % BERDSK))
check('пустая правка пункта — отметок нет', r[0] == 'OK' and m == [], (m, r[1][-300:]))
m, r = moved(srv, ALL, lambda: as_(srv, 'admin@x.ru', "update public.iva_pickup_points set name = 'Искитим-2' where id = '%s'" % ISK))
check('пункт без сотрудников — отметок нет', r[0] == 'OK' and m == [], (m, r[1][-300:]))

# в пункт / из пункта
m, r = moved(srv, ALL, lambda: link('admin@x.ru', IVAN, SKLAD, True))
check('Ивана — ещё и в Склад: «linked», отметка у него', r[0] == 'OK' and 'linked' in r[1] and pts(IVAN) == 'Бердск,Склад-1' and m == ['ivan@x.ru'], (m, r[1][-300:], pts(IVAN)))
st, out = link('admin@x.ru', IVAN, SKLAD, True)
check('повторно в тот же пункт — без ошибки и без дублей', st == 'OK' and pts(IVAN) == 'Бердск,Склад-1', out[-300:])
m, r = moved(srv, ALL, lambda: link('admin@x.ru', IVAN, BERDSK, False))
check('из Бердска: «unlinked», остался Склад, отметка у него', r[0] == 'OK' and 'unlinked' in r[1] and pts(IVAN) == 'Склад-1' and m == ['ivan@x.ru'], (m, r[1][-300:]))
check('права и состояние при этом не меняются',
      val(srv, "select status || can_issue::text || can_catalog::text from public.iva_staff where id = '%s'" % IVAN) == 'activetruetrue')
st, out = link('admin@x.ru', IVAN, ALIEN, True)
check('в чужой пункт — нельзя', st == 'ERR' and 'Пункт выдачи не найден у вашего проката' in out and pts(IVAN) == 'Склад-1', out[-300:])
st, out = link('admin@x.ru', OLGA, SKLAD, True)
check('чужого сотрудника — нельзя', st == 'ERR' and 'Сотрудник не найден' in out, out[-300:])
st, out = link('petr@x.ru', IVAN, BERDSK, True)
check('сотрудник не прикрепляет', st == 'ERR' and 'Сотрудников добавляет владелец проката' in out and pts(IVAN) == 'Склад-1', out[-300:])
check('клиент — нельзя', link('client@x.ru', IVAN, SKLAD, False)[0] == 'ERR' and pts(IVAN) == 'Склад-1')
check('гость функцию не вызывает', as_(srv, '', "select public.iva_staff_link('%s', '%s', true)" % (IVAN, BERDSK), role='anon')[0] == 'ERR')
m, r = moved(srv, ALL, lambda: link('admin@x.ru', IVAN, SKLAD, False))
check('из последнего пункта: «removed», больше не сотрудник, отметка у него', r[0] == 'OK' and 'removed' in r[1]
      and val(srv, "select count(*) from public.iva_staff where id = '%s'" % IVAN) == '0' and m == ['ivan@x.ru'], (m, r[1][-300:]))
st, out = link('admin@x.ru', IVAN, SKLAD, True)
check('убранного — уже нельзя', st == 'ERR' and 'Сотрудник не найден' in out, out[-300:])
check('Пётр не задет', pts(PETR) == 'Склад-1')

# откат
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK' and 'DROP FUNCTION IF EXISTS public.iva_staff_link' in RB, out[-600:])
st, out = q(srv, PING)
check('затем 20261103 ещё раз — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
check('после отката: функции нет, триггера пункта нет, тариф снова без отметки',
      val(srv, "select (to_regprocedure('public.iva_staff_link(uuid,uuid,boolean)') is null)::text") == 'true'
      and val(srv, "select count(*) from pg_trigger where tgrelid = 'public.iva_pickup_points'::regclass and tgname = 'trg_iva_role_ping'") == '0'
      and moved(srv, ALL, lambda: q(srv, "update public.admins set fee_pct = 9 where email = 'admin@x.ru'"))[0] == [])
st, out = q(srv, SQL)
check('повторное применение после отката — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
srv.cleanup()

# ═══════════════ Стенд Б: без 20261103 ═══════════════
srv = mk()
q(srv, BASELINE + EXTRA + PROD + PRODX)
ok = all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF))
st, out = q(srv, SQL)
check('без SQL 10.112 — понятная остановка', ok and st == 'ERR' and '20261103_role_ping.sql' in out, out[-400:])
check('и ничего не поменялось', val(srv, "select (to_regprocedure('public.iva_staff_link(uuid,uuid,boolean)') is null)::text") == 'true')
srv.cleanup()

print('Итог: %d ошибок' % fails)
sys.exit(1 if fails else 0)

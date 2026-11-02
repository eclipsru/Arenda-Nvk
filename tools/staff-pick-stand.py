# Стенд 10.114 «Сотрудник на двух пунктах — пункт из формы»: 20261105_staff_pick_point.sql на настоящем PostgreSQL.
#
# Стенд А — база как в стенде 10.113 (+ 20261104). Проверяет: файл применяется и повторяется; выбрать можно только
# свой пункт с правом «Выкладывает инструмент»; таблицу выбора напрямую не читает и не пишет никто; новый инструмент
# сотрудника без пункта встаёт в выбранный пункт и получает ЕГО режим (сразу / на одобрение); выбор старше часа или
# пункт, с которого сотрудника сняли, не действует (первый пункт, как раньше); явный пункт важнее выбора; откат.
# Стенд Б — база без 20261104: файл останавливается с понятной фразой и ничего не меняет.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-pick-stand.py
import ast
import json
import os
import subprocess
import sys
import tempfile
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
PUB = rd('supabase/migrations/20261104_point_staff_publish.sql')
SQL = rd('supabase/migrations/20261105_staff_pick_point.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
ADM = const('tools/admin-manage-stand.py', 'ADM')
# откат: строки-команды шапки (отступ 6 пробелов), затем — снова весь 20261104
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--      '))

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


def save(srv, owner, email, name, points):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    return uval(srv, owner, "select public.iva_staff_save(null, '%s', '%s', '', %s, false, true, false, false, false)" % (email, name, arr))


def ins_point(name, addr, email="'prokat@mail.ru'"):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email) "
            "values ('%s', '%s', 'Новосибирск', 55.0, 82.9, %s)") % (name, addr, email)


def pid(srv, name):
    return val(srv, "select id from public.iva_pickup_points where name = '%s'" % name)


def mode(srv, name):
    return val(srv, "select staff_publish from public.iva_pickup_points where name = '%s'" % name)


def add(srv, email, name, point=None):
    """Как штатная форма: POST tools с owner_email = вошедший, active = true; пункт — из поля «Пункт выдачи»."""
    return as_(srv, email, "insert into public.tools(name, active, cat, owner_email, price%s) values ('%s', true, 'drill', '%s', 400%s)"
               % (', point_id' if point else '', name, email, ", '%s'" % point if point else ''))


def tool(srv, name):
    """«пункт|показан|одобрение» инструмента."""
    return val(srv, ("select coalesce(pp.name, '') || '|' || coalesce(t.active::text, '') || '|' || coalesce(t.review, '') "
                     "from public.tools t left join public.iva_pickup_points pp on pp.id = t.point_id where t.name = '%s'") % name)


def pick(srv, email, point):
    return uval(srv, email, "select public.iva_staff_pick_point('%s')" % point)


# ═══════════════ Стенд А ═══════════════
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX)
check('исходная база как в бою', st == 'OK', out[-600:])
check('SQL 10.77–10.113 и admins как в бою применяются',
      all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PUB)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', "insert into public.iva_pickup_points(name, address, city, staff_publish) values ('Бердск', 'Бердск, улица Свердлова, 7', 'Бердск', 'now')")
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим, улица Пушкина, 3'))
SKLAD, BERDSK, ISK = pid(srv, 'Склад'), pid(srv, 'Бердск'), pid(srv, 'Искитим')
check('режимы пунктов: Склад — на одобрение, Бердск — сразу', mode(srv, 'Склад') == 'approve' and mode(srv, 'Бердск') == 'now')
PETR = save(srv, 'admin@x.ru', 'petr@x.ru', 'Пётр', [SKLAD, BERDSK])
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
st, out = add(srv, 'petr@x.ru', 'До SQL')
check('до SQL 10.114: без пункта — первый (Склад) и его режим', st == 'OK' and tool(srv, 'До SQL') == 'Склад|false|pending', (tool(srv, 'До SQL'), out[-300:]))

st, out = q(srv, SQL)
check('20261105_staff_pick_point.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])

check('Пётр выбрал Бердск', pick(srv, 'petr@x.ru', BERDSK) == 't')
st, out = add(srv, 'petr@x.ru', 'Пила бердск')
check('без пункта — в выбранный Бердск и сразу виден (режим Бердска)', st == 'OK' and tool(srv, 'Пила бердск') == 'Бердск|true|', (tool(srv, 'Пила бердск'), out[-300:]))
check('Пётр выбрал Склад', pick(srv, 'petr@x.ru', SKLAD) == 't')
st, out = add(srv, 'petr@x.ru', 'Дрель склад')
check('без пункта — в выбранный Склад и «на одобрении»', st == 'OK' and tool(srv, 'Дрель склад') == 'Склад|false|pending', (tool(srv, 'Дрель склад'), out[-300:]))
st, out = add(srv, 'petr@x.ru', 'Явный бердск', BERDSK)
check('явный пункт важнее выбора', st == 'OK' and tool(srv, 'Явный бердск') == 'Бердск|true|', tool(srv, 'Явный бердск'))
check('чужой пункт выбрать нельзя (и выбор не меняется)', pick(srv, 'petr@x.ru', ISK) == 'f'
      and val(srv, "select point_id from public.iva_staff_pick") == SKLAD)
check('клиент (не сотрудник) выбрать не может', pick(srv, 'client@x.ru', SKLAD) == 'f')
check('владелец — не сотрудник: выбор ему не нужен', pick(srv, 'admin@x.ru', SKLAD) == 'f')
check('гость функцию не вызывает', as_(srv, '', "select public.iva_staff_pick_point('%s')" % SKLAD, role='anon')[0] == 'ERR')
check('таблицу выбора напрямую не читают', as_(srv, 'petr@x.ru', 'select * from public.iva_staff_pick')[0] == 'ERR')
check('и не пишут', as_(srv, 'petr@x.ru', "insert into public.iva_staff_pick(staff_id, point_id) values ('%s', '%s')" % (PETR, BERDSK))[0] == 'ERR')
pick(srv, 'petr@x.ru', BERDSK)
q(srv, "update public.iva_staff_pick set picked_at = now() - interval '2 hours'")
st, out = add(srv, 'petr@x.ru', 'Старый выбор')
check('выбор старше часа не действует — первый пункт, как раньше', st == 'OK' and tool(srv, 'Старый выбор') == 'Склад|false|pending', tool(srv, 'Старый выбор'))
pick(srv, 'petr@x.ru', BERDSK)
uval(srv, 'admin@x.ru', "select public.iva_staff_save('%s', 'petr@x.ru', 'Пётр', '', array['%s']::uuid[], false, true, false, false, false)" % (PETR, SKLAD))
st, out = add(srv, 'petr@x.ru', 'Сняли с Бердска')
check('сняли с выбранного пункта — выбор не действует', st == 'OK' and tool(srv, 'Сняли с Бердска') == 'Склад|false|pending', (tool(srv, 'Сняли с Бердска'), out[-300:]))
check('и выбрать его снова нельзя', pick(srv, 'petr@x.ru', BERDSK) == 'f')
st, out = add(srv, 'admin@x.ru', 'Своя дрель')
check('инструмент владельца — как раньше (без одобрения)', st == 'OK' and tool(srv, 'Своя дрель').endswith('|true|'), tool(srv, 'Своя дрель'))

# откат
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK' and 'DROP TABLE' in RB and 'DROP FUNCTION' in RB, out[-600:])
st, out = q(srv, PUB)
check('затем 20261104 ещё раз — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
check('после отката таблицы и функции нет', val(srv, "select (to_regclass('public.iva_staff_pick') is null)::text || (to_regprocedure('public.iva_staff_pick_point(uuid)') is null)::text") == 'truetrue')
st, out = q(srv, SQL)
check('повторное применение после отката — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
srv.cleanup()

# ═══════════════ Стенд Б: без 20261104 ═══════════════
srv = mk()
q(srv, BASELINE + EXTRA + PROD + PRODX)
ok = all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST))
st, out = q(srv, SQL)
check('без SQL 10.113 — понятная остановка', ok and st == 'ERR' and '20261104_point_staff_publish.sql' in out, out[-400:])
check('и ничего не поменялось', val(srv, "select (to_regclass('public.iva_staff_pick') is null)::text") == 'true')
srv.cleanup()

print('Итог: %d ошибок' % fails)
sys.exit(1 if fails else 0)

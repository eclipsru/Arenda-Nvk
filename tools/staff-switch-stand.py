# Стенд «Сменить / Владелец» в «Работе» (сайт 06.11.2026): 20261126_staff_switch.sql на настоящем PostgreSQL (pgserver).
#
# База как в стенде чата пункта (BASELINE + пункты + 20261026 + 20261028).
# Проверяет iva_switch_info(): владелец проката — роль «owner» и ключ своего проката; сотрудник, принявший приглашение, —
# роль «staff», ключ проката, название и почта владельца; сотрудник другого пункта того же проката — тот же ключ; другого
# проката — другой; не принявший, убранный, клиент — пусто; выключенный прокат — пусто и у владельца, и у сотрудников;
# чужие почты в ответ не попадают; гостю — нельзя;
# повторный запуск; откат.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-switch-stand.py
import ast
import hashlib
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


PICK = rd('supabase/migrations/20261023_pickup_points.sql')
TEL = rd('supabase/migrations/20261025_pickup_tel.sql')
TP = rd('supabase/migrations/20261026_tool_points.sql')
STAFF = rd('supabase/migrations/20261028_staff.sql')
SQL = rd('supabase/migrations/20261126_staff_switch.sql')
RB = rd('supabase/migrations/20261126_staff_switch_rollback.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')

PSQL = str(pgserver.postgres_server.POSTGRES_BIN_PATH / 'psql')
fails = 0


def check(name, cond, detail=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + (' — ' + str(detail)[:900] if detail else ''))


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


def ins_point(name, city, addr):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email) "
            "values ('%s', '%s', '%s', 55.0, 82.9, 'prokat@mail.ru')") % (name, addr, city)


def save(srv, owner, email, name, points):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    return uval(srv, owner, "select public.iva_staff_save(null, '%s', '%s', '', %s, true, false, false, true, false)" % (email, name, arr))


def accept(srv, email, sid):
    return uval(srv, email, "select public.iva_staff_answer('%s', true)" % sid)


def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def info(srv, email):
    raw = uval(srv, email, 'select public.iva_switch_info()::text')
    try:
        return json.loads(raw)
    except Exception:
        return {'raw': raw}


def short(j):
    return sorted((p['role'], p['key'], p['owner']) for p in j.get('prokats', []))


srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
st, out = q(srv, BASELINE + EXTRA + PROD)
check('исходная база как в бою', st == 'OK', out[-600:])
check('SQL пунктов выдачи и сотрудников (10.95, 10.99, 10.101, 10.103) применяются', all(q(srv, s)[0] == 'OK' for s in (PICK, TEL, TP, STAFF)))
q(srv, "insert into public.admins(email, role, active) select 'admin2@x.ru', 'admin', true where not exists (select 1 from public.admins where email = 'admin2@x.ru')")
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Пушкина, 3'))
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
BERDSK = val(srv, "select id from public.iva_pickup_points where name = 'Бердск'")
ISK = val(srv, "select id from public.iva_pickup_points where name = 'Искитим'")
check('пункты созданы у двух прокатов', SKLAD and BERDSK and ISK, [SKLAD, BERDSK, ISK])

st, out = q(srv, SQL)
check('20261126_staff_switch.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 3 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 3, out[-600:])

# сотрудники: Иван — Склад, Ольга — Бердск (тот же прокат, другой пункт), Маша — не приняла, Гоша — другой прокат.
# Сотрудником двух прокатов сразу быть нельзя (SQL 10.103) — Даша остаётся в первом.
IVAN = save(srv, 'admin@x.ru', 'ivan@x.ru', 'Иван', [SKLAD])
OLGA = save(srv, 'admin@x.ru', 'olga@x.ru', 'Ольга', [BERDSK])
MASHA = save(srv, 'admin@x.ru', 'masha@x.ru', 'Маша', [SKLAD])
GOSHA = save(srv, 'admin2@x.ru', 'gosha@x.ru', 'Гоша', [ISK])
DASHA1 = save(srv, 'admin@x.ru', 'dasha@x.ru', 'Даша', [SKLAD])
for em, sid in (('ivan@x.ru', IVAN), ('olga@x.ru', OLGA), ('gosha@x.ru', GOSHA), ('dasha@x.ru', DASHA1)):
    accept(srv, em, sid)
st, out = as_(srv, 'admin2@x.ru', "select public.iva_staff_save(null, 'dasha@x.ru', 'Даша', '', array['%s']::uuid[], true, false, false, true, false)" % ISK)
check('сотрудника другого проката второй прокат пригласить не может (SQL 10.103)', st == 'ERR' and 'другого проката' in out, out[-300:])
check('сотрудники приглашены и приняли', val(srv, "select count(*) from public.iva_staff where status = 'active'") == '4',
      val(srv, "select string_agg(email || ':' || status, ' ') from public.iva_staff"))

K1, K2 = md5('admin@x.ru'), md5('admin2@x.ru')
a = info(srv, 'admin@x.ru')
check('владелец: роль «owner», ключ своего проката', short(a) == [('owner', K1, 'admin@x.ru')], a)
check('владелец: в ответе своя почта и название проката', a.get('me') == 'admin@x.ru' and all(p.get('company') for p in a.get('prokats', [])), a)
iv = info(srv, 'ivan@x.ru')
check('сотрудник: роль «staff», ключ проката и почта владельца', short(iv) == [('staff', K1, 'admin@x.ru')], iv)
ol = info(srv, 'olga@x.ru')
check('сотрудник другого пункта того же проката — тот же ключ (может войти через «Сменить»)', short(ol) == [('staff', K1, 'admin@x.ru')], ol)
go = info(srv, 'gosha@x.ru')
check('сотрудник другого проката — другой ключ («Вы не работаете в этом прокате»)', short(go) == [('staff', K2, 'admin2@x.ru')], go)
check('не принявший приглашение — пусто', info(srv, 'masha@x.ru').get('prokats') == [], info(srv, 'masha@x.ru'))
check('клиент — пусто', info(srv, 'client@x.ru') == {'me': 'client@x.ru', 'prokats': []}, info(srv, 'client@x.ru'))
raw = uval(srv, 'ivan@x.ru', 'select public.iva_switch_info()::text')
check('в ответе сотруднику — только его почта и почта его владельца (без других сотрудников и прокатов)',
      all(x not in raw for x in ('olga@', 'masha@', 'gosha@', 'dasha@', 'admin2@', 'prokat@')), raw)
st, out = as_(srv, '', 'select public.iva_switch_info()', role='anon')
check('гостю — нельзя', st == 'ERR' and 'permission denied' in out, out[-300:])
check('без почты в ключе — пусто', info(srv, '') == {'me': '', 'prokats': []}, info(srv, ''))

# прокат выключен — пусто у владельца и у его сотрудников; другой прокат Даши остаётся
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
check('выключенный прокат: владелец — пусто', info(srv, 'admin@x.ru').get('prokats') == [], info(srv, 'admin@x.ru'))
check('выключенный прокат: сотрудник — пусто', info(srv, 'ivan@x.ru').get('prokats') == [], info(srv, 'ivan@x.ru'))
check('выключенный прокат: другой прокат не задет', short(info(srv, 'gosha@x.ru')) == [('staff', K2, 'admin2@x.ru')], info(srv, 'gosha@x.ru'))
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")
# сотрудника убрали — сразу пусто
st, out = as_(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % IVAN)
check('владелец убрал сотрудника («Удалить», SQL 10.103)', st == 'OK', out[-300:])
check('убранный сотрудник — пусто', info(srv, 'ivan@x.ru').get('prokats') == [], info(srv, 'ivan@x.ru'))

# откат
st, out = q(srv, RB)
check('откат применяется, функция удалена', st == 'OK' and '✅' in out and '❌' not in out and
      val(srv, "select to_regprocedure('public.iva_switch_info()') is null") == 't', out[-400:])
check('после отката таблицы на месте', val(srv, 'select count(*) from public.iva_staff') != '' and val(srv, 'select count(*) from public.admins') != '')
st, out = q(srv, SQL)
check('после отката SQL снова применяется', st == 'OK' and out.count('✅') == 3, out[-400:])

print('Итого: ' + ('всё ✔' if not fails else '%d ✘' % fails))
sys.exit(1 if fails else 0)

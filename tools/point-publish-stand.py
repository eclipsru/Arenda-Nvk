# Стенд 10.113 «Инструменты сотрудников — у каждого пункта»: 20261104_point_staff_publish.sql на настоящем PostgreSQL.
#
# Стенд А — база как в стенде 10.108 (сотрудники 10.103–10.105, admins как в бою, 20261102). Проверяет: файл
# применяется и повторяется; у пунктов — прежний общий режим владельца (повтор не затирает выбор); пункт без email
# добавляется, неверный email по-прежнему не принимается; выключатель меняет только сам владелец; новый инструмент
# сотрудника — по режиму СВОЕГО пункта (сразу / на одобрение), без пункта — по первому пункту; правки — как раньше;
# инструмент владельца — без одобрения; откат из шапки возвращает общий режим.
# Стенд Б — база без 20261030: файл останавливается с понятной фразой и ничего не меняет.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/point-publish-stand.py
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
SQL = rd('supabase/migrations/20261104_point_staff_publish.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
ADM = const('tools/admin-manage-stand.py', 'ADM')
# откат: строки-команды шапки (отступ 6 пробелов), затем — снова весь 20261030
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


# ═══════════════ Стенд А ═══════════════
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX)
check('исходная база как в бою', st == 'OK', out[-600:])
check('SQL 10.77–10.105 и admins как в бою применяются',
      all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим, улица Пушкина, 3'))
SKLAD, BERDSK, ISK = pid(srv, 'Склад'), pid(srv, 'Бердск'), pid(srv, 'Искитим')
check('прежний общий режим: у admin — «сразу», у admin2 — по умолчанию',
      uval(srv, 'admin@x.ru', "select public.iva_staff_publish_set('now')") is not None
      and uval(srv, 'admin@x.ru', 'select public.iva_staff_publish()') == 'now'
      and uval(srv, 'admin2@x.ru', 'select public.iva_staff_publish()') == 'approve')
st, out = as_(srv, 'admin@x.ru', ins_point('Без почты', 'Новосибирск, улица Мира, 1', 'null'))
check('до SQL 10.113 пункт без email не добавляется (как было)', st == 'ERR')

st, out = q(srv, SQL)
check('20261104_point_staff_publish.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
check('у пунктов — прежний общий режим владельца', mode(srv, 'Склад') == 'now' and mode(srv, 'Бердск') == 'now' and mode(srv, 'Искитим') == 'approve')
st, out = as_(srv, 'admin@x.ru', "update public.iva_pickup_points set staff_publish = 'approve' where name = 'Склад'")
check('владелец выключил «сразу» у Склада', st == 'OK' and mode(srv, 'Склад') == 'approve', out[-300:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок и выбор у пунктов не затирает',
      st == 'OK' and out.count('✅') == 4 and mode(srv, 'Склад') == 'approve' and mode(srv, 'Бердск') == 'now', out[-600:])

# email и выключатель
st, out = as_(srv, 'admin@x.ru', ins_point('Без почты', 'Новосибирск, улица Мира, 1', 'null'))
check('пункт без email добавляется (как новая форма), режим — «после одобрения»',
      st == 'OK' and val(srv, "select coalesce(email, 'NULL') || '|' || staff_publish from public.iva_pickup_points where name = 'Без почты'") == 'NULL|approve', out[-300:])
st, out = as_(srv, 'admin@x.ru', "insert into public.iva_pickup_points(name, address, city, staff_publish) values ('Сразу', 'Новосибирск, улица Мира, 3', 'Новосибирск', 'now')")
check('новый пункт можно сразу добавить с «сразу»', st == 'OK' and mode(srv, 'Сразу') == 'now', out[-300:])
check('неверный email по-прежнему не принимается', as_(srv, 'admin@x.ru', ins_point('Плохой', 'Новосибирск, улица Мира, 2', "'нет-почты'"))[0] == 'ERR')
check('неизвестный режим не принимается', as_(srv, 'admin@x.ru', "update public.iva_pickup_points set staff_publish = 'later' where name = 'Склад'")[0] == 'ERR')
as_(srv, 'admin2@x.ru', "update public.iva_pickup_points set staff_publish = 'now' where name = 'Склад'")
check('чужой админ выключатель не меняет', mode(srv, 'Склад') == 'approve')
check('гость не меняет', as_(srv, '', "update public.iva_pickup_points set staff_publish = 'now'", role='anon')[0] == 'ERR'
      and mode(srv, 'Склад') == 'approve')

# сотрудники: Пётр — «Каталог» на Складе (одобрение) и в Бердске (сразу)
PETR = save(srv, 'admin@x.ru', 'petr@x.ru', 'Пётр', [SKLAD, BERDSK])
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
st, out = add(srv, 'petr@x.ru', 'Дрель склад', SKLAD)
check('Пётр выложил в Склад — скрыт и «на одобрении»', st == 'OK' and tool(srv, 'Дрель склад') == 'Склад|false|pending', (tool(srv, 'Дрель склад'), out[-300:]))
st, out = add(srv, 'petr@x.ru', 'Пила бердск', BERDSK)
check('Пётр выложил в Бердск — сразу виден клиентам', st == 'OK' and tool(srv, 'Пила бердск') == 'Бердск|true|', (tool(srv, 'Пила бердск'), out[-300:]))
check('клиент видит только бердский', uval(srv, 'client@x.ru', "select string_agg(name, ',') from public.tools where name in ('Дрель склад', 'Пила бердск')") == 'Пила бердск')
st, out = add(srv, 'petr@x.ru', 'Без пункта')
check('без пункта — первый свой (Склад) и его режим', st == 'OK' and tool(srv, 'Без пункта') == 'Склад|false|pending', (tool(srv, 'Без пункта'), out[-300:]))
as_(srv, 'admin@x.ru', "update public.iva_pickup_points set staff_publish = 'now' where name = 'Склад'")
st, out = add(srv, 'petr@x.ru', 'Шлифмашина', SKLAD)
check('владелец включил «сразу» у Склада — новый сразу виден', st == 'OK' and tool(srv, 'Шлифмашина') == 'Склад|true|', tool(srv, 'Шлифмашина'))
check('ждущий одобрения от смены режима не публикуется', tool(srv, 'Дрель склад') == 'Склад|false|pending')
as_(srv, 'petr@x.ru', "update public.tools set price = 450 where name = 'Пила бердск'")
check('правка опубликованного — сразу, как раньше', tool(srv, 'Пила бердск') == 'Бердск|true|'
      and val(srv, "select price from public.tools where name = 'Пила бердск'") == '450')
as_(srv, 'admin@x.ru', "update public.iva_pickup_points set staff_publish = 'approve' where name = 'Бердск'")
st, out = add(srv, 'admin@x.ru', 'Своя дрель', BERDSK)
check('инструмент самого владельца — без одобрения в любом режиме', st == 'OK' and tool(srv, 'Своя дрель') == 'Бердск|true|', tool(srv, 'Своя дрель'))
check('«Опубликовать» ждущего работает как раньше',
      uval(srv, 'admin@x.ru', "select public.iva_tool_review((select id from public.tools where name = 'Дрель склад'), true)") == 'published'
      and tool(srv, 'Дрель склад') == 'Склад|true|')

# откат
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK' and 'DROP COLUMN' in RB and 'reload' in RB, out[-600:])
st, out = q(srv, ST)
check('затем 20261030 ещё раз — без ошибок', st == 'OK' and out.count('✅') == 5, out[-600:])
check('после отката колонки нет', val(srv, "select count(*) from information_schema.columns where table_name = 'iva_pickup_points' and column_name = 'staff_publish'") == '0')
st, out = add(srv, 'petr@x.ru', 'После отката', BERDSK)
check('после отката — снова общий режим владельца («сразу»)', st == 'OK' and tool(srv, 'После отката') == 'Бердск|true|', (tool(srv, 'После отката'), out[-300:]))
st, out = q(srv, SQL)
check('повторное применение после отката — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
srv.cleanup()

# ═══════════════ Стенд Б: без 20261030 ═══════════════
srv = mk()
q(srv, BASELINE + EXTRA + PROD + PRODX)
ok = all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP))
st, out = q(srv, SQL)
check('без SQL 10.105 — понятная остановка', ok and st == 'ERR' and '20261030_staff_tools.sql' in out, out[-400:])
check('и ничего не поменялось', val(srv, "select count(*) from information_schema.columns where table_name = 'iva_pickup_points' and column_name = 'staff_publish'") == '0'
      and val(srv, "select is_nullable from information_schema.columns where table_name = 'iva_pickup_points' and column_name = 'email'") == 'NO')
srv.cleanup()

print('Итог: %d ошибок' % fails)
sys.exit(1 if fails else 0)

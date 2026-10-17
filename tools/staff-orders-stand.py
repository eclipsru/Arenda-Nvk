# Стенд этапа 3б «Сотрудники и пункты выдачи» (10.104): 20261029_staff_orders.sql на настоящем PostgreSQL (pgserver).
#
# База как в стенде 10.102 (заявки ТОЧНО как в рабочей базе по снимку 17.10: триггеры комиссии, журнал статусов,
# правила доступа), затем 20261014_rent_end_mode, пункты (20261023 + 20261025), 20261026, 20261027, 20261028 и 20261029.
# Проверяет: сотрудник видит заявки только своих пунктов (и только принявший приглашение, с правом «Выдаёт» или
# «Уведомления», при включённом прокате); в ответе нет денег, почты клиента и паспорта; клиент, гость, владелец,
# сотрудник другого проката и сотрудник «Каталог» — не видят; «Выдал» / «Вернули» — только с правом «Выдаёт», только в
# своём пункте, только new → rented → closed; кто нажал — в журнале статусов; комиссия и время выдачи — как у владельца;
# свою заявку — только с «Может выдавать самому себе»; убрали сотрудника / пункт / выключили прокат — доступ пропадает
# сразу; архив, закрытые старше 7 дней и части без пункта не видны; напрямую таблицу сотрудник не меняет; откат из
# шапки; повторное применение; без SQL 10.102 / 10.103 — понятная ошибка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-orders-stand.py
import ast
import json
import os
import subprocess
import sys
import tempfile

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
SQL = rd('supabase/migrations/20261029_staff_orders.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
# откат — строки «--      …» блока «ОТКАТ» в шапке файла
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--  '))

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
    return json.dumps(c).replace("'", "''")


def as_(srv, email, sql, role='authenticated'):
    st, out = q(srv, "begin; set local role %s; set local request.jwt.claims = '%s'; %s; commit;" % (role, claims(email), sql))
    if out.rstrip().endswith('ROLLBACK') or 'ERROR' in out:
        st = 'ERR'
    return st, out


def uval(srv, email, sql, role='authenticated'):
    return val(srv, "begin; set local role %s; set local request.jwt.claims = '%s';\n%s;\ncommit;" % (role, claims(email), sql))


def err_has(r, text):
    return r[0] == 'ERR' and text in r[1]


def ins_point(name, city, addr):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email) "
            "values ('%s', '%s', '%s', 55.0, 82.9, 'prokat@mail.ru')") % (name, addr, city)


def tid(srv, name, owner):
    return val(srv, "select id from public.tools where name = '%s' and owner_email = '%s'" % (name, owner))


def submit(srv, tools_text, its, email='client@x.ru', days=5):
    p = json.dumps([{'id': i, 'days': d} for i, d in its]).replace("'", "''")
    return uval(srv, '', "select public.iva_submit_order('Иван Клиентов', '+79990000000', '%s', 'Самовывоз', '', 'Позвоните заранее', '%s', %d, '%s'::jsonb)"
                % (tools_text, email, days, p), role='anon')


def part_of(srv, oid, point):
    return val(srv, ("select p.id from public.order_parts p join public.iva_pickup_points pp on pp.id = p.point_id "
                     "where p.order_id = %s and pp.name = '%s'") % (oid, point))


def save(srv, owner, sid, email, name, points, issue, catalog, chat, notify, self_):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, %s, %s, %s)" %
                ('null' if sid is None else "'%s'" % sid, email, name, arr, b(issue), b(catalog), b(chat), b(notify), b(self_)))


def accept(srv, email, sid):
    return uval(srv, email, "select public.iva_staff_answer('%s', true)" % sid)


def seen(srv, email):
    """Что видит сотрудник: «№части|пункт|состояние|mine|can_act|can_notify», по порядку."""
    return uval(srv, email, "select coalesce(string_agg(part_id || '|' || point_name || '|' || status || '|' || mine || '|' || can_act || '|' || can_notify, ' ; ' "
                            "order by part_id::bigint), '') from public.iva_staff_parts()")


def mark(srv, email, pid, st):
    return as_(srv, email, "select public.iva_staff_set_status(%s, '%s')" % (pid, st))


# ── 1. база, применение, повтор ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD)
check('исходная база как в бою (заявки по снимку 17.10)', st == 'OK', out[-600:])
check('SQL 10.77, 10.95, 10.99, 10.101 применяются', all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Пушкина, 3'))
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
BERDSK = val(srv, "select id from public.iva_pickup_points where name = 'Бердск'")
ISK = val(srv, "select id from public.iva_pickup_points where name = 'Искитим'")
q(srv, ("insert into public.tools(name, owner_email, price, point_id) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}'), ('Пила', 'admin@x.ru', 500, '{b}'), ('Дрель', 'admin2@x.ru', 100, '{i}')").format(s=SKLAD, b=BERDSK, i=ISK))
DRILL, SAW, D2 = tid(srv, 'Дрель Makita', 'admin@x.ru'), tid(srv, 'Пила', 'admin@x.ru'), tid(srv, 'Дрель', 'admin2@x.ru')
st, out = q(srv, OP)
check('SQL 10.102 применяется', st == 'OK', out[-600:])
st, out = q(srv, STAFF)
check('SQL 10.103 применяется', st == 'OK', out[-600:])
st, out = q(srv, SQL)
check('20261029_staff_orders.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 3 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 3, out[-600:])

# сотрудники: Иван — «Выдача» на Складе; Ольга — только уведомления в Бердске; Пётр — «Каталог» на Складе;
# Маша — приглашена, не приняла; Гоша — сотрудник другого проката
IVAN = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [SKLAD], True, False, False, True, False)
OLGA = save(srv, 'admin@x.ru', None, 'olga@x.ru', 'Ольга', [BERDSK], False, False, False, True, False)
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD], False, True, False, False, False)
MASHA = save(srv, 'admin@x.ru', None, 'masha@x.ru', 'Маша', [SKLAD], True, False, False, True, False)
GOSHA = save(srv, 'admin2@x.ru', None, 'gosha@x.ru', 'Гоша', [ISK], True, False, False, True, False)
for em, sid in (('ivan@x.ru', IVAN), ('olga@x.ru', OLGA), ('petr@x.ru', PETR), ('gosha@x.ru', GOSHA)):
    accept(srv, em, sid)
check('сотрудники приглашены и приняли (стенд 10.103)',
      val(srv, "select count(*) from public.iva_staff where status = 'active'") == '4'
      and val(srv, "select status from public.iva_staff where email = 'masha@x.ru'") == 'invited')

o1 = submit(srv, 'Дрель Makita (3 дня), Пила (5 дней)', [(DRILL, 3), (SAW, 5)])
o2 = submit(srv, 'Дрель Makita (2 дня)', [(DRILL, 2)], email='ivan@x.ru')
o3 = submit(srv, 'Дрель (1 день)', [(D2, 1)])
P1S, P1B, P2S, P3I = part_of(srv, o1, 'Склад'), part_of(srv, o1, 'Бердск'), part_of(srv, o2, 'Склад'), part_of(srv, o3, 'Искитим')
check('заявки разделились по пунктам (стенд 10.102)', all(x.isdigit() for x in (P1S, P1B, P2S, P3I)), (P1S, P1B, P2S, P3I))

# ── 2. кто что видит ──
r = seen(srv, 'ivan@x.ru')
check('Иван видит только Склад: заявку клиента (может выдать) и свою (выдать не может)',
      r == '%s|Склад|new|false|true|true ; %s|Склад|new|true|false|true' % (P1S, P2S), r)
r = uval(srv, 'ivan@x.ru', "select client_name || '|' || client_tel || '|' || get_method || '|' || comment || '|' || days || '|' || tools_text "
                            "from public.iva_staff_parts() where part_id = '%s'" % P1S)
check('в строке — имя и телефон клиента, получение, комментарий, срок части, инструменты',
      r == 'Иван Клиентов|+79990000000|Самовывоз|Позвоните заранее|3|Дрель Makita', r)
cols = val(srv, "select string_agg(n, ',') from (select unnest(proargnames) n from pg_proc where proname = 'iva_staff_parts') x")
check('в ответе нет денег, почты клиента и паспорта',
      cols and not any(w in cols for w in ('fee', 'sum', 'price', 'email', 'passport', 'owner')), cols)
r = seen(srv, 'olga@x.ru')
check('Ольга (только уведомления) видит Бердск, но кнопок у неё нет', r == '%s|Бердск|new|false|false|true' % P1B, r)
check('Пётр («Каталог») заявок не видит', seen(srv, 'petr@x.ru') == '')
check('Маша (не приняла приглашение) заявок не видит', seen(srv, 'masha@x.ru') == '')
check('сотрудник другого проката видит только свой пункт', seen(srv, 'gosha@x.ru') == '%s|Искитим|new|false|true|true' % P3I)
check('клиент и владелец этой функцией ничего не получают', seen(srv, 'client@x.ru') == '' and seen(srv, 'admin@x.ru') == '')
check('гостю функция закрыта', err_has(as_(srv, '', 'select * from public.iva_staff_parts()', role='anon'), 'permission denied'))
check('проверка прав (iva_staff_right) снаружи закрыта',
      err_has(as_(srv, 'ivan@x.ru', "select public.iva_staff_right('%s', 'issue')" % SKLAD), 'permission denied'))

# ── 3. «Выдал» / «Вернули» ──
check('Ольга (без «Выдаёт») не отмечает', err_has(mark(srv, 'olga@x.ru', P1B, 'rented'), 'не найдена в вашем пункте'))
check('Иван не отмечает в чужом пункте', err_has(mark(srv, 'ivan@x.ru', P1B, 'rented'), 'не найдена в вашем пункте'))
check('Гоша не отмечает в чужом прокате', err_has(mark(srv, 'gosha@x.ru', P1S, 'rented'), 'не найдена в вашем пункте'))
check('клиент не отмечает', err_has(mark(srv, 'client@x.ru', P1S, 'rented'), 'не найдена в вашем пункте'))
check('гостю «Выдал» закрыт', err_has(as_(srv, '', "select public.iva_staff_set_status(%s, 'rented')" % P1S, role='anon'), 'permission denied'))
check('несуществующая заявка — «не найдена»', err_has(mark(srv, 'ivan@x.ru', 999999, 'rented'), 'не найдена в вашем пункте'))
check('другие состояния нельзя', err_has(mark(srv, 'ivan@x.ru', P1S, 'new'), 'Можно отметить только'))
check('сразу «Вернули» у новой нельзя', err_has(mark(srv, 'ivan@x.ru', P1S, 'closed'), 'уже в другом состоянии'))
n0 = int(val(srv, 'select count(*) from public.order_events'))
check('«Выдал» — работает', uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % P1S) == 'rented')
check('часть «Выдана», время выдачи записано',
      val(srv, "select status || '|' || (rented_at is not null) from public.order_parts where id = %s" % P1S) == 'rented|true')
check('в журнале статусов — почта сотрудника',
      val(srv, "select string_agg(old_status || '>' || new_status || '|' || email, ';') from public.order_events where id > %d" % n0) == 'new>rented|ivan@x.ru')
check('повторный «Выдал» — «уже в другом состоянии»', err_has(mark(srv, 'ivan@x.ru', P1S, 'rented'), 'уже в другом состоянии'))
r = seen(srv, 'ivan@x.ru')
check('после «Выдал» в списке «rented», кнопка осталась («Вернули»)', ('%s|Склад|rented|false|true|true' % P1S) in r, r)
check('«Вернули» — работает', uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'closed')" % P1S) == 'closed')
r = val(srv, "select status || '|' || rent_sum::numeric(12,0) || '|' || fee::numeric(12,0) from public.order_parts where id = %s" % P1S)
check('«Закрыта», комиссия посчитана как у владельца (300 ₽ × 3 дня, 5 %)', r == 'closed|900|45', r)
r = seen(srv, 'ivan@x.ru')
check('закрытая видна (за 7 дней), кнопок нет', ('%s|Склад|closed|false|false|true' % P1S) in r, r)
check('у закрытой «Вернули» ещё раз нельзя', err_has(mark(srv, 'ivan@x.ru', P1S, 'closed'), 'уже в другом состоянии'))

# ── 4. свою заявку ──
check('свою заявку без разрешения — нельзя', err_has(mark(srv, 'ivan@x.ru', P2S, 'rented'), 'Свою заявку выдаёт'))
save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [SKLAD], True, False, False, True, True)
r = seen(srv, 'ivan@x.ru')
check('владелец разрешил «самому себе» — кнопка появилась', ('%s|Склад|new|true|true|true' % P2S) in r, r)
check('«Выдал» свою — работает', uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % P2S) == 'rented')
save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [SKLAD], True, False, False, True, False)
check('разрешение сняли — «Вернули» свою снова нельзя', err_has(mark(srv, 'ivan@x.ru', P2S, 'closed'), 'Свою заявку выдаёт'))

# ── 5. напрямую — по-прежнему нельзя ──
st, out = as_(srv, 'ivan@x.ru', "update public.order_parts set status = 'closed' where id = %s" % P2S)
check('напрямую сотрудник заявку не меняет (правила доступа — только владелец)',
      val(srv, "select status from public.order_parts where id = %s" % P2S) == 'rented', out[-300:])
check('напрямую сотрудник заявки не читает',
      uval(srv, 'ivan@x.ru', "select count(*) from public.order_parts") == '0'
      and uval(srv, 'ivan@x.ru', "select count(*) from public.orders where user_email <> 'ivan@x.ru'") == '0')

# ── 6. что скрыто ──
q(srv, "update public.order_parts set archived = true where id = %s" % P1S)
check('архив не виден', P1S not in seen(srv, 'ivan@x.ru').replace(' ; ', '|').split('|')[0::6])
q(srv, "update public.order_parts set archived = false, created_at = now() - interval '8 days' where id = %s" % P1S)
check('закрытая старше 7 дней не видна', P1S not in seen(srv, 'ivan@x.ru').replace(' ; ', '|').split('|')[0::6])
q(srv, "update public.order_parts set created_at = now() - interval '30 days' where id = %s" % P2S)
check('выданная видна, даже если заявка старая', ('%s|Склад|rented' % P2S) in seen(srv, 'ivan@x.ru'))
o4 = submit(srv, 'Дрель Makita (1 день)', [(DRILL, 1)])
P4 = part_of(srv, o4, 'Склад')
q(srv, "alter table public.order_parts disable trigger user; update public.order_parts set point_id = null where id = %s; "
       "alter table public.order_parts enable trigger user" % P4)
check('часть без пункта сотруднику не видна и не отмечается',
      P4 not in seen(srv, 'ivan@x.ru') and err_has(mark(srv, 'ivan@x.ru', P4, 'rented'), 'не найдена в вашем пункте'))

# ── 7. доступ пропадает сразу ──
o5 = submit(srv, 'Дрель Makita (1 день)', [(DRILL, 1)])
P5 = part_of(srv, o5, 'Склад')
save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [BERDSK], True, False, False, True, False)
check('сотрудника перевели в Бердск — Склад пропал, Бердск появился',
      P5 not in seen(srv, 'ivan@x.ru') and P1B in seen(srv, 'ivan@x.ru') and err_has(mark(srv, 'ivan@x.ru', P5, 'rented'), 'не найдена'))
save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [SKLAD], True, False, False, True, False)
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
check('прокат выключен — сотрудник ничего не видит и не отмечает',
      seen(srv, 'ivan@x.ru') == '' and err_has(mark(srv, 'ivan@x.ru', P5, 'rented'), 'не найдена'))
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")
check('прокат включён — снова видит', P5 in seen(srv, 'ivan@x.ru'))
as_(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % IVAN)
check('«Убрать из сотрудников» — сразу ничего не видит и не отмечает',
      seen(srv, 'ivan@x.ru') == '' and err_has(mark(srv, 'ivan@x.ru', P5, 'rented'), 'не найдена'))
check('у Гоши (другой прокат) ничего не изменилось', seen(srv, 'gosha@x.ru') == '%s|Искитим|new|false|true|true' % P3I)

# ── 8. откат из шапки, повтор ──
check('в шапке есть блок отката (3 функции + обновление схемы)', RB.count('DROP FUNCTION') == 3 and 'NOTIFY pgrst' in RB, RB)
st, out = q(srv, RB)
check('откат выполняется без ошибок', st == 'OK', out[-600:])
check('после отката функций нет, заявки и сотрудники на месте',
      val(srv, "select count(*) from pg_proc where proname in ('iva_staff_parts', 'iva_staff_set_status', 'iva_staff_right')") == '0'
      and val(srv, "select count(*) from public.order_parts") == '6'
      and val(srv, "select count(*) from public.iva_staff") == '4')
st, out = q(srv, SQL)
check('после отката применяется снова', st == 'OK' and out.count('✅') == 3, out[-600:])
srv.cleanup()

# ── 9. без SQL 10.103 / 10.102 — понятная ошибка ──
srv = mk()
q(srv, BASELINE + EXTRA + PROD)
for s in (RENT, PICK, TEL, TP, OP):
    q(srv, s)
st, out = q(srv, SQL)
check('без SQL 10.103 — понятная ошибка', st == 'ERR' and 'Сначала примените 20261028_staff.sql' in out, out[-400:])
srv.cleanup()
srv = mk()
q(srv, BASELINE + EXTRA + PROD)
for s in (RENT, PICK, TEL, TP):
    q(srv, s)
q(srv, "create table public.iva_staff(id int); create table public.iva_staff_points(id int)")
st, out = q(srv, SQL)
check('без SQL 10.102 — понятная ошибка', st == 'ERR' and 'Сначала примените 20261027_order_points.sql' in out, out[-400:])
srv.cleanup()

print('\nИтого: %s' % ('всё ✔' if not fails else '%d ✘' % fails))
sys.exit(1 if fails else 0)

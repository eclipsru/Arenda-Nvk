# Стенд 10.132 «Стрелки — сразу»: 20261122_msg_ping.sql на настоящем PostgreSQL (pgserver).
#
# База как в стенде чата сотрудников 10.107 (BASELINE + пункты + 20261026 + 20261028, app_messages с колонками сайта)
# и пустая публикация supabase_realtime, как в Supabase. Проверяет: файл применяется и повторяется, отметка — в публикации;
# отметку ставят: новое сообщение (получателю), сообщение владельцу по инструменту пункта — и сотрудникам этого пункта
# с правом чата (без права, приглашённым и с другого пункта — нет), чат пункта (point_id), «прочитано»; НЕ ставит правка текста и сообщение
# без получателя (а само сообщение сохраняется); читает только сам человек, гость — нет, записать и вызвать служебную
# функцию нельзя; без таблиц сотрудников — получателю всё равно; откат; повторное применение.
#
# Запуск: python tools/msg-ping-stand.py  (нужен pip-пакет pgserver)
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
STAFF = rd('supabase/migrations/20261028_staff.sql')
SQL = rd('supabase/migrations/20261122_msg_ping.sql')
RB = rd('supabase/migrations/20261122_msg_ping_rollback.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
CHATX = const('tools/staff-chat-stand.py', 'CHATX')
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


def ping(srv, email):
    return val(srv, "select coalesce((select extract(epoch from changed_at)::text from public.iva_msg_ping where email = '%s'), '')" % email)


def moved(srv, emails, action):
    before = {e: ping(srv, e) for e in emails}
    time.sleep(0.02)
    r = action()
    after = {e: ping(srv, e) for e in emails}
    return [e for e in emails if after[e] != before[e]], r


A = 'admin@x.ru'
ALL = [A, 'petr@x.ru', 'ivan@x.ru', 'olga@x.ru', 'inv@x.ru', 'client@x.ru', 'client2@x.ru']

srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + CHATX)
check('исходная база как в бою (чат с колонками сайта)', st == 'OK', out[-600:])
bad = [n for n, s in (('rent', RENT), ('pick', PICK), ('tel', TEL), ('tp', TP), ('staff', STAFF), ('pub', PUB)) if q(srv, s)[0] != 'OK']
check('SQL пунктов и сотрудников применяются', not bad, bad)
P1 = val(srv, "insert into public.iva_pickup_points(name, address, city, owner_email, email) values ('Склад', 'ул. Ленина, 5', 'Новосибирск', '%s', '%s') returning id" % (A, A)).split(';')[0]
P2 = val(srv, "insert into public.iva_pickup_points(name, address, city, owner_email, email) values ('Бердск', 'ул. Мира, 1', 'Бердск', '%s', '%s') returning id" % (A, A)).split(';')[0]
T1 = val(srv, "insert into public.tools(name, owner_email, point_id) values ('Дрель', '%s', '%s') returning id" % (A, P1)).split(';')[0]
st, out = q(srv, """
insert into public.iva_staff(owner_email, email, name, can_chat, status) values
  ('%(a)s', 'petr@x.ru', 'Пётр', true, 'active'), ('%(a)s', 'ivan@x.ru', 'Иван', false, 'active'),
  ('%(a)s', 'olga@x.ru', 'Ольга', true, 'active'), ('%(a)s', 'inv@x.ru', 'Новый', true, 'invited');
insert into public.iva_staff_points(staff_id, point_id)
  select s.id, case when s.email = 'olga@x.ru' then '%(p2)s'::uuid else '%(p1)s'::uuid end from public.iva_staff s;
""" % {'a': A, 'p1': P1, 'p2': P2})
check('пункты, инструмент пункта, сотрудники (с правом чата, без, другой пункт, приглашённый)', st == 'OK' and len(P1) == 36 and len(T1) == 36, out[-600:])

st, out = q(srv, SQL)
check('20261122 применяется', st == 'OK', out[-900:])
check('проверка в конце: всё «✅», кроме отметки заявок (SQL 20261119 здесь не применён)',
      out.count('✅') == 4 and out.count('❌') == 1 and 'отметка заявок' in out.split('❌')[0].splitlines()[-1], out[-900:])
st, out = q(srv, SQL)
check('20261122 повторно — без ошибок', st == 'OK', out[-600:])
check('отметка в публикации supabase_realtime',
      val(srv, "select count(*) from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'iva_msg_ping'") == '1')


def say(frm, to, text, tool=None):
    return lambda: as_(srv, frm, "insert into public.app_messages(from_email, to_email, text, tool_id) values ('%s', '%s', '%s', %s)"
                       % (frm, to, text, "'%s'" % tool if tool else 'null'))


m, r = moved(srv, ALL, say('client@x.ru', 'client2@x.ru', 'привет'))
check('сообщение без инструмента — только получателю', m == ['client2@x.ru'] and r[0] == 'OK', (m, r))
m, r = moved(srv, ALL, say('client@x.ru', A, 'есть дрель?', T1))
check('владельцу по инструменту пункта — владельцу и сотруднику пункта с правом чата', sorted(m) == sorted([A, 'petr@x.ru']) and r[0] == 'OK', (m, r))
m, r = moved(srv, ALL, say(A, 'client@x.ru', 'есть', T1))
check('ответ владельца клиенту — клиенту и сотруднику пункта (список чатов)', sorted(m) == sorted(['client@x.ru', 'petr@x.ru']) and r[0] == 'OK', (m, r))
mid = val(srv, "select id from public.app_messages where text = 'есть дрель?'")
m, r = moved(srv, ALL, lambda: as_(srv, A, "update public.app_messages set is_read = true where id = %s" % mid))
check('«прочитано» — владельцу и сотруднику пункта (стрелка гаснет)', sorted(m) == sorted([A, 'petr@x.ru']) and r[0] == 'OK', (m, r))
m, r = moved(srv, ALL, lambda: q(srv, "update public.app_messages set text = 'исправлено' where id = %s" % mid))
check('правка текста — без отметки', m == [] and r[0] == 'OK', (m, r))
m, r = moved(srv, ALL, lambda: q(srv, "update public.app_messages set is_read = true where id = %s" % mid))
check('«прочитано» ещё раз (не изменилось) — без отметки', m == [] and r[0] == 'OK', (m, r))
q(srv, 'alter table public.app_messages add column if not exists point_id uuid')
m, r = moved(srv, ALL, lambda: as_(srv, 'client@x.ru', "insert into public.app_messages(from_email, to_email, text, point_id) values ('client@x.ru', '%s', 'вопрос', '%s')" % (A, P2)))
check('чат пункта (point_id) — владельцу и сотруднику того пункта', sorted(m) == sorted([A, 'olga@x.ru']) and r[0] == 'OK', (m, r))
st, out = q(srv, "insert into public.app_messages(from_email, to_email, text) values ('client@x.ru', '', 'x')")
check('сообщение без получателя сохраняется (ошибка отметки сообщению не мешает)', st == 'OK', out[-400:])

check('человек видит только свою отметку', val(srv, "begin; set local role authenticated; set local request.jwt.claims = '%s'; select string_agg(email, ',') from public.iva_msg_ping; commit;" % claims('petr@x.ru')) == 'petr@x.ru')
st, out = as_(srv, '', 'select count(*) from public.iva_msg_ping', role='anon')
check('гость отметки не читает', st == 'ERR', out[-300:])
st, out = as_(srv, 'petr@x.ru', "insert into public.iva_msg_ping(email) values ('petr@x.ru')")
check('записать отметку самому нельзя', st == 'ERR', out[-300:])
st, out = as_(srv, 'petr@x.ru', "select public.iva_msg_ping_touch('client@x.ru')")
check('вызвать служебную функцию нельзя', st == 'ERR', out[-300:])

st, out = q(srv, RB)
check('откат выполняется', st == 'OK' and '✅ откат выполнен' in out, out[-400:])
st, out = q(srv, "insert into public.app_messages(from_email, to_email, text) values ('client@x.ru', '%s', 'после отката')" % A)
check('после отката сообщения пишутся', st == 'OK', out[-300:])
st, out = q(srv, SQL)
check('после отката применяется снова', st == 'OK' and out.count('✅') == 4, out[-400:])

# Б: база без сотрудников — получателю отметка всё равно
srv2 = mk()
st, out = q(srv2, BASELINE + EXTRA + PROD + CHATX + PUB)
st2, out2 = q(srv2, SQL)
check('Б: без таблиц сотрудников применяется', st == 'OK' and st2 == 'OK', (out[-300:], out2[-600:]))
st, out = q(srv2, "insert into public.app_messages(from_email, to_email, text, tool_id) values ('client@x.ru', '%s', 'x', null)" % A)
check('Б: получателю отметка есть', st == 'OK' and ping(srv2, A) != '', out[-300:])

print('ошибок: %d' % fails)
sys.exit(1 if fails else 0)

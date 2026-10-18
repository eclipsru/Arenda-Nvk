# Стенд 10.123: 20261114_v123.sql (и откат 20261114_v123_rollback.sql) на настоящем PostgreSQL (pgserver).
#
# База как в рабочей: всё до 10.122 (пункты, сотрудники, журнал «кто выдал», 20261104–20261113, чужой trg_tool_owner).
# Проверяет: «Выдал» по части с разными сроками делит её по срокам (у каждой своя «Вернули» и таймер, время выдачи
# одно, кто выдал — тот же, журнал статусов, без повторных сигналов «Новая заявка»); владелец (штатно) и сотрудник
# (iva_staff_set_status); одинаковые сроки и заявки сайта не делятся; «Вернули» по одной части не трогает другую;
# комиссия по своей части; заявки целиком (клиент, владелец, сотрудник) видят обе части; откат и повторное применение.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/v123-stand.py
import ast
import json
import os
import re
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


M = 'supabase/migrations/'
FILES = ['20261014_rent_end_mode.sql', '20261014_reviews_no_mat.sql', '20261021_censor.sql', '20261023_pickup_points.sql',
         '20261025_pickup_tel.sql', '20261026_tool_points.sql', '20261027_order_points.sql', '20261028_staff.sql',
         '20261029_staff_orders.sql', '20261030_staff_tools.sql', '20261031_staff_chat.sql', '20261101_staff_journal.sql',
         '20261102_admin_manage.sql',
         '20261103_role_ping.sql', '20261104_point_staff_publish.sql', '20261105_staff_pick_point.sql',
         '20261106_staff_live.sql', '20261107_staff_publish_check.sql', '20261108_staff_publish_probe.sql']
V121 = rd(M + '20261112_v121.sql')
SQL = rd(M + '20261113_v122.sql')
V122 = SQL
SQL = rd(M + '20261114_v123.sql')
RBF = rd(M + '20261114_v123_rollback.sql')
FINAL = rd(M + '20261109_staff_owner_final.sql')
PAGE = rd(M + '20261111_prokat_page.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
ADM = const('tools/admin-manage-stand.py', 'ADM')
CHATX = const('tools/staff-chat-stand.py', 'CHATX')
CHATX = '\n'.join(l for l in CHATX.splitlines() if not l.startswith(('create function auth.uid()', 'grant execute on function auth.uid()')))
CHATX = CHATX.replace('alter table public.tools add column if not exists img text;\n', '')
FOREIGN = const('tools/staff-owner-stand.py', 'FOREIGN')
PUB = 'create publication supabase_realtime;'
ADMX = r"""
alter table public.admins add column if not exists org_avatar text;
alter table public.admins add column if not exists org_tel text;
alter table public.admins add column if not exists phone text;
alter table public.admins add column if not exists address text;
alter table public.admins add column if not exists work_hours text;
alter table public.tools add column if not exists deposit numeric;
alter table public.tools add column if not exists terms text;
alter table public.tools add column if not exists imgs text[];
alter table public.tools add column if not exists model_id bigint;
update public.admins set company = 'Прокат «Ива»', org_tel = '+7 900 000-11-22', phone = '+7 999 999-99-99' where email = 'admin@x.ru';
insert into public.admins(email, role, active, company) values ('admin7@x.ru', 'admin', true, 'Новый прокат');
"""

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


def ins_point(name, addr, mode='approve', tel=''):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email, staff_publish, tel) "
            "values ('%s', '%s', 'Новосибирск', 55.0, 82.9, 'prokat@mail.ru', '%s', '%s')") % (name, addr, mode, tel)


def pid(srv, name):
    return val(srv, "select id from public.iva_pickup_points where name = '%s'" % name)


def save(srv, owner, email, name, points, issue, catalog):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(null, '%s', '%s', '', %s, %s, %s, false, false, false)" %
                (email, name, arr, b(issue), b(catalog)))


def ping(srv, email):
    return val(srv, "select coalesce((select extract(epoch from changed_at)::text from public.iva_role_ping where email = '%s'), '')" % email)


def tool(srv, tid):
    return val(srv, "select name || '|' || price || '|' || active || '|' || coalesce(review, '') || '|' || coalesce(edit_pending::text, '') "
                    "|| '|' || edit_rejected from public.tools where id = '%s'" % tid)


def qs(srv, sql):
    st, out = q(srv, sql)
    return st, re.sub(r'[ \t]*\|[ \t]*', ' | ', out)


def ensure_tel_points(srv):
    return uval(srv, '', "select coalesce(string_agg(name || '=' || tel, ' ; ' order by n), '') from public.iva_prokat_points('%s') "
                         "with ordinality as x(id, name, address, city, open_min, close_min, tel, lat, lng, company, avatar, tools, main, n)"
                % pid(srv, 'Склад'), role='anon')


# ── 1. база, применение, повтор ──
srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX + CHATX + PUB)
check('исходная база как в бою', st == 'OK', out[-600:])
bad = [f for f in FILES if q(srv, rd(M + f))[0] != 'OK']
check('SQL 10.77 … 10.118 применяются', not bad, bad)
st, out = q(srv, ADM + ADMX)
check('админы как в бою (название, телефоны, ещё один прокат без пунктов)', st == 'OK', out[-400:])
check('чужой trg_tool_owner и 20261109, 20261111 применяются', all(q(srv, s)[0] == 'OK' for s in (FOREIGN, FINAL, PAGE)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5', 'approve', '+7 383 111-22-33'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск, улица Свердлова, 7', 'now'))
as_(srv, 'admin@x.ru', ins_point('Пустой', 'Новосибирск, улица Мира, 1'))
SK, BK, PU = pid(srv, 'Склад'), pid(srv, 'Бердск'), pid(srv, 'Пустой')
PETR = save(srv, 'admin@x.ru', 'petr@x.ru', 'Пётр', [SK, BK], False, True)
IVAN = save(srv, 'admin@x.ru', 'ivan@x.ru', 'Иван', [SK], True, False)
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN)
as_(srv, 'admin@x.ru', ("insert into public.tools(name, owner_email, price, point_id, active, cat, descr, deposit, img, imgs) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}', true, 'drill', 'Мощная', 2000, 'https://x/d.jpg', null), "
        "('Пила', 'admin@x.ru', 500, '{b}', true, 'saw', '', null, '', array['https://x/p1.jpg']), "
        "('Шлифмашина', 'admin@x.ru', 250, '{p}', true, 'drill', '', 1000, '', null)").format(s=SK, b=BK, p=PU))
DRILL = val(srv, "select id from public.tools where name = 'Дрель Makita'")
SAW = val(srv, "select id from public.tools where name = 'Пила'")
GRIND = val(srv, "select id from public.tools where name = 'Шлифмашина'")
import hashlib

st, out = qs(srv, V121)
check('20261112_v121.sql (10.121) применяется', st == 'OK' and out.count('✅') == 5, out[-600:])
st, out = qs(srv, V122)
check('20261113_v122.sql (10.122) применяется', st == 'OK' and out.count('✅') == 5, out[-600:])
st, out = qs(srv, SQL)
check('20261114_v123.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все 3 строки «✅»', st == 'OK' and out.count('✅') == 3 and '❌' not in out, out[-900:])
st, out = qs(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 3, out[-600:])
check('порядок триггеров «до изменения»: деление — последним',
      val(srv, "select string_agg(tgname, ',' order by tgname) from pg_trigger where tgrelid = 'public.order_parts'::regclass and not tgisinternal "
               "and (tgtype & 2) = 2 and (tgtype & 16) = 16").endswith(',trg_zzz_iva_part_split'))

def save11(owner, sid, email, name, points, issue, catalog, chat, delete):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'null' if x is None else ('true' if x else 'false')
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, %s, false, false, %s)" %
                ("'%s'" % sid if sid else 'null', email, name, arr, b(issue), b(catalog), b(chat), b(delete)))


def cnt(sql):
    return val(srv, "select count(*) from (%s) x" % sql)


def order(items, who='client@x.ru'):
    return uval(srv, '', "select public.iva_submit_order('Иван Клиентов', '+79990000000', 'x', 'Самовывоз', '', "
                         "'', '%s', 3, '%s'::jsonb)" % (who, json.dumps(items)), role='anon')


def parts(o):
    return val(srv, "select string_agg(status || ':' || tools_text || ':' || (select string_agg(e ->> 'days', '/') from jsonb_array_elements(items) e), ' | ' order by id) "
                    "from public.order_parts where order_id = %s" % o)


as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where id in ('%s', '%s')" % (SK, GRIND, DRILL))
# ── 2. владелец «Выдал» (штатно: PATCH order_parts status) ──
O1 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 7}])
P1 = val(srv, "select id from public.order_parts where order_id = %s" % O1)
check('заявка: одна часть, два срока', parts(O1) == 'new:Дрель Makita, Шлифмашина:3/7', parts(O1))
pushes = val(srv, "select count(*) from public.iva_push_log")
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P1)
check('«Выдал» владельцем — без ошибок', st == 'OK', out[-400:])
check('часть разделилась по срокам: 3 дня и 7 дней, обе «выдано»', parts(O1) == 'rented:Дрель Makita:3 | rented:Шлифмашина:7', parts(O1))
check('время выдачи одно, кто выдал — тот же, пункт и прокат те же',
      val(srv, "select count(distinct rented_at) || ':' || count(distinct issued_by) || ':' || max(issued_by) || ':' || count(distinct point_id) "
               "|| ':' || count(distinct owner_email) || ':' || count(*) filter (where point_id = '%s') from public.order_parts where order_id = %s" % (SK, O1))
      == '1:1:admin@x.ru:1:1:2')
check('журнал статусов: «выдано» у обеих частей',
      val(srv, "select count(*) from public.order_events e join public.order_parts p on p.id = e.part_id where p.order_id = %s and e.new_status = 'rented'" % O1) == '2')
check('сигнал «Новая заявка» владельцу повторно не ушёл', val(srv, "select count(*) from public.iva_push_log") == pushes)
P1B = val(srv, "select max(id) from public.order_parts where order_id = %s" % O1)
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set status = 'closed' where id = %s" % P1)
check('«Вернули» по дрели — пила остаётся в аренде', parts(O1) == 'closed:Дрель Makita:3 | rented:Шлифмашина:7', parts(O1))
check('комиссия по своей части: дрель 300 × 3 × 5% = 45', val(srv, "select fee from public.order_parts where id = %s" % P1) == '45')
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'closed' where id = %s" % P1B)
check('«Вернули» по пиле: шлифмашина 250 × 7 × 5% = 88', val(srv, "select fee from public.order_parts where id = %s" % P1B) == '88'
      and parts(O1) == 'closed:Дрель Makita:3 | closed:Шлифмашина:7', parts(O1))
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P1B)
check('из «Закрыта» обратно — нельзя (как раньше)', st == 'ERR' and 'Недопустимый' in out, out[-200:])

# ── 3. сотрудник «Выдача»: три срока, два инструмента с одним сроком ──
as_(srv, 'admin@x.ru', ("insert into public.tools(name, owner_email, price, point_id, active, cat) values "
                        "('Перфоратор', 'admin@x.ru', 400, '%s', true, 'drill')") % SK)
PERF = val(srv, "select id from public.tools where name = 'Перфоратор'")
O2 = order([{'id': DRILL, 'days': 5}, {'id': GRIND, 'days': 2}, {'id': PERF, 'days': 5}])
P2 = val(srv, "select id from public.order_parts where order_id = %s" % O2)
r = uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % P2)
check('сотрудник «Выдал»: короткий срок — в этой части, 5 дней — дрель и перфоратор вместе', r == 'rented'
      and parts(O2) == 'rented:Шлифмашина:2 | rented:Дрель Makita, Перфоратор:5/5', parts(O2))
check('кто выдал — сотрудник, у обеих частей', val(srv, "select string_agg(distinct issued_by, ',') from public.order_parts where order_id = %s" % O2) == 'ivan@x.ru')
P2B = val(srv, "select max(id) from public.order_parts where order_id = %s" % O2)
S = uval(srv, 'ivan@x.ru', "select count(*) from public.iva_staff_parts() x where x.order_id = '%s'" % O2)
check('сотрудник видит обе части в «Заявках пункта»', S == '2', S)
r = uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'closed')" % P2B)
check('сотрудник «Вернули» по 5 дням: комиссия (300 + 400) × 5 × 5% = 175, шлифмашина в аренде', r == 'closed'
      and val(srv, "select fee from public.order_parts where id = %s" % P2B) == '175'
      and val(srv, "select status from public.order_parts where id = %s" % P2) == 'rented')
C = [json.loads(x) for x in uval(srv, 'client@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('client') j").split(' @@@ ') if x]
c2 = [c for c in C if c['order_id'] == O2]
check('клиент видит заявку целиком: две части, у каждой свой статус и срок', c2 and len(c2[0]['parts']) == 2
      and sorted(p['status'] for p in c2[0]['parts']) == ['closed', 'rented'], c2)
A = [json.loads(x) for x in uval(srv, 'admin@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('admin') j").split(' @@@ ') if x]
check('владелец видит каждую часть отдельно', len([a for a in A if a['order_id'] == O2]) == 2, [a.get('part_id') for a in A if a['order_id'] == O2])

# ── 4. что не делится ──
O3 = order([{'id': DRILL, 'days': 4}, {'id': GRIND, 'days': 4}])
P3 = val(srv, "select id from public.order_parts where order_id = %s" % O3)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P3)
check('одинаковый срок — одна часть', parts(O3) == 'rented:Дрель Makita, Шлифмашина:4/4', parts(O3))
O4 = order([{'id': DRILL, 'days': 3}])
P4 = val(srv, "select id from public.order_parts where order_id = %s" % O4)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P4)
check('один инструмент — одна часть', parts(O4) == 'rented:Дрель Makita:3', parts(O4))
O5 = uval(srv, '', "select public.iva_submit_order('Сайт', '+79990000000', 'Дрель Makita, Шлифмашина', 'Самовывоз', '', '', 'client@x.ru', 3, null)", role='anon')
P5 = val(srv, "select min(id) from public.order_parts where order_id = %s" % O5)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P5)
check('заявка с сайта (без списка) — не делится', cnt("select 1 from public.order_parts where order_id = %s" % O5) == '1')
O6 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 6}])
P6 = val(srv, "select id from public.order_parts where order_id = %s" % O6)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'closed' where id = %s" % P6)
check('сразу «Закрыта» (без выдачи) — не делится, комиссия за всё: (300 × 3 + 250 × 6) × 5% = 120',
      cnt("select 1 from public.order_parts where order_id = %s" % O6) == '1' and val(srv, "select fee from public.order_parts where id = %s" % P6) == '120')
O7 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 6}])
P7 = val(srv, "select id from public.order_parts where order_id = %s" % O7)
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set items = '[]'::jsonb, tools_text = 'x' where id = %s" % P7)
check('без «Выдал» (правка строки) — ничего не делится, список защищён как раньше', parts(O7) == 'new:x:3/6', parts(O7))
st, out = as_(srv, 'client@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P7)
check('клиент «Выдал» сам себе — нельзя (ничего не меняется)', parts(O7) == 'new:x:3/6', parts(O7))
O8 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 6}], who='eclipsik.ru@mail.ru')
P8 = val(srv, "select id from public.order_parts where order_id = %s" % O8)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P8)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'closed' where order_id = %s" % O8)
check('тестовый клиент: части без комиссии', val(srv, "select string_agg(fee::text, ',' order by id) from public.order_parts where order_id = %s" % O8) == '0,0')
check('гостю и вошедшим функция деления напрямую недоступна', as_(srv, 'admin@x.ru', "select public.iva_part_split_trg()")[0] == 'ERR')

# ── 5. откат ──
st, out = qs(srv, RBF)
check('откат 20261114_v123_rollback.sql — без ошибок, «✅»', st == 'OK' and out.count('✅') == 1 and '❌' not in out, out[-600:])
O9 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 6}])
P9 = val(srv, "select id from public.order_parts where order_id = %s" % O9)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P9)
check('после отката: «Выдал» — одна часть, как в 10.122', parts(O9) == 'rented:Дрель Makita, Шлифмашина:3/6', parts(O9))
check('после отката: разделённые раньше части на месте', parts(O2) == 'rented:Шлифмашина:2 | closed:Дрель Makita, Перфоратор:5/5', parts(O2))
r = uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'closed')" % P2)
check('после отката: «Вернули» по оставшейся части работает (250 × 2 × 5% = 25)', r == 'closed' and val(srv, "select fee from public.order_parts where id = %s" % P2) == '25')
st, out = qs(srv, SQL)
check('после отката файл применяется снова', st == 'OK' and out.count('✅') == 3, out[-400:])

print('\nИтого: %s' % ('все проверки прошли' if fails == 0 else 'не прошло: %d' % fails))
sys.exit(1 if fails else 0)

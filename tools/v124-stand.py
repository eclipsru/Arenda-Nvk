# Стенд 10.124: 20261115_v124.sql (и откат 20261115_v124_rollback.sql) на настоящем PostgreSQL (pgserver).
#
# База как в рабочей: всё до 10.122 (пункты, сотрудники, журнал «кто выдал», 20261104–20261113, чужой trg_tool_owner),
# плюс 20261114_v123.sql (вдруг владелец его применил). Проверяет: деление заявки на части выключено — заявка с разными
# сроками остаётся одной; «Вернули» по сроку (iva_staff_return) у владельца и у сотрудника с правом «Выдача»:
# отмечает только инструменты этого срока, заявка остаётся «Выдана», последний срок закрывает её с комиссией за всё;
# кто не может (без права, чужой прокат, клиент, гость, «своя заявка» сотрудника) — понятный отказ, ничего не меняется;
# заявка целиком отдаёт время возврата у инструмента (без почты принявшего); таймеры — по каждому оставшемуся сроку;
# откат и повторное применение.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/v124-stand.py
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
V123 = rd(M + '20261114_v123.sql')
SQL = rd(M + '20261115_v124.sql')
RBF = rd(M + '20261115_v124_rollback.sql')
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
st, out = qs(srv, V123)
check('20261114_v123.sql (10.123, деление на части) применяется — как будто владелец успел', st == 'OK' and out.count('✅') == 3, out[-600:])
st, out = qs(srv, SQL)
check('20261115_v124.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все 4 строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = qs(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
check('триггера и функции деления (10.123) больше нет',
      val(srv, "select count(*) from pg_trigger where tgname = 'trg_zzz_iva_part_split'") == '0'
      and val(srv, "select to_regprocedure('public.iva_part_split_trg()') is null") == 't')

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


def items(p):
    return val(srv, "select string_agg((e ->> 'days') || ':' || case when coalesce(e ->> 'returned_at', '') = '' then '-' else coalesce(e ->> 'returned_by', '?') end, '/' order by n) "
                    "from public.order_parts p, jsonb_array_elements(p.items) with ordinality as x(e, n) where p.id = %s" % p)


def rentals(email, fn, o):
    return uval(srv, email, "select coalesce(string_agg(tools_text || '#' || days, ' | '), '') from public.%s() where order_id = '%s'" % (fn, o))


def ret(email, p, d, role='authenticated'):
    return as_(srv, email, "select public.iva_staff_return(%s, %s)" % (p, d), role=role)


def card_items(email, scope, o):
    C = [json.loads(x) for x in uval(srv, email, "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('%s') j" % scope).split(' @@@ ') if x]
    out = []
    for c in C:
        if str(c.get('order_id')) != str(o):
            continue
        for p in c.get('parts') or [c]:
            out += p.get('items') or []
    return out


# ── 2. владелец: «Выдал» — заявка одна; «Вернули» по сроку ──
O1 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 7}])
P1 = val(srv, "select id from public.order_parts where order_id = %s" % O1)
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P1)
check('«Выдал» владельцем (штатно) — заявка с двумя сроками остаётся одной', st == 'OK' and parts(O1) == 'rented:Дрель Makita, Шлифмашина:3/7', parts(O1))
r = rentals('admin@x.ru', 'iva_admin_rentals', O1)
check('таймеры владельца: по каждому сроку («Дрель Makita (3 дн), Шлифмашина (7 дн)»)', r == 'Дрель Makita (3 дн), Шлифмашина (7 дн)#7', r)
r = rentals('client@x.ru', 'iva_my_rentals', O1)
check('таймеры клиента: тоже по каждому сроку', r == 'Дрель Makita (3 дн), Шлифмашина (7 дн)#7', r)
st, out = ret('admin@x.ru', P1, 3)
check('владелец «Вернули» по 3 дням → «partial», заявка «Выдана»', st == 'OK' and 'partial' in out
      and val(srv, "select status from public.order_parts where id = %s" % P1) == 'rented', out[-300:])
check('вернули только дрель, принял владелец; шлифмашина — в аренде', items(P1) == '3:admin@x.ru/7:-', items(P1))
check('комиссии пока нет (заявка не закрыта)', val(srv, "select coalesce(fee, 0) from public.order_parts where id = %s" % P1) == '0')
r = rentals('admin@x.ru', 'iva_admin_rentals', O1)
check('таймер остался только у 7 дней', r == 'Шлифмашина (7 дн)#7', r)
ci = card_items('client@x.ru', 'client', O1)
check('клиент в заявке целиком видит, что дрель вернули (время), почты принявшего нет',
      len(ci) == 2 and ci[0].get('returned_at') and not ci[1].get('returned_at') and 'admin@x.ru' not in json.dumps(ci), ci)
ai = card_items('admin@x.ru', 'admin', O1)
check('владелец в заявке целиком — то же', len(ai) == 2 and ai[0].get('returned_at') and not ai[1].get('returned_at'), ai)
st, out = ret('admin@x.ru', P1, 3)
check('повторно «Вернули» по тем же 3 дням — «уже в другом состоянии», ничего не меняется',
      st == 'ERR' and 'Заявка уже в другом состоянии — обновите список' in out and items(P1) == '3:admin@x.ru/7:-', out[-300:])
st, out = ret('admin@x.ru', P1, 5)
check('срока, которого нет в заявке, — отказ', st == 'ERR' and 'уже в другом состоянии' in out, out[-300:])
ev = val(srv, "select count(*) from public.order_events where part_id = %s" % P1)
st, out = ret('admin@x.ru', P1, 7)
check('последний срок → «closed», заявка закрыта', st == 'OK' and 'closed' in out
      and val(srv, "select status from public.order_parts where id = %s" % P1) == 'closed', out[-300:])
check('комиссия за всю заявку: (300 × 3 + 250 × 7) × 5% = 133', val(srv, "select fee from public.order_parts where id = %s" % P1) == '133',
      val(srv, "select fee from public.order_parts where id = %s" % P1))
check('журнал статусов: одна запись «закрыта»', val(srv, "select count(*) from public.order_events where part_id = %s" % P1) == str(int(ev) + 1)
      and val(srv, "select new_status from public.order_events where part_id = %s order by id desc limit 1" % P1) == 'closed')
check('закрытая — таймеров нет', rentals('admin@x.ru', 'iva_admin_rentals', O1) == '')
st, out = ret('admin@x.ru', P1, 7)
check('по закрытой «Вернули» — отказ', st == 'ERR' and 'уже в другом состоянии' in out, out[-300:])

# ── 3. сотрудник с правом «Выдача»; кто не может ──
as_(srv, 'admin@x.ru', ("insert into public.tools(name, owner_email, price, point_id, active, cat) values "
                        "('Перфоратор', 'admin@x.ru', 400, '%s', true, 'drill')") % SK)
PERF = val(srv, "select id from public.tools where name = 'Перфоратор'")
O2 = order([{'id': DRILL, 'days': 5}, {'id': GRIND, 'days': 2}, {'id': PERF, 'days': 5}])
P2 = val(srv, "select id from public.order_parts where order_id = %s" % O2)
st, out = ret('ivan@x.ru', P2, 2)
check('«Новая» (ещё не выдана) — «Вернули» нельзя', st == 'ERR' and 'уже в другом состоянии' in out, out[-300:])
r = uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % P2)
check('сотрудник «Выдал» — заявка одна', r == 'rented' and parts(O2) == 'rented:Дрель Makita, Шлифмашина, Перфоратор:5/2/5', parts(O2))
check('сотрудник видит одну заявку в «Заявках пункта»', uval(srv, 'ivan@x.ru', "select count(*) from public.iva_staff_parts() x where x.order_id = '%s'" % O2) == '1')
for who, why in (('petr@x.ru', 'сотрудник без права «Выдача»'), ('admin7@x.ru', 'чужой прокат'), ('client@x.ru', 'клиент')):
    st, out = ret(who, P2, 2)
    check('%s — «Заявка не найдена в вашем пункте», ничего не меняется' % why,
          st == 'ERR' and 'Заявка не найдена в вашем пункте' in out and items(P2) == '5:-/2:-/5:-', out[-300:])
st, out = ret('', P2, 2, role='anon')
check('гость — нет доступа', st == 'ERR' and items(P2) == '5:-/2:-/5:-', out[-300:])
st, out = ret('ivan@x.ru', P2, 2)
check('сотрудник «Вернули» по 2 дням → «partial», принял сотрудник', st == 'OK' and 'partial' in out and items(P2) == '5:-/2:ivan@x.ru/5:-', items(P2))
r = rentals('client@x.ru', 'iva_my_rentals', O2)
check('таймер клиента: дрель и перфоратор (5 дней)', r == 'Дрель Makita (5 дн), Перфоратор (5 дн)#5', r)
st, out = ret('ivan@x.ru', P2, 5)
check('сотрудник «Вернули» по 5 дням → «closed»: комиссия (300 × 5 + 250 × 2 + 400 × 5) × 5% = 200',
      st == 'OK' and 'closed' in out and val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P2) == 'closed:200',
      val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P2))

# ── 4. своя заявка сотрудника, одинаковые сроки, тестовый клиент ──
O3 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 6}], who='ivan@x.ru')
P3 = val(srv, "select id from public.order_parts where order_id = %s" % O3)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P3)
st, out = ret('ivan@x.ru', P3, 3)
check('своя заявка сотрудника — «Свою заявку выдаёт и принимает другой сотрудник или владелец»',
      st == 'ERR' and 'Свою заявку выдаёт и принимает другой сотрудник или владелец' in out and items(P3) == '3:-/6:-', out[-300:])
st, out = ret('admin@x.ru', P3, 3)
check('…а владелец может', st == 'OK' and items(P3) == '3:admin@x.ru/6:-', items(P3))
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set status = 'closed' where id = %s" % P3)
check('штатная «Вернули» целиком (старое приложение) закрывает заявку, комиссия за всё: (300 × 3 + 250 × 6) × 5% = 120',
      val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P3) == 'closed:120',
      val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P3))
O4 = order([{'id': DRILL, 'days': 4}, {'id': GRIND, 'days': 4}])
P4 = val(srv, "select id from public.order_parts where order_id = %s" % O4)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P4)
st, out = ret('admin@x.ru', P4, 4)
check('один срок у всех — «Вернули» по сроку сразу закрывает: (300 + 250) × 4 × 5% = 110', st == 'OK' and 'closed' in out
      and val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P4) == 'closed:110')
O5 = uval(srv, '', "select public.iva_submit_order('Сайт', '+79990000000', 'Дрель Makita, Шлифмашина', 'Самовывоз', '', '', 'client@x.ru', 3, null)", role='anon')
P5 = val(srv, "select min(id) from public.order_parts where order_id = %s" % O5)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P5)
r = rentals('admin@x.ru', 'iva_admin_rentals', O5)
check('заявка с сайта (без списка): таймер как раньше', r.endswith('#3') and 'Дрель Makita' in r, r)
O8 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 6}], who='eclipsik.ru@mail.ru')
P8 = val(srv, "select id from public.order_parts where order_id = %s" % O8)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P8)
ret('admin@x.ru', P8, 3)
ret('admin@x.ru', P8, 6)
check('тестовый клиент: закрыта без комиссии', val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P8) == 'closed:0')
st, out = as_(srv, 'client@x.ru', "update public.order_parts set items = '[]'::jsonb where id = %s" % P8)
check('клиент сам список не меняет (защита как раньше)', items(P8) == '3:admin@x.ru/6:admin@x.ru', items(P8))

# ── 5. откат ──
O9 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 6}])
P9 = val(srv, "select id from public.order_parts where order_id = %s" % O9)
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % P9)
ret('admin@x.ru', P9, 3)
st, out = qs(srv, RBF)
check('откат 20261115_v124_rollback.sql — без ошибок, «✅»', st == 'OK' and out.count('✅') == 1 and '❌' not in out, out[-600:])
check('после отката: «Вернули» по сроку нет', ret('admin@x.ru', P9, 6)[0] == 'ERR')
r = rentals('admin@x.ru', 'iva_admin_rentals', O9)
check('после отката: таймер как в 10.122 (по самому длинному сроку)', r == 'Дрель Makita, Шлифмашина#6', r)
check('после отката: деление на части не вернулось', val(srv, "select count(*) from pg_trigger where tgname = 'trg_zzz_iva_part_split'") == '0')
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'closed' where id = %s" % P9)
check('после отката: штатная «Вернули» закрывает, комиссия (300 × 3 + 250 × 6) × 5% = 120',
      val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P9) == 'closed:120')
st, out = qs(srv, SQL)
check('после отката файл применяется снова', st == 'OK' and out.count('✅') == 4, out[-400:])

print('\nИтого: %s' % ('все проверки прошли' if fails == 0 else 'не прошло: %d' % fails))
sys.exit(1 if fails else 0)

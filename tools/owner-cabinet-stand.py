# Стенд «Кабинет владельца» (сайт и приложение 10.138, просьба владельца №11 от 06.11.2026):
# 20261127_owner_cabinet.sql (и откат) на настоящем PostgreSQL (pgserver).
#
# База как в стенде 10.125 (всё до 10.125) + fee_payments как на сайте. Проверяет: файл применяется и повторяется,
# все «✅»; сотрудника можно пригласить без имени (в списке пусто — сайт и приложение покажут почту); при «Принять» имя
# берётся из профиля сотрудника (ФИО профиля → имя из входа → часть почты); пустое имя при правке прежнее не стирает,
# старое приложение с именем — сохраняет; длинное имя — отказ; сотрудник поменял ФИО — имя у владельца обновилось,
# у клиента профиль сохраняется как раньше; комиссия: iva_fee_balance = формула приложения (все order_parts.fee минус
# подтверждённые оплаты), переплата, ждёт подтверждения, процент; чужой прокат — только свой; клиенту, гостю,
# владельцу площадки — нельзя; iva_fee_balances — только владельцу площадки, те же числа; служебные функции закрыты;
# откат возвращает «имя обязательно» и заполняет пустые имена; после отката SQL снова применяется.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/owner-cabinet-stand.py
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
V124 = rd(M + '20261115_v124.sql')
SQL = rd(M + '20261116_v125.sql')
RBF = rd(M + '20261116_v125_rollback.sql')
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
st, out = qs(srv, V124)
check('20261115_v124.sql (10.124) применяется', st == 'OK' and out.count('✅') == 4, out[-600:])
as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where id in ('%s', '%s')" % (SK, GRIND, DRILL))
as_(srv, 'admin@x.ru', ("insert into public.tools(name, owner_email, price, point_id, active, cat) values "
                        "('Перфоратор', 'admin@x.ru', 400, '%s', true, 'drill')") % SK)
PERF = val(srv, "select id from public.tools where name = 'Перфоратор'")


def order(items, who='client@x.ru'):
    return uval(srv, '', "select public.iva_submit_order('Иван Клиентов', '+79990000000', 'x', 'Самовывоз', '', "
                         "'', '%s', 3, '%s'::jsonb)" % (who, json.dumps(items)), role='anon')


def part(items, who='client@x.ru'):
    return val(srv, "select id from public.order_parts where order_id = %s" % order(items, who))


def issued(p, ago):
    """«Выдал» владельцем (штатно), потом время выдачи — ago назад (как будто выдали тогда)."""
    as_(srv, 'admin@x.ru', "update public.order_parts set status = 'rented' where id = %s" % p)
    q(srv, "set session_replication_role = replica; update public.order_parts set rented_at = now() - interval '%s' where id = %s; "
           "set session_replication_role = origin;" % (ago, p))


def close(p, who='admin@x.ru'):
    return as_(srv, who, "update public.order_parts set status = 'closed' where id = %s" % p)


def ret(email, p, d, role='authenticated'):
    return as_(srv, email, "select public.iva_staff_return(%s, %s)" % (p, d), role=role)


def items(p):
    """«бронь:начатые» по инструментам (‑ — ещё не посчитано)."""
    return val(srv, "select string_agg((e ->> 'days') || ':' || coalesce(e ->> 'used_days', '-'), '/' order by n) "
                    "from public.order_parts p, jsonb_array_elements(p.items) with ordinality as x(e, n) where p.id = %s" % p)


def money(p):
    return val(srv, "select status || ':' || coalesce(rent_sum::text, '-') || ':' || coalesce(fee::text, '-') from public.order_parts where id = %s" % p)


def card_items(email, scope, o):
    C = [json.loads(x) for x in uval(srv, email, "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('%s') j" % scope).split(' @@@ ') if x]
    out = []
    for c in C:
        if str(c.get('order_id')) != str(o):
            continue
        for p in c.get('parts') or [c]:
            out += p.get('items') or []
    return out


# заявка, закрытая ДО 10.125 (выдали 30 ч назад, бронь 3 суток) — сумма не должна поменяться
POLD = part([{'id': DRILL, 'days': 3}])
issued(POLD, '30 hours')
close(POLD)
OLD = money(POLD)
check('до 10.125: закрыта по брони (300 × 3 = 900, комиссия 45)', OLD == 'closed:900:45', OLD)


st, out = qs(srv, SQL)
check('20261116_v125.sql (10.125) применяется', st == 'OK' and out.count('✅') == 4, out[-600:])
st, out = q(srv, """
create table if not exists public.fee_payments(id bigint generated by default as identity primary key, admin_email text not null,
  amount numeric not null, status text not null default 'pending' check (status in ('pending', 'confirmed', 'rejected')),
  note text, created_at timestamptz default now(), confirmed_at timestamptz, confirmed_by text);
alter table public.fee_payments enable row level security;
alter table public.customer_profiles add column if not exists patronymic text not null default '';
""")
check('fee_payments как на сайте', st == 'OK', out[-400:])

OWN = rd(M + '20261127_owner_cabinet.sql')
ORB = rd(M + '20261127_owner_cabinet_rollback.sql')
st, out = qs(srv, OWN)
check('20261127_owner_cabinet.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все 4 строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = qs(srv, OWN)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])


def uid(email):
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, email))


def user(email, meta=None, fio=None):
    q(srv, "insert into auth.users(id, email) values ('%s', '%s') on conflict do nothing" % (uid(email), email))
    if meta is not None:
        q(srv, "alter table auth.users add column if not exists raw_user_meta_data jsonb; "
               "update auth.users set raw_user_meta_data = '%s'::jsonb where email = '%s'" % (json.dumps(meta, ensure_ascii=False), email))
    if fio:
        q(srv, "insert into public.customer_profiles(user_id, surname, given_name, patronymic) values ('%s', '%s', '%s', '%s')"
               % (uid(email), fio[0], fio[1], fio[2]))


def name_of(email):
    return val(srv, "select '[' || name || ']' from public.iva_staff where email = '%s'" % email)


def save_full(owner, sid, email, name, points, tel=''):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    nm = 'null' if name is None else "'%s'" % name
    s = 'null' if sid is None else "'%s'" % sid
    return as_(srv, owner, "select public.iva_staff_save(%s, '%s', %s, '%s', %s, true, false, false, true, false)" % (s, email, nm, tel, arr))


# ── 1. сотрудник без имени ──
user('nina@x.ru', fio=('Сидорова', 'Нина', 'Петровна'))
user('lev@x.ru', meta={'name': 'Лев Толстой'})
user('oleg@x.ru')
st, out = save_full('admin@x.ru', None, 'nina@x.ru', '', [SK])
check('пригласить без имени — можно', st == 'OK', out[-300:])
st, out = save_full('admin@x.ru', None, 'lev@x.ru', None, [SK])
check('пригласить без имени (имя null, как отправит сайт) — можно', st == 'OK', out[-300:])
st, out = save_full('admin@x.ru', None, 'oleg@x.ru', '', [BK])
check('третий без имени — можно', st == 'OK', out[-300:])
check('до «Принять» имя пустое (сайт и приложение покажут почту)', name_of('nina@x.ru') == '[]' and name_of('lev@x.ru') == '[]', name_of('nina@x.ru'))
lst = uval(srv, 'admin@x.ru', "select string_agg(email || '=' || name || '=' || status, ' ' order by email) from public.iva_staff_list()")
check('в списке владельца — почта, пустое имя, «Ждёт ответа»', 'nina@x.ru==invited' in lst and 'lev@x.ru==invited' in lst, lst)
st, out = save_full('admin@x.ru', None, 'long@x.ru', 'Я' * 81, [SK])
check('имя длиннее 80 — отказ', st == 'ERR' and 'до 80' in out, out[-300:])
st, out = save_full('admin@x.ru', None, 'old@x.ru', 'Старое Приложение', [SK])
check('приложение 10.137 с именем — сохраняет имя', st == 'OK' and name_of('old@x.ru') == '[Старое Приложение]', name_of('old@x.ru'))
NINA = val(srv, "select id from public.iva_staff where email = 'nina@x.ru'")
LEV = val(srv, "select id from public.iva_staff where email = 'lev@x.ru'")
OLEG = val(srv, "select id from public.iva_staff where email = 'oleg@x.ru'")
OLD = val(srv, "select id from public.iva_staff where email = 'old@x.ru'")

# ── 2. «Принять» — имя из профиля ──
check('«Принять»: ФИО из профиля клиента', uval(srv, 'nina@x.ru', "select public.iva_staff_answer('%s', true)" % NINA) == 'active'
      and name_of('nina@x.ru') == '[Сидорова Нина Петровна]', name_of('nina@x.ru'))
check('«Принять»: профиля нет — имя из входа', uval(srv, 'lev@x.ru', "select public.iva_staff_answer('%s', true)" % LEV) == 'active'
      and name_of('lev@x.ru') == '[Лев Толстой]', name_of('lev@x.ru'))
check('«Принять»: имени нигде нет — часть почты', uval(srv, 'oleg@x.ru', "select public.iva_staff_answer('%s', true)" % OLEG) == 'active'
      and name_of('oleg@x.ru') == '[oleg]', name_of('oleg@x.ru'))
check('«Принять»: имя от старого приложения остаётся, если в профиле пусто',
      uval(srv, 'old@x.ru', "select public.iva_staff_answer('%s', true)" % OLD) == 'active' and name_of('old@x.ru') == '[Старое Приложение]', name_of('old@x.ru'))
st, out = as_(srv, 'oleg@x.ru', "select public.iva_staff_answer('%s', false)" % OLEG)
check('уже сотрудник — отказаться нельзя (как раньше)', st == 'ERR' and 'уже сотрудник' in out, out[-300:])

# ── 3. правка без имени ──
st, out = save_full('admin@x.ru', NINA, 'nina@x.ru', '', [SK, BK], '+7 913 000-00-00')
check('правка без имени: имя прежнее, пункты и телефон обновились', st == 'OK' and name_of('nina@x.ru') == '[Сидорова Нина Петровна]'
      and val(srv, "select count(*) || ':' || (select tel from public.iva_staff where id = '%s') from public.iva_staff_points where staff_id = '%s'" % (NINA, NINA))
      == '2:+7 913 000-00-00', name_of('nina@x.ru'))
st, out = save_full('admin@x.ru', NINA, 'nina@x.ru', None, [SK])
check('правка с именем null — имя прежнее', st == 'OK' and name_of('nina@x.ru') == '[Сидорова Нина Петровна]', name_of('nina@x.ru'))
st, out = save_full('admin@x.ru', NINA, 'nina@x.ru', 'Нина С.', [SK])
check('старое приложение переименовало — имя новое', st == 'OK' and name_of('nina@x.ru') == '[Нина С.]', name_of('nina@x.ru'))
st, out = save_full('admin7@x.ru', NINA, 'nina@x.ru', '', [SK])
check('чужой прокат править не может', st == 'ERR', out[-300:])

# ── 4. сотрудник поменял ФИО в профиле ──
st, out = q(srv, "update public.customer_profiles set surname = 'Петрова' where user_id = '%s'" % uid('nina@x.ru'))
check('сотрудник сохранил профиль — без ошибок', st == 'OK', out[-300:])
check('имя у владельца обновилось само', name_of('nina@x.ru') == '[Петрова Нина Петровна]', name_of('nina@x.ru'))
user('client9@x.ru')
st, out = q(srv, "insert into public.customer_profiles(user_id, surname, given_name) values ('%s', 'Клиентов', 'Клим')" % uid('client9@x.ru'))
check('клиент (не сотрудник) сохраняет профиль как раньше', st == 'OK' and 'Клиентов' not in val(srv, 'select string_agg(name, \' \') from public.iva_staff'), out[-300:])
q(srv, "insert into public.iva_staff(owner_email, email, name, status) values ('admin@x.ru', 'wait@x.ru', '', 'invited')")
user('wait@x.ru')
q(srv, "insert into public.customer_profiles(user_id, surname, given_name) values ('%s', 'Ждущий', 'Вася')" % uid('wait@x.ru'))
check('не принявший приглашение — имя не подставляется до «Принять»', name_of('wait@x.ru') == '[]', name_of('wait@x.ru'))

# ── 5. комиссия ──
def bal(email, fn='iva_fee_balance'):
    raw = uval(srv, email, 'select public.%s()::text' % fn)
    try:
        return json.loads(raw)
    except Exception:
        return {'raw': raw}


def app_formula(email):
    """Как приложение 10.117–10.137: сумма fee всех своих строк order_parts минус подтверждённые оплаты."""
    fee = float(val(srv, "select coalesce(sum(fee), 0) from public.order_parts where owner_email = '%s'" % email))
    paid = float(val(srv, "select coalesce(sum(amount), 0) from public.fee_payments where status = 'confirmed' and admin_email = '%s'" % email))
    return fee, paid


PA = part([{'id': DRILL, 'days': 2}])
issued(PA, '30 hours')
close(PA)
PB = part([{'id': PERF, 'days': 1}])           # не закрыта — комиссии нет
PC = part([{'id': DRILL, 'days': 1}])
as_(srv, 'admin@x.ru', "update public.order_parts set status = 'cancelled' where id = %s" % PC)
fee, paid = app_formula('admin@x.ru')
b = bal('admin@x.ru')
check('начислено = формула приложения (все заявки, вместе с отменёнными и незакрытыми)', float(b.get('fee', -1)) == fee and fee > 0, [b, fee])
check('без оплат: к оплате = начислено, переплаты нет', float(b['debt']) == fee and float(b['over']) == 0 and float(b['paid']) == 0, b)
check('процент проката (пусто — 5)', float(b['pct']) == 5, b)
q(srv, "update public.admins set fee_pct = 7 where email = 'admin@x.ru'")
check('процент из admins.fee_pct', float(bal('admin@x.ru')['pct']) == 7, bal('admin@x.ru'))
q(srv, "insert into public.fee_payments(admin_email, amount, status) values ('admin@x.ru', 30, 'confirmed'), ('admin@x.ru', 500, 'pending'), "
       "('admin@x.ru', 999, 'rejected'), ('admin7@x.ru', 100, 'confirmed')")
b = bal('admin@x.ru')
check('частичная оплата: к оплате = начислено − подтверждённые', float(b['paid']) == 30 and float(b['debt']) == max(fee - 30, 0), b)
check('ждёт подтверждения — отдельно, в долг не идёт; отклонённые не считаются', float(b['pending']) == 500, b)
q(srv, "insert into public.fee_payments(admin_email, amount, status) values ('admin@x.ru', %s, 'confirmed')" % (fee + 93 - 30))
b = bal('admin@x.ru')
fee2, paid2 = app_formula('admin@x.ru')
check('переплата 93 ₽ — как в приложении («Переплата 93 ₽»)', float(b['over']) == 93 and float(b['debt']) == 0 and paid2 - fee2 == 93, b)
b7 = bal('admin7@x.ru')
check('другой прокат видит только своё (без заявок, оплатил 100 → переплата 100)', float(b7['fee']) == 0 and float(b7['over']) == 100
      and b7.get('email') == 'admin7@x.ru', b7)
st, out = as_(srv, 'client@x.ru', 'select public.iva_fee_balance()')
check('клиенту — нельзя', st == 'ERR' and 'только у владельца проката' in out, out[-300:])
st, out = as_(srv, 'nina@x.ru', 'select public.iva_fee_balance()')
check('сотруднику — нельзя', st == 'ERR', out[-300:])
st, out = as_(srv, '', 'select public.iva_fee_balance()', role='anon')
check('гостю — нельзя', st == 'ERR' and 'permission denied' in out, out[-300:])
st, out = as_(srv, 'chief@x.ru', 'select public.iva_fee_balance()')
check('владельцу площадки свой баланс не нужен — отказ', st == 'ERR', out[-300:])
all_ = bal('chief@x.ru', 'iva_fee_balances')
mine = [x for x in all_ if isinstance(all_, list) and x.get('email') == 'admin@x.ru'] if isinstance(all_, list) else []
check('владелец площадки: по каждому прокату, те же числа', mine and float(mine[0]['over']) == 93 and 'chief@x.ru' not in json.dumps(all_), all_)
st, out = as_(srv, 'admin@x.ru', 'select public.iva_fee_balances()')
check('владельцу проката чужие балансы — нельзя', st == 'ERR' and 'Только владелец площадки' in out, out[-300:])
for fn in ("iva_fee_calc('admin@x.ru')", "iva_staff_profile_name('nina@x.ru')"):
    st, out = as_(srv, 'admin@x.ru', 'select public.' + fn)
    check('служебную %s не вызвать' % fn.split('(')[0], st == 'ERR' and 'permission denied' in out, out[-200:])

# ── 6. откат ──
st, out = qs(srv, ORB)
check('откат применяется, все «✅»', st == 'OK' and out.count('✅') == 2 and '❌' not in out, out[-600:])
check('после отката пустые имена заполнены частью почты', name_of('wait@x.ru') == '[wait]', name_of('wait@x.ru'))
st, out = save_full('admin@x.ru', None, 'after@x.ru', '', [SK])
check('после отката имя снова обязательно (как 10.122)', st == 'ERR' and 'Укажите имя' in out, out[-300:])
check('после отката оплаты и заявки на месте', val(srv, 'select count(*) from public.fee_payments') == '5', val(srv, 'select count(*) from public.fee_payments'))
st, out = qs(srv, OWN)
check('после отката SQL снова применяется', st == 'OK' and out.count('✅') == 4, out[-400:])

print('Итого: ' + ('всё ✔' if not fails else '%d ✘' % fails))
sys.exit(1 if fails else 0)

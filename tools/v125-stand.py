# Стенд 10.125: 20261116_v125.sql (и откат 20261116_v125_rollback.sql) на настоящем PostgreSQL (pgserver).
#
# База как в рабочей: всё до 10.124 (пункты, сотрудники, 20261104–20261115, чужой trg_tool_owner). Проверяет чек по
# факту (просьба владельца 18.10.2026): каждый инструмент — по начатым суткам от «Выдал» до «Вернули», запас 1 час,
# минимум 1 сутки, просрочка досчитывается; во всех заявках (один срок, разные сроки, «Вернули» по сроку и штатная,
# владелец и сотрудник, «до закрытия пункта»); не выданная — по брони; уже закрытые не меняются; тестовый клиент без
# комиссии; клиент сам ничего не меняет; заявка целиком отдаёт начатые сутки; откат и повторное применение.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/v125-stand.py
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
check('20261116_v125.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все 4 строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = qs(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
check('уже закрытая заявка не изменилась', money(POLD) == OLD and items(POLD) == '3:-', money(POLD) + ' ' + items(POLD))

# ── 1. пример владельца: 1-й инструмент на 1 сутки, 2-й на 2 суток, оба вернули до начала вторых суток ──
P1 = part([{'id': DRILL, 'days': 1}, {'id': GRIND, 'days': 2}])
issued(P1, '20 hours')
st, out = ret('admin@x.ru', P1, 1)
check('«Вернули» по 1 суткам через 20 ч — partial, у дрели 1 сутки', st == 'OK' and 'partial' in out and items(P1) == '1:1/2:-', items(P1))
st, out = ret('admin@x.ru', P1, 2)
check('«Вернули» по 2 суткам через 20 ч — закрыта, у шлифмашины тоже 1 сутки', st == 'OK' and 'closed' in out and items(P1) == '1:1/2:1', items(P1))
check('чек пересчитан: 300 × 1 + 250 × 1 = 550 (было бы 800), комиссия 28', money(P1) == 'closed:550:28', money(P1))
snap = val(srv, "select price_snapshot::text from public.order_parts where id = %s" % P1)
check('снимок цен: начатые сутки и бронь', '"days": 1' in snap and '"booked": 2' in snap, snap)
O1 = val(srv, "select order_id from public.order_parts where id = %s" % P1)
ci = card_items('client@x.ru', 'client', O1)
check('клиент в заявке видит начатые сутки у каждого инструмента (без почты принявшего)',
      [c.get('used_days') for c in ci] == [1, 1] and 'admin@x.ru' not in json.dumps(ci), ci)
ai = card_items('admin@x.ru', 'admin', O1)
check('владелец — то же', [c.get('used_days') for c in ai] == [1, 1], ai)

# ── 2. один срок, вернули раньше — штатная «Вернули» целиком ──
P2 = part([{'id': DRILL, 'days': 3}, {'id': PERF, 'days': 3}])
issued(P2, '30 hours')
st, out = close(P2)
check('бронь 3 суток, вернули через 30 ч штатной «Вернули» → по 2 суток: (300 + 400) × 2 = 1 400, комиссия 70',
      st == 'OK' and money(P2) == 'closed:1400:70' and items(P2) == '3:2/3:2', money(P2) + ' ' + items(P2))
check('у инструментов отмечено время возврата', val(srv, "select count(*) from public.order_parts p, jsonb_array_elements(p.items) e "
                                                         "where p.id = %s and coalesce(e ->> 'returned_at', '') <> ''" % P2) == '2')

# ── 3. запас 1 час и минимум 1 сутки ──
P3 = part([{'id': DRILL, 'days': 1}])
issued(P3, '24 hours 40 minutes')
close(P3)
check('вернули через 24 ч 40 мин — ещё 1 сутки (запас час): 300', money(P3) == 'closed:300:15' and items(P3) == '1:1', money(P3))
P4 = part([{'id': DRILL, 'days': 1}])
issued(P4, '25 hours 5 minutes')
close(P4)
check('через 25 ч 05 мин — 2 суток (просрочка досчитана): 600', money(P4) == 'closed:600:30' and items(P4) == '1:2', money(P4))
P5 = part([{'id': DRILL, 'days': 5}])
issued(P5, '1 hour')
close(P5)
check('через час после выдачи — минимум 1 сутки: 300', money(P5) == 'closed:300:15' and items(P5) == '5:1', money(P5))

# ── 4. просрочка ──
P6 = part([{'id': GRIND, 'days': 2}])
issued(P6, '3 days 2 hours')
close(P6)
check('бронь 2 суток, вернули через 3 суток 2 ч → 4 начатых суток: 250 × 4 = 1 000', money(P6) == 'closed:1000:50' and items(P6) == '2:4', money(P6))

# ── 5. сотрудник, разные сроки: одному просрочка, другим — раньше ──
P7 = part([{'id': DRILL, 'days': 1}, {'id': GRIND, 'days': 3}, {'id': PERF, 'days': 3}])
issued(P7, '50 hours')
st, out = ret('ivan@x.ru', P7, 1)
check('сотрудник «Вернули» по 1 суткам через 50 ч → у дрели 3 суток (просрочка)', st == 'OK' and items(P7) == '1:3/3:-/3:-', items(P7))
st, out = ret('ivan@x.ru', P7, 3)
check('сотрудник «Вернули» по 3 суткам → закрыта, всем по 3: (300 + 250 + 400) × 3 = 2 850, комиссия 143',
      st == 'OK' and 'closed' in out and money(P7) == 'closed:2850:143' and items(P7) == '1:3/3:3/3:3', money(P7) + ' ' + items(P7))

# ── 6. «Вернули» по сроку, потом штатная «Вернули» целиком — у остальных сутки по времени закрытия ──
P8 = part([{'id': DRILL, 'days': 2}, {'id': GRIND, 'days': 5}])
issued(P8, '26 hours')
ret('admin@x.ru', P8, 2)
q(srv, "set session_replication_role = replica; update public.order_parts set rented_at = rented_at - interval '2 days' where id = %s; "
       "set session_replication_role = origin;" % P8)
close(P8)
check('дрель вернули на 2-е сутки (2), шлифмашину закрыли ещё через 2 дня (4): 300 × 2 + 250 × 4 = 1 600',
      money(P8) == 'closed:1600:80' and items(P8) == '2:2/5:4', money(P8) + ' ' + items(P8))

# ── 7. «до закрытия пункта» ──
q(srv, "insert into public.iva_rent_settings(email, mode, close_min, tz) values ('admin@x.ru', 'close', '{1439,1439,1439,1439,1439,1439,1439}', 'Europe/Moscow') "
       "on conflict (email) do update set mode = 'close', close_min = excluded.close_min")
q(srv, "update public.iva_pickup_points set close_min = null where id = '%s'" % SK)
P9 = part([{'id': DRILL, 'days': 3}])
issued(P9, '10 minutes')
close(P9)
check('«до закрытия пункта»: вернули через 10 мин — 1 сутки', money(P9) == 'closed:300:15' and items(P9) == '3:1', money(P9))
r = val(srv, "select public.iva_used_days('2026-10-17 12:00+03', '2026-10-19 19:30+03', 'close', '{1140,1140,1140,1140,1140,1140,-1}', 'Europe/Moscow') "
             "|| '/' || public.iva_used_days('2026-10-17 12:00+03', '2026-10-21 19:00+03', 'close', '{1140,1140,1140,1140,1140,1140,-1}', 'Europe/Moscow') "
             "|| '/' || public.iva_used_days('2026-10-17 12:00+03', '2026-10-18 10:00+03', 'close', '{-1,-1,-1,-1,-1,-1,-1}', 'Europe/Moscow')")
check('«до закрытия пункта» (вс — выходной): пн 19:30 → 1, ср 19:00 → 3; все дни выходные — как «Сутки»', r == '1/3/1', r)
q(srv, "delete from public.iva_rent_settings where email = 'admin@x.ru'")

# ── 8. не выданная, тестовый клиент, защита ──
P10 = part([{'id': DRILL, 'days': 3}])
close(P10)
check('закрыли без «Выдал» — по брони: 300 × 3 = 900', money(P10) == 'closed:900:45' and items(P10) == '3:-', money(P10))
P11 = part([{'id': DRILL, 'days': 1}, {'id': GRIND, 'days': 2}], who='eclipsik.ru@mail.ru')
issued(P11, '3 hours')
ret('admin@x.ru', P11, 1)
ret('admin@x.ru', P11, 2)
check('тестовый клиент: начатые сутки посчитаны, комиссии нет', money(P11) == 'closed:550:0' and items(P11) == '1:1/2:1', money(P11))
P12 = part([{'id': DRILL, 'days': 3}])
issued(P12, '2 hours')
st, out = as_(srv, 'client@x.ru', "update public.order_parts set items = '[{\"id\": \"%s\", \"days\": 3, \"used_days\": 0, \"price\": 1}]'::jsonb where id = %s" % (DRILL, P12))
check('клиент сам начатые сутки не подставит', items(P12) == '3:-', items(P12))
st, out = as_(srv, 'client@x.ru', "select public.iva_part_used_days('admin@x.ru', null, now(), now())")
check('служебная функция клиенту закрыта', st == 'ERR' and 'permission denied' in out, out[-200:])
check('гостю — тоже', as_(srv, '', "select public.iva_used_days(now(), now(), 'day', null, 'x')", role='anon')[0] == 'ERR')
close(P12)
check('…и чек по факту: 300 × 1', money(P12) == 'closed:300:15', money(P12))

# ── 9. откат ──
st, out = qs(srv, RBF)
check('откат 20261116_v125_rollback.sql — без ошибок, «✅»', st == 'OK' and out.count('✅') == 1 and '❌' not in out, out[-600:])
P13 = part([{'id': DRILL, 'days': 1}, {'id': GRIND, 'days': 2}])
issued(P13, '20 hours')
st, out = ret('admin@x.ru', P13, 1)
check('после отката «Вернули» по сроку работает (10.124), сутки не считаются', st == 'OK' and items(P13) == '1:-/2:-', items(P13))
ret('admin@x.ru', P13, 2)
check('после отката чек по брони: 300 + 250 × 2 = 800', money(P13) == 'closed:800:40', money(P13))
check('после отката прошлые суммы не изменились', money(P1) == 'closed:550:28' and money(POLD) == OLD)
st, out = qs(srv, SQL)
check('после отката файл применяется снова', st == 'OK' and out.count('✅') == 4, out[-400:])

print('\nИтого: %s' % ('все проверки прошли' if fails == 0 else 'не прошло: %d' % fails))
sys.exit(1 if fails else 0)

# Стенд этапа 4 «Сотрудники и пункты выдачи» (10.107): 20261101_staff_journal.sql на настоящем PostgreSQL (pgserver).
#
# База как в стендах 10.104 и 10.106 (заявки ТОЧНО как в рабочей базе по снимку 17.10: триггеры комиссии, журнал
# статусов, правила доступа; чат «только своё»), затем 20261014, пункты, 20261026–20261029, 20261031 и 20261101.
# Проверяет: «Выдал» / «Вернули» записывают, кто нажал (сотрудник, владелец), вручную не подделать; старые заявки — из
# прежнего журнала; журнал части — только владельцу (и создателю), имена: «Вы», имя сотрудника, почта убранного;
# клиент, гость, сотрудник, другой прокат — не видят; «Сводка по пунктам» — только владельцу, цифры по пунктам и
# периоду, кто сколько выдал и принял, без почты клиента; имя сотрудника под его ответом в чате — только владельцу,
# после «Убрать» — «бывший сотрудник»; откат из шапки; повторное применение; без SQL 10.106 — понятная ошибка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-journal-stand.py
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
ORD = rd('supabase/migrations/20261029_staff_orders.sql')
CHAT = rd('supabase/migrations/20261031_staff_chat.sql')
SQL = rd('supabase/migrations/20261101_staff_journal.sql')
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



CHATX = r"""
alter table public.tools add column if not exists img text;
alter table public.tools add column if not exists terms text;
create table auth.users(id uuid primary key, email text);
create function auth.uid() returns uuid language sql stable as $$ select nullif(auth.jwt() ->> 'sub', '')::uuid $$;
grant execute on function auth.uid() to anon, authenticated;
create table public.reviews (
  id uuid primary key default gen_random_uuid(),
  tool_id uuid not null references public.tools(id) on delete cascade,
  author_id uuid not null references auth.users(id) on delete cascade,
  author_email text not null default '', author_name text not null default '',
  rating integer not null check (rating >= 1 and rating <= 5),
  text text not null default '' check (char_length(text) <= 1000),
  status text not null default 'published' check (status in ('published', 'hidden')),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  constraint uq_reviews_tool_author unique (tool_id, author_id));
alter table public.reviews enable row level security;
create policy reviews_select on public.reviews for select to anon, authenticated using (status = 'published' or author_id = auth.uid());
create table public.app_messages(id bigserial primary key, from_email text not null, to_email text not null, text text default '',
  img text default '', audio text default '', audio_dur numeric default 0, is_read boolean default false,
  created_at timestamptz default now(), tool_id uuid references public.tools(id) on delete set null, topic text);
alter table public.app_messages enable row level security;
grant select, insert, update, delete on public.app_messages to authenticated;
grant usage on all sequences in schema public to authenticated;
create policy msg_own_sel on public.app_messages for select to authenticated
  using (public.jwt_email() in (lower(btrim(from_email)), lower(btrim(to_email))));
create policy msg_own_ins on public.app_messages for insert to authenticated with check (lower(btrim(from_email)) = public.jwt_email());
create policy msg_own_upd on public.app_messages for update to authenticated using (lower(btrim(to_email)) = public.jwt_email());
create table public.customer_profiles(user_id uuid primary key references auth.users(id) on delete cascade,
  surname text not null default '', given_name text not null default '', phone text not null default '',
  passport_data jsonb not null default '{}'::jsonb);
alter table public.customer_profiles enable row level security;
insert into auth.users values ('11111111-1111-1111-1111-111111111111', 'client@x.ru'), ('22222222-2222-2222-2222-222222222222', 'client2@x.ru');
insert into public.customer_profiles(user_id, surname, given_name, phone, passport_data)
  values ('11111111-1111-1111-1111-111111111111', 'Клиентова', 'Анна', '+79991112233', '{"number": "1234 567890"}');
"""


def md5(s):
    import hashlib
    return hashlib.md5(s.encode()).hexdigest()


def say(srv, frm, to, text, tool=None, extra=''):
    """Сообщение «как из приложения»: вошёл frm, пишет сам (правило «только своё»)."""
    return as_(srv, frm, "insert into public.app_messages(from_email, to_email, text, tool_id%s) values ('%s', '%s', '%s', %s%s)"
               % (', audio' if extra else '', frm, to, text.replace("'", "''"), "'%s'" % tool if tool else 'null',
                  ", '%s'" % extra if extra else ''))


def send(srv, email, tool, peer, text):
    return as_(srv, email, "select public.iva_staff_chat_send('%s', '%s', '%s')" % (tool, peer, text.replace("'", "''")))


def journal(srv, email, pid, role='authenticated'):
    """Журнал части: «состояние|кто выдал|время есть|кто принял|время есть»."""
    return uval(srv, email, "select status || '|' || issued_who || '|' || (issued_at is not null) || '|' || returned_who || '|' "
                            "|| (returned_at is not null) from public.iva_part_journal(%s)" % pid, role=role)


def summary(srv, email, days):
    return uval(srv, email, "select public.iva_points_summary(%s)::text" % ('null' if days is None else days))


def pts(js):
    try:
        return {p['name']: p for p in json.loads(js)['points']}
    except Exception:
        return {}


def owner_set(srv, pid, st):
    return as_(srv, 'admin@x.ru', "update public.order_parts set status = '%s' where id = %s" % (st, pid))


# ── 1. база: заявки, сотрудники, чат; одна заявка закрыта ДО нового SQL (старый журнал) ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + CHATX)
check('исходная база как в бою (заявки по снимку 17.10, чат «только своё»)', st == 'OK', out[-600:])
check('SQL 10.77, 10.95, 10.99, 10.101 применяются', all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Пушкина, 3'))
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
BERDSK = val(srv, "select id from public.iva_pickup_points where name = 'Бердск'")
ISK = val(srv, "select id from public.iva_pickup_points where name = 'Искитим'")
q(srv, ("insert into public.tools(name, owner_email, price, point_id, active) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}', true), ('Пила', 'admin@x.ru', 500, '{b}', true), "
        "('Перфоратор', 'admin@x.ru', 400, '{s}', false), ('Дрель', 'admin2@x.ru', 100, '{i}', true), "
        "('Лобзик', 'admin@x.ru', 200, null, true)").format(s=SKLAD, b=BERDSK, i=ISK))
DRILL, SAW, D2 = tid(srv, 'Дрель Makita', 'admin@x.ru'), tid(srv, 'Пила', 'admin@x.ru'), tid(srv, 'Дрель', 'admin2@x.ru')
for name, s in (('10.102', OP), ('10.103', STAFF), ('10.104', ORD), ('10.106', CHAT)):
    st, out = q(srv, s)
    check('SQL %s применяется' % name, st == 'OK', out[-600:])
q(srv, "alter table public.tools add column if not exists review text")   # как SQL 10.105 (без его правил доступа)
q(srv, "update public.tools set review = 'pending' where name = 'Перфоратор'")

IVAN = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [SKLAD], True, False, True, True, False)
OLGA = save(srv, 'admin@x.ru', None, 'olga@x.ru', 'Ольга', [BERDSK], True, False, False, True, False)
MASHA = save(srv, 'admin@x.ru', None, 'masha@x.ru', 'Маша', [SKLAD], True, False, False, True, False)
GOSHA = save(srv, 'admin2@x.ru', None, 'gosha@x.ru', 'Гоша', [ISK], True, False, False, True, False)
for em, sid in (('ivan@x.ru', IVAN), ('olga@x.ru', OLGA), ('gosha@x.ru', GOSHA)):
    accept(srv, em, sid)

o0 = submit(srv, 'Дрель Makita (1 день)', [(DRILL, 1)])
P0 = part_of(srv, o0, 'Склад')
r1, r2 = owner_set(srv, P0, 'rented'), owner_set(srv, P0, 'closed')
check('до нового SQL: старая заявка выдана и закрыта владельцем', r1[0] == 'OK' and r2[0] == 'OK'
      and val(srv, "select status from public.order_parts where id = %s" % P0) == 'closed', (r1, r2))
o1 = submit(srv, 'Дрель Makita (3 дня), Пила (5 дней)', [(DRILL, 3), (SAW, 5)])
o2 = submit(srv, 'Дрель (1 день)', [(D2, 1)])
P1S, P1B, P2I = part_of(srv, o1, 'Склад'), part_of(srv, o1, 'Бердск'), part_of(srv, o2, 'Искитим')
r = mark(srv, 'ivan@x.ru', P1S, 'rented')
check('до нового SQL: Иван выдал (SQL 10.104)', r[0] == 'OK', r)

# ── 2. применение, повтор, старые заявки ──
st, out = q(srv, SQL)
check('20261101_staff_journal.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])
r = val(srv, "select coalesce(issued_by, '-') || '|' || coalesce(returned_by, '-') from public.order_parts where id = %s" % P0)
check('старая закрытая заявка: кто выдал и принял — из прежнего журнала', r == 'admin@x.ru|admin@x.ru', r)
r = val(srv, "select coalesce(issued_by, '-') || '|' || coalesce(returned_by, '-') from public.order_parts where id = %s" % P1S)
check('старая выданная заявка: выдал Иван, ещё не принята', r == 'ivan@x.ru|-', r)
check('новая заявка: пусто', val(srv, "select coalesce(issued_by, '-') from public.order_parts where id = %s" % P1B) == '-')

# ── 3. журнал пишет база ──
r = owner_set(srv, P1S, 'closed')
check('владелец принял — записано «admin@x.ru» и время', r[0] == 'OK' and val(srv,
      "select returned_by || '|' || (returned_at is not null) from public.order_parts where id = %s" % P1S) == 'admin@x.ru|true', r)
r = mark(srv, 'olga@x.ru', P1B, 'rented')
check('Ольга выдала через «Выдал» сотрудника — записано «olga@x.ru»', r[0] == 'OK'
      and val(srv, "select issued_by from public.order_parts where id = %s" % P1B) == 'olga@x.ru', r)
r = as_(srv, 'admin@x.ru', "update public.order_parts set issued_by = 'chuzhoy@x.ru', returned_by = 'x@x.ru', "
                           "returned_at = now() - interval '1 year' where id = %s" % P1B)
check('владелец не подделает «кто выдал / принял» напрямую', val(srv,
      "select issued_by || '|' || coalesce(returned_by, '-') || '|' || (returned_at is null) from public.order_parts where id = %s" % P1B)
      == 'olga@x.ru|-|true', r)
r = as_(srv, 'admin@x.ru', "update public.order_parts set archived = archived where id = %s" % P1B)
check('прочие правки части — не стирают журнал', r[0] == 'OK'
      and val(srv, "select issued_by from public.order_parts where id = %s" % P1B) == 'olga@x.ru', r)
r = mark(srv, 'olga@x.ru', P1B, 'closed')
check('Ольга приняла — записано «olga@x.ru»', r[0] == 'OK'
      and val(srv, "select returned_by from public.order_parts where id = %s" % P1B) == 'olga@x.ru', r)
check('сотрудник таблицу заявок не читает (как раньше)', uval(srv, 'olga@x.ru', "select count(*) from public.order_parts") == '0')
check('клиент таблицу заявок не читает (как раньше)', uval(srv, 'client@x.ru', "select count(*) from public.order_parts") == '0')
cols = val(srv, "select string_agg(n, ',') from (select unnest(proargnames) n from pg_proc where proname = 'iva_staff_parts') x")
check('в заявках сотрудника по-прежнему нет «кто выдал» (почт)', cols and 'issued_by' not in cols and 'returned_by' not in cols, cols)

# ── 4. журнал части — владельцу ──
r = journal(srv, 'admin@x.ru', P1S)
check('владелец: «Выдал: Иван», «Принял: Вы», время есть', r == 'closed|Иван|true|Вы|true', r)
r = journal(srv, 'admin@x.ru', P1B)
check('владелец: Бердск — выдала и приняла Ольга', r == 'closed|Ольга|true|Ольга|true', r)
r = journal(srv, 'admin@x.ru', P0)
check('владелец: старая заявка — «Вы» (время выдачи из 10.77)', r == 'closed|Вы|true|Вы|false', r)
r = journal(srv, 'chief@x.ru', P1S)
check('создатель видит: «Иван», «Владелец»', r == 'closed|Иван|true|Владелец|true', r)
check('другой прокат — пусто', journal(srv, 'admin2@x.ru', P1S) == '')
check('сотрудник — пусто', journal(srv, 'ivan@x.ru', P1S) == '')
check('клиент — пусто', journal(srv, 'client@x.ru', P1S) == '')
check('без входа — пусто', journal(srv, '', P1S) == '')
r = as_(srv, '', "select * from public.iva_part_journal(%s)" % P1S, role='anon')
check('гость — нет права', err_has(r, 'permission denied'), r)
r = as_(srv, 'admin@x.ru', "select public.iva_journal_who('admin@x.ru', 'ivan@x.ru')")
check('служебная функция имён закрыта', err_has(r, 'permission denied'), r)

# ── 5. «Сводка по пунктам» ──
js = summary(srv, 'admin@x.ru', 30)
P = pts(js)
check('сводка: пункты владельца по алфавиту и «Без пункта» последним',
      js and [p['name'] for p in json.loads(js)['points']] == ['Бердск', 'Склад', 'Без пункта'], js[:300])
s = P.get('Склад', {})
TS = "select count(*) from public.tools where owner_email = 'admin@x.ru' and point_id = '%s'" % SKLAD
check('Склад: инструменты — все, видят клиенты (без скрытого и ждущего), на одобрении 1; сотрудников 1, приглашён 1',
      (s.get('tools'), s.get('shown'), s.get('pending'), s.get('staff'), s.get('invited'))
      == (int(val(srv, TS)), int(val(srv, TS + " and active and review is null")), 1, 1, 1) and s.get('tools', 0) > s.get('shown', 0) >= 1, s)
check('Склад: за 30 дней 2 заявки, закрыто 2, сумма аренды и комиссия как в части',
      (s.get('parts'), s.get('closed'), s.get('new'), s.get('rented')) == (2, 2, 0, 0)
      and float(s.get('rent', 0)) == float(val(srv, "select sum(rent_sum) from public.order_parts where point_id = '%s'" % SKLAD))
      and float(s.get('fee', -1)) == float(val(srv, "select sum(fee) from public.order_parts where point_id = '%s'" % SKLAD)), s)
check('Склад: кто сколько выдал и принял — Вы 1/2, Иван 1/0',
      s.get('people') == [{'who': 'Вы', 'issued': 1, 'returned': 2}, {'who': 'Иван', 'issued': 1, 'returned': 0}], s.get('people'))
b = P.get('Бердск', {})
check('Бердск: Ольга выдала 1, приняла 1', b.get('people') == [{'who': 'Ольга', 'issued': 1, 'returned': 1}], b)
n = P.get('Без пункта', {})
check('«Без пункта»: инструмент без пункта, без сотрудников', (n.get('tools'), n.get('staff'), n.get('id')) == (1, 0, ''), n)
check('в сводке нет почты и телефона клиента', js and 'client@x.ru' not in js and '7999' not in js and '@' not in js, js[:300])
o3 = submit(srv, 'Пила (2 дня)', [(SAW, 2)])
P3 = part_of(srv, o3, 'Бердск')
q(srv, "update public.order_parts set created_at = now() - interval '40 days' where id = %s" % P1S)
P = pts(summary(srv, 'admin@x.ru', 30))
check('новая заявка — в «Новые сейчас»', P.get('Бердск', {}).get('new') == 1, P.get('Бердск'))
check('заявка 40 дней назад — не в 30 днях (но закрыта в периоде)', P.get('Склад', {}).get('parts') == 1, P.get('Склад'))
P = pts(summary(srv, 'admin@x.ru', 0))
check('за всё время — все заявки', P.get('Склад', {}).get('parts') == 2, P.get('Склад'))
P = pts(summary(srv, 'admin@x.ru', None))
check('пусто — тоже за всё время', P.get('Склад', {}).get('parts') == 2, P.get('Склад'))
q(srv, "alter table public.order_parts disable trigger trg_b_iva_part_journal; "
       "update public.order_parts set returned_at = now() - interval '40 days' where id = %s; "
       "alter table public.order_parts enable trigger trg_b_iva_part_journal" % P1S)
P = pts(summary(srv, 'admin@x.ru', 30))
check('принятая 40 дней назад — не в «закрыто» за 30 дней, Вы приняли 1', P.get('Склад', {}).get('closed') == 1
      and P.get('Склад', {}).get('people') == [{'who': 'Вы', 'issued': 1, 'returned': 1}, {'who': 'Иван', 'issued': 1, 'returned': 0}],
      P.get('Склад'))
P = pts(summary(srv, 'admin2@x.ru', 30))
check('другой прокат видит только свой пункт', list(P) == ['Искитим'], list(P))
for who, em in (('сотрудник', 'ivan@x.ru'), ('клиент', 'client@x.ru')):
    r = as_(srv, em, "select public.iva_points_summary(30)")
    check('%s — «Только владелец проката»' % who, err_has(r, 'Только владелец проката'), r)
r = as_(srv, '', "select public.iva_points_summary(30)", role='anon')
check('гость — нет права', err_has(r, 'permission denied'), r)
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
r = as_(srv, 'admin@x.ru', "select public.iva_points_summary(30)")
check('выключенный прокат — сводки нет', err_has(r, 'Только владелец проката'), r)
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")

# ── 6. имя сотрудника под ответом в чате владельца ──
say(srv, 'client@x.ru', 'admin@x.ru', 'Дрель свободна?', DRILL)
r = send(srv, 'ivan@x.ru', DRILL, md5('client@x.ru'), 'Да, свободна')
check('Иван ответил клиенту (SQL 10.106)', r[0] == 'OK', r)
say(srv, 'admin@x.ru', 'client@x.ru', 'Приезжайте', DRILL)
IDS = val(srv, "select string_agg(id::text, ',' order by id) from public.app_messages")
arr = "array[%s]::text[]" % ','.join("'%s'" % i for i in IDS.split(','))
names = lambda em: uval(srv, em, "select coalesce(string_agg(m.text || '=' || n.who, ' ; ' order by n.id::bigint), '') "
                                 "from public.iva_chat_staff_names(%s) n join public.app_messages m on m.id::text = n.id" % arr) \
    if em != 'client@x.ru' else uval(srv, em, "select count(*) from public.iva_chat_staff_names(%s)" % arr)
check('владелец: «Да, свободна» написал Иван, свои ответы — без имени', names('admin@x.ru') == 'Да, свободна=Иван', names('admin@x.ru'))
check('клиент имён не получает', names('client@x.ru') == '0')
check('другой прокат — пусто', uval(srv, 'admin2@x.ru', "select count(*) from public.iva_chat_staff_names(%s)" % arr) == '0')
check('сотрудник — пусто', uval(srv, 'ivan@x.ru', "select count(*) from public.iva_chat_staff_names(%s)" % arr) == '0')
big = "array(select g::text from generate_series(1, 301) g)"
check('больше 300 номеров — пусто', uval(srv, 'admin@x.ru', "select count(*) from public.iva_chat_staff_names(%s)" % big) == '0')
r = as_(srv, '', "select * from public.iva_chat_staff_names(%s)" % arr, role='anon')
check('гость — нет права', err_has(r, 'permission denied'), r)

# ── 7. «Убрать из сотрудников» ──
r = uval(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % IVAN)
check('Ивана убрали', val(srv, "select count(*) from public.iva_staff where id = '%s'" % IVAN) == '0', r)
check('ответ Ивана в чате: «бывший сотрудник», номер сохранён', names('admin@x.ru') == 'Да, свободна=бывший сотрудник'
      and val(srv, "select count(*) from public.app_messages where staff_id = '%s'" % IVAN) == '1', names('admin@x.ru'))
r = journal(srv, 'admin@x.ru', P1S)
check('журнал: вместо имени убранного — его почта', r == 'closed|ivan@x.ru|true|Вы|true', r)

# ── 8. откат и снова ──
n0 = val(srv, 'select count(*) from public.order_parts')
st, out = q(srv, RB)
check('откат выполняется без ошибок', st == 'OK', out[-600:])
check('после отката: функций, триггера и столбцов нет, заявки на месте, связь сообщений с сотрудником снова есть',
      val(srv, "select count(*) from pg_proc where proname in ('iva_part_journal', 'iva_points_summary', 'iva_chat_staff_names', "
               "'iva_journal_who', 'iva_part_journal_trg')") == '0'
      and val(srv, "select count(*) from information_schema.columns where table_name = 'order_parts' "
                   "and column_name in ('issued_by', 'returned_by', 'returned_at')") == '0'
      and val(srv, "select count(*) from pg_constraint where conname = 'app_messages_staff_fk'") == '1'
      and val(srv, 'select count(*) from public.order_parts') == n0
      and val(srv, "select count(*) from public.app_messages where staff_id is not null") == '0')
r = owner_set(srv, P3, 'rented')
check('после отката владелец выдаёт как раньше', r[0] == 'OK', r)
st, out = q(srv, SQL)
check('после отката применяется снова', st == 'OK' and out.count('✅') == 4, out[-600:])
srv.cleanup()

# ── 9. без SQL 10.106 — понятная ошибка ──
srv = mk()
q(srv, BASELINE + EXTRA + PROD + CHATX)
for s in (RENT, PICK, TEL, TP, OP, STAFF):
    q(srv, s)
st, out = q(srv, SQL)
check('без SQL 10.106 — понятная ошибка', st == 'ERR' and 'Сначала примените 20261031_staff_chat.sql' in out, out[-400:])
srv.cleanup()

print('\nИтого: %s' % ('всё ✔' if not fails else '%d ✘' % fails))
sys.exit(1 if fails else 0)

# Стенд миграции 20261014_rent_reminder.sql (10.76) на настоящем PostgreSQL (pgserver из PyPI).
#
# Проверяет: время выдачи ставит сама база, когда статус части заявки стал «rented» (в приложении или на сайте —
# одинаково, они пишут только статус); вручную его не подделать; функция iva_my_rentals отдаёт клиенту только
# его выданные инструменты, гостю недоступна; повторный запуск SQL ничего не ломает.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/rent-reminder-stand.py
import json
import os
import sys
import tempfile

import pgserver

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SQL = open(os.path.join(ROOT, 'supabase/migrations/20261014_rent_reminder.sql'), encoding='utf-8').read()

srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
fails = 0


def q(sql):
    out = srv.psql(sql).strip()
    err = any(line.startswith('ERROR') for line in out.splitlines())
    return ('ERR' if err else 'OK'), out


def as_user(email, sql, role='authenticated'):
    """От имени вошедшего (или гостя), с commit. Ошибка внутри — psql отвечает ROLLBACK вместо COMMIT."""
    c = json.dumps({'email': email, 'role': role} if email else {'role': role}).replace("'", "''")
    st, out = q("begin; set local role %s; set local request.jwt.claims = '%s'; %s commit;" % (role, c, sql))
    if out.rstrip().endswith('ROLLBACK'):
        st = 'ERR'
    return st, out


def ids(out):
    """Первая колонка строк результата psql (part_id)."""
    res = []
    for line in out.splitlines():
        cells = [x.strip() for x in line.split('|')]
        if line.startswith(' ') and cells[0].isdigit():
            res.append(cells[0])
    return res


def check(name, cond, extra=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + ('\n    ' + extra.replace('\n', '\n    ') if extra else ''))


BASELINE = """
create role anon nologin;
create role authenticated nologin;
create schema auth;
create function auth.jwt() returns jsonb language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb $$;
grant usage on schema auth to anon, authenticated;
grant execute on function auth.jwt() to anon, authenticated;
grant usage on schema public to anon, authenticated;
-- как в Supabase: новые функции в public сразу доступны anon и authenticated — закрывать надо явно
alter default privileges in schema public grant execute on functions to anon, authenticated;
-- как в бою: заявка и её части по прокатам (столбцы — из запросов сайта assets/sb.js)
create table public.orders(id bigserial primary key, name text, phone text, user_email text, tools text,
  get_method text, address text, comment text, days int, created_at timestamptz default now());
create table public.order_parts(id bigserial primary key, order_id bigint references public.orders(id),
  owner_email text, tools_text text, status text default 'new', fee numeric, rent_sum numeric, fee_pct numeric,
  created_at timestamptz default now(), archived boolean default false);
alter table public.order_parts enable row level security;
grant select, update on public.order_parts to authenticated;
-- прокат меняет статус только своих частей (как на сайте)
create policy parts_owner on public.order_parts for all to authenticated
  using (lower(owner_email) = lower(auth.jwt() ->> 'email')) with check (lower(owner_email) = lower(auth.jwt() ->> 'email'));
insert into public.orders(id, user_email, days, tools) values
  (1, 'Client@Mail.ru', 3, 'Дрель (3 дня), Пила (1 день)'), (2, 'other@mail.ru', 2, 'Болгарка (2 дня)'),
  (3, 'client@mail.ru', 1, 'Миксер (1 день)');
insert into public.order_parts(id, order_id, owner_email, tools_text, status) values
  (11, 1, 'prokat@mail.ru', 'Дрель (3 дня), Пила (1 день)', 'new'),
  (21, 2, 'prokat@mail.ru', 'Болгарка (2 дня)', 'new'),
  (31, 3, 'prokat@mail.ru', 'Миксер (1 день)', 'new');
"""

st, out = q(BASELINE)
check('стенд: схема «как в бою» поднялась', st == 'OK', out)
st, out = q(SQL)
check('SQL применяется без ошибок', st == 'OK', out)
check('проверка в конце SQL — все строки ✅', out.count('✅') == 5 and '❌' not in out, out)
st, out = q(SQL)
check('повторный запуск SQL — без ошибок и снова 5 ✅', st == 'OK' and out.count('✅') == 5, out)


def rented_at(pid):
    return q("select coalesce(rented_at::text, 'NULL') from public.order_parts where id = %d;" % pid)[1].splitlines()


def val(pid):
    lines = [l.strip() for l in rented_at(pid) if l.strip() and not l.startswith('-') and 'coalesce' not in l and 'row' not in l]
    return lines[0] if lines else ''


check('новая заявка — времени выдачи нет', val(11) == 'NULL', '\n'.join(rented_at(11)))
st, out = as_user('prokat@mail.ru', "update public.order_parts set status = 'rented' where id = 11;")
check('прокат нажал «Выдал» (PATCH статуса, как сайт и приложение)', st == 'OK', out)
t1 = val(11)
check('база сама запомнила время выдачи', t1 not in ('', 'NULL'), t1)
st, out = as_user('prokat@mail.ru', "update public.order_parts set rented_at = now() - interval '5 days' where id = 11;")
check('подделать время выдачи нельзя — значение не меняется', val(11) == t1, val(11))
as_user('prokat@mail.ru', "update public.order_parts set archived = false, fee = 10 where id = 11;")
check('другие правки части заявки время выдачи не трогают', val(11) == t1, val(11))
as_user('prokat@mail.ru', "update public.order_parts set status = 'RENTED ' where id = 11;")
check('тот же статус другим регистром — время не сбрасывается', val(11) == t1, val(11))
q("insert into public.order_parts(id, order_id, owner_email, tools_text, status) values (41, 3, 'prokat@mail.ru', 'Фен (1 день)', 'rented');")
check('часть сразу «в аренде» при создании — время ставится', val(41) not in ('', 'NULL'), val(41))
q("insert into public.order_parts(id, order_id, owner_email, tools_text, status, rented_at) values (42, 3, 'prokat@mail.ru', 'Пылесос (1 день)', 'new', now());")
check('при создании новой части время не подсунуть', val(42) == 'NULL', val(42))
as_user('prokat@mail.ru', "update public.order_parts set status = 'rented' where id = 21;")

st, out = as_user('client@mail.ru', "select part_id, order_id, tools_text, days from public.iva_my_rentals() order by part_id;")
check('клиенту — свои выданные (почта без учёта регистра), без чужих и невыданных',
      st == 'OK' and ids(out) == ['11', '41'] and 'Дрель (3 дня)' in out, out)
check('срок заявки в сутках — числом', '| 3' in out, out)
st, out = as_user('other@mail.ru', "select part_id from public.iva_my_rentals();")
check('другой клиент видит только своё', st == 'OK' and ids(out) == ['21'], out)
st, out = as_user('', "select * from public.iva_my_rentals();", role='anon')
check('гостю функция недоступна', st == 'ERR', out)
st, out = as_user('client@mail.ru', "select * from public.iva_my_rentals() limit 0;")
check('функция отдаёт только часть, заявку, инструменты, срок и время выдачи (без имени, телефона, адреса)',
      st == 'OK' and [c.strip() for c in out.splitlines()[3].split('|')] == ['part_id', 'order_id', 'tools_text', 'days', 'rented_at'], out)
st, out = as_user('client@mail.ru', "select 1 from public.iva_my_rentals();", role='anon')
check('гость с подставленной почтой — тоже отказ', st == 'ERR', out)

as_user('prokat@mail.ru', "update public.order_parts set status = 'closed' where id = 11;")
check('вернули (закрыта) — время выдачи сохраняется в истории', val(11) == t1, val(11))
st, out = as_user('client@mail.ru', "select part_id from public.iva_my_rentals();")
check('закрытая часть — напоминать больше не о чем', st == 'OK' and ids(out) == ['41'], out)
as_user('prokat@mail.ru', "update public.order_parts set status = 'rented' where id = 11;")
check('выдали снова — новое время', val(11) not in ('', 'NULL', t1), val(11))

print('ИТОГ: %s (%d ошибок)' % ('всё хорошо' if fails == 0 else 'есть ошибки', fails))
sys.exit(1 if fails else 0)

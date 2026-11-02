# Стенд миграции 20261014_rent_end_mode.sql (10.77) на настоящем PostgreSQL (pgserver из PyPI).
#
# Проверяет: настройки аренды меняет только действующий админ проката и только свои; неверный режим, время
# закрытия и часовой пояс не проходят; таблица напрямую закрыта; клиент получает свои выданные инструменты вместе
# с ТЕКУЩИМИ настройками их проката (смена режима — сразу и для выданных), админ — только свои выданные;
# гостю всё недоступно; SQL применяется и поверх 10.76, и без него; повторный запуск ничего не ломает.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/rent-mode-stand.py
import json
import os
import sys
import tempfile

import pgserver

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SQL = open(os.path.join(ROOT, 'supabase/migrations/20261014_rent_end_mode.sql'), encoding='utf-8').read()
SQL76 = open(os.path.join(ROOT, 'supabase/migrations/20261014_rent_reminder.sql'), encoding='utf-8').read()

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
-- и новые таблицы в public сразу доступны anon и authenticated (закрывает только REVOKE / RLS)
alter default privileges in schema public grant all on tables to anon, authenticated;
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
-- админы проката (как в бою: active, role)
create table public.admins(email text primary key, role text, active boolean, full_name text, phone text);
insert into public.admins values ('prokat@mail.ru', 'admin', true, 'Прокат', '+70000000000'),
  ('Second@Mail.ru', 'admin', true, 'Второй', ''), ('old@mail.ru', 'admin', false, 'Бывший', ''),
  ('chief@mail.ru', 'chief', true, 'Главный', '');
insert into public.orders(id, user_email, days, tools) values
  (1, 'Client@Mail.ru', 3, 'Дрель (3 дня), Пила (1 день)'), (2, 'other@mail.ru', 2, 'Болгарка (2 дня)'),
  (3, 'client@mail.ru', 1, 'Миксер (1 день)');
insert into public.order_parts(id, order_id, owner_email, tools_text, status) values
  (11, 1, 'prokat@mail.ru', 'Дрель (3 дня), Пила (1 день)', 'new'),
  (12, 1, 'second@mail.ru', 'Фреза (2 дня)', 'new'),
  (21, 2, 'prokat@mail.ru', 'Болгарка (2 дня)', 'new'),
  (31, 3, 'prokat@mail.ru', 'Миксер (1 день)', 'new');
"""

st, out = q(BASELINE)
check('стенд: схема «как в бою» поднялась', st == 'OK', out)
st, out = q(SQL76)
check('сначала SQL 10.76 (как у владельца, если уже применял)', st == 'OK', out)
st, out = q(SQL)
check('SQL 10.77 поверх 10.76 — без ошибок', st == 'OK', out)
check('проверка в конце SQL — 6 строк ✅', out.count('✅') == 6 and '❌' not in out, out)
st, out = q(SQL)
check('повторный запуск — без ошибок и снова 6 ✅', st == 'OK' and out.count('✅') == 6 and '❌' not in out, out)


def rows(out):
    """Строки результата psql: список списков ячеек (без заголовка и итога)."""
    res, lines = [], out.splitlines()
    started = False
    for line in lines:
        s = line.strip()
        if s and set(s) <= set('-+'):
            started = True
            continue
        if not started or not s or s.startswith('('):
            if s.startswith('('):
                started = False
            continue
        res.append([c.strip() for c in line.split('|')])
    return res


# --- настройки ---
st, out = as_user('prokat@mail.ru', "select mode, close_min, tz, saved from public.iva_rent_settings_get();")
check('нет настроек — по умолчанию «Сутки», закрытие 19:00, Москва, не сохранено',
      st == 'OK' and rows(out) == [['day', '{1140,1140,1140,1140,1140,1140,1140}', 'Europe/Moscow', 'f']], out)
st, out = as_user('prokat@mail.ru', "select public.iva_rent_settings_set('close', '{1080,1080,1080,1080,1080,900,-1}', 'Europe/Samara');")
check('админ сохраняет «До закрытия пункта» с закрытием по дням', st == 'OK', out)
st, out = as_user('PROKAT@mail.ru', "select mode, close_min, tz, saved from public.iva_rent_settings_get();")
check('читает свои (почта без учёта регистра)',
      st == 'OK' and rows(out) == [['close', '{1080,1080,1080,1080,1080,900,-1}', 'Europe/Samara', 't']], out)
st, out = as_user('second@mail.ru', "select mode, saved from public.iva_rent_settings_get();")
check('другой админ своих не сохранял — у него по умолчанию', st == 'OK' and rows(out) == [['day', 'f']], out)
st, out = as_user('client@mail.ru', "select public.iva_rent_settings_set('close', '{1080,1080,1080,1080,1080,900,-1}', 'Europe/Moscow');")
check('клиент менять не может', st == 'ERR', out)
st, out = as_user('old@mail.ru', "select public.iva_rent_settings_set('day', '{1080,1080,1080,1080,1080,900,-1}', 'Europe/Moscow');")
check('отключённый админ менять не может', st == 'ERR', out)
st, out = as_user('chief@mail.ru', "select public.iva_rent_settings_set('day', '{1080,1080,1080,1080,1080,900,1439}', 'Europe/Moscow');")
check('главный админ — может (свои)', st == 'OK', out)
for bad, why in [("'week', '{1080,1080,1080,1080,1080,900,-1}', 'Europe/Moscow'", 'неизвестный режим'),
                 ("'close', '{1080,1080,1080,1080,1080,900}', 'Europe/Moscow'", '6 дней вместо 7'),
                 ("'close', '{1080,1080,1080,1080,1080,900,1440}', 'Europe/Moscow'", 'время 24:00'),
                 ("'close', '{1080,1080,1080,1080,1080,900,-2}', 'Europe/Moscow'", 'время -2'),
                 ("'close', '{1080,1080,1080,1080,1080,900,NULL}', 'Europe/Moscow'", 'пустой день'),
                 ("'close', '{{1080,1080,1080,1080,1080,900,-1}}', 'Europe/Moscow'", 'двумерный массив'),
                 ("'close', NULL, 'Europe/Moscow'", 'нет расписания')]:
    st, out = as_user('second@mail.ru', "select public.iva_rent_settings_set(%s);" % bad)
    check('не проходит: ' + why, st == 'ERR', out)
st, out = as_user('second@mail.ru', "select mode from public.iva_rent_settings_get();")
check('после отказов у второго ничего не сохранилось', st == 'OK' and rows(out) == [['day']], out)
st, out = as_user('second@mail.ru', "select public.iva_rent_settings_set('close', '{600,600,600,600,600,600,600}', 'Mars/Olympus'); select tz from public.iva_rent_settings_get();")
check('неизвестный часовой пояс — Москва', st == 'OK' and 'Europe/Moscow' in out and 'Mars' not in out, out)
st, out = as_user('prokat@mail.ru', "select * from public.iva_rent_settings;")
check('таблицу напрямую не прочитать (только функции)', st == 'ERR', out)
st, out = as_user('prokat@mail.ru', "insert into public.iva_rent_settings(email) values ('x@mail.ru');")
check('и не записать', st == 'ERR', out)
for fn in ['iva_rent_settings_get()', "iva_rent_settings_set('day', '{1,1,1,1,1,1,1}', 'Europe/Moscow')", 'iva_my_rentals()', 'iva_admin_rentals()']:
    st, out = as_user('prokat@mail.ru', "select * from public.%s;" % fn, role='anon')
    check('гостю недоступно: ' + fn.split('(')[0], st == 'ERR', out)

# --- аренды ---
as_user('prokat@mail.ru', "update public.order_parts set status = 'rented' where id in (11, 21);")
as_user('second@mail.ru', "update public.order_parts set status = 'rented' where id = 12;")
st, out = as_user('client@mail.ru', "select part_id, days, rent_mode, close_min, tz from public.iva_my_rentals() order by part_id;")
r = rows(out)
check('клиент: свои выданные с настройками их проката',
      st == 'OK' and [x[0] for x in r] == ['11', '12']
      and r[0][2:] == ['close', '{1080,1080,1080,1080,1080,900,-1}', 'Europe/Samara']
      and r[1][2:] == ['close', '{600,600,600,600,600,600,600}', 'Europe/Moscow'], out)
st, out = as_user('client@mail.ru', "select * from public.iva_my_rentals() limit 0;")
check('клиенту — без имени, телефона и адреса',
      st == 'OK' and [c.strip() for c in out.splitlines()[3].split('|')] == ['part_id', 'order_id', 'tools_text', 'days', 'rented_at', 'rent_mode', 'close_min', 'tz'], out)
as_user('prokat@mail.ru', "select public.iva_rent_settings_set('day', '{1080,1080,1080,1080,1080,900,-1}', 'Europe/Samara');")
st, out = as_user('client@mail.ru', "select part_id, rent_mode from public.iva_my_rentals() where part_id = '11';")
check('прокат сменил режим — у выданного сразу новый (решение владельца)', st == 'OK' and rows(out) == [['11', 'day']], out)
st, out = as_user('prokat@mail.ru', "select part_id, order_id, days, rent_mode from public.iva_admin_rentals() order by part_id;")
check('админ: только свои выданные (чужого проката нет)', st == 'OK' and [x[0] for x in rows(out)] == ['11', '21'], out)
st, out = as_user('prokat@mail.ru', "select * from public.iva_admin_rentals() limit 0;")
check('админу — без имени, телефона и адреса клиента',
      st == 'OK' and [c.strip() for c in out.splitlines()[3].split('|')] == ['part_id', 'order_id', 'tools_text', 'days', 'rented_at', 'rent_mode', 'close_min', 'tz'], out)
st, out = as_user('client@mail.ru', "select part_id from public.iva_admin_rentals();")
check('клиенту функция админа ничего не отдаёт', st == 'OK' and rows(out) == [], out)
as_user('prokat@mail.ru', "update public.order_parts set status = 'closed' where id = 21;")
st, out = as_user('prokat@mail.ru', "select part_id from public.iva_admin_rentals();")
check('вернули — таймера больше нет', st == 'OK' and [x[0] for x in rows(out)] == ['11'], out)

# --- без 10.76: чистая база ---
srv2 = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
o1 = srv2.psql(BASELINE)
o2 = srv2.psql(SQL)
check('без SQL 10.76 (сразу 10.77) — тоже 6 ✅', 'ERROR' not in o1 + o2 and o2.count('✅') == 6 and '❌' not in o2, o2)
o3 = srv2.psql("update public.order_parts set status = 'rented' where id = 31; select rented_at is not null from public.order_parts where id = 31;")
check('…и время выдачи ставится', ' t' in o3, o3)

print('ИТОГ: %s (%d ошибок)' % ('всё хорошо' if fails == 0 else 'есть ошибки', fails))
sys.exit(1 if fails else 0)

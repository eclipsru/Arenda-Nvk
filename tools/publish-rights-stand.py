# Стенд миграции 20261006_publish_rights_fix.sql на настоящем PostgreSQL (pgserver из PyPI).
#
# Зачем: админ eclipsik.ru@mail.ru получал «не опубликовано: нет прав на публикацию (42501)»,
# хотя по базе он активный админ. Стенд сначала ВОСПРОИЗВОДИТ отказ на «боевой» схеме
# (какой она была до миграции), а потом проверяет, что миграция отказ убирает и что
# защита не ослабла (чужой, аноним, неактивный админ — по-прежнему не пускает).
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/publish-rights-stand.py
import json
import sys
import tempfile

import pgserver

srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')


def q(sql):
    """Выполнить SQL. Возвращает ('OK'|'ERR', вывод). ERR — если сервер хоть раз сказал ERROR."""
    out = srv.psql(sql).strip()
    err = any(line.startswith('ERROR') for line in out.splitlines())
    return ('ERR' if err else 'OK'), out


def sess(claims, sql, role='authenticated'):
    """Выполнить sql от имени сессии с указанными полями токена (внутри транзакции, откат)."""
    body = dict(claims)
    body.setdefault('role', role)
    c = json.dumps(body).replace("'", "''")
    return q("begin; set local role %s; set local request.jwt.claims = '%s'; %s rollback;" % (role, c, sql))


def sess_commit(claims, sql, role='authenticated'):
    """То же, что sess, но с commit — чтобы проверить, что реально легло в таблицу."""
    body = dict(claims)
    body.setdefault('role', role)
    c = json.dumps(body).replace("'", "''")
    return q("begin; set local role %s; set local request.jwt.claims = '%s'; %s commit;" % (role, c, sql))


BASELINE = """
create role anon nologin;
create role authenticated nologin;
create schema auth;
create table auth.users(id uuid primary key, email text);
create function auth.jwt() returns jsonb language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb $$;
create function auth.uid() returns uuid language sql stable as $$
  select nullif(btrim(coalesce(auth.jwt() ->> 'sub', '')), '')::uuid $$;
create function auth.role() returns text language sql stable as $$
  select coalesce(nullif(auth.jwt() ->> 'role', ''), 'anon') $$;
grant usage on schema auth to anon, authenticated;
grant execute on function auth.jwt() to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
grant execute on function auth.role() to anon, authenticated;
grant usage on schema public to anon, authenticated;

-- таблицы как в бою (состав столбцов tools снят с живого ответа API)
create table public.admins(
  email text primary key, role text not null default 'admin', active boolean not null default true,
  fee_pct numeric default 1, debt_limit numeric default 0);
create table public.limited_admins(email text primary key);
create table public.tools(
  id uuid primary key default gen_random_uuid(),
  name text not null default '', cat text not null default '', sub text not null default '',
  cat_label text not null default '', img text not null default '', descr text not null default '',
  price numeric not null default 0, active boolean not null default true, sort integer not null default 0,
  created_at timestamptz not null default now(), cats jsonb not null default '[]', subs jsonb not null default '[]',
  deposit numeric not null default 0, owner_email text not null default '', t_status text not null default 'free',
  terms text not null default '', imgs jsonb not null default '[]', delivery boolean not null default false,
  delivery_price numeric not null default 0, status text not null default 'active',
  pickup_city text not null default '', rating numeric(3,2) not null default 0.0, reviews_count integer not null default 0);
alter table public.tools enable row level security;
alter table public.admins enable row level security;
grant select on public.tools to anon;
grant select, insert, update, delete on public.tools to authenticated;
grant select on public.admins to authenticated;
create policy admins_read on public.admins for select to authenticated using (true);

-- так было в бою до миграции 20261006 (слабое место — почта берётся только из токена)
create function public.jwt_email() returns text language sql stable security definer set search_path = public as $$
  select lower(coalesce(auth.jwt() ->> 'email', '')) $$;
create function public.fn_is_chief() returns boolean language sql stable security definer set search_path = public as $$
  select exists(select 1 from public.admins a
                where coalesce(a.active, false) and a.role = 'chief'
                  and lower(btrim(coalesce(a.email, ''))) = public.jwt_email()) $$;
create function public.fn_is_admin() returns boolean language sql stable security definer set search_path = public as $$
  select exists(select 1 from public.admins a
                where a.active and a.role in ('admin', 'chief')
                  and lower(a.email) = lower(public.jwt_email())) $$;
grant execute on function public.jwt_email() to anon, authenticated;
grant execute on function public.fn_is_chief() to anon, authenticated;
grant execute on function public.fn_is_admin() to anon, authenticated;
create policy tools_admin on public.tools for all to authenticated
  using (public.fn_is_admin() and (public.fn_is_chief() or lower(owner_email) = lower(public.jwt_email())))
  with check (public.fn_is_admin() and (public.fn_is_chief() or lower(owner_email) = lower(public.jwt_email())));

-- люди: активный админ, админ с невидимым пробелом в почте, главный админ, обычный пользователь
insert into auth.users values
  ('11111111-1111-1111-1111-111111111111', 'eclipsik.ru@mail.ru'),
  ('22222222-2222-2222-2222-222222222222', 'eclips.ru@mail.ru'),
  ('33333333-3333-3333-3333-333333333333', 'user@mail.ru'),
  ('44444444-4444-4444-4444-444444444444', 'spacey.ru@mail.ru'),
  ('55555555-5555-5555-5555-555555555555', 'sleepy.ru@mail.ru');
insert into public.admins(email, role, active) values
  ('eclipsik.ru@mail.ru', 'admin', true),
  ('eclips.ru@mail.ru', 'chief', true),
  ('spacey.ru@mail.ru ', 'admin', true),
  ('sleepy.ru@mail.ru', 'admin', false);
"""

MIG = open('supabase/migrations/20261006_publish_rights_fix.sql').read()

ADM = {'email': 'eclipsik.ru@mail.ru', 'sub': '11111111-1111-1111-1111-111111111111'}
CHIEF = {'email': 'eclips.ru@mail.ru', 'sub': '22222222-2222-2222-2222-222222222222'}
USER = {'email': 'user@mail.ru', 'sub': '33333333-3333-3333-3333-333333333333'}
SPACEY = {'email': 'spacey.ru@mail.ru', 'sub': '44444444-4444-4444-4444-444444444444'}
SLEEPY = {'email': 'sleepy.ru@mail.ru', 'sub': '55555555-5555-5555-5555-555555555555'}

# тело запроса — ровно то, что шлёт приложение (метод lambda$setupAddTool$256 из APK)
def post_tool(owner=None, name='Отвёртка'):
    row = {'name': name, 'active': True, 'cat': 'hand'}
    if owner is not None:
        row['owner_email'] = owner
    row.update({'price': 100, 'deposit': 500, 'descr': 'описание', 'terms': 'условия',
                'delivery': False, 'imgs': [], 'img': ''})
    return "insert into public.tools(name, active, cat, %s price, deposit, descr, terms, delivery, imgs, img) " \
           "values ('%s', true, 'hand', %s 100, 500, 'описание', 'условия', false, '[]', '') " \
           "returning 'ПУСКАЕТ:' || owner_email;" % (
               'owner_email,' if owner is not None else '',
               name,
               "'%s'," % owner if owner is not None else '')


bad = 0


def check(name, res, expect):
    """res = ('OK'|'ERR', out); expect = 'ПУСКАЕТ' или 'ОТКАЗ'."""
    global bad
    st, out = res
    got = 'ПУСКАЕТ' if (st == 'OK' and 'ПУСКАЕТ' in out) else 'ОТКАЗ'
    ok = got == expect
    bad += not ok
    reason = '' if got == 'ПУСКАЕТ' else ' | ' + out.splitlines()[-1][:110]
    print(('  ✔' if ok else '  ✘'), ('%-58s' % name), got, '(жду %s)' % expect, reason)
    return out


print('== 0. Базовая схема (как в бою до миграции) ==')
print(' ', q(BASELINE)[0])

print('== 1. Воспроизведение отказа ДО миграции ==')
check('админ, токен с почтой, owner_email свой', sess(ADM, post_tool('eclipsik.ru@mail.ru')), 'ПУСКАЕТ')
check('админ, в токене НЕТ почты (устаревшая сессия)', sess({'sub': ADM['sub']}, post_tool('eclipsik.ru@mail.ru')), 'ОТКАЗ')
check('админ, клиент НЕ прислал owner_email', sess(ADM, post_tool(None)), 'ОТКАЗ')
check('админ с пробелом в admins.email', sess(SPACEY, post_tool('spacey.ru@mail.ru')), 'ОТКАЗ')
print(' ', q('revoke insert on public.tools from authenticated;')[0], '— имитируем отсутствующее право INSERT')
check('админ без права INSERT на таблицу', sess(ADM, post_tool('eclipsik.ru@mail.ru')), 'ОТКАЗ')

print('== 2. Применяем миграцию (дважды — проверка повторяемости) ==')
print('  применение 1:', q(MIG)[0])
print('  применение 2:', q(MIG)[0])
bad += 0 if q(MIG)[0] == 'OK' else 1

print('== 3. После миграции: админ публикувает во всех четырёх больных случаях ==')
check('админ, токен с почтой, owner_email свой', sess(ADM, post_tool('eclipsik.ru@mail.ru')), 'ПУСКАЕТ')
out = check('админ, в токене НЕТ почты (устаревшая сессия)', sess({'sub': ADM['sub']}, post_tool('eclipsik.ru@mail.ru')), 'ПУСКАЕТ')
check('админ, клиент НЕ прислал owner_email', sess(ADM, post_tool(None)), 'ПУСКАЕТ')
check('админ с пробелом в admins.email', sess(SPACEY, post_tool('spacey.ru@mail.ru')), 'ПУСКАЕТ')
check('админ прислал почту в другом регистре', sess(ADM, post_tool('Eclipsik.RU@Mail.ru')), 'ПУСКАЕТ')

print('== 4. После миграции: защита не ослабла ==')
check('главный админ публикует в чужую почту', sess(CHIEF, post_tool('eclipsik.ru@mail.ru')), 'ПУСКАЕТ')
check('обычный пользователь (нет в admins)', sess(USER, post_tool('user@mail.ru')), 'ОТКАЗ')
check('аноним', sess({}, post_tool('eclipsik.ru@mail.ru'), role='anon'), 'ОТКАЗ')
check('админ пишет ЧУЖУЮ почту (не chief)', sess(ADM, post_tool('eclips.ru@mail.ru')), 'ОТКАЗ')
check('неактивный админ (active=false)', sess(SLEEPY, post_tool('sleepy.ru@mail.ru')), 'ОТКАЗ')

print('== 5. Видимость и удаление своих объявлений (с уборкой за собой) ==')
sess_commit(CHIEF, post_tool('eclips.ru@mail.ru', 'Тест-стенд-chief'))
sess_commit(ADM, post_tool(None, 'Тест-стенд-админ'))
st, out = sess(ADM, "select 'админ своих видит: ' || count(*) from public.tools where owner_email = 'eclipsik.ru@mail.ru';"
                    "select 'админ чужих видит: ' || count(*) from public.tools where owner_email = 'eclips.ru@mail.ru';")
lines = ' | '.join(l.strip() for l in out.splitlines() if 'видит' in l)
print(' ', lines)
if 'админ своих видит: 1' not in lines or 'админ чужих видит: 0' not in lines:
    print('  ✘ правило чтения работает не так: свои должны быть видны, чужие — нет'); bad += 1
else:
    print('  ✔ свои видны, чужие нет')
d1 = sess_commit(ADM, "delete from public.tools where name = 'Тест-стенд-админ';")
d2 = sess_commit(CHIEF, "delete from public.tools where name = 'Тест-стенд-chief';")
if d1[0] != 'OK' or d2[0] != 'OK':
    print('  ✘ удаление не выполнилось:', d1[1].splitlines()[-1][:80], '/', d2[1].splitlines()[-1][:80]); bad += 1
st, out = q("select 'строк в таблице после уборки: ' || count(*) from public.tools")
print(' ', out.splitlines()[-2].strip() if len(out.splitlines()) > 1 else out)
if 'строк в таблице после уборки: 0' not in out:
    print('  ✘ тестовые строки не удалились'); bad += 1
else:
    print('  ✔ автор может удалить своё объявление, таблица пуста')

print('== 6. Диагностика из миграции ==')
st, out = sess(ADM, "select 'whoami: ' || public.iva_whoami()::text;")
print(' ', out.replace('\n', ' ')[-260:])
if '"is_admin": true' not in out:
    print('  ✘ iva_whoami() не показал is_admin=true'); bad += 1
else:
    print('  ✔ iva_whoami() показывает is_admin=true')
st, out = q("select public.iva_diag_schema();")
need = ['tools_admin', 'iva_tools_owner_default', 'jwt_email', 'fn_is_admin']
missing = [n for n in need if n not in out]
print('  ✔ iva_diag_schema() отдаёт текст (%d символов)' % len(out) if not missing else '  ✘ в схеме нет: %s' % missing)
bad += 1 if missing else 0

print('ИТОГ ошибок:', bad)
srv.cleanup()
sys.exit(1 if bad else 0)

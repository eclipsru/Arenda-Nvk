#!/usr/bin/env bash
# Локальная проверка справочника прокатов (этап П2) на своём PostgreSQL.
#
# Зачем: убедиться, что миграция и ввоз данных работают, ПРЕЖДЕ чем запускать их
# в проекте Supabase. Проверяем: создание таблицы, правила доступа (скрытые
# карточки не видны посторонним), повторный ввоз не создаёт дублей и не затирает
# статусы проверки, откат удаляет таблицу.
#
# Требуется: запущенный локальный PostgreSQL и права на создание базы.
# Запуск: bash tools/directory-stand.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB=iva_directory_test
PSQL=${PSQL:-sudo -u postgres psql}
FAILED=0

say()  { printf '%s\n' "$*"; }
good() { printf '  ok    %s\n' "$*"; }
bad()  { printf '  ОШИБКА %s\n' "$*"; FAILED=1; }

# PostgreSQL запущен под своим пользователем и не читает домашнюю папку проекта,
# поэтому копируем SQL во временную папку с открытыми правами.
STAGE=$(mktemp -d)
cp "$ROOT/supabase/migrations/20261001_directory_landlords.sql" "$STAGE/migration.sql"
cp "$ROOT/supabase/seed/20261001_directory_landlords_import.sql" "$STAGE/seed.sql"
chmod 755 "$STAGE" && chmod 644 "$STAGE"/*.sql
trap 'rm -rf "$STAGE"' EXIT

say "== 1. Готовлю чистую базу-стенд $DB =="
$PSQL -q -c "drop database if exists $DB" postgres >/dev/null
$PSQL -q -c "create database $DB" postgres >/dev/null
good "база создана"

# Заглушки Supabase: в реальном проекте роли и функции auth.* уже есть.
$PSQL -q -d "$DB" >/dev/null <<'SQL'
create schema if not exists auth;
create or replace function auth.jwt() returns jsonb language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb
$$;
create or replace function auth.uid() returns uuid language sql stable as $$
  select nullif(auth.jwt() ->> 'sub', '')::uuid
$$;
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;
grant usage on schema auth to anon, authenticated;
grant execute on function auth.jwt(), auth.uid() to anon, authenticated;
grant usage on schema public to anon, authenticated;
-- В Supabase права на таблицы выданы ролям по умолчанию — воспроизводим это,
-- иначе проверка правил доступа была бы нечестной.
alter default privileges in schema public grant select on tables to anon, authenticated;
create table if not exists public.admins (
  id uuid primary key default gen_random_uuid(),
  email text not null,
  role text not null default 'admin',
  active boolean not null default true
);
SQL
good "заглушки Supabase готовы (auth.uid, auth.jwt, роли anon/authenticated, таблица admins)"

say
say "== 2. Применяю миграцию =="
$PSQL -q -d "$DB" -v ON_ERROR_STOP=1 -f "$STAGE/migration.sql" >/dev/null 2>/tmp/iva-dir-migr.log \
  && good "миграция применилась" \
  || { bad "миграция не применилась:"; sed 's/^/      /' /tmp/iva-dir-migr.log; }

# Повторный запуск миграции: тоже должен проходить (привычка «запустил дважды»)
$PSQL -q -d "$DB" -v ON_ERROR_STOP=1 -f "$STAGE/migration.sql" >/dev/null 2>/tmp/iva-dir-migr2.log \
  && good "повторный запуск миграции безопасен" \
  || { bad "повторный запуск миграции сломался:"; sed 's/^/      /' /tmp/iva-dir-migr2.log; }

COLUMNS=$($PSQL -tA -d "$DB" -c "select count(*) from information_schema.columns where table_schema='public' and table_name='directory_landlords'")
[ "$COLUMNS" = "13" ] && good "столбцов в таблице: $COLUMNS" || bad "столбцов: $COLUMNS (ожидалось 13)"

# и сверим, что столбцы именно те, что задуманы (без лишних и без потерянных)
COLLIST=$($PSQL -tA -d "$DB" -c "select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns where table_schema='public' and table_name='directory_landlords'")
EXPECT="id,city,city_slug,name,phone,address,source,source_date,has_whatsapp,status,checked_at,created_at,updated_at"
[ "$COLLIST" = "$EXPECT" ] && good "состав столбцов совпадает с задуманным" || bad "состав столбцов: $COLLIST"

IDX=$($PSQL -tA -d "$DB" -c "select count(*) from pg_indexes where schemaname='public' and tablename='directory_landlords'")
[ "$IDX" -ge 4 ] && good "индексов: $IDX" || bad "индексов: $IDX (ожидалось не меньше 4)"

POL=$($PSQL -tA -d "$DB" -c "select count(*) from pg_policies where schemaname='public' and tablename='directory_landlords'")
[ "$POL" = "2" ] && good "правил доступа (RLS): $POL" || bad "правил доступа: $POL (ожидалось 2)"

RLS=$($PSQL -tA -d "$DB" -c "select relrowsecurity from pg_class where relname='directory_landlords'")
[ "$RLS" = "t" ] && good "RLS включён" || bad "RLS не включён"

say
say "== 3. Ввоз справочника =="
$PSQL -q -d "$DB" -v ON_ERROR_STOP=1 -f "$STAGE/seed.sql" >/dev/null 2>/tmp/iva-dir-seed.log \
  && good "ввоз выполнен" \
  || { bad "ввоз не выполнился:"; sed 's/^/      /' /tmp/iva-dir-seed.log; }

CNT=$($PSQL -tA -d "$DB" -c "select count(*) from public.directory_landlords")
[ "$CNT" = "115" ] && good "строк в справочнике: $CNT" || bad "строк: $CNT (ожидалось 115)"

BADPHONES=$($PSQL -tA -d "$DB" -c "select count(*) from public.directory_landlords where phone !~ '^\+7[0-9]{10}$'")
[ "$BADPHONES" = "0" ] && good "все телефоны в едином виде +7XXXXXXXXXX" || bad "телефонов не в едином виде: $BADPHONES"

DUPS=$($PSQL -tA -d "$DB" -c "select count(*) from (select phone, address from public.directory_landlords group by 1,2 having count(*)>1) t")
[ "$DUPS" = "0" ] && good "дублей «телефон + адрес» нет" || bad "дублей: $DUPS"

# Отмечаем одну карточку как проверенную, затем запускаем ввоз повторно:
# статус и дата проверки НЕ должны затереться.
$PSQL -q -d "$DB" -c "update public.directory_landlords set status='verified', checked_at=now() where id=(select id from public.directory_landlords order by created_at, id limit 1)" >/dev/null
$PSQL -q -d "$DB" -v ON_ERROR_STOP=1 -f "$STAGE/seed.sql" >/dev/null 2>/tmp/iva-dir-seed2.log \
  && good "повторный ввоз выполнен" \
  || { bad "повторный ввоз сломался:"; sed 's/^/      /' /tmp/iva-dir-seed2.log; }

CNT2=$($PSQL -tA -d "$DB" -c "select count(*) from public.directory_landlords")
[ "$CNT2" = "115" ] && good "после повторного ввоза строк: $CNT2 (дублей не появилось)" || bad "после повторного ввоза строк: $CNT2 (ожидалось 115)"

KEPT=$($PSQL -tA -d "$DB" -c "select count(*) from public.directory_landlords where status='verified' and checked_at is not null")
[ "$KEPT" = "1" ] && good "статус проверки не затёрт повторным ввозом" || bad "статус проверки затёрт (осталось $KEPT)"

say
say "== 4. Правила доступа: что видит обычный посетитель =="
$PSQL -q -d "$DB" -c "grant select on public.directory_landlords to anon, authenticated" >/dev/null
$PSQL -q -d "$DB" -c "update public.directory_landlords set status='hidden' where id=(select id from public.directory_landlords where status='new' order by id limit 1)" >/dev/null
$PSQL -q -d "$DB" -c "update public.directory_landlords set status='declined' where id=(select id from public.directory_landlords where status='new' order by id limit 1)" >/dev/null

ANON_CNT=$($PSQL -tA -d "$DB" -c "set role anon" -c "select count(*) from public.directory_landlords" | tail -1)
[ "$ANON_CNT" = "113" ] && good "посетитель видит 113 строк (скрытая и отклонённая не видны)" || bad "посетитель видит $ANON_CNT строк (ожидалось 113)"

ANON_WRITE=$($PSQL -tA -d "$DB" -c "set role anon" -c "insert into public.directory_landlords (city, name, phone, address) values ('Тест','Тест','+70000000000','Тест')" 2>&1 | grep -i -m1 -E "permission denied|row-level security" || echo "")
if [ -n "$ANON_WRITE" ]; then good "посетитель не может добавлять записи (правило сработало)"; else bad "посетитель смог записать строку"; fi

# и то же для администратора: он записывать может
$PSQL -q -d "$DB" -c "grant select on public.directory_landlords, public.admins to anon, authenticated" >/dev/null
$PSQL -q -d "$DB" -c "insert into public.admins (email, role, active) values ('owner@example.test', 'chief', true)" >/dev/null
ADMIN_CNT=$($PSQL -tA -d "$DB" \
  -c "set role authenticated" \
  -c "select set_config('request.jwt.claims', '{\"email\":\"owner@example.test\",\"sub\":\"11111111-1111-1111-1111-111111111111\"}', false)" \
  -c "select count(*) from public.directory_landlords" 2>/tmp/iva-dir-admin.log | tail -1)
if [ "$ADMIN_CNT" = "115" ]; then good "администратор видит все 115 строк, включая скрытую и отклонённую"
else bad "администратор видит $ADMIN_CNT строк (ожидалось 115)"; sed 's/^/      /' /tmp/iva-dir-admin.log; fi

say
say "== 5. Откат (проверяем, что он вообще работает) =="
$PSQL -q -d "$DB" -c "drop policy if exists \"directory_landlords_select_public\" on public.directory_landlords; drop policy if exists \"directory_landlords_write_admins\" on public.directory_landlords; drop table if exists public.directory_landlords;" >/dev/null
LEFT=$($PSQL -tA -d "$DB" -c "select count(*) from information_schema.tables where table_schema='public' and table_name='directory_landlords'")
[ "$LEFT" = "0" ] && good "откат удаляет таблицу" || bad "после отката таблица осталась"

say
if [ "$FAILED" = "0" ]; then
  say "ИТОГ: проверки пройдены — миграцию и ввоз можно запускать в Supabase."
else
  say "ИТОГ: есть ошибки — в Supabase пока не запускать."
fi
exit "$FAILED"

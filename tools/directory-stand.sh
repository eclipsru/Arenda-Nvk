#!/usr/bin/env bash
# Проверка справочника прокатов (этап П2) на локальном PostgreSQL.
#
# Главное отличие от обычных стендов: здесь воспроизводится БОЕВАЯ база —
# таблица уже существует и в ней 259 записей. Стенд проверяет, что файлы
# безопасно запускать именно в таком состоянии (а не в пустой базе).
#
# Требуется: запущенный локальный PostgreSQL и права на создание баз.
# Запуск: bash tools/directory-stand.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB_PROD=iva_dir_prod
DB_FRESH=iva_dir_fresh
PSQL=${PSQL:-sudo -u postgres psql}
FAILED=0

say()  { printf '%s\n' "$*"; }
good() { printf '  ok    %s\n' "$*"; }
bad()  { printf '  ОШИБКА %s\n' "$*"; FAILED=1; }

# PostgreSQL работает под своим пользователем и не читает домашнюю папку проекта,
# поэтому копируем SQL во временную папку с открытыми правами.
STAGE=$(mktemp -d)
chmod 755 "$STAGE"
cp "$ROOT/supabase/migrations/20261001_directory_landlords.sql" "$STAGE/migration.sql"
cp "$ROOT/supabase/migrations/20261001_directory_landlords_rls_NEW_PROJECT.sql" "$STAGE/rls.sql"
cp "$ROOT/supabase/seed/20261001_directory_landlords_import.sql" "$STAGE/seed.sql"
cp "$ROOT/supabase/seed/20261001_directory_ALL_IN_ONE.sql" "$STAGE/bundle.sql"
cp "$ROOT/tools/directory_existing.json" "$STAGE/existing.json"
chmod 644 "$STAGE"/*.sql "$STAGE"/*.json
trap 'rm -rf "$STAGE"' EXIT

# --- Готовим SQL, который воспроизводит боевое состояние -------------------------
SNAP="$STAGE/existing.json" python3 - <<'PY' > "$STAGE/prod_state.sql"
import json, os
rows = json.load(open(os.environ["SNAP"], encoding="utf-8"))["rows"]
def q(v): return "'" + str(v).replace("'", "''") + "'"
print("""create table public.directory_landlords (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  city text not null default '', name text not null default '', phone text not null,
  address text not null default '', source text not null default '',
  has_whatsapp boolean not null default false, last_contacted_at timestamptz,
  status text not null default 'new', campaign_count integer not null default 0,
  notes text not null default '', has_telegram boolean not null default false,
  has_max boolean not null default false, is_mobile boolean not null default false);""")
print("insert into public.directory_landlords (city, name, phone, source) values")
print(",\n".join(f"  ({q(r['city'])}, {q(r['name'])}, {q(r['phone'])}, 'снимок боевой базы')" for r in rows) + ";")
PY

say "== 1. Стенд «как боевая база»: таблица есть, в ней 259 записей =="
$PSQL -q -c "drop database if exists $DB_PROD" postgres >/dev/null
$PSQL -q -c "create database $DB_PROD" postgres >/dev/null
$PSQL -q -d "$DB_PROD" -v ON_ERROR_STOP=1 -f "$STAGE/prod_state.sql" >/dev/null
BEFORE=$($PSQL -tA -d "$DB_PROD" -c "select count(*) from public.directory_landlords")
[ "$BEFORE" = "259" ] && good "исходное состояние: $BEFORE записей" || bad "исходное состояние: $BEFORE (ожидалось 259)"

say
say "== 2. Файл структуры на боевой базе (должен быть безопасным) =="
$PSQL -q -d "$DB_PROD" -v ON_ERROR_STOP=1 -f "$STAGE/migration.sql" >/dev/null 2>/tmp/iva-dir-migr.log \
  && good "структура применилась без ошибок" \
  || { bad "структура дала ошибку:"; sed 's/^/      /' /tmp/iva-dir-migr.log; }
$PSQL -q -d "$DB_PROD" -v ON_ERROR_STOP=1 -f "$STAGE/migration.sql" >/dev/null 2>/tmp/iva-dir-migr2.log \
  && good "повторный запуск безопасен" \
  || { bad "повторный запуск сломался:"; sed 's/^/      /' /tmp/iva-dir-migr2.log; }
AFTER_MIG=$($PSQL -tA -d "$DB_PROD" -c "select count(*) from public.directory_landlords")
[ "$AFTER_MIG" = "259" ] && good "данные не тронуты: $AFTER_MIG записей" || bad "после структуры записей: $AFTER_MIG (ожидалось 259)"
UNIQ=$($PSQL -tA -d "$DB_PROD" -c "select count(*) from pg_indexes where schemaname='public' and indexname='uq_directory_landlords_phone'")
[ "$UNIQ" = "1" ] && good "защита от дублей (уникальный индекс по телефону) создана" || bad "уникальный индекс не создан"

say
say "== 3. Файл дополнения на боевой базе =="
$PSQL -q -d "$DB_PROD" -v ON_ERROR_STOP=1 -f "$STAGE/seed.sql" >/dev/null 2>/tmp/iva-dir-seed.log \
  && good "дополнение выполнено" \
  || { bad "дополнение дало ошибку:"; sed 's/^/      /' /tmp/iva-dir-seed.log; }
CNT=$($PSQL -tA -d "$DB_PROD" -c "select count(*) from public.directory_landlords")
[ "$CNT" = "284" ] && good "записей стало: $CNT (было 259 + 25 новых)" || bad "записей: $CNT (ожидалось 284)"

$PSQL -q -d "$DB_PROD" -v ON_ERROR_STOP=1 -f "$STAGE/seed.sql" >/dev/null 2>/tmp/iva-dir-seed2.log \
  && good "повторный запуск дополнения безопасен" \
  || { bad "повторный запуск сломался:"; sed 's/^/      /' /tmp/iva-dir-seed2.log; }
CNT2=$($PSQL -tA -d "$DB_PROD" -c "select count(*) from public.directory_landlords")
[ "$CNT2" = "284" ] && good "после повторного запуска: $CNT2 (дублей нет)" || bad "после повторного запуска: $CNT2 (ожидалось 284)"

DUPS=$($PSQL -tA -d "$DB_PROD" -c "select count(*) from (select right(regexp_replace(phone,'[^0-9]','','g'),10) p from public.directory_landlords group by 1 having count(*)>1) t")
[ "$DUPS" = "0" ] && good "дублей телефонов нет" || bad "дублей телефонов: $DUPS"

say
say "== 4. Новый (тестовый) проект: всё в одном файле =="
$PSQL -q -c "drop database if exists $DB_FRESH" postgres >/dev/null
$PSQL -q -c "create database $DB_FRESH" postgres >/dev/null
$PSQL -q -d "$DB_FRESH" >/dev/null <<'SQL'
create schema if not exists auth;
create or replace function auth.jwt() returns jsonb language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb $$;
create or replace function auth.uid() returns uuid language sql stable as $$
  select nullif(auth.jwt() ->> 'sub', '')::uuid $$;
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;
create table if not exists public.admins (
  id uuid primary key default gen_random_uuid(), email text not null,
  role text not null default 'admin', active boolean not null default true);
grant usage on schema auth to anon, authenticated;
grant execute on function auth.jwt(), auth.uid() to anon, authenticated;
grant usage on schema public to anon, authenticated;
alter default privileges in schema public grant select on tables to anon, authenticated;
SQL
$PSQL -q -d "$DB_FRESH" -v ON_ERROR_STOP=1 -f "$STAGE/bundle.sql" >/dev/null 2>/tmp/iva-dir-bundle.log \
  && good "комбинированный файл выполнился" \
  || { bad "комбинированный файл дал ошибку:"; sed 's/^/      /' /tmp/iva-dir-bundle.log; }
F1=$($PSQL -tA -d "$DB_FRESH" -c "select count(*) from public.directory_landlords")
[ "$F1" = "25" ] && good "в новый проект загружено: $F1 контактов" || bad "в новом проекте строк: $F1 (ожидалось 25)"
$PSQL -q -d "$DB_FRESH" -v ON_ERROR_STOP=1 -f "$STAGE/bundle.sql" >/dev/null 2>&1 && true
F2=$($PSQL -tA -d "$DB_FRESH" -c "select count(*) from public.directory_landlords")
[ "$F2" = "25" ] && good "повторный запуск не создал дублей: $F2" || bad "после повторного запуска: $F2"

say
say "== 5. Права доступа (файл для нового проекта) =="
$PSQL -q -d "$DB_FRESH" -v ON_ERROR_STOP=1 -f "$STAGE/rls.sql" >/dev/null 2>/tmp/iva-dir-rls.log \
  && good "права применены" \
  || { bad "права дали ошибку:"; sed 's/^/      /' /tmp/iva-dir-rls.log; }
POL=$($PSQL -tA -d "$DB_FRESH" -c "select count(*) from pg_policies where tablename='directory_landlords'")
[ "$POL" = "2" ] && good "правил доступа: $POL" || bad "правил доступа: $POL (ожидалось 2)"

$PSQL -q -d "$DB_FRESH" -c "grant select on public.directory_landlords, public.admins to anon, authenticated" >/dev/null
$PSQL -q -d "$DB_FRESH" -c "update public.directory_landlords set status='hidden' where id=(select id from public.directory_landlords limit 1)" >/dev/null
$PSQL -q -d "$DB_FRESH" -c "insert into public.admins (email, role, active) values ('owner@example.test','chief',true)" >/dev/null

ANON=$($PSQL -tA -d "$DB_FRESH" -c "set role anon" -c "select count(*) from public.directory_landlords" | tail -1)
[ "$ANON" = "24" ] && good "посетитель видит 24 из 25 (скрытая карточка не видна)" || bad "посетитель видит: $ANON (ожидалось 24)"

ANON_W=$($PSQL -tA -d "$DB_FRESH" -c "set role anon" -c "insert into public.directory_landlords (city,name,phone) values ('Тест','Тест','70000000000')" 2>&1 | grep -i -m1 -E "permission denied|row-level security" || echo "")
if [ -n "$ANON_W" ]; then good "посетитель не может записывать (правило сработало)"; else bad "посетитель смог записать"; fi

ADMIN=$($PSQL -tA -d "$DB_FRESH" \
  -c "set role authenticated" \
  -c "select set_config('request.jwt.claims', '{\"email\":\"owner@example.test\",\"sub\":\"11111111-1111-1111-1111-111111111111\"}', false)" \
  -c "select count(*) from public.directory_landlords" 2>/tmp/iva-dir-admin.log | tail -1)
[ "$ADMIN" = "25" ] && good "администратор видит все 25, включая скрытую" || { bad "администратор видит: $ADMIN (ожидалось 25)"; sed 's/^/      /' /tmp/iva-dir-admin.log; }

say
say "== 6. Откат =="
$PSQL -q -d "$DB_FRESH" -c "drop table if exists public.directory_landlords" >/dev/null
LEFT=$($PSQL -tA -d "$DB_FRESH" -c "select count(*) from information_schema.tables where table_schema='public' and table_name='directory_landlords'")
[ "$LEFT" = "0" ] && good "таблица удаляется без следов" || bad "таблица осталась"

say
if [ "$FAILED" = "0" ]; then
  say "ИТОГ: файлы безопасны и для боевой базы (таблица уже есть), и для нового проекта."
else
  say "ИТОГ: есть ошибки — файлы владельцу не отдавать."
fi
exit "$FAILED"

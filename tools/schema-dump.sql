-- ============================================================================
--  Ива — дамп СТРУКТУРЫ схемы public (без установки программ, без pg_dump).
--
--  Зачем: в репозитории нет базовой схемы базы. Этот запрос вытаскивает её из самой
--  базы и отдаёт одним текстом, который можно сохранить как SQL-файл.
--
--  Как пользоваться:
--    1) Supabase → SQL Editor → New query
--    2) вставить весь этот файл целиком → Run
--    3) в результате одна ячейка с текстом. Её можно:
--       а) скопировать целиком и сохранить как supabase/migrations/0000_baseline_schema.sql
--          (проще всего: навести на ячейку → значок копирования; или Download → CSV, и прислать мне)
--       б) не копировать вручную, а прислать файл мне — я сам положу его в репозиторий
--    4) проверить: в тексте должно быть больше 5 строк "create table",
--       а также строки "create policy" (правила доступа) и "create or replace function"
--
--  Требуется PostgreSQL 12 и новее (Supabase подходит). Устанавливать ничего не нужно.
--
--  Что попадает: расширения, последовательности, таблицы со столбцами и значениями по умолчанию,
--  ограничения (PK/FK/UNIQUE/CHECK), индексы, последовательности, функции,
--  триггеры, политики RLS и включение RLS на таблицах.
--  Чего НЕ будет: данные (для них — pg_dump или выгрузка через API);
--  схемы auth/storage (ими управляет Supabase, их восстанавливать не нужно).
-- ============================================================================

with tables_in as (
  select c.oid, c.relname, n.nspname
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relkind in ('r','p')
),
columns_in as (
  select t.oid as toid, a.attnum,
         '  ' || quote_ident(a.attname) || ' ' ||
         case
           when a.attidentity in ('a','d')
             then format_type(a.atttypid, a.atttypmod) || ' generated ' ||
                  case when a.attidentity = 'a' then 'always' else 'by default' end || ' as identity'
           when a.attgenerated = 's'
             then format_type(a.atttypid, a.atttypmod) || ' generated always as (' ||
                  pg_get_expr(ad.adbin, ad.adrelid) || ') stored'
           else format_type(a.atttypid, a.atttypmod)
         end ||
         case when a.attnotnull then ' not null' else '' end ||
         case when a.attidentity in ('a','d') or a.attgenerated = 's' then ''
              when ad.adbin is not null then ' default ' || pg_get_expr(ad.adbin, ad.adrelid)
              else '' end as coldef
  from tables_in t
  join pg_attribute a on a.attrelid = t.oid and a.attnum > 0 and not a.attisdropped
  left join pg_attrdef ad on ad.adrelid = a.attrelid and ad.adnum = a.attnum
),
parts as (
  -- 1. Расширения
  select 10 as ord, 1 as sub,
         format('create extension if not exists %I with schema %I;', e.extname, n.nspname) as stmt
  from pg_extension e
  join pg_namespace n on n.oid = e.extnamespace
  where e.extname <> 'plpgsql'

  union all
  -- 2. Последовательности (кроме тех, что создаёт identity-столбец)
  select 20, 1,
         format('create sequence if not exists %I.%I;', n.nspname, c.relname)
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relkind = 'S'
    and not exists (
      select 1 from pg_depend d
      where d.objid = c.oid and d.deptype = 'i' and d.refobjsubid > 0
    )

  union all
  -- 2б. Владелец последовательности (для serial-столбцов) — после таблиц
  select 35, 1,
         format('alter sequence %I.%I owned by %I.%I.%I;',
                ns.nspname, seq.relname, tn.nspname, tc.relname, a.attname)
  from pg_class seq
  join pg_namespace ns on ns.oid = seq.relnamespace
  join pg_depend d on d.objid = seq.oid and d.deptype = 'a' and d.classid = 'pg_class'::regclass
  join pg_class tc on tc.oid = d.refobjid
  join pg_namespace tn on tn.oid = tc.relnamespace
  join pg_attribute a on a.attrelid = d.refobjid and a.attnum = d.refobjsubid
  where ns.nspname = 'public' and seq.relkind = 'S'

  union all
  -- 3. Таблицы
  select 30, 1,
         format('create table if not exists %I.%I (%s);', t.nspname, t.relname,
                string_agg(c.coldef, E',\n' order by c.attnum))
  from tables_in t
  join columns_in c on c.toid = t.oid
  group by t.nspname, t.relname

  union all
  -- 4. Ограничения (PK, UNIQUE, FK, CHECK)
  select 40,
         case con.contype when 'p' then 1 when 'u' then 2 when 'f' then 3 else 4 end,
         format('alter table %I.%I add constraint %I %s%s;', n.nspname, cl.relname, con.conname,
                pg_get_constraintdef(con.oid),
                case when con.convalidated then '' else ' not valid' end)
  from pg_constraint con
  join tables_in t on t.oid = con.conrelid
  join pg_class cl on cl.oid = con.conrelid
  join pg_namespace n on n.oid = cl.relnamespace
  where con.contype in ('p','u','f','c')
    and con.conislocal

  union all
  -- 5. Индексы (кроме тех, что созданы ограничениями)
  select 50, 1, pg_get_indexdef(i.indexrelid) || ';'
  from pg_index i
  join tables_in t on t.oid = i.indrelid
  where not i.indisprimary
    and not exists (select 1 from pg_constraint c where c.conindid = i.indexrelid)

  union all
  -- 6. Функции схемы public (кроме тех, что пришли из расширений)
  select 60, 1, pg_get_functiondef(p.oid) || ';'
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.prokind in ('f','p')
    and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')

  union all
  -- 7. Триггеры
  select 70, 1, pg_get_triggerdef(tg.oid) || ';'
  from pg_trigger tg
  join tables_in t on t.oid = tg.tgrelid
  where not tg.tgisinternal

  union all
  -- 8. Включение RLS
  select 80, 1, format('alter table %I.%I enable row level security;', n.nspname, cl.relname)
  from tables_in t
  join pg_class cl on cl.oid = t.oid
  join pg_namespace n on n.oid = cl.relnamespace
  where cl.relrowsecurity

  union all
  -- 9. Политики RLS
  select 90, 1,
         format('create policy %I on %I.%I as %s for %s to %s%s%s;',
                p.policyname, p.schemaname, p.tablename,
                case when p.permissive = 'PERMISSIVE' then 'permissive' else 'restrictive' end,
                lower(p.cmd),
                (select string_agg(case when r::text = 'public' then 'public'
                                        else quote_ident(r::text) end, ', ')
                   from unnest(p.roles) as r),
                case when p.qual is not null then ' using (' || p.qual || ')' else '' end,
                case when p.with_check is not null then ' with check (' || p.with_check || ')' else '' end)
  from pg_policies p
  where p.schemaname = 'public'
)
select
  E'-- ============================================================\n' ||
  E'--  Схема public, снятая из рабочей базы (baseline).\n' ||
  E'--  Только для восстановления и истории. К обычным миграциям не относится.\n' ||
  E'--  Снято: ' || to_char(now(), 'YYYY-MM-DD HH24:MI') || E'\n' ||
  E'-- ============================================================\n\n' ||
  string_agg(stmt, E'\n\n' order by ord, sub) ||
  E'\n' as schema_dump
from parts;

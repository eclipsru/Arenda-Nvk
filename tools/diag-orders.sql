-- ============================================================
--  Ива · устройство заявок в базе (для этапа 2 «Заявки по пунктам», 17.10.2026). ТОЛЬКО ЧТЕНИЕ — ничего не меняет.
--
--  Показывает: текст функции submit_order (её нет в репозитории — она была в базе до проекта), столбцы orders и
--  order_parts, триггеры на них (с текстом функций), правила доступа (RLS) и число заявок по статусам.
--  Личных данных нет: ни имён, ни телефонов, ни адресов, ни email — только устройство. Похожее на ключи и пароли
--  (длинные строки, Bearer, sb_secret, service_role) вырезается и заменяется на ***.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Результат — таблица (раздел, №, строка). Скопировать: над таблицей «Export» → «Copy as CSV» (или Download CSV)
--  и прислать агенту.
-- ============================================================

with
fn as (                                  -- функции: submit_order и все функции триггеров orders / order_parts
  select p.oid, p.proname
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.proname = 'submit_order'
  union
  select p.oid, p.proname
  from pg_trigger t join pg_proc p on p.oid = t.tgfoid
  where t.tgrelid in ('public.orders'::regclass, 'public.order_parts'::regclass) and not t.tgisinternal
),
src as (
  select '3. функция ' || f.proname as section,
         regexp_replace(pg_get_functiondef(f.oid),
           '(eyJ[A-Za-z0-9_.\-]{20,}|sb_secret_[A-Za-z0-9_\-]+|service_role[^''"\s]*|Bearer [A-Za-z0-9_.\-]+|[A-Za-z0-9_\-]{40,})',
           '***', 'g') as body
  from fn f
),
lines as (
  select s.section, l.n::int as n, l.line
  from src s, regexp_split_to_table(s.body, E'\n') with ordinality as l(line, n)
)
select section, n, line from (
  -- 1. столбцы
  select '1. столбцы ' || c.table_name as section, c.ordinal_position::int as n,
         c.column_name || ' ' || c.data_type || coalesce(' default ' || c.column_default, '') ||
         case when c.is_nullable = 'NO' then ' not null' else '' end as line
  from information_schema.columns c
  where c.table_schema = 'public' and c.table_name in ('orders', 'order_parts')
  union all
  -- 2. триггеры
  select '2. триггеры ' || c.relname, row_number() over (partition by c.relname order by t.tgname)::int,
         t.tgname || ' → ' || p.proname || ' · ' || pg_get_triggerdef(t.oid)
  from pg_trigger t join pg_class c on c.oid = t.tgrelid join pg_proc p on p.oid = t.tgfoid
  where c.oid in ('public.orders'::regclass, 'public.order_parts'::regclass) and not t.tgisinternal
  union all
  -- 3. тексты функций
  select section, n, line from lines
  union all
  -- 4. правила доступа
  select '4. доступ ' || tablename, row_number() over (partition by tablename order by policyname)::int,
         policyname || ' · ' || cmd || ' · ' || array_to_string(roles, ',') || ' · USING ' || coalesce(qual, '-') ||
         ' · CHECK ' || coalesce(with_check, '-')
  from pg_policies where schemaname = 'public' and tablename in ('orders', 'order_parts')
  union all
  select '4. доступ ' || c.relname || ' (права)', 1,
         'RLS ' || case when c.relrowsecurity then 'включён' else 'выключен' end || ' · anon: ' ||
         coalesce((select string_agg(privilege_type, ',') from information_schema.role_table_grants g
                   where g.table_schema = 'public' and g.table_name = c.relname and g.grantee = 'anon'), '-') ||
         ' · authenticated: ' ||
         coalesce((select string_agg(privilege_type, ',') from information_schema.role_table_grants g
                   where g.table_schema = 'public' and g.table_name = c.relname and g.grantee = 'authenticated'), '-')
  from pg_class c where c.oid in ('public.orders'::regclass, 'public.order_parts'::regclass)
  union all
  -- 5. сколько заявок (без содержимого)
  select '5. числа', 1, 'заявок: ' || (select count(*) from public.orders)::text ||
         ' · частей заявок: ' || (select count(*) from public.order_parts)::text ||
         ' · заявок из нескольких частей: ' ||
         (select count(*) from (select order_id from public.order_parts group by order_id having count(*) > 1) x)::text
  union all
  select '5. числа', 2, 'статусы частей: ' ||
         coalesce((select string_agg(s || ' ' || k, ', ' order by s) from
                   (select coalesce(status, 'null') s, count(*)::text k from public.order_parts group by 1) y), '-')
  union all
  select '5. числа', 3, 'инструментов: ' || (select count(*) from public.tools)::text ||
         ' · с пунктом выдачи: ' ||
         case when exists (select 1 from information_schema.columns where table_schema = 'public'
                           and table_name = 'tools' and column_name = 'point_id')
              then (select count(*) from public.tools t where to_jsonb(t) ->> 'point_id' is not null)::text
              else 'нет столбца (SQL 20261026 не применён)' end
) r
order by section, n;

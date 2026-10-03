-- ============================================================================
--  Ива — «как устроен Telegram-бот» (диагностика №2 для владельца). 03.10.2026.
--
--  Повод: диагностика №1 (tools/diag-owner.sql) показала, что бот живёт прямо
--  в базе — функции public.iva_tg_* и исходящие запросы pg_net (17 шт., коды 200).
--  В репозитории их текста нет. Этот запрос показывает устройство бота, чтобы
--  перенести его в репозиторий (правило №1) и решить, что он отправляет.
--
--  ЧТО ДЕЛАЕТ: только ЧИТАЕТ. Ничего не создаёт, не меняет и не удаляет.
--  Секреты СКРЫВАЕТ: токен бота (123456789:AA… и bot123456789:AA…), ключи
--  Supabase (eyJ…, sb_secret_…) заменяются на «<скрыто>».
--  Данных клиентов НЕ показывает: у таблиц — только названия колонок и число строк.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Затем над таблицей результата: Export → Copy as markdown → вставить в чат агенту.
--  (Скриншот не подойдёт: тексты функций длинные и в ячейку не помещаются.)
-- ============================================================================
with
fn as (
  select p.oid, n.nspname, p.proname,
         pg_get_function_identity_arguments(p.oid) as args,
         pg_get_function_result(p.oid) as res,
         p.prosecdef as secdef,
         l.lanname as lang,
         regexp_replace(regexp_replace(regexp_replace(regexp_replace(
           pg_get_functiondef(p.oid),
           '(bot)?[0-9]{6,12}:[A-Za-z0-9_-]{25,}', '<скрыто>', 'g'),
           'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}', '<скрыто>', 'g'),
           'sb_secret_[A-Za-z0-9_-]+', '<скрыто>', 'g'),
           '(password|passwd|secret|token|apikey|api_key)(\s*[:=]\s*|'')([^''\s,;)]{12,})', '\1\2<скрыто>', 'gi') as src
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  join pg_language  l on l.oid = p.prolang
  where n.nspname not in ('pg_catalog','information_schema','net','supabase_functions',
                          'extensions','graphql','graphql_public','vault','pgsodium',
                          'realtime','storage','auth','cron','pgbouncer')
    and (p.proname ilike '%tg%' or p.proname ilike '%telegram%' or p.proname ilike '%bot%'
         or p.prosrc ilike '%telegram%' or p.prosrc ilike '%net.http_%')
),
r(sort, kind, name, details) as (
  -- 1. Функции бота: заголовок + полный текст (секреты скрыты)
  select 100 + row_number() over (order by proname), 'функция',
         nspname || '.' || proname || '(' || args || ') → ' || res,
         'язык ' || lang || case when secdef then ', SECURITY DEFINER' else '' end ||
         E'\n' || src
  from fn

  union all
  -- 2. Кто вызывает функции бота: триггеры на таблицах
  select 200 + row_number() over (order by c.relname, t.tgname), 'триггер',
         c.relname || ' → ' || pf.proname,
         t.tgname || ': ' ||
         concat_ws(',',
           case when t.tgtype & 4  <> 0 then 'INSERT' end,
           case when t.tgtype & 16 <> 0 then 'UPDATE' end,
           case when t.tgtype & 8  <> 0 then 'DELETE' end) ||
         case when t.tgenabled = 'D' then ' (ВЫКЛЮЧЕН)' else '' end
  from pg_trigger t
  join pg_class c on c.oid = t.tgrelid
  join pg_proc pf on pf.oid = t.tgfoid
  where not t.tgisinternal and pf.oid in (select oid from fn)

  union all
  -- 3. Кто ещё может вызывать: права на функции (anon = любой посетитель сайта)
  select 300 + row_number() over (order by f.proname), 'права',
         f.nspname || '.' || f.proname,
         'посетитель сайта (anon): ' ||
           case when has_function_privilege('anon', f.oid, 'execute') then 'МОЖЕТ вызвать' else 'нет' end ||
         '; вошедший (authenticated): ' ||
           case when has_function_privilege('authenticated', f.oid, 'execute') then 'может' else 'нет' end
  from fn f
  where exists (select 1 from pg_roles where rolname = 'anon')
    and exists (select 1 from pg_roles where rolname = 'authenticated')

  union all
  -- 4. Таблицы бота: только колонки и количество строк, без содержимого
  select 400 + row_number() over (order by c.relname), 'таблица',
         n.nspname || '.' || c.relname,
         'строк ≈ ' || greatest(c.reltuples, 0)::bigint ||
         '; колонки: ' || (select string_agg(a.attname, ', ' order by a.attnum)
                           from pg_attribute a
                           where a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped)
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where c.relkind = 'r'
    and n.nspname not in ('pg_catalog','information_schema','net','supabase_functions',
                          'extensions','graphql','vault','pgsodium','realtime','storage','auth','cron')
    and (c.relname ilike '%tg%' or c.relname ilike '%telegram%' or c.relname ilike '%bot%'
         or c.relname ilike '%setting%' or c.relname ilike '%config%')

  union all
  select 900, 'время', 'Когда снят отчёт (UTC)', to_char(now() at time zone 'UTC', 'YYYY-MM-DD HH24:MI')
)
select sort as "№", kind as "Что", name as "Название", details as "Подробности"
from r
order by sort;

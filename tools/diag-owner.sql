-- ============================================================================
--  Ива — «одна вставка — все ответы» (диагностика для владельца).
--  Этап: дочинка П6 + П5 (бот) + П4 (срок хранения). Создан 03.10.2026.
--
--  ЧТО ДЕЛАЕТ: только ЧИТАЕТ. Ничего не создаёт, не меняет и не удаляет.
--  Персональных данных НЕ показывает (только количества и даты без людей).
--  Секретов НЕ показывает: токены в адресах заменяются на «<скрыто>»,
--  у секретов из хранилища (Vault) видны только названия, не значения.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст
--  этого файла → Run. Получится одна таблица из ~12 строк.
--  Скопируйте её целиком (или сделайте скриншот) и пришлите агенту.
--
--  Что отвечает:
--    1–3  срок хранения заявок (П4): применена ли миграция 20261001_orders_retention.sql,
--         включено ли расписание, сколько заявок старше 12 месяцев;
--    4–9  Telegram-бот (П5): есть ли в базе вебхуки, триггеры, задания по расписанию,
--         исходящие запросы и секреты, связанные с ботом;
--    10–11 «Год до» (П6): у скольких арендодателей подходит/прошёл первый год ставки 1%.
--
--  Проверено агентом на PostgreSQL 17 (пустая база, база со всеми таблицами,
--  база с заявками/вебхуком/секретами) — см. docs/ЖУРНАЛ.md, запись 03.10.2026.
-- ============================================================================
with
-- маска для токенов: bot123456:AA... → bot<скрыто>; хвост ?... и #... отрезается
q(n, check_name, result) as (
  select 1, 'Срок хранения: функция обезличивания',
    case when to_regprocedure('public.iva_purge_requests()') is not null
      then 'ЕСТЬ — миграция 20261001_orders_retention.sql применена'
      else 'НЕТ — миграцию 20261001_orders_retention.sql ещё нужно применить' end

  union all
  select 2, 'Срок хранения: расписание раз в месяц (pg_cron)',
    case
      when not exists (select 1 from pg_extension where extname = 'pg_cron')
        then 'pg_cron выключен — обезличивание запускать вручную раз в месяц'
      when to_regclass('cron.job') is null then 'pg_cron есть, таблицы заданий нет'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select case when count(*) > 0 then 'расписание ЕСТЬ: ' || string_agg(schedule, ', ')
                       else 'расписания НЕТ' end as v
           from cron.job where jobname = 'iva-purge-requests'$x$, true, false, '')))[1]::text, '?')
    end

  union all
  select 3, 'Заявки (orders): всего / старше 12 месяцев / самая ранняя',
    case when to_regclass('public.orders') is null then 'таблицы orders нет'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select count(*) || ' / ' ||
                  count(*) filter (where created_at < now() - interval '12 months') || ' / ' ||
                  coalesce(to_char(min(created_at), 'YYYY-MM-DD'), '—') as v
           from public.orders$x$, true, false, '')))[1]::text, '?') end

  union all
  select 4, 'Бот: вебхуки базы (Database Webhooks) — таблица → адрес',
    coalesce((
      select string_agg(distinct
               c.relname || ' (' ||
               concat_ws(',',
                 case when t.tgtype & 4  <> 0 then 'INSERT' end,
                 case when t.tgtype & 16 <> 0 then 'UPDATE' end,
                 case when t.tgtype & 8  <> 0 then 'DELETE' end) || ') → ' ||
               regexp_replace(split_part(split_part(split_part(
                 encode(t.tgargs, 'escape'), '\000', 1), '?', 1), '#', 1),
                 'bot[0-9]+:[A-Za-z0-9_-]+', 'bot<скрыто>', 'g'),
             '; ')
      from pg_trigger t
      join pg_class c on c.oid = t.tgrelid
      join pg_proc  p on p.oid = t.tgfoid
      join pg_namespace pn on pn.oid = p.pronamespace
      where not t.tgisinternal
        and pn.nspname = 'supabase_functions' and p.proname = 'http_request'
    ), 'вебхуков нет')

  union all
  select 5, 'Бот: функции в базе, где упоминается Telegram',
    coalesce((
      select string_agg(n.nspname || '.' || p.proname, ', ' order by 1)
      from pg_proc p join pg_namespace n on n.oid = p.pronamespace
      where n.nspname not in ('pg_catalog','information_schema','net','supabase_functions',
                              'extensions','graphql','graphql_public','vault','pgsodium',
                              'realtime','storage','auth','cron','pgbouncer')
        and (p.prosrc ilike '%telegram%' or p.prosrc ilike '%sendMessage%')
    ), 'таких функций нет')

  union all
  select 6, 'Бот: задания по расписанию с Telegram / http',
    case when to_regclass('cron.job') is null then 'pg_cron не включён — заданий нет'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select coalesce(string_agg(jobname || ' [' || schedule || '] ' ||
                    left(regexp_replace(command, 'bot[0-9]+:[A-Za-z0-9_-]+', 'bot<скрыто>', 'g'), 120), '; '),
                    'таких заданий нет') as v
           from cron.job
           where command ilike '%telegram%' or command ilike '%http%'$x$, true, false, '')))[1]::text, '?') end

  union all
  select 7, 'Бот: исходящие запросы из базы (pg_net) за ~6 часов',
    case
      when not exists (select 1 from pg_extension where extname = 'pg_net') then 'pg_net выключен'
      when to_regclass('net._http_response') is null then 'pg_net есть, журнала ответов нет'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select case when count(*) = 0 then 'запросов не было'
                       else count(*) || ' шт., коды ответа: ' ||
                            string_agg(distinct coalesce(status_code::text, 'ошибка'), ', ') end as v
           from net._http_response$x$, true, false, '')))[1]::text, '?')
    end

  union all
  select 8, 'Бот: названия секретов в Vault (значения не показываются)',
    case when to_regclass('vault.secrets') is null then 'Vault не используется'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select coalesce(string_agg(name, ', ' order by name), 'секретов с такими названиями нет') as v
           from vault.secrets
           where name ilike '%telegram%' or name ilike '%bot%' or name ilike '%tg%'$x$, true, false, '')))[1]::text, '?') end

  union all
  select 9, 'Бот: служебная таблица notify_log (от функции notify-order)',
    case when to_regclass('public.notify_log') is null
      then 'НЕТ — функцию notify-order из ветки work/p5-order-notify не применяли'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select 'ЕСТЬ: уведомлений отправлено ' || count(*) ||
                  coalesce(', последнее ' || to_char(max(sent_at), 'YYYY-MM-DD HH24:MI'), '') as v
           from public.notify_log$x$, true, false, '')))[1]::text, '?') end

  union all
  select 10, '«Год до» (П6): одобрено / год прошёл / ≤30 дн. / >30 дн. / ближайшая дата',
    case when to_regclass('public.landlord_applications') is null then 'таблицы заявок арендодателей нет'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select count(*) || ' / ' ||
                  count(*) filter (where reviewed_at + interval '1 year' <  now()) || ' / ' ||
                  count(*) filter (where reviewed_at + interval '1 year' >= now()
                                     and reviewed_at + interval '1 year' <= now() + interval '30 days') || ' / ' ||
                  count(*) filter (where reviewed_at + interval '1 year' >  now() + interval '30 days') || ' / ' ||
                  coalesce(to_char(min(reviewed_at + interval '1 year')
                             filter (where reviewed_at + interval '1 year' >= now()), 'YYYY-MM-DD'), '—') as v
           from public.landlord_applications
           where status = 'approved' and reviewed_at is not null$x$, true, false, '')))[1]::text, '?') end

  union all
  select 11, '«Год до» (П6): активные арендодатели без одобренной заявки (в кабинете — прочерк)',
    case when to_regclass('public.admins') is null or to_regclass('public.landlord_applications') is null
      then 'нет нужных таблиц'
      else coalesce((xpath('/table/row/v/text()', query_to_xml(
        $x$select count(*) || ' из ' || (select count(*) from public.admins
                                          where coalesce(active, true) and coalesce(role, '') <> 'chief') as v
           from public.admins a
           where coalesce(a.active, true) and coalesce(a.role, '') <> 'chief'
             and not exists (select 1 from public.landlord_applications l
                             where l.status = 'approved' and l.reviewed_at is not null
                               and lower(l.email) = lower(a.email))$x$, true, false, '')))[1]::text, '?') end

  union all
  select 12, 'Когда снят отчёт (время базы, UTC)', to_char(now() at time zone 'UTC', 'YYYY-MM-DD HH24:MI')
)
select n as "№", check_name as "Проверка", result as "Результат"
from q
order by n;

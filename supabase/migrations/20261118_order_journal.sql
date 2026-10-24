-- ============================================================
--  Ива · 24.10.2026 — «Кто выдал / кто принял» на карточках заявок владельца (сайт и приложение 10.128).
--  Просьба владельца 24.10.2026: «в заявках у владельца проката отображался сотрудник, который выдал инструмент,
--  и сотрудник, который принял обратно».
--  Применять ПОСЛЕ 20261101_staff_journal.sql (столбцы issued_by / returned_by и iva_journal_who)
--  и 20261112_v121.sql (order_parts.items). Лучше — после 20261116_v125.sql (возврат по срокам пишет, кто принял).
--
--  Что делает (только ОДНА новая функция; таблицы, данные и прежние функции не меняются):
--   • iva_parts_journal(номера частей) — владельцу проката (и создателю) одним запросом по всем загруженным заявкам:
--       issued_who / issued_at   — кто отметил «Выдал» и когда (имя сотрудника, «Вы» или почта бывшего сотрудника);
--       returned_who / returned_at — кто закрыл заявку («Инструмент вернули») и когда;
--       items — по каждому уже возвращённому инструменту: кто принял и когда (возврат по срокам, с 10.124).
--     Чужие заявки — пусто. Клиент и сотрудник ничего не получают (сотрудник не видит других сотрудников).
--     Данные пишет только база (триггер 10.107 и функция возврата 10.124–10.125) — подделать с телефона нельзя.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (строки «Выдал / Принял» на карточках пропадут; заявки и статусы остаются):
--      supabase/migrations/20261118_order_journal_rollback.sql
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.order_parts') IS NULL OR to_regprocedure('public.fn_is_chief()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет order_parts / fn_is_chief — это не база «Ивы»';
  END IF;
  IF to_regprocedure('public.iva_journal_who(text, text)') IS NULL
     OR (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'order_parts'
           AND column_name IN ('issued_by', 'returned_by', 'returned_at')) <> 3 THEN
    RAISE EXCEPTION 'Сначала примените 20261101_staff_journal.sql (SQL 10.107: кто выдал / кто принял)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'items') THEN
    RAISE EXCEPTION 'Сначала примените 20261112_v121.sql (SQL 10.121: инструменты в части заявки)';
  END IF;
END $do$;

-- Владельцу: журнал своих частей заявок (до 500 за раз); имя — через iva_journal_who («Вы», имя сотрудника, почта)
CREATE OR REPLACE FUNCTION public.iva_parts_journal(p_parts bigint[])
 RETURNS SETOF jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select jsonb_build_object(
           'part_id', p.id::text,
           'issued_who', public.iva_journal_who(p.owner_email, p.issued_by),
           'issued_at', p.rented_at,
           'returned_who', public.iva_journal_who(p.owner_email, p.returned_by),
           'returned_at', p.returned_at,
           'items', coalesce((
              select jsonb_agg(jsonb_build_object(
                       'n', (e.n - 1)::int,
                       'id', coalesce(e.v ->> 'id', ''),
                       'returned_who', public.iva_journal_who(p.owner_email, e.v ->> 'returned_by'),
                       'returned_at', e.v -> 'returned_at') order by e.n)
                from jsonb_array_elements(case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end)
                     with ordinality as e(v, n)
               where coalesce(e.v ->> 'returned_at', '') <> ''), '[]'::jsonb))
    from public.order_parts p
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and coalesce(array_length(p_parts, 1), 0) between 1 and 500
     and p.id = any(p_parts)
     and (lower(btrim(coalesce(p.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email')) or public.fn_is_chief());
$function$;
REVOKE ALL ON FUNCTION public.iva_parts_journal(bigint[]) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_parts_journal(bigint[]) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('журнал заявок — функция есть',
     to_regprocedure('public.iva_parts_journal(bigint[])') IS NOT NULL),
  ('журнал заявок — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_parts_journal(bigint[])', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_parts_journal(bigint[])', 'EXECUTE')),
  ('служебная функция имён закрыта для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_journal_who(text, text)', 'EXECUTE')),
  ('журнал пишет только база (триггер 10.107)',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.order_parts'::regclass AND tgname = 'trg_b_iva_part_journal'))
) AS x(check_name, ok);

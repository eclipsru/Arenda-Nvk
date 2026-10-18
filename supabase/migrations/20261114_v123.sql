-- ============================================================
--  Ива · 10.123 — разные сроки в одной заявке: после «Выдал» у каждого срока свой таймер и своя кнопка «Вернули»
--  (просьба владельца 18.10.2026). Применять ПОСЛЕ 20261113_v122.sql (подойдёт и после 20261101_staff_journal.sql).
--  Сайт не меняется. Без этого файла приложение 10.123 работает как 10.122 (одна «Вернули» на всю часть заявки).
--
--  Что делает:
--   • когда прокат (владелец или сотрудник) отмечает «Выдал» по части заявки, где у инструментов РАЗНЫЕ сроки,
--     база делит эту часть на несколько — по одной на каждый срок. «Выдал» нажимается один раз (время выдачи у всех
--     одно), а дальше у каждой новой части свой таймер и своя кнопка «Вернули»;
--   • пример: дрель на 3 дня и пила на 7 дней → после «Выдал» две части: «Дрель» (3 дня) и «Пила» (7 дней);
--     через 3 дня прокат жмёт «Вернули» только у дрели, пила остаётся в аренде;
--   • комиссия считается как и раньше — при «Вернули» по инструментам своей части (в сумме столько же);
--   • кто выдал (журнал сотрудников), пункт, прокат, процент комиссии — переносятся в новые части; письмо клиенту
--     о новой заявке повторно не уходит, сигнал «Новая заявка» владельцу — тоже;
--   • заявки с одинаковым сроком, заявки с сайта (без списка инструментов) и уже выданные — не меняются.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ: Supabase → SQL Editor → вставить ВЕСЬ файл 20261114_v123_rollback.sql → Run (деление выключается; уже
--  разделённые части остаются отдельными — с ними всё работает как с обычными частями заявки).
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.order_parts') IS NULL OR to_regclass('public.order_events') IS NULL
     OR to_regprocedure('public.jwt_email()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет order_parts / order_events / jwt_email — это не база «Ивы»';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'items')
     OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'rented_at') THEN
    RAISE EXCEPTION 'Сначала примените 20261014_rent_end_mode.sql и 20261027_order_points.sql (время выдачи и список инструментов части)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'issued_by') THEN
    RAISE EXCEPTION 'Сначала примените 20261101_staff_journal.sql (журнал «кто выдал»)';
  END IF;
END $do$;

-- 1. Срок инструмента в части: свой (1…365), иначе срок заявки, иначе 1
CREATE OR REPLACE FUNCTION public.iva_item_days(p_item jsonb, p_order_days integer)
 RETURNS integer
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO 'public'
AS $function$
  select case when coalesce(p_item ->> 'days', '') ~ '^\d{1,4}$' then greatest(1, least(365, (p_item ->> 'days')::int))
              else greatest(1, least(365, coalesce(p_order_days, 1))) end;
$function$;
REVOKE ALL ON FUNCTION public.iva_item_days(jsonb, integer) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_item_days(jsonb, integer) TO authenticated;

-- 2. «Выдал» по части с разными сроками → часть делится по срокам. Срабатывает ПОСЛЕ остальных триггеров «до
--    изменения» (имя на «trg_zzz»): защита списка, журнал «кто выдал», комиссия, время выдачи уже отработали.
CREATE OR REPLACE FUNCTION public.iva_part_split_trg()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare g record; od int; first boolean := true; nid bigint;
begin
  if coalesce(old.status, '') <> 'new' or coalesce(new.status, '') <> 'rented' then return new; end if;
  if jsonb_typeof(new.items) is distinct from 'array' or jsonb_array_length(new.items) < 2 then return new; end if;
  select o.days into od from public.orders o where o.id = new.order_id;
  if (select count(distinct public.iva_item_days(e, od)) from jsonb_array_elements(new.items) e) < 2 then return new; end if;
  -- короткий срок — в этой части (она и выдана), остальные сроки — новыми частями (уже «выдано», то же время)
  for g in
    select x.d, jsonb_agg(x.e order by x.o) as its,
           coalesce(string_agg(nullif(btrim(coalesce(x.e ->> 'name', '')), ''), ', ' order by x.o), '') as names
      from (select a.e, a.o, public.iva_item_days(a.e, od) as d
              from jsonb_array_elements(new.items) with ordinality as a(e, o)) x
     group by x.d
     order by x.d
  loop
    if first then
      new.items := g.its;
      new.tools_text := g.names;
      first := false;
    else
      insert into public.order_parts(order_id, owner_email, tools_text, status, point_id, items, created_at, fee_pct, issued_by)
      values (new.order_id, new.owner_email, g.names, 'rented', new.point_id, g.its, new.created_at, new.fee_pct, new.issued_by)
      returning id into nid;
      insert into public.order_events(part_id, old_status, new_status, email)
      values (nid, 'new', 'rented', public.jwt_email());
    end if;
  end loop;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_part_split_trg() FROM public, anon, authenticated;

DROP TRIGGER IF EXISTS trg_zzz_iva_part_split ON public.order_parts;
CREATE TRIGGER trg_zzz_iva_part_split BEFORE UPDATE OF status ON public.order_parts
  FOR EACH ROW EXECUTE FUNCTION public.iva_part_split_trg();

NOTIFY pgrst, 'reload schema';

-- 3. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('деление по срокам при «Выдал»: триггер на месте и срабатывает последним',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.order_parts'::regclass AND tgname = 'trg_zzz_iva_part_split' AND NOT tgisinternal)
     AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.order_parts'::regclass AND NOT tgisinternal
                      AND (tgtype & 2) = 2 AND (tgtype & 16) = 16 AND tgname > 'trg_zzz_iva_part_split')),
  ('функция деления — не вызывается напрямую',
     NOT has_function_privilege('authenticated', 'public.iva_part_split_trg()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_part_split_trg()', 'EXECUTE')),
  ('срок инструмента: свой, иначе срок заявки',
     public.iva_item_days('{"days": 7}'::jsonb, 3) = 7 AND public.iva_item_days('{}'::jsonb, 3) = 3
     AND public.iva_item_days('{"days": "x"}'::jsonb, null) = 1)
) AS x(check_name, ok);

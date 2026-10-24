-- ============================================================
--  Ива 10.76 — напоминание клиенту об окончании аренды (просьба владельца 14.10.2026).
--
--  Конец аренды = момент, когда прокат нажал «Выдал» (в приложении или на сайте), + срок в сутках
--  (у каждого инструмента свой срок — из текста заявки «Дрель (3 дня), Пила (5 дней)»).
--  Раньше база время выдачи не запоминала — только статус.
--
--  Что делает:
--    • order_parts.rented_at — время выдачи. Ставит САМА БАЗА (триггер), когда статус части заявки
--      становится «rented»; вручную его поменять нельзя (ни с сайта, ни из приложения);
--      сайт и приложение админа не меняются — они, как и раньше, пишут только статус;
--    • iva_my_rentals() — клиенту: его выданные (в аренде) инструменты и время выдачи.
--      Только свои заявки; имена, телефоны и адреса не отдаются.
--  Для заявок, выданных ДО применения этого SQL, времени выдачи нет — напоминаний по ним не будет.
--
--  Безопасно: только ADD COLUMN IF NOT EXISTS / CREATE OR REPLACE, ничего не удаляется.
--  Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Внизу — таблица проверки, все строки «✅».
--  КАК ОТКЛЮЧИТЬ (откат, колонка остаётся — данные не удаляются):
--      DROP TRIGGER IF EXISTS trg_iva_part_rented_at ON public.order_parts;
--      DROP FUNCTION IF EXISTS public.iva_my_rentals();
-- ============================================================

-- 1. Время выдачи
ALTER TABLE public.order_parts ADD COLUMN IF NOT EXISTS rented_at timestamptz;
COMMENT ON COLUMN public.order_parts.rented_at IS
  'Когда прокат отметил «Выдал» (статус rented), 10.76. Ставит триггер trg_iva_part_rented_at, вручную не меняется.';

-- 2. Триггер: статус стал «rented» — запомнить время; в остальных случаях значение не трогается
CREATE OR REPLACE FUNCTION public.iva_part_rented_at()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  if tg_op = 'INSERT' then
    new.rented_at := case when lower(btrim(coalesce(new.status, ''))) = 'rented' then now() end;
  elsif lower(btrim(coalesce(new.status, ''))) = 'rented'
        and lower(btrim(coalesce(old.status, ''))) is distinct from 'rented' then
    new.rented_at := now();
  else
    new.rented_at := old.rented_at;      -- вручную не меняется
  end if;
  return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_iva_part_rented_at ON public.order_parts;
CREATE TRIGGER trg_iva_part_rented_at BEFORE INSERT OR UPDATE ON public.order_parts
  FOR EACH ROW EXECUTE FUNCTION public.iva_part_rented_at();

-- 3. Клиенту: свои инструменты в аренде и когда выданы (для напоминания на телефоне)
CREATE OR REPLACE FUNCTION public.iva_my_rentals()
 RETURNS TABLE (part_id text, order_id text, tools_text text, days int, rented_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id::text,
         p.order_id::text,
         coalesce(p.tools_text, ''),
         least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int,
         p.rented_at
  from public.order_parts p
  join public.orders o on o.id = p.order_id
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and lower(coalesce(o.user_email, '')) = lower(auth.jwt() ->> 'email')
    and lower(btrim(coalesce(p.status, ''))) = 'rented'
    and p.rented_at is not null
  order by p.rented_at desc
  limit 50;
$function$;
REVOKE ALL ON FUNCTION public.iva_my_rentals() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_my_rentals() TO authenticated;

-- 4. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('колонка времени выдачи',          EXISTS (SELECT 1 FROM information_schema.columns
                                              WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'rented_at')),
  ('триггер «Выдал» → время',          EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_part_rented_at' AND NOT tgisinternal)),
  ('функция для клиента',              EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'iva_my_rentals')),
  ('гостям функция недоступна',        NOT has_function_privilege('anon', 'public.iva_my_rentals()', 'EXECUTE')),
  ('вошедшим доступна',                has_function_privilege('authenticated', 'public.iva_my_rentals()', 'EXECUTE'))
) AS x(check_name, ok);

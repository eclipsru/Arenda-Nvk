-- ============================================================
--  Ива 10.77 — «Аренда» в настройках админа: как считать окончание аренды (просьба владельца 14.10.2026).
--
--  Два режима (у каждого проката — свой, по умолчанию «Сутки»):
--    • «Сутки»              — выдали в 14:30 → вернуть в 14:30 через столько суток, сколько в заявке;
--    • «До закрытия пункта» — выдали в 14:30 → вернуть до закрытия пункта через столько дней, сколько в заявке.
--      Время закрытия — своё у каждого дня недели; выходной день → срок переносится до закрытия
--      следующего рабочего дня.
--  Срок у каждого инструмента — из заявки. Решение владельца: смена режима пересчитывает и уже выданные
--  инструменты (база отдаёт текущие настройки, конец считает приложение). По этому концу клиент получает
--  напоминание «за 1–3 часа» (10.76), и клиент и админ видят живой таймер в заявке.
--
--  ВКЛЮЧАЕТ SQL 10.76 (20261014_rent_reminder.sql): время выдачи order_parts.rented_at и его триггер.
--  Если 10.76 ещё не применяли — достаточно применить только этот файл. Если применяли — тоже безопасно.
--
--  Что делает:
--    • iva_rent_settings — настройки проката (режим, закрытие по дням, часовой пояс телефона админа).
--      Прямого доступа к таблице нет ни у кого — только через функции ниже;
--    • iva_rent_settings_get() / iva_rent_settings_set(...) — админ читает и меняет СВОИ настройки
--      (менять может только действующий админ проката);
--    • iva_my_rentals() — клиенту: его выданные инструменты + настройки их проката (пересоздаётся —
--      добавились колонки; приложение 10.76 продолжает работать);
--    • iva_admin_rentals() — админу: его выданные инструменты (номер заявки, названия, срок, время выдачи) —
--      для таймера. Имена, телефоны и адреса не отдаются.
--  Сайт не меняется.
--
--  Безопасно: CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS / CREATE OR REPLACE; данные не удаляются.
--  Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Внизу — таблица проверки, все строки «✅».
--  КАК ОТКЛЮЧИТЬ (откат; таблица и колонка остаются — данные не удаляются):
--      DROP FUNCTION IF EXISTS public.iva_admin_rentals();
--      DROP FUNCTION IF EXISTS public.iva_rent_settings_set(text, int[], text);
--      DROP FUNCTION IF EXISTS public.iva_rent_settings_get();
--    (приложение тогда считает «Сутки»; iva_my_rentals с новыми колонками 10.76 не мешает)
-- ============================================================

-- 1. Время выдачи (как в 10.76)
ALTER TABLE public.order_parts ADD COLUMN IF NOT EXISTS rented_at timestamptz;
COMMENT ON COLUMN public.order_parts.rented_at IS
  'Когда прокат отметил «Выдал» (статус rented), 10.76. Ставит триггер trg_iva_part_rented_at, вручную не меняется.';

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

-- 2. Настройки проката: режим, закрытие по дням (Пн…Вс, минуты от полуночи, -1 — выходной), часовой пояс
CREATE TABLE IF NOT EXISTS public.iva_rent_settings (
  email      text PRIMARY KEY CHECK (email = lower(btrim(email)) AND email <> ''),
  mode       text NOT NULL DEFAULT 'day' CHECK (mode IN ('day', 'close')),
  close_min  int[] NOT NULL DEFAULT '{1140,1140,1140,1140,1140,1140,1140}'
             CHECK (array_ndims(close_min) = 1 AND array_length(close_min, 1) = 7
                    AND array_position(close_min, NULL) IS NULL
                    AND -1 <= ALL (close_min) AND 1439 >= ALL (close_min)),
  tz         text NOT NULL DEFAULT 'Europe/Moscow',
  updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.iva_rent_settings IS
  'Ива 10.77: как прокат считает окончание аренды. Доступ только через iva_rent_settings_get/_set и функции аренды.';
ALTER TABLE public.iva_rent_settings ENABLE ROW LEVEL SECURITY;      -- политик нет: напрямую не читается и не пишется
REVOKE ALL ON TABLE public.iva_rent_settings FROM public, anon, authenticated;

-- 3. Админ: свои настройки (нет записи — значения по умолчанию)
CREATE OR REPLACE FUNCTION public.iva_rent_settings_get()
 RETURNS TABLE (mode text, close_min int[], tz text, saved boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select coalesce(s.mode, 'day'),
         coalesce(s.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
         coalesce(s.tz, 'Europe/Moscow'),
         s.email is not null
  from (select 1) one
  left join public.iva_rent_settings s on s.email = lower(btrim(coalesce(auth.jwt() ->> 'email', '')))
  where coalesce(auth.jwt() ->> 'email', '') <> '';
$function$;
REVOKE ALL ON FUNCTION public.iva_rent_settings_get() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_rent_settings_get() TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_rent_settings_set(p_mode text, p_close int[], p_tz text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  z  text := coalesce(nullif(btrim(p_tz), ''), 'Europe/Moscow');
begin
  if em = '' or not exists (select 1 from public.admins a
                            where a.active and a.role in ('admin', 'chief') and lower(a.email) = em) then
    raise exception 'Настройки аренды меняет только прокат';
  end if;
  if p_mode is null or p_mode not in ('day', 'close') then
    raise exception 'Неизвестный режим аренды';
  end if;
  if p_close is null or array_ndims(p_close) is distinct from 1 or array_length(p_close, 1) is distinct from 7
     or array_position(p_close, NULL) is not null or not (-1 <= all (p_close) and 1439 >= all (p_close)) then
    raise exception 'Время закрытия: 7 дней, от 00:00 до 23:59 или выходной';
  end if;
  if not exists (select 1 from pg_timezone_names where name = z) then
    z := 'Europe/Moscow';
  end if;
  insert into public.iva_rent_settings as s (email, mode, close_min, tz, updated_at)
  values (em, p_mode, p_close, z, now())
  on conflict (email) do update set mode = excluded.mode, close_min = excluded.close_min,
                                    tz = excluded.tz, updated_at = now();
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_rent_settings_set(text, int[], text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_rent_settings_set(text, int[], text) TO authenticated;

-- 4. Клиенту: свои выданные инструменты + текущие настройки их проката (колонки добавились — пересоздаём)
DROP FUNCTION IF EXISTS public.iva_my_rentals();
CREATE FUNCTION public.iva_my_rentals()
 RETURNS TABLE (part_id text, order_id text, tools_text text, days int, rented_at timestamptz,
                rent_mode text, close_min int[], tz text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id::text,
         p.order_id::text,
         coalesce(p.tools_text, ''),
         least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int,
         p.rented_at,
         coalesce(s.mode, 'day'),
         coalesce(s.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
         coalesce(s.tz, 'Europe/Moscow')
  from public.order_parts p
  join public.orders o on o.id = p.order_id
  left join public.iva_rent_settings s on s.email = lower(btrim(coalesce(p.owner_email, '')))
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and lower(coalesce(o.user_email, '')) = lower(auth.jwt() ->> 'email')
    and lower(btrim(coalesce(p.status, ''))) = 'rented'
    and p.rented_at is not null
  order by p.rented_at desc
  limit 50;
$function$;
REVOKE ALL ON FUNCTION public.iva_my_rentals() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_my_rentals() TO authenticated;

-- 5. Админу: его выданные инструменты (для таймера на карточках) + его настройки
CREATE OR REPLACE FUNCTION public.iva_admin_rentals()
 RETURNS TABLE (part_id text, order_id text, tools_text text, days int, rented_at timestamptz,
                rent_mode text, close_min int[], tz text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id::text,
         p.order_id::text,
         coalesce(p.tools_text, ''),
         least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int,
         p.rented_at,
         coalesce(s.mode, 'day'),
         coalesce(s.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
         coalesce(s.tz, 'Europe/Moscow')
  from public.order_parts p
  join public.orders o on o.id = p.order_id
  left join public.iva_rent_settings s on s.email = lower(btrim(coalesce(p.owner_email, '')))
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and lower(btrim(coalesce(p.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
    and lower(btrim(coalesce(p.status, ''))) = 'rented'
    and p.rented_at is not null
  order by p.rented_at desc
  limit 200;
$function$;
REVOKE ALL ON FUNCTION public.iva_admin_rentals() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_admin_rentals() TO authenticated;

-- 6. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('колонка времени выдачи',            EXISTS (SELECT 1 FROM information_schema.columns
                                                WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'rented_at')),
  ('триггер «Выдал» → время',            EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_part_rented_at' AND NOT tgisinternal)),
  ('таблица настроек аренды',            to_regclass('public.iva_rent_settings') IS NOT NULL),
  ('таблица закрыта (RLS, без доступа)', (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_rent_settings'::regclass)
                                         AND NOT has_table_privilege('authenticated', 'public.iva_rent_settings', 'SELECT')
                                         AND NOT has_table_privilege('anon', 'public.iva_rent_settings', 'SELECT')),
  ('функции для вошедших',               has_function_privilege('authenticated', 'public.iva_rent_settings_get()', 'EXECUTE')
                                         AND has_function_privilege('authenticated', 'public.iva_rent_settings_set(text, int[], text)', 'EXECUTE')
                                         AND has_function_privilege('authenticated', 'public.iva_my_rentals()', 'EXECUTE')
                                         AND has_function_privilege('authenticated', 'public.iva_admin_rentals()', 'EXECUTE')),
  ('гостям функции недоступны',          NOT has_function_privilege('anon', 'public.iva_rent_settings_get()', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_rent_settings_set(text, int[], text)', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_my_rentals()', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_admin_rentals()', 'EXECUTE'))
) AS x(check_name, ok);

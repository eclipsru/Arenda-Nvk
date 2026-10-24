-- ============================================================
--  Ива · 10.124 — разные сроки в одной заявке: заявка остаётся ОДНОЙ, внутри — по срокам, у каждого срока своя
--  «Вернули», таймер и подсветка (просьба владельца 18.10.2026, заменяет 20261114_v123.sql).
--  Применять ПОСЛЕ 20261113_v122.sql. Файл 20261114_v123.sql применять НЕ нужно (если уже применили — этот файл
--  выключит деление заявки на части). Сайт не меняется. Без этого файла приложение 10.124 работает как 10.122
--  (одна «Вернули» на всю заявку).
--
--  Что делает:
--   • выключает деление заявки на части при «Выдал» (триггер 10.123, если был применён);
--   • iva_staff_return(часть, срок) — прокат (владелец этой заявки или сотрудник пункта с правом «Выдача») отмечает
--     «Вернули» у инструментов одного срока: у них ставится время возврата и кто принял; заявка остаётся
--     «Выдана», пока не вернут все сроки; последний срок закрывает заявку как обычно (комиссия — по всем
--     инструментам заявки: цена × свой срок, как и раньше);
--   • заявка целиком (iva_order_part_card) отдаёт у каждого инструмента время возврата — приложение показывает
--     внутри заявки сроки отдельно: свои инструменты, своя сумма, своя подсветка и кнопка «Вернули»;
--   • таймеры и напоминания (iva_my_rentals / iva_admin_rentals) — по каждому сроку, возвращённые не считаются.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ: Supabase → SQL Editor → вставить ВЕСЬ файл 20261115_v124_rollback.sql → Run (функции 10.122 возвращаются;
--  отметки «вернули» у инструментов остаются в заявке, но не используются; комиссия не меняется).
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_order_part_card(bigint, boolean)') IS NULL OR to_regprocedure('public.iva_order_cancel(bigint, text)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261113_v122.sql (SQL 10.122: заявки целиком, отмена клиентом)';
  END IF;
  IF to_regprocedure('public.iva_staff_right(uuid, text)') IS NULL OR to_regprocedure('public.iva_staff_set_status(bigint, text)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261029_staff_orders.sql (заявки пункта у сотрудника)';
  END IF;
  IF to_regclass('public.iva_rent_settings') IS NULL OR to_regprocedure('public.iva_admin_rentals()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261014_rent_end_mode.sql и 20261027_order_points.sql (срок аренды и таймеры)';
  END IF;
END $do$;

-- 1. Деление заявки на части (10.123) — выключено: заявка остаётся одной
DROP TRIGGER IF EXISTS trg_zzz_iva_part_split ON public.order_parts;
DROP FUNCTION IF EXISTS public.iva_part_split_trg();

-- 2. Срок инструмента: свой (1…365), иначе срок заявки, иначе 1
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

-- 3. «Вернули» по одному сроку заявки (владелец заявки или сотрудник пункта с правом «Выдача»)
CREATE OR REPLACE FUNCTION public.iva_staff_return(p_part bigint, p_days integer)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  pt  record;
  slf boolean;
  od  int;
  its jsonb;
  hit int;
begin
  select p.id, p.status, p.point_id, lower(btrim(coalesce(p.owner_email, ''))) as owner, p.items,
         lower(btrim(coalesce(o.user_email, ''))) as client, o.days::text as odays
    into pt
    from public.order_parts p join public.orders o on o.id = p.order_id
   where p.id = p_part
   for update of p;
  if pt.id is null or me = '' then
    raise exception 'Заявка не найдена в вашем пункте';
  end if;
  if pt.owner <> me then
    if pt.point_id is null or not public.iva_staff_right(pt.point_id, 'issue') then
      raise exception 'Заявка не найдена в вашем пункте';
    end if;
    select s.self_issue into slf from public.iva_staff s where s.email = me and s.status = 'active';
    if pt.client = me and not coalesce(slf, false) then
      raise exception 'Свою заявку выдаёт и принимает другой сотрудник или владелец';
    end if;
  end if;
  od := least(coalesce(nullif(regexp_replace(coalesce(pt.odays, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int;
  if coalesce(pt.status, '') <> 'rented' or jsonb_typeof(pt.items) is distinct from 'array' then
    raise exception 'Заявка уже в другом состоянии — обновите список';
  end if;
  select count(*) into hit from jsonb_array_elements(pt.items) e
   where public.iva_item_days(e, od) = p_days and coalesce(e ->> 'returned_at', '') = '';
  if hit = 0 then
    raise exception 'Заявка уже в другом состоянии — обновите список';
  end if;
  select jsonb_agg(case when public.iva_item_days(x.e, od) = p_days and coalesce(x.e ->> 'returned_at', '') = ''
                        then x.e || jsonb_build_object('returned_at', now(), 'returned_by', me) else x.e end order by x.n)
    into its
    from jsonb_array_elements(pt.items) with ordinality as x(e, n);
  if exists (select 1 from jsonb_array_elements(its) e where coalesce(e ->> 'returned_at', '') = '') then
    update public.order_parts set items = its where id = pt.id;          -- остальные сроки ещё в аренде
    return 'partial';
  end if;
  update public.order_parts set items = its, status = 'closed' where id = pt.id;   -- последний срок: заявка закрыта, комиссия
  return 'closed';
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_return(bigint, integer) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_return(bigint, integer) TO authenticated;

-- 4. Заявка целиком: у инструмента — когда вернули (кто принял — только в самой заявке, клиенту не показываем)
CREATE OR REPLACE FUNCTION public.iva_order_part_card(p_part bigint, p_fee boolean)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select jsonb_build_object(
           'part_id', p.id::text,
           'order_id', p.order_id::text,
           'status', coalesce(nullif(btrim(p.status), ''), 'new'),
           'created_at', p.created_at,
           'rented_at', p.rented_at,
           'rent_sum', p.rent_sum,
           'fee', case when p_fee then p.fee end,
           'tools_text', coalesce(p.tools_text, ''),
           'company', coalesce(nullif(btrim(to_jsonb(ad) ->> 'company'), ''), nullif(btrim(to_jsonb(ad) ->> 'full_name'), ''), ''),
           'prokat_tel', coalesce(nullif(btrim(to_jsonb(ad) ->> 'org_tel'), ''), nullif(btrim(to_jsonb(ad) ->> 'phone'), ''), ''),
           'prokat_addr', coalesce(btrim(to_jsonb(ad) ->> 'address'), ''),
           'work_hours', coalesce(btrim(to_jsonb(ad) ->> 'work_hours'), ''),
           'point', case when pp.id is null then null else
                      jsonb_build_object('id', pp.id, 'name', pp.name, 'address', pp.address, 'city', coalesce(pp.city, ''),
                                         'tel', coalesce(public.iva_point_tel(pp.id), ''),
                                         'open_min', pp.open_min, 'close_min', pp.close_min) end,
           'rent_mode', coalesce(rs.mode, 'day'),
           'close_min', coalesce(pp.close_min, rs.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
           'tz', coalesce(rs.tz, 'Europe/Moscow'),
           'items', coalesce((
              select jsonb_agg(jsonb_build_object(
                       'id', coalesce(e.v ->> 'id', ''),
                       'name', coalesce(nullif(btrim(e.v ->> 'name'), ''), t.name, ''),
                       'price', coalesce(e.v -> 'price', to_jsonb(t) -> 'price'),
                       'days', e.v -> 'days',
                       'img', coalesce(nullif(to_jsonb(t) ->> 'img', ''),
                                       case when jsonb_typeof(to_jsonb(t) -> 'imgs') = 'array' then to_jsonb(t) -> 'imgs' ->> 0 end, ''),
                       'deposit', to_jsonb(t) -> 'deposit',
                       'delivery_price', to_jsonb(t) -> 'delivery_price',
                       'returned_at', e.v -> 'returned_at') order by e.n)
                from jsonb_array_elements(case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end)
                     with ordinality as e(v, n)
                left join public.tools t on t.id::text = e.v ->> 'id'), '[]'::jsonb),
           'editable', jsonb_typeof(p.items) = 'array' and jsonb_array_length(p.items) > 0,
           'cancel', (select jsonb_build_object('reason', c.reason, 'at', c.created_at, 'answer', coalesce(c.answer, ''))
                        from public.iva_order_changes c where c.part_id = p.id and c.kind = 'cancel'
                       order by c.created_at desc limit 1),
           'change', (select jsonb_build_object('id', c.id, 'state', c.state, 'at', c.created_at, 'before', c.before, 'after', c.after)
                        from public.iva_order_changes c
                       where c.part_id = p.id and c.kind = 'edit'
                         and (c.state = 'pending' or c.decided_at > now() - interval '3 days')
                       order by c.created_at desc limit 1))
    from public.order_parts p
    left join lateral (select x.* from public.admins x
                        where lower(btrim(x.email)) = lower(btrim(p.owner_email)) order by x.active desc limit 1) ad on true
    left join public.iva_pickup_points pp on pp.id = p.point_id
    left join public.iva_rent_settings rs on rs.email = lower(btrim(coalesce(p.owner_email, '')))
   where p.id = p_part;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_part_card(bigint, boolean) FROM public, anon, authenticated;

-- 5. Таймеры и напоминания: по каждому сроку, возвращённые не считаются
CREATE OR REPLACE FUNCTION public.iva_my_rentals()
 RETURNS TABLE (part_id text, order_id text, tools_text text, days int, rented_at timestamptz,
                rent_mode text, close_min int[], tz text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id::text,
         p.order_id::text,
         -- 10.124: «Дрель (3 дн), Пила (7 дн)» — только ещё не возвращённые; приложение делает таймер на каждый срок
         coalesce((select string_agg(replace(coalesce(nullif(btrim(e ->> 'name'), ''), 'Инструмент'), ',', ' ')
                                     || ' (' || public.iva_item_days(e, least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int) || ' дн)', ', ' order by x.n)
                     from jsonb_array_elements(case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end)
                          with ordinality as x(e, n)
                    where coalesce(e ->> 'returned_at', '') = ''), coalesce(p.tools_text, '')),
         coalesce((select max(public.iva_item_days(e, least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int)) from jsonb_array_elements(
                     case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end) e
                    where coalesce(e ->> 'returned_at', '') = ''),
                  least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int),
         p.rented_at,
         coalesce(s.mode, 'day'),
         coalesce(pp.close_min, s.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
         coalesce(s.tz, 'Europe/Moscow')
  from public.order_parts p
  join public.orders o on o.id = p.order_id
  left join public.iva_rent_settings s on s.email = lower(btrim(coalesce(p.owner_email, '')))
  left join public.iva_pickup_points pp on pp.id = p.point_id
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and lower(coalesce(o.user_email, '')) = lower(auth.jwt() ->> 'email')
    and lower(btrim(coalesce(p.status, ''))) = 'rented'
    and p.rented_at is not null
  order by p.rented_at desc
  limit 50;
$function$;
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
         -- 10.124: «Дрель (3 дн), Пила (7 дн)» — только ещё не возвращённые; приложение делает таймер на каждый срок
         coalesce((select string_agg(replace(coalesce(nullif(btrim(e ->> 'name'), ''), 'Инструмент'), ',', ' ')
                                     || ' (' || public.iva_item_days(e, least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int) || ' дн)', ', ' order by x.n)
                     from jsonb_array_elements(case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end)
                          with ordinality as x(e, n)
                    where coalesce(e ->> 'returned_at', '') = ''), coalesce(p.tools_text, '')),
         coalesce((select max(public.iva_item_days(e, least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int)) from jsonb_array_elements(
                     case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end) e
                    where coalesce(e ->> 'returned_at', '') = ''),
                  least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int),
         p.rented_at,
         coalesce(s.mode, 'day'),
         coalesce(pp.close_min, s.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
         coalesce(s.tz, 'Europe/Moscow')
  from public.order_parts p
  join public.orders o on o.id = p.order_id
  left join public.iva_rent_settings s on s.email = lower(btrim(coalesce(p.owner_email, '')))
  left join public.iva_pickup_points pp on pp.id = p.point_id
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and lower(btrim(coalesce(p.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
    and lower(btrim(coalesce(p.status, ''))) = 'rented'
    and p.rented_at is not null
  order by p.rented_at desc
  limit 200;
$function$;
REVOKE ALL ON FUNCTION public.iva_my_rentals() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_my_rentals() TO authenticated;
REVOKE ALL ON FUNCTION public.iva_admin_rentals() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_admin_rentals() TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 6. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('заявка не делится на части (триггера 10.123 нет)',
     NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_zzz_iva_part_split' AND NOT tgisinternal)),
  ('«Вернули» по сроку — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_staff_return(bigint, integer)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_return(bigint, integer)', 'EXECUTE')),
  ('заявка целиком и таймеры знают о возврате по сроку',
     pg_get_functiondef('public.iva_order_part_card(bigint, boolean)'::regprocedure) LIKE '%returned_at%'
     AND pg_get_functiondef('public.iva_admin_rentals()'::regprocedure) LIKE '%returned_at%'
     AND pg_get_functiondef('public.iva_my_rentals()'::regprocedure) LIKE '%returned_at%'),
  ('срок инструмента: свой, иначе срок заявки',
     public.iva_item_days('{"days": 7}'::jsonb, 3) = 7 AND public.iva_item_days('{}'::jsonb, 3) = 3)
) AS x(check_name, ok);

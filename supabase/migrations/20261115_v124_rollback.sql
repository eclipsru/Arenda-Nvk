-- ============================================================
--  Ива · ОТКАТ 10.124 (20261115_v124.sql). Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
--  Убирает «Вернули» по сроку и возвращает функции 10.122 (заявка целиком) и 10.102 (таймеры). Отметки «вернули»
--  у инструментов остаются внутри заявок, но больше не используются; заявка закрывается штатной «Вернули» целиком.
--  Деление заявки на части (10.123) не возвращается.
-- ============================================================

DROP FUNCTION IF EXISTS public.iva_staff_return(bigint, integer);

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
                       'delivery_price', to_jsonb(t) -> 'delivery_price') order by e.n)
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
         coalesce(p.tools_text, ''),
         coalesce((select max(greatest(1, least(365, (e->>'days')::int))) from jsonb_array_elements(
                     case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end) e),
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
         coalesce(p.tools_text, ''),
         coalesce((select max(greatest(1, least(365, (e->>'days')::int))) from jsonb_array_elements(
                     case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end) e),
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

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('«Вернули» по сроку убрано, функции 10.122 на месте',
     to_regprocedure('public.iva_staff_return(bigint, integer)') IS NULL
     AND pg_get_functiondef('public.iva_order_part_card(bigint, boolean)'::regprocedure) NOT LIKE '%returned_at%'
     AND pg_get_functiondef('public.iva_admin_rentals()'::regprocedure) NOT LIKE '%returned_at%')
) AS x(check_name, ok);

-- ============================================================
--  Ива · ОТКАТ 10.125 (20261116_v125.sql). Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
--  Чек снова по брони (как в 10.124): закрытие заявки и «Вернули» по сроку — функции 10.122 / 10.124.
--  Отметки начатых суток (used_days) остаются внутри заявок, но не используются; уже посчитанные суммы не меняются.
-- ============================================================

CREATE OR REPLACE FUNCTION public.fn_fee_accrue()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare d int; snap jsonb; rs numeric; pct int;
begin
  if new.status is distinct from old.status then
    if not ((old.status = 'new' and new.status in ('rented','closed')) or (old.status = 'rented' and new.status = 'closed')
            or (old.status = 'new' and new.status = 'cancelled' and coalesce(current_setting('iva.part_cancel', true), '') = '1')
            or (old.status = 'cancelled' and new.status = 'closed' and coalesce(current_setting('iva.part_took', true), '') = '1')) then
      raise exception 'Недопустимый перевод статуса: % → %', old.status, new.status;
    end if;
  end if;
  if new.status = 'closed' and old.status is distinct from 'closed' and (new.fee is null or new.fee = 0) then
    select coalesce(new.fee_pct, (select fee_pct from public.admins where lower(email)=lower(new.owner_email)), 5) into pct;
    if jsonb_typeof(new.items) = 'array' and jsonb_array_length(new.items) > 0 then
      select coalesce(jsonb_agg(jsonb_build_object('name', e->>'name', 'price', (e->>'price')::numeric, 'days', (e->>'days')::int)), '[]'::jsonb),
             coalesce(sum((e->>'price')::numeric * greatest(1, (e->>'days')::int)), 0)
        into snap, rs
        from jsonb_array_elements(new.items) e;
    else
      select coalesce(days,1) into d from public.orders where id = new.order_id;
      d := coalesce(d,1);
      select coalesce(jsonb_agg(jsonb_build_object('name', m.name, 'price', m.price)), '[]'::jsonb), coalesce(sum(m.price),0) * d into snap, rs
        from (select distinct t.name, t.price from public.tools t
               where t.active <> false and position(t.name in new.tools_text) > 0
                 and lower(coalesce(t.owner_email, 'eclips.ru@mail.ru')) = lower(new.owner_email)
                 and (new.point_id is null or t.point_id = new.point_id)) m;
    end if;
    if rs is not null and rs > 0 then
      new.price_snapshot := snap; new.rent_sum := rs;
      new.fee := round(rs * pct::numeric / 100.0);
    end if;
  end if;
  if new.status = 'cancelled' then new.fee := 0; end if;
  if new.status is distinct from old.status then
    insert into public.order_events(part_id, old_status, new_status, email) values (new.id, old.status, new.status, public.jwt_email());
  end if;
  return new;
end
$function$;

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
DROP FUNCTION IF EXISTS public.iva_bill_days(jsonb, integer);
DROP FUNCTION IF EXISTS public.iva_part_used_days(text, uuid, timestamptz, timestamptz);
DROP FUNCTION IF EXISTS public.iva_used_days(timestamptz, timestamptz, text, integer[], text);
DROP FUNCTION IF EXISTS public.iva_rent_end(timestamptz, integer, text, integer[], text);

NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('чек снова по брони (функции 10.124)',
     pg_get_functiondef('public.fn_fee_accrue()'::regprocedure) NOT LIKE '%iva_bill_days%'
     AND pg_get_functiondef('public.iva_staff_return(bigint, integer)'::regprocedure) NOT LIKE '%used_days%'
     AND pg_get_functiondef('public.iva_order_part_card(bigint, boolean)'::regprocedure) NOT LIKE '%used_days%'
     AND to_regprocedure('public.iva_used_days(timestamptz, timestamptz, text, integer[], text)') IS NULL)
) AS x(check_name, ok);

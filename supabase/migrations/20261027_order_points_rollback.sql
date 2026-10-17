-- ============================================================
--  Ива · ОТКАТ 10.102 (20261027_order_points.sql) — только если владелец решил откатить этап 2.
--  Возвращает fn_split_order и fn_fee_accrue в прежний вид (тексты сняты с рабочей базы 17.10.2026 запросом
--  tools/diag-orders.sql), «До конца аренды» — как в 10.95 (график первого пункта через настройки проката),
--  убирает новые функции, защитный триггер и столбцы. Старая submit_order не трогалась — откатывать нечего.
--  Заявки, оформленные при 10.102, остаются; их части теряют пункт и список (комиссия по ним уже посчитана, если закрыты).
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run.
-- ============================================================

-- 1. Деление заявки — как до 10.102 (по названиям, часть на прокат)
CREATE OR REPLACE FUNCTION public.fn_split_order()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare o record; names text;
begin
  for o in select distinct coalesce(t.owner_email, 'eclips.ru@mail.ru') as em
            from public.tools t
            where t.active <> false and position(t.name in new.tools) > 0 loop
    select string_agg(distinct t2.name, ', ') into names
      from public.tools t2
      where t2.active <> false and position(t2.name in new.tools) > 0
        and coalesce(t2.owner_email, 'eclips.ru@mail.ru') = o.em;
    insert into public.order_parts(order_id, owner_email, tools_text, status)
    values (new.id, o.em, coalesce(names, ''), 'new');
  end loop;
  if not exists (select 1 from public.order_parts where order_id = new.id) then
    insert into public.order_parts(order_id, owner_email, tools_text, status)
    values (new.id, 'eclips.ru@mail.ru', coalesce(new.tools, ''), 'new');
  end if;
  perform public.fn_recalc_statuses();
  return new;
end $function$;

-- 2. Комиссия — как до 10.102
CREATE OR REPLACE FUNCTION public.fn_fee_accrue()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare d int; snap jsonb; rs numeric; pct int;
begin
  if new.status is distinct from old.status then
    if not ((old.status = 'new' and new.status in ('rented','closed')) or (old.status = 'rented' and new.status = 'closed')) then
      raise exception 'Недопустимый перевод статуса: % → %', old.status, new.status;
    end if;
  end if;
  if new.status = 'closed' and old.status is distinct from 'closed' and (new.fee is null or new.fee = 0) then
    select coalesce(days,1) into d from public.orders where id = new.order_id;
    d := coalesce(d,1);
    select coalesce(new.fee_pct, (select fee_pct from public.admins where lower(email)=lower(new.owner_email)), 5) into pct;
    select coalesce(jsonb_agg(jsonb_build_object('name', m.name, 'price', m.price)), '[]'::jsonb), coalesce(sum(m.price),0) * d into snap, rs
      from (select distinct t.name, t.price from public.tools t where t.active <> false and position(t.name in new.tools_text) > 0) m;
    if rs is not null and rs > 0 then
      new.price_snapshot := snap; new.rent_sum := rs;
      new.fee := round(rs * pct::numeric / 100.0);
    end if;
  end if;
  if new.status is distinct from old.status then
    insert into public.order_events(part_id, old_status, new_status, email) values (new.id, old.status, new.status, public.jwt_email());
  end if;
  return new;
end
$function$;

-- 3. «До конца аренды» — как в 10.77/10.95 (настройки проката)
DO $do$
BEGIN
  IF to_regclass('public.iva_rent_settings') IS NULL THEN
    RETURN;
  END IF;
  EXECUTE $f$
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
$f$;
  EXECUTE $f$
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
$f$;
END $do$;

-- 4. Новое из 10.102 — убрать
DROP FUNCTION IF EXISTS public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb);
DROP FUNCTION IF EXISTS public.iva_my_part_points();
DROP TRIGGER IF EXISTS trg_a_iva_part_guard ON public.order_parts;
DROP FUNCTION IF EXISTS public.iva_part_guard();
DROP FUNCTION IF EXISTS public.iva_order_items_clean(jsonb);
ALTER TABLE public.order_parts DROP COLUMN IF EXISTS point_id, DROP COLUMN IF EXISTS items;
ALTER TABLE public.orders DROP COLUMN IF EXISTS items;
NOTIFY pgrst, 'reload schema';

SELECT CASE WHEN to_regprocedure('public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb)') IS NULL
             AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
                             AND table_name = 'order_parts' AND column_name IN ('point_id', 'items'))
            THEN '✅ откат 10.102 выполнен' ELSE '❌ откат не выполнен' END AS result;

-- ============================================================
--  Ива · 10.102 — этап 2 «Сотрудники и пункты»: заявки по пунктам выдачи (решения владельца 17.10.2026,
--  docs/СОТРУДНИКИ-И-ПУНКТЫ.md). Применять ПОСЛЕ 20261026_tool_points.sql.
--
--  Что делает:
--   • заявка из приложения приходит со списком инструментов и сроком каждого (orders.items: [{id, days}]) —
--     новая функция iva_submit_order (9-й параметр p_items). Старая submit_order НЕ меняется: сайт и старые версии
--     приложения работают как раньше;
--   • часть заявки — на пару «прокат + пункт выдачи» (order_parts.point_id), у части — её инструменты с ценой на
--     момент заявки и своим сроком (order_parts.items: [{id, name, price, days}]). Заявка на два пункта одного проката —
--     две части, «Новая заявка» приходит по каждой (пуш и Telegram — на часть, письмо — одно на заявку, как было);
--   • заявка без списка (сайт, старое приложение) делится по названиям, как раньше, но: одна часть на прокат; пункт
--     ставится, если все найденные инструменты проката в одном пункте;
--   • комиссия при «Закрыта» — только по инструментам своей части: Σ (цена × свой срок). Части без списка (сайт) —
--     по названиям среди инструментов СВОЕГО проката (и пункта), срок — как раньше (orders.days);
--   • «До конца аренды»: время закрытия — из графика пункта части (нет пункта — как раньше), срок — самый длинный
--     в части;
--   • клиенту — пункты своих заявок (iva_my_part_points: название, адрес, город, график, телефон; без email);
--   • список инструментов и пункт части прокат через приложение/сайт поменять не может (защитный триггер).
--  Старые заявки (до этого SQL) не меняются: у них нет списка, их комиссия уже посчитана.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ: файл supabase/migrations/20261027_order_points_rollback.sql (целиком в SQL Editor) — возвращает прежние
--  fn_split_order, fn_fee_accrue и «До конца аренды» (тексты сняты с рабочей базы 17.10.2026), убирает новые функции,
--  триггер и столбцы:
--      DROP FUNCTION IF EXISTS public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb);
--      DROP FUNCTION IF EXISTS public.iva_my_part_points();
--      DROP TRIGGER IF EXISTS trg_a_iva_part_guard ON public.order_parts;
--      DROP FUNCTION IF EXISTS public.iva_part_guard();
--      DROP FUNCTION IF EXISTS public.iva_order_items_clean(jsonb);
--      ALTER TABLE public.order_parts DROP COLUMN IF EXISTS point_id, DROP COLUMN IF EXISTS items;
--      ALTER TABLE public.orders DROP COLUMN IF EXISTS items;
-- ============================================================

DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'tools' AND column_name = 'point_id') THEN
    RAISE EXCEPTION 'Сначала примените 20261026_tool_points.sql (SQL 10.101: инструмент ↔ пункт выдачи)';
  END IF;
  IF to_regprocedure('public.fn_recalc_statuses()') IS NULL OR to_regclass('public.order_events') IS NULL
     OR to_regprocedure('public.jwt_email()') IS NULL OR to_regclass('public.order_parts') IS NULL THEN
    RAISE EXCEPTION 'В базе нет order_parts / order_events / fn_recalc_statuses / jwt_email — это не база «Ивы»';
  END IF;
END $do$;

-- 1. Столбцы: список инструментов заявки, пункт и инструменты части
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS items jsonb;
ALTER TABLE public.order_parts ADD COLUMN IF NOT EXISTS point_id uuid;
ALTER TABLE public.order_parts ADD COLUMN IF NOT EXISTS items jsonb;
DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'order_parts_point_fk') THEN
    ALTER TABLE public.order_parts ADD CONSTRAINT order_parts_point_fk
      FOREIGN KEY (point_id) REFERENCES public.iva_pickup_points(id) ON DELETE SET NULL;
  END IF;
END $do$;
CREATE INDEX IF NOT EXISTS idx_order_parts_point ON public.order_parts (point_id);
COMMENT ON COLUMN public.orders.items IS 'Ива 10.102: инструменты заявки из приложения — [{id, days}] (iva_submit_order)';
COMMENT ON COLUMN public.order_parts.point_id IS 'Ива 10.102: пункт выдачи части заявки';
COMMENT ON COLUMN public.order_parts.items IS 'Ива 10.102: инструменты части — [{id, name, price, days}], цена на момент заявки';

-- 2. Список из приложения → чистый: только id-uuid без повторов, срок 1…365 (нет — 1), не больше 50; пусто — null
CREATE OR REPLACE FUNCTION public.iva_order_items_clean(p jsonb)
 RETURNS jsonb
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO 'public'
AS $function$
  select case when count(*) = 0 then null
              else jsonb_agg(jsonb_build_object('id', x.id, 'days', x.days) order by x.ord) end
  from (
    select distinct on (lower(e->>'id')) lower(e->>'id') as id, o as ord,
           greatest(1, least(365, case when coalesce(e->>'days', '') ~ '^\d{1,4}$' then (e->>'days')::int else 1 end)) as days
    from jsonb_array_elements(case when jsonb_typeof(p) = 'array' then p else '[]'::jsonb end) with ordinality as a(e, o)
    where jsonb_typeof(e) = 'object'
      and coalesce(e->>'id', '') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    order by lower(e->>'id'), o
  ) x
  where x.ord <= 50;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_items_clean(jsonb) FROM public, anon, authenticated;

-- 3. Заявка из приложения: как submit_order + список инструментов со сроками
CREATE OR REPLACE FUNCTION public.iva_submit_order(p_name text, p_phone text, p_tools text, p_get_method text DEFAULT ''::text,
  p_address text DEFAULT ''::text, p_comment text DEFAULT ''::text, p_user_email text DEFAULT ''::text,
  p_days integer DEFAULT NULL::integer, p_items jsonb DEFAULT NULL::jsonb)
 RETURNS bigint
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare new_id bigint;
begin
  insert into public.orders (name, phone, tools, get_method, address, comment, user_email, days, items)
  values (p_name, p_phone, p_tools, p_get_method, p_address, p_comment, nullif(p_user_email, ''), p_days,
          public.iva_order_items_clean(p_items))
  returning id into new_id;
  return new_id;
end $function$;
REVOKE ALL ON FUNCTION public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb) TO anon, authenticated;

-- 4. Деление заявки на части: со списком — по «прокат + пункт»; без списка — по названиям, одна часть на прокат
CREATE OR REPLACE FUNCTION public.fn_split_order()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare g record; v jsonb := public.iva_order_items_clean(new.items);
begin
  if v is not null then
    -- 10.102: инструменты из списка приложения (активные), цена — на момент заявки, срок — свой у каждого
    for g in
      select coalesce(t.owner_email, 'eclips.ru@mail.ru') as em, t.point_id as pid,
             string_agg(t.name, ', ' order by x.ord) as names,
             jsonb_agg(jsonb_build_object('id', t.id, 'name', t.name, 'price', coalesce(t.price, 0), 'days', x.days) order by x.ord) as its
      from (select (e->>'id')::uuid as id, (e->>'days')::int as days, o as ord
              from jsonb_array_elements(v) with ordinality as a(e, o)) x
      join public.tools t on t.id = x.id and t.active <> false
      group by 1, 2
      order by min(x.ord)
    loop
      insert into public.order_parts(order_id, owner_email, tools_text, status, point_id, items)
      values (new.id, g.em, coalesce(g.names, ''), 'new', g.pid, g.its);
    end loop;
  else
    -- как раньше (по названиям), но пункт — если все найденные инструменты проката в одном пункте
    for g in
      select coalesce(t.owner_email, 'eclips.ru@mail.ru') as em,
             string_agg(distinct t.name, ', ') as names,
             case when count(*) filter (where t.point_id is null) = 0 and count(distinct t.point_id) = 1
                  then (array_agg(t.point_id))[1] end as pid
      from public.tools t
      where t.active <> false and position(t.name in new.tools) > 0
      group by 1
    loop
      insert into public.order_parts(order_id, owner_email, tools_text, status, point_id)
      values (new.id, g.em, coalesce(g.names, ''), 'new', g.pid);
    end loop;
  end if;
  if not exists (select 1 from public.order_parts where order_id = new.id) then
    insert into public.order_parts(order_id, owner_email, tools_text, status)
    values (new.id, 'eclips.ru@mail.ru', coalesce(new.tools, ''), 'new');
  end if;
  perform public.fn_recalc_statuses();
  return new;
end $function$;

-- 5. Комиссия при «Закрыта» — только по инструментам своей части (переходы статусов и журнал — как были)
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
    select coalesce(new.fee_pct, (select fee_pct from public.admins where lower(email)=lower(new.owner_email)), 5) into pct;
    if jsonb_typeof(new.items) = 'array' and jsonb_array_length(new.items) > 0 then
      -- 10.102: Σ цена × свой срок (цена — на момент заявки)
      select coalesce(jsonb_agg(jsonb_build_object('name', e->>'name', 'price', (e->>'price')::numeric, 'days', (e->>'days')::int)), '[]'::jsonb),
             coalesce(sum((e->>'price')::numeric * greatest(1, (e->>'days')::int)), 0)
        into snap, rs
        from jsonb_array_elements(new.items) e;
    else
      -- без списка (сайт, старые заявки): по названиям, но только инструменты своего проката (и пункта)
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
  if new.status is distinct from old.status then
    insert into public.order_events(part_id, old_status, new_status, email) values (new.id, old.status, new.status, public.jwt_email());
  end if;
  return new;
end
$function$;

-- 6. Список инструментов и пункт части меняет только база (не прокат через приложение/сайт).
--    Пункт можно только снять (удаление пункта: ON DELETE SET NULL).
CREATE OR REPLACE FUNCTION public.iva_part_guard()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  if current_user in ('anon', 'authenticated') then
    new.items := old.items;
    if new.point_id is not null then new.point_id := old.point_id; end if;
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_part_guard() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_a_iva_part_guard ON public.order_parts;
CREATE TRIGGER trg_a_iva_part_guard BEFORE UPDATE ON public.order_parts
  FOR EACH ROW EXECUTE FUNCTION public.iva_part_guard();

-- 7. «До конца аренды»: график пункта части, срок — самый длинный в части (те же столбцы, что в 10.77)
DO $do$
BEGIN
  IF to_regclass('public.iva_rent_settings') IS NULL THEN
    RAISE NOTICE 'SQL 10.77 (20261014_rent_end_mode.sql) не применён — «До конца аренды» не меняется';
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
$f$;
  EXECUTE 'REVOKE ALL ON FUNCTION public.iva_my_rentals() FROM public, anon';
  EXECUTE 'GRANT EXECUTE ON FUNCTION public.iva_my_rentals() TO authenticated';
  EXECUTE 'REVOKE ALL ON FUNCTION public.iva_admin_rentals() FROM public, anon';
  EXECUTE 'GRANT EXECUTE ON FUNCTION public.iva_admin_rentals() TO authenticated';
END $do$;

-- 8. Клиенту: пункты его заявок (без email пункта и владельца)
CREATE OR REPLACE FUNCTION public.iva_my_part_points()
 RETURNS TABLE (part_id text, order_id text, tools_text text, point_id uuid, name text, address text, city text,
                open_min int[], close_min int[], tel text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id::text, p.order_id::text, coalesce(p.tools_text, ''), pp.id, pp.name, pp.address, pp.city,
         pp.open_min, pp.close_min, pp.tel
  from public.order_parts p
  join public.orders o on o.id = p.order_id
  join public.iva_pickup_points pp on pp.id = p.point_id
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and lower(btrim(coalesce(o.user_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
  order by p.order_id desc, p.id
  limit 300;
$function$;
REVOKE ALL ON FUNCTION public.iva_my_part_points() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_my_part_points() TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 9. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('столбцы списка и пункта',            (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
                                           AND ((table_name = 'orders' AND column_name = 'items')
                                             OR (table_name = 'order_parts' AND column_name IN ('point_id', 'items')))) = 3),
  ('пункт части → пункт выдачи',         EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'order_parts_point_fk')),
  ('заявка со списком — всем',           has_function_privilege('anon', 'public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb)', 'EXECUTE')
                                         AND has_function_privilege('authenticated', 'public.iva_submit_order(text, text, text, text, text, text, text, integer, jsonb)', 'EXECUTE')),
  ('старая submit_order на месте',       to_regprocedure('public.submit_order(text, text, text, text, text, text, text, integer)') IS NOT NULL),
  ('деление и комиссия по частям',       position('iva_order_items_clean' in pg_get_functiondef('public.fn_split_order()'::regprocedure)) > 0
                                         AND position('new.items' in pg_get_functiondef('public.fn_fee_accrue()'::regprocedure)) > 0),
  ('защита списка и пункта части',       EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_a_iva_part_guard' AND NOT tgisinternal)),
  ('пункты заявок — только вошедшим',    has_function_privilege('authenticated', 'public.iva_my_part_points()', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_my_part_points()', 'EXECUTE')),
  ('служебная функция закрыта',          NOT has_function_privilege('authenticated', 'public.iva_order_items_clean(jsonb)', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_order_items_clean(jsonb)', 'EXECUTE'))
) AS x(check_name, ok);

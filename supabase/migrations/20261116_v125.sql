-- ============================================================
--  Ива · 10.126 (файл 20261116) — чек заявки по факту: каждый инструмент считается по НАЧАТЫМ суткам от «Выдал» до «Вернули»
--  (просьба владельца 18.10.2026). Применять ПОСЛЕ 20261115_v124.sql. Сайт не меняется (он показывает ту же сумму
--  аренды и комиссию из базы).
--
--  Правила (ответы владельца 18.10.2026):
--   • считаются начатые сутки: сутки 1 — до конца первого срока («Выдал» + 24 ч или «до закрытия пункта» — как
--     у таймера), сутки 2 — до конца второго и т. д.;
--   • запас на возврат — 1 час (вернули через 24 ч 40 мин — это 1 сутки, через 25 ч 01 мин — 2);
--   • минимум 1 сутки; вернули раньше брони — дешевле; позже — начатые сутки просрочки досчитываются;
--   • во всех заявках (с одним сроком и с разными сроками внутри).
--   Пример: дрель на 1 сутки и пила на 2 суток, обе вернули через 20 часов → чек 1 сутки + 1 сутки.
--
--  Что делает:
--   • iva_rent_end / iva_used_days / iva_part_used_days — конец срока (как в приложении) и начатые сутки;
--   • «Вернули» по сроку (iva_staff_return) запоминает у инструментов начатые сутки (used_days);
--   • при закрытии заявки (любая «Вернули») у остальных инструментов ставится время возврата и начатые сутки;
--     сумма аренды и комиссия — по начатым суткам; в снимке цен — начатые сутки и бронь;
--   • заявка целиком (iva_order_part_card) отдаёт у инструмента начатые сутки — приложение пересчитывает чек.
--  Уже закрытые заявки не меняются. Без этого файла приложение 10.126 показывает чек по брони, как раньше.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ: Supabase → SQL Editor → вставить ВЕСЬ файл 20261116_v125_rollback.sql → Run (чек снова по брони;
--  отметки начатых суток в заявках остаются, но не используются; уже посчитанные суммы не меняются).
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_staff_return(bigint, integer)') IS NULL OR to_regprocedure('public.iva_item_days(jsonb, integer)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261115_v124.sql (SQL 10.124: сроки внутри заявки)';
  END IF;
  IF to_regclass('public.iva_rent_settings') IS NULL OR to_regprocedure('public.jwt_email()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261014_rent_end_mode.sql (срок аренды)';
  END IF;
END $do$;

-- 1. Конец срока — как в приложении (IvaRentPatch.end): «Сутки» — «Выдал» + дни × 24 ч; «До закрытия пункта» —
--    в день выдачи + дни (по часовому поясу проката) во время закрытия, выходной — следующий рабочий день.
CREATE OR REPLACE FUNCTION public.iva_rent_end(p_at timestamptz, p_days integer, p_mode text, p_close integer[], p_tz text)
 RETURNS timestamptz
 LANGUAGE plpgsql
 STABLE
 SET search_path TO 'public'
AS $function$
declare
  z text := coalesce(nullif(btrim(p_tz), ''), 'Europe/Moscow');
  d date;
  m int;
  i int;
begin
  if p_at is null or coalesce(p_days, 0) <= 0 then return null; end if;
  if coalesce(p_mode, 'day') <> 'close' or p_close is null or array_length(p_close, 1) is distinct from 7
     or not exists (select 1 from unnest(p_close) c where c >= 0) then
    return p_at + p_days * interval '24 hours';
  end if;
  d := (p_at at time zone z)::date + p_days;
  for i in 0..6 loop
    m := p_close[extract(isodow from d)::int];
    if m is not null and m >= 0 then
      return (d + m * interval '1 minute') at time zone z;
    end if;
    d := d + 1;
  end loop;
  return p_at + p_days * interval '24 hours';
end
$function$;

-- 2. Начатые сутки: сколько концов срока прошло до возврата (+ 1 час запаса), минимум 1, не больше 365.
--    «До закрытия пункта»: если после дня выдачи выходной, концы 1-х и 2-х суток совпадают — считаем их один раз.
CREATE OR REPLACE FUNCTION public.iva_used_days(p_at timestamptz, p_ret timestamptz, p_mode text, p_close integer[], p_tz text)
 RETURNS integer
 LANGUAGE plpgsql
 STABLE
 SET search_path TO 'public'
AS $function$
declare
  k    int := 1;
  n    int := 0;
  e    timestamptz;
  prev timestamptz;
begin
  if p_at is null or p_ret is null then return null; end if;
  loop
    e := public.iva_rent_end(p_at, k, p_mode, p_close, p_tz);
    if prev is null or e > prev then n := n + 1; prev := e; end if;
    exit when p_ret <= e + interval '1 hour' or k >= 365 or n >= 365;
    k := k + 1;
  end loop;
  return greatest(1, least(n, 365));
end
$function$;

-- 3. То же по заявке: правило проката и график пункта — как у таймера (iva_order_part_card / iva_my_rentals)
CREATE OR REPLACE FUNCTION public.iva_part_used_days(p_owner text, p_point uuid, p_at timestamptz, p_ret timestamptz)
 RETURNS integer
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select public.iva_used_days(p_at, p_ret, coalesce(rs.mode, 'day'),
                              coalesce(pp.close_min, rs.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
                              coalesce(rs.tz, 'Europe/Moscow'))
    from (select 1) one
    left join public.iva_rent_settings rs on rs.email = lower(btrim(coalesce(p_owner, '')))
    left join public.iva_pickup_points pp on pp.id = p_point;
$function$;

-- 4. Сутки в чеке: начатые (если уже посчитаны), иначе бронь
CREATE OR REPLACE FUNCTION public.iva_bill_days(p_item jsonb, p_order_days integer)
 RETURNS integer
 LANGUAGE sql
 IMMUTABLE
AS $function$
  select case when coalesce(p_item ->> 'used_days', '') ~ '^\d{1,3}$' then greatest(1, least(365, (p_item ->> 'used_days')::int))
              else public.iva_item_days(p_item, p_order_days) end;
$function$;
REVOKE ALL ON FUNCTION public.iva_rent_end(timestamptz, integer, text, integer[], text) FROM public, anon;
REVOKE ALL ON FUNCTION public.iva_used_days(timestamptz, timestamptz, text, integer[], text) FROM public, anon;
REVOKE ALL ON FUNCTION public.iva_part_used_days(text, uuid, timestamptz, timestamptz) FROM public, anon, authenticated;
REVOKE ALL ON FUNCTION public.iva_bill_days(jsonb, integer) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_rent_end(timestamptz, integer, text, integer[], text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.iva_used_days(timestamptz, timestamptz, text, integer[], text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.iva_bill_days(jsonb, integer) TO authenticated;

-- 5. Закрытие заявки: сумма аренды и комиссия — по начатым суткам (статусы и остальное — как в 10.122)
CREATE OR REPLACE FUNCTION public.fn_fee_accrue()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare d int; od int; snap jsonb; rs numeric; pct int;
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
      -- 10.125: чек по факту — у выданной заявки каждый инструмент считается по начатым суткам от «Выдал» до
      -- возврата (запас 1 час, минимум 1 сутки, просрочка досчитывается). Кто ещё не отмечен «Вернули» — вернули сейчас.
      select least(coalesce(nullif(regexp_replace(coalesce(o.days::text, ''), '\D', '', 'g'), '')::numeric, 0), 365)::int
        into od from public.orders o where o.id = new.order_id;
      if new.rented_at is not null then
        select jsonb_agg(case when coalesce(x.e ->> 'used_days', '') ~ '^\d{1,3}$' then x.e
                              else x.e || jsonb_strip_nulls(jsonb_build_object(
                                     'returned_at', case when coalesce(x.e ->> 'returned_at', '') = '' then to_jsonb(now()) else x.e -> 'returned_at' end,
                                     'returned_by', case when coalesce(x.e ->> 'returned_at', '') = '' then coalesce(public.jwt_email(), '') end,
                                     'used_days', public.iva_part_used_days(new.owner_email, new.point_id, new.rented_at,
                                                    coalesce(nullif(x.e ->> 'returned_at', '')::timestamptz, now())))) end
                         order by x.n)
          into snap from jsonb_array_elements(new.items) with ordinality as x(e, n);
        new.items := snap;
      end if;
      select coalesce(jsonb_agg(jsonb_build_object('name', e->>'name', 'price', (e->>'price')::numeric,
                                                   'days', public.iva_bill_days(e, od), 'booked', public.iva_item_days(e, od))), '[]'::jsonb),
             coalesce(sum((e->>'price')::numeric * public.iva_bill_days(e, od)), 0)
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

-- 6. «Вернули» по сроку — запоминает начатые сутки у инструментов этого срока
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
  select p.id, p.status, p.point_id, lower(btrim(coalesce(p.owner_email, ''))) as owner, p.items, p.rented_at,
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
                        then x.e || jsonb_strip_nulls(jsonb_build_object('returned_at', now(), 'returned_by', me,
                                       'used_days', public.iva_part_used_days(pt.owner, pt.point_id, pt.rented_at, now())))
                        else x.e end order by x.n)
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

-- 7. Заявка целиком — у инструмента начатые сутки (used_days)
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
                       'returned_at', e.v -> 'returned_at',
                       'used_days', e.v -> 'used_days') order by e.n)
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

NOTIFY pgrst, 'reload schema';

-- 8. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('начатые сутки: 20 ч → 1, 24 ч 59 мин → 1 (запас час), 25 ч 01 мин → 2, 3 суток 2 ч → 4',
     public.iva_used_days('2026-10-18 10:00+03', '2026-10-19 06:00+03', 'day', null, 'Europe/Moscow') = 1
     AND public.iva_used_days('2026-10-18 10:00+03', '2026-10-19 10:59+03', 'day', null, 'Europe/Moscow') = 1
     AND public.iva_used_days('2026-10-18 10:00+03', '2026-10-19 11:01+03', 'day', null, 'Europe/Moscow') = 2
     AND public.iva_used_days('2026-10-18 10:00+03', '2026-10-21 12:00+03', 'day', null, 'Europe/Moscow') = 4),
  ('«до закрытия пункта»: выдали в субботу, воскресенье выходной — 1 сутки до пн 19:00, потом 2-е до вт 19:00',
     public.iva_used_days('2026-10-17 12:00+03', '2026-10-19 19:30+03', 'close', '{1140,1140,1140,1140,1140,1140,-1}', 'Europe/Moscow') = 1
     AND public.iva_used_days('2026-10-17 12:00+03', '2026-10-19 20:01+03', 'close', '{1140,1140,1140,1140,1140,1140,-1}', 'Europe/Moscow') = 2),
  ('закрытие и «Вернули» по сроку считают начатые сутки',
     pg_get_functiondef('public.fn_fee_accrue()'::regprocedure) LIKE '%iva_bill_days%'
     AND pg_get_functiondef('public.fn_fee_accrue()'::regprocedure) LIKE '%iva.part_cancel%'
     AND pg_get_functiondef('public.iva_staff_return(bigint, integer)'::regprocedure) LIKE '%used_days%'),
  ('заявка целиком отдаёт начатые сутки; служебная функция закрыта',
     pg_get_functiondef('public.iva_order_part_card(bigint, boolean)'::regprocedure) LIKE '%used_days%'
     AND NOT has_function_privilege('authenticated', 'public.iva_part_used_days(text, uuid, timestamptz, timestamptz)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_part_used_days(text, uuid, timestamptz, timestamptz)', 'EXECUTE'))
) AS x(check_name, ok);

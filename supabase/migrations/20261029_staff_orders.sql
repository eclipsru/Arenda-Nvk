-- ============================================================
--  Ива · 10.104 — этап 3б «Сотрудники и пункты»: сотрудник видит заявки своих пунктов и отмечает «Выдал» / «Вернули»
--  (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
--  Применять ПОСЛЕ 20261027_order_points.sql (пункт у части заявки) и 20261028_staff.sql (сотрудники).
--
--  Что делает:
--   • iva_staff_parts() — сотруднику (принял приглашение, прокат включён) с правом «Выдаёт и принимает» или
--     «Получает уведомления»: части заявок ТОЛЬКО его пунктов — новые, выданные и закрытые за 7 дней (кроме архива).
--     В строке: пункт, инструменты, состояние, когда выдан, срок, имя и телефон клиента, получение, комментарий.
--     Денег (сумма, комиссия), почты клиента и паспортных данных — нет;
--   • iva_staff_set_status(part, 'rented' | 'closed') — «Выдал» / «Вернули». Только с правом «Выдаёт и принимает»,
--     только в своём пункте, только new → rented → closed. Свою заявку (сотрудник оформил её как клиент) — только если
--     владелец включил ему «Может выдавать заявки самому себе». Кто нажал — база пишет в журнал статусов
--     (order_events.email) сама, как и для владельца; комиссия при «Закрыта» считается как всегда;
--   • части без пункта (старые, с сайта, если пункт не определился) сотрудникам не видны — их ведёт владелец.
--  Таблицы и правила доступа не меняются: всё — через эти две функции.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (сотрудники перестанут видеть заявки; больше ничего не трогается):
--      DROP FUNCTION IF EXISTS public.iva_staff_set_status(bigint, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_parts();
--      DROP FUNCTION IF EXISTS public.iva_staff_right(uuid, text);
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL OR to_regclass('public.iva_staff_points') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261028_staff.sql (SQL 10.103: сотрудники)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'point_id') THEN
    RAISE EXCEPTION 'Сначала примените 20261027_order_points.sql (SQL 10.102: заявки по пунктам)';
  END IF;
END $do$;

-- 1. Право вошедшего сотрудника в пункте: 'issue' / 'catalog' / 'chat' / 'notify' (принял приглашение, прокат включён)
CREATE OR REPLACE FUNCTION public.iva_staff_right(p_point uuid, p_right text)
 RETURNS boolean
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select coalesce(auth.jwt() ->> 'email', '') <> '' and exists (
    select 1
      from public.iva_staff s
      join public.iva_staff_points sp on sp.staff_id = s.id and sp.point_id = p_point
      join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = s.owner_email
      join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
     where s.email = lower(btrim(auth.jwt() ->> 'email'))
       and s.status = 'active'
       and case p_right when 'issue' then s.can_issue when 'catalog' then s.can_catalog
                        when 'chat' then s.can_chat when 'notify' then s.can_notify else false end);
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_right(uuid, text) FROM public, anon, authenticated;

-- 2. Сотруднику: заявки своих пунктов (без денег и почты клиента)
CREATE OR REPLACE FUNCTION public.iva_staff_parts()
 RETURNS TABLE (part_id text, order_id text, point_id uuid, point_name text, tools_text text, status text,
                created_at timestamptz, rented_at timestamptz, days int, client_name text, client_tel text,
                get_method text, comment text, mine boolean, can_act boolean, can_notify boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id::text, p.order_id::text, p.point_id, pp.name, coalesce(p.tools_text, ''), p.status, p.created_at,
         (to_jsonb(p) ->> 'rented_at')::timestamptz,
         coalesce((select max(greatest(1, least(365, (e ->> 'days')::int))) from jsonb_array_elements(
                     case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end) e
                    where coalesce(e ->> 'days', '') ~ '^\d{1,4}$'),
                  greatest(1, least(365, coalesce(o.days, 1)))),
         coalesce(o.name, ''), coalesce(o.phone, ''), coalesce(o.get_method, ''), coalesce(o.comment, ''),
         lower(btrim(coalesce(o.user_email, ''))) = s.email,
         s.can_issue and p.status in ('new', 'rented') and (s.self_issue or lower(btrim(coalesce(o.user_email, ''))) <> s.email),
         s.can_notify
  from public.iva_staff s
  join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
  join public.iva_staff_points sp on sp.staff_id = s.id
  join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = s.owner_email
  join public.order_parts p on p.point_id = sp.point_id and lower(btrim(p.owner_email)) = s.owner_email
  join public.orders o on o.id = p.order_id
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and s.email = lower(btrim(auth.jwt() ->> 'email'))
    and s.status = 'active'
    and (s.can_issue or s.can_notify)
    and coalesce(p.archived, false) = false
    and (p.status in ('new', 'rented') or p.created_at > now() - interval '7 days')
  order by p.id desc
  limit 200;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_parts() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_parts() TO authenticated;

-- 3. Сотрудник: «Выдал» (new → rented) / «Вернули» (rented → closed)
CREATE OR REPLACE FUNCTION public.iva_staff_set_status(p_part bigint, p_status text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  pt  record;
  slf boolean;
begin
  if p_status not in ('rented', 'closed') then
    raise exception 'Можно отметить только «Выдал» или «Вернули»';
  end if;
  select p.id, p.status, p.point_id, p.owner_email, lower(btrim(coalesce(o.user_email, ''))) as client
    into pt
    from public.order_parts p join public.orders o on o.id = p.order_id
   where p.id = p_part
   for update of p;
  if pt.id is null or pt.point_id is null or me = '' or not public.iva_staff_right(pt.point_id, 'issue') then
    raise exception 'Заявка не найдена в вашем пункте';
  end if;
  select s.self_issue into slf from public.iva_staff s where s.email = me and s.status = 'active';
  if pt.client = me and not coalesce(slf, false) then
    raise exception 'Свою заявку выдаёт и принимает другой сотрудник или владелец';
  end if;
  if not ((pt.status = 'new' and p_status = 'rented') or (pt.status = 'rented' and p_status = 'closed')) then
    raise exception 'Заявка уже в другом состоянии — обновите список';
  end if;
  update public.order_parts set status = p_status where id = pt.id;   -- журнал и комментарий считают триггеры, как всегда
  return p_status;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_set_status(bigint, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_set_status(bigint, text) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 4. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('заявки пункта — только вошедшим',     has_function_privilege('authenticated', 'public.iva_staff_parts()', 'EXECUTE')
                                          AND NOT has_function_privilege('anon', 'public.iva_staff_parts()', 'EXECUTE')),
  ('«Выдал» / «Вернули» — только вошедшим', has_function_privilege('authenticated', 'public.iva_staff_set_status(bigint, text)', 'EXECUTE')
                                          AND NOT has_function_privilege('anon', 'public.iva_staff_set_status(bigint, text)', 'EXECUTE')),
  ('проверка прав закрыта',               NOT has_function_privilege('authenticated', 'public.iva_staff_right(uuid, text)', 'EXECUTE')
                                          AND NOT has_function_privilege('anon', 'public.iva_staff_right(uuid, text)', 'EXECUTE'))
) AS x(check_name, ok);

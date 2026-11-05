-- ============================================================
--  Ива, сайт 05.11.2026 — «Статистика» в «Работе» сотрудника проката.
--
--  Что делает:
--   • iva_staff_stats(пункт, дней) — числа по ОДНОМУ своему пункту выдачи за 7 / 30 / 90 дней:
--     заявок, выдано, вернули, отменено, сейчас в аренде, просрочено сейчас, средний срок аренды,
--     сколько выдал и принял лично этот сотрудник, популярный инструмент, заявки и выдачи по дням.
--   • Только сотрудник (iva_staff, статус «active») с правом «Выдача» или «Уведомления о заявках» и только пункт,
--     в который его добавил владелец проката. Чужой пункт — ошибка.
--   • Без денег (выручка, комиссия, залог) и без данных клиентов (имя, телефон, почта) — правило владельца.
--   • Таблицы не меняет; новых данных не собирает: считает по order_parts (issued_by / returned_by — SQL 20261101).
--
--   • iva_staff_chat_unread() — для списка чатов справа в «Работе»: с какого времени в каждой переписке лежит
--     непрочитанное сообщение клиента (красный — не просмотрено больше суток). Те же переписки и те же права,
--     что у iva_staff_chats / iva_staff_point_chats (право «Отвечает в чате пункта»). Текстов не отдаёт.
--
--  Без этого SQL сайт работает как раньше: «Статистика» считает по заявкам, которые уже видны сотруднику
--  (активные и закрытые за 7 дней), и пишет, что полная статистика — после SQL.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261125_staff_stats_rollback.sql (удаляет только эти две функции).
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL OR to_regclass('public.iva_staff_points') IS NULL THEN
    RAISE EXCEPTION 'В базе нет сотрудников (iva_staff) — сначала SQL 20261028_staff';
  END IF;
  IF to_regclass('public.order_parts') IS NULL OR to_regclass('public.iva_pickup_points') IS NULL THEN
    RAISE EXCEPTION 'В базе нет order_parts / iva_pickup_points — сначала SQL пунктов выдачи (20261027)';
  END IF;
END $do$;

CREATE OR REPLACE FUNCTION public.iva_staff_stats(p_point uuid, p_days int DEFAULT 30)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me    text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  d     int  := greatest(1, least(coalesce(p_days, 30), 365));
  since timestamptz := now() - make_interval(days => greatest(1, least(coalesce(p_days, 30), 365)));
  sid   uuid;
  own   text;
  ok    boolean;
  res   jsonb;
begin
  if me = '' then
    raise exception 'Нужен вход';
  end if;
  select s.id, s.owner_email, (s.can_issue or s.can_notify)
    into sid, own, ok
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
   where s.email = me and s.status = 'active'
   limit 1;
  if sid is null then
    raise exception 'Статистика — только для сотрудника проката';
  end if;
  if not ok then
    raise exception 'Нет права видеть заявки пункта';
  end if;
  if p_point is null or not exists (
       select 1 from public.iva_staff_points sp
         join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = own
        where sp.staff_id = sid and sp.point_id = p_point) then
    raise exception 'Это не ваш пункт выдачи';
  end if;

  with parts as (
    select p.id, p.status, p.created_at,
           coalesce(p.tools_text, '') as tools_text,
           case when jsonb_typeof(to_jsonb(p) -> 'items') = 'array' then to_jsonb(p) -> 'items' else '[]'::jsonb end as items,
           (to_jsonb(p) ->> 'rented_at')::timestamptz   as rented_at,
           (to_jsonb(p) ->> 'returned_at')::timestamptz as returned_at,
           lower(btrim(coalesce(to_jsonb(p) ->> 'issued_by', '')))   as iss,
           lower(btrim(coalesce(to_jsonb(p) ->> 'returned_by', ''))) as ret,
           coalesce((select max(greatest(1, least(365, (e ->> 'days')::int)))
                       from jsonb_array_elements(case when jsonb_typeof(to_jsonb(p) -> 'items') = 'array'
                                                      then to_jsonb(p) -> 'items' else '[]'::jsonb end) e
                      where coalesce(e ->> 'days', '') ~ '^\d{1,4}$'),
                    greatest(1, least(365, coalesce(o.days, 1)))) as days
      from public.order_parts p
      join public.orders o on o.id = p.order_id
     where p.point_id = p_point
       and lower(btrim(p.owner_email)) = own
  ),
  per as (
    select * from parts where created_at >= since or rented_at >= since or returned_at >= since
  ),
  tools as (
    select nm, count(*)::int as n
      from (select coalesce(nullif(btrim(e ->> 'name'), ''), nullif(btrim(x.tools_text), '')) as nm
              from per x
              left join lateral jsonb_array_elements(x.items) e on true
             where x.created_at >= since and x.status <> 'cancelled') t
     where nm is not null
     group by nm
     order by count(*) desc, nm
     limit 5
  ),
  daily as (
    select g::date as day,
           (select count(*) from per x where x.created_at >= g and x.created_at < g + interval '1 day')::int as n,
           (select count(*) from per x where x.rented_at  >= g and x.rented_at  < g + interval '1 day')::int as i
      from generate_series(date_trunc('day', now()) - make_interval(days => d - 1), date_trunc('day', now()), interval '1 day') g
  )
  select jsonb_build_object(
    'days',        d,
    'orders',      (select count(*) from per where created_at >= since)::int,
    'issued',      (select count(*) from per where rented_at >= since)::int,
    'returned',    (select count(*) from per where status = 'closed' and coalesce(returned_at, created_at) >= since and rented_at is not null)::int,
    'cancelled',   (select count(*) from per where status = 'cancelled' and created_at >= since)::int,
    'rented_now',  (select count(*) from parts where status = 'rented')::int,
    'late_now',    (select count(*) from parts where status = 'rented' and rented_at is not null
                                                 and rented_at + make_interval(days => days) < now())::int,
    'avg_days',    (select round(avg(extract(epoch from (returned_at - rented_at)) / 86400.0)::numeric, 1)
                      from per where status = 'closed' and rented_at is not null and returned_at >= since and returned_at > rented_at),
    'my_issued',   (select count(*) from per where iss = me and rented_at >= since)::int,
    'my_returned', (select count(*) from per where ret = me and returned_at >= since)::int,
    'journal',     exists (select 1 from information_schema.columns
                            where table_schema = 'public' and table_name = 'order_parts' and column_name = 'issued_by'),
    'top',         coalesce((select jsonb_agg(jsonb_build_object('name', nm, 'n', n) order by n desc, nm) from tools), '[]'::jsonb),
    'daily',       coalesce((select jsonb_agg(jsonb_build_object('d', to_char(day, 'YYYY-MM-DD'), 'n', n, 'i', i) order by day) from daily), '[]'::jsonb)
  ) into res;
  return res;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_stats(uuid, int) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_stats(uuid, int) TO authenticated;
COMMENT ON FUNCTION public.iva_staff_stats(uuid, int) IS 'Ива, сайт 05.11.2026: «Статистика» сотрудника по своему пункту — только числа, без денег и данных клиентов';

-- Чаты справа в «Работе»: с какого времени не прочитано (05.11.2026, правки владельца).
-- plpgsql: файл применяется и в базе без чатов пункта (SQL 20261111) — тогда считаются только чаты по инструменту.
CREATE OR REPLACE FUNCTION public.iva_staff_chat_unread()
 RETURNS TABLE (kind text, key uuid, peer text, first_unread_at timestamptz)
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  pts boolean := to_regprocedure('public.iva_staff_chat_points()') is not null;
begin
  if to_regprocedure('public.iva_staff_chat_me()') is null or to_regprocedure('public.iva_staff_chat_tools()') is null then
    return;                                                -- чатов сотрудника в базе нет (SQL 20261031)
  end if;
  return query
  with me as (select * from public.iva_staff_chat_me()),
  m as (
    select case when x.tool_id is not null then 't' else 'p' end as k,
           coalesce(x.tool_id, x.point_id) as id,
           lower(btrim(x.from_email)) as pe,
           x.created_at as at
      from me
      join public.app_messages x on lower(btrim(x.to_email)) = me.owner
     where x.created_at > now() - interval '90 days'
       and not coalesce(x.is_read, false)
       and lower(btrim(coalesce(x.from_email, ''))) not in ('', me.owner, me.email)
       and ((x.tool_id is not null and x.tool_id in (select t.tool_id from public.iva_staff_chat_tools() t))
         or (pts and x.tool_id is null and x.point_id is not null
             and x.point_id in (select p.point_id from public.iva_staff_chat_points() p)))
  )
  select m.k, m.id, md5(m.pe), min(m.at)
    from m
   group by m.k, m.id, m.pe
   limit 300;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat_unread() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_chat_unread() TO authenticated;
COMMENT ON FUNCTION public.iva_staff_chat_unread() IS 'Ива, сайт 05.11.2026: чаты сотрудника — с какого времени не прочитано (без текстов)';

NOTIFY pgrst, 'reload schema';

-- Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('статистика сотрудника — функция есть',
     to_regprocedure('public.iva_staff_stats(uuid, integer)') IS NOT NULL),
  ('без входа вызвать нельзя',
     NOT has_function_privilege('anon', 'public.iva_staff_stats(uuid, integer)', 'EXECUTE')),
  ('вошедшим — можно (внутри — только сотрудник и только свой пункт)',
     has_function_privilege('authenticated', 'public.iva_staff_stats(uuid, integer)', 'EXECUTE')),
  ('чаты сотрудника: «не прочитано с …» — функция есть, без входа нельзя',
     to_regprocedure('public.iva_staff_chat_unread()') IS NOT NULL
     AND NOT has_function_privilege('anon', 'public.iva_staff_chat_unread()', 'EXECUTE')),
  ('журнал «кто выдал / кто принял» (SQL 20261101) — для «Мой вклад»',
     EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'issued_by'))
) AS x(check_name, ok);

-- ============================================================
--  Ива, сайт и приложение 10.140 (просьба владельца 07.11.2026) — «Обращения» и счётчик скачиваний.
--
--  Что делает:
--   1. Счётчик скачиваний приложения (страница app.html на телефоне, как в Google Play: «10+», «50+», «100+»…).
--      • iva_app_downloads — одна строка на браузер (случайный номер браузера, без IP, cookies и данных человека).
--      • iva_download_hit(номер) — отметить скачивание (повтор с того же браузера не считается);
--        iva_download_count() — сколько скачали, округлено вниз до 1 / 5 / 10 / 50 / 100 / 500 / 1 000 …
--        Точное число наружу не отдаётся. Защита от накрутки: не больше 60 новых скачиваний за 10 минут.
--   2. Нажатия «Позвонить» (решение владельца 07.11.2026: в приложении и на сайте).
--      • iva_call_taps — прокат, пункт выдачи (если известен), откуда (app / site), время. Без данных клиента.
--      • iva_call_tap(пункт, инструмент, прокат, откуда) — отметить нажатие. Прокат и пункт база берёт сама:
--        из пункта, из инструмента или (если не известно ни то, ни другое) — почта действующего проката.
--        Не больше 20 нажатий в минуту на один прокат.
--      Сам звонок приложение и сайт не видят — считаются только нажатия «Позвонить», и только с этого обновления.
--   3. iva_appeals(пункт) — «Обращения» владельца проката (приложение → «Кабинет» → «Обращения»):
--      заявки, звонки и сообщения за сегодня, текущую неделю (с понедельника), текущий месяц (с 1-го числа)
--      и за всё время, по московскому времени. Пункт пустой — все пункты проката.
--      • Заявки — заявки (orders) с частью этого проката (и этого пункта); одна заявка = одно обращение.
--      • Звонки — нажатия «Позвонить» из п. 2.
--      • Сообщения — клиент написал прокату: один клиент за день = одно обращение (сколько бы сообщений ни прислал).
--        Свои сотрудники не считаются. Пункт — пункт переписки или пункт инструмента, о котором переписка.
--      Только сам владелец проката и только свои пункты. Текстов, имён и телефонов не отдаёт — только числа.
--
--  Таблицы заявок, сообщений и инструментов не меняет.
--  Без этого SQL: на странице приложения счётчика нет, в приложении «Обращения» пишет, какой SQL нужен.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261128_appeals_rollback.sql.
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.order_parts') IS NULL OR to_regclass('public.orders') IS NULL OR to_regclass('public.admins') IS NULL
     OR to_regclass('public.tools') IS NULL THEN
    RAISE EXCEPTION 'В базе нет orders / order_parts / admins / tools — это не база «Ивы»';
  END IF;
  IF to_regclass('public.iva_pickup_points') IS NULL THEN
    RAISE EXCEPTION 'В базе нет пунктов выдачи (iva_pickup_points) — сначала SQL 20261023_pickup_points';
  END IF;
  IF to_regclass('public.app_messages') IS NULL THEN
    RAISE EXCEPTION 'В базе нет переписки (app_messages)';
  END IF;
END $do$;

-- ── 1. Скачивания приложения ──
CREATE TABLE IF NOT EXISTS public.iva_app_downloads (
  dev text        PRIMARY KEY CHECK (dev ~ '^[A-Za-z0-9_-]{8,64}$'),
  at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.iva_app_downloads ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.iva_app_downloads FROM public, anon, authenticated;
CREATE INDEX IF NOT EXISTS idx_iva_app_downloads_at ON public.iva_app_downloads (at);
COMMENT ON TABLE public.iva_app_downloads IS 'Ива, 07.11.2026: скачивания приложения со страницы app.html — один браузер = одна строка (случайный номер, без данных человека)';

-- «Как в Google Play»: 0 → 0; 1…4 → 1; 5…9 → 5; 10…49 → 10; 50…99 → 50; 100…499 → 100 …
CREATE OR REPLACE FUNCTION public.iva_download_bucket(n bigint)
 RETURNS bigint
 LANGUAGE sql
 IMMUTABLE
 SET search_path TO 'public'
AS $function$
  select case when coalesce(n, 0) < 1 then 0::bigint
              else (select case when n >= 5 * p then 5 * p else p end
                      from (select (10::numeric ^ floor(log(10, n::numeric)))::bigint as p) x)
         end;
$function$;
REVOKE ALL ON FUNCTION public.iva_download_bucket(bigint) FROM public, anon, authenticated;
COMMENT ON FUNCTION public.iva_download_bucket(bigint) IS 'Ива, 07.11.2026: округление счётчика скачиваний как в Google Play (служебная)';

CREATE OR REPLACE FUNCTION public.iva_download_count()
 RETURNS bigint
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select public.iva_download_bucket((select count(*) from public.iva_app_downloads));
$function$;
REVOKE ALL ON FUNCTION public.iva_download_count() FROM public;
GRANT EXECUTE ON FUNCTION public.iva_download_count() TO anon, authenticated;
COMMENT ON FUNCTION public.iva_download_count() IS 'Ива, 07.11.2026: сколько раз скачали приложение — округлено (10+, 50+, 100+ …)';

CREATE OR REPLACE FUNCTION public.iva_download_hit(p_dev text)
 RETURNS bigint
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  d text := btrim(coalesce(p_dev, ''));
begin
  if d ~ '^[A-Za-z0-9_-]{8,64}$'
     and (select count(*) from public.iva_app_downloads where at > now() - interval '10 minutes') < 60 then
    insert into public.iva_app_downloads(dev) values (d) on conflict (dev) do nothing;
  end if;
  return public.iva_download_bucket((select count(*) from public.iva_app_downloads));
end
$function$;
REVOKE ALL ON FUNCTION public.iva_download_hit(text) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_download_hit(text) TO anon, authenticated;
COMMENT ON FUNCTION public.iva_download_hit(text) IS 'Ива, 07.11.2026: отметить скачивание приложения (один браузер — один раз)';

-- ── 2. Нажатия «Позвонить» ──
CREATE TABLE IF NOT EXISTS public.iva_call_taps (
  id          bigint      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  at          timestamptz NOT NULL DEFAULT now(),
  owner_email text        NOT NULL CHECK (owner_email = lower(btrim(owner_email)) AND owner_email <> ''),
  point_id    uuid,
  src         text        NOT NULL DEFAULT 'app' CHECK (src IN ('app', 'site'))
);
ALTER TABLE public.iva_call_taps ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.iva_call_taps FROM public, anon, authenticated;
CREATE INDEX IF NOT EXISTS idx_iva_call_taps_owner ON public.iva_call_taps (owner_email, at);
COMMENT ON TABLE public.iva_call_taps IS 'Ива, 07.11.2026: нажатия «Позвонить» (прокат, пункт, откуда, время) — без данных клиента';

CREATE OR REPLACE FUNCTION public.iva_call_tap(p_point uuid, p_tool uuid, p_owner text, p_src text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  own text;
  pt  uuid;
  s   text := case when p_src = 'site' then 'site' else 'app' end;
begin
  if p_point is not null then
    select pp.owner_email, pp.id into own, pt from public.iva_pickup_points pp where pp.id = p_point;
  end if;
  if own is null and p_tool is not null then
    select lower(btrim(coalesce(t.owner_email, ''))), (to_jsonb(t) ->> 'point_id')::uuid into own, pt
      from public.tools t where t.id = p_tool;
    if pt is not null and not exists (select 1 from public.iva_pickup_points pp where pp.id = pt and pp.owner_email = own) then
      pt := null;
    end if;
  end if;
  if coalesce(own, '') = '' then
    own := lower(btrim(coalesce(p_owner, '')));
    pt := null;
  end if;
  if own = '' or not exists (select 1 from public.admins a where lower(btrim(a.email)) = own and a.active) then
    return false;
  end if;
  if (select count(*) from public.iva_call_taps c where c.owner_email = own and c.at > now() - interval '1 minute') >= 20 then
    return false;
  end if;
  insert into public.iva_call_taps(owner_email, point_id, src) values (own, pt, s);
  return true;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_call_tap(uuid, uuid, text, text) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_call_tap(uuid, uuid, text, text) TO anon, authenticated;
COMMENT ON FUNCTION public.iva_call_tap(uuid, uuid, text, text) IS 'Ива, 07.11.2026: отметить нажатие «Позвонить» (прокат и пункт база определяет сама)';

-- ── 3. «Обращения» владельца проката ──
CREATE OR REPLACE FUNCTION public.iva_appeals(p_point uuid DEFAULT NULL)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  tz  text := 'Europe/Moscow';
  loc timestamp := now() at time zone 'Europe/Moscow';
  d0  timestamptz;
  w0  timestamptz;
  m0  timestamptz;
  stf boolean := to_regclass('public.iva_staff') is not null;
  res jsonb;
begin
  if me = '' then
    raise exception 'Нужен вход';
  end if;
  if not exists (select 1 from public.admins a where lower(btrim(a.email)) = me and a.active) then
    raise exception 'Обращения — только у владельца проката';
  end if;
  if p_point is not null and not exists (select 1 from public.iva_pickup_points pp where pp.id = p_point and pp.owner_email = me) then
    raise exception 'Это не ваш пункт выдачи';
  end if;
  d0 := date_trunc('day', loc) at time zone tz;
  w0 := date_trunc('week', loc) at time zone tz;
  m0 := date_trunc('month', loc) at time zone tz;

  with o as (
    select p.order_id, min(coalesce(p.created_at, x.created_at)) as at
      from public.order_parts p
      left join public.orders x on x.id = p.order_id
     where lower(btrim(coalesce(p.owner_email, ''))) = me
       and (p_point is null or p.point_id = p_point)
     group by p.order_id
  ),
  c as (
    select t.at from public.iva_call_taps t
     where t.owner_email = me and (p_point is null or t.point_id = p_point)
  ),
  m as (
    select lower(btrim(x.from_email)) as who, (x.created_at at time zone tz)::date as day, min(x.created_at) as at
      from public.app_messages x
      left join public.tools t on t.id = x.tool_id
     where lower(btrim(coalesce(x.to_email, ''))) = me
       and lower(btrim(coalesce(x.from_email, ''))) not in ('', me)
       and (not stf or not exists (select 1 from public.iva_staff s
                                    where s.owner_email = me and s.email = lower(btrim(x.from_email))))
       and (p_point is null or coalesce((to_jsonb(x) ->> 'point_id')::uuid, (to_jsonb(t) ->> 'point_id')::uuid) = p_point)
     group by 1, 2
  )
  select jsonb_build_object(
    'point',  p_point,
    'tz',     tz,
    'at',     now(),
    'orders', jsonb_build_object('day',   (select count(*) from o where at >= d0),
                                 'week',  (select count(*) from o where at >= w0),
                                 'month', (select count(*) from o where at >= m0),
                                 'all',   (select count(*) from o)),
    'calls',  jsonb_build_object('day',   (select count(*) from c where at >= d0),
                                 'week',  (select count(*) from c where at >= w0),
                                 'month', (select count(*) from c where at >= m0),
                                 'all',   (select count(*) from c)),
    'msgs',   jsonb_build_object('day',   (select count(*) from m where at >= d0),
                                 'week',  (select count(*) from m where at >= w0),
                                 'month', (select count(*) from m where at >= m0),
                                 'all',   (select count(*) from m)),
    'calls_since', (select min(t.at) from public.iva_call_taps t where t.owner_email = me)
  ) into res;
  return res;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_appeals(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_appeals(uuid) TO authenticated;
COMMENT ON FUNCTION public.iva_appeals(uuid) IS 'Ива, 07.11.2026: «Обращения» владельца проката — заявки, звонки, сообщения за день / неделю / месяц / всё время (только числа)';

NOTIFY pgrst, 'reload schema';

-- Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('скачивания: таблица закрыта, счётчик и отметка — всем',
     to_regclass('public.iva_app_downloads') IS NOT NULL
     AND NOT has_table_privilege('anon', 'public.iva_app_downloads', 'SELECT')
     AND has_function_privilege('anon', 'public.iva_download_count()', 'EXECUTE')
     AND has_function_privilege('anon', 'public.iva_download_hit(text)', 'EXECUTE')),
  ('счётчик округляется как в Google Play (7 → 5, 64 → 50, 1234 → 1000)',
     public.iva_download_bucket(7) = 5 AND public.iva_download_bucket(64) = 50 AND public.iva_download_bucket(1234) = 1000
     AND public.iva_download_bucket(0) = 0),
  ('нажатия «Позвонить»: таблица закрыта, отметка — всем',
     to_regclass('public.iva_call_taps') IS NOT NULL
     AND NOT has_table_privilege('anon', 'public.iva_call_taps', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_call_taps', 'SELECT')
     AND has_function_privilege('anon', 'public.iva_call_tap(uuid, uuid, text, text)', 'EXECUTE')),
  ('«Обращения»: без входа нельзя, вошедшим — можно (внутри — только владелец проката)',
     to_regprocedure('public.iva_appeals(uuid)') IS NOT NULL
     AND NOT has_function_privilege('anon', 'public.iva_appeals(uuid)', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_appeals(uuid)', 'EXECUTE'))
) AS x(check_name, ok);

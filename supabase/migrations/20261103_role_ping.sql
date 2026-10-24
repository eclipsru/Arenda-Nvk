-- ============================================================
--  Ива · 10.112 — приглашение в сотрудники и права админа появляются / пропадают моментально
--  (просьба владельца 17.10.2026). Нужны только admins и jwt_email; сотрудники (SQL 10.103 и новее) — если есть.
--
--  Что делает:
--   • iva_role_ping — по строке на почту: «когда последний раз поменялось что-то про роль этого человека».
--     Других данных нет. Читать может только сам человек (своя почта), писать — только база (триггеры).
--   • Триггеры трогают строку, когда:
--       – admins: назначили, удалили, вернули, приостановили, возобновили, сменили роль (его строка; при паузе /
--         удалении — ещё и строки всех его сотрудников: у них пропадает доступ);
--       – iva_staff: пригласили, приняли / отказались, поменяли права, убрали (если таблица есть);
--       – iva_staff_points: поменяли пункты сотрудника (если таблица есть).
--   • Таблица добавляется в Supabase Realtime (публикация supabase_realtime): приложение 10.112 держит одно соединение
--     и, как только его строка поменялась, сразу перечитывает свою роль (как в 10.110) — кабинет админа открывается или
--     закрывается, приглашение появляется окном. Без этого SQL приложение проверяет роль раз в 30 секунд, как раньше.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  Если сотрудников (20261028_staff.sql) ещё нет — запустите этот файл ещё раз после него (появятся их триггеры).
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (моментальность пропадёт, приложение снова проверяет раз в 30 секунд; роли и сотрудники не меняются):
--      DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_role_ping') THEN ALTER PUBLICATION supabase_realtime DROP TABLE public.iva_role_ping; END IF; END $$;
--      DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.admins;
--      DO $$ BEGIN IF to_regclass('public.iva_staff') IS NOT NULL THEN DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.iva_staff; END IF; END $$;
--      DO $$ BEGIN IF to_regclass('public.iva_staff_points') IS NOT NULL THEN DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.iva_staff_points; END IF; END $$;
--      DROP FUNCTION IF EXISTS public.iva_role_ping_admins();
--      DROP FUNCTION IF EXISTS public.iva_role_ping_staff();
--      DROP FUNCTION IF EXISTS public.iva_role_ping_points();
--      DROP FUNCTION IF EXISTS public.iva_role_ping_touch(text);
--      DROP TABLE IF EXISTS public.iva_role_ping;
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.admins') IS NULL OR to_regprocedure('public.jwt_email()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет admins / jwt_email — это не база «Ивы»';
  END IF;
END $do$;

-- 1. Отметка «роль поменялась»: только почта и время
CREATE TABLE IF NOT EXISTS public.iva_role_ping (
  email      text        PRIMARY KEY CHECK (email = lower(btrim(email)) AND email <> ''),
  changed_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.iva_role_ping IS 'Ива 10.112: когда поменялась роль человека (админ / сотрудник). Пишет только база, читает только сам человек — для Supabase Realtime.';
ALTER TABLE public.iva_role_ping ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_role_ping FROM public, anon, authenticated;
GRANT SELECT ON public.iva_role_ping TO authenticated;
DROP POLICY IF EXISTS iva_role_ping_own ON public.iva_role_ping;
CREATE POLICY iva_role_ping_own ON public.iva_role_ping FOR SELECT TO authenticated
  USING (email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))));

CREATE OR REPLACE FUNCTION public.iva_role_ping_touch(p_email text)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  insert into public.iva_role_ping (email, changed_at)
  select lower(btrim(p_email)), clock_timestamp()
   where nullif(lower(btrim(coalesce(p_email, ''))), '') is not null
  on conflict (email) do update set changed_at = excluded.changed_at;
$function$;
REVOKE ALL ON FUNCTION public.iva_role_ping_touch(text) FROM public, anon, authenticated;

-- 2. admins: назначили / удалили / пауза / роль — его строка и строки его сотрудников
CREATE OR REPLACE FUNCTION public.iva_role_ping_admins()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  o jsonb := case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) else '{}'::jsonb end;
  n jsonb := case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) else '{}'::jsonb end;
  em text;
begin
  if tg_op = 'UPDATE'
     and lower(btrim(coalesce(o ->> 'email', ''))) = lower(btrim(coalesce(n ->> 'email', '')))
     and (o ->> 'role') is not distinct from (n ->> 'role')
     and (o ->> 'active') is not distinct from (n ->> 'active')
     and (o ->> 'removed_at') is not distinct from (n ->> 'removed_at') then
    return null;                                     -- аватар, тариф, название — роль не поменялась
  end if;
  foreach em in array array[o ->> 'email', n ->> 'email'] loop
    continue when nullif(lower(btrim(coalesce(em, ''))), '') is null;
    perform public.iva_role_ping_touch(em);
    if to_regclass('public.iva_staff') is not null then
      execute $q$select public.iva_role_ping_touch(s.email) from public.iva_staff s where s.owner_email = $1$q$
        using lower(btrim(em));
    end if;
  end loop;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_role_ping_admins() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.admins;
CREATE TRIGGER trg_iva_role_ping
  AFTER INSERT OR UPDATE OR DELETE ON public.admins
  FOR EACH ROW EXECUTE FUNCTION public.iva_role_ping_admins();

-- 3. iva_staff: пригласили / ответил / права / убрали — строка сотрудника
CREATE OR REPLACE FUNCTION public.iva_role_ping_staff()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if tg_op in ('UPDATE', 'DELETE') then perform public.iva_role_ping_touch(to_jsonb(old) ->> 'email'); end if;
  if tg_op in ('INSERT', 'UPDATE') then perform public.iva_role_ping_touch(to_jsonb(new) ->> 'email'); end if;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_role_ping_staff() FROM public, anon, authenticated;

-- 4. iva_staff_points: поменяли пункты сотрудника — его строка
CREATE OR REPLACE FUNCTION public.iva_role_ping_points()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  sid text := coalesce(case when tg_op <> 'DELETE' then to_jsonb(new) ->> 'staff_id' end, to_jsonb(old) ->> 'staff_id');
begin
  if sid is not null then
    execute $q$select public.iva_role_ping_touch(s.email) from public.iva_staff s where s.id::text = $1$q$ using sid;
  end if;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_role_ping_points() FROM public, anon, authenticated;

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NOT NULL THEN
    DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.iva_staff;
    CREATE TRIGGER trg_iva_role_ping
      AFTER INSERT OR UPDATE OR DELETE ON public.iva_staff
      FOR EACH ROW EXECUTE FUNCTION public.iva_role_ping_staff();
  END IF;
  IF to_regclass('public.iva_staff_points') IS NOT NULL THEN
    DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.iva_staff_points;
    CREATE TRIGGER trg_iva_role_ping
      AFTER INSERT OR UPDATE OR DELETE ON public.iva_staff_points
      FOR EACH ROW EXECUTE FUNCTION public.iva_role_ping_points();
  END IF;
END $do$;

-- 5. Supabase Realtime: приложение подписывается на свою строку
DO $do$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND NOT puballtables)
     AND NOT EXISTS (SELECT 1 FROM pg_publication_tables
                      WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_role_ping') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.iva_role_ping;
  END IF;
END $do$;

NOTIFY pgrst, 'reload schema';

-- 6. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('отметка «роль поменялась» — читает только сам человек',
     to_regclass('public.iva_role_ping') IS NOT NULL
     AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_role_ping'::regclass)
     AND EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_role_ping' AND policyname = 'iva_role_ping_own')
     AND has_table_privilege('authenticated', 'public.iva_role_ping', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_role_ping', 'INSERT')
     AND NOT has_table_privilege('anon', 'public.iva_role_ping', 'SELECT')),
  ('триггеры: admins и сотрудники (если есть)',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.admins'::regclass AND tgname = 'trg_iva_role_ping')
     AND (to_regclass('public.iva_staff') IS NULL
          OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = to_regclass('public.iva_staff') AND tgname = 'trg_iva_role_ping'))
     AND (to_regclass('public.iva_staff_points') IS NULL
          OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = to_regclass('public.iva_staff_points') AND tgname = 'trg_iva_role_ping'))),
  ('Supabase Realtime включён для отметки',
     EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_role_ping')
     OR EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND puballtables)),
  ('служебные функции закрыты для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_role_ping_touch(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_role_ping_touch(text)', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_role_ping_admins()', 'EXECUTE'))
) AS x(check_name, ok);

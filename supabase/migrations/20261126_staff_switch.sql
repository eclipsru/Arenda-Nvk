-- ============================================================
--  Ива, сайт 06.11.2026 — быстрая смена аккаунта в «Работе» («Сменить» и кружок «Владелец»).
--
--  Что делает:
--   • iva_switch_info() — для того, кто вошёл, отвечает, в каких прокатах он работает:
--       владелец проката (активная строка admins) и/или сотрудник (iva_staff, статус «active», прокат включён).
--     Для каждого проката: ключ проката (md5 почты владельца), роль («owner» / «staff»), название проката и
--     почта владельца проката. Почту владельца видят только его сотрудники и он сам — по решению владельца 06.11.2026
--     («Владелец» спрашивает только пароль, почту подставляет сайт).
--   • Сайт сравнивает ключи «до» и «после» входа через «Сменить»: войти можно, только если новый аккаунт работает
--     в том же прокате (сотрудник любого пункта или владелец). Иначе — «Вы не работаете в этом прокате»,
--     прежний вход остаётся.
--   • Таблицы не меняет, новых данных не собирает. Пароли проверяет только Supabase Auth.
--
--  Без этого SQL сайт работает как раньше: кнопки «Сменить» и кружка «Владелец» нет.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261126_staff_switch_rollback.sql (удаляет только эту функцию).
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL THEN
    RAISE EXCEPTION 'В базе нет сотрудников (iva_staff) — сначала SQL 20261028_staff';
  END IF;
  IF to_regclass('public.admins') IS NULL THEN
    RAISE EXCEPTION 'В базе нет таблицы admins';
  END IF;
END $do$;

CREATE OR REPLACE FUNCTION public.iva_switch_info()
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  with me as (select lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) as e),
  p as (
    select distinct md5(lower(btrim(a.email))) as k, 'owner'::text as r, lower(btrim(a.email)) as o,
           coalesce(nullif(btrim(to_jsonb(a) ->> 'company'), ''), nullif(btrim(to_jsonb(a) ->> 'full_name'), ''), 'Прокат') as c
      from public.admins a, me
     where me.e <> '' and lower(btrim(a.email)) = me.e and a.active
    union
    select distinct md5(s.owner_email), 'staff', s.owner_email,
           coalesce(nullif(btrim(to_jsonb(a) ->> 'company'), ''), nullif(btrim(to_jsonb(a) ->> 'full_name'), ''), 'Прокат')
      from public.iva_staff s
      join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active, me
     where me.e <> '' and s.email = me.e and s.status = 'active'
  )
  select jsonb_build_object(
           'me', (select e from me),
           'prokats', coalesce((select jsonb_agg(jsonb_build_object('key', p.k, 'role', p.r, 'owner', p.o, 'company', p.c)
                                                 order by p.r, p.k) from p), '[]'::jsonb));
$function$;
REVOKE ALL ON FUNCTION public.iva_switch_info() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_switch_info() TO authenticated;
COMMENT ON FUNCTION public.iva_switch_info() IS 'Ива, сайт 06.11.2026: в каких прокатах работает вошедший — для «Сменить» / «Владелец» в «Работе»';

NOTIFY pgrst, 'reload schema';

-- Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('смена аккаунта — функция есть',
     to_regprocedure('public.iva_switch_info()') IS NOT NULL),
  ('без входа вызвать нельзя',
     NOT has_function_privilege('anon', 'public.iva_switch_info()', 'EXECUTE')),
  ('вошедшим — можно (отвечает только про свои прокаты)',
     has_function_privilege('authenticated', 'public.iva_switch_info()', 'EXECUTE'))
) AS x(check_name, ok);

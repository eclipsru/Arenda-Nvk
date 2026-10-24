-- ============================================================
--  Ива · 10.115 — все правки доступа видны сразу (просьба владельца 17.10.2026) и сотрудник — в пункт / из пункта
--  одной кнопкой. Применять ПОСЛЕ 20261103_role_ping.sql (моментальность) и 20261028_staff.sql (сотрудники).
--
--  Что делает:
--   • отметка «роль поменялась» (iva_role_ping, 10.112) теперь ставится ещё и когда:
--       – создатель сменил админу тариф или лимит задолженности (только этому админу);
--       – сотрудник принял приглашение или отказался (его владельцу: страница «Сотрудники» обновится сама);
--       – владелец поменял пункт выдачи: название, адрес, время работы, телефон, «Инструменты сотрудников»
--         (сотрудникам этого пункта: «Мой пункт» обновится сам);
--     Приложение по отметке сразу перечитывает своё и показывает короткую подсказку. Других данных в отметке нет.
--   • iva_staff_link — владелец прикрепляет своего сотрудника к своему пункту или убирает из пункта
--     (кнопки на странице пункта → «Сотрудники»). Убрали из последнего пункта — он больше не сотрудник
--     (как «Убрать из сотрудников»; приложение предупреждает заранее).
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (снова как в 10.112–10.114; сотрудники, пункты и роли не меняются):
--      DROP FUNCTION IF EXISTS public.iva_staff_link(uuid, uuid, boolean);
--      DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.iva_pickup_points;
--      DROP FUNCTION IF EXISTS public.iva_role_ping_point();
--    затем снова выполнить ВЕСЬ файл 20261103_role_ping.sql (его можно запускать повторно) — вернутся прежние
--    триггеры админов и сотрудников.
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_role_ping') IS NULL OR to_regprocedure('public.iva_role_ping_touch(text)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261103_role_ping.sql (SQL 10.112: роль — моментально)';
  END IF;
  IF to_regclass('public.iva_staff') IS NULL OR to_regclass('public.iva_staff_points') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261028_staff.sql (SQL 10.103: сотрудники)';
  END IF;
END $do$;

-- 1. admins: как в 10.112 + тариф и лимит задолженности (отметка только у этого админа)
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
    if (o ->> 'fee_pct') is distinct from (n ->> 'fee_pct')
       or (o ->> 'debt_limit') is distinct from (n ->> 'debt_limit') then
      perform public.iva_role_ping_touch(n ->> 'email');   -- 10.115: новый тариф — админ видит сразу
    end if;
    return null;                                         -- аватар, название — роль не поменялась
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

-- 2. iva_staff: как в 10.112 + принял / отказался — отметка и у владельца
CREATE OR REPLACE FUNCTION public.iva_role_ping_staff()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if tg_op in ('UPDATE', 'DELETE') then perform public.iva_role_ping_touch(to_jsonb(old) ->> 'email'); end if;
  if tg_op in ('INSERT', 'UPDATE') then perform public.iva_role_ping_touch(to_jsonb(new) ->> 'email'); end if;
  if tg_op = 'UPDATE' and (to_jsonb(old) ->> 'status') is distinct from (to_jsonb(new) ->> 'status') then
    perform public.iva_role_ping_touch(to_jsonb(new) ->> 'owner_email');
  end if;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_role_ping_staff() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.iva_staff;
CREATE TRIGGER trg_iva_role_ping
  AFTER INSERT OR UPDATE OR DELETE ON public.iva_staff
  FOR EACH ROW EXECUTE FUNCTION public.iva_role_ping_staff();

-- 3. iva_pickup_points: владелец поменял пункт — отметка у сотрудников этого пункта
--    (удалили пункт — отметку ставит триггер iva_staff_points: строки сотрудников удаляются вместе с пунктом)
CREATE OR REPLACE FUNCTION public.iva_role_ping_point()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if (to_jsonb(old) - 'updated_at') is not distinct from (to_jsonb(new) - 'updated_at') then
    return null;                                         -- ничего не поменялось
  end if;
  perform public.iva_role_ping_touch(s.email)
     from public.iva_staff_points sp
     join public.iva_staff s on s.id = sp.staff_id
    where sp.point_id = new.id;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_role_ping_point() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_role_ping ON public.iva_pickup_points;
CREATE TRIGGER trg_iva_role_ping
  AFTER UPDATE ON public.iva_pickup_points
  FOR EACH ROW EXECUTE FUNCTION public.iva_role_ping_point();

-- 4. Владелец: сотрудник — в пункт (p_on = true) или из пункта (false); из последнего — больше не сотрудник
CREATE OR REPLACE FUNCTION public.iva_staff_link(p_id uuid, p_point uuid, p_on boolean)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  sid uuid;
begin
  if me = '' or not public.iva_pickup_is_admin() then
    raise exception 'Сотрудников добавляет владелец проката';
  end if;
  select s.id into sid from public.iva_staff s where s.id = p_id and s.owner_email = me;
  if sid is null then
    raise exception 'Сотрудник не найден — возможно, его уже убрали';
  end if;
  if p_point is null or not exists (select 1 from public.iva_pickup_points p where p.id = p_point and p.owner_email = me) then
    raise exception 'Пункт выдачи не найден у вашего проката';
  end if;
  if coalesce(p_on, false) then
    insert into public.iva_staff_points (staff_id, point_id) values (sid, p_point) on conflict do nothing;
    return 'linked';
  end if;
  if not exists (select 1 from public.iva_staff_points sp where sp.staff_id = sid and sp.point_id <> p_point) then
    delete from public.iva_staff s where s.id = sid;     -- это был его единственный пункт
    return 'removed';
  end if;
  delete from public.iva_staff_points sp where sp.staff_id = sid and sp.point_id = p_point;
  return 'unlinked';
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_link(uuid, uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_link(uuid, uuid, boolean) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 5. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('тариф админа — отметка у него',
     position('fee_pct' in pg_get_functiondef('public.iva_role_ping_admins()'::regprocedure)) > 0
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.admins'::regclass AND tgname = 'trg_iva_role_ping')),
  ('ответ сотрудника — отметка у владельца',
     position('owner_email' in pg_get_functiondef('public.iva_role_ping_staff()'::regprocedure)) > 0
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.iva_staff'::regclass AND tgname = 'trg_iva_role_ping')),
  ('правка пункта — отметка у его сотрудников',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.iva_pickup_points'::regclass AND tgname = 'trg_iva_role_ping')),
  ('в пункт / из пункта — вошедшим, не гостям; служебные функции закрыты',
     has_function_privilege('authenticated', 'public.iva_staff_link(uuid, uuid, boolean)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_link(uuid, uuid, boolean)', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_role_ping_point()', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_role_ping_staff()', 'EXECUTE'))
) AS x(check_name, ok);

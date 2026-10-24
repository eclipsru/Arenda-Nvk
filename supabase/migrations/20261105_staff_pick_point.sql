-- ============================================================
--  Ива · 10.114 — сотрудник на двух и более пунктах: инструмент встаёт в тот пункт, который он выбрал в форме
--  «Добавить инструмент» (просьба владельца 17.10.2026). Применять ПОСЛЕ 20261104_point_staff_publish.sql.
--
--  Что делает:
--   • iva_staff_pick — какой пункт сотрудник только что выбрал в форме (номер сотрудника, пункт, время). Читать и
--     писать напрямую нельзя никому — только функция iva_staff_pick_point (свои пункты с правом «Выкладывает
--     инструмент»). Таблица отдельная, чтобы выбор пункта не дёргал «роль — моментально» (10.112);
--   • триггер инструментов сотрудника: новый инструмент без пункта встаёт в выбранный пункт (выбор не старше часа),
--     и режим «Инструменты сотрудников» (сразу / после одобрения) берётся у ЭТОГО пункта. Раньше — у первого пункта,
--     и приложение переставляло пункт уже после публикации. Остальное — как в 20261104.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (инструмент без пункта — снова в первый пункт сотрудника):
--      DROP FUNCTION IF EXISTS public.iva_staff_pick_point(uuid);
--      DROP TABLE IF EXISTS public.iva_staff_pick;
--    затем снова выполнить ВЕСЬ файл 20261104_point_staff_publish.sql (его можно запускать повторно) — вернётся
--    прежний триггер.
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL OR to_regclass('public.iva_staff_points') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261028_staff.sql (SQL 10.103: сотрудники)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'iva_pickup_points' AND column_name = 'staff_publish')
     OR to_regprocedure('public.iva_tools_staff_before()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261104_point_staff_publish.sql (SQL 10.113: «Инструменты сотрудников» у пункта)';
  END IF;
END $do$;

-- 1. Выбор пункта в форме
CREATE TABLE IF NOT EXISTS public.iva_staff_pick (
  staff_id  uuid        PRIMARY KEY REFERENCES public.iva_staff(id) ON DELETE CASCADE,
  point_id  uuid        NOT NULL REFERENCES public.iva_pickup_points(id) ON DELETE CASCADE,
  picked_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.iva_staff_pick IS
  'Ива 10.114: пункт, выбранный сотрудником в форме «Добавить инструмент» (пишет только iva_staff_pick_point).';
ALTER TABLE public.iva_staff_pick ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_staff_pick FROM public, anon, authenticated;

CREATE OR REPLACE FUNCTION public.iva_staff_pick_point(p_point uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  sid uuid;
begin
  if em = '' or p_point is null or not public.iva_staff_right(p_point, 'catalog') then
    return false;                                         -- не сотрудник «Каталог» этого пункта
  end if;
  select s.id into sid
    from public.iva_staff s
    join public.iva_staff_points sp on sp.staff_id = s.id and sp.point_id = p_point
   where s.email = em and s.status = 'active' and s.can_catalog
   limit 1;
  if sid is null then return false; end if;
  insert into public.iva_staff_pick (staff_id, point_id, picked_at) values (sid, p_point, now())
  on conflict (staff_id) do update set point_id = excluded.point_id, picked_at = excluded.picked_at;
  return true;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_pick_point(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_pick_point(uuid) TO authenticated;

-- 2. Триггер инструментов сотрудника: пункт без point_id — выбранный в форме (остальное — как в 20261104)
CREATE OR REPLACE FUNCTION public.iva_tools_staff_before()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  sid uuid;
  sowner text;
  mode text;
  fp uuid;
begin
  if tg_op = 'UPDATE' then
    new.staff_id := old.staff_id;                         -- кто добавил — не меняется
  end if;
  if em <> '' and not exists (select 1 from public.admins a
                               where a.active and a.role in ('admin', 'chief') and lower(btrim(a.email)) = em) then
    select s.id, s.owner_email into sid, sowner
      from public.iva_staff s
      join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
     where s.email = em and s.status = 'active' and s.can_catalog
     limit 1;
  end if;

  if sid is null then                                     -- владелец, создатель, база — как раньше
    if tg_op = 'INSERT' then
      new.staff_id := null;
      new.review := null;
    elsif old.review is not null and new.review is not distinct from old.review
          and coalesce(new.active, false) and not coalesce(old.active, false) then
      new.review := null;                                 -- владелец сам показал инструмент — это «Опубликовать»
    end if;
    return new;
  end if;

  if tg_op = 'INSERT' then
    new.owner_email := sowner;                            -- инструмент сотрудника — инструмент его проката
    new.staff_id := sid;
    if new.point_id is null then
      -- 10.114: пункт, который сотрудник выбрал в форме (iva_staff_pick, не старше часа и всё ещё его)
      select k.point_id into fp
        from public.iva_staff_pick k
        join public.iva_staff_points sp on sp.staff_id = k.staff_id and sp.point_id = k.point_id
        join public.iva_pickup_points pp on pp.id = k.point_id and pp.owner_email = sowner
       where k.staff_id = sid and k.picked_at > now() - interval '1 hour';
    end if;
    if new.point_id is null and fp is null then
      select pp.id into fp
        from public.iva_staff_points sp
        join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = sowner
       where sp.staff_id = sid
       order by pp.created_at, pp.id
       limit 1;
      if fp is null then raise exception 'У вас нет пункта выдачи — обратитесь к владельцу проката'; end if;
    end if;
    if new.point_id is null then
      new.point_id := fp;
    elsif not public.iva_staff_right(new.point_id, 'catalog') then
      raise exception 'Пункт выдачи — только из ваших пунктов';
    end if;
    -- 10.113: режим — у пункта; запасной — прежний общий режим владельца
    select pp.staff_publish into mode
      from public.iva_pickup_points pp where pp.id = new.point_id;
    if mode is null then
      select coalesce(max(a.staff_publish), 'approve') into mode
        from public.admins a where lower(btrim(a.email)) = sowner and a.active;
    end if;
    if mode = 'now' then
      new.review := null;
    else
      new.active := false;                                -- до одобрения клиенты не видят
      new.review := 'pending';
    end if;
    return new;
  end if;

  new.owner_email := old.owner_email;                     -- в чужой прокат не переносится
  if new.point_id is null then
    new.point_id := old.point_id;
  elsif new.point_id is distinct from old.point_id and not public.iva_staff_right(new.point_id, 'catalog') then
    raise exception 'Пункт выдачи — только из ваших пунктов';
  end if;
  if old.review is not null then
    new.active := false;                                  -- ждёт одобрения — сотрудник не покажет
    new.review := 'pending';                              -- исправил отклонённый — снова на одобрение
  else
    new.review := null;                                   -- правки опубликованного — сразу
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_staff_before() FROM public, anon, authenticated;

NOTIFY pgrst, 'reload schema';

-- 3. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('выбор пункта: таблица закрыта, пишет только функция',
     to_regclass('public.iva_staff_pick') IS NOT NULL
     AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_staff_pick'::regclass)
     AND NOT has_table_privilege('authenticated', 'public.iva_staff_pick', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_staff_pick', 'INSERT')
     AND NOT has_table_privilege('anon', 'public.iva_staff_pick', 'SELECT')),
  ('функция выбора — вошедшим, не гостям',
     has_function_privilege('authenticated', 'public.iva_staff_pick_point(uuid)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_pick_point(uuid)', 'EXECUTE')),
  ('триггер берёт выбранный пункт и режим пункта',
     position('iva_staff_pick' in pg_get_functiondef('public.iva_tools_staff_before()'::regprocedure)) > 0
     AND position('pp.staff_publish' in pg_get_functiondef('public.iva_tools_staff_before()'::regprocedure)) > 0
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'trg_iva_staff_tool' AND NOT tgisinternal)),
  ('триггер закрыт для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_tools_staff_before()', 'EXECUTE'))
) AS x(check_name, ok);

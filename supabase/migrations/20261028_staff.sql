-- ============================================================
--  Ива · 10.103 — этап 3а «Сотрудники и пункты»: сотрудники пунктов выдачи, приглашение и права
--  (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md). Применять ПОСЛЕ 20261023_pickup_points.sql.
--
--  Что делает:
--   • iva_staff — сотрудники проката: почта (вход), имя, телефон, права («Выдаёт и принимает», «Выкладывает
--     инструмент», «Отвечает в чате», «Получает уведомления»), «Может выдавать заявки самому себе», состояние
--     (приглашён / работает / отказался); iva_staff_points — к каким пунктам владельца прикреплён;
--   • один человек — сотрудник только у одного владельца (приглашён или работает); владельца проката сотрудником
--     сделать нельзя; себя — тоже;
--   • владелец добавляет / меняет / убирает только через функции базы (iva_staff_save / iva_staff_remove), они
--     проверяют, что пункты его; убрали — доступ пропадает сразу (строка удаляется);
--   • человек под своей почтой видит своё приглашение и отвечает «Принять / Отказаться» (iva_staff_me /
--     iva_staff_answer); другие пункты, данные проката и других сотрудников он не видит;
--   • имя и телефон сотрудника видят владелец этого проката и создатель; клиентам — никогда;
--   • удалили пункт — сотрудник от него открепляется; не осталось ни одного пункта — убирается из сотрудников.
--  Ничего существующего не меняется: заявки, инструмент, пункты, правила доступа — как были (это этапы 3б и 3в).
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (список сотрудников пропадёт; больше ничего не трогается):
--      DROP FUNCTION IF EXISTS public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean);
--      DROP FUNCTION IF EXISTS public.iva_staff_remove(uuid);
--      DROP FUNCTION IF EXISTS public.iva_staff_list();
--      DROP FUNCTION IF EXISTS public.iva_staff_me();
--      DROP FUNCTION IF EXISTS public.iva_staff_answer(uuid, boolean);
--      DROP TABLE IF EXISTS public.iva_staff_points;
--      DROP TABLE IF EXISTS public.iva_staff;
--      DROP FUNCTION IF EXISTS public.iva_staff_points_check();
--      DROP FUNCTION IF EXISTS public.iva_staff_points_orphan();
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_pickup_points') IS NULL OR to_regprocedure('public.iva_pickup_is_admin()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261023_pickup_points.sql (SQL 10.95: пункты выдачи)';
  END IF;
  IF to_regprocedure('public.fn_is_chief()') IS NULL OR to_regclass('public.admins') IS NULL THEN
    RAISE EXCEPTION 'В базе нет admins / fn_is_chief — это не база «Ивы»';
  END IF;
END $do$;

-- 1. Сотрудники и их пункты
CREATE TABLE IF NOT EXISTS public.iva_staff (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_email text        NOT NULL CHECK (owner_email = lower(btrim(owner_email)) AND owner_email <> ''),
  email       text        NOT NULL CHECK (email = lower(btrim(email)) AND char_length(email) <= 254
                                          AND email ~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]{2,}$'),
  name        text        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
  tel         text        NOT NULL DEFAULT '' CHECK (char_length(tel) <= 30),
  can_issue   boolean     NOT NULL DEFAULT false,
  can_catalog boolean     NOT NULL DEFAULT false,
  can_chat    boolean     NOT NULL DEFAULT false,
  can_notify  boolean     NOT NULL DEFAULT false,
  self_issue  boolean     NOT NULL DEFAULT false,
  status      text        NOT NULL DEFAULT 'invited' CHECK (status IN ('invited', 'active', 'declined')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  answered_at timestamptz,
  CONSTRAINT iva_staff_not_owner CHECK (email <> owner_email),
  CONSTRAINT iva_staff_owner_email UNIQUE (owner_email, email)
);
COMMENT ON TABLE public.iva_staff IS
  'Ива 10.103: сотрудники проката (вход по почте, права, приглашение). Меняется только функциями iva_staff_*.';
-- один человек — у одного владельца (приглашён или работает)
CREATE UNIQUE INDEX IF NOT EXISTS iva_staff_one_owner ON public.iva_staff (email) WHERE status IN ('invited', 'active');
CREATE INDEX IF NOT EXISTS idx_iva_staff_owner ON public.iva_staff (owner_email, created_at);

CREATE TABLE IF NOT EXISTS public.iva_staff_points (
  staff_id uuid NOT NULL REFERENCES public.iva_staff(id) ON DELETE CASCADE,
  point_id uuid NOT NULL REFERENCES public.iva_pickup_points(id) ON DELETE CASCADE,
  PRIMARY KEY (staff_id, point_id)
);
CREATE INDEX IF NOT EXISTS idx_iva_staff_points_point ON public.iva_staff_points (point_id);
COMMENT ON TABLE public.iva_staff_points IS 'Ива 10.103: к каким пунктам выдачи владельца прикреплён сотрудник.';

-- пункт — только своего владельца
CREATE OR REPLACE FUNCTION public.iva_staff_points_check()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if not exists (select 1 from public.iva_staff s join public.iva_pickup_points p on p.owner_email = s.owner_email
                  where s.id = new.staff_id and p.id = new.point_id) then
    raise exception 'Пункт выдачи не найден у вашего проката';
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_points_check() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_staff_points_check ON public.iva_staff_points;
CREATE TRIGGER trg_iva_staff_points_check BEFORE INSERT OR UPDATE ON public.iva_staff_points
  FOR EACH ROW EXECUTE FUNCTION public.iva_staff_points_check();

-- не осталось ни одного пункта (удалили пункт) — убрать из сотрудников
CREATE OR REPLACE FUNCTION public.iva_staff_points_orphan()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  delete from public.iva_staff s
   where s.id = old.staff_id
     and not exists (select 1 from public.iva_staff_points sp where sp.staff_id = old.staff_id);
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_points_orphan() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_staff_points_orphan ON public.iva_staff_points;
CREATE TRIGGER trg_iva_staff_points_orphan AFTER DELETE ON public.iva_staff_points
  FOR EACH ROW EXECUTE FUNCTION public.iva_staff_points_orphan();

-- 2. Доступ: читать — владелец (свои), сам сотрудник (себя), создатель; писать напрямую — никому
ALTER TABLE public.iva_staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.iva_staff_points ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.iva_staff FROM public, anon, authenticated;
REVOKE ALL ON TABLE public.iva_staff_points FROM public, anon, authenticated;
GRANT SELECT ON public.iva_staff TO authenticated;
GRANT SELECT ON public.iva_staff_points TO authenticated;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_staff' AND policyname = 'iva_staff_read') THEN
    CREATE POLICY iva_staff_read ON public.iva_staff FOR SELECT TO authenticated
      USING ((owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) AND public.iva_pickup_is_admin())
             OR (email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) AND coalesce(auth.jwt() ->> 'email', '') <> '')
             OR public.fn_is_chief());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_staff_points' AND policyname = 'iva_staff_points_read') THEN
    CREATE POLICY iva_staff_points_read ON public.iva_staff_points FOR SELECT TO authenticated
      USING (EXISTS (SELECT 1 FROM public.iva_staff s WHERE s.id = staff_id));   -- видно то же, что и сотрудника (правило выше)
  END IF;
END $$;

-- 3. Владелец: список своих сотрудников (с пунктами)
CREATE OR REPLACE FUNCTION public.iva_staff_list()
 RETURNS TABLE (id uuid, email text, name text, tel text, can_issue boolean, can_catalog boolean, can_chat boolean,
                can_notify boolean, self_issue boolean, status text, point_ids uuid[], created_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select s.id, s.email, s.name, s.tel, s.can_issue, s.can_catalog, s.can_chat, s.can_notify, s.self_issue, s.status,
         coalesce((select array_agg(sp.point_id order by p.created_at, p.id)
                     from public.iva_staff_points sp join public.iva_pickup_points p on p.id = sp.point_id
                    where sp.staff_id = s.id), '{}'::uuid[]),
         s.created_at
  from public.iva_staff s
  where public.iva_pickup_is_admin()
    and s.owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', '')))
  order by s.created_at, s.id
  limit 200;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_list() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_list() TO authenticated;

-- 4. Владелец: пригласить (p_id = null) или изменить сотрудника; пункты — полный список
CREATE OR REPLACE FUNCTION public.iva_staff_save(p_id uuid, p_email text, p_name text, p_tel text, p_points uuid[],
  p_issue boolean, p_catalog boolean, p_chat boolean, p_notify boolean, p_self boolean)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  em  text := lower(btrim(coalesce(p_email, '')));
  nm  text := btrim(regexp_replace(coalesce(p_name, ''), '\s+', ' ', 'g'));
  tl  text := btrim(coalesce(p_tel, ''));
  pts uuid[];
  sid uuid;
  st  text;
begin
  if me = '' or not public.iva_pickup_is_admin() then
    raise exception 'Сотрудников добавляет владелец проката';
  end if;
  select coalesce(array_agg(distinct x), '{}'::uuid[]) into pts from unnest(coalesce(p_points, '{}'::uuid[])) x where x is not null;
  if cardinality(pts) = 0 then
    raise exception 'Отметьте хотя бы один пункт выдачи';
  end if;
  if exists (select 1 from unnest(pts) x
              where not exists (select 1 from public.iva_pickup_points p where p.id = x and p.owner_email = me)) then
    raise exception 'Пункт выдачи не найден у вашего проката';
  end if;
  if not (coalesce(p_issue, false) or coalesce(p_catalog, false) or coalesce(p_chat, false) or coalesce(p_notify, false)) then
    raise exception 'Отметьте хотя бы одно право';
  end if;
  if char_length(nm) < 1 or char_length(nm) > 80 then
    raise exception 'Укажите имя сотрудника (до 80 символов)';
  end if;
  if tl <> '' and (tl !~ '^\+?[0-9() .-]+$' or char_length(tl) > 30
                   or char_length(regexp_replace(tl, '[^0-9]', '', 'g')) not between 10 and 15) then
    raise exception 'Проверьте телефон: 10–15 цифр';
  end if;

  if p_id is null then
    if em !~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]{2,}$' or char_length(em) > 254 then
      raise exception 'Похоже, в email ошибка (пример: ivan@mail.ru)';
    end if;
    if em = me then
      raise exception 'Это ваша почта — владельца проката добавлять не нужно';
    end if;
    if exists (select 1 from public.admins a where a.active and lower(btrim(a.email)) = em) then
      raise exception 'Это владелец проката — его нельзя сделать сотрудником';
    end if;
    select s.id, s.status into sid, st from public.iva_staff s where s.owner_email = me and s.email = em;
    if exists (select 1 from public.iva_staff s where s.email = em and s.owner_email <> me and s.status in ('invited', 'active')) then
      raise exception 'Этот человек уже сотрудник другого проката (или приглашён им)';
    end if;
    if sid is null then
      if (select count(*) from public.iva_staff s where s.owner_email = me) >= 50 then
        raise exception 'Не больше 50 сотрудников';
      end if;
      insert into public.iva_staff (owner_email, email, name, tel, can_issue, can_catalog, can_chat, can_notify, self_issue)
      values (me, em, nm, tl, coalesce(p_issue, false), coalesce(p_catalog, false), coalesce(p_chat, false),
              coalesce(p_notify, false), coalesce(p_self, false))
      returning id into sid;
    else
      -- уже есть у этого владельца: обновить; отказался — пригласить снова
      update public.iva_staff s
         set name = nm, tel = tl, can_issue = coalesce(p_issue, false), can_catalog = coalesce(p_catalog, false),
             can_chat = coalesce(p_chat, false), can_notify = coalesce(p_notify, false), self_issue = coalesce(p_self, false),
             status = case when s.status = 'declined' then 'invited' else s.status end,
             answered_at = case when s.status = 'declined' then null else s.answered_at end,
             updated_at = now()
       where s.id = sid;
    end if;
  else
    select s.id into sid from public.iva_staff s where s.id = p_id and s.owner_email = me;
    if sid is null then
      raise exception 'Сотрудник не найден — возможно, его уже убрали';
    end if;
    update public.iva_staff s
       set name = nm, tel = tl, can_issue = coalesce(p_issue, false), can_catalog = coalesce(p_catalog, false),
           can_chat = coalesce(p_chat, false), can_notify = coalesce(p_notify, false), self_issue = coalesce(p_self, false),
           updated_at = now()
     where s.id = sid;
  end if;

  -- сначала новые пункты, потом лишние (сотрудник ни на миг не остаётся без пункта)
  insert into public.iva_staff_points (staff_id, point_id)
  select sid, x from unnest(pts) x
  on conflict do nothing;
  delete from public.iva_staff_points sp where sp.staff_id = sid and sp.point_id <> all(pts);
  return sid;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean) TO authenticated;

-- 5. Владелец: убрать из сотрудников (доступ пропадает сразу)
CREATE OR REPLACE FUNCTION public.iva_staff_remove(p_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if not public.iva_pickup_is_admin() then
    raise exception 'Сотрудников убирает владелец проката';
  end if;
  delete from public.iva_staff s
   where s.id = p_id and s.owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  if not found then
    raise exception 'Сотрудник не найден — возможно, его уже убрали';
  end if;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_remove(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_remove(uuid) TO authenticated;

-- 6. Сотрудник: своё приглашение / место работы (название проката, свои пункты, свои права; без почты владельца)
CREATE OR REPLACE FUNCTION public.iva_staff_me()
 RETURNS TABLE (id uuid, status text, company text, can_issue boolean, can_catalog boolean, can_chat boolean,
                can_notify boolean, self_issue boolean, points jsonb)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select s.id, s.status,
         coalesce(nullif(btrim(to_jsonb(a) ->> 'company'), ''), nullif(btrim(to_jsonb(a) ->> 'full_name'), ''), 'Прокат'),
         s.can_issue, s.can_catalog, s.can_chat, s.can_notify, s.self_issue,
         coalesce((select jsonb_agg(jsonb_build_object('id', p.id, 'name', p.name, 'address', p.address, 'city', p.city,
                                                       'open_min', p.open_min, 'close_min', p.close_min,
                                                       'tel', coalesce(to_jsonb(p) ->> 'tel', ''))
                                    order by p.created_at, p.id)
                     from public.iva_staff_points sp join public.iva_pickup_points p on p.id = sp.point_id
                    where sp.staff_id = s.id), '[]'::jsonb)
  from public.iva_staff s
  join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and s.email = lower(btrim(auth.jwt() ->> 'email'))
    and s.status in ('invited', 'active')
  limit 1;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_me() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_me() TO authenticated;

-- 7. Сотрудник: «Принять» / «Отказаться»
CREATE OR REPLACE FUNCTION public.iva_staff_answer(p_id uuid, p_accept boolean)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare me text := lower(btrim(coalesce(auth.jwt() ->> 'email', ''))); st text;
begin
  select s.status into st from public.iva_staff s where s.id = p_id and s.email = me and me <> '' for update;
  if st is null or st = 'declined' then
    raise exception 'Приглашение не найдено — возможно, владелец его отменил';
  end if;
  if st = 'active' then
    if coalesce(p_accept, false) then return 'active'; end if;
    raise exception 'Вы уже сотрудник — убрать вас может владелец проката';
  end if;
  update public.iva_staff s
     set status = case when coalesce(p_accept, false) then 'active' else 'declined' end, answered_at = now(), updated_at = now()
   where s.id = p_id;
  return case when coalesce(p_accept, false) then 'active' else 'declined' end;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_answer(uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_answer(uuid, boolean) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 8. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('таблицы сотрудников',                to_regclass('public.iva_staff') IS NOT NULL AND to_regclass('public.iva_staff_points') IS NOT NULL),
  ('один человек — у одного проката',    EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'iva_staff_one_owner')),
  ('правила доступа включены',           (SELECT bool_and(relrowsecurity) FROM pg_class WHERE oid IN ('public.iva_staff'::regclass, 'public.iva_staff_points'::regclass))),
  ('напрямую не пишет никто',            NOT has_table_privilege('authenticated', 'public.iva_staff', 'INSERT')
                                         AND NOT has_table_privilege('authenticated', 'public.iva_staff', 'UPDATE')
                                         AND NOT has_table_privilege('authenticated', 'public.iva_staff', 'DELETE')
                                         AND NOT has_table_privilege('anon', 'public.iva_staff', 'SELECT')
                                         AND NOT has_table_privilege('authenticated', 'public.iva_staff_points', 'INSERT')),
  ('функции — только вошедшим',          has_function_privilege('authenticated', 'public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean)', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean)', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_staff_me()', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_staff_answer(uuid, boolean)', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_staff_list()', 'EXECUTE')
                                         AND NOT has_function_privilege('anon', 'public.iva_staff_remove(uuid)', 'EXECUTE')),
  ('пункт — только своего проката',      EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_staff_points_check' AND NOT tgisinternal)),
  ('удалили пункт — сотрудник открепляется', EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_staff_points_orphan' AND NOT tgisinternal))
) AS x(check_name, ok);

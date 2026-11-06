-- ============================================================
--  ОТКАТ: кабинет владельца (20261127_owner_cabinet.sql).
--  • Комиссия: удаляет iva_fee_balance / iva_fee_balances / iva_fee_calc — сайт и приложение 10.138 снова считают сами.
--  • Сотрудники: iva_staff_save и iva_staff_answer — как в 10.122 / 10.103; триггер имени из профиля удалён.
--    Пустые имена (не принявшие приглашение) заполняются частью почты до «@» — иначе прежнее правило
--    «имя обязательно» не вернуть. Остальные данные не трогает.
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
-- ============================================================

DROP FUNCTION IF EXISTS public.iva_fee_balances();
DROP FUNCTION IF EXISTS public.iva_fee_balance();
DROP FUNCTION IF EXISTS public.iva_fee_calc(text);
DROP TRIGGER IF EXISTS iva_staff_name_sync ON public.customer_profiles;
DROP FUNCTION IF EXISTS public.iva_staff_name_sync();

-- ответ на приглашение — как в 10.103
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
DROP FUNCTION IF EXISTS public.iva_staff_profile_name(text);

-- сохранение сотрудника — как в 10.122 (имя обязательно)
CREATE OR REPLACE FUNCTION public.iva_staff_save(p_id uuid, p_email text, p_name text, p_tel text, p_points uuid[],
  p_issue boolean, p_catalog boolean, p_chat boolean, p_notify boolean, p_self boolean, p_delete boolean DEFAULT NULL)
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
      insert into public.iva_staff (owner_email, email, name, tel, can_issue, can_catalog, can_chat, can_notify, self_issue, can_delete)
      values (me, em, nm, tl, coalesce(p_issue, false), coalesce(p_catalog, false), coalesce(p_chat, false),
              coalesce(p_notify, false), coalesce(p_self, false), coalesce(p_delete, false) and coalesce(p_catalog, false))
      returning id into sid;
    else
      update public.iva_staff s
         set name = nm, tel = tl, can_issue = coalesce(p_issue, false), can_catalog = coalesce(p_catalog, false),
             can_chat = coalesce(p_chat, false), can_notify = coalesce(p_notify, false), self_issue = coalesce(p_self, false),
             can_delete = coalesce(p_delete, s.can_delete) and coalesce(p_catalog, false),
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
           can_delete = coalesce(p_delete, s.can_delete) and coalesce(p_catalog, false),
           updated_at = now()
     where s.id = sid;
  end if;

  insert into public.iva_staff_points (staff_id, point_id)
  select sid, x from unnest(pts) x
  on conflict do nothing;
  delete from public.iva_staff_points sp where sp.staff_id = sid and sp.point_id <> all(pts);
  return sid;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean, boolean) TO authenticated;

UPDATE public.iva_staff SET name = left(split_part(email, '@', 1), 80) WHERE btrim(coalesce(name, '')) = '';
ALTER TABLE public.iva_staff DROP CONSTRAINT IF EXISTS iva_staff_name_len;
ALTER TABLE public.iva_staff DROP CONSTRAINT IF EXISTS iva_staff_name_check;
ALTER TABLE public.iva_staff ADD CONSTRAINT iva_staff_name_check CHECK (char_length(btrim(name)) BETWEEN 1 AND 80);
NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('комиссия из базы удалена', to_regprocedure('public.iva_fee_balance()') IS NULL AND to_regprocedure('public.iva_fee_calc(text)') IS NULL),
  ('имя сотрудника снова обязательно', EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.iva_staff'::regclass AND conname = 'iva_staff_name_check')
     AND to_regprocedure('public.iva_staff_profile_name(text)') IS NULL)
) AS x(check_name, ok);

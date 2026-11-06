-- ============================================================
--  Ива, сайт и приложение 10.138 (просьба владельца №11 от 06.11.2026) — кабинет владельца.
--
--  Что делает:
--   1. Сотрудник без «Имя и фамилия». Владелец вводит только почту, пункты и права.
--      • iva_staff.name может быть пустым, пока сотрудник не принял приглашение (сайт и приложение показывают почту).
--      • iva_staff_save: имя необязательно. Пустое имя при правке не стирает прежнее
--        (приложение 10.137 и старше по-прежнему присылает имя — оно сохраняется, как раньше).
--      • iva_staff_answer: при «Принять» имя берётся из профиля самого сотрудника
--        (ФИО из профиля клиента, иначе имя из входа, иначе часть почты до «@»).
--      • Сотрудник поменял ФИО в своём профиле — имя у владельца, в журнале и в чатах обновляется само.
--      Новых данных не собирает: это то же имя, которое сотрудник сам указал в своём профиле.
--   2. Комиссия — одна формула в базе для сайта и приложения:
--      • iva_fee_balance() — владельцу проката про свой прокат:
--        начислено (сумма order_parts.fee по всем своим заявкам), оплачено (подтверждённые fee_payments),
--        ждёт подтверждения, процент (admins.fee_pct, пусто — 5), к оплате, переплата.
--      • iva_fee_balances() — владельцу площадки: то же по каждому прокату.
--      Таблицы оплат и заявок не меняет.
--
--  Без этого SQL сайт и приложение работают как раньше (сайт считает комиссию сам, ФИО — как было).
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261127_owner_cabinet_rollback.sql.
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL
     OR to_regprocedure('public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean, boolean)') IS NULL THEN
    RAISE EXCEPTION 'В базе нет сотрудников 10.122 — сначала SQL 20261113_v122';
  END IF;
  IF to_regclass('public.order_parts') IS NULL OR to_regclass('public.fee_payments') IS NULL OR to_regclass('public.admins') IS NULL
     OR to_regprocedure('public.fn_is_chief()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет order_parts / fee_payments / admins / fn_is_chief — это не база «Ивы»';
  END IF;
  IF to_regclass('public.customer_profiles') IS NULL THEN
    RAISE EXCEPTION 'В базе нет профилей клиентов (customer_profiles) — сначала SQL 20261012';
  END IF;
END $do$;

-- ── 1. Имя сотрудника необязательно ──
DO $do$
DECLARE c record;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'public.iva_staff'::regclass AND contype = 'c' AND conname <> 'iva_staff_name_len'
              AND pg_get_constraintdef(oid) ILIKE '%btrim(name)%' LOOP
    EXECUTE format('ALTER TABLE public.iva_staff DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $do$;
ALTER TABLE public.iva_staff DROP CONSTRAINT IF EXISTS iva_staff_name_len;
ALTER TABLE public.iva_staff ADD CONSTRAINT iva_staff_name_len CHECK (char_length(name) <= 80);

-- имя человека из его профиля: ФИО профиля клиента → имя из входа → ''. Только для функций ниже.
CREATE OR REPLACE FUNCTION public.iva_staff_profile_name(p_email text)
 RETURNS text
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  with u as (
    select u.id, to_jsonb(u) -> 'raw_user_meta_data' as meta
      from auth.users u
     where lower(btrim(u.email)) = lower(btrim(coalesce(p_email, ''))) and btrim(coalesce(p_email, '')) <> ''
     limit 1
  ), cp as (
    select nullif(btrim(regexp_replace(concat_ws(' ', nullif(btrim(j ->> 'surname'), ''), nullif(btrim(j ->> 'given_name'), ''),
                                                  nullif(btrim(j ->> 'patronymic'), '')), '\s+', ' ', 'g')), '') as n
      from u, public.customer_profiles p, lateral (select to_jsonb(p) as j) x
     where p.user_id = u.id
     limit 1
  )
  select left(coalesce((select n from cp),
                       (select nullif(btrim(regexp_replace(coalesce(nullif(btrim(meta ->> 'name'), ''), meta ->> 'full_name', ''), '\s+', ' ', 'g')), '') from u),
                       ''), 80);
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_profile_name(text) FROM public, anon, authenticated;
COMMENT ON FUNCTION public.iva_staff_profile_name(text) IS 'Ива, 06.11.2026: имя сотрудника из его профиля (служебная, только для функций базы)';

-- сохранение сотрудника: имя необязательно; пустое при правке — прежнее остаётся
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
  if char_length(nm) > 80 then
    raise exception 'Имя сотрудника — до 80 символов';
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
         set name = case when nm = '' then s.name else nm end, tel = tl, can_issue = coalesce(p_issue, false),
             can_catalog = coalesce(p_catalog, false),
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
       set name = case when nm = '' then s.name else nm end, tel = tl, can_issue = coalesce(p_issue, false),
           can_catalog = coalesce(p_catalog, false),
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

-- ответ на приглашение: при «Принять» — имя из профиля сотрудника
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
     set status = case when coalesce(p_accept, false) then 'active' else 'declined' end, answered_at = now(), updated_at = now(),
         name = case when coalesce(p_accept, false)
                     then left(coalesce(nullif(public.iva_staff_profile_name(me), ''), nullif(btrim(s.name), ''), split_part(me, '@', 1)), 80)
                     else s.name end
   where s.id = p_id;
  return case when coalesce(p_accept, false) then 'active' else 'declined' end;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_answer(uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_answer(uuid, boolean) TO authenticated;

-- сотрудник поменял ФИО в профиле — имя у владельца обновляется (ошибка здесь профиль не ломает)
CREATE OR REPLACE FUNCTION public.iva_staff_name_sync()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare em text; nm text;
begin
  begin
    select lower(btrim(u.email)) into em from auth.users u where u.id = new.user_id;
    if coalesce(em, '') <> '' and exists (select 1 from public.iva_staff s where s.email = em and s.status = 'active') then
      nm := public.iva_staff_profile_name(em);
      if nm <> '' then
        update public.iva_staff s set name = nm, updated_at = now()
         where s.email = em and s.status = 'active' and s.name is distinct from nm;
      end if;
    end if;
  exception when others then
    null;
  end;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_name_sync() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS iva_staff_name_sync ON public.customer_profiles;
CREATE TRIGGER iva_staff_name_sync AFTER INSERT OR UPDATE ON public.customer_profiles
  FOR EACH ROW EXECUTE FUNCTION public.iva_staff_name_sync();

-- ── 2. Комиссия: одна формула для сайта и приложения ──
-- служебная: баланс одного проката (вызывают только функции ниже)
CREATE OR REPLACE FUNCTION public.iva_fee_calc(p_email text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  with e as (select lower(btrim(coalesce(p_email, ''))) as e),
  f as (
    select coalesce(sum(coalesce(op.fee, 0)), 0) as fee,
           coalesce(sum(coalesce(op.rent_sum, 0)) filter (where coalesce(op.fee, 0) <> 0), 0) as rent,
           count(*) filter (where coalesce(op.fee, 0) <> 0) as n
      from public.order_parts op, e where lower(btrim(op.owner_email)) = e.e
  ),
  p as (
    select coalesce(sum(coalesce(fp.amount, 0)) filter (where fp.status = 'confirmed'), 0) as paid,
           coalesce(sum(coalesce(fp.amount, 0)) filter (where fp.status = 'pending'), 0) as pending
      from public.fee_payments fp, e where lower(btrim(fp.admin_email)) = e.e
  ),
  t as (
    select coalesce((select nullif(to_jsonb(a) ->> 'fee_pct', '')::numeric
                       from public.admins a, e where lower(btrim(a.email)) = e.e limit 1), 5) as pct
  )
  select jsonb_build_object('email', (select e from e), 'pct', t.pct, 'fee', f.fee, 'paid', p.paid, 'pending', p.pending,
                            'debt', greatest(f.fee - p.paid, 0), 'over', greatest(p.paid - f.fee, 0),
                            'rent', f.rent, 'orders', f.n, 'at', now())
    from f, p, t;
$function$;
REVOKE ALL ON FUNCTION public.iva_fee_calc(text) FROM public, anon, authenticated;
COMMENT ON FUNCTION public.iva_fee_calc(text) IS 'Ива, 06.11.2026: баланс комиссии проката (служебная)';

-- владельцу проката — свой баланс (сайт «Комиссия и оплата», приложение 10.138 — карточка в «Кабинете»)
CREATE OR REPLACE FUNCTION public.iva_fee_balance()
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare me text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
begin
  if me = '' or not exists (select 1 from public.admins a where lower(btrim(a.email)) = me and coalesce(a.role, '') <> 'chief') then
    raise exception 'Комиссия есть только у владельца проката';
  end if;
  return public.iva_fee_calc(me);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_fee_balance() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_fee_balance() TO authenticated;
COMMENT ON FUNCTION public.iva_fee_balance() IS 'Ива, 06.11.2026: комиссия своего проката — одна формула для сайта и приложения';

-- владельцу площадки — баланс каждого проката
CREATE OR REPLACE FUNCTION public.iva_fee_balances()
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if not public.fn_is_chief() then
    raise exception 'Только владелец площадки';
  end if;
  return coalesce((select jsonb_agg(public.iva_fee_calc(x.e) order by x.e)
                     from (select distinct lower(btrim(a.email)) as e from public.admins a
                            where coalesce(a.role, '') <> 'chief' and btrim(coalesce(a.email, '')) <> '') x), '[]'::jsonb);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_fee_balances() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_fee_balances() TO authenticated;
COMMENT ON FUNCTION public.iva_fee_balances() IS 'Ива, 06.11.2026: комиссия каждого проката — владельцу площадки';

NOTIFY pgrst, 'reload schema';

-- Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('сотрудника можно добавить без имени',
     NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.iva_staff'::regclass AND contype = 'c'
                  AND pg_get_constraintdef(oid) ILIKE '%btrim(name)%')
     AND EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.iva_staff'::regclass AND conname = 'iva_staff_name_len')),
  ('при «Принять» имя берётся из профиля сотрудника',
     to_regprocedure('public.iva_staff_profile_name(text)') IS NOT NULL
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'iva_staff_name_sync' AND tgrelid = 'public.customer_profiles'::regclass)),
  ('служебные функции не вызвать ни гостю, ни вошедшему',
     NOT has_function_privilege('authenticated', 'public.iva_staff_profile_name(text)', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_fee_calc(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_fee_calc(text)', 'EXECUTE')),
  ('комиссия: функции есть, гостю нельзя, вошедшим — только своё',
     to_regprocedure('public.iva_fee_balance()') IS NOT NULL AND to_regprocedure('public.iva_fee_balances()') IS NOT NULL
     AND NOT has_function_privilege('anon', 'public.iva_fee_balance()', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_fee_balance()', 'EXECUTE'))
) AS x(check_name, ok);

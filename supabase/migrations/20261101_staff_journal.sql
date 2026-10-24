-- ============================================================
--  Ива · 10.107 — этап 4 «Сотрудники и пункты»: журнал «кто выдал / кто принял», «Сводка по пунктам» владельцу,
--  имя сотрудника под его ответом в чате владельца (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
--  Применять ПОСЛЕ 20261027_order_points.sql (заявки по пунктам) и 20261031_staff_chat.sql (чат пункта).
--
--  Что делает:
--   • order_parts.issued_by — кто отметил «Выдал» (почта вошедшего), order_parts.returned_by / returned_at — кто и когда
--     отметил «Вернули» (или закрыл заявку без выдачи). Время выдачи — прежний order_parts.rented_at (10.77).
--     Пишет только база (триггер при смене статуса — владелец, сотрудник, сайт); вручную не меняются.
--     Старые заявки — из прежнего журнала order_events (кто менял статус). Клиенты order_parts не читают (как и раньше),
--     сотрудник — только через свои функции, где этих столбцов нет;
--   • iva_part_journal(часть) — владельцу проката (и создателю): «Выдал: Иван · время», «Принял: Вы · время» —
--     имя сотрудника, «Вы» или почта, если сотрудника уже убрали;
--   • iva_points_summary(дней) — владельцу: по каждому пункту — инструменты (всего / видят клиенты / на одобрении),
--     сотрудники, заявки сейчас (новые / выдано), за период — заявки, закрыто, сумма аренды и комиссия,
--     кто сколько выдал и принял. Сотрудник и клиент вызвать не могут;
--   • iva_chat_staff_names(номера сообщений) — владельцу: какие его ответы в чате написал сотрудник и кто
--     (клиент по-прежнему видит ответ от проката). Чтобы после «Убрать из сотрудников» было видно, что ответил
--     сотрудник, связь app_messages.staff_id → iva_staff снимается (номер остаётся, имя — «бывший сотрудник»).
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (журнал, сводка и имена в чате пропадут; заявки, статусы и сообщения остаются):
--      DROP FUNCTION IF EXISTS public.iva_chat_staff_names(text[]);
--      DROP FUNCTION IF EXISTS public.iva_points_summary(int);
--      DROP FUNCTION IF EXISTS public.iva_part_journal(bigint);
--      DROP FUNCTION IF EXISTS public.iva_journal_who(text, text);
--      DROP TRIGGER IF EXISTS trg_b_iva_part_journal ON public.order_parts;
--      DROP FUNCTION IF EXISTS public.iva_part_journal_trg();
--      ALTER TABLE public.order_parts DROP COLUMN IF EXISTS issued_by, DROP COLUMN IF EXISTS returned_by, DROP COLUMN IF EXISTS returned_at;
--      UPDATE public.app_messages SET staff_id = NULL WHERE staff_id IS NOT NULL AND staff_id NOT IN (SELECT id FROM public.iva_staff);
--      ALTER TABLE public.app_messages DROP CONSTRAINT IF EXISTS app_messages_staff_fk;
--      ALTER TABLE public.app_messages ADD CONSTRAINT app_messages_staff_fk FOREIGN KEY (staff_id) REFERENCES public.iva_staff(id) ON DELETE SET NULL;
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.order_parts') IS NULL OR to_regclass('public.order_events') IS NULL
     OR to_regprocedure('public.jwt_email()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет order_parts / order_events / jwt_email — это не база «Ивы»';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'point_id') THEN
    RAISE EXCEPTION 'Сначала примените 20261027_order_points.sql (SQL 10.102: заявки по пунктам)';
  END IF;
  IF to_regclass('public.iva_staff') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261028_staff.sql (SQL 10.103: сотрудники)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'app_messages' AND column_name = 'staff_id') THEN
    RAISE EXCEPTION 'Сначала примените 20261031_staff_chat.sql (SQL 10.106: чат пункта)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'rented_at') THEN
    RAISE EXCEPTION 'Сначала примените 20261014_rent_end_mode.sql (SQL 10.77: время выдачи)';
  END IF;
END $do$;

-- 1. Кто выдал / кто принял
ALTER TABLE public.order_parts ADD COLUMN IF NOT EXISTS issued_by text;
ALTER TABLE public.order_parts ADD COLUMN IF NOT EXISTS returned_by text;
ALTER TABLE public.order_parts ADD COLUMN IF NOT EXISTS returned_at timestamptz;
COMMENT ON COLUMN public.order_parts.issued_by IS 'Ива 10.107: кто отметил «Выдал» (почта вошедшего). Ставит триггер trg_b_iva_part_journal, вручную не меняется.';
COMMENT ON COLUMN public.order_parts.returned_by IS 'Ива 10.107: кто отметил «Вернули» / закрыл заявку (почта вошедшего). Ставит триггер, вручную не меняется.';
COMMENT ON COLUMN public.order_parts.returned_at IS 'Ива 10.107: когда отметили «Вернули» / закрыли заявку. Ставит триггер, вручную не меняется.';

CREATE OR REPLACE FUNCTION public.iva_part_journal_trg()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare me text := nullif(lower(btrim(coalesce(public.jwt_email(), ''))), '');
begin
  new.issued_by := old.issued_by;          -- вручную не меняется — только при смене статуса
  new.returned_by := old.returned_by;
  new.returned_at := old.returned_at;
  if new.status is distinct from old.status then
    if new.status = 'rented' then
      new.issued_by := me;
    elsif new.status = 'closed' then
      new.returned_by := me;
      new.returned_at := now();
    end if;
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_part_journal_trg() FROM public, anon, authenticated;

-- старые заявки — из прежнего журнала (последняя запись «→ rented» / «→ closed»); триггер — после
DROP TRIGGER IF EXISTS trg_b_iva_part_journal ON public.order_parts;
UPDATE public.order_parts p
   SET issued_by = coalesce(p.issued_by, x.iss), returned_by = coalesce(p.returned_by, x.ret),
       returned_at = coalesce(p.returned_at, x.ret_at)
  FROM (SELECT p2.id,
               (SELECT nullif(lower(btrim(e.email)), '') FROM public.order_events e
                 WHERE e.part_id = p2.id AND e.new_status = 'rented' ORDER BY e.id DESC LIMIT 1) AS iss,
               (SELECT nullif(lower(btrim(e.email)), '') FROM public.order_events e
                 WHERE e.part_id = p2.id AND e.new_status = 'closed' ORDER BY e.id DESC LIMIT 1) AS ret,
               (SELECT (to_jsonb(e) ->> 'created_at')::timestamptz FROM public.order_events e
                 WHERE e.part_id = p2.id AND e.new_status = 'closed' ORDER BY e.id DESC LIMIT 1) AS ret_at
          FROM public.order_parts p2
         WHERE p2.status IN ('rented', 'closed')
           AND (p2.issued_by IS NULL OR p2.returned_by IS NULL)) x
 WHERE p.id = x.id
   AND ((p.issued_by IS NULL AND x.iss IS NOT NULL)
        OR (p.status = 'closed' AND p.returned_by IS NULL AND x.ret IS NOT NULL));
CREATE TRIGGER trg_b_iva_part_journal BEFORE UPDATE ON public.order_parts
  FOR EACH ROW EXECUTE FUNCTION public.iva_part_journal_trg();

-- 2. Имя для журнала: «Вы» — владелец, сотрудник этого проката — имя, иначе почта. Только для функций ниже.
CREATE OR REPLACE FUNCTION public.iva_journal_who(p_owner text, p_email text)
 RETURNS text
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select case
    when nullif(lower(btrim(coalesce(p_email, ''))), '') is null then ''
    when lower(btrim(p_email)) = lower(btrim(coalesce(p_owner, ''))) then
      case when lower(btrim(p_email)) = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) then 'Вы' else 'Владелец' end
    else coalesce((select btrim(s.name) from public.iva_staff s
                    where s.owner_email = lower(btrim(p_owner)) and s.email = lower(btrim(p_email)) limit 1),
                  lower(btrim(p_email)))
  end;
$function$;
REVOKE ALL ON FUNCTION public.iva_journal_who(text, text) FROM public, anon, authenticated;

-- 3. Владельцу: журнал одной части заявки (чужая часть — пусто)
CREATE OR REPLACE FUNCTION public.iva_part_journal(p_part bigint)
 RETURNS TABLE (status text, issued_who text, issued_at timestamptz, returned_who text, returned_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.status, public.iva_journal_who(p.owner_email, p.issued_by), p.rented_at,
         public.iva_journal_who(p.owner_email, p.returned_by), p.returned_at
    from public.order_parts p
   where p.id = p_part
     and coalesce(auth.jwt() ->> 'email', '') <> ''
     and (lower(btrim(p.owner_email)) = lower(btrim(auth.jwt() ->> 'email')) or public.fn_is_chief());
$function$;
REVOKE ALL ON FUNCTION public.iva_part_journal(bigint) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_part_journal(bigint) TO authenticated;

-- 4. Владельцу: «Сводка по пунктам» за период (дней: 1…3660; пусто или 0 — за всё время)
CREATE OR REPLACE FUNCTION public.iva_points_summary(p_days int)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me    text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  since timestamptz := case when coalesce(p_days, 0) <= 0 then '-infinity'::timestamptz
                            else now() - make_interval(days => least(p_days, 3660)) end;
  res   jsonb;
begin
  if me = '' or not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  with pts as (
    select pp.id, pp.name, coalesce(pp.city, '') as city, 0 as ord
      from public.iva_pickup_points pp where pp.owner_email = me
    union all
    select null::uuid, 'Без пункта', '', 1
     where exists (select 1 from public.tools t where lower(btrim(coalesce(t.owner_email, ''))) = me and t.point_id is null)
        or exists (select 1 from public.order_parts p where lower(btrim(p.owner_email)) = me and p.point_id is null)
  ), parts as (
    select p.* from public.order_parts p where lower(btrim(p.owner_email)) = me
  ), ppl as (
    select pid, who, sum(iss)::int as issued, sum(ret)::int as returned from (
      select p.point_id as pid, p.issued_by as who, 1 as iss, 0 as ret from parts p
       where p.issued_by is not null and p.rented_at >= since
      union all
      select p.point_id, p.returned_by, 0, 1 from parts p
       where p.returned_by is not null and p.rented_at is not null and coalesce(p.returned_at, p.rented_at) >= since
    ) z group by pid, who
  )
  select jsonb_build_object('days', coalesce(p_days, 0), 'points', coalesce(jsonb_agg(jsonb_build_object(
           'id', coalesce(x.id::text, ''), 'name', x.name, 'city', x.city,
           'tools', (select count(*) from public.tools t where lower(btrim(coalesce(t.owner_email, ''))) = me
                       and t.point_id is not distinct from x.id),
           'shown', (select count(*) from public.tools t where lower(btrim(coalesce(t.owner_email, ''))) = me
                       and t.point_id is not distinct from x.id and coalesce(t.active, true)
                       and coalesce(to_jsonb(t) ->> 'review', '') = ''),
           'pending', (select count(*) from public.tools t where lower(btrim(coalesce(t.owner_email, ''))) = me
                         and t.point_id is not distinct from x.id and to_jsonb(t) ->> 'review' = 'pending'),
           'staff', (select count(distinct s.id) from public.iva_staff s join public.iva_staff_points sp on sp.staff_id = s.id
                      where x.id is not null and sp.point_id = x.id and s.owner_email = me and s.status = 'active'),
           'invited', (select count(distinct s.id) from public.iva_staff s join public.iva_staff_points sp on sp.staff_id = s.id
                        where x.id is not null and sp.point_id = x.id and s.owner_email = me and s.status = 'invited'),
           'new', (select count(*) from parts p where p.point_id is not distinct from x.id and p.status = 'new'
                     and not coalesce(p.archived, false)),
           'rented', (select count(*) from parts p where p.point_id is not distinct from x.id and p.status = 'rented'),
           'parts', (select count(*) from parts p where p.point_id is not distinct from x.id and p.created_at >= since),
           'closed', (select count(*) from parts p where p.point_id is not distinct from x.id and p.status = 'closed'
                        and coalesce(p.returned_at, p.created_at) >= since),
           'rent', (select coalesce(sum(p.rent_sum), 0) from parts p where p.point_id is not distinct from x.id
                      and p.status = 'closed' and coalesce(p.returned_at, p.created_at) >= since),
           'fee', (select coalesce(sum(p.fee), 0) from parts p where p.point_id is not distinct from x.id
                     and p.status = 'closed' and coalesce(p.returned_at, p.created_at) >= since),
           'people', (select coalesce(jsonb_agg(jsonb_build_object('who', public.iva_journal_who(me, g.who),
                                                                   'issued', g.issued, 'returned', g.returned)
                                                order by g.issued desc, g.returned desc, g.who), '[]'::jsonb)
                        from ppl g where g.pid is not distinct from x.id)
         ) order by x.ord, lower(x.name), x.id), '[]'::jsonb))
    into res
    from pts x;
  return res;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_points_summary(int) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_points_summary(int) TO authenticated;

-- 5. Владельцу: какие его ответы в чате написал сотрудник (номера сообщений → имя; до 300 за раз)
ALTER TABLE public.app_messages DROP CONSTRAINT IF EXISTS app_messages_staff_fk;
COMMENT ON COLUMN public.app_messages.staff_id IS 'Ива 10.106/10.107: сотрудник, ответивший от имени проката (iva_staff.id; остаётся и после «Убрать из сотрудников»); у остальных сообщений — пусто.';

CREATE OR REPLACE FUNCTION public.iva_chat_staff_names(p_ids text[])
 RETURNS TABLE (id text, who text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select m.id::text, coalesce((select btrim(s.name) from public.iva_staff s where s.id = m.staff_id), 'бывший сотрудник')
    from public.app_messages m
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and coalesce(array_length(p_ids, 1), 0) between 1 and 300
     and m.id::text = any(p_ids)
     and m.staff_id is not null
     and lower(btrim(m.from_email)) = lower(btrim(auth.jwt() ->> 'email'));
$function$;
REVOKE ALL ON FUNCTION public.iva_chat_staff_names(text[]) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_chat_staff_names(text[]) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 6. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('столбцы «кто выдал / кто принял»',
     (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'order_parts'
        AND column_name IN ('issued_by', 'returned_by', 'returned_at')) = 3),
  ('журнал пишет только база (триггер)',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.order_parts'::regclass AND tgname = 'trg_b_iva_part_journal')),
  ('журнал, сводка и имена в чате — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_part_journal(bigint)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_part_journal(bigint)', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_points_summary(int)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_points_summary(int)', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_chat_staff_names(text[])', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_chat_staff_names(text[])', 'EXECUTE')),
  ('служебная функция закрыта для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_journal_who(text, text)', 'EXECUTE'))
) AS x(check_name, ok);

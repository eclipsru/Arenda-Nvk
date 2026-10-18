-- ============================================================
--  Ива · ОТКАТ 10.121 (20261112_v121.sql). Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
--  Возвращает функции 10.105–10.120 (как в 20261030_staff_tools.sql и 20261111_prokat_page.sql):
--  страница проката — телефон только пункта; «На одобрении» — только новые инструменты; правки сотрудников — сразу.
--  Ждущие правки сотрудников больше не показываются и не применяются (опубликованные инструменты не меняются;
--  строки tools этот файл не переписывает). Первый пункт, если уже создан, остаётся (его можно удалить в «Пунктах
--  выдачи»). Столбцы tools.edit_* и admins.first_point_at остаются — удалить их вместе с ждущими правками можно
--  отдельно (необязательно):
--      ALTER TABLE public.tools DROP COLUMN IF EXISTS edit_pending, DROP COLUMN IF EXISTS edit_at, DROP COLUMN IF EXISTS edit_rejected;
--      ALTER TABLE public.admins DROP COLUMN IF EXISTS first_point_at;
-- ============================================================

DROP TRIGGER IF EXISTS zy_iva_staff_edit ON public.tools;
DROP FUNCTION IF EXISTS public.iva_tools_staff_edit();
DROP FUNCTION IF EXISTS public.iva_order_cards(text);
DROP FUNCTION IF EXISTS public.iva_order_part_card(bigint, boolean);
DROP FUNCTION IF EXISTS public.iva_pickup_first(text, text, double precision, double precision, int[], int[]);

-- прежние (10.105) «На одобрении», «Опубликовать / Отклонить», инструменты сотрудника
DROP FUNCTION IF EXISTS public.iva_tools_pending();
CREATE OR REPLACE FUNCTION public.iva_tools_pending()
 RETURNS TABLE (id uuid, name text, price text, img text, point_name text, staff_name text, created_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select t.id, coalesce(t.name, ''), coalesce(to_jsonb(t) ->> 'price', ''), coalesce(to_jsonb(t) ->> 'img', ''),
         coalesce(pp.name, ''), coalesce(s.name, ''), (to_jsonb(t) ->> 'created_at')::timestamptz
    from public.tools t
    left join public.iva_pickup_points pp on pp.id = t.point_id
    left join public.iva_staff s on s.id = t.staff_id
   where public.iva_pickup_is_admin()
     and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
     and t.review = 'pending'
   order by (to_jsonb(t) ->> 'created_at') desc nulls last, t.id
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_pending() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tools_pending() TO authenticated;

-- 9. Владельцу: «Опубликовать» / «Отклонить»
CREATE OR REPLACE FUNCTION public.iva_tool_review(p_id uuid, p_ok boolean)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  r text;
begin
  if not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  update public.tools set active = coalesce(p_ok, false), review = case when p_ok then null else 'rejected' end
   where id = p_id and review = 'pending'
     and lower(btrim(coalesce(owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
  returning case when p_ok then 'published' else 'rejected' end into r;
  if r is null then raise exception 'Инструмент уже не ждёт одобрения — обновите список'; end if;
  return r;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_review(uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_review(uuid, boolean) TO authenticated;

-- прежние (10.105) инструменты сотрудника
CREATE OR REPLACE FUNCTION public.iva_staff_tools()
 RETURNS SETOF jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select (to_jsonb(t) - 'owner_email' - 'staff_id')
         || jsonb_build_object('point_name', pp.name,
                               'mine', t.staff_id is not null and t.staff_id = s.id)
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
    join public.iva_staff_points sp on sp.staff_id = s.id
    join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = s.owner_email
    join public.tools t on t.point_id = pp.id and lower(btrim(coalesce(t.owner_email, ''))) = s.owner_email
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and s.email = lower(btrim(auth.jwt() ->> 'email'))
     and s.status = 'active' and s.can_catalog
   order by case t.review when 'pending' then 0 when 'rejected' then 1 else 2 end, lower(coalesce(t.name, '')), t.id
   limit 500;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_tools() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_tools() TO authenticated;

-- прежние (10.120) страница проката и карточка «На одобрении»
CREATE OR REPLACE FUNCTION public.iva_prokat_points(p_point uuid)
 RETURNS TABLE (id uuid, name text, address text, city text, open_min int[], close_min int[], tel text,
                lat double precision, lng double precision, company text, avatar text, tools int, main boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  with o as (
    select p.owner_email as owner
      from public.iva_pickup_points p
     where p.id = p_point
       and exists (select 1 from public.tools t where t.point_id = p.id)
     limit 1
  ), a as (
    select coalesce(nullif(btrim(to_jsonb(ad) ->> 'company'), ''), nullif(btrim(to_jsonb(ad) ->> 'full_name'), ''), 'Прокат') as company,
           coalesce(nullif(btrim(to_jsonb(ad) ->> 'org_avatar'), ''), '') as avatar
      from public.admins ad, o
     where lower(btrim(ad.email)) = o.owner and ad.active
     limit 1
  )
  select p.id, p.name, p.address, coalesce(p.city, ''), p.open_min, p.close_min, coalesce(p.tel, ''),
         p.lat::double precision, p.lng::double precision, a.company, a.avatar,
         (select count(*)::int from public.tools t
           where t.point_id = p.id and coalesce(t.active, false)
             and coalesce(to_jsonb(t) ->> 'review', '') not in ('pending', 'rejected')),
         p.id = p_point
    from o
    join a on true
    join public.iva_pickup_points p on p.owner_email = o.owner
   order by p.created_at, p.id
   limit 50;
$function$;
REVOKE ALL ON FUNCTION public.iva_prokat_points(uuid) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_prokat_points(uuid) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.iva_tool_pending_card(p_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  j jsonb;
  pn text := '';
  sn text := '';
  mn text := '';
begin
  if not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  select to_jsonb(t), coalesce(pp.name, ''), coalesce(s.name, '')
    into j, pn, sn
    from public.tools t
    left join public.iva_pickup_points pp on pp.id = t.point_id
    left join public.iva_staff s on s.id = t.staff_id
   where t.id = p_id
     and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'));
  if j is null then raise exception 'Инструмент не найден'; end if;
  if coalesce(j ->> 'model_id', '') ~ '^[0-9]{1,12}$' and to_regclass('public.iva_models') is not null then
    begin
      execute 'select btrim(coalesce(brand, '''') || '' '' || coalesce(model, '''')) from public.iva_models where id = $1'
        into mn using (j ->> 'model_id')::bigint;
    exception when others then mn := '';
    end;
  end if;
  return (j - 'owner_email' - 'staff_id') || jsonb_build_object('point_name', pn, 'staff_name', sn, 'model_text', coalesce(mn, ''));
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_pending_card(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_pending_card(uuid) TO authenticated;

DROP FUNCTION IF EXISTS public.iva_point_tel(uuid);

NOTIFY pgrst, 'reload schema';

SELECT CASE WHEN to_regprocedure('public.iva_order_cards(text)') IS NULL
             AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'zy_iva_staff_edit')
             AND to_regprocedure('public.iva_prokat_points(uuid)') IS NOT NULL
            THEN '✅ откат 10.121 выполнен' ELSE '❌ откат не завершён' END AS result;

-- ============================================================
--  Ива · ОТКАТ 10.122 (20261113_v122.sql). Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
--  Возвращает функции 10.121 (сотрудники, «На одобрении», заявки целиком, комиссия) и убирает новые функции.
--  Остаётся в базе (ничего не удаляем): столбец iva_staff.can_delete, таблицы iva_tool_deletes (запросы на удаление),
--  iva_chat_watch, iva_order_changes (журнал отмен). Отменённые клиентом части заявок остаются со статусом
--  «cancelled» (комиссии по ним нет; приложение 10.121 покажет их как «Закрыта»).
--  По желанию потом, отдельно: DROP TABLE public.iva_chat_watch; (подсказки чата — без них ничего не теряется).
-- ============================================================

DROP TRIGGER IF EXISTS zy_iva_staff_delete ON public.tools;
DROP FUNCTION IF EXISTS public.iva_tools_staff_delete();
DROP FUNCTION IF EXISTS public.iva_staff_tool_delete(uuid);
DROP FUNCTION IF EXISTS public.iva_tool_delete_review(uuid, boolean);
DROP FUNCTION IF EXISTS public.iva_tool_edit_card(uuid);
DROP FUNCTION IF EXISTS public.iva_tool_move(uuid, uuid);
DROP FUNCTION IF EXISTS public.iva_tool_staff(uuid);
DROP FUNCTION IF EXISTS public.iva_chat_mark(text, text);
DROP FUNCTION IF EXISTS public.iva_chat_who(text);
DROP FUNCTION IF EXISTS public.iva_chat_who_am_i(text);
DROP FUNCTION IF EXISTS public.iva_order_cancel(bigint, text);
DROP FUNCTION IF EXISTS public.iva_order_edit(bigint, jsonb, text, boolean);
DROP FUNCTION IF EXISTS public.iva_order_edit_decide(bigint, boolean);
DROP FUNCTION IF EXISTS public.iva_order_followups();
DROP FUNCTION IF EXISTS public.iva_order_followup_answer(bigint, text);
DROP FUNCTION IF EXISTS public.iva_cancel_stats();
DROP FUNCTION IF EXISTS public.iva_cancel_journal();
DROP FUNCTION IF EXISTS public.iva_order_changes_pending();
DROP FUNCTION IF EXISTS public.iva_owner_ping(text);

-- сотрудники — как в 10.104 (20261028_staff.sql)
DROP FUNCTION IF EXISTS public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean, boolean);
DROP FUNCTION IF EXISTS public.iva_staff_list();
DROP FUNCTION IF EXISTS public.iva_staff_me();
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

-- «На одобрении», инструменты сотрудника, часть заявки — как в 10.121 (20261112_v121.sql)
DROP FUNCTION IF EXISTS public.iva_tools_pending();
CREATE OR REPLACE FUNCTION public.iva_tools_pending()
 RETURNS TABLE (id uuid, name text, price text, img text, point_name text, staff_name text, created_at timestamptz,
                edit boolean, edit_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select x.id, x.name, x.price, x.img, x.point_name, x.staff_name, x.created_at, x.edit, x.edit_at
    from (
      select t.id, coalesce(t.name, '') as name, coalesce(to_jsonb(t) ->> 'price', '') as price,
             coalesce(to_jsonb(t) ->> 'img', '') as img, coalesce(pp.name, '') as point_name, coalesce(s.name, '') as staff_name,
             (to_jsonb(t) ->> 'created_at')::timestamptz as created_at, false as edit, null::timestamptz as edit_at,
             (to_jsonb(t) ->> 'created_at')::timestamptz as at
        from public.tools t
        left join public.iva_pickup_points pp on pp.id = t.point_id
        left join public.iva_staff s on s.id = t.staff_id
       where public.iva_pickup_is_admin()
         and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
         and t.review = 'pending'
      union all
      select t.id, coalesce(t.edit_pending ->> 'name', t.name, ''), coalesce(t.edit_pending ->> 'price', to_jsonb(t) ->> 'price', ''),
             coalesce(t.edit_pending ->> 'img', to_jsonb(t) ->> 'img', ''), coalesce(pp.name, ''), coalesce(s.name, ''),
             (to_jsonb(t) ->> 'created_at')::timestamptz, true, t.edit_at, t.edit_at
        from public.tools t
        left join public.iva_pickup_points pp on pp.id = t.point_id
        left join public.iva_staff s on s.id = t.staff_id
       where public.iva_pickup_is_admin()
         and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
         and t.review is null and t.edit_pending is not null
    ) x
   order by x.at desc nulls last, x.id
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_pending() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tools_pending() TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_staff_tools()
 RETURNS SETOF jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select ((to_jsonb(t) || coalesce(t.edit_pending, '{}'::jsonb)) - 'owner_email' - 'staff_id' - 'edit_pending' - 'edit_at' - 'edit_rejected')
         || jsonb_build_object('point_name', pp.name,
                               'mine', t.staff_id is not null and t.staff_id = s.id,
                               'edit_state', case when t.edit_pending is not null then 'pending'
                                                  when coalesce(t.edit_rejected, false) then 'rejected' else '' end)
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
    join public.iva_staff_points sp on sp.staff_id = s.id
    join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = s.owner_email
    join public.tools t on t.point_id = pp.id and lower(btrim(coalesce(t.owner_email, ''))) = s.owner_email
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and s.email = lower(btrim(auth.jwt() ->> 'email'))
     and s.status = 'active' and s.can_catalog
   order by case when t.review = 'pending' or t.edit_pending is not null then 0
                 when t.review = 'rejected' or coalesce(t.edit_rejected, false) then 1 else 2 end,
            lower(coalesce(t.name, '')), t.id
   limit 500;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_tools() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_tools() TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_order_part_card(p_part bigint, p_fee boolean)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select jsonb_build_object(
           'part_id', p.id::text,
           'order_id', p.order_id::text,
           'status', coalesce(nullif(btrim(p.status), ''), 'new'),
           'created_at', p.created_at,
           'rented_at', p.rented_at,
           'rent_sum', p.rent_sum,
           'fee', case when p_fee then p.fee end,
           'tools_text', coalesce(p.tools_text, ''),
           'company', coalesce(nullif(btrim(to_jsonb(ad) ->> 'company'), ''), nullif(btrim(to_jsonb(ad) ->> 'full_name'), ''), ''),
           'prokat_tel', coalesce(nullif(btrim(to_jsonb(ad) ->> 'org_tel'), ''), nullif(btrim(to_jsonb(ad) ->> 'phone'), ''), ''),
           'prokat_addr', coalesce(btrim(to_jsonb(ad) ->> 'address'), ''),
           'work_hours', coalesce(btrim(to_jsonb(ad) ->> 'work_hours'), ''),
           'point', case when pp.id is null then null else
                      jsonb_build_object('id', pp.id, 'name', pp.name, 'address', pp.address, 'city', coalesce(pp.city, ''),
                                         'tel', coalesce(public.iva_point_tel(pp.id), ''),
                                         'open_min', pp.open_min, 'close_min', pp.close_min) end,
           'rent_mode', coalesce(rs.mode, 'day'),
           'close_min', coalesce(pp.close_min, rs.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
           'tz', coalesce(rs.tz, 'Europe/Moscow'),
           'items', coalesce((
              select jsonb_agg(jsonb_build_object(
                       'id', coalesce(e.v ->> 'id', ''),
                       'name', coalesce(nullif(btrim(e.v ->> 'name'), ''), t.name, ''),
                       'price', coalesce(e.v -> 'price', to_jsonb(t) -> 'price'),
                       'days', e.v -> 'days',
                       'img', coalesce(nullif(to_jsonb(t) ->> 'img', ''),
                                       case when jsonb_typeof(to_jsonb(t) -> 'imgs') = 'array' then to_jsonb(t) -> 'imgs' ->> 0 end, ''),
                       'deposit', to_jsonb(t) -> 'deposit',
                       'delivery_price', to_jsonb(t) -> 'delivery_price') order by e.n)
                from jsonb_array_elements(case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end)
                     with ordinality as e(v, n)
                left join public.tools t on t.id::text = e.v ->> 'id'), '[]'::jsonb))
    from public.order_parts p
    left join lateral (select x.* from public.admins x
                        where lower(btrim(x.email)) = lower(btrim(p.owner_email)) order by x.active desc limit 1) ad on true
    left join public.iva_pickup_points pp on pp.id = p.point_id
    left join public.iva_rent_settings rs on rs.email = lower(btrim(coalesce(p.owner_email, '')))
   where p.id = p_part;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_part_card(bigint, boolean) FROM public, anon, authenticated;

-- комиссия и переходы статусов — как в 10.102 (20261027_order_points.sql)
CREATE OR REPLACE FUNCTION public.fn_fee_accrue()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare d int; snap jsonb; rs numeric; pct int;
begin
  if new.status is distinct from old.status then
    if not ((old.status = 'new' and new.status in ('rented','closed')) or (old.status = 'rented' and new.status = 'closed')) then
      raise exception 'Недопустимый перевод статуса: % → %', old.status, new.status;
    end if;
  end if;
  if new.status = 'closed' and old.status is distinct from 'closed' and (new.fee is null or new.fee = 0) then
    select coalesce(new.fee_pct, (select fee_pct from public.admins where lower(email)=lower(new.owner_email)), 5) into pct;
    if jsonb_typeof(new.items) = 'array' and jsonb_array_length(new.items) > 0 then
      -- 10.102: Σ цена × свой срок (цена — на момент заявки)
      select coalesce(jsonb_agg(jsonb_build_object('name', e->>'name', 'price', (e->>'price')::numeric, 'days', (e->>'days')::int)), '[]'::jsonb),
             coalesce(sum((e->>'price')::numeric * greatest(1, (e->>'days')::int)), 0)
        into snap, rs
        from jsonb_array_elements(new.items) e;
    else
      -- без списка (сайт, старые заявки): по названиям, но только инструменты своего проката (и пункта)
      select coalesce(days,1) into d from public.orders where id = new.order_id;
      d := coalesce(d,1);
      select coalesce(jsonb_agg(jsonb_build_object('name', m.name, 'price', m.price)), '[]'::jsonb), coalesce(sum(m.price),0) * d into snap, rs
        from (select distinct t.name, t.price from public.tools t
               where t.active <> false and position(t.name in new.tools_text) > 0
                 and lower(coalesce(t.owner_email, 'eclips.ru@mail.ru')) = lower(new.owner_email)
                 and (new.point_id is null or t.point_id = new.point_id)) m;
    end if;
    if rs is not null and rs > 0 then
      new.price_snapshot := snap; new.rent_sum := rs;
      new.fee := round(rs * pct::numeric / 100.0);
    end if;
  end if;
  if new.status is distinct from old.status then
    insert into public.order_events(part_id, old_status, new_status, email) values (new.id, old.status, new.status, public.jwt_email());
  end if;
  return new;
end
$function$;

NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('функции 10.122 убраны', to_regprocedure('public.iva_order_cancel(bigint, text)') IS NULL
                             AND to_regprocedure('public.iva_chat_who(text)') IS NULL
                             AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'zy_iva_staff_delete')),
  ('сотрудники и заявки — как в 10.121',
     to_regprocedure('public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean)') IS NOT NULL
     AND to_regprocedure('public.iva_order_part_card(bigint, boolean)') IS NOT NULL
     AND pg_get_functiondef('public.fn_fee_accrue()'::regprocedure) NOT LIKE '%iva.part_cancel%')
) AS x(check_name, ok);

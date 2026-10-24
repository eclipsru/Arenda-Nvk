-- ============================================================
--  Ива · 10.122 — удаление инструмента сотрудником через одобрение, полная правка инструмента, перенос в другой
--  пункт, подсказка «кто отвечает» в чате, отмена и изменение заявки клиентом (просьба владельца 18.10.2026).
--  Применять ПОСЛЕ 20261112_v121.sql. Сайт не меняется. Без этого файла приложение 10.122 работает как 10.121
--  (удаление сотрудником — сразу, отмены и изменения заявки нет, подсказки в чате нет).
--
--  Что делает:
--   • право сотрудника «Удаляет инструмент» (iva_staff.can_delete, по умолчанию выключено). Выключено — сотрудник
--     удалить инструмент не может. Включено — удаление ВСЕГДА ждёт владельца (решение владельца): инструмент остаётся
--     в каталоге, у владельца в «На одобрении» строка «Удалить?» («Удалить» / «Оставить»). То же — если старое
--     приложение удаляет штатной кнопкой: база не удаляет, а ставит запрос на одобрение;
--   • iva_tool_edit_card(id) — инструмент целиком для правки (описание, условия, подкатегория, все фото, доставка):
--     штатный список владельца отдавал в правку не все поля, и форма у владельца и сотрудника была разной;
--   • iva_tool_move(id, пункт) — владелец переносит инструмент в другой свой пункт;
--   • чат: iva_chat_mark / iva_chat_who — кто из прокатa открыл переписку, печатает, нажал «Отвечу я», кто ответил
--     последним (владелец и сотрудники с правом чата этого пункта; клиенту не видно);
--   • заявки: клиент сам отменяет заявку, пока ни один инструмент не выдан (с причиной) — части получают статус
--     «cancelled», комиссии нет. Изменить заявку (срок, убрать инструмент, комментарий, доставка → самовывоз) можно
--     до выдачи; изменение ждёт подтверждения проката («Принять» / «Отклонить»). Защита от лазейки: отменить и
--     изменить может только сам клиент из своего аккаунта; прокат перевести заявку в «отменена» не может (база
--     не пустит); через 2 дня после отмены приложение спрашивает клиента «Вы в итоге взяли инструмент?» — «Да»
--     закрывает часть с комиссией; создатель видит журнал отмен и долю отмен по каждому прокату.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ: Supabase → SQL Editor → вставить ВЕСЬ файл 20261113_v122_rollback.sql → Run (функции 10.121 возвращаются;
--  отменённые заявки остаются отменёнными, журнал отмен и запросы на удаление остаются в базе, но не используются).
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_order_cards(text)') IS NULL OR to_regprocedure('public.iva_tools_staff_edit()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261112_v121.sql (SQL 10.121: заявки целиком, правки сотрудников)';
  END IF;
  IF to_regprocedure('public.iva_staff_chat_me()') IS NULL OR to_regprocedure('public.iva_staff_chat_points()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261031_staff_chat.sql и 20261111_prokat_page.sql (чаты пунктов)';
  END IF;
  IF to_regprocedure('public.fn_fee_accrue()') IS NULL OR to_regprocedure('public.fn_is_chief()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет fn_fee_accrue / fn_is_chief — это не база «Ивы»';
  END IF;
END $do$;

-- служебное: отметка владельцу (открытое приложение — сразу, закрытое — push «проверь сейчас»)
CREATE OR REPLACE FUNCTION public.iva_owner_ping(p_owner text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if coalesce(p_owner, '') = '' then return; end if;
  begin
    if to_regprocedure('public.iva_role_ping_touch(text)') is not null then
      execute 'select public.iva_role_ping_touch($1)' using lower(btrim(p_owner));
    end if;
    if to_regprocedure('public.iva_push_enqueue(text, text)') is not null then
      execute 'select public.iva_push_enqueue($1, $2)' using 'o', lower(btrim(p_owner));
    end if;
  exception when others then null;                        -- отметка не должна мешать делу
  end;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_owner_ping(text) FROM public, anon, authenticated;

-- ── 1. Право «Удаляет инструмент» ──
ALTER TABLE public.iva_staff ADD COLUMN IF NOT EXISTS can_delete boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN public.iva_staff.can_delete IS 'Ива 10.122: сотрудник может попросить удалить инструмент (удаление — после одобрения владельца).';

DROP FUNCTION IF EXISTS public.iva_staff_list();
CREATE FUNCTION public.iva_staff_list()
 RETURNS TABLE (id uuid, email text, name text, tel text, can_issue boolean, can_catalog boolean, can_chat boolean,
                can_notify boolean, self_issue boolean, status text, point_ids uuid[], created_at timestamptz, can_delete boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select s.id, s.email, s.name, s.tel, s.can_issue, s.can_catalog, s.can_chat, s.can_notify, s.self_issue, s.status,
         coalesce((select array_agg(sp.point_id order by p.created_at, p.id)
                     from public.iva_staff_points sp join public.iva_pickup_points p on p.id = sp.point_id
                    where sp.staff_id = s.id), '{}'::uuid[]),
         s.created_at, s.can_delete
  from public.iva_staff s
  where public.iva_pickup_is_admin()
    and s.owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', '')))
  order by s.created_at, s.id
  limit 200;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_list() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_list() TO authenticated;

DROP FUNCTION IF EXISTS public.iva_staff_me();
CREATE FUNCTION public.iva_staff_me()
 RETURNS TABLE (id uuid, status text, company text, can_issue boolean, can_catalog boolean, can_chat boolean,
                can_notify boolean, self_issue boolean, points jsonb, can_delete boolean)
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
                    where sp.staff_id = s.id), '[]'::jsonb),
         s.can_delete
  from public.iva_staff s
  join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
  where coalesce(auth.jwt() ->> 'email', '') <> ''
    and s.email = lower(btrim(auth.jwt() ->> 'email'))
    and s.status in ('invited', 'active')
  limit 1;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_me() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_me() TO authenticated;

-- сохранение сотрудника: + p_delete (не передали — право не меняется; приложение 10.121 его не знает)
DROP FUNCTION IF EXISTS public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean);
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

-- ── 2. Удаление инструмента сотрудником — запрос владельцу ──
CREATE TABLE IF NOT EXISTS public.iva_tool_deletes (
  tool_id     uuid        PRIMARY KEY REFERENCES public.tools(id) ON DELETE CASCADE,
  owner_email text        NOT NULL,
  staff_id    uuid        REFERENCES public.iva_staff(id) ON DELETE SET NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.iva_tool_deletes ENABLE ROW LEVEL SECURITY;    -- правил нет: только функции ниже
REVOKE ALL ON public.iva_tool_deletes FROM anon, authenticated;
COMMENT ON TABLE public.iva_tool_deletes IS 'Ива 10.122: сотрудник попросил удалить инструмент — ждёт владельца.';

-- сотрудник этого инструмента (активный, «Выкладывает инструмент», пункт инструмента — его); null — не сотрудник
CREATE OR REPLACE FUNCTION public.iva_tool_staff(p_tool uuid)
 RETURNS TABLE (sid uuid, owner text, can_delete boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select s.id, s.owner_email, s.can_delete
    from public.tools t
    join public.iva_staff s on s.owner_email = lower(btrim(coalesce(t.owner_email, '')))
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
    join public.iva_staff_points sp on sp.staff_id = s.id and sp.point_id = t.point_id
   where t.id = p_tool
     and coalesce(auth.jwt() ->> 'email', '') <> ''
     and s.email = lower(btrim(auth.jwt() ->> 'email'))
     and s.status = 'active' and s.can_catalog
   limit 1;
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_staff(uuid) FROM public, anon, authenticated;

CREATE OR REPLACE FUNCTION public.iva_staff_tool_delete(p_id uuid)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me record;
begin
  select * into me from public.iva_tool_staff(p_id);
  if not found then raise exception 'Инструмент не найден в вашем пункте'; end if;
  if not me.can_delete then raise exception 'Удалять инструменты вам не разрешил владелец проката'; end if;
  insert into public.iva_tool_deletes(tool_id, owner_email, staff_id) values (p_id, me.owner, me.sid)
  on conflict (tool_id) do update set staff_id = excluded.staff_id, created_at = now();
  perform public.iva_owner_ping(me.owner);
  return 'pending';
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_tool_delete(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_tool_delete(uuid) TO authenticated;

-- штатная кнопка «Удалить» у сотрудника (и старое приложение): не удаляем — ставим запрос
CREATE OR REPLACE FUNCTION public.iva_tools_staff_delete()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  me record;
begin
  if auth.jwt() is null or em = '' then return old; end if;          -- база сама, SQL Editor
  if exists (select 1 from public.admins a where a.active and a.role in ('admin', 'chief') and lower(btrim(a.email)) = em) then
    return old;                                                        -- владелец, создатель — удаляют сразу
  end if;
  select * into me from public.iva_tool_staff(old.id);
  if not found then return old; end if;                              -- не сотрудник этого пункта — решают правила доступа
  if not me.can_delete then raise exception 'Удалять инструменты вам не разрешил владелец проката'; end if;
  insert into public.iva_tool_deletes(tool_id, owner_email, staff_id) values (old.id, me.owner, me.sid)
  on conflict (tool_id) do update set staff_id = excluded.staff_id, created_at = now();
  perform public.iva_owner_ping(me.owner);
  return null;                                                         -- удалит владелец, когда одобрит
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_staff_delete() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS zy_iva_staff_delete ON public.tools;
CREATE TRIGGER zy_iva_staff_delete BEFORE DELETE ON public.tools
  FOR EACH ROW EXECUTE FUNCTION public.iva_tools_staff_delete();

-- «На одобрении»: + запросы на удаление (del)
DROP FUNCTION IF EXISTS public.iva_tools_pending();
CREATE FUNCTION public.iva_tools_pending()
 RETURNS TABLE (id uuid, name text, price text, img text, point_name text, staff_name text, created_at timestamptz,
                edit boolean, edit_at timestamptz, del boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select x.id, x.name, x.price, x.img, x.point_name, x.staff_name, x.created_at, x.edit, x.edit_at, x.del
    from (
      select t.id, coalesce(t.name, '') as name, coalesce(to_jsonb(t) ->> 'price', '') as price,
             coalesce(to_jsonb(t) ->> 'img', '') as img, coalesce(pp.name, '') as point_name, coalesce(s.name, '') as staff_name,
             (to_jsonb(t) ->> 'created_at')::timestamptz as created_at, false as edit, null::timestamptz as edit_at, false as del,
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
             (to_jsonb(t) ->> 'created_at')::timestamptz, true, t.edit_at, false, t.edit_at
        from public.tools t
        left join public.iva_pickup_points pp on pp.id = t.point_id
        left join public.iva_staff s on s.id = t.staff_id
       where public.iva_pickup_is_admin()
         and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
         and t.review is null and t.edit_pending is not null
      union all
      select t.id, coalesce(t.name, ''), coalesce(to_jsonb(t) ->> 'price', ''), coalesce(to_jsonb(t) ->> 'img', ''),
             coalesce(pp.name, ''), coalesce(s.name, ''), (to_jsonb(t) ->> 'created_at')::timestamptz, false, d.created_at, true,
             d.created_at
        from public.iva_tool_deletes d
        join public.tools t on t.id = d.tool_id
        left join public.iva_pickup_points pp on pp.id = t.point_id
        left join public.iva_staff s on s.id = d.staff_id
       where public.iva_pickup_is_admin()
         and d.owner_email = lower(btrim(auth.jwt() ->> 'email'))
         and lower(btrim(coalesce(t.owner_email, ''))) = d.owner_email
    ) x
   order by x.at desc nulls last, x.id
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_pending() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tools_pending() TO authenticated;

-- «Удалить» / «Оставить» в «На одобрении»
CREATE OR REPLACE FUNCTION public.iva_tool_delete_review(p_id uuid, p_ok boolean)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
begin
  if not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  if not exists (select 1 from public.iva_tool_deletes d where d.tool_id = p_id and d.owner_email = em) then
    raise exception 'Инструмент уже не ждёт одобрения — обновите список';
  end if;
  if coalesce(p_ok, false) then
    delete from public.tools t where t.id = p_id and lower(btrim(coalesce(t.owner_email, ''))) = em;
    delete from public.iva_tool_deletes d where d.tool_id = p_id;
    return 'deleted';
  end if;
  delete from public.iva_tool_deletes d where d.tool_id = p_id and d.owner_email = em;
  return 'kept';
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_delete_review(uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_delete_review(uuid, boolean) TO authenticated;

-- сотруднику: + состояние «удаление на одобрении» (edit_state = 'delete')
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
                               'can_delete', s.can_delete,
                               'edit_state', case when exists (select 1 from public.iva_tool_deletes d where d.tool_id = t.id) then 'delete'
                                                  when t.edit_pending is not null then 'pending'
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

-- ── 3. Инструмент целиком для правки; перенос в другой пункт ──
CREATE OR REPLACE FUNCTION public.iva_tool_edit_card(p_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  j  jsonb;
  ep jsonb;
  ow text;
  pn text := '';
begin
  if em = '' then raise exception 'Войдите в приложение'; end if;
  select to_jsonb(t), t.edit_pending, lower(btrim(coalesce(t.owner_email, ''))), coalesce(pp.name, '')
    into j, ep, ow, pn
    from public.tools t
    left join public.iva_pickup_points pp on pp.id = t.point_id
   where t.id = p_id;
  if j is null then raise exception 'Инструмент не найден'; end if;
  if ow = em and public.iva_pickup_is_admin() then
    null;                                                              -- владелец: опубликованный вариант
  elsif exists (select 1 from public.iva_tool_staff(p_id)) then
    j := j || coalesce(ep, '{}'::jsonb);                               -- сотрудник: со своей правкой
  else
    raise exception 'Инструмент не найден';
  end if;
  return (j - 'owner_email' - 'staff_id' - 'edit_pending' - 'edit_at' - 'edit_rejected')
         || jsonb_build_object('point_name', pn,
                               'delete_pending', exists (select 1 from public.iva_tool_deletes d where d.tool_id = p_id));
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_edit_card(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_edit_card(uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_tool_move(p_id uuid, p_point uuid)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  pn text;
begin
  if not public.iva_pickup_is_admin() then raise exception 'Переносит инструменты владелец проката'; end if;
  select p.name into pn from public.iva_pickup_points p where p.id = p_point and p.owner_email = em;
  if pn is null then raise exception 'Пункт выдачи не найден у вашего проката'; end if;
  update public.tools t set point_id = p_point
   where t.id = p_id and lower(btrim(coalesce(t.owner_email, ''))) = em;
  if not found then raise exception 'Инструмент не найден'; end if;
  return pn;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_move(uuid, uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_move(uuid, uuid) TO authenticated;

-- ── 4. Чат: кто из проката открыл переписку, печатает, отвечает ──
CREATE TABLE IF NOT EXISTS public.iva_chat_watch (
  thread     text        NOT NULL CHECK (char_length(thread) <= 120),
  email      text        NOT NULL,
  name       text        NOT NULL DEFAULT '',
  role       text        NOT NULL DEFAULT 'staff' CHECK (role IN ('owner', 'staff')),
  open_at    timestamptz,
  typing_at  timestamptz,
  claim_at   timestamptz,
  PRIMARY KEY (thread, email)
);
ALTER TABLE public.iva_chat_watch ENABLE ROW LEVEL SECURITY;      -- правил нет: только функции ниже
REVOKE ALL ON public.iva_chat_watch FROM anon, authenticated;
COMMENT ON TABLE public.iva_chat_watch IS 'Ива 10.122: кто из проката сейчас в переписке с клиентом (подсказка, чтобы не ответить вдвоём).';

-- переписка «t:<инструмент>:<md5 почты клиента>» или «p:<пункт>:<md5>»: можно ли мне её видеть и кто я
CREATE OR REPLACE FUNCTION public.iva_chat_who_am_i(p_thread text)
 RETURNS TABLE (owner text, point uuid, tool uuid, peer text, role text, name text)
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  m  text[];
  ow text;
  pt uuid;
  tl uuid;
  sn text;
begin
  if em = '' then return; end if;
  m := regexp_match(coalesce(p_thread, ''), '^([tp]):([0-9a-fA-F-]{36}):([0-9a-f]{32})$');
  if m is null then return; end if;
  if m[1] = 't' then
    select lower(btrim(coalesce(t.owner_email, ''))), t.point_id, t.id into ow, pt, tl from public.tools t where t.id = m[2]::uuid;
  else
    select p.owner_email, p.id into ow, pt from public.iva_pickup_points p where p.id = m[2]::uuid;
  end if;
  if ow is null or ow = '' then return; end if;
  if ow = em and public.iva_pickup_is_admin() then
    return query select ow, pt, tl, m[3], 'owner'::text, 'Владелец'::text;
    return;
  end if;
  select s.name into sn
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
    join public.iva_staff_points sp on sp.staff_id = s.id
   where s.email = em and s.owner_email = ow and s.status = 'active' and s.can_chat and pt is not null and sp.point_id = pt
   limit 1;
  if sn is null then return; end if;
  return query select ow, pt, tl, m[3], 'staff'::text, coalesce(nullif(btrim(sn), ''), 'Сотрудник');
end
$function$;
REVOKE ALL ON FUNCTION public.iva_chat_who_am_i(text) FROM public, anon, authenticated;

-- отметка: open (открыл переписку), typing (печатает), claim («Отвечу я»), unclaim, close (вышел)
CREATE OR REPLACE FUNCTION public.iva_chat_mark(p_thread text, p_state text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  me record;
begin
  select * into me from public.iva_chat_who_am_i(p_thread);
  if not found then return; end if;                                   -- чужая переписка — молча ничего
  if p_state not in ('open', 'typing', 'claim', 'unclaim', 'close') then return; end if;
  insert into public.iva_chat_watch(thread, email, name, role) values (p_thread, em, me.name, me.role)
  on conflict (thread, email) do update set name = excluded.name, role = excluded.role;
  update public.iva_chat_watch w
     set open_at   = case when p_state = 'close' then null when p_state in ('open', 'typing', 'claim') then now() else w.open_at end,
         typing_at = case when p_state = 'typing' then now() when p_state in ('close', 'unclaim') then null else w.typing_at end,
         claim_at  = case when p_state = 'claim' then now() when p_state in ('unclaim', 'close') then null else w.claim_at end
   where w.thread = p_thread and w.email = em;
  delete from public.iva_chat_watch w where w.open_at < now() - interval '1 day' and w.claim_at is null;   -- старые отметки
end
$function$;
REVOKE ALL ON FUNCTION public.iva_chat_mark(text, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_chat_mark(text, text) TO authenticated;

-- кто ещё в переписке (без меня) и кто из проката ответил последним
CREATE OR REPLACE FUNCTION public.iva_chat_who(p_thread text)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  me record;
  others jsonb;
  last jsonb;
begin
  select * into me from public.iva_chat_who_am_i(p_thread);
  if not found then return null; end if;
  select coalesce(jsonb_agg(jsonb_build_object('name', w.name, 'role', w.role,
                                               'open', coalesce(w.open_at > now() - interval '40 seconds', false),
                                               'typing', coalesce(w.typing_at > now() - interval '8 seconds', false),
                                               'claim', coalesce(w.claim_at > now() - interval '15 minutes', false))
                            order by w.claim_at desc nulls last, w.open_at desc nulls last), '[]'::jsonb)
    into others
    from public.iva_chat_watch w
   where w.thread = p_thread and w.email <> em
     and (w.open_at > now() - interval '40 seconds' or w.claim_at > now() - interval '15 minutes');
  select jsonb_build_object('name', case when x.staff_id is null then 'Владелец' else coalesce(nullif(btrim(s.name), ''), 'Сотрудник') end,
                            'at', x.created_at, 'me', coalesce(x.staff_id is not null and s.email = em, false)
                                                     or (x.staff_id is null and me.role = 'owner'))
    into last
    from public.app_messages x
    left join public.iva_staff s on s.id = x.staff_id
   where lower(btrim(x.from_email)) = me.owner
     and md5(lower(btrim(x.to_email))) = me.peer
     and ((me.tool is not null and x.tool_id = me.tool) or (me.tool is null and x.point_id = me.point and x.tool_id is null))
   order by x.created_at desc
   limit 1;
  return jsonb_build_object('others', others, 'last', last);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_chat_who(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_chat_who(text) TO authenticated;

-- ── 5. Отмена и изменение заявки клиентом ──
CREATE TABLE IF NOT EXISTS public.iva_order_changes (
  id           bigserial   PRIMARY KEY,
  order_id     bigint      NOT NULL,
  part_id      bigint      NOT NULL,
  owner_email  text        NOT NULL,
  client_email text        NOT NULL,
  kind         text        NOT NULL CHECK (kind IN ('cancel', 'edit')),
  state        text        NOT NULL CHECK (state IN ('pending', 'accepted', 'rejected', 'void', 'done')),
  before       jsonb,
  after        jsonb,
  reason       text        NOT NULL DEFAULT '' CHECK (char_length(reason) <= 300),
  created_at   timestamptz NOT NULL DEFAULT now(),
  decided_at   timestamptz,
  decided_by   text,
  ask_after    timestamptz,
  asked        int         NOT NULL DEFAULT 0,
  answer       text        CHECK (answer IN ('took', 'not')),
  answered_at  timestamptz,
  fee_after    numeric
);
CREATE INDEX IF NOT EXISTS idx_iva_order_changes_part ON public.iva_order_changes (part_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_iva_order_changes_client ON public.iva_order_changes (client_email, kind, answer);
CREATE INDEX IF NOT EXISTS idx_iva_order_changes_owner ON public.iva_order_changes (owner_email, created_at DESC);
ALTER TABLE public.iva_order_changes ENABLE ROW LEVEL SECURITY;   -- правил нет: только функции ниже
REVOKE ALL ON public.iva_order_changes FROM anon, authenticated;
COMMENT ON TABLE public.iva_order_changes IS 'Ива 10.122: отмены и изменения заявок клиентом (журнал для создателя, вопрос «взяли?»).';

-- статусы части: + «cancelled» (отменил клиент). Перевести в «cancelled» может только функция отмены клиента,
-- вернуть из «cancelled» в «closed» (с комиссией) — только ответ клиента «Да, взял». Комиссия — как в 10.102.
-- Отметки iva.part_cancel / iva.part_took ставят только функции ниже и только на время своей транзакции;
-- из приложения или сайта (PostgREST) их поставить нельзя.
CREATE OR REPLACE FUNCTION public.fn_fee_accrue()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare d int; snap jsonb; rs numeric; pct int;
begin
  if new.status is distinct from old.status then
    if not ((old.status = 'new' and new.status in ('rented','closed')) or (old.status = 'rented' and new.status = 'closed')
            or (old.status = 'new' and new.status = 'cancelled' and coalesce(current_setting('iva.part_cancel', true), '') = '1')
            or (old.status = 'cancelled' and new.status = 'closed' and coalesce(current_setting('iva.part_took', true), '') = '1')) then
      raise exception 'Недопустимый перевод статуса: % → %', old.status, new.status;
    end if;
  end if;
  if new.status = 'closed' and old.status is distinct from 'closed' and (new.fee is null or new.fee = 0) then
    select coalesce(new.fee_pct, (select fee_pct from public.admins where lower(email)=lower(new.owner_email)), 5) into pct;
    if jsonb_typeof(new.items) = 'array' and jsonb_array_length(new.items) > 0 then
      select coalesce(jsonb_agg(jsonb_build_object('name', e->>'name', 'price', (e->>'price')::numeric, 'days', (e->>'days')::int)), '[]'::jsonb),
             coalesce(sum((e->>'price')::numeric * greatest(1, (e->>'days')::int)), 0)
        into snap, rs
        from jsonb_array_elements(new.items) e;
    else
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
  if new.status = 'cancelled' then new.fee := 0; end if;
  if new.status is distinct from old.status then
    insert into public.order_events(part_id, old_status, new_status, email) values (new.id, old.status, new.status, public.jwt_email());
  end if;
  return new;
end
$function$;

-- клиент: отменить заявку целиком (пока ничего не выдано)
CREATE OR REPLACE FUNCTION public.iva_order_cancel(p_order bigint, p_reason text)
 RETURNS int
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  rs  text := btrim(regexp_replace(coalesce(p_reason, ''), '\s+', ' ', 'g'));
  p   record;
  n   int := 0;
begin
  if em = '' then raise exception 'Войдите в приложение'; end if;
  if not exists (select 1 from public.orders o where o.id = p_order and lower(btrim(coalesce(o.user_email, ''))) = em) then
    raise exception 'Заявка не найдена';
  end if;
  if char_length(rs) < 2 then raise exception 'Укажите причину отмены'; end if;
  if char_length(rs) > 300 then rs := left(rs, 300); end if;
  if exists (select 1 from public.order_parts x where x.order_id = p_order and x.status in ('rented', 'closed')) then
    raise exception 'Инструмент по заявке уже выдан — отменить её нельзя. Напишите в прокат';
  end if;
  if not exists (select 1 from public.order_parts x where x.order_id = p_order and x.status = 'new') then
    raise exception 'Заявка уже отменена';
  end if;
  perform set_config('iva.part_cancel', '1', true);
  for p in select * from public.order_parts x where x.order_id = p_order and x.status = 'new' order by x.id for update loop
    update public.order_parts set status = 'cancelled' where id = p.id;
    update public.iva_order_changes c set state = 'void', decided_at = now() where c.part_id = p.id and c.state = 'pending';
    insert into public.iva_order_changes(order_id, part_id, owner_email, client_email, kind, state, before, reason, ask_after)
    values (p_order, p.id, lower(btrim(p.owner_email)), em, 'cancel', 'done',
            jsonb_build_object('items', p.items, 'tools_text', p.tools_text), rs, now() + interval '2 days');
    perform public.iva_owner_ping(p.owner_email);
    n := n + 1;
  end loop;
  perform set_config('iva.part_cancel', '', true);
  return n;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_cancel(bigint, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_order_cancel(bigint, text) TO authenticated;

-- клиент: изменить часть заявки (до выдачи) — ждёт подтверждения проката
--   p_items: [{"id": "<инструмент>", "days": N}] — оставшиеся инструменты части и их срок;
--   p_comment: новый комментарий (null — не менять); p_pickup: true — вместо доставки самовывоз
CREATE OR REPLACE FUNCTION public.iva_order_edit(p_part bigint, p_items jsonb, p_comment text, p_pickup boolean DEFAULT false)
 RETURNS bigint
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em   text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  p    record;
  o    record;
  its  jsonb := '[]'::jsonb;
  e    jsonb;
  cur  jsonb;
  d    int;
  cm   text;
  gm   text;
  aft  jsonb;
  bef  jsonb;
  r    bigint;
begin
  if em = '' then raise exception 'Войдите в приложение'; end if;
  select * into p from public.order_parts x where x.id = p_part for update;
  if not found then raise exception 'Заявка не найдена'; end if;
  select * into o from public.orders x where x.id = p.order_id;
  if o is null or lower(btrim(coalesce(o.user_email, ''))) <> em then raise exception 'Заявка не найдена'; end if;
  if p.status <> 'new' then raise exception 'Инструмент уже выдан или заявка закрыта — изменить нельзя. Напишите в прокат'; end if;
  if jsonb_typeof(p.items) <> 'array' or jsonb_array_length(p.items) = 0 then
    raise exception 'Эту заявку изменить в приложении нельзя — напишите в прокат';
  end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
    raise exception 'Оставьте хотя бы один инструмент. Чтобы отказаться от всех — отмените заявку';
  end if;
  for e in select x from jsonb_array_elements(p_items) x loop
    select x into cur from jsonb_array_elements(p.items) x where x ->> 'id' = e ->> 'id' limit 1;
    if cur is null then raise exception 'В заявке нет такого инструмента'; end if;
    if exists (select 1 from jsonb_array_elements(its) y where y ->> 'id' = e ->> 'id') then continue; end if;
    begin d := (e ->> 'days')::int; exception when others then d := null; end;
    if d is null or d < 1 or d > 365 then raise exception 'Срок — от 1 до 365 суток'; end if;
    its := its || jsonb_build_array(cur || jsonb_build_object('days', d));
  end loop;
  cm := case when p_comment is null then coalesce(o.comment, '') else left(btrim(p_comment), 1000) end;
  gm := coalesce(o.get_method, '');
  if coalesce(p_pickup, false) and gm !~* 'самовывоз' then gm := 'Самовывоз'; end if;
  bef := jsonb_build_object('items', p.items, 'comment', coalesce(o.comment, ''), 'get_method', coalesce(o.get_method, ''));
  aft := jsonb_build_object('items', its, 'comment', cm, 'get_method', gm);
  if aft = bef then raise exception 'Ничего не изменилось'; end if;
  update public.iva_order_changes c set state = 'void', decided_at = now() where c.part_id = p.id and c.state = 'pending';
  insert into public.iva_order_changes(order_id, part_id, owner_email, client_email, kind, state, before, after)
  values (p.order_id, p.id, lower(btrim(p.owner_email)), em, 'edit', 'pending', bef, aft)
  returning id into r;
  perform public.iva_owner_ping(p.owner_email);
  return r;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_edit(bigint, jsonb, text, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_order_edit(bigint, jsonb, text, boolean) TO authenticated;

-- прокат: «Принять» / «Отклонить» изменение (владелец или сотрудник с «Выдаёт и принимает» в пункте части)
CREATE OR REPLACE FUNCTION public.iva_order_edit_decide(p_change bigint, p_ok boolean)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  c   record;
  p   record;
  ok  boolean := false;
begin
  if em = '' then raise exception 'Войдите в приложение'; end if;
  select * into c from public.iva_order_changes x where x.id = p_change and x.kind = 'edit' for update;
  if not found then raise exception 'Изменение не найдено'; end if;
  select * into p from public.order_parts x where x.id = c.part_id for update;
  if p is null then raise exception 'Изменение не найдено'; end if;
  if c.owner_email = em and public.iva_pickup_is_admin() then ok := true;
  elsif exists (select 1 from public.iva_staff s
                  join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
                  join public.iva_staff_points sp on sp.staff_id = s.id and sp.point_id = p.point_id
                 where s.email = em and s.owner_email = c.owner_email and s.status = 'active' and s.can_issue) then ok := true;
  end if;
  if not ok then raise exception 'Изменение не найдено'; end if;
  if c.state <> 'pending' then raise exception 'Изменение уже решено — обновите список'; end if;
  if p.status <> 'new' then
    update public.iva_order_changes set state = 'void', decided_at = now(), decided_by = em where id = c.id;
    return 'issued';                                     -- уже выдан: изменение не применяется (приложение так и скажет)
  end if;
  if coalesce(p_ok, false) then
    update public.order_parts
       set items = c.after -> 'items',
           tools_text = coalesce((select string_agg(x ->> 'name', ', ' order by n)
                                    from jsonb_array_elements(c.after -> 'items') with ordinality as y(x, n)), tools_text)
     where id = p.id;
    update public.orders
       set comment = c.after ->> 'comment',
           get_method = c.after ->> 'get_method'
     where id = p.order_id;                                 -- срок — у каждого инструмента (items), orders.days не трогаем
    update public.iva_order_changes set state = 'accepted', decided_at = now(), decided_by = em where id = c.id;
    return 'accepted';
  end if;
  update public.iva_order_changes set state = 'rejected', decided_at = now(), decided_by = em where id = c.id;
  return 'rejected';
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_edit_decide(bigint, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_order_edit_decide(bigint, boolean) TO authenticated;

-- клиент: вопросы «Вы в итоге взяли инструмент?» (через 2 дня после отмены; не чаще раза в сутки, не больше 3 раз)
CREATE OR REPLACE FUNCTION public.iva_order_followups()
 RETURNS TABLE (id bigint, order_id bigint, company text, tools_text text, cancelled_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select c.id, c.order_id,
         coalesce((select coalesce(nullif(btrim(to_jsonb(a) ->> 'company'), ''), nullif(btrim(to_jsonb(a) ->> 'full_name'), ''))
                     from public.admins a where lower(btrim(a.email)) = c.owner_email limit 1), 'Прокат'),
         coalesce(c.before ->> 'tools_text', ''), c.created_at
    from public.iva_order_changes c
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and c.client_email = lower(btrim(auth.jwt() ->> 'email'))
     and c.kind = 'cancel' and c.state = 'done' and c.answer is null
     and c.ask_after <= now() and c.asked < 3 and c.created_at > now() - interval '30 days'
   order by c.created_at
   limit 3;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_followups() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_order_followups() TO authenticated;

-- ответ: took — взял (часть закрывается с комиссией), not — не брал, later — спросить завтра
CREATE OR REPLACE FUNCTION public.iva_order_followup_answer(p_id bigint, p_answer text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  c  record;
  f  numeric;
begin
  select * into c from public.iva_order_changes x
   where x.id = p_id and x.client_email = em and x.kind = 'cancel' and x.state = 'done' and x.answer is null
   for update;
  if not found then return 'gone'; end if;
  if p_answer = 'later' then
    update public.iva_order_changes set asked = asked + 1, ask_after = now() + interval '1 day' where id = c.id;
    return 'later';
  end if;
  if p_answer = 'took' then
    perform set_config('iva.part_took', '1', true);
    update public.order_parts set status = 'closed' where id = c.part_id and status = 'cancelled'
    returning fee into f;
    perform set_config('iva.part_took', '', true);
    update public.iva_order_changes set answer = 'took', answered_at = now(), asked = asked + 1, fee_after = f where id = c.id;
    perform public.iva_owner_ping(c.owner_email);
    return 'took';
  end if;
  if p_answer = 'not' then
    update public.iva_order_changes set answer = 'not', answered_at = now(), asked = asked + 1 where id = c.id;
    return 'not';
  end if;
  raise exception 'Неизвестный ответ';
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_followup_answer(bigint, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_order_followup_answer(bigint, text) TO authenticated;

-- создатель: доля отмен по прокатам (90 дней) и журнал
CREATE OR REPLACE FUNCTION public.iva_cancel_stats()
 RETURNS TABLE (owner_email text, company text, parts int, cancelled int, took int, edits int)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select g.em,
         coalesce((select coalesce(nullif(btrim(to_jsonb(a) ->> 'company'), ''), nullif(btrim(to_jsonb(a) ->> 'full_name'), ''))
                     from public.admins a where lower(btrim(a.email)) = g.em limit 1), g.em),
         g.parts, g.cancelled, g.took, g.edits
    from (
      select lower(btrim(p.owner_email)) as em,
             count(*)::int as parts,
             count(*) filter (where exists (select 1 from public.iva_order_changes c where c.part_id = p.id and c.kind = 'cancel'))::int as cancelled,
             count(*) filter (where exists (select 1 from public.iva_order_changes c where c.part_id = p.id and c.kind = 'cancel' and c.answer = 'took'))::int as took,
             count(*) filter (where exists (select 1 from public.iva_order_changes c where c.part_id = p.id and c.kind = 'edit' and c.state = 'accepted'))::int as edits
        from public.order_parts p
       where public.fn_is_chief() and p.created_at > now() - interval '90 days'
       group by 1
    ) g
   order by g.cancelled desc, g.parts desc
   limit 200;
$function$;
REVOKE ALL ON FUNCTION public.iva_cancel_stats() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_cancel_stats() TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_cancel_journal()
 RETURNS TABLE (id bigint, order_id bigint, company text, tools_text text, reason text, created_at timestamptz,
                answer text, fee_after numeric)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select c.id, c.order_id,
         coalesce((select coalesce(nullif(btrim(to_jsonb(a) ->> 'company'), ''), nullif(btrim(to_jsonb(a) ->> 'full_name'), ''))
                     from public.admins a where lower(btrim(a.email)) = c.owner_email limit 1), c.owner_email),
         coalesce(c.before ->> 'tools_text', ''), c.reason, c.created_at, coalesce(c.answer, ''), c.fee_after
    from public.iva_order_changes c
   where public.fn_is_chief() and c.kind = 'cancel'
   order by c.created_at desc
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_cancel_journal() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_cancel_journal() TO authenticated;

-- заявки целиком: + отмена (причина, когда) и последнее изменение части (ждёт / принято / отклонено)
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
                left join public.tools t on t.id::text = e.v ->> 'id'), '[]'::jsonb),
           'editable', jsonb_typeof(p.items) = 'array' and jsonb_array_length(p.items) > 0,
           'cancel', (select jsonb_build_object('reason', c.reason, 'at', c.created_at, 'answer', coalesce(c.answer, ''))
                        from public.iva_order_changes c where c.part_id = p.id and c.kind = 'cancel'
                       order by c.created_at desc limit 1),
           'change', (select jsonb_build_object('id', c.id, 'state', c.state, 'at', c.created_at, 'before', c.before, 'after', c.after)
                        from public.iva_order_changes c
                       where c.part_id = p.id and c.kind = 'edit'
                         and (c.state = 'pending' or c.decided_at > now() - interval '3 days')
                       order by c.created_at desc limit 1))
    from public.order_parts p
    left join lateral (select x.* from public.admins x
                        where lower(btrim(x.email)) = lower(btrim(p.owner_email)) order by x.active desc limit 1) ad on true
    left join public.iva_pickup_points pp on pp.id = p.point_id
    left join public.iva_rent_settings rs on rs.email = lower(btrim(coalesce(p.owner_email, '')))
   where p.id = p_part;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_part_card(bigint, boolean) FROM public, anon, authenticated;

-- прокату: изменения заявок, ждущие решения (для отметки в «Заявках» и уведомления)
CREATE OR REPLACE FUNCTION public.iva_order_changes_pending()
 RETURNS TABLE (id bigint, order_id bigint, part_id bigint, created_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select c.id, c.order_id, c.part_id, c.created_at
    from public.iva_order_changes c
    join public.order_parts p on p.id = c.part_id
   where c.kind = 'edit' and c.state = 'pending' and p.status = 'new'
     and coalesce(auth.jwt() ->> 'email', '') <> ''
     and ((public.iva_pickup_is_admin() and c.owner_email = lower(btrim(auth.jwt() ->> 'email')))
          or exists (select 1 from public.iva_staff s
                       join public.iva_staff_points sp on sp.staff_id = s.id and sp.point_id = p.point_id
                      where s.email = lower(btrim(auth.jwt() ->> 'email')) and s.owner_email = c.owner_email
                        and s.status = 'active' and s.can_issue))
   order by c.created_at desc
   limit 50;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_changes_pending() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_order_changes_pending() TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 6. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('право «Удаляет инструмент»: столбец, список, сохранение',
     EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'iva_staff' AND column_name = 'can_delete')
     AND has_function_privilege('authenticated', 'public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean, boolean)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean, boolean)', 'EXECUTE')
     AND to_regprocedure('public.iva_staff_save(uuid, text, text, text, uuid[], boolean, boolean, boolean, boolean, boolean)') IS NULL),
  ('удаление сотрудником — через одобрение (триггер, запросы, решение владельца)',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'zy_iva_staff_delete' AND NOT tgisinternal)
     AND to_regclass('public.iva_tool_deletes') IS NOT NULL
     AND NOT has_function_privilege('anon', 'public.iva_tool_delete_review(uuid, boolean)', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_staff_tool_delete(uuid)', 'EXECUTE')),
  ('правка целиком и перенос — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_tool_edit_card(uuid)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tool_edit_card(uuid)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tool_move(uuid, uuid)', 'EXECUTE')),
  ('чат: кто отвечает — таблица закрыта, функции только вошедшим',
     to_regclass('public.iva_chat_watch') IS NOT NULL
     AND NOT has_table_privilege('authenticated', 'public.iva_chat_watch', 'SELECT')
     AND NOT has_function_privilege('anon', 'public.iva_chat_who(text)', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_chat_who_am_i(text)', 'EXECUTE')),
  ('отмена и изменение заявки: журнал закрыт, функции только вошедшим, создателю — статистика',
     to_regclass('public.iva_order_changes') IS NOT NULL
     AND NOT has_table_privilege('authenticated', 'public.iva_order_changes', 'SELECT')
     AND has_function_privilege('authenticated', 'public.iva_order_cancel(bigint, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_order_cancel(bigint, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_cancel_stats()', 'EXECUTE')
     AND pg_get_functiondef('public.fn_fee_accrue()'::regprocedure) LIKE '%iva.part_cancel%')
) AS x(check_name, ok);

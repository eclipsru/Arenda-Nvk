-- ============================================================
--  Ива · 10.120 — страница проката и чат пункта выдачи (просьба владельца 18.10.2026).
--  Применять ПОСЛЕ 20261031_staff_chat.sql (чат сотрудников) и 20261030_staff_tools.sql (инструменты сотрудников).
--  Сайт не меняется. Без этого файла приложение 10.120 работает как 10.119 (штатная строка «продавец» в карточке).
--
--  Что делает:
--   • iva_prokat_points(пункт) — для страницы проката (видят все, и гости): все пункты выдачи проката, к которому
--     относится этот пункт, — название, адрес, город, график, телефон пункта, координаты; название и аватарка
--     проката; сколько инструментов в пункте видят клиенты. Почта пункта и данные сотрудников НЕ отдаются.
--     Только если в этом пункте есть хотя бы один инструмент (то есть пункт уже виден в карточке) и прокат включён;
--   • app_messages.point_id — переписка с пунктом выдачи («Спросить в чате» на странице проката). Пункт
--     сохраняется, только если он принадлежит прокату, которому (или от которого) сообщение; у сообщения по
--     инструменту или «Хочу сдавать» пункта нет. Удалили пункт — сообщения остаются, переписка станет общим чатом;
--   • iva_tag_my_point(ids, пункт) — штатная запись голоса не передаёт пункт: приложение помечает СВОИ голосовые
--     последних суток пунктом открытой переписки (как iva_tag_my_topic у «Хочу сдавать»);
--   • сотрудник с правом «Отвечает в чате», прикреплённый к ЭТОМУ пункту, видит переписки этого пункта и отвечает
--     от имени проката: iva_staff_point_chats() / iva_staff_point_chat / _read / _send (как чат по инструментам
--     10.106: клиент обозначен меткой, без почты, телефона и паспорта; первым написать нельзя). Сотрудник другого
--     пункта этого же проката переписку не видит;
--   • iva_tool_pending_card(инструмент) — владельцу: все поля инструмента, который ждёт одобрения (фото, название,
--     категория, вид, модель, цена, залог, описание, условия, доставка, пункт, кто добавил) — для «На одобрении».
--  Владелец видит переписки пунктов в своём «Чате» как раньше (сообщения ему). Выключатель «Чаты пунктов у меня»
--  в «Настройке проката» хранится на телефоне владельца и в базу не пишется.
--  Правила доступа app_messages не меняются: клиент пишет и читает только своё; сотрудник — только через функции.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (страница проката и чат пункта пропадут; сообщения остаются у клиентов и владельца, переписки пунктов
--  станут общим чатом):
--      DROP FUNCTION IF EXISTS public.iva_tool_pending_card(uuid);
--      DROP FUNCTION IF EXISTS public.iva_staff_point_chat_send(uuid, text, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_point_chat_read(uuid, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_point_chat(uuid, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_point_chats();
--      DROP FUNCTION IF EXISTS public.iva_staff_chat_points();
--      DROP FUNCTION IF EXISTS public.iva_tag_my_point(text[], uuid);
--      DROP TRIGGER IF EXISTS trg_iva_msg_point ON public.app_messages;
--      DROP FUNCTION IF EXISTS public.iva_msg_point_guard();
--      ALTER TABLE public.app_messages DROP COLUMN IF EXISTS point_id;
--      DROP FUNCTION IF EXISTS public.iva_prokat_points(uuid);
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_staff_chat_me()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261031_staff_chat.sql (SQL 10.106: чат сотрудников)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'tools' AND column_name = 'review') THEN
    RAISE EXCEPTION 'Сначала примените 20261030_staff_tools.sql (SQL 10.105: инструменты сотрудников)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'iva_pickup_points' AND column_name = 'tel') THEN
    RAISE EXCEPTION 'Сначала примените 20261025_pickup_tel.sql (SQL 10.99: телефон пункта)';
  END IF;
END $do$;

-- 1. Страница проката: пункты проката (по любому его пункту, где есть инструмент)
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

-- 2. Переписка с пунктом
ALTER TABLE public.app_messages ADD COLUMN IF NOT EXISTS point_id uuid;
DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.app_messages'::regclass AND conname = 'app_messages_point_fk') THEN
    ALTER TABLE public.app_messages ADD CONSTRAINT app_messages_point_fk
      FOREIGN KEY (point_id) REFERENCES public.iva_pickup_points(id) ON DELETE SET NULL;
  END IF;
END $do$;
CREATE INDEX IF NOT EXISTS idx_app_messages_point ON public.app_messages (point_id) WHERE point_id IS NOT NULL;
COMMENT ON COLUMN public.app_messages.point_id IS 'Ива 10.120: переписка с пунктом выдачи («Спросить в чате» на странице проката); иначе пусто.';

-- пункт — только прокату-собеседнику и только у обычного сообщения (не по инструменту, не «Хочу сдавать»)
CREATE OR REPLACE FUNCTION public.iva_msg_point_guard()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  o text;
begin
  if new.point_id is null then return new; end if;
  if new.tool_id is not null or coalesce(to_jsonb(new) ->> 'topic', '') <> '' then
    new.point_id := null;
    return new;
  end if;
  select p.owner_email into o from public.iva_pickup_points p where p.id = new.point_id;
  if o is null or (lower(btrim(coalesce(new.from_email, ''))) <> o and lower(btrim(coalesce(new.to_email, ''))) <> o) then
    new.point_id := null;
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_msg_point_guard() FROM public, anon, authenticated;

DROP TRIGGER IF EXISTS trg_iva_msg_point ON public.app_messages;
CREATE TRIGGER trg_iva_msg_point
  BEFORE INSERT OR UPDATE OF point_id, tool_id ON public.app_messages
  FOR EACH ROW EXECUTE FUNCTION public.iva_msg_point_guard();

-- 3. Свои голосовые — пунктом открытой переписки (только свои, за сутки, без инструмента/темы/пункта, прокату пункта)
CREATE OR REPLACE FUNCTION public.iva_tag_my_point(p_ids text[], p_point uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  n integer := 0;
  o text;
begin
  if p_point is null or coalesce(array_length(p_ids, 1), 0) = 0 or array_length(p_ids, 1) > 20 then return 0; end if;
  select p.owner_email into o from public.iva_pickup_points p where p.id = p_point;
  if o is null then return 0; end if;
  update public.app_messages m
     set point_id = p_point
   where m.id::text = any (p_ids)
     and lower(btrim(m.from_email)) = lower(btrim(coalesce(auth.jwt() ->> 'email', '')))
     and lower(btrim(m.to_email)) = o
     and m.point_id is null
     and m.tool_id is null
     and coalesce(to_jsonb(m) ->> 'topic', '') = ''
     and m.created_at > now() - interval '1 day';
  get diagnostics n = row_count;
  return n;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tag_my_point(text[], uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tag_my_point(text[], uuid) TO authenticated;

-- 4. Сотруднику: пункты, где он отвечает в чате (право «Отвечает в чате» + прикреплён к пункту). Только для функций ниже.
CREATE OR REPLACE FUNCTION public.iva_staff_chat_points()
 RETURNS TABLE (point_id uuid, point_name text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select pp.id, coalesce(pp.name, '')
    from public.iva_staff_chat_me() me
    join public.iva_staff_points sp on sp.staff_id = me.sid
    join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = me.owner;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat_points() FROM public, anon, authenticated;

-- 5. Сотруднику: переписки своих пунктов (последние 90 дней, где клиент писал)
CREATE OR REPLACE FUNCTION public.iva_staff_point_chats()
 RETURNS TABLE (point_id uuid, peer text, client_name text, point_name text,
                last_text text, last_at timestamptz, last_mine boolean, unread int, last_client_id text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  with me as (select * from public.iva_staff_chat_me()),
  pl as (select * from public.iva_staff_chat_points()),
  m as (
    select x.id::text as mid, x.point_id as pid, x.created_at as at, coalesce(x.is_read, false) as rd,
           lower(btrim(x.from_email)) = me.owner as mine,
           case when lower(btrim(x.from_email)) = me.owner then lower(btrim(x.to_email)) else lower(btrim(x.from_email)) end as pe,
           case when coalesce(x.audio, '') <> '' then '🎤 Голосовое сообщение'
                when coalesce(x.text, '') <> '' then x.text
                when coalesce(x.img, '') <> '' then '📷 Фото' else '' end as body,
           me.email as my
      from me
      join public.app_messages x on lower(btrim(x.from_email)) = me.owner or lower(btrim(x.to_email)) = me.owner
      join pl on pl.point_id = x.point_id
     where x.created_at > now() - interval '90 days' and x.tool_id is null
  ),
  mm as (select * from m where m.pe <> '' and m.pe <> m.my and m.pe <> (select owner from me)),
  g as (select mm.pid, mm.pe, max(mm.at) as last_at, count(*) filter (where not mm.mine and not mm.rd) as unread
          from mm group by mm.pid, mm.pe),
  l as (select distinct on (mm.pid, mm.pe) mm.pid, mm.pe, mm.body, mm.mine from mm order by mm.pid, mm.pe, mm.at desc, mm.mid desc),
  lc as (select distinct on (mm.pid, mm.pe) mm.pid, mm.pe, mm.mid from mm where not mm.mine order by mm.pid, mm.pe, mm.at desc, mm.mid desc)
  select g.pid, md5(g.pe),
         coalesce((select nullif(btrim(to_jsonb(cp) ->> 'given_name'), '')
                     from auth.users u join public.customer_profiles cp on cp.user_id::text = u.id::text
                    where lower(btrim(u.email)) = g.pe limit 1), 'Клиент'),
         pl.point_name, l.body, g.last_at, l.mine, g.unread::int, lc.mid
    from g
    join pl on pl.point_id = g.pid
    join l on l.pid = g.pid and l.pe = g.pe
    join lc on lc.pid = g.pid and lc.pe = g.pe                    -- только где клиент писал сам
   order by g.last_at desc
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_point_chats() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_point_chats() TO authenticated;

-- 6. Сотруднику: сообщения одной переписки пункта (по возрастанию времени)
CREATE OR REPLACE FUNCTION public.iva_staff_point_chat(p_point uuid, p_peer text)
 RETURNS TABLE (id text, mine boolean, by_me boolean, text text, img text, audio text, audio_dur int,
                created_at timestamptz, is_read boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select z.id, z.mine, z.by_me, z.text, z.img, z.audio, z.audio_dur, z.created_at, z.is_read from (
    select x.id::text as id, lower(btrim(x.from_email)) = me.owner as mine, coalesce(x.staff_id = me.sid, false) as by_me,
           coalesce(x.text, '') as text, coalesce(x.img, '') as img, coalesce(x.audio, '') as audio,
           coalesce(round(nullif(to_jsonb(x) ->> 'audio_dur', '')::numeric), 0)::int as audio_dur,
           x.created_at as created_at, coalesce(x.is_read, false) as is_read, x.id::text as sk
      from public.iva_staff_chat_me() me
      join public.app_messages x on x.point_id = p_point and x.tool_id is null
       and ((lower(btrim(x.from_email)) = me.owner and md5(lower(btrim(x.to_email))) = p_peer)
         or (lower(btrim(x.to_email)) = me.owner and md5(lower(btrim(x.from_email))) = p_peer))
     where exists (select 1 from public.iva_staff_chat_points() t where t.point_id = p_point)
       and md5(me.email) <> coalesce(p_peer, '')
     order by x.created_at desc, x.id desc
     limit 300) z
   order by z.created_at, z.sk;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_point_chat(uuid, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_point_chat(uuid, text) TO authenticated;

-- 7. Сотрудник открыл переписку пункта — сообщения клиента прочитаны (и у владельца тоже)
CREATE OR REPLACE FUNCTION public.iva_staff_point_chat_read(p_point uuid, p_peer text)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me record;
  n integer := 0;
begin
  select * into me from public.iva_staff_chat_me();
  if not found then raise exception 'Нет права «Отвечает в чате» — обратитесь к владельцу проката'; end if;
  if not exists (select 1 from public.iva_staff_chat_points() t where t.point_id = p_point) then
    raise exception 'Переписка не найдена в вашем пункте';
  end if;
  update public.app_messages x set is_read = true
   where x.point_id = p_point and x.tool_id is null and lower(btrim(x.to_email)) = me.owner
     and md5(lower(btrim(x.from_email))) = p_peer and lower(btrim(x.from_email)) <> me.email
     and not coalesce(x.is_read, false);
  get diagnostics n = row_count;
  return n;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_point_chat_read(uuid, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_point_chat_read(uuid, text) TO authenticated;

-- 8. Ответ пункта от имени проката (только в переписку, где клиент уже писал в этот пункт)
CREATE OR REPLACE FUNCTION public.iva_staff_point_chat_send(p_point uuid, p_peer text, p_text text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me record;
  cl text;
  t text := btrim(coalesce(p_text, ''));
  r text;
begin
  select * into me from public.iva_staff_chat_me();
  if not found then raise exception 'Нет права «Отвечает в чате» — обратитесь к владельцу проката'; end if;
  if t = '' then raise exception 'Напишите сообщение'; end if;
  if char_length(t) > 2000 then raise exception 'Сообщение длиннее 2000 знаков — разбейте на части'; end if;
  if not exists (select 1 from public.iva_staff_chat_points() x where x.point_id = p_point) then
    raise exception 'Переписка не найдена в вашем пункте';
  end if;
  select x.from_email into cl
    from public.app_messages x
   where x.point_id = p_point and x.tool_id is null and lower(btrim(x.to_email)) = me.owner
     and md5(lower(btrim(x.from_email))) = p_peer and lower(btrim(x.from_email)) <> me.email
   order by x.created_at desc
   limit 1;
  if cl is null then raise exception 'Переписка не найдена в вашем пункте'; end if;
  insert into public.app_messages(from_email, to_email, text, point_id, staff_id)
  values (me.owner, btrim(cl), t, p_point, me.sid)
  returning id::text into r;
  return r;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_point_chat_send(uuid, text, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_point_chat_send(uuid, text, text) TO authenticated;

-- 9. Владельцу: инструмент на одобрении целиком (все поля + пункт, кто добавил, модель)
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

NOTIFY pgrst, 'reload schema';

-- 10. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('страница проката — всем (и гостям)',
     has_function_privilege('anon', 'public.iva_prokat_points(uuid)', 'EXECUTE')),
  ('столбец app_messages.point_id и проверка пункта',
     EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'app_messages' AND column_name = 'point_id')
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.app_messages'::regclass AND tgname = 'trg_iva_msg_point')),
  ('чат пункта — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_staff_point_chats()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_point_chats()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_point_chat(uuid, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_point_chat_read(uuid, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_point_chat_send(uuid, text, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tag_my_point(text[], uuid)', 'EXECUTE')),
  ('служебные функции закрыты для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_staff_chat_points()', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_msg_point_guard()', 'EXECUTE')),
  ('карточка «На одобрении» — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_tool_pending_card(uuid)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tool_pending_card(uuid)', 'EXECUTE')),
  ('цензура чата на месте (или ещё не применялась)',
     to_regprocedure('public.iva_app_messages_censor()') IS NULL
     OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.app_messages'::regclass AND tgname = 'tr_app_messages_censor'))
) AS x(check_name, ok);

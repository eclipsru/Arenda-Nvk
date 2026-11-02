-- ============================================================
--  Ива · 10.106 — этап 3г «Сотрудники и пункты»: сотрудник с правом «Отвечает в чате» видит переписки клиентов
--  по инструментам своих пунктов и отвечает от имени проката (решения владельца 16–17.10.2026,
--  docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
--  Применять ПОСЛЕ 20261026_tool_points.sql (пункт у инструмента) и 20261028_staff.sql (сотрудники).
--
--  Что делает:
--   • app_messages.staff_id — кто из сотрудников написал ответ (номер сотрудника). У сообщений клиентов и владельца —
--     пусто. Клиенту сотрудник не виден: ответ приходит от проката (как от владельца), в той же переписке;
--   • iva_staff_chats() — сотруднику: переписки по инструментам своих пунктов (последние 90 дней, где клиент писал):
--     инструмент, пункт, имя клиента из профиля (без почты, телефона и паспорта), последнее сообщение, непрочитанные;
--     клиент обозначен меткой (md5 почты), сама почта сотруднику не отдаётся;
--   • iva_staff_chat(инструмент, метка) — сообщения одной переписки (последние 300);
--   • iva_staff_chat_read(инструмент, метка) — открыл переписку — сообщения клиента прочитаны (и у владельца тоже);
--   • iva_staff_chat_send(инструмент, метка, текст) — ответ от имени проката; только в переписку, где клиент уже писал;
--     мат не пропустит прежняя «Цензура» (триггер app_messages);
--   • общий чат клиента с владельцем (без инструмента) и «Хочу сдавать инструмент» сотрудник не видит.
--  Таблицы и правила доступа app_messages не меняются: сотрудник читает и пишет только через эти функции.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (сотрудники перестанут видеть чат пункта; все сообщения остаются у клиентов и владельца):
--      DROP FUNCTION IF EXISTS public.iva_staff_chat_send(uuid, text, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_chat_read(uuid, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_chat(uuid, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_chats();
--      DROP FUNCTION IF EXISTS public.iva_staff_chat_tools();
--      DROP FUNCTION IF EXISTS public.iva_staff_chat_me();
--      ALTER TABLE public.app_messages DROP COLUMN IF EXISTS staff_id;
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL OR to_regclass('public.iva_staff_points') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261028_staff.sql (SQL 10.103: сотрудники)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'tools' AND column_name = 'point_id') THEN
    RAISE EXCEPTION 'Сначала примените 20261026_tool_points.sql (SQL 10.101: пункт у инструмента)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'app_messages' AND column_name = 'tool_id') THEN
    RAISE EXCEPTION 'Сначала примените 20261012_chat_tools_avatars.sql (SQL 10.54: чат по инструментам)';
  END IF;
END $do$;

-- 1. Кто ответил
ALTER TABLE public.app_messages ADD COLUMN IF NOT EXISTS staff_id uuid;
DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.app_messages'::regclass AND conname = 'app_messages_staff_fk') THEN
    ALTER TABLE public.app_messages ADD CONSTRAINT app_messages_staff_fk
      FOREIGN KEY (staff_id) REFERENCES public.iva_staff(id) ON DELETE SET NULL;
  END IF;
END $do$;
COMMENT ON COLUMN public.app_messages.staff_id IS 'Ива 10.106: сотрудник, ответивший от имени проката (iva_staff.id); у остальных сообщений — пусто.';

-- 2. Вошедший — сотрудник с правом «Отвечает в чате» (принял приглашение, прокат включён). Только для функций ниже.
CREATE OR REPLACE FUNCTION public.iva_staff_chat_me()
 RETURNS TABLE (sid uuid, owner text, email text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select s.id, s.owner_email, s.email
    from public.iva_staff s
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and s.email = lower(btrim(auth.jwt() ->> 'email'))
     and s.status = 'active' and s.can_chat
     and exists (select 1 from public.admins a where lower(btrim(a.email)) = s.owner_email and a.active)
   limit 1;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat_me() FROM public, anon, authenticated;

-- Инструменты пунктов этого сотрудника (только его прокат). Только для функций ниже.
CREATE OR REPLACE FUNCTION public.iva_staff_chat_tools()
 RETURNS TABLE (tool_id uuid, tool_name text, tool_img text, point_name text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select distinct on (t.id) t.id, coalesce(t.name, ''), coalesce(to_jsonb(t) ->> 'img', ''), coalesce(pp.name, '')
    from public.iva_staff_chat_me() me
    join public.iva_staff_points sp on sp.staff_id = me.sid
    join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = me.owner
    join public.tools t on t.point_id = pp.id and lower(btrim(coalesce(t.owner_email, ''))) = me.owner
   order by t.id;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat_tools() FROM public, anon, authenticated;

-- 3. Сотруднику: переписки своих пунктов
CREATE OR REPLACE FUNCTION public.iva_staff_chats()
 RETURNS TABLE (tool_id uuid, peer text, client_name text, tool_name text, tool_img text, point_name text,
                last_text text, last_at timestamptz, last_mine boolean, unread int, last_client_id text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  with me as (select * from public.iva_staff_chat_me()),
  tl as (select * from public.iva_staff_chat_tools()),
  m as (
    select x.id::text as mid, x.tool_id as tid, x.created_at as at, coalesce(x.is_read, false) as rd,
           lower(btrim(x.from_email)) = me.owner as mine,
           case when lower(btrim(x.from_email)) = me.owner then lower(btrim(x.to_email)) else lower(btrim(x.from_email)) end as pe,
           case when coalesce(x.audio, '') <> '' then '🎤 Голосовое сообщение'
                when coalesce(x.text, '') <> '' then x.text
                when coalesce(x.img, '') <> '' then '📷 Фото' else '' end as body,
           me.email as my
      from me
      join public.app_messages x on lower(btrim(x.from_email)) = me.owner or lower(btrim(x.to_email)) = me.owner
      join tl on tl.tool_id = x.tool_id
     where x.created_at > now() - interval '90 days'
  ),
  mm as (select * from m where m.pe <> '' and m.pe <> m.my and m.pe <> (select owner from me)),
  g as (select mm.tid, mm.pe, max(mm.at) as last_at, count(*) filter (where not mm.mine and not mm.rd) as unread
          from mm group by mm.tid, mm.pe),
  l as (select distinct on (mm.tid, mm.pe) mm.tid, mm.pe, mm.body, mm.mine from mm order by mm.tid, mm.pe, mm.at desc, mm.mid desc),
  lc as (select distinct on (mm.tid, mm.pe) mm.tid, mm.pe, mm.mid from mm where not mm.mine order by mm.tid, mm.pe, mm.at desc, mm.mid desc)
  select g.tid, md5(g.pe),
         coalesce((select nullif(btrim(to_jsonb(cp) ->> 'given_name'), '')
                     from auth.users u join public.customer_profiles cp on cp.user_id::text = u.id::text
                    where lower(btrim(u.email)) = g.pe limit 1), 'Клиент'),
         tl.tool_name, tl.tool_img, tl.point_name, l.body, g.last_at, l.mine, g.unread::int, lc.mid
    from g
    join tl on tl.tool_id = g.tid
    join l on l.tid = g.tid and l.pe = g.pe
    join lc on lc.tid = g.tid and lc.pe = g.pe                    -- только где клиент писал сам
   order by g.last_at desc
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chats() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_chats() TO authenticated;

-- 4. Сотруднику: сообщения одной переписки (по возрастанию времени)
CREATE OR REPLACE FUNCTION public.iva_staff_chat(p_tool uuid, p_peer text)
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
      join public.app_messages x on x.tool_id = p_tool
       and ((lower(btrim(x.from_email)) = me.owner and md5(lower(btrim(x.to_email))) = p_peer)
         or (lower(btrim(x.to_email)) = me.owner and md5(lower(btrim(x.from_email))) = p_peer))
     where exists (select 1 from public.iva_staff_chat_tools() t where t.tool_id = p_tool)
       and md5(me.email) <> coalesce(p_peer, '')
     order by x.created_at desc, x.id desc
     limit 300) z
   order by z.created_at, z.sk;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat(uuid, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_chat(uuid, text) TO authenticated;

-- 5. Сотрудник открыл переписку — сообщения клиента прочитаны
CREATE OR REPLACE FUNCTION public.iva_staff_chat_read(p_tool uuid, p_peer text)
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
  if not exists (select 1 from public.iva_staff_chat_tools() t where t.tool_id = p_tool) then
    raise exception 'Переписка не найдена в вашем пункте';
  end if;
  update public.app_messages x set is_read = true
   where x.tool_id = p_tool and lower(btrim(x.to_email)) = me.owner
     and md5(lower(btrim(x.from_email))) = p_peer and lower(btrim(x.from_email)) <> me.email
     and not coalesce(x.is_read, false);
  get diagnostics n = row_count;
  return n;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat_read(uuid, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_chat_read(uuid, text) TO authenticated;

-- 6. Ответ от имени проката
CREATE OR REPLACE FUNCTION public.iva_staff_chat_send(p_tool uuid, p_peer text, p_text text)
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
  if not exists (select 1 from public.iva_staff_chat_tools() x where x.tool_id = p_tool) then
    raise exception 'Переписка не найдена в вашем пункте';
  end if;
  select x.from_email into cl
    from public.app_messages x
   where x.tool_id = p_tool and lower(btrim(x.to_email)) = me.owner
     and md5(lower(btrim(x.from_email))) = p_peer and lower(btrim(x.from_email)) <> me.email
   order by x.created_at desc
   limit 1;
  if cl is null then raise exception 'Переписка не найдена в вашем пункте'; end if;
  insert into public.app_messages(from_email, to_email, text, tool_id, staff_id)
  values (me.owner, btrim(cl), t, p_tool, me.sid)
  returning id::text into r;
  return r;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat_send(uuid, text, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_chat_send(uuid, text, text) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 7. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('столбец app_messages.staff_id',
     EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'app_messages' AND column_name = 'staff_id')),
  ('функции чата пункта — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_staff_chats()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_chats()', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_staff_chat(uuid, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_chat(uuid, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_chat_read(uuid, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_chat_send(uuid, text, text)', 'EXECUTE')),
  ('служебные функции закрыты для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_staff_chat_me()', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_staff_chat_tools()', 'EXECUTE')),
  ('цензура чата на месте (или ещё не применялась)',
     to_regprocedure('public.iva_app_messages_censor()') IS NULL
     OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.app_messages'::regclass AND tgname = 'tr_app_messages_censor'))
) AS x(check_name, ok);

-- ============================================================
--  Ива 10.132 — «стрелочки оповещений появлялись сразу без каких-либо действий» (просьба владельца 04.11.2026).
--
--  Что делает:
--   • iva_msg_ping — по строке на почту: «когда последний раз поменялись сообщения этого человека».
--     В строке только почта и время. Ни текста, ни имён. Читать может только сам человек (своя почта),
--     писать — только база (триггер).
--   • Отметку ставит триггер на app_messages: новое сообщение и «прочитано» (is_read).
--     Кому: получатель (to_email) и сотрудники пункта с правом «Отвечает в чате» — пункта инструмента
--     или пункта чата (point_id), если сообщение владельцу проката.
--   • Таблица добавляется в Supabase Realtime (публикация supabase_realtime). Приложение 10.132 держит одно
--     соединение и, как только его строка поменялась, сразу пересчитывает стрелки и уведомления.
--     Заявки — так же, по отметке iva_order_ping из SQL 20261119 (его тоже нужно применить).
--   • Ошибка в отметке никогда не мешает сообщению: всё внутри «exception when others».
--
--  Без этого SQL стрелки обновляются как раньше — раз в 30 секунд и при пуш-уведомлении.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261122_msg_ping_rollback.sql (сообщения не меняются).
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.admins') IS NULL OR to_regclass('public.app_messages') IS NULL THEN
    RAISE EXCEPTION 'В базе нет admins / app_messages — это не база «Ивы»';
  END IF;
END $do$;

-- 1. Отметка «в сообщениях что-то поменялось»
CREATE TABLE IF NOT EXISTS public.iva_msg_ping (
  email      text        PRIMARY KEY CHECK (email = lower(btrim(email)) AND email <> ''),
  changed_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.iva_msg_ping IS 'Ива 10.132: когда поменялись сообщения человека (новое / прочитано). Пишет только база, читает только сам человек — для Supabase Realtime';
ALTER TABLE public.iva_msg_ping ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_msg_ping FROM public, anon, authenticated;
GRANT SELECT ON public.iva_msg_ping TO authenticated;
DROP POLICY IF EXISTS iva_msg_ping_own ON public.iva_msg_ping;
CREATE POLICY iva_msg_ping_own ON public.iva_msg_ping FOR SELECT TO authenticated
  USING (email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))));

CREATE OR REPLACE FUNCTION public.iva_msg_ping_touch(p_email text)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  insert into public.iva_msg_ping (email, changed_at)
  select lower(btrim(p_email)), clock_timestamp()
   where nullif(lower(btrim(coalesce(p_email, ''))), '') is not null
  on conflict (email) do update set changed_at = excluded.changed_at;
$function$;
REVOKE ALL ON FUNCTION public.iva_msg_ping_touch(text) FROM public, anon, authenticated;

-- 2. app_messages: новое сообщение и «прочитано»
CREATE OR REPLACE FUNCTION public.iva_msg_ping_row()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  n   jsonb := to_jsonb(new);
  em  text;
  tos text := lower(btrim(coalesce(n ->> 'to_email', '')));
  frm text := lower(btrim(coalesce(n ->> 'from_email', '')));
  pt  text := nullif(n ->> 'point_id', '');
  tid text := nullif(n ->> 'tool_id', '');
begin
  if tg_op = 'UPDATE' and (to_jsonb(old) -> 'is_read') is not distinct from (n -> 'is_read') then return null; end if;
  begin
    perform public.iva_msg_ping_touch(tos);
  exception when others then
    raise notice 'iva_msg_ping_row: отметка получателю не поставлена: %', sqlerrm;
  end;
  begin
    -- сотрудники пункта с правом чата: сообщение владельцу (или от владельца) по инструменту пункта / в чат пункта
    if (pt is not null or tid is not null)
       and to_regclass('public.iva_staff') is not null and to_regclass('public.iva_staff_points') is not null then
      for em in execute
        $q$select distinct s.email
             from public.iva_staff s join public.iva_staff_points sp on sp.staff_id = s.id
            where s.owner_email in ($1, $2) and s.status = 'active'
              and coalesce((to_jsonb(s) ->> 'can_chat')::boolean, false)
              and (sp.point_id::text = $3
                   or ($4 is not null and exists (select 1 from public.tools t
                                                   where t.id::text = $4 and (to_jsonb(t) ->> 'point_id') = sp.point_id::text)))$q$
        using tos, frm, coalesce(pt, ''), tid
      loop
        perform public.iva_msg_ping_touch(em);
      end loop;
    end if;
  exception when others then
    raise notice 'iva_msg_ping_row: отметка сотрудникам не поставлена: %', sqlerrm;
  end;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_msg_ping_row() FROM public, anon, authenticated;

DO $do$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = 'app_messages' AND column_name = 'is_read') THEN
    EXECUTE 'DROP TRIGGER IF EXISTS trg_iva_msg_ping ON public.app_messages';
    EXECUTE 'CREATE TRIGGER trg_iva_msg_ping AFTER INSERT OR UPDATE OF is_read ON public.app_messages
               FOR EACH ROW EXECUTE FUNCTION public.iva_msg_ping_row()';
  ELSE
    EXECUTE 'DROP TRIGGER IF EXISTS trg_iva_msg_ping ON public.app_messages';
    EXECUTE 'CREATE TRIGGER trg_iva_msg_ping AFTER INSERT ON public.app_messages
               FOR EACH ROW EXECUTE FUNCTION public.iva_msg_ping_row()';
  END IF;
END $do$;

-- 3. Supabase Realtime: приложение подписывается на свою строку
DO $do$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND NOT puballtables)
     AND NOT EXISTS (SELECT 1 FROM pg_publication_tables
                      WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_msg_ping') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.iva_msg_ping;
  END IF;
END $do$;

NOTIFY pgrst, 'reload schema';

-- 4. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('отметка «сообщения поменялись» — читает только сам человек',
     to_regclass('public.iva_msg_ping') IS NOT NULL
     AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_msg_ping'::regclass)
     AND EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_msg_ping' AND policyname = 'iva_msg_ping_own')
     AND has_table_privilege('authenticated', 'public.iva_msg_ping', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_msg_ping', 'INSERT')
     AND NOT has_table_privilege('authenticated', 'public.iva_msg_ping', 'UPDATE')
     AND NOT has_table_privilege('anon', 'public.iva_msg_ping', 'SELECT')),
  ('триггер на сообщениях',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.app_messages'::regclass AND tgname = 'trg_iva_msg_ping')),
  ('Supabase Realtime включён для отметки сообщений',
     EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_msg_ping')
     OR EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND puballtables)),
  ('отметка заявок (SQL 20261119) — для мгновенных стрелок заявок',
     to_regclass('public.iva_order_ping') IS NOT NULL),
  ('служебные функции закрыты для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_msg_ping_touch(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_msg_ping_touch(text)', 'EXECUTE'))
) AS x(check_name, ok);

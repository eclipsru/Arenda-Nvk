-- ============================================================
--  ОТКАТ 20261122_msg_ping.sql — стрелки сообщений снова обновляются раз в 30 секунд.
--  Сообщения не меняются. Supabase → SQL Editor → вставить весь файл → Run.
-- ============================================================
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_msg_ping') THEN ALTER PUBLICATION supabase_realtime DROP TABLE public.iva_msg_ping; END IF; END $$;
DROP TRIGGER IF EXISTS trg_iva_msg_ping ON public.app_messages;
DROP FUNCTION IF EXISTS public.iva_msg_ping_row();
DROP FUNCTION IF EXISTS public.iva_msg_ping_touch(text);
DROP TABLE IF EXISTS public.iva_msg_ping;
NOTIFY pgrst, 'reload schema';
SELECT CASE WHEN to_regclass('public.iva_msg_ping') IS NULL THEN '✅ откат выполнен' ELSE '❌ таблица осталась' END AS result;

-- ============================================================
--  ОТКАТ 20261121_chat_names.sql — в «Чате» у проката снова имена только из заявок, остальные — почтой.
--  Сообщения и профили не меняются. Supabase → SQL Editor → вставить весь файл → Run.
-- ============================================================
DROP FUNCTION IF EXISTS public.iva_chat_peer_names();
NOTIFY pgrst, 'reload schema';
SELECT CASE WHEN to_regprocedure('public.iva_chat_peer_names()') IS NULL THEN '✅ откат выполнен' ELSE '❌ функция осталась' END AS result;

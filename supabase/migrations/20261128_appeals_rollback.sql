-- ============================================================
--  ОТКАТ 20261128_appeals.sql (Ива, 07.11.2026): «Обращения», нажатия «Позвонить», счётчик скачиваний.
--
--  Удаляет функции iva_appeals, iva_call_tap, iva_download_hit, iva_download_count, iva_download_bucket
--  и таблицы iva_call_taps, iva_app_downloads (вместе с накопленными числами — их не вернуть).
--  Заявки, сообщения, инструменты и пункты не трогает.
--  После отката: на странице приложения счётчика нет, в приложении «Обращения» пишет, какой SQL нужен.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
-- ============================================================

DROP FUNCTION IF EXISTS public.iva_appeals(uuid);
DROP FUNCTION IF EXISTS public.iva_call_tap(uuid, uuid, text, text);
DROP FUNCTION IF EXISTS public.iva_download_hit(text);
DROP FUNCTION IF EXISTS public.iva_download_count();
DROP FUNCTION IF EXISTS public.iva_download_bucket(bigint);
DROP TABLE IF EXISTS public.iva_call_taps;
DROP TABLE IF EXISTS public.iva_app_downloads;

NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('функций «Обращений» и счётчика больше нет',
     to_regprocedure('public.iva_appeals(uuid)') IS NULL AND to_regprocedure('public.iva_download_count()') IS NULL
     AND to_regprocedure('public.iva_call_tap(uuid, uuid, text, text)') IS NULL),
  ('таблиц нажатий и скачиваний больше нет',
     to_regclass('public.iva_call_taps') IS NULL AND to_regclass('public.iva_app_downloads') IS NULL)
) AS x(check_name, ok);

-- ============================================================
--  ОТКАТ 20261120_app_reviews.sql — отзывы о приложении на app.html пропадут (страница покажет «Отзывов пока нет»).
--  Внимание: удаляет сами отзывы и голоса «полезно». Supabase → SQL Editor → вставить весь файл → Run.
-- ============================================================
DROP FUNCTION IF EXISTS public.iva_app_review_status(bigint, text);
DROP FUNCTION IF EXISTS public.iva_app_review_reply(bigint, text);
DROP FUNCTION IF EXISTS public.iva_app_review_vote(bigint, boolean);
DROP FUNCTION IF EXISTS public.iva_app_review_delete();
DROP FUNCTION IF EXISTS public.iva_app_review_save(int, text, text);
DROP FUNCTION IF EXISTS public.iva_app_reviews_list(text, int, int, int);
DROP FUNCTION IF EXISTS public.iva_app_reviews_stats();
DROP FUNCTION IF EXISTS public.iva_app_review_me();
DROP TABLE IF EXISTS public.iva_app_review_votes;
DROP TABLE IF EXISTS public.iva_app_reviews;
NOTIFY pgrst, 'reload schema';
SELECT CASE WHEN to_regclass('public.iva_app_reviews') IS NULL THEN '✅ откат выполнен' ELSE '❌ таблица осталась' END AS result;

-- ============================================================
--  ОТКАТ: «Статистика» сотрудника (20261125_staff_stats.sql).
--  Удаляет только функцию iva_staff_stats. Таблицы и данные не трогает.
--  Сайт после отката считает статистику по уже видным сотруднику заявкам (за 7 дней) и пишет, что нужен SQL.
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
-- ============================================================

DROP FUNCTION IF EXISTS public.iva_staff_stats(uuid, int);
NOTIFY pgrst, 'reload schema';

SELECT 'статистика сотрудника удалена' AS check_name,
       CASE WHEN to_regprocedure('public.iva_staff_stats(uuid, integer)') IS NULL THEN '✅' ELSE '❌' END AS result;

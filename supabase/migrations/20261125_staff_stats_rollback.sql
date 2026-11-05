-- ============================================================
--  ОТКАТ: «Статистика» и чаты справа в «Работе» сотрудника (20261125_staff_stats.sql).
--  Удаляет только функции iva_staff_stats и iva_staff_chat_unread. Таблицы и данные не трогает.
--  Сайт после отката считает статистику по уже видным сотруднику заявкам (за 7 дней) и пишет, что нужен SQL;
--  красный в чатах — по времени последнего сообщения.
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
-- ============================================================

DROP FUNCTION IF EXISTS public.iva_staff_stats(uuid, int);
DROP FUNCTION IF EXISTS public.iva_staff_chat_unread();
NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('статистика сотрудника удалена', to_regprocedure('public.iva_staff_stats(uuid, integer)') IS NULL),
  ('«не прочитано с …» для чатов удалено', to_regprocedure('public.iva_staff_chat_unread()') IS NULL)
) AS x(check_name, ok);

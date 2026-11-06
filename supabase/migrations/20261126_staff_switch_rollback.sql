-- ============================================================
--  ОТКАТ: быстрая смена аккаунта в «Работе» (20261126_staff_switch.sql).
--  Удаляет только функцию iva_switch_info. Таблицы и данные не трогает.
--  Сайт после отката работает как раньше: кнопки «Сменить» и кружка «Владелец» нет.
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
-- ============================================================

DROP FUNCTION IF EXISTS public.iva_switch_info();
NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('смена аккаунта удалена', to_regprocedure('public.iva_switch_info()') IS NULL)
) AS x(check_name, ok);

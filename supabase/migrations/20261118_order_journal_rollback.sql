-- ============================================================
--  Ива · откат 20261118_order_journal.sql — «кто выдал / кто принял» на карточках заявок.
--  Удаляет только новую функцию. Заявки, статусы и записи «кто выдал / кто принял» (10.107) остаются;
--  сайт и приложение без функции просто не показывают эти строки.
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run.
-- ============================================================
DROP FUNCTION IF EXISTS public.iva_parts_journal(bigint[]);
NOTIFY pgrst, 'reload schema';
SELECT CASE WHEN to_regprocedure('public.iva_parts_journal(bigint[])') IS NULL THEN '✅ откат выполнен' ELSE '❌ функция осталась' END AS result;

-- ============================================================
--  Ива · ОТКАТ 10.123 (20261114_v123.sql). Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
--  Выключает деление части заявки по срокам при «Выдал». Уже разделённые части остаются отдельными (это обычные
--  части заявки: своя «Вернули», своя комиссия) — ничего не удаляется.
-- ============================================================

DROP TRIGGER IF EXISTS trg_zzz_iva_part_split ON public.order_parts;
DROP FUNCTION IF EXISTS public.iva_part_split_trg();
DROP FUNCTION IF EXISTS public.iva_item_days(jsonb, integer);

NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('деление по срокам выключено',
     NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_zzz_iva_part_split' AND NOT tgisinternal)
     AND to_regprocedure('public.iva_part_split_trg()') IS NULL)
) AS x(check_name, ok);

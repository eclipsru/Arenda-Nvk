-- ============================================================
--  ОТКАТ 20261119_order_ping.sql — сайт снова обновляет заявки раз в 30 секунд.
--  Заявки, сотрудники и права не меняются. Supabase → SQL Editor → вставить весь файл → Run.
-- ============================================================
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_order_ping') THEN ALTER PUBLICATION supabase_realtime DROP TABLE public.iva_order_ping; END IF; END $$;
DROP TRIGGER IF EXISTS trg_iva_order_ping ON public.order_parts;
DO $$ BEGIN IF to_regclass('public.iva_order_changes') IS NOT NULL THEN DROP TRIGGER IF EXISTS trg_iva_order_ping ON public.iva_order_changes; END IF; END $$;
DROP FUNCTION IF EXISTS public.iva_order_ping_parts();
DROP FUNCTION IF EXISTS public.iva_order_ping_changes();
DROP FUNCTION IF EXISTS public.iva_order_ping_part(bigint, text, uuid, bigint, boolean);
DROP FUNCTION IF EXISTS public.iva_order_ping_touch(text, text, bigint);
DROP TABLE IF EXISTS public.iva_order_ping;
NOTIFY pgrst, 'reload schema';
SELECT CASE WHEN to_regclass('public.iva_order_ping') IS NULL THEN '✅ откат выполнен' ELSE '❌ таблица осталась' END AS result;

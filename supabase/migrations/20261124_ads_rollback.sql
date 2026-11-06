-- ============================================================
--  ОТКАТ Ива 10.136 «Рекламный кабинет» (20261124_ads.sql).
--  Удаляет рекламы, счётчики, отметку, функции и правила хранилища iva-ads.
--  Картинки в Storage → iva-ads остаются (удалить вручную вместе с bucket, если не нужны).
--  Приложение 10.136 после отката просто не показывает рекламу, кабинет пишет «нужен SQL».
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → вставить ВЕСЬ файл → Run.
-- ============================================================

DO $do$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_ads_ping') THEN
    ALTER PUBLICATION supabase_realtime DROP TABLE public.iva_ads_ping;
  END IF;
END $do$;

DROP FUNCTION IF EXISTS public.iva_ads_live();
DROP FUNCTION IF EXISTS public.iva_ad_hit(uuid, text);
DROP FUNCTION IF EXISTS public.iva_ads_list();
DROP FUNCTION IF EXISTS public.iva_ad_save(jsonb);
DROP FUNCTION IF EXISTS public.iva_ad_set_active(uuid, boolean);
DROP FUNCTION IF EXISTS public.iva_ad_delete(uuid);
DROP TRIGGER IF EXISTS trg_iva_ads_ping ON public.iva_ads;
DROP FUNCTION IF EXISTS public.iva_ads_touch();
DROP TABLE IF EXISTS public.iva_ad_stats;
DROP TABLE IF EXISTS public.iva_ads_ping;
DROP TABLE IF EXISTS public.iva_ads;

DO $do$
BEGIN
  IF to_regclass('storage.objects') IS NULL THEN RETURN; END IF;
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_ins ON storage.objects';
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_upd ON storage.objects';
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_del ON storage.objects';
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_sel ON storage.objects';
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'Правила хранилища не удалены (%): удалите вручную в Storage → Policies', SQLERRM;
END $do$;

NOTIFY pgrst, 'reload schema';

SELECT CASE WHEN to_regclass('public.iva_ads') IS NULL AND to_regprocedure('public.iva_ads_live()') IS NULL
            THEN '✅ откат выполнен' ELSE '❌ что-то осталось' END AS result;

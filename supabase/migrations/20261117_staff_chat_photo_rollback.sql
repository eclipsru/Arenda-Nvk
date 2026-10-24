-- ============================================================
--  Ива · ОТКАТ файла 20261117_staff_chat_photo.sql (фото в ответе сотрудника на сайте).
--  Убирает функции ответа с фото и правило хранилища. Ответ текстом (функции с тремя параметрами) не трогается.
--  Уже отправленные фото остаются в переписке. Сайт после отката на фото покажет «нужно обновление базы».
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
-- ============================================================

DROP FUNCTION IF EXISTS public.iva_staff_chat_send(uuid, text, text, text);
DROP FUNCTION IF EXISTS public.iva_staff_point_chat_send(uuid, text, text, text);
DO $do$
BEGIN
  IF to_regclass('storage.objects') IS NOT NULL THEN
    EXECUTE 'DROP POLICY IF EXISTS iva_staff_chat_photos_ins ON storage.objects';
  END IF;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'Правило хранилища не удалено (%)', SQLERRM;
END $do$;
DROP FUNCTION IF EXISTS public.iva_chat_img_ok(text);
DROP FUNCTION IF EXISTS public.iva_staff_chatter();

NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('ответа с фото больше нет',
     to_regprocedure('public.iva_staff_chat_send(uuid, text, text, text)') IS NULL
     AND to_regprocedure('public.iva_staff_point_chat_send(uuid, text, text, text)') IS NULL),
  ('ответ текстом на месте',
     to_regprocedure('public.iva_staff_chat_send(uuid, text, text)') IS NOT NULL
     AND to_regprocedure('public.iva_staff_point_chat_send(uuid, text, text)') IS NOT NULL)
) AS x(check_name, ok);

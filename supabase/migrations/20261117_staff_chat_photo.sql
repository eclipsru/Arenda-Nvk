-- ============================================================
--  Ива · сайт (файл 20261117) — фото в ответе сотрудника клиенту (раздел «Сообщения» на «Работе» сайта,
--  просьба владельца 24.10.2026 «всё по порядку»). Применять ПОСЛЕ 20261031_staff_chat.sql и 20261111_prokat_page.sql.
--  Приложение не меняется: оно и дальше отправляет от сотрудника только текст и показывает фото как обычно.
--
--  Что делает:
--   • iva_staff_chatter() — «я сотрудник с правом „Отвечает в чате“» (для правила хранилища);
--   • правило хранилища: такой сотрудник может загрузить фото в tool-photos/tools/ (как клиент и владелец);
--   • iva_staff_chat_send(p_tool, p_peer, p_text, p_img) и iva_staff_point_chat_send(p_point, p_peer, p_text, p_img) —
--     ответ с фото (текст можно не писать). Прежние функции с тремя параметрами не меняются.
--   Проверки те же, что у текста: право «Отвечает в чате», только свой пункт, только в переписку, где клиент уже писал.
--   Фото — только ссылка на хранилище tool-photos этого проекта.
--
--  Без этого файла сайт отправляет текст как раньше, а на фото покажет «нужно обновление базы».
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ: Supabase → SQL Editor → вставить ВЕСЬ файл 20261117_staff_chat_photo_rollback.sql → Run
--  (уже отправленные фото остаются в переписке).
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_staff_chat_send(uuid, text, text)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261031_staff_chat.sql (чат сотрудника)';
  END IF;
  IF to_regprocedure('public.iva_staff_point_chat_send(uuid, text, text)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261111_prokat_page.sql (чат пункта)';
  END IF;
END $do$;

-- 1. «Я сотрудник с правом „Отвечает в чате“» — для правила хранилища
CREATE OR REPLACE FUNCTION public.iva_staff_chatter()
 RETURNS boolean
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select exists (select 1 from public.iva_staff_chat_me());
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chatter() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_chatter() TO authenticated;

-- 2. Фото сотрудника в чат: папка tools/ хранилища tool-photos (там же фото клиентов и инструментов)
DO $do$
BEGIN
  IF to_regclass('storage.objects') IS NULL THEN
    RAISE NOTICE 'Хранилища нет — правило для фото не нужно';
    RETURN;
  END IF;
  EXECUTE 'DROP POLICY IF EXISTS iva_staff_chat_photos_ins ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_staff_chat_photos_ins ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_chatter())$p$;
EXCEPTION WHEN insufficient_privilege OR undefined_function THEN
  RAISE NOTICE 'Правило хранилища не создано (%): фото загрузится, только если пускают прежние правила', SQLERRM;
END $do$;

-- 3. Ссылка на фото — только из хранилища tool-photos (папка tools/)
CREATE OR REPLACE FUNCTION public.iva_chat_img_ok(p_img text)
 RETURNS boolean
 LANGUAGE sql
 IMMUTABLE
AS $function$
  select coalesce(p_img, '') ~ '^https://[a-z0-9.-]+/storage/v1/object/public/tool-photos/tools/[A-Za-z0-9._-]+$';
$function$;
REVOKE ALL ON FUNCTION public.iva_chat_img_ok(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_chat_img_ok(text) TO authenticated;

-- 4. Ответ по инструменту — с фото
CREATE OR REPLACE FUNCTION public.iva_staff_chat_send(p_tool uuid, p_peer text, p_text text, p_img text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me record;
  cl text;
  t text := btrim(coalesce(p_text, ''));
  im text := btrim(coalesce(p_img, ''));
  r text;
begin
  select * into me from public.iva_staff_chat_me();
  if not found then raise exception 'Нет права «Отвечает в чате» — обратитесь к владельцу проката'; end if;
  if im <> '' and not public.iva_chat_img_ok(im) then raise exception 'Фото не загрузилось — попробуйте ещё раз'; end if;
  if t = '' and im = '' then raise exception 'Напишите сообщение или приложите фото'; end if;
  if char_length(t) > 2000 then raise exception 'Сообщение длиннее 2000 знаков — разбейте на части'; end if;
  if not exists (select 1 from public.iva_staff_chat_tools() x where x.tool_id = p_tool) then
    raise exception 'Переписка не найдена в вашем пункте';
  end if;
  select x.from_email into cl
    from public.app_messages x
   where x.tool_id = p_tool and lower(btrim(x.to_email)) = me.owner
     and md5(lower(btrim(x.from_email))) = p_peer and lower(btrim(x.from_email)) <> me.email
   order by x.created_at desc
   limit 1;
  if cl is null then raise exception 'Переписка не найдена в вашем пункте'; end if;
  insert into public.app_messages(from_email, to_email, text, img, tool_id, staff_id)
  values (me.owner, btrim(cl), t, nullif(im, ''), p_tool, me.sid)
  returning id::text into r;
  return r;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_chat_send(uuid, text, text, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_chat_send(uuid, text, text, text) TO authenticated;

-- 5. Ответ в чат пункта — с фото
CREATE OR REPLACE FUNCTION public.iva_staff_point_chat_send(p_point uuid, p_peer text, p_text text, p_img text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me record;
  cl text;
  t text := btrim(coalesce(p_text, ''));
  im text := btrim(coalesce(p_img, ''));
  r text;
begin
  select * into me from public.iva_staff_chat_me();
  if not found then raise exception 'Нет права «Отвечает в чате» — обратитесь к владельцу проката'; end if;
  if im <> '' and not public.iva_chat_img_ok(im) then raise exception 'Фото не загрузилось — попробуйте ещё раз'; end if;
  if t = '' and im = '' then raise exception 'Напишите сообщение или приложите фото'; end if;
  if char_length(t) > 2000 then raise exception 'Сообщение длиннее 2000 знаков — разбейте на части'; end if;
  if not exists (select 1 from public.iva_staff_chat_points() x where x.point_id = p_point) then
    raise exception 'Переписка не найдена в вашем пункте';
  end if;
  select x.from_email into cl
    from public.app_messages x
   where x.point_id = p_point and x.tool_id is null and lower(btrim(x.to_email)) = me.owner
     and md5(lower(btrim(x.from_email))) = p_peer and lower(btrim(x.from_email)) <> me.email
   order by x.created_at desc
   limit 1;
  if cl is null then raise exception 'Переписка не найдена в вашем пункте'; end if;
  insert into public.app_messages(from_email, to_email, text, img, point_id, staff_id)
  values (me.owner, btrim(cl), t, nullif(im, ''), p_point, me.sid)
  returning id::text into r;
  return r;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_point_chat_send(uuid, text, text, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_point_chat_send(uuid, text, text, text) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 6. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('ответ с фото по инструменту — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_staff_chat_send(uuid, text, text, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_chat_send(uuid, text, text, text)', 'EXECUTE')),
  ('ответ с фото в чат пункта — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_staff_point_chat_send(uuid, text, text, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_point_chat_send(uuid, text, text, text)', 'EXECUTE')),
  ('прежние ответы текстом на месте',
     to_regprocedure('public.iva_staff_chat_send(uuid, text, text)') IS NOT NULL
     AND to_regprocedure('public.iva_staff_point_chat_send(uuid, text, text)') IS NOT NULL),
  ('ссылка на фото проверяется',
     public.iva_chat_img_ok('https://x.supabase.co/storage/v1/object/public/tool-photos/tools/1_2.jpg')
     AND NOT public.iva_chat_img_ok('https://evil.example/a.jpg')
     AND NOT public.iva_chat_img_ok('javascript:alert(1)')),
  ('правило хранилища для фото сотрудника',
     to_regclass('storage.objects') IS NULL
     OR EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'storage' AND tablename = 'objects' AND policyname = 'iva_staff_chat_photos_ins'))
) AS x(check_name, ok);

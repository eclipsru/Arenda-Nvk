-- ============================================================
--  Ива · 10.116 — «Не опубликовано: нет прав на публикацию» у сотрудника (сообщение владельца 17.10.2026):
--  причина словами и «На одобрении» у владельца сразу, с уведомлением.
--  Применять ПОСЛЕ 20261028_staff.sql (сотрудники). Остальные файлы сотрудников (20261029–20261106) этот файл
--  не требует: он сам проверяет, что из них есть в базе, и пишет это в таблице внизу.
--
--  Что делает:
--   • iva_staff_publish_check(почта) — почему сотрудник не может выложить инструмент: нет обновления базы,
--     приглашение не принято, нет права «Выкладывает инструмент», прокат владельца выключен, нет пункта выдачи,
--     нет правила для фото в хранилище. Только для базы (SQL Editor) — приложениям закрыта.
--   • iva_staff_publish_why() — то же для вошедшего сотрудника: приложение показывает причину после отказа
--     (только про себя; чужие почты, имена и телефоны не отдаются).
--   • правила хранилища для фото сотрудника (папка tools/ в tool-photos) создаются заново — как в 20261030
--     (если тогда хранилище их не создало, был только NOTICE).
--   • инструмент сотрудника встал «на одобрение» (и когда его одобрили / отклонили) — владельцу отметка
--     iva_role_ping (Realtime, 10.112: приложение открыто — сразу) и push-сигнал «o» (10.74: приложение закрыто).
--     Если 20261103 или 20261014 в базе нет — просто без отметки / без push; публикацию это не ломает.
--   • внизу — одна таблица: какие обновления есть, правило фото, отметка «на одобрение» и по каждому сотруднику —
--     может ли он выкладывать инструмент, а если нет — почему.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — таблица проверки: все строки «✅» (строки «⚠️» — сведения, присылать снимок).
--
--  ОТКАТ (причина словами и отметка «на одобрение» пропадут; правила фото остаются — они из 20261030):
--      DROP TRIGGER IF EXISTS trg_iva_tool_review_ping ON public.tools;
--      DROP FUNCTION IF EXISTS public.iva_tool_review_ping();
--      DROP FUNCTION IF EXISTS public.iva_staff_publish_why();
--      DROP FUNCTION IF EXISTS public.iva_staff_publish_check(text);
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL OR to_regclass('public.iva_staff_points') IS NULL
     OR to_regclass('public.iva_pickup_points') IS NULL OR to_regclass('public.admins') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261028_staff.sql (SQL 10.103: сотрудники)';
  END IF;
END $do$;

-- 1. Почему сотрудник не может выложить инструмент (код + фраза для человека)
CREATE OR REPLACE FUNCTION public.iva_staff_publish_check(p_email text)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(p_email, '')));
  sid uuid;
  st text;
  cat boolean;
  own text;
  n int;
  db_ok boolean;
begin
  if em = '' then
    return jsonb_build_object('code', 'no_login', 'text', 'Войдите в аккаунт заново — база не видит, кто публикует.');
  end if;
  if exists (select 1 from public.admins a
              where coalesce(a.active, false) and a.role in ('admin', 'chief') and lower(btrim(a.email)) = em) then
    return jsonb_build_object('code', 'admin', 'text',
      'Эта почта — администратор: инструмент публикуется как ваш собственный, права сотрудника тут ни при чём.');
  end if;
  -- обновления базы для сотрудников: права (20261029), запись инструмента сотрудника (20261030)
  db_ok := to_regprocedure('public.iva_staff_right(uuid, text)') is not null
           and to_regprocedure('public.iva_tools_staff_before()') is not null
           and exists (select 1 from pg_trigger where tgrelid = 'public.tools'::regclass and tgname = 'trg_iva_staff_tool' and not tgisinternal)
           and exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'tools' and policyname = 'tools_staff_insert')
           and exists (select 1 from information_schema.columns
                        where table_schema = 'public' and table_name = 'tools' and column_name = 'review');
  if not db_ok then
    return jsonb_build_object('code', 'no_sql', 'text',
      'В базе ещё нет обновления «сотрудник выкладывает инструмент». Владельцу: выполнить в Supabase файлы '
      || '20261029_staff_orders.sql и 20261030_staff_tools.sql, затем 20261107_staff_publish_check.sql.');
  end if;
  select s.id, s.status, s.can_catalog, s.owner_email into sid, st, cat, own
    from public.iva_staff s
   where s.email = em
   order by (s.status = 'active') desc, (s.status = 'invited') desc, s.created_at desc
   limit 1;
  if sid is null then
    return jsonb_build_object('code', 'not_staff', 'text',
      'Эта почта не числится сотрудником проката. Войдите с той почтой, на которую пришло приглашение, '
      || 'или попросите владельца пригласить её.');
  end if;
  if st = 'invited' then
    return jsonb_build_object('code', 'invited', 'text', 'Приглашение ещё не принято — примите его в «Кабинете».');
  end if;
  if st <> 'active' then
    return jsonb_build_object('code', 'declined', 'text',
      'Вы отказались от приглашения. Владелец может пригласить вас снова.');
  end if;
  if not coalesce(cat, false) then
    return jsonb_build_object('code', 'no_catalog', 'text',
      'Нет права «Выкладывает инструмент». Владелец включает его: «Кабинет» → «Настройка проката» → «Сотрудники».');
  end if;
  if not exists (select 1 from public.admins a where lower(btrim(a.email)) = own and coalesce(a.active, false)) then
    return jsonb_build_object('code', 'owner_off', 'text',
      'Прокат владельца сейчас приостановлен или выключен — новые инструменты не выкладываются.');
  end if;
  select count(*) into n
    from public.iva_staff_points sp
    join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = own
   where sp.staff_id = sid;
  if n = 0 then
    return jsonb_build_object('code', 'no_points', 'text',
      'Вы не прикреплены ни к одному пункту выдачи. Владелец прикрепляет: «Настройка проката» → «Сотрудники» → ваша карточка.');
  end if;
  if to_regclass('storage.objects') is not null
     and not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects'
                                                  and policyname = 'iva_staff_tool_photos_ins') then
    return jsonb_build_object('code', 'no_photo_rule', 'text',
      'Фото не загрузится: в хранилище нет правила для фото сотрудников. Владельцу: выполнить 20261107_staff_publish_check.sql '
      || '(если строка «фото сотрудника» осталась «❌» — создать правило в Storage → Policies).');
  end if;
  return jsonb_build_object('code', 'ok', 'text',
    'В базе всё в порядке: права есть, пункт есть, фото разрешены. Попробуйте ещё раз; если снова откажет — '
    || 'пришлите владельцу снимок этого окна.');
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_publish_check(text) FROM public, anon, authenticated;

-- 2. То же для вошедшего (приложение — после отказа)
CREATE OR REPLACE FUNCTION public.iva_staff_publish_why()
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select public.iva_staff_publish_check(coalesce(auth.jwt() ->> 'email', ''));
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_publish_why() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_publish_why() TO authenticated;

-- 3. Фото инструментов сотрудника: правила хранилища заново (как в 20261030)
DO $do$
BEGIN
  IF to_regclass('storage.objects') IS NULL OR to_regprocedure('public.iva_staff_catalog()') IS NULL THEN
    RAISE NOTICE 'Хранилища или 20261030 нет — правила для фото сотрудника не созданы';
    RETURN;
  END IF;
  EXECUTE 'DROP POLICY IF EXISTS iva_staff_tool_photos_ins ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_staff_tool_photos_ins ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog())$p$;
  EXECUTE 'DROP POLICY IF EXISTS iva_staff_tool_photos_sel ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_staff_tool_photos_sel ON storage.objects FOR SELECT TO authenticated
    USING (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog())$p$;
EXCEPTION WHEN insufficient_privilege OR undefined_function THEN
  RAISE NOTICE 'Правила хранилища для фото не созданы (%): создайте их в Storage → Policies', SQLERRM;
END $do$;
DO $do$
BEGIN
  IF to_regclass('storage.objects') IS NULL OR to_regprocedure('public.iva_staff_catalog()') IS NULL THEN RETURN; END IF;
  EXECUTE 'DROP POLICY IF EXISTS iva_staff_tool_photos_upd ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_staff_tool_photos_upd ON storage.objects FOR UPDATE TO authenticated
    USING (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog() AND owner = auth.uid())
    WITH CHECK (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog())$p$;
EXCEPTION WHEN insufficient_privilege OR undefined_column OR undefined_function THEN
  RAISE NOTICE 'Правило замены фото не создано (%): новые фото загружаются и без него', SQLERRM;
END $do$;

-- 4. «На одобрение» → владельцу сразу: отметка Realtime + push-сигнал «o» (только номер задания, без текста)
CREATE OR REPLACE FUNCTION public.iva_tool_review_ping()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  nr text := to_jsonb(new) ->> 'review';
  orv text := case when tg_op = 'UPDATE' then to_jsonb(old) ->> 'review' end;
  em text := lower(btrim(coalesce(new.owner_email, '')));
begin
  if em = '' then return null; end if;
  if tg_op = 'INSERT' and nr is distinct from 'pending' then return null; end if;
  if tg_op = 'UPDATE' and (nr is not distinct from orv
                           or (nr is distinct from 'pending' and orv is distinct from 'pending')) then
    return null;                                          -- «на одобрении» не появилось и не ушло
  end if;
  begin
    if to_regprocedure('public.iva_role_ping_touch(text)') is not null then
      execute 'select public.iva_role_ping_touch($1)' using em;   -- открытое приложение владельца — сразу
    end if;
  exception when others then null;                        -- отметка не должна мешать публикации
  end;
  if nr = 'pending' then
    begin
      if to_regprocedure('public.iva_push_enqueue(text, text)') is not null then
        execute 'select public.iva_push_enqueue($1, $2)' using 'o', em;   -- закрытое — push «проверь сейчас»
      end if;
    exception when others then null;
    end;
  end if;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_review_ping() FROM public, anon, authenticated;

DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'tools' AND column_name = 'review') THEN
    RAISE NOTICE 'В базе нет 20261030 (tools.review) — отметка «на одобрение» не создана';
    RETURN;
  END IF;
  EXECUTE 'DROP TRIGGER IF EXISTS trg_iva_tool_review_ping ON public.tools';
  EXECUTE 'CREATE TRIGGER trg_iva_tool_review_ping AFTER INSERT OR UPDATE OF review ON public.tools '
       || 'FOR EACH ROW EXECUTE FUNCTION public.iva_tool_review_ping()';
END $do$;

NOTIFY pgrst, 'reload schema';

-- 5. Проверка и причины (одна таблица): «✅» — есть, «❌» — нет, «⚠️» — сведения
SELECT r.check_name, r.result FROM (
  SELECT x.ord, x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
    (1, 'SQL 20261029 — права сотрудника',
       to_regprocedure('public.iva_staff_right(uuid, text)') IS NOT NULL),
    (2, 'SQL 20261030 — сотрудник выкладывает инструмент',
       to_regprocedure('public.iva_tools_staff_before()') IS NOT NULL
       AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'trg_iva_staff_tool' AND NOT tgisinternal)
       AND EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'tools' AND policyname = 'tools_staff_insert')),
    (3, 'SQL 20261103 — моментально (Realtime)',
       to_regprocedure('public.iva_role_ping_touch(text)') IS NOT NULL
       AND (EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_role_ping')
            OR EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND puballtables))),
    (4, 'SQL 20261104 — режим «Инструменты сотрудников» у пункта',
       EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'iva_pickup_points' AND column_name = 'staff_publish')),
    (5, 'SQL 20261105 — пункт из формы',
       to_regprocedure('public.iva_staff_pick_point(uuid)') IS NOT NULL),
    (6, 'SQL 20261106 — в пункт / из пункта',
       to_regprocedure('public.iva_staff_link(uuid, uuid, boolean)') IS NOT NULL),
    (7, 'фото сотрудника: правило хранилища',
       to_regclass('storage.objects') IS NULL
       OR EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'storage' AND tablename = 'objects' AND policyname = 'iva_staff_tool_photos_ins')),
    (8, '«на одобрение» — владельцу сразу',
       EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'trg_iva_tool_review_ping' AND NOT tgisinternal)),
    (9, 'причина словами — вошедшим; проверка по почте закрыта',
       has_function_privilege('authenticated', 'public.iva_staff_publish_why()', 'EXECUTE')
       AND NOT has_function_privilege('anon', 'public.iva_staff_publish_why()', 'EXECUTE')
       AND NOT has_function_privilege('authenticated', 'public.iva_staff_publish_check(text)', 'EXECUTE')
       AND NOT has_function_privilege('authenticated', 'public.iva_tool_review_ping()', 'EXECUTE'))
  ) AS x(ord, check_name, ok)
  UNION ALL
  SELECT 10, 'другие триггеры перед записью инструмента',
         coalesce('⚠️ ' || string_agg(t.tgname, ', ' ORDER BY t.tgname), '✅ нет')
    FROM pg_trigger t
   WHERE t.tgrelid = 'public.tools'::regclass AND NOT t.tgisinternal
     AND (t.tgtype & 2) = 2 AND (t.tgtype & 4) = 4                 -- BEFORE … INSERT
     AND t.tgname NOT IN ('iva_tools_owner_default', 'tr_tools_censor', 'trg_iva_staff_tool', 'trg_iva_tool_paused', 'trg_iva_tool_point')
  UNION ALL
  SELECT 11, 'ограничивающие правила (RESTRICTIVE) на инструменты и фото',
         coalesce('⚠️ ' || string_agg(p.schemaname || '.' || p.tablename || ': ' || p.policyname, ', ' ORDER BY p.policyname), '✅ нет')
    FROM pg_policies p
   WHERE p.permissive = 'RESTRICTIVE'
     AND ((p.schemaname = 'public' AND p.tablename = 'tools') OR (p.schemaname = 'storage' AND p.tablename = 'objects'))
  UNION ALL
  SELECT 20, 'сотрудник ' || s.email || ' (' || s.status || ')',
         CASE WHEN c.j ->> 'code' = 'ok' THEN '✅ может выкладывать инструмент'
              ELSE '❌ ' || (c.j ->> 'text') END
    FROM public.iva_staff s
   CROSS JOIN LATERAL (SELECT public.iva_staff_publish_check(s.email) AS j) c
   WHERE s.status IN ('invited', 'active')
) r
ORDER BY r.ord, r.check_name;

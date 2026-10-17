-- ============================================================
--  Ива · 10.105 — этап 3в «Сотрудники и пункты»: сотрудник с правом «Выкладывает инструмент» добавляет, правит,
--  скрывает и удаляет инструменты своих пунктов; новые — по выбору владельца сразу или после его одобрения
--  (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
--  Применять ПОСЛЕ 20261028_staff.sql (сотрудники) и 20261029_staff_orders.sql (проверка прав iva_staff_right).
--
--  Что делает:
--   • tools.staff_id — кто из сотрудников добавил инструмент (номер сотрудника, не почта: список инструментов
--     читают все, почта сотрудника клиентам не видна). У инструментов владельца — пусто;
--   • tools.review — 'pending' (ждёт одобрения владельца) / 'rejected' (владелец отклонил) / пусто (как раньше);
--   • admins.staff_publish — «Инструменты сотрудников»: 'approve' (после моего одобрения, так по умолчанию) / 'now'
--     (сразу);
--   • правила доступа к tools для сотрудника: видит, добавляет, меняет и удаляет инструменты ТОЛЬКО своих пунктов
--     (принял приглашение, право «Выкладывает инструмент», прокат включён). Правило владельца не меняется;
--   • триггер: у инструмента сотрудника владелец — его прокат (сам сотрудник владельцем не станет), пункт — только
--     свой (не указан — первый свой); в режиме «после одобрения» новый инструмент скрыт и ждёт одобрения. Правки —
--     сразу (одобрение только для новых). Правка отклонённого — снова «на одобрение». Пока инструмент ждёт
--     одобрения, сотрудник его не покажет. Владелец сам нажал «показать» в своём списке — это и есть «Опубликовать»;
--   • iva_staff_tools() — сотруднику: инструменты своих пунктов (без почты владельца) с названием пункта;
--   • iva_staff_publish() / iva_staff_publish_set(mode) — владельцу: режим «сразу / после одобрения»;
--   • iva_tools_pending() — владельцу: что ждёт одобрения (название, цена, фото, пункт, кто добавил);
--   • iva_tool_review(id, ok) — владельцу: «Опубликовать» (виден клиентам) / «Отклонить» (скрыт, сотрудник видит
--     «Отклонён» и может исправить);
--   • фото: сотруднику разрешено загружать фото инструментов в хранилище tool-photos (папка tools/), как владельцу.
--     Прежние правила хранилища не трогаются — добавляются свои. Если хранилище не даст создать правило — будет
--     NOTICE, остальное применится.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  Ждущие одобрения после отката останутся скрытыми — владелец может показать их в «Мои инструменты».
--
--  ОТКАТ (сотрудники перестанут выкладывать инструменты; инструменты остаются у проката):
--      DROP POLICY IF EXISTS iva_staff_tool_photos_ins ON storage.objects;
--      DROP POLICY IF EXISTS iva_staff_tool_photos_sel ON storage.objects;
--      DROP POLICY IF EXISTS iva_staff_tool_photos_upd ON storage.objects;
--      DROP POLICY IF EXISTS tools_staff_select ON public.tools;
--      DROP POLICY IF EXISTS tools_staff_insert ON public.tools;
--      DROP POLICY IF EXISTS tools_staff_update ON public.tools;
--      DROP POLICY IF EXISTS tools_staff_delete ON public.tools;
--      DROP TRIGGER IF EXISTS trg_iva_staff_tool ON public.tools;
--      DROP FUNCTION IF EXISTS public.iva_tools_staff_before();
--      DROP FUNCTION IF EXISTS public.iva_staff_tools();
--      DROP FUNCTION IF EXISTS public.iva_tool_review(uuid, boolean);
--      DROP FUNCTION IF EXISTS public.iva_tools_pending();
--      DROP FUNCTION IF EXISTS public.iva_staff_publish_set(text);
--      DROP FUNCTION IF EXISTS public.iva_staff_publish();
--      DROP FUNCTION IF EXISTS public.iva_staff_tool_ok(uuid, text);
--      DROP FUNCTION IF EXISTS public.iva_staff_catalog();
--      ALTER TABLE public.tools DROP COLUMN IF EXISTS review;
--      ALTER TABLE public.tools DROP COLUMN IF EXISTS staff_id;
--      ALTER TABLE public.admins DROP COLUMN IF EXISTS staff_publish;
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_staff') IS NULL OR to_regclass('public.iva_staff_points') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261028_staff.sql (SQL 10.103: сотрудники)';
  END IF;
  IF to_regprocedure('public.iva_staff_right(uuid, text)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261029_staff_orders.sql (SQL 10.104: заявки пункта)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'tools' AND column_name = 'point_id') THEN
    RAISE EXCEPTION 'Сначала примените 20261026_tool_points.sql (SQL 10.101: пункт у инструмента)';
  END IF;
END $do$;

-- 1. Столбцы
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS staff_id uuid;
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS review text;
DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.tools'::regclass AND conname = 'tools_staff_fk') THEN
    ALTER TABLE public.tools ADD CONSTRAINT tools_staff_fk
      FOREIGN KEY (staff_id) REFERENCES public.iva_staff(id) ON DELETE SET NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.tools'::regclass AND conname = 'tools_review_check') THEN
    ALTER TABLE public.tools ADD CONSTRAINT tools_review_check CHECK (review IS NULL OR review IN ('pending', 'rejected'));
  END IF;
END $do$;
CREATE INDEX IF NOT EXISTS idx_tools_review ON public.tools (owner_email) WHERE review IS NOT NULL;
COMMENT ON COLUMN public.tools.staff_id IS 'Ива 10.105: сотрудник, добавивший инструмент (iva_staff.id); у инструментов владельца — пусто.';
COMMENT ON COLUMN public.tools.review IS 'Ива 10.105: pending — ждёт одобрения владельца, rejected — отклонён, пусто — обычный.';

ALTER TABLE public.admins ADD COLUMN IF NOT EXISTS staff_publish text NOT NULL DEFAULT 'approve';
DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.admins'::regclass AND conname = 'admins_staff_publish_check') THEN
    ALTER TABLE public.admins ADD CONSTRAINT admins_staff_publish_check CHECK (staff_publish IN ('now', 'approve'));
  END IF;
END $do$;
COMMENT ON COLUMN public.admins.staff_publish IS 'Ива 10.105: инструменты сотрудников — now (сразу) / approve (после одобрения владельца).';

-- 2. Вошедший — сотрудник с правом «Выкладывает инструмент» (принял приглашение, прокат включён)
CREATE OR REPLACE FUNCTION public.iva_staff_catalog()
 RETURNS boolean
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select coalesce(auth.jwt() ->> 'email', '') <> '' and exists (
    select 1 from public.iva_staff s
      join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
     where s.email = lower(btrim(auth.jwt() ->> 'email')) and s.status = 'active' and s.can_catalog);
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_catalog() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_catalog() TO authenticated;

-- Инструмент (пункт + владелец) — в пункте вошедшего сотрудника с правом «Выкладывает инструмент»
CREATE OR REPLACE FUNCTION public.iva_staff_tool_ok(p_point uuid, p_owner text)
 RETURNS boolean
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p_point is not null
     and public.iva_staff_right(p_point, 'catalog')
     and exists (select 1 from public.iva_pickup_points pp
                  where pp.id = p_point and pp.owner_email = lower(btrim(coalesce(p_owner, ''))));
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_tool_ok(uuid, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_tool_ok(uuid, text) TO authenticated;

-- 3. Перед записью инструмента: владелец, пункт и одобрение у инструментов сотрудника
CREATE OR REPLACE FUNCTION public.iva_tools_staff_before()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  sid uuid;
  sowner text;
  mode text;
  fp uuid;
begin
  if tg_op = 'UPDATE' then
    new.staff_id := old.staff_id;                         -- кто добавил — не меняется
  end if;
  if em <> '' and not exists (select 1 from public.admins a
                               where a.active and a.role in ('admin', 'chief') and lower(btrim(a.email)) = em) then
    select s.id, s.owner_email into sid, sowner
      from public.iva_staff s
      join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
     where s.email = em and s.status = 'active' and s.can_catalog
     limit 1;
  end if;

  if sid is null then                                     -- владелец, создатель, база — как раньше
    if tg_op = 'INSERT' then
      new.staff_id := null;
      new.review := null;
    elsif old.review is not null and new.review is not distinct from old.review
          and coalesce(new.active, false) and not coalesce(old.active, false) then
      new.review := null;                                 -- владелец сам показал инструмент — это «Опубликовать»
    end if;
    return new;
  end if;

  if tg_op = 'INSERT' then
    new.owner_email := sowner;                            -- инструмент сотрудника — инструмент его проката
    new.staff_id := sid;
    if new.point_id is null then
      select pp.id into fp
        from public.iva_staff_points sp
        join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = sowner
       where sp.staff_id = sid
       order by pp.created_at, pp.id
       limit 1;
      if fp is null then raise exception 'У вас нет пункта выдачи — обратитесь к владельцу проката'; end if;
      new.point_id := fp;
    elsif not public.iva_staff_right(new.point_id, 'catalog') then
      raise exception 'Пункт выдачи — только из ваших пунктов';
    end if;
    select coalesce(max(a.staff_publish), 'approve') into mode
      from public.admins a where lower(btrim(a.email)) = sowner and a.active;
    if mode = 'now' then
      new.review := null;
    else
      new.active := false;                                -- до одобрения клиенты не видят
      new.review := 'pending';
    end if;
    return new;
  end if;

  new.owner_email := old.owner_email;                     -- в чужой прокат не переносится
  if new.point_id is null then
    new.point_id := old.point_id;
  elsif new.point_id is distinct from old.point_id and not public.iva_staff_right(new.point_id, 'catalog') then
    raise exception 'Пункт выдачи — только из ваших пунктов';
  end if;
  if old.review is not null then
    new.active := false;                                  -- ждёт одобрения — сотрудник не покажет
    new.review := 'pending';                              -- исправил отклонённый — снова на одобрение
  else
    new.review := null;                                   -- правки опубликованного — сразу
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_staff_before() FROM public, anon, authenticated;

-- имя: после iva_tools_owner_default и tr_tools_censor, до trg_iva_tool_point (адрес из пункта) — по алфавиту
DROP TRIGGER IF EXISTS trg_iva_staff_tool ON public.tools;
CREATE TRIGGER trg_iva_staff_tool
  BEFORE INSERT OR UPDATE ON public.tools
  FOR EACH ROW EXECUTE FUNCTION public.iva_tools_staff_before();

-- 4. Правила доступа: сотрудник — только инструменты своих пунктов (правило владельца tools_admin не меняется)
ALTER TABLE public.tools ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tools_staff_select ON public.tools;
CREATE POLICY tools_staff_select ON public.tools FOR SELECT TO authenticated
  USING (public.iva_staff_tool_ok(point_id, owner_email));
DROP POLICY IF EXISTS tools_staff_insert ON public.tools;
CREATE POLICY tools_staff_insert ON public.tools FOR INSERT TO authenticated
  WITH CHECK (public.iva_staff_tool_ok(point_id, owner_email));
DROP POLICY IF EXISTS tools_staff_update ON public.tools;
CREATE POLICY tools_staff_update ON public.tools FOR UPDATE TO authenticated
  USING (public.iva_staff_tool_ok(point_id, owner_email))
  WITH CHECK (public.iva_staff_tool_ok(point_id, owner_email));
DROP POLICY IF EXISTS tools_staff_delete ON public.tools;
CREATE POLICY tools_staff_delete ON public.tools FOR DELETE TO authenticated
  USING (public.iva_staff_tool_ok(point_id, owner_email));

-- 5. Фото инструментов: сотруднику — как владельцу (папка tools/ хранилища tool-photos)
DO $do$
BEGIN
  IF to_regclass('storage.objects') IS NULL THEN
    RAISE NOTICE 'Хранилища нет — правила для фото не нужны';
    RETURN;
  END IF;
  EXECUTE 'DROP POLICY IF EXISTS iva_staff_tool_photos_ins ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_staff_tool_photos_ins ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog())$p$;
  EXECUTE 'DROP POLICY IF EXISTS iva_staff_tool_photos_sel ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_staff_tool_photos_sel ON storage.objects FOR SELECT TO authenticated
    USING (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog())$p$;
EXCEPTION WHEN insufficient_privilege OR undefined_function THEN
  RAISE NOTICE 'Правила хранилища для фото не созданы (%): фото у сотрудника загрузятся, только если пускают прежние правила хранилища', SQLERRM;
END $do$;
-- замена фото с тем же именем (x-upsert) — только своего файла
DO $do$
BEGIN
  IF to_regclass('storage.objects') IS NULL THEN RETURN; END IF;
  EXECUTE 'DROP POLICY IF EXISTS iva_staff_tool_photos_upd ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_staff_tool_photos_upd ON storage.objects FOR UPDATE TO authenticated
    USING (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog() AND owner = auth.uid())
    WITH CHECK (bucket_id = 'tool-photos' AND name LIKE 'tools/%' AND public.iva_staff_catalog())$p$;
EXCEPTION WHEN insufficient_privilege OR undefined_column OR undefined_function THEN
  RAISE NOTICE 'Правило замены фото не создано (%): новые фото загружаются и без него', SQLERRM;
END $do$;

-- 6. Сотруднику: инструменты своих пунктов
CREATE OR REPLACE FUNCTION public.iva_staff_tools()
 RETURNS SETOF jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select (to_jsonb(t) - 'owner_email' - 'staff_id')
         || jsonb_build_object('point_name', pp.name,
                               'mine', t.staff_id is not null and t.staff_id = s.id)
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
    join public.iva_staff_points sp on sp.staff_id = s.id
    join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = s.owner_email
    join public.tools t on t.point_id = pp.id and lower(btrim(coalesce(t.owner_email, ''))) = s.owner_email
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and s.email = lower(btrim(auth.jwt() ->> 'email'))
     and s.status = 'active' and s.can_catalog
   order by case t.review when 'pending' then 0 when 'rejected' then 1 else 2 end, lower(coalesce(t.name, '')), t.id
   limit 500;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_tools() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_tools() TO authenticated;

-- 7. Владельцу: режим «Инструменты сотрудников»
CREATE OR REPLACE FUNCTION public.iva_staff_publish()
 RETURNS text
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select case when public.iva_pickup_is_admin() then
           coalesce((select max(a.staff_publish) from public.admins a
                      where lower(btrim(a.email)) = lower(btrim(auth.jwt() ->> 'email')) and a.active), 'approve')
         end;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_publish() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_publish() TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_staff_publish_set(p_mode text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  if p_mode is null or p_mode not in ('now', 'approve') then raise exception 'Неизвестный режим публикации'; end if;
  update public.admins set staff_publish = p_mode
   where lower(btrim(email)) = lower(btrim(auth.jwt() ->> 'email')) and active;
  return p_mode;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_publish_set(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_publish_set(text) TO authenticated;

-- 8. Владельцу: что ждёт одобрения
CREATE OR REPLACE FUNCTION public.iva_tools_pending()
 RETURNS TABLE (id uuid, name text, price text, img text, point_name text, staff_name text, created_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select t.id, coalesce(t.name, ''), coalesce(to_jsonb(t) ->> 'price', ''), coalesce(to_jsonb(t) ->> 'img', ''),
         coalesce(pp.name, ''), coalesce(s.name, ''), (to_jsonb(t) ->> 'created_at')::timestamptz
    from public.tools t
    left join public.iva_pickup_points pp on pp.id = t.point_id
    left join public.iva_staff s on s.id = t.staff_id
   where public.iva_pickup_is_admin()
     and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
     and t.review = 'pending'
   order by (to_jsonb(t) ->> 'created_at') desc nulls last, t.id
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_pending() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tools_pending() TO authenticated;

-- 9. Владельцу: «Опубликовать» / «Отклонить»
CREATE OR REPLACE FUNCTION public.iva_tool_review(p_id uuid, p_ok boolean)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  r text;
begin
  if not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  update public.tools set active = coalesce(p_ok, false), review = case when p_ok then null else 'rejected' end
   where id = p_id and review = 'pending'
     and lower(btrim(coalesce(owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
  returning case when p_ok then 'published' else 'rejected' end into r;
  if r is null then raise exception 'Инструмент уже не ждёт одобрения — обновите список'; end if;
  return r;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_review(uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_review(uuid, boolean) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 10. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('столбцы tools.staff_id, tools.review, admins.staff_publish',
     (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
        AND ((table_name = 'tools' AND column_name IN ('staff_id', 'review')) OR (table_name = 'admins' AND column_name = 'staff_publish'))) = 3),
  ('триггер инструментов сотрудника',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'trg_iva_staff_tool' AND NOT tgisinternal)),
  ('правила доступа сотрудника (4)',
     (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'tools' AND policyname LIKE 'tools\_staff\_%') = 4),
  ('функции — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_staff_tools()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_tools()', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_tool_review(uuid, boolean)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tool_review(uuid, boolean)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tools_pending()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_staff_publish_set(text)', 'EXECUTE')),
  ('триггер закрыт для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_tools_staff_before()', 'EXECUTE'))
) AS x(check_name, ok);

-- ============================================================
--  Ива · 10.108 — создатель приостанавливает и удаляет админов (решения владельца 17.10.2026).
--  Нужны только admins, tools, fn_is_chief, jwt_email — от SQL 10.102–10.107 не зависит (их можно применить позже).
--
--  Что делает:
--   • «Приостановить» = admins.active = false (тот же признак, что «Доступ включён» на сайте):
--     админ не публикует и не правит (как и раньше — fn_is_admin), его сотрудники теряют доступ (как и раньше —
--     функции сотрудников требуют активного владельца), И ТЕПЕРЬ его показанные инструменты скрываются от клиентов
--     (tools.active = false, отметка tools.chief_paused) — новые заявки к нему не приходят, пункты клиенты не видят
--     (пункт виден только через инструмент). «Возобновить» — показываются ровно те, что скрыла пауза
--     (кроме снятых «Цензурой»). Скрытое самим админом остаётся скрытым.
--     Срабатывает при любой смене active — из приложения, с сайта или из SQL Editor (триггер на admins).
--   • «Удалить» = admins.removed_at = время, active = false: из списка админов пропадает, инструменты скрыты,
--     сотрудники без доступа. Заявки, комиссия, долги и оплаты остаются в истории — ничего не удаляется.
--     Снова добавить эту почту — она возвращается админом, инструменты остаются скрытыми (включит сам).
--   • iva_admin_add / iva_admin_pause / iva_admin_remove / iva_admin_state / iva_admins_removed — только создателю
--     (fn_is_chief). Владельца площадки и самого себя приостановить и удалить нельзя.
--   • iva_admin_events — журнал: кто, кого, когда и сколько инструментов скрыто/показано. Читает только создатель.
--   • Защита: removed_at меняет только создатель; tools.chief_paused ставит только база (вручную не меняется).
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (кнопки пропадут; скрытые паузой инструменты сначала показываются, удалённые админы остаются выключенными):
--      UPDATE public.tools SET active = true WHERE chief_paused;
--      DROP FUNCTION IF EXISTS public.iva_admins_removed();
--      DROP FUNCTION IF EXISTS public.iva_admin_state(text);
--      DROP FUNCTION IF EXISTS public.iva_admin_remove(text);
--      DROP FUNCTION IF EXISTS public.iva_admin_pause(text, boolean);
--      DROP FUNCTION IF EXISTS public.iva_admin_add(text);
--      DROP TRIGGER IF EXISTS trg_iva_admin_access ON public.admins;
--      DROP FUNCTION IF EXISTS public.iva_admin_access_trg();
--      DROP TRIGGER IF EXISTS trg_iva_admin_removed_guard ON public.admins;
--      DROP FUNCTION IF EXISTS public.iva_admin_removed_guard();
--      DROP TRIGGER IF EXISTS trg_iva_tool_paused ON public.tools;
--      DROP FUNCTION IF EXISTS public.iva_tool_paused_guard();
--      DROP TABLE IF EXISTS public.iva_admin_events;
--      ALTER TABLE public.tools DROP COLUMN IF EXISTS chief_paused;
--      ALTER TABLE public.admins DROP COLUMN IF EXISTS removed_at;
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.admins') IS NULL OR to_regclass('public.tools') IS NULL
     OR to_regprocedure('public.fn_is_chief()') IS NULL OR to_regprocedure('public.jwt_email()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет admins / tools / fn_is_chief / jwt_email — это не база «Ивы»';
  END IF;
END $do$;

-- 1. Признаки «удалён» у админа и «скрыт паузой» у инструмента
ALTER TABLE public.admins ADD COLUMN IF NOT EXISTS removed_at timestamptz;
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS chief_paused boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN public.admins.removed_at IS 'Ива 10.108: когда создатель удалил админа (пусто — не удалён). Меняет только создатель.';
COMMENT ON COLUMN public.tools.chief_paused IS 'Ива 10.108: инструмент скрыт, потому что админ приостановлен/удалён. Ставит только база (триггер на admins).';

-- 2. Журнал действий создателя с админами
CREATE TABLE IF NOT EXISTS public.iva_admin_events (
  id     bigserial   PRIMARY KEY,
  email  text        NOT NULL,
  action text        NOT NULL CHECK (action IN ('add', 'restore', 'pause', 'resume', 'remove')),
  actor  text        NOT NULL DEFAULT '',
  tools  int         NOT NULL DEFAULT 0,
  at     timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.iva_admin_events IS 'Ива 10.108: кто из админов добавлен, возвращён, приостановлен, возобновлён, удалён; сколько инструментов скрыто/показано.';
ALTER TABLE public.iva_admin_events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_admin_events FROM public, anon, authenticated;
GRANT SELECT ON public.iva_admin_events TO authenticated;
DROP POLICY IF EXISTS iva_admin_events_read ON public.iva_admin_events;
CREATE POLICY iva_admin_events_read ON public.iva_admin_events FOR SELECT TO authenticated USING (public.fn_is_chief());

-- 3. «Скрыт паузой» ставит только база
CREATE OR REPLACE FUNCTION public.iva_tool_paused_guard()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  if coalesce(current_setting('iva.admin_pause', true), '') <> '1' then
    if tg_op = 'INSERT' then new.chief_paused := false;
    else new.chief_paused := old.chief_paused;
    end if;
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_paused_guard() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_tool_paused ON public.tools;
CREATE TRIGGER trg_iva_tool_paused
  BEFORE INSERT OR UPDATE ON public.tools
  FOR EACH ROW EXECUTE FUNCTION public.iva_tool_paused_guard();

-- 4. «Удалён» меняет только создатель (сам админ свою отметку не снимет и не поставит)
CREATE OR REPLACE FUNCTION public.iva_admin_removed_guard()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  if new.removed_at is distinct from old.removed_at
     and coalesce(current_setting('role', true), '') in ('authenticated', 'anon')
     and not public.fn_is_chief() then
    raise exception 'Удаляет админов только владелец площадки';
  end if;
  return new;
end
$function$;
GRANT EXECUTE ON FUNCTION public.iva_admin_removed_guard() TO authenticated;
DROP TRIGGER IF EXISTS trg_iva_admin_removed_guard ON public.admins;
CREATE TRIGGER trg_iva_admin_removed_guard
  BEFORE UPDATE ON public.admins
  FOR EACH ROW EXECUTE FUNCTION public.iva_admin_removed_guard();

-- 5. Доступ админа поменялся — скрыть или показать его инструменты, записать в журнал
CREATE OR REPLACE FUNCTION public.iva_admin_access_trg()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(new.email, '')));
  was_off boolean := not coalesce(old.active, false) or old.removed_at is not null;
  now_off boolean := not coalesce(new.active, false) or new.removed_at is not null;
  act text;
  n int := 0;
begin
  if coalesce(new.role, '') = 'chief' or em = '' then return null; end if;
  if old.removed_at is null and new.removed_at is not null then act := 'remove';
  elsif old.removed_at is not null and new.removed_at is null then act := 'restore';
  elsif coalesce(old.active, false) and not coalesce(new.active, false) then act := 'pause';
  elsif not coalesce(old.active, false) and coalesce(new.active, false) then act := 'resume';
  else return null;
  end if;

  perform set_config('iva.admin_pause', '1', true);
  perform set_config('iva.censor_skip', '1', true);
  if now_off and not was_off then
    update public.tools t set active = false, chief_paused = true
     where lower(btrim(coalesce(t.owner_email, ''))) = em and coalesce(t.active, false);
    get diagnostics n = row_count;
  elsif was_off and not now_off then
    if old.removed_at is not null then
      -- вернули удалённого: инструменты остаются скрытыми, включит сам
      update public.tools t set chief_paused = false
       where lower(btrim(coalesce(t.owner_email, ''))) = em and t.chief_paused;
    else
      if to_regclass('public.iva_censor_queue') is not null then
        -- снятое «Цензурой» за это время — не показываем
        execute $q$update public.tools t set chief_paused = false
                   where lower(btrim(coalesce(t.owner_email, ''))) = $1 and t.chief_paused
                     and exists (select 1 from public.iva_censor_queue c
                                  where c.source = 'tool' and c.ref_id = t.id::text and c.status in ('new', 'hidden'))$q$
          using em;
      end if;
      update public.tools t set active = true, chief_paused = false
       where lower(btrim(coalesce(t.owner_email, ''))) = em and t.chief_paused;
      get diagnostics n = row_count;
    end if;
  end if;
  perform set_config('iva.censor_skip', '', true);
  perform set_config('iva.admin_pause', '', true);

  insert into public.iva_admin_events (email, action, actor, tools)
  values (em, act, coalesce(nullif(lower(btrim(coalesce(auth.jwt() ->> 'email', ''))), ''), 'база'), n);
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_admin_access_trg() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_admin_access ON public.admins;
CREATE TRIGGER trg_iva_admin_access
  AFTER UPDATE OF active, removed_at ON public.admins
  FOR EACH ROW EXECUTE FUNCTION public.iva_admin_access_trg();

-- 6. Действия создателя
CREATE OR REPLACE FUNCTION public.iva_admin_add(p_email text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(p_email, '')));
  me text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  r record;
  had boolean;
  busy boolean := false;
begin
  if me = '' or not public.fn_is_chief() then
    raise exception 'Добавляет админов только владелец площадки';
  end if;
  if em !~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]{2,}$' or char_length(em) > 254 then
    raise exception 'Похоже, в email ошибка (пример: ivan@mail.ru)';
  end if;
  if em = me then
    raise exception 'Это ваша почта — вы уже владелец площадки';
  end if;
  select a.role, a.active, a.removed_at into r
    from public.admins a where lower(btrim(a.email)) = em limit 1;
  had := found;
  if had then
    if coalesce(r.role, '') = 'chief' then raise exception 'Это владелец площадки'; end if;
    if r.removed_at is null and coalesce(r.active, false) then raise exception 'Этот человек уже админ'; end if;
    if r.removed_at is null then
      raise exception 'Этот админ приостановлен — откройте его карточку и нажмите «Возобновить»';
    end if;
  end if;
  if to_regclass('public.iva_staff') is not null then
    execute $q$select exists (select 1 from public.iva_staff s where s.email = $1 and s.status in ('invited', 'active'))$q$
      into busy using em;
    if busy then
      raise exception 'Этот человек — сотрудник проката. Сначала владелец проката должен убрать его из сотрудников';
    end if;
  end if;
  if had then
    update public.admins a set active = true, removed_at = null where lower(btrim(a.email)) = em;
    return jsonb_build_object('email', em, 'restored', true);
  end if;
  insert into public.admins (email, role, name, active) values (em, 'admin', '', true);
  insert into public.iva_admin_events (email, action, actor) values (em, 'add', me);
  return jsonb_build_object('email', em, 'restored', false);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_admin_add(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_admin_add(text) TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_admin_pause(p_email text, p_pause boolean)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(p_email, '')));
  me text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  r record;
  n int;
begin
  if me = '' or not public.fn_is_chief() then
    raise exception 'Приостанавливает админов только владелец площадки';
  end if;
  select a.role, a.active, a.removed_at into r from public.admins a where lower(btrim(a.email)) = em limit 1;
  if not found or r.removed_at is not null then raise exception 'Админ не найден — возможно, его уже удалили'; end if;
  if coalesce(r.role, '') = 'chief' or em = me then raise exception 'Владельца площадки приостановить нельзя'; end if;
  if p_pause is null then raise exception 'Не указано, приостановить или возобновить'; end if;
  update public.admins a set active = not p_pause where lower(btrim(a.email)) = em;
  select count(*) into n from public.tools t where lower(btrim(coalesce(t.owner_email, ''))) = em and t.chief_paused;
  return jsonb_build_object('email', em, 'active', not p_pause, 'hidden', n);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_admin_pause(text, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_admin_pause(text, boolean) TO authenticated;

CREATE OR REPLACE FUNCTION public.iva_admin_remove(p_email text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(p_email, '')));
  me text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  r record;
  n int;
begin
  if me = '' or not public.fn_is_chief() then
    raise exception 'Удаляет админов только владелец площадки';
  end if;
  select a.role, a.removed_at into r from public.admins a where lower(btrim(a.email)) = em limit 1;
  if not found or r.removed_at is not null then raise exception 'Админ не найден — возможно, его уже удалили'; end if;
  if coalesce(r.role, '') = 'chief' or em = me then raise exception 'Владельца площадки удалить нельзя'; end if;
  update public.admins a set active = false, removed_at = now() where lower(btrim(a.email)) = em;
  select count(*) into n from public.tools t where lower(btrim(coalesce(t.owner_email, ''))) = em and t.chief_paused;
  return jsonb_build_object('email', em, 'hidden', n);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_admin_remove(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_admin_remove(text) TO authenticated;

-- Карточка админа в приложении: роль, доступ, удалён ли, сколько инструментов скрыто паузой
CREATE OR REPLACE FUNCTION public.iva_admin_state(p_email text)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select case when not public.fn_is_chief() then null else (
    select jsonb_build_object('email', lower(btrim(a.email)), 'role', coalesce(a.role, ''),
                              'active', coalesce(a.active, false), 'removed', a.removed_at is not null,
                              'hidden', (select count(*) from public.tools t
                                          where lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(a.email)) and t.chief_paused))
      from public.admins a where lower(btrim(a.email)) = lower(btrim(coalesce(p_email, ''))) limit 1) end;
$function$;
REVOKE ALL ON FUNCTION public.iva_admin_state(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_admin_state(text) TO authenticated;

-- Список «Все администраторы» в приложении прячет удалённых
CREATE OR REPLACE FUNCTION public.iva_admins_removed()
 RETURNS SETOF text
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select lower(btrim(a.email)) from public.admins a
   where public.fn_is_chief() and a.removed_at is not null
   order by 1;
$function$;
REVOKE ALL ON FUNCTION public.iva_admins_removed() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_admins_removed() TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 7. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('отметки «удалён» и «скрыт паузой»',
     (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
        AND ((table_name = 'admins' AND column_name = 'removed_at') OR (table_name = 'tools' AND column_name = 'chief_paused'))) = 2),
  ('пауза скрывает и показывает инструменты (триггер)',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.admins'::regclass AND tgname = 'trg_iva_admin_access')
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.admins'::regclass AND tgname = 'trg_iva_admin_removed_guard')
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'trg_iva_tool_paused')),
  ('действия — только вошедшим (внутри — только создателю)',
     has_function_privilege('authenticated', 'public.iva_admin_add(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_admin_add(text)', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_admin_pause(text, boolean)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_admin_pause(text, boolean)', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_admin_remove(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_admin_remove(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_admin_state(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_admins_removed()', 'EXECUTE')),
  ('служебная функция закрыта для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_admin_access_trg()', 'EXECUTE'))
) AS x(check_name, ok);

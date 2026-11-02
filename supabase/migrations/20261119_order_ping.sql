-- ============================================================
--  Ива · сайт — заявки обновляются сразу, без перезагрузки страницы (просьба владельца 24.10.2026:
--  «создали заявку, а сотрудник её не видел и уведомления не было»).
--
--  Что делает:
--   • iva_order_ping — по строке на почту: «когда последний раз что-то поменялось в заявках этого человека».
--     В строке только почта, время, вид ('new' — новая заявка, о ней сказать; 'upd' — просто обновить) и номер части.
--     Ни имён, ни телефонов, ни сумм. Читать может только сам человек (своя почта), писать — только база (триггеры).
--   • Отметку ставят триггеры:
--       – order_parts: новая заявка (часть) и любое изменение (Выдал, Вернули, отмена, срок, возврат по срокам);
--         кому: владелец проката, сотрудники этого пункта с правом «Выдаёт и принимает» или «Получает уведомления»
--         (о новой — 'new' только владельцу и тем, у кого «Получает уведомления»), клиент заявки ('upd');
--       – iva_order_changes (если есть, SQL 10.122): клиент просит отменить / изменить заявку — владелец и сотрудники пункта.
--   • Таблица добавляется в Supabase Realtime (публикация supabase_realtime). Сайт держит одно соединение на любой
--     странице и, как только его строка поменялась, сразу перечитывает заявки; о новой — всплывающее сообщение,
--     звук и (если разрешили) уведомление браузера.
--   • Ошибка в отметке никогда не мешает заявке: всё внутри «exception when others».
--
--  Без этого SQL сайт обновляет заявки раз в 30 секунд — как раньше, но теперь на любой странице и вкладке кабинета.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261119_order_ping_rollback.sql (заявки и сотрудники не меняются).
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.admins') IS NULL OR to_regclass('public.order_parts') IS NULL OR to_regclass('public.orders') IS NULL THEN
    RAISE EXCEPTION 'В базе нет admins / orders / order_parts — это не база «Ивы»';
  END IF;
END $do$;

-- 1. Отметка «в заявках что-то поменялось»
CREATE TABLE IF NOT EXISTS public.iva_order_ping (
  email      text        PRIMARY KEY CHECK (email = lower(btrim(email)) AND email <> ''),
  changed_at timestamptz NOT NULL DEFAULT now(),
  kind       text        NOT NULL DEFAULT 'upd' CHECK (kind IN ('new', 'upd')),
  part_id    bigint
);
COMMENT ON TABLE public.iva_order_ping IS 'Ива (сайт 24.10.2026): когда поменялись заявки человека (владелец, сотрудник пункта, клиент). Пишет только база, читает только сам человек — для Supabase Realtime';
ALTER TABLE public.iva_order_ping ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_order_ping FROM public, anon, authenticated;
GRANT SELECT ON public.iva_order_ping TO authenticated;
DROP POLICY IF EXISTS iva_order_ping_own ON public.iva_order_ping;
CREATE POLICY iva_order_ping_own ON public.iva_order_ping FOR SELECT TO authenticated
  USING (email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))));

CREATE OR REPLACE FUNCTION public.iva_order_ping_touch(p_email text, p_kind text, p_part bigint)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  insert into public.iva_order_ping (email, changed_at, kind, part_id)
  select lower(btrim(p_email)), clock_timestamp(), case when p_kind = 'new' then 'new' else 'upd' end, p_part
   where nullif(lower(btrim(coalesce(p_email, ''))), '') is not null
  on conflict (email) do update
    set changed_at = excluded.changed_at,
        -- «новая» не затирается простым обновлением той же части в той же секунде (вставка + триггеры журнала)
        kind = case when public.iva_order_ping.kind = 'new' and excluded.kind = 'upd'
                     and public.iva_order_ping.part_id is not distinct from excluded.part_id
                     and public.iva_order_ping.changed_at > clock_timestamp() - interval '2 seconds'
                    then 'new' else excluded.kind end,
        part_id = excluded.part_id;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_ping_touch(text, text, bigint) FROM public, anon, authenticated;

-- кому сказать про часть заявки: владелец, сотрудники пункта, клиент
CREATE OR REPLACE FUNCTION public.iva_order_ping_part(p_part bigint, p_owner text, p_point uuid, p_order bigint, p_new boolean)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text;
  nt boolean;
begin
  perform public.iva_order_ping_touch(p_owner, case when p_new then 'new' else 'upd' end, p_part);
  if p_point is not null and to_regclass('public.iva_staff') is not null and to_regclass('public.iva_staff_points') is not null then
    for em, nt in execute
      $q$select s.email, coalesce((to_jsonb(s) ->> 'can_notify')::boolean, false)
           from public.iva_staff s join public.iva_staff_points sp on sp.staff_id = s.id
          where sp.point_id = $1 and s.owner_email = lower(btrim($2)) and s.status = 'active'
            and (coalesce((to_jsonb(s) ->> 'can_issue')::boolean, false) or coalesce((to_jsonb(s) ->> 'can_notify')::boolean, false))$q$
      using p_point, coalesce(p_owner, '')
    loop
      perform public.iva_order_ping_touch(em, case when p_new and nt then 'new' else 'upd' end, p_part);
    end loop;
  end if;
  if p_order is not null then
    select o.user_email into em from public.orders o where o.id = p_order;
    if lower(btrim(coalesce(em, ''))) <> lower(btrim(coalesce(p_owner, ''))) then
      perform public.iva_order_ping_touch(em, 'upd', p_part);
    end if;
  end if;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_ping_part(bigint, text, uuid, bigint, boolean) FROM public, anon, authenticated;

-- 2. order_parts: новая часть заявки и любое её изменение
CREATE OR REPLACE FUNCTION public.iva_order_ping_parts()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  n jsonb := to_jsonb(new);
begin
  begin
    if tg_op = 'UPDATE' and to_jsonb(old) = n then return null; end if;
    perform public.iva_order_ping_part(new.id::bigint, n ->> 'owner_email', nullif(n ->> 'point_id', '')::uuid,
      nullif(n ->> 'order_id', '')::bigint, tg_op = 'INSERT' and coalesce(n ->> 'status', 'new') = 'new');
  exception when others then
    raise notice 'iva_order_ping_parts: отметка не поставлена: %', sqlerrm;
  end;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_ping_parts() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_order_ping ON public.order_parts;
CREATE TRIGGER trg_iva_order_ping
  AFTER INSERT OR UPDATE ON public.order_parts
  FOR EACH ROW EXECUTE FUNCTION public.iva_order_ping_parts();

-- 3. iva_order_changes (SQL 10.122): просьба клиента отменить / изменить заявку и решение по ней
CREATE OR REPLACE FUNCTION public.iva_order_ping_changes()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  n jsonb := to_jsonb(new);
  pt jsonb;
begin
  begin
    if tg_op = 'UPDATE' and to_jsonb(old) = n then return null; end if;
    select to_jsonb(p) into pt from public.order_parts p where p.id = nullif(n ->> 'part_id', '')::bigint;
    perform public.iva_order_ping_part(nullif(n ->> 'part_id', '')::bigint, coalesce(pt ->> 'owner_email', n ->> 'owner_email'),
      nullif(pt ->> 'point_id', '')::uuid, nullif(n ->> 'order_id', '')::bigint, false);
  exception when others then
    raise notice 'iva_order_ping_changes: отметка не поставлена: %', sqlerrm;
  end;
  return null;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_ping_changes() FROM public, anon, authenticated;
DO $do$
BEGIN
  IF to_regclass('public.iva_order_changes') IS NOT NULL THEN
    DROP TRIGGER IF EXISTS trg_iva_order_ping ON public.iva_order_changes;
    CREATE TRIGGER trg_iva_order_ping
      AFTER INSERT OR UPDATE ON public.iva_order_changes
      FOR EACH ROW EXECUTE FUNCTION public.iva_order_ping_changes();
  END IF;
END $do$;

-- 4. Supabase Realtime: сайт подписывается на свою строку
DO $do$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND NOT puballtables)
     AND NOT EXISTS (SELECT 1 FROM pg_publication_tables
                      WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_order_ping') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.iva_order_ping;
  END IF;
END $do$;

NOTIFY pgrst, 'reload schema';

-- 5. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('отметка «заявки поменялись» — читает только сам человек',
     to_regclass('public.iva_order_ping') IS NOT NULL
     AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_order_ping'::regclass)
     AND EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_order_ping' AND policyname = 'iva_order_ping_own')
     AND has_table_privilege('authenticated', 'public.iva_order_ping', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_order_ping', 'INSERT')
     AND NOT has_table_privilege('authenticated', 'public.iva_order_ping', 'UPDATE')
     AND NOT has_table_privilege('anon', 'public.iva_order_ping', 'SELECT')),
  ('триггеры: части заявок и просьбы клиента (если есть)',
     EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.order_parts'::regclass AND tgname = 'trg_iva_order_ping')
     AND (to_regclass('public.iva_order_changes') IS NULL
          OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = to_regclass('public.iva_order_changes') AND tgname = 'trg_iva_order_ping'))),
  ('Supabase Realtime включён для отметки',
     EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_order_ping')
     OR EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND puballtables)),
  ('служебные функции закрыты для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_order_ping_touch(text, text, bigint)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_order_ping_touch(text, text, bigint)', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_order_ping_part(bigint, text, uuid, bigint, boolean)', 'EXECUTE'))
) AS x(check_name, ok);

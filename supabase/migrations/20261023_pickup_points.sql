-- ============================================================
--  Ива 10.95 — «Пункт выдачи» в настройках админа (просьба владельца 16.10.2026).
--
--  У админа может быть несколько пунктов выдачи. У каждого: название, полный адрес
--  (с подсказками), график — часы открытия и закрытия на каждый день недели (или выходной),
--  email проката. Позже появится роль «Прокат» — email пункта пригодится ей.
--
--  Решение владельца: время закрытия для «Срок аренды → До закрытия пункта» берётся из
--  графика ПЕРВОГО пункта (самого раннего добавленного). Поэтому:
--    • при добавлении / изменении / удалении пункта база сама переписывает время закрытия
--      в настройках аренды этого админа (iva_rent_settings.close_min) — по первому пункту;
--      пунктов не осталось — время закрытия остаётся последним;
--    • iva_rent_settings_set(...) (кнопки «Срока аренды») при наличии пунктов сохраняет
--      время закрытия из первого пункта, а не присланное. Режим («Сутки» / «До закрытия»)
--      по-прежнему выбирает админ.
--
--  Права (проверяет база): видит, добавляет, меняет и удаляет пункты только сам действующий
--  админ (admins: active, role admin/chief) — свои; создатель (chief) видит все. Без входа
--  не видно ничего. Не больше 20 пунктов у одного админа.
--  Клиентам пункты пока не показываются. Сайт не меняется.
--
--  Нужен SQL 10.77 (20261014_rent_end_mode.sql) — без него пункты работают, но время
--  закрытия в «Срок аренды» не связывается (часть 4 пропускается).
--
--  Безопасно: CREATE ... IF NOT EXISTS / CREATE OR REPLACE; данные не удаляются.
--  Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  ПРОВЕРКА:  select count(*) from public.iva_pickup_points;   -- 0, пока админы не добавили
--  ОТКАТ (пункты исчезнут из приложения; время закрытия «Срока аренды» снова меняется вручную):
--      DROP TABLE IF EXISTS public.iva_pickup_points CASCADE;
--      DROP FUNCTION IF EXISTS public.iva_pickup_sync_rent(text);
--      DROP FUNCTION IF EXISTS public.iva_pickup_hours_ok(int[], int[]);
--      DROP FUNCTION IF EXISTS public.iva_pickup_is_admin();
--      DROP FUNCTION IF EXISTS public.iva_pickup_before() CASCADE;
--      DROP FUNCTION IF EXISTS public.iva_pickup_after() CASCADE;
--    и снова выполнить часть 3 из 20261014_rent_end_mode.sql (прежняя iva_rent_settings_set).
-- ============================================================

-- 1. Проверки
-- График: 7 дней (Пн…Вс), минуты от полуночи; выходной — открытие и закрытие = -1;
-- рабочий день — 00:00 ≤ открытие < закрытие ≤ 23:59; хотя бы один рабочий день.
CREATE OR REPLACE FUNCTION public.iva_pickup_hours_ok(p_open int[], p_close int[])
 RETURNS boolean
 LANGUAGE sql
 IMMUTABLE
AS $function$
  select p_open is not null and p_close is not null
     and array_ndims(p_open) = 1 and array_ndims(p_close) = 1
     and array_length(p_open, 1) = 7 and array_length(p_close, 1) = 7
     and array_position(p_open, NULL) is null and array_position(p_close, NULL) is null
     and (select bool_and((o = -1 and c = -1) or (o >= 0 and o < c and c <= 1439))
                 and bool_or(o >= 0)
            from unnest(p_open, p_close) as d(o, c));
$function$;

CREATE OR REPLACE FUNCTION public.iva_pickup_is_admin()
 RETURNS boolean
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select exists (select 1 from public.admins a
                 where a.active and a.role in ('admin', 'chief')
                   and lower(a.email) = lower(btrim(coalesce(auth.jwt() ->> 'email', '')))
                   and coalesce(auth.jwt() ->> 'email', '') <> '');
$function$;
REVOKE ALL ON FUNCTION public.iva_pickup_is_admin() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_pickup_is_admin() TO authenticated;

-- 2. Пункты выдачи
CREATE TABLE IF NOT EXISTS public.iva_pickup_points (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_email text        NOT NULL DEFAULT lower(btrim(coalesce(auth.jwt() ->> 'email', '')))
                          CHECK (owner_email = lower(btrim(owner_email)) AND owner_email <> ''),
  name        text        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
  address     text        NOT NULL CHECK (char_length(btrim(address)) BETWEEN 5 AND 300),
  city        text        NOT NULL DEFAULT '' CHECK (char_length(city) <= 100),
  lat         double precision,
  lng         double precision,
  open_min    int[]       NOT NULL DEFAULT '{540,540,540,540,540,540,540}',
  close_min   int[]       NOT NULL DEFAULT '{1140,1140,1140,1140,1140,1140,1140}',
  email       text        NOT NULL CHECK (char_length(email) <= 254 AND email ~* '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]{2,}$'),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT iva_pickup_coords CHECK ((lat IS NULL AND lng IS NULL)
                                      OR (lat IS NOT NULL AND lng IS NOT NULL
                                          AND lat BETWEEN -90 AND 90 AND lng BETWEEN -180 AND 180)),
  CONSTRAINT iva_pickup_hours CHECK (public.iva_pickup_hours_ok(open_min, close_min))
);
COMMENT ON TABLE public.iva_pickup_points IS
  'Ива 10.95: пункты выдачи админа (название, адрес, график Пн…Вс, email проката). Первый пункт задаёт время закрытия «Срока аренды».';
CREATE INDEX IF NOT EXISTS idx_iva_pickup_owner ON public.iva_pickup_points (owner_email, created_at, id);

ALTER TABLE public.iva_pickup_points ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.iva_pickup_points FROM public, anon, authenticated;
GRANT SELECT, DELETE ON public.iva_pickup_points TO authenticated;
GRANT INSERT (name, address, city, lat, lng, open_min, close_min, email) ON public.iva_pickup_points TO authenticated;
GRANT UPDATE (name, address, city, lat, lng, open_min, close_min, email) ON public.iva_pickup_points TO authenticated;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_pickup_points' AND policyname = 'iva_pickup_read') THEN
    CREATE POLICY iva_pickup_read ON public.iva_pickup_points FOR SELECT TO authenticated
      USING ((owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) AND public.iva_pickup_is_admin())
             OR public.fn_is_chief());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_pickup_points' AND policyname = 'iva_pickup_insert') THEN
    CREATE POLICY iva_pickup_insert ON public.iva_pickup_points FOR INSERT TO authenticated
      WITH CHECK (owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) AND public.iva_pickup_is_admin());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_pickup_points' AND policyname = 'iva_pickup_update') THEN
    CREATE POLICY iva_pickup_update ON public.iva_pickup_points FOR UPDATE TO authenticated
      USING (owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) AND public.iva_pickup_is_admin())
      WITH CHECK (owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) AND public.iva_pickup_is_admin());
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'iva_pickup_points' AND policyname = 'iva_pickup_delete') THEN
    CREATE POLICY iva_pickup_delete ON public.iva_pickup_points FOR DELETE TO authenticated
      USING (owner_email = lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) AND public.iva_pickup_is_admin());
  END IF;
END $$;

-- Перед записью: аккуратные строки, время изменения, не больше 20 пунктов у админа
CREATE OR REPLACE FUNCTION public.iva_pickup_before()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  new.name := btrim(regexp_replace(new.name, '\s+', ' ', 'g'));
  new.address := btrim(regexp_replace(new.address, '\s+', ' ', 'g'));
  new.city := btrim(coalesce(new.city, ''));
  new.email := lower(btrim(new.email));
  if tg_op = 'UPDATE' then
    new.owner_email := old.owner_email;           -- пункт не переходит к другому админу
    new.created_at := old.created_at;             -- порядок («первый пункт») не меняется
  elsif (select count(*) from public.iva_pickup_points p where p.owner_email = new.owner_email) >= 20 then
    raise exception 'Не больше 20 пунктов выдачи';
  end if;
  new.updated_at := now();
  return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_iva_pickup_before ON public.iva_pickup_points;
CREATE TRIGGER trg_iva_pickup_before BEFORE INSERT OR UPDATE ON public.iva_pickup_points
  FOR EACH ROW EXECUTE FUNCTION public.iva_pickup_before();

-- 3. Время закрытия «Срока аренды» = график первого пункта
CREATE OR REPLACE FUNCTION public.iva_pickup_sync_rent(p_email text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  c int[];
begin
  if to_regclass('public.iva_rent_settings') is null or coalesce(p_email, '') = '' then
    return;                                         -- SQL 10.77 не применён — связывать не с чем
  end if;
  select p.close_min into c from public.iva_pickup_points p
   where p.owner_email = p_email order by p.created_at, p.id limit 1;
  if c is null then
    return;                                         -- пунктов нет — время закрытия не трогаем
  end if;
  execute 'insert into public.iva_rent_settings as s (email, close_min, updated_at) values ($1, $2, now())
           on conflict (email) do update set close_min = excluded.close_min, updated_at = now()'
    using p_email, c;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_pickup_sync_rent(text) FROM public, anon, authenticated;

CREATE OR REPLACE FUNCTION public.iva_pickup_after()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  perform public.iva_pickup_sync_rent(case when tg_op = 'DELETE' then old.owner_email else new.owner_email end);
  return null;
end;
$function$;

DROP TRIGGER IF EXISTS trg_iva_pickup_after ON public.iva_pickup_points;
CREATE TRIGGER trg_iva_pickup_after AFTER INSERT OR UPDATE OR DELETE ON public.iva_pickup_points
  FOR EACH ROW EXECUTE FUNCTION public.iva_pickup_after();

-- 4. «Срок аренды»: при наличии пунктов время закрытия — из первого пункта (как 10.77 + одна проверка)
DO $do$
BEGIN
  IF to_regclass('public.iva_rent_settings') IS NULL THEN
    RAISE NOTICE 'SQL 10.77 (20261014_rent_end_mode.sql) не применён — «Срок аренды» не связан с пунктами';
    RETURN;
  END IF;
  EXECUTE $f$
CREATE OR REPLACE FUNCTION public.iva_rent_settings_set(p_mode text, p_close int[], p_tz text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  z  text := coalesce(nullif(btrim(p_tz), ''), 'Europe/Moscow');
  pc int[];
begin
  if em = '' or not exists (select 1 from public.admins a
                            where a.active and a.role in ('admin', 'chief') and lower(a.email) = em) then
    raise exception 'Настройки аренды меняет только прокат';
  end if;
  if p_mode is null or p_mode not in ('day', 'close') then
    raise exception 'Неизвестный режим аренды';
  end if;
  -- 10.95: есть пункты выдачи — время закрытия из графика первого пункта
  select p.close_min into pc from public.iva_pickup_points p where p.owner_email = em order by p.created_at, p.id limit 1;
  if pc is not null then
    p_close := pc;
  end if;
  if p_close is null or array_ndims(p_close) is distinct from 1 or array_length(p_close, 1) is distinct from 7
     or array_position(p_close, NULL) is not null or not (-1 <= all (p_close) and 1439 >= all (p_close)) then
    raise exception 'Время закрытия: 7 дней, от 00:00 до 23:59 или выходной';
  end if;
  if not exists (select 1 from pg_timezone_names where name = z) then
    z := 'Europe/Moscow';
  end if;
  insert into public.iva_rent_settings as s (email, mode, close_min, tz, updated_at)
  values (em, p_mode, p_close, z, now())
  on conflict (email) do update set mode = excluded.mode, close_min = excluded.close_min,
                                    tz = excluded.tz, updated_at = now();
end;
$function$;
$f$;
  EXECUTE 'REVOKE ALL ON FUNCTION public.iva_rent_settings_set(text, int[], text) FROM public, anon';
  EXECUTE 'GRANT EXECUTE ON FUNCTION public.iva_rent_settings_set(text, int[], text) TO authenticated';
END $do$;

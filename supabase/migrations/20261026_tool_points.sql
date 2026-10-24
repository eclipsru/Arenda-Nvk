-- ============================================================
--  Ива 10.101 — этап 1 «Сотрудники и пункты выдачи»: инструмент закреплён за пунктом выдачи
--  (решения владельца 16–17.10.2026, docs/СОТРУДНИКИ-И-ПУНКТЫ.md).
--
--  • У инструмента — пункт выдачи: tools.point_id → iva_pickup_points(id).
--  • Адрес, город и координаты инструмента берутся из пункта: база сама переписывает
--    tools.pickup_address / pickup_city / pickup_lat / pickup_lng (поиск по городу и расстояние работают
--    как раньше). Поменяли адрес пункта — меняется у всего инструмента этого пункта.
--  • Пункт можно поставить только свой (пункт владельца инструмента) — иначе база не сохранит.
--  • Новый инструмент без пункта и без своего адреса (форма приложения адрес не присылает) — на первый пункт
--    владельца (самый ранний добавленный); пунктов нет — как раньше.
--  • Уже выложенный инструмент: без адреса (добавлен из приложения) — на первый пункт владельца, сейчас и когда
--    владелец добавит первый пункт. Со своим адресом (с сайта, может быть в другом городе) — НЕ трогается,
--    пункт ему ставится в правке инструмента.
--  • Пункт, за которым есть инструмент, удалить нельзя (сначала перенести инструмент в другой пункт).
--  • iva_tool_points(ids) — всем, и без входа: название, адрес, город, график и телефон пунктов, за которыми есть
--    инструмент (для карточки инструмента). Email пункта и владелец не отдаются. Сами пункты по-прежнему видит и
--    меняет только их админ (политики 10.95 не меняются).
--
--  Нужны SQL 10.95 (20261023_pickup_points.sql) и 10.99 (20261025_pickup_tel.sql) — без них файл остановится
--  с понятной ошибкой. Без этого SQL приложение 10.101 работает как 10.100 (поля «Пункт выдачи» нет).
--
--  Безопасно: ADD COLUMN IF NOT EXISTS / CREATE OR REPLACE; данные не удаляются; публикации ВКонтакте не
--  повторяются (они только при добавлении инструмента); тексты объявлений не меняются. Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  ПРОВЕРКА (в конце файла — все строки «✅»):
--      select count(*) filter (where point_id is not null) as with_point, count(*) as all_tools from public.tools;
--  ОТКАТ (пункт у инструмента пропадёт; адреса, уже записанные в инструмент, останутся — как адрес выдачи):
--      DROP TRIGGER IF EXISTS trg_iva_tool_point ON public.tools;
--      DROP TRIGGER IF EXISTS trg_iva_pickup_tools_sync ON public.iva_pickup_points;
--      DROP TRIGGER IF EXISTS trg_iva_pickup_tools_first ON public.iva_pickup_points;
--      DROP FUNCTION IF EXISTS public.iva_tool_points(uuid[]);
--      DROP FUNCTION IF EXISTS public.iva_tool_point_before();
--      DROP FUNCTION IF EXISTS public.iva_pickup_tools_sync();
--      DROP FUNCTION IF EXISTS public.iva_pickup_tools_first();
--      DROP FUNCTION IF EXISTS public.iva_tools_to_first_point(text);
--      DROP FUNCTION IF EXISTS public.iva_first_point(text);
--      ALTER TABLE public.tools DROP COLUMN IF EXISTS point_id;
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

-- 0. Что нужно до этого файла
DO $$
BEGIN
  IF to_regclass('public.iva_pickup_points') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261023_pickup_points.sql (10.95 — пункты выдачи)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'iva_pickup_points' AND column_name = 'tel') THEN
    RAISE EXCEPTION 'Сначала примените 20261025_pickup_tel.sql (10.99 — телефон пункта)';
  END IF;
END $$;

-- 1. Столбцы инструмента (адресные — есть с 23.09 / 12.10; IF NOT EXISTS — на всякий случай)
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS pickup_address text NOT NULL DEFAULT '';
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS pickup_city text NOT NULL DEFAULT '';
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS pickup_lat double precision;
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS pickup_lng double precision;
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS point_id uuid;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.tools'::regclass AND conname = 'tools_point_fk') THEN
    ALTER TABLE public.tools ADD CONSTRAINT tools_point_fk
      FOREIGN KEY (point_id) REFERENCES public.iva_pickup_points(id) ON DELETE RESTRICT;
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_tools_point ON public.tools (point_id);
COMMENT ON COLUMN public.tools.point_id IS
  'Ива 10.101: пункт выдачи инструмента (iva_pickup_points). Адрес, город и координаты инструмента база берёт из пункта.';

-- 2. Первый пункт владельца (самый ранний добавленный)
CREATE OR REPLACE FUNCTION public.iva_first_point(p_email text)
 RETURNS uuid
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id from public.iva_pickup_points p
  where p.owner_email = lower(btrim(coalesce(p_email, '')))
  order by p.created_at, p.id
  limit 1;
$function$;
REVOKE ALL ON FUNCTION public.iva_first_point(text) FROM public, anon, authenticated;

-- 3. Перед записью инструмента: пункт — только свой; адрес, город, координаты — из пункта
CREATE OR REPLACE FUNCTION public.iva_tool_point_before()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  p public.iva_pickup_points%rowtype;
  em text := lower(btrim(coalesce(new.owner_email, '')));
begin
  if tg_op = 'INSERT' and new.point_id is null and btrim(coalesce(new.pickup_address, '')) = '' then
    new.point_id := public.iva_first_point(em);           -- форма приложения адрес не присылает — первый пункт
  end if;
  if new.point_id is null then return new; end if;
  if tg_op = 'UPDATE' and new.point_id is not distinct from old.point_id
     and em = lower(btrim(coalesce(old.owner_email, ''))) then
    return new;                                           -- ни пункт, ни владелец не менялись
  end if;
  select * into p from public.iva_pickup_points where id = new.point_id;
  if not found or p.owner_email <> em then
    if tg_op = 'UPDATE' and new.point_id is not distinct from old.point_id then
      new.point_id := null;                               -- инструмент передали другому владельцу — пункт прежнего снимается
      return new;
    end if;
    raise exception 'Пункт выдачи не найден у владельца инструмента';
  end if;
  new.pickup_address := p.address;
  new.pickup_city := p.city;
  new.pickup_lat := p.lat;
  new.pickup_lng := p.lng;
  return new;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_point_before() FROM public, anon, authenticated;

-- имя после iva_tools_owner_default (триггеры одного вида идут по алфавиту): владелец уже подставлен
DROP TRIGGER IF EXISTS trg_iva_tool_point ON public.tools;
CREATE TRIGGER trg_iva_tool_point
  BEFORE INSERT OR UPDATE OF point_id, owner_email ON public.tools
  FOR EACH ROW EXECUTE FUNCTION public.iva_tool_point_before();

-- 4. Инструмент без адреса — на первый пункт владельца (тексты не меняются — цензура не нужна)
CREATE OR REPLACE FUNCTION public.iva_tools_to_first_point(p_email text)
 RETURNS int
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  pid uuid := public.iva_first_point(p_email);
  n int := 0;
begin
  if pid is null then return 0; end if;
  perform set_config('iva.censor_skip', '1', true);
  update public.tools t set point_id = pid
   where t.point_id is null
     and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(coalesce(p_email, '')))
     and btrim(coalesce(t.pickup_address, '')) = '';
  get diagnostics n = row_count;
  perform set_config('iva.censor_skip', '', true);
  return n;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_to_first_point(text) FROM public, anon, authenticated;

CREATE OR REPLACE FUNCTION public.iva_pickup_tools_first()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  perform public.iva_tools_to_first_point(new.owner_email);
  return null;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_pickup_tools_first() FROM public, anon, authenticated;

DROP TRIGGER IF EXISTS trg_iva_pickup_tools_first ON public.iva_pickup_points;
CREATE TRIGGER trg_iva_pickup_tools_first
  AFTER INSERT ON public.iva_pickup_points
  FOR EACH ROW EXECUTE FUNCTION public.iva_pickup_tools_first();

-- 5. Поменяли адрес пункта — тот же адрес у всего его инструмента
CREATE OR REPLACE FUNCTION public.iva_pickup_tools_sync()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if new.address is not distinct from old.address and new.city is not distinct from old.city
     and new.lat is not distinct from old.lat and new.lng is not distinct from old.lng then
    return null;
  end if;
  perform set_config('iva.censor_skip', '1', true);
  update public.tools t
     set pickup_address = new.address, pickup_city = new.city, pickup_lat = new.lat, pickup_lng = new.lng
   where t.point_id = new.id;
  perform set_config('iva.censor_skip', '', true);
  return null;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_pickup_tools_sync() FROM public, anon, authenticated;

DROP TRIGGER IF EXISTS trg_iva_pickup_tools_sync ON public.iva_pickup_points;
CREATE TRIGGER trg_iva_pickup_tools_sync
  AFTER UPDATE OF address, city, lat, lng ON public.iva_pickup_points
  FOR EACH ROW EXECUTE FUNCTION public.iva_pickup_tools_sync();

-- 6. Для карточки инструмента: пункты по id (только те, за которыми есть инструмент; без email и владельца)
CREATE OR REPLACE FUNCTION public.iva_tool_points(p_ids uuid[])
 RETURNS TABLE (id uuid, name text, address text, city text, open_min int[], close_min int[], tel text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select p.id, p.name, p.address, p.city, p.open_min, p.close_min, p.tel
  from public.iva_pickup_points p
  where p.id = any ((coalesce(p_ids, '{}'::uuid[]))[1:200])
    and exists (select 1 from public.tools t where t.point_id = p.id)
  order by p.created_at, p.id;
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_points(uuid[]) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_tool_points(uuid[]) TO anon, authenticated;

-- 7. Уже выложенный инструмент без адреса — на первый пункт владельца
SELECT public.iva_tools_to_first_point(s.owner_email)
FROM (SELECT DISTINCT owner_email FROM public.iva_pickup_points) s;

NOTIFY pgrst, 'reload schema';

-- 8. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('колонка пункта у инструмента',       EXISTS (SELECT 1 FROM information_schema.columns
                                                 WHERE table_schema = 'public' AND table_name = 'tools' AND column_name = 'point_id')),
  ('пункт с инструментом не удаляется',  EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tools_point_fk' AND confdeltype = 'r')),
  ('триггер инструмента',                EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_tool_point' AND NOT tgisinternal)),
  ('адрес пункта → инструмент',          EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_pickup_tools_sync' AND NOT tgisinternal)),
  ('первый пункт → инструмент без адреса', EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_pickup_tools_first' AND NOT tgisinternal)),
  ('пункты для карточки — всем',         has_function_privilege('anon', 'public.iva_tool_points(uuid[])', 'EXECUTE')),
  ('служебные функции закрыты',          NOT has_function_privilege('authenticated', 'public.iva_tools_to_first_point(text)', 'EXECUTE'))
) AS x(check_name, ok);

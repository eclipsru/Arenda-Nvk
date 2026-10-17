-- ============================================================
--  Ива · 10.113 — «Инструменты сотрудников» у каждого пункта выдачи отдельно; email проката у пункта — необязателен
--  (просьба владельца 17.10.2026).
--  Применять ПОСЛЕ 20261030_staff_tools.sql (SQL 10.105: инструменты сотрудников).
--
--  Что делает:
--   • iva_pickup_points.staff_publish — у каждого пункта свой выключатель: 'approve' (новые инструменты сотрудников
--     этого пункта клиенты видят после одобрения владельца, так по умолчанию) / 'now' (сразу). При первом запуске
--     у всех пунктов ставится то, что владелец выбрал раньше общей строкой «Инструменты сотрудников»;
--   • триггер инструментов сотрудника (iva_tools_staff_before) берёт режим из пункта нового инструмента.
--     Всё остальное в триггере — без изменений (как в 20261030_staff_tools.sql). Общий режим admins.staff_publish
--     остаётся запасным (если у пункта почему-то пусто) — старые версии приложения его по-прежнему меняют;
--   • email проката у пункта больше не обязателен (поле убрано из формы). Уже введённые email не удаляются.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ (снова общий режим «Инструменты сотрудников» на весь прокат; email пункта остаётся необязательным):
--      ALTER TABLE public.iva_pickup_points DROP COLUMN IF EXISTS staff_publish;
--      NOTIFY pgrst, 'reload schema';
--    затем снова выполнить ВЕСЬ файл 20261030_staff_tools.sql (его можно запускать повторно) — вернётся прежний триггер.
--    Email обязательным обратно не делаем: у новых пунктов его нет, а старые версии приложения вписывают его сами.
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.iva_pickup_points') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261023_pickup_points.sql (SQL 10.95: пункты выдачи)';
  END IF;
  IF to_regprocedure('public.iva_tools_staff_before()') IS NULL
     OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'admins' AND column_name = 'staff_publish') THEN
    RAISE EXCEPTION 'Сначала примените 20261030_staff_tools.sql (SQL 10.105: инструменты сотрудников)';
  END IF;
END $do$;

-- 1. Выключатель у пункта; при первом запуске — прежний общий режим владельца
DO $do$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'iva_pickup_points' AND column_name = 'staff_publish') THEN
    ALTER TABLE public.iva_pickup_points ADD COLUMN staff_publish text NOT NULL DEFAULT 'approve';
    UPDATE public.iva_pickup_points p
       SET staff_publish = 'now'
     WHERE EXISTS (SELECT 1 FROM public.admins a
                    WHERE lower(btrim(a.email)) = p.owner_email AND a.staff_publish = 'now');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'public.iva_pickup_points'::regclass AND conname = 'iva_pickup_staff_publish_check') THEN
    ALTER TABLE public.iva_pickup_points ADD CONSTRAINT iva_pickup_staff_publish_check CHECK (staff_publish IN ('now', 'approve'));
  END IF;
END $do$;
COMMENT ON COLUMN public.iva_pickup_points.staff_publish IS
  'Ива 10.113: новые инструменты сотрудников этого пункта — approve (после одобрения владельца) / now (сразу).';
GRANT INSERT (staff_publish), UPDATE (staff_publish) ON public.iva_pickup_points TO authenticated;

-- 2. Email проката у пункта — необязателен (проверка формата остаётся для введённых)
ALTER TABLE public.iva_pickup_points ALTER COLUMN email DROP NOT NULL;
COMMENT ON TABLE public.iva_pickup_points IS
  'Ива 10.95: пункты выдачи админа (название, адрес, график Пн…Вс; email — с 10.113 необязателен). Первый пункт задаёт время закрытия «Срока аренды».';

-- 3. Триггер инструментов сотрудника: режим — из пункта (остальное — как в 20261030_staff_tools.sql)
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
    -- 10.113: режим — у пункта; запасной — прежний общий режим владельца
    select pp.staff_publish into mode
      from public.iva_pickup_points pp where pp.id = new.point_id;
    if mode is null then
      select coalesce(max(a.staff_publish), 'approve') into mode
        from public.admins a where lower(btrim(a.email)) = sowner and a.active;
    end if;
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

NOTIFY pgrst, 'reload schema';

-- 4. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('выключатель у пункта (staff_publish), владелец может менять',
     EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = 'iva_pickup_points' AND column_name = 'staff_publish')
     AND has_column_privilege('authenticated', 'public.iva_pickup_points', 'staff_publish', 'UPDATE')
     AND has_column_privilege('authenticated', 'public.iva_pickup_points', 'staff_publish', 'INSERT')
     AND NOT has_column_privilege('anon', 'public.iva_pickup_points', 'staff_publish', 'UPDATE')),
  ('email проката у пункта — необязателен',
     EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = 'iva_pickup_points' AND column_name = 'email' AND is_nullable = 'YES')),
  ('триггер берёт режим из пункта',
     position('pp.staff_publish' in pg_get_functiondef('public.iva_tools_staff_before()'::regprocedure)) > 0
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'trg_iva_staff_tool' AND NOT tgisinternal)),
  ('триггер закрыт для вызова',
     NOT has_function_privilege('authenticated', 'public.iva_tools_staff_before()', 'EXECUTE'))
) AS x(check_name, ok);

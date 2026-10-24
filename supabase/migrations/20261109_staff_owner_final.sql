-- ============================================================
--  Ива · 10.118 — исправление: сотрудник не может опубликовать инструмент («база не пустила запись»).
--  Применять ПОСЛЕ 20261108_staff_publish_probe.sql (и 20261105). Новая сборка приложения не нужна.
--
--  Причина (таблица 20261108 из рабочей базы, 17.10.2026): в базе есть триггер trg_tool_owner → fn_tool_owner.
--  Его нет в обновлениях «Ивы» (создан в базе раньше). Он срабатывает ПОСЛЕ триггера сотрудника (триггеры одного
--  вида идут по алфавиту: trg_iva_… → trg_tool_…) и ставит владельцем объявления почту того, кто публикует.
--  У владельца и админов это ничего не меняет (они и так владельцы своих объявлений). А у сотрудника владелец должен
--  быть прокатом владельца: триггер сотрудника это ставит, trg_tool_owner перезаписывает на почту сотрудника,
--  и правило доступа tools_staff_insert («объявление — в пункте сотрудника, прокат владельца») не пускает.
--
--  Что делает (ничего не удаляет):
--   • триггер zz_iva_staff_owner — последний перед записью (после trg_tool_owner и любых других): ТОЛЬКО если
--     публикует принятый сотрудник с правом «Выкладывает инструмент» (не админ), возвращает то, что поставил триггер
--     сотрудника: владелец — прокат владельца, «добавил» — этот сотрудник. При правке сотрудником — владелец прежний.
--     Владельца, админов, создателя и всех остальных не касается. trg_tool_owner остаётся как есть.
--   • правила доступа не меняются: запись по-прежнему проверяет tools_staff_insert (пункт сотрудника + прокат владельца).
--   • внизу таблица: порядок триггеров, текст fn_tool_owner (для сведения) и пробная запись за каждого сотрудника —
--     должна стать «✅».
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--
--  ОТКАТ (сотрудник снова не сможет публиковать, пока в базе есть trg_tool_owner):
--      DROP TRIGGER IF EXISTS zz_iva_staff_owner ON public.tools;
--      DROP FUNCTION IF EXISTS public.iva_tools_staff_owner_final();
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_tools_staff_before()') IS NULL OR to_regclass('public.iva_staff') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261030_staff_tools.sql (SQL 10.105: инструменты сотрудников)';
  END IF;
  IF to_regprocedure('public.iva_staff_publish_try()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261108_staff_publish_probe.sql (SQL 10.118: пробная запись)';
  END IF;
END $do$;

-- 1. Последнее слово — за триггером сотрудника (условие — то же, что в iva_tools_staff_before)
CREATE OR REPLACE FUNCTION public.iva_tools_staff_owner_final()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  sid uuid;
  sowner text;
begin
  if em = '' or exists (select 1 from public.admins a
                         where a.active and a.role in ('admin', 'chief') and lower(btrim(a.email)) = em) then
    return new;                                           -- владелец, админ, создатель, база — не трогаем
  end if;
  select s.id, s.owner_email into sid, sowner
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
   where s.email = em and s.status = 'active' and s.can_catalog
   limit 1;
  if sid is null then return new; end if;                 -- не сотрудник с правом «Выкладывает» — не трогаем
  if tg_op = 'INSERT' then
    new.owner_email := sowner;                            -- инструмент сотрудника — инструмент проката владельца
    new.staff_id := sid;
  else
    new.owner_email := old.owner_email;                   -- правка сотрудником: владелец не меняется
    new.staff_id := old.staff_id;
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_staff_owner_final() FROM public, anon, authenticated;

-- «zz_…» — после trg_tool_owner и других, но перед zzz_iva_probe (проба видит итоговую строку)
DROP TRIGGER IF EXISTS zz_iva_staff_owner ON public.tools;
CREATE TRIGGER zz_iva_staff_owner
  BEFORE INSERT OR UPDATE ON public.tools
  FOR EACH ROW EXECUTE FUNCTION public.iva_tools_staff_owner_final();

-- 2. Таблица: порядок триггеров, fn_tool_owner для сведения, пробная запись за каждого сотрудника
DROP TABLE IF EXISTS pg_temp.iva_fix_out;
CREATE TEMP TABLE iva_fix_out (ord int, n int, check_name text, result text);

DO $do$
DECLARE
  s record;
  r jsonb;
  uid text;
  k int := 0;
BEGIN
  INSERT INTO iva_fix_out
  SELECT 10, row_number() OVER (ORDER BY t.tgname)::int,
         'перед записью (по порядку): ' || t.tgname,
         CASE WHEN t.tgname = 'zz_iva_staff_owner' THEN '✅ новый — последнее слово за сотрудником'
              WHEN t.tgname IN ('iva_tools_owner_default', 'tr_tools_censor', 'trg_iva_staff_tool', 'trg_iva_tool_paused',
                                'trg_iva_tool_point', 'zzz_iva_probe') THEN '✅'
              ELSE '⚠️ не из «Ивы» (оставлен): ' || t.tgfoid::regproc::text END
    FROM pg_trigger t
   WHERE t.tgrelid = 'public.tools'::regclass AND NOT t.tgisinternal
     AND (t.tgtype & 2) = 2 AND (t.tgtype & 4) = 4;

  INSERT INTO iva_fix_out VALUES (20, 0, 'новый триггер — после всех остальных (кроме пробы)',
    CASE WHEN NOT EXISTS (SELECT 1 FROM pg_trigger t
                           WHERE t.tgrelid = 'public.tools'::regclass AND NOT t.tgisinternal
                             AND (t.tgtype & 2) = 2 AND (t.tgtype & 4) = 4
                             AND t.tgname > 'zz_iva_staff_owner' AND t.tgname <> 'zzz_iva_probe')
         THEN '✅' ELSE '❌ есть триггер после него' END);

  IF to_regprocedure('public.fn_tool_owner()') IS NOT NULL THEN
    INSERT INTO iva_fix_out
    SELECT 30, 0, 'для сведения: trg_tool_owner',
           left(regexp_replace(pg_get_triggerdef(t.oid), '\s+', ' ', 'g'), 300)
      FROM pg_trigger t WHERE t.tgrelid = 'public.tools'::regclass AND t.tgname = 'trg_tool_owner';
    INSERT INTO iva_fix_out VALUES (31, 0, 'для сведения: текст fn_tool_owner',
      left(regexp_replace((SELECT p.prosrc FROM pg_proc p WHERE p.oid = 'public.fn_tool_owner()'::regprocedure), '\s+', ' ', 'g'), 600));
  END IF;

  FOR s IN
    SELECT st.email FROM public.iva_staff st
     WHERE st.status = 'active' AND st.can_catalog
     ORDER BY st.email
  LOOP
    k := k + 1;
    r := NULL;
    uid := NULL;
    IF to_regclass('auth.users') IS NOT NULL THEN
      EXECUTE 'SELECT id::text FROM auth.users WHERE lower(btrim(email)) = $1 LIMIT 1' INTO uid USING s.email;
    END IF;
    BEGIN
      PERFORM set_config('request.jwt.claims',
                         jsonb_build_object('email', s.email, 'role', 'authenticated', 'sub', coalesce(uid, ''))::text, true);
      EXECUTE 'SET LOCAL ROLE authenticated';
      r := public.iva_staff_publish_try();
      RAISE EXCEPTION USING ERRCODE = 'IVAOK', MESSAGE = 'iva-probe-ok';       -- вернуть роль и вход как были
    EXCEPTION WHEN OTHERS THEN
      IF SQLSTATE <> 'IVAOK' THEN
        r := jsonb_build_object('code', 'error', 'text', 'проба не запустилась: ' || SQLERRM, 'raw', SQLSTATE);
      END IF;
    END;
    INSERT INTO iva_fix_out VALUES (40, k, 'пробная запись: сотрудник ' || s.email,
      CASE WHEN r ->> 'code' = 'ok' THEN '✅ проходит — сотрудник может публиковать'
           ELSE '❌ ' || coalesce(r ->> 'text', '—') END
      || CASE WHEN coalesce(r ->> 'point', '') <> '' THEN ' · пункт: ' || (r ->> 'point') ELSE '' END
      || CASE WHEN coalesce(r ->> 'raw', '') <> '' THEN ' · ответ базы: ' || (r ->> 'raw') ELSE '' END);
  END LOOP;
  IF k = 0 THEN
    INSERT INTO iva_fix_out VALUES (40, 0, 'пробная запись', '⚠️ нет сотрудников с правом «Выкладывает инструмент» (принявших приглашение)');
  END IF;
END $do$;

SELECT check_name, result FROM iva_fix_out ORDER BY ord, n, check_name;

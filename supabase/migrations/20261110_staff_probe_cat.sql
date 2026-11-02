-- ============================================================
--  Ива · 10.118 — пробная запись сотрудника: правильная категория (после 20261109).
--  Применять ПОСЛЕ 20261109_staff_owner_final.sql. Новая сборка приложения не нужна.
--
--  Что показала таблица 20261109 из рабочей базы (17.10.2026): триггер zz_iva_staff_owner стоит последним, а проба
--  сотрудника ответила «23503: … violates foreign key constraint "tools_cat_fkey"». Это уже НЕ отказ правил доступа:
--  база проверяет правила доступа (RLS) раньше ссылок (внешних ключей) — до проверки ссылок запись доходит, только
--  если правила её пустили (проверено на PostgreSQL: «правило нет + ссылка нет» → 42501, «правило да + ссылка нет»
--  → 23503). То есть запись сотрудника правила доступа теперь пускают. А категория пробного объявления (взятая
--  из уже выложенных инструментов, иначе «drill») в рабочей базе не нашлась в справочнике, на который ссылается
--  tools_cat_fkey (его тоже нет в обновлениях «Ивы»). В настоящем объявлении категорию выбирают в форме из
--  справочника, поэтому настоящая публикация на этом не споткнётся.
--
--  Что делает (данные не меняет, ничего не удаляет):
--   • iva_probe_fix_refs(jsonb) — для пробы берёт категорию (и подкатегорию) из того справочника, на который
--     в базе ссылается tools (смотрит сами ссылки таблицы): предпочтительно ту, что уже есть у выложенных
--     инструментов; пустую подкатегорию, которой нет в справочнике, не передаёт. Отдаёт только ключи категорий.
--   • iva_staff_publish_try() — та же проба, что в 20261108, но с этой категорией; если база всё же откажет
--     по ссылке — честно говорит «правила доступа пускают, не прошла проверка ссылок» (код data).
--   • внизу таблица: ссылки таблицы tools и пробная запись за каждого сотрудника — должна стать «✅ проходит».
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--
--  ОТКАТ (проба снова как в 20261108): выполнить ещё раз 20261108_staff_publish_probe.sql целиком, затем
--      DROP FUNCTION IF EXISTS public.iva_probe_fix_refs(jsonb);
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_staff_publish_try()') IS NULL OR to_regprocedure('public.iva_staff_probe_row(jsonb)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261108_staff_publish_probe.sql (SQL 10.118: пробная запись)';
  END IF;
  IF to_regprocedure('public.iva_tools_staff_owner_final()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261109_staff_owner_final.sql (SQL 10.118: исправление владельца)';
  END IF;
END $do$;

-- 1. Категория пробы — из справочника, на который ссылается tools (только cat / sub / cat_label)
CREATE OR REPLACE FUNCTION public.iva_probe_fix_refs(p_body jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  b jsonb := coalesce(p_body, '{}'::jsonb);
  fk record;
  v text;
  ok boolean;
  pick text;
begin
  for fk in
    select a.attname::text as col, c.confrelid::regclass::text as reft, ra.attname::text as refcol
      from pg_constraint c
      join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
      join pg_attribute ra on ra.attrelid = c.confrelid and ra.attnum = c.confkey[1]
     where c.conrelid = 'public.tools'::regclass and c.contype = 'f' and array_length(c.conkey, 1) = 1
       and a.attname in ('cat', 'sub', 'cat_label')
     order by c.conname
  loop
    v := b ->> fk.col;
    continue when v is null;
    execute format('select exists (select 1 from %s r where r.%I::text = $1)', fk.reft, fk.refcol) into ok using v;
    continue when ok;
    pick := null;
    if fk.col = 'cat' then
      execute format('select r.%1$I::text from %2$s r where exists (select 1 from public.tools t '
                     'where t.active and t.cat::text = r.%1$I::text) limit 1', fk.refcol, fk.reft) into pick;
      if pick is null then
        execute format('select r.%I::text from %s r where r.%I is not null limit 1', fk.refcol, fk.reft, fk.refcol) into pick;
      end if;
    end if;
    b := case when pick is null then b - fk.col else jsonb_set(b, array[fk.col], to_jsonb(pick)) end;
  end loop;
  return b;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_probe_fix_refs(jsonb) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_probe_fix_refs(jsonb) TO authenticated;

-- 2. Пробная запись — как в 20261108, с категорией из справочника и честным ответом на отказ по ссылке
CREATE OR REPLACE FUNCTION public.iva_staff_publish_try()
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  chk jsonb;
  body jsonb;
  cols text;
  ins text;
  st1 text := '';
  msg1 text := '';
  det text := '';
  st2 text := '';
  msg2 text := '';
  snap jsonb;
  info jsonb := '{}'::jsonb;
  pol record;
  res boolean;
  perm_ok boolean := false;
  restr text := '';
  bad text := '';
  extra text := '';
  pname text := '';
  code text;
  txt text;
  tail text := ' Владельцу: выполнить в Supabase 20261108_staff_publish_probe.sql — внизу таблицы будет подробно.';
begin
  chk := public.iva_staff_publish_why();
  if coalesce(chk ->> 'code', '') <> 'ok' then
    return chk || jsonb_build_object('raw', '');            -- не сотрудник / нет права / нет пункта — как в 20261107
  end if;

  -- те же поля, что шлёт форма «Добавить инструмент» (POST /rest/v1/tools); только существующие столбцы
  body := jsonb_build_object('name', 'Проверка публикации', 'active', true,
            'cat', coalesce((select t.cat from public.tools t where t.active and coalesce(t.cat, '') <> '' limit 1), 'drill'),
            'owner_email', em, 'cat_label', '', 'sub', '', 'price', 100, 'deposit', 0,
            'descr', '', 'terms', '', 'delivery', false);
  body := public.iva_probe_fix_refs(body);                  -- 20261110: категория — из справочника, на который ссылается tools
  select string_agg(quote_ident(k), ', ' order by k) into cols
    from jsonb_object_keys(body) k
   where exists (select 1 from pg_attribute a
                  where a.attrelid = 'public.tools'::regclass and a.attname = k and a.attnum > 0 and not a.attisdropped);
  ins := format('insert into public.tools (%s) select %s from jsonb_populate_record(null::public.tools, $1)', cols, cols);

  -- проход 1: строка после всех триггеров (zzz_iva_probe останавливает запись до правил доступа)
  begin
    perform set_config('iva.probe', '1', true);
    execute ins using body;
    raise exception using errcode = 'IVAOK', message = 'iva-probe-ok';
  exception when others then
    get stacked diagnostics st1 = returned_sqlstate, msg1 = message_text, det = pg_exception_detail;
  end;
  if st1 = 'IVAPR' then
    begin
      snap := det::jsonb;
    exception when others then snap := null;
    end;
  end if;

  -- проход 2: настоящая запись (как у приложения) — и откат
  begin
    perform set_config('iva.probe', '', true);
    execute ins using body;
    raise exception using errcode = 'IVAOK', message = 'iva-probe-ok';
  exception when others then
    get stacked diagnostics st2 = returned_sqlstate, msg2 = message_text;
  end;

  -- какие правила доступа на запись пускают эту строку (от имени вошедшего)
  if snap is not null then
    info := coalesce(public.iva_staff_probe_row(snap), '{}'::jsonb);
    pname := coalesce(info ->> 'point_name', '');
    for pol in
      select p.polname::text as name, p.polpermissive as permissive,
             coalesce(pg_get_expr(p.polwithcheck, p.polrelid), pg_get_expr(p.polqual, p.polrelid)) as expr
        from pg_policy p
       where p.polrelid = 'public.tools'::regclass and p.polcmd in ('a', '*')
         and (0::oid = any (p.polroles)
              or exists (select 1 from pg_roles r where r.oid = any (p.polroles) and pg_has_role(current_user, r.oid, 'member')))
       order by p.polname
    loop
      res := null;
      if pol.expr is null then
        res := true;
      else
        begin
          execute format('select coalesce((%s), false) from jsonb_populate_record(null::public.tools, $1) as tools', pol.expr)
            into res using snap;
        exception when others then res := null;
        end;
      end if;
      if pol.permissive then
        perm_ok := perm_ok or coalesce(res, false);
      elsif not coalesce(res, false) then
        restr := restr || case when restr = '' then '' else ', ' end || pol.name;
      end if;
    end loop;
  end if;
  select coalesce(string_agg(t.tgname, ', ' order by t.tgname), '') into extra
    from pg_trigger t
   where t.tgrelid = 'public.tools'::regclass and not t.tgisinternal
     and (t.tgtype & 2) = 2 and (t.tgtype & 4) = 4
     and t.tgname not in ('iva_tools_owner_default', 'tr_tools_censor', 'trg_iva_staff_tool', 'trg_iva_tool_paused',
                          'trg_iva_tool_point', 'zz_iva_staff_owner', 'zzz_iva_probe');

  if st2 = 'IVAOK' then
    code := 'ok';
    txt := 'Пробная запись (те же поля, что у формы) сейчас проходит — база пускает. Опубликуйте ещё раз. '
        || 'Если снова откажет — значит, мешает что-то в самом объявлении: пришлите владельцу снимок этого окна.';
  elsif st1 = '42501' and msg1 not ilike '%row-level security%' then
    code := 'grant';
    txt := 'У входа нет права записи в таблицу объявлений (' || msg1 || ').' || tail;
  elsif st1 not in ('IVAPR', 'IVAOK') then
    code := 'trigger';
    txt := 'Запись остановила проверка базы: ' || msg1;
  elsif st2 = '42501' and msg2 ilike '%row-level security%' then
    if restr <> '' then
      code := 'restrictive';
      txt := 'Не пускает ограничивающее правило доступа к объявлениям (RESTRICTIVE): ' || restr
          || '. Его нет в обновлениях «Ивы» — оно создано в базе отдельно.' || tail;
    elsif snap is null then
      code := 'policy';
      txt := 'Правила доступа не пустили запись, подробности база не отдала.' || tail;
    elsif not perm_ok then
      code := 'policy';
      bad := case
        when not coalesce((info ->> 'staff')::boolean, false) then
          'При записи база не узнала в вас сотрудника с правом «Выкладывает инструмент».'
        when not coalesce((info ->> 'staff_set')::boolean, false) then
          'Триггер сотрудника не отметил объявление как ваше (staff_id пуст)'
          || case when extra <> '' then ' — возможно, его перебивает другой триггер: ' || extra else '' end || '.'
        when not coalesce((info ->> 'owner_ok')::boolean, false) then
          'После всех проверок базы владелец объявления — не ваш прокат'
          || case when coalesce((info ->> 'owner_self')::boolean, false) then ' (подставилась ваша почта)' else '' end
          || case when extra <> '' then '. Его меняет другой триггер: ' || extra else '' end || '.'
        when not coalesce((info ->> 'point_set')::boolean, false) then
          'Пункт выдачи у объявления не подставился.'
        when not coalesce((info ->> 'point_owner_ok')::boolean, false) or not coalesce((info ->> 'point_linked')::boolean, false) then
          'Пункт «' || pname || '» не из ваших пунктов.'
        when not coalesce((info ->> 'right')::boolean, false) then
          'Право «Выкладывает инструмент» по пункту «' || pname || '» не подтвердилось.'
        when not coalesce((info ->> 'tool_ok')::boolean, false) then
          'Проверка «инструмент в пункте сотрудника» вернула «нет» при верных данных.'
        else
          'Данные верные, но правило tools_staff_insert в базе вернуло «нет» — оно отличается от обновления 20261030.'
        end;
      txt := bad || tail;
    else
      code := 'unknown';
      txt := 'Правила доступа строку пускают, но база всё равно отказала.' || tail;
    end if;
  elsif st2 = '42501' then
    code := 'grant';
    txt := 'У входа нет права записи в таблицу объявлений.' || tail;
  elsif st2 = '23503' then
    code := 'data';
    txt := 'Правила доступа запись сотрудника пускают. Пробное объявление не прошло проверку ссылок базы (' || msg2
        || ') — в настоящем объявлении эти поля заполняет форма. Опубликуйте ещё раз.';
  else
    code := 'error';
    txt := 'База ответила ошибкой: ' || msg2;
  end if;
  return jsonb_build_object('code', code, 'text', txt, 'point', pname, 'cat', coalesce(body ->> 'cat', ''),
                            'raw', case when st2 = 'IVAOK' then '' else st2 || ': ' || msg2 end);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_publish_try() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_publish_try() TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 3. Таблица: ссылки таблицы tools и проба за каждого сотрудника
DROP TABLE IF EXISTS pg_temp.iva_probe_out;
CREATE TEMP TABLE iva_probe_out (ord int, n int, check_name text, result text);

DO $do$
DECLARE
  s record;
  r jsonb;
  uid text;
  k int := 0;
BEGIN
  INSERT INTO iva_probe_out VALUES
    (1, 0, 'проба с категорией из справочника — установлена',
     CASE WHEN to_regprocedure('public.iva_probe_fix_refs(jsonb)') IS NOT NULL
               AND (SELECT p.prosrc LIKE '%iva_probe_fix_refs%' FROM pg_proc p WHERE p.oid = 'public.iva_staff_publish_try()'::regprocedure)
               AND has_function_privilege('authenticated', 'public.iva_staff_publish_try()', 'EXECUTE')
               AND NOT has_function_privilege('anon', 'public.iva_probe_fix_refs(jsonb)', 'EXECUTE')
          THEN '✅' ELSE '❌' END),
    (2, 0, 'триггер zz_iva_staff_owner (20261109) — на месте',
     CASE WHEN EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'zz_iva_staff_owner' AND NOT tgisinternal)
          THEN '✅' ELSE '❌ нет — выполните 20261109_staff_owner_final.sql' END);

  INSERT INTO iva_probe_out
  SELECT 20, row_number() OVER (ORDER BY c.conname)::int,
         'ссылка tools (для сведения): ' || c.conname,
         pg_get_constraintdef(c.oid) || CASE WHEN NOT c.convalidated THEN ' · старые строки не проверялись (NOT VALID)' ELSE '' END
    FROM pg_constraint c
   WHERE c.conrelid = 'public.tools'::regclass AND c.contype = 'f';

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
    INSERT INTO iva_probe_out VALUES (10, k, 'пробная запись: сотрудник ' || s.email,
      CASE WHEN r ->> 'code' = 'ok' THEN '✅ проходит'
           WHEN r ->> 'code' = 'data' THEN '⚠️ ' || coalesce(r ->> 'text', '—')
           ELSE '❌ ' || coalesce(r ->> 'text', '—') END
      || CASE WHEN coalesce(r ->> 'point', '') <> '' THEN ' · пункт: ' || (r ->> 'point') ELSE '' END
      || CASE WHEN coalesce(r ->> 'cat', '') <> '' THEN ' · категория пробы: ' || (r ->> 'cat') ELSE '' END
      || CASE WHEN r ->> 'code' <> 'ok' AND coalesce(r ->> 'raw', '') <> '' THEN ' · ответ базы: ' || (r ->> 'raw') ELSE '' END);
  END LOOP;
  IF k = 0 THEN
    INSERT INTO iva_probe_out VALUES (10, 0, 'пробная запись', '⚠️ нет сотрудников с правом «Выкладывает инструмент» (принявших приглашение)');
  END IF;
END $do$;

SELECT check_name, result FROM iva_probe_out ORDER BY ord, n, check_name;

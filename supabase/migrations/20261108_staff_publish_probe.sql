-- ============================================================
--  Ива · 10.118 — у сотрудника «Не опубликовано: база не пустила запись», хотя «В базе всё в порядке: права есть,
--  пункт есть, фото разрешены» (снимок владельца 17.10.2026).
--  Применять ПОСЛЕ 20261107_staff_publish_check.sql (SQL 10.116).
--
--  Почему прежняя проверка «врала». 20261107 смотрит только, ЕСТЬ ли нужное (сотрудник, право, пункт, правило фото).
--  А база отказывает на самой записи: после всех триггеров строка объявления проверяется правилами доступа
--  (RLS) таблицы tools. По файлам обновлений эта цепочка у сотрудника проходит (проверено на стенде), значит
--  в рабочей базе что-то отличается от файлов: лишнее ограничивающее правило, другой триггер, другая версия правила
--  или данные. Угадывать нельзя — этот файл заставляет базу САМУ показать, на каком шаге и почему.
--
--  Что делает:
--   • iva_staff_publish_try() — пробная запись объявления ровно как из приложения (те же поля, тот же вход
--     сотрудника), всегда с откатом: в базе ничего не остаётся, уведомления не уходят. Возвращает код и фразу:
--     прошло / какой триггер остановил / какое правило доступа не пустило и почему (владелец не тот, пункт не тот,
--     право не подтвердилось, ограничивающее правило). Приложение 10.118 показывает это в окне «Не опубликовано».
--     Только про себя; почты, имена и телефоны не отдаются (кроме названия пункта).
--   • триггер zzz_iva_probe — срабатывает ТОЛЬКО во время этой пробы (флаг iva.probe): показывает строку
--     объявления после всех остальных триггеров. Обычные записи не трогает.
--   • внизу — одна таблица: пробная запись за КАЖДОГО сотрудника с правом «Выкладывает инструмент» (результат
--     и точный ответ базы), все правила доступа таблицы tools и триггеры перед записью — в порядке срабатывания.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  Ничего не удаляет и не меняет в данных. В конце — таблица; пришлите её снимок целиком.
--
--  ОТКАТ (окно «Не опубликовано» снова покажет причину по 20261107):
--      DROP TRIGGER IF EXISTS zzz_iva_probe ON public.tools;
--      DROP FUNCTION IF EXISTS public.iva_staff_publish_try();
--      DROP FUNCTION IF EXISTS public.iva_staff_probe_row(jsonb);
--      DROP FUNCTION IF EXISTS public.iva_tools_probe_snap();
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_staff_publish_check(text)') IS NULL
     OR to_regprocedure('public.iva_staff_publish_why()') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261107_staff_publish_check.sql (SQL 10.116: причина словами)';
  END IF;
  IF to_regprocedure('public.iva_staff_tool_ok(uuid, text)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261030_staff_tools.sql (SQL 10.105: инструменты сотрудников)';
  END IF;
END $do$;

-- 1. Строка объявления после всех триггеров — только во время пробы (иначе ничего не делает)
--    Имя «zzz_…»: триггеры одного вида идут по алфавиту — этот последний, перед ним уже отработали все.
CREATE OR REPLACE FUNCTION public.iva_tools_probe_snap()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public'
AS $function$
begin
  if coalesce(current_setting('iva.probe', true), '') = '1' then
    raise exception using errcode = 'IVAPR', message = 'iva-probe-snap', detail = to_jsonb(new)::text;
  end if;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_probe_snap() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS zzz_iva_probe ON public.tools;
CREATE TRIGGER zzz_iva_probe
  BEFORE INSERT ON public.tools
  FOR EACH ROW EXECUTE FUNCTION public.iva_tools_probe_snap();

-- 2. Что в этой строке не так для вошедшего сотрудника (только да/нет и название пункта — без почт)
CREATE OR REPLACE FUNCTION public.iva_staff_probe_row(p_row jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  ro text := lower(btrim(coalesce(p_row ->> 'owner_email', '')));
  sid uuid;
  own text;
  pt uuid;
  pown text;
  pname text;
begin
  if em = '' then return '{}'::jsonb; end if;
  select s.id, s.owner_email into sid, own
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
   where s.email = em and s.status = 'active' and s.can_catalog
   limit 1;
  begin
    pt := nullif(p_row ->> 'point_id', '')::uuid;
  exception when others then pt := null;
  end;
  if pt is not null then
    select pp.owner_email, pp.name into pown, pname from public.iva_pickup_points pp where pp.id = pt;
  end if;
  return jsonb_build_object(
    'staff', sid is not null,
    'staff_set', sid is not null and coalesce(p_row ->> 'staff_id', '') = sid::text,
    'owner_ok', own is not null and ro = own,
    'owner_self', ro = em,
    'point_set', pt is not null,
    'point_name', coalesce(pname, ''),
    'point_owner_ok', pt is not null and own is not null and pown = own,
    'point_linked', pt is not null and sid is not null
                    and exists (select 1 from public.iva_staff_points sp where sp.staff_id = sid and sp.point_id = pt),
    'right', pt is not null and public.iva_staff_right(pt, 'catalog'),
    'tool_ok', public.iva_staff_tool_ok(pt, ro),
    'active', coalesce(p_row ->> 'active', ''),
    'review', coalesce(p_row ->> 'review', ''));
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_probe_row(jsonb) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_probe_row(jsonb) TO authenticated;

-- 3. Пробная запись как из приложения — от имени вошедшего (права и правила — его), всегда с откатом
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
  else
    code := 'error';
    txt := 'База ответила ошибкой: ' || msg2;
  end if;
  return jsonb_build_object('code', code, 'text', txt, 'point', pname,
                            'raw', case when st2 = 'IVAOK' then '' else st2 || ': ' || msg2 end);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_publish_try() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_publish_try() TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 4. Таблица: проба за каждого сотрудника, правила доступа tools, триггеры перед записью
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
    (1, 0, 'пробная запись — функции и триггер пробы',
     CASE WHEN to_regprocedure('public.iva_staff_publish_try()') IS NOT NULL
               AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'zzz_iva_probe' AND NOT tgisinternal)
               AND has_function_privilege('authenticated', 'public.iva_staff_publish_try()', 'EXECUTE')
               AND NOT has_function_privilege('anon', 'public.iva_staff_publish_try()', 'EXECUTE')
          THEN '✅' ELSE '❌' END),
    (2, 0, 'триггер сотрудника — версия',
     (SELECT CASE WHEN p.prosrc LIKE '%iva_staff_pick%' THEN '✅ 20261105 (пункт из формы)'
                  WHEN p.prosrc LIKE '%pp.staff_publish%' THEN '⚠️ 20261104 (без пункта из формы)'
                  ELSE '⚠️ 20261030 (старая)' END
        FROM pg_proc p WHERE p.oid = 'public.iva_tools_staff_before()'::regprocedure));

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
    INSERT INTO iva_probe_out VALUES (10, k, 'проба записи: сотрудник ' || s.email,
      CASE WHEN r ->> 'code' = 'ok' THEN '✅ ' ELSE '❌ ' END || coalesce(r ->> 'text', '—')
      || CASE WHEN coalesce(r ->> 'point', '') <> '' THEN ' · пункт: ' || (r ->> 'point') ELSE '' END
      || CASE WHEN coalesce(r ->> 'raw', '') <> '' THEN ' · ответ базы: ' || (r ->> 'raw') ELSE '' END);
  END LOOP;
  IF k = 0 THEN
    INSERT INTO iva_probe_out VALUES (10, 0, 'проба записи', '⚠️ нет сотрудников с правом «Выкладывает инструмент» (принявших приглашение)');
  END IF;

  INSERT INTO iva_probe_out
  SELECT 20, row_number() OVER (ORDER BY p.polname)::int,
         'правило tools: ' || p.polname || ' (' || CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE'
                                                         WHEN 'd' THEN 'DELETE' ELSE 'ALL' END
         || ', ' || CASE WHEN p.polpermissive THEN 'разрешающее' ELSE 'ОГРАНИЧИВАЮЩЕЕ' END
         || ', ' || coalesce((SELECT string_agg(rl.rolname, ',') FROM pg_roles rl WHERE rl.oid = any (p.polroles)), 'public') || ')',
         CASE WHEN NOT p.polpermissive AND p.polcmd IN ('a', '*') THEN '⚠️ ' ELSE '' END
         || left(coalesce('проверка записи: ' || pg_get_expr(p.polwithcheck, p.polrelid),
                          'условие: ' || pg_get_expr(p.polqual, p.polrelid), '—'), 400)
    FROM pg_policy p
   WHERE p.polrelid = 'public.tools'::regclass;

  INSERT INTO iva_probe_out
  SELECT 30, row_number() OVER (ORDER BY t.tgname)::int,
         'перед записью (по порядку): ' || t.tgname,
         CASE WHEN t.tgname IN ('iva_tools_owner_default', 'tr_tools_censor', 'trg_iva_staff_tool', 'trg_iva_tool_paused',
                                'trg_iva_tool_point', 'zz_iva_staff_owner', 'zzz_iva_probe') THEN '✅ ' ELSE '⚠️ не из «Ивы»: ' END
         || t.tgfoid::regproc::text || CASE WHEN p.prosecdef THEN ' (от имени базы)' ELSE '' END
    FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
   WHERE t.tgrelid = 'public.tools'::regclass AND NOT t.tgisinternal
     AND (t.tgtype & 2) = 2 AND (t.tgtype & 4) = 4;

  INSERT INTO iva_probe_out VALUES (40, 0, 'таблица tools: защита строк (RLS)',
    (SELECT CASE WHEN c.relrowsecurity THEN '✅ включена' ELSE '⚠️ выключена' END
            || CASE WHEN c.relforcerowsecurity THEN ', принудительно' ELSE '' END
       FROM pg_class c WHERE c.oid = 'public.tools'::regclass)),
    (41, 0, 'таблица tools: запись для вошедших (GRANT INSERT)',
     CASE WHEN has_table_privilege('authenticated', 'public.tools', 'INSERT') THEN '✅' ELSE '❌ нет' END);
END $do$;

SELECT check_name, result FROM iva_probe_out ORDER BY ord, n, check_name;

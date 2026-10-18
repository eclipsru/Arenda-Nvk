-- ============================================================
--  Ива · 10.121 — первый пункт из «Данных проката», телефон пункта, правки сотрудников на одобрение,
--  заявки целиком (просьба владельца 18.10.2026).
--  Применять ПОСЛЕ 20261111_prokat_page.sql (страница проката), 20261105_staff_pick_point.sql и
--  20261027_order_points.sql. Сайт не меняется. Без этого файла приложение 10.121 работает как 10.120
--  (заявки — прежним видом, правки сотрудников — сразу, первый пункт не создаётся).
--
--  Что делает:
--   • iva_pickup_first(адрес, город, координаты, график) — «Данные проката» → «Адрес пункта выдачи»: если у проката
--     ещё НЕТ ни одного пункта, создаёт первый пункт с этим адресом. Один раз: отметка admins.first_point_at; когда
--     пункты уже есть (или первый уже создавался) — ничего не делает. Дальше пункт меняют в «Пунктах выдачи»;
--   • iva_point_tel(пункт) — телефон пункта для «Позвонить»: свой номер пункта; нет номера и в пункте ещё нет
--     сотрудника — номер проката из «Данных проката» (org_tel, иначе телефон из «Моих данных»). iva_prokat_points
--     отдаёт этот номер (страница проката, карточка инструмента);
--   • правки сотрудников: если в пункте «Инструменты сотрудников — после моего одобрения», правка уже опубликованного
--     инструмента НЕ меняет его сразу — клиенты видят прежний вариант, правка ждёт владельца (tools.edit_pending).
--     «Опубликовать» в «На одобрении» применяет правку, «Отклонить» — убирает (сотрудник видит «Правка отклонена»).
--     Режим «Публиковать сразу» — правки сразу, как раньше. Показать/скрыть (active) — всегда сразу.
--     Владельцу — отметка «на одобрение» и push, как у нового инструмента;
--   • повторная публикация того же инструмента сотрудником (тот же пункт, название, цена и описание) не создаёт
--     второе объявление — база отвечает «Такой инструмент уже есть…»;
--   • iva_tools_pending() — ещё и правки (столбцы edit, edit_at); iva_tool_review — и для правок;
--     iva_tool_pending_card — правка: что изменилось и как было; iva_staff_tools — сотруднику его правка и её состояние;
--   • iva_order_cards('client' | 'admin' | 'staff') — заявки целиком для приложения: инструменты (фото, срок, цена,
--     залог), когда выдан, правило срока (для таймера), пункт с адресом, графиком и телефоном, сумма, состояние каждой
--     части. Клиенту — свои заявки (без своих ФИО и телефона); владельцу — части своего проката с именем и телефоном
--     клиента и комиссией; сотруднику — части своих пунктов (как iva_staff_parts) с именем и телефоном клиента, без
--     комиссии. Почта клиента и паспортные данные не отдаются никому.
--  Таблицы заявок и правила доступа не меняются: всё — через функции.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--
--  ОТКАТ: Supabase → SQL Editor → вставить ВЕСЬ файл 20261112_v121_rollback.sql → Run (прежние функции 10.105–10.120
--  возвращаются, ждущие правки сотрудников остаются в базе, но не применяются, опубликованные инструменты не меняются).
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.iva_prokat_points(uuid)') IS NULL OR to_regprocedure('public.iva_tool_pending_card(uuid)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261111_prokat_page.sql (SQL 10.120: страница проката)';
  END IF;
  IF to_regprocedure('public.iva_staff_pick_point(uuid)') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261105_staff_pick_point.sql (SQL 10.114: пункт в форме сотрудника)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'order_parts' AND column_name = 'items') THEN
    RAISE EXCEPTION 'Сначала примените 20261027_order_points.sql (SQL 10.102: заявки по пунктам)';
  END IF;
  IF to_regclass('public.iva_rent_settings') IS NULL THEN
    RAISE EXCEPTION 'Сначала примените 20261014_rent_end_mode.sql (SQL 10.77: срок аренды)';
  END IF;
END $do$;

-- 1. Телефон пункта для «Позвонить»: свой; нет — пока в пункте нет сотрудника, номер проката
CREATE OR REPLACE FUNCTION public.iva_point_tel(p_point uuid)
 RETURNS text
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select case
           when coalesce(btrim(p.tel), '') <> '' then btrim(p.tel)
           when exists (select 1 from public.iva_staff_points sp
                          join public.iva_staff s on s.id = sp.staff_id and s.status = 'active'
                         where sp.point_id = p.id) then ''
           else coalesce((select coalesce(nullif(btrim(to_jsonb(ad) ->> 'org_tel'), ''), nullif(btrim(to_jsonb(ad) ->> 'phone'), ''), '')
                            from public.admins ad
                           where lower(btrim(ad.email)) = p.owner_email and ad.active
                           limit 1), '')
         end
    from public.iva_pickup_points p
   where p.id = p_point;
$function$;
REVOKE ALL ON FUNCTION public.iva_point_tel(uuid) FROM public, anon, authenticated;

-- страница проката: то же, что в 10.120, телефон — по правилу выше
CREATE OR REPLACE FUNCTION public.iva_prokat_points(p_point uuid)
 RETURNS TABLE (id uuid, name text, address text, city text, open_min int[], close_min int[], tel text,
                lat double precision, lng double precision, company text, avatar text, tools int, main boolean)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  with o as (
    select p.owner_email as owner
      from public.iva_pickup_points p
     where p.id = p_point
       and exists (select 1 from public.tools t where t.point_id = p.id)
     limit 1
  ), a as (
    select coalesce(nullif(btrim(to_jsonb(ad) ->> 'company'), ''), nullif(btrim(to_jsonb(ad) ->> 'full_name'), ''), 'Прокат') as company,
           coalesce(nullif(btrim(to_jsonb(ad) ->> 'org_avatar'), ''), '') as avatar
      from public.admins ad, o
     where lower(btrim(ad.email)) = o.owner and ad.active
     limit 1
  )
  select p.id, p.name, p.address, coalesce(p.city, ''), p.open_min, p.close_min, coalesce(public.iva_point_tel(p.id), ''),
         p.lat::double precision, p.lng::double precision, a.company, a.avatar,
         (select count(*)::int from public.tools t
           where t.point_id = p.id and coalesce(t.active, false)
             and coalesce(to_jsonb(t) ->> 'review', '') not in ('pending', 'rejected')),
         p.id = p_point
    from o
    join a on true
    join public.iva_pickup_points p on p.owner_email = o.owner
   order by p.created_at, p.id
   limit 50;
$function$;
REVOKE ALL ON FUNCTION public.iva_prokat_points(uuid) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_prokat_points(uuid) TO anon, authenticated;

-- 2. Первый пункт из «Данных проката» — один раз и только если пунктов нет
ALTER TABLE public.admins ADD COLUMN IF NOT EXISTS first_point_at timestamptz;
COMMENT ON COLUMN public.admins.first_point_at IS
  'Ива 10.121: когда «Данные проката» создали первый пункт выдачи (или пункты уже были) — повторно не создаётся.';

CREATE OR REPLACE FUNCTION public.iva_pickup_first(p_address text, p_city text DEFAULT '',
  p_lat double precision DEFAULT NULL, p_lng double precision DEFAULT NULL,
  p_open int[] DEFAULT NULL, p_close int[] DEFAULT NULL)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em  text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  adr text := btrim(regexp_replace(coalesce(p_address, ''), '\s+', ' ', 'g'));
  fa  timestamptz;
  nm  text;
  ok  boolean;
  nid uuid;
  op  int[] := '{540,540,540,540,540,540,540}';
  cl  int[] := '{1140,1140,1140,1140,1140,1140,1140}';
begin
  if em = '' or not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  select true, a.first_point_at,
         coalesce(nullif(btrim(to_jsonb(a) ->> 'company'), ''), 'Пункт выдачи')
    into ok, fa, nm
    from public.admins a
   where lower(btrim(a.email)) = em and a.active
   limit 1
   for update;
  if ok is null then raise exception 'Только владелец проката'; end if;
  if fa is not null or exists (select 1 from public.iva_pickup_points p where p.owner_email = em) then
    update public.admins set first_point_at = coalesce(first_point_at, now()) where lower(btrim(email)) = em and active;
    return null;                                          -- пункты уже есть (или первый уже создавали) — не трогаем
  end if;
  if char_length(adr) < 5 or char_length(adr) > 300 then
    raise exception 'Адрес пункта выдачи — от 5 до 300 символов';
  end if;
  if p_open is not null and p_close is not null and public.iva_pickup_hours_ok(p_open, p_close) then
    op := p_open; cl := p_close;
  end if;
  insert into public.iva_pickup_points (owner_email, name, address, city, lat, lng, open_min, close_min, email)
  values (em, left(nm, 80), adr, left(btrim(coalesce(p_city, '')), 100),
          case when p_lat between -90 and 90 and p_lng between -180 and 180 then p_lat end,
          case when p_lat between -90 and 90 and p_lng between -180 and 180 then p_lng end,
          op, cl, em)
  returning id into nid;
  update public.admins set first_point_at = now() where lower(btrim(email)) = em and active;
  return nid;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_pickup_first(text, text, double precision, double precision, int[], int[]) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_pickup_first(text, text, double precision, double precision, int[], int[]) TO authenticated;

-- 3. Правки сотрудников — на одобрение (если в пункте «после моего одобрения»)
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS edit_pending jsonb;
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS edit_at timestamptz;
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS edit_rejected boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN public.tools.edit_pending IS
  'Ива 10.121: правка сотрудника, которая ждёт одобрения владельца (изменённые поля). Клиенты видят прежний вариант.';
COMMENT ON COLUMN public.tools.edit_rejected IS 'Ива 10.121: владелец отклонил последнюю правку сотрудника.';

CREATE OR REPLACE FUNCTION public.iva_tools_staff_edit()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em   text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  sid  uuid;
  sown text;
  mode text;
  o    jsonb;
  n    jsonb;
  d    jsonb := '{}'::jsonb;
  ep   jsonb;
  k    text;
  sys  text[] := array['id', 'owner_email', 'staff_id', 'review', 'active', 'created_at', 'updated_at',
                       'edit_pending', 'edit_at', 'edit_rejected'];
  gen  text[];
  ed   text[] := array['name', 'cat', 'cat_label', 'sub', 'price', 'deposit', 'descr', 'terms', 'delivery',
                       'delivery_price', 'img', 'imgs'];
begin
  if tg_op = 'UPDATE' and auth.jwt() is null then return new; end if;   -- база сама (одобрение, служебные правки)
  if em = '' or exists (select 1 from public.admins a
                         where a.active and a.role in ('admin', 'chief') and lower(btrim(a.email)) = em) then
    return new;                                           -- владелец, создатель — как раньше
  end if;
  select s.id, s.owner_email into sid, sown
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
   where s.email = em and s.status = 'active' and s.can_catalog
   limit 1;
  if sid is null then return new; end if;

  if tg_op = 'INSERT' then                                -- та же форма ещё раз — не второе объявление
    if exists (select 1 from public.tools t
                where t.staff_id = sid
                  and t.point_id is not distinct from new.point_id
                  and lower(btrim(coalesce(t.name, ''))) = lower(btrim(coalesce(new.name, '')))
                  and coalesce(to_jsonb(t) ->> 'price', '') = coalesce(to_jsonb(new) ->> 'price', '')
                  and coalesce(to_jsonb(t) ->> 'descr', '') = coalesce(to_jsonb(new) ->> 'descr', '')) then
      raise exception 'Такой инструмент уже есть в вашем пункте — откройте его в «Инструментах пункта» и измените';
    end if;
    return new;
  end if;

  if old.review is not null then return new; end if;      -- новый (ждёт или отклонён) — как в 10.105
  select coalesce(to_jsonb(pp) ->> 'staff_publish', '') into mode
    from public.iva_pickup_points pp where pp.id = old.point_id;
  if coalesce(mode, '') = '' then
    select coalesce(max(to_jsonb(a) ->> 'staff_publish'), 'approve') into mode
      from public.admins a where lower(btrim(a.email)) = sown and a.active;
  end if;
  if coalesce(mode, 'approve') <> 'approve' then return new; end if;   -- «Публиковать сразу» — правки сразу

  -- вычисляемые столбцы (если есть) в BEFORE-триггере пустые — их не сравниваем и не возвращаем
  select coalesce(array_agg(c.column_name::text), '{}') into gen
    from information_schema.columns c
   where c.table_schema = 'public' and c.table_name = 'tools' and c.is_generated <> 'NEVER';
  o := to_jsonb(old) - sys - gen;
  n := to_jsonb(new) - sys - gen;
  for k in select jsonb_object_keys(n) loop
    if (o -> k) is distinct from (n -> k) then d := d || jsonb_build_object(k, n -> k); end if;
  end loop;
  if d = '{}'::jsonb then return new; end if;             -- только показать / скрыть — сразу

  ep := coalesce(old.edit_pending, '{}'::jsonb);
  if exists (select 1 from jsonb_object_keys(d) x where x = any (ed)) then
    ep := ep - ed;                                        -- штатная правка присылает форму целиком — она и есть правка
  end if;
  ep := ep || d;
  new := jsonb_populate_record(new, o);                   -- клиенты видят прежний вариант
  new.edit_pending := ep;
  new.edit_at := now();
  new.edit_rejected := false;
  begin
    if to_regprocedure('public.iva_role_ping_touch(text)') is not null then
      execute 'select public.iva_role_ping_touch($1)' using sown;          -- открытое приложение владельца — сразу
    end if;
    if to_regprocedure('public.iva_push_enqueue(text, text)') is not null then
      execute 'select public.iva_push_enqueue($1, $2)' using 'o', sown;    -- закрытое — push «проверь сейчас»
    end if;
  exception when others then null;                        -- отметка не должна мешать правке
  end;
  return new;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_staff_edit() FROM public, anon, authenticated;

-- после trg_iva_tool_point (пункт и адрес уже подставлены), до zz_iva_staff_owner (владелец — последним)
DROP TRIGGER IF EXISTS zy_iva_staff_edit ON public.tools;
CREATE TRIGGER zy_iva_staff_edit BEFORE INSERT OR UPDATE ON public.tools
  FOR EACH ROW EXECUTE FUNCTION public.iva_tools_staff_edit();

-- 4. Владельцу: «На одобрении» — новые инструменты и правки
DROP FUNCTION IF EXISTS public.iva_tools_pending();
CREATE FUNCTION public.iva_tools_pending()
 RETURNS TABLE (id uuid, name text, price text, img text, point_name text, staff_name text, created_at timestamptz,
                edit boolean, edit_at timestamptz)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select x.id, x.name, x.price, x.img, x.point_name, x.staff_name, x.created_at, x.edit, x.edit_at
    from (
      select t.id, coalesce(t.name, '') as name, coalesce(to_jsonb(t) ->> 'price', '') as price,
             coalesce(to_jsonb(t) ->> 'img', '') as img, coalesce(pp.name, '') as point_name, coalesce(s.name, '') as staff_name,
             (to_jsonb(t) ->> 'created_at')::timestamptz as created_at, false as edit, null::timestamptz as edit_at,
             (to_jsonb(t) ->> 'created_at')::timestamptz as at
        from public.tools t
        left join public.iva_pickup_points pp on pp.id = t.point_id
        left join public.iva_staff s on s.id = t.staff_id
       where public.iva_pickup_is_admin()
         and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
         and t.review = 'pending'
      union all
      select t.id, coalesce(t.edit_pending ->> 'name', t.name, ''), coalesce(t.edit_pending ->> 'price', to_jsonb(t) ->> 'price', ''),
             coalesce(t.edit_pending ->> 'img', to_jsonb(t) ->> 'img', ''), coalesce(pp.name, ''), coalesce(s.name, ''),
             (to_jsonb(t) ->> 'created_at')::timestamptz, true, t.edit_at, t.edit_at
        from public.tools t
        left join public.iva_pickup_points pp on pp.id = t.point_id
        left join public.iva_staff s on s.id = t.staff_id
       where public.iva_pickup_is_admin()
         and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'))
         and t.review is null and t.edit_pending is not null
    ) x
   order by x.at desc nulls last, x.id
   limit 100;
$function$;
REVOKE ALL ON FUNCTION public.iva_tools_pending() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tools_pending() TO authenticated;

-- «Опубликовать» / «Отклонить»: новый инструмент — как в 10.105; правка — применить или убрать
CREATE OR REPLACE FUNCTION public.iva_tool_review(p_id uuid, p_ok boolean)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em   text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
  r    text;
  ep   jsonb;
  cols text;
begin
  if not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  update public.tools set active = coalesce(p_ok, false), review = case when p_ok then null else 'rejected' end
   where id = p_id and review = 'pending'
     and lower(btrim(coalesce(owner_email, ''))) = em
  returning case when p_ok then 'published' else 'rejected' end into r;
  if r is not null then return r; end if;

  select t.edit_pending into ep
    from public.tools t
   where t.id = p_id and t.review is null and t.edit_pending is not null
     and lower(btrim(coalesce(t.owner_email, ''))) = em
   for update;
  if ep is null then raise exception 'Инструмент уже не ждёт одобрения — обновите список'; end if;
  if coalesce(p_ok, false) then
    select string_agg(format('%I', c.column_name), ', ' order by c.ordinal_position) into cols
      from information_schema.columns c
     where c.table_schema = 'public' and c.table_name = 'tools' and ep ? c.column_name and c.is_generated = 'NEVER'
       and c.column_name <> all (array['id', 'owner_email', 'staff_id', 'review', 'active', 'created_at',
                                       'edit_pending', 'edit_at', 'edit_rejected']);
    if cols is not null then
      execute format('update public.tools set (%s) = (select %s from jsonb_populate_record(null::public.tools, $1)) where id = $2',
                     cols, cols) using ep, p_id;
    end if;
    update public.tools set edit_pending = null, edit_at = null, edit_rejected = false where id = p_id;
    return 'published';
  end if;
  update public.tools set edit_pending = null, edit_at = null, edit_rejected = true where id = p_id;
  return 'rejected';
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_review(uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_review(uuid, boolean) TO authenticated;

-- карточка «На одобрении»: у правки — новый вариант, что изменилось (changed) и как было (old)
CREATE OR REPLACE FUNCTION public.iva_tool_pending_card(p_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  j  jsonb;
  ep jsonb;
  pn text := '';
  sn text := '';
  mn text := '';
  ex jsonb := '{}'::jsonb;
begin
  if not public.iva_pickup_is_admin() then raise exception 'Только владелец проката'; end if;
  select to_jsonb(t), t.edit_pending, coalesce(pp.name, ''), coalesce(s.name, '')
    into j, ep, pn, sn
    from public.tools t
    left join public.iva_pickup_points pp on pp.id = t.point_id
    left join public.iva_staff s on s.id = t.staff_id
   where t.id = p_id
     and lower(btrim(coalesce(t.owner_email, ''))) = lower(btrim(auth.jwt() ->> 'email'));
  if j is null then raise exception 'Инструмент не найден'; end if;
  if ep is not null and coalesce(j ->> 'review', '') = '' then
    ex := jsonb_build_object('edit', true,
                             'changed', coalesce((select jsonb_agg(k order by k) from jsonb_object_keys(ep) k), '[]'::jsonb),
                             'old', coalesce((select jsonb_object_agg(k, j -> k) from jsonb_object_keys(ep) k), '{}'::jsonb));
    j := j || ep;
    if ep ? 'point_id' then
      select coalesce(pp.name, pn) into pn from public.iva_pickup_points pp where pp.id::text = ep ->> 'point_id';
    end if;
  end if;
  if coalesce(j ->> 'model_id', '') ~ '^[0-9]{1,12}$' and to_regclass('public.iva_models') is not null then
    begin
      execute 'select btrim(coalesce(brand, '''') || '' '' || coalesce(model, '''')) from public.iva_models where id = $1'
        into mn using (j ->> 'model_id')::bigint;
    exception when others then mn := '';
    end;
  end if;
  return (j - 'owner_email' - 'staff_id' - 'edit_pending' - 'edit_at' - 'edit_rejected')
         || jsonb_build_object('point_name', pn, 'staff_name', sn, 'model_text', coalesce(mn, '')) || ex;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_tool_pending_card(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_tool_pending_card(uuid) TO authenticated;

-- сотруднику: инструменты пунктов; его правка — поверх, состояние правки — edit_state
CREATE OR REPLACE FUNCTION public.iva_staff_tools()
 RETURNS SETOF jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select ((to_jsonb(t) || coalesce(t.edit_pending, '{}'::jsonb)) - 'owner_email' - 'staff_id' - 'edit_pending' - 'edit_at' - 'edit_rejected')
         || jsonb_build_object('point_name', pp.name,
                               'mine', t.staff_id is not null and t.staff_id = s.id,
                               'edit_state', case when t.edit_pending is not null then 'pending'
                                                  when coalesce(t.edit_rejected, false) then 'rejected' else '' end)
    from public.iva_staff s
    join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
    join public.iva_staff_points sp on sp.staff_id = s.id
    join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = s.owner_email
    join public.tools t on t.point_id = pp.id and lower(btrim(coalesce(t.owner_email, ''))) = s.owner_email
   where coalesce(auth.jwt() ->> 'email', '') <> ''
     and s.email = lower(btrim(auth.jwt() ->> 'email'))
     and s.status = 'active' and s.can_catalog
   order by case when t.review = 'pending' or t.edit_pending is not null then 0
                 when t.review = 'rejected' or coalesce(t.edit_rejected, false) then 1 else 2 end,
            lower(coalesce(t.name, '')), t.id
   limit 500;
$function$;
REVOKE ALL ON FUNCTION public.iva_staff_tools() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_staff_tools() TO authenticated;

-- 5. Заявки целиком: часть заявки (служебная, вызывается из iva_order_cards)
CREATE OR REPLACE FUNCTION public.iva_order_part_card(p_part bigint, p_fee boolean)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select jsonb_build_object(
           'part_id', p.id::text,
           'order_id', p.order_id::text,
           'status', coalesce(nullif(btrim(p.status), ''), 'new'),
           'created_at', p.created_at,
           'rented_at', p.rented_at,
           'rent_sum', p.rent_sum,
           'fee', case when p_fee then p.fee end,
           'tools_text', coalesce(p.tools_text, ''),
           'company', coalesce(nullif(btrim(to_jsonb(ad) ->> 'company'), ''), nullif(btrim(to_jsonb(ad) ->> 'full_name'), ''), ''),
           'prokat_tel', coalesce(nullif(btrim(to_jsonb(ad) ->> 'org_tel'), ''), nullif(btrim(to_jsonb(ad) ->> 'phone'), ''), ''),
           'prokat_addr', coalesce(btrim(to_jsonb(ad) ->> 'address'), ''),
           'work_hours', coalesce(btrim(to_jsonb(ad) ->> 'work_hours'), ''),
           'point', case when pp.id is null then null else
                      jsonb_build_object('id', pp.id, 'name', pp.name, 'address', pp.address, 'city', coalesce(pp.city, ''),
                                         'tel', coalesce(public.iva_point_tel(pp.id), ''),
                                         'open_min', pp.open_min, 'close_min', pp.close_min) end,
           'rent_mode', coalesce(rs.mode, 'day'),
           'close_min', coalesce(pp.close_min, rs.close_min, '{1140,1140,1140,1140,1140,1140,1140}'::int[]),
           'tz', coalesce(rs.tz, 'Europe/Moscow'),
           'items', coalesce((
              select jsonb_agg(jsonb_build_object(
                       'id', coalesce(e.v ->> 'id', ''),
                       'name', coalesce(nullif(btrim(e.v ->> 'name'), ''), t.name, ''),
                       'price', coalesce(e.v -> 'price', to_jsonb(t) -> 'price'),
                       'days', e.v -> 'days',
                       'img', coalesce(nullif(to_jsonb(t) ->> 'img', ''),
                                       case when jsonb_typeof(to_jsonb(t) -> 'imgs') = 'array' then to_jsonb(t) -> 'imgs' ->> 0 end, ''),
                       'deposit', to_jsonb(t) -> 'deposit',
                       'delivery_price', to_jsonb(t) -> 'delivery_price') order by e.n)
                from jsonb_array_elements(case when jsonb_typeof(p.items) = 'array' then p.items else '[]'::jsonb end)
                     with ordinality as e(v, n)
                left join public.tools t on t.id::text = e.v ->> 'id'), '[]'::jsonb))
    from public.order_parts p
    left join lateral (select x.* from public.admins x
                        where lower(btrim(x.email)) = lower(btrim(p.owner_email)) order by x.active desc limit 1) ad on true
    left join public.iva_pickup_points pp on pp.id = p.point_id
    left join public.iva_rent_settings rs on rs.email = lower(btrim(coalesce(p.owner_email, '')))
   where p.id = p_part;
$function$;
REVOKE ALL ON FUNCTION public.iva_order_part_card(bigint, boolean) FROM public, anon, authenticated;

-- заявки для приложения: клиенту свои, владельцу — части проката, сотруднику — части его пунктов
CREATE OR REPLACE FUNCTION public.iva_order_cards(p_scope text)
 RETURNS SETOF jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  em text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
begin
  if em = '' then return; end if;
  if p_scope = 'client' then
    return query
      select jsonb_build_object('order_id', o.id::text, 'created_at', o.created_at,
               'get_method', coalesce(o.get_method, ''), 'address', coalesce(o.address, ''),
               'comment', coalesce(o.comment, ''), 'days', o.days, 'tools', coalesce(o.tools, ''),
               'parts', coalesce((select jsonb_agg(public.iva_order_part_card(p.id, false) order by p.id)
                                    from public.order_parts p where p.order_id = o.id), '[]'::jsonb))
        from public.orders o
       where lower(btrim(coalesce(o.user_email, ''))) = em
       order by o.created_at desc, o.id desc
       limit 100;
  elsif p_scope = 'admin' then
    if not public.iva_pickup_is_admin() then return; end if;
    return query
      select jsonb_build_object('order_id', o.id::text, 'part_id', p.id::text, 'created_at', o.created_at,
               'get_method', coalesce(o.get_method, ''), 'address', coalesce(o.address, ''),
               'comment', coalesce(o.comment, ''), 'days', o.days, 'tools', coalesce(o.tools, ''),
               'client_name', coalesce(o.name, ''), 'client_tel', coalesce(o.phone, ''),
               'parts', jsonb_build_array(public.iva_order_part_card(p.id, true)))
        from public.order_parts p
        join public.orders o on o.id = p.order_id
       where lower(btrim(coalesce(p.owner_email, ''))) = em
       order by p.id desc
       limit 200;
  elsif p_scope = 'staff' then
    return query
      select x.j from (
        select distinct on (p.id) p.id,
               jsonb_build_object('order_id', o.id::text, 'part_id', p.id::text, 'created_at', o.created_at,
                 'get_method', coalesce(o.get_method, ''), 'address', coalesce(o.address, ''),
                 'comment', coalesce(o.comment, ''), 'days', o.days, 'tools', coalesce(o.tools, ''),
                 'client_name', coalesce(o.name, ''), 'client_tel', coalesce(o.phone, ''),
                 'parts', jsonb_build_array(public.iva_order_part_card(p.id, false))) as j
          from public.iva_staff s
          join public.admins a on lower(btrim(a.email)) = s.owner_email and a.active
          join public.iva_staff_points sp on sp.staff_id = s.id
          join public.iva_pickup_points pp on pp.id = sp.point_id and pp.owner_email = s.owner_email
          join public.order_parts p on p.point_id = sp.point_id and lower(btrim(p.owner_email)) = s.owner_email
          join public.orders o on o.id = p.order_id
         where s.email = em and s.status = 'active' and (s.can_issue or s.can_notify)
           and coalesce(p.archived, false) = false
           and (p.status in ('new', 'rented') or p.created_at > now() - interval '7 days')
         order by p.id desc
      ) x
      order by x.id desc
      limit 200;
  end if;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_order_cards(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_order_cards(text) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 6. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('первый пункт из «Данных проката» — только вошедшим',
     has_function_privilege('authenticated', 'public.iva_pickup_first(text, text, double precision, double precision, integer[], integer[])', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_pickup_first(text, text, double precision, double precision, integer[], integer[])', 'EXECUTE')
     AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'admins' AND column_name = 'first_point_at')),
  ('телефон пункта: свой или номер проката (служебная функция закрыта)',
     NOT has_function_privilege('authenticated', 'public.iva_point_tel(uuid)', 'EXECUTE')
     AND has_function_privilege('anon', 'public.iva_prokat_points(uuid)', 'EXECUTE')),
  ('правки сотрудников: столбцы и триггер (после пункта, до владельца)',
     EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'tools' AND column_name = 'edit_pending')
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.tools'::regclass AND tgname = 'zy_iva_staff_edit' AND NOT tgisinternal)),
  ('«На одобрении» с правками — только владельцу',
     has_function_privilege('authenticated', 'public.iva_tools_pending()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tools_pending()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_tool_review(uuid, boolean)', 'EXECUTE')),
  ('заявки целиком — только вошедшим, служебная часть закрыта',
     has_function_privilege('authenticated', 'public.iva_order_cards(text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_order_cards(text)', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_order_part_card(bigint, boolean)', 'EXECUTE'))
) AS x(check_name, ok);

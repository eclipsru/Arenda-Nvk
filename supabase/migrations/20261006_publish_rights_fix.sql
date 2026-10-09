-- ============================================================================
-- Миграция: «не опубликовано: нет прав на публикацию (код 42501)» — устраняем причину
-- Дата: 2026-10-06. Ветка: arena/01a10a83-arenda-nvk
-- ============================================================================
--
-- СИМПТОМ. Активный админ eclipsik.ru@mail.ru не может выложить объявление:
-- «не опубликовано: нет прав на публикацию (код 42501)». Диагностика
-- tools/diag-junior-admin.sql показала: по базе админ в порядке (active, role=admin,
-- вход с этой почтой есть). Повторный вход в приложение не помог.
--
-- ЧТО ПРОВЕРЕНО. Приложение (разобран ProkatInstrumenta-10.15.apk, метод
-- lambda$setupAddTool$256) шлёт POST /rest/v1/tools с заголовками apikey +
-- Authorization: Bearer <токен> и телом:
--   name, active, cat, owner_email (= userEmail, почта входа в нижнем регистре),
--   cat_label, sub, price, deposit, descr, terms, delivery, delivery_price, img, imgs
-- (поля status и pickup_city не шлёт — у них в базе значения по умолчанию).
-- Сообщение «нет прав…(42501)» появляется только если сервер вернул
-- «row-level security» / 42501 / «permission denied» (android/patches/IvaPatch.java:177-181).
-- Значит отказывает правило доступа tools_admin, а не данные.
--
-- Правило tools_admin (миграция 20260923_landlord_applications.sql) требует:
--   fn_is_admin() И (fn_is_chief() ИЛИ lower(owner_email) = lower(jwt_email()))
-- У главного админа вторая часть не проверяется — поэтому у него публикация проходит,
-- а у обычного админа всё решает сравнение почты и запись в admins. Отказ возможен
-- ровно в четырёх местах, и эта миграция закрывает ВСЕ четыре:
--
--   1) В токене сессии нет/не та почта (устаревший токен, вход по ссылке, смена почты).
--      -> jwt_email() теперь берёт почту из токена, из устаревшей переменной PostgREST,
--         а если и там пусто — из auth.users по идентификатору пользователя.
--   2) В таблице admins почта с невидимым пробелом или в другом регистре.
--      -> сравнение везде через lower(btrim(...)).
--   3) Клиент не прислал owner_email (или прислал пустой / в другом регистре).
--      -> триггер iva_tools_owner_default подставляет почту того, кто публикует.
--         Владелец объявления всегда равен автору — так и задумано правилом.
--   4) У роли authenticated нет права INSERT на таблицу («permission denied for table»).
--      -> права выданы явно.
--
-- ЗАЩИТА НЕ ОСЛАБЛЯЕТСЯ. Публиковать может только активный admin/chief из таблицы
-- admins и только в свою почту (главный админ — в любую). Аноним, обычный пользователь,
-- неактивный админ и попытка опубликовать «в чужую почту» — по-прежнему отказ.
--
-- БЕЗОПАСНОСТЬ ЗАПУСКА. Данные не удаляются: drop policy / drop trigger убирают
-- правило и триггер, которые тут же пересоздаются; drop table / truncate нет.
-- Всё остальное — create or replace / grant. Миграцию можно запускать повторно.
--
-- КАК ПРИМЕНИТЬ: Supabase -> SQL Editor -> New query -> вставить файл целиком -> Run.
-- КАК ПРОВЕРИТЬ (в том же SQL Editor):
--   select tgname from pg_trigger where tgrelid='public.tools'::regclass and not tgisinternal;
--     -- должна появиться строка iva_tools_owner_default
--   select public.iva_whoami();
--     -- вернёт JSON; у владельца площадки is_chief = true
-- КАК ОТКАТИТЬ: блок «ОТКАТ» в конце файла (снимите комментарии и выполните).
-- ============================================================================

begin;

-- ---------------------------------------------------------------------------
-- 1. Почта текущей сессии — надёжно, с нормализацией пробелов и регистра.
--    Функция jwt_email() в репозитории не описана (база старше репозитория), поэтому
--    язык её тела подставляется тот, каким она уже создана: create or replace не умеет
--    менять язык существующей функции, и миграция не должна из-за этого падать.
-- ---------------------------------------------------------------------------
do $do$
declare
  lang text;
  expr text := $f$
      lower(btrim(coalesce(
        nullif(btrim(auth.jwt() ->> 'email'), ''),
        nullif(btrim(current_setting('request.jwt.claim.email', true)), ''),
        (select btrim(u.email) from auth.users u where u.id = auth.uid()),
        ''
      )))
  $f$;
begin
  select l.lanname into lang
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  join pg_language l on l.oid = p.prolang
  where n.nspname = 'public' and p.proname = 'jwt_email' and p.proargtypes::text = '';

  if lang is null or lang = 'sql' then
    execute format('create or replace function public.jwt_email() returns text
                    language sql stable security definer set search_path = public, pg_temp
                    as $fn$ select %s $fn$', expr);
  else
    execute format('create or replace function public.jwt_email() returns text
                    language %I stable security definer set search_path = public, pg_temp
                    as $fn$ begin return %s; end $fn$', lang, expr);
  end if;
end
$do$;
grant execute on function public.jwt_email() to anon, authenticated;

-- ---------------------------------------------------------------------------
-- 2. Кто является админом — то же правило, но невидимый пробел больше не ломает права
-- ---------------------------------------------------------------------------
create or replace function public.fn_is_admin() returns boolean
language sql stable security definer set search_path = public, pg_temp
as $$
  select public.jwt_email() <> ''
     and exists(
           select 1 from public.admins a
           where coalesce(a.active, false)
             and a.role in ('admin', 'chief')
             and lower(btrim(coalesce(a.email, ''))) = public.jwt_email()
         )
$$;
grant execute on function public.fn_is_admin() to anon, authenticated;

-- ---------------------------------------------------------------------------
-- 3. Владелец объявления всегда тот, кто публикует (клиент мог не прислать поле)
-- ---------------------------------------------------------------------------
create or replace function public.iva_tools_owner_default() returns trigger
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  em text := public.jwt_email();
begin
  if em <> '' then
    if coalesce(btrim(new.owner_email), '') = '' then
      new.owner_email := em;                 -- клиент не прислал владельца — подставляем себя
    else
      new.owner_email := lower(btrim(new.owner_email));  -- приводим к тому же виду, что в правиле
    end if;
  end if;
  return new;
end
$$;

drop trigger if exists iva_tools_owner_default on public.tools;
create trigger iva_tools_owner_default
  before insert on public.tools
  for each row execute function public.iva_tools_owner_default();

-- ---------------------------------------------------------------------------
-- 4. Явные права роли authenticated на таблицу инструментов
--    (без этого любой отказ выглядит одинаково: «нет прав»)
-- ---------------------------------------------------------------------------
grant usage on schema public to anon, authenticated;
grant select on public.tools to anon;
grant select, insert, update, delete on public.tools to authenticated;

-- ---------------------------------------------------------------------------
-- 5. Правило доступа пересоздаётся: смысл тот же, но устойчив к пробелам и регистру
-- ---------------------------------------------------------------------------
drop policy if exists tools_admin on public.tools;
create policy tools_admin on public.tools for all to authenticated
  using (
    public.fn_is_admin()
    and (public.fn_is_chief() or lower(btrim(coalesce(owner_email, ''))) = public.jwt_email())
  )
  with check (
    public.fn_is_admin()
    and (public.fn_is_chief() or lower(btrim(coalesce(owner_email, ''))) = public.jwt_email())
  );

-- ---------------------------------------------------------------------------
-- 6. Диагностика (только чтение, ничего не меняет)
--    iva_whoami()      — что база видит про текущую сессию (почему пускает/не пускает)
--    iva_diag_schema() — правило, триггеры, права и определения функций текстом
-- ---------------------------------------------------------------------------
create or replace function public.iva_whoami() returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp
as $$
declare
  res jsonb;
  lim boolean := null;
begin
  res := jsonb_build_object(
    'jwt_role',  coalesce(auth.role(), ''),
    'uid',       auth.uid(),
    'jwt_email', public.jwt_email(),
    'token_has_email_claim', (auth.jwt() ? 'email'),
    'is_admin',  public.fn_is_admin(),
    'is_chief',  public.fn_is_chief(),
    'admins_row', (
      select jsonb_build_object('email', a.email, 'role', a.role, 'active', a.active,
                                'fee_pct', a.fee_pct, 'debt_limit', a.debt_limit)
      from public.admins a
      where lower(btrim(coalesce(a.email, ''))) = public.jwt_email()
      limit 1
    )
  );
  if to_regclass('public.limited_admins') is not null then
    execute 'select exists(select 1 from public.limited_admins l where lower(btrim(coalesce(l.email,''''))) = $1)'
      into lim using public.jwt_email();
    res := res || jsonb_build_object('in_limited_admins', lim);
  end if;
  return res;
end
$$;

create or replace function public.iva_diag_schema() returns text
language plpgsql stable security definer set search_path = public, pg_temp
as $$
declare
  t text := '';
  r record;
begin
  t := t || '=== public.tools: общее ===' || chr(10);
  select t || format('вид=%s, владелец=%s, RLS включена=%s, RLS принудительно=%s',
                     case c.relkind when 'r' then 'таблица' when 'v' then 'представление' when 'p' then 'разделённая таблица' else c.relkind::text end,
                     pg_get_userbyid(c.relowner), c.relrowsecurity, c.relforcerowsecurity) || chr(10)
    into t
  from pg_class c where c.oid = 'public.tools'::regclass;

  t := t || chr(10) || '=== политики RLS на public.tools ===' || chr(10);
  for r in
    select p.polname, p.polcmd,
           pg_get_expr(p.polqual, p.polrelid)      as expr_using,
           pg_get_expr(p.polwithcheck, p.polrelid) as expr_check,
           (select coalesce(string_agg(rl.rolname, ','), 'public')
              from pg_roles rl where rl.oid = any (p.polroles)) as roles
    from pg_policy p where p.polrelid = 'public.tools'::regclass order by p.polname
  loop
    t := t || format('политика «%s» (команда %s, роли %s)' || chr(10) || '  using: %s' || chr(10) || '  with check: %s' || chr(10),
                     r.polname, r.polcmd, r.roles, coalesce(r.expr_using, '—'), coalesce(r.expr_check, '—'));
  end loop;

  t := t || chr(10) || '=== триггеры на public.tools ===' || chr(10);
  for r in
    select pg_get_triggerdef(tg.oid) as d
    from pg_trigger tg where tg.tgrelid = 'public.tools'::regclass and not tg.tgisinternal
  loop
    t := t || r.d || chr(10);
  end loop;

  t := t || chr(10) || '=== права (GRANT) на public.tools ===' || chr(10);
  for r in
    select g.grantee, g.privilege_type
    from information_schema.role_table_grants g
    where g.table_schema = 'public' and g.table_name = 'tools'
    order by g.grantee, g.privilege_type
  loop
    t := t || format('%s: %s', r.grantee, r.privilege_type) || chr(10);
  end loop;

  t := t || chr(10) || '=== столбцы public.tools ===' || chr(10);
  for r in
    select c.column_name, c.data_type, c.is_nullable, c.column_default
    from information_schema.columns c
    where c.table_schema = 'public' and c.table_name = 'tools'
    order by c.ordinal_position
  loop
    t := t || format('%s %s%s%s', r.column_name, r.data_type,
                     case when r.is_nullable = 'NO' then ' NOT NULL' else '' end,
                     coalesce(' default ' || r.column_default, '')) || chr(10);
  end loop;

  t := t || chr(10) || '=== определения функций ===' || chr(10);
  for r in
    select pg_get_functiondef(p.oid) as d
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname in ('jwt_email', 'fn_is_admin', 'fn_is_chief', 'iva_tools_owner_default')
    order by p.proname
  loop
    t := t || r.d || chr(10);
  end loop;

  return t;
end
$$;

revoke all on function public.iva_whoami() from public;
revoke all on function public.iva_diag_schema() from public;
grant execute on function public.iva_whoami() to authenticated, anon;
grant execute on function public.iva_diag_schema() to authenticated;

commit;

-- ============================================================================
-- ОТКАТ (снять комментарии и выполнить — вернёт поведение до этой миграции;
-- правило tools_admin при этом станет таким, каким его сделала миграция
-- 20260923_landlord_applications.sql):
--
-- begin;
-- drop trigger if exists iva_tools_owner_default on public.tools;
-- drop policy if exists tools_admin on public.tools;
-- create policy tools_admin on public.tools for all to authenticated
--   using (public.fn_is_admin() and (public.fn_is_chief() or lower(owner_email) = lower(public.jwt_email())))
--   with check (public.fn_is_admin() and (public.fn_is_chief() or lower(owner_email) = lower(public.jwt_email())));
-- create or replace function public.fn_is_admin() returns boolean
--   language sql stable security definer set search_path = public as $$
--   select exists(select 1 from public.admins a
--                 where a.active and a.role in ('admin','chief')
--                   and lower(a.email) = lower(public.jwt_email())) $$;
-- create or replace function public.jwt_email() returns text
--   language sql stable security definer set search_path = public as $$
--   select lower(coalesce(auth.jwt() ->> 'email', '')) $$;
-- revoke execute on function public.iva_whoami() from authenticated, anon;
-- revoke execute on function public.iva_diag_schema() from authenticated;
-- commit;
-- ============================================================================

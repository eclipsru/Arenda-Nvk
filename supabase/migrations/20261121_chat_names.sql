-- ============================================================
--  Ива · приложение 10.130 — в «Чате» у проката имена клиентов, а не почта (просьба владельца 04.11.2026:
--  «в чатах отображать имена клиентов а не email»).
--
--  Что делает:
--   • iva_chat_peer_names() — администратору (тому, кому клиент писал; создателю — и в чатах других админов,
--     как iva_chat_peers из 20261012) — почта и имя клиента: «Имя Фамилия» из «Настроек профиля»
--     (customer_profiles), если там пусто — имя, указанное при регистрации. Телефон, адрес, паспорт не отдаются.
--     Новых данных не собирается: только то, что клиент сам ввёл в профиле.
--   • Приложение до этого SQL показывает имя только у тех, кто оформлял заявку (поле «ФИО» заявки), остальных — почтой.
--     Без SQL всё работает как раньше.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261121_chat_names_rollback.sql (сообщения и профили не меняются).
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.app_messages') IS NULL OR to_regclass('public.customer_profiles') IS NULL THEN
    RAISE EXCEPTION 'В базе нет app_messages / customer_profiles — это не база «Ивы»';
  END IF;
  IF to_regprocedure('public.iva_is_chat_admin()') IS NULL THEN
    RAISE EXCEPTION 'Нет iva_is_chat_admin() — сначала примените 20261012_chat_tools_avatars.sql';
  END IF;
END
$do$;

CREATE OR REPLACE FUNCTION public.iva_chat_peer_names()
 RETURNS TABLE (email text, name text)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public', 'auth'
AS $function$
  with me as (select lower(btrim(coalesce(auth.jwt() ->> 'email', ''))) as em),
  peers as (
    select distinct lower(btrim(m.from_email)) as pe
      from public.app_messages m, me
     where public.iva_is_chat_admin()
       and me.em <> ''
       and lower(btrim(m.from_email)) <> me.em
       and (lower(btrim(m.to_email)) = me.em
            or (me.em = 'eclips.ru@mail.ru'
                and exists (select 1 from public.admins a where lower(btrim(a.email)) = lower(btrim(m.to_email)))))
  )
  select p.pe,
         left(coalesce(
           nullif(btrim(concat_ws(' ', nullif(btrim(to_jsonb(cp) ->> 'given_name'), ''),
                                       nullif(btrim(to_jsonb(cp) ->> 'surname'), ''))), ''),
           nullif(btrim(u.raw_user_meta_data ->> 'full_name'), ''),
           nullif(btrim(u.raw_user_meta_data ->> 'name'), ''),
           nullif(btrim(u.raw_user_meta_data ->> 'given_name'), '')), 60)
    from peers p
    join auth.users u on lower(btrim(u.email::text)) = p.pe
    left join public.customer_profiles cp on cp.user_id::text = u.id::text
   where not exists (select 1 from public.admins a where lower(btrim(a.email)) = p.pe)
     and coalesce(
           nullif(btrim(concat_ws(' ', nullif(btrim(to_jsonb(cp) ->> 'given_name'), ''),
                                       nullif(btrim(to_jsonb(cp) ->> 'surname'), ''))), ''),
           nullif(btrim(u.raw_user_meta_data ->> 'full_name'), ''),
           nullif(btrim(u.raw_user_meta_data ->> 'name'), ''),
           nullif(btrim(u.raw_user_meta_data ->> 'given_name'), '')) is not null
   limit 2000;
$function$;

REVOKE ALL ON FUNCTION public.iva_chat_peer_names() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_chat_peer_names() TO authenticated;

NOTIFY pgrst, 'reload schema';

SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('имена клиентов в «Чате» — только администраторам, которым писали',
     to_regprocedure('public.iva_chat_peer_names()') IS NOT NULL
     AND has_function_privilege('authenticated', 'public.iva_chat_peer_names()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_chat_peer_names()', 'EXECUTE'))
) AS x(check_name, ok);

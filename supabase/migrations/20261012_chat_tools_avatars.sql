-- 10.54 — чат по инструментам в приложении: аватар клиента для администратора и метка инструмента у голосовых.
-- ПРИМЕНЯЕТ ВЛАДЕЛЕЦ (Supabase → SQL Editor). Агент в боевую базу ничего не записывает (решение №15).
-- Повторный запуск безопасен: create or replace / drop policy if exists / add column if not exists.
--
-- Что даёт:
--  1) iva_chat_peers() — администратору: почта и путь к фото тех клиентов, которые ему писали.
--     Только почта и путь к файлу — имена, телефоны, адреса и паспорт не отдаются (персональные данные не расширяются).
--  2) Политика чтения bucket customer-avatars: файл клиента может скачать администратор, которому этот клиент писал
--     (владелец eclips.ru@mail.ru — если клиент писал любому администратору). Остальным — как раньше (только сам клиент).
--  3) iva_tag_my_messages(ids, tool) — штатная запись голоса в приложении не передаёт tool_id; после отправки приложение
--     помечает СВОИ голосовые последних суток инструментом открытой переписки (только если tool_id пустой и инструмент есть).
-- До применения приложение работает: у клиентов вместо фото — буква, голосовые помечаются только на этом телефоне.
--
-- Откат (если понадобится):
--   drop policy if exists customer_avatars_chat_admin_select on storage.objects;
--   drop function if exists public.iva_chat_peers();
--   drop function if exists public.iva_tag_my_messages(text[], uuid);
--   drop function if exists public.iva_can_see_customer_avatar(text);
--   drop function if exists public.iva_is_chat_admin();

-- Колонка уже есть в боевой базе (проверка CI 12.10.2026: app_messages.tool_id отвечает) — строка ничего не меняет,
-- нужна только для чистой базы. Сайт (assets/sb.js) пишет и читает tool_id так же.
alter table public.app_messages add column if not exists tool_id uuid references public.tools(id) on delete set null;

-- Вызывающий — действующий администратор или владелец. security definer: authenticated не читает admins целиком.
create or replace function public.iva_is_chat_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select lower(coalesce(auth.jwt() ->> 'email', '')) = 'eclips.ru@mail.ru'
      or exists (
        select 1 from public.admins a
        where lower(a.email) = lower(coalesce(auth.jwt() ->> 'email', ''))
          and coalesce(a.active, true)
      );
$$;

-- Может ли вызывающий видеть фото клиента с id p_uid (папка файла = id клиента).
-- Политика RLS не может читать auth.users от имени authenticated — поэтому проверка в security definer функции.
create or replace function public.iva_can_see_customer_avatar(p_uid text)
returns boolean
language sql
stable
security definer
set search_path = public, auth
as $$
  select public.iva_is_chat_admin() and exists (
    select 1
    from auth.users u
    join public.app_messages m on lower(m.from_email) = lower(u.email)
    where u.id::text = p_uid
      and (
        lower(m.to_email) = lower(coalesce(auth.jwt() ->> 'email', ''))
        or (
          lower(coalesce(auth.jwt() ->> 'email', '')) = 'eclips.ru@mail.ru'
          and exists (select 1 from public.admins a where lower(a.email) = lower(m.to_email))
        )
      )
  );
$$;

-- Администратору — почта и путь к фото клиентов, которые ему писали (без имён и прочих данных).
create or replace function public.iva_chat_peers()
returns table (email text, avatar_path text)
language sql
stable
security definer
set search_path = public, auth
as $$
  select distinct on (lower(u.email::text)) lower(u.email::text) as email, p.avatar_path::text as avatar_path
  from public.app_messages m
  join auth.users u on lower(u.email::text) = lower(m.from_email)
  join public.customer_profiles p on p.user_id = u.id
  where public.iva_is_chat_admin()
    and coalesce(p.avatar_path, '') <> ''
    and (
      lower(m.to_email) = lower(coalesce(auth.jwt() ->> 'email', ''))
      or (
        lower(coalesce(auth.jwt() ->> 'email', '')) = 'eclips.ru@mail.ru'
        and exists (select 1 from public.admins a where lower(a.email) = lower(m.to_email))
      )
    )
  order by lower(u.email::text);
$$;

-- Чтение файла фото клиента администратором, которому клиент писал. Свои политики клиента не меняются.
drop policy if exists customer_avatars_chat_admin_select on storage.objects;
create policy customer_avatars_chat_admin_select on storage.objects
  for select to authenticated
  using (
    bucket_id = 'customer-avatars'
    and public.iva_can_see_customer_avatar((storage.foldername(name))[1])
  );

-- Метка инструмента у своих голосовых (штатная запись голоса tool_id не передаёт).
create or replace function public.iva_tag_my_messages(p_ids text[], p_tool uuid)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  n integer := 0;
begin
  if p_tool is null or coalesce(array_length(p_ids, 1), 0) = 0 or array_length(p_ids, 1) > 20 then
    return 0;
  end if;
  if not exists (select 1 from public.tools t where t.id = p_tool) then
    return 0;
  end if;
  update public.app_messages m
     set tool_id = p_tool
   where m.id::text = any (p_ids)
     and lower(m.from_email) = lower(coalesce(auth.jwt() ->> 'email', ''))
     and m.tool_id is null
     and m.created_at > now() - interval '1 day';
  get diagnostics n = row_count;
  return n;
end;
$$;

revoke all on function public.iva_is_chat_admin() from public, anon;
revoke all on function public.iva_can_see_customer_avatar(text) from public, anon;
revoke all on function public.iva_chat_peers() from public, anon;
revoke all on function public.iva_tag_my_messages(text[], uuid) from public, anon;
grant execute on function public.iva_is_chat_admin() to authenticated;
grant execute on function public.iva_can_see_customer_avatar(text) to authenticated;
grant execute on function public.iva_chat_peers() to authenticated;
grant execute on function public.iva_tag_my_messages(text[], uuid) to authenticated;

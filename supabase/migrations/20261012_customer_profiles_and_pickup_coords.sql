-- Ива — приватный профиль клиента и нормализованные точки выдачи.
-- Дата: 2026-10-12
--
-- В customer_profiles хранятся только данные текущего клиента. RLS не даёт
-- арендодателю, anon или другому клиенту читать эту таблицу; публичные карточки
-- инструментов её не используют. Паспортные данные не попадают ни в tools, ни
-- в landlord_profiles.
--
-- Выполняется в Supabase SQL Editor. Повторный запуск безопасен.

begin;

-- Координаты и точный адрес пункта выдачи принадлежат объявлению, а не клиенту.
alter table public.tools add column if not exists pickup_address text not null default '';
alter table public.tools add column if not exists pickup_lat double precision;
alter table public.tools add column if not exists pickup_lng double precision;

alter table public.tools drop constraint if exists tools_pickup_address_length;
alter table public.tools add constraint tools_pickup_address_length
  check (char_length(pickup_address) <= 500) not valid;
alter table public.tools validate constraint tools_pickup_address_length;

alter table public.tools drop constraint if exists tools_pickup_coordinates_pair;
alter table public.tools add constraint tools_pickup_coordinates_pair
  check ((pickup_lat is null and pickup_lng is null)
      or (pickup_lat between -90 and 90 and pickup_lng between -180 and 180)) not valid;
alter table public.tools validate constraint tools_pickup_coordinates_pair;

create index if not exists idx_tools_pickup_city on public.tools (pickup_city);

-- Защищённая запись клиента. user_id — auth.users.id, а не email:
-- смена почты не ломает связь и не раскрывает данные по публичному адресу.
create table if not exists public.customer_profiles (
  user_id          uuid primary key references auth.users(id) on delete cascade,
  surname          text not null default '',
  given_name       text not null default '',
  patronymic       text not null default '',
  phone            text not null default '',
  city             text not null default '',
  address          text not null default '',
  passport_data    jsonb not null default '{}'::jsonb,
  avatar_path      text not null default '',
  updated_at       timestamptz not null default now(),
  constraint customer_profiles_passport_object
    check (jsonb_typeof(passport_data) = 'object')
);

alter table public.customer_profiles enable row level security;
revoke all on public.customer_profiles from public, anon;
grant select, insert, update on public.customer_profiles to authenticated;

drop policy if exists customer_profiles_own_select on public.customer_profiles;
create policy customer_profiles_own_select on public.customer_profiles
  for select to authenticated
  using (user_id = auth.uid());

drop policy if exists customer_profiles_own_insert on public.customer_profiles;
create policy customer_profiles_own_insert on public.customer_profiles
  for insert to authenticated
  with check (user_id = auth.uid());

drop policy if exists customer_profiles_own_update on public.customer_profiles;
create policy customer_profiles_own_update on public.customer_profiles
  for update to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- RPC не принимает user_id: он всегда берётся из JWT. Это используется сайтом
-- и native-приложением, чтобы клиент не мог выбрать чужую запись.
create or replace function public.get_my_customer_profile()
returns setof public.customer_profiles
language sql stable security invoker
set search_path = public, pg_temp
as $$
  select p.* from public.customer_profiles p where p.user_id = auth.uid();
$$;

create or replace function public.save_my_customer_profile(
  p_surname text default '',
  p_given_name text default '',
  p_patronymic text default '',
  p_phone text default '',
  p_city text default '',
  p_address text default '',
  p_passport_data jsonb default '{}'::jsonb,
  p_avatar_path text default ''
)
returns public.customer_profiles
language plpgsql security invoker
set search_path = public, pg_temp
as $$
declare
  result public.customer_profiles;
  clean_passport jsonb;
begin
  if auth.uid() is null then raise exception 'Сначала войдите в аккаунт'; end if;
  if char_length(coalesce(p_surname, '')) > 100
     or char_length(coalesce(p_given_name, '')) > 100
     or char_length(coalesce(p_patronymic, '')) > 100
     or char_length(coalesce(p_phone, '')) > 50
     or char_length(coalesce(p_city, '')) > 100
     or char_length(coalesce(p_address, '')) > 500
     or char_length(coalesce(p_avatar_path, '')) > 300 then
    raise exception 'Одно из полей профиля слишком длинное';
  end if;
  if btrim(coalesce(p_given_name, '')) = '' then
    raise exception 'Укажите имя';
  end if;
  if coalesce(p_passport_data, '{}'::jsonb) is null
     or jsonb_typeof(coalesce(p_passport_data, '{}'::jsonb)) <> 'object' then
    raise exception 'Некорректные паспортные данные';
  end if;
  if coalesce(p_avatar_path, '') <> ''
     and p_avatar_path not like auth.uid()::text || '/%' then
    raise exception 'Некорректный путь аватарки';
  end if;
  -- Разрешаем только поля формы, чтобы в приватной записи не накапливался
  -- произвольный JSON. Пустые значения можно передать и тем самым удалить.
  if exists (
    select 1 from jsonb_object_keys(coalesce(p_passport_data, '{}'::jsonb)) k
    where k not in ('series', 'number', 'issued_by', 'issued_at')
  ) then
    raise exception 'Недопустимое поле паспортных данных';
  end if;
  clean_passport := jsonb_build_object(
    'series', btrim(coalesce(p_passport_data->>'series', '')),
    'number', btrim(coalesce(p_passport_data->>'number', '')),
    'issued_by', btrim(coalesce(p_passport_data->>'issued_by', '')),
    'issued_at', btrim(coalesce(p_passport_data->>'issued_at', ''))
  );
  if clean_passport = '{"series":"","number":"","issued_by":"","issued_at":""}'::jsonb then
    clean_passport := '{}'::jsonb;
  end if;

  insert into public.customer_profiles(
    user_id, surname, given_name, patronymic, phone, city, address,
    passport_data, avatar_path, updated_at
  ) values (
    auth.uid(), btrim(coalesce(p_surname, '')), btrim(coalesce(p_given_name, '')),
    btrim(coalesce(p_patronymic, '')), btrim(coalesce(p_phone, '')),
    btrim(coalesce(p_city, '')), btrim(coalesce(p_address, '')),
    clean_passport, btrim(coalesce(p_avatar_path, '')), now()
  )
  on conflict (user_id) do update set
    surname = excluded.surname,
    given_name = excluded.given_name,
    patronymic = excluded.patronymic,
    phone = excluded.phone,
    city = excluded.city,
    address = excluded.address,
    passport_data = excluded.passport_data,
    avatar_path = excluded.avatar_path,
    updated_at = now()
  returning * into result;
  return result;
end;
$$;

revoke all on function public.get_my_customer_profile() from public, anon;
revoke all on function public.save_my_customer_profile(text,text,text,text,text,text,jsonb,text) from public, anon;
grant execute on function public.get_my_customer_profile() to authenticated;
grant execute on function public.save_my_customer_profile(text,text,text,text,text,text,jsonb,text) to authenticated;

-- Отдельное приватное хранилище аватаров клиента. В URL карточки инструмента
-- и landlord_profiles этот bucket не используется; выдавать файл можно только
-- владельцу через signed URL.
insert into storage.buckets (id, name, public)
values ('customer-avatars', 'customer-avatars', false)
on conflict (id) do update set public = false;

drop policy if exists customer_avatars_own_select on storage.objects;
create policy customer_avatars_own_select on storage.objects
  for select to authenticated
  using (
    bucket_id = 'customer-avatars'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

drop policy if exists customer_avatars_own_insert on storage.objects;
create policy customer_avatars_own_insert on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'customer-avatars'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

drop policy if exists customer_avatars_own_update on storage.objects;
create policy customer_avatars_own_update on storage.objects
  for update to authenticated
  using (
    bucket_id = 'customer-avatars'
    and (storage.foldername(name))[1] = auth.uid()::text
  )
  with check (
    bucket_id = 'customer-avatars'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

drop policy if exists customer_avatars_own_delete on storage.objects;
create policy customer_avatars_own_delete on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'customer-avatars'
    and (storage.foldername(name))[1] = auth.uid()::text
  );

notify pgrst, 'reload schema';
commit;

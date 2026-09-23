-- Public business information only. No login credentials, fees or private application data.
begin;
create table if not exists public.landlord_profiles (
 owner_email text primary key references public.admins(email) on delete cascade,
 details jsonb not null default '{}'::jsonb check(jsonb_typeof(details)='object'),
 locations jsonb not null default '[]'::jsonb check(jsonb_typeof(locations)='array'),
 revision integer not null default 1,
 updated_at timestamptz not null default now()
);
alter table public.landlord_profiles enable row level security;
revoke all on public.landlord_profiles from public,anon,authenticated;
grant select on public.landlord_profiles to anon,authenticated;
grant all on public.landlord_profiles to service_role;
-- admins itself is not publicly readable: resolve visibility without exposing its private columns.
create or replace function public.landlord_profile_visible(p_email text)
returns boolean language sql stable security definer set search_path=public,pg_temp as $$
 select exists(select 1 from public.admins a where a.email=p_email
   and (a.active or lower(a.email)=lower(public.jwt_email()) or public.fn_is_chief()));
$$;
revoke all on function public.landlord_profile_visible(text) from public;
grant execute on function public.landlord_profile_visible(text) to anon,authenticated;
drop policy if exists landlord_profiles_public_read on public.landlord_profiles;
create policy landlord_profiles_public_read on public.landlord_profiles for select to anon,authenticated
 using (public.landlord_profile_visible(owner_email));

create or replace function public.save_landlord_profile(p_details jsonb,p_locations jsonb,p_revision integer)
returns public.landlord_profiles language plpgsql security definer set search_path=public,pg_temp as $$
declare
 em text; row public.landlord_profiles; clean jsonb:='{}'; locs jsonb:='[]'; loc jsonb; item jsonb;
 k text; val text; maxlen integer; seen text[]:='{}'; keys text[];
begin
 if auth.uid() is null then raise exception 'Сначала войдите в аккаунт'; end if;
 select a.email into em from public.admins a join auth.users u on lower(a.email)=lower(u.email)
 where u.id=auth.uid() and a.active for share of a;
 if em is null then raise exception 'Профиль доступен только действующим арендодателям'; end if;
 perform pg_advisory_xact_lock(hashtextextended('landlord_profile:'||em,0));
 select * into row from public.landlord_profiles where owner_email=em for update;
 if coalesce(row.revision,0) is distinct from p_revision then raise exception 'Профиль изменён в другой вкладке. Обновите страницу перед сохранением'; end if;
 if jsonb_typeof(p_details) is distinct from 'object' or jsonb_typeof(p_locations) is distinct from 'array' then
  raise exception 'Некорректный формат профиля'; end if;
 keys:=array['display_name','contact_name','contact_role','phone','public_email','website','vk','description','specialization','experience','delivery_area','delivery_price','delivery_terms','min_rental','deposit_terms','documents','payment_methods','extension_terms','cancellation_terms','return_terms','consumables'];
 if exists(select 1 from jsonb_object_keys(p_details) x where not (x=any(keys))) then raise exception 'Недопустимое поле профиля'; end if;
 foreach k in array keys loop
  if p_details ? k and jsonb_typeof(p_details->k)<>'string' then raise exception 'Поле % должно содержать текст',k; end if;
  val:=btrim(coalesce(p_details->>k,''));
  maxlen:=case when k in ('description','delivery_terms','deposit_terms','documents','extension_terms','cancellation_terms','return_terms','consumables') then 2000 else 300 end;
  if char_length(val)>maxlen then raise exception 'Слишком длинное поле: %',k; end if;
  if k='display_name' and char_length(val)<2 then raise exception 'Укажите название проката'; end if;
  if k='phone' and (val='' or char_length(regexp_replace(val,'[^0-9]','','g')) not between 10 and 15 or val !~ '^\+?[0-9() .-]+$') then raise exception 'Укажите рабочий телефон (10–15 цифр)'; end if;
  if k='public_email' and val<>'' and val !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then raise exception 'Проверьте контактный email'; end if;
  if k in ('website','vk') and val<>'' and val !~ '^https?://[^[:space:]]+$' then raise exception 'Ссылки должны начинаться с https:// или http://'; end if;
  clean:=clean||jsonb_build_object(k,val);
 end loop;
 if jsonb_array_length(p_locations)>20 then raise exception 'Можно добавить до 20 точек выдачи'; end if;
 for loc in select value from jsonb_array_elements(p_locations) loop
  if jsonb_typeof(loc)<>'object' then raise exception 'Некорректная точка выдачи'; end if;
  keys:=array['id','name','city','address','phone','hours','directions'];
  if exists(select 1 from jsonb_object_keys(loc) x where not (x=any(keys))) then raise exception 'Недопустимое поле точки выдачи'; end if;
  item:='{}';
  foreach k in array keys loop
   if loc ? k and jsonb_typeof(loc->k)<>'string' then raise exception 'Поле точки должно содержать текст'; end if;
   val:=btrim(coalesce(loc->>k,''));
   maxlen:=case when k='directions' then 1000 when k in ('address','hours') then 500 else 100 end;
   if char_length(val)>maxlen then raise exception 'Слишком длинное поле точки: %',k; end if;
   if k in ('name','city','address') and char_length(val)<2 then raise exception 'У каждой точки укажите название, город и точный адрес'; end if;
   if k='id' and val !~ '^[a-zA-Z0-9-]{1,80}$' then raise exception 'Некорректный идентификатор точки'; end if;
   if k='phone' and val<>'' and (char_length(regexp_replace(val,'[^0-9]','','g')) not between 10 and 15 or val !~ '^\+?[0-9() .-]+$') then raise exception 'Проверьте телефон точки выдачи'; end if;
   item:=item||jsonb_build_object(k,val);
  end loop;
  if item->>'id'=any(seen) then raise exception 'Повторяющаяся точка выдачи'; end if;
  seen:=array_append(seen,item->>'id');locs:=locs||jsonb_build_array(item);
 end loop;
 insert into public.landlord_profiles(owner_email,details,locations,revision,updated_at)
 values(em,clean,locs,1,now())
 on conflict(owner_email) do update set details=excluded.details,locations=excluded.locations,
 revision=landlord_profiles.revision+1,updated_at=now()
 returning * into row;
 return row;
end $$;
revoke all on function public.save_landlord_profile(jsonb,jsonb,integer) from public,anon;
grant execute on function public.save_landlord_profile(jsonb,jsonb,integer) to authenticated;
notify pgrst,'reload schema';
commit;

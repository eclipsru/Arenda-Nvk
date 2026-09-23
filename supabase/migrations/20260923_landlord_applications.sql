-- Separate landlord onboarding. Applicants never receive an admins row before approval.
-- Apply as database owner. Existing users, listings and accounting records are retained.
begin;

create table if not exists public.landlord_applications (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null unique references auth.users(id) on delete cascade,
 email text not null,
 full_name text not null check(char_length(full_name) between 3 and 150),
 phone text not null check(phone ~ '^\+?[0-9]{10,15}$'),
 business_type text not null check(business_type in ('self_employed','sole_trader','company')),
 region text not null check(char_length(region) between 2 and 100),
 city text not null check(char_length(city) between 2 and 100),
 categories text[] not null check(cardinality(categories) between 1 and 50),
 inventory_count integer not null check(inventory_count between 1 and 100000),
 company text not null default '' check(char_length(company)<=150),
 website text not null default '' check(char_length(website)<=500 and (website='' or website ~ '^https?://[^[:space:]]+$')),
 comment text not null default '' check(char_length(comment)<=2000),
 status text not null default 'pending' check(status in ('pending','changes_requested','rejected','approved')),
 review_comment text not null default '',
 reviewed_by uuid references auth.users(id) on delete set null,
 reviewed_at timestamptz,
 fee_pct integer check(fee_pct between 0 and 100),
 debt_limit numeric check(debt_limit between 0 and 10000000),
 terms_version text not null,
 consent_version text not null,
 accepted_at timestamptz not null default now(),
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now()
);
create index if not exists landlord_applications_status_idx on public.landlord_applications(status,updated_at desc);
create table if not exists public.landlord_application_events (
 id bigint generated always as identity primary key,
 application_id uuid not null references public.landlord_applications(id) on delete cascade,
 actor_id uuid references auth.users(id) on delete set null,
 status text not null,
 comment text not null default '',
 created_at timestamptz not null default now()
);
alter table public.landlord_applications enable row level security;
alter table public.landlord_application_events enable row level security;
revoke all on public.landlord_applications,public.landlord_application_events from public,anon,authenticated;
grant select on public.landlord_applications,public.landlord_application_events to authenticated;
grant all on public.landlord_applications,public.landlord_application_events to service_role;
drop policy if exists landlord_applications_read on public.landlord_applications;
create policy landlord_applications_read on public.landlord_applications for select to authenticated
 using (user_id=auth.uid() or public.fn_is_chief());
drop policy if exists landlord_application_events_read on public.landlord_application_events;
create policy landlord_application_events_read on public.landlord_application_events for select to authenticated
 using (exists(select 1 from public.landlord_applications a where a.id=application_id and (a.user_id=auth.uid() or public.fn_is_chief())));

create or replace function public.submit_landlord_application(p_data jsonb)
returns public.landlord_applications language plpgsql security definer set search_path=public,pg_temp as $$
declare
 u uuid := auth.uid(); em text; row public.landlord_applications; cats text[]; ph text;
begin
 if u is null then raise exception 'Сначала войдите в аккаунт'; end if;
 select lower(email) into em from auth.users where id=u;
 if em is null or em='' then raise exception 'В аккаунте не указан email'; end if;
 if exists(select 1 from public.admins where lower(email)=em) then
   raise exception 'У аккаунта уже есть профиль арендодателя. Обратитесь к владельцу площадки';
 end if;
 -- Serialise repeated submits for one account, including the first submission.
 perform pg_advisory_xact_lock(hashtextextended(u::text,0));
 select * into row from public.landlord_applications where user_id=u for update;
 if row.status in ('pending','approved') then raise exception 'Заявка уже отправлена или одобрена'; end if;
 if coalesce((p_data->>'accept_terms')::boolean,false) is not true
    or coalesce((p_data->>'accept_privacy')::boolean,false) is not true then
   raise exception 'Подтвердите условия и согласие на обработку данных';
 end if;
 select array_agg(distinct value) into cats from jsonb_array_elements_text(p_data->'categories');
 if cats is null or cardinality(cats)=0 or exists(
   select 1 from unnest(cats) c where not exists(select 1 from public.cats k where k.key=c and k.active)
 ) then raise exception 'Выберите действующие категории инструмента'; end if;
 ph := regexp_replace(coalesce(p_data->>'phone',''),'[^+0-9]','','g');
 insert into public.landlord_applications(user_id,email,full_name,phone,business_type,region,city,categories,inventory_count,company,website,comment,terms_version,consent_version)
 values(u,em,btrim(p_data->>'full_name'),ph,p_data->>'business_type',btrim(p_data->>'region'),btrim(p_data->>'city'),cats,
 (p_data->>'inventory_count')::integer,btrim(coalesce(p_data->>'company','')),btrim(coalesce(p_data->>'website','')),btrim(coalesce(p_data->>'comment','')),'2026-09-23','2026-09-23')
 on conflict(user_id) do update set
 email=excluded.email,full_name=excluded.full_name,phone=excluded.phone,business_type=excluded.business_type,
 region=excluded.region,city=excluded.city,categories=excluded.categories,inventory_count=excluded.inventory_count,
 company=excluded.company,website=excluded.website,comment=excluded.comment,status='pending',
 review_comment='',reviewed_by=null,reviewed_at=null,fee_pct=null,debt_limit=null,
 terms_version=excluded.terms_version,consent_version=excluded.consent_version,accepted_at=now(),updated_at=now()
 returning * into row;
 insert into public.landlord_application_events(application_id,actor_id,status,comment) values(row.id,u,'pending','Заявка отправлена на проверку');
 return row;
end $$;

create or replace function public.review_landlord_application(
 p_id uuid,p_status text,p_comment text,p_fee_pct integer,p_debt_limit numeric,p_expected_updated_at timestamptz
) returns public.landlord_applications language plpgsql security definer set search_path=public,pg_temp as $$
declare row public.landlord_applications; em text;
begin
 if auth.uid() is null or not exists(select 1 from public.admins where lower(email)=lower(public.jwt_email()) and role='chief' and active) then
  raise exception 'Только владелец площадки может принимать решение';
 end if;
 if p_status not in ('approved','changes_requested','rejected') or p_status is null then raise exception 'Недопустимое решение'; end if;
 select * into row from public.landlord_applications where id=p_id for update;
 if not found then raise exception 'Заявка не найдена'; end if;
 if row.status <> 'pending' or p_expected_updated_at is distinct from row.updated_at then
  raise exception 'Заявка уже изменена. Обновите список';
 end if;
 if char_length(coalesce(p_comment,''))>2000 then raise exception 'Комментарий не должен превышать 2000 символов'; end if;
 if p_status in ('changes_requested','rejected') and char_length(btrim(coalesce(p_comment,'')))<3 then
  raise exception 'Укажите причину — её увидит заявитель';
 end if;
 if p_status='approved' then
  if p_fee_pct is null or p_fee_pct<0 or p_fee_pct>100 or p_debt_limit is null or p_debt_limit<0 or p_debt_limit>10000000 then
   raise exception 'Укажите комиссию от 0 до 100%% и лимит от 0 до 10 000 000 рублей';
  end if;
  select lower(email) into em from auth.users where id=row.user_id;
  if em is distinct from row.email then raise exception 'Email заявителя изменён. Верните анкету на доработку'; end if;
  if exists(select 1 from public.admins where lower(email)=em) then raise exception 'Профиль арендодателя уже существует'; end if;
  insert into public.admins(email,role,name,full_name,company,phone,active,fee_pct,debt_limit)
   values(em,'admin',row.full_name,row.full_name,row.company,row.phone,true,p_fee_pct,p_debt_limit);
 end if;
 update public.landlord_applications set status=p_status,review_comment=btrim(coalesce(p_comment,'')),
  reviewed_by=auth.uid(),reviewed_at=now(),updated_at=now(),
  fee_pct=case when p_status='approved' then p_fee_pct else null end,
  debt_limit=case when p_status='approved' then p_debt_limit else null end
 where id=p_id returning * into row;
 insert into public.landlord_application_events(application_id,actor_id,status,comment)
  values(row.id,auth.uid(),p_status,row.review_comment);
 return row;
end $$;
revoke all on function public.submit_landlord_application(jsonb) from public,anon;
revoke all on function public.review_landlord_application(uuid,text,text,integer,numeric,timestamptz) from public,anon;
grant execute on function public.submit_landlord_application(jsonb) to authenticated;
grant execute on function public.review_landlord_application(uuid,text,text,integer,numeric,timestamptz) to authenticated;

-- Do not trust arbitrary JWT metadata as a role. Approval must be backed by admins.
create or replace function public.fn_is_admin() returns boolean language sql stable security definer set search_path=public as $$
 select exists(select 1 from public.admins a where a.active and a.role in ('admin','chief') and lower(a.email)=lower(public.jwt_email()))
$$;
-- Existing owner-email policy allowed any authenticated account to create a listing.
-- Require actual approved membership for writes (and private owner access).
drop policy if exists tools_admin on public.tools;
create policy tools_admin on public.tools for all to authenticated
 using (public.fn_is_admin() and (public.fn_is_chief() or lower(owner_email)=lower(public.jwt_email())))
 with check (public.fn_is_admin() and (public.fn_is_chief() or lower(owner_email)=lower(public.jwt_email())));
-- Profile editing stays available; role and commercial terms cannot be self-assigned.
create or replace function public.protect_admin_access_fields() returns trigger language plpgsql set search_path=public,pg_temp as $$
begin
 if auth.role()='authenticated' and not public.fn_is_chief() and
  (new.email is distinct from old.email or new.role is distinct from old.role or new.active is distinct from old.active
   or new.fee_pct is distinct from old.fee_pct or new.debt_limit is distinct from old.debt_limit) then
   raise exception 'Права доступа, комиссию и лимит меняет только владелец площадки';
 end if;
 return new;
end $$;
drop trigger if exists protect_admin_access_fields on public.admins;
create trigger protect_admin_access_fields before update on public.admins for each row execute function public.protect_admin_access_fields();
commit;

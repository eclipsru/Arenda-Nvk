-- Store the selected locality independently of the full pickup address.
-- No UPDATE/INSERT of listings: publication/VK triggers are not invoked.
begin;
alter table public.tools add column if not exists pickup_city text not null default '';
do $$ begin
 if not exists(select 1 from pg_constraint where conrelid='public.tools'::regclass and conname='tools_pickup_city_length') then
  alter table public.tools add constraint tools_pickup_city_length check(char_length(pickup_city)<=100) not valid;
 end if;
end $$;
alter table public.tools validate constraint tools_pickup_city_length;
notify pgrst,'reload schema';
commit;

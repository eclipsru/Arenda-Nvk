begin;
insert into auth.users(id,email,aud,role,email_confirmed_at,raw_app_meta_data,raw_user_meta_data,created_at,updated_at) values
('cb438e18-45de-4fcb-b658-97ad15380c01','qa-profile-a@example.invalid','authenticated','authenticated',now(),'{}','{}',now(),now()),
('cb438e18-45de-4fcb-b658-97ad15380c02','qa-profile-b@example.invalid','authenticated','authenticated',now(),'{}','{}',now(),now()),
('cb438e18-45de-4fcb-b658-97ad15380c03','qa-profile-c@example.invalid','authenticated','authenticated',now(),'{}','{}',now(),now());
insert into public.admins(email,role,active,fee_pct,debt_limit) values ('qa-profile-a@example.invalid','admin',true,5,1000),('qa-profile-b@example.invalid','admin',false,7,2000);
select set_config('request.jwt.claims','{"sub":"cb438e18-45de-4fcb-b658-97ad15380c01","email":"qa-profile-a@example.invalid","role":"authenticated"}',true);
set local role authenticated;
do $$declare r public.landlord_profiles; d jsonb:='{"display_name":"Тестовый прокат","phone":"+79001234567"}'; p jsonb:='[{"id":"point-1","name":"Основной склад","city":"Ростов-на-Дону","address":"ул. Тестовая, 1"},{"id":"point-2","name":"Филиал","city":"Шахты","address":"ул. Проверочная, 2"}]'; begin
 r:=public.save_landlord_profile(d,p,0);
 if r.revision<>1 or r.owner_email<>'qa-profile-a@example.invalid' or jsonb_array_length(r.locations)<>2 then raise exception 'TEST: wrong profile save'; end if;
 r:=public.save_landlord_profile(d||'{"description":"Описание проката"}',p,1);
 if r.revision<>2 then raise exception 'TEST: update revision'; end if;
 begin perform public.save_landlord_profile(d,p,1);raise exception 'TEST: stale write allowed';exception when others then if sqlerrm like 'TEST:%' then raise;end if;end;
 begin perform public.save_landlord_profile(d||'{"role":"chief","owner_email":"qa-profile-b@example.invalid"}',p,2);raise exception 'TEST: spoofed fields';exception when others then if sqlerrm like 'TEST:%' then raise;end if;end;
 begin perform public.save_landlord_profile(d||'{"website":"javascript:alert(1)"}',p,2);raise exception 'TEST: unsafe link';exception when others then if sqlerrm like 'TEST:%' then raise;end if;end;
 begin perform public.save_landlord_profile(d,'[{"id":"broken","city":""}]',2);raise exception 'TEST: invalid point';exception when others then if sqlerrm like 'TEST:%' then raise;end if;end;
 begin perform public.save_landlord_profile(d,jsonb_build_array(p->0,p->0),2);raise exception 'TEST: duplicate point';exception when others then if sqlerrm like 'TEST:%' then raise;end if;end;
 begin update public.landlord_profiles set owner_email='qa-profile-b@example.invalid';raise exception 'TEST: direct write';exception when insufficient_privilege then null;end;
 if exists(select 1 from public.admins where email='qa-profile-a@example.invalid' and (fee_pct<>5 or debt_limit<>1000 or role<>'admin')) then raise exception 'TEST: privileged fields changed';end if;
end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"cb438e18-45de-4fcb-b658-97ad15380c02","email":"qa-profile-b@example.invalid","role":"authenticated"}',true);
set local role authenticated;
do $$begin
 begin perform public.save_landlord_profile('{"display_name":"Blocked","phone":"+79001234567"}','[]',0);raise exception 'TEST: inactive admin write';exception when others then if sqlerrm like 'TEST:%' then raise;end if;end;
end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"cb438e18-45de-4fcb-b658-97ad15380c03","email":"qa-profile-c@example.invalid","role":"authenticated","user_metadata":{"email":"qa-profile-a@example.invalid","role":"chief"}}',true);
set local role authenticated;
do $$begin
 begin perform public.save_landlord_profile('{"display_name":"Blocked","phone":"+79001234567"}','[]',0);raise exception 'TEST: unapproved user write';exception when others then if sqlerrm like 'TEST:%' then raise;end if;end;
end $$;
reset role;
select set_config('request.jwt.claims','{"role":"anon"}',true);
set local role anon;
do $$begin
 if not exists(select 1 from public.landlord_profiles where owner_email='qa-profile-a@example.invalid' and revision=2) then raise exception 'TEST: public profile not visible';end if;
 begin perform public.save_landlord_profile('{}','[]',0);raise exception 'TEST: anonymous write';exception when insufficient_privilege then null;end;
end $$;
reset role;
update public.admins set active=false where email='qa-profile-a@example.invalid';
set local role anon;
do $$begin
 if exists(select 1 from public.landlord_profiles where owner_email='qa-profile-a@example.invalid') then raise exception 'TEST: inactive profile public';end if;
end $$;
reset role;
select 'PASS: own saves, multiple cities, revisions, input validation, public reads, inactive filtering, privilege isolation and unauthorised writes' as result;
rollback;

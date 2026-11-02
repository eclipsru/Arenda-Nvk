-- 10.63 — «Архив заявок» у клиента в приложении и прокат в подробностях заявки (решения владельца 14.10.2026).
-- ПРИМЕНЯЕТ ВЛАДЕЛЕЦ (Supabase → SQL Editor). Агент в боевую базу ничего не записывает (решение №15).
-- Повторный запуск безопасен: create table if not exists / create or replace / drop policy if exists.
--
-- Что даёт:
--  1) Таблица app_order_archived: кто убрал (почта из входа) и id заявки. Приложение не показывает такие заявки
--     во вкладке «Заявки» — они в «Кабинете» → «Архив заявок». Заявки (orders, order_parts) НЕ меняются и не удаляются:
--     у проката всё как было, архив проката (order_parts.archived) — отдельный и не трогается.
--  2) iva_archive_order(id, вкл/выкл) — убрать в архив / вернуть. Только свою заявку и только закрытую
--     (все части заявки отмечены прокатом «Вернули»; частей нет — по статусу самой заявки). Вернуть — всегда можно.
--  3) iva_my_order_parts() — по своим заявкам: прокат (почта) и статус каждой части. Нужна, чтобы клиент видел
--     настоящий статус («Выдал»/«Вернули» прокат отмечает в части заявки) и данные проката в подробностях.
--     Чужие заявки не видны; данные клиента не возвращаются. Новых персональных данных нет.
-- До применения: «Архив заявок» и «В архив» показывают подсказку «Архив заявок заработает после обновления базы»;
-- прокат в подробностях ищется по инструментам из каталога.
--
-- Откат (если понадобится; заявки из архива снова появятся в «Заявках»):
--   drop function if exists public.iva_my_order_parts();
--   drop function if exists public.iva_archive_order(text, boolean);
--   drop table if exists public.app_order_archived;

create table if not exists public.app_order_archived (
  user_email text        not null,              -- кто убрал заявку в архив (нижний регистр)
  order_id   text        not null,              -- orders.id (как текст)
  created_at timestamptz not null default now(),
  primary key (user_email, order_id)
);

alter table public.app_order_archived enable row level security;

revoke all on public.app_order_archived from anon;
revoke all on public.app_order_archived from authenticated;
grant select on public.app_order_archived to authenticated;

drop policy if exists app_order_archived_own_select on public.app_order_archived;
create policy app_order_archived_own_select on public.app_order_archived
  for select to authenticated
  using (user_email = lower(coalesce(auth.jwt() ->> 'email', '')));

-- Убрать свою закрытую заявку в архив (p_on = true) или вернуть её (p_on = false).
create or replace function public.iva_archive_order(p_id text, p_on boolean)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  me text := lower(coalesce(auth.jwt() ->> 'email', ''));
  v_id text := trim(coalesce(p_id, ''));
begin
  if me = '' or v_id = '' or length(v_id) > 64 then
    return 0;
  end if;
  if not coalesce(p_on, true) then
    delete from public.app_order_archived where user_email = me and order_id = v_id;
    return 1;
  end if;
  -- только своя заявка
  if not exists (select 1 from public.orders o where o.id::text = v_id and lower(coalesce(o.user_email, '')) = me) then
    return 0;
  end if;
  -- только закрытая: есть части — все не «new»/«rented»; частей нет — статус самой заявки
  if exists (select 1 from public.order_parts p where p.order_id::text = v_id) then
    if exists (select 1 from public.order_parts p
               where p.order_id::text = v_id and coalesce(nullif(lower(trim(p.status)), ''), 'new') in ('new', 'rented')) then
      return 0;
    end if;
  elsif exists (select 1 from public.orders o
                where o.id::text = v_id and coalesce(nullif(lower(trim(o.status)), ''), 'new') in ('new', 'rented')) then
    return 0;
  end if;
  insert into public.app_order_archived (user_email, order_id)
  values (me, v_id)
  on conflict (user_email, order_id) do nothing;
  return 1;
end;
$$;

revoke all on function public.iva_archive_order(text, boolean) from public, anon;
grant execute on function public.iva_archive_order(text, boolean) to authenticated;

-- Части своих заявок: какой прокат и какой статус (без данных клиента).
create or replace function public.iva_my_order_parts()
returns table (order_id text, owner_email text, status text)
language sql
stable
security definer
set search_path = public
as $$
  select p.order_id::text, lower(coalesce(p.owner_email, '')), coalesce(nullif(lower(trim(p.status)), ''), 'new')
  from public.order_parts p
  join public.orders o on o.id = p.order_id
  where lower(coalesce(o.user_email, '')) = lower(coalesce(auth.jwt() ->> 'email', ''))
    and coalesce(auth.jwt() ->> 'email', '') <> ''
  order by p.order_id, p.id;
$$;

revoke all on function public.iva_my_order_parts() from public, anon;
grant execute on function public.iva_my_order_parts() to authenticated;

-- 10.62 — «Удалить у меня» для одного сообщения в чате приложения (просьба владельца 14.10.2026).
-- ПРИМЕНЯЕТ ВЛАДЕЛЕЦ (Supabase → SQL Editor). Агент в боевую базу ничего не записывает (решение №15).
-- Повторный запуск безопасен: create table if not exists / create or replace / drop policy if exists.
--
-- Что даёт:
--  1) Таблица app_msg_hidden: кто скрыл (почта из входа) и id сообщения. Приложение не показывает этому человеку
--     скрытые им сообщения. У собеседника сообщение остаётся, в app_messages ничего не удаляется и не меняется.
--  2) «Удалить у всех» работает как раньше (удаление своей записи из app_messages) — этот SQL его не трогает.
--  3) Каждый читает только свои строки (RLS). Записывать — только через iva_hide_msg() и только за себя.
--     Новых персональных данных нет: только почта (уже есть во входе) и id сообщения.
-- До применения приложение работает как раньше; при выборе «Удалить у меня» — подсказка
-- «"Удалить у меня" заработает после обновления базы». «Удалить у всех» работает и без этого SQL.
--
-- Откат (если понадобится; скрытые сообщения снова появятся у тех, кто их скрыл):
--   drop function if exists public.iva_hide_msg(text);
--   drop table if exists public.app_msg_hidden;

create table if not exists public.app_msg_hidden (
  user_email text        not null,              -- кто скрыл сообщение у себя (нижний регистр)
  msg_id     text        not null,              -- app_messages.id (как текст)
  created_at timestamptz not null default now(),
  primary key (user_email, msg_id)
);

alter table public.app_msg_hidden enable row level security;

revoke all on public.app_msg_hidden from anon;
revoke all on public.app_msg_hidden from authenticated;
grant select on public.app_msg_hidden to authenticated;

drop policy if exists app_msg_hidden_own_select on public.app_msg_hidden;
create policy app_msg_hidden_own_select on public.app_msg_hidden
  for select to authenticated
  using (user_email = lower(coalesce(auth.jwt() ->> 'email', '')));

-- Скрыть сообщение у себя. Повтор — без ошибки. Несуществующий id не записывается.
create or replace function public.iva_hide_msg(p_id text)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  me text := lower(coalesce(auth.jwt() ->> 'email', ''));
  mid text := trim(coalesce(p_id, ''));
begin
  if me = '' or mid = '' or length(mid) > 64 then
    return 0;
  end if;
  if not exists (select 1 from public.app_messages m where m.id::text = mid) then
    return 0;
  end if;
  insert into public.app_msg_hidden (user_email, msg_id)
  values (me, mid)
  on conflict (user_email, msg_id) do nothing;
  return 1;
end;
$$;

revoke all on function public.iva_hide_msg(text) from public, anon;
grant execute on function public.iva_hide_msg(text) to authenticated;

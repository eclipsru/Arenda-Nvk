-- 10.61 — «Удалить чат» в приложении: только у того, кто удаляет (решение владельца 14.10.2026).
-- ПРИМЕНЯЕТ ВЛАДЕЛЕЦ (Supabase → SQL Editor). Агент в боевую базу ничего не записывает (решение №15).
-- Повторный запуск безопасен: create table if not exists / create or replace / drop policy if exists.
--
-- Что даёт:
--  1) Таблица app_chat_hidden: кто удалил (почта), собеседник (почта), инструмент ('' — общий чат) и hidden_until —
--     время последнего сообщения переписки на момент удаления. Приложение не показывает этому человеку сообщения
--     переписки не позже hidden_until; новое сообщение — чат появляется снова, только с новыми сообщениями.
--  2) Сообщения в app_messages НЕ удаляются и не меняются: у собеседника переписка остаётся как была, сайт не меняется.
--  3) Каждый читает только свои строки (RLS). Записывать — только через iva_hide_chat() и только за себя
--     (почта берётся из входа, а не из запроса). Новых персональных данных нет: почты собеседников уже есть в app_messages.
-- До применения приложение работает как раньше; при долгом нажатии на чат — подсказка
-- «Удаление чатов заработает после обновления базы».
--
-- Откат (если понадобится; удалённые чаты снова появятся у всех, кто их удалял):
--   drop function if exists public.iva_hide_chat(text, text, timestamptz);
--   drop table if exists public.app_chat_hidden;

create table if not exists public.app_chat_hidden (
  user_email   text        not null,              -- кто удалил чат у себя (нижний регистр)
  partner      text        not null,              -- собеседник (нижний регистр)
  tool_id      text        not null default '',   -- инструмент переписки; '' — общий чат
  hidden_until timestamptz not null,              -- скрыты сообщения не позже этого времени
  updated_at   timestamptz not null default now(),
  primary key (user_email, partner, tool_id)
);

alter table public.app_chat_hidden enable row level security;

revoke all on public.app_chat_hidden from anon;
revoke all on public.app_chat_hidden from authenticated;
grant select on public.app_chat_hidden to authenticated;

drop policy if exists app_chat_hidden_own_select on public.app_chat_hidden;
create policy app_chat_hidden_own_select on public.app_chat_hidden
  for select to authenticated
  using (user_email = lower(coalesce(auth.jwt() ->> 'email', '')));

-- Удалить чат у себя. Повторное удаление того же чата сдвигает границу только вперёд.
create or replace function public.iva_hide_chat(p_partner text, p_tool text, p_until timestamptz)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  me text := lower(coalesce(auth.jwt() ->> 'email', ''));
  who text := lower(trim(coalesce(p_partner, '')));
  t text := trim(coalesce(p_tool, ''));
begin
  if me = '' or who = '' or who = me or p_until is null or length(who) > 320 or length(t) > 64 then
    return 0;
  end if;
  -- граница — время сообщения из базы; из будущего не принимаем (иначе скрылись бы и будущие сообщения)
  if p_until > now() then
    p_until := now();
  end if;
  insert into public.app_chat_hidden (user_email, partner, tool_id, hidden_until)
  values (me, who, t, p_until)
  on conflict (user_email, partner, tool_id) do update
    set hidden_until = greatest(public.app_chat_hidden.hidden_until, excluded.hidden_until),
        updated_at = now();
  return 1;
end;
$$;

revoke all on function public.iva_hide_chat(text, text, timestamptz) from public, anon;
grant execute on function public.iva_hide_chat(text, text, timestamptz) to authenticated;

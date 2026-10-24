-- ============================================================================
-- 10.72: переписка «Хочу сдавать инструмент» — отдельная (просьба владельца 14.10.2026)
-- Применяет владелец в Supabase → SQL Editor (решение №15). Сайт не меняется.
--
-- Клиент в кабинете жмёт «Хочу сдавать инструмент» → «Чат с владельцем». Приложение пишет сообщения этой
-- переписки с topic = 'lend' — у владельца это отдельная переписка (со смайликом), а не общий чат.
-- До применения приложение работает как раньше: такие сообщения идут в общий чат с владельцем.
--
-- Что даёт:
--  1) колонка app_messages.topic: NULL — обычное сообщение, 'lend' — «Хочу сдавать инструмент».
--     Другие значения не принимаются; у сообщения по инструменту (tool_id) темы быть не может.
--  2) iva_tag_my_topic(ids, topic) — штатная запись голоса не передаёт тему; после отправки приложение
--     помечает СВОИ голосовые последних суток темой открытой переписки (как iva_tag_my_messages для инструмента).
-- Сообщения, отправленные раньше, остаются в общем чате. Ответ с сайта попадёт в общий чат (сайт не меняется).
--
-- Откат (если понадобится; сообщения не удаляются — переписка снова станет общим чатом):
--   drop function if exists public.iva_tag_my_topic(text[], text);
--   alter table public.app_messages drop constraint if exists app_messages_topic_chk;
--   alter table public.app_messages drop column if exists topic;
-- ============================================================================

alter table public.app_messages add column if not exists topic text;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'app_messages_topic_chk') then
    alter table public.app_messages
      add constraint app_messages_topic_chk check (topic is null or (topic = 'lend' and tool_id is null));
  end if;
end;
$$;

-- Пометить свои голосовые темой переписки: только свои, только без темы и без инструмента, только за сутки.
create or replace function public.iva_tag_my_topic(p_ids text[], p_topic text)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  n integer := 0;
begin
  if p_topic is distinct from 'lend' or coalesce(array_length(p_ids, 1), 0) = 0 or array_length(p_ids, 1) > 20 then
    return 0;
  end if;
  update public.app_messages m
     set topic = p_topic
   where m.id::text = any (p_ids)
     and lower(m.from_email) = lower(coalesce(auth.jwt() ->> 'email', ''))
     and m.topic is null
     and m.tool_id is null
     and m.created_at > now() - interval '1 day';
  get diagnostics n = row_count;
  return n;
end;
$$;

revoke all on function public.iva_tag_my_topic(text[], text) from public, anon;
grant execute on function public.iva_tag_my_topic(text[], text) to authenticated;

-- Чтобы API сразу увидел новую колонку.
notify pgrst, 'reload schema';

-- ============================================================================
-- ⚠️ ТОЛЬКО ДЛЯ НОВОГО (ТЕСТОВОГО) ПРОЕКТА. НЕ ЗАПУСКАТЬ НА БОЕВОЙ БАЗЕ.
-- ============================================================================
-- Зачем отдельный файл: на боевом проекте права доступа уже настроены и работают
-- (кабинет главного читает справочник). Если поменять правила на боевом проекте,
-- можно случайно закрыть себе доступ. Поэтому политики лежат отдельно и запускаются
-- только там, где таблицу создавали с нуля — например, в тестовом проекте.
--
-- Что делают правила:
--   • читать справочник может любой посетитель сайта, КРОМЕ карточек со статусом
--     hidden («скрыть по просьбе владельца точки») и declined («не наш формат»);
--   • администратор видит все карточки и может их изменять (статусы, заметки,
--     отметки рассылки);
--   • обычный посетитель не может ничего записать.
--
-- Безопасно при повторном запуске.
-- Откат (вернуть «как было»): drop policy ... — см. блок в конце файла.
-- ============================================================================

alter table public.directory_landlords enable row level security;

-- Чтение: всем, кроме скрытых и отклонённых; администратору — всё
drop policy if exists "directory_landlords_select_public" on public.directory_landlords;
create policy "directory_landlords_select_public" on public.directory_landlords
  for select to anon, authenticated
  using (
    status not in ('hidden', 'declined')
    or (auth.uid() is not null and exists (
      select 1 from public.admins
      where lower(email) = lower(auth.jwt() ->> 'email') and active = true
    ))
  );

-- Изменение: только активные администраторы
drop policy if exists "directory_landlords_write_admins" on public.directory_landlords;
create policy "directory_landlords_write_admins" on public.directory_landlords
  for all to authenticated
  using (
    exists (
      select 1 from public.admins
      where lower(email) = lower(auth.jwt() ->> 'email') and active = true
    )
  )
  with check (
    exists (
      select 1 from public.admins
      where lower(email) = lower(auth.jwt() ->> 'email') and active = true
    )
  );

-- ============================================================================
-- Откат (раскомментировать и запустить, если нужно вернуть свободу записи):
-- ============================================================================
-- drop policy if exists "directory_landlords_select_public" on public.directory_landlords;
-- drop policy if exists "directory_landlords_write_admins"   on public.directory_landlords;
-- alter table public.directory_landlords disable row level security;

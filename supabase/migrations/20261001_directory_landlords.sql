-- ============================================================================
-- Справочник прокатов (directory_landlords) — структура. Этап П2.
-- Дата: 2026-10-01
--
-- ФАКТЫ ПРО БОЕВУЮ БАЗУ (проверено 01.10.2026):
--   • таблица там УЖЕ ЕСТЬ — создана 24.09.2026, в ней 259 записей;
--   • она используется в кабинете главного: «Рассылка по базам проката» (chief.html).
--   Поэтому этот файл НЕ пересоздаёт и НЕ чистит таблицу. Он:
--     1) создаёт таблицу в правильном виде, если её нет (новый/тестовый проект);
--     2) добавляет недостающие столбцы, если таблица была создана в старой версии;
--     3) добавляет индексы (поиск по городу/статусу/телефону) и защиту от дублей.
--   Данные этот файл не трогает — для них отдельный файл дополнения.
--
-- Безопасен при повторном запуске (везде «if not exists»).
-- Политики доступа здесь НЕ меняются: на боевом проекте права уже настроены, а для
-- нового проекта есть отдельный файл 20261001_directory_landlords_rls_NEW_PROJECT.sql.
--
-- Откат: этот файл ничего не удаляет, откатывать нечего.
-- ============================================================================

-- 1. Таблица (если её нет) — в том же виде, что в боевой базе
create table if not exists public.directory_landlords (
  id                 uuid primary key default gen_random_uuid(),
  created_at         timestamptz not null default now(),
  city               text not null default '',
  name               text not null default '',
  phone              text not null,
  address            text not null default '',
  source             text not null default '',
  has_whatsapp       boolean not null default false,
  last_contacted_at  timestamptz,
  status             text not null default 'new',
  campaign_count     integer not null default 0,
  notes              text not null default '',
  has_telegram       boolean not null default false,
  has_max            boolean not null default false,
  is_mobile          boolean not null default false
);

-- 2. Если таблица создана в старой версии — дотягиваем недостающие столбцы
alter table public.directory_landlords add column if not exists created_at        timestamptz not null default now();
alter table public.directory_landlords add column if not exists city              text not null default '';
alter table public.directory_landlords add column if not exists name              text not null default '';
alter table public.directory_landlords add column if not exists address           text not null default '';
alter table public.directory_landlords add column if not exists source            text not null default '';
alter table public.directory_landlords add column if not exists has_whatsapp      boolean not null default false;
alter table public.directory_landlords add column if not exists last_contacted_at timestamptz;
alter table public.directory_landlords add column if not exists status            text not null default 'new';
alter table public.directory_landlords add column if not exists campaign_count    integer not null default 0;
alter table public.directory_landlords add column if not exists notes             text not null default '';
alter table public.directory_landlords add column if not exists has_telegram      boolean not null default false;
alter table public.directory_landlords add column if not exists has_max           boolean not null default false;
alter table public.directory_landlords add column if not exists is_mobile         boolean not null default false;

-- 3. Индексы для поиска
create index if not exists idx_directory_landlords_city   on public.directory_landlords (city);
create index if not exists idx_directory_landlords_status on public.directory_landlords (status);
create index if not exists idx_directory_landlords_phone  on public.directory_landlords (phone);

-- 4. Защита от дублей: один телефон — одна запись.
--    Проверено перед добавлением: в боевой базе повторяющихся телефонов нет,
--    поэтому индекс создаётся без ошибок. Если такой индекс уже есть — пропустится.
--    Если однажды появится ошибка «could not create unique index», значит в таблице
--    завёлся дубль телефона — напишите, разберём (это займёт минуту).
create unique index if not exists uq_directory_landlords_phone on public.directory_landlords (phone);

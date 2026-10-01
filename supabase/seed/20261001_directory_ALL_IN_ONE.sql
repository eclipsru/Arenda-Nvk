-- ============================================================================
-- Ива: ВСЁ В ОДНОМ ФАЙЛЕ — справочник прокатов «под ключ» (этап П2).
--
-- ⚠️ ЭТОТ ФАЙЛ — ДЛЯ НОВОГО / ТЕСТОВОГО ПРОЕКТА. На боевом проекте он не нужен:
--    таблица там уже создана, а данные — в отдельном файле дополнения.
--
-- Состав: структура таблицы + ввоз отсутствующих контактов из tools/real_bases.csv.
-- Политики доступа — отдельным файлом (…_rls_NEW_PROJECT.sql), чтобы случайно
-- не изменить права на боевом проекте.
--
-- Повторный запуск безопасен: ничего не удаляется, дубли не создаются.
-- ============================================================================

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

-- ============================================================================
-- ЧАСТЬ 2: данные (контакты, которых ещё нет в базе)
-- ============================================================================

-- ============================================================================
-- Ива — ДОПОЛНЕНИЕ справочника прокатов (этап П2)
--
-- Что это: добавляет в боевой справочник те контакты из tools/real_bases.csv,
-- которых там ещё нет. На момент сборки в базе было 259 записей
-- (снимок от 2026-10-01), новых в этом файле — 25.
--
-- Как запускать: Supabase -> SQL Editor -> New query -> вставить весь файл -> Run.
-- Безопасно при повторном запуске: уже существующие телефоны пропускаются,
-- дубли не создаются. Ничего не удаляет и не изменяет.
-- ============================================================================

begin;

insert into public.directory_landlords
  (city, name, phone, address, source, has_whatsapp, has_telegram, has_max, is_mobile, notes, status)
select
  v.city, v.name, v.phone, v.address, v.source,
  v.has_whatsapp, v.has_telegram, v.has_max, v.is_mobile, v.notes, 'new'
from (values
  ('Аксай', 'Ваш Дом Аксай', '78633100003', 'г. Аксай, ул. Вартанова, 11', 'vashdom24.ru', false, false, false, false, ''),
  ('Аксай', 'Стройрент Аксай', '78633332893', 'г. Аксай, пр. Ленина, д. 53 Д', 'stroyrent.ru', false, false, false, false, ''),
  ('Батайск', 'Фирма ЛТД Батайск', '78633089672', 'Промышленная улица', 'spravker.ru', false, false, false, false, ''),
  ('Воронеж', 'Бригадир Прокат', '74732000352', 'ул. Димитрова, 112', 'brigadirprokat36.ru', false, false, false, false, ''),
  ('Воронеж', 'Помощник 36', '74732932750', 'ул. 45 Стрелковой дивизии, 234/19', 'pomoshnik36.ru', false, false, false, false, ''),
  ('Воронеж', 'Прокат ВРН', '74732907276', 'ул. 60 армии, 29А', 'prokatvrn.ru', false, false, false, false, ''),
  ('Воронеж', 'Прокат36', '74733003618', 'ул. Кривошеина, 15, офис 223А', 'prokat36.ru', false, false, false, false, ''),
  ('Екатеринбург', 'АрендаСтрой Екатеринбург', '73432074877', 'ул. Крауля, 168А', 'arendastro.ru', false, false, false, false, ''),
  ('Екатеринбург', 'ИнструментБург', '73432264443', 'г. Екатеринбург', 'instrumentburg.ru', false, false, false, false, ''),
  ('Екатеринбург', 'Прокат-Екат', '73433020403', 'г. Екатеринбург', 'prokat-ekat.com', false, false, false, false, ''),
  ('Казань', 'Аренда инструмента Казань Победы', '78432464611', 'Проспект Победы, 206', 'arenda-instrumentov.ru', false, false, false, false, ''),
  ('Краснодар', 'РосПрокат 23', '78612012501', 'Северная ул., 223', '2gis', false, false, false, false, ''),
  ('Москва', 'Главпрокат Москва', '74952151105', 'Москва', 'glavprokat.net', false, false, false, false, ''),
  ('Москва', 'Городской Центр Проката', '74993505032', 'ул. Трофимова, 21 корп.1', 'gcprent.ru', false, false, false, false, ''),
  ('Москва', 'Магазин Проката', '74951504382', 'Мытищи, Фуражный проезд, 4', 'magazinprokata.ru', false, false, false, false, ''),
  ('Москва', 'МосСтройПрокат', '74953746108', 'Пятницкое шоссе, 28 стр.1', 'mosstroyprokat.ru', false, false, false, false, ''),
  ('Москва', 'РентБригадир', '74993508572', 'Большой Волоколамский проезд, 3Б', 'rentbrigadir.ru', false, false, false, false, ''),
  ('Мурманск', 'Стахановец.рф Мурманск', '78152567780', 'ул. Рогозерская, 34А (территория базы Строймикс)', 'stahanovec.ru', false, false, false, false, ''),
  ('Нижний Новгород', 'Всё в прокат НН', '78312915306', 'ул. Памирская, 11 литер М', 'vsevprokat52.ru', false, false, false, false, ''),
  ('Нижний Новгород', 'Прокат инструмента Деловая', '78314106644', 'ул. Деловая, д. 8 Б', 'orgpage.ru', false, false, false, false, ''),
  ('Новосибирск', 'СтройАренда Новосибирск', '73832990722', 'ул. Ставропольская, 1', 'xn--54-6kcatf0a8aftdhn.xn--p1ai', false, false, false, false, ''),
  ('Новочеркасск', 'Агентство Инструментарий (стац.)', '78635251150', 'ул. Гагарина, 33', 'yandex.maps', false, false, false, false, ''),
  ('Ростов-на-Дону', 'MachineStore', '78633033093', 'Привокзальная ул., 2', 'spravker.ru', false, false, false, false, ''),
  ('Санкт-Петербург', 'ТехноРент СПб', '78122451826', 'Б. Сампсониевский пр., 60 лит. И', 'trspb.ru', false, false, false, false, ''),
  ('Сочи', 'Прокат Мега Сочи', '78622913313', 'Виноградный пер., 5', 'vk.com', false, false, false, false, '')
) as v(city, name, phone, address, source, has_whatsapp, has_telegram, has_max, is_mobile, notes)
where not exists (
  select 1 from public.directory_landlords d
  where right(regexp_replace(d.phone, '[^0-9]', '', 'g'), 10) = right(v.phone, 10)
);

commit;

-- Итог: пришлите эту табличку — по ней видно результат
select
  count(*)                                           as "всего в справочнике",
  count(*) filter (where created_at >= now() - interval '1 hour') as "добавлено только что",
  count(distinct city)                               as "городов"
from public.directory_landlords;

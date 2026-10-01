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

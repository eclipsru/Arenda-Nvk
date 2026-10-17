-- 10.84 · Справочник моделей (этап 4): предложения моделей админами и комплектация.
-- ФАЙЛ СОБРАН tools/kit-gen.py из supabase/models/kit.txt — правьте данные там и пересоберите.
--
-- Что делает:
--   1. iva_kit_items        — что обычно отдают с инструментом, по видам (159 видов, 481 пунктов).
--                             Читают все, меняет только главный админ (fn_is_chief), как iva_kinds.
--   2. tools.kit            — отмеченная админом комплектация объявления (список строк, до 30).
--   3. iva_model_proposals  — «Предложить модель»: админ (fn_is_admin) предлагает модель, которой нет
--                             в справочнике. Видит только свои предложения, главный админ — все.
--                             Не больше 30 ждущих проверки от одного админа; ту же модель дважды не предложить.
--   4. iva_model_decide(id, одобрить, бренд, модель, название) — только главный админ.
--                             Одобрить: бренд добавляется (если нового нет), модель попадает в iva_models
--                             (source = 'proposal'; файлы списков 20261017_* её не выключают), объявления
--                             этого админа того же вида с этой моделью в названии получают model_id.
--                             Отклонить: только отметка. Решение повторно не принимается.
--
-- Порядок: после 20261017_models.sql и 20261017_models_data_1…3.sql.
-- Повторный запуск безопасен: комплектация приводится к списку, предложения и решения не трогаются.
--
-- Откат (объявления не трогает, кроме столбца kit; одобренные модели остаются в справочнике):
--   drop function if exists public.iva_model_decide(bigint, boolean, text, text, text);
--   drop table if exists public.iva_model_proposals;
--   drop table if exists public.iva_kit_items;
--   alter table public.tools drop column if exists kit;
--   -- по желанию убрать и одобренные модели: delete from public.iva_models where source = 'proposal';

begin;

-- ─────────────────────────────── 1. Комплектация по видам ───────────────────────────────
create table if not exists public.iva_kit_items (
  kind  text primary key references public.iva_kinds(key) on update cascade on delete cascade,
  items text[] not null default '{}'
);
alter table public.iva_kit_items enable row level security;
revoke all on public.iva_kit_items from anon, authenticated;
grant select on public.iva_kit_items to anon, authenticated;
grant insert, update, delete on public.iva_kit_items to authenticated;
drop policy if exists iva_kit_items_read on public.iva_kit_items;
create policy iva_kit_items_read on public.iva_kit_items for select to anon, authenticated using (true);
drop policy if exists iva_kit_items_chief on public.iva_kit_items;
create policy iva_kit_items_chief on public.iva_kit_items for all to authenticated
  using (public.fn_is_chief()) with check (public.fn_is_chief());

with v(kind, items) as (values
  ('drill', array['Кейс', 'Сверлильный патрон с ключом', 'Дополнительная рукоятка', 'Ограничитель глубины', 'Набор свёрл', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('screwdriver', array['Кейс', 'Аккумулятор', 'Второй аккумулятор', 'Зарядное устройство', 'Набор бит', 'Магнитный держатель бит']::text[]),
  ('impact-wrench', array['Кейс', 'Аккумулятор', 'Зарядное устройство', 'Набор торцевых головок', 'Удлинитель для головок']::text[]),
  ('magnetic-drill', array['Кейс', 'Корончатые свёрла', 'Центрирующий штифт', 'Страховочная цепь', 'Система охлаждения']::text[]),
  ('rotary-sds-plus', array['Кейс', 'Дополнительная рукоятка', 'Ограничитель глубины', 'Бур SDS-plus', 'Пика', 'Долото', 'Быстросъёмный патрон', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('rotary-sds-max', array['Кейс', 'Дополнительная рукоятка', 'Бур SDS-max', 'Пика', 'Долото']::text[]),
  ('breaker', array['Кейс', 'Дополнительная рукоятка', 'Пика', 'Долото']::text[]),
  ('demolition-hammer', array['Пика', 'Широкое долото', 'Тележка']::text[]),
  ('pneumatic-hammer', array['Пика', 'Долото', 'Шланг']::text[]),
  ('grinder-125', array['Кейс', 'Защитный кожух', 'Дополнительная рукоятка', 'Ключ для фланца', 'Отрезной диск', 'Алмазный диск', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('grinder-180', array['Защитный кожух', 'Дополнительная рукоятка', 'Ключ для фланца', 'Отрезной диск', 'Алмазный диск']::text[]),
  ('grinder-230', array['Защитный кожух', 'Дополнительная рукоятка', 'Ключ для фланца', 'Отрезной диск', 'Алмазный диск']::text[]),
  ('circular-saw', array['Кейс', 'Пильный диск', 'Направляющая шина', 'Параллельный упор', 'Ключ для диска', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('jigsaw', array['Кейс', 'Набор пилок', 'Параллельный упор', 'Защита от сколов', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('reciprocating-saw', array['Кейс', 'Полотна по дереву', 'Полотна по металлу', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('miter-saw', array['Пильный диск', 'Струбцина', 'Мешок для пыли', 'Удлинители стола']::text[]),
  ('table-saw', array['Пильный диск', 'Параллельный упор', 'Толкатель', 'Подставка']::text[]),
  ('plunge-saw', array['Систейнер', 'Пильный диск', 'Направляющая шина', 'Соединитель шин', 'Струбцины']::text[]),
  ('metal-cutoff-saw', array['Отрезной диск', 'Ключ для диска']::text[]),
  ('orbital-sander', array['Кейс', 'Шлифовальные круги', 'Пылесборник', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('belt-sander', array['Шлифовальная лента', 'Пылесборник', 'Рамка-ограничитель']::text[]),
  ('finishing-sander', array['Шлифовальные листы', 'Пылесборник']::text[]),
  ('electric-planer', array['Кейс', 'Ножи', 'Параллельный упор', 'Пылесборник']::text[]),
  ('router', array['Кейс', 'Набор фрез', 'Параллельный упор', 'Копировальная втулка', 'Цанги']::text[]),
  ('heat-gun', array['Кейс', 'Насадки', 'Скребок']::text[]),
  ('mixer-drill', array['Венчик-мешалка', 'Ключи для венчика']::text[]),
  ('multitool', array['Кейс', 'Погружные полотна', 'Сегментное полотно', 'Шлифовальная насадка', 'Шлифовальные листы', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('metal-shears', array['Кейс', 'Запасной нож', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('powder-nailer', array['Кейс', 'Монтажные патроны', 'Дюбель-гвозди', 'Защитные очки', 'Наушники']::text[]),
  ('gas-nailer', array['Кейс', 'Газовый баллон', 'Гвозди', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('nailer', array['Кейс', 'Гвозди', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('stapler', array['Кейс', 'Скобы']::text[]),
  ('riveter', array['Кейс', 'Насадки под заклёпки', 'Заклёпки', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('core-drill-hand', array['Коронка', 'Удлинитель коронки', 'Водяной бачок', 'Ключи']::text[]),
  ('core-drill-stand', array['Станина', 'Коронка', 'Анкер для станины', 'Водяной бачок', 'Водосборное кольцо']::text[]),
  ('wall-chaser', array['Кейс', 'Алмазные диски', 'Лопатка для выемки', 'Шланг для пылесоса']::text[]),
  ('tile-cutter-manual', array['Режущий ролик', 'Угловой упор']::text[]),
  ('tile-cutter-electric', array['Алмазный диск', 'Ванна для воды', 'Угловой упор']::text[]),
  ('stone-saw', array['Алмазный диск', 'Ванна для воды', 'Насос']::text[]),
  ('power-cutter', array['Алмазный диск', 'Ключ для диска', 'Бачок для воды']::text[]),
  ('floor-saw', array['Алмазный диск', 'Бак для воды', 'Ключи']::text[]),
  ('wall-saw', array['Алмазный диск', 'Направляющая рельса']::text[]),
  ('wire-saw', array['Алмазный канат', 'Направляющие ролики']::text[]),
  ('concrete-mixer', array['Ключи']::text[]),
  ('mortar-mixer', array['Венчик', 'Ёмкость']::text[]),
  ('poker-vibrator', array['Гибкий вал', 'Вибронаконечник']::text[]),
  ('screed-vibrator', array['Рейка', 'Ручки']::text[]),
  ('surface-vibrator', array['Ручка']::text[]),
  ('plate-compactor', array['Резиновый коврик', 'Колёса для перевозки']::text[]),
  ('rammer', '{}'::text[]),
  ('roller', '{}'::text[]),
  ('power-trowel', array['Лопасти', 'Затирочный диск']::text[]),
  ('rebar-cutter', array['Запасные ножи']::text[]),
  ('rebar-bender', array['Гибочные пальцы']::text[]),
  ('rebar-tier', array['Кейс', 'Катушки проволоки', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('wall-formwork', array['Щиты', 'Замки', 'Тяжи', 'Гайки', 'Подкосы']::text[]),
  ('slab-formwork', array['Стойки', 'Треноги', 'Унивилки', 'Балки']::text[]),
  ('props', array['Стойки', 'Треноги', 'Унивилки']::text[]),
  ('earth-auger', array['Шнек', 'Удлинитель шнека']::text[]),
  ('trencher', array['Цепь']::text[]),
  ('drum-floor-sander', array['Шлифовальная лента', 'Пылесборник', 'Ключ']::text[]),
  ('flat-floor-sander', array['Шлифовальные диски', 'Пылесборник']::text[]),
  ('edge-sander', array['Шлифовальные диски', 'Пылесборник']::text[]),
  ('concrete-grinder', array['Шлифовальные сегменты', 'Шланг для воды']::text[]),
  ('scarifier', array['Фрезы', 'Шланг для пылесоса']::text[]),
  ('drywall-sander-long', array['Кейс', 'Шлифовальные круги', 'Шланг для пылесоса']::text[]),
  ('wall-sander', array['Шлифовальные круги', 'Шланг для пылесоса']::text[]),
  ('trimmer', array['Леска', 'Нож', 'Ремень-разгрузка', 'Защитный кожух', 'Защитные очки', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('lawn-mower', array['Травосборник', 'Насадка для мульчирования', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('lawn-scarifier', array['Травосборник', 'Валик-аэратор']::text[]),
  ('chainsaw', array['Шина', 'Цепь', 'Чехол для шины', 'Масло для цепи', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('hedge-trimmer', array['Чехол для ножей', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('pole-saw', array['Шина', 'Цепь', 'Ремень', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('wood-chipper', array['Толкатель', 'Мешок для щепы']::text[]),
  ('tiller', array['Фрезы', 'Плуг', 'Окучник', 'Грунтозацепы']::text[]),
  ('cultivator', array['Фрезы', 'Сошник']::text[]),
  ('snow-blower', array['Щётка-скребок', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('leaf-blower', array['Труба', 'Мешок для листьев', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('garden-vacuum', array['Труба', 'Мешок для листьев']::text[]),
  ('construction-vacuum', array['Шланг', 'Трубка', 'Насадка для пола', 'Щелевая насадка', 'Мешок', 'Фильтр', 'Переходник для инструмента']::text[]),
  ('industrial-vacuum', array['Шланг', 'Трубка', 'Насадка для пола', 'Фильтр', 'Мешок']::text[]),
  ('washing-vacuum', array['Шланг', 'Насадка для пола', 'Насадка для мебели', 'Моющее средство']::text[]),
  ('extractor', array['Шланг', 'Ручная насадка', 'Насадка для пола', 'Моющее средство']::text[]),
  ('steam-cleaner', array['Шланг', 'Насадки', 'Салфетки']::text[]),
  ('pressure-washer', array['Пистолет', 'Шланг высокого давления', 'Насадка', 'Пенная насадка', 'Грязевая фреза']::text[]),
  ('hot-pressure-washer', array['Пистолет', 'Шланг высокого давления', 'Насадки']::text[]),
  ('scrubber-dryer', array['Щётка', 'Падодержатель', 'Скребок']::text[]),
  ('sweeper', array['Боковая щётка', 'Контейнер для мусора']::text[]),
  ('floor-polisher', array['Щётка', 'Падодержатель', 'Пады', 'Бак для раствора']::text[]),
  ('heat-gun-big', array['Газовый шланг', 'Редуктор']::text[]),
  ('infrared-heater', array['Подставка']::text[]),
  ('convector', array['Ножки']::text[]),
  ('dehumidifier', array['Сливной шланг']::text[]),
  ('adsorption-dryer', array['Воздуховод']::text[]),
  ('fan', array['Подставка']::text[]),
  ('mobile-ac', array['Воздуховод', 'Оконная вставка', 'Пульт']::text[]),
  ('air-cooler', array['Пульт', 'Аккумуляторы холода']::text[]),
  ('generator', array['Колёса', 'Ключ', 'Розеточный блок']::text[]),
  ('inverter-generator', array['Кабель для зарядки 12 В']::text[]),
  ('welding-generator', array['Сварочные кабели', 'Электрододержатель', 'Клемма заземления']::text[]),
  ('extension-reel', '{}'::text[]),
  ('site-board', array['Вводной кабель', 'Ключ от щита']::text[]),
  ('stabilizer', array['Кабель']::text[]),
  ('floodlight', array['Штатив', 'Удлинитель']::text[]),
  ('light-tower', '{}'::text[]),
  ('piston-compressor', array['Шланг', 'Пневмопистолет', 'Быстросъёмный разъём', 'Масло']::text[]),
  ('screw-compressor', array['Шланг', 'Масло']::text[]),
  ('mobile-compressor', array['Шланг']::text[]),
  ('drainage-pump', array['Шланг', 'Хомуты', 'Трос']::text[]),
  ('sewage-pump', array['Шланг', 'Хомуты', 'Трос']::text[]),
  ('motor-pump', array['Всасывающий рукав', 'Напорный рукав', 'Хомуты']::text[]),
  ('vibration-pump', array['Шланг', 'Трос', 'Хомуты']::text[]),
  ('mma-welder', array['Сварочные кабели', 'Электрододержатель', 'Клемма заземления', 'Маска', 'Щётка']::text[]),
  ('mig-welder', array['Горелка', 'Клемма заземления', 'Газовый редуктор', 'Проволока', 'Маска']::text[]),
  ('tig-welder', array['Горелка', 'Клемма заземления', 'Газовый редуктор', 'Вольфрамовые электроды', 'Маска']::text[]),
  ('plasma-cutter', array['Плазмотрон', 'Клемма заземления', 'Сопла и электроды', 'Циркульный упор']::text[]),
  ('gas-cutter', array['Резак', 'Шланги', 'Редукторы']::text[]),
  ('pp-welder', array['Кейс', 'Насадки', 'Подставка', 'Ножницы для труб']::text[]),
  ('press-tool', array['Кейс', 'Пресс-клещи', 'Аккумулятор', 'Зарядное устройство']::text[]),
  ('butt-welder', array['Центратор', 'Нагревательный элемент', 'Торцеватель', 'Вкладыши']::text[]),
  ('pipe-threader', array['Кейс', 'Резьбонарезные головки', 'Упор']::text[]),
  ('pipe-bender', array['Кейс', 'Гибочные башмаки']::text[]),
  ('pipe-cutter', array['Запасной ролик']::text[]),
  ('drain-cleaner', array['Спирали', 'Насадки', 'Перчатки']::text[]),
  ('pipe-camera', array['Кейс', 'Монитор', 'Зарядное устройство']::text[]),
  ('pressure-tester', array['Шланг', 'Манометр']::text[]),
  ('laser-level', array['Кейс', 'Штатив', 'Мишень', 'Очки для лазера', 'Приёмник', 'Батарейки']::text[]),
  ('rotary-laser', array['Кейс', 'Штатив', 'Приёмник', 'Рейка', 'Пульт']::text[]),
  ('optical-level', array['Кейс', 'Штатив', 'Рейка']::text[]),
  ('laser-meter', array['Чехол', 'Батарейки']::text[]),
  ('measuring-wheel', array['Чехол']::text[]),
  ('wall-scanner', array['Чехол', 'Батарейки']::text[]),
  ('thermal-camera', array['Кейс', 'Кабель USB', 'Зарядное устройство']::text[]),
  ('moisture-meter', array['Чехол', 'Батарейки']::text[]),
  ('scaffold', array['Рамы', 'Диагонали', 'Горизонтали', 'Настилы', 'Башмаки']::text[]),
  ('mobile-tower', array['Рамы', 'Диагонали', 'Настилы', 'Колёса', 'Стабилизаторы']::text[]),
  ('ladder', array['Стабилизатор-траверса']::text[]),
  ('stepladder', '{}'::text[]),
  ('scissor-lift', array['Зарядное устройство']::text[]),
  ('facade-cradle', array['Лебёдки', 'Страховочные канаты', 'Консоли']::text[]),
  ('drywall-lift', array['Удлинители']::text[]),
  ('winch', array['Трос', 'Крюк', 'Пульт']::text[]),
  ('hoist', array['Цепь', 'Крюк']::text[]),
  ('bottle-jack', array['Рукоять']::text[]),
  ('rack-jack', '{}'::text[]),
  ('pallet-truck', '{}'::text[]),
  ('hand-truck', '{}'::text[]),
  ('airless-sprayer', array['Пистолет', 'Шланг', 'Сопло', 'Удлинитель пистолета']::text[]),
  ('paint-sprayer', array['Ёмкость для краски', 'Сопла', 'Воронка']::text[]),
  ('plaster-machine', array['Растворный шланг', 'Воздушный шланг', 'Пистолет']::text[]),
  ('putty-sprayer', array['Шланг', 'Пистолет', 'Сопла']::text[]),
  ('bearing-puller', array['Кейс', 'Захваты']::text[]),
  ('spring-compressor', array['Кейс', 'Захваты']::text[]),
  ('car-polisher', array['Кейс', 'Полировальные круги', 'Подошва']::text[]),
  ('paint-gauge', array['Чехол', 'Калибровочные пластины']::text[]),
  ('jump-starter', array['Кабели с зажимами', 'Зарядное устройство']::text[]),
  ('battery-charger', array['Кабели с зажимами']::text[]),
  ('trolley-jack', array['Рукоять']::text[]),
  ('jack-stands', '{}'::text[])
)
insert into public.iva_kit_items (kind, items)
select v.kind, v.items from v where exists (select 1 from public.iva_kinds k where k.key = v.kind)
on conflict (kind) do update set items = excluded.items
  where iva_kit_items.items is distinct from excluded.items;

-- ─────────────────────────────── 2. Комплектация у объявления ───────────────────────────────
alter table public.tools add column if not exists kit text[];
do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'tools_kit_chk' and conrelid = 'public.tools'::regclass) then
    alter table public.tools add constraint tools_kit_chk check (kit is null or cardinality(kit) <= 30);
  end if;
end $$;

-- ─────────────────────────────── 3. Предложения моделей ───────────────────────────────
create table if not exists public.iva_model_proposals (
  id          bigint generated always as identity primary key,
  kind        text not null references public.iva_kinds(key) on update cascade on delete cascade,
  brand       text not null check (char_length(brand) between 1 and 40),
  model       text not null check (char_length(model) between 1 and 60),
  title       text not null check (char_length(title) between 3 and 120),
  specs       jsonb not null default '{}' check (jsonb_typeof(specs) = 'object' and pg_column_size(specs) < 4000),
  proposed_by text not null default public.jwt_email(),
  status      text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  model_id    bigint references public.iva_models(id) on delete set null,
  created_at  timestamptz not null default now(),
  decided_at  timestamptz
);
create unique index if not exists iva_model_proposals_pending_uq
  on public.iva_model_proposals (kind, lower(brand), lower(model)) where status = 'pending';
create index if not exists iva_model_proposals_status_idx on public.iva_model_proposals (status, created_at);

-- пробелы, бренд как в справочнике, лимит, «уже есть в справочнике»
create or replace function public.iva_model_proposal_check() returns trigger
language plpgsql security definer set search_path = public, pg_temp
as $$
declare b text;
begin
  new.brand := btrim(regexp_replace(coalesce(new.brand, ''), '\s+', ' ', 'g'));
  new.model := btrim(regexp_replace(coalesce(new.model, ''), '\s+', ' ', 'g'));
  new.title := btrim(regexp_replace(coalesce(new.title, ''), '\s+', ' ', 'g'));
  select name into b from public.iva_brands where lower(name) = lower(new.brand) limit 1;
  if b is not null then new.brand := b; end if;
  if tg_op = 'INSERT' then
    if exists (select 1 from public.iva_models m where m.active and m.kind = new.kind
                 and lower(m.brand) = lower(new.brand) and lower(m.model) = lower(new.model)) then
      raise exception 'model already in directory' using errcode = '23505';
    end if;
    if (select count(*) from public.iva_model_proposals p
          where p.proposed_by = new.proposed_by and p.status = 'pending') >= 30 then
      raise exception 'too many pending proposals' using errcode = '54000';
    end if;
  end if;
  return new;
end $$;
drop trigger if exists trg_iva_model_proposal_check on public.iva_model_proposals;
create trigger trg_iva_model_proposal_check before insert or update on public.iva_model_proposals
  for each row execute function public.iva_model_proposal_check();
revoke all on function public.iva_model_proposal_check() from public, anon, authenticated;

alter table public.iva_model_proposals enable row level security;
revoke all on public.iva_model_proposals from anon, authenticated;
grant select, insert, update, delete on public.iva_model_proposals to authenticated;
grant usage on sequence public.iva_model_proposals_id_seq to authenticated;
drop policy if exists iva_model_proposals_read on public.iva_model_proposals;
create policy iva_model_proposals_read on public.iva_model_proposals for select to authenticated
  using (proposed_by = public.jwt_email() or public.fn_is_chief());
drop policy if exists iva_model_proposals_add on public.iva_model_proposals;
create policy iva_model_proposals_add on public.iva_model_proposals for insert to authenticated
  with check (public.fn_is_admin() and public.jwt_email() <> '' and proposed_by = public.jwt_email()
              and status = 'pending' and model_id is null and decided_at is null);
drop policy if exists iva_model_proposals_chief on public.iva_model_proposals;
create policy iva_model_proposals_chief on public.iva_model_proposals for update to authenticated
  using (public.fn_is_chief()) with check (public.fn_is_chief());
drop policy if exists iva_model_proposals_chief_del on public.iva_model_proposals;
create policy iva_model_proposals_chief_del on public.iva_model_proposals for delete to authenticated
  using (public.fn_is_chief());

-- ─────────────────────────────── 4. Решение главного админа ───────────────────────────────
create or replace function public.iva_model_decide(p_id bigint, p_approve boolean,
    p_brand text default null, p_model text default null, p_title text default null)
returns bigint
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  p  public.iva_model_proposals%rowtype;
  b  text; m text; t text; b2 text; sp jsonb; mid bigint;
begin
  if not public.fn_is_chief() then
    raise exception 'only chief' using errcode = '42501';
  end if;
  select * into p from public.iva_model_proposals where id = p_id for update;
  if not found then
    raise exception 'proposal not found' using errcode = 'P0002';
  end if;
  if p.status <> 'pending' then
    raise exception 'already decided' using errcode = '22023';
  end if;
  if not coalesce(p_approve, false) then
    update public.iva_model_proposals set status = 'rejected', decided_at = now() where id = p_id;
    return null;
  end if;
  b := nullif(btrim(regexp_replace(coalesce(p_brand, p.brand), '\s+', ' ', 'g')), '');
  m := nullif(btrim(regexp_replace(coalesce(p_model, p.model), '\s+', ' ', 'g')), '');
  t := nullif(btrim(regexp_replace(coalesce(p_title, p.title), '\s+', ' ', 'g')), '');
  if b is null or m is null or t is null or char_length(b) > 40 or char_length(m) > 60 or char_length(t) > 120 then
    raise exception 'brand, model and title are required' using errcode = '22023';
  end if;
  select name into b2 from public.iva_brands where lower(name) = lower(b) limit 1;
  if b2 is null then
    insert into public.iva_brands (name) values (b);
  else
    b := b2;
  end if;
  -- только поля, которые есть у вида
  select coalesce(jsonb_object_agg(e.key, e.value), '{}'::jsonb) into sp
  from jsonb_each(p.specs) e
  where exists (select 1 from public.iva_spec_fields f where f.kind = p.kind and f.key = e.key);
  insert into public.iva_models (kind, brand, model, title, specs, source, active)
  values (p.kind, b, m, t, sp, 'proposal', true)
  on conflict (kind, brand, model) do update
    set source = case when iva_models.active then iva_models.source else 'proposal' end,
        title  = case when iva_models.active then iva_models.title  else excluded.title end,
        specs  = case when iva_models.active then iva_models.specs  else excluded.specs end,
        active = true
  returning id into mid;
  update public.iva_model_proposals
     set status = 'approved', decided_at = now(), model_id = mid, brand = b, model = m, title = t
   where id = p_id;
  -- объявления предложившего админа без модели, того же вида, с моделью в названии
  update public.tools tl set model_id = mid
   where tl.model_id is null and tl.kind = p.kind
     and lower(btrim(coalesce(tl.owner_email, ''))) = p.proposed_by
     and position(lower(m) in lower(coalesce(tl.name, ''))) > 0;
  return mid;
end $$;
revoke all on function public.iva_model_decide(bigint, boolean, text, text, text) from public, anon;
grant execute on function public.iva_model_decide(bigint, boolean, text, text, text) to authenticated;

commit;

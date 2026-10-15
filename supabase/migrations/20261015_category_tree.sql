-- Ива 10.79 — новое дерево категорий (15 категорий, в каждой «Другое») и «Вид инструмента».
-- Решение владельца 15.10.2026 (журнал, решение №28).
--
-- Что делает (всё повторяемо: второй запуск ничего не портит и не дублирует):
--   1. cats    — у старых категорий меняются только названия и порядок (ключи прежние: power, construction,
--                garden, auto, welding, measuring, generators, cleaning, moving); добавляются 6 новых.
--   2. subcats — старые подкатегории переименовываются / переносятся (ключи прежние), «На аккумуляторе» и
--                «От сети 220В» выключаются (active=false, не удаляются: питание станет характеристикой);
--                добавляются новые подкатегории и «Другое» в каждой категории.
--   3. iva_kinds — справочник «Вид инструмента» (вид → категория и подкатегория). Читают все, меняет главный админ.
--   4. tools.kind — вид у объявления (необязательно). Неизвестный вид база не примет.
--   5. iva_sub_guess / iva_tree_plan — правило «куда перенести» (одно для просмотра и переноса, только чтение).
--   6. В конце — ПРОСМОТР: какие объявления куда предлагается перенести. Сам перенос — отдельный файл
--      20261015_category_tree_move.sql, только после «да» владельца.
-- Объявления этим файлом НЕ меняются. Сайт и приложение читают категории из базы — новое дерево появится сразу.
--
-- Откат (всё вернуть как было до этого файла) — в самом конце, закомментирован.

begin;

-- ─────────────────────────────── 1. Категории ───────────────────────────────
-- Значки новых категорий — в стиле значков админки (оранжевый контур, 24×24).
with t(key, title, sort, icon) as (values
  ('power',        'Электроинструмент',             1,  null),
  ('cutting',      'Резка и бурение',                2,  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#ff8f1f" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="2"/><path d="M12 4v3M12 17v3M4 12h3M17 12h3"/></svg>'),
  ('construction', 'Бетонные и дорожные работы',     3,  null),
  ('surface',      'Шлифование поверхностей',        4,  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#ff8f1f" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="14" width="18" height="6" rx="2"/><path d="M8 14V9a4 4 0 0 1 8 0v5M12 5V3"/></svg>'),
  ('garden',       'Садовая техника',                5,  null),
  ('cleaning',     'Уборка и мойка',                 6,  null),
  ('climate',      'Обогрев и осушение',             7,  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#ff8f1f" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22c4 0 7-3 7-7 0-4-3-6-4-9-1 2-2 3-4 3 0-2 0-4-2-7C8 6 5 9 5 15c0 4 3 7 7 7z"/></svg>'),
  ('generators',   'Электроснабжение и освещение',   8,  null),
  ('compressors',  'Компрессоры и насосы',           9,  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#ff8f1f" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="13" r="8"/><path d="M12 13l4-4M8 3h8"/></svg>'),
  ('welding',      'Сварочное оборудование',         10, null),
  ('plumbing',     'Сантехнический и монтажный',     11, '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#ff8f1f" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 8h10a4 4 0 0 1 4 4v8M3 5v6M13 20h8"/></svg>'),
  ('measuring',    'Измерительные приборы',          12, null),
  ('moving',       'Высота и перемещение грузов',    13, null),
  ('painting',     'Покраска и штукатурка',          14, '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#ff8f1f" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="15" height="6" rx="1.5"/><path d="M18 6h3v5h-9v3M10 14h4v7h-4z"/></svg>'),
  ('auto',         'Автоинструмент',                 15, null)
), upd as (
  update public.cats c set title = t.title, sort = t.sort, active = true
  from t where c.key = t.key
  returning c.key
)
insert into public.cats (key, title, sort, icon, active)
select t.key, t.title, t.sort, t.icon, true from t
where not exists (select 1 from public.cats c where c.key = t.key)
  and t.key not in (select key from upd);

-- ─────────────────────────────── 2. Подкатегории ───────────────────────────────
-- Старые ключи остаются (на них могут ссылаться объявления); меняются название, категория, порядок.
-- «Леса и вышки» переезжают из «Стройоборудования» в «Высоту и перемещение грузов».
-- Сопоставление идёт по ключу и старой ИЛИ новой категории — второй запуск находит уже перенесённые строки.
with t(key, old_cat, cat, title, sort) as (values
  ('drills',      'power',        'power',        'Дрели, шуруповёрты и гайковёрты', 1),
  ('hammers',     'power',        'power',        'Перфораторы',                     2),
  ('grinders',    'power',        'power',        'Болгарки (УШМ)',                  4),
  ('concrete',    'construction', 'construction', 'Бетономешалки и растворы',        1),
  ('lawnmowers',  'garden',       'garden',       'Покос травы и газон',             1),
  ('trimmers',    'garden',       'garden',       'Обрезка деревьев и кустов',       2),
  ('scaffolding', 'construction', 'moving',       'Леса, вышки и лестницы',          1),
  ('hand-trucks', 'moving',       'moving',       'Лебёдки, домкраты и тележки',     3),
  ('semniki',     'auto',         'auto',         'Съёмники',                        1),
  ('dlya-lkp',    'auto',         'auto',         'Полировка и ЛКП',                 2)
)
update public.subcats s set cat = t.cat, title = t.title, sort = t.sort, active = true
from t where s.key = t.key and s.cat in (t.old_cat, t.cat);

-- Питание — не подкатегория: выключаем (строки остаются, объявления с ними видны в просмотре ниже).
update public.subcats set active = false where cat = 'power' and key in ('cordless', 'corded');

-- Новые подкатегории и «Другое» (sort 99) в каждой категории. Добавляются, только если такой пары
-- (категория, ключ) ещё нет; если есть — приводятся к названию и порядку из списка.
with t(cat, key, title, sort) as (values
  ('power',        'power-breakers',    'Отбойные молотки',                         3),
  ('power',        'power-saws',        'Пилы',                                     5),
  ('power',        'power-sanders',     'Шлифмашины, рубанки и фрезеры',            6),
  ('power',        'power-misc',        'Фены, миксеры и прочее',                   7),
  ('power',        'power-fasteners',   'Крепёжный инструмент',                     8),
  ('power',        'power-other',       'Другое',                                   99),
  ('cutting',      'cut-diamond',       'Алмазное бурение',                         1),
  ('cutting',      'cut-chasers',       'Штроборезы',                               2),
  ('cutting',      'cut-tile',          'Плиткорезы и камнерезы',                   3),
  ('cutting',      'cut-concrete',      'Резка бетона и швов',                      4),
  ('cutting',      'cutting-other',     'Другое',                                   99),
  ('construction', 'con-vibro',         'Виброоборудование для бетона',             2),
  ('construction', 'con-compact',       'Уплотнение грунта',                        3),
  ('construction', 'con-trowel',        'Затирка бетона',                           4),
  ('construction', 'con-rebar',         'Работа с арматурой',                       5),
  ('construction', 'con-formwork',      'Опалубка и стойки',                        6),
  ('construction', 'con-earth',         'Земляные работы',                          7),
  ('construction', 'construction-other','Другое',                                   99),
  ('surface',      'sur-wood',          'Деревянные полы',                          1),
  ('surface',      'sur-concrete',      'Бетон и камень',                           2),
  ('surface',      'sur-walls',         'Стены и потолки',                          3),
  ('surface',      'surface-other',     'Другое',                                   99),
  ('garden',       'gar-soil',          'Обработка почвы',                          3),
  ('garden',       'gar-cleanup',       'Уборка участка и снега',                   4),
  ('garden',       'garden-other',      'Другое',                                   99),
  ('cleaning',     'cln-vacuum',        'Строительные пылесосы',                    1),
  ('cleaning',     'cln-wash',          'Моющие пылесосы и химчистка',              2),
  ('cleaning',     'cln-pressure',      'Мойки высокого давления',                  3),
  ('cleaning',     'cln-floor',         'Поломоечные и подметальные машины',        4),
  ('cleaning',     'cleaning-other',    'Другое',                                   99),
  ('climate',      'clm-heat',          'Тепловые пушки и обогреватели',            1),
  ('climate',      'clm-dry',           'Осушители воздуха',                        2),
  ('climate',      'clm-fan',           'Вентиляторы и охлаждение',                 3),
  ('climate',      'climate-other',     'Другое',                                   99),
  ('generators',   'gen-gen',           'Генераторы',                               1),
  ('generators',   'gen-power',         'Удлинители, щиты и стабилизаторы',         2),
  ('generators',   'gen-light',         'Освещение и мачты',                        3),
  ('generators',   'generators-other',  'Другое',                                   99),
  ('compressors',  'cmp-air',           'Компрессоры',                              1),
  ('compressors',  'cmp-pumps',         'Насосы и мотопомпы',                       2),
  ('compressors',  'compressors-other', 'Другое',                                   99),
  ('welding',      'wld-weld',          'Сварочные аппараты',                       1),
  ('welding',      'wld-cut',           'Плазменная и газовая резка',               2),
  ('welding',      'welding-other',     'Другое',                                   99),
  ('plumbing',     'plb-solder',        'Пайка и прессование труб',                 1),
  ('plumbing',     'plb-thread',        'Резьба и гибка труб',                      2),
  ('plumbing',     'plb-clean',         'Прочистка и диагностика труб',             3),
  ('plumbing',     'plumbing-other',    'Другое',                                   99),
  ('measuring',    'msr-level',         'Уровни и нивелиры',                        1),
  ('measuring',    'msr-range',         'Дальномеры и разметка',                    2),
  ('measuring',    'msr-detect',        'Детекторы и диагностика',                  3),
  ('measuring',    'measuring-other',   'Другое',                                   99),
  ('moving',       'mov-lift',          'Подъёмники',                               2),
  ('moving',       'moving-other',      'Другое',                                   99),
  ('painting',     'pnt-spray',         'Окрасочные аппараты и краскопульты',       1),
  ('painting',     'pnt-plaster',       'Штукатурные станции',                      2),
  ('painting',     'painting-other',    'Другое',                                   99),
  ('auto',         'aut-start',         'Пуско-зарядные',                           3),
  ('auto',         'aut-lift',          'Подъём авто',                              4),
  ('auto',         'auto-other',        'Другое',                                   99)
), upd as (
  update public.subcats s set title = t.title, sort = t.sort, active = true
  from t where s.cat = t.cat and s.key = t.key
  returning s.cat, s.key
)
insert into public.subcats (cat, key, title, sort, active)
select t.cat, t.key, t.title, t.sort, true from t
where not exists (select 1 from public.subcats s where s.cat = t.cat and s.key = t.key)
  and (t.cat, t.key) not in (select cat, key from upd);

-- ─────────────────────────────── 3. Вид инструмента ───────────────────────────────
create table if not exists public.iva_kinds (
  key    text primary key,
  cat    text not null,
  sub    text not null,
  title  text not null,
  sort   int  not null default 0,
  active boolean not null default true
);
alter table public.iva_kinds enable row level security;
revoke all on public.iva_kinds from anon, authenticated;
grant select on public.iva_kinds to anon, authenticated;
grant insert, update, delete on public.iva_kinds to authenticated;
drop policy if exists iva_kinds_read on public.iva_kinds;
create policy iva_kinds_read on public.iva_kinds for select to anon, authenticated using (true);
drop policy if exists iva_kinds_chief on public.iva_kinds;
create policy iva_kinds_chief on public.iva_kinds for all to authenticated
  using (public.fn_is_chief()) with check (public.fn_is_chief());

-- Список видов. Повторный запуск приводит строки к этому списку (свои добавленные виды не трогает).
insert into public.iva_kinds (key, cat, sub, title, sort) values
  -- Электроинструмент
  ('drill',              'power', 'drills',          'Дрель',                              1),
  ('screwdriver',        'power', 'drills',          'Шуруповёрт',                         2),
  ('impact-wrench',      'power', 'drills',          'Гайковёрт',                          3),
  ('magnetic-drill',     'power', 'drills',          'Магнитная дрель',                    4),
  ('rotary-sds-plus',    'power', 'hammers',         'Перфоратор SDS-plus',                1),
  ('rotary-sds-max',     'power', 'hammers',         'Перфоратор SDS-max',                 2),
  ('breaker',            'power', 'power-breakers',  'Отбойный молоток',                   1),
  ('demolition-hammer',  'power', 'power-breakers',  'Бетонолом',                          2),
  ('pneumatic-hammer',   'power', 'power-breakers',  'Пневмомолоток',                      3),
  ('grinder-125',        'power', 'grinders',        'УШМ 115–125 мм',                     1),
  ('grinder-180',        'power', 'grinders',        'УШМ 150–180 мм',                     2),
  ('grinder-230',        'power', 'grinders',        'УШМ 230 мм',                         3),
  ('circular-saw',       'power', 'power-saws',      'Циркулярная пила',                   1),
  ('jigsaw',             'power', 'power-saws',      'Лобзик',                             2),
  ('reciprocating-saw',  'power', 'power-saws',      'Сабельная пила',                     3),
  ('miter-saw',          'power', 'power-saws',      'Торцовочная пила',                   4),
  ('table-saw',          'power', 'power-saws',      'Настольная пила',                    5),
  ('plunge-saw',         'power', 'power-saws',      'Погружная пила',                     6),
  ('metal-cutoff-saw',   'power', 'power-saws',      'Монтажная пила по металлу',          7),
  ('orbital-sander',     'power', 'power-sanders',   'Эксцентриковая шлифмашина',          1),
  ('belt-sander',        'power', 'power-sanders',   'Ленточная шлифмашина',               2),
  ('finishing-sander',   'power', 'power-sanders',   'Вибрационная шлифмашина',            3),
  ('electric-planer',    'power', 'power-sanders',   'Электрорубанок',                     4),
  ('router',             'power', 'power-sanders',   'Фрезер',                             5),
  ('heat-gun',           'power', 'power-misc',      'Строительный фен',                   1),
  ('mixer-drill',        'power', 'power-misc',      'Строительный миксер',                2),
  ('multitool',          'power', 'power-misc',      'Реноватор (МФИ)',                    3),
  ('metal-shears',       'power', 'power-misc',      'Ножницы по металлу',                 4),
  ('powder-nailer',      'power', 'power-fasteners', 'Пороховой монтажный пистолет',       1),
  ('gas-nailer',         'power', 'power-fasteners', 'Газовый монтажный пистолет',         2),
  ('nailer',             'power', 'power-fasteners', 'Гвоздезабивной пистолет (нейлер)',   3),
  ('stapler',            'power', 'power-fasteners', 'Строительный степлер',               4),
  ('riveter',            'power', 'power-fasteners', 'Заклёпочник',                        5),
  -- Резка и бурение
  ('core-drill-hand',    'cutting', 'cut-diamond',   'Установка алмазного бурения ручная', 1),
  ('core-drill-stand',   'cutting', 'cut-diamond',   'Установка алмазного бурения на станине', 2),
  ('wall-chaser',        'cutting', 'cut-chasers',   'Штроборез',                          1),
  ('tile-cutter-manual', 'cutting', 'cut-tile',      'Ручной плиткорез',                   1),
  ('tile-cutter-electric','cutting','cut-tile',      'Электрический плиткорез',            2),
  ('stone-saw',          'cutting', 'cut-tile',      'Камнерезный станок',                 3),
  ('power-cutter',       'cutting', 'cut-concrete',  'Бензорез',                           1),
  ('floor-saw',          'cutting', 'cut-concrete',  'Швонарезчик',                        2),
  ('wall-saw',           'cutting', 'cut-concrete',  'Стенорезная машина',                 3),
  ('wire-saw',           'cutting', 'cut-concrete',  'Канатная пила',                      4),
  -- Бетонные и дорожные работы
  ('concrete-mixer',     'construction', 'concrete',     'Бетономешалка',                  1),
  ('mortar-mixer',       'construction', 'concrete',     'Растворосмеситель',              2),
  ('poker-vibrator',     'construction', 'con-vibro',    'Глубинный вибратор',             1),
  ('screed-vibrator',    'construction', 'con-vibro',    'Виброрейка',                     2),
  ('surface-vibrator',   'construction', 'con-vibro',    'Площадочный вибратор',           3),
  ('plate-compactor',    'construction', 'con-compact',  'Виброплита',                     1),
  ('rammer',             'construction', 'con-compact',  'Вибротрамбовка',                 2),
  ('roller',             'construction', 'con-compact',  'Виброкаток',                     3),
  ('power-trowel',       'construction', 'con-trowel',   'Затирочная машина',              1),
  ('rebar-cutter',       'construction', 'con-rebar',    'Станок для резки арматуры',      1),
  ('rebar-bender',       'construction', 'con-rebar',    'Станок для гибки арматуры',      2),
  ('rebar-tier',         'construction', 'con-rebar',    'Пистолет для вязки арматуры',    3),
  ('wall-formwork',      'construction', 'con-formwork', 'Стеновая опалубка',              1),
  ('slab-formwork',      'construction', 'con-formwork', 'Опалубка перекрытий',            2),
  ('props',              'construction', 'con-formwork', 'Телескопические стойки',         3),
  ('earth-auger',        'construction', 'con-earth',    'Мотобур',                        1),
  ('trencher',           'construction', 'con-earth',    'Траншеекопатель',                2),
  -- Шлифование поверхностей
  ('drum-floor-sander',  'surface', 'sur-wood',      'Паркетошлифовальная машина',         1),
  ('flat-floor-sander',  'surface', 'sur-wood',      'Плоскошлифовальная машина',          2),
  ('edge-sander',        'surface', 'sur-wood',      'Угловая шлифмашина для паркета',     3),
  ('concrete-grinder',   'surface', 'sur-concrete',  'Мозаично-шлифовальная машина',       1),
  ('scarifier',          'surface', 'sur-concrete',  'Фрезеровальная машина по бетону',    2),
  ('drywall-sander-long','surface', 'sur-walls',     'Шлифмашина «жираф»',                 1),
  ('wall-sander',        'surface', 'sur-walls',     'Шлифмашина для стен',                2),
  -- Садовая техника
  ('trimmer',            'garden', 'lawnmowers',     'Триммер',                            1),
  ('lawn-mower',         'garden', 'lawnmowers',     'Газонокосилка',                      2),
  ('lawn-scarifier',     'garden', 'lawnmowers',     'Скарификатор (аэратор)',             3),
  ('chainsaw',           'garden', 'trimmers',       'Цепная пила',                        1),
  ('hedge-trimmer',      'garden', 'trimmers',       'Кусторез',                           2),
  ('pole-saw',           'garden', 'trimmers',       'Высоторез',                          3),
  ('wood-chipper',       'garden', 'trimmers',       'Измельчитель веток',                 4),
  ('tiller',             'garden', 'gar-soil',       'Мотоблок',                           1),
  ('cultivator',         'garden', 'gar-soil',       'Культиватор',                        2),
  ('snow-blower',        'garden', 'gar-cleanup',    'Снегоуборщик',                       1),
  ('leaf-blower',        'garden', 'gar-cleanup',    'Воздуходувка',                       2),
  ('garden-vacuum',      'garden', 'gar-cleanup',    'Садовый пылесос',                    3),
  -- Уборка и мойка
  ('construction-vacuum','cleaning', 'cln-vacuum',   'Строительный пылесос',               1),
  ('industrial-vacuum',  'cleaning', 'cln-vacuum',   'Промышленный пылесос',               2),
  ('washing-vacuum',     'cleaning', 'cln-wash',     'Моющий пылесос',                     1),
  ('extractor',          'cleaning', 'cln-wash',     'Экстрактор (химчистка)',             2),
  ('steam-cleaner',      'cleaning', 'cln-wash',     'Пароочиститель',                     3),
  ('pressure-washer',    'cleaning', 'cln-pressure', 'Мойка высокого давления',            1),
  ('hot-pressure-washer','cleaning', 'cln-pressure', 'Мойка с подогревом воды',            2),
  ('scrubber-dryer',     'cleaning', 'cln-floor',    'Поломоечная машина',                 1),
  ('sweeper',            'cleaning', 'cln-floor',    'Подметальная машина',                2),
  ('floor-polisher',     'cleaning', 'cln-floor',    'Роторная машина (полотёр)',          3),
  -- Обогрев и осушение
  ('heat-gun-big',       'climate', 'clm-heat',      'Тепловая пушка',                     1),
  ('infrared-heater',    'climate', 'clm-heat',      'Инфракрасный обогреватель',          2),
  ('convector',          'climate', 'clm-heat',      'Конвектор',                          3),
  ('dehumidifier',       'climate', 'clm-dry',       'Осушитель воздуха',                  1),
  ('adsorption-dryer',   'climate', 'clm-dry',       'Адсорбционный осушитель',            2),
  ('fan',                'climate', 'clm-fan',       'Вентилятор',                         1),
  ('mobile-ac',          'climate', 'clm-fan',       'Мобильный кондиционер',              2),
  ('air-cooler',         'climate', 'clm-fan',       'Охладитель воздуха',                 3),
  -- Электроснабжение и освещение
  ('generator',          'generators', 'gen-gen',    'Генератор',                          1),
  ('inverter-generator', 'generators', 'gen-gen',    'Инверторный генератор',              2),
  ('welding-generator',  'generators', 'gen-gen',    'Сварочный генератор',                3),
  ('extension-reel',     'generators', 'gen-power',  'Удлинитель на катушке',              1),
  ('site-board',         'generators', 'gen-power',  'Строительный электрощит',            2),
  ('stabilizer',         'generators', 'gen-power',  'Стабилизатор напряжения',            3),
  ('floodlight',         'generators', 'gen-light',  'Прожектор',                          1),
  ('light-tower',        'generators', 'gen-light',  'Осветительная мачта',                2),
  -- Компрессоры и насосы
  ('piston-compressor',  'compressors', 'cmp-air',   'Поршневой компрессор',               1),
  ('screw-compressor',   'compressors', 'cmp-air',   'Винтовой компрессор',                2),
  ('mobile-compressor',  'compressors', 'cmp-air',   'Передвижной компрессор',             3),
  ('drainage-pump',      'compressors', 'cmp-pumps', 'Дренажный насос',                    1),
  ('sewage-pump',        'compressors', 'cmp-pumps', 'Фекальный насос',                    2),
  ('motor-pump',         'compressors', 'cmp-pumps', 'Мотопомпа',                          3),
  ('vibration-pump',     'compressors', 'cmp-pumps', 'Вибрационный насос',                 4),
  -- Сварочное оборудование
  ('mma-welder',         'welding', 'wld-weld',      'Сварочный инвертор (MMA)',           1),
  ('mig-welder',         'welding', 'wld-weld',      'Полуавтомат (MIG/MAG)',              2),
  ('tig-welder',         'welding', 'wld-weld',      'Аргонодуговой (TIG)',                3),
  ('plasma-cutter',      'welding', 'wld-cut',       'Плазморез',                          1),
  ('gas-cutter',         'welding', 'wld-cut',       'Газовый резак',                      2),
  -- Сантехнический и монтажный
  ('pp-welder',          'plumbing', 'plb-solder',   'Паяльник для полипропилена',         1),
  ('press-tool',         'plumbing', 'plb-solder',   'Пресс-инструмент',                   2),
  ('butt-welder',        'plumbing', 'plb-solder',   'Аппарат стыковой сварки ПНД',        3),
  ('pipe-threader',      'plumbing', 'plb-thread',   'Электрический клупп',                1),
  ('pipe-bender',        'plumbing', 'plb-thread',   'Трубогиб',                           2),
  ('pipe-cutter',        'plumbing', 'plb-thread',   'Труборез',                           3),
  ('drain-cleaner',      'plumbing', 'plb-clean',    'Прочистная машина',                  1),
  ('pipe-camera',        'plumbing', 'plb-clean',    'Камера для осмотра труб',            2),
  ('pressure-tester',    'plumbing', 'plb-clean',    'Опрессовщик',                        3),
  -- Измерительные приборы
  ('laser-level',        'measuring', 'msr-level',   'Лазерный уровень',                   1),
  ('rotary-laser',       'measuring', 'msr-level',   'Ротационный нивелир',                2),
  ('optical-level',      'measuring', 'msr-level',   'Оптический нивелир',                 3),
  ('laser-meter',        'measuring', 'msr-range',   'Лазерный дальномер',                 1),
  ('measuring-wheel',    'measuring', 'msr-range',   'Измерительное колесо',               2),
  ('wall-scanner',       'measuring', 'msr-detect',  'Детектор проводки и металла',        1),
  ('thermal-camera',     'measuring', 'msr-detect',  'Тепловизор',                         2),
  ('moisture-meter',     'measuring', 'msr-detect',  'Влагомер',                           3),
  -- Высота и перемещение грузов
  ('scaffold',           'moving', 'scaffolding',    'Строительные леса',                  1),
  ('mobile-tower',       'moving', 'scaffolding',    'Вышка-тура',                         2),
  ('ladder',             'moving', 'scaffolding',    'Лестница',                           3),
  ('stepladder',         'moving', 'scaffolding',    'Стремянка',                          4),
  ('scissor-lift',       'moving', 'mov-lift',       'Ножничный подъёмник',                1),
  ('facade-cradle',      'moving', 'mov-lift',       'Фасадная люлька',                    2),
  ('drywall-lift',       'moving', 'mov-lift',       'Подъёмник для гипсокартона',         3),
  ('winch',              'moving', 'hand-trucks',    'Лебёдка',                            1),
  ('hoist',              'moving', 'hand-trucks',    'Таль',                               2),
  ('bottle-jack',        'moving', 'hand-trucks',    'Бутылочный домкрат',                 3),
  ('rack-jack',          'moving', 'hand-trucks',    'Реечный домкрат',                    4),
  ('pallet-truck',       'moving', 'hand-trucks',    'Гидравлическая тележка (рохля)',     5),
  ('hand-truck',         'moving', 'hand-trucks',    'Тележка',                            6),
  -- Покраска и штукатурка
  ('airless-sprayer',    'painting', 'pnt-spray',    'Безвоздушный окрасочный аппарат',    1),
  ('paint-sprayer',      'painting', 'pnt-spray',    'Краскопульт',                        2),
  ('plaster-machine',    'painting', 'pnt-plaster',  'Штукатурная станция',                1),
  ('putty-sprayer',      'painting', 'pnt-plaster',  'Аппарат для шпатлёвки',              2),
  -- Автоинструмент
  ('bearing-puller',     'auto', 'semniki',          'Съёмник подшипников',                1),
  ('spring-compressor',  'auto', 'semniki',          'Стяжка пружин',                      2),
  ('car-polisher',       'auto', 'dlya-lkp',         'Полировальная машина',               1),
  ('paint-gauge',        'auto', 'dlya-lkp',         'Толщиномер ЛКП',                     2),
  ('jump-starter',       'auto', 'aut-start',        'Пусковое устройство',                1),
  ('battery-charger',    'auto', 'aut-start',        'Зарядное устройство',                2),
  ('trolley-jack',       'auto', 'aut-lift',         'Подкатной домкрат',                  1),
  ('jack-stands',        'auto', 'aut-lift',         'Подставки под авто',                 2)
on conflict (key) do update
  set cat = excluded.cat, sub = excluded.sub, title = excluded.title, sort = excluded.sort, active = true;

-- ─────────────────────────────── 4. Вид у объявления ───────────────────────────────
alter table public.tools add column if not exists kind text;
do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'tools_kind_fkey' and conrelid = 'public.tools'::regclass) then
    alter table public.tools add constraint tools_kind_fkey
      foreign key (kind) references public.iva_kinds(key) on update cascade on delete set null;
  end if;
end $$;
create index if not exists tools_kind_idx on public.tools (kind);

-- ─────────────────────────────── 5. Правило переноса (для просмотра и для переноса) ───────────────────────────────
-- Куда перенести объявление с выключенной подкатегорией: по названию (Электроинструмент), иначе — «Другое»
-- своей категории. Регистр букв не важен.
create or replace function public.iva_sub_guess(p_cat text, p_name text) returns text
language sql immutable set search_path = public, pg_temp
as $$
  -- translate — строчные русские буквы при любой локали базы (lower в локали C русские буквы не меняет)
  select case
    when p_cat = 'power' and n ~ 'перфорат'                                then 'hammers'
    when p_cat = 'power' and n ~ 'отбойн|бетонолом'                        then 'power-breakers'
    when p_cat = 'power' and n ~ 'шуруп|дрел|гайков|винтов'                then 'drills'
    when p_cat = 'power' and n ~ 'болгар|ушм|углошлиф'                     then 'grinders'
    when p_cat = 'power' and n ~ 'пил[аыу]|лобзик'                         then 'power-saws'
    when p_cat = 'power' and n ~ 'шлиф|рубан|фрезер'                       then 'power-sanders'
    when p_cat = 'power' and n ~ 'фен|миксер|реноватор|ножниц'             then 'power-misc'
    when p_cat = 'power' and n ~ 'пистолет|нейлер|степлер|заклеп|заклёп'   then 'power-fasteners'
    else coalesce(p_cat, '') || '-other'
  end
  from (select translate(lower(coalesce(p_name, '')), 'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'абвгдеёжзийклмнопрстуфхцчшщъыьэюя') as n) x
$$;

-- План переноса: объявления, у которых подкатегория выключена или не существует в их категории.
--   * подкатегория переехала в другую категорию (например, «Леса и вышки») — переезжает и объявление, с той же
--     подкатегорией;
--   * иначе — подкатегория по названию (iva_sub_guess), если такая есть и включена;
--   * иначе — «Другое» своей категории; нет и его (неизвестная категория) — объявление не трогаем (new_sub пусто).
-- Только чтение. Вызывает просмотр ниже и шаг 2 (20261015_category_tree_move.sql) — правило одно.
create or replace function public.iva_tree_plan()
returns table(id text, name text, old_cat text, old_sub text, new_cat text, new_sub text)
language sql stable set search_path = public, pg_temp
as $$
  with bad as (
    select t.id::text as id, t.name, t.cat, t.sub,
           (select min(s.cat) from public.subcats s
             where s.key = t.sub and coalesce(s.active, true) and s.cat <> coalesce(t.cat, '')
            having count(distinct s.cat) = 1) as moved_cat
    from public.tools t
    where coalesce(t.sub, '') <> ''
      and not exists (select 1 from public.subcats s where s.cat = t.cat and s.key = t.sub and coalesce(s.active, true))
  )
  select b.id, b.name, b.cat, b.sub,
         coalesce(b.moved_cat, b.cat),
         case
           when b.moved_cat is not null then b.sub
           when exists (select 1 from public.subcats s where s.cat = b.cat and s.key = public.iva_sub_guess(b.cat, b.name)
                          and coalesce(s.active, true)) then public.iva_sub_guess(b.cat, b.name)
           when exists (select 1 from public.subcats s where s.cat = b.cat and s.key = b.cat || '-other'
                          and coalesce(s.active, true)) then b.cat || '-other'
         end
  from bad b
$$;
revoke all on function public.iva_tree_plan() from public, anon, authenticated;
revoke all on function public.iva_sub_guess(text, text) from public, anon, authenticated;

commit;

-- ─────────────────────────────── 6. ПРОСМОТР переноса (ничего не меняет) ───────────────────────────────
-- Объявления, у которых подкатегория выключена или не существует в своей категории, и куда их предлагается
-- перенести (new_cat / new_sub; пусто — не трогаем). Перенос — файл 20261015_category_tree_move.sql (та же функция),
-- только после «да» владельца.
select * from public.iva_tree_plan() order by old_cat, name;

-- ─────────────────────────────── Откат (не запускать без решения владельца) ───────────────────────────────
-- Вид у объявлений и справочник видов:
--   alter table public.tools drop constraint if exists tools_kind_fkey;
--   alter table public.tools drop column if exists kind;
--   drop table if exists public.iva_kinds;
--   drop function if exists public.iva_tree_plan();
--   drop function if exists public.iva_sub_guess(text, text);
-- Новые подкатегории и категории — выключить (не удалять):
--   update public.subcats set active = false where key in ('power-breakers','power-saws','power-sanders','power-misc',
--     'power-fasteners','cut-diamond','cut-chasers','cut-tile','cut-concrete','con-vibro','con-compact','con-trowel',
--     'con-rebar','con-formwork','con-earth','sur-wood','sur-concrete','sur-walls','gar-soil','gar-cleanup','cln-vacuum',
--     'cln-wash','cln-pressure','cln-floor','clm-heat','clm-dry','clm-fan','gen-gen','gen-power','gen-light','cmp-air',
--     'cmp-pumps','wld-weld','wld-cut','plb-solder','plb-thread','plb-clean','msr-level','msr-range','msr-detect',
--     'mov-lift','pnt-spray','pnt-plaster','aut-start','aut-lift') or key like '%-other';
--   update public.cats set active = false where key in ('cutting','surface','climate','compressors','plumbing','painting');
-- Старые названия, порядок и «Леса и вышки» обратно в «Стройоборудование»:
--   update public.cats c set title = v.title, sort = v.sort from (values ('power','Электроинструмент',1),
--     ('construction','Стройоборудование',2),('garden','Садовый инструмент',3),('auto','Для ремонта авто',4),
--     ('welding','Сварочное оборудование',5),('measuring','Измерительные приборы',6),
--     ('generators','Генераторы и электропитание',7),('cleaning','Клининговое оборудование',8),
--     ('moving','Перевозка и подъём',9)) v(key,title,sort) where c.key = v.key;
--   update public.subcats s set cat = v.cat, title = v.title, sort = v.sort, active = true from (values
--     ('semniki','auto','Съёмники',1),('dlya-lkp','auto','Для ЛКП',2),('scaffolding','construction','Леса и вышки',1),
--     ('concrete','construction','Бетонные работы',2),('lawnmowers','garden','Газонокосилки',1),
--     ('trimmers','garden','Триммеры и кусторезы',2),('hand-trucks','moving','Тележки и подъём',1),
--     ('cordless','power','На аккумуляторе',1),('corded','power','От сети 220В',2),
--     ('grinders','power','Болгарки и штроборезы',3),('drills','power','Дрели и шуруповёрты',4),
--     ('hammers','power','Перфораторы и отбойники',5)) v(key,cat,title,sort)
--     where s.key = v.key and s.cat in (v.cat, 'moving');

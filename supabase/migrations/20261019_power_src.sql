-- 10.86 · «Питание» в справочнике моделей (решение владельца 15.10.2026).
--
-- Варианты «Питания» у всех видов: Сеть 220 В, Аккумулятор, Бензин, Дизель. Редкие — только там, где они уже были
-- (так и бывает): «Сеть 380 В» (мойки, компрессоры, бетономешалки…), «Сжатый воздух» (пневмоинструмент),
-- «Ручной» (трубогибы, лебёдки, заклёпочники…; «Ручная» → «Ручной»). «Газ», «Гидравлический» и «12 В» убираются:
-- у моделей и объявлений с таким питанием оно становится не указанным (остальные характеристики не трогаются).
--
-- Нужен 20261017_models.sql (таблицы справочника и tools.specs). Для базы, где 20261017_models*.sql уже применены;
-- на свежей базе ничего не меняет (те файлы уже собраны с новыми вариантами). Повторный запуск безопасен.
-- Откат: вариантов «как было» нет смысла возвращать — данные моделей те же; при необходимости заново применить
-- 20261017_models.sql и части данных из коммита до 10.86.

begin;

-- 1. Варианты «Питания» у видов
with f as (
  select kind, key,
         array['Сеть 220 В', 'Аккумулятор', 'Бензин', 'Дизель']
         || case when options @> array['Сеть 380 В'] then array['Сеть 380 В'] else '{}'::text[] end
         || case when options @> array['Сжатый воздух'] then array['Сжатый воздух'] else '{}'::text[] end
         || case when options && array['Ручной', 'Ручная'] then array['Ручной'] else '{}'::text[] end as opts
  from public.iva_spec_fields
  where key = 'src'
)
update public.iva_spec_fields s set options = f.opts
from f
where s.kind = f.kind and s.key = f.key and s.options is distinct from f.opts;

-- 2. Модели справочника
update public.iva_models set specs = jsonb_set(specs, '{src}', to_jsonb('Ручной'::text))
where specs ->> 'src' = 'Ручная';
update public.iva_models set specs = specs - 'src'
where specs ->> 'src' in ('Газ', 'Гидравлический', '12 В');

-- 3. Объявления (то же правило; характеристики — только объект)
update public.tools set specs = jsonb_set(specs, '{src}', to_jsonb('Ручной'::text))
where jsonb_typeof(specs) = 'object' and specs ->> 'src' = 'Ручная';
update public.tools set specs = nullif(specs - 'src', '{}'::jsonb)
where jsonb_typeof(specs) = 'object' and specs ->> 'src' in ('Газ', 'Гидравлический', '12 В');

-- 4. Предложения моделей (10.84), если таблица есть
do $$
begin
  if to_regclass('public.iva_model_proposals') is not null then
    update public.iva_model_proposals set specs = jsonb_set(specs, '{src}', to_jsonb('Ручной'::text))
    where jsonb_typeof(specs) = 'object' and specs ->> 'src' = 'Ручная';
    update public.iva_model_proposals set specs = specs - 'src'
    where jsonb_typeof(specs) = 'object' and specs ->> 'src' in ('Газ', 'Гидравлический', '12 В');
  end if;
end $$;

commit;

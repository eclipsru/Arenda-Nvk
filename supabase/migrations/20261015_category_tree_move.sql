-- Ива 10.79 — ШАГ 2: перенос объявлений в новое дерево категорий.
-- ЗАПУСКАТЬ ТОЛЬКО ПОСЛЕ «да» владельца на просмотр из 20261015_category_tree.sql (тот же список, то же правило).
--
-- Переносит объявления, у которых подкатегория выключена или не существует в своей категории, по плану
-- public.iva_tree_plan(): переехавшая подкатегория — вместе с ней; иначе по названию; иначе «Другое» своей
-- категории. Где плана нет (new_sub пусто) — не трогает. Повторный запуск ничего не меняет.
-- В конце — что получилось (id, название, категория, подкатегория).
--
-- Откат: старые категория и подкатегория каждого перенесённого объявления видны в просмотре шага 1
-- (old_cat / old_sub) —   update public.tools set cat = '<old_cat>', sub = '<old_sub>' where id = '<id>';

begin;

update public.tools t set cat = p.new_cat, sub = p.new_sub
from public.iva_tree_plan() p
where t.id::text = p.id and p.new_sub is not null;

commit;

select t.id, t.name, t.cat, t.sub, t.kind
from public.tools t
order by t.cat, t.name;

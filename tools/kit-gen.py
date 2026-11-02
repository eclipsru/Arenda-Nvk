#!/usr/bin/env python3
# 10.84 · Справочник моделей, этап 4: собирает supabase/migrations/20261018_models_stage4.sql
# из supabase/models/kit.txt (комплектация по видам) + схема предложений моделей.
#
# Запуск:  python3 tools/kit-gen.py           — пересобрать SQL
#          python3 tools/kit-gen.py --check   — проверить данные и что SQL в репозитории совпадает с данными
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KIT = os.path.join(ROOT, 'supabase/models/kit.txt')
TREE = os.path.join(ROOT, 'supabase/migrations/20261015_category_tree.sql')
OUT = os.path.join(ROOT, 'supabase/migrations/20261018_models_stage4.sql')


def kinds():
    t = open(TREE, encoding='utf-8').read()
    i = t.index('insert into public.iva_kinds')
    blk = t[i:t.index('on conflict (key)', i)]
    return [m.group(1) for m in re.finditer(r"\('([a-z0-9-]+)',\s*'[a-z0-9-]+',\s*'[a-z0-9-]+',\s*'[^']+',\s*\d+\)", blk)]


def parse():
    errs, kit = [], {}
    for n, line in enumerate(open(KIT, encoding='utf-8').read().splitlines(), 1):
        s = line.strip()
        if not s or s.startswith('#'):
            continue
        m = re.match(r'^([a-z0-9-]+):\s*(.*)$', s)
        if not m:
            errs.append('строка %d: нет «вид:»' % n)
            continue
        k, rest = m.group(1), m.group(2).strip()
        if k in kit:
            errs.append('строка %d: вид %s повторяется' % (n, k))
        items = [x.strip() for x in rest.split(';')] if rest else []
        for it in items:
            if not it:
                errs.append('строка %d: пустой пункт' % n)
            elif len(it) > 40:
                errs.append('строка %d: пункт длиннее 40 символов: %s' % (n, it))
            elif "'" in it or '"' in it or ',' in it:
                errs.append('строка %d: кавычки/запятые в пункте: %s' % (n, it))
            elif not re.match(r'^[А-ЯЁ]', it):
                errs.append('строка %d: пункт с маленькой буквы: %s' % (n, it))
        low = [x.lower() for x in items]
        if len(set(low)) != len(low):
            errs.append('строка %d: повтор пункта у вида %s' % (n, k))
        if len(items) > 12:
            errs.append('строка %d: больше 12 пунктов у вида %s' % (n, k))
        kit[k] = items
    ks = kinds()
    miss = [k for k in ks if k not in kit]
    extra = [k for k in kit if k not in ks]
    if miss:
        errs.append('нет комплектации у видов: ' + ', '.join(miss))
    if extra:
        errs.append('видов нет в дереве: ' + ', '.join(extra))
    return kit, ks, errs


def q(s):
    return "'" + s.replace("'", "''") + "'"


HEAD = """-- 10.84 · Справочник моделей (этап 4): предложения моделей админами и комплектация.
-- ФАЙЛ СОБРАН tools/kit-gen.py из supabase/models/kit.txt — правьте данные там и пересоберите.
--
-- Что делает:
--   1. iva_kit_items        — что обычно отдают с инструментом, по видам (%(nk)d видов, %(ni)d пунктов).
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
%(rows)s
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
  new.brand := btrim(regexp_replace(coalesce(new.brand, ''), '\\s+', ' ', 'g'));
  new.model := btrim(regexp_replace(coalesce(new.model, ''), '\\s+', ' ', 'g'));
  new.title := btrim(regexp_replace(coalesce(new.title, ''), '\\s+', ' ', 'g'));
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
  p  public.iva_model_proposals%%rowtype;
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
  b := nullif(btrim(regexp_replace(coalesce(p_brand, p.brand), '\\s+', ' ', 'g')), '');
  m := nullif(btrim(regexp_replace(coalesce(p_model, p.model), '\\s+', ' ', 'g')), '');
  t := nullif(btrim(regexp_replace(coalesce(p_title, p.title), '\\s+', ' ', 'g')), '');
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
"""


def build(kit, ks):
    rows = []
    for k in ks:
        items = kit[k]
        arr = 'array[' + ', '.join(q(x) for x in items) + ']::text[]' if items else "'{}'::text[]"
        rows.append('  (%s, %s)' % (q(k), arr))
    return HEAD % {'nk': len(ks), 'ni': sum(len(kit[k]) for k in ks), 'rows': ',\n'.join(rows)}


def main():
    kit, ks, errs = parse()
    if errs:
        for e in errs:
            print('✘ ' + e)
        sys.exit(1)
    s = build(kit, ks)
    msg = 'комплектация: %d видов, %d пунктов, %d видов без пунктов' % (
        len(ks), sum(len(kit[k]) for k in ks), sum(1 for k in ks if not kit[k]))
    if '--check' in sys.argv:
        cur = open(OUT, encoding='utf-8').read() if os.path.exists(OUT) else ''
        if cur != s:
            print('✘ SQL устарел: python3 tools/kit-gen.py')
            sys.exit(1)
        print('✔ ' + msg + ', SQL совпадает с данными')
        return
    open(OUT, 'w', encoding='utf-8').write(s)
    print('✔ ' + msg + ' → ' + os.path.relpath(OUT, ROOT))


if __name__ == '__main__':
    main()

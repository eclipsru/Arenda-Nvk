#!/usr/bin/env python3
# Справочник моделей (этап 2, 10.82): проверяет supabase/models/*.txt и собирает из них SQL.
#
#   supabase/models/fields.txt   — поля характеристик и какие поля у какого вида
#   supabase/models/brands.txt   — бренды и другие написания
#   supabase/models/N-*.txt      — модели:  вид | Бренд | Модель | ключ=значение; … | своё слово для названия (необязательно)
#
# Выход:
#   supabase/migrations/20261017_models.sql          — таблицы, поля характеристик, бренды (запускать первым)
#   supabase/migrations/20261017_models_data_N.sql   — модели частями по CHUNK строк (запускать по порядку)
#
# Запуск:  python3 tools/models-gen.py          — проверить и записать SQL
#          python3 tools/models-gen.py --check  — проверить данные и что SQL в репозитории совпадает с данными
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD = os.path.join(ROOT, 'supabase/models')
TREE = os.path.join(ROOT, 'supabase/migrations/20261015_category_tree.sql')
OUT_SCHEMA = os.path.join(ROOT, 'supabase/migrations/20261017_models.sql')
OUT_DATA = os.path.join(ROOT, 'supabase/migrations/20261017_models_data_%d.sql')
CHUNK = 1000
TYPES = ('num', 'choice', 'text')
NUM_RE = re.compile(r'^\d+(?:[.,]\d+)?$')

errors = []


def err(where, msg):
    errors.append('%s: %s' % (where, msg))


def split_top(s, sep):
    """Делит по sep вне скобок () и []."""
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth -= 1
        if ch == sep and depth == 0:
            out.append(cur)
            cur = ''
        else:
            cur += ch
    out.append(cur)
    return [x.strip() for x in out]


def data_lines(path):
    for n, raw in enumerate(open(path, encoding='utf-8').read().split('\n'), 1):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        yield '%s:%d' % (os.path.relpath(path, ROOT), n), line


def tree_kinds():
    src = open(TREE, encoding='utf-8').read()
    m = re.search(r'insert into public\.iva_kinds \(key, cat, sub, title, sort\) values(.*?)\non conflict', src, re.S)
    src = m.group(1) if m else ''
    return [m.group(1) for m in re.finditer(r"\('([a-z0-9-]+)',\s*'[a-z0-9-]+',\s*'[a-z0-9-]+',\s*'[^']*',\s*\d+\)", src)]


def parse_fields(kinds_ok):
    lib, kinds = {}, {}
    path = os.path.join(MD, 'fields.txt')
    for where, line in data_lines(path):
        cols = [c.strip() for c in (line.split('|') if line.startswith('@') else line.split('|', 2))]
        if line.startswith('@'):
            if len(cols) != 4:
                err(where, 'поле: нужно 4 колонки «@ключ | Название | единица | тип»')
                continue
            key = cols[0][1:]
            if not re.match(r'^[a-z][a-z0-9]*$', key):
                err(where, 'плохой ключ поля «%s»' % key)
            if cols[3] not in TYPES:
                err(where, 'тип поля «%s» не из %s' % (cols[3], TYPES))
            if key in lib:
                err(where, 'поле «%s» уже есть' % key)
            lib[key] = {'title': cols[1], 'unit': cols[2], 'type': cols[3]}
            continue
        if len(cols) != 3:
            err(where, 'вид: нужно 3 колонки «вид | слово | поля»')
            continue
        kind, noun, spec = cols
        if kind not in kinds_ok:
            err(where, 'вида «%s» нет в 20261015_category_tree.sql' % kind)
        if kind in kinds:
            err(where, 'вид «%s» уже описан' % kind)
        if not noun:
            err(where, 'нет слова для названия')
        fields, seen = [], set()
        for f in split_top(spec, ','):
            m = re.match(r'^([a-z][a-z0-9]*)(?:\[([^\]|]*)(?:\|([^\]]*))?\])?(\(.*\))?$', f)
            if not m:
                err(where, 'не разобрать поле «%s»' % f)
                continue
            key = m.group(1)
            if key not in lib:
                err(where, 'поля «%s» нет в библиотеке' % key)
                continue
            if key in seen:
                err(where, 'поле «%s» повторяется' % key)
            seen.add(key)
            base = lib[key]
            opts = [o.strip() for o in m.group(4)[1:-1].split(';')] if m.group(4) is not None else []
            if base['type'] == 'choice' and not opts:
                err(where, 'у поля выбора «%s» нет вариантов' % key)
            if base['type'] != 'choice' and opts:
                err(where, 'варианты у поля «%s», которое не выбор' % key)
            if len(set(opts)) != len(opts) or any(not o for o in opts):
                err(where, 'пустые или повторные варианты у «%s»' % key)
            fields.append({
                'key': key,
                'title': (m.group(2) or base['title']).strip(),
                'unit': (m.group(3) if m.group(3) is not None else base['unit']).strip(),
                'type': base['type'],
                'options': opts,
            })
        kinds[kind] = {'noun': noun, 'fields': fields}
    for k in kinds_ok:
        if k not in kinds:
            err('fields.txt', 'у вида «%s» нет строки с полями' % k)
    return lib, kinds


def parse_brands():
    brands = {}
    low = {}
    for where, line in data_lines(os.path.join(MD, 'brands.txt')):
        cols = [c.strip() for c in line.split('|')]
        if len(cols) != 2 or not cols[0]:
            err(where, 'бренд: нужно «Название | написания»')
            continue
        name = cols[0]
        if name in brands:
            err(where, 'бренд «%s» уже есть' % name)
        aliases = []
        for a in cols[1].split(';'):
            a = a.strip().lower().replace('ё', 'е')
            if a and a != name.lower() and a not in aliases:
                aliases.append(a)   # повторы написаний просто убираем
        for a in [name.lower()] + aliases:
            if a in low and low[a] != name:
                err(where, 'написание «%s» уже у бренда «%s»' % (a, low[a]))
            low[a] = name
        brands[name] = aliases
    return brands


def fmt_num(v):
    x = float(v.replace(',', '.'))
    return int(x) if x == int(x) else x


def parse_models(kinds, brands):
    models, seen = [], {}
    files = sorted(glob.glob(os.path.join(MD, '[0-9]*-*.txt')), key=lambda p: int(os.path.basename(p).split('-')[0]))
    for path in files:
        for where, line in data_lines(path):
            cols = [c.strip() for c in line.split('|')]
            if len(cols) < 3 or len(cols) > 5:
                err(where, 'модель: нужно «вид | Бренд | Модель | характеристики | слово»')
                continue
            while len(cols) < 5:
                cols.append('')
            kind, brand, model, spec, noun = cols
            if kind not in kinds:
                err(where, 'нет вида «%s»' % kind)
                continue
            if brand not in brands:
                err(where, 'бренда «%s» нет в brands.txt' % brand)
            if not model or '  ' in model:
                err(where, 'пустая модель или двойной пробел')
                continue
            if model.lower().startswith(brand.lower() + ' '):
                err(where, 'бренд повторён в модели «%s»' % model)
            k3 = (kind, brand, model)
            if k3 in seen:
                err(where, 'повтор: уже в %s' % seen[k3])
                continue
            seen[k3] = where
            fdefs = {f['key']: f for f in kinds[kind]['fields']}
            vals = {}
            for pair in [p.strip() for p in spec.split(';')] if spec else []:
                if not pair:
                    continue
                if '=' not in pair:
                    err(where, 'нет «=» в «%s»' % pair)
                    continue
                key, val = [x.strip() for x in pair.split('=', 1)]
                if key not in fdefs:
                    err(where, 'у вида «%s» нет поля «%s»' % (kind, key))
                    continue
                if key in vals:
                    err(where, 'поле «%s» дважды' % key)
                f = fdefs[key]
                if f['type'] == 'num':
                    if not NUM_RE.match(val) or float(val.replace(',', '.')) <= 0:
                        err(where, '%s=%s — нужно положительное число' % (key, val))
                        continue
                    vals[key] = fmt_num(val)
                elif f['type'] == 'choice':
                    if val not in f['options']:
                        err(where, '%s=%s — нет среди вариантов %s' % (key, val, f['options']))
                        continue
                    vals[key] = val
                else:
                    if not val:
                        err(where, '%s — пустое значение' % key)
                        continue
                    vals[key] = val
            if 'volt' in vals and vals.get('src') not in (None, 'Аккумулятор'):
                err(where, 'напряжение аккумулятора при питании «%s»' % vals.get('src'))
            ordered = {f['key']: vals[f['key']] for f in kinds[kind]['fields'] if f['key'] in vals}
            title = '%s %s %s' % (noun or kinds[kind]['noun'], brand, model)
            models.append({'kind': kind, 'brand': brand, 'model': model, 'title': title, 'specs': ordered, 'where': where})
    return models


def q(s):
    return "'" + s.replace("'", "''") + "'"


def arr(items):
    return 'array[%s]::text[]' % ', '.join(q(x) for x in items) if items else "'{}'::text[]"


def js(d):
    return json.dumps(d, ensure_ascii=False, separators=(',', ':'))


def schema_sql(kinds, brands, n_models, n_files):
    rows_f = []
    for kind in sorted(kinds):
        for i, f in enumerate(kinds[kind]['fields'], 1):
            rows_f.append('  (%s, %s, %s, %s, %s, %s, %d)' % (q(kind), q(f['key']), q(f['title']), q(f['unit']), q(f['type']), arr(f['options']), i))
    rows_b = ['  (%s, %s)' % (q(b), arr(brands[b])) for b in sorted(brands, key=str.lower)]
    return '''-- 10.82 · Справочник моделей (этап 2): бренды, модели, характеристики по видам.
-- ФАЙЛ СОБРАН tools/models-gen.py из supabase/models/*.txt — правьте данные там и пересоберите.
--
-- Что делает:
--   1. iva_brands      — бренды и другие написания (для поиска «макита» → Makita).
--   2. iva_spec_fields — какие характеристики у какого вида (%d полей у %d видов): название, единица,
--                        тип (num — число, choice — выбор из вариантов, text — строка), порядок.
--   3. iva_models      — модели (вид, бренд, модель, единое название, характеристики jsonb).
--                        Заполняется файлами 20261017_models_data_1…%d.sql (%d моделей).
--   4. tools.model_id и tools.specs — выбранная модель и характеристики объявления.
-- Читают все (гость тоже), меняет только главный админ (fn_is_chief), как iva_kinds.
--
-- Порядок: 20261015_category_tree.sql → 20261017_models.sql → 20261017_models_data_1…%d.sql.
-- Повторный запуск безопасен: строки приводятся к списку, лишние поля у видов из списка удаляются,
-- модели из прошлых версий списка не удаляются, а выключаются (active = false).
--
-- Откат (объявления не трогает, кроме двух новых столбцов):
--   alter table public.tools drop column if exists specs;
--   alter table public.tools drop column if exists model_id;
--   drop table if exists public.iva_models;
--   drop table if exists public.iva_spec_fields;
--   drop table if exists public.iva_brands;

begin;

-- ─────────────────────────────── 1. Таблицы ───────────────────────────────
create table if not exists public.iva_brands (
  name    text primary key,
  aliases text[] not null default '{}',
  active  boolean not null default true
);

create table if not exists public.iva_spec_fields (
  kind    text not null references public.iva_kinds(key) on update cascade on delete cascade,
  key     text not null,
  title   text not null,
  unit    text not null default '',
  type    text not null check (type in ('num', 'choice', 'text')),
  options text[] not null default '{}',
  sort    int  not null default 0,
  primary key (kind, key)
);

create table if not exists public.iva_models (
  id         bigint generated always as identity primary key,
  kind       text not null references public.iva_kinds(key) on update cascade on delete cascade,
  brand      text not null references public.iva_brands(name) on update cascade,
  model      text not null,
  title      text not null,
  specs      jsonb not null default '{}' check (jsonb_typeof(specs) = 'object'),
  source     text not null default 'admin',
  active     boolean not null default true,
  created_at timestamptz not null default now(),
  unique (kind, brand, model)
);
create index if not exists iva_models_kind_idx on public.iva_models (kind);

do $$
declare t text;
begin
  foreach t in array array['iva_brands', 'iva_spec_fields', 'iva_models'] loop
    execute format('alter table public.%%I enable row level security', t);
    execute format('revoke all on public.%%I from anon, authenticated', t);
    execute format('grant select on public.%%I to anon, authenticated', t);
    execute format('grant insert, update, delete on public.%%I to authenticated', t);
    execute format('drop policy if exists %%I on public.%%I', t || '_read', t);
    execute format('create policy %%I on public.%%I for select to anon, authenticated using (true)', t || '_read', t);
    execute format('drop policy if exists %%I on public.%%I', t || '_chief', t);
    execute format('create policy %%I on public.%%I for all to authenticated using (public.fn_is_chief()) with check (public.fn_is_chief())', t || '_chief', t);
  end loop;
end $$;
grant usage on sequence public.iva_models_id_seq to authenticated;

-- ─────────────────────────────── 2. Модель и характеристики у объявления ───────────────────────────────
alter table public.tools add column if not exists model_id bigint;
alter table public.tools add column if not exists specs jsonb;
do $$ begin
  if not exists (select 1 from pg_constraint where conname = 'tools_model_id_fkey' and conrelid = 'public.tools'::regclass) then
    alter table public.tools add constraint tools_model_id_fkey
      foreign key (model_id) references public.iva_models(id) on delete set null;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'tools_specs_obj_chk' and conrelid = 'public.tools'::regclass) then
    alter table public.tools add constraint tools_specs_obj_chk check (specs is null or jsonb_typeof(specs) = 'object');
  end if;
end $$;
create index if not exists tools_model_id_idx on public.tools (model_id);

-- ─────────────────────────────── 3. Бренды ───────────────────────────────
insert into public.iva_brands (name, aliases) values
%s
on conflict (name) do update set aliases = excluded.aliases, active = true
  where (iva_brands.aliases, iva_brands.active) is distinct from (excluded.aliases, true);

-- ─────────────────────────────── 4. Характеристики по видам ───────────────────────────────
create temp table _f (kind text, key text, title text, unit text, type text, options text[], sort int) on commit drop;
insert into _f values
%s;

insert into public.iva_spec_fields (kind, key, title, unit, type, options, sort)
select kind, key, title, unit, type, options, sort from _f
on conflict (kind, key) do update
  set title = excluded.title, unit = excluded.unit, type = excluded.type, options = excluded.options, sort = excluded.sort
  where (iva_spec_fields.title, iva_spec_fields.unit, iva_spec_fields.type, iva_spec_fields.options, iva_spec_fields.sort)
        is distinct from (excluded.title, excluded.unit, excluded.type, excluded.options, excluded.sort);

-- лишние поля у видов из списка (свои виды главного админа не трогаем)
delete from public.iva_spec_fields s
where s.kind in (select distinct kind from _f)
  and not exists (select 1 from _f where _f.kind = s.kind and _f.key = s.key);

commit;
''' % (len(rows_f), len(kinds), n_files, n_models, n_files, ',\n'.join(rows_b), ',\n'.join(rows_f))


def data_sql(chunk, idx, total):
    rows = ['  (%s, %s, %s, %s, %s)' % (q(m['kind']), q(m['brand']), q(m['model']), q(m['title']), q(js(m['specs']))) for m in chunk]
    src = 'list-%d' % idx
    tail = ''
    if idx == total:
        tail = '''
-- модели из частей, которых больше нет в списке
update public.iva_models set active = false
where active and source like 'list-%%' and source not in (%s);
''' % ', '.join(q('list-%d' % i) for i in range(1, total + 1))
    return '''-- 10.82 · Справочник моделей, часть %d из %d (%d моделей).
-- ФАЙЛ СОБРАН tools/models-gen.py из supabase/models/*.txt. Нужен 20261017_models.sql. Повторный запуск безопасен.

begin;

with v(kind, brand, model, title, specs) as (values
%s
), up as (
  insert into public.iva_models (kind, brand, model, title, specs, source, active)
  select kind, brand, model, title, specs::jsonb, %s, true from v
  on conflict (kind, brand, model) do update
    set title = excluded.title, specs = excluded.specs, source = excluded.source, active = true
    where (iva_models.title, iva_models.specs, iva_models.source, iva_models.active)
          is distinct from (excluded.title, excluded.specs, excluded.source, true)
  returning 1
)
-- модели этой части, которых больше нет в списке, выключаются (объявления с ними не ломаются)
update public.iva_models m set active = false
where m.source = %s and m.active
  and not exists (select 1 from v where v.kind = m.kind and v.brand = m.brand and v.model = m.model);
%s
commit;
''' % (idx, total, len(chunk), ',\n'.join(rows), q(src), q(src), tail)


def main():
    check = '--check' in sys.argv
    kinds_ok = tree_kinds()
    if len(kinds_ok) < 100:
        err('20261015_category_tree.sql', 'нашлось только %d видов' % len(kinds_ok))
    lib, kinds = parse_fields(set(kinds_ok))
    brands = parse_brands()
    models = parse_models(kinds, brands) if kinds else []
    used = {m['brand'] for m in models}
    unused = sorted(set(brands) - used)
    if errors:
        for e in errors[:200]:
            print('✘', e)
        print('ИТОГ: ошибок %d' % len(errors))
        return 1
    order = {k: i for i, k in enumerate(kinds_ok)}
    models.sort(key=lambda m: (order[m['kind']], m['brand'].lower(), m['model'].lower()))
    chunks = [models[i:i + CHUNK] for i in range(0, len(models), CHUNK)] or [[]]
    files = {OUT_SCHEMA: schema_sql(kinds, brands, len(models), len(chunks))}
    for i, ch in enumerate(chunks, 1):
        files[OUT_DATA % i] = data_sql(ch, i, len(chunks))
    per_kind = {}
    for m in models:
        per_kind[m['kind']] = per_kind.get(m['kind'], 0) + 1
    empty = [k for k in kinds_ok if k not in per_kind]
    with_specs = sum(1 for m in models if m['specs'])
    stale = sorted(set(glob.glob(OUT_DATA.replace('%d', '*'))) - set(files))
    if check:
        bad = [os.path.relpath(p, ROOT) for p, s in files.items() if not os.path.exists(p) or open(p, encoding='utf-8').read() != s]
        bad += [os.path.relpath(p, ROOT) + ' (лишний)' for p in stale]
        if bad:
            print('✘ SQL не совпадает с данными — запустите python3 tools/models-gen.py:', ', '.join(bad))
            return 1
    else:
        for p, s in files.items():
            open(p, 'w', encoding='utf-8').write(s)
        for p in stale:
            os.remove(p)
    print('✔ моделей %d (с характеристиками %d), брендов %d (без моделей %d), видов с моделями %d из %d, частей SQL %d'
          % (len(models), with_specs, len(brands), len(unused), len(per_kind), len(kinds_ok), len(chunks)))
    if '-v' in sys.argv:
        print('без моделей:', ' '.join(empty))
        print('бренды без моделей:', ', '.join(unused))
    return 0


if __name__ == '__main__':
    sys.exit(main())

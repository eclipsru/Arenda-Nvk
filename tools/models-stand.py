# Стенд справочника моделей 20261017_models*.sql (этап 2, 10.82) на настоящем PostgreSQL (pgserver из PyPI).
#
# Исходная база — как в стенде дерева категорий, затем 20261015_category_tree.sql, 20261016_kind_words.sql,
# 20261017_models.sql и все части 20261017_models_data_N.sql. Проверяет: всё применяется, строк столько же, сколько
# собрал tools/models-gen.py, характеристики моделей совпадают с полями вида (ключи, варианты выбора, числа),
# гость читает модели постранично тем же запросом, что приложение и сайт, писать может только главный админ,
# объявление хранит model_id и specs (только объект), удаление модели не ломает объявление, повторный запуск ничего
# не меняет, свои модели/поля главного админа не трогаются, исчезнувшие из списка модели выключаются, откат работает.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/models-stand.py
import ast
import glob
import json
import os
import re
import subprocess
import sys
import tempfile

import pgserver

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def rd(p):
    return open(os.path.join(ROOT, p), encoding='utf-8').read()


TREE = rd('supabase/migrations/20261015_category_tree.sql')
WORDS = rd('supabase/migrations/20261016_kind_words.sql')
SCHEMA = rd('supabase/migrations/20261017_models.sql')
DATA = [open(p, encoding='utf-8').read() for p in sorted(glob.glob(os.path.join(ROOT, 'supabase/migrations/20261017_models_data_*.sql')),
                                                       key=lambda p: int(re.search(r'_(\d+)\.sql$', p).group(1)))]

_src = rd('tools/category-tree-stand.py')
_consts = {}
for node in ast.parse(_src).body:
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') in ('SVG', 'BASELINE', 'CATS0', 'SUBS0'):
        _consts[node.targets[0].id] = ast.literal_eval(node.value)
SVG, BASELINE, CATS0, SUBS0 = _consts['SVG'], _consts['BASELINE'], _consts['CATS0'], _consts['SUBS0']

srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
fails = 0


def q(sql):
    try:
        out = srv.psql('\\set ON_ERROR_STOP on\n' + sql + "\n;\nselect 'IVA-END-OK' as mark;\n").strip()
    except subprocess.CalledProcessError as e:
        out = (e.output or b'').decode('utf-8', 'replace') if isinstance(e.output, bytes) else str(e.output or '')
    return ('OK' if 'IVA-END-OK' in out else 'ERR'), out.replace('IVA-END-OK', '')


def val(sql):
    st, out = q('\\pset tuples_only on\n\\pset format unaligned\n' + sql)
    lines = [l for l in out.splitlines() if l and not l.startswith(('Tuples only', 'Output format'))]
    return ';'.join(lines)


def as_user(email, sql, role='authenticated'):
    c = json.dumps({'email': email, 'role': role} if email else {'role': role}).replace("'", "''")
    st, out = q("begin; set local role %s; set local request.jwt.claims = '%s'; %s commit;" % (role, c, sql))
    if out.rstrip().endswith('ROLLBACK'):
        st = 'ERR'
    return st, out


def check(name, cond, extra=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + ('\n    ' + str(extra).replace('\n', '\n    ') if extra else ''))


def esc(s):
    return 'null' if s is None else "'" + s.replace("'", "''") + "'"


def snap():
    return val("select md5(string_agg(kind || brand || model || title || specs::text || source || active::text, ',' order by kind, brand, model)) from public.iva_models") + '/' + \
        val("select md5(string_agg(kind || key || title || unit || type || options::text || sort, ',' order by kind, key)) from public.iva_spec_fields") + '/' + \
        val("select md5(string_agg(name || aliases::text || active::text, ',' order by name)) from public.iva_brands")


seed = BASELINE
seed += ''.join("insert into public.cats values (%s, %s, %s, %d, true);\n" % (esc(k), esc(t), esc(SVG), s) for k, t, s in CATS0)
seed += ''.join("insert into public.subcats values (%s, %s, %s, %s, %s, %d, true);\n" % (esc(i), esc(k), esc(c), esc(t), esc(SVG), s)
                for i, c, k, t, s in SUBS0)
st, out = q(seed)
check('исходная база', st == 'OK', out[-400:])
st, out = q(TREE)
check('дерево категорий (20261015) применяется', st == 'OK', out[-600:])
st, out = q(WORDS)
check('слова автоподбора (20261016) применяются', st == 'OK', out[-600:])
q("insert into public.iva_kinds(key, cat, sub, title, sort) values ('my-kind', 'power', 'drills', 'Мой вид', 50);")

st, out = q(SCHEMA)
check('20261017_models.sql применяется без ошибок', st == 'OK', out[-800:])
ok_all = True
for i, d in enumerate(DATA, 1):
    st, out = q(d)
    if st != 'OK':
        ok_all = False
        print(out[-800:])
check('части данных применяются по порядку (%d шт.)' % len(DATA), ok_all and len(DATA) >= 1)

# сколько строк собрал генератор
n_models = sum(len(re.findall(r"^  \('[a-z0-9-]+', '", d, re.M)) for d in DATA)
n_fields = int(re.search(r'iva_spec_fields — какие характеристики у какого вида \((\d+) полей', SCHEMA).group(1))
n_brands = len(re.findall(r"^  \('[^']+', (?:array\[|'\{\}')", SCHEMA.split('3. Бренды')[1].split('4. Характеристики')[0], re.M))
check('моделей в базе столько же, сколько в файлах (%d)' % n_models,
      val('select count(*) from public.iva_models where active') == str(n_models) and n_models >= 2000, val('select count(*) from public.iva_models'))
check('полей характеристик столько же, сколько в файле (%d)' % n_fields, val('select count(*) from public.iva_spec_fields') == str(n_fields))
check('брендов столько же, сколько в файле (%d)' % n_brands, val('select count(*) from public.iva_brands') == str(n_brands) and n_brands >= 80)
check('поля характеристик есть у всех 159 видов дерева', val("select count(distinct kind) from public.iva_spec_fields") == '159')

# характеристики моделей совпадают с полями вида
check('каждый ключ характеристик есть у вида модели',
      val("select count(*) from public.iva_models m, jsonb_object_keys(m.specs) k where not exists "
          "(select 1 from public.iva_spec_fields f where f.kind = m.kind and f.key = k)") == '0')
check('значения выбора — из вариантов поля',
      val("select count(*) from public.iva_models m join public.iva_spec_fields f on f.kind = m.kind and f.type = 'choice' "
          "where m.specs ? f.key and not (m.specs->>f.key = any(f.options))") == '0')
check('числа — числа JSON больше нуля',
      val("select count(*) from public.iva_models m join public.iva_spec_fields f on f.kind = m.kind and f.type = 'num' "
          "where m.specs ? f.key and (jsonb_typeof(m.specs->f.key) <> 'number' or (m.specs->>f.key)::numeric <= 0)") == '0')
check('у поля выбора всегда есть варианты, у остальных — нет',
      val("select count(*) from public.iva_spec_fields where (type = 'choice') <> (cardinality(options) > 0)") == '0')
check('название = слово вида + бренд + модель',
      val("select count(*) from public.iva_models where title not like '% ' || brand || ' ' || model") == '0')
check('пример: Makita HR2470', val("select title || '|' || specs::text from public.iva_models where brand = 'Makita' and model = 'HR2470'")
      .startswith('Перфоратор Makita HR2470|{'), val("select title || '|' || specs::text from public.iva_models where model = 'HR2470'"))
check('целые числа без «.0»', val("select count(*) from public.iva_models where specs::text ~ '\\.0[,}]'") == '0')

# чтение гостем — тем же запросом, что приложение и сайт (постранично по 1000)
st, out = as_user(None, "select count(*) from (select id, kind, brand, model, title, specs from public.iva_models where active order by id limit 1000 offset 1000) t;", 'anon')
check('гость читает модели постранично', st == 'OK' and '1000' in out, out[-300:])
st, out = as_user(None, "select key, title, unit, type, options from public.iva_spec_fields where kind = 'rotary-sds-plus' order by sort;", 'anon')
check('гость читает поля характеристик', st == 'OK' and 'Энергия удара' in out, out[-300:])
st, out = as_user(None, "select name, aliases from public.iva_brands where name = 'Makita';", 'anon')
check('гость читает бренды с написаниями', st == 'OK' and 'макита' in out, out[-300:])
before = val('select count(*) from public.iva_models')
as_user(None, "insert into public.iva_models(kind, brand, model, title) values ('drill', 'Makita', 'X1', 'Дрель Makita X1');", 'anon')
as_user('admin@x.ru', "insert into public.iva_models(kind, brand, model, title) values ('drill', 'Makita', 'X2', 'Дрель Makita X2');")
as_user('admin@x.ru', "update public.iva_models set title = 'взлом' where model = 'HR2470';")
as_user('admin@x.ru', "delete from public.iva_spec_fields where kind = 'drill';")
check('гость и обычный админ ничего не меняют', val('select count(*) from public.iva_models') == before
      and val("select count(*) from public.iva_models where title = 'взлом'") == '0'
      and val("select count(*) from public.iva_spec_fields where kind = 'drill'") != '0')
st, out = as_user('chief@x.ru', "insert into public.iva_brands(name) values ('Мойбренд'); "
                  "insert into public.iva_models(kind, brand, model, title, specs) values ('my-kind', 'Мойбренд', 'M1', 'Мой вид Мойбренд M1', '{}'); "
                  "insert into public.iva_spec_fields(kind, key, title, type) values ('my-kind', 'power', 'Мощность', 'num');")
check('главный админ добавляет свой бренд, модель и поле', st == 'OK', out[-300:])

# объявление
mid = val("select id from public.iva_models where brand = 'Makita' and model = 'HR2470'")
st, out = q("insert into public.tools(name, cat, sub, kind, model_id, specs) values ('Перфоратор Makita HR2470', 'power', 'hammers', 'rotary-sds-plus', %s, '{\"power\": 780}');" % mid)
check('объявление сохраняет model_id и specs', st == 'OK', out[-300:])
st, out = q("insert into public.tools(name, specs) values ('x', '[1,2]');")
check('specs только объект (массив не принимается)', st == 'ERR' and val("select count(*) from pg_constraint where conname = 'tools_specs_obj_chk'") == '1')
st, out = q("insert into public.tools(name, model_id) values ('x', 999999999);")
check('model_id только существующей модели', st == 'ERR' and val("select count(*) from pg_constraint where conname = 'tools_model_id_fkey'") == '1')
q("insert into public.tools(name, model_id) select 'моя', id from public.iva_models where model = 'M1';")

# повторный запуск
s1 = snap()
st, out = q(SCHEMA)
check('повторный запуск схемы безопасен (ничего не меняет)', st == 'OK' and 'INSERT 0 0' in out and 'DELETE 0' in out, out[-500:])
outs = [q(d) for d in DATA]
check('повторный запуск данных ничего не меняет', all(s == 'OK' for s, _ in outs) and snap() == s1
      and all(re.search(r'UPDATE 0', o) for _, o in outs), [o[-200:] for _, o in outs][:1])
check('свой вид, бренд и поле главного админа на месте', val("select count(*) from public.iva_spec_fields where kind = 'my-kind'") == '1'
      and val("select count(*) from public.iva_brands where name = 'Мойбренд'") == '1')

# правка в базе возвращается к списку; лишнее поле у вида из списка удаляется; исчезнувшие модели выключаются
q("update public.iva_models set title = 'испорчено', specs = '{}' where model = 'HR2470';")
q("insert into public.iva_spec_fields(kind, key, title, type) values ('drill', 'zzz', 'Лишнее', 'text');")
q("insert into public.iva_models(kind, brand, model, title, source) values ('drill', 'Makita', 'OLD-1', 'Дрель Makita OLD-1', 'list-1'), "
  "('drill', 'Makita', 'OLD-9', 'Дрель Makita OLD-9', 'list-99');")
q(SCHEMA)
for d in DATA:
    q(d)
check('испорченная модель восстановлена из списка', val("select title from public.iva_models where model = 'HR2470'") == 'Перфоратор Makita HR2470'
      and val("select specs ? 'energy' from public.iva_models where model = 'HR2470'") == 't')
check('лишнее поле у вида из списка удалено', val("select count(*) from public.iva_spec_fields where kind = 'drill' and key = 'zzz'") == '0')
check('модель, исчезнувшая из части списка, выключена, а не удалена', val("select active from public.iva_models where model = 'OLD-1'") == 'f')
check('модель из исчезнувшей части выключена', val("select active from public.iva_models where model = 'OLD-9'") == 'f')
check('своя модель главного админа не выключена', val("select active from public.iva_models where model = 'M1'") == 't')
check('объявление с моделью цело', val("select count(*) from public.tools where model_id = %s" % mid) == '1')
q("delete from public.iva_models where model = 'M1';")
check('удаление модели не удаляет объявление (model_id → null)', val("select count(*) from public.tools where name = 'моя' and model_id is null") == '1')

# откат
rb = SCHEMA.split('Откат')[1].split('begin;')[0]
rb = '\n'.join(l.lstrip('- ').strip() for l in rb.splitlines() if l.strip().startswith('--   '))
st, out = q(rb)
check('откат из файла убирает таблицы и столбцы', st == 'OK' and val("select count(*) from information_schema.tables where table_name in ('iva_models', 'iva_spec_fields', 'iva_brands')") == '0'
      and val("select count(*) from information_schema.columns where table_name = 'tools' and column_name in ('model_id', 'specs')") == '0', out[-300:])
check('объявления после отката на месте', val("select count(*) from public.tools") != '0')
st, out = q(SCHEMA)
ok_all = st == 'OK' and all(q(d)[0] == 'OK' for d in DATA)
check('после отката всё применяется снова', ok_all and val('select count(*) from public.iva_models') == str(n_models))

srv.cleanup()
print('ИТОГ: %s' % ('всё верно' if not fails else 'ошибок: %d' % fails))
sys.exit(1 if fails else 0)

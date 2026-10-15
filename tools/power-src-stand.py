# Стенд 20261019_power_src.sql (10.86, «Питание»: 4 основных варианта + редкие там, где бывают) на PostgreSQL (pgserver).
#
# База «как у владельца»: 20261015 / 20261016 / 20261017_models* / 20261018, затем справочник «портится» до вида,
# который был до 10.86 (варианты «Питания» в другом порядке, «Ручная», «Газ», «Гидравлический», «12 В»; у моделей —
# «Ручная», «Газ», «Гидравлический»), и есть объявления и предложение с такими значениями. Проверяет: миграция
# применяется, справочник становится ровно таким, как свежая установка из собранных файлов, у объявлений и
# предложений — то же правило (остальные характеристики не трогаются), повторный запуск ничего не меняет.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/power-src-stand.py
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
STAGE4 = rd('supabase/migrations/20261018_models_stage4.sql')
POWER = rd('supabase/migrations/20261019_power_src.sql')

_src = rd('tools/category-tree-stand.py')
_consts = {}
for node in ast.parse(_src).body:
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') in ('SVG', 'BASELINE', 'CATS0', 'SUBS0'):
        _consts[node.targets[0].id] = ast.literal_eval(node.value)
SVG, BASELINE, CATS0, SUBS0 = _consts['SVG'], _consts['BASELINE'], _consts['CATS0'], _consts['SUBS0']

# fn_is_admin как в бою (20261006_publish_rights_fix.sql)
FIX = rd('supabase/migrations/20261006_publish_rights_fix.sql')
i = FIX.index('create or replace function public.fn_is_admin()')
IS_ADMIN = FIX[i:FIX.index('grant execute on function public.fn_is_admin()', i)]

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
    return val("select md5(string_agg(kind || brand || model || title || specs::text || active::text, ',' order by kind, brand, model)) from public.iva_models") + '/' + \
        val("select md5(string_agg(kind || key || options::text, ',' order by kind, key)) from public.iva_spec_fields")


seed = BASELINE + IS_ADMIN + ';\n'
seed += ''.join("insert into public.cats values (%s, %s, %s, %d, true);\n" % (esc(k), esc(t), esc(SVG), s) for k, t, s in CATS0)
seed += ''.join("insert into public.subcats values (%s, %s, %s, %s, %s, %d, true);\n" % (esc(i), esc(k), esc(c), esc(t), esc(SVG), s)
                for i, c, k, t, s in SUBS0)
st, out = q(seed)
check('исходная база', st == 'OK', out[-400:])
ok = True
for name, sql in [('дерево', TREE), ('слова', WORDS), ('модели', SCHEMA)] + [('данные %d' % n, d) for n, d in enumerate(DATA, 1)] + [('этап 4', STAGE4)]:
    st, out = q(sql)
    if st != 'OK':
        ok = False
        print(name, out[-600:])
check('20261015 / 20261016 / 20261017_models* / 20261018 применяются', ok)
fresh = snap()
main4 = ['Сеть 220 В', 'Аккумулятор', 'Бензин', 'Дизель']
check('свежая установка: у всех полей «Питание» сначала 4 основных варианта',
      val("select count(*) from public.iva_spec_fields where key = 'src' and options[1:4] <> array['Сеть 220 В','Аккумулятор','Бензин','Дизель']") == '0'
      and int(val("select count(*) from public.iva_spec_fields where key = 'src'")) >= 60)
check('свежая установка: редкие только 380 В / сжатый воздух / ручной, без «Газ», «Гидравлический», «12 В», «Ручная»',
      val("select count(*) from public.iva_spec_fields f, unnest(f.options) o where f.key = 'src' and o not in "
          "('Сеть 220 В','Аккумулятор','Бензин','Дизель','Сеть 380 В','Сжатый воздух','Ручной')") == '0')
check('свежая установка: у моделей питание — из вариантов вида',
      val("select count(*) from public.iva_models m join public.iva_spec_fields f on f.kind = m.kind and f.key = 'src' "
          "where m.specs ? 'src' and not (m.specs->>'src' = any(f.options))") == '0')
check('пример: мойка — с «Сеть 380 В», трубогиб — с «Ручной», перфоратор — без редких',
      val("select options::text from public.iva_spec_fields where key = 'src' and kind = 'pressure-washer'") == '{"Сеть 220 В",Аккумулятор,Бензин,Дизель,"Сеть 380 В"}'
      and val("select options::text from public.iva_spec_fields where key = 'src' and kind = 'pipe-bender'") == '{"Сеть 220 В",Аккумулятор,Бензин,Дизель,Ручной}'
      and val("select options::text from public.iva_spec_fields where key = 'src' and kind = 'rotary-sds-plus'") == '{"Сеть 220 В",Аккумулятор,Бензин,Дизель}',
      val("select kind || ' ' || options::text from public.iva_spec_fields where key = 'src' and kind in ('pressure-washer','pipe-bender','rotary-sds-plus')"))

# «как было до 10.86»: другой порядок, «Ручная», «Газ», «Гидравлический», «12 В»
st, out = q("""
update public.iva_spec_fields set options = (select array_agg(case o when 'Ручной' then 'Ручная' else o end order by ord desc)
  from unnest(options) with ordinality u(o, ord) where o not in ('Бензин', 'Дизель') or kind in ('generator', 'roller', 'tiller'))
  || array['Газ'] where key = 'src' and kind in (select kind from public.iva_spec_fields where key = 'src' order by kind limit 30);
update public.iva_spec_fields set options = array['Ручной', 'Гидравлический', 'Сеть 220 В', '12 В'] where key = 'src' and kind = 'pipe-bender';
update public.iva_models set specs = jsonb_set(specs, '{src}', '"Ручная"') where specs->>'src' = 'Ручной' and kind = 'sweeper';
update public.iva_models set specs = specs || '{"src": "Газ"}' where brand = 'Paslode' and model in ('IM350+', 'IM250 II');
update public.iva_models set specs = specs || '{"src": "Гидравлический"}' where brand = 'Rothenberger' and model = 'Robull';
insert into public.tools (id, name, specs) values
  ('00000000-0000-0000-0000-000000000001', 'Подметалка', '{"src": "Ручная", "width": 700}'),
  ('00000000-0000-0000-0000-000000000002', 'Гвоздезабивной газовый', '{"src": "Газ", "nail": 90}'),
  ('00000000-0000-0000-0000-000000000003', 'Трубогиб', '{"src": "Гидравлический"}'),
  ('00000000-0000-0000-0000-000000000004', 'Перфоратор', '{"src": "Сеть 220 В", "power": 800}'),
  ('00000000-0000-0000-0000-000000000005', 'Без характеристик', null);
insert into public.iva_model_proposals (kind, brand, model, title, specs, proposed_by)
  values ('nailer', 'Paslode', 'IM90i', 'Гвоздезабивной пистолет Paslode IM90i', '{"src": "Газ", "nail": 90}', 'admin2@x.ru');
""")
check('справочник «как до 10.86» и объявления с такими значениями', st == 'OK' and snap() != fresh, out[-600:])

st, out = q(POWER)
check('20261019_power_src.sql применяется', st == 'OK', out[-600:])
check('справочник после миграции — ровно как свежая установка (модели и варианты)', snap() == fresh, snap() + ' ≠ ' + fresh)
tools = val("select coalesce(specs::text, 'null') from public.tools where id::text like '00000000-0000-0000-0000-00000000000%' order by id")
check('объявления: «Ручная» → «Ручной», «Газ» / «Гидравлический» — питание убрано, остальное как было',
      tools == '{"src": "Ручной", "width": 700};{"nail": 90};null;{"src": "Сеть 220 В", "power": 800};null', tools)
check('предложение модели: питание «Газ» убрано', val("select specs::text from public.iva_model_proposals where model = 'IM90i'") == '{"nail": 90}')
before = snap() + tools
st, out = q(POWER)
check('повторный запуск безопасен и ничего не меняет', st == 'OK' and snap() + val(
    "select coalesce(specs::text, 'null') from public.tools where id::text like '00000000-0000-0000-0000-00000000000%' order by id") == before, out[-300:])

print('ИТОГ: ошибок %d' % fails)
srv.cleanup()
sys.exit(1 if fails else 0)

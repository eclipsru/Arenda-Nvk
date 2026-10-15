# Стенд этапа 4 справочника моделей (10.84): 20261018_models_stage4.sql на настоящем PostgreSQL (pgserver из PyPI).
#
# База как в стенде моделей (дерево, слова, модели и все части данных), затем этап 4. Проверяет: применяется,
# комплектация совпадает с supabase/models/kit.txt, гость читает, менять может только главный админ, tools.kit
# не длиннее 30; предложить модель может только админ и только от своей почты, видит только свои, главный — все;
# дубли и модели из справочника не принимаются, лимит 30; решение принимает только главный админ, одобренная
# модель попадает в справочник с source = 'proposal', посторонние характеристики отбрасываются, объявления
# предложившего привязываются, чужие — нет; повторное решение запрещено; повторный запуск части данных
# одобренную модель не выключает; повторный запуск этапа 4 ничего не меняет; откат работает.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/models-stage4-stand.py
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


def uval(email, sql, role='authenticated'):
    c = json.dumps({'email': email, 'role': role} if email else {'role': role}).replace("'", "''")
    out = val("begin; set local role %s; set local request.jwt.claims = '%s';\n%s;\ncommit;" % (role, c, sql))
    return ';'.join(x for x in out.split(';') if x not in ('BEGIN', 'SET', 'COMMIT'))


def check(name, cond, extra=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + ('\n    ' + str(extra).replace('\n', '\n    ') if extra else ''))


def esc(s):
    return 'null' if s is None else "'" + s.replace("'", "''") + "'"


seed = BASELINE + IS_ADMIN + ';\n'
seed += "insert into public.admins values ('admin2@x.ru', 'admin', true), ('off@x.ru', 'admin', false);\n"
seed += ''.join("insert into public.cats values (%s, %s, %s, %d, true);\n" % (esc(k), esc(t), esc(SVG), s) for k, t, s in CATS0)
seed += ''.join("insert into public.subcats values (%s, %s, %s, %s, %s, %d, true);\n" % (esc(i), esc(k), esc(c), esc(t), esc(SVG), s)
                for i, c, k, t, s in SUBS0)
st, out = q(seed)
check('исходная база', st == 'OK', out[-400:])
ok = True
for name, sql in [('дерево', TREE), ('слова', WORDS), ('модели', SCHEMA)] + [('данные %d' % n, d) for n, d in enumerate(DATA, 1)]:
    st, out = q(sql)
    if st != 'OK':
        ok = False
        print(name, out[-600:])
check('20261015 / 20261016 / 20261017_models* применяются', ok)

st, out = q(STAGE4)
check('20261018_models_stage4.sql применяется без ошибок', st == 'OK', out[-800:])

# ── комплектация ──
kit = {}
for line in rd('supabase/models/kit.txt').splitlines():
    m = re.match(r'^([a-z0-9-]+):\s*(.*)$', line.strip())
    if m:
        kit[m.group(1)] = [x.strip() for x in m.group(2).split(';')] if m.group(2).strip() else []
check('комплектация есть у всех 159 видов', val('select count(*) from public.iva_kit_items') == '159' and len(kit) == 159)
check('пунктов столько же, сколько в kit.txt (%d)' % sum(map(len, kit.values())),
      val('select sum(cardinality(items)) from public.iva_kit_items') == str(sum(map(len, kit.values()))))
check('порядок пунктов как в файле (перфоратор SDS-plus)',
      val("select array_to_string(items, '|') from public.iva_kit_items where kind = 'rotary-sds-plus'") == '|'.join(kit['rotary-sds-plus']))
check('гость читает комплектацию', uval(None, "select count(*) from public.iva_kit_items", 'anon') == '159')
as_user('admin@x.ru', "update public.iva_kit_items set items = '{}' where kind = 'drill';")
check('админ не может менять комплектацию', val("select cardinality(items) from public.iva_kit_items where kind = 'drill'") == str(len(kit['drill'])))
st, _ = as_user('chief@x.ru', "update public.iva_kit_items set items = array['Кейс'] where kind = 'stepladder';")
check('главный админ может', st == 'OK' and val("select items from public.iva_kit_items where kind = 'stepladder'") == '{Кейс}')
q("update public.iva_kit_items set items = '{}' where kind = 'stepladder';")

q("insert into public.tools(id, name, kind, owner_email) values ('00000000-0000-0000-0000-000000000001', 'Перфоратор Hilti TE 999', 'rotary-sds-plus', 'admin@x.ru');")
st, _ = q("update public.tools set kit = array['Кейс', 'Бур SDS-plus'] where id = '00000000-0000-0000-0000-000000000001';")
check('tools.kit хранит список', st == 'OK' and val("select kit from public.tools where id = '00000000-0000-0000-0000-000000000001'") == '{Кейс,"Бур SDS-plus"}')
st, _ = q("update public.tools set kit = array_fill('x'::text, array[31]) where id = '00000000-0000-0000-0000-000000000001';")
check('tools.kit длиннее 30 не принимается', st == 'ERR')

# ── предложения ──
INS = "insert into public.iva_model_proposals (kind, brand, model, title, specs) values ('rotary-sds-plus', %s, %s, %s, %s::jsonb);"
st, out = as_user('admin@x.ru', INS % ("'  hilti '", "'TE  999'", "'Перфоратор Hilti TE 999'", "'{\"energy\": 3.2, \"bogus\": 1}'"))
check('админ предлагает модель', st == 'OK', out[-300:])
row = val("select brand || '|' || model || '|' || proposed_by || '|' || status from public.iva_model_proposals")
check('бренд как в справочнике, пробелы убраны, почта из токена', row == 'Hilti|TE 999|admin@x.ru|pending', row)
st, _ = as_user('admin@x.ru', INS % ("'Hilti'", "'te 999'", "'Перфоратор Hilti TE 999'", "'{}'"))
check('ту же модель второй раз не предложить, пока ждёт', st == 'ERR')
real = val("select brand || '|' || model from public.iva_models where kind = 'rotary-sds-plus' and active limit 1").split('|')
st, _ = as_user('admin@x.ru', INS % (esc(real[0].lower()), esc(real[1]), "'Перфоратор'", "'{}'"))
check('модель из справочника не предложить (%s %s)' % tuple(real), st == 'ERR')
st, _ = as_user('admin@x.ru', "insert into public.iva_model_proposals (kind, brand, model, title, proposed_by) values ('drill', 'Bosch', 'X1', 'Дрель X1', 'admin2@x.ru');")
check('от чужой почты нельзя', st == 'ERR')
st, _ = as_user('admin@x.ru', "insert into public.iva_model_proposals (kind, brand, model, title, status) values ('drill', 'Bosch', 'X2', 'Дрель X2', 'approved');")
check('сразу «одобрено» нельзя', st == 'ERR')
st, _ = as_user('user@x.ru', INS % ("'Bosch'", "'X3'", "'Дрель X3'", "'{}'"))
check('обычный пользователь не может', st == 'ERR')
st, _ = as_user('off@x.ru', INS % ("'Bosch'", "'X4'", "'Дрель X4'", "'{}'"))
check('выключенный админ не может', st == 'ERR')
st, _ = as_user(None, INS % ("'Bosch'", "'X5'", "'Дрель X5'", "'{}'"), 'anon')
check('гость не может', st == 'ERR')
st, _ = as_user('admin2@x.ru', INS % ("'NoName'", "'Z-1'", "'Перфоратор NoName Z-1'", "'{}'"))
check('второй админ предлагает модель нового бренда', st == 'OK')
check('админ видит только свои', uval('admin@x.ru', 'select count(*) from public.iva_model_proposals') == '1'
      and uval('admin2@x.ru', 'select count(*) from public.iva_model_proposals') == '1')
check('главный админ видит все', uval('chief@x.ru', 'select count(*) from public.iva_model_proposals') == '2')
check('гость не видит', as_user(None, 'select count(*) from public.iva_model_proposals;', 'anon')[0] == 'ERR')
as_user('admin@x.ru', "update public.iva_model_proposals set status = 'approved';")
as_user('admin@x.ru', "delete from public.iva_model_proposals;")
check('админ не может ни одобрить, ни удалить своё', val("select string_agg(status, ',') from public.iva_model_proposals") == 'pending,pending')
pid = val("select id from public.iva_model_proposals where proposed_by = 'admin@x.ru'")
pid2 = val("select id from public.iva_model_proposals where proposed_by = 'admin2@x.ru'")
st, _ = as_user('admin@x.ru', "select public.iva_model_decide(%s, true);" % pid)
check('админ не может решать', st == 'ERR' and val("select status from public.iva_model_proposals where id = %s" % pid) == 'pending')

# чужое объявление с той же моделью в названии — не привязывается
q("insert into public.tools(id, name, kind, owner_email) values ('00000000-0000-0000-0000-000000000002', 'Hilti TE 999 у другого', 'rotary-sds-plus', 'admin2@x.ru');")
q("insert into public.tools(id, name, kind, owner_email) values ('00000000-0000-0000-0000-000000000003', 'Hilti TE 999', 'drill', 'admin@x.ru');")
mid = uval('chief@x.ru', "select public.iva_model_decide(%s, true, null, null, 'Перфоратор Hilti TE 999 AVR')" % pid)
check('главный админ одобряет (правит название)', mid.isdigit(), mid)
m = val("select kind || '|' || brand || '|' || model || '|' || title || '|' || specs::text || '|' || source || '|' || active from public.iva_models where id = %s" % (mid or '0'))
check('модель в справочнике, лишнее поле отброшено', m == 'rotary-sds-plus|Hilti|TE 999|Перфоратор Hilti TE 999 AVR|{"energy": 3.2}|proposal|true', m)
check('предложение отмечено одобренным и ссылается на модель',
      val("select status || '|' || model_id || '|' || (decided_at is not null) from public.iva_model_proposals where id = %s" % pid) == 'approved|%s|true' % mid)
check('объявление предложившего привязалось к модели', val("select model_id from public.tools where id = '00000000-0000-0000-0000-000000000001'") == mid)
check('чужое объявление и объявление другого вида — нет',
      val("select count(*) from public.tools where model_id is not null and id in ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003')") == '0')
st, _ = as_user('chief@x.ru', "select public.iva_model_decide(%s, false);" % pid)
check('повторно решить нельзя', st == 'ERR' and val("select status from public.iva_model_proposals where id = %s" % pid) == 'approved')
st, _ = as_user('chief@x.ru', "select public.iva_model_decide(%s, true, '', null, null);" % pid2)
check('пустой бренд не принимается', st == 'ERR' and val("select status from public.iva_model_proposals where id = %s" % pid2) == 'pending')
nb = val("select count(*) from public.iva_brands")
st, _ = as_user('chief@x.ru', "select public.iva_model_decide(%s, false);" % pid2)
check('отклонение: только отметка, бренд и модель не добавлены',
      st == 'OK' and val("select status from public.iva_model_proposals where id = %s" % pid2) == 'rejected'
      and val("select count(*) from public.iva_brands") == nb and val("select count(*) from public.iva_models where brand = 'NoName'") == '0')
st, _ = as_user('admin2@x.ru', INS % ("'NoName'", "'Z-1'", "'Перфоратор NoName Z-1'", "'{}'"))
pid3 = val("select max(id) from public.iva_model_proposals")
uval('chief@x.ru', "select public.iva_model_decide(%s, true)" % pid3)
check('после отказа можно предложить снова; одобрение добавляет новый бренд',
      st == 'OK' and val("select count(*) from public.iva_brands where name = 'NoName'") == '1'
      and val("select count(*) from public.iva_models where brand = 'NoName' and source = 'proposal'") == '1')
st, _ = as_user('admin@x.ru', INS % ("'Hilti'", "'TE 999'", "'Перфоратор Hilti TE 999'", "'{}'"))
check('одобренную модель больше не предложить', st == 'ERR')

# выключенная модель из списка: одобрение включает её и метит proposal
off = val("select id || '|' || brand || '|' || model from public.iva_models where kind = 'drill' and active order by id limit 1").split('|')
q("update public.iva_models set active = false where id = %s" % off[0])
as_user('admin@x.ru', "insert into public.iva_model_proposals (kind, brand, model, title) values ('drill', %s, %s, 'Дрель');" % (esc(off[1]), esc(off[2])))
pid4 = val("select max(id) from public.iva_model_proposals")
r4 = uval('chief@x.ru', "select public.iva_model_decide(%s, true)" % pid4)
check('выключенная модель включается, а не дублируется',
      r4 == off[0] and val("select active || '|' || source from public.iva_models where id = %s" % off[0]) == 'true|proposal', r4)

# лимит 30 ждущих
q("insert into public.iva_model_proposals (kind, brand, model, title, proposed_by) select 'drill', 'Bosch', 'L' || g, 'Дрель L' || g, 'admin@x.ru' from generate_series(1, 30) g;")
st, _ = as_user('admin@x.ru', INS % ("'Bosch'", "'L31'", "'Дрель L31'", "'{}'"))
check('больше 30 ждущих от одного админа не принимается', st == 'ERR')

# повторный запуск частей данных и этапа 4
for d in DATA:
    q(d)
check('повторный запуск данных 20261017 не выключает одобренные модели (модель из списка снова «списочная»)',
      val("select count(*) from public.iva_models where source = 'proposal' and active") == '2'
      and val("select active::text from public.iva_models where id = %s" % off[0]) == 'true')


def snap():
    return val("select md5(string_agg(kind || items::text, ',' order by kind)) from public.iva_kit_items") + '/' + \
        val("select md5(string_agg(id || status || brand || model || coalesce(model_id::text, ''), ',' order by id)) from public.iva_model_proposals")


s1 = snap()
st, out = q(STAGE4)
check('повторный запуск этапа 4 безопасен и ничего не меняет', st == 'OK' and snap() == s1, out[-400:])

roll = re.search(r'-- Откат.*?\n((?:--   .*\n)+)', STAGE4).group(1)
roll = '\n'.join(l[5:] for l in roll.splitlines() if not l.startswith('--   --'))
st, out = q(roll)
check('откат из шапки файла работает',
      st == 'OK' and val("select count(*) from information_schema.tables where table_name in ('iva_kit_items', 'iva_model_proposals')") == '0'
      and val("select count(*) from information_schema.columns where table_name = 'tools' and column_name = 'kit'") == '0'
      and val("select count(*) from public.tools") == '3', out[-400:])
st, out = q(STAGE4)
check('после отката применяется снова', st == 'OK', out[-400:])

print('ИТОГ: %s' % ('всё верно' if not fails else 'ошибок %d' % fails))
srv.cleanup()
sys.exit(1 if fails else 0)

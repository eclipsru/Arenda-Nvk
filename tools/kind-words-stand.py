# Стенд миграции 20261016_kind_words.sql (автоподбор вида по названию) на настоящем PostgreSQL (pgserver из PyPI).
#
# Исходная база — как в стенде дерева категорий (tools/category-tree-stand.py), затем 20261015_category_tree.sql и
# 20261016_kind_words.sql. Проверяет: столбец words появился, у всех 159 видов есть слова, свой вид главного админа
# не трогается (пустые слова), гость читает key+words тем же запросом, что приложение и сайт, повторный запуск ничего
# не меняет, а подбор по словам из базы даёт ожидаемое на общих случаях tests/kind-guess-cases.txt.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/kind-words-stand.py
import ast
import json
import os
import re
import subprocess
import sys
import tempfile

import pgserver

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREE = open(os.path.join(ROOT, 'supabase/migrations/20261015_category_tree.sql'), encoding='utf-8').read()
WORDS = open(os.path.join(ROOT, 'supabase/migrations/20261016_kind_words.sql'), encoding='utf-8').read()

# исходная база — те же константы, что в стенде дерева категорий
_src = open(os.path.join(ROOT, 'tools/category-tree-stand.py'), encoding='utf-8').read()
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


def check(name, cond, extra=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + ('\n    ' + str(extra).replace('\n', '\n    ') if extra else ''))


def esc(s):
    return 'null' if s is None else "'" + s.replace("'", "''") + "'"


seed = BASELINE
seed += ''.join("insert into public.cats values (%s, %s, %s, %d, true);\n" % (esc(k), esc(t), esc(SVG), s) for k, t, s in CATS0)
seed += ''.join("insert into public.subcats values (%s, %s, %s, %s, %s, %d, true);\n" % (esc(i), esc(k), esc(c), esc(t), esc(SVG), s)
                for i, c, k, t, s in SUBS0)
st, out = q(seed)
check('исходная база', st == 'OK', out[-400:])
st, out = q(TREE)
check('дерево категорий (20261015) применяется', st == 'OK', out[-600:])
st, out = q("insert into public.iva_kinds(key, cat, sub, title, sort) values ('my-kind', 'power', 'drills', 'Мой вид', 50);")
check('свой вид главного админа до миграции', st == 'OK', out[-300:])

st, out = q(WORDS)
check('20261016_kind_words.sql применяется без ошибок', st == 'OK', out[-800:])
check('проверочный запрос в конце файла показывает только свой вид', 'my-kind' in out and '(1 row)' in out, out[-400:])
n_file = len(re.findall(r"^\s*\('[a-z0-9-]+', array\[", WORDS, re.M))
check('в файле слова для 159 видов', n_file == 159, n_file)
check('у всех видов дерева есть слова', val("select count(*) from public.iva_kinds where key <> 'my-kind' and cardinality(words) = 0") == '0'
      and val("select count(*) from public.iva_kinds where cardinality(words) > 0") == '159')
check('свой вид не тронут (пустые слова)', val("select cardinality(words) from public.iva_kinds where key = 'my-kind'") == '0')
check('words не null, по умолчанию пусто',
      val("select is_nullable || '|' || column_default from information_schema.columns where table_name = 'iva_kinds' and column_name = 'words'")
      == "NO|'{}'::text[]")
check('слова — строчные, без ё, буквы/цифры и одиночные пробелы',
      val("select count(*) from public.iva_kinds, unnest(words) w where w !~ '^[a-zа-я0-9]+( [a-zа-я0-9]+)*$' or w ~ 'ё'") == '0')

# гость читает тем же запросом, что приложение (IvaKindPatch.WORDS_PATH) и сайт
st, out = q("begin; set local role anon; select key, words from public.iva_kinds where active = true and key = 'grinder-125'; commit;")
check('гость читает key, words', st == 'OK' and 'болгар 125' in out, out[-300:])
st, out = q("begin; set local role anon; update public.iva_kinds set words = '{x}' where key = 'drill'; commit;")
check('гость слова не меняет', val("select words::text from public.iva_kinds where key = 'drill'") == '{дрел,drill}', out[-200:])

before = val("select md5(string_agg(key || ':' || words::text, ',' order by key)) from public.iva_kinds")
st, out = q(WORDS)
check('повторный запуск безопасен (UPDATE 0)', st == 'OK' and 'UPDATE 0' in out, out[-300:])
check('после повтора слова те же', val("select md5(string_agg(key || ':' || words::text, ',' order by key)) from public.iva_kinds") == before)

# подбор по словам из базы — эталон на Python (тот же алгоритм, что assets/kind-guess.js и IvaKindPatch.guess)
rows = val("select key || '|' || cat || '|' || sub || '|' || array_to_string(words, '~') from public.iva_kinds where active and key <> 'my-kind'")
K = []
for r in rows.split(';'):
    key, cat, sub, ws = r.split('|')
    K.append((key, cat, sub, ws.split('~')))


def toks(s):
    return re.sub(r'[^a-zа-я0-9]+', ' ', s.lower().replace('ё', 'е')).split()


def guess(name):
    t = toks(name)
    best, bs = [], (0, 0)
    for key, cat, sub, ws in K:
        sc = []
        for w in ws:
            parts, tot = w.split(), 0
            for p in parts:
                if not any(x.startswith(p) for x in t):
                    tot = 0
                    break
                tot += len(p)
            sc.append(tot)
        m, n = max(sc), sum(1 for x in sc if x)
        if not m:
            continue
        if (m, n) > bs:
            bs, best = (m, n), [(key, cat, sub)]
        elif (m, n) == bs:
            best.append((key, cat, sub))
    if not best:
        return ('', '', '')
    if len(best) == 1:
        return (best[0][1], best[0][2], best[0][0])
    if len({(b[1], b[2]) for b in best}) == 1:
        return (best[0][1], best[0][2], '')
    if len({b[1] for b in best}) == 1:
        return (best[0][1], '', '')
    return ('', '', '')


cases = [l.split('|') for l in open(os.path.join(ROOT, 'tests/kind-guess-cases.txt'), encoding='utf-8').read().split('\n')
         if l.strip() and not l.startswith('#')]
bad = [(n, (c, s, k), guess(n)) for n, c, s, k in cases if guess(n) != (c, s, k)]
check('подбор по словам из базы: %d общих случаев' % len(cases), not bad and len(cases) >= 120, json.dumps(bad[:5], ensure_ascii=False))
titles = val("select key || '|' || cat || '|' || sub || '|' || title from public.iva_kinds where active and key <> 'my-kind'")
bad = []
for r in titles.split(';'):
    key, cat, sub, title = r.split('|')
    if guess(title) != (cat, sub, key):
        bad.append((title, guess(title)))
check('название каждого вида подбирает сам вид (159)', not bad, json.dumps(bad[:5], ensure_ascii=False))

# откат
rb = WORDS.split('Откат')[1]
rb = '\n'.join(l.lstrip('- ').strip() for l in rb.splitlines() if l.strip().startswith('--   '))
st, out = q(rb)
check('откат из файла убирает столбец', st == 'OK' and val("select count(*) from information_schema.columns where table_name = 'iva_kinds' and column_name = 'words'") == '0', out[-300:])
st, out = q(WORDS)
check('после отката миграция применяется снова', st == 'OK' and val("select count(*) from public.iva_kinds where cardinality(words) > 0") == '159', out[-300:])

srv.cleanup()
print('ИТОГ: %s' % ('всё верно' if not fails else 'ошибок: %d' % fails))
sys.exit(1 if fails else 0)

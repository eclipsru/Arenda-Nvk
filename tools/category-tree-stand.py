# Стенд миграции 20261015_category_tree.sql (+ шаг 2 _move.sql) на настоящем PostgreSQL (pgserver из PyPI).
#
# Исходные данные — ровно как в рабочей базе 15.10.2026 (CI «Категории · 1/2»): 9 категорий, 12 подкатегорий с теми же
# ключами и id, 2 объявления (+ несколько проверочных). Проверяет: 15 категорий с нужными названиями и порядком,
# старые ключи и id не меняются, «Леса и вышки» переехали, питание выключено (не удалено), «Другое» в каждой
# категории, справочник видов согласован с подкатегориями, tools.kind принимает только известный вид, читать виды
# может каждый, менять — только главный админ; шаг 1 объявления не меняет и показывает план; шаг 2 переносит ровно
# по плану; оба шага можно запускать повторно.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/category-tree-stand.py
import json
import os
import re
import subprocess
import sys
import tempfile

import pgserver

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SQL = open(os.path.join(ROOT, 'supabase/migrations/20261015_category_tree.sql'), encoding='utf-8').read()
MOVE = open(os.path.join(ROOT, 'supabase/migrations/20261015_category_tree_move.sql'), encoding='utf-8').read()

srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
fails = 0


def q(sql):
    """Ошибки psql пишет не в вывод, поэтому: остановка на первой ошибке и метка в конце — нет метки = ошибка."""
    try:
        out = srv.psql('\\set ON_ERROR_STOP on\n' + sql + "\n;\nselect 'IVA-END-OK' as mark;\n").strip()
    except subprocess.CalledProcessError as e:    # psql остановился на ошибке (код 3)
        out = (e.output or b'').decode('utf-8', 'replace') if isinstance(e.output, bytes) else str(e.output or '')
    ok = 'IVA-END-OK' in out
    return ('OK' if ok else 'ERR'), out.replace('IVA-END-OK', '')


def val(sql):
    """Одно значение (или строки через «;») из запроса — без заголовков psql."""
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


SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"/>'
BASELINE = """
create role anon nologin;
create role authenticated nologin;
create schema auth;
create function auth.jwt() returns jsonb language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb $$;
grant usage on schema auth to anon, authenticated;
grant execute on function auth.jwt() to anon, authenticated;
grant usage on schema public to anon, authenticated;
alter default privileges in schema public grant execute on functions to anon, authenticated;
alter default privileges in schema public grant all on tables to anon, authenticated;
create function public.jwt_email() returns text language sql stable as $$
  select lower(btrim(coalesce(auth.jwt()->>'email', ''))) $$;
create table public.admins(email text, role text, active boolean);
insert into public.admins values ('chief@x.ru', 'chief', true), ('admin@x.ru', 'admin', true);
create function public.fn_is_chief() returns boolean language sql stable security definer as $$
  select exists(select 1 from public.admins a where a.active and a.role = 'chief' and lower(a.email) = public.jwt_email()) $$;
-- как в бою: столбцы cats / subcats / tools (CI «Категории · 1/2»)
create table public.cats(key text primary key, title text, icon text, sort int, active boolean default true);
create table public.subcats(id uuid primary key default gen_random_uuid(), key text, cat text, title text, icon text,
  sort int, active boolean default true);
create table public.tools(id uuid primary key default gen_random_uuid(), name text, cat text, cats text[] default '{}',
  sub text, active boolean default true, owner_email text);
alter table public.cats enable row level security;
alter table public.subcats enable row level security;
alter table public.tools enable row level security;
create policy cats_read on public.cats for select using (true);
create policy subcats_read on public.subcats for select using (true);
create policy tools_read on public.tools for select using (true);
"""

CATS0 = [('power', 'Электроинструмент', 1), ('construction', 'Стройоборудование', 2), ('garden', 'Садовый инструмент', 3),
         ('auto', 'Для ремонта авто', 4), ('welding', 'Сварочное оборудование', 5), ('measuring', 'Измерительные приборы', 6),
         ('generators', 'Генераторы и электропитание', 7), ('cleaning', 'Клининговое оборудование', 8),
         ('moving', 'Перевозка и подъём', 9)]
SUBS0 = [('1062e13d-586f-4ad7-a972-90515537d835', 'auto', 'semniki', 'Съёмники', 1),
         ('6faf96ed-1dac-4999-abf9-217a0ebef6fa', 'auto', 'dlya-lkp', 'Для ЛКП', 2),
         ('a0cd9205-5b71-4a4c-b92a-4fc78f242966', 'construction', 'scaffolding', 'Леса и вышки', 1),
         ('b90d13e1-269e-46e8-a65c-bd28cd51e442', 'construction', 'concrete', 'Бетонные работы', 2),
         ('3732ac26-7f85-4b96-a828-f7189211c962', 'garden', 'lawnmowers', 'Газонокосилки', 1),
         ('d3c29f4e-5d80-4f75-aee6-a593f534f594', 'garden', 'trimmers', 'Триммеры и кусторезы', 2),
         ('fd0bf914-3f03-48d4-9498-17966697bebd', 'moving', 'hand-trucks', 'Тележки и подъём', 1),
         ('dc88bcdf-c3e9-4640-b877-73a99cde2800', 'power', 'cordless', 'На аккумуляторе', 1),
         ('721568bd-f692-40cc-8ad7-3b4e9f7fa0a2', 'power', 'corded', 'От сети 220В', 2),
         ('ad2b010b-1df6-4b6b-af1e-fe538a7e0e34', 'power', 'grinders', 'Болгарки и штроборезы', 3),
         ('765dd180-1456-4899-99f3-c7f048735220', 'power', 'drills', 'Дрели и шуруповёрты', 4),
         ('a829725a-1549-41c1-8d52-67f41c85140e', 'power', 'hammers', 'Перфораторы и отбойники', 5)]
TOOLS0 = [('809aa868-7020-4311-be41-9a62a5b0e684', 'Полировальная машина', 'auto', 'dlya-lkp'),
          ('ba2074a1-c534-4d68-941c-378ae250f955', 'Аккумуляторный инструмент', 'power', 'cordless'),
          ('00000000-0000-0000-0000-000000000001', 'ШУРУПОВЁРТ Makita', 'power', 'cordless'),
          ('00000000-0000-0000-0000-000000000002', 'Перфоратор Bosch 2-26', 'power', 'corded'),
          ('00000000-0000-0000-0000-000000000003', 'УШМ Интерскол', 'power', 'corded'),
          ('00000000-0000-0000-0000-000000000004', 'Что-то', 'misc', 'old'),
          ('00000000-0000-0000-0000-000000000005', 'Леса рамные', 'construction', 'scaffolding'),
          ('00000000-0000-0000-0000-000000000006', 'Дрель без подкатегории', 'power', None)]


def esc(s):
    return 'null' if s is None else "'" + s.replace("'", "''") + "'"


seed = BASELINE
seed += ''.join("insert into public.cats values (%s, %s, %s, %d, true);\n" % (esc(k), esc(t), esc(SVG), s) for k, t, s in CATS0)
seed += ''.join("insert into public.subcats values (%s, %s, %s, %s, %s, %d, true);\n" % (esc(i), esc(k), esc(c), esc(t), esc(SVG), s)
                for i, c, k, t, s in SUBS0)
seed += ''.join("insert into public.tools(id, name, cat, sub) values (%s, %s, %s, %s);\n" % (esc(i), esc(n), esc(c), esc(s))
                for i, n, c, s in TOOLS0)
st, out = q(seed)
check('исходная база как в бою (9 категорий, 12 подкатегорий)', st == 'OK' and val('select count(*) from public.cats') == '9'
      and val('select count(*) from public.subcats') == '12', out[-400:])

tools_before = val('select id, cat, coalesce(sub, \'∅\') from public.tools order by id')

# ── шаг 1 ──
st, out = q(SQL)
check('шаг 1 применяется без ошибок', st == 'OK', out[-800:])
check('шаг 1 объявления не меняет', val('select id, cat, coalesce(sub, \'∅\') from public.tools order by id') == tools_before)

EXPECT_CATS = [('power', 'Электроинструмент'), ('cutting', 'Резка и бурение'), ('construction', 'Бетонные и дорожные работы'),
               ('surface', 'Шлифование поверхностей'), ('garden', 'Садовая техника'), ('cleaning', 'Уборка и мойка'),
               ('climate', 'Обогрев и осушение'), ('generators', 'Электроснабжение и освещение'),
               ('compressors', 'Компрессоры и насосы'), ('welding', 'Сварочное оборудование'),
               ('plumbing', 'Сантехнический и монтажный'), ('measuring', 'Измерительные приборы'),
               ('moving', 'Высота и перемещение грузов'), ('painting', 'Покраска и штукатурка'), ('auto', 'Автоинструмент')]
got = val("select key || '=' || title from public.cats where active order by sort")
check('15 категорий по порядку с новыми названиями', got == ';'.join(k + '=' + t for k, t in EXPECT_CATS), got)
check('значки старых категорий не тронуты, у новых — свои', val("select count(*) from public.cats where icon = %s" % esc(SVG)) == '9'
      and val("select count(*) from public.cats where icon like '<svg%' and icon <> " + esc(SVG)) == '6')
check('в каждой категории «Другое» (включена, последняя)',
      val("select count(*) from public.cats c where c.active and exists(select 1 from public.subcats s where s.cat = c.key "
          "and s.key = c.key || '-other' and s.title = 'Другое' and s.sort = 99 and s.active)") == '15')
check('id и ключи 12 старых подкатегорий на месте',
      val("select count(*) from public.subcats where id in (%s)" % ','.join(esc(i) for i, *_ in SUBS0)) == '12')
check('«Леса и вышки» → «Высота и перемещение грузов», тот же id',
      val("select cat || '|' || title || '|' || sort from public.subcats where id = 'a0cd9205-5b71-4a4c-b92a-4fc78f242966'")
      == 'moving|Леса, вышки и лестницы|1')
check('питание выключено, строки остались',
      val("select string_agg(key || ':' || active, ',' order by key) from public.subcats where key in ('cordless','corded')")
      == 'corded:false,cordless:false')
check('старые переименованы: drills / hammers / grinders / dlya-lkp / hand-trucks',
      val("select string_agg(key || '=' || title, ',' order by key) from public.subcats where key in "
          "('drills','hammers','grinders','dlya-lkp','hand-trucks')")
      == 'dlya-lkp=Полировка и ЛКП,drills=Дрели, шуруповёрты и гайковёрты,grinders=Болгарки (УШМ),'
         'hammers=Перфораторы,hand-trucks=Лебёдки, домкраты и тележки')
n_subs = val('select count(*) from public.subcats where active')
check('активных подкатегорий 70 (55 по дереву + 15 «Другое»)', n_subs == '70', n_subs)
check('пары (категория, ключ) не повторяются',
      val('select count(*) from (select cat, key from public.subcats group by 1, 2 having count(*) > 1) d') == '0')
check('у каждой активной подкатегории есть активная категория',
      val('select count(*) from public.subcats s where s.active and not exists(select 1 from public.cats c where c.key = s.cat and c.active)') == '0')
# дерево владельца: число подкатегорий без «Другого» в каждой категории
TREE = {'power': 8, 'cutting': 4, 'construction': 7, 'surface': 3, 'garden': 4, 'cleaning': 4, 'climate': 3, 'generators': 3,
        'compressors': 2, 'welding': 2, 'plumbing': 3, 'measuring': 3, 'moving': 3, 'painting': 2, 'auto': 4}
got = val("select string_agg(cat || ':' || n, ',' order by cat) from (select cat, count(*) n from public.subcats "
          "where active and key not like '%-other' group by cat) x")
check('подкатегорий в каждой категории — как в согласованном дереве',
      got == ','.join('%s:%d' % (k, TREE[k]) for k in sorted(TREE)), got)

n_kinds = val('select count(*) from public.iva_kinds')
kinds_block = SQL.split('insert into public.iva_kinds (key, cat, sub, title, sort) values')[1].split('on conflict (key)')[0]
n_file = len(re.findall(r"^\s*\('[a-z0-9-]+',", kinds_block, re.M))
check('видов в базе ровно столько, сколько в списке файла (%s), больше 150' % n_file, n_kinds == str(n_file) and n_file > 150, n_kinds)
check('каждый вид — в существующей активной подкатегории своей категории',
      val('select count(*) from public.iva_kinds k where not exists(select 1 from public.subcats s where s.cat = k.cat and s.key = k.sub and s.active)') == '0')
check('у «Другое» видов нет', val("select count(*) from public.iva_kinds where sub like '%-other'") == '0')
check('в каждой подкатегории дерева (кроме «Другое») есть хотя бы один вид',
      val("select count(*) from public.subcats s where s.active and s.key not like '%-other' and not exists(select 1 from public.iva_kinds k where k.cat = s.cat and k.sub = s.key)") == '0')
check('названия видов в подкатегории не повторяются',
      val('select count(*) from (select sub, title from public.iva_kinds group by 1, 2 having count(*) > 1) d') == '0')

# tools.kind
st, _ = q("update public.tools set kind = 'nope' where id = '809aa868-7020-4311-be41-9a62a5b0e684';")
check('неизвестный вид база не принимает', st == 'ERR')
st, _ = q("update public.tools set kind = 'car-polisher' where id = '809aa868-7020-4311-be41-9a62a5b0e684';")
check('известный вид принимается', st == 'OK' and val("select kind from public.tools where id = '809aa868-7020-4311-be41-9a62a5b0e684'") == 'car-polisher')

# права на справочник видов
st, out = as_user(None, 'select count(*) from public.iva_kinds;', 'anon')
check('гость читает виды', st == 'OK' and n_kinds in out, out[-200:])
st, _ = as_user(None, "insert into public.iva_kinds(key, cat, sub, title) values ('x1', 'power', 'drills', 'X');", 'anon')
check('гость виды не добавляет', st == 'ERR')
st, _ = as_user('admin@x.ru', "insert into public.iva_kinds(key, cat, sub, title) values ('x2', 'power', 'drills', 'X');")
check('админ проката виды не добавляет', st == 'ERR')
st, out = as_user('admin@x.ru', "update public.iva_kinds set title = 'взлом' where key = 'drill' returning key;")
check('админ проката виды не меняет', val("select title from public.iva_kinds where key = 'drill'") == 'Дрель', out[-200:])
st, _ = as_user('chief@x.ru', "insert into public.iva_kinds(key, cat, sub, title, sort) values ('my-kind', 'power', 'drills', 'Мой вид', 50);")
check('главный админ добавляет вид', st == 'OK' and val("select title from public.iva_kinds where key = 'my-kind'") == 'Мой вид')

# просмотр плана
prev = SQL.split('-- ─────────────────────────────── 6. ПРОСМОТР')[1].split('-- ─────────────────────────────── Откат')[0]
prev = prev.split('\n', 1)[1]
check('просмотр — только чтение (select из плана)', 'update ' not in prev.lower() and 'iva_tree_plan()' in prev)
st, out = q(prev)
plan = {}
for line in out.splitlines():
    cells = [c.strip() for c in line.split('|')]
    if len(cells) == 6 and len(cells[0]) == 36:
        plan[cells[0]] = '%s/%s → %s/%s' % (cells[2], cells[3], cells[4], cells[5] or '∅')
EXPECT_PLAN = {'ba2074a1-c534-4d68-941c-378ae250f955': 'power/cordless → power/power-other',
               '00000000-0000-0000-0000-000000000001': 'power/cordless → power/drills',
               '00000000-0000-0000-0000-000000000002': 'power/corded → power/hammers',
               '00000000-0000-0000-0000-000000000003': 'power/corded → power/grinders',
               '00000000-0000-0000-0000-000000000004': 'misc/old → misc/∅',
               '00000000-0000-0000-0000-000000000005': 'construction/scaffolding → moving/scaffolding'}
check('просмотр: переехавшая подкатегория — вместе с ней, иначе по названию, иначе «Другое», неизвестное — не трогаем', plan == EXPECT_PLAN, json.dumps(plan, ensure_ascii=False))

# повтор шага 1
snap = val("select (select string_agg(key||title||sort||active, ',' order by key) from public.cats) || '#' || "
           "(select string_agg(id||cat||key||title||sort||active, ',' order by id) from public.subcats)")
st, out = q(SQL)
check('шаг 1 повторно — без ошибок', st == 'OK', out[-500:])
check('повтор шага 1 ничего не дублирует и не меняет',
      val("select (select string_agg(key||title||sort||active, ',' order by key) from public.cats) || '#' || "
          "(select string_agg(id||cat||key||title||sort||active, ',' order by id) from public.subcats)") == snap
      and val('select count(*) from public.iva_kinds') == str(int(n_kinds) + 1))
check('свой вид главного админа повтор не удаляет', val("select count(*) from public.iva_kinds where key = 'my-kind'") == '1')
check('вид у объявления повтор не сбрасывает', val("select kind from public.tools where id = '809aa868-7020-4311-be41-9a62a5b0e684'") == 'car-polisher')

st, _ = as_user(None, 'select count(*) from public.iva_tree_plan();', 'anon')
check('план переноса гостю закрыт (названия и id — только в SQL-редакторе)', st == 'ERR')
# правило: регистр букв
check('правило не зависит от регистра (УШМ / ушм / Ушм)',
      val("select public.iva_sub_guess('power', 'УШМ') || public.iva_sub_guess('power', 'ушм') || public.iva_sub_guess('power', 'Ушм 125')")
      == 'grindersgrindersgrinders')

# ── шаг 2 ──
st, out = q(MOVE)
check('шаг 2 применяется без ошибок', st == 'OK', out[-500:])
got = {i: val("select cat || '/' || coalesce(sub, '∅') from public.tools where id = '%s'" % i) for i, *_ in TOOLS0}
EXPECT_AFTER = {'809aa868-7020-4311-be41-9a62a5b0e684': 'auto/dlya-lkp', 'ba2074a1-c534-4d68-941c-378ae250f955': 'power/power-other',
                '00000000-0000-0000-0000-000000000001': 'power/drills', '00000000-0000-0000-0000-000000000002': 'power/hammers',
                '00000000-0000-0000-0000-000000000003': 'power/grinders',
                '00000000-0000-0000-0000-000000000004': 'misc/old',             # «misc-other» нет — не трогаем
                '00000000-0000-0000-0000-000000000005': 'moving/scaffolding',   # переехал вместе с подкатегорией
                '00000000-0000-0000-0000-000000000006': 'power/∅'}               # без подкатегории — как было
check('шаг 2 переносит ровно по плану (чего нет — не трогает)', got == EXPECT_AFTER, json.dumps(got, ensure_ascii=False))
before2 = val("select string_agg(id || coalesce(sub, ''), ',' order by id) from public.tools")
st, _ = q(MOVE)
check('шаг 2 повторно ничего не меняет', st == 'OK' and val("select string_agg(id || coalesce(sub, ''), ',' order by id) from public.tools") == before2)

# удалили вид — у объявления вид пропадает, объявление остаётся
q("delete from public.iva_kinds where key = 'car-polisher';")
check('удалённый вид снимается с объявления (объявление остаётся)',
      val("select coalesce(kind, '∅') from public.tools where id = '809aa868-7020-4311-be41-9a62a5b0e684'") == '∅')

print('ИТОГ: %s' % ('всё верно' if fails == 0 else 'ошибок %d' % fails))
sys.exit(1 if fails else 0)

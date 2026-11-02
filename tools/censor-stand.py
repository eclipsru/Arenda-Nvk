# Стенд «Цензуры» (10.92): 20261021_censor.sql на настоящем PostgreSQL (pgserver из PyPI).
#
# База как в бою (роли, admins, fn_is_chief, tools, reviews, app_messages, 20261014_reviews_no_mat.sql), затем 20261021.
# Проверяет: все общие случаи tests/censor-cases.txt дают тот же уровень, что в приложении (IvaPatchTest);
# объявление с матом не сохраняется, сомнительное — снято с показа и в очереди с подробным «почему»; включить
# его снова автор не может; исправил — снова как было; очередь видит только создатель; «Оставить» / «Скрыть»;
# отзывы — так же; чат — только мат, сомнительное проходит и в очередь не попадает; списки слов действуют сразу;
# повторный запуск ничего не теряет; откат из шапки файла выполняется.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/censor-stand.py
import ast
import json
import os
import re
import subprocess
import sys
import tempfile

import pgserver
import pgserver.postgres_server

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def rd(p):
    return open(os.path.join(ROOT, p), encoding='utf-8').read()


NO_MAT = rd('supabase/migrations/20261014_reviews_no_mat.sql')
SQL = rd('supabase/migrations/20261021_censor.sql')
CASES = rd('tests/censor-cases.txt')

_src = rd('tools/category-tree-stand.py')
BASELINE = None
for node in ast.parse(_src).body:
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') == 'BASELINE':
        BASELINE = ast.literal_eval(node.value)

# сверх общей базы: поля объявления, auth.users / auth.uid, отзывы (как 20260924_reviews.sql), чат
EXTRA = """
alter table public.tools add column descr text, add column terms text;
create table auth.users(id uuid primary key, email text);
create function auth.uid() returns uuid language sql stable as $$
  select nullif(coalesce(auth.jwt()->>'sub', ''), '')::uuid $$;
grant execute on function auth.uid() to anon, authenticated;
create table public.reviews (
  id uuid primary key default gen_random_uuid(),
  tool_id uuid not null references public.tools(id) on delete cascade,
  author_id uuid not null references auth.users(id) on delete cascade,
  author_email text not null default '',
  author_name text not null default '',
  rating integer not null check (rating >= 1 and rating <= 5),
  text text not null default '' check (char_length(text) <= 1000),
  status text not null default 'published' check (status in ('published', 'hidden')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uq_reviews_tool_author unique (tool_id, author_id)
);
alter table public.reviews enable row level security;
create policy reviews_select on public.reviews for select to anon, authenticated using (status = 'published' or author_id = auth.uid());
create policy reviews_insert on public.reviews for insert to authenticated with check (author_id = auth.uid());
create policy reviews_update on public.reviews for update to authenticated using (author_id = auth.uid() or public.fn_is_chief());
create table public.app_messages(id bigserial primary key, from_email text, to_email text, text text, created_at timestamptz default now());
alter table public.app_messages enable row level security;
grant usage on all sequences in schema public to authenticated;
create policy msg_all on public.app_messages for all to authenticated using (true) with check (true);
create policy tools_write on public.tools for all to authenticated
  using (public.fn_is_chief() or lower(owner_email) = public.jwt_email())
  with check (public.fn_is_chief() or lower(owner_email) = public.jwt_email());
insert into auth.users values ('11111111-1111-1111-1111-111111111111', 'client@x.ru'), ('22222222-2222-2222-2222-222222222222', 'chief@x.ru');
insert into public.tools(id, name, cat, owner_email) values ('aaaaaaaa-0000-0000-0000-000000000001', 'Перфоратор Makita HR2470', 'power', 'admin@x.ru');
"""

srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
fails = 0


PSQL = str(pgserver.postgres_server.POSTGRES_BIN_PATH / 'psql')


def q(sql):
    # как srv.psql, но с текстом ошибок (stderr): стенд проверяет сообщения, которые увидит человек
    r = subprocess.run('%s %s' % (PSQL, srv.get_uri()), input=('\\set ON_ERROR_STOP on\n' + sql + "\n;\nselect 'IVA-END-OK' as mark;\n").encode(),
                       shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode('utf-8', 'replace').strip()
    return ('OK' if 'IVA-END-OK' in out else 'ERR'), out.replace('IVA-END-OK', '')


def val(sql):
    st, out = q('\\pset tuples_only on\n\\pset format unaligned\n' + sql)
    lines = [l for l in out.splitlines() if l and not l.startswith(('Tuples only', 'Output format'))]
    return ';'.join(lines)


def claims(email, sub=None):
    c = {'role': 'authenticated'}
    if email:
        c['email'] = email
    if sub:
        c['sub'] = sub
    return json.dumps(c, ensure_ascii=False).replace("'", "''")


def as_user(email, sql, sub=None, role='authenticated'):
    st, out = q("begin; set local role %s; set local request.jwt.claims = '%s'; %s commit;" % (role, claims(email, sub), sql))
    if out.rstrip().endswith('ROLLBACK') or 'ERROR' in out:
        st = 'ERR'
    return st, out


def uval(email, sql, sub=None, role='authenticated'):
    out = val("begin; set local role %s; set local request.jwt.claims = '%s';\n%s;\ncommit;" % (role, claims(email, sub), sql))
    return ';'.join(x for x in out.split(';') if x not in ('BEGIN', 'SET', 'COMMIT'))


def check(name, cond, detail=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + (' — ' + str(detail)[:900] if detail else ''))


def esc(s):
    return 'null' if s is None else "'" + s.replace("'", "''") + "'"


def unescape(s):
    return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s.replace('\\n', '\n'))


TOOL = "'aaaaaaaa-0000-0000-0000-000000000001'"
CLIENT = '11111111-1111-1111-1111-111111111111'

st, out = q(BASELINE + EXTRA + NO_MAT)
check('исходная база как в бою (объявления, отзывы 10.71, чат)', st == 'OK', out[-600:])

st, out = q(SQL)
check('20261021_censor.sql применяется без ошибок', st == 'OK', out[-900:])

# ── 1. общие случаи: тот же уровень, что в приложении ──
cases = []
for line in CASES.split('\n'):
    if not line.strip() or line.startswith('#'):
        continue
    want, txt = line.split('\t', 1)
    cases.append((want, unescape(txt)))
got = val('select ' + ', '.join("coalesce(public.iva_censor(%s)->>'level', '?')" % esc(t) for _, t in cases)).split('|')
bad = ['%s → %s' % (c[1], g) for c, g in zip(cases, got) if c[0] != g]
check('общие случаи tests/censor-cases.txt (%d) — как в приложении' % len(cases), len(got) == len(cases) and not bad and len(cases) >= 180, bad[:20])
check('что сработало и почему — как в приложении (латиница, транслит, по буквам, разбито, звёздочки, английский)',
      val("select string_agg(public.iva_censor(x)->'hits'->0->>'rule', ',' order by n) from unnest(array['xyй','pizdec','х у й','пи зда','х*й','fuck','жопа','хуй']) with ordinality u(x, n)")
      == 'visual,translit,letters,split,masked,en,rude,word')
check('как прочитали: «xyйня» → «хуйня», «nahuy» → «нахуй»',
      val("select public.iva_censor('Сдаю, xyйня')->'hits'->0->>'as', public.iva_censor('nahuy')->'hits'->0->>'as'") == 'хуйня|нахуй')
check('iva_has_mat (отзывы 10.71) — та же проверка: латиница теперь тоже мат',
      val("select public.iva_has_mat('nahuy'), public.iva_has_mat('жопа'), public.iva_has_mat(null), public.iva_has_mat('Перфоратор')") == 't|f|f|f')
check('подробное «почему» по-русски', val("select public.iva_censor_reason(public.iva_censor('xyйня, жопа')->'hits', 'tool')")
      == '«xyйня» (читается как «хуйня») — нецензурное слово, написанное похожими латинскими буквами или цифрами.;'
         '«жопа» — грубое слово.;Объявление снято с показа до вашего решения.')

# ── 2. объявления ──
st, out = as_user('admin@x.ru', "update public.tools set name = 'Перфоратор xyйня' where id = %s;" % TOOL)
check('объявление с матом (латиницей) не сохраняется — понятное сообщение',
      st == 'ERR' and 'В объявлении есть нецензурные слова (Название: «xyйня»)' in out, out[-300:])
st, out = as_user('admin@x.ru', "update public.tools set descr = 'Хозяин урод, но перфоратор отличный' where id = %s;" % TOOL)
check('сомнительное описание сохраняется, но объявление снято с показа',
      st == 'OK' and val('select active from public.tools where id = %s' % TOOL) == 'f', out[-300:])
check('в очереди: где, кто, весь текст, слово и почему',
      val("select source, field, title, author_email, content, hits->0->>'word', want_active, status from public.iva_censor_queue")
      == 'tool|descr|Перфоратор Makita HR2470|admin@x.ru|Хозяин урод, но перфоратор отличный|урод|t|new'
      and 'грубое слово' in val('select reason from public.iva_censor_queue'))
as_user('admin@x.ru', "update public.tools set active = true where id = %s;" % TOOL)
check('автор не может включить объявление, пока создатель не решил', val('select active from public.tools where id = %s' % TOOL) == 'f')
check('попытка включить запомнена — после «Оставить» объявление включится', val('select want_active from public.iva_censor_queue') == 't')

check('очередь видит только создатель', uval('chief@x.ru', 'select count(*) from public.iva_censor_queue') == '1'
      and uval('admin@x.ru', 'select count(*) from public.iva_censor_queue') == '0')
st, out = q("begin; set local role anon; select count(*) from public.iva_censor_queue; commit;")
check('гость очередь не читает', 'permission denied' in out, out[-200:])
st, out = as_user('admin@x.ru', 'select public.iva_censor_decide(1, true);')
check('решать может только создатель (админ — 42501)', st == 'ERR' and 'only chief' in out, out[-200:])
check('создатель: «Оставить» — объявление снова в каталоге',
      uval('chief@x.ru', 'select public.iva_censor_decide(1, true)') == 'kept'
      and val('select active, (select status from public.iva_censor_queue where id = 1) from public.tools where id = %s' % TOOL) == 't|kept')
st, out = as_user('chief@x.ru', 'select public.iva_censor_decide(1, false);')
check('повторное решение запрещено', st == 'ERR' and 'already decided' in out)
as_user('admin@x.ru', "update public.tools set name = 'Перфоратор Makita HR2470 SDS' where id = %s;" % TOOL)
check('оставленный текст не попадает в очередь снова при других правках',
      val("select count(*) from public.iva_censor_queue") == '1' and val('select active from public.tools where id = %s' % TOOL) == 't')
as_user('admin@x.ru', "update public.tools set terms = 'Не возвращаешь вовремя — ты дебил' where id = %s;" % TOOL)
check('новое сомнительное слово в условиях — снова на проверку',
      val("select count(*) from public.iva_censor_queue where status = 'new' and field = 'terms'") == '1'
      and val('select active from public.tools where id = %s' % TOOL) == 'f')
as_user('admin@x.ru', "update public.tools set terms = 'Залог 3000 ₽, вернуть чистым' where id = %s;" % TOOL)
check('автор исправил текст — запись «исправлено», объявление снова в каталоге',
      val("select status from public.iva_censor_queue where field = 'terms'") == 'fixed'
      and val('select active from public.tools where id = %s' % TOOL) == 't')
as_user('admin@x.ru', "update public.tools set terms = 'ты дебил' where id = %s;" % TOOL)
check('создатель: «Скрыть» — объявление выключено', uval('chief@x.ru', "select public.iva_censor_decide(max(id), false) from public.iva_censor_queue") == 'hidden'
      and val('select active from public.tools where id = %s' % TOOL) == 'f')
as_user('admin@x.ru', "update public.tools set active = true where id = %s;" % TOOL)
check('скрытое создателем автор включить не может', val('select active from public.tools where id = %s' % TOOL) == 'f')
st, out = as_user('chief@x.ru', "update public.tools set terms = 'ты дебил, хозяин урод' where id = %s;" % TOOL)
check('правки создателя в очередь не попадают', st == 'OK' and val("select count(*) from public.iva_censor_queue where status = 'new'") == '0', out[-200:])
st, out = as_user('admin@x.ru', "insert into public.tools(name, cat, owner_email, descr) values ('Болгарка', 'power', 'admin@x.ru', 'с у к а');")
check('новое объявление с матом по буквам не сохраняется', st == 'ERR' and 'нецензурные' in out, out[-200:])
st, out = as_user('admin@x.ru', "insert into public.tools(name, cat, owner_email, descr) values ('Болгарка', 'power', 'admin@x.ru', 'Отличная, не х*йня');")
check('новое объявление со звёздочками — сохраняется скрытым и в очереди',
      st == 'OK' and val("select active from public.tools where name = 'Болгарка'") == 'f'
      and val("select hits->0->>'rule' from public.iva_censor_queue where title = 'Болгарка'") == 'masked', out[-200:])

# ── 3. отзывы ──
RV = "insert into public.reviews(tool_id, author_id, author_email, author_name, rating, text) values (%s, '%s', 'client@x.ru', %s, 5, %s);"
st, out = as_user('client@x.ru', RV % (TOOL, CLIENT, "'Иван'", "'pizdec какой хороший'"), sub=CLIENT)
check('отзыв с матом латиницей не сохраняется', st == 'ERR' and 'В отзыве есть нецензурные слова' in out, out[-200:])
st, out = as_user('client@x.ru', RV % (TOOL, CLIENT, "'Иван'", "'Хозяин урод, но инструмент хороший'"), sub=CLIENT)
check('сомнительный отзыв сохраняется скрытым и попадает в очередь',
      st == 'OK' and val("select status from public.reviews") == 'hidden'
      and val("select source, field, title, author_email from public.iva_censor_queue where source = 'review'") == 'review|text|Перфоратор Makita HR2470 SDS|client@x.ru',
      out[-300:])
check('автор видит свой скрытый отзыв, гость — нет', uval('client@x.ru', 'select count(*) from public.reviews', sub=CLIENT) == '1'
      and val("begin; set local role anon; select count(*) from public.reviews; commit;").replace('BEGIN;', '').replace(';COMMIT', '').replace('SET;', '') == '0')
as_user('client@x.ru', "update public.reviews set text = 'Инструмент хороший', updated_at = now();", sub=CLIENT)
check('автор исправил отзыв — снова опубликован, запись «исправлено»', val("select status from public.reviews") == 'published'
      and val("select status from public.iva_censor_queue where source = 'review'") == 'fixed')
as_user('client@x.ru', "update public.reviews set author_name = 'Иван Сука', updated_at = now();", sub=CLIENT)
check('мат в имени — отзыв не сохраняется', val("select author_name from public.reviews") == 'Иван')
as_user('client@x.ru', "update public.reviews set text = 'Норм, но жопа болит таскать', updated_at = now();", sub=CLIENT)
qid = val("select max(id) from public.iva_censor_queue where source = 'review'")
check('создатель: «Оставить» — отзыв опубликован', uval('chief@x.ru', 'select public.iva_censor_decide(%s, true)' % qid) == 'kept'
      and val("select status from public.reviews") == 'published')

# ── 4. чат ──
st, out = as_user('client@x.ru', "insert into public.app_messages(from_email, to_email, text) values ('client@x.ru', 'admin@x.ru', 'ну ты xyйло');")
check('чат: мат не отправляется', st == 'ERR' and 'В сообщении есть нецензурные слова' in out, out[-200:])
n0 = val('select count(*) from public.iva_censor_queue')
st, out = as_user('client@x.ru', "insert into public.app_messages(from_email, to_email, text) values ('client@x.ru', 'admin@x.ru', 'ты урод');")
check('чат: сомнительное проходит и создателю не отправляется', st == 'OK' and val('select count(*) from public.iva_censor_queue') == n0, out[-200:])

# ── 5. списки слов ──
st, out = as_user('admin@x.ru', "select public.iva_censor_word('хрен', 'allow');")
check('менять списки может только создатель', st == 'ERR' and 'only chief' in out)
check('«Это не мат» — слово сразу чистое (регистр и знаки убираются)',
      uval('chief@x.ru', "select public.iva_censor_word(' ХРЕН! ', 'allow')") == 'хрен' and val("select public.iva_censor('хрен')->>'level'") == 'ok')
check('«Запрещать всегда» — сразу мат, и внутри слова', uval('chief@x.ru', "select public.iva_censor_word('гавно', 'ban')") == 'гавно'
      and val("select public.iva_censor('гавноед')->>'level', public.iva_censor('гавноед')->'hits'->0->>'rule'") == 'mat|ban')
st, out = as_user('client@x.ru', "insert into public.app_messages(from_email, to_email, text) values ('client@x.ru', 'admin@x.ru', 'гавно');")
check('запрещённое слово сразу не проходит и в чате', st == 'ERR')
check('список читают все (приложению — для той же проверки)', val("begin; set local role anon; select count(*) from public.iva_censor_words; commit;").split(';')[2] == '2')
st, out = as_user('chief@x.ru', "select public.iva_censor_word('х', 'ban');")
check('слишком короткое слово — понятная ошибка', st == 'ERR' and 'от 2 до 40' in out)
check('«убрать из списков»', uval('chief@x.ru', "select public.iva_censor_word('хрен', 'none')") == 'хрен'
      and val("select public.iva_censor('хрен')->>'level'") == 'suspect')
st, out = as_user('admin@x.ru', "insert into public.iva_censor_words values ('блин', 'ban');")
check('напрямую в таблицы писать нельзя', st == 'ERR')
st, out = as_user('admin@x.ru', "insert into public.iva_censor_queue(source, ref_id, field) values ('tool', 'x', 'name');")
check('в очередь пишут только триггеры', st == 'ERR')

# ── 6. повторный запуск и откат ──
before = val('select count(*), (select count(*) from public.iva_censor_words) from public.iva_censor_queue')
st, out = q(SQL)
check('повторный запуск безопасен: очередь и слова на месте', st == 'OK' and val('select count(*), (select count(*) from public.iva_censor_words) from public.iva_censor_queue') == before, out[-300:])
m = re.search(r'-- Откат[^\n]*\n((?:--[^\n]*\n)+)', SQL)
rollback = '\n'.join(l[2:].strip() for l in m.group(1).splitlines() if not l.startswith('--   затем') and not l.startswith('-- ====')) if m else ''
rollback = rollback.replace('затем выполнить заново 20261014_reviews_no_mat.sql (вернёт прежнюю iva_has_mat) и', '')
st, out = q(rollback.split('drop function if exists public.iva_censor(text)')[0] + NO_MAT
            + 'drop function if exists public.iva_censor(text)' + rollback.split('drop function if exists public.iva_censor(text)')[1])
check('откат из шапки файла выполняется; отзывы снова под проверкой 10.71', st == 'OK'
      and val("select count(*) from pg_proc where proname like 'iva_censor%'") == '0'
      and val("select public.iva_has_mat('хуй'), public.iva_has_mat('nahuy')") == 't|f', out[-500:])

srv.cleanup()
print('ошибок: %d' % fails if fails else 'все проверки стенда «Цензура» пройдены')
sys.exit(1 if fails else 0)

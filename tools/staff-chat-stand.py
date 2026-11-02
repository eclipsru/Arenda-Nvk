# Стенд этапа 3г «Сотрудники и пункты выдачи» (10.106): 20261031_staff_chat.sql на настоящем PostgreSQL (pgserver).
#
# База как в стенде 10.105 (BASELINE + пункты + 20261026 + 20261028), чат как в бою (app_messages с колонками сайта,
# правило «только своя переписка»), профиль клиента с паспортом, «Цензура» (20261014_reviews_no_mat + 20261021).
# Проверяет: сотрудник с правом «Отвечает в чате» видит переписки клиентов только по инструментам своих пунктов
# (без общего чата, чужих пунктов и прокатов, без переписок, где клиент не писал, и старше 90 дней); в ответе нет почты,
# телефона, фамилии и паспорта клиента; ответ уходит от имени проката в ту же переписку, кто ответил — записано; мат не
# уходит; первым и в чужое написать нельзя; без права, не принявший, клиент, гость — нельзя; «прочитано»; своя переписка
# сотрудника-клиента не видна; снятое право, перенос в другой пункт, переезд инструмента, выключенный прокат, «Убрать» —
# доступ пропадает сразу; откат из шапки; повторное применение; без SQL 10.103 / 10.54 — понятная ошибка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-chat-stand.py
import ast
import json
import os
import subprocess
import sys
import tempfile
import uuid

import pgserver
import pgserver.postgres_server

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def rd(p):
    return open(os.path.join(ROOT, p), encoding='utf-8').read()


def const(path, name):
    for node in ast.parse(rd(path)).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') == name:
            return ast.literal_eval(node.value)
    raise SystemExit('нет %s в %s' % (name, path))


RENT = rd('supabase/migrations/20261014_rent_end_mode.sql')
PICK = rd('supabase/migrations/20261023_pickup_points.sql')
TEL = rd('supabase/migrations/20261025_pickup_tel.sql')
TP = rd('supabase/migrations/20261026_tool_points.sql')
OP = rd('supabase/migrations/20261027_order_points.sql')
STAFF = rd('supabase/migrations/20261028_staff.sql')
SQL = rd('supabase/migrations/20261031_staff_chat.sql')
NO_MAT = rd('supabase/migrations/20261014_reviews_no_mat.sql')
CENSOR = rd('supabase/migrations/20261021_censor.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
# откат — строки «--      …» блока «ОТКАТ» в шапке файла
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--  '))

PSQL = str(pgserver.postgres_server.POSTGRES_BIN_PATH / 'psql')
fails = 0


def check(name, cond, detail=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + (' — ' + str(detail)[:900] if detail else ''))


def mk():
    return pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')


def q(srv, sql):
    r = subprocess.run('%s %s' % (PSQL, srv.get_uri()), input=('\\set ON_ERROR_STOP on\n' + sql + "\n;\nselect 'IVA-END-OK' as mark;\n").encode(),
                       shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode('utf-8', 'replace').strip()
    return ('OK' if 'IVA-END-OK' in out else 'ERR'), out.replace('IVA-END-OK', '')


def val(srv, sql):
    st, out = q(srv, '\\pset tuples_only on\n\\pset format unaligned\n' + sql)
    lines = [l for l in out.splitlines() if l and not l.startswith(('Tuples only', 'Output format', 'mark'))]
    return ';'.join(x for x in lines if x not in ('BEGIN', 'SET', 'COMMIT', 'INSERT 0 1', 'UPDATE 1', 'DELETE 1', 'UPDATE 0', 'DELETE 0'))


def claims(email):
    c = {'role': 'authenticated'}
    if email:
        c['email'] = email
        c['sub'] = str(uuid.uuid5(uuid.NAMESPACE_DNS, email))
    return json.dumps(c).replace("'", "''")


def as_(srv, email, sql, role='authenticated'):
    st, out = q(srv, "begin; set local role %s; set local request.jwt.claims = '%s'; %s; commit;" % (role, claims(email), sql))
    if out.rstrip().endswith('ROLLBACK') or 'ERROR' in out:
        st = 'ERR'
    return st, out


def uval(srv, email, sql, role='authenticated'):
    return val(srv, "begin; set local role %s; set local request.jwt.claims = '%s';\n%s;\ncommit;" % (role, claims(email), sql))


def err_has(r, text):
    return r[0] == 'ERR' and text in r[1]


def ins_point(name, city, addr):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email) "
            "values ('%s', '%s', '%s', 55.0, 82.9, 'prokat@mail.ru')") % (name, addr, city)


def tid(srv, name, owner):
    return val(srv, "select id from public.tools where name = '%s' and owner_email = '%s'" % (name, owner))


def save(srv, owner, sid, email, name, points, issue, catalog, chat, notify, self_):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, %s, %s, %s)" %
                ('null' if sid is None else "'%s'" % sid, email, name, arr, b(issue), b(catalog), b(chat), b(notify), b(self_)))


def accept(srv, email, sid):
    return uval(srv, email, "select public.iva_staff_answer('%s', true)" % sid)



# Как в бою: чат (app_messages с колонками сайта, своя переписка — правило «только своё»), профиль клиента, отзывы и
# «Цензура» (20261014_reviews_no_mat + 20261021) — чтобы проверить мат в ответе сотрудника
CHATX = r"""
alter table public.tools add column if not exists img text;
alter table public.tools add column if not exists terms text;
create table auth.users(id uuid primary key, email text);
create function auth.uid() returns uuid language sql stable as $$ select nullif(auth.jwt() ->> 'sub', '')::uuid $$;
grant execute on function auth.uid() to anon, authenticated;
create table public.reviews (
  id uuid primary key default gen_random_uuid(),
  tool_id uuid not null references public.tools(id) on delete cascade,
  author_id uuid not null references auth.users(id) on delete cascade,
  author_email text not null default '', author_name text not null default '',
  rating integer not null check (rating >= 1 and rating <= 5),
  text text not null default '' check (char_length(text) <= 1000),
  status text not null default 'published' check (status in ('published', 'hidden')),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  constraint uq_reviews_tool_author unique (tool_id, author_id));
alter table public.reviews enable row level security;
create policy reviews_select on public.reviews for select to anon, authenticated using (status = 'published' or author_id = auth.uid());
create table public.app_messages(id bigserial primary key, from_email text not null, to_email text not null, text text default '',
  img text default '', audio text default '', audio_dur numeric default 0, is_read boolean default false,
  created_at timestamptz default now(), tool_id uuid references public.tools(id) on delete set null, topic text);
alter table public.app_messages enable row level security;
grant select, insert, update, delete on public.app_messages to authenticated;
grant usage on all sequences in schema public to authenticated;
create policy msg_own_sel on public.app_messages for select to authenticated
  using (public.jwt_email() in (lower(btrim(from_email)), lower(btrim(to_email))));
create policy msg_own_ins on public.app_messages for insert to authenticated with check (lower(btrim(from_email)) = public.jwt_email());
create policy msg_own_upd on public.app_messages for update to authenticated using (lower(btrim(to_email)) = public.jwt_email());
create table public.customer_profiles(user_id uuid primary key references auth.users(id) on delete cascade,
  surname text not null default '', given_name text not null default '', phone text not null default '',
  passport_data jsonb not null default '{}'::jsonb);
alter table public.customer_profiles enable row level security;
insert into auth.users values ('11111111-1111-1111-1111-111111111111', 'client@x.ru'), ('22222222-2222-2222-2222-222222222222', 'client2@x.ru');
insert into public.customer_profiles(user_id, surname, given_name, phone, passport_data)
  values ('11111111-1111-1111-1111-111111111111', 'Клиентова', 'Анна', '+79991112233', '{"number": "1234 567890"}');
"""


def md5(s):
    import hashlib
    return hashlib.md5(s.encode()).hexdigest()


def say(srv, frm, to, text, tool=None, extra=''):
    """Сообщение «как из приложения»: вошёл frm, пишет сам (правило «только своё»)."""
    return as_(srv, frm, "insert into public.app_messages(from_email, to_email, text, tool_id%s) values ('%s', '%s', '%s', %s%s)"
               % (', audio' if extra else '', frm, to, text.replace("'", "''"), "'%s'" % tool if tool else 'null',
                  ", '%s'" % extra if extra else ''))


def chats(srv, email):
    """Что видит сотрудник: «инструмент|клиент|пункт|последнее|моё|непрочитано», по порядку."""
    return uval(srv, email, "select coalesce(string_agg(tool_name || '|' || client_name || '|' || point_name || '|' || last_text || '|' "
                            "|| last_mine || '|' || unread, ' ; ' order by n), '') from public.iva_staff_chats() with ordinality as x(tool_id, "
                            "peer, client_name, tool_name, tool_img, point_name, last_text, last_at, last_mine, unread, last_client_id, n)")


def thread(srv, email, tool, peer):
    return uval(srv, email, "select coalesce(string_agg(text || '|' || mine || '|' || by_me || '|' || is_read, ' ; ' order by n), '') "
                            "from public.iva_staff_chat('%s', '%s') with ordinality as x(id, mine, by_me, text, img, audio, audio_dur, "
                            "created_at, is_read, n)" % (tool, peer))


def send(srv, email, tool, peer, text):
    return as_(srv, email, "select public.iva_staff_chat_send('%s', '%s', '%s')" % (tool, peer, text.replace("'", "''")))


# ── 1. база, применение, повтор ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + CHATX)
check('исходная база как в бою (чат «только своё», профиль клиента, отзывы)', st == 'OK', out[-600:])
check('SQL «Цензуры» 10.92 применяется', all(q(srv, s)[0] == 'OK' for s in (NO_MAT, CENSOR)))
check('SQL 10.95, 10.99, 10.101, 10.103 применяются', all(q(srv, s)[0] == 'OK' for s in (PICK, TEL, TP, STAFF)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Пушкина, 3'))
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
BERDSK = val(srv, "select id from public.iva_pickup_points where name = 'Бердск'")
ISK = val(srv, "select id from public.iva_pickup_points where name = 'Искитим'")
q(srv, ("insert into public.tools(name, owner_email, price, point_id, active) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}', true), ('Пила', 'admin@x.ru', 500, '{b}', true), ('Дрель', 'admin2@x.ru', 100, '{i}', true)")
  .format(s=SKLAD, b=BERDSK, i=ISK))
DRILL, SAW, D2 = tid(srv, 'Дрель Makita', 'admin@x.ru'), tid(srv, 'Пила', 'admin@x.ru'), tid(srv, 'Дрель', 'admin2@x.ru')
st, out = q(srv, SQL)
check('20261031_staff_chat.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])

# сотрудники: Иван — «Выдача» (чат) на Складе; Пётр — «Каталог» на Складе (без чата); Ольга — чат в Бердске;
# Маша — чат на Складе, не приняла; Гоша — чат у другого проката
IVAN = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [SKLAD], True, False, True, True, False)
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD], False, True, False, False, False)
OLGA = save(srv, 'admin@x.ru', None, 'olga@x.ru', 'Ольга', [BERDSK], False, False, True, False, False)
MASHA = save(srv, 'admin@x.ru', None, 'masha@x.ru', 'Маша', [SKLAD], False, False, True, False, False)
GOSHA = save(srv, 'admin2@x.ru', None, 'gosha@x.ru', 'Гоша', [ISK], False, False, True, False, False)
for em, sid in (('ivan@x.ru', IVAN), ('petr@x.ru', PETR), ('olga@x.ru', OLGA), ('gosha@x.ru', GOSHA)):
    accept(srv, em, sid)
check('сотрудники приглашены и приняли (стенд 10.103)', val(srv, "select count(*) from public.iva_staff where status = 'active'") == '4')

# переписки (как из приложения клиента и владельца)
say(srv, 'client@x.ru', 'admin@x.ru', 'Здравствуйте, дрель свободна?', DRILL)
say(srv, 'admin@x.ru', 'client@x.ru', 'Да, свободна', DRILL)
say(srv, 'client2@x.ru', 'admin@x.ru', '', DRILL, extra='https://x/voice/v1.m4a')
say(srv, 'client@x.ru', 'admin@x.ru', 'А пила?', SAW)
say(srv, 'client@x.ru', 'admin@x.ru', 'Общий вопрос без инструмента')
say(srv, 'client@x.ru', 'admin2@x.ru', 'Дрель у вас есть?', D2)
say(srv, 'admin@x.ru', 'client3@x.ru', 'Владелец написал первым', DRILL)
q(srv, "insert into public.app_messages(from_email, to_email, text, tool_id, created_at) values "
       "('client4@x.ru', 'admin@x.ru', 'Старое', '%s', now() - interval '100 days')" % DRILL)
check('переписки клиентов записаны (как из приложения)', val(srv, 'select count(*) from public.app_messages') == '8')

# ── 2. что видит сотрудник ──
IV = chats(srv, 'ivan@x.ru')
check('Иван видит переписки своего пункта: Анна и клиент без профиля (голосовое), непрочитанные',
      IV == 'Дрель Makita|Клиент|Склад|🎤 Голосовое сообщение|false|1 ; Дрель Makita|Анна|Склад|Да, свободна|true|1', IV)
raw = uval(srv, 'ivan@x.ru', "select string_agg(to_jsonb(x)::text, ' ') from public.iva_staff_chats() x")
check('в ответе нет почты, телефона, фамилии и паспорта клиента', '@' not in raw and '7999' not in raw and 'Клиентова' not in raw
      and '1234' not in raw and md5('client@x.ru') in raw, raw[:300])
check('нет: общего чата, чужого пункта, чужого проката, где клиент не писал, старше 90 дней',
      'Общий' not in IV and 'Пила' not in IV and 'Владелец написал' not in IV and 'Старое' not in IV)
check('Ольга (Бердск) — только пила', chats(srv, 'olga@x.ru') == 'Пила|Анна|Бердск|А пила?|false|1')
check('Гоша (другой прокат) — только свой', chats(srv, 'gosha@x.ru') == 'Дрель|Анна|Искитим|Дрель у вас есть?|false|1')
check('без права «Отвечает в чате» (Пётр), не принявший (Маша), клиент, владелец — пусто',
      all(chats(srv, e) == '' for e in ('petr@x.ru', 'masha@x.ru', 'client@x.ru', 'admin@x.ru')))
st, out = as_(srv, '', 'select * from public.iva_staff_chats()', role='anon')
check('гостю — нет доступа', st == 'ERR' and 'permission denied' in out, out[-200:])
for fn in ('iva_staff_chat_me()', 'iva_staff_chat_tools()'):
    st, out = as_(srv, 'ivan@x.ru', 'select * from public.' + fn)
    check('служебная %s — не вызвать из приложения' % fn.split('(')[0], st == 'ERR' and 'permission denied' in out, out[-200:])

# ── 3. переписка ──
PA, PC2 = md5('client@x.ru'), md5('client2@x.ru')
check('переписка с Анной — по порядку, свои (прокат) справа', thread(srv, 'ivan@x.ru', DRILL, PA) == 'Здравствуйте, дрель свободна?|false|false|false ; Да, свободна|true|false|false')
check('голосовое клиента — адрес и длительность отдаются', uval(srv, 'ivan@x.ru', "select audio from public.iva_staff_chat('%s', '%s')" % (DRILL, PC2)) == 'https://x/voice/v1.m4a')
check('чужой пункт, чужая метка, не сотрудник — пусто', thread(srv, 'olga@x.ru', DRILL, PA) == '' and thread(srv, 'ivan@x.ru', SAW, PA) == ''
      and thread(srv, 'ivan@x.ru', DRILL, md5('nobody@x.ru')) == '' and thread(srv, 'petr@x.ru', DRILL, PA) == '' and thread(srv, 'client@x.ru', DRILL, PA) == '')
st, out = as_(srv, 'ivan@x.ru', "select count(*) from public.app_messages where tool_id = '%s'" % DRILL)
check('напрямую таблицу сообщений сотрудник не читает (правило «только своё»)', uval(srv, 'ivan@x.ru', "select count(*) from public.app_messages") == '0')

# ── 4. ответ ──
st, out = send(srv, 'ivan@x.ru', DRILL, PA, 'Приходите к 10, дрель ждёт')
check('Иван ответил', st == 'OK', out[-300:])
check('ответ — от проката клиенту, по этой дрели, кто ответил — записано',
      val(srv, "select from_email || '|' || to_email || '|' || (tool_id = '%s') || '|' || coalesce(staff_id::text, '') from public.app_messages "
               "where text like 'Приходите%%'" % DRILL) == 'admin@x.ru|client@x.ru|true|' + IVAN)
check('клиент видит ответ в той же переписке (от проката)',
      uval(srv, 'client@x.ru', "select count(*) from public.app_messages where from_email = 'admin@x.ru' and tool_id = '%s'" % DRILL) == '2')
check('владелец видит ответ у себя', uval(srv, 'admin@x.ru', "select count(*) from public.app_messages where text like 'Приходите%'") == '1')
check('в переписке у Ивана — «вы»', thread(srv, 'ivan@x.ru', DRILL, PA).endswith('Приходите к 10, дрель ждёт|true|true|false'))
check('в списке — последнее «моё»', chats(srv, 'ivan@x.ru').startswith('Дрель Makita|Анна|Склад|Приходите к 10, дрель ждёт|true|1'), chats(srv, 'ivan@x.ru'))
check('мат не уходит («Цензура» чата)', err_has(send(srv, 'ivan@x.ru', DRILL, PA, 'ну ты xyйло'), 'В сообщении есть нецензурные слова'))
check('пустое — «Напишите сообщение»', err_has(send(srv, 'ivan@x.ru', DRILL, PA, '   '), 'Напишите сообщение'))
check('длиннее 2000 — понятная ошибка', err_has(send(srv, 'ivan@x.ru', DRILL, PA, 'а' * 2001), 'длиннее 2000'))
check('в чужой пункт — нельзя', err_has(send(srv, 'ivan@x.ru', SAW, PA, 'Привет'), 'Переписка не найдена в вашем пункте'))
check('где клиент не писал — нельзя (первым сотрудник не пишет)', err_has(send(srv, 'ivan@x.ru', DRILL, md5('client3@x.ru'), 'Привет'), 'Переписка не найдена'))
check('чужая метка — нельзя', err_has(send(srv, 'ivan@x.ru', DRILL, md5('nobody@x.ru'), 'Привет'), 'Переписка не найдена'))
check('другой прокат — нельзя', err_has(send(srv, 'gosha@x.ru', DRILL, PA, 'Привет'), 'Переписка не найдена'))
check('без права, не принявший, клиент — «Нет права»', all(err_has(send(srv, e, DRILL, PA, 'Привет'), 'Нет права «Отвечает в чате»')
                                                      for e in ('petr@x.ru', 'masha@x.ru', 'client@x.ru')))
check('гостю — нет доступа', err_has(as_(srv, '', "select public.iva_staff_chat_send('%s', '%s', 'x')" % (DRILL, PA), role='anon'), 'permission denied'))
check('от имени проката напрямую — нельзя (правило «только своё»)',
      as_(srv, 'ivan@x.ru', "insert into public.app_messages(from_email, to_email, text) values ('admin@x.ru', 'client@x.ru', 'x')")[0] == 'ERR')
check('сообщений лишних не появилось', val(srv, 'select count(*) from public.app_messages') == '9')

# ── 5. прочитано ──
check('открыл переписку — сообщение клиента прочитано (1)', uval(srv, 'ivan@x.ru', "select public.iva_staff_chat_read('%s', '%s')" % (DRILL, PA)) == '1'
      and val(srv, "select is_read from public.app_messages where text like 'Здравствуйте%'") == 't')
check('повторно — 0, свои не трогаются', uval(srv, 'ivan@x.ru', "select public.iva_staff_chat_read('%s', '%s')" % (DRILL, PA)) == '0'
      and val(srv, "select is_read from public.app_messages where text like 'Приходите%'") == 'f')
check('в списке непрочитанных 0', '|Анна|Склад|Приходите к 10, дрель ждёт|true|0' in chats(srv, 'ivan@x.ru'))
check('чужой пункт / без права — нельзя', err_has(as_(srv, 'olga@x.ru', "select public.iva_staff_chat_read('%s', '%s')" % (DRILL, PA)), 'Переписка не найдена')
      and err_has(as_(srv, 'petr@x.ru', "select public.iva_staff_chat_read('%s', '%s')" % (DRILL, PA)), 'Нет права'))

# ── 6. сотрудник — сам клиент ──
say(srv, 'olga@x.ru', 'admin@x.ru', 'Пила свободна в выходные?', SAW)
check('своя переписка (как клиента) в чате пункта не видна', 'Пила свободна' not in chats(srv, 'olga@x.ru') and chats(srv, 'olga@x.ru').count('|') == 5)
check('и ответить себе от проката нельзя', err_has(send(srv, 'olga@x.ru', SAW, md5('olga@x.ru'), 'x'), 'Переписка не найдена')
      and thread(srv, 'olga@x.ru', SAW, md5('olga@x.ru')) == '')

# ── 7. доступ пропадает сразу ──
save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [SKLAD], True, False, False, True, False)
check('владелец снял «Отвечает в чате» — пусто и ответить нельзя', chats(srv, 'ivan@x.ru') == ''
      and err_has(send(srv, 'ivan@x.ru', DRILL, PA, 'x'), 'Нет права'))
save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [BERDSK], True, False, True, True, False)
check('перенесли в Бердск — видит пилу, не дрель', chats(srv, 'ivan@x.ru').startswith('Пила|') and 'Дрель' not in chats(srv, 'ivan@x.ru'))
save(srv, 'admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [SKLAD], True, False, True, True, False)
as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where id = '%s'" % (BERDSK, DRILL))
check('дрель переехала в Бердск — у Ивана пропала, у Ольги появилась', 'Дрель' not in chats(srv, 'ivan@x.ru') and 'Дрель Makita|Анна|Бердск' in chats(srv, 'olga@x.ru'))
as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where id = '%s'" % (SKLAD, DRILL))
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
check('прокат выключен — пусто', chats(srv, 'ivan@x.ru') == '')
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")
check('прокат включён — снова видно', 'Дрель Makita|Анна' in chats(srv, 'ivan@x.ru'))
st, out = as_(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % IVAN)
check('«Убрать из сотрудников» — пусто сразу, ответ остался у клиента (без отметки сотрудника)', st == 'OK' and chats(srv, 'ivan@x.ru') == ''
      and val(srv, "select coalesce(staff_id::text, 'пусто') from public.app_messages where text like 'Приходите%'") == 'пусто', out[-300:])

# ── 8. откат из шапки, повтор ──
check('в шапке есть блок отката', RB.count('DROP FUNCTION') == 6 and 'DROP COLUMN IF EXISTS staff_id' in RB and 'NOTIFY pgrst' in RB, RB)
n0 = val(srv, 'select count(*) from public.app_messages')
st, out = q(srv, RB)
check('откат выполняется без ошибок', st == 'OK', out[-600:])
check('после отката: функций и столбца нет, сообщения на месте',
      val(srv, "select count(*) from pg_proc where proname like 'iva_staff_chat%'") == '0'
      and val(srv, "select count(*) from information_schema.columns where table_name = 'app_messages' and column_name = 'staff_id'") == '0'
      and val(srv, 'select count(*) from public.app_messages') == n0)
check('после отката клиент пишет как раньше', say(srv, 'client@x.ru', 'admin@x.ru', 'После отката', DRILL)[0] == 'OK')
st, out = q(srv, SQL)
check('после отката применяется снова', st == 'OK' and out.count('✅') == 4, out[-600:])
srv.cleanup()

# ── 9. без SQL 10.103 / без чата по инструментам — понятная ошибка ──
srv = mk()
q(srv, BASELINE + EXTRA + PROD + CHATX)
for s in (PICK, TEL, TP):
    q(srv, s)
st, out = q(srv, SQL)
check('без SQL 10.103 — понятная ошибка', st == 'ERR' and 'Сначала примените 20261028_staff.sql' in out, out[-400:])
q(srv, STAFF)
q(srv, 'alter table public.app_messages drop column tool_id')
st, out = q(srv, SQL)
check('без чата по инструментам (10.54) — понятная ошибка', st == 'ERR' and 'Сначала примените 20261012_chat_tools_avatars.sql' in out, out[-400:])
q(srv, 'alter table public.app_messages add column tool_id uuid')
st, out = q(srv, SQL)
check('без «Цензуры» — применяется (строка проверки «✅»)', st == 'OK' and out.count('✅') == 4, out[-600:])
srv.cleanup()

print('\nИтого: %s' % ('всё ✔' if not fails else '%d ✘' % fails))
sys.exit(1 if fails else 0)

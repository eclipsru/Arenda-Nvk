# Стенд 10.120 «Страница проката и чат пункта выдачи»: 20261111_prokat_page.sql на настоящем PostgreSQL (pgserver).
#
# База как в стендах 10.105–10.106 (BASELINE + пункты + инструменты сотрудников + чат «только своё» + «Цензура»).
# Проверяет: страница проката — гостю и клиенту отдаются все пункты проката (название, адрес, график, телефон,
# аватарка и название проката, число видимых инструментов), без почты пункта и сотрудников; пункт без инструментов
# и выключенный прокат — пусто; сообщение с пунктом сохраняется только прокату пункта (чужому прокату, по инструменту —
# пункт снимается); голосовое помечается пунктом только своё; сотрудник с правом «Отвечает в чате» ЭТОГО пункта видит
# переписку пункта и отвечает от имени проката, сотрудник другого пункта, без права, клиент, гость — нет; первым
# написать нельзя; мат не уходит; «прочитано»; карточка «На одобрении» — все поля владельцу, чужому — нет; откат
# из шапки; повторное применение; без SQL 10.106 — понятная ошибка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/prokat-page-stand.py
import ast
import hashlib
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
SO = rd('supabase/migrations/20261029_staff_orders.sql')
ST = rd('supabase/migrations/20261030_staff_tools.sql')
SCHAT = rd('supabase/migrations/20261031_staff_chat.sql')
NO_MAT = rd('supabase/migrations/20261014_reviews_no_mat.sql')
CENSOR = rd('supabase/migrations/20261021_censor.sql')
SQL = rd('supabase/migrations/20261111_prokat_page.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
CHATX = const('tools/staff-chat-stand.py', 'CHATX')
# auth.uid() создают оба куска — оставляем один
CHATX = '\n'.join(l for l in CHATX.splitlines() if not l.startswith(('create function auth.uid()', 'grant execute on function auth.uid()')))
CHATX = CHATX.replace('alter table public.tools add column if not exists img text;\n', '')
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--      '))

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


def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def ins_point(name, city, addr, tel=''):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email, tel) "
            "values ('%s', '%s', '%s', 55.0, 82.9, 'punkt-secret@mail.ru', '%s')") % (name, addr, city, tel)


def save(srv, owner, email, name, points, chat):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    return uval(srv, owner, "select public.iva_staff_save(null, '%s', '%s', '', %s, false, false, %s, false, false)" %
                (email, name, arr, 'true' if chat else 'false'))


def say(srv, frm, to, text, point=None, tool=None, audio=''):
    return as_(srv, frm, "insert into public.app_messages(from_email, to_email, text, audio, point_id, tool_id) values ('%s', '%s', '%s', '%s', %s, %s)"
               % (frm, to, text.replace("'", "''"), audio, "'%s'" % point if point else 'null', "'%s'" % tool if tool else 'null'))


def pchats(srv, email):
    return uval(srv, email, "select coalesce(string_agg(point_name || '|' || client_name || '|' || last_text || '|' || last_mine || '|' || unread, ' ; ' "
                            "order by n), '') from public.iva_staff_point_chats() with ordinality as x(point_id, peer, client_name, point_name, "
                            "last_text, last_at, last_mine, unread, last_client_id, n)")


def pthread(srv, email, point, peer):
    return uval(srv, email, "select coalesce(string_agg(text || '|' || mine || '|' || by_me || '|' || is_read, ' ; ' order by n), '') "
                            "from public.iva_staff_point_chat('%s', '%s') with ordinality as x(id, mine, by_me, text, img, audio, audio_dur, "
                            "created_at, is_read, n)" % (point, peer))


def psend(srv, email, point, peer, text):
    return as_(srv, email, "select public.iva_staff_point_chat_send('%s', '%s', '%s')" % (point, peer, text.replace("'", "''")))


def points(srv, point, role='anon', email=''):
    return uval(srv, email, "select coalesce(string_agg(name || '|' || address || '|' || tel || '|' || company || '|' || avatar || '|' || tools || '|' || main, "
                            "' ; ' order by n), '') from public.iva_prokat_points('%s') with ordinality as x(id, name, address, city, open_min, "
                            "close_min, tel, lat, lng, company, avatar, tools, main, n)" % point, role=role)


ADMX = r"""
alter table public.admins add column if not exists company text;
alter table public.admins add column if not exists org_avatar text;
update public.admins set company = 'Прокат «Ива»', org_avatar = 'https://x/ava/ivа.jpg' where email = 'admin@x.ru';
create table public.iva_models(id bigserial primary key, brand text, model text);
insert into public.iva_models(brand, model) values ('Makita', 'HR2470');
alter table public.tools add column if not exists model_id bigint;
alter table public.tools add column if not exists descr text;
alter table public.tools add column if not exists deposit numeric;
"""

# ── 1. база, применение, повтор ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX + CHATX)
check('исходная база как в бою (инструменты, чат «только своё», профиль клиента)', st == 'OK', out[-600:])
check('SQL 10.77, 10.92, 10.95, 10.99, 10.101 применяются', all(q(srv, s)[0] == 'OK' for s in (RENT, NO_MAT, CENSOR, PICK, TEL, TP)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5', '+7 383 111-22-33'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin@x.ru', ins_point('Пустой', 'Новосибирск', 'Новосибирск, улица Мира, 1'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Пушкина, 3'))
P = {n: val(srv, "select id from public.iva_pickup_points where name = '%s'" % n) for n in ('Склад', 'Бердск', 'Пустой', 'Искитим')}
q(srv, ("insert into public.tools(name, owner_email, price, point_id, active) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}', true), ('Пила', 'admin@x.ru', 500, '{b}', true), "
        "('Скрытая', 'admin@x.ru', 50, '{s}', false), ('Дрель', 'admin2@x.ru', 100, '{i}', true)")
  .format(s=P['Склад'], b=P['Бердск'], i=P['Искитим']))
TOOL = val(srv, "select id from public.tools where name = 'Дрель Makita'")
check('SQL 10.102–10.106 применяются', all(q(srv, s)[0] == 'OK' for s in (OP, STAFF, SO, ST, SCHAT)))
st, out = q(srv, ADMX)
check('у проката — название и аватарка (как SQL 10.98), справочник моделей', st == 'OK', out[-300:])
st, out = q(srv, SQL)
check('20261111_prokat_page.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 6 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 6, out[-600:])

# ── 2. страница проката ──
G = points(srv, P['Склад'])
NS = val(srv, "select count(*) from public.tools where point_id = '%s' and active" % P['Склад'])
check('гость: все пункты проката по порядку, телефон, название и аватарка проката, видимые инструменты, «этот пункт»',
      G == ('Склад|Новосибирск, улица Ленина, 5|+7 383 111-22-33|Прокат «Ива»|https://x/ava/ivа.jpg|%s|true ; '
            'Бердск|Бердск, улица Свердлова, 7||Прокат «Ива»|https://x/ava/ivа.jpg|1|false ; '
            'Пустой|Новосибирск, улица Мира, 1||Прокат «Ива»|https://x/ava/ivа.jpg|0|false') % NS and NS == '2', G)
check('клиент видит то же, что гость', points(srv, P['Склад'], 'authenticated', 'client@x.ru') == G)
check('из другого пункта того же проката — тот же список, отмечен он', (points(srv, P['Бердск']).split(' ; ') + ['', ''])[1].endswith('|true'))
raw = uval(srv, '', "select string_agg(to_jsonb(x)::text, ' ') from public.iva_prokat_points('%s') x" % P['Склад'], role='anon')
check('в ответе нет почты пункта и почты владельца', '@' not in raw, raw[:300])
check('пункт без инструментов (его никто не видел) — пусто', points(srv, P['Пустой']) == '')
check('чужой прокат — только свои пункты, название из ФИО/«Прокат»', points(srv, P['Искитим']).startswith('Искитим|') and 'Склад' not in points(srv, P['Искитим']))
check('неизвестный пункт — пусто', points(srv, str(uuid.uuid4())) == '')
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
check('прокат выключен — пусто', points(srv, P['Склад']) == '')
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")

# ── 3. сообщения с пунктом ──
say(srv, 'client@x.ru', 'admin@x.ru', 'Здравствуйте, вы до скольки?', P['Склад'])
say(srv, 'client@x.ru', 'admin2@x.ru', 'Пункт чужого проката', P['Склад'])
say(srv, 'client@x.ru', 'admin@x.ru', 'По инструменту с пунктом', P['Склад'], TOOL)
say(srv, 'client2@x.ru', 'admin@x.ru', 'Бердск, есть пила?', P['Бердск'])
say(srv, 'client@x.ru', 'admin@x.ru', 'Общий вопрос')
rows = val(srv, "select string_agg(text || '=' || coalesce(point_id::text, '-'), ' ; ' order by id) from public.app_messages")
check('пункт сохраняется только прокату пункта; чужому прокату и по инструменту — снят',
      rows == ('Здравствуйте, вы до скольки?=%s ; Пункт чужого проката=- ; По инструменту с пунктом=- ; Бердск, есть пила?=%s ; Общий вопрос=-'
               % (P['Склад'], P['Бердск'])), rows)
st, out = say(srv, 'client@x.ru', 'admin@x.ru', 'Ты сука', P['Склад'])
check('мат в чат пункта не уходит («Цензура»)', st == 'ERR', out[-200:])
check('клиент читает свою переписку с пунктом (правило «только своё»)',
      uval(srv, 'client@x.ru', "select count(*) from public.app_messages where point_id is not null") == '1')

# голосовое: штатная запись без пункта → iva_tag_my_point
say(srv, 'client@x.ru', 'admin@x.ru', '', audio='https://x/voice/1.m4a')
V = val(srv, "select id from public.app_messages where audio <> '' order by id desc limit 1")
say(srv, 'client2@x.ru', 'admin@x.ru', '', audio='https://x/voice/2.m4a')
V2 = val(srv, "select id from public.app_messages where audio <> '' order by id desc limit 1")
check('своё голосовое помечается пунктом', uval(srv, 'client@x.ru', "select public.iva_tag_my_point(array['%s'], '%s')" % (V, P['Склад'])) == '1')
check('чужое голосовое — нет', uval(srv, 'client@x.ru', "select public.iva_tag_my_point(array['%s'], '%s')" % (V2, P['Склад'])) == '0')
check('голосовое прокату другого пункта — нет', uval(srv, 'client2@x.ru', "select public.iva_tag_my_point(array['%s'], '%s')" % (V2, P['Искитим'])) == '0')
st, out = as_(srv, '', "select public.iva_tag_my_point(array['%s'], '%s')" % (V2, P['Склад']), role='anon')
check('гостю — нет доступа к пометке', st == 'ERR' and 'permission denied' in out, out[-200:])

# ── 4. сотрудники ──
IVAN = save(srv, 'admin@x.ru', 'ivan@x.ru', 'Иван', [P['Склад']], True)
OLGA = save(srv, 'admin@x.ru', 'olga@x.ru', 'Ольга', [P['Бердск']], True)
PETR = save(srv, 'admin@x.ru', 'petr@x.ru', 'Пётр', [P['Склад']], False)
MASHA = save(srv, 'admin@x.ru', 'masha@x.ru', 'Маша', [P['Склад']], True)
for em, sid in (('ivan@x.ru', IVAN), ('olga@x.ru', OLGA), ('petr@x.ru', PETR)):
    uval(srv, em, "select public.iva_staff_answer('%s', true)" % sid)
IV = pchats(srv, 'ivan@x.ru')
check('Иван (чат, Склад) видит переписку своего пункта: Анна, голосовое последнее, 2 непрочитанных',
      IV == 'Склад|Анна|🎤 Голосовое сообщение|false|2', IV)
check('Ольга (чат, Бердск) — только Бердск', pchats(srv, 'olga@x.ru') == 'Бердск|Клиент|Бердск, есть пила?|false|1', pchats(srv, 'olga@x.ru'))
check('без права (Пётр), не принявшая (Маша), клиент, владелец — пусто',
      all(pchats(srv, e) == '' for e in ('petr@x.ru', 'masha@x.ru', 'client@x.ru', 'admin@x.ru')))
raw = uval(srv, 'ivan@x.ru', "select string_agg(to_jsonb(x)::text, ' ') from public.iva_staff_point_chats() x")
check('в ответе нет почты, телефона, фамилии и паспорта клиента', '@' not in raw and '7999' not in raw and 'Клиентова' not in raw
      and md5('client@x.ru') in raw, raw[:300])
st, out = as_(srv, '', 'select * from public.iva_staff_point_chats()', role='anon')
check('гостю — нет доступа', st == 'ERR' and 'permission denied' in out, out[-200:])
st, out = as_(srv, 'ivan@x.ru', 'select * from public.iva_staff_chat_points()')
check('служебная iva_staff_chat_points — не вызвать из приложения', st == 'ERR' and 'permission denied' in out, out[-200:])
check('по инструментам (10.106) переписка пункта не появляется', 'до скольки' not in uval(srv, 'ivan@x.ru', "select string_agg(last_text, ' ') from public.iva_staff_chats()"))

T = pthread(srv, 'ivan@x.ru', P['Склад'], md5('client@x.ru'))
check('переписка пункта по порядку', T == 'Здравствуйте, вы до скольки?|false|false|false ; |false|false|false', T)
check('Ольге переписка Склада недоступна', pthread(srv, 'olga@x.ru', P['Склад'], md5('client@x.ru')) == '')
st, out = psend(srv, 'ivan@x.ru', P['Склад'], md5('client@x.ru'), 'До 20:00, приходите')
check('Иван отвечает', st == 'OK', out[-300:])
last = val(srv, "select from_email || '|' || to_email || '|' || coalesce(point_id::text, '-') || '|' || (staff_id = '%s') from public.app_messages order by id desc limit 1" % IVAN)
check('ответ — от проката клиенту, в переписку пункта, записано кто ответил', last == 'admin@x.ru|client@x.ru|%s|true' % P['Склад'], last)
check('клиент видит ответ в переписке пункта', uval(srv, 'client@x.ru', "select count(*) from public.app_messages where point_id = '%s' and from_email = 'admin@x.ru'" % P['Склад']) == '1')
check('у Ивана — «Вы»', pthread(srv, 'ivan@x.ru', P['Склад'], md5('client@x.ru')).endswith('До 20:00, приходите|true|true|false'))
st, out = psend(srv, 'olga@x.ru', P['Склад'], md5('client@x.ru'), 'Чужой пункт')
check('Ольга не пишет в переписку Склада', st == 'ERR' and 'Переписка не найдена в вашем пункте' in out, out[-300:])
st, out = psend(srv, 'ivan@x.ru', P['Склад'], md5('client3@x.ru'), 'Первым')
check('первым написать нельзя', st == 'ERR' and 'Переписка не найдена в вашем пункте' in out, out[-300:])
st, out = psend(srv, 'petr@x.ru', P['Склад'], md5('client@x.ru'), 'Без права')
check('без права — нельзя', st == 'ERR' and 'Нет права «Отвечает в чате»' in out, out[-300:])
st, out = psend(srv, 'ivan@x.ru', P['Склад'], md5('client@x.ru'), 'Ты сука')
check('мат в ответе не уходит', st == 'ERR', out[-200:])
check('прочитано: 2 сообщения клиента', uval(srv, 'ivan@x.ru', "select public.iva_staff_point_chat_read('%s', '%s')" % (P['Склад'], md5('client@x.ru'))) == '2')
check('владелец видит их прочитанными', val(srv, "select count(*) from public.app_messages where point_id = '%s' and from_email = 'client@x.ru' and not is_read" % P['Склад']) == '0')
q(srv, "update public.iva_staff set can_chat = false where id = '%s'" % IVAN)
check('право сняли — переписка пункта пропала сразу', pchats(srv, 'ivan@x.ru') == '')
q(srv, "update public.iva_staff set can_chat = true where id = '%s'" % IVAN)
uval(srv, 'admin@x.ru', "select public.iva_staff_save('%s', 'ivan@x.ru', 'Иван', '', array['%s']::uuid[], false, false, true, false, false)" % (IVAN, P['Бердск']))
check('перевели в другой пункт — видит только его', pchats(srv, 'ivan@x.ru').startswith('Бердск|'), pchats(srv, 'ivan@x.ru'))

# ── 5. карточка «На одобрении» ──
q(srv, "set session_replication_role = replica; update public.tools set review = 'pending', active = false, model_id = 1, descr = 'Мощная', deposit = 2000, terms = 'Паспорт не нужен', "
       "staff_id = '%s' where id = '%s'" % (OLGA, TOOL))
C = uval(srv, 'admin@x.ru', "select public.iva_tool_pending_card('%s')" % TOOL)
try:
    cj = json.loads(C)
except Exception:
    cj = {}
check('владелец видит все поля: название, цена, залог, описание, условия, пункт, кто добавил, модель',
      cj.get('name') == 'Дрель Makita' and str(cj.get('deposit')) in ('2000', '2000.0') and cj.get('descr') == 'Мощная'
      and cj.get('terms') == 'Паспорт не нужен' and cj.get('point_name') == 'Склад' and cj.get('staff_name') == 'Ольга'
      and cj.get('model_text') == 'Makita HR2470' and 'owner_email' not in cj, json.dumps({k: cj.get(k) for k in ('deposit','point_name','staff_name','owner_email')}, ensure_ascii=False))
st, out = as_(srv, 'admin2@x.ru', "select public.iva_tool_pending_card('%s')" % TOOL)
check('чужой прокат — «Инструмент не найден»', st == 'ERR' and 'Инструмент не найден' in out, out[-200:])
st, out = as_(srv, 'olga@x.ru', "select public.iva_tool_pending_card('%s')" % TOOL)
check('сотрудник — «Только владелец проката»', st == 'ERR' and 'Только владелец проката' in out, out[-200:])
st, out = as_(srv, '', "select public.iva_tool_pending_card('%s')" % TOOL, role='anon')
check('гостю — нет доступа', st == 'ERR' and 'permission denied' in out, out[-200:])

# ── 6. откат и повтор ──
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK', out[-400:])
check('после отката — нет столбца и функций, сообщения на месте',
      val(srv, "select count(*) from information_schema.columns where table_name = 'app_messages' and column_name = 'point_id'") == '0'
      and val(srv, "select count(*) from pg_proc where proname in ('iva_prokat_points', 'iva_staff_point_chats', 'iva_tool_pending_card', 'iva_tag_my_point')") == '0'
      and int(val(srv, 'select count(*) from public.app_messages')) >= 8)
check('после отката клиент пишет как раньше', as_(srv, 'client@x.ru', "insert into public.app_messages(from_email, to_email, text) values ('client@x.ru', 'admin@x.ru', 'Снова общий')")[0] == 'OK')
st, out = q(srv, SQL)
check('снова применяется после отката', st == 'OK' and out.count('✅') == 6, out[-400:])

# ── 7. без SQL 10.106 — понятная остановка ──
srv2 = mk()
q(srv2, BASELINE + EXTRA + PROD + PRODX + CHATX)
for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST):
    q(srv2, s)
st, out = q(srv2, SQL)
check('без 20261031 — понятная ошибка', st == 'ERR' and 'Сначала примените 20261031_staff_chat.sql' in out, out[-300:])

print('\nИТОГ: %s' % ('всё ✔' if fails == 0 else '✘ ошибок: %d' % fails))
sys.exit(1 if fails else 0)

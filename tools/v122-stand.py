# Стенд 10.122: 20261113_v122.sql (и откат 20261113_v122_rollback.sql) на настоящем PostgreSQL (pgserver).
#
# База как в рабочей: всё до 10.121 (пункты, сотрудники, 20261104–20261112, чужой trg_tool_owner).
# Проверяет: право «Удаляет инструмент» (список, сотруднику, сохранение старым приложением не сбрасывает); удаление
# сотрудником без права — нельзя, с правом — только запрос владельцу (инструмент остаётся), «Оставить» / «Удалить»;
# владелец удаляет сразу; инструмент целиком для правки (владельцу — опубликованный, сотруднику — со своей правкой,
# чужим — нет); перенос в другой пункт; чат: кто открыл / печатает / «Отвечу я», кто ответил последним, чужим и клиенту
# — ничего; заявки: отмена клиентом (причина, только свой аккаунт, пока не выдано, без комиссии), прокат перевести в
# «отменена» не может, вопрос «взяли?» через 2 дня («Да» — комиссия), журнал и доля отмен создателю; изменение
# заявки — ждёт прокат, «Принять» / «Отклонить», после выдачи — нельзя; откат и повторное применение.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/v122-stand.py
import ast
import json
import os
import re
import subprocess
import sys
import tempfile
import time
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


M = 'supabase/migrations/'
FILES = ['20261014_rent_end_mode.sql', '20261014_reviews_no_mat.sql', '20261021_censor.sql', '20261023_pickup_points.sql',
         '20261025_pickup_tel.sql', '20261026_tool_points.sql', '20261027_order_points.sql', '20261028_staff.sql',
         '20261029_staff_orders.sql', '20261030_staff_tools.sql', '20261031_staff_chat.sql', '20261102_admin_manage.sql',
         '20261103_role_ping.sql', '20261104_point_staff_publish.sql', '20261105_staff_pick_point.sql',
         '20261106_staff_live.sql', '20261107_staff_publish_check.sql', '20261108_staff_publish_probe.sql']
V121 = rd(M + '20261112_v121.sql')
SQL = rd(M + '20261113_v122.sql')
RBF = rd(M + '20261113_v122_rollback.sql')
FINAL = rd(M + '20261109_staff_owner_final.sql')
PAGE = rd(M + '20261111_prokat_page.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
ADM = const('tools/admin-manage-stand.py', 'ADM')
CHATX = const('tools/staff-chat-stand.py', 'CHATX')
CHATX = '\n'.join(l for l in CHATX.splitlines() if not l.startswith(('create function auth.uid()', 'grant execute on function auth.uid()')))
CHATX = CHATX.replace('alter table public.tools add column if not exists img text;\n', '')
FOREIGN = const('tools/staff-owner-stand.py', 'FOREIGN')
PUB = 'create publication supabase_realtime;'
ADMX = r"""
alter table public.admins add column if not exists org_avatar text;
alter table public.admins add column if not exists org_tel text;
alter table public.admins add column if not exists phone text;
alter table public.admins add column if not exists address text;
alter table public.admins add column if not exists work_hours text;
alter table public.tools add column if not exists deposit numeric;
alter table public.tools add column if not exists terms text;
alter table public.tools add column if not exists imgs text[];
alter table public.tools add column if not exists model_id bigint;
update public.admins set company = 'Прокат «Ива»', org_tel = '+7 900 000-11-22', phone = '+7 999 999-99-99' where email = 'admin@x.ru';
insert into public.admins(email, role, active, company) values ('admin7@x.ru', 'admin', true, 'Новый прокат');
"""

PSQL = str(pgserver.postgres_server.POSTGRES_BIN_PATH / 'psql')
fails = 0


def check(name, cond, detail=''):
    global fails
    if cond:
        print('✔ ' + name)
    else:
        fails += 1
        print('✘ ' + name + (' — ' + str(detail)[:900] if detail else ''))


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


def ins_point(name, addr, mode='approve', tel=''):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email, staff_publish, tel) "
            "values ('%s', '%s', 'Новосибирск', 55.0, 82.9, 'prokat@mail.ru', '%s', '%s')") % (name, addr, mode, tel)


def pid(srv, name):
    return val(srv, "select id from public.iva_pickup_points where name = '%s'" % name)


def save(srv, owner, email, name, points, issue, catalog):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(null, '%s', '%s', '', %s, %s, %s, false, false, false)" %
                (email, name, arr, b(issue), b(catalog)))


def ping(srv, email):
    return val(srv, "select coalesce((select extract(epoch from changed_at)::text from public.iva_role_ping where email = '%s'), '')" % email)


def tool(srv, tid):
    return val(srv, "select name || '|' || price || '|' || active || '|' || coalesce(review, '') || '|' || coalesce(edit_pending::text, '') "
                    "|| '|' || edit_rejected from public.tools where id = '%s'" % tid)


def qs(srv, sql):
    st, out = q(srv, sql)
    return st, re.sub(r'[ \t]*\|[ \t]*', ' | ', out)


def ensure_tel_points(srv):
    return uval(srv, '', "select coalesce(string_agg(name || '=' || tel, ' ; ' order by n), '') from public.iva_prokat_points('%s') "
                         "with ordinality as x(id, name, address, city, open_min, close_min, tel, lat, lng, company, avatar, tools, main, n)"
                % pid(srv, 'Склад'), role='anon')


# ── 1. база, применение, повтор ──
srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX + CHATX + PUB)
check('исходная база как в бою', st == 'OK', out[-600:])
bad = [f for f in FILES if q(srv, rd(M + f))[0] != 'OK']
check('SQL 10.77 … 10.118 применяются', not bad, bad)
st, out = q(srv, ADM + ADMX)
check('админы как в бою (название, телефоны, ещё один прокат без пунктов)', st == 'OK', out[-400:])
check('чужой trg_tool_owner и 20261109, 20261111 применяются', all(q(srv, s)[0] == 'OK' for s in (FOREIGN, FINAL, PAGE)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5', 'approve', '+7 383 111-22-33'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск, улица Свердлова, 7', 'now'))
as_(srv, 'admin@x.ru', ins_point('Пустой', 'Новосибирск, улица Мира, 1'))
SK, BK, PU = pid(srv, 'Склад'), pid(srv, 'Бердск'), pid(srv, 'Пустой')
PETR = save(srv, 'admin@x.ru', 'petr@x.ru', 'Пётр', [SK, BK], False, True)
IVAN = save(srv, 'admin@x.ru', 'ivan@x.ru', 'Иван', [SK], True, False)
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN)
as_(srv, 'admin@x.ru', ("insert into public.tools(name, owner_email, price, point_id, active, cat, descr, deposit, img, imgs) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}', true, 'drill', 'Мощная', 2000, 'https://x/d.jpg', null), "
        "('Пила', 'admin@x.ru', 500, '{b}', true, 'saw', '', null, '', array['https://x/p1.jpg']), "
        "('Шлифмашина', 'admin@x.ru', 250, '{p}', true, 'drill', '', 1000, '', null)").format(s=SK, b=BK, p=PU))
DRILL = val(srv, "select id from public.tools where name = 'Дрель Makita'")
SAW = val(srv, "select id from public.tools where name = 'Пила'")
GRIND = val(srv, "select id from public.tools where name = 'Шлифмашина'")
import hashlib

st, out = qs(srv, V121)
check('20261112_v121.sql (10.121) применяется', st == 'OK' and out.count('✅') == 5, out[-600:])
st, out = qs(srv, SQL)
check('20261113_v122.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все 5 строк «✅»', st == 'OK' and out.count('✅') == 5 and '❌' not in out, out[-900:])
st, out = qs(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 5, out[-600:])
check('порядок триггеров удаления/правки: правка и удаление сотрудника — до владельца',
      val(srv, "select string_agg(tgname, ',' order by tgname) from pg_trigger where tgrelid = 'public.tools'::regclass and not tgisinternal "
               "and tgname in ('zy_iva_staff_delete', 'zy_iva_staff_edit', 'zz_iva_staff_owner')") == 'zy_iva_staff_delete,zy_iva_staff_edit,zz_iva_staff_owner')


def save11(owner, sid, email, name, points, issue, catalog, chat, delete):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'null' if x is None else ('true' if x else 'false')
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, %s, false, false, %s)" %
                ("'%s'" % sid if sid else 'null', email, name, arr, b(issue), b(catalog), b(chat), b(delete)))


def cnt(sql):
    return val(srv, "select count(*) from (%s) x" % sql)


# ── 2. право «Удаляет инструмент» ──
check('по умолчанию у сотрудника права удаления нет', val(srv, "select can_delete from public.iva_staff where id = '%s'" % PETR) == 'f')
save11('admin@x.ru', PETR, 'petr@x.ru', 'Пётр', [SK, BK], False, True, False, True)
check('владелец включил «Удаляет инструмент» — в списке сотрудников', uval(srv, 'admin@x.ru',
      "select can_delete from public.iva_staff_list() where email = 'petr@x.ru'") == 't')
check('сотрудник видит своё право', uval(srv, 'petr@x.ru', "select can_delete from public.iva_staff_me()") == 't')
uval(srv, 'admin@x.ru', "select public.iva_staff_save('%s', 'petr@x.ru', 'Пётр', '', array['%s','%s']::uuid[], false, true, false, false, false)" % (PETR, SK, BK))
check('сохранение старым приложением (10 полей) право не сбрасывает', val(srv, "select can_delete from public.iva_staff where id = '%s'" % PETR) == 't')
save11('admin@x.ru', IVAN, 'ivan@x.ru', 'Иван', [SK], True, False, False, True)
check('без «Выкладывает инструмент» право удаления не ставится', val(srv, "select can_delete from public.iva_staff where id = '%s'" % IVAN) == 'f')
st, out = as_(srv, 'petr@x.ru', "select * from public.iva_staff_list()")
check('сотруднику список сотрудников — пусто', 'can_delete' in out and '(0 rows)' in out, out[-300:])

# ── 3. удаление сотрудником — через одобрение ──
as_(srv, 'admin@x.ru', "insert into public.tools(name, owner_email, price, point_id, active, cat) values "
                       "('Удаляемый', 'admin@x.ru', 100, '%s', true, 'drill'), ('Удаляемый 2', 'admin@x.ru', 100, '%s', true, 'drill')" % (SK, SK))
DEL1 = val(srv, "select id from public.tools where name = 'Удаляемый'")
DEL2 = val(srv, "select id from public.tools where name = 'Удаляемый 2'")
save11('admin@x.ru', PETR, 'petr@x.ru', 'Пётр', [SK, BK], False, True, False, False)
st, out = as_(srv, 'petr@x.ru', "delete from public.tools where id = '%s'" % DEL1)
check('без права: штатное «Удалить» — понятная ошибка, инструмент на месте', st == 'ERR' and 'не разрешил' in out
      and cnt("select 1 from public.tools where id = '%s'" % DEL1) == '1', out[-300:])
st, out = as_(srv, 'petr@x.ru', "select public.iva_staff_tool_delete('%s')" % DEL1)
check('без права: «Удалить» приложения — та же ошибка', st == 'ERR' and 'не разрешил' in out, out[-300:])
save11('admin@x.ru', PETR, 'petr@x.ru', 'Пётр', [SK, BK], False, True, False, True)
before = ping(srv, 'admin@x.ru')
time.sleep(0.02)
st, out = as_(srv, 'petr@x.ru', "delete from public.tools where id = '%s'" % DEL1)
check('с правом: штатное «Удалить» — инструмент остаётся, запрос владельцу', st == 'OK'
      and cnt("select 1 from public.tools where id = '%s'" % DEL1) == '1'
      and cnt("select 1 from public.iva_tool_deletes where tool_id = '%s' and staff_id = '%s'" % (DEL1, PETR)) == '1', out[-300:])
check('владельцу — отметка (приложение обновит «На одобрении»)', ping(srv, 'admin@x.ru') != before)
r = uval(srv, 'petr@x.ru', "select public.iva_staff_tool_delete('%s')" % DEL2)
check('«Удалить» приложения — «pending», инструмент на месте', r == 'pending' and cnt("select 1 from public.tools where id = '%s'" % DEL2) == '1', r)
P = uval(srv, 'admin@x.ru', "select string_agg(name || ':' || del || ':' || edit || ':' || staff_name, ',' order by name) from public.iva_tools_pending() where del")
check('«На одобрении»: две строки «удалить» с именем сотрудника', P == 'Удаляемый:true:false:Пётр,Удаляемый 2:true:false:Пётр', P)
check('сотрудник видит «удаление на одобрении»', uval(srv, 'petr@x.ru', "select j ->> 'edit_state' from public.iva_staff_tools() j where j ->> 'id' = '%s'" % DEL1) == 'delete')
check('клиенты пока видят инструмент', uval(srv, '', "select count(*) from public.tools where id = '%s'" % DEL1, role='anon') == '1')
st, out = as_(srv, 'petr@x.ru', "select public.iva_tool_delete_review('%s', true)" % DEL1)
check('сотрудник сам одобрить не может', st == 'ERR' and 'Только владелец' in out, out[-200:])
st, out = as_(srv, 'admin2@x.ru', "select public.iva_tool_delete_review('%s', true)" % DEL1)
check('чужой прокат — нельзя', st == 'ERR', out[-200:])
r = uval(srv, 'admin@x.ru', "select public.iva_tool_delete_review('%s', false)" % DEL1)
check('«Оставить» — инструмент остаётся, запроса нет', r == 'kept' and cnt("select 1 from public.tools where id = '%s'" % DEL1) == '1'
      and cnt("select 1 from public.iva_tool_deletes where tool_id = '%s'" % DEL1) == '0', r)
r = uval(srv, 'admin@x.ru', "select public.iva_tool_delete_review('%s', true)" % DEL2)
check('«Удалить» — инструмента нет', r == 'deleted' and cnt("select 1 from public.tools where id = '%s'" % DEL2) == '0', r)
st, out = as_(srv, 'admin@x.ru', "select public.iva_tool_delete_review('%s', true)" % DEL2)
check('решённый запрос — «уже не ждёт одобрения»', st == 'ERR' and 'уже не ждёт' in out, out[-200:])
st, out = as_(srv, 'ivan@x.ru', "delete from public.tools where id = '%s'" % DEL1)
check('сотрудник без «Выкладывает инструмент» — ничего не удаляется и запроса нет',
      cnt("select 1 from public.tools where id = '%s'" % DEL1) == '1' and cnt("select 1 from public.iva_tool_deletes") == '0', out[-200:])
st, out = as_(srv, 'admin@x.ru', "delete from public.tools where id = '%s'" % DEL1)
check('владелец удаляет сразу', st == 'OK' and cnt("select 1 from public.tools where id = '%s'" % DEL1) == '0', out[-200:])

# ── 4. инструмент целиком для правки, перенос ──
E = json.loads(uval(srv, 'admin@x.ru', "select public.iva_tool_edit_card('%s')::text" % DRILL) or '{}')
check('владельцу: все поля (описание, залог, фото, пункт), без служебных', E.get('descr') == 'Мощная' and E.get('deposit') == 2000
      and E.get('img') == 'https://x/d.jpg' and E.get('point_name') == 'Склад' and 'owner_email' not in E and 'edit_pending' not in E
      and E.get('delete_pending') is False, E)
as_(srv, 'petr@x.ru', "update public.tools set price = 333, descr = 'Мощная, кейс' where id = '%s'" % DRILL)
E = json.loads(uval(srv, 'petr@x.ru', "select public.iva_tool_edit_card('%s')::text" % DRILL) or '{}')
E2 = json.loads(uval(srv, 'admin@x.ru', "select public.iva_tool_edit_card('%s')::text" % DRILL) or '{}')
check('сотруднику — со своей ждущей правкой; владельцу — опубликованный', E.get('price') == 333 and E.get('descr') == 'Мощная, кейс'
      and E2.get('price') == 300 and E2.get('descr') == 'Мощная', (E, E2))
uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', false)" % DRILL)
for who in ('ivan@x.ru', 'client@x.ru', 'admin2@x.ru'):
    st, out = as_(srv, who, "select public.iva_tool_edit_card('%s')" % DRILL)
    check('%s — «Инструмент не найден»' % who, st == 'ERR' and 'не найден' in out, out[-200:])
r = uval(srv, 'admin@x.ru', "select public.iva_tool_move('%s', '%s')" % (GRIND, SK))
check('перенос в другой пункт: пункт и адрес инструмента', r == 'Склад' and val(srv, "select point_id = '%s' from public.tools where id = '%s'" % (SK, GRIND)) == 't', r)
as_(srv, 'admin2@x.ru', ins_point('Чужой', 'Новосибирск, улица Чужая, 2'))
st, out = as_(srv, 'admin@x.ru', "select public.iva_tool_move('%s', '%s')" % (GRIND, pid(srv, 'Чужой')))
check('в чужой пункт — нельзя', st == 'ERR' and 'не найден у вашего' in out, out[-200:])
st, out = as_(srv, 'petr@x.ru', "select public.iva_tool_move('%s', '%s')" % (GRIND, BK))
check('сотруднику переносить — нельзя', st == 'ERR' and 'владелец' in out, out[-200:])

# ── 5. чат: кто отвечает ──
MASHA = save11('admin@x.ru', None, 'masha@x.ru', 'Маша', [SK], False, False, True, False)
uval(srv, 'masha@x.ru', "select public.iva_staff_answer('%s', true)" % MASHA)
TH = 't:%s:%s' % (DRILL, hashlib.md5(b'client@x.ru').hexdigest())
PTH = 'p:%s:%s' % (SK, hashlib.md5(b'client@x.ru').hexdigest())
uval(srv, 'masha@x.ru', "select public.iva_chat_mark('%s', 'open')" % TH)
W = json.loads(uval(srv, 'admin@x.ru', "select public.iva_chat_who('%s')::text" % TH) or '{}')
check('владелец видит: Маша открыла переписку', [(o['name'], o['role'], o['open'], o['typing'], o['claim']) for o in W.get('others', [])]
      == [('Маша', 'staff', True, False, False)] and W.get('last') is None, W)
uval(srv, 'masha@x.ru', "select public.iva_chat_mark('%s', 'typing')" % TH)
uval(srv, 'admin@x.ru', "select public.iva_chat_mark('%s', 'claim')" % TH)
W = json.loads(uval(srv, 'admin@x.ru', "select public.iva_chat_who('%s')::text" % TH) or '{}')
W2 = json.loads(uval(srv, 'masha@x.ru', "select public.iva_chat_who('%s')::text" % TH) or '{}')
check('владелец видит «печатает»; Маша видит «Отвечает Владелец»', W['others'][0]['typing'] is True
      and [(o['name'], o['claim']) for o in W2['others']] == [('Владелец', True)], (W, W2))
q(srv, "insert into public.app_messages(from_email, to_email, text, tool_id, staff_id, created_at) values "
       "('admin@x.ru', 'client@x.ru', 'Здравствуйте', '%s', '%s', now() - interval '1 minute'), "
       "('client@x.ru', 'admin@x.ru', 'Спасибо', '%s', null, now())" % (DRILL, MASHA, DRILL))
W = json.loads(uval(srv, 'admin@x.ru', "select public.iva_chat_who('%s')::text" % TH) or '{}')
check('«Ответила Маша» (ответ клиента не считается)', (W.get('last') or {}).get('name') == 'Маша' and W['last']['me'] is False, W)
W2 = json.loads(uval(srv, 'masha@x.ru', "select public.iva_chat_who('%s')::text" % TH) or '{}')
check('Маше — «ответили вы»', (W2.get('last') or {}).get('me') is True, W2)
uval(srv, 'admin@x.ru', "select public.iva_chat_mark('%s', 'close')" % TH)
W2 = json.loads(uval(srv, 'masha@x.ru', "select public.iva_chat_who('%s')::text" % TH) or '{}')
check('владелец вышел — у Маши подсказки «отвечает» нет', W2.get('others') == [], W2)
check('переписка пункта — тоже работает', uval(srv, 'masha@x.ru', "select public.iva_chat_mark('%s', 'open')" % PTH) == ''
      and json.loads(uval(srv, 'admin@x.ru', "select public.iva_chat_who('%s')::text" % PTH) or '{}').get('others', [{}])[0].get('name') == 'Маша')
for who in ('client@x.ru', 'petr@x.ru', 'admin2@x.ru'):
    check('%s — ничего не видит и не отмечает' % who, uval(srv, who, "select coalesce(public.iva_chat_who('%s')::text, 'null')" % TH) == 'null')
uval(srv, 'client@x.ru', "select public.iva_chat_mark('%s', 'claim')" % TH)
check('клиентская отметка не записалась; неверная переписка — молча', cnt("select 1 from public.iva_chat_watch where email = 'client@x.ru'") == '0'
      and uval(srv, 'admin@x.ru', "select coalesce(public.iva_chat_who('x:1:2')::text, 'null')") == 'null')
st, out = as_(srv, 'admin@x.ru', "select * from public.iva_chat_watch")
check('таблица отметок напрямую закрыта', st == 'ERR' and 'permission denied' in out, out[-200:])


# ── 6. отмена заявки клиентом ──
def order(items, who='client@x.ru'):
    return uval(srv, '', "select public.iva_submit_order('Иван Клиентов', '+79990000000', 'x', 'Доставка', 'ул. Мира, 1', "
                         "'Позвоните', '%s', 3, '%s'::jsonb)" % (who, json.dumps(items)), role='anon')


as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where id = '%s'" % (PU, GRIND))
O1 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 2}])
check('заявка: две части (Склад, Пустой)', cnt("select 1 from public.order_parts where order_id = %s and status = 'new'" % O1) == '2', O1)
st, out = as_(srv, 'client@x.ru', "select public.iva_order_cancel(%s, '')" % O1)
check('без причины — нельзя', st == 'ERR' and 'причину' in out, out[-200:])
st, out = as_(srv, 'other@x.ru', "select public.iva_order_cancel(%s, 'Передумал')" % O1)
check('чужой клиент — «Заявка не найдена»', st == 'ERR' and 'не найдена' in out, out[-200:])
st, out = as_(srv, 'admin@x.ru', "select public.iva_order_cancel(%s, 'Передумал')" % O1)
check('прокат отменить за клиента не может', st == 'ERR' and 'не найдена' in out, out[-200:])
P1 = val(srv, "select min(id) from public.order_parts where order_id = %s" % O1)
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set status = 'cancelled' where id = %s" % P1)
check('прокат перевести часть в «отменена» не может (лазейки нет)', st == 'ERR' and 'Недопустимый перевод' in out
      and val(srv, "select status from public.order_parts where id = %s" % P1) == 'new', out[-300:])
st, out = as_(srv, 'client@x.ru', "select set_config('iva.part_cancel', '1', true); update public.order_parts set status = 'cancelled' where id = %s" % P1)
check('клиент напрямую (мимо функции) — тоже нельзя', val(srv, "select status from public.order_parts where id = %s" % P1) == 'new', out[-300:])
before = ping(srv, 'admin@x.ru')
time.sleep(0.02)
r = uval(srv, 'client@x.ru', "select public.iva_order_cancel(%s, '  Нашёл   ближе ')" % O1)
check('клиент отменил: обе части «cancelled», комиссии нет', r == '2'
      and val(srv, "select string_agg(status || ':' || coalesce(fee, 0), ',') from public.order_parts where order_id = %s" % O1) == 'cancelled:0,cancelled:0', r)
check('журнал: две отмены с причиной, вопрос — через 2 дня; владельцу отметка',
      val(srv, "select count(*) || ':' || min(reason) || ':' || bool_and(ask_after > now() + interval '47 hours') from public.iva_order_changes where order_id = %s and kind = 'cancel'" % O1)
      == '2:Нашёл ближе:true' and ping(srv, 'admin@x.ru') != before)
st, out = as_(srv, 'client@x.ru', "select public.iva_order_cancel(%s, 'Ещё раз')" % O1)
check('повторно — «уже отменена»', st == 'ERR' and 'уже отменена' in out, out[-200:])
C = [json.loads(x) for x in uval(srv, 'client@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('client') j").split(' @@@ ') if x]
c1 = [c for c in C if c['order_id'] == O1]
check('клиенту в заявке: статус и причина отмены', c1 and c1[0]['parts'][0]['status'] == 'cancelled'
      and c1[0]['parts'][0]['cancel']['reason'] == 'Нашёл ближе', c1)
st, out = as_(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % P1)
check('сотрудник «Выдал» по отменённой — нельзя', st == 'ERR' and val(srv, "select status from public.order_parts where id = %s" % P1) == 'cancelled', out[-200:])
O2 = order([{'id': DRILL, 'days': 1}, {'id': GRIND, 'days': 1}])
P2SK = val(srv, "select id from public.order_parts where order_id = %s and point_id = '%s'" % (O2, SK))
uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % P2SK)
st, out = as_(srv, 'client@x.ru', "select public.iva_order_cancel(%s, 'Передумал')" % O2)
check('часть уже выдана — отменить нельзя', st == 'ERR' and 'уже выдан' in out
      and cnt("select 1 from public.order_parts where order_id = %s and status = 'cancelled'" % O2) == '0', out[-200:])

# вопрос «взяли?»
check('сразу после отмены вопроса нет', uval(srv, 'client@x.ru', "select count(*) from public.iva_order_followups()") == '0')
q(srv, "update public.iva_order_changes set ask_after = now() - interval '1 minute' where order_id = %s" % O1)
F = uval(srv, 'client@x.ru', "select string_agg(id || '|' || company || '|' || tools_text, ',' order by id) from public.iva_order_followups()")
check('через 2 дня: два вопроса с названием проката и инструментом', F.count('|Прокат «Ива»|') == 2 and 'Дрель Makita' in F, F)
check('чужому клиенту — вопросов нет', uval(srv, 'other@x.ru', "select count(*) from public.iva_order_followups()") == '0')
FIDS = val(srv, "select string_agg(id::text, ',' order by part_id) from public.iva_order_changes where order_id = %s" % O1).split(',')
check('«Спросить позже» — убрано до завтра', uval(srv, 'client@x.ru', "select public.iva_order_followup_answer(%s, 'later')" % FIDS[1]) == 'later'
      and uval(srv, 'client@x.ru', "select count(*) from public.iva_order_followups()") == '1')
check('чужой ответить за клиента не может', uval(srv, 'admin@x.ru', "select public.iva_order_followup_answer(%s, 'not')" % FIDS[0]) == 'gone')
r = uval(srv, 'client@x.ru', "select public.iva_order_followup_answer(%s, 'took')" % FIDS[0])
check('«Да, взял» — часть закрыта, комиссия начислена (300 × 3 × 5%)', r == 'took'
      and val(srv, "select status || ':' || fee from public.order_parts where id = %s" % P1) == 'closed:45'
      and val(srv, "select answer || ':' || fee_after from public.iva_order_changes where id = %s" % FIDS[0]) == 'took:45', r)
check('ответ — один раз', uval(srv, 'client@x.ru', "select public.iva_order_followup_answer(%s, 'not')" % FIDS[0]) == 'gone')

# создателю: журнал и доля отмен
S = uval(srv, 'chief@x.ru', "select string_agg(company || ':' || parts || ':' || cancelled || ':' || took, ',') from public.iva_cancel_stats()")
check('создателю: доля отмен по прокату (части, отмены, «взяли»)', S.startswith('Прокат «Ива»:') and ':2:1' in S, S)
J = uval(srv, 'chief@x.ru', "select count(*) || ':' || max(reason) || ':' || string_agg(answer, ',' order by id) from public.iva_cancel_journal()")
check('создателю: журнал отмен с причиной и ответом', J == '2:Нашёл ближе:took,', J)
check('владельцу проката журнал и доля — нет', uval(srv, 'admin@x.ru', "select count(*) from public.iva_cancel_journal()") == '0'
      and uval(srv, 'admin@x.ru', "select count(*) from public.iva_cancel_stats()") == '0')

# ── 7. изменение заявки клиентом ──
O3 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 2}])
as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where id = '%s'" % (SK, GRIND))
O4 = order([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 2}])
P4 = val(srv, "select id from public.order_parts where order_id = %s" % O4)
check('заявка: одна часть с двумя инструментами', cnt("select 1 from public.order_parts where order_id = %s" % O4) == '1', O4)
ED = "select public.iva_order_edit(%s, '%s'::jsonb, %s, %s)"
st, out = as_(srv, 'other@x.ru', ED % (P4, json.dumps([{'id': DRILL, 'days': 5}]), 'null', 'false'))
check('чужой клиент — «Заявка не найдена»', st == 'ERR' and 'не найдена' in out, out[-200:])
st, out = as_(srv, 'client@x.ru', ED % (P4, json.dumps([{'id': SAW, 'days': 5}]), 'null', 'false'))
check('чужой инструмент добавить нельзя', st == 'ERR' and 'нет такого' in out, out[-200:])
st, out = as_(srv, 'client@x.ru', ED % (P4, '[]', 'null', 'false'))
check('убрать все — «отмените заявку»', st == 'ERR' and 'отмените' in out, out[-200:])
st, out = as_(srv, 'client@x.ru', ED % (P4, json.dumps([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 2}]), 'null', 'false'))
check('ничего не изменилось — так и сказано', st == 'ERR' and 'Ничего не изменилось' in out, out[-200:])
st, out = as_(srv, 'client@x.ru', ED % (P4, json.dumps([{'id': DRILL, 'days': 400}]), 'null', 'false'))
check('срок больше 365 — нельзя', st == 'ERR' and '365' in out, out[-200:])
CH = uval(srv, 'client@x.ru', ED % (P4, json.dumps([{'id': DRILL, 'days': 5}]), "'Привезу паспорт'", 'true'))
check('изменение отправлено — ждёт прокат, заявка пока прежняя', CH.isdigit()
      and val(srv, "select jsonb_array_length(items) || ':' || tools_text from public.order_parts where id = %s" % P4) == '2:Дрель Makita, Шлифмашина', CH)
A = [json.loads(x) for x in uval(srv, 'admin@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('admin') j").split(' @@@ ') if x]
a4 = [a for a in A if a['order_id'] == O4]
chg = a4[0]['parts'][0].get('change') if a4 else None
check('владелец видит прежнее и новое', chg and chg['state'] == 'pending' and len(chg['before']['items']) == 2
      and chg['after']['items'] == [{'id': DRILL, 'name': 'Дрель Makita', 'price': 300, 'days': 5}]
      and chg['after']['comment'] == 'Привезу паспорт' and chg['after']['get_method'] == 'Самовывоз', chg)
check('в списке «ждут решения» — у владельца и у сотрудника «Выдача» пункта', uval(srv, 'admin@x.ru', "select count(*) from public.iva_order_changes_pending()") == '1'
      and uval(srv, 'ivan@x.ru', "select count(*) from public.iva_order_changes_pending()") == '1'
      and uval(srv, 'petr@x.ru', "select count(*) from public.iva_order_changes_pending()") == '0')
st, out = as_(srv, 'petr@x.ru', "select public.iva_order_edit_decide(%s, true)" % CH)
check('сотрудник без «Выдача» решить не может', st == 'ERR' and 'не найдено' in out, out[-200:])
st, out = as_(srv, 'client@x.ru', "select public.iva_order_edit_decide(%s, true)" % CH)
check('клиент сам принять не может', st == 'ERR', out[-200:])
r = uval(srv, 'ivan@x.ru', "select public.iva_order_edit_decide(%s, true)" % CH)
check('«Принять» (сотрудник пункта): инструменты, срок, комментарий, самовывоз', r == 'accepted'
      and val(srv, "select (items -> 0 ->> 'days') || ':' || jsonb_array_length(items) || '|' || tools_text from public.order_parts where id = %s" % P4) == '5:1|Дрель Makita'
      and val(srv, "select comment || '|' || get_method from public.orders where id = %s" % O4) == 'Привезу паспорт|Самовывоз', r)
C = [json.loads(x) for x in uval(srv, 'client@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('client') j").split(' @@@ ') if x]
c4 = [c for c in C if c['order_id'] == O4]
check('клиент видит «принято» и новый срок', c4 and c4[0]['parts'][0]['change']['state'] == 'accepted' and c4[0]['parts'][0]['items'][0]['days'] == 5, c4)
CH2 = uval(srv, 'client@x.ru', ED % (P4, json.dumps([{'id': DRILL, 'days': 1}]), 'null', 'false'))
r = uval(srv, 'admin@x.ru', "select public.iva_order_edit_decide(%s, false)" % CH2)
check('«Отклонить» — заявка прежняя', r == 'rejected' and val(srv, "select items -> 0 ->> 'days' from public.order_parts where id = %s" % P4) == '5', r)
CH3 = uval(srv, 'client@x.ru', ED % (P4, json.dumps([{'id': DRILL, 'days': 2}]), 'null', 'false'))
uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % P4)
r = uval(srv, 'admin@x.ru', "select public.iva_order_edit_decide(%s, true)" % CH3)
check('выдали раньше, чем решили, — изменение не применяется', r == 'issued'
      and val(srv, "select state from public.iva_order_changes where id = %s" % CH3) == 'void'
      and val(srv, "select items -> 0 ->> 'days' from public.order_parts where id = %s" % P4) == '5', r)
st, out = as_(srv, 'client@x.ru', ED % (P4, json.dumps([{'id': DRILL, 'days': 9}]), 'null', 'false'))
check('после выдачи изменить нельзя', st == 'ERR' and 'уже выдан' in out, out[-200:])
uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'closed')" % P4)
check('выдал → вернули: комиссия по новому сроку (300 × 5 × 5% = 75)', val(srv, "select fee from public.order_parts where id = %s" % P4) == '75')
st, out = as_(srv, 'client@x.ru', "select * from public.iva_order_changes")
check('журнал изменений напрямую закрыт', st == 'ERR' and 'permission denied' in out, out[-200:])
st, out = as_(srv, '', "select public.iva_order_cancel(1, 'x')", role='anon')
check('гостю — нельзя', st == 'ERR' and 'permission denied' in out, out[-200:])

# ── 8. откат ──
st, out = qs(srv, RBF)
check('откат 20261113_v122_rollback.sql — без ошибок, «✅»', st == 'OK' and out.count('✅') == 2 and '❌' not in out, out[-600:])
st, out = as_(srv, 'petr@x.ru', "delete from public.tools where id = '%s'" % GRIND)
check('после отката: удаление сотрудником — как в 10.121 (сразу)', cnt("select 1 from public.tools where id = '%s'" % GRIND) == '0', out[-300:])
check('после отката: сохранение сотрудника (10 полей) работает', len(save(srv, 'admin@x.ru', 'kolya@x.ru', 'Коля', [SK], True, False)) == 36)
check('после отката: отменённые остаются отменёнными, журнал на месте',
      val(srv, "select count(*) from public.order_parts where status = 'cancelled'") == '1' and cnt("select 1 from public.iva_order_changes") != '0')
st, out = as_(srv, 'admin@x.ru', "update public.order_parts set status = 'closed' where id = (select id from public.order_parts where status = 'cancelled')")
check('после отката: из «отменена» никуда не перевести', st == 'ERR' and 'Недопустимый' in out, out[-200:])
st, out = qs(srv, SQL)
check('после отката файл применяется снова', st == 'OK' and out.count('✅') == 5, out[-400:])
check('право удаления сохранилось', val(srv, "select can_delete from public.iva_staff where id = '%s'" % PETR) == 't')

print('\nИтого: %s' % ('все проверки прошли' if fails == 0 else 'не прошло: %d' % fails))
sys.exit(1 if fails else 0)

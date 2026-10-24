# Стенд 10.121: 20261112_v121.sql (и откат 20261112_v121_rollback.sql) на настоящем PostgreSQL (pgserver).
#
# База как в рабочей: всё до 10.120 (пункты, инструменты и заявки сотрудников, 20261104–20261111, чужой trg_tool_owner).
# Проверяет: первый пункт из «Данных проката» — только если пунктов нет и один раз, клиенту нельзя; телефон пункта —
# свой, иначе номер проката, пока в пункте нет сотрудника; правка сотрудника в пункте «после одобрения» не меняет
# инструмент (клиенты видят прежний), ждёт владельца, у него в «На одобрении» и отметка; сотрудник видит свою правку и
# её состояние; «Опубликовать» применяет (и одно поле), «Отклонить» убирает; показать/скрыть — сразу; режим «сразу» и
# владелец — сразу; та же форма ещё раз — не второе объявление; заявки целиком: клиенту свои без своих ФИО и телефона,
# владельцу — с клиентом и комиссией, сотруднику пункта — с клиентом без комиссии, чужим и гостю — нет; таймер — время
# выдачи и правило срока; откат и повторное применение.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/v121-stand.py
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
SQL = rd(M + '20261112_v121.sql')
RBF = rd(M + '20261112_v121_rollback.sql')
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
st, out = qs(srv, SQL)
check('20261112_v121.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все 5 строк «✅»', st == 'OK' and out.count('✅') == 5 and '❌' not in out, out[-900:])
st, out = qs(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 5, out[-600:])
check('порядок триггеров: пункт → правка сотрудника → владелец последним',
      val(srv, "select string_agg(tgname, ',' order by tgname) from pg_trigger where tgrelid = 'public.tools'::regclass and not tgisinternal "
               "and tgname in ('trg_iva_tool_point', 'zy_iva_staff_edit', 'zz_iva_staff_owner')") == 'trg_iva_tool_point,zy_iva_staff_edit,zz_iva_staff_owner')

# ── 2. первый пункт из «Данных проката» ──
FIRST = "select coalesce(public.iva_pickup_first('%s', 'Бердск', 54.75, 83.1, null, null)::text, 'null')"
r1 = uval(srv, 'admin7@x.ru', FIRST % 'Бердск, улица Ленина, 10')
check('прокат без пунктов: создан первый пункт', len(r1) == 36, r1)
check('пункт: название проката, адрес, город, координаты, график по умолчанию, владелец',
      val(srv, "select name || '|' || address || '|' || city || '|' || lat || '|' || owner_email || '|' || open_min[1] || '-' || close_min[1] "
               "from public.iva_pickup_points where id = '%s'" % r1) == 'Новый прокат|Бердск, улица Ленина, 10|Бердск|54.75|admin7@x.ru|540-1140')
check('второй раз — не создаёт', uval(srv, 'admin7@x.ru', FIRST % 'Бердск, улица Мира, 3') == 'null'
      and val(srv, "select count(*) from public.iva_pickup_points where owner_email = 'admin7@x.ru'") == '1')
as_(srv, 'admin7@x.ru', "delete from public.iva_pickup_points where owner_email = 'admin7@x.ru'")
check('удалил все пункты и сохранил данные снова — не создаёт (один раз)', uval(srv, 'admin7@x.ru', FIRST % 'Бердск, улица Мира, 3') == 'null'
      and val(srv, "select count(*) from public.iva_pickup_points where owner_email = 'admin7@x.ru'") == '0')
check('прокат с пунктами — не создаёт и отмечает', uval(srv, 'admin@x.ru', FIRST % 'Новосибирск, улица Мира, 9') == 'null'
      and val(srv, "select count(*) from public.iva_pickup_points where owner_email = 'admin@x.ru'") == '3'
      and val(srv, "select first_point_at is not null from public.admins where email = 'admin@x.ru'") == 't')
q(srv, "insert into public.admins(email, role, active, company) values ('admin4@x.ru', 'admin', true, null)")
st, out = as_(srv, 'admin4@x.ru', "select public.iva_pickup_first('Дом', '', null, null, null, null)")
check('короткий адрес — понятная ошибка', st == 'ERR' and 'от 5 до 300' in out, out[-300:])
r4 = uval(srv, 'admin4@x.ru', "select public.iva_pickup_first('Искитим, улица Пушкина, 3', '', 200, 500, array[600,600,600,600,600,-1,-1], array[1200,1200,1200,1200,1200,-1,-1])")
check('без названия проката — «Пункт выдачи»; неверные координаты — без них; свой график',
      val(srv, "select name || '|' || coalesce(lat::text, '-') || '|' || open_min[6] || '|' || close_min[1] from public.iva_pickup_points where id = '%s'" % r4)
      == 'Пункт выдачи|-|-1|1200', r4)
st, out = as_(srv, 'client@x.ru', FIRST % 'Новосибирск, улица Мира, 9')
check('клиенту — нельзя', st == 'ERR' and 'Только владелец' in out, out[-300:])
st, out = as_(srv, '', FIRST % 'Новосибирск, улица Мира, 9', role='anon')
check('гостю — нельзя', st == 'ERR' and 'permission denied' in out, out[-300:])

# ── 3. телефон пункта ──
T = ensure_tel_points(srv)
check('свой номер — его; без номера, но с сотрудником — пусто; без сотрудника — номер проката (org_tel)',
      T == 'Склад=+7 383 111-22-33 ; Бердск= ; Пустой=+7 900 000-11-22', T)
q(srv, "update public.admins set org_tel = null where email = 'admin@x.ru'")
check('нет номера в «Данных проката» — телефон из «Моих данных»', ensure_tel_points(srv).endswith('Пустой=+7 999 999-99-99'), ensure_tel_points(srv))
q(srv, "update public.admins set org_tel = '+7 900 000-11-22' where email = 'admin@x.ru'")
save(srv, 'admin@x.ru', 'olga@x.ru', 'Ольга', [PU], True, False)
check('приглашённый (ещё не принял) — номер проката остаётся', ensure_tel_points(srv).endswith('Пустой=+7 900 000-11-22'))
OLGA = val(srv, "select id from public.iva_staff where email = 'olga@x.ru'")
uval(srv, 'olga@x.ru', "select public.iva_staff_answer('%s', true)" % OLGA)
check('сотрудник принял — номер проката больше не подставляется', ensure_tel_points(srv).endswith('Пустой='), ensure_tel_points(srv))

# ── 4. правки сотрудника на одобрение ──
before = ping(srv, 'admin@x.ru')
time.sleep(0.02)
st, out = as_(srv, 'petr@x.ru', "update public.tools set name = 'Дрель Makita HP', price = 350, descr = 'Мощная, 2 акк.', active = true where id = '%s'" % DRILL)
check('сотрудник сохранил правку (без ошибки)', st == 'OK', out[-300:])
row = tool(srv, DRILL)
check('инструмент не изменился — клиенты видят прежний', row.startswith('Дрель Makita|300|true||'), row)
ep = json.loads(val(srv, "select edit_pending::text from public.tools where id = '%s'" % DRILL) or '{}')
check('правка ждёт владельца: название, цена, описание', ep == {'name': 'Дрель Makita HP', 'price': 350, 'descr': 'Мощная, 2 акк.'}, ep)
check('у владельца сразу отметка «на одобрение»', ping(srv, 'admin@x.ru') != before)
PEND = uval(srv, 'admin@x.ru', "select string_agg(name || '|' || price || '|' || edit, ' ; ') from public.iva_tools_pending()")
check('«На одобрении» владельца: правка с новым названием и ценой', PEND == 'Дрель Makita HP|350|true', PEND)
ST_ = uval(srv, 'petr@x.ru', "select string_agg((j ->> 'name') || '|' || (j ->> 'price') || '|' || (j ->> 'edit_state') || '|' || (j ? 'edit_pending'), ' ; ') "
                             "from public.iva_staff_tools() j where j ->> 'id' = '%s'" % DRILL)
check('сотрудник видит свою правку и «на одобрении» (служебных полей нет)', ST_ == 'Дрель Makita HP|350|pending|false', ST_)
CARD = json.loads(uval(srv, 'admin@x.ru', "select public.iva_tool_pending_card('%s')::text" % DRILL) or '{}')
check('карточка правки: новый вариант, что изменилось и как было', CARD.get('edit') is True and CARD.get('name') == 'Дрель Makita HP'
      and CARD.get('changed') == ['descr', 'name', 'price'] and CARD.get('old', {}).get('price') == 300 and 'edit_pending' not in CARD, CARD)
as_(srv, 'petr@x.ru', "update public.tools set name = 'Дрель Makita', price = 360, descr = 'Мощная' where id = '%s'" % DRILL)
ep = json.loads(val(srv, "select edit_pending::text from public.tools where id = '%s'" % DRILL) or '{}')
check('вторая правка формой — заменяет первую (вернул название — его нет в правке)', ep == {'price': 360}, ep)
as_(srv, 'petr@x.ru', "update public.tools set model_id = 7 where id = '%s'" % DRILL)
ep = json.loads(val(srv, "select edit_pending::text from public.tools where id = '%s'" % DRILL) or '{}')
check('дописанное после формы (модель) — добавляется к правке', ep == {'price': 360, 'model_id': 7}, ep)
as_(srv, 'petr@x.ru', "update public.tools set active = false where id = '%s'" % DRILL)
check('скрыть — сразу, правка остаётся', tool(srv, DRILL).startswith('Дрель Makita|300|false||{') , tool(srv, DRILL))
as_(srv, 'petr@x.ru', "update public.tools set active = true where id = '%s'" % DRILL)
r = uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', true)" % DRILL)
check('владелец «Опубликовать» — правка применена, ждущей нет', r == 'published'
      and val(srv, "select price || '|' || model_id || '|' || active || '|' || (edit_pending is null) from public.tools where id = '%s'" % DRILL) == '360|7|true|true',
      (r, val(srv, "select price || '|' || coalesce(model_id::text, '-') || '|' || active || '|' || (edit_pending is null) from public.tools where id = '%s'" % DRILL)))
as_(srv, 'petr@x.ru', "update public.tools set price = 999 where id = '%s'" % DRILL)
r = uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', true)" % DRILL)
check('одно поле — тоже применяется', r == 'published' and tool(srv, DRILL).startswith('Дрель Makita|999|true||'), tool(srv, DRILL))
as_(srv, 'petr@x.ru', "update public.tools set price = 1 where id = '%s'" % DRILL)
r = uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', false)" % DRILL)
check('«Отклонить» — правка убрана, инструмент прежний, сотруднику «отклонена»', r == 'rejected'
      and tool(srv, DRILL) == 'Дрель Makita|999|true|||true'
      and uval(srv, 'petr@x.ru', "select j ->> 'edit_state' from public.iva_staff_tools() j where j ->> 'id' = '%s'" % DRILL) == 'rejected', tool(srv, DRILL))
st, out = as_(srv, 'admin@x.ru', "select public.iva_tool_review('%s', true)" % DRILL)
check('решённая правка — «уже не ждёт одобрения»', st == 'ERR' and 'уже не ждёт' in out, out[-200:])
as_(srv, 'petr@x.ru', "update public.tools set price = 555 where id = '%s'" % SAW)
check('пункт «Публиковать сразу» — правка сразу', tool(srv, SAW).startswith('Пила|555|true|||'), tool(srv, SAW))
as_(srv, 'admin@x.ru', "update public.tools set price = 400 where id = '%s'" % DRILL)
check('владелец правит — сразу', tool(srv, DRILL).startswith('Дрель Makita|400|true|||'), tool(srv, DRILL))
st, out = as_(srv, 'admin2@x.ru', "select public.iva_tool_review('%s', true)" % DRILL)
check('чужой прокат одобрить не может', st == 'ERR', out[-200:])

# новый инструмент сотрудника — как в 10.105; та же форма ещё раз — не второе объявление
add = ("insert into public.tools(name, active, cat, owner_email, price, descr, point_id) values ('Перфоратор', true, 'drill', 'petr@x.ru', 700, 'SDS', '%s')" % SK)
st1, o1 = as_(srv, 'petr@x.ru', add)
NEWT = val(srv, "select id from public.tools where name = 'Перфоратор'")
check('новый инструмент сотрудника — на одобрение, как раньше', st1 == 'OK' and tool(srv, NEWT).startswith('Перфоратор|700|false|pending|'), (o1[-300:], tool(srv, NEWT)))
st2, o2 = as_(srv, 'petr@x.ru', add)
check('та же форма ещё раз — «Такой инструмент уже есть…», второго объявления нет', st2 == 'ERR' and 'уже есть' in o2
      and val(srv, "select count(*) from public.tools where name = 'Перфоратор'") == '1', o2[-300:])
as_(srv, 'petr@x.ru', "update public.tools set price = 750 where id = '%s'" % NEWT)
check('правка ждущего нового — сразу (он ещё не виден клиентам)', tool(srv, NEWT).startswith('Перфоратор|750|false|pending||'), tool(srv, NEWT))
check('другой инструмент — публикуется', as_(srv, 'petr@x.ru', add.replace('Перфоратор', 'Перфоратор 2'))[0] == 'OK')

# ── 5. заявки целиком ──
its = json.dumps([{'id': DRILL, 'days': 3}, {'id': GRIND, 'days': 2}])
OID = uval(srv, '', "select public.iva_submit_order('Иван Клиентов', '+79990000000', 'Дрель Makita (3 дня), Шлифмашина (2 дня)', 'Самовывоз', '', "
                    "'Позвоните заранее', 'client@x.ru', 3, '%s'::jsonb)" % its, role='anon')
check('заявка из приложения (два пункта)', OID.isdigit() and val(srv, "select count(*) from public.order_parts where order_id = %s" % OID) == '2', OID)
C = [json.loads(x) for x in uval(srv, 'client@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('client') j").split(' @@@ ') if x]
c0 = C[0] if C else {}
parts = c0.get('parts', [])
check('клиенту: заявка, две части, инструменты с ценой, сроком, фото и залогом', len(C) == 1 and len(parts) == 2
      and parts[0]['items'][0]['name'] == 'Дрель Makita' and parts[0]['items'][0]['days'] == 3 and parts[0]['items'][0]['img'] == 'https://x/d.jpg'
      and parts[0]['items'][0]['deposit'] == 2000 and parts[0]['point']['address'] == 'Новосибирск, улица Ленина, 5', c0)
check('клиенту: телефон пункта, название проката, правило срока; нет ФИО, телефона клиента и комиссии',
      parts and parts[0]['point']['tel'] == '+7 383 111-22-33' and parts[0]['company'] == 'Прокат «Ива»' and parts[0]['rent_mode'] == 'day'
      and 'client_name' not in c0 and 'client_tel' not in c0 and parts[0].get('fee') is None and 'Иван' not in json.dumps(c0, ensure_ascii=False), c0)
check('чужому клиенту — пусто', uval(srv, 'other@x.ru', "select count(*) from public.iva_order_cards('client')") == '0')
st, out = as_(srv, '', "select public.iva_order_cards('client')", role='anon')
check('гостю — нельзя', st == 'ERR' and 'permission denied' in out)
A = [json.loads(x) for x in uval(srv, 'admin@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('admin') j").split(' @@@ ') if x]
check('владельцу: каждая часть — с ФИО и телефоном клиента и комиссией, без почты клиента', len(A) == 2
      and all(a['client_name'] == 'Иван Клиентов' and a['client_tel'] == '+79990000000' and 'fee' in a['parts'][0] and a['parts'][0]['fee'] is not None for a in A)
      and 'client@x.ru' not in json.dumps(A), A)
check('клиенту чужие части — как владельцу нельзя', uval(srv, 'client@x.ru', "select count(*) from public.iva_order_cards('admin')") == '0')
S = [json.loads(x) for x in uval(srv, 'ivan@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('staff') j").split(' @@@ ') if x]
check('сотруднику «Выдача» — часть только своего пункта, с клиентом, без комиссии', len(S) == 1 and S[0]['parts'][0]['point']['name'] == 'Склад'
      and S[0]['client_name'] == 'Иван Клиентов' and S[0]['parts'][0].get('fee') is None and 'client@x.ru' not in json.dumps(S), S)
check('сотруднику только «Каталог» — заявок нет', uval(srv, 'petr@x.ru', "select count(*) from public.iva_order_cards('staff')") == '0')
PSK = val(srv, "select p.id from public.order_parts p where p.order_id = %s and p.point_id = '%s'" % (OID, SK))
uval(srv, 'ivan@x.ru', "select public.iva_staff_set_status(%s, 'rented')" % PSK)
S = [json.loads(x) for x in uval(srv, 'ivan@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('staff') j").split(' @@@ ') if x]
check('«Выдал» — время выдачи и график пункта для таймера', S and S[0]['parts'][0]['status'] == 'rented' and S[0]['parts'][0]['rented_at']
      and len(S[0]['parts'][0]['close_min']) == 7 and S[0]['parts'][0]['tz'], S)
OLD = uval(srv, '', "select public.iva_submit_order('Пётр', '+7', 'Дрель Makita', 'Доставка', 'ул. Мира, 1', '', 'client@x.ru', 1, null)", role='anon')
C = [json.loads(x) for x in uval(srv, 'client@x.ru', "select string_agg(j::text, ' @@@ ') from public.iva_order_cards('client') j").split(' @@@ ') if x]
check('заявка без списка (как с сайта): своя доставка видна, инструменты — текстом', len(C) == 2 and C[0]['address'] == 'ул. Мира, 1'
      and C[0]['parts'] and C[0]['parts'][0]['items'] == [] and 'Дрель' in C[0]['parts'][0]['tools_text'], C[0] if C else C)

# ── 6. откат ──
st, out = q(srv, RBF)
check('откат 20261112_v121_rollback.sql — без ошибок, «✅»', st == 'OK' and '✅ откат 10.121 выполнен' in out, out[-600:])
check('после отката: заявок целиком и триггера правок нет, «На одобрении» прежняя',
      val(srv, "select to_regprocedure('public.iva_order_cards(text)') is null and not exists (select 1 from pg_trigger where tgname = 'zy_iva_staff_edit') "
               "and not exists (select 1 from information_schema.routines r join information_schema.parameters p on p.specific_name = r.specific_name "
               "where r.routine_name = 'iva_tools_pending' and p.parameter_name = 'edit')") == 't')
T = ensure_tel_points(srv)
check('после отката: страница проката — телефон только пункта', T == 'Склад=+7 383 111-22-33 ; Бердск= ; Пустой=', T)
st, out = as_(srv, 'petr@x.ru', "update public.tools set price = 42 where id = '%s'" % DRILL)
check('после отката: правка сотрудника — сразу (как 10.120)', tool(srv, DRILL).startswith('Дрель Makita|42|true|'), (tool(srv, DRILL), out[-500:]))
st, out = qs(srv, SQL)
check('после отката файл применяется снова', st == 'OK' and out.count('✅') == 5, out[-400:])

print('\nИтого: %s' % ('все проверки прошли' if fails == 0 else 'не прошло: %d' % fails))
sys.exit(1 if fails else 0)

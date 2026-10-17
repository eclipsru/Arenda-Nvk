# Стенд 10.118 «Сотрудник не может опубликовать: чужой триггер trg_tool_owner подменяет владельца»:
# 20261109_staff_owner_final.sql на настоящем PostgreSQL.
#
# Стенд А — база как в стенде 10.118 + триггер trg_tool_owner, как в рабочей базе (ставит владельцем почту того, кто
# пишет; на запись и правку). Проверяет: без файла сотрудник не публикует (как на снимке владельца, проба называет
# trg_tool_owner); файл применяется и повторяется; после него сотрудник публикует — объявление проката владельца,
# «на одобрение» / «сразу» по пункту, правит своё; владелец, админ и клиент — как раньше; чужое в чужой прокат
# не пишется; таблица в конце — порядок триггеров, текст fn_tool_owner, проба ✅; откат из шапки.
# Стенд Б — без trg_tool_owner: файл ничего не меняет (как 10.116). Стенд В — без 20261108: понятная остановка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-owner-stand.py
import ast
import json
import re
import os
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


RENT = rd('supabase/migrations/20261014_rent_end_mode.sql')
PICK = rd('supabase/migrations/20261023_pickup_points.sql')
TEL = rd('supabase/migrations/20261025_pickup_tel.sql')
TP = rd('supabase/migrations/20261026_tool_points.sql')
OP = rd('supabase/migrations/20261027_order_points.sql')
STAFF = rd('supabase/migrations/20261028_staff.sql')
SO = rd('supabase/migrations/20261029_staff_orders.sql')
ST = rd('supabase/migrations/20261030_staff_tools.sql')
MANAGE = rd('supabase/migrations/20261102_admin_manage.sql')
PING = rd('supabase/migrations/20261103_role_ping.sql')
PUBL = rd('supabase/migrations/20261104_point_staff_publish.sql')
PICKF = rd('supabase/migrations/20261105_staff_pick_point.sql')
LIVE = rd('supabase/migrations/20261106_staff_live.sql')
CHK = rd('supabase/migrations/20261107_staff_publish_check.sql')
PROBE = rd('supabase/migrations/20261108_staff_publish_probe.sql')
SQL = rd('supabase/migrations/20261109_staff_owner_final.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
ADM = const('tools/admin-manage-stand.py', 'ADM')
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--      '))
PUB = 'create publication supabase_realtime;'

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


def code(srv, email):
    return val(srv, "select public.iva_staff_publish_check('%s') ->> 'code'" % email)


def why(srv, email):
    return uval(srv, email, "select public.iva_staff_publish_why() ->> 'code'")


def ping(srv, email):
    return val(srv, "select coalesce((select extract(epoch from changed_at)::text from public.iva_role_ping where email = '%s'), '')" % email)


def moved(srv, email, action):
    before = ping(srv, email)
    time.sleep(0.02)
    r = action()
    return ping(srv, email) != before, r


def save(srv, owner, sid, email, name, points, issue, catalog):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, false, false, false)" %
                ('null' if sid is None else "'%s'" % sid, email, name, arr, b(issue), b(catalog)))


def ins_point(name, addr, mode='approve'):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email, staff_publish) "
            "values ('%s', '%s', 'Новосибирск', 55.0, 82.9, 'prokat@mail.ru', '%s')") % (name, addr, mode)


def pid(srv, name):
    return val(srv, "select id from public.iva_pickup_points where name = '%s'" % name)


def add(srv, email, name, point=None):
    """Как штатная форма: POST tools с owner_email = вошедший, active = true; пункт — из поля «Пункт выдачи»."""
    return as_(srv, email, "insert into public.tools(name, active, cat, owner_email, price%s) values ('%s', true, 'drill', '%s', 400%s)"
               % (', point_id' if point else '', name, email, ", '%s'" % point if point else ''))


def tool(srv, name):
    return val(srv, ("select coalesce(t.owner_email, '') || '|' || coalesce(pp.name, '') || '|' || coalesce(t.active::text, '') || '|' "
                     "|| coalesce(t.review, '') from public.tools t left join public.iva_pickup_points pp on pp.id = t.point_id "
                     "where t.name = '%s'") % name)



def qs(srv):
    """Файл целиком; таблица в конце — без выравнивания psql («имя | результат»)."""
    st, out = q(srv, SQL)
    return st, re.sub(r'[ \t]*\|[ \t]*', ' | ', out)


def tcode(srv, email):
    return uval(srv, email, "select public.iva_staff_publish_try() ->> 'code'")


def ttext(srv, email):
    return uval(srv, email, "select (public.iva_staff_publish_try() ->> 'text') || ' | ' || (public.iva_staff_publish_try() ->> 'raw')")


def ntools(srv):
    return val(srv, 'select count(*) from public.tools')



def tcode(srv, email):
    return uval(srv, email, "select public.iva_staff_publish_try() ->> 'code'")


def ttext(srv, email):
    return uval(srv, email, "select (public.iva_staff_publish_try() ->> 'text') || ' | ' || (public.iva_staff_publish_try() ->> 'raw')")


def qs(srv):
    st, out = q(srv, SQL)
    return st, re.sub(r'[ \t]*\|[ \t]*', ' | ', out)


FOREIGN = r"""
create function public.fn_tool_owner() returns trigger language plpgsql security definer set search_path = public as $$
begin new.owner_email := public.jwt_email(); return new; end $$;
create trigger trg_tool_owner before insert or update on public.tools for each row execute function public.fn_tool_owner();
"""


def base(srv, foreign=True):
    st, out = q(srv, BASELINE + EXTRA + PROD + PRODX + PUB)
    ok = st == 'OK' and all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PING, PUBL, PICKF, LIVE, CHK, PROBE))
    if foreign:
        ok = ok and q(srv, FOREIGN)[0] == 'OK'
    return ok


def people(srv):
    as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5'))
    as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск, улица Свердлова, 7', 'now'))
    sk, bk = pid(srv, 'Склад'), pid(srv, 'Бердск')
    p = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [sk, bk], False, True)
    i = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [sk], True, False)
    uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % p)
    uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % i)
    return sk, bk, p, i


# ═══════════════ Стенд А: как в рабочей базе (есть trg_tool_owner) ═══════════════
srv = mk()
check('база как в рабочей: все файлы сотрудников, 20261107/08 и чужой trg_tool_owner', base(srv))
SKLAD, BERDSK, PETR, IVAN = people(srv)
st, out = add(srv, 'petr@x.ru', 'До исправления')
check('без файла: сотрудник не публикует — «row-level security … tools» (как на снимке)', st == 'ERR' and 'row-level security' in out
      and '"tools"' in out, out[-300:])
check('и проба называет именно trg_tool_owner, «подставилась ваша почта»', tcode(srv, 'petr@x.ru') == 'policy'
      and 'trg_tool_owner' in ttext(srv, 'petr@x.ru') and 'подставилась ваша почта' in ttext(srv, 'petr@x.ru'), ttext(srv, 'petr@x.ru'))
check('владелец при этом публикует (как в жизни)', add(srv, 'admin@x.ru', 'Своя до')[0] == 'OK' and tool(srv, 'Своя до') == 'admin@x.ru|Склад|true|')

st, out = qs(srv)
check('20261109_staff_owner_final.sql применяется без ошибок', st == 'OK', out[-900:])
check('таблица: новый триггер последний (до пробы), trg_tool_owner оставлен, текст fn_tool_owner, проба Петра ✅',
      'zz_iva_staff_owner | ✅ новый' in out and 'trg_tool_owner | ⚠️ не из «Ивы» (оставлен): fn_tool_owner' in out
      and 'после всех остальных (кроме пробы) | ✅' in out and 'jwt_email()' in out
      and 'пробная запись: сотрудник petr@x.ru | ✅ проходит' in out and 'ivan@x.ru' not in out
      and out.index('trg_tool_owner |') < out.index('zz_iva_staff_owner |') < out.index('zzz_iva_probe |'), out[-1800:])
st, out = qs(srv)
check('повторный запуск — без ошибок', st == 'OK' and '❌' not in out, out[-600:])
check('trg_tool_owner не удалён', val(srv, "select count(*) from pg_trigger where tgname = 'trg_tool_owner'") == '1')

mv, (st, out) = moved(srv, 'admin@x.ru', lambda: add(srv, 'petr@x.ru', 'Дрель склад'))
check('сотрудник публикует: объявление проката владельца, «Склад», на одобрение', st == 'OK'
      and tool(srv, 'Дрель склад') == 'admin@x.ru|Склад|false|pending', (tool(srv, 'Дрель склад'), out[-300:]))
check('и у владельца сразу отметка «на одобрение» (Realtime)', mv)
check('«добавил» — Пётр', val(srv, "select staff_id::text from public.tools where name = 'Дрель склад'") == PETR)
check('в «На одобрении» владельца — есть', 'Дрель склад' in uval(srv, 'admin@x.ru', 'select public.iva_tools_pending()::text'))
uval(srv, 'petr@x.ru', "select public.iva_staff_pick_point('%s')" % BERDSK)
st, out = add(srv, 'petr@x.ru', 'Пила бердск')
check('пункт из формы «Бердск» (режим «сразу») — сразу виден', st == 'OK' and tool(srv, 'Пила бердск') == 'admin@x.ru|Бердск|true|',
      (tool(srv, 'Пила бердск'), out[-300:]))
st, out = as_(srv, 'petr@x.ru', "update public.tools set price = 555 where name = 'Пила бердск'")
check('сотрудник правит своё: владелец прежний (trg_tool_owner на правке не перебивает)', st == 'OK'
      and val(srv, "select owner_email || '|' || price from public.tools where name = 'Пила бердск'") == 'admin@x.ru|555', out[-300:])
TID = val(srv, "select id from public.tools where name = 'Дрель склад'")
uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', true)" % TID)
check('владелец одобрил — виден клиентам', tool(srv, 'Дрель склад') == 'admin@x.ru|Склад|true|')
check('владелец публикует своё — как раньше', add(srv, 'admin@x.ru', 'Своя после')[0] == 'OK' and tool(srv, 'Своя после') == 'admin@x.ru|Склад|true|')
st, out = add(srv, 'ivan@x.ru', 'Иван без права')
check('сотрудник без права «Выкладывает» — по-прежнему нет', st == 'ERR' and 'row-level security' in out, out[-300:])
st, out = add(srv, 'client@x.ru', 'Клиент')
check('клиент — по-прежнему нет', st == 'ERR' and 'row-level security' in out, out[-300:])
st, out = as_(srv, 'petr@x.ru', "insert into public.tools(name, active, cat, owner_email, price, point_id) values ('Чужой', true, 'drill', 'other@x.ru', 1, null)")
check('сотрудник не запишет в чужой прокат: владелец всё равно его прокат', st == 'OK'
      and val(srv, "select owner_email from public.tools where name = 'Чужой'") == 'admin@x.ru', out[-300:])
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
st, out = add(srv, 'petr@x.ru', 'При выключенном')
check('прокат владельца приостановлен — сотрудник не публикует', st == 'ERR', out[-300:])
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")
check('проба Петра ✅', tcode(srv, 'petr@x.ru') == 'ok', ttext(srv, 'petr@x.ru'))

# откат
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK' and 'DROP TRIGGER' in RB and 'DROP FUNCTION' in RB, out[-600:])
check('после отката — снова как на снимке (сотрудник не публикует)', add(srv, 'petr@x.ru', 'После отката')[0] == 'ERR'
      and val(srv, "select count(*) from pg_trigger where tgname = 'zz_iva_staff_owner'") == '0')
st, out = qs(srv)
check('повторное применение после отката — сотрудник снова публикует', st == 'OK' and add(srv, 'petr@x.ru', 'Снова')[0] == 'OK', out[-600:])
srv.cleanup()

# ═══════════════ Стенд Б: без trg_tool_owner ═══════════════
srv = mk()
check('база без чужого триггера', base(srv, foreign=False))
SKLAD, BERDSK, PETR, IVAN = people(srv)
st, out = qs(srv)
check('файл применяется; чужих триггеров нет, текста fn_tool_owner нет', st == 'OK' and 'не из «Ивы»' not in out
      and 'fn_tool_owner' not in out and 'petr@x.ru | ✅' in out, out[-1200:])
check('сотрудник публикует, как в 10.116', add(srv, 'petr@x.ru', 'Дрель')[0] == 'OK' and tool(srv, 'Дрель') == 'admin@x.ru|Склад|false|pending')
check('владелец и админ — как раньше', add(srv, 'admin@x.ru', 'Своя')[0] == 'OK' and tool(srv, 'Своя') == 'admin@x.ru|Склад|true|')
srv.cleanup()

# ═══════════════ Стенд В: без 20261108 ═══════════════
srv = mk()
q(srv, BASELINE + EXTRA + PROD + PRODX + PUB)
for s_ in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PING, PUBL, PICKF, LIVE, CHK):
    q(srv, s_)
st, out = q(srv, SQL)
check('без SQL 20261108 — понятная остановка', st == 'ERR' and '20261108_staff_publish_probe.sql' in out, out[-400:])
check('и ничего не создано', val(srv, "select count(*) from pg_trigger where tgname = 'zz_iva_staff_owner'") == '0')
srv.cleanup()

print('Итог: %d ошибок' % fails)
sys.exit(1 if fails else 0)

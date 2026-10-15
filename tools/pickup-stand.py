# Стенд «Пункта выдачи» (10.95): 20261023_pickup_points.sql на настоящем PostgreSQL (pgserver из PyPI).
#
# База как в бою (роли, admins, fn_is_chief — общая BASELINE стендов), заказы для SQL 10.77, затем
# 20261014_rent_end_mode.sql («Срок аренды») и 20261023 (дважды — повторный запуск).
# Проверяет: пункты видит/меняет только свой действующий админ, создатель видит все, клиент и без входа — ничего;
# проверки названия, адреса, email, графика (выходной -1/-1, открытие < закрытия, хотя бы один рабочий день),
# координат; не больше 20 пунктов; владелец и порядок не меняются; время закрытия «Срока аренды» = график
# первого пункта (добавление, изменение, удаление первого, удаление всех); iva_rent_settings_set берёт
# время из первого пункта, режим — присланный; без SQL 10.77 файл тоже применяется; откат из шапки.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/pickup-stand.py
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


RENT = rd('supabase/migrations/20261014_rent_end_mode.sql')
SQL = rd('supabase/migrations/20261023_pickup_points.sql')
_src = rd('tools/category-tree-stand.py')
BASELINE = None
for node in ast.parse(_src).body:
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') == 'BASELINE':
        BASELINE = ast.literal_eval(node.value)

# сверх общей базы: заказы (для SQL 10.77), ещё один админ, выключенный админ
EXTRA = """
create table public.orders(id uuid primary key default gen_random_uuid(), days text, user_email text);
create table public.order_parts(id uuid primary key default gen_random_uuid(), order_id uuid references public.orders(id),
  tools_text text, status text, owner_email text, archived boolean default false);
insert into public.admins values ('admin2@x.ru', 'admin', true), ('off@x.ru', 'admin', false);
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
    return ';'.join(x for x in lines if x not in ('BEGIN', 'SET', 'COMMIT', 'INSERT 0 1', 'UPDATE 1', 'DELETE 1'))


def claims(email):
    c = {'role': 'authenticated'}
    if email:
        c['email'] = email
    return json.dumps(c).replace("'", "''")


def as_(srv, email, sql, role='authenticated'):
    st, out = q(srv, "begin; set local role %s; set local request.jwt.claims = '%s'; %s; commit;" % (role, claims(email), sql))
    if out.rstrip().endswith('ROLLBACK') or 'ERROR' in out:
        st = 'ERR'
    return st, out


def uval(srv, email, sql, role='authenticated'):
    return val(srv, "begin; set local role %s; set local request.jwt.claims = '%s';\n%s;\ncommit;" % (role, claims(email), sql))


H5 = "'{540,540,540,540,540,600,-1}','{1140,1140,1140,1140,1140,960,-1}'"


def ins(name, close="'{1140,1140,1140,1140,1140,960,-1}'", open_="'{540,540,540,540,540,600,-1}'", email="'Prokat@Mail.ru'", addr="'Новосибирск, улица Ленина, д. 5'", extra=''):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, open_min, close_min, email%s) "
            "values ('%s', %s, 'Новосибирск', 55.03, 82.92, %s, %s, %s%s)") % (
        ', owner_email' if extra else '', name, addr, open_, close, email, (', ' + extra) if extra else '')


# ── 1. полная база: 10.77 + 10.95 ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA)
check('исходная база как в бою (admins, fn_is_chief, заказы)', st == 'OK', out[-600:])
st, out = q(srv, RENT)
check('20261014_rent_end_mode.sql (10.77) применяется', st == 'OK', out[-600:])
st, out = q(srv, SQL)
check('20261023_pickup_points.sql применяется без ошибок', st == 'OK', out[-900:])
st, out = q(srv, SQL)
check('повторный запуск 20261023 — без ошибок', st == 'OK', out[-900:])

# «Срок аренды» до пунктов: админ сам выбрал «До закрытия» и своё время
st, out = as_(srv, 'admin@x.ru', "select public.iva_rent_settings_set('close', '{1200,1200,1200,1200,1200,1200,1200}', 'Asia/Novosibirsk')")
check('до пунктов «Срок аренды» сохраняет присланное время', st == 'OK' and uval(srv, 'admin@x.ru', "select close_min::text from public.iva_rent_settings_get()") == '{1200,1200,1200,1200,1200,1200,1200}', out[-300:])

# права
check('админ добавляет пункт', as_(srv, 'Admin@X.ru', ins('Склад на Ленина'))[0] == 'OK')
r = uval(srv, 'admin@x.ru', "select owner_email||'|'||email||'|'||name from public.iva_pickup_points")
check('владелец — сам админ, email проката — маленькими буквами', r == 'admin@x.ru|prokat@mail.ru|Склад на Ленина', r)
r = uval(srv, 'admin@x.ru', "select mode||'|'||close_min::text||'|'||tz from public.iva_rent_settings_get()")
check('первый пункт задал время закрытия «Срока аренды» (режим и пояс — прежние)', r == 'close|{1140,1140,1140,1140,1140,960,-1}|Asia/Novosibirsk', r)
check('клиент не добавляет', as_(srv, 'client@x.ru', ins('Мой'))[0] == 'ERR')
check('выключенный админ не добавляет', as_(srv, 'off@x.ru', ins('Мой'))[0] == 'ERR')
check('без входа не добавляет', as_(srv, '', ins('Мой'), role='anon')[0] == 'ERR')
check('админ не добавляет пункт другому админу', as_(srv, 'admin@x.ru', ins('Чужой', extra="'admin2@x.ru'"))[0] == 'ERR')
check('админ2 добавляет свой', as_(srv, 'admin2@x.ru', ins('Второй прокат'))[0] == 'OK')
check('админ видит только свои', uval(srv, 'admin@x.ru', "select count(*) from public.iva_pickup_points") == '1')
check('создатель видит все', uval(srv, 'chief@x.ru', "select count(*) from public.iva_pickup_points") == '2')
check('клиент не видит ничего', uval(srv, 'client@x.ru', "select count(*) from public.iva_pickup_points") == '0')
st, out = as_(srv, '', "select count(*) from public.iva_pickup_points", role='anon')
check('без входа таблица закрыта', st == 'ERR', out[-200:])
uval(srv, 'admin@x.ru', "update public.iva_pickup_points set name = 'Взлом' where owner_email = 'admin2@x.ru'")
check('админ не меняет чужой пункт', uval(srv, 'chief@x.ru', "select name from public.iva_pickup_points where owner_email = 'admin2@x.ru'") == 'Второй прокат')
uval(srv, 'admin@x.ru', "delete from public.iva_pickup_points where owner_email = 'admin2@x.ru'")
check('админ не удаляет чужой пункт', uval(srv, 'chief@x.ru', "select count(*) from public.iva_pickup_points") == '2')
st, out = as_(srv, 'admin@x.ru', "update public.iva_pickup_points set owner_email = 'admin2@x.ru'")
check('владельца поменять нельзя (нет права на столбец)', st == 'ERR', out[-200:])

# проверки полей
bad = [
    ('пустое название', ins('  ')),
    ('название длиннее 80', ins('Я' * 81)),
    ('короткий адрес', ins('Склад', addr="'Нск'")),
    ('email без @', ins('Склад', email="'prokat.mail.ru'")),
    ('email без домена', ins('Склад', email="'prokat@mail'")),
    ('открытие позже закрытия', ins('Склад', open_="'{1200,540,540,540,540,540,540}'", close="'{1140,1140,1140,1140,1140,1140,1140}'")),
    ('выходной только наполовину', ins('Склад', open_="'{-1,540,540,540,540,540,540}'", close="'{1140,1140,1140,1140,1140,1140,1140}'")),
    ('все дни выходные', ins('Склад', open_="'{-1,-1,-1,-1,-1,-1,-1}'", close="'{-1,-1,-1,-1,-1,-1,-1}'")),
    ('6 дней в графике', ins('Склад', open_="'{540,540,540,540,540,540}'", close="'{1140,1140,1140,1140,1140,1140}'")),
    ('закрытие 24:00', ins('Склад', close="'{1440,1140,1140,1140,1140,1140,1140}'", open_="'{540,540,540,540,540,540,540}'")),
    ('пустое время в графике', ins('Склад', close="'{1140,NULL,1140,1140,1140,1140,1140}'", open_="'{540,540,540,540,540,540,540}'")),
]
for name, sql in bad:
    st, out = as_(srv, 'admin@x.ru', sql)
    check('отказ: ' + name, st == 'ERR', out[-200:])
st, out = as_(srv, 'admin@x.ru', "insert into public.iva_pickup_points(name, address, lat, email) values ('Склад', 'Новосибирск, улица Ленина, д. 7', 55.0, 'a@b.ru')")
check('отказ: широта без долготы', st == 'ERR', out[-200:])
check('круглосуточно 00:00–23:59 и пробелы в названии', as_(srv, 'admin@x.ru', ins('  Круглосуточный   пункт ', open_="'{0,0,0,0,0,0,0}'", close="'{1439,1439,1439,1439,1439,1439,1439}'"))[0] == 'OK')
check('пробелы в названии убраны', uval(srv, 'admin@x.ru', "select name from public.iva_pickup_points where name like 'Кругл%'") == 'Круглосуточный пункт')
r = uval(srv, 'admin@x.ru', "select close_min::text from public.iva_rent_settings_get()")
check('второй пункт не меняет время закрытия (первый — прежний)', r == '{1140,1140,1140,1140,1140,960,-1}', r)

# изменение первого пункта → «Срок аренды»
st, out = as_(srv, 'admin@x.ru', "update public.iva_pickup_points set created_at = now() + interval '1 day' where name = 'Склад на Ленина'")
check('порядок («первый пункт») поменять нельзя (нет права на столбец)', st == 'ERR', out[-200:])
uval(srv, 'admin@x.ru', "update public.iva_pickup_points set close_min = '{1080,1080,1080,1080,1080,900,-1}' where name = 'Склад на Ленина'")
r = uval(srv, 'admin@x.ru', "select close_min::text from public.iva_rent_settings_get()")
check('изменили график первого пункта — время закрытия «Срока аренды» новое; порядок не сдвигается', r == '{1080,1080,1080,1080,1080,900,-1}', r)
st, out = as_(srv, 'admin@x.ru', "select public.iva_rent_settings_set('day', '{600,600,600,600,600,600,600}', 'Europe/Moscow')")
r = uval(srv, 'admin@x.ru', "select mode||'|'||close_min::text from public.iva_rent_settings_get()")
check('кнопки «Срока аренды»: режим меняется, время — из первого пункта', st == 'OK' and r == 'day|{1080,1080,1080,1080,1080,900,-1}', r + out[-200:])
uval(srv, 'admin@x.ru', "delete from public.iva_pickup_points where name = 'Склад на Ленина'")
r = uval(srv, 'admin@x.ru', "select close_min::text from public.iva_rent_settings_get()")
check('удалили первый — время закрытия из следующего (круглосуточный)', r == '{1439,1439,1439,1439,1439,1439,1439}', r)
uval(srv, 'admin@x.ru', "delete from public.iva_pickup_points")
r = uval(srv, 'admin@x.ru', "select close_min::text from public.iva_rent_settings_get()")
check('удалили все — время закрытия остаётся последним', r == '{1439,1439,1439,1439,1439,1439,1439}', r)
st, out = as_(srv, 'admin@x.ru', "select public.iva_rent_settings_set('close', '{1000,1000,1000,1000,1000,1000,-1}', 'Europe/Moscow')")
r = uval(srv, 'admin@x.ru', "select close_min::text from public.iva_rent_settings_get()")
check('пунктов нет — «Срок аренды» снова сохраняет присланное время', r == '{1000,1000,1000,1000,1000,1000,-1}', r)
st, out = as_(srv, 'client@x.ru', "select public.iva_rent_settings_set('close', '{1000,1000,1000,1000,1000,1000,-1}', 'Europe/Moscow')")
check('клиент «Срок аренды» не меняет (как в 10.77)', st == 'ERR' and 'только прокат' in out, out[-200:])

# лимит 20
for i in range(20):
    as_(srv, 'admin2@x.ru', ins('Пункт %d' % i))
check('не больше 20 пунктов у админа', uval(srv, 'admin2@x.ru', "select count(*) from public.iva_pickup_points") == '20')
st, out = as_(srv, 'admin2@x.ru', ins('Пункт 21'))
check('21-й — отказ с понятным текстом', st == 'ERR' and 'Не больше 20 пунктов выдачи' in out, out[-200:])

# откат из шапки
m = re.search(r'ОТКАТ[^\n]*\n((?:--\s+DROP[^\n]*\n)+)', SQL)
rollback = '\n'.join(l.lstrip('-').strip() for l in m.group(1).splitlines()) if m else ''
st, out = q(srv, rollback)
check('откат из шапки выполняется', bool(rollback) and st == 'OK' and val(srv, "select to_regclass('public.iva_pickup_points') is null") == 't', out[-300:])
srv.cleanup()

# ── 2. без SQL 10.77: пункты работают, связь пропускается ──
srv = mk()
q(srv, BASELINE + EXTRA)
st, out = q(srv, SQL)
check('без SQL 10.77 файл применяется (с предупреждением)', st == 'OK' and 'не применён' in out, out[-400:])
check('без SQL 10.77 админ добавляет пункт', as_(srv, 'admin@x.ru', ins('Склад'))[0] == 'OK')
srv.cleanup()

print('ИТОГ ошибок:', fails)
sys.exit(1 if fails else 0)

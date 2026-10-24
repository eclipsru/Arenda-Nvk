# Стенд этапа 1 «Сотрудники и пункты выдачи» (10.101): 20261026_tool_points.sql на настоящем PostgreSQL (pgserver).
#
# База как в бою (общая BASELINE стендов: роли, admins, fn_is_chief, tools), сверх неё — адресные столбцы tools,
# триггер «владелец — тот, кто публикует» (20261006) и правило доступа к своим объявлениям; затем пункты выдачи
# (20261023 + 20261025) и 20261026 (дважды — повторный запуск).
# Проверяет: уже выложенный инструмент без адреса — на первый пункт владельца, со своим адресом (с сайта) — не трогается;
# новый инструмент из приложения — на первый пункт, адрес и город — из пункта; перенос в другой свой пункт; чужой пункт —
# нельзя; адрес пункта поменяли — поменялся у инструмента; пункт с инструментом не удаляется; первый пункт у владельца
# без пунктов — его инструмент без адреса встаёт на пункт; смена владельца инструмента снимает чужой пункт; пункты для
# карточки — всем, только с инструментом и без email; служебные функции закрыты; без SQL 10.99 — понятная ошибка; откат.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/tool-points-stand.py
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


PICK = rd('supabase/migrations/20261023_pickup_points.sql')
TEL = rd('supabase/migrations/20261025_pickup_tel.sql')
SQL = rd('supabase/migrations/20261026_tool_points.sql')
_src = rd('tools/category-tree-stand.py')
BASELINE = None
for node in ast.parse(_src).body:
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') == 'BASELINE':
        BASELINE = ast.literal_eval(node.value)

# сверх общей базы — как в бою: адрес выдачи у объявления (23.09 / 12.10), владелец по входу (20261006),
# правило доступа «своё объявление — админ, всё — создатель», ещё админы
EXTRA = """
alter table public.tools add column pickup_address text not null default '';
alter table public.tools add column pickup_city text not null default '';
alter table public.tools add column pickup_lat double precision;
alter table public.tools add column pickup_lng double precision;
alter table public.tools add column descr text default '';
create or replace function public.iva_tools_owner_default() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare em text := public.jwt_email();
begin
  if em <> '' then
    if coalesce(btrim(new.owner_email), '') = '' then new.owner_email := em;
    else new.owner_email := lower(btrim(new.owner_email)); end if;
  end if;
  return new;
end $$;
create trigger iva_tools_owner_default before insert on public.tools for each row execute function public.iva_tools_owner_default();
create policy tools_admin on public.tools for all to authenticated
  using (lower(coalesce(owner_email, '')) = public.jwt_email() or public.fn_is_chief())
  with check (lower(coalesce(owner_email, '')) = public.jwt_email() or public.fn_is_chief());
insert into public.admins values ('admin2@x.ru', 'admin', true), ('admin3@x.ru', 'admin', true);
-- уже выложено: из приложения (адреса нет) и с сайта (свой адрес в другом городе)
insert into public.tools(name, owner_email) values ('Перфоратор из приложения', 'admin@x.ru'), ('Болгарка второго', 'admin2@x.ru'),
  ('Пила третьего', 'admin3@x.ru');
insert into public.tools(name, owner_email, pickup_address, pickup_city) values ('Шуруповёрт с сайта', 'admin@x.ru', 'Москва, ул. Тверская, 1', 'Москва');
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
    return ';'.join(x for x in lines if x not in ('BEGIN', 'SET', 'COMMIT', 'INSERT 0 1', 'UPDATE 1', 'DELETE 1', 'UPDATE 0', 'DELETE 0'))


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


def ins(name, city='Новосибирск', addr='Новосибирск, улица Ленина, д. 5', lat='55.03', lng='82.92'):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email) "
            "values ('%s', '%s', '%s', %s, %s, 'prokat@mail.ru')") % (name, addr, city, lat, lng)


def tool(name):
    return ("select coalesce(point_id::text, '-') || '|' || pickup_city || '|' || pickup_address || '|' || coalesce(pickup_lat::text, '-') "
            "from public.tools where name = '%s'") % name


def pid(srv, name):
    return val(srv, "select id from public.iva_pickup_points where name = '%s'" % name)


# ── 1. база, пункты (у admin@x.ru — один, у admin2 — пока нет), 20261026 ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA)
check('исходная база как в бою (tools с адресом выдачи, владелец по входу, свои объявления)', st == 'OK', out[-600:])
check('20261023 + 20261025 (пункты выдачи с телефоном) применяются', q(srv, PICK)[0] == 'OK' and q(srv, TEL)[0] == 'OK')
check('admin@x.ru добавил пункт «Склад» до этого SQL', as_(srv, 'admin@x.ru', ins('Склад'))[0] == 'OK')
st, out = q(srv, SQL)
check('20261026_tool_points.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 7 and '❌' not in out, out[-900:])
SKLAD = pid(srv, 'Склад')
r = val(srv, tool('Перфоратор из приложения'))
check('уже выложенный без адреса — на первый пункт владельца; адрес, город, координаты — из пункта',
      r == SKLAD + '|Новосибирск|Новосибирск, улица Ленина, д. 5|55.03', r)
r = val(srv, tool('Шуруповёрт с сайта'))
check('со своим адресом (с сайта, другой город) — не трогается', r == '-|Москва|Москва, ул. Тверская, 1|-', r)
check('у владельцев без пунктов — без пункта, как раньше', val(srv, tool('Болгарка второго')) == '-||' + '|-' and val(srv, tool('Пила третьего')) == '-|||-')
st, out = q(srv, SQL)
check('повторный запуск 20261026 — без ошибок, ничего не меняет', st == 'OK' and val(srv, tool('Перфоратор из приложения')).startswith(SKLAD + '|'), out[-600:])

# ── 2. новый инструмент из приложения: форма адрес не присылает, владельца ставит база ──
st, out = as_(srv, 'Admin@X.ru', "insert into public.tools(name) values ('Новый из приложения')")
r = val(srv, "select owner_email from public.tools where name = 'Новый из приложения'") + '|' + val(srv, tool('Новый из приложения'))
check('новый из приложения — владелец по входу, первый пункт, адрес и город пункта', st == 'OK'
      and r == 'admin@x.ru|' + SKLAD + '|Новосибирск|Новосибирск, улица Ленина, д. 5|55.03', r + out[-300:])
st, out = as_(srv, 'admin@x.ru', "insert into public.tools(name, pickup_address, pickup_city) values ('С сайта новый', 'Москва, Арбат, 2', 'Москва')")
check('новый со своим адресом (сайт) — без пункта, адрес свой', st == 'OK' and val(srv, tool('С сайта новый')) == '-|Москва|Москва, Арбат, 2|-')
st, out = as_(srv, 'admin3@x.ru', "insert into public.tools(name) values ('Без пунктов')")
check('у админа без пунктов новый — без пункта, как раньше', st == 'OK' and val(srv, tool('Без пунктов')) == '-|||-')

# ── 3. перенос в другой свой пункт; чужой — нельзя ──
check('admin@x.ru добавил второй пункт «Бердск»', as_(srv, 'admin@x.ru', ins('Бердск', 'Бердск', 'Бердск, улица Ленина, 10', '54.76', '83.1'))[0] == 'OK')
BERDSK = pid(srv, 'Бердск')
check('второй пункт не забирает инструмент первого', val(srv, tool('Новый из приложения')).startswith(SKLAD + '|'))
st, out = as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where name = 'Перфоратор из приложения'" % BERDSK)
r = val(srv, tool('Перфоратор из приложения'))
check('перенос в другой свой пункт — адрес и город нового пункта', st == 'OK' and r == BERDSK + '|Бердск|Бердск, улица Ленина, 10|54.76', r + out[-300:])
st, out = as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where name = 'Шуруповёрт с сайта'" % SKLAD)
check('инструменту с сайта ставят пункт в правке — адрес заменяется адресом пункта', st == 'OK'
      and val(srv, tool('Шуруповёрт с сайта')) == SKLAD + '|Новосибирск|Новосибирск, улица Ленина, д. 5|55.03', out[-300:])
check('admin2 добавил свой первый пункт', as_(srv, 'admin2@x.ru', ins('Второй прокат', 'Искитим', 'Искитим, улица Пушкина, 3', '54.64', '83.3'))[0] == 'OK')
P2 = pid(srv, 'Второй прокат')
r = val(srv, tool('Болгарка второго'))
check('первый пункт у владельца — его инструмент без адреса встал на этот пункт', r == P2 + '|Искитим|Искитим, улица Пушкина, 3|54.64', r)
st, out = as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where name = 'Новый из приложения'" % P2)
check('чужой пункт поставить нельзя (понятная ошибка)', st == 'ERR' and 'Пункт выдачи не найден у владельца инструмента' in out
      and val(srv, tool('Новый из приложения')).startswith(SKLAD + '|'), out[-300:])
st, out = as_(srv, 'admin@x.ru', "insert into public.tools(name, point_id) values ('Чужой при добавлении', '%s')" % P2)
check('и при добавлении — тоже нельзя', st == 'ERR' and 'Пункт выдачи не найден' in out, out[-300:])
st, out = as_(srv, 'admin@x.ru', "update public.tools set point_id = '00000000-0000-0000-0000-000000000000' where name = 'Новый из приложения'")
check('несуществующий пункт — нельзя', st == 'ERR', out[-300:])
st, out = as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where name = 'Болгарка второго'" % SKLAD)
check('чужой инструмент перенести нельзя (правило доступа)', val(srv, tool('Болгарка второго')).startswith(P2 + '|'), out[-300:])
st, out = as_(srv, 'chief@x.ru', "update public.tools set point_id = '%s' where name = 'Пила третьего'" % P2)
check('создатель тоже не ставит пункт чужого владельца', st == 'ERR' and val(srv, tool('Пила третьего')) == '-|||-', out[-300:])

# ── 4. адрес пункта поменяли — поменялся у инструмента; другие поля — без лишних записей ──
st, out = as_(srv, 'admin@x.ru', "update public.iva_pickup_points set address = 'Бердск, улица Свердлова, 7', lat = 54.77, lng = 83.11 where name = 'Бердск'")
check('адрес пункта поменяли — поменялся у его инструмента', st == 'OK'
      and val(srv, tool('Перфоратор из приложения')) == BERDSK + '|Бердск|Бердск, улица Свердлова, 7|54.77', out[-300:])
check('инструмент других пунктов не трогается', val(srv, tool('Новый из приложения')).startswith(SKLAD + '|Новосибирск|Новосибирск, улица Ленина'))
r = uval(srv, 'admin@x.ru', "update public.iva_pickup_points set address = 'Бердск, улица Свердлова, 8' where name = 'Бердск';\nselect '[' || coalesce(current_setting('iva.censor_skip', true), '') || ']'")
check('пропуск цензуры после записи адреса снова выключен', r.endswith('[]'), r)
q(srv, "create table public.iva_t(n int); insert into public.iva_t values (0);"
       "create function public.iva_t_count() returns trigger language plpgsql as $$ begin update public.iva_t set n = n + 1; return new; end $$;"
       "create trigger zz_count before update on public.tools for each row execute function public.iva_t_count();")
as_(srv, 'admin@x.ru', "update public.iva_pickup_points set name = 'Бердск-центр', email = 'b@mail.ru', tel = '+7 383 111-22-33' where name = 'Бердск'")
check('название, email, телефон пункта — инструмент не перезаписывается', val(srv, 'select n from public.iva_t') == '0')
q(srv, "drop trigger zz_count on public.tools; drop function public.iva_t_count(); drop table public.iva_t;")

# ── 5. пункт с инструментом не удаляется ──
st, out = as_(srv, 'admin@x.ru', "delete from public.iva_pickup_points where name = 'Склад'")
check('пункт с инструментом удалить нельзя', st == 'ERR' and 'tools_point_fk' in out and pid(srv, 'Склад') == SKLAD, out[-300:])
check('пустой пункт удаляется', as_(srv, 'admin@x.ru', ins('Пустой'))[0] == 'OK'
      and as_(srv, 'admin@x.ru', "delete from public.iva_pickup_points where name = 'Пустой'")[0] == 'OK' and pid(srv, 'Пустой') == '')

# ── 6. смена владельца инструмента (создатель) — пункт прежнего владельца снимается ──
st, out = as_(srv, 'chief@x.ru', "update public.tools set owner_email = 'admin3@x.ru' where name = 'Шуруповёрт с сайта'")
r = val(srv, tool('Шуруповёрт с сайта'))
check('инструмент передали другому — пункт прежнего владельца снят, адрес остался', st == 'OK' and r.startswith('-|Новосибирск|'), r + out[-300:])

# ── 7. пункты для карточки: всем, только с инструментом, без email и владельца ──
check('admin@x.ru добавил пункт без инструмента', as_(srv, 'admin@x.ru', ins('Без инструмента'))[0] == 'OK')
EMPTY = pid(srv, 'Без инструмента')
call = "select id::text || '|' || name || '|' || city || '|' || tel || '|' || close_min::text from public.iva_tool_points(array['%s', '%s', '%s']::uuid[])" % (SKLAD, P2, EMPTY)
r = uval(srv, '', call, role='anon')
check('без входа: пункты с инструментом — название, город, телефон, график', r == SKLAD + '|Склад|Новосибирск||{1140,1140,1140,1140,1140,1140,1140};'
      + P2 + '|Второй прокат|Искитим||{1140,1140,1140,1140,1140,1140,1140}', r)
check('пункт без инструмента не отдаётся', EMPTY not in r)
cols = val(srv, "select string_agg(a, ',') from (select unnest(proargnames) a from pg_proc where proname = 'iva_tool_points') s")
check('email и владелец пункта не отдаются', cols == 'p_ids,id,name,address,city,open_min,close_min,tel', cols)
check('клиент тоже получает', uval(srv, 'client@x.ru', "select count(*) from public.iva_tool_points(array['%s']::uuid[])" % SKLAD) == '1')
check('пустой и null список — пусто, без ошибки', uval(srv, '', "select count(*) from public.iva_tool_points(null)", role='anon') == '0'
      and uval(srv, '', "select count(*) from public.iva_tool_points('{}')", role='anon') == '0')
check('сами пункты клиенту по-прежнему не видны', uval(srv, 'client@x.ru', "select count(*) from public.iva_pickup_points") == '0')
for fn in ("public.iva_tools_to_first_point('admin@x.ru')", "public.iva_first_point('admin@x.ru')"):
    check('служебная функция закрыта: ' + fn.split('(')[0], as_(srv, 'admin@x.ru', 'select ' + fn)[0] == 'ERR')

# ── 8. откат из шапки, повторное применение ──
rb = '\n'.join(l[2:].strip() for l in SQL.split('-- ============================================================')[1].splitlines()
               if l.startswith('--      ') and re.match(r'--\s+(DROP|ALTER|NOTIFY)', l))
st, out = q(srv, rb)
check('откат из шапки выполняется', st == 'OK' and 'point_id' not in val(srv, "select string_agg(column_name, ',') from information_schema.columns where table_name = 'tools'"), out[-600:])
check('после отката адрес у инструмента остался, удаление пункта снова возможно',
      val(srv, "select pickup_city from public.tools where name = 'Перфоратор из приложения'") == 'Бердск'
      and as_(srv, 'admin@x.ru', "delete from public.iva_pickup_points where name = 'Без инструмента'")[0] == 'OK')
st, out = q(srv, SQL)
check('после отката снова применяется', st == 'OK' and out.count('✅') == 7, out[-600:])
srv.cleanup()

# ── 9. без SQL 10.99 (телефон пункта) — понятная ошибка, ничего не меняется ──
srv = mk()
q(srv, BASELINE + EXTRA)
q(srv, PICK)
st, out = q(srv, SQL)
check('без 20261025 — останавливается с понятной ошибкой', st == 'ERR' and 'Сначала примените 20261025_pickup_tel.sql' in out, out[-300:])
check('… и ничего не добавляет', 'point_id' not in val(srv, "select string_agg(column_name, ',') from information_schema.columns where table_name = 'tools'"))
srv.cleanup()

print('ИТОГ: ' + ('все проверки пройдены' if fails == 0 else 'ошибок: %d' % fails))
sys.exit(1 if fails else 0)

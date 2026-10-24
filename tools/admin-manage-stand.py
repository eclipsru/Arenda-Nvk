# Стенд 10.108 «Создатель приостанавливает и удаляет админов»: 20261102_admin_manage.sql на настоящем PostgreSQL (pgserver).
#
# Стенд А — база как в стенде 10.105 (BASELINE + пункты + 20261026–20261030: сотрудники и их инструменты), admins как в
# бою (почта уникальна, правило «права, комиссию и лимит меняет только владелец площадки» 20260923, профиль арендодателя
# со ссылкой на admins), каталог клиентам — только показанные. Проверяет: добавляет, приостанавливает, возобновляет и
# удаляет только создатель; владельца площадки и себя — нельзя; пауза скрывает показанные инструменты админа (скрытые им
# самим не трогает), клиенты их не видят и заявку на них не создать, админ не публикует, сотрудник теряет доступ;
# «Возобновить» показывает ровно скрытое паузой; переключатель «Доступ» на сайте (прямая правка active) — так же;
# сам админ отметки не снимет; удалённый пропадает из списка, история остаётся; снова добавить — вернётся, инструменты
# скрыты; сотрудника сделать админом нельзя; журнал; откат из шапки; повторное применение.
# Стенд Б — с «Цензурой» 10.92: снятое «Цензурой» во время паузы после «Возобновить» остаётся скрытым, очередь не растёт.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/admin-manage-stand.py
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
SO = rd('supabase/migrations/20261029_staff_orders.sql')
ST = rd('supabase/migrations/20261030_staff_tools.sql')
NO_MAT = rd('supabase/migrations/20261014_reviews_no_mat.sql')
CENSOR = rd('supabase/migrations/20261021_censor.sql')
SQL = rd('supabase/migrations/20261102_admin_manage.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')
PROD = const('tools/order-points-stand.py', 'PROD')
PRODX = const('tools/staff-tools-stand.py', 'PRODX')
CEXTRA = const('tools/censor-stand.py', 'EXTRA')
# откат — строки «--      …» блока «ОТКАТ» в шапке файла
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--  '))

PSQL = str(pgserver.postgres_server.POSTGRES_BIN_PATH / 'psql')
fails = 0

# admins как в бою: почта уникальна, на неё ссылается профиль арендодателя, правило 20260923 на права и комиссию,
# читать могут вошедшие, править — себя (профиль) и создатель, добавлять — создатель (штатное «Добавить админа»)
ADM = r"""
alter table public.admins add column if not exists name text, add column if not exists debt_limit numeric default 0,
  add column if not exists created_at timestamptz default now();
alter table public.admins add constraint admins_email_key unique (email);
create or replace function auth.role() returns text language sql stable as $$ select coalesce(auth.jwt() ->> 'role', '') $$;
grant execute on function auth.role() to anon, authenticated;
create or replace function public.protect_admin_access_fields() returns trigger language plpgsql set search_path=public,pg_temp as $$
begin
 if auth.role()='authenticated' and not public.fn_is_chief() and
  (new.email is distinct from old.email or new.role is distinct from old.role or new.active is distinct from old.active
   or new.fee_pct is distinct from old.fee_pct or new.debt_limit is distinct from old.debt_limit) then
   raise exception 'Права доступа, комиссию и лимит меняет только владелец площадки';
 end if;
 return new;
end $$;
create trigger protect_admin_access_fields before update on public.admins for each row execute function public.protect_admin_access_fields();
alter table public.admins enable row level security;
create policy admins_read on public.admins for select to authenticated using (true);
create policy admins_upd on public.admins for update to authenticated
  using (lower(email) = public.jwt_email() or public.fn_is_chief()) with check (lower(email) = public.jwt_email() or public.fn_is_chief());
create policy admins_ins on public.admins for insert to authenticated with check (public.fn_is_chief());
create table public.landlord_profiles(owner_email text primary key references public.admins(email) on delete cascade,
  details jsonb not null default '{}');
insert into public.landlord_profiles(owner_email) values ('admin@x.ru'), ('admin2@x.ru');
"""


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


def save(srv, owner, sid, email, name, points, issue, catalog, chat, notify, self_):
    arr = "array[%s]::uuid[]" % ','.join("'%s'" % p for p in points)
    b = lambda x: 'true' if x else 'false'
    return uval(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '', %s, %s, %s, %s, %s, %s)" %
                ('null' if sid is None else "'%s'" % sid, email, name, arr, b(issue), b(catalog), b(chat), b(notify), b(self_)))


def shown(srv, owner):
    """Что видит клиент (гость) из инструментов админа: названия по алфавиту."""
    return uval(srv, '', "select coalesce(string_agg(name, ', ' order by name), '') from public.tools where owner_email = '%s'" % owner,
                role='anon')


def flags(srv, owner):
    """«название:показан:скрыт паузой» всех инструментов админа (как видит база)."""
    return val(srv, "select string_agg(name || ':' || coalesce(active::text, '') || ':' || chief_paused::text, ', ' order by name) "
                    "from public.tools where owner_email = '%s'" % owner)


def adm(srv, email):
    return val(srv, "select coalesce(active::text, '') || '|' || (removed_at is not null)::text from public.admins where email = '%s'" % email)


def rpc(srv, who, sql):
    return as_(srv, who, 'select ' + sql)


def events(srv):
    return val(srv, "select string_agg(email || ':' || action || ':' || actor || ':' || tools, ' ; ' order by id) from public.iva_admin_events")


C = 'chief@x.ru'

# ═══════════════ Стенд А ═══════════════
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX)
check('исходная база как в бою (правило владельца 20261006, каталог — только показанные)', st == 'OK', out[-600:])
check('SQL 10.77, 10.95, 10.99, 10.101 применяются', all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
q(srv, ("update public.tools set active = false where name = 'Шуруповёрт с сайта';"
        "insert into public.tools(name, owner_email, price, point_id, active) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}', true), ('Пила', 'admin@x.ru', 500, '{s}', true)").format(s=SKLAD))
check('SQL 10.102–10.105 применяются', all(q(srv, s)[0] == 'OK' for s in (OP, STAFF, SO, ST)))
st, out = q(srv, ADM)
check('admins как в бою (уникальная почта, правило 20260923, профиль арендодателя)', st == 'OK', out[-600:])
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD], True, True, False, False, False)
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
check('сотрудник Пётр работает (видит инструменты пункта)', 'Дрель Makita' in uval(srv, 'petr@x.ru', 'select public.iva_staff_tools()::text'))

st, out = q(srv, SQL)
check('20261102_admin_manage.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-600:])
A_ALL = 'Дрель Makita:true:false, Перфоратор из приложения:true:false, Пила:true:false, Шуруповёрт с сайта:false:false'
check('после применения у инструментов ничего не поменялось', flags(srv, 'admin@x.ru') == A_ALL, flags(srv, 'admin@x.ru'))

# ── права на действия ──
for who, role, name in (('admin@x.ru', 'authenticated', 'админ'), ('client@x.ru', 'authenticated', 'клиент'), ('', 'anon', 'гость')):
    bad = [as_(srv, who, "select public.iva_admin_pause('admin2@x.ru', true)", role),
           as_(srv, who, "select public.iva_admin_remove('admin2@x.ru')", role),
           as_(srv, who, "select public.iva_admin_add('new@x.ru')", role)]
    check('%s не приостанавливает, не удаляет и не добавляет админов (отказ самой функции)' % name,
          err_has(bad[0], 'Приостанавливает админов только владелец площадки') and err_has(bad[1], 'Удаляет админов только владелец площадки')
          and err_has(bad[2], 'Добавляет админов только владелец площадки') if role != 'anon' else all(r[0] == 'ERR' for r in bad),
          [r[1][-200:] for r in bad])
check('админу состояние чужого админа и список удалённых не отдаются',
      uval(srv, 'admin@x.ru', "select coalesce(public.iva_admin_state('admin2@x.ru')::text, 'null') || '|' || "
                              "(select count(*) from public.iva_admins_removed())") == 'null|0')
check('у создателя — состояние админа', json.loads(uval(srv, C, "select public.iva_admin_state('admin@x.ru')")) ==
      {'email': 'admin@x.ru', 'role': 'admin', 'active': True, 'removed': False, 'hidden': 0})
check('владельца площадки не приостановить и не удалить',
      err_has(rpc(srv, C, "public.iva_admin_pause('chief@x.ru', true)"), 'Владельца площадки приостановить нельзя')
      and err_has(rpc(srv, C, "public.iva_admin_remove('chief@x.ru')"), 'Владельца площадки удалить нельзя'))
check('несуществующего — понятная ошибка', err_has(rpc(srv, C, "public.iva_admin_pause('nobody@x.ru', true)"), 'Админ не найден'))

# ── добавить ──
check('добавить: ошибка в почте', err_has(rpc(srv, C, "public.iva_admin_add('ivan.mail.ru')"), 'Похоже, в email ошибка'))
check('добавить: своя почта', err_has(rpc(srv, C, "public.iva_admin_add('Chief@X.ru')"), 'Это ваша почта'))
check('добавить: уже админ', err_has(rpc(srv, C, "public.iva_admin_add(' ADMIN@x.ru ')"), 'Этот человек уже админ'))
check('добавить: сотрудника проката нельзя', err_has(rpc(srv, C, "public.iva_admin_add('petr@x.ru')"), 'сотрудник проката'))
r = uval(srv, C, "select public.iva_admin_add('  New@X.ru ')")
check('добавить: новый админ (почта в нижнем регистре, доступ включён)',
      json.loads(r or '{}') == {'email': 'new@x.ru', 'restored': False} and adm(srv, 'new@x.ru') == 'true|false', r)
check('новый админ может публиковать', as_(srv, 'new@x.ru', "insert into public.tools(name, owner_email, price, active) values ('Лобзик', 'new@x.ru', 200, true)")[0] == 'OK')

# ── приостановить ──
r = uval(srv, C, "select public.iva_admin_pause('admin@x.ru', true)")
check('«Приостановить»: доступ выключен, скрыто 3 показанных инструмента',
      json.loads(r or '{}') == {'email': 'admin@x.ru', 'active': False, 'hidden': 3} and adm(srv, 'admin@x.ru') == 'false|false', r)
check('скрытый самим админом не помечен паузой',
      flags(srv, 'admin@x.ru') == 'Дрель Makita:false:true, Перфоратор из приложения:false:true, Пила:false:true, Шуруповёрт с сайта:false:false',
      flags(srv, 'admin@x.ru'))
check('клиенты не видят его инструменты', shown(srv, 'admin@x.ru') == '', shown(srv, 'admin@x.ru'))
check('чужие инструменты не тронуты', shown(srv, 'admin2@x.ru') == 'Болгарка второго' and shown(srv, 'new@x.ru') == 'Лобзик')
DRILL = val(srv, "select id from public.tools where name = 'Дрель Makita'")
before = val(srv, "select count(*) from public.order_parts where owner_email = 'admin@x.ru'")
uval(srv, '', ("select public.iva_submit_order('Иван', '+79990000000', 'Дрель Makita', 'Самовывоз', '', '', 'client@x.ru', 3, "
               "'[{\"id\": \"%s\", \"days\": 3}]'::jsonb)") % DRILL, role='anon')
check('заявка на его инструмент к нему не приходит',
      val(srv, "select count(*) from public.order_parts where owner_email = 'admin@x.ru'") == before)
check('приостановленный не публикует',
      as_(srv, 'admin@x.ru', "insert into public.tools(name, owner_email, price, active) values ('Новый', 'admin@x.ru', 100, true)")[0] == 'ERR')
check('приостановленный не показывает свой инструмент',
      as_(srv, 'admin@x.ru', "update public.tools set active = true where name = 'Пила'")[0] == 'OK'
      and shown(srv, 'admin@x.ru') == '')
check('сотрудник приостановленного без доступа',
      'Дрель' not in uval(srv, 'petr@x.ru', 'select coalesce(public.iva_staff_tools()::text, \'\')')
      and as_(srv, 'petr@x.ru', "insert into public.tools(name, active, cat, owner_email, price) values ('От Петра', true, 'drill', 'petr@x.ru', 100)")[0] == 'ERR')
check('сам себя не возобновит', err_has(as_(srv, 'admin@x.ru', "update public.admins set active = true where email = 'admin@x.ru'"),
                                         'меняет только владелец площадки') and adm(srv, 'admin@x.ru') == 'false|false')
r1 = as_(srv, 'admin2@x.ru', "update public.admins set removed_at = now() where email = 'admin2@x.ru'")
r2 = as_(srv, 'new@x.ru', "update public.tools set chief_paused = true where name = 'Лобзик'")
check('отметку «удалён» админ не поставит, «скрыт паузой» — не подделать',
      err_has(r1, 'Удаляет админов только владелец площадки') and r2[0] == 'OK'
      and flags(srv, 'new@x.ru') == 'Лобзик:true:false', [r1[1][-200:], flags(srv, 'new@x.ru')])
check('профиль админа свой «Мои данные» править может (как раньше)',
      as_(srv, 'admin2@x.ru', "update public.admins set full_name = 'Пётр Второй' where email = 'admin2@x.ru'")[0] == 'OK')
check('повторная «пауза» ничего не ломает', json.loads(uval(srv, C, "select public.iva_admin_pause('admin@x.ru', true)") or '{}').get('hidden') == 3)

# ── возобновить ──
r = uval(srv, C, "select public.iva_admin_pause('admin@x.ru', false)")
check('«Возобновить»: доступ включён, скрытого паузой не осталось',
      json.loads(r or '{}') == {'email': 'admin@x.ru', 'active': True, 'hidden': 0} and adm(srv, 'admin@x.ru') == 'true|false', r)
check('показаны ровно те, что скрыла пауза', flags(srv, 'admin@x.ru') == A_ALL, flags(srv, 'admin@x.ru'))
check('клиенты снова видят', shown(srv, 'admin@x.ru') == 'Дрель Makita, Перфоратор из приложения, Пила', shown(srv, 'admin@x.ru'))
check('сотрудник снова работает', 'Дрель Makita' in uval(srv, 'petr@x.ru', 'select public.iva_staff_tools()::text'))

# ── переключатель «Доступ включён» на сайте (прямая правка active создателем) ──
as_(srv, C, "update public.admins set active = false, full_name = 'Второй' where email = 'admin2@x.ru'")
s1 = shown(srv, 'admin2@x.ru')
as_(srv, C, "update public.admins set active = true where email = 'admin2@x.ru'")
check('сайт «Доступ выкл / вкл» — инструменты скрываются и возвращаются так же', s1 == '' and shown(srv, 'admin2@x.ru') == 'Болгарка второго', s1)
as_(srv, C, "update public.admins set full_name = 'Второй Админ' where email = 'admin2@x.ru'")
check('правка профиля без смены доступа инструменты не трогает', shown(srv, 'admin2@x.ru') == 'Болгарка второго')

# ── удалить ──
r = uval(srv, C, "select public.iva_admin_remove('admin2@x.ru')")
check('«Удалить»: доступ выключен, отметка «удалён», инструменты скрыты',
      json.loads(r or '{}') == {'email': 'admin2@x.ru', 'hidden': 1} and adm(srv, 'admin2@x.ru') == 'false|true'
      and shown(srv, 'admin2@x.ru') == '', r)
check('история и профиль арендодателя на месте (строка админа не удаляется)',
      val(srv, "select count(*) from public.admins where email = 'admin2@x.ru'") == '1'
      and val(srv, "select count(*) from public.landlord_profiles where owner_email = 'admin2@x.ru'") == '1')
check('список удалённых у создателя', uval(srv, C, "select string_agg(x, ',') from public.iva_admins_removed() x") == 'admin2@x.ru')
check('удалённого не удалить и не приостановить повторно',
      err_has(rpc(srv, C, "public.iva_admin_remove('admin2@x.ru')"), 'Админ не найден')
      and err_has(rpc(srv, C, "public.iva_admin_pause('admin2@x.ru', false)"), 'Админ не найден'))
check('удалённый не публикует',
      as_(srv, 'admin2@x.ru', "insert into public.tools(name, owner_email, price, active) values ('Ещё', 'admin2@x.ru', 100, true)")[0] == 'ERR')
check('приостановленного — «Возобновить», а не добавить заново',
      uval(srv, C, "select public.iva_admin_pause('new@x.ru', true)") != ''
      and err_has(rpc(srv, C, "public.iva_admin_add('new@x.ru')"), 'нажмите «Возобновить»'))
uval(srv, C, "select public.iva_admin_pause('new@x.ru', false)")

# ── снова добавить удалённого ──
r = uval(srv, C, "select public.iva_admin_add('admin2@x.ru')")
check('снова добавить удалённого: вернулся админом',
      json.loads(r or '{}') == {'email': 'admin2@x.ru', 'restored': True} and adm(srv, 'admin2@x.ru') == 'true|false', r)
check('его инструменты остались скрытыми, отметка паузы снята (включит сам)',
      flags(srv, 'admin2@x.ru') == 'Болгарка второго:false:false' and shown(srv, 'admin2@x.ru') == '', flags(srv, 'admin2@x.ru'))
check('вернувшийся сам показывает свой инструмент',
      as_(srv, 'admin2@x.ru', "update public.tools set active = true where name = 'Болгарка второго'")[0] == 'OK'
      and shown(srv, 'admin2@x.ru') == 'Болгарка второго')
check('список удалённых пуст', uval(srv, C, "select count(*) from public.iva_admins_removed()") == '0')

# ── журнал ──
ev = events(srv)
want = ['new@x.ru:add:chief@x.ru:0', 'admin@x.ru:pause:chief@x.ru:3', 'admin@x.ru:resume:chief@x.ru:3',
        'admin2@x.ru:pause:chief@x.ru:1', 'admin2@x.ru:resume:chief@x.ru:1', 'admin2@x.ru:remove:chief@x.ru:1',
        'new@x.ru:pause:chief@x.ru:1', 'new@x.ru:resume:chief@x.ru:1', 'admin2@x.ru:restore:chief@x.ru:0']
check('журнал: кто, кого, что и сколько инструментов', ev.split(' ; ') == want, ev)
check('журнал читает только создатель',
      uval(srv, 'admin@x.ru', 'select count(*) from public.iva_admin_events') == '0'
      and uval(srv, C, 'select count(*) from public.iva_admin_events') == str(len(want))
      and as_(srv, '', 'select count(*) from public.iva_admin_events', 'anon')[0] == 'ERR')
check('из SQL Editor (без входа) — тоже работает, в журнале «база»',
      q(srv, "update public.admins set active = false where email = 'new@x.ru'")[0] == 'OK'
      and shown(srv, 'new@x.ru') == '' and events(srv).endswith('new@x.ru:pause:база:1'))

# ── откат ──
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK', out[-600:])
check('после отката скрытое паузой снова показано, столбцов и функций нет',
      shown(srv, 'new@x.ru') == 'Лобзик'
      and val(srv, "select count(*) from information_schema.columns where table_schema = 'public' and column_name in ('chief_paused', 'removed_at')") == '0'
      and val(srv, "select count(*) from pg_proc where proname in ('iva_admin_add', 'iva_admin_pause', 'iva_admin_remove', 'iva_admin_state', "
                 "'iva_admins_removed', 'iva_admin_access_trg', 'iva_admin_removed_guard', 'iva_tool_paused_guard')") == '0'
      and val(srv, "select count(*) from pg_trigger where tgname in ('trg_iva_admin_access', 'trg_iva_admin_removed_guard', 'trg_iva_tool_paused')") == '0')
check('после отката штатное «Добавить админа» (POST admins) работает как раньше',
      as_(srv, C, "insert into public.admins(email, role, name) values ('old@x.ru', 'admin', '')")[0] == 'OK')
st, out = q(srv, SQL)
check('после отката применяется снова', st == 'OK' and out.count('✅') == 4, out[-400:])
srv.cleanup()

# ═══════════════ Стенд Б: с «Цензурой» ═══════════════
srv = mk()
st, out = q(srv, BASELINE + CEXTRA + NO_MAT + CENSOR)
check('стенд Б: база с «Цензурой» 10.92', st == 'OK', out[-600:])
q(srv, "alter table public.admins add column name text; insert into public.tools(name, cat, owner_email, active) values "
       "('Пила дисковая', 'power', 'admin@x.ru', true), ('Шлифмашина', 'power', 'admin@x.ru', true)")
st, out = q(srv, SQL)
check('стенд Б: 20261102 применяется', st == 'OK' and out.count('✅') == 4, out[-600:])
qn = val(srv, 'select count(*) from public.iva_censor_queue')
uval(srv, C, "select public.iva_admin_pause('admin@x.ru', true)")
SAW = val(srv, "select id from public.tools where name = 'Пила дисковая'")
q(srv, ("insert into public.iva_censor_queue(source, ref_id, field, content, status) values ('tool', '%s', 'name', 'Пила дисковая', 'hidden')") % SAW)
uval(srv, C, "select public.iva_admin_pause('admin@x.ru', false)")
check('снятое «Цензурой» во время паузы остаётся скрытым, остальное показано',
      flags(srv, 'admin@x.ru') == 'Перфоратор Makita HR2470:true:false, Пила дисковая:false:false, Шлифмашина:true:false',
      flags(srv, 'admin@x.ru'))
check('пауза и возобновление не добавляют записей в очередь «Цензуры»',
      val(srv, 'select count(*) from public.iva_censor_queue') == str(int(qn) + 1))
srv.cleanup()

print('\nИтого: %s' % ('всё сходится' if not fails else 'ошибок: %d' % fails))
sys.exit(1 if fails else 0)

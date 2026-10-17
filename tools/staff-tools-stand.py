# Стенд этапа 3в «Сотрудники и пункты выдачи» (10.105): 20261030_staff_tools.sql на настоящем PostgreSQL (pgserver).
#
# База как в стенде 10.104 (BASELINE + пункты + 20261026 + 20261027 + 20261028 + 20261029), правило владельца tools_admin
# как в бою (20261006: только активный админ и свои), чтение каталога клиентами — только показанные (active), хранилище
# фото — упрощённая storage.objects с правилом владельца. Проверяет: сотрудник «Каталог» добавляет инструмент своего
# пункта (владелец — прокат, пункт — первый свой, адрес из пункта, «на одобрении», клиентам не виден), видит, правит,
# скрывает и удаляет любые инструменты своих пунктов и только их; не показывает ждущий одобрения; правка отклонённого —
# снова на одобрение; чужой пункт, чужой прокат, смена владельца — нельзя; без права «Каталог», не принявший
# приглашение, убранный, при выключенном прокате — нельзя; владелец: режим «сразу / после одобрения», «На одобрении»,
# «Опубликовать» / «Отклонить», свой переключатель «показать» = одобрение; чужой прокат не одобряет; фото — сотруднику
# «Каталог» можно, «Выдаче» нельзя, чужой файл не заменить; откат из шапки; повторное применение; без SQL 10.104 —
# понятная ошибка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-tools-stand.py
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
SQL = rd('supabase/migrations/20261030_staff_tools.sql')
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


# Как в бою: правило владельца (20261006), каталог клиентам — только показанные, хранилище фото с правилом владельца
PRODX = r"""
alter table public.tools add column if not exists created_at timestamptz default now();
alter table public.tools add column if not exists img text;
create or replace function public.fn_is_admin() returns boolean language sql stable security definer set search_path = public as $$
  select public.jwt_email() <> '' and exists(select 1 from public.admins a where coalesce(a.active, false)
    and a.role in ('admin', 'chief') and lower(btrim(coalesce(a.email, ''))) = public.jwt_email()) $$;
drop policy if exists tools_admin on public.tools;
create policy tools_admin on public.tools for all to authenticated
  using (public.fn_is_admin() and (public.fn_is_chief() or lower(btrim(coalesce(owner_email, ''))) = public.jwt_email()))
  with check (public.fn_is_admin() and (public.fn_is_chief() or lower(btrim(coalesce(owner_email, ''))) = public.jwt_email()));
drop policy if exists tools_read on public.tools;
create policy tools_read on public.tools for select using (active);
create function auth.uid() returns uuid language sql stable as $$ select nullif(auth.jwt() ->> 'sub', '')::uuid $$;
grant execute on function auth.uid() to anon, authenticated;
create schema storage;
grant usage on schema storage to anon, authenticated;
create table storage.objects(id uuid primary key default gen_random_uuid(), bucket_id text, name text,
  owner uuid default auth.uid(), unique (bucket_id, name));
alter table storage.objects enable row level security;
grant select, insert, update, delete on storage.objects to authenticated;
create policy tp_admin_ins on storage.objects for insert to authenticated with check (bucket_id = 'tool-photos' and public.fn_is_admin());
"""


def tool(srv, tid_):
    """«владелец|пункт|показан|одобрение|сотрудник» инструмента (как видит база)."""
    return val(srv, ("select coalesce(t.owner_email, '') || '|' || coalesce(pp.name, '') || '|' || coalesce(t.active::text, '') || '|' "
                     "|| coalesce(t.review, '') || '|' || coalesce(s.name, '') from public.tools t "
                     "left join public.iva_pickup_points pp on pp.id = t.point_id left join public.iva_staff s on s.id = t.staff_id "
                     "where t.id = '%s'") % tid_)


def add(srv, email, name, extra_cols='', extra_vals=''):
    """Как штатная форма приложения: POST tools с owner_email = почта вошедшего, active = true, без пункта."""
    return as_(srv, email, ("insert into public.tools(name, active, cat, owner_email, price%s) values ('%s', true, 'drill', '%s', 400%s)"
                            % (extra_cols, name, email, extra_vals)))


def staff_list(srv, email):
    """«название|пункт|одобрение|добавил я» — в том порядке, в каком отдаёт база."""
    return uval(srv, email, "select coalesce(string_agg((j ->> 'name') || '|' || (j ->> 'point_name') || '|' || coalesce(j ->> 'review', '') "
                            "|| '|' || (j ->> 'mine'), ' ; ' order by n), '') from public.iva_staff_tools() with ordinality as x(j, n)")


# ── 1. база, применение, повтор ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX)
check('исходная база как в бою (правило владельца 20261006, хранилище фото)', st == 'OK', out[-600:])
check('SQL 10.77, 10.95, 10.99, 10.101 применяются', all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP)))
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
check('SQL 10.102, 10.103, 10.104 применяются', all(q(srv, s)[0] == 'OK' for s in (OP, STAFF, SO)))
st, out = q(srv, SQL)
check('20261030_staff_tools.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 5 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 5, out[-600:])
check('у прежних инструментов ничего не поменялось', tool(srv, DRILL) == 'admin@x.ru|Склад|true||' and tool(srv, SAW) == 'admin@x.ru|Бердск|true||')
check('по умолчанию — «после моего одобрения»', uval(srv, 'admin@x.ru', 'select public.iva_staff_publish()') == 'approve')

# сотрудники: Пётр — «Каталог» на Складе; Иван — «Выдача» на Складе; Ольга — «Каталог» в Бердске;
# Маша — «Каталог», не приняла; Гоша — «Каталог» другого проката
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD], False, True, False, False, False)
IVAN = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [SKLAD], True, False, False, True, False)
OLGA = save(srv, 'admin@x.ru', None, 'olga@x.ru', 'Ольга', [BERDSK], False, True, False, False, False)
MASHA = save(srv, 'admin@x.ru', None, 'masha@x.ru', 'Маша', [SKLAD], False, True, False, False, False)
GOSHA = save(srv, 'admin2@x.ru', None, 'gosha@x.ru', 'Гоша', [ISK], False, True, False, False, False)
for em, sid in (('petr@x.ru', PETR), ('ivan@x.ru', IVAN), ('olga@x.ru', OLGA), ('gosha@x.ru', GOSHA)):
    accept(srv, em, sid)
check('сотрудники приглашены и приняли (стенд 10.103)', val(srv, "select count(*) from public.iva_staff where status = 'active'") == '4')

# ── 2. сотрудник добавляет ──
st, out = add(srv, 'petr@x.ru', 'Перфоратор Bosch')
NEW = tid(srv, 'Перфоратор Bosch', 'admin@x.ru')
check('Пётр выложил из штатной формы (почта в форме — его)', st == 'OK' and len(NEW) == 36, out[-400:])
check('владелец — прокат, пункт — Склад, скрыт, «на одобрении», добавил Пётр', tool(srv, NEW) == 'admin@x.ru|Склад|false|pending|Пётр', tool(srv, NEW))
check('адрес и город — из пункта', val(srv, "select pickup_address || '|' || pickup_city from public.tools where id = '%s'" % NEW)
      == 'Новосибирск, улица Ленина, 5|Новосибирск')
check('клиент и гость не видят ждущий одобрения',
      uval(srv, 'client@x.ru', "select count(*) from public.tools where id = '%s'" % NEW) == '0'
      and uval(srv, '', "select count(*) from public.tools where id = '%s'" % NEW, role='anon') == '0')
check('Пётр находит свой новый инструмент (как приложение после «Опубликовать»: staff_id + название)',
      uval(srv, 'petr@x.ru', "select id from public.tools where staff_id = '%s' and name = 'Перфоратор Bosch'" % PETR) == NEW)
r = staff_list(srv, 'petr@x.ru')
check('«Инструменты пункта»: ждущий одобрения — первым, затем инструменты Склада по названию; Бердска нет',
      r == 'Перфоратор Bosch|Склад|pending|true ; Дрель Makita|Склад||false ; Перфоратор из приложения|Склад||false', r)
r = uval(srv, 'petr@x.ru', "select count(*) from public.iva_staff_tools() j where j ? 'owner_email' or j ? 'staff_id'")
check('в списке сотрудника нет почты владельца и номера сотрудника', r == '0', r)
check('владелец видит «На одобрении»: название, цена, пункт, кто добавил',
      uval(srv, 'admin@x.ru', "select name || '|' || price || '|' || point_name || '|' || staff_name from public.iva_tools_pending()")
      == 'Перфоратор Bosch|400|Склад|Пётр')

# ── 3. ждёт одобрения ──
as_(srv, 'petr@x.ru', "update public.tools set active = true where id = '%s'" % NEW)
check('Пётр не может показать ждущий одобрения', tool(srv, NEW) == 'admin@x.ru|Склад|false|pending|Пётр', tool(srv, NEW))
as_(srv, 'petr@x.ru', "update public.tools set price = 450, review = null where id = '%s'" % NEW)
check('правка ждущего — сохраняется, одобрение не снимается',
      val(srv, "select price from public.tools where id = '%s'" % NEW) == '450' and tool(srv, NEW).endswith('|false|pending|Пётр'))
check('чужой прокат не одобряет', err_has(as_(srv, 'admin2@x.ru', "select public.iva_tool_review('%s', true)" % NEW), 'уже не ждёт'))
check('сотрудник не одобряет и не видит «На одобрении»',
      err_has(as_(srv, 'petr@x.ru', "select public.iva_tool_review('%s', true)" % NEW), 'Только владелец')
      and uval(srv, 'petr@x.ru', 'select count(*) from public.iva_tools_pending()') == '0')
check('«Опубликовать»', uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', true)" % NEW) == 'published'
      and tool(srv, NEW) == 'admin@x.ru|Склад|true||Пётр')
check('после одобрения клиенты видят', uval(srv, 'client@x.ru', "select count(*) from public.tools where id = '%s'" % NEW) == '1')
check('второй раз «Опубликовать» — «уже не ждёт»', err_has(as_(srv, 'admin@x.ru', "select public.iva_tool_review('%s', true)" % NEW), 'уже не ждёт'))
as_(srv, 'petr@x.ru', "update public.tools set price = 500 where id = '%s'" % NEW)
check('правка опубликованного — сразу, без одобрения',
      val(srv, "select price from public.tools where id = '%s'" % NEW) == '500' and tool(srv, NEW) == 'admin@x.ru|Склад|true||Пётр')

# ── 4. отклонить ──
add(srv, 'petr@x.ru', 'Болгарка')
B = tid(srv, 'Болгарка', 'admin@x.ru')
check('«Отклонить» — скрыт, «Отклонён»', uval(srv, 'admin@x.ru', "select public.iva_tool_review('%s', false)" % B) == 'rejected'
      and tool(srv, B) == 'admin@x.ru|Склад|false|rejected|Пётр')
check('отклонённого нет в «На одобрении»', 'Болгарка' not in uval(srv, 'admin@x.ru', 'select string_agg(name, \',\') from public.iva_tools_pending()'))
check('сотрудник видит «Отклонён»', 'Болгарка|Склад|rejected|true' in staff_list(srv, 'petr@x.ru'))
as_(srv, 'petr@x.ru', "update public.tools set name = 'Болгарка Makita', active = true where id = '%s'" % B)
check('исправил отклонённый — снова «на одобрении», скрыт', tool(srv, B) == 'admin@x.ru|Склад|false|pending|Пётр', tool(srv, B))
as_(srv, 'admin@x.ru', "update public.tools set active = true where id = '%s'" % B)
check('владелец показал в своём списке — это «Опубликовать»', tool(srv, B) == 'admin@x.ru|Склад|true||Пётр', tool(srv, B))

# ── 5. только свои пункты ──
check('чужой пункт при добавлении — понятная ошибка',
      err_has(add(srv, 'petr@x.ru', 'Шуруповёрт', ', point_id', ", '%s'" % BERDSK), 'только из ваших пунктов'))
check('перенести в чужой пункт — нельзя',
      err_has(as_(srv, 'petr@x.ru', "update public.tools set point_id = '%s' where id = '%s'" % (BERDSK, NEW)), 'только из ваших пунктов'))
as_(srv, 'petr@x.ru', "update public.tools set price = 1 where id = '%s'" % SAW)
as_(srv, 'petr@x.ru', "delete from public.tools where id = '%s'" % SAW)
check('инструмент Бердска Пётр не меняет и не удаляет', val(srv, "select price from public.tools where id = '%s'" % SAW) == '500')
as_(srv, 'petr@x.ru', "update public.tools set descr = 'Кейс в комплекте' where id = '%s'" % DRILL)
check('любой инструмент своего пункта (добавил владелец) — правит', val(srv, "select descr from public.tools where id = '%s'" % DRILL) == 'Кейс в комплекте')
as_(srv, 'petr@x.ru', "update public.tools set active = false where id = '%s'" % DRILL)
r1 = tool(srv, DRILL)
as_(srv, 'petr@x.ru', "update public.tools set active = true where id = '%s'" % DRILL)
check('скрыть и показать — сразу', r1 == 'admin@x.ru|Склад|false||' and tool(srv, DRILL) == 'admin@x.ru|Склад|true||', (r1, tool(srv, DRILL)))
as_(srv, 'petr@x.ru', "update public.tools set owner_email = 'petr@x.ru', staff_id = null where id = '%s'" % NEW)
check('сменить владельца или «кто добавил» — нельзя', tool(srv, NEW) == 'admin@x.ru|Склад|true||Пётр', tool(srv, NEW))
check('Гоша (другой прокат) инструментов Склада не видит и не меняет',
      'Склад' not in staff_list(srv, 'gosha@x.ru')
      and as_(srv, 'gosha@x.ru', "update public.tools set price = 1 where id = '%s'" % DRILL)[0] == 'OK'
      and val(srv, "select price from public.tools where id = '%s'" % DRILL) == '300')
add(srv, 'gosha@x.ru', 'Пила Гоши')
check('у Гоши новый инструмент — в его прокат и пункт', tool(srv, tid(srv, 'Пила Гоши', 'admin2@x.ru')) == 'admin2@x.ru|Искитим|false|pending|Гоша')
check('Ольга видит только Бердск', staff_list(srv, 'olga@x.ru') == 'Пила|Бердск||false', staff_list(srv, 'olga@x.ru'))

# ── 6. без права — нельзя ──
check('Иван («Выдача») не выкладывает', add(srv, 'ivan@x.ru', 'Иванов')[0] == 'ERR' and tid(srv, 'Иванов', 'ivan@x.ru') == ''
      and tid(srv, 'Иванов', 'admin@x.ru') == '')
as_(srv, 'ivan@x.ru', "update public.tools set price = 2 where id = '%s'" % DRILL)
check('Иван не меняет и не видит список', val(srv, "select price from public.tools where id = '%s'" % DRILL) == '300'
      and staff_list(srv, 'ivan@x.ru') == '')
check('Маша (не приняла приглашение) не выкладывает', add(srv, 'masha@x.ru', 'Машин')[0] == 'ERR')
check('клиент не выкладывает', add(srv, 'client@x.ru', 'Клиентский')[0] == 'ERR')
check('клиент не меняет режим', err_has(as_(srv, 'client@x.ru', "select public.iva_staff_publish_set('now')"), 'Только владелец'))

# ── 7. владелец ──
add(srv, 'admin@x.ru', 'Своя дрель', ', staff_id, review', ", '%s', 'pending'" % PETR)
check('у инструмента владельца «кто добавил» и одобрения нет (даже если прислали)', tool(srv, tid(srv, 'Своя дрель', 'admin@x.ru')) == 'admin@x.ru|Склад|true||')
check('неизвестный режим — ошибка', err_has(as_(srv, 'admin@x.ru', "select public.iva_staff_publish_set('later')"), 'Неизвестный режим'))
check('режим «сразу»', uval(srv, 'admin@x.ru', "select public.iva_staff_publish_set('now')") == 'now'
      and uval(srv, 'admin@x.ru', 'select public.iva_staff_publish()') == 'now'
      and uval(srv, 'admin2@x.ru', 'select public.iva_staff_publish()') == 'approve')
add(srv, 'petr@x.ru', 'Лобзик')
L = tid(srv, 'Лобзик', 'admin@x.ru')
check('в режиме «сразу» новый инструмент сотрудника виден сразу', tool(srv, L) == 'admin@x.ru|Склад|true||Пётр', tool(srv, L))
check('сотруднику режим не виден', uval(srv, 'petr@x.ru', 'select coalesce(public.iva_staff_publish(), \'∅\')') == '∅')

# ── 8. фото ──
check('Пётр загружает фото инструмента', as_(srv, 'petr@x.ru', "insert into storage.objects(bucket_id, name) values ('tool-photos', 'tools/t1-0.jpg')")[0] == 'OK')
check('Пётр заменяет своё фото (x-upsert)', as_(srv, 'petr@x.ru', "update storage.objects set name = name where name = 'tools/t1-0.jpg'")[1].count('UPDATE 1') == 1)
q(srv, "insert into storage.objects(bucket_id, name, owner) values ('tool-photos', 'tools/t0-0.jpg', gen_random_uuid())")
check('чужое фото Пётр не заменяет', 'UPDATE 0' in as_(srv, 'petr@x.ru', "update storage.objects set name = name where name = 'tools/t0-0.jpg'")[1])
check('Иван фото не загружает', as_(srv, 'ivan@x.ru', "insert into storage.objects(bucket_id, name) values ('tool-photos', 'tools/t2-0.jpg')")[0] == 'ERR')
check('Пётр — только папка tools/ хранилища фото',
      as_(srv, 'petr@x.ru', "insert into storage.objects(bucket_id, name) values ('avatars', 'tools/x.jpg')")[0] == 'ERR'
      and as_(srv, 'petr@x.ru', "insert into storage.objects(bucket_id, name) values ('tool-photos', 'other/x.jpg')")[0] == 'ERR')
check('владелец загружает как раньше', as_(srv, 'admin@x.ru', "insert into storage.objects(bucket_id, name) values ('tool-photos', 'tools/t3-0.jpg')")[0] == 'OK')

# ── 9. удалить, доступ пропадает ──
as_(srv, 'petr@x.ru', "delete from public.tools where id = '%s'" % L)
check('Пётр удаляет инструмент своего пункта', val(srv, "select count(*) from public.tools where id = '%s'" % L) == '0')
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
check('прокат выключен — Пётр ничего не видит и не выкладывает', staff_list(srv, 'petr@x.ru') == '' and add(srv, 'petr@x.ru', 'Пока выключен')[0] == 'ERR')
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")
save(srv, 'admin@x.ru', PETR, 'petr@x.ru', 'Пётр', [SKLAD], True, False, False, False, False)
check('сняли «Каталог» — сразу не выкладывает и не меняет',
      add(srv, 'petr@x.ru', 'Без права')[0] == 'ERR' and staff_list(srv, 'petr@x.ru') == ''
      and as_(srv, 'petr@x.ru', "update public.tools set price = 3 where id = '%s'" % NEW)[0] == 'OK'
      and val(srv, "select price from public.tools where id = '%s'" % NEW) == '500')
save(srv, 'admin@x.ru', PETR, 'petr@x.ru', 'Пётр', [SKLAD], False, True, False, False, False)
as_(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % PETR)
check('«Убрать из сотрудников» — доступ пропал, инструменты остались у проката',
      staff_list(srv, 'petr@x.ru') == '' and add(srv, 'petr@x.ru', 'После ухода')[0] == 'ERR'
      and tool(srv, NEW) == 'admin@x.ru|Склад|true||')

# ── 10. откат из шапки, повтор ──
check('в шапке есть блок отката', RB.count('DROP FUNCTION') == 8 and RB.count('DROP POLICY') == 7 and 'NOTIFY pgrst' in RB, RB)
st, out = q(srv, RB)
check('откат выполняется без ошибок', st == 'OK', out[-600:])
check('после отката: функций, правил и столбцов нет, инструменты на месте',
      val(srv, "select count(*) from pg_proc where proname in ('iva_staff_tools', 'iva_tool_review', 'iva_tools_pending', 'iva_staff_publish', "
               "'iva_staff_publish_set', 'iva_staff_tool_ok', 'iva_staff_catalog', 'iva_tools_staff_before')") == '0'
      and val(srv, "select count(*) from pg_policies where policyname like 'tools_staff%' or policyname like 'iva_staff_tool_photos%'") == '0'
      and val(srv, "select count(*) from information_schema.columns where column_name in ('staff_id', 'review', 'staff_publish') "
               "and table_name in ('tools', 'admins')") == '0'
      and val(srv, "select owner_email || '|' || active from public.tools where id = '%s'" % DRILL) == 'admin@x.ru|true'
      and val(srv, "select count(*) from public.tools where owner_email = 'admin@x.ru'") == '7')
check('после отката владелец выкладывает как раньше', add(srv, 'admin@x.ru', 'После отката')[0] == 'OK')
st, out = q(srv, SQL)
check('после отката применяется снова', st == 'OK' and out.count('✅') == 5, out[-600:])
srv.cleanup()

# ── 11. без SQL 10.104 — понятная ошибка; без хранилища — применяется ──
srv = mk()
q(srv, BASELINE + EXTRA + PROD)
for s in (RENT, PICK, TEL, TP, OP, STAFF):
    q(srv, s)
st, out = q(srv, SQL)
check('без SQL 10.104 — понятная ошибка', st == 'ERR' and 'Сначала примените 20261029_staff_orders.sql' in out, out[-400:])
q(srv, SO)
st, out = q(srv, SQL)
check('без хранилища (storage) — применяется, фото-правила пропущены', st == 'OK' and out.count('✅') == 5 and 'Хранилища нет' in out, out[-600:])
srv.cleanup()

print('\nИтого: %s' % ('всё ✔' if not fails else '%d ✘' % fails))
sys.exit(1 if fails else 0)

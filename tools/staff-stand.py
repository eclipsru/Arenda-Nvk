# Стенд этапа 3а «Сотрудники и пункты выдачи» (10.103): 20261028_staff.sql на настоящем PostgreSQL (pgserver).
#
# База: общая BASELINE стендов + пункты выдачи (20261023 + 20261025), затем 20261028 (дважды).
# Проверяет: приглашение владельцем (только свои пункты, хотя бы одно право и пункт, имя, телефон, почта); владельца
# проката, себя и чужого сотрудника пригласить нельзя; клиент и гость не приглашают; напрямую в таблицы не пишет никто;
# кто что видит (владелец — своих, сотрудник — себя, создатель — всех, другой прокат — никого); сотруднику — приглашение
# с названием проката и своими пунктами, без почты владельца; «Принять» / «Отказаться» — только своё; повторное
# приглашение после отказа; правка прав и пунктов (без пропажи сотрудника); чужой прокат не меняет и не убирает;
# «Убрать» — доступ пропадает сразу; удалили пункт — сотрудник открепляется, последний пункт — убирается; владелец
# выключен — приглашения не видно; откат из шапки; повторное применение; без SQL 10.95 — понятная ошибка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-stand.py
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


def const(path, name):
    for node in ast.parse(rd(path)).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and getattr(node.targets[0], 'id', '') == name:
            return ast.literal_eval(node.value)
    raise SystemExit('нет %s в %s' % (name, path))


PICK = rd('supabase/migrations/20261023_pickup_points.sql')
TEL = rd('supabase/migrations/20261025_pickup_tel.sql')
SQL = rd('supabase/migrations/20261028_staff.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
# откат — строки «--      …» блока «ОТКАТ» в шапке файла
RB = '\n'.join(l[2:].strip() for l in SQL.split('--  ОТКАТ (', 1)[1].split('-- ====', 1)[0].splitlines()[1:] if l.startswith('--  '))
EXTRA = r"""
alter table public.admins add column company text;
insert into public.admins values ('admin2@x.ru', 'admin', true, 'Прокат Искитим'), ('off@x.ru', 'admin', false, null);
update public.admins set company = 'Ива-Прокат' where email = 'admin@x.ru';
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


def ins_point(name, city, addr):
    return ("insert into public.iva_pickup_points(name, address, city, lat, lng, email) "
            "values ('%s', '%s', '%s', 55.0, 82.9, 'prokat@mail.ru')") % (name, addr, city)


def arr(*ids):
    return "array[%s]::uuid[]" % ', '.join("'%s'" % i for i in ids) if ids else "'{}'::uuid[]"


def save(srv, owner, sid, email, name, tel, pts, rights=(True, False, True, True), self_issue=False):
    p = "null" if sid is None else "'%s'" % sid
    r = ', '.join('true' if x else 'false' for x in rights)
    return as_(srv, owner, "select public.iva_staff_save(%s, '%s', '%s', '%s', %s, %s, %s)" %
               (p, email, name, tel, arr(*pts), r, 'true' if self_issue else 'false'))


def sid_of(srv, email):
    return val(srv, "select id from public.iva_staff where email = '%s'" % email)


def err_has(res, text):
    return res[0] == 'ERR' and text in res[1]


# ── 1. база, пункты, 10.103 (дважды) ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA)
check('исходная база (admins, создатель, прокаты)', st == 'OK', out[-600:])
check('20261023 + 20261025 (пункты выдачи) применяются', all(q(srv, s)[0] == 'OK' for s in (PICK, TEL)))
st, out = q(srv, SQL)
check('20261028_staff.sql применяется, проверка — 7 «✅»', st == 'OK' and out.count('✅') == 7 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск ничего не ломает', st == 'OK' and out.count('✅') == 7, out[-600:])
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Пушкина, 3'))
as_(srv, 'admin@x.ru', "update public.iva_pickup_points set tel = '+7 383 000-00-01' where name = 'Склад'")
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
BERDSK = val(srv, "select id from public.iva_pickup_points where name = 'Бердск'")
ISK = val(srv, "select id from public.iva_pickup_points where name = 'Искитим'")

# ── 2. приглашение и проверки владельцем ──
r = save(srv, 'admin@x.ru', None, ' Ivan@X.ru ', '  Иван   Петров ', '+7 900 123-45-67', [SKLAD])
check('владелец приглашает сотрудника в свой пункт', r[0] == 'OK', r[1][-300:])
IVAN = sid_of(srv, 'ivan@x.ru')
check('почта — строчными, имя — без лишних пробелов, состояние «приглашён», права как отмечены',
      val(srv, "select email || '|' || name || '|' || tel || '|' || status || '|' || can_issue || can_catalog || can_chat || can_notify || self_issue "
               "from public.iva_staff where id = '%s'" % IVAN) == 'ivan@x.ru|Иван Петров|+7 900 123-45-67|invited|truefalsetruetruefalse')
lst = uval(srv, 'admin@x.ru', "select email || '|' || status || '|' || point_ids::text from public.iva_staff_list()")
check('владелец видит его в своём списке с пунктом', lst == 'ivan@x.ru|invited|{%s}' % SKLAD, lst)
check('без пункта — «Отметьте хотя бы один пункт выдачи»', err_has(save(srv, 'admin@x.ru', None, 'a1@x.ru', 'А', '', []), 'хотя бы один пункт'))
check('чужой пункт — «Пункт выдачи не найден у вашего проката»', err_has(save(srv, 'admin@x.ru', None, 'a2@x.ru', 'А', '', [SKLAD, ISK]), 'не найден у вашего проката'))
check('без прав — «Отметьте хотя бы одно право»', err_has(save(srv, 'admin@x.ru', None, 'a3@x.ru', 'А', '', [SKLAD], (False, False, False, False)), 'хотя бы одно право'))
check('без имени — ошибка', err_has(save(srv, 'admin@x.ru', None, 'a4@x.ru', '   ', '', [SKLAD]), 'имя сотрудника'))
check('плохой телефон — ошибка', err_has(save(srv, 'admin@x.ru', None, 'a5@x.ru', 'А', '12-34', [SKLAD]), 'Проверьте телефон'))
check('плохая почта — ошибка', err_has(save(srv, 'admin@x.ru', None, 'не почта', 'А', '', [SKLAD]), 'в email ошибка'))
check('свою почту — нельзя', err_has(save(srv, 'admin@x.ru', None, 'Admin@x.ru', 'А', '', [SKLAD]), 'ваша почта'))
check('владельца проката — нельзя', err_has(save(srv, 'admin@x.ru', None, 'admin2@x.ru', 'А', '', [SKLAD]), 'Это владелец проката'))
check('создателя — нельзя', err_has(save(srv, 'admin@x.ru', None, 'chief@x.ru', 'А', '', [SKLAD]), 'Это владелец проката'))
check('чужого сотрудника — нельзя («уже сотрудник другого проката»)', err_has(save(srv, 'admin2@x.ru', None, 'ivan@x.ru', 'И', '', [ISK]), 'другого проката'))
check('клиент не приглашает', err_has(save(srv, 'client@x.ru', None, 'a6@x.ru', 'А', '', [SKLAD]), 'владелец проката'))
check('выключенный прокат не приглашает', err_has(save(srv, 'off@x.ru', None, 'a7@x.ru', 'А', '', [SKLAD]), 'владелец проката'))
st, out = as_(srv, '', "select public.iva_staff_save(null, 'a8@x.ru', 'А', '', array['%s']::uuid[], true, false, false, false, false)" % SKLAD, role='anon')
check('гость не вызывает функции сотрудников', st == 'ERR' and 'permission denied' in out, out[-200:])
check('ошибки ничего не добавили', val(srv, "select count(*) from public.iva_staff") == '1')

# ── 3. напрямую — никто ──
for name, sql in (('добавить', "insert into public.iva_staff(owner_email, email, name) values ('admin@x.ru', 'x@x.ru', 'X')"),
                  ('изменить', "update public.iva_staff set can_catalog = true"),
                  ('удалить', "delete from public.iva_staff"),
                  ('прикрепить к пункту', "insert into public.iva_staff_points values ('%s', '%s')" % (IVAN, BERDSK))):
    st, out = as_(srv, 'admin@x.ru', sql)
    check('владелец не может %s напрямую (только функциями)' % name, st == 'ERR' and 'permission denied' in out, out[-200:])
st, out = as_(srv, 'ivan@x.ru', "update public.iva_staff set can_catalog = true, status = 'active'")
check('сотрудник не меняет себе права и состояние', st == 'ERR' and 'permission denied' in out)

# ── 4. кто что видит ──
check('владелец видит своего сотрудника и его пункты', uval(srv, 'admin@x.ru', "select (select count(*) from public.iva_staff) || '|' || (select count(*) from public.iva_staff_points)") == '1|1')
check('другой прокат не видит ни сотрудника, ни пунктов', uval(srv, 'admin2@x.ru', "select (select count(*) from public.iva_staff) || '|' || (select count(*) from public.iva_staff_points) || '|' || (select count(*) from public.iva_staff_list())") == '0|0|0')
check('клиент не видит', uval(srv, 'client@x.ru', "select (select count(*) from public.iva_staff) || '|' || (select count(*) from public.iva_staff_list())") == '0|0')
check('сам сотрудник видит только себя', uval(srv, 'ivan@x.ru', "select count(*) || '|' || min(name) from public.iva_staff") == '1|Иван Петров')
check('создатель видит всех', uval(srv, 'chief@x.ru', "select count(*) from public.iva_staff") == '1')
st, out = as_(srv, '', "select count(*) from public.iva_staff", role='anon')
check('гость не видит', st == 'ERR' and 'permission denied' in out)

# ── 5. приглашение у сотрудника ──
me = uval(srv, 'IVAN@x.ru', "select row_to_json(m)::text from public.iva_staff_me() m")
check('сотрудник видит приглашение: прокат, права, свои пункты (с телефоном пункта)',
      '"status":"invited"' in me and '"company":"Ива-Прокат"' in me and '"name": "Склад"' in me and '+7 383 000-00-01' in me
      and '"can_issue":true' in me and '"can_catalog":false' in me, me)
check('… без почты владельца, своей почты, имени и телефона', 'admin@x.ru' not in me and 'prokat@mail.ru' not in me and 'Петров' not in me and '123-45-67' not in me, me)
check('у другого человека приглашения нет', uval(srv, 'client@x.ru', "select count(*) from public.iva_staff_me()") == '0')
check('чужое приглашение принять нельзя', err_has(as_(srv, 'client@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN), 'не найдено'))
check('владелец не принимает за сотрудника', err_has(as_(srv, 'admin@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN), 'не найдено'))
check('«Принять» — работает', uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN) == 'active'
      and val(srv, "select status || '|' || (answered_at is not null) from public.iva_staff where id = '%s'" % IVAN) == 'active|true')
check('повторное «Принять» — без ошибки', uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN) == 'active')
check('работающий сам не уходит (убирает владелец)', err_has(as_(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', false)" % IVAN), 'убрать вас может владелец'))
check('владелец видит «работает»', uval(srv, 'admin@x.ru', "select status from public.iva_staff_list()") == 'active')

# ── 6. правка ──
r = save(srv, 'admin@x.ru', IVAN, 'другая@x.ru', 'Иван', '', [SKLAD, BERDSK], (True, True, False, False), True)
check('правка: права, «самому себе», два пункта; почта не меняется',
      r[0] == 'OK' and val(srv, "select email || '|' || name || '|' || tel || '|' || status || '|' || can_issue || can_catalog || can_chat || can_notify || self_issue || '|' || "
                                "(select count(*) from public.iva_staff_points where staff_id = s.id) from public.iva_staff s where id = '%s'" % IVAN)
      == 'ivan@x.ru|Иван||active|truetruefalsefalsetrue|2', r[1][-300:])
r = save(srv, 'admin@x.ru', IVAN, '', 'Иван', '', [BERDSK])
check('перенос в другой пункт — сотрудник не пропадает', r[0] == 'OK' and val(srv, "select string_agg(point_id::text, ',') from public.iva_staff_points where staff_id = '%s'" % IVAN) == BERDSK
      and val(srv, "select status from public.iva_staff where id = '%s'" % IVAN) == 'active')
check('чужой прокат не меняет', err_has(save(srv, 'admin2@x.ru', IVAN, '', 'Х', '', [ISK]), 'не найден'))
check('чужой прокат не убирает', err_has(as_(srv, 'admin2@x.ru', "select public.iva_staff_remove('%s')" % IVAN), 'не найден'))
check('… сотрудник на месте', val(srv, "select name from public.iva_staff where id = '%s'" % IVAN) == 'Иван')

# ── 7. отказ и повторное приглашение ──
save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', '', [SKLAD])
PETR = sid_of(srv, 'petr@x.ru')
check('«Отказаться» — отказался', uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', false)" % PETR) == 'declined')
check('после отказа приглашения не видно', uval(srv, 'petr@x.ru', "select count(*) from public.iva_staff_me()") == '0')
check('владелец видит «отказался»', uval(srv, 'admin@x.ru', "select status from public.iva_staff_list() where email = 'petr@x.ru'") == 'declined')
check('отказавшегося может пригласить другой прокат', save(srv, 'admin2@x.ru', None, 'petr@x.ru', 'Пётр', '', [ISK])[0] == 'OK')
check('… тогда первый пригласить снова не может', err_has(save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', '', [SKLAD]), 'другого проката'))
check('… и принять старое приглашение нельзя', err_has(as_(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR), 'не найдено'))
P2 = val(srv, "select id from public.iva_staff where email = 'petr@x.ru' and owner_email = 'admin2@x.ru'")
check('второй прокат убирает своего', as_(srv, 'admin2@x.ru', "select public.iva_staff_remove('%s')" % P2)[0] == 'OK')
check('первый приглашает снова — снова «приглашён»', save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', '', [SKLAD])[0] == 'OK'
      and val(srv, "select status || '|' || (answered_at is null) from public.iva_staff where id = '%s'" % PETR) == 'invited|true')
st, out = q(srv, "insert into public.iva_staff(owner_email, email, name) values ('admin2@x.ru', 'petr@x.ru', 'П')")
check('один человек — у одного проката (защита в самой базе)', st == 'ERR' and 'iva_staff_one_owner' in out, out[-200:])

# ── 8. удаление пункта ──
save(srv, 'admin@x.ru', None, 'olga@x.ru', 'Ольга', '', [SKLAD, BERDSK])
OLGA = sid_of(srv, 'olga@x.ru')
st, out = as_(srv, 'admin@x.ru', "delete from public.iva_pickup_points where id = '%s'" % BERDSK)
check('пункт с сотрудниками удаляется', st == 'OK', out[-300:])
check('… сотрудник только этого пункта убран, с двумя пунктами — откреплён',
      val(srv, "select count(*) from public.iva_staff where id = '%s'" % IVAN) == '0'
      and val(srv, "select string_agg(point_id::text, ',') from public.iva_staff_points where staff_id = '%s'" % OLGA) == SKLAD)
check('… у убранного приглашения больше нет', uval(srv, 'ivan@x.ru', "select count(*) from public.iva_staff_me()") == '0')

# ── 9. убрать, владелец выключен ──
uval(srv, 'olga@x.ru', "select public.iva_staff_answer('%s', true)" % OLGA)
check('«Убрать из сотрудников» — доступ пропадает сразу',
      as_(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % OLGA)[0] == 'OK'
      and uval(srv, 'olga@x.ru', "select (select count(*) from public.iva_staff_me()) || '|' || (select count(*) from public.iva_staff)") == '0|0'
      and val(srv, "select count(*) from public.iva_staff_points where staff_id = '%s'" % OLGA) == '0')
check('убрать второй раз — понятная ошибка', err_has(as_(srv, 'admin@x.ru', "select public.iva_staff_remove('%s')" % OLGA), 'не найден'))
q(srv, "update public.admins set active = false where email = 'admin@x.ru'")
check('прокат выключен — приглашения не видно, приглашать нельзя',
      uval(srv, 'petr@x.ru', "select count(*) from public.iva_staff_me()") == '0'
      and err_has(save(srv, 'admin@x.ru', None, 'z@x.ru', 'З', '', [SKLAD]), 'владелец проката'))
q(srv, "update public.admins set active = true where email = 'admin@x.ru'")
check('нигде не затронуты чужие таблицы (пункты владельца на месте)', val(srv, "select count(*) from public.iva_pickup_points") == '2')

# ── 10. откат и повторное применение ──
check('в шапке есть блок отката', 'DROP TABLE IF EXISTS public.iva_staff;' in RB and 'iva_staff_save' in RB, RB[:300])
st, out = q(srv, RB)
check('откат выполняется и убирает таблицы и функции', st == 'OK' and val(srv, "select count(*) from pg_proc where proname like 'iva\\_staff%'") == '0'
      and val(srv, "select to_regclass('public.iva_staff') is null") == 't', out[-400:])
check('… пункты выдачи не тронуты', val(srv, "select count(*) from public.iva_pickup_points") == '2')
st, out = q(srv, SQL)
check('после отката снова применяется', st == 'OK' and out.count('✅') == 7, out[-400:])
srv.cleanup()

# ── 11. без SQL 10.95 — понятная ошибка ──
srv = mk()
q(srv, BASELINE + EXTRA)
st, out = q(srv, SQL)
check('без 20261023 — останавливается с понятной ошибкой', st == 'ERR' and 'Сначала примените 20261023_pickup_points.sql' in out, out[-300:])
check('… и ничего не создаёт', val(srv, "select to_regclass('public.iva_staff') is null") == 't')
srv.cleanup()

print('ИТОГ: ' + ('все проверки пройдены' if fails == 0 else 'ошибок: %d' % fails))
sys.exit(1 if fails else 0)

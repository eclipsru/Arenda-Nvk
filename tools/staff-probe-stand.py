# Стенд 10.118 «Сотрудник: база не пустила запись, хотя права есть»: 20261108_staff_publish_probe.sql на настоящем
# PostgreSQL.
#
# Стенд А — база как в стенде 10.116 (все файлы сотрудников + 20261107). Проверяет: файл применяется и повторяется;
# пробная запись сотрудника (iva_staff_publish_try) проходит и НИЧЕГО не оставляет (ни объявления, ни отметки
# владельцу, ни push); обычная публикация не меняется (триггер пробы спит); причины 20261107 (нет права, админ) —
# как раньше. Затем ломаем базу так, как она может отличаться от файлов, — и проба называет точную причину:
# ограничивающее правило (RESTRICTIVE), чужой триггер подменяет владельца, правило tools_staff_insert другое,
# триггер останавливает запись, нет GRANT INSERT. Таблица в конце файла показывает то же за каждого сотрудника,
# все правила tools и триггеры по порядку. Гость пробу не запускает; откат из шапки.
# Стенд Б — база без 20261107: понятная остановка.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/staff-probe-stand.py
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
SQL = rd('supabase/migrations/20261108_staff_publish_probe.sql')
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


# ═══════════════ Стенд А ═══════════════
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD + PRODX + PUB)
check('исходная база как в бою (с хранилищем и Realtime)', st == 'OK', out[-600:])
check('SQL 10.77–10.116 и admins как в бою применяются',
      all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PING, PUBL, PICKF, LIVE, CHK)))

st, out = qs(srv)
check('20261108_staff_publish_probe.sql применяется без ошибок', st == 'OK', out[-900:])
check('таблица: функции пробы ✅, триггер сотрудника 20261105, RLS включена, INSERT есть, сотрудников ещё нет',
      st == 'OK' and 'пробная запись — функции и триггер пробы | ✅' in out and '✅ 20261105' in out
      and '✅ включена' in out and 'GRANT INSERT) | ✅' in out and 'нет сотрудников с правом' in out, out[-1500:])
check('таблица: правила tools и триггеры по порядку (zzz_iva_probe — последний)',
      'правило tools: tools_staff_insert (INSERT, разрешающее, authenticated)' in out
      and 'перед записью (по порядку): iva_tools_owner_default' in out
      and out.index('trg_iva_staff_tool |') < out.index('trg_iva_tool_point |') < out.index('zzz_iva_probe |'), out[-1500:])
st, out = qs(srv)
check('повторный запуск — без ошибок', st == 'OK' and '❌' not in out, out[-600:])

as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск, улица Свердлова, 7', 'now'))
SKLAD, BERDSK = pid(srv, 'Склад'), pid(srv, 'Бердск')
PETR = save(srv, 'admin@x.ru', None, 'petr@x.ru', 'Пётр', [SKLAD, BERDSK], False, True)
IVAN = save(srv, 'admin@x.ru', None, 'ivan@x.ru', 'Иван', [SKLAD], True, False)
uval(srv, 'petr@x.ru', "select public.iva_staff_answer('%s', true)" % PETR)
uval(srv, 'ivan@x.ru', "select public.iva_staff_answer('%s', true)" % IVAN)
check('сотрудники созданы и приняли', len(PETR) == 36 and len(IVAN) == 36 and code(srv, 'petr@x.ru') == 'ok', (PETR, IVAN))

q(srv, "create table public.push_log(kind text, email text); "
       "create function public.iva_push_enqueue(p_kind text, p_email text) returns void language sql security definer "
       "set search_path to 'public' as $$ insert into public.push_log values (p_kind, p_email) $$")
n0, p0 = ntools(srv), ping(srv, 'admin@x.ru')
check('проба Петра: запись проходит', tcode(srv, 'petr@x.ru') == 'ok', ttext(srv, 'petr@x.ru'))
check('и ничего не осталось: ни объявления, ни отметки владельцу, ни push, ни очереди цензуры',
      ntools(srv) == n0 and ping(srv, 'admin@x.ru') == p0 and val(srv, 'select count(*) from public.push_log') == '0'
      and val(srv, "select count(*) from public.tools where name = 'Проверка публикации'") == '0',
      (ntools(srv), n0, ping(srv, 'admin@x.ru'), p0))
mv, (st, out) = moved(srv, 'admin@x.ru', lambda: add(srv, 'petr@x.ru', 'Дрель склад'))
check('обычная публикация сотрудника — как в 10.116 (триггер пробы спит)',
      st == 'OK' and tool(srv, 'Дрель склад') == 'admin@x.ru|Склад|false|pending' and mv, (tool(srv, 'Дрель склад'), out[-300:]))
check('и у владельца — как раньше', add(srv, 'admin@x.ru', 'Своя дрель')[0] == 'OK' and tool(srv, 'Своя дрель') == 'admin@x.ru|Склад|true|')
check('причины 20261107 — без пробы: нет права / админ / не сотрудник',
      tcode(srv, 'ivan@x.ru') == 'no_catalog' and tcode(srv, 'admin@x.ru') == 'admin' and tcode(srv, 'client@x.ru') == 'not_staff')
check('ответ — код, фраза, пункт, ответ базы (без почт)',
      uval(srv, 'petr@x.ru', "select string_agg(k, ',' order by k) from jsonb_object_keys(public.iva_staff_publish_try()) k") == 'code,point,raw,text'
      and '@' not in ttext(srv, 'petr@x.ru'))
check('гость пробу не запускает', as_(srv, '', 'select public.iva_staff_publish_try()', role='anon')[0] == 'ERR'
      and as_(srv, '', "select public.iva_staff_probe_row('{}')", role='anon')[0] == 'ERR')
st, out = qs(srv)
check('таблица: проба за Петра ✅, Ивана (без права) нет', st == 'OK' and 'проба записи: сотрудник petr@x.ru | ✅' in out
      and 'ivan@x.ru' not in out, out[-1500:])
check('проба из таблицы тоже ничего не оставила', ntools(srv) == val(srv, 'select count(*) from public.tools')
      and val(srv, "select count(*) from public.tools where name = 'Проверка публикации'") == '0')

# ── ломаем базу, как она может отличаться от файлов ──
# 1) ограничивающее правило: «объявление только в свою почту»
q(srv, "create policy only_own on public.tools as restrictive for insert to authenticated "
       "with check (lower(owner_email) = public.jwt_email())")
st, out = add(srv, 'petr@x.ru', 'При ограничении')
check('RESTRICTIVE: сотрудник действительно не может (как на снимке владельца)', st == 'ERR' and 'row-level security' in out
      and '"tools"' in out, out[-300:])
check('проба называет правило', tcode(srv, 'petr@x.ru') == 'restrictive' and 'only_own' in ttext(srv, 'petr@x.ru')
      and '42501' in ttext(srv, 'petr@x.ru'), ttext(srv, 'petr@x.ru'))
check('у владельца — пускает (его почта)', add(srv, 'admin@x.ru', 'Своя 2')[0] == 'OK')
st, out = qs(srv)
check('таблица: ❌ у Петра с именем правила и ⚠️ ОГРАНИЧИВАЮЩЕЕ в списке правил',
      st == 'OK' and 'сотрудник petr@x.ru | ❌' in out and 'only_own' in out and 'ОГРАНИЧИВАЮЩЕЕ' in out
      and '| ⚠️ проверка записи' in out, out[-1800:])
q(srv, 'drop policy only_own on public.tools')
check('правило убрали — проба снова ✅', tcode(srv, 'petr@x.ru') == 'ok')

# 2) чужой триггер после триггера сотрудника подменяет владельца на вошедшего
q(srv, "create function public.x_owner() returns trigger language plpgsql as $$ begin "
       "new.owner_email := public.jwt_email(); return new; end $$; "
       "create trigger trg_x_owner before insert on public.tools for each row execute function public.x_owner()")
st, out = add(srv, 'petr@x.ru', 'При чужом триггере')
check('чужой триггер: сотрудник не может', st == 'ERR' and 'row-level security' in out, out[-300:])
check('проба: владелец не ваш прокат, подставилась ваша почта, называет триггер',
      tcode(srv, 'petr@x.ru') == 'policy' and 'владелец объявления — не ваш прокат' in ttext(srv, 'petr@x.ru')
      and 'подставилась ваша почта' in ttext(srv, 'petr@x.ru') and 'trg_x_owner' in ttext(srv, 'petr@x.ru'), ttext(srv, 'petr@x.ru'))
st, out = qs(srv)
check('таблица: ⚠️ trg_x_owner «не из Ивы»', st == 'OK' and 'trg_x_owner | ⚠️ не из «Ивы»: x_owner' in out, out[-1500:])
q(srv, 'drop trigger trg_x_owner on public.tools')

# 3) правило tools_staff_insert в базе другое
q(srv, "drop policy tools_staff_insert on public.tools; "
       "create policy tools_staff_insert on public.tools for insert to authenticated with check (false)")
check('другое tools_staff_insert: проба говорит, что данные верные, а правило отличается',
      tcode(srv, 'petr@x.ru') == 'policy' and 'отличается от обновления 20261030' in ttext(srv, 'petr@x.ru'), ttext(srv, 'petr@x.ru'))
q(srv, ST)
check('20261030 ещё раз — правило прежнее, проба ✅', tcode(srv, 'petr@x.ru') == 'ok')
st, out = qs(srv)
check('но триггер сотрудника откатился к 20261030 — таблица это показывает', '⚠️ 20261030 (старая)' in out, out[-1200:])
q(srv, PUBL)
q(srv, PICKF)

# 4) триггер останавливает запись
q(srv, "create function public.x_stop() returns trigger language plpgsql as $$ begin "
       "raise exception 'Стоп: проверка склада'; end $$; "
       "create trigger trg_x_stop before insert on public.tools for each row execute function public.x_stop()")
check('триггер-стоп: проба приводит его текст', tcode(srv, 'petr@x.ru') == 'trigger' and 'Стоп: проверка склада' in ttext(srv, 'petr@x.ru'),
      ttext(srv, 'petr@x.ru'))
q(srv, 'drop trigger trg_x_stop on public.tools')

# 5) нет права INSERT на таблицу
q(srv, 'revoke insert on public.tools from authenticated')
check('нет GRANT INSERT: проба — «нет права записи»', tcode(srv, 'petr@x.ru') == 'grant', ttext(srv, 'petr@x.ru'))
st, out = qs(srv)
check('таблица: GRANT INSERT ❌', st == 'OK' and 'GRANT INSERT) | ❌ нет' in out, out[-900:])
q(srv, 'grant insert on public.tools to authenticated')
check('право вернули — проба ✅', tcode(srv, 'petr@x.ru') == 'ok')

# 6) выбранный в форме пункт (iva_staff_pick) — проба идёт туда же, куда пойдёт объявление
uval(srv, 'petr@x.ru', "select public.iva_staff_pick_point('%s')" % BERDSK)
check('проба — в пункт, выбранный в форме («Бердск»)', tcode(srv, 'petr@x.ru') == 'ok'
      and uval(srv, 'petr@x.ru', "select public.iva_staff_publish_try() ->> 'point'") == 'Бердск')

# откат
st, out = q(srv, RB)
check('откат из шапки выполняется', st == 'OK' and 'DROP TRIGGER' in RB and 'DROP FUNCTION' in RB, out[-600:])
check('после отката пробы и триггера нет, 20261107 на месте',
      val(srv, "select (to_regprocedure('public.iva_staff_publish_try()') is null)::text "
               "|| (select count(*) from pg_trigger where tgname = 'zzz_iva_probe')::text "
               "|| (to_regprocedure('public.iva_staff_publish_why()') is not null)::text") == 'true0true')
check('и сотрудник выкладывает, как в 10.116 (в выбранный в форме «Бердск», режим «сразу»)', add(srv, 'petr@x.ru', 'После отката')[0] == 'OK'
      and tool(srv, 'После отката') == 'admin@x.ru|Бердск|true|', tool(srv, 'После отката'))
st, out = qs(srv)
check('повторное применение после отката — без ошибок', st == 'OK' and tcode(srv, 'petr@x.ru') == 'ok', out[-600:])
srv.cleanup()

# ═══════════════ Стенд Б: без 20261107 ═══════════════
srv = mk()
q(srv, BASELINE + EXTRA + PROD + PRODX + PUB)
ok = all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP, OP, STAFF, SO, ST, ADM, MANAGE, PING))
st, out = qs(srv)
check('без SQL 10.116 — понятная остановка', ok and st == 'ERR' and '20261107_staff_publish_check.sql' in out, out[-400:])
check('и ничего не создано', val(srv, "select (to_regprocedure('public.iva_staff_publish_try()') is null)::text "
                                      "|| (select count(*) from pg_trigger where tgname = 'zzz_iva_probe')::text") == 'true0')
srv.cleanup()

print('Итог: %d ошибок' % fails)
sys.exit(1 if fails else 0)

# Стенд этапа 2 «Сотрудники и пункты выдачи» (10.102): 20261027_order_points.sql на настоящем PostgreSQL (pgserver).
#
# База как в бою: общая BASELINE стендов + EXTRA стенда 10.101 (адрес у инструмента, владелец по входу), сверх них —
# заявки ТОЧНО как в рабочей базе (столбцы, триггеры, правила доступа и тексты функций сняты 17.10.2026 запросом
# tools/diag-orders.sql: submit_order, fn_split_order, fn_fee_accrue, fn_op_snap_fee_pct, demo_no_fee, пуш на часть),
# затем 20261014_rent_end_mode (10.77), пункты выдачи (20261023 + 20261025), 20261026 и 20261027 (дважды).
# Проверяет: заявка со списком делится по «прокат + пункт», чужому прокату с похожим названием ничего не уходит,
# одна модель в двух пунктах — часть только того пункта; пуш — по части (на каждый пункт); список чистится (повторы,
# не-id, выключенный инструмент, срок 1…365); заявка без списка (сайт) — как раньше, часть на прокат, пункт — если он
# один; комиссия — Σ цена × свой срок, цена на момент заявки; без списка — только инструменты своего проката;
# «демо без комиссии» и переходы статусов — как были; список и пункт части прокат не меняет; удаление пункта снимает
# его с частей; «До конца аренды» — график пункта и срок части; пункты заявок — только своему клиенту; без SQL 10.101 —
# понятная ошибка; откат из 20261027_order_points_rollback.sql возвращает прежние функции; повторное применение.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/order-points-stand.py
import ast
import json
import os
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


RENT = rd('supabase/migrations/20261014_rent_end_mode.sql')
PICK = rd('supabase/migrations/20261023_pickup_points.sql')
TEL = rd('supabase/migrations/20261025_pickup_tel.sql')
TP = rd('supabase/migrations/20261026_tool_points.sql')
SQL = rd('supabase/migrations/20261027_order_points.sql')
RB = rd('supabase/migrations/20261027_order_points_rollback.sql')
BASELINE = const('tools/category-tree-stand.py', 'BASELINE')
EXTRA = const('tools/tool-points-stand.py', 'EXTRA')

# заявки — как в рабочей базе 17.10.2026 (tools/diag-orders.sql); пуш на часть — счётчик вместо сети
PROD = r"""
alter table public.admins add column fee_pct integer, add column full_name text, add column company text;
alter table public.tools add column price numeric;
create table public.orders(id bigserial primary key, created_at timestamptz default now() not null, name text, phone text,
  tools text, get_method text, address text, comment text, user_email text, status text default 'closed' not null, days integer);
create table public.order_parts(id bigserial primary key, order_id bigint not null references public.orders(id),
  owner_email text not null, tools_text text default '' not null, status text default 'new' not null,
  created_at timestamptz default now() not null, rent_sum numeric default 0 not null, fee numeric default 0 not null,
  price_snapshot jsonb, archived boolean default false not null, fee_pct integer);
create table public.order_events(id bigserial primary key, part_id bigint, old_status text, new_status text, email text);
create table public.iva_push_log(id bigserial primary key, kind text, email text);
create function public.fn_recalc_statuses() returns void language plpgsql security definer set search_path to 'public' as $$ begin end $$;
alter table public.orders enable row level security;
alter table public.order_parts enable row level security;
create policy orders_insert_anon on public.orders for insert to public with check (true);
create policy orders_select_own on public.orders for select to authenticated using (user_email = (auth.jwt() ->> 'email'::text));
create policy orders_select_admin2 on public.orders for select to authenticated using (fn_is_chief() or exists (select 1 from order_parts p
  where p.order_id = orders.id and lower(p.owner_email) = lower(jwt_email())));
create policy parts_select on public.order_parts for select to authenticated using (fn_is_chief() or lower(owner_email) = lower(jwt_email()));
create policy parts_update on public.order_parts for update to authenticated using (fn_is_chief() or lower(owner_email) = lower(jwt_email()))
  with check (fn_is_chief() or lower(owner_email) = lower(jwt_email()));

CREATE OR REPLACE FUNCTION public.submit_order(p_name text, p_phone text, p_tools text, p_get_method text DEFAULT ''::text, p_address text DEFAULT ''::text, p_comment text DEFAULT ''::text, p_user_email text DEFAULT ''::text, p_days integer DEFAULT NULL::integer)
 RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare new_id bigint;
begin
  insert into public.orders (name, phone, tools, get_method, address, comment, user_email, days)
  values (p_name, p_phone, p_tools, p_get_method, p_address, p_comment, nullif(p_user_email,''), p_days)
  returning id into new_id;
  return new_id;
end $function$;

CREATE OR REPLACE FUNCTION public.fn_split_order()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare o record; names text;
begin
  for o in select distinct coalesce(t.owner_email, 'eclips.ru@mail.ru') as em
            from public.tools t
            where t.active <> false and position(t.name in new.tools) > 0 loop
    select string_agg(distinct t2.name, ', ') into names
      from public.tools t2
      where t2.active <> false and position(t2.name in new.tools) > 0
        and coalesce(t2.owner_email, 'eclips.ru@mail.ru') = o.em;
    insert into public.order_parts(order_id, owner_email, tools_text, status)
    values (new.id, o.em, coalesce(names, ''), 'new');
  end loop;
  if not exists (select 1 from public.order_parts where order_id = new.id) then
    insert into public.order_parts(order_id, owner_email, tools_text, status)
    values (new.id, 'eclips.ru@mail.ru', coalesce(new.tools, ''), 'new');
  end if;
  perform public.fn_recalc_statuses();
  return new;
end $function$;

CREATE OR REPLACE FUNCTION public.fn_fee_accrue()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare d int; snap jsonb; rs numeric; pct int;
begin
  if new.status is distinct from old.status then
    if not ((old.status = 'new' and new.status in ('rented','closed')) or (old.status = 'rented' and new.status = 'closed')) then
      raise exception 'Недопустимый перевод статуса: % → %', old.status, new.status;
    end if;
  end if;
  if new.status = 'closed' and old.status is distinct from 'closed' and (new.fee is null or new.fee = 0) then
    select coalesce(days,1) into d from public.orders where id = new.order_id;
    d := coalesce(d,1);
    select coalesce(new.fee_pct, (select fee_pct from public.admins where lower(email)=lower(new.owner_email)), 5) into pct;
    select coalesce(jsonb_agg(jsonb_build_object('name', m.name, 'price', m.price)), '[]'::jsonb), coalesce(sum(m.price),0) * d into snap, rs
      from (select distinct t.name, t.price from public.tools t where t.active <> false and position(t.name in new.tools_text) > 0) m;
    if rs is not null and rs > 0 then
      new.price_snapshot := snap; new.rent_sum := rs;
      new.fee := round(rs * pct::numeric / 100.0);
    end if;
  end if;
  if new.status is distinct from old.status then
    insert into public.order_events(part_id, old_status, new_status, email) values (new.id, old.status, new.status, public.jwt_email());
  end if;
  return new;
end
$function$;

CREATE OR REPLACE FUNCTION public.fn_op_snap_fee_pct()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  if new.fee_pct is null then
    select coalesce(fee_pct, 5) into new.fee_pct from admins where lower(email)=lower(new.owner_email);
    if new.fee_pct is null then new.fee_pct := 5; end if;
  end if;
  return new;
end $function$;

CREATE OR REPLACE FUNCTION public.demo_no_fee()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  if exists (select 1 from public.orders o where o.id = new.order_id and lower(o.user_email) = 'eclipsik.ru@mail.ru') then
    new.fee := 0;
  end if;
  return new;
end;
$function$;

CREATE OR REPLACE FUNCTION public.iva_push_on_part()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  if coalesce(new.status, 'new') = 'new' then insert into public.iva_push_log(kind, email) values ('o', new.owner_email); end if;
  return new;
end;
$function$;

CREATE TRIGGER tr_op_fee_pct BEFORE INSERT ON public.order_parts FOR EACH ROW EXECUTE FUNCTION fn_op_snap_fee_pct();
CREATE TRIGGER trg_fee_accrue BEFORE UPDATE ON public.order_parts FOR EACH ROW EXECUTE FUNCTION fn_fee_accrue();
CREATE TRIGGER trg_iva_push_part AFTER INSERT ON public.order_parts FOR EACH ROW EXECUTE FUNCTION iva_push_on_part();
CREATE TRIGGER trg_zz_demo_no_fee BEFORE INSERT OR UPDATE ON public.order_parts FOR EACH ROW EXECUTE FUNCTION demo_no_fee();
CREATE TRIGGER trg_split_order AFTER INSERT ON public.orders FOR EACH ROW EXECUTE FUNCTION fn_split_order();
insert into public.admins values ('eclips.ru@mail.ru', 'chief', true, null, null, null);
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


def tid(srv, name, owner):
    return val(srv, "select id from public.tools where name = '%s' and owner_email = '%s'" % (name, owner))


def items(*pairs):
    return json.dumps([{'id': i, 'days': d} for i, d in pairs]).replace("'", "''")


def submit(srv, tools_text, its, email='client@x.ru', days=5, role='anon'):
    """Заявка из приложения (iva_submit_order) — номер заявки."""
    p = "null" if its is None else "'%s'::jsonb" % its
    return uval(srv, '' if role == 'anon' else email,
                "select public.iva_submit_order('Иван', '+79990000000', '%s', 'Самовывоз', '', '', '%s', %d, %s)" % (tools_text, email, days, p),
                role=role)


def parts(srv, oid):
    """Части заявки: владелец|пункт|инструменты, по порядку."""
    return val(srv, ("select string_agg(p.owner_email || '|' || coalesce(pp.name, '-') || '|' || p.tools_text, ' ; ' order by p.id) "
                     "from public.order_parts p left join public.iva_pickup_points pp on pp.id = p.point_id where p.order_id = %s") % oid)


def part_id(srv, oid, point):
    return val(srv, ("select p.id from public.order_parts p left join public.iva_pickup_points pp on pp.id = p.point_id "
                     "where p.order_id = %s and coalesce(pp.name, '-') = '%s' order by p.id limit 1") % (oid, point))


def setstatus(srv, email, pid, st):
    return as_(srv, email, "update public.order_parts set status = '%s' where id = %s" % (st, pid))


def money(srv, pid):
    return val(srv, "select rent_sum::numeric(12,0) || '|' || fee::numeric(12,0) from public.order_parts where id = %s" % pid)


# ── 1. база как в бою, 10.77, пункты, 10.101, 10.102 (дважды) ──
srv = mk()
st, out = q(srv, BASELINE + EXTRA + PROD)
check('исходная база как в бою (заявки, части, триггеры, правила доступа — по снимку 17.10)', st == 'OK', out[-600:])
check('20261014_rent_end_mode + 20261023 + 20261025 + 20261026 применяются',
      all(q(srv, s)[0] == 'OK' for s in (RENT, PICK, TEL, TP)))
as_(srv, 'admin@x.ru', ins_point('Склад', 'Новосибирск', 'Новосибирск, улица Ленина, 5'))
as_(srv, 'admin@x.ru', ins_point('Бердск', 'Бердск', 'Бердск, улица Свердлова, 7'))
as_(srv, 'admin2@x.ru', ins_point('Искитим', 'Искитим', 'Искитим, улица Пушкина, 3'))
as_(srv, 'admin@x.ru', "update public.iva_pickup_points set open_min = '{540,540,540,540,540,540,-1}', close_min = '{1200,1200,1200,1200,1200,1200,-1}', tel = '+7 383 000-00-01' where name = 'Бердск'")
SKLAD = val(srv, "select id from public.iva_pickup_points where name = 'Склад'")
BERDSK = val(srv, "select id from public.iva_pickup_points where name = 'Бердск'")
q(srv, ("insert into public.tools(name, owner_email, price, point_id) values "
        "('Дрель Makita', 'admin@x.ru', 300, '{s}'), ('Пила', 'admin@x.ru', 500, '{b}'),"
        "('Перфоратор', 'admin@x.ru', 400, '{s}'), ('Перфоратор', 'admin@x.ru', 400, '{b}'),"
        "('Дрель', 'admin2@x.ru', 100, null), ('Старый', 'admin@x.ru', 50, '{s}');"
        "update public.tools set active = false where name = 'Старый';").format(s=SKLAD, b=BERDSK))
st, out = q(srv, SQL)
check('20261027_order_points.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все строки «✅»', st == 'OK' and out.count('✅') == 8 and '❌' not in out, out[-900:])
st, out = q(srv, SQL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 8, out[-600:])

DRILL, SAW = tid(srv, 'Дрель Makita', 'admin@x.ru'), tid(srv, 'Пила', 'admin@x.ru')
PERF_B = val(srv, "select id from public.tools where name = 'Перфоратор' and point_id = '%s'" % BERDSK)
OLD, D2 = tid(srv, 'Старый', 'admin@x.ru'), tid(srv, 'Дрель', 'admin2@x.ru')

# ── 2. заявка со списком: части по «прокат + пункт», чужому прокату — ничего, пуш — на каждый пункт ──
n0 = int(val(srv, 'select count(*) from public.iva_push_log'))
o1 = submit(srv, 'Дрель Makita (3 дня), Пила (5 дней)', items((DRILL, 3), (SAW, 5)))
check('заявка из приложения (гость) — номер заявки', o1.isdigit(), o1)
r = parts(srv, o1)
check('две части: Склад — дрель, Бердск — пила; прокату с «Дрелью» — ничего',
      r == 'admin@x.ru|Склад|Дрель Makita ; admin@x.ru|Бердск|Пила', r)
check('«Новая заявка» — по уведомлению на каждый пункт (2 пуша владельцу)',
      val(srv, "select count(*) from public.iva_push_log where id > %d and email = 'admin@x.ru'" % n0) == '2'
      and val(srv, "select count(*) from public.iva_push_log where id > %d" % n0) == '2')
r = val(srv, "select items::text from public.order_parts where id = %s" % part_id(srv, o1, 'Склад'))
check('у части — её инструменты с ценой и своим сроком', json.loads(r) == [{'id': DRILL, 'name': 'Дрель Makita', 'price': 300, 'days': 3}], r)
o2 = submit(srv, 'Перфоратор (2 дня)', items((PERF_B, 2)))
check('одна модель в двух пунктах — часть только того пункта, что выбран', parts(srv, o2) == 'admin@x.ru|Бердск|Перфоратор', parts(srv, o2))
o3 = submit(srv, 'x', json.dumps([{'id': SAW, 'days': 0}, {'id': SAW.upper(), 'days': 4}, {'id': 'abc'}, {'id': OLD, 'days': 2},
                                    {'id': DRILL, 'days': 999}, 'мусор', {'days': 3}]).replace("'", "''"))
r = val(srv, "select items::text from public.orders where id = %s" % o3)
check('список чистится: повтор, не-id, мусор — прочь; срок 0 → 1, 999 → 365',
      json.loads(r) == [{'id': SAW, 'days': 1}, {'id': OLD, 'days': 2}, {'id': DRILL, 'days': 365}], r)
check('выключенный инструмент в часть не попадает', parts(srv, o3) == 'admin@x.ru|Бердск|Пила ; admin@x.ru|Склад|Дрель Makita', parts(srv, o3))
o4 = submit(srv, 'Неизвестно', items(('00000000-0000-0000-0000-000000000000', 1)))
check('ни один инструмент не найден — одна часть создателю, как раньше', parts(srv, o4) == 'eclips.ru@mail.ru|-|Неизвестно', parts(srv, o4))
o5 = submit(srv, 'Дрель Makita', '[]')
check('пустой список — деление по названиям (как сайт)', val(srv, 'select items is null from public.orders where id = %s' % o5) == 't'
      and 'admin@x.ru|Склад|Дрель Makita' in parts(srv, o5), parts(srv, o5))
o6 = submit(srv, 'Пила', items((SAW, 2)), email='client@x.ru', role='authenticated')
check('вошедший клиент тоже оформляет', o6.isdigit() and parts(srv, o6) == 'admin@x.ru|Бердск|Пила', o6)

# ── 3. заявка без списка (сайт, старое приложение): как раньше, пункт — если он один ──
o7 = uval(srv, '', "select public.submit_order('Иван', '+7999', 'Дрель Makita (3 дня)', 'Самовывоз', '', '', 'client@x.ru', 3)", role='anon')
r = parts(srv, o7)
check('сайт: часть на прокат (как раньше — и «Дрель» второго проката по названию), пункт — у каждого свой найденный',
      sorted(r.split(' ; ')) == ['admin2@x.ru|Искитим|Дрель', 'admin@x.ru|Склад|Дрель Makita'], r)
o8 = uval(srv, '', "select public.submit_order('Иван', '+7999', 'Перфоратор, Пила', '', '', '', 'client@x.ru', 2)", role='anon')
check('сайт: инструменты проката в разных пунктах — одна часть без пункта', parts(srv, o8) == 'admin@x.ru|-|Перфоратор, Пила', parts(srv, o8))

# ── 4. комиссия: Σ цена × свой срок; цена на момент заявки; без списка — только свой прокат ──
q(srv, "update public.tools set price = 999 where name = 'Дрель Makita'")
p_sk, p_bd = part_id(srv, o1, 'Склад'), part_id(srv, o1, 'Бердск')
check('прокат: «Выдал» по пиле', setstatus(srv, 'admin@x.ru', p_bd, 'rented')[0] == 'OK')
check('«Закрыта»: дрель 300 × 3 = 900, комиссия 5 % = 45 (цена — на момент заявки, не 999)',
      setstatus(srv, 'admin@x.ru', p_sk, 'closed')[0] == 'OK' and money(srv, p_sk) == '900|45', money(srv, p_sk))
check('«Закрыта» после «Выдал»: пила 500 × 5 = 2500, комиссия 125',
      setstatus(srv, 'admin@x.ru', p_bd, 'closed')[0] == 'OK' and money(srv, p_bd) == '2500|125', money(srv, p_bd))
r = val(srv, "select price_snapshot::text from public.order_parts where id = %s" % p_sk)
check('снимок цен — с названием, ценой и сроком', json.loads(r) == [{'name': 'Дрель Makita', 'price': 300, 'days': 3}], r)
q(srv, "update public.tools set price = 300 where name = 'Дрель Makita'")
p7 = val(srv, "select id from public.order_parts where order_id = %s and owner_email = 'admin@x.ru'" % o7)
check('сайт: «Закрыта» — только свои инструменты: 300 × 3 = 900 (раньше брал и чужую «Дрель»: 400 × 3)',
      setstatus(srv, 'admin@x.ru', p7, 'closed')[0] == 'OK' and money(srv, p7) == '900|45', money(srv, p7))
p8 = val(srv, "select id from public.order_parts where order_id = %s" % o8)
check('сайт, часть без пункта: перфоратор 400 + пила 500 = 900 × 2 = 1800',
      setstatus(srv, 'chief@x.ru', p8, 'closed')[0] == 'OK' and money(srv, p8) == '1800|90', money(srv, p8))
od = submit(srv, 'Пила', items((SAW, 4)), email='eclipsik.ru@mail.ru')
pd = val(srv, "select id from public.order_parts where order_id = %s" % od)
check('демо-клиент — без комиссии, как было', setstatus(srv, 'admin@x.ru', pd, 'closed')[0] == 'OK' and money(srv, pd) == '2000|0', money(srv, pd))
st, out = setstatus(srv, 'admin@x.ru', p_sk, 'new')
check('переходы статусов — как были («Закрыта» → «Новая» нельзя)', st == 'ERR' and 'Недопустимый перевод статуса' in out, out[-300:])
check('журнал статусов пишется', val(srv, "select count(*) from public.order_events where part_id = %s" % p_bd) == '2')
p2b = part_id(srv, o2, 'Бердск')
check('чужой прокат часть не закрывает', setstatus(srv, 'admin2@x.ru', p2b, 'closed')[0] == 'OK'
      and val(srv, 'select status from public.order_parts where id = %s' % p2b) == 'new')

# ── 5. список и пункт части прокат не меняет; удаление пункта снимает его с частей ──
as_(srv, 'admin@x.ru', "update public.order_parts set items = '[]', point_id = '%s' where id = %s" % (SKLAD, p2b))
check('прокат не меняет список инструментов и пункт своей части',
      val(srv, "select point_id = '%s' and jsonb_array_length(items) = 1 from public.order_parts where id = %s" % (BERDSK, p2b)) == 't')
as_(srv, 'admin@x.ru', ins_point('Временный', 'Обь', 'Обь, улица Мира, 1'))
TMP = val(srv, "select id from public.iva_pickup_points where name = 'Временный'")
q(srv, "insert into public.tools(name, owner_email, price, point_id) values ('Лобзик', 'admin@x.ru', 200, '%s')" % TMP)
o9 = submit(srv, 'Лобзик', items((tid(srv, 'Лобзик', 'admin@x.ru'), 1)))
check('часть на временном пункте', parts(srv, o9) == 'admin@x.ru|Временный|Лобзик', parts(srv, o9))
as_(srv, 'admin@x.ru', "update public.tools set point_id = '%s' where name = 'Лобзик'" % SKLAD)
st, out = as_(srv, 'admin@x.ru', "delete from public.iva_pickup_points where name = 'Временный'")
check('пункт без инструмента удаляется, с частей заявок он снимается', st == 'OK' and parts(srv, o9) == 'admin@x.ru|-|Лобзик', out[-300:])

# ── 6. «До конца аренды»: график пункта части, срок — самый длинный в части ──
o10 = submit(srv, 'Дрель Makita, Пила', items((DRILL, 2), (SAW, 6)), email='client@x.ru')
setstatus(srv, 'admin@x.ru', part_id(srv, o10, 'Склад'), 'rented')
setstatus(srv, 'admin@x.ru', part_id(srv, o10, 'Бердск'), 'rented')
r = uval(srv, 'admin@x.ru', "select tools_text || '|' || days || '|' || close_min::text from public.iva_admin_rentals() where order_id = '%s' order by part_id" % o10)
check('прокат: у каждой части свой срок и график своего пункта',
      r == 'Дрель Makita|2|{1140,1140,1140,1140,1140,1140,1140};Пила|6|{1200,1200,1200,1200,1200,1200,-1}', r)
r = uval(srv, 'client@x.ru', "select tools_text || '|' || days || '|' || close_min::text from public.iva_my_rentals() where order_id = '%s' order by part_id" % o10)
check('клиент видит то же', r == 'Дрель Makita|2|{1140,1140,1140,1140,1140,1140,1140};Пила|6|{1200,1200,1200,1200,1200,1200,-1}', r)
o11 = uval(srv, '', "select public.submit_order('Иван', '+7999', 'Перфоратор', '', '', '', 'client@x.ru', 4)", role='anon')
setstatus(srv, 'admin@x.ru', val(srv, 'select id from public.order_parts where order_id = %s' % o11), 'rented')
r = uval(srv, 'admin@x.ru', "select days || '|' || close_min::text from public.iva_admin_rentals() where order_id = '%s'" % o11)
check('часть без пункта и без списка — срок заявки и время закрытия проката, как раньше', r == '4|{1140,1140,1140,1140,1140,1140,1140}', r)

# ── 7. пункты заявок клиенту: только свои, без email ──
r = uval(srv, 'client@x.ru', "select name || '|' || address || '|' || coalesce(tel, '') || '|' || tools_text from public.iva_my_part_points() where order_id = '%s' order by part_id" % o1)
check('клиент: пункты своей заявки — название, адрес, телефон, инструменты',
      r == 'Склад|Новосибирск, улица Ленина, 5||Дрель Makita;Бердск|Бердск, улица Свердлова, 7|+7 383 000-00-01|Пила', r)
cols = val(srv, "select string_agg(a, ',') from (select unnest(proargnames) a from pg_proc where proname = 'iva_my_part_points') s")
check('email пункта и владельца не отдаются', 'email' not in cols and 'owner' not in cols, cols)
check('чужой клиент — пусто', uval(srv, 'other@x.ru', 'select count(*) from public.iva_my_part_points()') == '0')
check('гостю — нельзя', as_(srv, '', 'select * from public.iva_my_part_points()', role='anon')[0] == 'ERR')
check('служебная функция списка закрыта', as_(srv, 'admin@x.ru', "select public.iva_order_items_clean('[]')")[0] == 'ERR')

# ── 8. откат и повторное применение ──
st, out = q(srv, RB)
check('откат (20261027_order_points_rollback.sql) выполняется', st == 'OK' and '✅ откат 10.102 выполнен' in out, out[-600:])
o12 = uval(srv, '', "select public.submit_order('Иван', '+7999', 'Дрель Makita', '', '', '', 'client@x.ru', 3)", role='anon')
r = val(srv, "select string_agg(owner_email || '|' || tools_text, ' ; ' order by id) from public.order_parts where order_id = %s" % o12)
check('после отката деление — как до 10.102 (по названиям, и «Дрель» второго проката тоже)',
      sorted(r.split(' ; ')) == ['admin2@x.ru|Дрель', 'admin@x.ru|Дрель Makita'], r)
p12 = val(srv, "select id from public.order_parts where order_id = %s and owner_email = 'admin@x.ru'" % o12)
check('после отката комиссия — как до 10.102 (300 + 100) × 3', setstatus(srv, 'admin@x.ru', p12, 'closed')[0] == 'OK' and money(srv, p12) == '1200|60', money(srv, p12))
check('после отката «До конца аренды» — время закрытия проката',
      'Пила|6|{1140' in uval(srv, 'admin@x.ru', "select tools_text || '|' || days || '|' || close_min::text from public.iva_admin_rentals() where order_id = '%s'" % o10)
      or uval(srv, 'admin@x.ru', "select count(*) from public.iva_admin_rentals() where order_id = '%s' and close_min[7] = -1" % o10) == '0')
st, out = q(srv, SQL)
check('после отката снова применяется', st == 'OK' and out.count('✅') == 8, out[-600:])
srv.cleanup()

# ── 9. без SQL 10.101 — понятная ошибка, ничего не добавляется ──
srv = mk()
q(srv, BASELINE + EXTRA + PROD)
q(srv, RENT)
q(srv, PICK)
q(srv, TEL)
st, out = q(srv, SQL)
check('без 20261026 — останавливается с понятной ошибкой', st == 'ERR' and 'Сначала примените 20261026_tool_points.sql' in out, out[-300:])
check('… и ничего не добавляет', val(srv, "select count(*) from information_schema.columns where table_name in ('orders', 'order_parts') and column_name in ('items', 'point_id')") == '0')
srv.cleanup()

print('ИТОГ: ' + ('все проверки пройдены' if fails == 0 else 'ошибок: %d' % fails))
sys.exit(1 if fails else 0)

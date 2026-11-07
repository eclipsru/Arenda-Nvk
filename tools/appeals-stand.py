# Стенд «Обращения» и счётчик скачиваний (сайт и приложение 10.140, просьба владельца 07.11.2026):
# 20261128_appeals.sql (и откат) на настоящем PostgreSQL (pgserver).
#
# База — как в стенде «Кабинет владельца» (tools/owner-cabinet-stand.py: всё до 20261127 включительно, пункты «Склад»,
# «Бердск», «Пустой», сотрудники, заявки). Проверяет: файл применяется и повторяется, все «✅»; счётчик скачиваний —
# один браузер один раз, плохой номер не пишется, округление как в Google Play, защита от накрутки, таблицу не прочитать;
# «Позвонить» — прокат и пункт из пункта / инструмента / почты проката, чужая почта и снятый прокат не пишутся, 20 в минуту;
# «Обращения» — заявки (одна заявка = одно обращение), звонки, сообщения (клиент в день = одно, сотрудники и ответы
# проката не считаются) за сегодня / неделю с понедельника / месяц с 1-го / всё время по Москве, фильтр по пункту;
# клиенту, сотруднику, гостю и с чужим пунктом — нельзя; откат удаляет всё, после отката файл снова применяется.
#
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh   или   python tools/appeals-stand.py
import datetime
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, 'owner-cabinet-stand.py'), encoding='utf-8').read()
cut = src.index('# ── 1. сотрудник без имени ──')
exec(compile(src[:cut], 'owner-cabinet-stand.py (база)', 'exec'))   # srv, q, val, uval, as_, check, SK, BK, PU, DRILL, SAW …

print('— база «Кабинета владельца» готова, дальше — «Обращения» —')
fails_base = fails
APL = rd(M + '20261128_appeals.sql')
ARB = rd(M + '20261128_appeals_rollback.sql')

st, out = qs(srv, APL)
check('20261128_appeals.sql применяется без ошибок', st == 'OK', out[-900:])
check('проверка в конце файла — все 4 строки «✅»', st == 'OK' and out.count('✅') == 4 and '❌' not in out, out[-900:])
st, out = qs(srv, APL)
check('повторный запуск — без ошибок', st == 'OK' and out.count('✅') == 4, out[-600:])

# ── 1. скачивания ──
check('округление как в Google Play',
      val(srv, "select string_agg(public.iva_download_bucket(n)::text, ',' order by n) from unnest(array[0,1,4,5,9,10,49,50,99,100,499,500,999,1000,4999,5000,12345]::bigint[]) n")
      == '0,1,1,5,5,10,10,50,50,100,100,500,500,1000,1000,5000,10000')
check('сначала — 0 (страница счётчик не покажет)', uval(srv, '', 'select public.iva_download_count()', role='anon') == '0')
check('первое скачивание — 1', uval(srv, '', "select public.iva_download_hit('dev_aaaaaaaa1')", role='anon') == '1')
check('тот же браузер ещё раз — не считается', uval(srv, '', "select public.iva_download_hit('dev_aaaaaaaa1')", role='anon') == '1'
      and val(srv, 'select count(*) from public.iva_app_downloads') == '1')
for bad_dev in ("''", 'null', "'коротко'", "'bad dev with spaces'", "'x'"):
    uval(srv, '', 'select public.iva_download_hit(%s)' % bad_dev, role='anon')
check('плохой номер браузера — не пишется', val(srv, 'select count(*) from public.iva_app_downloads') == '1')
for i in range(2, 8):
    uval(srv, 'client@x.ru' if i % 2 else '', "select public.iva_download_hit('dev_bbbbbbbb%d')" % i, role='authenticated' if i % 2 else 'anon')
check('7 браузеров → «5+» (точное число наружу не отдаётся)', uval(srv, '', 'select public.iva_download_count()', role='anon') == '5'
      and val(srv, 'select count(*) from public.iva_app_downloads') == '7')
st, out = as_(srv, '', 'select count(*) from public.iva_app_downloads', role='anon')
check('гость таблицу не читает', st == 'ERR' and 'permission denied' in out, out[-200:])
st, out = as_(srv, 'client@x.ru', 'delete from public.iva_app_downloads')
check('вошедший таблицу не меняет', st == 'ERR' and 'permission denied' in out, out[-200:])
for i in range(70):
    uval(srv, '', "select public.iva_download_hit('flood_%08d')" % i, role='anon')
n = int(val(srv, 'select count(*) from public.iva_app_downloads'))
check('накрутка: не больше 60 новых за 10 минут', n == 60, n)
q(srv, "update public.iva_app_downloads set at = now() - interval '1 hour'")
check('через 10 минут — снова считает', uval(srv, '', "select public.iva_download_hit('later_00000001')", role='anon') == '50'
      and val(srv, 'select count(*) from public.iva_app_downloads') == '61')
check('61 скачивание → «50+»', uval(srv, '', 'select public.iva_download_count()', role='anon') == '50')

# ── 2. «Позвонить» ──
def tap(point='null', tool='null', owner='null', s="'app'", email='', role='anon'):
    return uval(srv, email, 'select public.iva_call_tap(%s, %s, %s, %s)' % (point, tool, owner, s), role=role)


def taps():
    return val(srv, "select coalesce(string_agg(c.owner_email || ':' || coalesce(p.name, '-') || ':' || src, ' ' order by c.id), '') "
                    "from public.iva_call_taps c left join public.iva_pickup_points p on p.id = c.point_id")


check('гость — «Позвонить» в пункте (сайт)', tap(point="'%s'" % SK, s="'site'") == 't' and taps() == 'admin@x.ru:Склад:site', taps())
check('клиент — «Позвонить» у инструмента: пункт инструмента', tap(tool="'%s'" % SAW, email='client@x.ru', role='authenticated') == 't'
      and taps().endswith('admin@x.ru:Бердск:app'), taps())
check('только почта проката — без пункта', tap(owner="' Admin@X.ru '") == 't' and taps().endswith('admin@x.ru:-:app'), taps())
check('неизвестная почта — не пишется', tap(owner="'nobody@x.ru'") == 'f')
check('нет пункта и инструмента — не пишется', tap(point="'%s'" % '00000000-0000-0000-0000-000000000000') == 'f')
check('что-то кроме app / site — пишется как app', tap(point="'%s'" % BK, s="'hack'") == 't' and taps().endswith('admin@x.ru:Бердск:app'), taps())
q(srv, "update public.admins set active = false where email = 'admin7@x.ru'")
check('снятый прокат — не пишется', tap(owner="'admin7@x.ru'") == 'f')
st, out = as_(srv, '', 'select count(*) from public.iva_call_taps', role='anon')
check('гость таблицу нажатий не читает', st == 'ERR' and 'permission denied' in out, out[-200:])
st, out = as_(srv, 'admin@x.ru', 'select count(*) from public.iva_call_taps')
check('владелец таблицу напрямую не читает (только числа через «Обращения»)', st == 'ERR' and 'permission denied' in out, out[-200:])
before = int(val(srv, 'select count(*) from public.iva_call_taps'))
res = [tap(point="'%s'" % PU) for _ in range(25)]
after = int(val(srv, 'select count(*) from public.iva_call_taps'))
check('не больше 20 нажатий в минуту на прокат', after == 20 and res.count('f') == 25 - (20 - before), [before, after])
q(srv, "delete from public.iva_call_taps")

# ── 3. «Обращения» ──
part([{'id': DRILL, 'days': 2}])                                # «Склад»
part([{'id': SAW, 'days': 1}])                                  # «Бердск»
order([{'id': DRILL, 'days': 1}, {'id': SAW, 'days': 1}])       # одна заявка — две части (два пункта)
B = {k: val(srv, "select to_char(date_trunc('%s', now() at time zone 'Europe/Moscow'), 'YYYY-MM-DD HH24:MI')" % k)
     for k in ('day', 'week', 'month')}
MSK = lambda s, shift: "(timestamp '%s' %s) at time zone 'Europe/Moscow'" % (s, shift)
today = MSK(B['day'], "+ interval '1 minute'")
yest = MSK(B['day'], "- interval '1 minute'")
prev_month = MSK(B['month'], "- interval '1 minute'")
day0 = datetime.datetime.strptime(B['day'], '%Y-%m-%d %H:%M')
week0 = datetime.datetime.strptime(B['week'], '%Y-%m-%d %H:%M')
month0 = datetime.datetime.strptime(B['month'], '%Y-%m-%d %H:%M')
y_in_week = (day0 - datetime.timedelta(minutes=1)) >= week0
y_in_month = (day0 - datetime.timedelta(minutes=1)) >= month0
pm_in_week = (month0 - datetime.timedelta(minutes=1)) >= week0


def ins_tap(point, at):
    q(srv, "insert into public.iva_call_taps(owner_email, point_id, src, at) values ('admin@x.ru', %s, 'app', %s)" % (point, at))


ins_tap("'%s'" % SK, today)
ins_tap("'%s'" % SK, today)
ins_tap("'%s'" % BK, yest)
ins_tap('null', prev_month)
q(srv, "insert into public.iva_call_taps(owner_email, point_id, src, at) values ('admin7@x.ru', null, 'site', now())")


def msg(frm, to, at, tool='null', point='null'):
    q(srv, "insert into public.app_messages(from_email, to_email, text, created_at, tool_id, point_id) values ('%s', '%s', 'x', %s, %s, %s)"
           % (frm, to, at, tool, point))


D, S_, P_ = "'%s'" % DRILL, "'%s'" % SAW, "'%s'" % SK
msg('client@x.ru', 'admin@x.ru', today, D)
msg('client@x.ru', 'admin@x.ru', today, D)
msg('Client@x.ru ', 'admin@x.ru', today, D)                    # тот же клиент, другой регистр — тот же
msg('anna@x.ru', 'admin@x.ru', today, S_)                       # Бердск
msg('olga@x.ru', 'admin@x.ru', today, 'null', P_)               # чат пункта «Склад»
msg('client@x.ru', 'admin@x.ru', yest, D)                       # вчера — другое обращение
msg('boris@x.ru', 'admin@x.ru', prev_month)                     # прошлый месяц, без пункта
msg('petr@x.ru', 'admin@x.ru', today, D)                        # свой сотрудник — не обращение
msg('admin@x.ru', 'client@x.ru', today, D)                      # ответ проката — не обращение
msg('client@x.ru', 'admin7@x.ru', today)                        # другому прокату


def ap(email, point='null'):
    raw = uval(srv, email, 'select public.iva_appeals(%s)::text' % point)
    try:
        return json.loads(raw)
    except Exception:
        return {'raw': raw}


def exp(today_n, yest_n, pm_n):
    return {'day': today_n, 'week': today_n + (yest_n if y_in_week else 0) + (pm_n if pm_in_week else 0),
            'month': today_n + (yest_n if y_in_month else 0), 'all': today_n + yest_n + pm_n}


a = ap('admin@x.ru')
check('звонки по всем пунктам: сегодня 2, вчера 1, прошлый месяц 1', a.get('calls') == exp(2, 1, 1), [a.get('calls'), exp(2, 1, 1), B])
check('сообщения по всем пунктам: клиент в день = 1; сотрудник и ответ проката не считаются', a.get('msgs') == exp(3, 1, 1),
      [a.get('msgs'), exp(3, 1, 1)])
orders_all = int(val(srv, "select count(distinct order_id) from public.order_parts where owner_email = 'admin@x.ru'"))
check('заявки за всё время = заявки проката (одна заявка = одно обращение)', a.get('orders', {}).get('all') == orders_all and orders_all == 4,
      [a.get('orders'), orders_all])
check('заявки сегодня (все — только что из стенда)', a['orders']['day'] == orders_all and a['orders']['week'] == orders_all, a.get('orders'))
check('часовой пояс — Москва', a.get('tz') == 'Europe/Moscow', a.get('tz'))
q(srv, "update public.orders set created_at = %s; update public.order_parts set created_at = %s where id = (select min(id) from public.order_parts "
       "where owner_email = 'admin@x.ru')" % (prev_month, prev_month))
a2 = ap('admin@x.ru')
check('заявка прошлого месяца — только в «за всё время»', a2['orders']['all'] == orders_all and a2['orders']['month'] == orders_all - 1, a2.get('orders'))
s = ap('admin@x.ru', "'%s'" % SK)
check('пункт «Склад»: звонки 2 сегодня', s.get('calls') == exp(2, 0, 0), s.get('calls'))
check('пункт «Склад»: сообщения — по инструменту пункта и чату пункта', s.get('msgs') == exp(2, 1, 0), s.get('msgs'))
sk_orders = int(val(srv, "select count(distinct order_id) from public.order_parts where owner_email = 'admin@x.ru' and point_id = '%s'" % SK))
check('пункт «Склад»: заявки только этого пункта', s['orders']['all'] == sk_orders and sk_orders < orders_all, [s.get('orders'), sk_orders])
b = ap('admin@x.ru', "'%s'" % BK)
check('пункт «Бердск»: звонок вчера, сообщение об инструменте пункта', b.get('calls') == exp(0, 1, 0) and b.get('msgs') == exp(1, 0, 0), b)
p = ap('admin@x.ru', "'%s'" % PU)
check('пустой пункт — нули', p.get('calls') == exp(0, 0, 0) and p.get('msgs') == exp(0, 0, 0) and p['orders']['all'] == 0, p)
q(srv, "update public.admins set active = true where email = 'admin7@x.ru'")
a7 = ap('admin7@x.ru')
check('другой прокат видит только свои обращения', a7.get('calls', {}).get('all') == 1 and a7.get('msgs', {}).get('all') == 1
      and a7['orders']['all'] == 0, a7)
st, out = as_(srv, 'admin7@x.ru', "select public.iva_appeals('%s')" % SK)
check('чужой пункт — нельзя', st == 'ERR' and 'не ваш пункт' in out, out[-300:])
st, out = as_(srv, 'client@x.ru', 'select public.iva_appeals(null)')
check('клиенту — нельзя', st == 'ERR' and 'только у владельца проката' in out, out[-300:])
st, out = as_(srv, 'petr@x.ru', 'select public.iva_appeals(null)')
check('сотруднику — нельзя', st == 'ERR' and 'только у владельца проката' in out, out[-300:])
st, out = as_(srv, '', 'select public.iva_appeals(null)', role='anon')
check('гостю — нельзя', st == 'ERR' and 'permission denied' in out, out[-300:])
st, out = as_(srv, 'admin@x.ru', 'select public.iva_download_bucket(5)')
check('служебную iva_download_bucket не вызвать', st == 'ERR' and 'permission denied' in out, out[-200:])
check('заявки, сообщения и инструменты не тронуты', val(srv, "select count(*) from public.app_messages where text = 'x'") == '10')

# ── 4. откат ──
st, out = qs(srv, ARB)
check('откат применяется, все «✅»', st == 'OK' and out.count('✅') == 2 and '❌' not in out, out[-600:])
check('после отката сообщения на месте', val(srv, "select count(*) from public.app_messages where text = 'x'") == '10')
st, out = qs(srv, APL)
check('после отката SQL снова применяется', st == 'OK' and out.count('✅') == 4, out[-400:])

print('Итого: ' + ('всё ✔' if not fails else '%d ✘' % fails))
sys.exit(1 if fails else 0)

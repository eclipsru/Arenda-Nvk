#!/usr/bin/env bash
# Стенд миграций Telegram-бота (этапы П5, защитный контур).
#
# Проверяет на живом PostgreSQL две миграции:
#   supabase/migrations/20261003_tg_new_order.sql     — уведомление о новой заявке;
#   supabase/migrations/20261003_max_no_contacts.sql  — хук MAX без контактов и с защитой от сбоя (с 05.10.2026 вместо max_hook_safety).
#
# Стенд воспроизводит БОЕВУЮ картину: таблица orders с настоящими колонками,
# таблицы бота (iva_secrets, iva_tg_targets), функция бота iva_tg_send (заглушка,
# которая складывает сообщения в tg_log) и «как было» — незащищённый хук MAX,
# который вызывает net.http_post напрямую (при выключенном pg_net заявка не сохраняется).
#
# Код возврата: 0 — проверено, 1 — есть замечания, 2 — пропущено (нет локального PostgreSQL).
# Запуск: bash tools/tg-migrations-stand.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PSQL=${PSQL:-sudo -u postgres psql}
DB=iva_tg_stand
FAILED=0

say()  { printf '%s\n' "$*"; }
good() { printf '  ok    %s\n' "$*"; }
bad()  { printf '  ОШИБКА %s\n' "$*"; FAILED=1; }

if ! $PSQL -tAq -d postgres -c 'select 1' >/dev/null 2>&1; then
  say "ПРОПУСК: локальный PostgreSQL недоступен ($PSQL). Стенд миграций бота не выполнен."
  exit 2
fi

# PostgreSQL работает под своим пользователем и не читает домашнюю папку проекта.
STAGE=$(mktemp -d); chmod 755 "$STAGE"
cp "$ROOT/supabase/migrations/20261003_tg_new_order.sql"    "$STAGE/new_order.sql"
# 05.10.2026: защита хука MAX проверяется на нашей ПРИМЕНЁННОЙ миграции (без контактов, решение №17);
# 20261003_max_hook_safety.sql вернул бы отправку контактов в MAX — перенесён в supabase/obsolete/.
cp "$ROOT/supabase/migrations/20261003_max_no_contacts.sql" "$STAGE/max_safety.sql"

q() { $PSQL -tAq -d "$DB" -c "set client_min_messages=warning; $1" 2>&1; }  # вывод + ошибки, без NOTICE
run() { $PSQL -v ON_ERROR_STOP=1 -q -d "$DB" -f "$1" >/dev/null 2>&1; }

# ---------- 1. Чистая база и боевая картина ----------
$PSQL -q -d postgres -c "drop database if exists $DB" >/dev/null 2>&1
$PSQL -q -d postgres -c "create database $DB" >/dev/null 2>&1 || { bad "не удалось создать базу $DB"; exit 1; }

cat > "$STAGE/mock.sql" <<'SQL'
-- Таблица заявок — колонки как в бою.
create table public.orders(
  id bigserial primary key,
  created_at timestamptz default now(),
  status text default 'new',
  name text, phone text, address text, comment text,
  tools text, get_method text, days int, user_email text
);
-- Таблицы бота (как в боевой базе).
create table public.iva_secrets(name text primary key, value text);
create table public.iva_tg_targets(key text primary key, chat_id bigint, active boolean default true);
insert into public.iva_secrets values ('BOT_TOKEN', '111:TEST');
insert into public.iva_tg_targets(key, chat_id, active) values ('owner', 42, true);
-- Куда стенд складывает «отправленные» сообщения вместо Telegram.
create table public.tg_log(id bigserial primary key, key text, text text, at timestamptz default now());
-- Заглушка функции бота: та же подпись, что в бою.
create or replace function public.iva_tg_send(p_key text, p_text text)
returns bigint language plpgsql as $fn$
begin
  insert into public.tg_log(key, text) values (p_key, p_text);
  return 1;
end $fn$;
-- «Как было»: незащищённый хук MAX — вызывает net.http_post напрямую.
create or replace function public.fn_order_to_max_hook()
returns trigger language plpgsql as $fn$
begin
  perform net.http_post(url := 'https://example.invalid/order_to_max',
                        headers := '{}'::jsonb, body := '{}'::jsonb);
  return new;
end $fn$;
create trigger fn_order_to_max_hook after insert on public.orders
  for each row execute function public.fn_order_to_max_hook();
SQL
chmod 644 "$STAGE"/*.sql
if run "$STAGE/mock.sql"; then good "боевая картина воспроизведена (orders, бот, незащищённый хук MAX)"; else bad "не удалось собрать стенд"; exit 1; fi

order_insert() { # $1 — способ получения, $2 — срок
  q "insert into public.orders(name, phone, address, comment, tools, get_method, days, user_email)
     values ('Сергей Петров', '89180001122', 'Новочеркасск, ул. Ленина, 5', 'Хочу в субботу',
             'Перфоратор Bosch GBH 2-26', '$1', $2, 'client@mail.ru') returning id"
}

# ---------- 2. Баг до миграции: сбой уведомления ломает сохранение заявки ----------
out=$(order_insert 'Самовывоз, Новочеркасск, ул. Маресьева, 36' 2)
if printf '%s' "$out" | grep -qi 'net.http_post\|does not exist\|не существует'; then
  good "баг воспроизведён: без pg_net заявка НЕ сохраняется («$out»)"
  # чистим следы неудачной попытки (последовательность могла уйти вперёд — это нормально)
else
  bad "ожидали, что до миграции заявка не сохранится, а получили: $out"
fi

# ---------- 3. Миграция защиты: заявка сохраняется при любом сбое ----------
if run "$STAGE/max_safety.sql"; then good "миграция защиты хука MAX применена"; else bad "миграция защиты хука MAX не применилась"; fi
first=$(order_insert 'Самовывоз, Новочеркасск, ул. Маресьева, 36' 2)
if printf '%s' "$first" | grep -Eq '^[0-9]+$'; then
  good "после миграции заявка сохраняется даже без pg_net (номер $first)"
else
  bad "после миграции заявка всё ещё не сохраняется: $first"
fi

# ---------- 4. Миграция «уведомление о новой заявке» ----------
if run "$STAGE/new_order.sql"; then good "миграция уведомления о новой заявке применена"; else bad "миграция уведомления не применилась"; fi

first=$(order_insert 'Самовывоз, Новочеркасск, ул. Маресьева, 36' 2)
msg=$(q "select text from public.tg_log order by id desc limit 1")
say "  ----- сообщение, которое ушло бы владельцу -----"
printf '%s\n' "$msg" | sed 's/^/  │ /'
say "  ------------------------------------------------"

echo "$msg" | grep -q "Новая заявка"        && good "в сообщении есть заголовок"                       || bad "нет заголовка «Новая заявка»"
echo "$msg" | grep -q "№$first"             && good "указан номер заявки"                             || bad "нет номера заявки"
echo "$msg" | grep -q "Перфоратор Bosch"    && good "указан состав заявки"                            || bad "нет состава заявки"
echo "$msg" | grep -q "2 дня"               && good "указан срок со склонением (2 дня)"               || bad "нет срока или склонение неверное"
echo "$msg" | grep -q "Самовывоз"           && good "указан способ получения"                         || bad "нет способа получения"
echo "$msg" | grep -q "chief.html"          && good "есть ссылка в кабинет за контактами"             || bad "нет ссылки в кабинет"
echo "$msg" | grep -q "МСК"                 && good "указано время по Москве"                          || bad "нет времени МСК"

# Главное: контактов и адреса быть не должно (решение владельца №17).
if echo "$msg" | grep -qE "Петров|89180001122|Ленина|client@|Хочу в субботу"; then
  bad "в сообщении есть контакты/адрес/комментарий клиента — нарушение решения №17"
else
  good "контактов, адреса и комментария клиента в сообщении нет (решение №17)"
fi
if echo "$msg" | grep -q "Маресьева"; then
  bad "в сообщении адрес самовывоза из заявки"
else
  good "адрес в сообщении не утёк"
fi

# ---------- 5. Доставка: адрес тоже не должен попасть ----------
q "insert into public.orders(name, phone, tools, get_method, days)
   values ('Иван', '89180001133', 'Бетономешалка',
           'Доставка (туда-обратно): Новочеркасск, ул. Ленина, 5 — 400 ₽ (2 × 5 км по 40 ₽/км)', 3)" >/dev/null
msg2=$(q "select text from public.tg_log order by id desc limit 1")
if echo "$msg2" | grep -q "Доставка" && ! echo "$msg2" | grep -q "Ленина"; then
  good "доставка: способ указан, адрес доставки не утёк"
else
  bad "доставка: в сообщении адрес или нет способа получения"
fi
if echo "$msg2" | grep -q "3 дня"; then good "склонение для 3 (3 дня) верное"; else bad "склонение для 3 неверное: $msg2"; fi

# ---------- 6. Сбой самого бота не мешает заявке ----------
$PSQL -q -d "$DB" -c "create or replace function public.iva_tg_send(p_key text, p_text text) returns bigint language plpgsql as \$fn\$ begin raise exception 'бот недоступен'; end \$fn\$" >/dev/null 2>&1
broken=$(order_insert 'Самовывоз' 1)
if printf '%s' "$broken" | grep -Eq '^[0-9]+$'; then good "бот «сломан» — заявка всё равно сохранилась"; else bad "сбой бота помешал сохранить заявку: $broken"; fi

# ---------- 7. Повторный запуск миграций безопасен ----------
# возвращаем рабочую заглушку бота (в шаге 6 мы её намеренно сломали)
$PSQL -q -d "$DB" -c "create or replace function public.iva_tg_send(p_key text, p_text text) returns bigint language plpgsql as \$fn\$ begin insert into public.tg_log(key, text) values (p_key, p_text); return 1; end \$fn\$" >/dev/null 2>&1
run "$STAGE/new_order.sql" && run "$STAGE/max_safety.sql" || bad "повторный запуск миграций дал ошибку"
before=$(q "select count(*) from public.tg_log")
q "insert into public.orders(tools, days) values ('Болгарка', 1)" >/dev/null
q "insert into public.orders(tools, days) values ('Шуруповёрт', 1)" >/dev/null
after=$(q "select count(*) from public.tg_log")
if [ "$after" = "$((before + 2))" ]; then
  good "повторный запуск миграций не создал дублей: две заявки → два сообщения ($before → $after)"
else
  bad "сообщений не по одному на заявку: было $before, стало $after (ждали $((before + 2)))"
fi
trigs=$(q "select count(*) from pg_trigger where tgname = 'iva_tg_new_order'")
[ "$trigs" = "1" ] && good "триггер один, дублей нет" || bad "триггеров iva_tg_new_order: $trigs"

# ---------- 8. Бот не установлен: миграция не падает и предупреждает ----------
$PSQL -q -d postgres -c "drop database if exists ${DB}_nobot" >/dev/null 2>&1
$PSQL -q -d postgres -c "create database ${DB}_nobot" >/dev/null 2>&1
$PSQL -q -d "${DB}_nobot" -c "create table public.orders(id bigserial primary key, created_at timestamptz default now(), tools text, get_method text, days int)" >/dev/null 2>&1
out=$($PSQL -tAq -d "${DB}_nobot" -f "$STAGE/new_order.sql" 2>&1)
ins=$($PSQL -tAq -d "${DB}_nobot" -c "insert into public.orders(tools, days) values ('Тиски', 1) returning id" 2>&1)
if printf '%s' "$out" | grep -q "не найдена" && printf '%s' "$ins" | grep -Eq '^[0-9]+$'; then
  good "без установленного бота: миграция предупреждает и не мешает заявкам"
else
  bad "без бота поведение неверное (создание: $out / вставка: $ins)"
fi

# ---------- 9. MAX: что уходит в order_to_max (решение №17, вариант А) — 05.10.2026 ----------
# подставная pg_net: net.http_post записывает тело запроса в таблицу вместо отправки
$PSQL -q -d "$DB" -c "create schema if not exists net; create table if not exists public.max_log(body jsonb);
  create or replace function net.http_post(url text, headers jsonb, body jsonb) returns bigint language plpgsql as \$fn\$
  begin insert into public.max_log(body) values (body); return 1; end \$fn\$" >/dev/null 2>&1
run "$STAGE/max_safety.sql" || bad "повторное применение миграции MAX дало ошибку"
mx=$(order_insert 'Доставка: Новочеркасск, ул. Ленина, 5' 2)
body=$(q "select body::text from public.max_log order by ctid desc limit 1")
if [ -z "$body" ]; then
  bad "в MAX ничего не ушло (заявка $mx)"
elif echo "$body" | grep -qE "Петров|89180001122|Ленина|client@|Хочу в субботу"; then
  bad "в MAX уходят контакты/адрес/комментарий клиента — нарушение решения №17: $body"
else
  echo "$body" | grep -q "Перфоратор" && good "MAX: уходят номер и состав, контактов и адреса нет (решение №17)" || bad "MAX: нет состава заявки: $body"
fi

$PSQL -q -d postgres -c "drop database if exists $DB" >/dev/null 2>&1
$PSQL -q -d postgres -c "drop database if exists ${DB}_nobot" >/dev/null 2>&1
rm -rf "$STAGE"

if [ "$FAILED" = "0" ]; then say "Стенд бота: все проверки пройдены."; exit 0; fi
say "Стенд бота: есть замечания (см. выше)."; exit 1

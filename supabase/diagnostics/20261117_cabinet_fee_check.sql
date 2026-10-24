-- 10.117 (17.10.2026): ПРОВЕРКА, только чтение — ничего не меняет. Выполнить целиком в SQL Editor, прислать таблицу.
-- Строки «комиссия» — какую сумму должна показывать карточка «Комиссия владельцу» у каждого админа (те же данные,
-- что берёт приложение): итог < 0 — задолженность («−N ₽»), > 0 — переплата («+N ₽»).
-- Строки «повтор в архиве» — одна заявка дважды у одного админа в одном пункте. Таких строк нет — в данных повторов
-- нет (двоилась только строка «Архив заявок» в кабинете — исправлено в 10.117).
with parts as (
  select lower(owner_email) as email, coalesce(sum(fee), 0) as nachisleno, count(*) as chastey
  from public.order_parts where owner_email is not null group by 1
), paid as (
  select lower(admin_email) as email, coalesce(sum(amount), 0) as oplacheno, count(*) as platezhey
  from public.fee_payments where status = 'confirmed' and admin_email is not null group by 1
), dup as (
  select order_id, lower(owner_email) as email, point_id, count(*) as n
  from public.order_parts where archived is true
  group by 1, 2, 3 having count(*) > 1
)
select 'комиссия' as razdel,
       coalesce(p.email, f.email) as admin,
       coalesce(p.nachisleno, 0) as nachisleno,
       coalesce(f.oplacheno, 0) as oplacheno,
       coalesce(f.oplacheno, 0) - coalesce(p.nachisleno, 0) as itog,
       'частей заявок: ' || coalesce(p.chastey, 0) || ', подтверждённых оплат: ' || coalesce(f.platezhey, 0) as primechanie
from parts p full join paid f on f.email = p.email
union all
select 'повтор в архиве', d.email, null, null, null,
       'заявка №' || d.order_id || ', пункт ' || coalesce(d.point_id::text, '—') || ': частей ' || d.n
from dup d
order by 1, 2;

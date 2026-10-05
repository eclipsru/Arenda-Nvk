-- Ива: почему админ не может выложить инструмент. ТОЛЬКО ЧТЕНИЕ — ничего не меняет.
-- Вставить целиком в Supabase → SQL Editor → Run. Смотреть колонку «причина» у нужной почты.
select a.email,
       a.role,
       a.active,
       (select count(*) from public.tools t where lower(t.owner_email) = lower(a.email)) as инструментов,
       case
         when not coalesce(a.active, false) then 'не активен — база не даст публиковать (включить active)'
         when a.role not in ('admin', 'chief') then 'роль «' || coalesce(a.role, 'пусто') || '» — публиковать могут только admin/chief'
         when exists (select 1 from public.limited_admins l where lower(l.email) = lower(a.email))
           then 'в списке limited_admins (долг по комиссии) — вкладка «Добавить» скрыта'
         when not exists (select 1 from auth.users u where lower(u.email) = lower(a.email))
           then 'нет входа с этой почтой — в приложении вошли под другой'
         else 'по базе всё в порядке — нужен скриншот ошибки'
       end as причина
from public.admins a
order by a.role, a.email;

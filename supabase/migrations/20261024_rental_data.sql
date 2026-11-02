-- ============================================================
--  Ива 10.98 — «Настройка проката → Данные проката» (просьба владельца 16.10.2026).
--
--  Решение владельца: это те же данные админа, что были в «Мои данные», — их убрали оттуда
--  в отдельную страницу. Название, адрес организации и время работы по-прежнему пишутся
--  в admins.company / admins.org_address / admins.work_hours (их видят клиент в заявке и сайт).
--  Новые поля этой страницы — четыре колонки в той же строке admins:
--    org_ip      — «ИП организации» (до 150 символов);
--    org_tel     — «Номер телефона (для связи с начальником)» (10–15 цифр);
--    work_days   — «Рабочие дни»: биты Пн=1, Вт=2, Ср=4, Чт=8, Пт=16, Сб=32, Вс=64 (0…127);
--    org_avatar  — аватарка проката: адрес файла (https, до 500 символов), файл — штатной
--                  загрузкой фото, как аватарка админа.
--
--  Права: колонки в строке admins — меняет их тот же, кто меняет свою строку сейчас
--  (штатное «Сохранить» в «Мои данные»); политики admins не трогаются. Защита полей доступа
--  (email, role, active, fee_pct, debt_limit — триггер protect_admin_access_fields) не меняется.
--  ВНИМАНИЕ: строки admins сейчас читают и клиенты (название, адрес, телефон, время работы
--  в заявке) — если на таблицу выдано право чтения целиком, новые колонки (в том числе
--  телефон начальника) им тоже будут доступны. Приложение клиентам их не показывает.
--
--  Без этого SQL приложение 10.98 работает: «Данные проката» сохраняют название, адрес и
--  время работы, а «ИП», телефон, дни и аватарка проката показываются с подсказкой
--  «после обновления базы (SQL 10.98)».
--
--  Безопасно: только ADD COLUMN IF NOT EXISTS и проверки; данные не удаляются и не меняются.
--  Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  ПРОВЕРКА:  select org_ip, org_tel, work_days, org_avatar from public.admins limit 1;
--  ОТКАТ (поля «ИП», телефон начальника, дни и аватарка проката пропадут вместе с данными):
--    alter table public.admins drop column if exists org_ip, drop column if exists org_tel,
--      drop column if exists work_days, drop column if exists org_avatar;
--    notify pgrst, 'reload schema';
-- ============================================================

alter table public.admins add column if not exists org_ip text;
alter table public.admins add column if not exists org_tel text;
alter table public.admins add column if not exists work_days smallint;
alter table public.admins add column if not exists org_avatar text;

-- проверки — отдельно (ADD COLUMN IF NOT EXISTS пропускает встроенные CHECK, если колонка уже есть)
alter table public.admins drop constraint if exists admins_org_ip_len;
alter table public.admins add constraint admins_org_ip_len
  check (org_ip is null or char_length(org_ip) <= 150) not valid;
alter table public.admins drop constraint if exists admins_org_tel_fmt;
alter table public.admins add constraint admins_org_tel_fmt
  check (org_tel is null or org_tel = '' or (org_tel ~ '^\+?[0-9() .-]+$'
    and char_length(regexp_replace(org_tel, '[^0-9]', '', 'g')) between 10 and 15)) not valid;
alter table public.admins drop constraint if exists admins_work_days_bits;
alter table public.admins add constraint admins_work_days_bits
  check (work_days is null or work_days between 0 and 127) not valid;
alter table public.admins drop constraint if exists admins_org_avatar_url;
alter table public.admins add constraint admins_org_avatar_url
  check (org_avatar is null or org_avatar = '' or (org_avatar ~ '^https://' and char_length(org_avatar) <= 500)) not valid;

comment on column public.admins.org_ip is 'Ива 10.98: «ИП организации» (Настройка проката → Данные проката)';
comment on column public.admins.org_tel is 'Ива 10.98: «Номер телефона (для связи с начальником)»';
comment on column public.admins.work_days is 'Ива 10.98: рабочие дни, биты Пн=1 … Вс=64';
comment on column public.admins.org_avatar is 'Ива 10.98: аватарка проката (адрес файла)';

-- права на новые колонки — как на прежние поля строки (если права выданы по колонкам)
grant select (org_ip, org_tel, work_days, org_avatar) on public.admins to authenticated;
grant update (org_ip, org_tel, work_days, org_avatar) on public.admins to authenticated;

notify pgrst, 'reload schema';

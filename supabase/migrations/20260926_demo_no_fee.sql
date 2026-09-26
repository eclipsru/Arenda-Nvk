-- Демо-аккаунт: его заявки не начисляют комиссию арендодателям.
-- Аккаунт eclipsik.ru@mail.ru используется владельцем для наглядного
-- показа функционала сайта и приложения, поэтому деньги за его заказы
-- ни с кого списываться не должны.
--
-- Как работает: BEFORE-триггер на order_parts обнуляет fee у всех частей
-- заказов, где orders.user_email = демо-аккаунт. Ставка fee_pct и оборот
-- rent_sum при этом сохраняются (видно, что было бы), а долг не растёт,
-- т.к. долг считается как sum(fee) - оплаты. Покрывает сайт, приложение
-- и любых будущих клиентов, т.к. срабатывает в базе независимо от клиентов.
--
-- ВАЖНО: имя триггера начинается с zz_, чтобы он срабатывал ПОСЛЕ
-- trg_fee_accrue (PostgreSQL запускает триггеры по алфавиту): сначала
-- штатный пересчёт комиссии при закрытии, затем наше обнуление для демо.
-- Не переименовывать без учёта порядка!
--
-- Применение: Supabase Dashboard → SQL Editor → вставить целиком → Run.
-- Откат: drop trigger if exists trg_zz_demo_no_fee on public.order_parts;
--        drop function if exists public.demo_no_fee();

create or replace function public.demo_no_fee()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if exists (
    select 1 from public.orders o
    where o.id = new.order_id
      and lower(o.user_email) = 'eclipsik.ru@mail.ru'
  ) then
    new.fee := 0;
  end if;
  return new;
end;
$$;

drop trigger if exists trg_demo_no_fee on public.order_parts;
drop trigger if exists trg_zz_demo_no_fee on public.order_parts;
create trigger trg_zz_demo_no_fee
before insert or update on public.order_parts
for each row execute function public.demo_no_fee();

-- ============================================================
--  ОТКАТ SQL 20261123_yookassa_fee.sql (Ива 10.133, оплата комиссии через ЮKassa).
--  Убирает таблицу онлайн-оплат и её функции. fee_payments НЕ трогается: уже засчитанные онлайн-оплаты
--  остаются там обычными подтверждёнными строками (с пометкой «ЮKassa · онлайн-оплата №…»), долг не меняется.
--  Перед откатом выгрузите список (если нужен для чеков):
--    select * from public.iva_yk_pay order by id;
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run.
-- ============================================================
DROP FUNCTION IF EXISTS public.iva_yk_receipt(bigint, boolean);
DROP FUNCTION IF EXISTS public.iva_yk_done(text, text, numeric, boolean, text, text);
DROP FUNCTION IF EXISTS public.iva_yk_open(text, bigint);
DROP FUNCTION IF EXISTS public.iva_yk_fail(bigint, text);
DROP FUNCTION IF EXISTS public.iva_yk_set(bigint, text, text);
DROP FUNCTION IF EXISTS public.iva_yk_new(text, numeric);
DROP FUNCTION IF EXISTS public.iva_yk_fee_insert(text, numeric, text);
DROP TABLE IF EXISTS public.iva_yk_pay;
NOTIFY pgrst, 'reload schema';
SELECT CASE WHEN to_regclass('public.iva_yk_pay') IS NULL THEN '✅ онлайн-оплаты убраны' ELSE '❌ таблица осталась' END AS result;

-- ============================================================
--  Ива 10.99 — «Телефон пункта выдачи» (просьба владельца 16.10.2026).
--
--  В карточке пункта выдачи («Настройка проката → Пункты выдачи») показывается телефон пункта,
--  в форме добавления / изменения — поле «Телефон пункта выдачи». Телефон необязателен; если
--  указан — 10–15 цифр, только цифры, пробелы, + ( ) . - (как на сайте и в «Данных проката»).
--
--  Права не меняются: пункт видит и меняет только сам действующий админ (политики 10.95);
--  клиентам пункты не показываются. Добавляется только право записи в новую колонку.
--
--  Нужен SQL 10.95 (20261023_pickup_points.sql). Без этого SQL приложение 10.99 работает:
--  пункты сохраняются без телефона, в форме — подсказка «после обновления базы (SQL 10.99)».
--
--  Безопасно: ADD COLUMN IF NOT EXISTS и проверка; данные не удаляются. Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  ПРОВЕРКА:  select name, tel from public.iva_pickup_points limit 5;
--  ОТКАТ (телефоны пунктов пропадут):
--      ALTER TABLE public.iva_pickup_points DROP COLUMN IF EXISTS tel;
--      NOTIFY pgrst, 'reload schema';
-- ============================================================

ALTER TABLE public.iva_pickup_points ADD COLUMN IF NOT EXISTS tel text NOT NULL DEFAULT '';

-- проверка — отдельно (ADD COLUMN IF NOT EXISTS пропускает встроенные CHECK, если колонка уже есть)
ALTER TABLE public.iva_pickup_points DROP CONSTRAINT IF EXISTS iva_pickup_tel;
ALTER TABLE public.iva_pickup_points ADD CONSTRAINT iva_pickup_tel
  CHECK (tel = '' OR (tel ~ '^\+?[0-9() .-]+$' AND char_length(tel) <= 30
    AND char_length(regexp_replace(tel, '[^0-9]', '', 'g')) BETWEEN 10 AND 15));

COMMENT ON COLUMN public.iva_pickup_points.tel IS 'Ива 10.99: телефон пункта выдачи (необязательно)';

GRANT INSERT (tel) ON public.iva_pickup_points TO authenticated;
GRANT UPDATE (tel) ON public.iva_pickup_points TO authenticated;

NOTIFY pgrst, 'reload schema';

-- ============================================================
--  Ива — срок хранения заявок (этап П4, шаг 2).
--  Решение владельца: заявки и контакты в них храним 12 месяцев,
--  затем обезличиваем (имя, телефон, адрес, комментарий, email
--  стираются; состав заявки с датой и расчёты остаются без
--  привязки к человеку — они нужны для статистики и расчётов).
--
--  Что делает миграция:
--    1. создаёт функцию public.iva_purge_requests() — обезличивает
--       заявки старше 12 месяцев; возвращает сколько строк тронула;
--    2. если включено расширение pg_cron — планирует запуск раз в
--       месяц; иначе оставляет напоминание запускать вручную.
--
--  Миграция идемпотентная: повторный запуск ничего не ломает.
--  Ничего не удаляет и не меняет в живых заявках моложе 12 месяцев.
--
--  КАК ПРИМЕНИТЬ (действие владельца): SQL-редактор Supabase →
--  вставить текст → Run. Проверка: SELECT public.iva_purge_requests();
--
--  КАК ОТКАТИТЬ:
--    SELECT cron.unschedule('iva-purge-requests');  -- если включали расписание
--    DROP FUNCTION IF EXISTS public.iva_purge_requests();
-- ============================================================

DO $$
BEGIN
  IF to_regclass('public.orders') IS NULL THEN
    RAISE NOTICE 'Таблица public.orders не найдена — миграция пропущена.';
    RETURN;
  END IF;

  CREATE OR REPLACE FUNCTION public.iva_purge_requests()
  RETURNS integer
  LANGUAGE plpgsql
  AS $fn$
  DECLARE
    -- Персональные поля заявки. Обезличиваем только то, что есть в таблице.
    cols  text[] := ARRAY['name', 'phone', 'address', 'comment', 'user_email'];
    sets  text   := '';
    cond  text   := '';
    c     text;
    n     integer := 0;
  BEGIN
    FOREACH c IN ARRAY cols LOOP
      IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders' AND column_name = c
      ) THEN
        IF EXISTS (
          SELECT 1 FROM information_schema.columns
          WHERE table_schema = 'public' AND table_name = 'orders'
            AND column_name = c AND is_nullable = 'YES'
        ) THEN
          sets := sets || format('%I = NULL, ', c);
        ELSE
          -- Колонка NOT NULL — стираем до пустой строки, а не падаем.
          sets := sets || format('%I = %L, ', c, '');
        END IF;
        -- «Есть хоть одно непустое поле» — работает и для NULL, и для NOT NULL.
        cond := cond || format('coalesce(%I, '''') <> '''' OR ', c);
      END IF;
    END LOOP;

    IF sets = '' THEN
      RAISE NOTICE 'В orders не найдено персональных колонок — нечего обезличивать.';
      RETURN 0;
    END IF;

    -- Трогаем только заявки старше 12 месяцев, в которых ещё остались данные.
    EXECUTE format(
      'UPDATE public.orders SET %s WHERE created_at < now() - interval ''12 months'' AND (%s)',
      rtrim(sets, ', '),
      rtrim(cond, ' OR ')
    );
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
  END $fn$;

  COMMENT ON FUNCTION public.iva_purge_requests() IS
    'Обезличивает заявки (orders) старше 12 месяцев: имя, телефон, адрес, комментарий, email. Возвращает число тронутых строк.';
END $$;

-- Расписание: раз в месяц, первого числа в 02:00. Только если pg_cron доступен.
DO $sched$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
    BEGIN
      PERFORM cron.unschedule('iva-purge-requests');
    EXCEPTION WHEN OTHERS THEN
      NULL; -- задания ещё не было — это нормально
    END;
    PERFORM cron.schedule('iva-purge-requests', '0 2 1 * *', $cron$SELECT public.iva_purge_requests()$cron$);
    RAISE NOTICE 'Расписание iva-purge-requests создано (1-е число месяца, 02:00).';
  ELSE
    RAISE NOTICE 'pg_cron не включён: запускайте SELECT public.iva_purge_requests() вручную раз в месяц (Database → SQL Editor).';
  END IF;
END $sched$;

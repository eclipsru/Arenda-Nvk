-- ⛔ НЕ ПРИМЕНЯТЬ (05.10.2026, при вливании ветки второго чата work/merge-all-2026-10-03).
-- Этот вариант снова отправляет в MAX ВСЮ заявку с контактами клиента ('record', to_jsonb(new)) и отменил бы
-- применённую владельцем миграцию supabase/migrations/20261003_max_no_contacts.sql (решение №17, вариант А).
-- Полезная часть (заявка сохраняется при сбое уведомления) в max_no_contacts уже есть — проверено стендом
-- tools/tg-migrations-stand.sh на PostgreSQL 16. Файл сохранён только для истории (правило: ничего не удалять).
-- Замок: при запуске в SQL Editor первая команда остановит весь текст с ошибкой.
DO $lock$ BEGIN RAISE EXCEPTION 'НЕ ПРИМЕНЯТЬ: устаревшая миграция вернёт контакты клиентов в MAX. См. supabase/obsolete/README.md'; END $lock$;

-- ============================================================
--  Ива — защита сохранения заявок от сбоя уведомления в MAX (этап П5 / защитный контур).
--
--  Что не так сейчас: триггер orders(AFTER INSERT) вызывает функцию
--  public.fn_order_to_max_hook(), а та без всякой защиты выполняет
--  net.http_post(...). Если расширение pg_net выключено (например, его
--  выключали при диагностике бота) — вызов падает, а вместе с ним падает
--  и INSERT: КЛИЕНТ НЕ МОЖЕТ ОСТАВИТЬ ЗАЯВКУ. Это тихий и опасный сбой:
--  внешне «сайт сломался», а причина — в уведомлении.
--
--  Что делает миграция: заменяет тело этой функции на такое же по смыслу
--  (тот же адрес, тот же ключ, та же отправка всей строки заявки), но:
--    1. обёрнуто в перехват ошибки — сбой уведомления больше НЕ мешает заявке;
--    2. схема расширения pg_net определяется автоматически (как в функциях
--       бота iva_tg_*): работает и `net.http_post`, и `extensions.http_post`,
--       а если pg_net выключен — просто пропускает отправку с NOTICE;
--    3. ничего не удаляется и не создаётся, кроме этой одной функции.
--
--  Текст функции взят из диагностики боевой базы (снимок
--  supabase/bot/iva_tg_snapshot_20261003.sql, блок «orders AFTER INSERT»).
--  Поведение уведомления в MAX не меняется: заявка по-прежнему уходит в MAX
--  целиком, вместе с контактами (это отдельный вопрос — решение владельца).
--
--  Миграция идемпотентная: повторный запуск безопасен.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → вставить весь текст → Run.
--  ПРОВЕРКА:    select prosrc ilike '%exception when others%' as защищено
--                 from pg_proc where proname = 'fn_order_to_max_hook';
--               → должно быть true.
--
--  КАК ОТКАТИТЬ: вернуть исходный текст функции из снимка
--  supabase/bot/iva_tg_snapshot_20261003.sql (блок «orders AFTER INSERT»).
--  На данные и на сайт миграция не влияет.
-- ============================================================

DO $do$
DECLARE
  v_exists boolean;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname = 'fn_order_to_max_hook'
  ) INTO v_exists;

  IF NOT v_exists THEN
    RAISE NOTICE 'Функция fn_order_to_max_hook не найдена — миграция пропущена (в этой базе хук MAX не настроен).';
    RETURN;
  END IF;

  EXECUTE $sql$
CREATE OR REPLACE FUNCTION public.fn_order_to_max_hook()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  DECLARE
    v_ns   text;
    v_call text;
  BEGIN
    -- Отправку уведомления делаем «в стороне» от заявки: любой сбой — только NOTICE.
    BEGIN
      -- В какой схеме живёт pg_net (обычно net; бывает extensions).
      SELECT n.nspname INTO v_ns
        FROM pg_extension e
        JOIN pg_namespace n ON n.oid = e.extnamespace
       WHERE e.extname = 'pg_net';

      IF v_ns IS NULL THEN
        RAISE NOTICE 'order_to_max: pg_net выключен — уведомление пропущено, заявка сохранена';
        RETURN new;
      END IF;

      v_call := CASE WHEN v_ns = 'net' THEN 'net.http_post' ELSE quote_ident(v_ns) || '.http_post' END;

      EXECUTE format('SELECT %s(url := $1, headers := $2, body := $3)', v_call)
        USING 'https://wdxdeatphizclskfmfxi.supabase.co/functions/v1/order_to_max',
              jsonb_build_object(
                'Content-Type', 'application/json',
                'Authorization', 'Bearer sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE'
              ),
              jsonb_build_object('type', 'INSERT', 'table', 'orders', 'record', to_jsonb(new));
    EXCEPTION WHEN OTHERS THEN
      RAISE NOTICE 'order_to_max: уведомление не ушло — заявка сохранена';
    END;

    RETURN new;
  END
$function$;
$sql$;

  RAISE NOTICE 'Функция fn_order_to_max_hook защищена: сбой уведомления в MAX больше не мешает сохранять заявки.';
END
$do$;

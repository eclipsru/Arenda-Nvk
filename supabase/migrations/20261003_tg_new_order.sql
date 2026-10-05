-- ============================================================
--  Ива — уведомление в Telegram о НОВОЙ заявке (этап П5, шаг 2).
--
--  Зачем: сейчас о новой заявке узнаёт только мессенджер MAX (хук
--  fn_order_to_max_hook). В Telegram уведомления приходят позже — когда заявку
--  уже кто-то взял (order_parts). Если заявку никто не берёт, владелец может
--  узнать о ней с опозданием. Эта миграция закрывает промежуток: заявка только
--  появилась → в Telegram сразу уходит короткое сообщение.
--
--  Как: триггер на orders (AFTER INSERT) вызывает уже существующую функцию
--  бота public.iva_tg_send('owner', текст) — ту же, которой пользуются остальные
--  уведомления. Ничего нового настраивать не нужно: токен, получатель, отправка
--  через pg_net — всё как было.
--
--  ВАЖНО (решение владельца №17 от 03.10.2026): контакты клиента в Telegram
--  НЕ отправляются. В сообщении только: номер заявки, состав, срок, способ
--  получения (без адреса), время и ссылка в кабинет, где контакты видно после
--  входа. Имени, телефона, адреса, комментария и почты клиента здесь нет.
--
--  Безопасность для заявок: тело триггера полностью защищено от сбоя — если
--  бот не настроен, токен не задан или pg_net выключен, заявка всё равно
--  сохранится (клиент увидит «Заявка принята»), а в логах будет NOTICE.
--
--  Миграция идемпотентная: повторный запуск ничего не ломает и не дублирует.
--
--  КАК ПРИМЕНИТЬ (действие владельца): Supabase → SQL Editor → вставить весь
--  текст → Run. Ожидаемо: внизу одно сообщение NOTICE про созданный триггер.
--
--  ПРОВЕРКА:  select tgname from pg_trigger where tgname = 'iva_tg_new_order';
--             -- и попросить кого-нибудь оставить тестовую заявку с сайта
--
--  КАК ОТКАТИТЬ:
--    DROP TRIGGER IF EXISTS iva_tg_new_order ON public.orders;
--    DROP FUNCTION IF EXISTS public.iva_tg_new_order();
-- ============================================================

CREATE OR REPLACE FUNCTION public.iva_tg_new_order()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  DECLARE
    v_msg   text;
    v_days  text;
    v_how   text;
    v_when  text;
    v_n     integer;
  BEGIN
    -- Всё тело под защитой: сбой уведомления не должен мешать сохранить заявку.
    BEGIN
      -- Состав заявки (может быть пустым — тогда прочерк).
      -- Срок аренды со склонением: 1 день / 2 дня / 5 дней.
      IF coalesce(new.days, 0) > 0 THEN
        v_n := new.days % 100;
        IF v_n > 10 AND v_n < 20 THEN
          v_days := new.days || ' дней';
        ELSIF new.days % 10 = 1 THEN
          v_days := new.days || ' день';
        ELSIF new.days % 10 BETWEEN 2 AND 4 THEN
          v_days := new.days || ' дня';
        ELSE
          v_days := new.days || ' дней';
        END IF;
      END IF;

      -- Способ получения БЕЗ адреса: адрес — это тоже персональные данные.
      IF new.get_method ILIKE 'доставка%' THEN
        v_how := '🚚 Доставка';
      ELSIF new.get_method ILIKE 'самовывоз%' THEN
        v_how := '🏠 Самовывоз';
      END IF;

      -- Время в московском времени (владельцу так понятнее).
      v_when := to_char(
        coalesce(new.created_at, now()) AT TIME ZONE 'Europe/Moscow',
        'DD.MM.YYYY, HH24:MI'
      );

      v_msg := format(
        E'🆕 <b>Новая заявка</b> №%s\n\n%s%s%s🕒 %s МСК\n\nКонтакты клиента — в кабинете:\nhttps://eclipsru.github.io/Arenda-Nvk/chief.html',
        coalesce(new.id::text, '—'),
        '🧰 ' || coalesce(nullif(new.tools, ''), '—') || E'\n',
        CASE WHEN v_days IS NULL THEN '' ELSE '📅 ' || v_days || E'\n' END,
        CASE WHEN v_how  IS NULL THEN '' ELSE v_how || E'\n' END,
        v_when
      );

      PERFORM public.iva_tg_send('owner', v_msg);
    EXCEPTION WHEN OTHERS THEN
      -- Никогда не мешаем заявке: сообщаем в лог и живём дальше.
      RAISE NOTICE 'iva_tg_new_order: уведомление не ушло (заявка сохранена)';
    END;

    RETURN new;
  END
$function$;

COMMENT ON FUNCTION public.iva_tg_new_order() IS
  'Триггер orders(INSERT): короткое уведомление владельцу в Telegram о новой заявке. Без контактов клиента (решение №17). Сбой уведомления не мешает сохранить заявку.';

DO $do$
BEGIN
  IF to_regclass('public.orders') IS NULL THEN
    RAISE NOTICE 'Таблица public.orders не найдена — триггер не создан.';
    RETURN;
  END IF;

  EXECUTE 'DROP TRIGGER IF EXISTS iva_tg_new_order ON public.orders';
  EXECUTE 'CREATE TRIGGER iva_tg_new_order
             AFTER INSERT ON public.orders
             FOR EACH ROW EXECUTE FUNCTION public.iva_tg_new_order()';

  IF to_regprocedure('public.iva_tg_send(text,text)') IS NULL THEN
    RAISE NOTICE 'Триггер iva_tg_new_order создан. Внимание: функция бота public.iva_tg_send(text,text) не найдена — уведомления начнут уходить, как только бот будет установлен (повторять миграцию не нужно).';
  ELSE
    RAISE NOTICE 'Триггер iva_tg_new_order создан (уведомления о новых заявках включены).';
  END IF;
END
$do$;

-- ============================================================
--  Ива — Telegram-бот без контактов клиента (этап П5, решение владельца №17 от 03.10.2026).
--
--  Что меняется (только тексты двух уведомлений; бот, токен, получатели — как были):
--    1. «Пункт проката взял заявку» (владельцу) и «На ваш инструмент пришла заявка»
--       (пункту): БЕЗ имени и телефона клиента и без способа получения.
--       Остаются: номер заявки, состав, кто взял, комиссия — и ссылка в кабинет,
--       где контакты видны после входа.
--    2. «Новая анкета арендодателя» (владельцу): БЕЗ ФИО, телефона, email и компании
--       заявителя. Остаются: город, регион, категории, число инструментов — и ссылка
--       в кабинет владельца.
--  Зачем: в политике обработки данных Telegram не указан; сообщения в Telegram
--  хранятся у стороннего сервиса за рубежом. Контакты остаются только на сайте.
--
--  Не меняется: «статус заявки изменился» (контактов там и не было), отправка в MAX
--  (fn_order_to_max_hook) и во ВКонтакте (fn_vk_wall_post), настройка бота.
--
--  Безопасно: только CREATE OR REPLACE двух существующих функций, ничего не удаляется.
--  Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Внизу появится таблица из 2 строк — обе должны быть «✅».
--
--  КАК ОТКАТИТЬ: из файла supabase/bot/iva_tg_snapshot_20261003.sql вставить два блока
--  «ОТКАТ (1 из 2)» и «ОТКАТ (2 из 2)» → Run. Вернутся прежние тексты с контактами.
-- ============================================================

CREATE OR REPLACE FUNCTION public.iva_tg_on_part()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
      DECLARE
        v      jsonb;
        v_who  text;
        v_msg  text;
      BEGIN
        BEGIN
          v := to_jsonb(new);

          -- Кто взял: имя пункта из admins, иначе почта пункта (это не клиент).
          SELECT coalesce(nullif(a.full_name, ''), nullif(a.company, ''), a.email)
            INTO v_who
            FROM public.admins a
           WHERE lower(a.email) = lower(coalesce(new.owner_email, ''))
           LIMIT 1;

          -- Решение владельца №17: контакты клиента в Telegram не отправляем.
          v_msg := format(
            E'🤝 <b>Пункт проката взял заявку</b> #%s\n\n%s\n\n🏷 %s\nКомиссия: %s\n\nКонтакты клиента — в кабинете: https://eclipsru.github.io/Arenda-Nvk/chief.html',
            coalesce(v->>'order_id', '?'),
            coalesce(nullif(v->>'tools_text', ''), '—'),
            coalesce(v_who, new.owner_email, '—'),
            coalesce((v->>'fee_pct')::text, '—') || '%'
          );
          PERFORM public.iva_tg_send('owner', v_msg);

          -- Самому пункту, если его чат привязан (key = 'admin:<почта>').
          IF coalesce(new.owner_email, '') <> '' THEN
            PERFORM public.iva_tg_send(
              'admin:' || lower(new.owner_email),
              format(
                E'🆕 <b>На ваш инструмент пришла заявка</b> #%s\n\n%s\n\nКонтакты клиента — в кабинете: https://eclipsru.github.io/Arenda-Nvk/cabinet.html',
                coalesce(v->>'order_id', '?'),
                coalesce(nullif(v->>'tools_text', ''), '—')
              )
            );
          END IF;
        EXCEPTION WHEN OTHERS THEN
          RAISE NOTICE 'iva_tg_on_part: уведомление не ушло: %', sqlerrm;
        END;
        RETURN new;
      END $function$;


CREATE OR REPLACE FUNCTION public.iva_tg_on_landlord()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
      DECLARE v jsonb; v_msg text;
      BEGIN
        BEGIN
          v := to_jsonb(new);
          -- Решение владельца №17: ФИО, телефон, email и компанию заявителя в Telegram не отправляем.
          v_msg := format(
            E'🧑‍💼 <b>Новая анкета арендодателя</b>\n\nГород: %s\nРегион: %s\nКатегории: %s\nИнструментов: %s\n\nАнкета и контакты — в кабинете: https://eclipsru.github.io/Arenda-Nvk/chief.html',
            coalesce(nullif(v->>'city', ''), '—'),
            coalesce(nullif(v->>'region', ''), '—'),
            coalesce(nullif(array_to_string(ARRAY(SELECT jsonb_array_elements_text(v->'categories')), ', '), ''), '—'),
            coalesce(v->>'inventory_count', '—')
          );
          PERFORM public.iva_tg_send('owner', v_msg);
        EXCEPTION WHEN OTHERS THEN
          RAISE NOTICE 'iva_tg_on_landlord: уведомление не ушло: %', sqlerrm;
        END;
        RETURN new;
      END $function$;


-- Самопроверка: в текстах уведомлений больше нет контактов.
SELECT p.proname AS "Функция",
       CASE WHEN p.prosrc ~* '(''phone''|''name''|''full_name''|''email''|''company''|''get_method''|''address'')'
            THEN '❌ контакты всё ещё отправляются — пришлите это агенту'
            ELSE '✅ контактов в сообщении нет' END AS "Результат"
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.proname IN ('iva_tg_on_part', 'iva_tg_on_landlord')
ORDER BY 1;

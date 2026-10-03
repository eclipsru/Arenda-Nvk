-- ============================================================
--  Ива — уведомление в MAX без контактов клиента (этап П5, решение владельца от 03.10.2026:
--  «MAX — как Telegram», вариант А; продолжение решения №17).
--
--  Было: при каждой новой заявке база отправляла в функцию Supabase order_to_max
--  (мессенджер MAX) ВСЮ строку заявки: имя, телефон, email, адрес, комментарий.
--  Стало: отправляются только номер заявки, состав, срок и дата. На месте контактов —
--  «—» и ссылка в кабинет, где контакты видны после входа. Отправляется только
--  разрешённый список полей: новое поле в заявке само в MAX не попадёт.
--  Плюс защита: если MAX или pg_net недоступны, заявка всё равно сохраняется
--  (раньше сбой отправки мог сорвать сохранение заявки).
--
--  Безопасно: только CREATE OR REPLACE одной существующей функции; триггер, MAX,
--  Telegram, ВКонтакте — не трогаются. Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Внизу — 1 строка «✅».
--  КАК ОТКАТИТЬ: вставить весь текст supabase/bot/max_rollback_20261003.sql → Run.
-- ============================================================

CREATE OR REPLACE FUNCTION public.fn_order_to_max_hook()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v jsonb;
begin
  begin
    v := to_jsonb(new);
    -- Решение владельца (03.10.2026): контакты клиента в MAX не отправляем.
    perform net.http_post(
      url := 'https://wdxdeatphizclskfmfxi.supabase.co/functions/v1/order_to_max',
      headers := jsonb_build_object(
        'Content-Type', 'application/json',
        'Authorization', 'Bearer sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE'
      ),
      body := jsonb_build_object('type', 'INSERT', 'table', 'orders', 'record', jsonb_build_object(
        'id',         v->'id',
        'tools',      v->'tools',
        'days',       v->'days',
        'created_at', v->'created_at',
        'name',       '—',
        'phone',      '—',
        'user_email', '',
        'address',    '',
        'get_method', '',
        'comment',    'Контакты клиента — в кабинете: https://eclipsru.github.io/Arenda-Nvk/chief.html'
      ))
    );
  exception when others then
    raise notice 'fn_order_to_max_hook: уведомление в MAX не ушло: %', sqlerrm;
  end;
  return new;
end;
$function$;


-- Самопроверка: в MAX больше не уходит вся строка заявки.
SELECT p.proname AS "Функция",
       CASE WHEN p.prosrc ~* '''record''\s*,\s*to_jsonb\(\s*new\s*\)'
              OR p.prosrc ~* '(v|new)\s*(->>?|\.)\s*''?(name|phone|user_email|address|comment)\M'
            THEN '❌ контакты всё ещё уходят в MAX — пришлите это агенту'
            WHEN p.prosrc !~ 'Контакты клиента — в кабинете'
            THEN '❌ правка не применилась — пришлите это агенту'
            ELSE '✅ в MAX уходят только номер, состав, срок и дата' END AS "Результат"
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.proname = 'fn_order_to_max_hook';

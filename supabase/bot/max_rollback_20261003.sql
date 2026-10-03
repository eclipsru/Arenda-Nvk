-- ============================================================
--  ОТКАТ правки supabase/migrations/20261003_max_no_contacts.sql:
--  возвращает исходную функцию fn_order_to_max_hook «как в базе на 03.10.2026»
--  (снята владельцем через tools/diag-bot.sql). ⚠️ Снова отправляет в MAX ВСЮ строку
--  заявки с контактами и снова без защиты от сбоя.
--  Ключ в заголовке — публичный ключ сайта (sb_publishable_…), он и так открыт в assets/sb.js.
--  Применение: Supabase → SQL Editor → вставить весь текст → Run.
-- ============================================================
CREATE OR REPLACE FUNCTION public.fn_order_to_max_hook()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  perform net.http_post(
    url := 'https://wdxdeatphizclskfmfxi.supabase.co/functions/v1/order_to_max',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE'
    ),
    body := jsonb_build_object('type','INSERT','table','orders','record', to_jsonb(NEW))
  );
  return new;
end;
$function$;

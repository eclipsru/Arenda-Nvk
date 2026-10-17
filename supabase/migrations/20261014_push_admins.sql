-- ============================================================
--  Ива 10.74 — мгновенные уведомления админам через Firebase (FCM), решение владельца 14.10.2026.
--
--  Что делает:
--    • приложение админа присылает свой push-токен (rpc iva_push_register) — только админам и владельцу;
--    • новое сообщение админу (app_messages.to_email) или новая заявка на его инструмент
--      (order_parts.owner_email) → база будит функцию iva-push, та шлёт на телефон сигнал «проверь сейчас»;
--    • приложение само забирает новое обычным запросом и показывает уведомление.
--  Через Google не идёт ничего личного: в push только буква «m» (сообщение) или «o» (заявка),
--  без текста, имён, адресов и телефонов.
--
--  Как защищено (как письма П5, 20261003_order_email.sql):
--    • таблицы токенов и очереди закрыты (RLS включён, разрешений нет — ни сайт, ни приложение их не читают);
--    • у каждого задания свой одноразовый пропуск: функцию нельзя заставить слать push кому угодно,
--      адресаты берутся из базы, пропуск срабатывает один раз и живёт час;
--    • сбой push не мешает отправить сообщение или оформить заявку (ошибки глушатся внутри триггеров);
--    • токены телефонов, которые Google объявил недействительными, удаляются сами.
--
--  Безопасно: только CREATE ... IF NOT EXISTS / CREATE OR REPLACE, ничего не удаляется.
--  Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Внизу — таблица проверки, все строки «✅».
--  КАК ОТКЛЮЧИТЬ (откат, данные не удаляются):
--      DROP TRIGGER IF EXISTS trg_iva_push_msg ON public.app_messages;
--      DROP TRIGGER IF EXISTS trg_iva_push_part ON public.order_parts;
-- ============================================================

-- 1. Токены телефонов админов (один телефон — один токен; у админа может быть несколько телефонов)
CREATE TABLE IF NOT EXISTS public.iva_push_tokens (
  token      text        PRIMARY KEY,
  email      text        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS iva_push_tokens_email ON public.iva_push_tokens (email);
ALTER TABLE public.iva_push_tokens ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_push_tokens FROM anon, authenticated;
COMMENT ON TABLE public.iva_push_tokens IS
  'Push-токены (FCM) телефонов админов, 10.74. Закрыта: читают только функции базы.';

-- 2. Очередь сигналов (одноразовые пропуски для функции iva-push)
CREATE TABLE IF NOT EXISTS public.iva_push_jobs (
  id         bigserial   PRIMARY KEY,
  pass       text        NOT NULL,
  kind       text        NOT NULL CHECK (kind IN ('m', 'o')),
  email      text        NOT NULL,
  status     text        NOT NULL DEFAULT 'pending',
  sent       int,
  err        text,
  created_at timestamptz NOT NULL DEFAULT now(),
  done_at    timestamptz
);
ALTER TABLE public.iva_push_jobs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_push_jobs FROM anon, authenticated;
COMMENT ON TABLE public.iva_push_jobs IS
  'Очередь push-сигналов 10.74: pending → sending → sent / failed. Хранится сутки.';

-- 3. Приложение админа присылает токен
CREATE OR REPLACE FUNCTION public.iva_push_register(p_token text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_email text := lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
begin
  if v_email = '' then
    raise exception 'нужен вход' using errcode = '28000';
  end if;
  if not (v_email = 'eclips.ru@mail.ru'
          or exists (select 1 from public.admins a
                      where coalesce(a.active, false) and a.role in ('admin', 'chief')
                        and lower(btrim(coalesce(a.email, ''))) = v_email)) then
    return jsonb_build_object('ok', false, 'reason', 'not_admin');   -- клиентам push не нужен (решение 14.10)
  end if;
  if p_token is null or length(p_token) < 20 or length(p_token) > 4096 or p_token ~ '\s' then
    raise exception 'неверный токен' using errcode = '22023';
  end if;
  insert into public.iva_push_tokens (token, email) values (p_token, v_email)
  on conflict (token) do update set email = excluded.email, updated_at = now();
  -- не больше 5 телефонов на админа: самые старые забываем
  delete from public.iva_push_tokens t
   where t.email = v_email
     and t.token not in (select token from public.iva_push_tokens
                          where email = v_email order by updated_at desc limit 5);
  return jsonb_build_object('ok', true);
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_push_register(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_push_register(text) TO authenticated;

-- 4. Вышли из аккаунта (или вошёл клиент) — телефон просит забыть свой токен.
--    Знать токен = быть этим телефоном; чужих токенов никто не знает (они не выдаются наружу).
CREATE OR REPLACE FUNCTION public.iva_push_forget(p_token text)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  delete from public.iva_push_tokens where token = p_token;
$function$;
REVOKE ALL ON FUNCTION public.iva_push_forget(text) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_push_forget(text) TO anon, authenticated;

-- 5. Поставить сигнал в очередь и разбудить функцию iva-push (только если у адресата есть токены)
CREATE OR REPLACE FUNCTION public.iva_push_enqueue(p_kind text, p_email text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_email text := lower(btrim(coalesce(p_email, '')));
  v_id    bigint;
  v_pass  text;
begin
  if v_email = '' or not exists (select 1 from public.iva_push_tokens where email = v_email) then
    return;
  end if;
  delete from public.iva_push_jobs where created_at < now() - interval '1 day';
  v_pass := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  insert into public.iva_push_jobs (pass, kind, email) values (v_pass, p_kind, v_email)
  returning id into v_id;
  perform net.http_post(
    url := 'https://wdxdeatphizclskfmfxi.supabase.co/functions/v1/iva-push',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE'
    ),
    body := jsonb_build_object('id', v_id, 'pass', v_pass)
  );
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_push_enqueue(text, text) FROM public, anon, authenticated;

-- 6. Триггер: новое сообщение → сигнал получателю (если это не он сам себе)
CREATE OR REPLACE FUNCTION public.iva_push_on_message()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  begin
    if lower(btrim(coalesce(new.to_email, ''))) <> lower(btrim(coalesce(new.from_email, ''))) then
      perform public.iva_push_enqueue('m', new.to_email);
    end if;
  exception when others then
    raise notice 'iva_push_on_message: сигнал не поставлен: %', sqlerrm;
  end;
  return new;
end;
$function$;
DROP TRIGGER IF EXISTS trg_iva_push_msg ON public.app_messages;
CREATE TRIGGER trg_iva_push_msg AFTER INSERT ON public.app_messages
  FOR EACH ROW EXECUTE FUNCTION public.iva_push_on_message();

-- 7. Триггер: новая заявка (часть заявки) на инструмент админа → сигнал этому админу
CREATE OR REPLACE FUNCTION public.iva_push_on_part()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  begin
    if coalesce(new.status, 'new') = 'new' then
      perform public.iva_push_enqueue('o', new.owner_email);
    end if;
  exception when others then
    raise notice 'iva_push_on_part: сигнал не поставлен: %', sqlerrm;
  end;
  return new;
end;
$function$;
DROP TRIGGER IF EXISTS trg_iva_push_part ON public.order_parts;
CREATE TRIGGER trg_iva_push_part AFTER INSERT ON public.order_parts
  FOR EACH ROW EXECUTE FUNCTION public.iva_push_on_part();

-- 8. Функция iva-push забирает задание по пропуску: вид сигнала и токены адресата
CREATE OR REPLACE FUNCTION public.iva_push_take(p_id bigint, p_pass text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  j public.iva_push_jobs;
begin
  update public.iva_push_jobs set status = 'sending'
   where id = p_id and pass = p_pass and status = 'pending'
     and created_at > now() - interval '1 hour'
  returning * into j;
  if not found then
    return null;                                   -- чужой/использованный/просроченный пропуск
  end if;
  return jsonb_build_object('kind', j.kind,
    'tokens', coalesce((select jsonb_agg(t.token) from public.iva_push_tokens t where t.email = j.email), '[]'::jsonb));
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_push_take(bigint, text) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_push_take(bigint, text) TO anon, authenticated;

-- 9. Функция iva-push сообщает итог; недействительные токены (Google: UNREGISTERED) — удаляются
CREATE OR REPLACE FUNCTION public.iva_push_done(p_id bigint, p_pass text, p_sent int, p_bad text[], p_err text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  j public.iva_push_jobs;
begin
  update public.iva_push_jobs
     set status = case when coalesce(p_sent, 0) > 0 then 'sent' else 'failed' end,
         sent = p_sent, err = left(regexp_replace(coalesce(p_err, ''), '[^\s@]+@[^\s@]+', '…', 'g'), 300), done_at = now()
   where id = p_id and pass = p_pass and status = 'sending'
  returning * into j;
  if found and p_bad is not null then
    delete from public.iva_push_tokens where email = j.email and token = any(p_bad);
  end if;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_push_done(bigint, text, int, text[], text) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_push_done(bigint, text, int, text[], text) TO anon, authenticated;

-- 10. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('таблица токенов закрыта (RLS)',  (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_push_tokens'::regclass)),
  ('очередь сигналов закрыта (RLS)', (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_push_jobs'::regclass)),
  ('триггер на сообщения',            EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_push_msg' AND NOT tgisinternal)),
  ('триггер на заявки',               EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_push_part' AND NOT tgisinternal)),
  ('pg_net включён',                  EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_net')),
  ('сайт не видит токены',            NOT has_table_privilege('anon', 'public.iva_push_tokens', 'SELECT'))
) AS x(check_name, ok);

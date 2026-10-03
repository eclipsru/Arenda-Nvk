-- ============================================================
--  Ива — письма клиенту о его заявке (этап П5, решение владельца от 03.10.2026).
--
--  Какие письма (только клиенту, только если заявка оформлена из аккаунта с email):
--    1. «Заявка №N принята»          — когда заявка создана (одно письмо на заявку);
--    2. «Инструмент выдан»           — пункт проката нажал «Выдал»;
--    3. «Аренда завершена» + отзыв   — пункт проката нажал «Вернули».
--  Отправка: с почтового ящика владельца на mail.ru (без своего домена) через функцию
--  Supabase order_email (supabase/functions/order_email/index.ts).
--
--  Как защищено:
--    • у каждого письма свой одноразовый пропуск (token) — функцию нельзя заставить
--      написать произвольному адресату или произвольный текст: адрес и текст берутся
--      из базы, пропуск срабатывает один раз и живёт сутки;
--    • в очереди писем (iva_email_jobs) НЕТ email и имён — только номера заявок;
--      адреса из текста ошибок вырезаются;
--    • посетители сайта таблицу очереди не видят (RLS включён, разрешений нет);
--    • сбой почты не мешает сохранить заявку или сменить статус.
--
--  Безопасно: только CREATE ... IF NOT EXISTS / CREATE OR REPLACE, ничего не удаляется.
--  Повторный запуск ничего не ломает.
--
--  КАК ПРИМЕНИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ текст → Run.
--  Внизу — таблица из 4 строк, все «✅».
--  КАК ОТКЛЮЧИТЬ ПИСЬМА (откат): одна строка
--      DROP TRIGGER IF EXISTS trg_iva_email_part ON public.order_parts;
--  — это не удаляет данные, только перестаёт ставить письма в очередь
--  (по правилам проекта — только с согласия владельца).
-- ============================================================

-- 1. Очередь писем (без персональных данных)
CREATE TABLE IF NOT EXISTS public.iva_email_jobs (
  id         bigserial PRIMARY KEY,
  token      text        NOT NULL,
  kind       text        NOT NULL CHECK (kind IN ('new', 'rented', 'closed')),
  order_id   bigint      NOT NULL,
  part_id    bigint,
  dedup      text        NOT NULL UNIQUE,
  status     text        NOT NULL DEFAULT 'pending',
  err        text,
  created_at timestamptz NOT NULL DEFAULT now(),
  done_at    timestamptz
);
ALTER TABLE public.iva_email_jobs ENABLE ROW LEVEL SECURITY;
COMMENT ON TABLE public.iva_email_jobs IS
  'Очередь писем клиентам о заявках (П5). Без email и имён — только номера. Статусы: pending → sending → sent / failed / skipped.';


-- 2. Триггер: ставит письмо в очередь и будит функцию order_email
CREATE OR REPLACE FUNCTION public.iva_email_on_part()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_kind  text;
  v_dedup text;
  v_id    bigint;
  v_token text;
begin
  begin
    if tg_op = 'INSERT' then
      v_kind  := 'new';
      v_dedup := 'order:' || new.order_id || ':new';          -- одно письмо на всю заявку
    elsif tg_op = 'UPDATE'
          and new.status is distinct from old.status
          and new.status in ('rented', 'closed') then
      v_kind  := new.status;
      v_dedup := 'part:' || new.id || ':' || new.status;
    else
      return new;
    end if;

    v_token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
    insert into public.iva_email_jobs (token, kind, order_id, part_id, dedup)
    values (v_token, v_kind, new.order_id, case when v_kind = 'new' then null else new.id end, v_dedup)
    on conflict (dedup) do nothing
    returning id into v_id;

    if v_id is not null then
      perform net.http_post(
        url := 'https://wdxdeatphizclskfmfxi.supabase.co/functions/v1/order_email',
        headers := jsonb_build_object(
          'Content-Type', 'application/json',
          'Authorization', 'Bearer sb_publishable_dtRaEHNNPBFbHFvg8hw9iA_FqJSz9BE'
        ),
        body := jsonb_build_object('id', v_id, 'token', v_token)
      );
    end if;
  exception when others then
    raise notice 'iva_email_on_part: письмо не поставлено в очередь: %', sqlerrm;
  end;
  return new;
end;
$function$;


-- 3. Функция order_email забирает письмо по пропуску: адрес и текст — из базы
CREATE OR REPLACE FUNCTION public.iva_email_take(p_id bigint, p_token text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  j       public.iva_email_jobs;
  v_to    text;
  v_tools text;
  v_subj  text;
  v_text  text;
  v_site  constant text := 'https://eclipsru.github.io/Arenda-Nvk/';
begin
  update public.iva_email_jobs
     set status = 'sending'
   where id = p_id and token = p_token and status = 'pending'
     and created_at > now() - interval '1 day'
  returning * into j;
  if not found then
    return null;                                   -- чужой/использованный/просроченный пропуск
  end if;

  select lower(trim(o.user_email)) into v_to from public.orders o where o.id = j.order_id;
  if v_to is null or v_to !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then
    update public.iva_email_jobs set status = 'skipped', err = 'нет email (заявка гостя)', done_at = now()
     where id = j.id;
    return jsonb_build_object('skip', true);
  end if;

  if j.kind = 'new' then
    select string_agg(nullif(p.tools_text, ''), '; ' order by p.id) into v_tools
      from public.order_parts p where p.order_id = j.order_id;
    if v_tools is null then
      select nullif(o.tools, '') into v_tools from public.orders o where o.id = j.order_id;
    end if;
  else
    select nullif(p.tools_text, '') into v_tools from public.order_parts p where p.id = j.part_id;
  end if;
  v_tools := coalesce(v_tools, 'инструмент');

  if j.kind = 'new' then
    v_subj := format('Ива: заявка №%s принята', j.order_id);
    v_text := format(E'Здравствуйте!\n\nВаша заявка №%s на сайте «Ива» принята и передана в пункт проката:\n%s\n\nПункт проката свяжется с вами по телефону, указанному в заявке, чтобы уточнить наличие, цену и получение.\n\nСтатус заявки — в личном кабинете: %saccount.html',
                     j.order_id, v_tools, v_site);
  elsif j.kind = 'rented' then
    v_subj := format('Ива: инструмент по заявке №%s выдан', j.order_id);
    v_text := format(E'Здравствуйте!\n\nПункт проката отметил, что выдал вам инструмент по заявке №%s:\n%s\n\nАренда началась. Когда вернёте инструмент, пункт отметит возврат, и вам придёт письмо.\n\nЗаявки — в личном кабинете: %saccount.html',
                     j.order_id, v_tools, v_site);
  else
    v_subj := format('Ива: аренда по заявке №%s завершена', j.order_id);
    v_text := format(E'Здравствуйте!\n\nАренда по заявке №%s завершена:\n%s\n\nСпасибо, что воспользовались «Ивой»! Если не сложно — оцените пункт проката в личном кабинете, отзыв помогает другим: %saccount.html\n\nПонадобится инструмент снова — каталог: %s',
                     j.order_id, v_tools, v_site, v_site);
  end if;
  v_text := v_text || E'\n\n—\nЭто автоматическое письмо о вашей заявке на сайте «Ива — инструмент в аренду». Рекламы не присылаем. Если вы не оформляли эту заявку — просто ответьте на письмо.';

  return jsonb_build_object('to', v_to, 'subject', v_subj, 'text', v_text);
end;
$function$;


-- 4. Функция order_email сообщает результат; адреса из текста ошибки вырезаются
CREATE OR REPLACE FUNCTION public.iva_email_done(p_id bigint, p_token text, p_ok boolean, p_err text)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  update public.iva_email_jobs
     set status  = case when p_ok then 'sent' else 'failed' end,
         err     = left(regexp_replace(coalesce(p_err, ''), '\S+@\S+', '<email>', 'g'), 300),
         done_at = now()
   where id = p_id and token = p_token and status = 'sending';
$function$;


-- 5. Сам триггер (CREATE OR REPLACE TRIGGER — PostgreSQL 14+, в Supabase есть)
CREATE OR REPLACE TRIGGER trg_iva_email_part
  AFTER INSERT OR UPDATE OF status ON public.order_parts
  FOR EACH ROW EXECUTE FUNCTION public.iva_email_on_part();


-- Самопроверка
SELECT * FROM (VALUES
  (1, 'Очередь писем',
      CASE WHEN to_regclass('public.iva_email_jobs') IS NOT NULL
            AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_email_jobs'::regclass)
           THEN '✅ есть, посетителям закрыта' ELSE '❌ нет — пришлите это агенту' END),
  (2, 'Триггер на заявках',
      CASE WHEN EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_iva_email_part' AND NOT tgisinternal)
           THEN '✅ письма будут ставиться в очередь' ELSE '❌ нет — пришлите это агенту' END),
  (3, 'Выдача письма по пропуску',
      CASE WHEN to_regprocedure('public.iva_email_take(bigint,text)') IS NOT NULL
            AND to_regprocedure('public.iva_email_done(bigint,text,boolean,text)') IS NOT NULL
           THEN '✅ есть' ELSE '❌ нет — пришлите это агенту' END),
  (4, 'Чужой пропуск не работает',
      CASE WHEN public.iva_email_take(-1, 'проверка') IS NULL
           THEN '✅ без пропуска письмо не выдаётся' ELSE '❌ пришлите это агенту' END)
) AS t("№", "Проверка", "Результат") ORDER BY 1;

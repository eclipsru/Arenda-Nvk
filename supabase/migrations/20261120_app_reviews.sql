-- ============================================================
--  Ива · страница приложения (app.html) как в Google Play — оценки и отзывы о приложении
--  (просьба владельца 02.11.2026: «страничка как в плеймаркете с тем же функционалом, отзывами о приложении и рейтингом»).
--
--  Что делает:
--   • iva_app_reviews — один отзыв на аккаунт: оценка 1–5, текст до 1000 знаков, версия приложения, «полезно»,
--     ответ разработчика. Имя на странице — только первое слово имени из профиля входа (как в Google Play), почта
--     никому не отдаётся. Новых личных данных не собирается.
--   • iva_app_review_votes — «Отзыв полезен? Да / Нет» (один голос на аккаунт, за свой отзыв голосовать нельзя).
--   • Цензура (SQL 10.92 iva_censor, если есть; иначе iva_has_mat 10.71): мат — отзыв не сохраняется, человек видит
--     сообщение; сомнительное — отзыв «на проверке» (виден автору и создателю), создатель публикует или скрывает.
--   • Создатель площадки (fn_is_chief) прямо на странице: «Ответить» (ответ разработчика), «Скрыть» / «Опубликовать».
--   • Таблицы закрыты (RLS без прав) — только через функции ниже. Смотреть оценки и отзывы может любой, даже без входа.
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261120_app_reviews_rollback.sql (удалит отзывы о приложении).
-- ============================================================

DO $do$
BEGIN
  IF to_regprocedure('public.fn_is_chief()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет fn_is_chief() — это не база «Ивы» (или не выполнены ранние SQL)';
  END IF;
END $do$;

-- 1. Таблицы
CREATE TABLE IF NOT EXISTS public.iva_app_reviews (
  id          bigserial   PRIMARY KEY,
  email       text        NOT NULL UNIQUE CHECK (email = lower(btrim(email)) AND email <> ''),
  name        text        NOT NULL DEFAULT '' CHECK (char_length(name) <= 40),
  rating      smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
  body        text        NOT NULL DEFAULT '' CHECK (char_length(body) <= 1000),
  app_version text        NOT NULL DEFAULT '' CHECK (char_length(app_version) <= 20),
  status      text        NOT NULL DEFAULT 'visible' CHECK (status IN ('visible', 'pending', 'hidden')),
  helpful     int         NOT NULL DEFAULT 0,
  reply       text        NOT NULL DEFAULT '' CHECK (char_length(reply) <= 1000),
  reply_at    timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.iva_app_reviews IS 'Ива: отзывы о приложении на странице app.html (как в Google Play). Только через функции iva_app_review*';
CREATE INDEX IF NOT EXISTS idx_iva_app_reviews_shown ON public.iva_app_reviews (status, helpful DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.iva_app_review_votes (
  review_id  bigint      NOT NULL REFERENCES public.iva_app_reviews(id) ON DELETE CASCADE,
  email      text        NOT NULL CHECK (email = lower(btrim(email)) AND email <> ''),
  helpful    boolean     NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (review_id, email)
);
COMMENT ON TABLE public.iva_app_review_votes IS 'Ива: «Отзыв полезен? Да / Нет» на странице app.html';

ALTER TABLE public.iva_app_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.iva_app_review_votes ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_app_reviews FROM public, anon, authenticated;
REVOKE ALL ON public.iva_app_review_votes FROM public, anon, authenticated;
REVOKE ALL ON SEQUENCE public.iva_app_reviews_id_seq FROM public, anon, authenticated;

-- служебное: почта входа
CREATE OR REPLACE FUNCTION public.iva_app_review_me()
 RETURNS text
 LANGUAGE sql
 STABLE
 SET search_path TO 'public'
AS $function$
  select lower(btrim(coalesce(auth.jwt() ->> 'email', '')));
$function$;
REVOKE ALL ON FUNCTION public.iva_app_review_me() FROM public, anon, authenticated;

-- 2. Сводка: средняя оценка, сколько отзывов, сколько каждой оценки (только опубликованные)
CREATE OR REPLACE FUNCTION public.iva_app_reviews_stats()
 RETURNS TABLE (total int, avg numeric, s1 int, s2 int, s3 int, s4 int, s5 int)
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select count(*)::int, coalesce(round(avg(rating)::numeric, 1), 0),
         count(*) filter (where rating = 1)::int, count(*) filter (where rating = 2)::int,
         count(*) filter (where rating = 3)::int, count(*) filter (where rating = 4)::int,
         count(*) filter (where rating = 5)::int
    from public.iva_app_reviews where status = 'visible';
$function$;
REVOKE ALL ON FUNCTION public.iva_app_reviews_stats() FROM public;
GRANT EXECUTE ON FUNCTION public.iva_app_reviews_stats() TO anon, authenticated;

-- 3. Список: опубликованные; свой — в любом статусе; создателю — все (с пометкой статуса)
--    p_sort: 'helpful' (самые полезные) | 'new' (сначала новые); p_stars: 0 — все, 1–5 — только эта оценка
CREATE OR REPLACE FUNCTION public.iva_app_reviews_list(p_sort text DEFAULT 'helpful', p_stars int DEFAULT 0,
                                                       p_limit int DEFAULT 20, p_offset int DEFAULT 0)
 RETURNS TABLE (id bigint, name text, rating int, body text, app_version text, created_at timestamptz,
                updated_at timestamptz, helpful int, reply text, reply_at timestamptz, mine boolean,
                my_vote boolean, status text)
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me    text := public.iva_app_review_me();
  chief boolean := me <> '' and coalesce(public.fn_is_chief(), false);
begin
  return query
  select r.id, case when r.name = '' then 'Пользователь Ивы' else r.name end, r.rating::int, r.body, r.app_version,
         r.created_at, r.updated_at, r.helpful, r.reply, r.reply_at,
         (me <> '' and r.email = me),
         (select v.helpful from public.iva_app_review_votes v where v.review_id = r.id and v.email = me and me <> ''),
         case when chief or (me <> '' and r.email = me) then r.status else 'visible' end
    from public.iva_app_reviews r
   where (r.status = 'visible' or chief or (me <> '' and r.email = me))
     and (coalesce(p_stars, 0) = 0 or r.rating = p_stars)
   order by (me <> '' and r.email = me) desc,
            case when p_sort = 'new' then 0 else r.helpful end desc,
            case when p_sort = 'new' then 0 else (r.body <> '')::int end desc,
            r.updated_at desc, r.id desc
   limit least(greatest(coalesce(p_limit, 20), 1), 50) offset greatest(coalesce(p_offset, 0), 0);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_app_reviews_list(text, int, int, int) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_app_reviews_list(text, int, int, int) TO anon, authenticated;

-- 4. Написать / изменить свой отзыв
CREATE OR REPLACE FUNCTION public.iva_app_review_save(p_rating int, p_body text, p_version text DEFAULT '')
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me   text := public.iva_app_review_me();
  meta jsonb := coalesce(auth.jwt() -> 'user_metadata', '{}'::jsonb);
  nm   text;
  body text := btrim(regexp_replace(coalesce(p_body, ''), '[\x01-\x09\x0B-\x1F\x7F]', '', 'g'));
  ver  text := left(regexp_replace(coalesce(p_version, ''), '[^0-9.]', '', 'g'), 20);
  lvl  text := 'ok';
  st   text;
  old  public.iva_app_reviews;
  rid  bigint;
begin
  if me = '' then raise exception 'Войдите, чтобы оставить отзыв'; end if;
  if p_rating is null or p_rating < 1 or p_rating > 5 then raise exception 'Поставьте оценку от 1 до 5 звёзд'; end if;
  if char_length(body) > 1000 then raise exception 'Отзыв длиннее 1000 знаков — сократите его'; end if;
  -- имя: только первое слово из профиля входа
  nm := left(split_part(btrim(coalesce(nullif(meta ->> 'full_name', ''), nullif(meta ->> 'name', ''),
                                       nullif(meta ->> 'given_name', ''), '')), ' ', 1), 40);
  -- цензура
  if body <> '' then
    if to_regprocedure('public.iva_censor(text)') is not null then
      execute 'select coalesce(public.iva_censor($1) ->> ''level'', ''ok'')' into lvl using body;
    elsif to_regprocedure('public.iva_has_mat(text)') is not null then
      execute 'select case when public.iva_has_mat($1) then ''mat'' else ''ok'' end' into lvl using body;
    end if;
  end if;
  if lvl = 'mat' then raise exception 'В отзыве есть нецензурные слова — уберите их, и отзыв сохранится'; end if;
  select * into old from public.iva_app_reviews r where r.email = me;
  st := case when lvl = 'suspect' then 'pending'
             when old.id is not null and old.status = 'hidden' then 'pending'   -- скрытый создателем — после правки снова на проверку
             else 'visible' end;
  insert into public.iva_app_reviews as r (email, name, rating, body, app_version, status)
  values (me, nm, p_rating, body, ver, st)
  on conflict (email) do update
    set name = excluded.name, rating = excluded.rating, body = excluded.body,
        app_version = case when excluded.app_version <> '' then excluded.app_version else r.app_version end,
        status = excluded.status, updated_at = now()
  returning r.id into rid;
  return jsonb_build_object('id', rid, 'status', st);
end
$function$;
REVOKE ALL ON FUNCTION public.iva_app_review_save(int, text, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_app_review_save(int, text, text) TO authenticated;

-- 5. Удалить свой отзыв
CREATE OR REPLACE FUNCTION public.iva_app_review_delete()
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare me text := public.iva_app_review_me();
begin
  if me = '' then raise exception 'Войдите, чтобы удалить отзыв'; end if;
  delete from public.iva_app_reviews where email = me;
  return found;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_app_review_delete() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_app_review_delete() TO authenticated;

-- 6. «Отзыв полезен?» — да / нет / снять голос (null). Возвращает новое число «полезно».
CREATE OR REPLACE FUNCTION public.iva_app_review_vote(p_id bigint, p_helpful boolean)
 RETURNS int
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  me text := public.iva_app_review_me();
  r  public.iva_app_reviews;
  n  int;
begin
  if me = '' then raise exception 'Войдите, чтобы отметить отзыв'; end if;
  select * into r from public.iva_app_reviews x where x.id = p_id and x.status = 'visible';
  if r.id is null then raise exception 'Отзыв не найден'; end if;
  if r.email = me then raise exception 'За свой отзыв голосовать нельзя'; end if;
  if p_helpful is null then
    delete from public.iva_app_review_votes where review_id = p_id and email = me;
  else
    insert into public.iva_app_review_votes (review_id, email, helpful) values (p_id, me, p_helpful)
    on conflict (review_id, email) do update set helpful = excluded.helpful, created_at = now();
  end if;
  select count(*)::int into n from public.iva_app_review_votes where review_id = p_id and helpful;
  update public.iva_app_reviews set helpful = n where id = p_id;
  return n;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_app_review_vote(bigint, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_app_review_vote(bigint, boolean) TO authenticated;

-- 7. Создатель: ответ разработчика (пусто — убрать ответ)
CREATE OR REPLACE FUNCTION public.iva_app_review_reply(p_id bigint, p_reply text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare t text := btrim(coalesce(p_reply, ''));
begin
  if not coalesce(public.fn_is_chief(), false) then raise exception 'Отвечать на отзывы может только создатель площадки'; end if;
  if char_length(t) > 1000 then raise exception 'Ответ длиннее 1000 знаков'; end if;
  update public.iva_app_reviews set reply = t, reply_at = case when t = '' then null else now() end where id = p_id;
  return found;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_app_review_reply(bigint, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_app_review_reply(bigint, text) TO authenticated;

-- 8. Создатель: опубликовать / скрыть
CREATE OR REPLACE FUNCTION public.iva_app_review_status(p_id bigint, p_status text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if not coalesce(public.fn_is_chief(), false) then raise exception 'Скрывать отзывы может только создатель площадки'; end if;
  if p_status not in ('visible', 'hidden') then raise exception 'Неизвестный статус'; end if;
  update public.iva_app_reviews set status = p_status where id = p_id;
  return found;
end
$function$;
REVOKE ALL ON FUNCTION public.iva_app_review_status(bigint, text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_app_review_status(bigint, text) TO authenticated;

NOTIFY pgrst, 'reload schema';

-- 9. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('таблицы отзывов закрыты — только через функции',
     (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_app_reviews'::regclass)
     AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_app_review_votes'::regclass)
     AND NOT has_table_privilege('anon', 'public.iva_app_reviews', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_app_reviews', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_app_review_votes', 'SELECT')),
  ('оценки и отзывы видны всем, даже без входа',
     has_function_privilege('anon', 'public.iva_app_reviews_stats()', 'EXECUTE')
     AND has_function_privilege('anon', 'public.iva_app_reviews_list(text, int, int, int)', 'EXECUTE')),
  ('писать, голосовать, удалять — только после входа',
     NOT has_function_privilege('anon', 'public.iva_app_review_save(int, text, text)', 'EXECUTE')
     AND has_function_privilege('authenticated', 'public.iva_app_review_save(int, text, text)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_app_review_vote(bigint, boolean)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_app_review_delete()', 'EXECUTE')),
  ('цензура подключена (iva_censor или iva_has_mat)',
     to_regprocedure('public.iva_censor(text)') IS NOT NULL OR to_regprocedure('public.iva_has_mat(text)') IS NOT NULL)
) AS x(check_name, ok);

-- ============================================================
--  Ива 10.136 — «Рекламный кабинет» (просьба владельца 05.11.2026).
--
--  Что делает:
--   • iva_ads — рекламы создателя (eclips.ru@mail.ru): раздел (Акции / Инфа / Маркет — просто папки), вид показа
--     (баннер на странице, окно по центру, полоска сверху, во весь экран), картинка, заголовок, текст, кнопка
--     (закрыть, ссылка, раздел приложения, позвонить), кому (гости, клиенты, владельцы проката, сотрудники,
--     только недавно установившие), где (экраны приложения), когда (даты, часы, дни недели), как часто,
--     пробный показ «только мне», необязательная маркировка («Реклама», рекламодатель, ИНН, erid).
--     Прямого доступа к таблице нет ни у кого: всё через функции ниже.
--   • iva_ads_live() — что показывать: включённые рекламы (и пробные — только создателю). Без поля «Название (для меня)».
--     Может вызвать любой, даже без входа (экран входа).
--   • iva_ads_list / iva_ad_save / iva_ad_set_active / iva_ad_delete — только создатель (fn_is_chief).
--   • iva_ad_stats + iva_ad_hit(id, вид) — счётчики «показали / нажали / закрыли». Только числа: ни почты, ни устройства.
--   • iva_ads_ping — одна строка «рекламы поменялись»: её трогает триггер на iva_ads, таблица в Supabase Realtime.
--     Приложение 10.136 держит соединение и сразу перечитывает рекламы — включили / выключили / поменяли — видно сразу.
--     Счётчики живут в отдельной таблице и отметку не трогают.
--   • Хранилище картинок iva-ads (публичное чтение — картинку видят все, загружает и удаляет только создатель).
--
--  Без этого SQL приложение работает как раньше: реклама не показывается, «Рекламный кабинет» пишет «нужен SQL».
--
--  КАК ЗАПУСТИТЬ: Supabase → SQL Editor → New query → вставить ВЕСЬ файл → Run. Можно запускать повторно.
--  В конце — проверка: все строки должны быть «✅».
--  ОТКАТ: supabase/migrations/20261124_ads_rollback.sql (удаляет рекламы, счётчики и правила хранилища).
-- ============================================================

DO $do$
BEGIN
  IF to_regclass('public.admins') IS NULL THEN
    RAISE EXCEPTION 'В базе нет admins — это не база «Ивы»';
  END IF;
  IF to_regprocedure('public.fn_is_chief()') IS NULL THEN
    RAISE EXCEPTION 'В базе нет fn_is_chief() — сначала основные SQL «Ивы»';
  END IF;
END $do$;

-- 1. Рекламы
CREATE TABLE IF NOT EXISTS public.iva_ads (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  section     text        NOT NULL DEFAULT 'promo'  CHECK (section IN ('promo', 'info', 'market')),
  name        text        NOT NULL DEFAULT ''       CHECK (char_length(name) <= 120),
  active      boolean     NOT NULL DEFAULT false,
  test_only   boolean     NOT NULL DEFAULT false,
  format      text        NOT NULL DEFAULT 'banner' CHECK (format IN ('banner', 'modal', 'top', 'full')),
  image_url   text        NOT NULL DEFAULT ''       CHECK (char_length(image_url) <= 600),
  img_w       integer     NOT NULL DEFAULT 0        CHECK (img_w BETWEEN 0 AND 10000),
  img_h       integer     NOT NULL DEFAULT 0        CHECK (img_h BETWEEN 0 AND 10000),
  headline    text        NOT NULL DEFAULT ''       CHECK (char_length(headline) <= 120),
  body        text        NOT NULL DEFAULT ''       CHECK (char_length(body) <= 600),
  btn_text    text        NOT NULL DEFAULT ''       CHECK (char_length(btn_text) <= 40),
  btn_action  text        NOT NULL DEFAULT 'close'  CHECK (btn_action IN ('close', 'url', 'tab', 'phone')),
  btn_value   text        NOT NULL DEFAULT ''       CHECK (char_length(btn_value) <= 500),
  btn_place   text        NOT NULL DEFAULT 'below'  CHECK (btn_place IN ('below', 'on')),
  audience    text[]      NOT NULL DEFAULT '{guest,client,admin,staff}'
                          CHECK (audience <@ ARRAY['guest', 'client', 'admin', 'staff']::text[] AND cardinality(audience) > 0),
  new_days    integer     NOT NULL DEFAULT 0        CHECK (new_days BETWEEN 0 AND 365),
  places      text[]      NOT NULL DEFAULT '{home}'
                          CHECK (places <@ ARRAY['login', 'home', 'detail', 'order', 'success', 'chat', 'orders', 'profile', 'seller']::text[]
                                 AND cardinality(places) > 0),
  start_at    timestamptz,
  end_at      timestamptz,
  hour_from   smallint    NOT NULL DEFAULT 0        CHECK (hour_from BETWEEN 0 AND 23),
  hour_to     smallint    NOT NULL DEFAULT 24       CHECK (hour_to BETWEEN 1 AND 24),
  days        smallint    NOT NULL DEFAULT 127      CHECK (days BETWEEN 1 AND 127),
  max_shows   integer     NOT NULL DEFAULT 1        CHECK (max_shows BETWEEN 0 AND 1000),
  gap_hours   integer     NOT NULL DEFAULT 0        CHECK (gap_hours BETWEEN 0 AND 8760),
  hide_on_close boolean   NOT NULL DEFAULT true,
  priority    smallint    NOT NULL DEFAULT 0        CHECK (priority BETWEEN 0 AND 1),
  ad_label    boolean     NOT NULL DEFAULT false,
  advertiser  text        NOT NULL DEFAULT ''       CHECK (char_length(advertiser) <= 160),
  adv_inn     text        NOT NULL DEFAULT ''       CHECK (adv_inn ~ '^([0-9]{10}|[0-9]{12})?$'),
  erid        text        NOT NULL DEFAULT ''       CHECK (char_length(erid) <= 80),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),
  CHECK (image_url <> '' OR headline <> '' OR body <> '')
);
COMMENT ON TABLE public.iva_ads IS 'Ива 10.136: рекламы «Рекламного кабинета» создателя. Доступ — только через функции iva_ads_live / iva_ads_list / iva_ad_save / iva_ad_set_active / iva_ad_delete';
ALTER TABLE public.iva_ads ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_ads FROM public, anon, authenticated;

-- 2. Счётчики (отдельно: показы не трогают отметку «рекламы поменялись»)
CREATE TABLE IF NOT EXISTS public.iva_ad_stats (
  ad_id   uuid   PRIMARY KEY REFERENCES public.iva_ads(id) ON DELETE CASCADE,
  shows   bigint NOT NULL DEFAULT 0,
  clicks  bigint NOT NULL DEFAULT 0,
  closes  bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE public.iva_ad_stats IS 'Ива 10.136: сколько раз реклама показана / нажата / закрыта. Только числа — без почты и устройства';
ALTER TABLE public.iva_ad_stats ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_ad_stats FROM public, anon, authenticated;

-- 3. Отметка «рекламы поменялись» (одна строка) — для Supabase Realtime
CREATE TABLE IF NOT EXISTS public.iva_ads_ping (
  id         smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  changed_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.iva_ads_ping IS 'Ива 10.136: когда последний раз поменялись рекламы. Читают все (без данных — только время), пишет только база';
INSERT INTO public.iva_ads_ping (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
ALTER TABLE public.iva_ads_ping ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.iva_ads_ping FROM public, anon, authenticated;
GRANT SELECT ON public.iva_ads_ping TO anon, authenticated;
DROP POLICY IF EXISTS iva_ads_ping_read ON public.iva_ads_ping;
CREATE POLICY iva_ads_ping_read ON public.iva_ads_ping FOR SELECT TO anon, authenticated USING (true);

CREATE OR REPLACE FUNCTION public.iva_ads_touch()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  begin
    update public.iva_ads_ping set changed_at = clock_timestamp() where id = 1;
  exception when others then
    raise notice 'iva_ads_touch: отметка не поставлена: %', sqlerrm;
  end;
  return null;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_ads_touch() FROM public, anon, authenticated;
DROP TRIGGER IF EXISTS trg_iva_ads_ping ON public.iva_ads;
CREATE TRIGGER trg_iva_ads_ping AFTER INSERT OR UPDATE OR DELETE ON public.iva_ads
  FOR EACH STATEMENT EXECUTE FUNCTION public.iva_ads_touch();

DO $do$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND NOT puballtables)
     AND NOT EXISTS (SELECT 1 FROM pg_publication_tables
                      WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_ads_ping') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.iva_ads_ping;
  END IF;
END $do$;

-- 4. Что показывать (все, даже без входа): включённые и не закончившиеся; пробные — только создателю.
--    Поле «Название (для меня)» не отдаётся.
CREATE OR REPLACE FUNCTION public.iva_ads_live()
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select coalesce(jsonb_agg(to_jsonb(x) order by x.priority desc, x.updated_at desc), '[]'::jsonb)
    from (
      select a.id, a.section, a.test_only, a.format, a.image_url, a.img_w, a.img_h, a.headline, a.body,
             a.btn_text, a.btn_action, a.btn_value, a.btn_place, a.audience, a.new_days, a.places,
             a.start_at, a.end_at, a.hour_from, a.hour_to, a.days, a.max_shows, a.gap_hours, a.hide_on_close,
             a.priority, a.ad_label, a.advertiser, a.adv_inn, a.erid, a.updated_at
        from public.iva_ads a
       where (a.end_at is null or a.end_at > now())
         and a.active and (not a.test_only or public.fn_is_chief())
    ) x;
$function$;
REVOKE ALL ON FUNCTION public.iva_ads_live() FROM public;
GRANT EXECUTE ON FUNCTION public.iva_ads_live() TO anon, authenticated;

-- 5. Счётчик: вид — show / click / close. Реклама не найдена — ничего не делает.
CREATE OR REPLACE FUNCTION public.iva_ad_hit(p_id uuid, p_kind text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if p_id is null or p_kind not in ('show', 'click', 'close') then return; end if;
  if not exists (select 1 from public.iva_ads where id = p_id) then return; end if;
  insert into public.iva_ad_stats (ad_id, shows, clicks, closes)
  values (p_id, (p_kind = 'show')::int, (p_kind = 'click')::int, (p_kind = 'close')::int)
  on conflict (ad_id) do update set
    shows  = iva_ad_stats.shows  + (p_kind = 'show')::int,
    clicks = iva_ad_stats.clicks + (p_kind = 'click')::int,
    closes = iva_ad_stats.closes + (p_kind = 'close')::int;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_ad_hit(uuid, text) FROM public;
GRANT EXECUTE ON FUNCTION public.iva_ad_hit(uuid, text) TO anon, authenticated;

-- 6. Создатель: список со счётчиками
CREATE OR REPLACE FUNCTION public.iva_ads_list()
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if not public.fn_is_chief() then raise exception 'Только создатель' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(to_jsonb(a) || jsonb_build_object('shows', coalesce(s.shows, 0),
            'clicks', coalesce(s.clicks, 0), 'closes', coalesce(s.closes, 0)) order by a.created_at desc), '[]'::jsonb)
            from public.iva_ads a left join public.iva_ad_stats s on s.ad_id = a.id);
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_ads_list() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_ads_list() TO authenticated;

-- 7. Создатель: сохранить (нет id — новая). Возвращает сохранённую строку.
CREATE OR REPLACE FUNCTION public.iva_ad_save(p jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_id  uuid := nullif(p ->> 'id', '')::uuid;
  v_aud text[] := coalesce((select array_agg(x) from jsonb_array_elements_text(coalesce(p -> 'audience', '[]'::jsonb)) x), '{}');
  v_pl  text[] := coalesce((select array_agg(x) from jsonb_array_elements_text(coalesce(p -> 'places', '[]'::jsonb)) x), '{}');
  r     public.iva_ads;
begin
  if not public.fn_is_chief() then raise exception 'Только создатель' using errcode = '42501'; end if;
  if btrim(coalesce(p ->> 'name', '')) = '' then raise exception 'Укажите название рекламы'; end if;
  if v_id is null then
    insert into public.iva_ads (section, name, active, test_only, format, image_url, img_w, img_h, headline, body,
      btn_text, btn_action, btn_value, btn_place, audience, new_days, places, start_at, end_at, hour_from, hour_to,
      days, max_shows, gap_hours, hide_on_close, priority, ad_label, advertiser, adv_inn, erid)
    values (coalesce(p ->> 'section', 'promo'), btrim(p ->> 'name'), coalesce((p ->> 'active')::boolean, false),
      coalesce((p ->> 'test_only')::boolean, false), coalesce(p ->> 'format', 'banner'), coalesce(p ->> 'image_url', ''),
      coalesce((p ->> 'img_w')::int, 0), coalesce((p ->> 'img_h')::int, 0), btrim(coalesce(p ->> 'headline', '')),
      btrim(coalesce(p ->> 'body', '')), btrim(coalesce(p ->> 'btn_text', '')), coalesce(p ->> 'btn_action', 'close'),
      btrim(coalesce(p ->> 'btn_value', '')), coalesce(p ->> 'btn_place', 'below'), v_aud,
      coalesce((p ->> 'new_days')::int, 0), v_pl, nullif(p ->> 'start_at', '')::timestamptz, nullif(p ->> 'end_at', '')::timestamptz,
      coalesce((p ->> 'hour_from')::smallint, 0), coalesce((p ->> 'hour_to')::smallint, 24), coalesce((p ->> 'days')::smallint, 127),
      coalesce((p ->> 'max_shows')::int, 1), coalesce((p ->> 'gap_hours')::int, 0), coalesce((p ->> 'hide_on_close')::boolean, true),
      coalesce((p ->> 'priority')::smallint, 0), coalesce((p ->> 'ad_label')::boolean, false),
      btrim(coalesce(p ->> 'advertiser', '')), btrim(coalesce(p ->> 'adv_inn', '')), btrim(coalesce(p ->> 'erid', '')))
    returning * into r;
  else
    update public.iva_ads set
      section = coalesce(p ->> 'section', section), name = btrim(p ->> 'name'),
      active = coalesce((p ->> 'active')::boolean, active), test_only = coalesce((p ->> 'test_only')::boolean, test_only),
      format = coalesce(p ->> 'format', format), image_url = coalesce(p ->> 'image_url', image_url),
      img_w = coalesce((p ->> 'img_w')::int, img_w), img_h = coalesce((p ->> 'img_h')::int, img_h),
      headline = btrim(coalesce(p ->> 'headline', headline)), body = btrim(coalesce(p ->> 'body', body)),
      btn_text = btrim(coalesce(p ->> 'btn_text', btn_text)), btn_action = coalesce(p ->> 'btn_action', btn_action),
      btn_value = btrim(coalesce(p ->> 'btn_value', btn_value)), btn_place = coalesce(p ->> 'btn_place', btn_place),
      audience = case when p ? 'audience' then v_aud else audience end,
      new_days = coalesce((p ->> 'new_days')::int, new_days),
      places = case when p ? 'places' then v_pl else places end,
      start_at = case when p ? 'start_at' then nullif(p ->> 'start_at', '')::timestamptz else start_at end,
      end_at = case when p ? 'end_at' then nullif(p ->> 'end_at', '')::timestamptz else end_at end,
      hour_from = coalesce((p ->> 'hour_from')::smallint, hour_from), hour_to = coalesce((p ->> 'hour_to')::smallint, hour_to),
      days = coalesce((p ->> 'days')::smallint, days), max_shows = coalesce((p ->> 'max_shows')::int, max_shows),
      gap_hours = coalesce((p ->> 'gap_hours')::int, gap_hours),
      hide_on_close = coalesce((p ->> 'hide_on_close')::boolean, hide_on_close),
      priority = coalesce((p ->> 'priority')::smallint, priority), ad_label = coalesce((p ->> 'ad_label')::boolean, ad_label),
      advertiser = btrim(coalesce(p ->> 'advertiser', advertiser)), adv_inn = btrim(coalesce(p ->> 'adv_inn', adv_inn)),
      erid = btrim(coalesce(p ->> 'erid', erid)), updated_at = now()
     where id = v_id
    returning * into r;
    if r.id is null then raise exception 'Реклама не найдена — возможно, её удалили'; end if;
  end if;
  return to_jsonb(r);
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_ad_save(jsonb) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_ad_save(jsonb) TO authenticated;

-- 8. Создатель: показать / скрыть
CREATE OR REPLACE FUNCTION public.iva_ad_set_active(p_id uuid, p_on boolean)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare r public.iva_ads;
begin
  if not public.fn_is_chief() then raise exception 'Только создатель' using errcode = '42501'; end if;
  -- «Показать» — всем (пробная становится обычной); «Скрыть» — никому, пометка «пробная» остаётся
  update public.iva_ads set active = coalesce(p_on, false),
         test_only = case when coalesce(p_on, false) then false else test_only end,
         updated_at = now()
   where id = p_id returning * into r;
  if r.id is null then raise exception 'Реклама не найдена — возможно, её удалили'; end if;
  return to_jsonb(r);
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_ad_set_active(uuid, boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_ad_set_active(uuid, boolean) TO authenticated;

-- 9. Создатель: удалить (счётчики — вместе с рекламой; картинку удаляет приложение)
CREATE OR REPLACE FUNCTION public.iva_ad_delete(p_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  if not public.fn_is_chief() then raise exception 'Только создатель' using errcode = '42501'; end if;
  delete from public.iva_ads where id = p_id;
  return found;
end;
$function$;
REVOKE ALL ON FUNCTION public.iva_ad_delete(uuid) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.iva_ad_delete(uuid) TO authenticated;

-- 10. Хранилище картинок: читают все (публичное), загружает / меняет / удаляет только создатель
DO $do$
BEGIN
  IF to_regclass('storage.buckets') IS NULL THEN
    RAISE NOTICE 'Хранилища нет — картинки рекламы загрузить не получится';
    RETURN;
  END IF;
  INSERT INTO storage.buckets (id, name, public) VALUES ('iva-ads', 'iva-ads', true)
    ON CONFLICT (id) DO UPDATE SET public = true;
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_ins ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_ads_img_ins ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'iva-ads' AND public.fn_is_chief())$p$;
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_upd ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_ads_img_upd ON storage.objects FOR UPDATE TO authenticated
    USING (bucket_id = 'iva-ads' AND public.fn_is_chief()) WITH CHECK (bucket_id = 'iva-ads' AND public.fn_is_chief())$p$;
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_del ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_ads_img_del ON storage.objects FOR DELETE TO authenticated
    USING (bucket_id = 'iva-ads' AND public.fn_is_chief())$p$;
  EXECUTE 'DROP POLICY IF EXISTS iva_ads_img_sel ON storage.objects';
  EXECUTE $p$CREATE POLICY iva_ads_img_sel ON storage.objects FOR SELECT TO authenticated
    USING (bucket_id = 'iva-ads' AND public.fn_is_chief())$p$;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'Правила хранилища не созданы (%): создайте bucket iva-ads (Public) в Storage вручную', SQLERRM;
END $do$;

NOTIFY pgrst, 'reload schema';

-- 11. Проверка: все строки должны быть «✅»
SELECT x.check_name, CASE WHEN x.ok THEN '✅' ELSE '❌' END AS result FROM (VALUES
  ('рекламы — без прямого доступа (только функции)',
     to_regclass('public.iva_ads') IS NOT NULL
     AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.iva_ads'::regclass)
     AND NOT has_table_privilege('anon', 'public.iva_ads', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_ads', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_ads', 'UPDATE')),
  ('счётчики — без прямого доступа',
     to_regclass('public.iva_ad_stats') IS NOT NULL
     AND NOT has_table_privilege('anon', 'public.iva_ad_stats', 'SELECT')
     AND NOT has_table_privilege('authenticated', 'public.iva_ad_stats', 'UPDATE')),
  ('показ рекламы — всем, даже без входа',
     has_function_privilege('anon', 'public.iva_ads_live()', 'EXECUTE')
     AND has_function_privilege('anon', 'public.iva_ad_hit(uuid, text)', 'EXECUTE')),
  ('управление — только вход (внутри — только создатель)',
     NOT has_function_privilege('anon', 'public.iva_ad_save(jsonb)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_ads_list()', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_ad_delete(uuid)', 'EXECUTE')
     AND NOT has_function_privilege('anon', 'public.iva_ad_set_active(uuid, boolean)', 'EXECUTE')),
  ('отметка «рекламы поменялись» + триггер',
     EXISTS (SELECT 1 FROM public.iva_ads_ping WHERE id = 1)
     AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.iva_ads'::regclass AND tgname = 'trg_iva_ads_ping')
     AND has_table_privilege('anon', 'public.iva_ads_ping', 'SELECT')
     AND NOT has_table_privilege('anon', 'public.iva_ads_ping', 'UPDATE')),
  ('Supabase Realtime включён для отметки реклам',
     EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'iva_ads_ping')
     OR EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime' AND puballtables)),
  ('хранилище картинок iva-ads (публичное)',
     to_regclass('storage.buckets') IS NOT NULL AND EXISTS (SELECT 1 FROM storage.buckets WHERE id = 'iva-ads' AND public)),
  ('служебная функция отметки закрыта для вызова',
     NOT has_function_privilege('anon', 'public.iva_ads_touch()', 'EXECUTE')
     AND NOT has_function_privilege('authenticated', 'public.iva_ads_touch()', 'EXECUTE'))
) AS x(check_name, ok);

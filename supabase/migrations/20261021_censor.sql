-- ============================================================================
-- 10.92: «Цензура» (просьба владельца 15.10.2026)
-- Применяет владелец в Supabase → SQL Editor (решение №15). Порядок: после 20261014_reviews_no_mat.sql.
-- Повторный запуск безопасен: таблицы и слова не удаляются, функции и триггеры пересоздаются.
--
-- Решения владельца (ответы 15.10):
--   * явный мат нельзя написать нигде: и русскими буквами, и латиницей, транслитом, цифрами вместо букв,
--     через пробелы, точки и повторы — сохранение не проходит, человек видит сообщение;
--   * сомнительное (грубые слова, слово, разбитое пробелом, звёздочки вместо букв) НЕ публикуется до проверки:
--     объявление снимается с показа (active = false), отзыв скрывается (status = 'hidden'), запись попадает
--     в очередь «Цензура» у создателя: где, кто, весь текст, какое слово и почему;
--   * чат — только запрет явного мата; сомнительные сообщения проходят, их текст создателю не отправляется;
--   * на странице «Цензура»: «Оставить» / «Скрыть», «Это не мат» (исключения) и «Запрещать всегда» —
--     списки слов в базе, приложение и база учитывают их сразу.
--
-- Что делает файл:
--   1. iva_censor_words — списки слов (allow — «Это не мат», ban — «Запрещать всегда»); читать могут все
--      (нужно приложению для той же проверки), менять — только создатель (iva_censor_word).
--   2. iva_censor_queue — очередь «Цензура»; видит только создатель; пишут только триггеры.
--   3. iva_censor(text) → {"level": "mat" | "suspect" | "ok", "hits": [...]} — одна проверка для всего.
--      Та же — в приложении (IvaCensorPatch.check): выражения и замены одинаковые, сторож сверяет.
--      iva_has_mat(text) (10.71, отзывы) теперь = iva_censor(text) → "mat".
--   4. Триггеры: tools (название, описание, условия), reviews (текст, имя), app_messages (текст — только мат).
--   5. iva_censor_decide(id, оставить) и iva_censor_word(слово, вид) — только создатель (fn_is_chief).
--
-- Откат (объявления и отзывы, скрытые очередью, остаются скрытыми — их можно вернуть до отката кнопкой «Оставить»):
--   drop trigger if exists tr_tools_censor on public.tools;
--   drop trigger if exists tr_reviews_censor on public.reviews;
--   drop trigger if exists tr_app_messages_censor on public.app_messages;
--   drop function if exists public.iva_tools_censor(), public.iva_reviews_censor(), public.iva_app_messages_censor(),
--     public.iva_censor_decide(bigint, boolean), public.iva_censor_word(text, text),
--     public.iva_censor_reason(jsonb, text), public.iva_censor_msg(text, text, jsonb);
--   затем выполнить заново 20261014_reviews_no_mat.sql (вернёт прежнюю iva_has_mat) и
--   drop function if exists public.iva_censor(text), public.iva_censor_tok(text, text[], text[]),
--     public.iva_censor_vars(text);
--   drop table if exists public.iva_censor_queue; drop table if exists public.iva_censor_words;
-- ============================================================================

begin;

-- ─────────────────────────────── 1. Списки слов ───────────────────────────────
create table if not exists public.iva_censor_words (
  word       text primary key check (word ~ '^[а-яa-z0-9@$]{2,40}$'),
  kind       text not null check (kind in ('allow', 'ban')),
  created_at timestamptz not null default now()
);
alter table public.iva_censor_words enable row level security;
revoke all on public.iva_censor_words from anon, authenticated;
grant select on public.iva_censor_words to anon, authenticated;
drop policy if exists iva_censor_words_read on public.iva_censor_words;
create policy iva_censor_words_read on public.iva_censor_words for select to anon, authenticated using (true);

-- ─────────────────────────────── 2. Очередь «Цензура» ───────────────────────────────
create table if not exists public.iva_censor_queue (
  id           bigserial primary key,
  created_at   timestamptz not null default now(),
  source       text not null check (source in ('tool', 'review')),
  ref_id       text not null,                       -- id объявления / отзыва
  field        text not null,                       -- name, descr, terms / text, author_name
  title        text not null default '',            -- название инструмента (для отзыва — инструмента, о котором отзыв)
  author_email text not null default '',
  content      text not null default '',            -- весь текст поля
  hits         jsonb not null default '[]'::jsonb,  -- что сработало: [{level, word, as, rule}]
  reason       text not null default '',            -- подробное описание по-русски
  want_active  boolean not null default true,       -- объявление: включено ли оно у автора (вернуть после «Оставить»)
  status       text not null default 'new' check (status in ('new', 'kept', 'hidden', 'fixed')),
  decided_at   timestamptz,
  decided_by   text
);
create index if not exists iva_censor_queue_ref on public.iva_censor_queue (source, ref_id);
create index if not exists iva_censor_queue_status on public.iva_censor_queue (status, created_at desc);
alter table public.iva_censor_queue enable row level security;
revoke all on public.iva_censor_queue from anon, authenticated;
grant select on public.iva_censor_queue to authenticated;
drop policy if exists iva_censor_queue_chief on public.iva_censor_queue;
create policy iva_censor_queue_chief on public.iva_censor_queue for select to authenticated using (public.fn_is_chief());

-- ─────────────────────────────── 3. Проверка текста ───────────────────────────────
-- Варианты слова: {похожие буквы, транслит}, оба без повторов букв.
--   похожие: латиница и цифры, похожие на русские буквы (x → х, y → у, 3 → з, 0 → о, @ → а, 9 → я …);
--   транслит: сначала сочетания (sh → ш, ch → ч, ya → я …), потом по одной букве (h → х, u → у, y → й …).
create or replace function public.iva_censor_vars(t text)
returns text[]
language plpgsql immutable set search_path = public, pg_temp
as $$
declare
  v text;
  p text;
begin
  v := translate(t, 'abcehkmnoprtuxy3046@$9', 'авсенкмпоргтихузочбася');
  p := t;
  p := replace(p, 'shch', 'щ'); p := replace(p, 'sch', 'щ'); p := replace(p, 'sh', 'ш'); p := replace(p, 'ch', 'ч');
  p := replace(p, 'zh', 'ж'); p := replace(p, 'kh', 'х'); p := replace(p, 'ts', 'ц');
  p := replace(p, 'ya', 'я'); p := replace(p, 'ja', 'я'); p := replace(p, 'yu', 'ю'); p := replace(p, 'ju', 'ю');
  p := replace(p, 'yo', 'е'); p := replace(p, 'jo', 'е'); p := replace(p, 'ye', 'е'); p := replace(p, 'je', 'е');
  p := translate(p, 'abcdefghijklmnopqrstuvwxyz013469@$', 'абсдефгхийклмнопкрстуввхйзоизчбяас');
  return array[regexp_replace(v, '(.)\1+', '\1', 'g'), regexp_replace(p, '(.)\1+', '\1', 'g')];
end;
$$;

-- Одно слово: null — чисто; иначе {level: 2 — мат, 1 — сомнительно; word; as — как прочитали; rule}.
create or replace function public.iva_censor_tok(t text, allow text[], ban text[])
returns jsonb
language plpgsql immutable set search_path = public, pg_temp
as $$
declare
  c text;
  vs text[];
  v text;
  i int;
  w text;
  lat boolean;
begin
  if t is null or t = '' then return null; end if;
  c := regexp_replace(t, '(.)\1+', '\1', 'g');
  if t = any(allow) or c = any(allow) then return null; end if;
  if t ~ '(ebay|ebb|ebm|ebon|ebook|ebola|eber|eben)|^(hues?|her|hers)$' then return null; end if;   -- без ^: «на ebay» склеивается в «наebay»
  lat := t ~ '[a-z0-9@$]';
  if c ~ '^[a-z]+$' and c ~ 'fuck|^fck|^fuk(ing|er|in)?$|^phuck|^motherf|^shit|^bitch|^cunt|^ashole|^dickhead|^bastard' then
    return jsonb_build_object('level', 2, 'word', t, 'as', c, 'rule', 'en');
  end if;
  vs := public.iva_censor_vars(t);
  for i in 1..2 loop
    v := vs[i];
    continue when v ~ '^(за|пере|под|недо|при)?страху|психу';
    if v ~ 'хуй|хуе|хуя|хуи|хую|^хули$|пизд|пздц|^еб|^(за|на|по|вы|от|у|сь|разь|до|подь|при|про|обь|вь|изь|недо|пере|оть|взь)еб[аиулнкчшыо]|^долбоеб|бляд|^(вы|по|за|у)?бля(т|ть|$)|^муд(ак|ил|оз|ач|ень|ло)|^пид[оа]?р|^педик(и|а|ов|у|ам)?$|залуп|^г[ао]ндон|^манд(а|у|ы|ой|е)$|^мандавош|^шл(ю|у)х|^сук(а|и|у|ой|ин|ины|ину|ами)$|^(на|по|ни)?хер($|н|ов)|^(за|по|на|от)?дроч' then
      return jsonb_build_object('level', 2, 'word', t, 'as', v, 'rule', case when not lat then 'word' when i = 1 then 'visual' else 'translit' end);
    end if;
    foreach w in array ban loop
      if position(w in v) > 0 or position(w in c) > 0 then
        return jsonb_build_object('level', 2, 'word', t, 'as', w, 'rule', 'ban');
      end if;
    end loop;
  end loop;
  if c ~ '^(dick|dicks|damn|crap|slut|whore|wtf|stfu|pis)$' then
    return jsonb_build_object('level', 1, 'word', t, 'as', c, 'rule', 'en_rude');
  end if;
  for i in 1..2 loop
    v := vs[i];
    continue when v ~ '^(за|пере|под|недо|при)?страху|психу';
    if v ~ 'жоп|говн|дерьм|^сра(ть|л|ла|ли|ка|ный|ное|ная|ч)$|^(на|по|ни)?хрен|^гнид|^ублюд|^мраз(ь|и|ота)$|^твар(ь|и)$|^урод(ы|а|у|ов|ина|ский|ская|ское|ские)?$|^дебил|^чмо(шник|шница)?$|^лох(и|а|у|ов|ам|ушка|ушки)?$|^задрот|^шалав|^педрил|^сучар|^курв(а|ы|е|у|ой)$|^перд(еж|ет|ит|еть|ун|ак|уха)|^(за|от|вы)?трах(ать|ает|аю|ал|нуть|нул|ну|ни)' then
      return jsonb_build_object('level', 1, 'word', t, 'as', v, 'rule', 'rude');
    end if;
  end loop;
  return null;
end;
$$;

-- Весь текст: два прохода (знаки внутри слова убраны / знаки = пробел), буквы через пробел склеиваются
-- («х у й»), соседние слова проверяются вместе («пи зда» — сомнительно), звёздочки вместо букв — сомнительно.
create or replace function public.iva_censor(p text)
returns jsonb
language plpgsql stable set search_path = public, pg_temp
as $$
declare
  allow text[];
  ban text[];
  low text;
  s text;
  pass int;
  raw text[];
  toks text[];
  shows text[];
  run text;
  runshow text;
  x text;
  i int;
  h jsonb;
  j jsonb;
  prevh jsonb;
  hits jsonb := '[]'::jsonb;
  words text[] := '{}';
  lvl int := 0;
  m text;
begin
  if p is null or btrim(p) = '' then
    return jsonb_build_object('level', 'ok', 'hits', '[]'::jsonb);
  end if;
  select coalesce(array_agg(word) filter (where kind = 'allow'), '{}'), coalesce(array_agg(word) filter (where kind = 'ban'), '{}')
    into allow, ban from public.iva_censor_words;
  low := lower(translate(left(p, 20000), 'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'абвгдеёжзийклмнопрстуфхцчшщъыьэюя'));
  low := regexp_replace(low, '[\u0300-\u036f\u00ad\u200b-\u200f\u2060\ufeff]', '', 'g');
  low := translate(low, 'ёъ', 'еь');
  for pass in 1..2 loop
    s := regexp_replace(low, '[._*''`~"-]+', case when pass = 1 then '' else ' ' end, 'g');
    raw := regexp_split_to_array(s, '[^а-яa-z0-9@$]+');
    toks := '{}'; shows := '{}'; run := ''; runshow := '';
    foreach x in array raw loop
      continue when x = '';
      if char_length(x) = 1 then
        run := run || x;
        runshow := case when runshow = '' then x else runshow || ' ' || x end;
      else
        if run <> '' then toks := toks || run; shows := shows || runshow; run := ''; runshow := ''; end if;
        toks := toks || x; shows := shows || x;
      end if;
    end loop;
    if run <> '' then toks := toks || run; shows := shows || runshow; end if;
    prevh := null;
    for i in 1..coalesce(array_length(toks, 1), 0) loop
      h := public.iva_censor_tok(toks[i], allow, ban);
      if h is not null then
        if shows[i] <> toks[i] and (h->>'level')::int = 2 then h := h || jsonb_build_object('rule', 'letters'); end if;
        h := h || jsonb_build_object('word', shows[i]);
        if not (shows[i] = any(words)) then hits := hits || h; words := words || shows[i]; end if;
        lvl := greatest(lvl, (h->>'level')::int);
      elsif i > 1 and prevh is null then
        j := public.iva_censor_tok(toks[i - 1] || toks[i], allow, ban);
        if j is not null and (j->>'level')::int = 2 and not ((shows[i - 1] || ' ' || shows[i]) = any(words)) then
          hits := hits || jsonb_build_object('level', 1, 'word', shows[i - 1] || ' ' || shows[i], 'as', j->>'as', 'rule', 'split');
          words := words || (shows[i - 1] || ' ' || shows[i]);
          lvl := greatest(lvl, 1);
        end if;
      end if;
      prevh := h;
    end loop;
  end loop;
  for m in select (regexp_matches(low, '[а-яa-z]+\*+[а-яa-z]+', 'g'))[1] loop
    if m ~ '^[хпбесмзxhpbes]' and char_length(m) >= 3 and not (m = any(words)) then
      hits := hits || jsonb_build_object('level', 1, 'word', m, 'as', m, 'rule', 'masked');
      words := words || m;
      lvl := greatest(lvl, 1);
    end if;
  end loop;
  return jsonb_build_object('level', case lvl when 2 then 'mat' when 1 then 'suspect' else 'ok' end, 'hits', hits);
end;
$$;

-- 10.71 (отзывы): та же проверка.
create or replace function public.iva_has_mat(p text)
returns boolean
language sql stable set search_path = public, pg_temp
as $$ select public.iva_censor(p)->>'level' = 'mat' $$;

-- Подробное описание для очереди: каждое слово — как прочитали и почему.
create or replace function public.iva_censor_reason(hits jsonb, src text)
returns text
language plpgsql immutable set search_path = public, pg_temp
as $$
declare
  h jsonb;
  out text := '';
  why text;
begin
  for h in select * from jsonb_array_elements(coalesce(hits, '[]'::jsonb)) loop
    why := case h->>'rule'
      when 'word' then 'нецензурное слово'
      when 'visual' then 'нецензурное слово, написанное похожими латинскими буквами или цифрами'
      when 'translit' then 'нецензурное слово, написанное латиницей (транслитом)'
      when 'letters' then 'нецензурное слово, написанное по буквам через пробел'
      when 'ban' then 'слово из списка «Запрещать всегда»'
      when 'en' then 'английский мат'
      when 'rude' then 'грубое слово'
      when 'en_rude' then 'грубое английское слово'
      when 'split' then 'слово разбито пробелом — вместе похоже на мат'
      when 'masked' then 'звёздочки вместо букв — похоже на замаскированный мат'
      else 'похоже на мат' end;
    out := out || case when out = '' then '' else E'\n' end || '«' || (h->>'word') || '»'
      || case when (h->>'as') is distinct from (h->>'word') then ' (читается как «' || (h->>'as') || '»)' else '' end
      || ' — ' || why || '.';
  end loop;
  return out || E'\n' || case src when 'tool' then 'Объявление снято с показа до вашего решения.'
    else 'Отзыв скрыт до вашего решения.' end;
end;
$$;

-- Сообщение автору, если в тексте мат (показывают и приложение, и сайт).
create or replace function public.iva_censor_msg(src text, fld text, r jsonb)
returns text
language plpgsql immutable set search_path = public, pg_temp
as $$
declare
  w text;
begin
  select h->>'word' into w from jsonb_array_elements(r->'hits') h where (h->>'level')::int = 2 limit 1;
  return case src
    when 'tool' then 'В объявлении есть нецензурные слова (' || case fld when 'name' then 'Название' when 'descr' then 'Описание'
      else 'Условия аренды' end || ': «' || coalesce(w, '') || '»). Уберите их, пожалуйста.'
    when 'msg' then 'В сообщении есть нецензурные слова. Уберите их, пожалуйста.'
    when 'name' then 'В имени есть нецензурные слова. Измените имя в профиле.'
    else 'В отзыве есть нецензурные слова. Уберите их, пожалуйста.' end;
end;
$$;

-- ─────────────────────────────── 4. Триггеры ───────────────────────────────
-- Объявления: мат — не сохраняется; сомнительное — объявление снято с показа, запись в очереди.
-- Текст не менялся, но в очереди есть «новая» или «скрытая» запись с этим текстом — включить нельзя (кроме создателя).
create or replace function public.iva_tools_censor()
returns trigger
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  f text;
  v text;
  o text;
  r jsonb;
  chief boolean := public.fn_is_chief();
  hold boolean := false;
  want boolean := coalesce(new.active, true);
  back boolean;                -- автор исправил текст, снятый очередью: как было у автора
  b boolean;
begin
  if coalesce(current_setting('iva.censor_skip', true), '') = '1' then return new; end if;
  if tg_op = 'UPDATE' and not chief and new.active is distinct from old.active then
    update public.iva_censor_queue set want_active = coalesce(new.active, false)
      where source = 'tool' and ref_id = new.id::text and status = 'new';
  end if;
  foreach f in array array['name', 'descr', 'terms'] loop
    v := case f when 'name' then new.name when 'descr' then new.descr else new.terms end;
    if tg_op = 'UPDATE' then
      o := case f when 'name' then old.name when 'descr' then old.descr else old.terms end;
      continue when v is not distinct from o;
      with u as (update public.iva_censor_queue set status = 'fixed', decided_at = now()
        where source = 'tool' and ref_id = new.id::text and field = f and status = 'new' returning want_active)
      select bool_or(want_active) into b from u;
      if b is not null then back := coalesce(back, false) or b; end if;
    end if;
    r := public.iva_censor(v);
    if r->>'level' = 'mat' then
      raise exception '%', public.iva_censor_msg('tool', f, r);
    end if;
    if r->>'level' = 'suspect' and not chief and not exists (
        select 1 from public.iva_censor_queue q
        where q.source = 'tool' and q.ref_id = new.id::text and q.field = f and q.status = 'kept' and q.content = v) then
      insert into public.iva_censor_queue (source, ref_id, field, title, author_email, content, hits, reason, want_active)
      values ('tool', new.id::text, f, coalesce(new.name, ''), coalesce(new.owner_email, ''), v, r->'hits',
              public.iva_censor_reason(r->'hits', 'tool'), want);
      hold := true;
    end if;
  end loop;
  if not chief and coalesce(new.active, false) and exists (
      select 1 from public.iva_censor_queue q
      where q.source = 'tool' and q.ref_id = new.id::text and q.status in ('new', 'hidden')
        and q.content = case q.field when 'name' then new.name when 'descr' then new.descr else new.terms end) then
    hold := true;
  end if;
  if hold then new.active := false;
  elsif back is not null and not chief then new.active := back;
  end if;
  return new;
end;
$$;

drop trigger if exists tr_tools_censor on public.tools;
create trigger tr_tools_censor before insert or update on public.tools
for each row execute function public.iva_tools_censor();

-- Отзывы: мат — не сохраняется (как 10.71); сомнительное — отзыв скрыт, запись в очереди.
create or replace function public.iva_reviews_censor()
returns trigger
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  f text;
  v text;
  o text;
  r jsonb;
  chief boolean := public.fn_is_chief();
  hold boolean := false;
  back boolean := false;       -- автор исправил текст, скрытый очередью: отзыв снова виден
  n int;
  tname text;
begin
  if coalesce(current_setting('iva.censor_skip', true), '') = '1' then return new; end if;
  select t.name into tname from public.tools t where t.id = new.tool_id;
  foreach f in array array['text', 'author_name'] loop
    v := case f when 'text' then new.text else new.author_name end;
    if tg_op = 'UPDATE' then
      o := case f when 'text' then old.text else old.author_name end;
      continue when v is not distinct from o;
      update public.iva_censor_queue set status = 'fixed', decided_at = now()
        where source = 'review' and ref_id = new.id::text and field = f and status = 'new';
      get diagnostics n = row_count;
      if n > 0 then back := true; end if;
    end if;
    r := public.iva_censor(v);
    if r->>'level' = 'mat' then
      raise exception '%', public.iva_censor_msg(case f when 'text' then 'review' else 'name' end, f, r);
    end if;
    if r->>'level' = 'suspect' and not chief and not exists (
        select 1 from public.iva_censor_queue q
        where q.source = 'review' and q.ref_id = new.id::text and q.field = f and q.status = 'kept' and q.content = v) then
      insert into public.iva_censor_queue (source, ref_id, field, title, author_email, content, hits, reason)
      values ('review', new.id::text, f, coalesce(tname, ''), coalesce(new.author_email, ''), v, r->'hits',
              public.iva_censor_reason(r->'hits', 'review'));
      hold := true;
    end if;
  end loop;
  if not chief and new.status = 'published' and exists (
      select 1 from public.iva_censor_queue q
      where q.source = 'review' and q.ref_id = new.id::text and q.status in ('new', 'hidden')
        and q.content = case q.field when 'text' then new.text else new.author_name end) then
    hold := true;
  end if;
  if hold then new.status := 'hidden';
  elsif back and not chief then new.status := 'published';
  end if;
  return new;
end;
$$;

drop trigger if exists tr_reviews_censor on public.reviews;
create trigger tr_reviews_censor before insert or update on public.reviews
for each row execute function public.iva_reviews_censor();

-- Чат: только явный мат (решение владельца — сомнительные сообщения проходят, создателю не отправляются).
create or replace function public.iva_app_messages_censor()
returns trigger
language plpgsql security definer set search_path = public, pg_temp
as $$
begin
  if public.iva_censor(new.text)->>'level' = 'mat' then
    raise exception '%', public.iva_censor_msg('msg', 'text', null);
  end if;
  return new;
end;
$$;

drop trigger if exists tr_app_messages_censor on public.app_messages;
create trigger tr_app_messages_censor before insert or update of text on public.app_messages
for each row execute function public.iva_app_messages_censor();

-- ─────────────────────────────── 5. Решения создателя ───────────────────────────────
-- «Оставить» (p_keep = true): запись — «оставлено»; объявление включается, как было у автора (если других
-- нерешённых записей по нему нет); отзыв публикуется. «Скрыть»: объявление выключено, отзыв скрыт.
create or replace function public.iva_censor_decide(p_id bigint, p_keep boolean)
returns text
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  q public.iva_censor_queue%rowtype;
  st text := case when p_keep then 'kept' else 'hidden' end;
begin
  if not public.fn_is_chief() then
    raise exception 'only chief' using errcode = '42501';
  end if;
  select * into q from public.iva_censor_queue where id = p_id for update;
  if not found then
    raise exception 'not found' using errcode = 'P0002';
  end if;
  if q.status <> 'new' then
    raise exception 'already decided' using errcode = '22023';
  end if;
  update public.iva_censor_queue set status = st, decided_at = now(), decided_by = public.jwt_email() where id = p_id;
  perform set_config('iva.censor_skip', '1', true);
  if q.source = 'tool' then
    if not p_keep then
      update public.tools set active = false where id::text = q.ref_id;
    elsif not exists (select 1 from public.iva_censor_queue x where x.source = 'tool' and x.ref_id = q.ref_id and x.status in ('new', 'hidden')) then
      update public.tools set active = q.want_active where id::text = q.ref_id;
    end if;
  else
    if not p_keep then
      update public.reviews set status = 'hidden', updated_at = now() where id::text = q.ref_id;
    elsif not exists (select 1 from public.iva_censor_queue x where x.source = 'review' and x.ref_id = q.ref_id and x.status in ('new', 'hidden')) then
      update public.reviews set status = 'published', updated_at = now() where id::text = q.ref_id;
    end if;
  end if;
  perform set_config('iva.censor_skip', '', true);
  return st;
end;
$$;

-- Списки слов: p_kind = 'allow' («Это не мат»), 'ban' («Запрещать всегда»), 'none' — убрать из списков.
create or replace function public.iva_censor_word(p_word text, p_kind text)
returns text
language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  w text;
begin
  if not public.fn_is_chief() then
    raise exception 'only chief' using errcode = '42501';
  end if;
  w := regexp_replace(translate(lower(translate(coalesce(p_word, ''), 'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'абвгдеёжзийклмнопрстуфхцчшщъыьэюя')), 'ёъ', 'еь'),
                      '[^а-яa-z0-9@$]+', '', 'g');
  if char_length(w) < 2 or char_length(w) > 40 then
    raise exception 'Слово — от 2 до 40 букв' using errcode = '22023';
  end if;
  if p_kind = 'none' then
    delete from public.iva_censor_words where word = w;
  elsif p_kind in ('allow', 'ban') then
    insert into public.iva_censor_words (word, kind) values (w, p_kind)
      on conflict (word) do update set kind = excluded.kind, created_at = now();
  else
    raise exception 'kind: allow, ban, none' using errcode = '22023';
  end if;
  return w;
end;
$$;

revoke all on function public.iva_censor_decide(bigint, boolean) from public, anon;
revoke all on function public.iva_censor_word(text, text) from public, anon;
grant execute on function public.iva_censor_decide(bigint, boolean) to authenticated;
grant execute on function public.iva_censor_word(text, text) to authenticated;
grant execute on function public.iva_censor(text) to anon, authenticated;

commit;

-- Проверка после выполнения (должно быть: mat, mat, mat, suspect, ok):
--   select public.iva_censor('xyй')->>'level', public.iva_censor('pizdec')->>'level', public.iva_censor('х у й')->>'level',
--          public.iva_censor('жопа')->>'level', public.iva_censor('Перфоратор Makita HR2470, кейс, бур SDS-plus')->>'level';
-- По желанию: что из уже опубликованного сейчас попало бы в очередь (ничего не меняет):
--   select id, name, public.iva_censor(name || ' ' || coalesce(descr, '') || ' ' || coalesce(terms, ''))->>'level'
--   from public.tools where public.iva_censor(name || ' ' || coalesce(descr, '') || ' ' || coalesce(terms, ''))->>'level' <> 'ok';

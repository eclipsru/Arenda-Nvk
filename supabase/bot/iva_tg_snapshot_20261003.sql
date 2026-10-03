-- ============================================================================
--  Ива — Telegram-бот в базе: СНИМОК как было в боевой базе на 03.10.2026 13:31 UTC.
--
--  Откуда: результат tools/diag-bot.sql, присланный владельцем 03.10.2026
--  (pg_get_functiondef, токен и ключи скрыты). Раньше этих функций в репозитории
--  не было ни в одном коммите — их создали SQL-запросом из пакета документов
--  второго чата («файл 28»). Этот файл — память проекта (правило №1) и ТОЧКА ОТКАТА
--  для supabase/migrations/20261003_bot_no_contacts.sql.
--
--  ВАЖНО:
--   • Токен бота здесь НЕ хранится: он лежит в таблице public.iva_secrets
--     (строка name = 'BOT_TOKEN'); посетителю сайта таблица недоступна
--     (проверено 03.10.2026: REST с публичным ключом → «permission denied»).
--   • Ключ в адресе функции vk_wall_post заменён на <СКРЫТО> — в репозиторий
--     он не попадает. Поэтому fn_vk_wall_post отсюда НЕ восстанавливать
--     (она и не менялась), её текст — только для справки, закомментирован.
--   • Таблицы бота (iva_secrets, iva_tg_targets, iva_tg_chats) здесь не создаются:
--     точные типы колонок диагностика не снимала. Известные колонки:
--       iva_secrets(name, value, note, updated_at) — name уникален;
--       iva_tg_targets(key, chat_id, title, active, updated_at) — key уникален;
--       iva_tg_chats(chat_id, first_name, username, seen_at).
--
--  КАК ОТКАТИТЬ миграцию 20261003_bot_no_contacts.sql: SQL Editor → вставить
--  ДВА блока ниже, отмеченные «ОТКАТ», → Run. (Остальное можно не трогать.)
--
--  Триггеры (как в базе):
--    landlord_applications  AFTER INSERT → iva_tg_on_landlord   (trg_iva_tg_landlord)
--    order_parts            AFTER INSERT → iva_tg_on_part       (trg_iva_tg_part)
--    order_parts            AFTER UPDATE → iva_tg_on_part_status (trg_iva_tg_part_st)
--    orders                 AFTER INSERT → fn_order_to_max_hook (trg_order_to_max) — в MAX, не Telegram
--    tools, services        AFTER INSERT → fn_vk_wall_post      (trg_tool_to_vk, trg_service_to_vk) — во ВКонтакте
-- ============================================================================


-- ---------------------------------------------------------------------------
-- ОТКАТ (1 из 2): уведомление о новой анкете арендодателя — исходная версия
-- (с ФИО, телефоном, email и компанией заявителя)
-- ---------------------------------------------------------------------------
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
          v_msg := format(
            E'🧑‍💼 <b>Новая анкета арендодателя</b>\n\nГород: %s\nРегион: %s\nКатегории: %s\nИнструментов: %s\n\n👤 %s\n📞 %s\n📧 %s\n🏢 %s',
            coalesce(nullif(v->>'city', ''), '—'),
            coalesce(nullif(v->>'region', ''), '—'),
            coalesce(nullif(array_to_string(ARRAY(SELECT jsonb_array_elements_text(v->'categories')), ', '), ''), '—'),
            coalesce(v->>'inventory_count', '—'),
            coalesce(nullif(v->>'full_name', ''), '—'),
            coalesce(nullif(v->>'phone', ''), '—'),
            coalesce(nullif(v->>'email', ''), '—'),
            coalesce(nullif(v->>'company', ''), '—')
          );
          PERFORM public.iva_tg_send('owner', v_msg);
        EXCEPTION WHEN OTHERS THEN
          RAISE NOTICE 'iva_tg_on_landlord: уведомление не ушло: %', sqlerrm;
        END;
        RETURN new;
      END $function$;


-- ---------------------------------------------------------------------------
-- ОТКАТ (2 из 2): уведомление «пункт взял заявку» — исходная версия
-- (с именем и телефоном клиента владельцу и пункту, способом получения пункту)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.iva_tg_on_part()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
      DECLARE
        v      jsonb;
        v_o    jsonb;
        v_who  text;
        v_msg  text;
      BEGIN
        BEGIN
          v := to_jsonb(new);
          SELECT to_jsonb(o) INTO v_o FROM public.orders o WHERE o.id = new.order_id;

          -- Кто взял: имя пункта из admins, иначе почта.
          SELECT coalesce(nullif(a.full_name, ''), nullif(a.company, ''), a.email)
            INTO v_who
            FROM public.admins a
           WHERE lower(a.email) = lower(coalesce(new.owner_email, ''))
           LIMIT 1;

          v_msg := format(
            E'🤝 <b>Пункт проката взял заявку</b> #%s\n\n%s\n\n🏷 %s\n👤 Клиент: %s\n📞 %s\n\nКомиссия: %s',
            coalesce(v->>'order_id', '?'),
            coalesce(nullif(v->>'tools_text', ''), '—'),
            coalesce(v_who, new.owner_email, '—'),
            coalesce(nullif(v_o->>'name', ''), '—'),
            coalesce(nullif(v_o->>'phone', ''), '—'),
            coalesce((v->>'fee_pct')::text, '—') || '%'
          );
          PERFORM public.iva_tg_send('owner', v_msg);

          -- Самому пункту, если его чат привязан (key = 'admin:<почта>').
          IF coalesce(new.owner_email, '') <> '' THEN
            PERFORM public.iva_tg_send(
              'admin:' || lower(new.owner_email),
              format(
                E'🆕 <b>На ваш инструмент пришла заявка</b> #%s\n\n%s\n\n👤 Клиент: %s\n📞 %s\n📍 %s\n\nОткрыть: https://eclipsru.github.io/Arenda-Nvk/cabinet.html',
                coalesce(v->>'order_id', '?'),
                coalesce(nullif(v->>'tools_text', ''), '—'),
                coalesce(nullif(v_o->>'name', ''), '—'),
                coalesce(nullif(v_o->>'phone', ''), '—'),
                coalesce(nullif(v_o->>'get_method', ''), '—')
              )
            );
          END IF;
        EXCEPTION WHEN OTHERS THEN
          RAISE NOTICE 'iva_tg_on_part: уведомление не ушло: %', sqlerrm;
        END;
        RETURN new;
      END $function$;


-- ===========================================================================
-- Остальные функции бота — без изменений, для памяти проекта.
-- ===========================================================================

-- Смена статуса заявки → владельцу (персональных данных нет)
CREATE OR REPLACE FUNCTION public.iva_tg_on_part_status()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
      DECLARE v jsonb; v_old jsonb; v_msg text;
      BEGIN
        BEGIN
          v := to_jsonb(new); v_old := to_jsonb(old);
          v_msg := format(
            E'🔄 <b>Статус заявки изменился</b> #%s\n\n%s → %s\n\n%s',
            coalesce(v->>'order_id', '?'),
            coalesce(v_old->>'status', '—'),
            coalesce(v->>'status', '—'),
            coalesce(nullif(v->>'tools_text', ''), '—')
          );
          PERFORM public.iva_tg_send('owner', v_msg);
        EXCEPTION WHEN OTHERS THEN
          RAISE NOTICE 'iva_tg_on_part_status: уведомление не ушло: %', sqlerrm;
        END;
        RETURN new;
      END $function$;

-- Отправка сообщения получателю по ключу ('owner' или 'admin:<почта>')
CREATE OR REPLACE FUNCTION public.iva_tg_send(p_key text, p_text text)
 RETURNS bigint
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  DECLARE
    v_token text;
    v_chat  bigint;
    v_req   bigint;
  BEGIN
    IF coalesce(p_text, '') = '' THEN
      RETURN NULL;
    END IF;

    SELECT value INTO v_token FROM public.iva_secrets WHERE name = 'BOT_TOKEN';
    IF coalesce(v_token, '') = '' THEN
      RETURN NULL; -- токен не задан — уведомления выключены
    END IF;

    SELECT chat_id INTO v_chat
      FROM public.iva_tg_targets
     WHERE key = p_key AND active AND chat_id IS NOT NULL;
    IF v_chat IS NULL THEN
      RETURN NULL; -- получатель не настроен — молча
    END IF;

    v_req := public.iva_tg_post(
      'https://api.telegram.org/bot' || v_token || '/sendMessage',
      jsonb_build_object(
        'chat_id', v_chat,
        'text', p_text,
        'parse_mode', 'HTML',
        'disable_web_page_preview', true
      )
    );
    RETURN v_req;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'iva_tg_send: уведомление не ушло';
    RETURN NULL;
  END $function$;

-- Низкоуровневая отправка через pg_net
CREATE OR REPLACE FUNCTION public.iva_tg_post(p_url text, p_body jsonb)
 RETURNS bigint
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  DECLARE
    v_ns   text;
    v_call text;
    v_id   bigint;
  BEGIN
    v_ns := public.iva_tg_net_schema();
    IF v_ns IS NULL THEN
      RETURN NULL; -- pg_net не включён: молча ничего не делаем
    END IF;
    v_call := CASE WHEN v_ns = 'net' THEN 'net.http_post' ELSE 'extensions.net.http_post' END;
    EXECUTE format('SELECT %s(url := $1, body := $2, headers := $3)', v_call)
      INTO v_id
      USING p_url,
            p_body,
            jsonb_build_object('Content-Type', 'application/json');
    RETURN v_id;
  EXCEPTION WHEN OTHERS THEN
    -- Ошибку отправки не показываем в деталях: в URL-е есть токен бота.
    RAISE NOTICE 'iva_tg_post: запрос в Telegram не ушёл';
    RETURN NULL;
  END $function$;

-- В какой схеме живёт pg_net
CREATE OR REPLACE FUNCTION public.iva_tg_net_schema()
 RETURNS text
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  BEGIN
    IF EXISTS (
      SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'net' AND p.proname = 'http_post'
    ) THEN
      RETURN 'net';
    ELSIF EXISTS (
      SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'extensions' AND p.proname = 'http_post'
    ) THEN
      RETURN 'extensions.net';
    END IF;
    RETURN NULL; -- pg_net не включён
  END $function$;

-- Ответ pg_net по номеру запроса
CREATE OR REPLACE FUNCTION public.iva_tg_resp(p_req bigint)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_ns  text;
  v_row jsonb;
BEGIN
  IF p_req IS NULL OR p_req = 0 THEN
    RETURN NULL;
  END IF;
  v_ns := public.iva_tg_net_schema();
  IF v_ns IS NULL THEN
    RETURN NULL;
  END IF;
  EXECUTE format(
    'SELECT jsonb_build_object(''status'', status_code, ''content'', content, ''timed_out'', timed_out, ''error'', error_msg)
       FROM %s._http_response WHERE id = $1', v_ns)
    INTO v_row USING p_req;
  RETURN v_row;
EXCEPTION WHEN OTHERS THEN
  RETURN NULL;
END $function$;

-- Служебное: запомнить номер запроса
CREATE OR REPLACE FUNCTION public.iva_tg_remember(p_name text, p_req bigint)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
BEGIN
  INSERT INTO public.iva_secrets (name, value, note)
  VALUES (p_name, coalesce(p_req::text, '0'), 'служебное: id запроса в pg_net')
  ON CONFLICT (name) DO UPDATE SET value = excluded.value, updated_at = now();
END $function$;

-- Привязать получателя (owner / admin:<почта>) к чату
CREATE OR REPLACE FUNCTION public.iva_tg_link(p_key text, p_chat bigint, p_title text DEFAULT ''::text)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  BEGIN
    INSERT INTO public.iva_tg_targets (key, chat_id, title, updated_at)
    VALUES (p_key, p_chat, coalesce(p_title, ''), now())
    ON CONFLICT (key) DO UPDATE
      SET chat_id = excluded.chat_id,
          title = coalesce(nullif(excluded.title, ''), iva_tg_targets.title),
          active = true,
          updated_at = now();
  END $function$;

-- Попросить у Telegram список написавших боту (getUpdates)
CREATE OR REPLACE FUNCTION public.iva_tg_ask_updates()
 RETURNS bigint
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_token text;
  v_req   bigint;
BEGIN
  SELECT value INTO v_token FROM public.iva_secrets WHERE name = 'BOT_TOKEN';
  IF coalesce(v_token, '') = '' THEN
    RETURN NULL;
  END IF;
  v_req := public.iva_tg_post(
    'https://api.telegram.org/bot' || v_token || '/getUpdates',
    jsonb_build_object('offset', 0, 'limit', 100, 'timeout', 0)
  );
  PERFORM public.iva_tg_remember('TG_REQ_GETUPDATES', v_req);
  RETURN v_req;
END $function$;

-- Шаг настройки 1: сохранить токен (select public.iva_setup_bot('токен');)
CREATE OR REPLACE FUNCTION public.iva_setup_bot(p_token text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_req  bigint;
  v_chat bigint;
  v_name text;
BEGIN
  IF coalesce(p_token, '') !~ '^[0-9]{6,12}:[A-Za-z0-9_-]{20,}$' THEN
    RETURN '❌ Токен не похож на токен бота (ожидается вид 123456789:AA...). Проверь, что скопировал целиком.';
  END IF;

  INSERT INTO public.iva_secrets (name, value, note)
  VALUES ('BOT_TOKEN', p_token, 'токен Telegram-бота')
  ON CONFLICT (name) DO UPDATE SET value = excluded.value, updated_at = now();

  SELECT chat_id, title INTO v_chat, v_name FROM public.iva_tg_targets WHERE key = 'owner';
  IF v_chat IS NOT NULL THEN
    RETURN '✅ Токен сохранён. Получатель owner уже привязан (chat ' || v_chat ||
           coalesce(' — ' || v_name, '') || '). Дальше: select public.iva_tg_test();';
  END IF;

  v_req := public.iva_tg_ask_updates();
  IF v_req IS NULL THEN
    RETURN '⚠️ Токен сохранён, но pg_net не включён: Supabase → Database → Extensions → pg_net. После включения повтори эту строку.';
  END IF;
  RETURN '✅ Токен сохранён, запрос на поиск чата поставлен (запрос ' || v_req ||
         '). Не закрывая редактор: подожди ~10 секунд и выполни ОТДЕЛЬНЫМ Run  select public.iva_tg_confirm();';
END $function$;

-- Шаг настройки 2: найти чат владельца (select public.iva_tg_confirm();)
CREATE OR REPLACE FUNCTION public.iva_tg_confirm()
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_req   bigint;
  v_resp  jsonb;
  v_res   jsonb;
  v_item  jsonb;
  v_chat  jsonb;
  v_ids   bigint[] := ARRAY[]::bigint[];
  v_names text[]   := ARRAY[]::text[];
  v_id    bigint;
  v_name  text;
  v_list  text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.iva_secrets WHERE name = 'BOT_TOKEN' AND coalesce(value, '') <> '') THEN
    RETURN '❌ Токен не сохранён. Сначала выполни: select public.iva_setup_bot(''токен-от-BotFather''); — а эту строку запусти следующим Run.';
  END IF;

  SELECT coalesce(max(nullif(value, '')::bigint), 0) INTO v_req
    FROM public.iva_secrets WHERE name = 'TG_REQ_GETUPDATES';
  v_resp := public.iva_tg_resp(v_req);

  IF v_resp IS NULL THEN
    v_req := public.iva_tg_ask_updates();
    IF v_req IS NULL THEN
      RETURN '❌ pg_net не включён: Supabase → Database → Extensions → pg_net (включи и повтори).';
    END IF;
    RETURN '⏳ Ответа Telegram ещё нет — запрос ' || v_req || ' стоит в очереди (он уходит сразу после Run). Подожди ~10 секунд и запусти ЭТУ ЖЕ строку ещё раз.';
  END IF;
  IF coalesce((v_resp ->> 'timed_out')::boolean, false) THEN
    PERFORM public.iva_tg_ask_updates();
    RETURN '⏳ pg_net не дождался ответа от Telegram (таймаут). Запрос обновлён — запусти эту строку ещё раз через 10 секунд.';
  END IF;
  IF coalesce(v_resp ->> 'error', '') <> '' THEN
    PERFORM public.iva_tg_ask_updates();
    RETURN '⚠️ Канал pg_net вернул ошибку: ' || left(v_resp ->> 'error', 200) || '. Повтори эту строку через минуту.';
  END IF;
  IF coalesce(v_resp ->> 'status', '') <> '200' THEN
    RETURN '⚠️ Telegram ответил ' || coalesce(v_resp ->> 'status', '?') || ': ' || coalesce(left(v_resp ->> 'content', 300), '—');
  END IF;

  IF left(coalesce(v_resp ->> 'content', ''), 1) <> '{' THEN
    RETURN '⚠️ Ответ Telegram не похож на JSON: ' || left(coalesce(v_resp ->> 'content', '—'), 200);
  END IF;
  v_res := (v_resp ->> 'content')::jsonb;
  IF NOT coalesce((v_res ->> 'ok')::boolean, false) THEN
    RETURN '⚠️ Telegram не принял токен: ' || coalesce(v_res ->> 'description', 'проверь токен у @BotFather')
           || '. Исправление: select public.iva_setup_bot(''новый-токен''); затем снова эта строка.';
  END IF;

  FOR v_item IN SELECT jsonb_array_elements(coalesce(v_res -> 'result', '[]'::jsonb)) LOOP
    v_chat := v_item -> 'message' -> 'chat';
    IF v_chat IS NULL OR (v_chat ->> 'id') IS NULL THEN
      CONTINUE;
    END IF;
    v_id   := (v_chat ->> 'id')::bigint;
    v_name := coalesce(v_chat ->> 'first_name', v_chat ->> 'username', '');
    IF NOT (v_id = ANY (v_ids)) THEN
      v_ids   := array_append(v_ids, v_id);
      v_names := array_append(v_names, v_name);
      UPDATE public.iva_tg_chats AS c
         SET first_name = v_name, username = coalesce(v_chat ->> 'username', ''), seen_at = now()
       WHERE c.chat_id = v_id;
      INSERT INTO public.iva_tg_chats (chat_id, first_name, username, seen_at)
      SELECT v_id, v_name, coalesce(v_chat ->> 'username', ''), now()
       WHERE NOT EXISTS (SELECT 1 FROM public.iva_tg_chats c2 WHERE c2.chat_id = v_id);
    END IF;
  END LOOP;

  IF coalesce(array_length(v_ids, 1), 0) = 0 THEN
    RETURN '⏳ Telegram ответил, но написавших боту не видно: открой бота, нажми Start и запусти эту строку через 10 секунд.';
  ELSIF coalesce(array_length(v_ids, 1), 0) = 1 THEN
    PERFORM public.iva_tg_link('owner', v_ids[1], coalesce(nullif(v_names[1], ''), 'Владелец'));
    RETURN '✅ Готово: получатель owner привязан (chat ' || v_ids[1] || coalesce(' — ' || v_names[1], '') ||
           '). Следующий Run, через 10 секунд: select public.iva_tg_test();';
  END IF;

  SELECT string_agg(u.id::text || ' (' || coalesce(nullif(u.nm, ''), '?') || ')', ', ')
    INTO v_list
    FROM unnest(v_ids, v_names) AS u (id, nm);
  RETURN '⚠️ Боту писали несколько человек: ' || v_list ||
         E'.\nВыбери себя: select public.iva_tg_link(''owner'', ТВОЙ_CHAT_ID, ''Илья'');';
EXCEPTION WHEN OTHERS THEN
  RETURN '⚠️ Привязка не удалась: ' || sqlerrm;
END $function$;

-- Список написавших боту (для привязки пунктов проката)
CREATE OR REPLACE FUNCTION public.iva_tg_poll()
 RETURNS TABLE(chat_id bigint, first_name text, username text, seen_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_req  bigint;
  v_resp jsonb;
  v_res  jsonb;
  v_item jsonb;
  v_chat jsonb;
  v_id   bigint;
  v_name text;
BEGIN
  SELECT coalesce(max(nullif(value, '')::bigint), 0) INTO v_req
    FROM public.iva_secrets WHERE name = 'TG_REQ_GETUPDATES';
  v_resp := public.iva_tg_resp(v_req);

  IF v_resp IS NOT NULL AND coalesce(v_resp ->> 'status', '') = '200'
     AND left(coalesce(v_resp ->> 'content', ''), 1) = '{' THEN
    v_res := (v_resp ->> 'content')::jsonb;
    IF coalesce((v_res ->> 'ok')::boolean, false) THEN
      FOR v_item IN SELECT jsonb_array_elements(coalesce(v_res -> 'result', '[]'::jsonb)) LOOP
        v_chat := v_item -> 'message' -> 'chat';
        IF v_chat IS NULL OR (v_chat ->> 'id') IS NULL THEN
          CONTINUE;
        END IF;
        v_id   := (v_chat ->> 'id')::bigint;
        v_name := coalesce(v_chat ->> 'first_name', v_chat ->> 'username', '');
        UPDATE public.iva_tg_chats AS c
           SET first_name = v_name, username = coalesce(v_chat ->> 'username', ''), seen_at = now()
         WHERE c.chat_id = v_id;
        INSERT INTO public.iva_tg_chats (chat_id, first_name, username, seen_at)
        SELECT v_id, v_name, coalesce(v_chat ->> 'username', ''), now()
         WHERE NOT EXISTS (SELECT 1 FROM public.iva_tg_chats c2 WHERE c2.chat_id = v_id);
      END LOOP;
    END IF;
  END IF;

  PERFORM public.iva_tg_ask_updates();

  RETURN QUERY SELECT c.chat_id, c.first_name, c.username, c.seen_at
    FROM public.iva_tg_chats c ORDER BY c.seen_at DESC;
  RETURN;
END $function$;

-- Проверка: отправить тестовое сообщение (select public.iva_tg_test();)
CREATE OR REPLACE FUNCTION public.iva_tg_test()
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_token  text;
  v_chat   bigint;
  v_active boolean;
  v_req    bigint;
BEGIN
  SELECT value INTO v_token FROM public.iva_secrets WHERE name = 'BOT_TOKEN';
  IF coalesce(v_token, '') = '' THEN
    RETURN '❌ Не задан токен бота. Выполни: select public.iva_setup_bot(''токен-от-BotFather'');';
  END IF;

  SELECT chat_id, active INTO v_chat, v_active FROM public.iva_tg_targets WHERE key = 'owner';
  IF v_chat IS NULL THEN
    IF v_active IS NULL THEN
      RETURN '❌ Чат владельца не привязан. Выполни: select public.iva_tg_confirm(); (через ~10 секунд после iva_setup_bot)';
    END IF;
    RETURN '❌ У получателя owner не заполнен chat_id.';
  END IF;
  IF NOT coalesce(v_active, true) THEN
    RETURN '❌ Получатель owner выключен: update public.iva_tg_targets set active = true where key = ''owner'';';
  END IF;

  v_req := public.iva_tg_send('owner', '🤖 Ива: тест. Если это сообщение видно — уведомления о заявках работают.');
  IF v_req IS NULL THEN
    RETURN '❌ Запрос не поставлен: проверь pg_net (Supabase → Database → Extensions → pg_net).';
  END IF;
  PERFORM public.iva_tg_remember('TG_REQ_TEST', v_req);
  RETURN '✅ Тестовое сообщение поставлено в очередь (запрос ' || v_req ||
         '). Через 10 секунд выполни ОТДЕЛЬНЫМ Run: select public.iva_tg_status();';
EXCEPTION WHEN OTHERS THEN
  RETURN '⚠️ Внутренняя ошибка проверки: ' || sqlerrm;
END $function$;

-- Проверка: дошло ли тестовое сообщение (select public.iva_tg_status();)
CREATE OR REPLACE FUNCTION public.iva_tg_status()
 RETURNS text
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_ns   text;
  v_req  bigint;
  v_resp jsonb;
  v_row  record;
  v_txt  text := '';
  v_n    integer;
  v_desc text;
BEGIN
  v_ns := public.iva_tg_net_schema();
  IF v_ns IS NULL THEN
    RETURN '❌ pg_net не включён: Supabase → Database → Extensions → pg_net';
  END IF;

  SELECT coalesce(max(nullif(value, '')::bigint), 0) INTO v_req
    FROM public.iva_secrets WHERE name = 'TG_REQ_TEST';
  v_resp := public.iva_tg_resp(v_req);

  IF v_req = 0 OR v_resp IS NULL THEN
    v_txt := '⏳ Ответа на последнюю проверку не видно' ||
             coalesce(' (запрос ' || v_req || ')', '') ||
             '. Запусти: select public.iva_tg_test(); а через 10 секунд — эту строку снова.';
  ELSIF coalesce(v_resp ->> 'status', '') = '200'
        AND coalesce(((v_resp ->> 'content')::jsonb ->> 'ok')::boolean, false) THEN
    v_txt := '✅ Telegram принял сообщение (200, ok:true) — значит, бот и чат работают. Проверь чат: там должно быть тестовое сообщение.';
  ELSE
    v_desc := coalesce(nullif((v_resp ->> 'content'), ''), nullif(v_resp ->> 'error', ''), 'нет ответа');
    v_txt := '⚠️ Telegram ответил ' || coalesce(nullif(v_resp ->> 'status', ''), '—') || ': ' || left(v_desc, 250);
  END IF;

  EXECUTE format('SELECT count(*)::int FROM %s._http_response', v_ns) INTO v_n;
  v_txt := v_txt || E'\n\nВсего ответов в канале: ' || v_n;
  IF coalesce(v_n, 0) = 0 THEN
    v_txt := v_txt || E' — ни одного. Канал pg_net не работает: Database → Extensions → pg_net → выключить и включить, затем Supabase → Settings → General → Restart project.';
  END IF;
  FOR v_row IN EXECUTE format(
        'SELECT id, status_code, timed_out, error_msg FROM %s._http_response ORDER BY id DESC LIMIT 3', v_ns) LOOP
    v_txt := v_txt || E'\n  · запрос ' || v_row.id || ': ' || coalesce(v_row.status_code::text, '—') ||
             CASE WHEN coalesce(v_row.timed_out, false) THEN ' (таймаут)' ELSE '' END ||
             coalesce(nullif(' ' || v_row.error_msg, ' '), '');
  END LOOP;
  RETURN v_txt;
EXCEPTION WHEN OTHERS THEN
  RETURN '⚠️ Не удалось прочитать ответы канала: ' || sqlerrm;
END $function$;


-- ===========================================================================
-- Не Telegram, но тоже уходит наружу из базы — для справки (НЕ восстанавливать отсюда).
-- ===========================================================================

-- orders AFTER INSERT → функция Supabase order_to_max (мессенджер MAX).
-- ⚠️ Отправляет ВСЮ строку заявки (to_jsonb(NEW)): имя, телефон, адрес, комментарий.
-- Ключ в заголовке — публичный ключ сайта (sb_publishable_…), он и так виден в assets/sb.js.
/*
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
*/

-- tools, services AFTER INSERT → функция Supabase vk_wall_post (стена ВКонтакте).
-- Отправляет: название, категорию, цену, описание объявления (персональных данных нет).
-- Ключ в адресе (?key=…) в репозиторий НЕ переносится — заменён на <СКРЫТО>.
/*
CREATE OR REPLACE FUNCTION public.fn_vk_wall_post()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
AS $function$
begin
  if new.active is distinct from false then
    begin
      perform net.http_post(
        url := 'https://wdxdeatphizclskfmfxi.supabase.co/functions/v1/vk_wall_post?key=<СКРЫТО>',
        headers := jsonb_build_object('Content-Type','application/json'),
        body := json_build_object('kind', tg_table_name,
                                  'title', coalesce(new.name, new.title),
                                  'cat', case when tg_table_name = 'tools' then coalesce(new.cat_label,'') else '' end,
                                  'price', new.price,
                                  'descr', coalesce(new.descr,''))::text
      );
    exception when others then null;
    end;
  end if;
  return new;
end $function$;
*/

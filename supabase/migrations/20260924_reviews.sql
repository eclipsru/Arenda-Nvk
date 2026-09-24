-- ============================================================================
-- Миграция: Отзывы и рейтинг (2026-09-24)
-- ============================================================================

-- 1. Добавляем колонки rating и reviews_count в tools, если их нет
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS rating numeric(3,2) NOT NULL DEFAULT 0.0;
ALTER TABLE public.tools ADD COLUMN IF NOT EXISTS reviews_count integer NOT NULL DEFAULT 0;

-- 2. Таблица отзывов
CREATE TABLE IF NOT EXISTS public.reviews (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tool_id uuid NOT NULL REFERENCES public.tools(id) ON DELETE CASCADE,
  author_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  author_email text NOT NULL DEFAULT '',
  author_name text NOT NULL DEFAULT '',
  rating integer NOT NULL CHECK (rating >= 1 AND rating <= 5),
  text text NOT NULL DEFAULT '' CHECK (char_length(text) <= 1000),
  status text NOT NULL DEFAULT 'published' CHECK (status IN ('published', 'hidden')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_reviews_tool_author UNIQUE (tool_id, author_id)
);

CREATE INDEX IF NOT EXISTS idx_reviews_tool_id ON public.reviews(tool_id);
CREATE INDEX IF NOT EXISTS idx_reviews_author_id ON public.reviews(author_id);
CREATE INDEX IF NOT EXISTS idx_reviews_status ON public.reviews(status);

-- 3. RLS-политики для reviews
ALTER TABLE public.reviews ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "reviews_select_public" ON public.reviews;
CREATE POLICY "reviews_select_public" ON public.reviews
FOR SELECT TO anon, authenticated
USING (
  status = 'published'
  OR (auth.uid() IS NOT NULL AND author_id = auth.uid())
  OR (auth.uid() IS NOT NULL AND EXISTS (
    SELECT 1 FROM public.admins WHERE LOWER(email) = LOWER(auth.jwt()->>'email') AND role = 'chief' AND active = true
  ))
);

DROP POLICY IF EXISTS "reviews_insert_authenticated" ON public.reviews;
CREATE POLICY "reviews_insert_authenticated" ON public.reviews
FOR INSERT TO authenticated
WITH CHECK (author_id = auth.uid());

DROP POLICY IF EXISTS "reviews_update_author_or_chief" ON public.reviews;
CREATE POLICY "reviews_update_author_or_chief" ON public.reviews
FOR UPDATE TO authenticated
USING (
  author_id = auth.uid()
  OR EXISTS (
    SELECT 1 FROM public.admins WHERE LOWER(email) = LOWER(auth.jwt()->>'email') AND role = 'chief' AND active = true
  )
)
WITH CHECK (
  author_id = auth.uid()
  OR EXISTS (
    SELECT 1 FROM public.admins WHERE LOWER(email) = LOWER(auth.jwt()->>'email') AND role = 'chief' AND active = true
  )
);

DROP POLICY IF EXISTS "reviews_delete_author_or_chief" ON public.reviews;
CREATE POLICY "reviews_delete_author_or_chief" ON public.reviews
FOR DELETE TO authenticated
USING (
  author_id = auth.uid()
  OR EXISTS (
    SELECT 1 FROM public.admins WHERE LOWER(email) = LOWER(auth.jwt()->>'email') AND role = 'chief' AND active = true
  )
);

-- 4. Триггер пересчёта рейтинга и количества отзывов инструмента
CREATE OR REPLACE FUNCTION public.fn_sync_tool_rating()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  target_tool_id uuid;
  avg_r numeric(3,2);
  cnt integer;
BEGIN
  target_tool_id := COALESCE(NEW.tool_id, OLD.tool_id);
  
  SELECT 
    COALESCE(ROUND(AVG(rating)::numeric, 2), 0.0),
    COUNT(*)
  INTO avg_r, cnt
  FROM public.reviews
  WHERE tool_id = target_tool_id AND status = 'published';

  UPDATE public.tools
  SET rating = avg_r,
      reviews_count = cnt
  WHERE id = target_tool_id;

  RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS tr_sync_tool_rating ON public.reviews;
CREATE TRIGGER tr_sync_tool_rating
AFTER INSERT OR UPDATE OR DELETE ON public.reviews
FOR EACH ROW
EXECUTE FUNCTION public.fn_sync_tool_rating();

-- 5. Проверка прав пользователя на оставление отзыва
CREATE OR REPLACE FUNCTION public.can_user_review(p_tool_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_uid uuid;
  v_email text;
  v_tool_name text;
  v_tool_owner text;
  v_has_order boolean := false;
  v_existing_review jsonb := null;
  v_is_chief boolean := false;
BEGIN
  v_uid := auth.uid();
  IF v_uid IS NULL THEN
    RETURN jsonb_build_object('allowed', false, 'reason', 'auth_required');
  END IF;

  SELECT email INTO v_email FROM auth.users WHERE id = v_uid;
  SELECT name, owner_email INTO v_tool_name, v_tool_owner FROM public.tools WHERE id = p_tool_id;

  IF v_tool_name IS NULL THEN
    RETURN jsonb_build_object('allowed', false, 'reason', 'tool_not_found');
  END IF;

  -- Владелец не может оценивать свой инструмент
  IF LOWER(v_email) = LOWER(COALESCE(v_tool_owner, '')) THEN
    RETURN jsonb_build_object('allowed', false, 'reason', 'own_tool');
  END IF;

  -- Главный админ
  SELECT EXISTS (
    SELECT 1 FROM public.admins WHERE LOWER(email) = LOWER(v_email) AND role = 'chief' AND active = true
  ) INTO v_is_chief;

  -- Проверяем наличие оформленной заявки на этот инструмент
  SELECT EXISTS (
    SELECT 1 FROM public.orders o
    WHERE LOWER(COALESCE(o.user_email, '')) = LOWER(v_email)
      AND (
        o.tools ILIKE '%' || v_tool_name || '%'
        OR EXISTS (
          SELECT 1 FROM public.order_parts op
          WHERE op.order_id = o.id
            AND (op.tools_text ILIKE '%' || v_tool_name || '%'
                 OR (op.price_snapshot::text ILIKE '%' || v_tool_name || '%'))
        )
      )
  ) INTO v_has_order;

  IF NOT v_has_order AND NOT v_is_chief THEN
    RETURN jsonb_build_object('allowed', false, 'reason', 'no_order');
  END IF;

  -- Проверяем существующий отзыв
  SELECT jsonb_build_object(
    'id', r.id,
    'rating', r.rating,
    'text', r.text,
    'author_name', r.author_name,
    'created_at', r.created_at,
    'updated_at', r.updated_at,
    'status', r.status
  )
  INTO v_existing_review
  FROM public.reviews r
  WHERE r.tool_id = p_tool_id AND r.author_id = v_uid;

  RETURN jsonb_build_object(
    'allowed', true,
    'has_review', (v_existing_review IS NOT NULL),
    'review', v_existing_review
  );
END;
$$;

-- 6. RPC: публикация или обновление отзыва
CREATE OR REPLACE FUNCTION public.submit_review(
  p_tool_id uuid,
  p_rating integer,
  p_text text,
  p_author_name text DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_uid uuid;
  v_email text;
  v_can jsonb;
  v_res jsonb;
  v_name text;
BEGIN
  v_uid := auth.uid();
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Нужно войти в систему';
  END IF;

  IF p_rating < 1 OR p_rating > 5 THEN
    RAISE EXCEPTION 'Оценка должна быть от 1 до 5 звёзд';
  END IF;

  p_text := BTRIM(COALESCE(p_text, ''));
  IF CHAR_LENGTH(p_text) > 1000 THEN
    RAISE EXCEPTION 'Длина отзыва не может превышать 1000 символов';
  END IF;

  SELECT email, COALESCE(raw_user_meta_data->>'name', raw_user_meta_data->>'full_name', SPLIT_PART(email, '@', 1))
  INTO v_email, v_name
  FROM auth.users WHERE id = v_uid;

  p_author_name := BTRIM(COALESCE(p_author_name, ''));
  IF CHAR_LENGTH(p_author_name) = 0 THEN
    p_author_name := COALESCE(v_name, 'Арендатор');
  END IF;

  -- Проверка прав
  v_can := public.can_user_review(p_tool_id);
  IF NOT (v_can->>'allowed')::boolean THEN
    IF v_can->>'reason' = 'own_tool' THEN
      RAISE EXCEPTION 'Нельзя оставлять отзыв на собственный инструмент';
    ELSIF v_can->>'reason' = 'no_order' THEN
      RAISE EXCEPTION 'Отзыв могут оставить только арендаторы, оформившие заказ на этот инструмент';
    ELSE
      RAISE EXCEPTION 'Действие недоступно';
    END IF;
  END IF;

  -- Сохранение (Upsert)
  INSERT INTO public.reviews (
    tool_id, author_id, author_email, author_name, rating, text, status, updated_at
  ) VALUES (
    p_tool_id, v_uid, v_email, p_author_name, p_rating, p_text, 'published', now()
  )
  ON CONFLICT (tool_id, author_id) DO UPDATE
  SET rating = EXCLUDED.rating,
      text = EXCLUDED.text,
      author_name = EXCLUDED.author_name,
      status = 'published',
      updated_at = now()
  RETURNING jsonb_build_object(
    'id', reviews.id,
    'tool_id', reviews.tool_id,
    'author_id', reviews.author_id,
    'author_name', reviews.author_name,
    'rating', reviews.rating,
    'text', reviews.text,
    'created_at', reviews.created_at,
    'updated_at', reviews.updated_at,
    'status', reviews.status
  ) INTO v_res;

  RETURN v_res;
END;
$$;

-- 7. RPC: переключение статуса отзыва (модерация)
CREATE OR REPLACE FUNCTION public.toggle_review_status(p_review_id uuid, p_status text)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_uid uuid;
  v_email text;
  v_is_chief boolean := false;
BEGIN
  v_uid := auth.uid();
  IF v_uid IS NULL THEN RAISE EXCEPTION 'Нужно войти в систему'; END IF;
  SELECT email INTO v_email FROM auth.users WHERE id = v_uid;
  SELECT EXISTS (
    SELECT 1 FROM public.admins WHERE LOWER(email) = LOWER(v_email) AND role = 'chief' AND active = true
  ) INTO v_is_chief;

  IF NOT v_is_chief THEN
    RAISE EXCEPTION 'Только владелец площадки может модерировать отзывы';
  END IF;

  IF p_status NOT IN ('published', 'hidden') THEN
    RAISE EXCEPTION 'Некорректный статус';
  END IF;

  UPDATE public.reviews
  SET status = p_status, updated_at = now()
  WHERE id = p_review_id;

  RETURN true;
END;
$$;

-- 8. RPC: общий рейтинг арендодателя (проката)
CREATE OR REPLACE FUNCTION public.get_landlord_rating(p_owner_email text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_avg numeric(3,1);
  v_cnt integer;
BEGIN
  SELECT
    COALESCE(ROUND(AVG(r.rating)::numeric, 1), 0.0),
    COUNT(r.id)
  INTO v_avg, v_cnt
  FROM public.tools t
  JOIN public.reviews r ON r.tool_id = t.id
  WHERE LOWER(t.owner_email) = LOWER(p_owner_email)
    AND r.status = 'published';

  RETURN jsonb_build_object(
    'rating', v_avg,
    'reviews_count', v_cnt
  );
END;
$$;

-- ============================================================================
-- 10.71: нецензурные слова в отзывах запрещены (решение владельца 14.10.2026)
-- Применяет владелец в Supabase → SQL Editor (решение №15). Сайт не меняется: при попытке
-- сохранить отзыв с матом и сайт, и приложение покажут сообщение базы.
-- Та же проверка — в приложении (IvaReviewPatch.hasMat): выражения одинаковые, сторож сверяет.
-- Модерация (toggle_review_status) меняет только status — проверка её не трогает.
-- Откат:
--   DROP TRIGGER IF EXISTS tr_reviews_no_mat ON public.reviews;
--   DROP FUNCTION IF EXISTS public.iva_reviews_no_mat();
--   DROP FUNCTION IF EXISTS public.iva_has_mat(text);
-- ============================================================================

-- 1. Есть ли в тексте нецензурные слова.
--    Текст: строчные, ё→е, ъ→ь, похожие латинские буквы и цифры → русские, точки/дефисы/звёздочки
--    внутри слова убираются; слово — без повторов букв («сууука» → «сука»); исключения — «страхует», «психуешь».
CREATE OR REPLACE FUNCTION public.iva_has_mat(p text)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
SET search_path = public
AS $$
DECLARE
  s text;
  t text;
BEGIN
  IF p IS NULL OR btrim(p) = '' THEN
    RETURN false;
  END IF;
  s := translate(lower(p), 'ёъaceopxykmt306@', 'еьасеорхукмтзоба');
  s := regexp_replace(s, '[._*''`~-]', '', 'g');
  FOR t IN SELECT x FROM regexp_split_to_table(s, '[^а-яa-z0-9]+') AS x LOOP
    CONTINUE WHEN t = '';
    t := regexp_replace(t, '(.)\1+', '\1', 'g');
    CONTINUE WHEN t ~ '^(за|пере|под|недо|при)?страху|психу';
    IF t ~ 'хуй|хуе|хуя|хуи|хую|^хули$|пизд|пздц|^еб|^(за|на|по|вы|от|у|сь|разь|до|подь|при|про|обь|вь|изь|недо|пере|оть|взь)еб[аиулнкчшыо]|^долбоеб|^(вы|по|за|у)?бля(д|т|$)|^муд(ак|ил|оз|ач|ень|ло)|^пид[оа]?р|^педик(и|а|ов|у|ам)?$|залуп|^г[ао]ндон|^манд(а|у|ы|ой|е)$|^мандавош|^шлюх|^сук(а|и|у|ой|ин|ины|ину|ами)$|^(на|по|ни)?хер($|н|ов)|^(за|по|на|от)?дроч' THEN
      RETURN true;
    END IF;
  END LOOP;
  RETURN false;
END;
$$;

-- 2. Проверка при сохранении отзыва (новый отзыв и правка текста или имени).
CREATE OR REPLACE FUNCTION public.iva_reviews_no_mat()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
  IF public.iva_has_mat(NEW.text) THEN
    RAISE EXCEPTION 'В отзыве есть нецензурные слова. Уберите их, пожалуйста.';
  END IF;
  IF public.iva_has_mat(NEW.author_name) THEN
    RAISE EXCEPTION 'В имени есть нецензурные слова. Измените имя в профиле.';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_reviews_no_mat ON public.reviews;
CREATE TRIGGER tr_reviews_no_mat
BEFORE INSERT OR UPDATE OF text, author_name ON public.reviews
FOR EACH ROW
EXECUTE FUNCTION public.iva_reviews_no_mat();

-- 3. По желанию: скрыть уже опубликованные отзывы с матом (рейтинг пересчитает штатный триггер).
--    Сначала можно посмотреть, какие это отзывы:
--      SELECT id, author_name, rating, text FROM public.reviews
--      WHERE status = 'published' AND (public.iva_has_mat(text) OR public.iva_has_mat(author_name));
-- UPDATE public.reviews SET status = 'hidden', updated_at = now()
-- WHERE status = 'published' AND (public.iva_has_mat(text) OR public.iva_has_mat(author_name));

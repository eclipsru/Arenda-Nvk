-- ============================================================
--  Ива 10.94 — «Релиз версии» из приложения может нажать и eclipsik.ru@mail.ru
--  (просьба владельца 16.10.2026: жёлтая «Обновить до версии N» в кабинете
--  eclips.ru@mail.ru и eclipsik.ru@mail.ru, вариант «Релиз версия» делает
--  версию доступной всем).
--
--  Что меняет: ДОБАВЛЯЕТ одно правило вставки в public.app_releases — для
--  входа eclipsik.ru@mail.ru (подтвердившим записывается он сам). Правило
--  eclips.ru@mail.ru из 20261005 не трогается; читать, менять и удалять
--  строки по-прежнему никто нового права не получает. Таблица, столбцы и
--  проверки ссылки на APK — те же.
--
--  Без этого SQL приложение у eclipsik покажет: «Нет права выпускать версию».
--  Нужна уже применённая 20261005_app_releases.sql.
--
--  Миграция идемпотентная: повторный запуск ничего не меняет.
--
--  КАК ПРИМЕНИТЬ (действие владельца): Supabase → SQL Editor → вставить весь
--  текст → Run. Ожидаемо: «Success. No rows returned».
--
--  ПРОВЕРКА (должно быть 2 строки: app_releases_insert_creator и _tester):
--    select policyname from pg_policies
--     where schemaname = 'public' and tablename = 'app_releases' and cmd = 'INSERT';
--
--  ОТКАТ (снова выпускает только eclips.ru@mail.ru):
--    DROP POLICY IF EXISTS app_releases_insert_tester ON public.app_releases;
-- ============================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'app_releases'
                 AND policyname = 'app_releases_insert_tester') THEN
    CREATE POLICY app_releases_insert_tester ON public.app_releases
      FOR INSERT TO authenticated
      WITH CHECK (lower(coalesce(auth.jwt() ->> 'email', '')) = 'eclipsik.ru@mail.ru'
                  AND confirmed_by = 'eclipsik.ru@mail.ru');
  END IF;
END $$;

COMMENT ON TABLE public.app_releases IS
  'Подтверждённые релизы Android-приложения. Сайт и приложение показывают всем последнюю строку. Добавляют только eclips.ru@mail.ru и eclipsik.ru@mail.ru (кнопки «Подтвердить релиз» / «Релиз версии»).';

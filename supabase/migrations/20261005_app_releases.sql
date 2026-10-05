-- ============================================================
--  Ива — «Подтвердить релиз» приложения (решение владельца 05.10.2026).
--
--  Зачем: какую версию Android-приложения видят ВСЕ пользователи (баннер на
--  10 секунд и карточка в кабинете), решает создатель кнопкой «Подтвердить
--  релиз» на жёлтой карточке в своём кабинете. Нажатие добавляет строку в эту
--  таблицу; сайт берёт последнюю строку. Пока строк нет — у всех старая 10.14.
--
--  Права (проверяет сама база, не сайт):
--    • читать номер/ссылку выпущенной версии могут все (это публичная ссылка);
--    • добавить строку может ТОЛЬКО вход под eclips.ru@mail.ru;
--    • менять и удалять строки через сайт нельзя никому (история релизов).
--  Ссылка на файл — только на APK этого проекта (сайт или GitHub eclipsru/Arenda-Nvk).
--  Почта подтвердившего хранится, но наружу не отдаётся.
--
--  Миграция идемпотентная: только CREATE ... IF NOT EXISTS, без DROP/TRUNCATE.
--
--  КАК ПРИМЕНИТЬ (действие владельца): Supabase → SQL Editor → вставить весь
--  текст → Run. Ожидаемо: «Success. No rows returned».
--
--  ПРОВЕРКА:  select count(*) from public.app_releases;   -- 0 до первого подтверждения
--
--  ОТМЕНИТЬ ПОСЛЕДНИЙ РЕЛИЗ (у всех снова предыдущая версия):
--    delete from public.app_releases
--     where version_code = (select max(version_code) from public.app_releases);
--  ОТКАТ ЦЕЛИКОМ (у всех снова 10.14):
--    DROP TABLE IF EXISTS public.app_releases;
-- ============================================================

CREATE TABLE IF NOT EXISTS public.app_releases (
  version_code  integer     PRIMARY KEY CHECK (version_code > 0),
  version_name  text        NOT NULL CHECK (version_name ~ '^[0-9]+\.[0-9]+$'),
  apk_url       text        NOT NULL CHECK (apk_url ~ '^(https://github\.com/eclipsru/Arenda-Nvk/raw/[A-Za-z0-9._/-]+/)?ProkatInstrumenta-[0-9]+\.[0-9]+\.apk$'),
  reinstall     boolean     NOT NULL DEFAULT false,
  notes         text        NOT NULL DEFAULT '' CHECK (length(notes) <= 500),
  confirmed_by  text        NOT NULL DEFAULT lower(coalesce(auth.jwt() ->> 'email', '')),
  confirmed_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.app_releases IS
  'Подтверждённые релизы Android-приложения. Сайт показывает всем последнюю строку. Добавляет только eclips.ru@mail.ru (кнопка «Подтвердить релиз»).';

ALTER TABLE public.app_releases ENABLE ROW LEVEL SECURITY;

-- Наружу — только то, что нужно для ссылки (без почты подтвердившего)
REVOKE ALL ON public.app_releases FROM anon, authenticated;
GRANT SELECT (version_code, version_name, apk_url, reinstall, notes, confirmed_at)
  ON public.app_releases TO anon, authenticated;
GRANT INSERT (version_code, version_name, apk_url, reinstall, notes)
  ON public.app_releases TO authenticated;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'app_releases'
                 AND policyname = 'app_releases_read_all') THEN
    CREATE POLICY app_releases_read_all ON public.app_releases
      FOR SELECT TO anon, authenticated USING (true);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'app_releases'
                 AND policyname = 'app_releases_insert_creator') THEN
    CREATE POLICY app_releases_insert_creator ON public.app_releases
      FOR INSERT TO authenticated
      WITH CHECK (lower(coalesce(auth.jwt() ->> 'email', '')) = 'eclips.ru@mail.ru'
                  AND confirmed_by = 'eclips.ru@mail.ru');
  END IF;
END $$;

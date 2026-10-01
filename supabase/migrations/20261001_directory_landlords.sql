-- ============================================================================
-- Миграция: справочник прокатов (directory_landlords) — этап П2
-- Дата: 2026-10-01
--
-- Что делает: ДОБАВЛЯЕТ новую таблицу для базы прокатов инструмента
-- (115 контактов из tools/real_bases.csv). Ничего не удаляет и не меняет
-- в существующих таблицах, поэтому безопасна для боевой базы.
--
-- Повторный запуск безопасен (create ... if not exists, drop policy if exists).
-- Откат: см. конец файла (закомментированный блок).
-- ============================================================================

-- 1. Таблица справочника
CREATE TABLE IF NOT EXISTS public.directory_landlords (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  city          text NOT NULL,
  city_slug     text,                      -- ссылка города из tools/cities.json; NULL = города нет в списке сайта
  name          text NOT NULL DEFAULT '',
  phone         text NOT NULL,             -- в виде +7XXXXXXXXXX (нормализовано при ввозе)
  address       text NOT NULL DEFAULT '',
  source        text NOT NULL DEFAULT '',  -- откуда взят контакт (открытый источник)
  source_date   date,                      -- когда контакт получен/проверен источником
  has_whatsapp  boolean NOT NULL DEFAULT false,
  status        text NOT NULL DEFAULT 'new'
                CHECK (status IN ('new', 'verified', 'hidden', 'declined')),
  checked_at    timestamptz,               -- когда человек проверил карточку глазами
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  -- один и тот же контакт не должен появляться дважды: телефон + адрес точки
  CONSTRAINT uq_directory_landlords_phone_address UNIQUE (phone, address)
);

COMMENT ON TABLE public.directory_landlords IS
  'Справочник прокатов инструмента (открытые источники). Виден всем, кроме скрытых по просьбе владельца точки.';

-- 2. Индексы (поиск по городу, статусу и телефону)
CREATE INDEX IF NOT EXISTS idx_directory_landlords_city       ON public.directory_landlords (city);
CREATE INDEX IF NOT EXISTS idx_directory_landlords_city_slug  ON public.directory_landlords (city_slug);
CREATE INDEX IF NOT EXISTS idx_directory_landlords_status     ON public.directory_landlords (status);
CREATE INDEX IF NOT EXISTS idx_directory_landlords_phone      ON public.directory_landlords (phone);

-- 3. Правила доступа (RLS)
ALTER TABLE public.directory_landlords ENABLE ROW LEVEL SECURITY;

-- 3.1 Чтение: всем посетителям — только не скрытые и не отклонённые карточки.
--     Пометка 'hidden' — это механизм «убрать по просьбе владельца точки».
DROP POLICY IF EXISTS "directory_landlords_select_public" ON public.directory_landlords;
CREATE POLICY "directory_landlords_select_public" ON public.directory_landlords
  FOR SELECT TO anon, authenticated
  USING (
    status IN ('new', 'verified')
    -- администратор видит и скрытые/отклонённые карточки: иначе их нечем управлять
    OR (auth.uid() IS NOT NULL AND EXISTS (
      SELECT 1 FROM public.admins
      WHERE LOWER(email) = LOWER(auth.jwt() ->> 'email') AND active = true
    ))
  );

-- 3.2 Изменение: только активные администраторы (ввоз данных выполняется
--     сервисным ключом, он правила RLS обходит).
DROP POLICY IF EXISTS "directory_landlords_write_admins" ON public.directory_landlords;
CREATE POLICY "directory_landlords_write_admins" ON public.directory_landlords
  FOR ALL TO authenticated
  USING (
    EXISTS (
      SELECT 1 FROM public.admins
      WHERE LOWER(email) = LOWER(auth.jwt() ->> 'email') AND active = true
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM public.admins
      WHERE LOWER(email) = LOWER(auth.jwt() ->> 'email') AND active = true
    )
  );

-- ============================================================================
-- Откат (выполнять ТОЛЬКО если решено убрать справочник целиком).
-- Раскомментировать и запустить вручную; данные справочника при этом удалятся.
-- ============================================================================
-- DROP POLICY IF EXISTS "directory_landlords_select_public" ON public.directory_landlords;
-- DROP POLICY IF EXISTS "directory_landlords_write_admins"   ON public.directory_landlords;
-- DROP TABLE IF EXISTS public.directory_landlords;

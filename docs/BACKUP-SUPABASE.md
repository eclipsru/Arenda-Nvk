# Бэкап базы Supabase — пошагово (Ива)

## ⚡ Быстрый путь для Windows (рекомендую)

Скрипт делает всё сам: находит `pg_dump`, спрашивает строку подключения, сохраняет три дампа, проверяет их и по флагу кладёт структуру базы в репозиторий.

1. Откройте PowerShell **в папке проекта** (в Проводнике: правый клик по папке → «Открыть в Терминале» / Shift+правый клик → «Открыть окно PowerShell здесь»).
2. Выполните:
   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\backup-windows.ps1 -Baseline
   ```
3. Вставьте строку подключения (Supabase → Project Settings → Database → Connection string → URI) и нажмите Enter.
4. Дождитесь отчёта: сколько таблиц выгружено, размеры файлов, куда всё легло.

Если `pg_dump` не установлен — скрипт сам подскажет одну команду `winget install` и запасной путь через Dashboard. Файлы бэкапа в GitHub не попадают (закрыты `.gitignore`).

Ниже — подробное описание обоих способов и того, как проверить, что бэкап рабочий.


**Зачем:** в репозитории есть только 5 миграций (от сентября 2026). Основная схема (`tools`, `orders`, `admins`, `app_messages` и др.) создавалась вне миграций и **в репозиторий не попала**. Если база сломается или её придётся пересоздавать — восстановить структуру можно будет только из бэкапа.

**Когда делать:** сейчас (перед любыми этапами), затем — перед каждой миграцией, и далее по желанию раз в месяц.

**Правило:** файлы бэкапа лежат минимум в двух местах и **никогда не коммитятся в GitHub** (в `.gitignore` уже закрыты `*.sql`-маски? нет — добавлено правило `backup_*` и `*.dump`).

---

## Вариант 1. Через `pg_dump` (точный и полный; нужен терминал)

### Где взять строку подключения
Supabase Dashboard → проект → **Project Settings → Database → Connection string → URI**. Она выглядит так:
```
postgresql://postgres.abcd:ВАШ_ПАРОЛЬ@aws-0-…pooler.supabase.com:5432/postgres
```
Пароль базы — тот, что задавал при создании проекта. Если забыл: Settings → Database → **Reset database password** (это не сломает сайт, но обновит строку подключения в ваших скриптах — используйте с осторожностью).

### Windows (PowerShell)
1. Установите PostgreSQL-клиент: `winget install PostgreSQL.PostgreSQL` (даст `pg_dump`) — или скачайте «Command line tools» с postgresql.org.
2. Проверьте: `pg_dump --version`
3. Команды (замените `<СТРОКА>` на URI, `<ПАРОЛЬ>` — на пароль базы):

```powershell
# 1) Полный дамп: структура + данные
pg_dump "<СТРОКА>" --no-owner --no-privileges -f backup_full_2026-09-29.sql

# 2) Только структура (это потом положим в репозиторий как baseline)
pg_dump "<СТРОКА>" --no-owner --no-privileges --schema-only -f backup_schema_2026-09-29.sql

# 3) Только данные ключевых таблиц (быстрая точечная страховка)
pg_dump "<СТРОКА>" --no-owner --no-privileges --data-only `
  -t public.tools -t public.orders -t public.order_parts -t public.admins `
  -t public.landlord_applications -t public.landlord_profiles -t public.reviews `
  -f backup_data_2026-09-29.sql
```

### macOS / Linux
```bash
pg_dump "<СТРОКА>" --no-owner --no-privileges -f backup_full_2026-09-29.sql
pg_dump "<СТРОКА>" --no-owner --no-privileges --schema-only -f backup_schema_2026-09-29.sql
pg_dump "<СТРОКА>" --no-owner --no-privileges --data-only \
  -t public.tools -t public.orders -t public.order_parts -t public.admins \
  -t public.landlord_applications -t public.landlord_profiles -t public.reviews \
  -f backup_data_2026-09-29.sql
```

### Как проверить, что бэкап рабочий (обязательно)
1. Файл `backup_full_*.sql` открывается как текст и начинается с комментариев + `SET ...`.
2. Внутри есть `CREATE TABLE public.tools`, `CREATE TABLE public.orders`, `COPY public.tools` (значит, данные попали).
3. Размер файла больше 50 КБ (пустой дамп будет крошечным).
4. Команда проверки одной строкой:
   ```bash
   grep -c "CREATE TABLE" backup_schema_2026-09-29.sql   # должно быть больше 5
   ```

---

## Вариант 2. Без установки программ (через Dashboard)

Подходит, если не хочется ставить `pg_dump`. Сохраняем данные ключевых таблиц в CSV:

1. Dashboard → **Table Editor** → выбираем таблицу.
2. Три точки (⋮) справа сверху → **Export data** → **CSV** → сохранить.
3. Повторить для таблиц:

| Группа | Таблицы |
|---|---|
| Объявления и владельцы | `tools`, `admins`, `limited_admins` |
| Заказы и деньги | `orders`, `order_parts`, `fee_payments`, `order_claims` |
| Арендодатели | `landlord_applications`, `landlord_application_events`, `landlord_profiles` |
| Общение и отзывы | `app_messages`, `reviews`, `likes` |
| Справочники | `cats`, `subcats`, `directory_landlords` |

4. Структуру (схему) в этом варианте получить нельзя — только данные. Поэтому для схемы используйте SQL-запрос: Dashboard → **SQL Editor** → вставить и выполнить:

```sql
-- Структура всех таблиц public одной строкой (для сохранения в файл и в репозиторий)
select table_name,
       column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_schema = 'public'
order by table_name, ordinal_position;
```
Результат → кнопка **Download CSV** → сохранить как `schema_columns_2026-09-29.csv`.

Дополнительно сохраните **политики RLS** (это важнее данных структуры):
```sql
select schemaname, tablename, policyname, cmd, roles, qual, with_check
from pg_policies where schemaname = 'public' order by tablename, policyname;
```
И **функции**:
```sql
select routine_name, routine_type from information_schema.routines
where routine_schema = 'public' order by routine_name;
```

---

## Что делать с schema-baseline (важно)

1. Из варианта 1 у вас есть `backup_schema_*.sql`. Положите его в репозиторий как есть:
   ```
   supabase/migrations/0000_baseline_schema.sql
   ```
   В самом файле добавьте первой строкой комментарий:
   `-- Снимок структуры рабочей базы на 2026-09-29. Только для восстановления. К обычным миграциям не относится.`
2. Если делали вариант 2 — пришлите мне `schema_columns_*.csv` и вывод списка политик RLS, я соберу эквивалентный SQL и вы положите его так же.
3. После добавления baseline в репозиторий — на любой вопрос «а что было в базе изначально» можно ответить без доступа к базе.

**Проверка (после того как файл добавлен):** `grep -c "CREATE TABLE" supabase/migrations/0000_baseline_schema.sql` — должно быть больше 5.

---

## Восстановление (на случай аварии) — коротко

| Что | Команда |
|---|---|
| Вся база из дампа (на пустую базу) | `psql "<СТРОКА>" -f backup_full_2026-09-29.sql` |
| Только данные одной таблицы | `psql "<СТРОКА>" -c "truncate public.tools" && psql "<СТРОКА>" -f backup_tools.sql` |
| Отдельная строка/запись | открыть CSV/SQL, скопировать нужный `INSERT`/строку, выполнить в SQL Editor |

> ⚠️ Восстановление "в живую" базу — операция опасная: сначала спрашиваем (у себя и у меня), потом делаем. Правило проекта: не `drop`, не `truncate` без подтверждения.

---

## Фото и файлы (это НЕ в базе)

Фото инструментов и голосовые сообщения лежат в **Storage**: бакеты `tool-photos` и `voice`.
Бэкап CSV их не сохраняет. Варианты:
1. **Скачать вручную:** Dashboard → Storage → bucket → выделить → Download (для небольших объёмов).
2. **Через CLI:** `supabase storage cp --recursive ss:///tool-photos ./backup/tool-photos` (нужен Supabase CLI и вход).
3. Раз в месяц — проверять, что количество файлов совпадает с ожидаемым.

Минимум сейчас: убедиться, что файлы в бакетах открываются (Dashboard → Storage → открыть пару картинок).

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

Если `pg_dump` не установлен — скрипт сам подскажет четыре пути (в том числе **без установки и без прав администратора**, см. раздел «Если winget падает с ошибкой 403» ниже). Файлы бэкапа в GitHub не попадают (закрыты `.gitignore`).

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

### Windows (PowerShell) — проще всего без установки

`pg_dump` не обязательно устанавливать: он лежит внутри официального архива и работает сразу после распаковки. Установщик не запускается, служба не создаётся, права администратора не нужны.

1. Откройте в браузере (именно в браузере — `winget` на этой ссылке падает с ошибкой 403, это его известный баг):
   `https://get.enterprisedb.com/postgresql/postgresql-17.7-1-windows-x64-binaries.zip` (≈316 МБ)
2. Распакуйте архив в `C:\Users\<Вы>\pgsql`. Внутри появится папка `pgsql\bin\`, а в ней — `pg_dump.exe`.
3. Проверьте: `C:\Users\<Вы>\pgsql\pgsql\bin\pg_dump.exe --version`
4. Если распаковали в другое место — укажите папку скрипту:
   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\backup-windows.ps1 -Baseline -PgBin "D:\pgsql\pgsql\bin"
   ```
   Скрипт сам найдёт `pg_dump` в типовых местах (`pgsql`, `pgsql-tools`, «Загрузки», «Рабочий стол»), если указать путь не хочется.
5. Команды вручную (замените `<СТРОКА>` на URI из настроек проекта):

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

### Если winget падает с ошибкой 403 — три способа, установка не нужна

Симптом: `winget install -e --id PostgreSQL.PostgreSQL.16` обрывается на `Download request status is not success. 0x80190193 : Forbidden (403)`. Ссылка при этом в браузере скачивается нормально — это баг winget, а не проблема вашей сети или блокировка.

| Способ | Что даёт | Что нужно сделать |
|---|---|---|
| **1. Портативный `pg_dump` из браузера** | полный бэкап (структура + данные) | скачать архив выше, распаковать, запустить `tools\backup-windows.ps1 -PgBin "<папка>\bin"` |
| **2. Структура через SQL-редактор** | структура базы (=baseline для репозитория) | Supabase → SQL Editor → вставить `tools/schema-dump.sql` → Run → скопировать результат в `supabase\migrations\0000_baseline_schema.sql` |
| **3. Данные через API** | содержимое всех таблиц | `powershell -ExecutionPolicy Bypass -File tools\backup-api-windows.ps1` (спросит адрес проекта и ключ `service_role`) |

Способы 2 и 3 вместе дают то же, что способ 1, но **вообще без скачиваний и установок**. Способ 3 не выгружает файлы из Storage (`tool-photos`, `voice`) — они хранятся отдельно от базы.

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

## Вариант 2. Без установки программ

### 2а. Данные всех таблиц — скриптом (быстро и с проверкой)

```powershell
powershell -ExecutionPolicy Bypass -File tools\backup-api-windows.ps1
```

Скрипт спросит адрес проекта и ключ `service_role` (Supabase → Project Settings → API). Дальше сам: узнаёт список таблиц, постранично выгружает каждую в файл `<таблица>.ndjson`, сверяет количество записей с базой и пишет `manifest.json`. Ключ нигде не сохраняется. Результат — папка `backup-<дата>\api`.

### 2б. Данные по таблицам вручную через Dashboard

Если хочется выгрузить только несколько таблиц:

1. Dashboard → **Table Editor** → выбрать таблицу.
2. Три точки (⋮) справа сверху → **Export data** → **CSV** → сохранить.
3. Повторить для таблиц:

| Группа | Таблицы |
|---|---|
| Объявления и владельцы | `tools`, `admins`, `limited_admins` |
| Заказы и деньги | `orders`, `order_parts`, `fee_payments`, `order_claims` |
| Арендодатели | `landlord_applications`, `landlord_application_events`, `landlord_profiles` |
| Общение и отзывы | `app_messages`, `reviews`, `likes` |
| Справочники | `cats`, `subcats`, `directory_landlords` |

4. Структуру (схему) так получить нельзя — только данные. Для схемы есть готовый запрос:
   Dashboard → **SQL Editor** → New query → вставить всё содержимое файла `tools/schema-dump.sql` → **Run**.
   В ответе будет одна ячейка с готовым SQL: таблицы со столбцами, ограничения, индексы, функции, триггеры и правила доступа. Скопируйте её целиком в `supabase\migrations\0000_baseline_schema.sql`.

   ⚠️ Копировать нужно **текст дампа**, а не таблицу с колонками `table_name`/`column_name` — такой список не восстанавливает базу (проверено: из него нельзя собрать рабочие ограничения и политики доступа). Файл `tools/schema-dump.sql` отдаёт полноценные команды `create table …`, которые проверены восстановлением в пустую базу.

---

## Что делать с schema-baseline (важно)

1. Структуру даёт любой способ: `backup_schema_*.sql` из варианта 1 или текст из `tools/schema-dump.sql` (вариант 2). Положите файл в репозиторий:
   ```
   supabase/migrations/0000_baseline_schema.sql
   ```
   Шапка с датой и пояснением уже есть внутри (и у дампа, и у запроса).
2. После добавления baseline — на вопрос «а что было в базе изначально» можно ответить, не заходя в базу. Это единственная страховка структуры: в репозитории базовой схемы нет.
3. **Важно:** на бесплатном тарифе Supabase автоматических бэкапов проекта нет — только свой дамп. (Автоматические ежедневные бэкапы есть на тарифах Pro и выше.)

**Проверка (после того как файл добавлен):**
```
grep -ci "create table" supabase/migrations/0000_baseline_schema.sql   # больше 5
grep -ci "create policy" supabase/migrations/0000_baseline_schema.sql  # правила доступа на месте
grep -ci "function" supabase/migrations/0000_baseline_schema.sql       # функции на месте
```
Если политик или функций нет — файл снят неверно, восстановить базу из него не получится.

---

## Восстановление (на случай аварии) — коротко

| Что | Команда |
|---|---|
| Вся база из дампа (на пустую базу) | `psql "<СТРОКА>" -f backup_full_2026-09-29.sql` |
| Только структура (на пустую базу) | `psql "<СТРОКА>" -f supabase/migrations/0000_baseline_schema.sql` или вставить файл в SQL Editor и выполнить |
| Только данные одной таблицы | `psql "<СТРОКА>" -c "truncate public.tools" && psql "<СТРОКА>" -f backup_tools.sql` |
| Отдельная строка/запись | открыть CSV/SQL, скопировать нужный `INSERT`/строку, выполнить в SQL Editor |
| Данные из выгрузки `.ndjson` (вариант 2а) | пока нет готовой команды — восстановление напишу отдельным скриптом, когда понадобится (например, для `iva-staging`) |

> ⚠️ Восстановление "в живую" базу — операция опасная: сначала спрашиваем (у себя и у меня), потом делаем. Правило проекта: не `drop`, не `truncate` без подтверждения.

---

## Фото и файлы (это НЕ в базе)

Фото инструментов и голосовые сообщения лежат в **Storage**: бакеты `tool-photos` и `voice`.
Бэкап CSV их не сохраняет. Варианты:
1. **Скачать вручную:** Dashboard → Storage → bucket → выделить → Download (для небольших объёмов).
2. **Через CLI:** `supabase storage cp --recursive ss:///tool-photos ./backup/tool-photos` (нужен Supabase CLI и вход).
3. Раз в месяц — проверять, что количество файлов совпадает с ожидаемым.

Минимум сейчас: убедиться, что файлы в бакетах открываются (Dashboard → Storage → открыть пару картинок).

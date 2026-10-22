#!/usr/bin/env bash
# ============================================================
#  Ива — предполётная проверка (этап П0).
#  Ничего не меняет: только читает файлы и запускает тесты.
#
#  Запуск:
#    bash tools/check.sh              полная проверка (как в CI)
#    bash tools/check.sh --fast       только быстрое: секреты, релиз, статические тесты (~3 с)
#    bash tools/check.sh --only photon
#                                     только тесты, в имени которых есть «photon»
#    bash tools/check.sh --no-browser без браузерных тестов
#    bash tools/check.sh --no-stands  без стендов PostgreSQL и проверки APK
#    bash tools/check.sh --list       список имён тестов
#
#  Код выхода: 0 — всё в порядке, 1 — есть проблемы, 2 — неверный запуск.
# ============================================================
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2
ROOT="$(pwd)"
if [ -t 1 ]; then G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; N=$'\033[0m'; else G=''; R=''; Y=''; N=''; fi
PASS=0; FAIL=0; SKIP=0
ok()    { printf '  %s✔%s %s\n' "$G" "$N" "$1"; PASS=$((PASS+1)); }
bad()   { printf '  %s✘%s %s\n' "$R" "$N" "$1"; FAIL=$((FAIL+1)); }
skip()  { printf '  %s•%s %s\n' "$Y" "$N" "$1"; SKIP=$((SKIP+1)); }
head_() { printf '\n== %s ==\n' "$1"; }

STATIC_TESTS="syntax-and-release email-guard cities-sync directory-import city-pages city-priority py-compat empty-states p4-personal-data p6-commission diag-owner-readonly p5-bot-no-contacts p5-max-no-contacts p5-order-email deploy-functions-guard reset-password keystore-check-guard apk-build-guard app-test-channel publish-rights-guard qr-codes client-profile-page asset-versions dev-server"
BROWSER_TESTS="photon rental-profile new-tool-prefill order-and-fee order-consent-browser reviews-system app-banner chief-and-apk empty-states-browser"

RUN_STANDS=1
RUN_BROWSER=1
FAST=0
ONLY=""
LIST=0

usage() {
  cat <<'EOF'
Ива — проверка проекта

  bash tools/check.sh                 полная проверка (как в CI)
  bash tools/check.sh --fast          быстро: секреты, релиз, статические тесты
  bash tools/check.sh --only photon   только тесты, в имени которых есть «photon»
  bash tools/check.sh --no-browser    без браузерных тестов
  bash tools/check.sh --no-stands     без стендов PostgreSQL и проверки APK
  bash tools/check.sh --list          список имён тестов

  npm run check          то же, что полная проверка
  npm run check:fast     то же, что --fast
  npm run check:only -- photon
  npm run check:list
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --fast)       FAST=1; RUN_STANDS=0; RUN_BROWSER=0 ;;
    --no-browser) RUN_BROWSER=0 ;;
    --no-stands)  RUN_STANDS=0 ;;
    --only)
      shift
      if [ "$#" -eq 0 ]; then echo 'Нужно имя теста: bash tools/check.sh --only photon'; exit 2; fi
      ONLY="$1"
      ;;
    --only=*)     ONLY="${1#*=}" ;;
    --list)       LIST=1 ;;
    -h|--help)    usage; exit 0 ;;
    *)            echo "Неизвестная опция: $1"; usage; exit 2 ;;
  esac
  shift
done

if [ "$LIST" = "1" ]; then
  echo 'Статические тесты:'
  for t in $STATIC_TESTS; do echo "  $t"; done
  echo 'Браузерные тесты:'
  for t in $BROWSER_TESTS; do echo "  $t"; done
  exit 0
fi

matches() { case "$1" in *"$ONLY"*) return 0 ;; *) return 1 ;; esac; }
STATIC_MATCHED=0
BROWSER_MATCHED=0
if [ -n "$ONLY" ]; then
  for t in $BROWSER_TESTS; do
    if matches "$t"; then BROWSER_MATCHED=$((BROWSER_MATCHED+1)); fi
  done
fi

head_ "1. Окружение"
command -v node >/dev/null 2>&1 || { echo "Не найден node — проверка невозможна."; exit 2; }
ok "node $(node -v)"
if command -v python3 >/dev/null 2>&1; then ok "python3 $(python3 -V 2>&1 | cut -d' ' -f2)"
else skip "python3 не найден (нужен только для локального сервера в браузерных тестах)"; fi
printf '  инфо  ветка: %s, коммит: %s\n' "$(git branch --show-current 2>/dev/null || echo '?')" "$(git rev-parse --short HEAD 2>/dev/null || echo '?')"
if [ -n "$ONLY" ]; then printf '  инфо  режим: только тесты «%s»\n' "$ONLY"; fi
if [ "$FAST" = "1" ]; then printf '  инфо  режим: --fast (стенды и браузер пропущены)\n'; fi

head_ "2. Секреты не должны попадать в репозиторий"
scan() { # scan "<что ищем>" "<regex>"
  local label="$1" pattern="$2" out
  # ищем по файлам репозитория, исключая сам этот файл — иначе он находит собственные образцы поиска
  out="$(git grep -n -I -E "$pattern" -- . ':!tools/check.sh' 2>/dev/null || true)"
  if [ -n "$out" ]; then
    bad "$label"
    printf '%s\n' "$out" | cut -d: -f1,2 | sed 's/^/      /' | head -6   # только файл:строка — сам секрет в журнал не печатаем
  else
    ok "$label — чисто"
  fi
}
scan "GitHub-токены"                          'github_pat_[A-Za-z0-9_]{10,}|ghp_[A-Za-z0-9]{20,}'
scan "JWT и service-ключи Supabase"           'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}|sb_secret_[A-Za-z0-9_-]{5,}'
scan "Приватные ключи"                        'BEGIN [A-Z ]*PRIVATE KEY'
# образец записан так, чтобы сам пароль не лежал в публичном репозитории одной строкой (старый ключ №5 заменён новым 05.10.2026 — см. docs/КЛЮЧ-ПОДПИСИ.md)
scan "Утёкший пароль keystore (старый, скомпрометирован)"   'prokat20[2]6'
# ключ подписи, записанный текстом (base64 файла .p12 начинается с MII…) — не должен попасть в репозиторий
scan "Ключ подписи текстом (base64 .p12)"     'MII[A-Za-z0-9+/]{250}'
if git ls-files | grep -Eq '(^|/)\.env$|(^|/)\.env\.|\.p12$|\.jks$|\.keystore$|\.pem$'; then
  bad "В репозитории есть файлы секретов:"
  git ls-files | grep -E '(^|/)\.env$|(^|/)\.env\.|\.p12$|\.jks$|\.keystore$|\.pem$' | sed 's/^/      /'
else
  ok "Файлы секретов (.env/.p12/.jks/.keystore/.pem) в репозиторий не попали"
fi

head_ "2.1. PowerShell-скрипты читаются на Windows"
nobom=0
for f in tools/*.ps1; do
  bom="$(head -c 3 "$f" | od -An -tx1 | tr -d ' \n')"
  if [ "$bom" = "efbbbf" ]; then
    ok "$(basename "$f") — метка UTF-8 на месте"
  else
    bad "$(basename "$f") — НЕТ метки UTF-8: на Windows PowerShell скрипт сломается на русских словах"
    nobom=$((nobom+1))
  fi
done
[ "$nobom" -gt 0 ] && printf '%s\n' "      (лечение: добавить в начало файла 3 байта EF BB BF — метку UTF-8)" || true

head_ "3. Релиз приложения (app-update.json ↔ APK ↔ эталонный хеш)"
if node tools/check-release.js; then ok "релиз приложения согласован"; else bad "см. замечания выше"; fi

head_ "4. Статические тесты (без браузера)"
for t in $STATIC_TESTS; do
  if [ -n "$ONLY" ] && ! matches "$t"; then continue; fi
  STATIC_MATCHED=$((STATIC_MATCHED+1))
  if node "tests/$t.cjs" >"/tmp/iva-test-$t.log" 2>&1; then ok "tests/$t.cjs"
  else bad "tests/$t.cjs"; tail -6 "/tmp/iva-test-$t.log" | sed 's/^/      /'; fi
done
if [ -n "$ONLY" ] && [ "$STATIC_MATCHED" = "0" ]; then
  skip "нет статических тестов, совпадающих с «$ONLY»"
fi

if [ "$RUN_STANDS" = "1" ] && [ -z "$ONLY" ]; then
head_ "4.1. Стенд таблицы релизов app_releases (локальный PostgreSQL)"
PG_PY="${PG_PY:-python3}"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/app-releases-stand.py >/tmp/iva-test-rel-stand.log 2>&1; then ok "app_releases: подтверждает только eclips.ru@mail.ru (11 случаев на PostgreSQL)"
  else bad "стенд app_releases"; tail -8 /tmp/iva-test-rel-stand.log | sed 's/^/      /'; fi
else
  skip "стенд app_releases: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.2. Стенд миграций бота (локальный PostgreSQL)"
bash tools/tg-migrations-stand.sh >/tmp/iva-test-tg-stand.log 2>&1
rc=$?
if [ "$rc" = "2" ] && "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  # системной базы нет — поднимаем одноразовый PostgreSQL из pgserver (PyPI) и гоняем стенд на нём
  "$PG_PY" tools/tg-migrations-stand-pgserver.py >/tmp/iva-test-tg-stand.log 2>&1
  rc=$?
fi
if [ "$rc" = "0" ]; then
  ok "стенд бота: миграции проверены на живом PostgreSQL"
elif [ "$rc" = "2" ]; then
  skip "$(tail -1 /tmp/iva-test-tg-stand.log)"
else
  bad "стенд бота"; tail -20 /tmp/iva-test-tg-stand.log | sed 's/^/      /'
fi

head_ "4.3. Передача пакета одним файлом (круговой тест)"
bash tools/bundle-roundtrip.sh >/tmp/iva-test-bundle.log 2>&1
if [ "$?" = "0" ]; then
  ok "$(tail -1 /tmp/iva-test-bundle.log)"
else
  bad "канал «пакет одним файлом»"; tail -20 /tmp/iva-test-bundle.log | sed 's/^/      /'
fi

head_ "4.4. Проверка APK без Java (подпись, дайджесты записей)"
# В песочнице агента нет ни Java, ни apksigner, поэтому файл приложения проверяется
# инструментом tools/apk-verify.py: сертификат = ключ №6, подпись v2 подтверждается
# открытым ключом сертификата, дайджесты всех записей из MANIFEST.MF сверяются с файлом.
if "$PG_PY" -c "import androguard, cryptography" >/dev/null 2>&1; then
  APK_TEST="$("$PG_PY" -c "import json;print(json.load(open('app-test.json'))['apk'].split('/')[-1])" 2>/dev/null)"
  if [ -n "$APK_TEST" ] && [ -f "$APK_TEST" ]; then
    if "$PG_PY" tools/apk-verify.py "$APK_TEST" >/tmp/iva-apk-verify.log 2>&1; then
      ok "$APK_TEST: подпись ключом №6 подтверждена, дайджесты записей сошлись"
    else
      bad "$APK_TEST: проверка не прошла"; grep -E '✘|Итог' /tmp/iva-apk-verify.log | head -6 | sed 's/^/      /'
    fi
  else
    skip "APK из app-test.json ($APK_TEST) не найден в корне — проверка APK пропущена"
  fi
else
  skip "нет androguard/cryptography — проверка APK пропущена (pip install androguard cryptography)"
fi

head_ "4.5. Стенд прав на публикацию объявления (локальный PostgreSQL)"
# Зачем: админ eclipsik.ru@mail.ru получал «не опубликовано: нет прав на публикацию (42501)»,
# хотя по базе он активный админ. Стенд поднимает боевую схему «до», воспроизводит отказ,
# применяет миграцию 20261006_publish_rights_fix.sql и проверяет, что отказ ушёл,
# а защита осталась (чужой, аноним, неактивный админ, публикация в чужую почту — отказ).
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/publish-rights-stand.py >/tmp/iva-test-publish-rights.log 2>&1; then
    ok "публикация админа: $(grep -c '✔' /tmp/iva-test-publish-rights.log) проверок на PostgreSQL (4 причины отказа 42501 закрыты, защита цела)"
  else
    bad "стенд прав на публикацию"; grep -E '✘|ИТОГ' /tmp/iva-test-publish-rights.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд прав на публикацию: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.6. Скачивание приложения у всех: файл выпущенной версии на месте"
# Выпущенная версия живёт строкой в базе (app_releases) и ссылается на файл в корне сайта.
# Если файл убрать, у всех пользователей ломается «Скачать APK». Код 2 = проверить не удалось (нет сети).
node tests/released-apk-present.cjs >/tmp/iva-test-released-apk.log 2>&1
rc=$?
if [ "$rc" = "0" ]; then ok "$(tail -1 /tmp/iva-test-released-apk.log)"
elif [ "$rc" = "2" ]; then skip "$(tail -1 /tmp/iva-test-released-apk.log)"
else bad "файл выпущенной версии пропал из корня"; tail -6 /tmp/iva-test-released-apk.log | sed 's/^/      /'; fi
else
head_ "4.1–4.6. Стенды PostgreSQL и проверка APK"
if [ "$FAST" = "1" ]; then skip "пропущены: режим --fast"
elif [ -n "$ONLY" ]; then skip "пропущены: режим --only «$ONLY»"
else skip "пропущены: режим --no-stands"; fi
fi

if [ "$RUN_BROWSER" = "1" ]; then
head_ "5. Браузерные тесты (Playwright + локальный сервер)"
# Три разных «нет»: нет модуля, модуль есть без браузера, нет python3 для сервера.
# Во всех трёх случаях тесты честно пропускаются — окружение не должно
# превращаться в «ошибки сайта» в итоговой строке.
BROWSER_PROBE="const fs=require('fs');const {chromium}=require('playwright');if(!fs.existsSync(chromium.executablePath()))process.exit(1)"
BROWSER_RUN="$BROWSER_TESTS"
if [ -n "$ONLY" ]; then
  BROWSER_RUN=""
  for t in $BROWSER_TESTS; do
    if matches "$t"; then BROWSER_RUN="$BROWSER_RUN $t"; fi
  done
  BROWSER_RUN="${BROWSER_RUN# }"
fi
if [ -z "$BROWSER_RUN" ]; then
  skip "нет браузерных тестов, совпадающих с «$ONLY»"
elif ! node -e "require.resolve('playwright')" >/dev/null 2>&1; then
  skip "Playwright не установлен. Установить: npm install && npx playwright install chromium"
elif ! node -e "$BROWSER_PROBE" >/dev/null 2>&1; then
  skip "Playwright есть, но Chromium не скачан — браузерные тесты пропущены. Установить: npx playwright install chromium"
elif ! command -v python3 >/dev/null 2>&1; then
  skip "python3 не найден — браузерные тесты пропущены"
else
  PORT=8000
  python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
  SERVER_PID=$!
  trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
  READY=0
  for _ in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then READY=1; break; fi
    sleep 0.3
  done
  if [ "$READY" = "1" ]; then
    ok "локальный сервер поднят: http://127.0.0.1:$PORT"
    for t in $BROWSER_RUN; do
      if SITE_BASE_URL="http://127.0.0.1:$PORT" node "tests/$t.cjs" >"/tmp/iva-test-$t.log" 2>&1; then ok "tests/$t.cjs"
      else bad "tests/$t.cjs"; tail -6 "/tmp/iva-test-$t.log" | sed 's/^/      /'; fi
    done
  else
    bad "не удалось поднять локальный сервер на :$PORT"
    kill "$SERVER_PID" 2>/dev/null || true
  fi
fi
else
head_ "5. Браузерные тесты (Playwright + локальный сервер)"
skip "пропущены: режим --fast / --no-browser (их выполнит CI)"
fi

if [ -n "$ONLY" ] && [ "$STATIC_MATCHED" = "0" ] && [ "$BROWSER_MATCHED" = "0" ]; then
  bad "нет тестов, совпадающих с «$ONLY» — список: bash tools/check.sh --list"
fi

head_ "Итог"
printf '  пройдено: %s, ошибок: %s, пропущено: %s\n' "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -eq 0 ]; then echo "  Можно продолжать."; exit 0; else echo "  Сначала исправляем замечания выше."; exit 1; fi

#!/usr/bin/env bash
# ============================================================
#  Ива — предполётная проверка (этап П0).
#  Ничего не меняет: только читает файлы и запускает тесты.
#  Запуск:  bash tools/check.sh     (Windows: Git Bash)   или   npm run check
#  Код выхода: 0 — всё в порядке, 1 — есть проблемы.
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

head_ "1. Окружение"
command -v node >/dev/null 2>&1 || { echo "Не найден node — проверка невозможна."; exit 2; }
ok "node $(node -v)"
if command -v python3 >/dev/null 2>&1; then ok "python3 $(python3 -V 2>&1 | cut -d' ' -f2)"
else skip "python3 не найден (нужен только для локального сервера в браузерных тестах)"; fi
printf '  инфо  ветка: %s, коммит: %s\n' "$(git branch --show-current 2>/dev/null || echo '?')" "$(git rev-parse --short HEAD 2>/dev/null || echo '?')"

head_ "2. Секреты не должны попадать в репозиторий"
scan() { # scan "<что ищем>" "<regex>"
  local label="$1" pattern="$2" out
  # ищем по файлам репозитория, исключая сам этот файл — иначе он находит собственные образцы поиска
  out="$(git grep -n -I -E "$pattern" -- . ':!tools/check.sh' 2>/dev/null || true)"
  if [ -n "$out" ]; then
    bad "$label"
    printf '%s\n' "$out" | sed 's/^/      /' | head -6
  else
    ok "$label — чисто"
  fi
}
scan "GitHub-токены"                          'github_pat_[A-Za-z0-9_]{10,}|ghp_[A-Za-z0-9]{20,}'
scan "JWT и service-ключи Supabase"           'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}|sb_secret_[A-Za-z0-9_-]{5,}'
scan "Приватные ключи"                        'BEGIN [A-Z ]*PRIVATE KEY'
scan "Утёкший пароль keystore (prokat2026)"   'prokat2026'
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
for t in syntax-and-release email-guard cities-sync directory-import city-pages city-priority py-compat empty-states p4-personal-data p6-commission diag-owner-readonly p5-bot-no-contacts p5-max-no-contacts p5-order-email; do
  if node "tests/$t.cjs" >"/tmp/iva-test-$t.log" 2>&1; then ok "tests/$t.cjs"
  else bad "tests/$t.cjs"; tail -6 "/tmp/iva-test-$t.log" | sed 's/^/      /'; fi
done

head_ "5. Браузерные тесты (Playwright + локальный сервер)"
# Три разных «нет»: нет модуля, модуль есть без браузера, нет python3 для сервера.
# Во всех трёх случаях тесты честно пропускаются — окружение не должно
# превращаться в «ошибки сайта» в итоговой строке.
BROWSER_PROBE="const fs=require('fs');const {chromium}=require('playwright');if(!fs.existsSync(chromium.executablePath()))process.exit(1)"
if ! node -e "require.resolve('playwright')" >/dev/null 2>&1; then
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
    for t in photon rental-profile new-tool-prefill order-and-fee order-consent-browser reviews-system app-banner chief-and-apk empty-states-browser; do
      if SITE_BASE_URL="http://127.0.0.1:$PORT" node "tests/$t.cjs" >"/tmp/iva-test-$t.log" 2>&1; then ok "tests/$t.cjs"
      else bad "tests/$t.cjs"; tail -6 "/tmp/iva-test-$t.log" | sed 's/^/      /'; fi
    done
  else
    bad "не удалось поднять локальный сервер на :$PORT"
    kill "$SERVER_PID" 2>/dev/null || true
  fi
fi

head_ "Итог"
printf '  пройдено: %s, ошибок: %s, пропущено: %s\n' "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -eq 0 ]; then echo "  Можно продолжать."; exit 0; else echo "  Сначала исправляем замечания выше."; exit 1; fi

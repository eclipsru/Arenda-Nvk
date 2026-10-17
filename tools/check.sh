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
for t in syntax-and-release chat-auto-send-guard email-guard cities-sync directory-import city-pages city-priority py-compat empty-states p4-personal-data p6-commission diag-owner-readonly p5-bot-no-contacts p5-max-no-contacts p5-order-email deploy-functions-guard iva-push reset-password keystore-check-guard apk-build-guard app-test-channel publish-rights-guard qr-codes client-profile-page kind-guess model-search model-stage4 spec-filter; do
  if node "tests/$t.cjs" >"/tmp/iva-test-$t.log" 2>&1; then ok "tests/$t.cjs"
  else bad "tests/$t.cjs"; tail -6 "/tmp/iva-test-$t.log" | sed 's/^/      /'; fi
done

head_ "4.1. Стенд таблицы релизов app_releases (локальный PostgreSQL)"
PG_PY="${PG_PY:-python3}"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/app-releases-stand.py >/tmp/iva-test-rel-stand.log 2>&1; then ok "app_releases: подтверждает eclips.ru@mail.ru, после 20261022 — и eclipsik.ru@mail.ru (20 случаев на PostgreSQL)"
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

head_ "4.5.1. Стенд напоминания об окончании аренды (локальный PostgreSQL)"
# 10.76: время выдачи ставит сама база при «Выдал» (подделать нельзя), клиенту — только свои выданные инструменты.
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/rent-reminder-stand.py >/tmp/iva-test-rent-stand.log 2>&1; then
    ok "напоминание об аренде: $(grep -c '✔' /tmp/iva-test-rent-stand.log) проверок SQL на PostgreSQL"
  else
    bad "стенд напоминания об аренде"; grep -E '✘|ИТОГ' /tmp/iva-test-rent-stand.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд напоминания об аренде: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.2. Стенд режима окончания аренды (локальный PostgreSQL)"
# 10.77: настройки «Аренда» меняет только админ проката и только свои; клиент и админ получают выданные инструменты
# с текущими настройками проката; гостю недоступно; SQL идёт и поверх 10.76, и без него.
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/rent-mode-stand.py >/tmp/iva-test-rent-mode.log 2>&1; then
    ok "режим окончания аренды: $(grep -c '✔' /tmp/iva-test-rent-mode.log) проверок SQL на PostgreSQL"
  else
    bad "стенд режима окончания аренды"; grep -E '✘|ИТОГ' /tmp/iva-test-rent-mode.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд режима окончания аренды: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.3. Стенд нового дерева категорий и «Видов» (локальный PostgreSQL)"
# 10.79: 15 категорий, «Другое» в каждой, старые ключи и id на месте, виды согласованы с подкатегориями, вид у
# объявления — только известный, виды меняет только главный админ; просмотр переноса = сам перенос; повтор безопасен.
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/category-tree-stand.py >/tmp/iva-test-cat-tree.log 2>&1; then
    ok "дерево категорий и виды: $(grep -c '✔' /tmp/iva-test-cat-tree.log) проверок SQL на PostgreSQL"
  else
    bad "стенд дерева категорий"; grep -E '✘|ИТОГ|Error|ERROR' /tmp/iva-test-cat-tree.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд дерева категорий: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.4. Стенд слов автоподбора вида (локальный PostgreSQL)"
# 10.81: iva_kinds.words для 159 видов, свой вид не трогается, гость читает key+words, повтор и откат безопасны,
# подбор по словам из базы совпадает с общими случаями tests/kind-guess-cases.txt.
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/kind-words-stand.py >/tmp/iva-test-kind-words.log 2>&1; then
    ok "слова автоподбора: $(grep -c '✔' /tmp/iva-test-kind-words.log) проверок SQL на PostgreSQL"
  else
    bad "стенд слов автоподбора"; grep -E '✘|ИТОГ|Error|ERROR' /tmp/iva-test-kind-words.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд слов автоподбора: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.5. Справочник моделей: данные и SQL"
# 10.82: supabase/models/*.txt проверяются (виды, поля, варианты, числа, бренды, повторы), и SQL в репозитории
# совпадает с данными (собран tools/models-gen.py). Затем стенд на PostgreSQL: права, объявления, повтор, откат.
if command -v python3 >/dev/null 2>&1; then
  if python3 tools/models-gen.py --check >/tmp/iva-test-models-gen.log 2>&1; then ok "справочник моделей: $(tail -1 /tmp/iva-test-models-gen.log | sed 's/^✔ //')"
  else bad "справочник моделей"; head -12 /tmp/iva-test-models-gen.log | sed 's/^/      /'; fi
else
  skip "справочник моделей: python3 не найден"
fi
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/models-stand.py >/tmp/iva-test-models-stand.log 2>&1; then
    ok "справочник моделей: $(grep -c '✔' /tmp/iva-test-models-stand.log) проверок SQL на PostgreSQL"
  else
    bad "стенд справочника моделей"; grep -E '✘|ИТОГ|Error|ERROR' /tmp/iva-test-models-stand.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд справочника моделей: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

# 10.84: этап 4 — комплектация (supabase/models/kit.txt совпадает с SQL, собран tools/kit-gen.py), предложения моделей
# с одобрением главного админа; стенд на PostgreSQL: права, триггер, одобрение, отказ, повтор, откат.
if command -v python3 >/dev/null 2>&1; then
  if python3 tools/kit-gen.py --check >/tmp/iva-test-kit-gen.log 2>&1; then ok "$(tail -1 /tmp/iva-test-kit-gen.log | sed 's/^✔ //')"
  else bad "комплектация"; head -12 /tmp/iva-test-kit-gen.log | sed 's/^/      /'; fi
else
  skip "комплектация: python3 не найден"
fi
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/models-stage4-stand.py >/tmp/iva-test-models-s4.log 2>&1; then
    ok "предложения моделей и комплектация: $(grep -c '✔' /tmp/iva-test-models-s4.log) проверок SQL на PostgreSQL"
  else
    bad "стенд этапа 4 справочника"; grep -E '✘|ИТОГ|Error|ERROR' /tmp/iva-test-models-s4.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд этапа 4 справочника: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi
# 10.86: «Питание» — Сеть 220 В / Аккумулятор / Бензин / Дизель и редкие варианты только там, где они бывают
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/power-src-stand.py >/tmp/iva-test-power-src.log 2>&1; then
    ok "«Питание» (20261019_power_src.sql): $(grep -c '✔' /tmp/iva-test-power-src.log) проверок SQL на PostgreSQL"
  else
    bad "стенд «Питания»"; grep -E '✘|ИТОГ|Error|ERROR' /tmp/iva-test-power-src.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Питания»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.6. Стенд «Цензуры» (локальный PostgreSQL)"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/censor-stand.py >/tmp/iva-test-censor.log 2>&1; then
    ok "цензура: $(grep -c '✔' /tmp/iva-test-censor.log) проверок SQL на PostgreSQL (общие случаи — как в приложении)"
  else
    bad "стенд «Цензуры»"; grep -E '✘|Error|ERROR' /tmp/iva-test-censor.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Цензуры»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.7. Стенд «Пункта выдачи» (локальный PostgreSQL)"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/pickup-stand.py >/tmp/iva-test-pickup.log 2>&1; then
    ok "пункт выдачи: $(grep -c '✔' /tmp/iva-test-pickup.log) проверок SQL на PostgreSQL (права, график, связь со «Сроком аренды»)"
  else
    bad "стенд «Пункта выдачи»"; grep -E '✘|Error|ERROR' /tmp/iva-test-pickup.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Пункта выдачи»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.8. Кнопки «рамкой» в ресурсах APK (axml_outline.py на ресурсах 10.14)"
rm -rf /tmp/iva-outline && mkdir -p /tmp/iva-outline
if (cd /tmp/iva-outline && unzip -q "$OLDPWD/ProkatInstrumenta-10.14.apk" 'res/*' resources.arsc) \
   && python3 android/patches/axml_outline.py /tmp/iva-outline >/tmp/iva-test-outline.log 2>&1 \
   && ! python3 android/patches/axml_outline.py /tmp/iva-outline >/dev/null 2>&1; then
  ok "рамкой: 7 фонов переделаны, белый текст на них → оранжевый (13 мест в каждой разметке); повторный запуск останавливается"
else
  bad "axml_outline.py"; tail -4 /tmp/iva-test-outline.log | sed 's/^/      /'
fi

head_ "4.5.9. Свой звук уведомлений: провайдер в манифесте 10.14 (axml_sound.py после остальных) и файл звука"
rm -rf /tmp/iva-sound && mkdir -p /tmp/iva-sound
if unzip -q -o ProkatInstrumenta-10.14.apk AndroidManifest.xml -d /tmp/iva-sound \
   && python3 android/patches/axml_service.py /tmp/iva-sound/AndroidManifest.xml >/tmp/iva-test-sound.log 2>&1 \
   && python3 android/patches/axml_push.py /tmp/iva-sound/AndroidManifest.xml >>/tmp/iva-test-sound.log 2>&1 \
   && python3 android/patches/axml_install.py /tmp/iva-sound/AndroidManifest.xml >>/tmp/iva-test-sound.log 2>&1 \
   && python3 android/patches/axml_sound.py /tmp/iva-sound/AndroidManifest.xml >>/tmp/iva-test-sound.log 2>&1 \
   && python3 android/patches/axml_sound.py /tmp/iva-sound/AndroidManifest.xml 2>&1 | grep -q 'уже есть' \
   && python3 - /tmp/iva-sound/AndroidManifest.xml android/sounds/iva_notify.mp3 <<'PY' >>/tmp/iva-test-sound.log 2>&1
import struct, sys
sys.path.insert(0, 'android/patches')
from axml_permissions import read_pool
raw = open(sys.argv[1], 'rb').read()
ps = struct.unpack_from('<I', raw, 12)[0]
st = read_pool(raw[8:8 + ps])[0]
rs = struct.unpack_from('<I', raw, 8 + ps + 4)[0]
ids = struct.unpack_from('<%dI' % ((rs - 8) // 4), raw, 8 + ps + 8)
assert st[ids.index(0x01010018)] == 'authorities' and st[ids.index(0x01010003)] == 'name' and st[ids.index(0x01010010)] == 'exported'
assert 'ru.prokatnvsk.app.IvaSoundPatch$Files' in st and 'ru.prokatnvsk.app.ivasound' in st and st.count('provider') == 1
f = open(sys.argv[2], 'rb').read()
assert f[:3] != b'ID3' and f[0] == 0xFF and (f[1] & 0xE0) == 0xE0 and 1000 < len(f) <= 1024 * 1024
print('ok')
PY
then
  ok "звук: провайдер IvaSoundPatch\$Files (ru.prokatnvsk.app.ivasound) в манифесте, атрибуты на своих id; повторный запуск ничего не меняет; MP3 без тегов"
else
  bad "axml_sound.py / android/sounds/iva_notify.mp3"; tail -4 /tmp/iva-test-sound.log | sed 's/^/      /'
fi

head_ "4.6. Скачивание приложения у всех: файл выпущенной версии на месте"
# Выпущенная версия живёт строкой в базе (app_releases) и ссылается на файл в корне сайта.
# Если файл убрать, у всех пользователей ломается «Скачать APK». Код 2 = проверить не удалось (нет сети).
node tests/released-apk-present.cjs >/tmp/iva-test-released-apk.log 2>&1
rc=$?
if [ "$rc" = "0" ]; then ok "$(tail -1 /tmp/iva-test-released-apk.log)"
elif [ "$rc" = "2" ]; then skip "$(tail -1 /tmp/iva-test-released-apk.log)"
else bad "файл выпущенной версии пропал из корня"; tail -6 /tmp/iva-test-released-apk.log | sed 's/^/      /'; fi

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
    for t in photon rental-profile new-tool-prefill order-and-fee order-consent-browser reviews-system app-banner chief-and-apk empty-states-browser kind-guess-browser model-form-browser model-stage4-browser spec-filter-browser power-src-browser; do
      if SITE_BASE_URL="http://127.0.0.1:$PORT" node "tests/$t.cjs" >"/tmp/iva-test-$t.log" 2>&1; then ok "tests/$t.cjs"
      else bad "tests/$t.cjs"; tail -6 "/tmp/iva-test-$t.log" | sed 's/^/      /'; fi
    done
  else
    bad "не удалось поднять локальный сервер на :$PORT"
    kill "$SERVER_PID" 2>/dev/null || true
  fi
fi

head_ "4.5.10. Стенд «Инструмент ↔ пункт выдачи» (локальный PostgreSQL, SQL 10.101)"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/tool-points-stand.py >/tmp/iva-test-tool-points.log 2>&1; then
    ok "инструмент ↔ пункт: $(grep -c '✔' /tmp/iva-test-tool-points.log) проверок SQL на PostgreSQL (переход данных, свой пункт, адрес из пункта, удаление, карточка, откат)"
  else
    bad "стенд «Инструмент ↔ пункт выдачи»"; grep -E '✘|Error|ERROR' /tmp/iva-test-tool-points.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Инструмент ↔ пункт выдачи»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.11. Стенд «Заявки по пунктам выдачи» (локальный PostgreSQL, SQL 10.102)"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/order-points-stand.py >/tmp/iva-test-order-points.log 2>&1; then
    ok "заявки по пунктам: $(grep -c '✔' /tmp/iva-test-order-points.log) проверок SQL на PostgreSQL (деление по пунктам, сайт как раньше, комиссия по частям, таймер, клиенту — пункты, откат)"
  else
    bad "стенд «Заявки по пунктам выдачи»"; grep -E '✘|Error|ERROR' /tmp/iva-test-order-points.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Заявки по пунктам выдачи»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.12. Стенд «Сотрудники пунктов выдачи» (локальный PostgreSQL, SQL 10.103)"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/staff-stand.py >/tmp/iva-test-staff.log 2>&1; then
    ok "сотрудники: $(grep -c '✔' /tmp/iva-test-staff.log) проверок SQL на PostgreSQL (приглашение, права, кто что видит, «Принять / Отказаться», убрать, удаление пункта, откат)"
  else
    bad "стенд «Сотрудники пунктов выдачи»"; grep -E '✘|Error|ERROR' /tmp/iva-test-staff.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Сотрудники пунктов выдачи»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.13. Стенд «Заявки пункта у сотрудника» (локальный PostgreSQL, SQL 10.104)"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/staff-orders-stand.py >/tmp/iva-test-staff-orders.log 2>&1; then
    ok "заявки пункта: $(grep -c '✔' /tmp/iva-test-staff-orders.log) проверок SQL на PostgreSQL (только свои пункты, без денег, «Выдал / Вернули», самому себе, журнал, доступ пропадает, откат)"
  else
    bad "стенд «Заявки пункта у сотрудника»"; grep -E '✘|Error|ERROR' /tmp/iva-test-staff-orders.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Заявки пункта у сотрудника»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "4.5.14. Стенд «Инструменты пункта у сотрудника» (локальный PostgreSQL, SQL 10.105)"
if "$PG_PY" -c "import pgserver" >/dev/null 2>&1; then
  if "$PG_PY" tools/staff-tools-stand.py >/tmp/iva-test-staff-tools.log 2>&1; then
    ok "инструменты пункта: $(grep -c '✔' /tmp/iva-test-staff-tools.log) проверок SQL на PostgreSQL (только свои пункты, владелец — прокат, одобрение, «сразу», фото, доступ пропадает, откат)"
  else
    bad "стенд «Инструменты пункта у сотрудника»"; grep -E '✘|Error|ERROR' /tmp/iva-test-staff-tools.log | head -8 | sed 's/^/      /'
  fi
else
  skip "стенд «Инструменты пункта у сотрудника»: нет pgserver (PG_PY=<python с pgserver> bash tools/check.sh)"
fi

head_ "Итог"
printf '  пройдено: %s, ошибок: %s, пропущено: %s\n' "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -eq 0 ]; then echo "  Можно продолжать."; exit 0; else echo "  Сначала исправляем замечания выше."; exit 1; fi

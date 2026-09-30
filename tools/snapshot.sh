#!/usr/bin/env bash
# ============================================================
#  Ива — снимок состояния проекта. Ничего не публикует и не коммитит.
#  Складывает в .snapshots/ (папка в .gitignore):
#    state-<дата-время>.txt   — ветка, коммит, статус, веса файлов, app-update.json
#    apk-<дата-время>.sha256  — хеши всех APK
#    app-update-<дата-время>.json — копия app-update.json
#  Запуск:      bash tools/snapshot.sh
#  С тегом:     bash tools/snapshot.sh --tag p0-done   (тег создаётся только локально)
# ============================================================
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2
TS="$(date +%Y-%m-%d_%H-%M-%S)"
OUT=".snapshots"
mkdir -p "$OUT"

{
  echo "Снимок состояния — $TS"
  echo "Ветка:  $(git branch --show-current 2>/dev/null || echo '?')"
  echo "Коммит: $(git rev-parse HEAD 2>/dev/null || echo '?')"
  echo "Теги на коммите: $(git tag --points-at HEAD 2>/dev/null | paste -sd', ' -)"
  echo
  echo "== Рабочее дерево (git status --short) =="
  git status --short 2>/dev/null
  echo
  echo "== app-update.json =="
  cat app-update.json 2>/dev/null || echo "(файл не найден)"
  echo
  echo "== Вес файлов (топ-20, без .git) =="
  find . -path ./.git -prune -o -type f -print0 2>/dev/null | xargs -0 du -b 2>/dev/null | sort -rn | head -20
} > "$OUT/state-$TS.txt"

if ls ./*.apk >/dev/null 2>&1; then
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 ./*.apk > "$OUT/apk-$TS.sha256"
  else sha256sum ./*.apk > "$OUT/apk-$TS.sha256"; fi
else
  echo "APK в корне не найдены" > "$OUT/apk-$TS.sha256"
fi
cp -f app-update.json "$OUT/app-update-$TS.json" 2>/dev/null || true

echo "Снимок готов: $OUT/state-$TS.txt"
echo "Проверка: ничего не отправлено на сервер и не закоммичено."

if [ "${1:-}" = "--tag" ] && [ -n "${2:-}" ]; then
  if git tag -a "$2" -m "Точка отката: $2 ($TS)" 2>/dev/null; then
    echo "Тег '$2' создан локально. Отправить на GitHub (по согласованию): git push origin $2"
  else
    echo "Не удалось создать тег '$2' (возможно, он уже есть). Откат не затронут."
  fi
fi

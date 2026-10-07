#!/usr/bin/env bash
# ============================================================================
# Контент-машина (этап П7): контент сайта держится свежим сам.
#
# Шаги:
#   1) снять свежий справочник пунктов проката из боевой базы (только чтение);
#   2) снять счётчики объявлений по городам и пересобрать страницы городов;
#   3) прогнать проверку проекта (bash tools/check.sh);
#   4) если контент изменился — сделать ветку и Pull Request (в main не коммитим).
#
# Запуск:
#   bash tools/content-refresh.sh            # полный прогон (в GitHub Actions)
#   bash tools/content-refresh.sh --local    # освежить и пересобрать, git не трогать
#
# Секреты не нужны: чтение из базы идёт с публичным publishable-ключом, который
# и так лежит в коде сайта. Для Pull Request используется токен самого Actions.
# Откат: git revert коммита с этим файлом; страницы и сборка сайта не меняются.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

PY="${PYTHON:-python3}"
MODE="ci"
for arg in "$@"; do
  case "$arg" in
    --local) MODE="local" ;;
    *) echo "Неизвестный аргумент: $arg (можно: --local)" >&2; exit 2 ;;
  esac
done

echo "== 1/4 Снимок справочника из боевой базы (только чтение) =="
"$PY" tools/build_directory_import.py --snapshot

echo "== 2/4 Страницы городов по свежим данным =="
"$PY" tools/build_city_pages.py --refresh-listings

echo "== 3/4 Проверка проекта =="
bash tools/check.sh

if [ "$MODE" = "local" ]; then
  echo "== 4/4 Режим --local: git не трогаю. Что изменилось: =="
  git status --porcelain
  echo "Готово. Дальше — git diff и коммит вручную, если изменения нужны."
  exit 0
fi

echo "== 4/4 Если контент изменился — Pull Request =="
if [ -z "$(git status --porcelain)" ]; then
  echo "Контент свежий — изменений нет, Pull Request не нужен."
  exit 0
fi

: "${GH_TOKEN:?для создания Pull Request нужен GH_TOKEN (в Actions это secrets.GITHUB_TOKEN)}"
DAY="$(date -u +%d.%m.%Y)"
BR="content/auto-$(date -u +%Y%m%d)-${GITHUB_RUN_NUMBER:-0}"

git config user.name  "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git checkout -b "$BR"
git add tools/directory_existing.json tools/listings_by_city.json city assets/city-pages.json sitemap.xml
git commit -m "Контент-машина: снимки из базы и страницы городов обновлены $DAY"
git push -u origin "$BR"

BODY="Контент-машина освежила данные из боевой базы и пересобрала страницы городов ($DAY).

Что смотреть перед одобрением:
- \`tools/directory_existing.json\` — сколько пунктов проката в снимке (поле \`count\`) и дата снимка (\`taken_at\`);
- \`tools/listings_by_city.json\` — сколько активных объявлений по городам;
- \`city/*.html\`, \`sitemap.xml\`, \`assets/city-pages.json\` — страницы городов и их состав.

Проверка \`bash tools/check.sh\` в этом запуске пройдена. Данные не выдуманы:
на страницы попало только то, что база отдаёт публично (скрытые и отклонённые
карточки база не отдаёт). Телефоны пунктов по-прежнему не публикуются.

Откат: закрыть этот Pull Request — сайт останется на прежнем контенте."

gh pr create --base main --head "$BR" \
  --title "Контент-машина: обновление страниц городов $DAY" \
  --body "$BODY"

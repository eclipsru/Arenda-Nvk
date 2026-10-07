// Сторож «контент-машина» (этап П7, 07.10.2026).
//
// Контент-машина — это связка: tools/content-refresh.sh + .github/workflows/content-refresh.yml.
// Раз в неделю она снимает свежие данные из боевой базы (только чтение, публичный ключ),
// пересобирает страницы городов и открывает Pull Request, если контент изменился.
//
// Сторож следит, чтобы машина не стала опасной:
//   1) в main не коммитит и историю не переписывает (только ветка + Pull Request);
//   2) не просит новых секретов (читает базу публичным ключом, PR — токеном Actions);
//   3) инструменты обновления действительно только читают базу (никаких POST/PATCH/DELETE);
//   4) после пересборки прогоняется проверка проекта;
//   5) синтаксис скрипта и разметка запуска целы;
//   6) в форках не запускается.
// Запуск: node tests/content-machine.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const wf = fs.readFileSync(path.join(ROOT, '.github/workflows/content-refresh.yml'), 'utf8');
const sh = fs.readFileSync(path.join(ROOT, 'tools/content-refresh.sh'), 'utf8');

// 1. В main не коммитит, историю не переписывает
for (const bad of ['push origin main', 'push -f', 'push --force', '--force-with-lease', 'reset --hard', 'amend']) {
  assert.ok(!sh.includes(bad), `tools/content-refresh.sh: найдено «${bad}» — так делать нельзя`);
  assert.ok(!wf.includes(bad), `content-refresh.yml: найдено «${bad}» — так делать нельзя`);
}
assert.ok(/git push -u origin "\$BR"/.test(sh), 'скрипт должен пушить только в свою ветку $BR');
assert.ok(sh.includes('gh pr create --base main'), 'скрипт должен открывать Pull Request в main, а не коммитить в него');
assert.ok(sh.includes('git checkout -b "$BR"'), 'скрипт должен работать в отдельной ветке');
assert.ok(/if \[ -z "\$\(git status --porcelain\)" \]/.test(sh), 'если изменений нет — Pull Request создавать не нужно');

// 2. Никаких новых секретов
const secrets = wf.match(/secrets\.[A-Za-z0-9_]+/g) || [];
assert.deepEqual([...new Set(secrets)], ['secrets.GITHUB_TOKEN'],
  'контент-машина не должна требовать новых секретов — только токен самого Actions');
for (const f of ['.github/workflows/content-refresh.yml', 'tools/content-refresh.sh']) {
  const text = fs.readFileSync(path.join(ROOT, f), 'utf8');
  assert.ok(!/service_role|eyJhbGciOi/.test(text), `${f}: в файле не должно быть секретов`);
}

// 3. Обновление данных — только чтение
for (const tool of ['tools/build_directory_import.py', 'tools/build_city_pages.py']) {
  const code = fs.readFileSync(path.join(ROOT, tool), 'utf8');
  assert.ok(!/"(POST|PATCH|DELETE|PUT)"/.test(code), `${tool}: инструмент обновления обязан только читать базу`);
  assert.ok(!/service_role/.test(code), `${tool}: только публичный ключ`);
}
assert.ok(sh.includes('build_directory_import.py --snapshot'), 'скрипт: не обновляется снимок справочника');
assert.ok(sh.includes('build_city_pages.py --refresh-listings'), 'скрипт: не пересобираются страницы городов');

// 4. Проверка проекта после пересборки
assert.ok(sh.includes('bash tools/check.sh'), 'скрипт: после пересборки нужна проверка проекта');
assert.ok(sh.includes('set -euo pipefail'), 'скрипт: нужен строгий режим bash');

// 5. Синтаксис скрипта и разметка запуска
execSync('bash -n', { input: sh, stdio: ['pipe', 'ignore', 'pipe'] });
assert.ok(/on:\s*\n\s*schedule:/.test(wf), 'content-refresh.yml: нужен запуск по расписанию');
assert.ok(wf.includes('workflow_dispatch'), 'content-refresh.yml: нужен ручной запуск');
assert.ok(wf.includes('bash tools/content-refresh.sh'), 'content-refresh.yml: должен вызывать tools/content-refresh.sh');

// 6. В форках не запускается
assert.ok(wf.includes("if: github.repository == 'eclipsru/Arenda-Nvk'"),
  'content-refresh.yml: нужен запрет запуска в форках');

console.log('✔ content-machine: контент-машина работает веткой и Pull Request, без секретов, только чтение базы');

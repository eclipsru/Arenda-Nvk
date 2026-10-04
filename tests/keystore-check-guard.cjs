// Сторож «проверка ключа подписи» (04.10.2026): ключ приложения живёт только в секретах GitHub.
// Ловит: вывод ключа/пароля в журнал (echo, set -x, пароль в командной строке), сохранение ключа
// в артефакты или в репозиторий, запуск из Pull Request (форков), права на запись, отсутствие удаления временного файла,
// а также файлы ключей, случайно попавшие в репозиторий.
// Запуск: node tests/keystore-check-guard.cjs
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { execSync } = require('node:child_process');
try {
  const f = '.github/workflows/keystore-check.yml';
  const code = fs.readFileSync(path.join(__dirname, '..', f), 'utf8').replace(/^\s*#.*$/gm, '');
  assert.match(code, /permissions:\s*\n\s*contents: read/, `${f}: права только на чтение`);
  assert.ok(!/pull_request/.test(code), `${f}: запуск из Pull Request запрещён`);
  assert.ok(!/set\s+-[a-z]*x/.test(code), `${f}: set -x покажет секреты`);
  for (const s of ['KS_PASS', 'KS_B64', 'KS_LIST'])
    assert.ok(!new RegExp(`(echo|say|bad)[^\\n]*\\$\\{?${s}`).test(code), `${f}: ${s} выводится в журнал`);
  assert.ok(!/-storepass\s+["$]/.test(code) && !/--ks-pass\s+pass:/.test(code), `${f}: пароль в командной строке`);
  assert.match(code, /-storepass:env KS_PASS/); assert.match(code, /--ks-pass env:KS_PASS/);
  assert.match(code, /trap 'rm -f "\$KS"/, `${f}: временный файл ключа не удаляется`);
  assert.ok(!/upload-artifact|git (add|commit|push)/.test(code), `${f}: ключ не должен покидать задачу`);
  const tracked = execSync('git ls-files', { cwd: path.join(__dirname, '..') }).toString().split('\n');
  const keys = tracked.filter(p => /\.(p12|jks|keystore|pfx)$/i.test(p));
  assert.equal(keys.length, 0, 'в репозитории файл ключа: ' + keys.join(', '));
  console.log('Проверка ключа: секреты не выводятся, права — чтение, файлов ключей в репозитории 0');
} catch (e) { console.error('✘ ' + e.message); process.exit(1); }

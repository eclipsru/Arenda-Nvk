// Сторож «проверка ключа подписи» (04.10.2026): ключ приложения живёт только в секретах GitHub.
// Ловит: вывод ключа/пароля в журнал (echo, set -x, пароль в командной строке), сохранение ключа
// в артефакты или в репозиторий, запуск из Pull Request (форков), права на запись, отсутствие удаления временного файла,
// а также файлы ключей, случайно попавшие в репозиторий.
// Запуск: node tests/keystore-check-guard.cjs
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { execSync } = require('node:child_process');
const R = p => fs.readFileSync(path.join(__dirname, '..', p), 'utf8');
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
  // Решение №22 (05.10.2026): эталон проверки = отпечаток ключа №6 из памятки (или PENDING, пока владелец не создал ключ).
  const doc = R('docs/КЛЮЧ-ПОДПИСИ.md');
  const m = doc.match(/Эталон проверки: `([0-9A-F]{64}|PENDING)`/);
  assert.ok(m, 'docs/КЛЮЧ-ПОДПИСИ.md: нет строки «Эталон проверки: `…`»');
  assert.match(code, new RegExp('EXPECTED=' + m[1] + '\\n'), `${f}: эталон проверки не совпадает с памяткой (${m[1]})`);
  assert.ok(!tracked.some(p => /КЛЮЧ-ИВА|KLUCH-IVA/i.test(p)), 'папка с ключом попала в репозиторий');
  // Уборка 05.10.2026: в корне только APK, на которые есть ссылка (app-update.json), и ProkatInstrumenta.apk.
  const upd = R('app-update.json');
  const stale = tracked.filter(p => /^[^/]+\.apk$/i.test(p) && p !== 'ProkatInstrumenta.apk' && !upd.includes(p));
  assert.equal(stale.length, 0, 'старые APK без ссылки в app-update.json (уберите, чтобы не путаться): ' + stale.join(', '));
  console.log('Проверка ключа: секреты не выводятся, права — чтение, файлов ключей 0, эталон совпадает с памяткой, старых APK 0');
} catch (e) { console.error('✘ ' + e.message); process.exit(1); }

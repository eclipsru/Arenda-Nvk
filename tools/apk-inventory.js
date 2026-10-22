#!/usr/bin/env node
'use strict';
/* ============================================================
   Ива — отчёт о весе APK в корне сайта (только чтение).

   Зачем: каждая тестовая сборка остаётся файлом в репозитории.
   За месяцы накопилось ~100 APK и ~118 МБ — это замедляет клон,
   проверку в GitHub Actions и работу агента. Отчёт показывает,
   какие файлы защищены (выпущенная, текущая, кандидат, восстановление),
   а какие — старые тестовые сборки, которые можно archived.

   Запуск:
     npm run apk:report
     node tools/apk-inventory.js --json
     node tools/apk-inventory.js --top 10

   Ничего не удаляет и не меняет. Решение об архиве — за владельцем.
   ============================================================ */
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
const asJson = args.includes('--json');
const topIndex = args.indexOf('--top');
const top = topIndex >= 0 ? Number(args[topIndex + 1]) || 10 : 10;

function readJson(name) {
  try {
    return JSON.parse(fs.readFileSync(path.join(ROOT, name), 'utf8'));
  } catch {
    return null;
  }
}

function readText(name) {
  try {
    return fs.readFileSync(path.join(ROOT, name), 'utf8');
  } catch {
    return '';
  }
}

function mb(bytes) {
  return (bytes / (1024 * 1024)).toFixed(1).replace('.', ',') + ' МБ';
}

function plural(n, one, few, many) {
  const n10 = n % 10;
  const n100 = n % 100;
  if (n10 === 1 && n100 !== 11) return one;
  if (n10 >= 2 && n10 <= 4 && (n100 < 10 || n100 >= 20)) return few;
  return many;
}

function versionOf(name) {
  const m = name.match(/^ProkatInstrumenta-([\d.]+)\.apk$/);
  return m ? m[1] : null;
}

const test = readJson('app-test.json');
const release = readJson('app-update.json');
const build = readText('.github/workflows/build-apk.yml');
const candidate = (build.match(/NEW_NAME:\s*'([\d.]+)'/) || [])[1] || null;

const keepLines = readText('app-keep-apks.txt')
  .split('\n')
  .map((line) => line.trim())
  .filter((line) => line && !line.startsWith('#'));

const keep = keepLines.map((line) => {
  const name = line.split(/\s+/)[0];
  return { name, line, released: /выпущена для всех/i.test(line) };
});

const files = fs.readdirSync(ROOT)
  .filter((name) => /\.apk$/i.test(name))
  .map((name) => ({ name, size: fs.statSync(path.join(ROOT, name)).size, version: versionOf(name) }))
  .sort((a, b) => b.size - a.size);

const protectedMap = new Map();
function protect(name, why) {
  if (!name || !fs.existsSync(path.join(ROOT, name))) return;
  protectedMap.set(name, why);
}
protect('ProkatInstrumenta.apk', 'старая ссылка и восстановление 10.14');
protect('ProkatInstrumenta-10.15.apk', 'QR-код и эталон теста синтаксиса (10.15)');
if (release && release.versionName) protect(`ProkatInstrumenta-${release.versionName}.apk`, 'выпущенная для всех (app-update.json)');
if (test && test.versionName) protect(`ProkatInstrumenta-${test.versionName}.apk`, 'текущая тестовая сборка (app-test.json)');
if (candidate) protect(`ProkatInstrumenta-${candidate}.apk`, 'кандидат сборки (build-apk.yml)');
for (const k of keep) {
  if (k.released) protect(k.name, 'выпущена для всех (app-keep-apks.txt)');
}

const protectedFiles = files.filter((f) => protectedMap.has(f.name));
const oldFiles = files.filter((f) => !protectedMap.has(f.name));
const totalSize = files.reduce((n, f) => n + f.size, 0);
const oldSize = oldFiles.reduce((n, f) => n + f.size, 0);
const protectedSize = totalSize - oldSize;

if (asJson) {
  console.log(JSON.stringify({
    total: { count: files.length, bytes: totalSize },
    protected: protectedFiles.map((f) => ({ ...f, why: protectedMap.get(f.name) })),
    old: oldFiles,
    oldBytes: oldSize,
    protectedBytes: protectedSize,
    candidate,
    current: test && test.versionName,
    released: release && release.versionName,
  }, null, 2));
  process.exit(0);
}

console.log('APK в корне сайта');
console.log(`  файлов: ${files.length}, общий вес: ${mb(totalSize)}`);
console.log('');

console.log('Защищены (не трогать):');
if (!protectedFiles.length) console.log('  — нет');
for (const f of protectedFiles.sort((a, b) => b.size - a.size)) {
  console.log(`  ${f.name.padEnd(30)} ${mb(f.size).padStart(9)}  — ${protectedMap.get(f.name)}`);
}
console.log('');

console.log(`Старые тестовые сборки: ${oldFiles.length} ${plural(oldFiles.length, 'файл', 'файла', 'файлов')}, ${mb(oldSize)}`);
console.log('  Это файлы, на которые уже не ссылаются app-test.json, app-update.json,');
console.log('  build-apk.yml и строка «выпущена для всех». Они остаются в истории git,');
console.log('  поэтому архив в GitHub Releases или удаление из сайта не теряет код.');
console.log('');

if (top > 0 && oldFiles.length) {
  console.log(`Самые тяжёлые старые файлы (${Math.min(top, oldFiles.length)}):`);
  for (const f of oldFiles.slice(0, top)) {
    console.log(`  ${f.name.padEnd(30)} ${mb(f.size).padStart(9)}`);
  }
  console.log('');
}

console.log('Если оставить только защищённые файлы:');
console.log(`  останется ${protectedFiles.length} файлов (${mb(protectedSize)}), освободится ~${mb(oldSize)} рабочей копии.`);
console.log('  Ветку и историю не переписываем: старые файлы можно приложить к GitHub Release.');
console.log('');
console.log('Отчёт ничего не удаляет. Решение об архиве — за владельцем.');

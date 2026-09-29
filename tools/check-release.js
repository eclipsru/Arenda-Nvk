#!/usr/bin/env node
/* ============================================================
   Проверка согласованности релиза приложения:
   app-update.json ↔ APK-файл в корне ↔ эталонный хеш в тесте.
   Ничего не меняет. Код выхода 1 — если что-то не сходится.
   Запуск: node tools/check-release.js
   ============================================================ */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
let fail = 0;
const ok = m  => console.log('  ok    ' + m);
const bad = m => { console.log('  FAIL  ' + m); fail = 1; };
const info = m => console.log('  инфо  ' + m);

const updPath = path.join(root, 'app-update.json');
if (!fs.existsSync(updPath)) { bad('нет app-update.json'); process.exit(fail); }
let upd;
try { upd = JSON.parse(fs.readFileSync(updPath, 'utf8')); }
catch (e) { bad('app-update.json не разбирается как JSON: ' + e.message); process.exit(fail); }

const nameFromUrl = p => decodeURIComponent(String(p || '').split('/').pop());
const apkName = nameFromUrl(upd.apk || upd.apkUrl || '');
if (!apkName) bad('в app-update.json нет ссылки на APK');
else ok(`app-update.json: versionCode=${upd.versionCode}, versionName=${upd.versionName}, файл ${apkName}`);

if (upd.apk && upd.apkUrl && upd.apk !== upd.apkUrl) bad('apk и apkUrl указывают на разные файлы');
else if (upd.apk && upd.apkUrl) ok('apk и apkUrl совпадают');

const apkPath = path.join(root, apkName);
if (apkName && !fs.existsSync(apkPath)) {
  bad(`файл ${apkName} не найден в корне репозитория — ссылка в app-update.json битая`);
} else if (apkName) {
  const buf = fs.readFileSync(apkPath);
  const h = crypto.createHash('sha256').update(buf).digest('hex');
  ok(`${apkName}: ${(buf.length / 1048576).toFixed(2)} МБ, sha256 ${h.slice(0, 12)}…`);
  const testSrc = fs.readFileSync(path.join(root, 'tests', 'syntax-and-release.cjs'), 'utf8');
  const mName = testSrc.match(/const\s+apkName\s*=\s*'([^']+)'/);
  const mHash = testSrc.match(/const\s+expectedHash\s*=\s*'([0-9a-f]{64})'/);
  if (!mName || !mHash) bad('в tests/syntax-and-release.cjs не найдены apkName/expectedHash');
  else {
    if (mName[1] !== apkName) bad(`тест ждёт ${mName[1]}, а app-update.json отдаёт ${apkName}`);
    else ok('имя APK в тесте и в app-update.json совпадает');
    if (mHash[1] !== h) bad('sha256 APK не совпадает с эталоном в тесте — файл изменился без обновления теста');
    else ok('sha256 APK совпадает с эталоном в тесте');
  }
}

const apks = fs.readdirSync(root).filter(f => f.toLowerCase().endsWith('.apk'));
const old = apks.filter(f => f !== apkName);
if (old.length) info(`в корне лежат ещё APK: ${old.join(', ')} (разбираем на этапе П1)`);

process.exit(fail);

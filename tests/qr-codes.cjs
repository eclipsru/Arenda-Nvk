// Сторож «QR-коды сайта и приложения» (07.10.2026).
//
// QR-коды рисуются инструментом tools/make-qr.py локально (без внешних сервисов)
// и лежат в assets/qr-*.png. Сторож следит, чтобы коды не потерялись и не вели
// на устаревший адрес:
//   1) все файлы на месте и это валидные PNG;
//   2) адреса в инструменте совпадают с адресом живого сайта (первая строка
//      карты сайта sitemap.xml) и с файлом приложения текущей версии;
//   3) коды не зависят от сторонних сервисов (в make-qr.py нет сетевых запросов).
// Запуск: node tests/qr-codes.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const QR = ['assets/qr-site.png', 'assets/qr-app.png', 'assets/qr-app-apk.png', 'assets/qr-listovka.png'];

// 1. Файлы на месте и валидные PNG
for (const f of QR) {
  const p = path.join(ROOT, f);
  assert.ok(fs.existsSync(p), `нет файла ${f} — перерисуйте: python3 tools/make-qr.py`);
  const buf = fs.readFileSync(p);
  assert.ok(buf.length > 1000, `${f} подозрительно маленький (${buf.length} байт)`);
  assert.ok(buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
    `${f} — это не PNG`);
}

// 2. Адреса в инструменте совпадают с живым сайтом и файлом приложения
const make = fs.readFileSync(path.join(ROOT, 'tools', 'make-qr.py'), 'utf8');
const sitemap = fs.readFileSync(path.join(ROOT, 'sitemap.xml'), 'utf8');
const base = (sitemap.match(/<loc>(https:\/\/[^/<]+\/[^/<]+)/) || [])[1];
assert.ok(base, 'sitemap.xml: не нашёл базовый адрес сайта');
assert.ok(make.includes(`SITE = "${base}"`), `tools/make-qr.py: базовый адрес ${base} не совпадает со строкой SITE`);
assert.ok(make.includes('/app.html'), 'tools/make-qr.py: нет адреса страницы приложения (app.html)');
const appUpdate = JSON.parse(fs.readFileSync(path.join(ROOT, 'app-update.json'), 'utf8'));
const apkFile = (appUpdate.apk || '').split('/').pop();
assert.ok(make.includes(apkFile), `tools/make-qr.py: код приложения ведёт не на файл текущей версии (${apkFile})`);

// 3. Никаких внешних сервисов для генерации — только локальная библиотека
for (const bad of ['urlopen', 'requests.get', 'http://', 'api.qrserver', 'quickchart', 'chart.googleapis']) {
  assert.ok(!make.includes(bad), `tools/make-qr.py: найден внешний ресурс «${bad}» — коды должны рисоваться локально`);
}

console.log(`✔ qr-codes: 4 файла на месте, адреса совпадают с сайтом (${base}), генерация локальная`);

// Run before deploying: node tests/syntax-and-release.cjs
// No third-party packages required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
const apkName = 'ProkatInstrumenta-10.14.apk';
const expectedHash = '29cbc61bb4275faa010f759ca9f40bea1efaf1dc20470b9a9da215ef30316a5f';
// 05.10.2026: «Скачать» на сайте — 10.15 (новый ключ №6); apkName выше — файл самообновления (app-update.json), пока 10.14.
const downloadName = 'ProkatInstrumenta-10.15.apk';
const downloadHash = '48c8b922989c8f7fb436688edf02ef1cdc5a1ac41d54b3644e3210edd9b0d7df';
let scripts = 0;

for (const name of fs.readdirSync(root).filter(name => name.endsWith('.html'))) {
  const html = fs.readFileSync(path.join(root, name), 'utf8');
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
  for (const match of html.matchAll(re)) {
    if (/\bsrc\s*=/.test(match[1]) || /\btype\s*=\s*['"](?:application\/(?:json|ld\+json)|importmap)/i.test(match[1])) continue;
    const line = html.slice(0, match.index).split('\n').length;
    new vm.Script(match[2], { filename: `${name}:${line}` });
    scripts++;
  }
}
for (const name of fs.readdirSync(path.join(root, 'assets')).filter(name => name.endsWith('.js'))) {
  new vm.Script(fs.readFileSync(path.join(root, 'assets', name), 'utf8'), { filename: `assets/${name}` });
  scripts++;
}

const apk = fs.readFileSync(path.join(root, apkName));
const legacy = fs.readFileSync(path.join(root, 'ProkatInstrumenta.apk'));
assert.equal(crypto.createHash('sha256').update(apk).digest('hex'), expectedHash, 'Recovery APK changed unexpectedly');
assert.equal(crypto.createHash('sha256').update(legacy).digest('hex'), expectedHash, 'Legacy APK must serve the same restored build');
assert.ok(!fs.existsSync(path.join(root, 'ProkatInstrumenta.apk.idsig')), 'Do not ship an .idsig belonging to another APK');

assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(root, downloadName))).digest('hex'), downloadHash, `${downloadName} changed unexpectedly`);
for (const name of ['chief.html', 'admin.html', 'account.html', 'app.html', 'assets/app.js']) {
  const content = fs.readFileSync(path.join(root, name), 'utf8');
  assert.ok(content.includes(downloadName), `${name} must link to ${downloadName}`);
  // 05.10.2026: запасную ссылку на 10.14 владелец велел убрать — ссылок на файл самообновления на страницах быть не должно
  assert.ok(!content.includes(apkName), `${name}: запасная ссылка на ${apkName} убрана по решению владельца`);
  assert.match(content, /удалите (старое|текущ|прежнее)/, `${name}: нет предупреждения «удалите старое приложение» (новый ключ)`);
  assert.ok(!content.includes('href="https://eclipsru.github.io/Arenda-Nvk/ProkatInstrumenta.apk"'), `${name} advertises the old cached APK`);
}
const chief = fs.readFileSync(path.join(root, 'chief.html'), 'utf8');
assert.match(chief, /apk-recovery/, 'Owner dashboard needs recovery instructions');
assert.match(chief, /Если установка поверх текущего приложения отклонена/, 'Explain signature mismatch to owner');
console.log(`Syntax OK: ${scripts} scripts; APK SHA-256, ${downloadName} links OK, без запасной ссылки на ${apkName}`);

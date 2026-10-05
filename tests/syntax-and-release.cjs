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
// 05.10.2026 (решение владельца): какую версию видят все, решает кнопка «Подтвердить релиз» (таблица app_releases,
// assets/app-release.js). Пока релиз не подтверждён — у всех «старая» IVA_RELEASE_FALLBACK (сейчас 10.14 = apkName).
// Новая версия (10.15) ни на одной странице жёстко не прописана — её видит только создатель на жёлтой карточке.
const newName = 'ProkatInstrumenta-10.15.apk';
const newHash = '48c8b922989c8f7fb436688edf02ef1cdc5a1ac41d54b3644e3210edd9b0d7df';
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

assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(root, newName))).digest('hex'), newHash, `${newName} changed unexpectedly`);
const rel = fs.readFileSync(path.join(root, 'assets/app-release.js'), 'utf8');
const fb = rel.match(/IVA_RELEASE_FALLBACK = \{ versionName: '([\d.]+)', versionCode: (\d+), apk: '([^']+)'/);
assert.ok(fb, 'assets/app-release.js: нет IVA_RELEASE_FALLBACK');
assert.equal(fb[3], apkName, `старая версия для всех (IVA_RELEASE_FALLBACK) должна быть ${apkName} — проверенный файл`);
let links = 0;
for (const name of ['chief.html', 'admin.html', 'account.html', 'app.html', 'assets/app.js']) {
  const content = fs.readFileSync(path.join(root, name), 'utf8');
  assert.ok(!content.includes(newName), `${name}: новая версия ${newName} не должна быть прописана на странице — только после «Подтвердить релиз»`);
  // Каждая ссылка на APK в HTML — с data-app-dl (подставляется выпущенная версия)
  for (const m of content.matchAll(/<a\b[^>]*href="(ProkatInstrumenta-[\d.]+\.apk)"[^>]*>/g)) {
    assert.match(m[0], /data-app-dl=/, `${name}: ссылка ${m[1]} без data-app-dl — не переключится на выпущенную версию`);
    assert.equal(m[1], apkName, `${name}: ссылка по умолчанию должна вести на старую версию ${apkName}`);
    links++;
  }
  assert.ok(!content.includes('href="https://eclipsru.github.io/Arenda-Nvk/ProkatInstrumenta.apk"'), `${name} advertises the old cached APK`);
}
console.log(`Syntax OK: ${scripts} scripts; APK SHA-256 ок; ссылок на APK ${links}, все переключаемые, по умолчанию ${apkName}; ${newName} на страницах не прописана`);

// Run before deploying: node tests/syntax-and-release.cjs
// No third-party packages required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
const apkName = 'ProkatInstrumenta-10.12-clone.apk';
const expectedHash = 'a9af23756436c49d6307324de2b494c304c1e6e8e1991de135bb94ceaf3c15b7';
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

for (const name of ['chief.html', 'admin.html', 'account.html', 'app.html', 'assets/app.js']) {
  const content = fs.readFileSync(path.join(root, name), 'utf8');
  assert.ok(content.includes(apkName), `${name} must link to the cache-safe restored APK`);
  assert.ok(!content.includes('href="https://eclipsru.github.io/Arenda-Nvk/ProkatInstrumenta.apk"'), `${name} advertises the old cached APK`);
}
const chief = fs.readFileSync(path.join(root, 'chief.html'), 'utf8');
assert.match(chief, /apk-recovery/, 'Owner dashboard needs recovery instructions');
assert.match(chief, /Если установка поверх текущего приложения отклонена/, 'Explain signature mismatch to owner');
console.log(`Syntax OK: ${scripts} scripts; APK SHA-256 and ${apkName} links OK`);

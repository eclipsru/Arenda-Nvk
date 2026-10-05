// Сторож «тестовый канал приложения» (решение владельца 05.10.2026):
// жёлтая карточка тестовой сборки — только eclips.ru@mail.ru и только на телефоне, НАД зелёной карточкой «для всех».
// Проверяет код карточки на 4 случаях (чужой пользователь, компьютер, тест новее, тест не новее),
// порядок карточек в кабинетах, цвета, app-test.json и что выпущенный APK сборка не перезаписывает.
// Запуск: node tests/app-test-channel.cjs
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const ROOT = path.join(__dirname, '..'); const R = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
(async () => {
  const app = R('assets/app.js');
  const grab = re => { const m = app.match(re); assert.ok(m, 'assets/app.js: не найдено ' + re); return m[0]; };
  const fn = name => {
    const i = app.indexOf('function ' + name + '('); assert.ok(i >= 0, 'нет функции ' + name);
    let d = 0, j = app.indexOf('{', i);
    for (let k = j; k < app.length; k++) { if (app[k] === '{') d++; else if (app[k] === '}' && --d === 0) return app.slice(i, k + 1); }
  };
  assert.match(app, /const APP_TEST_EMAIL = 'eclips\.ru@mail\.ru';/, 'тестовая карточка — только для eclips.ru@mail.ru');
  const pub = grab(/const APP_PUBLIC_VERSION = \{ name: '([\d.]+)', code: (\d+) \};/);
  const [, pubName, pubCode] = pub.match(/'([\d.]+)', code: (\d+)/);
  assert.match(app, new RegExp(`const APP_DOWNLOAD_URL = 'ProkatInstrumenta-${pubName.replace('.', '\\.')}\\.apk';`), 'версия «для всех» не совпадает с файлом скачивания');
  const feed = grab(/const APP_TEST_FEED = '([^']+)';/).match(/'([^']+)'/)[1];
  assert.match(feed, /^https:\/\/raw\.githubusercontent\.com\/eclipsru\/Arenda-Nvk\/[^/]+\/.*app-test\.json$/, 'APP_TEST_FEED: не raw.githubusercontent.com/…/app-test.json');
  // app-test.json в репозитории — корректный и указывает на существующий APK
  const t = JSON.parse(R('app-test.json'));
  assert.ok(Number.isInteger(t.versionCode) && t.versionCode >= Number(pubCode), 'app-test.json: versionCode меньше версии для всех');
  const apkName = (t.apk.match(/\/(ProkatInstrumenta-[\d.]+\.apk)$/) || [])[1];
  assert.ok(apkName && fs.existsSync(path.join(ROOT, apkName)), `app-test.json: файла ${apkName} нет в репозитории`);
  assert.ok(t.apk.includes(feed.split('/')[5]), 'app-test.json: ссылка на APK не из той же ветки, что APP_TEST_FEED');

  // Поведение карточки
  const code = [fn('esc'), fn('isMobileDevice'), fn('currentUserEmail'), fn('isAppTester'), fn('mountTestApkCard'),
    pub, `const APP_TEST_FEED = '${feed}';`, `const APP_TEST_EMAIL = 'eclips.ru@mail.ru';`].join('\n');
  async function run({ email, mobile, feedJson, viaA }) {
    let fetched = 0; const slot = { innerHTML: '' };
    const ctx = {
      navigator: { userAgent: mobile ? 'Mozilla/5.0 (Linux; Android 13)' : 'Mozilla/5.0 (Windows NT 10.0)' },
      window: { innerWidth: mobile ? 375 : 1280 }, localStorage: { getItem: k => !viaA && k === 'iva_sess' ? JSON.stringify({ me: email }) : null },
      fetch: async () => { fetched++; return { ok: true, json: async () => feedJson }; }, Date, Number, String, JSON, console,
    };
    vm.createContext(ctx); vm.runInContext((viaA ? `const A = { me: '${email}' };\n` : '') + code + '\nthis.mount = mountTestApkCard;', ctx);
    ctx.mount(slot); await new Promise(r => setTimeout(r, 20));
    return { html: slot.innerHTML, fetched };
  }
  const newer = { versionCode: Number(pubCode) + 1, versionName: 'X.YY', apk: 'https://github.com/eclipsru/Arenda-Nvk/raw/b/ProkatInstrumenta-X.YY.apk', reinstall: false };
  let r = await run({ email: 'someone@mail.ru', mobile: true, feedJson: newer });
  assert.equal(r.html, '', 'чужой пользователь видит тестовую карточку'); assert.equal(r.fetched, 0);
  r = await run({ email: 'eclipsik.ru@mail.ru', mobile: true, feedJson: newer });
  assert.equal(r.html, '', 'тестовая карточка только для eclips.ru@mail.ru (не eclipsik)');
  r = await run({ email: 'eclips.ru@mail.ru', mobile: false, feedJson: newer });
  assert.equal(r.html, '', 'на компьютере тестовой карточки быть не должно');
  r = await run({ email: 'ECLIPS.RU@mail.ru', mobile: true, feedJson: newer });
  assert.match(r.html, /class="apk-test"/); assert.match(r.html, /ProkatInstrumenta-X\.YY\.apk/); assert.match(r.html, /видна только вам/);
  r = await run({ email: 'eclips.ru@mail.ru', mobile: true, feedJson: newer, viaA: true });
  assert.match(r.html, /class="apk-test"/, 'почта из объекта сессии A (const из sb.js) не распознаётся');
  r = await run({ email: 'eclips.ru@mail.ru', mobile: true, feedJson: { versionCode: Number(pubCode), versionName: pubName, apk: 'x' } });
  assert.match(r.html, /новее/); assert.ok(!/download/.test(r.html), 'когда теста новее нет — кнопки скачивания быть не должно');

  // Кабинеты: слот НАД зелёной карточкой, вызов после отрисовки
  for (const [f, card] of [['account.html', '<div class="apk-public"'], ['chief.html', '<section class="apk-recovery apk-public"']]) {
    const h = R(f); const i = h.indexOf('apk-test-slot'), j = h.indexOf(card);
    assert.ok(i > 0 && j > i, `${f}: жёлтая карточка должна стоять НАД зелёной`);
    assert.match(h, /mountTestApkCard\(\$\('apkTestSlot'\)\)/, `${f}: карточка не подключена`);
  }
  const css = R('assets/theme.css');
  assert.match(css, /\.apk-public\{[^}]*#39FF14/, 'зелёная (кислотная) подсветка карточки «для всех»');
  assert.match(css, /\.apk-test\{[^}]*#FFEA00/, 'жёлтая (кислотная) подсветка тестовой карточки');
  // Сборка: выпущенный APK не перезаписывается, app-test.json обновляется
  const wf = R('.github/workflows/build-apk.yml');
  assert.match(wf, /git cat-file -e "HEAD:\$F"[\s\S]*exit 0/, 'build-apk.yml: выпущенный APK может быть перезаписан');
  assert.match(wf, /git add -f "\$F" app-test\.json/, 'build-apk.yml: app-test.json не обновляется');
  console.log(`Тестовый канал: только eclips.ru@mail.ru на телефоне, над зелёной карточкой ${pubName}; тест ${t.versionName} (${t.versionCode}); 5 случаев поведения ок`);
})().catch(e => { console.error('✘ ' + e.message); process.exit(1); });

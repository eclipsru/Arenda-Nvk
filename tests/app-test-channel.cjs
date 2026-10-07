// Сторож «релиз приложения» (решения владельца 05.10.2026):
// - две карточки (жёлтая «новая» с «Подтвердить релиз» + зелёная «сейчас у всех») — ТОЛЬКО у eclips.ru@mail.ru на телефоне;
// - все остальные видят одну ссылку — на ВЫПУЩЕННУЮ версию (баннер 10 секунд + кабинет);
// - выпущенная = последняя строка app_releases; пока её нет — старая 10.14;
// - у создателя в кабинете статус «создатель».
// Код карточек запускается в песочнице node:vm на 8 случаях. Запуск: node tests/app-test-channel.cjs
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const ROOT = path.join(__dirname, '..'); const R = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
(async () => {
  const app = R('assets/app.js'), rel = R('assets/app-release.js');
  const fn = name => {
    const i = app.search(new RegExp('(async )?function ' + name + '\\(')); assert.ok(i >= 0, 'assets/app.js: нет функции ' + name);
    let d = 0, j = app.indexOf('{', i);
    for (let k = j; k < app.length; k++) { if (app[k] === '{') d++; else if (app[k] === '}' && --d === 0) return app.slice(i, k + 1); }
  };
  assert.match(rel, /var IVA_CREATOR_EMAIL = 'eclips\.ru@mail\.ru';/, 'создатель — только eclips.ru@mail.ru');
  const fb = rel.match(/IVA_RELEASE_FALLBACK = \{ versionName: '([\d.]+)', versionCode: (\d+), apk: '([^']+)'/);
  assert.ok(fb && fs.existsSync(path.join(ROOT, fb[3])), 'старая версия для всех: файла нет в репозитории');
  const feed = rel.match(/var IVA_TEST_FEED = '([^']+)';/)[1];
  const fm = feed.match(/^https:\/\/raw\.githubusercontent\.com\/eclipsru\/Arenda-Nvk\/(.+)\/app-test\.json$/);
  assert.ok(fm, 'IVA_TEST_FEED: нужен вид https://raw.githubusercontent.com/eclipsru/Arenda-Nvk/<ветка>/app-test.json');
  const feedBranch = fm[1];
  assert.match(feedBranch, /^arena\//, `IVA_TEST_FEED: тестовый канал должен читать ветку агента (arena/…), а не ${feedBranch}`);
  assert.notEqual(feedBranch, 'arena/01a101d5-arenda-nvk', 'IVA_TEST_FEED всё ещё указывает на старую ветку — её не удалять, но для этой сессии использовать новую');
  assert.match(rel, /\/rest\/v1\/app_releases\?select=version_code,version_name,apk_url,reinstall,notes&order=version_code\.desc&limit=1/, 'выпущенная версия берётся не из app_releases');
  const t = JSON.parse(R('app-test.json'));
  const apkName = (t.apk.match(/\/(ProkatInstrumenta-[\d.]+\.apk)$/) || [])[1];
  assert.ok(apkName && fs.existsSync(path.join(ROOT, apkName)), `app-test.json: файла ${apkName} нет в репозитории`);
  assert.ok(t.apk.includes('/raw/' + feedBranch + '/'),
    `app-test.json: ссылка на APK должна вести из той же ветки, что IVA_TEST_FEED (${feedBranch})`);
  assert.ok(t.versionCode > Number(fb[2]), 'app-test.json: новая версия должна быть новее старой');

  // ---- Поведение: app-release.js + функции карточек из app.js в песочнице ----
  const code = rel + '\n' + ['esc', 'isMobileDevice', 'currentUserEmail', 'isCreator', 'isAppTester', 'apkPublicCardHTML',
    'mountAppCards', 'mountTestApkCard', 'confirmAppRelease', 'ivaApkFile'].filter(n => n !== 'ivaApkFile').map(fn).join('\n');
  const newer = { versionCode: t.versionCode, versionName: t.versionName, apk: t.apk, reinstall: true, notes: 'n' };
  async function run({ email, mobile, released = [], feedJson = newer, post }) {
    const calls = [], store = {};
    const ctx = {
      navigator: { userAgent: mobile ? 'Mozilla/5.0 (Linux; Android 13)' : 'Mozilla/5.0 (Windows NT 10.0)' },
      window: { innerWidth: mobile ? 375 : 1280, confirm: () => true },
      localStorage: { getItem: k => k === 'iva_sess' && email ? JSON.stringify({ me: email }) : null },
      sessionStorage: { getItem: k => store[k] || null, setItem: (k, v) => { store[k] = v; } },
      fetch: async (url, opt) => {
        calls.push(String(url));
        if (/app_releases/.test(url)) return { ok: true, json: async () => released };
        if (/app-test\.json/.test(url)) return { ok: true, json: async () => feedJson };
        return { ok: false, status: 404 };
      },
      api: async (p, m, body) => { post && post.push({ p, m, body }); return null; },
      A: { me: email || '', tok: email ? 'tok' : null },
      Date, Number, String, JSON, Promise, console, setTimeout,
    };
    ctx.document = { readyState: 'complete', querySelectorAll: () => [] };
    vm.createContext(ctx);
    vm.runInContext(code + '\nthis.mountAppCards = mountAppCards; this.confirmAppRelease = confirmAppRelease;', ctx);
    const slot = { innerHTML: '', querySelector(sel) {
      if (sel === '.apk-test-slot') { const self = this; return { set innerHTML(v) { self.innerHTML = self.innerHTML.replace(/<div class="apk-test-slot">[\s\S]*?<\/div><\/div>/, '<div class="apk-test-slot">' + v + '</div>'); }, querySelector: () => null }; }
      return null; } };
    ctx.mountAppCards(slot); await new Promise(r => setTimeout(r, 30));
    return { html: slot.innerHTML, calls, ctx };
  }
  const row15 = [{ version_code: t.versionCode, version_name: t.versionName, apk_url: t.apk, reinstall: true, notes: 'Новое' }];
  const greenOnly = (r, file, who) => {
    assert.ok(!/apk-test/.test(r.html), `${who}: видит жёлтую карточку новой версии — она только у создателя на телефоне`);
    assert.equal((r.html.match(/class="apk-card apk-public"/g) || []).length, 1, `${who}: должна быть одна зелёная карточка`);
    assert.equal((r.html.match(/href="[^"]*\.apk"/g) || []).length, 1, `${who}: должна быть ровно одна ссылка на приложение`);
    assert.ok(r.html.includes(file), `${who}: ссылка не на ${file}`);
    assert.ok(!r.calls.some(u => /app-test\.json/.test(u)), `${who}: не создатель, а сайт запрашивает тестовую сборку`);
  };
  greenOnly(await run({ email: 'someone@mail.ru', mobile: true }), fb[3], 'обычный пользователь до релиза');
  greenOnly(await run({ email: '', mobile: true }), fb[3], 'гость до релиза');
  greenOnly(await run({ email: 'eclipsik.ru@mail.ru', mobile: true }), fb[3], 'eclipsik');
  greenOnly(await run({ email: 'eclips.ru@mail.ru', mobile: false }), fb[3], 'создатель на компьютере');
  greenOnly(await run({ email: 'someone@mail.ru', mobile: true, released: row15 }), t.apk, 'обычный пользователь после релиза');
  // Создатель на телефоне, релиз не подтверждён: жёлтая (новая + «Подтвердить релиз») НАД зелёной (старая)
  let r = await run({ email: 'ECLIPS.RU@mail.ru', mobile: true });
  const iy = r.html.indexOf('class="apk-test"'), ig = r.html.indexOf('apk-public');
  assert.ok(iy >= 0 && ig > iy, 'создатель: жёлтая карточка должна стоять НАД зелёной');
  assert.match(r.html, /<button class="btn apk-confirm"[^>]*id="apkConfirm">✅ Подтвердить релиз<\/button>/, 'нет кнопки «Подтвердить релиз»');
  assert.ok(r.html.includes(t.apk) && r.html.includes(fb[3]), 'создатель: должны быть ссылки на новую и на старую версию');
  assert.match(r.html, /Сейчас у всех пользователей/);
  // Создатель после релиза: новых версий нет
  r = await run({ email: 'eclips.ru@mail.ru', mobile: true, released: row15 });
  assert.match(r.html, /Новых версий нет/); assert.ok(!/apkConfirm/.test(r.html), 'когда новых версий нет — кнопки «Подтвердить релиз» быть не должно');
  // «Подтвердить релиз» пишет в app_releases новую версию
  const post = []; r = await run({ email: 'eclips.ru@mail.ru', mobile: true, post });
  const msg = { textContent: '', className: '' }, btn = { disabled: false };
  await r.ctx.confirmAppRelease(newer, btn, msg, null);
  assert.equal(post.length, 1, '«Подтвердить релиз» не отправил запись');
  assert.equal(post[0].p, '/rest/v1/app_releases'); assert.equal(post[0].m, 'POST');
  assert.equal(post[0].body.version_code, t.versionCode); assert.equal(post[0].body.apk_url, t.apk);
  assert.match(msg.textContent, /выпущена для всех/);

  // ---- Баннер: у всех одинаковый, 10 секунд, выпущенная версия ----
  assert.match(app, /const APP_BANNER_MS = 10000;/, 'баннер должен висеть 10 секунд');
  assert.ok(!/isPermanentUser|permanent/.test(fn('mountAppTopBanner') + fn('showAppTopBanner')), 'у баннера не должно быть «вечного» режима для отдельных людей');
  assert.match(fn('mountAppTopBanner'), /ivaReleased\(\)/, 'баннер должен брать выпущенную версию');
  assert.match(fn('showAppTopBanner'), /esc\(rel\.apk\)/, 'ссылка баннера — на выпущенную версию');

  // ---- Кабинеты и статус «создатель» ----
  for (const f of ['account.html', 'chief.html']) {
    const h = R(f);
    assert.match(h, /id="apkCards"/, `${f}: нет места для карточек приложения`);
    assert.match(h, /mountAppCards\(\$\('apkCards'\)\)/, `${f}: карточки не подключены`);
    assert.match(h, /isCreator\(\) \? ' · создатель'/, `${f}: у создателя должен быть статус «создатель»`);
    assert.ok(!/apk-test/.test(h), `${f}: жёлтая карточка не должна быть прописана в странице`);
  }
  for (const f of fs.readdirSync(ROOT).filter(n => n.endsWith('.html'))) {
    const h = R(f);
    if (/assets\/app\.js\?v=/.test(h)) assert.ok(h.indexOf('assets/app-release.js') >= 0 && h.indexOf('assets/app-release.js') < h.indexOf('assets/app.js?v='), `${f}: app-release.js должен подключаться перед app.js`);
  }
  const css = R('assets/theme.css');
  assert.match(css, /\.apk-public\{[^}]*#39FF14/, 'зелёная (кислотная) подсветка карточки «для всех»');
  assert.match(css, /\.apk-test\{[^}]*#FFEA00/, 'жёлтая (кислотная) подсветка карточки новой версии');
  // ---- База: только создатель может подтвердить ----
  const sql = R('supabase/migrations/20261005_app_releases.sql');
  const body = sql.split('\n').filter(l => !l.trim().startsWith('--')).join('\n');
  assert.match(body, /WITH CHECK \(lower\(coalesce\(auth\.jwt\(\) ->> 'email', ''\)\) = 'eclips\.ru@mail\.ru'/, 'в базе подтверждать должен только eclips.ru@mail.ru');
  assert.ok(!/\b(DROP|TRUNCATE|DELETE)\b/i.test(body), 'миграция без DROP/TRUNCATE/DELETE');
  assert.ok(!/GRANT[^;]*(UPDATE|DELETE)/i.test(body), 'менять и удалять релизы через сайт нельзя');
  // ---- Сборка: выпущенный APK не перезаписывается, app-test.json обновляется ----
  const wf = R('.github/workflows/build-apk.yml');
  assert.match(wf, /git cat-file -e "HEAD:\$F"[\s\S]*exit 0/, 'build-apk.yml: выпущенный APK может быть перезаписан');
  assert.match(wf, /git add -f "\$F" app-test\.json/, 'build-apk.yml: app-test.json не обновляется');
  assert.match(wf, /branches: \['main', 'arena\/\*\*'\]/, 'build-apk.yml: сборка должна запускаться на main и arena/** без имени конкретной ветки');
  assert.match(wf, /if: startsWith\(github\.ref, 'refs\/heads\/arena\/'\)/, 'build-apk.yml: APK должен коммититься только в ветки arena/**');
  assert.ok(!/arena\/01a101d5/.test(wf), 'build-apk.yml: осталось имя старой ветки агента');
  console.log(`Релиз приложения: до подтверждения у всех ${fb[1]}; новая ${t.versionName} — только eclips.ru@mail.ru на телефоне с «Подтвердить релиз»; тестовый канал — ${feedBranch}; баннер 10 с; 8 случаев поведения ок`);
})().catch(e => { console.error('✘ ' + (e && e.message || e)); process.exit(1); });

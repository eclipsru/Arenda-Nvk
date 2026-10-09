// Сторож «тестовый канал приложения» (решение владельца 09.10.2026: тот же сайт, что и страницы, без веток).
// Цель владельца: каждая новая сборка сама появляется жёлтой карточкой в кабинете создателя после слияния PR,
// без правки имени ветки в коде. Поэтому проверяем всю цепочку:
//  1) IVA_TEST_FEED = 'app-test.json' — относительный адрес файла на том же сайте (не raw.githubusercontent, не ветка);
//  2) app-test.json: поле apk — только имя файла, и этот файл лежит в корне репозитория;
//  3) шаг сборки (build-apk.yml) пишет app-test.json тем же видом ссылки: его Python-код запускается здесь
//     на тестовых переменных, и проверяется получившийся файл (а не только текст);
//  4) жёлтая карточка и «Подтвердить релиз» (app.js) читают этот адрес, пишут в базу только имя файла
//     и не выпускают файл, которого ещё нет на сайте.
// Плюс прежние правила: две карточки — только у создателя на телефоне; остальным — одна ссылка на выпущенную версию;
// баннер 10 секунд; у создателя в кабинете статус «создатель».
// Код карточек запускается в песочнице node:vm. Запуск: node tests/app-test-channel.cjs
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process'), os = require('node:os');
const ROOT = path.join(__dirname, '..'); const R = p => fs.readFileSync(path.join(ROOT, p), 'utf8');
const APK_RE = /^ProkatInstrumenta-[\d.]+\.apk$/;
(async () => {
  const app = R('assets/app.js'), rel = R('assets/app-release.js'), wf = R('.github/workflows/build-apk.yml');
  const fn = name => {
    const i = app.search(new RegExp('(async )?function ' + name + '\\(')); assert.ok(i >= 0, 'assets/app.js: нет функции ' + name);
    let d = 0, j = app.indexOf('{', i);
    for (let k = j; k < app.length; k++) { if (app[k] === '{') d++; else if (app[k] === '}' && --d === 0) return app.slice(i, k + 1); }
  };
  assert.match(rel, /var IVA_CREATOR_EMAIL = 'eclips\.ru@mail\.ru';/, 'создатель — только eclips.ru@mail.ru');
  const fb = rel.match(/IVA_RELEASE_FALLBACK = \{ versionName: '([\d.]+)', versionCode: (\d+), apk: '([^']+)'/);
  assert.ok(fb && fs.existsSync(path.join(ROOT, fb[3])), 'старая версия для всех: файла нет в репозитории');

  // ---- 1. Адрес канала: тот же сайт, без веток ----
  const feedDecl = rel.match(/var IVA_TEST_FEED = ([^;]+);/);
  assert.ok(feedDecl, 'assets/app-release.js: нет IVA_TEST_FEED');
  assert.equal(feedDecl[1].trim(), "'app-test.json'", `IVA_TEST_FEED должен быть 'app-test.json' (файл на том же сайте), а не ${feedDecl[1].trim()}`);
  for (const f of ['assets/app-release.js', 'assets/app.js', 'app-test.json', '.github/workflows/build-apk.yml'])
    assert.ok(!/raw\.githubusercontent\.com|\/raw\/arena\//.test(R(f)), `${f}: осталась ссылка на ветку (raw.githubusercontent / raw/arena)`);
  for (const f of ['assets/app-release.js', 'assets/app.js', 'app-test.json', '.github/workflows/build-apk.yml'])
    assert.ok(!/arena\/01a/.test(R(f)), `${f}: осталось имя конкретной ветки агента — при смене сессии его не поменяли бы`);

  // ---- 2. app-test.json: имя файла, файл на месте ----
  const t = JSON.parse(R('app-test.json'));
  assert.match(String(t.apk), APK_RE, `app-test.json: поле apk — только имя файла (ProkatInstrumenta-<версия>.apk), а не «${t.apk}»`);
  assert.ok(fs.existsSync(path.join(ROOT, t.apk)), `app-test.json: файла ${t.apk} нет в корне репозитория`);
  assert.equal(t.apk, `ProkatInstrumenta-${t.versionName}.apk`, 'app-test.json: имя файла не совпадает с versionName');
  assert.ok(t.versionCode > Number(fb[2]), 'app-test.json: новая версия должна быть новее старой');

  // ---- 3. Сборка: запускаем её собственный Python-код на тестовых переменных и смотрим, что записано ----
  const name = (wf.match(/NEW_NAME: '([\d.]+)'/) || [])[1], code = (wf.match(/NEW_CODE: '(\d+)'/) || [])[1];
  assert.ok(name && code, 'build-apk.yml: нет NEW_NAME / NEW_CODE');
  assert.match(wf, /if: startsWith\(github\.ref, 'refs\/heads\/arena\/'\)/, 'build-apk.yml: шаг «Положить APK в ветку агента» — только на ветках агента (arena/…)');
  assert.match(wf, /branches: \['main', 'arena\/\*\*'\]/, 'build-apk.yml: сборка запускается на любой ветке агента (arena/**), без имени конкретной ветки');
  assert.match(wf, /git cat-file -e "HEAD:\$F"[\s\S]*exit 0/, 'build-apk.yml: выпущенный APK может быть перезаписан');
  assert.match(wf, /git add -f "\$F" app-test\.json/, 'build-apk.yml: app-test.json не коммитится вместе с APK');
  const snippet = (() => {
    const lines = wf.split('\n'), start = lines.findIndex(l => /^\s*python3 - <<'PY'\s*$/.test(l));
    assert.ok(start >= 0, 'build-apk.yml: нет шага, который пишет app-test.json');
    const body = []; for (let i = start + 1; i < lines.length && lines[i].trim() !== 'PY'; i++) body.push(lines[i]);
    const indent = Math.min(...body.filter(l => l.trim()).map(l => l.match(/^ */)[0].length));
    return body.map(l => l.slice(indent)).join('\n');
  })();
  const env = { ...process.env, GITHUB_REF: 'refs/heads/arena/test-session', GITHUB_REPOSITORY: 'eclipsru/Arenda-Nvk',
    NEW_NAME: name, NEW_CODE: code, TEST_NOTES: 'проверка', TEST_REINSTALL: 'false' };
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'iva-test-channel-'));
  let bot = null, botNote = '';
  try {
    fs.writeFileSync(path.join(tmp, 'snippet.py'), snippet, 'utf8');
    execFileSync('python3', [path.join(tmp, 'snippet.py')], { cwd: tmp, env, stdio: 'pipe' });
    bot = JSON.parse(fs.readFileSync(path.join(tmp, 'app-test.json'), 'utf8'));
    botNote = 'шаг сборки запущен, app-test.json записан';
  } catch (e) {
    if (e.code === 'ENOENT') botNote = 'python3 не найден — запуск шага сборки пропущен';
    else throw new Error('шаг сборки не записал app-test.json: ' + String(e.stderr || e.message).split('\n').slice(-3).join(' '));
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
  if (bot) {
    assert.equal(bot.apk, `ProkatInstrumenta-${name}.apk`, `сборка пишет apk «${bot.apk}», а нужно только имя файла`);
    assert.match(String(bot.apk), APK_RE);
    assert.equal(bot.versionName, name); assert.equal(bot.versionCode, Number(code));
    assert.equal(bot.reinstall, false, 'подпись та же (ключ №6) — переустанавливать не нужно');
    assert.ok(!/\/raw\//.test(JSON.stringify(bot)), 'сборка не должна писать ссылки на ветки');
  }

  // ---- 4. Жёлтая карточка и «Подтвердить релиз» (app.js) ----
  const mt = fn('mountTestApkCard'), cfn = fn('confirmAppRelease');
  assert.match(mt, /fetch\(IVA_TEST_FEED \+ '\?t=' \+ Date\.now\(\)/, 'жёлтая карточка должна читать IVA_TEST_FEED (тот же сайт)');
  assert.ok(!/GitHub/i.test(mt) && !/GitHub/i.test(cfn), 'текст карточки и подтверждения не должен упоминать GitHub');
  assert.match(cfn, /const file = ivaApkFile\(feed\.apk\)/, 'подтверждение: имя файла берём из app-test.json');
  assert.match(cfn, /method: 'HEAD'/, 'подтверждение: сначала проверяем, что файл уже на сайте');
  assert.match(cfn, /apk_url: file/, 'в app_releases пишется только имя файла');

  // ---- Поведение: app-release.js + функции карточек из app.js в песочнице ----
  const feedNow = bot || t; // то, что записала сборка (или текущий app-test.json, если python3 нет)
  const newer = { versionCode: feedNow.versionCode, versionName: feedNow.versionName, apk: feedNow.apk, reinstall: !!feedNow.reinstall, notes: feedNow.notes || '' };
  const code2 = rel + '\n' + ['esc', 'isMobileDevice', 'currentUserEmail', 'isCreator', 'isAppTester', 'apkPublicCardHTML',
    'mountAppCards', 'mountTestApkCard', 'confirmAppRelease'].map(fn).join('\n');
  async function run({ email, mobile, released = [], feed = newer, feedStatus = 200, fileOnSite = true, answer = true, post }) {
    const calls = [], store = {};
    const ctx = {
      navigator: { userAgent: mobile ? 'Mozilla/5.0 (Linux; Android 13)' : 'Mozilla/5.0 (Windows NT 10.0)' },
      window: { innerWidth: mobile ? 375 : 1280, confirm: () => answer },
      localStorage: { getItem: k => k === 'iva_sess' && email ? JSON.stringify({ me: email }) : null },
      sessionStorage: { getItem: k => store[k] || null, setItem: (k, v) => { store[k] = v; } },
      fetch: async (url, opt) => {
        const u = String(url), method = opt && opt.method ? opt.method : 'GET';
        calls.push(method + ' ' + u);
        if (/app_releases/.test(u)) return { ok: true, json: async () => released };
        if (/app-test\.json/.test(u)) return { ok: feedStatus === 200, status: feedStatus, json: async () => feed };
        if (method === 'HEAD') return { ok: fileOnSite, status: fileOnSite ? 200 : 404 };
        return { ok: false, status: 404 };
      },
      api: async (p, m, body) => { post && post.push({ p, m, body }); return null; },
      A: { me: email || '', tok: email ? 'tok' : null },
      Date, Number, String, JSON, Promise, console, setTimeout,
    };
    ctx.document = { readyState: 'complete', querySelectorAll: () => [] };
    vm.createContext(ctx);
    vm.runInContext(code2 + '\nthis.mountAppCards = mountAppCards; this.confirmAppRelease = confirmAppRelease;', ctx);
    const slot = { innerHTML: '', querySelector(sel) {
      if (sel === '.apk-test-slot') { const self = this; return { set innerHTML(v) { self.innerHTML = self.innerHTML.replace(/<div class="apk-test-slot">[\s\S]*?<\/div><\/div>/, '<div class="apk-test-slot">' + v + '</div>'); }, querySelector: () => null }; }
      return null; } };
    ctx.mountAppCards(slot); await new Promise(r => setTimeout(r, 30));
    return { html: slot.innerHTML, calls, ctx };
  }
  const row = [{ version_code: feedNow.versionCode, version_name: feedNow.versionName, apk_url: feedNow.apk, reinstall: !!feedNow.reinstall, notes: 'Новое' }];
  const greenOnly = (r, file, who) => {
    assert.ok(!/apk-test/.test(r.html), `${who}: видит жёлтую карточку новой версии — она только у создателя на телефоне`);
    assert.equal((r.html.match(/class="apk-card apk-public"/g) || []).length, 1, `${who}: должна быть одна зелёная карточка`);
    assert.equal((r.html.match(/href="[^"]*\.apk"/g) || []).length, 1, `${who}: должна быть ровно одна ссылка на приложение`);
    assert.ok(r.html.includes(`href="${file}"`), `${who}: ссылка не на ${file}`);
    assert.ok(!r.calls.some(u => /app-test\.json/.test(u)), `${who}: не создатель, а сайт запрашивает тестовую сборку`);
  };
  let cases = 0;
  greenOnly(await run({ email: 'someone@mail.ru', mobile: true }), fb[3], 'обычный пользователь до релиза');
  greenOnly(await run({ email: '', mobile: true }), fb[3], 'гость до релиза');
  greenOnly(await run({ email: 'eclipsik.ru@mail.ru', mobile: true }), fb[3], 'eclipsik');
  greenOnly(await run({ email: 'eclips.ru@mail.ru', mobile: false }), fb[3], 'создатель на компьютере');
  greenOnly(await run({ email: 'someone@mail.ru', mobile: true, released: row }), feedNow.apk, 'обычный пользователь после релиза');
  cases += 5; // пять проверок «одна зелёная карточка» выше
  // Создатель на телефоне, релиз не подтверждён: жёлтая (новая + «Подтвердить релиз») НАД зелёной (старая)
  let r = await run({ email: 'ECLIPS.RU@mail.ru', mobile: true });
  const iy = r.html.indexOf('class="apk-test"'), ig = r.html.indexOf('apk-public');
  assert.ok(iy >= 0 && ig > iy, 'создатель: жёлтая карточка должна стоять НАД зелёной');
  cases += 1;
  assert.match(r.html, /<button class="btn apk-confirm"[^>]*id="apkConfirm">✅ Подтвердить релиз<\/button>/, 'нет кнопки «Подтвердить релиз»');
  assert.ok(r.html.includes(`href="${feedNow.apk}"`) && r.html.includes(`href="${fb[3]}"`), 'создатель: должны быть ссылки на новую и на старую версию');
  assert.match(r.html, /Сейчас у всех пользователей/);
  assert.ok(r.calls.some(u => u.startsWith('GET app-test.json?t=')), 'тестовая сборка должна читаться с того же сайта (относительный адрес)');
  cases += 1;
  // Сайт не отдаёт app-test.json: понятное сообщение без упоминания GitHub
  r = await run({ email: 'eclips.ru@mail.ru', mobile: true, feedStatus: 500 });
  assert.match(r.html, /Не удалось проверить новую сборку\. Обновите страницу позже\./, 'при недоступном app-test.json — понятное сообщение');
  assert.ok(!/GitHub/i.test(r.html), 'сообщение не должно упоминать GitHub');
  cases += 1;
  // Создатель после релиза: новых версий нет
  r = await run({ email: 'eclips.ru@mail.ru', mobile: true, released: row });
  assert.match(r.html, /Новых версий нет/); assert.ok(!/apkConfirm/.test(r.html), 'когда новых версий нет — кнопки «Подтвердить релиз» быть не должно');
  cases += 1;
  // «Подтвердить релиз» пишет в app_releases имя файла — но только если файл уже на сайте
  const post = []; r = await run({ email: 'eclips.ru@mail.ru', mobile: true, post });
  const msg = { textContent: '', className: '' }, btn = { disabled: false };
  await r.ctx.confirmAppRelease(newer, btn, msg, null);
  assert.equal(r.calls.filter(u => u === 'HEAD ' + newer.apk).length, 1, 'перед записью файл проверяется на сайте');
  assert.equal(post.length, 1, '«Подтвердить релиз» не отправил запись');
  assert.equal(post[0].p, '/rest/v1/app_releases'); assert.equal(post[0].m, 'POST');
  assert.equal(post[0].body.version_code, newer.versionCode); assert.equal(post[0].body.apk_url, newer.apk);
  assert.match(post[0].body.apk_url, APK_RE, 'в базу пишется только имя файла');
  assert.match(msg.textContent, /выпущена для всех/);
  cases += 1;
  // Файла на сайте ещё нет: подтверждение отменяется, запись не уходит, кнопка снова доступна
  const post2 = []; r = await run({ email: 'eclips.ru@mail.ru', mobile: true, post: post2, fileOnSite: false });
  const msg2 = { textContent: '', className: '' }, btn2 = { disabled: false };
  await r.ctx.confirmAppRelease(newer, btn2, msg2, null);
  assert.equal(post2.length, 0, 'файла на сайте нет — подтверждать нельзя, иначе у всех битая ссылка');
  assert.match(msg2.textContent, /ещё не открывается на сайте/); assert.ok(msg2.className.includes('bad'), 'отказ должен быть видно');
  assert.equal(btn2.disabled, false, 'после отказа кнопка снова доступна');
  cases += 1;
  // Создатель нажал «Подтвердить», но передумал в окне подтверждения: записи нет, кнопка снова доступна
  const post4 = []; r = await run({ email: 'eclips.ru@mail.ru', mobile: true, post: post4, answer: false });
  const btn4 = { disabled: false }; await r.ctx.confirmAppRelease(newer, btn4, { textContent: '', className: '' }, null);
  assert.equal(post4.length, 0, 'отмена в окне подтверждения не должна писать в базу');
  assert.equal(btn4.disabled, false, 'после отмены кнопка снова доступна');
  cases += 1;
  // Если в app-test.json осталась старая ссылка на ветку, в базу всё равно пишется только имя файла
  const post3 = []; r = await run({ email: 'eclips.ru@mail.ru', mobile: true, post: post3 });
  await r.ctx.confirmAppRelease({ ...newer, apk: 'https://github.com/eclipsru/Arenda-Nvk/raw/arena/x/' + newer.apk }, { disabled: false }, { textContent: '', className: '' }, null);
  assert.equal(post3.length, 1, 'со старой ссылкой запись тоже должна пройти');
  assert.equal(post3[0].body.apk_url, newer.apk, 'в базу — только имя файла');
  cases += 1;

  // ---- Баннер: у всех одинаковый, 10 секунд, выпущенная версия ----
  assert.match(app, /const APP_BANNER_MS = 10000;/, 'баннер должен висеть 10 секунд');
  assert.ok(!/isPermanentUser|permanent/.test(fn('mountAppTopBanner') + fn('showAppTopBanner')), 'у баннера не должно быть «вечного» режима для отдельных людей');
  assert.match(fn('mountAppTopBanner'), /ivaReleased\(\)/, 'баннер должен брать выпущенную версию');
  assert.match(fn('showAppTopBanner'), /esc\(rel\.apk\)/, 'ссылка баннера — на выпущенную версию');
  assert.match(rel, /\/rest\/v1\/app_releases\?select=version_code,version_name,apk_url,reinstall,notes&order=version_code\.desc&limit=1/, 'выпущенная версия берётся не из app_releases');

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
  console.log(`Тестовый канал: адрес app-test.json (тот же сайт); файл ${t.apk} в корне; ${botNote}; ` +
    (bot ? `сборка ${name} (${code}) пишет имя файла` : 'запись сборки не проверена') +
    `; жёлтая карточка и подтверждение — ${cases} случаев в песочнице; баннер 10 с`);
})().catch(e => { console.error('✘ ' + (e && e.message || e)); process.exit(1); });

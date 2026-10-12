// Сторож отдельной страницы настроек профиля клиента (profile.html).
//
// Зачем: настройки клиента (имя, телефон, город, выход) раньше лежали вкладкой
// «Профиль» внутри кабинета и мешались с работой арендодателя. Решение владельца —
// отдельная страница. Сторож следит за тремя вещами:
//   1. страница действительно есть, собирается из готовых компонентов и не собирает
//      лишние персональные данные (только имя, телефон, город — в метаданные Auth);
//   2. кабинет отправляет клиента на неё (иначе снова получится «две реализации»);
//   3. сохранение личных данных не теряет остальные метаданные пользователя
//      (город!) и не пишет права/комиссию — это проверяется запуском кода с подставным API.
//
// Запуск: node tests/client-profile-page.cjs
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const R = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

const page = R('profile.html');
const asset = R('assets/client-profile.js');
const account = R('account.html');
const cabinet = R('cabinet.html');

/* ---------- 1. Страница ---------- */
assert.match(page, /<meta name="robots" content="noindex, nofollow">/, 'profile.html: личная страница должна быть закрыта от поиска');
assert.match(page, /<title>Настройки профиля — Ива<\/title>/, 'profile.html: другой заголовок страницы');

for (const f of ['assets/recovery.js', 'assets/theme.css', 'assets/geo.css', 'assets/data.js',
                 'assets/geo.js', 'assets/email-guard.js', 'assets/sb.js',
                 'assets/app-release.js', 'assets/app.js', 'assets/client-profile.js']) {
  assert.ok(page.includes(f), 'profile.html: не подключён ' + f);
}
assert.ok(page.indexOf('assets/app-release.js') < page.indexOf('assets/app.js'),
  'profile.html: app-release.js должен подключаться раньше app.js — иначе карточки приложения не узнают выпущенную версию');

const pageTag = (page.match(/assets\/app\.js\?v=([a-z0-9-]+)/) || [])[1];
const accountTag = (account.match(/assets\/app\.js\?v=([a-z0-9-]+)/) || [])[1];
assert.ok(pageTag, 'profile.html: у assets/app.js нет метки версии');
assert.equal(pageTag, accountTag, 'profile.html: метка версии app.js отличается от кабинета');

assert.match(page, /mountChrome\('cab', ''\)/, 'profile.html: шапка и меню сайта не подключены');
assert.match(page, /IvaClientProfile\.mount\(\$\('root'\)\)/, 'profile.html: логика страницы не запускается');
assert.ok(!/ProkatInstrumenta-[\d.]+\.apk/.test(page), 'profile.html: версия приложения не должна быть прописана в странице');

const sitemap = R('sitemap.xml');
assert.ok(!sitemap.includes('profile.html'), 'sitemap.xml: закрытая страница настроек не должна попадать в карту сайта');

/* ---------- 2. Данные: ничего лишнего ---------- */
for (const bad of [/passport/i, /паспорт/i, /ИНН/, /карт[аы]\s+банк/i, /landlord_profiles/, /save_landlord_profile/, /app_releases/, /fee_pct/]) {
  assert.ok(!bad.test(asset), 'assets/client-profile.js: на странице настроек клиента не должно быть «' + bad + '»');
}
assert.ok(!/\/rest\/v1\//.test(asset), 'assets/client-profile.js: страница не должна ходить в таблицы базы напрямую — только метаданные Auth');
assert.match(asset, /api\('\/auth\/v1\/user', 'PUT'/, 'assets/client-profile.js: личные данные сохраняются не в аккаунт (Auth)');
assert.match(asset, /saveProfileCity\(/, 'assets/client-profile.js: город должен сохраняться общим способом (как в кабинете)');

/* ---------- 3. Кабинет ведёт клиента на отдельную страницу ---------- */
assert.match(account, /TAB === 'prof' && !isAdmin\(\)/, 'account.html: нет проверки «клиенту здесь не место»');
assert.match(account, /location\.replace\('profile\.html'\)/, 'account.html: клиента не перенаправляет на profile.html');
assert.ok(!/id="editPersonal"/.test(account), 'account.html: в кабинете остался старый редактор личных данных');
assert.ok(!/id="personalForm"/.test(account), 'account.html: форма личных данных осталась в кабинете — две реализации одного и того же');
assert.match(account, /data-tab="prof"/, 'account.html: пропала вкладка «Профиль», через неё клиент находит настройки');
assert.match(account, /href="profile\.html"/, 'account.html: арендодатель не видит ссылку на личные настройки');

assert.match(cabinet, /function vCabUser\(\)\{ location\.href='profile\.html'; \}/, 'cabinet.html: в приложении клиента отправляет не на страницу настроек');

/* ---------- 4. Поведение сохранения (подставной API) ---------- */
function sandbox(options) {
  const opts = options || {};
  const calls = [];
  const ctx = {
    A: {
      tok: 'test-token',
      me: 'client@example.invalid',
      uname: 'Иван Петров',
      userMeta: { name: 'Иван Петров', full_name: 'Иван Петров', phone: '+79001112233', city: 'Таганрог', iva_mark: 'не терять' },
      profileCity: 'Таганрог',
      admin: opts.admin ? { email: 'client@example.invalid', active: true } : null
    },
    esc: s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'),
    initials: n => String(n || '').trim().split(/\s+/).map(p => p[0]).join('').slice(0, 2).toUpperCase(),
    api: async (p, method, body) => { calls.push({ path: p, method: method || 'GET', body: body }); return { id: 'u1', email: 'client@example.invalid', user_metadata: body ? body.data : ctx.A.userMeta }; },
    applyAuthUser: u => { if (u && u.user_metadata) ctx.A.userMeta = u.user_metadata; },
    saveSess: () => {},
    saveProfileCity: async c => { calls.push({ path: 'saveProfileCity', method: 'CITY', body: c }); return c; },
    isAdmin: () => !!opts.admin,
    isAuthed: () => opts.authed !== false,
    restoreSess: async () => opts.authed !== false,
    toast: () => {},
    signOut: () => { calls.push({ path: 'signOut', method: 'LOCAL' }); },
    availableCities: () => ['Новочеркасск', 'Таганрог'],
    mountAppCards: () => {},
    console: console
  };
  vm.createContext(ctx);
  vm.runInContext(asset + '\n;globalThis.__profile = IvaClientProfile;', ctx, { filename: 'assets/client-profile.js' });
  return { P: ctx.__profile, A: ctx.A, calls: calls };
}

(async () => {
  const s = sandbox({});
  assert.equal(typeof s.P.mount, 'function', 'страница не отдала функцию сборки');

  // Пустое имя не уходит на сервер и объясняет причину.
  await assert.rejects(() => s.P.savePersonalData('  ', '+79001112233'), /Укажите имя/);
  assert.equal(s.calls.length, 0, 'пустое имя не должно доходить до сервера');
  await assert.rejects(() => s.P.savePersonalData('И', '9'.repeat(101)), /Телефон/);
  assert.equal(s.calls.length, 0, 'слишком длинный телефон не должен доходить до сервера');

  // Сохранение: только метаданные Auth, остальные поля пользователя целы.
  await s.P.savePersonalData('  Новое имя  ', '+7 900 111 22 33');
  const put = s.calls.filter(c => c.method === 'PUT');
  assert.equal(put.length, 1, 'сохранение личных данных — ровно один запрос');
  assert.equal(put[0].path, '/auth/v1/user', 'сохранение идёт не в аккаунт');
  assert.deepEqual(Object.keys(put[0].body), ['data'], 'в аккаунт отправляется только блок метаданных');
  assert.equal(put[0].body.data.name, 'Новое имя');
  assert.equal(put[0].body.data.full_name, 'Новое имя');
  assert.equal(put[0].body.data.phone, '+7 900 111 22 33');
  assert.equal(put[0].body.data.city, 'Таганрог', 'город в метаданных потерялся при сохранении имени');
  assert.equal(put[0].body.data.iva_mark, 'не терять', 'чужие метаданные потерялись');
  assert.ok(!('role' in put[0].body.data) && !('fee_pct' in put[0].body.data), 'права и комиссия в метаданные не пишутся');

  // Город — общим способом (assets/sb.js → saveProfileCity).
  await s.P.saveCityData('Тверь');
  assert.equal(s.calls.filter(c => c.method === 'CITY').length, 1, 'город сохраняется не общим способом');
  await assert.rejects(() => s.P.saveCityData('Т'), /2 до 100/, 'слишком короткий город должен быть отклонён');

  // Страница без входа: объяснение и кнопка входа вместо форм.
  const anon = sandbox({ authed: false });
  const guestHost = { isConnected: true, innerHTML: '', querySelector: () => null };
  await anon.P.mount(guestHost);
  assert.match(guestHost.innerHTML, /Войдите, чтобы настроить профиль/, 'без входа нет объяснения');
  assert.ok(!/personalForm/.test(guestHost.innerHTML), 'без входа форма личных данных не показывается');

  // Вошедший клиент: форма, город, выход и карточки приложения на месте.
  const host = { isConnected: true, innerHTML: '', querySelector: () => null };
  await s.P.mount(host);
  for (const id of ['personalForm', 'profileCityForm', 'profileCity', 'personalError', 'profileCityError', 'bOut', 'apkCards']) {
    assert.ok(host.innerHTML.includes('id="' + id + '"'), 'на странице нет блока ' + id);
  }
  assert.ok(host.innerHTML.includes('Настройки профиля'), 'нет заголовка страницы');
  assert.ok(!/Данные проката/.test(host.innerHTML), 'клиенту не показываем рабочий кабинет арендодателя');

  // Арендодателю страница тоже открывается: личные данные + ссылки в кабинет.
  const landlordHost = { isConnected: true, innerHTML: '', querySelector: () => null };
  const landlord = sandbox({ admin: true });
  await landlord.P.mount(landlordHost);
  assert.ok(landlordHost.innerHTML.includes('Данные проката'), 'арендодателю не предложены данные проката');
  assert.ok(!/Арендатор/.test(landlordHost.innerHTML), 'арендодателю показан статус клиента');

  console.log('OK profile.html: отдельная страница настроек клиента — подключения, перенаправление кабинета, ' +
    'сохранение имени/телефона без потери города, запрет лишних данных, вид без входа и для арендодателя');
})().catch(e => { console.error('FAIL client-profile-page: ' + (e && e.message || e)); process.exit(1); });

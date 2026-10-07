// SITE_BASE_URL=http://127.0.0.1:8000 node tests/chief-and-apk.cjs
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
// 05.10.2026: у всех — выпущенная версия (app_releases пусто → старая 10.14); у создателя на телефоне — ещё жёлтая карточка новой.
const APK = 'ProkatInstrumenta-10.14.apk';
const FEED = fs.readFileSync(path.join(__dirname, '../app-test.json'), 'utf8');
// Номер и файл тестовой сборки берём из app-test.json: их меняет «Сборка приложения» в Actions,
// а тест из-за этого ломаться не должен (07.10.2026: тест ждал 10.15, а собралась 10.16).
const FEED_J = JSON.parse(FEED);
const NEW_VER = String(FEED_J.versionName);
const NEW_APK = String(FEED_J.apk).split('/').pop();
async function mockRelease(page){
  await page.route('**/rest/v1/app_releases*', r => r.fulfill({ contentType: 'application/json', body: '[]' }));
  await page.route('**/app-test.json*', r => r.fulfill({ contentType: 'application/json', body: FEED, headers: { 'access-control-allow-origin': '*' } }));
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    // Anonymous users must see an access check, not a forever-loading screen.
    {
      const page = await browser.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      await page.goto(`${BASE}/chief.html`);
      await page.getByText('Нужно войти', { exact: true }).waitFor({ timeout: 10000 });
      assert.deepEqual(errors, []);
      assert.equal(await page.locator('#root a[href$=".apk"]').count(), 0, 'APK card is inside the owner-only dashboard');
      await page.close();
    }

    // Mock the owner's role and read-only data in the browser. No real account,
    // password, token or production mutations are involved.
    {
      const page = await browser.newPage({ viewport: { width: 375, height: 810 } });
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      const sb = fs.readFileSync(path.join(__dirname, '../assets/sb.js'), 'utf8');
      await page.route('**/assets/sb.js*', route => route.fulfill({
        contentType: 'text/javascript',
        body: sb + `\n
A.me = 'owner@example.test'; A.tok = 'mock'; A.admin = { role: 'chief' };
restoreSess = async () => A;
isAuthed = () => true;
loadOrders = async () => [];
loadParts = async () => [];
loadAdmins = async () => [{ email:'owner@example.test', role:'chief', active:true }];
loadFees = async () => [];
loadAllTools = async () => [];
loadAllCats = async () => ({ cats: [], subs: [] });
unreadCount = async () => 0;
api = async (p) => {
  // Счётчик обращений: кабинет должен показать цифры, а не прочерк.
  if (String(p).indexOf('directory_click_stats') >= 0) {
    const day = new Date().toISOString().slice(0, 10);
    return [
      { phone: '79081732475', city: 'Ростов-на-Дону', kind: 'call', day, cnt: 3 },
      { phone: '79081732475', city: 'Ростов-на-Дону', kind: 'lead', day, cnt: 1 },
      { phone: '',          city: 'Ростов-на-Дону', kind: 'view', day, cnt: 9 }
    ];
  }
  return [];
};
moneyStats = () => ({ rent:0, fee:0, paid:0, owed:0, byAdmin:{} });
`
      }));
      await mockRelease(page);
      await page.goto(`${BASE}/chief.html`);
      await page.locator('.apk-public').waitFor({ timeout: 10000 });
      assert.match(await page.locator('.apk-public').innerText(), /версия 10\.14/);
      // Не создатель: одна зелёная карточка + кнопка в шапке, обе на выпущенную версию; жёлтой нет
      await page.waitForTimeout(800);
      assert.equal(await page.locator('.apk-test').count(), 0, 'жёлтая карточка — только у создателя');
      assert.equal(await page.locator('#root a[href$=".apk"]').count(), 2);
      for (const h of await page.locator('#root a[href$=".apk"]').evaluateAll(a => a.map(x => x.getAttribute('href')))) assert.equal(h, APK);
      assert.match(await page.locator('.sect-h .cnt').first().innerText(), /владелец/);
      // Сводка: цифры обращений со страниц городов
      const dash = await page.locator('#root').innerText();
      assert.match(dash, /Обращения со страниц городов/, 'в сводке нет блока обращений');
      assert.match(dash, /Звонки в пункты проката/, 'нет счётчика звонков в пункты проката');
      assert.match(dash, /Обращения к нам/, 'нет счётчика обращений к площадке');
      assert.match(dash, /\+7 \(908\) 173-24-75/, 'телефон в отчёте не отформатирован');
      assert.match(dash, /ростов-на-дону/i, 'в отчёте нет города обращения');
      assert.match(dash, /без сбора данных о людях/, 'нет пояснения про отсутствие сбора персональных данных');

      await page.locator('[data-tab="outreach"]').click();
      assert.match(await page.locator('#root').innerText(), /Рассылка по базам проката/);
      assert.deepEqual(errors, []);
      // Счётчик ещё не включён (таблицы нет) — кабинет работает, но объясняет, что сделать
      {
        const p2 = await browser.newPage({ viewport: { width: 375, height: 810 } });
        const errs2 = [];
        p2.on('pageerror', e => errs2.push(e.message));
        await p2.route('**/assets/sb.js*', route => route.fulfill({
          contentType: 'text/javascript',
          body: sb + `\n
A.me = 'owner@example.test'; A.tok = 'mock'; A.admin = { role: 'chief' };
restoreSess = async () => A;
isAuthed = () => true;
loadOrders = async () => []; loadParts = async () => []; loadFees = async () => []; loadAllTools = async () => [];
loadAdmins = async () => [{ email:'owner@example.test', role:'chief', active:true }];
loadAllCats = async () => ({ cats: [], subs: [] });
unreadCount = async () => 0;
api = async (p) => { throw new Error('relation \\"public.directory_clicks\\" does not exist'); };
moneyStats = () => ({ rent:0, fee:0, paid:0, owed:0, byAdmin:{} });
`
        }));
        await mockRelease(p2);
        await p2.goto(`${BASE}/chief.html`);
        await p2.locator('.apk-public').waitFor({ timeout: 10000 });
        const txt = await p2.locator('#root').innerText();
        assert.match(txt, /Счётчик ещё не включён/, 'кабинет должен объяснить, что счётчик ещё не включён');
        assert.match(txt, /20261001_directory_clicks\.sql/, 'нет имени файла, который надо выполнить');
        assert.deepEqual(errs2, [], 'кабинет не должен падать без таблицы счётчика');
        await p2.close();
      }

      const response = await page.request.get(`${BASE}/${APK}`);
      assert.equal(response.status(), 200, 'APK must be publicly downloadable');
      assert.equal((await response.body()).length, 1108041, 'Correct APK file is served');
      await page.close();
    }

    // Создатель (eclips.ru@mail.ru) на телефоне: статус «создатель», жёлтая карточка новой версии НАД зелёной,
    // «Подтвердить релиз» отправляет запись в app_releases.
    {
      const page = await browser.newPage({ viewport: { width: 375, height: 810 } });
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('dialog', d => d.accept());
      const sb = fs.readFileSync(path.join(__dirname, '../assets/sb.js'), 'utf8');
      await page.route('**/assets/sb.js*', route => route.fulfill({
        contentType: 'text/javascript',
        body: sb + `\n
A.me = 'eclips.ru@mail.ru'; A.tok = 'mock'; A.admin = { role: 'chief' };
restoreSess = async () => A; isAuthed = () => true;
loadOrders = async () => []; loadParts = async () => []; loadFees = async () => []; loadAllTools = async () => [];
loadAdmins = async () => [{ email:'eclips.ru@mail.ru', role:'chief', active:true }];
loadAllCats = async () => ({ cats: [], subs: [] }); unreadCount = async () => 0;
window.__posts = [];
api = async (p, m, b) => { if (m === 'POST') { window.__posts.push({ p: p, b: b }); return null; } return []; };
moneyStats = () => ({ rent:0, fee:0, paid:0, owed:0, byAdmin:{} });
`
      }));
      await mockRelease(page);
      await page.goto(`${BASE}/chief.html`);
      await page.locator('#apkConfirm').waitFor({ timeout: 10000 });
      assert.match(await page.locator('.sect-h .cnt').first().innerText(), /создатель/, 'у создателя статус «создатель»');
      const order = await page.evaluate(() => {
        const y = document.querySelector('.apk-test'), g = document.querySelector('.apk-public');
        return !!(y && g && (y.compareDocumentPosition(g) & Node.DOCUMENT_POSITION_FOLLOWING));
      });
      assert.ok(order, 'жёлтая карточка должна стоять НАД зелёной');
      assert.match(await page.locator('.apk-test').innerText(), new RegExp('Новая версия ' + NEW_VER.replace(/\./g, '\\.')));
      assert.ok((await page.locator('.apk-test a.btn').getAttribute('href')).endsWith(NEW_APK));
      assert.ok((await page.locator('.apk-public a.btn').getAttribute('href')).endsWith(APK));
      await page.locator('#apkConfirm').click();
      await page.getByText(/выпущена для всех/).waitFor({ timeout: 5000 });
      const posts = await page.evaluate(() => window.__posts);
      assert.equal(posts.length, 1); assert.equal(posts[0].p, '/rest/v1/app_releases');
      assert.equal(posts[0].b.version_code, 89);
      assert.equal(posts[0].b.apk_url, NEW_APK, 'файл уже на сайте — ссылка на сайт, а не на GitHub');
      assert.deepEqual(errors, []);
      await page.close();
    }
    console.log('Chief cabinet, access control, outreach and native APK link: OK');
  } finally {
    await browser.close();
  }
})().catch(err => { console.error(err); process.exitCode = 1; });

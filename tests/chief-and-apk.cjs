// SITE_BASE_URL=http://127.0.0.1:8000 node tests/chief-and-apk.cjs
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
const APK = 'ProkatInstrumenta-10.15.apk';

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
      await page.goto(`${BASE}/chief.html`);
      await page.locator('.apk-recovery').waitFor({ timeout: 10000 });
      assert.match(await page.locator('.apk-recovery').innerText(), /обновлённая версия 10\.14/);
      assert.match(await page.locator('.apk-recovery').innerText(), /удалите текущую версию/);
      assert.equal(await page.locator('#root a[href$=".apk"]').count(), 2);
      assert.equal(new URL(await page.locator('.apk-recovery a').getAttribute('href'), `${BASE}/chief.html`).pathname.split('/').pop(), APK);
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
        await p2.goto(`${BASE}/chief.html`);
        await p2.locator('.apk-recovery').waitFor({ timeout: 10000 });
        const txt = await p2.locator('#root').innerText();
        assert.match(txt, /Счётчик ещё не включён/, 'кабинет должен объяснить, что счётчик ещё не включён');
        assert.match(txt, /20261001_directory_clicks\.sql/, 'нет имени файла, который надо выполнить');
        assert.deepEqual(errs2, [], 'кабинет не должен падать без таблицы счётчика');
        await p2.close();
      }

      const response = await page.request.get(`${BASE}/${APK}`);
      assert.equal(response.status(), 200, 'APK must be publicly downloadable');
      assert.equal((await response.body()).length, 1116271, 'Correct APK file is served');
      await page.close();
    }
    console.log('Chief cabinet, access control, outreach and native APK link: OK');
  } finally {
    await browser.close();
  }
})().catch(err => { console.error(err); process.exitCode = 1; });

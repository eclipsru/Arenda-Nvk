// SITE_BASE_URL=http://127.0.0.1:8000 node tests/chief-and-apk.cjs
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
const APK = 'ProkatInstrumenta-10.12-clone.apk';

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
api = async () => [];
moneyStats = () => ({ rent:0, fee:0, paid:0, owed:0, byAdmin:{} });
`
      }));
      await page.goto(`${BASE}/chief.html`);
      await page.locator('.apk-recovery').waitFor({ timeout: 10000 });
      assert.match(await page.locator('.apk-recovery').innerText(), /обновлённая версия 10\.12/);
      assert.match(await page.locator('.apk-recovery').innerText(), /удалите текущую версию/);
      assert.equal(await page.locator('#root a[href$=".apk"]').count(), 2);
      assert.equal(new URL(await page.locator('.apk-recovery a').getAttribute('href'), `${BASE}/chief.html`).pathname.split('/').pop(), APK);
      await page.locator('[data-tab="outreach"]').click();
      assert.match(await page.locator('#root').innerText(), /Рассылка по базам проката/);
      assert.deepEqual(errors, []);
      const response = await page.request.get(`${BASE}/${APK}`);
      assert.equal(response.status(), 200, 'APK must be publicly downloadable');
      assert.equal((await response.body()).length, 1095696, 'Correct APK file is served');
      await page.close();
    }
    console.log('Chief cabinet, access control, outreach and native APK link: OK');
  } finally {
    await browser.close();
  }
})().catch(err => { console.error(err); process.exitCode = 1; });

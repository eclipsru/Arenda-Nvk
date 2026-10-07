const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
// 05.10.2026: баннер у всех одинаковый, 10 секунд, ссылка на ВЫПУЩЕННУЮ версию (app_releases; пока пусто — 10.14).
const APK_NAME = 'ProkatInstrumenta-10.14.apk';
// Подтверждённую версию тестируем по текущему app-test.json, не закрепляя старую ветку.
const NEW_URL = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'app-test.json'), 'utf8')).apk;
// Ответ базы подменяется: тест не зависит от того, подтверждён ли релиз на самом деле.
async function mockReleases(ctx, rows){
  await ctx.route('**/rest/v1/app_releases*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(rows || []) }));
}
const MOBILE = {
  viewport: { width: 375, height: 667 },
  userAgent: 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Mobile Safari/537.36'
};

async function runTests() {
  const browser = await chromium.launch({ headless: true });
  try {
    // First visit: banner with the released (old) version; stays 10 seconds, then disappears.
    {
      const context = await browser.newContext(MOBILE);
      await mockReleases(context, []);
      const page = await context.newPage();
      await page.goto(`${BASE}/index.html`);
      const banner = page.locator('#appTopBanner');
      await banner.waitFor({ state: 'visible', timeout: 5000 });
      assert.match(await banner.innerText(), /Ива для Android · 10\.14/);
      assert.equal((await page.locator('#appTbBtn').innerText()).trim(), 'Скачать');
      assert.ok((await page.locator('#appTbBtn').getAttribute('href')).endsWith(APK_NAME));
      assert.equal(await banner.locator('.app-tb-bar').count(), 1);
      await page.waitForTimeout(6000);
      assert.equal(await banner.count(), 1, 'banner must still be visible after 6 s (10 s rule)');
      await page.waitForTimeout(5000);
      assert.equal(await banner.count(), 0, 'banner closes automatically after 10 s');
      await context.close();
    }

    // Clicking the link means "downloaded", not "installed". A real APK is served.
    {
      const context = await browser.newContext(MOBILE);
      await mockReleases(context, []);
      const page = await context.newPage();
      await page.addInitScript(() => localStorage.setItem('prokat_app_installed_ver', '10.12'));
      await page.goto(`${BASE}/index.html`);
      const banner = page.locator('#appTopBanner');
      await banner.waitFor({ state: 'visible', timeout: 5000 });
      assert.match(await banner.innerText(), /Обновление · версия 10\.14/);
      const [download] = await Promise.all([
        page.waitForEvent('download'),
        page.locator('#appTbBtn').click()
      ]);
      assert.equal(download.suggestedFilename(), APK_NAME);
      assert.equal(await page.evaluate(() => localStorage.getItem('prokat_app_download_release')), APK_NAME);
      assert.equal(await page.evaluate(() => localStorage.getItem('prokat_app_installed_ver')), '10.12', 'Do not claim installation on download');
      await context.close();
    }

    // Already downloaded the released file — no repeats.
    {
      const context = await browser.newContext(MOBILE);
      await mockReleases(context, []);
      const page = await context.newPage();
      await page.addInitScript(f => localStorage.setItem('prokat_app_download_release', f), APK_NAME);
      await page.goto(`${BASE}/index.html`);
      await page.waitForTimeout(1500);
      assert.equal(await page.locator('#appTopBanner').count(), 0);
      await context.close();
    }

    // Creator gets the same 10-second banner (two-version cards are in the cabinet only).
    {
      const context = await browser.newContext(MOBILE);
      await mockReleases(context, []);
      const page = await context.newPage();
      await page.addInitScript(() => localStorage.setItem('iva_sess', JSON.stringify({ me: 'eclips.ru@mail.ru' })));
      await page.goto(`${BASE}/index.html`);
      const banner = page.locator('#appTopBanner');
      await banner.waitFor({ state: 'visible', timeout: 5000 });
      assert.ok((await page.locator('#appTbBtn').getAttribute('href')).endsWith(APK_NAME), 'creator banner: released version only');
      assert.equal(await banner.locator('#appTbClose').count(), 1);
      await context.close();
    }

    // After «Подтвердить релиз» (row in app_releases) everyone gets the new version, someone who downloaded 10.14 too.
    {
      const context = await browser.newContext(MOBILE);
      await mockReleases(context, [{ version_code: 89, version_name: '10.15', apk_url: NEW_URL, reinstall: true, notes: '' }]);
      const page = await context.newPage();
      await page.addInitScript(f => localStorage.setItem('prokat_app_download_release', f), APK_NAME);
      await page.goto(`${BASE}/index.html`);
      const banner = page.locator('#appTopBanner');
      await banner.waitFor({ state: 'visible', timeout: 5000 });
      assert.match(await banner.innerText(), /Обновление · версия 10\.15/);
      assert.match(await banner.innerText(), /удалите старое приложение/);
      assert.equal(await page.locator('#appTbBtn').getAttribute('href'), NEW_URL);
      await context.close();
    }

    // No banner on desktop; it is available on catalogue for mobile.
    {
      const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
      const page = await context.newPage();
      await page.goto(`${BASE}/index.html`);
      assert.equal(await page.locator('#appTopBanner').count(), 0);
      await context.close();
    }
    {
      const context = await browser.newContext(MOBILE);
      await mockReleases(context, []);
      const page = await context.newPage();
      await page.goto(`${BASE}/catalog.html`);
      await page.locator('#appTopBanner').waitFor({ state: 'visible', timeout: 5000 });
      await context.close();
    }

    console.log('App banner: released version, 10 s, download, after release confirm: 7 cases OK');
  } finally {
    await browser.close();
  }
}
runTests().catch(err => { console.error(err); process.exitCode = 1; });

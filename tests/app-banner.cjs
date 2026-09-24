const { chromium } = require('playwright');
const assert = require('node:assert/strict');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
const APK_NAME = 'ProkatInstrumenta-10.11-recovery.apk';
const RELEASE = '10.11-recovery-20260924';
const MOBILE = {
  viewport: { width: 375, height: 667 },
  userAgent: 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Mobile Safari/537.36'
};

async function runTests() {
  const browser = await chromium.launch({ headless: true });
  try {
    // First visit: shows the restored native APK, then disappears after 5 seconds.
    {
      const context = await browser.newContext(MOBILE);
      const page = await context.newPage();
      await page.goto(`${BASE}/index.html`);
      const banner = page.locator('#appTopBanner');
      await banner.waitFor({ state: 'visible', timeout: 5000 });
      assert.match(await banner.innerText(), /Ива для Android · 10\.11/);
      assert.equal((await page.locator('#appTbBtn').innerText()).trim(), 'Скачать');
      assert.ok((await page.locator('#appTbBtn').getAttribute('href')).endsWith(APK_NAME));
      assert.equal(await banner.locator('.app-tb-bar').count(), 1);
      await page.waitForTimeout(5600);
      assert.equal(await banner.count(), 0, 'Non-owner banner closes automatically');
      await context.close();
    }

    // Clicking the link means "downloaded", not "installed". A real APK is served.
    {
      const context = await browser.newContext(MOBILE);
      const page = await context.newPage();
      await page.addInitScript(() => localStorage.setItem('prokat_app_installed_ver', '10.12'));
      await page.goto(`${BASE}/index.html`);
      const banner = page.locator('#appTopBanner');
      await banner.waitFor({ state: 'visible', timeout: 5000 });
      assert.match(await banner.innerText(), /Восстановленная версия 10\.11/);
      assert.match(await banner.innerText(), /удалите старый APK/);
      const [download] = await Promise.all([
        page.waitForEvent('download'),
        page.locator('#appTbBtn').click()
      ]);
      assert.equal(download.suggestedFilename(), APK_NAME);
      assert.equal(await page.evaluate(() => localStorage.getItem('prokat_app_download_release')), RELEASE);
      assert.equal(await page.evaluate(() => localStorage.getItem('prokat_app_installed_ver')), '10.12', 'Do not claim installation on download');
      await context.close();
    }

    // A previous visit to this *specific* release suppresses repeats.
    {
      const context = await browser.newContext(MOBILE);
      const page = await context.newPage();
      await page.addInitScript(release => localStorage.setItem('prokat_app_download_release', release), RELEASE);
      await page.goto(`${BASE}/index.html`);
      assert.equal(await page.locator('#appTopBanner').count(), 0);
      await context.close();
    }

    // Owner can always find the APK; banner never auto-dismisses.
    {
      const context = await browser.newContext(MOBILE);
      const page = await context.newPage();
      await page.addInitScript(release => {
        localStorage.setItem('iva_sess', JSON.stringify({ me: 'eclips.ru@mail.ru' }));
        localStorage.setItem('prokat_app_download_release', release);
      }, RELEASE);
      await page.goto(`${BASE}/index.html`);
      const banner = page.locator('#appTopBanner');
      await banner.waitFor({ state: 'visible', timeout: 5000 });
      assert.match(await banner.innerText(), /Восстановленная версия 10\.11/);
      assert.equal(await banner.locator('#appTbClose').count(), 0);
      await page.waitForTimeout(5600);
      assert.equal(await banner.count(), 1);
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
      const page = await context.newPage();
      await page.goto(`${BASE}/catalog.html`);
      await page.locator('#appTopBanner').waitFor({ state: 'visible', timeout: 5000 });
      await context.close();
    }

    console.log('App banner, rollback link and download: 6 cases OK');
  } finally {
    await browser.close();
  }
}
runTests().catch(err => { console.error(err); process.exitCode = 1; });

const { chromium } = require('playwright');
const assert = require('assert');

async function runTests() {
  console.log('--- Starting App Top Banner Tests ---');
  const browser = await chromium.launch({ headless: true });

  // Test 1: Fresh mobile user
  console.log('Test 1: Fresh mobile user sees "В приложении удобней" and it auto-dismisses after 5s');
  {
    const context = await browser.newContext({
      viewport: { width: 375, height: 667 },
      userAgent: 'Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 Mobile Safari/537.36'
    });
    const page = await context.newPage();
    await page.goto('http://localhost:8000/index.html');

    // Banner should be visible
    const banner = page.locator('#appTopBanner');
    await banner.waitFor({ state: 'visible', timeout: 3000 });

    const title = await page.locator('#appTopBanner .app-tb-title').textContent();
    console.log('  Banner title:', title.trim());
    assert(title.includes('В приложении удобней'), 'Expected title to contain "В приложении удобней"');

    const btn = await page.locator('#appTopBanner #appTbBtn').textContent();
    console.log('  Button text:', btn.trim());
    assert.strictEqual(btn.trim(), 'Скачать');

    // Check progress bar exists
    const bar = await page.locator('#appTopBanner .app-tb-bar').count();
    assert.strictEqual(bar, 1, 'Expected progress bar to exist');

    // Wait 5.5s to verify auto-dismiss
    console.log('  Waiting 5.5s for auto-dismiss...');
    await page.waitForTimeout(5600);

    const isHidden = await banner.count();
    console.log('  Banner count after 5.5s:', isHidden);
    assert.strictEqual(isHidden, 0, 'Banner should be removed after 5 seconds');
    await context.close();
  }

  // Test 2: User with current version already installed
  console.log('\nTest 2: User with current version installed does NOT see banner');
  {
    const context = await browser.newContext({
      viewport: { width: 375, height: 667 },
      userAgent: 'Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 Mobile Safari/537.36'
    });
    const page = await context.newPage();
    await page.addInitScript(() => {
      localStorage.setItem('prokat_app_installed_ver', '10.12');
    });
    await page.goto('http://localhost:8000/index.html');
    await page.waitForTimeout(1000);

    const bannerCount = await page.locator('#appTopBanner').count();
    console.log('  Banner count for installed user:', bannerCount);
    assert.strictEqual(bannerCount, 0, 'Banner should NOT be shown for users with current version installed');
    await context.close();
  }

  // Test 3: User with older version installed gets "Обновление удобнее"
  console.log('\nTest 3: User with older version (10.10) sees "Обновление удобнее"');
  {
    const context = await browser.newContext({
      viewport: { width: 375, height: 667 },
      userAgent: 'Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 Mobile Safari/537.36'
    });
    const page = await context.newPage();
    await page.addInitScript(() => {
      localStorage.setItem('prokat_app_installed_ver', '10.10');
    });
    await page.goto('http://localhost:8000/index.html');

    const banner = page.locator('#appTopBanner');
    await banner.waitFor({ state: 'visible', timeout: 3000 });

    const title = await page.locator('#appTopBanner .app-tb-title').textContent();
    console.log('  Banner title:', title.trim());
    assert(title.includes('Обновление удобнее'), 'Expected title to contain "Обновление удобнее"');

    const btn = await page.locator('#appTopBanner #appTbBtn').textContent();
    console.log('  Button text:', btn.trim());
    assert.strictEqual(btn.trim(), 'Обновить');

    // Click update button to verify it saves new version
    await page.locator('#appTopBanner #appTbBtn').click();
    await page.waitForTimeout(500);

    const savedVer = await page.evaluate(() => localStorage.getItem('prokat_app_installed_ver'));
    console.log('  Saved version in localStorage after click:', savedVer);
    assert.strictEqual(savedVer, '10.12', 'Expected version 10.12 to be saved in localStorage');
    await context.close();
  }

  // Test 4: Desktop user does not see banner
  console.log('\nTest 4: Desktop user does not see mobile banner');
  {
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36'
    });
    const page = await context.newPage();
    await page.goto('http://localhost:8000/index.html');
    await page.waitForTimeout(1000);

    const bannerCount = await page.locator('#appTopBanner').count();
    console.log('  Banner count on desktop:', bannerCount);
    assert.strictEqual(bannerCount, 0, 'Desktop user should NOT see mobile app banner');
    await context.close();
  }

  // Test 5: Verify on catalog and tool page on mobile
  console.log('\nTest 5: Verify banner appears on catalog.html on mobile');
  {
    const context = await browser.newContext({
      viewport: { width: 375, height: 667 },
      userAgent: 'Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 Mobile Safari/537.36'
    });
    const page = await context.newPage();
    await page.goto('http://localhost:8000/catalog.html');

    const banner = page.locator('#appTopBanner');
    await banner.waitFor({ state: 'visible', timeout: 3000 });
    const title = await page.locator('#appTopBanner .app-tb-title').textContent();
    console.log('  Catalog page banner title:', title.trim());
    assert(title.includes('В приложении удобней'));
    await context.close();
  }

  await browser.close();
  console.log('\n>>> ALL 5 APP BANNER TESTS PASSED SUCCESSFULLY! <<<');
}

runTests().catch(err => {
  console.error('Test failed:', err);
  process.exit(1);
});

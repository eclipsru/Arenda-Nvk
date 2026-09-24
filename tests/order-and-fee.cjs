const { chromium } = require('playwright');
const assert = require('assert');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  let submittedOrderPayload = null;
  let postedFee = null;

  const email = 'eclipsik.ru@mail.ru';
  let meta = { name: 'Галиновский Илья Игоревич', phone: '89081732475' };

  await page.route('**/*', async (route) => {
    const req = route.request();
    const url = req.url();
    const u = new URL(url);

    if (u.hostname.includes('photon.komoot.io')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              geometry: { type: 'Point', coordinates: [40.09, 47.41] },
              properties: { countrycode: 'RU', city: 'Новочеркасск', street: 'улица Ленина', housenumber: '5' }
            }
          ]
        })
      });
    }

    if (u.hostname.endsWith('supabase.co')) {
      if (u.pathname === '/auth/v1/user') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: '50000000-0000-4000-8000-000000000001',
            email,
            user_metadata: meta
          })
        });
      }

      if (u.pathname === '/auth/v1/token') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            access_token: 'fake-jwt',
            refresh_token: 'fake-ref',
            user: { id: '50000000-0000-4000-8000-000000000001', email, user_metadata: meta }
          })
        });
      }

      if (u.pathname.endsWith('/admins')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([{
            email,
            active: true,
            role: 'admin',
            fee_pct: 3,
            debt_limit: 1000,
            company: 'Прокат',
            full_name: 'Галиновский Илья Игоревич',
            phone: '89081732475',
            address: 'Новочеркасск, ул. Маресьева, 36'
          }])
        });
      }

      if (u.pathname.endsWith('/rpc/submit_order')) {
        submittedOrderPayload = JSON.parse(req.postData());
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(12345)
        });
      }

      if (u.pathname.endsWith('/order_parts')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: 22,
              order_id: 49,
              owner_email: email,
              tools_text: 'Бетономешалка',
              status: 'closed',
              rent_sum: 15000,
              fee: 450,
              fee_pct: 3,
              created_at: '2026-09-22T08:26:53Z',
              archived: false,
              orders: {
                name: 'Петров Петр',
                phone: '89081234567',
                get_method: 'Самовывоз, Новочеркасск',
                days: 1
              }
            },
            {
              id: 25,
              order_id: 50,
              owner_email: email,
              tools_text: 'Болгарка (УШМ) аккумуляторная Makita 36 В',
              status: 'new',
              rent_sum: 900,
              fee: 27,
              fee_pct: 3,
              created_at: '2026-09-24T10:00:00Z',
              archived: false,
              orders: {
                name: 'Иван Сидоров',
                phone: '89511112233',
                get_method: 'Доставка: Новочеркасск, ул. Ленина, 5',
                days: 2
              }
            }
          ])
        });
      }

      if (u.pathname.endsWith('/fee_payments')) {
        if (req.method() === 'POST') {
          postedFee = JSON.parse(req.postData());
          return route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify([{ id: 99, ...postedFee, status: 'pending' }])
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: 10,
              admin_email: email,
              amount: 450,
              status: 'confirmed',
              note: 'Оплата комиссии',
              created_at: '2026-09-22T16:58:53Z'
            }
          ])
        });
      }

      if (u.pathname.endsWith('/tools')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: 'tool-with-delivery',
              owner_email: 'other@mail.ru',
              status: 'active',
              active: true,
              name: 'Болгарка (УШМ) аккумуляторная Makita 36 В',
              cat: 'power',
              price: 450,
              deposit: 2250,
              imgs: ['https://example.invalid/tool.jpg'],
              descr: 'Тестовая болгарка с доставкой',
              terms: 'Адрес выдачи: Новочеркасск, ул. Маресьева, 36',
              delivery: true,
              delivery_price: 40
            },
            {
              id: 'tool-no-delivery',
              owner_email: 'other@mail.ru',
              status: 'active',
              active: true,
              name: 'Сварочный аппарат полуавтомат',
              cat: 'welding',
              price: 900,
              deposit: 4500,
              imgs: ['https://example.invalid/tool.jpg'],
              descr: 'Тестовый сварочник без доставки',
              terms: 'Адрес выдачи: Новочеркасск, ул. Маресьева, 36',
              delivery: false,
              delivery_price: 0
            }
          ])
        });
      }

      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      });
    }

    return route.continue();
  });

  // Test 1: Tool WITHOUT delivery has «Доставки нет!»
  console.log('Testing tool WITHOUT delivery...');
  await page.goto('http://127.0.0.1:8000/tool.html?id=tool-no-delivery', { waitUntil: 'networkidle' });
  await page.locator('#orderBtn').click();
  await page.waitForSelector('#sheet.open');

  const noDelivBanner = page.locator('.no-delivery-banner');
  assert(await noDelivBanner.isVisible(), 'Should show .no-delivery-banner for tool without delivery');
  const bannerText = await noDelivBanner.textContent();
  assert(bannerText.includes('Доставки нет!'), 'Banner must contain "Доставки нет!"');
  assert(await page.locator('#rowDelivery').count() === 0, 'Should NOT show delivery toggle row for tool without delivery');
  await page.locator('#ordCancel').click();
  await page.waitForTimeout(300);
  console.log('✔ "Доставки нет!" verified on tool without delivery');

  // Test 2: Tool WITH delivery has toggle switch and round-trip switch
  console.log('Testing tool WITH delivery, address autocomplete and round-trip toggle...');
  await page.goto('http://127.0.0.1:8000/tool.html?id=tool-with-delivery', { waitUntil: 'networkidle' });
  await page.locator('#orderBtn').click();
  await page.waitForSelector('#sheet.open');

  // Verify delivery toggle is present and initially off
  const delivRow = page.locator('#rowDelivery');
  assert(await delivRow.isVisible(), 'Delivery toggle row should be visible');
  assert(await page.locator('#swDelivery').isVisible(), 'Delivery switch should be visible');
  assert(!await page.locator('#swDelivery').evaluate(el => el.classList.contains('on')), 'Delivery switch should be initially off');

  // Click to turn Delivery ON
  await delivRow.click();
  await page.waitForFunction(() => document.getElementById('swDelivery')?.classList.contains('on'));
  assert(await page.locator('#deliveryDetailsBox').isVisible(), 'Delivery details box should appear');
  assert(await page.locator('#rowRoundTrip').isVisible(), 'Round trip toggle row should appear');

  // Fill delivery address
  const addrInput = page.locator('#ordDeliveryAddr');
  await addrInput.fill('Новочеркасск, улица Ленина, 5');
  await addrInput.dispatchEvent('change');
  await page.waitForTimeout(300);

  // Toggle «Туда и обратно» ON
  const roundTripRow = page.locator('#rowRoundTrip');
  await roundTripRow.click();
  await page.waitForFunction(() => document.getElementById('swRoundTrip')?.classList.contains('on'));

  // Fill client name & phone
  await page.locator('#ordName').fill('Сергей Петров');
  await page.locator('#ordPhone').fill('+7 918 000-11-22');

  // Submit order
  await page.locator('#ordSend').click();
  await page.waitForFunction(() => {
    const title = document.querySelector('.sheet-h b');
    return title && title.textContent.includes('Заявка принята');
  }, { timeout: 5000 });

  assert(submittedOrderPayload, 'submit_order RPC should be called');
  assert(submittedOrderPayload.p_get_method.includes('туда-обратно'), 'p_get_method should indicate round-trip delivery');
  assert.strictEqual(submittedOrderPayload.p_address, 'Новочеркасск, улица Ленина, 5');
  console.log('✔ Delivery toggle and round-trip doubled calculation verified');

  // Test 3: Account tabs for landlord
  console.log('Testing account.html tabs for landlord...');
  await page.evaluate((em) => {
    localStorage.setItem('iva_sess', JSON.stringify({
      tok: 'fake-jwt',
      rtok: 'fake-ref',
      me: em,
      uname: 'Илья'
    }));
  }, email);

  await page.goto('http://127.0.0.1:8000/account.html#prof', { waitUntil: 'networkidle' });

  const tabs = await page.locator('.tabs button').allTextContents();
  console.log('Account tabs:', tabs);
  assert(tabs.some(t => t.includes('Мои объявления')), 'Should have "Мои объявления" tab');
  assert(tabs.some(t => t.includes('Заявки')), 'Should have "Заявки" tab');
  assert(tabs.some(t => t.includes('Комиссия и оплата')), 'Should have "Комиссия и оплата" tab');
  assert(tabs.some(t => t.includes('Профиль')), 'Should have "Профиль" tab');

  await browser.close();
  console.log('ALL TESTS PASSED SUCCESSFULLY!');
})();

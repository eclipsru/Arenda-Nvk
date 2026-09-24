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
        body: JSON.stringify({ type: 'FeatureCollection', features: [] })
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
              tools_text: 'Сварочный аппарат полуавтомат',
              status: 'new',
              rent_sum: 1800,
              fee: 54,
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
          body: JSON.stringify([{
            id: 'tool-test',
            owner_email: email,
            status: 'active',
            active: true,
            name: 'Сварочный аппарат полуавтомат',
            cat: 'welding',
            price: 900,
            deposit: 4500,
            imgs: ['https://example.invalid/tool.jpg'],
            descr: 'Тестовый инструмент',
            terms: 'Адрес выдачи: Новочеркасск, ул. Маресьева, 36',
            delivery: true
          }])
        });
      }

      if (u.pathname.endsWith('/landlord_profiles')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([])
        });
      }

      if (u.pathname.endsWith('/landlord_applications')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([])
        });
      }

      if (u.pathname.endsWith('/app_messages')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([])
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

  // Test 1: tool.html has «Оформить заявку» button and interactive booking sheet
  console.log('Testing tool.html order button and booking sheet...');
  await page.goto('http://127.0.0.1:8000/tool.html?id=tool-test', { waitUntil: 'networkidle' });

  // Verify button exists
  const orderBtn = page.locator('#orderBtn');
  await assert.doesNotReject(orderBtn.waitFor({ state: 'visible', timeout: 5000 }), 'orderBtn should be visible on tool.html');
  const orderBtnText = await orderBtn.textContent();
  assert(orderBtnText.includes('Оформить заявку'), 'orderBtn text should contain "Оформить заявку"');

  // Click «Оформить заявку»
  await orderBtn.click();
  await page.waitForSelector('#sheet.open');

  // Verify modal elements
  assert(await page.locator('#sheet-title, .sheet-h b').textContent() === 'Оформление заявки', 'Sheet title should be "Оформление заявки"');
  assert(await page.locator('#ordDays').textContent() === '1', 'Initial days should be 1');

  // Test stepper
  await page.locator('#ordPlus').click();
  assert(await page.locator('#ordDays').textContent() === '2', 'Days should increase to 2');

  // Fill name and phone
  await page.locator('#ordName').fill('Тестовый Заказчик');
  await page.locator('#ordPhone').fill('+7 999 123-45-67');

  // Submit order
  await page.locator('#ordSend').click();

  // Wait for confirmation sheet
  await page.waitForFunction(() => {
    const title = document.querySelector('.sheet-h b');
    return title && title.textContent.includes('Заявка принята');
  }, { timeout: 5000 });

  assert(submittedOrderPayload, 'submit_order RPC should have been called');
  assert.strictEqual(submittedOrderPayload.p_name, 'Тестовый Заказчик');
  assert.strictEqual(submittedOrderPayload.p_phone, '+7 999 123-45-67');
  assert.strictEqual(submittedOrderPayload.p_days, 2);
  console.log('✔ tool.html order flow passed');

  // Test 2: account.html tabs for landlord: «Заявки» and «Комиссия и оплата»
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

  // Verify tab list contains: Мои объявления, Заявки, Комиссия и оплата, Избранное, Сообщения, Профиль
  const tabs = await page.locator('.tabs button').allTextContents();
  console.log('Account tabs:', tabs);
  assert(tabs.some(t => t.includes('Мои объявления')), 'Should have "Мои объявления" tab');
  assert(tabs.some(t => t.includes('Заявки')), 'Should have "Заявки" tab');
  assert(tabs.some(t => t.includes('Комиссия и оплата')), 'Should have "Комиссия и оплата" tab');
  assert(tabs.some(t => t.includes('Профиль')), 'Should have "Профиль" tab');

  // Test 3: Open «Комиссия и оплата» tab
  console.log('Testing «Комиссия и оплата» view...');
  await page.locator('button[data-tab="fee"]').click();
  await page.waitForTimeout(300);

  // Check KPI cards
  const kpiLabels = await page.locator('.kpi .lb').allTextContents();
  console.log('KPI labels:', kpiLabels);
  assert(kpiLabels.includes('К оплате (долг)'), 'Should show debt KPI');
  assert(kpiLabels.includes('Ставка комиссии'), 'Should show commission rate KPI');
  assert(kpiLabels.includes('Лимит задолженности'), 'Should show debt limit KPI');

  // Check SBP details card
  const sbpPhone = await page.locator('#btnCopyPhone').isVisible();
  assert(sbpPhone, 'Copy phone button should be visible in payment details');

  // Test pay fee modal
  await page.locator('#btnPayFeeTop').click();
  await page.waitForSelector('#sheet.open');
  assert(await page.locator('.sheet-h b').textContent() === 'Оплата комиссии', 'Payment modal should open');

  await page.locator('#payModalAmount').fill('200');
  await page.locator('#payModalNote').fill('Перевод СБП Т-Банк');
  await page.locator('#payModalSubmit').click();
  await page.waitForTimeout(400);

  assert(postedFee, 'addFee should have been submitted');
  assert.strictEqual(postedFee.amount, 200);
  assert.strictEqual(postedFee.admin_email, email);
  console.log('✔ Fee payment submission flow passed');

  // Test 4: Open «Заявки» tab
  console.log('Testing «Заявки» view...');
  await page.locator('button[data-tab="orders"]').click();
  await page.waitForTimeout(300);

  // Check filter chips
  const chips = await page.locator('.chips .chip').allTextContents();
  console.log('Order filter chips:', chips);
  assert(chips.some(c => c.includes('Все')), 'Chips should include "Все"');
  assert(chips.some(c => c.includes('Новые')), 'Chips should include "Новые"');

  // Check order card exists
  const orderCards = await page.locator('.order-card').count();
  assert(orderCards >= 2, 'Should display order cards for orders 49 and 50');

  // Check action buttons in new order
  const newOrderCard = page.locator('.order-card', { hasText: 'Сварочный аппарат полуавтомат' });
  assert(await newOrderCard.locator('button[data-ord-do="rented"]').isVisible(), 'Should have "Выдал инструмент" button');

  console.log('✔ Orders view passed');

  await browser.close();
  console.log('ALL TESTS PASSED SUCCESSFULLY!');
})();

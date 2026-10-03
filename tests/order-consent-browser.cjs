// Браузерная проверка «заявка без согласия не уходит» (этап П4, шаг 2).
//
// Зачем: статический сторож (tests/p4-personal-data.cjs) видит только строки
// в коде. Здесь мы нажимаем кнопку по-настоящему и проверяем поведение:
//   1. согласие не отмечено → заявка НЕ отправляется, показывается понятное сообщение;
//   2. согласие отмечено → заявка уходит, в неё попадает телефон заявителя;
//   3. рядом с флажком есть ссылка на политику обработки данных.
//
// Нашлось 01.10.2026: в cabinet.html согласие было, а в tool.html (основная
// форма заявки — со страницы инструмента) заявка уходила без согласия.
//
// Запуск: SITE_BASE_URL=http://127.0.0.1:8000 node tests/order-consent-browser.cjs
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';

(async () => {
  const browser = await chromium.launch();
  let submitCalls = [];
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

    await page.route('**/*', route => {
      const req = route.request();
      const u = new URL(req.url());

      if (u.hostname.includes('photon.komoot.io')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            type: 'FeatureCollection',
            features: [{
              type: 'Feature',
              geometry: { type: 'Point', coordinates: [40.09, 47.41] },
              properties: { countrycode: 'RU', city: 'Новочеркасск', street: 'улица Ленина', housenumber: '5' }
            }]
          })
        });
      }

      if (u.hostname.endsWith('supabase.co')) {
        // Вызова submit_order быть не должно, пока согласие не отмечено.
        if (u.pathname.endsWith('/rpc/submit_order')) {
          submitCalls.push(JSON.parse(req.postData() || '{}'));
          return route.fulfill({ status: 200, contentType: 'application/json', body: '12345' });
        }
        if (u.pathname === '/auth/v1/user') {
          return route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ message: 'not authed' }) });
        }
        if (u.pathname.endsWith('/tools')) {
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify([{
              id: 'tool-consent-test',
              owner_email: 'other@mail.ru',
              status: 'active',
              active: true,
              name: 'Перфоратор Bosch тестовый',
              cat: 'power',
              price: 500,
              deposit: 2500,
              imgs: ['https://example.invalid/tool.jpg'],
              descr: 'Тестовый инструмент для проверки согласия',
              terms: 'Адрес выдачи: Новочеркасск, ул. Маресьева, 36',
              delivery: false,
              delivery_price: 0
            }])
          });
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
      }

      return route.continue();
    });

    await page.goto(BASE + '/tool.html?id=tool-consent-test', { waitUntil: 'networkidle' });

    // 1. Открываем форму заявки
    await page.locator('#orderBtn').click();
    await page.waitForSelector('#sheet.open');

    // 2. Флажок согласия на месте, рядом — политика
    assert.equal(await page.locator('#ordAgree').count(), 1, 'в форме заявки нет флажка согласия (ordAgree)');
    assert.ok(await page.locator('.sheet a[href="privacy.html"]').count() > 0,
      'рядом с флажком нет ссылки на политику обработки данных');

    // 3. Заполняем имя и телефон, но согласие НЕ отмечаем
    await page.locator('#ordName').fill('Сергей Петров');
    await page.locator('#ordPhone').fill('+7 918 000-11-22');
    await page.locator('#ordSend').click();
    await page.waitForTimeout(400);

    assert.equal(submitCalls.length, 0, 'заявка ушла в базу без согласия — это нарушение правил П4');
    const err = page.locator('#ordErr');
    assert.ok(await err.isVisible(), 'без согласия не показано понятное сообщение об ошибке');
    const errText = (await err.textContent()) || '';
    assert.ok(errText.includes('Требуется согласие'),
      `в сообщении нет объяснения про согласие: «${errText.trim()}»`);
    console.log('✔ без согласия заявка не уходит, показано сообщение');

    // 4. Отмечаем согласие — заявка уходит
    await page.locator('#ordAgree').check();
    await page.locator('#ordSend').click();
    await page.waitForFunction(() => {
      const title = document.querySelector('.sheet-h b');
      return title && title.textContent.includes('Заявка принята');
    }, { timeout: 5000 });

    assert.equal(submitCalls.length, 1, 'с отмеченным согласием заявка не ушла');
    assert.ok(String(submitCalls[0].p_phone || '').replace(/\D/g, '').includes('9180001122'),
      'в заявке нет телефона заявителя');
    console.log('✔ с согласием заявка уходит, телефон в заявке есть');

    console.log('Согласие в форме заявки проверено браузером: без флажка заявка не отправляется.');
  } catch (e) {
    console.error('ОШИБКА:', e && e.message ? e.message : e);
    await browser.close();
    process.exit(1);
  }
  await browser.close();
})();

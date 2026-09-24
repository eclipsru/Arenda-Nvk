const { chromium } = require('playwright');
const assert = require('assert');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });

  const email = 'landlord-prefill@example.invalid';
  const mockProfile = {
    owner_email: email,
    revision: 1,
    details: {
      display_name: 'Прокат Инструментов Экспресс',
      contact_name: 'Иван Петров',
      phone: '+7 999 111-22-33',
      delivery_area: 'Новочеркасск и Ростовская область',
      delivery_price: '65 ₽/км',
      delivery_terms: 'Доставка до объекта с 9:00 до 18:00',
      min_rental: '1 сутки',
      deposit_terms: 'Залог по договору аренды'
    },
    locations: [
      {
        id: 'loc-1',
        name: 'Главная база',
        city: 'Новочеркасск',
        address: 'ул. Маресьева, 36',
        hours: 'Пн-Сб 8:00-20:00'
      },
      {
        id: 'loc-2',
        name: 'Филиал Ростов',
        city: 'Ростов-на-Дону',
        address: 'пр-кт Ворошиловский, 12'
      }
    ]
  };

  await context.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url());
    let data = [];
    if (u.pathname === '/auth/v1/user') {
      data = {
        id: '40000000-0000-4000-8000-000000000001',
        email,
        user_metadata: { name: 'Иван', city: 'Новочеркасск', phone: '+79991112233' }
      };
    } else if (u.pathname.endsWith('/admins')) {
      data = [{
        email,
        role: 'admin',
        active: true,
        full_name: 'Иван Петров',
        company: 'ООО Инструмент',
        phone: '+7 999 111-22-33',
        address: 'Новочеркасск, ул. Маресьева, 36'
      }];
    } else if (u.pathname.endsWith('/landlord_profiles')) {
      data = [mockProfile];
    } else if (u.pathname.endsWith('/cats')) {
      data = [{ key: 'electro', title: 'Электроинструмент', active: true }];
    } else if (u.pathname.endsWith('/subcats')) {
      data = [{ key: 'drills', title: 'Дрели и перфораторы', cat: 'electro', active: true }];
    } else if (u.pathname.endsWith('/landlord_applications')) {
      data = [{ city: 'Новочеркасск', status: 'approved', business_type: 'company' }];
    }
    await route.fulfill({ json: data });
  });

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));

  // Set auth session
  await page.goto('http://127.0.0.1:8000/');
  await page.evaluate(em => {
    localStorage.setItem('iva_auth', JSON.stringify({
      tok: 'fake-jwt-token',
      ref: 'fake-refresh-token',
      exp: 9999999999999
    }));
    localStorage.setItem('iva_tok', JSON.stringify({
      tok: 'fake-jwt-token',
      rtok: 'fake-refresh-token',
      me: em,
      uname: 'Иван'
    }));
    localStorage.removeItem('iva_draft_ad');
  }, email);

  // 1. Check rental profile editor in account.html#prof
  await page.goto('http://127.0.0.1:8000/account.html#prof');
  await page.waitForSelector('#editRental');
  await page.locator('#editRental').click();

  // Verify website and vk are removed
  assert.equal(await page.locator('[name=website]').count(), 0, 'Website field must be removed');
  assert.equal(await page.locator('[name=vk]').count(), 0, 'VK field must be removed');

  // Verify delivery hint is present
  const delivSection = page.locator('#rp-delivery');
  const delivText = await delivSection.textContent();
  assert(delivText.includes('Данные, которые указываются в этом пункте, будут автоматически применяться во всех инструментах'), 'Delivery hint text missing');
  assert(delivText.includes('стоимость доставки разная, можете оставить этот пункт пустым'), 'Delivery empty option text missing');

  // 2. Open new.html to add tool (fresh form, no draft)
  await page.goto('http://127.0.0.1:8000/new.html');
  await page.waitForSelector('.steps');

  // Step 1: select category
  await page.locator('[data-cat="electro"]').click();
  await page.locator('[data-sub="drills"]').click();
  await page.locator('#bNext').click();

  // Step 2: Skip photos / click next
  await page.locator('#bNext').click();

  // Step 3: Fill name and description
  await page.locator('#iName').fill('Перфоратор Bosch 2-26');
  await page.locator('#iDescr').fill('Отличный рабочий перфоратор в полном комплекте');
  await page.locator('#bNext').click();

  // Step 4: Terms should be pre-filled from profile!
  const termsVal = await page.locator('#iTerms').inputValue();
  assert(termsVal.includes('Мин. срок: 1 сутки'), 'Terms should contain min_rental from profile');
  assert(termsVal.includes('Залог: Залог по договору аренды'), 'Terms should contain deposit_terms from profile');
  assert(termsVal.includes('Доставка: Доставка до объекта'), 'Terms should contain delivery_terms from profile');

  // User can edit terms
  await page.locator('#iTerms').fill('Свои особые условия для перфоратора');
  await page.locator('#iPrice').fill('700');
  await page.locator('#bNext').click();

  // Step 5: Delivery, delivery rate, city and address should be prefilled!
  const delivOn = await page.locator('[data-dl="1"]').getAttribute('class');
  assert(delivOn.includes('on'), 'Delivery toggle should be ON based on profile');

  const dpVal = await page.locator('#iDp').inputValue();
  assert.equal(dpVal, '65', 'Delivery rate should be 65 from profile "65 ₽/км"');

  const cityVal = await page.locator('#iCity').inputValue();
  assert.equal(cityVal, 'Новочеркасск', 'City should be prefilled from primary point');

  const addrVal = await page.locator('#iAddr').inputValue();
  assert.equal(addrVal, 'ул. Маресьева, 36', 'Address should be prefilled from primary point');

  // Verify user can edit any prefilled field:
  await page.locator('#iDp').fill('85');
  assert.equal(await page.locator('#iDp').inputValue(), '85');

  await page.locator('#iCity').fill('Ростов-на-Дону');
  assert.equal(await page.locator('#iCity').inputValue(), 'Ростов-на-Дону');

  await page.locator('#iAddr').fill('ул. Ленина, 10');
  assert.equal(await page.locator('#iAddr').inputValue(), 'ул. Ленина, 10');

  // Can switch delivery to pickup only
  await page.locator('[data-dl="0"]').click();
  const delivOff = await page.locator('[data-dl="0"]').getAttribute('class');
  assert(delivOff.includes('on'), 'Can switch to pickup only');
  assert.equal(await page.locator('#iDp').count(), 0, 'Delivery price hidden when pickup only');

  // 3. Test empty delivery price in profile
  mockProfile.details.delivery_price = '';
  mockProfile.details.delivery_area = '';
  await page.evaluate(() => localStorage.removeItem('iva_draft_ad'));
  await page.goto('http://127.0.0.1:8000/new.html');
  await page.waitForSelector('.steps');

  await page.locator('[data-cat="electro"]').click();
  await page.locator('[data-sub="drills"]').click();
  await page.locator('#bNext').click();
  await page.locator('#bNext').click();
  await page.locator('#iName').fill('Дрель ударная Makita HP1630');
  await page.locator('#iDescr').fill('Ударная дрель Makita для сверления кирпича и бетона');
  await page.locator('#bNext').click();
  await page.locator('#iPrice').fill('400');
  await page.locator('#bNext').click();

  // On step 5: Delivery should be "Только самовывоз" by default because profile delivery price was left empty
  const delivDefault = await page.locator('[data-dl="0"]').getAttribute('class');
  assert(delivDefault.includes('on'), 'When profile delivery is empty, default to pickup');
  assert.equal(await page.locator('#iDp').count(), 0, 'No delivery rate input initially');

  // But user can easily turn on delivery for this tool!
  await page.locator('[data-dl="1"]').click();
  assert.equal(await page.locator('#iDp').count(), 1, 'Delivery rate input appears when toggled on');
  await page.locator('#iDp').fill('50');
  assert.equal(await page.locator('#iDp').inputValue(), '50');

  assert.equal(errors.length, 0, 'No unexpected browser errors: ' + errors.join('; '));
  await browser.close();
  console.log('PASS: rental profile hint, contacts cleanup, and tool prefill verification.');
})();

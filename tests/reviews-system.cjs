const { chromium } = require('playwright');
const assert = require('assert');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });

  const renterEmail = 'renter-qa@example.invalid';
  const ownerEmail = 'landlord-qa@example.invalid';
  const toolId = '11111111-2222-3333-4444-555555555555';

  let currentReviews = [
    {
      id: 'rev-1',
      tool_id: toolId,
      author_id: '99999999-0000-0000-0000-000000000001',
      author_name: 'Алексей',
      rating: 5,
      text: 'Отличный перфоратор, пробурил все стены без проблем!',
      status: 'published',
      created_at: '2026-09-20T10:00:00Z',
      updated_at: '2026-09-20T10:00:00Z'
    }
  ];

  let userHasRented = true;
  let userReview = null;

  await context.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url());
    let data = [];

    if (u.pathname === '/auth/v1/user') {
      data = {
        id: 'user-renter-id',
        email: renterEmail,
        user_metadata: { name: 'Сергей Тестов', city: 'Новочеркасск' }
      };
    } else if (u.pathname.endsWith('/admins')) {
      data = [{
        email: ownerEmail,
        role: 'admin',
        active: true,
        full_name: 'Иван Мастеров',
        company: 'Инструмент Мастер',
        phone: '+7 908 111-22-33',
        address: 'Новочеркасск, ул. Маресьева, 36'
      }];
    } else if (u.pathname.endsWith('/tools')) {
      data = [{
        id: toolId,
        name: 'Перфоратор Bosch GBH 2-26 DRE',
        cat: 'power',
        sub: 'drills',
        price: 600,
        deposit: 3000,
        owner_email: ownerEmail,
        status: 'active',
        active: true,
        rating: 4.8,
        reviews_count: 5,
        terms: 'Адрес выдачи: Новочеркасск, ул. Маресьева, 36\nСостояние: Отличное',
        imgs: [],
        delivery: true,
        delivery_price: 40,
        created_at: '2026-09-01T10:00:00Z'
      }];
    } else if (u.pathname.endsWith('/rpc/can_user_review')) {
      return route.fulfill({
        json: {
          allowed: userHasRented,
          reason: userHasRented ? 'ok' : 'no_order',
          has_review: !!userReview,
          review: userReview
        }
      });
    } else if (u.pathname.endsWith('/rpc/submit_review')) {
      const payload = JSON.parse(r.postData());
      userReview = {
        id: 'my-rev-id',
        tool_id: toolId,
        author_id: 'user-renter-id',
        author_name: payload.p_author_name || 'Сергей Тестов',
        rating: payload.p_rating,
        text: payload.p_text,
        status: 'published',
        created_at: '2026-09-24T12:00:00Z',
        updated_at: userReview ? '2026-09-24T12:05:00Z' : '2026-09-24T12:00:00Z'
      };
      // update reviews list
      const idx = currentReviews.findIndex(x => x.id === 'my-rev-id');
      if (idx >= 0) currentReviews[idx] = userReview;
      else currentReviews.unshift(userReview);

      return route.fulfill({ json: userReview });
    } else if (u.pathname.endsWith('/rpc/get_landlord_rating')) {
      return route.fulfill({ json: { rating: 4.9, reviews_count: 14 } });
    } else if (u.pathname.endsWith('/rpc/toggle_review_status')) {
      const payload = JSON.parse(r.postData());
      const rev = currentReviews.find(x => x.id === payload.p_review_id);
      if (rev) rev.status = payload.p_status;
      return route.fulfill({ json: true });
    } else if (u.pathname.endsWith('/reviews')) {
      const enriched = currentReviews.map(r => ({
        ...r,
        tools: { name: 'Перфоратор Bosch GBH 2-26 DRE', owner_email: ownerEmail }
      }));
      return route.fulfill({ json: enriched });
    } else if (u.pathname.endsWith('/landlord_profiles')) {
      data = [{ details: { display_name: 'Инструмент Мастер' }, locations: [] }];
    } else if (u.pathname.endsWith('/order_parts')) {
      data = [];
    } else if (u.pathname.endsWith('/orders')) {
      data = [];
    }
    await route.fulfill({ json: data });
  });

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));

  // --- ТЕСТ 1: Отображение рейтинга на главной / в каталоге ---
  await page.goto('http://127.0.0.1:8000/');
  await page.waitForSelector('.ad');
  const adOwnerText = await page.locator('.ad .ad-meta').first().textContent();
  assert(adOwnerText.includes('4.8') || adOwnerText.includes('★'), 'Card should show star rating or new badge');

  // --- ТЕСТ 2: Страница инструмента без авторизации ---
  await page.goto('http://127.0.0.1:8000/tool.html?id=' + toolId);
  await page.waitForSelector('#reviewsBlock');
  assert((await page.locator('#reviewFormArea').textContent()).includes('Войдите в аккаунт'), 'Unauthenticated should see login hint');

  // Проверка рейтинга проката в боковой колонке
  await page.waitForFunction(() => document.querySelector('#landlordRatingBlock') && document.querySelector('#landlordRatingBlock').textContent.includes('4.9'));
  const llText = await page.locator('#landlordRatingBlock').textContent();
  assert(llText.includes('4.9') && llText.includes('14 отзывов'), 'Landlord rating block displayed correctly');

  // --- ТЕСТ 3: Авторизован, но ещё НЕ брал в аренду этот инструмент ---
  userHasRented = false;
  await page.evaluate(em => {
    localStorage.setItem('iva_auth', JSON.stringify({ tok: 'test', ref: 'test', exp: 9999999999999 }));
    localStorage.setItem('iva_tok', JSON.stringify({ tok: 'test', rtok: 'test', me: em, uname: 'Сергей' }));
  }, renterEmail);
  await page.reload();
  await page.waitForSelector('#reviewFormArea');
  assert((await page.locator('#reviewFormArea').textContent()).includes('после оформления заявки'), 'User without order should see rent requirement');

  // --- ТЕСТ 4: Авторизован И брал в аренду -> Форма отзыва ---
  userHasRented = true;
  await page.reload();
  await page.waitForSelector('#submitReviewBtn');

  // Выбираем 5 звёзд, пишем текст и нажимаем «Опубликовать отзыв»
  await page.locator('#reviewTextInput').fill('Шикарный мощный перфоратор, пробурил 40 отверстий на ура!');
  await page.locator('#submitReviewBtn').click();

  // Должен появиться блок «Ваш отзыв» с карандашиком ✏️
  await page.waitForSelector('#openEditReviewBtn');
  const myReviewText = await page.locator('#reviewFormArea').textContent();
  assert(myReviewText.includes('Ваш отзыв'), 'Should show "Ваш отзыв" card');
  assert(myReviewText.includes('Шикарный мощный перфоратор'), 'Review text matches');

  // --- ТЕСТ 5: Редактирование отзыва по клику на карандашик ---
  await page.locator('#openEditReviewBtn').click();
  await page.waitForSelector('#submitReviewBtn');
  assert.equal(await page.locator('#submitReviewBtn').textContent(), 'Сохранить изменения');
  assert.equal(await page.locator('#reviewTextInput').inputValue(), 'Шикарный мощный перфоратор, пробурил 40 отверстий на ура!');

  // Меняем текст и сохраняем
  await page.locator('#reviewTextInput').fill('Дополняю отзыв: инструмент чистый, смазанный и в кейсе.');
  await page.locator('#submitReviewBtn').click();

  await page.waitForSelector('#openEditReviewBtn');
  assert((await page.locator('#reviewFormArea').textContent()).includes('Дополняю отзыв: инструмент чистый'), 'Edited text updated');

  // --- ТЕСТ 6: Кабинет арендодателя -> Вкладка «Отзывы» ---
  await page.evaluate(em => {
    localStorage.setItem('iva_tok', JSON.stringify({ tok: 'test', rtok: 'test', me: em, uname: 'Иван' }));
  }, ownerEmail);

  await page.goto('http://127.0.0.1:8000/account.html#reviews');
  await page.waitForSelector('.tabs');
  assert(await page.locator('[data-tab="reviews"]').count() > 0, 'Reviews tab must exist for landlord');
  await page.locator('[data-tab="reviews"]').click();
  await page.waitForSelector('.review-card');
  const accRevText = await page.locator('.review-card').first().textContent();
  assert(accRevText.includes('перфоратор') || accRevText.includes('Перфоратор'), 'Landlord sees tool review');

  assert.equal(errors.length, 0, 'No unexpected browser errors: ' + errors.join('; '));
  await browser.close();
  console.log('PASS: reviews and rating system fully verified!');
})();

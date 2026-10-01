// Браузерная проверка пустых состояний (этап П4): что человек видит, когда объявлений нет.
//
// Проверяем на живых страницах (каталог и главная) при пустом каталоге:
//   1. вместо тупика — объяснение и три пути: позвонить, посмотреть пункты проката, разместить объявление;
//   2. ссылка на пункты проката ведёт в свой город (если у города есть страница) и открывается;
//   3. с включёнными фильтрами добавляется кнопка «Сбросить фильтры», и она работает;
//   4. нажатие на «Позвонить» считается как обращение (kind=lead), а показы страниц — нет;
//   5. ошибок в консоли нет, на телефоне 390×844 нет горизонтальной прокрутки.
//
// Запуск (нужен локальный сервер проекта): SITE_BASE_URL=http://127.0.0.1:8000 node tests/empty-states-browser.cjs
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
  const page = await context.newPage();

  const errors = [];
  const sent = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 120)); });
  page.on('response', r => {
    if (r.status() >= 400 && !r.url().includes('directory_clicks')) {
      errors.push(r.status() + ' ' + r.url().slice(-70));
    }
  });

  // Все обращения к базе подменяем: каталог пуст (объявлений нет), обращения записываем.
  // Мокаем по адресу базы целиком — иначе часть запросов уходит в сеть и демо-данные
  // из assets/data.js остаются на странице, а пустое состояние просто не появляется.
  const mockSupabase = async route => {
    const req = route.request();
    const url = req.url();
    if (!url.includes('supabase.co')) return route.continue();
    if (url.includes('/rest/v1/directory_clicks')) {
      sent.push(JSON.parse(req.postData() || '{}'));
      return route.fulfill({ status: 201, body: '' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  };
  await page.route('**/*', mockSupabase);

  for (const url of ['catalog.html', 'index.html']) {
    await page.goto(`${BASE}/${url}`, { waitUntil: 'load' });
    await page.waitForTimeout(1200);

    const grid = page.locator('#grid');
    const text = await grid.innerText();
    assert.match(text, /Объявлений пока нет/,
      `${url}: нет честного объяснения пустого каталога. Сейчас показано: ${text.slice(0, 120)}`);
    assert.match(text, /Арендодатели ещё не разместили объявления/,
      `${url}: нет пояснения, почему пусто — человек может подумать, что сломалось`);

    // Звонок нам — помечен как обращение к площадке
    const call = grid.locator('a[href^="tel:"][data-lead="1"]').first();
    assert.equal(await call.count(), 1, `${url}: нет кнопки «Позвонить — подберём инструмент»`);

    // Ссылка на пункты проката: свой город (если страница есть) и она открывается
    const dir = grid.locator('a', { hasText: 'Пункты проката' }).first();
    const href = await dir.getAttribute('href');
    assert.ok(href && href.startsWith('city/'), `${url}: ссылка на пункты проката ведёт не туда: ${href}`);
    const resp = await page.request.get(`${BASE}/${href}`);
    assert.equal(resp.status(), 200, `${url}: ссылка на пункты проката (${href}) не открывается`);

    // Предложение арендодателю
    assert.ok(await grid.locator('a[href="landlord-register.html"]').count() > 0,
      `${url}: нет предложения владельцу инструмента разместить объявление`);

    // Обращение считается именно как обращение, а показов с этих страниц нет
    const before = sent.length;
    await call.click({ noWaitAfter: true });
    await page.waitForTimeout(400);
    const last = sent[sent.length - 1];
    assert.equal(sent.length - before, 1, `${url}: нажатие на кнопку звонка не посчиталось`);
    assert.equal(last.kind, 'lead', `${url}: обращение записано с неверным типом: ${last.kind}`);
    assert.equal(last.phone, '79081732475', `${url}: записан не наш номер: ${last.phone}`);
    assert.ok(!sent.some(s => s.kind === 'view' && s.page === url),
      `${url}: показы страниц не должны попадать в статистику обращений`);
  }

  // Ветка «не подошло по условиям»: в каталоге есть объявление, но не в выбранном городе.
  // Здесь важно другое: человек видит объяснение и кнопку сброса фильтров.
  {
    const busy = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
    const bp = await busy.newPage();
    await bp.route('**/*', async route => {
      const url = route.request().url();
      if (!url.includes('supabase.co')) return route.continue();
      if (url.includes('/rest/v1/tools')) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{
          id: 'test-1', name: 'Перфоратор тестовый', cat: 'instrument', sub: 'perf',
          price: 900, deposit: 3000, descr: '', pickup_city: 'Азов', status: 'active', active: true,
          owner_email: 'owner@example.test', imgs: [], terms: 'Состояние: Рабочее'
        }]) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    await bp.goto(`${BASE}/catalog.html?city=Азов&q=вертолёт`, { waitUntil: 'load' });
    await bp.waitForTimeout(1200);
    const withFilters = await bp.locator('#grid').innerText();
    assert.match(withFilters, /ничего не нашлось/i,
      'когда объявления есть, но не подошли под условия — нужно объяснение «ничего не нашлось». ' +
      `Сейчас показано: ${withFilters.slice(0, 100)}`);
    const reset = bp.locator('#eReset');
    assert.equal(await reset.count(), 1, 'с включёнными условиями нужна кнопка «Сбросить фильтры»');
    await reset.click();
    await bp.waitForTimeout(700);
    assert.match(await bp.locator('#grid').innerText(), /Перфоратор тестовый/,
      'сброс условий должен показать объявления, которые есть');
    await busy.close();
  }

  // Телефон 390×844 — без горизонтальной прокрутки
  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const mp = await mobile.newPage();
  await mp.route('**/*', mockSupabase);
  await mp.goto(`${BASE}/catalog.html`, { waitUntil: 'load' });
  await mp.waitForTimeout(900);
  assert.equal(await mp.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2), false,
    'на телефоне пустое состояние даёт горизонтальную прокрутку');

  assert.deepEqual(errors, [], `ошибки на страницах: ${errors.join(' | ')}`);
  await browser.close();
  console.log('Пустые состояния проверены в браузере: объяснение, звонок, пункты проката, размещение объявления; ' +
    'обращения считаются, показы — нет, фильтры сбрасываются, на телефоне всё помещается.');
})().catch(async err => {
  console.error(err && err.message ? err.message : err);
  // Браузер закрываем обязательно: иначе процесс висит до внешнего таймаута
  try { await browser.close(); } catch (e) {}
  process.exit(1);
});

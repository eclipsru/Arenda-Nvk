// «Обращения» и счётчик скачиваний на сайте (просьба владельца 07.11.2026, SQL 20261128_appeals.sql).
// База Supabase подменена. Проверяем:
//  1. app.html в мобильной версии: счётчик «как в Google Play» (7 → «7+», 1234 → «1 234+»), нет скачиваний / нет SQL —
//     счётчика нет; на компьютере (от 1000px) его нет; «Установить» — одно скачивание с браузера (iva_download_hit
//     со случайным номером браузера, повторное нажатие базу не трогает).
//  2. tool.html: «Показать телефон» и ссылка «tel:» с номером проката — iva_call_tap (инструмент, прокат, «site»),
//     один раз за показ страницы; ошибка базы страницу не ломает.
const { chromium } = require('playwright'); const assert = require('assert'); const path = require('path');
const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000'; const SHOT = process.env.IVA_SHOT_DIR || '';
const ANDROID = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';
const REL = [{ version_code: 214, version_name: '10.140', apk_url: 'ProkatInstrumenta-10.140.apk', reinstall: false, notes: 'Обращения', confirmed_at: '2026-11-07T10:00:00Z' }];
const TID = '11111111-2222-3333-4444-555555555555';

(async () => {
  const b = await chromium.launch();
  async function app(o) {
    const c = await b.newContext({ viewport: o.vp || { width: 390, height: 844 }, userAgent: ANDROID, timezoneId: 'Europe/Moscow', locale: 'ru-RU' });
    o.calls = [];
    await c.route('https://*.supabase.co/**', async route => {
      const r = route.request(), p = new URL(r.url()).pathname; let body = null; try { body = r.postDataJSON(); } catch (e) {}
      if (p.startsWith('/rest/v1/rpc/')) o.calls.push({ fn: p.slice(13), body });
      if (/iva_download/.test(p) && o.noSql) return route.fulfill({ status: 404, json: { code: 'PGRST202', message: 'Could not find the function' } });
      let data = [];
      if (p.endsWith('/app_releases')) data = REL;
      else if (p.endsWith('/rpc/iva_app_reviews_stats')) data = [{ total: 0, avg: 0, s1: 0, s2: 0, s3: 0, s4: 0, s5: 0 }];
      else if (p.endsWith('/rpc/iva_download_count')) data = o.count;
      else if (p.endsWith('/rpc/iva_download_hit')) data = o.hit;
      await route.fulfill({ json: data });
    });
    const p = await c.newPage(); const errors = []; p.on('pageerror', e => errors.push(e.message));
    await p.goto(BASE + '/app.html', { waitUntil: 'domcontentloaded' });
    await p.waitForFunction(() => document.getElementById('apInstall').getAttribute('href') === 'ProkatInstrumenta-10.140.apk');
    return { c, p, errors };
  }
  const shown = p => p.evaluate(() => { const e = document.getElementById('stDl'); return !!e && !e.hidden && getComputedStyle(e).display !== 'none'; });

  // 1а. телефон: 7 скачиваний → «7+»; «Установить» → одно скачивание, ответ базы — новое число
  {
    const o = { count: 7, hit: 10 }; const { c, p, errors } = await app(o);
    await p.waitForFunction(() => document.getElementById('stDlV').textContent === '7+');
    assert.equal(await shown(p), true, 'в мобильной версии счётчик виден');
    assert.equal(await p.locator('#stDl span').textContent(), 'скачиваний');
    if (SHOT) await p.screenshot({ path: path.join(SHOT, 'appeals-dl-390.png') });
    await p.locator('#apInstall').click();
    await p.waitForFunction(() => document.getElementById('stDlV').textContent === '10+');
    const hits = o.calls.filter(x => x.fn === 'iva_download_hit');
    assert.equal(hits.length, 1);
    assert.match(hits[0].body.p_dev, /^w[0-9a-f]{24}$/, 'случайный номер браузера, без данных человека');
    assert.deepEqual(Object.keys(hits[0].body), ['p_dev'], 'в базу — только номер браузера');
    const dev = await p.evaluate(() => localStorage.getItem('iva_dl_dev'));
    assert.equal(dev, hits[0].body.p_dev);
    await p.evaluate(() => closeSheet(true));
    await p.locator('#apInstall').click(); await p.waitForTimeout(400);
    assert.equal(o.calls.filter(x => x.fn === 'iva_download_hit').length, 1, 'повторное скачивание с того же браузера не считается');
    assert.deepEqual(errors, []);
    await c.close();
  }
  // 1б. большие числа — пробел между тысячами
  {
    const o = { count: 1234 }; const { c, p } = await app(o);
    await p.waitForFunction(() => document.getElementById('stDlV').textContent === '1 234+');
    await c.close();
  }
  // 1в. скачиваний нет / нет SQL — счётчика нет, страница работает
  for (const o of [{ count: 0, hit: 0 }, { noSql: true }]) {
    const { c, p, errors } = await app(o);
    await p.waitForFunction(() => /версия 10\.140/.test(document.getElementById('apInstall').textContent));
    await p.waitForTimeout(500);
    assert.equal(await shown(p), false, 'нет скачиваний или нет SQL — счётчик спрятан');
    await p.locator('#apInstall').click(); await p.waitForTimeout(400);
    assert.equal(await shown(p), false);
    if (o.noSql) assert.equal(await p.evaluate(() => localStorage.getItem('iva_dl_done')), null, 'неудачная запись — попробуем при следующем скачивании');
    assert.deepEqual(errors, []);
    await c.close();
  }
  // 1г. компьютер — счётчика нет (только мобильная версия)
  {
    const o = { count: 50 }; const { c, p } = await app(Object.assign(o, { vp: { width: 1280, height: 900 } }));
    await p.waitForFunction(() => document.getElementById('stDlV').textContent === '50+');
    assert.equal(await shown(p), false, 'на компьютере счётчик скрыт');
    await c.close();
  }

  // 2. страница инструмента: нажатие «Показать телефон» / «tel:» → iva_call_tap
  for (const fail of [false, true]) {
    const c = await b.newContext({ viewport: { width: 390, height: 844 }, locale: 'ru-RU', timezoneId: 'Europe/Moscow' });
    const taps = [];
    await c.route('https://*.supabase.co/**', async route => {
      const r = route.request(), u = new URL(r.url()), sel = u.searchParams.get('select') || ''; let data = [];
      if (u.pathname.endsWith('/rpc/iva_call_tap')) {
        taps.push({ body: r.postDataJSON(), auth: r.headers()['authorization'] || '', key: r.headers()['apikey'] || '' });
        return fail ? route.fulfill({ status: 404, json: { code: 'PGRST202' } }) : route.fulfill({ json: true });
      }
      if (u.pathname.endsWith('/tools')) {
        data = /kind/.test(sel) ? [{ kind: null }] : [{ id: TID, name: 'Перфоратор Makita HR2470', cat: 'power', sub: 'hammers', descr: 'Проверка', price: 900, deposit: 3000,
          imgs: [], img: '', delivery: false, delivery_price: 0, terms: '', pickup_city: 'Новочеркасск', pickup_address: '', owner_email: 'owner@example.invalid',
          status: 'active', active: true, rating: 0, reviews_count: 0, created_at: '2026-10-10T10:00:00Z' }];
      } else if (u.pathname.endsWith('/landlord_profiles')) data = [{ details: { display_name: 'Прокат Тест', phone: '+7 900 111-22-33' }, locations: [] }];
      await route.fulfill({ json: data });
    });
    const p = await c.newPage(); const errors = []; p.on('pageerror', e => errors.push(e.message));
    await p.goto(BASE + '/tool.html?id=' + TID);
    await p.waitForSelector('#phoneBtn', { timeout: 15000 });
    await p.locator('#phoneBtn').scrollIntoViewIfNeeded();
    await p.locator('#phoneBtn').click();
    await p.waitForFunction(() => document.getElementById('phoneBtn').textContent === '+7 900 111-22-33');
    await p.waitForTimeout(300);
    assert.equal(taps.length, 1, 'нажатие «Показать телефон» — одно обращение');
    assert.deepEqual(taps[0].body, { p_point: null, p_tool: TID, p_owner: 'owner@example.invalid', p_src: 'site' });
    assert.ok(taps[0].key && !/test-token/.test(taps[0].auth), 'без ключа входа: звонят и гости');
    // ссылка «tel:» с тем же номером — тот же инструмент, второй раз не шлём
    await p.evaluate(() => { const a = document.createElement('a'); a.href = 'tel:+79001112233'; a.id = 'tt'; a.textContent = 'x'; a.addEventListener('click', e => e.preventDefault()); document.body.appendChild(a); });
    await p.locator('#tt').click(); await p.waitForTimeout(300);
    assert.equal(taps.length, 1, 'один раз за показ страницы');
    assert.equal(await p.evaluate(() => JSON.stringify(IvaCallTap.body({ tool: 'x', owner: '' }))), 'null', 'нечего записать — не шлём');
    assert.deepEqual(errors, [], fail ? 'нет SQL — страница не ломается' : 'без ошибок');
    if (SHOT && !fail) await p.screenshot({ path: path.join(SHOT, 'appeals-tool-390.png') });
    await c.close();
  }
  await b.close();
  console.log('Сайт: счётчик скачиваний «как в Google Play» (7+, 1 234+, нет/без SQL — скрыт, на компьютере скрыт, одно скачивание с браузера), «Позвонить» на странице инструмента → iva_call_tap один раз, без данных человека');
})().catch(e => { console.error('✘', e.message); process.exit(1); });

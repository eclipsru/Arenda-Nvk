/* 10.82: справочник моделей на сайте — подсказки моделей, «Модель» с брендами, характеристики, запись model_id и specs.
   new.html: полный путь до публикации; база без таблиц справочника (SQL 10.82 не применён) — форма как в 10.81;
   база не приняла модель — объявление всё равно публикуется. cabinet.html: форма «Новый инструмент».
   Справочник (модели, бренды, поля) и слова видов — из настоящих миграций. */
const { chromium } = require('playwright');
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MIG = path.join(ROOT, 'supabase/migrations');
const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';

const { kindsFromSql, dirFromSql } = require('./model-fixtures.cjs');   /* 10.84: общий разбор миграций */

const KINDS = kindsFromSql();
const DIR = dirFromSql();
const CATS = [{ key: 'power', title: 'Электроинструмент' }, { key: 'garden', title: 'Садовая техника' }];
const SUBS = [{ key: 'drills', cat: 'power', title: 'Дрели, шуруповёрты и гайковёрты' },
              { key: 'hammers', cat: 'power', title: 'Перфораторы' },
              { key: 'trimmers', cat: 'garden', title: 'Пилы и кусторезы' }];
const byTitle = t => { const m = DIR.models.find(x => x.title === t); assert.ok(m, 'нет модели ' + t); return m; };

/* mode: 'full' — всё есть; 'nodir' — таблиц справочника нет; 'reject' — база не принимает model_id / specs */
async function routeSupabase(context, email, mode, posted, calls) {
  await context.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url());
    let data = [];
    if (u.pathname === '/auth/v1/user') {
      data = { id: '40000000-0000-4000-8000-000000000002', email, email_confirmed_at: '2026-01-01T00:00:00.000Z',
               user_metadata: { name: 'Иван', city: 'Новочеркасск' } };
    } else if (u.pathname.endsWith('/admins')) data = [{ email, role: 'admin', active: true, full_name: 'Иван Петров', address: '' }];
    else if (u.pathname.endsWith('/cats')) data = CATS;
    else if (u.pathname.endsWith('/subcats')) data = SUBS;
    else if (u.pathname.endsWith('/iva_kinds')) data = KINDS;
    else if (/\/iva_(models|brands|spec_fields)$/.test(u.pathname)) {
      calls.push(u.pathname + u.search);
      if (mode === 'nodir') return route.fulfill({ status: 404, json: { code: '42P01', message: 'relation does not exist' } });
      if (u.pathname.endsWith('/iva_brands')) data = DIR.brands;
      else if (u.pathname.endsWith('/iva_spec_fields')) data = DIR.fields;
      else {
        assert.equal(u.searchParams.get('active'), 'eq.true', 'модели — только активные');
        assert.equal(u.searchParams.get('limit'), '1000', 'модели — страницами по 1000');
        const off = +u.searchParams.get('offset');
        data = DIR.models.slice(off, off + 1000);
      }
    } else if (u.pathname.endsWith('/tools') && r.method() === 'POST') {
      const body = JSON.parse(r.postData() || '{}');
      posted.push(body);
      if (mode === 'reject' && (body.model_id || body.specs))
        return route.fulfill({ status: 400, json: { code: 'PGRST204', message: "Could not find the 'model_id' column of 'tools'" } });
      data = [Object.assign({ id: '50000000-0000-4000-8000-000000000001' }, body)];
    } else if (u.pathname.endsWith('/landlord_applications')) data = [{ city: 'Новочеркасск', status: 'approved', business_type: 'company' }];
    await route.fulfill({ json: data });
  });
}

async function openNew(page, email) {
  await page.goto(BASE + '/offer.html');   /* лёгкая страница того же сайта — только чтобы записать сессию */
  await page.evaluate(em => {
    localStorage.setItem('iva_auth', JSON.stringify({ tok: 'fake-jwt-token', ref: 'fake-refresh-token', exp: 9999999999999 }));
    localStorage.setItem('iva_tok', JSON.stringify({ tok: 'fake-jwt-token', rtok: 'fake-refresh-token', me: em, uname: 'Иван' }));
    localStorage.removeItem('iva_draft_ad');
    localStorage.removeItem('iva_models_v2');
  }, email);
  await page.goto(BASE + '/new.html');
  await page.waitForSelector('#iWhat');
}

async function publishFromStep1(page) {
  await page.locator('#bNext').click();
  await page.locator('#bNext').click();
  await page.locator('#iDescr').fill('Инструмент в отличном состоянии, полный комплект, проверен перед выдачей');
  await page.locator('#bNext').click();
  await page.locator('#iPrice').fill('900');
  await page.locator('#bNext').click();
  await page.locator('#iCity').fill('Новочеркасск');
  await page.locator('#bSave').click();
  await page.waitForFunction(() => !document.getElementById('bSave'), null, { timeout: 15000 });
}

async function runNew(mode) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const email = 'landlord-models@example.invalid';
  const posted = [], calls = [];
  await routeSupabase(context, email, mode, posted, calls);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await openNew(page, email);
  const on = sel => page.locator(sel + '.on').count();
  const hr = byTitle('Перфоратор Makita HR2470'), gbh = byTitle('Перфоратор Bosch GBH 2-26');

  if (mode === 'nodir') {
    // Таблиц нет — ни подсказок, ни «Модели»: форма как в 10.81, в запросе нет model_id / specs
    await page.waitForTimeout(500);
    await page.locator('#iWhat').fill('Перфоратор SDS-plus Makita HR2470');
    await page.waitForSelector('[data-kind="rotary-sds-plus"].on', { timeout: 4000 });
    assert.equal(await page.locator('.msug.on').count(), 0, 'без справочника подсказок нет');
    assert.equal(await page.locator('#iModel').count() + await page.locator('[data-spec]').count(), 0, 'без справочника нет «Модели» и характеристик');
    assert.ok(calls.length >= 1 && calls.length <= 3, 'запрос справочника был и не повторялся по кругу: ' + calls.length);
    await publishFromStep1(page);
    assert.equal(posted.length, 1, 'объявление отправлено');
    assert.ok(!('model_id' in posted[0]) && !('specs' in posted[0]), 'без справочника model_id и specs не отправляются');
    assert.equal(posted[0].kind, 'rotary-sds-plus', 'вид записан, как в 10.81');
    assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));
    await browser.close();
    return;
  }

  // 1. Подсказки моделей под «Что сдаёте»
  // справочник загружен — подсказки подключены к полю (сам блок #mSugWhat есть в разметке сразу)
  await page.waitForFunction(() => document.getElementById('mSugWhat') && document.getElementById('iWhat').dataset.msug === '1', null, { timeout: 15000 });
  await page.locator('#iWhat').fill('makita hr2470');
  await page.waitForSelector('#mSugWhat.on', { timeout: 4000 });
  const sug = await page.locator('#mSugWhat button').allTextContents();
  assert.deepEqual(sug, ['Перфоратор Makita HR2470', 'Перфоратор Makita HR2470FT'], 'подсказки как в tests/model-search-cases.txt');
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'iWhat', 'фокус в поле');
  const pages = calls.filter(c => c.startsWith('/rest/v1/iva_models')).length;
  assert.equal(pages, Math.ceil((DIR.models.length + 1) / 1000), 'модели загружены страницами по 1000');

  // 2. Выбор: название, категория, подкатегория, вид, модель, характеристики. Касание медленное (палец держат 300 мс):
  //    подсказки не должны исчезнуть до отпускания
  const bb = await page.locator('#mSugWhat button').first().boundingBox();
  await page.mouse.move(bb.x + 20, bb.y + bb.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(300);
  await page.mouse.up();
  await page.waitForSelector('#iModel');
  assert.equal(await page.locator('#iWhat').inputValue(), hr.title, 'название — как у модели');
  assert.equal(await on('[data-cat="power"]') + await on('[data-sub="hammers"]') + await on('[data-kind="rotary-sds-plus"]'), 3, 'категория, подкатегория и вид модели');
  assert.equal(await page.locator('#iModel').inputValue(), 'Makita HR2470', 'поле «Модель»');
  for (const k of Object.keys(hr.specs)) {
    const el = page.locator('[data-spec="' + k + '"]');
    assert.equal(await el.inputValue(), String(hr.specs[k]), 'характеристика ' + k + ' подставлена');
  }
  assert.ok(Object.keys(hr.specs).length >= 2 && 'power' in hr.specs, 'у HR2470 есть мощность');
  assert.ok(await page.locator('.mbrands button[data-mbrand="Makita"]').count() === 1 && await page.locator('.mbrands button').count() > 5, 'бренды вида');

  if (process.env.SHOT && mode === 'full') await page.screenshot({ path: process.env.SHOT + '/new-2.png', fullPage: true });
  // 3. Мощность правим руками; выбираем другую модель через бренд и поле «Модель»
  await page.locator('[data-spec="power"]').fill('999');
  await page.locator('[data-mbrand="Bosch"]').click();
  assert.equal(await on('[data-mbrand="Bosch"]'), 1, 'бренд выбран');
  await page.locator('#iModel').fill('');
  await page.waitForSelector('#mSugModel.on', { timeout: 4000 });
  const all = await page.locator('#mSugModel button').allTextContents();
  assert.ok(all.length >= 3 && all.every(t => t.startsWith('Bosch ')), 'пустое поле с брендом — модели этого бренда: ' + all.slice(0, 3).join(' | '));
  await page.locator('#iModel').fill('gbh 2-26');
  await page.waitForFunction(() => { const b = document.querySelector('#mSugModel.on button'); return b && b.textContent === 'Bosch GBH 2-26'; }, null, { timeout: 4000 });
  const ms = await page.locator('#mSugModel button').allTextContents();
  assert.ok(ms.length >= 1 && ms.every(t => t.startsWith('Bosch ')), 'подсказки модели — только Bosch этого вида: ' + ms.join(' | '));
  assert.equal(ms[0], 'Bosch GBH 2-26', 'первая — GBH 2-26');
  await page.locator('#mSugModel button').first().click();
  await page.waitForFunction(t => document.getElementById('iWhat').value === t, gbh.title, { timeout: 4000 });
  assert.equal(await page.locator('[data-spec="power"]').inputValue(), '999', 'ручная мощность не перезаписана');
  for (const k of Object.keys(gbh.specs)) if (k !== 'power')
    assert.equal(await page.locator('[data-spec="' + k + '"]').inputValue(), String(gbh.specs[k]), 'характеристика ' + k + ' — новой модели');

  // 4. Публикация: model_id и specs уходят вместе с объявлением
  await publishFromStep1(page);
  if (mode === 'reject') {
    assert.equal(posted.length, 2, 'база не приняла модель — вторая попытка без неё');
    assert.ok(posted[0].model_id && !('model_id' in posted[1]) && !('specs' in posted[1]), 'вторая попытка — без model_id и specs');
    assert.equal(posted[1].kind, 'rotary-sds-plus', 'вид остался');
    assert.equal(await page.locator('text=Объявление опубликовано').count() + await page.locator('text=Отправлено на проверку').count(), 1, 'объявление опубликовано');
  } else {
    assert.equal(posted.length, 1, 'одно объявление отправлено');
    assert.equal(posted[0].model_id, gbh.id, 'model_id — выбранная модель');
    assert.equal(posted[0].name, gbh.title, 'название — как у модели');
    assert.equal(posted[0].kind, 'rotary-sds-plus', 'вид записан');
    assert.equal(posted[0].specs.power, 999, 'ручная мощность — числом');
    for (const k of Object.keys(gbh.specs)) if (k !== 'power') assert.equal(posted[0].specs[k], gbh.specs[k], 'specs.' + k);
    assert.ok(Object.keys(posted[0].specs).every(k => DIR.fields.some(f => f.kind === 'rotary-sds-plus' && f.key === k)), 'specs — только поля вида');
  }
  assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));

  // 5. Название переписали (вид тот же) — модель снимается, характеристики остаются (только для полного режима)
  if (mode === 'full') {
    posted.length = 0;
    await openNew(page, email);
    await page.waitForFunction(() => document.getElementById('mSugWhat') && document.getElementById('iWhat').dataset.msug === '1', null, { timeout: 15000 });
    await page.locator('#iWhat').fill('makita hr2470');
    await page.waitForSelector('#mSugWhat.on', { timeout: 4000 });
    await page.locator('#mSugWhat button').first().click();
    await page.waitForSelector('#iModel');
    await page.locator('#iWhat').fill('Перфоратор SDS-plus Makita б/у');
    await page.waitForFunction(() => document.getElementById('iModel') && document.getElementById('iModel').value === '', null, { timeout: 4000 });
    assert.equal(await page.locator('[data-spec="power"]').inputValue(), String(hr.specs.power), 'характеристики остались');
    await publishFromStep1(page);
    assert.ok(!('model_id' in posted[0]) && posted[0].specs && posted[0].specs.power === hr.specs.power, 'без модели, с характеристиками');
    assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));
  }
  await browser.close();
}

async function runCabinet(mode) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const email = 'landlord-models@example.invalid';
  const posted = [], calls = [];
  await context.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url());
    let data = [];
    if (u.pathname === '/auth/v1/user') data = { id: '40000000-0000-4000-8000-000000000002', email, email_confirmed_at: '2026-01-01T00:00:00.000Z', user_metadata: { name: 'Иван' } };
    else if (u.pathname.endsWith('/cats')) data = CATS;
    else if (u.pathname.endsWith('/subcats')) data = SUBS;
    else if (u.pathname.endsWith('/admins')) data = [{ email, role: 'admin', active: true }];
    else if (u.pathname.endsWith('/iva_kinds')) data = KINDS;
    else if (u.pathname.endsWith('/iva_brands')) data = DIR.brands;
    else if (u.pathname.endsWith('/iva_spec_fields')) data = DIR.fields;
    else if (u.pathname.endsWith('/iva_models')) { calls.push(u.search); const off = +u.searchParams.get('offset'); data = DIR.models.slice(off, off + 1000); }
    else if (u.pathname.endsWith('/tools') && r.method() === 'POST') {
      const body = JSON.parse(r.postData() || '{}');
      posted.push(body);
      if (mode === 'reject' && (body.model_id || body.specs))
        return route.fulfill({ status: 400, json: { code: 'PGRST204', message: "Could not find the 'model_id' column of 'tools'" } });
      data = null;
    }
    await route.fulfill(data === null ? { status: 201, body: '' } : { json: data });
  });
  /* cabinet.html без #resendLink падает до входа (отдельная находка, передана владельцу) — как в kind-guess-browser.cjs */
  await context.route(BASE + '/cabinet.html', async route => {
    const res = await route.fetch();
    let html = await res.text();
    if (!/id="resendLink"/.test(html)) html = html.replace('<body>', '<body><a id="resendLink" class="hide"></a>');
    await route.fulfill({ response: res, body: html });
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(BASE + '/offer.html');
  await page.evaluate(() => { localStorage.setItem('iva_auth', JSON.stringify({ tok: 'fake-jwt-token', ref: 'fake-refresh-token', exp: 9999999999999 })); localStorage.removeItem('iva_models_v2'); });
  await page.goto(BASE + '/cabinet.html');
  await page.waitForFunction(() => typeof toolForm === 'function' && S.me && S.cats && S.cats.length, null, { timeout: 15000 });
  await page.evaluate(() => toolForm());
  await page.waitForSelector('#fName');
  const ms = byTitle('Бензопила Stihl MS 180');
  await page.waitForFunction(() => document.getElementById('fBrands') && window.ivaModelsLoad && document.querySelectorAll('#fNSug').length, null, { timeout: 8000 });
  await page.waitForTimeout(800);
  await page.locator('#fName').fill('бензопила штиль 180');
  await page.waitForSelector('#fNSug.on', { timeout: 4000 });
  assert.equal((await page.locator('#fNSug button').allTextContents())[0], ms.title, 'кабинет: подсказка модели');
  await page.locator('#fNSug button').first().click();
  await page.waitForFunction(() => document.getElementById('fKind').value === 'chainsaw', null, { timeout: 4000 });
  assert.equal(await page.locator('#fName').inputValue(), ms.title, 'кабинет: название как у модели');
  assert.equal(await page.locator('#fCat').inputValue() + '/' + await page.locator('#fSub').inputValue(), 'garden/trimmers', 'кабинет: категория и подкатегория');
  assert.equal(await page.locator('#fModelBox').isVisible(), true, 'кабинет: «Модель» видна');
  assert.equal(await page.locator('#fModel').inputValue(), 'Stihl MS 180', 'кабинет: поле «Модель»');
  for (const k of Object.keys(ms.specs)) assert.equal(await page.locator('#fSpecs [data-spec="' + k + '"]').inputValue(), String(ms.specs[k]), 'кабинет: ' + k);
  assert.ok(await page.locator('#fBrands .chip').count() > 3, 'кабинет: бренды вида');
  if (process.env.SHOT && mode === 'full') await page.screenshot({ path: process.env.SHOT + '/cab-1.png', fullPage: true });
  // длину шины правим руками, потом выбираем другую модель через бренд — правка остаётся
  const ms2 = byTitle('Бензопила Stihl MS 250');
  await page.locator('#fSpecs [data-spec="bar"]').fill('38');
  await page.locator('#fBrands [data-mbrand="Stihl"]').click();
  await page.locator('#fModel').fill('');
  await page.waitForSelector('#fMSug.on', { timeout: 4000 });
  const allS = await page.locator('#fMSug button').allTextContents();
  assert.ok(allS.length >= 3 && allS.every(t => t.startsWith('Stihl ')), 'кабинет: пустое поле с брендом — модели бренда: ' + allS.slice(0, 3).join(' | '));
  await page.locator('#fModel').fill('ms 250');
  await page.waitForFunction(() => { const b = document.querySelector('#fMSug.on button'); return b && b.textContent === 'Stihl MS 250'; }, null, { timeout: 4000 });
  await page.locator('#fMSug button').first().click();
  await page.waitForFunction(t => document.getElementById('fName').value === t, ms2.title, { timeout: 4000 });
  assert.equal(await page.locator('#fSpecs [data-spec="bar"]').inputValue(), '38', 'кабинет: ручная длина шины не перезаписана');
  assert.equal(await page.locator('#fSpecs [data-spec="cc"]').inputValue(), String(ms2.specs.cc), 'кабинет: объём — новой модели');
  await page.locator('#fPrice').fill('900');
  await page.locator('#fSave').click();
  await page.waitForFunction(() => !document.getElementById('fSave'), null, { timeout: 8000 }).catch(() => {});
  if (mode === 'reject') {
    assert.equal(posted.length, 2, 'кабинет: база не приняла модель — вторая попытка');
    assert.ok(posted[0].model_id && !('model_id' in posted[1]) && !('specs' in posted[1]) && posted[1].kind === 'chainsaw', 'кабинет: вторая попытка без модели, с видом');
  } else {
    assert.equal(posted.length, 1, 'кабинет: инструмент отправлен');
    assert.equal(posted[0].model_id, ms2.id, 'кабинет: model_id');
    assert.deepEqual(posted[0].specs, { src: 'Бензин', cc: ms2.specs.cc, bar: 38 }, 'кабинет: specs — модель + ручная правка');
    assert.equal(posted[0].kind, 'chainsaw', 'кабинет: вид');
  }
  assert.equal(errors.length, 0, 'кабинет: ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

/* 10.83: карточка tool.html — «Вид» и характеристики после «Состояния».
   mode: 'full' — kind и specs; 'nocol' — столбца specs нет (только вид); 'none' — у объявления нет вида (как раньше). */
async function runTool(mode) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const TID = '11111111-2222-3333-4444-555555555555';
  const calls = [];
  const F = DIR.fields.filter(f => f.kind === 'rotary-sds-plus');
  await context.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url()), sel = u.searchParams.get('select') || '';
    let data = [];
    if (u.pathname.endsWith('/tools')) {
      if (sel === 'kind,specs,kit' || sel === 'kind,specs' || sel === 'kind') {
        calls.push(sel);
        /* 10.84: сначала с комплектацией; nocol — нет ни kit, ни specs (база до 10.82) */
        if (mode === 'nocol' && sel !== 'kind') return route.fulfill({ status: 400, json: { code: '42703', message: 'column tools.' + (sel.endsWith('kit') ? 'kit' : 'specs') + ' does not exist' } });
        data = mode === 'none' ? [{ kind: null }] : [Object.assign({ kind: 'rotary-sds-plus' }, sel === 'kind' ? {} : { specs: { src: 'Сеть 220 В', power: 780, energy: 2.4, zzz: 1 } },
          sel === 'kind,specs,kit' ? { kit: ['Кейс', 'Бур SDS-plus'] } : {})];
      } else data = [{ id: TID, name: 'Перфоратор Makita HR2470', cat: 'power', sub: 'hammers', descr: 'Проверка', price: 900, deposit: 3000,
        imgs: [], img: '', delivery: false, delivery_price: 0, terms: '', pickup_city: 'Новочеркасск', pickup_address: '', owner_email: 'owner@example.invalid',
        status: 'active', active: true, rating: 0, reviews_count: 0, created_at: '2026-10-10T10:00:00Z' }];
    } else if (u.pathname.endsWith('/cats')) data = CATS;
    else if (u.pathname.endsWith('/subcats')) data = SUBS;
    else if (u.pathname.endsWith('/iva_kinds')) { calls.push('kinds'); data = [{ title: 'Перфоратор SDS-plus' }]; }
    else if (u.pathname.endsWith('/iva_spec_fields')) { calls.push('fields:' + u.searchParams.get('kind')); data = F; }
    await route.fulfill({ json: data });
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(BASE + '/tool.html?id=' + TID);
  await page.waitForSelector('#specCond', { timeout: 15000 });
  if (mode === 'none') {
    await page.waitForTimeout(1500);
    assert.equal(await page.locator('[data-tspec]').count(), 0, 'карточка без вида — как раньше');
  } else {
    await page.waitForSelector('[data-tspec]', { timeout: 8000 });
    const rows = await page.$$eval('dl.specs .spec', els => els.map(e => e.querySelector('dt').textContent + '=' + e.querySelector('dd').textContent));
    const i = rows.findIndex(x => x.startsWith('Состояние='));
    const want = mode === 'full'
      ? ['Вид=Перфоратор SDS-plus', 'Питание=Сеть 220 В', 'Мощность=780\u00a0Вт', 'Энергия удара=2,4\u00a0Дж', 'Комплектация=Кейс, Бур SDS-plus']
      : ['Вид=Перфоратор SDS-plus'];
    assert.deepEqual(rows.slice(i + 1, i + 1 + want.length), want, 'карточка ' + mode + ': вид и характеристики после «Состояния»: ' + rows.join(' | '));
    assert.equal(await page.locator('[data-tspec]').count(), want.length, 'карточка ' + mode + ': добавлены только эти строки');
    assert.ok(rows[0].startsWith('Категория=') && rows.some(x => x.startsWith('Минимальный срок=')), 'карточка ' + mode + ': прежние строки на месте');
    assert.ok(mode === 'full' ? calls.includes('fields:eq.rotary-sds-plus') : !calls.some(c => c.startsWith('fields')), 'карточка ' + mode + ': подписи — только когда есть характеристики');
    if (process.env.SHOT && mode === 'full') await page.screenshot({ path: process.env.SHOT + '/tool-1.png', fullPage: true });
  }
  assert.equal(errors.length, 0, 'карточка: ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

(async () => {
  await runTool('full');
  await runTool('nocol');
  await runTool('none');
  await runNew('full');
  await runNew('nodir');
  await runNew('reject');
  await runCabinet('full');
  await runCabinet('reject');
  console.log('PASS: справочник моделей на сайте — карточка (вид и характеристики), подсказки, «Модель», характеристики, запись; без справочника и при отказе базы — как раньше; кабинет.');
})().catch(e => { console.error(e); process.exit(1); });

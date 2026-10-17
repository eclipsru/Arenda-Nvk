/* 10.86: «Питание» в форме сайта (решение владельца 15.10.2026, model_only):
   * модели нет — строки «Питание» нет, «Напряжения аккумулятора» тоже (у вида есть «Питание»);
   * выбрана модель — «Питание» одним вариантом (питание модели, уже выбрано); «Напряжение аккумулятора» —
     только пока выбран «Аккумулятор»; сняли питание — напряжение пропадает, вернули — появляется с тем же числом;
   * скрытые поля не сохраняются. new.html и кабинет. Справочник — из настоящих миграций. */
const { chromium } = require('playwright');
const assert = require('assert');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
const { kindsFromSql, dirFromSql } = require('./model-fixtures.cjs');

const KINDS = kindsFromSql();
const DIR = dirFromSql();
const CATS = [{ key: 'power', title: 'Электроинструмент' }, { key: 'garden', title: 'Садовая техника' }];
const SUBS = [{ key: 'hammers', cat: 'power', title: 'Перфораторы' }, { key: 'trimmers', cat: 'garden', title: 'Пилы и кусторезы' }];
const byTitle = t => { const m = DIR.models.find(x => x.title === t); assert.ok(m, 'нет модели ' + t); return m; };
const EMAIL = 'landlord-power@example.invalid';

async function route(context, posted) {
  await context.route('https://*.supabase.co/**', async rt => {
    const r = rt.request(), u = new URL(r.url());
    let data = [];
    if (u.pathname === '/auth/v1/user') data = { id: '40000000-0000-4000-8000-000000000002', email: EMAIL, email_confirmed_at: '2026-01-01T00:00:00.000Z', user_metadata: { name: 'Иван', city: 'Новочеркасск' } };
    else if (u.pathname.endsWith('/admins')) data = [{ email: EMAIL, role: 'admin', active: true, full_name: 'Иван Петров', address: '' }];
    else if (u.pathname.endsWith('/cats')) data = CATS;
    else if (u.pathname.endsWith('/subcats')) data = SUBS;
    else if (u.pathname.endsWith('/iva_kinds')) data = KINDS;
    else if (u.pathname.endsWith('/iva_brands')) data = DIR.brands;
    else if (u.pathname.endsWith('/iva_spec_fields')) data = DIR.fields;
    else if (u.pathname.endsWith('/iva_models')) { const off = +u.searchParams.get('offset'); data = DIR.models.slice(off, off + 1000); }
    else if (u.pathname.endsWith('/tools') && r.method() === 'POST') {
      const body = JSON.parse(r.postData() || '{}');
      posted.push(body);
      data = [Object.assign({ id: '50000000-0000-4000-8000-000000000001' }, body)];
    } else if (u.pathname.endsWith('/landlord_applications')) data = [{ city: 'Новочеркасск', status: 'approved', business_type: 'company' }];
    await rt.fulfill({ json: data });
  });
}

const specKeys = (page, box) => page.evaluate(b => [...document.querySelectorAll((b || '') + ' [data-spec]')].map(e => e.getAttribute('data-spec')).join(','), box);
const srcOpts = (page, box) => page.evaluate(b => { const s = document.querySelector((b || '') + ' [data-spec="src"]'); return s ? [...s.options].map(o => o.value).join(';') + '=' + s.value : null; }, box);

async function openNew(page) {
  await page.goto(BASE + '/offer.html');
  await page.evaluate(em => {
    localStorage.setItem('iva_auth', JSON.stringify({ tok: 'fake-jwt-token', ref: 'fake-refresh-token', exp: 9999999999999 }));
    localStorage.setItem('iva_tok', JSON.stringify({ tok: 'fake-jwt-token', rtok: 'fake-refresh-token', me: em, uname: 'Иван' }));
    localStorage.removeItem('iva_draft_ad');
    localStorage.removeItem('iva_models_v2');
  }, EMAIL);
  await page.goto(BASE + '/new.html');
  await page.waitForFunction(() => document.getElementById('mSugWhat') && document.getElementById('iWhat').dataset.msug === '1', null, { timeout: 15000 });
}

async function publish(page) {
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

async function pickModel(page, query, title) {
  await page.locator('#iWhat').fill(query);
  await page.waitForFunction(t => [...document.querySelectorAll('#mSugWhat.on button')].some(b => b.textContent === t), title, { timeout: 4000 });
  await page.locator('#mSugWhat button', { hasText: title }).first().click();
  await page.waitForFunction(t => document.getElementById('iWhat').value === t && document.getElementById('iModel'), title, { timeout: 4000 });
}

async function runNew() {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const posted = [];
  await route(context, posted);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const gbh = byTitle('Перфоратор Bosch GBH 18V-26'), hr = byTitle('Перфоратор Makita HR2470');
  assert.equal(gbh.specs.src, 'Аккумулятор');
  assert.equal(hr.specs.src, 'Сеть 220 В');

  // 1. Только вид — ни «Питания», ни напряжения; остальные поля вида на месте
  await openNew(page);
  await page.locator('#iWhat').fill('Перфоратор SDS-plus');
  await page.waitForSelector('[data-kind="rotary-sds-plus"].on', { timeout: 4000 });
  await page.waitForSelector('[data-spec="power"]', { timeout: 4000 });
  assert.equal(await specKeys(page), 'power,energy,weight', 'без модели: нет «Питания» и напряжения');

  // 2. Аккумуляторная модель: «Питание» — один вариант, выбран; напряжение — с числом модели
  await pickModel(page, 'gbh 18v-26', gbh.title);
  assert.equal(await srcOpts(page), ';Аккумулятор=Аккумулятор', '«Питание» — только питание модели, выбрано');
  assert.equal(await specKeys(page), 'src,volt,power,energy,weight', 'с моделью: все поля вида');
  assert.equal(await page.locator('[data-spec="volt"]').inputValue(), String(gbh.specs.volt), 'напряжение модели');
  // снимаем питание — напряжение пропадает; возвращаем — появляется с тем же числом
  await page.locator('[data-spec="src"]').selectOption('');
  await page.waitForFunction(() => !document.querySelector('[data-spec="volt"]'), null, { timeout: 4000 });
  assert.equal(await specKeys(page), 'src,power,energy,weight', 'питание снято — напряжения нет, «Питание» осталось');
  await page.locator('[data-spec="src"]').selectOption('Аккумулятор');
  await page.waitForSelector('[data-spec="volt"]', { timeout: 4000 });
  assert.equal(await page.locator('[data-spec="volt"]').inputValue(), String(gbh.specs.volt), 'напряжение вернулось с тем же числом');
  // снова снимаем — и публикуем: ни src, ни volt не уходят
  await page.locator('[data-spec="src"]').selectOption('');
  await page.waitForFunction(() => !document.querySelector('[data-spec="volt"]'), null, { timeout: 4000 });
  await publish(page);
  assert.equal(posted.length, 1, 'объявление отправлено');
  assert.equal(posted[0].model_id, gbh.id, 'модель записана: ' + JSON.stringify(posted[0]));
  assert.ok(!('src' in posted[0].specs) && !('volt' in posted[0].specs), 'скрытое напряжение и пустое питание не сохраняются: ' + JSON.stringify(posted[0].specs));
  assert.equal(posted[0].specs.energy, gbh.specs.energy, 'остальные характеристики модели');

  // 3. Сетевая модель: «Питание» — «Сеть 220 В», напряжения нет; при публикации src уходит
  posted.length = 0;
  await openNew(page);
  await pickModel(page, 'makita hr2470', hr.title);
  assert.equal(await srcOpts(page), ';Сеть 220 В=Сеть 220 В', 'сетевая модель — один вариант');
  assert.equal(await specKeys(page), 'src,power,energy,weight', 'сетевая модель — без напряжения');
  // переписали название — модель снята, «Питание» пропадает (и не сохраняется)
  await page.locator('#iWhat').fill('Перфоратор SDS-plus Makita б/у');
  await page.waitForFunction(() => document.getElementById('iModel') && document.getElementById('iModel').value === '' && !document.querySelector('[data-spec="src"]'), null, { timeout: 4000 });
  await publish(page);
  assert.equal(posted.length, 1, 'второе объявление отправлено');
  assert.ok(!('model_id' in posted[0]) && !('src' in posted[0].specs) && posted[0].specs.power === hr.specs.power, 'без модели — без питания, мощность осталась: ' + JSON.stringify(posted[0].specs));
  assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

async function runCabinet() {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const posted = [];
  await context.route('https://*.supabase.co/**', async rt => {
    const r = rt.request(), u = new URL(r.url());
    let data = [];
    if (u.pathname === '/auth/v1/user') data = { id: '40000000-0000-4000-8000-000000000002', email: EMAIL, email_confirmed_at: '2026-01-01T00:00:00.000Z', user_metadata: { name: 'Иван' } };
    else if (u.pathname.endsWith('/cats')) data = CATS;
    else if (u.pathname.endsWith('/subcats')) data = SUBS;
    else if (u.pathname.endsWith('/admins')) data = [{ email: EMAIL, role: 'admin', active: true }];
    else if (u.pathname.endsWith('/iva_kinds')) data = KINDS;
    else if (u.pathname.endsWith('/iva_brands')) data = DIR.brands;
    else if (u.pathname.endsWith('/iva_spec_fields')) data = DIR.fields;
    else if (u.pathname.endsWith('/iva_models')) { const off = +u.searchParams.get('offset'); data = DIR.models.slice(off, off + 1000); }
    else if (u.pathname.endsWith('/tools') && r.method() === 'POST') { posted.push(JSON.parse(r.postData() || '{}')); data = null; }
    await rt.fulfill(data === null ? { status: 201, body: '' } : { json: data });
  });
  /* cabinet.html без #resendLink падает до входа (отдельная находка) — как в model-form-browser.cjs */
  await context.route(BASE + '/cabinet.html', async rt => {
    const res = await rt.fetch();
    let html = await res.text();
    if (!/id="resendLink"/.test(html)) html = html.replace('<body>', '<body><a id="resendLink" class="hide"></a>');
    await rt.fulfill({ response: res, body: html });
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
  await page.waitForFunction(() => document.getElementById('fBrands') && window.ivaModelsLoad && document.querySelectorAll('#fNSug').length, null, { timeout: 8000 });
  await page.waitForTimeout(800);
  // только вид — «Питания» и напряжения нет
  await page.locator('#fName').fill('бензопила');
  await page.waitForFunction(() => document.getElementById('fKind').value === 'chainsaw', null, { timeout: 4000 });
  await page.waitForSelector('#fSpecs [data-spec="cc"]', { timeout: 4000 });
  assert.equal(await specKeys(page, '#fSpecs'), 'cc,power,bar,weight', 'кабинет: без модели нет «Питания» и напряжения');
  // модель — «Питание» одним вариантом
  const ms = byTitle('Бензопила Stihl MS 180');
  await page.locator('#fName').fill('бензопила штиль 180');
  await page.waitForFunction(t => [...document.querySelectorAll('#fNSug.on button')].some(b => b.textContent === t), ms.title, { timeout: 4000 });
  await page.locator('#fNSug button', { hasText: ms.title }).first().click();
  await page.waitForFunction(() => document.querySelector('#fSpecs [data-spec="src"]'), null, { timeout: 4000 });
  assert.equal(await srcOpts(page, '#fSpecs'), ';Бензин=Бензин', 'кабинет: «Питание» — питание модели');
  assert.equal(await specKeys(page, '#fSpecs'), 'src,cc,power,bar,weight', 'кабинет: бензиновая модель — без напряжения');
  // аккумуляторная пила: напряжение — пока «Аккумулятор»
  const acc = DIR.models.find(m => m.kind === 'chainsaw' && m.specs.src === 'Аккумулятор' && m.specs.volt);
  assert.ok(acc, 'есть аккумуляторная пила с напряжением');
  await page.locator('#fModel').fill(acc.model);
  await page.waitForFunction(t => [...document.querySelectorAll('#fMSug.on button')].some(b => b.textContent === t), acc.brand + ' ' + acc.model, { timeout: 4000 });
  await page.locator('#fMSug button', { hasText: acc.brand + ' ' + acc.model }).first().click();
  await page.waitForFunction(() => document.querySelector('#fSpecs [data-spec="volt"]'), null, { timeout: 4000 });
  assert.equal(await srcOpts(page, '#fSpecs'), ';Аккумулятор=Аккумулятор', 'кабинет: аккумуляторная модель');
  await page.locator('#fSpecs [data-spec="src"]').selectOption('');
  await page.waitForFunction(() => !document.querySelector('#fSpecs [data-spec="volt"]'), null, { timeout: 4000 });
  await page.locator('#fPrice').fill('900');
  await page.locator('#fSave').click();
  await page.waitForFunction(() => !document.getElementById('fSave'), null, { timeout: 8000 }).catch(() => {});
  assert.equal(posted.length, 1, 'кабинет: инструмент отправлен');
  assert.equal(posted[0].model_id, acc.id, 'кабинет: model_id');
  assert.ok(posted[0].specs == null || (!('src' in posted[0].specs) && !('volt' in posted[0].specs)), 'кабинет: скрытые поля не сохраняются: ' + JSON.stringify(posted[0].specs));
  assert.equal(errors.length, 0, 'кабинет: ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

(async () => {
  await runNew();
  await runCabinet();
  console.log('PASS: «Питание» на сайте — без модели строки нет; у модели — один вариант; напряжение только при «Аккумуляторе»; скрытое не сохраняется (new.html, кабинет).');
})().catch(e => { console.error('FAIL:', e && e.stack || e); process.exit(1); });

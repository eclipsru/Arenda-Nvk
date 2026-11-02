/* 10.84: справочник моделей, этап 4 на сайте.
   new.html — «Предложить модель» (запись, «уже есть», дубль, понятные ошибки), «Комплектация» (порядок вида, запись kit),
   «Сгенерировать описание» (ручное не трогаем), подсказка цены с «Подставить»; SQL этапа 4 не применён — форма без новых
   блоков, предложение объясняет причину; нет столбца kit — объявление публикуется без комплектации, с моделью.
   cabinet.html — те же блоки в форме «Новый инструмент». chief.html — вкладка «Модели»: счётчик, правка, одобрить, отклонить. */
const { chromium } = require('playwright');
const assert = require('assert');
const path = require('path');
const { kindsFromSql, dirFromSql, kitFromTxt } = require('./model-fixtures.cjs');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
const KINDS = kindsFromSql();
const DIR = dirFromSql();
const KIT = kitFromTxt();
const CATS = [{ key: 'power', title: 'Электроинструмент' }, { key: 'garden', title: 'Садовая техника' }];
const SUBS = [{ key: 'drills', cat: 'power', title: 'Дрели, шуруповёрты и гайковёрты' },
              { key: 'hammers', cat: 'power', title: 'Перфораторы' },
              { key: 'trimmers', cat: 'garden', title: 'Пилы и кусторезы' }];
const PRICE_ROWS = [{ price: 800, deposit: 4000, model_id: null, kind: 'rotary-sds-plus' },
                    { price: 1000, deposit: 5000, model_id: null, kind: 'rotary-sds-plus' },
                    { price: 1200, deposit: 6000, model_id: null, kind: 'rotary-sds-plus' }];
const HINT = 'Обычно сдают за 800–1 200 ₽ в сутки, залог 4 000–6 000 ₽ (3 объявления этого вида)';
const nb = s => String(s).replace(/\u00a0/g, ' ');

/* mode: 'full' — этап 4 применён; 'old' — нет таблиц этапа 4 (iva_kit_items, iva_model_proposals, tools.kit);
         'nocol' — комплектация есть, а столбца tools.kit нет */
function handler(email, role, mode, st) {
  return async route => {
    const r = route.request(), u = new URL(r.url()), p = u.pathname;
    let data = [];
    if (p === '/auth/v1/user') data = { id: '40000000-0000-4000-8000-000000000002', email, email_confirmed_at: '2026-01-01T00:00:00.000Z', user_metadata: { name: 'Иван', city: 'Новочеркасск' } };
    else if (p.endsWith('/admins')) data = [{ email, role, active: true, full_name: 'Иван Петров', address: '' }];
    else if (p.endsWith('/cats')) data = CATS;
    else if (p.endsWith('/subcats')) data = SUBS;
    else if (p.endsWith('/iva_kinds')) {
      const inl = (u.searchParams.get('key') || '').match(/^in\.\((.*)\)$/);
      data = inl ? KINDS.filter(k => inl[1].split(',').includes(k.key)) : KINDS;
    } else if (p.endsWith('/iva_brands')) data = DIR.brands;
    else if (p.endsWith('/iva_spec_fields')) {
      const inl = (u.searchParams.get('kind') || '').match(/^in\.\((.*)\)$/);
      data = inl ? DIR.fields.filter(f => inl[1].split(',').includes(f.kind)) : DIR.fields;
    } else if (p.endsWith('/iva_models')) { const off = +u.searchParams.get('offset'); data = DIR.models.slice(off, off + 1000); }
    else if (p.endsWith('/iva_kit_items')) {
      if (mode === 'old') return route.fulfill({ status: 404, json: { code: 'PGRST205', message: "Could not find the table 'public.iva_kit_items' in the schema cache" } });
      data = Object.keys(KIT).map(k => ({ kind: k, items: KIT[k] }));
    } else if (p.endsWith('/iva_model_proposals')) {
      if (mode === 'old') return route.fulfill({ status: 404, json: { code: 'PGRST205', message: "Could not find the table 'public.iva_model_proposals' in the schema cache" } });
      if (r.method() === 'POST') {
        const body = JSON.parse(r.postData() || '{}');
        st.proposals.push(body);
        if (body.model === 'DUP1') return route.fulfill({ status: 409, json: { code: '23505', message: 'duplicate key value violates unique constraint "iva_model_proposals_pending_uq"' } });
        return route.fulfill({ status: 201, body: '' });
      }
      data = st.rows;
    } else if (p.endsWith('/rpc/iva_model_decide')) {
      const body = JSON.parse(r.postData() || '{}');
      st.rpc.push(body);
      const row = st.rows.find(x => x.id === body.p_id);
      if (!row || row.status !== 'pending') return route.fulfill({ status: 400, json: { code: '22023', message: 'already decided' } });
      row.status = body.p_approve ? 'approved' : 'rejected';
      row.decided_at = '2026-10-15T12:00:00Z';
      if (body.p_approve) Object.assign(row, { brand: body.p_brand, model: body.p_model, title: body.p_title, model_id: 9999 });
      data = body.p_approve ? 9999 : null;
    } else if (p.endsWith('/tools') && r.method() === 'GET' && u.searchParams.get('kind')) {
      st.priceCalls.push(u.search);
      data = u.searchParams.get('kind') === 'eq.rotary-sds-plus' ? PRICE_ROWS : [];
    } else if (p.endsWith('/tools') && r.method() === 'POST') {
      const body = JSON.parse(r.postData() || '{}');
      st.posted.push(body);
      if ((mode === 'old' || mode === 'nocol') && body.kit)
        return route.fulfill({ status: 400, json: { code: 'PGRST204', message: "Could not find the 'kit' column of 'tools' in the schema cache" } });
      data = st.cabinet ? null : [Object.assign({ id: '50000000-0000-4000-8000-000000000001' }, body)];
    } else if (p.endsWith('/landlord_applications')) data = [{ city: 'Новочеркасск', status: 'approved', business_type: 'company' }];
    await route.fulfill(data === null ? { status: 201, body: '' } : { json: data });
  };
}

async function session(page, email) {
  await page.goto(BASE + '/offer.html');
  await page.evaluate(em => {
    localStorage.setItem('iva_auth', JSON.stringify({ tok: 'fake-jwt-token', ref: 'fake-refresh-token', exp: 9999999999999 }));
    localStorage.setItem('iva_tok', JSON.stringify({ tok: 'fake-jwt-token', rtok: 'fake-refresh-token', me: em, uname: 'Иван' }));
    localStorage.removeItem('iva_draft_ad');
    localStorage.removeItem('iva_models_v2');
  }, email);
}

async function runNew(mode) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const email = 'landlord-s4@example.invalid';
  const st = { proposals: [], posted: [], priceCalls: [], rows: [], rpc: [] };
  await context.route('https://*.supabase.co/**', handler(email, 'admin', mode, st));
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await session(page, email);
  await page.goto(BASE + '/new.html');
  await page.waitForSelector('#iWhat');
  await page.waitForFunction(() => document.getElementById('iWhat').dataset.msug === '1', null, { timeout: 15000 });   // справочник загружен
  await page.locator('#iWhat').fill('Перфоратор SDS-plus');
  await page.waitForSelector('[data-kind="rotary-sds-plus"].on', { timeout: 5000 });
  await page.waitForSelector('#iModel', { timeout: 5000 });
  const items = KIT['rotary-sds-plus'];

  if (mode === 'old') {
    assert.equal(await page.locator('#fKit').count(), 0, 'old: без iva_kit_items блока «Комплектация» нет');
    await page.locator('#iModel').fill('Makita HR9999X');
    await page.waitForSelector('[data-mprop]', { timeout: 4000 });
    await page.locator('[data-mprop]').click();
    await page.waitForFunction(() => /после обновления базы/.test(document.getElementById('mProp').textContent), null, { timeout: 4000 });
    assert.equal(st.proposals.length, 0, 'old: тело не принято (таблицы нет)');
  } else {
    // комплектация: пункты вида по порядку; отмечаем в обратном порядке — запишется в порядке вида
    const shown = await page.locator('#fKit [data-kit]').evaluateAll(els => els.map(e => e.getAttribute('data-kit')));
    assert.deepEqual(shown, items, mode + ': комплектация — пункты вида из kit.txt по порядку');
    await page.locator('#fKit [data-kit="Пика"]').click();
    await page.locator('#fKit [data-kit="Кейс"]').click();
    assert.equal(await page.locator('#fKit [data-kit].on').count(), 2, mode + ': отмечены два пункта');
    assert.ok(nb(await page.locator('#fKit [data-kit="Кейс"]').textContent()).startsWith('✓ '), mode + ': отмеченный пункт с галочкой');
  }

  if (mode === 'full') {
    await page.locator('[data-spec="power"]').fill('800');
    // модель из справочника, набранная без выбора, — «Предложить» не показывается
    await page.locator('#iModel').fill('Makita HR2470');
    await page.waitForTimeout(300);
    assert.equal(await page.locator('[data-mprop]').count(), 0, 'есть в справочнике — предлагать нечего');
    // дубль — понятный текст, можно повторить
    await page.locator('#iModel').fill('Bosch DUP1');
    await page.waitForSelector('[data-mprop]', { timeout: 4000 });
    await page.locator('[data-mprop]').click();
    await page.waitForFunction(() => /уже предложили/.test(document.getElementById('mProp').textContent), null, { timeout: 4000 });
    // новая модель
    await page.locator('#iModel').fill('макита  HR9999X');
    await page.waitForFunction(() => { const b = document.querySelector('[data-mprop]'); return b && b.textContent === 'Предложить «Makita HR9999X»'; }, null, { timeout: 4000 });
    if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT + '/s4-new-1.png', fullPage: true });
    await page.locator('[data-mprop]').click();
    await page.waitForFunction(() => /отправлена главному админу/.test(document.getElementById('mProp').textContent), null, { timeout: 4000 });
    assert.deepEqual(st.proposals[1], { kind: 'rotary-sds-plus', brand: 'Makita', model: 'HR9999X', title: 'Перфоратор SDS-plus Makita HR9999X', specs: { power: 800 } },
      'предложение: вид, бренд как в справочнике, модель, название, характеристики формы');
    assert.equal(st.proposals.length, 2, 'одно касание — одна запись (дубль + новая)');
    await page.locator('[data-mprop]').count().then(n => assert.equal(n, 0, 'после отправки кнопки нет'));
    // ввод не теряется при перерисовке (касание комплектации перерисовывает блок)
    await page.locator('#fKit [data-kit="Долото"]').click();
    assert.equal(await page.locator('#iModel').inputValue(), 'макита  HR9999X', 'текст «Модели» сохраняется при перерисовке');
    await page.locator('#fKit [data-kit="Долото"]').click();
  }

  // шаги 2 → 3: описание
  await page.locator('#bNext').click();
  await page.locator('#bNext').click();
  await page.waitForSelector('#iDescr');
  const wantKit = mode === 'old' ? '' : '\n\nВ комплекте: кейс, пика.';
  const wantSpecs = mode === 'full' ? '\n\nХарактеристики:\n— Мощность: 800\u00a0Вт' : '';
  await page.locator('#genDescr').click();
  assert.equal(await page.locator('#iDescr').inputValue(), 'Перфоратор SDS-plus в аренду.' + wantSpecs + wantKit, mode + ': описание из названия, характеристик и комплектации');
  if (mode === 'full') {
    await page.locator('#iDescr').fill('Мой собственный текст описания инструмента');
    await page.locator('#genDescr').click();
    assert.equal(await page.locator('#iDescr').inputValue(), 'Мой собственный текст описания инструмента', 'ручное описание не перезаписано');
    assert.ok(/уже написано/.test(await page.locator('#genNote').textContent()) && await page.locator('#genNote').isVisible(), 'подсказка, почему не заменили');
    await page.locator('#iDescr').fill('');
    await page.locator('#genDescr').click();
    assert.ok((await page.locator('#iDescr').inputValue()).startsWith('Перфоратор SDS-plus в аренду.'), 'пустое — снова сгенерировано');
    assert.equal(await page.locator('#genNote').isVisible(), false, 'подсказка скрыта');
  }
  // шаг 4: цена
  await page.locator('#bNext').click();
  await page.waitForSelector('#iPrice');
  await page.waitForFunction(() => { const h = document.getElementById('priceHint'); return h && h.style.display !== 'none' && h.textContent; }, null, { timeout: 5000 });
  assert.ok(nb(await page.locator('#priceHint').textContent()).startsWith(nb(HINT)), mode + ': подсказка цены: ' + await page.locator('#priceHint').textContent());
  assert.ok(st.priceCalls.some(q => /active=eq\.true/.test(q) && /select=price,deposit,model_id,kind/.test(q)), mode + ': подсказка — активные объявления вида');
  if (process.env.SHOT && mode === 'full') await page.screenshot({ path: process.env.SHOT + '/s4-new-4.png', fullPage: true });
  await page.locator('#priceAuto').click();
  assert.equal(await page.locator('#iPrice').inputValue() + '/' + await page.locator('#iDep').inputValue(), '1000/5000', mode + ': «Подставить» — середина цены и залога');
  assert.equal(await page.locator('#priceAuto').count(), 0, mode + ': цена есть — «Подставить» больше нет');
  await page.locator('#bNext').click();
  await page.locator('#iCity').fill('Новочеркасск');
  await page.locator('#bSave').click();
  await page.waitForFunction(() => !document.getElementById('bSave'), null, { timeout: 15000 });
  if (mode === 'full') {
    assert.equal(st.posted.length, 1, 'одна запись');
    assert.deepEqual(st.posted[0].kit, ['Кейс', 'Пика'], 'kit — в порядке вида: ' + JSON.stringify(st.posted[0]));
    assert.deepEqual(st.posted[0].specs, { power: 800 }, 'specs');
    assert.equal(st.posted[0].price + '/' + st.posted[0].deposit, '1000/5000', 'цена и залог');
  } else if (mode === 'nocol') {
    assert.equal(st.posted.length, 2, 'nocol: вторая попытка без kit');
    assert.ok(st.posted[0].kit && !('kit' in st.posted[1]) && st.posted[1].kind === 'rotary-sds-plus', 'nocol: без комплектации, вид остался');
  } else {
    assert.equal(st.posted.length, 1, 'old: одна запись, без kit');
    assert.ok(!('kit' in st.posted[0]), 'old: kit не отправляется');
  }
  assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

async function runCabinet() {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const email = 'landlord-s4@example.invalid';
  const st = { proposals: [], posted: [], priceCalls: [], rows: [], rpc: [], cabinet: true };
  await context.route('https://*.supabase.co/**', handler(email, 'admin', 'full', st));
  await context.route(BASE + '/cabinet.html', async route => {   /* как в model-form-browser.cjs: нет #resendLink — падает до входа */
    const res = await route.fetch();
    let html = await res.text();
    if (!/id="resendLink"/.test(html)) html = html.replace('<body>', '<body><a id="resendLink" class="hide"></a>');
    await route.fulfill({ response: res, body: html });
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await session(page, email);
  await page.goto(BASE + '/cabinet.html');
  await page.waitForFunction(() => typeof toolForm === 'function' && S.me && S.cats && S.cats.length, null, { timeout: 15000 });
  await page.evaluate(() => toolForm());
  await page.waitForSelector('#fName');
  await page.waitForFunction(() => document.getElementById('fGenBox') && !document.getElementById('fGenBox').classList.contains('hide'), null, { timeout: 10000 });
  await page.locator('#fName').fill('Перфоратор SDS-plus');
  await page.waitForFunction(() => document.getElementById('fKind').value === 'rotary-sds-plus', null, { timeout: 5000 });
  await page.waitForSelector('#fKitBox:not(.hide)', { timeout: 4000 });
  assert.deepEqual(await page.locator('#fKit [data-kit]').evaluateAll(els => els.map(e => e.getAttribute('data-kit'))), KIT['rotary-sds-plus'], 'кабинет: комплектация вида');
  await page.locator('#fKit [data-kit="Бур SDS-plus"]').click();
  await page.locator('#fKit [data-kit="Кейс"]').click();
  await page.locator('#fModel').fill('Makita HR9999X');
  await page.waitForSelector('#fProp [data-mprop]', { timeout: 4000 });
  await page.locator('#fProp [data-mprop]').click();
  await page.waitForFunction(() => /отправлена главному админу/.test(document.getElementById('fProp').textContent), null, { timeout: 4000 });
  assert.equal(st.proposals.length, 1, 'кабинет: предложение отправлено');
  assert.equal(st.proposals[0].brand + ' ' + st.proposals[0].model + ' / ' + st.proposals[0].kind, 'Makita HR9999X / rotary-sds-plus', 'кабинет: тело предложения');
  await page.waitForSelector('#fPHint:not(.hide)', { timeout: 5000 });
  assert.ok(nb(await page.locator('#fPHint').textContent()).startsWith(nb(HINT)), 'кабинет: подсказка цены');
  await page.locator('#fPAuto').click();
  assert.equal(await page.locator('#fPrice').inputValue() + '/' + await page.locator('#fDep').inputValue(), '1000/5000', 'кабинет: «Подставить»');
  await page.locator('#fGen').click();
  assert.equal(await page.locator('#fDescr').inputValue(), 'Перфоратор SDS-plus в аренду.\n\nВ комплекте: кейс, бур SDS-plus.', 'кабинет: описание');
  await page.locator('#fDescr').fill('Своё описание');
  await page.locator('#fGen').click();
  assert.equal(await page.locator('#fDescr').inputValue(), 'Своё описание', 'кабинет: ручное описание не трогаем');
  assert.ok(/уже написано/.test(await page.locator('#fGenNote').textContent()), 'кабинет: подсказка');
  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT + '/s4-cab.png', fullPage: true });
  await page.locator('#fSave').click();
  await page.waitForFunction(() => !document.getElementById('fSave'), null, { timeout: 8000 }).catch(() => {});
  assert.equal(st.posted.length, 1, 'кабинет: инструмент отправлен');
  assert.deepEqual(st.posted[0].kit, ['Кейс', 'Бур SDS-plus'], 'кабинет: kit в порядке вида');
  assert.equal(errors.length, 0, 'кабинет: ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

async function runChief(mode) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const email = 'chief-s4@example.invalid';
  const st = { proposals: [], posted: [], priceCalls: [], rpc: [], rows: [
    { id: 1, kind: 'rotary-sds-plus', brand: 'Makita', model: 'HR9999X', title: 'Перфоратор SDS-plus Makita HR9999X', specs: { power: 800, energy: 2.4 },
      proposed_by: 'admin@example.invalid', status: 'pending', created_at: '2026-10-15T10:00:00Z', decided_at: null, model_id: null },
    { id: 2, kind: 'chainsaw', brand: 'NoName', model: 'Z-1', title: 'Бензопила NoName Z-1', specs: {},
      proposed_by: 'admin2@example.invalid', status: 'pending', created_at: '2026-10-15T09:00:00Z', decided_at: null, model_id: null },
    { id: 3, kind: 'drill', brand: 'Bosch', model: 'X1', title: 'Дрель Bosch X1', specs: {},
      proposed_by: 'admin@example.invalid', status: 'rejected', created_at: '2026-10-14T09:00:00Z', decided_at: '2026-10-14T12:00:00Z', model_id: null }] };
  await context.route('https://*.supabase.co/**', handler(email, 'chief', mode, st));
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  await session(page, email);
  await page.goto(BASE + '/chief.html#models');
  await page.waitForSelector('[data-tab="models"]', { timeout: 15000 });
  if (mode === 'old') {
    await page.waitForFunction(() => /20261018_models_stage4\.sql/.test(document.getElementById('root').textContent), null, { timeout: 5000 });
    assert.equal(await page.locator('[data-tab="models"] .n').count(), 0, 'old: без таблицы — без счётчика');
  } else {
    assert.equal(await page.locator('[data-tab="models"] .n').textContent(), '2', 'счётчик ждущих');
    assert.equal(await page.locator('[data-pp-ok]').count(), 2, 'две карточки с кнопками');
    const t1 = await page.locator('[data-pp-row="1"]').textContent();
    assert.ok(/Перфоратор SDS-plus/.test(t1) && /admin@example\.invalid/.test(t1) && /Мощность: 800\u00a0Вт/.test(t1) && /Энергия удара: 2,4\u00a0Дж/.test(t1), 'вид, кто предложил, характеристики с подписями: ' + t1);
    assert.ok(/Дрель Bosch X1/.test(await page.locator('#root').textContent()), 'решённые — ниже');
    if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT + '/s4-chief.png', fullPage: true });
    await page.locator('[data-pp-row="1"] [data-pp-f="title"]').fill('Перфоратор Makita HR9999X AVT');
    await page.locator('[data-pp-ok="1"]').click();
    await page.waitForFunction(() => document.querySelectorAll('[data-pp-ok]').length === 1, null, { timeout: 5000 });
    assert.deepEqual(st.rpc[0], { p_id: 1, p_approve: true, p_brand: 'Makita', p_model: 'HR9999X', p_title: 'Перфоратор Makita HR9999X AVT' }, 'одобрение — с правкой названия');
    assert.equal(await page.locator('[data-tab="models"] .n').textContent(), '1', 'счётчик уменьшился');
    await page.locator('[data-pp-no="2"]').click();
    await page.waitForFunction(() => document.querySelectorAll('[data-pp-ok]').length === 0, null, { timeout: 5000 });
    assert.deepEqual(st.rpc[1], { p_id: 2, p_approve: false, p_brand: null, p_model: null, p_title: null }, 'отклонение');
    assert.ok(/Новых предложений нет/.test(await page.locator('#root').textContent()), 'пусто — понятный текст');
    assert.equal(await page.locator('[data-tab="models"] .n').count(), 0, 'счётчика нет');
  }
  assert.equal(errors.length, 0, 'панель: ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

(async () => {
  await runNew('full');
  await runNew('old');
  await runNew('nocol');
  await runCabinet();
  await runChief('full');
  await runChief('old');
  console.log('PASS: этап 4 на сайте — предложить модель, комплектация, описание, подсказка цены (new.html: full/old/nocol, кабинет), вкладка «Модели» (full/old).');
})().catch(e => { console.error(e); process.exit(1); });

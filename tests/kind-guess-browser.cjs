/* 10.81: автоподбор категории, подкатегории и вида по названию на сайте (new.html) + запись вида.
   Справочник видов и слова — из настоящих миграций; второй прогон — база без столбца words (SQL 10.81 не применён). */
const { chromium } = require('playwright');
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';

function kindsFromSql() {
  const tree = fs.readFileSync(path.join(ROOT, 'supabase/migrations/20261015_category_tree.sql'), 'utf8');
  const words = fs.readFileSync(path.join(ROOT, 'supabase/migrations/20261016_kind_words.sql'), 'utf8');
  const i0 = tree.indexOf('insert into public.iva_kinds');
  const block = tree.slice(i0, tree.indexOf('on conflict (key)', i0));
  const out = [];
  const re = /\('([a-z0-9-]+)',\s*'([a-z0-9-]+)',\s*'([a-z0-9-]+)',\s*'([^']+)',\s*(\d+)\)/g;
  let m;
  while ((m = re.exec(block))) out.push({ key: m[1], cat: m[2], sub: m[3], title: m[4], sort: +m[5], words: [] });
  const by = Object.fromEntries(out.map(k => [k.key, k]));
  const wre = /^\s*\('([a-z0-9-]+)', array\[(.*)\]::text\[\]\)/gm;
  while ((m = wre.exec(words))) by[m[1]].words = m[2].split(',').map(s => s.trim().replace(/^'|'$/g, ''));
  return out;
}

const KINDS = kindsFromSql();
const CATS = [{ key: 'power', title: 'Электроинструмент' }, { key: 'garden', title: 'Садовая техника' }];
const SUBS = [{ key: 'drills', cat: 'power', title: 'Дрели, шуруповёрты и гайковёрты' },
              { key: 'hammers', cat: 'power', title: 'Перфораторы' },
              { key: 'trimmers', cat: 'garden', title: 'Пилы и кусторезы' }];

async function run(withWords) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const email = 'landlord-kind@example.invalid';
  const posted = [];
  await context.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url());
    let data = [];
    if (u.pathname === '/auth/v1/user') {
      data = { id: '40000000-0000-4000-8000-000000000002', email, email_confirmed_at: '2026-01-01T00:00:00.000Z',
               user_metadata: { name: 'Иван', city: 'Новочеркасск' } };
    } else if (u.pathname.endsWith('/admins')) {
      data = [{ email, role: 'admin', active: true, full_name: 'Иван Петров', address: '' }];
    } else if (u.pathname.endsWith('/cats')) {
      data = CATS;
    } else if (u.pathname.endsWith('/subcats')) {
      data = SUBS;
    } else if (u.pathname.endsWith('/iva_kinds')) {
      const sel = u.searchParams.get('select') || '';
      if (sel.includes('words') && !withWords) {
        return route.fulfill({ status: 400, json: { code: '42703', message: 'column iva_kinds.words does not exist' } });
      }
      data = KINDS.map(k => withWords ? k : { key: k.key, cat: k.cat, sub: k.sub, title: k.title, sort: k.sort });
    } else if (u.pathname.endsWith('/tools') && r.method() === 'POST') {
      const body = JSON.parse(r.postData() || '{}');
      posted.push(body);
      data = [Object.assign({ id: '50000000-0000-4000-8000-000000000001' }, body)];
    } else if (u.pathname.endsWith('/landlord_applications')) {
      data = [{ city: 'Новочеркасск', status: 'approved', business_type: 'company' }];
    }
    await route.fulfill({ json: data });
  });

  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(BASE + '/offer.html');   /* лёгкая страница того же сайта — только чтобы записать сессию */
  await page.evaluate(em => {
    localStorage.setItem('iva_auth', JSON.stringify({ tok: 'fake-jwt-token', ref: 'fake-refresh-token', exp: 9999999999999 }));
    localStorage.setItem('iva_tok', JSON.stringify({ tok: 'fake-jwt-token', rtok: 'fake-refresh-token', me: em, uname: 'Иван' }));
    localStorage.removeItem('iva_draft_ad');
  }, email);
  await page.goto(BASE + '/new.html');
  await page.waitForSelector('.steps');
  const on = sel => page.locator(sel + '.on').count();

  if (!withWords) {
    // Слов нет — поля «Что сдаёте» нет, вид выбирается руками, форма как раньше
    assert.equal(await page.locator('#iWhat').count(), 0, 'без слов поля «Что сдаёте» нет');
    await page.locator('[data-cat="power"]').click();
    await page.locator('[data-sub="hammers"]').click();
    assert.equal(await page.locator('[data-kind]').count(), 3, 'виды подкатегории: «Без уточнения» + 2');
    await page.locator('[data-kind="rotary-sds-plus"]').click();
    assert.equal(await on('[data-kind="rotary-sds-plus"]'), 1, 'вид выбирается руками');
    await page.locator('[data-sub="drills"]').click();
    assert.equal(await on('[data-kind=""]'), 1, 'сменили подкатегорию — чужой вид снят');
    assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));
    await browser.close();
    return;
  }

  // 1. Название → категория, подкатегория и вид; фокус остаётся в поле
  await page.locator('#iWhat').fill('Перфоратор SDS-max Hilti TE 70');
  await page.waitForSelector('[data-kind="rotary-sds-max"].on', { timeout: 4000 });
  assert.equal(await on('[data-cat="power"]'), 1, 'категория подобрана');
  assert.equal(await on('[data-sub="hammers"]'), 1, 'подкатегория подобрана');
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'iWhat', 'фокус остался в поле');

  // 2. Вид выбрали руками — дописывание названия его не меняет
  await page.locator('[data-kind="rotary-sds-plus"]').click();
  await page.locator('#iWhat').fill('Перфоратор SDS-max Hilti TE 70 б/у');
  await page.waitForTimeout(700);
  assert.equal(await on('[data-kind="rotary-sds-plus"]'), 1, 'ручной вид не перезаписан');

  // 3. Общее название: только категория и подкатегория
  await page.evaluate(() => localStorage.removeItem('iva_draft_ad'));
  await page.goto(BASE + '/new.html');
  await page.waitForSelector('#iWhat');
  await page.locator('#iWhat').fill('Перфоратор Makita HR2470');
  await page.waitForSelector('[data-sub="hammers"].on', { timeout: 4000 });
  assert.equal(await on('[data-kind=""]'), 1, 'вид не угадывается — «Без уточнения»');

  // 4. Категорию выбрали руками — другое название её не меняет
  await page.locator('[data-cat="garden"]').click();
  await page.locator('#iWhat').fill('Перфоратор SDS-plus Bosch');
  await page.waitForTimeout(700);
  assert.equal(await on('[data-cat="garden"]'), 1, 'ручная категория не перезаписана');

  // 5. Новое объявление с видом доходит до публикации, вид уходит в запросе
  await page.evaluate(() => localStorage.removeItem('iva_draft_ad'));
  await page.goto(BASE + '/new.html');
  await page.waitForSelector('#iWhat');
  await page.locator('#iWhat').fill('Бензопила Stihl MS 180');
  await page.waitForSelector('[data-kind="chainsaw"].on', { timeout: 4000 });
  assert.equal(await on('[data-cat="garden"]'), 1, 'бензопила → садовая техника');
  await page.locator('#bNext').click();
  await page.locator('#bNext').click();
  assert.equal(await page.locator('#iName').inputValue(), 'Бензопила Stihl MS 180', 'название перешло на шаг «Описание»');
  await page.locator('#iDescr').fill('Бензопила в отличном состоянии, шина 35 см, цепь новая');
  await page.locator('#bNext').click();
  await page.locator('#iPrice').fill('900');
  await page.locator('#bNext').click();
  await page.locator('#iCity').fill('Новочеркасск');
  await page.locator('#bSave').click();
  await page.waitForFunction(() => !document.getElementById('bSave'), null, { timeout: 15000 });
  assert.equal(posted.length, 1, 'одно объявление отправлено');
  assert.equal(posted[0].kind, 'chainsaw', 'вид записан');
  assert.equal(posted[0].cat + '/' + posted[0].sub, 'garden/trimmers', 'категория и подкатегория записаны');

  assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

/* Кабинет (cabinet.html): та же логика в форме «Новый инструмент» (select «Вид инструмента»). */
async function runCabinet() {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const email = 'landlord-kind@example.invalid';
  const posted = [];
  await context.route('https://*.supabase.co/**', async route => {
    const r = route.request(), u = new URL(r.url());
    let data = [];
    if (u.pathname === '/auth/v1/user') {
      data = { id: '40000000-0000-4000-8000-000000000002', email, email_confirmed_at: '2026-01-01T00:00:00.000Z', user_metadata: { name: 'Иван' } };
    } else if (u.pathname.endsWith('/cats')) data = CATS;
    else if (u.pathname.endsWith('/subcats')) data = SUBS;
    else if (u.pathname.endsWith('/admins')) data = [{ email, role: 'admin', active: true }];
    else if (u.pathname.endsWith('/iva_kinds')) data = KINDS;
    else if (u.pathname.endsWith('/tools') && r.method() === 'POST') { posted.push(JSON.parse(r.postData() || '{}')); data = null; }
    await route.fulfill(data === null ? { status: 201, body: '' } : { json: data });
  });
  /* В cabinet.html с 12.10 (слияние PR №31) нет элемента #resendLink, а скрипт его ждёт — страница падает до входа
     (отдельная находка, передана владельцу). Чтобы проверить саму форму, тест добавляет пустой элемент в отдаваемую страницу. */
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
  await page.evaluate(() => localStorage.setItem('iva_auth', JSON.stringify({ tok: 'fake-jwt-token', ref: 'fake-refresh-token', exp: 9999999999999 })));
  await page.goto(BASE + '/cabinet.html');
  await page.waitForFunction(() => typeof toolForm === 'function' && S.me && S.cats && S.cats.length, null, { timeout: 15000 });
  await page.evaluate(() => toolForm());
  await page.waitForSelector('#fName');
  await page.locator('#fName').fill('Бензопила Stihl MS 180');
  await page.waitForFunction(() => document.getElementById('fKind').value === 'chainsaw', null, { timeout: 4000 });
  assert.equal(await page.locator('#fCat').inputValue(), 'garden', 'кабинет: категория подобрана');
  assert.equal(await page.locator('#fSub').inputValue(), 'trimmers', 'кабинет: подкатегория подобрана');
  assert.equal(await page.locator('#fKind').isVisible(), true, 'кабинет: поле вида видно');
  // ручная категория не перезаписывается
  await page.locator('#fCat').selectOption('power');
  await page.locator('#fName').fill('Бензопила Stihl MS 180 новая');
  await page.waitForTimeout(700);
  assert.equal(await page.locator('#fCat').inputValue(), 'power', 'кабинет: ручная категория не перезаписана');
  await page.locator('#fCat').selectOption('garden');
  await page.locator('#fSub').selectOption('trimmers');
  await page.locator('#fKind').selectOption('chainsaw');
  await page.locator('#fPrice').fill('900');
  await page.locator('#fSave').click();
  await page.waitForFunction(() => !document.getElementById('fSave'), null, { timeout: 8000 }).catch(() => {});
  assert.equal(posted.length, 1, 'кабинет: инструмент отправлен');
  assert.equal(posted[0].kind + '|' + posted[0].cat + '|' + posted[0].sub, 'chainsaw|garden|trimmers', 'кабинет: вид, категория и подкатегория записаны');
  assert.equal(errors.length, 0, 'кабинет: ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

(async () => {
  await run(true);
  await run(false);
  await runCabinet();
  console.log('PASS: автоподбор вида на сайте — new.html (со словами и без столбца words) и кабинет, вид уходит при сохранении.');
})().catch(e => { console.error(e); process.exit(1); });

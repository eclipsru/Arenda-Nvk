/* 10.85: каталог сайта (catalog.html) — «Вид» и характеристики, как в шторке приложения: характеристики-выбор,
   числа «от–до» с подсказкой, объявления без нужной характеристики — в конце списка, плашки, ссылка с условиями.
   Режимы: full — всё есть; nospecs — нет столбца tools.specs (только «Вид»); old — SQL не применён (каталог как раньше). */
const { chromium } = require('playwright');
const assert = require('assert');
const { kindsFromSql, dirFromSql } = require('./model-fixtures.cjs');

const BASE = process.env.SITE_BASE_URL || 'http://127.0.0.1:8000';
const KINDS = kindsFromSql();
const DIR = dirFromSql();
const nb = s => String(s).replace(/\u00a0/g, ' ');
const id = n => '60000000-0000-4000-8000-00000000000' + n;
const day = n => new Date(Date.UTC(2026, 9, 15 - n)).toISOString();
// t1–t5 — перфораторы SDS-plus, t6 — SDS-max, t7 — дрель (другая подкатегория)
const TOOLS = [
  [1, 'Перфоратор Makita HR2470', 'hammers', 900, { src: 'Сеть 220 В', power: 780, energy: 2.4 }],
  [2, 'Перфоратор Bosch GBH 2-28', 'hammers', 1100, { src: 'Сеть 220 В', power: 880 }],
  [3, 'Перфоратор Makita DHR242', 'hammers', 1200, { src: 'Аккумулятор', volt: 18 }],
  [4, 'Перфоратор б/у', 'hammers', 700, {}],
  [5, 'Перфоратор Интерскол П-22', 'hammers', 600, { src: 'Сеть 220 В', power: 620 }],
  [6, 'Перфоратор Makita HR4013C', 'hammers', 1500, { src: 'Сеть 220 В', power: 1100 }],
  [7, 'Дрель Bosch', 'drills', 400, { src: 'Сеть 220 В', power: 600 }]
];
const KIND_OF = { 1: 'rotary-sds-plus', 2: 'rotary-sds-plus', 3: 'rotary-sds-plus', 4: 'rotary-sds-plus', 5: 'rotary-sds-plus', 6: 'rotary-sds-max', 7: 'drill' };

function route(mode, calls) {
  return async r => {
    const req = r.request(), u = new URL(req.url()), p = u.pathname, sel = u.searchParams.get('select') || '';
    calls.push(p + '?' + u.searchParams.toString());
    let data = [];
    if (p.endsWith('/cats')) data = [{ key: 'power', title: 'Электроинструмент' }];
    else if (p.endsWith('/subcats')) data = [{ key: 'drills', cat: 'power', title: 'Дрели' }, { key: 'hammers', cat: 'power', title: 'Перфораторы' }];
    else if (p.endsWith('/tools') && sel.startsWith('id,kind')) {
      if (mode === 'old' || (mode === 'nospecs' && sel.includes('specs')))
        return r.fulfill({ status: 400, json: { code: '42703', message: 'column tools.' + (sel.includes('specs') ? 'specs' : 'kind') + ' does not exist' } });
      data = TOOLS.map(t => sel.includes('specs') ? { id: id(t[0]), kind: KIND_OF[t[0]], specs: t[4] } : { id: id(t[0]), kind: KIND_OF[t[0]] });
    } else if (p.endsWith('/tools')) {
      data = TOOLS.map(t => ({ id: id(t[0]), name: t[1], cat: 'power', sub: t[2], price: t[3], deposit: 0, imgs: [], img: '', descr: 'Описание',
        delivery: false, delivery_price: 0, terms: '', pickup_city: 'Новочеркасск', pickup_address: '', owner_email: 'owner@example.invalid',
        status: 'active', active: true, rating: null, reviews_count: 0, created_at: day(t[0]) }));
    } else if (p.endsWith('/iva_kinds')) {
      if (mode === 'old') return r.fulfill({ status: 404, json: { code: 'PGRST205', message: "Could not find the table 'public.iva_kinds'" } });
      data = KINDS;
    } else if (p.endsWith('/iva_spec_fields')) data = DIR.fields;
    await r.fulfill({ json: data });
  };
}

async function run(mode) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const calls = [];
  await context.route('https://*.supabase.co/**', route(mode, calls));
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const ids = () => page.locator('#grid .ad').evaluateAll(els => els.map(e => e.getAttribute('data-id').slice(-1)).join(''));
  const chips = async () => nb((await page.locator('#chips .chip').allTextContents()).join(' | '));
  await page.goto(BASE + '/catalog.html?city=&cat=power&sub=hammers');
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 6, null, { timeout: 15000 });

  if (mode === 'old') {
    await page.waitForTimeout(800);
    assert.equal(await page.locator('#filters [data-kind]').count(), 0, 'old: без видов в базе блока «Вид» нет');
    // вид в ссылке не мешает: каталог как раньше
    await page.goto(BASE + '/catalog.html?city=&cat=power&sub=hammers&kind=rotary-sds-plus&s.src=' + encodeURIComponent('Сеть 220 В'));
    await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 6, null, { timeout: 15000 });
    await page.waitForTimeout(800);
    assert.equal(await page.locator('#grid .ad').count(), 6, 'old: вид и характеристика из ссылки без SQL не скрывают объявления');
    assert.equal(errors.length, 0, 'old: ошибки: ' + errors.join('; '));
    await browser.close();
    return;
  }

  // «Вид» — виды подкатегории, где есть объявления, с числами
  await page.waitForSelector('#filters [data-kind="rotary-sds-plus"]', { timeout: 8000 });
  assert.equal(nb(await page.locator('#filters [data-kind="rotary-sds-plus"]').textContent()), 'Перфоратор SDS-plus5', 'вид с числом');
  assert.equal(nb(await page.locator('#filters [data-kind="rotary-sds-max"]').textContent()), 'Перфоратор SDS-max1', 'второй вид');
  assert.ok(calls.every(c => !/select=\*/.test(c) || !c.startsWith('/rest/v1/tools')) && calls.some(c => /\/tools\?status=eq\.active&active=eq\.true&limit=1000&select=id%2Ckind/.test(c)),
    'вид и характеристики — отдельным запросом активных объявлений');
  await page.locator('#filters [data-kind="rotary-sds-plus"]').click();
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 5);
  assert.equal(await page.locator('#found').textContent(), 'Найдено 5 объявлений', 'только вид');
  assert.ok(/Перфоратор SDS-plus/.test(await chips()), 'плашка вида');

  if (mode === 'nospecs') {
    assert.equal(await page.locator('#filters [data-spec]').count() + await page.locator('#filters [data-rng]').count(), 0, 'nospecs: характеристик нет');
    assert.equal(errors.length, 0, 'nospecs: ошибки: ' + errors.join('; '));
    await browser.close();
    return;
  }

  // характеристики-выбор: только значения, что есть у объявлений
  const srcItems = await page.locator('#filters [data-spec="src"]').allTextContents();
  assert.deepEqual(srcItems.map(nb), ['Любое', 'Сеть 220 В3', 'Аккумулятор1'], 'Питание — значения с числами');
  // числа: только поля, указанные хоть у одного объявления вида (энергия — у одного, вес — ни у кого)
  const rng = await page.locator('#filters [data-rng][data-end="0"]').evaluateAll(els => els.map(e => e.getAttribute('data-rng')));
  assert.deepEqual(rng, ['volt', 'power', 'energy'], 'числовые поля — только указанные');
  const powerHint = nb(await page.locator('#filters [data-rng="power"]').first().locator('xpath=../following-sibling::div[1]').textContent());
  assert.equal(powerHint, 'Сейчас в выдаче: от 620 до 880 Вт.', 'подсказка под «Мощностью»');

  // выбор «Сеть 220 В»: подходящие сначала, без «Питания» — в конце; аккумуляторный скрыт
  await page.locator('#filters [data-spec="src"][data-val="Сеть 220 В"]').click();
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 4);
  const order1 = await ids();
  assert.ok(order1.endsWith('4') && order1.split('').sort().join('') === '1245', 'без характеристики — в конце: ' + order1);
  assert.equal(nb(await page.locator('#filters #fSpecNote').textContent()), 'Ещё 1 объявление без указанной характеристики — в конце списка.', 'строка про конец списка');

  // «от 700» по Enter: 620 Вт скрыт, без мощности (t4) — в конце
  await page.locator('#filters [data-rng="power"][data-end="0"]').fill('700');
  await page.locator('#filters [data-rng="power"][data-end="0"]').press('Enter');
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 3);
  const order2 = await ids();
  assert.ok(order2.endsWith('4') && order2.split('').sort().join('') === '124', '«от 700»: ' + order2);
  assert.ok(/Питание: Сеть 220 В/.test(await chips()) && /Мощность: от 700 Вт/.test(await chips()), 'плашки: ' + await chips());
  assert.equal(await page.locator('#fMobN').textContent(), '5', 'счётчик фильтров: категория, подкатегория, вид, питание, мощность');
  const url = page.url();
  assert.ok(url.includes('kind=rotary-sds-plus') && url.includes('s.src=') && url.includes('r.power=700..'), 'условия в ссылке: ' + url);
  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT + '/s85-catalog.png', fullPage: true });

  // ссылка открывается с теми же условиями
  await page.goto(url);
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 3, null, { timeout: 15000 });
  assert.equal(await page.locator('#filters [data-rng="power"][data-end="0"]').inputValue(), '700', 'поле «от» восстановлено');
  // дробное «до»: 2,5 Дж — у Makita 2,4 подходит, у остальных энергии нет — в конце
  await page.locator('#filters [data-rng="energy"][data-end="1"]').fill('2,5');
  await page.locator('#filters [data-rng="energy"][data-end="1"]').press('Enter');
  await page.waitForFunction(() => /Энергия удара: до 2,5 Дж/.test(document.getElementById('chips').textContent.replace(/\u00a0/g, ' ')));
  assert.equal((await ids())[0], '1', 'с энергией 2,4 — первым');
  // снять плашку «Мощность» и вид
  await page.locator('#chips [data-x="r:power"]').click();
  await page.locator('#chips [data-x="r:energy"]').click();
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 4);
  await page.locator('#chips [data-x="kind"]').click();
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 6);
  assert.ok(!/Питание/.test(await chips()) && !page.url().includes('kind='), 'вид снят — характеристики сняты');
  // смена подкатегории снимает вид
  await page.locator('#filters [data-kind="rotary-sds-plus"]').click();
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 5);
  await page.locator('#filters [data-sub="drills"]').click();
  await page.waitForFunction(() => document.querySelectorAll('#grid .ad').length === 1);
  assert.ok(!page.url().includes('kind='), 'другая подкатегория — вид снят');
  // вид из ссылки не из этой подкатегории — снимается после загрузки видов
  await page.goto(BASE + '/catalog.html?city=&cat=power&sub=drills&kind=rotary-sds-plus&s.src=' + encodeURIComponent('Сеть 220 В'));
  await page.waitForSelector('#filters [data-kind="drill"]', { timeout: 15000 });
  assert.ok(!page.url().includes('kind=') && !page.url().includes('s.src='), 'чужой вид из ссылки снят: ' + page.url());
  assert.equal(await page.locator('#grid .ad').count(), 1, 'чужой вид из ссылки не скрывает объявления');
  assert.equal(errors.length, 0, 'ошибки страницы: ' + errors.join('; '));
  await browser.close();
}

(async () => {
  await run('full');
  await run('nospecs');
  await run('old');
  console.log('PASS: каталог сайта — «Вид», характеристики-выбор, числа «от–до», без характеристики в конце, ссылка; без specs; без SQL — как раньше.');
})().catch(e => { console.error(e); process.exit(1); });

// Сторож «комиссия 1% навсегда» (этап П6).
//
// Решение владельца (01.10.2026): вместо запускных 0% — комиссия площадки 1% от суммы
// аренды, всегда. Этот сторож ловит откат: если в текстах снова появится «0% комиссии»,
// в форме владельца вернётся подсказка 5%, или страницы начнут грузить старые версии
// partners.js / landlord-register.js — проверка упадёт.
//
// Запуск: node tests/p6-commission.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

// Все html-файлы проекта (кроме служебных папок), чтобы искать тексты и версии ассетов.
const htmlFiles = [];
const walk = d => fs.readdirSync(d, { withFileTypes: true }).forEach(e => {
  if (e.name === 'node_modules' || e.name === '.git') return;
  const p = path.join(d, e.name);
  if (e.isDirectory()) return walk(p);
  if (e.name.endsWith('.html')) htmlFiles.push(p);
});
walk(ROOT);
const body = f => fs.readFileSync(f, 'utf8');

try {
  // 1. Нигде не осталось запускных «0% комиссии»
  const all = [
    ...htmlFiles.map(f => [f, body(f)]),
    ['assets/partners.js', read('assets/partners.js')],
    ['assets/landlord-register.js', read('assets/landlord-register.js')],
  ];
  for (const [f, src] of all) {
    assert.ok(!/0\s?%\s?комисси/i.test(src),
      `${f}: остался текст про 0% комиссию — решение владельца «1% навсегда» не выполнено`);
  }

  // 2. Стандартная ставка 1% названа там, где её видит арендодатель
  const claim = read('claim.html');
  assert.match(claim, /Комиссия площадки — 1% от суммы аренды/,
    'claim.html: в предложении забрать заказ не названа ставка 1%');
  assert.match(read('assets/partners.js'), /Стандартная комиссия площадки — 1%/,
    'partners.js: в разделе «Комиссия и расчёты» не названа стандартная ставка 1%');
  assert.match(read('partner-terms.html'), /Стандартная комиссия площадки — 1%/,
    'partner-terms.html: в правилах подключения не названа стандартная ставка 1%');
  assert.match(read('offer.html'), /Стандартный размер комиссии — 1%/,
    'offer.html: в оферте не названа стандартная ставка 1%');
  assert.match(read('index.html'), /Комиссия площадки — 1% от суммы аренды/,
    'index.html: в блоке для арендодателей на главной не названа ставка 1%');
  assert.match(read('assets/landlord-register.js'), /Комиссия площадки — 1%/,
    'landlord-register.js: в тексте согласия заявителя не названа ставка 1%');

  // 3. Форма владельца: ставка по умолчанию — 1, поле остаётся редактируемым
  const chief = read('chief.html');
  assert.match(chief, /a\.fee_pct != null \? a\.fee_pct : 1\)/,
    'chief.html: в форме арендодателя подставляется не 1% по умолчанию');
  assert.match(chief, /id="eFee" type="number"/,
    'chief.html: поле ставки комиссии должно оставаться редактируемым (решение может измениться)');

  // 4. Версии изменённых ассетов подняты везде и единообразно — иначе арендодатели
  //    увидят старые тексты из кеша.
  const versions = {};
  for (const f of htmlFiles) {
    const src = body(f);
    for (const m of src.matchAll(/assets\/(partners|landlord-register)\.js\?v=([a-z0-9-]+)/g)) {
      versions[m[1]] = versions[m[1]] || new Set();
      versions[m[1]].add(m[2]);
    }
  }
  for (const asset of ['partners', 'landlord-register']) {
    assert.ok(versions[asset], `ни одна страница не подключает assets/${asset}.js`);
    assert.equal(versions[asset].size, 1,
      `версия assets/${asset}.js разная на страницах (${[...versions[asset]].join(', ')}) — ` +
      'после правки текстов версию надо поднять везде');
    assert.equal([...versions[asset]][0], '20261001-p6-commission',
      `assets/${asset}.js: версия не 20261001-p6-commission — тексты комиссии могли не доехать`);
  }

  console.log('Комиссия в порядке: стандарт 1% назван во всех текстах для арендодателей, ' +
    'запускных 0% нигде не осталось, форма владельца по умолчанию 1%, версии ассетов единые.');
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

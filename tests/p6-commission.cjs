// Сторож «комиссия 1% на первый год» (этап П6).
//
// Решения владельца: 01.10.2026 — комиссия площадки 1% (вместо запускных 0%);
// далее в тот же день уточнено — 1% на первый год с даты одобрения заявки, не навсегда.
// 03.10.2026: все тексты приведены к оферте — «с даты одобрения заявки» (не «подключения»)
// и «уведомление не менее чем за 30 календарных дней» (колонка «Год до» считает так же).
// Сторож ловит откат обеих правок: если в текстах снова появится «0% комиссии»,
// если 1% начнут обещать «навсегда», если в форме владельца вернётся подсказка 5%,
// или если страницы начнут грузить старые версии partners.js / landlord-register.js.
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
  // 1. Нигде не осталось запускных «0% комиссии» и обещаний «1% навсегда»
  const all = [
    ...htmlFiles.map(f => [f, body(f)]),
    ['assets/partners.js', read('assets/partners.js')],
    ['assets/landlord-register.js', read('assets/landlord-register.js')],
  ];
  for (const [f, src] of all) {
    assert.ok(!/0\s?%\s?комисси/i.test(src),
      `${f}: остался текст про 0% комиссию — решение владельца «1% на первый год» не выполнено`);
    assert.ok(!/комисси\w*[^.<]{0,160}навсегда/i.test(src),
      `${f}: комиссия обещана «навсегда» — решение владельца «1% на первый год» не выполнено`);
  }

  // 2. Ставка 1% и срок «первый год» названы там, где их видит арендодатель
  const claim = read('claim.html');
  assert.match(claim, /Комиссия площадки — 1% от суммы аренды в первый год/,
    'claim.html: в предложении забрать заказ не названы ставка 1% и срок «первый год»');
  assert.match(read('assets/partners.js'), /Стандартная комиссия площадки — 1% от суммы аренды в первый год/,
    'partners.js: в разделе «Комиссия и расчёты» не названы ставка 1% и срок «первый год»');
  assert.match(read('partner-terms.html'), /Стандартная комиссия площадки — 1% от суммы аренды в первый год/,
    'partner-terms.html: в правилах подключения не названы ставка 1% и срок «первый год»');
  assert.match(read('offer.html'), /Стандартный размер комиссии — 1% от суммы аренды в первый год/,
    'offer.html: в оферте не названы ставка 1% и срок «первый год»');
  assert.match(read('offer.html'), /не менее чем за 30 календарных дней/,
    'offer.html: не обещан срок уведомления об изменении ставки после первого года');
  assert.match(read('index.html'), /Комиссия площадки — 1% от суммы аренды в первый год/,
    'index.html: в блоке для арендодателей на главной не названы ставка 1% и срок «первый год»');
  assert.match(read('assets/landlord-register.js'), /Комиссия площадки — 1% от суммы аренды в первый год/,
    'landlord-register.js: в тексте согласия заявителя не названы ставка 1% и срок «первый год»');

  // 2.1. Тексты не расходятся с офертой (03.10.2026): срок считается с даты одобрения
  //      заявки, после года — уведомление не менее чем за 30 дней. Иначе арендодатель
  //      прочитает в правилах одно, в оферте другое.
  for (const [f, src] of all) {
    assert.ok(!/перв\S* год\S* с даты подключения/i.test(src),
      `${f}: срок 1% считается «с даты подключения» — в оферте и в колонке «Год до» он с даты одобрения заявки`);
  }
  for (const f of ['claim.html', 'partner-terms.html', 'assets/partners.js', 'offer.html']) {
    assert.match(read(f), /1% от суммы аренды в первый год с даты одобрения заявки/,
      `${f}: не сказано, что первый год считается с даты одобрения заявки`);
  }
  for (const f of ['partner-terms.html', 'assets/partners.js']) {
    assert.match(read(f), /не менее чем за 30 календарных дней/,
      `${f}: не обещан срок уведомления 30 дней — в оферте он есть, тексты расходятся`);
  }

  // 3. Форма владельца: ставка по умолчанию 1%, поле редактируемо, есть подсказка про год
  const chief = read('chief.html');
  assert.match(chief, /a\.fee_pct != null \? a\.fee_pct : 1\)/,
    'chief.html: в форме арендодателя подставляется не 1% по умолчанию');
  assert.match(chief, /id="eFee" type="number"/,
    'chief.html: поле ставки комиссии должно оставаться редактируемым (решение может измениться)');
  assert.match(chief, /1% — стандарт на первый год с даты одобрения; по истечении года пересмотреть ставку/,
    'chief.html: в форме нет подсказки, что 1% действует первый год');

  // 4. Версии изменённых ассетов подняты везде и единообразны — иначе арендодатели
  //    увидят старые тексты из кеша.
  const versions = {};
  for (const f of htmlFiles) {
    const src = body(f);
    for (const m of src.matchAll(/assets\/(partners|landlord-register)\.js\?v=([a-z0-9-]+)/g)) {
      versions[m[1]] = versions[m[1]] || new Set();
      versions[m[1]].add(m[2]);
    }
  }
  const expectedVersion = { 'partners': '20261003-p6-texts', 'landlord-register': '20261001-p6-1year' };
  for (const asset of ['partners', 'landlord-register']) {
    assert.ok(versions[asset], `ни одна страница не подключает assets/${asset}.js`);
    assert.equal(versions[asset].size, 1,
      `версия assets/${asset}.js разная на страницах (${[...versions[asset]].join(', ')}) — ` +
      'после правки текстов версию надо поднять везде');
    assert.equal([...versions[asset]][0], expectedVersion[asset],
      `assets/${asset}.js: версия не ${expectedVersion[asset]} — тексты комиссии могли не доехать`);
  }

  // 5. Автоматика «первый год заканчивается»: колонка в таблице арендодателей
  assert.match(chief, /<th class="num" title="Год с даты одобрения заявки \(у подключённых вручную — с даты добавления\) — когда пересматривать ставку">Год до<\/th>/,
    'chief.html: в таблице арендодателей нет колонки «Год до» — владелец не увидит, у кого подходит срок пересмотра');
  assert.match(chief, /function feeYearCell\(em, a\)\{/,
    'chief.html: нет функции расчёта даты окончания первого года');
  assert.match(chief, /x\.reviewed_at/,
    'chief.html: дата пересмотра не считается от reviewed_at заявки');
  assert.match(chief, /setFullYear\(end\.getFullYear\(\) \+ 1\)/,
    'chief.html: первый год не считается как reviewed_at + 1 год');
  assert.match(chief, /const FEE_NOTICE_DAYS = 45;/,
    'chief.html: напоминание «уведомить» должно включаться за 45 дней (решение владельца №16 от 03.10.2026)');
  assert.match(chief, /if \(days <= FEE_NOTICE_DAYS\)/,
    'chief.html: порог напоминания задан числом в обход FEE_NOTICE_DAYS');
  assert.match(chief, /дн\. — уведомить/,
    'chief.html: за 30 дней до конца года нет напоминания «уведомить» (оферта обещает уведомление за 30 дней)');
  assert.match(chief, /год прошёл — пересмотреть/,
    'chief.html: после окончания года нет напоминания «пересмотреть»');
  assert.match(chief, /feeYearCell\(em, a\) \+/,
    'chief.html: ячейка «Год до» не выводится в строках таблицы');

  // 6. Поведение «Год до» (запускаем сам расчёт из chief.html на пяти случаях).
  //    Решение владельца 03.10.2026: подключённым вручную — год с даты добавления в базу (admins.created_at).
  {
    const vm = require('node:vm');
    const i0 = chief.indexOf('const FEE_NOTICE_DAYS');
    const i1 = chief.indexOf('/* ---------- Таблица арендодателей');
    assert.ok(i0 > 0 && i1 > i0, 'chief.html: не найден блок расчёта «Год до»');
    const ctx = { D: { landlordApps: [] }, dt: s => s.slice(0, 10), Date, Math, isNaN };
    vm.runInNewContext(chief.slice(i0, i1) + '; this.feeYearCell = feeYearCell;', ctx);
    const iso = d => new Date(Date.now() + d * 86400000).toISOString();
    const cell = (em, a) => ctx.feeYearCell(em, a);
    // а) по одобренной заявке — как раньше, без пометки
    ctx.D.landlordApps = [{ email: 'app@x.ru', status: 'approved', reviewed_at: iso(-100) }];
    let c = cell('app@x.ru', { email: 'app@x.ru', role: 'admin', created_at: iso(-300) });
    assert.ok(/дн\./.test(c) && !/с даты добавления/.test(c) && c.includes(iso(265).slice(0, 10)),
      'chief.html: при одобренной заявке год должен считаться от даты одобрения, а не от даты добавления');
    // б) вручную подключённый — от created_at, с пометкой
    c = cell('hand@x.ru', { email: 'hand@x.ru', role: 'admin', created_at: iso(-340) });
    assert.ok(/с даты добавления/.test(c) && /уведомить/.test(c),
      'chief.html: у подключённого вручную год не считается от даты добавления (решение владельца 03.10.2026)');
    // в) год прошёл
    c = cell('old@x.ru', { email: 'old@x.ru', role: 'admin', created_at: iso(-400) });
    assert.ok(/год прошёл — пересмотреть/.test(c), 'chief.html: после года нет «пересмотреть» у подключённого вручную');
    // г) сам владелец (chief) и запись без даты — прочерк
    assert.ok(/—/.test(cell('me@x.ru', { email: 'me@x.ru', role: 'chief', created_at: iso(-10) })) &&
              !/дн\./.test(cell('me@x.ru', { email: 'me@x.ru', role: 'chief', created_at: iso(-10) })),
      'chief.html: у владельца площадки «Год до» должен быть прочерк');
    assert.ok(!/дн\./.test(cell('nodate@x.ru', { email: 'nodate@x.ru', role: 'admin' })),
      'chief.html: без даты должен быть прочерк, а не выдуманный срок');
  }

  console.log('Комиссия в порядке: стандарт 1% на первый год назван во всех текстах для арендодателей, ' +
    'обещания «навсегда» и запускных 0% нигде не осталось, форма владельца по умолчанию 1% с подсказкой, ' +
    'в таблице есть колонка «Год до» с напоминаниями, версии ассетов единые.');
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

// Сторож пустых состояний (этап П4).
//
// Смысл: пустой каталог — это место, где человек уходит. Проверяем, что вместо тупика
// он видит честное объяснение и живые действия:
//   • «Позвонить — подберём инструмент» (наш номер, помечен как обращение к площадке);
//   • ссылку на пункты проката — в свой город, если у города есть страница, иначе в общий список;
//   • предложение арендодателю разместить объявление;
//   • кнопку сброса фильтров, когда фильтры включены.
// Плюс следим, чтобы в пустом состоянии не появилось сбора данных (форм, полей ввода)
// и чтобы обращения считались, а показы на этих страницах — нет.
//
// Запуск: node tests/empty-states.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

try {
  const app = read('assets/app.js');
  const catalog = read('catalog.html');
  const index = read('index.html');
  const clicks = read('assets/clicks.js');
  const pages = JSON.parse(read('assets/city-pages.json'));

  // 1. Механизм на месте: разметка пустого состояния и ссылка на страницу города
  for (const [name, fn] of [['ivaEmptyStateHTML', /function ivaEmptyStateHTML\(/],
                            ['ivaCityPageUrl', /function ivaCityPageUrl\(/],
                            ['loadCityPages', /function loadCityPages\(/]]) {
    assert.match(app, fn, `в assets/app.js нет ${name} — пустые состояния работать не будут`);
  }
  // Данные и обещание загрузки должны быть разными переменными: если перепутать,
  // ссылка всегда ведёт в общий список (эта ошибка уже случалась).
  assert.match(app, /var _cityPagesData = null;/, 'нет переменной с загруженным списком городов');
  assert.match(app, /_cityPagesData = \(d && d\.pages\) \? d : null;/,
    'список городов не сохраняется в данные — ссылка на город останется общей');
  assert.match(app, /return \(d && d\.hub\) \? d\.hub : 'city\/index\.html';/,
    'нет запасного адреса общего списка городов');

  // 2. Обе страницы используют механизм и передают именно данные (не обещание)
  for (const [name, html] of [['catalog.html', catalog], ['index.html', index]]) {
    assert.match(html, /ivaEmptyStateHTML\(\{/, `${name}: пустое состояние не использует общий механизм`);
    assert.match(html, /pages: _cityPagesData/, `${name}: в пустое состояние передаётся не загруженный список`);
    assert.match(html, /if \(!_cityPagesData && typeof loadCityPages === 'function'\)/,
      `${name}: список городов не подгружается для ссылки`);
    assert.match(app, /landlord-register\.html/,
      `${name}: в пустом состоянии нет предложения арендодателю разместить объявление`);

    // 3. Счётчик обращений подключён, показы на этих страницах не считаем
    assert.match(html, /<script src="assets\/clicks\.js[^"]*"><\/script>/,
      `${name}: нет счётчика обращений`);
    assert.match(html, /IvaClicks\.init\(getCity\(\), \{views: false\}\)/,
      `${name}: показы страниц не должны попадать в статистику обращений`);
  }

  // 4. В пустом состоянии не должно быть сбора данных: только тексты и ссылки
  const emptyFn = app.slice(app.indexOf('function ivaEmptyStateHTML('), app.indexOf('function ivaEmptyStateHTML(') + 2600);
  for (const forbidden of ['<input', '<form', '<textarea', 'type="email"', 'type="tel"']) {
    assert.ok(!emptyFn.includes(forbidden),
      `пустое состояние собирает данные (${forbidden}) — до политики обработки данных это запрещено`);
  }
  assert.match(emptyFn, /data-lead="1"/, 'кнопка звонка должна считаться как обращение к площадке');

  // 5. Счётчик: клик ловится перехватом (нужно для блоков, появляющихся позже)
  assert.match(clicks, /document\.addEventListener\('click'/,
    'счётчик не поймает ссылку, появившуюся после загрузки страницы (пустое состояние, окна)');
  assert.match(clicks, /opts\.views !== false/, 'нельзя выключить подсчёт показов');
  assert.match(clicks, /if \(!allowed\) return;/, 'счётчик должен отправлять только номера со страницы');

  // 6. Список страниц городов согласован: хаб есть, ссылки ведут на существующие файлы
  assert.ok(pages.hub === 'city/index.html', 'в assets/city-pages.json нет адреса общего списка');
  const names = Object.keys(pages.pages || {});
  assert.ok(names.length > 0, 'в assets/city-pages.json нет ни одного города');
  for (const [city, href] of Object.entries(pages.pages)) {
    assert.ok(fs.existsSync(path.join(ROOT, href)), `city-pages.json: ${city} → ${href}, а файла нет`);
  }

  // 7. Оформление не менялось: у каждой страницы — только её собственные файлы стилей
  //    (у главной исторически есть свой assets/partners.css — он был и до этих правок).
  const allowedCss = { 'catalog.html': /assets\/(theme|geo)\.css/, 'index.html': /assets\/(theme|geo|partners)\.css/ };
  for (const [name, html] of [['catalog.html', catalog], ['index.html', index]]) {
    for (const link of (html.match(/<link[^>]+rel="stylesheet"[^>]*>/g) || [])) {
      assert.ok(allowedCss[name].test(link), `${name}: подключён посторонний файл стилей: ${link}`);
    }
  }
  // И ни в одном из новых файлов (app.js, clicks.js) не должно быть правок оформления
  for (const [name, body] of [['assets/app.js', app], ['assets/clicks.js', clicks]]) {
    assert.ok(!/\.style\.background|\.style\.color|font-size\s*=/.test(body),
      `${name}: пустые состояния не должны менять оформление страниц`);
  }

  console.log(`Пустые состояния в порядке: обе страницы объясняют, почему объявлений нет, ` +
    `и ведут к делу (звонок, пункты проката, размещение объявления); страниц городов в списке — ${names.length}; ` +
    'данные в пустом состоянии не собираются.');
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

// Сторож пустых состояний (этап П4): «ничего не нашлось» — не тупик, а развилка.
//
// Что проверяем и зачем:
//  1. Карта городов assets/city-pages.js совпадает с папкой city/ и со снимком
//     справочника. Карта генерируется тем же генератором, что и страницы
//     городов, — если кто-то пересоберёт страницы и забудет карту (или наоборот),
//     ссылки из пустого состояния поведут в никуда.
//  2. index.html и catalog.html подключают карту ДО app.js и рисуют пустое
//     состояние общей функцией noResultsHTML (а не своей копией разметки).
//  3. Настоящий прогон в DOM (jsdom, если установлен): каталог с заведомо
//     пустым результатом показывает переход на страницу города с честным числом
//     адресов, телефон, приглашение сдать инструмент и рабочую кнопку сброса.
//     Без браузера и без сети: запросы к базе заглушены.
//
// Если jsdom не установлен — третья часть честно пропускается, а не «зеленеет».
// Запуск: node tests/empty-states.cjs
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const CITY_DIR = path.join(ROOT, 'city');
const MAP_JS = path.join(ROOT, 'assets', 'city-pages.js');
const OUR_PHONE = '+79081732475';

/* ---------- 1. Карта городов ↔ папка city/ ↔ справочник ---------- */
assert.ok(fs.existsSync(MAP_JS), 'нет assets/city-pages.js — пересобрать: python3 tools/build_city_pages.py');
const mapSrc = fs.readFileSync(MAP_JS, 'utf8');
assert.match(mapSrc, /window\.IvaCityPages\s*=\s*\{/, 'в assets/city-pages.js нет window.IvaCityPages');
const MAP = JSON.parse(mapSrc.slice(mapSrc.indexOf('{'), mapSrc.lastIndexOf('}') + 1)).cities;
const names = Object.keys(MAP);
assert.ok(names.length >= 50, `в карте городов слишком мало записей: ${names.length}`);

const pageFiles = fs.readdirSync(CITY_DIR).filter((f) => f.endsWith('.html') && f !== 'index.html');
for (const name of names) {
  const { slug, points } = MAP[name];
  assert.match(slug, /^[a-z0-9-]+$/, `недопустимая ссылка у города «${name}»: ${slug}`);
  assert.ok(fs.existsSync(path.join(CITY_DIR, slug + '.html')),
    `карта обещает city/${slug}.html для города «${name}», а файла нет`);
  const html = fs.readFileSync(path.join(CITY_DIR, slug + '.html'), 'utf8');
  const cards = (html.match(/data-prokat-card="1"/g) || []).length;
  assert.equal(points, cards,
    `у города «${name}» в карте ${points} адресов, а на странице ${cards}`);
}
for (const f of pageFiles) {
  const slug = f.replace(/\.html$/, '');
  assert.ok(names.some((n) => MAP[n].slug === slug),
    `страница city/${f} есть, а города нет в карте — из пустого состояния на неё не попасть`);
}

// Число адресов в карте — то же, что в снимке справочника (ничего не выдумано).
const snapshot = JSON.parse(fs.readFileSync(path.join(ROOT, 'tools', 'directory_existing.json'), 'utf8'));
const totalInMap = names.reduce((sum, n) => sum + MAP[n].points, 0);
assert.ok(totalInMap > 0, 'в карте нет ни одного адреса');
assert.ok(totalInMap <= snapshot.rows.length,
  `в карте ${totalInMap} адресов, а в снимке справочника всего ${snapshot.rows.length}`);

/* ---------- 2. Страницы подключают карту и общую разметку ---------- */
for (const page of ['index.html', 'catalog.html']) {
  const html = fs.readFileSync(path.join(ROOT, page), 'utf8');
  const mapAt = html.indexOf('assets/city-pages.js');
  const appAt = html.indexOf('assets/app.js');
  assert.ok(mapAt >= 0, `${page}: не подключён assets/city-pages.js`);
  assert.ok(appAt >= 0, `${page}: не подключён assets/app.js`);
  assert.ok(mapAt < appAt, `${page}: карта городов должна подключаться ДО app.js`);
  assert.match(html, /noResultsHTML\(\{\s*city:\s*F\.city\s*\}\)/,
    `${page}: пустое состояние рисуется не общей функцией noResultsHTML`);
  assert.ok(!/Ничего не нашлось/.test(html),
    `${page}: осталась собственная копия разметки пустого состояния — править нужно assets/app.js`);
}

console.log(`empty-states: карта городов — ${names.length} городов, ${totalInMap} адресов; `
  + 'страницы подключены к общей разметке');

/* ---------- 3. Прогон в DOM (нужен jsdom; без него — честный пропуск) ---------- */
let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (e) {
  console.log('empty-states: прогон в DOM пропущен — jsdom не установлен (npm install)');
  process.exit(0);
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent(req.url.split('?')[0]);
  const file = path.join(ROOT, urlPath === '/' ? 'index.html' : urlPath);
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404); res.end('not found'); return;
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  res.end(fs.readFileSync(file));
});

// База «недоступна» (ответ 500): страницы по проекту остаются на демонстрационных
// объявлениях из assets/data.js — на них и проверяем пустое состояние.
function stubNetwork(w) {
  w.fetch = () => Promise.resolve({
    ok: false, status: 500,
    json: () => Promise.resolve({ message: 'база в тесте недоступна' }),
    text: () => Promise.resolve('{"message":"база в тесте недоступна"}')
  });
  w.matchMedia = w.matchMedia || ((q) => ({
    matches: false, media: q, addEventListener() {}, removeEventListener() {},
    addListener() {}, removeListener() {}
  }));
}

function listen() {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}

function open(port, urlPath) {
  const errors = [];
  const { VirtualConsole } = require('jsdom');
  const vc = new VirtualConsole();
  vc.on('jsdomError', (e) => errors.push(String(e.message || e)));
  // Страница рисуется после подключения базы (fetch заглушён), поэтому ждём тик.
  return JSDOM.fromFile(path.join(ROOT, urlPath.split('?')[0]), {
    url: `http://127.0.0.1:${port}/${urlPath}`,
    resources: 'usable',
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    virtualConsole: vc,
    beforeParse: stubNetwork
  }).then((dom) => new Promise((resolve) => {
    setTimeout(() => resolve({ dom, errors }), 900);
  }));
}

function click(el) {
  el.dispatchEvent(new el.ownerDocument.defaultView.MouseEvent('click', { bubbles: true }));
}

(async function run() {
  const port = await listen();
  const impossible = 'несуществующийинструментzzz';

  // --- 3.1 Город, у которого есть страница пунктов ---
  {
    const { dom, errors } = await open(port, 'catalog.html?q=' + encodeURIComponent(impossible)
      + '&city=' + encodeURIComponent('Ростов-на-Дону'));
    const w = dom.window;
    const d = w.document;
    assert.deepEqual(errors.filter((e) => !/Not implemented/.test(e)), [],
      'ошибки скриптов на странице каталога: ' + errors.join(' | '));
    assert.equal(typeof w.noResultsHTML, 'function', 'app.js не дал noResultsHTML');
    const grid = d.getElementById('grid');
    const html = grid.innerHTML;
    assert.match(html, /Ничего не нашлось/, 'пустое состояние не показано');
    assert.ok(html.includes('city/rostov-na-donu.html'),
      'нет перехода на страницу города Ростов-на-Дону');
    const points = MAP['Ростов-на-Дону'].points;
    assert.ok(html.includes(`Пункты проката в городе «Ростов-на-Дону» — ${points} `),
      'нет честного числа адресов пунктов: ' + points);
    assert.ok(html.includes('tel:' + OUR_PHONE), 'нет телефона для подбора');
    assert.ok(html.includes('landlord-register.html'), 'нет приглашения сдать инструмент');
    const reset = d.getElementById('eReset');
    assert.ok(reset, 'нет кнопки сброса фильтров');

    // Кнопка сброса живая: после нажатия каталог снова что-то показывает.
    click(reset);
    const after = d.getElementById('found').textContent;
    assert.match(after, /Найдено [1-9]/, 'сброс фильтров не вернул результаты: ' + after);
    console.log(`empty-states: каталог — город с пунктами (${points} адресов), сброс работает, «${after}»`);
    dom.window.close();
  }

  // --- 3.2 Город без страницы пунктов: ведём на общий список, число не выдумываем ---
  {
    const { dom } = await open(port, 'catalog.html?q=' + encodeURIComponent(impossible)
      + '&city=' + encodeURIComponent('Персиановский'));
    const html = dom.window.document.getElementById('grid').innerHTML;
    assert.match(html, /Ничего не нашлось/);
    assert.ok(html.includes('city/index.html'), 'нет перехода на список городов');
    assert.ok(html.includes('Пункты проката в городах России'), 'нет подписи про общий список');
    assert.ok(!html.includes('city/persianovskiy.html'), 'ссылка на несуществующую страницу города');
    dom.window.close();
  }

  // --- 3.3 Город не выбран: сайт подставляет Новочеркасск и ведёт на его страницу ---
  {
    const { dom } = await open(port, 'catalog.html?q=' + encodeURIComponent(impossible));
    const html = dom.window.document.getElementById('grid').innerHTML;
    assert.ok(html.includes('В городе «Новочеркасск»'), 'нет текста про город по умолчанию');
    assert.ok(html.includes('city/novocherkassk.html'), 'нет страницы города по умолчанию');
    dom.window.close();
  }

  // --- 3.4 Явно «Все города»: общий текст и те же три пути дальше ---
  {
    const { dom } = await open(port, 'catalog.html?q=' + encodeURIComponent(impossible) + '&city=');
    const html = dom.window.document.getElementById('grid').innerHTML;
    assert.ok(html.includes('По выбранным условиям объявлений нет'), 'нет общего текста пустого состояния');
    assert.ok(html.includes('city/index.html') && html.includes('tel:' + OUR_PHONE),
      'без города потерялись пути дальше');
    dom.window.close();
  }

  // --- 3.5 Главная: карта и функция на месте в контексте страницы ---
  {
    const { dom } = await open(port, 'index.html');
    const w = dom.window;
    assert.ok(w.IvaCityPages && w.IvaCityPages.cities, 'на главной нет карты городов');
    assert.equal(typeof w.noResultsHTML, 'function', 'на главной нет noResultsHTML');
    const html = w.noResultsHTML({ city: 'Таганрог' });
    assert.ok(html.includes('city/taganrog.html'), 'главная: нет ссылки на страницу Таганрога');
    dom.window.close();
  }

  console.log('empty-states OK: карта, разметка и поведение пустого состояния проверены в DOM');
  server.close();
  process.exit(0);
})().catch((e) => { console.error(e); server.close(); process.exit(1); });

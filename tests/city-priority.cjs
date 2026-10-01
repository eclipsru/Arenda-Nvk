// Сторож порядка на страницах городов: объявления арендодателей выше справочника,
// счётчик обращений, «заявка»-консьерж и защита от подлога номеров.
//
// Проверяет:
//   1. Есть объявления в городе  -> страница ведёт к ним, справочник свёрнут в блок
//      «Показать справочник», телефоны при этом остаются в тексте страницы (для людей и поиска).
//   2. Объявлений нет            -> честная надпись «Объявлений арендодателей здесь пока нет»,
//      справочник развёрнут, и нигде не обещано объявлений, которых нет.
//   3. На каждой странице — счётчик обращений (assets/clicks.js) и он запускается с названием города.
//   4. У каждого телефона справочника — кнопка «Позвонить»; у площадки — «Позвонить» с пометкой
//      data-lead="1" (это обращение к нам, а не в прокат — в статистике они различаются).
//   5. Счётчик не собирает персональные данные: пишет только телефон (из самой страницы),
//      город, имя страницы и тип обращения; чужой номер через подставную страницу не уходит.
//   6. SQL счётчика существует и не отдаёт статистику посторонним.
//
// Запуск: node tests/city-priority.cjs
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const GENERATOR = path.join(ROOT, 'tools', 'build_city_pages.py');
const SNAPSHOT = path.join(ROOT, 'tools', 'directory_existing.json');
const CLICKS = path.join(ROOT, 'assets', 'clicks.js');
const MIGRATION = path.join(ROOT, 'supabase', 'migrations', '20261001_directory_clicks.sql');

const rel = p => path.relative(ROOT, p);

// Сколько контактов должно быть на странице города Ростов-на-Дону (скрытые не считаются)
function expectedContacts(cityName) {
  const snapshot = JSON.parse(fs.readFileSync(SNAPSHOT, 'utf8'));
  const aliasesRaw = JSON.parse(fs.readFileSync(path.join(ROOT, 'tools', 'city_aliases.json'), 'utf8'));
  const aliases = Object.fromEntries(Object.entries(aliasesRaw).filter(([k]) => !k.startsWith('_')));
  return snapshot.rows.filter(r => {
    const status = r.status || 'new';
    if (status === 'hidden' || status === 'declined') return false;
    return (aliases[r.city.trim()] || r.city.trim()) === cityName;
  }).length;
}

// Собираем страницу в отдельной папке с подставленными данными об объявлениях:
// файлы проекта при этом не меняются.
function buildWith(tmp, listings, city = 'rostov-na-donu') {
  const listingsPath = path.join(tmp, 'listings.json');
  fs.writeFileSync(listingsPath, JSON.stringify({ by_city: listings, total: Object.values(listings).reduce((a, b) => a + b, 0) }), 'utf8');
  const out = path.join(tmp, 'out');
  fs.mkdirSync(out, { recursive: true });
  execFileSync('python3', [GENERATOR, '--only', `${city},novocherkassk`], {
    cwd: ROOT,
    env: { ...process.env, IVA_OUT: out, IVA_LISTINGS: listingsPath },
    stdio: 'pipe'
  });
  return {
    city: fs.readFileSync(path.join(out, `${city}.html`), 'utf8'),
    empty: fs.readFileSync(path.join(out, 'novocherkassk.html'), 'utf8')
  };
}

function commonChecks(html, file) {
  // Счётчик обращений подключён и запускается с названием города
  assert.match(html, /<script src="assets\/clicks\.js[^"]*"><\/script>/,
    `${file}: нет счётчика обращений assets/clicks.js — проверить шаблон tools/city_template.html`);
  assert.match(html, /IvaClicks\.init\('[^']+'\)/,
    `${file}: счётчик не запускается (нет IvaClicks.init) — обращения не будут считаться`);

  // Кнопка «Позвонить» у каждого пункта справочника
  const cardCalls = (html.match(/class="btn sm" href="tel:/g) || []).length;
  const cards = (html.match(/class="pill">Пункт проката<\/span>/g) || []).length;
  assert.equal(cardCalls, cards, `${file}: «Позвонить» — ${cardCalls}, карточек — ${cards}`);

  // Обращение к площадке помечено отдельно от звонка в прокат
  assert.match(html, /<a class="btn hot sm" href="tel:\+79081732475" data-lead="1"/,
    `${file}: кнопка «Позвонить нам» без пометки data-lead="1" — обращения к площадке не отличить от звонков в прокат`);
  assert.match(html, /Не подобрали инструмент\?/,
    `${file}: нет блока «Не подобрали инструмент?» — человек не понимает, что можно попросить помощь`);
}

try {
  // 1. В городе есть объявления арендодателей
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'iva-city-priority-'));
  const withListings = buildWith(tmp, { 'Ростов-на-Дону': 3 });
  const rostov = withListings.city;

  assert.match(rostov, /В этом городе уже 3 объявления/,
    'нет блока про объявления арендодателей, когда они есть — проверить tools/build_city_pages.py');
  const rostovCatalogUrl = 'catalog.html?city=' + encodeURIComponent('Ростов-на-Дону');
  assert.ok(rostov.includes(rostovCatalogUrl),
    `блок объявлений не ведёт в каталог этого города (ожидалась ссылка ${rostovCatalogUrl})`);

  // Справочник свёрнут, но телефоны остаются на странице
  assert.match(rostov, /<summary[^>]*>Показать справочник \(10 пунктов, не подключены к «Иве»\)<\/summary>/,
    'когда есть объявления, справочник должен быть свёрнут в блок «Показать справочник»');
  const rostovContacts = expectedContacts('Ростов-на-Дону');
  const rostovPhones = new Set((rostov.match(/href="tel:\+7\d{10}"/g) || []));
  assert.ok(rostovPhones.size >= rostovContacts,
    `в свёрнутом справочнике должны остаться все телефоны: карточек ${rostovContacts}, номеров ${rostovPhones.size}`);

  // Первым на странице идёт блок объявлений, а не справочник
  const orderListings = rostov.indexOf('Инструмент от арендодателей «Ивы»');
  const orderDirectory = rostov.indexOf('Пункты проката в городе');
  assert.ok(orderListings !== -1 && orderDirectory !== -1 && orderListings < orderDirectory,
    'объявления арендодателей должны стоять выше справочника — это решение владельца');

  // 2. В городе объявлений нет — честно и без обещаний
  const noListings = buildWith(tmp, {}).city;
  assert.match(noListings, /Объявлений арендодателей здесь пока нет/,
    'когда объявлений нет, об этом надо сказать прямо — нельзя обещать несуществующий каталог');
  assert.ok(!/В этом городе уже \d+ (объявление|объявления|объявлений)/.test(noListings),
    'на странице заявлено число объявлений, которых нет');
  assert.ok(!noListings.includes('<summary'), 'сворачивать нечего — в городе нет объявлений');
  assert.match(noListings, /Это справочник: пункты ниже пока не подключены к «Иве»/,
    'нет пояснения, что пункты справочника не подключены к «Иве»');
  assert.match(noListings, /landlord-register\.html/,
    'нет выхода для владельца инструмента («сдать инструмент»)');

  // 3. Общие требования на обеих страницах
  commonChecks(withListings.city, 'city/rostov-na-donu.html (с объявлениями)');
  commonChecks(noListings, 'city/rostov-na-donu.html (без объявлений)');

  // 4. Счётчик: только телефон со страницы, никаких персональных данных
  const clicks = fs.readFileSync(CLICKS, 'utf8');
  const payloadKeys = [...new Set((clicks.match(/^\s{10,}(phone|city|page|kind|email|name|ip|user|device|fingerprint|token|ua):/gm) || [])
    .map(s => s.trim().replace(':', '')))];
  const forbidden = payloadKeys.filter(k => !['phone', 'city', 'page', 'kind'].includes(k));
  assert.equal(forbidden.length, 0, `счётчик отправляет лишние данные: ${forbidden.join(', ')} — это персональные данные`);
  assert.match(clicks, /var allowed = \{\};/,
    'счётчик должен отправлять только те номера, которые напечатаны на странице (защита от подлога)');
  assert.match(clicks, /!allowed\[tel\]/, 'счётчик не проверяет номер по списку страницы — подставной номер запишется');
  assert.match(clicks, /keepalive: true/, 'без keepalive обращение может потеряться при переходе на звонок');
  assert.match(clicks, /directory_clicks/, 'счётчик пишет не в таблицу directory_clicks');
  assert.ok(!/https?:\/\/(?!wdxdeatphizclskfmfxi)/.test(clicks),
    'счётчик стучится на посторонний адрес — данные обращений не должны уходить на сторону');

  // 5. SQL счётчика: таблица есть, статистика посторонним закрыта, откат описан
  const sql = fs.readFileSync(MIGRATION, 'utf8');
  assert.match(sql, /create table if not exists public\.directory_clicks/, 'в SQL нет таблицы directory_clicks');
  assert.match(sql, /enable row level security/, 'в SQL не включены правила доступа (RLS)');
  assert.match(sql, /check \(kind in \('call', 'lead', 'view'\)\)/, 'в SQL нет проверки типа обращения');
  assert.match(sql, /security_invoker = true/, 'сводка должна считаться от имени спрашивающего (иначе видна всем)');
  assert.match(sql, /revoke all on public\.directory_click_stats from anon/, 'сводка не закрыта от посторонних');
  assert.match(sql, /drop table if exists public\.directory_clicks/, 'в SQL нет блока отката');

  fs.rmSync(tmp, { recursive: true, force: true });
  console.log('Порядок на страницах в порядке: объявления выше справочника, справочник не обещает лишнего, ' +
    'счётчик считает звонки и обращения, персональные данные не собираются, статистика закрыта от посторонних.');
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

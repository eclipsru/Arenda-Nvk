// Сторож страниц городов (этап П3, первый шаг).
//
// Проверяет без браузера, что сгенерированные страницы городов:
//   • собраны из данных справочника (число контактов совпадает со снимком базы);
//   • не содержат незаполненных мест шаблона и битых ссылок на файлы проекта;
//   • не показывают карточки, скрытые по просьбе владельца точки (hidden/declined);
//   • имеют корректные заголовок, описание, canonical и микроразметку (JSON-LD);
//   • пока идут на приёмку — закрыты от поиска (noindex), пока не передан флаг --index;
//   • телефоны пунктов проката НЕ публикуются (решение владельца, вариант 5): на странице
//     допустим только наш телефон, через который идёт заявка на подбор.
//
// Запуск: node tests/city-pages.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const CITY_DIR = path.join(ROOT, 'city');
const SNAPSHOT = path.join(ROOT, 'tools', 'directory_existing.json');
const CITIES = path.join(ROOT, 'tools', 'cities.json');
const ALIASES = path.join(ROOT, 'tools', 'city_aliases.json');

function run() {
  assert.ok(fs.existsSync(CITY_DIR), 'нет папки city/ — сгенерировать: python3 tools/build_city_pages.py');
  const allFiles = fs.readdirSync(CITY_DIR).filter(f => f.endsWith('.html')).sort();
  assert.ok(allFiles.length > 0, 'в папке city/ нет страниц');
  assert.ok(allFiles.includes('index.html'), 'нет страницы-хаба city/index.html — пересобрать: python3 tools/build_city_pages.py');
  const files = allFiles.filter(f => f !== 'index.html');

  const snapshot = JSON.parse(fs.readFileSync(SNAPSHOT, 'utf8'));
  const aliasesRaw = JSON.parse(fs.readFileSync(ALIASES, 'utf8'));
  const aliases = Object.fromEntries(Object.entries(aliasesRaw).filter(([k]) => !k.startsWith('_')));

  // Сколько контактов должно быть на странице города (скрытые не считаются)
  const byCity = new Map();
  for (const row of snapshot.rows) {
    const status = row.status || 'new';
    if (status === 'hidden' || status === 'declined') continue;
    const name = aliases[row.city.trim()] || row.city.trim();
    byCity.set(name, (byCity.get(name) || 0) + 1);
  }

  const cityNames = new Map(JSON.parse(fs.readFileSync(CITIES, 'utf8')).cities.map(c => [c.slug, c.name]));

  let checkedContacts = 0;
  for (const file of files) {
    const slug = file.replace(/\.html$/, '');
    const html = fs.readFileSync(path.join(CITY_DIR, file), 'utf8');

    // 1. Незаполненные места шаблона
    const leftovers = html.match(/\{\{[A-Z_]+\}\}/g);
    assert.ok(!leftovers, `${file}: не заполнены места шаблона ${leftovers && leftovers.join(', ')} — пересобрать: python3 tools/build_city_pages.py`);

    // 2. Из какого города страница — берём прямо из заголовка страницы
    const h2 = html.match(/<h2>Аренда инструмента — ([^<]+)<\/h2>/);
    assert.ok(h2, `${file}: не найден заголовок «Аренда инструмента — <город>»`);
    const cityName = h2[1];
    const expectedName = cityNames.get(slug);
    if (expectedName) {
      assert.equal(cityName, expectedName, `${file}: город на странице «${cityName}», а по ссылке ожидается «${expectedName}»`);
    }

    // 3. Заголовок, описание, canonical
    assert.match(html, new RegExp(`<link rel="canonical" href="[^"]*/city/${slug}\\.html">`),
      `${file}: неверный canonical`);
    const titleMatch = html.match(/<title>([^<]+)<\/title>/);
    assert.ok(titleMatch && titleMatch[1].includes('Аренда инструмента'), `${file}: странный заголовок`);
    const descMatch = html.match(/<meta name="description" content="([^"]+)">/);
    assert.ok(descMatch && descMatch[1].length >= 40, `${file}: слишком короткое описание`);

    // 4. Микроразметка читается как JSON
    const ld = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/);
    assert.ok(ld, `${file}: нет микроразметки`);
    const parsed = JSON.parse(ld[1]);
    const itemList = (parsed['@graph'] || []).find(x => x['@type'] === 'ItemList');
    assert.ok(itemList, `${file}: в микроразметке нет списка организаций`);

    // 5. Число контактов совпадает со снимком базы.
    //    Считаем именно карточки — по метке data-prokat-card (одна карточка = один пункт).
    const cardsCount = (html.match(/data-prokat-card="1"/g) || []).length;
    const phonesInHtml = new Set((html.match(/href="tel:(\+?\d+)"/g) || []).map(s => s.replace(/\D/g, '').slice(-10)));
    // 5а. Телефоны пунктов не публикуются: любой tel:-номер на странице — только наш.
    const OUR = '9081732475';
    for (const num of phonesInHtml) {
      assert.equal(num, OUR,
        `${file}: на странице телефон пункта проката (${num}) — по решению владельца телефоны пунктов ` +
        'не публикуются, вместо них кнопка «Узнать наличие и цену». Собрать заново: python3 tools/build_city_pages.py');
    }
    const leadButtons = (html.match(/href="tel:\+79081732475" data-lead="1"/g) || []).length;
    assert.ok(leadButtons >= cardsCount,
      `${file}: у пунктов нет пути к заявке: карточек ${cardsCount}, кнопок «Узнать наличие и цену» — ${leadButtons}`);
    // Цифры телефонов пунктов не должны встречаться и в тексте страницы
    const digitsOnly = html.replace(/\D/g, '');
    for (const row of snapshot.rows) {
      const p10 = String(row.phone).replace(/\D/g, '').slice(-10);
      assert.ok(!digitsOnly.includes(p10),
        `${file}: в тексте страницы остался телефон пункта проката (${p10}) — публиковать нельзя`);
    }

    assert.equal(itemList.itemListElement.length, cardsCount,
      `${file}: карточек с кнопкой «Позвонить» — ${cardsCount}, организаций в разметке — ${itemList.itemListElement.length}`);
    assert.equal(cardsCount, byCity.get(cityName) || 0,
      `${file}: на странице ${cardsCount} контактов, а в справочнике для «${cityName}» — ${byCity.get(cityName) || 0}. ` +
      'Пересобрать: python3 tools/build_city_pages.py');
    checkedContacts += itemList.itemListElement.length;

    // 5б. Бизнес-логика страницы: справочник не заменяет заказ,
    //     у каждого пункта есть приглашение подключиться, а сверху — путь в каталог.
    const orderLinks = (html.match(/Это ваш прокат\? Подключитесь/g) || []).length;
    assert.equal(orderLinks, cardsCount,
      `${file}: приглашений «Это ваш прокат? Подключитесь» — ${orderLinks}, а пунктов — ${cardsCount}`);
    assert.match(html, /href="landlord-register\.html"/,
      `${file}: нет ссылки «Подключитесь» на форму арендодателя`);
    assert.match(html, /href="catalog\.html\?city=/,
      `${file}: нет ссылки в каталог с выбранным городом (человек должен попадать к объявлениям, а не только к телефонам)`);
    assert.match(html, /пока не подключены к «Иве»/,
      `${file}: нет пояснения, что пункты справочника не подключены (заказ возможен только у объявлений)`);

    // 6. Ни одного скрытого контакта: ни названия, ни телефона скрытой карточки на странице нет
    for (const row of snapshot.rows) {
      const status = row.status || 'new';
      if (status !== 'hidden' && status !== 'declined') continue;
      const name = String(row.name || '').trim();
      assert.ok(!name || !html.includes(name),
        `${file}: на странице есть скрытая карточка «${name}» — недопустимо`);
    }

    // 7. Ссылки на файлы проекта существуют
    const links = new Set((html.match(/(?:href|src)="([^"#:]+\.(?:html|css|js|svg|png))(?:\?[^"]*)?"/g) || [])
      .map(s => s.replace(/^(?:href|src)="/, '').replace(/"[^"]*$/, '').replace(/\?.*$/, '')));
    for (const link of links) {
      const target = path.join(ROOT, link);
      assert.ok(fs.existsSync(target), `${file}: ссылка на несуществующий файл «${link}»`);
    }

    // 8. Дизайн: только существующая тема, никаких новых стилевых файлов
    const cssLinks = (html.match(/<link[^>]+rel="stylesheet"[^>]*>/g) || []);
    for (const link of cssLinks) {
      assert.ok(/assets\/(theme|geo)\.css/.test(link),
        `${file}: подключается посторонний файл стилей — дизайн менять нельзя: ${link}`);
    }

    // 9. Приёмка: пока страницы закрыты от поиска
    const robots = html.match(/<meta name="robots" content="([^"]+)">/);
    assert.ok(robots, `${file}: нет указания для поисковых систем`);
    assert.ok(/^(noindex|index),follow$/.test(robots[1]),
      `${file}: неожиданное значение robots («${robots[1]}») — допустимо index,follow или noindex,follow`);
  }

  // --- страница-хаб: список городов со ссылками ---
  const hub = fs.readFileSync(path.join(CITY_DIR, 'index.html'), 'utf8');
  assert.match(hub, /Аренда инструмента — города России/, 'хаб: нет заголовка');
  const hubLinks = new Set((hub.match(/href="city\/([a-z0-9-]+\.html)"/g) || [])
    .map(s => s.replace('href="city/', '').replace('.html"', '')));
  const missing = files.map(f => f.replace(/\.html$/, '')).filter(slug => !hubLinks.has(slug));
  assert.equal(missing.length, 0,
    `хаб: нет ссылок на города — ${missing.slice(0, 5).join(', ')}${missing.length > 5 ? ' и другие' : ''}. Пересобрать: python3 tools/build_city_pages.py`);
  const hubRobots = hub.match(/<meta name="robots" content="([^"]+)">/);
  assert.ok(hubRobots && /^(noindex|index),follow$/.test(hubRobots[1]), 'хаб: неожиданное значение robots');
  for (const link of (hub.match(/(?:href|src)="([^"#:]+)\.(?:html|css|js|svg|png)(?:\?[^"]*)?"/g) || [])) {
    const target = link.replace(/^(?:href|src)="/, '').replace(/"[^"]*$/, '').replace(/\?.*$/, '');
    assert.ok(fs.existsSync(path.join(ROOT, target)), `хаб: ссылка на несуществующий файл «${target}»`);
  }

  console.log(`Страницы городов в порядке: ${files.length} шт., контактов на них — ${checkedContacts}, ` +
    `хаб со ссылками на все ${files.length} городов, скрытые карточки не показываются, битых ссылок и чужих стилей нет.`);
}

try {
  run();
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

// Сторож «двух списков городов» (этап П2).
//
// Самый дорогой класс ошибок в таких проектах — когда городов стало два:
// один в assets/data.js (его видят страницы), второй в tools/cities.json
// (его видят база прокатов, выгрузки и проверки). Этот тест падает, как только
// списки разошлись, — то есть поймать расхождение можно до правки в бою.
//
// Запуск: node tests/cities-sync.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const DATA_JS = path.join(ROOT, 'assets', 'data.js');
const CITIES_JSON = path.join(ROOT, 'tools', 'cities.json');

// Разбираем CITIES из data.js тем же способом, что и tools/export_cities.py:
// строки в кавычках + заголовки-комментарии (// Регион) как подписи групп.
function parseFromDataJs() {
  const src = fs.readFileSync(DATA_JS, 'utf8');
  const start = src.indexOf('CITIES');
  assert.ok(start >= 0, 'в assets/data.js не найден массив CITIES');
  const open = src.indexOf('[', start);
  let depth = 0;
  let end = -1;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '[') depth++;
    else if (src[i] === ']') {
      depth--;
      if (depth === 0) { end = i; break; }
    }
  }
  assert.ok(end > open, 'в assets/data.js не найден конец массива CITIES');

  const block = src.slice(open + 1, end);
  const pairs = [];
  let region = 'Ростовская область'; // города до первого заголовка — домашний регион
  for (const line of block.split('\n')) {
    const trimmed = line.trim();
    if (trimmed.startsWith('//')) {
      region = trimmed.replace(/^\/+/, '').trim();
      continue;
    }
    for (const m of trimmed.matchAll(/'([^']+)'/g)) pairs.push([m[1], region]);
  }
  return pairs;
}

function run() {
  const pairs = parseFromDataJs();
  assert.ok(pairs.length > 0, 'список городов в assets/data.js пуст');

  assert.ok(fs.existsSync(CITIES_JSON),
    'нет tools/cities.json — создать: python3 tools/export_cities.py');
  const json = JSON.parse(fs.readFileSync(CITIES_JSON, 'utf8'));
  const cities = json.cities;
  assert.ok(Array.isArray(cities) && cities.length > 0, 'в tools/cities.json нет списка cities');

  // 1. Одинаковое количество
  assert.equal(cities.length, pairs.length,
    `городов разное количество: data.js — ${pairs.length}, cities.json — ${cities.length}. ` +
    'Починить: python3 tools/export_cities.py');

  // 2. Тот же состав и тот же порядок
  const namesData = pairs.map(p => p[0]);
  const namesJson = cities.map(c => c.name);
  for (let i = 0; i < namesData.length; i++) {
    if (namesData[i] !== namesJson[i]) {
      throw new Error(
        `списки разошлись на позиции ${i + 1}: data.js — «${namesData[i]}», ` +
        `cities.json — «${namesJson[i]}». Починить: python3 tools/export_cities.py`);
    }
  }

  // 3. Регион у каждого города совпадает с группой в data.js
  for (let i = 0; i < pairs.length; i++) {
    assert.equal(cities[i].region, pairs[i][1],
      `город «${cities[i].name}»: регион в cities.json — «${cities[i].region}», ` +
      `а в data.js он в группе «${pairs[i][1]}»`);
  }

  // 4. Ссылки (slug) уникальны и пригодны для адреса
  const seen = new Map();
  for (const c of cities) {
    assert.match(c.slug, /^[a-z0-9]+(?:-[a-z0-9]+)*$/,
      `город «${c.name}»: ссылка «${c.slug}» содержит недопустимые символы`);
    assert.ok(!seen.has(c.slug),
      `ссылка «${c.slug}» повторяется: «${c.name}» и «${seen.get(c.slug)}»`);
    seen.set(c.slug, c.name);
  }

  // 5. Обязательные поля на месте (пустые значения допустимы — заполним из открытых источников)
  for (const c of cities) {
    for (const key of ['name', 'slug', 'region', 'population', 'lat', 'lon', 'has_snt']) {
      assert.ok(key in c, `город «${c.name}»: нет поля «${key}»`);
    }
  }

  console.log(`Списки городов совпадают: ${cities.length} городов, ссылки уникальны, регионы на месте.`);
}

try {
  run();
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

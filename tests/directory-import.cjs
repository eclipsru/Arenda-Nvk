// Сторож ввоза справочника прокатов (этап П2).
//
// Проверяет без базы данных, что готовый файл ввоза соответствует источнику
// tools/real_bases.csv и что данные приведены в порядок: телефоны в едином виде,
// дублей «телефон + адрес» нет, ссылки городов взяты из tools/cities.json,
// а повторный запуск файла ничего не испортит (есть правило «при совпадении — обновить»).
//
// Запуск: node tests/directory-import.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const CSV = path.join(ROOT, 'tools', 'real_bases.csv');
const SEED = path.join(ROOT, 'supabase', 'seed', '20261001_directory_landlords_import.sql');
const CITIES = path.join(ROOT, 'tools', 'cities.json');
const ALIASES = path.join(ROOT, 'tools', 'city_aliases.json');

// Простой разбор CSV с кавычками (в источнике встречаются адреса с запятыми в кавычках)
function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++; }
        else inQuotes = false;
      } else cell += ch;
    } else if (ch === '"') inQuotes = true;
    else if (ch === ',') { row.push(cell); cell = ''; }
    else if (ch === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
    else if (ch !== '\r') cell += ch;
  }
  if (cell.length || row.length) { row.push(cell); rows.push(row); }
  return rows.filter(r => r.length > 1 || (r.length === 1 && r[0] !== ''));
}

function run() {
  assert.ok(fs.existsSync(SEED),
    'нет файла ввоза — собрать: python3 tools/build_directory_import.py');

  const csvText = fs.readFileSync(CSV, 'utf8').replace(/^\uFEFF/, '');
  const rows = parseCsv(csvText);
  const header = rows[0].map(h => h.trim());
  const data = rows.slice(1).filter(r => r.join('').trim() !== '');
  const col = name => header.indexOf(name);
  assert.ok(col('phone') >= 0 && col('address') >= 0 && col('city') >= 0,
    'в tools/real_bases.csv нет нужных столбцов');

  const seed = fs.readFileSync(SEED, 'utf8');

  // 1. Строк в файле ввоза столько же, сколько в источнике
  const valueLines = seed.split('\n').filter(l => l.trim().startsWith("('"));
  assert.equal(valueLines.length, data.length,
    `строк к ввозу ${valueLines.length}, а в источнике ${data.length}. Починить: python3 tools/build_directory_import.py`);

  // 2. Телефоны в едином виде, дублей «телефон + адрес» нет
  const phones = [];
  const pairs = new Set();
  for (const line of valueLines) {
    const m = line.match(/'(\+7\d{10})'/);
    assert.ok(m, `в строке ввоза телефон не приведён к виду +7XXXXXXXXXX: ${line.slice(0, 80)}…`);
    phones.push(m[1]);
  }
  for (const r of data) {
    const phone = '+' + String(r[col('phone')]).replace(/\D/g, '').replace(/^8/, '7');
    const addr = String(r[col('address')] || '').trim().toLowerCase();
    const key = phone + '|' + addr;
    assert.ok(!pairs.has(key), `дубль «телефон + адрес» в источнике: ${phone}, ${addr}`);
    pairs.add(key);
  }

  // 3. Ссылки городов — только из tools/cities.json (нет «второго списка городов»)
  const citiesJson = JSON.parse(fs.readFileSync(CITIES, 'utf8'));
  const slugs = new Set(citiesJson.cities.map(c => c.slug));
  const names = new Set(citiesJson.cities.map(c => c.name));
  const usedSlugs = [...seed.matchAll(/DATE '(\d{4}-\d{2}-\d{2})'/g)].length;
  assert.equal(usedSlugs, data.length, 'в файле ввоза не у каждой строки проставлена дата источника');

  const sqlSlugs = [...seed.matchAll(/^\s*\('[^']*', '([a-z0-9-]+)', /gm)].map(m => m[1]);
  for (const slug of sqlSlugs) {
    assert.ok(slugs.has(slug), `в файле ввоза ссылка города «${slug}» отсутствует в tools/cities.json`);
  }

  // 4. Повторный запуск безопасен: правило «при совпадении — обновить» на месте
  assert.match(seed, /on conflict \(phone, address\) do update set/,
    'в файле ввоза нет правила обновления при совпадении — повторный запуск создаст дубли');

  // 5. Статусы проверки повторным ввозом не затираются
  assert.ok(!/status\s*=\s*excluded\.status/.test(seed),
    'повторный ввоз затирает статус проверки карточки (status = excluded.status) — убрать');

  // 6. Города, которых нет в списке сайта, остаются без ссылки (это видно и в отчёте)
  const nullSlugs = [...seed.matchAll(/^\s*\('[^']*', NULL, /gm)].length;
  const aliasesRaw = JSON.parse(fs.readFileSync(ALIASES, 'utf8'));
  const aliases = Object.fromEntries(
    Object.entries(aliasesRaw).filter(([k]) => !k.startsWith('_')));
  const unknownCities = [];
  let expectedNullSlugs = 0;
  for (const r of data) {
    const raw = String(r[col('city')] || '').trim();
    const city = aliases[raw] || raw;
    if (!names.has(city)) {
      expectedNullSlugs++;
      if (!unknownCities.includes(city)) unknownCities.push(city);
    }
  }
  assert.equal(nullSlugs, expectedNullSlugs,
    `строк без ссылки города ${nullSlugs}, а городов вне списка сайта ${expectedNullSlugs}. ` +
    'Починить: python3 tools/build_directory_import.py');

  console.log(
    `Ввоз справочника согласован с источником: ${data.length} строк, телефоны в едином виде, ` +
    `дублей нет, городов вне списка сайта — ${unknownCities.length}.`);
}

try {
  run();
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

// Сторож справочника прокатов (этап П2).
//
// Проверяет без базы данных, что:
//   1) файл дополнения содержит РОВНО те контакты, которых нет в боевой базе
//      (сравнение по последним 10 цифрам телефона — так же, как в SQL);
//   2) в файле нет ни одного телефона, который в базе уже есть;
//   3) повторный запуск безопасен (есть проверка «такого телефона ещё нет»);
//   4) файлы собраны из правильной структуры таблицы: в них НЕ упоминаются
//      столбцы, которых в боевой базе нет (это ломало запуск раньше);
//   5) комбинированный файл (для нового проекта) = структура + данные,
//      и в нём структура идёт раньше данных.
//
// Запуск: node tests/directory-import.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const CSV = path.join(ROOT, 'tools', 'real_bases.csv');
const SNAPSHOT = path.join(ROOT, 'tools', 'directory_existing.json');
const ALIASES = path.join(ROOT, 'tools', 'city_aliases.json');
const MIGRATION = path.join(ROOT, 'supabase', 'migrations', '20261001_directory_landlords.sql');
const RLS = path.join(ROOT, 'supabase', 'migrations', '20261001_directory_landlords_rls_NEW_PROJECT.sql');
const SEED = path.join(ROOT, 'supabase', 'seed', '20261001_directory_landlords_import.sql');
const BUNDLE = path.join(ROOT, 'supabase', 'seed', '20261001_directory_ALL_IN_ONE.sql');

function parseCsv(text) {
  const rows = [];
  let row = [], cell = '', inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++; } else inQuotes = false;
      } else cell += ch;
    } else if (ch === '"') inQuotes = true;
    else if (ch === ',') { row.push(cell); cell = ''; }
    else if (ch === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
    else if (ch !== '\r') cell += ch;
  }
  if (cell.length || row.length) { row.push(cell); rows.push(row); }
  return rows.filter(r => r.length > 1 || (r.length === 1 && r[0] !== ''));
}

const digits = v => String(v == null ? '' : v).replace(/\D/g, '');
const last10 = v => digits(v).slice(-10);

function run() {
  for (const f of [SEED, BUNDLE, MIGRATION, RLS, SNAPSHOT]) {
    assert.ok(fs.existsSync(f), `нет файла: ${path.relative(ROOT, f)} — собрать: python3 tools/build_directory_import.py`);
  }

  const csvRows = (() => {
    const rows = parseCsv(fs.readFileSync(CSV, 'utf8').replace(/^\uFEFF/, ''));
    const header = rows[0].map(h => h.trim());
    return rows.slice(1).filter(r => r.join('').trim() !== '')
      .map(r => Object.fromEntries(header.map((h, i) => [h, r[i] == null ? '' : r[i]])));
  })();

  const snapshot = JSON.parse(fs.readFileSync(SNAPSHOT, 'utf8'));
  const known = new Set(snapshot.rows.map(r => last10(r.phone)));
  const aliasesRaw = JSON.parse(fs.readFileSync(ALIASES, 'utf8'));
  const aliases = Object.fromEntries(Object.entries(aliasesRaw).filter(([k]) => !k.startsWith('_')));

  // Ожидаемые новые контакты: то же правило, что в tools/build_directory_import.py
  const expected = [];
  const seen = new Set();
  for (const r of csvRows) {
    const name = String(r.name || '').trim();
    const phone = String(r.phone || '').trim();
    if (!name || !phone || !String(r.address || '').trim()) continue;
    const p10 = last10(phone);
    if (seen.has(p10)) continue;
    seen.add(p10);
    if (known.has(p10)) continue;
    expected.push({ name, p10, city: aliases[String(r.city || '').trim()] || String(r.city || '').trim() });
  }

  const seed = fs.readFileSync(SEED, 'utf8');
  const bundle = fs.readFileSync(BUNDLE, 'utf8');
  const migration = fs.readFileSync(MIGRATION, 'utf8');
  const rls = fs.readFileSync(RLS, 'utf8');

  // 1. Ровно ожидаемое число строк данных
  const valueLines = seed.split('\n').filter(l => l.trim().startsWith("('"));
  assert.equal(valueLines.length, expected.length,
    `в файле дополнения строк ${valueLines.length}, а новых контактов ${expected.length}. ` +
    'Починить: python3 tools/build_directory_import.py');

  const seedPhones = valueLines.map(l => last10(l.split("'")[3] === undefined ? '' : l.split("'")[3]));
  const phonesInSeed = new Set((seed.match(/'(\d{11})'/g) || []).map(s => last10(s)));

  // 2. Каждый ожидаемый новый контакт есть…
  for (const e of expected) {
    assert.ok(phonesInSeed.has(e.p10),
      `нового контакта нет в файле дополнения: «${e.name}» (${e.p10})`);
  }
  // …а ни одного уже существующего нет
  for (const p of phonesInSeed) {
    assert.ok(!known.has(p),
      `в файл дополнения попал контакт, который уже есть в базе: ${p}. Починить: python3 tools/build_directory_import.py`);
  }

  // 3. Повторный запуск безопасен
  assert.match(seed, /where not exists \(/i,
    'в файле нет проверки «такого телефона ещё нет» — повторный запуск создаст дубли');
  assert.ok(!/on conflict/i.test(seed),
    'файл использует «on conflict» — на боевой таблице это даёт ошибку (нет такого ограничения)');

  // 4. Никаких столбцов, которых нет в боевой базе
  const forbidden = ['city_slug', 'source_date', 'checked_at', 'updated_at'];
  for (const bad of forbidden) {
    assert.ok(!seed.includes(bad), `файл дополнения упоминает несуществующий столбец «${bad}»`);
    assert.ok(!migration.includes(bad), `файл структуры упоминает несуществующий столбец «${bad}»`);
  }

  // 5. Структура: создаёт таблицу и дотягивает столбцы; права в ней не трогаются
  assert.match(migration, /create table if not exists public\.directory_landlords/i,
    'в файле структуры нет создания таблицы');
  assert.match(migration, /alter table public\.directory_landlords add column if not exists/i,
    'в файле структуры нет добавления недостающих столбцов');
  assert.ok(!/row level security/i.test(migration),
    'файл структуры меняет права доступа — на боевом проекте это опасно, права должны быть в отдельном файле');
  assert.match(rls, /enable row level security/i,
    'в файле прав (для нового проекта) нет включения защиты строк');
  assert.match(rls, /create policy "directory_landlords_select_public"/i,
    'в файле прав нет правила чтения');

  // 6. Комбинированный файл = структура + данные, структура раньше
  assert.ok(bundle.includes(migration.trim()),
    'комбинированный файл не содержит текущую структуру — пересобрать: python3 tools/build_directory_import.py');
  assert.ok(bundle.includes(seed.trim()),
    'комбинированный файл не содержит текущее дополнение — пересобрать: python3 tools/build_directory_import.py');
  assert.ok(bundle.indexOf(migration.trim()) < bundle.indexOf(seed.trim()),
    'в комбинированном файле данные идут раньше структуры — пересобрать');
  assert.match(bundle, /ДЛЯ НОВОГО \/ ТЕСТОВОГО ПРОЕКТА/,
    'в комбинированном файле нет предупреждения, что он для нового проекта');

  const cities = [...new Set(expected.map(e => e.city))];
  console.log(
    `Справочник: в базе ${snapshot.rows.length} записей, в файле дополнения ${expected.length} новых ` +
    `(${cities.length} городов), структура и права не меняют боевую базу.`);
}

try {
  run();
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

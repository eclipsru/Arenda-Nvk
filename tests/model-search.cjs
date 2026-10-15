/* Справочник моделей (10.82): JS-алгоритм сайта (assets/models.js) на настоящих данных из миграций и общих случаях. */
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const M = require(path.join(ROOT, 'assets/models.js'));
const MIG = path.join(ROOT, 'supabase/migrations');

const un = s => s.replace(/''/g, "'");
function loadData() {
  const models = [];
  let id = 1;
  const files = fs.readdirSync(MIG).filter(f => /^20261017_models_data_\d+\.sql$/.test(f))
    .sort((a, b) => +a.match(/_(\d+)\.sql$/)[1] - +b.match(/_(\d+)\.sql$/)[1]);
  for (const f of files) {
    const s = fs.readFileSync(path.join(MIG, f), 'utf8');
    const re = /^ {2}\('((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)', '((?:[^']|'')*)'\)/gm;
    let m;
    while ((m = re.exec(s))) models.push({ id: id++, kind: m[1], brand: un(m[2]), model: un(m[3]), title: un(m[4]), specs: JSON.parse(un(m[5])) });
  }
  const sch = fs.readFileSync(path.join(MIG, '20261017_models.sql'), 'utf8');
  const bsec = sch.split('3. Бренды')[1].split('4. Характеристики')[0];
  const brands = [];
  const bre = /^ {2}\('((?:[^']|'')*)', (?:array\[(.*)\]::text\[\]|'\{\}'::text\[\])\)/gm;
  let m;
  while ((m = bre.exec(bsec))) brands.push({ name: un(m[1]), aliases: m[2] ? m[2].split(', ').map(x => un(x.replace(/^'|'$/g, ''))) : [] });
  const fsec = sch.split('create temp table _f')[1];
  const fields = {};
  const fre = /^ {2}\('([a-z0-9-]+)', '([a-z0-9]+)', '((?:[^']|'')*)', '((?:[^']|'')*)', '(num|choice|text)', (?:array\[(.*)\]::text\[\]|'\{\}'::text\[\]), (\d+)\)/gm;
  while ((m = fre.exec(fsec))) (fields[m[1]] = fields[m[1]] || []).push({ key: m[2], title: un(m[3]), unit: un(m[4]), type: m[5],
    options: m[6] ? m[6].split(/', '/).map(x => un(x.replace(/^'|'$/g, ''))) : [] });
  const w = fs.readFileSync(path.join(MIG, '20261016_kind_words.sql'), 'utf8');
  const kinds = [];
  const wre = /^\s*\('([a-z0-9-]+)', array\[(.*)\]::text\[\]\)/gm;
  while ((m = wre.exec(w))) kinds.push({ key: m[1], words: m[2].split(',').map(s => s.trim().replace(/^'|'$/g, '')) });
  return { models, brands, fields, kinds };
}

let pass = 0;
function ok(cond, name, extra) {
  if (!cond) { console.error('✘ ' + name + (extra ? '\n    ' + extra : '')); process.exitCode = 1; return; }
  pass++;
}

const D = loadData();
const nSql = (fs.readFileSync(path.join(MIG, '20261017_models.sql'), 'utf8').match(/\((\d+) моделей\)/) || [])[1];
ok(D.models.length >= 2000 && String(D.models.length) === nSql, 'модели из SQL: ' + D.models.length, nSql);
ok(D.brands.length >= 80 && D.kinds.length === 159 && Object.keys(D.fields).length === 159, 'бренды, слова видов, поля 159 видов',
   D.brands.length + '/' + D.kinds.length + '/' + Object.keys(D.fields).length);
ok(D.models.every(m => D.fields[m.kind] && Object.keys(m.specs).every(k => D.fields[m.kind].some(f => f.key === k))),
   'характеристики моделей — поля своего вида');
ok(D.models.every(m => JSON.stringify(M.ivaSpecsClean(m.specs, D.fields[m.kind]) || {}) === JSON.stringify(
   Object.fromEntries(D.fields[m.kind].filter(f => f.key in m.specs).map(f => [f.key, m.specs[f.key]])))),
   'очистка характеристик модели ничего не теряет');

const idx = M.ivaModelIndex(D.models, D.brands, D.kinds);
ok(idx.length === D.models.length, 'индекс по всем моделям');

const cases = fs.readFileSync(path.join(ROOT, 'tests/model-search-cases.txt'), 'utf8').split('\n')
  .filter(l => l.trim() !== '' && !l.startsWith('#')).map(l => l.split('|'));
let nc = 0;
for (const [q, kind, brand, first, count] of cases) {
  const r = M.ivaModelSearch(q, idx, { kind, brand });
  const got = r.map(m => m.title);
  if (first && first !== '-') ok(got[0] === first, 'случай «' + q + '» ' + kind + ' ' + brand + ': первая ' + first, got.slice(0, 4).join(' | '));
  if (first === '-') ok(got.length === 0, 'случай «' + q + '»: подсказок нет', got.slice(0, 4).join(' | '));
  if (count !== undefined && count !== '') ok(got.length === +count, 'случай «' + q + '»: подсказок ' + count, got.length + ': ' + got.slice(0, 4).join(' | '));
  if (kind) ok(r.every(m => m.kind === kind), 'случай «' + q + '»: только вид ' + kind);
  if (brand) ok(r.every(m => m.brand === brand), 'случай «' + q + '»: только бренд ' + brand);
  nc++;
}
ok(nc >= 40, 'общих случаев не меньше 40', nc);

// название каждой модели находит саму модель первой (модель в двух видах — среди подсказок)
const dupT = new Set(D.models.map(m => m.title).filter((t, i, a) => a.indexOf(t) !== i));
let bad = [];
for (const m of D.models) {
  const r = M.ivaModelSearch(m.title, idx);
  if (dupT.has(m.title) ? !r.includes(m) : r[0] !== m) bad.push(m.title + ' → ' + (r[0] ? r[0].title : '—'));
}
ok(bad.length === 0, 'название каждой модели находит её саму (' + D.models.length + ')', bad.slice(0, 5).join('; '));
bad = [];
for (const m of D.models) {
  const r = M.ivaModelSearch(m.brand + ' ' + m.model, idx);
  if (!r.includes(m)) bad.push(m.brand + ' ' + m.model);
}
ok(bad.length === 0, 'бренд + модель находит модель среди подсказок', bad.slice(0, 5).join('; '));

// бренды вида — по алфавиту, без повторов
const bs = M.ivaModelBrands(idx, 'rotary-sds-plus');
ok(bs.length > 5 && bs.join() === [...new Set(bs)].sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : 1).join() && bs.includes('Makita'),
   'бренды вида по алфавиту', bs.join(', '));
ok(M.ivaModelBrands(idx, 'bottle-jack').length === 0, 'у вида без моделей брендов нет');
const mini = M.ivaModelIndex([{ kind: 'k', brand: 'Denzel', model: 'A1', title: 'X Denzel A1' }, { kind: 'k', brand: 'DeWalt', model: 'B2', title: 'X DeWalt B2' },
  { kind: 'k', brand: 'AEG', model: 'C3', title: 'X AEG C3' }, { kind: 'z', brand: 'Bosch', model: 'D4', title: 'X Bosch D4' }], [], []);
ok(M.ivaModelBrands(mini, 'k').join() === 'AEG,Denzel,DeWalt', 'бренды без учёта регистра (Denzel раньше DeWalt), только свой вид', M.ivaModelBrands(mini, 'k').join());

// подстановка характеристик: ручное не трогаем
let r = M.ivaSpecsFill({}, {}, { power: 780, energy: 2.4 });
ok(JSON.stringify(r) === JSON.stringify({ values: { power: '780', energy: '2.4' }, auto: { power: '780', energy: '2.4' } }), 'пустая форма заполняется', JSON.stringify(r));
r = M.ivaSpecsFill({ power: '800', energy: '2.4' }, { power: '780', energy: '2.4' }, { power: 830, weight: 2.9 });
ok(JSON.stringify(r) === JSON.stringify({ values: { power: '800', energy: '', weight: '2.9' }, auto: { weight: '2.9' } }),
   'ручная мощность остаётся, наша энергия убирается, вес подставляется', JSON.stringify(r));
r = M.ivaSpecsFill({ power: '780', src: 'Сеть 220 В' }, { power: '780' }, { power: 830 });
ok(r.values.power === '830' && r.values.src === 'Сеть 220 В' && r.auto.power === '830', 'наше прошлое значение меняется на новое, ручное поле цело', JSON.stringify(r));
r = M.ivaSpecsFill({ power: '' }, { power: '780' }, {});
ok(r.values.power === '' && JSON.stringify(r.auto) === '{}', 'модель без характеристик ничего не ставит');
r = M.ivaSpecsFill(null, null, null);
ok(JSON.stringify(r) === '{"values":{},"auto":{}}', 'пустые аргументы');

// модель ещё подходит к названию
const hr = D.models.find(m => m.model === 'HR2470');
const gbh = D.models.find(m => m.model === 'GBH 2-26');
ok(M.ivaModelStillFits('Перфоратор Makita HR2470', hr) && M.ivaModelStillFits('Макита hr-2470 б/у', hr), 'код модели в названии — подходит');
ok(!M.ivaModelStillFits('Перфоратор Makita HR2450', hr) && !M.ivaModelStillFits('Перфоратор Makita', hr), 'другой код или без кода — не подходит');
ok(M.ivaModelStillFits('Bosch GBH2-26', gbh) && M.ivaModelStillFits('GBH 2 26 DRE', gbh), 'код с пробелами и дефисами сравнивается слитно');
ok(!M.ivaModelStillFits('x', null) && !M.ivaModelStillFits('x', { model: '' }), 'нет модели — не подходит');

// очистка характеристик к сохранению
const F = D.fields['rotary-sds-plus'];
ok(JSON.stringify(M.ivaSpecsClean({ src: 'Аккумулятор', volt: '18', power: ' 780 ', energy: '2,4', weight: 'abc', zzz: '1' }, F))
   === JSON.stringify({ src: 'Аккумулятор', volt: 18, power: 780, energy: 2.4 }), 'числа с запятой, лишние и плохие поля отбрасываются',
   JSON.stringify(M.ivaSpecsClean({ src: 'Аккумулятор', volt: '18', power: ' 780 ', energy: '2,4', weight: 'abc', zzz: '1' }, F)));
ok(M.ivaSpecsClean({ src: 'Газ' }, F) === null && M.ivaSpecsClean({}, F) === null && M.ivaSpecsClean(null, F) === null, 'чужой вариант и пусто → null');
const T = D.fields['pp-welder'];
ok(JSON.stringify(M.ivaSpecsClean({ pipe: '  20–63 ' }, T)) === '{"pipe":"20–63"}', 'строка — без пробелов по краям');

// 10.86: какие характеристики показывать в форме (общие случаи с приложением) и что из них сохранится
{
  const kv = t => { const o = {}; (t || '').split(';').map(x => x.trim()).filter(Boolean).forEach(x => { const i = x.indexOf('='); o[x.slice(0, i).trim()] = x.slice(i + 1).trim(); }); return o; };
  const lines = fs.readFileSync(path.join(ROOT, 'tests/form-fields-cases.txt'), 'utf8').split('\n').filter(l => l.trim() !== '' && !l.startsWith('#'));
  let nf = 0;
  for (const l of lines) {
    const [kind, ms, vs, want, save] = l.split('|');
    const fl = kind ? D.fields[kind].map((f, i) => Object.assign({ kind, sort: i + 1 }, f)) : [];
    const vis = M.ivaFormFields(fl, kv(ms), kv(vs));
    const got = vis.map(f => f.type === 'choice' ? f.key + '(' + f.options.join('; ') + ')' : f.key).join(',');
    ok(got === want, 'форма 10.86: ' + l, got);
    const sv = M.ivaSpecsClean(kv(vs), vis);
    ok(JSON.stringify(sv) === save, 'форма 10.86, сохранение: ' + l, JSON.stringify(sv));
    nf++;
  }
  ok(nf >= 12, 'случаев формы 10.86: ' + nf);
  const fl = D.fields['rotary-sds-plus'].map((f, i) => Object.assign({ kind: 'rotary-sds-plus', sort: i + 1 }, f));
  const vis = M.ivaFormFields(fl, { src: 'Сеть 220 В' }, {});
  ok(fl[0].options.length === 4 && vis[0].options.length === 1, 'поле вида не меняется (вариант модели — в копии)');
  ok(M.ivaFormFields(null, null, null).length === 0, 'нет полей — пусто');
}

// поля вида по порядку; загрузка справочника страницами по 1000, ошибки → null
const allF = Object.keys(D.fields).reduce((acc, k) => acc.concat(D.fields[k].map((f, i) => Object.assign({ kind: k, sort: i + 1 }, f))), []);
ok(M.ivaFieldsFor({ fields: allF.slice().reverse() }, 'rotary-sds-plus').map(f => f.key).join() === D.fields['rotary-sds-plus'].map(f => f.key).join()
   && M.ivaFieldsFor({ fields: allF }, '').length === 0 && M.ivaFieldsFor(null, 'drill').length === 0, 'поля вида — по порядку sort');
(async () => {
  const calls = [];
  const fake = async p => {
    calls.push(p);
    if (p.startsWith('/rest/v1/iva_brands')) return D.brands;
    if (p.startsWith('/rest/v1/iva_spec_fields')) return allF;
    const off = +p.match(/offset=(\d+)/)[1];
    return D.models.slice(off, off + 1000);
  };
  const md = await M.ivaModelsLoad(fake, D.kinds);
  const mp = calls.filter(c => c.startsWith('/rest/v1/iva_models'));
  ok(md && md.models.length === D.models.length && mp.length === Math.ceil((D.models.length + 1) / 1000) && mp.every(c => /active=eq\.true&order=id\.asc&limit=1000&offset=\d+$/.test(c)),
     'загрузка: модели страницами по 1000, только активные', mp.length + ' страниц');
  ok(md && md.byId[D.models[5].id] === md.models[5] && M.ivaModelSearch('makita hr2470', md.index)[0].title === 'Перфоратор Makita HR2470', 'загрузка: индекс и модели по id');
  const md2 = await M.ivaModelsLoad(async () => { throw new Error('нет сети'); }, D.kinds);
  ok(md2 && md2.models.length === D.models.length, 'загрузка: копия на 30 минут — без повторных запросов');
  ok(calls.filter(c => c.startsWith('/rest/v1/iva_models')).length === mp.length, 'загрузка: копия не запрашивает сервер снова');
  delete require.cache[require.resolve(path.join(ROOT, 'assets/models.js'))];
  const M2 = require(path.join(ROOT, 'assets/models.js'));
  ok(await M2.ivaModelsLoad(async () => { throw new Error('нет таблицы'); }, D.kinds) === null, 'загрузка: нет таблиц — null (форма как раньше)');
  ok(await M2.ivaModelsLoad(async p => p.includes('iva_models') ? [] : [], D.kinds) === null, 'загрузка: пустой справочник — null');
  // 10.83: характеристики в карточке — те же ожидания, что в IvaPatchTest
  const NB = '\u00a0';
  const rowsT = M.ivaSpecRows({ weight: '', energy: 2.4, zzz: '1', power: 780, src: 'Сеть 220 В' }, D.fields['rotary-sds-plus']).map(x => x[0] + '=' + x[1]).join('|') + '|';
  ok(rowsT === 'Питание=Сеть 220 В|Мощность=780' + NB + 'Вт|Энергия удара=2,4' + NB + 'Дж|', 'карточка: строки по порядку полей, число с запятой и единицей', rowsT);
  ok(M.ivaSpecRows({ power: 1 }, null).length === 0 && M.ivaSpecRows(null, F).length === 0, 'карточка: нет подписей или характеристик — строк нет');
  ok(M.ivaSpecsMatch({ src: 'Бензин', bar: 40 }, {}) && M.ivaSpecsMatch({ src: 'Бензин' }, null) && M.ivaSpecsMatch({ src: 'Бензин' }, { src: 'Бензин' })
     && !M.ivaSpecsMatch({}, { src: 'Бензин' }) && !M.ivaSpecsMatch(null, { src: 'Бензин' }) && !M.ivaSpecsMatch({ src: 'Бензин' }, { src: 'Аккумулятор' }),
     'фильтр: совпадение — все выбранные значения; без значения — не подходит');
  const tCalls = [];
  const tFake = async p => {
    tCalls.push(p);
    if (p.startsWith('/rest/v1/tools')) return [{ kind: 'rotary-sds-plus', specs: { src: 'Сеть 220 В', power: 780, energy: 2.4 } }];
    if (p.startsWith('/rest/v1/iva_kinds')) return [{ title: 'Перфоратор SDS-plus' }];
    if (p.startsWith('/rest/v1/iva_spec_fields')) return F.map((f, i) => Object.assign({ sort: i + 1 }, f)).reverse();
    return [];
  };
  const ts = await M.ivaToolSpecs(tFake, 'abc-1');
  ok(ts && ts.rows.map(x => x[0] + '=' + x[1]).join('|') === 'Вид=Перфоратор SDS-plus|Питание=Сеть 220 В|Мощность=780' + NB + 'Вт|Энергия удара=2,4' + NB + 'Дж'
     && tCalls[0] === '/rest/v1/tools?id=eq.abc-1&select=kind,specs,kit' && /iva_spec_fields\?.*&kind=eq\.rotary-sds-plus$/.test(tCalls[2] || ''),
     'карточка сайта: вид и характеристики объявления', JSON.stringify(ts));
  const ts2 = await M.ivaToolSpecs(async p => { if (p.includes('kind,specs')) throw new Error('нет столбца'); if (p.startsWith('/rest/v1/tools')) return [{ kind: 'chainsaw' }];
    if (p.startsWith('/rest/v1/iva_kinds')) return [{ title: 'Цепная пила' }]; throw new Error('лишний запрос ' + p); }, 'x');
  ok(ts2 && ts2.rows.length === 1 && ts2.rows[0][1] === 'Цепная пила', 'карточка сайта: нет столбца specs — только вид', JSON.stringify(ts2));
  // 10.84: комплектация — последней строкой; нет столбца kit — запрос без него, карточка как в 10.83
  const ts3 = await M.ivaToolSpecs(async p => { if (p.startsWith('/rest/v1/tools')) return [{ kind: 'chainsaw', specs: {}, kit: ['Шина', ' ', 7, 'Цепь'] }];
    if (p.startsWith('/rest/v1/iva_kinds')) return [{ title: 'Цепная пила' }]; return []; }, 'x');
  ok(ts3 && ts3.rows.map(x => x[0] + '=' + x[1]).join('|') === 'Вид=Цепная пила|Комплектация=Шина, Цепь', 'карточка сайта: комплектация последней строкой', JSON.stringify(ts3));
  const k4 = [];
  const ts4 = await M.ivaToolSpecs(async p => { k4.push(p); if (p.includes(',kit')) throw new Error('нет столбца kit');
    if (p.startsWith('/rest/v1/tools')) return [{ kind: 'chainsaw', specs: {} }]; if (p.startsWith('/rest/v1/iva_kinds')) return [{ title: 'Цепная пила' }]; return []; }, 'x');
  ok(ts4 && ts4.rows.length === 1 && k4[1] === '/rest/v1/tools?id=eq.x&select=kind,specs', 'карточка сайта: нет столбца kit — запрос без него', JSON.stringify(k4));
  ok(await M.ivaToolSpecs(async () => [{ kind: null }], 'x') === null && await M.ivaToolSpecs(async () => { throw new Error('x'); }, 'x') === null
     && await M.ivaToolSpecs(async () => [], 'x') === null && await M.ivaToolSpecs(async () => [], '') === null, 'карточка сайта: без вида или при ошибке — null (как раньше)');
  console.log('✔ model-search: ' + pass + ' проверок, ' + nc + ' общих случаев, ' + D.models.length + ' моделей');
})().catch(e => { console.error('✘ ' + e.stack); process.exitCode = 1; });

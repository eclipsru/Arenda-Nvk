/* 10.85: фильтр по характеристикам — функции сайта (assets/models.js) на общих случаях tests/spec-filter-cases.txt
   (их же проверяет IvaPatchTest → IvaModelPatch) и на настоящем справочнике полей из SQL. */
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const M = require(path.join(ROOT, 'assets/models.js'));
const { dirFromSql } = require('./model-fixtures.cjs');

let pass = 0;
function ok(cond, name, extra) {
  if (!cond) { console.error('✘ ' + name + (extra ? '\n    ' + extra : '')); process.exitCode = 1; return; }
  pass++;
}
const nb = s => String(s).replace(/\u00a0/g, ' ');
const DIR = dirFromSql();
const range = t => { const b = t.split('..'); return [b[0] === '' ? NaN : Number(b[0]), b[1] === '' ? NaN : Number(b[1])]; };
const map = t => { const o = {}; (t ? t.split(';') : []).forEach(x => { const i = x.indexOf('='); o[x.slice(0, i)] = x.slice(i + 1); }); return o; };

const counts = {};
for (const line of fs.readFileSync(path.join(ROOT, 'tests/spec-filter-cases.txt'), 'utf8').split('\n')) {
  if (!line || line.startsWith('#')) continue;
  const f = line.split('\t');
  let got, exp;
  if (f[0] === 'num') { const v = M.ivaNum(f[1]); got = isNaN(v) ? '' : String(v); exp = f[2]; }
  else if (f[0] === 'state') {
    const r = {}; (f[3] ? f[3].split(';') : []).forEach(x => { const i = x.indexOf('='); r[x.slice(0, i)] = range(x.slice(i + 1)); });
    got = String(M.ivaSpecState(map(f[1]), map(f[2]), r)); exp = f[4];
  } else if (f[0] === 'chip') { got = nb(M.ivaRangeChip(M.ivaFieldsFor(DIR, f[1]), f[2], range(f[3]))); exp = f[4]; }
  else if (f[0] === 'hint') { got = nb(M.ivaRangeHint(f[1] === '' ? NaN : Number(f[1]), f[2] === '' ? NaN : Number(f[2]), Number(f[3]), f[4])); exp = f[5]; }
  else if (f[0] === 'note') { got = M.ivaMissingNote(Number(f[1])); exp = f[2]; }
  else { got = '?'; exp = 'известный вид строки: ' + f[0]; }
  counts[f[0]] = (counts[f[0]] || 0) + 1;
  ok(got === exp, f[0] + ' ' + f.slice(1).join(' | '), JSON.stringify(got) + ' ≠ ' + JSON.stringify(exp));
}
ok(Object.keys(counts).sort().join(',') === 'chip,hint,note,num,state', 'в файле все виды случаев', JSON.stringify(counts));

// поля перфоратора из настоящего справочника — как в приложении
const rf = M.ivaFieldsFor(DIR, 'rotary-sds-plus');
ok(M.ivaNumFields(rf).map(x => x.key).join(',') === 'volt,power,energy,weight' && M.ivaChoiceFields(rf).map(x => x.key).join(',') === 'src',
  'поля перфоратора: числа и выбор');
ok(nb(M.ivaSpecChip(rf, 'src', 'Аккумулятор')) === 'Питание: Аккумулятор', 'подпись выбранного значения');
// ivaSpecState принимает числа из JSON (specs.power = 800) так же, как строки
ok(M.ivaSpecState({ power: 800, weight: 2.6 }, {}, { power: [700, NaN], weight: [NaN, 3] }) === 1, 'числа из JSON');

// загрузка для каталога: нет столбца specs — повтор только с видом; нет видов — null
(async () => {
  const calls = [];
  const fake = mode => path => {
    calls.push(path);
    if (/select=id,kind,specs/.test(path)) return mode === 'nospecs' || mode === 'nokind' ? Promise.reject(new Error('column tools.specs does not exist')) : [{ id: 1, kind: 'rotary-sds-plus', specs: { src: 'Аккумулятор' } }, { id: 2, kind: null }];
    if (/select=id,kind$/.test(path)) return mode === 'nokind' ? Promise.reject(new Error('column tools.kind does not exist')) : [{ id: 1, kind: 'rotary-sds-plus' }];
    if (/iva_kinds/.test(path)) return mode === 'nokinds' ? Promise.reject(new Error('404')) : [{ key: 'rotary-sds-plus', cat: 'power', sub: 'hammers', title: 'Перфоратор SDS-plus', sort: 1 }];
    if (/iva_spec_fields/.test(path)) return rf;
    return [];
  };
  const full = await M.ivaCatalogSpecs(fake('full'));
  ok(full && full.byId[1].specs.src === 'Аккумулятор' && !full.byId[2] && full.kinds.length === 1 && full.fields.length === 5, 'каталог: вид, характеристики, виды, поля', JSON.stringify(full));
  ok(calls.every(c => !/select=\*/.test(c)) && calls[0].includes('status=eq.active&active=eq.true'), 'каталог: только активные, без select=*');
  const ns = await M.ivaCatalogSpecs(fake('nospecs'));
  ok(ns && ns.byId[1].kind === 'rotary-sds-plus' && Object.keys(ns.byId[1].specs).length === 0, 'каталог: нет столбца specs — только вид');
  ok(await M.ivaCatalogSpecs(fake('nokind')) === null && await M.ivaCatalogSpecs(fake('nokinds')) === null, 'каталог: нет вида или видов — null (как раньше)');
  if (process.exitCode) return;
  console.log('✔ фильтр по характеристикам на сайте: ' + pass + ' проверок (' + Object.entries(counts).map(e => e[0] + ' ' + e[1]).join(', ') + ')');
})();

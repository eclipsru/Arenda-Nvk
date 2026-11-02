/* Справочник моделей, этап 4 (10.84): «Предложить модель», подсказка цены, «Сгенерировать описание», комплектация —
   функции сайта (assets/models.js) на общих случаях tests/model-stage4-cases.txt (их же проверяет IvaPatchTest)
   и на настоящей комплектации из supabase/models/kit.txt. */
const fs = require('fs');
const path = require('path');
const ROOT = path.join(__dirname, '..');
const M = require(path.join(ROOT, 'assets/models.js'));

let pass = 0;
function ok(cond, name, extra) {
  if (!cond) { console.error('✘ ' + name + (extra ? '\n    ' + extra : '')); process.exitCode = 1; return; }
  pass++;
}
const nb = s => String(s).replace(/\u00a0/g, ' ');
const list = s => (s ? s.split(';') : []);

const F = require(path.join(ROOT, 'assets/model-form.js'));
let brands = [], mindex = null;
const counts = { parts: 0, hint: 0, describe: 0, kit: 0, title: 0, exact: 0, err: 0 };
for (const line of fs.readFileSync(path.join(ROOT, 'tests/model-stage4-cases.txt'), 'utf8').split('\n')) {
  if (!line || line.startsWith('#')) continue;
  const f = line.split('\t');
  if (f[0] === 'brands') {
    brands = f[1].split(';').map(x => { const [n, a] = x.split('='); return { name: n, aliases: a ? a.split(',') : [] }; });
  } else if (f[0] === 'parts') {
    const r = M.ivaProposalParts(f[1], f[2], brands);
    ok((r ? r.brand + '|' + r.model : '—') === f[3], 'parts «' + f[1] + '» / «' + f[2] + '»', JSON.stringify(r) + ' ≠ ' + f[3]);
    counts.parts++;
  } else if (f[0] === 'hint') {
    const rows = [];
    for (const tok of f[3].split(' ')) {
      const m = tok.match(/^(.*?)(?:×(\d+))?$/);
      const [p, d, id, k] = m[1].split(':');
      for (let i = 0; i < (+m[2] || 1); i++) rows.push({ price: p === '' ? null : +p, deposit: d === '' ? null : +d, model_id: id === '-' ? null : +id, kind: k });
    }
    const h = M.ivaPriceHint(rows, f[1] === '' ? null : +f[1], f[2]);
    const got = h ? nb(M.ivaPriceHintText(h)) + ' #' + h.price[2] + '/' + (h.deposit ? h.deposit[2] : '-') : '—';
    ok(got === f[4], 'hint ' + f[3].slice(0, 40), got + '\n    ≠ ' + f[4]);
    counts.hint++;
  } else if (f[0] === 'describe') {
    const rows = list(f[2]).map(x => x.split('='));
    const got = M.ivaDescribe(f[1], rows, list(f[3])) || '—';
    ok(got === f[4].replace(/\\n/g, '\n'), 'describe «' + f[1] + '»', JSON.stringify(got));
    counts.describe++;
  } else if (f[0] === 'title') {
    const [b, m] = f[2].split('|');
    const got = M.ivaProposalTitle(f[1], { brand: b, model: m }, f[3]);
    ok(got === f[4], 'title «' + f[1] + '»', JSON.stringify(got));
    counts.title++;
  } else if (f[0] === 'models') {
    mindex = M.ivaModelIndex(f[1].split(';').map((x, i) => { const [k, b, m] = x.split(':'); return { id: i + 1, kind: k, brand: b, model: m, title: m, specs: {} }; }), brands, []);
  } else if (f[0] === 'exact') {
    const r = M.ivaModelExact(mindex, f[1], f[2]);
    ok((r ? r.brand + '|' + r.model : '—') === f[3], 'exact ' + f[1] + ' «' + f[2] + '»', JSON.stringify(r));
    counts.exact++;
  } else if (f[0] === 'err') {
    ok(F.ivaProposalErr(f[1]) === f[2], 'err «' + f[1] + '»', F.ivaProposalErr(f[1]));
    counts.err++;
  } else if (f[0] === 'kit') {
    const r = M.ivaKitClean(list(f[1]), list(f[2]));
    ok((r ? r.join(';') : '—') === f[3], 'kit ' + f[1], JSON.stringify(r));
    counts.kit++;
  }
}
ok(counts.parts >= 15 && counts.hint >= 10 && counts.describe >= 5 && counts.kit >= 4 && counts.title >= 4 && counts.exact >= 8 && counts.err >= 6, 'случаев достаточно', JSON.stringify(counts));

// настоящая комплектация: описание с ней собирается, пункты со строчной (кроме аббревиатур)
const kit = {};
for (const line of fs.readFileSync(path.join(ROOT, 'supabase/models/kit.txt'), 'utf8').split('\n')) {
  const m = line.trim().match(/^([a-z0-9-]+):\s*(.*)$/);
  if (m) kit[m[1]] = m[2] ? m[2].split(';').map(s => s.trim()) : [];
}
ok(Object.keys(kit).length === 159, 'комплектация у 159 видов', Object.keys(kit).length);
let allOk = true;
for (const k of Object.keys(kit)) {
  const items = kit[k];
  if (!items.length) continue;
  const d = M.ivaDescribe('Инструмент', [], items);
  const tail = d.split('В комплекте: ')[1] || '';
  if (!tail.endsWith('.') || tail.split(', ').length !== items.length || M.ivaKitClean(items.slice().reverse(), items).join(';') !== items.join(';')) {
    allOk = false; console.error('  ' + k + ': ' + d);
  }
}
ok(allOk, 'описание и порядок комплектации для всех видов с пунктами');

// подсказка: деление на 0 и мусор не ломают
ok(M.ivaPriceHint(null, 1, 'a') === null && M.ivaPriceHint([{ price: 'abc', kind: 'a' }, {}, null], null, 'a') === null, 'пустые и битые строки');
ok(M.ivaPriceHintText(null) === '', 'нет подсказки — пустой текст');

console.log('✔ этап 4 справочника: ' + pass + ' проверок (parts ' + counts.parts + ', hint ' + counts.hint + ', describe ' + counts.describe + ', kit ' + counts.kit + ', title ' + counts.title + ', exact ' + counts.exact + ', err ' + counts.err + ')');

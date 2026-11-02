/* Справочник моделей: общий разбор миграций и данных для браузерных тестов (10.82 — model-form-browser, 10.84 — model-stage4-browser). */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const MIG = path.join(ROOT, 'supabase/migrations');
const un = s => s.replace(/''/g, "'");

function kindsFromSql() {
  const tree = fs.readFileSync(path.join(MIG, '20261015_category_tree.sql'), 'utf8');
  const words = fs.readFileSync(path.join(MIG, '20261016_kind_words.sql'), 'utf8');
  const i0 = tree.indexOf('insert into public.iva_kinds');
  const block = tree.slice(i0, tree.indexOf('on conflict (key)', i0));
  const out = [];
  const re = /\('([a-z0-9-]+)',\s*'([a-z0-9-]+)',\s*'([a-z0-9-]+)',\s*'([^']+)',\s*(\d+)\)/g;
  let m;
  while ((m = re.exec(block))) out.push({ key: m[1], cat: m[2], sub: m[3], title: m[4], sort: +m[5], words: [] });
  const by = Object.fromEntries(out.map(k => [k.key, k]));
  const wre = /^\s*\('([a-z0-9-]+)', array\[(.*)\]::text\[\]\)/gm;
  while ((m = wre.exec(words))) by[m[1]].words = m[2].split(',').map(s => s.trim().replace(/^'|'$/g, ''));
  return out;
}

function dirFromSql() {
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
  const fields = [];
  const fre = /^ {2}\('([a-z0-9-]+)', '([a-z0-9]+)', '((?:[^']|'')*)', '((?:[^']|'')*)', '(num|choice|text)', (?:array\[(.*)\]::text\[\]|'\{\}'::text\[\]), (\d+)\)/gm;
  while ((m = fre.exec(fsec))) fields.push({ kind: m[1], key: m[2], title: un(m[3]), unit: un(m[4]), type: m[5],
    options: m[6] ? m[6].split(/', '/).map(x => un(x.replace(/^'|'$/g, ''))) : [], sort: +m[7] });
  return { models, brands, fields };
}

/* 10.84: комплектация по видам из supabase/models/kit.txt — {вид: [пункты]} (виды без пунктов не включаются, как в загрузчике) */
function kitFromTxt() {
  const out = {};
  for (const line of fs.readFileSync(path.join(ROOT, 'supabase/models/kit.txt'), 'utf8').split('\n')) {
    const m = line.trim().match(/^([a-z0-9-]+):\s*(.*)$/);
    if (m && m[2]) out[m[1]] = m[2].split(';').map(x => x.trim());
  }
  return out;
}

module.exports = { kindsFromSql, dirFromSql, kitFromTxt };

// Ива — сверка города и состояния объявлений: приложение (IvaSearchPatch, через IvaCatalogAudit) = сайт (assets/geo.js + toolToAd из assets/sb.js).
// Только чтение: берёт выгрузку tools/catalog-audit/fetch.py и app-cities.tsv, которую пишет IvaCatalogAudit.
// Запуск: node tools/catalog-audit/site-compare.cjs /tmp/catalog
// Без аргумента — самопроверка на примерах (нужна, чтобы сверка не «проходила» молча, если сломается сама).
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const ROOT = path.join(__dirname, '..', '..');

function siteGeo() {
  const data = fs.readFileSync(path.join(ROOT, 'assets', 'data.js'), 'utf8');
  const m = data.match(/const CITIES = \[([\s\S]*?)\];/);
  const CITIES = (m[1].match(/'[^']+'|"[^"]+"/g) || []).map(x => x.slice(1, -1));
  // geo.js при загрузке ждёт DOMContentLoaded — заглушка document, чтобы он не искал поля формы
  const ctx = { window: {}, CITIES, console, document: { readyState: 'loading', addEventListener() {}, documentElement: {} },
    MutationObserver: class { observe() {} } };
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(path.join(ROOT, 'assets', 'geo.js'), 'utf8'), ctx);
  return ctx.window.IvaGeo;
}

// Ровно как toolToAd в assets/sb.js (город и состояние).
function siteTool(geo, t) {
  const terms = t.terms || '';
  let cond = 'good';
  if (/Состояние:\s*Новый/i.test(terms)) cond = 'new';
  else if (/Состояние:\s*Рабочее/i.test(terms)) cond = 'work';
  const mAddr = terms.match(/Адрес выдачи:\s*(.+)/i);
  const pickupAddress = String(t.pickup_address || (mAddr ? mAddr[1] : '') || '').trim();
  const city = geo.city(t.pickup_city) || (pickupAddress ? geo.addressCity(pickupAddress) : 'Новочеркасск');
  return { city, cond, stated: /Состояние:\s*(Новый|Хорошее|Рабочее)/i.test(terms) };
}

const un = s => s === '\u0001' || s === '\u0002' ? null
  : s.replace(/\\(.)/g, (_, c) => c === 't' ? '\t' : c === 'n' ? '\n' : c === 'r' ? '\r' : c);

const dir = process.argv[2];
const geo = siteGeo();
if (!dir) {
  const ex = [
    [{ pickup_city: 'Аксай' }, 'Аксай'],
    [{ terms: 'Адрес выдачи: г. Шахты, ул. Победы 2' }, 'Шахты'],
    [{ pickup_address: 'ул. Садовая, 1' }, ''],
    [{}, 'Новочеркасск'],
  ];
  for (const [t, want] of ex) {
    const got = siteTool(geo, t).city;
    if (got !== want) { console.error(`✘ самопроверка: ${JSON.stringify(t)} → «${got}», ждали «${want}»`); process.exit(1); }
  }
  console.log('самопроверка сверки с сайтом: 4/4');
  process.exit(0);
}

const lines = fs.readFileSync(path.join(dir, 'tools.tsv'), 'utf8').split('\n').filter(Boolean);
const head = lines.shift().split('\t');
const app = new Map(fs.readFileSync(path.join(dir, 'app-cities.tsv'), 'utf8').split('\n').filter(Boolean)
  .map(l => l.split('\t')).map(([id, city, cond]) => [id, { city, cond: Number(cond) }]));
let same = 0, diff = 0, condSame = 0, condDiff = 0, unstated = 0;
const examples = [];
for (const l of lines) {
  const row = l.split('\t'), t = {};
  head.forEach((k, i) => { t[k] = un(row[i] ?? '\u0001'); });
  const s = siteTool(geo, t), a = app.get(String(t.id ?? 'null'));
  if (!a) { diff++; examples.push(`id ${t.id}: нет в app-cities.tsv`); continue; }
  if (s.city === a.city) same++; else { diff++; if (examples.length < 5) examples.push(`id ${t.id}: сайт «${s.city}», приложение «${a.city}»`); }
  const ac = a.cond === 1 ? 'new' : a.cond === 4 ? 'work' : a.cond === 2 ? 'good' : '';
  if (!s.stated) unstated++;
  else if (ac === s.cond) condSame++; else condDiff++;
}
const msg = `город: совпал у ${same}, разошёлся у ${diff}${examples.length ? ' — ' + examples.join('; ') : ''}%0A`
  + `состояние (где указано): совпало ${condSame}, разошлось ${condDiff}; не указано ${unstated} — сайт считает их «Хорошее», приложение при фильтре не показывает`;
console.log(msg.replace(/%0A/g, '\n   '));
console.log(`::notice title=Каталог · Сверка с сайтом::${msg}`);
process.exit(diff || condDiff ? 1 : 0);

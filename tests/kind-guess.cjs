/* Автоподбор вида (10.81): JS-алгоритм сайта на настоящем словаре из миграций и общих случаях. */
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const ROOT = path.join(__dirname, '..');
const { ivaGuessKind, ivaKindTokens, ivaKindMayFill, ivaKindPlan } = require(path.join(ROOT, 'assets/kind-guess.js'));

function kindsFromSql() {
  const tree = fs.readFileSync(path.join(ROOT, 'supabase/migrations/20261015_category_tree.sql'), 'utf8');
  const words = fs.readFileSync(path.join(ROOT, 'supabase/migrations/20261016_kind_words.sql'), 'utf8');
  const block = tree.slice(tree.indexOf('insert into public.iva_kinds'), tree.indexOf('on conflict (key)', tree.indexOf('insert into public.iva_kinds')));
  const kinds = [];
  const re = /\('([a-z0-9-]+)',\s*'([a-z0-9-]+)',\s*'([a-z0-9-]+)',\s*'([^']+)',\s*\d+\)/g;
  let m;
  while ((m = re.exec(block))) kinds.push({ key: m[1], cat: m[2], sub: m[3], title: m[4], words: [] });
  const byKey = Object.fromEntries(kinds.map(k => [k.key, k]));
  const wre = /^\s*\('([a-z0-9-]+)', array\[(.*)\]::text\[\]\)/gm;
  let n = 0;
  while ((m = wre.exec(words))) {
    assert(byKey[m[1]], 'вид из words есть в дереве: ' + m[1]);
    byKey[m[1]].words = m[2].split(',').map(s => s.trim().replace(/^'|'$/g, ''));
    n++;
  }
  return { kinds, n };
}

let pass = 0;
function ok(cond, name, extra) {
  if (!cond) { console.error('✘ ' + name + (extra ? '\n    ' + extra : '')); process.exitCode = 1; return; }
  pass++;
}

const { kinds, n } = kindsFromSql();
ok(kinds.length === 159 && n === 159, 'словарь: 159 видов и 159 строк words', kinds.length + '/' + n);
ok(kinds.every(k => k.words.length > 0), 'у каждого вида есть слова');
ok(kinds.every(k => k.words.every(w => w === w.toLowerCase() && !/ё/.test(w) && /^[a-zа-я0-9]+( [a-zа-я0-9]+)*$/.test(w))),
   'слова — строчные, без ё, только буквы, цифры и одиночные пробелы');

const cases = fs.readFileSync(path.join(ROOT, 'tests/kind-guess-cases.txt'), 'utf8').split('\n')
  .filter(l => l.trim() && !l.startsWith('#')).map(l => l.split('|'));
ok(cases.length >= 120, 'случаев не меньше 120', String(cases.length));
for (const [name, cat, sub, kind] of cases) {
  const g = ivaGuessKind(name, kinds);
  ok(g.cat === cat && g.sub === sub && g.kind === kind, 'случай «' + name + '»',
     'ждали ' + [cat, sub, kind].join('/') + ', получили ' + [g.cat, g.sub, g.kind].join('/'));
}
for (const k of kinds) {
  const g = ivaGuessKind(k.title, kinds);
  ok(g.kind === k.key && g.cat === k.cat && g.sub === k.sub, 'название вида «' + k.title + '» подбирает сам вид', JSON.stringify(g));
}

// Алгоритм на маленьком словаре — те же проверки есть в IvaPatchTest.
const mini = [
  { key: 'a1', cat: 'c1', sub: 's1', words: ['перфорат', 'перфорат max'] },
  { key: 'a2', cat: 'c1', sub: 's1', words: ['перфорат', 'перфорат plus'] },
  { key: 'b1', cat: 'c1', sub: 's2', words: ['пил', 'цепн пил'] },
  { key: 'd1', cat: 'c2', sub: 's3', words: ['пил', 'пил по металл'] },
  { key: 'e1', cat: 'c1', sub: 's4', words: ['фрез'] },
  { key: 'e2', cat: 'c1', sub: 's5', words: ['фрез'] },
];
const G = s => { const g = ivaGuessKind(s, mini); return [g.cat, g.sub, g.kind].join('/'); };
ok(G('Перфоратор Makita') === 'c1/s1/', 'мини: ничья в подкатегории → кат+подкат');
ok(G('перфоратор SDS-MAX') === 'c1/s1/a1', 'мини: уточнение выигрывает');
ok(G('Цепная пила') === 'c1/s2/b1', 'мини: сочетание выигрывает у общего');
ok(G('Пила') === '//', 'мини: ничья между категориями → пусто');
ok(G('Фреза') === 'c1//', 'мини: ничья в категории → только категория');
ok(G('бензопила') === '//', 'мини: часть должна быть началом слова');
ok(G('') === '//' && G(null) === '//' && ivaGuessKind('пила', []).cat === '', 'мини: пусто → пусто');
ok(ivaKindTokens('Шуруповёрт-дрель, 18V!').join(' ') === 'шуруповерт дрель 18v', 'нормализация');
ok(ivaKindMayFill('', 'x') && ivaKindMayFill('x', 'x') && !ivaKindMayFill('y', 'x') && ivaKindMayFill('', ''), 'ручной выбор не перезаписывается');

// Что подставлять: те же случаи — в IvaPatchTest (plan).
const P = (cur, last, g) => { const r = ivaKindPlan(cur, last, g); return r ? [r.cat, r.sub, r.kind].join('/') : 'null'; };
const E = { cat: '', sub: '', kind: '' };
const g1 = { cat: 'power', sub: 'hammers', kind: 'rotary-sds-max' };
ok(P(E, E, g1) === 'power/hammers/rotary-sds-max', 'план: пустая форма — всё');
ok(P(E, E, { cat: '', sub: '', kind: '' }) === 'null', 'план: нечего подставить — не трогаем');
ok(P({ cat: 'garden', sub: '', kind: '' }, E, g1) === 'null', 'план: категорию выбрали руками — не меняем');
ok(P({ cat: 'power', sub: 'hammers', kind: '' }, { cat: 'power', sub: 'hammers', kind: '' }, g1) === 'power/hammers/rotary-sds-max', 'план: уточнили название — вид дописан');
ok(P({ cat: 'power', sub: 'hammers', kind: 'rotary-sds-max' }, { cat: 'power', sub: 'hammers', kind: 'rotary-sds-max' }, { cat: 'power', sub: 'hammers', kind: '' }) === 'power/hammers/', 'план: название стало общим — наш вид снят');
ok(P({ cat: 'power', sub: 'hammers', kind: 'rotary-sds-plus' }, { cat: 'power', sub: 'hammers', kind: '' }, { cat: 'power', sub: 'hammers', kind: '' }) === 'null', 'план: вид выбран руками — остаётся');
ok(P({ cat: 'power', sub: 'drills', kind: '' }, { cat: 'power', sub: '', kind: '' }, g1) === 'null', 'план: подкатегорию выбрали руками — остаётся, вид не ставим');
ok(P({ cat: 'power', sub: 'hammers', kind: 'rotary-sds-max' }, { cat: 'power', sub: 'hammers', kind: 'rotary-sds-max' }, { cat: 'garden', sub: 'trimmers', kind: 'chainsaw' }) === 'garden/trimmers/chainsaw', 'план: переписали название — наше заменяется');
ok(P({ cat: 'power', sub: '', kind: '' }, E, g1) === 'power/hammers/rotary-sds-max', 'план: та же категория руками — подкатегорию и вид дописываем');
ok(P({ cat: 'power', sub: 'hammers', kind: 'rotary-sds-max' }, E, g1) === 'null', 'план: уже стоит то же — ничего');

if (process.exitCode) console.error('kind-guess: есть ошибки'); else console.log('✔ автоподбор вида: ' + pass + ' проверок');

// Сторож «заявки и персональные данные» (этап П4, шаг 2).
//
// Смысл: заявка — это первый момент, когда «Ива» собирает ПД. Решения владельца:
//   • храним заявки в текущей Supabase, но собираем минимум данных («тонкие» заявки);
//   • срок хранения — 12 месяцев, потом обезличивание;
//   • согласие — обязательный флажок в форме, без него заявка не уходит;
//   • политика обработки ПД для клиентов — отдельная страница, ссылка из подвала и из формы;
//   • текст заявки в Роскомнадзор — черновик, подаёт владелец.
// Сторож ловит откат этих правил: если флажок согласия убрать, политику потерять,
// в форму начать просить паспорт или карты, или вернуться к «храню вечно» — проверка упадёт.
//
// Запуск: node tests/p4-personal-data.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

try {
  // 1. Политика для клиентов существует и говорит главное
  assert.ok(fs.existsSync(path.join(ROOT, 'privacy.html')), 'нет страницы политики privacy.html');
  const privacy = read('privacy.html');
  assert.match(privacy, /Галиновский/, 'в политике не назван оператор — субъект должен знать, кто обрабатывает данные');
  assert.match(privacy, /eclips\.ru@mail\.ru/, 'в политике нет контакта для отзыва согласия и удаления данных');
  assert.match(privacy, /12 месяцев/, 'в политике не указан срок хранения (решение владельца — 12 месяцев)');
  assert.match(privacy, /Роскомнадзор/, 'в политике не сказано про уведомление Роскомнадзора и трансграничную передачу');
  assert.match(privacy, /пункт проката/i, 'в политике не сказано, кто ещё видит заявку (пункт проката, который её берёт)');
  assert.match(privacy, /href="offer\.html"/, 'из политики нет ссылки на оферту с реквизитами оператора');

  // 2. ОБЕ формы заявки: согласие обязательно, ссылка на политику рядом.
  //    Заявку отправляют две страницы: кабинет (cabinet.html) и карточка
  //    инструмента (tool.html) — правило должно быть в обеих, иначе половина
  //    заявок уходит без согласия (нашлось 01.10.2026: в tool.html согласия не было).
  const cabinet = read('cabinet.html');
  assert.match(cabinet, /<input type="checkbox" id="oAgree"/,
    'в форме заявки кабинета нет флажка согласия на обработку данных');
  assert.match(cabinet, /href="privacy\.html"/,
    'в форме заявки кабинета нет ссылки на политику — согласие должно быть информированным');
  assert.match(cabinet, /\$\('oAgree'\)\.checked/,
    'отправка заявки из кабинета не проверяет флажок согласия — заявка уйдёт без согласия');
  assert.match(cabinet, /Требуется согласие на обработку данных/,
    'нет понятного сообщения, почему заявка не отправляется без согласия (кабинет)');
  assert.match(cabinet, /позвоним сами, подберём вариант или закроем заявку/,
    'в форме не сказано, что будет, если заявку не взяли (честное обещание вместо тишины)');
  assert.match(cabinet, /Телефон и адрес храним 12 месяцев/,
    'в списке заявок не сказано, сколько хранятся контакты');

  const tool = read('tool.html');
  assert.match(tool, /<input type="checkbox" id="ordAgree"/,
    'в форме заявки на странице инструмента (tool.html) нет флажка согласия — заявка уйдёт без согласия');
  assert.match(tool, /href="privacy\.html"/,
    'в форме заявки на странице инструмента нет ссылки на политику');
  assert.match(tool, /\$\('ordAgree'\)\.checked/,
    'отправка заявки со страницы инструмента не проверяет флажок согласия');
  assert.match(tool, /Требуется согласие на обработку данных/,
    'нет понятного сообщения, почему заявка не отправляется без согласия (инструмент)');
  assert.match(tool, /Храним 12 месяцев/,
    'рядом с флажком на странице инструмента не сказано про срок хранения');

  // 3. «Тонкие заявки»: не просим то, что не нужно
  const form = cabinet.slice(cabinet.indexOf('function orderSheet('), cabinet.indexOf('function orderSheet(') + 4000);
  for (const forbidden of [/паспорт/i, /номер карты/i, /данные карты/i, /cvv/i, /код из sms/i, /дата рождения/i]) {
    assert.ok(!forbidden.test(form), `форма заявки просит лишние данные (${forbidden}) — нарушение «тонких» заявок`);
  }

  // 4. Подвал ведёт на политику, и версия app.js поднята везде —
  //    иначе посетители увидят старый подвал из кеша.
  const app = read('assets/app.js');
  const foot = app.slice(app.indexOf('function footHTML('), app.indexOf('function footHTML(') + 1200);
  assert.match(foot, /href="privacy\.html"/, 'в общем подвале нет ссылки на политику обработки данных');

  const htmlFiles = [];
  const walk = d => fs.readdirSync(d, { withFileTypes: true }).forEach(e => {
    const p = path.join(d, e.name);
    if (e.isDirectory()) return walk(p);
    if (e.name.endsWith('.html')) htmlFiles.push(p);
  });
  walk(ROOT);
  const versions = new Set();
  for (const f of htmlFiles) {
    const body = fs.readFileSync(f, 'utf8');
    const m = body.match(/assets\/app\.js\?v=([a-z0-9-]+)/);
    if (m) versions.add(m[1]);
  }
  assert.equal(versions.size, 1,
    `версия assets/app.js разная на страницах (${[...versions].join(', ')}) — ` +
    'после правки подвала версию надо поднять везде, иначе кто-то увидит старую страницу');

  // 5. Срок хранения зафиксирован в базе: функция обезличивания + расписание
  const mig = 'supabase/migrations/20261001_orders_retention.sql';
  assert.ok(fs.existsSync(path.join(ROOT, mig)), 'нет миграции срокa хранения заявок');
  const sql = read(mig);
  assert.match(sql, /CREATE OR REPLACE FUNCTION public\.iva_purge_requests\(\)/,
    'в миграции нет функции обезличивания старых заявок');
  assert.match(sql, /12 months/, 'в миграции не 12 месяцев — решение владельца не выполнено');
  assert.match(sql, /to_regclass\('public\.orders'\) IS NULL/,
    'миграция не защищена: упадёт, если таблицы orders нет');
  assert.match(sql, /DROP FUNCTION IF EXISTS public\.iva_purge_requests/,
    'в миграции не написано, как откатывать');
  assert.match(sql, /name/, 'миграция должна обезличивать имя заявителя');

  // 6. Черновик уведомления в РКН на месте и помечен как черновик
  const rkn = 'docs/РКН-УВЕДОМЛЕНИЕ-ЧЕРНОВИК.md';
  assert.ok(fs.existsSync(path.join(ROOT, rkn)), 'нет черновика уведомления в Роскомнадзор');
  const draft = read(rkn);
  assert.match(draft, /черновик/i, 'документ РКН не помечен как черновик');
  assert.match(draft, /владелец/i, 'не сказано, что уведомление подаёт владелец');

  // 7. Журнал знает про этап
  assert.match(read('docs/ЖУРНАЛ.md'), /П4 \(шаг 2\)|П4 — заявки и персональные данные/,
    'в docs/ЖУРНАЛ.md нет записи об этапе П4 (шаг 2)');

  console.log('Заявки и ПД в порядке: согласие обязательно, политика доступна из формы и подвала, ' +
    '«тонкие» данные, срок хранения 12 месяцев зафиксирован в миграции, черновик РКН на месте.');
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

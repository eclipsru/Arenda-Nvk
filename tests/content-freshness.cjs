// Сторож «свежесть контента» (этап П7, 07.10.2026).
//
// Страницы городов собираются из снимков боевой базы:
//   tools/directory_existing.json — справочник пунктов проката;
//   tools/listings_by_city.json   — счётчики объявлений по городам.
// Снимки обновляет контент-машина (tools/content-refresh.sh) раз в неделю.
//
// Сторож показывает возраст снимков и не даёт им протухнуть незаметно:
//   старше 14 дней — предупреждение (проверка остаётся зелёной);
//   старше 45 дней — ошибка: контент на сайте явно разошёлся с базой.
// Запуск: node tests/content-freshness.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const WARN_DAYS = 14;
const FAIL_DAYS = 45;

function ageDays(file) {
  const data = JSON.parse(fs.readFileSync(path.join(ROOT, file), 'utf8'));
  const taken = data.taken_at;
  assert.ok(taken, `${file}: нет поля taken_at — неизвестно, когда сняты данные`);
  const then = new Date(taken + 'T00:00:00Z');
  assert.ok(!Number.isNaN(then.getTime()), `${file}: taken_at «${taken}» не похож на дату`);
  return { days: Math.floor((Date.now() - then.getTime()) / 86400000), taken, count: data.count ?? data.total ?? null };
}

const dir = ageDays('tools/directory_existing.json');
const lis = ageDays('tools/listings_by_city.json');
const worst = Math.max(dir.days, lis.days);

console.log(`  снимок справочника: ${dir.taken} (${dir.days} дн. назад, записей: ${dir.count})`);
console.log(`  снимок объявлений:  ${lis.taken} (${lis.days} дн. назад, всего: ${lis.count})`);

assert.ok(worst <= FAIL_DAYS,
  `снимки контента старше ${FAIL_DAYS} дней (справочник ${dir.days} дн., объявления ${lis.days} дн.). ` +
  'Запустите контент-машину: bash tools/content-refresh.sh --local (или Run workflow «Контент-машина»)');

if (worst > WARN_DAYS) {
  console.log(`  • внимание: снимки старше ${WARN_DAYS} дней — контент-машина их обновит в ближайший понедельник`);
}
console.log('✔ content-freshness: снимки контента свежие');

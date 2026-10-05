// Сторож «выкладка функций Supabase» (решение владельца №21 от 04.10.2026, вариант А):
// из GitHub выкладываются ТОЛЬКО функции; SQL-правки базы применяет владелец.
// Ловит: команды, меняющие базу (db push / migration / database query / psql); вывод секретов
// в журнал запуска; расширение прав задачи; запуск на чужих ветках и из PR (форков).
// Запуск: node tests/deploy-functions-guard.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
try {
  const f = '.github/workflows/deploy-functions.yml';
  const y = fs.readFileSync(path.join(__dirname, '..', f), 'utf8');
  const code = y.replace(/^\s*#.*$/gm, '');
  for (const re of [/supabase\s+db\b/i, /\bmigration\b/i, /database\/query/i, /\bpsql\b/i, /\bdb\s+push\b/i, /\bdb\s+reset\b/i])
    assert.ok(!re.test(code), `${f}: задача трогает базу (${re}) — по решению владельца №21 только функции`);
  assert.match(code, /supabase functions deploy/, `${f}: нет выкладки функций`);
  assert.match(code, /permissions:\s*\n\s*contents: read/, `${f}: права задачи должны быть только на чтение`);
  assert.ok(!/pull_request/.test(code), `${f}: выкладка из Pull Request (в т. ч. форков) запрещена`);
  assert.match(code, /branches: \['main', 'arena\/01a101d5-arenda-nvk'\]/, `${f}: выкладка с посторонних веток`);
  for (const s of ['SUPABASE_ACCESS_TOKEN', 'SMTP_PASS', 'SMTP_USER'])
    assert.ok(!new RegExp(`echo[^\\n]*\\$\\{?${s}`).test(code), `${f}: секрет ${s} выводится в журнал запуска`);
  assert.ok(!/set\s+-[a-z]*x/.test(code), `${f}: set -x покажет секреты в журнале`);
  console.log('Выкладка функций: только функции, база не трогается, секреты не выводятся, права — чтение');
} catch (e) { console.error('✘ ' + e.message); process.exit(1); }

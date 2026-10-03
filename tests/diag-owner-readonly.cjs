// Сторож «диагностика владельца только читает» (03.10.2026).
//
// tools/diag-owner.sql владелец вставляет в SQL-редактор БОЕВОЙ базы и присылает
// результат в чат. Поэтому запрос обязан:
//   1) ничего не менять в базе (никаких insert/update/delete/drop/create/alter/truncate/grant…);
//   2) не показывать секреты: токен бота в адресах маскируется, из Vault берутся
//      только названия секретов, а не значения; заголовки вебхуков (там ключи) не выводятся;
//   3) не показывать персональные данные заявок (имя, телефон, адрес, email).
// Запуск: node tests/diag-owner-readonly.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
try {
  const raw = fs.readFileSync(path.join(ROOT, 'tools/diag-owner.sql'), 'utf8');
  // Убираем комментарии: в них слова «удаляет», «drop» и т. п. допустимы.
  const sql = raw.replace(/--[^\n]*/g, '').replace(/\/\*[\s\S]*?\*\//g, '');
  const low = sql.toLowerCase();

  // 1. Только чтение
  for (const w of ['insert', 'update', 'delete', 'drop', 'truncate', 'alter', 'create', 'grant',
                   'revoke', 'comment on', 'copy', 'vacuum', 'cluster', 'reindex', 'refresh',
                   'do $', 'call ', 'perform', 'execute', 'set role', 'set session', 'nextval', 'setval',
                   'cron.schedule', 'cron.unschedule', 'net.http_', 'pg_terminate', 'lo_', 'dblink']) {
    // «INSERT»/«UPDATE»/«DELETE» встречаются только как подписи событий триггера в кавычках —
    // их вырезаем перед проверкой.
    const scrubbed = low.replace(/'(insert|update|delete)'/g, "''");
    // Ищем целые слова: «created_at» — не «create».
    const esc = w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp((/^\w/.test(w) ? '\\b' : '') + esc + (/\w$/.test(w) ? '\\b' : ''));
    assert.ok(!re.test(scrubbed), `diag-owner.sql: найдено «${w}» — запрос должен только читать`);
  }
  assert.match(low.trim(), /^with\b/, 'diag-owner.sql: запрос должен быть одним select (with … select)');
  // Точки с запятой внутри строк ('; ') и вложенных запросов ($x$…$x$) не считаем.
  const bare = sql.replace(/\$x\$[\s\S]*?\$x\$/g, '').replace(/'(?:[^']|'')*'/g, "''");
  assert.equal((bare.match(/;/g) || []).length, 1, 'diag-owner.sql: должна быть ровно одна команда');
  assert.ok(!/iva_purge_requests\(\)\s*(as|from|,|\))/i.test(sql.replace(/to_regprocedure\('public\.iva_purge_requests\(\)'\)/g, '')),
    'diag-owner.sql: функцию обезличивания нельзя ВЫЗЫВАТЬ из диагностики — только проверять, что она есть');

  // 2. Секреты
  assert.match(sql, /bot\[0-9\]\+:\[A-Za-z0-9_-\]\+', 'bot<скрыто>'/,
    'diag-owner.sql: нет маски токена бота в адресах (bot123:ABC → bot<скрыто>)');
  assert.ok((sql.match(/bot<скрыто>/g) || []).length >= 2,
    'diag-owner.sql: маска токена должна стоять и для вебхуков, и для заданий по расписанию');
  assert.match(sql, /'\\000', 1\)/,
    'diag-owner.sql: из аргументов вебхука берётся не только адрес — заголовки с ключами могут утечь');
  assert.ok(!/decrypted_secret|\bsecret\s+from|select\s+\*\s+from\s+vault/i.test(sql),
    'diag-owner.sql: из Vault читаются значения секретов — можно только названия');
  assert.ok(!/select\s+\*/i.test(sql), 'diag-owner.sql: «select *» может вывести лишние данные');

  // 3. Персональные данные
  for (const col of ['phone', 'address', 'full_name', 'user_email', 'comment']) {
    assert.ok(!new RegExp(`\\b${col}\\b`).test(sql.replace(/comment on/gi, '')),
      `diag-owner.sql: упоминается колонка ${col} — отчёт не должен показывать персональные данные`);
  }

  // 4. Диагностика №2 — устройство бота (tools/diag-bot.sql): те же правила «только чтение»,
  //    а раз она выводит ТЕКСТЫ функций — токен и ключи обязаны маскироваться.
  {
    const raw2 = fs.readFileSync(path.join(ROOT, 'tools/diag-bot.sql'), 'utf8');
    const sql2 = raw2.replace(/--[^\n]*/g, '');
    const low2 = sql2.toLowerCase().replace(/'(insert|update|delete)'/g, "''");
    for (const w of ['insert', 'update', 'delete', 'drop', 'truncate', 'alter', 'create', 'grant',
                     'revoke', 'copy', 'perform', 'execute', 'call', 'nextval', 'setval', 'dblink']) {
      // «execute» допустим только как право в has_function_privilege(…, 'execute')
      const scrubbed = low2.replace(/'execute'/g, "''");
      assert.ok(!new RegExp('\\b' + w + '\\b').test(scrubbed), `diag-bot.sql: найдено «${w}» — запрос должен только читать`);
    }
    const bare2 = sql2.replace(/'(?:[^']|'')*'/g, "''");
    assert.equal((bare2.match(/;/g) || []).length, 1, 'diag-bot.sql: должна быть ровно одна команда');
    assert.match(sql2, /'\(bot\)\?\[0-9\]\{6,12\}:\[A-Za-z0-9_-\]\{25,\}', '<скрыто>'/,
      'diag-bot.sql: токен бота в текстах функций не маскируется (в т. ч. без приставки bot)');
    assert.match(sql2, /'eyJ\[A-Za-z0-9_-\]/, 'diag-bot.sql: ключи Supabase (eyJ…) в текстах функций не маскируются');
    assert.match(sql2, /sb_secret_\[A-Za-z0-9_-\]\+', '<скрыто>'/, 'diag-bot.sql: ключи sb_secret_ не маскируются');
    assert.ok(/regexp_replace\([\s\S]*pg_get_functiondef\(p\.oid\)/.test(sql2) &&
              (sql2.match(/pg_get_functiondef/g) || []).length === 1,
      'diag-bot.sql: текст функции выводится в обход маскировки');
    assert.ok(!/select\s+\*/i.test(sql2), 'diag-bot.sql: «select *» может вывести содержимое таблиц');
    assert.ok(!/\bfrom\s+public\.(iva_tg|orders)/i.test(sql2), 'diag-bot.sql: читается содержимое таблиц бота/заявок — можно только колонки');
    assert.ok(!/decrypted_secret/i.test(sql2), 'diag-bot.sql: читаются значения секретов Vault');
  }

  console.log('Диагностика владельца в порядке: одна команда, только чтение, токены маскируются, ' +
    'из Vault — только названия, персональные данные заявок не выводятся; diag-bot.sql — только чтение, токен и ключи в текстах функций скрываются.');
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

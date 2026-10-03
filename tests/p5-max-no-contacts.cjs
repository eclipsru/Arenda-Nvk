// Сторож «в MAX не уходят контакты клиента» (этап П5, решение владельца от 03.10.2026: MAX — как Telegram).
// Ловит: возврат отправки всей строки заявки (to_jsonb(new) целиком), контакты в списке полей,
// опасные команды в миграции, пропажу защиты от сбоя и самопроверки, пропажу файла отката.
// Запуск: node tests/p5-max-no-contacts.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const ROOT = path.resolve(__dirname, '..');
const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

try {
  const f = 'supabase/migrations/20261003_max_no_contacts.sql';
  const mig = read(f);
  const code = mig.replace(/--[^\n]*/g, '');
  const body = code.slice(code.indexOf('CREATE OR REPLACE FUNCTION public.fn_order_to_max_hook('), code.indexOf('$function$;'));
  assert.ok(body.length > 100, `${f}: нет функции fn_order_to_max_hook`);

  for (const w of ['drop', 'truncate', 'delete', 'alter table', 'grant', 'revoke', 'create trigger']) {
    assert.ok(!new RegExp('\\b' + w + '\\b', 'i').test(code), `${f}: найдено «${w}» — разрешено только create or replace`);
  }
  assert.ok(!/'record'\s*,\s*to_jsonb\(\s*new\s*\)/i.test(body), `${f}: в MAX снова уходит ВСЯ строка заявки`);
  assert.ok(!/\b(v|new)\s*(->>?|\.)\s*'?(name|phone|user_email|address|comment|get_method|passport)\b/i.test(body),
    `${f}: в MAX снова уходит поле с контактами`);
  for (const k of ['name', 'phone', 'user_email', 'address', 'comment']) {
    const m = body.match(new RegExp(`'${k}'\\s*,\\s*([^\\n]+)`));
    assert.ok(m && /^'/.test(m[1].trim()), `${f}: поле «${k}» должно быть заглушкой-строкой, а не данными заявки`);
  }
  assert.match(body, /Контакты клиента — в кабинете: https:\/\/eclipsru\.github\.io\/Arenda-Nvk\/chief\.html/, `${f}: нет ссылки в кабинет`);
  assert.match(body, /exception when others then/i, `${f}: без защиты — сбой MAX сорвёт сохранение заявки`);
  assert.match(body, /SECURITY DEFINER/, `${f}: потерян SECURITY DEFINER`);
  assert.match(mig, /-- Самопроверка/, `${f}: нет самопроверки`);
  assert.ok(!/sb_secret_|service_role|eyJ[A-Za-z0-9_-]{20,}\./.test(mig), `${f}: секретный ключ в миграции`);

  const rb = read('supabase/bot/max_rollback_20261003.sql');
  assert.match(rb, /CREATE OR REPLACE FUNCTION public\.fn_order_to_max_hook\(/, 'нет файла/функции отката max_rollback_20261003.sql');
  assert.ok(!/\b(drop|truncate|delete)\b/i.test(rb.replace(/--[^\n]*/g, '')), 'в откате опасная команда');
  assert.ok(!/sb_secret_|service_role|eyJ[A-Za-z0-9_-]{20,}\./.test(rb), 'секретный ключ в откате');

  console.log('MAX без контактов: в уведомление уходят только номер, состав, срок и дата; откат на месте');
} catch (e) {
  console.error('✘ ' + e.message);
  process.exit(1);
}

// Сторож «бот не отправляет контакты в Telegram» (этап П5, решение владельца №17 от 03.10.2026).
//
// Бот живёт в базе (функции public.iva_tg_*). Решение: в Telegram — номер заявки,
// состав, ссылка в кабинет; имя, телефон, адрес, email, компания — НЕ отправляются.
// Сторож ловит:
//   • возврат контактов в миграцию supabase/migrations/20261003_bot_no_contacts.sql;
//   • опасные команды в миграции (drop/truncate/delete) — правило «только create or replace»;
//   • пропажу самопроверки и ссылок в кабинет;
//   • утечку секретов в снимок бота supabase/bot/iva_tg_snapshot_20261003.sql
//     (токен бота, ключ vk_wall_post, секретные ключи Supabase);
//   • пропажу блоков отката из снимка.
// Запуск: node tests/p5-bot-no-contacts.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

// Текст функции между CREATE OR REPLACE FUNCTION public.<name>( … и концом её тела $function$;
function fnBody(sql, name) {
  const start = sql.indexOf(`CREATE OR REPLACE FUNCTION public.${name}(`);
  assert.ok(start >= 0, `нет функции ${name}`);
  const end = sql.indexOf('$function$;', sql.indexOf('AS $function$', start) + 13);
  assert.ok(end > start, `не найден конец функции ${name}`);
  return sql.slice(start, end);
}

try {
  const migFile = 'supabase/migrations/20261003_bot_no_contacts.sql';
  const mig = read(migFile);
  const code = mig.replace(/--[^\n]*/g, '');

  // 1. Только create or replace, ничего не удаляем
  for (const w of ['drop', 'truncate', 'delete', 'alter table', 'grant', 'revoke']) {
    assert.ok(!new RegExp('\\b' + w + '\\b', 'i').test(code), `${migFile}: найдено «${w}» — разрешены только create or replace`);
  }

  // 2. В обеих функциях нет контактов клиента / заявителя
  const forbidden = [/'phone'/, /'name'/, /'full_name'/, /'email'/, /'company'/, /'get_method'/, /'address'/, /'comment'/,
                     /\bv_o\b/, /FROM public\.orders/i, /\bnew\.(phone|name|full_name|email|address|company|comment|get_method)\b/i, /👤/, /📞/, /📧/, /📍/];
  for (const fn of ['iva_tg_on_part', 'iva_tg_on_landlord']) {
    const body = fnBody(code, fn);
    for (const re of forbidden) {
      assert.ok(!re.test(body), `${migFile}: ${fn} снова отправляет контакты (${re}) — решение владельца №17`);
    }
    assert.match(body, /в кабинете: https:\/\/eclipsru\.github\.io\/Arenda-Nvk\/(chief|cabinet)\.html/,
      `${migFile}: ${fn} — нет ссылки в кабинет, где смотреть контакты`);
    assert.match(body, /SECURITY DEFINER/, `${migFile}: ${fn} потеряла SECURITY DEFINER — бот перестанет читать токен`);
    assert.match(body, /SET search_path TO 'public'/, `${migFile}: ${fn} потеряла search_path`);
    assert.match(body, /EXCEPTION WHEN OTHERS THEN/, `${migFile}: ${fn} без защиты — сбой Telegram сорвёт заявку`);
  }
  assert.match(fnBody(code, 'iva_tg_on_part'), /'admin:' \|\| lower\(new\.owner_email\)/,
    `${migFile}: пункт проката перестал получать уведомление о своей заявке`);

  // 3. Самопроверка на месте и ловит контакты
  assert.match(mig, /-- Самопроверка/, `${migFile}: нет самопроверки в конце`);
  assert.ok(mig.includes("''phone''"), `${migFile}: самопроверка не ищет телефон`);

  // 4. Снимок бота: откат на месте, секретов нет
  const snapFile = 'supabase/bot/iva_tg_snapshot_20261003.sql';
  const snap = read(snapFile);
  assert.match(snap, /ОТКАТ \(1 из 2\)[\s\S]*FUNCTION public\.iva_tg_on_landlord\(\)/, `${snapFile}: нет блока отката 1`);
  assert.match(snap, /ОТКАТ \(2 из 2\)[\s\S]*FUNCTION public\.iva_tg_on_part\(\)/, `${snapFile}: нет блока отката 2`);
  for (const [label, re] of [
    ['токен бота', /[0-9]{6,12}:[A-Za-z0-9_-]{25,}/],
    ['ключ vk_wall_post', /vkwp_[0-9a-f]{6,}/i],
    ['секретный ключ Supabase', /sb_secret_[A-Za-z0-9_-]{5,}|service_role/i],
    ['JWT-ключ', /eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}/],
  ]) {
    assert.ok(!re.test(snap), `${snapFile}: в снимок попал ${label}`);
  }

  console.log('Бот без контактов: в уведомлениях о заявке и анкете нет имени, телефона, адреса, email и компании; ' +
    'есть ссылка в кабинет и самопроверка; миграция только create or replace; снимок с откатом, секретов нет.');
} catch (err) {
  console.error(err && err.message ? err.message : err);
  process.exitCode = 1;
}

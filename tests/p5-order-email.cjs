// Сторож «письма клиенту о заявке» (этап П5, решение владельца от 03.10.2026: письма клиенту,
// без своего домена — с ящика на mail.ru через функцию Supabase order_email).
// Ловит: опасные команды в миграции; пропажу одноразового пропуска и срока его жизни;
// хранение email в очереди; утечку адресов в тексты ошибок и журнал функции; возможность
// задать адресата снаружи; пароль в коде; внешние библиотеки в функции (непроверяемы);
// пропажу защиты от сбоя; пропажу абзаца о письмах в политике.
// Запуск: node tests/p5-order-email.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const ROOT = path.resolve(__dirname, '..');
const read = f => fs.readFileSync(path.join(ROOT, f), 'utf8');

try {
  const mf = 'supabase/migrations/20261003_order_email.sql';
  const mig = read(mf);
  const code = mig.replace(/--[^\n]*/g, '');
  for (const w of ['drop', 'truncate', 'delete', 'alter table [^;]* drop', 'grant', 'revoke']) {
    assert.ok(!new RegExp('\\b' + w + '\\b', 'i').test(code), `${mf}: найдено «${w}» — только create if not exists / create or replace`);
  }
  assert.match(code, /CREATE TABLE IF NOT EXISTS public\.iva_email_jobs/, `${mf}: очередь должна создаваться через IF NOT EXISTS`);
  assert.match(code, /ALTER TABLE public\.iva_email_jobs ENABLE ROW LEVEL SECURITY/, `${mf}: очередь открыта посетителям (нет RLS)`);
  const table = code.slice(code.indexOf('CREATE TABLE IF NOT EXISTS public.iva_email_jobs'), code.indexOf(');', code.indexOf('CREATE TABLE IF NOT EXISTS public.iva_email_jobs')));
  assert.ok(!/\b(email|to_email|user_email|name|phone|address)\b/i.test(table), `${mf}: в очереди писем появилось поле с персональными данными`);
  assert.match(table, /dedup\s+text\s+NOT NULL UNIQUE/, `${mf}: нет защиты от повторных писем (dedup unique)`);

  const trg = code.slice(code.indexOf('FUNCTION public.iva_email_on_part('), code.indexOf('FUNCTION public.iva_email_take('));
  assert.match(trg, /exception when others then/i, `${mf}: сбой почты сорвёт сохранение заявки — нет перехвата`);
  assert.match(trg, /gen_random_uuid\(\)/, `${mf}: пропуск не случайный`);
  assert.match(trg, /on conflict \(dedup\) do nothing/i, `${mf}: повторный статус пошлёт повторное письмо`);
  assert.ok(!/user_email|'to'/.test(trg), `${mf}: триггер передаёт адрес в запросе — адрес должен браться только в iva_email_take`);
  assert.match(trg, /jsonb_build_object\('id', v_id, 'token', v_token\)/, `${mf}: в функцию должны уходить только номер и пропуск`);
  assert.match(trg, /new\.status in \('rented', 'closed'\)/, `${mf}: письма должны быть только на «выдал» и «вернули»`);

  const take = code.slice(code.indexOf('FUNCTION public.iva_email_take('), code.indexOf('FUNCTION public.iva_email_done('));
  assert.match(take, /token = p_token and status = 'pending'/, `${mf}: письмо выдаётся без пропуска или повторно`);
  assert.match(take, /interval '1 day'/, `${mf}: у пропуска нет срока жизни`);
  assert.match(take, /if not found then\s+return null;/i, `${mf}: на чужой пропуск должно возвращаться пусто`);
  assert.match(take, /Рекламы не присылаем/, `${mf}: из письма пропала строка «рекламы не присылаем»`);
  assert.ok(!/o\.(name|phone|address|comment)\b/.test(take), `${mf}: в письмо попали имя/телефон/адрес/комментарий — не нужны`);
  const done = code.slice(code.indexOf('FUNCTION public.iva_email_done('), code.indexOf('CREATE OR REPLACE TRIGGER'));
  assert.match(done, /regexp_replace\(.*'\\S\+@\\S\+', '<email>', 'g'\)/, `${mf}: адреса из текста ошибок не вырезаются`);
  assert.match(code, /CREATE OR REPLACE TRIGGER trg_iva_email_part\s+AFTER INSERT OR UPDATE OF status ON public\.order_parts/, `${mf}: нет триггера на заявках`);
  assert.match(mig, /-- Самопроверка/, `${mf}: нет самопроверки`);
  assert.ok(!/sb_secret_|service_role|eyJ[A-Za-z0-9_-]{20,}\./.test(mig), `${mf}: секретный ключ в миграции`);

  const ff = 'supabase/functions/order_email/index.ts';
  const fn = read(ff);
  assert.ok(!/^\s*import\s/m.test(fn), `${ff}: появились внешние библиотеки — в среде Deno их работа не проверена (nodemailer там рвал TLS)`);
  assert.match(fn, /Deno\.connectTls\(/, `${ff}: письмо должно уходить по шифрованному соединению (порт 465)`);
  assert.match(fn, /Deno\.env\.get\("SMTP_PASS"\)/, `${ff}: пароль почты должен браться из секретов Supabase`);
  assert.ok(!/SMTP_PASS[^\n]*=\s*["'][^"']{4,}["']/.test(fn) && !/pass:\s*["'][^"']+["']/.test(fn), `${ff}: пароль вписан в код`);
  assert.ok(!/sb_secret_|service_role|eyJ[A-Za-z0-9_-]{20,}\./.test(fn), `${ff}: секретный ключ в функции`);
  assert.match(fn, /rpc\("iva_email_take"/, `${ff}: адрес и текст должны браться из базы по пропуску`);
  assert.match(fn, /\/\^\[0-9a-f\]\{64\}\$\/\.test\(token\)/, `${ff}: не проверяется формат пропуска`);
  assert.match(fn, /to: job\.to/, `${ff}: адресат должен браться только из ответа базы`);
  assert.ok(!/body\.to|\{ id, token, to|req\.json\(\)\)\.to/.test(fn), `${ff}: адресата можно задать снаружи`);
  assert.ok(!/console\.(log|error)\([^)]*job\.(to|text|subject)/.test(fn), `${ff}: адрес или текст письма пишется в журнал функции`);
  assert.match(fn, /\[\\r\\n<>\]/, `${ff}: нет защиты от подстановки заголовков в адрес`);
  assert.match(fn, /Content-Type: text\/plain; charset=UTF-8/, `${ff}: письмо без кодировки UTF-8 — кириллица сломается`);

  const pr = read('privacy.html');
  assert.match(pr, /<h3>Письма о заявке<\/h3>/, 'privacy.html: нет абзаца о письмах о заявке');
  assert.match(pr, /mail\.ru \(российский почтовый сервис\)/, 'privacy.html: не назван почтовый сервис, через который уходят письма');
  assert.match(pr, /рекламы не присылаем/i, 'privacy.html: не сказано, что писем-рекламы нет');

  console.log('Письма клиенту: одноразовые пропуска, адрес только из базы, без ПД в очереди и журнале, без внешних библиотек, политика обновлена');
} catch (e) {
  console.error('✘ ' + e.message);
  process.exit(1);
}

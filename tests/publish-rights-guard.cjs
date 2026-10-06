// Сторож «права на публикацию объявления» (06.10.2026).
//
// История: активный админ eclipsik.ru@mail.ru получал «не опубликовано: нет прав на
// публикацию (код 42501)», хотя по базе он активный админ, а повторный вход не помогал.
// Причина — правило доступа tools_admin: оно сравнивает почту из токена с owner_email
// и требует запись в admins. Отказ возможен в четырёх местах, и миграция
// supabase/migrations/20261006_publish_rights_fix.sql закрывает все четыре.
//
// Этот сторож следит, чтобы починку не потеряли:
//   1) миграция на месте, повторяемая и не удаляет данные;
//   2) в ней остались все четыре защиты (иначе 42501 вернётся);
//   3) есть блок ОТКАТ;
//   4) стенд tools/publish-rights-stand.py подключён к tools/check.sh;
//   5) страница диагностики на месте и стучится в нужные функции.
// Запуск: node tests/publish-rights-guard.cjs
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const ROOT = path.resolve(__dirname, '..');
const MIG = 'supabase/migrations/20261006_publish_rights_fix.sql';

const mig = fs.readFileSync(path.join(ROOT, MIG), 'utf8');
// Для проверок «ничего не удаляем» убираем комментарии: в них drop/truncate допустимы.
const sql = mig.replace(/--[^\n]*/g, '').toLowerCase();

// 1. Ничего не удаляет данные
for (const bad of ['drop table', 'truncate', 'drop schema', 'drop database', 'delete from']) {
  assert.ok(!sql.includes(bad), `${MIG}: найдено «${bad}» — миграция не должна удалять данные`);
}
assert.ok(sql.includes('create or replace function'), `${MIG}: функции должны пересоздаваться через create or replace`);
assert.ok(sql.includes('begin;') && sql.includes('commit;'), `${MIG}: нужна транзакция begin/commit`);

// 2. Все четыре защиты на месте
const guards = [
  ['почта из auth.users, если в токене её нет', /from\s+auth\.users\s+u\s+where\s+u\.id\s*=\s*auth\.uid\(\)/],
  ['сравнение почты через lower(btrim(...))', /lower\(btrim\(/],
  ['триггер подставляет владельца объявления', /create trigger iva_tools_owner_default[\s\S]*before insert on public\.tools/],
  ['явное право insert для authenticated', /grant select, insert, update, delete on public\.tools to authenticated/],
  ['правило tools_admin пересоздаётся', /create policy tools_admin on public\.tools for all to authenticated/],
];
for (const [name, re] of guards) {
  assert.ok(re.test(sql), `${MIG}: пропала защита «${name}» — отказ 42501 вернётся`);
}

// 3. Защита не ослаблена: правило по-прежнему требует админа и свою почту
assert.ok(/using\s*\(\s*public\.fn_is_admin\(\)/.test(sql), `${MIG}: правило должно требовать public.fn_is_admin()`);
assert.ok(/with check\s*\(\s*public\.fn_is_admin\(\)/.test(sql), `${MIG}: with check должен требовать public.fn_is_admin()`);
assert.ok(sql.includes('public.fn_is_chief()'), `${MIG}: право главного админа публиковать в любую почту потеряно`);

// 4. Диагностика и откат
assert.ok(/create or replace function public\.iva_whoami\(\)/.test(sql), `${MIG}: нет функции iva_whoami()`);
assert.ok(/create or replace function public\.iva_diag_schema\(\)/.test(sql), `${MIG}: нет функции iva_diag_schema()`);
assert.ok(mig.includes('ОТКАТ'), `${MIG}: нет закомментированного блока «ОТКАТ»`);

// 5. Стенд подключён к проверке проекта
const stand = fs.readFileSync(path.join(ROOT, 'tools/publish-rights-stand.py'), 'utf8');
assert.ok(stand.includes('20261006_publish_rights_fix.sql'), 'стенд должен применять именно эту миграцию');
for (const c of ['в токене НЕТ почты', 'НЕ прислал owner_email', 'пробелом в admins.email', 'аноним', 'неактивный админ']) {
  assert.ok(stand.includes(c), `стенд: пропал случай «${c}»`);
}
const check = fs.readFileSync(path.join(ROOT, 'tools/check.sh'), 'utf8');
assert.ok(check.includes('tools/publish-rights-stand.py'), 'tools/check.sh: стенд прав на публикацию не подключён');

// 6. Страница диагностики
const page = fs.readFileSync(path.join(ROOT, 'tools/diag-prava.html'), 'utf8');
for (const [what, needle] of [['вход через Supabase', '/auth/v1/token?grant_type=password'],
                              ['что видит база', '/rest/v1/rpc/iva_whoami'],
                              ['настоящая публикация', "'/rest/v1/tools'"],
                              ['схема прав', '/rest/v1/rpc/iva_diag_schema']]) {
  assert.ok(page.includes(needle), `tools/diag-prava.html: нет «${what}» (${needle})`);
}
assert.ok(page.includes('type="password"'), 'tools/diag-prava.html: пароль должен вводиться в поле type="password"');

console.log('✔ publish-rights-guard: миграция, стенд, проверка и страница диагностики на месте');

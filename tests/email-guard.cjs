// Run: node tests/email-guard.cjs
// Проверяет модуль проверки email без браузера.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(
  fs.readFileSync(path.join(__dirname, '..', 'assets', 'email-guard.js'), 'utf8'),
  sandbox,
  { filename: 'email-guard.js' }
);
const G = sandbox.IvaEmailGuard;
assert.equal(typeof G.check, 'function');
assert.equal(typeof G.isConfirmed, 'function');
assert.equal(typeof G.resendConfirm, 'function');
assert.ok(G.NOT_CONFIRMED_MSG.length > 10);

// Реальные адреса проходят (регистр и пробелы не страшны).
for (const ok of ['ivan@mail.ru', 'user.name+tag@yandex.ru', 'a_b-c.d@gmail.com', 'x@eclips.ru', '  Ivan@Mail.RU  ']) {
  assert.equal(G.check(ok), '', 'must pass: ' + ok);
}
// Кривые адреса режутся.
for (const bad of ['', '   ', 'no-at-sign', 'a@b', 'a@b.c', 'a b@c.ru', 'a..b@c.ru', '.a@c.ru',
    'a@c.ru.', 'a@.ru', 'a@b..ru', 'a@b.c!', '@c.ru', 'a@', 'a@-b.ru', 'a@b-.ru',
    'x'.repeat(250) + '@mail.ru', 'a@' + 'b'.repeat(64) + '.ru']) {
  assert.notEqual(G.check(bad), '', 'must fail: ' + bad);
}
// Одноразовые ящики режутся, включая поддомены.
for (const d of ['q@tempmail.com', 'q@mail.tempmail.com', 'q@10minutemail.com',
    'q@yopmail.com', 'q@mailinator.com', 'q@temp-mail.org', 'q@guerrillamail.com']) {
  assert.match(G.check(d), /Одноразовые/, 'must block disposable: ' + d);
}
// Похожие, но честные домены проходят.
assert.equal(G.check('q@mytempmail.com'), '');
assert.equal(G.check('q@tempmail.company'), '');

// Флаг подтверждения.
assert.equal(G.isConfirmed(null), false);
assert.equal(G.isConfirmed({}), false);
assert.equal(G.isConfirmed({ email_confirmed_at: null }), false);
assert.equal(G.isConfirmed({ email_confirmed_at: '2026-01-01T00:00:00Z' }), true);

console.log('email-guard OK: формат, одноразовые домены, флаг подтверждения');

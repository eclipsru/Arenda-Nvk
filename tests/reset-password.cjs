// Сторож бага владельца 04.10.2026: «ссылка из письма "Забыли пароль" ведёт на сайт».
// Письмо Supabase ведёт на главную с #access_token=…&type=recovery. Проверяем:
//  1. перехватчик assets/recovery.js подключён на всех страницах, куда может вести ссылка,
//     и переносит на reset-password.html, сохраняя ключ (и не трогает обычные адреса);
//  2. reset-password.html: убирает ключ из адресной строки, проверяет пароли,
//     шлёт PUT /auth/v1/user с Bearer-ключом из ссылки, понятно сообщает об устаревшей ссылке.
// Запуск: node tests/reset-password.cjs
const fs = require('fs'), vm = require('vm'), assert = require('node:assert/strict');
const R = p => fs.readFileSync(require('path').join(__dirname, '..', p), 'utf8');

// 1. Подключение
for (const f of ['index.html', 'cabinet.html', 'account.html', 'kabinet.html', 'app.html', 'catalog.html', '404.html'])
  assert.match(R(f), /<script src="[^"]*assets\/recovery\.js[^"]*"><\/script>/, f + ': нет перехватчика recovery.js');
assert.match(R('404.html'), /src="\/Arenda-Nvk\/assets\/recovery\.js/, '404.html: путь к перехватчику должен быть абсолютным');

const rec = R('assets/recovery.js');
function runRec(pathname, hash) {
  let went = null;
  vm.runInNewContext(rec, { location: { pathname, hash, replace: u => { went = u; } } });
  return went;
}
assert.equal(runRec('/Arenda-Nvk/', '#access_token=abc&type=recovery'), '/Arenda-Nvk/reset-password.html#access_token=abc&type=recovery');
assert.equal(runRec('/Arenda-Nvk/cabinet.html', '#error=access_denied&error_code=otp_expired'), '/Arenda-Nvk/reset-password.html#error=access_denied&error_code=otp_expired');
assert.equal(runRec('/Arenda-Nvk/', '#catalog'), null, 'обычный якорь не должен перекидывать');
assert.equal(runRec('/Arenda-Nvk/', ''), null);
assert.equal(runRec('/Arenda-Nvk/reset-password.html', '#access_token=a&type=recovery'), null, 'не зацикливаться');

// 2. Страница нового пароля — выполняем её скрипт на подставном DOM
const html = R('reset-password.html');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
function page(hash) {
  const els = {}; const el = id => els[id] || (els[id] = { id, hidden: id === 'rpForm' || id === 'rpAfter', value: '', textContent: '', className: '', disabled: false, focus() {}, addEventListener(ev, fn) { this['on' + ev] = fn; } });
  const st = { replaced: null, calls: [], resp: { ok: true, status: 200, json: async () => ({}) } };
  const ctx = {
    document: { getElementById: el },
    location: { hash, pathname: '/Arenda-Nvk/reset-password.html' },
    history: { replaceState: (a, b, u) => { st.replaced = u; } },
    fetch: async (u, o) => { st.calls.push({ u, o }); return st.resp; },
    JSON, console, decodeURIComponent
  };
  vm.runInNewContext(script, ctx);
  st.el = el;
  st.submit = async () => { await el('rpForm').onsubmit({ preventDefault() {} }); };
  return st;
}
(async () => {
  let p = page('#access_token=TOK123&expires_in=3600&type=recovery');
  assert.equal(p.replaced, '/Arenda-Nvk/reset-password.html', 'ключ должен убираться из адреса');
  assert.equal(p.el('rpForm').hidden, false, 'форма должна показаться');
  p.el('p1').value = '12345'; p.el('p2').value = '12345'; await p.submit();
  assert.equal(p.calls.length, 0); assert.match(p.el('rpMsg').textContent, /не короче 6/);
  p.el('p1').value = 'abcdef1'; p.el('p2').value = 'abcdef2'; await p.submit();
  assert.equal(p.calls.length, 0); assert.match(p.el('rpMsg').textContent, /не совпадают/);
  p.el('p2').value = 'abcdef1'; await p.submit();
  assert.equal(p.calls.length, 1);
  const c = p.calls[0];
  assert.equal(c.u, 'https://wdxdeatphizclskfmfxi.supabase.co/auth/v1/user');
  assert.equal(c.o.method, 'PUT');
  assert.equal(c.o.headers.Authorization, 'Bearer TOK123');
  assert.match(c.o.headers.apikey, /^sb_publishable_/, 'только публичный ключ');
  assert.deepEqual(JSON.parse(c.o.body), { password: 'abcdef1' });
  assert.match(p.el('rpMsg').textContent, /Пароль изменён/);
  assert.equal(p.el('rpAfter').hidden, false);

  p = page('#access_token=OLD&type=recovery');
  p.resp = { ok: false, status: 401, json: async () => ({ msg: 'expired' }) };
  p.el('p1').value = p.el('p2').value = 'abcdef1'; await p.submit();
  assert.match(p.el('rpMsg').textContent, /устарела/);

  p = page('#error=access_denied&error_code=otp_expired&error_description=x');
  assert.equal(p.el('rpForm').hidden, true); assert.match(p.el('rpMsg').textContent, /устарела/);

  p = page('');
  assert.equal(p.el('rpForm').hidden, true); assert.match(p.el('rpMsg').textContent, /Забыли пароль/);

  assert.doesNotMatch(html, /service_role|localStorage\.setItem/, 'ключ не сохраняем, service_role не используем');
  console.log('OK reset-password: перехват на 7 страницах, 5 адресов, страница — 8 сценариев');
})().catch(e => { console.error('FAIL reset-password:', e.message); process.exit(1); });
